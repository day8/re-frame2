# Template — Repo Split (Monorepo → External Repo)

> **Normative procedure for moving `tools/template/` out of the
> re-frame2 monorepo to its permanent home at
> `github.com/day8/re-frame2-template`** (rf2-dolpf §4 / rf2-7jgkv).
>
> Scope: this doc owns the migration sequence + the deps-new coord
> retarget. The steady-state spec of the template lives in 000 / 001
> / 002 / API / Principles / DESIGN-RATIONALE; this doc retires once
> the external repo is live and the in-monorepo `tools/template/` is
> stubbed or deleted.

**Sequencing prerequisite.** The Stage-6 W8 prerequisite — collapse the
substrate variants onto the compiled-view scaffold (synthesis
`11-adoption-workstreams.md` W8) — is void: that variant was retired
(rf2-qmvep, 2026-07-25) rather than adopted, so nothing gates this split
on it. The split itself runs in Stage 7 after every alpha gate is green
(synthesis `08-delivery.md` §2, Stage 7).

## §1 Why split

The template's published invocation surface is `clojure -Tnew create
:template io.github.day8/re-frame2-template`. The `io.github.*`
prefix triggers deps-new's auto-git-clone path
(`tools.deps.extensions.git`'s `auto-git-url`) — deps-new clones the
named GitHub repo and resolves the template body inside the cloned
tree. For that to work end-to-end, **the template body must live at
the root of a standalone GitHub repo**, not nested under
`tools/template/` in a monorepo. A monorepo-nested template can be
exercised via `:local/root` for development, but cannot be the
published production-invocation target — deps-new clones the named
repo, not a subdirectory of it.

Secondary benefits:

- **Release cadence independence.** Template releases tag on the
  template repo, not the monorepo. The two trees ship at their own
  pace. The `tools/template/VERSION` file (added at rf2-h0w5y §3.1)
  is already independent of the framework-wide repo-root `VERSION`,
  in anticipation.
- **Smaller clone footprint** for `clojure -Tnew` consumers — they
  get the template's own commits, not the full re-frame2 monorepo
  (which is heavy: 22 K-line spec, full implementation tree, multiple
  tool surfaces, etc.).
- **Cleaner CI surface.** The template repo owns its
  `template-release.yml` workflow + tag-on-release pipeline
  (rf2-h0w5y §3.1); the monorepo's CI doesn't need to know about
  template publishes post-split.

## §2 What lives where after the split

The split leaves **two roots**, and every path the test suite touches
belongs to exactly one of them:

- **template root** — the external `day8/re-frame2-template` checkout.
  Owns the template body, this spec tree, the template's own `VERSION`,
  the tests, their Chromium driver, and (post-split) the release
  workflow.
- **framework root** — a checkout of the `day8/re-frame2` monorepo,
  consumed as a *test fixture*. Owns the framework sources, the pin
  files, `implementation/node_modules`, the framework-wide `VERSION`,
  and the setup-skill reference leaves.

In the monorepo these two coincide, which is why the current harness
has only one. §3.2.1 names them apart and assigns every consumer.

```
github.com/day8/re-frame2-template/        ; external repo (NEW) — the TEMPLATE ROOT
├── README.md                              ; invocation + quick start (from tools/template/README.md)
├── LICENSE
├── deps.edn                               ; the template's own deps + :test alias
├── VERSION                                ; the TEMPLATE's version — NOT the framework's; see §3.2.1
├── src/day8/re_frame2_template/
│   └── hooks.clj                          ; :data-fn / :template-fn / :post-process-fn
├── resources/io/github/day8/re_frame2_template/  ; RETARGETED from day8/… — see §2.1
│   ├── template.edn                       ; the deps-new template config (the only template.edn in the tree)
│   ├── root/                              ; bulk-copied: README.md + resources/public/ (index.html, css/app.css)
│   ├── _shared/                           ; substrate-agnostic: gitignore, events.cljs, events_test.cljs, subs.cljs, shadow-cljs.edn, package.json
│   ├── _reagent/                          ; Reagent-specific (core.cljs / views.cljs / deps.edn)
│   └── _uix/                              ; UIx-specific (core.cljs / views.cljs / deps.edn)
├── spec/                                  ; 000-Vision, 001-Substrate-Variants, 002-Generated-Shape,
│                                          ;   005-Repo-Split (this doc), API, DESIGN-RATIONALE, Principles, README
├── test/day8/re_frame2_template/          ; framework-fixture-coupled — see §3.2.1
│   ├── test_support.clj                   ; shared harness; owns the root resolution §3.2.1 splits in two
│   ├── template_test.clj                  ; emitted-tree contract (reads the emitted project only)
│   ├── template_emission_test.clj         ; emitted re-frame symbols audited against framework source
│   ├── emitted_test_run_test.clj          ; behavioural compile/run/release tier + setup-leaf parity
│   ├── version_lockstep_test.clj          ; pin-lockstep guard (all coords ride one version)
│   ├── release_gate_test.clj              ; workflow-sanity coverage for template-release.yml
│   └── repo_split_spec_test.clj           ; executable checks for the §2.1.x deps-new grammar
├── test-support/
│   └── page-boot-proof.cjs                ; Chromium page-boot driver, dev + release (emitted-app tier)
└── .github/workflows/
    └── template-release.yml               ; tag-on-release CI (moved from the monorepo — see §3.2)

<framework root>/                          ; a day8/re-frame2 checkout, consumed as a TEST FIXTURE
├── VERSION                                ; the FRAMEWORK version — the :rf2-version lockstep target
├── skills/re-frame2-setup/references/     ; first-counter.md — the twelve-file leaf parity fixture
└── implementation/
    ├── package.json                       ; react / react-dom / shadow-cljs pins
    ├── node_modules/                      ; populated by `npm ci`; junctioned into every emitted project
    ├── core/                              ; deps.edn (clojure/clojurescript pins) + src/re_frame (surface audit)
    └── adapters/reagent/, adapters/uix/   ; deps.edn (substrate pins) + src (surface audit)

github.com/day8/re-frame2/                 ; monorepo (current)
└── tools/template/                        ; STUB or DELETED after the split (see §6)
```

**There is no repo-root `template.edn`.** Earlier drafts of this tree
carried one, annotated "deps-new declarative config (placeholder)". No
such file has ever existed under `tools/template/`, no step in this
runbook created or consumed it, and deps-new never looks for one there:
`find-root` resolves `template.edn` on the *classpath*, under the
template-body path (§2.1). It is removed from the plan rather than
created.

The external repo's layout drops the `tools/template/` prefix — the
template lives at the repo root. But the template-body resource path
is **not** identical to the in-monorepo path: it must be retargeted
from `resources/day8/re_frame2_template/` to
`resources/io/github/day8/re_frame2_template/`. See §2.1.

### §2.1 Template-body resource retarget (REQUIRED for the published coord)

deps-new's resolver (`org.corfield.new.impl/find-root`) derives the
template-body path from the **full** `:template` symbol — dots to
slashes, hyphens to underscores — and looks for `<path>/template.edn`
on the cloned repo's classpath:

| `:template` symbol | Derived `template.edn` path |
|---|---|
| `day8/re-frame2-template` | `day8/re_frame2_template/template.edn` |
| `io.github.day8/re-frame2-template` | `io/github/day8/re_frame2_template/template.edn` |

The published production invocation is
`io.github.day8/re-frame2-template` (the `io.github.*` prefix is what
triggers deps-new's auto-git-clone — see §1). So the cloned external
repo **must** ship the template body at
`resources/io/github/day8/re_frame2_template/`, not the in-monorepo
`resources/day8/re_frame2_template/`. (The in-monorepo path matches
the local-dev coord `day8/re-frame2-template`, whose `day8` qualifier
deliberately bypasses auto-clone for `:local/root` smoke — see §4.)

Concrete retarget step, run after §3.1's `git subtree split`:

```bash
# In the external repo (post-subtree-split, at the repo root)
mkdir -p resources/io/github/day8
git mv resources/day8/re_frame2_template resources/io/github/day8/re_frame2_template
git commit -m "chore: retarget template body to io/github/day8 for published coord"
```

The hooks ns (`src/day8/re_frame2_template/hooks.clj`) and its
`day8.re-frame2-template.hooks` symbol stay put — deps-new resolves
the hook fns by the `:data-fn` / `:template-fn` / `:post-process-fn`
ns-qualified symbols inside `template.edn` (independent of the
template-body path), so only the `resources/` body moves.

#### §2.1.1 The `:template` override grammar (two fields, one separator)

deps-new parses `:template` as `repo[%deps-root]%template-sym[#tag]`
(`org.corfield.new.impl/preprocess-options`). The separator count — not
the field content — decides what each field means:

| Spelling | `repo` | `deps-root` | template resolved by `find-root` |
|---|---|---|---|
| `template-sym` | — | — | `template-sym` |
| `repo%template-sym` | `repo` | — | `template-sym` |
| `repo%deps-root%template-sym` | `repo` | `deps-root` | `template-sym` |

The form this doc mandates is the **two-component `repo%template-sym`**:
one separator, the single field after it being the template symbol. The
three-component `repo%deps-root%template-sym` is a *different* grammar
whose middle field is a classpath-relative deps-root inside the resolved
repo. This template needs no deps-root, so a second separator is always
wrong — and wrong *quietly*: deps-new would reinterpret the intended
template-sym as a deps-root and still parse `:template` to the same
symbol, so only an exact-token check catches it (see §2.1.2).

**Single canonical path, no second copy.** Pre-alpha posture: the
external repo ships the `io/github/day8/…` body *only*. The local-dev
`:local/root` smoke (§4) resolves that same single body **without**
auto-clone by using deps-new's `repo%template-sym` override form
(`day8/re-frame2-template%io.github.day8/re-frame2-template`): the
`day8/re-frame2-template` repo part bypasses `auto-git-url` (no
`io.github.*` prefix → no clone, the `:local/root` override stands),
while the `io.github.day8/re-frame2-template` template-sym part drives
`find-root` to the canonical `io/github/day8/…` body. There is no
second on-disk copy and no compatibility alias to keep in sync. The
test harness (`run-template!`) updates in lockstep with this retarget
(see §3.4.1).

#### §2.1.2 One exact token, guarded

Every `%`-bearing spelling of the override in this document must be the
complete, exact token

```
day8/re-frame2-template%io.github.day8/re-frame2-template
```

— repository half included, one separator, no fragments. `repo-split_spec_test.clj`
extracts each such spelling **whole** and asserts it equals that token
verbatim; it never supplies the repository half itself.

The rule is deliberately about the *token*, not about what deps-new makes
of it, because deps-new is a **weak oracle** here — asking "does deps-new
resolve the right template?" misses two of the three defect classes:

- **A wrong repository half** parses to the *exact* canonical `:template`
  symbol. Nothing in the parse betrays it — but at run time the wrong
  half sends deps-new through `auto-git-url` and clones a repo instead of
  honouring `:local/root`.
- **A tripled separator** also parses to the *exact* canonical
  `:template` symbol, silently promoting the intended template-sym to a
  deps-root and leaving `deps-root` set to a stray `%` (§2.1.1).
- **A doubled separator** is the only class deps-new itself rejects; it
  mangles the parsed symbol.

Because two classes are invisible to a parse-only assertion, the guard
compares the extracted token against the canonical spelling verbatim.
The negative cases live as explicit mutations in the test, not in this
document — every `%`-bearing spelling *here* is the real thing.

## §3 Migration sequence (operator-side)

This is the **operator-handoff sequence**. The scaffolding (this
doc, the migration note, the cross-ref sweep) landed under
rf2-7jgkv; the steps below are Mike-side actions that complete the
split.

### §3.1 Seed the external repo

Use `git subtree split` to extract `tools/template/`'s history from
the monorepo into a new branch:

```bash
# In the re-frame2 monorepo
cd /path/to/re-frame2
git subtree split --prefix=tools/template -b template-split-export
```

This produces a `template-split-export` branch whose history is the
subset of monorepo commits that touched `tools/template/`, rewritten
to look like a standalone repo (`tools/template/` becomes the new
repo root).

Then create the external repo and push:

```bash
# Create the new repo on github.com/day8/re-frame2-template (empty)
# via the GitHub web UI or gh repo create.

# Push the extracted history
git remote add template-repo git@github.com:day8/re-frame2-template.git
git push template-repo template-split-export:main
```

### §3.2 Move the release workflow

The template-release workflow (`.github/workflows/template-release.yml`,
landed at rf2-h0w5y §3.1) currently lives in the re-frame2 monorepo.
Move it to the external repo:

```bash
# In the external repo
cp /path/to/re-frame2/.github/workflows/template-release.yml \
   .github/workflows/template-release.yml
git add .github/workflows/template-release.yml
git commit -m "ci: tag-on-release workflow (moved from monorepo)"
git push template-repo main
```

The workflow's checkout step + path references (`tools/template/...`)
need adjusting against the external repo's root layout (the template
now lives at the repo root, not under `tools/template/`). Concrete
edits:

- Drop the `tools/template/` prefix from path references:
  `tools/template/VERSION` → `VERSION`,
  `tools/template/test/` → `test/`, etc.
- The `clojure -M:test` invocation runs from the repo root.
- The `paths:` filter on the `push:` trigger drops the
  `tools/template/**` prefix (every change in the external repo
  touches the template).
- **The provisioning steps move to the framework root, not the template
  root.** `release_gate_test.clj` asserts today that the workflow runs
  `npm ci` in `implementation/` and `clojure -M:test` from
  `tools/template`. Post-split those become
  `$RF2_FRAMEWORK_ROOT/implementation` and the template repo root
  (§3.2.1). The workflow edit and the assertion edit are one change —
  `release_gate_test.clj` reads the workflow, so moving one without the
  other reds the suite.
- **The pre-split Release-body caveat retires with the split.**
  `release_gate_test.clj`'s `release-body-carries-pre-split-caveat-test`
  requires the cut Release body to state it is *not a usable public
  scaffold*, to name the `:local/root` rewrite, and to cite rf2-8n4s71 —
  because pre-split the emitted framework coords are unpublished and the
  `io.github.day8/re-frame2-template` git-coord does not resolve.
  Completing this split is exactly what makes that coord resolve, so the
  first external release drops the caveat and the assertion demanding it
  together. Dropping either alone reds the suite; dropping the assertion
  *before* the coords actually resolve reinstates the false-green it
  guards.

### §3.2.1 Post-split test architecture: two roots, named apart

Dropping path prefixes is **not** sufficient. The template's JVM suite
resolves every path it touches from a single `repo-root`, and that one
root is doing two incompatible jobs.

`test_support.clj`'s `repo-root` walks up from `user.dir` until it finds
a directory with an `implementation/core/src/re_frame` child — a
**framework** marker. Four consumers then read **template**-owned paths
off that same root:

- `template-resource-dir` returns
  `<repo-root>/tools/template/resources` and hands it to deps-new as
  `:src-dirs`.
- `repo_split_spec_test.clj` slurps
  `<repo-root>/tools/template/spec/005-Repo-Split.md` — this document.
- `release_gate_test.clj` slurps
  `<repo-root>/.github/workflows/template-release.yml`.
- `emitted_test_run_test.clj` launches
  `<repo-root>/tools/template/test-support/page-boot-proof.cjs`.

In the monorepo the framework marker and the template body sit under one
root, so the conflation is invisible. **That is exactly what makes the
obvious fix dangerous.** Renaming `repo-root` to `RF2_FRAMEWORK_ROOT` —
the shape earlier drafts of this section prescribed — points those four
consumers at the *monorepo* copy of the template's resources, spec,
release workflow and browser driver: precisely the copy §3.5 stubs or
deletes. The external suite would then certify a tree that is not the one
being released, and would go on passing until the monorepo copy
disappeared, at which point it would fail for the wrong reason.

So the harness grows **two** roots, and no consumer may read from the
wrong one:

- **`template-root`** — the checkout under test. Resolved by walking up
  from `user.dir` to the directory holding
  `resources/io/github/day8/re_frame2_template/template.edn` (the
  post-retarget body, §2.1). Deliberately *not* an env var: the suite
  always tests the checkout it ships in, which is what makes reading the
  monorepo copy impossible rather than merely discouraged.
- **`framework-root`** — `RF2_FRAMEWORK_ROOT`, pointing at a checkout of
  `day8/re-frame2`. An env var, because it is genuinely external and can
  legitimately skew in version from the template.

Every current path consumer, and the root it must read from:

| Consumer | Path | Root |
|---|---|---|
| `test_support` / `template-resource-dir` | `resources/` (deps-new `:src-dirs`) | template |
| `repo_split_spec_test` | `spec/005-Repo-Split.md` | template |
| `release_gate_test` | `.github/workflows/template-release.yml` | template |
| `emitted_test_run_test` (boot proof) | `test-support/page-boot-proof.cjs` | template |
| `version_lockstep_test` (`:rf2-version`) | `VERSION` | framework |
| `version_lockstep_test` (react / shadow pins) | `implementation/package.json` | framework |
| `version_lockstep_test` (clojure + substrate pins) | `implementation/core/deps.edn`, `implementation/adapters/reagent/deps.edn`, `implementation/adapters/uix/deps.edn` | framework |
| `template_emission_test` (surface audit) | `implementation/core/src/re_frame`, `implementation/adapters/` + substrate | framework |
| `emitted_test_run_test` (deps rewrite) | `implementation/core`, `implementation/adapters/` + substrate | framework |
| `emitted_test_run_test` (React resolution) | `implementation/node_modules` | framework |
| `emitted_test_run_test` (setup-leaf parity) | `skills/re-frame2-setup/references/first-counter.md` | framework |
| `template_test` | the emitted project in a temp dir | neither |

`template_test.clj` is the one file with no root dependency at all — it
reads only the tree deps-new has just emitted — so it needs no migration.

**The `VERSION` trap.** Both roots hold a file called `VERSION`, the two
files currently hold the *same string* (`0.0.1.alpha`), and they mean
different things. `version_lockstep_test.clj` reads `<repo-root>/VERSION`
for the `:rf2-version` lockstep, and that is the **framework** version
the emitted app pins — not the template's release version. Post-split
`<template-root>/VERSION` is the template's own version, the one §5
matches against the `template-v…` tag. Route `:rf2-version` at
`<framework-root>/VERSION` explicitly. Because the two files agree today,
mis-routing it yields a green suite asserting that the template's
`:rf2-version` literal matches the template's own release version — a
tautology that stays green right up until the two versions first diverge,
which is the only moment the guard exists for.

**When `RF2_FRAMEWORK_ROOT` is unset**, fail fast under CI and skip with
a loud message in the fast local loop — the idiom
`RF2_TEMPLATE_RUN_EMITTED_TESTS` already establishes. A silent skip is
not acceptable for any tier in the table above: they are the pin, surface
and behavioural coverage the release gate rests on.

**Retired coupling, removed from this plan.** Earlier drafts of this
section listed dependencies the suite no longer has: that
`template_emission_test.clj` resolves emitted symbols against
`tools/story/src/`, and that `emitted_test_run_test.clj` rewrites the
generated `deps.edn` to `:local/root` paths under `tools/xray` and
`tools/story`. Neither holds today. The emission audit resolves against
`implementation/core/src/re_frame` and the per-substrate adapter sources
only; the deps rewrite touches exactly two coordinates, `day8/re-frame2`
and the substrate adapter. Story, Xray and the schema coordinates survive
in `template_test.clj` only as **retired-coord guards** — assertions that
the emitted app does *not* carry them — so they are contract to keep, not
a dependency to migrate. Likewise the `root/` bulk-copy holds `README.md`
and `resources/public/` only: the Lefthook and `dev/` entries earlier
drafts inventoried are not in the tree, and no SSR artefact is emitted.

**Fallback shape, for the pin tiers only.** If the second checkout proves
unworkable, the pin tests can consume the framework through its published
git-coords, with the live `implementation/package.json` and `deps.edn`
reads replaced by frozen fixture data committed under the template repo's
`test/resources/`. That drops `framework-root` for those tests, but
trades live lockstep for a fixture needing a refresh on every
framework-pin bump (the guard weakens to "template pins == fixture pins
== last-known framework pins"). It cannot serve `emitted_test_run_test`
at all: that tier needs a real `implementation/node_modules` to junction
and real adapter sources to compile against. Taking it wholesale would
mean dropping the behavioural tier — the exact false-green rf2-jdj17 /
rf2-ek857f hardened against — so it is never an option for the release
gate.

Whichever shape wins for the pin tiers, the external-repo release
workflow (§5) MUST run the full set green from the standalone layout
before the first `template-v…` tag — a release that skips these tiers is
the exact false-green the in-monorepo gates (rf2-jdj17, rf2-ek857f) were
hardened against, reintroduced post-split.

### §3.3 Pin the initial release tag

The git-coord distribution (rf2-h0w5y §3.1) cuts a tag per release.
The first tag on the external repo establishes the initial release
that `clojure -Tnew create :template io.github.day8/re-frame2-template`
can resolve:

```bash
# In the external repo (or via gh release create)
cd /path/to/external-repo
git tag template-v0.0.1.alpha
git push template-repo template-v0.0.1.alpha
```

(The `template-v…` prefix matches the workflow's trigger filter; see
rf2-h0w5y §3.1.)

### §3.4 Verify the published invocation works

From any directory (not inside either repo):

```bash
cd $(mktemp -d)
clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app
cd my-app
cat deps.edn  # should reference re-frame2 alpha coords
```

If deps-new resolves the template via the git-coord and the
generated tree matches the in-monorepo template's emit set, the
external repo is live.

This smoke is the canonical check for the §2.1 retarget: it exercises
the **full published path** (`io.github.day8/…` → auto-clone →
`find-root` against `resources/io/github/day8/re_frame2_template/`).
If the body were left at the in-monorepo `resources/day8/…` path,
this command fails with `Unable to find template.edn for
io.github.day8/re-frame2-template` — so it doubles as the regression
guard for the retarget. The external-repo release workflow (§5) runs
the same published-coord scaffold as a pre-release gate so the
published path can never go green while broken.

#### §3.4.1 Update the local-dev test harness for the retarget

The in-repo test harness drives `org.corfield.new/create` in-process
(`tools/template/test/day8/re_frame2_template/test_support.clj`'s
`run-template!`). Pre-split it passes `:template 'day8/re-frame2-template`
with `:src-dirs [<resources>]`, which `find-root` resolves to
`<resources>/day8/re_frame2_template/template.edn`. After the §2.1
retarget the body lives at `io/github/day8/…`, so `run-template!`
updates to the override form:

```clojure
;; post-retarget run-template! opts
{:template 'day8/re-frame2-template%io.github.day8/re-frame2-template
 ...}
```

(`day8/re-frame2-template` repo part bypasses auto-clone; the
`io.github.day8/re-frame2-template` template-sym part drives
`find-root` to the single canonical body — see §2.1.) This keeps the
suite resolving one canonical path with no second on-disk copy. The
retarget commit (§2.1) and this harness edit land together.

### §3.5 Retire the in-monorepo `tools/template/`

Once §3.4 is green, the in-monorepo `tools/template/` becomes
redundant. Two options:

**Option A — Stub.** Replace `tools/template/` with a one-file
README pointing at the external repo. Cheap and discoverable for
anyone who lands at the old path. Keep enough of the original
README to redirect:

```markdown
# tools/template/ — moved

The re-frame2 template moved to its own repo:
**[github.com/day8/re-frame2-template](https://github.com/day8/re-frame2-template)**.

Invocation:

    clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app

See the external repo for the full spec, source, and CI.
```

**Option B — Delete.** Remove `tools/template/` entirely; rely on
the repo-root README's "Project layout" entry and this doc's history
to point readers at the external repo.

The bead reviewing this decision should weigh discoverability (A is
friendlier for anyone who clones the monorepo expecting the template
there) against repo hygiene (B is cleaner; the template's history
already migrated). File a follow-on bead for the stub-vs-delete
call.

### §3.6 Sweep remaining monorepo cross-refs

After §3.5, sweep the monorepo for any remaining `tools/template/`
references that survived the docs sweep and either:

- Point at the external repo (`https://github.com/day8/re-frame2-template`).
- Delete if the reference is now meaningless.

Both build-file edits this step used to list are ALREADY TAKEN, ahead
of the split and for unrelated reasons. `tools/deps.edn`'s `:local/root
"template"` entry is gone: it hung off the aggregate `:test` alias,
which PR #9093 (rf2-6r9j.139) retired as a second hand-written JVM-lane
inventory beside `scripts/test-jvm-tools.sh`. The `tools/shadow-cljs.edn`
"JVM-only" exclusion comment is gone with the whole file, deleted by the
same PR (rf2-6r9j.140) as an ungated mirror of two of Pair MCP's builds.
The template is already off the tools/ classpath, so this step is now the
docs sweep above plus the README entry below.

The repo-root README's Project Layout entry retires (replace the
`template/` row with a one-line "see github.com/day8/re-frame2-template"
note, or drop entirely).

## §4 Deps-new coord retarget

The deps-new coord used to invoke the template changes shape across
the split:

| Phase | Coord | Resolution |
|---|---|---|
| Pre-split (development) | `day8/re-frame2-template` | `:local/root "tools/template"` in the consuming `deps.edn`. Used by tests + manual smoke. Does NOT trigger deps-new's auto-git-clone. |
| Pre-split (via published-shape coord) | `io.github.day8/re-frame2-template` | deps-new clones the **re-frame2 monorepo** (because the `day8/re-frame2-template` GitHub repo doesn't exist yet), then fails to find `resources/day8/re_frame2_template/template.edn` at the cloned repo root (it's under `tools/template/` in the monorepo). Not a viable production path; only `:local/root` works pre-split. |
| Post-split (published) | `io.github.day8/re-frame2-template` | deps-new clones from `https://github.com/day8/re-frame2-template.git` via `auto-git-url`, then `find-root` resolves the template body inside the cloned tree at `resources/io/github/day8/re_frame2_template/` (the path `->file` derives from the full `io.github.day8/…` symbol — see §2.1). |
| Post-split (pinned to tag) | `io.github.day8/re-frame2-template#template-v<version>` | Same as above, but pinned to a specific git tag (matches the `template-v…` tag space from rf2-h0w5y §3.1). |

The local-dev fallback (`:local/root` against a checkout of the
external repo) continues to work post-split, but the coord changes
shape: it uses the two-component `repo%template-sym` override form
(§2.1.1) so it both bypasses auto-clone **and** resolves the canonical
`io/github/day8/…` body (see §2.1):

```bash
git clone https://github.com/day8/re-frame2-template.git
cd /path/to/consumer
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "/path/to/re-frame2-template"}}}' \
        -Tnew create \
        :template day8/re-frame2-template%io.github.day8/re-frame2-template \
        :name acme/my-app
```

(Note: the `day8/re-frame2-template` repo part — not
`io.github.day8/re-frame2-template` — bypasses auto-clone: the
`io.github.*` prefix would trigger a git-clone before classpath
lookup, losing the local override. The trailing
`io.github.day8/re-frame2-template` template-sym part drives
`find-root` to the single canonical `io/github/day8/…` body that the
published coord also uses, so there is no second on-disk copy.)

## §5 CI workflow (lives in the external repo post-split)

The `template-release.yml` workflow (from rf2-h0w5y §3.1) currently
lives in the re-frame2 monorepo at
`.github/workflows/template-release.yml`. Post-split it lives **in
the external repo**, not the monorepo.

Shape (unchanged across the split, modulo path adjustments per §3.2
and the test-architecture setup per §3.2.1):

- Trigger: push of a tag matching
  `template-v[0-9]+.[0-9]+.[0-9]+*`.
- Steps:
  1. Check out the template repo. This is `template-root` — the bytes
     being released. Every template-side consumer in §3.2.1 reads from
     here and from nowhere else.
  2. Check out `day8/re-frame2` as a sibling, `npm ci` in its
     `implementation/`, `npx playwright install --with-deps chromium`,
     and export `RF2_FRAMEWORK_ROOT` at it (§3.2.1) together with
     `RF2_TEMPLATE_RUN_EMITTED_TESTS=1`. Without the env var the
     behavioural tier short-circuits to skip-asserts and the release
     regresses to fast-loop-only — the false-green rf2-ek857f /
     rf2-jdj17 hardened against. Without the Chromium install the
     emitted dev-page boot proof cannot launch.
  3. `clojure -M:test` from the template repo root. The tiers this must
     carry, every one of them against the external checkout:
     - **pin lockstep** (`version_lockstep_test`) — the template's
       literals against the framework `VERSION`,
       `implementation/package.json`, and the core / adapter `deps.edn`
       pins;
     - **framework surface** (`template_emission_test`) — every emitted
       `re-frame.*` symbol resolved against framework source;
     - **emitted-tree contract** (`template_test`) — including the
       retired-coord guards;
     - **setup-leaf parity** (`emitted_test_run_test`) — the emitted
       Reagent tree against the twelve-file manifest in
       `skills/re-frame2-setup/references/first-counter.md`;
     - **behavioural compile / run + browser** (`emitted_test_run_test`)
       — compile the emitted app against `:local/root` framework paths,
       junction `implementation/node_modules`, and boot the page under
       Chromium via `test-support/page-boot-proof.cjs` twice per
       substrate: once over the dev bundle, once over the `:advanced`
       release;
     - **release-workflow sanity** (`release_gate_test`) — read against
       the template repo's own
       `.github/workflows/template-release.yml`;
     - **split-spec grammar** (`repo_split_spec_test`) — the §2.1.2
       override-token guard, read against this document in the template
       repo.
  4. Run the §3.4 published-coord scaffold smoke against the tagged
     commit (the §2.1 retarget guard).
  5. Read `<template-root>/VERSION` — the template's own version, not
     the framework's (§3.2.1).
  6. Verify the tag matches `template-v` + that VERSION.
  7. Cut a GitHub Release pointing at the tagged commit. The pre-split
     "not a usable public scaffold" caveat does not apply post-split and
     retires with it (§3.2).
- Secrets needed: `GITHUB_TOKEN` (default; for push-tag permission).
  **No Clojars credentials** — git-coord distribution has no
  artefact-upload step.

The monorepo's CI doesn't carry any template-publish hooks post-split;
the template owns its own release cadence end-to-end.

## §6 Decision: stub vs. delete the in-monorepo tools/template/

Open question for the operator handoff. See §3.5 above. File a
follow-on bead once the external repo is live; resolve the question
based on whether the monorepo discoverability gain (Option A) or
the repo-hygiene gain (Option B) wins.

Pre-alpha posture: hard rename, no transitional carry-over of the
template source. The template's history lives in the external repo
post-split; the monorepo carries a pointer at most.

## §7 Cross-references

- The deps-new rebuild plan (the former `003-DepsNew-Rebuild-Plan.md`,
  a completed migration record now kept in git history) §4 — the
  parent migration plan; this doc owns §4.3.
- [`tools/template/README.md`](../README.md) — the template's
  user-facing README, updated to reference the future external
  location.
- [`tools/template/spec/000-Vision.md`](000-Vision.md) — Lineage
  section enumerates the Clojars → git-coord shift.
- [`tools/template/spec/DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
  §1 — WHY deps-new + git-coord over clj-new + Clojars.
- [`migration/from-clj-new-template/README.md`](../../../migration/from-clj-new-template/README.md)
  — user-facing migration note for existing clj-new template users.
- rf2-dolpf — EPIC umbrella; closes once §4.3 operator handoff lands.
- rf2-7jgkv — this scaffolding + cross-ref sweep.
- rf2-h0w5y — git-coord release pipeline (the
  `template-release.yml` workflow, which moves into the external
  repo as part of the split).
- rf2-4bs7k — senior review that prescribed §2.1 (the published-coord
  resource retarget) and §3.2.1 (the post-split test architecture).
- rf2-ek857f / rf2-jdj17 — the in-monorepo false-green release gates;
  §3.2.1 + §5 carry their hardening forward so the post-split release
  gate cannot regress to a fast-loop-only test.
