(ns re-frame.core
  "Public API surface for re-frame2. Per spec/API.md.

  Users `(:require [re-frame.core :as rf])` and call `rf/dispatch`,
  `rf/reg-event-fx`, etc. Internal namespaces are not part of the
  contract; per-namespace docs carry the design rationale.

  Topology — this ns is a thin façade:

    - Optional-artefact wrappers (rf2-hoiu) live in `re-frame.core-
      <feature>` sibling namespaces (`flows`, `routing`, `schemas`,
      `machines`, `ssr`, `epoch`, `http`); each looks up its target
      fn through the late-bind hook table so requiring this ns does
      NOT pull the feature artefacts.
    - Macro-helper code is factored (per rf2-4rnui) into three
      siblings — `core-reg-macros`, `core-call-site-macros`,
      `core-reg-view-macro` — keeping each leaf under the rf2-zkca8
      250-LoC ceiling. The user-facing `defmacro`s themselves stay in
      THIS ns (so `rf/reg-event-db` etc. resolve alias-qualified per
      Clojure's standard `ns-alias/Var` lookup); each is a one-line
      shell that delegates to the sibling-ns expansion helper.

  File-naming uses the flat dash-form (`core_X.cljc`, per rf2-2vbm):
  CLJS `goog.provide` for `re-frame.core` overwrites its parent
  object, which would wipe a previously-loaded `re-frame.core.X`."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.router :as router]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.cofx :as cofx]
            [re-frame.subs :as subs]
            [re-frame.interceptor :as interceptor]
            [re-frame.std-interceptors :as std-interceptors]
            [re-frame.privacy :as privacy]
            [re-frame.spec :as spec]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]
            [re-frame.trace.projection :as trace-projection]
            [re-frame.event-emit :as event-emit]
            [re-frame.error-emit :as error-emit]
            [re-frame.elision :as elision]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.core-flows    :as rf-flows]
            [re-frame.core-routing  :as rf-routing]
            [re-frame.core-schemas  :as rf-schemas]
            [re-frame.core-machines :as rf-machines]
            [re-frame.core-ssr      :as rf-ssr]
            [re-frame.core-epoch    :as rf-epoch]
            [re-frame.core-http     :as rf-http]
            ;; Macro-helper carve-out (rf2-4rnui).
            [re-frame.core-reg-macros        :as rm
             #?@(:cljs [:include-macros true])]
            [re-frame.core-call-site-macros  :as csm]
            [re-frame.core-reg-view-macro    :as rvm]
            [re-frame.substrate.plain-atom :as plain-atom]
            #?@(:cljs [[re-frame.views :as views]]))
  ;; `bound-fn` shadows clojure.core/bound-fn — the v2 surface
  ;; deliberately reuses the name (per Spec 002 §bound-fn).
  (:refer-clojure :exclude [bound-fn])
  ;; The macros are defined in this ns's `#?(:clj ...)` blocks below.
  ;; CLJS users see them under `rf/<name>` via this self-`:require-
  ;; macros`, so `(:require [re-frame.core :as rf])` is the only import
  ;; CLJS apps need.
  #?(:cljs (:require-macros
             [re-frame.core :refer [reg-event-db reg-event-fx reg-event-ctx
                                    reg-sub reg-fx reg-cofx reg-frame
                                    reg-flow reg-route reg-app-schema reg-app-schemas
                                    reg-error-projector reg-head
                                    reg-view reg-machine
                                    dispatch dispatch-sync subscribe inject-cofx
                                    with-frame bound-fn with-fx-overrides
                                    with-managed-request-stubs]])))

;; ---- CLJS fn-aliases for registration ------------------------------------
;;
;; Source-coord capture on CLJS rides the JVM-emitted macros above. The
;; fns themselves honour `*pending-coords*` either way — the macros are
;; the *capture* path; the merge path is in the fns. Apps that reach
;; the registration fn programmatically (HoF, runtime registration) use
;; these aliases.

#?(:cljs
   (do
     (def reg-event-db    events/reg-event-db)
     (def reg-event-fx    events/reg-event-fx)
     (def reg-event-ctx   events/reg-event-ctx)
     (def reg-sub         subs/reg-sub)
     (def reg-fx          fx/reg-fx)
     (def reg-cofx        cofx/reg-cofx)
     (def reg-frame       frame/reg-frame)
     (def reg-flow        rf-flows/reg-flow)
     (def reg-route       rf-routing/reg-route)
     (def reg-app-schema  rf-schemas/reg-app-schema)
     (def reg-app-schemas rf-schemas/reg-app-schemas)
     (def reg-machine*    rf-machines/reg-machine*)))

;; ---- reg-* macros (JVM-only; CLJS sees them via :require-macros) --------
;;
;; Each `defreg-macro` form below expands (per rf2-bd6zl) to a
;; `defmacro` IN THIS ns — so `rf/reg-event-db` resolves alias-
;; qualified per Clojure's `ns-alias/Var` lookup. The expansion
;; captures source-coords at the user's call site and splices args
;; through to the fully-qualified delegate fn.

#?(:clj
   (do
     (rm/defreg-macro reg-event-db events/reg-event-db
       "Register a `(fn [db event-vec] new-db)` event handler under `id`.
       Captures source-coords (Spec 001) at this call site. See
       `re-frame.events/reg-event-db` for the full signature.")

     (rm/defreg-macro reg-event-fx events/reg-event-fx
       "Register a `(fn [cofx event-vec] effect-map)` event handler under
       `id`. Effect-map is a closed shape: only `:db` and `:fx` at the
       top level. Captures source-coords (Spec 001) at this call site.
       See `re-frame.events/reg-event-fx` for the full signature.")

     (rm/defreg-macro reg-event-ctx events/reg-event-ctx
       "Register a `(fn [context] context)` full-context event handler
       under `id`. Advanced — most handlers want `reg-event-db` or
       `reg-event-fx` instead. Captures source-coords (Spec 001) at
       this call site. See `re-frame.events/reg-event-ctx` for the
       full signature.")

     (rm/defreg-macro reg-sub subs/reg-sub
       "Register a subscription under `id`. Layer-1 subs read `app-db`
       directly; layer-2 subs chain off other subs via `:<-`. Captures
       source-coords (Spec 001) at this call site. See
       `re-frame.subs/reg-sub` for the full signature.")

     (rm/defreg-macro reg-fx fx/reg-fx
       "Register an effect handler under `id`. Handler signature is
       `(fn [ctx args] ...)`; runs when a `reg-event-fx` returns an
       effect-map carrying `[id args]` inside its `:fx` vector.
       Captures source-coords (Spec 001) at this call site. See
       `re-frame.fx/reg-fx` for the full signature.")

     (rm/defreg-macro reg-cofx cofx/reg-cofx
       "Register a coeffect handler under `id` — a source of input
       data injected into an event handler's `:coeffects` map via
       `inject-cofx`. Captures source-coords (Spec 001) at this call
       site. See `re-frame.cofx/reg-cofx` for the full signature.")

     (rm/defreg-macro reg-frame frame/reg-frame
       "Register a frame. Captures source-coords (Spec 001) at this
       call site. See `re-frame.frame/reg-frame` for the full
       signature."
       {:arglists '([id metadata])})

     (rm/defreg-macro reg-flow rf-flows/reg-flow
       "Register a flow. Captures source-coords (Spec 001) at this
       call site. Implementation ships in `day8/re-frame2-flows`
       (rf2-tfw3); apps must add the artefact and require
       `re-frame.flows` at boot. See `re-frame.core-flows/reg-flow`
       for the full signature.")

     (rm/defreg-macro reg-route rf-routing/reg-route
       "Register a route under `id`. `metadata` is a map keyed at
       minimum on `:path` (URL pattern, Spec 012 §Pattern syntax).
       Captures source-coords (Spec 001) at this call site.
       Implementation ships in `day8/re-frame2-routing` (rf2-k682);
       apps must add the artefact and require `re-frame.routing` at
       boot. See `re-frame.core-routing/reg-route` for the full
       signature."
       {:arglists '([id metadata])})

     (rm/defreg-macro reg-app-schema rf-schemas/reg-app-schema
       "Register a Malli schema at a path inside app-db (frame-scoped
       per Spec 010). Captures source-coords (Spec 001) at this call
       site. Implementation ships in `day8/re-frame2-schemas`
       (rf2-p7va). See `re-frame.core-schemas/reg-app-schema` for the
       full signature."
       {:arglists '([path schema] [path schema opts])})

     (rm/defreg-macro reg-app-schemas rf-schemas/reg-app-schemas
       "Bulk-register a `{path -> schema}` map against the active frame
       (or the `:frame` opt). Plural form of `reg-app-schema`. Captures
       source-coords (Spec 001) at this call site. Implementation ships
       in `day8/re-frame2-schemas` (rf2-p7va). See
       `re-frame.core-schemas/reg-app-schemas` for the full signature."
       {:arglists '([path->schema] [path->schema opts])})

     (rm/defreg-macro reg-error-projector rf-ssr/-reg-error-projector
       "Register an error projector — `(trace-event) -> public-error-
       map`. Frames opt in via the `:ssr` config's `:public-error-id`
       key. Captures source-coords (Spec 001) at this call site. Per
       Spec 011 §Server error projection.")

     (rm/defreg-macro reg-head rf-ssr/-reg-head
       "Register a head-fragment producer — `(fn [db route] head-
       model)`. `id` is a namespaced keyword (e.g. `:my.app/article`);
       routes name a head via `:head` route metadata. Captures source-
       coords (Spec 001) at this call site. Per Spec 011 §Head/meta
       contract.")))

;; ---- reg-machine (bespoke — per-element coord stamping) -----------------

#?(:clj
   (defmacro reg-machine
     "Register a machine as an event handler. Captures source-coords
     (Spec 001) at this call site plus a per-element coord index under
     `:rf.machine/source-coords` (Spec 005 §Source-coord stamping;
     dev-only — DCE'd under `goog.DEBUG=false`). Implementation ships
     in `day8/re-frame2-machines` (rf2-xbtj). For runtime registration
     use `reg-machine*`."
     [machine-id machine]
     (rm/expand-reg-machine (meta &form)
                            (symbol (str (ns-name *ns*)))
                            *file*
                            machine-id
                            machine)))

;; ---- public helpers re-exported for test access (rf2-4rnui) -------------
;;
;; Re-exposed because pre-split tests reach `re-frame.core/expand-reg-
;; view` and `re-frame.core/parse-reg-view-args` directly. Preserved as
;; CLJ-only aliases — the helpers themselves are JVM-only (used at
;; macro-expansion time).

#?(:clj
   (do
     (def expand-reg-view             rvm/expand-reg-view)
     (def parse-reg-view-args         rvm/parse-reg-view-args)))

;; ---- view registration ---------------------------------------------------

(defn reg-view*
  "Plain-fn surface for view registration. Use for runtime registration
  (computed ids, library generation, Form-3 / `create-class` bodies)
  where the `reg-view` macro's defn-shape doesn't fit. Optional metadata
  is merged with any pending source-coords from a wrapping `reg-view`.
  Per Spec 004 §reg-view*."
  ([id render-fn]
   (reg-view* id {} render-fn))
  ([id metadata render-fn]
   #?(:cljs
      (views/reg-view* id (source-coords/merge-coords metadata) render-fn)
      :clj
      (registrar/register! :view id (assoc (source-coords/merge-coords metadata)
                                           :handler-fn render-fn)))
   id))

(defn view
  "Runtime-lookup handle for a registered view. Returns the registered
  render fn (or nil if not registered) — call with the view's invocation
  args to yield the hiccup tree. Per Spec 004 §Calling a registered view."
  [id]
  (when-let [meta (registrar/lookup :view id)]
    (:handler-fn meta)))

#?(:clj
   (defmacro reg-view
     "Register a view as a defn-shape macro. Auto-derives id from
     `(keyword (str *ns*) (str sym))` (override via `^{:rf/id :id}` meta
     on sym), auto-injects lexical `dispatch` / `subscribe` bound to the
     surrounding frame, defs the symbol and registers under the id.
     For runtime registration with computed ids or non-defn bodies, use
     `reg-view*`. Per Spec 004 §reg-view."
     {:arglists '([sym args body+] [sym docstring args body+])}
     [sym & more]
     (rvm/expand-reg-view (meta &form)
                          (symbol (str (ns-name *ns*)))
                          *file*
                          sym
                          more)))

;; ---- CLJS reg-error-projector / reg-head fn-aliases --------------------

(def ^:private -reg-error-projector rf-ssr/-reg-error-projector)
(def ^:private -reg-head            rf-ssr/-reg-head)

#?(:cljs
   (do
     (def reg-error-projector -reg-error-projector)
     (def reg-head            -reg-head)))

;; ---- SSR re-exports (Spec 011, rf2-uo7v) ---------------------------------

(def render-to-string rf-ssr/render-to-string)
(def render-tree-hash rf-ssr/render-tree-hash)
(def project-error    rf-ssr/project-error)
(def render-head      rf-ssr/render-head)
(def active-head      rf-ssr/active-head)
(def head-model->html rf-ssr/head-model->html)

;; ---- frame management ----------------------------------------------------

(def make-frame    frame/make-frame)
(def reset-frame   frame/reset-frame!)
(def destroy-frame frame/destroy-frame!)

;; ---- flows / schemas — plain-fn re-exports -------------------------------

(def clear-flow             rf-flows/clear-flow)
(def app-schema-at          rf-schemas/app-schema-at)
(def app-schemas            rf-schemas/app-schemas)
(def app-schemas-digest     rf-schemas/app-schemas-digest)
(def set-schema-validator!  rf-schemas/set-schema-validator!)
(def set-schema-explainer!  rf-schemas/set-schema-explainer!)

;; ---- clearing ------------------------------------------------------------

(def clear-event events/clear-event)
(def clear-sub   subs/clear-sub)
(def clear-fx    fx/clear-fx)
(def clear-subscription-cache! subs/clear-subscription-cache!)

;; ---- dispatch and subscribe ----------------------------------------------
;;
;; Per rf2-ts1a — each surface ships as a macro + `*`-fn pair (Q1=C).
;; The macros expand to `re-frame.core/dispatch*` / `subscribe*` etc.,
;; so those defs must live here.

(def dispatch*       router/dispatch!)
(def dispatch-sync*  router/dispatch-sync!)
(def subscribe-value subs/subscribe-value)
(def unsubscribe     subs/unsubscribe)
(def compute-sub     subs/compute-sub)

(defn subscribe*
  "Runtime-callable fn form of `subscribe` (HoF / programmatic callers).
  Arities mirror `re-frame.subs/subscribe`."
  ([query-v]            (subs/subscribe query-v))
  ([frame-id query-v]   (subs/subscribe frame-id query-v)))

(defn inject-cofx*
  "Runtime-callable fn form of `inject-cofx` (HoF / programmatic callers).
  The macro form routes through the 3-arity with a `cofx/no-value`
  sentinel."
  ([cofx-id]                 (cofx/inject-cofx cofx-id))
  ([cofx-id value]           (cofx/inject-cofx cofx-id value nil))
  ([cofx-id value call-site] (cofx/inject-cofx cofx-id value call-site)))

#?(:clj
   (defmacro dispatch
     "Enqueue `event-vec` on the target frame's router; returns nil
     immediately, BEFORE the handler runs. Captures call-site coords
     (rf2-ts1a) for error-trace attribution. For HoF / programmatic use
     call `dispatch*`. Per Spec 002 §Routing."
     ([event-vec]
      (csm/build-dispatch-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                               event-vec nil))
     ([event-vec opts]
      (csm/build-dispatch-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                               event-vec opts))))

#?(:clj
   (defmacro dispatch-sync
     "Run `event-vec` end-to-end synchronously; the router drains to
     fixed point. For tests / REPL / bootstrap only — never call from
     inside a running event handler (raises `:rf.error/dispatch-sync-
     in-handler`). Captures call-site coords (rf2-ts1a). For HoF /
     programmatic use call `dispatch-sync*`. Per Spec 002 §dispatch-sync."
     ([event-vec]
      (csm/build-dispatch-sync-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                    event-vec nil))
     ([event-vec opts]
      (csm/build-dispatch-sync-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                    event-vec opts))))

#?(:clj
   (defmacro subscribe
     "Return a reaction whose value is the registered sub's current
     output for `query-v` (`[sub-id & args]`); deref to read. 2-arity
     targets an explicit frame, otherwise resolves via `current-frame`.
     Use `subscribe-value` for a one-shot read; use `subscribe*` for
     HoF / programmatic callers. Captures call-site coords (rf2-ts1a).
     Per Spec 006 §Lookup algorithm."
     ([query-v]
      (csm/build-subscribe-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                nil query-v))
     ([frame-id query-v]
      (csm/build-subscribe-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                frame-id query-v))))

#?(:clj
   (defmacro inject-cofx
     "Used as an interceptor in the interceptor-vector of `reg-event-*`.
     Builds a `:before`-only interceptor that runs the cofx registered
     under `cofx-id` and merges its result into the handler's
     `:coeffects`. 2-arity `(inject-cofx :id value)` passes a per-call
     value. Captures call-site coords (rf2-ts1a). For HoF / programmatic
     use call `inject-cofx*`. See `re-frame.cofx/inject-cofx` for the
     full signature."
     ([cofx-id]
      (csm/build-inject-cofx-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                  cofx-id nil))
     ([cofx-id value]
      (csm/build-inject-cofx-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                  cofx-id value))))

;; ---- frame-aware closures (runtime side) ---------------------------------
;;
;; Per rf2-d4sf the public `current-frame` consults the `:adapter/
;; current-frame` late-bind hook on CLJS so the React-context tier of
;; the resolution chain is live at every user-facing surface that flows
;; through `(dispatcher)` / `(subscriber)` / `bound-fn`. JVM falls
;; through to `frame/current-frame` (dynamic-var → `:rf/default`).

(defn current-frame
  "Return the active frame at the call site. Resolution chain: dynamic
  var -> React context (CLJS only, via `:adapter/current-frame` late-
  bind hook) -> `:rf/default`. Per Spec 002 §Reading the frame from
  React context."
  []
  #?(:cljs (if-let [f (late-bind/get-fn :adapter/current-frame)]
             (f)
             (frame/current-frame))
     :clj  (frame/current-frame)))

(defn dispatcher
  "Return a fn that dispatches under the current frame, captured at call
  time so closures need not thread it. Per Spec 004 §Affordance for
  plain fns:
    (let [d (rf/dispatcher)] [:button {:on-click #(d [:inc])} ...])"
  []
  (let [frame (current-frame)]
    (fn dispatch-fn
      ([event]      (dispatch* event {:frame frame}))
      ([event opts] (dispatch* event (assoc opts :frame frame))))))

(defn subscriber
  "Return a fn that subscribes under the current frame, captured at call
  time. Sibling of `dispatcher`. Per Spec 004 §Affordance for plain fns."
  []
  (let [frame (current-frame)]
    (fn subscribe-fn
      [query-v]
      (subs/subscribe frame query-v))))

;; ---- frame-scope lexical macros ------------------------------------------

#?(:clj
   (defmacro with-frame
     "Run `body` with `*current-frame*` bound to the given frame-id.
     Two shapes — first arg is the discriminator:
       (with-frame :keyword body+)        pin to an existing frame-id;
       (with-frame [sym expr] body+)      eval expr, bind, run, destroy.
     For async closures after body returns, capture via `bound-fn` /
     `dispatcher` / `subscriber`. Per Spec 002 §with-frame."
     {:arglists '([frame-id body+] [[sym expr] body+])}
     [bindings & body]
     (rvm/expand-with-frame bindings body)))

#?(:clj
   (defmacro bound-fn
     "Return a fn that captures the current frame and re-binds
     `*current-frame*` inside its body. Per Spec 002 §bound-fn."
     [argv & body]
     (rvm/expand-bound-fn argv body)))

#?(:clj
   (defmacro with-fx-overrides
     "Bind a per-call `:fx-overrides` map for `body`'s lexical scope —
     test-support sugar over `(rf/dispatch ev {:fx-overrides {...}})`.
     Precedence: per-call opt > lexical `with-fx-overrides` > per-frame
     `:fx-overrides`. Composes with `with-frame`. Per Spec 002
     §`:fx-overrides`."
     [overrides-map & body]
     `(binding [re-frame.router/*fx-overrides* ~overrides-map]
        ~@body)))

#?(:clj
   (defmacro with-managed-request-stubs
     "Install stubs, run body, uninstall. `stubs` is
     `{[method url] {:reply <:ok|:failure>}}`. Implementation ships in
     `day8/re-frame2-http` (rf2-5kpd). Per Spec 014 §Testing."
     [stubs & body]
     `(re-frame.core/with-managed-request-stubs* ~stubs (fn [] ~@body))))

;; ---- view ergonomics (CLJS only) -----------------------------------------
;;
;; frame-provider is a Reagent component re-exported here as the
;; canonical user-facing surface (per Spec 002 §What `frame-provider`
;; is); the impl lives in re-frame.views to keep React/Reagent off the
;; JVM load path.

#?(:cljs (def frame-provider views/frame-provider))

;; ---- routing helpers ------------------------------------------------------

(def match-url   rf-routing/match-url)
(def route-url   rf-routing/route-url)
(def route-link  rf-routing/route-link)

;; ---- machine helpers ------------------------------------------------------
;;
;; Plain-fn `reg-machine*` for code-gen pipelines / conformance corpus
;; registering without a literal spec form. Per Spec 005 §reg-machine vs
;; reg-machine* (rf2-8bp3).

#?(:clj
   (def reg-machine* rf-machines/reg-machine*))

(def create-machine-handler rf-machines/create-machine-handler)
(def machine-transition     rf-machines/machine-transition)
(def machines               rf-machines/machines)
(def machine-meta           rf-machines/machine-meta)
(def machine-by-system-id   rf-machines/machine-by-system-id)
(def dispatch-to-system     rf-machines/dispatch-to-system)
(def sub-machine            rf-machines/sub-machine)
(def has-tag?               rf-machines/has-tag?)

;; ---- introspection (Spec 002 §The public registrar query API) -----------

(def handlers     registrar/handlers)
(def handler-meta registrar/handler-meta)
(def handler-ids  registrar/ids)
(def registry-summary registrar/all-kinds-with-counts)
(def frame-ids    frame/frame-ids)
(def frame-meta   frame/frame-meta)

(defn get-frame-db
  "Return the current `app-db` value (plain map) for the named frame, or
  `nil` if not registered. Value-form accessor (no deref). Per Spec 002
  §The public registrar query API."
  [frame-id]
  (frame/frame-app-db-value frame-id))

(defn snapshot-of
  "Return the value at `path` in a frame's app-db — convenience over
  `(get-in (rf/get-frame-db frame-id) path)`. Frame resolution:
  `(:frame opts)` if supplied, else `(current-frame)`. Returns `nil` if
  the frame is missing or the path resolves to nothing. Per Spec 002
  §The public registrar query API."
  ([path] (snapshot-of path nil))
  ([path opts]
   (let [frame-id (or (:frame opts) (current-frame))]
     (get-in (frame/frame-app-db-value frame-id) path))))

(defn sub-cache
  "Inspect a frame's runtime sub-cache — CLJS-only, returns
  `{query-v {:value v :ref-count n}}`. JVM returns `nil` (cache has no
  reaction values). No-arg form uses the active frame. Per Spec 002
  §The public registrar query API."
  ([] (sub-cache (current-frame)))
  ([frame-id]
   (subs/sub-cache-snapshot frame-id)))

(def sub-topology subs/sub-topology)

;; ---- interceptors --------------------------------------------------------

(def ->interceptor   interceptor/->interceptor)
(def get-coeffect    interceptor/get-coeffect)
(def assoc-coeffect  interceptor/assoc-coeffect)
(def get-effect      interceptor/get-effect)
(def assoc-effect    interceptor/assoc-effect)
(def inject-cofx     cofx/inject-cofx)
(def path            std-interceptors/path)
(def unwrap          std-interceptors/unwrap)

;; ---- privacy / spec / trace / emit / elision (Spec 009, 010) -------------

(def with-redacted        privacy/with-redacted)
(def sensitive?           trace/sensitive?)
(def validate-at-boundary spec/validate-at-boundary)

(def register-trace-cb!  trace/register-trace-cb!)
(def remove-trace-cb!    trace/remove-trace-cb!)
(def emit-trace!         trace/emit!)
(def trace-buffer        trace/trace-buffer)
(def clear-trace-buffer! trace/clear-trace-buffer!)

(def register-event-emit-listener!   event-emit/register-event-emit-listener!)
(def unregister-event-emit-listener! event-emit/unregister-event-emit-listener!)
(def register-error-emit-listener!   error-emit/register-error-emit-listener!)
(def unregister-error-emit-listener! error-emit/unregister-error-emit-listener!)

(def elide-wire-value               elision/elide-wire-value)
(def declare-large-path!            elision/declare-large-path!)
(def clear-large-path!              elision/clear-large-path!)
(def populate-elision-from-schemas! elision/populate-elision-from-schemas!)
(def elision-declarations           elision/declarations)
(def elision-runtime-flagged        elision/runtime-flagged)

(def group-cascades  trace-projection/group-cascades)
(def domino-bucket   trace-projection/domino-bucket)

;; ---- epoch history (Tool-Pair §Time-travel) ------------------------------

(def epoch-history      rf-epoch/epoch-history)
(def restore-epoch      rf-epoch/restore-epoch)
(def register-epoch-cb! rf-epoch/register-epoch-cb!)
(def remove-epoch-cb!   rf-epoch/remove-epoch-cb!)
(def reset-frame-db!    rf-epoch/reset-frame-db!)

;; ---- Spec 014 — :rf.http/managed -----------------------------------------

(def install-managed-request-stubs!   rf-http/install-managed-request-stubs!)
(def uninstall-managed-request-stubs! rf-http/uninstall-managed-request-stubs!)
(def with-managed-request-stubs*      rf-http/with-managed-request-stubs*)
(def reg-http-interceptor             rf-http/reg-http-interceptor)
(def clear-http-interceptor           rf-http/clear-http-interceptor)

;; ---- configure / substrate adapter / boot --------------------------------

(defn configure
  "Configure a process-level runtime knob. v1 keys:
    :epoch-history {:depth N}                       ring depth (default 50; 0 disables)
    :trace-buffer  {:depth N}                       ring depth (default 200; 0 disables)
    :sub-cache     {:grace-period-ms N}             dispose grace (default 50ms)
    :elision       {:rf.size/threshold-bytes N}     runtime auto-detect threshold (default 16384; 0 disables)
  Unknown keys silently no-op. Per-frame settings live on frame metadata.
  Per Tool-Pair §How AI tools attach."
  [knob opts]
  (case knob
    :epoch-history (when-let [f (late-bind/get-fn :epoch/configure!)]
                     (f opts))
    :trace-buffer  (trace/configure-trace-buffer! opts)
    :sub-cache     (subs/configure! opts)
    :elision       (elision/configure-elision! opts)
    nil))

(def install-adapter!     adapter/install-adapter!)
(def dispose-adapter!     adapter/dispose-adapter!)
(def current-adapter      adapter/current-adapter)
(def current-adapter-spec adapter/current-adapter-spec)

(defn- bad-init-arg!
  "Raise `:rf.error/no-adapter-specified` with a consistent reason
  string. Factored out of `init!`'s nil-check and not-map-check."
  [received]
  (throw (ex-info ":rf.error/no-adapter-specified"
                  (cond-> {:where    'rf/init!
                           :expected "adapter spec map"
                           :recovery :no-recovery
                           :reason   "rf/init! takes the adapter spec map directly — there is no keyword form, no nil form, and no default-adapter registry. Require the adapter ns and pass its `adapter` Var: (rf/init! reagent/adapter). Per rf2-agql the default-adapter registry was removed."}
                    (some? received) (assoc :received received)))))

(defn init!
  "Idempotent boot — installs a substrate adapter and ensures the
  `:rf/default` frame exists. Pass the adapter spec map directly (no
  default-adapter registry; rf2-agql):
    (require '[re-frame.adapter.reagent :as reagent])
    (rf/init! reagent/adapter)
  Non-map / nil raises `:rf.error/no-adapter-specified`. Per Spec 006
  §Adapter selection at boot."
  [adapter-map]
  (cond
    (nil? adapter-map)        (bad-init-arg! nil)
    (not (map? adapter-map))  (bad-init-arg! adapter-map)
    :else
    (do
      (when-not (adapter/current-adapter)
        (adapter/install-adapter! adapter-map))
      (frame/ensure-default-frame!)
      nil)))
