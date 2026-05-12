# check_doc_slugs.py self-test fixtures (rf2-unge8)

Each subdirectory is a self-contained mini-repo the validator script
treats as a real corpus when invoked with `--repo-root <fixture-dir>`.
Required minimum: a top-level `mkdocs.yml` (so the repo-root guard
accepts the directory) and at least one `.md` file under `docs/`.

Run all fixtures via:

    python scripts/check_doc_slugs.py --self-test --verbose

The expected broken-link count for each fixture is hard-coded in
`_run_self_tests()` inside the script itself.

Fixtures:

| Fixture                     | Expected broken | Exercises                                  |
| --------------------------- | --------------- | ------------------------------------------ |
| `valid_link`                | 0               | Cross-file link to existing file + anchor  |
| `broken_target`             | 1               | Cross-file link to a missing `.md` file    |
| `broken_anchor`             | 1               | Target file exists but anchor doesn't      |
| `same_file_anchor_ok`       | 0               | `[text](#anchor)` resolving to local head  |
| `same_file_anchor_broken`   | 1               | `[text](#anchor)` not in same file         |
| `absolute_path_ok`          | 0               | `[text](/docs/foo.md)` repo-root-absolute  |
| `relative_dotdot_ok`        | 0               | `[text](../foo.md)` from a subdirectory    |
