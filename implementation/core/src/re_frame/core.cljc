(ns re-frame.core
  "Public API surface for re-frame2. Per spec/API.md.

  Users `(:require [re-frame.core :as rf])` and call `rf/dispatch`,
  `rf/reg-event-fx`, etc. Internal namespaces are not part of the
  contract; per-namespace docs carry the design rationale.

  Topology — this ns is a thin façade:

    - Optional-artefact wrappers live in `re-frame.core-<feature>`
      sibling namespaces (`flows`, `routing`, `schemas`, `machines`,
      `ssr`, `epoch`, `http`); each looks up its target fn through
      the late-bind hook table so requiring this ns does NOT pull
      the feature artefacts.
    - Macro-helper code is factored into three siblings —
      `core-reg-macros` (reg-event-db/fx, reg-sub, reg-fx, reg-cofx
      expansion), `core-call-site-macros` (dispatch / dispatch-sync /
      subscribe call-site expansion), `core-reg-view-macro`
      (reg-view + view-component expansion). The boundary is the
      *responsibility* — call-site vs registration vs view-registration
      — so each helper ns owns one cohesive expansion family. The
      user-facing `defmacro`s themselves stay in THIS ns (so
      `rf/reg-event-db` etc. resolve alias-qualified per Clojure's
      standard `ns-alias/Var` lookup); each is a one-line shell that
      delegates to the sibling-ns expansion helper.

  File-naming uses the flat dash-form (`core_X.cljc`) because CLJS
  `goog.provide` for `re-frame.core` overwrites its parent object,
  which would wipe a previously-loaded `re-frame.core.X`."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.router :as router]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.cofx :as cofx]
            [re-frame.subs :as subs]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.interceptor :as interceptor]
            [re-frame.std-interceptors :as std-interceptors]
            [re-frame.privacy :as privacy]
            [re-frame.spec :as spec]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]
            [re-frame.trace.projection :as trace-projection]
            ;; JVM-only autoload for the focused-event-only cascade-DAG
            ;; aggregator (rf2-931pm). CLJS deliberately omits the
            ;; require so Closure DCE keeps the aggregator + per-fn
            ;; keyword interns out of production bundles — the
            ;; bundle-isolation gate verifies. Causa's Reactive panel
            ;; loads the ns explicitly from its tools-side build.
            #?@(:clj [[re-frame.trace.cascade]])
            [re-frame.event-emit :as event-emit]
            [re-frame.error-emit :as error-emit]
            [re-frame.elision :as elision]
            [re-frame.marks   :as marks]
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
                                    reg-http-interceptor
                                    reg-view reg-machine
                                    dispatch dispatch-sync subscribe inject-cofx
                                    with-frame bound-fn with-fx-overrides
                                    with-managed-request-stubs]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- CLJS fn-aliases for registration ------------------------------------
;;
;; Source-coord capture on CLJS rides the JVM-emitted macros above. The
;; fns themselves honour `*pending-coords*` either way — the macros are
;; the *capture* path; the merge path is in the fns. Apps that reach
;; the registration fn programmatically (HoF, runtime registration) use
;; these aliases.

#?(:cljs
   (do
     (def ^{:doc "Fn-alias of the `reg-event-db` macro for HoF / programmatic
  registration (no source-coord capture). Register a
  `(fn [db event-vec] new-db)` event handler under `id`. See
  `re-frame.events/reg-event-db` and spec/API.md §Registration."}
       reg-event-db    events/reg-event-db)
     (def ^{:doc "Fn-alias of the `reg-event-fx` macro for HoF / programmatic
  registration (no source-coord capture). Register a
  `(fn [cofx event-vec] effect-map)` event handler under `id`. See
  `re-frame.events/reg-event-fx` and spec/API.md §Registration."}
       reg-event-fx    events/reg-event-fx)
     (def ^{:doc "Fn-alias of the `reg-event-ctx` macro for HoF / programmatic
  registration (no source-coord capture). Register a
  `(fn [context] context)` full-context event handler under `id`. Advanced
  — most handlers want `reg-event-db` / `reg-event-fx`. See
  `re-frame.events/reg-event-ctx` and spec/API.md §Registration."}
       reg-event-ctx   events/reg-event-ctx)
     (def ^{:doc "Fn-alias of the `reg-sub` macro for HoF / programmatic
  registration (no source-coord capture). Register a subscription under
  `id`. See `re-frame.subs/reg-sub` and spec/API.md §Registration."}
       reg-sub         subs/reg-sub)
     (def ^{:doc "Fn-alias of the `reg-fx` macro for HoF / programmatic
  registration (no source-coord capture). Register an effect handler
  under `id`. See `re-frame.fx/reg-fx` and spec/API.md §Registration."}
       reg-fx          fx/reg-fx)
     (def ^{:doc "Fn-alias of the `reg-cofx` macro for HoF / programmatic
  registration (no source-coord capture). Register a coeffect handler —
  a source of input data injected into an event handler's `:coeffects`
  via `inject-cofx`. See `re-frame.cofx/reg-cofx` and spec/API.md
  §Registration."}
       reg-cofx        cofx/reg-cofx)
     (def ^{:doc "Fn-alias of the `reg-frame` macro for HoF / programmatic
  registration (no source-coord capture). Atomically create + register
  a frame under `id` with the given metadata. See
  `re-frame.frame/reg-frame` and spec/API.md §Registration."}
       reg-frame       frame/reg-frame)
     (def ^{:doc "Fn-alias of the `reg-flow` macro for HoF / programmatic
  registration (no source-coord capture). Register a flow. Implementation
  ships in `day8/re-frame2-flows`; require `re-frame.flows` at boot. See
  `re-frame.core-flows/reg-flow` and spec/API.md §Registration."}
       reg-flow        rf-flows/reg-flow)
     (def ^{:doc "Fn-alias of the `reg-route` macro for HoF / programmatic
  registration (no source-coord capture). Register a route under `id`;
  `metadata` carries `:path` at minimum. Implementation ships in
  `day8/re-frame2-routing`; require `re-frame.routing` at boot. See
  `re-frame.core-routing/reg-route` and spec/API.md §Registration."}
       reg-route       rf-routing/reg-route)
     (def ^{:doc "Fn-alias of the `reg-app-schema` macro for HoF / programmatic
  registration (no source-coord capture). Register a Malli schema at a
  path inside app-db (frame-scoped per Spec 010). Implementation ships
  in `day8/re-frame2-schemas`. See `re-frame.core-schemas/reg-app-schema`
  and spec/API.md §Registration."}
       reg-app-schema  rf-schemas/reg-app-schema)
     (def ^{:doc "Fn-alias of the `reg-app-schemas` macro for HoF /
  programmatic registration (no source-coord capture). Bulk-register a
  `{path -> schema}` map. Implementation ships in `day8/re-frame2-schemas`.
  See `re-frame.core-schemas/reg-app-schemas` and spec/API.md
  §Registration."}
       reg-app-schemas rf-schemas/reg-app-schemas)
     (def ^{:doc "Plain-fn surface for machine registration. Use for code-gen
  pipelines and REPL workflows that bypass the `reg-machine` macro's
  per-element source-coord stamping. Implementation ships in
  `day8/re-frame2-machines`. See `re-frame.core-machines/reg-machine*`
  and spec/API.md §Registration."}
       reg-machine*    rf-machines/reg-machine*)))

;; ---- reg-* macros (JVM-only; CLJS sees them via :require-macros) --------
;;
;; Each `defreg-macro` form below expands to a `defmacro` IN THIS ns
;; — so `rf/reg-event-db` resolves alias-qualified per Clojure's
;; `ns-alias/Var` lookup. The expansion captures source-coords at the
;; user's call site and splices args through to the fully-qualified
;; delegate fn.

#?(:clj
   (do
     (rm/defreg-event-macro reg-event-db events/reg-event-db
       "Register a `(fn [db event-vec] new-db)` event handler under `id`.
       Captures source-coords (Spec 001) at this call site. Additionally
       captures the whole `(reg-event-db :id ...)` form as a string under
       the handler's `:rf.handler/source` meta (Spec 009, rf2-xgfuy) —
       DEBUG-gated, elided in CLJS `:advanced` + `goog.DEBUG=false`
       production builds. See `re-frame.events/reg-event-db` for the
       full signature.")

     (rm/defreg-event-macro reg-event-fx events/reg-event-fx
       "Register a `(fn [cofx event-vec] effect-map)` event handler under
       `id`. Effect-map is a closed shape: only `:db` and `:fx` at the
       top level. Captures source-coords (Spec 001) at this call site.
       Additionally captures the whole `(reg-event-fx :id ...)` form as
       a string under the handler's `:rf.handler/source` meta (Spec 009,
       rf2-xgfuy) — DEBUG-gated, elided in CLJS `:advanced` +
       `goog.DEBUG=false` production builds. See
       `re-frame.events/reg-event-fx` for the full signature.")

     (rm/defreg-event-macro reg-event-ctx events/reg-event-ctx
       "Register a `(fn [context] context)` full-context event handler
       under `id`. Advanced — most handlers want `reg-event-db` or
       `reg-event-fx` instead. Captures source-coords (Spec 001) at
       this call site. Additionally captures the whole `(reg-event-ctx
       :id ...)` form as a string under the handler's `:rf.handler/
       source` meta (Spec 009, rf2-xgfuy) — DEBUG-gated, elided in CLJS
       `:advanced` + `goog.DEBUG=false` production builds. See
       `re-frame.events/reg-event-ctx` for the full signature.")

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
       contract.")

     (rm/defreg-macro reg-http-interceptor rf-http/reg-http-interceptor
       "Register a request-side HTTP interceptor on a frame's
       `:rf.http/managed` middleware chain. Captures source-coords
       (Spec 001) at this call site. Implementation ships in
       `day8/re-frame2-http` (Spec 014 §Middleware). Signature:
       `(reg-http-interceptor id opts? before)` — per rf2-eyjbn the
       surface aligns with the rest of the `reg-*` family (positional
       id + opts kwarg + positional handler, matching `reg-flow`)."
       {:arglists '([id before] [id opts before])})))

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
     (def ^{:no-doc true
            :doc "JVM-only macro-helper re-exposed for tests that reach
  `re-frame.core/expand-reg-view` directly (pre-split test access, per
  rf2-4rnui). Not part of the public surface — see
  `re-frame.core-reg-view-macro/expand-reg-view`. Internal per rf2-kp835
  Phase-2 (2026-05-17)."}
       expand-reg-view             rvm/expand-reg-view)
     (def ^{:no-doc true
            :doc "JVM-only macro-helper re-exposed for tests that reach
  `re-frame.core/parse-reg-view-args` directly (pre-split test access,
  per rf2-4rnui). Not part of the public surface — see
  `re-frame.core-reg-view-macro/parse-reg-view-args`. Internal per
  rf2-kp835 Phase-2 (2026-05-17)."}
       parse-reg-view-args         rvm/parse-reg-view-args)))

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
     (def ^{:doc "Fn-alias of the `reg-error-projector` macro for HoF /
  programmatic registration (no source-coord capture). Register an
  error projector — `(trace-event) -> public-error-map`. Frames opt in
  via the `:ssr` config's `:public-error-id` key. Implementation ships
  in `day8/re-frame2-ssr`. See `re-frame.core-ssr/-reg-error-projector`
  and spec/API.md §Registration."}
       reg-error-projector -reg-error-projector)
     (def ^{:doc "Fn-alias of the `reg-head` macro for HoF / programmatic
  registration (no source-coord capture). Register a head-fragment
  producer `(fn [db route] head-model)` under a namespaced `id`.
  Implementation ships in `day8/re-frame2-ssr`. See
  `re-frame.core-ssr/-reg-head` and spec/API.md §Registration."}
       reg-head            -reg-head)))

;; ---- SSR re-exports (Spec 011, rf2-uo7v) ---------------------------------

(def ^{:doc "Render a hiccup tree to an HTML string. Per Spec 011 §The
  render-tree → HTML emitter. Delegates to the installed substrate
  adapter's `:render-to-string` slot; `opts` may carry `:doctype?` and
  `:emit-hash?`. Implementation ships in `day8/re-frame2-ssr`. Late-bound
  via `:ssr/render-to-string`."}
  render-to-string rf-ssr/render-to-string)

(def ^{:doc "Stable structural hash of a render tree (FNV-1a 32-bit, lowercase
  hex). Identical output on JVM and CLJS for the same canonical-EDN
  representation. Per Spec 011 §Hydration-mismatch detection.
  Implementation ships in `day8/re-frame2-ssr`. Late-bound via
  `:ssr/render-tree-hash`."}
  render-tree-hash rf-ssr/render-tree-hash)

(def ^{:doc "Apply the active error projector for `frame-id` to the trace
  event; returns an `:rf/public-error` map. Per Spec 011 §Server error
  projection. Implementation ships in `day8/re-frame2-ssr`. Late-bound
  via `:ssr/project-error`."}
  project-error    rf-ssr/project-error)

(def ^{:doc "Apply the head fn registered under `head-id` against a frame's
  app-db and active route; returns the produced `:rf/head-model`. Per
  Spec 011 §Head/meta contract. Implementation ships in
  `day8/re-frame2-ssr`. Late-bound via `:ssr/render-head`."}
  render-head      rf-ssr/render-head)

(def ^{:doc "Look up the active route's `:head` metadata and render its
  model; returns the default head when none is configured (per Spec 011
  §Default head). Implementation ships in `day8/re-frame2-ssr`.
  Late-bound via `:ssr/active-head`."}
  active-head      rf-ssr/active-head)

(def ^{:doc "Render an `:rf/head-model` map to its inner-head HTML fragment
  in canonical order. Per Spec 011 §Default flow step 4. Implementation
  ships in `day8/re-frame2-ssr`. Late-bound via `:ssr/head-model-html`."}
  head-model->html rf-ssr/head-model->html)

(def ^{:doc "Read the per-frame `{head-id → last-produced head-model}`
  snapshot for `frame-id`. Returns `{}` for a frame that has never
  seen a `render-head` call (or whose snapshot has been cleared via
  per-request frame teardown). Useful for tests, introspection, and
  tools (Causa, MCP). Per Spec 011 §Head/meta contract (rf2-4dra9).
  Implementation ships in `day8/re-frame2-ssr`. Late-bound via
  `:ssr/head-snapshot`."}
  head-snapshot    rf-ssr/head-snapshot)

;; ---- frame management ----------------------------------------------------

(def ^{:doc "Anonymous-instance frame creation — generates a gensym'd id
  under `:rf.frame/...` and returns it. Per Spec 002 §Per-instance frames.
  Use `reg-frame` to create a frame with a caller-supplied id."}
  make-frame    frame/make-frame)

(def ^{:doc "Atomic `destroy-frame!` + `reg-frame` with the same config —
  full replace (opt-in). Per Spec 002 §reset-frame!. Use sparingly:
  destroy is the normative teardown boundary, so per-feature artefacts
  hang their cleanup off this call."}
  reset-frame!   frame/reset-frame!)

(def ^{:doc "Tear down `frame-id` — the normative teardown boundary. Runs
  the user `:on-destroy`, releases per-feature resources (flows,
  machines, schemas, SSR, epoch), clears the sub-cache, and removes the
  frame from the registry. Idempotent. Per Spec 002 §Destroy."}
  destroy-frame! frame/destroy-frame!)

;; ---- flows / schemas — plain-fn re-exports -------------------------------

(def ^{:doc "Clear a flow from a frame's registry and vacate its output
  path. Per Spec 013 §Lifecycle. Implementation ships in
  `day8/re-frame2-flows`. Late-bound via `:flows/clear-flow`."}
  clear-flow             rf-flows/clear-flow)

(def ^{:doc "Return the registered schema at `path` for a frame, or `nil`.
  Per Spec 010 §Schemas as a tooling and agent surface. Returns `nil`
  when the schemas artefact is not on the classpath. Implementation
  ships in `day8/re-frame2-schemas`."}
  app-schema-at          rf-schemas/app-schema-at)

(def ^{:doc "Return every registered `app-schema-at` declaration for a
  frame as a `{path -> schema}` map. Per Spec 010 §Per-frame schemas.
  Returns `{}` when the schemas artefact is not on the classpath.
  Implementation ships in `day8/re-frame2-schemas`."}
  app-schemas            rf-schemas/app-schemas)

(def ^{:doc "Return a stable cross-runtime digest of the registered
  schemas for a frame. Per Spec 010 §Digest algorithm. Returns `nil`
  when the schemas artefact is not on the classpath. Implementation
  ships in `day8/re-frame2-schemas`."}
  app-schemas-digest     rf-schemas/app-schemas-digest)

(def ^{:doc "Register the validator fn every dev-time schema-validation
  site routes through — `(fn [schema value] truthy?)` (or nil to
  disable). Map-arity atomically swaps `{:validate :explain}` together.
  Per Spec 010 §Non-Malli validators (rf2-froe). Default ships Malli;
  callers swap to drop the ~24 KB gzipped Malli surface. Implementation
  ships in `day8/re-frame2-schemas`."}
  set-schema-validator!  rf-schemas/set-schema-validator!)

(def ^{:doc "Register the explainer fn — `(fn [schema value] explanation)`
  — used to enrich schema-validation-failure traces' `:explain` key.
  Per Spec 010 §Non-Malli validators (rf2-froe). Implementation ships
  in `day8/re-frame2-schemas`."}
  set-schema-explainer!  rf-schemas/set-schema-explainer!)

(def ^{:doc "Register the schema-print companion — `(fn [schema-value]
  canonical-string)` — that the digest pipeline hashes. MUST be pure
  and cross-runtime deterministic. Per Spec 010 §Digest algorithm
  (rf2-wla45). Implementation ships in `day8/re-frame2-schemas`."}
  set-schema-printer!    rf-schemas/set-schema-printer!)

;; ---- data classification (Spec 015) -------------------------------------

(def ^{:doc "Additively merge path-marks into `app-db` for a frame, per
  Spec 015 §App-db marks (per frame). `path->mark` is a map from
  `get-in`-shaped path vectors to mark keywords (`:sensitive` or
  `:large`). Paths supplied here MERGE into the frame's existing marks
  — paths NOT mentioned keep their prior state. Repeat calls accumulate.
  Schema-attached marks are preserved — the two sources union per
  Spec 015 §Relationship with schema-attached marks. The declaration
  feeds the mark-lookup table the observation surfaces (trace bus,
  Causa, MCP, third-party log sinks) consult at emission time — real
  values flow through the application unchanged.

  Example:

      (rf/add-marks :rf/default
        {[:user :ssn]   :sensitive
         [:auth :token] :sensitive
         [:docs :csv]   :large})

  Returns `frame-id`. Pure declaration — does NOT mutate `app-db`,
  does NOT install an interceptor, does NOT change any handler's view
  of the data. Use `set-marks` for replace-semantics."}
  add-marks marks/add-marks)

(def ^{:doc "Replace the `app-db` mark-set for a frame with `path->mark`,
  per Spec 015 §App-db marks (per frame). `path->mark` is a map from
  `get-in`-shaped path vectors to mark keywords (`:sensitive` or
  `:large`). Paths supplied here REPLACE the frame's prior marks set
  wholesale — paths NOT mentioned are CLEARED. Schema-attached marks
  are preserved — only `:marks`-sourced entries are dropped. The two
  declaration sources union at lookup time per Spec 015 §Relationship
  with schema-attached marks.

  Example:

      (rf/set-marks :rf/default
        {[:user :ssn]   :sensitive
         [:auth :token] :sensitive
         [:docs :csv]   :large})

  Returns `frame-id`. Pure declaration — does NOT mutate `app-db`,
  does NOT install an interceptor, does NOT change any handler's view
  of the data. Use `add-marks` for additive-merge semantics."}
  set-marks marks/set-marks)

;; ---- clearing ------------------------------------------------------------

(def ^{:doc "Unregister an event handler. Zero-arity clears every
  registered event handler in the registrar; one-arity clears the named
  one. For hot-reload tools and test fixtures. Per spec/API.md §Clearing
  registrations."}
  clear-event events/clear-event)

(def ^{:doc "Unregister a subscription. Zero-arity clears every
  registered sub in the registrar; one-arity clears the named one. For
  hot-reload tools and test fixtures. Per spec/API.md §Clearing
  registrations."}
  clear-sub   subs/clear-sub)

(def ^{:doc "Unregister an fx handler. Zero-arity clears every registered
  fx; one-arity clears the named one. For hot-reload tools and test
  fixtures. Per spec/API.md §Clearing registrations."}
  clear-fx    fx/clear-fx)

(def ^{:doc "Dispose every cached entry in a frame's runtime sub-cache
  and clear the cache. Cancels any pending grace-period timers before
  disposing. For tests and hot-reload. Per spec/API.md §Clearing
  registrations."}
  clear-sub-cache! subs-cache/clear-sub-cache!)

;; ---- dispatch and subscribe ----------------------------------------------
;;
;; Each surface ships as a macro + `*`-fn pair. The macros expand to
;; `re-frame.core/dispatch*` / `subscribe*` etc., so those defs must
;; live here.

(def ^{:doc "Fn-form of `dispatch` for HoF / programmatic dispatch — no
  call-site source-coord capture. Append `event` to the target frame's
  router queue; returns nil. Per spec/API.md §Dispatch and subscribe
  (rf2-ts1a)."}
  dispatch*       router/dispatch!)

(def ^{:doc "Fn-form of `dispatch-sync` for HoF / programmatic sync
  dispatch — no call-site source-coord capture. Process `event`
  end-to-end synchronously, then drain to fixed point. For tests / REPL
  / bootstrap only. Per spec/API.md §Dispatch and subscribe (rf2-ts1a)."}
  dispatch-sync*  router/dispatch-sync!)

(def ^{:doc "One-shot read of a sub's current value — subscribes, derefs,
  then unsubscribes. Does NOT retain a cache reference. Use in handler
  bodies, machine actions, REPL — anywhere you need a value without a
  reactive subscription. Per spec/API.md §Dispatch and subscribe."}
  subscribe-once subs/subscribe-once)

(def ^{:doc "Decrement the ref-count on the cached subscription for
  `query-v`; ref-count→0 schedules disposal after the configured
  `:sub-cache` grace-period. Returns nil. Per spec/API.md §Dispatch and
  subscribe.

  Verb-axis carve-out (per Conventions §Tear-down verb axis,
  rf2-cmabc): the `un-` prefix is reserved as the singular form for
  the sub-cache ref-count decrement, because `clear-sub` is already
  taken by the symmetric inverse of `reg-sub` (the registrar
  decrement, above). The two operations are distinct: `clear-sub`
  drops the registration; `unsubscribe` releases a live cache
  ref-count. They cannot share the name."}
  unsubscribe     subs/unsubscribe)

(def ^{:doc "Compute a subscription's value against a supplied `db`,
  bypassing the reactive cache. **Pure / JVM-runnable testing entry
  point** — no live cache mutation, no frame state required: hand it a
  `query-v` and an `app-db` value and it returns the value the
  registered sub would compute for that hypothetical db. Use in JVM
  unit-test suites that want to assert sub correctness without
  mounting a frame; CLJS handler bodies and views normally reach the
  cached value via `subscribe` / `subscribe*` / `subscribe-once`. Per
  rf2-7t1a6."}
  compute-sub     subs/compute-sub)

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
     call `dispatch*`. Per Spec 002 §Routing.

     Canonical `event-vec` shape (best practice — not enforced):
       [<event-id>]                   ;; trivial
       [<event-id> <single-scalar>]   ;; single-argument
       [<event-id> {<k> <v>}]         ;; multi-argument — single map payload
     Variadic `[<id> a b c]` is accepted by the runtime for v1-migration
     and caller convenience; the linter nudges new code toward the map
     form. See spec/Conventions.md §Canonical event-vector shape."
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
     programmatic use call `dispatch-sync*`. Per Spec 002 §dispatch-sync.

     Canonical `event-vec` shape — see `dispatch` docstring above; same
     best-practice convention applies (id-first, with at most one
     trailing map; variadic tolerated, linter nudges)."
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
     Use `subscribe-once` for a one-shot read; use `subscribe*` for
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
;; The public `current-frame` exposes the 3-tier resolution chain
;; (dynamic var → React context → `:rf/default`) at every user-facing
;; surface that flows through `(dispatcher)` / `(subscriber)` /
;; `bound-fn`. Single-sourced through `frame/resolve-current-frame`.

(defn current-frame
  "Return the active frame at the call site. Resolution chain: dynamic
  var -> React context (CLJS only, via `:adapter/current-frame` late-
  bind hook) -> `:rf/default`. Per Spec 002 §Reading the frame from
  React context."
  []
  (frame/resolve-current-frame))

(defn dispatcher
  "Return a fn that dispatches under the current frame, captured at call
  time so closures need not thread it. Per Spec 004 §Affordance for
  plain fns:
    (let [d (rf/dispatcher)] [:button {:on-click #(d [:inc])} ...])

  **Noun form is deliberate (rf2-knz3l).** The noun is a convenience
  for capturing the current frame in closures; it is distinct from the
  router's internal \"dispatcher\" concept (the engine that drains the
  event queue). The verb-form alternative `bound-dispatcher` was
  considered and cut as a pure alias — the noun's capture-at-call-time
  semantics are part of the contract here."
  []
  (let [frame (current-frame)]
    (fn dispatch-fn
      ([event]      (dispatch* event {:frame frame}))
      ([event opts] (dispatch* event (assoc opts :frame frame))))))

(defn subscriber
  "Return a fn that subscribes under the current frame, captured at call
  time. Sibling of `dispatcher`. Per Spec 004 §Affordance for plain fns.

  **Noun form is deliberate (rf2-knz3l).** The noun is a convenience
  for capturing the current frame in closures so async callbacks need
  not thread the frame id; `subscriber` is distinct from the router or
  reactive-substrate notion of a \"subscriber\" (a subscribed
  reaction). The verb-form alternative `bound-subscriber` was
  considered and cut as a pure alias."
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

#?(:cljs (def ^{:doc "Reagent component that puts a frame on React context
  for descendant views. Usage: `[rf/frame-provider {:frame :todo} &
  children]`. Children resolve `(current-frame)` to the provided frame
  unless a lexical `with-frame` or dynamic binding overrides. Per
  Spec 002 §Reading the frame from React context."}
         frame-provider views/frame-provider))

;; ---- routing helpers ------------------------------------------------------

(def ^{:doc "Match a URL against registered routes; return
  `{:route-id :params :query :fragment :validation-failed?}` for the
  first match, or `nil`. The URL's `#fragment` portion is parsed off
  and surfaced as `:fragment`. Per Spec 012 §Bidirectional URL <-> params.
  Implementation ships in `day8/re-frame2-routing`."}
  match-url   rf-routing/match-url)

(def ^{:doc "Build a URL string from a route-id + path-params (+ optional
  query-params + optional fragment). Inverse of `match-url`. The 4-arity
  appends `#fragment` when non-empty. Per Spec 012 §Bidirectional URL
  <-> params. Implementation ships in `day8/re-frame2-routing`."}
  route-url   rf-routing/route-url)

(def ^{:doc "Remove a registered route. Emits `:rf.route/cleared` so
  tools subscribing to route lifecycle observe the removal; symmetric
  with `:rf.flow/cleared`. Per Spec 012 §Trace events (rf2-dn26r).
  Implementation ships in `day8/re-frame2-routing`."}
  unregister-route!  rf-routing/unregister-route!)

(def ^{:doc "Registered view at `:route/link` — renders an `<a href=...>`
  from a route-id and intercepts plain primary-button clicks to dispatch
  `:rf/url-requested`. Modifier-key / middle-clicks defer to the browser.
  Shape: `[rf/route-link {:to :route-id :params {} :query {} :fragment
  \"\" & html-attrs} & children]`. Per Spec 012 §Linking from views."}
  route-link  rf-routing/route-link)

;; ---- machine helpers ------------------------------------------------------
;;
;; Plain-fn `reg-machine*` for code-gen pipelines / conformance corpus
;; registering without a literal spec form. Per Spec 005 §reg-machine vs
;; reg-machine* (rf2-8bp3).

#?(:clj
   (def ^{:doc "Plain-fn surface for machine registration (JVM). Use for
  code-gen pipelines and conformance harnesses that synthesise specs
  from data; the `reg-machine` macro is preferred for literal spec
  forms (it carries per-element source-coord stamping). Implementation
  ships in `day8/re-frame2-machines`. Per Spec 005 §reg-machine vs
  reg-machine* (rf2-8bp3)."}
     reg-machine* rf-machines/reg-machine*))

(def ^{:doc "Build an event-fx handler from a machine spec. Per Spec 005
  §Registration. Implementation ships in `day8/re-frame2-machines`.
  Late-bound via `:machines/make-machine-handler`."}
  make-machine-handler rf-machines/make-machine-handler)

(def ^{:doc "Pure `(machine, snapshot, event) -> [snapshot fx]`. Per
  Spec 005 §Drain semantics §Level 3. **Pure / JVM-runnable testing
  entry point** — no app-db read, no registrar lookup, no dispatcher
  involvement: hand it a definition, a snapshot, and an event, and it
  returns the next snapshot + the effects to interpret. The
  conformance corpus and JVM unit-test suites consume `machine-transition`
  directly; CLJS handler bodies normally reach the same engine via
  `make-machine-handler` and `dispatch`. Implementation ships in
  `day8/re-frame2-machines`. Late-bound via
  `:machines/machine-transition`. Per rf2-7t1a6."}
  machine-transition     rf-machines/machine-transition)

(def ^{:doc "Return a sequence of registered machine ids. Per Spec 005
  §Querying machines. Returns `[]` when the machines artefact is not
  on the classpath. Implementation ships in `day8/re-frame2-machines`."}
  machines               rf-machines/machines)

(def ^{:doc "Return the registered machine spec map for `machine-id`, or
  `nil`. Per Spec 005 §Querying machines. Returns `nil` when the
  machines artefact is not on the classpath. Implementation ships in
  `day8/re-frame2-machines`."}
  machine-meta           rf-machines/machine-meta)

(def ^{:doc "Look up the spawned-machine id bound to `system-id` in the
  active frame's `[:rf/system-ids]` reverse index, or `nil`. Optional
  `frame-id` arg targets an explicit frame. Per Spec 005 §Named
  addressing via `:system-id`. Implementation ships in
  `day8/re-frame2-machines`."}
  machine-by-system-id   rf-machines/machine-by-system-id)

(def ^{:doc "Sugar: dispatch `event` to the spawned-machine bound to
  `system-id` in the active frame; no-op fall-through when the
  system-id is unbound. Per Spec 005 §Cross-machine messaging by name."}
  dispatch-to-system     rf-machines/dispatch-to-system)

(def ^{:doc "Subscribe to a machine's snapshot. Sugar over
  `(subscribe [:rf/machine machine-id])`. Returns a reaction whose value
  is `{:state <kw> :data <map>}`, or `nil` if uninitialised. Per
  Spec 005 §Subscribing to machines via sub-machine."}
  sub-machine            rf-machines/sub-machine)

(def ^{:doc "Subscribe to a machine's `:fsm/tags` containment-bit for
  `tag`. Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`
  — returns a reaction whose value is `true` iff the current snapshot's
  `:tags` set contains `tag`. Per Spec 005 §State tags (rf2-ee0d)."}
  machine-has-tag?               rf-machines/machine-has-tag?)

(def ^{:doc "Wrap a 3-arity guard / action fn so the machine runtime
  calls it with the introspection ctx `{:state :meta}`. Sugar over the
  `^:rf.machine/wants-ctx` metadata flag for anonymous-fn / combinator
  call sites where attaching metadata to the source form is awkward.
  Per Spec 005 §3-arity escape hatch (rf2-2yupx) and rf2-b73dm
  re-export."}
  wants-ctx                      rf-machines/wants-ctx)

;; ---- introspection (Spec 002 §The public registrar query API) -----------

(def ^{:doc "Return all ids registered under `kind` with their metadata —
  the introspection workhorse used by tools, agents, and storybook
  resolution. Per Spec 002 §The public registrar query API."}
  registrations registrar/registrations)

(def ^{:doc "Return the registered metadata map for `[kind id]`, or `nil`.
  Public alias for `re-frame.registrar/lookup`. Used by tooling. Per
  Spec 002 §The public registrar query API."}
  handler-meta registrar/handler-meta)

(def ^{:doc "Return the set of registered ids under `kind` (no metadata).
  Per Spec 002 §The public registrar query API."}
  handler-ids  registrar/ids)

(def ^{:doc "Return the set of registered, non-destroyed frame ids. Per
  Spec 002 §The public registrar query API."}
  frame-ids    frame/frame-ids)

(def ^{:doc "Return the effective metadata map for a frame as a flat
  shape — `:id` plus the post-preset-expansion user-supplied config.
  Per Spec 002 §The public registrar query API and Spec-Schemas
  §`:rf/frame-meta`."}
  frame-meta   frame/frame-meta)

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

;; Per rf2-bmzq0: `sub-topology` and `sub-cache-snapshot` live in
;; `re-frame.subs.tooling` (production-DCE split). On JVM the
;; convenience aliases in `re-frame.subs` keep the legacy
;; `subs/<name>` shape working; this ns mirrors them so the
;; `rf/sub-topology` / `rf/sub-cache` public API is unchanged. CLJS
;; consumers needing the surface (Causa, re-frame2-pair-mcp, re-frame-10x,
;; conformance tests) call `re-frame.subs.tooling/<name>` directly so
;; production counter bundles DCE the bodies.

#?(:clj
   (do
     (defn sub-cache
       "Inspect a frame's runtime sub-cache — CLJS-only, returns
       `{query-v {:value v :ref-count n}}`. JVM returns `nil` (cache has no
       reaction values). No-arg form uses the active frame. Per Spec 002
       §The public registrar query API."
       ([] (sub-cache (current-frame)))
       ([frame-id]
        (subs/sub-cache-snapshot frame-id)))

     (def ^{:doc "Return the static dependency graph of every registered
       subscription — what each sub depends on, computed from its
       registration (NOT from the live runtime cache). JVM-only convenience
       alias for `re-frame.subs.tooling/sub-topology` (rf2-bmzq0). CLJS
       consumers (Causa, re-frame2-pair-mcp, re-frame-10x, conformance tests) call
       the tooling ns directly so production bundles DCE the body.
       Per Spec 002 §The public registrar query API."}
       sub-topology subs/sub-topology)))

;; ---- interceptors --------------------------------------------------------

(def ^{:doc "Build an interceptor map from kwargs — the primitive entry
  point for custom interceptors. Accepts `:id`, `:before`, `:after`,
  `:comment`. Per spec/API.md §Interceptors."}
  ->interceptor   interceptor/->interceptor)

(def ^{:doc "Read from the context's `:coeffects` map. Used inside
  interceptor `:before` / `:after` fns and handler bodies that receive
  the full context. Per spec/API.md §Interceptors."}
  get-coeffect    interceptor/get-coeffect)

(def ^{:doc "Set the value at `k` in the context's `:coeffects` map;
  returns the updated context. Use from a `:before` interceptor when
  injecting an input the handler will read via `get-coeffect`. Per
  spec/API.md §Interceptors."}
  assoc-coeffect  interceptor/assoc-coeffect)

(def ^{:doc "Read from the context's `:effects` map. Used inside
  interceptor `:after` fns inspecting what the handler returned. Per
  spec/API.md §Interceptors."}
  get-effect      interceptor/get-effect)

(def ^{:doc "Set the value at `k` in the context's `:effects` map;
  returns the updated context. Use from a handler-wrapper interceptor
  to inject an effect into the cascade. Per spec/API.md §Interceptors."}
  assoc-effect    interceptor/assoc-effect)

(def ^{:doc "Returns an interceptor that focuses the handler on the
  app-db sub-slice at the given path — the handler receives the slice
  value as `:db` (not the full app-db); its returned `:db` is spliced
  back. Usage: `(reg-event-db :inc [(path :counter)] (fn [n _] (inc n)))`.
  Per spec/API.md §Interceptors."}
  path            std-interceptors/path)

(def ^{:doc "Pre-registered interceptor (a value, not a fn) that asserts
  the dispatched event has shape `[<id> <payload-map>]` and replaces
  the `:event` coeffect with the payload map itself. Usage:
  `(reg-event-fx :foo [unwrap-interceptor] (fn [_ {:keys [a b]}] ...))`.
  Per Conventions §Canonical event-vector shape (M-19) and §Value-vs-fn
  naming (rf2-k367k)."}
  unwrap-interceptor std-interceptors/unwrap-interceptor)

(def ^{:doc "Build a positional interceptor that overwrites the named
  payload keys with the `:rf/redacted` sentinel on the trace surface
  while the handler body still sees the unredacted payload via the
  `:event` coeffect. `paths` is a sequence of `get-in`-style key paths
  into the M-19 payload map. Composes additively with schema-declared
  sensitive slots (handler-meta `:sensitive?` has been removed in
  favour of path-marked classification). Usage:
  `(reg-event-fx :auth/login [(redact-interceptor [[:password]])] ...)`.
  Per spec/API.md §Privacy and Spec 009 §Privacy."}
  redact-interceptor   privacy/redact-interceptor)

;; ---- privacy / spec / trace / emit / elision (Spec 009, 010) -------------

(def ^{:doc "Predicate: returns `true` iff `trace-event` is a map carrying
  `:sensitive? true`. Trace-event filter for privacy-aware listeners and
  off-box egress. Per Spec 009 §Privacy."}
  sensitive?           privacy/sensitive?)

(def ^{:doc "Production-side schema validation interceptor. Add to a
  `reg-event-*` handler's positional interceptor vector to force `:schema`
  validation against the dispatched event vector even in production
  builds where dev-time validation is elided. The verb `validate-` (per
  rf2-todvi) telegraphs the time/build-mode axis the interceptor lives
  on (no-op in dev, validates in prod); the `-interceptor` suffix (per
  rf2-k367k, Conventions §Value-vs-fn naming) telegraphs that this is a
  Var holding a value, not a fn. Per Spec 010 §Production builds.
  The interceptor reuses the handler's existing `:schema` metadata —
  no parallel schema."}
  validate-at-boundary-interceptor spec/validate-at-boundary-interceptor)

(def ^{:doc "Emit a trace event. Production builds elide the body
  entirely (Closure DCE on the `interop/debug-enabled?` gate); in dev /
  JVM the envelope is built and delivered to the ring buffer, epoch
  recorder, and registered listeners. Per Spec 009 §Trace emit."}
  emit-trace-event!         trace/emit!)

;; Per rf2-qwm0a the public-tooling listener + buffer surface
;; (`register-trace-listener!` / `unregister-trace-listener!` / `clear-trace-listeners!` /
;; `trace-buffer` / `clear-trace-buffer!` / `configure-trace-buffer!`)
;; lives in `re-frame.trace.tooling`, not `re-frame.trace`. On the JVM
;; we preserve the legacy `rf/<name>` shape via the convenience aliases
;; below — the JVM has no Closure DCE bundle to protect, and re-frame.
;; trace ships JVM-only `re-frame.trace/<name>` aliases that the
;; aliases here mirror. CLJS deliberately omits the aliases so
;; production counter bundles DCE the tooling sibling wholesale; CLJS
;; consumers that need the listener / buffer surface (test fixtures,
;; dev preloads, Causa / Story / re-frame2-pair-mcp / re-frame-10x, SSR error-
;; projection) call `re-frame.trace.tooling/<name>` directly. Listener
;; observability in a production CLJS build is meaningless anyway:
;; `trace/emit!` is gated on `interop/debug-enabled?` and elides at
;; `:advanced` + `goog.DEBUG=false`, so even a registered listener
;; would observe nothing.

#?(:clj
   (do
     (def ^{:doc "Register a listener under `id` that receives every trace
       event. Same-id registration replaces. Returns `id`. JVM-only
       alias (CLJS omits — production bundles DCE the tooling sibling
       wholesale; CLJS callers use `re-frame.trace.tooling/register-trace-listener!`
       directly). Per rf2-qwm0a."}
       register-trace-listener!     trace/register-trace-listener!)
     (def ^{:doc "Drop the listener registered under `id`. JVM-only alias —
       CLJS callers use `re-frame.trace.tooling/unregister-trace-listener!`
       directly. Per rf2-qwm0a."}
       unregister-trace-listener!       trace/unregister-trace-listener!)
     (def ^{:doc "Return the trace ring buffer's current contents,
       oldest-first. Opts filter the result; the buffer is the substrate
       behind `re-frame-10x` and other dev tools. JVM-only alias —
       CLJS callers use `re-frame.trace.tooling/trace-buffer` directly.
       Per Spec 009 §Retain-N trace ring buffer."}
       trace-buffer           trace/trace-buffer)
     (def ^{:doc "Empty the trace ring buffer. Tooling uses this between
       sessions. No-op in production. JVM-only alias — CLJS callers use
       `re-frame.trace.tooling/clear-trace-buffer!` directly. Per Spec 009
       §Retain-N trace ring buffer."}
       clear-trace-buffer!    trace/clear-trace-buffer!)))

(def ^{:doc "Register an always-on event-emit listener `f` under `id`.
  Survives `:advanced` + `goog.DEBUG=false` — fires in CLJS production
  builds where the trace surface is elided. `f` receives a tight
  event-record per processed event (NOT subs / fxs); see
  `re-frame.event-emit` ns docstring for the record shape. Re-registering
  the same id replaces. Returns `id`. Per Spec 009 §Event-emit listener
  (rf2-rirbq)."}
  register-event-emit-listener!   event-emit/register-event-emit-listener!)

(def ^{:doc "Drop the always-on event-emit listener registered under `id`.
  Returns nil. Per Spec 009 §Event-emit listener (rf2-rirbq)."}
  unregister-event-emit-listener! event-emit/unregister-event-emit-listener!)

(def ^{:doc "Register an always-on error-emit listener `f` under `id`.
  Survives `:advanced` + `goog.DEBUG=false`. `f` receives a tight
  error-record per `:rf.error/*` event (see `re-frame.error-emit` ns
  docstring for the record shape). For off-box observability shippers
  (Sentry, Honeybadger, Rollbar). Re-registering the same id replaces.
  Returns `id`. Per Spec 009 §Error-handler policy (rf2-bacs4)."}
  register-error-emit-listener!   error-emit/register-error-emit-listener!)

(def ^{:doc "Drop the always-on error-emit listener registered under `id`.
  Returns nil. Per Spec 009 §Error-handler policy (rf2-bacs4)."}
  unregister-error-emit-listener! error-emit/unregister-error-emit-listener!)

(def ^{:doc "Walk `v` and substitute schema-declared sensitive or large
  paths for wire egress. Sensitive wins over large when both
  declarations match. Sensitive paths become `:rf/redacted`; large paths
  become `:rf.size/large-elided`. Per Spec 009 §Wire elision and
  Security.md §Off-box egress."}
  elide-wire-value                 elision/elide-wire-value)

(def ^{:doc "Populate `[:rf/elision :declarations]` from `{:large? true}`
  schema-slot metadata for the frame. Returns the populated paths.
  Called from boot once app-schemas are registered. Per Spec 010 §Schema
  metadata flags."}
  populate-elision-from-schemas!   elision/populate-elision-from-schemas!)

(def ^{:doc "Populate `[:rf/elision :sensitive-declarations]` from
  `{:sensitive? true}` schema-slot metadata for the frame. Returns the
  populated paths. Called from boot once app-schemas are registered.
  Per Spec 010 §Schema metadata flags."}
  populate-sensitive-from-schemas! elision/populate-sensitive-from-schemas!)

(def ^{:doc "Return the schema-derived `:large?` declarations for a
  frame as a `{path -> spec}` map. Per Spec 009 §Wire elision."}
  elision-declarations             elision/declarations)

(def ^{:doc "Return the schema-derived `:sensitive?` declarations for a
  frame as a `{path -> spec}` map. Per Spec 009 §Wire elision."}
  elision-sensitive-declarations   elision/sensitive-declarations)

(def ^{:doc "Project a sequence of raw trace events into one cascade
  record per `:dispatch-id`. Pure data — JVM and CLJS. Used by
  `re-frame-10x`, Causa, and other tools that present cascade-level
  views over the raw event stream. Per Spec 009 §Trace projection."}
  group-cascades  trace-projection/group-cascades)

(def ^{:doc "Classify a trace event into one of the six domino buckets
  (`:event` / `:event-handler` / `:fx` / `:db` / `:sub` / `:view`).
  Pure fn used by trace projections and cascade views. Per Spec 009
  §Trace projection."}
  domino-bucket   trace-projection/domino-bucket)

;; ---- epoch history (Tool-Pair §Time-travel) ------------------------------

(def ^{:doc "Return the vector of `:rf/epoch-record` values for the
  frame, oldest-first. Empty when the frame has no recorded epochs,
  when recording is disabled, or when the `day8/re-frame2-epoch`
  artefact is not on the classpath. Per Tool-Pair §Time-travel.
  Late-bound via `:epoch/epoch-history`."}
  epoch-history      rf-epoch/epoch-history)

(def ^{:doc "Rewind the named frame's `app-db` to the named epoch's
  `:db-after`. Returns `true` on success, `false` on any of the six
  documented failure modes (each emits a structured `:rf.epoch/*` error
  trace) or when the epoch artefact is absent. Per Tool-Pair
  §Time-travel. Late-bound via `:epoch/restore-epoch`."}
  restore-epoch      rf-epoch/restore-epoch)

(def ^{:doc "Register a callback fired once per drain-settle with the
  assembled `:rf/epoch-record`. Same-id replaces; listener exceptions
  are isolated. Returns the `id`, or `nil` when the epoch artefact is
  absent. Per Spec 009 §`register-epoch-listener!`. Late-bound via
  `:epoch/register-epoch-listener!`."}
  register-epoch-listener! rf-epoch/register-epoch-listener!)

(def ^{:doc "Remove the epoch listener registered under `id`. No-op when
  the epoch artefact is absent. Late-bound via
  `:epoch/unregister-epoch-listener!`."}
  unregister-epoch-listener!   rf-epoch/unregister-epoch-listener!)

(def ^{:doc "Replace `frame-id`'s `app-db` with `new-db`, bypassing the
  dispatch loop. The canonical Tool-Pair write surface for state
  injection — pair tools, story fixtures, conformance harnesses, and
  time-travel from JSON repros. Records a synthetic `:rf/epoch-record`
  so `restore-epoch` can rewind. Dev-only (gated on
  `interop/debug-enabled?`). Raises `:rf.error/epoch-artefact-missing`
  when the artefact is absent. Per Tool-Pair §Pair-tool writes
  (rf2-zq55). Late-bound via `:epoch/reset-frame-db!`."}
  reset-frame-db!    rf-epoch/reset-frame-db!)
;; Per Security.md §Epoch privacy posture and rf2-mrsck — single
;; normative projection helpers for off-box epoch egress.
(def ^{:doc "Project an `:rf/epoch-record` for off-box egress — the
  single normative projection emission site for off-box epoch egress
  (parallel to `elide-wire-value` for direct reads). Routes payload
  slots through wire-elision with off-box defaults; bookkeeping slots
  pass through unchanged. Tools that egress epoch records across a
  process boundary (Causa-MCP `watch-epochs`, recorders, forwarders)
  MUST route through this fn. Per Security.md §Epoch privacy posture
  (rf2-mrsck). Late-bound via `:epoch/projected-record`."}
  projected-record   rf-epoch/projected-record)

(def ^{:doc "Off-box egress safety primitive for whole-ring epoch
  egress. Returns the projected vector of records for a frame —
  every record routed through `projected-record` so payload slots are
  wire-elided with off-box defaults. Tools that egress the entire
  epoch ring (initial-snapshot dumps, full session captures, recorders
  / forwarders) MUST call `projected-history` rather than walking
  `(epoch-history frame-id)` and re-wrapping by hand: the hand-walk is
  one missed `mapv projected-record` away from leaking un-elided data
  across the process boundary. The convenience framing is incidental;
  the safety framing is the reason the surface is kept (per rf2-p7vf9).

  Mechanically equivalent to `(mapv projected-record (epoch-history
  frame-id))` but spelled as a single normative emission site so the
  hand-walk anti-pattern has nowhere to land. Empty vector when the
  frame has no recorded epochs or the epoch artefact is absent. Per
  Security.md §Epoch privacy posture. Late-bound via
  `:epoch/projected-history`."}
  projected-history  rf-epoch/projected-history)

;; ---- Spec 014 — :rf.http/managed -----------------------------------------

(def ^{:doc "Install per-call fx-overrides for `:rf.http/managed` that
  synthesise the configured replies. `stubs` is `{[method url]
  {:reply <:ok|:failure>}}`. Implementation ships in
  `day8/re-frame2-http`. Per Spec 014 §Testing. Late-bound via
  `:http/install-managed-request-stubs!`."}
  install-managed-request-stubs!   rf-http/install-managed-request-stubs!)

(def ^{:doc "Remove the per-call fx-override installed by
  `install-managed-request-stubs!`. Implementation ships in
  `day8/re-frame2-http`. Per Spec 014 §Testing. Late-bound via
  `:http/uninstall-managed-request-stubs!`."}
  uninstall-managed-request-stubs! rf-http/uninstall-managed-request-stubs!)

(def ^{:doc "Fn-form: install stubs, run `thunk`, uninstall. The plumbing
  the `with-managed-request-stubs` macro routes through. Implementation
  ships in `day8/re-frame2-http`. Per Spec 014 §Testing. Late-bound via
  `:http/with-managed-request-stubs*`."}
  with-managed-request-stubs*      rf-http/with-managed-request-stubs*)

(def ^{:doc "Clear an HTTP interceptor by `id` from a frame's
  `:rf.http/managed` middleware chain. Single-arity clears on
  `:rf/default`; two-arity targets the named frame. Implementation ships
  in `day8/re-frame2-http`. Per Spec 014 §Middleware. Late-bound via
  `:http/clear-http-interceptor`."}
  clear-http-interceptor           rf-http/clear-http-interceptor)

;; reg-http-interceptor is a macro (per the defreg-macro form above) so
;; source-coords are captured at the call site like every other reg-*.
;; CLJS apps reach the fn-form via `re-frame.core-http/reg-http-interceptor`
;; for programmatic registration that bypasses the macro path.

;; ---- configure / substrate adapter / boot --------------------------------

(defn configure
  "Configure a process-level runtime knob. v1 keys:
    :epoch-history {:depth N}                       ring depth (default 50; 0 disables)
    :trace-buffer  {:depth N}                       ring depth (default 200; 0 disables)
    :sub-cache     {:grace-period-ms N}             dispose grace (default 50ms)
  Unknown keys silently no-op. Per-frame settings live on frame metadata.
  Per Tool-Pair §How AI tools attach.

  `:trace-buffer` routes through the `re-frame.trace.tooling` sibling
  ns (per rf2-qwm0a). Production builds that never load the tooling
  sibling silently no-op on this key — the buffer + listener
  machinery is DCE'd anyway."
  [knob opts]
  (case knob
    :epoch-history (when-let [f (late-bind/get-fn :epoch/configure!)]
                     (f opts))
    :trace-buffer  (when-let [f (late-bind/get-fn :trace.tooling/configure-trace-buffer!)]
                     (f opts))
    :sub-cache     (subs-cache/configure! opts)
    nil))

(def ^{:doc "Install the substrate adapter for this process. Once. A
  second call without an intervening `destroy-adapter!` raises
  `:rf.error/adapter-already-installed`. Most apps call `init!` rather
  than this directly. Per Spec 006 §Adapter selection at boot."}
  install-adapter!     adapter/install-adapter!)

(def ^{:doc "Tear down the installed adapter. Calls the adapter's
  `:dispose-adapter!` fn (if present), clears the install slot so a
  new adapter can install, and marks the adapter as disposed. Per
  Spec 006 §Adapter lifecycle.

  The `destroy-` verb places this fn on the lifecycle-boundary axis
  of the tear-down verb taxonomy (per Conventions §Tear-down verb
  axis) — adapter install/destroy is symmetric with frame
  create/destroy (`destroy-frame!`)."}
  destroy-adapter!     adapter/dispose-adapter!)

(def ^{:doc "Return the discriminator keyword identifying the installed
  adapter, or `nil` if none. One of `:reagent` / `:plain-atom` /
  `:uix` / `:helix` per Spec 006 §Adapter introspection."}
  current-adapter      adapter/current-adapter)

(def ^{:doc "Return the installed adapter spec map, or `nil` if none.
  Carries the adapter contract fns (`:make-state-container`,
  `:replace-container!`, `:render`, `:dispose-adapter!`, etc.). Per
  Spec 006 §Adapter introspection."}
  current-adapter-spec adapter/current-adapter-spec)

(def ^{:doc "Return `true` iff the most recent lifecycle event was a
  successful `dispose-adapter!` and no `install-adapter!` has fired
  since. False otherwise. Per Spec 006 §Adapter lifecycle."}
  adapter-disposed?    adapter/adapter-disposed?)

(defn- bad-init-arg!
  "Raise `:rf.error/no-adapter-specified` with a consistent reason
  string. Factored out of `init!`'s nil-check and not-map-check."
  [received]
  (throw (ex-info ":rf.error/no-adapter-specified"
                  (cond-> {:where    'rf/init!
                           :expected "adapter spec map"
                           :recovery :no-recovery
                           :reason   "rf/init! takes the adapter spec map directly — there is no keyword form, no nil form, and no default-adapter registry. Require the adapter ns and pass its `adapter` Var: (rf/init! reagent/adapter)."}
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
