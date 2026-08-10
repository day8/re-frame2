#!/usr/bin/env python3
"""Validate links in every README.md — and in every repo-root markdown file.

Companion gate to `scripts/check_doc_slugs.py` (rf2-br5u7).  Where
`check_doc_slugs.py` covers the published docs corpus (docs/, spec/,
migration/, skills/, tools/*/spec/), this script covers the README.md
files that live alongside source code — adapters/, examples/,
testbeds/, tools/, etc.  These READMEs are read on GitHub and in the
local working tree but are **not** copied into the MkDocs site, so
they need their own anchor-correctness gate.

REPO-ROOT MARKDOWN IS THE SAME SURFACE (rf2-znup0).  `AGENTS.md`,
`CHANGELOG.md`, `CLAUDE.md`, `SKILL-REDIRECT.md` and `TESTING.md` sit
beside the root `README.md` this gate has always walked, appear nowhere
in `mkdocs.yml`, and are therefore rendered by GitHub exactly as the
READMEs are — yet `check_doc_slugs.py`'s roster (DEFAULT_ROOTS +
`tools/*/spec`) never opened them, so they had no link gate of any kind.
A mutation carrying a broken relative link AND a broken in-page anchor
was appended to `TESTING.md` and the docs gate still exited 0.

They belong HERE rather than in the docs gate for three reasons, and the
choice changes no finding on today's tree — it is decided on renderer
authority and scheduling, not on findings:

    * RENDERER.  The docs gate models MkDocs' `_N` duplicate-heading
      suffix; this one models GitHub's `-N`, and the two deliberately
      disagree because their renderers do (rf2-zzt2r).  Root markdown
      renders on GitHub, so `-N` is the rule that actually resolves in a
      browser.
    * NO DOUBLE-COVERAGE.  The root `README.md` is already in this gate's
      roster.  Adding root markdown to the docs gate instead would cover
      that one file twice, under two conflicting duplicate-suffix rules.
    * SCHEDULING.  `verify-readme-links` runs `--ci` on EVERY pull request
      (test.yml's trigger is unfiltered and the job carries no surface
      guard), where the docs gate is documentation-surface-gated.  Root
      markdown gets the stronger of the two lanes for free.

The root roster is GIT-TRACKED and NON-RECURSIVE (see `_iter_root_markdown`),
so it cannot grow into `implementation/`, `tools/`, `node_modules` or any
generated tree, and an untracked scratch file dropped at the repo root
cannot red the gate on an author's machine (the rf2-k30r7 lesson).

What this validates per file:

    * BROKEN TARGET   — internal link points at a .md file (or any
                        repo-internal path) that does not exist.
    * BROKEN ANCHOR   — target file exists but the #anchor isn't a real
                        slug as **GitHub** would emit it.  These READMEs
                        are rendered by GitHub, so GitHub's heading
                        slugger is the authority here — NOT MkDocs'
                        (rf2-zzt2r).  Two rules make up a heading id:

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
    * Markdown BELOW the repo root that is not a README.md.  The root
      roster is deliberately non-recursive; everything deeper is either
      the docs gate's or a README this gate already walks.

THE MAYOR-LOOP COMMAND FILES ARE A THIRD, DIFFERENT SURFACE (rf2-1yy75).
`.claude/commands/*.md` is what the mayor loop actually EXECUTES;
`docs/the-mayor-method/**` DESCRIBES the same practice.  They drifted for
hours in rf2-40d9d — the method doc was corrected, the command file kept
the retired reaping rule, and the loop destroyed live worker gate runs
while following the file that runs rather than the file that describes.
Semantic agreement between the two is not checkable and rf2-1yy75 says
so.  What IS checkable is the mechanical half: the command files name
method docs BY PATH and BY FILENAME, so a rename breaks them silently.
This gate resolves those references — see `_check_command_file_refs`.

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
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Iterable

# Reuse the docs-gate's BASE slugifier and inline-extraction helpers
# (rf2-br5u7).  This is a deliberate direct import rather than a
# separately-factored helper module: the imported symbols are the
# base-slug source of truth, and routing them via a third file would
# dilute that.
#
# Sharing SLUGIFY across two different renderers is a measured decision,
# not an assumption (rf2-zzt2r).  pymdownx's slugify and GitHub's
# slugger were diffed over every heading in the in-scope README corpus:
# 545 headings, 0 divergences.  One divergence class is known and
# currently unexercised by any live README — heading text shaped like an
# HTML tag: pymdownx strips `<name>` entirely, GitHub escapes it and
# keeps `name`.  The `mkdocs_slug_anchor_ok` /
# `github_slug_anchor_broken` fixtures pin that gap so it stays visible;
# closing it needs a GitHub-specific base slugifier, which is out of
# scope until a real README heading exercises it.
#
# What is NOT shared is the duplicate-heading suffix — see `_slug_index`.
#
# `_extract_links` is imported rather than reimplemented (rf2-vpc4c). It used
# to be a verbatim copy here, which meant this gate inherited the same
# line-wrap blindness — a link whose `](target#anchor)` fell on the following
# line was never validated in EITHER corpus. One extractor, one fix.
try:
    from check_doc_slugs import (
        SLUGIFY,
        SLUG_SEP,
        _HEADING_RE,
        _HTML_ANCHOR_RE,
        _extract_links,
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

    Git tracking, not a filesystem walk — the roster discipline both rosters in
    this gate depend on (rf2-k30r7 / rf2-znup0).  Note that git pathspecs are
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
    """Yield every GIT-TRACKED markdown file sitting AT the repo root (rf2-znup0).

    Two properties make this roster safe to add, and both are structural
    rather than a list somebody has to maintain:

    NON-RECURSIVE.  `git ls-files` pathspecs use fnmatch without
    FNM_PATHNAME, so `*` matches `/` and a bare `*.md` pathspec would
    return markdown at every depth — the whole of `docs/`, `implementation/`,
    `tools/` and every generated tree.  Entries containing a separator are
    therefore dropped, leaving exactly the files a reader sees when they open
    the repository on GitHub.  The roster cannot grow silently: a new
    directory is invisible to it by construction, while a genuinely new root
    document (the next `TESTING.md`) is picked up the moment it is tracked —
    which is the failure this closes, so an explicit six-name list would just
    re-open it one file later.

    GIT-TRACKED, NOT A FILESYSTEM WALK.  A `.glob("*.md")` would scan an
    author's untracked scratch notes, so a stray root `PLAN.md` with a
    speculative link could red the gate on one machine while CI — which runs
    on a clean clone — stayed green.  That is the rf2-k30r7 defect, recorded
    against this gate's sibling; the roster is Git tracking so it cannot
    recur here.

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


def _iter_scanned(repo_root: Path) -> Iterable[Path]:
    """Yield every file this gate validates: the README corpus plus root markdown.

    Deduplicated by resolved path, because the repo-root `README.md` is a
    member of both rosters.
    """
    seen: set[Path] = set()
    for path in _iter_readmes(repo_root):
        ap = path.resolve()
        if ap in seen:
            continue
        seen.add(ap)
        yield path
    for path in _iter_root_markdown(repo_root):
        ap = path.resolve()
        if ap in seen:
            continue
        seen.add(ap)
        yield path


# --------------------------------------------------------------------------
# rf2-1yy75 — the mayor-loop command files and the method docs they name.
#
# WHY THESE REFERENCES NEED A RESOLVER RATHER THAN A ROSTER ENTRY.  The obvious
# fix — adding `.claude` to `check_doc_slugs.py`'s `DEFAULT_ROOTS` — validates
# NOTHING, and that is measured rather than argued: the five tracked command
# files (70 lines) contain ZERO markdown links, ZERO headings, and therefore
# ZERO rendered fragment ids.  Every reference in them is a bare or BACKTICKED
# path (`` `docs/the-mayor-method/bootstrap.md` ``, and a bare
# `dispatch-prompt-template.md` in two more files), and both link gates
# deliberately mask inline code and only ever examine `[text](dest)` links
# (rf2-mqv8s).  A roster entry would scan five more files and check nothing in
# them — today, and equally on the day a method doc is renamed.
#
# WHY THIS GATE RATHER THAN THE DOCS GATE.  The same three criteria rf2-znup0
# weighed for repo-root markdown, and the answer is decided on renderer
# authority and scheduling, not on findings:
#
#   * RENDERER.  `.claude/**` appears nowhere in `mkdocs.yml` (`docs_dir: docs`),
#     so MkDocs never renders it — and it is not documentation a GitHub reader
#     browses either: it is prompt text the Claude Code CLI feeds to a model
#     verbatim.  With no headings and no links there is no anchor to resolve at
#     all, so the `_N`-versus-`-N` duplicate-suffix rule the two gates
#     deliberately disagree on (rf2-zzt2r) simply does not arise here.  Only
#     target RESOLUTION is meaningful, which is why this lands as a reference
#     resolver and not as a corpus root in either gate.
#   * NO DOUBLE-COVERAGE.  Neither gate covers `.claude/**` today, so the choice
#     cannot double-cover a file the way widening the docs roster to the repo
#     root would have.
#   * SCHEDULING.  `verify-readme-links` runs `--ci` on EVERY pull request
#     (test.yml's PR trigger is unfiltered and the job carries no surface
#     guard), while the docs gate is documentation-surface-gated and
#     `.claude/**` is on no surface docs.yml filters for.  A PR touching only a
#     command file would not have run the docs gate at all.  Hosting the
#     resolver here gets the stronger lane with no workflow change.
#
# The mechanism mirrors `check_doc_slugs.py`'s source-comment scan (rf2-57k74):
# resolve path-shaped SUBSTRINGS in files that are not part of any markdown
# corpus.  Inline code is deliberately NOT masked — for a file consumed verbatim
# as prompt text a backticked path is exactly as load-bearing as a bare one, and
# masking it is what makes the existing gates blind here.
# --------------------------------------------------------------------------

# The command-file roster.  A GLOB, never a hand-list of the five files that
# exist today: a hand-list re-opens this gap one command file later.
COMMAND_FILE_PATHSPEC = ".claude/commands/*.md"

# A markdown path reference, in either of the two forms the command files use:
# repo-root-relative (`docs/the-mayor-method/bootstrap.md`) or a bare filename
# (`dispatch-prompt-template.md`).  The trailing look-ahead keeps `foo.mdx` from
# matching as `foo.md`; the leading look-behind keeps the scan from starting
# mid-token.  A glob such as `ladder-*.md` matches nothing — `*` is outside the
# character class — so prose about a FAMILY of files is not a reference.
_COMMAND_DOC_REF_RE = re.compile(
    r"(?<![A-Za-z0-9_/.-])((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\.md)(?![A-Za-z0-9_-])"
)

# `/ai/` is gitignored at the repo root by design (see CLAUDE.md), and the
# repo's own instructions send agents to write findings there.  A reference into
# it can never resolve against a tracked roster, so it is skipped rather than
# reported — the alternative is a gate that reds on following the house rules.
_UNTRACKED_REF_ROOTS = frozenset({"ai"})


def _iter_command_files(repo_root: Path) -> Iterable[Path]:
    """Yield every GIT-TRACKED mayor-loop command file (rf2-1yy75).

    Tracked, not walked, for the rf2-k30r7 reason and one specific to this
    tree: `/.claude/worktrees/` is gitignored, and the tooling that uses it
    puts whole repository checkouts there.  A `rglob("*.md")` under `.claude`
    would descend into one and scan thousands of files that are not repository
    content — slow on the author's machine, invisible on CI's clean clone.
    """
    for rel in _git_ls_files(repo_root, COMMAND_FILE_PATHSPEC):
        path = repo_root / rel
        # A tracked path can be absent from the working tree mid-rename; the
        # index still lists it. Skip rather than crash the whole scan.
        if path.is_file():
            yield path


def _tracked_markdown_index(repo_root: Path) -> tuple[frozenset[str], frozenset[str]]:
    """Return (tracked repo-relative .md paths, tracked .md basenames).

    Both halves are needed because the command files reference documents both
    ways.  Resolution is against the TRACKED set rather than the filesystem so
    the verdict is identical on the author's machine and on CI's clean clone.
    """
    rels = _git_ls_files(repo_root, "*.md")
    return frozenset(rels), frozenset(rel.rsplit("/", 1)[-1] for rel in rels)


def _check_command_file_refs(
    repo_root: Path,
    *,
    command_files: Iterable[Path],
    tracked_markdown: tuple[frozenset[str], frozenset[str]] | None = None,
) -> list[tuple[Path, int, str, str]]:
    """Resolve every markdown reference in the mayor-loop command files.

    Returns (command-file, line-no, reference, reason) per unresolved
    reference.  A path-form reference must name a tracked file at exactly that
    repo-root-relative path — which is also the only form that means anything
    in a file executed from the repo root, so requiring it is a feature.  A
    bare-filename reference must match the basename of at least one tracked
    markdown file; that is the weaker of the two rules, and it is the one that
    covers `dispatch-prompt-template.md`, the most rename-fragile reference in
    the set precisely because it carries no directory to anchor it.

    `command_files` and `tracked_markdown` are parameters, not module state, so
    the self-tests drive the mechanism with fixture inputs instead of depending
    on the production command files being present or absent.
    """
    command_files = list(command_files)
    if not command_files:
        return []
    if tracked_markdown is None:
        tracked_markdown = _tracked_markdown_index(repo_root)
    tracked_paths, tracked_names = tracked_markdown

    broken: list[tuple[Path, int, str, str]] = []
    for src in command_files:
        text = src.read_text(encoding="utf-8", errors="replace")
        for line_no, line in enumerate(text.splitlines(), start=1):
            for match in _COMMAND_DOC_REF_RE.finditer(line):
                ref = match.group(1)
                segments = ref.split("/")
                if segments[0] in _UNTRACKED_REF_ROOTS:
                    continue
                if len(segments) > 1:
                    if ref not in tracked_paths:
                        broken.append(
                            (src, line_no, ref, "no tracked file at that path")
                        )
                elif ref not in tracked_names:
                    broken.append(
                        (src, line_no, ref, "no tracked markdown file of that name")
                    )
    return broken


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
    the two (rf2-zzt2r).

    The `while` loop is load-bearing, not defensive: a document with
    `## Errors`, `## Errors`, `## Errors-1` renders ids `errors`,
    `errors-1`, and `errors-1-1` — the third heading's natural slug
    collides with the second's generated one and gets bumped again.
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
    resolves in a browser (rf2-zzt2r).

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
        # (rf2-w6ltl, mirroring rf2-ru0wg in check_doc_slugs.py).
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


def check(
    repo_root: Path,
    verbose: bool = False,
    check_external: bool = False,
    *,
    command_files: Iterable[Path] | None = None,
) -> int:
    """Validate every README.md plus every repo-root markdown file.  Return finding count.

    Findings:
        * BROKEN TARGET   — internal link to a missing file.
        * BROKEN ANCHOR   — file exists, anchor doesn't.
        * BROKEN EXTERNAL — only when check_external=True; HEAD-probe
                            failed or returned non-2xx/3xx.
        * BROKEN COMMAND-FILE REFERENCE — (rf2-1yy75) a `.md` path or filename
                            named by a mayor-loop command file
                            (`.claude/commands/*.md`) resolves to no tracked
                            document.  These files are executed, not rendered;
                            see `_check_command_file_refs`.

    `command_files` defaults to the tracked production roster; the self-tests
    pass fixture inputs so a fixture never depends on it.
    """
    files = list(_iter_scanned(repo_root))
    if command_files is None:
        command_files = list(_iter_command_files(repo_root))
    else:
        command_files = list(command_files)
    if verbose:
        sys.stderr.write(
            f"scanning {len(files)} file(s) (README corpus + repo-root markdown)"
            f" + {len(command_files)} mayor-loop command file(s)...\n"
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

    broken_command_refs = _check_command_file_refs(
        repo_root, command_files=command_files
    )

    total = (
        len(broken_target)
        + len(broken_anchor)
        + len(broken_external)
        + len(broken_command_refs)
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
            "docs-corpus anchor and will not resolve in a README (rf2-zzt2r).\n"
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

    if broken_command_refs:
        sys.stderr.write(
            f"\n{len(broken_command_refs)} unresolved reference(s) in the "
            "mayor-loop command files (rf2-1yy75):\n\n"
        )
        for src, line_no, ref, reason in broken_command_refs:
            rel = src.relative_to(repo_root.resolve()).as_posix()
            sys.stderr.write(
                f"  BROKEN COMMAND-FILE REFERENCE: {rel}:{line_no} -> {ref}\n"
                f"      ({reason})\n"
            )
        sys.stderr.write(
            "\nFix: `.claude/commands/*.md` is what the mayor loop EXECUTES and "
            "`docs/the-mayor-method/**` is what DESCRIBES it — the two drifting "
            "apart cost a day of destroyed worker gate runs (rf2-40d9d).  If a "
            "method doc moved, update the command file that names it; if a "
            "command file names a document that never existed, correct the "
            "reference.  Paths in these files are read from the repo root, so "
            "write them repo-root-relative.\n"
        )

    if total == 0 and verbose:
        sys.stderr.write(
            "no broken README / repo-root markdown links, and every mayor-loop "
            "command-file reference resolves.\n"
        )

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
        # Known base-slug gap (rf2-zzt2r): heading text shaped like an HTML
        # tag is the one measured divergence between the shared SLUGIFY and
        # GitHub's slugger, and no live README heading exercises it.  These
        # two pin the CURRENT behaviour so the gap stays visible rather than
        # silently drifting; they are not an endorsement of the MkDocs rule.
        ("mkdocs_slug_anchor_ok",            0),  # `<name>` stripped (shared SLUGIFY)
        ("github_slug_anchor_broken",        1),  # `<name>` kept — GitHub's real shape
        ("mustache_placeholder_ignored",     0),  # rf2-br5u7 false-positive guard
        # GitHub duplicate-heading rule — `-N` from the second occurrence
        # (rf2-zzt2r).  Positive, wrong-separator negative, out-of-range
        # negative, and the collision re-bump.
        ("github_dup_suffix_ok",             0),  # errors / errors-1 / errors-2
        ("mkdocs_dup_suffix_broken",         1),  # `#errors_1` is MkDocs', not GitHub's
        ("dup_suffix_out_of_range_broken",   1),  # `#errors-2` with only two headings
        ("github_dup_collision_bump_ok",     0),  # `## Errors-1` after two `## Errors`
        ("inline_code_link_ignored",         0),  # fence + inline-code guard
        # rf2-skpf — the shared extractor's block bound and multiline
        # code-span mask reach this gate too. Expects 1, not 0: the finding is
        # a REAL broken wrapped link, so the count moves if a phantom is
        # invented (up) or if wrapped links stop being seen (down).
        ("block_bound_link_ignored",         1),
        ("external_link_skipped_by_default", 0),  # off without --check-external
        ("explicit_id_full_title_ok",        0),  # `{#id}` is heading TEXT (rf2-w6ltl)
        ("explicit_id_brace_not_a_target",   1),  # ...so the brace id resolves nowhere
        # rf2-znup0 — repo-root markdown that is NOT a README. Neither fixture
        # contains a README.md at all, so every finding (and every non-finding)
        # comes from the root roster and nothing else. Both directions:
        ("root_markdown_ok",                 0),  # correct root links stay silent
        ("root_markdown_broken_link",        2),  # broken target + broken anchor
        # rf2-1yy75 — the mayor-loop command files. Neither fixture contains a
        # README.md or any root markdown, so every count below comes from the
        # command-file resolver and nothing else. Both directions, and both
        # reference forms the live command files actually use:
        ("command_refs_ok",                  0),  # backticked path + bare filename resolve;
                                                  # a `ladder-*.md` glob and an `ai/` link
                                                  # are not references, and this 0 pins it
        ("command_refs_broken",              2),  # one drifted path form + one drifted bare
                                                  # filename, alongside a reference that
                                                  # still resolves — so 2 fails both ways
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

    # rf2-znup0 — the root roster's two structural properties, asserted
    # directly rather than only through a fixture's aggregate count.
    #
    # NON-RECURSIVE. `git ls-files -- '*.md'` matches at EVERY depth (git
    # pathspecs are fnmatch without FNM_PATHNAME, so `*` crosses `/`), which is
    # the naive addition that would drag `docs/`, `implementation/` and every
    # generated tree into this gate. `root_markdown_ok/sub/other.md` is a
    # tracked markdown file one level down: it must resolve as a link TARGET
    # while never entering the roster itself.
    #
    # GIT-TRACKED. An untracked scratch document at the repo root must be
    # invisible. The tooth is causal in both directions, mirroring rf2-k30r7:
    # the same file is first proven poisonous when the roster does reach it,
    # then proven absent from the tracked roster — a filesystem walk passes the
    # first half and fails the second.
    ok_root = _SELF_TEST_FIXTURE_ROOT / "root_markdown_ok"
    root_roster = {
        p.relative_to(ok_root).as_posix() for p in _iter_root_markdown(ok_root)
    }
    if root_roster != {"CHANGELOG.md", "TESTING.md"}:
        sys.stderr.write(
            "self-test FAIL: root roster is not exactly the tracked root markdown "
            f"(got {sorted(root_roster)})\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: root roster is non-recursive — tracked markdown one "
            "level down resolves as a target but is not scanned\n"
        )

    scratch = ok_root / "znup0_untracked_scratch.md"
    try:
        scratch.write_text(
            "# Untracked scratch\n\n"
            "[a link nobody tracked](znup0-no-such-file.md)\n",
            encoding="utf-8",
        )
        # The POISON half: the scratch carries a genuinely broken target, and a
        # filesystem-walk roster would take it. If either stopped being true the
        # green result below would prove nothing.
        scratch_is_poisonous = not (ok_root / "znup0-no-such-file.md").exists()
        walk_roster = {p.name for p in ok_root.glob("*.md")}
        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            findings_with_scratch = check(
                ok_root, verbose=False, check_external=False
            )
        finally:
            sys.stderr = saved_stderr
        tracked_roster = {
            p.relative_to(ok_root).as_posix() for p in _iter_root_markdown(ok_root)
        }
    finally:
        scratch.unlink(missing_ok=True)

    if not (scratch_is_poisonous and scratch.name in walk_roster):
        sys.stderr.write(
            "self-test FAIL: the untracked-scratch fixture is not actually "
            "poisonous to a walk-based roster, so the tracking tooth proves "
            f"nothing (broken-target={scratch_is_poisonous}, "
            f"walk-roster={sorted(walk_roster)})\n"
        )
        failures += 1
    elif scratch.name in tracked_roster:
        sys.stderr.write(
            "self-test FAIL: an untracked scratch document at the repo root "
            f"reached the roster (got {sorted(tracked_roster)})\n"
        )
        failures += 1
    elif findings_with_scratch != 0:
        sys.stderr.write(
            "self-test FAIL: the untracked scratch reded the gate anyway, so the "
            f"tracked roster is not what is consulted (got {findings_with_scratch})\n"
        )
        failures += 1
    elif "TESTING.md" not in tracked_roster:
        sys.stderr.write(
            "self-test FAIL: the roster lost a tracked root document while "
            f"excluding the untracked one (got {sorted(tracked_roster)})\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: an untracked root scratch document cannot red the "
            "gate while tracked root markdown stays covered\n"
        )

    # rf2-1yy75 — the command-file roster is GIT-TRACKED, for the rf2-k30r7
    # reason and for one specific to this tree: `/.claude/worktrees/` is
    # gitignored and holds whole repository checkouts, so a filesystem walk
    # under `.claude` would scan content that is not repository content at all.
    # The tooth is causal in both directions, mirroring the root-roster tooth
    # above: the scratch command file is first proven poisonous when a roster
    # does reach it, then proven absent from the tracked roster.
    cmd_ok = _SELF_TEST_FIXTURE_ROOT / "command_refs_ok"
    cmd_scratch = cmd_ok / ".claude" / "commands" / "untracked-scratch.md"
    try:
        cmd_scratch.write_text(
            "---\n"
            "description: Untracked scratch command file.\n"
            "---\n"
            "Names a method doc nobody ever wrote: "
            "`docs/the-mayor-method/1yy75-no-such-doc.md`.\n",
            encoding="utf-8",
        )
        scratch_is_poisonous = not (
            cmd_ok / "docs" / "the-mayor-method" / "1yy75-no-such-doc.md"
        ).exists()
        walk_roster = {
            p.relative_to(cmd_ok).as_posix()
            for p in cmd_ok.glob(".claude/commands/*.md")
        }
        saved_stderr = sys.stderr
        sys.stderr = _DevNull()
        try:
            findings_with_scratch = check(cmd_ok, verbose=False, check_external=False)
        finally:
            sys.stderr = saved_stderr
        tracked_roster = {
            p.relative_to(cmd_ok).as_posix() for p in _iter_command_files(cmd_ok)
        }
    finally:
        cmd_scratch.unlink(missing_ok=True)

    scratch_rel = ".claude/commands/untracked-scratch.md"
    live_rel = ".claude/commands/mayor-example.md"
    if not (scratch_is_poisonous and scratch_rel in walk_roster):
        sys.stderr.write(
            "self-test FAIL: the untracked command-file fixture is not actually "
            "poisonous to a walk-based roster, so the tracking tooth proves "
            f"nothing (unresolvable-reference={scratch_is_poisonous}, "
            f"walk-roster={sorted(walk_roster)})\n"
        )
        failures += 1
    elif scratch_rel in tracked_roster:
        sys.stderr.write(
            "self-test FAIL: an untracked scratch command file reached the "
            f"roster (got {sorted(tracked_roster)})\n"
        )
        failures += 1
    elif findings_with_scratch != 0:
        sys.stderr.write(
            "self-test FAIL: the untracked scratch command file reded the gate "
            f"anyway, so the tracked roster is not what is consulted "
            f"(got {findings_with_scratch})\n"
        )
        failures += 1
    elif live_rel not in tracked_roster:
        sys.stderr.write(
            "self-test FAIL: the roster lost the tracked command file while "
            f"excluding the untracked one (got {sorted(tracked_roster)})\n"
        )
        failures += 1
    elif verbose:
        sys.stderr.write(
            "self-test PASS: an untracked scratch command file cannot red the "
            "gate while tracked command files stay covered\n"
        )

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
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
