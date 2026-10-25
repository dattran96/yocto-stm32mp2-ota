# 12h Signed FIT image

**Goal:** U-Boot boots only kernels signed with your key.
Learning log: [step 12h](learning-log.md#step-12-ota-updates-with-rauc-in-progress).

## Background
The hashes from 12g sit in the same file as the data, so anyone can build a new FIT with
matching hashes. Signing adds trust from outside the file:

1. You create an RSA key pair. The **private** key stays on the build PC.
2. When the kernel is built, `mkimage` signs each FIT **configuration** (which covers the
   kernel and device tree hashes) with the private key.
3. When U-Boot is built, the **public** key is written into U-Boot's own device tree and marked
   `required`. That device tree is part of the FIP (`fip-a` partition).
4. At boot, `bootm` checks the signature with the public key it already has. A kernel that was
   not signed with your private key is refused.

Same idea as the RAUC bundles: the device only holds the public part.

## Steps

1. **Keep an unsigned FIT** for the negative test, before the build overwrites it:
   ```
   PC$ mkdir -p ~/fit-test
   PC$ cp -L tmp/deploy/images/stm32mp25-disco/kernel/fitImage ~/fit-test/fitImage-unsigned
   ```
2. **Create the key pair outside the repository:**
   ```
   $ mkdir -p ~/yocto-keys/fit && cd ~/yocto-keys/fit
   $ openssl genrsa -F4 -out fitconf.key 2048
   $ openssl req -batch -new -x509 -key fitconf.key -out fitconf.crt -subj "/CN=mydistro FIT signing (dev)" -days 3650
   $ chmod 600 fitconf.key
   ```
   The build looks for `<KEYNAME>.key` and `<KEYNAME>.crt` in `UBOOT_SIGN_KEYDIR`.
3. **Configure the build** in `local.conf`:
   ```
   UBOOT_SIGN_ENABLE = "1"
   UBOOT_SIGN_KEYDIR = "${HOME}/yocto-keys/fit"
   UBOOT_SIGN_KEYNAME = "fitconf"
   UBOOT_MKIMAGE_DTCOPTS = "-I dts -O dtb -p 2000"
   # ST embeds the public key in its own U-Boot recipe; skip poky's generic step
   UBOOT_DTB_BINARY = ""
   ```
   - `UBOOT_SIGN_ENABLE` also makes ST's U-Boot recipe add `CONFIG_FIT_SIGNATURE=y` and
     `CONFIG_RSA=y`.
   - `-p 2000` leaves room in the FIT for the signature.
   - The values follow ST's commented example in `meta-st-stm32mp/conf/machine/stm32mp15-eval.conf`.

   Raise `DISTRO_VERSION` in `mydistro.conf`.
4. **Build:**
   ```
   PC$ bitbake my-image my-bundle
   ```
5. **Check both halves before flashing.** Follow [verify.md: FIT image](verify.md#fit-image-kernel--device-tree)
   and [verify.md: FIP](verify.md#fip-does-u-boot-carry-the-public-key).
   **Expected:**
   - The FIT configuration has `Sign algo: sha256,rsa2048:fitconf`.
   - `hw-config.bin` from the FIP is identical to `u-boot-...-default-with-signature.dtb`.
   - `/signature/key-fitconf` has `required = conf`.

   Why check the FIP and not only the deployed `.dtb`: U-Boot's public key only protects you if
   it is in the FIP that is actually flashed. A U-Boot without the key checks only hashes and
   boots unsigned kernels too, and nothing warns you.
6. **Flash** (the FIP changed, so a full flash; guide 11, step 7).

## Board test
1. **Normal boot.** **Expected:** `Verifying Hash Integrity ... sha256,rsa2048:fitconf+ OK`.
   Before signing it said only `sha256+ OK`.
2. **Unsigned kernel.** Copy `~/fit-test/fitImage-unsigned` to the USB stick, then:
   ```
   board# mkdir -p /mnt/usb && mount /dev/sda1 /mnt/usb
   board# cp /mnt/usb/fitImage-unsigned /boot/
   board# sync && umount /mnt/usb
   board# rauc status            (note which slot is booted)
   board# reboot
   ```
   Stop U-Boot. Use `rootfs1` for slot A, `rootfs2` for slot B:
   ```
   STM32MP> part number mmc 0 rootfs1 rootpart
   STM32MP> load mmc 0:${rootpart} ${kernel_addr_r} /boot/fitImage-unsigned
   STM32MP> bootm ${kernel_addr_r}
   ```
   **Expected:** `iminfo` still shows `sha256+` for every image (the file is intact, just not
   signed), but `bootm` refuses it:
   ```
   Verifying Hash Integrity ...  error!
   No 'signature' subnode found for '<NULL>' hash node in 'conf-stm32mp257f-dk.dtb' config node
   Failed to verify required signature 'key-fitconf'
   Bad Data Hash
   ERROR: can't get kernel image!
   ```
3. **For comparison**, the signed FIT from the same partition:
   ```
   STM32MP> load mmc 0:${rootpart} ${kernel_addr_r} /boot/fitImage
   STM32MP> bootm ${kernel_addr_r}
   ```
   **Expected:** `sha256,rsa2048:fitconf+ OK` and the kernel starts.

## What this does not protect
U-Boot itself is not verified yet: whoever can write the SD card can replace the FIP with a
U-Boot that has no key. That gap is closed by signing TF-A and the FIP, with the root key hash
in the chip's OTP fuses (next part of the plan).

## Errors you may see
- `do_uboot_assemble_fitimage` fails with `Unimplemented compression type 1`,
  `uboot-mkimage: Invalid FIT blob`: poky's generic `uboot-sign.bbclass` and ST's U-Boot recipe
  both try to embed the key, and poky's self-check fails on its dummy FIT. Set
  `UBOOT_DTB_BINARY = ""` (step 3) so only ST's code runs.
