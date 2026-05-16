# Index — Inline-Code Negative Control (rf2-mqv8s)

This fixture proves the inline-code stripping does NOT over-mask.  On the
line below, the FIRST link sits inside an inline-code span (placeholder,
ignore) and the SECOND link is real markdown pointing at a missing file
(must still be flagged as BROKEN TARGET).

`[placeholder](ignored.md)` but this one [is real and broken](missing.md).
