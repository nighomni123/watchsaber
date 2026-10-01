#!/usr/bin/env python3
"""Extract the markdown payload from each monid /web/scrape/markdown JSON dump."""
import glob
import json
import os

here = os.path.dirname(os.path.abspath(__file__))
os.chdir(here)

for path in sorted(glob.glob("*.json")):
    try:
        data = json.load(open(path))
    except Exception as exc:  # noqa: BLE001
        print(f"{path}: UNPARSEABLE {exc}")
        continue
    if "error" in data:
        print(f"{path}: ERROR {data['error']}")
        continue
    out = data.get("output", {})
    if "markdown" in out:
        md = out["markdown"]
        target = path[:-5] + ".md"
        open(target, "w").write(md)
        print(f"{path}: OK {len(md)} chars -> {target}")
    else:
        print(f"{path}: NO-MARKDOWN keys={list(out)[:6]}")