# check_readme_links.py self-test fixtures

Each subdirectory is a self-contained mini-repo the validator treats as a real
corpus. Run them all via:

    python scripts/check_readme_links.py --self-test --verbose

The expected finding count for each fixture is hard-coded in
`_run_self_tests()` inside the script itself. Only a fixture that `main()` is
pointed at needs a `mkdocs.yml` marker (the repo-root guard), and only a
site-URL fixture needs `site_url:` in it.

| Fixture                          | Expected | Exercises                                                                                                   |
| -------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------- |
| `github_dup_suffix_ok`           | 0        | GitHub's duplicate-heading ids: `errors`, `errors-1`, `errors-2`, and a later natural `errors-1` bumped to `errors-1-1`. |
| `mkdocs_dup_suffix_broken`       | 1        | `#errors_1` is MkDocs' underscore rule and resolves nowhere on GitHub.                                      |
| `explicit_id_brace_not_a_target` | 1        | `## One {#dup}` is heading TEXT on GitHub, so a link to `#dup` targets nothing.                             |
| `root_markdown_broken_link`      | 2        | Repo-root markdown that is not a README: a broken target and a broken cross-file anchor.                    |
| `site_url_in_root_markdown`      | 1        | This project's own site URLs, resolved offline against the source tree: one live, one dead.                 |
| `redirect_table_broken`          | 1        | `SKILL-REDIRECT.md`'s bare-URL bullets: a live site URL, a dead one and a `github.com` row left external.    |
| `root_markdown_ok`               | —        | Not in the case list: the self-test drops an untracked scratch note into it and requires 0 findings.        |
| `broken_internal_link`           | 1        | Not in the case list: the fast-PR harness runs it through `main()` (case N) to pin the exit code.           |

These files render on GitHub, so GitHub's heading slugger is the authority. The
base slug is shared with the docs gate (`SLUGIFY`, imported from
`scripts/check_doc_slugs.py`); the duplicate suffix is GitHub's `-N`, not
MkDocs' `_N`, because the two renderers disagree.
