SUMMARY = "U-Boot boot script with A/B slot selection for RAUC"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

DEPENDS = "u-boot-mkimage-native"

SRC_URI = "file://boot.cmd"
S = "${WORKDIR}"

# Uses board-specific partition names and U-Boot variables
PACKAGE_ARCH = "${MACHINE_ARCH}"

# For UBOOT_ARCH (arm64)
inherit kernel-arch

do_compile() {
    mkimage -C none -A ${UBOOT_ARCH} -T script -d ${S}/boot.cmd ${B}/boot.scr
}

# /boot is split off into the bootfs partition by ST's image classes
do_install() {
    install -d ${D}/boot
    install -m 0644 ${B}/boot.scr ${D}/boot/
}

FILES:${PN} = "/boot"
