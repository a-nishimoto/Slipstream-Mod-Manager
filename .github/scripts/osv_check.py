#!/usr/bin/env python3
"""Check this project's resolved Maven dependencies against the OSV database.

Deliberately not a third-party action. It queries api.osv.dev directly, which
keeps the check auditable, runnable on a laptop, and free of a supply-chain
dependency of its own -- a scanner you cannot inspect is a strange thing to
trust with supply-chain questions.

It scans the RESOLVED tree, not the declarations in pom.xml, so transitive
dependencies are covered. That matters: jackson-core reached this project only
transitively and carried its own advisory.

Exit status: 0 clean, 1 vulnerabilities found, 2 the check itself failed.
"""

import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request

OSV_API = "https://api.osv.dev/v1/query"

# "[INFO]    group:artifact:jar:version:scope" (optionally with a classifier)
DEP_LINE = re.compile(
    r"^\[INFO\]\s+([\w.\-]+):([\w.\-]+):(?:[\w.\-]+:)?(?:[\w.\-]+:)?([\w.\-]+):(\w+)"
)


def maven_command():
    """Prefer the pinned wrapper, so this needs no system Maven."""
    root = pathlib.Path(__file__).resolve().parents[2]
    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if wrapper.exists():
        return [str(wrapper)]
    return ["mvn"]


def resolved_dependencies():
    """Ask Maven what actually ends up on the classpath."""
    proc = subprocess.run(
        maven_command() + ["-B", "--no-transfer-progress", "dependency:list"],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout[-4000:] + proc.stderr[-2000:])
        raise SystemExit("mvn dependency:list failed")

    deps = {}
    for line in proc.stdout.splitlines():
        m = DEP_LINE.match(line)
        if not m:
            continue
        group, artifact, version, scope = m.groups()
        # Test-only libraries do not ship to users.
        if scope == "test":
            continue
        deps[(f"{group}:{artifact}", version)] = scope
    return deps


def query_osv(name, version):
    body = json.dumps(
        {"package": {"ecosystem": "Maven", "name": name}, "version": version}
    ).encode()
    req = urllib.request.Request(
        OSV_API, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp).get("vulns", []) or []


def describe(vuln):
    ids = [vuln.get("id", "")] + list(vuln.get("aliases", []))
    cve = next((i for i in ids if i.startswith("CVE-")), vuln.get("id", "?"))
    severity = (vuln.get("database_specific") or {}).get("severity", "?")
    fixed = sorted(
        {
            event["fixed"]
            for affected in vuln.get("affected", [])
            for rng in affected.get("ranges", [])
            for event in rng.get("events", [])
            if "fixed" in event
        }
    )
    return cve, severity, fixed, (vuln.get("summary") or "").strip()


def main():
    deps = resolved_dependencies()
    if not deps:
        raise SystemExit("no dependencies parsed -- the mvn output format may have changed")

    print(f"Checking {len(deps)} resolved runtime dependencies against OSV\n")

    findings = 0
    for (name, version), scope in sorted(deps.items()):
        try:
            vulns = query_osv(name, version)
        except (urllib.error.URLError, TimeoutError) as e:
            # A network blip must not look like a clean bill of health.
            print(f"  ERROR  {name}:{version} -- OSV unreachable: {e}")
            raise SystemExit(2)

        if not vulns:
            print(f"  ok     {name}:{version}")
            continue

        findings += len(vulns)
        print(f"  VULN   {name}:{version} ({scope}) -- {len(vulns)} advisories")
        for vuln in vulns:
            cve, severity, fixed, summary = describe(vuln)
            fixed_in = ", ".join(fixed) if fixed else "no fix listed"
            print(f"           {cve}  severity={severity}  fixed in: {fixed_in}")
            if summary:
                print(f"             {summary[:110]}")

    print()
    if findings:
        print(f"FAILED: {findings} advisories across the dependency tree.")
        return 1
    print("Clean: no known vulnerabilities in the resolved dependency tree.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
