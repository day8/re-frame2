# Template — Principles

> The design principles `tools/template/` commits against. Short
> companion doc; the WHY for the major decisions lives in
> [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md).

## P1 — Build-time only

The template is **never on a consumer's runtime classpath**. It is a
build-time scaffold; consumers invoke it via
`clojure -Tnew create :template io.github.day8/re-frame2-template`
and what lands in their project is the generated source tree, not
this artefact.

This is the strongest version of the bundle-isolation contract
[`tools/README.md`](../../README.md) lays out. Every other tool in
`tools/` has to argue why it satisfies the contract (separate
classpath root, deps.edn hygiene, DCE-friendly macros). The
template satisfies it trivially: it never appears anywhere except
the user's `clojure -Tnew create` invocation, and only then on
the CLJ-side build tool's classpath (deps-new clones the tagged
commit into `~/.gitlibs/` and reads it once), never the generated
app's.

The dependency flow is unidirectional:

```
user → deps-new → this template → generated files
```

The generated app has **no compile-time or runtime knowledge of
the template**. The generated `deps.edn` does not depend on
`day8/re-frame2-template`; the generated source does not
`:require` from it. Once the template emits its files, the
template's role is over.

## P2 — Counter as the canonical example

Every variant emits a working counter. The counter:

- Is the same shape developers read about in [Guide chapter 03
  — Your first app](../../../docs/guide/03-first-app.md).
- Matches the per-substrate `examples/<substrate>/counter*/`
  reference apps.
- Uses the smallest amount of re-frame2 surface that demonstrates
  the full cycle: an init event, an action event, a sub, a view
  that dispatches.

This is the "counter throughline" principle applied to the
scaffolding tool. A developer who runs
`clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app`
and then opens the guide sees the same code in both places. No
mismatch, no "wait, what's this different shape doing here?"
moment.

Branching out from counter (a TodoMVC variant, a routing variant,
a forms variant) is a future template-mode question. v1 of the
template keeps the example minimal.

## P3 — Substrate-agnostic shell, substrate-specific surface

Events (`events.cljs`), subs (`subs.cljs`), and the host shell
(`README.md`, `.gitignore`, `resources/public/index.html`) are
**identical across all three substrates**. They live under
`_shared/` in the resource tree (plus `root/` for the bulk-copied
content with default placement).

The substrate-specific files are:

- `core.cljs` — substrate-specific render.
- `views.cljs` — substrate-specific component syntax.
- `deps.edn` — substrate-specific adapter coord.
- `shadow-cljs.edn` and `package.json` — substrate-specific npm
  pins.

This split is enforced at the resource-tree level (see
[002-Generated-Shape.md §Resource tree](002-Generated-Shape.md#resource-tree-template-side)).
Adding a new substrate means dropping a new sub-tree; the shared
shell does not need to be touched.

The split also surfaces a re-frame2 design assertion: business
logic (events + subs + state shape) is substrate-portable. Only
the render edge differs. The template's resource layout demonstrates
this.

## P4 — Substrate selection via top-level k/v

The substrate selector is a **top-level k/v argument** on the
`-Tnew create` invocation:

```bash
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix
```

deps-new hands template arguments directly to the template's
`data-fn` as a Clojure map; there is no pass-through bag, no
nested EDN payload. `data-fn` in
[`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)
reads `:substrate` off the map and threads the coerced keyword
through to `template-fn`'s per-substrate `case`.

The v1 clj-new template plumbed `:substrate` through `:edn-args`
to work around clj-new's top-level-arg stripping; the deps-new
migration (rf2-dolpf) removed the workaround. See
[DESIGN-RATIONALE.md §Retired §`:edn-args`-not-top-level](DESIGN-RATIONALE.md#retired--edn-args-not-top-level)
for the historical record.

## P5 — Pins in lockstep with the reference implementation

`:rf2-version`, `:shadow-version`, and `:react-version` are defined
in one place — the entry ns — and bumped in lockstep with
`implementation/package.json` and the re-frame2 alpha release
cadence.

The point: what the template emits should match what the reference
implementation's own CI is exercising. A developer who runs the
template and then files an issue should be running the same combo
the maintainers smoke-test. Drift here is a bug.

## P6 — Forgiving on input shape, strict on substrate set

The template accepts the substrate arg as a keyword, a string
(with or without leading `:`), or a symbol — clj-new harnesses
across the wider tooling ecosystem hand keywords through
inconsistently, and the template tolerates the variants.

But the substrate **value** is strict: anything not in
`#{:reagent :uix :helix}` throws with a clear message naming the
valid set. No silent fallback to default, no "did you mean...?"
fuzz. The user sees the typo and fixes it.

## P7 — Tested end-to-end, per substrate

The template ships a layered JVM test suite under
`test/day8/re_frame2_template/`. Each substrate (Reagent / UIx /
Helix) is exercised end-to-end across the layers:

1. **Shape.** `template_test.clj` — generates a tmp app via
   `org.corfield.new/create` in-process (the full deps-new pipeline
   — `data-fn` / `template-fn` / `post-process-fn` — runs exactly
   as a `clojure -Tnew create` shell invocation would), walks the
   produced file tree, asserts file presence + `deps.edn`
   substrate-adapter coords. Always runs.
2. **Static parse.** `template_emission_test.clj` — parses every
   emitted `.cljs` file, resolves each `<alias>/<sym>` reference
   against the framework source under `implementation/core/src/`,
   and asserts the symbol exists. Catches rename/cut drift between
   the template scaffold and the runtime API. Always runs.
3. **Pin lockstep.** `version_lockstep_test.clj` — emits a Reagent
   app and asserts the template's `:rf2-version` matches the
   repo-root `VERSION`, the `:shadow-version` matches
   `implementation/package.json` :devDependencies/shadow-cljs, and
   the `:react-version` matches the same file's
   :devDependencies/react. Catches drift between the template's
   inline pin literals and their external sources of truth. Always
   runs.
4. **Behavioural.** `emitted_test_run_test.clj` — compiles and runs
   the emitted `events_test.cljs` end-to-end via shadow-cljs + Node.
   Gated behind `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` (CI sets it; off
   locally for fast loop).

A `clojure -P` deps-parse smoke also runs in `template_test.clj`,
gated behind `RF2_TEMPLATE_DEPS_RESOLVE=1` (CI sets it). It is
default-off locally until the alpha artefacts land on Clojars —
until publication every `clojure -P` fails with a "couldn't find
artifact" error that is a known skip, not a real signal.

CI sets both env vars; a change that breaks file-tree shape, drifts
the framework surface, breaks the pin lockstep, or breaks the
emitted test compile fails the build. The git-coord release
workflow (`.github/workflows/template-release.yml`) runs the same
`clojure -M:test` suite as a pre-release gate, so a `template-v…`
tag push that would publish a drifted template fails before the
GitHub Release is cut.

## Cross-references

- [`tools/README.md`](../../README.md) — the bundle-isolation
  contract this template satisfies trivially.
- [000-Vision.md](000-Vision.md) — non-goals.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY decisions.
