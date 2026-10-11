#!/usr/bin/env python3
"""Probe official public model endpoints; no credentials or inference claims."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import urllib.error
import urllib.request

URLS = [
    ("OPUS-MT eng→cmn_Hans official checkpoint",
     "https://object.pouta.csc.fi/Tatoeba-MT-models/eng-zho/opus-2021-02-23.zip", "HEAD"),
    ("NLLB-200 distilled 600M metadata",
     "https://huggingface.co/api/models/facebook/nllb-200-distilled-600M", "GET"),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = []
    for name, url, method in URLS:
        result = {"name": name, "url": url, "method": method}
        try:
            with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=30) as response:
                result.update(status="reachable", http_status=response.status,
                              content_length=response.headers.get("Content-Length"))
                if method == "GET":
                    metadata = json.load(response)
                    result["revision"] = metadata.get("sha")
                    result["license_metadata"] = metadata.get("cardData", {}).get("license")
        except (urllib.error.URLError, OSError) as failure:
            result.update(status="blocked", error=str(failure))
        results.append(result)
    report = {"checked_at_utc": datetime.now(timezone.utc).isoformat(), "endpoints": results,
              "model_downloaded": False, "tokenizer_executed": False,
              "android_inference_executed": False, "translation_executed": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 1 if any(r["status"] == "blocked" for r in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
