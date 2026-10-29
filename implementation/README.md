# re-frame2 — reference implementation

The v2 reference implementation, generated from the specification corpus in
`../spec/`. The acceptance test for the spec is:
"an AI can one-shot the implementation from the spec alone." This
directory is that one-shot.

## Status

The fast PR spine runs on every pull request. Expensive browser, bundle,
tool, template, and live-MCP gates run by changed surface and in the
scheduled/manual rigorous workflow. See [`../TESTING.md`](../TESTING.md)
for the canonical matrix. Open implementation beads live in `../.beads/`.

## Layout

The implementation is split into per-Maven-artefact subdirectories per
[Conventions §Adapter shipping convention](../spec/Conventions.md#adapter-shipping-convention),
for the adapters and the per-feature artefacts alike. Each subdirectory carries
its own `deps.edn`. The top-level `deps.edn` and `shadow-cljs.edn` are
build coordinators that pull every artefact onto a single classpath
for the cross-substrate builds (browser tests, elision probe, examples).

There are 2 top-level groupings:

- `adapters/` — adapter artefacts that bridge the reactive substrates
  (named "adapters", not "substrates": the substrate is the runtime an
  adapter bridges to). One directory per adapter (`reagent`, `uix`).
- per-feature artefacts — one flat directory per feature
  (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `ssr-ring`,
  `resources` — 8 in all), each plugged into core via the
  `re-frame.late-bind` hook table per
  [Conventions §Independence rule](../spec/Conventions.md#independence-rule).

The `fresco/` artefact sits beside `core/` and `adapters/` as re-frame2's own
native view substrate. Reagent and UIx are first-class, actively-supported
adapters.

```
implementation/
  deps.edn                   Top-level coordinator: :local/root deps for every artefact.
  package.json               npm deps for the CLJS test targets (shadow-cljs, react, playwright).
  shadow-cljs.edn            Top-level shadow build: pulls every artefact's src+test paths
                             plus ../examples for the cross-substrate test and example bundles.

  core/                      day8/re-frame2 — the core artefact.
    deps.edn                 Core's own deps (clojure, clojurescript). Stock Reagent is
                             the Reagent adapter's dep, not core's.
    src/re_frame/
      interop.{clj,cljs}     JVM / CLJS host primitives.
      registrar.cljc         (kind, id) → metadata + replacement-hooks.
      frame.cljc             Frame container, make-frame, destroy-frame!.
      router.cljc            Per-frame FIFO router + drain + dispatch-sync-in-handler guard.
      fx.cljc                Effect interpreter + fx-overrides + :rf.fx/skipped-on-platform.
      events.cljc            reg-event (the one event form) + removed-name stubs.
      subs.cljc              Sub cache with ref-counting + hot-reload eviction.
      interceptor.cljc       Interceptor chain runtime.
      std_interceptors.cljc  :rf.interceptor/path standard + path/unwrap removed-value stubs.
      cofx.cljc              reg-cofx supplier + declared-only delivery (:rf.cofx/requires).
      trace.cljc             Trace event emission + listener API.
      epoch.cljc             Per-frame :rf/epoch-record ring buffer + time-travel
                             (Tool-Pair §Time-travel; dev-only, re-frame.epoch.*).
      late_bind.cljc         Late-binding hook table for cross-artefact references.
      performance.cljc       Per-cascade duration capture + budget warnings (Spec 009).
      source_coords.cljc     Source-coord capture + format helpers (Spec 009).
      test_support.cljc      Test fixtures, dispatch-sync helpers, reset-runtime (Spec 008).
      conformance.cljc       DSL interpreter for fixture handler bodies.
      views.cljs             reg-view*, React frame-context bridge (CLJS-only).
      adapter/
        context.cljs         The shared React frame Context object every React-shaped
                             adapter consumes.
      substrate/
        adapter.cljc         The adapter contract: delegation, install/teardown, capability publication.
        plain_atom.cljc      Plain-atom adapter (JVM, SSR, headless).
      core.cljc              Public API surface (re-frame.core).
    test/re_frame/           JVM tests + the substrate-agnostic CLJS tests
                             (conformance, hash-check, elision-probe).

  adapters/                  Substrate adapters, one directory per adapter
                             (reagent, uix). Per-feature artefacts
                             (schemas, machines, ...) stay flat under implementation/.
    reagent/                 day8/re-frame2-reagent — the Reagent adapter artefact.
      deps.edn               :local/root dep on ../../core.
      src/re_frame/adapter/
        reagent.cljs         The Reagent adapter.
      test/re_frame/         CLJS tests of the Reagent adapter itself (mount, render flush,
                             hydration, Form-2/3 components, reg-view, SSR) and the
                             integration-shaped tests that drive Reagent's own primitives
                             (cross-spec DOM, diamond recompute, runtime).
    uix/                     day8/re-frame2-uix — the UIx adapter artefact.
      deps.edn               :local/root dep on ../../core; pulls com.pitch/uix.{core,dom}.
      src/re_frame/adapter/uix.cljs
                             The UIx adapter (use-sub, use-frame, etc.).

  schemas/                   day8/re-frame2-schemas — schemas (Spec 010).
    deps.edn                 :local/root dep on ../core; pulls Malli for runtime validation.
    src/re_frame/schemas.cljc      Malli runtime validation.
    test/re_frame/                 JVM + CLJS schema tests.

  machines/                  day8/re-frame2-machines — state machines (Spec 005).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/machines.cljc     Hierarchical FSM, :always, :after, :spawn / spawn / destroy.
    test/re_frame/                 JVM + CLJS machine tests.

  routing/                   day8/re-frame2-routing — routing (Spec 012).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/routing.cljc      reg-route, 6-rule rank cascade, query coercion,
                                   nav protocol, :rf.route/* events, :rf.nav/* fxs,
                                   :rf/route reg-sub family.
    test/re_frame/                 JVM routing tests.

  flows/                     day8/re-frame2-flows — flows (Spec 013).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/flows.cljc        reg-flow, topo-sort, dirty-check + hot-reload.
    test/re_frame/                 JVM flows tests.

  http/                      day8/re-frame2-http — managed HTTP (Spec 014).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/http/managed.cljc :rf.http/managed args-map, decode pipeline, retry,
                                   abort, frame-aware reply addressing.
    test/re_frame/                 JVM + CLJS http-managed tests.

  ssr/                       day8/re-frame2-ssr — SSR (Spec 011).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/ssr.cljc          Hiccup → HTML5 emitter, :rf/hydrate, :rf.server/* fx,
                                   error projector.
    test/re_frame/                 JVM SSR end-to-end tests + source-coord parity tests.

  resources/                 day8/re-frame2-resources — managed resource queries
                             (Spec 016, EP-0003).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/resources.cljc    reg-resource / clear-resource / resource-meta /
                                   resource-state / resources, :rf.resource/* events
                                   + passive subs, :resource registrar kind, in-flight attempts.
    test/re_frame/                 CLJS surface/wiring smoke + runtime behaviour tests
                                   (ensure/refetch, in-flight attempts, invalidation/GC, hydration).

  fresco/                   day8/re-frame2-fresco — the Fresco view substrate: a
                             boundary is a real React function component minted by
                             `defview`, and the runtime owns only what React does not.
                             Its benchmark harness is bench/fresco/ at the repo
                             root, its own hand-run shadow project off this
                             classpath.
    deps.edn                 :local/root deps on ../core and ../ssr (the server module);
                             own :test alias, and the :clein + :clein/build deploy
                             aliases — published as day8/re-frame2-fresco.
    scripts/check_optional_module_reachability.py
                             No optional module is reachable from the public door, and
                             nothing in the package requires UIx or the bench tree.
    scripts/check_modules_compile.cjs
                             The optional modules and the two core attribution
                             instruments compile warnings-fatal (test:fresco-compile).
    src/re_frame/fresco.cljc      Public door — the three macros (defview, event, defhost)
                                   and the author-facing vars, each an alias.
    src/re_frame/fresco/impl/     The runtime: codec, controlled, intent, slot,
                                   state, presence, route-link, runtime, boundary, mount.
    test/re_frame/                 Package smoke: a defview reads a sub through the door
                                   (shadow-cljs build :node-test-fresco).

  ssr-ring/                  day8/re-frame2-ssr-ring — Ring host adapter for the SSR pipeline.
    deps.edn                 :local/root deps on ../core and ../ssr (Ring is test-only).
    src/re_frame/ssr/ring.clj      Ring handler wrapper + :rf.server/* cookie/header glue.
    test/re_frame/                 JVM Ring-adapter tests.

  ssr-node/                  The bounded Node/React SSR service. NOT a Clojure
                             artefact and NOT on any classpath or shadow build: plain Node,
                             node: builtins only, no deps.edn and no npm dependency. The JVM
                             owns the request, Node owns the React render, and this bounds
                             the crossing — per-request state isolation via immutable
                             snapshots, a fail-closed request allowlist, one in-flight render
                             per isolate, timeout with hard termination, and a pre-registered
                             latency envelope.
    README.md                The contract, the protocol, the envelope, and deployment.
    src/protocol.cjs         Message shapes + the fail-closed validator. No transport in it.
    src/{isolate,pool}.cjs   One worker thread per isolate; admission, deadline, terminate.
    src/service.cjs          renderToString: validate, admit, render, release.
    src/http.cjs             One transport over the protocol: POST /render, GET /health.
    test/                    node:test suites, run by test/run.cjs. No build, no browser.

  test-quiet/                day8/re-frame2-test-quiet — quiet-on-success cljs.test /
                             clojure.test reporter shared across the test runners.
    deps.edn                 No runtime deps; a test-tooling library.
    src/re_frame/test_quiet*       Silent-on-success reporter + runner entry points.

  spec-resource/             day8/re-frame2-spec-resource — the ONE build-time reader for
                             committed `spec/` data. The macro that inlines a spec-side file
                             at macro-expansion time (the api-manifest sidecar) reads through
                             it, so the file participates in the compiling namespace's
                             shadow-cljs cache key instead of being frozen into it invisibly.
                             Shared rather than copied because resolving shadow's recording
                             reader is a cold-load race that two independent resolvers lose
                             to each other.
    deps.edn                 No runtime deps, and none on shadow-cljs; build/test-time only.
    src/re_frame/build/spec_resource.clj  Recording read + the require-before-resolve
                             that makes the reader cold-load-safe.
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
                             the reply-envelope families (HTTP, resources, mutations,
                             routing) plus the egress projection of reply
                             slots through `trace-summary`. Core owns the
                             `project-egress` profiles.
    test/re_frame/                 JVM + CLJS cross-family reply conformance suites.

  derivation-conformance/    Cross-family derivation/process-algebra conformance tier
                             (EP-0014) — test-only, no shipped namespace. Holds the
                             umbrella axes that sit above several artefacts: graph
                             edges, lifecycle release, on-demand evaluation and graph
                             egress, proven across all five families (subscriptions,
                             flows, resources, route facts, machines) through the
                             graph composer (`re-frame.derivation.graph`). Each
                             family's own suite pins its lowering and classification.
    test/re_frame/                 JVM + CLJS cross-family derivation conformance suites.

  integration/               The framework integration suite — test-only, no shipped
                             namespace. The examples' and the features' end-to-end tests
                             (realworld, todomvc, routing, websocket, boot, …), which drive
                             events, subscriptions and effects rather than views. Every
                             fixture installs the adapter `re-frame.integration-adapter`
                             names: Fresco's substrate, the taught default.
    test/re_frame/                 CLJS integration suites.
```

## Status by spec area

| Spec | Status | Conformance fixtures |
|------|--------|----------------------|
| 001 Registration | Done | (covered transitively) |
| 002 Frames | Done | dispatch/envelope, drain/depth-limit, frame/{lifecycle,multi-instance}, fx/{db-first,ordering-source-order,override-by-id} |
| 003 — | Reserved (no `003-*.md`; held open per [`spec/README.md`](../spec/README.md) for future cross-frame composition work) | — |
| 004 — | Vacant (no `004-*.md` — see [`spec/README.md`](../spec/README.md); `004B` and `004C` are separate Specs, and this table does not grade them) | — |
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

The framework integration suite (`integration/`) runs on Fresco's
substrate, the taught default; each adapter's own tests stay with that
adapter and run against it. The UIx adapter is smoke-tested via the
counter + login pair per [Conventions §Adapter test matrix policy](../spec/Conventions.md#adapter-test-matrix-policy).

## Running tests

For the repo-level tiers, prefer:

```sh
../scripts/test-fast-pr.sh
../scripts/test-jvm-implementation.sh
../scripts/test-rigorous-local.sh
```

Per-artefact JVM (no setup beyond the Clojure CLI): run `clojure -M:test`
from the artefact's own directory.

```sh
cd implementation/core
clojure -M:test
```

Every JVM lane, with the aliases CI runs, is a row of
[`../scripts/jvm-lanes.txt`](../scripts/jvm-lanes.txt), and
[`../scripts/test-jvm-implementation.sh`](../scripts/test-jvm-implementation.sh)
runs every `implementation/` row. `clojure -M:test` here, in
`implementation/` itself, starts a REPL on the combined classpath and runs
no tests.

The core run executes the full JVM suite — smoke, conformance, drain,
SSR end-to-end and so on. Conformance grades two corpora, each by its
runnable subset: the portable one, every `.edn` in
`../../spec/conformance/fixtures/`, and the reference-only one in
`test/conformance-reference/`, which grades shapes internal to this
implementation.

CLJS (one-time `npm install` at the implementation/ root, then iterate):

```sh
cd implementation
npm install
npm run test:cljs       # node-test build, every artefact's CLJS test tree
npm run test:browser    # browser-test build, headless Chromium via Playwright
npm run test:elision    # production-elision contract (Spec 009 §Production builds)
npm run test:bundle-isolation
npm run test:adapter-smokes   # one browser smoke per adapter (adapters/*/testbed)
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

If port 8021 is already in use (for example another local repo's dev server),
the harness logs a warning and falls back to a free OS-chosen port.
Set `BROWSER_TEST_PORT` to pin a specific port (CI determinism); the
harness still falls back if that port is busy too.

## Running the dev testbeds

`npm run dev` (`scripts/dev-testbed.cjs`) is the cross-platform launcher
for the Xray / Story driving surfaces. It spawns `shadow-cljs watch` for
the explicit build-ids you name, printing each watched build's served URL
on start. "Open in editor" needs nothing from it — every testbed's
`:dev-http` entry runs the JVM open-in-editor endpoint, which resolves
source coordinates against the live source paths at request time, so the
chips work on a fresh clone at any path, on any OS:

```sh
cd implementation
npm run dev -- :examples/standard-epochs
npm run dev -- :testbeds/panel-gallery
# watch a handful at once — keep the count modest (see the note below):
npm run dev -- :examples/standard-epochs :examples/routes-epochs
npm run dev -- :examples/login-form --verbose
```

Name the build(s) you actually want to watch. Extra `shadow-cljs watch`
flags pass straight through, and duplicate build-ids are de-duped while
preserving first-seen order.

> Why explicit build-ids, not a "watch everything" alias? Each
> `shadow-cljs watch` build kicks off a Closure externs-prebuild, and
> several builds compiled simultaneously race on the shared `externs.zip`
> — intermittent build failures ("Exception parsing externs.zip",
> "`this.contents` is null"), reliably on some machines (notably
> Windows) once a watch names half a dozen builds. Watch the build(s) you
> need; keep any explicit list short.

| Build id | URL |
|---|---|
| `:examples/standard-epochs` | http://localhost:8031/ |
| `:examples/routes-epochs` | http://localhost:8032/ |
| `:examples/machine-epochs` | http://localhost:8033/ |
| `:examples/edn-inspector` | http://localhost:8034/ |
| `:examples/managed-http` | http://localhost:8035/ |
| `:examples/two-frame-isolation` | http://localhost:8030/ |
| `:testbeds/panel-gallery` | http://localhost:8765/ |
| `:examples/nine-states-with-stories` | http://localhost:8040/ · `/#/stories` |
| `:examples/login-with-stories` | http://localhost:8041/ · `/#/stories` |
| `:examples/counter-with-stories` | http://localhost:8042/ · `/#/stories` |
| `:examples/login-form` | http://localhost:8043/ · `/#/stories` |
| `:examples/linearlite` | http://localhost:8044/ |
| `:examples/fresco-counter` | http://localhost:8045/ · `/#/stories` |
| `:testbeds/tenant-switcher` | http://localhost:8060/ |
| `:fresco/hmr-testbed` | http://localhost:8061/ |

The build→port table mirrors the `:dev-http` map in `shadow-cljs.edn`.

## Vocabulary — commonly confused public concepts

When in doubt, the canonical reference is [`../spec/Conventions.md`](../spec/Conventions.md)
and [`../spec/Ownership.md`](../spec/Ownership.md). The notes below
disambiguate names that look interchangeable at a glance.

- id-only enumeration vs `rf/registrations` — for just the ids
  under a kind, project `registrations`' keys
  (`(-> (rf/registrations {:kind :event}) keys set) → #{...}`). `registrations`
  returns the full id→metadata map; use it directly when the caller
  needs the per-handler value (`:doc`, source coords, route template,
  fx fn, flow def). There is no `rf/handler-ids` projection.
- adapter vs substrate vs artefact — substrate is the
  reactive runtime (Reagent, UIx). Adapter is the
  `re-frame.adapter.*` ns that bridges core to that substrate
  (not "substrate adapter" or "renderer"). Artefact is a Maven coordinate the adapter ships
  as (for example `day8/re-frame2-reagent`, `day8/re-frame2-uix`).
- `story/ids` vs `rf/registrations` — both enumerate registered
  ids for a kind. `story/ids` is Story's re-export of
  `registrar/ids` colocated with the Story facade so test-driver
  code does not pull `re-frame.core`; `(-> (rf/registrations {:kind kind})
  keys set)` is the public route for application code (there is no
  `rf/handler-ids` projection). They return
  the same id set for the same kind.
- projected epoch record vs raw `:db-after` — `epoch/projected-
  record` returns the elision-safe view of an epoch (the structured
  `:sub-runs` / `:renders` / `:effects` projections) and is safe
  for off-box surfaces (Xray, Story-MCP, Pair2-MCP). The raw
  `:db-before` / `:db-after` snapshots on a record are devtools-local
  and must not cross the elision boundary.

## What's not in scope

- the migration agent (re-frame v1 → v2). That's a separate
  AI-driven task per `../migration/from-re-frame-v1/README.md`.
