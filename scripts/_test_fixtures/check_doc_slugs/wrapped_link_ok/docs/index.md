# Index — Wrapped Link, Valid (rf2-vpc4c)

Seeing wrapped links must not mean flagging correct ones. Every link
below wraps and every one resolves, so this fixture expects zero
findings.

Text wrapping once: [a target file and a live
anchor](target.md#hello-world).

Text wrapping across three lines, with the destination alone on the
last: [a rather long piece of link
text that keeps
going](target.md#hello-world).

A same-file anchor whose text wraps: [back to the
top](#index--wrapped-link-valid-rf2-vpc4c).

Inside a blockquote, where the continuation line carries a `>` marker:

> Per the note above, [§Hello
> World](target.md#hello-world) is the section that matters.

Inline code still masks a wrapped PLACEHOLDER, so this backticked
link-shaped text is not resolved: `[placeholder](nowhere.md)`.
