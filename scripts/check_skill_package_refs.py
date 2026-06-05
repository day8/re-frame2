#!/usr/bin/env python3
"""Package/distribution-completeness gate for skill `../shared/` references (rf2-f14su).

re-frame2's published skills (the ones carrying a `package.json` +
`.claude-plugin/plugin.json`) are **monorepo-link-only**: the supported install
is a symlink/junction from a full re-frame2 clone into `~/.claude/skills/`, so a
skill's relative `../shared/<leaf>.md` references resolve as a *sibling*
directory inside the checkout.

The defect this gate prevents (rf2-f14su, finding 1): a skill makes
`../shared/<leaf>.md` a **normal-operation** reference (cited from `SKILL.md` or
a `references/**` leaf the skill loads while running), but the skill's npm
package / plugin bundle ships only its own directory — never the sibling
`skills/shared/` tree. `files`-allow-lists in npm cannot reach a parent
directory, so a `../shared/` leaf is structurally impossible to include in the
skill's own tarball. A consumer who installs from the tarball, the plugin
bundle, or a hand-copied snapshot — instead of linking from a clone — then hits
broken `../shared/` links and silently loses whatever the shared leaf carries.
For the retro/improver skills that leaf is a **security boundary**
(redaction, untrusted-evidence handling, shell-safe `gh issue create`), so the
silent drop is a correctness/safety regression, not cosmetic.

The fix re-frame2 adopts is **guard, don't vendor**: the shared leaves stay a
single boundary owned once under `skills/shared/` (vendoring a copy into each
consumer package would duplicate the security boundary and let the copies
drift), and every consumer skill's README must carry an explicit *link-only /
bring-shared-along* caveat so no install path silently drops the protocol.

This gate enforces that invariant generically:

    For every skill directory that (a) carries a `package.json` AND
    (b) makes a normal-operation `../shared/<leaf>` reference, the skill's
    README.md MUST contain BOTH:
      * the literal token `../shared/`             — proves the caveat names
                                                     the sibling path, and
      * a `npm pack` / link-only distribution marker (`npm pack`,
        `link from a clone`, or `link, never copy`)
                                                   — proves the caveat is about
                                                     distribution, not just a
                                                     stray relative link.

"Normal-operation" excludes `spec/**` (skill-internal re-authoring meta-docs,
explicitly "not loaded during normal operation") and any `tests/**` tree.

This is deliberately a *prose-caveat* gate, not an `npm pack` invocation: the
structural fact (a `../shared/` ref can never be in the skill's own tarball) is
known statically, so the only thing left to verify is that the README owns up to
it. Keeping the gate pure-Python-stdlib makes it fast and CI-portable (no Node
needed) and mirrors the other `scripts/check_skill_*.py` gates.

Exit code:
    0  no findings
    1  at least one finding
    2  invocation / setup error

Usage:
    python scripts/check_skill_package_refs.py
    python scripts/check_skill_package_refs.py --verbose
    python scripts/check_skill_package_refs.py --ci          # terse; CI-shaped
    python scripts/check_skill_package_refs.py --self-test    # built-in fixtures

rf2-f14su.
"""

from __future__ import annotations

import argparse
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

# A `../shared/<leaf>` reference anywhere in a markdown link target or inline.
# `<leaf>` is one path segment (e.g. retro-protocol.md); we only need to know a
# normal-operation `../shared/` ref exists, not enumerate every one.
_SHARED_REF_RE = re.compile(r"\.\./shared/[A-Za-z0-9._/-]+")

# Distribution markers that prove a README's `../shared/` mention is the
# link-only / bring-shared-along caveat (case-insensitive).
_DISTRIBUTION_MARKERS = (
    "npm pack",
    "link from a clone",
    "link, never copy",
    "bring `skills/shared/`",
    "bring skills/shared/",
)

# Subtrees of a skill that are NOT loaded during normal operation, so a
# `../shared/` reference there does not create the distribution hazard.
_META_SUBDIRS = ("spec", "tests")


def _is_meta_doc(path: Path, skill_dir: Path) -> bool:
    """True if `path` is a skill-internal meta-doc (spec/** or tests/**)."""
    rel_parts = path.relative_to(skill_dir).parts
    return bool(rel_parts) and rel_parts[0] in _META_SUBDIRS


def _normal_op_docs(skill_dir: Path) -> Iterable[Path]:
    """Yield the markdown files a skill loads during normal operation.

    SKILL.md + every references/**.md leaf.  spec/** and tests/** are excluded
    (re-authoring / regression meta-docs, not loaded at runtime).
    """
    skill_md = skill_dir / "SKILL.md"
    if skill_md.is_file():
        yield skill_md
    refs = skill_dir / "references"
    if refs.is_dir():
        for md in sorted(refs.rglob("*.md")):
            yield md


def _references_shared(skill_dir: Path) -> bool:
    """True if any normal-operation doc references `../shared/`."""
    for doc in _normal_op_docs(skill_dir):
        if _is_meta_doc(doc, skill_dir):
            continue
        text = doc.read_text(encoding="utf-8", errors="replace")
        if _SHARED_REF_RE.search(text):
            return True
    return False


def _readme_has_caveat(skill_dir: Path) -> bool:
    """True if the skill README carries the link-only / shared-along caveat."""
    readme = skill_dir / "README.md"
    if not readme.is_file():
        return False
    text = readme.read_text(encoding="utf-8", errors="replace")
    if "../shared/" not in text:
        return False
    low = text.lower()
    return any(marker.lower() in low for marker in _DISTRIBUTION_MARKERS)


def _iter_packaged_skills(skills_root: Path) -> Iterable[Path]:
    """Yield each skill dir that carries a package.json (i.e. is distributable)."""
    if not skills_root.is_dir():
        return
    for child in sorted(skills_root.iterdir()):
        if not child.is_dir():
            continue
        if child.name == "shared":
            continue  # the shared leaf is the boundary, not a consumer skill
        if (child / "package.json").is_file():
            yield child


def check(skills_root: Path, verbose: bool = False, ci: bool = False) -> int:
    """Validate every packaged skill.  Return finding count."""
    findings: list[tuple[Path, str]] = []
    n_checked = 0

    for skill_dir in _iter_packaged_skills(skills_root):
        if not _references_shared(skill_dir):
            if verbose:
                sys.stderr.write(
                    f"skip: {skill_dir.name} has no normal-operation ../shared/ ref\n"
                )
            continue
        n_checked += 1
        if not _readme_has_caveat(skill_dir):
            findings.append(
                (
                    skill_dir,
                    "references ../shared/ in normal operation but README.md "
                    "lacks the link-only / bring-shared-along distribution caveat",
                )
            )
        elif verbose:
            sys.stderr.write(f"ok: {skill_dir.name}\n")

    if findings:
        prefix = "::error:: " if ci else ""
        sys.stderr.write(
            f"\n{len(findings)} skill(s) reference ../shared/ without a "
            "distribution caveat:\n\n"
        )
        for skill_dir, msg in findings:
            try:
                rel = skill_dir.relative_to(REPO_ROOT)
            except ValueError:
                rel = skill_dir
            sys.stderr.write(f"  {prefix}{rel}: {msg}\n")
        sys.stderr.write(
            "\nFix: the npm tarball / plugin bundle for a skill can never carry "
            "its sibling `../shared/` leaves (a `files` allow-list cannot reach a "
            "parent dir). Add a README caveat that the supported install is a "
            "link from a full monorepo clone, that `npm pack` does not ship "
            "`shared/`, and that any tarball / plugin / vendored copy must bring "
            "`skills/shared/` alongside the skill. (rf2-f14su)\n"
        )
    elif verbose:
        sys.stderr.write(
            f"all {n_checked} ../shared/-referencing skill(s) carry the caveat.\n"
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
    shared_ref_in: str | None,  # "skill" | "references" | "spec" | None
    readme_caveat: bool,
) -> None:
    d = root / name
    if package:
        _write(d / "package.json", '{"name": "@day8/%s", "files": ["SKILL.md"]}' % name)
    skill_body = "# skill\n"
    refs_body = "# refs\n"
    spec_body = "# spec\n"
    if shared_ref_in == "skill":
        skill_body += "Load [`../shared/retro-protocol.md`](../shared/retro-protocol.md).\n"
    elif shared_ref_in == "references":
        refs_body += "See [`../../shared/issue-filing.md`](../../shared/issue-filing.md).\n"
    elif shared_ref_in == "spec":
        spec_body += "Design refs [`../../shared/retro-protocol.md`](../../shared/retro-protocol.md).\n"
    _write(d / "SKILL.md", skill_body)
    _write(d / "references" / "lens.md", refs_body)
    _write(d / "spec" / "design.md", spec_body)
    readme = "# readme\n"
    if readme_caveat:
        readme += (
            "Supported install is a link from a clone; `npm pack` does not ship "
            "`../shared/`, so vendoring must bring `skills/shared/` along.\n"
        )
    _write(d / "README.md", readme)


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, dict, int]] = [
        # (name, make-kwargs, expected findings contributed by this skill)
        # packaged + shared ref in SKILL.md + caveat present  -> 0
        ("ok_skill_ref", dict(package=True, shared_ref_in="skill", readme_caveat=True), 0),
        # packaged + shared ref in references + caveat present -> 0
        ("ok_refs_ref", dict(package=True, shared_ref_in="references", readme_caveat=True), 0),
        # packaged + shared ref in SKILL.md + NO caveat        -> 1
        ("bad_no_caveat", dict(package=True, shared_ref_in="skill", readme_caveat=False), 1),
        # packaged + shared ref only in spec/ (meta-doc)       -> 0 (not normal-op)
        ("ok_spec_only", dict(package=True, shared_ref_in="spec", readme_caveat=False), 0),
        # packaged + NO shared ref                              -> 0
        ("ok_no_ref", dict(package=True, shared_ref_in=None, readme_caveat=False), 0),
        # UNpackaged + shared ref + NO caveat                   -> 0 (not distributable)
        ("ok_unpackaged", dict(package=False, shared_ref_in="skill", readme_caveat=False), 0),
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
            "Verify every packaged skill that references ../shared/ in normal "
            "operation carries the link-only distribution caveat in its README "
            "(rf2-f14su)."
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
