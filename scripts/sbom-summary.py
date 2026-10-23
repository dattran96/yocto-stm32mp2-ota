#!/usr/bin/env python3
"""Turn the image SBOM into one table.

Yocto writes the SBOM as SPDX 2.2: one JSON file per recipe and package,
packed in IMAGE.spdx.tar.zst. This prints one line per recipe in the image
with version, declared license and CPE (the ID used to look up CVEs).

  scripts/sbom-summary.py IMAGE.spdx.tar.zst > sbom.csv
  scripts/sbom-summary.py --licenses IMAGE.spdx.tar.zst   count per license

Needs only Python and tar with zstd.
"""
import collections
import csv
import json
import pathlib
import re
import subprocess
import sys
import tempfile


def recipes(archive):
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["tar", "--zstd", "-xf", archive, "-C", tmp], check=True)
        for f in sorted(pathlib.Path(tmp).glob("recipe-*.spdx.json")):
            pkg = json.loads(f.read_text())["packages"][0]
            # Build tools run on the build PC and are not in the image
            if re.search(r"-native$|-cross-", pkg["name"]):
                continue
            # "DocumentRef-recipe-x:LicenseRef-y" points to a license text in
            # another SPDX file; only the name is kept
            lic = re.sub(r"DocumentRef-[^:]*:", "", pkg.get("licenseDeclared", ""))
            cpe = next((r["referenceLocator"] for r in pkg.get("externalRefs", [])
                        if r.get("referenceType", "").endswith("cpe23Type")), "")
            yield pkg["name"], pkg.get("versionInfo", ""), lic, cpe


def main():
    args = sys.argv[1:]
    licenses = args[:1] == ["--licenses"]
    if licenses:
        args = args[1:]
    if len(args) != 1:
        sys.exit(f"usage: {sys.argv[0]} [--licenses] IMAGE.spdx.tar.zst")
    rows = list(recipes(args[0]))
    if licenses:
        for lic, n in collections.Counter(r[2] for r in rows).most_common():
            print(f"{n:4} {lic}")
    else:
        out = csv.writer(sys.stdout)
        out.writerow(["recipe", "version", "license", "cpe"])
        out.writerows(rows)


if __name__ == "__main__":
    main()
