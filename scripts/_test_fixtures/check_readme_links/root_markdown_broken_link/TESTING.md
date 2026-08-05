# Root markdown with a broken link and a broken anchor

Negative control for the repo-root roster (rf2-znup0), and the mutation the
bead proved the docs gate exits 0 on: one broken relative target and one
broken in-page anchor, in a root file that is not a README.

* [broken target](does-not-exist.md) — no such file.
* [broken anchor](CHANGELOG.md#no-such-heading) — the file exists, the
  heading does not.

Two findings, therefore.  A count of 0 means the roster stopped walking the
repo root; a count above 2 means something else started firing.
