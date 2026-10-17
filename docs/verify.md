# Checking build results

A build that finishes without errors has only proved that BitBake ran. Before flashing or
booting, check that the output contains what you expect. These are the commands I used, grouped
by question, each with the expected result for this layer.

All `PC$` commands run in the build directory. Shortcut used below:

```
PC$ D=tmp/deploy/images/stm32mp25-disco     # board build
PC$ D=tmp/deploy/images/qemuarm64           # QEMU build
```

## Host tools that BitBake already built

Many checks need tools like `dumpimage` or `fiptool`. BitBake builds them for the host
(`-native` recipes) anyway; you don't need to install them on your PC. Put a tool into a
sysroot once, then run it with `oe-run-native`:

```
PC$ bitbake u-boot-tools-native dtc-native tf-a-tools-native rauc-native -c addto_recipe_sysroot
PC$ oe-run-native u-boot-tools-native dumpimage -V
```

| Recipe | Tools |
|---|---|
| `u-boot-tools-native` | `mkimage`, `dumpimage`, `fit_check_sign` |
| `dtc-native` | `dtc`, `fdtget`, `fdtdump` |
| `tf-a-tools-native` | `fiptool` |
| `rauc-native` | `rauc` |

`Have you run 'bitbake <recipe> -c addto_recipe_sysroot'?` means you skipped this step.

## Configuration: which value does BitBake use?

```
PC$ bitbake-getvar -r my-image --value DISTRO_VERSION
PC$ bitbake-getvar -r virtual/kernel KERNEL_IMAGETYPE
PC$ bitbake-getvar -r my-image MACHINE_FEATURES
```
- `--value` prints only the result. Without it, you also get the **history**: which file set the
  variable, which file changed it, and in which order. This answers "why is it not my value?".
- **Expected** for the board build: `KERNEL_IMAGETYPE` is `fitImage` (step 12g),
  `MACHINE_FEATURES` contains `nogpu` and `fit`.

```
PC$ bitbake-layers show-layers
PC$ bitbake-layers show-appends | grep -A3 linux-stm32mp
```
- **Expected:** `meta-mylayer` is listed, and `linux-stm32mp_%.bbappend` from `meta-mylayer`
  appears under the kernel recipe. If your `.bbappend` is not listed, it is not applied.

## Packages: is it in the image?

```
PC$ grep -E "^(rauc|mymod|kernel-module-exfat|hello) " $D/my-image-*.rootfs.manifest
```
- The manifest lists every package in the image with its version.
- **Expected:** one line per package, e.g. `hello cortexa35 1.0`, `mymod stm32mp25_disco 1.0`, `rauc cortexa35 1.15.2`.

```
PC$ oe-pkgdata-util list-pkg-files kernel-module-exfat
PC$ oe-pkgdata-util find-path /usr/bin/hello
```
- Which files a package contains, and which package installs a file. Works even after
  `rm_work` has deleted the recipe's work directory.

## Binaries: built for the right CPU?

```
PC$ file tmp/work/cortexa57-poky-linux/hello/1.0/image/usr/bin/hello
```
- **Expected:** `ELF 64-bit LSB executable, ARM aarch64`. If it says `x86-64`, the host compiler
  was used instead of `${CC}`.

## Kernel configuration

```
PC$ grep -E "EXFAT|DM_VERITY|LOCALVERSION" tmp/work-shared/stm32mp25-disco/kernel-build-artifacts/.config
```
- **Expected:** `CONFIG_EXFAT_FS=m`, `CONFIG_DM_VERITY=y`, `CONFIG_LOCALVERSION="-mydistro"`.
- `# CONFIG_X is not set` means your fragment did not apply. On the board build, check that it
  is in `KERNEL_CONFIG_FRAGMENTS` (step 11).

## Image contents without flashing

`debugfs` reads an ext4 image file directly. No mounting, no root rights:

```
PC$ debugfs -R "ls -l /boot" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4
PC$ debugfs -R "cat /etc/modprobe.d/blacklist.conf" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4
PC$ debugfs -R "ls -l /" $D/my-image-stm32mp25-disco.splitted-bootfs.ext4
```
- **Expected** (since step 12g):
  - rootfs `/boot` has `fitImage` (a symlink) and `fitImage-<version>`.
  - bootfs is empty (since step 13c; before that it held only `boot.scr`).
  - `blacklist.conf` contains `blacklist etnaviv` (step 11).
- `debugfs ls` may show its output in a pager that closes at once, so nothing appears. Add
  `| cat`: `debugfs -R "ls -l /data" <image> | cat`.
- `/etc/fstab` ends with the `/data` line (since step 14):
  `debugfs -R "cat /etc/fstab" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4 | tail -2`
- All files show the date 9 March 2018. That is `REPRODUCIBLE_TIMESTAMP_ROOTFS`, not an error.
- ST splits the image: `my-image-*.rootfs.ext4` still contains everything, while
  `...splitted-rootfs.ext4` is what actually goes into the rootfs partitions.

## U-Boot: built-in boot logic and environment

```
PC$ strings $D/u-boot/u-boot-nodtb-stm32mp25-default.bin | grep -E "^(rauc_boot|rauc_select|rauc_load)=" | cut -c1-120
PC$ grep -E "BOOTCOMMAND|ENV_WRITEABLE_LIST|ENV_ACCESS_IGNORE_FORCE" $D/u-boot/configuration-stm32mp25_defconfig
```
- U-Boot's default environment is stored as plain text in the binary, so `strings` shows it.
- **Expected** (since step 13c): the three `rauc_*` variables, `CONFIG_BOOTCOMMAND="run rauc_boot"`,
  `CONFIG_ENV_WRITEABLE_LIST=y` and `CONFIG_ENV_ACCESS_IGNORE_FORCE=y`.
- `bootcmd=run rauc_boot` is there too, but glued to the string before it
  (`SunMonTue…bootcmd=…`), so `^bootcmd=` does not match.

## Flash layout

```
PC$ cat $D/flashlayout_my-image/optee/FlashLayout_sdcard_stm32mp257f-dk-optee.tsv
```
- **Expected:** partitions `fsbla1/2`, `metadata1/2`, `fip-a/b`, `u-boot-env`, `bootfs`,
  `vendorfs`, `rootfs1`, `rootfs2`, `userfs`, and the image file for each.

## FIT image (kernel + device tree)

```
PC$ oe-run-native u-boot-tools-native dumpimage -l $D/kernel/fitImage
```
- ST deploys kernel files to the `kernel/` subfolder.
- **Expected:**
  - `kernel-1` (gzip, `Load Address: 0xc4000000`) and one `fdt-...` per device tree, each with
    `Hash algo: sha256`
  - `Default Configuration: 'conf-stm32mp257f-dk.dtb'`
  - **Signed (step 12h):** each configuration also has `Sign algo: sha256,rsa2048:fitconf` and a
    `Sign value`. Only the configuration part, for example:
    ```
    PC$ oe-run-native u-boot-tools-native dumpimage -l $D/kernel/fitImage | sed -n '/Configuration 0/,/Configuration 1/p'
    ```

## FIP: does U-Boot carry the public key?

The FIP is the partition `fip-a` that holds TF-A BL31, OP-TEE and U-Boot. U-Boot's own
device tree, with the public key for the signed FIT, is inside it.

```
PC$ oe-run-native tf-a-tools-native fiptool info $D/fip/fip-stm32mp257f-dk-optee-sdcard.bin
```
- **Expected:** `Non-Trusted Firmware BL33` (U-Boot) and `HW_CONFIG` (U-Boot's device tree),
  among others.

```
PC$ mkdir -p ~/fip-check
PC$ oe-run-native tf-a-tools-native fiptool unpack --force --out ~/fip-check $D/fip/fip-stm32mp257f-dk-optee-sdcard.bin
PC$ cmp ~/fip-check/hw-config.bin $D/u-boot/u-boot-stm32mp257f-dk-default-with-signature.dtb && echo "FIP has the signed DTB"
PC$ cmp ~/fip-check/hw-config.bin $D/u-boot/u-boot-stm32mp257f-dk-default-without-signature.dtb && echo "FIP has the UNSIGNED DTB"
```
- `cmp` compares two files byte by byte and prints nothing if they are equal.
- **Expected:** only `FIP has the signed DTB`.

```
PC$ oe-run-native dtc-native fdtget -l ~/fip-check/hw-config.bin /signature
PC$ oe-run-native dtc-native fdtget -p ~/fip-check/hw-config.bin /signature/key-fitconf
PC$ oe-run-native dtc-native fdtget ~/fip-check/hw-config.bin /signature/key-fitconf required
PC$ oe-run-native dtc-native fdtget ~/fip-check/hw-config.bin /signature/key-fitconf algo
```
- **Expected:**
  - `key-fitconf`
  - a list with `rsa,modulus`, `rsa,exponent`, `required`, ... (the public key)
  - `conf`
  - `sha256,rsa2048`
- `required = conf` matters: without it, U-Boot would still boot an unsigned FIT.

## Signed TF-A and FIP

```
PC$ STM32_SigningTool_CLI -dump $D/arm-trusted-firmware/tf-a-stm32mp257f-dk-optee-sdcard_Signed.stm32
```
- **Expected:** `Signature` not all zeros, `Extension: 0x80000001`, an `Authentication header` with
  `ECDSA pub key` and `Key 0` … `Key 7`. The unsigned file shows `Signature: 00 00 …`.

Are those your keys?
```
PC$ cd ~/yocto-keys/stm32mp
PC$ xxd -p -c 32 publicKeyHash00.bin                  (compare with "Key 0" in the dump)
PC$ cat publicKeyHash0{0..7}.bin | sha256sum
PC$ xxd -p -c 32 publicKeysHashHashes.bin
```
- **Expected:** each `publicKeyHash0x.bin` equals `Key x`, and the sha256 over all eight equals
  `publicKeysHashHashes.bin`: the value the fuses would hold.

```
PC$ oe-run-native tf-a-tools-native fiptool info $D/fip/fip-stm32mp257f-dk-optee-sdcard_Signed.bin
```
- **Expected:** besides BL31, OP-TEE, U-Boot and the configs: `Trusted key certificate`, a key
  certificate and a content certificate each for SoC Firmware (BL31), Trusted OS Firmware (OP-TEE)
  and Non-Trusted Firmware (U-Boot), `Trusted Boot Firmware BL2 certificate` and
  `STM32MP CONFIG CERT`.

## RAUC bundle

```
PC$ oe-run-native rauc-native rauc info --keyring=../../meta-mylayer/recipes-core/rauc/files/ca.cert.pem $D/my-bundle-stm32mp25-disco.raucb
```
- **Expected:**
  - `Verified inline signature by '... Development-1'`
  - `Compatible: 'stm32mp257f-dk-mydistro'`
  - `Version` = your `DISTRO_VERSION`
  - `Bundle Format: verity`
  - `1 Image: [rootfs]` with `Filename: my-image-stm32mp25-disco.splitted-rootfs.ext4`
  - `Certificate Chain`: the signing certificate `Development-1`, issued by `rauc CA Development`
- This is the same check the board does before it installs anything.

## Logs when a task fails

```
PC$ ls tmp/work/*/<recipe>/*/temp/
```
- `log.do_<task>` is the output of the failed task; `run.do_<task>` is the exact script that
  BitBake ran. The error message at the end of a failed build prints the log path.

## On the board

| Question | Command | Expected (current state) |
|---|---|---|
| Which version runs? | `cat /etc/os-release` | `VERSION="1.x (learning)"` |
| Which kernel? | `uname -r` | `6.6.129-mydistro` |
| Kernel option active? | `zcat /proc/config.gz \| grep EXFAT` | `CONFIG_EXFAT_FS=m` |
| Module loaded? | `lsmod`, `modinfo mymod` | `mymod`, `vermagic` = running kernel |
| Service running? | `systemctl status sysmon` | `active (running)` |
| Which slot? | `rauc status` | `Booted from: rootfs.0 (A)` or `rootfs.1 (B)` |
| What did U-Boot pass? | `cat /proc/cmdline` | `root=PARTUUID=... rauc.slot=A` |
| What is mounted? | `mount` (`findmnt` is not in the image) | `/` on the slot's partition |
| U-Boot env from Linux | `fw_printenv \| grep BOOT_` | `BOOT_ORDER`, `BOOT_A_LEFT`, `BOOT_B_LEFT` |
| Stable disk names | `ls -l /dev/disk/by-path/` | `...48220000.mmc-partN` = SD card |
| Name of a raw partition | `cat /sys/class/block/$(basename $(readlink -f <dev>))/uevent` | `PARTNAME=fip-a` (BusyBox `blkid` shows nothing without a filesystem) |
| Data partition mounted and grown? | `df -h /data` | `/dev/mmcblkNp12`, `6.5G` (size of the partition) |
| Journal on `/data`? | `dmesg \| grep mmcblk.p12` | `r/w with ordered data mode` (not `without journal`) |
| Growfs and journal services | `systemctl status systemd-growfs@data data-journal` | growfs `active (exited)`; `data-journal` `status=0/SUCCESS` (oneshot, so `inactive (dead)` is fine) |
| RAUC waits for `/data`? | `systemctl show rauc -p RequiresMountsFor` | `/run/rauc/mnt /data` |
| RAUC slot status | `cat /data/rauc/central.raucs` | a `[slot.rootfs.N]` section per installed slot, `bundle.version`, `sha256` of the rootfs image |
| Secure boot state | first console lines after power-on | `TRUSTED_BOARD_BOOT support enabled`, `ROTPK is not deployed ...` on an open chip |

In U-Boot:

| Question | Command |
|---|---|
| Variables (addresses, boot targets) | `printenv`, `printenv kernel_addr_r` |
| Partitions | `part list mmc 0` |
| What is in a loaded FIT? Hashes OK? | `iminfo ${kernel_addr_r}` (look for `sha256+`) |
| How does a command work? | `help bootm` |
