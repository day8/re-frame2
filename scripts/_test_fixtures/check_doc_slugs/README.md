# check_doc_slugs.py self-test fixtures (rf2-unge8)

Each subdirectory is a self-contained mini-repo the validator script
treats as a real corpus when invoked with `--repo-root <fixture-dir>`.
Required minimum: a top-level `mkdocs.yml` (so the repo-root guard
accepts the directory) and at least one `.md` file under the scope the
fixture exercises (`docs/`).

Run all fixtures via:

    python scripts/check_doc_slugs.py --self-test --verbose

The expected broken-link count for each fixture is hard-coded in
`_run_self_tests()` inside the script itself.

Fixtures:

| Fixture                            | Expected broken | Exercises                                                                          |
| ---------------------------------- | --------------- | ---------------------------------------------------------------------------------- |
| `valid_link`                       | 0               | Cross-file link to existing file + anchor                                          |
| `broken_target`                    | 1               | Cross-file link to a missing `.md` file                                            |
| `broken_anchor`                    | 1               | Target file exists but anchor doesn't                                              |
| `same_file_anchor_ok`              | 0               | `[text](#anchor)` resolving to local head                                          |
| `same_file_anchor_broken`          | 1               | `[text](#anchor)` not in same file                                                 |
| `absolute_path_ok`                 | 0               | `[text](/docs/foo.md)` repo-root-absolute                                          |
| `relative_dotdot_ok`               | 0               | `[text](../foo.md)` from a subdirectory                                            |
| `inline_code_placeholder_ignored`  | 0               | Backticked link-syntax placeholders are masked; real link still validates (rf2-mqv8s) |
| `inline_code_negative_control`     | 1               | Same-line broken link OUTSIDE an inline-code span is still flagged (rf2-mqv8s)     |
| `ai_findings_link_flagged`         | 1               | Link into the gitignored `ai/findings/<file>.md` tree is flagged (rf2-l7yj8)       |
| `ai_findings_dir_link_flagged`     | 1               | Link into the bare `ai/findings/` directory is flagged (rf2-l7yj8)                 |
| `blockquoted_heading_ok`           | 0               | Link into a blockquoted heading (`> #### Foo`, incl. nested) resolves (rf2-869k9m) |
| `indented_heading_not_indexed`     | 1               | Negative control: an *indented bare* `#` line still mints no anchor (rf2-869k9m)   |
| `explicit_id_full_title_ok`        | 0               | `attr_list` is off, so `## One {#dup}` mints `one-dup` — the full visible title (rf2-ru0wg) |
| `explicit_id_brace_not_a_target`   | 1               | Negative control: the brace suffix `#dup` is NOT a fragment target (rf2-ru0wg)     |
| `explicit_id_duplicate`            | 0               | Two `{#dup}` headings disambiguate as `one-dup` / `one-dup_1` (rf2-ru0wg)          |
| `wrapped_link_broken_anchor`       | 2               | A link whose TEXT wraps across a newline is still validated (rf2-vpc4c)            |
| `wrapped_link_ok`                  | 0               | Correct wrapped links — plain, multi-line, same-file, blockquoted — are not flagged (rf2-vpc4c) |
| `wrapped_link_block_bound`         | 0               | False-positive control: the join stops at a block boundary, so `[text` … blank … `](x.md)` is not a link (rf2-vpc4c) |
| `multiline_code_span_not_a_link`   | 1               | A code span crossing a line ending is masked whole, so the link-shaped text inside it yields nothing; the 1 is a real broken wrapped link (rf2-skpf) |
| `non_blank_block_bound`            | 1               | The join stops at every NON-blank boundary — heading (before and after), thematic break, list item, table row, blockquote entry; the 1 is a real wrapped link inside one list item (rf2-skpf) |
| `indented_fence_link_ignored`      | 0               | A fence carrying its container's indentation — list item, admonition — is still a fence, so the sample inside it carries no links to resolve (rf2-mmyc) |
| `indented_fence_negative_control`  | 1               | Widening the fence matcher must not quieten the gate: the 1 is a real broken link in prose after an indented fence, and an unclosed fence ends with its container (rf2-mmyc) |
| `fenced_doc_link_in_scope`         | 1               | A doc link INSIDE a fence, in a `FENCED_DOC_LINK_TREES` tree — it resolves, so only the assertion can see it (rf2-mmyc) |
| `fenced_doc_link_out_of_scope`     | 0               | The identical sample under a sibling tree stays silent — the assertion is scoped, not corpus-wide (rf2-mmyc) |
| `fenced_doc_link_blockquoted`      | 1               | The same assertion on a BLOCKQUOTED fence (`> ```clojure`) — the shape `docs/design/hicasso/studio/` actually writes (rf2-1cpt) |
| `blockquoted_fence_not_indexed`    | 2               | A `###` line and an `<a id>` inside a blockquoted fence mint no fragment target, so links to them are broken; the real heading and the blockquoted heading below must still resolve (rf2-1cpt) |
| `compat_anchor_teeth`              | driven directly | The compat-anchor manifest, placement, and source-comment teeth — invoked with explicit fixture inputs, not from this table (rf2-57k74 / rf2-zq5i6 / rf2-k30r7) |
