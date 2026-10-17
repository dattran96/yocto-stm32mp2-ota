# Follow-along guides

These guides repeat each lesson of the [learning log](../README.md#learning-log) as steps you
can follow yourself. The learning log says *what I did and what went wrong*; the guides say
*what to type*, and what you should see.

- [verify.md](verify.md): how to check build results and the board, with the expected output.
  Most guides link to it instead of repeating the commands.

| Guide | Learning log | Hardware |
|---|---|---|
| [01 First build with poky](01-first-build.md) | Step 1 | PC (QEMU) |
| [02 Adding packages to an image](02-add-packages.md) | Step 2 | PC (QEMU) |
| [03 Own layer and a hello-world recipe](03-own-layer-hello.md) | Step 3 | PC (QEMU) |
| [04 Patching a recipe with devtool](04-devtool-modify.md) | Step 4 | PC (QEMU) |
| [05 A custom image recipe](05-custom-image.md) | Step 5 | PC (QEMU) |
| [06 A third-party project with devtool add](06-devtool-add.md) | Step 6 | PC (QEMU) |
| [07 Building and using an SDK](07-sdk.md) | Step 7 | PC (QEMU) |
| [08 A CMake project as a boot service, then systemd](08-cmake-service.md) | Step 8, 8b | PC (QEMU) |
| [09 Kernel fragments and an out-of-tree module](09-kernel.md) | Step 9a, 9b | PC (QEMU) |
| [10 Own distro configuration](10-distro.md) | Step 10 | PC (QEMU) |
| [11 STM32MP257F-DK bring-up](11-stm32mp2-bringup.md) | Step 11 | Board |
| [12 A/B updates with RAUC](12-rauc-ota.md) | Step 12a–12e | Board |
| [12f Kernel inside each slot](12f-kernel-in-slots.md) | Step 12f | Board |
| [12g Kernel as a FIT image](12g-fit-image.md) | Step 12g | Board |
| [12h Signed FIT image](12h-signed-fit.md) | Step 12h | Board |
| [13 Secure boot chain](13-secure-boot.md) | Step 13 | Board |
| [14 Persistent data partition](14-data-partition.md) | Step 14 | Board |

## Conventions

- Guides 01–13 mark each command with a prompt (below). From guide 14 on, command blocks have
  no prompt, so they can be copied as they are; a line above each block says where to run it.
- `PC$` is a shell on the build host, inside the build environment
  (`source oe-init-build-env <build dir>` from the `poky` folder).
- `board#` is the serial console of the board, logged in as `root`
  (`picocom -b 115200 /dev/ttyACM0` on the PC).
- `STM32MP>` is the U-Boot prompt. Press any key during "Hit any key to stop autoboot".
- **Expected:** blocks show what you should see. If you see something else, stop there and
  find out why before you go on: the next step builds on it.
- Folder layout used in all guides:
  ```
  yocto/
  ├── poky/                  (branch scarthgap)
  │   ├── build/             QEMU build
  │   └── build-stm32mp/     board build
  ├── meta-mylayer/          this repository
  ├── meta-openembedded/
  ├── meta-st-stm32mp/
  └── meta-rauc/
  ```
- Signing keys are **never** in this repository. They live in `~/yocto-keys/` and are
  referenced from `local.conf`.
