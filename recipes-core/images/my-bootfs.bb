SUMMARY = "Boot partition of a slot: the signed FIT with the dm-verity initramfs"
LICENSE = "MIT"

# No packages. The only file is the FIT that the kernel recipe builds
# with my-initramfs (kernel + DTB + initramfs with the root hash, signed).
PACKAGE_INSTALL = ""
IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
IMAGE_NAME_SUFFIX = ""

# ST adds partition images, a flash layout and a license summary to every
# image; not for this one. Must be set before "inherit image".
ENABLE_PARTITIONS_IMAGE = "0"
ENABLE_FLASHLAYOUT_CONFIG = "0"
ENABLE_IMAGE_LICENSE_SUMMARY = "0"

IMAGE_FSTYPES = "ext4"
# ST builds the ext4 label from IMAGE_NAME_SUFFIX, which is empty here.
# Same options as ST otherwise (^metadata_csum,^dir_index: U-Boot can't
# write ext4 with them).
EXTRA_IMAGECMD:ext4 = "-i 4096 -O ^metadata_csum,^dir_index -L bootfs"
inherit image

# U-Boot loads /fitImage from bootfs1 or bootfs2 (rauc_load).
do_rootfs[depends] += "virtual/kernel:do_deploy"
ROOTFS_POSTPROCESS_COMMAND += "install_fit"
install_fit() {
    install -m 0644 ${DEPLOY_DIR_IMAGE}/kernel/fitImage-my-initramfs-${MACHINE}-${MACHINE} \
        ${IMAGE_ROOTFS}/fitImage
}
