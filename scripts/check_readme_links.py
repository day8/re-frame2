#!/usr/bin/env python3
"""Validate links in every README.md in the repo.

Companion gate to `scripts/check_doc_slugs.py` (rf2-br5u7).  Where
`check_doc_slugs.py` covers the published docs corpus (docs/, spec/,
migration/, skills/, tools/*/spec/), this script covers the README.md
files that live alongside source code — adapters/, examples/,
testbeds/, tools/, etc.  These READMEs are read on GitHub and in the
local working tree but are **not** copied into the MkDocs site, so
they need their own anchor-correctness gate.

What this validates per README:

    * BROKEN TARGET   — internal link points at a .md file (or any
                        repo-internal path) that does not exist.
    * BROKEN ANCHOR   — target file exists but the #anchor isn't a real
                        slug as the MkDocs build would emit it.  Slug
                        rules are the project's `pymdownx.slugs.slugify`
                        config (case=lower, _N disambiguation) — the
                        same rules `check_doc_slugs.py` enforces, so the
                        two gates agree by construction (rf2-br5u7).

What this skips (deliberate scope cuts):

    * External http(s) URLs — off by default to keep the gate stable
      against third-party outages.  Opt in with `--check-external` to
      HEAD-probe (5s timeout, flag non-2xx/3xx).  The CI invocation
      `--ci` does NOT set this flag.
    * Links inside fenced code blocks AND inline-code spans — code,
      not cross-references (matches check_doc_slugs.py behaviour).
    * Mustache `{{...}}` template placeholders — flagged by the
      tools/template/resources/.../README.md surface.  The template's
      README is rendered AS-IS into the generated project, so its link
      placeholders are not real links.
    * READMEs that live under directories check_doc_slugs.py already
      walks (docs/, spec/, migration/, skills/, tools/*/spec/) — those
      are covered by the docs gate.  No double-coverage.

CLI:
    --verbose       print progress + per-finding detail
    --ci            terse output for log readability; exits non-zero on
                    any finding; --check-external stays off
    --check-external  HEAD-probe external http(s) URLs (5s timeout)
    --self-test     run the bundled fixture self-tests

Exit codes:
    0  no findings
    1  at least one finding
    2  invocation / setup error
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Iterable

# Reuse the docs-gate's slugifier and inline-extraction helpers so the
# two scripts agree by construction (rf2-br5u7).  This is a deliberate
# direct import rather than a separately-factored helper module: the
# imported symbols are the slug-rule source of truth, and routing them
# via a third file would dilute that.
try:
    from check_doc_slugs import (
        SLUGIFY,
        SLUG_SEP,
        _EXPLICIT_ID_RE,
        _HEADING_RE,
        _HTML_ANCHOR_RE,
        _LINK_RE,
        _strip_fences,
        _strip_inline_code,
    )
except ImportError as exc:  # pragma: no cover - dev-env path
    # Make `python scripts/check_readme_links.py` work from repo root
    # without setting PYTHONPATH manually.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        from check_doc_slugs import (  # type: ignore  # noqa: F401
            SLUGIFY,
            SLUG_SEP,
            _EXPLICIT_ID_RE,
            _HEADING_RE,
            _HTML_ANCHOR_RE,
            _LINK_RE,
            _strip_fences,
            _strip_inline_code,
        )
    except ImportError:
        sys.stderr.write(
            "error: cannot import check_doc_slugs from "
            f"{Path(__file__).resolve().parent}.  Is scripts/check_doc_slugs.py "
            f"missing?  Underlying ImportError: {exc}\n"
        )
        sys.exit(2)


# Directories to skip entirely when walking for README.md.
# Includes:
#   * Build/output dirs (node_modules, site, .shadow-cljs, target).
#   * Gitignored working trees (ai/).
#   * The docs gate's own coverage (docs/, spec/, migration/, skills/).
#     READMEs there are anchor-validated by check_doc_slugs.py — no
#     double-coverage.  `tools/*/spec/` READMEs are similarly covered.
#   * .git / .beads metadata.
EXCLUDE_DIR_NAMES = frozenset({
    ".git",
    ".beads",
    ".shadow-cljs",
    "node_modules",
    "site",
    "target",
    "ai",
    "__pycache__",
    # Fixture trees for the gates themselves — these contain
    # deliberately-broken READMEs that exercise the validator, and must
    # not be walked by the live repo scan.  Exact dir-name match (no
    # prefix matching) keeps the fixture scan working when invoked
    # via --repo-root <fixture-dir> (the fixture's root is *inside*
    # _test_fixtures/<gate>/<fixture>/, so the exclude only fires on
    # the live scan that starts above _test_fixtures).
    "_test_fixtures",
})

# Top-level subtrees the docs gate already validates.  README.md files
# under any of these are skipped — check_doc_slugs.py is authoritative.
DOCS_GATE_ROOTS = frozenset({
    Path("docs"),
    Path("spec"),
    Path("migration"),
    Path("skills"),
})

# Tool spec directories are also covered by the docs gate
# (DEFAULT_ROOTS in check_doc_slugs.py).  Match any path with a
# `tools/<X>/spec/` prefix.
_TOOL_SPEC_RE = re.compile(r"^tools/[^/]+/spec/")


# Mustache placeholder — `{{some.variable}}` — used in
# tools/template/resources/day8/re_frame2_template/root/README.md and
# similar template-source files.  When the placeholder appears inside a
# markdown link's destination it is a render-time substitution, not a
# real link; flagging it produces noise.  False-positive guard.
_MUSTACHE_RE = re.compile(r"\{\{.*?\}\}")


def _is_excluded(path: Path, repo_root: Path) -> bool:
    """Return True if `path` lies under a directory we should skip."""
    rel = path.relative_to(repo_root)
    parts = set(rel.parts)
    if parts & EXCLUDE_DIR_NAMES:
        return True
    # Subtrees the docs gate already covers.
    for top in rel.parents:
        if top in DOCS_GATE_ROOTS:
            return True
    if rel.parts and Path(rel.parts[0]) in DOCS_GATE_ROOTS:
        return True
    # tools/*/spec/ subtree.
    rel_str = rel.as_posix()
    if _TOOL_SPEC_RE.match(rel_str):
        return True
    return False


def _iter_readmes(repo_root: Path) -> Iterable[Path]:
    """Yield absolute paths to every in-scope README.md in the repo."""
    seen: set[Path] = set()
    for path in sorted(repo_root.rglob("README.md")):
        if _is_excluded(path, repo_root):
            continue
        ap = path.resolve()
        if ap in seen:
            continue
        seen.add(ap)
        yield path


def _slug_index(path: Path) -> set[str]:
    """Compute the slug set for headings + inline HTML anchors in `path`.

    Mirrors check_doc_slugs.py's `_slug_index` exactly so the two gates
    apply identical slug rules.  Kept as a private copy here (rather
    than imported) because check_doc_slugs.py's version is tagged
    `_slug_index` with a leading underscore but is used identically.
    Re-implementing it locally would risk drift; importing it directly
    would couple this script to a private symbol.  Chose the latter —
    private-symbol import — for now.  If check_doc_slugs.py promotes
    `_slug_index` to a public function (`slug_index`), update both
    call sites.
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    slugs: set[str] = set()
    seen_counts: dict[str, int] = {}
    for _, line in _strip_fences(text.splitlines()):
        for am in _HTML_ANCHOR_RE.finditer(line):
            slugs.add(am.group(1))
        m = _HEADING_RE.match(line)
        if not m:
            continue
        title = m.group(2).strip()
        explicit = _EXPLICIT_ID_RE.search(title)
        if explicit:
            slug = explicit.group(1)
        else:
            slug = SLUGIFY(title, SLUG_SEP)
        if not slug:
            continue
        n = seen_counts.get(slug, 0)
        if n == 0:
            slugs.add(slug)
        else:
            slugs.add(f"{slug}_{n}")
        seen_counts[slug] = n + 1
    return slugs


def _extract_links(path: Path) -> Iterable[tuple[int, str]]:
    """Yield (line-number, destination) for every inline markdown link.

    Links inside fenced code blocks AND inside inline-code spans are
    skipped (matches check_doc_slugs.py behaviour).
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    for line_no, content in _strip_fences(text.splitlines()):
        if not content:
            continue
        for m in _LINK_RE.finditer(_strip_inline_code(content)):
            yield line_no, m.group(1)


def _resolve_target(linker: Path, dest_path: str, repo_root: Path) -> Path | None:
    """Resolve a (possibly relative) link path against the linker's directory.

    Absolute-style paths (starting with `/`) resolve repo-root-relative
    — matches MkDocs' link-rendering convention.  Returns None if the
    path escapes the repo (treated as external; caller skips).
    """
    if not dest_path:
        return linker  # same-file anchor
    try:
        if dest_path.startswith("/"):
            target = (repo_root / dest_path.lstrip("/")).resolve()
        else:
            target = (linker.parent / dest_path).resolve()
    except (OSError, ValueError):
        return None
    try:
        target.relative_to(repo_root.resolve())
    except ValueError:
        return None
    return target


def _head_check(url: str, timeout: float = 5.0) -> tuple[bool, str]:
    """HEAD-probe `url`.  Return (ok, reason).

    `ok` is True for any 2xx/3xx response.  Some servers reject HEAD
    (405); fall back to a Range-limited GET in that case.  Network
    errors return (False, "<errno-or-class>").
    """
    req = urllib.request.Request(url, method="HEAD")
    req.add_header("User-Agent", "re-frame2-readme-link-check/1.0")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            code = resp.status
            if 200 <= code < 400:
                return True, f"HTTP {code}"
            return False, f"HTTP {code}"
    except urllib.error.HTTPError as exc:
        if exc.code == 405:
            # Method not allowed — retry with GET + Range: bytes=0-0.
            try:
                req2 = urllib.request.Request(url, method="GET")
                req2.add_header("User-Agent", "re-frame2-readme-link-check/1.0")
                req2.add_header("Range", "bytes=0-0")
                with urllib.request.urlopen(req2, timeout=timeout) as resp:
                    code = resp.status
                    if 200 <= code < 400:
                        return True, f"HTTP {code} (GET)"
                    return False, f"HTTP {code} (GET)"
            except Exception as exc2:  # noqa: BLE001
                return False, f"{type(exc2).__name__}: {exc2}"
        return False, f"HTTP {exc.code}"
    except Exception as exc:  # noqa: BLE001
        return False, f"{type(exc).__name__}: {exc}"


def check(
    repo_root: Path,
    verbose: bool = False,
    check_external: bool = False,
) -> int:
    """Validate every README.md in the repo.  Return finding count.

    Findings:
        * BROKEN TARGET   — internal link to a missing file.
        * BROKEN ANCHOR   — file exists, anchor doesn't.
        * BROKEN EXTERNAL — only when check_external=True; HEAD-probe
                            failed or returned non-2xx/3xx.
    """
    files = list(_iter_readmes(repo_root))
    if verbose:
        sys.stderr.write(f"scanning {len(files)} README.md files...\n")

    slug_cache: dict[Path, set[str]] = {}

    def slugs_for(path: Path) -> set[str]:
        ap = path.resolve()
        if ap not in slug_cache:
            slug_cache[ap] = _slug_index(path)
        return slug_cache[ap]

    broken_target: list[tuple[Path, int, str, str]] = []
    broken_anchor: list[tuple[Path, int, str, str]] = []
    broken_external: list[tuple[Path, int, str, str]] = []

    for path in files:
        for line_no, dest in _extract_links(path):
            # Mustache-template placeholder anywhere in the destination
            # → render-time substitution, not a real link.  Skip.
            if _MUSTACHE_RE.search(dest):
                continue

            # External / non-file references.
            if dest.startswith(("http://", "https://")):
                if check_external:
                    ok, reason = _head_check(dest)
                    if not ok:
                        broken_external.append((path, line_no, dest, reason))
                continue
            if dest.startswith(("mailto:", "tel:", "//", "#!")):
                continue

            path_part, _, anchor = dest.partition("#")
            anchor = urllib.parse.unquote(anchor).strip()
            path_part = path_part.split("?", 1)[0]

            # Same-file anchor.
            if path_part == "":
                if not anchor:
                    continue
                if anchor not in slugs_for(path):
                    broken_anchor.append(
                        (path, line_no, dest, str(path.relative_to(repo_root.resolve())))
                    )
                continue

            target = _resolve_target(path, path_part, repo_root)
            if target is None:
                # Path escapes the repo — treat as external, skip.
                continue
            if not target.exists():
                broken_target.append(
                    (path, line_no, dest, _display_target(target, repo_root))
                )
                continue

            # Anchor validation only meaningful for .md targets.  Other
            # filetypes don't have a slug index; the existence check
            # above is the sole gate.
            if anchor and target.suffix.lower() == ".md" and target.is_file():
                if anchor not in slugs_for(target):
                    broken_anchor.append(
                        (path, line_no, dest, str(target.relative_to(repo_root.resolve())))
                    )

    total = len(broken_target) + len(broken_anchor) + len(broken_external)

    if broken_target:
        sys.stderr.write(
            f"\n{len(broken_target)} broken target file(s) in README links:\n\n"
        )
        for src, line_no, dest, target_rel in broken_target:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN TARGET: {rel}:{line_no} -> {dest}\n"
                f"      (missing: {target_rel})\n"
            )

    if broken_anchor:
        sys.stderr.write(
            f"\n{len(broken_anchor)} broken anchor link(s) in READMEs:\n\n"
        )
        for src, line_no, dest, target_rel in broken_anchor:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN ANCHOR: {rel}:{line_no} -> {dest}\n"
                f"      (target: {target_rel})\n"
            )
        sys.stderr.write(
            "\nFix: confirm the heading still exists in the target file and "
            "update the link, or rename the heading and re-link.  Anchor "
            "slugs use MkDocs' `pymdownx.slugs.slugify` rules (case=lower, "
            "underscore-N disambiguation), NOT GitHub's rules.\n"
        )

    if broken_external:
        sys.stderr.write(
            f"\n{len(broken_external)} broken external URL(s) in READMEs:\n\n"
        )
        for src, line_no, dest, reason in broken_external:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN EXTERNAL: {rel}:{line_no} -> {dest}\n"
                f"      ({reason})\n"
            )

    if total == 0 and verbose:
        sys.stderr.write("no broken README links.\n")

    return total


def _display_target(target: Path, repo_root: Path) -> str:
    try:
        return str(target.relative_to(repo_root.resolve()))
    except ValueError:
        return str(target)


# --------------------------------------------------------------------------
# Self-tests (rf2-br5u7) — fixture-driven sanity checks parallel to the
# check_doc_slugs.py fixtures.  Each fixture is a self-contained mini-repo
# (mkdocs.yml + at least one README.md).
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent / "_test_fixtures" / "check_readme_links"
)


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        # (fixture-dir, expected-finding-count)
        ("valid_readme",                     0),  # baseline: clean README
        ("broken_internal_link",             1),  # missing target file
        ("mkdocs_slug_anchor_ok",            0),  # MkDocs slug rule, NOT GitHub
        ("github_slug_anchor_broken",        1),  # would pass on GitHub, fails on MkDocs
        ("mustache_placeholder_ignored",     0),  # rf2-br5u7 false-positive guard
        ("underscore_disambig_ok",           0),  # MkDocs _N suffix
        ("inline_code_link_ignored",         0),  # fence + inline-code guard
        ("external_link_skipped_by_default", 0),  # off without --check-external
    ]

    failures = 0
    for fixture, expected in cases:
        root = _SELF_TEST_FIXTURE_ROOT / fixture
        if not (root / "mkdocs.yml").is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing mkdocs.yml at {root}\n"
            )
            failures += 1
            continue

        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            got = check(root, verbose=False, check_external=False)
        finally:
            sys.stderr = saved_stderr

        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (broken={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected broken={expected}, got {got}\n"
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
            "Validate links in every README.md in the repo (rf2-br5u7). "
            "Companion gate to check_doc_slugs.py — covers READMEs that "
            "live alongside source code, NOT in the docs/spec/migration "
            "trees the docs gate already validates."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root.  Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--ci",
        action="store_true",
        help=(
            "CI mode: terse output, exit non-zero on any finding, do NOT "
            "probe external URLs (--check-external stays off for stability)."
        ),
    )
    parser.add_argument(
        "--check-external",
        action="store_true",
        help=(
            "HEAD-probe external http(s) URLs (5s timeout).  Off by default "
            "(third-party outages otherwise flake the gate)."
        ),
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture self-tests in "
            "scripts/_test_fixtures/check_readme_links/ and exit."
        ),
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    if args.repo_root:
        repo_root = Path(args.repo_root).resolve()
    else:
        repo_root = Path(__file__).resolve().parent.parent

    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} does not look like the re-frame2 repo root "
            "(no mkdocs.yml).  Pass --repo-root explicitly.\n"
        )
        return 2

    # --ci forces --check-external off, regardless of CLI ordering.
    if args.ci:
        check_external = False
    else:
        check_external = args.check_external

    findings = check(
        repo_root,
        verbose=args.verbose and not args.ci,
        check_external=check_external,
    )
    return 0 if findings == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
