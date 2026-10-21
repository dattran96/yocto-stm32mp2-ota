SUMMARY = "Small system monitor daemon logging load and memory to syslog"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://sysmon \
    file://sysmon.init \
    file://sysmon.service \
"

S = "${WORKDIR}/sysmon"

inherit cmake pkgconfig update-rc.d systemd

# With systemd in DISTRO_FEATURES: build against libsystemd for sd_notify()
PACKAGECONFIG ??= "${@bb.utils.filter('DISTRO_FEATURES', 'systemd', d)}"
PACKAGECONFIG[systemd] = "-DWITH_SYSTEMD=ON,-DWITH_SYSTEMD=OFF,systemd"

# SysVinit: /etc/init.d/sysmon, linked into the runlevels at priority 90
INITSCRIPT_NAME = "sysmon"
INITSCRIPT_PARAMS = "defaults 90"

# systemd: enable the unit at boot
SYSTEMD_SERVICE:${PN} = "sysmon.service"

do_install:append() {
    if ${@bb.utils.contains('DISTRO_FEATURES', 'sysvinit', 'true', 'false', d)}; then
        install -d ${D}${sysconfdir}/init.d
        install -m 0755 ${WORKDIR}/sysmon.init ${D}${sysconfdir}/init.d/sysmon
    fi
    if ${@bb.utils.contains('DISTRO_FEATURES', 'systemd', 'true', 'false', d)}; then
        install -d ${D}${systemd_system_unitdir}
        install -m 0644 ${WORKDIR}/sysmon.service ${D}${systemd_system_unitdir}/sysmon.service
    fi
}
