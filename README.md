# yocto-stm32mp2-ota

Embedded Linux with Yocto on the **STM32MP257F-DK** board: my own distro and image, board
bring-up with ST's BSP, and **A/B over-the-air updates with RAUC**, tested on real hardware,
including automatic rollback.

**Highlights**
- **A/B OTA updates with RAUC:** two rootfs slots in ST's flash layout, a U-Boot boot script with
  attempt counters and fallback, signed `verity` bundles, and a rollback test that interrupts the
  first boots of an update ([step 12](#step-12-ota-updates-with-rauc-in-progress)).
- **Board bring-up and BSP debugging:** traced an OP-TEE panic in the secure firmware back to a
  GPU driver that ST's recipes only blacklist in some configurations; adapted kernel config
  fragments to ST's kernel recipe ([step 11](#step-11-building-for-real-hardware-stm32mp257f-dk)).
- **U-Boot and Linux working together:** manual boot from the U-Boot prompt, the redundant U-Boot
  environment found with `hexdump`, and stable device paths after the SD card's `/dev/mmcblkN`
  name changed between boots.
- **Yocto fundamentals:** own layer, distro and image; recipes for C, CMake and third-party
  projects; `devtool` workflow; kernel configuration fragments and an out-of-tree kernel module;
  SDK; systemd services.

Built on **Yocto 5.0 "Scarthgap" (LTS)**; also runs on the **`qemuarm64`** machine. The layer is
called `meta-mylayer` and uses its own distro configuration (`mydistro`, based on poky with
systemd). It contains:

| Path | What it shows |
|---|---|
| `conf/distro/mydistro.conf` | My own distro: inherits poky and sets the distro policy (systemd as init manager, RAUC enabled) |
| `recipes-hello/hello/` | A recipe written by hand for a small C program (cross-compiled with `${CC}`) |
| `recipes-devtools/file/` | A `.bbappend` that patches an existing poky recipe (`file`), created with `devtool modify` / `devtool finish` |
| `recipes-microsocks/microsocks/` | A recipe for a third-party project from GitHub, created with `devtool add`, plus a local patch |
| `recipes-core/images/my-image.bb` | A custom image recipe (`inherit core-image`) with an SSH server |
| `recipes-sysmon/sysmon/` | A CMake project (`inherit cmake`) packaged as a daemon that starts at boot, with both a SysVinit script and a systemd unit |
| `recipes-bsp/ab-bootscript/` | A U-Boot boot script that selects the A or B root filesystem for RAUC, with boot attempt counters and fallback, and boots the slot's FIT image (kernel + device tree, checked with sha256) |
| `recipes-bsp/u-boot/` | `fw_env.config` so that `fw_printenv` / `fw_setenv` (and RAUC) can access the U-Boot environment from Linux |
| `recipes-core/rauc/` | `rauc-conf.bbappend` with the board's RAUC `system.conf` (two rootfs slots, U-Boot backend) and the public CA certificate |
| `recipes-core/bundles/my-bundle.bb` | A signed RAUC update bundle (`verity` format) with the rootfs of `my-image` |
| `recipes-kernel/mymod/` | An out-of-tree kernel module (`inherit module`) with a parameter, loaded automatically at boot |
| `recipes-kernel/linux/` | `.bbappend`s for `linux-yocto` (QEMU) and `linux-stm32mp` (ST board) that add the same kernel config fragment (exFAT as a module), plus the kernel options RAUC needs for the ST kernel |
| `sdk-example/` | A program cross-compiled outside BitBake with the SDK generated from `my-image` |
| `docs/` | Follow-along guides for each lesson, and a cheat sheet for checking build results |

The [learning log](#learning-log) below records what I did at each step, the
problems I ran into and how I solved them. To repeat a lesson yourself, use the
[follow-along guides](docs/README.md): the commands for the PC and the board, with the
output to expect. [docs/verify.md](docs/verify.md) collects the commands I use to check
build results (image contents, kernel config, FIT image, FIP, RAUC bundle) before flashing.

## Building

Requirements: a Linux host set up for Yocto builds
(see the [Yocto system requirements](https://docs.yoctoproject.org/5.0/ref-manual/system-requirements.html)).

```bash
git clone -b scarthgap https://git.yoctoproject.org/poky
git clone https://github.com/dattran96/yocto-stm32mp2-ota.git meta-mylayer

cd poky
source oe-init-build-env build
bitbake-layers add-layer ../../meta-mylayer

# conf/local.conf:
#   MACHINE ?= "qemuarm64"
#   DISTRO ?= "mydistro"        (my distro from this layer: poky + systemd)
bitbake my-image
runqemu my-image nographic        # log in as root
```

On the target:

```
cat /etc/os-release        # My Learning Distro 1.0 (learning)
hello                      # Hello from my own Yocto layer!
file -v                    # file-5.45 (patched by Dat)
microsocks -p 1080 &       # prints "Learn yocto", SOCKS5 proxy on port 1080
systemctl status sysmon    # CMake daemon started at boot
journalctl -u sysmon -f    # load and memory logged every 10 s
dmesg | grep mymod         # mymod: hello Dat, ... (kernel module loaded at boot)
modprobe exfat             # exFAT module enabled by a kernel config fragment
```

### On the STM32MP257F-DK board

```bash
git clone -b scarthgap https://git.openembedded.org/meta-openembedded
git clone -b openstlinux-6.6-yocto-scarthgap-mpu-v26.06.10 https://github.com/STMicroelectronics/meta-st-stm32mp.git

cd poky
source oe-init-build-env build-stm32mp
bitbake-layers add-layer ../../meta-openembedded/meta-oe ../../meta-openembedded/meta-python \
    ../../meta-st-stm32mp ../../meta-mylayer

# conf/local.conf:
#   MACHINE = "stm32mp25-disco"
#   DISTRO = "mydistro"
#   MACHINE_FEATURES:append = " nogpu"   (ST GPU EULA not accepted, see step 11)
#   ST_EXTLINUX_PACKAGE = "ab-bootscript" (A/B boot script instead of extlinux.conf, see step 12)
#   STM32MP_BOOTFS_MOUNTPOINT = "/bootfs"  (kernel stays in each rootfs slot, see step 12f)
#   MACHINE_FEATURES:append = " fit"     (kernel + device tree as one FIT image, see step 12g)
#   RAUC_KEY_FILE / RAUC_CERT_FILE = your bundle signing key and certificate (not in this repo)
bitbake my-image

bitbake my-bundle                       # signed RAUC update bundle (.raucb)

# board in USB boot mode, then:
cd tmp/deploy/images/stm32mp25-disco
STM32_Programmer_CLI -c port=usb1 -w flashlayout_my-image/optee/FlashLayout_sdcard_stm32mp257f-dk-optee.tsv
```

From the host (with `runqemu` tap networking, target at `192.168.7.2`):

```bash
ssh root@192.168.7.2
curl --socks5 192.168.7.2:1080 http://192.168.7.1:8000/   # through the proxy on the target
```

## Learning log

### Step 1: First build with poky

- Built `core-image-minimal` for `qemuarm64` and booted it with `runqemu`.
- Learned where BitBake puts its output: `build/tmp/deploy/images/<machine>/`
  (kernel `Image`, root filesystem `.ext4`, `.manifest`, `.qemuboot.conf`).

### Step 2: Adding packages to an image

- Added `strace` and `file` with `IMAGE_INSTALL:append = " strace file"` in `local.conf`.
- Learned why the leading space in `:append` matters, and that the rebuild was quick
  because BitBake reused the shared-state (sstate) cache and only built what changed.
- Checked the result in the image `.manifest` and on the running target.

### Step 3: My own layer and a hello-world recipe

- Created the layer with `bitbake-layers create-layer` / `add-layer`.
- Wrote `hello_1.0.bb`: `LICENSE` / `LIC_FILES_CHKSUM`, `SRC_URI = "file://..."`,
  `do_compile` and `do_install` into `${D}${bindir}`.
- **Cross-compiling:** `${CC}` expands to `aarch64-poky-linux-gcc -mcpu=cortex-a57+crc ... --sysroot=...`.
  The compiler runs on the x86-64 host but produces an ARM64 binary (checked with `file`).
  Yocto builds this cross toolchain itself from `gcc-cross_13.4.bb`.
- Traced where the target architecture comes from:
  `MACHINE` → `conf/machine/qemuarm64.conf` → `tune-cortexa57.inc`
  (`TARGET_ARCH = "aarch64"`) → `TARGET_SYS` / `HOST_PREFIX` in `bitbake.conf` → `CC`.
  Used `bitbake-getvar -r <recipe> <VAR>` to inspect resolved values.
- Explored the recipe work directory (`tmp/work/cortexa57-poky-linux/hello/1.0/`):
  `temp/log.do_*`, `temp/run.do_*`, `image/`, `packages-split/`.

### Step 4: Patching an existing recipe with devtool

- `devtool modify file` → edited the source in `workspace/sources/file` → committed →
  `devtool finish file meta-mylayer`, which generated a patch and a `file_%.bbappend`.
- **Problem 1:** `QA Issue: Missing Upstream-Status in patch [patch-status]`.
  Patches to core-layer recipes must state their upstream status. Fixed by adding
  `Upstream-Status: Inappropriate [local customization for learning]` to the patch header.
- **Problem 2:** the `.bbappend` also patched `file-native` (the build-host tool),
  which caused hundreds of dependent tasks to rebuild. Fixed by restricting the patch
  to the target with `SRC_URI:append:class-target = " file://..."`.

### Step 5: A custom image recipe

- Wrote `my-image.bb` with `inherit core-image`, an explicit `IMAGE_INSTALL`
  (`packagegroup-core-boot` + my packages) and `IMAGE_FEATURES += "ssh-server-dropbear"`.
- Moved package selection out of `local.conf` into the layer so the image is reproducible.
- **Problem:** `runqemu qemuarm64 my-image` failed with
  `IMAGE_LINK_NAME wasn't set to find corresponding .qemuboot.conf file`.
  Reading `scripts/runqemu` showed that when a machine name is given, it runs
  `bitbake -e` without the image target, so `IMAGE_LINK_NAME` is missing.
  Solved by running `runqemu my-image nographic` (the machine comes from `local.conf`).
- Logged in to the target over SSH from the host (`ssh root@192.168.7.2`).

### Step 6: Adding a third-party project with devtool add

- `devtool add microsocks https://github.com/rofl0r/microsocks` generated a recipe
  with `SRC_URI`, a pinned `SRCREV` and `LIC_FILES_CHKSUM`.
- Reviewed the draft: the license was not recognised (`LICENSE = "Unknown"`); upstream
  `COPYING` is MIT, so I set `LICENSE = "MIT"`. Checked that `do_install` passes
  `prefix=${prefix}`, because the Makefile defaults to `/usr/local`.
- Fast edit/test loop without rebuilding the image:
  `devtool build microsocks && devtool deploy-target microsocks root@192.168.7.2`.
- **Problem:** my added `printf` only appeared when the program exited. Cause: stdout
  buffering, since the string had no `\n` and the server never exits. Fixed by adding `\n`.
- **Problem:** `devtool finish` refused with `Source tree is not clean: ?? microsocks`.
  The Makefile builds in the source directory, so the compiled binary was untracked.
  Removed the build artifact rather than using `--force`.
- Put `Upstream-Status:` in the commit message so the generated patch passed QA
  without editing.
- Added `microsocks` to `my-image` and verified it in the booted image.

### Step 7: Building and using an SDK

- Built a standard SDK for the image with `bitbake my-image -c populate_sdk`. The result is
  a self-extracting installer (`tmp/deploy/sdk/poky-glibc-x86_64-my-image-cortexa57-qemuarm64-toolchain-5.0.20.sh`, 183 MB).
- The SDK has two parts, each with its own manifest: host tools that run on any x86-64 PC
  (the `nativesdk` cross-compiler, `pkg-config`, ...) and a target sysroot with the headers and
  libraries of `my-image` (e.g. `libz-dev 1.3.1`, matching `libz1 1.3.1` in the image).
- Installed it without root (`-d ~/yocto-sdk/my-image`) and used it in a clean shell with no
  BitBake environment: `source environment-setup-cortexa57-poky-linux` sets `CC`, `CFLAGS`,
  `LDFLAGS` and `PKG_CONFIG_*`, just like BitBake does for recipes.
- Cross-compiled [`sdk-example/zversion.c`](sdk-example/zversion.c) against zlib from the SDK sysroot:
  ```bash
  $CC $CFLAGS $LDFLAGS zversion.c -o zversion -lz
  scp zversion root@192.168.7.2:/tmp/ && ssh root@192.168.7.2 /tmp/zversion
  ```
  For comparison, the same file built with the host `gcc` is an x86-64 binary linked
  against the host's zlib, and it does not run on the target.
- **Problem:** the SDK build failed with `No space left on device`. `tmp/work` had grown to
  62 GB, because BitBake keeps every recipe's work directory, and the SDK added a second
  toolchain (`gcc-cross-canadian`, `nativesdk-qemu`, ...) of about 20 GB.
  The disk space monitor (`BB_DISKMON_DIRS`) first stopped starting new tasks (`STOPTASKS`) and
  then halted the build (`HALT`).
  **Fix:** `INHERIT += "rm_work"` (with `RM_WORK_EXCLUDE` for the recipes I develop) and a
  clean `tmp/`. Finished recipes are restored from the sstate cache, and `tmp/` dropped from
  72 GB to 16 GB.

### Step 8: A CMake project as a boot service

- Wrote `sysmon`, a small C daemon that logs the load average and available memory to
  syslog at a fixed interval and exits cleanly on `SIGTERM`, with a `CMakeLists.txt` that uses
  `GNUInstallDirs` instead of hard-coded paths.
- The recipe has no `do_configure` / `do_compile`: `inherit cmake` runs CMake with Yocto's
  toolchain file and installs into `${D}`. The build happens in a separate build directory (`B` ≠ `S`).
- `SRC_URI = "file://sysmon"` fetches a whole directory; `S = "${WORKDIR}/sysmon"`.
- Supports both init systems, like the recipes in poky:
  - SysVinit: `inherit update-rc.d`, `INITSCRIPT_NAME` / `INITSCRIPT_PARAMS = "defaults 90"`, and an
    init script using `start-stop-daemon` (background, pid file, stop by `SIGTERM`).
  - systemd: `inherit systemd`, `SYSTEMD_SERVICE:${PN}` and a `.service` unit.
  - `do_install:append` uses `bb.utils.contains('DISTRO_FEATURES', ...)` so only the files for
    the active init system are installed (this build uses `INIT_MANAGER = "sysvinit"`).
- Verified on the target: the service starts at boot via `/etc/rc5.d/S90sysmon`, logs to
  `/var/log/messages` every 10 s, and `/etc/init.d/sysmon stop|start|status` work
  (the log shows `stopped` from the signal handler, then a new PID).
- **Problem:** `ERROR: Nothing PROVIDES 'sysmon'`. The recipe was in `recipes-sysmon/` instead of
  `recipes-sysmon/sysmon/`. `BBFILES` in `conf/layer.conf` only matches `recipes-*/*/*.bb`,
  so the file was never parsed. Moved the recipe and `files/` one level down.

### Step 8b: Switching the image from SysVinit to systemd

- Set `INIT_MANAGER = "systemd"` in `local.conf`. In poky this pulls in
  `conf/distro/include/init-manager-systemd.inc`, which adds `systemd usrmerge` to
  `DISTRO_FEATURES` and points the `VIRTUAL-RUNTIME_*` variables (init manager, device manager,
  init scripts) to systemd packages. Checked the result with `bitbake-getvar`.
- Because `DISTRO_FEATURES` is part of almost every task signature, nearly the whole image was
  rebuilt. The SysVinit results stay in the sstate cache, so switching back is quick.
- The `sysmon` recipe was **not changed**: with systemd in `DISTRO_FEATURES` it installs
  `sysmon.service` instead of `/etc/init.d/sysmon`, and the `systemd` class enables the unit.
- In the image manifest, `sysvinit`, `initscripts` and `eudev` are replaced by `systemd`,
  `systemd-udev-rules`, `systemd-serialgetty` and `systemd-compat-units` (96 packages in total).
- **Finding:** `busybox-syslog` is still installed next to the systemd journal, because
  `packagegroup-core-boot` always pulls in `${VIRTUAL-RUNTIME_base-utils-syslog}` and poky
  defaults it to `busybox-syslog` (`default-providers.inc`). Two loggers is a candidate for
  later image optimization.
- Trade-off: systemd costs more flash and RAM than SysVinit + BusyBox, but adds restart on
  failure, a structured journal, dependency-based parallel startup and `systemd-analyze`.
  A real decision needs measurements (e.g. with `buildhistory` / `buildhistory-diff`).

### Step 9a: Kernel configuration fragments

- `linux-yocto` builds its `.config` from a base configuration plus small fragments. Added my own
  fragment from the layer: `recipes-kernel/linux/files/exfat.cfg` (`CONFIG_EXFAT_FS=m`) and
  `linux-yocto_%.bbappend` with `SRC_URI += "file://exfat.cfg"`. The kernel classes merge `.cfg`
  files from `SRC_URI` automatically, and `do_kernel_configcheck` warns if a requested option
  does not end up in the final `.config`.
- Verified `CONFIG_EXFAT_FS=m` in `tmp/work-shared/qemuarm64/kernel-build-artifacts/.config`.
  Kconfig added the dependent default `CONFIG_EXFAT_DEFAULT_IOCHARSET="utf8"` by itself.
- `=m` builds a loadable module that is packaged separately as `kernel-module-exfat`, so it
  had to be added to `IMAGE_INSTALL` in `my-image.bb`. The running kernel exposes its
  configuration in `/proc/config.gz` (`CONFIG_IKCONFIG_PROC=y`).
- Tested on the target: `zcat /proc/config.gz | grep EXFAT` shows `CONFIG_EXFAT_FS=m`, and after
  `modprobe exfat` the module appears in `lsmod` and `exfat` is listed in `/proc/filesystems`.
- Practised the interactive workflow:
  1. `bitbake linux-yocto -c menuconfig`: find an option with `/`, set it with `y` / `m` / `n`, save.
  2. `bitbake linux-yocto -c diffconfig`: writes only the changes to `${WORKDIR}/fragment.cfg`
     (tried with `NTFS3_FS`, which produced `CONFIG_NTFS3_FS=m` plus its sub-options).
  3. Copy the fragment into the layer under a meaningful name and add it to `SRC_URI`.
  Changes made only in `menuconfig` are temporary: the next configuration run regenerates
  `.config` from the recipe and the layer's fragments.
- **Observation:** running `diffconfig` after `menuconfig` on an option that was already set by
  my fragment produced no file at all. The configuration had not changed, so there was nothing to write.
- Decided **not** to keep the NTFS3 fragment: every enabled option adds kernel size, attack surface
  and maintenance, so a fragment should only exist for a feature the product needs.

### Step 9b: An out-of-tree kernel module

- Wrote `mymod`, a kernel module with a string parameter (`module_param(whom, charp, 0444)`) that
  logs a greeting on load and unload. `Makefile` and `COPYING` (GPL-2.0) were taken from the
  template in `poky/meta-skeleton/recipes-kernel/hello-mod`. The Makefile only sets
  `obj-m := mymod.o` and calls the kernel's build system with `-C $(KERNEL_SRC)`.
- The recipe uses `inherit module`, which builds the module against the exact kernel of the image
  (`KERNEL_SRC=${STAGING_KERNEL_DIR}`). Because of that the recipe is machine-specific
  (`tmp/work/qemuarm64-poky-linux/mymod`), and the module ends up in the package
  `kernel-module-mymod-6.6.147-yocto-standard`. The `mymod` package itself is empty and depends on it.
- `KERNEL_MODULE_AUTOLOAD += "mymod"` and `KERNEL_MODULE_PROBECONF` / `module_conf_mymod =
  "options mymod whom=Dat"` generate `/usr/lib/modules-load.d/mymod.conf` and
  `/usr/lib/modprobe.d/mymod.conf` (under `/usr/lib` because of systemd + `usrmerge`).
- **Finding:** these `.conf` files are not in `image/` (the output of `do_install`). They are
  created later in `do_package` by `kernel-module-split`, so they appear in `packages-split/` and
  in `tmp/pkgdata`. `image/` also contained `Module.symvers` for other modules to build against.
- **Finding:** after the first build only `temp/` was left in the work directory, because
  `rm_work` had removed it. Package contents can still be checked in
  `tmp/pkgdata/<machine>/runtime/<package>` (`FILES_INFO`). To inspect the work directory, the
  recipe was added to `RM_WORK_EXCLUDE` and rebuilt with `bitbake mymod -c cleansstate && bitbake mymod`,
  because a normal rebuild only restores the packages from sstate.
- Tested on the target: the module is loaded at boot with `whom=Dat` (`lsmod`, `dmesg`,
  `/sys/module/mymod/parameters/whom`). `modinfo` shows the `vermagic` that ties it to
  `6.6.147-yocto-standard`. `rmmod` runs the exit function, and `modprobe mymod whom=Yocto`
  overrides the configured parameter. `dmesg` reports `loading out-of-tree module taints kernel`,
  which is the expected taint flag `O` for modules built outside the kernel tree.

### Step 10: My own distro configuration

- Separated the three kinds of configuration: **MACHINE** (hardware), **DISTRO** (policies such
  as the init manager and `DISTRO_FEATURES`) and **IMAGE** (package selection). `local.conf` is
  only for build-host settings.
- **Problem found:** `INIT_MANAGER = "systemd"` was only in my `local.conf`, so a fresh clone of
  this repo would have produced a SysVinit image. Moved it into `conf/distro/mydistro.conf`.
- `mydistro.conf` uses `require conf/distro/poky.conf` and overrides only what differs (name,
  version, maintainer, init manager), following the example of `meta-poky/conf/distro/poky-altcfg.conf`.
  BitBake finds it through `BBPATH` as `conf/distro/${DISTRO}.conf`; `local.conf` only sets `DISTRO ?= "mydistro"`.
- Checked the values with `bitbake-getvar`. Without `--value` it shows the history of a variable,
  i.e. that `poky.conf` sets a default for `INIT_MANAGER` and `mydistro.conf` overrides it.
- On the target, `/etc/os-release` shows `My Learning Distro 1.0 (learning)` and sysmon still runs
  under systemd.
- The `WARNING: Poky is a reference Yocto Project distribution ...` login message disappeared. It
  comes from `meta-poky/recipes-core/base-files/files/poky/motd`, and files in a `poky/` folder are
  only used when `DISTROOVERRIDES` contains `poky`.

### Step 11: Building for real hardware (STM32MP257F-DK)

- First flashed ST's prebuilt starter package (`st-image-weston`) with STM32CubeProgrammer to
  check the board, the boot switches and the serial console (`picocom -b 115200 /dev/ttyACM0`).
- Learned the STM32MP2 boot chain: boot ROM → TF-A (BL2) → FIP (TF-A BL31, OP-TEE, U-Boot)
  → Linux kernel → rootfs. The flash layout (`.tsv`) lists every partition of the SD card
  (`fsbla1/2`, `metadata1/2`, `fip-a/b`, `u-boot-env`, `bootfs`, `vendorfs`, `rootfs`, `userfs`)
  and the USB loader images that STM32CubeProgrammer uses to write them.
- Created a second build directory `build-stm32mp` with `meta-oe`, `meta-python` and
  `meta-st-stm32mp` (tag `openstlinux-6.6-yocto-scarthgap-mpu-v26.06.10`), `MACHINE =
  "stm32mp25-disco"` and the **same layer, distro and image** as for QEMU. Downloads and the
  sstate cache are shared between both builds; the sstate match was only 33 %, because the board
  uses a different CPU tune (`cortexa35` instead of `cortexa57`). The BSP adds the boot firmware
  recipes (`tf-a-stm32mp`, `optee-os-stm32mp`, `u-boot-stm32mp`, `fip-stm32mp`) and generates the
  flash layouts in `tmp/deploy/images/stm32mp25-disco/flashlayout_my-image/`.
- **Problem:** `do_rootfs` failed with `No match for argument: kernel-module-exfat`. The board uses
  ST's kernel recipe `linux-stm32mp`, so my `linux-yocto_%.bbappend` never applied, and the
  `.config` had `# CONFIG_EXFAT_FS is not set`. In addition, ST's recipe does not merge `.cfg` files
  from `SRC_URI` like `linux-yocto`: it only merges the files listed in `KERNEL_CONFIG_FRAGMENTS`
  with the kernel's `merge_config.sh`. **Fix:** `linux-stm32mp_%.bbappend`, which reuses the same
  `exfat.cfg` with `KERNEL_CONFIG_FRAGMENTS:append = " ${WORKDIR}/exfat.cfg"`. It has to be
  `:append`, not `+=`, because the recipe sets `KERNEL_CONFIG_FRAGMENTS:aarch64 = "..."`, and that
  override assignment replaces the whole variable, including anything added with `+=`.
- **Problem:** the board booted to the login prompt, then OP-TEE panicked in
  `clk_stm32_pll_init` and the watchdog reset the board in a loop. The last kernel message was
  `etnaviv: bound 48280000.gpu`. The open-source GPU driver `etnaviv` asked the secure firmware
  for a GPU clock, which crashed it. ST's images never load `etnaviv`: `linux-stm32mp.inc` writes
  `blacklist etnaviv` to `/etc/modprobe.d/blacklist.conf`, but only if `MACHINE_FEATURES`
  contains `gpu` (added when the ST GPU EULA is accepted) or `nogpu`. I had not accepted the EULA,
  so neither was set. **Fix:** `MACHINE_FEATURES:append = " nogpu"` in `local.conf`, the switch
  ST provides for boards without its GPU driver.
- Checked the fix before flashing by reading the file straight from the image with
  `debugfs -R "cat /etc/modprobe.d/blacklist.conf" ...splitted-rootfs.ext4`, because `rm_work`
  had already deleted the image's work directory.
- Tested on the board: `/etc/os-release` shows `My Learning Distro 1.0`, kernel `6.6.129` (aarch64),
  `hello` and the patched `file --version` work, `sysmon` runs under systemd, `mymod` is loaded at
  boot with `whom=Dat`, `modprobe exfat` works, and neither `etnaviv` nor `galcore` is loaded.

### Step 12: OTA updates with RAUC (in progress)

Goal: A/B updates of the root filesystem on the STM32MP257F-DK. The board runs from one rootfs
slot while RAUC writes a signed update bundle into the other one; U-Boot then boots the new slot
and falls back to the old one if the new system does not boot.

Chose RAUC over Mender: Mender also builds the SD card image itself and patches U-Boot
automatically, which conflicts with ST's flash layout tooling, and hides the A/B mechanics I want
to learn. The concepts (A/B slots, boot counting, rollback, signed bundles) are the same.

**12a: Adding meta-rauc**
- Cloned `meta-rauc` (branch scarthgap, RAUC 1.15.2) and added it to both builds.
- Read the layer's README first. It asks for three things: `rauc` in `DISTRO_FEATURES` (added to
  `mydistro.conf`, since it is a distro policy; the layer's sanity check warns if it is missing),
  a `rauc-conf.bbappend` with the board's `system.conf` and keyring (next steps), and `rauc` in
  `IMAGE_INSTALL`, because `my-image` uses `packagegroup-core-boot` instead of `packagegroup-base`.
- **Same trap as in step 11:** `meta-rauc` ships a kernel fragment (`rauc.cfg`), but only for
  `linux-yocto`. Comparing it with the ST kernel's `.config` showed `CONFIG_BLK_DEV_DM=m` and
  `# CONFIG_DM_VERITY is not set`. RAUC's `verity` bundle format is a SquashFS image that is
  checked by dm-verity while it is read, so installing a bundle would have failed on the board.
  Added my own `rauc.cfg` to `linux-stm32mp_%.bbappend`, only when the distro enables RAUC
  (`bb.utils.contains('DISTRO_FEATURES', 'rauc', ...)`), like `meta-rauc` does for `linux-yocto`.
- Result: `CONFIG_BLK_DEV_DM=y` and `CONFIG_DM_VERITY=y`, and the image contains `rauc`,
  `rauc-conf`, `rauc-service` and `rauc-mark-good`. The build warns that `system.conf` and
  `ca.cert.pem` are still the layer's examples.
- **Observation:** adding a `DISTRO_FEATURES` entry rebuilt only about 400 tasks, unlike the
  systemd switch. A task's signature only changes if the task actually uses the variable, and few
  recipes check for `rauc`; switching to systemd changed many more variables and recipes.

**12b: Two rootfs slots and an A/B boot script**
- ST's flash layout generator can duplicate partitions (that is how `fsbla1`/`fsbla2` exist). Setting
  the copy field of `PARTITIONS_IMAGES[rootfs]` to `2` in `my-image.bb` produced `rootfs1` and
  `rootfs2` (4 GiB each), both filled with the same image, and moved `userfs` behind them.
- Explored U-Boot on the board (`printenv`, `part list`): the distro boot scans the partition
  with the bootable flag (`bootfs`) for `extlinux/extlinux.conf` first, then for `boot.scr`.
  Booted Linux by hand from the U-Boot prompt (`part number`, `part uuid`, `load`, `setenv
  bootargs`, `booti`) to test each command before writing a script.
- Wrote `boot.cmd` (RAUC's standard U-Boot logic): take the first slot in `BOOT_ORDER` with
  attempts left, decrement `BOOT_x_LEFT`, `saveenv`, then boot with `root=PARTUUID=<uuid of
  rootfs1/2>` and `rauc.slot=A/B`. The partition UUID is looked up by name at boot time, because
  STM32CubeProgrammer gives the new partitions random UUIDs. The recipe `ab-bootscript` builds it
  with `mkimage -T script` and installs it to `/boot`, which ST splits into `bootfs`.
  `ST_EXTLINUX_PACKAGE ??= "stm32mp-extlinux"` in ST's machine config is a weak default, so
  setting it to `ab-bootscript` replaces `extlinux.conf` without touching ST's layer.
- Linux side: `fw_printenv` / `fw_setenv` need `/etc/fw_env.config`. ST only ships examples
  (`fw_env.config.mmc`) that point to `/dev/disk/by-partlabel/u-boot-env`. Found the real layout
  with `hexdump`: the environment is in the last 8 KiB of the partition, and the flag byte after
  the CRC shows a **redundant environment** (two copies; U-Boot writes the copy not in use, so a
  power cut while saving can never destroy both). Installed my own config through a
  `u-boot-fw-config-stm32mp_%.bbappend`.
- **Problem: device names are not stable.** The board also has an 8 GB eMMC with an old ST image
  and the same partition names, so `by-partlabel` links pointed to a mix of both devices. And the
  SD card was `mmcblk0` in one boot and `mmcblk1` in the next (the kernel numbers MMC devices in
  probe order, including the SDIO WiFi controller). My first `fw_env.config` with `/dev/mmcblk0p7`
  suddenly pointed to the eMMC; `fw_printenv` refused because the checksum did not match, before
  anything was written. Fixed with `/dev/disk/by-path/...48220000.mmc-part7`, which is based on
  the SD controller's address in the chip.
- Tested on the board: booting slot A three times without marking it good used up its attempts,
  and U-Boot fell back to slot B by itself (`Booting slot B (rootfs2), attempts left: A=0 B=2`,
  `/` on `...p11`, `rauc.slot=B`). Resetting the counters and `BOOT_ORDER` with `fw_setenv`
  switched back to A. The U-Boot log alternates between `Writing to redundant MMC(0)` and
  `Writing to MMC(0)`, i.e. between the two environment copies.
- Hardware lesson: STM32CubeProgrammer did not find the board because the USB-C cable on the
  flashing port was charge-only (`lsusb` showed only the ST-LINK, no DFU device).
- Not done yet: the kernel is still in the shared `bootfs`, so updates cannot change it.

**12c: RAUC configuration and signing keys**
- Created a development CA and signing key with `meta-rauc/scripts/openssl-ca.sh`. The device only
  installs bundles signed by a key that chains up to the CA certificate in its keyring.
- **The private key is not in this repository.** It lives in `~/yocto-keys/` on the build machine
  and is passed to the build from `local.conf`; only the public `ca.cert.pem` is in the layer, and
  `.gitignore` excludes `*.key.pem` as a safety net. Anyone with the signing key could create
  updates that the devices accept.
- `system.conf`: `compatible` (bundles for other products are rejected), `bootloader=uboot`,
  `bundle-formats=-plain` (only `verity` bundles), and two slots `rootfs.0` / `rootfs.1` with
  `bootname` A / B, addressed by the stable `by-path` links. `rauc-conf.bbappend` only needs
  `FILESEXTRAPATHS:prepend`, because `rauc-conf.bb` already fetches `system.conf` and
  `ca.cert.pem`, and the first match in the search path wins; the build warnings about the
  example files disappeared.
- On the board, `rauc status` shows `Booted from: rootfs.0 (A)`, `Activated: rootfs.0 (A)` and
  both slots `good`. At boot, `rauc-mark-good` ran (`Marked slot rootfs.0 as good`), so the
  attempt counter is now reset automatically instead of with `fw_setenv`.
- `Failed to load status file .../slot.raucs` is expected: without a central status file
  (`data-directory`), RAUC keeps a status file inside each slot, and freshly flashed slots have
  none until RAUC installs into them.

**12d: Building a bundle and installing an update**
- `my-bundle.bb` uses `inherit bundle` with `RAUC_SLOT_rootfs = "my-image"`. The bundle class
  would take `my-image-<machine>.rootfs.ext4`, but that file still contains `/boot`; the slots hold
  ST's split rootfs, so `RAUC_SLOT_rootfs[file]` selects `...splitted-rootfs.ext4`.
  `compatible` matches `system.conf`, the version comes from `DISTRO_VERSION`, and the format is
  `verity`. The signing key and certificate are set in `local.conf`.
- Checked the bundle on the build host before touching the board, with the host version of RAUC
  that BitBake already built: `bitbake rauc-native -c addto_recipe_sysroot`, then
  `oe-run-native rauc-native rauc info --keyring=<ca.cert.pem> <bundle>`. It shows the signature
  check and the certificate chain: signing certificate → issued by the CA → CA trusted because it
  is in the keyring. The bundle carries the signing certificate, so the device only needs the CA
  certificate, and new signing keys from the same CA work without updating the devices.
- Raised `DISTRO_VERSION` to 1.1 as a visible change, copied the bundle to a USB stick and ran
  `rauc install` on the board running 1.0 from slot A. RAUC wrote the image into the inactive slot
  B, stored a status file in it, and set `BOOT_ORDER=B A`. After the reboot U-Boot printed
  `Booting slot B (rootfs2), attempts left: A=3 B=2`, `/etc/os-release` showed `1.1`, and
  `rauc-mark-good` reset B's counter.
- In `rauc status`, `x` marks the **activated** slot (first in `BOOT_ORDER`), not the running one;
  `booted` / `inactive` in parentheses shows which slot is running. Right after an install the
  running slot is `o` and the new slot is `x`.

**12e: Rollback test**
- Built version 1.2 and installed it from the USB stick while 1.1 was running from slot B, so
  RAUC wrote it into slot A and set `BOOT_ORDER=A B`.
- Simulated an update that never finishes booting (e.g. a power cut during the first boot): pressed
  reset three times during the kernel boot, before `rauc-mark-good` could run. Each time U-Boot
  decreased `BOOT_A_LEFT`; with A at 0 attempts it booted slot B on its own.
- Result: the board came up with the old version 1.1 from B. `rauc status` shows A as `bad` and B
  as booted and activated; `fw_printenv` shows `BOOT_ORDER=A B`, `BOOT_A_LEFT=0`, `BOOT_B_LEFT=3`.
  RAUC reports B as activated even though A is first in `BOOT_ORDER`, because a slot without
  attempts left is skipped by the boot script. The next install goes into A again and resets it.

**12f: Kernel and device tree inside each slot**
- So far the kernel was in the shared `bootfs`, so updates could not change it, and new kernel
  modules in an updated rootfs would not have matched the old kernel.
- ST builds one complete root filesystem tree and then *splits* it
  (`image_types-stsplitpartitions.bbclass`): the content of each partition's mount point directory
  (`/boot`, `/vendor`, `/usr/local`) becomes that partition's image and is removed from the rootfs
  image. The mount points come from weak defaults like `STM32MP_BOOTFS_MOUNTPOINT ?= "/boot"`.
  This only happens at build time; which partition is mounted where at runtime is decided by
  `/etc/fstab` (my image mounts nothing besides `/`).
- Set `STM32MP_BOOTFS_MOUNTPOINT = "/bootfs"`: `/boot` with kernel and device trees now stays in
  the rootfs image, i.e. in each slot and in each RAUC bundle, and only `boot.scr` (installed to
  `${STM32MP_BOOTFS_MOUNTPOINT}` by `ab-bootscript`) goes to the `bootfs` partition. `boot.scr`
  now loads `/boot/Image.gz` and `/boot/${fdtfile}` from the selected slot's partition.
- Checked with `debugfs` before flashing (bootfs: only `boot.scr`; rootfs: empty `/bootfs`,
  kernel in `/boot`), and on the board: `/boot` lists the kernel and device trees of the running
  slot, `/bootfs` is empty.
- Proved it with an update that changes only the kernel: a config fragment `localversion.cfg`
  (`CONFIG_LOCALVERSION="-mydistro"`) in `linux-stm32mp_%.bbappend`. Installed the bundle from
  the USB stick while slot A ran `6.6.129`; after the reboot slot B ran `6.6.129-mydistro`, and
  `mymod`, rebuilt for the new kernel, still loaded. `rauc status mark-active other` and a
  reboot brought back slot A with the old `6.6.129`: kernel rollback works the same way as
  rootfs rollback.
- Mistake: I forgot to raise `DISTRO_VERSION`, so both slots reported version 1.2 although they
  run different kernels. A release needs a new version every time, or `rauc status` and
  `/etc/os-release` cannot tell the two builds apart.

**12g: Kernel and device tree as one FIT image**
- A FIT (Flattened Image Tree) packs the kernel, the device trees and a sha256 hash of each into
  one file, plus *configurations* that say which kernel goes with which device tree. U-Boot checks
  the hashes before booting, and it is the format U-Boot can verify signatures on (next step).
- ST's machine feature `fit` switches the kernel recipe to `KERNEL_IMAGETYPE = "fitImage"`:
  `MACHINE_FEATURES:append = " fit"` in `local.conf`. `/boot` in the rootfs now holds `fitImage`
  instead of `Image.gz`.
- Inspected it on the host with `oe-run-native u-boot-tools-native dumpimage -l <fitImage>`: a
  gzip-compressed kernel with load address `0xc4000000`, three device trees, and the default
  configuration `conf-stm32mp257f-dk.dtb`. Checked the memory layout: U-Boot loads the 11 MB FIT
  to `kernel_addr_r` (`0x8a000000`) and unpacks the 28 MB kernel to `0xc4000000`, so they do not
  overlap.
- Installed the FIT build (1.3) with RAUC **before** changing the boot script, on purpose: the
  old `boot.scr` failed with `Failed to load '/boot/Image.gz'`, then ran `booti` on whatever was
  in RAM (`Bad Linux ARM64 Image magic!`) and stopped at the U-Boot prompt. Two lessons:
  - `boot.scr` is in the shared `bootfs`, which RAUC does not update, so an update must not
    change the kernel format unless the installed boot script can handle it.
  - The fallback to the other slot only happens on the *next* reset. A device in the field would
    have waited at the prompt forever.
- Booted the FIT by hand from the U-Boot prompt: `load` it, `iminfo` (lists the contents and
  checks every hash: `sha256+`), set `bootargs`, then `bootm ${kernel_addr_r}#conf-stm32mp257f-dk.dtb`.
  1.3 came up from slot B.
- New `boot.cmd`: `if load ... /boot/fitImage; then bootm ${kernel_addr_r}; fi`, using the FIT's
  default configuration, so the script does not need the device tree name; if loading or
  booting fails it prints a message and runs `reset`. Copied the new `boot.scr` into the bootfs
  partition from Linux (taken out of the bootfs image with `debugfs -R "dump ..."`).
- Tests: slot B booted the FIT on its own (`Verifying Hash Integrity ... sha256+ OK`). Then
  `rauc status mark-active other` selected slot A, which still had 1.2 without a FIT: U-Boot
  printed `Booting slot A failed, resetting` and reset by itself until A had no attempts left,
  then booted B. `rauc status` showed A as `bad`, B booted and good, with nobody pressing reset.
- Mistakes on the way: ran `bitbake` in the QEMU build directory (`source oe-init-build-env`
  without an argument opens `build/`), where the bbappends for ST's recipes have no recipe to
  apply to (`No recipes in default available for: ...linux-stm32mp_%.bbappend`); and looked
  for the FIT in the wrong place, because ST deploys kernel files into a `kernel/` subfolder of
  the deploy directory.
- Side notes: all files in the rootfs show the date 9 March 2018 because of
  `REPRODUCIBLE_TIMESTAMP_ROOTFS` (same inputs give byte-identical images). The loose `.dtb`
  files and ST's splash screens are still in `/boot` but no longer used.

### Other things learned along the way

- `Ctrl-Z` pauses a command instead of cancelling it. A paused `devtool build` kept
  holding the BitBake server, so every following `bitbake` / `devtool` command
  waited forever at `Reconnecting to bitbake server...`.
- Run BitBake commands from the build directory, and use absolute paths (or check the
  current directory) for arguments like the layer path in `devtool finish`.

## Next steps

- [x] Build an SDK (`bitbake my-image -c populate_sdk`) and compile against it outside Yocto
- [x] Write a recipe for a CMake project and a systemd/SysVinit service
- [x] Kernel: configuration fragments
- [x] Kernel: an out-of-tree kernel module recipe, loaded at boot
- [x] Create my own distro config instead of using `poky`
- [x] Build for real hardware (STM32MP257F-DK with `meta-st-stm32mp`)
- [x] OTA updates with RAUC: A/B rootfs, U-Boot boot selection, signed bundles, rollback

Planned, in this order:

1. **OTA stage 2 and verified kernel boot**
   - [x] Kernel and device tree inside each rootfs slot, so updates can change the kernel;
         `boot.scr` loads them from the selected slot
   - [x] Kernel and device tree as one FIT image, hashes checked by U-Boot
   - [ ] Signed FIT image: U-Boot verifies kernel and device tree before booting
2. **Persistent data**
   - [ ] Mount `userfs` as a data partition that survives updates
   - [ ] Central RAUC status file (`data-directory`) on that partition
3. **Device tree and a driver** (I2C sensor, e.g. BME280, on the GPIO expansion header)
   - [ ] Find the I2C controller and pins in the board schematics
   - [ ] Device tree node for the sensor in a `linux-stm32mp` bbappend; test with the existing
         `bmp280` IIO driver and `i2cdetect`
   - [ ] Own I2C driver (`probe()`, `regmap`, sysfs/IIO interface) as an out-of-tree module
   - [ ] Debug the bus with a logic analyzer (wrong address, missing pull-ups)
4. **Secure boot chain**
   - [ ] Sign TF-A and FIP with ST's tools (without closing the OTP fuses, which is irreversible)
   - [ ] Document the chain of trust: ROM → TF-A → FIP → signed FIT → signed RAUC bundles
   - [ ] Optional: read-only rootfs with dm-verity
5. **Connectivity and comparison**
   - [ ] WiFi: chip firmware, `wpa_supplicant`, systemd-networkd config, credentials kept out of
         the repository
   - [ ] Optional: install updates over HTTPS (RAUC streaming or hawkBit)
   - [ ] Optional: Mender on QEMU, compared with RAUC
