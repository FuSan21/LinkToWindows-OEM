/*
 * SPDX-License-Identifier: MIT
 *
 * ltw_virtual_keyboard — "vendor.virtual_keyboard" for Link to Windows.
 *
 * Re-implementation of OnePlus' /system/bin/virtual_keyboard (OxygenOS 15). It serves
 * com.microsoftsdk.IVirtualKeyboardService to com.microsoft.deviceintegrationservice, which turns
 * PC keystrokes into a real uinput keyboard on the phone (so Android applies key layouts,
 * modifiers, key repeat and hides the soft keyboard, exactly as for a hardware keyboard).
 *
 * Interface (client: deviceintegrationservice com/microsoftsdk/IVirtualKeyboardService.java):
 *   1 int attach(IBinder token, String keyboardName)  create the uinput keyboard
 *   2 int detach(IBinder token)                       destroy it
 *   3 int sendKey(IBinder token, int value, int code)  EV_KEY code=value (1 down, 0 up) + SYN
 * Every reply = no-exception status header + int32 (0 = success).
 * deviceintegrationservice finds it with ServiceManager.waitForService("vendor.virtual_keyboard").
 *
 * Single source for both builds:
 *   - Lineage (Soong, -DLTW_PLATFORM): links libbinder_ndk platform APIs directly, and authorizes
 *     callers by deviceintegration.permission.MANAGE_CROSS_DEVICE through libbinder.
 *   - Rooted-stock emulation (NDK): binder_manager/binder_process are not in the NDK, so those few
 *     functions are resolved from the device's libbinder_ndk.so with dlsym. The allowed caller UID
 *     is passed as argv[1].
 */
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <mutex>
#include <string>

#ifdef LTW_PLATFORM
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <utils/String16.h>
#endif

#define LOG_TAG "ltw_virtual_keyboard"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kDescriptor = "com.microsoftsdk.IVirtualKeyboardService";
constexpr const char* kServiceName = "vendor.virtual_keyboard";
constexpr transaction_code_t TX_ATTACH = FIRST_CALL_TRANSACTION + 0;
constexpr transaction_code_t TX_DETACH = FIRST_CALL_TRANSACTION + 1;
constexpr transaction_code_t TX_SENDKEY = FIRST_CALL_TRANSACTION + 2;

// Result codes (0 = OK, like OnePlus; the client only tests == 0).
constexpr int32_t OK = 0;
constexpr int32_t ERR_PERMISSION = -1;
constexpr int32_t ERR_STATE = -2;
constexpr int32_t ERR_ARG = -3;
constexpr int32_t ERR_UINPUT = -4;

// ---------------------------------------------------------------- platform-only binder APIs
#ifdef LTW_PLATFORM
binder_status_t addService(AIBinder* b, const char* n) { return AServiceManager_addService(b, n); }
void setMaxThreads(uint32_t n) { ABinderProcess_setThreadPoolMaxThreadCount(n); }
void startThreadPool() { ABinderProcess_startThreadPool(); }
void joinThreadPool() { ABinderProcess_joinThreadPool(); }
#else
template <typename F>
F sym(const char* name) {
    static void* lib = dlopen("libbinder_ndk.so", RTLD_NOW);
    void* p = lib ? dlsym(lib, name) : nullptr;
    if (!p) {
        ALOGE("missing %s in libbinder_ndk.so", name);
        abort();
    }
    return reinterpret_cast<F>(p);
}
binder_status_t addService(AIBinder* b, const char* n) {
    return sym<binder_status_t (*)(AIBinder*, const char*)>("AServiceManager_addService")(b, n);
}
void setMaxThreads(uint32_t n) {
    sym<bool (*)(uint32_t)>("ABinderProcess_setThreadPoolMaxThreadCount")(n);
}
void startThreadPool() { sym<void (*)()>("ABinderProcess_startThreadPool")(); }
void joinThreadPool() { sym<void (*)()>("ABinderProcess_joinThreadPool")(); }
#endif

// ---------------------------------------------------------------- state
struct State {
    std::mutex lock;
    int fd = -1;                 // uinput fd while attached
    AIBinder* client = nullptr;  // attached client's token (strong ref held)
    AIBinder_DeathRecipient* death = nullptr;
    uid_t allowedUid = static_cast<uid_t>(-1);  // emulation: deviceintegrationservice UID
} g;

bool sameBinder(AIBinder* a, AIBinder* b) {
    return a && b && !AIBinder_lt(a, b) && !AIBinder_lt(b, a);
}

bool callerAllowed() {
    const uid_t uid = AIBinder_getCallingUid();
#ifdef LTW_PLATFORM
    const bool ok = android::checkCallingPermission(
            android::String16("deviceintegration.permission.MANAGE_CROSS_DEVICE"));
#else
    const bool ok = uid == g.allowedUid;
#endif
    if (!ok) {
        ALOGW("permission denied to pid=%d uid=%d", AIBinder_getCallingPid(), uid);
    }
    return ok;
}

// ---------------------------------------------------------------- uinput
int openKeyboard(const std::string& name) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("couldn't open uinput (errno=%d)", errno);
        return -1;
    }
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        ALOGE("failed to enable event types (errno=%d)", errno);
        close(fd);
        return -1;
    }
    // A full keyboard (=> Android classes KEYBOARD|ALPHAKEY, an alphabetic hardware keyboard), but
    // without the BTN_* button ranges, which would also make it a gamepad/stylus/touch device.
    for (int code = 1; code < KEY_CNT; code++) {
        if ((code >= BTN_MISC && code < KEY_OK) ||             // 0x100-0x15f joystick/gamepad/digi
            (code >= BTN_TRIGGER_HAPPY && code < KEY_CNT)) {   // 0x2c0-     trigger-happy buttons
            continue;
        }
        ioctl(fd, UI_SET_KEYBIT, code);  // some codes may be rejected; that's fine
    }
    struct uinput_setup setup = {};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x045e;   // Microsoft
    setup.id.product = 0x4c54;  // "LT"
    setup.id.version = 1;
    strncpy(setup.name, name.c_str(), UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        ALOGE("failed to create uinput device (errno=%d)", errno);
        close(fd);
        return -1;
    }
    return fd;
}

bool writeEvent(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev = {};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) != static_cast<ssize_t>(sizeof(ev))) {
        ALOGE("failed to write event 0x%X, 0x%X, 0x%X (errno=%d)", type, code, value, errno);
        return false;
    }
    return true;
}

void detachLocked() {
    if (g.fd >= 0) {
        ioctl(g.fd, UI_DEV_DESTROY);
        close(g.fd);
        g.fd = -1;
    }
    if (g.client) {
        AIBinder_unlinkToDeath(g.client, g.death, nullptr);
        AIBinder_decStrong(g.client);
        g.client = nullptr;
    }
}

void onClientDied(void* /*cookie*/) {
    std::lock_guard<std::mutex> l(g.lock);
    ALOGW("client died, detaching keyboard");
    if (g.fd >= 0) {
        ioctl(g.fd, UI_DEV_DESTROY);
        close(g.fd);
        g.fd = -1;
    }
    if (g.client) {
        AIBinder_decStrong(g.client);
        g.client = nullptr;
    }
}

// ---------------------------------------------------------------- binder methods
int32_t attach(AIBinder* token, const std::string& name) {
    if (!token) return ERR_ARG;
    if (name.empty()) {
        ALOGW("Keyboard name should not be empty");
        return ERR_ARG;
    }
    std::lock_guard<std::mutex> l(g.lock);
    if (g.client) {
        ALOGW(sameBinder(g.client, token) ? "Client attached twice"
                                          : "Another client is already attached");
        return ERR_STATE;
    }
    const int fd = openKeyboard(name);
    if (fd < 0) return ERR_UINPUT;
    if (AIBinder_linkToDeath(token, g.death, nullptr) != STATUS_OK) {
        ALOGW("client already dead");
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
        return ERR_STATE;
    }
    AIBinder_incStrong(token);
    g.client = token;
    g.fd = fd;
    ALOGI("attached keyboard \"%s\"", name.c_str());
    return OK;
}

int32_t detach(AIBinder* token) {
    std::lock_guard<std::mutex> l(g.lock);
    if (!g.client) {
        ALOGW("no client attached");
        return ERR_STATE;
    }
    if (!sameBinder(g.client, token)) return ERR_PERMISSION;
    detachLocked();
    ALOGI("detached keyboard");
    return OK;
}

int32_t sendKey(AIBinder* token, int32_t value, int32_t code) {
    if (code < 0 || code >= KEY_CNT) {
        ALOGW("key 0x%X out of range [0,0x%X)", code, KEY_CNT);
        return ERR_ARG;
    }
    std::lock_guard<std::mutex> l(g.lock);
    if (!g.client || g.fd < 0) {
        ALOGW("no client attached");
        return ERR_STATE;
    }
    if (!sameBinder(g.client, token)) return ERR_PERMISSION;
    if (!writeEvent(g.fd, EV_KEY, static_cast<uint16_t>(code), value) ||
        !writeEvent(g.fd, EV_SYN, SYN_REPORT, 0)) {
        return ERR_UINPUT;
    }
    return OK;
}

// ---------------------------------------------------------------- binder plumbing
void* onCreate(void* args) { return args; }
void onDestroy(void* /*userData*/) {}

bool stringAllocator(void* stringData, int32_t length, char** buffer) {
    auto* s = static_cast<std::string*>(stringData);
    if (length < 0) {  // null string
        s->clear();
        *buffer = nullptr;
        return true;
    }
    s->resize(length - 1);  // length includes the terminating NUL
    *buffer = s->data();
    return true;
}

binder_status_t writeResult(AParcel* out, int32_t result) {
    AStatus* ok = AStatus_newOk();
    binder_status_t st = AParcel_writeStatusHeader(out, ok);
    AStatus_delete(ok);
    if (st != STATUS_OK) return st;
    return AParcel_writeInt32(out, result);
}

binder_status_t onTransact(AIBinder* /*binder*/, transaction_code_t code, const AParcel* in,
                           AParcel* out) {
    // libbinder_ndk has already checked the interface token (descriptor) here.
    if (code != TX_ATTACH && code != TX_DETACH && code != TX_SENDKEY) {
        return STATUS_UNKNOWN_TRANSACTION;
    }
    if (!callerAllowed()) return writeResult(out, ERR_PERMISSION);

    AIBinder* token = nullptr;
    binder_status_t st = AParcel_readStrongBinder(in, &token);
    if (st != STATUS_OK) return st;

    int32_t result = ERR_ARG;
    if (code == TX_ATTACH) {
        std::string name;
        st = AParcel_readString(in, &name, stringAllocator);
        if (st == STATUS_OK) result = attach(token, name);
    } else if (code == TX_DETACH) {
        result = detach(token);
    } else {
        int32_t value = 0, key = 0;
        st = AParcel_readInt32(in, &value);
        if (st == STATUS_OK) st = AParcel_readInt32(in, &key);
        if (st == STATUS_OK) result = sendKey(token, value, key);
    }
    if (token) AIBinder_decStrong(token);
    if (st != STATUS_OK) return st;
    return writeResult(out, result);
}

}  // namespace

int main(int argc, char** argv) {
#ifndef LTW_PLATFORM
    if (argc < 2) {
        ALOGE("usage: %s <deviceintegrationservice uid>", argv[0]);
        return 2;
    }
    g.allowedUid = static_cast<uid_t>(strtoul(argv[1], nullptr, 10));
#else
    (void)argc;
    (void)argv;
#endif
    g.death = AIBinder_DeathRecipient_new(onClientDied);

    AIBinder_Class* clazz = AIBinder_Class_define(kDescriptor, onCreate, onDestroy, onTransact);
    AIBinder* service = AIBinder_new(clazz, nullptr);

    setMaxThreads(2);
    const binder_status_t st = addService(service, kServiceName);
    if (st != STATUS_OK) {
        ALOGE("Virtual keyboard service not added: %d", st);
        return 1;
    }
    ALOGI("%s registered (allowed uid=%d)", kServiceName, static_cast<int>(g.allowedUid));
    startThreadPool();
    joinThreadPool();
    return 0;
}
