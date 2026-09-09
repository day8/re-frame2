// NEGATIVE fixture (rule g) — the archived-vocabulary CONTROL assertion
//
// Reproduced from
// `bench/fresco/src/re_frame/bench/fresco/data_archive.test.cjs`. The check it
// controls re-derives a published figure from an archived run record; without
// it, a corpus that had somehow been rewritten in the current vocabulary would
// pass that check while proving nothing at all about the boundary. So the
// assertion has to name the arm key as the archived BYTES name it, which is a
// historical fact about a file no commit on main can reach (rf2-d1nr.2).
//
// Exempt AT THAT PATH and only there: the self-test scans these same bytes
// attributed to a second file in the same tree and expects a finding, because a
// second file spelling the retired name is the reintroduction rule (g) exists
// to catch.

corpusTest('the archived corpus really is in the OLD vocabulary', () => {
  const raw = fs.readFileSync(path.join(archive.DATA, 'alloc-0gjqi', 'paired-run1.json'), 'utf8');
  assert.ok(raw.includes('lad/hicasso'), 'the archived record names the arm as it was named when the run was taken');
  assert.ok(!raw.includes('lad/fresco'), 'and does not name it as it is named now');
});
