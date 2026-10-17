SUMMARY = "Add an ext4 journal to the /data partition once"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://data-journal.service"

inherit systemd

SYSTEMD_SERVICE:${PN} = "data-journal.service"
RDEPENDS:${PN} = "e2fsprogs-tune2fs"

do_install() {
    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/data-journal.service ${D}${systemd_system_unitdir}/
}
