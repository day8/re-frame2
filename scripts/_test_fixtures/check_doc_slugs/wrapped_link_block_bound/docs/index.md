# Index — Wrapped-Link Block Bound (rf2-vpc4c)

The false-positive control for the wrapped-link fix. Joining lines to see
a wrapped link must not join across a BLOCK boundary: no markdown inline
construct survives a blank line, so text either side of one can never be
one link. Each pair below would resolve to a link — and be flagged as a
BROKEN TARGET — under a naive whole-file join, so this fixture expects
zero findings only if the block bound holds.

A stray unclosed bracket at the end of a paragraph [like this one

](missing-across-a-blank-line.md) and a paragraph opening with what looks
like the tail of a link.

A stray bracket ending a paragraph [again

<!-- an intervening block -->

](missing-across-two-blanks.md) with two block boundaries in between.

A stray bracket before a fenced block [here

```clojure
(comment "a fenced block is a block boundary too")
```

](missing-across-a-fence.md) and text after it.
