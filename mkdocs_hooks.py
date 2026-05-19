# MkDocs build-time hooks (rf2-qvlf, rf2-jtkc, rf2-wjfzn).
#
# The narrative guide lives at docs/guide/*.md. Many guide pages cross-link
# to the normative spec under spec/*.md using the path ../../spec/ — that
# is correct when the guide is browsed via the GitHub source tree (where
# spec/ lives at the repo root, two levels up from docs/guide/).
#
# At MkDocs build time the spec tree is staged under docs/spec/ (see the
# `Stage spec/ into docs/spec/` step in .github/workflows/docs.yml, and
# the equivalent local `cp -r spec docs/spec`). From a guide page the
# correct relative path to the staged spec is therefore ../spec/, not
# ../../spec/. Without rewriting, every guide -> spec link 404s on the
# published site and emits a WARNING during `mkdocs build`.
#
# Rather than rewrite the source files (which would break GitHub
# source-tree browsing for anyone reading the guide on github.com), we
# rewrite the markdown at build time. Source on disk remains correct for
# both contexts.
#
# Rewrite cases:
#
#   1. guide -> spec      ../../spec/       -> ../spec/
#                         (spec is staged into docs/spec/, so it IS in the
#                         site; the relative-path depth changes)
#
#   2. cross-tree refs to trees that are NOT staged into the site:
#        - examples/   (CLJS apps; not docs to render)
#        - root README.md, CHANGELOG.md, VERSION, TESTING.md
#        - .github/    (CI workflows; not docs)
#        - implementation/   (CLJS source; not docs)
#        - tools/      (tool source + per-tool spec; not staged into docs/)
#        - skills/X/Y  (skill internals — references/, SKILL.md, etc; not
#                       staged. Only the single-page summary docs/skills/X.md
#                       is staged via the Skills nav section.)
#      These are rewritten to absolute GitHub blob URLs so the published
#      site links to the source-tree view on github.com. Source files
#      keep their GitHub-friendly relative paths.
#
#   3. directory-style links to in-tree pages:
#        - guide pages link to ../../skills/X/ (the source-tree directory)
#          where docs/skills/X.md exists as the published summary — rewrite
#          to ../skills/X.md so the published site lands on the summary.
#        - guide sub-chapter pages link to ../../causa/ where the published
#          site's causa section opens at causa/index.md — rewrite the bare
#          directory link to ../../causa/index.md.
#        - spec/SPEC-AUTHORING.md links to conformance/ (sibling directory
#          of spec/, staged into docs/spec/conformance/) — rewrite the bare
#          directory link to conformance/README.md.
#
# Pre-build staging (rf2-qw5mh).
#
# The published site needs spec/ and migration/ to live inside docs_dir so
# MkDocs can see them. CI did this via a shell step (.github/workflows/docs.yml:
# "Stage spec/ + migration/ into docs/"). Locally, a contributor running
# `mkdocs build --strict` without that step would see dozens of WARNINGs
# about missing spec/*.md and migration/*.md targets — link-rot that's not
# actually rot, just a missing staging step.
#
# We promote the staging into this hook (on_pre_build), so that ANY local
# or CI invocation of mkdocs (build, serve, --strict) auto-stages the two
# trees. CI keeps its explicit step as a redundant belt-and-braces; both
# are idempotent (rm -rf + copy). The staged dirs are .gitignored.

import re
import shutil
from pathlib import Path

GH_BLOB_BASE = "https://github.com/day8/re-frame2/blob/main"

# Trees that live at the repo root but need to render inside the site.
# Source dir (repo root) -> staged dir (under docs_dir).
_STAGE = (
    ("spec", "spec"),
    ("migration", "migration"),
)


def on_pre_build(config):
    """Mirror spec/ and migration/ into docs_dir before MkDocs scans files.

    Idempotent: removes the existing staged copy first. The staged paths
    are gitignored (see .gitignore: /docs/spec/, /docs/migration/) so this
    never dirties the worktree. CI runs the equivalent shell step in
    .github/workflows/docs.yml; both are safe to coexist.
    """
    docs_dir = Path(config["docs_dir"])
    repo_root = docs_dir.parent
    for src_name, dest_name in _STAGE:
        src = repo_root / src_name
        if not src.is_dir():
            # Source tree absent — skip silently. A clean checkout always
            # has both; the guard keeps the hook safe in unusual layouts.
            continue
        dest = docs_dir / dest_name
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(src, dest)

# Case 1a: guide/* and skills/* pages link to spec via ../../spec/ — collapse
# one level. Both trees live at docs/<tree>/X.md (depth 2 below repo root),
# so from the source tree the correct ref to spec/ is ../../spec/, while in
# the staged docs_dir (where spec/ is copied to docs/spec/) the correct ref
# is ../spec/. The rewrite is identical for both trees because they share
# the same depth.
_GUIDE_TO_SPEC = re.compile(r'\]\(\.\./\.\./spec/')

# Case 1a-deep: chapter sub-pages live at docs/guide/<chapter-dir>/X.md (depth
# 3 below repo root). From the source tree the correct ref to spec/ is
# ../../../spec/; in the staged docs_dir (docs/spec/) the correct ref is
# ../../spec/. The rewrite collapses one level, same as the depth-2 case.
_GUIDE_DEEP_TO_SPEC = re.compile(r'\]\(\.\./\.\./\.\./spec/')

# Case 1b: docs-root pages (e.g. docs/release-process.md) link to spec via
# ../spec/ — correct for the GitHub source tree (where spec/ lives at the
# repo root, one level up from docs/). In the staged tree spec/ is copied
# into docs/spec/, so from a docs-root page the correct path is plain
# spec/ (one fewer ../).
_DOCSROOT_TO_SPEC = re.compile(r'\]\(\.\./spec/')

# Case 1b-mig: docs-root pages link to the migration corpus via
# ../migration/ — correct for the source tree (migration/ at the repo
# root). The staged tree puts it at docs/migration/, so from a docs-root
# page the correct path is plain migration/ (one fewer ../).
_DOCSROOT_TO_MIGRATION = re.compile(r'\]\(\.\./migration/')

# Case 1c: spec/* pages link to docs/-root pages (e.g. release-process.md)
# via ../docs/ — correct for the GitHub source tree (where docs/ lives at
# the repo root, one level up from spec/). In the staged tree spec/X.md is
# at docs/spec/X.md and docs-root pages are at docs/X.md, so the correct
# relative path from spec/ is ../release-process.md, i.e. ../ (no docs/
# segment).
_SPEC_TO_DOCSROOT = re.compile(r'\]\(\.\./docs/')

# Case 3a: directory-style ../../skills/X/ from guide pages — rewrite to the
# in-tree summary page ../skills/X.md (one level up from docs/guide/X.md is
# docs/, and docs/skills/X.md is the published nav entry). Only the bare
# directory form is matched; sub-paths fall through to the GitHub-URL rewrite.
_GUIDE_SKILL_DIR = re.compile(r'\]\(\.\./\.\./skills/([A-Za-z0-9._-]+)/\)')

# Case 3b: directory-style ../../causa/ from guide sub-chapter pages — the
# published causa section opens at docs/causa/index.md. Rewrite the bare
# directory link to the explicit index file. Source path is unchanged on
# GitHub (where a directory link opens the directory listing as the user
# expects).
_GUIDE_CAUSA_DIR = re.compile(r'\]\(\.\./\.\./causa/\)')

# Case 3c: directory-style conformance/ from spec/SPEC-AUTHORING.md — the
# conformance corpus is staged at docs/spec/conformance/, with README.md as
# its index. MkDocs needs the explicit .md target.
_SPEC_CONFORMANCE_DIR = re.compile(r'\]\(conformance/\)')

# Case 2: cross-tree refs that don't exist in the staged docs tree.
# These rewrites apply to ALL pages (guide/, spec/, and docs/-root pages
# like release-process.md), since each tree references examples/, root
# README.md, .github/, CHANGELOG.md, VERSION, and implementation/ from
# its own depth in the source layout.
#
# From guide/ source (docs/guide/X.md),                path to repo-root: ../../
# From spec/  source (spec/X.md, staged at docs/spec/X.md):               ../
# From docs/  source (docs/X.md, e.g. release-process.md):                ../
#
# Spec-depth and docs-depth share the same ../prefix, so a single ../-rule
# covers both. The guide-depth rule (../../) handles the third case.
#
# All depths are rewritten to the same absolute GitHub URL.
_REWRITES = (
    # examples/ tree (anchor or no anchor; trailing path captured greedily
    # up to the closing paren or whitespace).
    (re.compile(r'\]\(\.\./\.\./examples/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/examples/\1)'),
    (re.compile(r'\]\(\.\./examples/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/examples/\1)'),
    # root README.md
    (re.compile(r'\]\(\.\./\.\./README\.md(#[^)\s]*)?\)'),
     rf']({GH_BLOB_BASE}/README.md\1)'),
    (re.compile(r'\]\(\.\./README\.md(#[^)\s]*)?\)'),
     rf']({GH_BLOB_BASE}/README.md\1)'),
    # .github/ tree (workflows, scripts, anything under .github/).
    (re.compile(r'\]\(\.\./\.\./\.github/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/.github/\1)'),
    (re.compile(r'\]\(\.\./\.github/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/.github/\1)'),
    # root CHANGELOG.md
    (re.compile(r'\]\(\.\./\.\./CHANGELOG\.md(#[^)\s]*)?\)'),
     rf']({GH_BLOB_BASE}/CHANGELOG.md\1)'),
    (re.compile(r'\]\(\.\./CHANGELOG\.md(#[^)\s]*)?\)'),
     rf']({GH_BLOB_BASE}/CHANGELOG.md\1)'),
    # root VERSION file
    (re.compile(r'\]\(\.\./\.\./VERSION\)'),
     rf']({GH_BLOB_BASE}/VERSION)'),
    (re.compile(r'\]\(\.\./VERSION\)'),
     rf']({GH_BLOB_BASE}/VERSION)'),
    # implementation/ tree (source files; not docs to render).
    (re.compile(r'\]\(\.\./\.\./implementation/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/implementation/\1)'),
    (re.compile(r'\]\(\.\./implementation/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/implementation/\1)'),
    # tools/ tree (per-tool source + per-tool spec/; not staged into docs/).
    # The per-tool spec/* lives with its artefact (lockstep contract) per
    # spec/Ownership.md §"Canonical homes outside /spec"; rewriting to a
    # GitHub URL preserves both the in-source and on-site link targets.
    (re.compile(r'\]\(\.\./\.\./tools/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/tools/\1)'),
    (re.compile(r'\]\(\.\./tools/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/tools/\1)'),
    # skills/X/Y (skill internals — references/, SKILL.md, etc.). The single
    # per-skill summary page docs/skills/X.md is staged via the Skills nav;
    # everything else under skills/ is repo-only. NOTE: this MUST run after
    # the case-3a rewrite that turns guide-page ../../skills/X/ (bare dir)
    # into ../skills/X.md (in-tree summary).
    (re.compile(r'\]\(\.\./\.\./skills/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/skills/\1)'),
    (re.compile(r'\]\(\.\./skills/([^)\s]*)\)'),
     rf']({GH_BLOB_BASE}/skills/\1)'),
    # root TESTING.md (canonical test-matrix; not staged into docs/).
    (re.compile(r'\]\(\.\./\.\./TESTING\.md(#[^)\s]*)?\)'),
     rf']({GH_BLOB_BASE}/TESTING.md\1)'),
    (re.compile(r'\]\(\.\./TESTING\.md(#[^)\s]*)?\)'),
     rf']({GH_BLOB_BASE}/TESTING.md\1)'),
)


def on_page_markdown(markdown, page, config, files):
    """Rewrite cross-tree links in guide/*, skills/*, and spec/* pages.

    See the module docstring for the full rewrite catalogue. Order matters:
    the in-tree directory-style rewrites (case 3) MUST run before the
    GitHub-URL rewrites (case 2), so that e.g. ../../skills/X/ collapses to
    ../skills/X.md (in-tree summary) instead of expanding to a GitHub URL
    for the directory listing.
    """
    src = page.file.src_path.replace('\\', '/')

    if src.startswith('guide/') or src.startswith('skills/'):
        # Choose by source depth. A guide sub-chapter at
        # docs/guide/<chapter-dir>/X.md is depth 3; its source spec ref is
        # ../../../spec/ and the staged path is ../../spec/. Plain
        # docs/guide/X.md is depth 2; ../../spec/ -> ../spec/.
        # Applying both rules unconditionally would let the depth-2 rule
        # re-rewrite the depth-3 rule's output ../../spec/ down to ../spec/,
        # so we dispatch on src depth.
        if src.count('/') >= 2:
            # depth-3 (or deeper) — sub-chapter pages
            markdown = _GUIDE_DEEP_TO_SPEC.sub('](../../spec/', markdown)
            # Bare ../../causa/ -> ../../causa/index.md (chapter overview).
            # Only sub-chapter pages emit this shape; depth-2 guide pages
            # would use ../causa/ and don't appear in the warning set.
            markdown = _GUIDE_CAUSA_DIR.sub('](../../causa/index.md)', markdown)
        else:
            markdown = _GUIDE_TO_SPEC.sub('](../spec/', markdown)
            # Bare ../../skills/X/ -> ../skills/X.md (in-tree summary page).
            # Only depth-2 guide pages emit this shape today; skills/ sub-
            # paths (e.g. ../../skills/X/SKILL.md) fall through to the
            # GitHub-URL rewrite below since the published site only carries
            # the per-skill summary page, not the skill internals.
            markdown = _GUIDE_SKILL_DIR.sub(r'](../skills/\1.md)', markdown)
    elif src.startswith('spec/'):
        # Spec pages link to docs-root pages via ../docs/ — collapse the
        # docs/ segment so the staged-tree path is ../release-process.md
        # (sibling of docs/spec/ in the docs_dir).
        markdown = _SPEC_TO_DOCSROOT.sub('](../', markdown)
        # Bare conformance/ -> conformance/README.md. The conformance corpus
        # is staged at docs/spec/conformance/; MkDocs needs the explicit .md.
        markdown = _SPEC_CONFORMANCE_DIR.sub('](conformance/README.md)', markdown)
    elif '/' not in src:
        # Docs-root page (e.g. release-process.md). Rewrite ../spec/ -> spec/
        # and ../migration/ -> migration/ (both staged trees are siblings of
        # this docs-root page in the staged docs_dir).
        markdown = _DOCSROOT_TO_SPEC.sub('](spec/', markdown)
        markdown = _DOCSROOT_TO_MIGRATION.sub('](migration/', markdown)

    for pattern, replacement in _REWRITES:
        markdown = pattern.sub(replacement, markdown)

    return markdown
