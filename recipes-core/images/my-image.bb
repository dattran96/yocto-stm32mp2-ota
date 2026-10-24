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
    data-journal \
    cryptsetup \
    root-ssh-key \
    health-check \
    wifi-config \
"

IMAGE_FEATURES += "ssh-server-dropbear"
# Read-only rootfs (preparation for dm-verity): fstab mounts / as ro,
# /var/lib, /var/cache and /var/spool become tmpfs copies (volatile-binds),
# Dropbear keeps its host key on /data (dropbear bbappend).
IMAGE_FEATURES += "read-only-rootfs"
# STM32MP: two rootfs partitions (rootfs1/rootfs2) for RAUC A/B updates.
# Same as ST's default in st-machine-image-partition-stm32mp.inc,
# but with copy = 2. Ignored on machines without ST's flash layout class.
PARTITIONS_IMAGES[rootfs] = "${STM32MP_ROOTFS_IMAGE},${STM32MP_ROOTFS_LABEL},,${STM32MP_ROOTFS_SIZE},FileSystem,2"

# dm-verity: append a hash tree to ST's split rootfs image and write the
# root hash to .verity-params (meta-oe image_types_verity.bbclass).
# The hash is checked in 4 KB blocks, so the ext4 needs 4 KB blocks too
# (mke2fs picks 1 KB below 512 MB). ST uses the same options for every
# partition image, so bootfs and userfs get 4 KB blocks as well.
inherit image_types_verity
IMAGE_FSTYPES:append:stm32mpcommon = " verity"
VERITY_IMAGE_FSTYPE:stm32mpcommon = "stsplitpartitions"
VERITY_INPUT_IMAGE:stm32mpcommon = "${IMGDEPLOYDIR}/${IMAGE_BASENAME}${IMAGE_MACHINE_SUFFIX}.splitted-rootfs.ext4"
VERITY_SALT = "b5e06473a0642a14a6c7ab46e9fb3f5ae4617ef372d3f008a71fa143d683bcc5"
EXTRA_IMAGECMD:ext4:append = " -b 4096"

# STM32MP: one boot partition per slot (bootfs1/bootfs2) with the signed
# FIT from my-bootfs (kernel, DTB, dm-verity initramfs). vendorfs is
# empty and unused here; without it, rootfs1, rootfs2 and userfs keep
# their partition numbers 10, 11 and 12.
ST_VENDORFS = "0"
PARTITIONS_IMAGES[bootfs] = "${STM32MP_BOOTFS_IMAGE},${STM32MP_BOOTFS_LABEL},${STM32MP_BOOTFS_MOUNTPOINT},${STM32MP_BOOTFS_SIZE},${STM32MP_BOOTFS_TYPE},2"
FLASHLAYOUT_PARTITION_BIN2LOAD:bootfs = "my-bootfs-${MACHINE}.ext4"
# rootfs1/rootfs2 get the image with the dm-verity hash tree appended
# (image_types_verity); the initramfs expects the tree right after the data.
FLASHLAYOUT_PARTITION_BIN2LOAD:rootfs = "my-image-${MACHINE}.splitted-rootfs.ext4.verity"
do_create_flashlayout_config[depends] += "my-bootfs:do_image_complete"

# The SDK is for Linux applications. ST also adds ARM's prebuilt bare-metal
# compilers (M33, TF-A); their RPMs need host libraries that no SDK package
# provides (libpython3.8.so ...), so dnf cannot install them.
TOOLCHAIN_HOST_TASK:remove = " \
    nativesdk-gcc-arm-none-eabi nativesdk-gcc-arm-none-eabi-staticdev \
    nativesdk-gcc-aarch64-none-elf nativesdk-gcc-aarch64-none-elf-staticdev \
"
