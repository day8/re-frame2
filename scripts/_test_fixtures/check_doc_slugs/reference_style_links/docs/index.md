# Index — Reference-Style Links (rf2-2ryk)

The gate matched inline links only, so every link the renderer emits from a
`[label]: destination` definition went unchecked. This fixture expects **2**
findings — one broken target and one broken anchor, both reached through a
reference definition — and every other construct on the page must contribute
nothing.

## Resolving, in all three forms

Full form: [the greeting section][hello].

Collapsed form: [hello][].

Shortcut form: [hello].

Case is not significant, and an internal whitespace run folds to one space:
[the greeting][HELLO] and [the greeting][spaced  label].

Inside a blockquote, where the definition carries its own quote marker:

> Per the note, [quoted] is live.
>
> [quoted]: target.md#hello-world

## The two findings

A definition whose target file does not exist, reached by the shortcut
form: [gone].

A definition whose target file exists but whose anchor does not, reached by
the full form: [the missing section][bad-anchor].

## Negative controls — none of these is a link

A bare bracket run with no definition anywhere is not a link, which is the
whole reason resolution has to happen before reporting: [nolink],
[also missing][nowhere] and [neither][] all render as literal brackets.

A definition that is NEVER used emits no link, so its missing target is not
a finding: see `unused` below.

An inline link outranks a definition of the same name, so this resolves to
`target.md`, not to the definition: [hello](target.md#hello-world).

A reference use inside a code span is code: `[hello][]`.

A footnote is not a reference link[^1].

A label may not contain brackets, so `[a[b]]` defines nothing and
[bracketed][a[b]] is prose.

A use label is folded but never stripped, so [padded][  hello  ] resolves to
nothing at all.

[^1]: Footnote bodies are consumed by the `footnotes` extension.

[hello]: target.md#hello-world
[spaced label]: target.md#hello-world
[gone]: no-such-file.md
[bad-anchor]: target.md#no-such-anchor
[unused]: also-no-such-file.md
