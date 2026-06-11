# Index

This page links into a heading that lives *inside* a blockquote callout
box.  python-markdown renders `> #### …` as a real `<h4 id="…">`, so the
anchor resolves on the built site and the validator must index it too
(rf2-869k9m).

Jump to [the reply envelope](#the-uniform-reply-envelope) — the target is a
blockquoted heading below, so this link must validate (0 broken).

A nested-blockquote heading is also a real anchor:
[deeper note](#a-deeper-note).

> #### The uniform reply envelope
>
> Every managed async surface completes through one reply shape.

> > ## A deeper note
> >
> > Nested blockquotes still mint heading anchors.
