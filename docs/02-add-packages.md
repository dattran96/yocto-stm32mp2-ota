# 02 Adding packages to an image

**Goal:** add packages to an image and see how BitBake reuses earlier work.
Learning log: [step 2](learning-log.md#step-2-adding-packages-to-an-image).

## Steps

1. In `build/conf/local.conf`, add:
   ```
   IMAGE_INSTALL:append = " strace file"
   ```
   The space at the start is required: `:append` adds the text as it is. Without it, the last
   package and `strace` would run together into one wrong name.
2. Build again:
   ```
   PC$ bitbake core-image-minimal
   ```
   This is much faster than the first build: BitBake reuses everything that did not change
   from the shared-state (sstate) cache.

## Check

```
PC$ grep -E "^(strace|file) " tmp/deploy/images/qemuarm64/core-image-minimal-qemuarm64.rootfs.manifest
PC$ runqemu core-image-minimal nographic
board# strace -V
board# file /bin/busybox
```
**Expected:** both packages are in the manifest, and both commands work on the target.

This is a quick experiment. Step 05 moves the package list into an image recipe, where it
belongs.
