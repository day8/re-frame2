# test_fast_pr_docs_gate self-test (rf2-lwweq, extended rf2-r6x1t)

Self-test for the changed-surface tiering in `scripts/test-fast-pr.sh`.
Run with:

```bash
bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh
```

The harness drives the **real** spine in `--plan` mode (which classifies the
change set, prints one `PLAN docs=… jvm=… node=…` line, and runs nothing) via
`--repo-root DIR` against disposable git repos. It does **not** replicate the
detection logic — the earlier harness copied the markdown-diff block inline,
which could drift from the spine.

The harness covers:

* **Git-state cases (A–D)** — committed docs diff vs `origin/main`, staged code,
  unstaged docs, untracked docs — the four states the change-set gathering must
  handle deterministically.
* **Tiering cases (E–H)** — an unknown surface falls back to the full runtime
  run; a clean tree runs static checks only; a missing `origin/main` base falls
  back conservatively; a mixed docs+code diff runs both tiers.
* **Override cases (I–L)** — `--all` and `RF2_FAST_PR_ALL=1` run the complete
  spine regardless of classification; `--with-docs` / `--no-docs` force the
  documentation tier on/off.
* **Gate-has-teeth cases (M–N)** — `check_doc_slugs.py` + `check_readme_links.py`
  exit non-zero on the bundled broken fixtures.
* **Motivating-miss reproductions (O–P)** — minimal mkdocs-rooted repos that
  reproduce the defect shapes from #2232 (`#trace-events-1` vs pymdownx's
  `#trace-events_1` underscore-N disambiguation) and #2233 (anchor missing the
  `-rf2-XXX` suffix the heading actually carries). Both must trip
  `check_doc_slugs.py`.

Why a hand-rolled bash harness instead of extending the JS test runner? The
spine is plain bash invoked from POSIX environments (Mac/Linux CI + Windows Git
Bash workers); pulling in pytest or the JS harness for a change-set tiering
check would add more surface than the test exercises. Keep dependencies to
bash + python + git + the already-present link validators.
