(ns panel-gallery.fixtures-routing
  "Pure fixture builders for the Causa Routes tab gallery (rf2-nrbs9,
  reshaped per rf2-lq0ef).

  The Routes panel reads:

    - `:rf.causa/registered-routes` — defaults to `(rf/registrations
      :route)`; test override slot exists at
      `:rf.causa/set-registered-routes-override-for-test`.
    - `:rf.causa/current-route-slice` — defaults to the target-frame
      app-db's `:rf/route` slot; test override slot at
      `:rf.causa/set-current-route-slice-override-for-test`.
    - `:rf.causa/cascades` — drives the FROM/TO detection. Seed via
      `:rf.causa/sync-trace-buffer` + `:rf.causa/focus-cascade` so
      the spine's focused cascade carries the
      `:rf.route.nav-token/allocated` emit.
    - `:rf.causa.routing/query` + `:rf.causa.routing/sim-url` —
      UI-state slots driven by `:rf.causa.routing/set-query` and
      `:rf.causa.routing/set-sim-url` respectively.

  Variants:

    1. no-routes-registered (silent)
    2. current-route-only (◆ HERE)
    3. from-to-transition (◆ FROM / ◆ TO)
    4. search-filter (substring search exercises the catalogue)
    5. simulate-url-winner (paste URL → see ranked candidates)")

;; ---- route registrar fixtures -------------------------------------------

(def cart-routes
  "Shallow e-commerce route set — exercises basic catalogue rendering
  plus the metadata badges (`:on-match`, `:can-leave`, `:tags`,
  `:parent`)."
  {:route/root      {:path "/"                  :doc "home"}
   :route/cart      {:path "/cart"              :doc "shopping cart"
                     :tags #{:public}}
   :route/checkout  {:path "/checkout"          :doc "checkout overview"
                     :can-leave :guard/checkout-dirty?}
   :route/payment   {:path "/checkout/payment"  :doc "payment step"
                     :parent :route/checkout
                     :on-match [:payment/load]}
   :route/confirm   {:path "/checkout/confirm"  :doc "confirmation"
                     :parent :route/checkout}
   :route/admin     {:path "/admin"             :doc "admin landing"
                     :tags #{:admin}}
   :route/audit     {:path "/admin/audit"       :doc "admin audit log"
                     :parent :route/admin
                     :on-match [:audit/load]}
   :route/not-found {:path "/404"               :doc "fallback"}})

(def docs-routes
  "Larger registrar — exercises the substring search filter at a
  scale where the catalogue starts to need narrowing."
  {:route/root            {:path "/"}
   :route/docs            {:path "/docs"                       :doc "docs landing"}
   :route/docs.guide      {:path "/docs/guide"                 :doc "guide section"}
   :route/docs.api        {:path "/docs/api"                   :doc "API reference"}
   :route/docs.api.subs   {:path "/docs/api/subs"              :doc "subs API"}
   :route/docs.api.evts   {:path "/docs/api/events"            :doc "events API"}
   :route/docs.api.detail {:path "/docs/api/events/detail"     :doc "single event detail"}
   :route/docs.routing    {:path "/docs/guide/routing"         :doc "routing chapter"}
   :route/blog            {:path "/blog"                       :doc "blog index"}
   :route/blog.post       {:path "/blog/post"                  :doc "single post"}
   :route/blog.tag        {:path "/blog/tag/:tag"              :doc "blog tag"}
   :route/blog.splat      {:path "/blog/*rest"                 :doc "blog wildcard"}})

;; ---- route-slice fixtures ----------------------------------------------

(def cart-slice
  {:id     :route/cart
   :params {}
   :query  {}})

(def confirm-slice
  {:id       :route/confirm
   :params   {:order-id "ord-1234"}
   :query    {:source "cart"}
   :fragment "step-3"})

(def docs-api-detail-slice
  {:id     :route/docs.api.detail
   :params {:event-id "user/login"}
   :query  {:tab :timeline}})

;; ---- trace-buffer fixtures (drive FROM/TO detection) -------------------

(defn- nav-allocated-trace
  [id dispatch-id route-id nav-token]
  {:id        id
   :op-type   :event
   :operation :rf.route.nav-token/allocated
   :tags      {:dispatch-id dispatch-id
               :route-id    route-id
               :nav-token   nav-token}})

(defn- event-dispatched-trace
  [id dispatch-id event-vec]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :tags      {:dispatch-id dispatch-id
               :event       event-vec}})

(defn nav-buffer
  "Trace buffer carrying one cascade that navigates to `to-route`."
  [dispatch-id to-route nav-token]
  [(event-dispatched-trace 1 dispatch-id [:rf.route/navigate to-route])
   (nav-allocated-trace 2 dispatch-id to-route nav-token)])

(defn no-nav-buffer
  "Trace buffer carrying one cascade that does NOT navigate."
  [dispatch-id event-vec]
  [(event-dispatched-trace 1 dispatch-id event-vec)])
