#!/usr/bin/env python3
"""Forward the platform HTTPS proxy to Java without printing credentials."""
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlparse

root = Path(__file__).resolve().parents[1]
options = []
proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
if proxy:
    parsed = urlparse(proxy)
    if parsed.username or parsed.password:
        raise SystemExit("Authenticated proxy requires secure Java proxy configuration")
    for scheme in ("http", "https"):
        options += [f"-D{scheme}.proxyHost={parsed.hostname}", f"-D{scheme}.proxyPort={parsed.port or 80}"]
env = os.environ.copy()
env.setdefault("GRADLE_USER_HOME", str(root.parent / "tooling" / "gradle-cache"))
env.setdefault("ANDROID_USER_HOME", str(root.parent / "tooling" / "android-user"))
Path(env["ANDROID_USER_HOME"]).mkdir(parents=True, exist_ok=True)
raise SystemExit(subprocess.call([str(root / "gradlew"), *options, *sys.argv[1:]], cwd=root, env=env))
