# Link to Windows OEM integration - product makefile.
# With this repo cloned to vendor/ltw (see rom/local_manifest.xml), inherit it from your product,
# e.g. in vendor/extra/product.mk (LineageOS' official hook):
#   $(call inherit-product, vendor/ltw/rom/ltw.mk)

PRODUCT_PACKAGES += \
    com.microsoft.device-private \
    CrossDeviceServiceBroker \
    DeviceIntegrationService \
    LinktoWindows \
    ltw_virtual_keyboard
