# 09 Kernel fragments and an out-of-tree module

**Goal:** change the kernel configuration from your layer, and build your own kernel module
that loads at boot.
Learning log: [step 9a](../README.md#step-9a-kernel-configuration-fragments) and
[9b](../README.md#step-9b-an-out-of-tree-kernel-module).

## Steps (9a: configuration fragment)

1. Write a fragment, [`recipes-kernel/linux/files/exfat.cfg`](../recipes-kernel/linux/files/exfat.cfg):
   ```
   CONFIG_EXFAT_FS=m
   ```
2. Add it to the kernel with [`linux-yocto_%.bbappend`](../recipes-kernel/linux/linux-yocto_%.bbappend):
   ```
   FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
   SRC_URI += "file://exfat.cfg"
   ```
   `linux-yocto` merges every `.cfg` in `SRC_URI` automatically. (ST's kernel does not; see
   guide 11.)
3. `=m` builds a module in its own package. Add `kernel-module-exfat` to `my-image.bb`.
4. Build and boot.

**Check:**
```
PC$ grep EXFAT tmp/work-shared/qemuarm64/kernel-build-artifacts/.config
board# zcat /proc/config.gz | grep EXFAT
board# modprobe exfat && lsmod | grep exfat && grep exfat /proc/filesystems
```
**Expected:** `CONFIG_EXFAT_FS=m` in both places, and the module loads.

**Interactive way to find options:**
```
PC$ bitbake linux-yocto -c menuconfig       (search with "/", set y/m/n, save)
PC$ bitbake linux-yocto -c diffconfig       (writes only your changes to a fragment.cfg)
```
Copy that `fragment.cfg` into the layer under a clear name. Changes made only in `menuconfig`
are lost at the next configure run.

## Steps (9b: out-of-tree module)

1. Copy `Makefile` and `COPYING` from `poky/meta-skeleton/recipes-kernel/hello-mod`.
2. Write [`mymod.c`](../recipes-kernel/mymod/files/mymod.c): a module with a parameter
   (`module_param(whom, charp, 0444)`) that logs on load and unload.
3. Write [`mymod_1.0.bb`](../recipes-kernel/mymod/mymod_1.0.bb) with `inherit module`. Load it at
   boot and set its parameter:
   ```
   KERNEL_MODULE_AUTOLOAD += "mymod"
   KERNEL_MODULE_PROBECONF += "mymod"
   module_conf_mymod = "options mymod whom=Dat"
   ```
4. Add `mymod` to `my-image.bb`, build, boot.

**Check:**
```
board# lsmod | grep mymod
board# dmesg | grep mymod
board# cat /sys/module/mymod/parameters/whom
board# modinfo mymod | grep vermagic
board# rmmod mymod && modprobe mymod whom=Yocto && dmesg | tail -n 2
```
**Expected:** the module is loaded at boot with `whom=Dat`; `vermagic` matches `uname -r`; the
`modprobe` parameter overrides the configured one. `loading out-of-tree module taints kernel`
in `dmesg` is normal for modules built outside the kernel tree.

The generated files `/usr/lib/modules-load.d/mymod.conf` and `/usr/lib/modprobe.d/mymod.conf`
are not in the recipe's `image/` folder: `do_package` creates them later. Find them with
`oe-pkgdata-util list-pkg-files -p mymod`.
