#!/usr/bin/env python3
"""Validate in-repo markdown links — both target files and anchor slugs.

Walks the docs corpus, builds a per-file heading-slug index for every
heading (H1-H6) using the exact same slugifier the MkDocs build uses
(pymdownx.slugs.slugify with case=lower), then scans every
[text](file.md) and [text](file.md#anchor) link and reports:

    * BROKEN TARGET — the .md file the link points at does not exist.
    * BROKEN ANCHOR — the file exists but the #anchor isn't a real slug.

Target-file validation was added under rf2-unge8 after the cross-link
audit on 2026-05-12 surfaced a stale `[text](file.md)` (no anchor) ref
the anchor-only validator could not see (docs/guide/17a → 19-where-next
after #483 renamed it to 20-where-next).

Hook this into CI and the build fails before such drift ships.

Exit code:
    0  no broken links
    1  at least one broken link (results printed in file:line form)
    2  invocation / setup error

Notes on what is and isn't checked:
    * Only intra-repo links are validated. External http(s) URLs are skipped.
    * Only links to .md files are validated. Code, image, and asset links
      are skipped (their existence is mkdocs' concern, not the slug index's).
    * Same-file anchors (no path, just #foo) are validated against the
      current file's index. No target-file check is needed.
    * Cross-tree links resolve relative to the linking file (..  segments
      are honoured). Absolute-style paths (`/docs/foo.md`) resolve
      repo-root-relative.
    * Anchors are decoded before comparison — links written as #foo%20bar
      are compared as #foo bar (rare in this corpus but defensive).
    * Pure section-anchor permalinks (e.g. #fragment-only-anchor) and link
      definitions inside fenced code blocks are skipped.

The script is intentionally dependency-light. Beyond pymdown-extensions
(already pinned in requirements.txt for the MkDocs build) it relies only
on the Python stdlib.
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.parse
from pathlib import Path
from typing import Iterable

try:
    from pymdownx.slugs import slugify as _slugify_factory
except ImportError as exc:  # pragma: no cover - exercised only in dev envs
    sys.stderr.write(
        "error: pymdown-extensions is required.  Install requirements.txt "
        "(`pip install -r requirements.txt`) before running this script.\n"
        f"underlying ImportError: {exc}\n"
    )
    sys.exit(2)

# Match the mkdocs.yml `toc.slugify` configuration exactly:
#   slugify: !!python/object/apply:pymdownx.slugs.slugify {kwds: {case: lower}}
SLUGIFY = _slugify_factory(case="lower")
SLUG_SEP = "-"


# Roots to scan. Order does not matter — files are deduplicated by absolute path.
DEFAULT_ROOTS = (
    "docs",
    "spec",
    "skills",
    "migration",
)

# Tools live one tier deeper: tools/<tool-name>/spec/**/*.md.
TOOLS_ROOT = "tools"

# Paths whose markdown should never be scanned.
EXCLUDE_DIR_NAMES = frozenset({
    "findings",      # exploratory working substrate
    "node_modules",
    "site",          # mkdocs build output
    ".git",
    ".beads",
    "ai",            # gitignored AI working tree
    "__pycache__",
})

# Auto-generated copies of spec/ + migration/ that mkdocs build stages
# under docs/spec/ + docs/migration/. .gitignored, but defensive in case
# a stale copy survives locally.
EXCLUDE_DIR_REL = frozenset({Path("docs/spec"), Path("docs/migration")})

# ATX heading regex — captures level (count of #) and trimmed title text.
# Fenced code blocks are stripped before this is applied.  We capture every
# heading level H1-H6 because the markdown `toc` extension assigns `id="..."`
# to every heading regardless of `toc_depth` (which only controls which
# headings appear in the rendered TOC).  Anchor links to H4-H6 therefore
# resolve on the published site and must be validated here too.
_HEADING_RE = re.compile(r"^(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")

# Custom heading id syntax: "## Title {#explicit-id}" — pymdownx.toc honours
# this when the attr_list extension is enabled.  We're conservative and accept
# any {#id} suffix on H1-H3 headings.
_EXPLICIT_ID_RE = re.compile(r"\{#([A-Za-z0-9_\-:.]+)\}\s*$")

# Inline HTML anchor — authors use `<a name="foo"></a>` / `<a id="foo"></a>`
# to mint a stable target slug that is independent of (and often shorter than)
# the heading's auto-derived slug.  Browsers and the rendered MkDocs site
# resolve both forms; the script must too.  Examples in this corpus:
# Tool-Pair.md `<a name="time-travel">`, Spec-Schemas.md `<a id="rfstate-node">`.
_HTML_ANCHOR_RE = re.compile(
    r"""<a\s+(?:name|id)\s*=\s*["']([A-Za-z0-9_\-:.]+)["']\s*(?:/\s*)?>""",
    re.IGNORECASE,
)

# Markdown inline link.  Captures destination only.  Reference-style links
# ([text][ref]) are ignored — none in this corpus per spot-check, and a full
# parser is out of scope for a CI guard.
_LINK_RE = re.compile(r"\[(?:[^\]\\]|\\.)*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")

# Fenced code block delimiter (``` or ~~~ optionally followed by language).
_FENCE_RE = re.compile(r"^(```|~~~)")

# Inline-code span (CommonMark §6.1).  A span opens with a run of N
# backticks and closes with the next run of EXACTLY N backticks on the same
# line.  This regex implements that with a back-reference for the closing
# run length and a `(?!`)` look-ahead that forbids a longer closing run
# (which would belong to a different span, not this one).
#
# Used by `_extract_links` to mask out spans like
# `` `[NNN-DocName](NNN-DocName.md)` `` — backticked link-syntax PLACEHOLDERS
# authors use to denote a literal link template, not a real link.  Without
# this mask `_LINK_RE` would treat the placeholder as a real link and
# (often) flag it as BROKEN TARGET.
#
# Scope:
# * Single-line only.  Multi-line inline code is rare in this corpus and
#   the validator already processes line-by-line after `_strip_fences`.
# * Backslash escaping of backticks (`\``) is NOT honoured — CommonMark
#   itself does not honour it; backticks are always literal markup.
# * `_strip_fences` has already blanked fenced-block lines, so this regex
#   never sees the language tag of a fence as a stray backtick run.
_INLINE_CODE_RE = re.compile(r"(`+)(?:.+?)\1(?!`)")


def _is_excluded(path: Path, repo_root: Path) -> bool:
    """Return True if path lies under a directory we should skip."""
    rel = path.relative_to(repo_root)
    parts = set(rel.parts)
    if parts & EXCLUDE_DIR_NAMES:
        return True
    for ex in EXCLUDE_DIR_REL:
        try:
            rel.relative_to(ex)
            return True
        except ValueError:
            pass
    return False


def _iter_markdown(repo_root: Path) -> Iterable[Path]:
    """Yield absolute paths to every in-scope .md file."""
    roots = []
    for d in DEFAULT_ROOTS:
        p = repo_root / d
        if p.is_dir():
            roots.append(p)
    tools = repo_root / TOOLS_ROOT
    if tools.is_dir():
        for tool in sorted(tools.iterdir()):
            spec = tool / "spec"
            if spec.is_dir():
                roots.append(spec)

    seen: set[Path] = set()
    for root in roots:
        for path in sorted(root.rglob("*.md")):
            if _is_excluded(path, repo_root):
                continue
            ap = path.resolve()
            if ap in seen:
                continue
            seen.add(ap)
            yield path


def _strip_fences(lines: list[str]) -> list[tuple[int, str]]:
    """Return (1-based line-number, content) pairs with fenced code stripped.

    Lines inside a fenced block are replaced with empty strings (preserving
    line numbering) so heading-pattern lines inside code samples don't get
    indexed as real headings.
    """
    out: list[tuple[int, str]] = []
    in_fence = False
    fence_marker = ""
    for i, raw in enumerate(lines, start=1):
        m = _FENCE_RE.match(raw)
        if m:
            marker = m.group(1)
            if not in_fence:
                in_fence = True
                fence_marker = marker
            elif marker == fence_marker:
                in_fence = False
                fence_marker = ""
            out.append((i, ""))
            continue
        out.append((i, "" if in_fence else raw))
    return out


def _slug_index(path: Path) -> set[str]:
    """Compute the slug set for every heading and inline HTML anchor in path.

    Two anchor mechanisms contribute to the slug set:

    1. ATX headings (`# Title`, `## Title`, ...) — slugified with the same
       pymdownx slugifier MkDocs uses, with duplicate-suffix disambiguation
       (`slug`, `slug_1`, `slug_2`, ...) matching pymdownx.toc.
    2. Inline HTML anchors — `<a name="...">` / `<a id="...">` — added by
       authors to mint stable cross-link targets that survive heading renames.
       Both attribute names are recognised (`name` is the legacy HTML form;
       `id` is the modern form; browsers resolve both as fragment targets).
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    slugs: set[str] = set()
    seen_counts: dict[str, int] = {}
    for _, line in _strip_fences(text.splitlines()):
        # HTML anchor elements can appear on any line (heading or not).
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
        # pymdownx.toc disambiguates duplicate slugs by appending _N starting
        # at the second occurrence.  We mirror that so links to disambiguated
        # anchors validate.
        n = seen_counts.get(slug, 0)
        if n == 0:
            slugs.add(slug)
        else:
            slugs.add(f"{slug}_{n}")
        seen_counts[slug] = n + 1
    return slugs


def _strip_inline_code(line: str) -> str:
    """Mask inline-code spans with spaces so `_LINK_RE` skips them.

    Spaces (not empty replacement) preserve column offsets, which keeps
    column-sensitive diagnostics honest if added later.  Length-preserving
    masking also means `_LINK_RE` cannot bridge across a stripped span.

    Backticked link-syntax PLACEHOLDERS such as
    `` `[NNN-DocName](NNN-DocName.md)` `` are a documentation idiom in this
    corpus — they're literal markup the author wants to TALK about, not a
    real link to resolve.  Without this masking the validator flags every
    such placeholder as BROKEN TARGET (rf2-mqv8s).
    """
    return _INLINE_CODE_RE.sub(lambda m: " " * (m.end() - m.start()), line)


def _extract_links(path: Path) -> Iterable[tuple[int, str]]:
    """Yield (line-number, destination) for every inline markdown link.

    Links inside fenced code blocks AND inside inline-code spans are
    skipped — both are "code", not real cross-references (rf2-mqv8s).
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    for line_no, content in _strip_fences(text.splitlines()):
        if not content:
            continue
        for m in _LINK_RE.finditer(_strip_inline_code(content)):
            yield line_no, m.group(1)


def _resolve_target(linker: Path, dest_path: str, repo_root: Path) -> Path | None:
    """Resolve a (possibly relative) link path against the linker's directory.

    Returns the absolute Path to the target file, or None if resolution would
    escape the repo (which can't be validated locally — those are treated as
    external references and skipped by the caller).

    Absolute-style paths (starting with `/`) resolve repo-root-relative —
    this matches mkdocs' link-rendering convention.
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


def _is_ai_findings_link(path_part: str) -> bool:
    """Return True if a link path resolves under the gitignored ai/findings/ tree.

    The repo's `/ai/` directory is gitignored at the root, so committed markdown
    that links into `ai/findings/<file>.md` (or the bare directory) creates an
    invisible-on-CI broken target — mkdocs strict's link validator catches it,
    blocking unrelated PRs in cascade.  rf2-l7yj8 promotes this from a
    mkdocs-only failure to a fast pre-PR lint.

    The check is path-component-sensitive: it matches `ai/findings/` only as a
    whole pair of path components, so casual prose links to (say)
    `vai/findings.md` or `ai-findings.md` are not mis-flagged.  Both root-anchored
    (`/ai/findings/...`) and relative (`../../ai/findings/...`) forms are caught.
    """
    # Normalise to forward slashes so the path-component test is OS-agnostic.
    normalised = path_part.replace("\\", "/")
    # Split into segments and search for the consecutive pair ("ai", "findings").
    segments = [s for s in normalised.split("/") if s not in ("", ".")]
    for i in range(len(segments) - 1):
        if segments[i] == "ai" and segments[i + 1] == "findings":
            return True
    return False


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate every in-repo markdown link.  Return broken-link count.

    Flags three distinct defects:
        * BROKEN TARGET     — link points at an .md file that doesn't exist.
        * BROKEN ANCHOR     — file exists but the #anchor doesn't resolve.
        * AI_FINDINGS_LINK  — link points into the gitignored ai/findings/ tree
                              (rf2-l7yj8).  Committed files must not reference
                              gitignored working artefacts; inline a sentence
                              summary instead.
    """
    files = list(_iter_markdown(repo_root))
    if verbose:
        sys.stderr.write(f"scanning {len(files)} markdown files...\n")

    # Build slug index lazily — many files are never linked to with an anchor.
    slug_cache: dict[Path, set[str]] = {}

    def slugs_for(path: Path) -> set[str]:
        ap = path.resolve()
        if ap not in slug_cache:
            slug_cache[ap] = _slug_index(path)
        return slug_cache[ap]

    broken_anchor: list[tuple[Path, int, str, str]] = []
    broken_target: list[tuple[Path, int, str, str]] = []
    ai_findings: list[tuple[Path, int, str]] = []
    for path in files:
        for line_no, dest in _extract_links(path):
            # External / non-file references — out of scope.
            if dest.startswith(("http://", "https://", "mailto:", "tel:", "//")):
                continue

            path_part, _, anchor = dest.partition("#")
            anchor = urllib.parse.unquote(anchor).strip()

            # Strip any query string from path-part (rare in markdown but safe).
            path_part = path_part.split("?", 1)[0]

            # rf2-l7yj8: any link into the gitignored ai/findings/ tree is a
            # policy violation regardless of whether the target happens to
            # exist locally.  Flag and continue so further checks still run.
            if _is_ai_findings_link(path_part):
                ai_findings.append((path, line_no, dest))
                continue

            # Same-file anchor (`[text](#foo)`).  No target-file check; anchor
            # only.  Empty anchor (just `#` with no fragment) is meaningless
            # so we skip it.
            if path_part == "":
                if not anchor:
                    continue
                if anchor not in slugs_for(path):
                    broken_anchor.append(
                        (path, line_no, dest, str(path.relative_to(repo_root.resolve())))
                    )
                continue

            # Only validate links to .md files — anchors and target-existence
            # on other file types (images, source files, asset links) aren't
            # part of the slug-index contract.  mkdocs' own link check is the
            # right gate for those.
            if not path_part.endswith(".md"):
                continue

            target = _resolve_target(path, path_part, repo_root)
            if target is None:
                # Path escapes the repo — treat as external reference, skip.
                continue
            if not target.is_file():
                broken_target.append(
                    (path, line_no, dest, _display_target(target, repo_root))
                )
                continue

            # Target exists.  Validate anchor if one was specified.
            if anchor and anchor not in slugs_for(target):
                broken_anchor.append(
                    (path, line_no, dest, str(target.relative_to(repo_root.resolve())))
                )

    total = len(broken_anchor) + len(broken_target) + len(ai_findings)

    if broken_target:
        sys.stderr.write(
            f"\n{len(broken_target)} broken target file(s) found:\n\n"
        )
        for src, line_no, dest, target_rel in broken_target:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN TARGET: {rel}:{line_no} -> {dest}\n"
                f"      (missing: {target_rel})\n"
            )
        sys.stderr.write(
            "\nFix: rename the link to point at the file's current path, "
            "or restore the missing file.\n"
        )

    if broken_anchor:
        sys.stderr.write(
            f"\n{len(broken_anchor)} broken anchor link(s) found:\n\n"
        )
        for src, line_no, dest, target_rel in broken_anchor:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN ANCHOR: {rel}:{line_no} -> {dest}\n"
                f"      (target: {target_rel})\n"
            )
        sys.stderr.write(
            "\nFix: confirm the heading still exists in the target file and "
            "update the link, or rename the heading and re-link.\n"
        )

    if ai_findings:
        sys.stderr.write(
            f"\n{len(ai_findings)} link(s) into gitignored ai/findings/ tree "
            "found (rf2-l7yj8):\n\n"
        )
        for src, line_no, dest in ai_findings:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  AI_FINDINGS_LINK: {rel}:{line_no} -> {dest}\n"
            )
        sys.stderr.write(
            "\nFix: the /ai/ tree is gitignored — committed files must not "
            "link into it.  Replace the markdown link with a 1-sentence inline "
            "summary of the finding (and a date) so the committed prose is "
            "self-contained and mkdocs strict's link validator doesn't trip "
            "on a missing target in CI.\n"
        )

    if total == 0 and verbose:
        sys.stderr.write("no broken links.\n")

    return total


def _display_target(target: Path, repo_root: Path) -> str:
    """Best-effort repo-relative string for a (possibly non-existent) target."""
    try:
        return str(target.relative_to(repo_root.resolve()))
    except ValueError:
        return str(target)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Validate intra-repo markdown links — target files (rf2-unge8) "
            "and anchor slugs (rf2-sefq)."
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
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture-based self-tests in "
            "scripts/_test_fixtures/check_doc_slugs/ and exit."
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

    broken = check(repo_root, verbose=args.verbose)
    return 0 if broken == 0 else 1


# --------------------------------------------------------------------------
# Self-tests (rf2-unge8) — small fixture-driven sanity checks.
#
# Each fixture is a self-contained mini-repo (just enough to exercise the
# validator: a single .md file plus a sibling mkdocs.yml so the repo-root
# guard accepts it).  We invoke `check(repo_root)` against each fixture
# and assert the expected broken-link count.  These run in CI alongside
# the full-corpus scan.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = Path(__file__).resolve().parent / "_test_fixtures" / "check_doc_slugs"


def _run_self_tests(verbose: bool = False) -> int:
    """Run fixture-based self-tests.  Return 0 on success, 1 on any failure."""
    cases: list[tuple[str, int]] = [
        # (fixture-dir, expected-broken-link-count)
        ("valid_link",                       0),
        ("broken_target",                    1),
        ("broken_anchor",                    1),
        ("same_file_anchor_ok",              0),
        ("same_file_anchor_broken",          1),
        ("absolute_path_ok",                 0),
        ("relative_dotdot_ok",               0),
        ("inline_code_placeholder_ignored",  0),  # rf2-mqv8s
        ("inline_code_negative_control",     1),  # rf2-mqv8s
        ("ai_findings_link_flagged",         1),  # rf2-l7yj8
        ("ai_findings_dir_link_flagged",     1),  # rf2-l7yj8
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

        # Silence the validator's own diagnostic output during self-tests so
        # the success path stays terse.  Failures still surface via the
        # PASS/FAIL summary below.
        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            got = check(root, verbose=False)
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
    """Minimal stderr stand-in that silently swallows writes during self-tests."""

    def write(self, *_args, **_kwargs) -> int:  # noqa: D401
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
