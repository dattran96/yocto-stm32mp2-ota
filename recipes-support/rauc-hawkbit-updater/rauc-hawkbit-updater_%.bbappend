# The config holds this device's hawkBit token, so it is not part of the
# image: /etc/rauc-hawkbit-updater/config.conf is a link to /data/hawkbit/,
# written on the board (docs/23-hawkbit.md). Without that file, the updater
# does not start.
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://hawkbit-data.conf"

do_install:append() {
    rm ${D}${sysconfdir}/${PN}/config.conf
    ln -s /data/hawkbit/config.conf ${D}${sysconfdir}/${PN}/config.conf

    install -d ${D}${systemd_system_unitdir}/${PN}.service.d
    install -m 0644 ${WORKDIR}/hawkbit-data.conf ${D}${systemd_system_unitdir}/${PN}.service.d/
}

FILES:${PN} += "${systemd_system_unitdir}/${PN}.service.d"
