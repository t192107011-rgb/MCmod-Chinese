#!/usr/bin/env python3
"""Download pinned public OPUS checkpoints and verify Hub Git/LFS digests."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "app/src/main/assets/models"
PINS = {
    "en-zh": ("046f55aec303cdee3e0318604406d4df20f1e8ea", "408d9bc410a388e1d9aef112a2daba955b945255"),
    "ru-en": ("afe8c6c738ec81b6d033fd8f44f9678a639a7c67", "fbd6dc73284f95536648512cc21d57f19191961a"),
    "de-en": ("399dfd68706739fffd503f876093e455ae268a06", "1a922f3b32a8e809e17a47d4b32142d8105924e5"),
    "fr-en": ("6b166a182780e118c997879d0ad5be4b53671644", "c4aed37b318c763fd177aa449b44e3b783cc6c02"),
    "es-en": ("eadfd7c658a9d8929ac3b8e996b68a68e2c7d480", "c96e2c5399ebfae4fc43d9669556b9afa74bb69d"),
}
FILES = ["onnx/encoder_model_quantized.onnx", "onnx/decoder_model_quantized.onnx",
         "source.spm", "target.spm", "vocab.json", "config.json", "generation_config.json", "tokenizer_config.json", "README.md"]


def fetch_model(pair):
    converted, original = PINS[pair]
    name = "Xenova/opus-mt-" + pair
    source_name = "Helsinki-NLP/opus-mt-" + pair
    metadata = json.load(urllib.request.urlopen(f"https://huggingface.co/api/models/{name}/revision/{converted}?blobs=true", timeout=30))
    source_meta = json.load(urllib.request.urlopen(f"https://huggingface.co/api/models/{source_name}/revision/{original}", timeout=30))
    assert metadata["sha"] == converted and source_meta["sha"] == original
    license_id = source_meta.get("cardData", {}).get("license")
    assert license_id in ("apache-2.0", "cc-by-4.0"), f"Unreviewed model license: {pair}"
    siblings = {f["rfilename"]: f for f in metadata["siblings"]}
    dest = DESTINATION / pair
    dest.mkdir(parents=True, exist_ok=True)
    records = []
    for name_in_repo in FILES:
        info = siblings[name_in_repo]
        target = dest / name_in_repo
        target.parent.mkdir(parents=True, exist_ok=True)
        expected_size = info["size"]
        def verified(path):
            if not path.exists() or path.stat().st_size != expected_size:
                return False
            data = path.read_bytes()
            if "lfs" in info:
                return hashlib.sha256(data).hexdigest() == info["lfs"]["sha256"]
            return hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest() == info["blobId"]
        if not verified(target):
            part = target.with_suffix(target.suffix + ".part")
            with urllib.request.urlopen(f"https://huggingface.co/{name}/resolve/{converted}/{name_in_repo}", timeout=60) as response, part.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            assert verified(part), f"Checksum failure: {pair}/{name_in_repo}"
            part.replace(target)
        records.append({"file": name_in_repo, "bytes": expected_size,
                        "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                        "upstream_digest": info.get("lfs", {}).get("sha256") or info["blobId"]})
    card = urllib.request.urlopen(f"https://huggingface.co/{source_name}/resolve/{original}/README.md", timeout=30).read()
    (dest / "ORIGINAL_MODEL_CARD.md").write_bytes(card)
    print(pair, "downloaded and verified", sum(r["bytes"] for r in records), "bytes", flush=True)
    return {"pair": pair, "license": license_id, "original_repository": source_name,
            "original_revision": original, "converted_repository": name,
            "converted_revision": converted, "files": records}


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=3) as pool:
        models = list(pool.map(fetch_model, PINS))
    (ROOT / "docs/model-manifest.json").write_text(json.dumps(models, ensure_ascii=False, indent=2) + "\n")
    (ROOT / "app/src/main/assets/model-manifest.json").write_text(json.dumps(models, ensure_ascii=False, indent=2) + "\n")
