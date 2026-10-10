# test_fast_pr_docs_gate self-test

Self-test for the changed-surface tiering in `scripts/test-fast-pr.sh`.
Run with:

```bash
bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh
```

The harness drives the **real** spine in `--plan` mode (which classifies the
change set, prints machine-readable `PLAN…` lines, and runs nothing) via
`--repo-root DIR` against disposable git repos. It does **not** replicate the
detection logic, so it cannot drift from the spine.

The harness covers:

* **Git states (A–D)** — committed docs diff vs `origin/main`, staged code,
  unstaged docs, untracked docs. C1 is an unstaged `spec/` page, which arms the
  JVM tier without the node tier, as CI's `implementation_jvm` does.
* **Tiering (F–H)** — a clean tree runs static checks only; a missing
  `origin/main` base falls back conservatively; a mixed docs+code diff runs both
  tiers.
* **Overrides (I–L)** — `--all` and `RF2_FAST_PR_ALL=1` run the complete spine
  regardless of classification; `--with-docs` / `--no-docs` force the
  documentation tier on/off.
* **Gate teeth (M–N)** — `check_doc_slugs.py` and `check_readme_links.py` exit 1
  through `main()` on the bundled broken fixtures.
* **Per-artefact JVM selection (U)** — a diff under an artefact's tree adds
  exactly that artefact's suite beside `implementation/core`, matched on a path
  boundary.
* **The spine's own tree (Z, AB)** — a diff touching `scripts/test-fast-pr.sh`
  arms every tier; an ordinary `scripts/` change takes the unknown-surface
  fallback and does not arm the documentation tier.
* **Hermetic mkdocs resolution (AC2, AD, AE)** — on GitHub CI `requirements.txt`
  puts a bare `mkdocs` on PATH, so a host-consulting case could never execute
  the module fallback there. These cases construct the module-only state
  instead: a PATH with every `mkdocs`-providing directory removed plus a stub
  directory that shadows all three launchers `resolve_mkdocs` tries and lets at
  most one answer `-m mkdocs --version`. AC2 lets only `py`, the last launcher,
  answer, so it also proves the loop walks past the two that fail; AD asserts a
  console script wins over a working module launcher; AE asserts a host where
  nothing resolves reports `unresolved` rather than anything that reads as a
  pass.
* **The pinned clj-kondo lane (AF1–AF5)** — a later `--lint` root and
  `lint.yml` itself arm it, a docs-only diff does not, and an unresolvable base
  arms it conservatively.

## Where it runs

* **CI** — test.yml's always-on `verify-readme-links` job runs this harness on
  every pull request, and that job is in `all-required-passed`'s `needs:`.
  `implementation/scripts/_changed-surfaces.test.cjs` pins both halves of that
  wiring — the invocation inside `verify-readme-links`, and that job's presence
  in `all-required-passed`'s `needs:` — because deleting the step leaves valid
  YAML and an otherwise green matrix.
* **The local spine** — `scripts/test-fast-pr.sh` runs it when the diff touches
  the spine or this fixture tree. No recursion: the harness invokes the spine in
  `--plan` mode, which exits before the gate steps.

The harness is plain bash because the spine is plain bash invoked from POSIX
environments (Mac/Linux CI and Windows Git Bash workers). It needs only bash,
python, git and the link validators already in the repo.
