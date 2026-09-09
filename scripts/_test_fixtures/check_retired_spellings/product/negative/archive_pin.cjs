// NEGATIVE fixture (rule g) — the archive pin and the translation table
//
// Reproduced from `bench/fresco/src/re_frame/bench/fresco/data_archive.cjs`.
// Four lines below carry the retired product name and all four have to. The run
// corpus was archived BEFORE the rename, so the path it sits at inside that
// commit is a historical fact in the same category as the SHA beside it — a
// directory rename on main cannot rename a path inside an older commit, and the
// restore resolves that exact string. The three table rows are the other half:
// they ARE the retired spellings, being translated to the current ones on the
// way in, so the left column cannot be spelled any other way (rf2-d1nr.2).
//
// Both constructs are exempt AT THAT PATH and only there: the self-test scans
// these same bytes attributed to a second path and expects all four to fire,
// which is what proves the exemption is scoped rather than merely present.

/** The corpus's path INSIDE that commit. Pre-rename, and necessarily so. */
const ARCHIVE_PATH = 'bench/hicasso/src/re_frame/bench/hicasso/data';

const LEGACY_TOKENS = [
  ['hicasso', 'fresco'],
  ['Hicasso', 'Fresco'],
  ['HICASSO', 'FRESCO'],
];

const currentVocabulary = (text) =>
  LEGACY_TOKENS.reduce((s, [from, to]) => s.split(from).join(to), text);

module.exports = { ARCHIVE_PATH, currentVocabulary };
