SUMMARY = "My first custom image"
LICENSE = "MIT"

inherit core-image

IMAGE_INSTALL = " \
    packagegroup-core-boot \
    ${CORE_IMAGE_EXTRA_INSTALL} \
    strace \
    file \
    hello \
    microsocks \
    sysmon \
    kernel-module-exfat \
    mymod \
    rauc \
"

IMAGE_FEATURES += "ssh-server-dropbear"
# STM32MP: two rootfs partitions (rootfs1/rootfs2) for RAUC A/B updates.
# Same as ST's default in st-machine-image-partition-stm32mp.inc,
# but with copy = 2. Ignored on machines without ST's flash layout class.
PARTITIONS_IMAGES[rootfs] = "${STM32MP_ROOTFS_IMAGE},${STM32MP_ROOTFS_LABEL},,${STM32MP_ROOTFS_SIZE},FileSystem,2"
