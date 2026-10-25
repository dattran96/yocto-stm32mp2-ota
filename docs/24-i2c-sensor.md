# 24 Device tree and an I2C sensor (BME280)

**Goal:** a Bosch BME280 (temperature, pressure, humidity) on the board's 40-pin header, described
in the device tree and read through Linux's IIO interface; later with my own driver. Learning log:
[step 24](../README.md#step-24-device-tree-and-an-i2c-sensor-bme280).

| Part | What | Where | Status |
|---|---|---|---|
| 24a | Which bus and pins: schematic, device tree, RIF; wiring | PC, table | done |
| 24b | `i2c8` on, `i2c-tools`, sensor found | layer, board | done |
| 24c | Device tree node + Linux's `bmp280` driver, values in IIO | layer, board | done |
| 24d | Own driver as an out-of-tree module | layer, board | next |

## I2C in short

Two wires shared by many chips: SDA (data) and SCL (clock). One controller (the STM32) and
devices with a 7-bit address (BME280: `0x76` or `0x77`). The lines are open-drain: chips can only
pull them to 0 V, pull-up resistors (2–10 kΩ to 3.3 V) pull them back up. Without pull-ups
nothing works.
```
3.3V ──┬───────────┬─────────
      [R]         [R]          pull-ups (here on the sensor board)
SDA ───┴─────┬─────│────┬────
SCL ─────────│─────┴────│──┬─
          ┌──┴──┐    ┌──┴──┴┐
          │STM32│    │BME280│  address 0x76
          │I2C8 │    └──────┘
          └─────┘
```

## 24a From a header pin to a driver

A chip pin can do many jobs. Several layers decide which one, and which code runs:
```
① Hardware:   STM32 pins PZ3 / PZ4 ──copper──► header pins 3 / 5        (manual UM3385, table 24)
② Security:   may Linux use I2C8 and PZ3/PZ4?                            (OP-TEE RIF, before Linux)
③ Pinmux:     PZ3 = I2C8_SDA, PZ4 = I2C8_SCL (alternate function 8)     (device tree, pinctrl)
④ Controller: &i2c8 status = "okay" ──► driver i2c-stm32f7 ──► bus /dev/i2c-1
⑤ Device:     child node pressure@76 ──► driver bmp280 ──► /sys/bus/iio/devices/iio:device0
```

| Layer | File | Found |
|---|---|---|
| ① | UM3385 table 24 "GPIO connector pinout" | pin 1 3V3, pin 3 PZ3 (I2C8_SDA), pin 5 PZ4 (I2C8_SCL), pin 6 GND, pin 9 GND, pin 17 3V3; the header follows the Raspberry Pi layout |
| ② | OP-TEE `stm32mp257f-dk-ca35tdcid-rif.dtsi` (in ST's OP-TEE patch) | `I2C8`, `PZ3`, `PZ4`: `RIF_NSEC`, `RIF_CFDIS`: non-secure, any CPU may use them (`PZ2` is secure: the tamper pin) |
| ③ | `arch/arm64/boot/dts/st/stm32mp25-pinctrl.dtsi:918` | `i2c8_pins_a`: `PZ4` I2C8_SCL, `PZ3` I2C8_SDA, `AF8`, `drive-open-drain`, `bias-disable` (no internal pull-up) |
| ④ | `arch/arm64/boot/dts/st/stm32mp257f-dk.dts:483` | `&i2c8`, 100 kHz, `status = "disabled"`: prepared by ST, switched off |
| ⑤ | — | not there: added in 24c |

The kernel sources are in `build/tmp/work-shared/stm32mp25-disco/kernel-source/` after a build.

What the kernel already had: `CONFIG_I2C_STM32F7=m` (controller driver), `CONFIG_I2C_CHARDEV=y`
(`/dev/i2c-*`). Missing: `CONFIG_BMP280` (not set) and the `i2c-tools` package.

### Wiring

The sensor board has a 6-pin PH2.0 plug for I2C or SPI: VCC, GND, SCL/SCK, SDA/MOSI, MISO/ADDR,
CS. With the board switched off:
```
sensor            DK header
VCC        ─────► pin 1   3V3
SDA/MOSI   ─────► pin 3   PZ3 I2C8_SDA
SCL/SCK    ─────► pin 5   PZ4 I2C8_SCL
GND        ─────► pin 6   GND
MISO/ADDR  ─────► pin 9   GND   (address 0x76)
CS         ─────► pin 17  3V3   (I2C mode)
```
- ADDR is the chip's SDO pin: in I2C mode it sets the last address bit (GND `0x76`, 3.3 V `0x77`).
- CS high at power-on selects I2C, low selects SPI.
- 3.3 V, not 5 V: with 5 V the sensor board's pull-ups would put 5 V on the STM32 pins.
- Follow the labels, not the wire colours.

## 24b/c The layer changes

| File | Change |
|---|---|
| [`recipes-kernel/linux/files/bmp280.cfg`](../recipes-kernel/linux/files/bmp280.cfg) | `CONFIG_BMP280=m` (it selects `BMP280_I2C`) |
| [`recipes-kernel/linux/files/0001-arm64-dts-st-stm32mp257f-dk-BME280-on-i2c8.patch`](../recipes-kernel/linux/files/0001-arm64-dts-st-stm32mp257f-dk-BME280-on-i2c8.patch) | `&i2c8` `"okay"`, child node `pressure@76` |
| [`recipes-kernel/linux/linux-stm32mp_%.bbappend`](../recipes-kernel/linux/linux-stm32mp_%.bbappend) | the patch in `SRC_URI`, the fragment in `KERNEL_CONFIG_FRAGMENTS` |
| [`recipes-core/images/my-image.bb`](../recipes-core/images/my-image.bb) | `i2c-tools` |

### Where the driver comes from

The driver is part of Linux (kernel.org), not ST's: `drivers/iio/pressure/bmp280-core.c`
(logic), `bmp280-i2c.c` (I2C part), `bmp280-regmap.c`. ST's kernel recipe is Linux 6.6 plus ST's
patch, so the source is already there. ST's config only builds the drivers for the chips on its
board; `CONFIG_BMP280=m` adds this one. `menuconfig` shows the same option: *Device Drivers →
Industrial I/O support → Pressure sensors*, `/` to search for `BMP280`; `-c diffconfig` turns a
change made there into a fragment like `bmp280.cfg`.

### The device tree change

The patch changes ST's source file `stm32mp257f-dk.dts` in `do_patch`; `dtc` compiles it to
`stm32mp257f-dk.dtb` in `do_compile`, which goes into the signed FIT. A patch instead of an edit:
the source folder is unpacked again on rebuilds, the patch is in git with its reason, and it
fails loudly if ST changes the same lines. A patch instead of my own `.dts`: the board keeps its
name `stm32mp257f-dk`, which the FIT, U-Boot and the WiFi firmware link depend on.

The `&i2c8` block after the patch:
```
&i2c8 {
	pinctrl-names = "default", "sleep";
	pinctrl-0 = <&i2c8_pins_a>;
	pinctrl-1 = <&i2c8_sleep_pins_a>;
	i2c-scl-rising-time-ns = <185>;
	i2c-scl-falling-time-ns = <20>;
	clock-frequency = <100000>;
	status = "okay";
	/* spare dmas for other usage */
	/delete-property/dmas;
	/delete-property/dma-names;

	/* BME280 on the GPIO expansion connector: SDA pin 3, SCL pin 5, SDO to GND */
	pressure@76 {
		compatible = "bosch,bme280";
		reg = <0x76>;
	};
};
```
| Line | Meaning |
|---|---|
| `&i2c8 {` | adds to the node with the label `i2c8` from the chip file `stm32mp251.dtsi` (`i2c8: i2c@46040000 { compatible = "st,stm32mp25-i2c"; ... }`) |
| `pinctrl-0 = <&i2c8_pins_a>` | pin setting for normal use: PZ3/PZ4 as I2C8 |
| `i2c-scl-rising/falling-time-ns` | signal edges on this board, for exact timing |
| `clock-frequency = <100000>` | 100 kHz, safe with jumper wires |
| `status = "okay"` | the switch: with `"disabled"` no driver starts |
| `pressure@76` | generic name of the kind of device, `@` its address |
| `compatible = "bosch,bme280"` | which chip: `"vendor,model"`; the kernel looks for a driver with this string |
| `reg = <0x76>` | the I2C address |

### How `compatible` finds the driver

`drivers/iio/pressure/bmp280-i2c.c`:
```c
static const struct of_device_id bmp280_of_i2c_match[] = {
	...
	{ .compatible = "bosch,bme280", .data = &bme280_chip_info },
	...
};
MODULE_DEVICE_TABLE(of, bmp280_of_i2c_match);
```
One driver handles six Bosch chips; `.data` tells it which one (only the BME280 has humidity).
`MODULE_DEVICE_TABLE` writes the names into the `.ko`, so udev loads the module when the node
is there. The allowed values are in the binding
`Documentation/devicetree/bindings/iio/pressure/bmp085.yaml`; `bosch` is listed in
`vendor-prefixes.yaml`. For a new sensor: grep the bindings for the rules, grep `drivers/` for the
driver, write the node.

## 24b/c Build and check

After the build, on the PC (in `kas-work`):
```
dtc=build/tmp/sysroots-components/x86_64/dtc-native/usr/bin/dtc
$dtc -I dtb -O dts build/tmp/deploy/images/stm32mp25-disco/kernel/stm32mp257f-dk.dtb 2>/dev/null | sed -n '/i2c@46040000 {/,/^\t\t\t};/p' | grep -E "status|clock-frequency|pressure|compatible|reg = <0x76>"
grep -E '^(i2c-tools|kernel-module-bmp280-i2c|kernel-module-i2c-stm32f7-drv) ' build/tmp/deploy/images/stm32mp25-disco/my-image-stm32mp25-disco.rootfs.manifest
```
**Expected:** `status = "okay"`, `clock-frequency = <0x186a0>` (100000 in hex), `pressure@76`,
`compatible = "bosch,bme280"`, `reg = <0x76>`; the three packages. ST's image has all kernel
modules (1058), so the driver needs no line in `IMAGE_INSTALL`.

Installed over hawkBit ([23](23-hawkbit.md)), on the PC:
```
scripts/hawkbit-deploy.sh ../kas-work/build/tmp/deploy/images/stm32mp25-disco/my-bundle-stm32mp25-disco.raucb 1.14
```
**Expected:** `uploaded my-bundle-stm32mp25-disco.raucb 73582611 9fdf2ab6...` (the same sha256 as
the file on the PC), `assigned to stm32mp25-disco-1: 1`. The board downloads, installs into the
other slot and reboots.

On the board:
```
dmesg | grep -E "46040000|bmp280"
i2cdetect -l
i2cdetect -y 1
cat /sys/bus/iio/devices/iio:device0/name
```
**Expected:**
```
stm32f7-i2c 46040000.i2c: STM32F7 I2C-1 bus adapter
bmp280 1-0076: supply vddd not found, using dummy regulator
bmp280 1-0076: supply vdda not found, using dummy regulator
bmp280 1-0076: release reset
bmp280 1-0076: 0x60 is a known chip id for bme280
i2c-0	i2c       	STM32F7 I2C(0x0000000040130000)	I2C adapter
i2c-1	i2c       	STM32F7 I2C(0x0000000046040000)	I2C adapter
...
70: -- -- -- -- -- -- UU --
bme280
```
- `I2C-1`: the controller driver started for `0x46040000` (I2C8). `i2c-0` is ST's internal bus
  for the camera and HDMI.
- `1-0076`: bus 1, address 0x76, the `pressure@76` node.
- `supply ... dummy regulator`: harmless. The binding allows `vddd-supply`/`vdda-supply`; the
  sensor is powered from pin 1 directly.
- `0x60 is a known chip id`: the driver read register `0xD0` over the wires and got the BME280's
  ID (the BMP280's is `0x58`). Proof that wiring, pull-ups, address and I2C mode are right.
- `UU`: a device answers and a kernel driver uses it. `76` would mean "answers, no driver";
  `--` everywhere means nothing answers (wiring, pull-ups).

The values:
```
cd /sys/bus/iio/devices/iio:device0
cat in_temp_input in_pressure_input in_humidityrelative_input
```
**Expected** (my room):
```
18790
95.242222656
69453
```
IIO units: temperature in milli-degrees (18.79 °C), pressure in kPa (952.4 hPa; about 1 hPa less
per 8 m of height), humidity in milli-percent (69.5 %). Breathe on the sensor: temperature and
humidity go up.

## Errors you may see

- `head: invalid option -- '5'`: BusyBox, use `head -n 5`.
- Many `Fixed dependency cycle(s)` lines in `dmesg`: from ST's camera/HDMI nodes, on every boot.
