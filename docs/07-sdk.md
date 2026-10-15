# 07 Building and using an SDK

**Goal:** build an SDK that application developers can use without Yocto, and cross-compile a
program against a library from the image.
Learning log: [step 7](../README.md#step-7-building-and-using-an-sdk).

## Steps

1. Make room first. The SDK adds a second toolchain of about 20 GB. In `local.conf`:
   ```
   INHERIT += "rm_work"
   RM_WORK_EXCLUDE += "hello microsocks sysmon mymod"
   ```
   `rm_work` deletes each recipe's work folder after the build; finished results stay in the
   sstate cache. The exclude list keeps the folders of recipes you are still working on.
2. Build the SDK for your image:
   ```
   PC$ bitbake my-image -c populate_sdk
   ```
   Result: `tmp/deploy/sdk/poky-glibc-x86_64-my-image-cortexa57-qemuarm64-toolchain-5.0.20.sh`.
3. Install it without root:
   ```
   $ tmp/deploy/sdk/poky-glibc-x86_64-my-image-cortexa57-qemuarm64-toolchain-5.0.20.sh -d ~/yocto-sdk/my-image
   ```
4. Use it in a **new** terminal, without the BitBake environment:
   ```
   $ source ~/yocto-sdk/my-image/environment-setup-cortexa57-poky-linux
   $ echo $CC
   $ cd meta-mylayer/sdk-example
   $ $CC $CFLAGS $LDFLAGS zversion.c -o zversion -lz
   ```
5. Copy it to the running QEMU target and run it:
   ```
   $ scp zversion root@192.168.7.2:/tmp/ && ssh root@192.168.7.2 /tmp/zversion
   ```

## Check
```
$ file zversion
```
**Expected:** `ARM aarch64`, and on the target it prints the zlib version of the image. The
same file built with the host `gcc` is an x86-64 binary and does not run on the target.

## Errors you may see
- `No space left on device`: BitBake's disk monitor (`BB_DISKMON_DIRS`) first stops new tasks,
  then halts the build. Use `rm_work` (step 1 above) and delete `tmp/`; it is rebuilt from sstate.
