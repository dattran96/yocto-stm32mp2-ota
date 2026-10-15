# 12 A/B updates with RAUC

**Goal:** two root filesystem slots (A and B). The board runs from one slot while RAUC writes a
signed update into the other; U-Boot boots the new slot and falls back to the old one if the
new system does not come up.
Learning log: [step 12a–12e](../README.md#step-12-ota-updates-with-rauc-in-progress).

Board build only (`build-stm32mp`). Every partition change needs a full flash
(guide 11, step 7). After that, updates go over RAUC.

## 12a Add meta-rauc

1. Get the layer and add it:
   ```
   $ cd ~/yocto && git clone -b scarthgap https://github.com/rauc/meta-rauc.git
   PC$ bitbake-layers add-layer ../../meta-rauc
   ```
2. Read `meta-rauc/README.rst`. It asks for three things:
   - `rauc` in `DISTRO_FEATURES`. That is distro policy, so in `mydistro.conf`:
     `DISTRO_FEATURES:append = " rauc"`
   - `rauc` in `IMAGE_INSTALL` of `my-image.bb`
   - a `rauc-conf.bbappend` with your `system.conf` and keyring (12c)
3. RAUC's `verity` bundles need device-mapper and dm-verity in the kernel. `meta-rauc` adds
   them only for `linux-yocto`, so add your own [`rauc.cfg`](../recipes-kernel/linux/files/rauc.cfg)
   to `linux-stm32mp_%.bbappend`, only when the distro uses RAUC:
   ```
   SRC_URI += "file://rauc.cfg"
   KERNEL_CONFIG_FRAGMENTS:append = " ${@bb.utils.contains('DISTRO_FEATURES', 'rauc', '${WORKDIR}/rauc.cfg', '', d)}"
   ```

**Check:**
```
PC$ bitbake my-image
PC$ grep -E "BLK_DEV_DM|DM_VERITY" tmp/work-shared/stm32mp25-disco/kernel-build-artifacts/.config
PC$ grep -E "^rauc" tmp/deploy/images/stm32mp25-disco/my-image-*.rootfs.manifest
```
**Expected:** `CONFIG_BLK_DEV_DM=y`, `CONFIG_DM_VERITY=y`; packages `rauc`, `rauc-conf`,
`rauc-service`, `rauc-mark-good`. Warnings that `system.conf` and the CA are examples are
expected until 12c.

## 12b Two rootfs slots and an A/B boot script

1. **Two rootfs partitions.** In `my-image.bb`, ST's default rootfs entry with copy count `2`:
   ```
   PARTITIONS_IMAGES[rootfs] = "${STM32MP_ROOTFS_IMAGE},${STM32MP_ROOTFS_LABEL},,${STM32MP_ROOTFS_SIZE},FileSystem,2"
   ```
2. **Try the boot by hand first.** Flash, stop U-Boot, and boot Linux from the prompt, using
   each command you will put into the script. At this step the kernel is still in `bootfs`
   (partition 8; 12f moves it into the slots). Look up the file names first:
   ```
   STM32MP> printenv
   STM32MP> part list mmc 0
   STM32MP> ls mmc 0:8 /
   STM32MP> part number mmc 0 rootfs1 rootpart
   STM32MP> part uuid mmc 0:${rootpart} rootuuid
   STM32MP> load mmc 0:8 ${kernel_addr_r} /<kernel file from ls>
   STM32MP> load mmc 0:8 ${fdt_addr_r} /<device tree file from ls>
   STM32MP> setenv bootargs root=PARTUUID=${rootuuid} rootwait rw console=${console},${baudrate}
   STM32MP> booti ${kernel_addr_r} - ${fdt_addr_r}
   ```
   **Expected:** Linux boots with `/` on `rootfs1`, even though the script does not exist yet.
3. **The boot script.** Write [`boot.cmd`](../recipes-bsp/ab-bootscript/files/boot.cmd): take the
   first slot in `BOOT_ORDER` that has attempts left, decrease `BOOT_x_LEFT`, `saveenv`, then boot
   with `root=PARTUUID=...` and `rauc.slot=A|B`. The recipe
   [`ab-bootscript.bb`](../recipes-bsp/ab-bootscript/ab-bootscript.bb) turns it into `boot.scr`
   with `mkimage -T script`.
4. Use it instead of ST's `extlinux.conf`. In `local.conf`:
   ```
   ST_EXTLINUX_PACKAGE = "ab-bootscript"
   ```
5. **U-Boot environment from Linux.** `fw_printenv` / `fw_setenv` (and RAUC) need
   `/etc/fw_env.config`. Write [`fw_env.config`](../recipes-bsp/u-boot/u-boot-fw-config-stm32mp/fw_env.config)
   and install it with
   [`u-boot-fw-config-stm32mp_%.bbappend`](../recipes-bsp/u-boot/u-boot-fw-config-stm32mp_%.bbappend).
   - Use `/dev/disk/by-path/...48220000.mmc-part7`, not `/dev/mmcblkN` (the number changes
     between boots) and not `by-partlabel` (the eMMC has partitions with the same names).
   - Check the offsets on the board. Each copy starts with 4 bytes CRC, 1 flag byte, then
     the variables as text:
     ```
     board# hexdump -C -s 0x7C000 -n 48 /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part7
     board# hexdump -C -s 0x7E000 -n 48 /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part7
     ```
6. Build, flash.

**Check:**
```
board# fw_printenv | grep BOOT_
board# cat /proc/cmdline
```
**Expected:** `BOOT_ORDER=A B`, `BOOT_A_LEFT=2` (one attempt used by this boot), and
`rauc.slot=A` in the command line. In the U-Boot log: `Booting slot A (rootfs1), attempts left: A=2 B=3`.

**Fallback test** (only works before 12c, while nothing marks the slot as good): reboot three
times. The fourth boot shows `Booting slot B (rootfs2)`. Reset the counters with
`fw_setenv BOOT_A_LEFT 3`.

## 12c RAUC configuration and signing keys

1. Create a development CA and signing key **outside the repository**:
   ```
   $ mkdir -p ~/yocto-keys/rauc && cd ~/yocto-keys/rauc
   $ ~/yocto/meta-rauc/scripts/openssl-ca.sh
   ```
2. Copy only the **public** CA certificate into the layer:
   ```
   $ cp ~/yocto-keys/rauc/openssl-ca/dev/ca.cert.pem ~/yocto/meta-mylayer/recipes-core/rauc/files/
   ```
   `.gitignore` blocks `*.key.pem` as a safety net.
3. Tell the build where the signing key is. In `local.conf`:
   ```
   RAUC_KEY_FILE = "${HOME}/yocto-keys/rauc/openssl-ca/dev/private/development-1.key.pem"
   RAUC_CERT_FILE = "${HOME}/yocto-keys/rauc/openssl-ca/dev/development-1.cert.pem"
   ```
4. Write [`system.conf`](../recipes-core/rauc/files/system.conf): `compatible`, `bootloader=uboot`,
   `bundle-formats=-plain`, the keyring, and two slots with `bootname=A` / `B` on the `by-path`
   devices of partitions 10 and 11.
5. [`rauc-conf.bbappend`](../recipes-core/rauc/rauc-conf.bbappend) only needs
   `FILESEXTRAPATHS:prepend`: the recipe already fetches `system.conf` and `ca.cert.pem`, and the
   first match in the search path wins.
6. Build, flash.

**Check:**
```
board# rauc status
board# journalctl -u rauc-mark-good
```
**Expected:** `Booted from: rootfs.0 (A)`, both slots `good`, and `Marked slot rootfs.0 as good`.
`Failed to load status file .../slot.raucs` is normal for freshly flashed slots.

## 12d Build a bundle and install an update

1. Write [`my-bundle.bb`](../recipes-core/bundles/my-bundle.bb) (`inherit bundle`, same
   `compatible` as `system.conf`, format `verity`, slot `rootfs` from
   `my-image-${MACHINE}.splitted-rootfs.ext4`).
2. Make a visible change: `DISTRO_VERSION = "1.1"` in `mydistro.conf`. Build:
   ```
   PC$ bitbake my-bundle
   ```
3. Check the bundle on the PC, see [verify.md: RAUC bundle](verify.md#rauc-bundle).
4. Copy it to a FAT USB stick:
   ```
   $ lsblk -o NAME,SIZE,FSTYPE,LABEL,MOUNTPOINT
   $ cp -L tmp/deploy/images/stm32mp25-disco/my-bundle-stm32mp25-disco.raucb /media/$USER/<LABEL>/
   $ sync && umount /media/$USER/<LABEL>
   ```
5. Install on the board:
   ```
   board# mkdir -p /mnt/usb && mount /dev/sda1 /mnt/usb
   board# rauc install /mnt/usb/my-bundle-stm32mp25-disco.raucb
   board# umount /mnt/usb
   board# rauc status
   board# reboot
   ```

**Expected:** after the install, `x` (activated, next boot) is on the other slot, `o` on the
running one. `x` is **not** the running slot; the running one says `booted`. After the reboot:
`Booting slot B ...`, `/etc/os-release` shows `1.1`.

## 12e Rollback test

1. Build version 1.2, install it (it goes into the slot you are not running from).
2. Simulate an update that never finishes booting: during each of the next three boots, press
   the reset button while the kernel is starting, before the login prompt.
3. Let the fourth boot run.

**Expected:** U-Boot gives up on the new slot (`attempts left: A=0`) and boots the old one.
```
board# rauc status
board# fw_printenv | grep BOOT_
```
The new slot is `bad`, the old one `booted` and `good`.

## Errors you may see
- `fw_printenv`: `Cannot read environment, using default`: wrong device or offset in
  `fw_env.config`. Check with `hexdump` (12b step 5).
- `mount: mounting /dev/sda1 on /mnt/usb failed: No such file or directory`: create the folder
  first, `mkdir -p /mnt/usb`.
- `scp` to the board fails: the board uses Dropbear; use `scp -O`, or a USB stick.
- BusyBox `head` needs `head -n 30`, not `head -30`.
