# Index — Inline-Code Placeholder Regression (rf2-mqv8s)

This fixture exercises the rule that link-syntax appearing inside
inline-code spans is documentation-of-syntax, not a real link to resolve.

## Real link (must still validate)

This is a real link to [the target](target.md#hello-world) — the file
exists, the anchor exists, the validator must NOT flag it.

## Single-backtick placeholder (must be ignored)

The skill docs use a backticked link-syntax template such as
`[NNN-DocName](NNN-DocName.md)` to TALK ABOUT link syntax — the bracketed
file does not exist and must not be flagged as BROKEN TARGET.

## Double-backtick placeholder (must be ignored)

Double-backtick spans wrap content containing a literal backtick, e.g.
`` `[doc](missing-doc.md)` `` is a code span whose content is itself a
backticked example.  The outer span must mask the inner link.

## Mixed line — real link AND placeholder

Here a real link to [the target](target.md) sits next to a placeholder
`[fake](not-real.md)` on the same line — the real link must validate, the
placeholder must be ignored.

## Multiple placeholders on one line

`[a](missing-a.md)` and `[b](missing-b.md)` and `[c](missing-c.md)` — all
three are inline-code-masked and must be ignored.

## Bare ATX heading slug check

The `## Hello World` heading in `target.md` is what `#hello-world` resolves
to; this paragraph is just narrative.
