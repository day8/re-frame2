// POSITIVE fixture (rule g) — the exempted file, OUTSIDE the exempted constructs
//
// The exemption for `bench/fresco/src/re_frame/bench/fresco/data_archive.cjs`
// is a PATH plus two LINE CONSTRUCTS, not a file. This fixture is scanned
// attributed to that real path and must report exactly ONE finding: the archive
// pin and the three translation rows are historical facts and stay green, while
// the ordinary comment at the bottom is new text in the same file and is graded
// like any other.
//
// If either construct were ever widened to the whole file, this fixture reads
// zero and the self-test fails — which is the point of it.

const ARCHIVE_PATH = 'bench/hicasso/src/re_frame/bench/hicasso/data';

const LEGACY_TOKENS = [
  ['hicasso', 'fresco'],
  ['Hicasso', 'Fresco'],
  ['HICASSO', 'FRESCO'],
];

// A later editor documents the boundary and spells the retired Hicasso name in
// ordinary prose. That line is not a historical fact and must be caught.
module.exports = { ARCHIVE_PATH, LEGACY_TOKENS };
