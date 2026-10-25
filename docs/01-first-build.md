# 01 First build with poky

**Goal:** build a minimal Linux for an emulated ARM64 machine and boot it in QEMU.
Learning log: [step 1](learning-log.md#step-1-first-build-with-poky).

## Prerequisites
- A Linux PC set up for Yocto (packages from the
  [Yocto system requirements](https://docs.yoctoproject.org/5.0/ref-manual/system-requirements.html)),
  at least 100 GB of free disk space.

## Steps

1. Get poky, branch `scarthgap` (Yocto 5.0 LTS):
   ```
   $ mkdir -p ~/yocto && cd ~/yocto
   $ git clone -b scarthgap https://git.yoctoproject.org/poky
   $ cd poky
   ```
2. Create the build environment. This creates `build/` and changes into it:
   ```
   $ source oe-init-build-env build
   ```
3. In `conf/local.conf`, set the machine:
   ```
   MACHINE ?= "qemuarm64"
   ```
4. Optional, but saves hours later: keep downloads and the sstate cache outside the build
   folder, so other build folders can share them:
   ```
   DL_DIR = "${HOME}/yocto-downloads"
   SSTATE_DIR = "${HOME}/yocto-sstate"
   ```
5. Build. The first build takes hours, because it builds the cross compiler too:
   ```
   PC$ bitbake core-image-minimal
   ```
6. Boot it:
   ```
   PC$ runqemu core-image-minimal nographic
   ```
   Log in as `root` (no password). Quit QEMU with `Ctrl-A`, then `X`.

## Check

```
PC$ ls tmp/deploy/images/qemuarm64/
```
**Expected:** the kernel `Image`, the root filesystem `core-image-minimal-qemuarm64.rootfs.ext4`,
a `.manifest` (list of packages) and a `.qemuboot.conf` (how `runqemu` starts the image).
