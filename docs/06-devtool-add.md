# 06 A third-party project with devtool add

**Goal:** create a recipe for a project from GitHub, change its code, and test it on the
running target without rebuilding the image.
Learning log: [step 6](learning-log.md#step-6-adding-a-third-party-project-with-devtool-add).

## Steps

1. Generate a draft recipe and get the source into the workspace:
   ```
   PC$ devtool add microsocks https://github.com/rofl0r/microsocks
   ```
2. Review the draft (`devtool edit-recipe microsocks`). Don't trust it blindly:
   - `LICENSE = "Unknown"`: read the project's `COPYING` file. It is MIT, so set `LICENSE = "MIT"`.
   - The Makefile installs to `/usr/local` by default. Pass `prefix=${prefix}` in `do_install`.
   - Compare with the final [`microsocks_git.bb`](../recipes-microsocks/microsocks/microsocks_git.bb).
3. Change the code in `workspace/sources/microsocks`. This layer prints a line at startup; see
   [the patch](../recipes-microsocks/microsocks/microsocks/0001-microsocks-print-a-message-at-startup.patch).
4. Test loop: start `runqemu my-image nographic`, then on the PC:
   ```
   PC$ devtool build microsocks && devtool deploy-target microsocks root@192.168.7.2
   board# microsocks -p 1080 &
   ```
5. When it works: remove build output from the source folder, commit with an
   `Upstream-Status:` line, then:
   ```
   PC$ devtool finish microsocks ../../meta-mylayer
   ```
6. Add `microsocks` to `IMAGE_INSTALL` in `my-image.bb` and rebuild the image.

## Check
```
board# microsocks -p 1080 &
$ curl --socks5 192.168.7.2:1080 http://example.com/      (on the PC)
```
**Expected:** your startup message is printed, and `curl` gets the page through the proxy.

## Errors you may see
- Your `printf` appears only when the program exits: stdout is buffered until a newline.
  End the string with `\n`.
- `devtool finish` says `Source tree is not clean: ?? microsocks`: the Makefile builds inside
  the source folder. Delete the build output; don't use `--force`.
