(ns re-frame.ui.reactive-stale-lease-capture-cljs-test
  "rf2-vxgfnd.161 — a ViewCell commit must never CONNECT while retaining a lease
  from a SUPERSEDED frame incarnation.

  RE-VERIFICATION (2026-07): the bead was filed against PR #5787's head, which
  PRE-DATES the lexical-site commit rewrite (#5821) that introduced the
  `candidate-current?` gate in `commit*`. At that head, a two-site capture
  could acquire lease 1 from incarnation A, lose A, acquire lease 2 from a fresh
  same-id B, and CONNECT while retaining the stale A lease — because commit
  incarnation evidence was derived from a LATER bare-id registry read
  (`committed-frame-incarnations` → `frame/frame-incarnation-token`), which by
  then read B's token, so `incarnation-superseded?` and
  `frame-incarnation-closing?` both passed.

  On CURRENT main that symptom is UNREACHABLE, so the bead's required
  lease-carried-token machinery would be REDUNDANT fencing. The reason:
  destroying incarnation A disposes A's sub-cache, so any lease acquired from A
  is no longer CANONICAL — `obs/current?` reads false for it
  (`node-still-canonical?` compares the lease's reaction against the frame's
  CURRENT sub-cache, which is now B's). `commit*`'s `candidate-current?` gate
  rejects the WHOLE commit if ANY candidate lease is non-current, rolling back
  every staged lease and synchronously invalidating (no connect, no retained
  stale lease) — strictly BEFORE the incarnation-token revalidation runs. A
  mixed capture therefore cannot connect: lease 1 (owner A destroyed) is always
  non-current at the gate.

  These fixtures PIN that fencing so a future weakening of `candidate-current?`
  re-opens .161 as a red test. `.cljc` ending `-cljs-test` graft-checks on node
  (`test:cljs`) AND JVM (`clojure -M:test`) against the REAL observation port +
  sub-cache. The `:post-acquire`-window incarnation revalidation (destruction
  AFTER the gate) is pinned separately by
  `re-frame.ui.reactive-incarnation-close-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.live-frame            :as live-frame]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.substrate.observation :as obs]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.frames]
            [re-frame.ui.reactive           :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

;; ---- THE LINCHPIN: a superseded incarnation's lease is non-current -----------
(deftest a-superseded-incarnations-lease-is-non-current
  ;; This is WHY the mixed capture cannot connect: the instant A is destroyed
  ;; (and a same-id B replaces it), the A-owned lease stops being the frame's
  ;; canonical node, so `obs/current?` — the exact predicate `commit*`'s
  ;; candidate-current? gate uses — reads false for it.
  (rf/reg-sub :sl/a (fn [db _] (:a db)))
  (let [fid    :sl/frame
        _      (make-frame! fid {:a 1})
        target (rf/with-frame fid (obs/resolve-target {:query-v [:sl/a]}))
        lease  (rf/with-frame fid (obs/acquire! target (fn [_] nil)))]
    (is (obs/current? lease target) "the A lease is current while A is live")
    (frame/destroy-frame! fid)
    (make-frame! fid {:a 99})                           ;; same-id B
    (is (not (obs/current? lease target))
        "after same-id B replaces A the A lease is NON-CURRENT — its node was disposed")
    (obs/release! lease)))

;; ---- the two-site stale capture is rejected, never connected -----------------
(deftest a-two-site-capture-with-a-superseded-incarnation-does-not-connect
  ;; Two sites acquire from A, then A is destroyed and same-id B created in the
  ;; acquire→snapshot window (:post-stage-acquire). Both A leases go non-current,
  ;; so the candidate-current? gate rejects the whole commit BEFORE any
  ;; incarnation-token revalidation — the cell never connects and retains no
  ;; stale lease. (Pre-#5821 this connected with stale A leases; removing
  ;; candidate-current? would restore that failure.)
  (rf/reg-sub :sl/a (fn [db _] (:a db)))
  (rf/reg-sub :sl/b (fn [db _] (:b db)))
  (let [fid     :sl/frame2
        _       (make-frame! fid {:a 1 :b 2})
        token-a (frame/frame-incarnation-token fid)
        cell    (reactive/make-cell ::two)
        [_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn []
                              (reactive/sub-read ::s1 [:sl/a])
                              (reactive/sub-read ::s2 [:sl/b]))))]
    (binding [reactive/*commit-barrier*
              (fn [phase _]
                (when (= :post-stage-acquire phase)
                  (frame/destroy-frame! fid)
                  (make-frame! fid {:a 99 :b 88})))]
      (reactive/commit! cell capture))
    (is (not (identical? token-a (frame/frame-incarnation-token fid)))
        "B is a distinct incarnation under the reused id")
    (testing "the stale capture is rejected — no connect, no retained lease"
      (is (not= :connected (reactive/lifecycle cell))
          "a commit whose incarnation was superseded before the snapshot never connects")
      (is (empty? (reactive/committed-sites cell)) "no committed sites — nothing retained")
      (is (nil? (:ref-count (get @(:sub-cache (frame/frame fid)) [:sl/a])))
          "B's replacement cache is untouched — the rejected staging released cleanly"))))

;; ---- ordinary same-incarnation multi-site commit is unaffected ---------------
(deftest ordinary-same-incarnation-multi-site-commit-connects
  (rf/reg-sub :sl/a (fn [db _] (:a db)))
  (rf/reg-sub :sl/b (fn [db _] (:b db)))
  (let [fid  (make-frame! :sl/frame3 {:a 1 :b 2})
        cell (reactive/make-cell ::ok)
        [_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn []
                              (reactive/sub-read ::s1 [:sl/a])
                              (reactive/sub-read ::s2 [:sl/b]))))]
    (reactive/commit! cell capture)
    (is (= :connected (reactive/lifecycle cell))
        "a live, unchanged incarnation commits normally — no false rejection")
    (is (= 2 (count (reactive/committed-sites cell))))))
