# Retiring `re-frame.freehand` and `re-frame.ui`

**Operator ruling, Mike, 2026-08-14:** *"Freehand and re-frame2.ui are to be retired and removed
ASAP."* The target architecture is a Reagent adapter and a UIx adapter, plus hicasso as re-frame2
native. That is settled; nothing below re-argues it. This page establishes what stands in the way,
in what order it must fall, and what proves the build never breaks between steps.

Filed under `rf2-0yp7w` phase 1. Census re-taken at `85a70687e4` on 2026-08-15 15:34 AUSEST.

This page is the *plan*, and its subject is the two trees that go. The one-page record of
what became of all three donor view surfaces — including the Hicasso bench tree, which is
kept as evidence and is no part of this plan — is [`donor-surfaces.md`](donor-surfaces.md)
(`rf2-hic-062`).

## Why this page is not in `docs/design/freehand/`

The obvious home is the withdrawn programme's own design tree — but that tree is one of the things
this plan deletes, and a plan filed inside its own demolition site stops being readable at exactly
the moment someone needs to check whether the demolition was complete. `docs/design/retirement/`
is a new sibling of `design/freehand/` and `design/hicasso/`, added to `mkdocs.yml`'s
`exclude_docs` the same way they are: a working design record, tracked and greppable, not a page
of the built site. `scripts/check_doc_slugs.py` still validates its links and anchors, and that
checker runs unconditionally in `test.yml`'s `verify-readme-links` job.

## The census, re-taken

Every number here was captured by the command beside it, in a clean worktree off `origin/main` at
`85a70687e4`. `git ls-files` produced the tree counts; `rg` produced the reference counts, because
`git grep` under-reports on at least one supported checkout.

| Quantity | Measured | Command |
|---|---|---|
| `implementation/freehand` tracked files | **376** | `git ls-files implementation/freehand \| wc -l` |
| `implementation/ui` tracked files | **231** | `git ls-files implementation/ui \| wc -l` |
| Both trees together | **607** | `git ls-files -- implementation/freehand implementation/ui \| wc -l` |
| Files outside those trees naming `freehand` | **425** | `rg -il --hidden freehand -g '!implementation/freehand/**' -g '!implementation/ui/**' -g '!.git/**'` |
| Files outside those trees naming `re-frame.ui` / `implementation/ui` | **235** | same, `-e 're[-_]frame[./]ui\b' -e 'implementation/ui\b'` |
| Union of the two, `.beads/issues.jsonl` excluded | **508** | union of the two lists, `sort -u` |

The tree counts were verified three ways (`git ls-files … | wc -l`, `git ls-files | grep -c '^…'`,
and a combined `git ls-files --` over both paths) and agree. Every one of the 509 raw reference
hits is a **tracked** file — verified by `comm -12` against `git ls-files` — so nothing here is
inflated by generated or gitignored output. `rg` honours `.gitignore`, which already excludes
`/site/`, `/ai/`, `/docs/spec/`, `/docs/migration/` and the playground bundle; `--hidden` is
required or `.github/` is silently skipped, which cost this survey one wrong count before it was
caught.

**The epic's headline of 821 files is stale by 214.** PR #8202 and PR #8210 moved the hicasso
bench harness out of `implementation/freehand/test/` and into `implementation/hicasso/test/`
(205 files under `implementation/hicasso/test/re_frame/bench/hicasso`, plus four shared Node
helpers now at `implementation/core/test/re_frame/bench/`). That was the epic's own stated phase 0
and it has already landed.

### Classification

The 508 referencing files sort into three classes. The rule is path-shaped and stated here so the
counts can be reproduced or disputed: **(b)** is anything under a `test/` directory, a `_test.*`
file, a testbed, `examples/`, `spec/conformance/`, a lint fixture, a probe or a scaffold;
**(a)** is anything under `.github/`, `scripts/`, `implementation/scripts/`, a `deps.edn`, a
`package.json`, `shadow-cljs.edn`, `mkdocs.yml`, `frozen-sources.edn`, a `.cjs`/`.py`/`.sh` gate,
`tools/*/src/`, or the api-manifest generator; **(c)** is everything left, which is prose.

| Class | Files | What it means |
|---|---|---|
| (a) live dependency that must be re-homed or edited | **91** | build config, CI, gate scripts, generators, tool source |
| (b) test, fixture, example or testbed that dies with the tree | **180** | removed with the tree, or repointed where it tests something generic |
| (c) prose to re-point or delete | **237** | spec, docs, skills, READMEs, and source comments |

### The census patterns, and the way each one lies (rf2-g633h)

The three patterns above — `freehand`, `re-frame.ui`, `re-frame2-ui` — are what R1–R6 were
dispatched against, and each successive sweep found the same thing: **a pattern that names the
donor cannot see a citer that does not.** Three further spellings were discovered one at a time,
always by a worker reading a file rather than by a grep. They are collected here so R6's tail and
any later residue sweep can start from six rather than rediscover them individually. Re-measured on
this branch at `origin/main` HEAD, `.beads/issues.jsonl` excluded throughout because `bd` rewrites
it in every checkout.

| # | Pattern | Measured here | What it misses, or falsely claims |
|---|---|---|---|
| 1 | `freehand` | (see table above) | nothing structural; the reliable one |
| 2 | `re-frame.ui` | (see table above) | the alias spellings, rows 5–6 |
| 3 | `re-frame2-ui` as a raw substring | **106 hits / 52 files** | **60% noise** — see below |
| 4 | `004D` | **41 hits / 19 files** | conflates two unrelated documents — see below |
| 5 | a bare namespace alias (`ui/mount`, `ui.test`) | 25 hits in `spec/004C` alone | unusable repo-wide — see below |
| 6 | a bare view alias (`v/…`) | 148 hits in `spec/004-Views.md` alone | unusable repo-wide — see below |

**Pattern 3 is 60% noise, and the noise is all first-class supported surface.** Matched as a raw
substring, `re-frame2-ui` matches `re-frame2-uix` — the Maven coordinate of the live UIx adapter.
Of the 106 raw hits, **64 across 34 files are `re-frame2-uix`**, every one naming a supported
adapter rather than a donor; word-boundaried (`re-frame2-ui` not followed by `x`) the honest figure
is **42 hits across 26 files**. A sweeper running the raw form reads the UIx adapter's own
coordinate as residue, in `implementation/deps.edn`, three published `docs/core/` pages and the
setup skill among others. **Word-boundary this pattern or do not run it.**

**Pattern 4 matches two unrelated retirements, and attributing it to one of them is wrong.** Two
different specs were called 004D at different times: `spec/004D-UI-Test-Selectors.md`, deleted by
`37fc6c6f80` when rf2-n7jtp minimised `ui.test` to its six-name surface, and
`spec/004D-Freehand-Compiled-Grammar.md`, deleted by `0042a14fe2` under rf2-0yp7w.11. The hits in
`spec/009-Instrumentation.md` and the opening of `docs/EP/EP-0034` are the **selector** grammar,
not the compiled-view grammar, so a sweep that repoints every `004D` at the Freehand disposition
mis-states what happened to them. Read each hit before classifying it.

**Patterns 5 and 6 do not survive contact with a repo-wide grep, and that is the whole finding.**
A `:require … :as ui` severs the donor name from every use site below it, which is why
`spec/004C-Roots-and-Mount.md` could carry `ui.test` on 9 lines, `ui/mount`-family calls on 8 and
bare `v/…` on 8 — one section *titled* `ui.test/render` — while returning zero for patterns 1–3.
But the aliases are generic English, and run repo-wide they match live code overwhelmingly:
`\bui\.test\b` hits **64 files**, of which **34 are the Story tool's own live `story.ui.test-mode`
namespace**, and `v/…` hits **85 files** including live hicasso bench apps, an Xray test,
`re-frame.routing`, `mkdocs.yml` and three binary PNGs. **The alias spelling is only meaningful
file-locally**: establish first that a given file binds the alias to a donor namespace, then read
its bare uses. There is no repo-wide form of this check, and a sweeper who invents one will
generate hundreds of false rows against supported surface.

**Where rows 5–6 bite, measured rather than assumed.** They are real in `spec/` and absent from
`docs/`: the alias census over `docs/` returned a delta of two raw files and **zero** genuine rows,
both false positives on `:ui/local-theme`, an app-authored coeffect keyword. Measure per tree
before generalising.

**Every count above is a claim, and the grep that checks it is a different claim.** Each figure
here was taken with `git grep` over tracked files, which is authoritative on this repo where plain
`grep` has returned false empties; each pattern was run once against something it should find
before its zero was believed. The two `004D` mentions that must **stay** are the note in
`scripts/check_adapter_disposition.py` recording the removal and the synthetic heading string in
`api_md_check_test.clj`, which reads no spec file.

## The epic's central premise is false, and I re-verified it independently

The epic states that `implementation/core/src` and `implementation/ssr/src` carry live runtime
dependencies on Freehand, and that "nothing else is safe until those are clear". A prior mapper
reported the same premise false on 2026-08-14; this survey re-derived the result from scratch
rather than trusting that report, and it holds.

**There is not a single `:require` of `re-frame.freehand.*` or `re-frame.ui.*` anywhere in
`implementation/core/src`, `implementation/ssr/src` or `implementation/ssr-ring/src.`** A repo-wide
search for require vectors naming either namespace returns hits only in tests, examples, testbeds,
conformance fixtures, the api-manifest probe, and `shadow-cljs.edn` build entries. Nothing in
shipped core or SSR runtime code requires either tree.

The reference counts the epic read as coupling are, almost entirely, comments and docstrings.
Here is what is actually there:

| Where | Lines | Reading |
|---|---|---|
| `core/src`, `freehand` | 31 across 8 files | 27 prose; 1 code; 3 data |
| `core/src`, `re-frame.ui` | 49 across 14 files | 45 prose; 4 data |
| `ssr/src`, `freehand` | 4 across 2 files | all prose |
| `ssr/src`, `re-frame.ui` | 34 across 7 files | all prose — but see the provenance pin below |
| `ssr-ring/src`, `freehand` | 3 across 2 files | all prose |

The seven non-prose references, in full:

1. **`core/src/re_frame/frame.cljc:4616`** — `(safe-call-hook! :freehand/on-frame-destroyed! id
   expected-incarnation-token)`. A call **by keyword** through the late-bind registry, documented
   as a no-op when the artefact is absent. **Dies with Freehand:** its only producer is
   `re-frame.freehand.root`.
2. **`core/src/re_frame/frame.cljc:4488`** — `(safe-call-hook! :ui/on-frame-destroyed! id)`, step 4
   of the destroy recipe. **Dies with `re-frame.ui`:** its only producer is `re-frame.ui.frames`.
   This one is not free — see "the one real code-path deletion" below.
3. **`core/src/re_frame/late_bind/directory.cljc:1164`** — the `:freehand/on-frame-destroyed!`
   registry row, `:producer-ns 're-frame.freehand.root`. A quoted symbol in a data map, never
   resolved at compile time. **Dies with Freehand.**
4. **`…/directory.cljc:1159`** — the `:ui/on-frame-destroyed!` row, `:producer-ns
   're-frame.ui.frames`. **Dies with `re-frame.ui`.**
5. **`…/directory.cljc:962`** — `re-frame.ui.substrate` is one entry in the `:adapter/current-frame`
   row's producer **vector**, alongside four adapters that stay. **One element to drop; the row
   survives.**
6. **`…/directory.cljc:1038`** — the same shape in
   `:adapter/arm-hiccup-emitter-if-unarmed!`. **One element to drop.**
7. **`ssr/src/re_frame/ssr/ui_tree.cljc`** — eight conversion tables and `escape-html`, carried as
   deliberate verbatim copies because the Independence rule forbids `re-frame.ssr` requiring
   `re-frame.ui`. **The code stays** — SSR needs it and always did. What dies is the *provenance
   pin*: `implementation/ssr/test/re_frame/ssr/emit_ui_tree_cljs_test.cljc` asserts all eight
   tables byte-identical to `re-frame.ui.rules`, and that test's reference side disappears. The
   20 docstring lines in `ui_tree.cljc` that name `re-frame.ui.rules` as provenance must be
   re-pointed in the same change, or the file will claim an origin nothing can check.

**So: five of the seven die with the trees, two are single-element edits to surviving rows, and
none is a re-homing or a re-implementation.** Freehand and `re-frame.ui` can be deleted without
changing a line of shipped core or SSR runtime *behaviour*. A reference count cannot distinguish a
require from a comment, and the epic's histogram was read as though it could.

### The one real code-path deletion

`:ui/on-frame-destroyed!` is not merely a registry row. It is **step 4 of the normative Spec 002
destroy recipe** (`spec/002-Frames.md:722`, "Compiled-view observer teardown"), it is called at
`frame.cljc:4488`, and four `implementation/core/test` files drive it through
`late-bind/set-fn!`. Removing it renumbers a normative recipe in a **hot-zone spec file**.

The four tests are worth reading carefully before anyone deletes them. They use
`:ui/on-frame-destroyed!` as a convenient *live hook key* to exercise generic teardown mechanics —
ordering against `claim-frame-destroy!`, incarnation scoping, failure accumulation. That machinery
survives. Those tests get **repointed**, most naturally to the `:hicasso/on-frame-destroyed!`
sibling at `frame.cljc:4631`, not deleted. Mistaking a repoint for a deletion here would quietly
drop coverage of the destroy recipe's ordering guarantees.

## What actually orders the work

Because there is nothing to untangle in core and SSR, the epic's suggested step 2 is empty, and
its claim that nothing else is safe until core is clear is not a constraint. Four things order the
work instead.

**The two trees are independent, and CI asserts it.** `test.yml`'s `jvm-freehand` job carries a
step that greps `implementation/freehand` for `re-frame.ui` — described in
`scripts/check_fast_pr_gap.py` as exactly that. So the retirement runs as **two parallel lanes**,
each internally sequential.

**Some edits must be atomic with their deletion.** `implementation/shadow-cljs.edn:528` carries
`:build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}` — a **repo-wide default
on every build**. Delete `implementation/ui/src` without that line and every build in the
repository fails to resolve the hook symbol. Same commit, not before, not after. Its Freehand
counterpart is `implementation/deps.edn:63` (`day8/re-frame2-freehand {:local/root "freehand"}`)
plus the `freehand/test` extra-path, and `implementation/package.json`'s `test:bspine-compile`,
which is the `Uncovered bench-namespace compile gate` step of the **required** `cljs` job and runs
a file inside `implementation/freehand/`.

**Prose is a blocker on one surface, not a trailer.** Twenty-eight links across eight `spec/` files
point at `spec/conformance/freehand/conformance-index.md`, and `scripts/check_doc_slugs.py` runs
unconditionally in `test.yml`'s `verify-readme-links` job under `All required checks passed`.
Delete the corpus before those links go and a required check reds **on every PR**. The epic ranks
spec prose last and lowest-risk; for this subset that is backwards.

**The generated-artefact chain serialises merges.**
`implementation/scripts/api-manifest/src/re_frame/api_manifest/gen.clj` hardcodes a roster of
**62 distinct `re-frame.freehand.*` namespaces** plus the `re-frame.ui` set, gated by three JVM
tests, generating `spec/api-manifest.edn`, which `ui_context.clj` reads to generate the committed
`skills/re-frame2-ui/references/ui-context.md`. The generator *refuses to build* when a source
namespace is named by neither of its rosters, so the roster edit must ride the same commit as the
tree deletion, and the regenerated `spec/api-manifest.edn` and `docs/api/*.md` must ride it too.
This is the one place where two open PRs will always conflict; it takes one toucher and gets
merged fast.

## Reconciling with the CI-lane retirement already recorded

`TESTING.md` § *Retirement order for the Freehand CI lanes* already rules a three-stage retirement
for `freehand-bench.yml`, `freehand-conformance.yml` and `portability.yml`, with checkable
triggers. That record is good and this plan does not replace it — but **one of its triggers is now
discharged by the operator ruling and the record does not yet say so.**

Stage 2's trigger includes HD-018's win conditions, and `TESTING.md` states that HD-029(a) left
them undischarged, so "the fork is decided; the deletion is not yet authorised". HD-018 reopens
only by operator ruling. **Mike's 2026-08-14 ruling is that ruling.** Until `TESTING.md` records
it, every worker who traces stage 2 will find an undischarged trigger and stall — which is why
recording it is the first bead rather than a footnote.

Two of the three stage-2 conditions are in any case already met: the frozen-sources donor root now
sits at `implementation/hicasso/test/re_frame/bench/hicasso` and touches `implementation/freehand/`
not at all, and rf2-hic-062's keep-as-evidence relocation has landed in both halves.

`TESTING.md`'s stage-3 quantities reproduce exactly under `rg`, which is worth recording because
that section warns the `git grep` form can fail open: **68 `FH-*` references across seven files**
outside the corpus (`004-Views.md` 49, `004D` 5, `004C` 4, `004B` 4, `api-manifest-metadata.edn` 2,
`012-Routing.md` 2, `011-SSR.md` 2) and **28 conformance-index link targets across eight files**.
Two independent means, same numbers.

## The phases

Nine beads in two lanes. Every phase is one worker, one surface fence, and every hot-zone surface
has exactly one owner and is sequenced, never parallel.

| # | Bead | Lane | Depends on | Hot zone |
|---|---|---|---|---|
| R0 | Record the ruling in `TESTING.md` | shared | — | no |
| R1 | Unpoint the 68 `FH-*` ids and 28 index links in `spec/` | shared | — | **spec** |
| R2a | Unwire every `re-frame.ui` consumer outside the tree | ui | — | no |
| R2b | Atomic: build config + delete `implementation/ui/` | ui | R2a | **shadow-cljs.edn, deps.edn** |
| R3a | Unwire every `re-frame.freehand` consumer outside the tree | freehand | — | no |
| R3b | Atomic: build config + delete `implementation/freehand/` | freehand | R3a | **shadow-cljs.edn, deps.edn** |
| R4 | Retire the two `on-frame-destroyed!` hooks and Spec 002 step 4 | shared | R2b, R3b | **spec/002** |
| R5 | Delete the conformance corpus and its workflows | shared | R1, R3b | **spec, .github/workflows** |
| R6 | The prose sweep | shared | R2b, R3b, R5 | **spec** |

**R0, R1, R2a and R3a can all start at once** — four surfaces, no overlap, nothing deleted yet.

**The point of no return is R2b and R3b.** After those two land the trees are gone and the work is
cleanup. Neither can land before its `a` sibling, because each deletes a tree that other files
still require; and each must be a single commit, because the build-hook and the classpath entry
cannot be separated from what they point at.

**What proves the build never breaks.** Every phase except R2b and R3b is green on the ordinary PR
spine, because it removes consumers rather than producers: `scripts/test-fast-pr.sh` locally, then
`All required checks passed`. R2b and R3b each need one thing the spine does not give for free —
a full `shadow-cljs` compile of the remaining build ids, since a stale `:build-hooks` or a dangling
`:entries` symbol fails at compile rather than at test. Each of those two beads therefore runs
`scripts/test-jvm-implementation.sh` **and** `npm run test:cljs` **and** an explicit compile of
every build id it touched, in the foreground, to completion. R5 additionally runs
`python scripts/check_doc_slugs.py`, which is the check its ordering exists to protect.

### What is in each phase

**R0** is small: `TESTING.md`'s stage table plus the paragraph that traces stage 2 to HD-018.

**R1** is the largest prose job and the one with a real deadline, because R5 waits on it.
`004-Views.md` alone carries 49 ids behind 17 links. **It raised one question this survey would not
answer on Mike's behalf, and that question has since been ruled:**
`spec/004D-Freehand-Compiled-Grammar.md` was named for the thing being retired, so did 004D die, or
was it renamed and re-aimed at hicasso's grammar? **Ruled DELETED, and not re-aimed** (rf2-0yp7w.11,
executed by `0042a14fe2`), precisely so that donor-era normative text is not laundered into
hicasso's; a hicasso-native grammar spec is left as a separate later judgement, and the Form-1/2/3
grammar is deliberately homeless in the meantime. R1 was therefore right to stop at the unpointing.
For the citations that recur while re-pointing: `spec/API.md`'s `reg-view*` row names Reagent
Form-3 / `create-class` explicitly, `spec/002-Frames.md` states the plain-fn-cannot-read-context
rule verbatim, and `spec/001-Registration.md`'s table files `reg-view` / `reg-view*` to 004-Views
and API. Everything else de-addresses — keep the rule, drop the pointer — or is cut.

**R2a** removes the six `implementation/ssr/test` files that require `re-frame.ui` — re-pointing
`ui_tree.cljc`'s provenance docstrings as it goes — plus the `re-frame.ui` arm of
`implementation/core/test/re_frame/elision_probe.cljs`, two
`implementation/adapters/reagent/test` suites, `implementation/scripts/bundle-isolation-positive-control`'s
ui control, and the nine `examples/real-apps/realworld_resources/ui_*` files.

**R2b** is one commit: `shadow-cljs.edn` (line 528's build-hook, the `ui/src` and `ui/test` source
paths, and the `:ui`, `:node-test-ui`, `:ui-bench`, `:ui-g13`, `:ui-g13-prod`, `:ui-g8` and
proof-pack build ids), `implementation/deps.edn`'s `ui/test` extra-path, the seven `test:ui*` npm
scripts, the five `implementation/scripts/{check,run}-ui-*.cjs` gates,
`scripts/check_ui_root_lifecycle_drift.py`, the `re-frame.ui` half of the api-manifest roster with
its regenerated outputs, and `implementation/ui/` itself — 231 files.

**R3a** removes `tools/xray/testbeds/freehand_views/` and its `:testbeds/freehand-views` build id
and port 8036, `examples/ui/minimal-counter/`, the Reagent adapter's
`freehand_cell_under_ratom_adapter_dom_cljs_test.cljs`, and the freehand arm of the api-manifest
probe test. **`tools/story`'s presence bridge is already covered by the open bead rf2-5gka** and
should land through that rather than be duplicated here.

**R3b** is one commit: `implementation/deps.edn:63` and the `freehand/test` extra-path,
`shadow-cljs.edn`'s freehand source paths and its eight `:freehand-*` / `:node-test-freehand` /
`:browser-test-freehand-bench` build ids, the ten `*freehand*` npm scripts plus the Freehand-resident
files that `test:bspine-compile` and `test:script-helpers` run, the three
`implementation/scripts/check-freehand-*.cjs` gates, `scripts/test-freehand-prod-gate.sh`,
`.github/workflows/freehand-bench.yml`, the 62-namespace roster in `gen.clj` with its three JVM
tests and regenerated outputs, and `implementation/freehand/` itself — 376 files.

**R4** removes both `on-frame-destroyed!` rows and call sites, drops `re-frame.ui.substrate` from
the two surviving producer vectors, renumbers the Spec 002 destroy recipe, and **repoints** the
four core tests rather than deleting them.

**R5** was planned to delete `spec/conformance/freehand/` (59 files),
`.github/workflows/freehand-conformance.yml`, `scripts/check_freehand_conformance_index.py` and
`scripts/check_donor_inventory.py`, and to trim `.github/scripts/report-changed-surfaces.sh` with
its `_changed-surfaces.test.cjs` mirror.

**Two of those numbers were wrong and one of those deletions cannot happen yet — measured
2026-08-16.** The corpus is **116** tracked files, not 59 (113 fixtures plus three Markdown pages;
59 was a count of files *mentioning* "freehand" under `spec/conformance`, which is a different
question). And the corpus is a **compile-time input to `implementation/freehand/test/`**:
`conformance.cljc` reads `fixtures/*.edn` at macro-expansion time and `roster.cljc` reads
`conformance-index.md` the same way, both failing the compile by design when the file is absent.
Deleting the corpus while `implementation/freehand/` stands therefore reds `jvm-freehand`, `cljs`
and `cljs-browser` — three REQUIRED jobs. Reproduced by moving the corpus aside and loading the
suites. So the corpus, the workflow and `check_freehand_conformance_index.py` ride **R3b**, and
dropping the validator earlier would disarm a gate whose subject still stands.

**What R5 did land** is the half with no such coupling: `scripts/check_donor_inventory.py` and
`spec/conformance/freehand/donor-inventory.md`, deleted together in one commit, with
`freehand-conformance.yml` **narrowed** to its index job rather than removed, and the archive's
arms trimmed out of the classifier and its mirror. See `TESTING.md` § "Retirement order for the
Freehand CI lanes", stages 2 and 3.

**The R5 residual landed with R3b on 2026-08-16, exactly where the coupling put it.** The remaining
**115** files of `spec/conformance/freehand/`, `.github/workflows/freehand-conformance.yml` and
`scripts/check_freehand_conformance_index.py` were deleted in the same commit as
`implementation/freehand/`, together with the corpus's three classifier arms (the fixtures root, the
index, and the measured `README.md` exclusion) and their mirror tests. Both retirement conditions
were re-measured immediately before the cut rather than inherited: external `FH-*` citations under
`spec/` outside the corpus read **0** against a positive control of **366** ids inside it, and
`check_doc_slugs.py` and `check_readme_links.py` both exited 0 afterwards. R5's own bead
(rf2-0yp7w.8) closes as absorbed rather than being dispatched as a second corpus deletion.

**R6** is the remaining ~237 prose files: `docs/design/freehand/`, the `docs/api` pages, `docs/skills`,
`skills/re-frame2-ui/` entire, `mkdocs.yml`'s nav, the root `README.md`/`CHANGELOG.md`/`AGENTS.md`/
`CLAUDE.md` mentions, `spec/Conventions.md`'s Freehand section and `:rf.adapter/*` row,
`spec/006-ReactiveSubstrate.md`'s adapter table, `spec/API.md`'s rows, `spec/Ownership.md`, and the
~80 comment and docstring lines left in `core/src`, `ssr/src`, `ssr-ring/src` and `tools/xray/src`.

**`docs/EP/EP-0030` through `EP-0036` are historical programme records and this plan does not
delete them.** A withdrawn programme's EP is the reason the withdrawal is legible; deleting it
would leave the retirement itself unexplained. Same for the `docs/design/hicasso/` pages that cite
Freehand as the substrate hicasso replaced — those are hicasso's record, not Freehand's.

### What remains to be broken down

R6's 237 files will not fit one worker and should be split by sub-surface when it is dispatched —
spec is one toucher, everything else parallelises. **The R1 contingency is discharged**: it was to
split R1 if the 004D question came back as "rename and re-aim", and it came back as "delete"
(rf2-0yp7w.11, recorded above). Nothing else here should need further division.

What R6's sub-workers do still need is the six-pattern list in
[§The census patterns](#the-census-patterns-and-the-way-each-one-lies-rf2-g633h) rather than the
three the epic was written against, because a sweep run on three patterns reports a surface clean
while three more spellings of the same name stand on it.

## What bears on Xray

Two things, only one of which is retirement work.

`tools/xray/src/day8/re_frame2_xray/mount.cljs:220` holds
`#{:rf.adapter/ui :rf.adapter/uix :rf.adapter/helix :rf.adapter/freehand}` — a **live set the mount
path reads**, not a comment: the adapter kinds whose `:render` takes React elements, which Xray's
hiccup shell refuses to mount through. **It needs no change for this retirement.** The file already
records that `:rf.adapter/freehand` stays on the same defensive footing as `:rf.adapter/helix`
(removed at S7/W13) because a stale co-loaded build could still present the kind, and it already
cites `rf2-0yp7w` by name. `docs/design/hicasso/product/tool-consumer-census.md` reaches the same
disposition. Leave it.

The health question is the other one, and it is not about Freehand at all: **hicasso appears
nowhere in that set, and `:rf.adapter/hicasso` does not exist anywhere in the tree.** A repo-wide
search finds no such keyword and no `:rf.adapter/` value anywhere under
`implementation/hicasso/src`. So on the substrate that is becoming re-frame2 native, Xray's
refusal test consults a set that cannot name it. Whether that is correct depends on how hicasso
relates to the adapter contract — it may install no adapter at all, in which case the set is
simply not the mechanism — but the set enumerates two retired substrates and omits the live one,
and that asymmetry should be looked at by whoever is getting Xray right. It is filed as **rf2-wtznc**
rather than folded in here, because it is Xray's question and not the retirement's.

## The beads

| Phase | Bead |
|---|---|
| R0 | `rf2-0yp7w.1` |
| R1 | `rf2-0yp7w.2` |
| R2a | `rf2-0yp7w.3` |
| R2b | `rf2-0yp7w.4` |
| R3a | `rf2-0yp7w.5` |
| R3b | `rf2-0yp7w.6` |
| R4 | `rf2-0yp7w.7` |
| R5 | `rf2-0yp7w.8` |
| R6 | `rf2-0yp7w.9` |
| — | `rf2-wtznc` (Xray health, discovered here, not retirement work) |
