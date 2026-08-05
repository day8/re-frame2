---
description: Fixture command file carrying exactly two unresolved references (rf2-1yy75).
---
MAYOR LOOP (fixture). This reference still resolves — the document is present
and tracked: `docs/the-mayor-method/bootstrap.md`. It is here so the fixture's
count of 2 fails in BOTH directions: upward if a resolving reference starts
being flagged, downward if either broken form stops being seen.

PATH FORM, drifted: the method doc was renamed and nobody updated this line, so
`docs/the-mayor-method/renamed-away.md` names nothing.

BARE-FILENAME FORM, drifted: retired-template.md Shape 4 — the form with no
directory to anchor it, and the one no path-only rule would catch.
