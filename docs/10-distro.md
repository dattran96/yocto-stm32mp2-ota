# 10 Own distro configuration

**Goal:** move distro policy (init manager, distro features, name and version) out of
`local.conf` into a distro file in the layer.
Learning log: [step 10](../README.md#step-10-my-own-distro-configuration).

Three kinds of configuration, three places:

| What | Example | Where |
|---|---|---|
| MACHINE: the hardware | `qemuarm64`, `stm32mp25-disco` | BSP layer (`conf/machine/`) |
| DISTRO: policies | systemd, `DISTRO_FEATURES` | `conf/distro/mydistro.conf` |
| IMAGE: package selection | `my-image.bb` | `recipes-core/images/` |

`local.conf` is only for settings of your build PC (paths, `rm_work`, keys).

## Steps

1. Write [`conf/distro/mydistro.conf`](../conf/distro/mydistro.conf). Base it on poky and change
   only what differs:
   ```
   require conf/distro/poky.conf

   DISTRO = "mydistro"
   DISTRO_NAME = "My Learning Distro"
   DISTRO_VERSION = "1.0"
   DISTRO_CODENAME = "learning"
   MAINTAINER = "Your Name <you@example.com>"

   INIT_MANAGER = "systemd"
   ```
2. In `local.conf`: set `DISTRO ?= "mydistro"` and **remove** `INIT_MANAGER`.
3. Rebuild and boot.

## Check
```
PC$ bitbake-getvar -r my-image INIT_MANAGER
board# cat /etc/os-release
board# systemctl status sysmon
```
**Expected:**
- The `bitbake-getvar` history shows `poky.conf` setting a default and `mydistro.conf` setting
  `systemd`.
- `/etc/os-release` shows `My Learning Distro 1.0 (learning)`.
- The poky warning at login (`Poky is a reference Yocto Project distribution ...`) is gone.
