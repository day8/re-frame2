(ns re-frame.resources
  "Resources — declarative server-state as a runtime-managed read model
  over a frame work ledger. Per Spec 016.

  A resource is a named, cached read of remote or external state.
  `reg-resource` registers it; views read it through PASSIVE subscriptions
  (`[:rf.resource/state …]`); route entry, events, and machines CAUSE it
  to fetch. The resource runtime owns identity, cache scope, staleness,
  dedupe, invalidation, GC, in-flight ownership, SSR hydration, and tool
  metadata, so an app stops re-implementing that bookkeeping per feature.

  This namespace is the **public boot point and façade** for the
  resources artefact: apps boot it with `(:require [re-frame.resources])`.
  Doing so transitively loads every concern sibling under
  `re-frame.resources.*` and runs the registrations at the bottom of this
  file:

  - `re-frame.resources.state`           — reserved runtime-db paths, durable shapes, the host-side generation allocator, framework-write-authority stamp
  - `re-frame.resources.registry`        — `reg-resource` / `clear-resource` + the `:resource` registrar kind + registry introspection
  - `re-frame.resources.transport`       — the transport-neutral lower seam
  - `re-frame.resources.transport.http`  — the `:rf.http/managed` lowering (late-bound HTTP)
  - `re-frame.resources.events`          — the `:rf.resource/*` causal event handlers (+ internal replies)
  - `re-frame.resources.subs`            — the `:rf.resource/*` passive subs
  - `re-frame.resources.route`           — LATE-BOUND routing integration (`:resources` route-metadata key)
  - `re-frame.resources.ssr`             — LATE-BOUND SSR/hydration projection

  The registrations live HERE (not in the siblings) so a
  `(require 're-frame.resources :reload)` on a fresh registrar
  (`clear-all!` test fixture) re-wires every handler — the long-
  established consumer-test pattern, mirroring `re-frame.routing` /
  `re-frame.machines`.

  ## Optionality + bundle isolation

  Per Spec 016 §Implementation status this is a POST-V1 OPTIONAL artefact
  (`day8/re-frame2-resources`). `re-frame.core` MUST NOT `:require` it; the
  public-API surface is published through the late-bind table, so an app
  that omits the artefact sees the wrappers throw a clean
  `:rf.error/resources-artefact-missing`. The routing + SSR integrations
  are LATE-BOUND (resources never statically `:require`s routing / ssr /
  http), so an app that loads resources but not those optional artefacts
  carries none of their code. Nothing here `:require`s from `tools/`.

  ## Slice status

  This is the rf2-p10npe artefact SKELETON (EP-0003 slice 2). The public
  surface, the registrar kind, the late-bind wiring, and the
  routing/SSR-projection extensions are real and load cleanly; the runtime
  LOGIC (entry transition function, work-ledger join/dedupe, stale
  suppression, GC, invalidation, route-plan execution, hydration install)
  lands in later slices (rf2-afpdkn / rf2-pbxj48 / …). The event handlers
  are registered but their bodies raise `:rf.error/resource-not-implemented`
  so a premature call fails loudly."
  (:require [re-frame.events :as events]
            [re-frame.late-bind :as late-bind]
            [re-frame.resources.events :as resource-events]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.route :as route]
            [re-frame.resources.ssr :as ssr]
            [re-frame.resources.state :as state]
            [re-frame.resources.subs :as resource-subs]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;; These `def`s make the sibling fns reachable as
;; `re-frame.resources/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, conformance, tests, examples) see one surface.

(def reg-resource    registry/reg-resource)
(def clear-resource  registry/clear-resource)
(def resource-meta   registry/resource-meta)
(def resource-ids    registry/resource-ids)

(defn resources
  "Return resource introspection for a frame target (Spec 016
  §Introspection). The static registry half — every registered resource id
  — is real now; the per-frame live resource-instance table lands with the
  runtime slice (rf2-pbxj48).

  SKELETON: returns `{:resource-ids [...]}` (the static registry). The
  `:frame`-scoped live-instance enumeration is filled in by the runtime
  slice."
  ([] {:resource-ids (resource-ids)})
  ([_opts] {:resource-ids (resource-ids)}))

(defn resource-state
  "Return a resource instance's runtime state for an explicit `:frame`
  introspection target `{:resource :scope :params :frame}` (Spec 016
  §Introspection). Per EP-0002 there is no ambient `:rf/default` fallback.

  SKELETON: the live per-frame entry read lands with the runtime slice
  (rf2-pbxj48); raises until then so a premature call is obvious."
  [_opts]
  (throw (ex-info ":rf.error/resource-not-implemented"
                  {:rf.error/id :rf.error/resource-not-implemented
                   :where       'rf/resource-state
                   :recovery    :no-recovery
                   :reason      (str "resource-state's live per-frame entry read "
                                     "lands with the EP-0003 resource runtime "
                                     "slice (rf2-pbxj48). This is the rf2-p10npe "
                                     "artefact skeleton.")})))

;; ---- event / sub / hook registrations -------------------------------------
;; Keeping the registrations in this façade means a `(require
;; 're-frame.resources :reload)` re-wires every handler on a fresh
;; registrar (the `clear-all!` test-fixture recovery pattern).

;; Every resource event handler stamps the reserved
;; `:rf/framework-authority? true` registration-meta (Spec 016 §Write
;; authority) so a returned `:rf.db/runtime` effect is recognised as a
;; framework write — the runtime's `:rf.warning/app-handler-runtime-effect`
;; ownership diagnostic treats these as in-bounds. Applied uniformly so a
;; new resource handler that touches the slice inherits authority by
;; sitting in this façade. (Mirrors routing's framework-authority-meta.)
(def ^:private framework-authority-meta state/framework-authority-meta)

;; Public resource events (map payloads). Per Spec 016 §Events.
(events/reg-event-fx :rf.resource/ensure
                     framework-authority-meta
                     resource-events/ensure-handler)
(events/reg-event-fx :rf.resource/refetch
                     framework-authority-meta
                     resource-events/refetch-handler)
(events/reg-event-fx :rf.resource/invalidate-tags
                     framework-authority-meta
                     resource-events/invalidate-tags-handler)
(events/reg-event-fx :rf.resource/release-owner
                     framework-authority-meta
                     resource-events/release-owner-handler)
(events/reg-event-fx :rf.resource/clear-scope
                     framework-authority-meta
                     resource-events/clear-scope-handler)
(events/reg-event-fx :rf.resource/remove
                     framework-authority-meta
                     resource-events/remove-handler)

;; Framework-internal reply handlers. Per Spec 016 §Events / §Transport.
;; User code MUST NOT dispatch these.
(events/reg-event-fx :rf.resource.internal/succeeded
                     framework-authority-meta
                     resource-events/succeeded-handler)
(events/reg-event-fx :rf.resource.internal/failed
                     framework-authority-meta
                     resource-events/failed-handler)
(events/reg-event-fx :rf.resource.internal/aborted
                     framework-authority-meta
                     resource-events/aborted-handler)
(events/reg-event-fx :rf.resource.internal/gc-fired
                     framework-authority-meta
                     resource-events/gc-fired-handler)
(events/reg-event-fx :rf.resource.internal/stale-suppressed
                     framework-authority-meta
                     resource-events/stale-suppressed-handler)

;; Passive resource subs. Per Spec 016 §Subscriptions.
(resource-subs/register-subs!)

;; LATE-BOUND cross-feature integrations (Spec 016 §Route integration /
;; §SSR and hydration). Wired here so they re-install on a `:reload`. Each
;; publishes a late-bind hook the host artefact (routing / ssr) CONSULTS;
;; both are no-op-effect on an app that never loads the host artefact.
(route/install-routing-integration!)
(ssr/install-ssr-integration!)

;; ---- late-bind hook registration ------------------------------------------
;; `re-frame.core` MUST NOT `:require [re-frame.resources]` — the artefact
;; is optional. Public-API re-exports are published through the late-bind
;; table; consumers without the artefact see the hooks unregistered and the
;; wrappers throw `:rf.error/resources-artefact-missing`. The
;; `:resources/reg-resource` key doubles as the feature-inspection PROBE
;; (re-frame.features) — its presence in the late-bind table is the
;; loaded?-signal for the `:resources` feature.

;; The test-isolation reset hook (`:resources/reset-resources!`) is NOT
;; published here — it lives behind an explicit
;; `re-frame.resources.test-support` require (the rf2-dbiv8 posture: keep
;; test fixtures out of the always-on production façade), which publishes
;; it from its own ns-load. The shared CLJS make-reset-runtime-fixture
;; reset-hooks table consults it by key and no-ops when test-support is
;; absent.
(late-bind/set-fns!
  {:resources/reg-resource   reg-resource
   :resources/clear-resource clear-resource
   :resources/resource-meta  resource-meta
   :resources/resource-state resource-state
   :resources/resources      resources})
