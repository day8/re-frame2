# re-frame2 — API

> **Type:** Reference
> Reference for the CLJS implementation's API: signatures, status, cross-references. No rationale — per-Spec docs own the *why*. Pattern-level contracts live in [000-Vision §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic) and the per-Spec docs. **`:fx-overrides` asymmetry:** id-valued at the pattern level; CLJS reference also accepts fn values — see [002 §`:fx-overrides`](002-Frames.md#fx-overrides--replace-fx-handlers).

## Conventions

- **Status** — exactly one base value, optionally combined with a single qualifier:
  - Base values: `v1` (ships in v1), `v1 (preserved)` (exists in current re-frame; preserved unchanged), `v1 (preserved + extended)` (exists today; v1 adds new arity or behaviour), `post-v1 lib` (design spec in v1 Specs but ships in a post-v1 library).
  - Qualifier: `dev-only` (elided in production builds — the macro emit site or runtime body, depending on the API).
  - Examples: `v1`, `v1 (preserved)`, `v1 (dev-only)`, `v1 (preserved, dev-only)`, `post-v1 lib`.
  - The `re-frame.alpha` namespace is dissolved (rf2-7cb2 / rf2-s9dn) — no APIs in this reference live outside `re-frame.core` (with the documented per-namespace exception: `re-frame.test-support`).
- **Macro/Fn:** marked `M` (macro) or `Fn`.
- **Spec column** — names exactly the **canonical owning Spec** (the per-Spec doc whose contract this API implements). Migration rules and other cross-references are NOT in the Spec column; they appear in the Notes column when relevant.
- **Configure keys** — runtime configuration is uniformly via `(rf/configure <key> <opts>)`. Every `<key>` is enumerated in [§Configure keys](#configure-keys) below; per-area tables call out which keys their APIs read but do not redefine the key's vocabulary.
- All APIs live in `re-frame.core` unless otherwise noted (`re-frame.test-support`).

---

## Registration

> **Return value.** Every `reg-*` row below returns its **primary id** — the keyword (or path, for `reg-app-schema`) the caller registered with. `reg-flow` returns the `:id` value of its flow-map (the primary id is carried by the map, not a separate arg). Per [Conventions §`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

| API | M/Fn | Signature | Status | Spec | Notes |
|---|---|---|---|---|---|
| `reg-event-db` | M | `(reg-event-db id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | Macro for source-coord capture. See [MIGRATION §M-5](MIGRATION.md) for higher-order-use migration. |
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
| `reg-app-schema` | M | `(reg-app-schema path schema)` / `(reg-app-schema path schema opts)` | v1 | 010 | |
| `reg-app-schemas` | M | `(reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})` / `(reg-app-schemas {…} opts)` — bulk plural form for feature-modular apps that register 5–20 paths against the same prefix (per Conventions §Feature-modularity prefix convention). Each entry routes through the singular `reg-app-schema` and is stamped with this call's source-coords. Returns the vector of paths registered (rf2-jzs9) | v1 | 010 | |
| `reg-flow` | Fn | `(reg-flow flow)` / `(reg-flow flow opts)` | v1 | 013 | `opts` is a map (currently `{:frame frame-id}`). The shipped surface is opts-map only — same shape as `dispatch` / `subscribe` / `clear-flow`. Returns the flow's `:id` (per Conventions §`reg-*` return-value convention). |
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
| `destroy-frame` | Fn | `(destroy-frame frame-id)` | v1 |
| `reset-frame` | Fn | `(reset-frame frame-id)` | v1 |
| `clear-subscription-cache!` | Fn | `(clear-subscription-cache! frame-id?)` | v1 (preserved) |

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
| `subscribe-value` | Fn | `(subscribe-value query-v)` / `(subscribe-value frame-id query-v)` → value (subscribe + deref + immediate unsubscribe; one-shot, non-reactive read for handler bodies, machine actions, REPL) | v1 | 006 |
| `unsubscribe` | Fn | `(unsubscribe query-v)` / `(unsubscribe frame-id query-v)` → nil (decrement the cache ref-count; ref-count→0 schedules disposal after the configured `:sub-cache` grace-period) | v1 | 006 |
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot. Sugar over `(subscribe [:rf/machine machine-id])`. | v1 | 005 |

`opts` map keys: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Envelope shape and semantics: see [002 §Routing: the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope).

---

## View ergonomics

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `frame-provider` | Component (Reagent) | `[rf/frame-provider {:frame :todo} & children]` | v1 | 002 |
| `with-frame` | M | `(with-frame :keyword body)` *or* `(with-frame [sym expr] body)` | v1 | 002 |
| `bound-fn` | M | `(bound-fn [args] body)` | v1 | 002 |
| `dispatcher` | Fn | `(dispatcher)` → `(fn [event] ...)` — captures the current frame at call time and returns a frame-bound dispatch fn. Safe to call during render AND from async callbacks where the dynamic-var binding has unwound | v1 | 002, 004 |
| `subscriber` | Fn | `(subscriber)` → `(fn [query-v] ...)` — companion to `dispatcher` for subscribe. Captures the current frame at call time | v1 | 002, 004 |
| `view` | Fn | `(view view-id)` → render-fn (runtime-lookup handle) | v1 | 001, 004 |

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

`reg-head` and `reg-error-projector` are rowed canonically in [§Registration](#registration). The head-fn signature is `(fn [db route] head-model)`; the projector-fn signature is `(fn [trace-event] :rf/public-error)`.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `render-to-string` | Fn | `(render-to-string view-or-hiccup opts)` → HTML string | v1 | 011 |
| `render-tree-hash` | Fn | `(render-tree-hash render-tree)` → 32-bit FNV-1a structural hash (lowercase hex). Identical output on JVM and CLJS for the same canonical-EDN representation. Per [011 §Hydration-mismatch detection](011-SSR.md). | v1 | 011 |
| `project-error` | Fn | `(project-error frame-id trace-event)` → `:rf/public-error`. Applies the active error-projector (selected by the frame's `:ssr {:public-error-id ...}` metadata) for the named frame. Per [011 §Server error projection](011-SSR.md). | v1 | 011 |
| `render-head` | Fn | `(render-head head-id opts)` → `:rf/head-model` | v1 | 011 |
| `active-head` | Fn | `(active-head)` / `(active-head frame-id)` → `:rf/head-model` | v1 | 011 |
| `head-model->html` | Fn | `(head-model->html head-model)` / `(head-model->html head-model {:wrap? bool})` → inner-head HTML string | v1 | 011 |

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
| `:rf.http/managed-canned-success` | fx | `[:rf.http/managed-canned-success {:value v}]` — synthesises the canonical success reply (per [014 §Testing](014-HTTPRequests.md#testing)) | v1 (optional capability, dev/test) | 014 |
| `:rf.http/managed-canned-failure` | fx | `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]` — synthesises the canonical failure reply | v1 (optional capability, dev/test) | 014 |
| `with-managed-request-stubs` | M | `(with-managed-request-stubs route-map body+)` — route-map `{[<method> <url>] {:reply ...}}` per [014 §Testing](014-HTTPRequests.md#testing) | v1 (optional capability, dev/test) | 014 |
| `with-managed-request-stubs*` | Fn | `(with-managed-request-stubs* route-map body-fn)` — plain-fn surface beneath `with-managed-request-stubs`; the `*` follows the Clojure `let`/`let*`, `fn`/`fn*` idiom (per [Conventions](Conventions.md)). Use for computed route-maps or non-literal bodies. Per [014 §Testing](014-HTTPRequests.md#testing). | v1 (optional capability, dev/test) | 014 |
| `install-managed-request-stubs!` | Fn | `(install-managed-request-stubs! route-map)` — install the stub routes; persists until `uninstall-managed-request-stubs!` is called. Lower-level than `with-managed-request-stubs`; use when stubs span multiple `deftest`s. Per [014 §Testing](014-HTTPRequests.md#testing). | v1 (optional capability, dev/test) | 014 |
| `uninstall-managed-request-stubs!` | Fn | `(uninstall-managed-request-stubs!)` — drop any installed stubs and restore real-request routing. Idempotent. Per [014 §Testing](014-HTTPRequests.md#testing). | v1 (optional capability, dev/test) | 014 |
| `reg-http-interceptor` | Fn | `(reg-http-interceptor {:frame ... :id ... :before (fn [ctx] ctx')})` — register a request-side interceptor on a frame's `:rf.http/managed` middleware chain (per [014 §Middleware](014-HTTPRequests.md#middleware), rf2-6y3q). `:before` receives a ctx `{:request :args :frame :event}` and returns a (possibly-modified) ctx. | v1 (optional capability) | 014 |
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

Closed: `:db` + `:fx` only. See [Spec-Schemas §:rf/effect-map](Spec-Schemas.md#rfeffect-map). Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` from v1 migrate via [MIGRATION.md §M-8](MIGRATION.md).

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
| `[:rf.machine/spawn spawn-spec]` | spawn-spec map (per `:rf.fx/spawn-args`: `:machine-id`/`:definition`, `:id-prefix`, `:data`, `:on-spawn`, `:start`) | v1 | 005 | Canonical actor-lifecycle fx (registered globally by `re-frame.machines`); registers a new dynamic actor (whose snapshot lives at `[:rf/machines <gensym'd-id>]`) and (via `:on-spawn`) records its id into the parent's `:data`. Emitted from any event handler's `:fx` (including machine actions and the `:invoke` desugar). |
| `[:rf.machine/destroy actor-id]` | actor id (keyword) | v1 | 005 | Canonical actor-destroy fx (registered globally by `re-frame.machines`); runs the actor's `:exit` action, dissociates `[:rf/machines <actor-id>]`, and clears the actor's event-handler registration. Symmetric counterpart to `:rf.machine/spawn`. |

---

## Public registrar query API

For tooling, agents, story tools, 10x.

| API | M/Fn | Signature | Status | JVM-runnable? | Spec |
|---|---|---|---|---|---|
| `handlers` | Fn | `(handlers kind)` / `(handlers kind pred-fn)` | v1 | ✓ | 002 |
| `handler-ids` | Fn | `(handler-ids kind)` → id set (canonical alias for `(-> (handlers kind) keys set)`) | v1 | ✓ | 002 |
| `handler-meta` | Fn | `(handler-meta kind id)` → registration-metadata map. View registrations include source-coord keys (`:ns` / `:line` / `:column` / `:file`) per `:rf/source-coord-meta` ([Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta)); pair tools resolve `data-rf2-source-coord` DOM annotations to `:file` via this lookup. | v1 | ✓ | 002 |
| `registry-summary` | Fn | `(registry-summary)` → `{kind count}` (count of registered ids per kind; useful in dev-tooling overlays) | v1 | ✓ | 002 |
| `machines` | Fn | `(machines)` → seq of machine-ids. Derived view over `(handlers :event)` filtered by `:rf/machine? true`. | v1 | ✓ | 005 |
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

`reg-app-schema` is rowed canonically in [§Registration](#registration).

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `app-schemas` | Fn | `(app-schemas)` / `(app-schemas {:frame frame-id})` | v1 | 010 |
| `app-schema-at` | Fn | `(app-schema-at path)` / `(app-schema-at path {:frame frame-id})` | v1 | 010 |
| `app-schemas-digest` | Fn | `(app-schemas-digest)` / `(app-schemas-digest {:frame frame-id})` → string | v1 | 010 |
| `set-schema-validator!` | Fn | `(set-schema-validator! validate-fn)` / `(set-schema-validator! {:validate validate-fn :explain explain-fn})` — install the validator (and optionally the explainer) every dev-time schema-validation site routes through. `nil` disables validation entirely. Default ships Malli's `validate`/`explain` pair; this seam lets apps swap in their own validator to drop the Malli dep. Per [010 §Non-Malli validators (rf2-froe)](010-Schemas.md). | v1 | 010 |
| `set-schema-explainer!` | Fn | `(set-schema-explainer! explain-fn)` — install the explainer used to enrich `:rf.error/schema-validation-failure` traces' `:explain` key. Companion to `set-schema-validator!`. Per [010 §Non-Malli validators (rf2-froe)](010-Schemas.md). | v1 | 010 |
| `:spec/validate-at-boundary` (interceptor) | — | Add to a `reg-event-*`'s positional interceptor vector for production-boundary validation | v1 | 010 |

See [010 §Schemas](010-Schemas.md) for `:spec` metadata, validation timing, and dev/prod elision.

---

## Event-emit (always-on, production-survivable)

Per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production) (#2). A minimal always-on listener surface that survives `:advanced` + `goog.DEBUG=false` and delivers one tight record per processed event. The intended consumers are hosted observability back-ends (Datadog, Honeycomb, Sentry, …). Parallel to (not a fallback for) the dev-only trace surface; per-event only — no per-sub, per-fx, or per-`:event/db-changed` records. Record shape `{:event :event-id :frame :time :outcome :elapsed-ms}`; the `:event` slot is passed through [`rf/elide-wire-value`](#size-elision-wire-boundary-walker) once before fan-out, so `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-event-emit-listener!` | Fn | `(register-event-emit-listener! id listener-fn)` — `listener-fn` receives one event-record per processed event (shape above). Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. | v1 | 009 |
| `unregister-event-emit-listener!` | Fn | `(unregister-event-emit-listener! id)` → nil | v1 | 009 |

## Error-emit (always-on, production-survivable)

Per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production) (#2 second paragraph). Sibling of the event-emit surface above (rf2-bacs4); runs through the SAME always-on error-emit substrate as the per-frame `:on-error` slot ([002-Frames.md](002-Frames.md)) but along an INDEPENDENT corpus-wide fan-out path. Survives `:advanced` + `goog.DEBUG=false`. Intended consumers are hosted error monitors (Sentry, Honeybadger, Rollbar). One tight record per `:rf.error/*` event the router emits through the handler-exception path; record shape `{:error :event :event-id :frame :time :exception :elapsed-ms}`. The `:event` slot is passed through [`rf/elide-wire-value`](#size-elision-wire-boundary-walker) once before fan-out, so `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`. The two paths from the substrate (corpus-wide listeners AND the per-frame `:on-error` policy fn) are mutually isolated; either may throw without affecting the other.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-error-emit-listener!` | Fn | `(register-error-emit-listener! id listener-fn)` — `listener-fn` receives one error-record per `:rf.error/*` event (shape above). Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. | v1 | 009 |
| `unregister-error-emit-listener!` | Fn | `(unregister-error-emit-listener! id)` → nil | v1 | 009 |

## Tracing

All tracing is **dev-only** (elided in production). See [009 §Tracing](009-Instrumentation.md) for emit semantics and synchronous listener delivery.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-trace-cb!` | Fn | `(register-trace-cb! key callback-fn)` — `callback-fn` receives one trace event per call | v1 (dev-only) | 009 |
| `remove-trace-cb!` | Fn | `(remove-trace-cb! key)` → nil | v1 (dev-only) | 009 |
| `emit-trace!` | Fn | `(emit-trace! op-type operation tags)` → nil | v1 (dev-only) | 009 |
| `re-frame.interop/debug-enabled?` | Var | `^boolean` (alias of `goog.DEBUG` on CLJS; `true` on JVM) | v1 | 009 |
| `re-frame.performance/enabled?` | Var | `^boolean` `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in `performance.mark` + `performance.measure` calls (User-Timing entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). **Compile-time only** — not a `(rf/configure ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op. See [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation) and [Tool-Pair §Performance API consumption](Tool-Pair.md#performance-api-consumption) | v1 | 009 |
| `trace-buffer` | Fn | `(trace-buffer)` / `(trace-buffer opts)` → vector of trace events, oldest-first | v1 (dev-only) | 009 |
| `clear-trace-buffer!` | Fn | `(clear-trace-buffer!)` → nil | v1 (dev-only) | 009 |
| `(rf/configure :trace-buffer {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | 009 |
| `group-cascades` | Fn | `(group-cascades events)` → vector of cascade records `{:dispatch-id :event :handler :fx :effects :subs :renders :other}`, sorted by emission order. Pure data; JVM-runnable. Re-exported from `re-frame.trace.projection` (see [009 §Cascade projection](009-Instrumentation.md#cascade-projection-group-cascades--domino-bucket)). | v1 (dev-only) | 009 |
| `domino-bucket` | Fn | `(domino-bucket trace-event)` → `#{:event :handler :fx :effect :sub :render :other}`. Classifies a raw trace event into the six-domino slot used by `group-cascades`. Pure data. | v1 (dev-only) | 009 |

### Epoch history (per Tool-Pair)

Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `epoch-history` | Fn | `(epoch-history frame-id)` → vector of epoch records. Returns `[]` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | Tool-Pair |
| `restore-epoch` | Fn | `(restore-epoch frame-id epoch-id)` → boolean (true on success). Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | Tool-Pair |
| `reset-frame-db!` | Fn | `(reset-frame-db! frame-id new-db)` → boolean (true on success) — pair-tool write surface (state injection). Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false` for an unknown / destroyed frame. | v1 (dev-only) | Tool-Pair |
| `register-epoch-cb!` | Fn | `(register-epoch-cb! key callback-fn)` — assembled-epoch listener. Process-global; a callback whose previously-observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | Tool-Pair, 009 |
| `remove-epoch-cb!` | Fn | `(remove-epoch-cb! key)` | v1 (dev-only) | Tool-Pair, 009 |
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
| `:rf.epoch/reset-frame-db-during-drain` | `:frame` |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `:frame`, `:failing-paths` |
| `:rf.epoch.cb/silenced-on-frame-destroy` | `:frame`, `:cb-id` |

<a id="elide-wire-value-the-wire-boundary-walker"></a>

### Size-elision wire-boundary walker

The framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots. Consumed by every tool that emits wire data (the off-box error-monitor forwarders, the Causa-MCP / pair2-mcp / story-mcp servers per [Tool-Pair.md](Tool-Pair.md), the on-box dev panels). The walker is the **single normative emission site** for the `:rf/redacted` sensitive sentinel and the `:rf.size/large-elided` marker; per-tool reimplementation is prohibited.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `elide-wire-value` | Fn | `(elide-wire-value v opts)` → `v` or an elision-marker substitution. `opts` is a map: `{:rf.size/include-large? <bool> :rf.size/include-sensitive? <bool> :rf.size/include-digests? <bool> :rf.size/threshold-bytes <int> :path [...] :frame <frame-id>}`. Defaults: both `include-*` flags `false` (maximum elision); `:rf.size/threshold-bytes` falls back to `(rf/configure :elision ...)` then `16384`. Walks `v` consulting `[:rf/elision :declarations]` and `[:rf/elision :runtime-flagged]` of the named frame's `app-db`; substitutes `:rf/redacted` for sensitive slots and `:rf.size/large-elided` markers for large slots. Composition rule (normative): when both predicates match the **sensitive drop wins** — the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest`. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) and [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker). | v1 | 009 |
| `declare-large-path!` | Fn | `(declare-large-path! path)` / `(declare-large-path! path hint)` → nil. REPL / boot-time convenience wrapper that dispatches `[:rf.size/declare-large {:path path :hint hint}]` against the current frame. Writes `{:large? true :hint <hint-or-nil> :source :declared}` into `[:rf/elision :declarations <path>]`. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). | v1 | 009 |
| `clear-large-path!` | Fn | `(clear-large-path! path)` → nil. REPL / boot-time convenience wrapper that dispatches `[:rf.size/clear {:path path}]`. Clears both the `:declarations` and `:runtime-flagged` slots for the path. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). | v1 | 009 |

The fx-form ids `:rf.size/declare-large` and `:rf.size/clear` are the canonical app-time path (per [Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids)); the schema-driven path (per [Spec-Schemas §`:rf/app-schema-meta`](Spec-Schemas.md#rfapp-schema-meta) — `:large? true` on a Malli slot) is the AI-discoverable boot-time path. The `!`-suffix on the convenience wrappers per [Conventions §Naming](Conventions.md#naming-when-does-a-surface-carry-) bucket 3 (process-level mutation outside the registrar — they synthesise a dispatch against the current frame's elision registry).

### DOM source-coord annotations (mandatory; rf2-z7f7 / rf2-z9n1)

Per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) and [Tool-Pair §Source-mapping](Tool-Pair.md), every adapter whose host has a DOM-attribute concept MUST inject `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. Format and exemptions (Fragments, non-DOM roots) are documented in Spec 006 §Source-coord annotation. Annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via dead-code elimination — there is no DOM-bytes cost in shipped bundles. The JVM SSR emitter mirrors the same contract per [Spec 011 §Source-coord annotation under SSR](011-SSR.md#source-coord-annotation-under-ssr).

### Error contract

Errors are emitted as structured trace events with `:op-type :error` (or `:warning` / `:info` / `:fx` / `:flow` / `:frame`) and a per-category `:operation` keyword. The complete normative catalogue — every `:rf.error/*`, `:rf.warning/*`, `:rf.fx/*`, `:rf.cofx/*`, `:rf.ssr/*`, `:rf.epoch/*`, `:rf.flow/*`, `:rf.http/*`, `:rf.http.interceptor/*`, `:rf.frame/*`, and `:rf.route.nav-token/*` event the runtime emits — lives at [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (single source of truth for category names, `:op-type` discriminator, trigger conditions, default `:recovery`, and `:tags` payload keys). Per-category Malli `:tags` schemas are canonicalised at [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas) — one schema per catalogue row.

Recent additions consumers should be aware of: `:rf.ssr/version-mismatch`, `:rf.ssr/schema-digest-mismatch`, `:rf.ssr/compatibility-check-skipped` (the SSR hydration compatibility-check trio, per rf2-69ad2), and `:rf.cofx/skipped-on-platform` (the platform-gating mirror of `:rf.fx/skipped-on-platform`). The catalogue at 009 is the single source of truth — do not duplicate the table here.

Per-Spec emit-sites: [002-Frames](002-Frames.md), [005-StateMachines](005-StateMachines.md), [006-ReactiveSubstrate](006-ReactiveSubstrate.md), [010-Schemas](010-Schemas.md), [011-SSR](011-SSR.md), [012-Routing](012-Routing.md), [013-Flows](013-Flows.md), [014-HTTPRequests](014-HTTPRequests.md), [Tool-Pair](Tool-Pair.md). Each catalogue row's "Per [N]" cross-link names the owning Spec section.

### Privacy (Spec 009 §Privacy / sensitive data in traces)

Per [Spec 009 §Privacy](009-Instrumentation.md) the runtime stamps `:sensitive? true` at the top level of every trace event emitted inside the scope of a registration declared `:sensitive? true`. Framework-published trace consumers (Sentry/Honeybadger forwarders, pair2 server, Causa, Story, story-mcp, pair2-mcp, causa-mcp) MUST default-drop the stamped events at their egress boundary. `with-redacted` is the in-place payload scrub composed alongside the stamp.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `sensitive?` | Fn | `(sensitive? trace-event)` → `boolean`. True iff `trace-event` is a map carrying `:sensitive? true` at the top level (not under `:tags`). The framework-published predicate every consumer composes against — replaces per-consumer reimplementations of the same five-token check (rf2-sqxjn). | v1 | 009 |
| `with-redacted` | Fn | `(with-redacted paths)` → interceptor. Build a positional interceptor that overwrites the named keys in the event vector's payload map with the `:rf/redacted` sentinel before the handler chain runs. The handler body itself sees the UNREDACTED payload via the regular `:event` coeffect slot; the redaction is for the trace surface only. `paths` is a vector of `get-in`-style key paths into the payload map. | v1 | 009 |

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

`re-frame.test-support` ships the test-flavoured helpers below alongside re-exports of `make-frame`/`destroy-frame`/`with-frame`/`dispatch-sync` for one-stop import. See [008-Testing.md](008-Testing.md) for fixtures, framework adapters, and `re-frame-test` compatibility.

| API | M/Fn | Signature | Status | Spec | Notes |
|---|---|---|---|---|---|
| `dispatch-sequence` | Fn | `(dispatch-sequence events)` / `(dispatch-sequence events opts)` | v1 | 008 | `opts`: `:after-each (fn [db ev] ...)`, `:frame`. Returns final `app-db`. Lives in `re-frame.test-support`. |
| `assert-state` | Fn | `(assert-state expected-db)` / `(assert-state path expected-val)` / either form `+ {:frame ...}` opts | v1 | 008 | Mismatch fires `clojure.test/is`-style failure via `do-report`. Lives in `re-frame.test-support`. |
| `with-fx-overrides` | M | `(with-fx-overrides {fx-id -> override, …} body+)` | v1 | 002, 008 | Lexical-scope `:fx-overrides` binding (rf2-5uwl). Every `dispatch` / `dispatch-sync` inside the body merges the supplied map into its envelope's `:fx-overrides`. Precedence: per-call opt > lexical `with-fx-overrides` > per-frame `:fx-overrides`. Composes with `with-frame`. Lives in `re-frame.core`. Renamed from `with-overrides` per [MIGRATION §M-50](MIGRATION.md#m-50-with-overrides-macro-renamed-to-with-fx-overrides). |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | 008 | Pure sub computation against an `app-db` value. Lives in `re-frame.core`. |
| `snapshot-registrar` / `restore-registrar!` / `with-fresh-registrar` / `reset-runtime-fixture` | Fn | per docstring | v1 | 008 | Fixture machinery. Lives in `re-frame.test-support`. |

---

## Standard interceptors

The v2 std-interceptor surface is **three specific helpers** plus the `->interceptor` primitive. The principled line: keep helpers that do specific, non-trivial work; drop helpers that are just `(->interceptor :before f)` or `(->interceptor :after f)` with no other logic. Five interceptors removed: `debug`, `trim-v`, `on-changes`, `enrich`, `after` (per [MIGRATION §M-21](MIGRATION.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)).

| API | M/Fn | Signature | Purpose |
|---|---|---|---|
| `inject-cofx` | M | `(inject-cofx id)` / `(inject-cofx id value)` | Inject a registered cofx into the handler's coeffect map. Macro per rf2-ts1a — captures call-site for `:rf.trace/call-site` on errors emitted from inside the cofx body. Specific work — `:cofx` registry lookup, not subsumable by `->interceptor`. |
| `inject-cofx*` | Fn | `(inject-cofx* id)` / `(inject-cofx* id value)` | Fn form (rf2-ts1a) for HoF / programmatic interceptor construction — no call-site stamping. |
| `path` | Fn | `(path & path)` | Focus a handler on an `app-db` sub-slice. Specific work — `:before` focuses, `:after` splices the result back into parent db. |
| `unwrap` | (val) | `unwrap` | Assert `[id payload-map]` event shape; replace `:event` coeffect with the payload map; restore on `:after`. Sugar over the M-19 canonical map-payload form. |
| `->interceptor` | Fn | `(->interceptor & {:keys [id before after]})` | The primitive. Build a custom interceptor with `:before` and/or `:after` slots. **Use this for any work not covered by the three specific helpers above** — analytics, logging, validation, ad-hoc context manipulation. The resulting interceptor is named, addressable, and queryable like any other artefact. |

Removed in v2 (see [MIGRATION §M-21](MIGRATION.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)):

| Removed API | Replaced by |
|---|---|
| `debug` | Trace surface ([009](009-Instrumentation.md)) + 10x / re-frame-pair |
| `trim-v` | Canonical map-payload call shape ([M-19](MIGRATION.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)) |
| `on-changes` | Flows ([Spec 013](013-Flows.md)) |
| `enrich` | Flows (derived state) / `:spec` (validation) / custom `->interceptor` (escape hatch) |
| `after` | Registered fx (`:fx [[:my-fx ...]]`) for side-effects; custom `->interceptor` for context-shaped work; vendor from v1 if the helper is wanted as a local utility |

### `reg-flow` / `clear-flow` (Spec 013)

`reg-flow` is rowed canonically in [§Registration](#registration); required flow-map keys are `:id`, `:inputs`, `:output`, `:path`. Both surfaces take an optional opts map (`{:frame frame-id}`) selecting the owning frame: `(reg-flow flow)` / `(reg-flow flow opts)` and `(clear-flow id)` / `(clear-flow id opts)`. `clear-flow` is rowed canonically in [§Clearing registrations](#clearing-registrations); it deregisters the flow from the named frame and `dissoc-in`s its `:path` from that frame's `app-db` only (per Spec 013 §Frame-scoping).

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
| `current-adapter` | Fn | `(current-adapter)` → discriminator keyword (`:reagent` / `:reagent-slim` / `:uix` / `:helix` / `:plain-atom` / `:ssr` / `:custom`) or `nil` when no adapter is installed. Answers "what substrate am I on?" — predicate / branch code. For the spec map (fn handles, identity checks), use `current-adapter-spec`. | v1 | 006 |
| `current-adapter-spec` | Fn | `(current-adapter-spec)` → the installed adapter spec map (the value passed to `(rf/init! ...)`) or `nil` when no adapter is installed. Answers "give me the adapter fns to call" — tools, routing, identity checks across the install/dispose lifecycle. For the discriminator keyword, use `current-adapter`. | v1 | 006 |
| `configure` | Fn | `(configure key opts)` — runtime config; key vocabulary in [§Configure keys](#configure-keys). One of three orthogonal configuration surfaces per [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) (`configure` for process-level data knobs; `set-!` / `install-!` for adapter-pluggable hooks; per-frame metadata for frame-scoped overrides). | v1 | — |

---

## Configure keys

Runtime configuration is uniformly via `(rf/configure <key> <opts>)`. Every framework-owned key is enumerated here. Keys are plural-noun-shaped; opts are an open map of per-key settings.

| Key | Opts shape | Default | Status | Spec |
|---|---|---|---|---|
| `:epoch-history` | `{:depth N}` — non-negative integer; 0 disables | `{:depth 50}` | v1 (dev-only) | Tool-Pair |
| `:trace-buffer` | `{:depth N}` — non-negative integer; 0 disables | `{:depth 200}` | v1 (dev-only) | 009 |
| `:sub-cache` | `{:grace-period-ms N}` — non-negative integer; 0 selects synchronous disposal | `{:grace-period-ms 50}` | v1 | 006 |
| `:elision` | `{:rf.size/threshold-bytes N}` — non-negative integer; 0 disables runtime auto-detect (only declared / schema entries elide) | `{:rf.size/threshold-bytes 16384}` | v1 | 009 |

SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure` key — it is per-frame metadata on the frame's `:ssr` map (see [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) bucket 3 and [011 §Server error projection](011-SSR.md#server-error-projection)). Different frames in the same process can carry different projector / dev-detail settings, so the natural lifetime is per-frame, not process-global.

The configure-keys vocabulary is fixed-and-additive (Spec-ulation): existing keys cannot be renamed or removed; new keys are added by extending the table. User code that wraps `configure` should pattern-match on known keys and ignore unknown ones.

---

## Machines

Split between the v1 machine-as-event-handler foundation and the post-v1 `re-frame.machines` scaffolding library — see [005-StateMachines.md §Disposition](005-StateMachines.md#disposition). The machine *is* the event handler: a machine is registered as one `reg-event-fx` whose body comes from `create-machine-handler`.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` — registers a machine as an event handler. Walks the literal spec form at expansion time and stamps per-element source coords under `:rf.machine/source-coords` (rf2-8bp3). | v1 | 005 |
| `reg-machine*` | Fn | `(reg-machine* machine-id machine-spec)` — plain-fn surface beneath the macro. No source-coord walking. | v1 | 005 |
| `create-machine-handler` | Fn | `(create-machine-handler spec)` → event-handler fn | v1 | 005 |
| `machine-transition` | Fn | `(machine-transition definition snapshot event)` → `[next-snapshot effects]` | v1 | 005 |
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot | v1 | 005 |
| `machines` | Fn | `(machines)` → seq of registered machine-ids | v1 | 005 |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration metadata; carries `:rf.machine/source-coords` index when registered via the macro | v1 | 005 |
| `machine-by-system-id` | Fn | `(machine-by-system-id system-id)` / `(machine-by-system-id system-id frame-id)` → spawned-machine id bound to `system-id` in the frame's `[:rf/system-ids]` reverse index (or `nil`). Per [005 §Named addressing via `:system-id`](005-StateMachines.md). | v1 | 005 |
| `dispatch-to-system` | Fn | `(dispatch-to-system system-id event)` / `(dispatch-to-system system-id event frame-id)` — sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`; no-op when the system-id is unbound. Per [005 §Cross-machine messaging by name](005-StateMachines.md). | v1 | 005 |
| `has-tag?` | Fn | `(has-tag? machine-id tag)` → reaction whose value is `true` iff the machine's snapshot's `:tags` set contains `tag`. Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`. Per [005 §State tags (rf2-ee0d / Nine States Stage 1)](005-StateMachines.md). | v1 | 005 |
| `:rf.machine/spawn` (fx) | — | Canonical actor-lifecycle fx (registered globally by `re-frame.machines`). Args per `:rf.fx/spawn-args`. | v1 | 005 |
| `:rf.machine/destroy` (fx) | — | Canonical actor-destroy fx (registered globally by `re-frame.machines`). Args: an actor id. | v1 | 005 |
| `:raise` (fx) | — | Reserved fx-id inside a machine action's `:fx` (machine-internal, routed pre-commit). Args: an event vector. | v1 | 005 |
| `:final?` / `:output-key` (state-node keys) | — | `:final?` marks a leaf state as terminal — entering it auto-destroys the machine. `:output-key` (requires `:final?`) designates the child's `:data` slot reported back via the parent's `:on-done`. Capability axis `:fsm/final-states`. Per rf2-gn80; see [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key). | v1 | 005 |
| `:on-done` (`:invoke` spec key) | — | `(fn [data result] new-data)` on the parent's `:invoke` map. Fires synchronously when the spawned child enters a `:final?` state; `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). Per rf2-gn80. | v1 | 005 |
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
| `add-trace-listener` / `remove-trace-listener` (proposed earlier) | Use `register-trace-cb!` / `remove-trace-cb!` | 009 |
| `register-trace-cb` / `remove-trace-cb` (no-bang, proposed earlier) | Renamed to `register-trace-cb!` / `remove-trace-cb!` (bang form matches the side-effecting nature of listener registration) | 009 |
| Bare `[:my-view "args"]` keyword-tagged hiccup | Use the Var form `[my-view "args"]` (canonical) or `[(rf/view :my-view) "args"]` for late-binding by id | 004 |
| `h` macro (proposed earlier) | Removed (rf2-n4um). Use the Var form `[my-view "args"]` or `[(rf/view :my-view) "args"]` | 004 |
| `reg-global-interceptor` | Use `reg-frame :interceptors` (frame-level is the canonical "global within this frame"). For cross-frame observation use `register-trace-cb!`. | MIGRATION M-17 |
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
- [MIGRATION.md](MIGRATION.md) — AI-driven migration spec
