# 22 Network: WiFi

**Goal:** the board joins a WiFi network at boot, gets an address by DHCP and can be reached by
SSH. The network name and password are not in the image and not in this repository: they are
written once on the board, to `/data`. Learning log:
[step 22](learning-log.md#step-22-network-wifi).

| Part | What | Where | Status |
|---|---|---|---|
| 22a | Ethernet | board | skipped (no cable at hand) |
| 22b | WiFi: driver, firmware, `wpa_supplicant`, DHCP, credentials on `/data` | layer, board | done |

```
 image (read-only, checked by dm-verity)              /data (stays across updates)
 ──────────────────────────────────────────           ─────────────────────────────────────
 brcmfmac driver ──loads──► CYW43439 firmware
 wpa_supplicant@wlan0 ──reads──► /etc/wpa_supplicant/wpa_supplicant-wlan0.conf
                                   (a link) ─────────────────► /data/wifi/wpa_supplicant-wlan0.conf
                                                               (network name + psk hash)
 systemd-networkd: 30-wlan0.network (DHCP) ──► wlan0 gets an address ──► ssh root@<ip>
```

## The parts

| Part | What it is | Where it comes from |
|---|---|---|
| `brcmfmac` | Linux driver for Broadcom/Cypress WiFi chips, runs on the CPU | kernel tree, already a module in the image |
| Firmware | program for the WiFi chip's own CPU, loaded by the driver at every boot | Murata's git repos, ST's recipe `linux-firmware-addons-bcm43xx` |
| `wpa_supplicant` | logs in to the access point (WPA2) | poky |
| `systemd-networkd` | asks the router for an address (DHCP) | poky's systemd, on by default |

The chip is a Murata 1YN module with a Cypress CYW43439, on SDIO. The device tree
(`stm32mp257f-dk.dts`) has it as `brcm,bcm4329-fmac`. ST's firmware recipe
(`meta-st-stm32mp/recipes-kernel/linux-firmware/linux-firmware-addons-bcm43xx.bb`) installs
three files under the names the driver looks for, and links named after the board:
```
/lib/firmware/brcm/brcmfmac43439-sdio.bin        program for the chip
/lib/firmware/brcm/brcmfmac43439-sdio.txt        board settings (antenna, pins)
/lib/firmware/brcm/brcmfmac43439-sdio.clm_blob   allowed channels and power per country
/lib/firmware/brcm/brcmfmac43439-sdio.st,stm32mp257f-dk.bin -> brcmfmac43439-sdio.bin
```
The license is `Cypress-bcm43xx`: the files may be shipped, there is no source.

## 22b-1 The recipe

[`recipes-connectivity/wifi-config/wifi-config.bb`](../recipes-connectivity/wifi-config/wifi-config.bb),
added to `IMAGE_INSTALL` in `my-image.bb`:

| File in the image | Why |
|---|---|
| `/etc/systemd/network/30-wlan0.network` | DHCP on `wlan0`; `ClientIdentifier=mac`, see "The address changed" below |
| `/etc/wpa_supplicant/wpa_supplicant-wlan0.conf` → `/data/wifi/...` | the image has only the link; the file is written on the board |
| `/etc/systemd/system/multi-user.target.wants/wpa_supplicant@wlan0.service` | starts poky's template unit `wpa_supplicant@.service` for `wlan0` at boot |
| `/etc/systemd/system/wpa_supplicant@wlan0.service.d/wpa-data.conf` | waits for `/data`; does not start when the file is missing |

The drop-in:
```
[Unit]
RequiresMountsFor=/data
ConditionPathExists=/data/wifi/wpa_supplicant-wlan0.conf
```
`RDEPENDS` pulls in `wpa-supplicant`, the firmware and `iw` (shows the link).

Build and install as usual (development build with your own signing key, see
[17](17-kas-build.md)). The board rejects a CI bundle: that one is signed with the CI test key.

## 22b-2 Install and check the chip

The rootfs is read-only, so the USB stick goes to `/run/usb` (RAM). The stick can show up as
`sda1` or `sdb1`.

On the PC (`<LABEL>` is the stick's name in `ls /media/$USER`):
```
cp -L build/tmp/deploy/images/stm32mp25-disco/my-bundle-stm32mp25-disco.raucb /media/$USER/<LABEL>/
sync
sha256sum build/tmp/deploy/images/stm32mp25-disco/my-bundle-stm32mp25-disco.raucb /media/$USER/<LABEL>/my-bundle-stm32mp25-disco.raucb
```
**Expected:** the same hash twice. Eject the stick before pulling it out.

On the board:
```
mkdir -p /run/usb
mount /dev/sda1 /run/usb
sha256sum /run/usb/my-bundle-stm32mp25-disco.raucb
rauc install /run/usb/my-bundle-stm32mp25-disco.raucb
umount /run/usb
reboot
```
After the reboot:
```
dmesg | grep brcmf
ip link show wlan0
```
**Expected:**
```
brcmfmac: brcmf_fw_alloc_request: using brcm/brcmfmac43439-sdio for chip BCM43439/5
brcmfmac: brcmf_c_process_txcap_blob: no txcap_blob available (err=-2)
brcmfmac: brcmf_c_preinit_dcmds: Firmware: BCM43439/5 wl0: Apr 18 2024 01:55:03 version 7.95.86 (9a12fda CY) FWID 01-86edbd67
3: wlan0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc fq_codel qlen 1000
```
`no txcap_blob` is harmless (an optional file with transmit power limits). `NO-CARRIER` is
right here: there is no network file on `/data` yet.

## 22b-3 Credentials on `/data`

On the board, one line at a time. `read -r SSID` waits for the network name, `read -rs PASS`
for the password (not shown, not in the shell history):
```
mkdir -p /data/wifi
chmod 700 /data/wifi
read -r SSID
read -rs PASS
{ echo "ctrl_interface=/run/wpa_supplicant"; echo "country=DE"; wpa_passphrase "$SSID" "$PASS" | sed '/#psk/d'; } > /data/wifi/wpa_supplicant-wlan0.conf
chmod 600 /data/wifi/wpa_supplicant-wlan0.conf
unset PASS
grep -v psk /data/wifi/wpa_supplicant-wlan0.conf
```
- `wpa_passphrase` stores a hash of the password (`psk=...`); `sed` removes the comment line in
  which it repeats the plain password.
- `country=DE` sets the channels and power allowed in Germany.

**Expected:**
```
ctrl_interface=/run/wpa_supplicant
country=DE
network={
	ssid="<network name>"
}
```
Start it and look at the link:
```
systemctl start wpa_supplicant@wlan0
iw dev wlan0 link
ip addr show wlan0
```
**Expected:**
```
Connected to 4c:22:f3:..:..:.. (on wlan0)
    SSID: <network name>
    freq: 2437.0
    signal: -61 dBm
...
    inet 192.168.2.91/24 brd 192.168.2.255 scope global dynamic wlan0
```

## 22b-4 SSH over WiFi and a reboot

This is also the board test of step 18 (key login only).

On the PC:
```
ssh -i ~/.ssh/id_ed25519_stm32 root@<ip>
```
**Expected:** the first time `Are you sure you want to continue connecting`, answer `yes`; then
`root@stm32mp25-disco:~#` without a password.

Reboot the board, then on the PC:
```
ssh -i ~/.ssh/id_ed25519_stm32 root@<ip> 'systemctl is-active wpa_supplicant@wlan0 systemd-networkd; rauc status | grep -E "Booted|boot status"'
```
**Expected:**
```
active
active
Booted from: rootfs.0 (A)
      boot status: good
      boot status: good
```
The host key fingerprint is the same as before the reboot (Dropbear keeps it on `/data`, step 18).

## The address changed after the reboot

First boot `.91`, after the reboot `.92`. The DHCP client of systemd-networkd does not send the
MAC address as its ID, but one made from `/etc/machine-id`. In the read-only image that file is
empty (step 15c), so systemd puts a new, temporary ID over it at every boot:
```
grep machine-id /proc/mounts
```
```
tmpfs /etc/machine-id tmpfs ro,size=773576k,nr_inodes=819200,mode=755 0 0
```
```
boot 1: machine-id A ──► DHCP "I am A" ──► .91
boot 2: machine-id B ──► DHCP "I am B" ──► .92   (a new device for the router)
```
Fix in `30-wlan0.network` (written; board test with the next build, expected: the same
address after a reboot):
```
[DHCPv4]
ClientIdentifier=mac
```
Open: one machine-id that stays (kept on `/data`). The journal on `/data` keeps its files in
`/var/log/journal/<machine-id>/`, so today every boot starts a new folder there.

## Errors you may see

- `mkdir: can't create directory '/mnt/usb': Read-only file system`: use `/run/usb`.
- `rauc install`: `device-mapper: verity: 7:0: data block 2824 is corrupted`, `SQUASHFS error`,
  `Failed updating slot bootfs.0 ... Input/output error`: the bundle on the stick is damaged (the
  signature check at the start still passes, the blocks are checked while reading). Compare
  `sha256sum` on the PC and on the board, copy again with `sync`. The failed install does not
  change the boot order; the running slot stays.
- `FAT-fs (sdb1): Volume was not properly unmounted`: the stick was pulled without `umount`.
  Harmless if the hash is right.
- `findmnt: not found`: not in the image, use `grep <path> /proc/mounts`.
