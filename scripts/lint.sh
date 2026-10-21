#!/bin/sh
# Lint the recipes of this layer with oelint-adv.
#   scripts/lint.sh           fail on errors (CI gate)
#   scripts/lint.sh --report  show all findings, never fail
set -e
cd "$(dirname "$0")/.."
files=$(git ls-files '*.bb' '*.bbappend')

# Rules switched off, and why:
#   vars.noncoreoverride, func.specific: the bbappends are not limited to this
#     distro yet (README plan: Yocto-compatible layer)
#   vars.specific, vars.mispell, vars.mispell.unknown: overrides and variables
#     from other layers (stm32mpcommon, FLASHLAYOUT_*, VERITY_*, ST_VENDORFS)
#     are unknown to the linter
#   file.underscores: own recipes have no version in the file name
#   var.mandatoryvar.SRC_URI, .DESCRIPTION, .HOMEPAGE: images, bundles and
#     own recipes have no upstream project
#   vars.inconspaces: 'SRC_URI += " \' over several lines is the usual style
suppress="
oelint.vars.noncoreoverride
oelint.func.specific
oelint.vars.specific
oelint.vars.mispell
oelint.vars.mispell.unknown
oelint.file.underscores
oelint.var.mandatoryvar.SRC_URI
oelint.var.mandatoryvar.DESCRIPTION
oelint.var.mandatoryvar.HOMEPAGE
oelint.vars.inconspaces
"

if [ "$1" = "--report" ]; then
    # shellcheck disable=SC2086
    exec oelint-adv --release scarthgap --relpaths --exit-zero $files
fi

args=""
for rule in $suppress; do
    args="$args --suppress $rule"
done
# shellcheck disable=SC2086
exec oelint-adv --release scarthgap --relpaths --quiet --hide info --hide warning $args $files
