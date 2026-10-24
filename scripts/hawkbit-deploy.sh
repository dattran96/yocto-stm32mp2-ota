#!/bin/sh
# Upload a RAUC bundle to hawkBit and assign it to the board (docs/23-hawkbit.md).
#   scripts/hawkbit-deploy.sh <bundle.raucb> <version> [target]
# Needs HAWKBIT_ADMIN_PASS in the environment. HAWKBIT_URL defaults to this PC.
# Each version can be uploaded once; for a second upload of the same image
# use a new version string (for example 1.13-2).
set -eu

bundle=$1
version=$2
target=${3:-stm32mp25-disco-1}
api=${HAWKBIT_URL:-http://localhost:8080}/rest/v1
auth="admin:${HAWKBIT_ADMIN_PASS:?read HAWKBIT_ADMIN_PASS first}"

post() {
    curl -sf -u "$auth" -X POST -H 'Content-Type: application/json' -d "$2" "$api/$1"
}
first_id() {
    python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])'
}

module=$(post softwaremodules "[{\"name\":\"my-bundle\",\"version\":\"$version\",\"type\":\"os\"}]" | first_id)
curl -sf -u "$auth" -F "file=@$bundle" "$api/softwaremodules/$module/artifacts" |
    python3 -c 'import sys,json; a=json.load(sys.stdin); print("uploaded", a["providedFilename"], a["size"], a["hashes"]["sha256"])'
set_id=$(post distributionsets "[{\"name\":\"my-image\",\"version\":\"$version\",\"type\":\"os\",\"modules\":[{\"id\":$module}]}]" | first_id)
post "distributionsets/$set_id/assignedTargets" "[{\"id\":\"$target\",\"type\":\"forced\"}]" |
    python3 -c 'import sys,json; print("assigned to", sys.argv[1] + ":", json.load(sys.stdin)["assigned"])' "$target"
