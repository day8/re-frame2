# MkDocs build-time hooks (rf2-qvlf).
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
# Scope deliberately narrow: only the ../../spec/ -> ../spec/ rewrite,
# applied only to pages whose source path is under guide/. Other
# repo-relative paths in the guide (e.g. ../../examples/, ../../README.md)
# are out of scope for this hook — those targets are not staged into
# docs/ and require a separate decision (link to GitHub instead, or stage
# additional trees).

import re

_SPEC_LINK = re.compile(r'\]\(\.\./\.\./spec/')


def on_page_markdown(markdown, page, config, files):
    """Rewrite ../../spec/ -> ../spec/ in guide/* pages."""
    src = page.file.src_path.replace('\\', '/')
    if src.startswith('guide/'):
        markdown = _SPEC_LINK.sub('](../spec/', markdown)
    return markdown
