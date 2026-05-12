# Template — Principles

> The design principles `tools/template/` commits against. Short
> companion doc; the WHY for the major decisions lives in
> [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md).

## P1 — Build-time only

The template is **never on a consumer's runtime classpath**. It is a
build-time scaffold; consumers invoke it via
`clojure -X:project/new` and what lands in their project is the
generated source tree, not this jar.

This is the strongest version of the bundle-isolation contract
[`tools/README.md`](../../README.md) lays out. Every other tool in
`tools/` has to argue why it satisfies the contract (separate
classpath root, deps.edn hygiene, DCE-friendly macros). The
template satisfies it trivially: it never appears anywhere except
the user's `clojure -X:project/new` invocation, and only then on
the CLJ-side build tool's classpath, never the generated app's.

The dependency flow is unidirectional:

```
user → clj-new → this template → generated files
```

The generated app has **no compile-time or runtime knowledge of
the template**. The generated `deps.edn` does not depend on
`day8/clj-template.re-frame2`; the generated source does not
`:require` from it. Once the template emits its files, the
template's role is over.

## P2 — Counter as the canonical example

Every variant emits a working counter. The counter:

- Is the same shape developers read about in [Guide chapter 03
  — Your first app](../../../docs/guide/03-your-first-app.md).
- Matches the per-substrate `examples/<substrate>/counter*/`
  reference apps.
- Uses the smallest amount of re-frame2 surface that demonstrates
  the full cycle: an init event, an action event, a sub, a view
  that dispatches.

This is rf2-2kzw's "counter throughline" principle applied to the
scaffolding tool. A developer who runs
`clojure -X:project/new :template re-frame2 :name acme/my-app` and
then opens the guide sees the same code in both places. No
mismatch, no "wait, what's this different shape doing here?"
moment.

Branching out from counter (a TodoMVC variant, a routing variant,
a forms variant) is a future template-mode question. v1 of the
template keeps the example minimal.

## P3 — Substrate-agnostic shell, substrate-specific surface

Events (`events.cljs`), subs (`subs.cljs`), and the host shell
(`README.md`, `.gitignore`, `resources/public/index.html`) are
**identical across all three substrates**. They live under
`shared/` in the resource tree.

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

## P4 — Substrate selection via `:edn-args`

The substrate selector rides on `:edn-args`, not on a top-level
`:substrate` key:

```bash
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :uix]'
```

This is a clj-new harness constraint surfaced during implementation
— clj-new's `create` strips unknown top-level args before
classloading the template's entry fn. `:edn-args` is the documented
pass-through bag, and it is what the template reads from.

See [DESIGN-RATIONALE](DESIGN-RATIONALE.md) §edn-args-not-top-level
for the audit trail.

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

The template's JVM test (`test/clj/new/re_frame2/core_test.clj`)
exercises each substrate end-to-end:

1. Generate a tmp app via the template's main fn.
2. Walk the produced file tree and assert the expected shape.
3. Run `clojure -P` against the generated `deps.edn` to confirm a
   successful deps-parse.

The test runs on CI. A change that breaks file-tree shape for any
substrate fails the build. The `clojure -P` check is best-effort
— it requires a `clojure` CLI on PATH, logs and skips if not
available.

## Cross-references

- [`tools/README.md`](../../README.md) — the bundle-isolation
  contract this template satisfies trivially.
- [000-Vision.md](000-Vision.md) — non-goals.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY decisions.
