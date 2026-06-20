(ns re-frame.resources-route-infinite-blocking-cljs-test
  "Route ↔ INFINITE-feed BLOCKING integration (rf2-kz90ep, EP-0021 wave 7
  coverage gap, Spec 016 §Route integration + §Infinite resources and
  load-more feeds). Cross-host (JVM + CLJS) so the routing/resources seam
  behaves identically server- and client-side.

  The scalar route-blocking surface (`resources_route_cljs_test.cljc`)
  thoroughly covers a BLOCKING SCALAR resource: it holds the transition
  `:loading`, drains `:idle` on success, and a blocking FIRST-load FAILURE
  flips the route to `:error`. But a route blocking an INFINITE feed drains
  through a DIFFERENT path — the PAGE reply handlers
  (`:rf.resource.internal/page-succeeded` / `…/page-failed`), NOT the scalar
  succeeded/failed handlers — and an infinite feed has a CRUCIAL divergence:
  it has ONE error axis (`:page-error` + `:loaded`), unlike a scalar resource
  whose first-load failure is `:error`. The flagship example test
  (`infinite_feed_example_cljs_test`) navigates a `:blocking? true` infinite
  route but only asserts the entry `:status` / view `:loading?` — it never
  reads the route blocking-slot or asserts the route TRANSITION completes.

  THE CONTRACT this pins (rf2-byl7bk.3.1 — spec-correct first-load semantics,
  superseding the rf2-kz90ep characterisation that pinned the divergence):

    1. a blocking infinite route holds the transition `:loading` on PAGE 0
       in flight, and DRAINS (transition → `:idle`) when page-0 SUCCEEDS via
       the PAGE reply handler (`entry-replace-page` append path), NOT the
       scalar succeeded-handler;

    2. a blocking page-0 FIRST-load FAILURE (no accumulated pages) uses the
       FIRST-LOAD `:error` channel, NOT the load-more `:page-error` channel:
       Spec 016 reserves `:error` / `:status :error` for first-load (page 0)
       failure and `:page-error` for load-more (page N>0) failure only. So a
       blocking infinite route whose required page 0 fails flips to route
       `:error` (the feed settles `:status :error`, `:error` envelope, no
       data) — EXACTLY like a blocking SCALAR resource, restoring parity. The
       rf2-kz90ep test pinned the OPPOSITE (`:loaded` + `:page-error` + route
       `:idle`), which tested the implementation against itself, not against
       Spec 016; this fix inverts it;

    3. a LOAD-MORE (page N>0) FAILURE with accumulated pages still uses the
       `:page-error` channel: the feed stays `:loaded`, KEEPS all pages, and
       the route (already drained on page-0 success) is undisturbed — the
       third error channel is intact for the case the spec actually reserves
       it for.

  The capturing transport REPLAYS the live managed-HTTP reply-append shape
  (the transport conj's its result as the LAST arg of the internal reply
  event — Spec 014 §Reply addressing), so the PAGE reply handlers run against
  the genuine 3-element event addressed at `:rf.resource.internal/page-*`.

  Named `*-cljs-test.cljc` so it is discovered by BOTH the JVM runner
  (`.*-test$`) and the shadow-cljs `:node-test` build (`cljs-test$`)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the routing + resources
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as route]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   [re-frame.routing :as routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.test-support :as core-test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- capturing transport (REPLAYS the live page reply-append shape) -------

(def ^:private last-managed-args (atom nil))

(defn- init!
  "Per-test setup (after adapter install, registrar live): re-register
  `:rf/default` as the URL-owning app frame, reset the routing counters,
  re-publish the late-bound routing integration, and stub managed-HTTP (a
  CAPTURING stub, so page-0's reply can be driven) + push-url.

  The resources host-side caches are cleared by the shared
  `make-reset-runtime-fixture`'s post-dispose hook BEFORE this `:init-fn`."
  []
  (reset! last-managed-args nil)
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "Route-infinite-blocking suite default app frame."})
  (routing/reset-counters!)
  (route/install-routing-integration!)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- slice []
  (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current]))

(defn- entry [scoped-key]
  (get-in (rf/runtime-db-value :rf/default) (state/entry-path scoped-key)))

(defn- blocking-slot [nav-token]
  (get-in (rf/runtime-db-value :rf/default) (route/blocking-path nav-token)))

(def ^:private next-cursor
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(defn- page
  "An enveloped page: items + a page-info cursor envelope."
  [items next-c]
  {:items items :page-info {:next-cursor next-c}})

(defn- feed-spec
  "A minimal valid infinite-feed resource spec (global scope, cursor
  pagination via :page-info)."
  [overrides]
  (merge {:scope           :rf.scope/global
          :infinite        true
          :params-schema   [:map [:slug :string]]
          :request         (fn [{:keys [slug]} {:rf.resource/keys [page-param page-index]}]
                             {:request {:method :get :url (str "/api/feed/" slug)
                                        :params (cond-> {:page-index page-index}
                                                  page-param (assoc :cursor page-param))}})
          :next-page-param next-cursor
          :page->items     :items
          :tags            (fn [{:keys [slug]} _data] #{[:feed slug]})}
         overrides))

(defn- feed-key [slug]
  (state/scoped-resource-key :rf.scope/global :feed/articles {:slug slug}))

(defn- reply-page-success!
  "Dispatch the captured page reply's `:on-success` with the transport's
  success result appended as the LAST arg — the live page reply shape
  (addressed at `:rf.resource.internal/page-succeeded`)."
  [pg]
  (let [args @last-managed-args]
    (is (= :rf.resource.internal/page-succeeded (first (:on-success args)))
        "page-0 of an infinite feed is addressed at the PAGE success handler")
    (rf/dispatch-sync (conj (:on-success args) {:kind :success :value pg}))))

(defn- reply-page-failure!
  "Dispatch the captured page reply's `:on-failure` with the transport's
  failure envelope appended as the LAST arg — the live page reply shape
  (addressed at `:rf.resource.internal/page-failed`)."
  [failure]
  (let [args @last-managed-args]
    (is (= :rf.resource.internal/page-failed (first (:on-failure args)))
        "page-0 of an infinite feed is addressed at the PAGE failure handler")
    (rf/dispatch-sync (conj (:on-failure args) {:kind :failure :failure failure}))))

(defn- register-blocking-infinite-route! []
  (rf/reg-resource :feed/articles (feed-spec {}))
  (rf/reg-route :route/feed
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :feed/articles
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/feed/:slug"))

;; ===========================================================================
;; 1. blocking infinite route holds :loading on page 0, DRAINS on page-0 success
;; ===========================================================================

(deftest blocking-infinite-route-holds-then-drains-on-page-0-success
  ;; rf2-kz90ep (1): the blocking drain on an infinite feed runs through the
  ;; PAGE reply handler (entry-replace-page append), NOT the scalar succeeded-
  ;; handler — and the route transition must complete when page 0 lands.
  (register-blocking-infinite-route!)
  (rf/dispatch-sync [:rf.route/navigate :route/feed {:slug "intro"}])
  (let [nav-token (:nav-token (slice))
        k         (feed-key "intro")]
    (testing "the route holds :loading while page 0 of the infinite feed is in flight"
      (is (= :loading (:transition (slice)))
          "transition stays :loading while the blocking infinite feed's page 0 is pending")
      (is (contains? (blocking-slot nav-token) k)
          "the blocking infinite feed's scoped key is tracked under the nav-token")
      (let [e (entry k)]
        (is (state/infinite-entry? e) "ensured an INFINITE entry (page-0 fetch, not a scalar)")
        (is (= :loading (:status e)) "first load (no data) is :loading")
        (is (= [] (:data e)) "page vector empty (page-0 in flight)"))
      (testing "the route owner is on the infinite feed entry"
        (is (contains? (:active-owners (entry k)) [:route :route/feed nav-token])
            "owner is [:route route-id nav-token]")))
    (testing "page-0 SUCCESS drains the route via the PAGE reply handler → :idle"
      (reply-page-success! (page [:a :b] "c1"))
      (let [e (entry k)]
        (is (= :loaded (:status e)) "feed settled :loaded")
        (is (= 1 (state/page-count e)) "page 0 accumulated through the append path")
        (is (= [(page [:a :b] "c1")] (:data e)) "page-0 data appended"))
      (is (= :idle (:transition (slice)))
          "the blocking infinite route drained to :idle on the page-0 reply")
      (is (empty? (blocking-slot nav-token))
          "the blocking slot drained when page 0 landed"))))

;; ===========================================================================
;; 2. blocking infinite route page-0 FIRST-load FAILURE errors the route
;;    (the FIRST-load :error channel — parity with a scalar blocking resource)
;; ===========================================================================

(deftest blocking-infinite-route-page-0-failure-errors-route
  ;; rf2-byl7bk.3.1 — THE spec-correct contract (Spec 016 §Status semantics +
  ;; §Infinite resources): a blocking infinite page-0 FIRST-load FAILURE (no
  ;; accumulated pages) settles the FIRST-load :error channel (`entry-failed`
  ;; → :status :error, :error envelope, :data nil), and `page-failed-handler`
  ;; drains the blocking slot as :FAILURE. drain-blocking then flips the route
  ;; to :error (its (= :error (:status entry)) branch fires automatically) —
  ;; EXACTLY like a blocking SCALAR resource. This INVERTS the rf2-kz90ep
  ;; characterisation test (which pinned :loaded + :page-error + route :idle):
  ;; Spec 016 reserves :error for first-load (page 0) and :page-error for
  ;; load-more (page N>0) ONLY — page 0 is a first load, never a load-more.
  (register-blocking-infinite-route!)
  (rf/dispatch-sync [:rf.route/navigate :route/feed {:slug "intro"}])
  (let [nav-token (:nav-token (slice))
        k         (feed-key "intro")
        failure   {:kind :rf.http/server :status 503 :message "upstream down"}]
    (testing "the route holds :loading on the in-flight blocking page 0"
      (is (= :loading (:transition (slice))))
      (is (contains? (blocking-slot nav-token) k)))
    (reply-page-failure! failure)
    (testing "the feed settles :status :error with the :error channel — NOT :page-error"
      (let [e (entry k)]
        (is (= :error (:status e))
            "a page-0 first-load failure (no pages) is :status :error, never :loaded")
        (is (= failure (:error e)) ":error records the first-load failure envelope")
        (is (nil? (:page-error e)) "NOT the load-more :page-error channel (page 0 is not a load-more)")
        (is (nil? (:refresh-error e)) "NOT the whole-feed :refresh-error channel")
        (is (nil? (:data e)) "first-load failure clears data (no usable data)")
        (is (= 0 (state/page-count e)) "no page accumulated — page 0 never landed")))
    (testing "the BLOCKING ROUTE flips to :error (parity with a scalar blocking resource)"
      (is (= :error (:transition (slice)))
          "the blocking infinite route flips to :error on the page-0 first-load failure")
      (is (= :rf.error/resource-route-blocking (:rf.error/id (:error (slice))))
          ":rf.route/error carries the structured blocking-failure error")
      (is (empty? (blocking-slot nav-token))
          "the blocking slot drained on the page-0 failure (slot does not leak / hang the route)"))))

;; ===========================================================================
;; 3. contrast guard — a SCALAR blocking first-load failure errors the route
;;    too, so #2 now confirms PARITY (infinite page-0 == scalar first load)
;; ===========================================================================

(deftest scalar-blocking-first-load-failure-still-errors-route-contrast
  ;; A guard so #2's assertion has a parity baseline: the SAME route shape with
  ;; a SCALAR (non-infinite) blocking resource ALSO flips to :error on a
  ;; first-load failure. Post-fix #2 and #3 agree — a blocking infinite page-0
  ;; first-load failure errors the route exactly like a scalar one (parity
  ;; restored, the divergence rf2-kz90ep pinned is removed).
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request       (fn [{:keys [slug]} _ctx]
                                     {:request {:method :get :url (str "/api/articles/" slug)}})
                    :tags          (fn [{:keys [slug]} _data] #{[:article slug]})})
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource  :article/by-slug
                              :params    (fn [route] {:slug (get-in route [:params :slug])})
                              :blocking? true}]} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "intro"}])
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "intro"})
        e          (entry scoped-key)]
    (is (not (state/infinite-entry? e)) "the contrast resource is SCALAR, not infinite")
    ;; settle the scalar via the SCALAR failed handler (its live reply shape)
    (rf/dispatch-sync (conj (:on-failure @last-managed-args)
                            {:kind :failure :failure {:status 503 :message "upstream down"}}))
    (testing "a SCALAR blocking first-load failure flips the route to :error (parity with #2)"
      (is (= :error (:transition (slice)))
          "scalar first-load failure errors the route — infinite page-0 (#2) now matches it")
      (is (= :rf.error/resource-route-blocking (:rf.error/id (:error (slice))))
          ":rf.route/error carries the structured blocking-failure error"))))

;; ===========================================================================
;; 4. LOAD-MORE (page N>0) FAILURE keeps the :page-error channel intact — the
;;    third error channel still applies where the spec reserves it (data kept)
;; ===========================================================================

(deftest blocking-infinite-route-load-more-failure-keeps-page-error-channel
  ;; rf2-byl7bk.3.1 — the OTHER side of the split: a page N>0 (load-more)
  ;; failure with accumulated pages is STILL the third error channel
  ;; (`entry-page-failed` → :loaded + :page-error, all pages kept). Only page 0
  ;; with no pages uses the first-load :error channel; a load-more failure must
  ;; NOT error the route (the route already drained on the page-0 success) and
  ;; must NOT lose the feed.
  (register-blocking-infinite-route!)
  (rf/dispatch-sync [:rf.route/navigate :route/feed {:slug "intro"}])
  (let [nav-token (:nav-token (slice))
        k         (feed-key "intro")
        failure   {:kind :rf.http/server :status 503 :message "load-more down"}]
    ;; page 0 SUCCEEDS first (cursor "c1" ⇒ a next page exists) — the route
    ;; drains to :idle, then the feed has one accumulated page.
    (reply-page-success! (page [:a :b] "c1"))
    (is (= :idle (:transition (slice))) "route drained on page-0 success")
    (is (= 1 (state/page-count (entry k))) "page 0 accumulated")
    ;; now a load-more (page 1) is dispatched and FAILS.
    (rf/dispatch-sync [:rf.resource/load-more {:resource :feed/articles
                                               :scope    :rf.scope/global
                                               :params   {:slug "intro"}}])
    (let [args @last-managed-args]
      (is (= 1 (:rf.resource/page-index (second (:on-failure args))))
          "the load-more fetches page index 1 (a positive page, not page 0)")
      (rf/dispatch-sync (conj (:on-failure args) {:kind :failure :failure failure})))
    (testing "a load-more (N>0) failure keeps the feed :loaded + records :page-error"
      (let [e (entry k)]
        (is (= :loaded (:status e)) "load-more failure leaves the feed :loaded (data kept)")
        (is (= failure (:page-error e)) ":page-error records the load-more failure envelope")
        (is (nil? (:error e)) "NOT the first-load :error channel")
        (is (= 1 (state/page-count e)) "the accumulated page-0 is KEPT (no data lost)")
        (is (= [(page [:a :b] "c1")] (:data e)) "page-0 data still present")))
    (testing "the route is undisturbed by the load-more failure (already :idle)"
      (is (= :idle (:transition (slice)))
          "a load-more failure does NOT error the route (route blocks on page 0 only)")
      (is (nil? (:error (slice))) "no :rf.route/error written on a load-more failure"))))
