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
* **Coverage-honesty + per-artefact JVM selection (Q–V)** — `--plan` states what
  the JVM tier actually contains and points at the full sweep; a diff under an
  artefact's tree adds that artefact's suite and a diff elsewhere does not.
* **mkdocs resolution (W–Y)** — the console script is preferred; an
  installed-as-a-module mkdocs is found rather than soft-skipped; a code-only
  diff never probes for it.
* **The spine's own tree (Z–AB, rf2-fhdd3)** — a diff touching
  `scripts/test-fast-pr.sh` or this fixture tree arms the documentation tier and
  the spine's own self-test, and an ordinary `scripts/` change still does
  neither. Before rf2-fhdd3 a spine-only diff classified as an *unknown
  surface*: the JVM and node tiers ran conservatively while `run_docs` stayed
  keyed on a documentation-content predicate the spine never matched, so a
  change to the spine's own documentation gate did not run that gate.
* **Hermetic mkdocs resolution (AC–AE, rf2-03298)** — case X consults the host,
  and on GitHub CI `requirements.txt` always puts a bare `mkdocs` on PATH, so it
  can never execute a module fallback there. These cases construct the
  module-only state instead: a PATH with every `mkdocs`-providing directory
  removed plus a stub directory that shadows all three launchers `resolve_mkdocs`
  tries and lets exactly one of them answer `-m mkdocs --version`. There is one
  such case per supported launcher — AC (`python`), AC1 (`python3`), AC2 (`py`) —
  because a witness for `python` alone cannot tell a working three-entry loop
  from a one-entry one, and `python3`-only / `py`-only checkouts are the two this
  fallback exists for. AD asserts a console script still wins over a working
  module launcher; AE asserts a host where nothing resolves reports `unresolved`
  rather than anything that reads as a pass. Shadowing matters: the sanitised
  PATH still carries the host's real interpreters, so an unshadowed `python3`
  witness would be satisfied by the host's `python` and stay green through the
  regression it exists to catch.

## Where it runs

Two places, and both matter:

* **CI** — test.yml's always-on `verify-readme-links` job runs this harness on
  every pull request, and that job is in `all-required-passed`'s `needs:`. Until
  rf2-03298 no workflow and no npm script referenced this file at all, so every
  assertion in it was local-only and therefore skippable. That wiring is itself
  pinned: `implementation/scripts/_changed-surfaces.test.cjs` asserts both halves
  of it — the invocation inside `verify-readme-links`, and that job's presence in
  `all-required-passed`'s `needs:` — under the equally unconditional
  `js-harness-self-tests` job. Deleting the step leaves valid YAML and a green
  matrix otherwise, which is exactly how the harness went unrun for so long.
* **The local spine** — `scripts/test-fast-pr.sh` runs it when the diff touches
  the spine or this fixture tree (rf2-fhdd3). No recursion: the harness invokes
  the spine in `--plan` mode, which exits before the gate steps.

Why a hand-rolled bash harness instead of extending the JS test runner? The
spine is plain bash invoked from POSIX environments (Mac/Linux CI + Windows Git
Bash workers); pulling in pytest or the JS harness for a change-set tiering
check would add more surface than the test exercises. Keep dependencies to
bash + python + git + the already-present link validators.
