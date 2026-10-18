# 16 dm-verity at boot

**Goal:** every block of the rootfs is checked against a root hash while the board runs, and the
root hash itself is signed. Guide 15 made the rootfs read-only and built the hash tree
([15e](15-read-only-rootfs.md#15e-hash-tree-and-root-hash-in-the-build)). This guide brings it
into the boot. Learning log: [step 16](../README.md#step-16-dm-verity-at-boot-in-progress).

Board build only (`build-stm32mp`). The parts:

| Part | What | Status |
|---|---|---|
| 16a | An initramfs image (RAM system) with `veritysetup` | done |
| 16b | Own initramfs module: open the slot with dm-verity, root hash built in | done |
| 16c | Boot test by hand from the U-Boot prompt | done |
| 16d | Tamper test: the initramfs refuses a changed slot | done |
| 16e | FIT with kernel, DTB and initramfs, signed together | next |
| 16f | One boot partition per slot, RAUC slot group, full flash | |

## Background

### Why an initramfs
dm-verity has to be set up before the rootfs is mounted, by a program (`veritysetup`). Without an
initramfs, the only programs are in the rootfs itself, which is not checked yet:
```
without:  kernel ──mount──► rootfs (mmcblkXp10) ──► systemd
                     the kernel mounts the partition directly, nobody checks it

with:     kernel ──► /init (in RAM) ──veritysetup open──► /dev/mapper/vroot ──mount──► systemd
```
An initramfs is a small archive (`.cpio.gz`). U-Boot loads it into RAM next to the kernel, the
kernel unpacks it and starts its `/init` instead of mounting `root=`. `/init` prepares the real
rootfs and ends with `switch_root`: the RAM files are deleted, the real rootfs becomes `/`, and
its `/sbin/init` (systemd) starts. The initramfs lives for less than a second.

### The boot flow
```
U-Boot          checks the FIT signature (kernel + DTB, later + initramfs)
  │             bootargs: root=PARTUUID=<slot> rauc.slot=A ro
  ▼
kernel          unpacks the initramfs into RAM, starts /init
  ▼
/init           poky's initramfs-framework: runs /init.d/* in number order
  ├─ 80-verity  OWN MODULE:
  │               1. PARTUUID from root= → blkid → /dev/mmcblkXpY
  │               2. read /etc/verity-params (root hash, salt, block counts)
  │               3. veritysetup open <dev> vroot <dev> <root hash>
  │               4. mount -o ro /dev/mapper/vroot /rootfs
  │               any failure → reboot, never mount without the check
  ├─ 90-rootfs  /rootfs already mounted → nothing to do
  └─ 99-finish  move /dev /proc /sys, switch_root /rootfs /sbin/init
  ▼
systemd         / is /dev/dm-0; every block read is checked
```
- `root=` comes from U-Boot and is not signed. Pointing it at another partition does not help an
  attacker: whatever partition is opened must match the root hash from the initramfs.
- On a failure the module reboots. U-Boot has already counted the try (`BOOT_A_LEFT`, guide 13),
  so after three failures it boots the other slot.

### Where the root hash comes from, and where this is going
```
bitbake my-image ──► .verity-params                     (15e)
                          │ copied in at build time
                          ▼
bitbake my-initramfs ──► /etc/verity-params in the initramfs   (16a, 16b)
                          │ packed into the signed FIT         (16e)
                          ▼
                     U-Boot's signature check protects the root hash
```
The root hash cannot be stored in the rootfs it describes, and today the FIT is in the rootfs
(`/boot/fitImage`). So each slot gets its own boot partition (16f):
```
SD card:  ... | bootfs1 (new) | bootfs2 (new) | rootfs1 | rootfs2 | userfs |
                │
                └─ fitImage (signed)
                     ├─ kernel
                     ├─ DTB
                     └─ initramfs   ← file inside the FIT
                         └─ /etc/verity-params (root hash of rootfs1)
```
The initramfs is a file, not a partition. RAUC will write `bootfs1` and `rootfs1` together, so
the root hash always belongs to its rootfs.

## 16a The initramfs image

New image recipe [`my-initramfs.bb`](../recipes-core/images/my-initramfs.bb), modelled on poky's
`core-image-minimal-initramfs.bb`:
- `PACKAGE_INSTALL =` is the exact package list: `initramfs-framework-base` (`/init` and
  `99-finish`), `initramfs-module-rootfs` (`90-rootfs`), BusyBox. No systemd. (At first it also
  listed `cryptsetup` and `util-linux-blkid`; since 16b the module pulls them in.)
- `PACKAGE_EXCLUDE = "kernel-image-*"`: the kernel comes separately.
- `IMAGE_FSTYPES = "${INITRAMFS_FSTYPES}"` = `cpio.gz`, the format the kernel unpacks.
- ST adds partition images, a flash layout and a license summary to every image.
  `ENABLE_PARTITIONS_IMAGE`, `ENABLE_FLASHLAYOUT_CONFIG` and `ENABLE_IMAGE_LICENSE_SUMMARY = "0"`
  turn them off, as ST's own `st-image-partitions.inc` does. They must come before
  `inherit core-image`.

On the PC, in `build-stm32mp`:
```
bitbake my-initramfs
D=tmp/deploy/images/stm32mp25-disco
ls -lL $D/my-initramfs-stm32mp25-disco.cpio.gz
zcat $D/my-initramfs-stm32mp25-disco.cpio.gz | cpio -t 2>/dev/null | grep -E "^init|veritysetup|blkid|switch_root"
cut -d" " -f1 $D/my-initramfs-stm32mp25-disco.manifest | tr '\n' ' '; echo
```
`cpio -t` only lists the archive; nothing is unpacked.

**Expected:** `init`, `init.d/90-rootfs`, `init.d/99-finish`, `usr/sbin/veritysetup`,
`usr/sbin/blkid`, `usr/sbin/switch_root`; no `systemd` in the manifest.

### Size
The first build was 10.2 MB (25.9 MB unpacked, 44 packages). The sizes of the files, still
without unpacking:
```
zcat $D/my-initramfs-stm32mp25-disco.cpio.gz | cpio -tv 2>/dev/null | awk '$1 ~ /^-/ {s+=$5; if ($5>300000) printf "%6.1f MB  %s\n", $5/1048576, $9} END {printf "%6.1f MB  total (unpacked)\n", s/1048576}' | sort -n
```
It showed `libsystemd-shared`, `udevadm`, `libssh`, and in the manifest dm-crypt kernel modules:
- cryptsetup's `udev` option has `RDEPENDS` on systemd's `udev` (about 5 MB with its libraries),
  `ssh-token` pulls `libssh`. Neither is needed for `veritysetup`.
  [`cryptsetup_%.bbappend`](../recipes-crypto/cryptsetup/cryptsetup_%25.bbappend):
  `PACKAGECONFIG:remove = "udev ssh-token"`. This also changes the `cryptsetup` in `my-image`,
  so its root hash changes.
- The kernel modules, `busybox-udhcpc` and more are only recommended (`RRECOMMENDS`).
  `NO_RECOMMENDATIONS = "1"` in the initramfs recipe leaves them out. dm-verity is built into
  the kernel (`CONFIG_DM_VERITY=y`).

**Expected after both:** about 5.3 MB (12.5 MB unpacked, 17 packages). 5.6 MB of it is OpenSSL's
`libcrypto`, which `veritysetup` uses for sha256.

## 16b The module `80-verity`

1. [`initramfs-module-verity/files/verity`](../recipes-core/initramfs-module-verity/files/verity),
   the script. `/init` loads every file in `/init.d/` with `.` and calls `<name>_enabled` and
   `<name>_run`; the name comes from the file name (`80-verity` → `verity`). `/init` also turns
   every `key=value` of the kernel command line into a variable `bootparam_key`.
   - `blkid -c /dev/null -o device -t PARTUUID=...` finds the partition. `-c /dev/null`: no cache
     file, a fresh scan each time. It retries for 10 s: the SD card appears a moment after the
     kernel starts.
   - The `veritysetup open` line is the `verify` command of 15e, with a device name.
   - `reboot -f`: without `-f`, BusyBox asks PID 1 to reboot, and PID 1 is this script.
2. [`initramfs-module-verity.bb`](../recipes-core/initramfs-module-verity/initramfs-module-verity.bb)
   installs it as `/init.d/80-verity`, `RDEPENDS` on `cryptsetup` and `util-linux-blkid`.
3. In `my-initramfs.bb`: `initramfs-module-verity` in `PACKAGE_INSTALL`, and the root hash:
   ```
   do_rootfs[depends] += "my-image:do_image_complete"
   ROOTFS_POSTPROCESS_COMMAND += "install_verity_params"
   ```
   `install_verity_params` copies `.verity-params` from the deploy folder to
   `/etc/verity-params`. The task dependency makes BitBake rebuild the initramfs whenever
   `my-image` changes.

On the PC, in `build-stm32mp`:
```
bitbake my-initramfs
zcat $D/my-initramfs-stm32mp25-disco.cpio.gz | cpio -t 2>/dev/null | grep -E "^init.d|verity"
zcat $D/my-initramfs-stm32mp25-disco.cpio.gz | cpio -i --to-stdout etc/verity-params 2>/dev/null; echo
grep VERITY_ROOT_HASH $D/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity-params
```
**Expected:** `init.d/80-verity` next to `90-rootfs` and `99-finish`, `etc/verity-params`; the
`VERITY_ROOT_HASH` inside the initramfs is the same as the one of `my-image`.

## 16c Boot test by hand

Slot A stays as it is. Slot B gets the built `.verity` image, and the U-Boot prompt boots it with
A's kernel and the initramfs from the USB stick:
```
kernel + DTB (FIT)    from slot A /boot/      just a kernel, it does not know the root hash
initramfs + root hash from the USB stick      today's build
rootfs                slot B (rootfs2)        today's build: the .verity image
```
The root hash and the rootfs come from the same build, so they match. The initramfs from the
stick is not signed; 16e puts it into the FIT.

Copy to the USB stick (`cp -L`): `my-image-stm32mp25-disco.splitted-rootfs.ext4.verity`,
`...ext4.verity-params` and `my-initramfs-stm32mp25-disco.cpio.gz`.

### Write the image to slot B
On the board, running from slot A:
```
cat /proc/cmdline
P=$(readlink -f /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11)
cat /sys/class/block/$(basename $P)/uevent | grep PARTNAME
```
**Expected:** `rauc.slot=A`, `PARTNAME=rootfs2`. Only then:
```
mkdir -p /run/usb
mount /dev/sda1 /run/usb
dd if=/run/usb/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity of=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11 bs=4096
sync
cmp /run/usb/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11
```
**Expected:** `51932+0 records`, `cmp: EOF on /run/usb/...verity` (equal up to the end of the
file).

The same check the initramfs will do, in Linux:
```
. /run/usb/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity-params
V=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11
veritysetup open --no-superblock --data-block-size=$VERITY_DATA_BLOCK_SIZE --hash-block-size=$VERITY_HASH_BLOCK_SIZE --data-blocks=$VERITY_DATA_BLOCKS --hash-offset=$((VERITY_DATA_SECTORS * 512)) --hash=$VERITY_HASH_ALGORITHM --salt=$VERITY_SALT $V vroot $V $VERITY_ROOT_HASH
veritysetup status vroot
veritysetup close vroot
umount /run/usb
```
**Expected:** `status: verified`, `data block: 4096`, `size: 412192 sectors`,
`hash offset: 412192 sectors` (the tree starts right after the data, no superblock).
If this fails, the image or the parameters are wrong, not the initramfs.

### Boot from the U-Boot prompt
Leave the stick in, `reboot`, press a key at `Hit any key to stop autoboot`. At `STM32MP>`, one
line at a time:
```
part number mmc ${boot_instance} rootfs1 apart
part number mmc ${boot_instance} rootfs2 bpart
part uuid mmc ${boot_instance}:${bpart} rootuuid
echo ${boot_instance} ${apart} ${bpart} ${rootuuid}
```
**Expected:** `0 0xa 0xb <PARTUUID of rootfs2>`.
```
usb reset
load usb 0:1 ${ramdisk_addr_r} my-initramfs-stm32mp25-disco.cpio.gz
setenv rdsize ${filesize}
load mmc ${boot_instance}:${apart} ${kernel_addr_r} /boot/fitImage
setenv bootargs "root=PARTUUID=${rootuuid} rootwait ro earlycon console=${console},${baudrate} rauc.slot=B"
bootm ${kernel_addr_r} ${ramdisk_addr_r}:${rdsize}
```
- `usb reset` should find `1 Storage Device(s)`. See the errors below if it does not.
- `bootm <FIT> <initramfs>:<size>`: the second argument is a raw initramfs
  (`CONFIG_SUPPORT_RAW_INITRD=y` in ST's U-Boot). The FIT signature is checked as before.

**Expected in the console:**
```
Loading Ramdisk to 8fae4000, end 8ffff647 ... OK
[1.43] Unpacking initramfs...
[1.72] Freeing initrd memory: 5228K
[2.39] Run /init as init process
verity: rootfs /dev/mmcblk2p11, root hash 072c7021...
[2.99] EXT4-fs (dm-0): write access unavailable, skipping orphan cleanup
[2.99] EXT4-fs (dm-0): mounted filesystem ... ro with ordered data mode.
[3.85] systemd 255 running in system mode
```
The SD card was `mmcblk2` in this boot and `mmcblk0` in the next one; the PARTUUID found it both
times. The module took about 0.6 s, most of it waiting for the SD card.

On the board, after login:
```
cat /proc/cmdline
mount | grep " / "
veritysetup status vroot | grep -E "status|root hash|data device"
rauc status | grep -i booted
```
**Expected:** `rauc.slot=B`; `/dev/mapper/vroot on / type ext4 (ro,relatime)`;
`status: verified` with the root hash of the build; `Booted from: rootfs.1 (B)` (RAUC takes the
slot from `rauc.slot=`, not from the `/` device).

`reboot` without stopping U-Boot boots slot A as usual: the manual boot did not change the
U-Boot counters.

## 16d Tamper test

One byte changes in the ext4 superblock on slot B: the volume label at byte 1144 (superblock at
1024, label at offset 120). The superblock is in data block 0, the first block `mount` reads.

On the board, running from slot A, the same `PARTNAME=rootfs2` check as in 16c first:
```
V=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11
dd if=$V bs=1 skip=1144 count=6 2>/dev/null; echo
printf 'R' | dd of=$V bs=1 seek=1144 count=1
sync
dd if=$V bs=1 skip=1144 count=6 2>/dev/null; echo
```
**Expected:** `rootfs`, then `Rootfs`.

Boot B by hand again as in 16c (all U-Boot lines; the variables are gone after the reboot).

**Expected:**
```
[2.39] Run /init as init process
verity: rootfs /dev/mmcblk0p11, root hash 072c7021...
[2.98] device-mapper: verity: 179:43: data block 0 is corrupted
[2.98] EXT4-fs (dm-0): unable to read superblock
mount: mounting /dev/mapper/vroot on /rootfs failed: Input/output error
verity: mount of /dev/mapper/vroot failed, rebooting
[5.03] reboot: Restarting system
```
The board restarts by itself and boots slot A. The changed slot was never mounted.

The difference between the two boot paths, seen in this test:

| | Slot A (normal boot) | Slot B (through the initramfs) |
|---|---|---|
| Who mounts `/`? | the kernel: `Waiting for root device PARTUUID=...` | the module, through `/dev/mapper/vroot` |
| First program | `Run /sbin/init` (systemd) | `Run /init` (initramfs), then `switch_root` |
| Check | none; ext4 even writes (`orphan cleanup on readonly fs`) | every block; writing is impossible |

Write the byte back (on A, after the same `PARTNAME` check):
```
V=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11
printf 'r' | dd of=$V bs=1 seek=1144 count=1
sync
dd if=$V bs=1 skip=1144 count=6 2>/dev/null; echo
```
**Expected:** `rootfs`.

## Errors you may see
- `usb start` in U-Boot: `EHCI timed out on TD`, `0 Storage Device(s) found`. The stick works in
  Linux but not at once in U-Boot. Plugging it into the other USB-A port and `usb reset` (once or
  twice) found it.
- `mount /dev/sda1 /run/usb` typed twice mounts the stick twice; one `umount` leaves it mounted.
- Several lines pasted at once into the serial console arrive mixed up. Paste one line at a time.
- The initramfs grows to 10 MB: cryptsetup's `udev` and `ssh-token` options and recommended
  packages; see [16a](#size).
- `Bootstage space exhasuted` in U-Boot before `Starting kernel`: U-Boot's boot-time statistics
  table is full. Harmless.
