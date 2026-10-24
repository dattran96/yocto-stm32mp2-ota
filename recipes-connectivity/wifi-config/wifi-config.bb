SUMMARY = "WiFi on wlan0: wpa_supplicant with its settings on /data, DHCP by systemd-networkd"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# The network name and password are not part of the image or the repository:
# /etc/wpa_supplicant/wpa_supplicant-wlan0.conf is a link to /data/wifi/,
# written on the board (docs/22-wifi.md). Without that file, wpa_supplicant
# does not start.
SRC_URI = " \
    file://30-wlan0.network \
    file://wpa-data.conf \
    file://wait-online-any.conf \
"
S = "${WORKDIR}"

inherit allarch

do_install() {
    install -d ${D}${sysconfdir}/systemd/network
    install -m 0644 ${WORKDIR}/30-wlan0.network ${D}${sysconfdir}/systemd/network/

    install -d ${D}${sysconfdir}/wpa_supplicant
    ln -s /data/wifi/wpa_supplicant-wlan0.conf ${D}${sysconfdir}/wpa_supplicant/wpa_supplicant-wlan0.conf

    # Start wpa_supplicant for wlan0 at boot (the instance of poky's template unit)
    install -d ${D}${sysconfdir}/systemd/system/multi-user.target.wants
    ln -s ${systemd_system_unitdir}/wpa_supplicant@.service \
        ${D}${sysconfdir}/systemd/system/multi-user.target.wants/wpa_supplicant@wlan0.service
    install -d ${D}${sysconfdir}/systemd/system/wpa_supplicant@wlan0.service.d
    install -m 0644 ${WORKDIR}/wpa-data.conf ${D}${sysconfdir}/systemd/system/wpa_supplicant@wlan0.service.d/

    install -d ${D}${sysconfdir}/systemd/system/systemd-networkd-wait-online.service.d
    install -m 0644 ${WORKDIR}/wait-online-any.conf ${D}${sysconfdir}/systemd/system/systemd-networkd-wait-online.service.d/
}

FILES:${PN} = "${sysconfdir}"
# The firmware for the board's WiFi chip (Murata 1YN, CYW43439) comes from
# ST's layer; iw is for looking at the link
RDEPENDS:${PN} = "wpa-supplicant linux-firmware-addons-bcm43xx iw"
