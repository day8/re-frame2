# Index — Non-Blank Block Bounds The Join (rf2-skpf)

A blank line is a block boundary, but it is not the only one. Every pair
below sits either side of a boundary that is NOT a blank line, so no
renderer produces a link from it and the extractor must not either. The
first is the counterexample from the bead: python-markdown renders a
paragraph, an H1 and a second paragraph — three blocks, no link.

A stray [opening
# Separate heading
](missing-across-a-heading.md)

A single-line leaf block also ENDS where it starts — nothing continues an
ATX heading — so a stray bracket in a heading title cannot reach into the
paragraph underneath it:

## A heading holding a stray [ bracket
](missing-under-a-heading.md) opens the paragraph below it.

A thematic break (read as a setext underline here, which ends the paragraph
above it just the same) bounds the join:

A stray bracket before a rule [here
---
](missing-across-a-rule.md) after it.

Each list item is its own container, so a stray bracket cannot bleed into
the next bullet:

* A bullet holding a stray [ bracket
* the next bullet](missing-across-a-bullet.md) closes the list.

A table's rows render as separate cells; no inline construct spans two of
them:

| Column                        | Note |
| ----------------------------- | ---- |
| A cell holding a stray [      | one  |
| a later row](missing-across-a-table-row.md) | two |

A blockquote interrupts the paragraph above it:

A paragraph holding a stray [ bracket
> and a quote](missing-across-a-quote.md) that interrupts it.

The expected count for this fixture is 1, not 0, and deliberately so: a
fixture that only asserted "nothing found" would pass just as well if
extraction stopped working altogether — the mirror-image failure, where
bounding the join discards real links. The one finding is the wrapped link
below, which lives INSIDE a single list item (its continuation line carries
no marker, so it is one inline run) and is genuinely broken:

* A bullet whose link text wraps onto its continuation line, [which must
  still be extracted and flagged](missing-for-real.md).
