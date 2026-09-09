# POSITIVE fixture (rule g) — the exempted file, OUTSIDE the exempted construct

The exemption for `docs/design/fresco/decisions.md` is a PATH plus a LINE
CONSTRUCT, not a file. This fixture is scanned attributed to that real path and
must report exactly ONE finding: the nested blockquote is quoted history and
stays green, while the plain prose line below it is new text in the same file
and is graded like any other.

If the exemption were ever widened to the whole file, this fixture reads zero
and the self-test fails — which is the point of it.

> **Superseded by operator ruling.** The quoted ruling is preserved verbatim:
>
> > **Ruling.** The product is **Hicasso**; namespace `re-frame.hicasso`.
> > **Reopens** never — names are permanent after first publish.

A later editor adds a note to the same file and spells the retired Hicasso name
in ordinary prose. That line is not quoted history and must be caught.
