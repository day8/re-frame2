# Index

This page links to a working note —
[the audit finding](../../ai/findings/some-audit-2026-05-19.md) — under
the gitignored `/ai/findings/` tree.  rf2-l7yj8 says the validator must
flag this even though the target may exist locally (developers' working
copies do contain it), because the file is invisible in fresh CI clones
and mkdocs strict's link validator trips on the missing reference.
