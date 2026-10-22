# libtiff's configure turns on LERC compression by itself when it finds
# liblerc, and the recipe has no PACKAGECONFIG to stop that. So tiff-native
# built on a PC with liblerc-dev links to the PC's libLerc.so.4, and that
# sstate breaks every other build host (seen in CI: Pillow in optee-os fails
# with "libLerc.so.4: cannot open shared object file"). We don't need LERC.
EXTRA_OECONF:append = " --disable-lerc"
