---
description: Fixture command file carrying exactly two unresolved SCRIPT references (rf2-2eivg).
---
MAYOR LOOP (fixture). PATH FORM, drifted: the script was renamed and nobody
updated this line, so `scripts/renamed-away.sh` names nothing.

SECOND ON ITS LINE, and that placement is the whole point of it: checkpoint with
`scripts/beads-checkpoint.sh` and then run `.github/scripts/report-changed-surfaces.sh`.
The first of those two resolves and the second does not, so a scan that took only
the first match on a line would report nothing here and the count would fall to 1
— which is this fixture's answer to "what happens to the SECOND broken
reference?".

The traps stay silent here too, so the count fails upward as well as downward: a
glob (`scripts/*.sh`), a git ref range (`origin/main...worker/gatearm`), a
gitignored root (`.scratch/gate-fastpr-1.log`) and a bare non-markdown filename
(`no-such-helper.sh`).
