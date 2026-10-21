# Use this board's system.conf and the public CA certificate from this
# layer instead of meta-rauc's examples.
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# Fill in the anti-rollback limit: this system's own version
do_install:append() {
    sed -i 's/@DISTRO_VERSION@/${DISTRO_VERSION}/' ${D}${sysconfdir}/rauc/system.conf
}
