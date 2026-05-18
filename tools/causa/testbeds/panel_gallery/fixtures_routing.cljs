(ns panel-gallery.fixtures-routing
  "Pure fixture builders for the Causa Routing tab gallery (rf2-nrbs9).

  The Routing panel reads:

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

  Variants:

    1. no-routes-registered (silent)
    2. current-route-only (◆ HERE)
    3. from-to-transition (◆ FROM / ◆ TO)
    4. nested-route-tree (depth 3+)")

;; ---- route registrar fixtures -------------------------------------------

(def cart-routes
  "Shallow e-commerce route set — exercises depth 0 / 1 / 2 paths and
  the current/from/to lens."
  {:route/root      {:path "/"                  :doc "home"}
   :route/cart      {:path "/cart"              :doc "shopping cart"}
   :route/checkout  {:path "/checkout"          :doc "checkout overview"}
   :route/payment   {:path "/checkout/payment"  :doc "payment step"}
   :route/confirm   {:path "/checkout/confirm"  :doc "confirmation"}
   :route/admin     {:path "/admin"             :doc "admin landing"}
   :route/audit     {:path "/admin/audit"       :doc "admin audit log"}
   :route/not-found {:path "/404"               :doc "fallback"}})

(def deep-routes
  "Deeper nesting exercises the indentation behaviour at depth 3+."
  {:route/root          {:path "/"}
   :route/docs          {:path "/docs"                              :doc "docs landing"}
   :route/docs.guide    {:path "/docs/guide"                        :doc "guide section"}
   :route/docs.api      {:path "/docs/api"                          :doc "API reference"}
   :route/docs.api.subs {:path "/docs/api/subs"                     :doc "subs API"}
   :route/docs.api.evts {:path "/docs/api/events"                   :doc "events API"}
   :route/docs.api.evts.detail {:path "/docs/api/events/detail"     :doc "single event detail"}
   :route/docs.guide.routing   {:path "/docs/guide/routing"         :doc "routing chapter"}
   :route/blog          {:path "/blog"                              :doc "blog index"}
   :route/blog.post     {:path "/blog/post"                         :doc "single post"}})

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
  {:id     :route/docs.api.evts.detail
   :params {:event-id "user/login"}
   :query  {:tab :timeline}})

;; ---- trace-buffer fixtures (drive FROM/TO detection) -------------------

(defn- nav-allocated-trace
  "Build a `:rf.route.nav-token/allocated` trace event mirroring the
  shape `(trace/emit! :event :rf.route.nav-token/allocated ...)`
  produces in `re-frame.routing`."
  [id dispatch-id route-id nav-token]
  {:id        id
   :op-type   :event
   :operation :rf.route.nav-token/allocated
   :tags      {:dispatch-id dispatch-id
               :route-id    route-id
               :nav-token   nav-token}})

(defn- event-dispatched-trace
  "Build an `:event/dispatched` trace event — the cascade root."
  [id dispatch-id event-vec]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :tags      {:dispatch-id dispatch-id
               :event       event-vec}})

(defn nav-buffer
  "Trace buffer carrying one cascade that navigates to `to-route`.
  Returns a vector shaped exactly as `re-frame.trace.projection/
  group-cascades` consumes."
  [dispatch-id to-route nav-token]
  [(event-dispatched-trace 1 dispatch-id [:rf.route/navigate to-route])
   (nav-allocated-trace 2 dispatch-id to-route nav-token)])

(defn no-nav-buffer
  "Trace buffer carrying one cascade that does NOT navigate. Used by
  the orientation variants where only `◆ HERE` should render."
  [dispatch-id event-vec]
  [(event-dispatched-trace 1 dispatch-id event-vec)])
