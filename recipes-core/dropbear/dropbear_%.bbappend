# Own /etc/default/dropbear (key-only logins, host key on /data) and a
# drop-in so the host key is generated once, on /data
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://dropbearkey-data.conf"

do_install:append() {
    install -d ${D}${systemd_system_unitdir}/dropbearkey.service.d
    install -m 0644 ${WORKDIR}/dropbearkey-data.conf \
        ${D}${systemd_system_unitdir}/dropbearkey.service.d/data.conf
}

FILES:${PN} += "${systemd_system_unitdir}/dropbearkey.service.d"
