SUMMARY = "Health check before RAUC marks the booted slot good"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://health-check file://health-check.service"
S = "${WORKDIR}"

inherit systemd
SYSTEMD_SERVICE:${PN} = "health-check.service"

do_install() {
    install -d ${D}${libexecdir} ${D}${systemd_system_unitdir}
    install -m 0755 ${WORKDIR}/health-check ${D}${libexecdir}/health-check
    install -m 0644 ${WORKDIR}/health-check.service ${D}${systemd_system_unitdir}
}
