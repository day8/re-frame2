# test_fast_pr_docs_gate self-test (rf2-lwweq)

Self-test for the markdown-change detection logic in
`scripts/test-fast-pr.sh`.  Run with:

```bash
bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh
```

The harness covers:

* Detection-logic cases (A–D) — committed `.md` diff vs `origin/main`,
  unstaged `.md` changes, `--no-docs` override, `--with-docs` override.
* Gate-has-teeth cases (E–F) — `check_doc_slugs.py` + `check_readme_links.py`
  exit non-zero on bundled broken fixtures.
* Motivating-miss reproductions — minimal mkdocs-rooted repos that
  reproduce the defect shapes from #2232
  (`#trace-events-1` vs pymdownx's `#trace-events_1` underscore-N
  disambiguation) and #2233 (anchor missing the `-rf2-XXX` suffix the
  heading actually carries).  Both must trip `check_doc_slugs.py`.

Why a hand-rolled harness instead of extending an existing test
runner? The spine is plain bash invoked from POSIX environments
(Mac/Linux CI + Windows Git Bash workers); pulling in pytest or the JS
harness for a 200-line detection check would add more surface than the
test exercises.  Keep dependencies to bash + python + git + the
already-present link validators.
