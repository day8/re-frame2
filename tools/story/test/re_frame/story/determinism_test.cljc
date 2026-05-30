(ns re-frame.story.determinism-test
  "Tests for the determinism gate `assert-deterministic` + the per-run
  stamp strip the gate adds to `canonicalize` (rf2-5x1wt.8,
  spec/017-Testing-Story.md §Determinism gate).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE: the canonicalize strip normalizes per-run stamps (epoch / trace
    / frame / wall-clock) but NOT semantic content; `wait-steps` /
    `has-wall-clock-wait?` / `cannot-run-wait-refusal` detect + refuse a
    bare `[:wait ms]`; `compare-runs` decides deterministic vs not over a
    set of hand-built run-results.
  - HEADLESS gate (against a live frame): `assert-deterministic` replays
    into N FRESH frames and reports `:deterministic` / `:non-deterministic`
    / `:cannot-run` — the §A4 acceptance bullets:
      • same event program twice is equal after canonicalization;
      • a real semantic difference IS detected;
      • volatile fields do NOT cause false drift;
      • a bare wall-clock `[:wait ms]` returns `:cannot-run`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.epoch     :as epoch]
            [re-frame.frame     :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.artifact    :as artifact]
            [re-frame.story.determinism :as det]
            [re-frame.story.fingerprint :as fp]))

;; ===========================================================================
;; PURE: the per-run stamp strip in canonicalize  (rf2-5x1wt.8)
;; ===========================================================================
;;
;; A fresh-frame replay restarts the process-global epoch / dispatch /
;; trace-id counters and allocates a new :rf.test.replay/* frame id, so two
;; semantically-equal runs stamp DIFFERENT values for each of these. The
;; strip is what makes them canonicalize `=`.

(defn- epoch-rec
  "A minimal `:rf/epoch-record` carrying its per-run stamps."
  [epoch-id frame m]
  (merge {:epoch-id epoch-id :frame frame :committed-at 1234567
          :schema-digest "abc" :outcome :ok
          :db-before {} :db-after {}
          :trace-events [] :effects [] :sub-runs [] :renders []}
         m))

(defn- trace-ev
  "A minimal trace event carrying its per-run stamps (`:id` / `:time`)."
  [id m]
  (merge {:operation :rf.event/run-start :op-type :event
          :id id :time 999 :tags {}}
         m))

(deftest canonicalize-strips-epoch-record-stamps
  (testing "two epoch records that differ ONLY in per-run stamps canonicalize ="
    (let [a (epoch-rec 5  :rf.test.replay/frame-aaa {:db-after {:n 1}})
          b (epoch-rec 99 :rf.test.replay/frame-zzz {:db-after {:n 1}})]
      (is (= (fp/canonicalize a) (fp/canonicalize b))
          ":epoch-id / :frame / :committed-at / :schema-digest are per-run stamps")
      (is (= (fp/canonical-hash a) (fp/canonical-hash b)))))

  (testing "a SEMANTIC difference (db-after) still perturbs the canonical form"
    (let [a (epoch-rec 5  :rf.test.replay/frame-aaa {:db-after {:n 1}})
          c (epoch-rec 5  :rf.test.replay/frame-aaa {:db-after {:n 2}})]
      (is (not= (fp/canonicalize a) (fp/canonicalize c))
          "the strip must not hide a real app-db change")
      (is (not= (fp/canonical-hash a) (fp/canonical-hash c))))))

(deftest canonicalize-strips-trace-event-stamps
  (testing "trace events differing only in :id / :time canonicalize ="
    (let [a {:trace-events [(trace-ev 17 {:tags {:rf.trace/event-id :foo}})]}
          b {:trace-events [(trace-ev 88 {:tags {:rf.trace/event-id :foo}})]}]
      (is (= (fp/canonicalize a) (fp/canonicalize b))
          ":id (process-global counter) + :time (wall-clock) are per-run stamps")))

  (testing "a SEMANTIC trace difference (operation / tags) is NOT stripped"
    (let [a {:trace-events [(trace-ev 17 {:tags {:rf.trace/event-id :foo}})]}
          c {:trace-events [(trace-ev 17 {:tags {:rf.trace/event-id :bar}})]}]
      (is (not= (fp/canonicalize a) (fp/canonicalize c))
          "the event-id tag is behavioural, not a stamp"))))

(deftest structural-strip-spares-app-db-keys
  (testing ":id / :time / :frame as APP-DB values are NOT stripped — only the
            trace-event / epoch-record carriers lose them"
    ;; A plain app-db map that happens to key on :id / :time / :frame is not
    ;; a trace event (no :operation+:op-type) nor an epoch record (no
    ;; :epoch-id+record-slot), so the structural strip leaves it intact.
    (let [db1 {:user {:id 1 :time 10 :frame :left}}
          db2 {:user {:id 2 :time 20 :frame :right}}]
      (is (not= (fp/canonicalize db1) (fp/canonicalize db2))
          "semantic app-db data on common keys survives canonicalization")
      ;; And run-results that embed them in :app-db preserve the distinction.
      (is (not= (fp/run-hash {:status :pass :app-db db1})
                (fp/run-hash {:status :pass :app-db db2}))))))

(deftest fresh-frame-tape-twins-hash-equal
  (testing "two run-results from fresh-frame replays — distinct epoch ids,
            dispatch ids, trace ids, frame ids, wall-clock — hash equal after
            the determinism strip"
    (let [run (fn [epoch-base disp frame trace-base]
                {:status :pass
                 :app-db {:n 2}
                 :epoch-tape
                 [(epoch-rec epoch-base frame
                    {:dispatch-id disp
                     :db-after {:n 1}
                     :trace-events [(trace-ev trace-base
                                      {:tags {:rf.trace/event-id :rep/inc}})]})
                  (epoch-rec (inc epoch-base) frame
                    {:dispatch-id disp
                     :db-before {:n 1} :db-after {:n 2}
                     :trace-events [(trace-ev (inc trace-base)
                                      {:tags {:rf.trace/event-id :rep/inc}})]})]})
          r1 (run 5  "d-5"  :rf.test.replay/frame-aaa 17)
          r2 (run 90 "d-90" :rf.test.replay/frame-zzz 200)]
      (is (= (fp/canonicalize r1) (fp/canonicalize r2)))
      (is (= (fp/run-hash r1) (fp/run-hash r2))
          "semantically-equal fresh-frame runs share one run-hash"))))

;; ===========================================================================
;; PURE: wait-step detection + refusal
;; ===========================================================================

(deftest wait-step-detection
  (testing "wait-steps picks out bare [:wait ms]; [:wait-until] is not a wait"
    (let [a (artifact/make-run-artifact
              {:event-program [[:dispatch [:a]]
                               [:wait 100]
                               [:dispatch [:b]]]})]
      (is (= [[:wait 100]] (det/wait-steps a)))
      (is (det/has-wall-clock-wait? a))))

  (testing "a wall-clock-free program has no wait steps"
    (let [a (artifact/make-run-artifact
              {:event-program [[:dispatch [:a]] [:dispatch-sync [:b]]]})]
      (is (= [] (det/wait-steps a)))
      (is (not (det/has-wall-clock-wait? a)))))

  (testing "the refusal is the :cannot-run third status with the wait steps"
    (let [a (artifact/make-run-artifact {:event-program [[:wait 5] [:dispatch [:x]]]})
          r (det/cannot-run-wait-refusal a)]
      (is (= :cannot-run (:status r)))
      (is (= :determinism-wall-clock-wait (:reason r)))
      (is (= [[:wait 5]] (:wait-steps r))))))

;; ===========================================================================
;; PURE: ->artifact coercion
;; ===========================================================================

(deftest ->artifact-coercion
  (testing "a run-artifact is used verbatim"
    (let [a (artifact/make-run-artifact {:event-program [[:dispatch [:x]]]})]
      (is (identical? a (det/->artifact a)))))

  (testing "a normalized plan folds [:world :setup] ⧺ :script and lifts fx-overrides"
    (let [plan {:variant/id :story/x
                :world  {:setup [[:dispatch [:seed]]]
                         :frame {:fx-overrides {:http/get :http/stub}}}
                :script [[:dispatch [:act]] [:wait 9]]}
          a    (det/->artifact plan)]
      (is (artifact/run-artifact? a))
      (is (= [[:dispatch [:seed]] [:dispatch [:act]] [:wait 9]]
             (:event-program a))
          "setup-first fold, then script")
      (is (= {:http/get :http/stub} (:fx-decisions a))
          "[:world :frame :fx-overrides] become :fx-decisions"))))

;; ===========================================================================
;; PURE: compare-runs
;; ===========================================================================

(deftest compare-runs-pure
  (testing "identical canonical runs are deterministic with one shared run-hash"
    (let [r {:status :pass :app-db {:n 1}}
          c (det/compare-runs [r r r])]
      (is (:deterministic? c))
      (is (= 3 (:run-count c)))
      (is (= (fp/run-hash r) (:run-hash c)))
      (is (nil? (:divergence c)))))

  (testing "a divergent run is detected and named (first differing run vs run 0)"
    (let [r0 {:status :pass :app-db {:n 1}}
          r1 {:status :pass :app-db {:n 1}}
          r2 {:status :pass :app-db {:n 999}}
          c  (det/compare-runs [r0 r1 r2])]
      (is (not (:deterministic? c)))
      (is (nil? (:run-hash c)))
      (is (= 2 (get-in c [:divergence :run])) "run 2 is the first divergence")
      (is (not= (get-in c [:divergence :run-hash-0])
                (get-in c [:divergence :run-hash-n])))))

  ;; rf2-12wg5 — compare-runs canonicalizes each run-slice ONCE and derives
  ;; the hash from the canon (via fp/hash-canonical) rather than
  ;; re-canonicalizing inside run-hash. The reported hashes MUST stay
  ;; byte-identical to run-hash, so a recorded :run-hash and a
  ;; determinism-gate hash never disagree. (rf2-lvrqa — the type-tagged
  ;; canonical-form is NOT idempotent, so the canon is hashed via
  ;; hash-canonical with no second canonicalization pass.)
  (testing "the reported hashes are byte-identical to fp/run-hash (no double canon)"
    (let [r0 {:status :pass :app-db {:n 1 :nested {:b 2 :a 1}}
              :warnings #{:w2 :w1}}
          r1 {:status :pass :app-db {:n 1 :nested {:a 1 :b 2}}
              ;; volatile + per-run stamps differ but must be stripped equal
              :elapsed-ms 99 :warnings #{:w1 :w2}}
          c  (det/compare-runs [r0 r1])]
      (is (:deterministic? c) "the two runs differ only in volatile fields")
      (is (= [(fp/run-hash r0) (fp/run-hash r1)] (:hashes c))
          "content-hash of the canon equals run-hash for every run")
      (is (= (fp/run-hash r0) (:run-hash c))
          "the shared run-hash is the canonical run-hash"))))

;; rf2-ewrse — the determinism gate inherited the rf2-4gwja fn-slot
;; nondeterminism: a raw fn in the run-slice (`:app-db` or an effect `:args`)
;; re-allocated per replay hashed by object identity, so compare-runs read a
;; genuinely-deterministic program as a FALSE `:non-deterministic`. The
;; rf2-4gwja `opaque-fn` fold closes it — these runs must now compare
;; `:deterministic?` true.
(deftest compare-runs-fn-slot-is-deterministic
  (testing "two runs whose ONLY difference is the IDENTITY of fns in :app-db
            / effect :args compare deterministic (rf2-ewrse) — each replay
            re-allocates the closure, the exact false-RED the gate produced"
    (let [run-with (fn [f] {:status :pass
                            :app-db  {:n 1 :cb f}
                            :effects [{:fx-id :x :args f :outcome :ok}]})
          c        (det/compare-runs [(run-with (fn [] 1))
                                      (run-with (fn [] 1))
                                      (run-with (fn [] 1))])]
      (is (:deterministic? c) "fn-identity-only difference is NOT non-determinism")
      (is (nil? (:divergence c)))
      (is (some? (:run-hash c)) "a stable run-hash is reported")))
  (testing "a fn in app-db does NOT mask a real semantic difference"
    (let [c (det/compare-runs [{:status :pass :app-db {:n 1 :cb (fn [] 1)}}
                               {:status :pass :app-db {:n 2 :cb (fn [] 1)}}])]
      (is (not (:deterministic? c)) "the :n 1 vs :n 2 difference still diverges"))))

;; ===========================================================================
;; HEADLESS gate: against a live frame  (the §A4 acceptance)
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest gate-same-program-is-deterministic
  (testing "the SAME event program replayed twice is equal after
            canonicalization — :deterministic with one shared run-hash"
    (rf/reg-event-db :det/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [a   (artifact/make-run-artifact
                {:event-program [[:dispatch [:det/inc]] [:dispatch [:det/inc]]]})
          res (det/assert-deterministic a)]
      (is (= :deterministic (:status res)))
      (is (= 2 (:runs res)))
      (is (string? (:run-hash res)))
      (is (= 8 (count (:run-hash res))))
      (is (apply = (:hashes res)) "every replay shares the canonical run-hash"))))

(deftest gate-accepts-a-normalized-plan
  (testing "a normalized plan (setup ⧺ script) is replayed deterministically"
    (rf/reg-event-db :det/seed (fn [db [_ v]] (assoc db :v v)))
    (rf/reg-event-db :det/bump (fn [db _] (update db :v inc)))
    (let [plan {:variant/id :story.det/plan
                :world  {:setup [[:dispatch [:det/seed 10]]]}
                :script [[:dispatch [:det/bump]]]}
          res  (det/assert-deterministic plan {:runs 3})]
      (is (= :deterministic (:status res)))
      (is (= 3 (:runs res))))))

(deftest gate-detects-real-semantic-nondeterminism
  (testing "a handler whose result depends on a PROCESS-GLOBAL mutable counter
            (not app-db) produces a different app-db each replay — the gate
            DETECTS it as :non-deterministic"
    (let [counter (atom 0)]
      ;; Each dispatch reads + bumps a shared atom, so replay 1 writes 1 and
      ;; replay 2 writes 2 into a FRESH frame's app-db — a genuine semantic
      ;; divergence the canonical strip must NOT mask.
      (rf/reg-event-db :det/nondet
                       (fn [db _] (assoc db :token (swap! counter inc))))
      (let [a   (artifact/make-run-artifact
                  {:event-program [[:dispatch [:det/nondet]]]})
            res (det/assert-deterministic a)]
        (is (= :non-deterministic (:status res)))
        (is (= 1 (get-in res [:divergence :run]))
            "run 1 diverged from run 0")
        (is (not= (get-in res [:divergence :run-hash-0])
                  (get-in res [:divergence :run-hash-n])))
        (is (= 2 (count (:results res)))
            "per-run results returned for a downstream semantic diff")))))

(deftest gate-volatile-fields-do-not-cause-false-drift
  (testing "a purely-deterministic handler is :deterministic even though every
            replay stamps fresh epoch / dispatch / trace ids, a new frame id,
            and its own wall-clock — volatile fields cause NO false drift"
    (rf/reg-event-db :det/pure (fn [db _] (assoc db :answer 42)))
    (let [a   (artifact/make-run-artifact
                {:event-program [[:dispatch [:det/pure]] [:dispatch [:det/pure]]]})
          res (det/assert-deterministic a {:runs 4})]
      (is (= :deterministic (:status res))
          "four fresh-frame replays with distinct stamps still agree")
      (is (= 4 (:runs res)))
      (is (apply = (:hashes res))))))

(deftest gate-refuses-bare-wall-clock-wait
  (testing "a plan containing a bare [:wait ms] returns :cannot-run for the
            determinism gate rather than a flaky verdict — and does NOT replay"
    (rf/reg-event-db :det/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [a   (artifact/make-run-artifact
                {:event-program [[:dispatch [:det/inc]]
                                 [:wait 50]
                                 [:dispatch [:det/inc]]]})
          res (det/assert-deterministic a)]
      (is (= :cannot-run (:status res)))
      (is (= :determinism-wall-clock-wait (:reason res)))
      (is (= [[:wait 50]] (:wait-steps res)))
      (is (not (contains? res :hashes))
          "the gate refused BEFORE replaying — no run hashes produced"))))

(deftest gate-reapplies-fx-decisions-deterministically
  (testing "fx decisions ride every replay — a stubbed effect fires the stub
            on each fresh-frame run, and the gate is :deterministic"
    (let [hits (atom [])]
      (rf/reg-fx :det.fx/real {:platforms #{:client :server}}
                 (fn [_ _] (swap! hits conj :real)))
      (rf/reg-fx :det.fx/stub {:platforms #{:client :server}}
                 (fn [_ _] (swap! hits conj :stub)))
      (rf/reg-event-fx :det/fire (fn [_ _] {:fx [[:det.fx/real {}]]}))
      (let [a   (artifact/make-run-artifact
                  {:event-program [[:dispatch [:det/fire]]]
                   :fx-decisions  {:det.fx/real :det.fx/stub}})
            res (det/assert-deterministic a)]
        (is (= :deterministic (:status res)))
        (is (= [:stub :stub] @hits)
            "the stub fired on BOTH fresh-frame replays")))))
