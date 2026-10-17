# 15 Read-only rootfs and dm-verity

**Goal:** the root filesystem is never written on the board, from the first moment of boot.
dm-verity (next part) checks every block of the slot against a hash, which only works if the
slot stays exactly as it was built.
Learning log: [step 15](../README.md#step-15-read-only-rootfs-and-dm-verity-in-progress).

Board build only (`build-stm32mp`). Needs `/data` from [guide 14](14-data-partition.md).

## Background

What happens to writes once `/` is read-only:

```
                          writable?   survives reboot?   survives update?
/  /usr /bin /lib  /etc      NO            –                  –
/var/lib /var/cache          yes (RAM)     NO                 NO
/var/spool                   yes (RAM)     NO                 NO
/var/log /var/tmp /run       yes (RAM)     NO                 NO     (already before this step)
/data                        yes (SD)      YES                YES
```
Anything that must be kept goes to `/data`, decided item by item (an SSH host key, WiFi
settings, ...). I did not use `overlayfs-etc`: it keeps the changes to `/etc` on `/data` as an
overlay, and those files are not covered by dm-verity, but still win over the checked ones.

## 15a Find out what writes to `/` (by hand)

`systemd-remount-fs` applies the options of the `/` line in `/etc/fstab` early at boot, so `ro`
there makes `/` read-only. Stay in the slot you are changing, otherwise the next boot goes to
the other slot.

On the board:
```
rauc status mark-active booted
cp /etc/fstab /etc/fstab.rw
sed -i 's|^\(/dev/root.*\) defaults |\1 defaults,ro |' /etc/fstab
grep /dev/root /etc/fstab
reboot
```
**Expected:** `/dev/root ... auto       defaults,ro ...`. The order matters: options are applied
from left to right, and `defaults` contains `rw`, so `ro,defaults` stays writable.

After the reboot:
```
mount | grep " / "
systemctl --failed
systemctl status systemd-remount-fs -l --no-pager
```
**What I got:** `/` was still `rw`. `systemd-remount-fs` failed with `mount: /: mount point is
busy`: Linux does not switch a filesystem to read-only while a program has a file open for
writing on it. The same `mount -o remount,ro /` worked by hand a few minutes later. (The cause,
found in 15c: the kernel mounts `/` writable, and systemd writes into it before the remount.)

To see who writes to `/`, list everything newer than the copy made before the reboot. Run each
line on its own; pasting a block while output is still printing mixes up the terminal.
```
mount -o remount,ro /
find / -xdev -newer /etc/fstab.rw -not -path "/proc/*" 2>/dev/null
ls -laR /var/lib/systemd /var/lib/dbus
ls -la /var /var/cache /etc/dropbear
systemctl cat dropbearkey.service --no-pager
systemctl start dropbearkey
```
**What writes to `/`:**

| Who | Writes | When |
|---|---|---|
| systemd | `/var/lib/systemd/random-seed`, `catalog/database`, `rfkill/...`, `timesync/clock` | every boot |
| D-Bus | `/var/lib/dbus/machine-id` (link to `/etc/machine-id`) | first boot |
| systemd | `/etc/machine-id` | first boot |
| ldconfig | `/var/cache/ldconfig` | every boot |
| Dropbear | `/etc/dropbear/dropbear_rsa_host_key` | first SSH connection; `systemctl start dropbearkey` fails with `/` read-only |

`/var/log` and `/var/tmp` already point to `volatile/` (tmpfs); `/var/lib` and `/var/cache` are
real folders on the rootfs.

Undo the test:
```
mount -o remount,rw /
rm -rf /etc/dropbear/*
cp /etc/fstab.rw /etc/fstab
rm /etc/fstab.rw
```

## 15b `read-only-rootfs` in the image (version 1.7)

Poky has an image feature for exactly this list (`read_only_rootfs_hook` in
`meta/classes-recipe/rootfs-postcommands.bbclass`):

| Problem | What the feature does at build time |
|---|---|
| `/` must be `ro` | `fstab`: `defaults` → `ro`, fsck pass → `0` (fsck cannot repair a read-only `/`) |
| Dropbear key | `DROPBEAR_RSAKEY_DIR=/var/lib/dropbear` in `/etc/default/dropbear` |
| `/etc/machine-id` | an empty file: systemd then makes an ID in RAM at every boot |
| `/var/lib`, `/var/cache`, `/var/spool` | nothing here: `volatile-binds` (installed with systemd) mounts them as overlays with an upper layer in tmpfs when they are read-only at boot |

1. [`my-image.bb`](../recipes-core/images/my-image.bb): `IMAGE_FEATURES += "read-only-rootfs"`.
2. `DISTRO_VERSION = "1.7"`.

On the PC, in `build-stm32mp`:
```
bitbake my-bundle
D=tmp/deploy/images/stm32mp25-disco
debugfs -R "cat /etc/fstab" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4 | grep /dev/root
debugfs -R "cat /etc/default/dropbear" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4
debugfs -R "stat /etc/machine-id" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4 | grep Size
```
**Expected:** `/dev/root ... ro ... 1  0`, `DROPBEAR_RSAKEY_DIR=/var/lib/dropbear`,
`Size: 0`.

Install the bundle (guide 12, 12d), reboot. On the board:
```
mount | grep -E " / | /var/lib | /var/cache "
systemctl --failed
touch /test.txt
ls -la /var/volatile/lib
rauc status
```
**Expected:**
- `/` is `(ro,relatime)`, `systemctl --failed` lists nothing
- `overlay on /var/lib type overlay (rw,...,lowerdir=/var/lib,upperdir=/var/volatile/lib,...)`,
  the same for `/var/cache`
- `touch: /test.txt: Read-only file system`
- `/var/volatile/lib` holds only what this boot wrote (`dbus`, `private`, `systemd`)
- the new slot is `booted` and `good`: `rauc-mark-good` writes to `/data`

An overlay shows two folders as one: reads come from the upper layer (here tmpfs) if the file is
there, otherwise from the lower one (the read-only rootfs); a write copies the file up first. With
the upper layer in RAM, every change is gone after a reboot.

Harmless messages: `Failed to create directory or subvolume "/root/.ssh" ... Read-only file
system` comes from systemd's `provision.conf` (SSH keys passed as credentials, used in VMs; our
root home is `/home/root`). `regulatory.db` belongs to WiFi.

## 15c `ro` from U-Boot

`/etc/machine-id` was empty in the image but had an ID on the board, with nothing mounted over
it:
```
ls -l --full-time /etc/machine-id
dmesg | grep p11
```
**What I got:**
```
[2.51 s]  EXT4-fs (mmcblk0p11): mounted filesystem ... r/w with ordered data mode.
08:44:13  /etc/machine-id written (33 bytes)
[4.66 s]  EXT4-fs (mmcblk0p11): re-mounted ... ro.
```
U-Boot passes `rw`, so `/` is writable for the first seconds, and systemd writes into the slot.
That is also what made the remount "busy" in 15a.

1. [`stm32mp2.env`](../recipes-bsp/u-boot/u-boot-stm32mp/stm32mp2.env), in `rauc_load`:
   `rootwait rw` → `rootwait ro`. A slot without `read-only-rootfs` (1.6) still works: its
   `fstab` says `defaults`, so `systemd-remount-fs` makes it writable after boot.
2. Build and check that U-Boot inside the **signed FIP** has the change.

On the PC, in `build-stm32mp`:
```
bitbake my-image
D=tmp/deploy/images/stm32mp25-disco
strings $D/u-boot/u-boot-nodtb-stm32mp25-default.bin | grep -o "rootwait r[ow]"
mkdir -p ~/fip-ro
oe-run-native tf-a-tools-native fiptool unpack --force --out ~/fip-ro $D/fip/fip-stm32mp257f-dk-optee-sdcard_Signed.bin
strings ~/fip-ro/nt-fw.bin | grep -o "rootwait r[ow]"
```
**Expected:** `rootwait ro` twice.

3. RAUC does not update the FIP. Copy `fip-stm32mp257f-dk-optee-sdcard_Signed.bin` to the USB
   stick and write it to `fip-a` from Linux. `/mnt` is read-only now, so mount the stick in
   `/run` (tmpfs). Check the partition name first.

On the board:
```
P=$(readlink -f /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part5)
cat /sys/class/block/$(basename $P)/uevent | grep PARTNAME
```
**Expected:** `PARTNAME=fip-a`. Only then:
```
mkdir -p /run/usb
mount /dev/sda1 /run/usb
dd if=/run/usb/fip-stm32mp257f-dk-optee-sdcard_Signed.bin of=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part5 bs=4096
sync
cmp /run/usb/fip-stm32mp257f-dk-optee-sdcard_Signed.bin /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part5
umount /run/usb
reboot
```
**Expected:** `774+1 records in/out` (for a 3.17 MB file), `cmp: EOF on /run/usb/...`: all bytes
match, the partition is just bigger. If the new FIP does not boot, recover with a USB flash of
`_Signed.tsv` (guide 11).

After the reboot:
```
cat /proc/cmdline
dmesg | grep p11
mount | grep " / "
```
**Expected:**
- `rootwait ro` in the command line
- `mounted filesystem ... ro with ordered data mode`, then `re-mounted ...` **without** `ro` or
  `r/w`: `systemd-remount-fs` still applies `fstab`, and the kernel names the state only when it
  changes
- `/` is `(ro,relatime)`

`orphan cleanup on readonly fs` may show once: files deleted while still open during an earlier
writable boot. ext4 cleans them up even on a read-only mount, by writing to the device. With
dm-verity the device itself cannot be written.

## 15d dm-verity by hand (version 1.8)

**Goal:** see dm-verity work before building it into the image. Compute the hash tree on the PC,
write image + tree into the slot that is not running, open it through dm-verity on the board,
then change one byte.

### How dm-verity works
```
build time:  ext4 image ──► sha256 of every data block ──► hash tree ──► one ROOT HASH (32 bytes)
                                                           (appended after the data)

 partition:  [ ext4 data .................... | superblock | hash tree ]
                                                 4 KB: block sizes, salt, algorithm

read time:              root hash (given by you, later by the signed FIT)
                             ▲ sha256(top hash block) == root hash?
                        [ top hash block ]
                             ▲ sha256(lower hash block) == entry above?
                        [ lower hash block ]
                             ▲ sha256(data block) == entry above?
                        [ data block ]  → equal: data to ext4; different: I/O error
```
- `veritysetup open` checks nothing yet. It creates `/dev/mapper/<name>`, a block device on top of
  the partition. Every block read through it is checked, as long as it is open, not only at boot.
  Checked hash blocks stay in RAM, so later reads only hash the data block.
- The hash tree is on the SD card too. Someone who changes data **and** tree still cannot match
  the root hash, which is not on the SD card.

### Step 1: `veritysetup` on the board
Add `cryptsetup` (contains `veritysetup`, needs `libdevmapper`) to `IMAGE_INSTALL` in
`my-image.bb`, `DISTRO_VERSION = "1.8"`, install the bundle. The kernel has dm-verity since
step 12a (`rauc.cfg`). After this you run from slot A, and B is free for the test.

On the board:
```
cat /proc/cmdline
veritysetup --version
```
**Expected:** `rauc.slot=A`, `veritysetup 2.7.5`.

### Step 2: hash tree on the PC
On the PC, in `build-stm32mp`:
```
bitbake cryptsetup-native -c addto_recipe_sysroot
D=tmp/deploy/images/stm32mp25-disco
mkdir -p ~/verity-test
cp -L $D/my-image-stm32mp25-disco.splitted-rootfs.ext4 ~/verity-test/rootfs.img
SIZE=$(stat -c %s ~/verity-test/rootfs.img)
echo $SIZE $((SIZE / 1024))
dumpe2fs -h ~/verity-test/rootfs.img | grep "Block size"
```
**Expected:** `213667840 208660` and `Block size: 1024`. `mke2fs` uses 1 KB blocks for
"small" filesystems (below 512 MB), and the dm-verity data block size must not be bigger than
the ext4 block size (see below). Use your own numbers in the next command:
```
oe-run-native cryptsetup-native veritysetup format --data-block-size=1024 --data-blocks=208660 --hash-offset=213667840 ~/verity-test/rootfs.img ~/verity-test/rootfs.img | tee ~/verity-test/verity.txt
ls -l ~/verity-test/rootfs.img
oe-run-native cryptsetup-native veritysetup verify --data-block-size=1024 --data-blocks=208660 --hash-offset=213667840 ~/verity-test/rootfs.img ~/verity-test/rootfs.img <ROOT HASH>
echo "exit: $?"
debugfs -R "bmap /usr/lib/os-release 0" ~/verity-test/rootfs.img
```
**Expected:**
- `Data block size: 1024`, `Hash blocks: 1645`, `Root hash: <64 hex characters>`
- the file grew by 1646 × 4 KB (6.7 MB): the hash blocks and the superblock
- `verify` prints nothing, `exit: 0`
- `bmap`: `119175`, the ext4 block of `os-release`; with 1 KB blocks, byte 122035200

Copy `rootfs.img` to the USB stick.

### Step 3: open it on the board
Check that partition 11 is `rootfs2` and that you run from A.

On the board:
```
P=$(readlink -f /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11)
cat /sys/class/block/$(basename $P)/uevent | grep PARTNAME
cat /proc/cmdline
```
**Expected:** `PARTNAME=rootfs2`, `rauc.slot=A`. Only then:
```
mkdir -p /run/usb /run/vroot
mount /dev/sda1 /run/usb
dd if=/run/usb/rootfs.img of=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11 bs=4096
sync
cmp /run/usb/rootfs.img /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11
umount /run/usb
V=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11
veritysetup open --hash-offset=213667840 $V vroot $V <ROOT HASH>
veritysetup status vroot
mount -o ro /dev/mapper/vroot /run/vroot
head -n 3 /run/vroot/usr/lib/os-release
umount /run/vroot
veritysetup close vroot
```
`veritysetup open <data device> <name> <hash device> <root hash>`: data and tree are on the same
partition, so `$V` appears twice; `--hash-offset` says where the tree starts.

**Expected:**
- `cmp: EOF on /run/usb/rootfs.img`
- `status: verified`, `data block: 1024`, `size: 417320 sectors`,
  `hash offset: 417328 sectors` (data + the 4 KB superblock)
- `EXT4-fs (dm-0): write access unavailable, skipping orphan cleanup`: the dm-verity device
  cannot be written at all, not even by ext4 (compare 15c, where a read-only mount still wrote)
- `head`: `ID=mydistro`, `NAME="My Learning Distro"`, `VERSION="1.8 (learning)"`

### Step 4: change one byte, past dm-verity
This is what someone with the SD card in a PC, or root on the board, could do: write to the
partition directly.

On the board:
```
dd if=$V bs=1 skip=122035200 count=11 2>/dev/null; echo
printf 'X' | dd of=$V bs=1 seek=122035200 count=1
sync
dd if=$V bs=1 skip=122035200 count=11 2>/dev/null; echo
```
**Expected:** `ID=mydistro`, then `XD=mydistro`. Stop if the first line is something else.

### Step 5: read it again through dm-verity
```
veritysetup open --hash-offset=213667840 $V vroot $V <ROOT HASH>
mount -o ro /dev/mapper/vroot /run/vroot
head -n 3 /run/vroot/usr/lib/os-release
dmesg | grep -i verity | tail -n 3
umount /run/vroot
veritysetup close vroot
```
**Expected:**
```
device-mapper: verity: 179:43: data block 119175 is corrupted
head: /run/vroot/usr/lib/os-release: Input/output error
```
`open` and `mount` still work: dm-verity refuses only the changed block, when it is read.
`179:43` is the partition's device number (`ls -l /dev/mmcblk0p11`). The message comes twice
because the kernel retries the read.

Slot B now has a changed byte. Do not boot it; the next RAUC install overwrites it.

## Errors you may see
- `EXT4-fs (dm-0): bad block size 1024` when mounting `/dev/mapper/...`: the ext4 uses 1 KB
  blocks, and dm-verity was formatted with its default 4 KB data blocks. A filesystem cannot use
  blocks smaller than its device's. Use `--data-block-size=1024`, or build the ext4 with 4 KB
  blocks.
- `mount: /mnt/usb: mount point does not exist`: `/mnt/usb` was created by hand in another slot,
  and `mkdir` fails on a read-only `/`. Use `/run/usb`.
- `FAT-fs (sda1): Volume was not properly unmounted`: the stick was pulled without `umount`.
  Harmless for reading; repair on the PC with `sudo fsck.vfat -a /dev/sdX1`.
- `mount` twice on the same folder mounts it twice; one `umount` leaves it mounted.
- `ls: ... tmp/deploy/...: No such file`: you are not in `build-stm32mp`.
