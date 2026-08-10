#!/usr/bin/env python3
"""Fail on JVM test failures or unexpected skips and print one core-test summary."""

from __future__ import annotations

import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent
RESULT_ROOT = ROOT / "app" / "build" / "test-results"
VARIANTS = ("testDebugUnitTest", "testReleaseUnitTest")
PICO_TEST_CLASS = "dev.foldcode.ide.PicoExampleCompileIntegrationTest"
PICO_ENVIRONMENT = (
    "PICO_TEST_SDK",
    "PICO_TEST_TOOLCHAIN",
    "PICO_TEST_CMAKE",
    "PICO_TEST_NINJA",
    "PICO_TEST_PICOTOOL",
)


def enabled(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes"}


def main() -> int:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    failures: list[str] = []
    skipped: list[str] = []

    for variant in VARIANTS:
        result_directory = RESULT_ROOT / variant
        reports = sorted(result_directory.glob("TEST-*.xml"))
        if not reports:
            raise SystemExit(f"No JVM test reports found for {variant}")
        variant_tests = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, "0"))
            variant_tests += int(suite.attrib.get("tests", "0"))
            for case in suite.findall("testcase"):
                identity = f"{variant}: {case.attrib.get('classname')}.{case.attrib.get('name')}"
                if case.find("failure") is not None or case.find("error") is not None:
                    failures.append(identity)
                if case.find("skipped") is not None:
                    skipped.append(identity)
        if variant_tests < 50:
            raise SystemExit(f"Only {variant_tests} tests ran for {variant}; expected the core suite")

    unexpected_skips = [item for item in skipped if PICO_TEST_CLASS not in item]
    if unexpected_skips:
        raise SystemExit("Unexpected skipped JVM tests:\n" + "\n".join(unexpected_skips))
    if failures or totals["failures"] or totals["errors"]:
        raise SystemExit("Failed JVM tests:\n" + "\n".join(failures))

    pico_configured = all(os.environ.get(name, "").strip() for name in PICO_ENVIRONMENT)
    if skipped and (enabled("FOLDCODE_REQUIRE_PICO_INTEGRATION") or pico_configured):
        missing = [name for name in PICO_ENVIRONMENT if not os.environ.get(name, "").strip()]
        detail = f" Missing: {', '.join(missing)}." if missing else " Check that every configured path exists."
        raise SystemExit("Pico SDK compilation was required but skipped." + detail)

    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    print(
        f"Core JVM tests passed: {passed}/{totals['tests']} "
        f"({totals['skipped']} optional Pico variant skips)."
    )
    if skipped:
        missing = [name for name in PICO_ENVIRONMENT if not os.environ.get(name, "").strip()]
        print("Pico SDK compile matrix: optional, not run" + (f"; missing {', '.join(missing)}" if missing else ""))
    else:
        print("Pico SDK compile matrix: passed for debug and release test variants.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
