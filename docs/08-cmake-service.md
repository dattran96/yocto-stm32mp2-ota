# 08 A CMake project as a boot service, then systemd

**Goal:** package a CMake project as a daemon that starts at boot, for both SysVinit and
systemd, then switch the image to systemd.
Learning log: [step 8](../README.md#step-8-a-cmake-project-as-a-boot-service) and
[8b](../README.md#step-8b-switching-the-image-from-sysvinit-to-systemd).

## Steps (8: the service)

1. Write the program and its build file in `recipes-sysmon/sysmon/files/sysmon/`:
   [`main.c`](../recipes-sysmon/sysmon/files/sysmon/main.c) (logs load and memory to syslog,
   exits on `SIGTERM`) and [`CMakeLists.txt`](../recipes-sysmon/sysmon/files/sysmon/CMakeLists.txt)
   (uses `GNUInstallDirs`, no hard-coded paths).
2. Write a start script for each init system:
   [`sysmon.init`](../recipes-sysmon/sysmon/files/sysmon.init) and
   [`sysmon.service`](../recipes-sysmon/sysmon/files/sysmon.service).
3. Write [`sysmon_1.0.bb`](../recipes-sysmon/sysmon/sysmon_1.0.bb):
   - `inherit cmake`: no `do_configure` / `do_compile` needed.
   - `SRC_URI = "file://sysmon ..."` fetches a whole folder; `S = "${WORKDIR}/sysmon"`.
   - `inherit update-rc.d systemd` with `INITSCRIPT_NAME`, `INITSCRIPT_PARAMS = "defaults 90"`
     and `SYSTEMD_SERVICE:${PN}`.
   - `do_install:append` installs only the start file for the active init system,
     with `bb.utils.contains('DISTRO_FEATURES', 'systemd', ...)`.
4. Add `sysmon` to `my-image.bb`, build, boot.

**Check (SysVinit):**
```
board# ls /etc/rc5.d/ | grep sysmon
board# tail /var/log/messages
board# /etc/init.d/sysmon stop; /etc/init.d/sysmon start
```
**Expected:** `S90sysmon`, a log line every 10 s, and a new PID after the restart.

## Steps (8b: switch to systemd)

1. In `local.conf`: `INIT_MANAGER = "systemd"`. Step 10 moves this into the distro.
2. Rebuild. Almost everything is rebuilt, because `DISTRO_FEATURES` changes nearly every task's
   signature.

**Check (systemd):**
```
PC$ bitbake-getvar -r my-image --value DISTRO_FEATURES | tr ' ' '\n' | grep -E "systemd|usrmerge"
board# systemctl status sysmon
board# journalctl -u sysmon -f
```
**Expected:** `systemd` and `usrmerge` are listed, and the service is `active (running)`. The
`sysmon` recipe was not changed: it now installs the `.service` file instead of the init script.

## Errors you may see
- `ERROR: Nothing PROVIDES 'sysmon'`: the recipe is in `recipes-sysmon/` instead of
  `recipes-sysmon/sysmon/`. `BBFILES` in `conf/layer.conf` only matches `recipes-*/*/*.bb`.
