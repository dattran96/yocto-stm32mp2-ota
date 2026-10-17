# 12f Kernel inside each slot

**Goal:** move the kernel and device tree from the shared `bootfs` partition into each rootfs
slot, so a RAUC update can change the kernel, and a rollback restores the old kernel too.
Learning log: [step 12f](../README.md#step-12-ota-updates-with-rauc-in-progress).

> Since [guide 13](13-secure-boot.md) the A/B logic is built into U-Boot and `boot.scr` is gone. The steps here show the earlier boot script; its files are in [git history](https://github.com/dattran96/yocto-stm32mp2-ota/blob/1ed09c9/recipes-bsp/ab-bootscript).

## Background
ST builds one rootfs tree and then **splits** it (`image_types-stsplitpartitions.bbclass`): the
content of `/boot` becomes the `bootfs` partition image, and is removed from the rootfs image.
Which folder is split off comes from `STM32MP_BOOTFS_MOUNTPOINT ?= "/boot"`. This is only a build
step; at runtime, `/etc/fstab` decides what is mounted where.

## Steps

1. In `local.conf`, move bootfs to another folder, so `/boot` stays in the rootfs:
   ```
   STM32MP_BOOTFS_MOUNTPOINT = "/bootfs"
   ```
   `ab-bootscript` installs `boot.scr` to `${STM32MP_BOOTFS_MOUNTPOINT}`, so it still goes to the
   bootfs partition.
2. In `boot.cmd`, load the kernel and device tree from the selected slot's partition. At this
   step:
   ```
   load mmc ${devnum}:${rootpart} ${kernel_addr_r} /boot/Image.gz
   load mmc ${devnum}:${rootpart} ${fdt_addr_r} /boot/${fdtfile}
   booti ${kernel_addr_r} - ${fdt_addr_r}
   ```
   (12g replaces these lines with a FIT image.)
3. Build, check, flash once (bootfs and both rootfs slots change):
   ```
   PC$ bitbake my-image
   PC$ D=tmp/deploy/images/stm32mp25-disco
   PC$ debugfs -R "ls -l /" $D/my-image-stm32mp25-disco.splitted-bootfs.ext4
   PC$ debugfs -R "ls -l /boot" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4
   ```
   **Expected:** bootfs contains only `boot.scr`; the rootfs `/boot` contains the kernel and the
   device trees.

## Test: update only the kernel

1. Make the kernel visibly different: [`localversion.cfg`](../recipes-kernel/linux/files/localversion.cfg)
   with `CONFIG_LOCALVERSION="-mydistro"`, added to `linux-stm32mp_%.bbappend` like `exfat.cfg`.
2. Raise `DISTRO_VERSION`, otherwise both slots report the same version.
3. `bitbake my-bundle`, install with RAUC (guide 12, 12d), reboot.

**Expected:**
```
board# uname -r            6.6.129-mydistro
board# lsmod | grep mymod  still loaded (rebuilt for the new kernel)
```
Then go back to the other slot:
```
board# rauc status mark-active other
board# reboot
board# uname -r            6.6.129
```
