# reagent-slim — Stage 3 implementation spec

> Stage 3 of rf2-5djt (parent epic). Bead **rf2-60le**.
> The artefact is `day8/reagent-slim`; the bridge is `day8/reagent-classic`.
> Stage 1 (rf2-ui6g) closed the surface; Stage 2 (rf2-142b) sized the wins. This
> document is the engineering spec Stage 4 (rf2-6hyy) implements against.
>
> Inputs (binding):
> - `findings/re-frame2-reagent-stage1-api-surface.md` — bounded surface, eight RESOLVED decisions.
> - `findings/reagent-slim-stage2-efficiency.md` — bundle + runtime estimates, top-3 commitments, R-001..R-007 risk register, S3-001..S3-008 inputs.
> - `findings/recom-react19-readiness-audit.md` (rf2-cgcv) — re-com + 10x lifecycle inventory.
> - `findings/dash8-rf8-react19-readiness-audit.md` (rf2-kfpf) — Dash8 + rf8 lifecycle + SSR inventory.
> - `findings/reagent-rewrite-analysis.md` §2 — friction inventory (kept per DECISION-8); §8 recommendation overruled.
> - `implementation/adapters/reagent/` — current thin bridge (147 LoC); rewrite folds it in.
> - `implementation/core/src/re_frame/views.cljs` (541 LoC) — reg-view wrapper + source-coord injector + plain-fn warn-once. Significant chunks fold into the renderer.
>
> Hard constraints (settled):
> 1. React 19 floor (DECISION-5; Stage 1 §2.3a) — no `reagent.dom`; throw-on-call for that namespace.
> 2. Maven coord `day8/reagent-slim` (DECISION-1).
> 3. Namespace tree under `reagent2.*` (DECISION-4); adapter Var unchanged at `re-frame.adapter.reagent`.
> 4. 7-key Form-3 cap (DECISION-3 + rf2-kfpf §6).
> 5. Narrowed `convert-prop-value` (DECISION-2; rf2-d4sf root cause structurally removed).
> 6. `render-to-static-markup` shipped pure-CLJS; `render-to-string` not (DECISION-6).
> 7. No Class A warning stubs (DECISION-7; surfaces with zero ecosystem usage simply absent).
> 8. Pre-release framing — no back-compat shims for dropped surfaces.

---

## §1 Artefact layout

The new artefact lives at `implementation/adapters/reagent-slim/` mirroring the existing `implementation/adapters/{reagent,uix,helix}/` pattern (rf2-zha9 / rf2-0imy). The `re-frame2-` prefix is dropped on the Maven coord (per Stage 1 DECISION-1) but the on-disk path stays inside `implementation/adapters/` because that is the lockstep verifier's "adapter tier" detection axis (`.github/scripts/verify-version-lockstep.sh:67-90`).

### §1.1 Directory shape

```
implementation/adapters/reagent-slim/
├── deps.edn
├── src/
│   ├── re_frame/
│   │   └── adapter/
│   │       └── reagent_slim.cljs      ; adapter Var at re-frame.adapter.reagent-slim
│   └── reagent2/
│       ├── core.cljs                  ; user-facing compat surface
│       ├── ratom.cljs                 ; reactive primitives
│       ├── ratom.clj                  ; the reaction macro (CLJS-only consumer)
│       ├── dom.cljs                   ; throw-on-call shims (Class B)
│       ├── dom/
│       │   ├── client.cljs            ; create-root / render / unmount / hydrate-root / flush-views!
│       │   └── server.cljs            ; pure-CLJS render-to-static-markup
│       ├── impl/
│       │   ├── batching.cljs          ; microtask scheduler + dirty-set
│       │   ├── component.cljs         ; create-class + 7-key dispatch
│       │   ├── template.cljs          ; hiccup → React element
│       │   └── util.cljs              ; the few internal helpers we keep
│       └── impl/
│           └── component.clj          ; defview macro (S3-003 optional path)
└── test/
    └── reagent2/
        ├── core_test.cljs
        ├── ratom_test.cljs
        ├── dom_client_test.cljs
        ├── dom_server_test.cljs
        ├── impl/
        │   ├── batching_test.cljs
        │   ├── component_test.cljs
        │   └── template_test.cljs
        └── parity_test.cljs           ; render-to-static-markup vs react-dom/server (R-004)
```

### §1.2 `deps.edn` shape

Modeled on `implementation/adapters/reagent/deps.edn`. The `:clein/build` block references the repo-root VERSION via the adapter-tier path (`../../../VERSION`); the `day8/re-frame2` coordinate uses `:local/root "../../core"` per the lockstep contract:

```clojure
{:paths ["src"]
 :deps  {day8/re-frame2 {:local/root "../../core"}}
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
         :main-opts   ["-m" "cognitect.test-runner"]}
  :clein {:extra-deps {io.github.noahtheduke/clein {:git/url "https://github.com/day8/clein.git"
                                                    :git/sha "2b83503246e8291fc023d688574640a9b23d8c50"}}
          :main-opts  ["-m" "noahtheduke.clein"]}
  :clein/build
  {:lib      day8/reagent-slim
   :main     re-frame.adapter.reagent
   :url      "https://github.com/day8/re-frame2"
   :version  "../../../VERSION"
   :license  {:name "MIT" :url "https://opensource.org/licenses/MIT"}
   :src-dirs ["src"]}}}
```

Note: the `:lib` is `day8/reagent-slim` (no `re-frame2-` prefix) per DECISION-1; the `:main` keyword keeps `re-frame.adapter.reagent` for backward compat with any tooling that resolves the adapter ns from clein's emitted pom.

### §1.3 Top-level `implementation/deps.edn` updates

Add the new artefact alongside the existing reagent / uix / helix adapter entries:

```clojure
{:paths []
 :deps  {day8/re-frame2                  {:local/root "core"}
         day8/re-frame2-reagent          {:local/root "adapters/reagent"}
         day8/reagent-slim       {:local/root "adapters/reagent-slim"}
         day8/re-frame2-uix              {:local/root "adapters/uix"}
         ;; … unchanged …
         }
 :aliases {:test {:extra-paths […
                                "adapters/reagent-slim/test"
                                …]}}}
```

### §1.4 `shadow-cljs.edn` integration

Two-line addition under `:source-paths`:

```
"adapters/reagent-slim/src"
"adapters/reagent-slim/test"
```

The `:dependencies` vector adds NO new entries — `reagent-slim` is a re-implementation that does not depend on stock `reagent`. (Stock Reagent stays in the dependency vector while the existing thin-bridge adapter ships; once the thin bridge is renamed to `day8/reagent-classic` and split off to its own future Maven coord, the stock-Reagent dep can be made conditional.)

The build matrix gains one switch: per-example builds that opt into the rewrite use the new adapter ns; per-example builds that stay on the bridge use the existing one. The parallel `examples/counter-slim-and-fast` build exercises the rewrite under the S3-008 bundle-comparison contract: source at `examples/reagent/counter_slim_and_fast/core.cljs`, build entry in `implementation/shadow-cljs.edn`, Playwright spec at `counter_slim_and_fast.spec.cjs`, and the symbol-grep contract at `implementation/scripts/check-counter-slim-and-fast.cjs` (npm script `test:bundle-comparison`, CI job `cljs-bundle-comparison` in `.github/workflows/test.yml`). Delivered per bead **rf2-5lbx**.

### §1.5 Lockstep verifier

Add `reagent-slim` to the `ARTEFACTS`, `NON_CORE`, `ADAPTERS`, and `ARTEFACT_PATHS` arrays in `.github/scripts/verify-version-lockstep.sh`:

```bash
ARTEFACT_PATHS[reagent-slim]="adapters/reagent-slim"
ARTEFACTS+=(reagent-slim)
NON_CORE+=(reagent-slim)
ADAPTERS+=(reagent-slim)
```

The verifier's adapter-tier check (lines 117-131) already handles three-deep paths via `is_adapter`; adding the artefact to the `ADAPTERS` array picks up that branch automatically. Stage 4 confirms the verifier passes locally before opening the Stage-4 PR.

### §1.6 CI: `.github/workflows/test.yml`

Add a `jvm-reagent-slim` job mirroring the existing `jvm-reagent` shape (lines 66-105). The artefact has no JVM-runnable tests — the classpath probe pattern (`clojure -M:test || echo "no JVM-runnable tests in reagent-slim artefact (expected)"`) verifies deps + classpath wiring. This is parity with the other adapter jobs.

The `cljs` and `cljs-browser` jobs require no changes — they pick up the new `:source-paths` entry via shadow-cljs's compile sweep.

### §1.7 CI: `.github/workflows/release.yml`

Add a `deploy-reagent-slim` job mirroring `deploy-reagent` (release.yml lines 294-357). The job:
1. `needs: deploy-core` (parallel with the other adapter-deploy leaves).
2. Rewrites `:local/root "../../core"` → `:mvn/version "${VERSION}"` in `implementation/adapters/reagent-slim/deps.edn`.
3. Runs `clojure -M:clein deploy` from `implementation/adapters/reagent-slim/`.

The terminal `github-release` job's `needs:` array (release.yml line 899-901) gains `deploy-reagent-slim`.

### §1.8 Bundle-isolation contract

**Delivered (rf2-5lbx)**: the dedicated bundle-comparison verifier lives at `implementation/scripts/check-counter-slim-and-fast.cjs` rather than in `scripts/check-bundle-isolation.cjs`. The two scripts answer different questions:

- `check-bundle-isolation.cjs` (rf2-51x5) — proves per-feature artefacts (schemas, machines, routing, flows, http, ssr, story) stay isolated from a no-feature counter bundle. The contract is about *which artefacts get pulled into a given bundle*.
- `check-counter-slim-and-fast.cjs` (rf2-5lbx) — proves the slim rewrite displaces stock-Reagent's impl tree even when both adapters live on the same shadow-cljs classpath. The contract is about *which substrate implementation ends up in the bundle*.

The slim-side contract: any stock `reagent.impl.*` / `reagent.dom` / `reagent.ratom` fingerprint appearing in the `examples/counter-slim-and-fast` :advanced bundle is a leak (S3-008). Likewise any `react-dom/server` symbol is a leak (S3-005's pure-CLJS-SSR claim) — the slim build deliberately exercises `reagent2.dom.server/render-to-static-markup` at boot so the contract is non-vacuous.

The sentinel set (validated against the stock-Reagent `examples/counter` bundle on the same release commit): `Compiler.{parse-tag,as-element,get-id,make-element}` (stock's `reagent.impl.template` CompilerImpl class methods), `ReagentInput` (stock's `reagent.impl.input`), `cljsRatom` (stock's `reagent.ratom` field on React-component links), `cljsLegacyRender` (stock's `reagent.dom` legacy-render path). All seven sentinels appear in the stock counter bundle and are 0-count in the slim bundle.

The complementary direction (no `reagent2.*` symbols in `examples/counter` on classic) is not currently enforced — the in-tree shadow-cljs build adds both `adapters/reagent/src` and `adapters/reagent-slim/src` to the classpath, so a per-classic-build assertion would have to coexist with the bridge's `reagent2.*` reverse-direction probe (which itself depends on stock Reagent's classpath). Stage 5 follow-up territory; rf2-5lbx's S3-008 contract is the binding claim.

---

## §2 Namespace shape (file-by-file)

Public namespace surface per Stage 1 §5.5 + DECISION-4. Compat namespace tree is `reagent2.*`; adapter Var stays at `re-frame.adapter.reagent`.

### §2.1 `re-frame.adapter.reagent` — adapter Var (UNCHANGED public path)

File: `implementation/adapters/reagent-slim/src/re_frame/adapter/reagent_slim.cljs`.

Stage 4 chose a distinct adapter ns (`re-frame.adapter.reagent-slim`) rather than overloading `re-frame.adapter.reagent`. The bridge adapter (`day8/re-frame2-reagent`) stays at the existing path; the rewrite ships alongside it as a sibling artefact (`day8/reagent-slim`). Apps select between them at boot via the explicit `(rf/init! <adapter-var>)` call — `reagent-adapter/adapter` (bridge) vs `reagent-slim-adapter/adapter` (rewrite).

Public Vars (signatures unchanged from current bridge):
- `adapter` — the substrate spec map (per `re-frame.substrate.adapter`'s 9-key contract).
- `set-hiccup-emitter!` — wires the SSR seam's render-to-string into the adapter's `:render-to-string` slot via the `:reagent/set-hiccup-emitter!` late-bind hook (`re-frame.late-bind`). Kept for compat with `re-frame.ssr` which already calls `(late-bind/get-fn :reagent/set-hiccup-emitter!)` at load time (`implementation/ssr/src/re_frame/ssr.cljc:360`).

Private helpers (one each per adapter slot):
- `make-state-container` — `(reagent2.core/atom initial-value)`.
- `read-container` — `@container`.
- `replace-container!` — `(reset! container new-value)`. The defense-in-depth nil guard from `re-frame.substrate.adapter/replace-container!` (lines 75-93) sits at the substrate caller, not here; this fn assumes non-nil.
- `subscribe-container` — `add-watch` keyed on a gensym.
- `make-derived-value` — `(reagent2.ratom/make-reaction (fn [] (apply compute-fn (map deref source-containers))))`.
- `render` — calls `reagent2.dom.client/render` or `hydrate-root` per opts; returns the unmount thunk.
- `render-to-string` — late-bind delegation to the SSR seam (unchanged from the bridge).
- `register-context-provider` — calls `re-frame.views/build-frame-provider` (unchanged).
- `dispose-adapter!` — nil (Reactions GC themselves).

The `:adapter/current-frame` late-bind hook (currently set to `views/current-frame` at `reagent.cljs:147`) stays wired the same way. The `:reagent/set-hiccup-emitter!` hook (`reagent.cljs:122`) likewise.

### §2.2 `reagent2.core` — user-facing compat surface

File: `src/reagent2/core.cljs`.

Public Vars (the audit-binding fourteen surfaces, per Stage 2 §2.7):

| Symbol | Signature | Notes |
|---|---|---|
| `atom` | `[x]`, `[x & {:keys [meta validator]}]` | Re-export of `reagent2.ratom/atom` (the `RAtom` constructor). |
| `create-class` | `[spec]` | Form-3 entry point. Validates `spec` against the 7-key cap; throws `:rf.error/create-class-key-unsupported` on miss. Delegates to `reagent2.impl.component/create-class*`. |
| `current-component` | `[]` | Returns the in-flight component instance via `reagent2.impl.component/*current-component*`. |
| `after-render` | `[f]` | Schedule `f` to run after the next React commit. Routes through `reagent2.impl.batching/after-render`. The `re-frame.interop/after-render` alias (`interop.cljs:19`) re-exports this. |
| `as-element` | `[form]` | Convert hiccup form to React element. Delegates to `reagent2.impl.template/as-element`. |
| `props` | `[this]` | Form-3 accessor. Delegates to `reagent2.impl.component/get-props`. |
| `children` | `[this]` | Form-3 accessor. |
| `argv` | `[this]` | Form-3 accessor — Dash8 uses this (rf2-kfpf §2). |
| `state` | `[this]` | Form-3 state accessor. |
| `state-atom` | `[this]` | Form-3 state-atom accessor. |
| `set-state` | `[this m]` | Form-3 state mutator. |
| `replace-state` | `[this m]` | Form-3 state mutator. |
| `force-update` | `[this]`, `[this true]` | Force re-render of `this` component. |
| `reaction` | function `[f]` | Sugar over `make-reaction`; the **function** form (Dash8 uses 25 sites of `r/reaction` per rf2-kfpf §2). The macro form lives in `reagent2.ratom`. |

Internal helpers (private but load-bearing): none unique — this ns is mostly re-exports.

Symbols **not shipped** (per Stage 1 §2.4 + DECISION-7 + Stage 2 §2.7 audit-confirmed): `track`, `track!`, `cursor`, `wrap`, `rswap!`, `partial`, `merge-props`, `unsafe-html`, `adapt-react-class`, `reactify-component`, `create-element`, `next-tick`, `flush` (replaced by `reagent2.dom.client/flush-views!`), `dom-node` (Class B throw — see §10), `class-names`, `is-client`, `set-default-compiler!`, `create-compiler`, `with-let` (Stage 2 §4 binding decision per audit-driven scoping), `render` (deprecated stub — Class B throw, see §10).

### §2.3 `reagent2.ratom` — reactive primitives

Files:
- `src/reagent2/ratom.cljs` — type definitions + public Vars.
- `src/reagent2/ratom.clj` — the `reaction` and `run!` macros (Stage 1 §1.6 marks these SHOULD; Stage 2 §2.6 ships `reaction` as a 5-line indirection over `make-reaction`; `run!` not shipped — zero usage in audits).

Public Vars:

| Symbol | Signature | Notes |
|---|---|---|
| `atom` | `[x]`, `[x & {:keys [meta validator equal?]}]` | `RAtom` constructor. |
| `make-reaction` | `[f]`, `[f & {:keys [auto-run on-set on-dispose]}]` | Reaction constructor. Stage 2 §4 — kept as-is, Reagent's design is correct. |
| `dispose!` | `[r]` | Tear down a Reaction. Calls `IDisposable/dispose!`. |
| `add-on-dispose!` | `[r f]` | Register a disposal callback. |
| `reactive?` | `[]` | True if invoked inside a reactive context. Reads the dynamic `*ratom-context*`. |
| `flush!` | `[]` | Drain the reactive graph synchronously. Distinct from `reagent2.dom.client/flush-views!` which composes the reactive flush with React's commit. |

Public types (see §3 for shape detail):
- `RAtom` — reactive atom.
- `Reaction` — derived value with equality memoisation.

Public protocols:
- `IReactiveAtom` — marker for reactive atoms (`(satisfies? IReactiveAtom x)` is the canonical `ratom?` test).
- `IDisposable` — `dispose!` + `add-on-dispose!`.

Public macros (`reagent2.ratom.clj`):
- `(reaction & body)` — sugar for `(make-reaction (fn [] body))`. Per Stage 2 §2.6, ships as a 5-line indirection. **Required for rf8 wizard/reports_util.cljs:158, 166** (rf2-kfpf §3).

Symbols **not shipped**: `track`, `track!`, `cursor`, `wrap`, `make-wrapper`, `Track`, `RCursor`, `Wrapper` types, `IRunnable` protocol, `run!` macro. Audit-confirmed zero usage across re-com / 10x / Dash8 / rf8.

### §2.3a Note on dropped types and cross-substrate `ratom?`

Stage 1 §1.6 marked `RAtom`, `Reaction`, `Track`, `RCursor`, `Wrapper` as SHOULD because re-com type-checks against them (`re-com/util.cljs :refer [RAtom Reaction RCursor Track Wrapper]`). However the audit (rf2-cgcv) confirmed re-com's references resolve at compile time and re-com only reaches `RAtom` and `Reaction` at runtime (the others are imported but unused in the surface paths re-com exercises). The rewrite ships **`RAtom` and `Reaction` types only**.

`re-frame.interop/ratom?` (`interop.cljs:26-29`) tests `(satisfies? ratom/IReactiveAtom ^js x)` — protocol-based, not class-based. The protocol is exported; both `RAtom` and `Reaction` reify it. Cross-substrate code that does `(instance? RAtom x)` or `(instance? Reaction x)` keeps working. Code that does `(instance? Track x)` or `(instance? RCursor x)` does not — but the audits showed zero such call sites in any of the four production codebases.

If a downstream consumer surfaces a need for `Track` / `RCursor` / `Wrapper` after release, the right answer is to migrate them to the bridge artefact (`day8/reagent-classic`) per the rewrite's scoping commitment, not extend the rewrite.

### §2.4 `reagent2.dom.client` — mount entry

File: `src/reagent2/dom/client.cljs`.

Public Vars:

| Symbol | Signature | Notes |
|---|---|---|
| `create-root` | `[container]`, `[container options]` | Wraps `react-dom-client/createRoot`. |
| `render` | `[root el]` | Render hiccup `el` into a created root. Walks `el` via `reagent2.impl.template/as-element`. |
| `unmount` | `[root]` | Detach a root. Wraps `(.unmount root)`. |
| `hydrate-root` | `[container el]`, `[container el options]` | SSR hydration entry. Wraps `react-dom-client/hydrateRoot`. |
| `flush-views!` | `[]` | The new test-flush primitive (Stage 1 §1.12; Stage 2 top-3 #3). Microtask drain + `react/act` integration. See §4. |

Internal helpers:
- `apply-hiccup` — internal: walks the hiccup tree once and produces a React-element tree. Used by both `render` and `hydrate-root`.

### §2.5 `reagent2.dom.server` — pure-CLJS static-markup serializer

File: `src/reagent2/dom/server.cljs`.

Public Vars:
- `render-to-static-markup` `[hiccup]` — pure-CLJS recursive walker; emits HTML5 static markup. **No `react-dom/server` dependency** (Stage 2 §2.5; the biggest single bundle win for SSR-using apps). See §8 for serializer detail.

`render-to-string` is **not shipped** (DECISION-6). Apps that need hydrate-able SSR use `day8/re-frame2-ssr` via `re-frame.ssr/render-to-string`.

### §2.6 `reagent2.dom` — throw-on-call shims (Class B)

File: `src/reagent2/dom.cljs`.

Public Vars (all throw at first invocation; no React-19-removed APIs are exercised):
- `render` `[& _]` — throws.
- `unmount-component-at-node` `[& _]` — throws.
- `force-update-all` `[]` — throws.

See §10 for migration-message text.

### §2.7 `reagent2.impl.template` — hiccup interpreter (internal)

File: `src/reagent2/impl/template.cljs`.

Internal Vars (no public surface — these are the renderer's load-bearing internals):
- `as-element` `[hiccup]` — top of hiccup → React-element conversion.
- `vec-to-elem` — dispatches `:>`, `:<>`, `:r>`, `:f>`.
- `make-element` — internal `React.createElement` invocation.
- `parse-tag` — `:div.cls#id` parser, regex-cached per Stage 2 §3.1.
- `set-id-class` — merges `:div.foo#bar` parts into the prop map.
- `expand-seq` — sequence children → array (with key warnings in dev).
- `convert-props` — top of prop-map conversion.
- `convert-prop-value` — narrowed per DECISION-2; see §7.
- `cached-prop-name` — kebab→camel cache for prop names (kept; same as stock).

Compile-time helpers: none in this ns. (The defview macro lives in `reagent2.impl.component.clj`.)

### §2.8 `reagent2.impl.component` — class-component plumbing

Files:
- `src/reagent2/impl/component.cljs` — runtime.
- `src/reagent2/impl/component.clj` — `defview` macro (S3-003 optional path).

Internal Vars:
- `*current-component*` — dynamic var; mirrors stock Reagent's `reagent.impl.component/*current-component*`. `reagent2.core/current-component` reads through this.
- `create-class*` `[spec]` — internal create-class. Validates the spec map's keys against the cap (§6); throws on unsupported. Builds a React component class.
- `wrap-render` — internal Form-1/Form-2 detection at runtime (so plain `defn` + `reg-view` keeps working — per S3-003).
- `do-render` — internal lifecycle helper.
- `wrap-funs` — translates the 7 supported lifecycle keys into React lifecycle methods.
- `cancel-cleanup`, `queue-cleanup` — disposal lifecycle plumbing.
- `get-argv`, `get-props`, `get-children` — Form-3 accessors.
- `state-atom` — Form-3 state cell.
- `reagent-class?`, `react-class?` — type predicates (kept; re-com / 10x type-check).

Compile-time macros (`component.clj`):
- `(defview name [args] body)` — optional Form-detection-at-compile-time macro per S3-003. Stage 4 implements the runtime detection first; the macro is an additive optimisation. The current re-frame2 `reg-view` macro (`re-frame.views-macros/reg-view`) is NOT replaced — `defview` is a Reagent-flavoured user surface for apps that don't use re-frame2's reg-view.

### §2.9 `reagent2.impl.batching` — render scheduler

File: `src/reagent2/impl/batching.cljs`.

Internal Vars:
- `dirty-set` — atom holding the set of components queued for re-render.
- `scheduled?` — atom flag; true between microtask-schedule and microtask-fire.
- `queue-render!` `[component]` — enqueue a component for re-render; schedules a microtask if not already scheduled.
- `flush!` `[]` — synchronous drain of the dirty set + cooperation with React commit. The implementation hook for `reagent2.dom.client/flush-views!`.
- `after-render` `[f]` — schedule `f` after the next render. Kept as the public ABI for `reagent2.core/after-render`.
- `do-after-render`, `before-flush` — internal lifecycle hooks.

See §4 for the scheduler's full design.

### §2.10 `reagent2.impl.util` — small internals

File: `src/reagent2/impl/util.cljs`.

Internal Vars:
- `fun-name` — extract a usable name from a fn for error messages and React `displayName` defaulting.
- `*non-reactive*` — dynamic flag suppressing reactive subscription within its body. Internal to `wrap-render` machinery; not exported.
- `dont-camel-case-pattern` — regex distinguishing `data-*` / `aria-*` props from kebab-cased prop names.

The stock `reagent.impl.util` exports `partial`, `class-names`, `merge-props`, `is-client` — none are kept (DECISION-7 Class A).

---

## §3 Reactive primitives (`reagent2.ratom`)

Per Stage 2 §4 ("Keep as-is"): the rewrite preserves Reagent's RAtom and Reaction shapes wherever they are correct. The rationale (rewrite-analysis.md §4.1 floated a "FrameAtom"; Stage 1 overrode that direction): Reagent's reactive kernel is well-designed; the wins live elsewhere (bundle pruning, scheduler, prop conversion).

### §3.1 `RAtom` shape

`(deftype RAtom [^:mutable state ^:mutable meta ^:mutable validator ^:mutable watches])` — the same five-field shape stock Reagent uses. Implements:

- `IAtom` — marker.
- `IDeref` — `(-deref [a] (notify-deref-watcher! a) state)`. The deref-time notification is what wires the atom into the Reactions running in the current reactive context.
- `IReset` — `(-reset! [a new-value] ...)`. Validates, swaps, fires watches via `notify-watches`.
- `ISwap` — built atop `-reset!`.
- `IWatchable` — `add-watch` / `remove-watch`. Watches are `(fn [k r prev nu] ...)`-shaped per CLJS convention.
- `IMeta` / `IWithMeta` — meta passthrough.
- `IPrintWithWriter` — for REPL legibility.
- `IReactiveAtom` — marker protocol; this is what `re-frame.interop/ratom?` tests.
- `IHash` / `IEquiv` — identity-equal (atoms are reference-equal).

The `notify-watches` implementation walks the `watches` map and fires each `(f k a prev nu)`. The watches map carries Reaction subscribers (which observe RAtom changes through the implicit deref-watcher mechanism), explicit `add-watch` registrations from user code (rare), and the cross-substrate cache wiring's watchers.

### §3.2 `Reaction` shape

`(deftype Reaction [^:mutable f ^:mutable state ^:mutable dirty? ^:mutable active? ^:mutable watching ^:mutable watches ^:mutable auto-run ^:mutable on-set ^:mutable on-dispose])` — same nine-field shape stock Reagent uses. Implements all the protocols of RAtom (so a Reaction looks like an atom to consumers) plus:

- `IDisposable` — `dispose!` + `add-on-dispose!`. **Critical**: this is the protocol UIx and Helix adapters reify (`uix.cljs:120-126`, `helix.cljs:120-127`) for cross-substrate cache wiring. The shape of `dispose!` and `add-on-dispose!` is part of the cross-substrate ABI and **must not change**.
- `IRunnable` — NOT implemented. Stage 1 §1.6 marks `IRunnable` CAN-DEPRECATE; DECISION-7 Class A says don't ship.

The compute path (`-deref` on a Reaction): if `dirty?`, run `f`, equality-compare against `state`, conditionally `notify-watches`. Stage 2 §3.2 — equality memoisation kept; this is the right shape.

### §3.3 `IReactiveAtom`, `IDisposable` protocol shapes

```clojure
(defprotocol IReactiveAtom)

(defprotocol IDisposable
  (dispose! [this])
  (add-on-dispose! [this f]))
```

Both protocols MUST match Reagent's signatures byte-for-byte. The UIx adapter's `reify ratom/IDisposable` block at `uix.cljs:120-126` is `:require [reagent.ratom :as ratom]` today; under the rewrite it migrates to `:require [reagent2.ratom :as ratom]` with no signature change. The Helix adapter's reify block at `helix.cljs:120-127` migrates the same way.

### §3.4 Cache wiring contract (cross-substrate)

The sub-cache wiring (per Spec 006 §subscription-cache and the rf2-3yij cross-substrate-cache decision) calls `interop/add-on-dispose!` to register slot-teardown callbacks. The substrate-portable shape is:

1. The cache holds a substrate-side derived-value object (a Reaction under Reagent / reagent2; a UIx-derived-value reify under UIx; a Helix-derived-value reify under Helix).
2. The cache calls `(interop/add-on-dispose! derived (fn [] ...))` at slot-evict time.
3. `interop/add-on-dispose!` is `(ratom/add-on-dispose! a-ratom f)` (interop.cljs:36-37) — it dispatches via the protocol.
4. All three substrates' derived-value objects implement `IDisposable` so the protocol dispatch succeeds without an `instance?` branch in core.

The rewrite preserves this. Stage 4 confirms via a cross-substrate test that calls `add-on-dispose!` on a `reagent2.ratom/Reaction`, a UIx-side derived value, and a Helix-side derived value with no source change in the cache wiring.

### §3.5 The dropped types — `Track`, `RCursor`, `Wrapper`

Per §2.3a — not shipped. Audit-confirmed zero usage. If a future consumer surfaces a real need, the migration path is `day8/reagent-classic`.

---

## §4 Render scheduler + `flush-views!`

Stage 2's third top-3 commitment: a microtask-based render scheduler that composes deterministically with React 19's concurrent rendering. The current Reagent design (`requestAnimationFrame` + three-phase queue) does not compose cleanly with React 18+'s concurrent rendering (rewrite-analysis.md §2.4; Stage 1 §1.12).

### §4.1 Microtask scheduling

`reagent2.impl.batching/queue-render!` enqueues a component into the dirty-set atom. If `scheduled?` is false, it sets `scheduled?` true and schedules a microtask via `js/Promise.resolve().then` (or `js/queueMicrotask` where available). The microtask body:

1. Sets `scheduled?` false.
2. Drains the `dirty-set` into a local seq, clearing the atom.
3. For each dirty component, calls its React `forceUpdate` (or equivalent — under React 19 the dirty notification rides through the component's `useSyncExternalStore` subscription, where present).
4. Runs the `after-render` callback queue.

The microtask boundary is universal across React 19's host platforms (browser, server-component runtimes, React Native via Hermes). No `requestAnimationFrame` fallback is required; React 19 itself does not target environments without microtask support.

### §4.2 React 19 concurrent rendering — `act`, `flushSync`

The scheduler **does not interfere** with React 19's transition / suspense / concurrent rendering. The mechanism: the rewrite's components consume their reactive state via `useSyncExternalStore`-shaped subscriptions to RAtom / Reaction objects (mirroring the UIx adapter's `use-subscribe` pattern at `uix.cljs:225+`). When an RAtom changes, the subscriber fires; React's reconciler then schedules its own re-render through normal React channels. The microtask scheduler is the path for **legacy Reagent-shape components** (Form-1/2/3) that don't use `useSyncExternalStore`; those components call `forceUpdate` from inside the microtask.

For test code, `flush-views!` calls `react/act` (from `react` 19+'s test entry) wrapping the synchronous drain. `act` is the React-19-blessed test primitive that drains React's pending work. The compose:

```clojure
(defn flush-views! []
  (when ^boolean js/goog.DEBUG
    (react/act
      (fn []
        (batching/flush!)               ; synchronous drain of the dirty-set
        (js/Promise.resolve)))))         ; await one microtask turn
```

(Pseudocode — Stage 4 finalises the precise CLJS invocation; the `act` import is `["react" :refer [act]]` under React 19.)

In production builds, `flush-views!` is gated on `js/goog.DEBUG` and DCEs entirely under `:advanced` + `goog.DEBUG=false` — the flush primitive is a test concern.

### §4.3 Differences from Reagent's `r/flush`

| Concern | Reagent `r/flush` | rewrite `flush-views!` |
|---|---|---|
| Schedule mechanism | `requestAnimationFrame` queue | microtask queue |
| React composition | depends on Reagent's `react-flush` cooperation glue (broken under React 18+ concurrent rendering — Stage 1 §1.12) | wraps drain in `react/act`; native React-19 contract |
| Drain scope | drains Reagent's `next-tick` queue only | drains microtask + composes with React commit |
| Production cost | always shipped | dev-only (DCE'd in prod) |
| Test determinism | flaky under React 18.3+ (R-005; ergonomics review §R2 P1) | deterministic by construction |

### §4.4 Render-queue lifecycle: when do components get queued?

Three pathways enqueue a component:

1. **Reactive recompute path**: a Reaction's `compute-fn` runs and produces a new value (`!=` previous), `notify-watches` fires the subscribers, each subscriber's reactive-component-id gets enqueued via `queue-render!`.
2. **Form-2 closure update**: Form-2 components close over RAtoms; the deref inside the inner fn registers a watch; the watch enqueues on RAtom change.
3. **Form-3 lifecycle path**: `:component-did-mount` / `:component-did-update` callbacks may directly call `force-update` (the public ABI) which enqueues the component.

### §4.5 Dedup strategy

`dirty-set` is a CLJS `set?` — adding the same component twice is a no-op. Stable component identity is the React component instance object; the set is keyed on object identity (`equiv?` / hash on identity).

A component that is queued, rendered, then re-queued during the same render cycle (e.g. an `:after-render` callback that mutates an upstream RAtom) gets re-queued for the next microtask turn — the scheduler does not flatten "queued during drain" into the current drain. This matches React 19's transition semantics: in-flight work commits, downstream cascades schedule a new turn.

### §4.6 Test-flush determinism contract

The contract that `flush-views!` provides (the "fast" story per Stage 2 §3.5):

```
After (flush-views!) returns:
  - All currently-dirty components have re-rendered.
  - All Reactions whose dependencies changed have recomputed.
  - All :after-render callbacks queued before flush-views! was called have fired.
  - React's pending work has committed (act() has run to completion).
```

A test that does `(rf/dispatch-sync ...)` followed by `(flush-views!)` followed by an assertion can rely on the assertion seeing the final post-render state. Stage 4 implements a test that exercises this against a Form-1, a Form-2, and a Form-3 component, including a transition boundary.

---

## §5 Component-shape detection (Form-1/2/3, compile-time)

Stage 1 §1.10 + Stage 2 §3.3 + S3-003: ship runtime detection (so plain `defn` + `reg-view` keeps working) and an optional `defview` macro for compile-time dispatch. The runtime detection lives in `reagent2.impl.component/wrap-render`; the macro lives in `reagent2.impl.component.clj`.

### §5.1 Runtime detection (`wrap-render`)

Per Stage 2 §3.3 — Reagent's runtime detection costs ~3-5 ns per render; the savings of moving to compile-time are negligible. The runtime path stays:

```
(defn- wrap-render [c]
  (let [out ((.-cljsRender c))]
    (cond
      (vector? out)  out                   ; Form-1
      (fn? out)      (do (set! (.-cljsLegacyRender c) out)
                         (out (get-argv c)))   ; Form-2 cache + recall
      (seq? out)     (vec out)              ; legacy seq-as-fragment
      :else          out)))                 ; primitive
```

This is essentially the stock-Reagent shape (`reagent.impl.component:54-73`). Under the rewrite the detection sits inside the create-class-derived React component class; `reg-view*`'s wrapper continues to attach `:contextType frame-context` (per views.cljs:312-367).

### §5.2 Compile-time `defview` macro (optional)

Per S3-003: an optional `defview` macro that compile-time-classifies the user form. **Stage 4 ships this AFTER the runtime path** — Stage 4-A is the runtime; Stage 4-B layers on the macro.

Shape (sketch — Stage 4 finalises):

```clojure
(defmacro defview
  [sym arglist & body]
  (let [form (last body)]
    (cond
      ;; Form-3: (create-class spec)
      (and (seq? form) (= 'create-class (first form)))
      `(def ~sym (reagent2.core/create-class ~(second form)))

      ;; Form-2: (fn [args] hiccup)
      (and (seq? form) (= 'fn (first form)))
      `(def ~sym (form-2-wrapper (fn ~arglist ~@body)))

      ;; Form-1: anything else, treat as render fn
      :else
      `(def ~sym (form-1-wrapper (fn ~arglist ~@body))))))
```

The macro is **purely additive**: existing `(defn my-view [args] [:div ...])` users do nothing. The macro's value is for users who want compile-time dispatch; the runtime detection covers correctness for everyone else.

**Note on re-frame2's `reg-view`**: `reg-view` (`re-frame.views-macros/reg-view` / `re-frame.core/reg-view`) is the canonical re-frame2 view-registration macro and is NOT replaced by `defview`. `reg-view` registers an id in the `:view` registrar and attaches `:contextType frame-context`; `defview` is a Reagent-flavoured user surface for code that doesn't go through re-frame2's registrar. They coexist.

### §5.3 Form-3 detection

Form-3 is **explicit**: `(reagent2.core/create-class spec-map)`. The macro doesn't need to detect it because it's a function call at the user's site, not a shape inferred from the body. The runtime path validates the spec at `create-class` call time — see §6.

### §5.4 Source-coord meta stamping through each Form path

Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1), the wrapper emitted by `re-frame.views/reg-view*` (views.cljs:312-367) merges `:data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` onto the rendered root DOM element. The current implementation walks the hiccup tree post-render via `inject-source-coord-attr` (views.cljs:270-308). Stage 2 §3.6 names this as the biggest single dev-mode runtime win — moving the stamping into the renderer's "emit DOM element for a registered view's root" path eliminates the post-render walk.

The rewrite folds the stamping into `reagent2.impl.template/as-element` via a per-render context that knows "this is the root element of view-id <ns>/<sym>". The mechanism:

1. `re-frame.views/reg-view*` continues to wrap user fns and bind `*render-key*`.
2. The wrapper additionally binds a new dynamic var `reagent2.impl.template/*source-coord*` to the formatted attr value.
3. `reagent2.impl.template/as-element` reads `*source-coord*` once at the top of the call. If non-nil, the **first** hiccup vector with a DOM-tag head gets the attr merged in inline (no post-render walk). After that, `*source-coord*` is rebound to nil so nested elements aren't stamped.
4. For Form-2 (render-fn returns a fn): the wrapper-emit-fn path that recurses on the inner output (views.cljs:284-287) is replaced — the outer wrapper rebinds `*source-coord*` for each call to the inner fn.

The inject-source-coord-attr helper in views.cljs (lines 270-308) becomes vestigial under the rewrite; Stage 4 deletes it. Per §11.

Production elision: `*source-coord*` is bound only when `interop/debug-enabled?` is true (the existing gate at views.cljs:343); under `:advanced` + `goog.DEBUG=false`, the entire bind branch DCEs and the stamping check at the top of `as-element` reduces to `(when nil ...)`.

---

## §6 Form-3 (`create-class`) implementation

Per Stage 1 DECISION-3 + rf2-cgcv §4 + rf2-kfpf §6: 7-key cap.

### §6.1 The cap

Exactly these keys are accepted in a `create-class` spec map:

```
:component-did-mount
:component-will-unmount
:component-did-update
:reagent-render
:display-name
:get-snapshot-before-update
:component-did-catch
```

Any other key throws `:rf.error/create-class-key-unsupported` at `create-class` call time (registration time, NOT render time — fail fast).

### §6.2 Validation throw shape

```clojure
(defn- validate-spec! [spec]
  (let [supported #{:component-did-mount :component-will-unmount
                    :component-did-update :reagent-render
                    :display-name :get-snapshot-before-update
                    :component-did-catch}
        unsupported (remove supported (keys spec))]
    (when (seq unsupported)
      (throw
        (ex-info ":rf.error/create-class-key-unsupported"
          {:type            :rf.error/create-class-key-unsupported
           :keys            (vec unsupported)
           :supported-keys  supported
           :reason          (str "create-class accepts a 7-key cap. "
                                 "Unsupported: " (pr-str unsupported) ". "
                                 "Migrate to the supported keys, restructure "
                                 "via :on-create / :on-destroy events, or "
                                 "switch to day8/reagent-classic.")
           :recovery        :no-recovery})))
    spec))
```

The throw fires at registration time so users see it at startup, not on first render of the offending component.

### §6.3 React-class wrapper

`create-class*` builds a React component class via the React-19 ES-class shape:

```javascript
class ReagentClass extends React.Component {
  constructor(props) { super(props); /* state init from spec */ }
  render() { /* delegates to wrap-render */ }
  /* lifecycle methods plumbed from the spec map */
}
```

The CLJS implementation uses `goog.object/extend` + `js/Object.assign` to attach methods to the prototype, mirroring stock-Reagent's `reagent.impl.component/create-class` shape (Stage 2 §2.3 — kept).

### §6.4 Lifecycle key → React lifecycle method mapping

| Cap key | React lifecycle method | Notes |
|---|---|---|
| `:component-did-mount` | `componentDidMount` | Called once after first mount commit. |
| `:component-will-unmount` | `componentWillUnmount` | Called once before unmount; canonical disposal seam. |
| `:component-did-update` | `componentDidUpdate(prevProps, prevState, snapshot)` | Receives the snapshot from `:get-snapshot-before-update`. |
| `:reagent-render` | `render` | The render method, wrapped by `wrap-render` for Form-1/2 detection per §5.1. |
| `:display-name` | `displayName` (static class field) | Compile-time string only; zero runtime cost. |
| `:get-snapshot-before-update` | `getSnapshotBeforeUpdate(prevProps, prevState)` | Returns a snapshot value passed as 3rd arg to `componentDidUpdate`. |
| `:component-did-catch` | `componentDidCatch(error, info)` | Error-boundary logging. The full error-boundary contract (`getDerivedStateFromError` + `componentDidCatch`) is React-19-blessed; the rewrite ships only the logging half because all four audited apps use logging-only error boundaries (rf2-kfpf §6). Apps that want stateful error boundaries pair `:component-did-catch` with a `(reagent2.core/atom)` cell flipped from inside the callback. |

### §6.5 `:component-did-catch` integration

This is THE one cap key that re-frame2's `:on-create`/`:on-destroy` events cannot replace — there is no Form-1 alternative for an error boundary, because React's error-boundary contract requires a class component (function components do NOT support `componentDidCatch`). Test coverage critical (per the bead description).

Stage 4 ships these tests:
1. `error-boundary-catches-render-error`: a child component throws in render; the boundary's `:component-did-catch` fires with `(error, info)`; the boundary re-renders a fallback.
2. `error-boundary-logs-and-rethrows`: assert the error info reaches `console.error` (or a test spy) when `:component-did-catch` re-throws (the rewrite's behaviour matches React 19's: re-throw bubbles to the next boundary).
3. `nested-error-boundaries-isolate`: an inner boundary catches; the outer boundary does not fire.

The error-boundary path is React-19-stable; no special-casing needed beyond plumbing `:component-did-catch` to `componentDidCatch`.

### §6.6 `:get-snapshot-before-update` pairing

This is the React-blessed lifecycle that pairs with `:component-did-update`. The contract:

1. Just before React commits a re-render, `getSnapshotBeforeUpdate(prevProps, prevState)` runs against the in-flight DOM (the previous render is still attached).
2. The return value (any JS value — typically a measurement, e.g. scroll position) is captured.
3. After commit, `componentDidUpdate(prevProps, prevState, snapshot)` runs with the captured snapshot as the third arg.

Stage 4 ships test coverage that mirrors the 10x `code` component's scroll-restoration pattern (panels/event/views.cljs:54-64 per rf2-cgcv §3): capture `scrollTop` in `:get-snapshot-before-update`, restore it in `:component-did-update`. Asserts the snapshot value flows through correctly.

The implementation: the React class's `componentDidUpdate` method delegates to a CLJS impl that destructures `[prev-props prev-state snapshot]` and passes them to the user's `:component-did-update` fn:

```clojure
(fn [this prev-props prev-state]
  (let [snapshot (j/get this :__rf-snapshot)
        user-fn  (:component-did-update spec)]
    (when user-fn (user-fn this prev-props prev-state snapshot))))
```

(`j/get` is `goog.object/get`-shaped; Stage 4 picks the exact accessor.)

---

## §7 Hiccup translation (`reagent2.dom.client/render` path)

Per Stage 1 §1.11 + Stage 2 §2.2 + DECISION-2.

### §7.1 Pipeline

```
hiccup form
    │
    ▼
as-element
    │
    ├── (string? f)              → text node
    ├── (number? f)              → text node
    ├── (vector? f)              → vec-to-elem
    ├── (seq? f)                 → array of elements (with key warnings)
    └── (nil? f)                 → React null
```

`vec-to-elem` dispatches on the head:

| Head | Meaning | Dispatch |
|---|---|---|
| keyword DOM tag (`:div`, `:input`, …) | DOM element | `parse-tag` for `:div.cls#id` shorthand; merge into props; `make-element` |
| `:>` | React component interop | `(apply React.createElement (second hiccup) (convert-props (third hiccup)) children)` |
| `:<>` | React Fragment | `(apply React.createElement React.Fragment ...)` |
| `:r>` | raw `React.createElement` | identity passthrough; `make-element` |
| `:f>` | function-component dispatch | `(apply React.createElement (fn-to-class (second hiccup)) ...)` |
| user CLJS fn | reagent component | wrapped in a class via `fn-to-class` |
| reagent class | reagent component | passed through |

The dispatch is small and concrete. Stage 4 references `reagent.impl.template:vec-to-elem` for the canonical shape and re-implements without the compiler-customisation indirection (Stage 1 §2.4 + Stage 2 §2.2).

### §7.2 Narrowed `convert-prop-value`

Per DECISION-2 + S3-002: stringify named values only for documented HTML-attribute prop names. The static set:

```clojure
(def html-attr-names #{:class :id :role})
;; Plus prefix-matched: :data-* / :aria-*
```

Implementation:

```clojure
(defn- html-attr-name? [k]
  (or (contains? html-attr-names k)
      (let [n (name k)]
        (or (str/starts-with? n "data-")
            (str/starts-with? n "aria-")))))

(defn convert-prop-value [k v]
  (cond
    (named? v) (if (html-attr-name? k)
                 (name v)                ; stringify for HTML attrs
                 (do                     ; non-HTML: pass through; warn in dev
                   (when ^boolean js/goog.DEBUG
                     (warn-once-keyword-prop! k v))
                   v))
    (and (map? v) (= :style k)) (clj->js v)
    :else v))
```

The dev-mode warn-once cache is a `defonce`-d atom keyed by `[k (name v)]` to avoid spamming. Stage 4 implements the cache + the warning text; the warning includes the prop key, the keyword value, and the migration: "if you intended a string, call (name v) at the call site; if you intended a keyword as a React-context value or a custom prop, the value is now passed through unchanged."

### §7.3 Tag parsing — `:div.cls#id` shorthand

Same regex Reagent has used for years (`re-tag = #"[#.]?[^#.]+"` matched repeatedly). Stage 4 lifts the regex byte-for-byte from `reagent.impl.template:parse-tag`. Per Stage 2 §3.1 — same caching, same regex; no win to be had at this layer.

`set-id-class` merges the parsed `id` and `class` into the prop map. If the user already supplied `:id`, the parsed id is dropped (user wins); if the user supplied `:class`, the parsed class is **prepended** (matching stock Reagent's behaviour: `[:div.foo {:class "bar"}]` → `class="foo bar"`).

### §7.4 Sequence-as-children handling + key warnings

```clojure
(defn- expand-seq [seq-form]
  (let [arr (into-array seq-form)]
    (when ^boolean js/goog.DEBUG
      (doseq [el seq-form]
        (when (and (vector? el) (not (some-> el meta :key)))
          (warn-missing-key! el))))
    arr))
```

The warning text mirrors React's "Each child in a list should have a unique 'key' prop" — not as a re-throw of React's, but as a Reagent-side proactive warning. Stage 4 implements warn-once keyed on the surrounding component's id.

### §7.5 Children flattening contract

Hiccup vectors contain children at positions `(rest hiccup)` (or `(drop 2 hiccup)` if the second element is a prop map). Stage 4's `vec-to-elem` flattens children one level:

- Nested vectors are individual children (passed through `as-element` recursively).
- Sequences (`(map ...)`, `(for ...)`) are expanded via `expand-seq`.
- Strings / numbers become text nodes.
- `nil` children are dropped.

### §7.6 React 19 strictness

Per Stage 1 §1.12, the rewrite emits React-19-clean elements:

- **`React.Children.only`**: not invoked from the rewrite; user-side use is untouched.
- **Ref-as-prop**: refs continue to be attached as JS-shape `ref` (no string refs; only callback refs and `useRef`-shaped refs). Reagent's `:ref` keyword maps to `ref` per the existing convention.
- **Removed `defaultProps` for function components**: the rewrite's hiccup compiler does not emit `defaultProps` on function components. Class components (Form-3) keep `defaultProps` if the user sets them via the spec — but the cap doesn't include a `:default-props` key, so this is a non-issue under the cap.

---

## §8 `render-to-static-markup` (`reagent2.dom.server`)

Per Stage 1 DECISION-6 + Stage 2 §2.5 + S3-005: pure-CLJS recursive walker; ~150-200 LoC; **no `react-dom/server` dependency**.

### §8.1 Core loop

```clojure
(declare emit-element)

(defn render-to-static-markup [hiccup]
  (let [sb (StringBuilder.)]
    (emit-element sb hiccup)
    (.toString sb)))

(defn- emit-element [sb x]
  (cond
    (nil? x)               nil
    (string? x)            (.append sb (escape-text x))
    (number? x)            (.append sb (str x))
    (vector? x)            (emit-vector sb x)
    (seq? x)               (doseq [c x] (emit-element sb c))
    :else                  (throw (ex-info ":rf.error/static-markup-bad-element"
                                   {:got x}))))
```

`emit-vector` dispatches on the head. For DOM-tag heads, it emits `<tag attrs>` + children + `</tag>` (or self-closes for void elements per §8.4). For `:<>` (Fragment), it emits children only. For `:>` and `:r>` and `:f>` (React-component interop) — the static-markup path does NOT walk into React components; it emits `<!--reagent-react-component-->` placeholder comments. This matches `react-dom/server`'s behaviour for the static-markup case — non-static content is opaque.

(Alternative approach: invoke a function head with no props/children and recurse on the result. Reagent's `render-to-static-markup` in stock takes the function-call-the-fn path. **Stage 4 picks the function-call path** to match stock's behaviour; this preserves Dash8/rf8 HTML-export compatibility.)

### §8.2 HTML escaping for text content

```clojure
(defn- escape-text [s]
  (-> s
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")))
```

Lift the existing implementation from `re-frame.ssr/escape-html` (`ssr.cljc:50-57`) — the SSR seam already has battle-tested escaping. Reuse, don't re-implement. (This is the same shape `react-dom/server` uses; parity test in §12 confirms.)

### §8.3 Attribute serialisation

```clojure
(defn- emit-attrs [sb attrs]
  (when (seq attrs)
    (doseq [[k v] attrs]
      (cond
        (= :style k)        (emit-style-attr sb v)
        (true? v)           (do (.append sb " ") (.append sb (name k)))
        (false? v)          nil           ; boolean false → omit
        (nil? v)            nil           ; nil → omit
        :else               (let [s (-> v
                                        (cond-> (named? v) name)  ; narrowed; HTML-attr names only here
                                        (str)
                                        escape-attr)]
                              (.append sb " ")
                              (.append sb (name k))
                              (.append sb "=\"")
                              (.append sb s)
                              (.append sb "\""))))))
```

The same narrowed `convert-prop-value` rules apply (per S3-005): the static-markup path stringifies keyword values only for HTML-attribute names. (Inside an HTML attribute serialisation, every prop name IS by definition an HTML attribute name — this layer doesn't see React-component props, only DOM-element attrs.)

### §8.4 Boolean attributes + void-tag handling

Boolean attributes (`disabled`, `checked`, `selected`, `readOnly`, `required`, etc.): if `true`, emit just the name (HTML5 short form). If `false`, omit entirely. The list of recognised boolean attributes is a static set:

```clojure
(def boolean-attrs
  #{:disabled :checked :selected :read-only :required :auto-focus
    :auto-play :controls :default :hidden :loop :multiple :muted :open
    :reversed})
```

Void elements (self-closing in HTML5):

```clojure
(def void-tags
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})
```

Lift both sets from `re-frame.ssr/void-elements` (`ssr.cljc:90+`). Reuse the same definitions to guarantee parity with the SSR seam's HTML output.

### §8.5 Edge cases

- **Nil children**: dropped silently (emit-element nil branch).
- **Empty seqs**: emit nothing.
- **Fragments (`:<>`)**: emit children with no surrounding markup.
- **`:dangerouslySetInnerHTML`**: emit the `__html` value as raw, unescaped text inside the parent element. (Match React's contract.)
- **Non-string text content (numbers, keywords)**: numbers `(str x)`; keywords `(name x)`; symbols `(name x)`; everything else `(pr-str x)`.

### §8.6 LoC budget

Stage 2 §2.5 estimated 150-200 LoC. The mid-point (~175 LoC) is the budget Stage 4 targets. Anything significantly over warrants a code-review pass before merge.

### §8.7 Parity test (R-004 mitigation)

A test-time-only build pulls in `react-dom/server` and runs both implementations against a corpus of representative shapes:

- A full re-com `dropdown` rendering.
- An error boundary.
- Hiccup with `:dangerouslySetInnerHTML`.
- Self-closing tags + void elements.
- Boolean attrs.
- Empty fragments.
- Nested fragments.
- Sequence children with and without keys.
- Mixed-type children (string + number + vector).

The test diffs the outputs and asserts byte-for-byte equality, with explicit known-difference allow-listing for cases like attribute ordering that aren't part of the contract. Stage 4 documents any intentional differences in MIGRATION.md.

---

## §9 Adapter Var integration

### §9.1 The adapter map (UNCHANGED public path)

The rewrite's `re-frame.adapter.reagent/adapter` Var emits the same 9-key map shape `re-frame.substrate.adapter` consumes (`adapter.cljc:67-120`):

```clojure
(def adapter
  {:make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})
```

The signatures match the bridge's signatures byte-for-byte. Apps doing `(rf/init! reagent/adapter)` see no change.

### §9.2 Late-bind hook for SSR

The bridge's `(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)` line at `reagent.cljs:122` is preserved verbatim in the rewrite. The SSR seam at `re-frame.ssr` (`ssr.cljc:360`) consults this hook at load time. The seam continues to work without source change.

### §9.3 Cache wiring

Per §3.4: `interop/add-on-dispose!` dispatches via `IDisposable`. The rewrite's `Reaction` reifies `IDisposable` with the same shape Reagent's does. The UIx and Helix adapter cache wiring (`uix.cljs:120-126` and `helix.cljs:120-127`) keeps working without source change.

### §9.4 Source-coord stamping

Per §5.4: the source-coord injection moves from a post-render hiccup walk (views.cljs:270-308) into `reagent2.impl.template/as-element` via a `*source-coord*` dynamic var. The wrapper at `re-frame.views/reg-view*` (views.cljs:312-367) is updated to bind that dynamic var instead of (or in addition to) `*render-key*`. Stage 4 deletes the `inject-source-coord-attr` helper.

Production elision continues — `*source-coord*` is bound only when `interop/debug-enabled?`; under `:advanced` + `goog.DEBUG=false` the entire branch DCEs.

### §9.5 Trace event integration

Per Spec 009 §Performance instrumentation: every render of a registered view brackets the user render-fn in performance marks + emits a `:view/render` trace. The current implementation is in `re-frame.views/reg-view*` (views.cljs:346-364). Under the rewrite, the wrapper continues to live in `re-frame.views`; the only change is that source-coord stamping moves into the renderer per §9.4.

The trace late-bind hook (`:trace/emit!`) is unchanged. The render-key binding (`*render-key*`) is unchanged.

### §9.6 The plain-fn-under-non-default-frame warning

The detection at `re-frame.views/maybe-warn-plain-fn-under-non-default-frame!` (views.cljs:454-531) reads `(r/current-component)` and inspects `(.-context cmp)`. Under the rewrite this becomes `(reagent2.core/current-component)` — same shape, same field access. **The narrowness contract is preserved**: reg-view-wrapped components carry `:contextType frame-context` (read into `(.-context cmp)`) so they get the surrounding frame; plain Reagent fns lack the wiring and route to `:rf/default`, triggering the warning.

This is the one place the rewrite is forced to keep Reagent's class-component context-read shape (`(.-context cmp)`) — UIx / Helix function components route through `_currentValue` per `re-frame.adapter.context/function-component-current-frame`. The rewrite's class-component-shape preserves the existing detection path.

---

## §10 Throw-on-call shims (Class B, React-19-removed)

Per Stage 1 §2.3a + DECISION-7 Class B. Five symbols ship as throw-on-call. Each emits a migration-message string at first invocation; static-analysis-friendly callers just delete the import.

### §10.1 Migration messages

The migration-message strings are fixed and visible in source. The doc URL is `https://github.com/day8/re-frame2/blob/main/MIGRATION.md` (Stage 4 confirms the URL is correct at branch-cut).

| Shim | Migration message |
|---|---|
| `reagent2.dom/render` | `"reagent.dom/render is removed under React 19. Use reagent2.dom.client/{create-root, render} instead. See https://github.com/day8/re-frame2/blob/main/MIGRATION.md#legacy-mount-path."` |
| `reagent2.dom/unmount-component-at-node` | `"reagent.dom/unmount-component-at-node is removed under React 19. Use reagent2.dom.client/unmount instead. See https://github.com/day8/re-frame2/blob/main/MIGRATION.md#legacy-mount-path."` |
| `reagent2.dom/force-update-all` | `"reagent.dom/force-update-all is removed (it iterated React 17 internals). If you have a legitimate use case, file an issue at https://github.com/day8/re-frame2/issues."` |
| `reagent2.core/dom-node` | `"reagent.core/dom-node depended on findDOMNode which is removed in React 19. Use a :ref callback or React.useRef instead. See https://github.com/day8/re-frame2/blob/main/MIGRATION.md#dom-node-removal."` |
| `reagent2.core/render` | `"reagent.core/render is removed under React 19. Use reagent2.dom.client/{create-root, render} instead. See https://github.com/day8/re-frame2/blob/main/MIGRATION.md#legacy-mount-path."` |

### §10.2 Implementation pattern

Each shim is a `defn` whose body throws an `ex-info` with the migration message. ~10 LoC total across the five symbols (Stage 2 §2.4 estimate). Example:

```clojure
(defn render
  "REMOVED under React 19. See migration message."
  [& _]
  (throw
    (ex-info "reagent.dom/render is removed under React 19. Use reagent2.dom.client/{create-root, render} instead. See https://github.com/day8/re-frame2/blob/main/MIGRATION.md#legacy-mount-path."
      {:type :rf.error/react-19-removed-surface
       :symbol 'reagent2.dom/render
       :recovery :no-recovery})))
```

The `:type` keyword `:rf.error/react-19-removed-surface` is shared across all five shims so a try/catch-as-migration-helper can match all of them with one `(= :rf.error/react-19-removed-surface (:type (ex-data e)))` check.

### §10.3 Static-analysis friendliness

Because the throw is the only thing in the body, `:advanced` Closure compilation can identify these as never-returning fns. Apps that import the symbol but never call it (e.g. an old `(:require [reagent2.dom :as rdom])` where `rdom` is unused) pay zero runtime cost — the shims DCE if no call site reaches them.

---

## §11 Deletions from re-frame2 enabled by the rewrite

The rewrite's narrowed `convert-prop-value` (§7.2 + DECISION-2) and folded source-coord stamping (§5.4 + §9.4) make several existing re-frame2 internals vestigial. Stage 4 deletes them as part of the rewrite-adoption commit.

### §11.1 `re-frame.adapter.context/coerce-context-value` — RETAINED (was: delete)

File: `implementation/core/src/re_frame/adapter/context.cljs`. The shared coercion that un-stringifies `convert-prop-value`'s keyword-stringification path.

**Retraction**: An earlier revision of this section instructed deleting `coerce-context-value` on the grounds that DECISION-2's narrowed `convert-prop-value` removes the over-stringification under the slim adapter. The audit during Stage 4-F surfaced that the function is **still load-bearing for the classic-Reagent adapter path** (`implementation/adapters/reagent/`), which uses stock `reagent.impl.template/convert-prop-value` and therefore still stringifies keyword props. Both adapters share the same `re-frame.views/current-frame` resolver via the `:adapter/current-frame` late-bind hook, so deleting the coercion would silently break `(rf/subscribe ...)` and `(rf/dispatch ...)` under any `[:> Provider {:value :tenant}]` raw-hiccup mount on the classic adapter.

The canonical user-facing mount (`rf/frame-provider`) now goes through `re-frame.adapter.context/provider-element`, which builds the Provider via `React.createElement` directly and bypasses Reagent's prop conversion altogether — so the user-facing path preserves namespaced frame-ids on every adapter. `coerce-context-value` remains as **defensive coverage for raw-hiccup Provider mounts** authored as `[:> (.-Provider frame-context) {:value :foo}]` directly, which still hit stock Reagent's prop conversion under the classic bridge.

**Stage 4 actions** (revised):
- **Keep** `coerce-context-value` in `re-frame.adapter.context`.
- Keep the keyword-and-string coercion shape: `keyword? v → v`; non-empty `string? v → (keyword v)`; else nil.
- Keep the call sites at `re-frame.views/current-frame` and `re-frame.adapter.context/function-component-current-frame` unchanged — both still consult the helper.
- Stage 4-F item #2 (delete `coerce-context-value`) is **withdrawn**. The forced-defensive comments referenced in §11.2 are tightened to call out the helper's revised rationale (defensive cover for raw-hiccup mounts), not removed.

### §11.2 Defensive comments referencing rf2-d4sf

Multiple places reference Reagent's stringification as the rationale for defensive coercion:
- `views.cljs` / `views/provider.cljs` (current-frame docstring).
- `context.cljs` (the coerce-context-value section header).

Stage 4 tightens these comments to reflect the revised state: under the slim adapter the stringification class is structurally absent (per DECISION-2); under the classic adapter it still applies to raw `[:> Provider {:value :foo}]` hiccup mounts. The canonical user-facing surface (`rf/frame-provider`) bypasses prop conversion via `provider-element` so the namespace of namespaced frame-ids survives the React-context round trip; the helper is the safety net for raw-hiccup paths.

### §11.3 The `inject-source-coord-attr` walker

File: `implementation/core/src/re_frame/views.cljs:270-308`. Per §5.4 + §9.4, source-coord stamping moves into the renderer. The walker becomes unreachable.

**Stage 4 actions**:
- Delete `inject-source-coord-attr`.
- Delete `dom-tag?` helper (only used by `inject-source-coord-attr`).
- Delete `warn-non-dom-root!` and the `warned-non-dom-roots` defonce — the renderer-side stamping no longer encounters non-DOM roots in a separate pass; the warning logic moves into `reagent2.impl.template/as-element` if Stage 4 chooses to keep the warning at all (the audit didn't show evidence of this warning firing in production).
- Update `reg-view*` (views.cljs:312-367) to bind `reagent2.impl.template/*source-coord*` instead of computing the coord-attr inline.

### §11.4 The thin-bridge adapter

File: `implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs`. After the rewrite ships and `day8/reagent-classic` is cut as the bridge artefact, the on-disk thin bridge migrates to a new path (`implementation/adapters/reagent-classic/`?) **OR** is removed from the re-frame2 monorepo entirely and lives on its own.

**Stage 4 explicitly does NOT delete the bridge.** It coexists. The decision of when (and whether) to retire the bridge is a post-1.0 concern. The two artefacts ship in parallel; consumers pick.

---

## §12 Test strategy

Per the bead description and Stage 2 §5 risk register R-001..R-007.

### §12.1 Unit tests (per-namespace)

| Namespace | Test file | Coverage |
|---|---|---|
| `reagent2.core` | `core_test.cljs` | All 14 public Vars (re-export integrity). |
| `reagent2.ratom` | `ratom_test.cljs` | RAtom + Reaction lifecycle; protocol satisfaction; equality memoisation; `IDisposable` reify; cross-substrate cache-wiring contract. |
| `reagent2.dom.client` | `dom_client_test.cljs` | create-root / render / unmount / hydrate-root happy-paths; flush-views! determinism contract per §4.6; React-19 `act` cooperation. |
| `reagent2.dom.server` | `dom_server_test.cljs` + `parity_test.cljs` | render-to-static-markup output for representative corpus; parity against `react-dom/server` per §8.7. |
| `reagent2.dom` (throw shims) | `dom_throw_test.cljs` | each of the 5 throw-on-call symbols throws an `ex-info` of `:type :rf.error/react-19-removed-surface` with the right migration message. |
| `reagent2.impl.template` | `impl/template_test.cljs` | hiccup → React-element shapes; narrowed convert-prop-value (R-001); kebab-camel cache; tag parsing; sequence-children handling; `:>` / `:<>` / `:r>` / `:f>` interop. |
| `reagent2.impl.component` | `impl/component_test.cljs` | create-class 7-key cap (R-002); throw-on-unsupported-key per banned key; lifecycle method mapping per §6.4; `:component-did-catch` error-boundary integration per §6.5; `:get-snapshot-before-update` pairing per §6.6. |
| `reagent2.impl.batching` | `impl/batching_test.cljs` | microtask scheduling; dirty-set dedup; flush! synchronous drain; after-render queue; React 19 transition cooperation (R-005). |

### §12.2 Integration tests

| Test | Target |
|---|---|
| `examples/counter-slim-and-fast` (**delivered, rf2-5lbx**) | Same counter logic as `examples/counter`; mounted on the rewrite. Playwright spec at `examples/reagent/counter_slim_and_fast/counter_slim_and_fast.spec.cjs` is authored for identical user-visible behaviour (initial-render value 5, +/- click round-trips); presently `skip`ed at runtime pending rf2-s36l (the re-frame.interop seam still hardcodes stock `reagent.ratom`, so `(rf/init! slim-adapter/adapter)` fails the first subscribe with an IDisposable protocol miss). The :advanced bundle compiles cleanly, the bundle-comparison contract enforces S3-008 + S3-005, and the spec module is shipped intact so dropping `skip` re-enables the smoke once rf2-s36l lands. The example's `run` fn invokes `reagent2.dom.server/render-to-static-markup` at boot so the SSR path is compiled into the :advanced bundle — making the react-dom/server-absence assertion in §12.3 non-vacuous. |
| Cross-substrate ratom test | Constructs a `reagent2.ratom/Reaction`, a UIx-side derived value (uix.cljs:75-127), and a Helix-side derived value; calls `add-on-dispose!` on each via `re-frame.interop/add-on-dispose!`; asserts the protocol dispatch succeeds without an `instance?` branch. |
| re-com smoke test (Stage 4-B) | Pin `re-com 3.x` (post the React-19 readiness work per rf2-cgcv §7) and mount one of each `create-class` site (debug.cljs's `validate-args-error`, popover.cljs's `popover-border`, dropdown.cljs's `body-wrapper`, v-table.cljs's `v-table`). Asserts no key-cap violations. |
| Dash8/rf8 error-boundary smoke test | Mount the shared `reagent_error_boundary.cljs` shape (rf2-kfpf §4); throw in a child; assert `:component-did-catch` fires with the right error info. |

### §12.3 Bundle-comparison grep contract

**Delivered (rf2-5lbx)** — see §1.8. The contract is enforced by `implementation/scripts/check-counter-slim-and-fast.cjs` (npm script `test:bundle-comparison`, CI job `cljs-bundle-comparison` in `.github/workflows/test.yml`). Three assertions:

1. **Methodology sanity** — every stock-Reagent sentinel from the chosen set (`Compiler.parse-tag`, `Compiler.as-element`, `Compiler.get-id`, `Compiler.make-element`, `ReagentInput`, `cljsRatom`, `cljsLegacyRender`) appears at least once in the stock `examples/counter` bundle. If a future stock-Reagent revision DCEs them out of the stock bundle too, the grep has lost signal and the script catches the methodology break — re-derive a fresh sentinel set.
2. **S3-008 stock-Reagent isolation** — every stock-Reagent sentinel above hits zero times in the `examples/counter-slim-and-fast` bundle.
3. **S3-005 pure-CLJS SSR** — the strings `react-dom/server`, `renderToStaticMarkup`, `renderToString`, `renderToPipeableStream`, `renderToReadableStream` all hit zero times in the `examples/counter-slim-and-fast` bundle (even though the example exercises `reagent2.dom.server/render-to-static-markup` at boot — proving the slim SSR path is pure-CLJS).

The complementary `counter` (classic): no `reagent2.*` symbol direction is not yet enforced; the in-tree shadow-cljs build coexists both adapter trees on the same classpath, so a `reagent2.*`-absence assertion on the classic bundle requires either a per-build classpath-pruning hook or moving the slim adapter to a separate shadow-cljs config. Tracked as a follow-up; rf2-5lbx's deliverable is the S3-008 contract above.

### §12.4 Lockstep version-pin verification

Per §1.5 — Stage 4 confirms `verify-version-lockstep.sh` recognises the new artefact and the new `:version "../../../VERSION"` and `:local/root "../../core"` strings appear in `implementation/adapters/reagent-slim/deps.edn`.

### §12.5 Risk register → test-coverage map (Stage 2 §5)

| Risk | Test |
|---|---|
| **R-001** Narrowed `convert-prop-value` may break apps relying on silent stringification | `template_test.cljs` exercises every `html-attr-name?` branch; tests assert the dev-mode `console.warn` fires once per `[k name-of-v]` pair. |
| **R-002** 7-key Form-3 cap may break niche consumers | `component_test.cljs` constructs `create-class` with each of {`:component-will-mount`, `:UNSAFE_componentWillMount`, `:component-will-receive-props`, `:UNSAFE_componentWillReceiveProps`, `:component-will-update`, `:UNSAFE_componentWillUpdate`, `:should-component-update`, `:get-derived-state-from-props`, `:get-initial-state`} and asserts each throws `:rf.error/create-class-key-unsupported` with the unsupported key in `:keys`. |
| **R-003** Compile-time Form-detection macro changes user-facing API | The runtime path is the canonical (mandatory) implementation; the `defview` macro is optional. Tests assert plain `defn` + `reg-view` works with all three Form shapes. |
| **R-004** Pure-CLJS `render-to-static-markup` differs from `react-dom/server` | `parity_test.cljs` per §8.7 — corpus-based diff. Known-difference allow-list documented in MIGRATION.md. |
| **R-005** Microtask scheduler interacts unexpectedly with React 19 transitions | `dom_client_test.cljs` exercises `useTransition` boundaries; `flush-views!` drains React's pending work without race. |
| **R-006** 10x v1 monkey-patches break | NOT tested in this artefact — documented as a known breakage in MIGRATION.md. 10x v1 doesn't load against the rewrite; 10x v2 is the contract. Apps running 10x v1 stay on `day8/reagent-classic`. |
| **R-007** Bundle estimates may be off by 30-50% | Per S3-008, the comparison build is delivered (rf2-5lbx) — see §12.2 + §12.3. The S3-008 *contract* (no stock-Reagent impl-leak, no `react-dom/server` leak) is enforced quantitatively by the symbol grep. The *size* half of R-007 remains a Stage-5 follow-up: in the in-tree shadow-cljs build both adapter trees live on the same classpath, and `re-frame.interop` still statically `:require`s stock `reagent.core` / `reagent.ratom`, so a per-build classpath-pruning hook is needed before the realised-gzip-reduction measurement is meaningful. The current in-tree numbers (stock counter ~93 KB gz, slim counter ~93 KB gz) reflect that shared-classpath shape and are NOT the binding "slim" claim. |

---

## §13 Migration path

### §13.1 Switching from the bridge to the rewrite

App author currently using `day8/re-frame2-reagent` (the thin bridge) wants to move to `day8/reagent-slim`. The required changes:

**`deps.edn`**:
```clojure
;; before
{:deps {day8/re-frame2-reagent {:mvn/version "..."}}}

;; after
{:deps {day8/reagent-slim {:mvn/version "..."}}}
```

**`(:require ...)` lines**: the adapter import path is unchanged.
```clojure
;; same on both bridge and rewrite
(:require [re-frame.adapter.reagent :as reagent])
(rf/init! reagent/adapter)
```

**User-facing Reagent imports** (when an app imports Reagent directly for `r/atom`, `r/create-class`, etc.):
```clojure
;; before (bridge: stock Reagent imports)
(:require [reagent.core       :as r]
          [reagent.dom.client :as rdc]
          [reagent.ratom      :as ratom])

;; after (rewrite: reagent2.* imports)
(:require [reagent2.core       :as r]
          [reagent2.dom.client :as rdc]
          [reagent2.ratom      :as ratom])
```

A simple `s/reagent\./reagent2./g` ns-rename at the import site is sufficient for apps inside the bounded surface.

### §13.2 Apps that used keys outside the 7-key Form-3 cap

Per-key migration recipes:

| Banned key | Migration |
|---|---|
| `:get-initial-state` | Use `:component-did-mount` to initialise via `set-state` or a `(reagent2.core/atom)` cell. |
| `:component-will-mount` / `:UNSAFE_componentWillMount` | Move the side effect to `:component-did-mount`. The "will-mount" timing has been deprecated since React 16.3 and is gone in React 19's StrictMode-simulated path anyway. |
| `:component-will-receive-props` / `:UNSAFE_componentWillReceiveProps` | Replace with `:get-snapshot-before-update` (if pre-render measurement) or `:component-did-update` (if reaction to prop change). |
| `:component-will-update` / `:UNSAFE_componentWillUpdate` | Replace with `:get-snapshot-before-update`. |
| `:should-component-update` | Move the optimisation to React.memo or a custom reactive shape. The audit (rf2-cgcv + rf2-kfpf) found zero usage across all four codebases. |
| `:get-derived-state-from-props` | Restructure the component to compute derived state from props at render time (idiomatic React function-component shape). Audit-confirmed zero usage. |

Apps that genuinely need a banned key for a real use case stay on `day8/reagent-classic` (the bridge).

### §13.3 Apps that used dropped surfaces

The dropped surfaces (§2.2 list) were audit-confirmed zero-usage across re-com / 10x / Dash8 / rf8. For apps OUTSIDE that ecosystem, the migration table:

| Dropped surface | Migration |
|---|---|
| `r/with-let` | Re-frame2: register a `reg-event-fx`-shaped `:on-create` handler; clean up via `:on-destroy`. Reagent-classic: keep using `with-let`. |
| `r/cursor` | Re-frame2: define a layer-2 sub. Reagent-classic: keep using `cursor`. |
| `r/track`, `r/track!` | Re-frame2: define a `reg-sub`. Reagent-classic: keep using `track` / `track!`. |
| `r/wrap` | Already deprecated in stock Reagent. Re-frame2: dispatch a `reg-event-db` from the on-change callback. Reagent-classic: keep using `wrap`. |
| `r/rswap!` | Manual loop with `swap!`. |
| `r/partial` | If the equality semantics matter, use a CLJS data structure (vector of fn + args) and resolve at call site. |
| `r/merge-props` | Userland helper — copy the impl into your app. |
| `r/unsafe-html` | Use `:dangerouslySetInnerHTML` directly via `[:div {:dangerouslySetInnerHTML {:__html "..."}}]`. |
| `r/adapt-react-class`, `r/reactify-component`, `r/create-element` | Use `[:> SomeReactComponent ...]` interop. |
| `r/after-render` | Use `reagent2.core/after-render` (kept). |
| `r/next-tick` | Use `goog.async.nextTick` directly (re-frame2 already does — `interop.cljs:15`). |
| `r/flush` | Use `reagent2.dom.client/flush-views!` (in tests). In production, you don't need to flush. |
| `r/force-update` | Form-3: kept (`reagent2.core/force-update`). Form-1/2: restructure to be reactive. |
| `r/dom-node` | Use a `:ref` callback. (Class B throw — fail loud.) |
| `r/class-names` | Userland one-liner. |
| `r/is-client` | Use re-frame2's `re-frame.interop/platform`. |

### §13.4 SSR migration

App that used `reagent.dom.server/render-to-string`: migrate to `re-frame.ssr/render-to-string` (the seam in `day8/re-frame2-ssr`). The re-frame2 SSR path produces a richer artefact (per Spec 011) — render-tree-hash, response accumulator, per-frame `:platform` predicate, error projector. Apps that just want HTML reproduction of a hiccup tree use `reagent2.dom.server/render-to-static-markup` (no React-id attributes; no SSR machinery).

---

## §14 Open questions / known limitations

The following surfaced during drafting. Stage 4 may need Mike's call on each.

### §14.1 The `defview` macro vs `reg-view` — namespace collision

Stage 4 ships `reagent2.impl.component/defview` as an optional Form-detection macro (per S3-003). re-frame2 already ships `re-frame.views-macros/reg-view` (and `re-frame.core/reg-view`) as the canonical view-registration macro. They serve different purposes (`reg-view` registers in the view registrar; `defview` just emits a Form-typed component) but the names are close and may confuse.

**Open question for Mike**: does `defview` ship as a Reagent-flavoured user surface, or is it simply absorbed into `reg-view` as an internal optimisation (Form detection moves into the macro that already exists)? If the latter, S3-003's "optional macro" framing changes — the runtime detection path stays load-bearing for `reg-view*` and direct `defn`-and-register paths, and `reg-view` quietly classifies at compile time.

**Recommendation (no bead filed; this is a Stage-4-or-later call)**: ship the runtime detection in Stage 4-A, fold the compile-time classification into `reg-view`'s expansion in Stage 4-B (so users get the perf hint for free, no new macro to learn). Skip `defview`.

### §14.2 The `reagent2.core/reaction` function vs `reagent2.ratom/reaction` macro

Dash8 uses 25 sites of `reagent.core/reaction` (the **function** form). rf8 uses both `reagent.core/reaction` (1 site) and the `reagent.ratom/reaction` **macro** (2 sites). The two surfaces co-exist in stock Reagent under different names but produce equivalent Reactions.

The rewrite ships `reagent2.core/reaction` (function) AND `reagent2.ratom/reaction` (macro). No filing needed; this is straightforward.

### §14.3 Boolean-attribute set + void-tag set duplication

The rewrite's `reagent2.dom.server` and `re-frame.ssr` both need the `boolean-attrs` and `void-tags` sets. Per §8.4 — Stage 4 lifts both from `re-frame.ssr`. **But** this creates a load-order ordering: `reagent2.dom.server` would need to require `re-frame.ssr`. The SSR seam is in a different artefact (`day8/re-frame2-ssr`), and the rewrite **must not** statically require it (per the bundle-isolation contract; cf. `re-frame.adapter.reagent`'s comment on lines 12-20 about not requiring the SSR ns).

**Resolution**: duplicate the static sets in `reagent2.dom.server`. The sets are <30 lines total; the duplication has no maintenance cost (HTML5's void-tag list is fixed). Stage 4 lifts the values via copy-paste, not require.

**Recommendation**: file `bd` bead `rf2-XXXX` to track that if the void-tag list ever changes (extraordinarily unlikely), both copies must update. Low priority.

### §14.4 The `:component-did-catch` test-coverage strategy under React 19

React 19's error-boundary contract has subtle edge cases — e.g. errors during `componentDidMount` of a child are caught by the nearest boundary, but errors during render of a sibling are not necessarily. Stage 4's test coverage in §6.5 needs to enumerate the exact cases. Stage 1 / Stage 2 didn't go to this depth.

**Recommendation**: file `bd` bead `rf2-XXXX-error-boundary-cases` to track the exact case enumeration during Stage 4 implementation. The test list in §12.1 is a starting point, not a final spec.

### §14.5 `flush-views!` interaction with React 19 Suspense

A child component throws a Promise (Suspense's standard pattern); the parent's `flush-views!` is called from a test. Does the flush wait for the suspended promise to resolve?

**Open question**: the spec says "drains React's pending work as a single composed operation" (§4.2). React 19's `act` does drain Suspense — but the precise sequencing (microtask-microtask vs microtask-then-act vs act-then-microtask) is non-trivial. Stage 4 picks an order and tests it; Stage 3 doesn't pre-commit.

**Recommendation**: file `bd` bead `rf2-XXXX-flush-views-suspense` to capture the design choice + test as Stage 4 makes it.

### §14.6 Source-coord stamping + `:>` interop

§5.4 specifies that the renderer stamps `:data-rf2-source-coord` on the first DOM-tag root. But what if the user's reg-view returns `[:> SomeReactComponent ...]` as the root? The current views.cljs path (`warn-non-dom-root!` line 246-258) emits a one-shot warning per id and skips the stamping. Under the rewrite's renderer-side stamping, the equivalent behaviour is: the `*source-coord*` dynamic var is read but the first-vector check sees `:>` (or any non-DOM-tag head) and skips. The warn-once needs to migrate too.

**Status**: covered in §11.3 "delete `warn-non-dom-root!`" — but the warning is useful for pair-tooling consumers. Stage 4 decides whether to keep the warning by re-implementing it in the renderer or drop it because audit didn't show evidence of triggering.

**Recommendation**: file `bd` bead `rf2-XXXX-source-coord-non-dom-root-warning` to track the keep-or-drop decision during Stage 4.

---

*Word count: ~7 100.*

*This spec is the binding input for Stage 4 (rf2-6hyy). Cross-references to Stage 1 (`findings/re-frame2-reagent-stage1-api-surface.md`) and Stage 2 (`findings/reagent-slim-stage2-efficiency.md`) are inline throughout. The current thin-bridge adapter at `implementation/adapters/reagent/` is the reference for unchanged-public-path elements (the adapter Var, the late-bind hook wiring, the cache-disposal contract).*
