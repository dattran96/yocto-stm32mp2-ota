# 14 Persistent data partition (`/data`)

**Goal:** a partition that RAUC updates never touch, mounted at `/data`, with RAUC's own slot
status on it. Without it, every update and every rollback loses everything written at runtime,
because RAUC writes the whole rootfs slot.
Learning log: [step 14](../README.md#step-14-persistent-data-partition).

Board build only (`build-stm32mp`). No full flash: ST's flash layout already has the partition,
so everything is delivered as RAUC bundles.

From this guide on, command blocks have no prompt. The line above each block says where to run
it, so every line can be copied as it is.

## Background

```
SD card:  ... | rootfs1 | rootfs2 | userfs (partition 12)                         |
                                    |<-- partition: rest of the card (6.5 GiB) ->|
                                    |<-- ext4 from ST: 1.2 MiB -->|              |
```
- ST's flash layout puts `userfs` last, so the partition fills the rest of the card.
- ST builds the filesystem only as big as its content
  (`image_types-stsplitpartitions.bbclass`: `partition_size=$CALCULATED_SIZE`;
  `STM32MP_USERFS_SIZE` is only an upper limit). The content is ST's empty `/usr/local`.
- `mke2fs` adds no journal to such a small filesystem, and growing it later does not add one.
  The journal is ext4's own record of changes in progress, so that a power cut leaves a
  consistent filesystem. (It is not the systemd journal that `journalctl` reads.)
- Nothing mounts `userfs` yet.

## 14a Look at the partition

On the board:
```
P=$(readlink -f /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part12)
echo $P
cat /sys/class/block/$(basename $P)/uevent
cat /sys/class/block/$(basename $P)/size
mount | grep -E "mmc|/data|/usr/local"
mkdir -p /mnt/userfs
mount $P /mnt/userfs
df -h /mnt/userfs
ls -la /mnt/userfs
umount /mnt/userfs
```
**Expected:**
- `PARTNAME=userfs`
- `size` is `13676475` sectors of 512 bytes (6.5 GiB on a card like mine)
- only `/` is mounted from the SD card
- `df` shows `1.2M`
- the mount message in the console says `without journal`
- the folder contains only `lost+found`

Mount it only once. If you mount it twice, one `umount` leaves it mounted, and BusyBox `df`
then shows the wrong mount point later.

## 14b Mount it at `/data` and grow it, by hand first

On the board (this edit of `/etc/fstab` is lost with the next update, which is the point of this
lesson):
```
mkdir -p /data
echo "/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part12  /data  ext4  defaults,x-systemd.growfs  0  2" >> /etc/fstab
systemctl daemon-reload
systemctl start data.mount
df -h /data
systemctl status systemd-growfs@data.service
dmesg | grep mmcblk.p12
```
**Expected:**
- `systemctl daemon-reload` makes systemd's fstab generator create `data.mount`
- dmesg: `resizing filesystem from 1332 to 6838237 blocks`, `Converting file system to meta_bg`,
  `resized filesystem to 6838237`
- `systemd-growfs` reports `Successfully resized "/data" to 6.5G`
- `df -h /data` shows `6.5G`

Reboot. `/data` is mounted by itself, and growfs runs on every boot
(`resizing filesystem from 6838237 to 6838237 blocks` does nothing). The device was
`mmcblk1p12` after my reboot and `mmcblk0p12` before: the `by-path` name in `fstab` keeps working.

## 14c The same in the layer, plus the journal (version 1.5)

1. [`base-files_%.bbappend`](../recipes-core/base-files/base-files_%25.bbappend): ST already
   ships `fstab` through its own `base-files` bbappend, so add one line in `do_install:append`
   instead of copying the file, and create the empty `/data` mount point. The override
   `:stm32mp25-disco` keeps the line out of the QEMU build.
2. [`data-journal`](../recipes-core/data-journal/): a oneshot service that runs after
   `systemd-growfs@data.service` and adds a journal with `tune2fs -O has_journal` if there is
   none. It has to run after growing: a journal does not fit into the 1 MB filesystem. The recipe
   needs `RDEPENDS = "e2fsprogs-tune2fs"`; `tune2fs` is not in the image otherwise.
3. Add `data-journal` to `IMAGE_INSTALL` in `my-image.bb`, set `DISTRO_VERSION = "1.5"`.

On the PC, in `build-stm32mp`:
```
bitbake my-bundle
D=tmp/deploy/images/stm32mp25-disco
debugfs -R "cat /etc/fstab" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4 | tail -2
debugfs -R "ls -l /data" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4 | cat
grep -E "^(data-journal|e2fsprogs-tune2fs) " $D/my-image-*.rootfs.manifest
```
**Expected:** the `/data` line is the last line of `fstab`, `/data` contains only `.` and `..`,
both packages are listed. (Without `| cat`, `debugfs ls` may show its output in a pager that
closes at once.)

Install the bundle (guide 12, 12d) and reboot. On the board:
```
grep VERSION_ID /etc/os-release
grep /data /etc/fstab
systemctl status data-journal
ls -la /data
```
**Expected:**
- `VERSION_ID=1.5`
- the `fstab` line now comes from the image
- `data-journal`: `Creating journal inode: done`, `status=0/SUCCESS`. It shows
  `inactive (dead)`: that is normal for a `Type=oneshot` service that has finished.
- `ls -la /data` shows a 32 MB `.journal` file: `tune2fs` added the journal to a mounted
  filesystem, so it is visible as a file. It is marked immutable.

Reboot once more:
```
dmesg | grep mmcblk.p12
```
**Expected:** `mounted filesystem ... r/w with ordered data mode` instead of `without journal`.

## 14d RAUC status on `/data` (version 1.6)

RAUC stored its status in a file inside each slot (`slot.raucs`), so an update overwrote it,
and `rauc status` warned `Failed to load status file`.

1. [`system.conf`](../recipes-core/rauc/files/system.conf): `data-directory=/data/rauc` in
   `[system]`. RAUC creates the folder itself.
2. [`rauc-data.conf`](../recipes-core/rauc/files/rauc-data.conf): a systemd drop-in with
   `RequiresMountsFor=/data`, so `rauc.service` never starts before `/data` is mounted and
   writes into the empty folder below the mount point.
3. [`rauc_%.bbappend`](../recipes-core/rauc/rauc_%25.bbappend) installs it to
   `rauc.service.d/` and adds it to the `rauc-service` package, where meta-rauc puts
   `rauc.service`.
4. `DISTRO_VERSION = "1.6"`.

On the PC, in `build-stm32mp` (the relative `$D` path does not work from `meta-mylayer`):
```
bitbake my-bundle
D=tmp/deploy/images/stm32mp25-disco
debugfs -R "cat /etc/rauc/system.conf" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4 | grep data-directory
debugfs -R "cat /usr/lib/systemd/system/rauc.service.d/rauc-data.conf" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4
```

### Test: what survives an update

On the board, still on 1.5:
```
echo "I live in /data" > /data/test.txt
echo "I live in /etc"  > /etc/test.txt
```
Install the 1.6 bundle and reboot. Then:
```
grep VERSION_ID /etc/os-release
cat /data/test.txt
cat /etc/test.txt
ls -la /data/rauc
systemctl show rauc -p RequiresMountsFor
rauc status --detailed
```
**Expected:**
- `VERSION_ID=1.6`
- `/data/test.txt` is still there; `/etc/test.txt`: `No such file or directory`
- `/data/rauc/central.raucs` exists
- `RequiresMountsFor=/run/rauc/mnt /data` (the first one is meta-rauc's own)
- no `slot.raucs` warning any more. The slot status is still empty: the 1.6 install ran on 1.5,
  which wrote it to the old per-slot file.

Install the same bundle once more from 1.6 (no reboot needed):
```
mount /dev/sda1 /mnt/usb
rauc install /mnt/usb/my-bundle-stm32mp25-disco.raucb
umount /mnt/usb
cat /data/rauc/central.raucs
```
**Expected:** a `[slot.rootfs.1]` section with `bundle.version=1.6`, `status=ok`, `sha256`,
`size`, `installed.count=1`. The `sha256` and `size` are those of
`my-image-stm32mp25-disco.splitted-rootfs.ext4` on the PC (`sha256sum`, `ls -l`).

The dates on the board (2025) are not today: the board has no network time yet and starts
with the image's build date.

## Errors you may see
- `debugfs: No such file or directory while trying to open tmp/deploy/...`: you are not in
  `build-stm32mp`. `bitbake` works from any folder after `source oe-init-build-env`, relative
  paths do not.
- `grep /data` without a file waits for keyboard input. Press Ctrl+C.
