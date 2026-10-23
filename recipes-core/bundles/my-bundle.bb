SUMMARY = "RAUC update bundle: rootfs (dm-verity) and boot partition of my-image"
LICENSE = "MIT"

inherit bundle

# Must match "compatible" in the board's system.conf
RAUC_BUNDLE_COMPATIBLE = "stm32mp257f-dk-mydistro"
RAUC_BUNDLE_VERSION = "${DISTRO_VERSION}"
RAUC_BUNDLE_FORMAT = "verity"

RAUC_BUNDLE_SLOTS = "rootfs bootfs"

RAUC_SLOT_rootfs = "my-image"
RAUC_SLOT_rootfs[fstype] = "ext4"
# The same file as rootfs1/rootfs2 in the flash layout: ST's split rootfs
# with the dm-verity hash tree after the data. RAUC chooses how to write
# an image by its file extension, so the bundle stores it as ".ext4".
RAUC_SLOT_rootfs[file] = "my-image-${MACHINE}.splitted-rootfs.ext4.verity"
RAUC_SLOT_rootfs[rename] = "rootfs.ext4"

# The boot partition with the signed FIT. The root hash in its initramfs
# matches only this rootfs, which is why both are in one bundle.
RAUC_SLOT_bootfs = "my-bootfs"
RAUC_SLOT_bootfs[fstype] = "ext4"
RAUC_SLOT_bootfs[file] = "my-bootfs-${MACHINE}.ext4"

# The signing key is set in local.conf (RAUC_KEY_FILE / RAUC_CERT_FILE)
# and is not part of this layer.

# Sign again when the certificate changes. meta-rauc lists the key and
# certificate as "${RAUC_CERT_FILE}:False", and BitBake skips entries marked
# ":False" (file does not exist), so a new key would keep the old signature.
# A new key always comes with a new certificate, so the certificate is enough.
# (Only when a certificate is set: the parse check in CI has none.)
do_bundle[file-checksums] += "${@'${RAUC_CERT_FILE}:True' if d.getVar('RAUC_CERT_FILE') else ''}"
