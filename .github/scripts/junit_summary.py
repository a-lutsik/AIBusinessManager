#!/usr/bin/env python3
"""Summarise Gradle JUnit XML results of the backend modules and guard against silently skipped tests.

Usage: junit_summary.py <backend-dir> [--fail-on-skipped] [--min-tests N] [--require-class REGEX]

Writes a Markdown table to $GITHUB_STEP_SUMMARY (or stdout when unset) and emits `::error` annotations.

Exit status:
  0  report written, no gate violated (without any option this is always the case: Gradle decides pass/fail)
  1  a gate failed:
       --fail-on-skipped     at least one test case was skipped (a runner without Docker would skip every
                             Testcontainers IT and still leave the Gradle build green)
       --min-tests N         fewer than N test cases were executed in total (a module that stopped running its tests)
       --require-class REGEX no executed test case has a class name matching REGEX (e.g. 'IT$': no IT ran at all)
       any failed test case or unparsable result file is also a failure once any gate option is given
       (no result files at all also fails when a gate option is given)
"""
import argparse
import glob
import re
import os
import sys
import xml.etree.ElementTree as ET

MAX_FAILURES_LISTED = 30


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("root", nargs="?", default="backend", help="backend directory (default: backend)")
    parser.add_argument("--fail-on-skipped", action="store_true", help="exit 1 when any test case was skipped")
    parser.add_argument("--min-tests", type=int, default=0, metavar="N", help="exit 1 when fewer than N test cases ran")
    parser.add_argument("--require-class", action="append", default=[], metavar="REGEX",
                        help="exit 1 unless some test case has a class name matching REGEX (repeatable)")
    args = parser.parse_args()
    root = args.root
    gated = args.fail_on_skipped or args.min_tests > 0 or bool(args.require_class)
    class_patterns = {rx: re.compile(rx) for rx in args.require_class}
    class_hits = dict.fromkeys(class_patterns, 0)
    skipped_cases = []
    pattern = os.path.join(root, "modules", "*", "build", "test-results", "*", "TEST-*.xml")
    files = sorted(glob.glob(pattern))

    per_module = {}
    failures = []
    for path in files:
        module = os.path.relpath(path, os.path.join(root, "modules")).split(os.sep)[0]
        stats = per_module.setdefault(module, {"tests": 0, "failed": 0, "skipped": 0, "time": 0.0})
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            failures.append((module, os.path.basename(path), "unparsable result file"))
            stats["failed"] += 1
            continue
        for case in suite.iter("testcase"):
            stats["tests"] += 1
            stats["time"] += float(case.get("time") or 0)
            for rx, pattern in class_patterns.items():
                if pattern.search(case.get("classname") or ""):
                    class_hits[rx] += 1
            if case.find("skipped") is not None:
                stats["skipped"] += 1
                skipped_cases.append((module, f"{case.get('classname')}.{case.get('name')}"))
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is not None:
                stats["failed"] += 1
                message = (bad.get("message") or "").strip().splitlines()
                failures.append((module, f"{case.get('classname')}.{case.get('name')}", message[0] if message else ""))

    total_tests = sum(m["tests"] for m in per_module.values())
    total_skipped = sum(m["skipped"] for m in per_module.values())
    violations = []
    if gated:
        if not files:
            violations.append("no JUnit result files found")
        if failures:
            violations.append(f"{len(failures)} failed test case(s) or unreadable result file(s)")
        if args.fail_on_skipped and total_skipped:
            violations.append(f"{total_skipped} test case(s) were skipped (Docker unreachable on the runner?)")
        if args.min_tests and total_tests < args.min_tests:
            violations.append(f"only {total_tests} test case(s) ran, expected at least {args.min_tests}")
        for rx, hits in class_hits.items():
            if hits == 0:
                violations.append(f"no executed test case has a class name matching /{rx}/")

    lines = ["## Backend test results", ""]
    if not files:
        lines.append("No JUnit result files found (the build failed before the tests ran, or no tests were selected).")
    else:
        lines += ["| Module | Tests | Failed | Skipped | Time (s) |", "|---|---:|---:|---:|---:|"]
        total = {"tests": 0, "failed": 0, "skipped": 0, "time": 0.0}
        for module, stats in sorted(per_module.items()):
            lines.append(f"| {module} | {stats['tests']} | {stats['failed']} | {stats['skipped']} | {stats['time']:.1f} |")
            for key in total:
                total[key] += stats[key]
        lines.append(f"| **Total** | **{total['tests']}** | **{total['failed']}** | **{total['skipped']}** | **{total['time']:.1f}** |")
        if failures:
            lines += ["", f"### Failed tests ({len(failures)})", ""]
            for module, name, message in failures[:MAX_FAILURES_LISTED]:
                lines.append(f"- `{module}` `{name}`" + (f": {message}" if message else ""))
            if len(failures) > MAX_FAILURES_LISTED:
                lines.append(f"- ... and {len(failures) - MAX_FAILURES_LISTED} more")

    if violations:
        lines += ["", "### Test gate FAILED", ""] + [f"- {v}" for v in violations]
        if skipped_cases:
            lines += ["", f"Skipped ({len(skipped_cases)}):"]
            lines += [f"- `{m}` `{n}`" for m, n in skipped_cases[:MAX_FAILURES_LISTED]]
            if len(skipped_cases) > MAX_FAILURES_LISTED:
                lines.append(f"- ... and {len(skipped_cases) - MAX_FAILURES_LISTED} more")

    text = "\n".join(lines) + "\n"
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as out:
            out.write(text)
    else:
        sys.stdout.write(text)

    for module, name, _ in failures[:MAX_FAILURES_LISTED]:
        safe = f"{module} {name}".replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Test failed::{safe}")
    for violation in violations:
        print(f"::error title=Test gate::{violation}")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
