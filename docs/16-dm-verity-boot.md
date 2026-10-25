# 16 dm-verity at boot

**Goal:** every block of the rootfs is checked against a root hash while the board runs, and the
root hash itself is signed. Guide 15 made the rootfs read-only and built the hash tree
([15e](15-read-only-rootfs.md#15e-hash-tree-and-root-hash-in-the-build)). This guide brings it
into the boot. Learning log: [step 16](learning-log.md#step-16-dm-verity-at-boot).

Board build only (`build-stm32mp`). The parts:

| Part | What | Status |
|---|---|---|
| 16a | An initramfs image (RAM system) with `veritysetup` | done |
| 16b | Own initramfs module: open the slot with dm-verity, root hash built in | done |
| 16c | Boot test by hand from the U-Boot prompt | done |
| 16d | Tamper test: the initramfs refuses a changed slot | done |
| 16e | FIT with kernel, DTB and initramfs, signed together | done |
| 16f | One boot partition per slot, RAUC slot group, full flash, update and rollback | done |

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
The root hash cannot be stored in the rootfs it describes, and until 16e the FIT was in the
rootfs (`/boot/fitImage`). So each slot gets its own boot partition (16f):
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

## 16e The initramfs inside the signed FIT

In 16c the initramfs came from the stick as a separate, unsigned file. Now it goes into the FIT,
so U-Boot checks it with the `fitconf` key, like the kernel:
```
fitImage-my-initramfs (signed with fitconf)
 ├─ kernel
 ├─ DTB
 └─ initramfs
      └─ /etc/verity-params   root hash
```

Poky's `kernel-fitimage.bbclass` does this when the kernel recipe sets `INITRAMFS_IMAGE`. In
[`linux-stm32mp_%.bbappend`](../recipes-kernel/linux/linux-stm32mp_%25.bbappend):
```
INITRAMFS_IMAGE = "my-initramfs"
```
- The class builds a second FIT in its own task, `do_assemble_fitimage_initramfs`, which waits for
  `my-initramfs:do_image_complete`. The first FIT (without initramfs) stays as it is and still
  goes into `/boot` of the rootfs.
- With a ramdisk, every configuration gets `sign-images = "kernel", "fdt", "ramdisk"`
  (`kernel-fitimage.bbclass`), so the signature covers the initramfs.
- No dependency loop: `my-image` needs only the kernel packages (`do_package_write_rpm`), and
  `do_assemble_fitimage_initramfs` runs after `do_install`, before `do_deploy`. So BitBake builds
  kernel packages → `my-image` → root hash → `my-initramfs` → second FIT.
- The kernel is rebuilt once (`INITRAMFS_IMAGE` is part of its tasks). That changes the kernel
  modules in `my-image`, so its root hash changes too, and the new initramfs gets the new one.

On the PC, in `build-stm32mp`:
```
bitbake linux-stm32mp
D=tmp/deploy/images/stm32mp25-disco
ls -lL $D/kernel/ | grep -i initramfs
bitbake u-boot-tools-native -c addto_recipe_sysroot
F=$(ls $D/kernel/fitImage-my-initramfs-*.bin | head -1); echo $F
oe-run-native u-boot-tools-native dumpimage -l $F | grep -E "Image [0-9]|Type|Hash value|Sign algo|Configuration|Ramdisk"
sha256sum $D/my-initramfs-stm32mp25-disco.cpio.gz
```
**Expected:**
- `fitImage-my-initramfs-stm32mp25-disco-stm32mp25-disco` (link) and the `.bin`, 17017414 bytes
  for me (11.6 MB FIT + 5.3 MB initramfs), plus the `.its`
- `dumpimage`: `Kernel Image`, three `Flat Device Tree` (ST's DTBs), `RAMDisk Image`; every
  configuration has `Init Ramdisk: ramdisk-1` and `Sign algo: sha256,rsa2048:fitconf`
- the `Hash value` of `ramdisk-1` is the `sha256sum` of the `.cpio.gz`

Copy to the USB stick (`U` = where the stick is mounted on the PC, see
`lsblk -o NAME,SIZE,FSTYPE,MOUNTPOINT`), with a short name for typing in U-Boot:
```
U=/media/dat/XXXX-XXXX
cp -L $D/kernel/fitImage-my-initramfs-stm32mp25-disco-stm32mp25-disco $U/fit-initramfs.itb
cp -L $D/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity $U/
sync
umount $U
```

### Three boot tests
The kernel, DTB and initramfs all come from `fit-initramfs.itb` on the stick; only the rootfs
comes from slot B. Slot A is only the fallback.

| Test | Slot B | Expected |
|---|---|---|
| A | the old image from 16c | U-Boot accepts the FIT; the initramfs refuses the slot |
| B | the new `.verity` image | boots, `/` through dm-verity |
| C | new image, but one byte of the initramfs in the FIT changed | U-Boot refuses the FIT |

At the U-Boot prompt, for all three (`fit-bad.itb` instead of `fit-initramfs.itb` in test C):
```
usb reset
load usb 0:1 ${kernel_addr_r} fit-initramfs.itb
part number mmc ${boot_instance} rootfs2 bpart
part uuid mmc ${boot_instance}:${bpart} rootuuid
setenv bootargs "root=PARTUUID=${rootuuid} rootwait ro earlycon console=${console},${baudrate} rauc.slot=B"
bootm ${kernel_addr_r}
```
`bootm` gets only the FIT; U-Boot takes the initramfs from the configuration.

**Test A, expected** (slot B still has the image of 16c):
```
## Loading ramdisk from FIT Image at 8a000000 ...
   Verifying Hash Integrity ... sha256,rsa2048:fitconf+ OK
   Trying 'ramdisk-1' ramdisk subimage
   Verifying Hash Integrity ... sha256+ OK
...
verity: rootfs /dev/mmcblk0p11, root hash f31f09dc...
device-mapper: verity: 179:43: metadata block 51524 is corrupted
EXT4-fs (dm-0): unable to read superblock
verity: mount of /dev/mapper/vroot failed, rebooting
```
In 16d one data block was changed, and the tree still matched the root hash: `data block 0 is
corrupted`. Here the whole tree belongs to another build, so the first check, top tree block
against root hash, fails: `metadata block 51524` is the start of the tree, right after the
51524 data blocks.

**Test B:** write the new image to slot B as in 16c (`PARTNAME=rootfs2` check, `dd`, `cmp`),
then boot. **Expected:** `verity: rootfs /dev/mmcblk2p11, root hash f31f09dc...`,
`EXT4-fs (dm-0): mounted filesystem ... ro`, login on slot B.

**Test C:** on the PC, change one byte in the middle of the initramfs in a copy of the FIT. The
offset is where the `.cpio.gz` starts inside the FIT (11658836 for me) plus half its size. The
offset differs for every build; find it with:
```
python3 -I -c "import sys; f=open(sys.argv[1],'rb').read(); r=open(sys.argv[2],'rb').read(); i=f.find(r); print(i, i+len(r)//2)" $U/fit-initramfs.itb $D/my-initramfs-stm32mp25-disco.cpio.gz
```
With my numbers (byte `2a` at 14336376):
```
cp $U/fit-initramfs.itb $U/fit-bad.itb
dd if=$U/fit-bad.itb bs=1 skip=14336376 count=1 2>/dev/null | xxd -p
printf '\x2b' | dd of=$U/fit-bad.itb bs=1 seek=14336376 count=1 conv=notrunc
cmp -l $U/fit-initramfs.itb $U/fit-bad.itb
```
**Expected:** `2a`; one `cmp` line `14336377  52  53` (counted from 1, octal). `conv=notrunc`
keeps the rest of the file.

Boot `fit-bad.itb`. **Expected:**
```
## Loading ramdisk from FIT Image at 8a000000 ...
   Verifying Hash Integrity ... sha256,rsa2048:fitconf+ OK
   Trying 'ramdisk-1' ramdisk subimage
     Data Start:   0x8ab1e654
     Hash value:   3d1e398b...
   Verifying Hash Integrity ... sha256 error!
Bad hash value for 'hash-1' hash node in 'ramdisk-1' image node
Bad Data Hash
Ramdisk image is corrupt or invalid
STM32MP>
```
The kernel does not start. `Data Start` minus the load address `0x8a000000` is `0xB1E654` =
11658836, the offset found on the PC. The signature is still `OK`: it covers the configuration,
which lists the hashes of kernel, DTB and initramfs. The stored hash is unchanged, the data is
not. Changing the stored hash as well would break the signature. `boot` then starts slot A.

## 16f One boot partition per slot, RAUC slot group

Until now the signed FIT with the initramfs only came from the USB stick. Now each slot gets its
own boot partition with that FIT, U-Boot loads it from there, and RAUC writes boot partition and
rootfs of a slot together:
```
SD card:  ... | u-boot-env | bootfs1 | bootfs2 | rootfs1 | rootfs2 | userfs |
                  p7          p8        p9        p10       p11       p12

slot A = bootfs1 + rootfs1          slot B = bootfs2 + rootfs2
         │                                   │
         └─ /fitImage (kernel, DTB, initramfs with the root hash of rootfs1)
```
The partition numbers stay the same as before, so `system.conf`, `fstab` and `fw_env.config`
did not change for them: `vendorfs` (empty) is gone and `bootfs` became two partitions.

### Step 1: the boot partition image `my-bootfs`
[`my-bootfs.bb`](../recipes-core/images/my-bootfs.bb) is an image with no packages
(`PACKAGE_INSTALL = ""`, `inherit image`). A rootfs postprocess command copies the deployed FIT
into it as `/fitImage`; `do_rootfs` waits for `virtual/kernel:do_deploy`:
```
ROOTFS_POSTPROCESS_COMMAND += "install_fit"
install_fit() {
    install -m 0644 ${DEPLOY_DIR_IMAGE}/kernel/fitImage-my-initramfs-${MACHINE}-${MACHINE} \
        ${IMAGE_ROOTFS}/fitImage
}
```
- ST's machine config sets `EXTRA_IMAGECMD:ext4 = "... -L ${IMAGE_NAME_SUFFIX}"`. With
  `IMAGE_NAME_SUFFIX = ""` the label is empty and `mkfs.ext4` fails, so the recipe sets its own
  `EXTRA_IMAGECMD:ext4` with `-L bootfs`.

On the PC, in `build-stm32mp`:
```
bitbake my-bootfs
D=tmp/deploy/images/stm32mp25-disco
debugfs -R "ls -l /" $D/my-bootfs-stm32mp25-disco.ext4 2>/dev/null | grep fitImage
cmp <(debugfs -R "cat /fitImage" $D/my-bootfs-stm32mp25-disco.ext4 2>/dev/null) $D/kernel/fitImage-my-initramfs-stm32mp25-disco-stm32mp25-disco && echo SAME
```
**Expected:** `fitImage` with the size of the FIT (17017414 bytes for me) and `SAME`. The empty
skeleton directories of an image (`etc`, `usr`, `var`, ...) are in it too and do no harm.

### Step 2: flash layout
In [`my-image.bb`](../recipes-core/images/my-image.bb):
```
ST_VENDORFS = "0"
PARTITIONS_IMAGES[bootfs] = "${STM32MP_BOOTFS_IMAGE},${STM32MP_BOOTFS_LABEL},${STM32MP_BOOTFS_MOUNTPOINT},${STM32MP_BOOTFS_SIZE},${STM32MP_BOOTFS_TYPE},2"
FLASHLAYOUT_PARTITION_BIN2LOAD:bootfs = "my-bootfs-${MACHINE}.ext4"
do_create_flashlayout_config[depends] += "my-bootfs:do_image_complete"
FLASHLAYOUT_PARTITION_BIN2LOAD:rootfs = "my-image-${MACHINE}.splitted-rootfs.ext4.verity"
```
- The last field of `PARTITIONS_IMAGES` is the number of copies: `2` gives `bootfs1` and
  `bootfs2`, like `rootfs1/2` in step 12.
- `BIN2LOAD` chooses the file that is flashed into a partition. Without the last line the rootfs
  partitions got the plain `.ext4` without the hash tree.

On the PC:
```
bitbake my-image
cut -f5,7 $D/flashlayout_my-image/optee/FlashLayout_sdcard_stm32mp257f-dk-optee_Signed.tsv | grep -E "fs"
```
**Expected:** `bootfs1` and `bootfs2` with `my-bootfs-stm32mp25-disco.ext4`, `rootfs1` and
`rootfs2` with `my-image-stm32mp25-disco.splitted-rootfs.ext4.verity`, `userfs`; no `vendorfs`.
The binary is column 7.

### Step 3: U-Boot loads the FIT from the slot's boot partition
In [`stm32mp2.env`](../recipes-bsp/u-boot/u-boot-stm32mp/stm32mp2.env), `rauc_select` also sets
`bootpartname` (`bootfs1` for A, `bootfs2` for B), and `rauc_load` loads from there:
```
part number mmc ${boot_instance} ${bootpartname} bootpart;
...
if load mmc ${boot_instance}:${bootpart} ${kernel_addr_r} /fitImage; then
    bootm ${kernel_addr_r};
fi;
```
`root=PARTUUID=` still names the rootfs partition; the initramfs opens it with dm-verity.

On the PC:
```
bitbake my-image
strings $D/u-boot/u-boot-nodtb-stm32mp25-disco-*.bin | grep -E "^rauc_load=|^rauc_select=" | grep -o "bootpart[a-z]* [a-z0-9]*\|/fitImage"
ls -l --time-style=+%H:%M $D/fip/fip-stm32mp257f-dk-optee-sdcard_Signed.bin
```
**Expected:** `bootpartname bootfs1`, `bootpartname bootfs2`, `/fitImage`, and a signed FIP from
this build.

U-Boot does not change for a new version: it holds only the public `fitconf` key and accepts every
FIT signed with the private key. Only FIT and rootfs change per version, and both are in the
bundle:
```
ROM ─(ST key)─► TF-A ─(ST key)─► U-Boot ─(fitconf key)─► FIT ─(root hash)─► rootfs
     fixed           fixed            fixed               changes per version
```

### Step 4: RAUC slot group
In [`system.conf`](../recipes-core/rauc/files/system.conf) each boot partition is a child of its
rootfs slot (`parent=`). RAUC installs a slot group only as a whole:
```
[slot.bootfs.0]
device=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part8
type=ext4
parent=rootfs.0

[slot.bootfs.1]
device=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part9
type=ext4
parent=rootfs.1
```
In [`my-bundle.bb`](../recipes-core/bundles/my-bundle.bb):
```
RAUC_BUNDLE_SLOTS = "rootfs bootfs"
RAUC_SLOT_rootfs[file] = "my-image-${MACHINE}.splitted-rootfs.ext4.verity"
RAUC_SLOT_rootfs[rename] = "rootfs.ext4"
RAUC_SLOT_bootfs = "my-bootfs"
RAUC_SLOT_bootfs[fstype] = "ext4"
RAUC_SLOT_bootfs[file] = "my-bootfs-${MACHINE}.ext4"
```
- The bundle gets the `.verity` file, the same as the flash layout.
- RAUC chooses how to write an image by its file name, so `[rename]` stores it as `rootfs.ext4`.
- `bundle.bbclass` makes the bundle wait for `my-bootfs:do_image_complete` by itself.

On the PC (there is no `rauc` on the PC, so the one from the build is used):
```
bitbake my-bundle
S=tmp/work/x86_64-linux/rauc-native/1.15.2/recipe-sysroot-native
LD_LIBRARY_PATH=$S/usr/lib $S/usr/bin/rauc info --keyring=$HOME/yocto-keys/rauc/openssl-ca/dev/ca.cert.pem $D/my-bundle-stm32mp25-disco.raucb 2>&1 | grep -E "Version|Images|Filename|Size:|Checksum"
sha256sum $D/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity $D/my-bootfs-stm32mp25-disco.ext4
```
**Expected:** `2 Images`: `[rootfs]` as `rootfs.ext4` (212,7 MB for me) and `[bootfs]` as
`my-bootfs-stm32mp25-disco.ext4` (67,1 MB); their checksums are the two `sha256sum`s.

### Step 5: full flash
`DISTRO_VERSION = "1.9"`. Flash the signed TSV (this erases `/data` too):
```
cd tmp/deploy/images/stm32mp25-disco
STM32_Programmer_CLI -c port=usb1 -w flashlayout_my-image/optee/FlashLayout_sdcard_stm32mp257f-dk-optee_Signed.tsv
```
Boot from the SD card. **Expected** in the serial log, without anything from the stick:
```
Booting slot A (rootfs1), attempts left: A=2 B=3
17017414 bytes read in 918 ms (17.7 MiB/s)
   Verifying Hash Integrity ... sha256,rsa2048:fitconf+ OK
   Trying 'ramdisk-1' ramdisk subimage
...
verity: rootfs /dev/mmcblk0p10, root hash 4ac09197...
EXT4-fs (dm-0): mounted filesystem ... ro with ordered data mode.
Welcome to My Learning Distro 1.9 (learning)!
```
On the board:
```
grep " / " /proc/mounts
rauc status | grep -E "Booted from|Activated|\[|bootname|boot status"
systemctl --failed --no-legend
```
**Expected:** `/dev/mapper/vroot / ext4 ro,relatime`; two slot groups, each `[bootfs.x]`
indented under its `[rootfs.x]`; no failed units.

The flash writes the same images into both slots (copy `2`), so A and B are equal now.

### Step 6: update test with a new version
An update with the same version would not show much: B would boot even if RAUC skipped the boot
partition. A new version changes the root hash, and only the new FIT knows it:
```
DISTRO_VERSION = "1.10"
  → /etc/os-release in the rootfs → new hash tree → new root hash
  → my-initramfs with the new /etc/verity-params → new FIT (re-signed) → new my-bootfs
```
If RAUC wrote only the rootfs, the old FIT would refuse it (`metadata block ... is corrupted`,
test A in 16e).

On the PC, after `bitbake my-bundle`:
```
grep -o "VERITY_ROOT_HASH=[0-9a-f]\{16\}" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity-params
```
**Expected:** a new hash (`0958c609...` for me, not `4ac09197...`). Copy the bundle to the stick
with `cp -L` (the deploy name is a link).

On the board, running slot A:
```
mkdir -p /run/usb
mount /dev/sda1 /run/usb
rauc install /run/usb/my-bundle-stm32mp25-disco.raucb
rauc status | grep -E "Activated|\[|boot status"
umount /run/usb
reboot
```
**Expected:** `Activated: rootfs.1 (B)`, then:
```
Booting slot B (rootfs2), attempts left: A=3 B=2
17017410 bytes read in 917 ms (17.7 MiB/s)
     Description:  my-initramfs
     Hash value:   6b61519e...          (1.9: b514acbd...)
verity: rootfs /dev/mmcblk1p11, root hash 0958c609...
EXT4-fs (dm-0): mounted filesystem ... ro with ordered data mode.
Welcome to My Learning Distro 1.10 (learning)!
```
- The FIT size and the ramdisk hash changed: RAUC wrote `bootfs2`. The new rootfs fits the new
  root hash: RAUC wrote `rootfs2` too.
- The kernel hash changed as well, with the same size: the kernel was rebuilt. The DTB hash is the
  same.
- The SD card was `mmcblk1` in this boot, not `mmcblk0`. The PARTUUID in `root=` and the
  `by-path` names in `system.conf` do not depend on that.

### Step 7: rollback test
Break slot B on purpose and let U-Boot go back to A by itself.

1. From B: `rauc status mark-active other`, `reboot`. **Expected:** `Booting slot A (rootfs1)`,
   version 1.9.
2. On A, check the device first:
   ```
   cat /proc/cmdline | grep -o "rauc.slot=."
   P=$(readlink -f /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11)
   cat /sys/class/block/$(basename $P)/uevent | grep PARTNAME
   ```
   **Expected:** `rauc.slot=A`, `PARTNAME=rootfs2`. Only then change the ext4 label of rootfs2
   (as in 16d):
   ```
   V=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part11
   printf 'R' | dd of=$V bs=1 seek=1144 count=1
   sync
   dd if=$V bs=1 skip=1144 count=6 2>/dev/null; echo
   ```
   **Expected:** `Rootfs`.
3. `rauc status mark-active other`, `reboot`. B gets 3 attempts again.
4. Nothing to do. **Expected:** three boots of B, each refused by the initramfs
   (`data block 0 is corrupted`, reboot), with `B=2`, `B=1`, `B=0`; then
   `Booting slot A (rootfs1)` and version 1.9.
5. On A:
   ```
   cat /etc/os-release | grep VERSION_ID
   rauc status | grep -E "Booted from|Activated|boot status"
   ```
   **Expected** (what I got):
   ```
   VERSION_ID=1.9
   Booted from: rootfs.0 (A)
   Activated: rootfs.0 (A)
         boot status: bad
         boot status: good
   ```
   B is `bad` (no attempts left). `Activated` is A: RAUC's U-Boot backend takes the first slot in
   `BOOT_ORDER` that still has attempts.
6. Repair B: the same check as in 2, then
   ```
   printf 'r' | dd of=$V bs=1 seek=1144 count=1
   sync
   rauc status mark-active other
   reboot
   ```
   **Expected:** `Booting slot B (rootfs2)`, version 1.10.

## Errors you may see
- `usb start` in U-Boot: `EHCI timed out on TD`, `0 Storage Device(s) found`. The stick works in
  Linux but not at once in U-Boot. Plugging it into the other USB-A port and `usb reset` (once or
  twice) found it.
- `mount /dev/sda1 /run/usb` typed twice mounts the stick twice; one `umount` leaves it mounted.
- Several lines pasted at once into the serial console arrive mixed up. Paste one line at a time.
- The initramfs grows to 10 MB: cryptsetup's `udev` and `ssh-token` options and recommended
  packages; see [16a](#size).
- `metadata block ... is corrupted` instead of `data block ...`: the hash tree on the partition
  belongs to another build than the root hash (see test A in 16e).
- `Bootstage space exhasuted` in U-Boot before `Starting kernel`: U-Boot's boot-time statistics
  table is full. Harmless.
- `mkfs.ext4: ... label too long` (or a missing label) for `my-bootfs`: ST's `EXTRA_IMAGECMD:ext4`
  uses `-L ${IMAGE_NAME_SUFFIX}`, which is empty there. Set `EXTRA_IMAGECMD:ext4` in the recipe.
- `rauc info` on the PC prints nothing with `2>/dev/null`: there is no `rauc` on the PC. Use the
  one from `rauc-native` (16f step 4) and the CA from `openssl-ca/dev/ca.cert.pem`.
