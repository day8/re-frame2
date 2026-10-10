#!/usr/bin/env python3
"""Validate links in every README.md — and in every repo-root markdown file.

Companion gate to `scripts/check_doc_slugs.py`.  Where
`check_doc_slugs.py` covers the published docs corpus (docs/, spec/,
migration/, skills/, tools/*/spec/), this script covers the README.md
files that live alongside source code — adapters/, examples/,
testbeds/, tools/, etc.  These READMEs are read on GitHub and in the
local working tree but are **not** copied into the MkDocs site, so
they need their own anchor-correctness gate.

REPO-ROOT MARKDOWN IS THE SAME SURFACE.  `AGENTS.md`, `CHANGELOG.md`,
`CLAUDE.md`, `SKILL-REDIRECT.md` and `TESTING.md` sit beside the root
`README.md` this gate walks, appear nowhere in `mkdocs.yml`, and are
therefore rendered by GitHub exactly as the READMEs are — and
`check_doc_slugs.py`'s roster (DEFAULT_ROOTS + `tools/*/spec`) never
opens them, so without this gate they would have no link gate of any kind.

They belong HERE rather than in the docs gate for three reasons, decided on
renderer authority and scheduling, not on findings:

    * RENDERER.  The docs gate models MkDocs' `_N` duplicate-heading
      suffix; this one models GitHub's `-N`, and the two deliberately
      disagree because their renderers do.  Root markdown
      renders on GitHub, so `-N` is the rule that actually resolves in a
      browser.
    * NO DOUBLE-COVERAGE.  The root `README.md` is in this gate's
      roster.  Adding root markdown to the docs gate instead would cover
      that one file twice, under two conflicting duplicate-suffix rules.
    * SCHEDULING.  `verify-readme-links` runs `--ci` on EVERY pull request
      (test.yml's trigger is unfiltered and the job carries no surface
      guard), where the docs gate is documentation-surface-gated.  Root
      markdown gets the stronger of the two lanes for free.

NON-README MARKDOWN BESIDE SOURCE IS THE SAME SURFACE AGAIN, for the same
three reasons.  `implementation/SECURITY.md` is cited from the root
`README.md`, carries relative targets including cross-file anchors into
`spec/`, and renders on GitHub.  It is not a README, so `_iter_readmes` never
sees it; it has a `/` in its path, so `_iter_root_markdown` drops it; it is
outside DEFAULT_ROOTS, so the docs gate never opens it.  The two
`tools/mcp-conformance/` vocabularies, which `spec/Conventions.md` and
`spec/Tool-Pair.md` cite normatively, are the same shape.

`_iter_source_markdown` covers them and needs NO new exclusion to do so: the
existing `_is_excluded` refuses the gate fixture trees, the docs-gate
roots and `tools/*/spec/`.  What it must NOT take, and why, is written on that
function.

The root and beside-source rosters are GIT-TRACKED, so an untracked scratch
file dropped there cannot red the gate on an author's machine while CI, running
on a clean clone, stays green.  The README roster walks the filesystem
(`_iter_readmes`), so an untracked README.md is scanned.  The root roster is
additionally NON-RECURSIVE (see `_iter_root_markdown`), so it cannot grow into
`implementation/`, `tools/`, `node_modules` or any generated tree.

What this validates per file:

    * BROKEN TARGET   — internal link points at a .md file (or any
                        repo-internal path) that does not exist.
    * BROKEN ANCHOR   — target file exists but the #anchor isn't a real
                        slug as **GitHub** would emit it.  These READMEs
                        are rendered by GitHub, so GitHub's heading
                        slugger is the authority here — NOT MkDocs'.
                        Two rules make up a heading id:

                          base slug — the visible heading title, cased
                            down, punctuation dropped, spaces hyphenated.
                            Shared with `check_doc_slugs.py` via SLUGIFY:
                            measured to produce byte-identical results to
                            GitHub's slugger on every heading in this
                            corpus (545/545), so the shared helper is
                            reused rather than re-implemented.  One known
                            divergence class stays unexercised here — see
                            the SLUGIFY import note below.

                          duplicate suffix — when two headings slugify
                            alike, GitHub appends `-1`, `-2`, … to the
                            later ones (`## One` / `## One` -> `one` and
                            `one-1`).  MkDocs/pymdownx.toc instead appends
                            `_1`.  This gate models GitHub's `-N`; the
                            docs gate models MkDocs' `_N`.  The two gates
                            deliberately DISAGREE on this rule because
                            their renderers do.

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
    * Markdown BELOW the repo root that is not a README.md — from the
      ROOT roster only, which is deliberately non-recursive.  It is not
      skipped by the gate: `_iter_source_markdown` walks it, minus
      whatever `_is_excluded` refuses.
    * Path-shaped REFERENCES in executed (rather than rendered) markdown.
      There is no such arm: tracked markdown under `.claude/`, if any,
      is ordinary beside-source markdown to `_iter_source_markdown`.

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
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Iterable

# Reuse the docs-gate's BASE slugifier and inline-extraction helpers.
# This is a deliberate direct import rather than a
# separately-factored helper module: the imported symbols are the
# base-slug source of truth, and routing them via a third file would
# dilute that.
#
# Sharing SLUGIFY across two different renderers is a measured decision,
# not an assumption: pymdownx's slugify and GitHub's slugger agree on every
# heading in the in-scope README corpus.  One divergence class is known and
# unexercised by any live README — heading text shaped like an
# HTML tag: pymdownx strips `<name>` entirely, GitHub escapes it and
# keeps `name`.  Closing it needs a GitHub-specific base slugifier, which
# is out of scope until a real README heading exercises it.
#
# What is NOT shared is the duplicate-heading suffix — see `_slug_index`.
#
# `_extract_links` is imported rather than reimplemented: a copy here would
# drift from the original, and a fix to one — such as seeing a link whose
# `](target#anchor)` falls on the following line — would reach only one
# corpus. One extractor, one fix.
try:
    from check_doc_slugs import (
        SLUGIFY,
        SLUG_SEP,
        _HEADING_RE,
        _HTML_ANCHOR_RE,
        _extract_links,
        _site_url_link_problems,
        _site_url_path,
        _strip_fences,
    )
except ImportError as exc:  # pragma: no cover - dev-env path
    # Make `python scripts/check_readme_links.py` work from repo root
    # without setting PYTHONPATH manually.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        from check_doc_slugs import (  # type: ignore  # noqa: F401
            SLUGIFY,
            SLUG_SEP,
            _HEADING_RE,
            _HTML_ANCHOR_RE,
            _extract_links,
            _site_url_link_problems,
            _site_url_path,
            _strip_fences,
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


def _git_ls_files(repo_root: Path, pathspec: str) -> list[str]:
    """Return the sorted tracked paths under `repo_root` matching `pathspec`.

    Git tracking, not a filesystem walk — the roster discipline the rosters in
    this gate depend on.  Note that git pathspecs are
    fnmatch WITHOUT FNM_PATHNAME, so `*` crosses `/`: `*.md` matches markdown at
    every depth, and callers that want a bounded roster must say so themselves.

    `git ls-files` is scoped to (and reports relative to) `repo_root`, so a
    self-test can point any caller at a fixture subtree unchanged.
    """
    result = subprocess.run(
        ["git", "ls-files", "-z", "--", pathspec],
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"git ls-files failed in {repo_root}: {result.stderr.strip()}"
        )
    return sorted(entry for entry in result.stdout.split("\0") if entry)


def _iter_root_markdown(repo_root: Path) -> Iterable[Path]:
    """Yield every GIT-TRACKED markdown file sitting AT the repo root.

    Two properties make this roster safe, and both are structural
    rather than a list somebody has to maintain:

    NON-RECURSIVE.  `git ls-files` pathspecs use fnmatch without
    FNM_PATHNAME, so `*` matches `/` and a bare `*.md` pathspec would
    return markdown at every depth — the whole of `docs/`, `implementation/`,
    `tools/` and every generated tree.  Entries containing a separator are
    therefore dropped, leaving exactly the files a reader sees when they open
    the repository on GitHub.  The roster cannot grow silently: a new
    directory is invisible to it by construction, while a genuinely new root
    document (the next `TESTING.md`) is picked up the moment it is tracked —
    where an explicit list of names would leave it ungated until somebody
    remembered to add it.

    GIT-TRACKED, NOT A FILESYSTEM WALK.  A `.glob("*.md")` would scan an
    author's untracked scratch notes, so a stray root `PLAN.md` with a
    speculative link could red the gate on one machine while CI — which runs
    on a clean clone — stays green.

    `git ls-files` is scoped to (and reports relative to) `repo_root`, so the
    self-tests point this at a fixture subtree unchanged.
    """
    for rel in _git_ls_files(repo_root, "*.md"):
        if "/" in rel:
            continue
        path = repo_root / rel
        # A tracked path can be absent from the working tree mid-rename; the
        # index still lists it. Skip rather than crash the whole scan.
        if path.is_file():
            yield path


def _iter_source_markdown(repo_root: Path) -> Iterable[Path]:
    """Yield every GIT-TRACKED non-README markdown file beside source.

    WHAT THIS COVERS.  `implementation/SECURITY.md` is cited from the repo
    root `README.md` and carries relative targets, several of them cross-file
    anchors into `spec/`.  Neither other gate would reach it: not
    `check_doc_slugs.py` (outside DEFAULT_ROOTS), and not the other two
    rosters here, because `_iter_readmes` wants the name `README.md` and
    `_iter_root_markdown` drops anything with a `/` in it.  The same holds for
    `examples/TESTING.md`, `implementation/adapters/TESTING.md`, the
    mutually-linked reagent-slim design trio, and the two
    `tools/mcp-conformance/` vocabularies that `spec/Conventions.md` and
    `spec/Tool-Pair.md` cite normatively.

    IT NEEDS NO EXCLUSION OF ITS OWN.  `_is_excluded` drops everything a
    wider roster must not take: `_test_fixtures` (deliberately-broken markdown
    that exists to be flagged -- covering it would red this gate permanently),
    the docs-gate roots, and `tools/*/spec/` -- which is what keeps the
    `tools/*/spec/findings/` design records out.  Those carry stale relative
    targets (`../spec/…` from inside `tools/xray/spec/findings/` resolves to
    `tools/xray/spec/spec/…`), exactly the link rot that
    `check_doc_slugs.py`'s `findings` exclusion -- "excludes exploratory
    work" -- exists to leave alone.

    It also takes `.../_shared/README_with_ssr.md`, a template payload whose
    sibling `.../root/README.md` this gate walks under the same `_MUSTACHE_RE`
    guard; taking it is the consistent answer.

    GIT-TRACKED, NOT A FILESYSTEM WALK, for the reason `_iter_root_markdown`
    records: an author's untracked scratch note must not red a gate that CI runs
    on a clean clone.
    """
    for rel in _git_ls_files(repo_root, "*.md"):
        if "/" not in rel:
            continue                      # _iter_root_markdown's roster
        path = repo_root / rel
        if path.name == "README.md":
            continue                      # _iter_readmes' roster
        if _is_excluded(path, repo_root):
            continue
        if not path.is_file():
            # Tracked but absent mid-rename; the index still lists it.
            continue
        yield path


def _iter_scanned(repo_root: Path) -> Iterable[Path]:
    """Yield every file this gate validates for links and anchors.

    Three rosters: the README corpus, repo-root markdown and
    non-README markdown beside source.  Deduplicated by resolved
    path, because the repo-root `README.md` is a member of the first two.
    """
    seen: set[Path] = set()
    for roster in (_iter_readmes, _iter_root_markdown, _iter_source_markdown):
        for path in roster(repo_root):
            ap = path.resolve()
            if ap in seen:
                continue
            seen.add(ap)
            yield path


def _github_dedupe(slug: str, occurrences: dict[str, int]) -> str:
    """Return `slug` disambiguated per GitHub's duplicate-heading rule.

    A faithful port of `github-slugger`'s `slug()` bookkeeping — the
    package GitHub uses to mint heading ids in rendered Markdown:

        while (own.call(self.occurrences, result)) {
          self.occurrences[originalSlug]++
          result = originalSlug + '-' + self.occurrences[originalSlug]
        }
        self.occurrences[result] = 0

    So a repeated heading gets `-1`, `-2`, … appended (starting at the
    SECOND occurrence), and `occurrences` is mutated across calls — pass
    one dict per document.  Note this is MkDocs' `_N` rule with a
    different separator AND a different collision walk; do not collapse
    the two.

    The `while` loop is load-bearing, not defensive: a document with
    `## Errors-1`, `## Errors`, `## Errors` renders ids `errors-1`,
    `errors`, and `errors-2` — the third heading's first candidate,
    `errors-1`, is the first heading's natural slug, so it is bumped
    again.
    """
    original = slug
    while slug in occurrences:
        occurrences[original] += 1
        slug = f"{original}-{occurrences[original]}"
    occurrences[slug] = 0
    return slug


def _slug_index(path: Path) -> set[str]:
    """Compute the slug set for headings + inline HTML anchors in `path`.

    Base slugification is check_doc_slugs.py's SLUGIFY (see the import
    note above: measured identical to GitHub's on this corpus).  The
    DUPLICATE-heading rule is GitHub's and diverges from the docs gate's
    on purpose — READMEs are rendered by GitHub, so `-N` is what actually
    resolves in a browser.

    Inline HTML anchors are indexed but deliberately kept OUT of the
    duplicate bookkeeping: GitHub's slugger only ever sees heading text,
    so an `<a id="errors">` does not push a later `## Errors` heading to
    `errors-1`.  (It mints a duplicate id in the HTML, which the browser
    resolves to whichever comes first — not something a link checker can
    usefully flag.)
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    slugs: set[str] = set()
    occurrences: dict[str, int] = {}
    for _, line in _strip_fences(text.splitlines()):
        for am in _HTML_ANCHOR_RE.finditer(line):
            slugs.add(am.group(1))
        m = _HEADING_RE.match(line)
        if not m:
            continue
        title = m.group(2).strip()
        # A trailing `{#id}` is NOT a custom heading id here.  GitHub — which
        # renders these READMEs — does not support the syntax at all, and the
        # project's mkdocs.yml leaves `attr_list` disabled, so under BOTH
        # renderers the brace suffix is ordinary heading TEXT: "## One {#dup}"
        # shows the visible title "One {#dup}" and mints the id "one-dup", not
        # "dup".  Slugify the full visible title; no explicit-id special case
        # (mirroring check_doc_slugs.py).
        slug = SLUGIFY(title, SLUG_SEP)
        if not slug:
            continue
        slugs.add(_github_dedupe(slug, occurrences))
    return slugs


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


# The redirect table's bare-URL bullets.
#
# `SKILL-REDIRECT.md` is the canonical pointer table for this repo's AI
# skills: the skills stay free of hardcoded URLs and cite its bullets by
# LABEL, so every URL they reach is written once, here. The bullets are bare
# URLs after an arrow — the format the file documents for itself — and the
# shared extractor reads INLINE and REFERENCE links only, so it yields
# literally nothing for this file. Without this reader its rows would be
# validated by no gate at all, and a dead row would point at a 404 unnoticed.
#
# The answer is this narrow reader, not a wider `_extract_links`: bare URLs are
# not links in any renderer, and teaching the shared extractor to treat them
# as such would change what BOTH gates see in every file they read. Nor is it
# a reshape of the table into `[URL](URL)`, which would break the format the
# file documents at its own `Format` section.
_REDIRECT_TABLE_NAME = "SKILL-REDIRECT.md"

# A twin of `BULLET_LABEL_RE` in `check_skill_redirect_anchors.py`, extended
# to capture the URL after the arrow. That script owns the LABEL coupling and
# is deliberately left alone: it is stdlib-only and runs in the invariant CI
# job, which installs nothing, while importing this gate's resolver would pull
# `pymdownx` in and red every pull request.
_REDIRECT_BULLET_URL_RE = re.compile(
    r"^\s*-\s+(?:\*\*|__).+?(?:\*\*|__)\s*(?:→|->)\s*(\S+)"
)


def _redirect_table_site_urls(repo_root: Path, path: Path) -> Iterable[tuple[int, str]]:
    """Yield `(line_no, url)` for the table's own-site bullets only.

    Deliberately narrow in two directions. Only THIS project's site URLs are
    yielded, so the table's `github.com` rows reach nothing — in particular
    `--check-external` probes none of them, because a row this reader skips
    can never arrive at the HEAD-probe branch.
    And fences are stripped, so a bullet quoted as a sample stays a sample.
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    for line_no, content in _strip_fences(text.splitlines()):
        match = _REDIRECT_BULLET_URL_RE.match(content)
        if match is None:
            continue
        url = match.group(1)
        if _site_url_path(repo_root, url) is not None:
            yield line_no, url


def _scanned_destinations(repo_root: Path, path: Path) -> Iterable[tuple[int, str]]:
    """Every destination this gate validates in one file.

    The shared extractor's links, plus — for the repo-root redirect table
    alone — its bare-URL bullets.
    """
    yield from _extract_links(path)
    if path.name == _REDIRECT_TABLE_NAME and path.parent.resolve() == repo_root.resolve():
        yield from _redirect_table_site_urls(repo_root, path)


def check(
    repo_root: Path,
    verbose: bool = False,
    check_external: bool = False,
) -> int:
    """Validate every README.md plus every repo-root markdown file.  Return finding count.

    Findings:
        * BROKEN TARGET   — internal link to a missing file.
        * BROKEN ANCHOR   — file exists, anchor doesn't.
        * BROKEN EXTERNAL — only when check_external=True; HEAD-probe
                            failed or returned non-2xx/3xx.
    """
    files = list(_iter_scanned(repo_root))
    if verbose:
        sys.stderr.write(
            f"scanning {len(files)} file(s) "
            "(README corpus + repo-root markdown + markdown beside source)...\n"
        )

    slug_cache: dict[Path, set[str]] = {}

    def slugs_for(path: Path) -> set[str]:
        ap = path.resolve()
        if ap not in slug_cache:
            slug_cache[ap] = _slug_index(path)
        return slug_cache[ap]

    broken_target: list[tuple[Path, int, str, str]] = []
    broken_anchor: list[tuple[Path, int, str, str]] = []
    broken_external: list[tuple[Path, int, str, str]] = []
    site_url_broken: list[tuple[Path, int, str, str]] = []

    for path in files:
        for line_no, dest in _scanned_destinations(repo_root, path):
            # Mustache-template placeholder anywhere in the destination
            # → render-time substitution, not a real link.  Skip.
            if _MUSTACHE_RE.search(dest):
                continue

            # This project's own published-site URLs, resolved offline to the
            # source page MkDocs builds them from, and their fragment graded
            # against that page's MkDocs slugs.  Checked BEFORE the
            # external guard below, which would skip them wholesale and let
            # dead ones on the repo's front page go unseen.  Inert unless
            # `mkdocs.yml` names a `site_url`.  `slugs_for` is NOT passed:
            # it is this gate's GitHub `-N` model, and MkDocs, not GitHub,
            # renders the published page.
            site_problems = _site_url_link_problems(repo_root, dest)
            if site_problems is not None:
                for problem in site_problems:
                    site_url_broken.append((path, line_no, dest, problem))
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

    total = (
        len(broken_target)
        + len(broken_anchor)
        + len(broken_external)
        + len(site_url_broken)
    )

    if site_url_broken:
        sys.stderr.write(
            f"\n{len(site_url_broken)} broken project-site URL(s) in README / "
            "repo-root markdown:\n\n"
        )
        for src, line_no, dest, problem in site_url_broken:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN SITE URL: {rel}:{line_no} -> {dest}\n"
                f"      ({problem})\n"
            )
        sys.stderr.write(
            "\nFix: this is a URL into THIS project's published documentation "
            "site, resolved offline against the source page MkDocs would build "
            "it from — repoint it at the page's current home. A `#fragment` "
            "must name a heading that page renders under MkDocs' slug rules "
            "(a repeated heading takes `_1`, not GitHub's `-1`). The trailing "
            "slash is not graded, and neither is whether the page it reaches "
            "is the right one. "
            "Remember that MkDocs publishes `X/index.md` and `X/README.md` at "
            "`X/`, so `X/index/`, `X/README/` and `X.md` are not URLs it "
            "serves.\n"
        )

    if broken_target:
        sys.stderr.write(
            f"\n{len(broken_target)} broken target file(s) in README / "
            "repo-root markdown links:\n\n"
        )
        for src, line_no, dest, target_rel in broken_target:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN TARGET: {rel}:{line_no} -> {dest}\n"
                f"      (missing: {target_rel})\n"
            )

    if broken_anchor:
        sys.stderr.write(
            f"\n{len(broken_anchor)} broken anchor link(s) in READMEs / "
            "repo-root markdown:\n\n"
        )
        for src, line_no, dest, target_rel in broken_anchor:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN ANCHOR: {rel}:{line_no} -> {dest}\n"
                f"      (target: {target_rel})\n"
            )
        sys.stderr.write(
            "\nFix: confirm the heading still exists in the target file and "
            "update the link, or rename the heading and re-link.  These files "
            "render on GitHub, so anchors follow GitHub's heading "
            "slugger: the visible title cased down with punctuation dropped "
            "and spaces hyphenated, and repeated headings disambiguated with "
            "`-1`, `-2`, ... on the second and later occurrences.  GitHub's "
            "`-N` suffix is NOT MkDocs' `_N` suffix — `#errors_1` is a "
            "docs-corpus anchor and will not resolve in a README.\n"
        )

    if broken_external:
        sys.stderr.write(
            f"\n{len(broken_external)} broken external URL(s) in READMEs / "
            "repo-root markdown:\n\n"
        )
        for src, line_no, dest, reason in broken_external:
            rel = src.relative_to(repo_root)
            sys.stderr.write(
                f"  BROKEN EXTERNAL: {rel}:{line_no} -> {dest}\n"
                f"      ({reason})\n"
            )

    if total == 0 and verbose:
        sys.stderr.write(
            "no broken links in the README corpus, repo-root markdown or "
            "markdown beside source.\n"
        )

    return total


def _display_target(target: Path, repo_root: Path) -> str:
    try:
        return str(target.relative_to(repo_root.resolve()))
    except ValueError:
        return str(target)


# --------------------------------------------------------------------------
# Self-tests — each fixture is a self-contained mini-repo.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent / "_test_fixtures" / "check_readme_links"
)


def _run_self_tests(verbose: bool = False) -> int:
    cases: list[tuple[str, int]] = [
        # (fixture-dir, expected-finding-count)
        # GitHub's duplicate-heading rule: `-N` from the second occurrence, and
        # a generated id is itself taken, so a later natural `errors-1` renders
        # as `errors-1-1`.
        ("github_dup_suffix_ok",             0),
        ("mkdocs_dup_suffix_broken",         1),  # `#errors_1` is MkDocs', not GitHub's
        ("explicit_id_brace_not_a_target",   1),  # `{#id}` is heading TEXT, not a target
        # Root markdown that is not a README, so every finding comes from the
        # root roster: a broken target and a broken cross-file anchor.
        ("root_markdown_broken_link",        2),
        # This project's own site URLs, resolved offline BEFORE the external
        # skip would drop them: one live, one dead.
        ("site_url_in_root_markdown",        1),
        # The redirect table's bare-URL bullets, which the shared extractor
        # does not read: a live site URL, a dead one and a github.com row.
        ("redirect_table_broken",            1),
    ]

    failures = 0
    for fixture, expected in cases:
        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            got = check(_SELF_TEST_FIXTURE_ROOT / fixture, verbose=False, check_external=False)
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

    # The rosters are GIT-TRACKED: an untracked scratch note carrying a broken
    # link must not red the gate on an author's machine while CI, on a clean
    # clone, stays green.
    ok_root = _SELF_TEST_FIXTURE_ROOT / "root_markdown_ok"
    scratch = ok_root / "znup0_untracked_scratch.md"
    scratch.write_text("[a link nobody tracked](znup0-no-such-file.md)\n", encoding="utf-8")
    saved_stderr = sys.stderr
    sys.stderr = _DevNull()
    try:
        findings_with_scratch = check(ok_root, verbose=False, check_external=False)
    finally:
        sys.stderr = saved_stderr
        scratch.unlink(missing_ok=True)
    if findings_with_scratch != 0:
        sys.stderr.write(
            "self-test FAIL: an untracked scratch document at the repo root "
            f"reded the gate (got {findings_with_scratch})\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: an untracked root scratch document cannot red the gate\n"
        )

    # The beside-source roster, against the real tree: it takes
    # `implementation/SECURITY.md` (cited from the root README, reached by no
    # other roster) and not an untracked note beside it.
    live_root = Path(__file__).resolve().parent.parent
    src_scratch = live_root / "implementation" / "i4nb2_untracked_scratch.md"
    src_scratch.write_text("# Untracked scratch\n", encoding="utf-8")
    try:
        source_roster = {
            p.relative_to(live_root).as_posix() for p in _iter_source_markdown(live_root)
        }
    finally:
        src_scratch.unlink(missing_ok=True)
    scratch_taken = f"implementation/{src_scratch.name}" in source_roster
    security_taken = "implementation/SECURITY.md" in source_roster
    if scratch_taken or not security_taken:
        sys.stderr.write(
            "self-test FAIL: the beside-source roster is not the tracked markdown "
            f"beside source (scratch taken: {scratch_taken}, "
            f"SECURITY.md taken: {security_taken})\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: the beside-source roster takes tracked markdown "
            "beside source and not an untracked note\n"
        )

    # A --repo-root outside any git work tree is a setup error: `main` exits 2
    # with a one-line message, never a traceback.  The ceiling stops git from
    # finding a repository above the temporary directory.
    with tempfile.TemporaryDirectory() as non_git_dir:
        non_git_root = Path(non_git_dir)
        (non_git_root / "mkdocs.yml").write_text("site_name: non-git\n", encoding="utf-8")
        saved_stderr = sys.stderr
        saved_ceiling = os.environ.get("GIT_CEILING_DIRECTORIES")
        sys.stderr = _DevNull()
        os.environ["GIT_CEILING_DIRECTORIES"] = str(non_git_root.resolve().parent)
        try:
            non_git_exit = main(["--repo-root", str(non_git_root)])
        finally:
            sys.stderr = saved_stderr
            if saved_ceiling is None:
                os.environ.pop("GIT_CEILING_DIRECTORIES", None)
            else:
                os.environ["GIT_CEILING_DIRECTORIES"] = saved_ceiling
    if non_git_exit != 2:
        sys.stderr.write(
            f"self-test FAIL: a non-git --repo-root exited {non_git_exit}, not 2\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write("self-test PASS: a non-git --repo-root is a setup error (exit 2)\n")

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        # `+ 3`: the three PASS lines after the fixture loop.
        sys.stderr.write(f"all {len(cases) + 3} self-tests passed.\n")
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:  # noqa: D401
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Validate links in every README.md in the repo. "
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

    try:
        findings = check(
            repo_root,
            verbose=args.verbose and not args.ci,
            check_external=check_external,
        )
    except RuntimeError as exc:
        # `git ls-files` failed: the rosters cannot be read, which is a setup
        # error rather than a finding.
        sys.stderr.write(f"error: {exc}\n")
        return 2
    return 0 if findings == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
