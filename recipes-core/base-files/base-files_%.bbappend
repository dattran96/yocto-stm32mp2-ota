# Persistent data on ST's userfs partition (12) at /data.
# ST builds that ext4 only as big as its content (about 1 MB), while the
# partition fills the rest of the SD card: x-systemd.growfs grows the
# filesystem to the partition size at boot.
DATA_DEVICE ?= "/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part12"

do_install:append:stm32mp25-disco () {
    install -d ${D}/data
    echo "${DATA_DEVICE}  /data  ext4  defaults,x-systemd.growfs  0  2" >> ${D}${sysconfdir}/fstab
}
