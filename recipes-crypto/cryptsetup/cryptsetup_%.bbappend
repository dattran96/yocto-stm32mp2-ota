# Not needed here: udev support pulls systemd's udev into the initramfs
# (~5 MB), ssh-token is for LUKS keys over SSH. veritysetup works
# without both.
PACKAGECONFIG:remove = "udev ssh-token"
