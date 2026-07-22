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
| `github_dup_suffix_ok`               | 0        | Three duplicate `## Errors` headings → GitHub ids `errors`, `errors-1`, `errors-2`.                  |
| `mkdocs_dup_suffix_broken`           | 1        | Negative control: `#errors_1` is MkDocs' underscore rule and resolves nowhere on GitHub.            |
| `dup_suffix_out_of_range_broken`     | 1        | Negative control: `#errors-2` with only two duplicates — the counter is bounded, not a wildcard.     |
| `github_dup_collision_bump_ok`       | 0        | `## Errors-1` after two `## Errors` → `errors-1-1` (the slugger re-bumps on collision).              |
| `inline_code_link_ignored`           | 0        | Broken-looking links inside fenced code blocks AND inline code spans — skipped.                      |
| `block_bound_link_ignored`           | 1        | The shared extractor's block bound + multiline code-span mask reach this gate; the 1 is a real broken wrapped link. |
| `external_link_skipped_by_default`   | 0        | External `https://` URL — skipped without `--check-external` (the default + `--ci` mode).            |
| `explicit_id_full_title_ok`          | 0        | `## One {#dup}` is heading TEXT — the id is the full-title slug `one-dup`, so that link resolves.    |
| `explicit_id_brace_not_a_target`     | 1        | Negative control: a link to the brace id `#dup` targets nothing → flagged.                           |

These READMEs are rendered by GitHub, so GitHub's heading slugger is the
authority (rf2-zzt2r).  A heading id is two rules, and this gate treats
them differently:

**Base slug** — shared with the docs gate via
`pymdownx.slugs.slugify(case='lower')`, imported directly from
`scripts/check_doc_slugs.py`.  That reuse is measured, not assumed: the
two slugifiers were diffed over every heading in the in-scope README
corpus and agreed on 545 of 545.  One divergence class is known and
currently unexercised — heading text shaped like an HTML tag, where
pymdownx strips `<name>` and GitHub keeps `name`.  The
`mkdocs_slug_anchor_ok` + `github_slug_anchor_broken` pair pins that gap
so it stays visible; they record where the shared helper stops matching
the real renderer, and closing it needs a GitHub-specific base
slugifier.  (Historically this pair also locked the rf2-br5u7
calibration, after `ai/link-sweep-v2.py` emitted 2 false-positive
anchors in the rf2-69nh9 sweep.)

**Duplicate suffix** — GitHub's, and deliberately NOT the docs gate's.
Repeated headings get `-1`, `-2`, ... from the second occurrence;
MkDocs/pymdownx.toc uses `_1`, `_2`.  The four `*_dup_*` fixtures above
cover the positive case, the wrong-separator negative, the out-of-range
negative, and the collision re-bump.  The two gates disagree here
because their renderers do.
