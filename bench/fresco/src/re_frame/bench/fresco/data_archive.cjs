'use strict';
// THE RUN CORPUS LIVES IN GIT HISTORY, NOT ON MAIN (rf2-6c12m.6).
//
// `data/` beside this file held every run record the Fresco programme's
// numbers were taken from — 237 files, 80 MB, 96% of the tracked lines under
// the bench — and every clone carried it to serve verdicts already written
// down in `docs/design/fresco/studio/`. The tree was deleted from main in one
// commit; the full corpus is the tree at the commit named below, and one
// command puts it back exactly where every reader still looks — run it from
// the repository root:
//
//     node bench/fresco/src/re_frame/bench/fresco/data_archive.cjs --restore
//
// (It writes the working tree and never the index, and `data/` is git-ignored,
// so a restored corpus never lands in a commit by accident.) The studio pages'
// provenance citations are history and stay as written; each block carries the
// SHA its data resolves at.
//
// THE ARCHIVE IS IMMUTABLE, AND THAT IS WHY THIS FILE EXISTS RATHER THAN A
// BARE `git restore` LINE IN THE README (rf2-d1nr.2). Two things about the
// corpus were fixed on the day it was archived and cannot be changed by any
// commit on main:
//
//   * ITS PATH. The corpus was archived BEFORE the Hicasso → Fresco rename, so
//     inside `ARCHIVE_SHA` it sits under `ARCHIVE_PATH` below. A directory
//     rename on main cannot rename a path inside an older commit, and
//     `git restore --source=<sha> -- <path>` has no source/destination split
//     to bridge the two — it resolves the path in the OLD tree and fails with
//     `pathspec ... did not match any file(s) known to git`. So the restore
//     reads the archived path and writes the current one, which is what
//     `restore()` does.
//
//   * ITS VOCABULARY. Every record in it names the product as it was named
//     when the run was taken. A reader that addresses an arm, a segment or a
//     build id by its CURRENT spelling matches nothing in an archived record —
//     and matching nothing is not an error, it is a smaller population and a
//     different median. So every corpus record is read through `readRecord`,
//     which translates the archived spelling to the current one on the way in.
//     The bytes on disk stay byte-identical to the archived blob: the corpus
//     is the measured evidence and is never rewritten.
//
// Readers keep their `path.join(__dirname, 'data', ...)` constants unchanged.
// What changes is the self-tests `npm run check` runs: a check that re-derives
// a published figure from the corpus runs when the corpus is present and is
// SKIPPED, with one printed line, when it is not — a fixed prose skip rather
// than a silent one, because a green run must say what it did not read. The
// small records a self-test needs as a fixture in its own right (a mutation
// proof, a declaration) live under `fixtures/` and are always present.

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

/** Where the run corpus sits once restored — the path every reader still uses. */
const DATA = path.join(__dirname, 'data');

/** The commit whose tree carries the full corpus. */
const ARCHIVE_SHA = '7b492b98cb';

/**
 * The corpus's path INSIDE that commit. Pre-rename, and necessarily so: the
 * commit is older than the rename. See the header.
 */
const ARCHIVE_PATH = 'bench/hicasso/src/re_frame/bench/hicasso/data';

/** How many files the archived tree carries, so a partial restore is loud. */
const ARCHIVE_ENTRIES = 237;

/**
 * The one command that restores the corpus. This string is the single spelling
 * of it: the header above, `bench/fresco/README.md` and the `skipped()` line
 * below all quote it, and `data_archive.test.cjs` holds them to that.
 */
const RESTORE = 'node bench/fresco/src/re_frame/bench/fresco/data_archive.cjs --restore';

// Every git call runs at the checkout root, because `ARCHIVE_PATH` is written
// from there — a pathspec is resolved against the CURRENT directory, so the
// same string run from this directory names nothing and would report the
// archive as missing.
const repoRoot = () =>
  execFileSync('git', ['-C', __dirname, 'rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();

const git = (args, opts = {}) =>
  execFileSync('git', ['-C', repoRoot(), ...args], { maxBuffer: 1 << 30, ...opts });

/**
 * The archived corpus as `{ oid, rel }` rows, `rel` being the path relative to
 * `DATA`. Throws when the pin no longer resolves — which is the whole point of
 * having it: a broken archive pointer must be an error and not an empty list.
 */
const archiveEntries = () => {
  const listing = git(['ls-tree', '-r', '-z', ARCHIVE_SHA, '--', ARCHIVE_PATH]).toString('utf8');
  const rows = listing
    .split('\0')
    .filter(Boolean)
    .map((rec) => {
      const tab = rec.indexOf('\t');
      const [, type, oid] = rec.slice(0, tab).split(' ');
      return { type, oid, rel: rec.slice(tab + 1).slice(ARCHIVE_PATH.length + 1) };
    })
    .filter((e) => e.type === 'blob');
  if (!rows.length) {
    throw new Error(
      `the archived corpus did not resolve: ${ARCHIVE_SHA}:${ARCHIVE_PATH} names no files. ` +
        'The pin is wrong, or this clone is shallow and does not carry that commit.'
    );
  }
  return rows;
};

/**
 * Restore the corpus beside the readers. Working tree only — nothing is
 * staged, and `data/` is git-ignored, so a restored record cannot reach a
 * commit. Returns the number of files written.
 */
const restore = () => {
  const entries = archiveEntries();
  const blobs = git(['cat-file', '--batch'], {
    input: Buffer.from(entries.map((e) => e.oid).join('\n') + '\n', 'utf8'),
  });
  let off = 0;
  for (const e of entries) {
    // `<oid> <type> <size>\n<contents>\n`, in the order the oids were asked for.
    const nl = blobs.indexOf(0x0a, off);
    const size = Number(blobs.slice(off, nl).toString('utf8').split(' ')[2]);
    const out = path.join(DATA, e.rel);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, blobs.slice(nl + 1, nl + 1 + size));
    off = nl + 1 + size + 1;
  }
  return entries.length;
};

/**
 * THE ARCHIVED VOCABULARY, TRANSLATED. The corpus predates the Hicasso →
 * Fresco rename, so its arm keys (`reagent-subs|lad/hicasso#R7@page`), its
 * segment names, its `arm`/`pairKey`/`build` values and its env-var mentions
 * all carry the old token. This is the same three-form token substitution the
 * rename applied to source, applied to an archived record on the way in — so a
 * reader written against the current vocabulary reads the whole population,
 * and a record a driver wrote TODAY passes through unchanged.
 */
const LEGACY_TOKENS = [
  ['hicasso', 'fresco'],
  ['Hicasso', 'Fresco'],
  ['HICASSO', 'FRESCO'],
];
const currentVocabulary = (text) =>
  LEGACY_TOKENS.reduce((s, [from, to]) => s.split(from).join(to), text);

/**
 * Read one corpus record. THE ONE DOOR every reader of the archive comes
 * through: it is where the archived vocabulary becomes the current one. A
 * bare `JSON.parse(fs.readFileSync(...))` over `data/` is the bug this
 * function exists to stop — it does not fail, it quietly returns a smaller
 * population.
 */
const readRecord = (file) => JSON.parse(currentVocabulary(fs.readFileSync(file, 'utf8')));

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
  console.log(
    `${what}: SKIPPED — data/ is absent; the run corpus is in git history. ` +
      `Restore it from the repo root with: ${RESTORE}`
  );
};

module.exports = {
  DATA,
  ARCHIVE_SHA,
  ARCHIVE_PATH,
  ARCHIVE_ENTRIES,
  RESTORE,
  archiveEntries,
  restore,
  currentVocabulary,
  readRecord,
  present,
  skipped,
};

if (require.main === module) {
  if (process.argv.includes('--restore')) {
    const n = restore();
    console.log(`restored ${n} files into ${DATA} (working tree only; data/ is git-ignored)`);
    if (!present()) {
      console.error('but the corpus mark is absent — the restore did not land what the readers look for');
      process.exit(1);
    }
  } else {
    console.log(`usage: node ${path.relative(process.cwd(), __filename)} --restore`);
    process.exit(2);
  }
}
