#!/usr/bin/env python3
"""Install the real APK and run instrumented offline inference/native-world checks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--fixture-checkout", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    def adb(*parts, **kwargs):
        return subprocess.run([args.adb, *parts], check=True, **kwargs)
    deadline = time.monotonic() + 900
    while time.monotonic() < deadline:
        boot = subprocess.run([args.adb, "shell", "getprop", "sys.boot_completed"], capture_output=True, text=True)
        if boot.stdout.strip() == "1":
            break
        time.sleep(5)
    else:
        raise RuntimeError("Emulator did not complete boot within 15 minutes")
    print("Android package manager is reachable; starting functional checks", flush=True)
    rev = subprocess.check_output(["git", "-C", str(args.fixture_checkout), "rev-parse", "HEAD"], text=True).strip()
    assert rev == "fad173683012f95cc892afd16dc2a95ee7c4697f"
    fixture = args.fixture_checkout / "tests/data/worlds_src/bedrock/vanilla/1_18/vanilla"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    archive = args.output.parent / "device-test-fixture.mcworld"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zip:
        for file in fixture.rglob("*"):
            if file.is_file():
                zip.write(file, file.relative_to(fixture).as_posix())
    print("Installing app and instrumentation APKs", flush=True)
    adb("install", "-r", str(args.apk))
    adb("install", "-r", str(args.test_apk))
    adb("shell", "am", "start", "-W", "-n", "cn.mcworld.translator/.MainActivity")
    adb("shell", "run-as", "cn.mcworld.translator", "mkdir", "-p", "files")
    with archive.open("rb") as input:
        adb("shell", "-T", "run-as cn.mcworld.translator sh -c 'cat > files/device-test-fixture.mcworld'", stdin=input)
    archive.unlink()
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    adb("logcat", "-c")
    log = args.output.with_suffix(".instrumentation.txt")
    print("Running actual Android tests with Wi-Fi/data disabled", flush=True)
    with log.open("wb") as output:
        result = subprocess.run([args.adb, "shell", "am", "instrument", "-w", "-r",
            "cn.mcworld.translator.test/androidx.test.runner.AndroidJUnitRunner"], stdout=output, stderr=subprocess.STDOUT, timeout=1800)
    text = log.read_text(errors="replace")
    lines = subprocess.check_output([args.adb, "logcat", "-d", "-s", "MCWT_TEST:I", "AndroidRuntime:E", "*:S"], text=True)
    args.output.with_suffix(".logcat.txt").write_text(lines)
    success = result.returncode == 0 and bool(re.search(r"OK \(5 tests\)", text)) and "FAILURES!!!" not in text
    info = {"android_release": subprocess.check_output([args.adb, "shell", "getprop", "ro.build.version.release"], text=True).strip(),
        "sdk": subprocess.check_output([args.adb, "shell", "getprop", "ro.build.version.sdk"], text=True).strip(),
        "abi": subprocess.check_output([args.adb, "shell", "getprop", "ro.product.cpu.abi"], text=True).strip(),
        "wifi_disabled": True, "mobile_data_disabled": True, "passed": success,
        "instrumentation_log": log.name, "minecraft_import_tested": False,
        "fixture_commit": rev, "app_apk_bytes": args.apk.stat().st_size,
        "app_apk_sha256": hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest()}
    args.output.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
    print(text, flush=True)
    print(json.dumps(info, ensure_ascii=False, indent=2), flush=True)
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
