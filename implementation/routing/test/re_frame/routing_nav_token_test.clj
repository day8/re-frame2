(ns re-frame.routing-nav-token-test
  "Navigation-token stale-result suppression and `:on-match` loader tests for
  re-frame.routing: nav-token allocation, the `:rf.route/nav-token` /
  `:rf.route/route-id` cofx, the `:rf.route/with-nav-token` fx, and `:on-match`
  throw isolation.

  ## Posture split

  STALE-RESULT SUPPRESSION is production-real and carries no posture guard:
  a superseded completion's app `:rf/reply-to` target is never dispatched, so
  app-db and runtime-db are unchanged, and the fresh completion still commits.
  Every deftest asserts that outside any arm, so it runs in the ordinary
  `clojure -M:test` suite AND in `clojure -M:test:prod-gate` (the
  `-Dre-frame.debug=false` lane).

  What is dev-only is the `:rf.route.nav-token/stale-suppressed` TRACE and
  everything spelled on it: the carried / current tokens, `:rf.trace/event-id`,
  `:completed-at`, the `:rf.reply/work-id` join key and the `:rf.reply/*`
  envelope facts. It rides `trace/emit!`, gated on `rf.interop/debug-enabled?`,
  so those assertions sit inside `(when rf.interop/debug-enabled? …)` arms."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- reg-article! []
  (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
  (rf/reg-event :article/loaded
                (fn [{:keys [db]} [_ id payload]]
                  {:db (assoc db :article {:id id :payload payload})})))

(defn- visit! [url]
  (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]))

(defn- article [] (:article (rf/app-db-value :rf/default)))

(deftest routing-nav-token-staleness
  (testing "through the test-only `:rf.test/simulate-http-resolution` fixture:
            the superseded navigation's result is suppressed, the live one commits"
    (reg-article!)
    (visit! "/articles/A")
    (visit! "/articles/B")
    (let [resolve! (fn [id token]
                     (rf/dispatch-sync [:rf.test/simulate-http-resolution
                                        {:on-success-event  [:article/loaded id (str id "-payload")]
                                         :carried-nav-token token
                                         :carried-route-id  :route/article}])
                     (article))]
      (is (= [nil {:id "B" :payload "B-payload"}]
             [(resolve! "A" "nav-1") (resolve! "B" "nav-2")])))))

(def ^:private stale-tag-keys
  [:carried-token :current-token :rf.trace/event-id :completed-at
   :rf.reply/work-id :rf.reply/carried :rf.reply/current
   :rf.reply/status :rf.reply/work-status :rf.reply/stale-reason])

(deftest with-nav-token-fx-suppresses-stale-reply-to-and-commits-fresh
  (testing ":rf.route/with-nav-token: a stale :rf/reply-to is suppressed, a fresh one runs"
    (reg-article!)
    (rf/reg-route :route/profile {:params [:map [:id :string]]} "/profile/:id")
    (rf/reg-event :article/completed
                  (fn [_ [_ {:keys [token route-id completed-at id]}]]
                    {:fx [[:rf.route/with-nav-token
                           {:rf/reply-to  [:article/loaded id (str id "-payload")]
                            :nav-token    token
                            :route-id     route-id
                            :completed-at completed-at}]]}))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::with-nav-token-fx (fn [ev] (swap! traces conj ev)))
      (visit! "/articles/A")
      ;; B is a DIFFERENT route, so the stale work-id must carry A's CAPTURED
      ;; route id rather than the live one.
      (visit! "/profile/B")
      (let [complete! (fn [m] (rf/dispatch-sync [:article/completed m]) (article))]
        (is (= [nil {:id "B" :payload "B-payload"}]
               [(complete! {:token "nav-1" :route-id :route/article
                            :completed-at 1717000123456 :id "A"})
                (complete! {:token "nav-2" :route-id :route/profile :id "B"})])))
      (rf/unregister-listener! :trace ::with-nav-token-fx)
      (when rf.interop/debug-enabled?
        (is (= [{:carried-token         "nav-1"
                 :current-token         "nav-2"
                 :rf.trace/event-id     :article/loaded
                 :completed-at          1717000123456
                 :rf.reply/work-id      [:rf.work/route :route/article "nav-1" :article/loaded]
                 :rf.reply/carried      {:route/nav-token "nav-1"}
                 :rf.reply/current      {:route/nav-token "nav-2"}
                 :rf.reply/status       :stale
                 :rf.reply/work-status  :suppressed
                 :rf.reply/stale-reason :rf.route/nav-token-stale}]
               (->> @traces
                    (filter #(= :rf.route.nav-token/stale-suppressed (:operation %)))
                    (mapv #(select-keys (:tags %) stale-tag-keys))))
            "exactly one stale trace, in the shared :rf.reply/* vocabulary")))))

(deftest nav-token+route-id-cofx-yields-complete-route-work-id
  (testing "the :rf.route/nav-token and :rf.route/route-id cofx deliver the live
            token and route id together, the two facts a route work-id needs"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [captured (atom nil)]
      (rf/reg-event :article/load
                    {:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]}
                    (fn [{:rf.route/keys [nav-token route-id]} _]
                      (reset! captured {:nav-token nav-token :route-id route-id})
                      {}))
      (visit! "/articles/A")
      (rf/dispatch-sync [:article/load])
      (is (= {:nav-token "nav-1" :route-id :route/article} @captured)))))

(deftest with-nav-token-fx-reply-to-completes-live-through-shared-substrate
  (testing "a live completion appends the :status :ok reply map to its :rf/reply-to target"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :article/load-replied
                  (fn [{:keys [db]} [_ id reply]]
                    {:db (assoc db :replied [id reply])}))
    (rf/reg-event :article/completed
                  (fn [_ [_ token]]
                    {:fx [[:rf.route/with-nav-token
                           {:rf/reply-to  [:article/load-replied "A"]
                            :nav-token    token
                            :route-id     :route/article
                            :value        {:title "Welcome"}
                            :completed-at 1717000000000}]]}))
    (visit! "/articles/A")
    (rf/dispatch-sync [:article/completed "nav-1"])
    (is (= ["A" {:status               :ok
                 :value                {:title "Welcome"}
                 :rf.reply/work-id     [:rf.work/route :route/article "nav-1" :article/load-replied]
                 :rf.reply/work-kind   :route
                 :rf.reply/work-status :completed
                 :rf.frame/id          :rf/default
                 :completed-at         1717000000000}]
           (:replied (rf/app-db-value :rf/default))))))

(deftest with-nav-token-never-delivers-stale-to-any-target
  (testing "no :rf/reply-to target — not even one forging a truthy stale-delivery
            authority, as wire- or EDN-authored data could — makes a superseded
            completion deliver"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :app/observe-stale
                  (fn [{:keys [db]} [_ reply]] {:db (assoc db :app-saw reply)}))
    (rf/reg-event :app/completed
                  (fn [_ _]
                    {:fx [[:rf.route/with-nav-token
                           {:rf/reply-to {:event                          [:app/observe-stale]
                                          :dispatch-stale?                true
                                          :re-frame.reply/stale-authority true}
                            :nav-token   "nav-1"
                            :route-id    :route/article}]]}))
    (let [traces (atom [])
          state  #(vector (rf/app-db-value :rf/default)
                          (:rf.db/runtime (rf/frame-state-value :rf/default)))]
      (rf/register-listener! :trace ::stale-nondelivery (fn [ev] (swap! traces conj ev)))
      (visit! "/articles/A")
      (visit! "/articles/B")
      (let [before (state)]
        (rf/dispatch-sync [:app/completed])
        (rf/unregister-listener! :trace ::stale-nondelivery)
        (is (= before (state)) "app-db and runtime-db are unchanged")
        (when rf.interop/debug-enabled?
          (is (some #(= :rf.route.nav-token/stale-suppressed (:operation %)) @traces)
              "the completion took the suppression path"))))))

(deftest on-match-throw-does-not-flip-route-and-later-loader-runs
  (testing "a throwing :on-match event stays an ordinary event exception: the
            later loader still runs, and the route stays :idle with no :error,
            because :on-match never drives readiness"
    (let [order (atom [])]
      (rf/reg-event :load/fail (fn [_ _] (swap! order conj :fail) (throw (ex-info "first-boom" {}))))
      (rf/reg-event :load/next (fn [_ _] (swap! order conj :next) {}))
      (rf/reg-route :route/two-loaders {:on-match [[:load/fail] [:load/next]]} "/two-loaders")
      (visit! "/two-loaders")
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :current])]
        (is (= [[:fail :next] :idle nil]
               [@order (:transition slice) (:error slice)]))))))

(defn- hydrate-then-navigate!
  "Hydrate route A carrying the server's `server-token`, capture the live token
  on A, navigate to B, then complete A's work under the captured token.
  Returns `[captured token-on-B result-delivered-into-B]`."
  [server-token]
  (rf/reg-route :route/a {} "/a")
  (rf/reg-route :route/b {} "/b")
  (rf/reg-event :a/loaded (fn [{:keys [db]} [_ reply]] {:db (assoc db :a-result (:value reply))}))
  (rf/reg-event :a/load-more
                {:rf.cofx/requires [:rf.route/nav-token]}
                (fn [{:keys [db] :rf.route/keys [nav-token]} _] {:db (assoc db :captured nav-token)}))
  (rf/reg-event :a/complete
                (fn [_ [_ token]]
                  {:fx [[:rf.route/with-nav-token {:rf/reply-to [:a/loaded]
                                                   :nav-token   token
                                                   :route-id    :route/a
                                                   :value       "A-page-2"}]]}))
  (rf/dispatch-sync [:rf/hydrate {:rf/app-db     {}
                                  :rf/runtime-db {:rf.runtime/routing
                                                  {:current {:route-id :route/a :params {} :query {}
                                                             :fragment nil :transition :idle :error nil
                                                             :nav-token server-token}}}}])
  (rf/dispatch-sync [:a/load-more])
  (let [captured (:captured (rf/app-db-value :rf/default))]
    (visit! "/b")
    (rf/dispatch-sync [:a/complete captured])
    [captured
     (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :nav-token])
     (:a-result (rf/app-db-value :rf/default))]))

(deftest a-hydrated-token-the-client-would-mint-next-is-minted-past
  (testing "a fresh server process serves nav-1, which is the fresh client's own
            next token: the first client navigation mints past it, so work
            captured on the hydrated route is stale on the new one"
    (is (= ["nav-1" "nav-2" nil] (hydrate-then-navigate! "nav-1")))))

(deftest a-hydrated-token-ahead-of-the-client-is-minted-past
  (testing "a server token ahead of the client's counter floors the next mint"
    (is (= ["nav-7" "nav-8" nil] (hydrate-then-navigate! "nav-7")))))
