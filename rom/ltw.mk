# Link to Windows OEM integration - product makefile.
# With this repo cloned to vendor/ltw (see rom/local_manifest.xml), inherit it from your product,
# e.g. in vendor/extra/product.mk (LineageOS' official hook):
#   $(call inherit-product, vendor/ltw/rom/ltw.mk)

LTW_ROM_DIR := $(patsubst %/,%,$(dir $(lastword $(MAKEFILE_LIST))))

# Microsoft's APKs are not in this repo: fail early with a clear message instead of a Soong error.
$(foreach apk,DeviceIntegrationService LinktoWindows,\
    $(if $(wildcard $(LTW_ROM_DIR)/prebuilt/$(apk)/$(apk).apk),,\
        $(error Link to Windows: put $(apk).apk in $(LTW_ROM_DIR)/prebuilt/$(apk)/ (see vendor/ltw README, "Custom ROM"))))

PRODUCT_PACKAGES += \
    com.microsoft.device-private \
    CrossDeviceServiceBroker \
    DeviceIntegrationService \
    LinktoWindows \
    ltw_virtual_keyboard
