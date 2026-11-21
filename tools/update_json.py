"""Writes update.json for the in-app updater: the newest version and what changed in each version.

usage: update_json.py <pages/update.json> <changelog.json> <run number>
changelog.json (in the repo) lists release notes, newest first: [{"id": "...", "notes": {"lt": [...], "en": [...], "uk": [...], "ru": [...]}}]
A changelog entry is attached to the first build that sees it; later builds without a new entry keep the history as is.
"""
import json, os, sys

path, changelog_path, run = sys.argv[1], sys.argv[2], int(sys.argv[3])
name = f"2.0.{run}"
data = {"latest": {}, "history": []}
if os.path.exists(path):
    try:
        data = json.load(open(path, encoding="utf-8"))
    except Exception:
        pass
history = data.get("history", [])
known = {h.get("id") for h in history}
entries = json.load(open(changelog_path, encoding="utf-8"))
if entries and entries[0]["id"] not in known:
    history.insert(0, {"id": entries[0]["id"], "code": run, "name": name, "notes": entries[0]["notes"]})
data["latest"] = {"code": run, "name": name, "apk": f"https://strikkz.github.io/eteris/banga.apk?v={run}"}
data["history"] = history[:40]
json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("update.json:", data["latest"], "history", len(history))
