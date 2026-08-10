# Block Bound Link Ignored

This gate imports `_extract_links` from `check_doc_slugs.py` rather than
carrying its own copy, so the block bound and the multiline code-span mask
must reach it too (rf2-skpf). Both samples below are link-SHAPED text that
no renderer turns into a link.

A code span may contain a line ending, so this is one `<code>` element:

`[literal
link](missing-in-a-code-span.md)`

An ATX heading is a block boundary even without a blank line around it, so
these three lines are a paragraph, an H1 and a second paragraph:

A stray [opening
# Separate heading
](missing-across-a-heading.md)

The expected count is 1, not 0: the finding below is a REAL wrapped link
whose target does not exist, so this fixture fails if a phantom is invented
AND if the shared extractor stops seeing wrapped links at all.

A real link whose text wraps and whose [target does not
exist](missing-for-real.md).
