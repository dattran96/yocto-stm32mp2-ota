# 12g Kernel as a FIT image

**Goal:** pack the kernel and device tree into one FIT image with sha256 hashes, boot it with
`bootm`, and make the boot script reset on failure so the fallback needs nobody at the board.
Learning log: [step 12g](../README.md#step-12-ota-updates-with-rauc-in-progress).

## Background
A FIT (Flattened Image Tree) is one file with the kernel, the device trees, a hash of each, and
*configurations* that say which kernel goes with which device tree. At build time `mkimage`
computes each hash and stores it in the file. At boot, `bootm` computes the hash again over the
data in RAM and compares. That catches damaged data, not an attacker; for that, see 12h.

## Steps

1. Switch on ST's FIT support in `local.conf`:
   ```
   MACHINE_FEATURES:append = " fit"
   ```
   and raise `DISTRO_VERSION` in `mydistro.conf`.
2. Build and inspect:
   ```
   PC$ bitbake my-bundle
   PC$ D=tmp/deploy/images/stm32mp25-disco
   PC$ debugfs -R "ls -l /boot" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4
   PC$ bitbake u-boot-tools-native -c addto_recipe_sysroot
   PC$ oe-run-native u-boot-tools-native dumpimage -l $D/kernel/fitImage
   ```
   **Expected:** `/boot` has `fitImage` and no `Image.gz` any more. `dumpimage` lists `kernel-1`
   (gzip, load address `0xc4000000`), the device trees, and
   `Default Configuration: 'conf-stm32mp257f-dk.dtb'`.
3. Look at U-Boot's side. Stop autoboot:
   ```
   STM32MP> help bootm
   STM32MP> printenv kernel_addr_r fdt_addr_r
   ```
   `bootm addr#<conf>` boots one configuration. `kernel_addr_r` is `0x8a000000`: the 11 MB FIT is
   loaded there and the kernel is unpacked to `0xc4000000` (28 MB), so they don't overlap.
4. **Optional demonstration:** install this bundle with the *old* boot script still in place.
   It fails with `Failed to load '/boot/Image.gz'`, then `Bad Linux ARM64 Image magic!`, and
   U-Boot stops at its prompt. The boot script lives in `bootfs`, which RAUC does not update,
   so the script and the kernel format must change together.
5. Boot the FIT by hand (`rootfs2` = slot B):
   ```
   STM32MP> part number mmc 0 rootfs2 rootpart
   STM32MP> part uuid mmc 0:${rootpart} rootuuid
   STM32MP> load mmc 0:${rootpart} ${kernel_addr_r} /boot/fitImage
   STM32MP> iminfo ${kernel_addr_r}
   STM32MP> setenv bootargs root=PARTUUID=${rootuuid} rootwait rw earlycon console=ttySTM0,115200 rauc.slot=B
   STM32MP> bootm ${kernel_addr_r}#conf-stm32mp257f-dk.dtb
   ```
   **Expected:** `iminfo` ends with `sha256+` for every image; `bootm` prints
   `Verifying Hash Integrity ... sha256+ OK` and Linux starts.
6. Update [`boot.cmd`](../recipes-bsp/ab-bootscript/files/boot.cmd):
   ```
   if load mmc ${devnum}:${rootpart} ${kernel_addr_r} /boot/fitImage; then
     bootm ${kernel_addr_r}
   fi
   echo "Booting slot ${raucslot} failed, resetting"
   sleep 3
   reset
   ```
   - Plain `bootm ${kernel_addr_r}` uses the FIT's default configuration, so the script doesn't
     need the device tree name.
   - `if load ...` stops `bootm` from running on old RAM content if the file is missing.
   - `reset` makes a failed attempt reboot by itself. Without it, the board waits at the
     U-Boot prompt and the fallback never happens.
7. Put the new `boot.scr` on the board without a full flash. Take it out of the bootfs image:
   ```
   PC$ bitbake my-image
   PC$ debugfs -R "dump /boot.scr /tmp/boot.scr" $D/my-image-stm32mp25-disco.splitted-bootfs.ext4
   PC$ strings /tmp/boot.scr | grep -E "fitImage|reset"
   ```
   Copy it to the USB stick (as in guide 12, 12d), then on the board mount bootfs **by path**:
   ```
   board# mkdir -p /mnt/usb /mnt/bootfs
   board# mount /dev/sda1 /mnt/usb
   board# mount /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part8 /mnt/bootfs
   board# ls -l /mnt/bootfs
   board# cp /mnt/usb/boot.scr /mnt/bootfs/boot.scr
   board# sync && umount /mnt/bootfs /mnt/usb
   board# reboot
   ```
   `ls` should show only the old `boot.scr`. If you see anything else, stop: you mounted the
   wrong partition.

## Check
1. Boot without touching anything. **Expected:** `Booting slot B ...`,
   `Verifying Hash Integrity ... sha256+ OK`, Linux comes up.
2. Fallback test. Select the other slot, which still has the old kernel format:
   ```
   board# rauc status mark-active other
   board# reboot
   ```
   **Expected:** `Failed to load '/boot/fitImage'`, `Booting slot A failed, resetting`, three
   times; then the board boots B by itself, and `rauc status` shows A as `bad`.

## Errors you may see
- `ERROR: No recipes in default available for: .../linux-stm32mp_%.bbappend`: you are in the
  QEMU build folder. `source oe-init-build-env` without an argument opens `build/`; use
  `source oe-init-build-env build-stm32mp`.
- `dumpimage: Can't open ".../fitImage"`: ST puts kernel files in the `kernel/` subfolder.
