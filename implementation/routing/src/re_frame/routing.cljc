(ns re-frame.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route). Navigation is an event;
  URL changes are events. The route slice at
  `[:rf.runtime/routing :current]` carries
  `{:route-id :params :query :fragment :transition :error :nav-token}`.

  This namespace is the public boot point and facade for the routing
  artefact: apps load it with `(:require [re-frame.routing])`. Doing so
  transitively loads every runtime concern under `re-frame.routing.*` (each owns
  one cohesive concern, see below) and runs the registrations
  (`reg-event` / `reg-fx` / `reg-sub` / the late-bind lifecycle hooks /
  `register-error-listener!` / `reg-view*`) at the bottom of this file.

  Registrations live here rather than in the concern namespaces so a
  `(require 're-frame.routing :reload)` on a fresh registrar
  (`clear-all!` test fixture) re-wires every handler. Siblings expose handler fns +
  metadata; this façade composes them.

  The implementation lives in per-concern namespaces:

  - `re-frame.routing.url`            — URL %-encode / %-decode primitives
  - `re-frame.routing.match`          — pattern parsing + match-against
  - `re-frame.routing.address`        — the shared RouteAddress extraction law (closed key classes) every door resolves through (EP-0037 R0)
  - `re-frame.routing.resolve`        — the one resolved-target / route-plan seam + its R0 diagnostic projection (EP-0037 R0)
  - `re-frame.routing.registry`       — reg-route + match-url + route-url + route-table cache
  - `re-frame.routing.classification` — projection-relative route data classification
  - `re-frame.routing.scroll`         — scroll-restoration helpers + :rf.nav/scroll + :rf.nav/capture-scroll fxs
  - `re-frame.routing.events`         — shared nav-event helpers (fire-and-forget :on-match commit)
  - `re-frame.routing.readiness`      — pure resource-derived route-readiness projector (EP-0037 R1)
  - `re-frame.routing.plan`           — pure pre-commit navigation-planning seam (fragment/not-found/classification/telemetry/scroll) shared by both nav entry points
  - `re-frame.routing.decisions`      — the leave/entry decisions, the leave-only pending protocol, and :rf.route/url-requested
  - `re-frame.routing.nav-token`      — :rf.route/with-nav-token + stale-suppression fx
  - `re-frame.routing.navigate`       — :rf.route/navigate event
  - `re-frame.routing.url-change`     — :rf.route/transitioned + :rf.route/handle-url-change
  - `re-frame.routing.nav-fx`         — :rf.nav/push-url + :rf.nav/replace-url + url-owner-frame-id
  - `re-frame.routing.url-bound`      — :url-bound? exclusivity + claim-order maintenance
  - `re-frame.routing.history`        — strategy listener lifecycle + current-url
  - `re-frame.routing.subs`           — framework-shipped subs over the slice
  - `re-frame.routing.link`           — :route/link registered view"
  (:require [re-frame.cofx :as rf.cofx]
            [re-frame.emit :as rf.emit]
            [re-frame.events :as rf.events]
            [re-frame.fx :as rf.fx]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.subs :as rf.subs]
            [re-frame.routing.decisions :as rf.routing.decisions]
            [re-frame.routing.events :as rf.routing.events]
            [re-frame.routing.history :as rf.routing.history]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.routing.nav-counters :as rf.routing.nav-counters]
            [re-frame.routing.nav-fx :as rf.routing.nav-fx]
            [re-frame.routing.nav-token :as rf.routing.nav-token]
            [re-frame.routing.navigate :as rf.routing.navigate]
            [re-frame.routing.prefetch :as rf.routing.prefetch]
            [re-frame.routing.replan :as rf.routing.replan]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.routing.strategy :as rf.routing.strategy]
            [re-frame.routing.sub-egress :as rf.routing.sub-egress]
            [re-frame.routing.subs :as rf.routing.subs]
            [re-frame.routing.url-bound :as rf.routing.url-bound]
            [re-frame.routing.url-change :as rf.routing.url-change]
            ;; The JVM facade provides convenience aliases for tooling. CLJS
            ;; consumers require the tooling namespace directly, keeping it
            ;; out of applications that load routing but attach no tool.
            #?@(:clj  [[re-frame.routing.tooling :as rf.routing.tooling]])
            #?@(:cljs [[re-frame.views :as rf.views]])))

;; ---- public API re-exports ----------------------------------------------
;; The facade is the application-facing home for these operations.

;; Registry
(def reg-route                  rf.routing.registry/reg-route)
(def clear-route                rf.routing.registry/clear-route)
(def match-url                  rf.routing.registry/match-url)
(def route-url                  rf.routing.registry/route-url)
(def malformed-url?             rf.routing.registry/malformed-url?)
(def reset-counters!            rf.routing.registry/reset-counters!)

;; Static-registry introspection. These are owned-namespace operations and
;; are not re-exported from `re-frame.core`.
(def route-ids                  rf.routing.registry/route-ids)
(def route-meta                 rf.routing.registry/route-meta)

;; EP-0037 R0 — the shared RouteAddress extraction law
;; (`re-frame.routing.address`) and the one resolved-target / route-plan seam
;; (`re-frame.routing.resolve`) are INTERNAL routing seams the navigation
;; doors consume; they are deliberately NOT re-exported here. The plan's
;; observable projection is the `:rf.route/planned` trace
;; (`resolve/plan-trace-tags`), which every door commit branch emits through;
;; a tool reads it there rather than through a facade export, exactly as CLJS
;; tools require `re-frame.routing.tooling` directly — routing adds no public
;; `RoutePlan` constructor (Spec 012 §Resolved target and the plan diagnostic
;; projection).

;; Scroll positions are host-side transient state, not runtime-db state.
(def scroll-positions-cap       rf.routing.scroll/scroll-positions-cap)
(def lookup-scroll-position     rf.routing.scroll/lookup-scroll-position)
(def save-scroll-position       rf.routing.scroll/save-scroll-position)
(def frame-scroll-cache         rf.routing.scroll/frame-scroll-cache)
(def save-scroll-position!      rf.routing.scroll/save-scroll-position!)
(def reset-scroll-cache!        rf.routing.scroll/reset-cache!)

;; Allocator high-water marks are host-side transient state so restoring an
;; epoch cannot rewind and recycle a live token.
(def counter-snapshot           rf.routing.nav-counters/counter-snapshot)
(def routing-state-classification rf.routing.nav-counters/routing-state-classification)
(def reset-nav-counters!        rf.routing.nav-counters/reset-cache!)

;; Subs
(def route-sub-fn               rf.routing.subs/route-sub-fn)

;; JVM tools get facade aliases for the static route algebra and a frame's
;; live route-slice algebra. CLJS tools require `re-frame.routing.tooling`
;; directly so production routing does not reach the tooling namespace.
#?(:clj
   (do
     (def route-algebra-view       rf.routing.tooling/route-algebra-view)
     (def route-slice-algebra-view rf.routing.tooling/route-slice-algebra-view)))

;; URL-owner resolution
(def url-owner-frame-id         rf.routing.nav-fx/url-owner-frame-id)

;; Reset the process-global URL claim order between tests.
(def reset-url-claims!          rf.routing.nav-fx/reset-url-claims!)

;; The two shipped frame-level `:url-strategy`
;; maps — `history-url-strategy` (default, path-form) + `hash-url-strategy`
;; (`#`-prefixed) — plus the `with-base-path` combinator that
;; wraps either one for an app deployed under a sub-path. Declared on the
;; URL-owning frame:
;;   (rf/make-frame {:id :app :url-bound? true :url-strategy rf.routing/hash-url-strategy})
;;   (rf/make-frame {:id :app :url-bound? true
;;                       :url-strategy (rf.routing/with-base-path
;;                                       rf.routing/history-url-strategy "/realworld")})
;; These live on the routing facade, not `re-frame.core`; bundle-isolation
;; tests enforce that applications which do not load routing carry none of
;; the routing implementation. Per Spec 012 §URL strategies.
(def history-url-strategy       rf.routing.strategy/history-url-strategy)
(def hash-url-strategy          rf.routing.strategy/hash-url-strategy)
(def with-base-path             rf.routing.strategy/with-base-path)

;; Listener installation/removal follows the `:url-bound?` frame lifecycle.
;; `current-url` is the public query for the history strategy's current
;; path-form URL.
(def current-url                rf.routing.history/current-url)

;; Route-link render fns
#?(:cljs (def route-link-render rf.routing.link/route-link-render))
(def route-link-render-ssr      rf.routing.link/route-link-render-ssr)

;; ---- event / fx / sub / hook / listener registrations -------------------
;; Keeping the registrations in this façade means consumers using
;; `(require 're-frame.routing :reload)` to recover from `clear-all!`
;; re-run every wire here.

;; Routing handlers are legitimate runtime-db writers. This reserved
;; registration marker keeps the app-handler ownership diagnostic from
;; classifying their `:rf.db/runtime` effects as application writes.
(def ^:private framework-authority-meta {:rf/framework-authority? true})

;; The two REPLACEABLE-DEFAULT routing handlers (`:rf.route/navigation-blocked`,
;; `:rf.route/entry-denied`) carry the reserved `:rf/framework-default? true`
;; marker in addition to framework authority. Spec 012 §Navigation blocking:
;; the framework ships a no-op default so a `:can-leave` block / `:can-enter`
;; denial always resolves with no application handler, and an application
;; registering its OWN handler under the same id is the documented recipe (stash
;; the denied destination, replace-navigate to login, fresh-navigate back).
;;
;; The marker is what makes that recipe work. The framework seeds these through
;; the internal `rf.events/reg-event` path, so the source store records them with
;; NO `:rf.provenance/ns`; without the marker the default image selected BOTH
;; the framework copy and the app's provenanced registration and image assembly
;; refused to let selection order decide (`:rf.error/image-duplicate-id`,
;; rf2-0r6q4). Image assembly reads the marker and stops projecting the
;; framework's own copy into the app layer exactly when the app has registered
;; one — see `re-frame.image-assembly/superseded-framework-default-keys`.
(def ^:private framework-default-meta
  (assoc framework-authority-meta :rf/framework-default? true))

;; EP-0037 R1: `:on-match` is fire-and-forget and route readiness is the
;; resource-derived projection, so there is NO `:rf.route.internal/
;; settle-transition` event, NO `:rf.route.internal/on-match-error` event, and
;; NO corpus-wide on-match error-emit listener. A synchronous `:on-match` throw
;; stays on the ordinary Spec 009 event error channel, attributed to the event.
;;
;; Deleting the registration CALLS (the R1 cut) does NOT retire a registration
;; a PRE-R1 generation already installed: the event registrar and the always-on
;; error-emit listener registry are `defonce`, so a dev session that loaded
;; routing before the cut and then `(require 're-frame.routing :reload)`s under
;; HMR retains the three retired framework registrations. A persisting on-match
;; error trap could still observe a new blocking-resource `:loading` transition,
;; route an `:on-match` throw through the retired handler, and resurrect the
;; removed route `:error` / `:on-error` behaviour — a contract violation under
;; normal reload. So the façade IDEMPOTENTLY unregisters exactly these three
;; retired framework ids on every load/reload. This targets ONLY the framework's
;; own retired ids: it resets no registry and clears no user registration.
(rf.events/clear-event :rf.route.internal/settle-transition)
(rf.events/clear-event :rf.route.internal/on-match-error)
(rf.emit/unregister-error-listener! :rf.route/on-match-error-trap)

;; The two recordable allocation coeffects read host-side high-water marks and
;; record the selected id on the causal token. The effect installs the
;; recorded counter with `max`, preserving replay identity without allowing a
;; restore to rewind an allocator. See Spec 012 §Navigation tokens.
(rf.cofx/reg-cofx :rf.route/nav-allocation
               rf.routing.nav-counters/nav-allocation-cofx-meta
               rf.routing.nav-counters/nav-allocation-cofx)
(rf.cofx/reg-cofx :rf.route/pending-nav-allocation
               rf.routing.nav-counters/pending-nav-allocation-cofx-meta
               rf.routing.nav-counters/pending-nav-allocation-cofx)
(rf.fx/reg-fx :rf.route/commit-nav-counter
           rf.routing.nav-counters/commit-nav-counter-meta
           rf.routing.nav-counters/commit-nav-counter-handler)

;; Two-way handlers declare both recordable allocations. They are generated
;; eagerly at processing start; the untaken branch is recorded but not
;; committed to its host high-water mark. Gaps are harmless because allocator
;; ids are monotone and never recycled.
(def ^:private nav-commit-meta
  ;; navigate / transitioned / handle-url-change can BLOCK (pending-nav id)
  ;; OR COMMIT (nav-token) — declare both recordable allocations.
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf.route/nav-allocation
                            :rf.route/pending-nav-allocation]))
(def ^:private url-requested-meta
  ;; A URL request can block here; its transitioned event owns any eventual
  ;; nav-token allocation.
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf.route/pending-nav-allocation]))

;; :rf.route/url-requested + :rf.route/continue + :rf.route/cancel +
;; :rf.route/navigation-blocked + :rf.route/entry-denied — Spec 012
;; §Navigation blocking. `:rf.route/url-requested` runs the leave decision
;; (which mints a pending-nav id on a block), so it declares the recordable
;; pending-nav allocation cofx; `:rf.route/continue` / `:rf.route/cancel` /
;; `:rf.route/navigation-blocked` / `:rf.route/entry-denied` never allocate,
;; so they don't — a terminal entry denial creates no pending value at all.
(rf.events/reg-event :rf.route/url-requested
                     url-requested-meta
                     rf.routing.decisions/url-requested-handler)
;; The leave-pending value and the entry-denial payload both carry the
;; requested URL plus the replayable `:destination` / `:target` (which embed
;; query values and path params). Mark those argument paths sensitive so
;; dispatched-event trace copies redact their carrier values; handlers and
;; the `:rf/pending-navigation` sub still receive the original in-process map.
;; Both payloads are FRAMEWORK-CONSTRUCTED, so this classification is the
;; framework's own fact about its own payload shape, not something an
;; application handler has any reason to restate. An application handler
;; REPLACING one of these defaults replaces the BEHAVIOUR only: the declaration
;; below rides forward and unions with anything the app declares
;; (`image-assembly/retain-framework-default-classification`, rf2-kqxe6.20), so
;; the canonical auth recipe keeps the redaction with no boilerplate.
(def ^:private nav-carrier-sensitive
  [[:requested-url] [:destination] [:target]])
(rf.events/reg-event :rf.route/navigation-blocked
                     (assoc framework-default-meta
                            :sensitive nav-carrier-sensitive)
                     rf.routing.decisions/navigation-blocked-handler)
;; A terminal entry denial carries the same carriers and therefore uses the
;; same trace classification.
(rf.events/reg-event :rf.route/entry-denied
                     (assoc framework-default-meta
                            :sensitive nav-carrier-sensitive)
                     rf.routing.decisions/entry-denied-handler)
(rf.events/reg-event :rf.route/continue
                     framework-authority-meta
                     rf.routing.decisions/continue-handler)
(rf.events/reg-event :rf.route/cancel
                     framework-authority-meta
                     rf.routing.decisions/cancel-handler)

;; :rf.route/navigate — Spec 012 §Navigation is an event. Declares both
;; recordable allocation cofx (mints the nav-token on commit + any block's
;; pending-nav id).
(rf.events/reg-event :rf.route/navigate
                     nav-commit-meta
                     rf.routing.navigate/navigate-handler)

;; :rf.route/prefetch — Spec 012 §Route-plan prefetch (EP-0037 R3). Warm-mode
;; resource-only intent preload: runs a named destination's effective resource
;; plan ownerlessly WITHOUT navigating. It allocates NO nav-token / pending-nav
;; id (prefetch is not an activation), so it declares neither recordable
;; allocation cofx — only the default `:db` (for `{:from-db …}` scope resolution)
;; + `:rf.frame/id`. It writes no runtime-db; the framework-authority marker is
;; carried for consistency with the sibling routing events.
(rf.events/reg-event :rf.route/prefetch
                     framework-authority-meta
                     rf.routing.prefetch/prefetch-handler)

;; :rf.route/replan-resources — Spec 012 §Replanning the active route's
;; resources (rf2-y8jjk). Rerun the ACTIVE route's effective resource plan
;; against the current app-db under the UNCHANGED nav-token, without navigating
;; — the causal door for an app-db-derived identity input (principal, tenant,
;; locale) that changed with no route change. It mints NO nav-token / pending-nav
;; id (a replan is not an activation), so it declares neither recordable
;; allocation cofx — only the default `:db` (for `{:from-db …}` scope
;; resolution) + `:rf.db/runtime` + `:rf.frame/id`. It DOES write runtime-db
;; (the per-token blocking / plan slots and the slice readiness), so the
;; framework-authority marker is load-bearing here, not a courtesy.
(rf.events/reg-event :rf.route/replan-resources
                     framework-authority-meta
                     rf.routing.replan/replan-handler)

;; :rf.route/transitioned + :rf.route/handle-url-change — Spec 012 §URL
;; changes are events. Both declare both recordable allocation cofx (mint the
;; nav-token on commit + any block's pending-nav id).
(rf.events/reg-event :rf.route/transitioned
                     nav-commit-meta
                     rf.routing.url-change/transitioned-handler)
(rf.events/reg-event :rf.route/handle-url-change
                     nav-commit-meta
                     rf.routing.url-change/handle-url-change-handler)

;; :rf.route/nav-token cofx — Spec 012 §Navigation tokens — stale-result
;; suppression step 2. A value-returning AMBIENT supplier (EP-0017) for
;; the current navigation epoch token; delivered FLAT under the
;; `:rf.route/nav-token` key in the coeffects map (EP-0017 §5) to any
;; `:on-match`-reached handler that declares
;; `:rf.cofx/requires [:rf.route/nav-token]`, so the documented
;; `(fn [{:rf.route/keys [nav-token]} _] ...)` shape resolves the live
;; token (not nil). Owner-qualified to the routing subsystem root per
;; EP-0017 §2 / Conventions §Recordable-coeffect fact naming, like its
;; siblings `:rf.route/nav-allocation` / `:rf.route/pending-nav-allocation`.
;; Registered in the façade so a `:reload` re-wires it on a fresh registrar.
(rf.cofx/reg-cofx :rf.route/nav-token
               rf.routing.nav-token/nav-token-cofx-meta
               rf.routing.nav-token/nav-token-cofx)

;; :rf.route/route-id cofx — rf2-ph1grf. The capture-side companion to
;; `:rf.route/nav-token`: delivers the live route id FLAT under
;; `:rf.route/route-id` so an `:on-match`-reached loader declaring
;; `:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]` captures BOTH
;; facts the route-loader work-id `[:rf.work/route route-id nav-token
;; loader-id]` needs at scheduling time — the documented stale-suppression path
;; can no longer thread a nil route id into the work-id tuple. Owner-qualified
;; to the routing subsystem root, like its sibling `:rf.route/nav-token`.
(rf.cofx/reg-cofx :rf.route/route-id
               rf.routing.nav-token/route-id-cofx-meta
               rf.routing.nav-token/route-id-cofx)

;; :rf.route/with-nav-token — Spec 012 §Navigation tokens — stale-result
;; suppression. The test-only `:rf.test/simulate-http-resolution` fixture
;; analogue is NOT wired here — it lives behind an explicit
;; `re-frame.routing.test-support` require so the `:rf.test/*` fixture
;; event never reaches a production registry (rf2-dbiv8, mirrors the
;; managed-HTTP canned-stub gate rf2-cdmle).
(rf.fx/reg-fx :rf.route/with-nav-token
           rf.routing.nav-token/with-nav-token-meta
           rf.routing.nav-token/with-nav-token-handler)

;; :rf.nav/push-url + :rf.nav/replace-url — Spec 012 §Multi-frame
;; routing (rf2-w50qm).
(rf.fx/reg-fx :rf.nav/push-url    rf.routing.nav-fx/push-url-meta    rf.routing.nav-fx/push-url-handler)
(rf.fx/reg-fx :rf.nav/replace-url rf.routing.nav-fx/replace-url-meta rf.routing.nav-fx/replace-url-handler)

;; :rf.nav/scroll + :rf.nav/capture-scroll — Spec 012 §Scroll
;; restoration.
(rf.fx/reg-fx :rf.nav/capture-scroll rf.routing.scroll/capture-scroll-meta rf.routing.scroll/capture-scroll-handler)
(rf.fx/reg-fx :rf.nav/scroll         rf.routing.scroll/scroll-fx-meta      rf.routing.scroll/scroll-fx-handler)

;; Frame (re-)registration lifecycle hook (rf2-h1vqa4: frames do not flow
;; through `rf.registrar/register!`, so the former registrar registration hook is
;; gone — the frame engine's `:routing/on-frame-registered!` late-bind hook is
;; THE lifecycle point, fired AFTER the frame container exists and, on first
;; registration, after `:initial-events` ran). The body is ORDERED: url-bound
;; exclusivity + claim maintenance FIRST (both hosts — JVM routing tests
;; resolve URL ownership too), THEN the CLJS browser-listener reconcile, so
;; the owner resolution the reconcile performs sees current claims. Published
;; via `rf.late-bind/set-fn!` (key-idempotent — a facade `:reload` re-publishes,
;; never stacks). The hook observes only future registrations; reconcile the
;; current frames store on every facade load to seed an unambiguous existing
;; owner. Reconciliation is idempotent and fails closed when multiple
;; pre-existing bindings have no recoverable claim order.
(defn- on-frame-registered!
  [frame-id]
  (rf.routing.url-bound/check-url-bound-exclusivity! frame-id)
  #?(:cljs (rf.routing.history/reconcile-url-listener!))
  nil)
(rf.late-bind/set-fn! :routing/on-frame-registered! on-frame-registered!)
(rf.routing.url-bound/reconcile-existing-url-bindings!)

;; Framework-shipped subs over routing runtime-db state. The derived
;; `:rf.route/*` subscriptions chain from `:rf/route`.
(rf.subs/reg-runtime-sub :rf/route
  {:doc "Subscribe to the current route slice `{:route-id :params :query :transition :error :fragment :nav-token}` at `[:rf.runtime/routing :current]`. The sub returns that published map directly; sibling routing state such as `:pending-navigation` and optional resource-blocking bookkeeping is not included. Allocator counters and scroll positions are host-side transient caches, not runtime-db siblings. Per Spec 012."}
  route-sub-fn)
(rf.subs/reg-sub :rf.route/id
  {:doc "Subscribe to the current route's id keyword (the slice's `:route-id` key). Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:route-id route)))
(rf.subs/reg-sub :rf.route/params
  {:doc "Subscribe to the current route's path params map. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:params route)))
(rf.subs/reg-sub :rf.route/query
  {:doc "Subscribe to the current route's query params map. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:query route)))
(rf.subs/reg-sub :rf.route/transition
  {:doc "Subscribe to the current route's `:transition` state. Per Spec 012 §Route transitions."}
  :<- [:rf/route] (fn [route _] (:transition route)))
(rf.subs/reg-sub :rf.route/error
  {:doc "Subscribe to the current route's `:error` (nil when no error). Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:error route)))
(rf.subs/reg-sub :rf.route/fragment
  {:doc "Subscribe to the current route's URL `#fragment` (string or nil). Per Spec 012 §Fragments."}
  :<- [:rf/route] (fn [route _] (:fragment route)))
(rf.subs/reg-sub :rf.route/chain
  {:doc "Subscribe to the `:parent`-chain of the active route, returned
  as a vector `[parent-most ... current]`. Per Spec 012 §Nested layouts."}
  :<- [:rf.route/id] (fn [id _] (rf.routing.subs/chain-from-meta id)))
(rf.subs/reg-runtime-sub :rf/pending-navigation
  {:doc "Subscribe to the pending-navigation slot at
  `[:rf.runtime/routing :pending-navigation]` in the runtime-db partition
  (nil when no navigation is pending). Per Spec 012 §Navigation blocking —
  pending-nav protocol."}
  rf.routing.subs/pending-navigation-sub-fn)

;; :route/link registered view — Spec 012 §Linking from views.
;; Exposed on both platforms so .cljc render trees resolve identically
;; server- and client-side.
#?(:cljs
   (def route-link
     "Registered view at `:route/link`. Intercepts plain left-clicks and
     dispatches `:rf.route/url-requested`; modifier-key clicks defer to the
     browser. Per Spec 012 §Linking from views and API.md `route-link`
     row. The underlying render fn is `route-link-render`."
     (rf.views/reg-view* :route/link
                      (rf.source-coords/merge-coords {})
                      rf.routing.link/route-link-render))
   :clj
   (rf.registrar/register! :view :route/link
                        (assoc (rf.source-coords/merge-coords {})
                               :handler-fn rf.routing.link/route-link-render-ssr)))

#?(:cljs
   (defn ^:no-doc route-link-element
     "The value published to the `:routing/route-link` hook — what
     `rf/route-link` becomes at a call site (rf2-nvcp).

     Returns the HICCUP ELEMENT `[<component-head> props & children]` rather
     than calling the render fn. That distinction is the whole of this fn, and
     it is a correctness requirement, not a style choice.

     `rf/route-link` is a `defwrapper` (`re-frame.core-routing`), so its body
     is `(apply <hook-fn> args)` — an ordinary function call. A user writes
     `[rf/route-link {:to …} \"Articles\"]`, and the component the substrate
     mounts is therefore `rf/route-link` ITSELF: a plain fn, carrying no
     `{:contextType frame-context}` meta. Publishing the registered view head
     directly made that head run INSIDE that plain-fn component instead of
     becoming a component of its own, so the React-context tier had nothing to
     read it from: `re-frame.views.provider/current-frame` saw React's empty
     default on `(.-context cmp)`, resolved nil, and `route-link-render`'s
     render-time `require-current-frame!` (rf2-o3nam4) raised
     `:rf.error/no-frame-context` on FIRST RENDER — blanking every routed
     application, however correctly it mounted its `frame-root`.

     Emitting the element instead hands the head to the substrate as an
     element TYPE, so it is componentized exactly as a `reg-view` view is
     (`[home-page]` and `[rf/route-link …]` now take the same path) and the
     enclosing `frame-root` / `frame-provider` context reaches it.

     The head is resolved through `rf.views/view-head` per render rather than
     closed over: the head is a property of (registration × installed
     substrate), and `view-head` re-derives it against the adapter installed
     NOW and memoizes (rf2-8mkmb). Closing over the `reg-view*` return value
     would pin the SEED head — derived at ns-load, before `rf/init!` seats any
     adapter — which is right for Reagent and wrong for any substrate that
     publishes `:adapter/componentize-view`."
     [& args]
     (into [(rf.views/view-head :route/link)] args)))

;; ---- late-bind hook registration ------------------------------------------
;; Core must not require this optional artefact. Late-bound hooks publish the
;; integration points without reversing that dependency.

(rf.late-bind/set-fn! :routing/reg-route          reg-route)
;; rf2-bcjpq5 / rf2-sy7zr: no :routing/match-url, :routing/route-url,
;; :routing/clear-route or :routing/current-url hooks — none of those four is
;; a facade export (czn2m0 D1), so core has nothing to late-bind to. Callers
;; use `re-frame.routing/match-url` / `route-url` / `clear-route` /
;; `current-url` directly; a routing app requires this namespace at boot
;; regardless.
(rf.late-bind/set-fn! :routing/reset-counters!    reset-counters!)
;; Reset hooks clear host-side routing state that a raw frame-container reset
;; cannot reach.
(rf.late-bind/set-fn! :routing/reset-nav-counters! reset-nav-counters!)
(rf.late-bind/set-fn! :routing/reset-url-claims!  reset-url-claims!)
;; rf2-6r9j.8: no :routing/route-sub-fn hook. `route-sub-fn` is registered
;; DIRECTLY as the `:rf/route` sub above, in this already-loaded facade, so
;; core never needed to late-bind to it; the hook was mechanical residue of the
;; artefact split (c435165251) that nothing ever read. The public
;; `re-frame.routing/route-sub-fn` alias stays — it is a documented owned-
;; namespace function.

;; Registration-time frame-config preflight (rf2-ktmto9): routing owns the
;; MEANING of `:url-strategy` (presence semantics + host-required legs), core
;; owns the TIMING — the frame engine (`re-frame.frame/upsert-frame!`) invokes
;; this hook with the
;; final expanded config BEFORE any candidate-derived write, so a malformed
;; declaration fails with zero residue (first registration) / preserves the
;; previous frame untouched (re-registration). Published on BOTH hosts — the
;; JVM validates its two host-agnostic legs the same way.
(rf.late-bind/set-fn! :routing/preflight-frame-config! rf.routing.strategy/preflight-frame-config!)

;; Route classifications are stored at runtime-db-absolute paths, while route
;; subscriptions return projections below those paths. These hooks let core's
;; shared egress walker seed a route-sub value at its storage coordinate so
;; the same declarations apply to trace, tool, and off-box reads.
(rf.late-bind/set-fn! :routing/route-sub-egress-path    rf.routing.sub-egress/route-sub-seed-path)
(rf.late-bind/set-fn! :routing/project-route-sub-egress rf.routing.sub-egress/project-route-sub-egress)

;; Frame teardown reaches all routing-owned host-side state through one
;; optional late-bound hook.
(defn- release-routing-host-caches!
  "Release a destroyed frame's scroll and allocator caches, drop the frame's
  URL claim, and reconcile the browser URL listener. The
  `:routing/on-frame-destroyed!` teardown body."
  [frame-id]
  (rf.routing.scroll/release-frame! frame-id)
  (rf.routing.nav-counters/release-frame! frame-id)
  ;; Drop the destroyed frame's URL claim FIRST so `url-owner-frame-id` resolves
  ;; to the successor claimant (or nil), THEN reconcile the browser listener via
  ;; the single strategy-aware op. Reconciliation rebinds to the successor's
  ;; strategy when a live claimant remains (the ownership-transfer fix), tears
  ;; the listener down when no successor remains, and leaves the incumbent
  ;; instance untouched when a non-owner frame is destroyed. `frame-id` is passed
  ;; as the EXCLUDED owner as defence in depth: by hook time the destroyed
  ;; frame's `:destroyed?` flag is already flipped so `frame-meta` reads it as
  ;; absent, but the exclusion keeps the must-not-resolve-as-owner contract
  ;; explicit rather than timing-derived.
  (rf.routing.nav-fx/drop-url-claim! frame-id)
  #?(:cljs (rf.routing.history/reconcile-url-listener! frame-id))
  nil)

(rf.late-bind/set-fn! :routing/on-frame-destroyed! release-routing-host-caches!)

;; Browser URL-change wiring is strategy-aware and follows the URL-owning
;; frame's lifecycle. It is CLJS-only; SSR feeds request URLs through
;; `:rf.route/handle-url-change`. The listener reconcile rides the composed
;; `:routing/on-frame-registered!` hook published above (after the url-bound
;; claim maintenance) — it runs after the frame container exists, so the
;; listener's synchronous initial URL dispatch has a valid target; only the
;; resolved URL owner installs, and a losing duplicate does not replace the
;; incumbent listener.
;;
;; The reset hook covers test fixtures that reset frame containers without
;; executing `destroy-frame!`.
#?(:cljs (rf.late-bind/set-fn! :routing/reset-url-listener!  rf.routing.history/remove-url-listener!))

;; route-link is exposed on both platforms so .cljc render trees
;; resolve identically server- and client-side. On CLJS the hook carries the
;; ELEMENT-emitting `route-link-element`, not the registered head itself — see
;; its docstring (rf2-nvcp): `rf/route-link` is a `defwrapper`, so whatever
;; this hook holds is CALLED rather than mounted, and a head that is called
;; never becomes a component that can read the frame context. SSR has no React
;; context to read, so the JVM side keeps rendering the anchor directly.
#?(:cljs (rf.late-bind/set-fn! :routing/route-link route-link-element)
   :clj  (rf.late-bind/set-fn! :routing/route-link route-link-render-ssr))

;; The substrate-neutral link seam consumed by an optional view artefact's own
;; route-link (rf2-vxgfnd.95.5). `link-model` is PURE and published on BOTH hosts
;; (the JVM/SSR shell needs the path-form href + native? too); `activate-link!` is
;; the CLJS-only click op (SSR has no DOM click to intercept). A view artefact
;; reaches these through the late-bind directory without a static require on
;; routing.
(rf.late-bind/set-fn! :routing/link-model rf.routing.link/link-model)
#?(:cljs (rf.late-bind/set-fn! :routing/activate-link! rf.routing.link/activate-link!))

;; NOTE — there is deliberately no late-bind seam for `:prefetch :intent`
;; (rf2-6r9j.15). `rf/route-link` reaches `rf.routing.link/prefetch-payload`
;; and `prefetch-intent-keys` by direct call, and `prefetch-on-intent!` not at
;; all — see that fn's docstring for what composes the intent handlers in its
;; place. No view artefact consumes any of the three through the hook table,
;; so publishing them only promised reachability that did not exist. Per Spec
;; 012 §Route-plan prefetch the law still lives in `re-frame.routing.link`; a
;; view artefact that needs it should land a real reader first and publish
;; only the keys it reads.
