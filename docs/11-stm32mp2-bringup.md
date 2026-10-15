# 11 STM32MP257F-DK bring-up

**Goal:** build the same layer, distro and image for real hardware with ST's BSP layer, flash
it to the SD card and boot it.
Learning log: [step 11](../README.md#step-11-building-for-real-hardware-stm32mp257f-dk).

## Hardware
- STM32MP257F-DK board, microSD card (16 GB or more).
- **CN21** (USB-C, ST-LINK): power and serial console. Needs a USB-C power supply that delivers
  5 V / 3 A. A red LED only means power; a weak supply won't boot the board.
- **CN15** (USB-C): flashing with STM32CubeProgrammer. Use a **data** cable: with a charge-only
  cable, `lsusb` shows only the ST-LINK and the programmer finds no device.
- Boot switches: one setting for "USB boot" (flashing) and one for "SD card boot". See the
  board's user manual or ST's wiki for the positions.
- On the PC: [STM32CubeProgrammer](https://www.st.com/en/development-tools/stm32cubeprog.html)
  (`STM32_Programmer_CLI`) and a serial terminal:
  ```
  $ picocom -b 115200 /dev/ttyACM0
  ```

Optional, but recommended: flash ST's prebuilt starter package first. If that boots, the
board, cables and switches are fine, and any later problem is in your build.

## Boot chain
ROM code → **TF-A BL2** (`fsbla`) → **FIP** (`fip-a`: TF-A BL31, OP-TEE, U-Boot) → U-Boot →
Linux kernel → rootfs. The flash layout file (`.tsv`) lists all partitions of the SD card.

## Steps

1. Get the BSP layer and its dependencies next to poky:
   ```
   $ cd ~/yocto
   $ git clone -b scarthgap https://git.openembedded.org/meta-openembedded
   $ git clone -b openstlinux-6.6-yocto-scarthgap-mpu-v26.06.10 https://github.com/STMicroelectronics/meta-st-stm32mp.git
   ```
2. Create a second build folder for the board:
   ```
   $ cd poky
   $ source oe-init-build-env build-stm32mp
   PC$ bitbake-layers add-layer ../../meta-openembedded/meta-oe ../../meta-openembedded/meta-python \
         ../../meta-st-stm32mp ../../meta-mylayer
   ```
3. In `build-stm32mp/conf/local.conf`:
   ```
   MACHINE = "stm32mp25-disco"
   DISTRO = "mydistro"
   DL_DIR = "${HOME}/yocto-downloads"
   SSTATE_DIR = "${HOME}/yocto-sstate"
   INHERIT += "rm_work"
   # ST's GPU driver needs an EULA; without it, keep the open etnaviv driver out
   MACHINE_FEATURES:append = " nogpu"
   ```
4. My kernel fragment must also reach ST's kernel recipe. ST's recipe only merges the files
   listed in `KERNEL_CONFIG_FRAGMENTS`, so
   [`linux-stm32mp_%.bbappend`](../recipes-kernel/linux/linux-stm32mp_%.bbappend) has:
   ```
   FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
   SRC_URI += "file://exfat.cfg"
   KERNEL_CONFIG_FRAGMENTS:append = " ${WORKDIR}/exfat.cfg"
   ```
   It must be `:append`: the recipe sets `KERNEL_CONFIG_FRAGMENTS:aarch64 = "..."`, which
   replaces anything added with `+=`.
5. Build:
   ```
   PC$ bitbake my-image
   ```
6. Check before flashing:
   ```
   PC$ D=tmp/deploy/images/stm32mp25-disco
   PC$ grep EXFAT tmp/work-shared/stm32mp25-disco/kernel-build-artifacts/.config
   PC$ debugfs -R "cat /etc/modprobe.d/blacklist.conf" $D/my-image-stm32mp25-disco.splitted-rootfs.ext4
   ```
   **Expected:** `CONFIG_EXFAT_FS=m`, and `blacklist etnaviv`.
7. Flash: set the boot switches to USB boot, connect CN15 and power on, then:
   ```
   PC$ cd $D
   PC$ STM32_Programmer_CLI -c port=usb1 -w flashlayout_my-image/optee/FlashLayout_sdcard_stm32mp257f-dk-optee.tsv
   ```
8. Set the boot switches to SD card, open the serial console, power on.

## Check
```
board# cat /etc/os-release
board# uname -a
board# hello && file --version && systemctl status sysmon
board# dmesg | grep mymod
board# modprobe exfat && lsmod | grep exfat
board# lsmod | grep -E "etnaviv|galcore"
```
**Expected:** `My Learning Distro`, kernel `6.6.129` (aarch64), all programs work, `mymod`
loaded with `whom=Dat`, exFAT loads, and **no** GPU driver is loaded (the last command prints
nothing).

## Errors you may see
- `do_rootfs`: `No match for argument: kernel-module-exfat`: the fragment did not reach ST's
  kernel. See step 4 above.
- The board reaches the login prompt, then OP-TEE panics in `clk_stm32_pll_init` and the
  watchdog resets the board, in a loop. The last kernel line is `etnaviv: bound 48280000.gpu`.
  Set `MACHINE_FEATURES:append = " nogpu"`: ST's recipe then blacklists `etnaviv`.
- STM32CubeProgrammer finds no device: check the cable (data, not charge-only) and the boot
  switches.
