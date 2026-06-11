# Index

Negative control for rf2-869k9m: the blockquote-prefix relaxation must NOT
start indexing *indented bare* `#` lines.  Markdown treats a `#` indented
under a list/code context as ordinary text or code, not a heading, so it
mints no anchor.  This link must therefore stay BROKEN (1 broken).

Jump to [an indented pseudo-heading](#indented-pseudo-heading).

Below, the `#` is indented four spaces with no blockquote marker, so it is
not a heading and contributes no slug:

    #### Indented pseudo-heading
