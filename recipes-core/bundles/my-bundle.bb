SUMMARY = "RAUC update bundle with the rootfs of my-image"
LICENSE = "MIT"

inherit bundle

# Must match "compatible" in the board's system.conf
RAUC_BUNDLE_COMPATIBLE = "stm32mp257f-dk-mydistro"
RAUC_BUNDLE_VERSION = "${DISTRO_VERSION}"
RAUC_BUNDLE_FORMAT = "verity"

RAUC_BUNDLE_SLOTS = "rootfs"
RAUC_SLOT_rootfs = "my-image"
RAUC_SLOT_rootfs[fstype] = "ext4"
# The slot gets the same image as rootfs1/rootfs2 in the flash layout:
# ST's split rootfs, without /boot (that goes to bootfs)
RAUC_SLOT_rootfs[file] = "my-image-${MACHINE}.splitted-rootfs.ext4"

# The signing key is set in local.conf (RAUC_KEY_FILE / RAUC_CERT_FILE)
# and is not part of this layer.
