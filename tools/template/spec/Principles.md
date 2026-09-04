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

- Is the same shape developers read about in [the Guide introduction
  — a tiny counter application](../../../docs/core/introduction.md).
- Matches the per-substrate `examples/<substrate>/counter*/`
  reference apps.
- Uses the smallest amount of re-frame2 surface that demonstrates
  the full cycle: an init event, an action event, a sub, a view
  that dispatches.

A developer who runs
`clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app`
and then opens the guide sees the same code in both places. No
mismatch, no "wait, what's this different shape doing here?"
moment.

## P3 — Substrate-agnostic shell, substrate-specific surface

Events (`events.cljs`), subs (`subs.cljs`), the test
(`events_test.cljs`), the build configs (`shadow-cljs.edn`,
`package.json`) and the host shell (`README.md`, `.gitignore`,
`resources/public/index.html`, `css/app.css`) are **identical across
both substrates**. They live under `_shared/` in the resource tree
(plus `root/` for the bulk-copied content with default placement).

The substrate-specific files — the only three that genuinely vary
by substrate — are:

- `core.cljs` — substrate-specific render / mount + adapter wiring.
- `views.cljs` — substrate-specific component syntax.
- `deps.edn` — substrate-specific adapter coord + view library.

`shadow-cljs.edn` and `package.json` carry **no** substrate-varying
content (the React substrate is chosen in `deps.edn` + `core.cljs`,
and react / react-dom are the only npm dependencies for every
variant), so they too live under `_shared/` and emit once — the lone
per-variant difference is a display name in a comment / description,
filled by `{{substrate-label}}`.

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

`:substrate` is the one and only selector. A future substrate is a new
value of it — one registry entry, one resource tree, one `case` arm —
never a second key.

The v1 clj-new template plumbed `:substrate` through `:edn-args`
to work around clj-new's top-level-arg stripping; deps-new removed the
workaround. See
[DESIGN-RATIONALE.md §Retired §`:edn-args`-not-top-level](DESIGN-RATIONALE.md#retired--edn-args-not-top-level)
for the historical record.

## P5 — Pins in lockstep with the reference implementation

`:rf2-version`, `:shadow-version`, and `:react-version` are defined
in one place — the entry ns — and bumped in lockstep with the
repo-root `VERSION`, `implementation/package.json` and the re-frame2
release cadence. The view-library and Clojure pins in the per-substrate
`deps.edn` resources track the adapter and core `deps.edn` files under
`implementation/` the same way.

The point: what the template emits should match what the reference
implementation's own CI is exercising. A developer who runs the
template and then files an issue should be running the same combo
the maintainers smoke-test. Drift here is a bug.

## P6 — Strict on input shape, strict on substrate set

The template requires the substrate arg as a keyword — deps-new's
top-level k/v contract guarantees it arrives as a keyword, so
accepting string/symbol shapes would paper over registration errors
that should surface immediately. Pass a non-keyword, get an
`ex-info` naming the valid set.

The substrate **value** is also strict: anything not in
`#{:reagent :uix}` throws with a clear message naming the
valid set. No silent fallback to default, no "did you mean...?"
fuzz. The user sees the typo and fixes it.

The same posture covers the key set: any argument the template does not
accept — a typo, a retired flag — fails closed as unknown before a file
is written.

See [DESIGN-RATIONALE.md §8](DESIGN-RATIONALE.md) for the rationale.

## P7 — Tested end-to-end, per substrate

The template ships a layered JVM test suite under
`test/day8/re_frame2_template/`. Each substrate (Reagent / UIx)
is exercised end-to-end across the layers:

1. **Shape.** `template_test.clj` — generates a tmp app via
   `org.corfield.new/create` in-process (the full deps-new pipeline
   — `data-fn` / `template-fn` / `post-process-fn` — runs exactly
   as a `clojure -Tnew create` shell invocation would), and asserts
   the emitted file set IS the twelve-file manifest (a set equality),
   the parsed `deps.edn` / `shadow-cljs.edn` / `package.json`, the
   absence of every retired file, coordinate, npm package, preload and
   host in the emitted tree, the argument gate, and the npm name. Each
   negative check carries a witness that it bites. Always runs.
2. **Static parse.** `template_emission_test.clj` — parses every
   emitted `.cljs` file, resolves each `<alias>/<sym>` reference
   against the framework source under `implementation/`, and asserts
   the symbol exists; pins the hot-reload lifecycle facts on the
   emitted `core.cljs`. Always runs.
3. **Pin lockstep.** `version_lockstep_test.clj` — emits an app and
   asserts the template's pins match their sources of truth. Always
   runs.
4. **Behavioural.** `emitted_test_run_test.clj` — compiles the emitted
   `:app` and `:test` builds, runs the focused test under Node, loads
   the real emitted `index.html` in Chromium and proves it paints the
   counter and moves it 0 → 1 with zero uncaught pageerrors, then
   breaks the page's mount node and proves that same proof goes red.
   Gated behind `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` (CI sets it; off
   locally for the fast loop). Its one convenience — junctioning
   `implementation/node_modules` into the emitted project instead of
   an `npm install` per variant — is what would let an undeclared npm
   package compile green, so the tier reads the `:browser` build's own
   `manifest.edn` and requires every resolved package to be reachable
   from the emitted `package.json`.

   **The `:advanced` release is proven the same way, for every
   substrate.** `npm run release` is one of the three commands the
   emitted README documents, and the scaffold tells its user the
   resulting `resources/public` is deployable — so the tier finishes
   each substrate by running `shadow-cljs release app` and loading the
   emitted page a second time, over that optimised bundle, through the
   same Chromium driver. A compile that exits 0 is not the claim: under
   `:advanced` Closure renames un-inferrable property access and drops
   code behind `goog.DEBUG`, so a non-empty optimised `main.js` can
   still throw at load or paint nothing. The two verdicts are held
   apart by construction — the release must change `js/main.js`'s
   length, and the driver requires the globals it observes (`goog` /
   `cljs`, objects in the dev bundle and renamed away under
   `:advanced`) to be the ones that mode's bundle has — so neither
   bundle can be credited with the other's boot.

   The Chromium proof treats an unlaunchable browser asymmetrically,
   and deliberately. Every CI job that enables the tier also installs a
   browser, so under CI a missing one is a broken job and fails the
   run. Locally it is a skip, printed as a loud NOT PROVEN banner so it
   cannot read as a pass.

CI sets `RF2_TEMPLATE_RUN_EMITTED_TESTS=1`; a change that breaks the
manifest, drifts the framework surface, breaks the pin lockstep, or
breaks the emitted compile / boot / release fails the build. The
git-coord release workflow
(`.github/workflows/template-release.yml`) runs the same
`clojure -M:test` suite as a pre-release gate, so a `template-v…`
tag push that would publish a drifted template fails before the
GitHub Release is cut.

## Cross-references

- [`tools/README.md`](../../README.md) — the bundle-isolation
  contract this template satisfies trivially.
- [000-Vision.md](000-Vision.md) — non-goals.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY decisions.
