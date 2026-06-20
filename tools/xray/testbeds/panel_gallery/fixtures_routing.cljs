(ns panel-gallery.fixtures-routing
  "Pure fixture builders for the Xray Routes tab gallery.

  The Routes panel reads:

    - `:rf.xray/registered-routes` — defaults to `(rf/registrations
      :route)`; test override slot exists at
      `:rf.xray/set-registered-routes-override-for-test`.
    - `:rf.xray/current-route-slice` — defaults to the target-frame
      runtime-db's `[:rf.runtime/routing :current]` slice; test override
      slot at `:rf.xray/set-current-route-slice-override-for-test`.
    - `:rf.xray/cascades` — drives the FROM/TO detection. Seed via
      `:rf.xray/sync-trace-buffer` + `:rf.xray/focus-cascade` so
      the spine's focused cascade carries the
      `:rf.route.nav-token/allocated` emit.
    - `:rf.xray.routing/query` + `:rf.xray.routing/sim-url` —
      UI-state slots driven by `:rf.xray.routing/set-query` and
      `:rf.xray.routing/set-sim-url` respectively.

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
  {:route-id :route/cart
   :params   {}
   :query    {}})

(def confirm-slice
  {:route-id :route/confirm
   :params   {:order-id "ord-1234"}
   :query    {:source "cart"}
   :fragment "step-3"})

(def docs-api-detail-slice
  {:route-id :route/docs.api.detail
   :params   {:event-id "user/login"}
   :query    {:tab :timeline}})

;; ---- trace-buffer fixtures (drive FROM/TO detection) -------------------

(defn- nav-allocated-trace
  [id dispatch-id route-id nav-token]
  {:id        id
   :op-type   :rf.event
   :operation :rf.route.nav-token/allocated
   :tags      {:rf.trace/dispatch-id dispatch-id
               :route-id    route-id
               :nav-token   nav-token}})

(defn- event-dispatched-trace
  [id dispatch-id event-vec]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.trace/dispatch-id dispatch-id
               :rf.event/v       event-vec}})

(defn- deactivated-trace
  "The runtime's `:rf.route/deactivated` lifecycle emit for the PRIOR
  route on a cross-route nav. Its `:tags :route-id` is the FROM — the
  panel reads FROM off this emit, not the live slice."
  [id dispatch-id route-id]
  {:id        id
   :op-type   :rf.event
   :operation :rf.route/deactivated
   :tags      {:rf.trace/dispatch-id dispatch-id
               :route-id    route-id}})

(defn nav-buffer
  "Trace buffer carrying one cascade that navigates to `to-route`.

  The 4-arity threads a `from-route`: the runtime emits
  `:rf.route/deactivated` for the prior route on a cross-route nav, and
  the panel derives FROM from that emit (NOT the live slice),
  so the FROM ◆ glyph requires the deactivated trace to be present."
  ([dispatch-id to-route nav-token]
   [(event-dispatched-trace 1 dispatch-id [:rf.route/navigate to-route])
    (nav-allocated-trace 2 dispatch-id to-route nav-token)])
  ([dispatch-id to-route nav-token from-route]
   [(event-dispatched-trace 1 dispatch-id [:rf.route/navigate to-route])
    (nav-allocated-trace 2 dispatch-id to-route nav-token)
    (deactivated-trace 3 dispatch-id from-route)]))

(defn no-nav-buffer
  "Trace buffer carrying one cascade that does NOT navigate."
  [dispatch-id event-vec]
  [(event-dispatched-trace 1 dispatch-id event-vec)])
