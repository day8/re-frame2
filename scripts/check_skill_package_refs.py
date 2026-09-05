#!/usr/bin/env python3
"""In-package link-resolution gate for packaged skills (rf2-deo2zp).

re-frame2's published skills (the ones carrying a `package.json` +
`.claude-plugin/plugin.json`) each ship a self-contained package under a
TWO-PROMISE package boundary (rf2-nvbz):

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

(The former sibling `skills/shared/` protocol layer — and the link-only
distribution caveat + guard arm that defended it, rf2-f14su — was retired under
rf2-fqjys: each consumer now owns its instructions locally.)

The two invariants this gate enforces:

    a. In-package link resolution (rf2-deo2zp). For every packaged skill,
       every INTRA-package relative link from a shipped doc MUST resolve to a
       file the `files` allow-list ships.

    b. No escape (rf2-nvbz). NO relative link in a shipped doc may resolve
       OUTSIDE its package at all.

"Shipped doc" is DERIVED from that same allow-list rather than from a roster of
directory names (rf2-pp72). The docs a packaged install can resolve links inside
are exactly the markdown files the tarball contains, so `patterns/`,
`decision-trees/` and a top-level `examples-map.md` are scanned on the day
`files` starts shipping them — there is no second list to keep in step, which is
what let the roster fall behind the allow-list in the first place. A doc `files`
omits is not in the tarball at all, so its links cannot break a packaged install
and it is correctly out of scope.

"Intra-package" is decided by where a link RESOLVES, not by how it is spelled
(rf2-kgw8z). A `../` from a nested doc usually lands back inside the package —
`references/README.md` -> `../spec/design.md` is the reported case, and
`references/x.md` -> `../SKILL.md` is the common one — so treating the literal
`../` prefix as "escapes the package" left those links unexamined. Resolution
still decides which invariant a link answers to now that both are enforced:
re-entry is invariant (a)'s allow-list question, a genuine escape is invariant
(b), and neither can be read off the spelling.

The defect invariant (a) prevents: a shipped doc links to a sibling support doc
(`docs/LOCAL_DEV.md`, `STATUS.md`, `RELEASING.md`, ...) that the `files`
allow-list omits, so a packaged install resolves the link to a missing file at
exactly the point a user is configuring the skill.

The defect invariant (b) prevents (rf2-nvbz): a link that genuinely RESOLVES
outside the package is correct for a reader standing in the monorepo and dead
for everyone else — each skill publishes as its OWN package with its own name
and `repository.directory`, so an installed one has no sibling skill beside it
and no `spec/` above it. Those links used to be OUT OF SCOPE here, on the
reasoning that they point at the monorepo rather than the tarball, while
`check_doc_slugs.py` passed them because their targets do exist in the repo —
so 427 of them accumulated in the seam between the two gates with the tree
green throughout. They now take the absolute-URL spelling, and
`check_doc_slugs.py` unwraps this repo's own `blob/main` / `tree/main` URLs so
that spelling keeps the rename-safety the relative one had.

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

rf2-deo2zp.
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

# Inline markers (case-insensitive) that flag a link as a DELIBERATE
# monorepo-only / repo-maintenance reference — a file the skill intentionally
# omits from its published package and tells the reader to reach from a clone.
# When the linking line carries one of these, an unshipped target is expected,
# not a defect.
# The spelling a shipped doc must use to cite anything outside its package
# (rf2-nvbz). Deliberate TWINS of `GH_BLOB_BASE` in mkdocs_hooks.py, which
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
    halves of the invariant cannot drift apart (rf2-pp72).

    This replaced a hardcoded SKILL.md / README.md / references/**.md roster
    that had already fallen behind: `skills/re-frame2` ships `patterns/`,
    `decision-trees/` and `examples-map.md`, and `skills/re-frame2-pair` ships
    `docs/` and `STATUS.md`, none of which the roster named — so a link from
    any of them to an unshipped path was invisible here.
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
                # RESOLVES, never by how it is spelled (rf2-kgw8z): a `../`
                # from a nested doc very often lands back INSIDE the package
                # — `references/README.md` -> `../spec/design.md` is the
                # reported case — and skipping on the literal prefix left
                # every such link unexamined, which is the allow-list question
                # this gate exists to answer.
                resolved = (doc.parent / target).resolve()
                try:
                    rel_target = resolved.relative_to(skill_dir.resolve())
                except ValueError:
                    # Genuinely escapes the package. Under the two-promise rule
                    # (rf2-nvbz) that is now a finding rather than out of scope:
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
            "reference. (rf2-deo2zp)\n"
            "  * `resolves OUTSIDE the package` — a shipped doc reaches out of "
            "its own package by a relative link, which resolves only for a "
            "reader standing in the monorepo. Spell it as an absolute repo URL "
            f"instead: {_GH_BLOB_BASE}/<path>[#anchor] for a file, "
            f"{_GH_TREE_BASE}/<path> for a directory — or, if the skill's "
            "normal operation must READ it, ship the material in `files` (or "
            "route the read through the pinned checkout the skill names). The "
            "monorepo-only marker does not apply here: it excuses an unshipped "
            "IN-package path, and an escape has a spelling that works for "
            "every reader. (rf2-nvbz)\n"
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


def _make_skill(
    root: Path,
    name: str,
    *,
    package: bool,
    files: list[str] | None = None,  # package.json `files`; None -> ["SKILL.md"]
    skill_link: str | None = None,   # an intra-package link to embed in SKILL.md
    ref_link: str | None = None,     # a link to embed in references/lens.md
    pattern_link: str | None = None, # a link to embed in patterns/example.md
    create_docs: bool = False,       # create docs/SETUP.md on disk
    monorepo_only: bool = False,     # mark the link's line monorepo-only
) -> None:
    d = root / name
    if package:
        allow = files if files is not None else ["SKILL.md"]
        _write(
            d / "package.json",
            '{"name": "@day8/%s", "files": %s}' % (name, json.dumps(allow)),
        )
    suffix = (
        " (not in the published package; run from a monorepo clone)"
        if monorepo_only
        else ""
    )
    skill_body = "# skill\n"
    if skill_link is not None:
        skill_body += f"See [setup]({skill_link}){suffix}.\n"
    if create_docs:
        _write(d / "docs" / "SETUP.md", "# setup\n")
    _write(d / "SKILL.md", skill_body)
    # references/lens.md sits one level down, so a `../` link from it resolves
    # back INSIDE the package — the rf2-kgw8z shape.
    ref_body = "# refs\n"
    if ref_link is not None:
        ref_body += f"See [design]({ref_link}){suffix}.\n"
    _write(d / "references" / "lens.md", ref_body)
    # patterns/example.md is the rf2-pp72 shape: a doc the old hardcoded
    # roster never scanned, shipped or not.
    if pattern_link is not None:
        _write(
            d / "patterns" / "example.md",
            f"# pattern\nSee [setup]({pattern_link}){suffix}.\n",
        )
    _write(d / "README.md", "# readme\n")


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, dict, int]] = [
        # (name, make-kwargs, expected findings contributed by this skill)
        # packaged, no links                                    -> 0
        ("ok_no_links", dict(package=True), 0),
        # UNpackaged skill dir                                  -> 0 (not distributable)
        ("ok_unpackaged", dict(package=False, skill_link="docs/SETUP.md", create_docs=True), 0),
        # in-package link to a docs/ file NOT in `files`         -> 1 (broken in tarball)
        (
            "bad_unshipped_link",
            dict(
                package=True,
                files=["SKILL.md", "README.md"],
                skill_link="docs/SETUP.md",
                create_docs=True,
            ),
            1,
        ),
        # in-package link to a docs/ file covered by a dir entry -> 0 (shipped)
        (
            "ok_shipped_dir_link",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "docs/"],
                skill_link="docs/SETUP.md",
                create_docs=True,
            ),
            0,
        ),
        # in-package link with #anchor to a shipped file         -> 0 (anchor stripped)
        (
            "ok_anchor_shipped",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "docs/SETUP.md"],
                skill_link="docs/SETUP.md#configure",
                create_docs=True,
            ),
            0,
        ),
        # rf2-nvbz — a link RESOLVING outside the package is a FINDING. It is
        # correct for a reader standing in the monorepo and dead for a packaged
        # install, which carries no sibling skill and no spec/ above it. This
        # case expected 0 until the two-promise rule landed.            -> 1
        (
            "bad_parent_escape",
            dict(
                package=True,
                files=["SKILL.md", "README.md"],
                skill_link="../../tools/foo/README.md",
            ),
            1,
        ),
        # rf2-nvbz — an absolute repo URL is the sanctioned spelling for the
        # same citation, and carries no allow-list question at all.      -> 0
        (
            "ok_parent_escape_as_repo_url",
            dict(
                package=True,
                files=["SKILL.md", "README.md"],
                skill_link=(
                    "https://github.com/day8/re-frame2/blob/main/"
                    "tools/foo/README.md"
                ),
            ),
            0,
        ),
        # rf2-nvbz — the monorepo-only MARKER does not excuse an escaping link.
        # The marker says "this in-package path is deliberately unshipped"; an
        # escape has a spelling that works for every reader, so there is
        # nothing to excuse.                                             -> 1
        (
            "bad_parent_escape_marker_does_not_excuse",
            dict(
                package=True,
                files=["SKILL.md", "README.md"],
                skill_link="../../tools/foo/README.md",
                monorepo_only=True,
            ),
            1,
        ),
        # rf2-kgw8z — a `../` link from a NESTED doc that resolves back INSIDE
        # the package, at a path `files` omits. Spelled like an escape, but it
        # is an intra-package link and is exactly the reported improver case
        # (references/README.md -> ../spec/design.md).                  -> 1
        (
            "bad_parent_reentry_unshipped",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "references/"],
                ref_link="../spec/design.md",
            ),
            1,
        ),
        # the same `../` re-entry shape, but the target IS shipped      -> 0
        (
            "ok_parent_reentry_shipped",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "references/"],
                ref_link="../SKILL.md",
            ),
            0,
        ),
        # the same `../` re-entry to an unshipped path, but the LINE documents
        # it as a deliberate monorepo-only reference                    -> 0
        (
            "ok_parent_reentry_marked",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "references/"],
                ref_link="../spec/design.md",
                monorepo_only=True,
            ),
            0,
        ),
        # NOT an existence checker: a `../` re-entry to a path that is SHIPPED
        # by the allow-list but does not exist on disk stays green — existence
        # is check_doc_slugs.py's question, not this gate's.            -> 0
        (
            "ok_shipped_but_absent_is_not_our_question",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "references/", "docs/"],
                ref_link="../docs/NEVER-CREATED.md",
            ),
            0,
        ),
        # rf2-pp72 — THE WIDENING CASE. A shipped `patterns/` doc links to a
        # path `files` omits. Under the old hardcoded SKILL/README/references
        # roster this doc was never opened, so the finding was invisible and
        # this case returned 0. It is the negative fixture proving the scan
        # surface is genuinely derived from the allow-list.        -> 1
        (
            "bad_patterns_doc_unshipped_link",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "patterns"],
                pattern_link="../docs/SETUP.md",
                create_docs=True,
            ),
            1,
        ),
        # the same shipped `patterns/` doc, but the target IS shipped   -> 0
        (
            "ok_patterns_doc_shipped_link",
            dict(
                package=True,
                files=["SKILL.md", "README.md", "patterns", "docs"],
                pattern_link="../docs/SETUP.md",
                create_docs=True,
            ),
            0,
        ),
        # the derivation's OTHER half: `patterns/` is NOT in `files`, so the
        # doc is absent from the tarball and its links cannot break a packaged
        # install. Out of scope by construction rather than by omission. -> 0
        (
            "ok_unshipped_patterns_dir_is_not_scanned",
            dict(
                package=True,
                files=["SKILL.md", "README.md"],
                pattern_link="../docs/SETUP.md",
                create_docs=True,
            ),
            0,
        ),
        # in-package link to an unshipped file, but the LINE documents it as a
        # deliberate monorepo-only reference                     -> 0
        (
            "ok_monorepo_only_marker",
            dict(
                package=True,
                files=["SKILL.md", "README.md"],
                skill_link="docs/SETUP.md",
                create_docs=True,
                monorepo_only=True,
            ),
            0,
        ),
    ]

    failures = 0
    for name, kwargs, expected in cases:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "skills"
            root.mkdir()
            _make_skill(root, name, **kwargs)

            saved = sys.stderr
            sys.stderr = _DevNull()
            try:
                got = check(root, verbose=False, ci=False)
            finally:
                sys.stderr = saved

            if got == expected:
                if verbose:
                    sys.stderr.write(f"self-test PASS: {name} (findings={got})\n")
            else:
                sys.stderr.write(
                    f"self-test FAIL: {name} expected {expected}, got {got}\n"
                )
                failures += 1

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} self-tests passed.\n")
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:  # noqa: D401
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify every packaged skill's shipped docs resolve their "
            "intra-package links against the package.json `files` allow-list "
            "(rf2-deo2zp)."
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
