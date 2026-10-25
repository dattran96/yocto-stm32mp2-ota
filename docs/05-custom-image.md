# 05 A custom image recipe

**Goal:** move the package list out of `local.conf` into an image recipe in your layer, so a
fresh clone builds the same image.
Learning log: [step 5](learning-log.md#step-5-a-custom-image-recipe).

## Steps

1. Write [`recipes-core/images/my-image.bb`](../recipes-core/images/my-image.bb). At this step it
   only needs:
   ```
   SUMMARY = "My first custom image"
   LICENSE = "MIT"

   inherit core-image

   IMAGE_INSTALL = " \
       packagegroup-core-boot \
       ${CORE_IMAGE_EXTRA_INSTALL} \
       strace \
       file \
       hello \
   "

   IMAGE_FEATURES += "ssh-server-dropbear"
   ```
   The version in the repository has more packages; later steps add them.
2. Remove the `IMAGE_INSTALL:append` lines from `local.conf`.
3. Build and boot. Give only the image name to `runqemu`; the machine comes from `local.conf`:
   ```
   PC$ bitbake my-image
   PC$ runqemu my-image nographic
   ```
4. From a second terminal on the PC, log in over SSH:
   ```
   $ ssh root@192.168.7.2
   ```

## Check
```
PC$ grep -E "^(hello|dropbear) " tmp/deploy/images/qemuarm64/my-image-qemuarm64.rootfs.manifest
```
**Expected:** both packages are listed, and the SSH login works.

## Errors you may see
- `runqemu qemuarm64 my-image` fails with
  `IMAGE_LINK_NAME wasn't set to find corresponding .qemuboot.conf file`. When you name the
  machine, `runqemu` runs `bitbake -e` without the image and cannot find the image's files.
  Use `runqemu my-image nographic`.
