# A/B boot selection for RAUC.
# Slot A = partition "rootfs1", slot B = partition "rootfs2" on the boot device.
# RAUC changes BOOT_ORDER and BOOT_x_LEFT from Linux with fw_setenv;
# rauc-mark-good resets the counter after a successful boot.
# Run by U-Boot's distro boot: devnum and distro_bootpart (= bootfs) are set.

test -n "${BOOT_ORDER}" || setenv BOOT_ORDER "A B"
test -n "${BOOT_A_LEFT}" || setenv BOOT_A_LEFT 3
test -n "${BOOT_B_LEFT}" || setenv BOOT_B_LEFT 3

setenv rootpartname
for BOOT_SLOT in "${BOOT_ORDER}"; do
  if test "x${rootpartname}" != "x"; then
    # a slot was already chosen, skip the others
  elif test "x${BOOT_SLOT}" = "xA"; then
    if test ${BOOT_A_LEFT} -gt 0; then
      setexpr BOOT_A_LEFT ${BOOT_A_LEFT} - 1
      setenv rootpartname rootfs1
      setenv raucslot A
    fi
  elif test "x${BOOT_SLOT}" = "xB"; then
    if test ${BOOT_B_LEFT} -gt 0; then
      setexpr BOOT_B_LEFT ${BOOT_B_LEFT} - 1
      setenv rootpartname rootfs2
      setenv raucslot B
    fi
  fi
done

if test -z "${rootpartname}"; then
  echo "No slot with attempts left, resetting counters"
  setenv BOOT_A_LEFT 3
  setenv BOOT_B_LEFT 3
  saveenv
  reset
fi

echo "Booting slot ${raucslot} (${rootpartname}), attempts left: A=${BOOT_A_LEFT} B=${BOOT_B_LEFT}"
saveenv

part number mmc ${devnum} ${rootpartname} rootpart
part uuid mmc ${devnum}:${rootpart} rootuuid
setenv bootargs "root=PARTUUID=${rootuuid} rootwait rw earlycon console=${console},${baudrate} rauc.slot=${raucslot}"

# The FIT holds kernel + device tree with sha256 hashes; bootm checks them
# and uses the FIT's default configuration (the matching device tree).
if load mmc ${devnum}:${rootpart} ${kernel_addr_r} /boot/fitImage; then
  bootm ${kernel_addr_r}
fi

# Only reached if loading or booting failed. Reset so the next attempt
# (and, once the counter is used up, the other slot) is tried without
# someone pressing the reset button.
echo "Booting slot ${raucslot} failed, resetting"
sleep 3
reset
