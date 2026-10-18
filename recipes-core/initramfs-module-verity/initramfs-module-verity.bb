SUMMARY = "initramfs-framework module: open the rootfs slot with dm-verity"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://verity"

RDEPENDS:${PN} = "initramfs-framework-base cryptsetup util-linux-blkid"

do_install() {
    install -d ${D}/init.d
    install -m 0755 ${WORKDIR}/verity ${D}/init.d/80-verity
}

FILES:${PN} = "/init.d/80-verity"
