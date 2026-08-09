# re-frame2 — reference implementation

The v2 reference implementation, generated from the specification corpus in
`../spec/`. The acceptance test for the spec is:
*"an AI can one-shot the implementation from the spec alone."* This
directory is that one-shot.

## Status

The fast PR spine runs on every pull request; expensive browser, bundle,
tool, template, and live-MCP gates run by changed surface and in the
scheduled/manual rigorous workflow. See [`../TESTING.md`](../TESTING.md)
for the canonical matrix. Open implementation beads live in `../.beads/`.

## Layout

The implementation is split into per-Maven-artefact subdirectories per
[Conventions §Adapter shipping convention](../spec/Conventions.md#adapter-shipping-convention)
(rf2-0hxm) — extended to per-feature artefacts under rf2-p7va (schemas),
rf2-xbtj (machines), rf2-k682 (routing), rf2-tfw3 (flows), rf2-5kpd
(http), rf2-uo7v (ssr), and rf2-lt4e (epoch). Each subdirectory carries
its own `deps.edn`; the top-level `deps.edn` and `shadow-cljs.edn` are
build coordinators that pull every artefact onto a single classpath
for the cross-substrate builds (browser tests, elision probe, examples).

Two top-level groupings:

- **`adapters/`** — substrate adapters (rf2-zha9 directory introduction;
  rf2-0imy canonical naming, "adapters" not "substrates"). One directory
  per adapter (`reagent`, `uix`, `reagent-slim`).
- **Per-feature artefacts** — one flat directory per feature
  (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `ssr-ring`,
  `resources`, `epoch` — nine in all), each plugged into core via the
  `re-frame.late-bind` hook table per
  [Conventions §Independence rule](../spec/Conventions.md#independence-rule).

The `ui/` artefact (the re-frame.ui compiled-view substrate, epic
rf2-vxgfnd) sits beside `core/` and `adapters/`: it is a **new,
experimental view substrate offered alongside the existing adapters** —
not another adapter under `adapters/` and not a late-bind feature
artefact. Reagent, reagent-slim, and UIx live on as first-class,
actively-supported adapters; only Helix is removed
([EP-0030](../docs/EP/EP-0030-the-compiled-view-substrate-program.md)
Resolved Decisions, 2026-07-17).

The `freehand/` artefact ([EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md))
sits beside it as the ratified successor: one re-frame-native view substrate with
an interpreted paved path and a compiled hot tier over one semantic model,
published through `re-frame.freehand`. It is built by **absorption** — useful
`ui/` code moves into it slice by slice — so `freehand/` declares no dependency
on `ui/` and never will; `ui/` is a donor, deleted at the F6 conformance gate.

```
implementation/
  deps.edn                   Top-level coordinator: :local/root deps for every artefact.
  package.json               npm deps for the CLJS test targets (shadow-cljs, react, playwright).
  shadow-cljs.edn            Top-level shadow build: pulls every artefact's src+test paths
                             plus ../examples for the cross-substrate test and example bundles.

  core/                      day8/re-frame2 — the core artefact.
    deps.edn                 Core's own deps (clojure, clojurescript, reagent).
    src/re_frame/
      interop.{clj,cljs}     JVM / CLJS host primitives.
      registrar.cljc         (kind, id) → metadata + replacement-hooks.
      frame.cljc             Frame container, make-frame, destroy-frame!.
      router.cljc            Per-frame FIFO router + drain + dispatch-sync-in-handler guard.
      fx.cljc                Effect interpreter + fx-overrides + :rf.fx/skipped-on-platform.
      events.cljc            reg-event (the one event form) + removed-name stubs.
      subs.cljc              Sub cache with ref-counting + hot-reload eviction.
      interceptor.cljc       Interceptor chain runtime.
      std_interceptors.cljc  path, unwrap, ->interceptor primitive.
      cofx.cljc              reg-cofx supplier + declared-only delivery (:rf.cofx/requires).
      trace.cljc             Trace event emission + listener API.
      late_bind.cljc         Late-binding hook table for cross-artefact references.
      performance.cljc       Per-cascade duration capture + budget warnings (Spec 009).
      source_coords.cljc     Source-coord capture + format helpers (Spec 009).
      test_support.cljc      Test fixtures, dispatch-sync helpers, reset-runtime (Spec 008).
      conformance.cljc       DSL interpreter for fixture handler bodies.
      views.cljs             reg-view*, React frame-context bridge (CLJS-only).
      adapter/
        context.cljs         The shared React frame Context object every React-shaped
                             adapter consumes (rf2-3yij Decision 2).
      substrate/
        adapter.cljc         The ten-fn adapter contract (6 required + 3 optional + 1 lifecycle).
        plain_atom.cljc      Plain-atom adapter (JVM, SSR, headless).
      core.cljc              Public API surface (re-frame.core).
    test/re_frame/           JVM tests + the substrate-agnostic CLJS tests
                             (conformance, hash-check, elision-probe).

  adapters/                  Substrate adapters live here per rf2-zha9 (renamed from
                             substrates/ per rf2-0imy) — one directory per adapter
                             (reagent, uix, reagent-slim). Per-feature artefacts
                             (schemas, machines, ...) stay flat under implementation/.
    reagent/                 day8/re-frame2-reagent — the Reagent adapter artefact.
      deps.edn               :local/root dep on ../../core.
      src/re_frame/adapter/
        reagent.cljs         The Reagent adapter.
      test/re_frame/         CLJS tests that exercise the Reagent adapter end-to-end
                             (cross-spec, events, hot-reload, http-managed, machines,
                             nine-states, realworld, render-key, routing, runtime,
                             schemas).
    uix/                     day8/re-frame2-uix — the UIx adapter artefact (rf2-3yij).
      deps.edn               :local/root dep on ../../core; pulls com.pitch/uix.{core,dom}.
      src/re_frame/adapter/uix.cljs
                             The UIx adapter (use-subscribe, flush-views!, etc.).
    reagent-slim/            day8/reagent-slim — Reagent rewrite for React 19
                             (rf2-5djt; Stage 4 rf2-6hyy). Stage 4 landed: the full
                             reagent2.* rewrite on disk — reactive primitives
                             (ratom), render scheduler (impl/batching), component-shape
                             detection (impl/component), hiccup translation
                             (impl/template), DOM Root API (dom + dom/client) and
                             pure-CLJS render-to-string (dom/server), plus the
                             re-frame.adapter.reagent-slim adapter and its own CLJS
                             test suite (~20 test files).

  schemas/                   day8/re-frame2-schemas — schemas (Spec 010, rf2-p7va).
    deps.edn                 :local/root dep on ../core; pulls Malli for runtime validation.
    src/re_frame/schemas.cljc      Malli runtime validation.
    test/re_frame/                 JVM + CLJS schema tests.

  machines/                  day8/re-frame2-machines — state machines (Spec 005, rf2-xbtj).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/machines.cljc     Hierarchical FSM, :always, :after, :spawn / spawn / destroy.
    test/re_frame/                 CLJS machine tests.

  routing/                   day8/re-frame2-routing — routing (Spec 012, rf2-k682).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/routing.cljc      reg-route, 6-rule rank cascade, query coercion,
                                   nav protocol, :rf.route/* events, :rf.nav/* fxs,
                                   :rf/route reg-sub family.
    test/re_frame/                 JVM routing tests.

  flows/                     day8/re-frame2-flows — flows (Spec 013, rf2-tfw3).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/flows.cljc        reg-flow, topo-sort, dirty-check + hot-reload.
    test/re_frame/                 JVM flows tests.

  http/                      day8/re-frame2-http — managed HTTP (Spec 014, rf2-5kpd).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/http/managed.cljc :rf.http/managed args-map, decode pipeline, retry,
                                   abort, frame-aware reply addressing.
    test/re_frame/                 JVM + CLJS http-managed tests.

  ssr/                       day8/re-frame2-ssr — SSR (Spec 011, rf2-uo7v).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/ssr.cljc          Hiccup → HTML5 emitter, :rf/hydrate, :rf.server/* fx,
                                   error projector.
    test/re_frame/                 JVM SSR end-to-end tests + source-coord parity tests.

  epoch/                     day8/re-frame2-epoch — per-frame epoch history
                             (Tool-Pair §Time-travel, rf2-lt4e).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/epoch.cljc        Per-frame :rf/epoch-record ring buffer + projection
                                   walker for sub-runs / renders / effects.
    test/re_frame/                 JVM epoch tests.

  resources/                 day8/re-frame2-resources — managed resource queries
                             (Spec 016, EP-0003, rf2-p10npe).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/resources.cljc    reg-resource / clear-resource / resource-meta /
                                   resource-state / resources, :rf.resource/* events
                                   + passive subs, :resource registrar kind, work-ledger.
    test/re_frame/                 CLJS surface/wiring smoke + runtime behaviour tests
                                   (ensure/refetch, work ledger, invalidation/GC, hydration).

  ui/                        day8/re-frame2-ui — the re-frame.ui compiled-view substrate
                             (epic rf2-vxgfnd; S1a skeleton per rf2-vxgfnd.1 — the
                             compiler slice lands from S1b).
    deps.edn                 :local/root dep on ../core; own :test alias (never published,
                             so no :clein deploy aliases at all).
    src/re_frame/ui.cljc           Public-surface root stub (defview et al. land S1b+).
    src/re_frame/ui/compiler.cljc  Compiler entry stub (AST / analyzer / emitters, S1b).
    src/re_frame/ui/client.cljs    Client-kernel stub (mount surface S1c; reactivity S2).
    test/re_frame/                 Classpath + build-id probe (npm run test:ui).

  freehand/                  day8/re-frame2-freehand — the Freehand view substrate
                             (EP-0036; F1a skeleton per rf2-drpa3.15 — the paved-path
                             spine lands from F1b). Declares no dependency on ui/.
    deps.edn                 :local/root dep on ../core; own :test alias (pre-publication,
                             so no :clein deploy aliases).
    src/re_frame/freehand.cljc     Public-door namespace — empty of surface at F1a
                                   (defview / sub / mount land with the F1 spine).
    test/re_frame/                 Classpath + build-wiring probe (npm run test:freehand).

  hicasso/                   day8/re-frame2-hicasso — the Hicasso view substrate: a
                             boundary is a real React function component minted by
                             `defview`, and the runtime owns only what React does not.
                             Extracted by rf2-hic-001 as a mechanical copy of the
                             measured prototype in freehand/test/re_frame/bench/hicasso/,
                             which stays where it is and keeps running.
    deps.edn                 :local/root dep on ../core; own :test alias (pre-publication,
                             so no :clein deploy aliases).
    frozen-sources.edn       Every donor file the copy read, pinned by digest, plus the
                             rename table and the forbidden import prefixes.
    scripts/check_freeze.py  The gate over that pin: the bench tree has not drifted, and
                             nothing in the package imports it. --self-test included.
    src/re_frame/hicasso.cljc      Public door — the three macros (defview, hfn, defhost)
                                   and the author-facing vars, each an alias.
    src/re_frame/hicasso/impl/     The copied runtime: codec, controlled, intent, slot,
                                   state, presence, route-link, runtime, boundary, mount.
    test/re_frame/                 Package smoke: a defview reads a sub through the door
                                   (shadow-cljs build :node-test-hicasso).

  ssr-ring/                  day8/re-frame2-ssr-ring — Ring host adapter for the SSR pipeline
                             (rf2-ny6v7).
    deps.edn                 :local/root deps on ../core and ../ssr (Ring is test-only).
    src/re_frame/ssr/ring.clj      Ring handler wrapper + :rf.server/* cookie/header glue.
    test/re_frame/                 JVM Ring-adapter tests.

  test-quiet/                day8/re-frame2-test-quiet — quiet-on-success cljs.test /
                             clojure.test reporter shared across the test runners (rf2-try1x).
    deps.edn                 No runtime deps; a test-tooling library.
    src/re_frame/test_quiet*       Silent-on-success reporter + runner entry points.

  spec-resource/             day8/re-frame2-spec-resource — the ONE build-time reader for
                             committed `spec/` data. Macros that inline a spec-side file at
                             macro-expansion time (the Freehand conformance fixtures, the
                             api-manifest sidecar) read through it, so the file participates
                             in the compiling namespace's shadow-cljs cache key instead of
                             being frozen into it invisibly. Shared rather than copied
                             because resolving shadow's recording reader is a cold-load
                             race that two independent resolvers lose to each other.
    deps.edn                 No runtime deps, and none on shadow-cljs; build/test-time only.
    src/re_frame/build/spec_resource.clj  Recording read + walk-up fallback + the
                             require-before-resolve that makes the reader cold-load-safe.
    test/re_frame/build/           The deterministic race control: it holds the
                             interned-but-unbound window open and drives two independent
                             resolver sites into it.

  security/                  Cross-cutting security regression tests (MCP egress, schema
                             redaction, SSR escaping) — test-only, no shipped namespace.
    test/re_frame/security/        JVM + CLJS security regression suites.

  reply-conformance/         Cross-family reply-vocabulary conformance tier (EP-0011) —
                             test-only, no shipped namespace. Holds the umbrella guards
                             that sit above several artefacts: the shared :status /
                             :rf.reply/work-status / :work/id / canonical-stale vocabulary across
                             every managed-async family (HTTP, resources, mutations,
                             machines, routing) plus the family-level functor/naturality
                             law at the target-relocating families.
    test/re_frame/                 JVM + CLJS cross-family reply conformance suites.

  derivation-conformance/    Cross-family derivation/process-algebra conformance tier
                             (EP-0014) — test-only, no shipped namespace. Holds the
                             umbrella axes that sit above several artefacts: the
                             EP-0014 algebra axes (lowering, classification, graph
                             edges, whole-value, lifecycle, evaluation) proven across
                             all five families (subscriptions, flows, resources, route
                             facts, machines) through the graph composer
                             (`re-frame.derivation.graph`).
    test/re_frame/                 JVM + CLJS cross-family derivation conformance suites.

  event-conformance/         One-form event-MODEL conformance tier (EP-0018) —
                             test-only, no shipped namespace. Holds the umbrella
                             regression lock on the one-form event-registration
                             public contract: `reg-event` as the single public
                             form (reg-event-fx semantics), the three retired
                             names (`reg-event-db`/`-fx`/`-ctx`) surviving only as
                             `^:no-doc` throwing stubs, the single
                             `:rf/event-handler` wrapper (no `:event/kind` sub-tag),
                             and preserved realm-routing. Spans the events runtime,
                             the public facade, the error-emit channel, and the
                             realm registrar.
    test/re_frame/                 JVM + CLJS one-form event-model conformance suite.
```

## Status by spec area

| Spec | Status | Conformance fixtures |
|------|--------|----------------------|
| 001 Registration | Done | (covered transitively) |
| 002 Frames | Done | dispatch/envelope, drain/depth-limit, frame/{lifecycle,multi-instance}, fx/{db-first,ordering-source-order,override-by-id} |
| 003 — | Reserved (no `003-*.md`; held open per [`spec/README.md`](../spec/README.md) for future cross-frame composition work) | — |
| 004 Views | Done (JVM-runnable + CLJS via Reagent) | covered via reg-view in SSR fixtures |
| 005 State Machines | Done | machine/transition, hierarchical-{compound,cross-level,parent-fallthrough}, always-{single-microstep,depth-exceeded}, after-{single-delay,stale-detection,hierarchy}, spawn-on-entry-destroy-on-exit |
| 006 Reactive Substrate | Done (Reagent + plain-atom; UIx smoke-tested) | sub/chain |
| 007 Stories | Out of scope for the CLJS reference (no canonical implementation; the spec defines the Story / Variant / Workspace contract for tooling consumers) | — |
| 008 Testing | Done | dispatch-sync, conformance harness |
| 009 Instrumentation | Done | error/{handler-exception,fx-handler-exception,sub-exception,no-such-handler,override-fallthrough} |
| 010 Schemas | Done | error/schema-failure |
| 011 SSR | Done | ssr/{render-to-string,hydrate,hydration-mismatch,head-emits,head-hydration,error-known-mapping,error-sanitisation,cookie,redirect,set-status}, fx/platforms |
| 012 Routing | Done | routing/{match-url,navigate,fragment-change,navigation-blocked,ranking-precedence,stale-nav-token-suppression} |
| 013 Flows | Done | (covered via smoke + flow-recomputes) |
| 014 HTTP requests | Done | (covered via the `http_managed_test` suites + the managed-HTTP example smoke spec) |

The Reagent adapter is the canonical adapter — every test target (every
`clojure -M:test` run, every `node-test` build, every `:browser-test`
run, every `examples` run, every conformance fixture) executes against
it. The UIx adapter is smoke-tested via the counter + login
pair per [Conventions §Adapter test matrix policy](../spec/Conventions.md#adapter-test-matrix-policy).
The `reagent-slim` adapter has landed Stage 4 (the full `reagent2.*`
rewrite) and carries its own CLJS test suite — the `reagent2.*` internals
plus the slim-adapter substrate-shape, container round-trip, derived-value,
disposal, render-sequence, and source-coord / view-id contracts.

## Running tests

For the repo-level tiers, prefer:

```sh
../scripts/test-fast-pr.sh
../scripts/test-jvm-implementation.sh
../scripts/test-rigorous-local.sh
```

**Per-artefact JVM** (no setup beyond Clojure CLI):

```sh
# core artefact
cd implementation/core
clojure -M:test

# reagent artefact (CLJS-only — JVM run is a classpath probe; 0 tests is normal)
cd implementation/adapters/reagent
clojure -M:test

# schemas artefact
cd implementation/schemas
clojure -M:test

# machines artefact (CLJS-only — JVM run is a classpath probe; 0 tests is normal)
cd implementation/machines
clojure -M:test

# routing artefact
cd implementation/routing
clojure -M:test

# flows artefact
cd implementation/flows
clojure -M:test

# http artefact
cd implementation/http
clojure -M:test

# ssr artefact
cd implementation/ssr
clojure -M:test

# epoch artefact
cd implementation/epoch
clojure -M:test
```

The core run executes the full JVM suite — smoke, conformance, drain,
SSR end-to-end, etc. — loading every `.edn` in
`../../spec/conformance/fixtures/` and running the runnable subset
against this implementation.

**CLJS** (one-time `npm install` at the implementation/ root, then iterate):

```sh
cd implementation
npm install
npm run test:cljs       # node-test build, every artefact's CLJS test tree
npm run test:browser    # browser-test build, headless Chromium via Playwright
npm run test:elision    # production-elision contract (Spec 009 §Production builds)
npm run test:bundle-isolation
npm run test:reagent-slim:bundle-isolation
npm run test:adapter-smokes   # example-app browser tests
```

`npm run test:cljs` builds the `:node-test` target via shadow-cljs and
runs `cljs.test` under Node. Every artefact's source and test trees
are on the classpath, so the core's substrate-agnostic CLJS tests AND
each adapter / per-feature artefact's CLJS tests run together.

`npm run test:browser` builds the `:browser-test` target into
`out/browser-test/`, serves it on `http://localhost:8021` via
`http-server`, then drives headless Chromium to the runner page and
parses the `cljs.test` summary (`Ran N tests containing M assertions.`).
Exits 0 on green, 1 on red. Use this when verifying anything that
depends on a real DOM, real browser timing, or React's DOM-rendering
pipeline.

If port 8021 is already in use (e.g. another local repo's dev server),
the harness logs a warning and falls back to a free OS-chosen port.
Set `BROWSER_TEST_PORT` to pin a specific port (CI determinism); the
harness still falls back if that port is busy too.

## Running the dev testbeds

`npm run dev` (`scripts/dev-testbed.cjs`) is the cross-platform launcher
for the Xray / Story driving surfaces. It seeds `RF2_TESTBED_PROJECT_ROOT`
(so "open in editor" works on a fresh clone at any path, on any OS) and
spawns `shadow-cljs watch` for the **explicit build-ids you name**,
printing each watched build's served URL on start:

```sh
cd implementation
npm run dev -- :examples/standard-epochs
npm run dev -- :testbeds/panel-gallery
# watch a handful at once — keep the count modest (see the note below):
npm run dev -- :examples/standard-epochs :examples/routes-epochs
npm run dev -- :examples/login-form --verbose
```

Name the build(s) you actually want to watch; extra `shadow-cljs watch`
flags pass straight through, and duplicate build-ids are de-duped while
preserving first-seen order.

> **Why explicit build-ids, not a "watch everything" alias?** Each
> `shadow-cljs watch` build kicks off a Closure externs-prebuild, and
> several builds compiled simultaneously race on the shared `externs.zip`
> — intermittent build failures ("Exception parsing externs.zip",
> "`this.contents` is null"). The old `xray` / `stories` / `all` group
> aliases fired up to six builds into a single watch and tripped the race
> reliably on some machines (notably Windows), so they were removed
> (rf2-trlj7). Watch the build(s) you need; keep any explicit list short.

| Build id | URL |
|---|---|
| `:examples/standard-epochs` | http://localhost:8031/ |
| `:examples/routes-epochs` | http://localhost:8032/ |
| `:examples/machine-epochs` | http://localhost:8033/ |
| `:examples/edn-inspector` | http://localhost:8034/ |
| `:examples/managed-http` | http://localhost:8035/ |
| `:testbeds/freehand-views` | http://localhost:8036/ |
| `:examples/two-frame-isolation` | http://localhost:8030/ |
| `:testbeds/panel-gallery` | http://localhost:8765/ |
| `:examples/nine-states-with-stories` | http://localhost:8040/ · `/#/stories` |
| `:examples/login-with-stories` | http://localhost:8041/ · `/#/stories` |
| `:examples/counter-with-stories` | http://localhost:8042/ · `/#/stories` |
| `:examples/login-form` | http://localhost:8043/ · `/#/stories` |
| `:examples/linearlite` | http://localhost:8044/ |
| `:testbeds/tenant-switcher` | http://localhost:8060/ |

The build→port table mirrors the `:dev-http` map in `shadow-cljs.edn`.

## Vocabulary — commonly confused public concepts

When in doubt, the canonical reference is [`../spec/Conventions.md`](../spec/Conventions.md)
and [`../spec/Ownership.md`](../spec/Ownership.md). The notes below
disambiguate names that look interchangeable at a glance.

- **id-only enumeration vs `rf/registrations`** — for just the ids
  under a kind, project `registrations`' keys
  (`(-> (rf/registrations :event) keys set) → #{...}`). `registrations`
  returns the full id→metadata map; use it directly when the caller
  needs the per-handler value (`:doc`, source coords, route template,
  fx fn, flow def). (The dedicated `rf/handler-ids` projection was
  removed — rf2-i4hk4b.)
- **adapter vs substrate vs artefact** — *substrate* is the
  reactive runtime (Reagent, UIx). *Adapter* is the
  `re-frame.adapter.*` ns that bridges core to that substrate
  (canonical naming per rf2-0imy; not "substrate adapter" or
  "renderer"). *Artefact* is a Maven coordinate the adapter ships
  as (e.g. `day8/re-frame2-reagent`, `day8/reagent-slim`).
- **`story/ids` vs `rf/registrations`** — both enumerate registered
  ids for a kind. `story/ids` is Story's re-export of
  `registrar/ids` colocated with the Story facade so test-driver
  code does not pull `re-frame.core`; `(-> (rf/registrations kind)
  keys set)` is the public route for application code (the dedicated
  `rf/handler-ids` projection was removed — rf2-i4hk4b). They return
  the same id set for the same kind.
- **projected epoch record vs raw `:db-after`** — `epoch/projected-
  record` returns the elision-safe view of an epoch (the structured
  `:sub-runs` / `:renders` / `:effects` projections) and is safe
  for off-box surfaces (Xray, Story-MCP, Pair2-MCP). The raw
  `:db-before` / `:db-after` snapshots on a record are devtools-local
  and must not cross the elision boundary.

## What's not in scope

- The migration agent (re-frame v1 → v2). That's a separate
  AI-driven task per `../migration/from-re-frame-v1/README.md`.
