# Roadmap

What is done and what comes next, in the order I work on it. Details of each finished item are in
the [learning log](learning-log.md) and the [guides](README.md). Back to the
[project overview](../README.md).

- [x] Build an SDK (`bitbake my-image -c populate_sdk`) and compile against it outside Yocto
- [x] Write a recipe for a CMake project and a systemd/SysVinit service
- [x] Kernel: configuration fragments
- [x] Kernel: an out-of-tree kernel module recipe, loaded at boot
- [x] Create my own distro config instead of using `poky`
- [x] Build for real hardware (STM32MP257F-DK with `meta-st-stm32mp`)
- [x] OTA updates with RAUC: A/B rootfs, U-Boot boot selection, signed bundles, rollback

Done so far:

1. **OTA stage 2 and verified kernel boot**
   - [x] Kernel and device tree inside each rootfs slot, so updates can change the kernel;
         the boot script loads them from the selected slot
   - [x] Kernel and device tree as one FIT image, hashes checked by U-Boot
   - [x] Signed FIT image: U-Boot verifies kernel and device tree before booting
2. **Persistent data**
   - [x] Mount `userfs` as a data partition that survives updates
   - [x] Central RAUC status file (`data-directory`) on that partition
3. **Secure boot chain**
   - [x] Sign TF-A and FIP with ST's tools; tamper test shows TF-A refusing a changed U-Boot
   - [x] Document the chain of trust: ROM → TF-A → FIP → signed FIT → signed RAUC bundles
   - [x] A/B boot logic inside U-Boot, environment locked to RAUC's variables
   - [x] Tamper test for a signed TF-A: the open chip's boot ROM checks only the checksum
   - [x] Document the development settings (OP-TEE provisioning driver, open debug access)
   - [x] Read-only rootfs (`read-only-rootfs`, `ro` from U-Boot)
   - [x] dm-verity by hand: hash tree on the PC, tamper test on the board
   - [x] dm-verity in the build: hash tree and root hash next to the rootfs image
   - [x] Initramfs that opens the rootfs slot with dm-verity; boot and tamper test by hand
   - [x] Initramfs inside the signed FIT (kernel, DTB and root hash under one signature)
   - [x] One boot partition per slot for the FIT, RAUC slot group, full flash; update and
         rollback test

Next, production level, in this order:

1. **Reproducible build**
   - [x] kas file with all layers pinned to exact git revisions; one command sets up and builds
   - [ ] Optional: bit-identical kernel (the built-in config copy is compressed differently)
2. **Development and release builds, hardening**
   - [x] Release build without `debug-tweaks` (kas without `kas/debug.yml`)
   - [x] SSH with keys only; Dropbear host key on `/data`, so it stays the same across updates
         (built; board test needs network)
   - [x] Serial login in the release build: the prompt stays, but root is locked (`root:*`) and
         there is no other user, so nobody can log in; boot messages stay visible for service
   - [ ] Release image without test and debug packages
3. **Robust updates**
   - [x] Mark a slot good only after a health check, not right after boot (board test open)
   - [x] systemd watchdog for the main service (`sysmon`, board test open)
   - [x] Anti-rollback: refuse bundles older than the installed version (`min-bundle-version`,
         board test open)
4. **CI/CD**
   - [x] GitHub Actions: lint (`oelint-adv`), parse check (`bitbake -p` with kas-container)
   - [ ] Later (20h): pin the GitHub actions to a commit instead of `@v4` (code that runs on
         my PC)
   - [ ] Later (20h): Yocto-compatible layer: limit the bbappends to `mydistro` (`:mydistro` overrides), so the
         layer changes nothing for other distros; then `yocto-check-layer` in CI
   - [x] Full build of image and RAUC bundle on a self-hosted runner (own pushes only,
         never pull requests from forks)
   - [ ] The SDK in CI, for releases
   - [x] Signing: CI test key for the bundle, my keys kept out of CI
   - [x] Release signing apart from CI: written down (trusted machine, signing server, `rauc resign`)
   - [ ] Optional: compare the root hash of a laptop release build with CI's (reproducible build)
   - [ ] Later (20h): shared hash-equivalence server (`BB_HASHSERVE_UPSTREAM`) and sstate, so new build
         folders reuse the cache
   - [ ] Automatic checks of the results: `rauc info` (signature, compatible, version), a test
         build of `sdk-example/` with the SDK
5. **SBOM, CVEs and licenses**
   - [x] SPDX SBOM (`create-spdx`), `cve-check` report, license manifest, as CI results
   - [x] GPLv3 in the image: what it is, how `INCOMPATIBLE_LICENSE` keeps it out
   - [x] CI fails on a new critical CVE (baseline) or a forbidden license (allow list)
   - [ ] GPL sources (`archiver`) for releases; find why some recipes miss in the CVE report
6. **Network** (WiFi; no Ethernet cable at hand)
   - [x] WiFi: chip firmware, `wpa_supplicant`, systemd-networkd config, credentials only on `/data`
   - [x] SSH over WiFi with the board key
   - [ ] One machine-id kept on `/data` (stable DHCP ID, one journal folder)
7. **OTA from a server** (over WiFi)
   - [x] hawkBit server in Docker, `rauc-hawkbit-updater` on the board, device token kept out of
         the image and the repository
   - [ ] Report success only after the health check passed (today: before the reboot)
   - [ ] HTTPS between board and server
   - [ ] RAUC streaming (install directly from HTTPS); optional adaptive updates
8. **Device tree and a driver** (I2C sensor, e.g. BME280, on the GPIO expansion header)
   - [x] Find the I2C controller and pins in the board schematics
   - [x] Device tree node for the sensor in a `linux-stm32mp` bbappend; test with the existing
         `bmp280` IIO driver and `i2cdetect`
   - [ ] Own I2C driver (`probe()`, `regmap`, sysfs/IIO interface) as an out-of-tree module
   - [ ] Debug the bus with a logic analyzer (wrong address, missing pull-ups)

Later:

- [ ] Encrypted `/data` (dm-crypt, key from OP-TEE) and protected storage for device secrets
- [ ] Logging and diagnostics: persistent, size-limited journal on `/data`, crash reports
- [ ] Clean-up: remove the old FIT and device trees from `/boot` of the rootfs
      (`kernel-image-fitimage`, `kernel-devicetree`). Since step 16f U-Boot loads the FIT from
      the slot's boot partition, so these ~12 MB per slot are unused
- [ ] Media player controlled with an IR remote
- [ ] Optional: Mender on QEMU, compared with RAUC
- [ ] Fully closed serial console for the release build, together with closing the chip:
  - no login prompt: no `serial-getty@ttySTM0` and `getty@tty1` (ST sets `SERIAL_CONSOLES` with a
    hard `=` in `st-machine-common-stm32mp.inc`, so it needs `:forcevariable` or a bbappend, only
    in the release configuration)
  - quiet kernel console: `quiet` / `loglevel=` or no `console=` in U-Boot's `bootargs`
  - U-Boot console locked: no key stops autoboot (`bootdelay=-2` or `CONFIG_AUTOBOOT_KEYED`), or
    `silent` console
  - TF-A and OP-TEE: release log level (`ST_TF_A_DEBUG_TRACE = "0"` is already forced when
    signing)
  - a way back for service: a development build signed with the same keys, installed by RAUC
- [ ] Last, after all other lessons: key hash into the OTP fuses (irreversible)
