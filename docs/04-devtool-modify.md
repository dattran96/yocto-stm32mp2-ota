# 04 Patching an existing recipe with devtool

**Goal:** change the source code of a recipe from poky (`file`) without editing poky, and keep
the change as a patch in your own layer.
Learning log: [step 4](learning-log.md#step-4-patching-an-existing-recipe-with-devtool).

## Steps

1. Get the source into a workspace:
   ```
   PC$ devtool modify file
   ```
   The source is now in `build/workspace/sources/file`, as a git repository.
2. Change something visible. In this layer, `file --version` prints an extra line. See
   [the patch](../recipes-devtools/file/file/0001-file-mark-version-output-as-patched.patch).
3. Build and test:
   ```
   PC$ devtool build file
   ```
4. Commit the change in the source repository. Put the upstream status in the commit message,
   because patches for core recipes must have it:
   ```
   PC$ cd workspace/sources/file
   PC$ git commit -a -m "file: mark version output as patched" -m "Upstream-Status: Inappropriate [local customization for learning]"
   PC$ cd -
   ```
5. Write the patch and a `.bbappend` into your layer, and remove the recipe from the workspace:
   ```
   PC$ devtool finish file ../../meta-mylayer
   ```
6. Restrict the patch to the target. Without this, `file-native` (the version that runs on the
   build host) is patched too, and hundreds of tasks that use it are rebuilt. In
   [`file_%.bbappend`](../recipes-devtools/file/file_%.bbappend):
   ```
   SRC_URI:append:class-target = " file://0001-file-mark-version-output-as-patched.patch"
   ```

## Check

```
PC$ bitbake-layers show-appends | grep -A2 "^file"
PC$ bitbake core-image-minimal && runqemu core-image-minimal nographic
board# file --version
```
**Expected:** `file_%.bbappend` from `meta-mylayer` is listed, and `file --version` shows the
patched line.

## Errors you may see
- `QA Issue: Missing Upstream-Status in patch [patch-status]`: add the `Upstream-Status:`
  line to the patch header.
- Do not press `Ctrl-Z` during `devtool build`: it pauses the command, which keeps the BitBake
  server busy, and every following command hangs at `Reconnecting to bitbake server...`.
  Use `Ctrl-C` to cancel.
