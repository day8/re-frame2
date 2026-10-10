#!/usr/bin/env python3
"""In-package link-resolution gate for packaged skills.

re-frame2's published skills (the ones carrying a `package.json` +
`.claude-plugin/plugin.json`) each ship a self-contained package under a
TWO-PROMISE package boundary:

    1. Anything a skill's normal operation must READ ships inside the package
       (its `package.json` `files` allow-list) and is linked package-relative,
       or is read through the verified PINNED LOCAL CHECKOUT that skill's own
       leaves name — re-frame-migration's references/setup.md "Pin the
       migration corpus before reading it", re-frame2-implementor's cardinal
       rule 1. Neither of those routes is the monorepo the package happens to
       sit in, and neither is the network.
    2. Everything a shipped doc merely CITES outside its package is spelled as
       an absolute repository URL — `https://github.com/day8/re-frame2/blob/
       main/<path>[#anchor]` for a file, `tree/main/<path>` for a directory.

(There is no shared `skills/shared/` protocol layer: each consumer owns its
instructions locally.)

The two invariants this gate enforces:

    a. In-package link resolution. For every packaged skill,
       every INTRA-package relative link from a shipped doc MUST resolve to a
       file the `files` allow-list ships.

    b. No escape. NO relative link in a shipped doc may resolve
       OUTSIDE its package at all.

"Shipped doc" is DERIVED from that same allow-list rather than from a roster of
directory names. The docs a packaged install can resolve links inside
are exactly the markdown files the tarball contains, so `patterns/`,
`decision-trees/` and a top-level `examples-map.md` are scanned whenever
`files` ships them — there is no second list to keep in step, and a second
list would fall behind the allow-list. A doc `files`
omits is not in the tarball at all, so its links cannot break a packaged install
and it is correctly out of scope.

"Intra-package" is decided by where a link RESOLVES, not by how it is spelled.
A `../` from a nested doc usually lands back inside the package —
`references/README.md` -> `../spec/design.md` and `references/x.md` ->
`../SKILL.md` both do — so treating the literal `../` prefix as "escapes the
package" would leave those links unexamined. Resolution also decides which
invariant a link answers to: re-entry is invariant (a)'s allow-list question, a
genuine escape is invariant (b), and neither can be read off the spelling.

The defect invariant (a) prevents: a shipped doc links to a sibling support doc
(`docs/LOCAL_DEV.md`, `STATUS.md`, `RELEASING.md`, ...) that the `files`
allow-list omits, so a packaged install resolves the link to a missing file at
exactly the point a user is configuring the skill.

The defect invariant (b) prevents: a link that genuinely RESOLVES
outside the package is correct for a reader standing in the monorepo and dead
for everyone else — each skill publishes as its OWN package with its own name
and `repository.directory`, so an installed one has no sibling skill beside it
and no `spec/` above it. `check_doc_slugs.py` passes such a link because its
target does exist in the repo, so without invariant (b) escaping links would
accumulate in the seam between the two gates with the tree green throughout.
They take the absolute-URL spelling instead, and `check_doc_slugs.py` unwraps
this repo's own `blob/main` / `tree/main` URLs so that spelling keeps the
rename-safety a relative link has.

npm always ships `package.json`, the README, and LICENSE/LICENCE regardless of
`files`.

This gate is NOT an existence checker, and must not become one: a link to a
path that matches the `files` allow-list but does not exist on disk passes here
by design, and `check_doc_slugs.py` owns that question. What is checked is
allow-list membership — a packaging question.

Exit code:
    0  no findings
    1  at least one finding
    2  invocation / setup error

Usage:
    python scripts/check_skill_package_refs.py
    python scripts/check_skill_package_refs.py --verbose
    python scripts/check_skill_package_refs.py --ci          # terse; CI-shaped
    python scripts/check_skill_package_refs.py --self-test    # built-in fixtures
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
SKILLS_ROOT = REPO_ROOT / "skills"

# Force UTF-8 on output streams — the corpus carries → / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-reconfigurable stream
        pass


def _iter_packaged_skills(skills_root: Path) -> Iterable[Path]:
    """Yield each skill dir that carries a package.json (i.e. is distributable)."""
    if not skills_root.is_dir():
        return
    for child in sorted(skills_root.iterdir()):
        if not child.is_dir():
            continue
        if (child / "package.json").is_file():
            yield child


# A markdown link target: the `(...)` part of `[label](target)`. We strip an
# optional `#anchor` and any surrounding angle-brackets later.
_MD_LINK_RE = re.compile(r"\]\(\s*<?([^)>\s]+)>?\s*\)")

# The spelling a shipped doc must use to cite anything outside its package.
# Deliberate TWINS of `GH_BLOB_BASE` in mkdocs_hooks.py, which
# rewrites out-of-context references to GitHub URLs for the same reason; the
# hook is a MkDocs plugin and is not imported here. If the repository moves,
# these change with it.
_GH_BLOB_BASE = "https://github.com/day8/re-frame2/blob/main"
_GH_TREE_BASE = "https://github.com/day8/re-frame2/tree/main"


def _repo_display(resolved: Path) -> str:
    """`resolved` as a repo-relative path when it is in the repo, else absolute.

    Only used inside a finding message, so an out-of-repo path (a self-test
    tempdir) degrades to something readable rather than raising.
    """
    try:
        return resolved.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return resolved.as_posix()


# Inline markers (case-insensitive) that flag a link as a DELIBERATE
# monorepo-only / repo-maintenance reference — a file the skill intentionally
# omits from its published package and tells the reader to reach from a clone.
# When the linking line carries one of these, an unshipped target is expected,
# not a defect.
_MONOREPO_ONLY_MARKERS = (
    "not in the published package",
    "deliberately not in the published",
    "repo-maintenance artifact",
    "repo-maintenance artefact",
    "monorepo clone",
    "from a clone",
    "not shipped in the package",
)


def _load_files_allowlist(skill_dir: Path) -> list[str] | None:
    """Return the package.json `files` array (normalized, forward-slash), or None."""
    pkg = skill_dir / "package.json"
    if not pkg.is_file():
        return None
    try:
        data = json.loads(pkg.read_text(encoding="utf-8", errors="replace"))
    except (json.JSONDecodeError, OSError):
        return None
    files = data.get("files")
    if not isinstance(files, list):
        return None
    return [str(f).strip().lstrip("./").rstrip("/") for f in files if isinstance(f, str)]


def _is_shipped(rel_target: str, allow: list[str]) -> bool:
    """True if `rel_target` (package-root-relative, forward-slash) is shipped.

    npm always ships package.json + README + LICENSE/LICENCE regardless of
    `files`; otherwise a target is shipped if it equals an allow-list file entry
    or sits under an allow-list directory entry.
    """
    norm = rel_target.lstrip("./")
    low = norm.lower()
    # npm-always-shipped specials (top-level only).
    if low in ("package.json", "readme.md") or low.startswith(("license", "licence")):
        return True
    for entry in allow:
        if not entry:
            continue
        if norm == entry:
            return True
        # Directory entry (e.g. "docs/") covers everything beneath it.
        if norm.startswith(entry + "/"):
            return True
    return False


def _shipped_docs_with_links(skill_dir: Path, allow: list[str]) -> Iterable[Path]:
    """Yield shipped markdown docs whose intra-package links we validate.

    Every markdown file the `files` allow-list ships — decided by the same
    [[_is_shipped]] predicate the link TARGETS are checked against, so the two
    halves of the invariant cannot drift apart.

    A hardcoded SKILL.md / README.md / references/**.md roster would fall
    behind: `skills/re-frame2` ships `patterns/`, `decision-trees/` and
    `examples-map.md`, and `skills/re-frame2-pair` ships `docs/` and
    `STATUS.md`, none of which such a roster names — so a link from any of
    them to an unshipped path would be invisible here.
    """
    for md in sorted(skill_dir.rglob("*.md")):
        if _is_shipped(md.relative_to(skill_dir).as_posix(), allow):
            yield md


def _broken_package_links(skill_dir: Path) -> list[str]:
    """Return human-readable findings for intra-package links to unshipped files."""
    allow = _load_files_allowlist(skill_dir)
    if allow is None:
        return []
    findings: list[str] = []
    for doc in _shipped_docs_with_links(skill_dir, allow):
        rel_doc = doc.relative_to(skill_dir)
        text = doc.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            line_low = line.lower()
            monorepo_only = any(mk in line_low for mk in _MONOREPO_ONLY_MARKERS)
            for m in _MD_LINK_RE.finditer(line):
                raw = m.group(1)
                # Skip external links, mailto, and pure-anchor links.
                if raw.startswith(("http://", "https://", "mailto:", "#")):
                    continue
                # Strip a trailing #anchor and any query.
                target = raw.split("#", 1)[0].split("?", 1)[0]
                if not target:
                    continue
                # Resolve relative to the linking doc's directory, then make it
                # package-root-relative. Scope is decided by where a link
                # RESOLVES, never by how it is spelled: a `../`
                # from a nested doc very often lands back INSIDE the package
                # — as `references/README.md` -> `../spec/design.md` does —
                # and skipping on the literal prefix would leave every such
                # link unexamined, though each is the allow-list question
                # this gate exists to answer.
                resolved = (doc.parent / target).resolve()
                try:
                    rel_target = resolved.relative_to(skill_dir.resolve())
                except ValueError:
                    # Genuinely escapes the package. Under the two-promise rule
                    # that is a finding:
                    # the link resolves for a reader who happens to be standing
                    # in the monorepo and resolves nowhere for a packaged
                    # install, which has no sibling skill and no spec/ above it.
                    # A marker does NOT excuse it — the marker mechanism says
                    # "this in-package path is deliberately unshipped", and an
                    # escaping link has a spelling that works for every reader.
                    findings.append(
                        f"{rel_doc.as_posix()} links to `{target}` "
                        f"→ resolves OUTSIDE the package "
                        f"({_repo_display(resolved)}); a shipped doc may leave "
                        f"its package only by absolute repo URL "
                        f"({_GH_BLOB_BASE}/…, {_GH_TREE_BASE}/… for a "
                        f"directory), or the material must ship in `files`"
                    )
                    continue
                rel_str = rel_target.as_posix()
                if _is_shipped(rel_str, allow):
                    continue
                # A deliberate monorepo-only reference (documented inline) is
                # expected to be absent from the tarball — not a defect.
                if monorepo_only:
                    continue
                findings.append(
                    f"{rel_doc.as_posix()} links to `{target}` "
                    f"→ `{rel_str}` is omitted from package.json `files`"
                )
    return findings


def check(skills_root: Path, verbose: bool = False, ci: bool = False) -> int:
    """Validate every packaged skill.  Return finding count."""
    findings: list[tuple[Path, str]] = []
    n_checked = 0

    for skill_dir in _iter_packaged_skills(skills_root):
        n_checked += 1
        skill_findings = _broken_package_links(skill_dir)
        for msg in skill_findings:
            findings.append((skill_dir, msg))
        if verbose and not skill_findings:
            sys.stderr.write(f"ok: {skill_dir.name}\n")

    if findings:
        prefix = "::error:: " if ci else ""
        sys.stderr.write(
            f"\n{len(findings)} in-package link finding(s):\n\n"
        )
        for skill_dir, msg in findings:
            try:
                rel = skill_dir.relative_to(REPO_ROOT)
            except ValueError:
                rel = skill_dir
            sys.stderr.write(f"  {prefix}{rel}: {msg}\n")
        sys.stderr.write(
            "\nFix, by which finding you have:\n"
            "  * `omitted from package.json files` — a shipped doc links to a "
            "path INSIDE the package that the allow-list does not ship, so a "
            "packaged install resolves it to a missing file. Add the target to "
            "`files`, or mark the linking line as a deliberate monorepo-only "
            "reference.\n"
            "  * `resolves OUTSIDE the package` — a shipped doc reaches out of "
            "its own package by a relative link, which resolves only for a "
            "reader standing in the monorepo. Spell it as an absolute repo URL "
            f"instead: {_GH_BLOB_BASE}/<path>[#anchor] for a file, "
            f"{_GH_TREE_BASE}/<path> for a directory — or, if the skill's "
            "normal operation must READ it, ship the material in `files` (or "
            "route the read through the pinned checkout the skill names). The "
            "monorepo-only marker does not apply here: it excuses an unshipped "
            "IN-package path, and an escape has a spelling that works for "
            "every reader.\n"
        )
    elif verbose:
        sys.stderr.write(
            f"all {n_checked} packaged skill(s) resolve their in-package links.\n"
        )

    return len(findings)


# --------------------------------------------------------------------------
# Self-tests — synthetic skill dirs exercising the pass/fail axes.
# --------------------------------------------------------------------------


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _run_self_tests(verbose: bool = False) -> int:
    escape = "resolves OUTSIDE the package"
    omitted = "is omitted from package.json `files`"
    marker = " (not in the published package; run from a monorepo clone)"
    # (name, package.json `files`, {doc: line}, the kind of its one finding)
    cases = [
        ("unshipped_link", ["SKILL.md"],
         {"SKILL.md": "See [setup](docs/SETUP.md)."}, omitted),
        ("parent_escape", ["SKILL.md"],
         {"SKILL.md": "See [foo](../../tools/foo/README.md)."}, escape),
        # The marker excuses an unshipped IN-package path, never an escape.
        ("parent_escape_marker_does_not_excuse", ["SKILL.md"],
         {"SKILL.md": "See [foo](../../tools/foo/README.md)" + marker}, escape),
        # A `../` from a nested doc that lands back inside the package answers
        # to the allow-list, however it is spelled.
        ("parent_reentry_unshipped", ["SKILL.md", "references/"],
         {"references/lens.md": "See [design](../spec/design.md)."}, omitted),
        # The scan surface is derived from `files`: a shipped patterns/ doc is
        # scanned although no roster names it.
        ("patterns_doc_unshipped_link", ["SKILL.md", "patterns"],
         {"patterns/example.md": "See [setup](../docs/SETUP.md)."}, omitted),
    ]

    failures = 0
    for name, files, docs, expected in cases:
        with tempfile.TemporaryDirectory() as td:
            skill = Path(td) / name
            _write(skill / "package.json", json.dumps({"name": f"@day8/{name}", "files": files}))
            for rel, line in docs.items():
                _write(skill / rel, f"# doc\n{line}\n")
            got = _broken_package_links(skill)
        if len(got) != 1 or expected not in got[0]:
            sys.stderr.write(
                f"self-test FAIL: {name} expected one `{expected}` finding, got {got}\n"
            )
            failures += 1
        elif verbose:
            sys.stderr.write(f"self-test PASS: {name}\n")

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} self-tests passed.\n")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify every packaged skill's shipped docs resolve their "
            "intra-package links against the package.json `files` "
            "allow-list."
        ),
    )
    parser.add_argument(
        "--skills-root",
        default=None,
        help="Path to the skills/ root. Defaults to <repo>/skills.",
    )
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument(
        "--ci",
        action="store_true",
        help="CI mode: ::error:: prefixed findings, exit non-zero on any.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the bundled synthetic-skill self-tests and exit.",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    skills_root = Path(args.skills_root).resolve() if args.skills_root else SKILLS_ROOT
    if not skills_root.is_dir():
        sys.stderr.write(f"error: {skills_root} is not a directory.\n")
        return 2

    findings = check(skills_root, verbose=args.verbose and not args.ci, ci=args.ci)
    return 0 if findings == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
