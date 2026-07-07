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

    - `dispatch` / `dispatch-sync` / `subscribe` are macro-only on the
      public surface (no same-named fn-alias — the fns are
      `dispatch*` / `dispatch-sync*` / `subscribe*`). We add explicit
      `dispatch` / `dispatch-sync` / `subscribe` entries to the SCI
      `re-frame.core` namespace bound to those `*` fns, so cells call
      `rf/dispatch` / `rf/subscribe` exactly as in real code.

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
  id`), and registers the `:rf/machine` / `:rf/machine-has-tag?`
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
            [re-frame.views]
            [re-frame.machines]
            ;; flows artefact (Spec 007): required for its late-bind hooks so
            ;; `rf/reg-flow` (a copy-ns'd core fn-alias) is live in cells —
            ;; same pattern as re-frame.machines above (rf2 guide page 7).
            [re-frame.flows]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
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
(defn- playground-dispatch
  ([event]      (rf/dispatch* event {:frame app-frame}))
  ([event opts] (rf/dispatch* event (merge {:frame app-frame} opts))))

(defn- playground-dispatch-sync
  ([event]      (rf/dispatch-sync* event {:frame app-frame}))
  ([event opts] (rf/dispatch-sync* event (merge {:frame app-frame} opts))))

(defn- playground-subscribe
  ([query-v]      (rf/subscribe* query-v {:frame app-frame}))
  ([query-v opts] (rf/subscribe* query-v (merge {:frame app-frame} opts))))

(def rf-ns (sci/create-ns 're-frame.core nil))

;; `reg-view` is a JVM-only defn-shape macro on the public surface (its
;; expansion lives in re-frame.core-reg-view-macro/expand-reg-view). Cells
;; want the same sugar, so this SCI macro mirrors that expansion — register
;; the render fn via the REAL `reg-view*`, inject `dispatch` / `subscribe`
;; as locals from a render-time `make-capture-frame` (so frame resolution
;; is genuine: the harness frame by default, or a cell-created
;; `frame-provider`'s frame via the context tier), `def` the var to
;; `(rf/view id)`, and return the id per Conventions §`reg-*` return-value.
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
                              [handle     (list 're-frame.core/make-capture-frame
                                                (list 're-frame.core/current-frame-id)
                                                {:dispatch-opts {:source :ui}})
                               'dispatch  (list :dispatch handle)
                               'subscribe (list :subscribe handle)]
                              (cons 'do body))))
            (list 'def sym (list 're-frame.core/view id))
            id))))  ;; the do returns the id form's value, per reg-* contract

;; copy-ns brings every public runtime var of re-frame.core into SCI —
;; that includes the reg-* fn-aliases (reg-event, reg-sub, reg-fx,
;; reg-cofx, ...), plus init!, configure, clear-event,
;; current-frame-id, capture-frame, app-db-value, etc.
;; The macro-only public names (dispatch/dispatch-sync/subscribe) have no
;; same-named runtime var so they are NOT in the copy; we add them below.
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
   {:namespaces {'re-frame.core        re-frame-core-namespace
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

(defonce ^:private inited? (atom false))

;; EP-0002 (carried-frame invariant): `rf/init!` installs the adapter but the
;; runtime never synthesises a frame from absence. The playground is a
;; consumer app, so it establishes its own app frame — `app-frame`,
;; `:rf/default` (defined up top) — once, here. Cells then dispatch/subscribe
;; against it without any per-cell frame boilerplate: `renderLast` evaluates
;; each cell's source under a `with-frame :rf/default` scope (so a top-level
;; `dispatch-sync` in the cell resolves), mounts the cell's component under a
;; `frame-provider` (so the render-time `subscribe` resolves), and the SCI-
;; bound `dispatch`/`dispatch-sync`/`subscribe` wrappers default to `app-frame`
;; (so a DEFERRED `:on-click` dispatch resolves too).
(defn- ensure-init! []
  (when-not @inited?
    (rf/init! reagent-slim-adapter/adapter)
    (rf/reg-frame app-frame {})
    (reset! inited? true)))

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
    ;; Mount under the merged `rf/frame-provider {:frame …}` SCOPE-only shape
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
;; Install the JS-visible global the bootstrap reads.
;; ---------------------------------------------------------------------------

(defn ^:export init []
  (set! (.-rf2sci js/window)
        #js {:renderLast renderLast}))

(init)
