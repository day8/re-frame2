# MkDocs build-time hooks (rf2-qvlf, rf2-jtkc).
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
# Two rewrite cases (rf2-jtkc):
#
#   1. guide -> spec      ../../spec/       -> ../spec/
#                         (spec is staged into docs/spec/, so it IS in the
#                         site; the relative-path depth changes)
#
#   2. cross-tree refs to trees that are NOT staged into the site:
#        - examples/   (CLJS apps; not docs to render)
#        - root README.md
#      These are rewritten to absolute GitHub blob URLs so the published
#      site links to the source-tree view on github.com. Source files
#      keep their GitHub-friendly relative paths.

import re

GH_BLOB_BASE = "https://github.com/day8/re-frame2/blob/main"

# Case 1: guide/* pages link to spec via ../../spec/ — collapse one level.
_GUIDE_TO_SPEC = re.compile(r'\]\(\.\./\.\./spec/')

# Case 2: cross-tree refs that don't exist in the staged docs tree.
# These rewrites apply to ALL pages (guide/ and spec/), since both trees
# reference examples/ and root README.md from their own depth in the source
# layout.
#
# From guide/ source (docs/guide/X.md), the path to repo-root is ../../
# From spec/  source (spec/X.md, staged at docs/spec/X.md), it is ../
#
# Both depths are rewritten to the same absolute GitHub URL.
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
)


def on_page_markdown(markdown, page, config, files):
    """Rewrite cross-tree links in guide/* and spec/* pages.

    1. ../../spec/ -> ../spec/   (only on guide/* pages; spec IS staged)
    2. ../../examples/ and ../examples/  -> https://github.com/.../examples/
    3. ../../README.md and ../README.md  -> https://github.com/.../README.md
    """
    src = page.file.src_path.replace('\\', '/')

    if src.startswith('guide/'):
        markdown = _GUIDE_TO_SPEC.sub('](../spec/', markdown)

    for pattern, replacement in _REWRITES:
        markdown = pattern.sub(replacement, markdown)

    return markdown
