# Root markdown, all links correct

Positive control for the repo-root roster (rf2-znup0).  This file is NOT a
README, so before the roster widened it was invisible to every link gate in
the repo — the docs gate never walked the repo root and this gate only ever
globbed for `README.md`.

It links to [a sibling root document](CHANGELOG.md#unreleased) and to
[a file below the root](sub/other.md), and both resolve, so the fixture must
report zero findings.  Its mirror image is `root_markdown_broken_link/`.

## Section one

Same-file anchors are validated too: see [section one](#section-one).
