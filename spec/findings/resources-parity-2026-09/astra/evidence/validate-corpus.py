from pathlib import Path
import datetime, hashlib, json, re, subprocess, sys

root = Path(__file__).resolve().parent.parent
repo = root.parents[3]
print(f"gate root: {repo}")
files = [root / name for name in ("prompt.md", "report.md", "parity-matrix.md", "evaluation-plan.md", "scorecard-audit.md", "evidence.md", "sibling-review.md", "review-round-2.md")]
errors, documents = [], []
for path in files:
    content = path.read_text(encoding="utf-8")
    text = re.sub(r"```.*?```", "", content, flags=re.S)
    local_links = []
    for target in re.findall(r"\]\(([^)]+)\)", text):
        if re.match(r"(?:https?://|mailto:|#)", target):
            continue
        target = target.split("#", 1)[0].strip("<>")
        if not target:
            continue
        local_links.append(target)
        if not (path.parent / target).exists():
            errors.append(f"{path.name}: missing target {target}")
    expected = None
    for n, line in enumerate(text.splitlines(), 1):
        if line.startswith("|"):
            count = len(re.split(r"(?<!\\)\|", line)) - 2
            if expected is None:
                expected = count
            elif count != expected:
                errors.append(f"{path.name}:{n}: table has {count} cells; expected {expected}")
        else:
            expected = None
    documents.append({"path": path.name, "words": len(content.split()), "local_links": len(local_links), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})

baseline = json.loads((root / "evidence/baseline.json").read_text(encoding="utf-8-sig"))
prompt_hash = hashlib.sha256((root / "prompt.md").read_bytes()).hexdigest()
if prompt_hash.upper() != baseline["prompt_sha256"].upper():
    errors.append("Prompt changed during execution")
if not 2500 <= next(d["words"] for d in documents if d["path"] == "report.md") <= 4000:
    errors.append("Main report word count outside requested range")
manifest = json.loads((root / "evidence/local-source-manifest.json").read_text(encoding="utf-8"))
changed = []
for row in manifest:
    snapshot = root / "evidence/local-source" / row["path"]
    if hashlib.sha256(snapshot.read_bytes()).hexdigest() != row["sha256"]:
        errors.append(f"Pinned snapshot changed: {row['path']}")
    current = repo / row["path"]
    if not current.exists() or hashlib.sha256(current.read_bytes()).hexdigest() != row["sha256"]:
        changed.append(row["path"])
receipt = {"utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "repo": str(repo),
           "head": subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip(),
           "documents": documents, "pinned_source_files": len(manifest), "changed_since_snapshot": changed, "errors": errors}
(root / "evidence/corpus-validation.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
print(json.dumps(receipt, indent=2))
sys.exit(1 if errors else 0)
