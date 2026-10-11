FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# ST's kernel recipe doesn't merge .cfg files from SRC_URI the way
# linux-yocto does; it merges only the files listed in
# KERNEL_CONFIG_FRAGMENTS.
SRC_URI += "file://exfat.cfg"
KERNEL_CONFIG_FRAGMENTS:append = " ${WORKDIR}/exfat.cfg"

SRC_URI += "file://rauc.cfg"
KERNEL_CONFIG_FRAGMENTS:append = " ${@bb.utils.contains('DISTRO_FEATURES', 'rauc', '${WORKDIR}/rauc.cfg', '', d)}"
