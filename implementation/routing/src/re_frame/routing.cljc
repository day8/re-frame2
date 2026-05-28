(ns re-frame.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route). Navigation is an event;
  URL changes are events. The :rf/route slice carries
  {:id :params :query :fragment :transition :error :nav-token}.

  This namespace is the **public boot point and façade** for the
  routing artefact (per rf2-k682 + the skill docs): apps boot the
  artefact with `(:require [re-frame.routing])`. Doing so transitively
  loads every concern sibling under `re-frame.routing.*` (each owns
  one cohesive concern, see below) and runs the registrations
  (`reg-event-fx` / `reg-fx` / `reg-sub` / `add-registration-hook!` /
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
  - `re-frame.routing.scroll`         — scroll-restoration helpers + :rf.nav/scroll fxs
  - `re-frame.routing.events`         — shared nav-event helpers + :rf.route.internal/settle-transition
  - `re-frame.routing.on-match-error` — :on-match error trap + listener
  - `re-frame.routing.can-leave`      — :can-leave gate + pending-nav protocol + :rf/url-requested
  - `re-frame.routing.nav-token`      — :rf.route/with-nav-token + stale-suppression fx
  - `re-frame.routing.navigate`       — :rf.route/navigate event
  - `re-frame.routing.url-change`     — :rf.route/transitioned + :rf.route/handle-url-change
  - `re-frame.routing.nav-fx`         — :rf.nav/push-url + :rf.nav/replace-url + url-owner-frame-id
  - `re-frame.routing.url-bound`      — :url-bound? exclusivity registration hook
  - `re-frame.routing.history`        — popstate listener install/remove + current-url
  - `re-frame.routing.subs`           — framework-shipped subs over the slice
  - `re-frame.routing.link`           — :route/link registered view"
  (:require [re-frame.error-emit :as error-emit]
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
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.nav-token :as nav-token]
            [re-frame.routing.navigate :as navigate]
            [re-frame.routing.on-match-error :as on-match-error]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.scroll :as scroll]
            [re-frame.routing.subs :as subs-ns]
            [re-frame.routing.url-bound :as url-bound]
            [re-frame.routing.url-change :as url-change]
            #?@(:cljs [[re-frame.views :as views]])))

;; ---- public API re-exports ----------------------------------------------
;; These deliberately match the pre-split surface so consumers
;; (tests, conformance, examples, docs) continue to reach
;; `routing/reg-route`, `routing/match-url`, etc. without import churn.

;; Registry
(def reg-route                  registry/reg-route)
(def unregister-route!          registry/unregister-route!)
(def match-url                  registry/match-url)
(def route-url                  registry/route-url)
(def malformed-url?             registry/malformed-url?)
(def reset-counters!            registry/reset-counters!)
(def default-max-decoded-keys   registry/default-max-decoded-keys)

;; Scroll
(def scroll-positions-cap       scroll/scroll-positions-cap)
(def lookup-scroll-position     scroll/lookup-scroll-position)
(def save-scroll-position       scroll/save-scroll-position)

;; Subs
(def route-sub-fn               subs-ns/route-sub-fn)

;; URL-owner resolution
(def url-owner-frame-id         nav-fx/url-owner-frame-id)

;; Browser history (CLJS-only; `:require`-able from .cljc boot)
(def current-url                history/current-url)
#?(:cljs (def install-history-listener! history/install-history-listener!))
#?(:cljs (def remove-history-listener!  history/remove-history-listener!))

;; Route-link render fns
#?(:cljs (def route-link-render link/route-link-render))
(def route-link-render-ssr      link/route-link-render-ssr)

;; ---- event / fx / sub / hook / listener registrations -------------------
;; Keeping the registrations in this façade means consumers using
;; `(require 're-frame.routing :reload)` to recover from `clear-all!`
;; re-run every wire here.

;; :rf.route.internal/settle-transition — Spec 012 §Per-route data
;; loading §2 FIFO settle.
(events/reg-event-db :rf.route.internal/settle-transition
                     routing-events/settle-transition-handler)

;; :rf.route.internal/on-match-error — Spec 012 §Per-route error
;; handling.
(events/reg-event-fx :rf.route.internal/on-match-error
                     on-match-error/on-match-error-handler)

;; On-match error trap — Spec 009 always-on error-emit listener.
;; Per Spec 009 §What IS available in production this survives
;; `:advanced` + `goog.DEBUG=false`.
(error-emit/register-error-listener!
  :rf.route/on-match-error-trap
  on-match-error/on-match-error-listener)

;; :rf/url-requested + :rf.route/continue + :rf.route/cancel +
;; :rf.route/navigation-blocked — Spec 012 §Navigation blocking —
;; pending-nav protocol.
(events/reg-event-fx :rf/url-requested
                     can-leave/url-requested-handler)
(events/reg-event-fx :rf.route/navigation-blocked
                     can-leave/navigation-blocked-handler)
(events/reg-event-fx :rf.route/continue
                     can-leave/continue-handler)
(events/reg-event-fx :rf.route/cancel
                     can-leave/cancel-handler)

;; :rf.route/navigate — Spec 012 §Navigation is an event.
(events/reg-event-fx :rf.route/navigate
                     navigate/navigate-handler)

;; :rf.route/transitioned + :rf.route/handle-url-change — Spec 012 §URL
;; changes are events.
(events/reg-event-fx :rf.route/transitioned
                     url-change/transitioned-handler)
(events/reg-event-fx :rf.route/handle-url-change
                     url-change/handle-url-change-handler)

;; :rf.test/simulate-http-resolution + :rf.route/with-nav-token — Spec
;; 012 §Navigation tokens — stale-result suppression.
(events/reg-event-fx :rf.test/simulate-http-resolution
                     nav-token/simulate-http-resolution-handler)
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
(registrar/add-registration-hook! url-bound/check-url-bound-exclusivity!)

;; Framework-shipped subs over the `:rf/route` slice — Spec 012.
(subs/reg-sub :rf/route
  {:doc "Subscribe to the current route slice `{:id :params :query :transition :error :fragment :nav-token}`. Layer-1 read of the `:rf/route` slice — internal routing-runtime keys nested under `:rf/route` in app-db (`:scroll-positions`, `:nav-token-counter`, …) do not surface through this sub (rf2-xak8u). Per Spec 012."}
  route-sub-fn)
(subs/reg-sub :rf.route/id
  {:doc "Subscribe to the current route's `:id` keyword. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:id route)))
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
  :<- [:rf.route/id] (fn [id _] (subs-ns/chain-from-meta id)))
(subs/reg-sub :rf/pending-navigation
  {:doc "Subscribe to the `:rf/pending-navigation` slot (nil when no
  navigation is pending). Per Spec 012 §Navigation blocking — pending-nav
  protocol."}
  subs-ns/pending-navigation-sub-fn)

;; :route/link registered view — Spec 012 §Linking from views.
;; Exposed on both platforms so .cljc render trees resolve identically
;; server- and client-side.
#?(:cljs
   (def route-link
     "Registered view at `:route/link`. Intercepts plain left-clicks and
     dispatches `:rf/url-requested`; modifier-key clicks defer to the
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
(late-bind/set-fn! :routing/unregister-route!  unregister-route!)
(late-bind/set-fn! :routing/match-url          match-url)
(late-bind/set-fn! :routing/route-url          route-url)
(late-bind/set-fn! :routing/reset-counters!    reset-counters!)
(late-bind/set-fn! :routing/route-sub-fn       route-sub-fn)
(late-bind/set-fn! :routing/current-url        current-url)

;; Browser-history wiring (popstate → url-owner frame). CLJS-only; the
;; JVM build has no `window` so the install/remove fns are not defined
;; there (SSR feeds the request URL via `:rf.route/handle-url-change`).
#?(:cljs (late-bind/set-fn! :routing/install-history-listener! install-history-listener!))
#?(:cljs (late-bind/set-fn! :routing/remove-history-listener!  remove-history-listener!))

;; route-link is exposed on both platforms so .cljc render trees
;; resolve identically server- and client-side.
#?(:cljs (late-bind/set-fn! :routing/route-link route-link)
   :clj  (late-bind/set-fn! :routing/route-link route-link-render-ssr))
