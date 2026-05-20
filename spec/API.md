# re-frame2 — API

> **Type:** Reference
> Reference for the CLJS implementation's API: signatures, status, cross-references. No rationale — per-Spec docs own the *why*. Pattern-level contracts live in [000-Vision §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic) and the per-Spec docs. **`:fx-overrides` asymmetry:** id-valued at the pattern level; CLJS reference also accepts fn values — see [002 §`:fx-overrides`](002-Frames.md#fx-overrides--replace-fx-handlers).

## Conventions

- **Status** — exactly one base value, optionally combined with a single qualifier:
  - Base values: `v1` (ships in v1), `v1 (preserved)` (exists in current re-frame; preserved unchanged), `v1 (preserved + extended)` (exists today; v1 adds new arity or behaviour), `post-v1 lib` (design spec in v1 Specs but ships in a post-v1 library).
  - Qualifier: `dev-only` (elided in production builds — the macro emit site or runtime body, depending on the API).
  - Examples: `v1`, `v1 (preserved)`, `v1 (dev-only)`, `v1 (preserved, dev-only)`, `post-v1 lib`.
  - The `re-frame.alpha` namespace is dissolved (rf2-7cb2 / rf2-s9dn) — no APIs in this reference live outside `re-frame.core` (with the documented per-namespace exceptions: `re-frame.test-support` and `re-frame.test-helpers`).
- **Macro/Fn:** marked `M` (macro) or `Fn`.
- **Spec column** — names exactly the **canonical owning Spec** (the per-Spec doc whose contract this API implements). Migration rules and other cross-references are NOT in the Spec column; they appear in the Notes column when relevant.
- **Configure keys** — runtime configuration is uniformly via `(rf/configure <key> <opts>)`. Every `<key>` is enumerated in [§Configure keys](#configure-keys) below; per-area tables call out which keys their APIs read but do not redefine the key's vocabulary.
- **Per-artefact public namespaces.** The core surfaces live in `re-frame.core`. Per-feature artefacts ship their own public namespace; consumers `:require` the namespace directly (with the documented exception of the epoch surface, which late-binds re-exports through `re-frame.core`):

  | Namespace | Artefact | Surfaces |
  |---|---|---|
  | `re-frame.core` | core | the registration / dispatch / subscribe / interceptor / lifecycle / configure surfaces; **late-binds re-exports for two artefacts**: (a) the `re-frame.epoch` surface (`epoch-history`, `restore-epoch`, `reset-frame-db!`, `register-epoch-listener!`, `unregister-epoch-listener!`, `projected-record`, `projected-history`); (b) the `re-frame.ssr` **query surface** (`render-to-string`, `render-tree-hash`, `project-error`, `render-head`, `active-head`, `head-model->html`, `head-snapshot`). The `streaming-render-shell` / `streaming-render-continuation` / `streaming-build-final-payload` triple is **not** re-exported — the streaming surface is host-adapter territory and the SSR-aware host (`re-frame.ssr.ring` / equivalents) requires `[re-frame.ssr :as ssr]` directly. Re-exports activate when the named artefact is on the classpath; absent artefacts surface `:rf.error/<feature>-artefact-missing` errors. Per rf2-bc7dw (re-export drift reconciliation). |
  | `re-frame.test-support` | core | `dispatch-sequence`, `assert-state`, `poll-until`, fixture machinery (per [§Testing](#testing)). **Runtime-state axis** — registrar, frames, `app-db`, drain (per rf2-v7kjq). View-tree assertions live in the sibling `re-frame.test-helpers`. |
  | `re-frame.test-helpers` | core | View-assertion helpers — hiccup-walk (`find-by-testid` / `find-by-attr` family, `text-content`, `extract-handler`, `invoke-handler`), the `testid` authoring helper, and the `expand-tree` walker (per [§Testing — View-assertion helpers](#testing--view-assertion-helpers)). **View-tree axis** — hiccup data, testids, attached handlers (per rf2-v7kjq). Runtime-state assertions live in the sibling `re-frame.test-support`. |
  | `re-frame.ssr` | `day8/re-frame2-ssr` | `render-to-string`, `render-tree-hash`, `streaming-render-*`, `render-head`, `active-head`, `head-model->html`, `head-snapshot`, `project-error` (per [§SSR](#ssr-spec-011)). |
  | `re-frame.ssr.ring` | `day8/re-frame2-ssr-ring` | the Ring host-adapter (default-html-shell, streaming-prefix/suffix, trusted-shell hooks per Spec 011). |
  | `re-frame.schemas` | `day8/re-frame2-schemas` | `app-schemas`, `app-schema-at`, `app-schema-meta-at`, `app-schemas-digest`, `set-schema-validator!`/-explainer!/-printer!, `at-boundary` (per [§Schemas](#schemas)). |
  | `re-frame.http` | `day8/re-frame2-http` | the verb helpers `get` / `post` / `put` / `delete` / `patch` / `head` / `options` (per [§HTTP requests](#http-requests-spec-014)). |
  | `re-frame.machines` | `day8/re-frame2-machines` (post-v1 scaffolding) | `reg-machine`, `make-machine-handler`, `machine-transition`, `sub-machine`, `machines`, `machine-meta`, the `:rf.machine/spawn` / `:rf.machine/destroy` fx (per [§Machines](#machines)). |
  | `re-frame.epoch` | `day8/re-frame2-epoch` | `epoch-history`, `restore-epoch`, `reset-frame-db!`, `register-epoch-listener!`, `unregister-epoch-listener!`, `(rf/configure :epoch-history ...)`. Re-exported through `re-frame.core` via late-bind hooks — `(:require [re-frame.epoch])` at boot before consuming the surfaces through `re-frame.core` (per [Tool-Pair §Time-travel — Artefact home](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)). |
  | `re-frame.adapter.uix` / `re-frame.adapter.helix` | `day8/re-frame2-uix` / `day8/re-frame2-helix` | UIx- and Helix-specific surfaces (per [§UIx adapter](#uix-adapter-spec-006-rf2-3yij) / [§Helix adapter](#helix-adapter-spec-006-rf2-2qit)). |

- **Projection-maintenance rule (rf2-w0n68).** This doc is a **non-canonical projection** — the canonical contract lives in the per-Spec docs cited in each row's Spec column. The projection MUST stay in sync with shipped artefacts. Every row carries: **owner** (Spec column) — the canonical spec doc; **artefact / namespace** — where the public-var lives (table above); **public-var status** — `v1` / `v1 (preserved)` / `post-v1 lib` / `post-v1 (planned, rf2-<id>)` for surfaces specced normatively but not yet shipped (the spec contract holds; the impl is tracked by the named bead); **verification pointer** — the conformance fixture, the per-artefact test, or the AI-Audit row that asserts the row holds. Rows that document a surface neither shipped nor on a tracking bead MUST be cut from this projection — the design's normative claim then lives only in the owner spec.

---

## Classification (rf2-kp835)

Every public-surface row in this document is **Canonical** — a documented, supported, v1 (or post-v1 lib) API that downstream apps and tools may rely on, in the status the row's Status column gives. The rf2-kp835 audit (Phase-1, 2026-05-17) classified ~110 public defs of `re-frame.core` and confirmed 97% canonical; the rare-use helpers all justified canonical status (interceptor plumbing, adapter lifecycle, schema-printer swap seam, off-box-egress projections, v1-preserved teardown / clearing surfaces). No public surface is classified Deprecated; none is classified Advanced.

The only **Internal** carve-outs are two JVM-only macro-helpers re-exposed in `re-frame.core` purely so pre-split tests can reach them (per rf2-4rnui):

- `re-frame.core/expand-reg-view` — `^:no-doc`; the canonical home is `re-frame.core-reg-view-macro/expand-reg-view`.
- `re-frame.core/parse-reg-view-args` — `^:no-doc`; the canonical home is `re-frame.core-reg-view-macro/parse-reg-view-args`.

Neither is rowed in this projection. Applications and tools MUST NOT depend on these re-exports; reach the canonical homes directly.

---

## Registration

> **Return value.** Every `reg-*` row below returns its **primary id** — the keyword (or path, for `reg-app-schema`) the caller registered with. `reg-flow` returns the `:id` value of its flow-map (the primary id is carried by the map, not a separate arg). Per [Conventions §`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

| API | M/Fn | Signature | Status | Spec | Notes |
|---|---|---|---|---|---|
| `reg-event-db` | M | `(reg-event-db id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | Macro for source-coord capture. See [MIGRATION §M-5](../migration/from-re-frame-v1/README.md) for higher-order-use migration. |
| `reg-event-fx` | M | `(reg-event-fx id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | Handler accepts `(fn [m] ...)` or `(fn [m event-vec] ...)`. |
| `reg-event-ctx` | M | `(reg-event-ctx id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | |
| `reg-sub` | M | `(reg-sub id ?metadata signal-fn? computation-fn)` | v1 (preserved + extended) | 002 | `:<-` sugar preserved. The only sub-registration form in v2. |
| `reg-fx` | M | `(reg-fx id ?metadata handler)` | v1 (preserved + extended) | 002 | Unary or binary handler. |
| `reg-cofx` | M | `(reg-cofx id ?metadata handler)` | v1 (preserved) | 002 | Reading a sub's value from a handler is by cofx-wrapping — see [Guide ch.05 §Reading a sub from a handler](../docs/guide/05-coeffects.md#reading-a-sub-from-a-handler). |
| `reg-frame` | M | `(reg-frame id metadata)` | v1 | 002 | Atomic create + register. |
| `make-frame` | Fn | `(make-frame opts) → :rf.frame/<id>` | v1 | 002 | Anonymous frame; gensym'd id. |
| `reg-view` | M | `(reg-view sym [args] body+)` / `(reg-view sym docstring [args] body+)` / `(reg-view ^{:rf/id :explicit/id} sym [args] body+)` | v1 | 004 | Defn-shape; auto-defs the symbol; auto-derives id from `(keyword *ns* sym)`; auto-injects `dispatch` / `subscribe` as lexical bindings; rejects non-defn-shape bodies at macroexpand. |
| `reg-view*` | Fn | `(reg-view* id render-fn)` / `(reg-view* id metadata render-fn)` | v1 | 004 | Plain-fn surface beneath `reg-view`. No auto-def, no auto-inject, no compile check. Use for computed ids, library-generated views, Reagent Form-3 (`create-class`), or registration without a Var. The `*` follows Clojure's `let`/`let*`, `fn`/`fn*` idiom (per [Conventions](Conventions.md)). |
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` | v1 | 005 | Walks the literal spec form at expansion time; stamps per-element source coords under `:rf.machine/source-coords` (rf2-8bp3). Top-level call-site coords land on `handler-meta`. |
| `reg-machine*` | Fn | `(reg-machine* machine-id machine-spec)` | v1 | 005 | Plain-fn surface beneath `reg-machine`. No source-coord walking. Use for code-gen pipelines, REPL workflows, or conformance harnesses that synthesise specs from data. |
| `reg-app-schema` | M | `(reg-app-schema path schema)` / `(reg-app-schema path schema opts)` | v1 | 010 | **Path is the registration id.** The `:app-schema` registry kind is **path-keyed** (per Spec 001 §Registry model) — every other `reg-*` is keyword-id-keyed; the first arg is the path vector (e.g. `[:user]`) and `(app-schema-at [:user])` / `(app-schema-meta-at [:user])` look up by the same vector. The asymmetry is principled (paths are first-class in `get-in` / `assoc-in` grain — schemas-at-paths matches the dataflow grain), not accidental. Per [Conventions §`reg-*` return-value rule](Conventions.md#reg--return-value-convention). |
| `reg-app-schemas` | M | `(reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})` / `(reg-app-schemas {…} opts)` — bulk plural form for feature-modular apps that register 5–20 paths against the same prefix (per Conventions §Feature-modularity prefix convention). Each entry routes through the singular `reg-app-schema` and is stamped with this call's source-coords. Returns the vector of paths registered (rf2-jzs9) | v1 | 010 | |
| `reg-marks` | Fn | `(reg-marks frame-id {:sensitive [paths] :large [paths]})` | v1 (rf2-vw7f5) | 015 | Frame-scoped path-marks for data classification — `:sensitive` paths render as `:rf/redacted` in observation surfaces; `:large` paths surface as `:rf/large` summaries. Per [015 §App-db (per frame) — `reg-marks`](015-Data-Classification.md#2-app-db-per-frame--reg-marks). Re-registration replaces the previous declaration set in full (schema-attached marks per `reg-app-schema` `:sensitive?` / `:large?` are preserved and union at lookup time). Pure declaration — does not mutate `app-db`. Returns `frame-id`. |
| `reg-flow` | Fn | `(reg-flow flow)` / `(reg-flow flow opts)` | v1 | 013 | `opts` is a map (currently `{:frame frame-id}`). The shipped surface is opts-map only — same shape as `dispatch` / `subscribe` / `clear-flow`. Returns the flow's `:id` (per Conventions §`reg-*` return-value convention). **The `:flow` registrar slot is last-registration-wins across frames** (the same id registered against multiple frames shares one registrar slot, keyed by `flow-id` only); for full per-frame discovery use `@re-frame.flows/flows` — the per-frame runtime registry is the source of truth for evaluation. Per [013-Flows.md §Frame-scoping](013-Flows.md#frame-scoping) and rf2-o4um2. |
| `reg-route` | M | `(reg-route id metadata)` | v1 | 012 | |
| `reg-head` | M | `(reg-head id ?metadata head-fn)` | v1 | 011 | New registry kind `:head`; routes name a registered head via `:head` route metadata. Captures source-coords; under the optional-artefact wrapper convention the surface routes through the `:ssr/reg-head` late-bind hook. |
| `reg-error-projector` | M | `(reg-error-projector id ?metadata projector-fn)` | v1 | 011 | New registry kind `:error-projector`; named per-frame via the frame's `:ssr {:public-error-id ...}` metadata (per `reg-frame` / `make-frame`). |

### Clearing registrations

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `clear-event` | Fn | `(clear-event)` / `(clear-event id)` | v1 (preserved) |
| `clear-sub` | Fn | `(clear-sub)` / `(clear-sub id)` | v1 (preserved) |
| `clear-fx` | Fn | `(clear-fx)` / `(clear-fx id)` | v1 (preserved) |
| `clear-flow` | Fn | `(clear-flow id)` / `(clear-flow id opts)` | v1 |
| `destroy-frame!` | Fn | `(destroy-frame! frame-id)` — the normative teardown boundary. Per-feature artefacts (flows, machines, schemas, SSR, epoch) hang their frame-scoped cleanup off this call; flows release per [013 §Frame-destroy teardown](013-Flows.md#frame-destroy-teardown). | v1 |
| `reset-frame!` | Fn | `(reset-frame! frame-id)` | v1 |
| `clear-sub-cache!` | Fn | `(clear-sub-cache! frame-id?)` | v1 (preserved) |

---

## Dispatch and subscribe

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `dispatch` | M | `(dispatch event)` / `(dispatch event opts)` | v1 (preserved + extended); macro per rf2-ts1a — captures call-site for `:rf.trace/call-site` | 002 |
| `dispatch*` | Fn | `(dispatch* event)` / `(dispatch* event opts)` | rf2-ts1a — fn form for HoF / programmatic dispatch (no call-site stamping) | 002 |
| `dispatch-sync` | M | `(dispatch-sync event)` / `(dispatch-sync event opts)` | v1 (preserved + extended); macro per rf2-ts1a | 002 |
| `dispatch-sync*` | Fn | `(dispatch-sync* event)` / `(dispatch-sync* event opts)` | rf2-ts1a — fn form for HoF / programmatic sync dispatch | 002 |
| `subscribe` | M | `(subscribe query-v)` / `(subscribe frame-id query-v)` | v1 (preserved + extended); macro per rf2-ts1a | 002 |
| `subscribe*` | Fn | `(subscribe* query-v)` / `(subscribe* frame-id query-v)` | rf2-ts1a — fn form for HoF / programmatic subscribe | 002 |
| `subscribe-once` | Fn | `(subscribe-once query-v)` / `(subscribe-once frame-id query-v)` → value (subscribe + deref + immediate unsubscribe; one-shot, non-reactive read for handler bodies, machine actions, REPL) | v1 | 006 |
| `unsubscribe` | Fn | `(unsubscribe query-v)` / `(unsubscribe frame-id query-v)` → nil (decrement the cache ref-count; ref-count→0 schedules disposal after the configured `:sub-cache` grace-period). Carved out from the [Conventions §Tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-) — `clear-sub` is already taken by the symmetric inverse of `reg-sub` (the registrar decrement), so `un-` is reserved as the singular form for the sub-cache ref-count decrement (rf2-cmabc). | v1 | 006 |
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot. Sugar over `(subscribe [:rf/machine machine-id])`. | v1 | 005 |

`opts` map keys: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Envelope shape and semantics: see [002 §Routing: the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope).

**Canonical `event` / `query-v` shape (best practice).** `[<id>]` (trivial), `[<id> <single-scalar>]` (single-arg), `[<id> {<k> <v>}]` (multi-arg → single map payload). Variadic `[<id> a b c]` is tolerated by the runtime for v1-migration and caller convenience; the linter nudges new code toward the map form. Full rationale and cross-refs: [Conventions §Canonical event-vector shape](Conventions.md#canonical-event-vector-shape-best-practice).

### `dispatch-*` family taxonomy

Per audit-of-audits (rf2-cthfn) state-machines #10, the `dispatch-*` family has two sub-shapes that look alike on first read but answer different questions. Both *are* dispatch operations — the family-prefix is honest — but they sit in different sub-families.

**Stamping-pair sub-family** (`dispatch` / `dispatch-sync` macros + `dispatch*` / `dispatch-sync*` fn variants).
The pair-shape question is **"do you want call-site stamping or not?"** The macro form captures `:rf.trace/call-site` from the surrounding source position (rf2-ts1a) so tooling can navigate from a trace event back to the originating expression. The `*` fn-form skips the stamping — needed for HoF composition (`(map dispatch* events)`) where a macro can't sit inside the higher-order call. Both shapes route through the same dispatcher; only the trace stamping differs.

**Named-target sugar sub-family** (`dispatch-to-system`, per [005 §Cross-machine messaging by name](005-StateMachines.md)).
This question is **"do you have a `:system-id` instead of a target machine-id?"** `dispatch-to-system` is sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))` — it dispatches just like the macros, but it resolves the target through the per-frame `[:rf/system-ids]` reverse index first. It is **not** outside the dispatch family; it's a named-addressing sugar built on top of `dispatch`. The hyphen-after-`dispatch` reads as "dispatch with extra routing logic on top," not "different kind of dispatch."

The two sub-families compose: `dispatch-to-system` ultimately calls `dispatch`, so the same `:rf.trace/call-site` stamping fires (call-site at the `dispatch-to-system` invocation, since that's the macro the user wrote). When future named-addressing variants land (per actor-model patterns) they slot into the same sub-family; new call-site-stamping variants slot into the same pair.

---

## View ergonomics

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `frame-provider` | Component (Reagent) | `[rf/frame-provider {:frame :todo} & children]` | v1 | 002 |
| `with-frame` | M | `(with-frame :keyword body)` *or* `(with-frame [sym expr] body)` | v1 | 002 |
| `bound-fn` | M | `(bound-fn [args] body)` | v1 | 002 |
| `dispatcher` | Fn | `(dispatcher)` → `(fn [event] ...)` — captures the current frame at call time and returns a frame-bound dispatch fn. Safe to call during render AND from async callbacks where the dynamic-var binding has unwound | v1 | 002, 004 |
| `subscriber` | Fn | `(subscriber)` → `(fn [query-v] ...)` — companion to `dispatcher` for subscribe. Captures the current frame at call time | v1 | 002, 004 |
| `view` | Fn | `(view view-id)` → **render-fn** (runtime-lookup handle; returns the registered render-fn, *not* hiccup). Use in hiccup as `[(rf/view :id) args...]` — the lookup form for late-binding a registered view by id. | v1 | 001, 004 |

`with-frame`'s two shapes (bare keyword vs let-binding) are documented in [002 §with-frame](002-Frames.md#with-frame).

`bound-fn` is a CLJS-only macro; CLJS users either reach it via `rf/bound-fn` (after `(:require [re-frame.core :as rf])`) or `:require-macros [re-frame.core :refer [bound-fn]]`.

---

## UIx adapter (Spec 006, rf2-3yij)

UIx-specific surfaces live in `re-frame.adapter.uix` (artefact `day8/re-frame2-uix`) — they are NOT re-exported from `re-frame.core` because core has no static dependency on the adapter (the dependency direction is adapter → core per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention)). Apps targeting UIx `:require [re-frame.adapter.uix :as uix-adapter]` and call the surfaces directly.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `uix-adapter/adapter` | Var (map) | `{:make-state-container … :render … :dispose-adapter! …}` | v1 | 006 |
| `uix-adapter/use-subscribe` | Fn (UIx hook) | `(use-subscribe query-v)` / `(use-subscribe frame-kw query-v)` → current sub value | v1 | 006 |
| `uix-adapter/use-current-frame` | Fn (UIx hook) | `(use-current-frame)` → frame-kw | v1 | 006 |
| `uix-adapter/frame-provider` | Fn (UIx component) | `($ uix-adapter/frame-provider {:frame :session :children […]})` | v1 | 002, 006 |
| `uix-adapter/wrap-view` | Fn | `(wrap-view id metadata user-fn)` → wrapped fn (source-coord injection per Spec 006 §Source-coord annotation) | v1 | 006 |
| `uix-adapter/flush-views!` | Fn | `(flush-views!)` / `(flush-views! f)` — wraps React's `act()` for tests | v1 | 006, 008 |
| `uix-adapter/set-hiccup-emitter!` | Fn | `(set-hiccup-emitter! f)` — install render-tree → HTML fn (parity with the Reagent adapter's late-bind seam) | v1 | 006, 011 |

Per rf2-3yij Decision 1 the hook is named `use-subscribe` (matching the React/UIx idiom). Per Decision 3 there is no auto-injection — UIx components call the hook and `(rf/dispatcher)` directly. Per Decision 4 `reg-view` (the Reagent macro) does NOT cover UIx; UIx users register with `rf/reg-view*` if they need registry-keyed view addressing.

The shared React Context that backs `frame-provider` lives in `re-frame.adapter.context` (CLJS-only file in core, factored out per Decision 2) — both the Reagent adapter and the UIx adapter consume the same `createContext` object so a future mixed-substrate app's frame-provider chain composes across substrates.

---

## Helix adapter (Spec 006, rf2-2qit)

Helix-specific surfaces live in `re-frame.adapter.helix` (artefact `day8/re-frame2-helix`) — they are NOT re-exported from `re-frame.core` because core has no static dependency on the adapter (the dependency direction is adapter → core per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention)). Apps targeting Helix `:require [re-frame.adapter.helix :as helix-adapter]` and call the surfaces directly. The Helix adapter mirrors the UIx adapter exactly — the eight rf2-3yij decisions transfer one-for-one to rf2-2qit.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `helix-adapter/adapter` | Var (map) | `{:make-state-container … :render … :dispose-adapter! …}` | v1 | 006 |
| `helix-adapter/use-subscribe` | Fn (Helix hook) | `(use-subscribe query-v)` / `(use-subscribe frame-kw query-v)` → current sub value | v1 | 006 |
| `helix-adapter/use-current-frame` | Fn (Helix hook) | `(use-current-frame)` → frame-kw | v1 | 006 |
| `helix-adapter/frame-provider` | Fn (Helix component) | `($ helix-adapter/frame-provider {:frame :session :children […]})` | v1 | 002, 006 |
| `helix-adapter/wrap-view` | Fn | `(wrap-view id metadata user-fn)` → wrapped fn (source-coord injection per Spec 006 §Source-coord annotation) | v1 | 006 |
| `helix-adapter/flush-views!` | Fn | `(flush-views!)` / `(flush-views! f)` — wraps React's `act()` for tests | v1 | 006, 008 |
| `helix-adapter/set-hiccup-emitter!` | Fn | `(set-hiccup-emitter! f)` — install render-tree → HTML fn (parity with the Reagent and UIx adapters' late-bind seam) | v1 | 006, 011 |

Per rf2-2qit (transferring rf2-3yij Decision 1) the hook is named `use-subscribe`. Per Decision 3 there is no auto-injection — Helix components call the hook and `(rf/dispatcher)` directly. Per Decision 4 `reg-view` (the Reagent macro) does NOT cover Helix; Helix users register with `rf/reg-view*` if they need registry-keyed view addressing.

The shared React Context that backs `frame-provider` lives in `re-frame.adapter.context` (CLJS-only file in core, factored out per Decision 2) — the Reagent, UIx, and Helix adapters all consume the same `createContext` object so a mixed-substrate app's frame-provider chain composes across substrates.

---

## Routing (Spec 012)

`reg-route` is rowed canonically in [§Registration](#registration).

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `match-url` | Fn | `(match-url url)` → `{:route-id :params :query :validation-failed?}` or `nil` | v1 | 012 |
| `route-url` | Fn | `(route-url route-id path-params)` / `(route-url route-id path-params query-params)` → URL string | v1 | 012 |
| `route-link` | Fn (registered view at `:route/link`) | `[rf/route-link {:to :route-id :params {...} :query {...} :fragment "..." & html-attrs} & children]` | v1 | 012 |

`reg-route` metadata reserved keys: `:doc`, `:path`, `:params`, `:query`, `:query-defaults`, `:query-retain`, `:tags`, `:parent`, `:on-match`, `:on-error`, `:can-leave`, `:scroll`. Canonical detail in [012-Routing.md](012-Routing.md); shape in [Spec-Schemas §`:rf/route-metadata`](Spec-Schemas.md#rfroute-metadata).

`route-link` click rules: a plain primary-button click (no modifier keys, no `defaultPrevented`) calls `.preventDefault` and dispatches `[:rf/url-requested {:url <synthesised> :to <route-id> :params {...} :query {...} :fragment "..."}]`. Modifier-key clicks (cmd / ctrl / shift / alt) and auxiliary-button clicks (middle-click) defer to the browser so the native `href` opens in a new tab. A caller-supplied `:on-click` runs first; if it calls `.preventDefault` (or otherwise leaves `defaultPrevented` true) the framework's interception is skipped. Keys other than `:to` / `:params` / `:query` / `:fragment` / `:on-click` pass through to the underlying `<a>` element. Detailed semantics in [012-Routing.md §Linking from views](012-Routing.md#linking-from-views--plain-anchor-semantics).

Standard route-related events:

| Event | Notes | Spec |
|---|---|---|
| `:rf.route/navigate` | Navigate to a registered route. | 012 |
| `:rf.route/handle-url-change` | Default handler for `:rf/url-changed`. | 012 |
| `:rf/url-changed` | The browser URL changed. | 012 |
| `:rf/url-requested` | The user clicked a framework-owned link. | 012 |
| `:rf.route/navigation-blocked` | A `:can-leave` guard rejected a navigation. | 012 |
| `:rf.route/continue` | User-dispatched event proceeding a blocked navigation. | 012 |
| `:rf.route/cancel` | User-dispatched event abandoning a blocked navigation. | 012 |

Standard route-related subs:

| Sub | Returns | Spec |
|---|---|---|
| `:rf/route` | The full `:rf/route` slice `{:id :params :query :transition :error}` | 012 |
| `:rf.route/id` | Current route id | 012 |
| `:rf.route/params` | Current path params | 012 |
| `:rf.route/query` | Current query params | 012 |
| `:rf.route/transition` | `:idle` / `:loading` / `:error` | 012 |
| `:rf.route/error` | Current error map (when `:transition = :error`) | 012 |
| `:rf.route/fragment` | Current URL fragment (string or nil) | 012 |
| `:rf.route/chain` | Vector of route ids from parent-most to current (per `:parent` links) | 012 |
| `:rf/pending-navigation` | The pending-nav slot (per `:rf/pending-navigation` schema) when a navigation is blocked; `nil` otherwise | 012 |

Standard route-related fx (canonical detail in [012-Routing.md](012-Routing.md)):

| Fx | Args | Platforms |
|---|---|---|
| `:rf.nav/push-url` | URL string | `:client` |
| `:rf.nav/replace-url` | URL string | `:client` |
| `:rf.nav/scroll` | scroll-spec map | `:client` |
| `:rf.route/with-nav-token` | `{:do <fx-entry> :nav-token <token>}` | universal |

---

## SSR (Spec 011)

> **Namespace:** the surfaces below live in `re-frame.ssr` (artefact `day8/re-frame2-ssr`); consumers `(:require [re-frame.ssr :as ssr])`. The Ring host-adapter lives in `re-frame.ssr.ring` (artefact `day8/re-frame2-ssr-ring`). Neither is re-exported from `re-frame.core` — apps targeting SSR add the artefacts to their deps and require the namespace directly. Per the §Conventions per-artefact namespace table.

`reg-head` and `reg-error-projector` are rowed canonically in [§Registration](#registration). The head-fn signature is `(fn [db route] head-model)`; the projector-fn signature is `(fn [trace-event] :rf/public-error)`.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `render-to-string` | Fn | `(render-to-string view-or-hiccup opts)` → HTML string | v1 | 011 |
| `render-tree-hash` | Fn | `(render-tree-hash render-tree)` → 32-bit FNV-1a structural hash (lowercase hex). Identical output on JVM and CLJS for the same canonical-EDN representation. Per [011 §Hydration-mismatch detection](011-SSR.md). | v1 | 011 |
| `project-error` | Fn | `(project-error frame-id trace-event)` → `:rf/public-error`. Applies the active error-projector (selected by the frame's `:ssr {:public-error-id ...}` metadata) for the named frame. Per [011 §Server error projection](011-SSR.md). | v1 | 011 |
| `render-head` | Fn | `(render-head head-id opts)` → `:rf/head-model` | v1 | 011 |
| `active-head` | Fn | `(active-head)` / `(active-head frame-id)` → `:rf/head-model` | v1 | 011 |
| `head-model->html` | Fn | `(head-model->html head-model)` / `(head-model->html head-model {:wrap? bool})` → inner-head HTML string | v1 | 011 |
| `head-snapshot` | Fn | `(head-snapshot frame-id)` → `{head-id → :rf/head-model}`. Read the per-frame snapshot of last-produced head-models. Returns `{}` for a frame that has never seen a `render-head` call (or whose snapshot has been cleared via per-request frame teardown). Useful for tests, introspection, and tools. Re-exported as `rf/head-snapshot` (rf2-ip6ol). Per [011 §Head/meta contract](011-SSR.md). | v1 | 011 |
| `streaming-render-shell` | Fn | `(streaming-render-shell root-hiccup)` → `{:shell-html "…" :continuations [{:id <id> :subtree <hiccup>} …]}`. Walks the tree once; at each `:rf/suspense-boundary` emits a `<template …suspense-fallback>` placeholder + records a continuation. Per [011 §Streaming SSR](011-SSR.md#streaming-ssr) — rf2-ojakd / rf2-olb64 (a). | v1 | 011 |
| `streaming-render-continuation` | Fn | `(streaming-render-continuation frame-id entry)` → `{:id … :html "…" :delta {…} :failed? bool}`. Drains one continuation against `frame-id`'s app-db; snapshots before-db / after-db and computes the per-subtree delta. Catches throws and surfaces the original fallback HTML inline (per [011 §Failure semantics — inline fallback](011-SSR.md#failure-semantics--inline-fallback)). | v1 | 011 |
| `streaming-build-final-payload` | Fn | `(streaming-build-final-payload frame-id render-hash opts)` → canonical `:rf/hydration-payload`. Called after all continuations drain to populate the `__rf_payload` final chunk. | v1 | 011 |

Standard SSR-related events:

| Event | What it does | Spec |
|---|---|---|
| `:rf/server-init` | Per-request server-side initialisation. Reads request cofx; dispatches setup events. `:platforms #{:server}`. | 011 |
| `:rf/hydrate` | Seed the client-side `app-db` from the server-supplied payload. Runs once on client bootstrap. | 011 |

Standard SSR-related fx (server-only; `:platforms #{:server}`):

| Fx | Args | Spec |
|---|---|---|
| `:rf.server/set-status` | `:int` (per `:rf.fx.server/set-status-args`) | 011 |
| `:rf.server/set-header` | `{:name :value}` (per `:rf.fx.server/set-header-args`) | 011 |
| `:rf.server/append-header` | `{:name :value}` (per `:rf.fx.server/append-header-args`) | 011 |
| `:rf.server/set-cookie` | `:rf.server/cookie` map | 011 |
| `:rf.server/delete-cookie` | `{:name ?:path ?:domain}` | 011 |
| `:rf.server/redirect` | `{:location ?:status}` (default `:status 302`); truncates HTML | 011 |

Standard SSR-related subs:

| Sub | Returns | Spec |
|---|---|---|
| `:rf/response` | The current request's response accumulator (status / headers / cookies / redirect) | 011 |
| `:rf/head` | The head model for the active route (resolved via `(active-head)`) | 011 |
| `:rf/public-error` | The sanitised public-error projection when an error page is being rendered; `nil` otherwise | 011 |

Standard cofx (server-only):

| Cofx | Returns | Spec |
|---|---|---|
| `:rf.server/request` | The active HTTP request map | 011 |

`reg-fx`'s `:platforms` metadata key (a set containing `:server` and/or `:client`) gates fx execution by active platform; **default `#{:server :client}` (universal)** when the key is absent. Skipped fx emit a `:rf.fx/skipped-on-platform` trace event. Detail in [011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx).

SSR error-projection policy is **per-frame metadata** (see [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) bucket 3): a frame opts in via the `:ssr {:public-error-id ... :dev-error-detail? ...}` map on its `reg-frame` / `make-frame` metadata. See [011 §Server error projection](011-SSR.md#server-error-projection) for the keys.

---

## HTTP requests (Spec 014)

`:rf.http/managed` is the canonical, optional HTTP-request fx — `v1 (optional capability)`. CLJS reference ships it on Fetch (browser) and `java.net.http.HttpClient` (JVM). Args, behaviours, decode pipeline, retry semantics, abort surface, failure taxonomy, and reply addressing are normatively defined in [014-HTTPRequests.md](014-HTTPRequests.md); the surface below is the API-level summary.

| API | Kind | Signature / shape | Status | Spec |
|---|---|---|---|---|
| `:rf.http/managed` | fx | `[:rf.http/managed args-map]` — args per [014 §The args map](014-HTTPRequests.md#the-args-map) and `:rf.fx/managed-args` | v1 (optional capability) | 014 |
| `:rf.http/managed-abort` | fx | `[:rf.http/managed-abort request-id]` — abort the in-flight request with the given `:request-id` | v1 (optional capability) | 014 |
| `:rf.http/managed-canned-success` | fx | `[:rf.http/managed-canned-success {:value v}]` — synthesises the canonical success reply (per [014 §Testing](014-HTTPRequests.md#testing)). Registered at load of `re-frame.http-test-support` (NOT `re-frame.http-managed`) per rf2-cdmle / rf2-fu71w; the actual stubbing macros (`with-managed-request-stubs` and friends, rows below) live in `re-frame.http-managed`. | v1 (optional capability, dev/test) | 014 |
| `:rf.http/managed-canned-failure` | fx | `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]` — synthesises the canonical failure reply. Same registration gate (`re-frame.http-test-support`) and same cross-ref to `re-frame.http-managed` for the stubbing macros (rf2-fu71w). | v1 (optional capability, dev/test) | 014 |
| `with-managed-request-stubs` | M | `(with-managed-request-stubs route-map body+)` — route-map `{[<method> <url>] {:reply ...}}` per [014 §Testing](014-HTTPRequests.md#testing). Lives in `re-frame.http-managed` (NOT in `re-frame.http-test-support`, which is the canned-stub registration gate); see [Spec 008 §HTTP test surfaces](008-Testing.md#http-test-surfaces--two-namespaces-rf2-fu71w) (rf2-fu71w). | v1 (optional capability, dev/test) | 014 |
| `with-managed-request-stubs*` | Fn | `(with-managed-request-stubs* route-map body-fn)` — plain-fn surface beneath `with-managed-request-stubs`; the `*` follows the Clojure `let`/`let*`, `fn`/`fn*` idiom (per [Conventions](Conventions.md)). Use for computed route-maps or non-literal bodies. Per [014 §Testing](014-HTTPRequests.md#testing). | v1 (optional capability, dev/test) | 014 |
| `install-managed-request-stubs!` | Fn | `(install-managed-request-stubs! route-map)` — install the stub routes; persists until `uninstall-managed-request-stubs!` is called. Lower-level than `with-managed-request-stubs`; use when stubs span multiple `deftest`s. Per [014 §Testing](014-HTTPRequests.md#testing). | v1 (optional capability, dev/test) | 014 |
| `uninstall-managed-request-stubs!` | Fn | `(uninstall-managed-request-stubs!)` — drop any installed stubs and restore real-request routing. Idempotent. Per [014 §Testing](014-HTTPRequests.md#testing). | v1 (optional capability, dev/test) | 014 |
| `reg-http-interceptor` | Fn | `(reg-http-interceptor {:frame ... :id ... :before (fn [ctx] ctx')})` — register a request-side interceptor on a frame's `:rf.http/managed` middleware chain (per [014 §Middleware](014-HTTPRequests.md#middleware), rf2-6y3q). `:before` receives a ctx `{:request :args :frame :event}` and returns a (possibly-modified) ctx. **Call-shape note.** `:frame` rides inside the interceptor map alongside `:id` / `:before`, not in a trailing `opts` kwarg — this is the **sole documented exception** to the `reg-*` opts-kwarg convention (per [Conventions §`reg-*` frame-binding convention — opts kwarg, not main arg](Conventions.md#reg--frame-binding-convention--opts-kwarg-not-main-arg)). Rationale: `:frame` is semantically part of an HTTP interceptor's identity (the chain is per-frame; the `:rf.http.interceptor/registered` trace tags `:frame` and `:id` as co-equal coordinates) rather than an orthogonal mounting key. New `reg-*` APIs MUST use the opts-kwarg shape. | v1 (optional capability) | 014 |
| `clear-http-interceptor` | Fn | `(clear-http-interceptor id)` / `(clear-http-interceptor frame id)` — unregister an interceptor by id (per [014 §Middleware](014-HTTPRequests.md#middleware), rf2-6y3q). Single-arity targets `:rf/default`. | v1 (optional capability) | 014 |
| `re-frame.http/get`     | Fn | `(rf.http/get url)` / `(rf.http/get url args)` — build a `[:rf.http/managed {:request {:method :get :url url} ...}]` fx vector (per [014 §Call-site helpers](014-HTTPRequests.md#call-site-helpers), rf2-pf4k). | v1 (optional capability) | 014 |
| `re-frame.http/post`    | Fn | `(rf.http/post url)` / `(rf.http/post url args)` — POST helper; same shape as `get`. | v1 (optional capability) | 014 |
| `re-frame.http/put`     | Fn | `(rf.http/put url)` / `(rf.http/put url args)` — PUT helper. | v1 (optional capability) | 014 |
| `re-frame.http/delete`  | Fn | `(rf.http/delete url)` / `(rf.http/delete url args)` — DELETE helper. | v1 (optional capability) | 014 |
| `re-frame.http/patch`   | Fn | `(rf.http/patch url)` / `(rf.http/patch url args)` — PATCH helper. | v1 (optional capability) | 014 |
| `re-frame.http/head`    | Fn | `(rf.http/head url)` / `(rf.http/head url args)` — HEAD helper. | v1 (optional capability) | 014 |
| `re-frame.http/options` | Fn | `(rf.http/options url)` / `(rf.http/options url args)` — OPTIONS helper. | v1 (optional capability) | 014 |

Public API surface in `re-frame.core` for ports that ship Spec 014. Ports that omit it MUST NOT register `:rf.http/*` for any other purpose (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).

The verb helpers (`get` / `post` / `put` / `delete` / `patch` / `head` / `options`) live in `re-frame.http` — users `(:require [re-frame.http :as rf.http])` alongside `re-frame.core`. They're pure synthesis fns that produce the canonical `[:rf.http/managed args-map]` fx vector; the namespace ships in `day8/re-frame2-http` (same artefact as the fx they reference) so loading the helpers and the fx are a single dep decision.

### Reply-payload shape

Every reply lands as `{:rf/reply {:kind :success :value v}}` or `{:rf/reply {:kind :failure :failure {:kind <:rf.http/*> ...}}}`. Default reply addressing dispatches `[<originating-event-id> (assoc original-msg :rf/reply ...)]` back to the same handler; explicit `:on-success` / `:on-failure` targets append the reply payload as the last event-vector arg. Both shapes detailed in [014 §Reply addressing](014-HTTPRequests.md#reply-addressing).

### Failure categories (closed set)

The eight `:kind` values inside a failure reply, all reserved under `:rf.http/*` (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). See [014 §Failure categories](014-HTTPRequests.md#failure-categories-closed-set) for tags-by-kind:

| `:kind` | Meaning |
|---|---|
| `:rf.http/transport` | Network / DNS / connection error pre-HTTP |
| `:rf.http/cors` | CORS preflight rejected (CLJS-only) |
| `:rf.http/timeout` | Per-attempt timeout fired |
| `:rf.http/http-4xx` | Non-2xx 4xx response |
| `:rf.http/http-5xx` | Non-2xx 5xx response |
| `:rf.http/decode-failure` | 2xx response but decode rejected the body |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}` |
| `:rf.http/aborted` | Request aborted via `:request-id` or `:abort-signal` |

### Trace events emitted by `:rf.http/managed`

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http/retry-attempt` | `:info` | Per intermediate attempt that matched `:retry :on`; carries `:attempt`, `:max-attempts`, `:failure`, `:next-backoff-ms` |
| `:rf.warning/decode-defaulted` | `:warning` | The request relied on `:decode :auto` (default); informational, not an error |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded; carries `:frame`, `:id` (per [014 §Middleware](014-HTTPRequests.md#middleware), rf2-6y3q) |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing slot; carries `:frame`, `:id` |
| `:rf.error/http-interceptor-failed` | `:error` | A request-interceptor `:before` threw; carries `:frame`, `:interceptor-id`, `:url`, `:cause`. The request is NOT dispatched (per [014 §Middleware §Failure mode](014-HTTPRequests.md#failure-mode), rf2-6y3q) |

### Schema-reflection metadata

Handlers may declare `:rf.http/decode-schemas [<schema> ...]` in their `reg-event-fx` metadata-map; pair tools and generators read it via `(rf/handler-meta :event id)`. Optional, never enforced — see [014 §Schema reflection](014-HTTPRequests.md#schema-reflection-optional-ergonomic).

---

## Effect-map shape

Closed: `:db` + `:fx` only. See [Spec-Schemas §:rf/effect-map](Spec-Schemas.md#rfeffect-map). Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` from v1 migrate via [MIGRATION.md §M-8](../migration/from-re-frame-v1/README.md).

| Key | Notes |
|---|---|
| `:db` | New `app-db` (replaces). |
| `:fx` | Vector of `[fx-id args]` pairs. |

Standard `:fx` entries:

| `[fx-id args]` | Args | Status | Spec | Notes |
|---|---|---|---|---|
| `[:dispatch [event-id ...]]` | event vector | v1 | 002 | |
| `[:dispatch-later {:ms ms :dispatch event-vec}]` | options map | v1 | 002 | |
| `[:http args]` | impl-specific | — | — | user-registered via `reg-fx`. |
| `[:rf.http/managed args-map]` | args per [014 §The args map](014-HTTPRequests.md#the-args-map) | v1 (optional capability) | 014 | Framework-provided when the implementation ships Spec 014. CLJS reference: ships on Fetch + JVM `HttpClient`. See also `:rf.http/managed-abort`, `:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`. |
| `[:rf.nav/push-url url-string]` | URL string | v1 | 012 | |
| `[:raise event-vec]` | event vector | v1 | 005 | *machine-only*: reserved fx-id recognised by the machine handler; routes the event back into the same machine, atomic and pre-commit. Outside a machine action's `:fx`, this fx-id is unbound. |
| `[:rf.machine/spawn spawn-spec]` | spawn-spec map (per `:rf.fx/spawn-args`: `:machine-id`/`:definition`, `:id-prefix`, `:data`, `:on-spawn`, `:start`) | v1 | 005 | Canonical actor-lifecycle fx (registered globally by `re-frame.machines`); registers a new dynamic actor (whose snapshot lives at `[:rf/machines <gensym'd-id>]`) and (via `:on-spawn`) records its id into the parent's `:data`. Emitted from any event handler's `:fx` (including machine actions and the `:spawn` desugar). |
| `[:rf.machine/destroy actor-id]` | actor id (keyword) | v1 | 005 | Canonical actor-destroy fx (registered globally by `re-frame.machines`); runs the actor's `:exit` action, dissociates `[:rf/machines <actor-id>]`, and clears the actor's event-handler registration. Symmetric counterpart to `:rf.machine/spawn`. |

---

## Public registrar query API

For tooling, agents, story tools, 10x.

| API | M/Fn | Signature | Status | JVM-runnable? | Spec |
|---|---|---|---|---|---|
| `registrations` | Fn | `(registrations kind)` / `(registrations kind pred-fn)` → `{id metadata-map}`. **Use when you want metadata** — registry walks that read source-coords, `:rf/sensitive`, `:rf/machine?`, `:platforms`, etc. | v1 | ✓ | 002 |
| `handler-ids` | Fn | `(handler-ids kind)` → id set (canonical alias for `(-> (registrations kind) keys set)`). **Use when you only need to enumerate** — completion lists, existence checks, set-shaped intersections; saves both the metadata-map allocations and the `keys` walk. | v1 | ✓ | 002 |
| `handler-meta` | Fn | `(handler-meta kind id)` → registration-metadata map. View registrations include source-coord keys (`:ns` / `:line` / `:column` / `:file`) per `:rf/source-coord-meta` ([Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta)); pair tools resolve `data-rf2-source-coord` DOM annotations to `:file` via this lookup. | v1 | ✓ | 002 |
| `machines` | Fn | `(machines)` → seq of machine-ids. Derived view over `(registrations :event)` filtered by `:rf/machine? true`. | v1 | ✓ | 005 |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration-metadata map (transition table, doc, schemas). Equivalent to `(handler-meta :event machine-id)`. | v1 | ✓ | 005 |
| `frame-ids` | Fn | `(frame-ids)` / `(frame-ids ns-prefix)` | v1 | ✓ | 002 |
| `frame-meta` | Fn | `(frame-meta frame-id)` | v1 | ✓ | 002 |
| `get-frame-db` | Fn | `(get-frame-db frame-id)` → app-db value (plain map) | v1 | ✓ | 002 |
| `snapshot-of` | Fn | `(snapshot-of path)` / `(snapshot-of path opts)` | v1 | ✓ | 002 |
| `sub-topology` | Fn | `(sub-topology)` → `{sub-id {:inputs [<input-sub-ids>] :doc :ns :line :file}}` — static dependency graph from `:<-` declarations. Pure data over the registrar; `:inputs` always present (empty for layer-1); the per-entry `:doc` / `:ns` / `:line` / `:file` keys are present when registration carries them. | v1 | ✓ | 002 |
| `sub-cache` | Fn | `(sub-cache frame-id)` → live cache state | v1 | ✗ (CLJS-only) | 002 |

Schema-introspection accessors — `app-schemas`, `app-schema-at`, `app-schemas-digest` — are rowed canonically in [§Schemas](#schemas).

`compute-sub` is rowed canonically in [§Testing](#testing) (pure sub computation against an `app-db` value).

---

## Schemas

> **Namespace:** the introspection surfaces below live in `re-frame.schemas` (artefact `day8/re-frame2-schemas`); consumers `(:require [re-frame.schemas :as schemas])`. They are not re-exported from `re-frame.core` — apps targeting schemas add the artefact and require the namespace directly. The registration macros (`reg-app-schema` / `reg-app-schemas`) live in `re-frame.core` and route through the schemas artefact at registration time. Per the §Conventions per-artefact namespace table.

`reg-app-schema` is rowed canonically in [§Registration](#registration).

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `app-schemas` | Fn | `(app-schemas)` / `(app-schemas {:frame frame-id})` | v1 | 010 |
| `app-schema-at` | Fn | `(app-schema-at path)` / `(app-schema-at path {:frame frame-id})` | v1 | 010 |
| `app-schema-meta-at` | Fn | `(app-schema-meta-at path)` / `(app-schema-meta-at path opts-or-frame-id)` — return the full registration-metadata map (`:path`, `:schema`, `:frame`, plus source-coords `:ns` / `:line` / `:file` and the rest of `:rf/registration-metadata`) for a registered `app-db` schema, or `nil`. Pair-tool and 10x consumers reach for this when they need the registration anchor (e.g. click-back-to-code); the lighter `app-schema-at` is the right call when only the schema value is needed. Per [010 §Schemas as a tooling/agent surface](010-Schemas.md) and [Spec-Schemas §`:rf/app-schema-meta`](Spec-Schemas.md#rfapp-schema-meta). | v1 | 010 |
| `app-schemas-digest` | Fn | `(app-schemas-digest)` / `(app-schemas-digest {:frame frame-id})` → string | v1 | 010 |
| `set-schema-validator!` | Fn | `(set-schema-validator! validate-fn)` / `(set-schema-validator! {:validate validate-fn :explain explain-fn})` — install the validator (and optionally the explainer) every dev-time schema-validation site routes through. `nil` disables validation entirely. Default ships Malli's `validate`/`explain` pair; this seam lets apps swap in their own validator to drop the Malli dep. Per [010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point). | v1 | 010 |
| `set-schema-explainer!` | Fn | `(set-schema-explainer! explain-fn)` — install the explainer used to enrich `:rf.error/schema-validation-failure` traces' `:explain` key. Companion to `set-schema-validator!`. Per [010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point). | v1 | 010 |
| `set-schema-printer!` | Fn | `(set-schema-printer! print-fn)` — install the **schema-print companion** the digest pipeline (per [010 §Schema digest](010-Schemas.md#schema-digest)) hashes. `print-fn` is `(fn [schema-value] canonical-string)` and MUST be pure + deterministic across runtimes. `nil` falls back to the default EDN canonicaliser so the digest is never undefined. Parallel to `set-schema-validator!` / `set-schema-explainer!`: non-Malli ports register their own serialiser so cross-runtime digest comparison reflects their port's contract. Per [010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point). | v1 | 010 |
| `at-boundary` | Var (interceptor value) | `at-boundary` — a **pre-built interceptor value**, not a fn (interceptor `:id` is `:rf.schema/at-boundary`). Add it to a `reg-event-*`'s positional interceptor vector for production-boundary validation; do **not** call it as a fn (it has no fn arity — invoking `(rf/at-boundary ...)` raises `ArityException`). | v1 | 010 |

See [010 §Schemas](010-Schemas.md) for `:schema` metadata, validation timing, and dev/prod elision. (v1's `:spec` metadata key is accepted as a deprecated alias for one cycle — per [MIGRATION §M-54](../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema-rf2-ieu0i).)

---

## Event-emit (always-on, production-survivable)

Per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production) (#2). A minimal always-on listener surface that survives `:advanced` + `goog.DEBUG=false` and delivers one tight record per processed event. The intended consumers are hosted observability back-ends (Datadog, Honeycomb, Sentry, …). Parallel to (not a fallback for) the dev-only trace surface; per-event only — no per-sub, per-fx, or per-`:event/db-changed` records. Record shape `{:event :event-id :frame :time :outcome :elapsed-ms}`; the `:event` slot is passed through [`rf/elide-wire-value`](#size-elision-wire-boundary-walker) once before fan-out, so schema-marked `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`.

> Sensitive data marking is path-based per the upcoming data-classification mechanism (separate spec doc; in progress). The legacy handler-meta `:sensitive?` annotation that previously drove substrate-level record drop has been removed (rf2-hjs2d).

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-event-emit-listener!` | Fn | `(register-event-emit-listener! id listener-fn)` — `listener-fn` receives one event-record per processed event (shape above). Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. | v1 | 009 |
| `unregister-event-emit-listener!` | Fn | `(unregister-event-emit-listener! id)` → nil | v1 | 009 |

## Error-emit (always-on, production-survivable)

Per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production) (#2 second paragraph). Sibling of the event-emit surface above (rf2-bacs4); runs through the SAME always-on error-emit substrate as the per-frame `:on-error` slot ([002-Frames.md](002-Frames.md)) but along an INDEPENDENT corpus-wide fan-out path. Survives `:advanced` + `goog.DEBUG=false`. Intended consumers are hosted error monitors (Sentry, Honeybadger, Rollbar). One tight record per `:rf.error/*` event the router emits through the handler-exception path; record shape `{:error :event :event-id :frame :time :exception :elapsed-ms}`. The `:event` slot is passed through [`rf/elide-wire-value`](#size-elision-wire-boundary-walker) once before fan-out, so schema-marked `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`. The two paths from the substrate (corpus-wide listeners AND the per-frame `:on-error` policy fn) are mutually isolated; either may throw without affecting the other.

> Sensitive data marking on the error-emit substrate is path-based per the upcoming data-classification mechanism (separate spec doc; in progress). The legacy handler-meta `:sensitive?` annotation that previously drove substrate-level event redaction has been removed (rf2-hjs2d) — the per-path elision wire-walker is now the sole redaction surface on this path.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-error-emit-listener!` | Fn | `(register-error-emit-listener! id listener-fn)` — `listener-fn` receives one error-record per `:rf.error/*` event (shape above). Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. | v1 | 009 |
| `unregister-error-emit-listener!` | Fn | `(unregister-error-emit-listener! id)` → nil | v1 | 009 |

## Tracing

All tracing is **dev-only** (elided in production). See [009 §Tracing](009-Instrumentation.md) for emit semantics and synchronous listener delivery.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-trace-listener!` | Fn | `(register-trace-listener! key callback-fn)` — `callback-fn` receives one trace event per call | v1 (dev-only) | 009 |
| `unregister-trace-listener!` | Fn | `(unregister-trace-listener! key)` → nil | v1 (dev-only) | 009 |
| `emit-trace-event!` | Fn | `(emit-trace-event! op-type operation tags)` → nil | v1 (dev-only) | 009 |
| `re-frame.interop/debug-enabled?` | Var | `^boolean`. **CLJS**: alias of `goog.DEBUG` — constant-folded by Closure under `:advanced`, so `:advanced` + `goog.DEBUG=false` builds DCE every `(when interop/debug-enabled? ...)` branch. **JVM**: a `def` read ONCE at ns-load from the Java system property `-Dre-frame.debug` (winning on conflict) or the environment variable `RE_FRAME_DEBUG`; defaults `true` (dev parity). Accepts the conventional false-y vocabulary case-insensitively (`false`, `0`, `no`, `off`, empty string) with whitespace trimmed; anything else leaves the flag at `true`. Set BEFORE `re-frame.interop` loads. SSR / webhook receivers / long-running JVMs facing untrusted input MUST set the gate `false` explicitly — per [009 §JVM builds](009-Instrumentation.md#jvm-builds) and [Security §Production gates](Security.md#production-gates). | v1 | 009 |
| `re-frame.performance/enabled?` | Var | `^boolean` `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in `performance.mark` + `performance.measure` calls (User-Timing entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). **Compile-time only** — not a `(rf/configure ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op. See [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation) and [Tool-Pair §Performance API consumption](Tool-Pair.md#performance-api-consumption) | v1 | 009 |
| `trace-buffer` | Fn | `(trace-buffer)` / `(trace-buffer opts)` → vector of trace events, oldest-first | v1 (dev-only) | 009 |
| `clear-trace-buffer!` | Fn | `(clear-trace-buffer!)` → nil | v1 (dev-only) | 009 |
| `(rf/configure :trace-buffer {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | 009 |
| `group-cascades` | Fn | `(group-cascades events)` → vector of cascade records `{:dispatch-id :event :handler :fx :effects :subs :renders :other}`, sorted by emission order. Pure data; JVM-runnable. Re-exported from `re-frame.trace.projection` (see [009 §Cascade projection](009-Instrumentation.md#cascade-projection-group-cascades--domino-bucket)). | v1 (dev-only) | 009 |
| `domino-bucket` | Fn | `(domino-bucket trace-event)` → `#{:event :handler :fx :effect :sub :render :other}`. Classifies a raw trace event into the six-domino slot used by `group-cascades`. Pure data. | v1 (dev-only) | 009 |

### Trace-emission opt-out (per-handler metadata)

Event-handler registration accepts a `:rf.trace/no-emit? true` metadata flag (rf2-qsjda). When set, the runtime suppresses **every** trace emission and event-emit record within the handler's scope — the handler runs invisibly to the trace surface, the event-emit substrate, and (transitively) the epoch buffer. Used by framework-internal bookkeeping handlers (Causa, Story, re-frame2-pair-mcp, story-mcp) that would otherwise saturate the trace stream. Per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) the `:rf.trace/*` namespace is framework-owned.

| Metadata key | Where | Value | Default | Effect |
|---|---|---|---|---|
| `:rf.trace/no-emit?` | `reg-event-db` / `reg-event-fx` / `reg-event-ctx` metadata map | boolean | `false` | When `true`, suppresses all trace + event-emit emissions inside the handler's scope. Per [009 §Trace-emission opt-out](009-Instrumentation.md#trace-emission-opt-out-rftraceno-emit-event-meta). |

### Epoch history (per Tool-Pair)

Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `epoch-history` | Fn | `(epoch-history frame-id)` → vector of epoch records. Returns `[]` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | Tool-Pair |
| `restore-epoch` | Fn | `(restore-epoch frame-id epoch-id)` → boolean (true on success). Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | Tool-Pair |
| `reset-frame-db!` | Fn | `(reset-frame-db! frame-id new-db)` → boolean (true on success) — pair-tool write surface (state injection). Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false` for an unknown / destroyed frame. | v1 (dev-only) | Tool-Pair |
| `register-epoch-listener!` | Fn | `(register-epoch-listener! key callback-fn)` — assembled-epoch listener. Process-global; a callback whose previously-observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | Tool-Pair, 009 |
| `unregister-epoch-listener!` | Fn | `(unregister-epoch-listener! key)` | v1 (dev-only) | Tool-Pair, 009 |
| `(rf/configure :epoch-history {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | Tool-Pair |
| `get-frame-db` (cross-ref to [§Public registrar query API](#public-registrar-query-api)) | Fn | Returns `nil` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 | 002 |

Trace events emitted by epoch-history machinery:

| `:operation` | Tags |
|---|---|
| `:rf.epoch/snapshotted` | `:frame`, `:epoch-id`, `:event-id` |
| `:rf.epoch/restored` | `:frame`, `:epoch-id` |
| `:rf.epoch/db-replaced` | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-unknown-epoch` | `:frame`, `:epoch-id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:frame`, `:epoch-id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:frame`, `:epoch-id`, `:missing` |
| `:rf.epoch/restore-version-mismatch` | `:frame`, `:epoch-id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-non-ok-record` | `:frame`, `:epoch-id`, `:outcome`, `:halt-reason` |
| `:rf.epoch/reset-frame-db-during-drain` | `:frame` |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `:frame`, `:failing-paths` |
| `:rf.epoch.cb/silenced-on-frame-destroy` | `:frame`, `:cb-id` |

<a id="elide-wire-value-the-wire-boundary-walker"></a>

### Size-elision wire-boundary walker

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) — `elide-wire-value` is named there as the **single normative emission site** for the `:rf/redacted` sentinel. Every off-box egress (trace forwarders, MCP servers, error monitors) routes through this walker; the trust-boundary surfaces catalogued in [Security.md](Security.md) compose against this primitive.

The framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots. Consumed by every tool that emits wire data (the off-box error-monitor forwarders, the Causa-MCP / re-frame2-pair-mcp / story-mcp servers per [Tool-Pair.md](Tool-Pair.md), the on-box dev panels). The walker is the **single normative emission site** for the `:rf/redacted` sensitive sentinel and the `:rf.size/large-elided` marker; per-tool reimplementation is prohibited.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `elide-wire-value` | Fn | `(elide-wire-value v opts)` → `v` or an elision-marker substitution. `opts` is a map: `{:rf.size/include-large? <bool> :rf.size/include-sensitive? <bool> :rf.size/include-digests? <bool> :rf.size/threshold-bytes <int> :path [...] :frame <frame-id>}`. Defaults: both `include-*` flags `false` (maximum elision); `:rf.size/threshold-bytes` falls back to `(rf/configure :elision ...)` then `16384`. Walks `v` consulting `[:rf/elision :declarations]` and `[:rf/elision :sensitive-declarations]` of the named frame's `app-db`; substitutes `:rf/redacted` for sensitive slots and `:rf.size/large-elided` markers for large slots. Composition rule (normative): when both predicates match the **sensitive drop wins** — the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest`. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) and [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker). | v1 | 009 |
| `elision-declarations` | Fn | `(elision-declarations)` / `(elision-declarations frame-id)` → the current `[:rf/elision :declarations]` map for the frame (or `{}`). Pair-tool / introspection reader for paths nominated for elision (schema-sourced). Default frame is `:rf/default`. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). | v1 | 009 |
| `populate-elision-from-schemas!` | Fn | `(populate-elision-from-schemas!)` / `(populate-elision-from-schemas! frame-id)` → vector of paths populated (possibly empty). Boot-time hydrator that walks the frame's registered app-schemas and writes `{:large? true :source :schema}` declarations for every path whose Malli schema carries `:large? true`. Idempotent. No-op when the schemas artefact (day8/re-frame2-schemas) is not on the classpath. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). | v1 | 009 |

**Schema-only declaration path (rf2-w0n68).** The `[:rf/elision]` registry has exactly two slots: `:declarations` (schema-derived `:large?` paths, populated by `populate-elision-from-schemas!`) and `:sensitive-declarations` (schema-derived `:sensitive?` paths). There is no runtime declaration API — apps declare `:large?` / `:sensitive?` on the Malli schema and `rf/reg-app-schema` it; the boot-time hydrator does the rest. Per [docs/guide/23b-large-blobs.md](../docs/guide/23b-large-blobs.md) — the canonical statement of "schemas are the only path" — and `implementation/core/src/re_frame/elision.cljc` L4-6.

### DOM source-coord annotations (mandatory; rf2-z7f7 / rf2-z9n1)

Per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) and [Tool-Pair §Source-mapping](Tool-Pair.md), every adapter whose host has a DOM-attribute concept MUST inject `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. Format and exemptions (Fragments, non-DOM roots) are documented in Spec 006 §Source-coord annotation. Annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via dead-code elimination — there is no DOM-bytes cost in shipped bundles. The JVM SSR emitter mirrors the same contract per [Spec 011 §Source-coord annotation under SSR](011-SSR.md#source-coord-annotation-under-ssr).

### Error contract

Errors are emitted as structured trace events with `:op-type :error` (or `:warning` / `:info` / `:fx` / `:flow` / `:frame`) and a per-category `:operation` keyword. The complete normative catalogue — every `:rf.error/*`, `:rf.warning/*`, `:rf.fx/*`, `:rf.cofx/*`, `:rf.ssr/*`, `:rf.epoch/*`, `:rf.flow/*`, `:rf.http/*`, `:rf.http.interceptor/*`, `:rf.frame/*`, and `:rf.route.nav-token/*` event the runtime emits — lives at [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (single source of truth for category names, `:op-type` discriminator, trigger conditions, default `:recovery`, and `:tags` payload keys). Per-category Malli `:tags` schemas are canonicalised at [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas) — one schema per catalogue row.

Recent additions consumers should be aware of: `:rf.ssr/version-mismatch`, `:rf.ssr/schema-digest-mismatch`, `:rf.ssr/compatibility-check-skipped` (the SSR hydration compatibility-check trio, per rf2-69ad2), and `:rf.cofx/skipped-on-platform` (the platform-gating mirror of `:rf.fx/skipped-on-platform`). The catalogue at 009 is the single source of truth — do not duplicate the table here.

Per-Spec emit-sites: [002-Frames](002-Frames.md), [005-StateMachines](005-StateMachines.md), [006-ReactiveSubstrate](006-ReactiveSubstrate.md), [010-Schemas](010-Schemas.md), [011-SSR](011-SSR.md), [012-Routing](012-Routing.md), [013-Flows](013-Flows.md), [014-HTTPRequests](014-HTTPRequests.md), [Tool-Pair](Tool-Pair.md). Each catalogue row's "Per [N]" cross-link names the owning Spec section.

### Privacy (Spec 009 §Privacy / sensitive data in traces)

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the framework-wide pattern-level posture and the two composition sites (`redact-interceptor` + per-slot schema meta); the trust-boundary catalogue lives in [Security.md](Security.md). The **cross-artefact inventory + composition order** (every privacy surface in `re-frame.core`, `re-frame.http`, `re-frame.schemas`, `re-frame.epoch`, `tools/mcp-base`, with the data-flow from handler exit to off-box wire) lives in [Privacy.md](Privacy.md).

Per [Spec 009 §Privacy](009-Instrumentation.md) the runtime stamps `:sensitive? true` at the top level of every trace event emitted inside the scope of a handler whose schema-derived path overlap declares sensitivity. (The legacy handler-meta `:sensitive?` annotation has been removed per rf2-hjs2d; sensitive data marking is path-based per the upcoming data-classification mechanism — separate spec doc; in progress.) Framework-published trace consumers (Sentry/Honeybadger forwarders, re-frame2-pair server, Causa, Story, story-mcp, re-frame2-pair-mcp) MUST default-drop the stamped events at their egress boundary. `redact-interceptor` is the in-place payload scrub composed alongside the stamp.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `sensitive?` | Fn | `(sensitive? trace-event)` → `boolean`. True iff `trace-event` is a map carrying `:sensitive? true` at the top level (not under `:tags`). The framework-published predicate every consumer composes against — replaces per-consumer reimplementations of the same five-token check (rf2-sqxjn). | v1 | 009 |
| `redact-interceptor` | Fn | `(redact-interceptor paths)` → interceptor. Build a positional interceptor that overwrites the named keys in the event vector's payload map with the `:rf/redacted` sentinel before the handler chain runs. The handler body itself sees the UNREDACTED payload via the regular `:event` coeffect slot; the redaction is for the trace surface only. `paths` is a vector of `get-in`-style key paths into the payload map. | post-v1 (planned, rf2-461sp) | 009 |

---

## Spec-internal schemas

Per [Spec-Schemas.md](Spec-Schemas.md), the spec's own runtime shapes are described as Malli schemas registered at runtime. These are the **conformance contract** an implementation validates against.

| Schema | Describes | Spec |
|---|---|---|
| `:rf/dispatch-envelope` | Internal envelope wrapping every dispatch | 002 |
| `:rf/dispatch-opts` | The user-facing opts map for `dispatch` / `dispatch-sync` / `subscribe` | 002 |
| `:rf/registration-metadata` | Common metadata-map shape across `reg-*` | 001 / 010 |
| `:rf/effect-map` | Return value of `reg-event-fx` handlers — **closed**: only `:db` and `:fx` | 002 |
| `:rf/trace-event` | Universal trace event shape | 009 |
| `:rf/error-event` | Refinement of `:rf/trace-event` for `:op-type :error` / `:warning` (unified error/warning envelope) | 009 |
| `:rf/handler-body-dsl` | Conformance corpus handler-body DSL (host-agnostic event/sub bodies; small-DSL grammar) | 008 / Spec-Schemas |
| `:rf/transition-table` | State-machine transition table grammar | 005 |
| `:rf/machine-snapshot` | Runtime snapshot of a machine instance | 005 |
| `:rf/hydration-payload` | Wire format for SSR hydration | 011 |
| `:rf/response` | HTTP-response accumulator owned by the request frame during SSR | 011 |
| `:rf.server/cookie` | Structured-cookie shape for `:rf.server/set-cookie` / `:rf.server/delete-cookie` | 011 |
| `:rf/head-model` | SSR head/meta data model (title, meta, link, json-ld, html/body attrs) | 011 |
| `:rf/public-error` | Sanitised, client-safe projection of an internal error trace event | 011 |
| `:rf.fx.server/set-status-args` / `:rf.fx.server/set-header-args` / `:rf.fx.server/append-header-args` / `:rf.fx.server/set-cookie-args` / `:rf.fx.server/delete-cookie-args` / `:rf.fx.server/redirect-args` | Args of standard `:rf.server/*` SSR fx | 011 |
| `:rf/frame-meta` | Returned by `(frame-meta frame-id)` | 002 |
| `:rf/variant` | Story-variant artefact contract (post-v1 lib) — variants are data, no fn-valued slots | 007 |
| `:rf/epoch-record` | Per-frame epoch snapshot record (Tool-Pair) | Tool-Pair |
| `:rf.fx/dispatch-args` | Args of standard `:dispatch` fx (and `:raise`, same shape) | 002 / 005 |
| `:rf.fx/dispatch-later-args` | Args of standard `:dispatch-later` fx | 002 |
| `:rf.fx/http-args` | Args of `:http` fx (user-owned recommendation) | Pattern-RemoteData |
| `:rf.fx/nav/push-url-args` | Args of `:rf.nav/push-url` fx | 012 |
| `:rf.fx/nav/replace-url-args` | Args of `:rf.nav/replace-url` fx | 012 |
| `:rf.fx/nav/scroll-args` | Args of `:rf.nav/scroll` fx | 012 |
| `:rf.fx/with-nav-token-args` | Args of `:rf.route/with-nav-token` fx wrapper | 012 |
| `:rf.fx/spawn-args` | Args of `:rf.machine/spawn` fx (the canonical actor-lifecycle fx-id; emitted from any event handler's `:fx`) | 005 |
| `:rf.fx/managed-args` | Args of `:rf.http/managed` fx (request envelope, decode, accept, retry, timeout-ms, on-success/on-failure, request-id, abort-signal) | 014 |
| `:rf.fx/managed-abort-args` | Args of `:rf.http/managed-abort` fx (request-id) | 014 |
| `:rf.http/reply` | Reply-payload envelope `{:kind :success :value v}` / `{:kind :failure :failure {:kind <:rf.http/*> ...}}` lands under `:rf/reply` | 014 |
| `:rf/route-rank` | Structural-rank tuple for route-precedence sorting | 012 |
| `:rf/pending-navigation` | Pending-navigation slot when `:can-leave` guard rejects | 012 |
| `:rf/elision-registry` | Per-frame size-elision declaration registry under reserved app-db key `:rf/elision` | 009 |
| `:rf/elision-marker` | Wire shape `rf/elide-wire-value` substitutes for an elided large value (`:rf.size/large-elided`) | 009 |

Schemas are **open** by default (consumers tolerate unknown keys; producers grow shapes additively); `:closed true` is opt-in at boundary-validation sites and on the effect-map.

---

## Testing

The testing surface lives across three namespaces. `re-frame.core` carries the production primitives that double as testing entry points (`make-frame`, `with-frame`, `dispatch-sync`, `with-fx-overrides`, `get-frame-db`, `snapshot-of`, `compute-sub`, `machine-transition`, `sub-topology`). `re-frame.test-support` ships the test-only fixture machinery and test-flavoured helpers. `re-frame.test-helpers` ships the view-assertion helpers (hiccup-walk + `testid` authoring). `re-frame.test-support` does **not** re-export from `re-frame.core` — a test file requires both `[re-frame.core :as rf]` and `[re-frame.test-support :as ts]`, and additionally `[re-frame.test-helpers :as th]` for view-assertion tests. See [008-Testing.md](008-Testing.md) for fixtures, framework adapters, and `re-frame-test` compatibility.

| API | M/Fn | Signature | Status | Spec | Notes |
|---|---|---|---|---|---|
| `dispatch-sequence` | Fn | `(dispatch-sequence events)` / `(dispatch-sequence events opts)` | v1 | 008 | `opts`: `:after-each (fn [db ev] ...)`, `:frame`. Returns final `app-db`. Lives in `re-frame.test-support`. |
| `assert-state` | Fn | `(assert-state expected-db)` / `(assert-state path expected-val)` / either form `+ {:frame ...}` opts | v1 | 008 | Mismatch fires `clojure.test/is`-style failure via `do-report`. Lives in `re-frame.test-support`. **Sibling surface — `:rf.assert/*` event family** (`:rf.assert/path-equals`, `:rf.assert/sub-equals`, `:rf.assert/state-is`, `:rf.assert/dispatched?`, `:rf.assert/no-warnings`, `:rf.assert/effect-emitted`, `:rf.assert/path-matches`) used inside a Story `:play` block — see [007 §Play functions](007-Stories.md#play-functions). Argument shapes are isomorphic; runner and reporting channel differ. Choose by test surface: `assert-state` from a `deftest` body, `:rf.assert/*` from a story variant's `:play` vector. |
| `poll-until` | Fn | `(poll-until pred)` / `(poll-until pred opts)` | v1 | 008 | Bounded-deadline poll. JVM: synchronous — returns the truthy value, throws `ex-info` with `:rf.test/poll-timeout true` on timeout. CLJS: returns a `js/Promise` resolving with the truthy value or rejecting on timeout. Opts: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`. Lives in `re-frame.test-support`. |
| `with-fx-overrides` | M | `(with-fx-overrides {fx-id -> override, …} body+)` | v1 | 002, 008 | Lexical-scope `:fx-overrides` binding (rf2-5uwl). Every `dispatch` / `dispatch-sync` inside the body merges the supplied map into its envelope's `:fx-overrides`. Precedence: per-call opt > lexical `with-fx-overrides` > per-frame `:fx-overrides`. Composes with `with-frame`. Lives in `re-frame.core`. Renamed from `with-overrides` per [MIGRATION §M-50](../migration/from-re-frame-v1/README.md#m-50-with-overrides-macro-renamed-to-with-fx-overrides). |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | 008 | Pure sub computation against an `app-db` value. Lives in `re-frame.core`. |
| `snapshot-registrar` / `restore-registrar!` / `with-fresh-registrar` / `reset-runtime-fixture-factory` | Fn | per docstring | v1 | 008 | Fixture machinery. Lives in `re-frame.test-support`. |

### Testing — view-assertion helpers

`re-frame.test-helpers` ships the hiccup-walk view-assertion surface — call the view-fn directly, walk the returned hiccup, assert on content or invoke a handler. JVM-runnable; no JSDOM, no React, no `act()`. Pairs with `render-to-string` (the HTML-string view-test path per Spec 011): hiccup-walk for structure / handler assertions, `render-to-string` for HTML-markup assertions. Per [008-Testing §View-assertion helpers](008-Testing.md#view-assertion-helpers-re-frametest-helpers).

| API | M/Fn | Signature | Status | Spec | Notes |
|---|---|---|---|---|---|
| `expand-tree` | Fn | `(expand-tree tree) → tree` | v1 | 008 | Recursively expand fn-components and Form-3 class components inside a hiccup tree. After expansion every vector's first element is a keyword tag or a non-component value. Lives in `re-frame.test-helpers`. |
| `attrs` | Fn | `(attrs node) → map?` | v1 | 008 | Return the attrs map of a hiccup node, or `nil`. Lives in `re-frame.test-helpers`. |
| `children` | Fn | `(children node) → vector` | v1 | 008 | Return the child elements — everything after the tag (and optional attrs map). Lives in `re-frame.test-helpers`. |
| `text-content` | Fn | `(text-content node) → string` | v1 | 008 | Recursively collect string leaves under `node` and join. Numbers coerce to strings; nils are skipped. Lives in `re-frame.test-helpers`. |
| `extract-handler` | Fn | `(extract-handler node event-key) → fn?` | v1 | 008 | Return the value of `event-key` from `node`'s attrs map, or `nil`. Lives in `re-frame.test-helpers`. |
| `find-by-attr` | Fn | `(find-by-attr tree attr val) → node?` | v1 | 008 | First hiccup node whose attrs map carries `attr == val`, or `nil`. Generic over the attribute keyword (`:data-testid`, `:data-test`, `:id`, custom). Lives in `re-frame.test-helpers`. |
| `find-all-by-attr` | Fn | `(find-all-by-attr tree attr val) → vector` | v1 | 008 | Every matching node, in depth-first order. Lives in `re-frame.test-helpers`. |
| `find-by-attr-prefix` | Fn | `(find-by-attr-prefix tree attr prefix) → vector` | v1 | 008 | Every node whose `attr` value (a string) STARTS with `prefix`. Non-string attr values do not match. Lives in `re-frame.test-helpers`. |
| `find-by-testid` | Fn | `(find-by-testid tree test-id) → node?` | v1 | 008 | Convenience over `find-by-attr` keyed on `:data-testid`. Lives in `re-frame.test-helpers`. |
| `find-all-by-testid` | Fn | `(find-all-by-testid tree test-id) → vector` | v1 | 008 | Convenience over `find-all-by-attr` keyed on `:data-testid`. Lives in `re-frame.test-helpers`. |
| `find-by-testid-prefix` | Fn | `(find-by-testid-prefix tree prefix) → vector` | v1 | 008 | Convenience over `find-by-attr-prefix` keyed on `:data-testid`. Lives in `re-frame.test-helpers`. |
| `invoke-handler` | Fn | `(invoke-handler node event-key & args) → any` | v1 | 008 | Find the handler under `event-key` on `node` and call it with `args`. Returns the handler's return value. THROWS when `node` is not a hiccup vector, the node has no attrs map, or no handler is registered — the throwing failure mode is deliberate (a missing handler is almost always a test bug). Lives in `re-frame.test-helpers`. |
| `testid` | Fn | `(testid id)` / `(testid id extra) → map` | v1 | 008 | Build an attrs map carrying `:data-testid id`. The 2-arity merges `extra` into the map; `:data-testid` always wins on collision. Authoring helper at the view call site. Lives in `re-frame.test-helpers`. |

---

## Standard interceptors

The v2 std-interceptor surface is **three specific helpers** plus the `->interceptor` primitive. The principled line: keep helpers that do specific, non-trivial work; drop helpers that are just `(->interceptor :before f)` or `(->interceptor :after f)` with no other logic. Five interceptors removed: `debug`, `trim-v`, `on-changes`, `enrich`, `after` (per [MIGRATION §M-21](../migration/from-re-frame-v1/README.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)).

| API | M/Fn | Signature | Purpose |
|---|---|---|---|
| `inject-cofx` | M | `(inject-cofx id)` / `(inject-cofx id value)` | Inject a registered cofx into the handler's coeffect map. Macro per rf2-ts1a — captures call-site for `:rf.trace/call-site` on errors emitted from inside the cofx body. Specific work — `:cofx` registry lookup, not subsumable by `->interceptor`. |
| `inject-cofx*` | Fn | `(inject-cofx* id)` / `(inject-cofx* id value)` | Fn form (rf2-ts1a) for HoF / programmatic interceptor construction — no call-site stamping. |
| `path` | Fn | `(path & path)` | Focus a handler on an `app-db` sub-slice. Specific work — `:before` focuses, `:after` splices the result back into parent db. |
| `unwrap` | (val) | `unwrap` | Assert `[id payload-map]` event shape; replace `:event` coeffect with the payload map; restore on `:after`. Sugar over the M-19 canonical map-payload form. |
| `->interceptor` | Fn | `(->interceptor & {:keys [id before after]})` | The primitive. Build a custom interceptor with `:before` and/or `:after` slots. **Use this for any work not covered by the three specific helpers above** — analytics, logging, validation, ad-hoc context manipulation. The resulting interceptor is named, addressable, and queryable like any other artefact. |

Removed in v2 (see [MIGRATION §M-21](../migration/from-re-frame-v1/README.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)):

| Removed API | Replaced by |
|---|---|
| `debug` | Trace surface ([009](009-Instrumentation.md)) + 10x / re-frame-pair |
| `trim-v` | Canonical map-payload call shape ([M-19](../migration/from-re-frame-v1/README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)) |
| `on-changes` | Flows ([Spec 013](013-Flows.md)) |
| `enrich` | Flows (derived state) / `:schema` (validation) / custom `->interceptor` (escape hatch) |
| `after` | Registered fx (`:fx [[:my-fx ...]]`) for side-effects; custom `->interceptor` for context-shaped work; vendor from v1 if the helper is wanted as a local utility |

### `reg-flow` / `clear-flow` (Spec 013)

`reg-flow` is rowed canonically in [§Registration](#registration); required flow-map keys are `:id`, `:inputs`, `:output`, `:path`. Both surfaces take an optional opts map (`{:frame frame-id}`) selecting the owning frame: `(reg-flow flow)` / `(reg-flow flow opts)` and `(clear-flow id)` / `(clear-flow id opts)`. `clear-flow` is rowed canonically in [§Clearing registrations](#clearing-registrations); it deregisters the flow from the named frame and `dissoc-in`s its `:path` from that frame's `app-db` only (per Spec 013 §Frame-scoping).

**Frame-destroy teardown.** `destroy-frame!` releases every per-frame piece of flow state (registry slot, `last-inputs` rows, registrar entries for ids the destroyed frame was last owner of) per [Spec 013 §Frame-destroy teardown](013-Flows.md#frame-destroy-teardown). Sibling frames' state is preserved.

**Flow-eval failures in production.** A throw inside a flow's `:output` fn surfaces as `:rf.error/flow-eval-exception` on the **always-on error-emit substrate** — registered `:on-error` policy fns and `register-error-emit-listener!` callbacks fire under CLJS `:advanced` + `goog.DEBUG=false`. The error is NOT trace-only. Per [Spec 013 §Failure semantics](013-Flows.md#failure-semantics) rule 4 and [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code).

Reserved fx-ids for runtime flow management via `:fx`:

| Name | Kind | Signature | Status |
|---|---|---|---|
| `:rf.fx/reg-flow` | Reserved fx-id | `[:rf.fx/reg-flow flow-map]` — register a flow at runtime via `:fx` | v2 |
| `:rf.fx/clear-flow` | Reserved fx-id | `[:rf.fx/clear-flow id]` — clear a registered flow via `:fx` | v2 |

---

## Interceptor / context plumbing

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `get-coeffect` | Fn | `(get-coeffect ctx)` / `(get-coeffect ctx key)` / `(get-coeffect ctx key not-found)` | v1 (preserved) |
| `assoc-coeffect` | Fn | `(assoc-coeffect ctx key value)` | v1 (preserved) |
| `get-effect` | Fn | `(get-effect ctx)` / `(get-effect ctx key)` / `(get-effect ctx key not-found)` | v1 (preserved) |
| `assoc-effect` | Fn | `(assoc-effect ctx key value)` | v1 (preserved) |

---

## Lifecycle / utility

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `init!` | Fn | `(init! adapter-map)` — idempotent boot. Required arg: the adapter spec map. Each adapter ns exports an `adapter` Var; consumers require the ns and pass the Var, e.g. `(rf/init! reagent/adapter)`. Calling `(init!)` with no args raises a language-level `ArityException` at compile/load time (rf2-3ubmv — the no-arg arity was cut so the missing-adapter mistake surfaces before runtime). Calling `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified` at runtime. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) and rf2-agql. Ensures `:rf/default` frame is present | v1 | 006 |
| `install-adapter!` | Fn | `(install-adapter! adapter-map)` — must be called before any frame is created. Lower-level than `init!`; most consumers call `init!` instead | v1 | 006 |
| `destroy-adapter!` | Fn | `(destroy-adapter!)` — tear down the installed adapter. Calls the adapter spec's `:dispose-adapter!` fn (if present), clears the install slot so a new adapter can install, and flips the `adapter-disposed?` breadcrumb. Per [Conventions §Tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-) — `destroy-` cluster (lifecycle boundary; symmetric with `install-adapter!` and with `destroy-frame!`). The adapter-spec **map key** `:dispose-adapter!` (an internal contract slot adapters implement) is unchanged. | v2 | 006 |
| `current-adapter` | Fn | `(current-adapter)` → discriminator keyword (`:rf.adapter/reagent` / `:rf.adapter/reagent-slim` / `:rf.adapter/uix` / `:rf.adapter/helix` / `:rf.adapter/plain-atom` / `:rf.adapter/ssr` / `:custom`) or `nil` when no adapter is installed. Answers "what substrate am I on?" — predicate / branch code. For the spec map (fn handles, identity checks), use `current-adapter-spec`. | v1 | 006 |
| `current-adapter-spec` | Fn | `(current-adapter-spec)` → the installed adapter spec map (the value passed to `(rf/init! ...)`) or `nil` when no adapter is installed. Answers "give me the adapter fns to call" — tools, routing, identity checks across the install/dispose lifecycle. For the discriminator keyword, use `current-adapter`. | v1 | 006 |
| `adapter-disposed?` | Fn | `(adapter-disposed?)` → `true` iff the most recent lifecycle event was a successful `destroy-adapter!` and no subsequent `install-adapter!` has fired. `false` for never-installed (fresh process) and after a fresh install. Read-only — the breadcrumb is owned by the install / destroy pair. Use to distinguish `:rf.error/no-adapter-installed` (fresh process) from `:rf.error/adapter-disposed` (torn down). Per [006 §Disposed-vs-never-installed](006-ReactiveSubstrate.md#disposed-vs-never-installed-rf2-6wxys), rf2-6wxys. | v1 | 006 |
| `configure` | Fn | `(configure key opts)` — runtime config; key vocabulary in [§Configure keys](#configure-keys). One of three orthogonal configuration surfaces per [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) (`configure` for process-level data knobs; `set-!` / `install-!` for adapter-pluggable hooks; per-frame metadata for frame-scoped overrides). | v1 | — |

---

## Configure keys

Runtime configuration is uniformly via `(rf/configure <key> <opts>)`. Every framework-owned key is enumerated here. Keys are plural-noun-shaped; opts are an open map of per-key settings.

| Key | Opts shape | Default | Status | Spec |
|---|---|---|---|---|
| `:epoch-history` | `{:depth N :trace-events-keep N :redact-fn (fn [record] …)}` — `:depth` non-negative integer (0 disables the ring); `:trace-events-keep` non-negative integer caps raw `:trace-events` retention (per [Security §Epoch privacy posture](Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)); `:redact-fn` is `fn?` or `nil` — invoked once per assembled record at build-time (between `build-record` and ring-append / listener fan-out) so ring + listeners see the same redacted shape, with the `:rf.epoch/sensitive?` rollup computed first, throws caught and surfaced as `:rf.warning/epoch-redact-fn-exception` with fallback to the raw record. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo). | `{:depth 50, :redact-fn nil}` | v1 (dev-only) | Tool-Pair |
| `:trace-buffer` | `{:depth N}` — non-negative integer; 0 disables | `{:depth 200}` | v1 (dev-only) | 009 |
| `:sub-cache` | `{:grace-period-ms N}` — non-negative integer; 0 selects synchronous disposal | `{:grace-period-ms 50}` | v1 | 006 |
| `:elision` | `{:rf.size/threshold-bytes N}` — non-negative integer; 0 disables runtime auto-detect (only declared / schema entries elide) | `{:rf.size/threshold-bytes 16384}` | v1 | 009 |

SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure` key — it is per-frame metadata on the frame's `:ssr` map (see [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) bucket 3 and [011 §Server error projection](011-SSR.md#server-error-projection)). Different frames in the same process can carry different projector / dev-detail settings, so the natural lifetime is per-frame, not process-global.

### Opts-key naming rule

The opts map for any `configure` key mixes two shapes deliberately, and the choice is **not** stylistic — it encodes which contract owns the sub-key:

- **Framework-owned semantic sub-keys use a namespaced keyword** under a reserved `:rf.<area>/*` sub-namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). The namespace identifies the cross-spec policy area the sub-key participates in — the same key shape appears verbatim wherever that policy is consumed, not only inside `configure`. Example: `:elision` carries `{:rf.size/threshold-bytes N}` because `:rf.size/threshold-bytes` is the same per-call policy key consumed by `rf/elide-wire-value` and the wire-elision walker (per [Conventions §Reserved namespaces — `:rf.size/*`](Conventions.md#the-single-root-reserved-set)). The namespaced form makes the cross-surface identity grep-visible and prevents collision with adjacent per-knob settings.
- **Ergonomic per-knob sub-keys are unqualified bare keywords** (`:depth`, `:grace-period-ms`, `:trace-events-keep`, `:redact-fn`). These sub-keys are local to a single `configure` key's opts map — they do not appear elsewhere in the framework's vocabulary, so a framework-owned namespace would add noise without adding identity. The bare form is the default at this leaf position; reach for it whenever the knob is unique to one `configure` key.

The discriminator is **whether the sub-key names a cross-surface policy slot or a one-off knob**. A sub-key earns a `:rf.<area>/*` namespace when it names a contract that lives in more than one place (`:rf.size/threshold-bytes` is read by `:elision`, by `elide-wire-value`, and by the MCP wire walker). A sub-key stays bare when it is local to its parent `configure` key (`:depth` under `:trace-buffer` has nothing to do with `:depth` under `:epoch-history` — same English word, separate knobs, no shared contract).

New `configure` keys MUST apply the same rule: if a sub-key participates in a cross-spec policy area, qualify it under the area's reserved namespace; otherwise leave it bare. The rule is closed — there is no third shape (no `:configure/depth`, no `:rf.configure/*` prefix). A sub-key that would want a third shape is evidence the proposed knob is doing two things and should be split.

### Fixed-and-additive

The configure-keys vocabulary is fixed-and-additive (Spec-ulation): existing keys cannot be renamed or removed; new keys are added by extending the table. User code that wraps `configure` should pattern-match on known keys and ignore unknown ones.

---

## Machines

Split between the v1 machine-as-event-handler foundation and the post-v1 `re-frame.machines` scaffolding library — see [005-StateMachines.md §Disposition](005-StateMachines.md#disposition). The machine *is* the event handler: a machine is registered as one `reg-event-fx` whose body comes from `make-machine-handler`.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` — registers a machine as an event handler. Walks the literal spec form at expansion time and stamps per-element source coords under `:rf.machine/source-coords` (rf2-8bp3). | v1 | 005 |
| `reg-machine*` | Fn | `(reg-machine* machine-id machine-spec)` — plain-fn surface beneath the macro. No source-coord walking. | v1 | 005 |
| `make-machine-handler` | Fn | `(make-machine-handler spec)` → event-handler fn | v1 | 005 |
| `machine-transition` | Fn | `(machine-transition definition snapshot event)` → `[next-snapshot effects]` | v1 | 005 |
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot | v1 | 005 |
| `machines` | Fn | `(machines)` → seq of registered machine-ids | v1 | 005 |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration metadata; carries `:rf.machine/source-coords` index when registered via the macro | v1 | 005 |
| `machine-by-system-id` | Fn | `(machine-by-system-id system-id)` / `(machine-by-system-id system-id frame-id)` → spawned-machine id bound to `system-id` in the frame's `[:rf/system-ids]` reverse index (or `nil`). Per [005 §Named addressing via `:system-id`](005-StateMachines.md). | v1 | 005 |
| `dispatch-to-system` | Fn | `(dispatch-to-system system-id event)` / `(dispatch-to-system system-id event frame-id)` — sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`; no-op when the system-id is unbound. Per [005 §Cross-machine messaging by name](005-StateMachines.md). | v1 | 005 |
| `machine-has-tag?` | Fn | `(machine-has-tag? machine-id tag)` → reaction whose value is `true` iff the machine's snapshot's `:tags` set contains `tag`. Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`. Per [005 §State tags (rf2-ee0d / Nine States Stage 1)](005-StateMachines.md). | v1 | 005 |
| `wants-ctx` | Fn | `(wants-ctx fn)` → same fn with `:rf.machine/wants-ctx true` metadata attached. Sugar over the `^:rf.machine/wants-ctx` reader-macro for anonymous-fn / combinator call sites where source-form metadata is awkward. Per [005 §3-arity escape hatch (rf2-2yupx)](005-StateMachines.md) and rf2-b73dm re-export. | v1 | 005 |
| `:rf.machine/spawn` (fx) | — | Canonical actor-lifecycle fx (registered globally by `re-frame.machines`). Args per `:rf.fx/spawn-args`. | v1 | 005 |
| `:rf.machine/destroy` (fx) | — | Canonical actor-destroy fx (registered globally by `re-frame.machines`). Args: an actor id. | v1 | 005 |
| `:raise` (fx) | — | Reserved fx-id inside a machine action's `:fx` (machine-internal, routed pre-commit). Args: an event vector. | v1 | 005 |
| `:final?` / `:output-key` (state-node keys) | — | `:final?` marks a leaf state as terminal — entering it auto-destroys the machine. `:output-key` (requires `:final?`) designates the child's `:data` slot reported back via the parent's `:on-done`. Capability axis `:fsm/final-states`. Per rf2-gn80; see [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key). | v1 | 005 |
| `:on-done` (`:spawn` spec key) | — | `(fn [data result] new-data)` on the parent's `:spawn` map. Fires synchronously when the spawned child enters a `:final?` state; `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). Per rf2-gn80. | v1 | 005 |
| `:child-machine` (transition-table key) | — | Declarative state-scoped child-machine binding. | post-v1 lib | 005 |
| `machine->xstate-json` | Fn | `(machine->xstate-json definition)` → JSON | post-v1 lib | 005 |
| `machine->mermaid` | Fn | `(machine->mermaid definition)` → string | post-v1 lib | 005 |

Canonical descriptions (factory purity, spec keys, snapshot location, registration-time validation, etc.) in [005-StateMachines.md](005-StateMachines.md) and [Spec-Schemas](Spec-Schemas.md).

v1 transition-table grammar subset is enumerated in [005 §Capability matrix](005-StateMachines.md#capability-matrix); shape in [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table).

### Standard registered subs (machines)

| Standard sub | Returns | Spec |
|---|---|---|
| `[:rf/machine <machine-id>]` | The machine's snapshot `{:state :data}` (or `nil` if not yet initialised) | 005 |

`sub-machine` is sugar over the registered `:rf/machine` sub — see [005 §Subscribing to machines](005-StateMachines.md#subscribing-to-machines-via-sub-machine).

---

## Story / variant / workspace library (post-v1)

See [007-Stories.md](007-Stories.md).

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg-story` | M | `(reg-story id metadata)` | post-v1 lib | 007 |
| `reg-variant` | M | `(reg-variant id metadata)` | post-v1 lib | 007 |
| `reg-workspace` | M | `(reg-workspace id metadata)` | post-v1 lib | 007 |
| `reg-tag` | M | `(reg-tag id metadata)` | post-v1 lib | 007 |
| `reg-decorator` | M | `(reg-decorator id metadata)` | post-v1 lib | 007 |
| `reg-story-panel` | M | `(reg-story-panel id metadata)` | post-v1 lib | 007 |
| `run-variant` | Fn | `(run-variant variant-id)` → result map | post-v1 lib | 007 |
| `watch-variant` | Fn | `(watch-variant variant-id)` → live-updating result map | post-v1 lib | 007 |
| `reset-variant` | Fn | `(reset-variant variant-id)` | post-v1 lib | 007 |
| `variants-with-tags` | Fn | `(variants-with-tags tag-set)` → seq of variant ids | post-v1 lib | 007 |
| `snapshot-identity` | Fn | `(snapshot-identity variant-id)` → `{:variant-id ... :content-hash "..."}` | post-v1 lib | 007 |
| `story-view` | Fn | `(story-view variant-id)` → hiccup | post-v1 lib | 007 |

---

## Removed / not shipped

| API | What to do | Reference |
|---|---|---|
| `dispatch-with` (master) | Use `(dispatch event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-sync-with` (master) | Use `(dispatch-sync event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-to` (proposed earlier) | Use `(dispatch event {:frame :todo})` | 002 |
| `subscribe-to` (proposed earlier) | Use `(subscribe query-v {:frame :todo})` | 002 |
| `frame-dispatcher` (proposed earlier) | Use `dispatcher` (captures current frame at call time; safe to call during render and from async callbacks) | 002 |
| `bound-dispatcher` / `bound-subscriber` (proposed earlier) | Cut as pure aliases for `dispatcher` / `subscriber` (rf2-knz3l). The verb-form names already imply capture-at-call-time semantics | 002 |
| `enable-performance-api-tracing!` (proposed earlier) | Performance-API instrumentation is gated on the compile-time `re-frame.performance/enabled?` `goog-define`, not a runtime toggle (see [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation)) | 009 |
| `add-trace-listener` / `remove-trace-listener` (proposed earlier) | Use `register-trace-listener!` / `unregister-trace-listener!` | 009 |
| `register-trace-listener` / `unregister-trace-listener` (no-bang, proposed earlier) | Renamed to `register-trace-listener!` / `unregister-trace-listener!` (bang form matches the side-effecting nature of listener registration) | 009 |
| Bare `[:my-view "args"]` keyword-tagged hiccup | Use the Var form `[my-view "args"]` (canonical) or `[(rf/view :my-view) "args"]` for late-binding by id | 004 |
| `h` macro (proposed earlier) | Removed (rf2-n4um). Use the Var form `[my-view "args"]` or `[(rf/view :my-view) "args"]` | 004 |
| `reg-global-interceptor` | Use `reg-frame :interceptors` (frame-level is the canonical "global within this frame"). For cross-frame observation use `register-trace-listener!`. | MIGRATION M-17 |
| `clear-global-interceptor` | No replacement needed — re-register `reg-frame` with an updated `:interceptors` vector (absent-key semantics clear it). | MIGRATION M-17 |
| `reg-sub-raw` | Use `reg-sub` (app-db reads), Pattern-AsyncEffect (non-app-db sources), state machines (lifecycle), or the [006](006-ReactiveSubstrate.md) adapter contract (bridging external reactivity). | MIGRATION M-18 |
| `re-frame.alpha/reg` | Per-kind macros: `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow`. | MIGRATION M-23 |
| `re-frame.alpha/sub` | Vector-form `(rf/subscribe [::id arg])`. | MIGRATION M-23 |
| `re-frame.alpha/reg-sub-lifecycle` and built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Sub-cache uses a single algorithm — deferred ref-counting with grace-period, per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal). For specific edge cases file a follow-up bead. | MIGRATION M-23 |

---

## Cross-references

- [000-Vision.md](000-Vision.md) — principles and design decisions
- [002-Frames.md](002-Frames.md) — frames, dispatch envelope, drain semantics, overrides, machine foundations
- [004-Views.md](004-Views.md) — view registration, hiccup forms
- [005-StateMachines.md](005-StateMachines.md) — machine library design (post-v1)
- [007-Stories.md](007-Stories.md) — story/variant/workspace library design (post-v1)
- [008-Testing.md](008-Testing.md) — testing API and patterns
- [009-Instrumentation.md](009-Instrumentation.md) — trace event stream, listeners, error contract
- [010-Schemas.md](010-Schemas.md) — Malli schemas
- [014-HTTPRequests.md](014-HTTPRequests.md) — `:rf.http/managed` request fx (optional capability)
- [MIGRATION.md](../migration/from-re-frame-v1/README.md) — AI-driven migration spec
