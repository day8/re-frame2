# Template — deps-new Rebuild Plan

> **Normative spec for the deps-new migration (rf2-dolpf).** Stages 2-4
> are the actionable plan; each sub-stage is scoped to a single worker
> run (~30-60 minutes). Stage 1 (audit + scoping) produced this doc and
> the local-only findings note referenced below.
>
> **Scope.** This doc owns the migration plan only. Once Stage 2 lands,
> the steady-state spec for the deps-new template lives across the
> updated `000-Vision.md`, `001-Substrate-Variants.md`,
> `002-Generated-Shape.md`, `API.md`, `DESIGN-RATIONALE.md`, and
> `Principles.md`. This file becomes a historical record of the
> migration shape.

## §0 Lineage

The current template is a [clj-new](https://github.com/seancorfield/clj-new)
template at `tools/template/`, published as `day8/clj-template.re-frame2`
on Clojars (publish pipeline wired via clein). Per Mike's lock
(template walkthrough Q2, 2026-05-12), the rebuild moves to:

- **[deps-new](https://github.com/seancorfield/deps-new)** as the
  template framework — programmatic `:data-fn` / `:template-fn` /
  `:post-process-fn` hooks layered on a declarative `template.edn`
  + a `root/` content tree.
- **Git-coord distribution** instead of Clojars — the published
  artefact is a tagged commit on `day8/re-frame2-template`, invoked
  via `clojure -Tnew create :template io.github.day8/re-frame2-template`.
- **Initial home `tools/template/`** during transition; **final home
  the dedicated `day8/re-frame2-template` repo**.

The full audit — current template inventory, deps-new vs clj-new
comparison, risk list — lives in the worker-local findings note
`ai/findings/2026-05-20-rf2-dolpf-template-deps-new-audit.md`
(local-only per `docs/the-mayor-method.md`).

## §1 Final shape (steady state, post-migration)

```
day8/re-frame2-template/                ; eventually its own repo
├── deps.edn                            ; declares Clojure + deps-new (test scope)
├── README.md                           ; invocation, quick start
├── template.edn                        ; deps-new declarative config
├── root/                               ; deps-new content root
│   ├── _shared/                        ; substrate-agnostic; emitted to every project
│   │   ├── README.md
│   │   ├── gitignore                   (→ .gitignore at emit time)
│   │   ├── editorconfig                (→ .editorconfig)
│   │   ├── clj-kondo/config.edn        (→ .clj-kondo/config.edn)
│   │   ├── cljfmt.edn                  (→ .cljfmt.edn)
│   │   ├── lefthook.yml                (→ lefthook.yml)
│   │   ├── events.cljs · subs.cljs · events_test.cljs
│   │   ├── index.html · resources/public/css/app.css
│   │   └── dev/{user.clj, scratch.cljs}
│   ├── _reagent/                       ; per-substrate; emitted only when :substrate :reagent
│   │   ├── deps.edn · shadow-cljs.edn · package.json
│   │   ├── core.cljs · core_with_stories.cljs
│   │   └── views.cljs
│   ├── _uix/                           ; same shape (no with-stories variant in v1)
│   └── _helix/                         ; same shape (no with-stories variant in v1)
├── src/day8/re_frame2_template/
│   └── hooks.clj                       ; :data-fn / :template-fn / :post-process-fn
├── spec/                               ; this folder; ports as-is
└── test/                               ; per-substrate JVM tests; minimal port from clj-new shape
```

### §1.1 `template.edn`

```clojure
{:description "Scaffold a new re-frame2 application (Reagent / UIx / Helix)."
 :data-fn        day8.re-frame2-template.hooks/data-fn
 :template-fn    day8.re-frame2-template.hooks/template-fn
 :post-process-fn day8.re-frame2-template.hooks/post-process-fn}
```

No static `:transform` — `template-fn` builds the transform vector at
run time from the substrate + opt-in flags. The static `template.edn`
is intentionally minimal; the programmatic body owns the decisions.

### §1.2 Hook fns (`src/day8/re_frame2_template/hooks.clj`)

```clojure
(ns day8.re-frame2-template.hooks)

(defn data-fn
  "Augment deps-new's substitution data with re-frame2 template fields.

   Receives the merged map (deps-new's auto-derived keys + the user's
   CLI args). Returns the extra k/v pairs to merge.

   Coerces :substrate / :include-story? / :css / :include-ssr? from
   the wider command-line shape (keyword / string / symbol) into
   strict internal keywords. Throws on out-of-set values."
  [data]
  ,,,)

(defn template-fn
  "Read the augmented data; emit a template-edn (with :transform)
   that drives deps-new's file emission.

   Constructs the :transform vector from:
     - :substrate     → maps _<substrate>/* → top-level (per the chosen substrate only)
     - :include-story? → conditionally adds stories.cljs + with-stories core
     - :css           → swaps in the Tailwind app.css when :css :tailwind (rf2-gthro)
     - :include-ssr?  → adds the SSR build (rf2-0m5ea)"
  [edn data]
  ,,,)

(defn post-process-fn
  "After file emission, run any final fix-ups (e.g. fix-up of files
   deps-new can't natively rename, like the gitignore dot-strip).

   Returns nil."
  [edn data]
  ,,,)
```

The three flags are the **locked v1 set** (rf2-dolpf): no other branching
toggles permitted. Future toggles slot in here; the spec rule is
"justify in DESIGN-RATIONALE before adding."

### §1.3 Invocation

```bash
# Reagent (default)
clojure -Tnew create \
        :template io.github.day8/re-frame2-template \
        :name acme/my-app

# UIx, with all v1 flags
clojure -Tnew create \
        :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix \
        :include-story? true \
        :css :tailwind \
        :include-ssr? true

# Pinned to a specific release tag
clojure -Tnew create \
        :template io.github.day8/re-frame2-template#v0.0.1 \
        :name acme/my-app
```

`:edn-args` is **gone** — deps-new takes template args directly as
top-level k/v pairs after `:name`. The clj-new-era plumbing rationale
(DESIGN-RATIONALE §2) is obsolete and gets archived.

## §2 Stage 2 — deps-new template body (build it)

Goal: replace the clj-new template body with a deps-new equivalent.
Existing clj-new template stays unchanged (and still works) until
Stage 4 cutover.

Suggested commit decomposition (each ≤ 60 min worker scope):

### §2.1 — Spike: minimal deps-new template (rf2-cwvnj — DONE 2026-05-20)

- ✓ Added `template.edn` + `src/day8/re_frame2_template/hooks.clj`
  alongside (not replacing) the existing clj-new tree. The template
  body lives at `tools/template/resources/day8/re_frame2_template/`
  with `template.edn` + `root/` + per-substrate `_reagent/` and
  shared-renames `_shared/`.
- ✓ Implemented `data-fn` for one CLI flag (`:substrate`),
  `template-fn` for one resource sub-tree (Reagent only) + the
  `_shared/` set.
- ✓ Manual end-to-end smoke succeeds:
  ```
  clojure -Sdeps '{:deps {day8/re-frame2-template
                          {:local/root "<abs>/tools/template"}}}' \
          -Tnew create :template day8/re-frame2-template \
                       :name acme/my-app
  ```
  Emits a Reagent-only counter app with the expected 18-file
  inventory (dotfiles renamed, namespace paths under
  `src/acme/my_app/`, `test/acme/my_app/`).
- ✓ Risks 1 + 3 flushed (see §2.1 spike findings below).

**Spike findings — risks 1 + 3 resolved:**

- **Risk 1 (substitution-data key set).** deps-new's
  `preprocess-options` populates `:top` / `:main` / `:name` /
  `:artifact/id` / `:group/id` / `:scm/*` / `:target-dir` etc. as
  primitive strings; the `/ns` and `/file` derivatives (`:top/file`,
  `:main/ns`, …) are computed later by `->subst-map` AFTER `data-fn`
  + `template-fn` have run. Our `data-fn` therefore re-derives them
  inline (`->file-path` / `->ns-form` helpers in
  `day8.re-frame2-template.hooks`) so `template-fn`'s rename targets
  can use the proper namespace-path values. **No clj-new substitution
  key is missing under deps-new** — `{{name}}` / `{{namespace}}` /
  `{{nested-dirs}}` carry over with identical semantics; the `data-fn`
  computes `{{namespace}}` + `{{nested-dirs}}` explicitly (clj-new's
  `project-data` provided them; deps-new doesn't, but they're trivial
  to derive from `{{top}}` + `{{main}}`).
- **Risk 3 (`_shared/` + `_<substrate>/` shape).** deps-new's
  `:transform` mechanism cleanly supports the `_shared/` + per-
  substrate layout via `:only` flags and file-map renames. The
  cleanest shape:
  - `<template-dir>/root/` — files that land at default location
    (README.md, lefthook.yml, dev/*, resources/public/*); bulk-
    copied by deps-new's `:root` mechanism.
  - `<template-dir>/_shared/` — files needing renames (dotfile
    renames + namespace-path renames for src/test sources); copied
    via a `:transform` with `:only`.
  - `<template-dir>/_reagent/` (and `_uix/` + `_helix/` in §2.2) —
    substrate-specific files; one `:transform` entry per substrate,
    chosen by `template-fn`'s `case` on `:substrate`.

  No deps-new transform-feature gaps were uncovered. The shape ports
  to §2.2 unchanged: more underscore sub-dirs, more case clauses.

**Footguns surfaced (not blockers, document for §2.2):**

- `io.github.*` template names trigger deps-new's auto-git-clone
  (`auto-git-url` in `tools.deps.extensions.git`) BEFORE classpath
  lookup. So local-dev smokes must use a non-`io.github.*`
  qualifier (`day8/re-frame2-template` works). The §3 publish step
  copies the template body to the `io/github/day8/re_frame2_template/`
  path inside the dedicated repo so the steady-state invocation
  (`:template io.github.day8/re-frame2-template`) resolves via
  `find-root` against the git-clone.
- `:template-fn` runs AFTER `:data-fn`; rename targets in the
  file-map are pure Clojure strings, so `template-fn` can reference
  `data-fn`'s additions directly (no Selmer indirection).
- `:only` is mandatory on per-substrate transforms to suppress the
  implicit bulk-copy (otherwise `_reagent/` content lands at `./`
  with its underscore-prefixed parent directory).

**Gate:** Manual smoke gate met. `npm install && npx shadow-cljs
watch app` against the emitted app is deferred to §2.2 once the
re-frame2-causa Clojars artefact lands (rf2-y9zqc) — today the
emitted `deps.edn` pins `0.0.1.alpha` which isn't published yet, so
the deps resolution would fail at run time regardless of the
template's correctness. Static-shape check + content-substitution
check on the emitted tree is the spike's effective signal.

### §2.2 — Port the full resource tree (rf2-c2770 — DONE 2026-05-20)

- ✓ Migrated `_shared/`, `_reagent/`, `_uix/`, `_helix/` content out of
  `src/clj/new/re_frame2/` to the deps-new tree at
  `tools/template/resources/day8/re_frame2_template/`. Per-substrate
  sub-trees (`_reagent/`, `_uix/`, `_helix/`) each carry their
  substrate-specific `core.cljs` + `views.cljs` + `deps.edn` +
  `shadow-cljs.edn` + `package.json`. `_shared/` carries the
  substrate-agnostic files that need renames (dotfile renames +
  src/test source files re-homed under the user's namespace path).
  `root/` carries the bulk-copied content (README.md, lefthook.yml,
  dev/{user,scratch}, resources/public/{index.html, css/app.css}).
- ✓ Updated `template-fn` to handle all three substrates + the
  `:include-story?` branch (see §2.4 below).
- ✓ The clj-new tree at `src/clj/new/re_frame2/` stays in parallel as
  a backstop until §2.5 (rf2-40vmd) lands.

### §2.3 — Port the test suite (rf2-c2770 — DONE 2026-05-20)

- ✓ Ported `test/clj/new/re_frame2_test.clj` →
  `test/day8/re_frame2_template/template_test.clj`. The new invocation
  drives `org.corfield.new/create` in-process (the full deps-new
  pipeline runs through `data-fn` / `template-fn` / `post-process-fn`
  exactly as a `clojure -Tnew create` shell-out would, without the
  per-substrate JVM start-up cost). deps-new lands on the classpath
  via the `:test` alias.
- ✓ Ported `template_emission_test.clj` and `emitted_test_run_test.clj`
  to `test/day8/re_frame2_template/` — both operate on the emitted
  tree and only the harness call switched from
  `(rft/re-frame2 …)` to `(deps-new/create {…})`.
- ✓ Ported `version_lockstep_test.clj` likewise; the lockstep
  guarantees are unchanged.
- ✓ Test-quiet reporter (`day8/re-frame2-test-quiet`) stays as the
  default test driver.

**Footgun surfaced in §2.3 port:**

- deps-new's substitution data exposes `:name` as the full
  group-qualified project name (e.g. `acme/my-app`), not the bare
  artifact id (`my-app`) the way clj-new's `project-data` did. The
  clj-new `stories.cljs` had `:{{name}}/canonical`, which became
  `:my-app/canonical` under clj-new but would render as
  `:acme/my-app/canonical` (an invalid keyword — two slashes) under
  deps-new. The port switches to `:{{main}}/canonical`
  (`:main` is the bare artifact id), which matches the original
  intent. No other clj-new substitution keys exhibited similar
  semantics drift.

### §2.4 — Add `:include-story?` end-to-end (rf2-c2770 — DONE 2026-05-20)

- ✓ Wired `template-fn` to emit `stories.cljs` (from `_shared/`) and
  swap the `_reagent/core.cljs` for `_reagent/core_with_stories.cljs`
  under `:include-story? true`.
- ✓ Reagent-only gate enforced in `data-fn` (throws with a clear
  message on `:uix` / `:helix` + `:include-story? true`). UIx + Helix
  variants follow once Story's adapter coverage matches Reagent's —
  same as today's clj-new template.
- ✓ Story-flag also swaps the `_reagent/deps.edn` for
  `_reagent/deps_with_story.edn` (adds the
  `day8/re-frame2-story {:mvn/version …}` coord) and the
  `_reagent/package.json` for `_reagent/package_with_story.json`
  (adds the `story` npm script + the `qrcode-generator` npm dep). This
  separate-source approach replaces clj-new's Mustache-style
  `{{#include-story?}}…{{/include-story?}}` blocks — deps-new uses
  flat `{{key}}` substitution (see `org.corfield.new.impl/->subst-map`
  + `substitute`) and has no conditional syntax. The output filename
  is the same (`deps.edn` / `package.json`) regardless of which
  source ran.
- ✓ Behavioural test: `include-story-true-reagent-test` ported from
  the clj-new test suite; the with-stories emission test in
  `template_emission_test.clj` is also ported and green.

### §2.5 — Drop clj-new (rf2-40vmd — DONE 2026-05-20)

- ✓ Removed `tools/template/src/clj/new/` resource tree (the Mustache
  resources) and the `re-frame2.clj` entry fn.
- ✓ Removed `tools/template/test/clj/new/` test suite — coverage
  carried over to `tools/template/test/day8/re_frame2_template/` in
  §2.3 (rf2-c2770).
- ✓ Removed `:clein` and `:clein/build` aliases from
  `tools/template/deps.edn` together with their Clojars-publish
  rationale comments.
- ✓ Removed `com.github.seancorfield/clj-new` dep from
  `tools/template/deps.edn`.
- ✓ Bumped `tools/template/README.md` to the deps-new invocation
  surface (`clojure -Tnew create :template
  io.github.day8/re-frame2-template`) — replaces the clj-new
  `:project/new` examples.
- ✓ Renamed the in-repo coord from `day8/clj-template.re-frame2`
  to `day8/re-frame2-template` in `tools/deps.edn` (the `:test`
  alias's `:local/root "template"` entry) and in the bundle-isolation
  rationale comments. **Lockstep contract impact:** the template's
  `:test` alias no longer carries a `:clein/build` descriptor, so it
  no longer participates in `.github/scripts/verify-version-lockstep.sh`
  — the entry has been removed from the script's `TOOLS` set
  alongside its `TOOLS_PATHS` and `TOOLS_LOCAL_ROOTS` references.
- ✓ The repo no longer publishes to Clojars. The
  `day8/clj-template.re-frame2` Clojars artefact stays in place
  (older versions remain resolvable for legacy users) — we just stop
  pushing new versions.
- ⚠ Spec sweeps for `000-Vision.md`, `001-Substrate-Variants.md`,
  `002-Generated-Shape.md`, `API.md`, `DESIGN-RATIONALE.md`, and
  `Principles.md` (the steady-state docs that still narrate the
  clj-new + `:edn-args` invocation surface) are deferred to §3.2
  per the §3 decomposition above. §2.5 closes out the **code** drop;
  §3.2 closes out the **spec narrative** drop.

## §3 Stage 3 — git-coord release + tag pipeline

Goal: tag-based release replaces the Clojars publish pipeline.

### §3.1 — Tag-on-release CI workflow (rf2-h0w5y — DONE 2026-05-20)

- ✓ Added `.github/workflows/template-release.yml`:
  - Triggers on push of tags matching
    `template-v[0-9]+.[0-9]+.[0-9]+*` (e.g.
    `template-v0.0.1.alpha`). The `template-v…` prefix keeps the
    trigger disjoint from the framework `v…` and Causa
    `causa-v…` tag spaces.
  - Verifies the pushed tag against the template's co-located
    `VERSION` file (`tools/template/VERSION`). A mismatched tag
    fails the gate before any deploy step runs.
  - Runs the template's JVM test suite (`clojure -M:test` from
    `tools/template/`) as a pre-release gate — shape +
    static-parse + pin-lockstep + (CI-gated) behavioural slice.
  - Cuts a GitHub Release pointing at the tagged commit. No
    artefact upload — the tagged commit IS the artefact under
    git-coord distribution.
- ✓ Added `tools/template/VERSION` (initial value `0.0.1.alpha` —
  tracks the framework alpha-channel cadence while the template
  is in pre-alpha; bumps independently once the post-1.0 cadence
  decouples). The in-template lockstep guard
  ([`version_lockstep_test.clj`](../test/day8/re_frame2_template/version_lockstep_test.clj))
  continues to read the repo-root `VERSION` as the source of
  truth for `:rf2-version`; the template's own `VERSION` drives
  the release tag space.

### §3.2 — Document the invocation form (rf2-h0w5y — DONE 2026-05-20)

- ✓ `tools/template/README.md` — Quick start section reflects the
  steady-state `-Tnew create` invocation, the tag-pin example
  uses `#template-v0.0.1.alpha`, and a callout explains the
  release-pipeline shape + the local-development `:local/root`
  fallback.
- ✓ `tools/template/spec/API.md` — full rewrite of §Invocation
  + §Args reference + §Examples + §Errors against deps-new
  semantics. `:edn-args` pass-through bag removed; substrate +
  include-story? are top-level k/v.
- ✓ `tools/template/spec/001-Substrate-Variants.md` —
  `:edn-args` plumbing language removed. Invocation examples,
  coercion section, future-variants checklist all reflect the
  deps-new shape.
- ✓ `tools/template/spec/000-Vision.md` — invocation form +
  lineage paragraph + non-goals enumeration aligned with the
  three-flag lock; new §Distribution section calls out git-coord
  + the `template-v…` tag sequence.
- ✓ `tools/template/spec/Principles.md` §P4 — rewritten to
  "Substrate selection via top-level k/v". §P1 + §P2 + §P3 + §P7
  swept for `:edn-args` / `clj-new` / `shared/` residue and
  realigned with the deps-new shape.
- ✓ `tools/template/spec/DESIGN-RATIONALE.md` — §1 flipped to
  "deps-new over clj-new"; §2 (`:edn-args`-not-top-level)
  archived as a retired rationale under §Retired
  §`:edn-args`-not-top-level; the v1 clj-new-over-deps-new
  rationale archived under §Retired §clj-new-over-deps-new for
  historical record. §5 invocation example updated to top-level
  k/v.
- ✓ `tools/template/spec/002-Generated-Shape.md` — resource-tree
  diagram + substitution-variable table + clj-new-finds-resources
  section rewritten against the deps-new `root/` + `_shared/` +
  per-substrate layout.
- ✓ `tools/template/spec/README.md` — index entries reflect the
  new shape.

## §4 Stage 4 — Docs + migration for existing template users (rf2-7jgkv — IN-FLIGHT 2026-05-20)

Goal: existing clj-new template users have a clean upgrade path;
the template's permanent home moves to the dedicated
`github.com/day8/re-frame2-template` repo.

Status: docs sweep landed at rf2-h0w5y §3.2 (#1724); migration note
+ repo-split scaffolding landed at rf2-7jgkv. The actual external
repo creation + initial push is a Mike-operator handoff (see
[005-Repo-Split.md](005-Repo-Split.md)).

### §4.1 — Skill / repo-root docs residue sweep (DONE 2026-05-20)

The rf2-h0w5y §3.2 sweep covered the template's own spec/ tree +
`tools/template/README.md`. The residue swept under rf2-7jgkv:

- ✓ `tools/shadow-cljs.edn` — "JVM-only (clj-new template)" comment
  flipped to "(deps-new template)" + path under
  `resources/day8/re_frame2_template/`.
- ✓ `skills/README.md` — re-frame2-setup index entry's template
  invocation example flipped to `clojure -Tnew create
  :template io.github.day8/re-frame2-template`.
- ✓ `skills/re-frame2-setup/README.md` — Relationship-to-the-
  generator-template section rewritten against the deps-new shape;
  external-repo home called out.
- ✓ Repo-root `README.md` — Project Layout entry annotated with
  the eventual external repo home + cross-ref to 005-Repo-Split.md.
- ✓ `tools/template/README.md` — Spec table entry corrected
  ("deps-new vs clj-new vs CLI" → "deps-new + git-coord over
  clj-new + Clojars"); added entries for the new 004 + 005 specs;
  repo-home callout block added.
- ✓ `tools/template/spec/README.md` — index entries added for
  004 + 005.

### §4.2 — Migration note for existing users (DONE 2026-05-20)

- ✓ Added [`migration/from-clj-new-template/README.md`](../../../migration/from-clj-new-template/README.md).
  One-liner change for the user:
  ```
  # OLD
  clojure -X:project/new :template re-frame2 :name acme/my-app

  # NEW
  clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app
  ```
- ✓ Surfaces the locked-three-flags rule + the v1 flag set
  (`:include-story?`, `:css`, `:include-ssr?`).
- ✓ Lists the per-flag deltas (each `:edn-args '[...]'` flag now
  rides as a top-level k/v pair).
- ✓ Added the migration note to `mkdocs.yml`'s Migration nav.

### §4.3 — Repo split (SCAFFOLDING DONE 2026-05-20; operator-handoff pending)

- ✓ [`tools/template/spec/005-Repo-Split.md`](005-Repo-Split.md)
  documents the full migration procedure (what to copy out, how to
  retarget the deps-new coord, the cutover sequence). Numbered 005
  because 004 is taken by the SSR Validation Report (rf2-0m5ea).
- ✓ `tools/template/README.md` flags the future external location
  prominently; cross-refs to 005-Repo-Split.md.
- ⚠ **Operator action required** (Mike-side):
  - Create `github.com/day8/re-frame2-template` repo.
  - Run `git subtree split` from this monorepo to seed the
    external repo's history.
  - Push initial commits + first release tag.
  - Move `.github/workflows/template-release.yml` to the
    external repo (the workflow lives in the external repo
    post-split; it's the template's own CI surface).
  - Update the in-monorepo `tools/template/` to a "see external
    repo" stub (or delete; the deps-new coord
    `io.github.day8/re-frame2-template` resolves against the
    external repo from that point on).
  - File a follow-on bead to retire the in-monorepo
    `tools/template/` resource tree once the external repo's CI
    is green and at least one release tag exists.

Once §4.3's operator handoff completes, the EPIC bead rf2-dolpf can
be closed.

## §5 Cross-references to locked v1 emit spec

The locks below are inherited from the template walkthrough (Mike,
2026-05-12). The deps-new rebuild preserves them unchanged:

| Lock | Source | Implementation |
|---|---|---|
| Substrates: Reagent / UIx / Helix | Q2 lock | `template-fn` switch on `:substrate` |
| Sample: counter | Q4 lock | Carries over from current resource tree |
| Test harness: `:node-test` + `:browser-test` | Q5 lock | shadow-cljs.edn `:builds` ports as-is |
| CSS default: plain CSS | Q6 lock | `_shared/resources/public/css/app.css` |
| CSS opt-in: `:css :tailwind` (Tailwind v4) | Q6 lock; depends on rf2-gthro | `template-fn` branch on `:css` |
| Build targets: browser + `:test` | Q7 lock | shadow-cljs.edn |
| SSR opt-in: `:include-ssr?` | Q7 lock; gated on rf2-0m5ea | `template-fn` branch on `:include-ssr?` |
| Story opt-in: `:include-story?` | Q3/Q4 era | `template-fn` branch on `:include-story?` |
| Causa preload (always-on) | Q9 lock | `_shared/shadow-cljs.edn` :preloads |
| Skill install stub: `install-skills.sh` | Q9 lean A | Deferred — README mention only today (`_shared/README.md` §Future: skill install); placeholder script lands when the Claude Code skill marketplace publishes. |
| Layout: `src/` + `test/` + `dev/scratch.cljs` + `dev/user.clj` | Q8 lock | Unconditional under `_shared/` |

Three flags total (`:include-story?`, `:css`, `:include-ssr?`). Resist
further proliferation — every new flag requires explicit
DESIGN-RATIONALE justification.

## §6 Cross-references

- `tools/template/spec/000-Vision.md` — gets non-goals update in
  §3.2 (locked-three-flags rule replaces "no branching toggles").
- `tools/template/spec/001-Substrate-Variants.md` — invocation
  language update in §3.2.
- `tools/template/spec/DESIGN-RATIONALE.md` — clj-new-vs-deps-new
  decision flip in §3.2; `:edn-args` rationale archived.
- `ai/findings/2026-05-20-rf2-dolpf-template-deps-new-audit.md`
  (local-only) — the audit lineage this plan is derived from.
- [seancorfield/deps-new docs](https://github.com/seancorfield/deps-new)
  — upstream reference; the migration must remain compatible with
  the deps-new authoring conventions documented there.
- rf2-dolpf — the EPIC umbrella bead. Stays open until §4.2 lands.
- rf2-gthro — Tailwind major-version verification (gates `:css :tailwind`
  flag work).
- rf2-0m5ea — SSR validation (gates `:include-ssr?` flag work).
- rf2-y9zqc — Causa preload Clojars artefact (gates the
  always-on preload).
