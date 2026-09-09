'use strict';
// THE RUN CORPUS LIVES IN GIT HISTORY, NOT ON MAIN (rf2-6c12m.6).
//
// `data/` beside this file held every run record the Hicasso programme's
// numbers were taken from — 237 files, 80 MB, 96% of the tracked lines under
// the bench — and every clone carried it to serve verdicts already written
// down in `docs/design/hicasso/studio/`. The tree was deleted from main in one
// commit; the full corpus is the tree at the commit named below, and a
// checkout puts it back exactly where every reader still looks:
//
//     git restore --source=7b492b98cb -- bench/hicasso/src/re_frame/bench/hicasso/data
//
// (`restore`, not `checkout`: it writes the working tree without staging
// 237 files, and `data/` is git-ignored so a restored corpus never lands in a
// commit by accident.) The studio pages' provenance citations are history and
// stay as written; each block carries the SHA its data resolves at.
//
// Readers keep their `path.join(__dirname, 'data', ...)` constants unchanged.
// What changes is the self-tests `npm run check` runs: a check that re-derives
// a published figure from the corpus runs when the corpus is present and is
// SKIPPED, with one printed line, when it is not — a fixed prose skip rather
// than a silent one, because a green run must say what it did not read. The
// small records a self-test needs as a fixture in its own right (a mutation
// proof, a declaration) live under `fixtures/` and are always present.

const fs = require('node:fs');
const path = require('node:path');

/** Where the run corpus sits once restored — the path every reader still uses. */
const DATA = path.join(__dirname, 'data');

/** The commit whose tree carries the full corpus, and the command that restores it. */
const ARCHIVE_SHA = '7b492b98cb';
const RESTORE = `git restore --source=${ARCHIVE_SHA} -- bench/hicasso/src/re_frame/bench/hicasso/data`;

/**
 * True when the run corpus has been restored beside the readers. Tested on one
 * record the restore brings back and no driver writes, not on the directory:
 * a bare `data/` — left behind by a checkout, or created by a driver writing
 * a fresh run into it — is not the corpus, and the whole-tree readers pin
 * counts over the archived corpus exactly.
 */
const CORPUS_MARK = path.join(DATA, 'alloc-0gjqi', 'paired-run1.json');
const present = () => fs.existsSync(CORPUS_MARK);

/**
 * The one line a skipped corpus-backed check leaves behind. `what` names the
 * script and the checks it did not run, e.g. `alloc_null_floor: the self-test`.
 */
const skipped = (what) => {
  console.log(`${what}: SKIPPED — data/ is absent; the run corpus is in git history. Restore it with: ${RESTORE}`);
};

module.exports = { DATA, ARCHIVE_SHA, RESTORE, present, skipped };
