# 18 Development and release builds, hardening

**Goal:** a release build without development settings: no empty passwords, SSH with a key only,
a host key that stays the same across boots and updates. Learning log:
[step 18](../README.md#step-18-development-and-release-builds-hardening-in-progress).

| Part | What | Status |
|---|---|---|
| 18a | What is "development" in the image today | done |
| 18b | Release build: kas without `kas/debug.yml` (no `debug-tweaks`) | done |
| 18c | SSH with a key only | built, board test open (needs network) |
| 18d | Dropbear host key on `/data` | built, board test open |
| 18e | Serial console in the release build | done (prompt stays, no account can log in) |

## 18a What is "development" today

Read from the image on the PC (`debugfs` only reads):
```
R=build/tmp/deploy/images/stm32mp25-disco/my-image-stm32mp25-disco.splitted-rootfs.ext4
debugfs -R "cat /etc/shadow" $R 2>/dev/null | grep "^root:" | cut -d: -f1,2
debugfs -R "cat /etc/default/dropbear" $R 2>/dev/null
```

| What | With `debug-tweaks` | Risk |
|---|---|---|
| root password | empty (`root:`) | anyone on the serial port or the network logs in |
| Dropbear | `DROPBEAR_EXTRA_ARGS=" -B"`: empty passwords accepted | SSH as root without a password |
| SSH keys | none | |
| Dropbear host key | `/var/lib/dropbear`, in RAM since step 15b: a new key every boot | a fake board cannot be told from the real one; "host key changed" every time |
| serial login | `serial-getty@ttySTM0`, `getty@tty1` | physical access gives a login prompt |
| test packages | `strace`, `hello`, `mymod`, `sysmon`, `microsocks`, `file`, `squashfs-tools` | more tools and more to keep up to date |

How poky does it (`rootfs-postcommands.bbclass`): without `debug-tweaks`, `zap_empty_root_password`
locks root (`root:*`), and Dropbear keeps poky's default `-w` (no root login at all). Removing
`debug-tweaks` alone would lock the board, so the key comes in the same step.

## 18b Release build

The release build is the kas build without `kas/debug.yml`:
```
~/kas-venv/bin/kas build ../meta-mylayer/kas/stm32mp25-disco.yml:../meta-mylayer/kas/signing.yml
```
**Expected:** `root:*` in `/etc/shadow`.

## 18c SSH with a key only

A separate key for the board, so it can be replaced without touching other keys:
```
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_stm32 -C "stm32mp25-disco root"
```
Only the public key goes into the layer
([`root-ssh-key`](../recipes-core/root-ssh-key/root-ssh-key.bb) installs it as
`${ROOT_HOME}/.ssh/authorized_keys`, mode 0600, directory 0700). It is added to `IMAGE_INSTALL`.

The [dropbear bbappend](../recipes-core/dropbear/dropbear_%25.bbappend) replaces poky's
`/etc/default/dropbear` (same file name, found first through `FILESEXTRAPATHS`):
```
DROPBEAR_EXTRA_ARGS="-s"
DROPBEAR_RSAKEY_DIR=/data/dropbear
```
- `-s`: no password logins at all. No `-w`, so root may log in, with the key.
- In a development build `debug-tweaks` still adds `-B`, but `-s` wins: SSH is key-only there too.
  Only the serial console keeps the empty root password.

Check, on the PC in `kas-work`:
```
debugfs -R "cat /etc/default/dropbear" $R 2>/dev/null
debugfs -R "cat /etc/passwd" $R 2>/dev/null | grep "^root:"
debugfs -R "ls -l /root/.ssh" $R 2>/dev/null
cmp <(debugfs -R "cat /root/.ssh/authorized_keys" $R 2>/dev/null) ~/.ssh/id_ed25519_stm32.pub && echo SAME
```
**Expected:** `-s` without `-B`; root's home is `/root` in this setup, not `/home/root`;
`authorized_keys` with mode `100600`, owner 0; `SAME`.

## 18d Dropbear host key on `/data`

`dropbearkey.service` creates the key, but its conditions only know `/etc/dropbear` and
`/var/lib/dropbear`. A drop-in
([`dropbearkey-data.conf`](../recipes-core/dropbear/files/dropbearkey-data.conf), installed as
`dropbearkey.service.d/data.conf`) resets them and waits for `/data`:
```
[Unit]
RequiresMountsFor=/data
ConditionPathExists=
ConditionPathExists=!/data/dropbear/dropbear_rsa_host_key
```
An empty `ConditionPathExists=` clears the conditions from the original unit. The service's own
`mkdir -p ${DROPBEAR_RSAKEY_DIR}` creates `/data/dropbear`.

Check:
```
debugfs -R "cat /lib/systemd/system/dropbearkey.service.d/data.conf" $R 2>/dev/null | grep Condition
```
**Expected:** the empty line and `!/data/dropbear/dropbear_rsa_host_key`.

**Board test, open** (needs a network connection): with the release image, `ssh -i
~/.ssh/id_ed25519_stm32 root@<board>` logs in without a password, `ssh -o PubkeyAuthentication=no
root@<board>` is refused, and the host key fingerprint stays the same after a reboot and after an
update. Do not install a release image before this works: it has no serial login, so SSH is the
only way in (the way back is a full USB flash, which erases `/data`).

## 18e Serial console in the release build

The release image keeps the login prompt on `ttySTM0`, but root is locked (`root:*`) and there is
no other user, so nobody can log in. Kernel and boot messages stay visible, which helps with
service. Closing the console completely (no prompt, quiet kernel, locked U-Boot console) belongs
to closing the device; it is in the [plan](../README.md#next-steps) under "Later".

## Errors you may see
- `debugfs -R "ls -l /home/root/.ssh"` shows nothing: root's home is `/root` here
  (`/etc/passwd`). Recipes should use `${ROOT_HOME}`, not a fixed path.
