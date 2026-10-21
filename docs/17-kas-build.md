# 17 Reproducible build with kas

**Goal:** one command sets up and builds the board image from this repository alone: the same
layers at the same commits, the same configuration, no hand-written `local.conf`. Learning log:
[step 17](../README.md#step-17-reproducible-build-with-kas).

Board build. Until now the build depended on things only on my PC:
```
~/Dokumente/yocto/
├─ poky, meta-openembedded, meta-st-stm32mp, meta-rauc   cloned by hand, at some commit
└─ poky/build-stm32mp/conf/local.conf                    written by hand, in no repository
```
With kas:
```
meta-mylayer/kas/stm32mp25-disco.yml   (in git)   repos + exact commits, machine, distro,
                                                  targets, local.conf settings
meta-mylayer/kas/debug.yml             (in git)   development settings (debug-tweaks)
meta-mylayer/kas/signing.yml           (NOT in git) key paths of this PC
password                               (no file)  typed into the shell before the build
```

## 17a Install kas

In its own Python environment, so the system Python does not change:
```
python3 -m venv ~/kas-venv
~/kas-venv/bin/pip install kas
~/kas-venv/bin/kas --version
```
**Expected:** `kas 5.5 (configuration format version 23, ...)` (or newer).

## 17b The kas files

[`kas/stm32mp25-disco.yml`](../kas/stm32mp25-disco.yml) lists the layers with `url`, `branch` and
the full `commit` (kas 5 accepts only the 40-character ID there; with `branch` it also checks that
the branch contains the commit). `meta-mylayer:` without a URL means "the repository this file is
in". `local_conf_header` holds the settings that were in `local.conf`:
```
local_conf_header:
  board: |
    CONF_VERSION = "2"
    INHERIT += "rm_work"
    MACHINE_FEATURES:append = " nogpu fit"
    ...
```
The commits are the ones of the hand-made setup:
```
for r in poky meta-openembedded meta-st-stm32mp meta-rauc; do echo "$r $(git -C $r rev-parse HEAD)"; done
```

The signing settings are split off:

| In [`kas/signing.yml.example`](../kas/signing.yml.example) (in git) | Only on the PC |
|---|---|
| which variables are needed, with `/path/to/...` | `kas/signing.yml` with the real paths (in `.gitignore`) |
| `env: SIGN_KEY_PASS: null` | the password itself, in the shell |

- `env:` with the value `null` passes a variable from the shell to BitBake only if it is set.
- The two M33 passwords in my `local.conf` were `"${SIGN_KEY_PASS}"`, not passwords of their
  own, so they are lines in `signing.yml` and only `SIGN_KEY_PASS` comes from the shell.
- `kas/signing.yml` was made from `local.conf` with a filter that drops every line containing
  `PASS`.

Check that git ignores it, in `meta-mylayer`:
```
git check-ignore kas/signing.yml
git status --short
```
**Expected:** `kas/signing.yml`; `git status` does not list it.

[`kas/debug.yml`](../kas/debug.yml) has only `EXTRA_IMAGE_FEATURES ?= "debug-tweaks"`, the
development setting from my `local.conf` (root without password). It is added on the command line
only for desk builds.

## 17c Build in a new folder

```
mkdir -p ~/Dokumente/yocto/kas-work
cd ~/Dokumente/yocto/kas-work
export DL_DIR=$HOME/yocto-downloads SSTATE_DIR=$HOME/yocto-sstate
read -rs SIGN_KEY_PASS; export SIGN_KEY_PASS
~/kas-venv/bin/kas build ../meta-mylayer/kas/stm32mp25-disco.yml:../meta-mylayer/kas/debug.yml:../meta-mylayer/kas/signing.yml
```
- `read -rs` waits for the password without showing it, and it is not in the shell history.
  `echo ${#SIGN_KEY_PASS}` shows only its length. It lives only in this terminal.
- kas forwards `DL_DIR` and `SSTATE_DIR` from the shell by itself, so downloads and sstate are
  shared with the old build.
- kas clones the four repositories into `kas-work/`, checks out the commits, writes
  `kas-work/build/conf/local.conf` and runs `bitbake -c build my-image my-bundle`.

**Expected:** the build configuration shows the pinned commits, and
`Tasks Summary: ... all succeeded`.
```
meta-mylayer         = "main:88667972e98d..."
meta-python          = "scarthgap:0f00f8b9a219..."
meta-rauc            = "scarthgap:d63878f20eba..."
meta-st-stm32mp      = "scarthgap:49046b2a0ad4..."
meta-yocto-bsp       = "scarthgap:b2c16f1e6913..."
```
The `TF-A SIGNATURE: force ST_TF_A_DEBUG_TRACE to '0'` warnings come from ST's signing class and
are expected.

## 17d Compare with the hand-made build

```
cd ~/Dokumente/yocto
for b in poky/build-stm32mp kas-work/build; do grep -o "VERITY_ROOT_HASH=[0-9a-f]\{16\}" $b/tmp/deploy/images/stm32mp25-disco/my-image-stm32mp25-disco.splitted-rootfs.ext4.verity-params; done
```
The root hash is a fingerprint of every byte of the rootfs. My results:

| Build | Root hash | Why |
|---|---|---|
| hand-made (`build-stm32mp`) | `0958c609...` | |
| kas without `debug.yml` | `2c6737c0...` | root password `*` instead of empty: `debug-tweaks` was only in `local.conf` |
| kas with `debug.yml` | `c72456e0...` | one file differs, see below |

To find the difference, unpack both images (read-only for the images, into a scratch folder) and
compare:
```
mkdir -p /tmp/cmp/hand /tmp/cmp/kas
debugfs -R "rdump / /tmp/cmp/hand" poky/build-stm32mp/tmp/deploy/images/stm32mp25-disco/my-image-stm32mp25-disco.splitted-rootfs.ext4
debugfs -R "rdump / /tmp/cmp/kas" kas-work/build/tmp/deploy/images/stm32mp25-disco/my-image-stm32mp25-disco.splitted-rootfs.ext4
diff -rq --no-dereference /tmp/cmp/hand /tmp/cmp/kas
```
**Expected (what I got):** one line, `/boot/fitImage-6.6.129-mydistro` differs. Every other file
(164 MB) is identical, and the package manifests are the same.

That file is the old FIT with the kernel, which U-Boot no longer uses since step 16f. Inside it:
- kernel config, version string (`oe-user@oe-host`, `Thu Mar 5 15:03:43 UTC 2026`) and the size of
  the unpacked `Image` (27808256 bytes) are the same;
- 52541 of the 53277 differing bytes are in one block, `IKCFG_ST`: the kernel's own gzip copy of
  its config (`/proc/config.gz`). Unpacked, both copies are the same text; the gzip data differs
  (51342 vs 51350 bytes, same gzip header). The few hundred other bytes follow from that block
  being 8 bytes longer.

So the configuration is reproduced exactly; the kernel binary is not bit-identical, because the
same config was compressed into different bytes. Once the old FIT leaves `/boot` (clean-up item in
the [plan](../README.md#next-steps)), the rootfs should give the same root hash; the kernel would
still differ in `bootfs`.

## Build time

`Sstate summary: ... 58% match` in the first kas build, and the second one rebuilt ~570 tasks
(glibc, gcc-runtime, systemd, the kernel). The recipes, variables and files of those tasks were the
same; following the task signatures down ended in tasks with the same task hash but a different
"unihash". BitBake's hash-equivalence database (`build/cache/hashserv.db`) is per build folder, and
the new folder started empty. A shared hash server (`BB_HASHSERVE_UPSTREAM`, forwarded by kas) or a
shared database fixes that; that belongs to CI.

## Errors you may see
- `[sign-stm32mp] Not able to find "/tmp/tmpXXXX/STMicroelectronics/.../STM32_SigningTool_CLI"`:
  kas runs BitBake with its own temporary `HOME`, so `${HOME}` in `signing.yml` points into
  `/tmp`. Use absolute paths there.
- A kas image without `kas/debug.yml` has root login locked (`*` in `/etc/shadow`) and no SSH
  key yet: do not flash it until the release setup (next step) gives a way to log in.
