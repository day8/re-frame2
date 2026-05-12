#!/usr/bin/env python3
"""Validate in-repo markdown anchor links resolve to real heading slugs.

Walks the docs corpus, builds a per-file heading-slug index for every
heading (H1-H6) using the exact same slugifier the MkDocs build uses
(pymdownx.slugs.slugify with case=lower), then scans every
[text](file.md#anchor) link and reports any anchors that don't exist in
the target file's slug index.

This is the durable fix for rf2-sefq — the cross-link audit in cluster #10
surfaced 105+ broken anchors that drifted as chapters were renumbered and
headings renamed. Hook this into CI and the build fails before such drift
ships.

Exit code:
    0  no broken anchors
    1  at least one broken anchor (results printed in file:line form)
    2  invocation / setup error

Notes on what is and isn't checked:
    * Only intra-repo links are validated. External http(s) URLs are skipped.
    * Only links to .md files are validated. Code, image, and asset links
      are skipped.
    * Same-file anchors (no path, just #foo) are validated against the
      current file's index.
    * Cross-tree links resolve relative to the linking file (..  segments
      are honoured).
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

# An auto-generated copy of spec/ that mkdocs build stages under docs/spec/.
# .gitignored, but defensive in case a stale copy survives locally.
EXCLUDE_DIR_REL = frozenset({Path("docs/spec")})

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

# Markdown inline link.  Captures destination only.  Reference-style links
# ([text][ref]) are ignored — none in this corpus per spot-check, and a full
# parser is out of scope for a CI guard.
_LINK_RE = re.compile(r"\[(?:[^\]\\]|\\.)*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")

# Fenced code block delimiter (``` or ~~~ optionally followed by language).
_FENCE_RE = re.compile(r"^(```|~~~)")


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
    """Compute the slug set for every H1/H2/H3 heading in path."""
    text = path.read_text(encoding="utf-8", errors="replace")
    slugs: set[str] = set()
    seen_counts: dict[str, int] = {}
    for _, line in _strip_fences(text.splitlines()):
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


def _extract_links(path: Path) -> Iterable[tuple[int, str]]:
    """Yield (line-number, destination) for every inline markdown link.

    Links inside fenced code blocks are skipped.
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    for line_no, content in _strip_fences(text.splitlines()):
        if not content:
            continue
        for m in _LINK_RE.finditer(content):
            yield line_no, m.group(1)


def _resolve_target(linker: Path, dest_path: str, repo_root: Path) -> Path | None:
    """Resolve a (possibly relative) link path against the linker's directory.

    Returns the absolute Path to the target file, or None if resolution would
    escape the repo (which can't be validated locally — those are treated as
    external references and skipped by the caller).
    """
    if not dest_path:
        return linker  # same-file anchor
    try:
        target = (linker.parent / dest_path).resolve()
    except (OSError, ValueError):
        return None
    try:
        target.relative_to(repo_root.resolve())
    except ValueError:
        return None
    return target


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate every in-repo anchor link.  Return broken-link count."""
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

    broken: list[tuple[Path, int, str, str]] = []
    for path in files:
        for line_no, dest in _extract_links(path):
            # Skip external / non-markdown / mailto / fragment-only links
            # don't qualify as broken — we only validate .md anchors.
            if "#" not in dest:
                continue
            if dest.startswith(("http://", "https://", "mailto:", "tel:", "//")):
                continue

            path_part, _, anchor = dest.partition("#")
            anchor = urllib.parse.unquote(anchor).strip()
            if not anchor:
                continue

            # Strip any query string from path-part (rare in markdown but safe).
            path_part = path_part.split("?", 1)[0]

            # Same-file anchor.
            if path_part == "":
                target = path
            else:
                # Only validate links to .md files — anchors on other file
                # types (images, source files) aren't slug-derived.
                if not path_part.endswith(".md"):
                    continue
                target = _resolve_target(path, path_part, repo_root)
                if target is None or not target.is_file():
                    # Missing target file is a different problem (and mkdocs'
                    # own link check catches it for the rendered site).  We
                    # only flag anchor mismatches.
                    continue

            if anchor not in slugs_for(target):
                broken.append((path, line_no, dest, str(target.relative_to(repo_root.resolve()))))

    if broken:
        sys.stderr.write(
            f"\n{len(broken)} broken anchor link(s) found:\n\n"
        )
        for src, line_no, dest, target_rel in broken:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  {rel}:{line_no}  -> {dest}\n"
                f"      (target: {target_rel})\n"
            )
        sys.stderr.write(
            "\nFix: confirm the heading still exists in the target file and "
            "update the link, or rename the heading and re-link.\n"
        )
    elif verbose:
        sys.stderr.write("no broken anchors.\n")

    return len(broken)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Validate intra-repo markdown anchor links (rf2-sefq).",
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root.  Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    args = parser.parse_args(argv)

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


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
