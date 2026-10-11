FILESEXTRAPATHS:prepend := "${THISDIR}/${PN}:"

# ST only ships examples (fw_env.config.mmc etc.). Install the config
# that matches this board's SD card layout under the name that
# fw_printenv/fw_setenv (and RAUC) actually read.
SRC_URI += "file://fw_env.config"

do_install:append() {
    install -m 0644 ${WORKDIR}/fw_env.config ${D}${sysconfdir}/fw_env.config
}
