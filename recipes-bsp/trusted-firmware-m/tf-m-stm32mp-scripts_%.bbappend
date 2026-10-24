# ST copies the imgtool scripts with "cp -a", which keeps the owner of the
# build user (e.g. uid 30034 on the CI runner). do_package of the SDK version
# then fails with "getpwuid(): uid not found". Give the files to root.
do_install:append() {
    chown -R root:root ${D}${datadir}/tf-m/scripts/wrapper/imgtool
}
