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

;; ---- THE MIXED-INCARNATION STAGED ATTEMPT (rf2-vxgfnd.256) --------------------
;; The two-site test above acquires BOTH leases from A, then swaps A→B at the
;; :post-stage-acquire barrier — so at the gate BOTH candidates are non-current,
;; and a regression that rejected only when NO candidate is current would still
;; pass. The bead's required barrier is stronger: acquire lease 1 from A, DESTROY
;; A + publish a same-id B in the acquire→acquire window, then acquire lease 2
;; from the LIVE B — a genuinely MIXED candidate set {lease1@A (NON-current),
;; lease2@B (current)}. `candidate-current?` is `every?`, so the single
;; non-current A lease rejects the whole commit. `incarnation-superseded?`
;; cannot help here: both sites share the reused id, whose CURRENT token is B's,
;; so the post-acquire `committed-frame-incarnations` snapshot reads B's token
;; and the check is false — exactly the pre-#5821 blind spot .161 documents.
;; This pins `candidate-current?` as the SOLE fence: weakening it to `some`, or
;; dropping lease 1's exact check, connects a stale A lease and flips this red.
;;
;; The interleave is injected through a TEST-ONLY wrapper around the observation
;; port (no production seam): the same `with-redefs` idiom the resource-owner
;; wiring proof uses. The commit sees the ordinary staged `obs/acquire!`; the
;; wrapper merely destroys/recreates the frame strictly BETWEEN the two calls.
(deftest a-mixed-incarnation-staged-attempt-cannot-connect
  (rf/reg-sub :sl/a (fn [db _] (:a db)))
  (rf/reg-sub :sl/b (fn [db _] (:b db)))
  (let [fid     :sl/mixed
        _       (make-frame! fid {:a 1 :b 2})
        token-a (frame/frame-incarnation-token fid)
        cell    (reactive/make-cell ::mixed)
        [_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn []
                              (reactive/sub-read ::s1 [:sl/a])
                              (reactive/sub-read ::s2 [:sl/b]))))
        acq          (atom [])           ;; [lease query token-at-acquire]
        rel          (atom [])           ;; query in release order
        real-acquire obs/acquire!
        real-release obs/release!
        lease->query (fn [lease]
                       (some (fn [[l q _]] (when (identical? l lease) q)) @acq))]
    ;; ONE staged attempt: the A→B swap lands strictly BETWEEN the two acquires.
    (with-redefs [obs/acquire!
                  (fn [target on-change]
                    (when (= 1 (count @acq))
                      ;; lease 1 (from A) is in hand — destroy A and publish a
                      ;; same-id B BEFORE lease 2 is acquired, so lease 2 binds B
                      (frame/destroy-frame! fid)
                      (make-frame! fid {:a 99 :b 88}))
                    (let [lease (real-acquire target on-change)]
                      (swap! acq conj [lease (:query target)
                                       (frame/frame-incarnation-token fid)])
                      lease))
                  obs/release!
                  (fn [lease]
                    (swap! rel conj (lease->query lease))
                    (real-release lease))]
      (reactive/commit! cell capture))
    (let [token-b (frame/frame-incarnation-token fid)]
      (is (not (identical? token-a token-b))
          "B is a distinct incarnation under the reused id")
      (testing "the interleave was genuinely MIXED — lease 1 from A, lease 2 from B"
        (is (= [[:sl/a] [:sl/b]] (mapv second @acq))
            "both sites acquired, in render order")
        (is (identical? token-a (nth (first @acq) 2))
            "lease 1 was acquired while incarnation A was live")
        (is (identical? token-b (nth (second @acq) 2))
            "lease 2 was acquired against the fresh incarnation B"))
      (testing "the mixed candidate set is rejected — no connect, no retained lease"
        (is (not= :connected (reactive/lifecycle cell))
            "a commit holding a superseded-incarnation lease never connects")
        (is (empty? (reactive/committed-sites cell)) "nothing retained"))
      (testing "the newly staged leases release exactly once, in reverse order"
        (is (= [[:sl/b] [:sl/a]] @rel)
            "reverse-order rollback: lease 2 (B) then lease 1 (A)")
        (is (= 2 (count @rel)) "each staged lease released exactly once"))
      (testing "B's replacement cache is untouched — the rejected staging released cleanly"
        (is (nil? (:ref-count (get @(:sub-cache (frame/frame fid)) [:sl/a])))
            "B's :sl/a node has no owner (it was never acquired from B)")
        (is (nil? (:ref-count (get @(:sub-cache (frame/frame fid)) [:sl/b])))
            "B's :sl/b node's lease was released — zero owners")))))

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
