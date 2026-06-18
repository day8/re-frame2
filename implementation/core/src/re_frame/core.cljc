(ns re-frame.core
  "Public API surface for re-frame2. Per spec/API.md.

  Users `(:require [re-frame.core :as rf])` and call `rf/dispatch`,
  `rf/reg-event`, etc. Internal namespaces are not part of the
  contract; per-namespace docs carry the design rationale.

  Topology — this ns is a thin façade:

    - Optional-artefact wrappers live in `re-frame.core-<feature>`
      sibling namespaces (`flows`, `routing`, `schemas`, `machines`,
      `ssr`, `epoch`, `http`); each looks up its target fn through
      the late-bind hook table so requiring this ns does NOT pull
      the feature artefacts.
    - Macro-helper code is factored into three siblings —
      `core-reg-macros` (reg-event, reg-sub, reg-fx, reg-cofx
      expansion), `core-call-site-macros` (dispatch / dispatch-sync /
      subscribe call-site expansion), `core-reg-view-macro`
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
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.router :as router]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.cofx :as cofx]
            [re-frame.interop :as interop]
            [re-frame.subs :as subs]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.std-interceptors :as std-interceptors]
            [re-frame.privacy :as privacy]
            [re-frame.spec :as spec]
            [re-frame.late-bind :as late-bind]
            [re-frame.features :as features]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]
            [re-frame.trace.projection :as trace-projection]
            ;; JVM-only autoload for the focused-event-only cascade-DAG
            ;; aggregator (rf2-931pm). CLJS deliberately omits the
            ;; require so Closure DCE keeps the aggregator + per-fn
            ;; keyword interns out of production bundles — the
            ;; bundle-isolation gate verifies. Xray's Reactive panel
            ;; loads the ns explicitly from its tools-side build.
            #?@(:clj [[re-frame.trace.cascade]])
            [re-frame.event-emit :as event-emit]
            [re-frame.error :as error]
            [re-frame.error-emit :as error-emit]
            [re-frame.elision :as elision]
            [re-frame.projection :as projection]
            ;; EP-0015 §3 + §7 (bead item 2, rf2-mngp4o): the imperative
            ;; `add-marks` / `set-marks` façade exports are removed (frame-
            ;; owned classification + `project-egress` are the public
            ;; boundary now). `re-frame.marks` stays a side-effect-only
            ;; require — it publishes the `:marks/*` late-bind hooks (the
            ;; trace bus's `:marks/project-trace-event` emit chokepoint, the
            ;; per-(kind,id) `:marks/register-marks!` / `:marks/marks-for`
            ;; registry, etc.) that must be bound at boot. No `marks/*`
            ;; symbols are referenced from this façade any more.
            [re-frame.marks]
            ;; EP-0015 §3 (rf2-ueg1tn): required for its ns-load side-effect
            ;; only — it publishes the `:frame-classification/*` late-bind
            ;; hooks `re-frame.frame/reg-frame` consults to validate + install
            ;; frame-owned durable classification. No symbols are referenced
            ;; here; the require exists so the hooks are bound at boot before
            ;; any runtime `reg-frame` call.
            [re-frame.frame-classification]
            ;; EP-0015 §9 (rf2-t55hxg.7): frame-owned observability sink
            ;; routing — the central §9 claim made production-live. Required
            ;; for BOTH its public façade exports (`register-observability-sink!`
            ;; et al., re-exported below) AND its ns-load side-effect: it
            ;; publishes the `:observability/route-handled-event` /
            ;; `:observability/route-error` late-bind hooks the router +
            ;; error-emit substrate fire (a static require would close a
            ;; load cycle). Pulls only `re-frame.projection` +
            ;; `re-frame.frame` + `re-frame.late-bind` (core spine), so it is
            ;; bundle-isolation neutral and production-surviving (the
            ;; always-on observability stream is NOT DCE'd, by design).
            [re-frame.observability :as observability]
            ;; EP-0013 D2: the app-value ns — EP-0023 RETAINED-INTERNAL substrate.
            ;; EP-0023 (rf2-pl97nd.2) REMOVED the public facade re-exports it once
            ;; backed (`rf/module` / `rf/app` / `rf/app-*` / `rf/install!` /
            ;; `rf/reinstall!`); the ns stays as the registrar-backed installation
            ;; path during migration. It is still REQUIRED HERE for its ns-load
            ;; SIDE-EFFECT — it publishes the `:app-value/project` late-bind hook
            ;; `re-frame.realm/installed-app` consults to project a realm's
            ;; installed app VALUE over its registrar (bound at boot; a static
            ;; require would close a load cycle), and core wires the descriptor
            ;; lowering hooks (`:app-value/install-descriptor!` et al.) it consults
            ;; (see below). The ns pulls only `re-frame.realm` + `re-frame.registrar`
            ;; + `re-frame.late-bind` (core spine), so it is bundle-isolation
            ;; neutral. The alias is no longer referenced directly now the facade
            ;; re-exports are gone; the require is load-side-effect-only.
            [re-frame.app-value :as app-value]
            ;; EP-0013 D1 stage 8 (rf2-blibek): the realm-targeted QUERY
            ;; readers — `realm/realm-registrations` / `realm-handler-meta` /
            ;; `realm-handler-ids` back the public map-shaped facade forms
            ;; `(rf/registrations {:realm r :kind k})` etc. (open-issue 11).
            ;; A leaf on the registrar/late-bind spine; bundle-isolation
            ;; neutral. The default-realm keyword arities are byte-identical.
            [re-frame.realm :as realm]
            ;; EP-0023 (rf2-32siq3.17): the public `rf/image` constructor —
            ;; an IMAGE value, the selected registration-set value a frame
            ;; resolves against (EP-0023 §Image, §Public API). PURE inert data
            ;; (no realm, no registrar, no side effect), re-exported below as
            ;; `rf/image`. `re-frame.image` lives in the core artefact and
            ;; pulls only `clojure.string` + `re-frame.error` (the core
            ;; spine), so it is bundle-isolation neutral; an app that never
            ;; constructs an image leaves the constructor as Closure-DCE dead
            ;; code.
            [re-frame.image :as image]
            ;; EP-0023 collapse slice 2 (rf2-32siq3.32): the runnable-object
            ;; live-frame ns. Required for its PUBLIC hot-reload export
            ;; `reload-images!` (re-exported below as `rf/reload-images!`) — the
            ;; frame-targeted, composition-replacing image reload that preserves
            ;; frame memory (EP-0023 §Hot Reload / §Public API). The ns is
            ;; ALREADY in the core spine transitively (router / subs / frame /
            ;; registrar require it for the `{:frame object}` resolution seam), so
            ;; this adds no new load edge; it pulls only `re-frame.image-assembly`
            ;; + `re-frame.registrar` + `re-frame.frame` + `re-frame.late-bind` +
            ;; `re-frame.error` (the core spine), so it is bundle-isolation
            ;; neutral. An app that never hot-reloads leaves `rf/reload-images!`
            ;; (and the live-frame reload fns) as Closure-DCE dead code — the
            ;; elision probe deliberately does NOT root the image-loading path
            ;; (see elision_probe.cljs). EP-0023 collapse FINALE (rf2-32siq3.48):
            ;; the OBJECT-returning `live-frame/make-frame` IS now the backing of
            ;; the facade `rf/make-frame` (repointed off the EP-0013 RECORD
            ;; constructor once every record caller was migrated).
            [re-frame.live-frame :as live-frame]
            ;; EP-0023 (rf2-32siq3.11): the EP-0013 -> EP-0023 migration shims +
            ;; diagnostics ns. Carries the surface dispositions as inspectable
            ;; data plus the fail-loud migration diagnostics (the cross-realm
            ;; duplicate-frame-id assertion). A leaf over `re-frame.error` /
            ;; `re-frame.realm` (both already in the core spine); re-exported
            ;; below as `rf/migration-map` / `rf/migration-explain` /
            ;; `rf/assert-process-local-frame-id!`. An app that never reaches a
            ;; superseded EP-0013 surface leaves them as Closure-DCE dead code.
            [re-frame.migration :as migration]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.core-flows    :as rf-flows]
            [re-frame.core-routing  :as rf-routing]
            [re-frame.core-schemas  :as rf-schemas]
            [re-frame.core-machines :as rf-machines]
            [re-frame.core-resources :as rf-resources]
            [re-frame.core-ssr      :as rf-ssr]
            [re-frame.core-epoch    :as rf-epoch]
            [re-frame.core-http     :as rf-http]
            ;; Macro helpers — the macro siblings live in their own
            ;; nss; required here with `:include-macros` so CLJS callers
            ;; see them under `rf/<name>`.
            [re-frame.core-reg-macros        :as rm
             #?@(:cljs [:include-macros true])]
            [re-frame.core-call-site-macros  :as csm]
            [re-frame.core-reg-view-macro    :as rvm]
            ;; NOTE: re-frame.substrate.plain-atom is deliberately NOT
            ;; required here. It carries no ns-load side-effect that core
            ;; needs — its only load-time effect is the CLJS
            ;; `:adapter/add-on-dispose!` / `:adapter/dispose!` route-hook!
            ;; block, which is keyed on the adapter being the
            ;; `(rf/init! ...)`-installed one and so is inert unless a
            ;; consumer installs plain-atom. `rf/init!` takes the adapter
            ;; map directly (no default-adapter registry), so consumers
            ;; that want the plain-atom path require the ns themselves and
            ;; pass `plain-atom/adapter` — eagerly pulling it into the
            ;; facade would ship JVM/SSR adapter code into every consumer
            ;; (rf2-1mdvlv).
            #?@(:cljs [[re-frame.views :as views]]))
  ;; The macros are defined in this ns's `#?(:clj ...)` blocks below.
  ;; CLJS users see them under `rf/<name>` via this self-`:require-
  ;; macros`, so `(:require [re-frame.core :as rf])` is the only import
  ;; CLJS apps need.
  #?(:cljs (:require-macros
             [re-frame.core :refer [reg-event
                                    reg-sub reg-fx reg-cofx reg-frame
                                    reg-interceptor
                                    reg-flow reg-route reg-app-schema reg-app-schemas
                                    reg-resource reg-mutation reg-resource-scope
                                    reg-error-projector reg-head
                                    reg-http-interceptor
                                    reg-view reg-machine defmachine
                                    dispatch dispatch-sync subscribe
                                    ->interceptor
                                    with-frame with-new-frame frame-bound-fn
                                    with-fx-overrides
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
     (def ^{:doc "Fn-alias of the `reg-event` macro for HoF / programmatic
  registration (no source-coord capture). Register a
  `(fn [coeffects event-vec] effect-map)` event handler under `id` — the
  ONE public event form (EP-0018). See `re-frame.events/reg-event` and
  spec/API.md §Registration."}
       reg-event       events/reg-event)
     (def ^{:doc "Fn-alias of the `reg-sub` macro for HoF / programmatic
  registration (no source-coord capture). Register a subscription under
  `id`. See `re-frame.subs/reg-sub` and spec/API.md §Registration."}
       reg-sub         subs/reg-sub)
     (def ^{:doc "Fn-alias of the `reg-fx` macro for HoF / programmatic
  registration (no source-coord capture). Register an effect handler
  under `id`. See `re-frame.fx/reg-fx` and spec/API.md §Registration."}
       reg-fx          fx/reg-fx)
     (def ^{:doc "Fn-alias of the `reg-cofx` macro for HoF / programmatic
  registration (no source-coord capture). Register a coeffect SUPPLIER —
  a value-returning fn whose result is delivered FLAT into a declaring
  handler's `:coeffects` map (the handler declares `:rf.cofx/requires
  [id]`). See `re-frame.cofx/reg-cofx` (grades: `:recordable?` /
  `:provided?`) and spec/API.md §Registration."}
       reg-cofx        cofx/reg-cofx)
     (def ^{:doc "Fn-alias of the `reg-interceptor` macro for HoF /
  programmatic registration (no source-coord capture). Register an
  interceptor DESCRIPTOR (`{:before}` / `{:after}` / `{:before :after}` /
  `{:factory}`) under `id` — the public interceptor-authoring form (EP-0022).
  Event/frame `:interceptors` chains reference it by id. See
  `re-frame.interceptor-registry/reg-interceptor*` and spec/API.md
  §Registration."}
       reg-interceptor  icpt-reg/reg-interceptor*)
     (def ^{:doc "Fn-alias of the `reg-frame` macro for HoF / programmatic
  registration (no source-coord capture). Atomically create + register
  a frame under `id` with the given metadata. See
  `re-frame.frame/reg-frame` and spec/API.md §Registration."}
       reg-frame       frame/reg-frame)
     (def ^{:doc "Fn-alias of the `reg-route` macro for HoF / programmatic
  registration (no source-coord capture). Register a route under `id`;
  `metadata` carries `:path` at minimum. Implementation ships in
  `day8/re-frame2-routing`; require `re-frame.routing` at boot. See
  `re-frame.core-routing/reg-route` and spec/API.md §Registration."}
       reg-route       rf-routing/reg-route)
     (def ^{:doc "Fn-alias of the `reg-resource` macro for HoF / programmatic
  registration (no source-coord capture). Register a resource — a named,
  cached read of remote/external state — under `resource-id`;
  `resource-spec` carries the REQUIRED fail-closed `:scope` policy plus
  `:params-schema` / `:request`. Implementation ships in
  `day8/re-frame2-resources`; require `re-frame.resources` at boot. See
  `re-frame.core-resources/reg-resource` and spec/API.md §Registration."}
       reg-resource    rf-resources/reg-resource)
     (def ^{:doc "Fn-alias of the `reg-resource-scope` macro for HoF /
  programmatic registration (no source-coord capture). Register a PURE named
  scope resolver under `scope-id`; `resolver` is the declared-inputs map
  `{:inputs {name [:db <rf-path>]} :resolve (fn [inputs ctx] -> scope|nil)}`
  or the whole-db fn sugar. Implementation ships in `day8/re-frame2-resources`;
  require `re-frame.resources` at boot. See
  `re-frame.core-resources/reg-resource-scope` and spec/API.md §Resources."}
       reg-resource-scope rf-resources/reg-resource-scope)
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
       reg-app-schemas rf-schemas/reg-app-schemas)))
;; `reg-machine*` (the plain-fn machine-registration surface) is NO LONGER a
;; `re-frame.core` façade export (rf2-wad2fl — front-porch shrink); reach it
;; through `re-frame.machines/reg-machine*`. The `reg-machine` / `defmachine`
;; MACROS stay on the façade (per-element source-coord stamping).

;; ---- EP-0018 retired public names — facade-exported throwing stubs --------
;;
;; `reg-event-db` / `reg-event-fx` are REMOVED and public `reg-event-ctx` is
;; demoted to a framework-internal primitive (EP-0018 §2/§3 + EP-0007 rule 2).
;; They survive on the façade ONLY as `^:no-doc` aliases to the
;; `re-frame.events` throwing stubs, so a stale `(rf/reg-event-db …)` call site
;; resolves to a real var and fails LOUDLY with an actionable hard error naming
;; the replacement (`:rf.error/reg-event-db-removed` / `-fx-removed` names
;; `reg-event`; `-ctx-removed` names `reg-interceptor`) — NOT an opaque "no such
;; var", NOT a working alias. They are plain `def` aliases (no macro / no
;; source-coord capture — they register nothing), platform-neutral so the stub
;; throws identically on JVM and CLJS. `^:no-doc` drops them from the API
;; manifest generator + the CLJS publics probe: they carry no manifest row and
;; are not part of the documented public surface. See spec/001-Registration.md
;; §The retired event-registration names + docs/api/15-removed.md.
(def ^{:no-doc true
       :doc "REMOVED in EP-0018 (no alias). Calling `reg-event-db` raises
  `:rf.error/reg-event-db-removed`, naming `reg-event` as the replacement.
  See `re-frame.events/reg-event-db` and spec/001-Registration.md §The
  retired event-registration names."}
  reg-event-db  events/reg-event-db)
(def ^{:no-doc true
       :doc "REMOVED in EP-0018 (no alias). Calling `reg-event-fx` raises
  `:rf.error/reg-event-fx-removed`, naming `reg-event` (the identical shape
  under the bare name). See `re-frame.events/reg-event-fx` and
  spec/001-Registration.md §The retired event-registration names."}
  reg-event-fx  events/reg-event-fx)
(def ^{:no-doc true
       :doc "DEMOTED to framework-internal in EP-0018. Calling public
  `reg-event-ctx` raises `:rf.error/reg-event-ctx-removed`, naming
  `reg-interceptor` as the public replacement for application full-context
  work (register the interceptor by id, then reference it from a `reg-event`
  registration's `:interceptors` chain; `->interceptor` is internal-only
  post-EP-0022). See `re-frame.events/reg-event-ctx` and
  spec/001-Registration.md §The retired event-registration names."}
  reg-event-ctx events/reg-event-ctx)

;; ---- reg-* macros (JVM-only; CLJS sees them via :require-macros) --------
;;
;; Each `defreg-macro` form below expands to a `defmacro` IN THIS ns
;; — so `rf/reg-event` resolves alias-qualified per Clojure's
;; `ns-alias/Var` lookup. The expansion captures source-coords at the
;; user's call site and splices args through to the fully-qualified
;; delegate fn.

#?(:clj
   (do
     (rm/defreg-event-macro reg-event events/reg-event
       "Register a `(fn [coeffects event-vec] effect-map)` event handler
       under `id` — the ONE public event-registration form (EP-0018):
       coeffects in, a closed effects map out. The effect-map is a closed
       shape — `#{:db :rf.db/runtime :fx}` at the top level; app handlers
       return only `:db` / `:fx`, while `:rf.db/runtime` (the runtime-db
       partition) is reserved by convention for framework /
       runtime-extension authority. Coeffects are declared uniformly via
       `:rf.cofx/requires`. Full-context work is expressed with an
       interceptor authored via `reg-interceptor` and referenced by id from
       this registration's `:interceptors` chain (`->interceptor` is the
       internal lowering constructor post-EP-0022, not the authoring form).
       Captures source-coords (Spec 001) at this call site. Additionally
       captures the whole `(reg-event :id ...)`
       form as a string under the handler's `:rf.handler/source` meta
       (Spec 009, rf2-xgfuy) — DEBUG-gated, elided in CLJS `:advanced` +
       `goog.DEBUG=false` production builds. See
       `re-frame.events/reg-event` for the full signature.")

     (rm/defreg-macro reg-sub subs/reg-sub
       "Register a subscription under `id`. Every sub is one of three
       input-fn producers: `:db` (layer-1 — reads `app-db` directly, no
       producer), `:static` (the literal `:<-` producer — a fixed list
       of input query-vectors), or `:parametric` (an `input-fn` producer
       — a pure `query-v -> vector-of-query-vectors` fn computing the
       inputs per concrete query). Captures source-coords (Spec 001) at
       this call site. See `re-frame.subs/reg-sub` for the full
       signature, Spec 006 §Subscription input producers, and
       spec/API.md §`reg-sub` input-production modes.")

     (rm/defreg-macro reg-fx fx/reg-fx
       "Register an effect handler under `id`. Handler signature is
       `(fn [ctx args] ...)`; runs when a `reg-event` handler returns an
       effect-map carrying `[id args]` inside its `:fx` vector.
       Captures source-coords (Spec 001) at this call site. See
       `re-frame.fx/reg-fx` for the full signature.")

     (rm/defreg-macro reg-cofx cofx/reg-cofx
       "Register a coeffect SUPPLIER under `id` — a value-returning fn
       whose result is delivered FLAT into a declaring handler's
       `:coeffects` map (the handler declares `:rf.cofx/requires [id]`).
       Captures source-coords (Spec 001) at this call site. See
       `re-frame.cofx/reg-cofx` for the grades (`:recordable?` /
       `:provided?`) and full signature.")

     (rm/defreg-macro reg-interceptor icpt-reg/reg-interceptor*
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

     (rm/defreg-macro reg-resource rf-resources/reg-resource
       "Register a resource under `resource-id` — a named, cached read of
       remote/external state. `resource-spec` carries the REQUIRED
       fail-closed `:scope` policy (`:rf.scope/global` | resolver |
       `:rf.scope/from-caller`), `:params-schema`, `:request`, and
       optional `:data-schema` / `:stale-after-ms` / `:gc-after-ms` /
       `:tags`. Captures source-coords (Spec 001) at this call site.
       Implementation ships in `day8/re-frame2-resources` (rf2-p10npe);
       apps must add the artefact and require `re-frame.resources` at
       boot. See `re-frame.core-resources/reg-resource` for the full
       signature."
       {:arglists '([resource-id resource-spec])})

     (rm/defreg-macro reg-mutation rf-resources/reg-mutation
       "Register a mutation under `mutation-id` — a named, causal WRITE to
       remote state that, on success, invalidates / patches / populates
       cached resource reads (run with `[:rf.mutation/execute …]`).
       `mutation-spec` carries the REQUIRED `:request` (a Spec 014
       managed-HTTP args map) and `:params-schema`, plus optional
       `:invalidates` / `:patches` / `:populates` / `:scope` /
       `:invalidate-timing` / `:retry`. Captures source-coords (Spec 001)
       at this call site. Implementation ships in `day8/re-frame2-resources`
       (rf2-dwme29); apps must add the artefact and require
       `re-frame.resources` at boot. See
       `re-frame.core-resources/reg-mutation` for the full signature."
       {:arglists '([mutation-id mutation-spec])})

     (rm/defreg-macro reg-resource-scope rf-resources/reg-resource-scope
       "Register a PURE named scope resolver under `scope-id` (EP-0016 D3) —
       the one scope-resolution currency reused by resource registration,
       route resources, ensure / subscriptions, invalidation descriptors, and
       clear-scope. `resolver` is the declared-inputs map `{:inputs {name
       [:db <rf-path>]} :resolve (fn [inputs ctx] -> scope|nil)}` or the
       whole-db fn sugar. The shipped input source is `[:db <rf-path>]`;
       `[:runtime …]` is reserved. A nil resolve result is FAIL-CLOSED.
       Referenced via `{:from-db <scope-id>}`. Captures source-coords (Spec
       001) at this call site. Implementation ships in
       `day8/re-frame2-resources` (rf2-hls77w); apps must add the artefact and
       require `re-frame.resources` at boot. See
       `re-frame.core-resources/reg-resource-scope` for the full signature."
       {:arglists '([scope-id resolver])})

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
       "Register an HTTP interceptor on a frame's `:rf.http/managed`
       middleware chain. Captures source-coords (Spec 001) at this call
       site. Implementation ships in `day8/re-frame2-http` (Spec 014
       §Middleware). Signature: `(reg-http-interceptor id
       interceptor-map)` — per rf2-uheqq the surface mirrors the
       event-interceptor `{:id :before :after}` shape: a single map
       carrying at least one of `:before (fn [ctx] ctx')` or
       `:after (fn [ctx response] response')`, plus an optional
       `:frame` (the EP-0002 *override*) and any
       `:rf/registration-metadata` slots. Absent an explicit `:frame`
       the carried-invariant scope chain resolves the target; under no
       scope the call raises `:rf.error/no-frame-context` (no
       `:rf/default` is synthesised)."
       {:arglists '([id interceptor-map])})))

;; ---- reg-machine (bespoke — per-element coord stamping) -----------------

#?(:clj
   (defmacro reg-machine
     "Register a machine as an event handler. Captures source-coords
     (Spec 001) at this call site plus co-located per-element source on
     each `:guards` / `:actions` / `:on-spawn-action` entry (`{:fn ..
     :source-coords .. :source-code ..}`) and a reference-site
     `:source-coords` co-located onto each `:states`-tree map node
     (state-node / transition map) at its spec-path (Spec 005 §Source-coord
     stamping; the `:source-*` slots are dev-only — DCE'd under
     `goog.DEBUG=false`). Implementation ships in `day8/re-frame2-machines`
     (rf2-xbtj / rf2-npvsx / rf2-vqja2). For runtime registration use
     `reg-machine*`.

     Per rf2-wgmipl an optional `opts` registration-metadata map may precede
     the spec — `(reg-machine machine-id opts machine)`. Its `:schema` key
     validates the dispatched OUTER event vector at the `:where :event`
     boundary, so a machine that needs BOTH a live `:data-schema` AND an
     event-vector schema (the login / realworld auth shape) is expressible
     through this blessed surface — no hand-stamped `reg-event` +
     `make-machine-handler` composition. The framework-owned `:rf/machine?` /
     `:rf/machine` keys are stamped by the registration home and MUST NOT
     appear in `opts`."
     ([machine-id machine]
      (rm/expand-reg-machine (meta &form)
                             (symbol (str (ns-name *ns*)))
                             *file*
                             machine-id
                             machine))
     ([machine-id opts machine]
      (rm/expand-reg-machine (meta &form)
                             (symbol (str (ns-name *ns*)))
                             *file*
                             machine-id
                             machine
                             opts))))

;; ---- defmachine (value-registered per-element source capture; rf2-gwj8l) -
;;
;; The common app shape is `(def door-machine {…}) … (reg-machine :door/main
;; door-machine)` — `reg-machine` sees only the `door-machine` symbol, so its
;; compile-time literal-walk captures nothing (the spec form is not a map
;; literal). `defmachine` is the `def`-replacement that walks the inline
;; literal AT THE DEFINITION SITE and co-locates per-element source onto each
;; `:guards` / `:actions` / `:on-spawn-action` entry (plus a reference-site
;; `:source-coords` on each `:states`-tree map node), so the per-element
;; source travels WITH the value into `reg-machine`. Per Spec 005
;; §Source-coord stamping (value-registered machines; rf2-npvsx / rf2-vqja2).

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
     `:guards` / `:actions` / `:on-spawn-actions` entry, plus a
     reference-site `:source-coords` onto each `:states`-tree map node
     (state-node / transition map; rf2-npvsx / rf2-vqja2) of the def'd
     value. When that value is later passed to `reg-machine`, the source is
     already present on the stamped spec, so `(rf/handler-meta :machine-guard
     [machine-id guard-id])` (and the Epoch machine-cascade source rendering)
     light up for value-registered machines exactly as for inline ones
     (rf2-gwj8l) — the source is derived from the `:event` registration spec
     (rf2-ftrcv), no registrar side-table involved. The dev-only `:source-*`
     slots DCE under `:advanced + goog.DEBUG=false`.

     Use `defmachine` for the `def`-then-register shape; use the
     `reg-machine` macro directly when registering an inline literal."
     [name & body]
     (let [[doc-or-spec spec-or-nil] body]
       (rm/expand-defmachine (symbol (str (ns-name *ns*)))
                             *file*
                             name
                             doc-or-spec
                             spec-or-nil))))

;; ---- public helpers re-exported for test access -------------------------
;;
;; Tests reach `re-frame.core/expand-reg-view` and
;; `re-frame.core/parse-reg-view-args` directly; the helpers themselves
;; live in `re-frame.core-reg-view-macro` (JVM-only — used at macro-
;; expansion time).

#?(:clj
   (do
     (def ^{:no-doc true
            :doc "JVM-only macro-helper re-exposed for tests that reach
  `re-frame.core/expand-reg-view` directly. Not part of the public
  surface — see `re-frame.core-reg-view-macro/expand-reg-view`."}
       expand-reg-view             rvm/expand-reg-view)
     (def ^{:no-doc true
            :doc "JVM-only macro-helper re-exposed for tests that reach
  `re-frame.core/parse-reg-view-args` directly. Not part of the public
  surface — see `re-frame.core-reg-view-macro/parse-reg-view-args`."}
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
  tools (Xray, MCP). Per Spec 011 §Head/meta contract.
  Implementation ships in `day8/re-frame2-ssr`. Late-bound via
  `:ssr/head-snapshot`."}
  head-snapshot    rf-ssr/head-snapshot)

;; ---- images (EP-0023) ----------------------------------------------------

(def ^{:doc "Construct an IMAGE value — the selected registration-set value a
  frame resolves against (EP-0023 §Image, §Public API). `rf/image` is the
  public constructor; `spec` is a map carrying `:id` (optional), `:include-ns`
  (a vector of namespace-glob strings selecting registered descriptors by
  their `:rf.provenance/ns`), `:registrations` (inline registrar-keyed
  sections), `:rf.image/requires` (the `:rf.capability/*` set), and the
  declared-winner maps `:replace` / `:replace-standard`. Returns a normalized,
  INERT image value — PURE: no realm, no registrar, no side effect (an image
  is data, not registration). Supplied to `make-frame` / `reg-frame` via the
  `:images` vector. See `re-frame.image/image`."}
  image    image/image)

;; ---- EP-0013 -> EP-0023 migration shims + diagnostics (rf2-32siq3.11) -----
;;
;; EP-0023 moves the PUBLIC model to `image -> frame -> event stream` while
;; RETAINING EP-0013's realm machinery as an internal substrate (EP-0023
;; §Backwards Compatibility). These surfaces help a codebase migrate off the
;; superseded EP-0013 public names (`rf/app`, `rf/module`, the realm-targeted
;; install/query surfaces, the `(realm, frame)` address) without a silent break:
;; the migration map + `explain` are inspectable guidance, the two `assert-*`
;; forms are fail-loud diagnostics. See `re-frame.migration`.

(def ^{:doc "The EP-0013 -> EP-0023 surface dispositions as inspectable data —
  `{surface-kw {:status … :replacement … :guidance …}}` (EP-0023 §Backwards
  Compatibility). One entry per superseded / re-expressed / retained-internal
  EP-0013 public name: `:rf/app`, `:rf/module`, `:rf/install!`, `:rf/reinstall!`,
  `:rf/installed-app`, `:rf/realm`, the `(realm, frame)` address
  (`:rf.realm/frame-address`), and the realm-scoped registrar queries
  (`:rf.realm/scoped-query`). The public model is `image -> frame -> event
  stream`; the realm substrate is retained internally where it still earns its
  keep. See `re-frame.migration/migration-map`."}
  migration-map     migration/migration-map)

(def ^{:doc "Return the one-line EP-0013 -> EP-0023 migration guidance string
  for an EP-0013 public-name keyword (`:rf/app`, `:rf/module`, `:rf/install!`,
  `:rf/realm`, `:rf.realm/frame-address`, `:rf.realm/scoped-query`), or nil for
  an unknown surface (EP-0023 §Backwards Compatibility). Reads `migration-map`.
  See `re-frame.migration/explain`."}
  migration-explain migration/explain)

(def ^{:doc "Fail loud with `:rf.error/cross-realm-frame-id` when a frame id is
  already live in a realm OTHER than the target — the EP-0013 -> EP-0023
  duplicate-frame-id migration break (EP-0023 §Id Spaces). EP-0013's
  `(realm, frame)` addressing let the same public frame id coexist in two
  realms; the EP-0023 public model has ONE process-local frame-id space, so a
  caller adopting the EP-0023 frame-id contract calls this to surface — with an
  actionable error naming the conflicting realms + the fix (distinct ids, or a
  direct frame object in local scope) — a reuse the new model forbids. Returns
  the frame id when there is no cross-realm collision. A CALLER-INVOKED
  diagnostic: it does NOT mutate the shipped `reg-frame` path (which legitimately
  allows the same id across realms for the retained internal substrate). See
  `re-frame.migration/assert-process-local-frame-id!`."}
  assert-process-local-frame-id! migration/assert-process-local-frame-id!)

;; ---- frame management ----------------------------------------------------

;; EP-0023 collapse FINALE (rf2-32siq3.48): `rf/make-frame` is REPOINTED onto the
;; runnable OBJECT constructor (`re-frame.live-frame/make-frame`). The migration
;; slices (rf2-32siq3.45/.46/.47) moved every record caller off the
;; keyword-returning contract first, so the flip is non-breaking for the public
;; surface; the facade exports exactly one make-frame, the object one. The
;; EP-0013 RECORD constructor survives, demoted to the advanced
;; `re-frame.frame/make-frame` (gensym id + the record-config surface —
;; `:on-create` / `:fx-overrides` / `:platform` / `:ssr` / `:doc` / `:preset` /
;; `:tags` / …); it is no longer facade-exported.
;;
;; Record-config keys (the rf2-32siq3.45 finding — NEVER silent-drop): the
;; object constructor honours only the EP-0023 frame-creation opts
;; (`:images` / `:id` / `:initial-db` / `:capabilities` / `:adapter`). A
;; record-only config key (e.g. `:on-create`, `:preset`, `:fx-overrides`) would
;; be SILENTLY DROPPED by the object constructor, so the facade FAILS LOUD on any
;; opt outside the EP-0023 set rather than accepting a config that does nothing —
;; the diagnostic names the advanced `re-frame.frame/make-frame` for the record
;; surface. This is the option-(b) disposition (fail-loud + redirect) per the
;; bead: option-(a) (extend the object constructor to honour record-config keys)
;; would require editing `re-frame.live-frame`, owned by a concurrent slice.
(def ^{:private true
       :doc "The EP-0023 frame-creation opt keys `rf/make-frame` (the object
  constructor) accepts. Any other key in the opts map is a record-only config
  key — fail loud + redirect to `re-frame.frame/make-frame`."}
  make-frame-opt-keys
  #{:images :id :initial-db :capabilities :adapter})

(defn- assert-make-frame-opts!
  "Fail loud (`:rf.error/make-frame-record-only-key`) when `opts` carries a key
  outside the EP-0023 object-constructor set (`make-frame-opt-keys`). The
  record-config keys (`:on-create` / `:fx-overrides` / `:platform` / `:ssr` /
  `:doc` / `:preset` / `:tags` / …) are honoured ONLY by the advanced EP-0013
  record constructor `re-frame.frame/make-frame`; the object constructor would
  silently drop them, so the facade rejects them rather than accept a config
  that does nothing (the rf2-32siq3.45 never-silent-drop finding). Returns
  `opts` unchanged when every key is an EP-0023 opt."
  [opts]
  (let [extra (when (map? opts)
                (not-empty (into #{} (remove make-frame-opt-keys) (keys opts))))]
    (when extra
      (error/throw-error!
        :rf.error/make-frame-record-only-key
        'rf/make-frame
        (str "rf/make-frame: opt key(s) " (pr-str extra) " are not EP-0023 "
             "make-frame opts. rf/make-frame is the EP-0023 OBJECT constructor — "
             "it accepts only " (pr-str make-frame-opt-keys) " and returns the "
             "live frame object. The record-config surface (:on-create, "
             ":fx-overrides, :platform, :ssr, :doc, :preset, :tags, …) lives on "
             "the advanced EP-0013 record constructor re-frame.frame/make-frame. "
             "Seed frame state with :initial-db; reach the record surface via "
             "re-frame.frame/make-frame if you genuinely need it.")
        {:recovery :use-an-ep-0023-opt-or-the-advanced-record-constructor
         :extra    {:offending-keys extra
                    :ep-0023-opts   make-frame-opt-keys}})))
  opts)

(defn make-frame
  "Create a live frame from one or more IMAGES and return the live frame OBJECT
  (EP-0023 §Public API — \"`rf/make-frame` returns the live frame object in all
  cases\"). The returned object is fully runnable: `dispatch` / `subscribe` /
  `destroy-frame!` / `app-db-value` accept it (or its `:id`). Per Spec 002
  §Per-instance frames + EP-0023 §Public API.

  `opts` is a map; the EP-0023 opt keys are:

    :images        a VECTOR of image values (always a vector, even for a single
                   image). Resolved into one sealed image generation; a
                   non-vector fails loud (`:rf.error/make-frame-bad-images`).
                   Optional — absent/`[]` runs the DEFAULT IMAGE.
    :id            the frame id (optional). When supplied, the object is
                   registered in the PROCESS-LOCAL live-frame registry under this
                   id (a duplicate live id fails loud). When ABSENT, the frame is
                   LOCAL-ONLY — keep the returned object and pass it to
                   dispatch / subscribe / test helpers.
    :initial-db    the frame's initial app-db value (optional). Seeds the frame's
                   app-db partition so an immediate subscribe/read observes it.
                   Seed frame STATE here — image is a behaviour concern.
    :capabilities  the host capability map the image's `:rf.image/requires` is
                   checked against (optional, fail-loud on a missing capability).
    :adapter       the active-substrate adapter binding/configuration (optional).

  RECORD-CONFIG KEYS FAIL LOUD: this is the EP-0023 OBJECT constructor — it
  accepts ONLY the keys above. A record-only config key (`:on-create`,
  `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`, …) would be
  silently dropped, so it is rejected `:rf.error/make-frame-record-only-key`
  (the rf2-32siq3.45 never-silent-drop finding). Seed frame state with
  `:initial-db`; reach the advanced EP-0013 record constructor (gensym id +
  record-config surface) via `re-frame.frame/make-frame` directly if you need it.

  EP-0023 collapse FINALE (rf2-32siq3.48): the facade repoint. Earlier slices
  migrated every record caller off the keyword-returning contract; this flips
  `rf/make-frame` onto the object constructor and retires the dual-export guard.

  Two arities mirror `re-frame.live-frame/make-frame`:
    (make-frame opts)             — resolve `:images` against the LIVE source store.
    (make-frame opts descriptors) — resolve against an explicit descriptor pool
                                    (tests / harnesses / a pre-snapshotted store)."
  {:arglists '([opts] [opts descriptors])}
  ([opts]             (live-frame/make-frame (assert-make-frame-opts! opts)))
  ([opts descriptors] (live-frame/make-frame (assert-make-frame-opts! opts) descriptors)))

(def ^{:doc "Hot-reload ONE frame's whole image composition, PRESERVING FRAME
  MEMORY (EP-0023 §Hot Reload / §Public API). `target` is EITHER a frame id
  (looked up in the process-local live-frame registry) OR a direct live frame
  OBJECT (`re-frame.live-frame/make-frame`'s return value); `opts` takes the same
  `:images` VECTOR shape as the object constructor. Reload is composition-
  REPLACING (it replaces the whole `:images` vector, not one member) and frame-
  targeted: it re-assembles `:images` into a fresh sealed generation
  (capability-checked against the frame's own `:rf.frame/capabilities`) and SWAPS
  only `:rf.frame/generation` onto the frame — app-db, runtime-db, caches,
  lifecycle, and every other frame slot continue unchanged (\"hot reload must not
  be implemented by tearing down and recreating the frame\"). It does NOT move
  sibling frames that previously shared the generation. Returns the reload REPORT
  `{:rf.frame/frame <reloaded object> :rf.reload/diff {:added … :changed …
  :removed … :retained …}}`. For an `:id`-bearing frame the registry slot is
  updated in place. A NEW export (non-breaking); the EP-0023 hot-reload public
  path. See `re-frame.live-frame/reload-images!`."}
  reload-images! live-frame/reload-images!)

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

;; ---- flows / schemas — façade boundary (rf2-wad2fl) ----------------------
;;
;; The flows + schemas QUERY / LIFECYCLE / VALIDATOR-INSTALL helpers are NO
;; LONGER re-exported from `re-frame.core` (rf2-wad2fl — front-porch shrink).
;; They are optional-feature surfaces whose owned namespace is the better
;; public home: reach them through `re-frame.flows` (`clear-flow`) and
;; `re-frame.schemas` (`app-schema-at`, `app-schema-meta-at`, `app-schemas`,
;; `app-schemas-digest`, `set-schema-validator!`, `set-schema-explainer!`,
;; `set-schema-printer!`, `set-schema-fns!`) — the owned namespaces already
;; publish them. The `reg-flow` / `reg-app-schema` / `reg-app-schemas`
;; REGISTRATION MACROS stay on the façade (above): they capture call-site
;; source-coords and have no owned-namespace macro form, so registration
;; stays easy to reach per the bead's "registration must stay central" rule.

;; ---- data classification (Spec 015) -------------------------------------
;;
;; EP-0015 (frame-owned egress policy, accepted 2026-06-11). The public
;; classification boundary is now (a) frame-owned `:sensitive` / `:large`
;; classification declared on `reg-frame` / `make-frame`, plus (b)
;; `project-egress` and the six `:rf.egress/*` profiles at trust
;; boundaries. The imperative `add-marks` / `set-marks` path-marks API is
;; NO LONGER part of the public `re-frame.core` façade (EP-0015 §3 + bead
;; plan item 2). The underlying `re-frame.marks/add-marks` /
;; `re-frame.marks/set-marks` fns remain as internal / test / generated-
;; code helpers (the conformance corpus and the marks unit tests exercise
;; them via their home namespace), but they are not the normal authoring
;; surface — declare durable app-db classification on the frame instead.

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
  and clear the cache. Disposal is synchronous and unconditional. For
  tests and hot-reload. Per spec/API.md §Clearing registrations."}
  clear-sub-cache! subs-cache/clear-sub-cache!)

;; ---- dispatch and subscribe ----------------------------------------------
;;
;; Each surface ships as a macro + `*`-fn pair. The macros expand to
;; `re-frame.core/dispatch*` / `subscribe*` etc., so those defs must
;; live here.

;; EP-0023 collapse slice 2 (rf2-32siq3.32): the `*`-fns gain a frame-FIRST
;; positional 2-arity — `(dispatch* frame event-vec)` — beside the established
;; event-first `(dispatch* event-vec opts)`, so the public macro forms
;; `(rf/dispatch-sync frame [...])` / `(rf/dispatch frame [...])` (EP-0023
;; §Public API) route the carried frame TARGET (a frame-id keyword OR a live
;; frame OBJECT) exactly like the 2-arity `(rf/subscribe frame [...])` already
;; does. The discriminator is the FIRST arg's shape: an event-vec is ALWAYS a
;; vector, a frame target NEVER is (a keyword id or a `:rf.frame/object`-marked
;; object map), so `vector?` on arg-1 cleanly separates the two 2-arg forms —
;; every existing `(dispatch* [..] opts)` caller (the frame-handle closures, the
;; macro expansion, programmatic HoF callers) stays byte-identical. The
;; frame-first form lowers to the established `{:frame target}` opt, which
;; `re-frame.router/build-envelope` normalizes through `frame/frame-target->id`
;; (object → runnable-id, keyword unchanged) — no new internal seam, the slice-1
;; wiring carries it. The optional THIRD positional arg is the call-site coord
;; the `dispatch` / `dispatch-sync` macro splices in under the OUTERMOST
;; debug-gate (so it DCEs in `:advanced` + `goog.DEBUG=false`); it is stamped
;; onto whichever opts map the discriminated form builds.

;; The discriminating bodies live in `-impl` fns, and `dispatch*` /
;; `dispatch-sync*` are `def` ALIASES to them. The alias-`def` (not a direct
;; `defn`) is DELIBERATE: it keeps the public `*`-fns RE-DEFINABLE under
;; `with-redefs` from the tools tests (Xray/Story stub `rf/dispatch*`). A direct
;; `defn` here would let the CLJS compiler attach inline fixed-arity metadata to
;; `re-frame.core/dispatch*`, so the macro call sites would emit a STATIC
;; `.cljs$core$IFn$_invoke$arity$N` dispatch that a `with-redefs`'d fn whose arity
;; set differs cannot satisfy ("arity$N is not a function"); the alias-`def`
;; preserves the same general-application indirection the previous `(def
;; dispatch* router/dispatch!)` cross-var alias gave.
;;
;; The `*`-fns are 1-or-2-arity ONLY (no call-site arity). The 2-arity
;; discriminates the frame-first `(dispatch* frame event-vec)` sugar from the
;; event-first `(dispatch* event-vec opts)` on the FIRST arg's shape (an event-vec
;; is ALWAYS a vector; a frame target — a keyword id or a `:rf.frame/object`
;; object map — never is), lowering frame-first to the established `{:frame …}`
;; opt. Call-site stamping is the MACRO's job (debug-gated, DCE'd in prod) and
;; reaches these via the 2-arity opts map, so NO `:rf.trace/call-site` literal
;; lives in these production-reachable bodies (the elision probe asserts the
;; keyword is ABSENT from the prod bundle).

(defn- dispatch*-impl
  ([event-vec] (router/dispatch! event-vec))
  ([a b]
   (if (vector? a)
     (router/dispatch! a b)
     (router/dispatch! b {:frame a}))))

(defn- dispatch-sync*-impl
  ([event-vec] (router/dispatch-sync! event-vec))
  ([a b]
   (if (vector? a)
     (router/dispatch-sync! a b)
     (router/dispatch-sync! b {:frame a}))))

(def ^{:doc "Fn-form of `dispatch` for HoF / programmatic dispatch — no
  call-site source-coord capture. Two 2-arity forms, discriminated by the
  FIRST arg's shape (EP-0023 §Public API, rf2-32siq3.32):

    (dispatch* event-vec)              — ambient frame (carried scope).
    (dispatch* event-vec opts)         — event-first; `opts` may carry
                                         `:frame` (a frame-id keyword OR a
                                         live frame object), plus the other
                                         dispatch opts.
    (dispatch* frame event-vec)        — frame-first; `frame` is a frame-id
                                         keyword OR a live frame OBJECT
                                         (`rf/make-frame`'s return value),
                                         lowered to `{:frame frame}`.

  An event-vec is always a VECTOR; a frame target never is — so `vector?`
  on the first arg separates the forms. Appends `event` to the target
  frame's router queue; returns nil. Per spec/API.md §Dispatch and
  subscribe (rf2-ts1a)."
       :arglists '([event-vec] [event-vec opts] [frame event-vec])}
  dispatch*       dispatch*-impl)

(def ^{:doc "Fn-form of `dispatch-sync` for HoF / programmatic sync dispatch — no
  call-site source-coord capture. Mirrors `dispatch*`'s forms, discriminated by
  the FIRST arg's shape (EP-0023 §Public API, rf2-32siq3.32):

    (dispatch-sync* event-vec)         — ambient frame.
    (dispatch-sync* event-vec opts)    — event-first; `opts` may carry `:frame`.
    (dispatch-sync* frame event-vec)   — frame-first; `frame` is a frame-id
                                         keyword OR a live frame OBJECT.

  An event-vec is always a VECTOR; a frame target never is. Processes `event`
  end-to-end synchronously, then drains to fixed point. For tests / REPL /
  bootstrap only. Per spec/API.md §Dispatch and subscribe (rf2-ts1a)."
       :arglists '([event-vec] [event-vec opts] [frame event-vec])}
  dispatch-sync*  dispatch-sync*-impl)

(def ^{:doc "One-shot read of a sub's current value — subscribes, derefs,
  then unsubscribes. Does NOT retain a cache reference. Use in handler
  bodies, machine actions, REPL — anywhere you need a value without a
  reactive subscription. Per spec/API.md §Dispatch and subscribe."}
  subscribe-once subs/subscribe-once)

(def ^{:doc "Decrement the ref-count on the cached subscription for
  `query-v`; ref-count → 0 disposes the entry **synchronously**
  (rf2-cmfln, per Spec 006 §Reference counting and disposal).
  Returns nil. Per spec/API.md §Dispatch and subscribe.

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

;; `inject-cofx` / `inject-cofx*` are NOT on the public facade (EP-0017,
;; rf2-w9xyx1). The interceptor idiom was removed; coeffect delivery is
;; declared with `:rf.cofx/requires`. The migration alarm survives as the
;; private hard-error thrower `re-frame.cofx/inject-cofx` (a stale call to
;; that namespace-internal var still raises `:rf.error/inject-cofx-removed`,
;; an always-on catalogue error naming `:rf.cofx/requires`), but a removed
;; surface no longer occupies the canonical public API — no facade var, no
;; api-manifest row. See spec/001-Registration.md §`inject-cofx` is removed
;; and docs/api/15-removed.md.

#?(:clj
   (defmacro dispatch
     "Enqueue `event-vec` on the target frame's router; returns nil
     immediately, BEFORE the handler runs. Captures call-site coords
     (rf2-ts1a) for error-trace attribution. For HoF / programmatic use
     call `dispatch*`. Per Spec 002 §Routing.

     Two public 2-arities, discriminated at runtime by the FIRST arg's
     shape (EP-0023 §Public API, rf2-32siq3.32):

       (dispatch event-vec)             ;; ambient frame (carried scope)
       (dispatch event-vec opts)        ;; event-first; `opts` may carry
                                        ;; `:frame` (a frame-id keyword OR a
                                        ;; live frame object) + other opts
       (dispatch frame event-vec)       ;; frame-first; `frame` is a frame-id
                                        ;; keyword OR a live frame OBJECT
                                        ;; (`rf/make-frame`'s return value)

     An `event-vec` is ALWAYS a vector and a frame target NEVER is, so the
     two 2-arg forms are unambiguous; the frame-first form mirrors the
     2-arity `(subscribe frame query-v)` and lowers to the `{:frame frame}`
     opt. Canonical `event-vec` shape (best practice — not enforced):
       [<event-id>]                   ;; trivial
       [<event-id> <single-scalar>]   ;; single-argument
       [<event-id> {<k> <v>}]         ;; multi-argument — single map payload
     Variadic `[<id> a b c]` is accepted by the runtime for v1-migration
     and caller convenience; the linter nudges new code toward the map
     form. See spec/Conventions.md §Canonical event-vector shape."
     {:arglists '([event-vec] [event-vec opts] [frame event-vec])}
     ([arg1]
      (csm/build-dispatch-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                               arg1 nil))
     ([arg1 arg2]
      (csm/build-dispatch-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                               arg1 arg2))))

#?(:clj
   (defmacro dispatch-sync
     "Run `event-vec` end-to-end synchronously; the router drains to
     fixed point. For tests / REPL / bootstrap only — never call from
     inside a running event handler (raises `:rf.error/dispatch-sync-
     in-handler`). Captures call-site coords (rf2-ts1a). For HoF /
     programmatic use call `dispatch-sync*`. Per Spec 002 §dispatch-sync.

     Two public 2-arities, discriminated at runtime by the FIRST arg's
     shape (EP-0023 §Public API, rf2-32siq3.32):

       (dispatch-sync event-vec)        ;; ambient frame
       (dispatch-sync event-vec opts)   ;; event-first; `opts` may carry `:frame`
       (dispatch-sync frame event-vec)  ;; frame-first; `frame` is a frame-id
                                        ;; keyword OR a live frame OBJECT

     The frame-first form is the `(rf/dispatch-sync frame [...])` shape a
     local test harness uses on a `rf/make-frame` object (EP-0023 §Public
     API). Canonical `event-vec` shape — see `dispatch` docstring above;
     same best-practice convention applies (id-first, with at most one
     trailing map; variadic tolerated, linter nudges)."
     {:arglists '([event-vec] [event-vec opts] [frame event-vec])}
     ([arg1]
      (csm/build-dispatch-sync-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                    arg1 nil))
     ([arg1 arg2]
      (csm/build-dispatch-sync-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                    arg1 arg2))))

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

;; (`inject-cofx` macro removed from the public facade — rf2-w9xyx1; see the
;; comment by `subscribe*` above.)

;; ---- frame-handle (the keystone) + frame-aware closures ------------------
;;
;; `current-frame-id` reads the carried-invariant scope/hold stamp via
;; `frame/require-current-frame!` (EP-0002): the dynamic `*current-frame*`
;; var or a React-context frame-provider scope, with NO `:rf/default`
;; floor — absence raises `:rf.error/no-frame-context`. `frame-handle` is
;; the keystone: a per-frame OPERATION BUNDLE (`{:frame :dispatch
;; :dispatch-sync :subscribe}`) captured at CREATION time, so its ops
;; survive async boundaries that unwind the dynamic-var / React-context
;; scope. The no-arg `frame-handle` / `frame-bound-fn*` capture forms
;; capture ONLY when a real scope exists at capture time.

(defn current-frame-id
  "Return the active frame id the in-effect scope carries — a keyword.
  Resolution is the carried-invariant scope/hold chain via
  `frame/require-current-frame!` (EP-0002): the dynamic `*current-frame*`
  stamp or a React-context frame-provider scope (CLJS only, via the
  `:adapter/current-frame` late-bind hook). There is NO `:rf/default`
  floor — called under no established scope it raises
  `:rf.error/no-frame-context` rather than reporting an invented default.
  This is the context READER form; it is `frame-scoped` and so requires a
  scope. Per Spec 002 §Resolver surface + §Reading the frame from React
  context."
  []
  (frame/require-current-frame! :current-frame-id
                                {:where 're-frame.core/current-frame-id}))

(defn make-frame-handle
  "Internal constructor for `frame-handle` (and the `reg-view` injection
  sugar). Not part of the public front-porch/back-room tiers — call
  `frame-handle` instead. It is a plain (technically public) Var rather
  than `defn-` ONLY so the `reg-view` macro's emitted body can reference
  it fully-qualified (a `defn-` private would fail the CLJ analyzer when
  the expansion compiles on the JVM); the precedent is `reg-view*` /
  `view`, which are likewise public plumbing.

  Build an OPERATION BUNDLE locked to `frame`:

    {:frame         frame
     :dispatch      (fn ([event] [event opts]))
     :dispatch-sync (fn ([event] [event opts]))
     :subscribe     (fn [query-v])}

  The captured `frame` is closed over by every op — no dynamic-var read
  at op-call time — so the bundle dispatches / subscribes into `frame`
  even when an op fires after the surrounding `with-frame` /
  `frame-provider` scope has unwound (the async-boundary case).

  Per the frame-affordance redesign (rf2-kkut0) the captured frame is
  AUTHORITATIVE: `:frame` is assoc'd LAST in the dispatch opts, so a
  per-call `:frame` in `opts` CANNOT override it — the handle is locked
  to one frame.

  `opts` (the second arg) supports the `reg-view` source-coord sugar:
    :dispatch-opts        base dispatch opts merged BELOW the captured
                          `:frame` (and below any per-call `opts`). The
                          `reg-view` macro injects
                          `{:source :ui :rf.trace/call-site <view-coord>}`
                          here so a view's on-click `#((:dispatch h) [...])`
                          classifies as `:source :ui` + carries the view's
                          call-site for Xray's dispatch 'go to code'.
    :subscribe-call-site  a source-coord stamped (under
                          `interop/debug-enabled?`) onto any error emitted
                          inside the synchronous subscribe miss path
                          (`:rf.error/no-such-sub`, `:rf.error/frame-
                          destroyed`). Mirrors the `subscribe` macro's
                          `trace/with-call-site` wrapper; subscriptions
                          carry no `:source` axis. DCEs in production."
  [frame {:keys [dispatch-opts subscribe-call-site]}]
  ;; EP-0013 step 4 (rf2-a15n62): CAPTURE the realm half of the (realm, frame)
  ;; address at handle-CREATION time, alongside the captured frame — the handle
  ;; is the capture-at-creation affordance for crossing async boundaries, so it
  ;; must pin BOTH dimensions of the address (a frame id alone is ambiguous once
  ;; the same id is legal in two realms). The ambient realm is `*current-realm*`
  ;; (set when the handle is created inside a realm-routed cascade / drain);
  ;; nil ⇒ the default realm ⇒ the byte-identical handle (no realm carried, no
  ;; `:realm` opt stamped). DERIVED from the carried address at creation, never
  ;; ambient at op-call time (EP-0002 — the whole point of the handle is to NOT
  ;; depend on op-time dynamic scope). The dispatch ops stamp `:realm` so the
  ;; envelope routes; the subscribe op rebinds `*current-realm*` (subscribe has
  ;; no `:realm` opt — it resolves the frame's realm from the carried var).
  (let [realm (frame/frame-realm frame)
        realm-opts (when (and realm
                              (not (= realm realm/default-realm-id)))
                     {:realm realm})]
    {:frame frame
     :dispatch
     (fn dispatch-fn
       ([event]      (dispatch* event (merge dispatch-opts realm-opts {:frame frame})))
       ([event opts] (dispatch* event (merge dispatch-opts opts realm-opts {:frame frame}))))
     :dispatch-sync
     (fn dispatch-sync-fn
       ([event]      (dispatch-sync* event (merge dispatch-opts realm-opts {:frame frame})))
       ([event opts] (dispatch-sync* event (merge dispatch-opts opts realm-opts {:frame frame}))))
     :subscribe
     (fn subscribe-fn
       [query-v]
       (frame/call-with-realm realm
         (fn []
           (if (and subscribe-call-site interop/debug-enabled?)
             (trace/with-call-site subscribe-call-site
               (subs/subscribe frame query-v))
             (subs/subscribe frame query-v)))))}))

(defn frame-handle
  "Return a per-frame OPERATION BUNDLE — the keystone affordance for
  carrying a frame into closures and across async boundaries. Per Spec
  002 §frame-handle and Spec 004 §Affordance for plain fns.

  Two arities:
    (frame-handle)            — capture the ambient frame
                                (`(current-frame-id)`) at CREATION time.
    (frame-handle frame-id)   — bundle locked to an explicit `frame-id`;
                                no surrounding `with-frame` / frame-
                                provider needed.

  Returns:

    {:frame         <id>
     :dispatch      (fn ([event] [event opts]))
     :dispatch-sync (fn ([event] [event opts]))
     :subscribe     (fn [query-v])}

  The frame is captured at CREATION; every op targets the captured
  frame and survives async — the bundle is the answer to \"ambient
  frame lookup does not survive `setTimeout` / `Promise.then` /
  WebSocket `onmessage` / observer callbacks\":

    (rf/reg-view StreamView [_]
      (let [{:keys [dispatch]} (rf/frame-handle)]   ;; captures render frame
        (ws/subscribe! (fn [msg] (dispatch [:ws/incoming msg])))
        [:div \"streaming…\"]))

  A per-call `:frame` in the dispatch opts MUST NOT override the
  captured frame — the handle is LOCKED to one frame. It is an
  OPERATION BUNDLE, not a container: read the frame's app-db value via
  `(rf/app-db-value (:frame handle))`, not the handle itself.

  EP-0002: the no-arg form captures the scope/hold stamp at CREATION
  time via `frame/require-current-frame!` — it captures ONLY when a real
  scope exists at capture time. Capturing outside any scope raises
  `:rf.error/no-frame-context`, never a captured `:rf/default` (per Spec
  002 §Resolver surface). Use the 1-arity `(frame-handle frame-id)` to
  lock a handle to a named frame from outside any scope (the right shape
  for async callbacks / tools / tests / SSR)."
  ([]         (make-frame-handle
                (frame/require-current-frame!
                  :frame-handle {:where 're-frame.core/frame-handle})
                nil))
  ([frame-id] (make-frame-handle frame-id nil)))

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
     `frame-handle` / `frame-bound-fn` / `frame-bound-fn*`. Per
     Spec 002 §with-frame."
     {:arglists '([frame-id body+])}
     [frame-id & body]
     (rvm/expand-with-frame frame-id body)))

#?(:clj
   (defmacro with-new-frame
     "Eval `expr`, bind the resulting frame to `sym`, run `body` with
     `*current-frame*` bound to it, and destroy the frame on exit
     (success or exception). The eval-bind-run-destroy form:

       (with-new-frame [sym (rf/make-frame opts)] body+)

     `expr` may be `(rf/make-frame opts)` (EP-0023 — returns the live
     frame OBJECT), `(rf/reg-frame :id opts)` (returns the keyword id),
     or any expression yielding a frame target (object or id); whatever
     is bound is destroyed on body exit (`dispatch` / `subscribe` /
     `destroy-frame!` accept either). Throws at compile time on a
     keyword argument — use `with-frame` to pin to an existing
     frame-id.

     For async closures that fire after body returns, capture via
     `frame-handle` / `frame-bound-fn` — the body's dynamic binding has
     unwound and `destroy-frame!` has already run by then. Per Spec 002
     §with-frame."
     {:arglists '([[sym expr] body+])}
     [bindings & body]
     (rvm/expand-with-new-frame bindings body)))

(defn frame-bound-fn*
  "Higher-order callback wrapper (the `*`-twin of the `frame-bound-fn`
  macro, matching `dispatch`/`dispatch*` and `subscribe`/`subscribe*`):
  take an existing fn `f` and return a new fn that re-establishes
  `*current-frame*` for `f`'s body. The captured frame value is closed
  over — no dynamic-var read at call time, so the wrapped fn dispatches
  into the captured frame even when it fires after the surrounding
  `with-frame` / `frame-provider` lexical scope has unwound.

  Two arities:
    (frame-bound-fn* f)            — capture `(current-frame-id)` at wrap time.
    (frame-bound-fn* frame-id f)   — explicit frame-id; no surrounding
                                     `with-frame` or frame-provider needed
                                     at wrap time.

  Use the `frame-bound-fn` MACRO when you want both `fn` syntax and
  frame-capture in one step; reach for `frame-bound-fn*` (this fn) when
  you already hold a fn value (HoF / programmatic wrap). For the common
  dispatch / subscribe case prefer `frame-handle` — `frame-bound-fn*`
  is the advanced surface for re-establishing the dynamic binding
  around an arbitrary fn body (e.g. one that itself calls
  `current-frame-id`).

  Use it when a callback is constructed in one synchronous moment (a
  render-fn, an event handler body, a module install! routine) but
  invoked LATER, across an async boundary that unwinds the
  `*current-frame*` dynamic binding:

    - `setTimeout` / `setInterval` callbacks
    - `Promise.then` / `js/await` continuations
    - `requestAnimationFrame` ticks
    - WebSocket / EventSource `onmessage` handlers
    - Worker `postMessage` handlers
    - IntersectionObserver / MutationObserver callbacks
    - Custom event subscribers, deferred fns, third-party callback APIs

  See also spec/006 §Lazy-seq deref tracking (Reagent adapter) for an
  adjacent but DIFFERENT bug class — \"view doesn't update on click\"
  that looks superficially like \"frame lost across React onClick\" but
  is actually a Reagent reactive-tracking failure (a lazy `(for ...)`
  in a `reg-view` body whose elements deref subscriptions must be
  realised with `doall` / `mapv` / `into` inside the render scope).
  Reach for `frame-bound-fn*` when you have a genuine async-boundary
  case; reach for `doall` when you have a reactive-tracking case. Per
  rf2-atqkg the two are not interchangeable.

  EP-0002: the 1-arity form captures the scope/hold stamp at WRAP time
  via `frame/require-current-frame!` — it captures ONLY when a real scope
  exists at wrap time. Wrapping outside any scope raises
  `:rf.error/no-frame-context`, never a captured `:rf/default` (per Spec
  002 §Resolver surface). Use the 2-arity `(frame-bound-fn* frame-id f)`
  to bind an explicit frame from outside any scope."
  ([f]
   (let [frame (frame/require-current-frame!
                 :frame-bound-fn* {:where 're-frame.core/frame-bound-fn*})]
     (fn [& args]
       (binding [frame/*current-frame* frame]
         (apply f args)))))
  ([frame-id f]
   (fn [& args]
     (binding [frame/*current-frame* frame-id]
       (apply f args)))))

#?(:clj
   (defmacro frame-bound-fn
     "Return a fn that captures the current frame and re-binds
     `*current-frame*` inside its body. The `fn`-syntax sugar over
     `frame-bound-fn*` — write the argv + body inline:

       (rf/frame-bound-fn [msg] (rf/dispatch [:ws/incoming msg]))

     is equivalent to

       (rf/frame-bound-fn* (fn [msg] (rf/dispatch [:ws/incoming msg])))

     The captured frame is closed over, so the returned fn dispatches
     into the captured frame even when it fires after the surrounding
     `with-frame` / `frame-provider` scope has unwound (the async-
     boundary case). For the common dispatch / subscribe case prefer
     `frame-handle`. Per Spec 002 §frame-bound-fn."
     [argv & body]
     (rvm/expand-frame-bound-fn argv body)))

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
  children]`. Children resolve `(current-frame-id)` to the provided frame
  unless a lexical `with-frame` or dynamic binding overrides. Per
  Spec 002 §Reading the frame from React context."}
         frame-provider views/frame-provider))

;; ---- routing helpers ------------------------------------------------------
;;
;; The routing URL-codec + lifecycle QUERY helpers `match-url` / `route-url`
;; / `current-url` / `clear-route` are NO LONGER re-exported from
;; `re-frame.core` (rf2-wad2fl — front-porch shrink). They are optional-
;; routing-feature reads whose owned namespace is the better public home:
;; reach them through `re-frame.routing` (which already publishes them). The
;; `reg-route` REGISTRATION MACRO stays on the façade (above; source-coord
;; capture, no owned-ns macro form). `route-link` (the view) and the
;; `install-history-listener!` / `remove-history-listener!` boot seams stay
;; on the façade for now — they have no classified owned-namespace peer
;; (the routing-ns forms are CLJS-only and unrowed).

(def ^{:doc "Registered view at `:route/link` — renders an `<a href=...>`
  from a route-id and intercepts plain primary-button clicks to dispatch
  `:rf/url-requested`. Modifier-key / middle-clicks defer to the browser.
  Shape: `[rf/route-link {:to :route-id :params {} :query {} :fragment
  \"\" & html-attrs} & children]`. Per Spec 012 §Linking from views."}
  route-link  rf-routing/route-link)

(def ^{:doc "Install a browser `popstate` listener that drives the
  URL-owning frame: Back/Forward dispatches `:rf.route/handle-url-change`
  to `(url-owner-frame-id)` (resolved at pop time), so the owner's
  route slice (at `[:rf.runtime/routing :current]`) and rendered body restore — whether the owner is
  `:rf/default` or a non-default `:url-bound? true` frame (rf2-6qgbs.4).
  Also syncs the initial URL on install. Idempotent (hot-reload safe).
  CLJS-only. The inbound counterpart of the outbound `:rf.nav/push-url`
  gate. Per Spec 012 §Multi-frame routing. Implementation ships in
  `day8/re-frame2-routing`."}
  install-history-listener!  rf-routing/install-history-listener!)

(def ^{:doc "Tear down the `popstate` listener installed by
  `install-history-listener!`. No-op when none is installed. CLJS-only.
  Per Spec 012 §Multi-frame routing. Implementation ships in
  `day8/re-frame2-routing`."}
  remove-history-listener!  rf-routing/remove-history-listener!)

;; ---- machine helpers ------------------------------------------------------
;;
;; The machine REGISTRATION (`reg-machine*` plain fn), HANDLER-BUILD
;; (`make-machine-handler`), pure-engine (`machine-transition`), and QUERY
;; (`machines`, `machine-meta`, `machine-by-system-id`) helpers are NO LONGER
;; re-exported from `re-frame.core` (rf2-wad2fl — front-porch shrink). They
;; are optional-machines-feature surfaces whose owned namespace is the better
;; public home: reach them through `re-frame.machines` (which already
;; publishes them). The `reg-machine` / `defmachine` REGISTRATION MACROS stay
;; on the façade (above; per-element source-coord stamping, no owned-ns macro
;; form). The `machine-has-tag?` subscription sugar stays on the façade —
;; it has no classified owned-namespace peer (64-example adoption, rf2-gkt25a).
;; The `dispatch-to-system` FN is DEMOTED off the façade (rf2-gkt25a /
;; rf2-80mmlf — exactly one in-repo caller): the canonical action-side
;; messaging surface is the reserved `[:rf.machine/dispatch-to-system
;; [system-id event]]` fx tuple; the direct-call FN now lives in
;; `re-frame.machines` as an implementation-tier helper. The `sub-machine`
;; snapshot sugar is REMOVED (rf2-wh7xip — zero adopters); the canonical
;; machine read is the `[:rf/machine machine-id]` subscription vector.

(def ^{:doc "Subscribe to a machine's `:fsm/tags` containment-bit for
  `tag`. Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`
  — returns a reaction whose value is `true` iff the current snapshot's
  `:tags` set contains `tag`. Per Spec 005 §State tags."}
  machine-has-tag?               rf-machines/machine-has-tag?)

;; ---- resource helpers (Spec 016) ------------------------------------------
;;
;; The optional resources artefact (`day8/re-frame2-resources`). `reg-resource`
;; is a macro (above, for source-coord capture) + a CLJS fn-alias; the
;; non-registration surface is plain re-exports below. Each delegates
;; through the late-bind table and throws `:rf.error/resources-artefact-missing`
;; when the artefact is absent. Per Spec 016 §Public API.

(def ^{:doc "Remove a registered resource (a registration-lifecycle
  operation — NOT cache invalidation; for data lifecycle use
  `:rf.resource/invalidate-tags` / `:rf.resource/remove` /
  `:rf.resource/clear-scope`). Per Spec 016 §Registration. Implementation
  ships in `day8/re-frame2-resources`."}
  clear-resource  rf-resources/clear-resource)

(def ^{:doc "Return the registered resource's spec map (`:params-schema`,
  `:data-schema`, `:request`, `:scope`, `:transport`, `:stale-after-ms`,
  `:gc-after-ms`, `:tags`, `:doc`), or `nil`. Per Spec 016 §Introspection.
  Implementation ships in `day8/re-frame2-resources`."}
  resource-meta   rf-resources/resource-meta)

(def ^{:doc "Return a resource instance's runtime state for an
  explicit-frame target `{:resource :scope :params :frame}`. Per EP-0002
  the frame is carried explicitly. Per Spec 016 §Introspection.
  Implementation ships in `day8/re-frame2-resources`."}
  resource-state  rf-resources/resource-state)

(def ^{:doc "Return resource introspection for a frame target `{:frame …}`
  — the registered resources and the live per-frame resource-instance
  table. Per Spec 016 §Introspection. Implementation ships in
  `day8/re-frame2-resources`."}
  resources       rf-resources/resources)

(def ^{:doc "Install host `window` focus / network-reconnect listeners that
  drive active-stale revalidation for `frame-id` — on window focus / tab
  return and network reconnect the listener dispatches
  `[:rf.resource/window-focused]` / `[:rf.resource/network-reconnected]` at
  the frame, whose handlers refetch the frame's active-owner STALE entries in
  the background (cause `:focus` / `:reconnect`). Idempotent (hot-reload
  safe). CLJS-only (JVM no-op). Cancelled on frame destroy. Per Spec 016
  §Deferred slices. Implementation ships in `day8/re-frame2-resources`."}
  install-revalidation-listeners! rf-resources/install-revalidation-listeners!)

(def ^{:doc "Tear down the `window` focus / online revalidation listeners
  installed by `install-revalidation-listeners!` for `frame-id`. No-op when
  none is installed (and on the JVM). CLJS-only. Per Spec 016 §Deferred
  slices. Implementation ships in `day8/re-frame2-resources`."}
  remove-revalidation-listeners! rf-resources/remove-revalidation-listeners!)

;; Mutations (rf2-dwme29, EP-0003 §Mutations — first public-beta gate).
;; `reg-mutation` is a macro (above, for source-coord capture) + a CLJS
;; fn-alias; the non-registration surface is plain re-exports below.

(def ^{:doc "Remove a registered mutation (a registration-lifecycle
  operation — NOT a form-error reset; for the causal runtime-instance reset
  use the `[:rf.mutation/clear …]` event). Per Spec 016 §Deferred slices /
  EP-0003 §Mutations. Implementation ships in `day8/re-frame2-resources`."}
  clear-mutation  rf-resources/clear-mutation)

(def ^{:doc "Return the registered mutation's spec map (`:request`,
  `:params-schema`, `:invalidates`, `:patches`, `:populates`, `:scope`,
  `:invalidate-timing`, `:transport`, `:doc`), or `nil`. Per EP-0003
  §Mutations. Implementation ships in `day8/re-frame2-resources`."}
  mutation-meta   rf-resources/mutation-meta)

(def ^{:doc "Return a mutation INSTANCE's durable runtime row (`{:status
  :result :error …}`) for an explicit-frame target `{:instance :frame}`, or
  `nil`. Per EP-0002 the frame is carried explicitly. Per EP-0003
  §Mutations. Implementation ships in `day8/re-frame2-resources`."}
  mutation-state  rf-resources/mutation-state)

(def ^{:doc "Return mutation introspection for a frame target `{:frame …}`
  — the registered mutation ids and the live per-frame mutation-instance
  table (keyed by instance id). Per EP-0003 §Mutations. Implementation ships
  in `day8/re-frame2-resources`."}
  mutations       rf-resources/mutations)

;; Named resource-scope resolvers (rf2-hls77w, EP-0016 D3). `reg-resource-scope`
;; is a macro (above, for source-coord capture) + a CLJS fn-alias; the
;; non-registration surface is plain re-exports below.

(def ^{:doc "Remove a registered resource-scope resolver (a
  registration-lifecycle removal — the `clear-` decrement counterpart of
  `reg-resource-scope`). Per Spec 016 §Named resource-scope resolvers.
  Implementation ships in `day8/re-frame2-resources`."}
  clear-resource-scope rf-resources/clear-resource-scope)

(def ^{:doc "Resolver helper: resolve the named resolver `scope-id` against
  the supplied `db` value, returning a canonical concrete scope or nil — a
  plain function over the resolver registry, NOT an effect (no app-state /
  dispatch side effects). It is not a pure data helper, though: like every
  resolution site it emits `:rf.resource/scope-resolved` dev-time trace
  evidence. Canonical use is the logout idiom (resolve the concrete old scope
  from the handler's coeffect db and pass it to `:rf.resource/clear-scope`
  concretely). Per Spec 016 §`clear-scope` resolves the concrete scope from
  the coeffect db (EP-0016 issue 7). Implementation ships in
  `day8/re-frame2-resources`."}
  resolve-resource-scope rf-resources/resolve-resource-scope)

;; ---- introspection (Spec 002 §The public registrar query API) -----------
;;
;; The registrar query workhorses. Each grows a REALM-TARGETED map-shaped form
;; alongside its existing process-global keyword arities (EP-0013 D1 stage 8,
;; rf2-blibek; open-issue 11 — map-shaped is the ruled public form, unambiguous
;; against the keyword arities and extensible). The map-shaped form reads ONLY
;; the specified realm's registrar (`realm-registrations` / `realm-handler-meta`
;; / `realm-handler-ids` over the realm's OWN `(kind, id) → metadata` atom), so
;; "realm-targeted registrar queries return only that realm's registrations"
;; (EP-0013 §Realm Conformance). The default-realm keyword arities are
;; BYTE-IDENTICAL — a single-realm caller never spells a realm (the absence-is-
;; default rule); only a caller that passes an explicit `{:realm …}` map reaches
;; the realm-scoped path.
;;
;; EP-0023 (rf2-wkw8na) — the FRAME-TARGETED map form. Each of the trio ALSO
;; grows a `{:frame f :kind k …}` form that reads the registrations resolved
;; through a live frame's OWN sealed image generation (EP-0023 §Frame-derived
;; live registration resolution — "target frame -> resolved image generation ->
;; registration resolution"), surfacing the `:rf.provenance/ns` + inline/image +
;; replacement/standard facts the resolved descriptors already carry. This is
;; the promised-but-unshipped READ of the EP-0023 model: the public tooling
;; surface over a frame's generation, for tools (Pair MCP, Xray) that EP-0023
;; forbids from consuming `re-frame.live-frame` / `re-frame.image-assembly`
;; internals. The thin, no-duplication path: resolve the frame target ONCE to a
;; live frame object, then run the EXISTING registrar reads inside
;; `re-frame.live-frame/call-with-frame-resolution`, which binds
;; `registrar/*generation*` so the registrar's ALREADY generation-aware
;; `lookup` / `registrations` / `ids` do the resolution — byte-identical
;; descriptor shape, no resolver-walk duplicated here. The realm/default paths
;; stay byte-identical: only a caller that passes `{:frame …}` reaches it.
;;
;; FAIL-LOUD (rf2-wkw8na, EP-0023 §Id Spaces — the address families are
;; distinct): a `:frame` that does not resolve to a live frame carrying a
;; generation throws (NO fallback to the default/realm registrar — the read
;; needs a live EP-0023 frame), and a map mixing `:frame` + `:realm` throws (a
;; silent priority rule would re-introduce the (realm, frame) ambiguity EP-0023
;; removes). `:frame` accepts a REGISTERED FRAME ID (keyword, looked up in the
;; process-local live-frame registry — the same path `reload-images!` uses) OR a
;; direct live frame OBJECT (`make-frame`'s return value).

(defn- resolve-live-frame-object
  "Resolve a `:frame` target to the LIVE frame OBJECT it names — verifying the
  object carries a sealed image generation — or FAIL LOUD
  (`:rf.error/frame-no-generation`). `frame-target` is a REGISTERED frame id
  (keyword, looked up in the process-local live-frame registry — the same path
  `rf/reload-images!` uses) OR a direct live frame OBJECT — the same target
  shapes `rf/reload-images!` accepts. NO fallback to the default/realm registrar:
  a frame-targeted read needs a live EP-0023 frame that carries a generation, so
  an id that names no live frame, a non-frame value, or a frame object with no
  `:rf.frame/generation` slot is an error naming the bad target. Returns the
  frame OBJECT (the caller passes it to `re-frame.live-frame/call-with-frame-
  resolution`, which binds its generation, or reads its generation directly) —
  the façade re-surfaces that BEHAVIOUR through the public reads without
  exporting the internal `re-frame.live-frame` names.

  `where-sym` is the public fn the caller invoked, used in the diagnostic so the
  message names the user-facing surface (`rf/registrations` / `rf/handler-meta`
  / `rf/handler-ids` / `rf/frame-generation`)."
  [frame-target where-sym]
  (let [frame-object (if (live-frame/frame-object? frame-target)
                       frame-target
                       (live-frame/live-frame frame-target))]
    (if (some? (live-frame/frame-resolution-generation frame-object))
      frame-object
      (error/throw-error!
        :rf.error/frame-no-generation
        where-sym
        (str where-sym ": :frame target " (pr-str frame-target) " does not "
             "resolve to a live frame carrying an image generation. A frame-"
             "targeted registrar read resolves the (kind, id) set through the "
             "target frame's OWN sealed image generation (EP-0023 §Frame-derived "
             "live registration resolution); it needs a LIVE EP-0023 frame — a "
             "frame id registered in the process-local live-frame registry, or a "
             "direct frame object returned by rf/make-frame — and does NOT fall "
             "back to the default/realm registrar. Live frame ids: "
             (pr-str (vec (live-frame/live-frame-ids))) ".")
        {:recovery :target-a-live-frame-id-or-a-direct-frame-object
         :extra    {:frame          frame-target
                    :live-frame-ids (vec (live-frame/live-frame-ids))}}))))

(defn- assert-not-frame-realm-mixed!
  "Fail loud (`:rf.error/frame-realm-mixed-address`) when a registrar-query map
  carries BOTH `:frame` and `:realm`. They are DISTINCT address families
  (EP-0023 §Id Spaces — `:rf.realm/frame-address`): `:realm` targets a realm's
  OWN registrar atom, `:frame` resolves through a live frame's sealed image
  generation. A silent priority rule would re-introduce the (realm, frame)
  ambiguity EP-0023 removes, so the façade rejects the mixed address rather than
  pick one. Returns `arg` unchanged when at most one of the two is present."
  [arg where-sym]
  (when (and (map? arg) (contains? arg :frame) (contains? arg :realm))
    (error/throw-error!
      :rf.error/frame-realm-mixed-address
      where-sym
      (str where-sym ": a registrar-query map carries BOTH :frame and :realm, "
           "but they are DISTINCT address families (EP-0023 §Id Spaces). :realm "
           "reads a realm's OWN registrar atom; :frame resolves through a live "
           "frame's sealed image generation. Pick ONE — a silent priority rule "
           "would re-introduce the (realm, frame) addressing ambiguity EP-0023 "
           "removes.")
      {:recovery :pass-frame-or-realm-not-both
       :extra    {:frame (:frame arg)
                  :realm (:realm arg)}}))
  arg)

(defn frame-generation
  "Return the SEALED, resolved image GENERATION a live frame is running — the
  inert image-assembly generation value it resolves `(kind, id)` lookups through
  (EP-0023 §Frame — \"a reference to the resolved image generation it is
  running\"). The dedicated raw READ over the EP-0023 frame->generation model,
  for tools (Pair MCP `describe-image`, Xray) that EP-0023 forbids from consuming
  `re-frame.live-frame` / `re-frame.image-assembly` internals directly.

  `frame-target` is a REGISTERED frame id (keyword, looked up in the process-
  local live-frame registry) OR a direct live frame OBJECT (`rf/make-frame`'s
  return value) — the same target shapes `rf/reload-images!` accepts.

  Returns the generation map with the four documented stable public keys (EP-0023
  §Specification Summary; `re-frame.image-assembly`):

    :rf.gen/resolver  {[kind id] descriptor, …}   the sealed [kind id] map
    :rf.gen/images    [<normalized image value> …]
    :rf.gen/requires  #{:rf.capability/* …}        union of image requires
    :rf.gen/kinds     #{kind …}                     kinds present, for tools

  FAILS LOUD (`:rf.error/frame-no-generation`) when `frame-target` does not
  resolve to a live frame carrying a generation: NO nil-as-default, NO fallback
  to the default/realm registrar — a frame-generation read needs a live EP-0023
  frame. Use the `:frame` arity of `registrations` / `handler-meta` /
  `handler-ids` for per-`(kind, id)` resolution WITH provenance; use this when a
  view needs the whole generation (selected registrations, capability set,
  replacement facts) without per-kind round-trips. Per Spec 002 §The public
  registrar query API; EP-0023 §Public API / Use-Case 7."
  [frame-target]
  (-> (resolve-live-frame-object frame-target 'rf/frame-generation)
      (live-frame/frame-resolution-generation)))

(defn registrations
  "Return all ids registered under `kind` with their metadata — the
  introspection workhorse used by tools, agents, and storybook resolution.
  Per Spec 002 §The public registrar query API.

  Arities:
    `(registrations kind)`        — the full `{id metadata}` for `kind` in the
                                     default realm (process-global registrar),
                                     `{}` if none.
    `(registrations kind pred-fn)` — same, filtered to entries whose metadata
                                     satisfies `pred-fn`.
    `(registrations {:realm r :kind k})` — REALM-TARGETED (EP-0013 stage 8):
                                     the `{id metadata}` for `:kind` in realm
                                     `:realm`'s OWN registrar — only THAT
                                     realm's registrations. `:realm` is a realm
                                     map, a realm-id keyword, or absent/nil for
                                     the default realm. An optional `:pred`
                                     filters by metadata as the positional
                                     `pred-fn` does.
    `(registrations {:frame f :kind k})` — FRAME-TARGETED (EP-0023, rf2-wkw8na):
                                     the `{id metadata}` for `:kind` resolved
                                     through live frame `:frame`'s OWN sealed
                                     image generation — only the ids that frame's
                                     image carries, with the `:rf.provenance/ns`
                                     + inline/image + replacement/standard facts
                                     the resolved descriptors carry. `:frame` is
                                     a REGISTERED frame id (keyword) OR a direct
                                     live frame OBJECT (the same target shapes
                                     `rf/reload-images!` accepts). An optional
                                     `:pred` filters by metadata as above. FAILS
                                     LOUD when `:frame` does not resolve to a live
                                     frame generation (`:rf.error/frame-no-
                                     generation` — NO default/realm fallback), or
                                     when the map ALSO carries `:realm`
                                     (`:rf.error/frame-realm-mixed-address` —
                                     distinct address families).

  The map-shaped form is unambiguous against the keyword arities (a `kind` is a
  keyword, never a map) — the byte-identical default-realm path is unchanged
  for every existing caller."
  ([arg]
   (cond
     (and (map? arg) (contains? arg :frame))
     (let [_     (assert-not-frame-realm-mixed! arg 'rf/registrations)
           frame (resolve-live-frame-object (:frame arg) 'rf/registrations)
           {:keys [kind pred]} arg]
       (live-frame/call-with-frame-resolution
         frame
         #(if pred
            (registrar/registrations kind pred)
            (registrar/registrations kind))))

     (map? arg)
     (let [{:keys [realm kind pred]} arg
           base (realm/realm-registrations realm kind)]
       (if pred
         (into {} (filter (fn [[_id meta]] (pred meta))) base)
         base))

     :else
     (registrar/registrations arg)))
  ([kind pred-fn]
   (registrar/registrations kind pred-fn)))

(defn handler-meta
  "Return the registration metadata map for `[kind id]`, or `nil`. The
  general source-meta surface tools (Xray Open-in-editor, re-frame-pair
  source-jump) read to find a (kind, id)'s definition. Per Spec 002 §The
  public registrar query API.

  Arities:
    `(handler-meta kind id)` — the default-realm (process-global) metadata for
      `[kind id]`. For the registrar kinds (`:event :sub :fx :cofx :interceptor
      :view :frame :route :head :error-projector :flow :resource`) this is
      `registrar/lookup`. For `:interceptor` the metadata carries the registered
      `:rf/interceptor-descriptor` plus source coords + `:doc` (EP-0022).
    `(handler-meta {:realm r :kind k :id id})` — REALM-TARGETED (EP-0013 stage
      8): the metadata for `[k id]` in realm `r`'s OWN registrar — only THAT
      realm's registration. `:realm` is a realm map, a realm-id keyword, or
      absent/nil for the default realm. (The machine kinds are derived from
      the default realm's machine specs, so the realm-targeted form is for the
      registrar kinds; a machine kind through the map form resolves against
      the default-realm derivation.)
    `(handler-meta {:frame f :kind k :id id})` — FRAME-TARGETED (EP-0023,
      rf2-wkw8na): the metadata for `[k id]` resolved through live frame `f`'s
      OWN sealed image generation (surfacing `:rf.provenance/ns` + inline/image +
      replacement/standard facts the resolved descriptor carries), or `nil` when
      that frame's image carries no such `[k id]`. `:frame` is a REGISTERED frame
      id (keyword) OR a direct live frame OBJECT (the same target shapes
      `rf/reload-images!` accepts). The frame form is for the REGISTRAR kinds
      (the machine kinds are not registrar kinds and are not in the generation
      resolver — a machine kind through the frame form is `nil`). FAILS LOUD when
      `:frame` does not resolve to a live frame generation
      (`:rf.error/frame-no-generation` — NO default/realm fallback) or when the
      map ALSO carries `:realm` (`:rf.error/frame-realm-mixed-address`).

  The two machine kinds `:machine-guard` / `:machine-action` are NOT
  registrar kinds (rf2-ftrcv, supersedes rf2-ypu5i / rf2-npvsx) — `id` is
  the 2-vector `[<machine-id> <guard-or-action-id>]`, and the dev-only
  fn-source meta (`:rf.handler/source` + coords) is DERIVED on demand from
  the machine's `:event` registration spec's co-located `:guards` /
  `:actions` entries (see `re-frame.core-machines/machine-handler-meta`).
  The addressing is uniform: callers read
  `(handler-meta :machine-guard [machine-id guard-id])` exactly as before.
  Production-elided per Spec 009 (the derivation returns nil when the
  `:source-*` slots are absent).

  The map-shaped form is unambiguous against the `(kind id)` arity (a `kind`
  is a keyword, never a map) — the byte-identical default-realm path is
  unchanged for every existing caller."
  ([arg]
   (if (contains? arg :frame)
     (let [_     (assert-not-frame-realm-mixed! arg 'rf/handler-meta)
           frame (resolve-live-frame-object (:frame arg) 'rf/handler-meta)
           {:keys [kind id]} arg]
       (live-frame/call-with-frame-resolution
         frame
         #(registrar/handler-meta kind id)))
     (let [{:keys [realm kind id]} arg]
       (case kind
         (:machine-guard :machine-action) (rf-machines/machine-handler-meta kind id)
         (realm/realm-handler-meta realm kind id)))))
  ([kind id]
   (case kind
     (:machine-guard :machine-action) (rf-machines/machine-handler-meta kind id)
     (registrar/handler-meta kind id))))

(defn handler-ids
  "Return the set of registered ids under `kind` (no metadata). Per Spec 002
  §The public registrar query API.

  Arities:
    `(handler-ids kind)` — the id set under `kind` in the default realm.
    `(handler-ids {:realm r :kind k})` — REALM-TARGETED (EP-0013 stage 8): the
      id set under `:kind` in realm `:realm`'s OWN registrar — only THAT
      realm's ids. `:realm` is a realm map, a realm-id keyword, or absent/nil
      for the default realm.
    `(handler-ids {:frame f :kind k})` — FRAME-TARGETED (EP-0023, rf2-wkw8na):
      the id set under `:kind` resolved through live frame `:frame`'s OWN sealed
      image generation — only the ids that frame's image carries. `:frame` is a
      REGISTERED frame id (keyword) OR a direct live frame OBJECT (the same
      target shapes `rf/reload-images!` accepts). FAILS LOUD when `:frame` does
      not resolve to a live frame generation (`:rf.error/frame-no-generation` —
      NO default/realm fallback) or when the map ALSO carries `:realm`
      (`:rf.error/frame-realm-mixed-address`).

  The map-shaped form is unambiguous against the keyword arity (a `kind` is a
  keyword, never a map) — the default-realm path is byte-identical."
  [arg]
  (cond
    (and (map? arg) (contains? arg :frame))
    (let [_     (assert-not-frame-realm-mixed! arg 'rf/handler-ids)
          frame (resolve-live-frame-object (:frame arg) 'rf/handler-ids)]
      (live-frame/call-with-frame-resolution
        frame
        #(registrar/ids (:kind arg))))

    (map? arg)
    (realm/realm-handler-ids (:realm arg) (:kind arg))

    :else
    (registrar/ids arg)))

(def ^{:doc "Return the set of registered, non-destroyed frame ids. Per
  Spec 002 §The public registrar query API."}
  frame-ids    frame/frame-ids)

(def ^{:doc "Return the effective metadata map for a frame as a flat
  shape — `:id` plus the post-preset-expansion user-supplied config.
  Per Spec 002 §The public registrar query API and Spec-Schemas
  §`:rf/frame-meta`."}
  frame-meta   frame/frame-meta)

;; EP-0013 -> EP-0023 supersession (rf2-pl97nd.2): the realm-family facade read
;; `rf/frame-realm` (the frame-side half of the retired `(realm, frame)`
;; addressing model) is REMOVED from the public facade. `re-frame.frame/frame-realm`
;; remains as internal substrate. The public model is `image -> frame -> event
;; stream`; a frame is addressed by its process-local frame id, with no realm
;; coordinate. See `(rf/migration-explain :rf.realm/frame-address)`.

(defn app-db-value
  "Return the current `app-db` VALUE (a plain map) for the named frame,
  or `nil` if not registered. Value-form accessor (no deref, no
  container) — pairs with `app-db-container` (the container accessor).
  Per Spec 002 §The public registrar query API.

  EP-0023 (rf2-32siq3.32): the argument may be a frame-id KEYWORD or a live
  frame OBJECT (`rf/make-frame`'s return value); an object is normalized to its
  runnable-id address via `frame/frame-target->id`."
  [frame-id]
  (frame/frame-app-db-value (frame/frame-target->id frame-id)))

(defn runtime-db-value
  "Return the current `runtime-db` partition VALUE for the named frame —
  the framework-owned subsystem state (the `:rf.runtime/*` children), or
  `nil` for an unknown / destroyed frame. The tool / privileged-runtime
  read of the framework partition.

  EP-0001 (rf2-q4i9ko + rf2-adwcv6): the physical runtime-db partition is
  live. A fresh frame's runtime-db starts `{}`; reads return the current
  `:rf.db/runtime` projection off the one-container frame-state. Per Spec
  002 §The two-partition frame contract and API.md `runtime-db-value`.

  EP-0023 (rf2-32siq3.32): accepts a frame-id keyword or a live frame object."
  [frame-id]
  (frame/frame-runtime-db-value (frame/frame-target->id frame-id)))

(defn frame-state-value
  "Return the coherent frame-state projection for the named frame —
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, or `nil` for an
  unknown / destroyed frame. The full-frame read for SSR / epoch /
  time-travel / Xray.

  EP-0001 (rf2-q4i9ko + rf2-adwcv6): reads the coherent two-partition
  frame state off the one physical frame-state container. A fresh frame's
  state is `{:rf.db/app {} :rf.db/runtime {}}`; the `:rf.db/runtime` slot
  equals `runtime-db-value`. Per Spec 002 §The two-partition frame
  contract and API.md `frame-state-value`.

  EP-0023 (rf2-32siq3.32): accepts a frame-id keyword or a live frame object."
  [frame-id]
  (frame/frame-state-value (frame/frame-target->id frame-id)))

(defn snapshot-of
  "Return the value at `path` in a frame's app-db — convenience over
  `(get-in (rf/app-db-value frame-id) path)`. Frame resolution (EP-0002
  carried invariant): an explicit `(:frame opts)` override WINS; else the
  scope/hold stamp via `frame/require-current-frame!`. Returns `nil` if
  the frame is missing or the path resolves to nothing. A snapshot read
  under no `:frame` opt and no established scope raises
  `:rf.error/no-frame-context` rather than reading an invented default.
  Per Spec 002 §The public registrar query API."
  ([path] (snapshot-of path nil))
  ([path opts]
   (let [frame-id (frame/frame-target->id
                    (or (:frame opts)
                        (frame/require-current-frame!
                          :snapshot-of {:where 're-frame.core/snapshot-of})))]
     (get-in (frame/frame-app-db-value frame-id) path))))

;; rf2-80mmlf: the `sub-topology` / `sub-cache` facade aliases are REMOVED.
;; They are SUBSCRIPTION-TOOLING surfaces (static dependency-graph + runtime
;; cache inspection), not app-author front-porch reads — their real callers
;; are tests / REPLs / dev tooling, which address the owning
;; `re-frame.subs.tooling` namespace (`sub-topology` / `sub-cache-snapshot`)
;; directly (production counter bundles DCE the bodies). On JVM the
;; convenience aliases in `re-frame.subs` (`subs/sub-topology` /
;; `subs/sub-cache-snapshot`) remain for the legacy `subs/<name>` shape.

;; EP-0013 -> EP-0023 supersession (rf2-pl97nd.2): the app-value construction +
;; composition facade (`rf/module` / `rf/app` / `rf/app-registrations` /
;; `rf/app-requires` / `rf/app-owns`) is REMOVED from the public facade. The
;; public construction model is `rf/image` — the selected registration-set value
;; a frame loads — supplied to `make-frame` via `:images`; a feature namespace
;; registers ordinary `reg-*` forms and an image selects them by `:include-ns`
;; provenance glob, so no separate module/app composition noun is needed. The
;; constructors + inspectors REMAIN in `re-frame.app-value` as internal substrate
;; (the registrar-backed installation path during migration). See
;; `(rf/migration-explain :rf/app)` / `(rf/migration-explain :rf/module)` and
;; EP-0023 §Backwards Compatibility.

;; ---- app-value installation (EP-0013 D2 stage 7) -------------------------
;;
;; The LAST D2 slice: seat an immutable app value into a runtime realm.
;; `install!` makes a constructed (stage-6) app value the program a realm
;; dispatches/subscribes/resolves against — capability-checked first (the
;; app's `:rf.app/requires` must be satisfiable by the realm, fail loud on unmet),
;; then the descriptors are lowered into the realm's registrar and the seated
;; value recorded at the realm boundary. `reinstall!` hot-reloads a realm by
;; diffing the new app value against the installed one and applying the delta,
;; returning the diff. Both DEFAULT to the process default realm, so seating an
;; app value is byte-identical to the `reg-*` sugar that registered the same
;; ids — zero ergonomic regression; the ordinary namespace-load sugar path is
;; untouched. `rf/install!` / `rf/reinstall!` are the reserved-vocabulary names
;; ruled in EP-0013 issue 1.

;; ---- the kind-aware descriptor lowering seam (the install! bridge) --------
;;
;; A CONSTRUCTED app value's descriptor (from `module`) carries the HIGH-LEVEL
;; registration form — a raw `:handler` + the `:doc`/`:schema` metadata — NOT
;; the wrapped, dispatch-ready registrar metadata (the event interceptor chain,
;; the sub `:input-kind`/`:input-signals`). To make a seated descriptor
;; actually DISPATCHABLE/RESOLVABLE, `install!` must lower it through the SAME
;; kind-specific registration logic the `reg-*` sugar path uses —
;; `register-event!` wraps the handler into the `:rf/event-handler` interceptor;
;; `reg-sub` parses input signals; `reg-fx`/`reg-cofx` stamp their slots. That
;; logic lives in `re-frame.events` / `re-frame.subs` / `re-frame.fx` /
;; `re-frame.cofx` / `re-frame.frame`, which `re-frame.app-value` must NOT
;; require (it is a leaf on the realm/registrar spine, bundle-isolation
;; neutral). So core — which already pulls all those reg surfaces — publishes a
;; `:app-value/install-descriptor!` late-bind hook that `app-value/install!`
;; consults per descriptor.
;;
;; EP-0013 §Implementation step 7 defines the FIRST descriptor-format kinds —
;; `:event`/`:sub`/`:fx`/`:cofx` AND `:frame`. All five are wired here through
;; their real registration logic (`:frame` → `reg-frame`, which creates the
;; frame container + runs `:on-create` + installs classification — the malformed
;; flat slot that `reg-frame` never produces is the rf2-chc8vs gap this closes).
;;
;; Step 8 DEFERS the rest (`:route`/`:flow`/`:resource`/`:mutation`/`:view`/
;; `:head`/`:error-projector`/`:resource-scope`): each has real registration
;; logic (route compile, flow input-signal parse, scope wiring, …) the flat
;; registrar lowering BYPASSES, so flat-lowering one seats a slot the subsystem
;; cannot consume. Rather than silently seat that malformed slot, the hook
;; REFUSES LOUDLY — a diagnosable `:rf.error/unsupported-descriptor-kind` naming
;; the unsupported kind + the wired set + that it is a later slice — per EP-0013
;; issue-12 (refuse-loudly is fail-closed) + the corpus no-silent-swallow rule
;; (rf2-3nbl5.1). The flat fallback in `register-descriptor!` now only ever runs
;; for a projected descriptor of a wired kind whose `:metadata` already carries
;; the registrar slot (when the hook is unbound — e.g. a production bundle that
;; never loaded core's reg surfaces).

(def ^:private install-deferred-kinds
  "EP-0013 step-8 registration kinds whose install lowering is NOT yet wired.
  Each carries real registration logic (route compile, flow input-signal parse,
  resource/mutation/scope wiring, view handler wrap, SSR head + error-projector
  registration) the flat registrar lowering bypasses, so `install!` REFUSES
  LOUDLY rather than seat a malformed flat slot the subsystem cannot consume —
  fail-closed per EP-0013 issue-12. Wiring these is a later slice."
  #{:route :flow :resource :mutation :resource-scope :view :head :error-projector})

(def ^:private install-wired-kinds
  "EP-0013 step-7 FIRST descriptor-format kinds — those `install-descriptor!`
  lowers through their real registration logic. The complement of
  `install-deferred-kinds` within the Spec 001 registrar taxonomy."
  #{:event :sub :fx :cofx :frame})

(defn- install-descriptor!
  "Lower one app-value registration descriptor into the realm's registrar
  through its kind's real registration path, so a constructed (high-level)
  descriptor becomes dispatch/resolve-ready exactly as a `reg-*` call would.

  Handles the EP-0013 step-7 first-format kinds — `:event`/`:sub`/`:fx`/`:cofx`
  AND `:frame` — through their real `reg-*` logic, returning `true`. `:frame`
  lowers through `reg-frame` (atomic create-and-register: a frame container,
  `:on-create`, classification install), so a seated frame is a REAL frame that
  appears in `frame-ids` — not the malformed flat slot the registrar-only path
  produced (rf2-chc8vs). EP-0018 (rf2-xhfxcs.14): an `:event` descriptor
  lowers straight through the ONE public `reg-event` runtime fn (coeffects in,
  a closed effects map out); the former `:event/kind` sub-discriminator is
  gone, so every module event seats through the one shape.

  For the step-8-DEFERRED kinds (`install-deferred-kinds`) THROWS
  `:rf.error/unsupported-descriptor-kind` (the ex-data IS the diagnostic —
  naming the kind + the wired set + that its install lowering is a later slice)
  rather than silently flat-lowering a slot the subsystem cannot consume —
  fail-closed per EP-0013 issue-12 + the no-silent-swallow rule.

  Returns `false` for any other kind to signal `install!` should fall back to
  the flat registrar lowering — reached only by a projected descriptor when the
  hook is unbound (the flat path round-trips a projected descriptor unchanged)."
  [kind id {:keys [handler metadata source owner]}]
  ;; Fold the descriptor's lifted `:source` envelope back into the metadata the
  ;; reg fn sees, so an explicitly-supplied source coordinate (issue 8: a
  ;; non-macro / code-gen host) survives the lower→register round-trip. The reg
  ;; fns merge the macro `*pending-coords*` over this, so a real macro-path coord
  ;; still wins when present. Carry the `:owner` (module provenance) through too
  ;; so the realm's registrar records which module installed each registration.
  (let [meta (cond-> (merge source (or metadata {}))
               (some? owner) (assoc :owner owner))]
    (cond
      (contains? install-deferred-kinds kind)
      (error/throw-error!
        :rf.error/unsupported-descriptor-kind
        'rf/install!
        (str "rf/install!: descriptor kind " kind
             " is not yet installable — its real registration"
             " logic is a later EP-0013 slice (step 8). install!"
             " wires " (pr-str install-wired-kinds) " so far;"
             " seating " kind " through the flat registrar would"
             " produce a slot the subsystem cannot consume."
             " Register it through its own reg-* sugar.")
        {:recovery :register-through-reg-*-sugar
         :extra    {:kind     kind
                    :id       id
                    :wired    install-wired-kinds
                    :deferred install-deferred-kinds}})

      :else
      (case kind
        ;; EP-0018 (rf2-xhfxcs.14): an event descriptor lowers straight through
        ;; the ONE public `reg-event` runtime fn (coeffects in, a closed effects
        ;; map out). The former `:event/kind` sub-discriminator + the per-kind
        ;; reg fns are gone — every module event seats through the one shape.
        ;;
        ;; rf2-untip9: a PROJECTED descriptor (from `av/app-value`) carries the
        ;; EFFECTIVE registrar metadata — the assembled `:interceptors` chain
        ;; whose tail is the inline `:rf/event-handler` framework wrapper, plus
        ;; the generated `:rf.cofx/requires-parsed`. Re-feeding those generated
        ;; slots to `reg-event` makes its reference-only validation reject the
        ;; wrapper (`:rf.error/inline-interceptor-removed`). `normalize-relowered-meta`
        ;; strips them back to the authored shape (recovering the author's
        ;; reference chain), so a projected/reconciled app value re-lowers
        ;; faithfully; it is a no-op on a constructed descriptor.
        :event (do (events/reg-event id (events/normalize-relowered-meta meta) handler) true)
        :sub   (do (subs/reg-sub id meta handler) true)
        :fx    (do (fx/reg-fx id meta handler) true)
        :cofx  (do (cofx/reg-cofx id meta handler) true)
        :frame (do (frame/reg-frame id meta) true)
        false))))

(late-bind/set-fn! :app-value/install-descriptor! install-descriptor!)

(defn- refuse-unsupported-install!
  "The PREFLIGHT counterpart of `install-descriptor!`'s kind-boundary throw.
  Given an app value's full `[kind id]` registration pairs, THROWS
  `:rf.error/unsupported-descriptor-kind` (enumerating the blocking `[kind id]`s)
  when ANY names a step-8-DEFERRED kind (`install-deferred-kinds`), BEFORE the
  seating loop lowers a single descriptor (rf2-c6armm.8 #2).

  `install-descriptor!` already throws on a deferred kind, but it throws
  MID-LOOP — after the loop has already lowered kinds 1..N-1. For most wired
  kinds that mid-loop throw is harmless: `seat-into-realm!` snapshots the
  realm's registrar atom and restores it on any throw, so a half-populated
  registrar rolls back cleanly. But `:frame` is the exception — `reg-frame`
  creates a LIVE frame container (a side-channel write to the process
  `frame/frames` atom, plus `:on-create` + classification side effects) that the
  registrar-only rollback does NOT undo. So a multi-descriptor app that lowers a
  `:frame` BEFORE hitting a deferred kind would leave the frame container, its
  classification, and its `:on-create` residue live even though the registrar
  rolled back and no `:app` was recorded — the exact `false installed-app /
  failed-install` leak rf2-c6armm.8 #2 names.

  Preflighting every kind here, before any lowering, closes that window: a
  refused install creates NO live frame because the throw precedes the loop.
  This is the install-path symmetry of `refuse-unsupported-removal!` (the
  removal-path preflight) — both refuse-loudly at the KIND BOUNDARY before any
  mutation, fail-closed per EP-0013 issue-12 + the no-silent-swallow rule. The
  per-descriptor `install-descriptor!` throw stays as the in-loop backstop (a
  bundle whose preflight hook is unbound still refuses, just later).

  Published as `:app-value/refuse-unsupported-install!` (mirroring the removal
  hook) so the leaf `re-frame.app-value` ns preflights through core rather than
  re-stating the deferred-kind set; no-op fallback when the hook is unbound (a
  bundle that never loaded core's reg surfaces — the in-loop throw still fires)."
  [kind-id-pairs]
  (let [blocking      (->> kind-id-pairs
                           (filter (fn [[kind _]] (contains? install-deferred-kinds kind)))
                           (sort)
                           (vec))
        [first-kind
         first-id]    (first blocking)]
    (when (seq blocking)
      (error/throw-error!
        :rf.error/unsupported-descriptor-kind
        'rf/install!
        (str "rf/install!: cannot seat the descriptor(s) "
             (pr-str blocking) " — their kinds are step-8-DEFERRED "
             "(" (pr-str install-deferred-kinds) "), whose real "
             "registration logic is a later EP-0013 slice. install! "
             "wires " (pr-str install-wired-kinds) " so far; seating "
             "a deferred kind through the flat registrar would produce "
             "a slot the subsystem cannot consume. Refused BEFORE any "
             "lowering so no partially-seated runtime state (e.g. a "
             "live :frame container) leaks from a failed install. "
             "Register the deferred kinds through their own reg-* "
             "sugar.")
        {:recovery :register-through-reg-*-sugar
         ;; `:kind`/`:id` name the FIRST blocking pair (the same shape
         ;; `install-descriptor!`'s in-loop throw carried, so the
         ;; single-deferred-kind diagnostic is unchanged); `:blocking`
         ;; enumerates every blocking pair (the whole-app preflight).
         :extra    {:kind     first-kind
                    :id       first-id
                    :blocking blocking
                    :deferred install-deferred-kinds
                    :wired    install-wired-kinds}}))))

(late-bind/set-fn! :app-value/refuse-unsupported-install! refuse-unsupported-install!)

(defn- refuse-unsupported-removal!
  "The REMOVAL-path counterpart of `install-descriptor!`'s kind-boundary throw.
  Given the `:removed` `[kind id]` pairs of a `reinstall!` diff, THROWS
  `:rf.error/unsupported-descriptor-kind` (enumerating the blocking
  `[kind id]`s) when any names a step-8-DEFERRED kind (`install-deferred-kinds`)
  — symmetric with the add/changed path, which already throws via
  `install-descriptor!`. The descriptor diff does not own step-8 kinds in EITHER
  direction in this slice: a deferred kind is not yet app-value-INSTALLABLE, so
  it is not app-value-REMOVABLE either.

  Without this, `reinstall!`'s `:removed` path would call `registrar/unregister!`
  UNCONDITIONALLY for every removed `[kind id]`. A step-8 kind registered through
  its OWN sugar (`reg-mutation`/`reg-resource`/`reg-route`/`reg-flow`/…) DOES
  reach the realm's registrar and IS projected into the diff's old-app, so a
  `reinstall!` that omits a sugar-registered step-8 id would land it in `:removed`
  and silently `unregister!` it — running NO subsystem teardown (in-flight
  mutation/resource abort, routing `:current`, flow owner-rebind), the same
  silent-orphan window `refuse-live-frame-removal!` closed for `:frame`. This is
  the symmetric closure on the removal path (rf2-cquy9u, completing the
  rf2-7zn9kg kind-boundary ruling).

  Throws BEFORE any mutation so a refused reinstall leaves the realm untouched.
  Published as `:app-value/refuse-unsupported-removal!` (mirroring the install
  hook) so the leaf `re-frame.app-value` ns refuses through core rather than
  re-stating the deferred-kind set — when the hook is unbound (a bundle that
  never loaded core's reg surfaces) the removal path falls back to the bare
  `registrar/unregister!`, exactly as `install-descriptor!` falls back to the
  flat registrar lowering."
  [removed]
  (let [blocking (->> removed
                      (filter (fn [[kind _]] (contains? install-deferred-kinds kind)))
                      (sort)
                      (vec))]
    (when (seq blocking)
      (error/throw-error!
        :rf.error/unsupported-descriptor-kind
        'rf/reinstall!
        (str "rf/reinstall!: cannot remove the descriptor(s) "
             (pr-str blocking) " — their kinds are step-8-DEFERRED "
             "(" (pr-str install-deferred-kinds) "), not yet "
             "installable through the descriptor diff, so they are "
             "not removable through it either. A step-8 kind "
             "registered via its own sugar (reg-mutation / "
             "reg-resource / reg-route / reg-flow / …) stays owned "
             "by that sugar's clear-* lifecycle — unregistering it "
             "through the app-value diff would skip the subsystem "
             "teardown (in-flight abort, routing :current, flow "
             "owner-rebind) and silently orphan its live instances. "
             "Clear it through its own clear-* surface before "
             "reinstalling without it.")
        {:recovery :clear-through-reg-*-sugar
         :extra    {:removed  blocking
                    :deferred install-deferred-kinds
                    :wired    install-wired-kinds}}))))

(late-bind/set-fn! :app-value/refuse-unsupported-removal! refuse-unsupported-removal!)

;; EP-0013 -> EP-0023 supersession (rf2-pl97nd.2): the realm install / hot-reload
;; / constructor / query facade — `rf/install!`, `rf/reinstall!`, `rf/realm`,
;; `rf/dispose-realm!`, `rf/realm-ids`, `rf/installed-app` — is REMOVED from the
;; public facade. The public model targets a FRAME (a process-local frame id, or
;; a direct frame object for tests) whose image generation determines
;; registration resolution; the public hot-reload path is `reload-images!`
;; against a frame target. The realm machinery REMAINS in `re-frame.realm` /
;; `re-frame.app-value` as internal substrate (the registrar-backed installation
;; path that `install!`/`reinstall!` above still wire through the late-bind
;; hooks), but it is no longer presented as public vocabulary. See
;; `(rf/migration-explain :rf/install!)` / `:rf/realm` / `:rf/installed-app` and
;; EP-0023 §Backwards Compatibility.

;; ---- interceptors --------------------------------------------------------

(def ^{:doc "Programmatic / REPL form of `reg-interceptor` (the `*`-suffix
  fn, per Conventions §`*`-suffix naming) — no macro source-coordinate
  capture. Register an interceptor DESCRIPTOR (`{:before}` / `{:after}` /
  `{:before :after}` / `{:factory}`) under `id` (arities `(id descriptor)` /
  `(id metadata descriptor)`). The public ergonomic surface is the
  `reg-interceptor` macro (captures source coords). Per EP-0022 and
  spec/API.md §Registration."}
  reg-interceptor* icpt-reg/reg-interceptor*)

(def ^{:doc "INTERNAL lowering constructor (EP-0022) — NOT the public
  application-authoring surface; author interceptors with `reg-interceptor`
  and reference them by id from event/frame `:interceptors` chains. This
  fn-form lowers a descriptor into an executable chain entry from kwargs:
  `:id`, `:before`, `:after`, and an optional `:source-coord` (the
  `->interceptor` macro supplies it from `(meta &form)`). Retained as the
  framework-internal lowering seam (used by the registry resolver, the std
  interceptors, and tests); it MUST NOT appear in a public event/frame
  chain. Per spec/001 §`->interceptor` is not the application authoring
  form, spec/002 §10, and spec/API.md §Standard interceptors."}
  ->interceptor*  interceptor/->interceptor*)

#?(:clj
   (defmacro ->interceptor
     "INTERNAL lowering constructor (EP-0022) — NOT the public application-
     authoring surface. The public form is `reg-interceptor` (which names the
     interceptor, captures source coords, and makes it addressable / queryable
     / overridable by id). `->interceptor` survives only as the coord-capturing
     internal lowering constructor (the macro counterpart of `->interceptor*`):
     it builds an interceptor map from kwargs and bakes the definition-site
     `:source-coord` from `(meta &form)` so the Xray Epoch INTERCEPTOR row can
     jump to source when an interceptor throws. It MUST NOT appear in a public
     event/frame chain — those carry interceptor REFERENCES (Spec 002).

     Kwargs: `:id` (keyword name; default `:unnamed`), `:before`
     (`(fn [ctx] ctx)` — runs before the handler), `:after`
     (`(fn [ctx] ctx)` — runs after, in reverse order).

     The captured coord DCEs under `:advanced` + `goog.DEBUG=false` (the
     macro's prod branch omits the `:source-coord` kwarg entirely). Per
     spec/001 §`->interceptor` is not the application authoring form,
     spec/002 §10, spec/API.md §Standard interceptors, and rf2-siheh."
     [& kwargs]
     (csm/build-interceptor-form (meta &form) (symbol (str (ns-name *ns*))) *file*
                                 kwargs)))

;; Interceptor CONTEXT ACCESSORS — `get-coeffect` / `assoc-coeffect` /
;; `get-effect` / `assoc-effect` — are NO LONGER re-exported from the
;; `re-frame.core` façade. Post-EP-0017/EP-0022 they lost their audience: the
;; setters (`assoc-coeffect` / `assoc-effect`) had zero callers, the getters
;; one. The intended interceptor model is to author with `reg-interceptor` and
;; let the `:before` / `:after` fns receive and return the context map directly
;; (ordinary `(get-in ctx [:coeffects k])` / `assoc-in` map work) — there is no
;; façade-blessed accessor layer. The underlying `re-frame.interceptor/get-
;; coeffect` / `assoc-coeffect` / `get-effect` / `assoc-effect` fns remain in
;; their owning namespace as the framework-internal context helpers (used by
;; `events` / `privacy` / `router` / `spec` and the interceptor tests); they are
;; simply not a public surface.

;; EP-0022 (accepted) removed the public `rf/path` VALUE constructor
;; (EP-0022:552 "There is no public rf/path value constructor."; :932 lists the
;; removal). spec/API.md + spec/002-Frames.md already follow it — the one public
;; path surface is the framework-registered factory ref
;; `[:rf.interceptor/path <path-vector>]`. The implementation had DRIFTED (kept
;; exporting `rf/path` aliased to a legacy std-interceptors `path` fn whose
;; weaker `:after` defeated the rf2-ekq28v commit no-op). rf2-dgtdna reconciles:
;; the legacy fn is gone and this facade name survives ONLY as a `^:no-doc`
;; throwing stub (the project's actionable-removed-API pattern, like the
;; EP-0018 `reg-event-db` / EP-0017 `inject-cofx` stubs) — a stale `(rf/path …)`
;; resolves to a real var and fails LOUDLY with `:rf.error/path-removed`, naming
;; the `[:rf.interceptor/path …]` ref as the replacement. `^:no-doc` drops it
;; from the API manifest generator + the CLJS publics probe: it carries no
;; manifest row and is not part of the documented public surface. See
;; spec/API.md §Standard interceptors and docs/api/15-removed.md.
(def ^{:no-doc true
       :doc "REMOVED in EP-0022 (no alias). Calling `path` raises the hard
  error `:rf.error/path-removed`, naming the framework-registered ref
  `[:rf.interceptor/path <path-vector>]` (used in a handler's `:interceptors`
  chain) as the replacement. See `re-frame.std-interceptors/path-removed!` and
  spec/API.md §Standard interceptors."}
  path            std-interceptors/path-removed!)

;; EP-0022 (accepted) removed the public `unwrap-interceptor` VALUE
;; (docs/EP/EP-0022-registered-interceptors.md:53-55 "no standard unwrap";
;; :555-578 §"No standard unwrap"; :881/:932 list the removal). spec/API.md +
;; spec/002-Frames.md already follow it — the framework ships NO standard
;; unwrap value; the canonical spelling is handler-payload destructuring (the
;; M-19 `[<id> <payload-map>]` shape destructured in the handler arglist), or
;; a PROJECT-registered `:app/unwrap` interceptor when chain-wide reshaping is
;; genuinely intended. The implementation had DRIFTED (kept exporting
;; `unwrap-interceptor` aliased to a legacy std-interceptors value). rf2-3qeu38
;; reconciles (the `rf/path` twin under rf2-dgtdna): the legacy value is gone
;; and this facade name survives ONLY as a `^:no-doc` throwing stub (the
;; project's actionable-removed-API pattern, like the EP-0018 `reg-event-db` /
;; EP-0017 `inject-cofx` stubs) — a stale `(rf/unwrap-interceptor …)` resolves
;; to a real var and fails LOUDLY with `:rf.error/unwrap-removed`, naming the
;; replacement. `^:no-doc` drops it from the API manifest generator + the CLJS
;; publics probe: it carries no manifest row and is not part of the documented
;; public surface. See spec/API.md §Standard interceptors and
;; docs/api/15-removed.md.
(def ^{:no-doc true
       :doc "REMOVED in EP-0022 (no alias). Referencing `unwrap-interceptor`
  raises the hard error `:rf.error/unwrap-removed`, naming the replacement:
  handler-payload destructuring (the M-19 `[<id> <payload-map>]` shape
  destructured in the handler arglist), or a project-registered `:app/unwrap`
  interceptor for genuine chain-wide reshaping. See
  `re-frame.std-interceptors/unwrap-removed!` and spec/API.md §Standard
  interceptors."}
  unwrap-interceptor std-interceptors/unwrap-removed!)

;; EP-0015 §7 (accepted 2026-06-11): `redact-interceptor` is REMOVED from
;; the public API. A positional "redact for the trace but not the handler"
;; interceptor made privacy depend on interceptor placement rather than on
;; the owner of the payload shape; registration-owned `:sensitive` payload
;; classification + centralized `project-egress` at egress boundaries
;; replace it. The `re-frame.privacy/redact-interceptor` fn (and the
;; router's internal `redact-interceptor?` consumer) remain as internal
;; plumbing, but the var is no longer published from this façade.

;; ---- privacy / spec / trace / emit / elision (Spec 009, 010) -------------

(def ^{:doc "Predicate: returns `true` iff `trace-event` is a map carrying
  `:sensitive? true`. Trace-event filter for privacy-aware listeners and
  off-box egress. Per Spec 009 §Privacy."}
  sensitive?           privacy/sensitive?)

(def ^{:doc "Production-side schema validation interceptor VALUE, registered
  under the framework id `:rf.schema/at-boundary`. Reference it by id from a
  `reg-event` handler's metadata `:interceptors` chain —
  `{:interceptors [:rf.schema/at-boundary]}` (EP-0022: chains are
  reference-only; this Var is the registration-boundary input, never an
  inline chain entry) — to force `:schema` validation against the dispatched
  event vector even in production builds where dev-time validation is elided.
  The verb `validate-` telegraphs the time/build-mode axis the interceptor
  lives on (no-op in dev, validates in prod); the `-interceptor` suffix (per
  Conventions §Value-vs-fn naming) telegraphs that this is a Var holding a
  value, not a fn. Per Spec 010 §Production builds. The interceptor reuses
  the handler's existing `:schema` metadata — no parallel schema."}
  validate-at-boundary-interceptor spec/validate-at-boundary-interceptor)

(def ^{:doc "Emit a trace event. Production builds elide the body
  entirely (Closure DCE on the `interop/debug-enabled?` gate); in dev /
  JVM the envelope is built and delivered to the ring buffer, epoch
  recorder, and registered listeners. Per Spec 009 §Trace emit."}
  emit-trace-event!         trace/emit!)

;; ---- stream-parameterized observation listener verb ----------------------
;;
;; One listener verb across the four pure listener streams — the
;; differentiator is DATA (which stream), so it rides in a leading
;; required `stream` keyword rather than spawning one register/unregister
;; pair per channel. The closed stream vocabulary:
;;
;;   :trace   — dev-only trace-event listener (re-frame.trace; production
;;              CLJS bundles DCE the registration site under a `goog.DEBUG`
;;              gate). The `:event` vector and all slots ride in dev only.
;;   :events  — always-on event-emit listener (re-frame.event-emit);
;;              survives `:advanced` + `goog.DEBUG=false`. Receives a tight
;;              per-event record fanned across EVERY frame, `:event` elided
;;              through the wire-walker but otherwise unprojected. NOT the
;;              normal off-box egress path — that is the frame-owned
;;              `:observability` sink. For an intentionally cross-frame hook.
;;   :errors  — always-on error-emit listener (re-frame.error-emit);
;;              survives `:advanced` + `goog.DEBUG=false`. Receives a tight
;;              error-record per `:rf.error/*` event; `:event` wire-elided,
;;              but `:exception` rides RAW (the documented exception to the
;;              always-on 'structured data only' rule). NOT projected under
;;              any frame's egress policy — the frame-owned `:observability
;;              :errors` sink is the normal off-box error path.
;;   :epoch   — drain-settle epoch-record listener, late-bound through the
;;              optional `day8/re-frame2-epoch` artefact; degrades to nil
;;              when the artefact is absent.
;;
;; `register-observability-sink!` is a DISTINCT verb, NOT a `:sink` stream
;; (frame-policy sink-id, ALREADY-PROJECTED record, `reg-frame`
;; `:observability` coupling) — see below.
;;
;; Unknown stream throws `:rf.error/unknown-listener-stream` (closed
;; vocabulary; pre-alpha — no bare 2-arity `:trace` default, no compat
;; aliases). Per Spec 009 §Observation listeners + Spec 015 §Frame-owned
;; observability sink policy. The heavier trace-buffer machinery is reached
;; via `re-frame.trace.tooling/<name>` directly for the production-DCE
;; story; the trace-buffer reader is re-exported below for the JVM-side
;; tools / story / xray / re-frame-10x consumers.

(def ^:private listener-streams
  "Closed vocabulary for `register-listener!` / `unregister-listener!`."
  #{:trace :events :errors :epoch})

(defn- unknown-listener-stream! [verb stream]
  (throw (ex-info (str verb ": unknown listener stream " (pr-str stream)
                       " — must be one of " (pr-str listener-streams))
                  {:rf.error/id     :rf.error/unknown-listener-stream
                   :rf/where        verb
                   :rf/stream       stream
                   :rf/valid        listener-streams})))

(defn register-listener!
  "Register an observation listener `f` under `id` on `stream` — one verb
  across the four pure listener streams (`:trace` / `:events` / `:errors`
  / `:epoch`). Re-registering the same `id` on the same stream replaces.
  Returns `id` (or nil on the `:epoch` stream when the
  `day8/re-frame2-epoch` artefact is absent).

  - `:trace`  — dev-only trace-event listener (production CLJS bundles DCE
                the registration site when it is `goog.DEBUG`-gated).
  - `:events` — always-on event-emit listener; ADVANCED corpus-wide
                integration hook fanned across EVERY frame (NOT the normal
                off-box egress path — that is the frame-owned
                `:observability` sink). Survives `:advanced` +
                `goog.DEBUG=false`.
  - `:errors` — always-on error-emit listener; the record is fanned across
                EVERY frame, NOT projected under any frame's egress policy
                (`:event` wire-elided, `:exception` rides RAW). The
                frame-owned `:observability :errors` sink is the normal
                off-box error path.
  - `:epoch`  — drain-settle epoch-record listener (Spec 009
                §`register-epoch-listener!`); no-op returning nil when the
                epoch artefact is absent.

  An unknown `stream` throws `:rf.error/unknown-listener-stream` (closed
  vocabulary — no bare trace default, no compatibility aliases). Per
  Spec 009 §Observation listeners + Spec 015 (EP-0015) §Frame-owned
  observability sink policy."
  [stream id f]
  (case stream
    :trace  (trace/register-listener! id f)
    :events (event-emit/register-event-listener! id f)
    :errors (error-emit/register-error-listener! id f)
    :epoch  (rf-epoch/register-epoch-listener! id f)
    (unknown-listener-stream! 'rf/register-listener! stream)))

(defn unregister-listener!
  "Drop the listener registered under `id` on `stream` (`:trace` /
  `:events` / `:errors` / `:epoch`). Returns nil. No-op on the `:epoch`
  stream when the `day8/re-frame2-epoch` artefact is absent. An unknown
  `stream` throws `:rf.error/unknown-listener-stream`. Per Spec 009
  §Observation listeners."
  [stream id]
  (case stream
    :trace  (trace/unregister-listener! id)
    :events (event-emit/unregister-event-listener! id)
    :errors (error-emit/unregister-error-listener! id)
    :epoch  (rf-epoch/unregister-epoch-listener! id)
    (unknown-listener-stream! 'rf/unregister-listener! stream)))

(defn clear-listeners!
  "Drop every registered listener on `stream` (`:trace` / `:events` /
  `:errors` / `:epoch`). Test-isolation only — production code should
  never call this. Returns nil. No-op on the `:epoch` stream when the
  `day8/re-frame2-epoch` artefact is absent. An unknown `stream` throws
  `:rf.error/unknown-listener-stream`. Per Spec 009 §Observation
  listeners."
  [stream]
  (case stream
    :trace  (trace/clear-listeners!)
    :events (event-emit/clear-event-listeners!)
    :errors (error-emit/clear-error-listeners!)
    :epoch  (rf-epoch/clear-epoch-listeners!)
    (unknown-listener-stream! 'rf/clear-listeners! stream)))

#?(:clj
   (do
     (def ^{:doc "Return the named frame's cascade-keyed trace ring,
       oldest-first. Two arities:

         (rf/trace-buffer frame-id)
           Returns cascade bundles by default — one entry per retained
           cascade with the cascade's `:dispatch-id`, raw `:trace-events`,
           and the projected six-domino slots (`:event`, `:dispatched`,
           `:handler`, `:fx`, `:effects`, `:subs`, `:renders`, `:other`).

         (rf/trace-buffer frame-id opts)
           `opts` is a filter map. `{:flat true}` returns raw trace
           events instead of cascade bundles. The full filter
           vocabulary lives in Spec 009 §Filter vocabulary.

       Returns `[]` for a destroyed or never-registered frame, and `[]`
       in production (the ring is never allocated under
       `goog.DEBUG=false`). JVM-only alias — CLJS callers use
       `re-frame.trace.tooling/trace-buffer` directly. Per Spec 009
       §Per-frame trace rings (cascade-keyed, dev-only)."}
       trace-buffer           trace/trace-buffer)
     (def ^{:doc "Empty the named frame's cascade-keyed trace ring.
       Tooling uses this between sessions. No-op for an unknown frame,
       no-op in production. JVM-only alias — CLJS callers use
       `re-frame.trace.tooling/clear-trace-buffer!` directly. Per Spec
       009 §`trace-buffer` API."}
       clear-trace-buffer!    trace/clear-trace-buffer!)))

;; The always-on event-emit / error-emit listener registries are NO LONGER
;; facade exports. They are reached through the stream-parameterized
;; `register-listener!` / `unregister-listener!` / `clear-listeners!` verb
;; above with the `:events` / `:errors` stream (rf2-ikjmkm, decision
;; rf2-dbo0c9 Option C). The registries themselves stay reachable via the
;; `re-frame.event-emit` / `re-frame.error-emit` namespaces + the
;; `:error-emit/register-error-listener!` late-bind hooks for the internal
;; consumers that already address them that way (router fan-out, the routing
;; on-match-error trap, the SSR error projector).

(def ^{:doc "Walk `v` and substitute the frame's declared sensitive or
  large paths for wire egress (the durable declarations are frame-owned —
  installed by `reg-frame` `:sensitive` / `:large {:app-db …}`, EP-0015 §8).
  Sensitive wins over large when both declarations match. Sensitive paths
  become `:rf/redacted`; large paths become `:rf.size/large-elided`. Per
  Spec 009 §Wire elision and Security.md §Off-box egress."}
  elide-wire-value                 elision/elide-wire-value)

(def ^{:doc "Project a record or value for egress across a trust boundary
  (EP-0015 §10/§11). The public, record-level boundary primitive — the
  required step before any off-box sink. Dispatches on a record's `:kind`
  (`:rf.observe/handled-event` / `:rf.observe/error`) to a private per-kind
  projector, falling back to walking a kindless input as a tree-shaped
  value (the direct-read path); for every tree-shaped slot it delegates to
  `elide-wire-value` against the frame's classification. `opts` carries
  `:rf.egress/profile` (the closed six-member enum), `:frame`, `:path`, and
  the advanced `:rf.size/*` overrides (which compose on top of the
  profile — the override wins). An unknown profile throws
  `:rf.error/unknown-egress-profile`. Fail-closed: projects a tree slot
  only when the frame is known; no `:rf/default` synthesis. Per Spec 015
  §Projection and Security.md §Off-box egress."}
  project-egress                   projection/project-egress)

;; ---- frame-owned observability sink routing (EP-0015 §9, rf2-t55hxg.7) ----
;;
;; The NORMAL production observability story (Spec 015 §Frame-owned
;; observability sink policy): an app declares a sink under a frame's
;; `:observability` config and registers the concrete sink fn against that
;; sink id here. The runtime routes one handled-event record per processed
;; event and one error record per `:rf.error/*` site through `project-egress`
;; (under the frame's classification + the sink's egress profile) to the
;; declared sinks. Sinks consume ALREADY-PROJECTED records — no sink-local
;; redaction. The advanced corpus-wide event/error listeners (reachable via
;; `register-listener!` with the `:events` / `:errors` stream) remain for
;; advanced cross-frame integration; this sink is the normal Datadog/Sentry
;; surface.

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
  register-observability-sink!     observability/register-observability-sink!)

(def ^{:doc "Drop the observability sink registered under `sink-id`.
  Returns nil. Per Spec 015 §Frame-owned observability sink policy."}
  unregister-observability-sink!   observability/unregister-observability-sink!)

;; EP-0015 §8 (rf2-d2r3um): the `populate-elision-from-schemas!` /
;; `populate-sensitive-from-schemas!` facade exports are REMOVED. They were
;; the public route that walked `reg-app-schema` `{:large? true}` /
;; `{:sensitive? true}` slot props into the app-db egress registry — a
;; second route to classify a durable app-db path the frame now owns
;; (`reg-frame` `:sensitive` / `:large {:app-db …}`). Schemas describe
;; shape, not durable app-db egress policy; durable app-db classification is
;; installed once at `reg-frame` time by `re-frame.frame-classification`.

;; Derived-tree value-based egress — the SINGLE composed multi-slot helper
;; (rf2-leggev Option B2, rf2-j7qbhm). The nine granular gears that USED to
;; sit here — `elision-declarations` / `elision-sensitive-declarations` (the
;; introspection readers) and the sensitive + large value-match trios
;; (`redact-derived-values`, `elision-sensitive-value-set`,
;; `elision-collect-sensitive-values`, `redact-matching-values`,
;; `redact-derived-large-values`, `elision-large-value-marker-map`,
;; `redact-matching-large-values`) — are NO LONGER `re-frame.core` façade
;; exports. They were the disassembled gears of derived-tree value redaction,
;; leaked onto the façade for ONE assembling consumer (Story-MCP egress). Spec
;; 015 names the boundary/operation, not the gearbox: this one composed helper
;; subsumes them. The low-level arms (and the two declaration readers) remain
;; in `re-frame.elision` for the rare bespoke caller — reach them through that
;; home namespace. (KEPT on the façade: `elide-wire-value` — the path walker —
;; and `project-egress` — the record-level boundary; those ARE the boundary
;; language, not derived-value gears.)

(def ^{:doc "Redact a frame's app-db-sensitive / -large values out of one or
  more DERIVED value slots before off-box egress — the single composed
  multi-slot egress helper (EP-0015, rf2-leggev). The value-based DUAL of the
  path-based `elide-wire-value`: where the walker redacts a frame's declared
  `:sensitive` / `:large` app-db slots BY PATH, a derived tree (rendered
  hiccup, a resolved `:effective-args` map, a snapshot body, a plan-resolved
  value slot) re-surfaces the SAME values at non-app-db positions the
  path-based walker can never reach — so they must be redacted BY VALUE.

  `(redact-derived-slots m slot-keys source-db frame-id wire-opts)`:

    - `m` carries the derived value(s).
    - `slot-keys` `nil` (or empty) ⇒ the SINGLE-TREE case: `m` *is* the
      derived tree, scrubbed wholesale. A seq of keys ⇒ `m` is a MAP and each
      PRESENT key's value is scrubbed (absent keys untouched) — the one
      collection pass drives every slot.
    - `source-db` is the raw db the derived values were produced from (the
      source of the secret / large candidate sets).
    - `wire-opts` is the `elide-wire-value` egress floor the path-based
      `:app-db` slot ships under (`:frame frame-id` supplied automatically, so
      the helper stays egress-profile-agnostic). It also accepts
      `:rf.elision/extra-sensitive-source` — an extra raw db whose unguarded
      governed-sensitive values join the candidate union, the FAIL-CLOSED
      pre-frame source for a documented no-run path (e.g. a plan's authored
      `:db-seed` read before any run allocates the frame).

  Both egress axes run in `elide-wire-value`'s composition order — SENSITIVE
  first (it wins; the large collector skips sensitive-declared nodes), then
  LARGE over the survivors. The sensitive candidate set (with the
  non-unique-secret guard) AND the large `{value marker}` map are each
  collected ONCE from `source-db` and reused across every slot. Returns `m`
  unchanged when there is no candidate source or the frame declares nothing.
  The trusted-local raw opt-out belongs to the caller. Per Spec 015
  §Projection and Security.md §Off-box egress."}
  redact-derived-slots             elision/redact-derived-slots)

(def ^{:doc "Project a sequence of raw trace events into one cascade
  record per `:dispatch-id`. Pure data — JVM and CLJS. Used by
  `re-frame-10x`, Xray, and other tools that present cascade-level
  views over the raw event stream. Per Spec 009 §Trace projection."}
  group-cascades  trace-projection/group-cascades)

;; Facade-export classification (diff-time rule): TOOL-FACING projection
;; primitive, sibling to `group-cascades`. Same `[frame dispatch-id]`
;; grouping, additionally attaching each cascade's raw `:trace-events`.
;; Justified: Tool-Pair streaming consumers (re-frame2-pair cascade
;; bundles) need both the six-domino record AND each cascade's raw events
;; keyed by the SAME frame-scoped key the framework uses — re-deriving the
;; grouping consumer-side with a weaker (dispatch-id-only) key mixes
;; foreign-frame events (rf2 trace-contract drift). Pure data; JVM + CLJS.
(def ^{:doc "Like `group-cascades`, but each cascade record additionally
  carries a `:trace-events` slot with the vector of raw trace events that
  composed it — keyed by the same frame-scoped `[frame dispatch-id]`
  grouping. Pure data — JVM and CLJS. The correct projection for
  consumers needing both the six-domino record and each cascade's raw
  events (e.g. re-frame2-pair streaming cascade bundles), so they never
  re-derive the grouping with a weaker dispatch-id-only key. Per Spec 009
  §Trace projection."}
  group-cascades-with-events  trace-projection/group-cascades-with-events)

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

(def ^{:doc "Rewind the named frame's WHOLE frame-state — BOTH the app-db
  AND runtime-db partitions — to the named epoch's `:frame-state-after`,
  reinstalled atomically via `replace-frame-state!` (EP-0001, Mike ruling #2),
  so machine snapshots, the route slice, elision declarations, and SSR
  metadata are revived alongside app-db, not just the app-db projection
  (`:db-after`). Returns `true` on success, `false` on any of the seven
  documented failure modes (each emits a structured `:rf.epoch/*` error
  trace) or when the epoch artefact is absent. Per Tool-Pair
  §Time-travel. Late-bound via `:epoch/restore-epoch!`."}
  restore-epoch!     rf-epoch/restore-epoch!)

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

;; ---- EP-0001 two-partition mutators (rf2-q4i9ko / rf2-tfepxu) -------------
;;
;; Per Spec 002 §Frame-state value accessors and mutators + API.md, the
;; partition write surface is `replace-app-db!` / `reset-app-db!` /
;; `replace-runtime-db!` / `replace-frame-state!` (Mike ruling #1 / #10).
;;
;; - `replace-app-db!` is the app-db-only state-injection write — the
;;   direct rename of the former `reset-frame-db!` Tool-Pair surface
;;   (rf2-tfepxu, bead 9). Same synthetic-epoch recording, gating, and
;;   failure modes; a db-shaped name never silently replaces runtime-db.
;; - `reset-app-db!` resets the app-db partition to `{}` while preserving
;;   live runtime-db — the app-db sibling of the whole-frame `reset-frame!`.
;; - `replace-runtime-db!` / `replace-frame-state!` are epoch-backed
;;   Tool-Pair injection writes (rf2-szbzei): each records a synthetic
;;   `:rf/epoch-record` so `restore-epoch!` can rewind past the injection,
;;   returns a boolean, shares the drain-guard + the framework-owned
;;   runtime-db schema-validation contract, and raises
;;   `:rf.error/epoch-artefact-missing` when the epoch artefact is absent.
;;   They late-bind through `re-frame.epoch` exactly as the app-db pair
;;   does (via `:epoch/replace-runtime-db!` / `:epoch/replace-frame-state!`).

(def ^{:doc "Replace `frame-id`'s `app-db` partition with `app-db`,
  bypassing the dispatch loop. The canonical Tool-Pair write surface for
  app-db state injection — pair tools, story fixtures, conformance
  harnesses, and time-travel from JSON repros. Records a synthetic
  `:rf/epoch-record` so `restore-epoch!` can rewind. Returns `true` on
  success, `false` on a documented failure (unknown frame, drain in
  flight, schema mismatch). Dev-only (gated on `interop/debug-enabled?`).
  Raises `:rf.error/epoch-artefact-missing` when the epoch artefact is
  absent.

  EP-0001 (rf2-tfepxu): the direct rename of the former `reset-frame-db!`
  Tool-Pair surface (Mike ruling #10). Replaces ONLY the app-db partition;
  runtime-db is a partition this surface never touches (a db-shaped name
  never silently replaces runtime-db). Per Spec 002 §Frame-state value
  accessors and mutators and API.md `replace-app-db!`. Late-bound via
  `:epoch/replace-app-db!`."}
  replace-app-db!    rf-epoch/replace-app-db!)

(def ^{:doc "Reset `frame-id`'s `app-db` partition to `{}`, bypassing the
  dispatch loop, while preserving live runtime-db (machines / routes /
  elision / SSR survive). The app-db-only sibling of the whole-frame
  `reset-frame!` (EP-0001 rf2-tfepxu, Mike ruling #10). Equivalent to
  `(replace-app-db! frame-id {})` — same synthetic-epoch recording, gating,
  and failure modes. Returns `true` on success, `false` on a documented
  failure. Dev-only (gated on `interop/debug-enabled?`). Raises
  `:rf.error/epoch-artefact-missing` when the epoch artefact is absent. Per
  Spec 002 §reset-frame! and API.md `reset-app-db!`. Late-bound via
  `:epoch/reset-app-db!`."}
  reset-app-db!    rf-epoch/reset-app-db!)

(def ^{:doc "Replace ONLY `frame-id`'s `runtime-db` partition with
  `runtime-db` — the framework-owned subsystem state (machine snapshots,
  route slice, …). Privileged runtime / full-frame Tool-Pair injection
  surface; app-db is untouched. Records a synthetic `:rf/epoch-record` so
  `restore-epoch!` can rewind. Returns `true` on success, `false` on a
  documented failure (unknown frame, drain in flight, runtime-db schema
  mismatch). Dev-only (gated on `interop/debug-enabled?`). Raises
  `:rf.error/epoch-artefact-missing` when the epoch artefact is absent.

  rf2-szbzei: epoch-backed Tool-Pair injection write — the runtime-db
  sibling of `replace-app-db!`. Per Spec 002 §Frame-state value accessors
  and mutators, Tool-Pair §Pair-tool writes, and API.md
  `replace-runtime-db!`. Late-bound via `:epoch/replace-runtime-db!`."}
  replace-runtime-db!    rf-epoch/replace-runtime-db!)

(def ^{:doc "Replace BOTH partitions of `frame-id` atomically with
  `frame-state` (`{:rf.db/app … :rf.db/runtime …}`) — the full-frame
  install for tool-driven replay / fixture install (epoch restore, time
  travel, SSR hydration, frame reset, test-fixture install). A db-shaped
  name never silently replaces runtime-db — this is the explicit full-frame
  surface (Mike ruling #10). Records a synthetic `:rf/epoch-record` so
  `restore-epoch!` can rewind. Returns `true` on success, `false` on a
  documented failure (unknown frame, drain in flight, app-db OR runtime-db
  schema mismatch). Dev-only (gated on `interop/debug-enabled?`). Raises
  `:rf.error/epoch-artefact-missing` when the epoch artefact is absent.

  rf2-szbzei: epoch-backed Tool-Pair injection write — the full-frame
  sibling of `replace-app-db!`. Per Spec 002 §Frame-state value accessors
  and mutators, Tool-Pair §Pair-tool writes, and API.md
  `replace-frame-state!`. Late-bound via `:epoch/replace-frame-state!`."}
  replace-frame-state!   rf-epoch/replace-frame-state!)

;; Per Security.md §Epoch privacy posture and rf2-mrsck — single
;; normative projection helpers for off-box epoch egress.
(def ^{:doc "Project an `:rf/epoch-record` for off-box egress — the
  single normative projection emission site for off-box epoch egress
  (parallel to `elide-wire-value` for direct reads). Routes payload
  slots through wire-elision with off-box defaults; bookkeeping slots
  pass through unchanged. Tools that egress epoch records across a
  process boundary (Xray-MCP `watch-epochs`, recorders, forwarders)
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
;;
;; The raw `install-managed-request-stubs!` / `uninstall-managed-request-stubs!`
;; pair is NO LONGER a `re-frame.core` façade export (rf2-ntwwyt — test-support
;; infrastructure, not app-facing core surface). Reach the pair through its
;; home namespace `re-frame.http-test-support` (require it from your test ns).
;; The ergonomic `with-managed-request-stubs` macro stays on the façade.

(def ^{:doc "Fn-form: install stubs, run `thunk`, uninstall. The plumbing
  the `with-managed-request-stubs` macro routes through. Implementation
  ships in `day8/re-frame2-http` under `re-frame.http-test-support`
  (rf2-lwmgw). Per Spec 014 §Testing. Late-bound via
  `:http/with-managed-request-stubs*`."}
  with-managed-request-stubs*      rf-http/with-managed-request-stubs*)

(def ^{:doc "Clear an HTTP interceptor by `id` from a frame's
  `:rf.http/managed` middleware chain. EP-0002 context-required
  frame-local: the single-arity `(clear-http-interceptor id)` resolves
  the frame through the carried-invariant scope chain; the two-arity
  `(clear-http-interceptor frame id)` names the frame explicitly (the
  *override*). Under no scope and no explicit frame the call raises
  `:rf.error/no-frame-context` — it does NOT synthesise a `:rf/default`
  target. Implementation ships in `day8/re-frame2-http`. Per Spec 014
  §Middleware. Late-bound via `:http/clear-http-interceptor`."}
  clear-http-interceptor           rf-http/clear-http-interceptor)

;; reg-http-interceptor is a macro (per the defreg-macro form above) so
;; source-coords are captured at the call site like every other reg-*.
;; CLJS apps reach the fn-form via `re-frame.core-http/reg-http-interceptor`
;; for programmatic registration that bypasses the macro path.

;; ---- configure / substrate adapter / boot --------------------------------

(defn configure!
  "Configure a process-level runtime knob. v1 keys:
    :epoch-history {:depth N}                       ring depth (default 50; 0 disables)
    :trace-buffer  {:cascades-retained N}           per-frame trace-ring cascade count
                                                    (default 50; 0 disables retention).
                                                    Applied to `:rf/default` and to every
                                                    frame that did not set its own
                                                    `:rf.trace/cascades-retained` metadata.
                                                    Per Spec 009 §Per-frame trace rings.
    :elision       {:rf.size/threshold-bytes N}     wire-elision size threshold
                                                    (default 16384; 0 disables runtime
                                                    auto-detect — only declared / schema
                                                    entries elide)
  Unknown keys silently no-op. Per-frame settings live on frame metadata.
  Per Tool-Pair §How AI tools attach.

  Per rf2-cmfln: the prior `:sub-cache {:grace-period-ms N}` knob is
  retired. Sub disposal is **synchronous on derefer-count → 0** —
  there is no deferred-grace timer to configure.

  `:trace-buffer` routes through the `re-frame.trace.tooling` sibling
  ns (per rf2-qwm0a). Production builds that never load the tooling
  sibling silently no-op on this key — the ring + listener machinery
  is DCE'd anyway."
  [knob opts]
  (case knob
    :epoch-history (when-let [f (late-bind/get-fn :epoch/configure!)]
                     (f opts))
    :trace-buffer  (when-let [f (late-bind/get-fn :trace.tooling/configure-trace-buffer!)]
                     (f opts))
    :elision       (elision/configure! opts)
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
  (error/throw-error!
    :rf.error/no-adapter-specified
    'rf/init!
    "rf/init! takes the adapter spec map directly — there is no keyword form, no nil form, and no default-adapter registry. Require the adapter ns and pass its `adapter` Var: (rf/init! reagent/adapter)."
    {:recovery :no-recovery
     :extra    (cond-> {:expected "adapter spec map"}
                 (some? received) (assoc :received received))}))

(defn init!
  "Idempotent boot — installs a substrate adapter. Pass the adapter spec
  map directly (no default-adapter registry; rf2-agql):
    (require '[re-frame.adapter.reagent :as reagent])
    (rf/init! reagent/adapter)
  Non-map / nil raises `:rf.error/no-adapter-specified`. Per Spec 006
  §Adapter selection at boot.

  `init!` does NOT create a `:rf/default` frame. Per Spec 002 §`:rf/default`
  is an ordinary id (EP-0002), the runtime never synthesises a default
  frame — frame identity is carried, not found. Declare your app's root
  frame explicitly (`(rf/reg-frame :app {…})` / `with-frame` / a
  frame-provider) and dispatch / subscribe within that scope. A small app
  or test may still choose `:rf/default` as its explicit frame id; the
  runtime will not infer it."
  [adapter-map]
  (cond
    (nil? adapter-map)        (bad-init-arg! nil)
    (not (map? adapter-map))  (bad-init-arg! adapter-map)
    :else
    (do
      (when-not (adapter/current-adapter)
        (adapter/install-adapter! adapter-map))
      ;; EP-0022 (rf2-0adhqs.2): re-seed the framework-standard interceptors
      ;; (`:rf.interceptor/path`) so the standard refs survive a test fixture's
      ;; `registrar/clear-all!`. Idempotent.
      (std-interceptors/register-standard-interceptors!)
      ;; EP-0022 (rf2-i3uxo2): re-seed the framework-standard
      ;; `:rf.schema/at-boundary` interceptor so the ref form
      ;; `[:rf.schema/at-boundary]` resolves after a `registrar/clear-all!`.
      ;; Idempotent.
      (spec/register-schema-interceptors!)
      nil)))

(defn init-platform
  "Set the host-wide active-platform marker (`:server` or `:client`).
  Per Spec 011 §Effect handling on the server: the runtime tracks the
  active platform so `reg-fx`/`reg-cofx` `:platforms` metadata can
  gate execution. CLJS hosts default to `:client`, JVM hosts to
  `:server`; call this at boot to override:

    (rf/init-platform :server)   ;; CLJS-on-Node SSR runtime
    (rf/init-platform :client)   ;; JVM-runnable test simulating browser

  Per-frame `:config :platform` (e.g. set by the `:ssr-server` preset)
  still wins over this host-wide marker — `init-platform` is the
  fallback when no per-frame override is in play.

  `p` must be `:server` or `:client`; anything else raises
  `:rf.error/invalid-platform`. Idempotent / re-callable."
  [p]
  (if (#{:server :client} p)
    (do (interop/set-platform! p) nil)
    (error/throw-error!
      :rf.error/invalid-platform
      'rf/init-platform
      "rf/init-platform takes the platform keyword directly — :server or :client per Spec 011 §Effect handling on the server."
      {:recovery :no-recovery
       :extra    {:expected "one of #{:server :client}"
                  :received p}})))

;; ---- feature inspection (rf2-3nbl5.5, API-governance G5) ------------------
;;
;; Front-porch for the optional-feature inventory: which `day8/re-frame2-
;; <feature>` artefacts are on the classpath, and the exact copy-pasteable
;; coordinate to add when one is missing. These three fns SHIP to
;; production (runtime queries, not instrumentation — NOT elided). The
;; feature→coordinate mapping is STATIC DATA in the always-loaded
;; `re-frame.features` facade ns, never a live require into the optional
;; impls (which would pull every optional namespace into every production
;; bundle and break bundle-isolation). Per spec/API.md §Feature inspection
;; and Conventions §Facade re-export, artefact require.

(def ^{:doc "Return a map of every optional feature keyword to its
  inspection entry — static coordinate data (`:maven` / `:require` /
  `:spec`) merged with the live `:loaded?` status. Ships to production
  (NOT elided). Per spec/API.md §Feature inspection."
       :arglists '([])}
  features features/features)

(def ^{:doc "Return `true` when the optional feature's implementation
  artefact is on the classpath, `false` otherwise (incl. an unknown
  feature keyword). Detection is a pure keyword lookup in the always-
  loaded late-bind hooks atom — no require into the optional namespace.
  Known features: `:schemas` `:machines` `:routing` `:flows` `:http`
  `:ssr` `:epoch`. Ships to production (NOT elided). Per spec/API.md
  §Feature inspection."
       :arglists '([feature])}
  feature-loaded? features/feature-loaded?)

(def ^{:doc "Assert the optional feature is loaded — returns `true`, or
  throws `:rf.error/feature-not-loaded` carrying the EXACT copy-pasteable
  Maven coordinate + require form when the impl artefact is absent (an
  unknown feature keyword throws `:rf.error/unknown-feature`). Use as a
  self-explaining early guard before a feature-dependent code path.
  Ships to production (NOT elided). Per spec/API.md §Feature inspection."
       :arglists '([feature])}
  require-feature! features/require-feature!)
