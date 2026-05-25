# check_readme_links.py self-test fixtures (rf2-br5u7)

Each subdirectory is a self-contained mini-repo the validator script
treats as a real corpus when invoked with `--repo-root <fixture-dir>`.
Required minimum: a top-level `mkdocs.yml` (so the repo-root guard
accepts the directory) and at least one `README.md` file.

Run all fixtures via:

    python scripts/check_readme_links.py --self-test --verbose

The expected finding count for each fixture is hard-coded in
`_run_self_tests()` inside the script itself.

Fixtures:

| Fixture                              | Expected | Exercises                                                                                            |
| ------------------------------------ | -------- | ---------------------------------------------------------------------------------------------------- |
| `valid_readme`                       | 0        | Baseline: README with valid internal links + anchor + relative subdir target.                        |
| `broken_internal_link`               | 1        | README links to a missing `.md` file → BROKEN TARGET.                                                |
| `mkdocs_slug_anchor_ok`              | 0        | Anchor uses MkDocs slug rule (`<name>` stripped) — passes; was rf2-69nh9 false-positive class.       |
| `github_slug_anchor_broken`          | 1        | Anchor uses GitHub slug rule (`<name>` kept as `name`) — flagged under MkDocs rules.                 |
| `mustache_placeholder_ignored`       | 0        | Link destination contains `{{var}}` Mustache placeholder — skipped (template-source false-positive). |
| `underscore_disambig_ok`             | 0        | Duplicate `## Errors` headings → MkDocs slug `errors_1` (underscore), not GitHub's `errors-1`.       |
| `inline_code_link_ignored`           | 0        | Broken-looking links inside fenced code blocks AND inline code spans — skipped.                      |
| `external_link_skipped_by_default`   | 0        | External `https://` URL — skipped without `--check-external` (the default + `--ci` mode).            |

The MkDocs-vs-GitHub slug fixtures (`mkdocs_slug_anchor_ok` +
`github_slug_anchor_broken`) are the load-bearing pair that locks in
the rf2-br5u7 calibration: the original `ai/link-sweep-v2.py` used
GitHub's rules and emitted 2 false-positive anchors in the rf2-69nh9
sweep.  This gate must use `pymdownx.slugs.slugify(case='lower')`,
imported directly from `scripts/check_doc_slugs.py`, so the docs gate
and the README gate agree by construction.
