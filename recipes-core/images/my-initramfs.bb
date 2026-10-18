SUMMARY = "Initramfs that opens the rootfs slot with dm-verity"
LICENSE = "MIT"

# Only what /init needs: poky's initramfs-framework (its /init runs the
# scripts in /init.d/ in number order), BusyBox, veritysetup and blkid
# (blkid finds the slot partition by its PARTUUID from root=).
PACKAGE_INSTALL = "\
    initramfs-framework-base \
    initramfs-module-rootfs \
    initramfs-module-verity \
    ${VIRTUAL-RUNTIME_base-utils} \
    base-passwd \
    ${ROOTFS_BOOTSTRAP_INSTALL} \
"

IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
PACKAGE_EXCLUDE = "kernel-image-*"
# Only the packages listed above and what they really need (RDEPENDS).
# cryptsetup recommends dm-crypt kernel modules for encryption; dm-verity
# is built into the kernel.
NO_RECOMMENDATIONS = "1"
IMAGE_NAME_SUFFIX ?= ""

# ST adds partition images, a flash layout and a license summary to every
# image. None of them make sense for an initramfs. These must be set
# before "inherit core-image", which reads them.
ENABLE_PARTITIONS_IMAGE = "0"
ENABLE_FLASHLAYOUT_CONFIG = "0"
ENABLE_IMAGE_LICENSE_SUMMARY = "0"

IMAGE_FSTYPES = "${INITRAMFS_FSTYPES}"
inherit core-image

IMAGE_ROOTFS_SIZE = "8192"
IMAGE_ROOTFS_EXTRA_SPACE = "0"

# The root hash of my-image's rootfs, from image_types_verity. The
# dependency rebuilds this initramfs whenever the rootfs changes.
do_rootfs[depends] += "my-image:do_image_complete"
ROOTFS_POSTPROCESS_COMMAND += "install_verity_params"
install_verity_params() {
    install -m 0644 ${DEPLOY_DIR_IMAGE}/my-image-${MACHINE}.splitted-rootfs.ext4.verity-params \
        ${IMAGE_ROOTFS}${sysconfdir}/verity-params
}
