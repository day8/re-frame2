(ns re-frame.routing-nav-allocation-record-replay-test
  "Nav-token / pending-nav-id as RECORDABLE allocation coeffects.

  Routing mints `:nav-token` and the pending-nav `:id` and writes them DURABLY
  to runtime-db. Minted from an unrecorded host-cache read, replay would
  re-mint DIFFERENT ids: a recorded `[:rf.route/continue \"pn-1\"]` would no-op
  against a re-minted \"pn-2\", and a continuation carrying \"nav-1\" would flip
  between stale and current. So the ids ride two recordable, generator-backed
  coeffects —
    `:rf.route/nav-allocation         {:token \"nav-N\" :counter N}`
    `:rf.route/pending-nav-allocation {:id    \"pn-N\"  :counter N}`
  — which strict replay re-presents verbatim, whose `:counter` the commit fx
  folds into the host high-water mark with `max`, and whose `:schema` refuses
  a malformed recorded value before the handler writes it.

  Every assertion reads runtime-db or a thrown error, so the namespace runs
  unguarded in the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
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

(defn- replay-ex-data [event recorded]
  (try (replay! event recorded) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

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
            :schema before the block folds a corrupt id into runtime-db"
    (block-fixture!)
    (let [data (replay-ex-data [:rf.route/url-requested {:url "/home"}]
                               {:rf.route/nav-allocation         {:token "nav-2" :counter 2}
                                :rf.route/pending-nav-allocation {:id nil :counter "bad"}})]
      (is (= [:rf.error/cofx-value-invalid :rf.route/pending-nav-allocation nil]
             [(:rf.error/id data) (:rf.cofx/id data) (pending-id)])))))

(deftest strict-replay-rejects-a-malformed-nav-allocation
  (testing "a present-but-malformed recorded nav allocation fails its :schema
            before the commit folds a corrupt token into the route slice"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [data (replay-ex-data [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}]
                               {:rf.route/nav-allocation {:token nil :counter "bad"}})]
      (is (= [:rf.error/cofx-value-invalid :rf.route/nav-allocation nil]
             [(:rf.error/id data) (:rf.cofx/id data) (nav-token)])))))
