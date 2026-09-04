(ns rf2-playground.sci
  "SCI eval bundle for the docs/cljs playground's `cljs-rf2` cells
  (rf2-00zvt, Phase 3).

  A `cljs-rf2` cell calls re-frame2's OWN public API (`re-frame.core`
  v2) and renders via reagent2 (the reagent-slim rewrite re-frame2
  actually uses). Scittle's plugins ship STOCK reagent / re-frame and
  there is no published `scittle.core` artefact a standalone plugin
  build can `:require`, so this is NOT a Scittle plugin — it is a
  self-contained SCI eval bundle (findings doc §6 option B): a
  shadow-cljs `:browser` build that depends on `org.babashka/sci` +
  re-frame2 core + reagent-slim, builds an SCI context exposing
  re-frame2's runtime API, and exports the `renderLast` JS entry point
  the playground bootstrap calls.

  How re-frame2's API reaches a cell:

    - In compiled CLJS, `re-frame.core` carries plain-fn *aliases* for
      every `reg-*` registration (`reg-event`, `reg-sub`, `reg-fx`,
      `reg-cofx`, ... — the macro forms are JVM-only and only add
      source-coord capture, which a browser cell does not need). So
      `sci/copy-ns` over `re-frame.core` exposes those fn-aliases under
      their plain names; a cell writes `(rf/reg-event :id (fn ...))`
      and it resolves to the fn-alias. No macro support needed.
      (EP-0018 Z: `reg-event` is the ONE public event registrar; the
      former per-kind `reg-event-db` / `reg-event-fx` / `reg-event-ctx`
      survive only as `^:no-doc` throwing stubs, so a stale cell calling
      one raises `:rf.error/reg-event-*-removed` — the intended signal.)

    - `dispatch` / `dispatch-sync` / `subscribe` are macro-in-call-
      position / fn-in-value-position on CLJS (Convention A, rf2-m90brg
      — same-named `def`-aliases to `re-frame.router/dispatch!` /
      `-dispatch-sync!` / `re-frame.subs/subscribe`), so `sci/copy-ns`
      already brings the plain fn form in. We still override the three
      entries below with playground-local wrappers — not because no
      fn-alias exists, but so every cell dispatch/subscribe defaults to
      `{:frame app-frame}` (a bare cell call has no dynamic `with-frame`
      scope to resolve against).

  Rendering: re-frame2 renders through reagent2 with a substrate adapter
  installed (`re-frame.adapter.reagent-slim/adapter` via `rf/init!`).
  reagent2 targets React 19 (`reagent2.dom.client/create-root`). React 19
  DROPPED its UMD build, so the global-`React`-from-CDN trick is
  unavailable — this bundle BUNDLES react@19 + react-dom@19 (the
  impl-pinned versions, resolved from `sci/package.json`) directly. The
  result is one fully self-contained `playground-rf2.js`: no external
  React, no CDN, no version-mismatch risk (see `sci/shadow-cljs.edn`).

  Machines (rf2-ldgpd): `re-frame.machines` is `:require`d at the top
  of this ns so the machines artefact's late-bind hooks register at
  bundle init — that activates the `:machines/*` slots `re-frame.core-
  machines` reads on every call (`reg-machine*`, `make-machine-handler`,
  `machine-transition`, `machines`, `machine-meta`, `machine-by-system-
  id`), and registers the `:rf/machine` / `:rf.machine/has-tag?`
  framework subs + the `:rf.machine/spawn` / `:rf.machine/destroy` /
  `:rf.machine/spawn-all-init` / `:rf.machine/after-schedule` /
  `:rf.machine/after-cancel` / `:rf.machine/update-snapshot` fxs from
  the namespace's top-level forms. The front-porch shrink demoted the
  machine query/build helpers off the `re-frame.core` façade to their
  owning `re-frame.machines` namespace, so the SCI `re-frame.core`
  namespace below adds a `reg-machine` entry bound to the
  `re-frame.machines/reg-machine*` fn-alias (same pattern as the
  `dispatch`/`subscribe` macro→fn shims) so the guide's
  ch12 cells can write `(rf/reg-machine ...)` exactly as in real code
  rather than the macro-less `reg-machine*` variant."
  (:require [sci.core :as sci]
            [re-frame.core :as rf]
            [re-frame.router :as rf.router]
            [re-frame.subs :as rf.subs]
            ;; The frame api constructor behind `capture-frame` and the
            ;; `reg-view` injection — an owned implementation seam, off the
            ;; facade (rf2-93sxp). Bound under its own SCI namespace below so
            ;; the `reg-view` shim can name it exactly as the real expansion
            ;; does, without copying an internal alias into `re-frame.core`.
            [re-frame.capture-frame :as rf.capture-frame]
            ;; Consumed (not re-exposed to cells) for page-owned registration
            ;; ownership + clear-on-disposal — see `disposePage` (rf2-u4pqs).
            [re-frame.registrar :as rf.registrar]
            [re-frame.views]
            [re-frame.machines]
            ;; flows artefact (Spec 007): required for its late-bind hooks so
            ;; `rf/reg-flow` (a copy-ns'd core fn-alias) is live in cells —
            ;; same pattern as re-frame.machines above (rf2 guide page 7).
            [re-frame.flows]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [reagent2.core :as r]
            [reagent2.ratom :as ratom]
            [reagent2.dom.client :as rdc]))

;; ---------------------------------------------------------------------------
;; SCI namespace configs
;; ---------------------------------------------------------------------------

;; EP-0002 (carried-frame invariant): the playground is a SINGLE-frame app —
;; every cell operation targets this one frame (`renderLast` registers it once,
;; see `ensure-init!`). Defined here so the SCI-bound `dispatch` / `dispatch-
;; sync` / `subscribe` wrappers below can default to it.
(def ^:private app-frame :rf/default)

;; EP-0002: a cell's `:on-click` (or any deferred) handler calls the SCI-bound
;; `dispatch` / `dispatch-sync` LATER — outside the render's dynamic extent and
;; any `with-frame` scope — so a bare call resolves NO frame and raises
;; `:rf.error/no-frame-context` (the runtime never synthesises a frame from
;; absence). These wrappers inject `{:frame app-frame}` as the DEFAULT, so every
;; cell dispatch resolves `:rf/default` regardless of dynamic scope. An explicit
;; `:frame` in a cell's opts still wins — `dispatch!`/`dispatch-sync!` give the
;; passed opt precedence over the carried scope. `subscribe` defaults to its
;; `{:frame app-frame}` opts form for the deferred / non-render case (the
;; render-time `subscribe` is already scoped by the `with-frame` binding
;; `frame-bind-component` re-establishes on every call).
;; Direct owning-ns calls (not the `rf/dispatch` macro) — a macro call here
;; would capture THIS file's call-site, not the cell's, which is misleading
;; (and the whole point of these wrappers is to inject the default `:frame`,
;; not to author a real application call site).
(defn- playground-dispatch
  ([event]      (rf.router/dispatch! event {:frame app-frame}))
  ([event opts] (rf.router/dispatch! event (merge {:frame app-frame} opts))))

(defn- playground-dispatch-sync
  ([event]      (rf.router/dispatch-sync! event {:frame app-frame}))
  ([event opts] (rf.router/dispatch-sync! event (merge {:frame app-frame} opts))))

(defn- playground-subscribe
  ([query-v]      (rf.subs/subscribe query-v {:frame app-frame}))
  ([query-v opts] (rf.subs/subscribe query-v (merge {:frame app-frame} opts))))

(def rf-ns (sci/create-ns 're-frame.core nil))

;; `reg-view` is a JVM-only defn-shape macro on the public surface (its
;; expansion lives in re-frame.core-reg-view-macro/expand-reg-view). Cells
;; want the same sugar, so this SCI macro mirrors that expansion — register
;; the render fn via the REAL `reg-view*`, inject `dispatch` / `subscribe`
;; as locals from a render-time `re-frame.capture-frame/make-capture-frame`
;; (the owned constructor the real expansion names, bound under its own SCI
;; namespace below; frame resolution is genuine: the harness frame by
;; default, or a cell-created `frame-provider`'s frame via the context
;; tier), `def` the var to `(rf/view id)`, and return the id per
;; Conventions §`reg-*` return-value.
;; Omitted vs the real expansion: source-coord capture (`*pending-coords*`,
;; `:rf.trace/call-site`) — a browser cell has no `*file*` to stamp, and
;; coord capture is an optional capability per Spec 001. The id derives
;; under the cell namespace (`:user/<sym>`), matching the auto-derive rule.
(defn- sci-reg-view
  [_&form _&env sym & more]
  (let [[docstring more] (if (string? (first more))
                           [(first more) (rest more)]
                           [nil more])
        [args & body]    more]
    (when-not (and (symbol? sym) (vector? args) (seq body))
      (throw (ex-info (str "reg-view's second argument must be an args vector "
                           "(defn-shape: (reg-view sym [args] body)). Got: "
                           (pr-str (first more)))
                      {:rf.error/id :rf.error/reg-view-bad-args})))
    (let [;; derive the id from the CELL'S current ns at eval time (matching
          ;; the real macro's auto-derive rule), not a hardcoded namespace —
          ;; a cell's (ns first-app.counter ...) form must show through.
          id     (list 'keyword (list 'str (list 'ns-name '*ns*)) (str sym))
          handle (gensym "handle")]
      (list 'do
            (list 're-frame.core/reg-view* id
                  (if docstring {:doc docstring} {})
                  (list 'fn sym args
                        (list 'let
                              [handle     (list 're-frame.capture-frame/make-capture-frame
                                                (list 're-frame.core/current-frame-id)
                                                {:dispatch-opts {:source :ui}})
                               'dispatch  (list :dispatch handle)
                               'subscribe (list :subscribe handle)]
                              (cons 'do body))))
            (list 'def sym (list 're-frame.core/view id))
            id))))  ;; the do returns the id form's value, per reg-* contract

;; copy-ns brings every public runtime var of re-frame.core into SCI —
;; that includes the reg-* fn-aliases (reg-event, reg-sub, reg-fx,
;; reg-cofx, ...) AND, since rf2-m90brg, the dispatch/dispatch-sync/
;; subscribe fn-aliases (Convention A), plus init!, configure,
;; clear-event, current-frame-id, capture-frame, app-db-value, etc.
;; We still override dispatch/dispatch-sync/subscribe below with the
;; playground-local default-frame wrappers (see `playground-dispatch`
;; et al. above) — the `merge` below wins over `copy-ns`'s entries.
;; (`inject-cofx` was removed from the public facade in EP-0017 / rf2-w9xyx1
;; — coeffect delivery is the `:rf.cofx/requires` declaration — so there is
;; no public surface for a cell to reach and no SCI binding for it.)
;;
;; Machines (rf2-ldgpd): `reg-machine` is also a JVM-only macro on the
;; public surface (per-element source-coord stamping at expansion time);
;; CLJS code reaches the plain-fn alias `reg-machine*` on the owning
;; `re-frame.machines` namespace (front-porch shrink — no longer
;; re-exported from `re-frame.core`). For cells the source-coord story
;; doesn't apply, so we bind `reg-machine` to
;; `re-frame.machines/reg-machine*` exactly as we do for `dispatch`/`subscribe`.
;; A cell calls `(rf/reg-machine :auth/flow login-flow)` and resolves to
;; the runtime fn — same shape as the chapter's prose.
(def re-frame-core-namespace
  (merge
   (sci/copy-ns re-frame.core rf-ns)
   {'dispatch      (sci/copy-var playground-dispatch rf-ns)
    'dispatch-sync (sci/copy-var playground-dispatch-sync rf-ns)
    'subscribe     (sci/copy-var playground-subscribe rf-ns)
    'reg-machine   (sci/copy-var re-frame.machines/reg-machine* rf-ns)
    ;; Flows (guide page 7): like reg-machine, `reg-flow` is a JVM-only
    ;; macro on the façade; the runtime fn lives on the owned namespace
    ;; (front-porch shrink, rf2-wad2fl). Bind it so cells call the real
    ;; 3-slot (reg-flow flow-id metadata derive-fn) grammar.
    'reg-flow      (sci/copy-var re-frame.flows/reg-flow rf-ns)
    ;; JVM-only defn-shape macro → SCI macro shim (see sci-reg-view above).
    'reg-view      (sci/new-var 'reg-view sci-reg-view
                                {:ns rf-ns :macro true :sci/macro true})}))

;; The owned constructor the `reg-view` shim expands to (rf2-93sxp). ONLY that
;; var is bound — not a `copy-ns` of the implementation namespace — and it is
;; bound under its own namespace, never merged into the `re-frame.core` map
;; above, so the facade a cell sees stays the real one.
(def capture-frame-ns (sci/create-ns 're-frame.capture-frame nil))
(def re-frame-capture-frame-namespace
  {'make-capture-frame (sci/copy-var rf.capture-frame/make-capture-frame capture-frame-ns)})

(def r-ns (sci/create-ns 'reagent2.core nil))
(def reagent2-core-namespace (sci/copy-ns reagent2.core r-ns))

(def ratom-ns (sci/create-ns 'reagent2.ratom nil))
(def reagent2-ratom-namespace (sci/copy-ns reagent2.ratom ratom-ns))

(def rdc-ns (sci/create-ns 'reagent2.dom.client nil))
(def reagent2-dom-client-namespace (sci/copy-ns reagent2.dom.client rdc-ns))

;; The SCI context. Cells require these as
;;   (require '[re-frame.core :as rf] '[reagent2.core :as r])
;; Aliases reagent.core -> reagent2.core / re-frame.core for cells that
;; copy stock-style import vectors, so the docs can use the v2 names but
;; a paste of stock idiom still resolves to the v2 surface.
(def sci-ctx
  (sci/init
   {:namespaces {'re-frame.core          re-frame-core-namespace
                 're-frame.capture-frame re-frame-capture-frame-namespace
                 'reagent2.core        reagent2-core-namespace
                 'reagent2.ratom       reagent2-ratom-namespace
                 'reagent2.dom.client  reagent2-dom-client-namespace
                 ;; convenience aliases — cells may say `reagent.core`
                 'reagent.core         reagent2-core-namespace}}))

;; ---------------------------------------------------------------------------
;; Adapter bootstrap (idempotent)
;; ---------------------------------------------------------------------------
;;
;; re-frame2's views layer needs a substrate adapter installed before any
;; subscribe-in-component render works. Install the reagent-slim adapter
;; once, the first time the bundle is asked to eval/render anything.

;; The reagent-slim adapter install (`rf/init!`) is PROCESS-GLOBAL: once per
;; bundle load, never torn down. `dispose-page!` (below) reaps page-owned frames
;; on every instant nav but must NOT un-install the adapter, so its guard is
;; kept separate from the (page-owned) app-frame creation.
(defonce ^:private adapter-inited? (atom false))

(defn- ensure-adapter! []
  (when-not @adapter-inited?
    (rf/init! rf.adapter.reagent-slim/adapter)
    (reset! adapter-inited? true)))

;; EP-0002 (carried-frame invariant): `rf/init!` installs the adapter but the
;; runtime never synthesises a frame from absence. The playground is a
;; consumer app, so it establishes its own app frame — `app-frame`,
;; `:rf/default` (defined up top). Cells then dispatch/subscribe against it
;; without any per-cell frame boilerplate: `renderLast` evaluates each cell's
;; source under a `with-frame :rf/default` scope (so a top-level `dispatch-sync`
;; in the cell resolves), mounts the cell's component under a `frame-provider`
;; (so the render-time `subscribe` resolves), and the SCI-bound
;; `dispatch`/`dispatch-sync`/`subscribe` wrappers default to `app-frame` (so a
;; DEFERRED `:on-click` dispatch resolves too).
;;
;; The app frame is PAGE-OWNED (rf2-io9mdr): `dispose-page!` destroys it on each
;; Material instant nav so the incoming page starts from `:rf/default`'s
;; documented initial (empty) app-db, not the outgoing page's accumulated
;; state. It is therefore (re)created ON DEMAND — whenever a render runs and the
;; frame is not currently live — rather than once behind a boot flag. `frame-ids`
;; is the live, non-destroyed set, so a `contains?` miss means a prior dispose
;; reaped it (or it was never made) and we recreate it fresh.
(defn- ensure-app-frame! []
  (when-not (contains? (rf/frame-ids) app-frame)
    (rf/make-frame {:id app-frame})))

;; Page-owned registration ownership (rf2-u4pqs). Cells reach re-frame2's
;; process-global `reg-*` API directly (the `sci/copy-ns` over `re-frame.core`),
;; so a cell's `reg-event` / `reg-sub` / `reg-view` / `reg-machine` writes a
;; `(kind, id)` into the ONE process registrar. Those registrations are
;; PAGE-OWNED — nothing scopes them to the outgoing document — so `disposePage`
;; must clear them on each instant nav, or a later page can dispatch/subscribe
;; through an id it never registered (the outgoing cell's leaked handler still
;; resolves). To tell page-owned from FRAMEWORK/BUNDLE-INIT baseline (the
;; machines/flows artefacts' `:rf/machine` sub + `:rf.machine/*` fxs, the
;; adapter's installs — global, spanning every page), snapshot the registrar's
;; `(kind, id)` set ONCE, right after the framework is fully booted (adapter
;; installed) and BEFORE any cell has evaluated. Everything registered after the
;; snapshot is a cell's work, so `disposePage` clears exactly `current −
;; baseline` and leaves the baseline intact.
(defonce ^:private baseline-registrations
  ;; {kind #{id …}} captured once at first init; nil until captured. Holds the
  ;; framework/bundle-init registrations that intentionally span pages.
  (atom nil))

(defn- capture-registration-baseline! []
  (when (nil? @baseline-registrations)
    (reset! baseline-registrations
            (into {} (map (fn [k] [k (rf.registrar/ids k)])) rf.registrar/kinds))))

(defn- ensure-init! []
  (ensure-adapter!)
  ;; Snapshot the framework baseline after the adapter's process-global installs
  ;; and before the cell about to be evaluated registers anything (rf2-u4pqs).
  (capture-registration-baseline!)
  (ensure-app-frame!))

;; ---------------------------------------------------------------------------
;; JS entry points
;; ---------------------------------------------------------------------------

;; render-root cache, keyed by the target DOM element, so re-render on
;; Mod-Enter reuses the same React root (and a Reaction stays live).
(defonce ^:private roots (atom {}))

(defn- frame-bind-component
  "Bind the cell's render to `app-frame` so a render-time `subscribe` /
  `dispatch` inside a PLAIN Reagent fn resolves (EP-0002 carried
  invariant).

  Playground cells are author-written plain `(defn foo [] …)` fns, NOT
  `reg-view`-registered components. The frame-provider React-context tier
  (`views/current-frame`) only resolves for components carrying the
  `:contextType` static-field wiring `reg-view*` attaches — a plain fn's
  `(.-context cmp)` is the no-provider sentinel, so its render-time
  `subscribe` would resolve nil → `:rf.error/no-frame-context` and the
  render throws (the runtime never synthesises a frame from absence).

  So we re-establish the frame via the DYNAMIC-VAR tier instead: when the
  cell value is a component-invocation vector `[f & args]` whose head `f`
  is a fn, wrap that head in a fn that re-binds `*current-frame*` to
  `app-frame` on every call via `with-frame` (API-shrink #1, rf2-csbbwu
  removed the public `frame-bound-fn*` — `capture-frame` / `with-frame`
  are the public carry/scope primitives; this is the 3-line idiom that
  composes them for an arbitrary already-held fn). The wrapped fn re-binds
  `*current-frame*` on EVERY invocation — including each React render —
  so the plain-fn's `subscribe`/`dispatch` resolve to `:rf/default`
  regardless of the (reg-view-only) context tier. A non-fn head (e.g. a
  plain `[:div …]` hiccup tag) needs no scope and rides through untouched."
  [component]
  (if (and (vector? component)
           (fn? (first component)))
    (let [f (first component)]
      (assoc component 0 (fn [& args] (rf/with-frame app-frame (apply f args)))))
    component))

(defn ^:export renderLast
  "Render entry (cljs-rf2 cells). Eval `src` at the SCI top level (so
  a leading `(require ...)`'s aliases reach sibling forms — same reason
  the JS bootstrap does NOT wrap render-cell source in `(do ...)`), then
  mount the LAST form's value (a hiccup vector or component vector) into
  `target-el` via reagent2's React-19 root API. Reuses an existing root
  for `target-el` so edits re-render in place."
  [src target-el]
  (ensure-init!)
  ;; Evaluate the cell under the app frame's scope so a top-level
  ;; `dispatch-sync` in the cell source resolves to `:rf/default` (EP-0002 —
  ;; the runtime never infers a frame from absence). The dynamic `with-frame`
  ;; binding is in effect for the synchronous SCI eval.
  (let [component (rf/with-frame app-frame
                    (sci/eval-string* sci-ctx src))
        root (or (get @roots target-el)
                 (let [rt (rdc/create-root target-el)]
                   (swap! roots assoc target-el rt)
                   rt))]
    ;; Mount under the SCOPE-only `rf/frame-provider {:frame …}`
    ;; (scopes any `reg-view`'d descendant the cell author writes to the
    ;; ALREADY-CREATED `app-frame`) AND frame-bind the cell's own component fn
    ;; so a render-time `subscribe` inside a PLAIN fn resolves `:rf/default`
    ;; via the dynamic-var tier — the React-context tier alone reaches only
    ;; reg-views. EP-0024 (amended) merged the family into ONE config-shaped
    ;; `frame-provider` dispatched on the prop map: `{:frame …}` is SCOPE-only
    ;; (provide an already-created frame id), `{:id …}` ENSURES a named frame.
    ;; `app-frame` is created once in `ensure-init!`, so the SCOPE-only
    ;; `{:frame …}` shape is correct here.
    ;; A cell whose last form isn't hiccup (e.g. a registrations-only cell
    ;; ending on a reg-* call) renders its PRINTED value instead — so the
    ;; visible result is the returned id, per Conventions §reg-* return-value.
    (rdc/render root [rf/frame-provider {:frame app-frame}
                      (if (vector? component)
                        (frame-bind-component component)
                        [:code (pr-str component)])])
    nil))

;; ---------------------------------------------------------------------------
;; Instant-navigation teardown (rf2-io9mdr)
;; ---------------------------------------------------------------------------

(defn ^:export disposePage
  "Release every PAGE-OWNED resource this bundle created for the OUTGOING
  document. The JS bootstrap calls it on each Material `navigation.instant`
  page swap (a `window.document$` emission) BEFORE the incoming page's cells
  mount. Three process-global leaks close here:

    1. Detached React roots. `roots` is a cache keyed by each cell's result
       element. An instant nav discards the whole `<main>` (and every cell in
       it) WITHOUT telling React, so those roots stay mounted — holding
       component instances, effects, and live Reactions/subscriptions — on
       now-detached DOM, accumulating one dead root per cell per visited page.
       We `unmount` every cached root (React runs its teardown, releasing the
       Reactions) and drop the cache.

    2. Stale frames. Cells author their OWN frames with page-agnostic ids
       (`:app`, `:demo`, `:orders`, the shared `:rf/default`, …), and those
       live in the process-global frame registry. A new page's
       `make-frame` / `frame-root` for a colliding id is IDEMPOTENT REPLACEMENT
       (Spec 002 §Duplicate id policy): durable app-db is PRESERVED and
       `:initial-events` is re-recorded but NEVER REPLAYED — so the incoming
       page inherits the prior page's app-db and its intended seed is skipped.
       Destroying every live frame here (the `destroy-frame!` half of the
       spec's `destroy-frame!` + re-seat full-replace idiom) forces the next
       page's cells to recreate + RE-SEED their frames from scratch, so
       navigating between pages can't reuse another cell's frame state and
       returning to a page reproduces its documented initial state.

    3. Page-owned registrations (rf2-u4pqs). Cells write `(kind, id)`
       registrations straight into the process-global registrar
       (`reg-event`/`reg-sub`/`reg-view`/`reg-machine`), and those are NOT
       scoped to the outgoing document. Re-eval on the NEXT page overwrites only
       COLLIDING ids — it does NOT protect a later page that dispatches or
       subscribes to an id it never registers itself: without teardown the
       outgoing cell's leaked handler still resolves, so the fresh page silently
       reuses another cell's registration (the rf2-io9mdr isolation break this
       step closes). We clear exactly the page-owned set — `current − baseline`,
       where `baseline` is the framework/bundle-init snapshot captured at
       `ensure-init!` — via `rf.registrar/unregister!`, so a later page's dispatch/
       subscribe through a cleared page-only id raises the ordinary
       `:rf.error/no-such-handler` / `:rf.error/no-such-sub` instead of hitting a
       leaked handler.

  NOT reset: the adapter install (process-global, reused across pages) and the
  framework registrations the machines/flows artefacts install at bundle init
  (the `:rf/machine` sub, `:rf.machine/*` fxs, `:rf/flow` slots — global, not
  page-owned; a machine/flow cell on a later page still resolves them). Those
  are the BASELINE captured before any cell evaluated, so step 3's
  `current − baseline` diff never clears them.

  Idempotent + defensive: safe to call when nothing was created (empty cache,
  no frames) and each teardown is guarded so one failure can't strand the rest.
  Returns nil."
  []
  ;; 1. Unmount + forget every cached root (releases detached roots + Reactions).
  (doseq [[_el root] @roots]
    (try (rdc/unmount root) (catch :default _ nil)))
  (reset! roots {})
  ;; 2. Destroy every live frame so the next page re-seeds from scratch.
  ;;    `frame-ids` is evaluated ONCE (a snapshot set), so destroying — which
  ;;    mutates the live registry — can't disturb the iteration.
  (doseq [id (rf/frame-ids)]
    (try (rf/destroy-frame! id) (catch :default _ nil)))
  ;; 3. Clear page-owned registrations (rf2-u4pqs) so a later page cannot
  ;;    dispatch/subscribe through an outgoing cell's (kind,id) it never
  ;;    re-registered. `current − baseline` is exactly the cells' registrations;
  ;;    the framework/bundle-init baseline (captured in `ensure-init!`) is
  ;;    preserved. The per-kind id set is realised ONCE before removal (a
  ;;    snapshot — `unregister!` mutates the registry), and each removal is
  ;;    guarded so one failure can't strand the rest. No-op before any cell ran
  ;;    (baseline nil ⇒ nothing page-owned yet).
  (when-let [baseline @baseline-registrations]
    (doseq [kind rf.registrar/kinds]
      (let [baseline-ids (get baseline kind #{})]
        (doseq [id (rf.registrar/ids kind)]
          (when-not (contains? baseline-ids id)
            (try (rf.registrar/unregister! kind id) (catch :default _ nil)))))))
  nil)

;; ---------------------------------------------------------------------------
;; Install the JS-visible global the bootstrap reads.
;; ---------------------------------------------------------------------------

(defn ^:export init []
  (set! (.-rf2sci js/window)
        #js {:renderLast   renderLast
             :disposePage  disposePage
             ;; Test-only introspection (rf2-io9mdr): the count of live cached
             ;; roots, so the instant-nav smoke can prove `disposePage` released
             ;; the outgoing page's detached roots rather than leaking them.
             :liveRootCount (fn [] (count @roots))}))

(init)
