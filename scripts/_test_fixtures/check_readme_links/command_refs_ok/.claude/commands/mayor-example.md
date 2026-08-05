---
description: Fixture command file whose every reference resolves (rf2-1yy75).
---
MAYOR LOOP (fixture). Reference a method doc BY PATH inside inline code, the
way the live command files do: `docs/the-mayor-method/bootstrap.md`. The gate
must see it even though every markdown-link gate masks inline code.

Reference the same tree BY BARE FILENAME, also the way the live files do —
dispatch-prompt-template.md Shape 4 — with no directory to anchor it.

Two things on this line must NOT be read as references, and the fixture's zero
count is what pins that: a glob naming a FAMILY of files (`ladder-*.md`, which
no single path can resolve) and a link into the gitignored working tree
(`ai/findings/some-note.md`, which no tracked roster can ever contain).
