(ns re-frame.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route). Navigation is an event;
  URL changes are events. The route slice at
  `[:rf.runtime/routing :current]` carries
  `{:route-id :params :query :fragment :transition :error :nav-token}`.

  This namespace is the **public boot point and façade** for the
  routing artefact (per rf2-k682 + the skill docs): apps boot the
  artefact with `(:require [re-frame.routing])`. Doing so transitively
  loads every concern sibling under `re-frame.routing.*` (each owns
  one cohesive concern, see below) and runs the registrations
  (`reg-event` / `reg-fx` / `reg-sub` / `add-registration-hook!` /
  `register-error-listener!` / `reg-view*`) at the bottom of this file.

  The registrations live here (not in the siblings) so a
  `(require 're-frame.routing :reload)` on a fresh registrar
  (`clear-all!` test fixture) re-wires every handler — the long-
  established consumer-test pattern. Siblings expose handler fns +
  metadata; this façade composes them.

  Per the rf2-2yabr cohesion split, the implementation lives in
  per-concern siblings:

  - `re-frame.routing.url`            — URL %-encode / %-decode primitives
  - `re-frame.routing.match`          — pattern parsing + match-against
  - `re-frame.routing.registry`       — reg-route + match-url + route-url + route-table cache
  - `re-frame.routing.classification` — EP-0025 route data classification (projection-relative :sensitive/:large, lowered into the per-frame elision registry at activation)
  - `re-frame.routing.scroll`         — scroll-restoration helpers + :rf.nav/scroll fxs
  - `re-frame.routing.events`         — shared nav-event helpers + :rf.route.internal/settle-transition
  - `re-frame.routing.plan`           — pure pre-commit navigation-planning seam (fragment/not-found/classification/telemetry/scroll) shared by both nav entry points
  - `re-frame.routing.on-match-error` — :on-match error trap + listener
  - `re-frame.routing.can-leave`      — :can-leave gate + pending-nav protocol + :rf.route/url-requested
  - `re-frame.routing.nav-token`      — :rf.route/with-nav-token + stale-suppression fx
  - `re-frame.routing.navigate`       — :rf.route/navigate event
  - `re-frame.routing.url-change`     — :rf.route/transitioned + :rf.route/handle-url-change
  - `re-frame.routing.nav-fx`         — :rf.nav/push-url + :rf.nav/replace-url + url-owner-frame-id
  - `re-frame.routing.url-bound`      — :url-bound? exclusivity registration hook
  - `re-frame.routing.history`        — popstate listener install/remove (driven automatically by the :url-bound? frame lifecycle, rf2-g8pbwg) + current-url
  - `re-frame.routing.subs`           — framework-shipped subs over the slice
  - `re-frame.routing.link`           — :route/link registered view"
  (:require [re-frame.cofx :as cofx]
            [re-frame.error-emit :as error-emit]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.subs :as subs]
            [re-frame.routing.can-leave :as can-leave]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.history :as history]
            [re-frame.routing.link :as link]
            [re-frame.routing.nav-counters :as nav-counters]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.nav-token :as nav-token]
            [re-frame.routing.navigate :as navigate]
            [re-frame.routing.on-match-error :as on-match-error]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.scroll :as scroll]
            [re-frame.routing.strategy :as strategy]
            [re-frame.routing.sub-egress :as sub-egress]
            [re-frame.routing.subs :as routing-subs]
            [re-frame.routing.url-bound :as url-bound]
            [re-frame.routing.url-change :as url-change]
            ;; EP-0014 slice-5 (rf2-eiiifu): the JVM-only require of the
            ;; routing tooling sibling that backs the `route-algebra-view` /
            ;; `route-slice-algebra-view` aliases below. CLJS deliberately
            ;; OMITS this require so a CLJS app that loads the routing
            ;; artefact but never attaches a tool DCEs the tooling body
            ;; wholesale — the facade never reaches it. JVM has no bundle to
            ;; protect; the alias gives JVM tools / conformance fixtures the
            ;; ergonomic `re-frame.routing/route-algebra-view` shape. Mirrors
            ;; the `re-frame.flows` → `re-frame.flows.tooling` JVM-only
            ;; require (rf2-s8w3nw slice-3) and `re-frame.subs` →
            ;; `re-frame.subs.tooling` (rf2-bmzq0 / rf2-gge6mf slice-2).
            #?@(:clj  [[re-frame.routing.tooling :as routing-tooling]])
            #?@(:cljs [[re-frame.views :as views]])))

;; ---- public API re-exports ----------------------------------------------
;; These deliberately match the pre-split surface so consumers
;; (tests, conformance, examples, docs) continue to reach
;; `routing/reg-route`, `routing/match-url`, etc. without import churn.

;; Registry
(def reg-route                  registry/reg-route)
(def clear-route                registry/clear-route)
(def match-url                  registry/match-url)
(def route-url                  registry/route-url)
(def malformed-url?             registry/malformed-url?)
(def reset-counters!            registry/reset-counters!)

;; Static-registry introspection — the `<thing>-ids` + `<thing>-meta`
;; enumerate pair every sibling registry carries (`resource-ids` /
;; `resource-meta`, machines `machines` / `machine-meta`). Owned-ns surface
;; only (no `re-frame.core` re-export), mirroring `route-algebra-view`'s
;; disposition + the machines introspection home.
(def route-ids                  registry/route-ids)
(def route-meta                 registry/route-meta)

;; Scroll (rf2-1hncp2: host-side transient cache, not runtime-db)
(def scroll-positions-cap       scroll/scroll-positions-cap)
(def lookup-scroll-position     scroll/lookup-scroll-position)
(def save-scroll-position       scroll/save-scroll-position)
(def frame-scroll-cache         scroll/frame-scroll-cache)
(def save-scroll-position!      scroll/save-scroll-position!)
(def reset-scroll-cache!        scroll/reset-cache!)

;; Nav counters (rf2-oosjmh: host-side transient high-water marks, not
;; runtime-db — so an epoch restore cannot rewind + recycle a token)
(def counter-snapshot           nav-counters/counter-snapshot)
(def routing-state-classification nav-counters/routing-state-classification)
(def reset-nav-counters!        nav-counters/reset-cache!)

;; Subs
(def route-sub-fn               routing-subs/route-sub-fn)

;; The `sub-route` / `sub-pending-navigation` reactive-read sugar fns that
;; once lived here were REMOVED (rf2-il99l3, reversing rf2-2cmcas). The route
;; and pending-navigation reads are subscription VECTORS —
;; `(subscribe [:rf/route])` / `(subscribe [:rf/pending-navigation])` — one
;; read grammar; `subscribe`'s own frame-first arity carries the `{:frame …}`
;; target for a non-default url-bound frame.

;; EP-0014 slice-5 (rf2-eiiifu): the derivation/process algebra view of
;; registered routes (`route-algebra-view`, static) and a frame's live route
;; slice (`route-slice-algebra-view`, live). JVM-runnable (the route
;; registry is partition-agnostic registration metadata; the live read is a
;; single runtime-db container deref), so a JVM convenience alias lets tools /
;; conformance fixtures reach them as `re-frame.routing/route-algebra-view` /
;; `re-frame.routing/route-slice-algebra-view` without naming the sibling. The
;; bodies live in `re-frame.routing.tooling` so a CLJS app that loads the
;; routing artefact but attaches no tool DCEs them (the CLJS facade never
;; `:require`s the tooling sibling — the require above is `#?@(:clj ...)`-
;; gated). CLJS consumers (Xray + conformance) call
;; `re-frame.routing.tooling/<name>` directly. No `re-frame.core` facade
;; export (EP-0014 issue-1 disposition). Mirrors the
;; `re-frame.flows/flow-algebra-view` JVM alias (slice-3).
#?(:clj
   (do
     (def route-algebra-view       routing-tooling/route-algebra-view)
     (def route-slice-algebra-view routing-tooling/route-slice-algebra-view)))

;; URL-owner resolution
(def url-owner-frame-id         nav-fx/url-owner-frame-id)

;; rf2-3l7xxz: the URL-ownership claim-order state (a process-global vector
;; recording, in claim order, which frames carry :url-bound? true). Reset
;; between tests so a prior test's claim cannot leak; the incumbent-protecting
;; resolution in `url-owner-frame-id` reads it.
(def reset-url-claims!          nav-fx/reset-url-claims!)

;; URL strategies (rf2-aerrz5). The two shipped frame-level `:url-strategy`
;; maps — `history-url-strategy` (default, path-form) + `hash-url-strategy`
;; (`#`-prefixed) — plus the `with-base-path` combinator (rf2-g8pbwg) that
;; wraps either one for an app deployed under a sub-path. Declared on the
;; URL-owning frame:
;;   (rf/reg-frame :app {:url-bound? true :url-strategy routing/hash-url-strategy})
;;   (rf/reg-frame :app {:url-bound? true
;;                       :url-strategy (routing/with-base-path
;;                                       routing/history-url-strategy "/realworld")})
;; Routing-façade home (NOT re-frame.core) so a non-routing app's production
;; bundle carries no strategy code — the routing bundle-isolation invariant.
;; Per Spec 012 §URL strategies.
(def history-url-strategy       strategy/history-url-strategy)
(def hash-url-strategy          strategy/hash-url-strategy)
(def with-base-path             strategy/with-base-path)

;; Browser URL-change listener. rf2-g8pbwg: the imperative
;; `install-url-listener!` / `remove-url-listener!` exports (and their
;; retired `install-history-listener!` / `remove-history-listener!` aliases)
;; are GONE — a `:url-bound? true` frame installs its strategy listener on
;; create and removes it on destroy, automatically (see
;; `re-frame.routing.history`'s ns docstring THE FOLD note and the
;; `:routing/on-frame-registered!` / `:routing/on-frame-destroyed!` wiring
;; below). `current-url` remains the one query-shaped export — reading the
;; current browser URL is a legitimate direct call, not a coupled step of
;; the URL-binding declaration.
(def current-url                history/current-url)

;; Route-link render fns
#?(:cljs (def route-link-render link/route-link-render))
(def route-link-render-ssr      link/route-link-render-ssr)

;; ---- event / fx / sub / hook / listener registrations -------------------
;; Keeping the registrations in this façade means consumers using
;; `(require 're-frame.routing :reload)` to recover from `clear-all!`
;; re-run every wire here.

;; EP-0001 (rf2-3939ig) — the general framework-authority minting key.
;; Routing is one of the legitimate runtime-db writers Spec 002 §Write
;; authority names: every nav / url-change / can-leave / settle handler
;; below reads + returns the reserved `:rf.db/runtime` route slice. Stamping
;; this reserved registration-meta key (Conventions §Reserved registration
;; meta) mints framework-write authority so the runtime's
;; `:rf.warning/app-handler-runtime-effect` ownership diagnostic recognises
;; these as in-bounds writers (it is reserved BY CONVENTION, Mike ruling #4 —
;; not a capability gate). Applied uniformly to every framework-shipped
;; routing event handler so a new routing handler that touches the slice
;; inherits authority by sitting in this façade.
(def ^:private framework-authority-meta {:rf/framework-authority? true})

;; :rf.route.internal/settle-transition — Spec 012 §Per-route data
;; loading §2 FIFO settle. EP-0001 (rf2-vzld77): the route slice is durable
;; runtime-db state, so this is a runtime-db `reg-event` handler.
(events/reg-event :rf.route.internal/settle-transition
                     framework-authority-meta
                     routing-events/settle-transition-handler)

;; :rf.route.internal/on-match-error — Spec 012 §Per-route error
;; handling.
(events/reg-event :rf.route.internal/on-match-error
                     framework-authority-meta
                     on-match-error/on-match-error-handler)

;; On-match error trap — Spec 009 always-on error-emit listener.
;; Per Spec 009 §What IS available in production this survives
;; `:advanced` + `goog.DEBUG=false`.
(error-emit/register-error-listener!
  :rf.route/on-match-error-trap
  on-match-error/on-match-error-listener)

;; :rf.route/nav-allocation + :rf.route/pending-nav-allocation cofx +
;; :rf.route/commit-nav-counter fx — Spec 012 §Navigation tokens (rf2-oosjmh
;; / rf2-vcop6y). The host-side nav-token / pending-nav allocators are minted
;; through TWO RECORDABLE generator-backed coeffects (one per allocator): the
;; generator mints the next id from the host high-water snapshot (READ) and
;; the cofx machinery RECORDS the allocation `{:token/:id .. :counter ..}`
;; onto the causal token, so replay re-presents the SAME id verbatim
;; (rf2-vcop6y replay-determinism fix; the retired ambient
;; `:rf.route/nav-counters` cofx was never recorded → replay re-minted
;; different ids). The fx records the new high-water mark into the host cache
;; with a `max` install (WRITE) so a restore/replay re-establishes the host
;; high-water from the recorded `:counter` and can never rewind the allocator.
;; The counters are host-side TRANSIENT state (`re-frame.routing.nav-counters`)
;; so an epoch restore cannot rewind them and recycle a token (rf2-oosjmh).
;; Registered in the façade so a `:reload` re-wires them on a fresh registrar.
(cofx/reg-cofx :rf.route/nav-allocation
               nav-counters/nav-allocation-cofx-meta
               nav-counters/nav-allocation-cofx)
(cofx/reg-cofx :rf.route/pending-nav-allocation
               nav-counters/pending-nav-allocation-cofx-meta
               nav-counters/pending-nav-allocation-cofx)
(fx/reg-fx :rf.route/commit-nav-counter
           nav-counters/commit-nav-counter-meta
           nav-counters/commit-nav-counter-handler)

;; EP-0017 / rf2-vcop6y: each nav entry point DECLARES the recordable
;; allocation cofx it consumes via `:rf.cofx/requires`. The generator runs at
;; processing-start (router `:live` policy), mints from the host snapshot, and
;; the value is recorded onto the token; strict replay re-presents it (and
;; FAILS on a missing recorded allocation). A two-way nav handler (block vs
;; commit) declares BOTH allocations — both generate eagerly, the not-taken
;; branch's allocation is recorded-but-unused (harmless — the counters are
;; monotone never-recycle allocators, gaps are a documented non-concern).
(def ^:private nav-commit-meta
  ;; navigate / transitioned / handle-url-change can BLOCK (pending-nav id)
  ;; OR COMMIT (nav-token) — declare both recordable allocations.
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf.route/nav-allocation
                            :rf.route/pending-nav-allocation]))
(def ^:private url-requested-meta
  ;; :rf.route/url-requested only ever mints a pending-nav id (on a block); the
  ;; forward push synthesises :rf.route/transitioned, which mints its own
  ;; nav-token. Declare only the pending-nav allocation.
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf.route/pending-nav-allocation]))

;; :rf.route/url-requested + :rf.route/continue + :rf.route/cancel +
;; :rf.route/navigation-blocked — Spec 012 §Navigation blocking —
;; pending-nav protocol. `:rf.route/url-requested` runs the leave guard (which
;; mints a pending-nav id on a block), so it declares the recordable
;; pending-nav allocation cofx; `:rf.route/continue` / `:rf.route/cancel` /
;; `:rf.route/navigation-blocked` never allocate, so they don't.
(events/reg-event :rf.route/url-requested
                     url-requested-meta
                     can-leave/url-requested-handler)
;; EP-0015 (rf2-jfaucw): the runtime dispatches `[:rf.route/navigation-blocked
;; pending-nav]` on every block, carrying the pending-nav map as the event
;; arg. That map carries route CARRIERS — `:requested-url` (a target URL with
;; potential `?token=…` / `#access_token=…`) and `:requested-by-event` (the
;; full original nav event vector, whose args can carry params/query secrets).
;; The dispatched-event TRACE (`:rf.event/dispatched` `:rf.event/v`, and the
;; bare `:event` slot on any error trace) is an egress surface the
;; per-registration marks chokepoint walks: declare those two carrier slots
;; `:sensitive` (paths rooted at the arg-map — the pending-nav map) so
;; `re-frame.classification/project-trace-event` redacts them to `:rf/redacted` on the
;; trace egress copy. The handler / pending-nav sub still see the raw map
;; in-process (marks touch only the trace tags), so continue/cancel resume
;; behaviour is unaffected; the durable runtime-db slot's off-box epoch egress
;; is already fail-closed by the redact-whole-runtime-db default (ruling #14).
(events/reg-event :rf.route/navigation-blocked
                     (assoc framework-authority-meta
                            :sensitive [[:requested-url] [:requested-by-event]])
                     can-leave/navigation-blocked-handler)
;; rf2-p69yaz point 5: `:rf.route/entry-blocked` is the ENTER-block mirror
;; of `:rf.route/navigation-blocked` — the runtime dispatches it (carrying
;; the pending-nav map) when a target route's `:can-enter` guard rejects.
;; It carries the SAME route carriers (`:requested-url`, `:requested-by-event`),
;; so it declares the SAME `:sensitive` slots so `project-trace-event`
;; redacts them on the trace egress copy (EP-0015 rf2-jfaucw).
(events/reg-event :rf.route/entry-blocked
                     (assoc framework-authority-meta
                            :sensitive [[:requested-url] [:requested-by-event]])
                     can-leave/entry-blocked-handler)
(events/reg-event :rf.route/continue
                     framework-authority-meta
                     can-leave/continue-handler)
(events/reg-event :rf.route/cancel
                     framework-authority-meta
                     can-leave/cancel-handler)

;; :rf.route/navigate — Spec 012 §Navigation is an event. Declares both
;; recordable allocation cofx (mints the nav-token on commit + any block's
;; pending-nav id).
(events/reg-event :rf.route/navigate
                     nav-commit-meta
                     navigate/navigate-handler)

;; :rf.route/transitioned + :rf.route/handle-url-change — Spec 012 §URL
;; changes are events. Both declare both recordable allocation cofx (mint the
;; nav-token on commit + any block's pending-nav id).
(events/reg-event :rf.route/transitioned
                     nav-commit-meta
                     url-change/transitioned-handler)
(events/reg-event :rf.route/handle-url-change
                     nav-commit-meta
                     url-change/handle-url-change-handler)

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
(cofx/reg-cofx :rf.route/nav-token
               nav-token/nav-token-cofx-meta
               nav-token/nav-token-cofx)

;; :rf.route/route-id cofx — rf2-ph1grf. The capture-side companion to
;; `:rf.route/nav-token`: delivers the live route id FLAT under
;; `:rf.route/route-id` so an `:on-match`-reached loader declaring
;; `:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]` captures BOTH
;; facts the route-loader work-id `[:rf.work/route route-id nav-token
;; loader-id]` needs at scheduling time — the documented stale-suppression path
;; can no longer thread a nil route id into the work-id tuple. Owner-qualified
;; to the routing subsystem root, like its sibling `:rf.route/nav-token`.
(cofx/reg-cofx :rf.route/route-id
               nav-token/route-id-cofx-meta
               nav-token/route-id-cofx)

;; :rf.route/with-nav-token — Spec 012 §Navigation tokens — stale-result
;; suppression. The test-only `:rf.test/simulate-http-resolution` fixture
;; analogue is NOT wired here — it lives behind an explicit
;; `re-frame.routing.test-support` require so the `:rf.test/*` fixture
;; event never reaches a production registry (rf2-dbiv8, mirrors the
;; managed-HTTP canned-stub gate rf2-cdmle).
(fx/reg-fx :rf.route/with-nav-token
           nav-token/with-nav-token-meta
           nav-token/with-nav-token-handler)

;; :rf.nav/push-url + :rf.nav/replace-url — Spec 012 §Multi-frame
;; routing (rf2-w50qm).
(fx/reg-fx :rf.nav/push-url    nav-fx/push-url-meta    nav-fx/push-url-handler)
(fx/reg-fx :rf.nav/replace-url nav-fx/replace-url-meta nav-fx/replace-url-handler)

;; :rf.nav/scroll + :rf.nav/capture-scroll — Spec 012 §Scroll
;; restoration.
(fx/reg-fx :rf.nav/capture-scroll scroll/capture-scroll-meta scroll/capture-scroll-handler)
(fx/reg-fx :rf.nav/scroll         scroll/scroll-fx-meta      scroll/scroll-fx-handler)

;; :url-bound? exclusivity check — Spec 012 §Multi-frame routing
;; ("Only one frame can own the URL at a time").
;;
;; rf2-25i7r7 (finding 1 — reload idempotence): the registrar's
;; `add-registration-hook!` appends to a process-`defonce` vector with
;; no dedupe (re-frame.registrar §registration-hooks), and `clear-all!`
;; does NOT clear that vector. So the unconditional top-level call this
;; replaced stacked one identical hook per `(require 're-frame.routing
;; :reload)` — the long-established `clear-all!` test-fixture recovery
;; pattern and any REPL hot-reload. N stacked copies meant a single
;; duplicate URL binding emitted N `:rf.error/duplicate-url-binding`
;; diagnostics, and every `:frame` registration paid N× the hook work.
;;
;; The `defonce` guard makes installation idempotent across reloads:
;; `:reload` re-runs every top-level form, but a `defonce` whose var
;; already carries a root binding is a no-op, so the hook is installed
;; exactly once per process. The registrar's hooks vector is itself
;; `defonce` and survives `clear-all!`, so the single installed hook
;; stays live across the fixture's `clear-all!` + `:reload` recovery —
;; no re-install is needed. Mirrors the flows registry's
;; `_hot-reload-hook` defonce wrapper over `add-replacement-hook!`
;; (re-frame.flows.registry, rf2-94ol5) — the same "install a
;; registrar hook once, survive hot-reload" pattern.
;; rf2-68k8as — `add-registration-hook!` is an append-only FUTURE observer; it
;; does NOT replay existing `:frame` registrations (re-frame.registrar
;; §registration-hooks). So a frame that claimed `:url-bound? true` BEFORE this
;; namespace loaded never recorded a claim in `url-claim-order`, leaving
;; `url-owner-frame-id` to its claim-free fallback — which (before rf2-68k8as)
;; id-sorted and let a later, alphabetically-earlier duplicate STEAL the URL
;; from the true first-claimant (Spec 012 §Multi-frame routing forbids exactly
;; this). After installing the hook we therefore reconcile the frames already
;; in the registry: seed the unambiguous incumbent, or fail closed (no claim,
;; one diagnostic per extra binding) when multiple url-bound frames pre-exist
;; and claim order is unrecoverable from the unordered registrar map.
;;
;; The reconcile runs on every façade `:reload` (it is NOT inside the
;; `defonce`, since the registrar's hooks vector + the `url-claim-order` atom
;; survive `clear-all!` but a test's `reset-url-claims!` empties the claim
;; vector — re-seeding the incumbent after reset keeps the resolver correct).
;; It is idempotent: `record-url-claim!` appends-iff-absent and the
;; multi-binding branch records no claim, so a redundant call is a no-op.
(defonce ^:private _url-bound-exclusivity-hook
  (registrar/add-registration-hook! url-bound/check-url-bound-exclusivity!))
(url-bound/reconcile-existing-url-bindings!)

;; Framework-shipped subs over the route slice — Spec 012.
;; EP-0001 (rf2-vzld77): the route slice is durable framework runtime-db
;; state, so `:rf/route` and `:rf/pending-navigation` are `:runtime-db` subs
;; (read the runtime-db projection); the `:rf.route/*` derived subs chain off
;; `:rf/route` unchanged.
(subs/reg-runtime-sub :rf/route
  {:doc "Subscribe to the current route slice `{:route-id :params :query :transition :error :fragment :nav-token}`. Layer-1-shaped read of the route slice at `[:rf.runtime/routing :current]` in the runtime-db partition — the only sibling runtime-db key is `:pending-navigation` (its own `:rf/pending-navigation` sub), so the slice carries only the published shape and the sub returns it directly. The nav-token / pending-nav counters are NOT runtime-db siblings — like scroll positions they live in host-side transient caches (rf2-oosjmh / rf2-1hncp2). Per Spec 012."}
  route-sub-fn)
(subs/reg-sub :rf.route/id   ;; sub-id stays `:rf.route/id`; reads the slice's `:route-id` key (rf2-3a5nk7)
  {:doc "Subscribe to the current route's id keyword (the slice's `:route-id` key). Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:route-id route)))
(subs/reg-sub :rf.route/params
  {:doc "Subscribe to the current route's path params map. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:params route)))
(subs/reg-sub :rf.route/query
  {:doc "Subscribe to the current route's query params map. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:query route)))
(subs/reg-sub :rf.route/transition
  {:doc "Subscribe to the current route's `:transition` state. Per Spec 012 §Route transitions."}
  :<- [:rf/route] (fn [route _] (:transition route)))
(subs/reg-sub :rf.route/error
  {:doc "Subscribe to the current route's `:error` (nil when no error). Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:error route)))
(subs/reg-sub :rf.route/fragment
  {:doc "Subscribe to the current route's URL `#fragment` (string or nil). Per Spec 012 §Fragments."}
  :<- [:rf/route] (fn [route _] (:fragment route)))
(subs/reg-sub :rf.route/chain
  {:doc "Subscribe to the `:parent`-chain of the active route, returned
  as a vector `[parent-most ... current]`. Per Spec 012 §Nested layouts."}
  :<- [:rf.route/id] (fn [id _] (routing-subs/chain-from-meta id)))
(subs/reg-runtime-sub :rf/pending-navigation
  {:doc "Subscribe to the pending-navigation slot at
  `[:rf.runtime/routing :pending-navigation]` in the runtime-db partition
  (nil when no navigation is pending). Per Spec 012 §Navigation blocking —
  pending-nav protocol."}
  routing-subs/pending-navigation-sub-fn)

;; :route/link registered view — Spec 012 §Linking from views.
;; Exposed on both platforms so .cljc render trees resolve identically
;; server- and client-side.
#?(:cljs
   (def route-link
     "Registered view at `:route/link`. Intercepts plain left-clicks and
     dispatches `:rf.route/url-requested`; modifier-key clicks defer to the
     browser. Per Spec 012 §Linking from views and API.md `route-link`
     row. The underlying render fn is `route-link-render`."
     (views/reg-view* :route/link
                      (source-coords/merge-coords {})
                      link/route-link-render))
   :clj
   (registrar/register! :view :route/link
                        (assoc (source-coords/merge-coords {})
                               :handler-fn link/route-link-render-ssr)))

;; ---- late-bind hook registration ------------------------------------------
;; Per rf2-k682. `re-frame.core` MUST NOT `:require [re-frame.routing]` —
;; the artefact is optional. Public-API re-exports are published through
;; the late-bind table; consumers without the artefact see the hooks
;; unregistered and the active surfaces throw cleanly.

(late-bind/set-fn! :routing/reg-route          reg-route)
(late-bind/set-fn! :routing/clear-route        clear-route)
(late-bind/set-fn! :routing/match-url          match-url)
(late-bind/set-fn! :routing/route-url          route-url)
(late-bind/set-fn! :routing/reset-counters!    reset-counters!)
;; rf2-oosjmh: drop the host-side nav-token / pending-nav counter
;; high-water marks. Published as a reset hook so the shared CLJS
;; `make-reset-runtime-fixture` reset-hooks table clears them per test
;; (they are host-side transient state now, not cleared by the `frames`
;; reset). No-op when re-frame.routing is absent (the artefact is optional).
(late-bind/set-fn! :routing/reset-nav-counters! reset-nav-counters!)
;; rf2-3l7xxz: drop the process-global URL-ownership claim-order vector
;; (re-frame.routing.nav-fx/url-claim-order). Like the nav-counters it is
;; process-global state not touched by a runtime/frames reset, so the shared
;; CLJS make-reset-runtime-fixture reset-hooks table fires this per test so a
;; prior test's URL-ownership claim cannot leak into the next (test isolation).
;; No-op when re-frame.routing is absent (the artefact is optional).
(late-bind/set-fn! :routing/reset-url-claims!  reset-url-claims!)
(late-bind/set-fn! :routing/route-sub-fn       route-sub-fn)
(late-bind/set-fn! :routing/current-url        current-url)

;; rf2-mtzv5m: route-sub egress projection. A route's projection-relative
;; `:sensitive` / `:large` classification is lowered (re-rooted under
;; `[:rf.runtime/routing :current …]`) into the per-frame elision registry at
;; activation, but the BARE route slice the `:rf/route` / `:rf.route/query` /
;; `:rf.route/params` subs return is walked at the WHOLE-VALUE root, so the
;; re-rooted absolute decls never match and a classified query / param shipped
;; RAW on the trace / off-box read surfaces. Publish the seed-path resolver +
;; the projector so (a) the core wire walker re-seeds a route sub value at its
;; runtime-db storage position (via `elide-wire-value`'s `:query-v` opt — the
;; chokepoint the MCP read-sub / list-subscriptions / snapshot / Xray surfaces
;; share) and (b) the trace `project-sub-tags` chokepoint redacts the
;; `:rf.sub/run` `:rf.sub/value` / `:rf.sub/prev-value` slots. Core consults
;; the hook keys with NO static `:require` on routing (rf2-k682); absent the
;; artefact the hooks are unbound and there is no route slice to leak.
(late-bind/set-fn! :routing/route-sub-egress-path    sub-egress/route-sub-seed-path)
(late-bind/set-fn! :routing/project-route-sub-egress sub-egress/project-route-sub-egress)

;; rf2-1hncp2 + rf2-oosjmh: release the destroyed frame's host-side
;; transient routing caches — the scroll-position cache AND the nav-counter
;; high-water marks. `frame/destroy-frame!` invokes this single hook by key
;; (no static dep on routing — the artefact is optional). Analogous to the
;; ssr / machines / flows / schemas per-frame teardown hooks; without it a
;; long-running multi-frame / per-request-frame process would leak one
;; entry per destroyed frame in each host cache. Composed here (the single
;; late-bind key carries one fn) so adding a new host cache is a one-line
;; addition to this teardown.
(defn- release-routing-host-caches!
  "Release ALL of the destroyed frame's host-side transient routing caches
  (scroll positions + nav-counter high-water marks), tear down its browser
  URL-change listener (rf2-g8pbwg, when it held the URL), AND drop any
  URL-ownership claim it held (rf2-3l7xxz — a destroyed owner relinquishes
  the URL, so ownership re-resolves to the next live claimant). The
  `:routing/on-frame-destroyed!` teardown body."
  [frame-id]
  (scroll/release-frame! frame-id)
  (nav-counters/release-frame! frame-id)
  ;; rf2-g8pbwg: tear down the browser URL-change listener BEFORE dropping
  ;; the claim below — the equality check needs `url-owner-frame-id` to still
  ;; resolve `frame-id` as the (about-to-be-former) owner; once the claim
  ;; drops, ownership may already have moved to the next claimant. A
  ;; non-owner frame's destroy is a no-op here (its `:url-bound? true`, if
  ;; any, was a losing duplicate that never installed a listener). CLJS-only
  ;; (the JVM build never installs a listener to remove).
  #?(:cljs
     (when (= frame-id (nav-fx/url-owner-frame-id))
       (history/remove-url-listener!)))
  (nav-fx/drop-url-claim! frame-id)
  nil)

(late-bind/set-fn! :routing/on-frame-destroyed! release-routing-host-caches!)

;; Browser URL-change wiring (popstate / hashchange → url-owner frame),
;; strategy-aware (rf2-aerrz5), driven automatically by the `:url-bound?`
;; frame LIFECYCLE (rf2-g8pbwg) — there is no imperative
;; install-url-listener! / install-history-listener! / remove-url-listener! /
;; remove-history-listener! export; see `re-frame.routing.history`'s ns
;; docstring THE FOLD note. CLJS-only; the JVM build has no `window` so
;; `on-frame-registered!` is not defined there (SSR feeds the request URL via
;; `:rf.route/handle-url-change`).
;;
;; `:routing/on-frame-registered!` — invoked by `re-frame.frame/reg-frame`
;; AFTER the frame container exists (first registration: after
;; `:initial-events` ran; re-registration: after the surgical config update)
;; — (re)installs the listener when the just-(re)registered frame is the
;; resolved URL owner. A losing duplicate `:url-bound? true` registration
;; never installs (Codex correction: fail loud via the existing
;; `:rf.error/duplicate-url-binding`, never install the losing strategy).
;;
;; `:routing/reset-url-listener!` — test-isolation hook consumed by the
;; shared `make-reset-runtime-fixture` reset-hook table: a raw `frame/frames`
;; reset does not run `destroy-frame!`'s teardown chain, so without this a
;; leftover installed listener would survive into the next test. Reuses
;; `remove-url-listener!` — unconditional teardown is exactly what a full
;; reset needs.
#?(:cljs (late-bind/set-fn! :routing/on-frame-registered! history/on-frame-registered!))
#?(:cljs (late-bind/set-fn! :routing/reset-url-listener!  history/remove-url-listener!))

;; route-link is exposed on both platforms so .cljc render trees
;; resolve identically server- and client-side.
#?(:cljs (late-bind/set-fn! :routing/route-link route-link)
   :clj  (late-bind/set-fn! :routing/route-link route-link-render-ssr))
