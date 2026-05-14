#!/usr/bin/env python3
"""Check that every `SKILL-REDIRECT.md -> *Label*` reference in the skills/ tree
resolves to a real bullet label in the root SKILL-REDIRECT.md.

Anchor coupling (rf2-o2qrj). SKILL-REDIRECT.md is the single coupling point for
deep-dive routing from the AI skills. Leaf files cite its bullet labels (e.g.
`SKILL-REDIRECT.md -> *EP - Frames (002)*`). If SKILL-REDIRECT.md is renamed,
restructured, or a bullet label drifts, every leaf citation goes stale silently.

Run from the repo root:
    python3 scripts/check_skill_redirect_anchors.py

Exits non-zero if any reference points at a label that does not exist in
SKILL-REDIRECT.md. Run before any edit to SKILL-REDIRECT.md, and from CI to
catch drift introduced by leaves.
"""
from __future__ import annotations

import io
import os
import re
import sys
import pathlib


REDIRECT_FILE = pathlib.Path("SKILL-REDIRECT.md")
SKILLS_DIR = pathlib.Path("skills")

# Bullet-label syntax in SKILL-REDIRECT.md is:
#   - **Label** -> URL `[tag]` `[tag]`
# Bold may be ** or __. Allow either.
BULLET_LABEL_RE = re.compile(
    r"^\s*-\s+(?:\*\*|__)(.+?)(?:\*\*|__)\s*(?:→|->)",
    re.MULTILINE,
)

# Reference syntax in leaf files (any *.md under skills/):
#   ... SKILL-REDIRECT.md -> *Label* ...           (single emphasis)
#   ... SKILL-REDIRECT.md -> **Label** ...         (bold)
#   ... `SKILL-REDIRECT.md` -> *Label* ...         (back-ticked redirect)
# The label is the next *-delimited or **-delimited run after the arrow.
# Arrow may be the Unicode -> (U+2192) or ASCII ->.
REF_RE = re.compile(
    r"SKILL-REDIRECT\.md`?\s*(?:→|->)\s*(?:\*\*|\*|__|_)([^*_\n`]+?)(?:\*\*|\*|__|_)",
)

# Some leaf refs trail a "section" qualifier on the same label, e.g.
#   *EP - State machines (005)* §Cancellation cascade
# The label proper is what's between the *s; the §Section is informational
# and is NOT validated here (it would couple to the spec docs, not SKILL-REDIRECT).
# So we just normalise the captured label by stripping a trailing " §..." if any.
def normalise(label: str) -> str:
    return re.split(r"\s*§", label, maxsplit=1)[0].strip()


def main() -> int:
    # Force UTF-8 output so we don't choke on the en-dash / arrow on Windows.
    if hasattr(sys.stdout, "buffer"):
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)

    if not REDIRECT_FILE.exists():
        print(f"ERROR: {REDIRECT_FILE} not found. Run from repo root.", file=sys.stderr)
        return 2

    redirect_text = REDIRECT_FILE.read_text(encoding="utf-8")
    canonical = {m.group(1).strip() for m in BULLET_LABEL_RE.finditer(redirect_text)}

    if not canonical:
        print(f"ERROR: no bullet labels parsed out of {REDIRECT_FILE}.", file=sys.stderr)
        return 2

    refs = []  # (path, lineno, raw_label, normalised_label)
    for path in sorted(SKILLS_DIR.rglob("*.md")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            for m in REF_RE.finditer(line):
                raw = m.group(1).strip()
                refs.append((path, lineno, raw, normalise(raw)))

    broken = [(p, ln, raw, norm) for (p, ln, raw, norm) in refs if norm not in canonical]
    ok = len(refs) - len(broken)

    # Silent-on-success (rf2-try1x): the audit summary prints only when
    # there are broken refs. On green the exit code is the success
    # signal and the script emits no stdout.
    if broken:
        print(f"SKILL-REDIRECT.md anchor coupling audit")
        print(f"  canonical labels in SKILL-REDIRECT.md: {len(canonical)}")
        print(f"  leaf references inspected:             {len(refs)}")
        print(f"  references OK:                         {ok}")
        print(f"  references BROKEN:                     {len(broken)}")
        print()
        print("Broken references (label does not appear as a bullet in SKILL-REDIRECT.md):")
        for path, ln, raw, norm in broken:
            rel = path.as_posix()
            print(f"  {rel}:{ln}  label={raw!r}  base={norm!r}")
        print()
        print("Fix options:")
        print("  - Update SKILL-REDIRECT.md so the bullet label exactly matches the leaf ref.")
        print("  - Update the leaf ref so it exactly matches a SKILL-REDIRECT.md bullet label.")
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
