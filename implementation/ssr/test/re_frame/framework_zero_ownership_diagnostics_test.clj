(ns re-frame.framework-zero-ownership-diagnostics-test
  "The framework never trips its OWN runtime-db ownership diagnostics. Real
  flows of every runtime-db-writing subsystem (Spec 002 §Write authority) —
  routing, machines, elision, SSR hydration — run while the trace stream is
  recorded. A framework writer that tripped a diagnostic would teach users
  the warning is noise.

  It lives here because only the ssr `:test` alias pulls in routing,
  machines and ssr together, and `tf/reset-runtime` reloads their ns-load
  registrations between tests.

  The diagnostics are dev-only, so each `empty?` sits in a `debug-enabled?`
  arm, where it would otherwise pass vacuously. Each test also pins the
  runtime-db write its flow performed, which proves the flow ran and holds in
  both postures. The last test is the control: an ordinary app handler DOES
  trip the warning, and its write still lands."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private ownership-diagnostics
  #{:rf.warning/app-handler-runtime-effect
    :rf.error/legacy-runtime-root
    :rf.error/effect-map-shape})

(defn- record-ownership-diagnostics! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id
      (fn [ev]
        (when (contains? ownership-diagnostics (:operation ev))
          (swap! a conj ev))))
    a))

(defn- runtime-at [path]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) path))

(defn- current-route []
  (runtime-at [:rf.runtime/routing :current :route-id]))

(defn- stub-push-url! []
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- assert-no-diagnostic! [diags]
  (when rf.interop/debug-enabled?
    (is (empty? @diags) (str "got " (mapv :operation @diags)))))

(deftest routing-flows-fire-no-ownership-diagnostic
  (testing "navigate / handle-url-change (link and popstate) / an :on-match commit"
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-route :route/search  {} "/search")
    (rf/reg-event :load/noop (fn [{:keys [db]} _] {:db db}))
    (rf/reg-route :route/loaded  {:on-match [[:load/noop]]} "/loaded")
    (stub-push-url!)
    (let [diags (record-ownership-diagnostics! ::routing)]
      (is (= [:route/article :route/search :route/home :route/loaded]
             (for [ev [[:rf.route/navigate {:to :route/article :params {:id "intro"}}]
                       [:rf.route/handle-url-change "/search?q=widgets" {:rf.route/cause :link}]
                       [:rf.route/handle-url-change "/"]
                       [:rf.route/handle-url-change "/loaded" {:rf.route/cause :link}]]]
               (do (rf/dispatch-sync ev) (current-route)))))
      (assert-no-diagnostic! diags))))

(deftest can-leave-pending-nav-fires-no-ownership-diagnostic
  (testing "url-requested / cancel / continue"
    (rf/reg-route :editor/article
                  {:params [:map [:id :string]] :can-leave :editor/can-leave?}
                  "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave? (fn [db _] (not (get-in db [:editor :dirty?]))))
    (stub-push-url!)
    (let [diags   (record-ownership-diagnostics! ::can-leave)
          pending #(runtime-at [:rf.runtime/routing :pending-navigation])]
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/articles/A" {:rf.route/cause :link}])
      (rf/dispatch-sync [:editor/dirty true])
      (is (= [true nil :route/cart]
             [(do (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}]) (some? (pending)))
              (do (rf/dispatch-sync [:rf.route/cancel "pn-1"]) (pending))
              (do (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
                  (rf/dispatch-sync [:rf.route/continue "pn-2"])
                  (current-route))])
          "blocked, cancelled, then continued")
      (assert-no-diagnostic! diags))))

(deftest machine-lifecycle-fires-no-ownership-diagnostic
  (testing "first-dispatch bootstrap, declarative :spawn, explicit destroy"
    (rf/reg-machine :zod/child {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :zod/parent
                    {:initial :idle
                     :data    {}
                     :states  {:idle    {:on {:start :working}}
                               :working {:spawn {:machine-id :zod/child}
                                         :on    {:kill :tearing :done :idle}}
                               :tearing {:entry (fn [_] {:fx [[:rf.machine/destroy :zod/child]]})
                                         :on    {:done :idle}}}})
    (let [diags    (record-ownership-diagnostics! ::machines)
          snapshot #(runtime-at [:rf.runtime/machines :snapshots %])]
      (is (= [true true nil]
             [(do (rf/dispatch-sync [:zod/child [:rf.machine/noop]]) (some? (snapshot :zod/child)))
              (do (rf/dispatch-sync [:zod/parent [:start]]) (some? (snapshot :zod/parent)))
              (do (rf/dispatch-sync [:zod/parent [:kill]]) (snapshot :zod/child))])
          "bootstrap and spawn commit snapshots; the explicit destroy clears the child's")
      (assert-no-diagnostic! diags))))

(deftest elision-population-fires-no-ownership-diagnostic
  (testing "commit-plane classification effects write `[:rf.runtime/elision …]`
            through the privileged frame-state commit, not the app-visible
            runtime-db effect"
    (let [diags (record-ownership-diagnostics! ::elision)]
      (rf/reg-event :test/classify-profile
                    (fn [{:keys [db]} _]
                      {:db        (assoc db :profile {:avatar :x :ssn :y})
                       :large     [[:profile :avatar]]
                       :sensitive [[:profile :ssn]]}))
      (rf/dispatch-sync [:test/classify-profile])
      (is (every? seq [(rf.elision/declarations :rf/default)
                       (rf.elision/sensitive-declarations :rf/default)
                       (runtime-at [:rf.runtime/elision])]))
      (assert-no-diagnostic! diags))))

(deftest ssr-hydrate-fires-no-ownership-diagnostic
  (testing "`:rf/hydrate` writes both partitions under framework authority"
    (let [diags (record-ownership-diagnostics! ::ssr)]
      (rf/dispatch-sync [:rf/hydrate {:rf/version     1
                                      :rf/frame-id    :rf/default
                                      :rf/app-db      {:greeting "hello from server"}
                                      :rf/runtime-db  {:rf.runtime/machines {:snapshots {}}}
                                      :rf/render-hash "deadbeef"}])
      (is (= ["hello from server" "deadbeef"]
             [(:greeting (rf/app-db-value :rf/default))
              (runtime-at [:rf.runtime/ssr :hydration :server-hash])]))
      (assert-no-diagnostic! diags))))

(deftest ordinary-app-handler-returning-runtime-db-still-warns
  (testing "the control: the recorder and the diagnostic are live"
    (rf/reg-event :app/sneaky-runtime-write
                  (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :hijacked}}}}))
    (let [diags (record-ownership-diagnostics! ::app-sneaky)]
      (rf/dispatch-sync [:app/sneaky-runtime-write])
      (is (= :hijacked (current-route))
          "the diagnostic is advisory, not enforcement: the write lands in both postures")
      (when rf.interop/debug-enabled?
        (is (= [[:rf.warning/app-handler-runtime-effect :app/sneaky-runtime-write]]
               (map (juxt :operation (comp :rf.trace/event-id :tags)) @diags)))))))
