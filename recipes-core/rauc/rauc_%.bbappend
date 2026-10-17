# systemd drop-in: start rauc.service only after /data is mounted.
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://rauc-data.conf"

do_install:append() {
    install -d ${D}${systemd_system_unitdir}/rauc.service.d
    install -m 0644 ${WORKDIR}/rauc-data.conf ${D}${systemd_system_unitdir}/rauc.service.d/
}

FILES:${PN}-service += "${systemd_system_unitdir}/rauc.service.d"
