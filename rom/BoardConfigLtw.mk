# Link to Windows OEM integration - board config (sepolicy).
# Include it from the device BoardConfig.mk or vendor/extra/BoardConfigExtra.mk:
#   include vendor/ltw/rom/BoardConfigLtw.mk
LTW_ROM_DIR := $(patsubst %/,%,$(dir $(lastword $(MAKEFILE_LIST))))
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(LTW_ROM_DIR)/sepolicy/private
