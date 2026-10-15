(ns re-frame.routing-nav-allocation-record-replay-test
  "Recorded routing allocations replay verbatim, advance live counters and
  reject malformed records, with or without the schemas artefact."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing.nav-counters :as rf.routing.nav-counters]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- block-fixture!
  "Land on an editor route whose `:can-leave` always blocks."
  []
  (rf/reg-route :route/editor {:can-leave [:editor/can-leave?]} "/editor")
  (rf/reg-route :route/home   {} "/home")
  (rf/reg-sub :editor/can-leave? (fn [_ _] false))
  (rf/dispatch-sync [:rf.route/handle-url-change "/editor" {:rf.route/cause :link}]))

(defn- pending-id []
  (:id (rf/subscribe-once [:rf/pending-navigation] {:frame :rf/default})))

(defn- nav-token []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/routing :current :nav-token]))

(defn- replay!
  "Dispatch `event` the way replay does: strict, carrying the `recorded` cofx."
  [event recorded]
  (rf/dispatch-sync event {:rf.cofx recorded :rf.cofx/mint-policy :strict}))

(defn- without-schemas-artefact
  "Run `f` with the schemas artefact's validation seam unbound, as in an app
  that never loaded `re-frame.schemas`."
  [f]
  (let [hooks [:schemas/validate-event!
               :schemas/validate-with-registered-fn
               :schemas/explain-with-registered-fn
               :schemas/redact-validation-tags]
        saved (mapv rf.late-bind/get-fn hooks)]
    (try
      (doseq [hook hooks] (rf.late-bind/set-fn! hook nil))
      (f)
      (finally
        (doseq [[hook hook-fn] (map vector hooks saved)] (rf.late-bind/set-fn! hook hook-fn))))))

(defn- replay-refusal
  "Replay `event` carrying `recorded`, without the schemas artefact. Returns
  the ex-data of the error that refused it, whether thrown or reported to the
  always-on error listeners."
  [event recorded]
  (let [reported (atom [])]
    (rf.error-emit/register-error-listener!
      ::refusal #(swap! reported conj (ex-data (:exception %))))
    (try
      (without-schemas-artefact #(replay! event recorded))
      (first (remove nil? @reported))
      (catch clojure.lang.ExceptionInfo e (ex-data e))
      (finally (rf.error-emit/unregister-error-listener! ::refusal)))))

(deftest live-navigation-runs-without-the-schemas-artefact
  (testing "a commit mints nav-1 and a blocked leave mints pn-1 with the
            schemas seam unbound"
    (without-schemas-artefact
      #(do (block-fixture!)
           (rf/dispatch-sync [:rf.route/url-requested {:url "/home"}])))
    (is (= ["nav-1" "pn-1"] [(nav-token) (pending-id)]))))

(deftest failure-1-fixed-recorded-allocation-replays-same-pending-nav-id
  (testing "replaying a block re-presents the recorded pn-1 though the host
            counter has moved on (a re-mint would give pn-2)"
    (block-fixture!)
    (rf.routing.nav-counters/commit-counter! :pending-nav-counter 1)
    ;; The link door can block or commit, so its record carries both allocations.
    (replay! [:rf.route/url-requested {:url "/home"}]
             {:rf.route/nav-allocation         {:token "nav-2" :counter 2}
              :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}})
    (is (= "pn-1" (pending-id)))))

(deftest failure-2-fixed-recorded-allocation-replays-same-nav-token
  (testing "replaying a commit re-presents the recorded nav-1 though the host
            counter has moved on (a re-mint would give nav-6)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf.routing.nav-counters/commit-counter! :nav-token-counter 5)
    ;; A `handle-url-change` record carries both allocations: both generate live.
    (replay! [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}]
             {:rf.route/nav-allocation         {:token "nav-1" :counter 1}
              :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}})
    (is (= "nav-1" (nav-token)))))

(deftest commit-advances-host-high-water-with-max-from-recorded-counter
  (testing "a replayed allocation's :counter re-establishes the host high-water
            mark, so the next live navigation mints past it"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (replay! [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}]
             {:rf.route/nav-allocation         {:token "nav-9" :counter 9}
              :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}})
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/B" {:rf.route/cause :link}])
    (is (= "nav-10" (nav-token)))))

(deftest strict-replay-rejects-a-malformed-pending-nav-allocation
  (testing "a present-but-malformed recorded pending-nav allocation fails its
            shape check before the block folds a corrupt id into runtime-db"
    (block-fixture!)
    (let [data (replay-refusal [:rf.route/url-requested {:url "/home"}]
                               {:rf.route/nav-allocation         {:token "nav-2" :counter 2}
                                :rf.route/pending-nav-allocation {:id nil :counter "bad"}})]
      (is (= [:rf.error/cofx-value-invalid :rf.route/pending-nav-allocation nil]
             [(:rf.error/id data) (:rf.cofx/id data) (pending-id)])))))

(deftest strict-replay-rejects-a-malformed-nav-allocation
  (testing "a present-but-malformed recorded nav allocation fails its shape
            check before the commit folds a corrupt token into the route slice"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [data (replay-refusal [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}]
                               {:rf.route/nav-allocation         {:token nil :counter "bad"}
                                :rf.route/pending-nav-allocation {:id "pn-1" :counter 1}})]
      (is (= [:rf.error/cofx-value-invalid :rf.route/nav-allocation nil]
             [(:rf.error/id data) (:rf.cofx/id data) (nav-token)])))))
