# Duplicate explicit-id headings disambiguate on the full-title slug

## One {#dup}

First occurrence — [the first rendered id](#one-dup).

## One {#dup}

Two headings carrying the SAME brace suffix slugify to the same full-title slug,
so pymdownx.toc disambiguates the second with its `_N` suffix.

The second heading's target is [the disambiguated id](#one-dup_1). The
predecessor collapsed both headings onto the brace id `dup` (rf2-ru0wg).
