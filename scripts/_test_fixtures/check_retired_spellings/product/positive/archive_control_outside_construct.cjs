'use strict';
// POSITIVE fixture (rule g) — the exempted test file, OUTSIDE its construct
//
// The exemption for
// `bench/fresco/src/re_frame/bench/fresco/data_archive.test.cjs` is a PATH plus
// ONE LINE CONSTRUCT — the control assertion that reads the archived record's
// own bytes — and not the file. This fixture is scanned attributed to that real
// path and must report exactly ONE finding: the assertion stays green, the
// ordinary comment at the bottom does not.
//
// If the construct were ever widened to the whole file, this fixture reads zero
// and the self-test fails.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const archive = require('./data_archive.cjs');

exports.archivedVocabularyIsOld = () => {
  const raw = fs.readFileSync(path.join(archive.DATA, 'alloc-0gjqi', 'paired-run1.json'), 'utf8');
  assert.ok(raw.includes('lad/hicasso'), 'the archived record names the arm as it was named when the run was taken');
};

// A later editor adds a case to the same file and mentions Hicasso in an
// ordinary comment. Not a historical fact, and caught.
