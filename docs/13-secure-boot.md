# 13 Secure boot chain

**Goal:** every boot stage checks the next one. Sign TF-A and the FIP with your own keys, see
what an open (unfused) chip enforces, and close the gaps in U-Boot: an unverified boot script and
an unverified environment.
Learning log: [step 13](learning-log.md#step-13-secure-boot-chain-in-progress).

> **No OTP fuses in this guide.** Writing the key hash into the fuses is permanent: with a lost
> key or a mistake the board never boots again. Nothing here runs `STM32_Programmer_CLI` with
> `-otp` or `-ssp`. Every step can be undone by flashing the normal images.

## Background

### Who checks what

| Checker | Checks | Its trusted reference comes from | Key |
|---|---|---|---|
| Boot ROM | TF-A BL2 | OTP fuses (hash of the key table) | ST ECDSA key set |
| TF-A BL2 | FIP: BL31, OP-TEE, U-Boot | the same hash in the fuses | same root key, as certificates |
| U-Boot | FIT (kernel + device tree) | its own device tree, inside the FIP | `fitconf` RSA key (12h) |
| RAUC | update bundles | `ca.cert.pem` in the rootfs | RAUC CA (12c) |

A checker can only trust a key that it already has from somewhere the attacker cannot change.
At the start of the chain that is the chip itself: the ROM code and the fuses.

### Where everything is stored

```
┌──────────────────────────── INSIDE THE CHIP (can't be replaced) ────────────────────────────┐
│  BOOT ROM (ST's code)                         OTP FUSES                                     │
│  - knows how to check signatures              PKH = hash of the 8-key table                 │
│                                               (405dcafa… in my build)   today: 0000…0       │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
        │ ① ROM: hash(key table in header) == PKH?   ▲ ② TF-A reads the same PKH (via BSEC)
        │    then checks TF-A's signature            │
        ▼                                            │
┌──────────────────── SD card: fsbla1 / fsbla2 ─────────────────────┐
│  TF-A BL2 (_Signed.stm32)                                         │
│  HEADER: public key 0, table Key0…Key7 (8 hashes), signature      │
│  TF-A code (runs from internal SRAM: DDR is not set up yet)       │
└───────────────────────────────────────────────────────────────────┘
        │ ③ TF-A: hash(root key in FIP certificate) == Key0 from its trusted header?
        ▼    then follows the certificate chain
┌──────────────────── SD card: fip-a ───────────────────────────────────────────────────┐
│  Trusted key certificate (root key) → key certificates → content certificates         │
│  content certificate for U-Boot: hash of U-Boot ──④ TF-A compares──┐                  │
│  BL31        OP-TEE        U-Boot ◀────────────────────────────────┘                  │
│                            + U-Boot DTB: fitconf public key (required)                │
│                            + built-in A/B boot logic (rauc_boot, step 13c)            │
└───────────────────────────────────────────────────────────────────────────────────────┘
        │ ⑤ U-Boot checks the FIT signature with the fitconf key from its own DTB
        ▼
┌── u-boot-env ─────────────────┐   ┌── rootfs1 / rootfs2 ─────────────────────────────┐
│ only BOOT_ORDER, BOOT_A_LEFT, │   │ /boot/fitImage: kernel + DTB + signature         │
│ BOOT_B_LEFT are accepted      │   │ /etc/rauc/ca.cert.pem → checks bundles ⑥         │
└───────────────────────────────┘   └──────────────────────────────────────────────────┘

On the PC only, never on the board:
  ~/yocto-keys/stm32mp/privateKey00…07.pem   sign TF-A (①) and the FIP certificates (③④)
  ~/yocto-keys/fit/fitconf.key               signs the FIT (⑤)
  ~/yocto-keys/rauc/…                        signs the RAUC bundles (⑥)
```

### The certificate chain in the FIP

TF-A uses its standard chain of trust (TBBR). Each public key travels inside a certificate that is
signed by the key above it:

```
                         ROOT KEY (privateKey00)
                        /                       \
          Trusted World key                 Non-Trusted World key
           /             \                           |
  BL31 content key   OP-TEE content key       U-Boot content key
        |                  |                         |
   hash of BL31      hash of OP-TEE            hash of U-Boot
```

| Certificate (`fiptool info`) | Signed by | Contains |
|---|---|---|
| Trusted key certificate | root key | Trusted World and Non-Trusted World public keys |
| SoC Firmware key certificate | Trusted World key | BL31 content key |
| Trusted OS Firmware key certificate | Trusted World key | OP-TEE content key |
| Non-Trusted Firmware key certificate | Non-Trusted World key | U-Boot content key |
| SoC / Trusted OS / Non-Trusted Firmware content certificate | the matching content key | hash of BL31 / OP-TEE / U-Boot |
| Trusted Boot Firmware BL2 certificate | root key | hashes of TF-A's configuration |
| STM32MP CONFIG CERT | (ST specific) | |

The levels let different parties own different keys (the root key stays offline). In ST's build
only the root key is yours: `cert_create -n` creates the five intermediate keys freshly on every
build.

## 13a Sign TF-A and the FIP

1. Check that ST's tools are there (they come with STM32CubeProgrammer):
   ```
   $ which STM32_SigningTool_CLI STM32_KeyGen_CLI
   ```
2. Look at the current, unsigned TF-A header:
   ```
   PC$ STM32_SigningTool_CLI -dump tmp/deploy/images/stm32mp25-disco/arm-trusted-firmware/tf-a-stm32mp257f-dk-optee-sdcard.stm32
   ```
   **Expected:** `Signature: 00 00 …`, `Header version: 0x20200` (v2.2).
3. Create the key set outside the repository. `read -s` asks for a password without showing it;
   write the password down:
   ```
   $ mkdir -p ~/yocto-keys/stm32mp && cd ~/yocto-keys/stm32mp
   $ read -s -p "Key password: " PW; echo
   $ STM32_KeyGen_CLI -abs ~/yocto-keys/stm32mp -n 8 -ecc 1 -pwd "$PW" "$PW" "$PW" "$PW" "$PW" "$PW" "$PW" "$PW"
   $ unset PW
   $ chmod 444 publicKeyHash0*.bin
   ```
   **Expected:** `privateKey00…07.pem`, `publicKey00…07.pem`, `publicKeyHash00…07.bin` and
   `publicKeysHashHashes.bin` (the value the fuses would hold). Back up the folder.
4. In `local.conf`:
   ```
   SIGN_ENABLE = "1"
   SIGN_TOOL = "${HOME}/STMicroelectronics/STM32Cube/STM32CubeProgrammer/bin/STM32_SigningTool_CLI"
   SIGN_KEY = "${HOME}/yocto-keys/stm32mp/privateKey00.pem"
   SIGN_KEY_PUB = "${HOME}/yocto-keys/stm32mp/publicKey00.pem"
   SIGN_KEY_PASS = "<your password>"
   # No Cortex-M33 firmware in this image: skip its signing, but ST's checks still want keys
   SIGN_COPRO_ENABLE = "0"
   SIGN_M33DDR_KEY = "${SIGN_KEY}"
   SIGN_M33DDR_KEY_PASS = "${SIGN_KEY_PASS}"
   SIGN_M33FW_KEY = "${SIGN_KEY}"
   SIGN_M33FW_KEY_PASS = "${SIGN_KEY_PASS}"
   ```
   - `SIGN_TOOL` must be an absolute path: BitBake runs tasks with its own `PATH`.
   - The M33 key variables are required because the machine has `m33copro` in `MACHINE_FEATURES`.
5. Build:
   ```
   PC$ bitbake my-image
   ```
   The warning `TF-A SIGNATURE: force ST_TF_A_DEBUG_TRACE to '0'` is expected: ST reduces TF-A's
   debug output when signing.
6. Check the result before flashing, see [verify.md: signed TF-A and FIP](verify.md#signed-tf-a-and-fip).
   **Expected:**
   - The signature is no longer zero.
   - `Key 0 … Key 7` in the header equal your `publicKeyHash0x.bin`.
   - `fiptool info` lists the certificates from the table above.
   - The flash layout is now `FlashLayout_sdcard_stm32mp257f-dk-optee_Signed.tsv`.
7. Flash the `_Signed.tsv` layout (guide 11, step 7).

## 13b What the open chip enforces

**Check:** the first lines on the console after power-on:
```
NOTICE:  TRUSTED_BOARD_BOOT support enabled
NOTICE:  ROTPK is not deployed on platform. Skipping ROTPK verification.
NOTICE:  ROTPK is not deployed on platform. Skipping ROTPK verification.
NOTICE:  BL2: Booting BL31
```
With empty fuses TF-A skips only the comparison of the root key with the fuses. It still checks
every certificate signature and every image hash. Prove it with a tampered U-Boot:

1. PC: change one byte in U-Boot inside the signed FIP, without re-signing:
   ```
   PC$ D=tmp/deploy/images/stm32mp25-disco
   PC$ mkdir -p ~/fip-tamper
   PC$ oe-run-native tf-a-tools-native fiptool unpack --force --out ~/fip-tamper $D/fip/fip-stm32mp257f-dk-optee-sdcard_Signed.bin
   PC$ cp ~/fip-tamper/nt-fw.bin ~/fip-tamper/nt-fw-tampered.bin
   PC$ printf 'X' | dd of=$HOME/fip-tamper/nt-fw-tampered.bin bs=1 seek=$((0x100000)) conv=notrunc
   PC$ cmp -l ~/fip-tamper/nt-fw.bin ~/fip-tamper/nt-fw-tampered.bin
   PC$ cp $D/fip/fip-stm32mp257f-dk-optee-sdcard_Signed.bin ~/fip-tamper/fip-tampered.bin
   PC$ oe-run-native tf-a-tools-native fiptool update --nt-fw ~/fip-tamper/nt-fw-tampered.bin ~/fip-tamper/fip-tampered.bin
   ```
   **Expected:** `cmp -l` prints exactly one line. Copy `fip-tampered.bin` to a USB stick.
2. Board: make sure partition 5 is `fip-a` (BusyBox `blkid` shows nothing for it, because it has
   no filesystem):
   ```
   board# P=$(readlink -f /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part5)
   board# cat /sys/class/block/$(basename $P)/uevent
   ```
   **Expected:** `PARTNAME=fip-a`. Only then write it. BusyBox `dd` has no `conv=`, so use `sync`:
   ```
   board# mount /dev/sda1 /mnt/usb
   board# dd if=/mnt/usb/fip-tampered.bin of=/dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part5 bs=4096
   board# sync
   board# cmp /mnt/usb/fip-tampered.bin /dev/disk/by-path/platform-42080000.bus-amba-48220000.mmc-part5
   board# umount /mnt/usb && reboot
   ```
   `cmp: EOF on /mnt/usb/fip-tampered.bin` means the partition holds the file.
3. **Expected** on the console, and no U-Boot banner:
   ```
   ERROR:   BL2: Failed to load image id 5 (-80)
   ```
   Image 5 is `BL33_IMAGE_ID` (U-Boot), -80 is `EAUTH` (authentication error).
4. Recover: flash the `_Signed.tsv` layout again.

What this shows: without fuses, any change to a signed image in the FIP is caught, but a FIP that
was signed completely new with somebody else's root key would be accepted.

### Does the ROM check TF-A's signature?

Change one visible byte in the signed TF-A, fix the checksum so that only the signature is wrong,
and write it only to `fsbla1`. `fsbla2` keeps the original: the ROM tries the second copy if the
first one fails, so the board stays bootable either way.

1. PC: the checksum at offset `0x44` is the sum of all bytes after the 0x200-byte header:
   ```
   $ mkdir -p ~/tfa-tamper && cd ~/tfa-tamper
   $ cp $D/arm-trusted-firmware/tf-a-stm32mp257f-dk-optee-sdcard_Signed.stm32 tfa-signed.stm32
   $ cp tfa-signed.stm32 tfa-tampered.stm32
   $ python3 -I - tfa-tampered.stm32 <<'EOF'
   import struct, sys
   p = sys.argv[1]
   d = bytearray(open(p, "rb").read())
   i = d.find(b"lts-v2.10.24-dirty") + len("lts-v2.10.24-")
   assert d[i:i+1] == b"d"
   d[i] = ord("D")
   struct.pack_into("<I", d, 0x44, sum(d[0x200:]) & 0xffffffff)
   open(p, "wb").write(d)
   EOF
   $ cmp -l tfa-signed.stm32 tfa-tampered.stm32
   ```
   **Expected:** two lines: the checksum byte at position 69 (`0x44` + 1) and the changed letter.
   The version string is the one TF-A prints at boot; use the one in your build.
2. Board: check `PARTNAME=fsbla1` for partition 1 (as for `fip-a` above), then write it with
   `dd ... bs=4096`, `sync`, `cmp`, `reboot`.
3. **Result on an open chip:**
   ```
   NOTICE:  BL2: v2.10-stm32mp2-r2.0(release):lts-v2.10.24-Dirty(a07367a1)
   NOTICE:  BL31: v2.10-stm32mp2-r2.0(release):lts-v2.10.24-dirty(a07367a1)
   ```
   The ROM ran the changed TF-A: without the key hash in the fuses it does not check the signature,
   only the checksum. BL31 comes from the unchanged FIP and still prints `-dirty`.
4. Restore `fsbla1` with `tfa-signed.stm32` the same way.

| Stage | Checked on an open chip | Added by the key hash in the fuses |
|---|---|---|
| ROM → TF-A | checksum only | key table and signature |
| TF-A → FIP | all signatures and hashes of the chain | the root key is compared with the fuses |
| U-Boot → FIT | signature with the `fitconf` key from its own DTB | (unchanged) |

## 13c Boot logic inside U-Boot, locked environment

U-Boot is verified now, but it used two inputs that nobody checks: `boot.scr` in `bootfs`, and
the environment in `u-boot-env`.

1. **See the attack.** From Linux, as root:
   ```
   board# fw_setenv bootcmd 'echo "*** ATTACK: this command came from the env partition ***"; run bootcmd_stm32mp'
   board# reboot
   ```
   **Expected:** U-Boot prints the `*** ATTACK` line and then boots normally. Clean up with
   `fw_setenv bootcmd`.
2. **Move the A/B logic into U-Boot.** [`stm32mp2.env`](../recipes-bsp/u-boot/u-boot-stm32mp/stm32mp2.env)
   is the old `boot.cmd` in U-Boot's text environment format (`doc/usage/environment.rst`):
   `rauc_boot` → `rauc_select` → `rauc_load`. U-Boot uses `board/st/stm32mp2/stm32mp2.env`
   automatically, so [`u-boot-stm32mp_%.bbappend`](../recipes-bsp/u-boot/u-boot-stm32mp_%.bbappend)
   copies it there before `do_configure`. Rules of the format:
   - the lines of a variable are joined with spaces, so end every command with `;`
   - the file goes through the C preprocessor, so use only `/* */` comments
   - every boot that is not from SD/eMMC goes to ST's `bootcmd_stm32mp`; that keeps USB flashing
     with STM32CubeProgrammer working
3. **Lock the environment.** [`rauc-env.cfg`](../recipes-bsp/u-boot/u-boot-stm32mp/rauc-env.cfg):
   ```
   CONFIG_BOOTCOMMAND="run rauc_boot"
   CONFIG_ENV_WRITEABLE_LIST=y
   CONFIG_ENV_ACCESS_IGNORE_FORCE=y
   ```
   The list of writeable variables is a C define in the board header, so it needs
   [a small patch](../recipes-bsp/u-boot/u-boot-stm32mp/0001-stm32mp25-import-only-the-RAUC-variables-from-the-environment.patch):
   ```
   #define CFG_ENV_FLAGS_LIST_STATIC "BOOT_ORDER:sw,BOOT_A_LEFT:dw,BOOT_B_LEFT:dw"
   ```
   (`s`/`d` = string/decimal, `w` = writeable: may be imported from the partition.)
4. Remove the boot script. In `local.conf`:
   ```
   ST_EXTLINUX_PACKAGE = ""
   ```
5. Build, check, flash `_Signed.tsv`:
   ```
   PC$ bitbake my-image
   PC$ strings $D/u-boot/u-boot-nodtb-stm32mp25-default.bin | grep "^rauc_boot=" | cut -c1-120
   PC$ grep -E "BOOTCOMMAND|ENV_WRITEABLE_LIST|ENV_ACCESS_IGNORE_FORCE" $D/u-boot/configuration-stm32mp25_defconfig
   PC$ debugfs -R "ls -l /" $D/my-image-stm32mp25-disco.splitted-bootfs.ext4
   ```
   **Expected:** `rauc_boot=if test "${boot_device}" != mmc; then …`, the three options, and no
   `boot.scr` in bootfs.

## Check
1. Normal boot. **Expected:** `Booting slot A (rootfs1), attempts left: A=2 B=3` with no
   `Scanning mmc 0:8` / `Found U-Boot script` before it, then the FIT check and Linux.
2. The attack from step 1 again. **Expected:** `fw_printenv bootcmd` shows the value, but U-Boot
   no longer prints the `*** ATTACK` line.
3. `rauc status mark-active other`, reboot. **Expected:** `Booting slot B (rootfs2) …`: the RAUC
   variables are still accepted.

## Known development settings

OP-TEE prints three warnings at boot. They are not part of the chain of trust (which stage checks
which), but a product changes them. What they mean, from OP-TEE's source
(`core/arch/arm/plat-stm32mp2/`, `core/drivers/`):

| Warning | Cause | What a product does |
|---|---|---|
| `This OP-TEE configuration might be insecure!` | `CFG_INSECURE=y`, OP-TEE's own default, also forced by ST's provisioning driver | builds with `CFG_INSECURE=n`: then wrong firewall (RIFSC/ETZPC) or GPIO security settings make OP-TEE panic instead of warn |
| `Embeds insecure stm32mp_provisioning driver`, `Override the OTP 124: 0 to 0x18db6` | `CFG_STM32MP_PROVISIONING=y` writes a value from the device tree into the **shadow register** of OTP word 124 (HCONF1, watchdog settings) at every boot; no fuse is burned | burns HCONF1 into the fuses once in production and builds with `CFG_STM32MP_PROVISIONING=n` |
| `All debug access are allowed` | on every chip that is not closed, OP-TEE opens JTAG/SWD debug access; `CFG_INSECURE` only decides whether it prints this | closes the chip: only that locks debug access. With debug access, someone with physical access can read memory and bypass every check. |

One more, without a warning: OP-TEE signs Trusted Applications with `keys/default_ta.pem`, a key that
is public in OP-TEE's repository. A product that uses TAs sets its own `TA_SIGN_KEY`.

Only the debug access matters for this board, and it can be locked only together with the fuses,
which come last.

## Errors you may see
- The environment is saved twice on every boot: ST's `env_check` (in `bootcmd_stm32mp`) saves it
  whenever U-Boot reports the default environment, and with the writeable list it did so on every
  boot. `rauc_boot` does not call it; `rauc_select` saves once.
- `Nothing RPROVIDES 'ab-bootscript'`: `local.conf` still has the old `ST_EXTLINUX_PACKAGE`.
