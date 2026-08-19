---
description: Fixture command file whose every SCRIPT reference resolves (rf2-2eivg).
---
MAYOR LOOP (fixture). Checkpoint before pulling: `scripts/beads-checkpoint.sh`.
That is the reference class the arm was blind to while it resolved markdown and
nothing else — a path the loop EXECUTES rather than one it reads.

The changed-surface classifier lives under a DOTFILE directory —
`.github/scripts/report-changed-surfaces.sh` — and a rule requiring every path
segment to begin with a word character would silently stop covering it, along
with every other reference in `.github/`.

Extensions are not a hand-list: `scripts/gate-config.edn` resolves on the very
same rule as the two shell scripts above, which is what keeps the gap from
re-opening one file type later.

FOUR THINGS BELOW MUST NOT BE READ AS REFERENCES, and this fixture's zero count
is what pins each of them:

* a glob naming a FAMILY of files, which no single path can resolve —
  `scripts/*.sh` and `.github/workflows/*`;
* a git ref RANGE, which is not a path at all — `origin/main...worker/gatearm`
  and `origin/main..worker/gatearm`;
* a path into a gitignored tree, which no tracked roster can ever contain —
  `ai/findings/some-note.md` and `.scratch/gate-fastpr-1.log`;
* a bare NON-markdown filename — `no-such-helper.sh` — because a bare dotted
  token is ordinary prose (`v1.x`, `2.53`, `GOV.UK`) far more often than it is
  a path, and a slash is what settles the question.
