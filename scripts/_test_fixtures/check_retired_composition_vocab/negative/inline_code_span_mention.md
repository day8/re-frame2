# Inline code-span mention (negative fixture)

The retired symbols named inside single-backtick INLINE code spans are prose
(a name being discussed), not a fenced snippet to copy. The gate scans only
fenced blocks, so these must stay GREEN.

Compose with `rf/image` + `rf/make-frame`; the old `rf/install!` /
`rf/reinstall!` / `(rf/realm ...)` / `rf/app-owns` surface is gone.
