(ns re-frame.core
  "Public API surface for re-frame2. Per spec/API.md.

  Users `(:require [re-frame.core :as rf])` and call `rf/dispatch`,
  `rf/reg-event`, etc. Internal namespaces are not part of the
  contract; per-namespace docs carry the design rationale.

  Topology — this ns is a thin façade:

    - Optional-artefact wrappers live in `re-frame.core-<feature>`
      sibling namespaces (`flows`, `routing`, `schemas`, `machines`,
      `ssr`, `http`, `resources`); each looks up its target fn through
      the late-bind hook table so requiring this ns does NOT pull
      the feature artefacts.
    - Macro-helper code is factored into three siblings —
      `core-reg-macros` (reg-event, reg-sub, reg-fx, reg-cofx
      expansion), `core-call-site-macros` (dispatch / dispatch-sync
      call-site expansion), `core-reg-view-macro`
      (reg-view + view-component expansion). The boundary is the
      *responsibility* — call-site vs registration vs view-registration
      — so each helper ns owns one cohesive expansion family. The
      user-facing `defmacro`s themselves stay in THIS ns (so
      `rf/reg-event` etc. resolve alias-qualified per Clojure's
      standard `ns-alias/Var` lookup); each is a one-line shell that
      delegates to the sibling-ns expansion helper.

  File-naming uses the flat dash-form (`core_X.cljc`) because CLJS
  `goog.provide` for `re-frame.core` overwrites its parent object,
  which would wipe a previously-loaded `re-frame.core.X`."
  (:require [re-frame.registrar :as rf.registrar]
            [re-frame.frame :as rf.frame]
            ;; The frame api constructor behind `capture-frame` and the
            ;; `reg-view` injection — an implementation namespace below this
            ;; facade, so the expansion can name it fully-qualified
            ;; without a compiler-only Var living here.
            [re-frame.capture-frame :as rf.capture-frame]
            [re-frame.router :as rf.router]
            [re-frame.events :as rf.events]
            [re-frame.fx :as rf.fx]
            [re-frame.cofx :as rf.cofx]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.std-interceptors :as rf.std-interceptors]
            [re-frame.privacy :as rf.privacy]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.features :as rf.features]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; JVM-only: the server-side reg-view registration-boundary
            ;; annotation. The `:clj` branch of `reg-view*`
            ;; wraps the stored `:handler-fn` with it; the CLJS view path
            ;; annotates through the substrate wrappers (spine / views),
            ;; so this ns is deliberately absent from CLJS bundles.
            #?@(:clj [[re-frame.views.jvm-source-coord-annotation :as rf.views.jvm-source-coord-annotation]])
            ;; The event-emit require is side-effect-only: no symbol from
            ;; it is referenced here, but `re-frame.core`
            ;; is its ONLY static requirer (the router reaches
            ;; `dispatch-on-event!` through the
            ;; `:event-emit/dispatch-on-event` late-bind hook), so dropping
            ;; it would leave that hook unpublished at boot. `error-emit`
            ;; needs no require here — `router.cljc` requires it statically.
            [re-frame.event-emit]
            [re-frame.error :as rf.error]
            [re-frame.elision :as rf.elision]
            [re-frame.projection :as rf.projection]
            ;; Its load publishes the `:classification/*` late-bind hooks the
            ;; trace bus projects every emit through, so they are bound at
            ;; boot; it also owns `reg-classification*`, behind
            ;; `reg-classification`.
            [re-frame.classification :as rf.classification]
            ;; Re-exported below, and its load publishes the `:observability/*`
            ;; late-bind hooks the router, error-emit and the frame preflight
            ;; call through (a static require from them would close a load
            ;; cycle). It survives production by design: the frame
            ;; `:observability` stream is the production observation stream.
            [re-frame.observability :as rf.observability]
            ;; Required with no alias, so the facade's `image` macro name does
            ;; not collide with one.
            [re-frame.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.core-flows    :as rf.core-flows]
            [re-frame.core-routing  :as rf.core-routing]
            [re-frame.core-schemas  :as rf.core-schemas]
            [re-frame.core-machines :as rf.core-machines]
            [re-frame.core-resources :as rf.core-resources]
            [re-frame.core-state    :as rf.core-state]
            [re-frame.core-ssr      :as rf.core-ssr]
            ;; Epoch history and time-travel (Tool-Pair §Time-travel). Every
            ;; development build records epochs, so core loads it; every
            ;; call below and its own load-time publication sit under
            ;; `interop/debug-enabled?`, so production eliminates it.
            [re-frame.epoch         :as rf.epoch]
            [re-frame.epoch.state   :as rf.epoch.state]
            [re-frame.core-http     :as rf.core-http]
            ;; Macro helpers — the macro siblings live in their own
            ;; nss; required here with `:include-macros` so CLJS callers
            ;; see them under `rf/<name>`.
            [re-frame.core-reg-macros        :as rf.core-reg-macros
             #?@(:cljs [:include-macros true])]
            [re-frame.core-call-site-macros  :as rf.core-call-site-macros]
            [re-frame.core-reg-view-macro    :as rf.core-reg-view-macro]
            ;; `re-frame.substrate.plain-atom` is deliberately not required: an
            ;; app passes its `adapter` to `rf/init!`, and requiring it here
            ;; would ship the JVM/SSR adapter into every consumer.
            #?@(:cljs [[re-frame.views :as rf.views]]))
  ;; The macros are defined in this ns's `#?(:clj ...)` blocks below.
  ;; CLJS users see them under `rf/<name>` via this self-`:require-
  ;; macros`, so `(:require [re-frame.core :as rf])` is the only import
  ;; CLJS apps need.
  #?(:cljs (:require-macros
             [re-frame.core :refer [reg-event
                                    reg-sub reg-fx reg-cofx
                                    reg-interceptor
                                    reg-flow reg-route reg-app-schema reg-app-schemas
                                    reg-resource reg-mutation reg-resource-scope
                                    reg-error-projector reg-head
                                    reg-http-interceptor reg-classification reg-state
                                    reg-view reg-machine defmachine
                                    image
                                    dispatch dispatch-sync
                                    with-frame with-new-frame]])))

#?(:clj (set! *warn-on-reflection* true))

;; `dispatch-impl` / `dispatch-sync-impl` are the internal seam the `dispatch`
;; / `dispatch-sync` macro expansions and a frame api's ops
;; (`re-frame.capture-frame/make-capture-frame`) all call through, so ONE
;; `with-redefs` on either intercepts every macro-driven or reg-view-injected
;; call. The CLJS value aliases below COPY the var's value at load, so a redef
;; of one reaches only its own callers. `^:no-doc` keeps them off the API
;; manifest; `subscribe` needs no seam, being a plain fn.
;;
;; They are `def` aliases, not `defn`s, because a `defn` lets the CLJS compiler
;; attach fixed-arity metadata, and callers then emit a static
;; `.cljs$core$IFn$_invoke$arity$N` call that a `with-redefs`'d fn with another
;; arity set cannot satisfy — under shadow-cljs's `:node-test` build too, not
;; only `:advanced`. Call-site stamping is the debug-gated macros' job, so no
;; `:rf.trace/call-site` literal lives in these production-reachable bodies
;; (the elision probe asserts it is absent).
(def ^:no-doc dispatch-impl      rf.router/dispatch!)
(def ^:no-doc dispatch-sync-impl rf.router/dispatch-sync!)

(declare subscribe)

;; Publish the two seams and `subscribe` to `re-frame.capture-frame`. It sits
;; BELOW this facade and cannot `:require` it, so the frame api's ops reach
;; them through the late-bind registry (the cycle-breaking flavour, per
;; `re-frame.late-bind.directory`). Each published fn reads the var at CALL
;; time — `(dispatch-impl event opts)`, not the var's current value — so a
;; `with-redefs` on `rf/dispatch-impl` reaches an op captured before it,
;; exactly as it reaches the macro expansions.
(rf.late-bind/set-fns!
  {:core/dispatch-impl      (fn [event opts]   (dispatch-impl event opts))
   :core/dispatch-sync-impl (fn [event opts]   (dispatch-sync-impl event opts))
   :core/subscribe          (fn [query-v opts] (subscribe query-v opts))})

(defn ^:no-doc stamp-opts
  "Merge macro-stamped `extra` into a call's user-supplied `opts`, TOLERANTLY:
  a malformed (non-map, non-nil) `opts` passes through UNTOUCHED so the callee's
  own validation still produces its clean framework error rather than a raw
  host `ClassCastException`.

  Reached ONLY from the debug-gated stamped branch of the call-site macros, so
  it DCEs under `:advanced` + `goog.DEBUG=false`. It is deliberately GENERIC —
  no reserved keyword literal lives in this body, keeping the elision probe's
  `rf.trace/call-site`-absent-from-prod-bundle assertion intact (the keyword
  literal stays in the macro expansion, which DCEs).

  It is also a plain FN CALL by construction: the call-site
  macros may splice only yield-free expression forms into the caller's context,
  and an inline `(if (map? opts) …)` would need a `let` to avoid double
  evaluation — which the CLJS compiler lowers to an awaited async IIFE inside
  an async context. A function call cannot lower that way."
  [opts extra]
  (if (or (map? opts) (nil? opts))
    (merge opts extra)
    opts))

;; ---- CLJS fn-aliases for registration ------------------------------------
;;
;; Source-coord capture on CLJS rides the JVM-emitted macros above. The
;; fns themselves honour `*pending-coords*` either way — the macros are
;; the *capture* path; the merge path is in the fns. Apps that reach
;; the registration fn programmatically (HoF, runtime registration) use
;; these aliases.
;;
;; CONCORDANCE (Conventions §Convention A). Every `defreg-macro` /
;; `defreg-event-macro` row in the `#?(:clj …)` table below has EXACTLY ONE
;; same-named alias here, delegating to the SAME fn, and this block is kept
;; in the table's order so the two read side by side. The two declarations
;; are a hand-written pair, so they are pinned equal by
;; `re-frame.registrar-alias-concordance-cljs-test`, which derives both from
;; THIS FILE (read once under `:features #{:clj}` and once under `#{:cljs}`)
;; rather than restating either as a list — a new registrar macro without its
;; alias goes red there.

#?(:cljs
   (do
     (def ^{:doc "Fn-alias of the `reg-event` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a
  `(fn [coeffects event-vec] effect-map)` event handler under `id` — the
  ONE public event form (EP-0018). See `re-frame.events/reg-event` and
  spec/API.md §Registration."}
       reg-event       rf.events/reg-event)
     (def ^{:doc "Fn-alias of the `reg-sub` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a subscription under
  `id`. See `re-frame.subs/reg-sub` and spec/API.md §Registration."}
       reg-sub         rf.subs/reg-sub)
     (def ^{:doc "Fn-alias of the `reg-fx` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register an effect handler
  under `id`. See `re-frame.fx/reg-fx` and spec/API.md §Registration."}
       reg-fx          rf.fx/reg-fx)
     (def ^{:doc "Fn-alias of the `reg-cofx` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a coeffect SUPPLIER —
  a value-returning fn whose result is delivered FLAT into a declaring
  handler's `:coeffects` map (the handler declares `:rf.cofx/requires
  [id]`). See `re-frame.cofx/reg-cofx` (grades: `:recordable?` /
  `:provided?`) and spec/API.md §Registration."}
       reg-cofx        rf.cofx/reg-cofx)
     (def ^{:doc "Fn-alias of the `reg-interceptor` macro for HoF /
  programmatic registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register an
  interceptor DESCRIPTOR (`{:before}` / `{:after}` / `{:before :after}` /
  `{:factory}`) under `id` — the public interceptor-authoring form (EP-0022).
  Event/frame `:interceptors` chains reference it by id. See
  `re-frame.interceptor-registry/reg-interceptor*` and spec/API.md
  §Registration."}
       reg-interceptor  rf.interceptor-registry/reg-interceptor*)
     (def ^{:doc "Fn-alias of the `reg-classification` macro for HoF /
  programmatic registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`).
  Register a feature's static app-db classification under `id`:
  `(reg-classification id {:sensitive [paths] :large [paths] :doc \"…\"})`.
  See `re-frame.classification/reg-classification*` and Spec 015 §Static
  app-db classification."}
       reg-classification rf.classification/reg-classification*)
     (def ^{:doc "Fn-alias of the `reg-flow` macro for HoF / programmatic
  registration (no source-coord capture, so no `:rf.provenance/ns` either —
  stamp `:ns` in the metadata to keep the definition inside an explicit
  image). Register the flow definition `flow-id` under the registrar's
  `:flow` kind: `(reg-flow flow-id metadata derive-fn)` — the pure `:derive`
  fn is the THIRD value slot, `metadata` carries `:inputs` /
  `:output-path` (both REQUIRED) plus optional `:doc` / the
  EP-0025 classification keys. A definition names no frame; each frame runs
  the flows its `:flows` config names. Implementation
  ships in `day8/re-frame2-flows`; require `re-frame.flows` at boot. See
  `re-frame.core-flows/reg-flow` and spec/API.md §Registration."}
       reg-flow        rf.core-flows/reg-flow)
     (def ^{:doc "Fn-alias of the `reg-route` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a route: `(reg-route id
  metadata path)` — `metadata` is the MIDDLE registration-metadata map
  (`:doc`, `:params`, `:on-match`, …); the `path` pattern is the THIRD value
  slot, merged onto the stored route-meta so downstream
  readers see `:path`. Implementation ships in
  `day8/re-frame2-routing`; require `re-frame.routing` at boot. See
  `re-frame.core-routing/reg-route` and spec/API.md §Registration."}
       reg-route       rf.core-routing/reg-route)
     (def ^{:doc "Fn-alias of the `reg-resource` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a resource — a named,
  cached read of remote/external state: `(reg-resource resource-id metadata
  request-fn)` — `metadata` is the MIDDLE registration-metadata map carrying
  the REQUIRED fail-closed `:scope` policy plus optional `:params-schema`,
  `:doc`, `:stale-after-ms`, …; the `:request` handler is the THIRD value slot.
  The stored introspection spec (`resource-meta`)
  reconstructs `:request` onto the metadata map. Implementation ships in
  `day8/re-frame2-resources`; require `re-frame.resources` at boot. See
  `re-frame.core-resources/reg-resource` and spec/API.md §Registration."}
       reg-resource    rf.core-resources/reg-resource)
     (def ^{:doc "Fn-alias of the `reg-mutation` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a mutation — a named,
  causal WRITE to remote state that, on success, invalidates / patches /
  populates cached resource reads: `(reg-mutation mutation-id metadata
  request-fn)` — the `:request` write fn (a Spec 014 managed-HTTP args map)
  is the THIRD value slot, `metadata` carries the REQUIRED
  `:params-schema` plus optional `:invalidates` / `:patches` / `:populates` /
  `:scope` / `:invalidate-timing`. Write retries are opt-in and are not a
  metadata key: the request fn returns `:retry` in its managed-HTTP args.
  Implementation ships in `day8/re-frame2-resources`; require
  `re-frame.resources` at boot. See
  `re-frame.core-resources/reg-mutation` and spec/API.md §Resources."}
       reg-mutation    rf.core-resources/reg-mutation)
     (def ^{:doc "Fn-alias of the `reg-resource-scope` macro for HoF /
  programmatic registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a PURE named
  scope resolver under `scope-id`. The 3-slot grammar is
  `(reg-resource-scope scope-id metadata resolve-fn)`: the `:resolve` fn is the
  value slot, `metadata` carries the declared `:inputs` map `{name [:db
  <rf-path>]}` (+ optional `:doc`). `:inputs` is REQUIRED; read the whole db by
  declaring it on the root path (`{:inputs {:db [:db []]}}`).
  Implementation ships in `day8/re-frame2-resources`;
  require `re-frame.resources` at boot. See
  `re-frame.core-resources/reg-resource-scope` and spec/API.md §Resources."}
       reg-resource-scope rf.core-resources/reg-resource-scope)
     (def ^{:doc "Fn-alias of the `reg-app-schema` macro for HoF / programmatic
  registration (no source-coord capture, so no provenance: stamp `:ns` in the
  metadata to keep it selectable by an explicit image). Register a Malli
  schema at a path inside app-db as the registrar kind `:app-schema`, keyed
  by the canonical path — a frame-agnostic program registration every frame
  resolves like any other kind (Spec 010). Implementation ships in
  `day8/re-frame2-schemas`.

  DEVELOPMENT-BUILD ASSERTION. A production build registers the schema
  (tools can still introspect it) but never checks it — a violating
  candidate installs silently, with no rejection and no diagnostic. Your
  app-db schemas do not run in production builds. Per Spec 010
  §Production builds.

  See `re-frame.core-schemas/reg-app-schema` and spec/API.md
  §Registration."}
       reg-app-schema  rf.core-schemas/reg-app-schema)
     (def ^{:doc "Fn-alias of the `reg-app-schemas` macro for HoF /
  programmatic registration (no source-coord capture, so no provenance, as
  for the singular form). Bulk-register a `{path -> schema}` map, each entry
  its own `:app-schema` registration. Implementation ships in
  `day8/re-frame2-schemas`.

  DEVELOPMENT-BUILD ASSERTION, as for the singular form: the batch
  registers in a production build but is never checked there, so a
  violating candidate installs silently. Your app-db schemas do not run
  in production builds. Per Spec 010 §Production builds.

  See `re-frame.core-schemas/reg-app-schemas` and spec/API.md
  §Registration."}
       reg-app-schemas rf.core-schemas/reg-app-schemas)
     (def ^{:doc "Fn-alias of the `reg-error-projector` macro for HoF /
  programmatic registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register an error
  projector — `(fn [trace-event] public-error-map)` — under `id`; frames opt
  in via the `:ssr` config's `:public-error-id` key. Implementation ships in
  `day8/re-frame2-ssr`; require `re-frame.ssr` at boot. See
  `re-frame.core-ssr/-reg-error-projector` and Spec 011 §Server error
  projection."}
       reg-error-projector rf.core-ssr/-reg-error-projector)
     (def ^{:doc "Fn-alias of the `reg-head` macro for HoF / programmatic
  registration (no source-coord capture, so no
  `:rf.provenance/ns` — reached only by a frame made without `:images`
  unless the metadata stamps `:ns`). Register a head-fragment producer —
  `(fn [db route] head-model)` — under a namespaced keyword `id`; routes name
  a registered head via `:head` route metadata. Implementation ships in
  `day8/re-frame2-ssr`; require `re-frame.ssr` at boot. See
  `re-frame.core-ssr/-reg-head` and Spec 011 §Head/meta contract."}
       reg-head        rf.core-ssr/-reg-head)
     (def ^{:doc "Fn-alias of the `reg-http-interceptor` macro for HoF /
  programmatic registration (no source-coord capture). Register the
  frame-agnostic HTTP interceptor definition `id` under the registrar kind
  `:http-interceptor`: `(reg-http-interceptor id interceptor-map)`, the map
  carrying at least one of `:before` / `:after`. A frame runs it only by
  naming `id` in its `:http-interceptors` config vector. Implementation
  ships in `day8/re-frame2-http`; require `re-frame.http.managed` at boot.
  See `re-frame.core-http/reg-http-interceptor` and Spec 014 §Middleware."}
       reg-http-interceptor rf.core-http/reg-http-interceptor)
     (def ^{:doc "Fn-alias of the `dispatch` macro for HoF / programmatic
  dispatch (no call-site source-coord capture). Appends `event-vec` to the
  target frame's router queue; returns nil.

    (dispatch event-vec)         — ambient frame (carried scope).
    (dispatch event-vec opts)    — `opts` may carry `:frame` (a frame-id
                                   keyword OR a live frame value) — the
                                   explicit OVERRIDE intent.

  See `re-frame.router/dispatch!` and spec/API.md §Dispatch and subscribe."
       :arglists '([event-vec] [event-vec opts])}
       dispatch        dispatch-impl)
     (def ^{:doc "Fn-alias of the `dispatch-sync` macro for HoF / programmatic
  synchronous dispatch (no call-site source-coord capture). Mirrors
  `dispatch`'s forms:

    (dispatch-sync event-vec)         — ambient frame.
    (dispatch-sync event-vec opts)    — `opts` may carry `:frame`.

  Processes `event-vec` end-to-end synchronously, then drains to fixed
  point. For tests / REPL / bootstrap only. See
  `re-frame.router/dispatch-sync!` and spec/API.md §Dispatch and subscribe."
       :arglists '([event-vec] [event-vec opts])}
       dispatch-sync   dispatch-sync-impl)))

;; ---- retired event-registration names — throwing stubs ------------------
;;
;; Each resolves to its `re-frame.events` stub, so a stale `(rf/reg-event-db …)`
;; fails loud naming its replacement rather than as "no such var". `^:no-doc`
;; keeps them off the API manifest. Spec 001 §The retired event-registration
;; names.
(def ^{:no-doc true
       :doc "REMOVED in EP-0018 (no alias). Calling `reg-event-db` raises
  `:rf.error/reg-event-db-removed`, naming `reg-event` as the replacement.
  See `re-frame.events/reg-event-db` and spec/001-Registration.md §The
  retired event-registration names."}
  reg-event-db  rf.events/reg-event-db)
(def ^{:no-doc true
       :doc "REMOVED in EP-0018 (no alias). Calling `reg-event-fx` raises
  `:rf.error/reg-event-fx-removed`, naming `reg-event` (the identical shape
  under the bare name). See `re-frame.events/reg-event-fx` and
  spec/001-Registration.md §The retired event-registration names."}
  reg-event-fx  rf.events/reg-event-fx)
(def ^{:no-doc true
       :doc "Framework-internal (EP-0018). Calling public
  `reg-event-ctx` raises `:rf.error/reg-event-ctx-removed`, naming
  `reg-interceptor` as the public replacement for application full-context
  work (register the interceptor by id, then reference it from a `reg-event`
  registration's `:interceptors` chain). See `re-frame.events/reg-event-ctx` and
  spec/001-Registration.md §The retired event-registration names."}
  reg-event-ctx rf.events/reg-event-ctx)

;; ---- reg-* macros (JVM-only; CLJS sees them via :require-macros) --------
;;
;; Each `defreg-macro` form below expands to a `defmacro` IN THIS ns
;; — so `rf/reg-event` resolves alias-qualified per Clojure's
;; `ns-alias/Var` lookup. The expansion captures source-coords at the
;; user's call site and splices args through to the fully-qualified
;; delegate fn.

#?(:clj
   (do
     (rf.core-reg-macros/defreg-event-macro reg-event rf.events/reg-event
       "Register a `(fn [coeffects event-vec] effect-map)` event handler
       under `id` — the ONE public event-registration form (EP-0018):
       coeffects in, a closed effects map out. The effect-map is a closed
       shape of three top-level keys — `:db` / `:rf.db/runtime` / `:fx`;
       app handlers return only `:db` / `:fx` day to day, while
       `:rf.db/runtime` (the runtime-db partition) is reserved by
       convention for framework / runtime-extension authority. Any other
       top-level key refuses the event pre-commit, nothing committed.
       Coeffects are declared uniformly via
       `:rf.cofx/requires`. Full-context work is expressed with an
       interceptor authored via `reg-interceptor` and referenced by id from
       this registration's `:interceptors` chain (there is no public
       interceptor-value constructor; EP-0022).
       Captures source-coords (Spec 001) at this call site. Additionally
       captures the whole `(reg-event :id ...)`
       form as a string under the handler's `:rf.handler/source` meta
       (Spec 009) — DEBUG-gated, elided in CLJS `:advanced` +
       `goog.DEBUG=false` production builds. See
       `re-frame.events/reg-event` for the full signature.")

     (rf.core-reg-macros/defreg-macro reg-sub rf.subs/reg-sub
       "Register a subscription under `id`. Every sub is one of three
       input-fn producers: `:db` (layer-1 — reads `app-db` directly, no
       producer), `:static` (the literal `:inputs` producer — a fixed list
       of input query-vectors), or `:parametric` (an `input-fn` producer
       — a pure `query-v -> vector-of-query-vectors` fn computing the
       inputs per concrete query). Captures source-coords (Spec 001) at
       this call site. See `re-frame.subs/reg-sub` for the full
       signature, Spec 006 §Subscription input producers, and
       spec/API.md §`reg-sub` input-production modes.")

     (rf.core-reg-macros/defreg-macro reg-fx rf.fx/reg-fx
       "Register an effect handler under `id`. Handler signature is
       `(fn [ctx args] ...)`; runs when a `reg-event` handler returns an
       effect-map carrying `[id args]` inside its `:fx` vector.
       Captures source-coords (Spec 001) at this call site. See
       `re-frame.fx/reg-fx` for the full signature.")

     (rf.core-reg-macros/defreg-macro reg-cofx rf.cofx/reg-cofx
       "Register a coeffect SUPPLIER under `id` — a value-returning fn
       whose result is delivered FLAT into a declaring handler's
       `:coeffects` map (the handler declares `:rf.cofx/requires [id]`).
       Captures source-coords (Spec 001) at this call site. See
       `re-frame.cofx/reg-cofx` for the grades (`:recordable?` /
       `:provided?`) and full signature.")

     (rf.core-reg-macros/defreg-macro reg-interceptor rf.interceptor-registry/reg-interceptor*
       "Register an interceptor DESCRIPTOR under `id` — the public
       interceptor-authoring form (EP-0022). `descriptor` is one of
       `{:before f}` / `{:after f}` / `{:before f :after g}` (a static
       interceptor) or `{:factory f}` (a parameterized family — `f` receives
       ONE arg and returns a descriptor/interceptor; the standard
       `:rf.interceptor/path` is the canonical factory consumer). The optional
       middle slot is the standard registration-metadata map (`:doc`,
       `:schema`, `:tags`, …). Event/frame `:interceptors` chains reference a
       registered interceptor by id (a bare keyword, or `[id arg]` for a
       factory) — not by inline value. Captures source-coords (Spec 001) at
       this call site. See `re-frame.interceptor-registry/reg-interceptor*`
       for the full signature."
       {:arglists '([id descriptor] [id metadata descriptor])})

     (rf.core-reg-macros/defreg-macro reg-flow rf.core-flows/reg-flow
       "Register a flow under `flow-id`. The canonical 3-slot
       grammar is `(reg-flow flow-id metadata derive-fn)`: the pure `:derive`
       fn is the third VALUE slot, and `metadata` carries `:inputs` /
       `:output-path` (both REQUIRED) plus optional `:doc` / the
       EP-0025 classification keys. The definition names no frame: each frame
       runs the flows its `:flows` config names. Captures
       source-coords (Spec 001) at this call site. Implementation ships in
       `day8/re-frame2-flows`; apps must add the artefact and require
       `re-frame.flows` at boot. See `re-frame.core-flows/reg-flow` for the full
       signature."
       {:arglists '([flow-id metadata derive-fn])})

     (rf.core-reg-macros/defreg-macro reg-route rf.core-routing/reg-route
       "Register a route under `id`. The canonical
       3-slot grammar is `(reg-route id metadata path)` — the URL pattern
       (Spec 012 §Pattern syntax) is the third VALUE slot, `metadata` the
       pure reflection map. Captures source-coords (Spec 001) at this call
       site. Implementation ships in `day8/re-frame2-routing`;
       apps must add the artefact and require `re-frame.routing` at
       boot. See `re-frame.core-routing/reg-route` for the full
       signature."
       {:arglists '([id metadata path])})

     (rf.core-reg-macros/defreg-macro reg-resource rf.core-resources/reg-resource
       "Register a resource under `resource-id` — a named, cached read of
       remote/external state. The canonical 3-slot grammar
       is `(reg-resource id metadata request-fn)`: the `:request` fetch fn is
       the third VALUE slot, and `metadata` carries the REQUIRED fail-closed
       `:scope` policy (`:rf.scope/global` | `{:from-db <resource-scope-id>}`),
       and optional `:params-schema` / `:data-schema` / `:stale-after-ms` /
       `:gc-after-ms` / `:tags`. Captures source-coords (Spec 001) at this call
       site. Implementation ships in `day8/re-frame2-resources`;
       apps must add the artefact and require `re-frame.resources` at
       boot. See `re-frame.core-resources/reg-resource` for the full
       signature."
       {:arglists '([resource-id metadata request-fn])})

     (rf.core-reg-macros/defreg-macro reg-mutation rf.core-resources/reg-mutation
       "Register a mutation under `mutation-id` — a named, causal WRITE to
       remote state that, on success, invalidates / patches / populates
       cached resource reads (run with `[:rf.mutation/execute …]`). The
       canonical 3-slot grammar is `(reg-mutation id metadata
       request-fn)`: the `:request` write fn (a Spec 014 managed-HTTP args map)
       is the third VALUE slot, and `metadata` carries the REQUIRED
       `:params-schema` plus optional `:invalidates` / `:patches` /
       `:populates` / `:scope` / `:invalidate-timing`. Write retries are
       opt-in and are not a metadata key: the request fn returns `:retry` in
       its managed-HTTP args. Captures source-coords (Spec 001) at this call
       site. Implementation ships in
       `day8/re-frame2-resources`; apps must add the artefact and
       require `re-frame.resources` at boot. See
       `re-frame.core-resources/reg-mutation` for the full signature."
       {:arglists '([mutation-id metadata request-fn])})

     (rf.core-reg-macros/defreg-macro reg-resource-scope rf.core-resources/reg-resource-scope
       "Register a PURE named scope resolver under `scope-id` (EP-0016 D3) —
       the one scope-resolution currency reused by resource registration,
       route resources, ensure / subscriptions, invalidation descriptors, and
       clear-scope. The canonical 3-slot grammar is
       `(reg-resource-scope scope-id metadata resolve-fn)`: the `:resolve` fn is
       the third VALUE slot, and `metadata` carries the declared `:inputs` map
       `{name [:db <rf-path>]}` (plus optional `:doc`). `:inputs` is REQUIRED and
       the resolver's first arg is ALWAYS the resolved inputs map; read the whole
       db by declaring it on the root path (`{:inputs {:db [:db []]}}`). The
       shipped input source is `[:db <rf-path>]`; `[:runtime …]` is reserved. A nil
       resolve result is FAIL-CLOSED. Referenced via `{:from-db <scope-id>}`.
       Captures source-coords (Spec 001) at this call site. Implementation ships
       in `day8/re-frame2-resources`; apps must add the artefact and
       require `re-frame.resources` at boot. See
       `re-frame.core-resources/reg-resource-scope` for the full signature."
       {:arglists '([scope-id metadata resolve-fn])})

     (rf.core-reg-macros/defreg-macro reg-app-schema rf.core-schemas/reg-app-schema
       "Register a Malli schema at a path inside app-db, as the registrar
       kind `:app-schema` (Spec 010). `reg-app-schema` is an ordinary
       member of the `reg-*` family — the schema is the POSITIONAL value
       slot: `(reg-app-schema [:user] UserSchema)` (2-slot) /
       `(reg-app-schema [:user] {:doc \"…\"} UserSchema)` (3-slot, the
       optional middle metadata map). The path is the registration id. A
       frame-agnostic program registration: every frame validates against
       the schemas it resolves, and a `:frame` is an error. Captures
       source-coords (Spec 001) at this call site. Implementation ships in
       `day8/re-frame2-schemas`.

       DEVELOPMENT-BUILD ASSERTION. The schema you register here is
       checked in dev builds only. A production build (`:advanced` with
       `goog.DEBUG` false, or `-Dre-frame.debug=false` on the JVM) still
       REGISTERS the schema so tools and agents can introspect it, but
       never CHECKS it: the candidate validator is elided, so a candidate
       that violates this schema installs silently — no rejection, no
       rollback, no diagnostic. Your app-db schemas do not run in
       production builds. Keep the real invariant in the handler, and
       set `:boundary? true` on the ingress event handler when untrusted
       input has to be validated in production too. Per Spec 010
       §Production builds.

       See `re-frame.core-schemas/reg-app-schema` for the full
       signature."
       {:arglists '([path schema] [path metadata schema])})

     (rf.core-reg-macros/defreg-macro reg-app-schemas rf.core-schemas/reg-app-schemas
       "Bulk-register a `{path -> schema}` map, each entry its own
       `:app-schema` registration. Plural form of `reg-app-schema`. Captures
       source-coords (Spec 001) at this call site. Implementation ships
       in `day8/re-frame2-schemas`.

       DEVELOPMENT-BUILD ASSERTION, exactly as for the singular form:
       every schema in the batch registers in a production build but is
       never checked there, so a violating candidate installs silently.
       Your app-db schemas do not run in production builds. Per Spec 010
       §Production builds.

       See `re-frame.core-schemas/reg-app-schemas` for the full
       signature."
       {:arglists '([path->schema] [path->schema opts])})

     (rf.core-reg-macros/defreg-macro reg-error-projector rf.core-ssr/-reg-error-projector
       "Register an error projector — `(trace-event) -> public-error-
       map`. Frames opt in via the `:ssr` config's `:public-error-id`
       key. Captures source-coords (Spec 001) at this call site. Per
       Spec 011 §Server error projection.")

     (rf.core-reg-macros/defreg-macro reg-head rf.core-ssr/-reg-head
       "Register a head-fragment producer — `(fn [db route] head-
       model)`. `id` is a namespaced keyword (e.g. `:my.app/article`);
       routes name a head via `:head` route metadata. Captures source-
       coords (Spec 001) at this call site. Per Spec 011 §Head/meta
       contract.")

     (rf.core-reg-macros/defreg-macro reg-http-interceptor rf.core-http/reg-http-interceptor
       "Register the frame-agnostic HTTP interceptor definition `id` under
       the registrar kind `:http-interceptor`. Captures source-coords
       (Spec 001) at this call site. Implementation ships in
       `day8/re-frame2-http` (Spec 014 §Middleware). Signature:
       `(reg-http-interceptor id interceptor-map)` — a single map carrying
       at least one of `:before (fn [ctx] ctx')` or
       `:after (fn [ctx response] response')`, plus any
       `:rf/registration-metadata` slots. A frame runs the definition only
       by naming `id` in its `:http-interceptors` config vector, which
       also fixes its position in the chain. A `:frame` in the map is
       `:rf.error/http-bad-interceptor`."
       {:arglists '([id interceptor-map])})

     (rf.core-reg-macros/defreg-macro reg-classification rf.classification/reg-classification*
       "Register a feature's static app-db classification under `id`, the
       registrar kind `:classification`. Captures source-coords (Spec 001) at
       this call site. `decl` is a closed map: `:sensitive` and `:large`, each
       a vector of app-db-absolute paths, plus an optional `:doc`. Every frame
       unions the registrations its own view resolves, so a declared path is
       redacted from its first egress. A malformed declaration raises
       `:rf.error/bad-classification`. Per Spec 015 §Static app-db
       classification."
       {:arglists '([id decl])})))

;; ---- reg-machine (bespoke — per-element coord stamping) -----------------

#?(:clj
   (defmacro reg-machine
     "Register a machine as an event handler. Captures source-coords
     (Spec 001) at this call site plus co-located per-element source on
     each `:guards` / `:actions` entry (`{:fn ..
     :source-coords .. :source-code ..}`) and a reference-site
     `:source-coords` co-located onto each `:states`-tree map node
     (state-node / transition map) at its spec-path (Spec 005 §Source-coord
     stamping; the `:source-*` slots are dev-only — DCE'd under
     `goog.DEBUG=false`). Implementation ships in `day8/re-frame2-machines`.
     For runtime registration use
     `re-frame.machines/reg-machine*`.

     An optional `opts` registration-metadata map may precede
     the spec — `(reg-machine machine-id opts machine)`. Its `:schema` key
     validates the dispatched OUTER event vector at the `:where :event`
     boundary, so a machine that needs BOTH a live `[:schemas :data]` AND an
     event-vector schema (the login / realworld auth shape) is expressible
     through this one surface. The framework-owned `:rf/machine?` /
     `:rf/machine` keys are stamped by the registration home and MUST NOT
     appear in `opts`."
     ([machine-id machine]
      (rf.core-reg-macros/expand-reg-machine (meta &form)
                             (symbol (str (ns-name *ns*)))
                             *file*
                             machine-id
                             machine))
     ([machine-id opts machine]
      (rf.core-reg-macros/expand-reg-machine (meta &form)
                             (symbol (str (ns-name *ns*)))
                             *file*
                             machine-id
                             machine
                             opts))))

;; ---- defmachine (value-registered per-element source capture) ------------
;;
;; `reg-machine` sees only a symbol in `(reg-machine :door/main door-machine)`,
;; so it captures nothing; `defmachine` walks the literal at the definition
;; site instead. Spec 005 §Source-coord stamping.

#?(:clj
   (defmacro defmachine
     "Define a machine-spec value with per-element source captured. A
     drop-in for `def` whose body is a literal machine-spec map:

         (defmachine door-machine
           \"optional docstring\"
           {:initial :locked
            :guards  {:may-close? (fn [_] …)}
            :actions {:count-open (fn [_] …)}
            :states  {…}})

         (reg-machine :door/main door-machine)

     Walks the literal spec at expansion time and co-locates per-element
     source — `{:fn .. :source-coords .. :source-code ..}` — onto each
     `:guards` / `:actions` entry, plus a
     reference-site `:source-coords` onto each `:states`-tree map node
     (state-node / transition map) of the def'd
     value. When that value is later passed to `reg-machine`, the source is
     already present on the stamped spec, so `(rf/handler-meta {:kind
     :machine-guard :id [machine-id guard-id]})` (and the Epoch
     machine-cascade source rendering)
     light up for value-registered machines exactly as for inline ones
     — the source is derived from the `:event` registration spec,
     no registrar side-table involved. The dev-only `:source-*`
     slots DCE under `:advanced + goog.DEBUG=false`.

     Use `defmachine` for the `def`-then-register shape; use the
     `reg-machine` macro directly when registering an inline literal."
     [name & body]
     (let [[doc-or-spec spec-or-nil] body]
       (rf.core-reg-macros/expand-defmachine (symbol (str (ns-name *ns*)))
                             *file*
                             name
                             doc-or-spec
                             spec-or-nil))))

;; ---- view registration ---------------------------------------------------

(defn reg-view*
  "Plain-fn surface for view registration. Use for runtime registration
  (computed ids, library generation, Form-3 / `create-class` bodies)
  where the `reg-view` macro's defn-shape doesn't fit. Optional metadata
  is merged with any pending source-coords from a wrapping `reg-view`.
  Per Spec 001 §Allowed forms of the middle slot."
  ([id render-fn]
   (reg-view* id {} render-fn))
  ([id metadata render-fn]
   #?(:cljs
      (rf.views/reg-view* id (rf.source-coords/merge-coords metadata) render-fn)
      :clj
      ;; The JVM registration boundary is the one home of the dev-mode view
      ;; annotations (`data-rf2-source-coord` / `data-rf-view`), as the CLJS
      ;; path annotates through the substrate wrappers: a server render emits
      ;; what the dev client render stamps, so a dev SSR page hydrates as a
      ;; clean adoption. The coords stamped are the ones stored, and the wrap
      ;; is a no-op in a production build.
      (let [coords (rf.source-coords/merge-coords metadata)]
        (rf.registrar/register!
          :view id
          (assoc coords
                 :handler-fn (rf.views.jvm-source-coord-annotation/wrap-handler-fn id coords render-fn)))))
   id))

(defn view
  "Runtime-lookup handle for a registered view. Returns the registered
  render fn (or nil if not registered) — call with the view's invocation
  args to yield the hiccup tree. Per Spec 001 §`(re-frame.core/view id)`.

  On CLJS the answer is the INSTALLED SUBSTRATE's component head for that
  registration, which on a React-hook substrate is a mountable,
  substrate-marked shell — so `($ (rf/view ::row) props)` works as the UIx
  docs advertise. `rf.views/view-head` derives it against the adapter installed
  NOW rather than the one installed at registration time, because the
  canonical boot order registers views at ns-load and calls `rf/init!`
  afterwards; the head is memoized, so repeat lookups are reference-stable
  and React reconciles them as the same component type."
  [id]
  #?(:cljs (rf.views/view-head id)
     :clj  (when-let [meta (rf.registrar/lookup :view id)]
             (:handler-fn meta))))

#?(:clj
   (defmacro reg-view
     "Register a view as a defn-shape macro. Auto-derives id from
     `(keyword (str *ns*) (str sym))` (override via `^{:rf/id :id}` meta
     on sym), auto-injects lexical `dispatch` / `subscribe` bound to the
     surrounding frame, defs the symbol and registers under the id.
     For runtime registration with computed ids or non-defn bodies, use
     `reg-view*`. Per Spec 001 §Allowed forms of the middle slot and
     Conventions §`reg-view` auto-id derivation rule."
     {:arglists '([sym args body+] [sym docstring args body+])}
     [sym & more]
     (rf.core-reg-view-macro/expand-reg-view (meta &form)
                          (symbol (str (ns-name *ns*)))
                          *file*
                          sym
                          more)))

;; ---- images (EP-0023) ----------------------------------------------------

#?(:clj
   (defmacro image
     "Construct an IMAGE value — the selected registration-set value a frame
     resolves against (EP-0023 §Image / EP-0026 §Image Keys). `rf/image` is the
     public constructor; `spec` is a map carrying EXACTLY three public keys —
     `:id` (optional), `:select-ns` (the `{:include [globs] :exclude [globs]}`
     selection map, selecting registered descriptors by their
     `:rf.provenance/ns`), and `:registrations` (inline registrar-keyed
     sections). Returns a normalized, INERT image value — PURE: no realm, no
     registrar, no side effect (an image is data, not registration). Supplied to
     `make-frame` via the `:images` vector; composition resolves by image order
     (the later image wins, EP-0026 §Layered Resolution). The EP-0023 keys
     `:include-ns` / `:exclude-ns` / `:replace` / `:replace-standard` /
     `:rf.image/requires` are RETIRED (EP-0026) and fail loud.

     A PROGRAMMATIC registration has NO provenance, and `:select-ns` cannot
     see it. The `reg-*` fn-aliases — and any code-generated or JVM-direct
     registration — leave the macro's source-coord capture unbound, so their
     descriptors carry no `:rf.provenance/ns` and are never matched by an
     `:include` glob: an APPLICATION registration made that way is reachable
     only from a frame made without `:images`. (One whose id sits under the reserved
     `:rf` root is treated as framework-owned instead: it rides the framework
     base, reported as `:rf/framework`, that every explicit composition is
     layered over. That is how the framework's own feature handlers reach an
     explicit image, so an app registering a reserved-root id
     programmatically should stamp `:ns` to keep it inside its own image.)
     Nothing reports this — the zero-match guard fires per PATTERN, not per
     descriptor, so a glob that matches some other namespace stays silent
     while the programmatic registration is quietly absent from the image.
     Stamping `:ns` (or the qualified `:rf.provenance/ns`) in the registration
     metadata restores selectability on every registration kind, and
     OVERRIDES the macro's captured coords, because the store reads the
     explicit key first; the macro form supplies the same provenance with no
     stamp. Spec 001 §Source-coordinate capture.

     A MACRO (not a plain fn) purely to elide production bytes at the authoring
     seam: `rf/image` is value-oriented, but a LITERAL inline
     `:registrations` metadata map `{:doc \"…\"}` is constructed AT THE CALL SITE
     before any runtime normalization runs, and per Spec 001 §Production elision
     contract a runtime strip cannot DCE those call-site string bytes. The macro
     runs `gate-image-spec` over the spec so each literal doc-bearing inline
     metadata slot rides the same `(if rf.interop/debug-enabled? <full> <stripped>)`
     gate the `reg-*` macros emit, DCEing the `:doc` string under `:advanced` +
     `goog.DEBUG=false`. It then delegates to the runtime CONSTRUCTOR
     `re-frame.image/image` (which stays a plain value fn for programmatic /
     computed-spec callers), so an image built from a NON-literal spec is
     unchanged and its stored descriptors are still `:doc`-normalized at
     assembly by `image-assembly/strip-descriptor-documentation`. See
     `re-frame.image/image`."
     {:arglists '([spec])}
     [spec]
     `(re-frame.image/image ~(rf.core-reg-macros/gate-image-spec spec))))

;; ---- frame management ----------------------------------------------------

(defn make-frame
  "Create a live frame and return the frame VALUE — the ONE public constructor
  (EP-0024 §One constructor, Spec 002 §Per-instance frames). It accepts BOTH
  image-selection options AND frame record-config options in one call.

  `opts` is a map (REQUIRED — a non-map `opts`, including `nil`, fails loud with
  `:rf.error/make-frame-bad-opts`; the all-defaults frame is `(make-frame {})`).
  The image-selection + value opts:

    :images        a NON-EMPTY VECTOR of image values (always a vector, even
                   for a single image), resolved into one sealed image
                   generation. A non-vector OR an empty `[]` fails loud
                   (`:rf.error/make-frame-bad-images`) — pass at least one
                   image, or OMIT `:images` and the frame seals no generation:
                   it resolves the whole source store + framework standards
                   live, and a `[kind id]` registered from more than one
                   namespace raises `:rf.error/ambiguous-registration` where it
                   is used.
    :id            the frame id (optional). When supplied, the frame is registered
                   under this id; re-`make-frame`-ing the same id is IDEMPOTENT
                   REPLACEMENT (config + generation refresh, durable state
                   preserved — hot-reload / Story-friendly, EP-0024 §Duplicate id
                   policy): image hot-reload is re-`make-frame`-ing the SAME
                   `:id` with a NEW `:images` vector, and a full replace is
                   `destroy-frame!` then `make-frame`. Diff the swap by reading
                   `frame-generation` before/after and comparing with
                   `generation-diff`). When ABSENT, the frame is LOCAL-ONLY —
                   keep the returned value and pass it (or its id) to
                   dispatch / subscribe / test helpers.
    :adapter       the active-substrate adapter binding/configuration (optional).

  EVERY OTHER key is RECORD-CONFIG, honoured in the same call: `:initial-events`
  (a vector of event vectors dispatch-sync'd into the new frame at construction,
  in order, draining to fixed point before the call returns — seed a literal
  app-db with `[[:rf/set-db {…}]]`; EP-0027), `:fx-overrides`, `:platform`,
  `:ssr`, `:doc`, `:preset`, `:tags`, the EP-0015 classification keys, etc. So
  `(rf/make-frame {:id :todo/left :images [todo-image] :fx-overrides {...}})`
  configures the frame in one call.

  Two record-config keys are owned by OPTIONAL artefacts and honoured by the
  frame LIFECYCLE, so the host listeners they name need no imperative call:
  `:url-bound? true` (routing — the browser URL-change listener)
  and `:revalidate-on` (resources — a SET drawn from the closed enum
  `#{:focus :reconnect}` naming the host signals that revalidate the frame's
  active-stale reads). Creation installs, re-registration
  reconciles, destroy removes.

  What happens when the ARTEFACT IS ABSENT differs between them, deliberately:

    :revalidate-on   without `re-frame.resources` FAILS LOUD at registration
                     (`:rf.error/resources-artefact-missing`, zero residue).
                     Nothing back-fills the listeners later, so storing a
                     declaration nobody will honour would silently never
                     revalidate.
    :url-bound? true without `re-frame.routing` REGISTERS, and stays in
                     `frame-meta`. Routing loading AFTER the frame is a
                     supported order: the artefact reconciles the frame
                     registry when its hooks install, seeding a sole
                     pre-existing declaration as the URL owner (Spec 012
                     §Multi-frame routing). The routing key that DOES fail
                     loud is `:url-strategy` — an explicitly-declared strategy
                     map only routing can validate — with
                     `:rf.error/routing-artefact-missing`.

  Returns the frame VALUE — the live lifecycle token (its representation is not
  an app-facing data contract). The frame is fully runnable: the routing
  operations `dispatch` / `subscribe` / `app-db-value` / `frame-provider` all
  accept the value directly, OR its id — they normalize the value to its id, so
  there is no separate value→id accessor to reach for. `destroy-frame!` accepts
  either too, but is the one LIFECYCLE exception: the returned value
  is an EXACT-INCARNATION token — destroying it tears down ONLY the incarnation
  this call produced (a stale value no-ops against a same-id successor), while
  destroying by the id is ADDRESS-directed (tears down whatever incarnation is
  currently live under the id). See `destroy-frame!` and spec/002-Frames.md
  §Destroy.

  Two arities mirror `re-frame.live-frame/make-frame`:
    (make-frame opts)             — resolve `:images` against the LIVE source store.
    (make-frame opts descriptors) — resolve against an explicit descriptor pool
                                    (tests / harnesses / a pre-snapshotted store)."
  {:arglists '([opts] [opts descriptors])}
  ([opts]             (rf.live-frame/make-frame opts))
  ([opts descriptors] (rf.live-frame/make-frame opts descriptors)))

(def ^{:doc "Tear down `frame-id` — the normative teardown boundary. Claims
  the exact incarnation and atomically cuts ordinary queued work, runs the
  user `:on-destroy` cascade on a private cleanup queue, tears down machines
  and compiled-view observers while live, then publishes dead. It next disposes
  the sub-cache and partition projections, runs post-dead feature cleanup,
  emits/releases trace state, removes the frame from the registry, and finally
  compare-cleans epoch state with the exact incarnation token. A post-claim,
  pre-dead ordinary dispatch may enqueue, but the drain rejects it before any
  handler/effect/child runs; dead/absent operations no-op and emit the always-on
  frame-destroyed diagnostic. A frame VALUE target carries exact-incarnation
  authority — a stale value no-ops against a same-id successor — while a
  frame-id keyword is address-directed and tears down whatever incarnation is
  currently live. Idempotent. Per Spec 002 §Destroy."}
  destroy-frame! rf.frame/destroy-frame!)

;; Flow and schema queries, lifecycle and validator installation live at home
;; in `re-frame.flows` / `re-frame.schemas`; only their registration macros are
;; here, for call-site source-coord capture (spec/API.md §Conventions).

;; ---- clearing ------------------------------------------------------------

(def ^{:doc "Dispose every cached entry in a frame's runtime sub-cache
  and clear the cache. Disposal is synchronous and unconditional. For
  tests and hot-reload. Per spec/API.md §Clearing registrations.

  NOT a registrar inverse — this clears a runtime CACHE, and keeps its
  bang. The registrar inverse is `clear` (below)."}
  clear-sub-cache! rf.subs.cache/clear-sub-cache!)

;; ---- the registrar inverse: (clear kind id) ------------------------------
;;
;; The registrar is ONE map, `(kind, id) -> metadata` (Spec 001). The read side
;; speaks that grammar, and so does its inverse: ONE door, `(clear kind id)`,
;; for every clearable kind, with no per-kind clear names.

(def ^:private clear-kinds
  "The closed kind set of `clear` — the registrar's own kind set MINUS
  `:frame` (a frame is a live runtime object torn down by `destroy-frame!`,
  not a registrar row).

  Spec 001's kind table carries the per-kind `clear` column this set is the
  code half of."
  (disj rf.registrar/kinds :frame))

(defn- clear-bad-request!
  "Fail loud (`:rf.error/registrar-clear-bad-request`) for `clear`'s own
  argument validation: an unknown kind, or a trailing opts map. Never returns
  normally."
  [kind reason detail]
  (rf.error/throw-error!
    :rf.error/registrar-clear-bad-request
    'rf/clear
    (str "rf/clear: " detail)
    {:recovery :fix-the-clear-call
     :extra    {:kind        kind
                :reason      reason
                :clear-kinds (vec (sort clear-kinds))}}))

(defn clear
  "Remove ONE registration, `(clear kind id)` — the inverse of the `reg-*`
  verbs, and the mirror of the `(kind, id)` grammar the read side already
  speaks. Returns `id`, for every kind. Per spec/API.md §Clearing
  registrations and Spec 001 §Registry model.

    (rf/clear :sub  :cart/total)                    ;; => :cart/total
    (rf/clear :cofx :now)                           ;; every registrar kind
    (rf/clear :flow :cart/total)

  Each kind routes to its OWNING lifecycle fn, never short-cutting a kind that
  owns runtime state straight to `rf.registrar/unregister!`: `:flow` emits
  `:rf.flow/cleared`, and each frame running the flow vacates its output on
  its next event (Spec 013 §Re-registration),
  `:http-interceptor` emits `:rf.http.interceptor/cleared`, `:route` emits
  `:rf.route/cleared`, and the resources trio disposes per-frame resource
  runtime state. The plain registrar kinds — `:event` `:sub` `:fx` `:cofx`
  `:interceptor` `:view` `:head` `:error-projector` `:app-schema`
  `:classification` — have no owning
  lifecycle fn, so `unregister!` (which forgets provenance, marks the
  live-frame projection dirty, and emits `:rf.registry/handler-cleared`) IS
  the owner.

  NO FRAME. Every registration is a program member read through each frame's
  own view, so `clear` removes it for every frame at once. A trailing opts map
  fails closed with `:rf.error/registrar-clear-bad-request` rather than being
  ignored (Principles §No silent swallow). A frame stops running a flow or an
  HTTP interceptor when it is re-made without naming it.

  An unknown kind fails closed too, with the message naming the closed set.
  Clearing a kind owned by an artefact that is not on the classpath raises
  that artefact's documented `:rf.error/<artefact>-artefact-missing`, because
  the dispatch goes THROUGH the existing optional-capability wrappers.

  There is NO clear-all arity: `re-frame.test-support` and
  `rf.registrar/clear-kind!` are the fixture-side bulk verbs.
  `clear-sub-cache!` (above) and the
  other cache / buffer `clear-*!` names are a different axis — they clear
  runtime state, not registrations."
  ([kind id]
   (case kind
     :flow             (rf.core-flows/clear-flow id)
     :http-interceptor (rf.core-http/clear-http-interceptor id)
     :route            (rf.core-routing/clear-route id)
     :resource         (rf.core-resources/clear-resource id)
     :mutation         (rf.core-resources/clear-mutation id)
     :resource-scope   (rf.core-resources/clear-resource-scope id)
     (:event :sub :fx :cofx :interceptor :view :head :error-projector :app-schema
      :classification)
     (rf.registrar/unregister! kind id)
     (clear-bad-request!
       kind :unknown-kind
       (str ":kind " (pr-str kind) " is not a clearable registry kind. The "
            "closed set is " (pr-str (vec (sort clear-kinds))) ".")))
   id)
  ([kind _id opts]
   (clear-bad-request!
     kind :opts-on-a-non-frame-scoped-kind
     (str ":kind " (pr-str kind) " takes no opts — received " (pr-str opts)
          ". Every registration is a program member, so clear removes it for "
          "every frame at once; to stop one frame running a flow or an HTTP "
          "interceptor, re-make that frame without naming it."))))

;; ---- dispatch and subscribe ----------------------------------------------
;;
;; `dispatch` / `dispatch-sync` are JVM macros that capture call-site coords
;; and expand to the seam above, with a CLJS value alias each for
;; higher-order use; `subscribe` is a plain fn on both hosts. Every op takes
;; `[payload]` / `[payload opts]`, and the frame is the ambient scope, an
;; explicit `{:frame target}` (a frame id or a live frame value), or a
;; `capture-frame` hold (Spec 002 §Routing).

(def ^{:doc "Return a reaction whose value is the registered sub's current
  output for `query-v` (`[sub-id & args]`); deref to read. A plain fn on both
  hosts, so it is also a value — `(map subscribe query-vs)`, `(partial
  subscribe query-v)`. Use `subscribe-once` for a one-shot read; for a frame
  carried across an async boundary use the `:subscribe` op from a
  `capture-frame`. Per Spec 006 §Lookup algorithm.

  Two public arities:

      (subscribe query-v)            ;; ambient frame (carried scope)
      (subscribe query-v opts)       ;; `opts` may carry `:frame` (a frame-id
                                     ;; keyword OR a live frame value)

  The `{:frame …}` opt is the public way to target an explicit frame. Inside
  a `reg-view` body the injected `subscribe` reads the render-time frame and
  attributes a read's errors to the view's definition site."
       :arglists '([query-v] [query-v opts])}
  subscribe rf.subs/subscribe)

(def ^{:doc "One-shot read of a sub's current value — subscribes, derefs,
  then unsubscribes. Does NOT retain a cache reference. The **advanced**
  live one-shot read for non-reactive consumers — REPL sessions, SSR
  builders, tools, and integration tests that want what the running frame
  sees right now; for reactive consumers (Reagent views, tools holding
  the reaction) use `subscribe`. NOT the default update-phase idiom:
  ordinary event/effect design derives from the handler's declared
  coeffects (`:rf.cofx/requires`, EP-0017) and pure helpers, so the read
  rides the causal record rather than an ambient side-effect. And **never
  from inside a machine callback** (`:guard` / `:action` / `:entry` /
  `:exit`): an in-callback ambient read is unrecorded, so replay could
  select a different transition — a machine takes external facts by
  payload threading or a declared recordable coeffect (machines-only
  `{:rf/sub query-v :as fact-id}`). Per Spec 006 §subscribe-once and
  Cross-Spec-Interactions §2.

  Call shapes, mirroring `subscribe`:

      (subscribe-once query-v)               ;; ambient frame (carried scope)
      (subscribe-once query-v {:frame f})    ;; explicit-frame opts form

  The `{:frame …}` opt is the public way to target a named frame from
  outside any scope (`f` is a frame-id keyword or a live frame value) —
  parallel to `subscribe`'s opts form."}
  subscribe-once rf.subs/subscribe-once)

(def ^{:doc "Return one counted share of `r` — the reaction `subscribe`
  returned — to the sub-cache; the last share disposes the entry
  **synchronously** (per Spec 006 §Reference counting and disposal).
  Returns nil. Per spec/API.md §Dispatch and subscribe.

      (let [r (subscribe [:items])]
        …
        (unsubscribe r))

  Each call returns ONE share, so N subscribes need N releases. The release
  is identity-guarded: a reaction whose slot is gone — evicted by
  re-registration, `clear-sub-cache!` or hot reload, or its frame destroyed
  and re-made — is a silent no-op that never touches the successor entry,
  and so is `nil`. No frame is resolved, so a release is safe from any
  context. A query vector is refused with `:rf.error/bad-unsubscribe-arg`.

  Verb-axis carve-out (per Conventions §Tear-down verb axis):
  the `un-` prefix is reserved as the singular form for
  the sub-cache ref-count decrement. It is distinct from the registrar
  inverse of `reg-sub`, `(clear :sub id)` (above): `clear`
  drops the registration; `unsubscribe` releases a live cache
  ref-count."}
  unsubscribe     rf.subs/unsubscribe)

(def ^{:doc "Compute a subscription's value against a supplied `db`,
  bypassing the reactive cache. It is how an event handler reads
  framework state: compute the vector a view subscribes to against the
  handler's own `:rf.db/runtime` coeffect —
  `(compute-sub [:rf/machine id] (:rf.db/runtime cofx))`,
  `(:nav-token (compute-sub [:rf/route] rt))` — rather than spelling
  where the framework stores it. It is also the pure, JVM-runnable form
  a test asserts a sub with: hand it a `query-v` and an `app-db` value
  and it returns what the registered sub would compute. `db` may be an
  app-db, a runtime-db or a whole frame-state value. No live cache
  mutation and no frame required; a view reads the cached value with
  `subscribe`."}
  compute-sub     rf.subs/compute-sub)

;; `inject-cofx` is not on the facade: a handler declares its coeffects with
;; `:rf.cofx/requires` (Spec 001 §`inject-cofx` is removed).

#?(:clj
   (defmacro dispatch
     "Enqueue `event-vec` on the target frame's router; returns nil
     immediately, BEFORE the handler runs. Captures call-site coords
     for error-trace attribution. For HoF / programmatic use
     (no call-site capture) call the value-position `dispatch` alias
     (CLJS) or `re-frame.router/dispatch!` directly (JVM). Per Spec 002
     §Routing.

     Two public arities:

       (dispatch event-vec)             ;; ambient frame (carried scope)
       (dispatch event-vec opts)        ;; `opts` may carry `:frame` (a
                                        ;; frame-id keyword OR a live frame
                                        ;; object) + other opts

     The `{:frame …}` opt is the public way to target an explicit frame.
     Canonical `event-vec` shape (best practice — not enforced):
       [<event-id>]                   ;; trivial
       [<event-id> <single-scalar>]   ;; single-argument
       [<event-id> {<k> <v>}]         ;; multi-argument — single map payload
     Variadic `[<id> a b c]` is accepted by the runtime for v1-migration
     and caller convenience; the linter nudges new code toward the map
     form. See spec/Conventions.md §Canonical event-vector shape."
     {:arglists '([event-vec] [event-vec opts])}
     ([arg1]
      (rf.core-call-site-macros/build-dispatch-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                               arg1 nil))
     ([arg1 arg2]
      (rf.core-call-site-macros/build-dispatch-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                               arg1 arg2))))

#?(:clj
   (defmacro dispatch-sync
     "Run `event-vec` end-to-end synchronously; the router drains to
     fixed point, then returns nil. For tests / REPL / bootstrap only.
     A call that targets a frame while that frame is draining (from one of
     its handlers, interceptors or effects) is rejected: the event does
     not run, and development builds emit
     `:rf.error/dispatch-sync-in-handler` — return a `[:dispatch …]`
     effect instead. A call that targets ANOTHER frame mid-drain runs,
     with the dev warning `:rf.warning/cross-frame-dispatch-sync-during-drain`.
     Captures call-site coords. For HoF /
     programmatic use (no call-site capture) use the value-position
     `dispatch-sync` alias (CLJS) or `re-frame.router/dispatch-sync!`
     directly (JVM). Per Spec 002 §dispatch-sync.

     Two public arities:

       (dispatch-sync event-vec)        ;; ambient frame
       (dispatch-sync event-vec opts)   ;; `opts` may carry `:frame`

     The `{:frame …}` opt is the public way to target an explicit frame.
     Canonical `event-vec` shape — see `dispatch` docstring above; same
     best-practice convention applies (id-first, with at most one
     trailing map; variadic tolerated, linter nudges)."
     {:arglists '([event-vec] [event-vec opts])}
     ([arg1]
      (rf.core-call-site-macros/build-dispatch-sync-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                    arg1 nil))
     ([arg1 arg2]
      (rf.core-call-site-macros/build-dispatch-sync-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                    arg1 arg2))))

;; ---- capture-frame (the keystone) + frame-aware closures ------------------

(defn current-frame-id
  "Return the active frame id the in-effect scope carries — a keyword.
  Resolution is the carried-invariant scope/hold chain via
  `rf.frame/require-current-frame!` (EP-0002): the dynamic `*current-frame*`
  stamp or the closest React-context frame boundary, a `frame-provider`
  (SCOPE) or `frame-root` (ENSURE) (CLJS only, via the
  `:adapter/current-frame` late-bind hook). There is NO `:rf/default`
  floor — called under no established scope it raises
  `:rf.error/no-frame-context` rather than reporting an invented default.
  This is the context READER form; it is `frame-scoped` and so requires a
  scope. Per Spec 002 §Resolver surface + §Reading the frame from React
  context."
  []
  (rf.frame/require-current-frame! :current-frame-id
                                {:where 're-frame.core/current-frame-id}))

(defn capture-frame
  "Return a frame api — the keystone affordance for
  carrying a frame into closures and across async boundaries. Per Spec
  002 §capture-frame and Cross-Spec-Interactions §10 Plain Reagent fn
  under a `frame-provider`.

  Two arities:
    (capture-frame)           — capture the ambient frame
                                (`(current-frame-id)`) at CREATION time.
    (capture-frame frame-id)  — frame api locked to an explicit `frame-id`;
                                no surrounding `with-frame` / frame-
                                provider needed.

  Returns:

    {:frame         <id>
     :dispatch      (fn ([event] [event opts]))
     :dispatch-sync (fn ([event] [event opts]))
     :subscribe     (fn [query-v])}

  The frame is captured at CREATION; every op targets the captured
  frame and survives async — the frame api is the answer to \"ambient
  frame lookup does not survive `setTimeout` / `Promise.then` /
  WebSocket `onmessage` / observer callbacks\":

    (rf/reg-view StreamView [_]
      (let [{:keys [dispatch]} (rf/capture-frame)]   ;; captures render frame
        (ws/subscribe! (fn [msg] (dispatch [:ws/incoming msg])))
        [:div \"streaming…\"]))

  The frame api is pinned to the frame INCARNATION live at capture, not
  merely to its id: once that incarnation is destroyed, the dispatch ops
  do nothing and `:subscribe` returns nil, each emitting
  `:rf.error/frame-destroyed` — even after a new frame is created under
  the same id. Capturing an id with no live frame pins nothing: its ops
  follow whichever frame holds the id when they run.

  A per-call `:frame` in the dispatch opts MUST NOT override the
  captured frame — the frame api is LOCKED to one frame. It is a bundle
  of ops, not a container: read the frame's app-db value via
  `(rf/app-db-value (:frame handle))`, not the frame api itself.

  EP-0002: the no-arg form captures the scope/hold stamp at CREATION
  time via `rf.frame/require-current-frame!` — it captures ONLY when a real
  scope exists at capture time. Capturing outside any scope raises
  `:rf.error/no-frame-context`, never a captured `:rf/default` (per Spec
  002 §Resolver surface). Use the 1-arity `(capture-frame frame-id)` to
  lock a frame api to a named frame from outside any scope (the right shape
  for async callbacks / tools / tests / SSR)."
  ([]         (rf.capture-frame/make-capture-frame
                (rf.frame/require-current-frame!
                  :capture-frame {:where 're-frame.core/capture-frame})
                nil))
  ([frame-id] (rf.capture-frame/make-capture-frame frame-id nil)))

;; ---- frame-scope lexical macros ------------------------------------------

#?(:clj
   (defmacro with-frame
     "Pin `*current-frame*` to an existing frame-id for `body`'s
     lexical scope. The pin form:

       (with-frame :existing-frame-id body+)

     The frame is **not** created or destroyed by this macro — use
     `with-new-frame` when you want eval-bind-run-destroy. Throws at
     compile time on a vector argument.

     For async closures that fire after body returns, capture via
     `capture-frame` — the ONE public HOLD primitive. Per Spec 002
     §with-frame."
     {:arglists '([frame-id body+])}
     [frame-id & body]
     (rf.core-reg-view-macro/expand-with-frame frame-id body)))

#?(:clj
   (defmacro with-new-frame
     "Eval `expr`, bind the resulting frame to `sym`, run `body` with
     `*current-frame*` bound to it, and destroy the frame on exit
     (success or exception). The eval-bind-run-destroy form:

       (with-new-frame [sym (rf/make-frame opts)] body+)

     `expr` may be `(rf/make-frame opts)` (EP-0023 — returns the live
     frame OBJECT) or any expression yielding a frame target (object or
     id); whatever
     is bound is destroyed on body exit (`dispatch` / `subscribe` /
     `destroy-frame!` accept either). Throws at compile time on a
     keyword argument — use `with-frame` to pin to an existing
     frame-id.

     For async closures that fire after body returns, capture via
     `capture-frame` — the body's dynamic binding has unwound and
     `destroy-frame!` has already run by then. Per Spec 002 §with-frame."
     {:arglists '([[sym expr] body+])}
     [bindings & body]
     (rf.core-reg-view-macro/expand-with-new-frame bindings body)))

;; ---- view ergonomics (CLJS only) -----------------------------------------
;;
;; The two frame-boundary components, one verb each: `frame-provider` SCOPES
;; an existing frame and `frame-root` ENSURES one (Spec 002 §`frame-provider` /
;; §`frame-root`). Their impls live in `re-frame.views`, off the JVM load path.

#?(:cljs (def ^{:doc "SCOPE-only Reagent component.

  `[rf/frame-provider {:frame :todo} & children]`: provides an ALREADY-CREATED
  frame's id to descendant views through React context and creates / refreshes /
  destroys nothing. Children resolve `(current-frame-id)` to the provided frame
  unless a lexical `with-frame` or dynamic binding overrides. FAILS LOUD when
  the named frame is absent (`:rf.error/frame-provider-frame-absent`). The
  scope-into-React counterpart to `rf/with-frame` (a dynamic var cannot cross
  React's render boundary).

  Given an `:id` (the ENSURE key), FAILS LOUD naming `frame-root`
  (`:rf.error/frame-provider-given-id`) — providers scope; roots ensure. To
  CREATE the frame if absent, use `rf/frame-root {:id …}`. Per Spec 002
  §`frame-provider`."}
         frame-provider rf.views/frame-provider))

#?(:cljs (def ^{:doc "ENSURE Reagent component — a COMMIT-OWNED TWO-PASS
  boundary.

  `[rf/frame-root {:id :todo :images [todo-image]} & children]`: CREATES the
  frame if absent (via `rf/make-frame`), REUSES it WITHOUT re-seeding if present,
  provides its id to descendants; NO destroy-on-unmount. Takes the `rf/make-frame`
  opts (`:id` / `:images` / `:initial-events` / `:url-bound?` …); `:id` is
  REQUIRED and must be a keyword (a missing/non-keyword `:id` →
  `:rf.error/frame-root-missing-id`).

  The ENSURE runs in a client `useLayoutEffect` (NOT during render): the first
  render emits no descendant subtree, the frame is created AFTER commit, and only
  then do the children render against the now-live frame. A Suspense-aborted /
  concurrent-discarded render therefore creates + seeds NOTHING (no ghost frame).
  `:initial-events` fire ONCE on first successful creation per committed frame-id
  lifetime, never on a re-mount / reconfiguration (idempotent re-mount preserves
  durable state; StrictMode-tolerant). A keyed remount preserves durable state; a
  MOUNTED `:id` / opts change FAILS LOUD (`:rf.error/frame-root-reconfigured`).

  Given a `:frame` (the SCOPE key), FAILS LOUD naming `frame-provider`
  (`:rf.error/frame-root-given-frame`). True ownership (modals, multi-instance
  widgets) stays expressible as `rf/make-frame` + `rf/destroy-frame!` inside a
  `create-class`. Per Spec 002 §`frame-root`."}
         frame-root rf.views/frame-root))

;; ---- routing helpers ------------------------------------------------------
;;
;; `reg-route` is on the facade for macro-time source-coordinate capture;
;; `route-link` is its view counterpart. URL codec and lifecycle queries live
;; in `re-frame.routing`. A `:url-bound? true` frame owns its strategy listener:
;; construction installs it and frame destruction removes it.

(def ^{:doc "Registered view at `:route/link` — renders an `<a href=...>`
  from a route-id and intercepts plain primary-button clicks to dispatch
  `[:rf.route/navigate {:url <href>}]`. Modifier-key / middle-clicks defer to
  the browser.
  Shape: `[rf/route-link {:to :route-id :params {} :query {} :fragment
  \"\" & html-attrs} & children]`. Per Spec 012 §Linking from views."}
  route-link  rf.core-routing/route-link)

;; ---- resource helpers (Spec 016) ------------------------------------------
;;
;; The registration macros are above; the facade adds `resolve-resource-scope`,
;; which a logout handler calls. Introspection (`resource-state`,
;; `mutation-state`) stays at home in `re-frame.resources`, and a registered
;; spec is the generic registrar read:
;;   (:rf/resource (rf/handler-meta {:kind :resource :id id}))
;;   (:rf/mutation (rf/handler-meta {:kind :mutation :id id}))

(def ^{:doc "Resolver helper: resolve the named resolver `scope-id` against
  the supplied `db` value, returning a canonical concrete scope or nil — a
  plain function over the resolver registry, NOT an effect (no app-state /
  dispatch side effects). It is a PURE data helper: it routes
  through the trace-free `resolve-scope*-pure` evaluator and so does NOT emit
  `:rf.resource/scope-resolved` — a passive read advertised as pure carries no
  observability side effect. The CAUSAL resolution boundaries that DO carry
  that trace evidence (a resource event's `{:from-db …}` scope, route entry,
  mutation settle) run the traced `resolve-scope*` wrapper instead. Canonical
  use is the logout idiom (resolve the concrete old scope
  from the handler's coeffect db and pass it to `:rf.resource/clear-scope`
  concretely). Per Spec 016 §`clear-scope` resolves the concrete scope from
  the coeffect db (EP-0016 issue 7). Implementation ships in
  `day8/re-frame2-resources`."}
  resolve-resource-scope rf.core-resources/resolve-resource-scope)

;; ---- per-instance view state (Spec 002 §Per-instance view state) ---------
;;
;; One spelling for every view substrate. A macro like every `reg-*`, so the
;; concern's sub and setter record the calling namespace and an explicit image
;; selecting it carries them (Spec 002 §Image resolution and composition); the
;; CLJS value alias beside it is the programmatic door (Conventions
;; §Convention A). A clear is a dispatch of the shared `:rf.state/clear` event,
;; so there is no clear fn here.

#?(:clj
   (rf.core-reg-macros/defreg-macro reg-state rf.core-state/reg-state
     "Register the per-instance state `concern`: a sub and a setter
     event under `concern` reading and writing `[:ui concern instance-key]`,
     plus the shared clear event `[:rf.state/clear concern instance-key]`.

         (rf/reg-state :todo.ui/expanded? {:default false})
         @(rf/subscribe [:todo.ui/expanded? id])     ;; read
         (rf/dispatch [:todo.ui/expanded? id true])  ;; write

     `opts` may carry `:default` and nothing else. Returns `concern`.
     Captures source-coords (Spec 001) at this call site. See
     `re-frame.core-state/reg-state` and Spec 002 §Per-instance view state."
     {:arglists '([concern] [concern opts])}))

#?(:cljs
   (def ^{:doc "Fn-alias of the `reg-state` macro for HoF / programmatic
  registration (no source-coord capture, so the concern's sub and setter
  carry no `:rf.provenance/ns` — reached only by a frame made without
  `:images`). Register the per-instance state `concern`. See
  `re-frame.core-state/reg-state` and Spec 002 §Per-instance view state."}
     reg-state rf.core-state/reg-state))

;; ---- introspection (Spec 002 §The public registrar query API) -----------
;;
;; A query without `:frame` reads the registration table through a fresh
;; `re-frame.live-frame/live-view`, never through `rf.registrar`'s own reads:
;; those consult the frame view `call-with-frame-resolution` binds around every
;; sub build, dispatch, fx and view resolution, so from inside a handler they
;; would silently answer for THAT frame. The distinction lives here, at the
;; public boundary, because the `rf.registrar` reads are the runtime's
;; resolution path and must stay generation-routed. A query WITH `:frame` runs
;; those same reads inside `call-with-frame-resolution` for the target frame,
;; so no resolver walk is duplicated here.

(defn- frame-target-ids
  "The public ids of the frames a frame-targeted read resolves, for the
  `:rf.error/frame-no-generation` diagnostic: every frame carrying a sealed
  image generation and, when `reads-live-store?`, every frame made without
  `:images`. No-id frames are left out, because a caller reaches one only
  through its frame value."
  [reads-live-store?]
  (vec (sort (cond-> (rf.live-frame/live-frame-ids)
               reads-live-store?
               (into (remove #(= "rf.frame" (namespace %)))
                     (rf.frame/live-store-frame-ids))))))

(defn- resolve-live-frame-object
  "Resolve a `:frame` target to the LIVE frame OBJECT it names — verifying the
  object carries a sealed image generation — or FAIL LOUD
  (`:rf.error/frame-no-generation`). `frame-target` is a REGISTERED frame id
  (keyword, looked up in the process-local live-frame registry) OR a direct
  live frame OBJECT — the same target shapes `rf/frame-generation` accepts.
  NO fallback to the default/realm registrar: an id that names no live frame,
  a non-frame value, or a frame object with no `:rf.frame/generation` slot is
  an error naming the bad target. Returns the frame OBJECT (the caller passes
  it to `re-frame.live-frame/call-with-frame-resolution`, which binds its
  generation, or reads its generation directly) — the façade re-surfaces that
  BEHAVIOUR through the public reads without exporting the internal
  `re-frame.live-frame` names.

  `where-sym` is the public fn the caller invoked, used in the diagnostic so the
  message names the user-facing surface (`rf/registrations` / `rf/handler-meta`
  / `rf/frame-generation`). `reads-live-store?` is true for the `{:frame f …}`
  registrar query, which also reads a frame made without `:images` (its caller
  returns such a frame before reaching here), so the diagnostic describes and
  lists both kinds of frame; `rf/frame-generation` needs a generation, so its
  diagnostic lists only the frames carrying one."
  [frame-target where-sym reads-live-store?]
  (let [frame-object (if (rf.live-frame/frame-value? frame-target)
                       frame-target
                       (rf.live-frame/live-frame frame-target))]
    (if (some? (rf.live-frame/frame-resolution-generation frame-object))
      frame-object
      (let [ids (frame-target-ids reads-live-store?)]
        (rf.error/throw-error!
          :rf.error/frame-no-generation
          where-sym
          (str where-sym ": :frame target " (pr-str frame-target)
               (if reads-live-store?
                 (str " names no live frame. A frame-targeted registrar read "
                      "resolves the (kind, id) set through the target frame's "
                      "OWN view: the sealed image generation of a frame made "
                      "with :images, or the registration store, resolved live, "
                      "for a frame made without (EP-0023 §Frame-derived live "
                      "registration resolution).")
                 (str " does not resolve to a live frame carrying an image "
                      "generation. Only a frame made with :images carries one; "
                      "the {:frame f} form of rf/registrations and "
                      "rf/handler-meta also reads a frame made without."))
               " The target is a frame id registered in the process-local "
               "live-frame registry, or a direct frame object returned by "
               "rf/make-frame; there is NO fallback to the default/realm "
               "registrar. Frames this read resolves: " (pr-str ids) ".")
          {:recovery :target-a-live-frame-id-or-a-direct-frame-object
           :extra    {:frame          frame-target
                      :live-frame-ids ids}})))))

(defn- resolve-frame-query-target
  "Resolve the target of a `{:frame f …}` registrar query: a frame made without
  `:images` (it resolves the registration store live, so the query reports
  what that frame runs) is returned as is; anything else must be a live frame
  carrying a generation (`resolve-live-frame-object`, which fails loud)."
  [frame-target where-sym]
  (if (rf.live-frame/live-store-frame? frame-target)
    frame-target
    (resolve-live-frame-object frame-target where-sym true)))

(defn- assert-registrar-query!
  "Fail loud (`:rf.error/registrar-query-invalid`) unless `arg` is a map whose
  keys all sit in `allowed`: `:not-a-map` for any other value (a leftover
  positional keyword, most often), `:unknown-key` naming the keys outside
  `allowed`. A misspelled `:frame` is the case this exists for, since it would
  otherwise read the store. Returns `arg`."
  [arg allowed where-sym]
  (let [allowed-keys (vec (sort allowed))
        grammar      (str where-sym ": a registrar query is a map of "
                          (pr-str allowed-keys) " — {:kind k …} reads the "
                          "registration store, {:kind k :frame f …} reads "
                          "live frame f's resolved program. ")
        unknown      (when (map? arg)
                       (vec (sort-by pr-str (remove allowed (keys arg)))))]
    (cond
      (not (map? arg))
      (rf.error/throw-error!
        :rf.error/registrar-query-invalid
        where-sym
        (str grammar "Received " (pr-str arg) ", which is not a map; there "
             "is no positional arity.")
        {:recovery :pass-a-query-map
         :extra    {:reason   :not-a-map
                    :received arg}})

      (seq unknown)
      (rf.error/throw-error!
        :rf.error/registrar-query-invalid
        where-sym
        (str grammar "Received the unknown key(s) " (pr-str unknown) ".")
        {:recovery :pass-a-query-map
         :extra    {:reason       :unknown-key
                    :unknown-keys unknown
                    :allowed-keys allowed-keys}})

      :else arg)))

;; The kinds a public registrar query may address. The registrar's own closed
;; kind set, MINUS the reserved-but-empty `:frame` slot (see
;; `not-queryable-kinds` below), PLUS the two DERIVED machine kinds, which are
;; not registrar kinds at all: `handler-meta` serves them from the enclosing
;; machine's `:event` registration spec (`rf.core-machines/machine-handler-meta`),
;; and `registrations` returns `{}` for them because there is no side-table to
;; enumerate — documented behaviour, per Spec 005 §`:machine-guard` /
;; `:machine-action` handler-meta surfaces.
(def ^:private queryable-kinds
  (into #{:machine-guard :machine-action} rf.registrar/kinds))

;; Reserved-but-empty registrar slots. A query naming one of these is a caller
;; asking a real question at the wrong door, so the answer names the right door
;; rather than being a `{}` that reads as "there are none".
(def ^:private not-queryable-kinds
  {:frame "rf/frame-ids (the live frame ids) or rf/frame-meta (one frame's config)"})

(defn- assert-queryable-kind!
  "Validate the `:kind` of a registrar query. `:frame` throws
  `:rf.error/registrar-kind-not-queryable` naming the real door; a kind outside
  `queryable-kinds` throws the registrar's own
  `:rf.error/unknown-registry-kind`, with `where` naming the QUERY fn rather
  than `register!`. Returns `kind` when it is queryable."
  [kind where-sym]
  (cond
    (contains? not-queryable-kinds kind)
    (rf.error/throw-error!
      :rf.error/registrar-kind-not-queryable
      where-sym
      (str where-sym ": :kind " (pr-str kind) " is a RESERVED-BUT-EMPTY registrar "
           "slot — nothing is ever registered under it, so this query could only "
           "ever answer \"none\", which would read as an authoritative empty "
           "catalogue while the real store sits elsewhere. Read "
           (get not-queryable-kinds kind) " instead.")
      {:recovery :use-the-owning-read
       :extra    {:kind kind
                  :read (get not-queryable-kinds kind)}})

    (not (contains? queryable-kinds kind))
    (rf.error/throw-error!
      :rf.error/unknown-registry-kind
      where-sym
      (str where-sym ": :kind " (pr-str kind) " is not a registry kind. The "
           "queryable set is " (pr-str (vec (sort queryable-kinds))) ".")
      {:recovery :fix-registration
       :extra    {:kind kind
                  :reason :not-a-queryable-registry-kind}})

    :else kind))

(defn- resolve-registrar-query
  "Resolve a registrar query map to `[:store nil]` or `[:frame frame-object]`,
  validating its keys against `allowed` and its `:kind` on the way. Every
  public registrar query enters through here, so the grammar is stated once.
  The presence of `:frame` decides, so `{:frame nil …}` fails loud rather than
  reading the store."
  [arg allowed where-sym]
  (assert-registrar-query! arg allowed where-sym)
  (assert-queryable-kind! (:kind arg) where-sym)
  (if (contains? arg :frame)
    [:frame (resolve-frame-query-target (:frame arg) where-sym)]
    [:store nil]))

(defn frame-generation
  "Return the SEALED, resolved image GENERATION a live frame made with `:images`
  is running — the inert image-assembly generation value it resolves `(kind, id)` lookups through
  (EP-0023 §Frame — \"a reference to the resolved image generation it is
  running\"). The dedicated raw READ over the EP-0023 frame->generation model,
  for tools (Pair MCP `describe-image`, Xray) that EP-0023 forbids from consuming
  `re-frame.live-frame` / `re-frame.image-assembly` internals directly.

  `frame-target` is a REGISTERED frame id (keyword, looked up in the process-
  local live-frame registry) OR a direct live frame OBJECT (`rf/make-frame`'s
  return value).

  Returns the generation map with the documented stable public keys (EP-0023
  §Specification Summary; `re-frame.image-assembly`):

    :rf.gen/resolver  {[kind id] descriptor, …}   the sealed [kind id] map
    :rf.gen/images    [<normalized image value> …]
    :rf.gen/kinds     #{kind …}                     kinds present, for tools
    :rf.gen/shadows   [{:registration [kind id]
                        :image <loser> :shadowed-by <winner>} …]   the
                      cross-image SHADOW REPORT (EP-0026 §Shadow Report)

  The `:rf.gen/shadows` shadow report is the data the programmer reads to see
  exactly what a LATER image overrode in an EARLIER one. A FLAT vector, one
  entry per cross-image shadow — three keys, nothing else (no winner descriptor,
  image index, tier, source namespace, or scope tag): the shadowed
  `:registration` `[kind id]`, the `:image` id the LOSER was defined in, and the
  `:shadowed-by` id of the FINAL live winner. An EMPTY vector when no later image
  shadowed an earlier one (the common deliberate-no-override case —
  `(empty? (:rf.gen/shadows (frame-generation f)))` asserts nothing was
  overridden). For a shadow CHAIN — `[base override-a override-b]` defining one
  `[kind id]` — every loser names the FINAL winner (`override-b`), not the
  immediate predecessor, so an assertion never walks a chain. Every shadow is
  cross-image (a within-image collision is an error, not a resolved winner). This
  is the EP-0026 model — there are no declared-winner keys (`:replace` /
  `:replace-standard` fail loud): define the winner in a later image, image order decides,
  and read this report to assert on (or log / ignore) what each later image
  shadowed. A tool wanting fuller detail (source namespace, etc.) reads the live
  registration's `:rf.provenance/*` via the `{:frame f …}` form of
  `rf/registrations` / `rf/handler-meta`.

  FAILS LOUD (`:rf.error/frame-no-generation`) when `frame-target` does not
  resolve to a live frame carrying a generation: NO nil-as-default, NO fallback
  to the default/realm registrar — a frame-generation read needs a live EP-0023
  frame. Use the `{:frame f …}` form of `registrations` / `handler-meta` for
  per-`(kind, id)` resolution WITH provenance; use this when a view needs the
  whole generation (selected registrations, kinds, shadow report) without
  per-kind round-trips. Per Spec 002 §The public registrar query API; EP-0023
  §Public API / Use-Case 7."
  [frame-target]
  (-> (resolve-live-frame-object frame-target 'rf/frame-generation false)
      (rf.live-frame/frame-resolution-generation)))

(def ^{:doc "Compute the DIFF between two sealed image generations as four
  `[kind id]` sets (EP-0023 §Hot Reload — \"A good reload result should be a
  concrete diff\"). PURE — a function of the two generation VALUES, not a
  frame target: read `rf/frame-generation` before and after a re-`make-frame`
  call and diff the two (a read, not a bespoke verb):

    (let [before (rf/frame-generation :my/frame)
          _      (rf/make-frame {:id :my/frame :images new-images})
          after  (rf/frame-generation :my/frame)]
      (rf/generation-diff before after))
      ;; => {:added #{[kind id] …} :changed #{…} :removed #{…} :retained #{…}}

    :added    keys present in `after` but not `before`
    :removed  keys present in `before` but not `after`
    :changed  keys present in BOTH whose resolved descriptor is not `=`
    :retained keys present in BOTH whose resolved descriptor IS `=`

  \"changed\" is by descriptor value equality, so an unchanged registration
  selected by a different image composition is `:retained`, not `:changed` —
  that lets a caller invalidate only what actually changed. See
  `re-frame.live-frame/generation-diff` and spec/002-Frames.md §Image
  resolution and composition."}
  generation-diff rf.live-frame/generation-diff)

(defn registrations
  "Return all ids registered under `:kind` with their metadata — the
  introspection workhorse used by tools, agents, and storybook resolution.
  Per Spec 002 §The public registrar query API.

  ONE argument: a query map of `:kind` and, optionally, `:frame`.

    `(registrations {:kind k})` — the `{id metadata}` for `k` in
        the process registration table, resolved as a frame made without
        `:images` resolves it, `{}` if none. An id more than one namespace
        registers is not in it ([[ambiguous-registrations]] lists those). This
        read NEVER consults a bound frame view, so it returns the same answer
        from inside a sub computation, an event handler or an inspector frame as
        it does from the REPL. It is the read a tool wants when it is asking
        what the HOST PROCESS registered.

    `(registrations {:kind k :frame f})` — FRAME-TARGETED (EP-0023):
        the `{id metadata}` for `k` resolved through live frame `f`'s OWN sealed
        image generation — only the ids that frame's image carries, with the
        `:rf.provenance/ns` + inline/image + replacement/standard facts the
        resolved descriptors carry. For a frame made without `:images` it is
        the ids that frame can run from the live registration store (an id
        more than one namespace registers is not one of them). `f` is a
        REGISTERED frame id (keyword) OR a direct live frame OBJECT. FAILS
        LOUD when `f` names neither kind of frame
        (`:rf.error/frame-no-generation` — NO default fallback).

  Filtering is `filter` over the returned map — there is no `:pred` key and no
  predicate arity:

    (into {} (filter (fn [[_ m]] (:rf/machine? m)))
              (registrations {:kind :event}))

  Errors: a non-map argument, or a key other than `:kind` and `:frame` →
  `:rf.error/registrar-query-invalid`. `:kind :frame` (a reserved-but-empty
  slot) →
  `:rf.error/registrar-kind-not-queryable`, naming the real door. Any other
  non-registry kind → `:rf.error/unknown-registry-kind`."
  [arg]
  (let [[source frame] (resolve-registrar-query arg #{:kind :frame} 'rf/registrations)
        kind           (:kind arg)]
    (if (= :frame source)
      (rf.live-frame/call-with-frame-resolution
        frame
        #(rf.registrar/registrations kind))
      ((rf.live-frame/live-view) kind))))

(defn handler-meta
  "Return the registration metadata map for `[kind id]`, or `nil`. The
  general source-meta surface tools (Xray Open-in-editor, re-frame-pair
  source-jump) read to find a (kind, id)'s definition. Per Spec 002 §The
  public registrar query API.

  ONE argument: a query map of `:kind`, `:id` and, optionally, `:frame`.

    `(handler-meta {:kind k :id id})` — the metadata for
      `[k id]` in the process registration table, or nil, resolved as a frame
      made without `:images` resolves it: an id more than one namespace
      registers throws `:rf.error/ambiguous-registration`, naming the
      namespaces. For the registrar kinds (`:event :sub :fx :cofx :interceptor
      :view :route :head :error-projector :resource :mutation :resource-scope`)
      it NEVER consults a bound frame view. For `:interceptor` the metadata
      carries the registered `:rf/interceptor-descriptor` plus source coords +
      `:doc` (EP-0022).

    `(handler-meta {:kind k :id id :frame f})` — FRAME-TARGETED (EP-0023):
      the metadata for `[k id]` resolved through live frame `f`'s
      OWN sealed image generation (surfacing `:rf.provenance/ns` + inline/image +
      replacement/standard facts the resolved descriptor carries), or `nil` when
      that frame's image carries no such `[k id]`. For a frame made without
      `:images` it is what that frame runs from the live registration store,
      and an id more than one namespace registers throws the
      `:rf.error/ambiguous-registration` the frame raises when it uses the id.
      `f` is a REGISTERED frame id (keyword) OR a direct live frame OBJECT.
      FAILS LOUD when `f` names neither kind of frame
      (`:rf.error/frame-no-generation` — NO default fallback).

  The two machine kinds `:machine-guard` / `:machine-action` are NOT
  registrar kinds — `:id` is
  the 2-vector `[<machine-id> <guard-or-action-id>]`, and the dev-only
  fn-source meta (`:rf.handler/source` + coords) is DERIVED on demand from
  the machine's `:event` registration spec's co-located `:guards` /
  `:actions` entries (see `re-frame.core-machines/machine-handler-meta`).
  They are read without `:frame`:

    (handler-meta {:kind :machine-guard :id [:auth/login :form-valid?]})

  Production-elided per Spec 009 (the derivation returns nil when the
  `:source-*` slots are absent). A machine kind read with `:frame` is `nil` —
  machine kinds are not in the generation resolver.

  Errors: a non-map argument (including a bare positional keyword, as in
  `(handler-meta :event id)`), or a key other than `:kind`, `:id` and
  `:frame` → `:rf.error/registrar-query-invalid`.
  `:kind :frame` → `:rf.error/registrar-kind-not-queryable`,
  naming the real door. Any other non-registry kind →
  `:rf.error/unknown-registry-kind`."
  [arg]
  (let [[source frame] (resolve-registrar-query arg #{:kind :id :frame} 'rf/handler-meta)
        {:keys [kind id]} arg]
    (if (= :frame source)
      (rf.live-frame/call-with-frame-resolution
        frame
        #(rf.registrar/handler-meta kind id))
      (case kind
        (:machine-guard :machine-action) (rf.core-machines/machine-handler-meta kind id)
        ((rf.live-frame/live-view) kind id)))))

(defn ambiguous-registrations
  "Return the ids of `:kind` that more than one namespace registers and so
  cannot run, as `{id {provenance-ns descriptor}}` — every registration of
  each, keyed by the namespace that made it. The ids [[registrations]] omits
  and [[handler-meta]] throws `:rf.error/ambiguous-registration` for. Per Spec
  002 §The public registrar query API.

  ONE argument: the query map [[registrations]] takes.

    `(ambiguous-registrations {:kind k})` — the ambiguous ids of
        `k` in the process registration table.

    `(ambiguous-registrations {:kind k :frame f})` — the ambiguous ids of `k`
        that live frame `f` refuses: those of the registration table for a
        frame made without `:images`, `{}` for a frame running a sealed image
        generation (assembly already refused a duplicate there). FAILS LOUD
        when `f` names neither kind of frame (`:rf.error/frame-no-generation`).

  `{}` when there are none. Errors as [[registrations]]."
  [arg]
  (let [[source frame] (resolve-registrar-query arg #{:kind :frame} 'rf/ambiguous-registrations)
        kind           (:kind arg)]
    (if (= :frame source)
      (rf.live-frame/call-with-frame-resolution
        frame
        #(rf.registrar/ambiguous-registrations kind))
      (rf.registrar/ambiguous-registrations (rf.live-frame/live-view) kind))))

(def ^{:doc "Return the set of registered, non-destroyed frame ids. Per
  Spec 002 §The public registrar query API."}
  frame-ids    rf.frame/frame-ids)

(def ^{:doc "Return the effective metadata map for a frame as a flat
  shape — `:id` plus the post-preset-expansion user-supplied config.
  Per Spec 002 §The public registrar query API and Spec-Schemas
  §`:rf/frame-meta`."}
  frame-meta   rf.frame/frame-meta)

(defn app-db-value
  "Return the current `app-db` VALUE (a plain map) for the named frame,
  or `nil` if not registered. Value-form accessor (no deref, no
  container): the non-reactive snapshot read for tools, tests, the REPL
  and fx / handler bodies; a view reads reactively with `subscribe`.
  Accepts a frame-id keyword or a live frame value; values are normalized to
  their runnable id. Per Spec 002 §The public registrar query API."
  [frame-id]
  (rf.frame/frame-app-db-value (rf.frame/frame-value->id frame-id)))

(defn frame-state-value
  "Return the coherent frame-state projection for the named frame —
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, or `nil` for an
  unknown / destroyed frame. The full-frame read for SSR / epoch /
  time-travel / Xray, and the supported production read for persistence: an
  app serialises the partitions and runtime-db subtrees it keeps (e.g. with
  `pr-str`) and installs them back with `[:rf/install-frame-state saved]`
  (Spec 002 §Installing a persisted frame-state).

  Reads both partitions from the one physical frame-state container. A fresh
  frame's state is `{:rf.db/app {} :rf.db/runtime {}}`; read the runtime-db
  partition with `(:rf.db/runtime (frame-state-value frame-id))`. Accepts a
  frame-id keyword or a live frame value. Per Spec 002 §The two-partition
  frame contract."
  [frame-id]
  (rf.frame/frame-state-value (rf.frame/frame-value->id frame-id)))

;; ---- interceptors --------------------------------------------------------
;;
;; Interceptors are authored with `reg-interceptor` and referenced by id; the
;; `:before` / `:after` fns work on the context map directly. The fn twin
;; `reg-interceptor*`, the `->interceptor*` constructor and the context
;; accessors stay in their owning namespaces (EP-0022).

;; `rf/path` resolves to a throwing stub, so a stale call fails loud naming the
;; `[:rf.interceptor/path …]` factory ref (spec/API.md §Standard interceptors).
(def ^{:no-doc true
       :doc "REMOVED in EP-0022 (no alias). Calling `path` raises the hard
  error `:rf.error/path-removed`, naming the framework-registered ref
  `[:rf.interceptor/path <path-vector>]` (used in a handler's `:interceptors`
  chain) as the replacement. See `re-frame.std-interceptors/path-removed!` and
  spec/API.md §Standard interceptors."}
  path            rf.std-interceptors/path-removed!)

;; There is no `redact-interceptor`: classification is registration-owned and
;; applied at egress, never by interceptor placement (EP-0015 §7).

;; ---- privacy / spec / trace / emit / elision (Spec 009, 010) -------------

(def ^{:doc "Predicate: returns `true` when `trace-event` carries a TRUTHY
  top-level `:sensitive?` stamp. Fail-closed: any truthy value counts, so a
  malformed stamp (`\"true\"`, `:yes`, `1`) reads as sensitive rather than
  forwarding the event. Trace-event filter for privacy-aware listeners and
  off-box egress. Per Spec 009 §Privacy."}
  sensitive?           rf.privacy/sensitive?)

(def ^{:doc "Emit a trace event. Production builds elide the body
  entirely (Closure DCE on the `rf.interop/debug-enabled?` gate); in dev /
  JVM the envelope is built and delivered to the ring buffer, epoch
  recorder, and registered listeners. Per Spec 009 §Trace emit."}
  emit-trace-event!         rf.trace/emit!)

;; ---- stream-parameterized observation listener verb ----------------------
;;
;; One verb over the two raw DEV streams, `:trace` and `:epoch`: the stream is
;; data, so it rides as a leading keyword rather than one verb pair per
;; channel, and both arms DCE in production. Production observation is a
;; different verb, `register-observability-sink!`, the one production door.
;; There is no `:events` / `:errors` stream because it would be a second,
;; unprojected, fail-open production door; `re-frame.event-emit` /
;; `re-frame.error-emit` stay implementation registries. Spec 009 §Observation
;; listeners, Spec 015 §Frame-owned observability sink policy.

(def ^:private listener-streams
  "Closed vocabulary for `register-listener!` / `unregister-listener!`."
  #{:trace :epoch})

(defn- unknown-listener-stream! [verb stream]
  ;; Route through the canonical thrown-error builder
  ;; (`rf.error/throw-error!`, Spec 009 §The thrown-error shape) like every
  ;; other framework throw, so a consumer reading `(:where (ex-data e))`
  ;; finds the canonical bare `:where` slot. `verb` is the
  ;; user-facing `'rf/<surface>` symbol at every call site
  ;; (`'rf/register-listener!` / `'rf/unregister-listener!`), so it lands
  ;; directly in the canonical `:where` slot. The surface-specific
  ;; `:stream` / `:valid` slots ride in `:extra`.
  (rf.error/throw-error!
    :rf.error/unknown-listener-stream
    verb
    (str verb ": unknown listener stream " (pr-str stream)
         " — must be one of " (pr-str listener-streams)
         ". The listener-stream vocabulary is closed (no bare :trace "
         "default, no compatibility aliases); pass one of the two raw dev "
         "observation streams. Production observation is a DIFFERENT verb: "
         "rf/register-observability-sink! against a frame's :observability "
         "policy, or the (rf/configure! {:observability …}) process default.")
    {:recovery :fix-registration
     :extra    {:stream stream
                :valid  listener-streams}}))

(defn register-listener!
  "Register an observation listener `f` under `id` on `stream` — one verb
  across the two raw DEV observation streams (`:trace` / `:epoch`).
  Re-registering the same `id` on the same stream replaces. Returns `id`
  (or nil on the `:epoch` stream in a production build).

  - `:trace`  — dev-only trace-event listener (production CLJS bundles DCE
                the registration site when it is `goog.DEBUG`-gated).
  - `:epoch`  — epoch-record listener (Spec 009
                §`register-epoch-listener!`); no-op returning nil in a
                production build.

  Both members are raw and DCE'd: this verb means \"raw dev stream\".
  PRODUCTION observation is a different verb —
  [[register-observability-sink!]] against a frame's `:observability`
  policy, or the `(rf/configure! {:observability …})` process default for
  declare-once-per-process and for records with no resolvable frame. For a
  raw cross-frame record, declare an explicit
  `{:sink … :rf.egress/profile :rf.egress/local-raw}` entry on that
  default.

  An unknown `stream` throws `:rf.error/unknown-listener-stream` (closed
  vocabulary — no bare trace default, no compatibility aliases). Per
  Spec 009 §Observation listeners + Spec 015 (EP-0015) §Frame-owned
  observability sink policy."
  [stream id f]
  (case stream
    :trace  (rf.trace.tooling/register-listener! id f)
    :epoch  (when rf.interop/debug-enabled?
              (rf.epoch/register-epoch-listener! id f))
    (unknown-listener-stream! 'rf/register-listener! stream)))

(defn unregister-listener!
  "Drop the listener registered under `id` on `stream` (`:trace` /
  `:epoch`). Returns nil. No-op on the `:epoch` stream in a production
  build. An unknown `stream` throws
  `:rf.error/unknown-listener-stream`. Per Spec 009 §Observation
  listeners."
  [stream id]
  (case stream
    :trace  (rf.trace.tooling/unregister-listener! id)
    :epoch  (when rf.interop/debug-enabled?
              (rf.epoch/unregister-epoch-listener! id))
    (unknown-listener-stream! 'rf/unregister-listener! stream)))

(def ^{:doc "Return the named frame's event-keyed trace ring,
  oldest-first. Two arities:

    (rf/trace-buffer frame-id)
      Returns event bundles by default — one entry per retained
      event bundle with the run's `:dispatch-id`, raw `:trace-events`,
      and the projected six-domino slots (`:event`, `:dispatched`,
      `:handler`, `:fx`, `:effects`, `:subs`, `:renders`, `:other`).

    (rf/trace-buffer frame-id opts)
      `opts` is a filter map. `{:flat true}` returns raw trace
      events instead of event bundles. The full filter
      vocabulary lives in Spec 009 §Filter vocabulary.

  Returns `[]` for a destroyed or never-registered frame, and `[]`
  in production (the ring is never allocated under
  `goog.DEBUG=false`). Per Spec 009 §Per-frame trace rings
  (event-keyed, dev-only)."}
  trace-buffer           rf.trace.tooling/trace-buffer)

(def ^{:doc "Empty the named frame's event-keyed trace ring.
  Tooling uses this between sessions. No-op for an unknown frame,
  no-op in production. Per Spec 009 §`trace-buffer` API."}
  clear-trace-buffer!    rf.trace.tooling/clear-trace-buffer!)

;; `project-egress` is the facade's one projection door. The path walker
;; `re-frame.elision/elide-wire-value` reads no `:rf.egress/profile`, so it
;; stays at home for the framework's own emit-time chokepoints.

(def ^{:doc "Project a record or value for egress across a trust boundary
  (EP-0015 §10/§11). The public, record-level boundary primitive — the
  required step before any off-box sink. Dispatches on a record's `:kind`
  (`:rf.observe/handled-event`, `:rf.observe/error`,
  `:rf.observe/derived-tree`, or `:rf/epoch-record`, whose projector is
  late-bound from `re-frame.epoch` and raises `:rf.error/epoch-elided`
  in a build that elides epoch) to a
  private per-kind projector, falling back to walking a kindless input as a
  tree-shaped value (the direct-read path); for every tree-shaped slot it
  delegates to the internal `re-frame.elision/elide-wire-value` walker
  against the frame's classification. `opts` is a CLOSED map:
  `:rf.egress/profile` (the closed six-member enum), `:frame`, `:path`,
  `:query-v`, the `:rf.egress/include-sensitive?` /
  `:rf.egress/include-large?` / `:rf.egress/include-digests?` /
  `:rf.egress/threshold-bytes` overrides (which compose on top of the
  profile — the override wins), and the `:rf/epoch-record`-only
  `:rf.egress/include-fx-args?` / `:rf.egress/include-runtime-db?` /
  `:rf.egress/include-event-args?`. `include-runtime-db?` lifts the
  runtime-db partition whole, so a resource entry's scope and params ride it
  raw while the resource's classification still redacts the entry's data
  (Spec 016 §Xray and AI tooling). An unknown profile throws
  `:rf.error/unknown-egress-profile`; any other key throws
  `:rf.error/bad-egress-opts`. Fail-closed: projects a tree slot
  only when the frame is known; no `:rf/default` synthesis. Per Spec 015
  §Projection and Security.md §Off-box egress."}
  project-egress                   rf.projection/project-egress)

;; ---- frame-owned observability sink routing (EP-0015 §9) -----------------
;;
;; The production observation door (Spec 015 §Frame-owned observability sink
;; policy): a frame's `:observability` config, or the `configure!` process
;; default, names sink ids, and the fns registered here receive
;; ALREADY-PROJECTED records.

(def ^{:doc "Register an observability sink FN `f` under the keyword
  `sink-id` — the user/library-owned id a frame's `:observability`
  `{:sink <sink-id> ...}` entry names (EP-0015 §9). `f` receives a single
  ALREADY-PROJECTED record (a `:rf.observe/handled-event` or
  `:rf.observe/error` projected under the owning frame's classification and
  the entry's egress profile); NO sink-local redaction. Re-registering the
  same id replaces. Returns `sink-id`. The framework ships no Datadog /
  Sentry client (EP-0015 Non-Goals); the concrete sink fn is an app /
  integration-library concern. Survives `:advanced` + `goog.DEBUG=false` —
  the frame `:observability` stream is the production observation stream.
  Per Spec 015 §Frame-owned observability sink policy."}
  register-observability-sink!     rf.observability/register-observability-sink!)

(def ^{:doc "Drop the observability sink registered under `sink-id`.
  Returns nil. Per Spec 015 §Frame-owned observability sink policy."}
  unregister-observability-sink!   rf.observability/unregister-observability-sink!)

;; ---- surfaces that live at home ------------------------------------------
;;
;; Not re-exported, because each has an owning namespace (spec/API.md
;; §Conventions, the role rule): the epoch history and time-travel surface —
;; `epoch-history`, `restore-epoch!`, `replay-epoch!`, `replace-frame-state!` —
;; in `re-frame.epoch`, whose listener pair is reached here as
;; `(register-listener! :epoch …)`; `group-by-event` / `domino-bucket` in
;; `re-frame.trace.projection`; the stub HTTP transports in
;; `re-frame.http.stub-transport`. An epoch record goes off-box through
;; `project-egress`, which dispatches on its `:kind`.

;; ---- configure / substrate adapter / boot --------------------------------

(def ^:private known-configure-keys
  "The closed set of top-level keys `configure!` READS. Mirrors the
  delegations in `configure!`'s body and the §Configure keys table in
  spec/API.md — keep the three in step.

  `configure!`'s vocabulary is CLOSED and its keys are BARE, so an
  unrecognised bare key reads as a typo of a real key rather than as an
  extension point: `:epoch-histroy` applies nothing, and
  `unknown-configure-keys` below is what makes it say so, as
  [Conventions §No silent swallow] requires of that shape."
  #{:epoch-history :trace-buffer :elision :observability})

(defn- unknown-configure-keys
  "Return a vector of `config-map`'s top-level keys that `configure!` does
  not read AND that are not the caller's own extension keys.

  The carve-out is [Conventions §No silent swallow]'s own line: a
  USER-NAMESPACED key (`:myapp/thing`) is an extension key a wrapper may
  legitimately thread through a composed config value, so it passes in
  silence. A BARE key (`:epoch-histroy`) or a FRAMEWORK-namespaced one
  (`:rf.foo/bar`, `:rf/x`) is a name the runtime is entitled to recognise,
  so failing to recognise it is a typo signal.

  Dev-only by construction: the only caller is inside `configure!`'s
  `rf.interop/debug-enabled?` gate, so the walk DCEs under `:advanced` +
  `goog.DEBUG=false`."
  [config-map]
  (into []
        (remove (fn [k]
                  (or (contains? known-configure-keys k)
                      (and (keyword? k)
                           (some? (namespace k))
                           (not (re-find #"^rf(\.|$)" (namespace k)))))))
        (keys config-map)))

(defn configure!
  "Configure process-level runtime knobs from one nested map:
    :epoch-history {:depth N}                       ring depth (default 50; 0 disables)
    :trace-buffer  {:events-retained N}             per-frame trace-ring retained-event
                                                    count — one slot per dequeued event,
                                                    however many trace events it emitted
                                                    (default 50; 0 disables retention),
                                                    for every frame without its own
                                                    `:rf.trace/events-retained`. Per
                                                    Spec 009 §Per-frame trace rings.
    :elision       {:rf.egress/threshold-bytes N}     wire-elision size threshold
                                                    (default 16384; 0 disables runtime
                                                    auto-detect — only declared / schema
                                                    entries elide)
    :observability {:errors         [<sink-entry>]  PROCESS-DEFAULT production
                    :handled-events [<sink-entry>]} observation sink policy — the
                                                    same closed entry grammar
                                                    `make-frame` takes. `nil` clears.

      (configure! {:epoch-history {:depth 100}
                   :trace-buffer  {:events-retained 25}
                   :elision       {:rf.egress/threshold-bytes 8192}
                   :observability {:errors [{:sink :app/sentry}]}})

  A missing key leaves its subsystem untouched; a present one delegates to
  that subsystem's configurator, in the order above. `:epoch-history`
  applies nothing in a production build, and `:trace-buffer` nothing in a
  build without the trace tooling; the other keys still apply.

  `:observability` declares production observation once per process, so a
  multi-frame app need not restate it on every `make-frame`. Precedence is
  PER STREAM: a frame that declares a stream uses its own entries for it, one
  that omits the stream inherits the default's, and `{:errors []}` on a frame
  is that frame's opt-out. One source is consulted per record per stream, so
  a sink named by both is invoked once. It is also the only door for records
  with no resolvable frame (`:rf.error/no-frame-context`, a destroyed frame's
  teardown report, …), projected fail-closed under an explicitly nil frame;
  a stale `:frame` id is never re-resolved, so it cannot reach a same-id
  successor's sink. Unlike the other keys it validates at call time, failing
  loud with `:rf.error/bad-frame-classification`, because a policy that
  installed silently would surface only as a dropped record. Per Spec 015
  §Frame-owned observability sink policy.

  A non-map argument throws `:rf.error/configure-bad-arg`, in every build.
  An unrecognised top-level key applies nothing: a bare or `rf`-namespaced
  one emits the dev warning `:rf.warning/unknown-configure-key`, because the
  vocabulary is closed and it reads as a typo, while a user-namespaced key
  (`:myapp/thing`) passes in silence, so a wrapper can thread a composed
  config through (Conventions §No silent swallow). Per-frame settings live
  on frame metadata. Per Tool-Pair §How AI tools attach."
  [config-map]
  ;; A plain guard, not `assert`: `:elide-asserts true` (or JVM `*assert*`
  ;; false) compiles an assert away, and the keyed `(rf/configure!
  ;; :trace-buffer …)` call migration produces would then apply nothing in
  ;; silence. Mirrors `re-frame.live-frame/validate-opts!`. It checks the
  ;; top-level shape only; nested opts are each subsystem's.
  (when-not (map? config-map)
    (rf.error/throw-error!
      :rf.error/configure-bad-arg
      'rf/configure!
      (str "rf/configure!: the config argument must be a MAP — got "
           (:type (rf.error/diag-value-summary config-map))
           ". Pass ONE nested config map, e.g. "
           "(rf/configure! {:epoch-history {:depth 100} "
           ":trace-buffer {:events-retained 25}}). The keyed arity "
           "(rf/configure! :trace-buffer {…}) is RETIRED — put the subsystem "
           "key INSIDE the map.")
      ;; `diag-value-summary` is content-free BY CONSTRUCTION (a closed `:type`
      ;; vocabulary + an integer count), so the offending argument's SHAPE
      ;; travels off-box without echoing app-owned configuration.
      {:recovery :pass-a-config-map
       :extra    {:received (rf.error/diag-value-summary config-map)}}))
  (when rf.interop/debug-enabled?
    (when-let [opts (:epoch-history config-map)]
      (rf.epoch/configure! opts)))
  (when-let [opts (:trace-buffer config-map)]
    (when-let [f (rf.late-bind/get-fn :trace.tooling/configure-trace-buffer!)]
      (f opts)))
  (when-let [opts (:elision config-map)]
    (rf.elision/configure! opts))
  ;; By key presence, so `{:observability nil}` clears the default while
  ;; omitting the key leaves it alone. Its validation throws, unlike the
  ;; other keys': a policy is refused at the call the author typed.
  (when (contains? config-map :observability)
    (rf.observability/configure! (:observability config-map)))
  ;; Dev-only and observational: the key walk and the message sit inside the
  ;; gate, so they DCE in production, and the call still returns nil.
  (when rf.interop/debug-enabled?
    (when-let [unknown (seq (unknown-configure-keys config-map))]
      (let [unknown (vec unknown)
            known   (vec (sort known-configure-keys))]
        (rf.trace/emit!
          :warning
          :rf.warning/unknown-configure-key
          {:unknown-keys unknown
           :known-keys   known
           :detected-at  (rf.interop/now-ms)
           :recovery     :ignored
           :reason       (str "rf/configure! was given unrecognised top-level "
                              "key" (when (next unknown) "s") " "
                              (pr-str unknown) ". The runtime reads only "
                              (pr-str known)
                              " — a bare or `rf`-namespaced unknown key is "
                              "ignored, so a typo (e.g. `:epoch-histroy` for "
                              "`:epoch-history`) applies nothing and changes "
                              "no behaviour. Check for a misspelt key. "
                              "User-namespaced keys (e.g. `:myapp/thing`) "
                              "pass through silently — a wrapper may thread a "
                              "composed config value straight in.")}))))
  nil)

(defn current-config
  "Return the process-level config values currently in effect, in
  `configure!`'s own nested shape — the read twin of `configure!`:

      (configure! {:epoch-history {:depth 100}})
      (current-config)
      ;; => {:epoch-history {:depth 100 :trace-events-keep 50}
      ;;     :trace-buffer  {:events-retained 50}
      ;;     :elision       {:rf.egress/threshold-bytes 16384}}

      (get-in (current-config) [:epoch-history :depth])   ;; => 100

  PROCESS values only. There are no per-frame effective values here: a
  frame carrying its own `:rf.trace/events-retained` metadata is not
  reflected, because this reads the same slots `configure!` writes and
  nothing else. `:observability` follows that rule exactly: it reports the
  PROCESS DEFAULT policy verbatim (absent when none is declared) and never
  the effective policy of any frame, which is per-stream and resolved per
  record.

  A subsystem key is ABSENT — not `nil`, not a fabricated default —
  when its OWN producer is unavailable in this build. The optional keys
  are INDEPENDENT: `:epoch-history` comes from `re-frame.epoch`, read only
  in a development build, and `:trace-buffer` from the dev-only
  `re-frame.trace.tooling` sibling, read through its own late-bind hook.
  `(get-in (current-config) [:epoch-history :depth])` reads `nil` in a
  production build, which is the answer a health query wants; the facade
  handles the availability branch so callers do not resolve owning-ns vars
  by symbol.

  The returned map is a snapshot read key-by-key, not a transactional
  one, and it is not promised to be wire-serialisable. Namespaced
  pass-through keys handed to
  `configure!` (the `:myapp/thing` extension carve-out) are NOT
  reflected back: `configure!`'s vocabulary is closed, so only the keys
  it READS have live values to report.

  Per Conventions §`configure!` (mutation) vs `current-config` (read)."
  []
  (let [trace-read (rf.late-bind/get-fn :trace.tooling/current-trace-buffer-config)
        ;; Absent, not nil, when no process default is declared. Unlike the
        ;; dev-only keys this is about configuration, never the build:
        ;; `re-frame.observability` is always loaded.
        observability (rf.observability/current-observability-config)]
    (cond-> {:elision (rf.elision/current-config)}
      rf.interop/debug-enabled? (assoc :epoch-history (rf.epoch.state/current-config))
      trace-read          (assoc :trace-buffer (trace-read))
      (some? observability) (assoc :observability observability))))

(def ^{:doc "Tear down the installed adapter. Calls the adapter's
  `:dispose-adapter!` fn (if present). Teardown is a one-way terminal
  boundary: it clears only the exact generation it claimed, marks the
  adapter disposed even when cleanup throws, then rethrows the primary
  cleanup failure. A fresh adapter may install afterward; stale teardown
  can never clear that replacement. Per Spec 006 §Adapter lifecycle.

  The `destroy-` verb places this fn on the lifecycle-boundary axis
  of the tear-down verb taxonomy (per Conventions §Tear-down verb
  axis) — adapter install/destroy is symmetric with frame
  create/destroy (`destroy-frame!`)."}
  destroy-adapter!     rf.substrate.adapter/dispose-adapter!)

(def ^{:doc "Return the installed adapter SPEC MAP — the exact value
  passed to `init!` — or `nil` if none is installed. Carries the adapter
  contract fns (`:make-state-container`, `:replace-container!`,
  `:render`, `:dispose-adapter!`, …) plus a `:kind` discriminator.

  ONE read, map-shaped. Branch code asks for the discriminator as a KEY:
  `(:kind (rf/current-adapter))` — `:rf.adapter/reagent`,
  `:rf.adapter/uix`, `:rf.adapter/fresco`,
  `:rf.adapter/plain-atom`, `:rf.adapter/ssr`, or `nil` for a custom
  adapter map that picked no canonical kind (nothing is synthesised for
  it). PRESENCE is a question about the MAP, never about `:kind`.
  Per Spec 006 §Adapter introspection."}
  current-adapter      rf.substrate.adapter/current-adapter)

(defn- bad-init-arg!
  "Raise `:rf.error/no-adapter-specified` with a consistent reason
  string. Factored out of `init!`'s nil-check and not-map-check."
  [received]
  (rf.error/throw-error!
    :rf.error/no-adapter-specified
    'rf/init!
    "rf/init! takes the adapter spec map directly — there is no keyword form, no nil form, and no default-adapter registry. Require the adapter ns and pass its `adapter` Var: (rf/init! reagent/adapter)."
    {:recovery :no-recovery
     :extra    (cond-> {:expected "adapter spec map"}
                 (some? received) (assoc :received received))}))

(defn init!
  "Boot — installs a substrate adapter. Pass the adapter spec map
  directly (no default-adapter registry):
    (require '[re-frame.adapter.reagent :as reagent])
    (rf/init! reagent/adapter)
  Non-map / nil raises `:rf.error/no-adapter-specified`. Per Spec 006
  §Adapter selection at boot.

  IDEMPOTENT FOR THE SEATED ADAPTER, NOT FOR ANY ADAPTER. Re-calling
  `init!` with the adapter already seated does not reinstall it.
  Calling it with a DIFFERENT adapter raises
  `:rf.error/adapter-already-installed` and leaves the seated adapter
  untouched — swapping substrates means `(rf/destroy-adapter!)` first.
  Two spec maps are the same adapter when they carry the same canonical
  `:rf.adapter/*` `:kind`, or when they are the identical map.

  That rule is what keeps hot reload working. Every adapter Var is a
  plain `def`, so a reload re-evaluates the map with fresh fn identities
  and a `^:dev/after-load` boot re-calls `init!` with a structurally
  fresh map — but a canonical `:rf.adapter/*` kind is a stable token that
  survives the re-evaluation, so the re-call keeps the seated generation and
  publishes the fresh map's capabilities over it (`refresh-capabilities!`):
  the reloaded hooks answer, the live containers and roots stay put. A
  CUSTOM adapter with no canonical kind falls back to object identity,
  so re-evaluating its Var on reload and re-calling `init!` DOES raise:
  hold such an adapter in a `defonce`, or call `destroy-adapter!` in the
  after-load fn.

  `init!` does NOT create a `:rf/default` frame. Per Spec 002 §`:rf/default`
  is the conventional app-frame id (EP-0002), the runtime never synthesises a default
  frame — frame identity is carried, not found. Declare your app's root
  frame explicitly (`(rf/make-frame {:id :rf/default …})` / `with-frame` / a
  `frame-root` (ENSURE — creates it if absent) or `frame-provider` (SCOPE —
  scopes an already-created one)) and dispatch / subscribe within that scope.
  `:rf/default` is the conventional id for a single-frame app's frame, but
  the app names it explicitly; the runtime will not infer it."
  [adapter-map]
  (cond
    (nil? adapter-map)        (bad-init-arg! nil)
    (not (map? adapter-map))  (bad-init-arg! adapter-map)
    :else
    (do
      ;; Is the adapter handed in the seated one? `same-adapter?` (canonical
      ;; `:kind`, else identity) rather than `=`, which is what keeps hot
      ;; reload working. A different seated adapter makes `install-adapter!`
      ;; throw `:rf.error/adapter-already-installed`, so a second adapter is
      ;; never ignored in silence.
      (if (rf.substrate.adapter/same-adapter?
            adapter-map
            (rf.substrate.adapter/current-adapter))
        (rf.substrate.adapter/refresh-capabilities! adapter-map)
        (rf.substrate.adapter/install-adapter! adapter-map))
      ;; EP-0022: re-seed the framework-standard interceptors
      ;; (`:rf.interceptor/path`) so the standard refs survive a test fixture's
      ;; `rf.registrar/clear-all!`. Idempotent.
      (rf.std-interceptors/register-standard-interceptors!)
      ;; EP-0027: re-seed the framework-standard `:rf/set-db` event
      ;; (into both the regular registrar AND the image standard registry) so it
      ;; resolves after a `rf.registrar/clear-all!`. Idempotent.
      (rf.events/register-set-db-standard!)
      ;; The framework-standard `:rf/install-frame-state` event, re-seeded the
      ;; same way and for the same reason. Idempotent.
      (rf.events/register-install-frame-state-standard!)
      nil)))

;; ---- feature inspection --------------------------------------------------
;;
;; Ships to production. The feature→coordinate table is static data in
;; `re-frame.features`, never a require into the optional artefacts, which
;; would pull every one into every bundle (spec/API.md §Feature inspection).

(def ^{:doc "Return a map of every optional feature keyword to its
  inspection entry — static coordinate data (`:maven` / `:require` /
  `:spec`) merged with the live `:loaded?` status. Known features:
  `:schemas` `:machines` `:routing` `:flows` `:http` `:ssr`
  `:resources`.

  The one feature-inspection door. Read the boolean out of it —
  `(get-in (rf/features) [:routing :loaded?])`; an unknown feature keyword
  reads `nil`, because its entry is simply absent. For boot-time rather
  than first-call failure, write the guard (not an elidable `assert`):

      (when-not (get-in (rf/features) [:routing :loaded?])
        (throw (ex-info \"re-frame.routing is not on the classpath\"
                        (get (rf/features) :routing))))

  Detection is a pure keyword lookup in the always-loaded late-bind
  hooks atom — no require into the optional namespace. Ships to
  production (NOT elided). Per spec/API.md §Feature inspection."
       :arglists '([])}
  features rf.features/features)
