from pathlib import Path
import hashlib, json, re

root = Path(__file__).resolve().parents[5]
base = root / 'ai/findings/Story/astra'
print(f'gate root: {root}')
files = [base / x for x in ['report.md', 'parity-matrix.md', 'evaluation-plan.md', 'critique-for-fable.md', 'evidence/second-sibling-review.md', 'evidence/sibling-synthesis.md', 'evidence/source-map.md', 'evidence/fable-mcp-receipt/README.md']]
errors, links, rows = [], 0, 0
for file in files:
    content = file.read_text(encoding='utf-8-sig')
    text = re.sub(r'^```.*?^```\s*$', '', content, flags=re.M | re.S)
    for target in re.findall(r'!?\[[^\]]+\]\(([^\)]+)\)', text):
        if target.startswith(('https://','http://')):
            continue
        path, _, anchor = target.partition('#')
        resolved = (file.parent / path).resolve() if path else file
        links += 1
        if not resolved.exists():
            errors.append(f'{file.relative_to(base)}: missing {target}')
        elif anchor and resolved.suffix == '.md':
            headings = re.findall(r'^#+\s+(.+)$', resolved.read_text(encoding='utf-8-sig'), re.M)
            slugs = [re.sub(r'[^\w\- ]','',h.lower()).replace(' ','-') for h in headings]
            if anchor not in slugs:
                errors.append(f'{file.relative_to(base)}: anchor {target}')
    width = None
    for line in text.splitlines():
        if line.startswith('|'):
            count = len(re.split(r'(?<!\\)\|',line)) - 2
            rows += 1
            if width is not None and width != count:
                errors.append(f'{file.relative_to(base)}: table width {count}, expected {width}: {line[:100]}')
            width = count
        else:
            width = None
    print(f'{file.relative_to(base)}: {len(content.split())} words')
prompt_hash = hashlib.sha256((base/'prompt.md').read_bytes()).hexdigest()
if prompt_hash != '3f03a5e29f607c84429ca9e06e4c0ad74d6c8a14e80cbe8a6b595ebc8f1fbc7c':
    errors.append('Prompt changed')
result={'root':str(root),'files':len(files),'localLinks':links,'tableRows':rows,'promptSha256':prompt_hash,'errors':errors}
(base/'evidence/artifact-validation.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
raise SystemExit(bool(errors))
