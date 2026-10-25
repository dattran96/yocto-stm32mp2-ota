FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# ST's kernel recipe doesn't merge .cfg files from SRC_URI the way
# linux-yocto does; it merges only the files listed in
# KERNEL_CONFIG_FRAGMENTS.
SRC_URI += "file://exfat.cfg"
KERNEL_CONFIG_FRAGMENTS:append = " ${WORKDIR}/exfat.cfg"

SRC_URI += "file://rauc.cfg"
KERNEL_CONFIG_FRAGMENTS:append = " ${@bb.utils.contains('DISTRO_FEATURES', 'rauc', '${WORKDIR}/rauc.cfg', '', d)}"

SRC_URI += "file://localversion.cfg"
KERNEL_CONFIG_FRAGMENTS:append = " ${WORKDIR}/localversion.cfg"
# Build a second FIT with the initramfs from my-initramfs (dm-verity root
# hash inside), signed together with kernel and DTB. Deployed as
# kernel/fitImage-my-initramfs-*; the FIT in /boot stays without it.
INITRAMFS_IMAGE = "my-initramfs"

# BME280 on the GPIO expansion connector (docs/24-i2c-sensor.md): i2c8 on,
# the sensor node in the board's device tree, and Linux's bmp280 driver
SRC_URI += "file://0001-arm64-dts-st-stm32mp257f-dk-BME280-on-i2c8.patch"
SRC_URI += "file://bmp280.cfg"
KERNEL_CONFIG_FRAGMENTS:append = " ${WORKDIR}/bmp280.cfg"
