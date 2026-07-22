# Index — Wrapped Link, Broken Anchor (rf2-vpc4c)

The negative control for line-wrap blindness. The link below is one
rendered link whose text wraps across a newline, so `](target.md#...)`
lands on the second line. A line-oriented extractor never matches it and
the broken anchor ships silently — `mkdocs build --strict` will not catch
it either (it reports a broken anchor as INFO and exits 0).

This page links to [a real file whose anchor does not
exist](target.md#not-a-real-anchor) — the file exists, the anchor does
not, so the validator must flag it as a BROKEN ANCHOR.

The same wrap inside a blockquote, which is how the defect was first
found in the real corpus:

> Per the freeze note above, [§Some
> Section](target.md#also-not-a-real-anchor) is the live projection.
