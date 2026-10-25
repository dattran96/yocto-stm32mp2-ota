# 19 Robust updates: health check, watchdog, anti-rollback

**Goal:** a slot is marked good only if the product works, a hung application is noticed, and an
older bundle cannot be installed. Learning log:
[step 19](learning-log.md#step-19-robust-updates-health-check-watchdog-anti-rollback).

| Part | Question | Status |
|---|---|---|
| 16f (done) | does it **boot**? | tested |
| 19a | does it **work** after boot? (health check before mark-good) | built and checked in the image; board test open |
| 19b | does it **keep** working? (watchdog for `sysmon`) | built and checked in the image; board test open |
| 19c | is the bundle **newer**? (anti-rollback) | built and checked in the image; board test open |

Before this step, `rauc-mark-good.service` (meta-rauc) marked a slot good as soon as
`boot-complete.target` was reached, and nothing gated that target. A system that booted but whose
application crashed stayed on the broken version. `sysmon` (step 8) plays the role of the
product's main application.

## 19a Health check before mark-good

```
sysmon, /data ──► health-check.service ──OK──► boot-complete.target ──► rauc-mark-good → slot good
                         │
                         └─FAIL──► FailureAction=reboot ──► U-Boot: attempts 3 → 0 ──► other slot
```
`boot-complete.target` is reached only if every unit that is `RequiredBy=` it succeeds (systemd's
boot assessment). [`health-check`](../recipes-core/health-check/health-check.bb) installs:
- [`health-check.service`](../recipes-core/health-check/files/health-check.service):
  `Before=boot-complete.target`, `After=sysmon.service`, `RequiresMountsFor=/data`,
  `FailureAction=reboot`, `[Install] RequiredBy=boot-complete.target`.
- [`/usr/libexec/health-check`](../recipes-core/health-check/files/health-check): `sysmon` must be
  active and `/data` writable.
- A test switch for one slot: `/data/health-check-fail-A` (or `-B`) makes only that slot fail. A
  single file for both slots would make both fail; U-Boot would then reset the counters and the
  board would reboot forever.

It must not be ordered after `multi-user.target`: that target waits for `rauc-mark-good`, which
waits for `boot-complete.target`, which would wait for the health check (a cycle).

Check in the image (PC, `kas-work`):
```
R=build/tmp/deploy/images/stm32mp25-disco/my-image-stm32mp25-disco.splitted-rootfs.ext4
debugfs -R "ls -l /etc/systemd/system/boot-complete.target.requires" $R 2>/dev/null | awk '{print $NF}'
```
**Expected:** `health-check.service` (`debugfs ls` without `-l` shows nothing useful here).

**Board test (open):**
1. Install a dev bundle (kas with `kas/debug.yml`) into the other slot, reboot. `systemctl status
   health-check` shows `active (exited)` and `health-check: slot A OK`; `rauc status` shows the
   slot `good`.
2. `touch /data/health-check-fail-A`, `reboot`. **Expected:** `health-check: FAILED: test file
   ...` and a reboot, three times (`A=2`, `A=1`, `A=0`), then U-Boot boots B.
3. On B: `rauc status` shows A `bad`. `rm /data/health-check-fail-A`.

## 19b Watchdog for `sysmon`

The hardware watchdog is already used by systemd (`RuntimeWatchdogSec=32` from ST's
`systemd-conf`), but it only catches a hung kernel or systemd. For the application:
```
sysmon, every loop: sd_notify("WATCHDOG=1") ──► systemd
no message for 30 s ──► systemd kills sysmon (SIGABRT), Restart=on-failure starts it again
3 starts within 5 minutes ──► StartLimitAction=reboot ──► U-Boot counts the attempt
```
- [`main.c`](../recipes-sysmon/sysmon/files/sysmon/main.c): `sd_notify(0, "READY=1")` after start,
  `sd_notify(0, "WATCHDOG=1")` in the loop. Without systemd, `sd_notify` is an empty macro.
- [`CMakeLists.txt`](../recipes-sysmon/sysmon/files/sysmon/CMakeLists.txt): option `WITH_SYSTEMD`
  links `libsystemd` (pkg-config).
- [`sysmon_1.0.bb`](../recipes-sysmon/sysmon/sysmon_1.0.bb): the usual Yocto way to make an option
  depend on the distro:
  ```
  PACKAGECONFIG ??= "${@bb.utils.filter('DISTRO_FEATURES', 'systemd', d)}"
  PACKAGECONFIG[systemd] = "-DWITH_SYSTEMD=ON,-DWITH_SYSTEMD=OFF,systemd"
  ```
  The fields: option when on, option when off, build dependency.
- [`sysmon.service`](../recipes-sysmon/sysmon/files/sysmon.service): `Type=notify`,
  `WatchdogSec=30`, and in `[Unit]` `StartLimitIntervalSec=300`, `StartLimitBurst=3`,
  `StartLimitAction=reboot`.

Check in the image:
```
debugfs -R "cat /lib/systemd/system/sysmon.service" $R 2>/dev/null | grep -E "Type|Watchdog|StartLimitAction"
debugfs -R "cat /usr/sbin/sysmon" $R 2>/dev/null | strings | grep -E "libsystemd|WATCHDOG"
```
**Expected:** `Type=notify`, `WatchdogSec=30`, `StartLimitAction=reboot`; `libsystemd.so.0` and
`WATCHDOG=1`.

**Board test (open):** `kill -STOP $(pidof sysmon)` freezes it without ending it, like a hang.
**Expected:** after about 30 s, `journalctl -u sysmon` shows `Watchdog timeout` and a new start.

## 19c Anti-rollback

RAUC has this built in (checked in the 1.15.2 source): `min-bundle-version` in `[system]` of
`system.conf`. A bundle with an older `version` is refused with `Version mismatch: Expected at
least '...' but bundle manifest has '...'`. RAUC compares the versions like semantic versioning,
so `1.10` is newer than `1.9`.

[`system.conf`](../recipes-core/rauc/files/system.conf) has `min-bundle-version=@DISTRO_VERSION@`,
and [`rauc-conf.bbappend`](../recipes-core/rauc/rauc-conf.bbappend) fills it in with `sed` in
`do_install:append`. Each system accepts only bundles of its own version or newer.

Check in the image:
```
debugfs -R "cat /etc/rauc/system.conf" $R 2>/dev/null | grep min-bundle
```
**Expected:** `min-bundle-version=1.11`.

- The A/B rollback by the boot counter still works: it boots the other slot, it installs nothing.
- An old bundle cannot be installed again to undo a bad release; a fixed newer version has to be
  built. For service, `rauc install --ignore-version-limit` skips the check (root only).
- Stronger: keep the minimum version in hardware (OTP counter, eMMC RPMB), so it survives a
  changed SD card. That belongs to closing the device.

**Board test (open):** on a 1.11 system, `rauc install` of a 1.10 bundle. **Expected:** the
`Version mismatch` message, nothing installed.
