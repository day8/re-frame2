# Index — A Multiline Code Span Is Not A Link (rf2-skpf)

A CommonMark code span may contain a line ending, so the backticked text
below is one `<code>` element and holds no link at all — python-markdown
renders it as `<p><code>[literal link](missing-in-a-code-span.md)</code></p>`.
Masking inline code per LINE could not see that: neither backtick has a
partner on its own line, so neither was masked, the joined lines matched the
link regex, and a target the renderer never produces was reported broken.

`[literal
link](missing-in-a-code-span.md)`

The expected count for this fixture is 1, not 0, and deliberately so: a
fixture that only asserted "nothing found" would pass just as well if
extraction stopped working altogether. The one finding comes from the REAL
wrapped link below, whose target genuinely does not exist and must still be
flagged as a BROKEN TARGET.

A real link whose text wraps across a newline and whose [target does not
exist](missing-for-real.md).
