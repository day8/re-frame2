(ns re-frame.story.invariants-test
  "Tests for `re-frame.story.invariants` — the invariant sentinel fixture
  (rf2-5x1wt.5) and the pure first-bad-epoch utility (rf2-5x1wt.6),
  spec/017-Testing-Story.md §Invariant sentinels.

  Two axes:

  - PURE — hand-built `:rf/epoch-record` tapes in, violations out:
    `first-bad-epoch`, `coerce-invariant`, `check-epoch`, the
    report-once `on-epoch!` core. These run under `clojure -M:test`
    (JVM) and the node-runtime CLJS build with no runtime.
  - LIVE — the `with-invariants` fixture over a real frame + dispatch +
    epoch listeners, exercising the bead acceptance: passing invariant
    across multiple dispatches; failing invariant reports once per
    failing epoch; listener exceptions are isolated; works with fresh
    AND destroyed frames.

  The live axis captures `clojure.test` / `cljs.test` reports through a
  rebound `report` multimethod / `do-report` so a deliberate violation
  registers a counted failure WITHOUT failing this suite."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story.invariants :as inv]
            #?(:clj [re-frame.story.invariants :refer [with-invariants]])
            #?(:clj [re-frame.frame :as frame])
            #?(:clj [re-frame.registrar :as registrar])
            #?(:clj [re-frame.epoch :as epoch])
            #?(:clj [re-frame.substrate.plain-atom :as plain-atom])
            ;; Side-effect require — machine restore hooks must be present
            ;; on the classpath (mirrors re-frame.epoch-test / the story
            ;; runtime fixture).
            #?(:clj [re-frame.machines]))
  #?(:cljs (:require-macros [re-frame.story.invariants :refer [with-invariants]])))

;; The live `with-invariants` axis runs on the JVM (`clojure -M:test`),
;; where a real frame can be allocated and dispatched synchronously. The
;; fixture mirrors the canonical story runtime fixture
;; (`re-frame.story-runtime-test/reset-all`): tear down the registrar +
;; frames, re-seat the plain-atom adapter, re-require machines, and clear
;; the epoch listener / history registries between tests.
#?(:clj (use-fixtures :each
          (fn [test-fn]
            (registrar/clear-all!)
            (reset! frame/frames {})
            (try (rf/init! plain-atom/adapter)
                 (catch clojure.lang.ExceptionInfo _ nil))
            (require 're-frame.machines :reload)
            (epoch/clear-epoch-listeners!)
            (epoch/clear-history!)
            (frame/ensure-default-frame!)
            (test-fn))))

;; ===========================================================================
;; FIXTURE BUILDERS  (pure tapes)
;; ===========================================================================

(defn- epoch
  "Build a minimal `:rf/epoch-record`. `m` overrides any slot."
  [epoch-id m]
  (merge {:epoch-id     epoch-id
          :frame        :test/frame
          :outcome      :ok
          :db-before    {}
          :db-after     {}
          :trace-events []}
         m))

;; ===========================================================================
;; coerce-invariant  (normalization)
;; ===========================================================================

(deftest coerce-bare-fn
  (testing "a bare predicate normalizes to {:id … :check fn}"
    (let [{:keys [id check]} (inv/coerce-invariant 0 (fn [e] (map? (:db-after e))))]
      (is (= :invariant-0 id))
      (is (fn? check)))))

(deftest coerce-explicit-map
  (testing "an explicit map keeps its :id and resolves :check (or :pred)"
    (let [c (inv/coerce-invariant 3 {:id :my/inv :check (fn [_] true)})]
      (is (= :my/inv (:id c)))
      (is (fn? (:check c))))
    (testing ":pred is accepted as an alias for :check"
      (is (fn? (:check (inv/coerce-invariant 0 {:pred (fn [_] true)})))))
    (testing "a map without a check fn throws a structured error"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (inv/coerce-invariant 0 {:id :x}))))))

(deftest coerce-db-shorthand
  (testing "[:db path pred] checks the value at the path"
    (let [{:keys [check]} (inv/coerce-invariant 0 [:db [:cart :items] vector?])]
      (is (:ok? (check (epoch 1 {:db-after {:cart {:items []}}}))))
      (let [bad (check (epoch 1 {:db-after {:cart {:items 7}}}))]
        (is (false? (:ok? bad)))
        (is (= [:cart :items] (:path bad)))
        (is (= 7 (:actual bad))))))
  (testing "[:db path = expected] checks equality and carries :expected"
    (let [{:keys [check]} (inv/coerce-invariant 0 [:db [:n] = 5])]
      (is (:ok? (check (epoch 1 {:db-after {:n 5}}))))
      (let [bad (check (epoch 1 {:db-after {:n 4}}))]
        (is (false? (:ok? bad)))
        (is (= 5 (:expected bad)))
        (is (= 4 (:actual bad)))))))

(deftest coerce-bad-shape-throws
  (testing "a non-fn / non-vector / non-map invariant throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (inv/coerce-invariant 0 :not-an-invariant)))))

;; ===========================================================================
;; check-epoch  (one invariant × one epoch, never-throws)
;; ===========================================================================

(deftest check-epoch-holds-returns-nil
  (testing "a holding invariant returns nil"
    (let [c (inv/coerce-invariant 0 (fn [e] (map? (:db-after e))))]
      (is (nil? (inv/check-epoch c (epoch 1 {:db-after {}})))))))

(deftest check-epoch-violation-carries-spine
  (testing "a violation carries the diagnostic spine from the epoch"
    (let [c (inv/coerce-invariant 0 (fn [e] (pos? (get-in (:db-after e) [:n]))))
          v (inv/check-epoch c (epoch 7 {:event-id      :do/thing
                                         :trigger-event [:do/thing 42]
                                         :db-after      {:n -1}}))]
      (is (= :invariant-0 (:invariant v)))
      (is (= 7 (:epoch-id v)))
      (is (= :test/frame (:frame v)))
      (is (= :do/thing (:event v)))
      (is (= [:do/thing 42] (:trigger-event v))))))

(deftest check-epoch-isolates-predicate-exception
  (testing "a throwing predicate is caught and reported as a violation with :error"
    (let [c (inv/coerce-invariant 0 (fn [_] (throw (ex-info "boom" {}))))
          v (inv/check-epoch c (epoch 1 {}))]
      (is (some? v))
      (is (= :invariant-0 (:invariant v)))
      (is (string? (:error v))))))

;; ===========================================================================
;; first-bad-epoch  (pure post-hoc utility, rf2-5x1wt.6)
;; ===========================================================================

(deftest first-bad-epoch-nil-when-holds
  (testing "returns nil when the invariant holds across the whole tape"
    (let [tape [(epoch 1 {:db-after {:n 1}})
                (epoch 2 {:db-after {:n 2}})
                (epoch 3 {:db-after {:n 3}})]]
      (is (nil? (inv/first-bad-epoch tape (fn [e] (pos? (:n (:db-after e))))))))))

(deftest first-bad-epoch-returns-first-failing
  (testing "returns the FIRST failing epoch, enriched with trigger + db-diff + traces"
    (let [tape [(epoch 1 {:db-after {:n 1}})
                (epoch 2 {:trigger-event [:break]
                          :db-before     {:n 1}
                          :db-after      {:n -5 :extra true}
                          :trace-events  [{:operation :rf.event/run-start}]})
                (epoch 3 {:db-after {:n -9}})]
          bad  (inv/first-bad-epoch tape (fn [e] (>= (:n (:db-after e)) 0)))]
      (is (= 2 (:epoch-id bad)) "the FIRST failing epoch, not a later one")
      (is (= [:break] (:trigger-event bad)))
      (is (= #{:n :extra} (:db-diff bad)) "shallow top-level changed-key set")
      (is (= [{:operation :rf.event/run-start}] (:trace-events bad)))
      (is (= (get tape 1) (:epoch bad)) "the failing record is returned verbatim"))))

(deftest first-bad-epoch-empty-tape
  (testing "an empty (or nil) tape returns nil — no epoch can fail"
    (is (nil? (inv/first-bad-epoch [] (fn [_] false))))
    (is (nil? (inv/first-bad-epoch nil (fn [_] false))))))

(deftest first-bad-epoch-failure-in-first-epoch
  (testing "a failure in the FIRST epoch is returned"
    (let [tape [(epoch 1 {:db-after {:n -1}})
                (epoch 2 {:db-after {:n -2}})]
          bad  (inv/first-bad-epoch tape (fn [e] (pos? (:n (:db-after e)))))]
      (is (= 1 (:epoch-id bad))))))

(deftest first-bad-epoch-accepts-db-shorthand
  (testing "first-bad-epoch accepts the same authored shapes as with-invariants"
    (let [tape [(epoch 1 {:db-after {:cart {:items []}}})
                (epoch 2 {:db-after {:cart {:items :oops}}})]
          bad  (inv/first-bad-epoch tape [:db [:cart :items] vector?])]
      (is (= 2 (:epoch-id bad)))
      (is (= [:cart :items] (:path bad)))
      (is (= :oops (:actual bad))))))

;; ===========================================================================
;; on-epoch!  (report-once core — pure over a state atom)
;; ===========================================================================
;;
;; on-epoch! reports through `do-report`; we rebind the report sink so the
;; fixture's deliberate failures don't fail THIS suite, and count them.

(defn- with-captured-reports
  "Run `f`, capturing every `clojure.test` / `cljs.test` report into a
  vector instead of letting it reach the live reporter. Returns the
  captured reports."
  [f]
  (let [reports (atom [])]
    #?(:clj
       (binding [clojure.test/report (fn [m] (swap! reports conj m))]
         (f))
       :cljs
       ;; `cljs.test/report` is a multimethod (a `def`, not a `^:dynamic`
       ;; var), so we swap its root via `with-redefs` for the dynamic
       ;; extent of `f`. `do-report` resolves `report` at call time, so
       ;; every report `f` emits routes to our sink and is captured here.
       (with-redefs [cljs.test/report (fn [m] (swap! reports conj m))]
         (f)))
    @reports))

(deftest on-epoch-reports-violation-once-per-epoch
  (testing "a violation is reported once per (invariant, epoch) — re-fire does not double-count"
    (let [coerced (inv/coerce-invariants [(fn [e] (pos? (:n (:db-after e))))])
          state   (atom {:seen #{} :violations []})
          ep      (epoch 5 {:db-after {:n -3}})
          reports (with-captured-reports
                    (fn []
                      (inv/on-epoch! state coerced ep)
                      ;; A second fire of the SAME record (back-filled
                      ;; render re-notify) must NOT re-report.
                      (inv/on-epoch! state coerced ep)))]
      (is (= 1 (count (filter #(= :fail (:type %)) reports)))
          "exactly one :fail across two fires of the same epoch")
      (is (= 1 (count (:violations @state)))))))

(deftest on-epoch-reports-each-distinct-epoch
  (testing "distinct failing epochs each report once"
    (let [coerced (inv/coerce-invariants [(fn [e] (pos? (:n (:db-after e))))])
          state   (atom {:seen #{} :violations []})
          reports (with-captured-reports
                    (fn []
                      (inv/on-epoch! state coerced (epoch 1 {:db-after {:n -1}}))
                      (inv/on-epoch! state coerced (epoch 2 {:db-after {:n -2}}))))]
      (is (= 2 (count (filter #(= :fail (:type %)) reports))))
      (is (= 2 (count (:violations @state)))))))

(deftest on-epoch-reports-once-per-frame-same-epoch-id
  (testing "two frames with the SAME epoch-id each report once (dedup-key is per-frame, rf2-ilatz)"
    ;; `with-invariants` observes EVERY frame's epochs, so report-once must
    ;; be keyed per-frame. Epoch-ids are globally unique today (single
    ;; global counter), but should they ever become per-frame, two frames
    ;; could both emit epoch-id 1 — a frame-less dedup-key would SILENTLY
    ;; DROP the second frame's violation. This asserts both frames report.
    (let [coerced (inv/coerce-invariants [(fn [e] (pos? (:n (:db-after e))))])
          state   (atom {:seen #{} :violations []})
          ep-a    (epoch 1 {:frame :frame/a :db-after {:n -1}})
          ep-b    (epoch 1 {:frame :frame/b :db-after {:n -1}})
          reports (with-captured-reports
                    (fn []
                      (inv/on-epoch! state coerced ep-a)
                      (inv/on-epoch! state coerced ep-b)
                      ;; Re-fires of each frame's epoch must NOT re-report.
                      (inv/on-epoch! state coerced ep-a)
                      (inv/on-epoch! state coerced ep-b)))]
      (is (= 2 (count (filter #(= :fail (:type %)) reports)))
          "one :fail per frame even though both share epoch-id 1")
      (is (= 2 (count (:violations @state))))
      (is (= #{:frame/a :frame/b}
             (into #{} (map :frame) (:violations @state)))
          "the two violations come from the two distinct frames"))))

(deftest on-epoch-isolates-and-reports-broken-predicate
  (testing "a throwing predicate reports a :fail and never escapes on-epoch!"
    (let [coerced (inv/coerce-invariants [(fn [_] (throw (ex-info "boom" {})))])
          state   (atom {:seen #{} :violations []})
          reports (with-captured-reports
                    (fn [] (inv/on-epoch! state coerced (epoch 1 {}))))]
      (is (= 1 (count (filter #(= :fail (:type %)) reports)))))))

;; ===========================================================================
;; with-invariants  (LIVE fixture over a real frame, JVM)
;; ===========================================================================
;;
;; These spin up a real frame, dispatch real events, and let the
;; registered epoch listener check invariants after each committed epoch.
;; Reports are captured so deliberate violations are counted, not fatal.

#?(:clj
   (deftest with-invariants-passes-across-multiple-dispatches
     (testing "a holding invariant across multiple dispatches reports only passes"
       (rf/reg-frame :test/main {})
       (rf/reg-event-db :seed (fn [_ _] {:n 0}))
       (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
       (let [reports (with-captured-reports
                       (fn []
                         (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))
                                           [:db [:n] number?]]
                           (rf/dispatch-sync [:seed] {:frame :test/main})
                           (rf/dispatch-sync [:inc]  {:frame :test/main})
                           (rf/dispatch-sync [:inc]  {:frame :test/main}))))]
         (is (zero? (count (filter #(= :fail (:type %)) reports)))
             "no failures for a holding invariant")
         (is (= 2 (count (filter #(= :pass (:type %)) reports)))
             "one green :pass per invariant that held across the run")))))

#?(:clj
   (deftest with-invariants-reports-once-per-failing-epoch
     (testing "a failing invariant reports exactly once per failing epoch"
       (rf/reg-frame :test/main {})
       (rf/reg-event-db :seed (fn [_ _] {:n 0}))
       (rf/reg-event-db :dec  (fn [db _] (update db :n dec)))
       (let [reports (with-captured-reports
                       (fn []
                         (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))]
                           (rf/dispatch-sync [:seed] {:frame :test/main})  ; n=0  holds
                           (rf/dispatch-sync [:dec]  {:frame :test/main})  ; n=-1 fails
                           (rf/dispatch-sync [:dec]  {:frame :test/main})))) ; n=-2 fails
             fails   (filter #(= :fail (:type %)) reports)]
         (is (= 2 (count fails))
             "two failing epochs → two failures, one per epoch")))))

#?(:clj
   (deftest with-invariants-isolates-listener-exception
     (testing "a throwing invariant predicate does not break the run; it reports"
       (rf/reg-frame :test/main {})
       (rf/reg-event-db :seed (fn [_ _] {:n 0}))
       (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
       (let [body-completed (atom false)
             reports        (with-captured-reports
                              (fn []
                                (with-invariants [(fn [_] (throw (ex-info "boom" {})))]
                                  (rf/dispatch-sync [:seed] {:frame :test/main})
                                  (rf/dispatch-sync [:inc]  {:frame :test/main})
                                  (reset! body-completed true))))]
         (is (true? @body-completed)
             "the body ran to completion despite the throwing predicate")
         (is (pos? (count (filter #(= :fail (:type %)) reports)))
             "the broken predicate reported failures")))))

#?(:clj
   (deftest with-invariants-listener-unregistered-after-body
     (testing "the sentinel listener is removed on exit — later epochs are not observed"
       (rf/reg-frame :test/main {})
       (rf/reg-event-db :seed (fn [_ _] {:n 0}))
       (rf/reg-event-db :dec  (fn [db _] (update db :n dec)))
       (let [reports (with-captured-reports
                       (fn []
                         (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))]
                           (rf/dispatch-sync [:seed] {:frame :test/main}))
                         ;; This dispatch happens AFTER with-invariants exits;
                         ;; its (failing) epoch must NOT be observed.
                         (rf/dispatch-sync [:dec] {:frame :test/main})))]
         (is (zero? (count (filter #(= :fail (:type %)) reports)))
             "the post-body dispatch's violation was not observed")))))

#?(:clj
   (deftest with-invariants-works-with-destroyed-frame
     (testing "destroying the frame mid-run does not break the sentinel; the body completes"
       (rf/reg-frame :test/main {})
       (rf/reg-event-db :seed (fn [_ _] {:n 0}))
       (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
       (let [body-completed (atom false)]
         (with-captured-reports
           (fn []
             (with-invariants [(fn [e] (map? (:db-after e)))]
               (rf/dispatch-sync [:seed] {:frame :test/main})
               (rf/dispatch-sync [:inc]  {:frame :test/main})
               (rf/destroy-frame! :test/main)
               (reset! body-completed true))))
         (is (true? @body-completed)
             "the body — including the destroy and the post-destroy form — completed")
         (is (= [] (rf/epoch-history :test/main))
             "the destroyed frame's ring is empty afterward")))))
