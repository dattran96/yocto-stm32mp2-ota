#!/usr/bin/env python3
"""CI gates for the image: fail on a forbidden license or a new critical CVE.

  scripts/ci-gates.py licenses license.manifest --allow .github/license-allow.txt
  scripts/ci-gates.py cves IMAGE.rootfs.json --baseline .github/cve-baseline.txt
  scripts/ci-gates.py cves IMAGE.rootfs.json --write-baseline > .github/cve-baseline.txt

licenses: a package fails when every choice in its LICENSE needs GPLv3,
LGPLv3 or AGPLv3 ("A | B" passes if one side passes; the GCC exception
passes). Packages in the allow list pass.

cves: fails on an "Unpatched" CVE with a CVSS v3 score of at least
--min-score that is not in the baseline. The baseline is the list of
critical CVEs that were already known and accepted, so CI fails only on
new ones.

The allow list and the baseline: one entry per line, "#" starts a comment.
"""
import argparse
import json
import re
import sys

FORBIDDEN = {
    "GPL-3.0-only", "GPL-3.0-or-later",
    "LGPL-3.0-only", "LGPL-3.0-or-later",
    "AGPL-3.0-only", "AGPL-3.0-or-later",
}


def read_list(path):
    entries = set()
    with open(path) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                entries.add(line)
    return entries


def license_ok(expr):
    """True if the license expression can be met without a forbidden license."""
    tokens = re.findall(r"[()&|]|[^\s()&|]+", expr)
    pos = 0

    def parse_or():
        nonlocal pos
        ok = parse_and()
        while pos < len(tokens) and tokens[pos] == "|":
            pos += 1
            ok = parse_and() or ok
        return ok

    def parse_and():
        nonlocal pos
        ok = parse_atom()
        while pos < len(tokens) and tokens[pos] == "&":
            pos += 1
            ok = parse_atom() and ok
        return ok

    def parse_atom():
        nonlocal pos
        token = tokens[pos]
        pos += 1
        if token == "(":
            ok = parse_or()
            pos += 1  # ")"
            return ok
        return token not in FORBIDDEN

    return parse_or()


def packages(manifest):
    """(package, recipe, license) for each package in a license.manifest."""
    pkg = recipe = None
    with open(manifest) as f:
        for line in f:
            key, _, value = line.partition(":")
            value = value.strip()
            if key == "PACKAGE NAME":
                pkg = value
            elif key == "RECIPE NAME":
                recipe = value
            elif key == "LICENSE":
                yield pkg, recipe, value


def gate_licenses(args):
    allow = read_list(args.allow)
    bad = [(p, r, l) for p, r, l in packages(args.manifest)
           if p not in allow and not license_ok(l)]
    for p, r, l in bad:
        print(f"FORBIDDEN LICENSE: {p} (recipe {r}): {l}")
    if not bad:
        print("License gate: OK")
    return 1 if bad else 0


def critical(report, min_score):
    with open(report) as f:
        for pkg in json.load(f)["package"]:
            for issue in pkg.get("issue", []):
                try:
                    score = float(issue.get("scorev3") or 0)
                except ValueError:
                    score = 0.0
                if issue["status"] == "Unpatched" and score >= min_score:
                    yield issue["id"], pkg["name"], score


def gate_cves(args):
    found = sorted(set(critical(args.report, args.min_score)))
    if args.write_baseline:
        print(f"# Critical CVEs (CVSS v3 >= {args.min_score}) accepted for now")
        for cve, name, score in found:
            print(f"{cve}  # {name} {score}")
        return 0
    baseline = read_list(args.baseline)
    new = [c for c in found if c[0] not in baseline]
    for cve, name, score in new:
        print(f"NEW CRITICAL CVE: {cve} in {name} (CVSS {score})")
    print(f"CVE gate: {len(found)} critical, {len(found) - len(new)} in the baseline, "
          f"{len(new)} new")
    return 1 if new else 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="gate", required=True)
    lic = sub.add_parser("licenses")
    lic.add_argument("manifest")
    lic.add_argument("--allow", required=True)
    cve = sub.add_parser("cves")
    cve.add_argument("report")
    cve.add_argument("--min-score", type=float, default=9.0)
    group = cve.add_mutually_exclusive_group(required=True)
    group.add_argument("--baseline")
    group.add_argument("--write-baseline", action="store_true")
    args = ap.parse_args()
    return gate_licenses(args) if args.gate == "licenses" else gate_cves(args)


if __name__ == "__main__":
    sys.exit(main())
