#!/usr/bin/env python3
"""Real upstream Bedrock fixture validation, NOT an automatic translation test.

Requires the pinned Amulet-Core 1.7 checkout. Never edits that checkout.
The Kotlin disk-NBT parser and Kotlin ZIP exporter/importer are exercised.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import warnings
import zipfile

import amulet_nbt
from leveldb import LevelDB

REVISION = "fad173683012f95cc892afd16dc2a95ee7c4697f"
PROJECT = Path(__file__).resolve().parents[1]


def hashes(root):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob("*") if p.is_file()}


def snapshot(world):
    db = LevelDB(str(world / "db"))
    try:
        return dict(db.iterate())
    finally:
        db.close()


def gradle(task, **properties):
    args = [sys.executable, str(PROJECT / "tools/run_gradle.py"), task, "--console=plain"]
    args += [f"-P{k}={v}" for k, v in properties.items()]
    subprocess.run(args, cwd=PROJECT, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-checkout", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    revision = subprocess.check_output(["git", "-C", str(args.fixture_checkout), "rev-parse", "HEAD"], text=True).strip()
    if revision != REVISION:
        raise RuntimeError(f"Fixture must be pinned to {REVISION}")
    source = args.fixture_checkout / "tests/data/worlds_src/bedrock/vanilla/1_18/vanilla"
    original = hashes(source)
    args.work.mkdir(parents=True, exist_ok=False)
    world = args.work / "world"
    shutil.copytree(source, world)
    before = snapshot(world)
    selected = None
    failures = []
    # Bedrock chunk BlockEntity records are chunk coordinates plus byte tag 0x31.
    for key, value in before.items():
        if len(key) not in (9, 13) or key[-1] != 0x31:
            continue
        offset = 0
        root = 0
        try:
            while offset < len(value):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", FutureWarning)
                    tag, consumed = amulet_nbt.load(value[offset:], compressed=False, little_endian=True, offset=True)
                if consumed <= 0:
                    raise RuntimeError("NBT parser made no progress")
                if str(tag.compound.get("id", "")) == "Sign" and "Text" in tag.compound:
                    selected = (key, value, root, tag.compound["Text"].py_str)
                offset += consumed
                root += 1
        except Exception as failure:
            failures.append({"key": key.hex(), "reason": str(failure)})
    if selected is None:
        raise RuntimeError("Real fixture contains no supported legacy Sign text")
    key, raw, root, original_text = selected
    if original_text != "<----\n§r---->\n§r":
        raise RuntimeError("Unexpected pinned fixture sign content")
    replacement = "向左\n§r向右\n§r"
    input_file = args.work / "record-before.nbt"
    output_file = args.work / "record-after.nbt"
    input_file.write_bytes(raw)
    gradle(":core:rewriteNbt", inputFile=input_file, outputFile=output_file,
           nbtPath=f"{root}/Text", replacement=replacement)
    edited = output_file.read_bytes()
    db = LevelDB(str(world / "db"))
    try:
        db.put(key, edited)
    finally:
        db.close()
    after = snapshot(world)
    assert after.keys() == before.keys()
    assert after[key] == edited
    assert all(value == after[k] for k, value in before.items() if k != key)
    # Independently inspect the Kotlin-edited record with Amulet's NBT parser.
    offset = 0
    for index in range(root + 1):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", FutureWarning)
            tag, consumed = amulet_nbt.load(edited[offset:], compressed=False, little_endian=True, offset=True)
        offset += consumed
    assert tag.compound["Text"].py_str == replacement
    archive = args.work / "validated-manual-edit.mcworld"
    gradle(":core:archiveProbe", archiveAction="export", inputFile=world, outputFile=archive)
    restored_dir = args.work / "restored"
    gradle(":core:archiveProbe", archiveAction="extract", inputFile=archive, outputFile=restored_dir)
    assert snapshot(restored_dir) == after
    assert {k: v for k, v in hashes(world).items() if not k.startswith("db/")} == {
        k: v for k, v in hashes(restored_dir).items() if not k.startswith("db/")}
    # A second real-world archive exercises a Unicode outer directory.
    wrapped = args.work / "wrapped.zip"
    with zipfile.ZipFile(archive) as src, zipfile.ZipFile(wrapped, "w", zipfile.ZIP_DEFLATED) as dest:
        for item in src.infolist():
            with src.open(item) as inp, dest.open("地图外层/" + item.filename, "w") as out:
                shutil.copyfileobj(inp, out, 64 * 1024)
    wrapped_dir = args.work / "wrapped-restored"
    gradle(":core:archiveProbe", archiveAction="extract", inputFile=wrapped, outputFile=wrapped_dir)
    assert snapshot(wrapped_dir / "地图外层") == after
    assert hashes(source) == original, "Original fixture changed"
    report = {
        "fixture_repository": "https://github.com/Amulet-Team/Amulet-Core",
        "fixture_commit": REVISION,
        "world": "Bedrock vanilla 1.18",
        "database_library": "amulet-leveldb 1.0.7 (host Python binding, not Android JNI)",
        "records_verified": len(before),
        "edited_key": key.hex(),
        "manual_edit": {"original": original_text, "replacement": replacement},
        "unparseable_block_entity_records": failures,
        "unchanged_other_records": True,
        "database_closed_and_reopened": True,
        "kotlin_nbt_reparse": True,
        "export_and_reimport_database_equal": True,
        "unicode_outer_folder_import": True,
        "source_unchanged": True,
        "model_inference_tested": False,
        "minecraft_application_import_tested": False,
    }
    (args.work / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
