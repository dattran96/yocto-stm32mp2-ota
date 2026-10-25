# 23 OTA from a server: hawkBit

**Goal:** the board gets its updates from a server instead of a USB stick. The server is
Eclipse hawkBit on the PC (Docker); on the board `rauc-hawkbit-updater` asks the server for new
software, downloads the RAUC bundle, installs it and reboots. The device token is not in the image
and not in this repository. Learning log:
[step 23](../README.md#step-23-ota-from-a-server-hawkbit).

| Part | What | Where | Status |
|---|---|---|---|
| 23a | Start the server (`hawkbit/docker-compose.yml`) | PC | done |
| 23b | Allow target tokens, create the board as a target | PC | done |
| 23c | `rauc-hawkbit-updater` in the image, config with the token on `/data` | layer, board | done |
| 23d | Upload a bundle, assign it; the board updates itself | PC, board | done |
| 23e | Database in a volume instead of RAM | PC | done |

```
 PC 192.168.2.90                                         board (WiFi)
 ┌───────────────────────────────┐                       ┌──────────────────────────────┐
 │ hawkbit-ui      :8088 (only PC)│  upload bundle,       │ rauc-hawkbit-updater         │
 │  └─► hawkbit server :8080 ◄────┼─ assign to the board  │  polls :8080 every 30 s ─────┤
 │      (DDI API for devices)     │◄──────────────────────┼─ "anything for me?"          │
 │                                │── bundle ────────────►│  → rauc install → reboot     │
 └───────────────────────────────┘◄── "success" ─────────┤  (token on /data)            │
                                                          └──────────────────────────────┘
```
The board always asks; the server never connects to the board. This works behind any router.

hawkBit's three levels:
```
Software module "my-bundle 1.13"   holds the file (artifact): my-bundle-stm32mp25-disco.raucb
        ▼
Distribution set "my-image 1.13"   what gets installed (can hold several modules)
        ▼ assign
Target "stm32mp25-disco-1"         gets it at its next poll
```

## 23a Start the server

[`hawkbit/docker-compose.yml`](../hawkbit/docker-compose.yml): server and web UI, version 1.1.0.

| | |
|---|---|
| Server, port 8080 | open to the network: the board polls it (DDI API); also the management API (REST) |
| Web UI, port 8088 | only on this PC (`127.0.0.1`) |
| Admin password | from the shell (`HAWKBIT_ADMIN_PASS`), not in the file; without it hawkBit's default is `admin:admin` |
| Database | H2 as a file in the volume `hawkbit-data`, with the uploaded bundles (23e) |

On the PC, in the layer folder:
```
read -rs HAWKBIT_ADMIN_PASS; export HAWKBIT_ADMIN_PASS
docker compose -f hawkbit/docker-compose.yml up -d
sleep 40
docker compose -f hawkbit/docker-compose.yml logs hawkbit | grep -E "Database:|Tomcat started"
```
**Expected:**
```
Database: jdbc:h2:file:/app/artifactrepo/db/hawkbit;MODE=LEGACY (H2 2.4)
Tomcat started on port 8080 (http) with context path '/'
```
Then `http://localhost:8088` in the browser, login `admin` with that password. Do not `echo` the
password: it stays in the terminal and its scrollback.

## 23b The board as a target

Two server settings: a device may log in with its own token (off by default), and how often
devices should ask (default 5 minutes). Then the target with its ID (`controllerId`); the server
makes a random security token. The token goes straight into a file in `~/yocto-keys/`, not to
the screen.

On the PC, in the same terminal (it has the password):
```
H=http://localhost:8080/rest/v1
curl -sf -u "admin:$HAWKBIT_ADMIN_PASS" -X PUT -H 'Content-Type: application/json' -d '{"value":true}' $H/system/configs/authentication.targettoken.enabled && echo OK
curl -sf -u "admin:$HAWKBIT_ADMIN_PASS" -X PUT -H 'Content-Type: application/json' -d '{"value":"00:00:30"}' $H/system/configs/pollingTime && echo OK
(umask 077; curl -sf -u "admin:$HAWKBIT_ADMIN_PASS" -X POST -H 'Content-Type: application/json' -d '[{"controllerId":"stm32mp25-disco-1","name":"STM32MP257F-DK"}]' $H/targets | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["securityToken"])' > ~/yocto-keys/hawkbit-token-stm32mp25-disco-1)
wc -c ~/yocto-keys/hawkbit-token-stm32mp25-disco-1
```
**Expected:** `OK`, `OK`, `33 /home/dat/yocto-keys/hawkbit-token-stm32mp25-disco-1` (32
characters and a line break). The web page shows the target as "unknown" until the board asks.

If the target has to be made again with the same token (new database), give the token in the
request; the board then needs no change:
```
curl -sf -u "admin:$HAWKBIT_ADMIN_PASS" -X POST -H 'Content-Type: application/json' -d "[{\"controllerId\":\"stm32mp25-disco-1\",\"name\":\"STM32MP257F-DK\",\"securityToken\":\"$(cat ~/yocto-keys/hawkbit-token-stm32mp25-disco-1)\"}]" $H/targets >/dev/null && echo OK
```

## 23c The updater on the board

`meta-rauc` has the recipe (`rauc-hawkbit-updater` 1.3) with a systemd service and its own user
`rauc-hawkbit`. RAUC's D-Bus policy lets every user send it commands, so that user can start an
install. My [`rauc-hawkbit-updater_%.bbappend`](../recipes-support/rauc-hawkbit-updater/rauc-hawkbit-updater_%.bbappend)
replaces meta-rauc's example config with a link to `/data`, plus a drop-in:
```
image (read-only):  /usr/bin/rauc-hawkbit-updater, service, user rauc-hawkbit
                    /etc/rauc-hawkbit-updater/config.conf ──link──► /data/hawkbit/config.conf
/data:              config.conf = server + target name + token
```
```
[Unit]
RequiresMountsFor=/data
ConditionPathExists=/data/hawkbit/config.conf
```
`rauc-hawkbit-updater` is in `IMAGE_INSTALL`. Build and install the bundle once more over USB
(see [22](22-wifi.md), with the hash check). After the reboot, on the board:
```
systemctl status rauc-hawkbit-updater --no-pager | head -n 5
grep " /tmp " /proc/mounts
```
**Expected:** `enabled`, the drop-in `hawkbit-data.conf`, `inactive (dead)` (no config yet);
`/tmp` is `tmpfs` (the updater downloads the bundle there, into RAM).

Folder for the config, on the board:
```
mkdir -p /data/hawkbit
chown root:rauc-hawkbit /data/hawkbit
chmod 750 /data/hawkbit
```
The config is written on the PC, in `~/yocto-keys/`, with the token from its file, and copied to
the board with `scp`:
```
(umask 077; cat > ~/yocto-keys/hawkbit-config-stm32mp25-disco-1.conf <<EOF
[client]
hawkbit_server            = 192.168.2.90:8080
ssl                       = false
ssl_verify                = false
tenant_id                 = DEFAULT
target_name               = stm32mp25-disco-1
auth_token                = $(cat ~/yocto-keys/hawkbit-token-stm32mp25-disco-1)
bundle_download_location  = /tmp/bundle.raucb
retry_wait                = 60
connect_timeout           = 20
timeout                   = 60
post_update_reboot        = true
log_level                 = message

[device]
mac_address               = b0:65:3a:67:41:ce
model                     = STM32MP257F-DK
EOF
)
scp -i ~/.ssh/id_ed25519_stm32 ~/yocto-keys/hawkbit-config-stm32mp25-disco-1.conf root@<board-ip>:/data/hawkbit/config.conf
```
- `post_update_reboot = true`: reboot after a successful install.
- `ssl = false`: plain HTTP on the home network. For a product: HTTPS with `ssl_verify = true`.
- `[device]`: sent to the server as target attributes.

On the board:
```
chown root:rauc-hawkbit /data/hawkbit/config.conf
chmod 640 /data/hawkbit/config.conf
systemctl start rauc-hawkbit-updater
journalctl -u rauc-hawkbit-updater -n 15 --no-pager
```
**Expected:**
```
systemd[1]: Started HawkBit client for Rauc.
rauc-hawkbit-updater[478]: Checking for new software...
rauc-hawkbit-updater[478]: No new software.
```
No `401` / `Unauthorized`. The web page shows the target as registered.

## 23d An update from the server

[`scripts/hawkbit-deploy.sh`](../scripts/hawkbit-deploy.sh) does the three steps (module with
upload, distribution set, assignment). On the PC, in the terminal with the password:
```
scripts/hawkbit-deploy.sh ../kas-work/build/tmp/deploy/images/stm32mp25-disco/my-bundle-stm32mp25-disco.raucb 1.13
```
A version can be uploaded once; for the same image again, use a new version string
(`1.13-2`). The first time, I ran the three steps by hand with `curl`; the output was:
```
module 1
my-bundle-stm32mp25-disco.raucb 73545747 0bbf0c5fd3a55a23d68057ae44a57f58fab39ad395982bfe012599b8e2981d42
set 1
"assigned": 1
```
On the board, watch it (the new 30 s polling time counts after the next poll):
```
journalctl -u rauc-hawkbit-updater -f
```
**Expected:**
```
New software ready for download (Name: my-bundle, Version: 1.13, Size: 73545747 bytes, URL: http://192.168.2.90:8080/DEFAULT/controller/v1/stm32mp25-disco-1/softwaremodules/1/artifacts/my-bundle-stm32mp25-disco.raucb)
Download complete. 5.44 MB/s
File checksum OK.
Installing: /tmp/bundle.raucb :  20% Verifying signature done.
Installing: /tmp/bundle.raucb :  43% Copying image to rootfs.0
...
```
then the reboot. RAUC writes into the slot that is not running (here A, because the USB install
had gone to B). After the reboot:
```
rauc status | grep -E "Booted|boot status"
```
**Expected:** `Booted from: rootfs.0 (A)`, both `boot status: good`. The web page shows
"Installed Distribution Set" = "Assigned Distribution Set" = `my-image 1.13`.

## 23e Database in a volume

hawkBit's default database is H2 in RAM (`jdbc:h2:mem:hawkbit;MODE=LEGACY` in
`hawkbit-jpa-defaults.properties`). After a container restart (also a PC reboot) the target,
its token and the distribution sets are gone, and the board gets `401`. Only the uploaded files
were in a volume.

Fix in the compose file: `spring.datasource.url` = `jdbc:h2:file:/app/artifactrepo/db/hawkbit;MODE=LEGACY`,
and a named volume `hawkbit-data` on `/app/artifactrepo`. `MODE=LEGACY` is needed: without it
every insert fails with `Function "IDENTITY" not found`.

Test, on the PC:
```
docker compose -f hawkbit/docker-compose.yml restart hawkbit
sleep 40
curl -sf -u "admin:$HAWKBIT_ADMIN_PASS" $H/targets/stm32mp25-disco-1 | python3 -c 'import sys,json; t=json.load(sys.stdin); print(t["controllerId"], t["updateStatus"])'
```
**Expected:** `stm32mp25-disco-1 registered`, and on the board new `Checking for new software...`
lines every 30 s without `401`.

## The updater started 2 minutes late after a reboot

Right after the reboot, `journalctl -u rauc-hawkbit-updater` showed `-- No entries --`:
```
systemctl status systemd-networkd-wait-online --no-pager | head -n 4
```
```
Active: activating (start) since Sat 2026-10-24 18:48:23 UTC; 1min 29s ago
```
The updater waits for `network-online.target`. `systemd-networkd-wait-online` waits for every
interface, also the Ethernet port with no cable, and gives up only after 2 minutes. Fix in
`wifi-config`: a drop-in with `--any` (one interface is enough). Tested with 1.14:
`Active: active (exited)` right after boot.

## Errors you may see

- `curl` prints nothing, no `OK`: the password variable is missing in this terminal (`read -rs`
  again) or the server is still starting (`Tomcat started` in the log).
- Server log `Function "IDENTITY" not found`: H2 without `MODE=LEGACY`.
- BusyBox `head: invalid option -- '5'`: use `head -n 5`.
