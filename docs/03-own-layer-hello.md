# 03 Own layer and a hello-world recipe

**Goal:** create a layer, write a recipe for a small C program, and understand cross-compiling.
Learning log: [step 3](learning-log.md#step-3-my-own-layer-and-a-hello-world-recipe).

## Steps

1. Create the layer next to poky and add it to the build:
   ```
   PC$ bitbake-layers create-layer ../../meta-mylayer
   PC$ bitbake-layers add-layer ../../meta-mylayer
   ```
   `create-layer` writes `conf/layer.conf` and an example recipe that you can delete.
2. Write the program and its recipe. The folder structure matters: `conf/layer.conf` only finds
   recipes at `recipes-*/*/*.bb`.
   - [`recipes-hello/hello/files/hello.c`](../recipes-hello/hello/files/hello.c)
   - [`recipes-hello/hello/hello_1.0.bb`](../recipes-hello/hello/hello_1.0.bb):
     `LICENSE` + `LIC_FILES_CHKSUM`, `SRC_URI = "file://hello.c"`, and two tasks:
     ```
     do_compile() {
         ${CC} ${CFLAGS} ${LDFLAGS} hello.c -o hello
     }
     do_install() {
         install -d ${D}${bindir}
         install -m 0755 hello ${D}${bindir}
     }
     ```
3. Build only this recipe:
   ```
   PC$ bitbake hello
   ```
4. Add it to the image (`IMAGE_INSTALL:append = " hello"` in `local.conf` for now), rebuild the
   image and run `hello` in QEMU.

## Check

```
PC$ bitbake-getvar -r hello --value CC
PC$ file tmp/work/cortexa57-poky-linux/hello/1.0/image/usr/bin/hello
```
**Expected:**
- `CC` is `aarch64-poky-linux-gcc -mcpu=cortex-a57+crc ... --sysroot=...`: a compiler that runs on
  your x86-64 PC and produces ARM64 code.
- `file` says `ARM aarch64`.

Where does `aarch64` come from? `MACHINE` → `conf/machine/qemuarm64.conf` →
`tune-cortexa57.inc` → `TARGET_ARCH`. Follow it with `bitbake-getvar -r hello TARGET_ARCH`,
which shows each file that set the variable.

Look around the recipe's work folder `tmp/work/cortexa57-poky-linux/hello/1.0/`:

| Folder | Content |
|---|---|
| `temp/log.do_<task>` | output of each task |
| `temp/run.do_<task>` | the exact shell script BitBake ran |
| `image/` | result of `do_install` |
| `packages-split/` | the files split into packages |
