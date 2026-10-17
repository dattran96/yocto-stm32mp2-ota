FILESEXTRAPATHS:prepend := "${THISDIR}/${PN}:"

# A/B boot logic for RAUC built into U-Boot, and an environment that only
# accepts the RAUC variables from the u-boot-env partition. U-Boot is
# verified by TF-A as part of the FIP, so the boot logic is too; boot.scr
# in bootfs and bootcmd/bootargs in u-boot-env were not verified at all.
SRC_URI += " \
    file://0001-stm32mp25-import-only-the-RAUC-variables-from-the-environment.patch \
    file://rauc-env.cfg \
    file://stm32mp2.env \
"

# U-Boot picks up board/<vendor>/<board>/<CONFIG_SYS_BOARD>.env as text
# environment (doc/usage/environment.rst)
do_configure:prepend() {
    install -m 0644 ${WORKDIR}/stm32mp2.env ${S}/board/st/stm32mp2/stm32mp2.env
}
