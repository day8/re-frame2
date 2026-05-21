(ns re-frame.story.play.runner-events-cljs-test
  "Integration tests for the rich-DSL play runner against a live
  re-frame frame (rf2-8i2a9).

  Most tests live under a JVM `#?(:clj ...)` reader gate because
  `re-frame.story.async/deref-blocking` is JVM-only. CLJS tests of
  the runner-events surface live under the `dom` cljs corpus or the
  Playwright browser specs (see TESTING matrix). The pure
  `parse-spec` / `coerce-script` paths are exercised by
  `runner-test` (no re-frame deps); those run on both runtimes.

  CLJS-side coverage that survives the JVM gate:

  - The runner's pure state machine (via `runner-test`).
  - The chip + banner label helpers (via `play-status-cljs-test`).
  - The dispatch→assertion outcome-matching bridge (`failed-since` +
    `dispatch-step-result`, exercised directly below — rf2-uhq5j)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.play.runner :as runner]
            [re-frame.core              :as rf]
            [re-frame.frame             :as frame]
            [re-frame.story             :as story]
            [re-frame.story.assertions  :as assertions]
            [re-frame.story.loaders     :as loaders]
            [re-frame.story.play        :as legacy-play]
            [re-frame.story.play.runner-events :as re]
            [re-frame.machines          :as machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.registrar         :as registrar]
            ;; `re-frame.story.async/deref-blocking` is JVM-only (it
            ;; blocks a thread) and `config/set-global-args!` is only
            ;; needed by the JVM `reset-all` lineage — the integration
            ;; tests below that use them are themselves JVM-gated.
            #?@(:clj [[re-frame.story.async  :as async]
                      [re-frame.story.config :as config]])))

;; ---- CLJS+JVM: the dispatch→assertion outcome-matching bridge -----------
;;
;; `failed-since` + `dispatch-step-result` (runner_events.cljc) turn a
;; handler-recorded `:rf.assert/*` failure into a runner `step-fail` so
;; the play's terminal status flips. Before rf2-uhq5j these were hit only
;; transitively via the heavy JVM `run-blocking` integration tests below;
;; this section drives them directly on BOTH runtimes by seeding a known
;; `:rf.story/assertions` vector on a live frame, then asserting the
;; step-fail / step-skip shape + `:message`/`:expected`/`:actual`
;; projection. Both fns are private — reached via var-quote (the
;; established Story-test seam pattern, e.g. play/listener-for-frame).

(def ^:private failed-since        @#'re/failed-since)
(def ^:private dispatch-step-result @#'re/dispatch-step-result)

(def ^:private bridge-frame :story.bridge/frame)

(defn- seed-assertions!
  "Append each record in `recs` to `bridge-frame`'s `:rf.story/assertions`
  via a real `dispatch-sync`, mirroring how the `:rf.assert/*` handlers
  land their records. Returns the post-seed assertion count."
  [recs]
  (doseq [r recs]
    (rf/dispatch-sync [::seed r] {:frame bridge-frame}))
  (count (:rf.story/assertions (rf/get-frame-db bridge-frame))))

(defn- bridge-reset!
  "Single namespace fixture (rf2-uhq5j unified the prior JVM-only
  `reset-all`). Resets every Story side-table + the re-frame frame
  registry, reinstalls the canonical vocabulary, and registers the
  bridge test frame + its `::seed` event. Runs on both runtimes; the
  JVM-only `:reload` of machines + `config/set-global-args!` are gated."
  [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  #?(:clj (require 're-frame.machines :reload))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  #?(:clj (config/set-global-args! {}))
  (reset! assertions/trace-accumulators {})
  (reset! legacy-play/stepper-state {})
  (reset! re/run-state {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (frame/reg-frame bridge-frame {:doc "outcome-matching bridge test frame"})
  (rf/reg-event-db ::seed
    (fn [db [_ rec]]
      (update db :rf.story/assertions (fnil conj []) rec)))
  (test-fn))

(use-fixtures :each bridge-reset!)

(deftest failed-since-filters-to-failures-after-prev-count
  (testing "failed-since returns only the :passed? false records appended
            after prev-count — the dispatched event's contribution"
    (let [prev (seed-assertions! [{:passed? true  :id :rf.assert/path-equals}])
          _    (seed-assertions! [{:passed? false :id :rf.assert/path-equals
                                   :expected :loaded :actual :idle :message "boom"}
                                  {:passed? true  :id :rf.assert/path-equals}])
          out  (failed-since bridge-frame prev)]
      (is (= 1 (count out))
          "only the one new failing record is returned — the earlier pass
           (before prev-count) and the trailing pass are excluded")
      (is (= :loaded (:expected (first out))))
      (is (= :idle   (:actual   (first out)))))))

(deftest failed-since-empty-when-no-new-failures
  (testing "failed-since is empty when nothing failed since prev-count"
    (let [prev (seed-assertions! [{:passed? false :id :rf.assert/path-equals}])]
      ;; prior failure is BEFORE prev-count; a clean pass lands after.
      (seed-assertions! [{:passed? true :id :rf.assert/path-equals}])
      (is (empty? (failed-since bridge-frame prev))
          "the pre-prev failure is excluded; the post-prev pass is filtered"))))

(deftest failed-since-tolerates-prev-count-overshoot
  (testing "failed-since clamps prev-count to the current length (subvec
            never throws even if the accumulator shrank)"
    (seed-assertions! [{:passed? false :id :rf.assert/path-equals}])
    (is (= [] (failed-since bridge-frame 999))
        "an out-of-range prev-count yields an empty slice, not an error")))

(deftest dispatch-step-result-projects-failure-to-step-fail
  (testing "a new failing assertion since prev becomes a runner step-fail
            carrying the record's :expected / :actual / :message"
    (let [step [:dispatch-sync [:rf.assert/path-equals [:status] :loaded]]
          prev (seed-assertions! [])
          _    (seed-assertions! [{:passed? false :id :rf.assert/path-equals
                                   :expected :loaded :actual :idle
                                   :message  "expected :loaded, got :idle"}])
          out  (dispatch-step-result bridge-frame prev 3 step)]
      (is (false? (:passed? out)) "the step result flips to fail")
      (is (= :dispatch-sync (:type out)) "step-type is preserved")
      (is (= 3 (:idx out)))
      (is (= step (:step out)))
      (is (= :loaded (:expected out)))
      (is (= :idle   (:actual out)))
      (is (= "expected :loaded, got :idle" (:message out))))))

(deftest dispatch-step-result-synthesizes-message-when-record-has-none
  (testing "when the failing record carries no :message, the bridge
            synthesizes one from :id / :payload / :expected / :actual"
    (let [step [:dispatch [:rf.assert/path-equals [:k] 1]]
          prev (seed-assertions! [])
          _    (seed-assertions! [{:passed? false :id :rf.assert/path-equals
                                   :payload  [[:k] 1] :expected 1 :actual 0}])
          out  (dispatch-step-result bridge-frame prev 0 step)]
      (is (false? (:passed? out)))
      (is (re-find #":rf.assert/path-equals" (:message out))
          "the synthesized message names the assertion id")
      (is (re-find #"expected 1" (:message out)))
      (is (re-find #"actual 0"   (:message out))))))

(deftest dispatch-step-result-skips-when-no-failure
  (testing "a dispatch that contributed no failing assertion is a
            step-skip — :passed? nil, so it doesn't count toward failures"
    (let [step [:dispatch-sync [:some/event]]
          prev (seed-assertions! [{:passed? true :id :rf.assert/path-equals}])]
      ;; A clean pass lands after prev — no failures contributed.
      (seed-assertions! [{:passed? true :id :rf.assert/path-equals}])
      (let [out (dispatch-step-result bridge-frame prev 1 step)]
        (is (nil? (:passed? out)) "step-skip leaves :passed? nil")
        (is (= :dispatch-sync (:type out)))
        (is (= 1 (:idx out)))
        (is (= step (:step out)))))))

;; ---- CLJS-runnable: pure-data coverage of the script lift ---------------
;;
;; (rf2-uhq5j) the former `bare-event-vector-lift-via-coerce` test here
;; was removed: it duplicated the pure `coerce-script` / `parse-spec`
;; contract already asserted in `runner-test`
;; (coerce-script-lifts-bare-event-vectors + parse-spec-lifts-bare-
;; vectors-inside-map). The wrong-layer copy added no signal.

;; ---- JVM-only: integration tests against a live re-frame frame ----------
;;
;; These reuse the namespace-wide `bridge-reset!` fixture above (which
;; unified the prior JVM-only `reset-all`).

#?(:clj
   (defn- run-blocking
     "Drive `run!` and block until terminal status is reached or
     `timeout-ms` expires."
     ([variant-id] (run-blocking variant-id 5000))
     ([variant-id timeout-ms]
      (let [done (atom nil)]
        (re/run! variant-id (fn [state] (reset! done state)))
        (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
          (loop []
            (cond
              @done @done
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "run-blocking timeout"
                              {:variant-id variant-id
                               :state      @re/run-state}))
              :else (do (Thread/sleep 5) (recur)))))))))

;; ---- dispatch + dispatch-sync round-trip --------------------------------

#?(:clj
   (deftest dispatch-sync-fires-event
     (testing ":dispatch-sync step fires the event into the variant's frame"
       (let [seen (atom [])]
         (rf/reg-event-db :rt/inc
           (fn [db _] (swap! seen conj :hit)
             (update db :n (fnil inc 0))))
         (story/reg-variant :story.runner/sync
           {:events []
            :play-script {:auto-run? false
                          :script    [[:dispatch-sync [:rt/inc]]
                                      [:dispatch-sync [:rt/inc]]]}})
         (async/deref-blocking (story/run-variant :story.runner/sync) 5000)
         (let [final (run-blocking :story.runner/sync)]
           (is (= :pass (:status final)))
           (is (= [:hit :hit] @seen))
           (is (= 2 (count (:results final)))))))))

#?(:clj
   (deftest assert-db-equals-pass-and-fail
     (testing ":assert-db pass / fail outcomes against frame app-db"
       (rf/reg-event-db :rt/set-status
         (fn [db [_ v]] (assoc db :status v)))
       (story/reg-variant :story.runner/assert-db
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-status :loaded]]
                                    [:assert-db [:status] :loaded]
                                    [:assert-db [:status] :wrong]]}})
       (async/deref-blocking (story/run-variant :story.runner/assert-db) 5000)
       (let [final (run-blocking :story.runner/assert-db)]
         (is (= :fail (:status final)))
         (is (= 1 (:failures final)))
         (let [assert-results (filterv #(= :assert-db (:type %)) (:results final))]
           (is (= 2 (count assert-results)))
           (is (true?  (:passed? (nth assert-results 0))))
           (is (false? (:passed? (nth assert-results 1))))
           (is (= :wrong  (:expected (nth assert-results 1))))
           (is (= :loaded (:actual   (nth assert-results 1)))))))))

#?(:clj
   (deftest assert-db-pred-form
     (testing ":assert-db :pred resolves a symbol and applies it"
       (rf/reg-event-db :rt/set-n
         (fn [db [_ v]] (assoc db :n v)))
       (story/reg-variant :story.runner/pred
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-n 7]]
                                    [:assert-db [:n] :pred 'clojure.core/pos?]
                                    [:assert-db [:n] :pred 'clojure.core/neg?]]}})
       (async/deref-blocking (story/run-variant :story.runner/pred) 5000)
       (let [final  (run-blocking :story.runner/pred)
             results (filterv #(= :assert-db (:type %)) (:results final))]
         (is (= :fail (:status final)))
         (is (true?  (:passed? (nth results 0))))
         (is (false? (:passed? (nth results 1))))))))

#?(:clj
   (deftest assert-db-pred-fn-direct
     (testing ":assert-db :pred accepts a fn directly (rf2-inbad — advanced-CLJS-safe)"
       (rf/reg-event-db :rt/set-n
         (fn [db [_ v]] (assoc db :n v)))
       (story/reg-variant :story.runner/pred-fn
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-n 7]]
                                    [:assert-db [:n] :pred pos?]
                                    [:assert-db [:n] :pred neg?]
                                    [:assert-db [:n] :pred (fn [x] (= x 7))]]}})
       (async/deref-blocking (story/run-variant :story.runner/pred-fn) 5000)
       (let [final   (run-blocking :story.runner/pred-fn)
             results (filterv #(= :assert-db (:type %)) (:results final))]
         (is (= :fail (:status final))
             "neg? against 7 fails — overall status is :fail")
         (is (= 3 (count results)))
         (is (true?  (:passed? (nth results 0))) "pos? against 7 passes")
         (is (false? (:passed? (nth results 1))) "neg? against 7 fails")
         (is (true?  (:passed? (nth results 2))) "anonymous fn passes")
         (is (re-find #"<fn>" (:message (nth results 1)))
             "failure message renders fn ref as <fn> — no compiler-munged leakage")))))

#?(:clj
   (deftest assert-db-pred-bogus-symbol-fails-gracefully
     (testing ":assert-db :pred with an unresolvable symbol fails gracefully (rf2-inbad)"
       (rf/reg-event-db :rt/set-n
         (fn [db [_ v]] (assoc db :n v)))
       (story/reg-variant :story.runner/pred-bogus
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-n 7]]
                                    [:assert-db [:n] :pred 'no.such.ns/missing-pred]]}})
       (async/deref-blocking (story/run-variant :story.runner/pred-bogus) 5000)
       (let [final   (run-blocking :story.runner/pred-bogus)
             results (filterv #(= :assert-db (:type %)) (:results final))]
         (is (= :fail (:status final)))
         (is (= 1 (count results)))
         (is (false? (:passed? (first results))))
         (is (re-find #"could not resolve" (:message (first results)))
             "user-facing message mentions resolution failure")))))

#?(:clj
   (deftest run-records-results-in-order
     (testing "results vector reflects step order"
       (rf/reg-event-db :rt/touch
         (fn [db _] (update db :touches (fnil inc 0))))
       (story/reg-variant :story.runner/order
         {:events []
          :play-script
          {:auto-run? false
           :script [[:dispatch-sync [:rt/touch]]
                    [:dispatch-sync [:rt/touch]]
                    [:dispatch-sync [:rt/touch]]
                    [:assert-db [:touches] 3]]}})
       (async/deref-blocking (story/run-variant :story.runner/order) 5000)
       (let [final (run-blocking :story.runner/order)]
         (is (= :pass (:status final)))
         (is (= 4 (count (:results final))))
         (is (= [0 1 2 3] (mapv :idx (:results final))))))))

#?(:clj
   (deftest bare-vector-with-unknown-event-still-dispatches
     (testing "a bare event vector with no registered handler still dispatches; the play status does not fail because dispatch is a no-assertion step"
       (story/reg-variant :story.runner/bare-unknown
         {:events []
          :play-script {:auto-run? false
                        :script    [[:does-not-exist :nope]]}})
       (async/deref-blocking (story/run-variant :story.runner/bare-unknown) 5000)
       (let [final (run-blocking :story.runner/bare-unknown)]
         (is (some? final))
         (is (= 1 (count (:results final))))))))

;; ---- variant-play-script resolution --------------------------------------

#?(:clj
   (deftest variant-play-script-from-body
     (testing "variant-play-script resolves the :play-script slot on a variant"
       (story/reg-variant :story.runner/resolved
         {:events []
          :play-script {:script [[:dispatch [:foo]]]
                        :auto-run? false
                        :name "named"}})
       (let [spec (re/variant-play-script :story.runner/resolved)]
         (is (= [[:dispatch [:foo]]] (:script spec)))
         (is (false? (:auto-run? spec)))
         (is (= "named" (:name spec)))))))

#?(:clj
   (deftest variant-play-script-missing
     (testing "variants without :play-script resolve to an empty spec"
       (story/reg-variant :story.runner/no-script {:events []})
       (let [spec (re/variant-play-script :story.runner/no-script)]
         (is (= [] (:script spec)))
         (is (true? (:auto-run? spec)))))))

;; ---- auto-run gating ------------------------------------------------------

#?(:clj
   (deftest auto-run-skips-when-disabled
     (testing "auto-run! is a no-op when :auto-run? is false"
       (let [seen (atom 0)]
         (rf/reg-event-db :rt/touch
           (fn [db _] (swap! seen inc) db))
         (story/reg-variant :story.runner/no-auto
           {:events []
            :play-script {:auto-run? false
                          :script    [[:dispatch-sync [:rt/touch]]]}})
         (async/deref-blocking (story/run-variant :story.runner/no-auto) 5000)
         (re/auto-run! :story.runner/no-auto)
         (is (zero? @seen))))))

;; ---- run-state lifecycle ----------------------------------------------

#?(:clj
   (deftest run-state-clears-and-resets
     (testing "successive runs reset :results and re-walk every step"
       (rf/reg-event-db :rt/touch
         (fn [db _] (update db :touches (fnil inc 0))))
       (story/reg-variant :story.runner/reset
         {:events []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]
                                    [:assert-db [:touches] 1]]}})
       (async/deref-blocking (story/run-variant :story.runner/reset) 5000)
       (run-blocking :story.runner/reset)
       (let [first-state (re/current-state :story.runner/reset)]
         (is (= :pass (:status first-state))))
       (run-blocking :story.runner/reset)
       (let [second-state (re/current-state :story.runner/reset)]
         (is (= :fail (:status second-state)))
         (is (= 1 (:failures second-state)))
         (is (= 2 (count (:results second-state))))))))

;; ---- trace integration --------------------------------------------------

#?(:clj
   (deftest each-step-emits-a-trace-event
     (testing "the runner emits a :rf.story.play/step trace event per step"
       (let [trace-events (atom [])
             listener-id  ::play-trace-test]
         (rf/reg-event-db :rt/touch
           (fn [db _] (update db :touches (fnil inc 0))))
         (try
           (require '[re-frame.trace.tooling :as trace-tooling])
           (let [reg!  (resolve 're-frame.trace.tooling/register-listener!)
                 unreg (resolve 're-frame.trace.tooling/unregister-listener!)]
             (reg! listener-id
               (fn [ev]
                 (when (= :rf.story.play/step (:operation ev))
                   (swap! trace-events conj ev))))
             (story/reg-variant :story.runner/trace
               {:events []
                :play-script {:auto-run? false
                              :script    [[:dispatch-sync [:rt/touch]]
                                          [:assert-db [:touches] 1]]}})
             (async/deref-blocking (story/run-variant :story.runner/trace) 5000)
             (run-blocking :story.runner/trace)
             (is (>= (count @trace-events) 2)
                 "at least one trace per step landed on the bus")
             (let [first-ev (first @trace-events)]
               (is (= :rf.story.play/step (:operation first-ev)))
               (is (= :story.runner/trace (get-in first-ev [:tags :frame]))))
             (unreg listener-id))
           (finally
             (try
               (let [unreg (resolve 're-frame.trace.tooling/unregister-listener!)]
                 (when unreg (unreg listener-id)))
               (catch Throwable _ nil))))))))

;; ---- DOM-step skip on JVM (no DOM available) ----------------------------

#?(:clj
   (deftest dom-step-skipped-on-jvm
     (testing "DOM-touching steps record :skipped? on JVM (no DOM)"
       (story/reg-variant :story.runner/dom
         {:events []
          :play-script {:auto-run? false
                        :script    [[:click "button.foo"]
                                    [:assert-dom "div" :visible]]}})
       (async/deref-blocking (story/run-variant :story.runner/dom) 5000)
       (let [final   (run-blocking :story.runner/dom)
             results (:results final)]
         (is (= :fail (:status final)))
         (is (every? (fn [r] (or (:skipped? r) (true? (:passed? r)))) results))))))

;; ---- multi-play (rf2-tl7zk) ----------------------------------------------

#?(:clj
   (defn- run-play-blocking
     "Drive a specific play via `run-play!` and block until terminal."
     ([variant-id play-key] (run-play-blocking variant-id play-key 5000))
     ([variant-id play-key timeout-ms]
      (let [done (atom nil)]
        (re/run-play! variant-id play-key (fn [s] (reset! done s)))
        (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
          (loop []
            (cond
              @done @done
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "run-play-blocking timeout"
                              {:variant-id variant-id
                               :play-key   play-key}))
              :else (do (Thread/sleep 5) (recur)))))))))

#?(:clj
   (deftest variant-plays-resolves-plays-vector
     (testing "variant-plays returns a vector of parsed plays"
       (rf/reg-event-db :rt/inc
         (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.multi/two
         {:events []
          :plays  [{:name "happy"
                    :auto-run? false
                    :script    [[:dispatch-sync [:rt/inc]]
                                [:assert-db [:n] 1]]}
                   {:name "error"
                    :auto-run? false
                    :script    [[:dispatch-sync [:rt/inc]]
                                [:assert-db [:n] 99]]}]})
       (let [plays (re/variant-plays :story.multi/two)]
         (is (= 2 (count plays)))
         (is (= ["happy" "error"] (mapv :name plays)))
         ;; First play's auto-run? was explicitly false here, so no
         ;; positional default applies.
         (is (every? false? (map :auto-run? plays)))))))

#?(:clj
   (deftest variant-plays-wraps-legacy-play-script
     (testing "variant-plays wraps a legacy :play-script body in a one-entry vector"
       (story/reg-variant :story.multi/legacy
         {:events []
          :play-script {:name "lone" :script [[:dispatch [:a]]]}})
       (let [plays (re/variant-plays :story.multi/legacy)]
         (is (= 1 (count plays)))
         (is (= "lone" (:name (first plays))))))))

#?(:clj
   (deftest run-play-keys-state-per-play
     (testing "running each play stores state under [variant-id play-key]"
       (rf/reg-event-db :rt/inc
         (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.multi/keyed
         {:events []
          :plays  [{:name "first"  :auto-run? false
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 1]]}
                   {:name "second" :auto-run? false
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 2]]}]})
       (async/deref-blocking (story/run-variant :story.multi/keyed) 5000)
       (let [first-state  (run-play-blocking :story.multi/keyed "first")
             second-state (run-play-blocking :story.multi/keyed "second")]
         (is (= :pass (:status first-state)))
         (is (= :pass (:status second-state)))
         ;; Per-play state preserved.
         (is (= :pass (:status (re/current-state-for-play :story.multi/keyed "first"))))
         (is (= :pass (:status (re/current-state-for-play :story.multi/keyed "second"))))
         ;; The latest run-state slot tracks the most recent run.
         (let [latest (re/current-state :story.multi/keyed)]
           (is (= :pass (:status latest)))
           (is (= "second" (:play-key latest))))))))

#?(:clj
   (deftest run-play-sets-active-play
     (testing "run-play! also sets the active-play key the toolbar reads"
       (rf/reg-event-db :rt/touch
         (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.multi/active
         {:events []
          :plays  [{:name "alpha" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}
                   {:name "beta"  :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}]})
       (async/deref-blocking (story/run-variant :story.multi/active) 5000)
       (run-play-blocking :story.multi/active "beta")
       (is (= "beta" (re/active-play-key :story.multi/active))))))

#?(:clj
   (deftest select-play-without-running
     (testing "select-play! changes the active key but does not run"
       (rf/reg-event-db :rt/touch
         (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.multi/select
         {:events []
          :plays  [{:name "one" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}
                   {:name "two" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}]})
       (async/deref-blocking (story/run-variant :story.multi/select) 5000)
       (re/select-play! :story.multi/select "two")
       (is (= "two" (re/active-play-key :story.multi/select)))
       ;; No run-state slot was populated by select-play! alone.
       (is (nil? (re/current-state-for-play :story.multi/select "two"))))))

#?(:clj
   (deftest run-all-plays-sequences-runs
     (testing "run-all-plays! drives every play and resolves with per-play results"
       (rf/reg-event-db :rt/touch
         (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.multi/all
         {:events []
          :plays  [{:name "a" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]
                             [:assert-db [:n] 1]]}
                   {:name "b" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]
                             [:assert-db [:n] 2]]}
                   {:name "c" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]
                             [:assert-db [:n] 3]]}]})
       (async/deref-blocking (story/run-variant :story.multi/all) 5000)
       (let [done    (atom nil)
             _       (re/run-all-plays! :story.multi/all
                                        (fn [final] (reset! done final)))
             deadline (+ (System/currentTimeMillis) 5000)]
         (loop []
           (cond
             (some? @done) nil
             (> (System/currentTimeMillis) deadline)
             (throw (ex-info "run-all-plays timeout" {}))
             :else (do (Thread/sleep 5) (recur))))
         (let [results @done]
           (is (= 3 (count results)))
           (is (every? #(= :pass (:status %)) results))
           (is (= ["a" "b" "c"] (mapv :play-key results))))))))

#?(:clj
   (deftest auto-run-multi-runs-only-opted-in-plays
     (testing "auto-run! runs only plays whose :auto-run? is true (per-position defaults)"
       (rf/reg-event-db :rt/inc
         (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.multi/auto
         {:events []
          :plays  [{:name "first-default-true"
                    ;; :auto-run? omitted → first defaults to true
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 1]]}
                   {:name "second-default-false"
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 99]]}
                   {:name "third-opted-in"
                    :auto-run? true
                    :script [[:dispatch-sync [:rt/inc]]]}]})
       (async/deref-blocking (story/run-variant :story.multi/auto) 5000)
       (let [done (atom nil)]
         ;; Multi-play auto-run sequences the opted-in plays + resolves
         ;; the done-cb ONCE with the per-play terminal-state vector.
         (re/auto-run! :story.multi/auto (fn [final] (reset! done final)))
         (let [deadline (+ (System/currentTimeMillis) 5000)]
           (loop []
             (cond
               (some? @done) nil
               (> (System/currentTimeMillis) deadline)
               (throw (ex-info "auto-run multi timeout" {:done @done}))
               :else (do (Thread/sleep 5) (recur)))))
         (let [results @done]
           (is (= 2 (count results))
               "first + third auto-ran; second did not")
           (is (= ["first-default-true" "third-opted-in"]
                  (mapv :play-key results))))
         ;; second play was NOT auto-run — its state slot stays empty.
         (is (nil? (re/current-state-for-play :story.multi/auto
                                              "second-default-false")))))))

#?(:clj
   (deftest mutual-exclusion-warning-emits-once
     (testing "variants declaring BOTH :play-script and :plays warn ONCE per variant"
       ;; Bypass schema validation by writing directly into the side-table.
       (require '[re-frame.story.registrar :as registrar])
       (let [registrar (resolve 're-frame.story.registrar/kind->id->body)]
         (swap! @registrar assoc-in
                [:variant :story.multi/both]
                {:play-script [[:dispatch [:legacy]]]
                 :plays       [{:name "p" :script [[:dispatch [:plays]]]}]})
         ;; First read warns; the call returns the :plays-derived plays vector.
         (let [out1 (with-out-str
                      (binding [*err* *out*]
                        (re/variant-plays :story.multi/both)))
               out2 (with-out-str
                      (binding [*err* *out*]
                        (re/variant-plays :story.multi/both)))]
           (is (re-find #":play-script.*:plays" out1)
               "first read prints the both-slots warning")
           (is (empty? out2)
               "subsequent reads stay silent — warning is one-shot per variant"))))))

;; ---- rf2-ftow6: concurrent-run race fix ---------------------------------
;;
;; The Playwright matrix-before-load scenario could overshoot counter
;; increments when `runtime/run-phase-4!` and the shell's
;; `selection-watcher` both fired `runner-events/run!` against the same
;; variant in quick succession. The blanket setTimeout-0 between every
;; step let the second `run!` reset state mid-script, and BOTH loops
;; then walked the shared state, double-dispatching the increment
;; events. The fix stamps a unique `:run-token` on every fresh run and
;; bails any loop whose token no longer matches the state slot — see
;; `run-loop!`.

#?(:clj
   (deftest run-stamps-token-on-state
     (testing "run! writes a :run-token onto the started state map so
              concurrent-run detection can compare loops"
       (rf/reg-event-db :rt/touch (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.runner/token
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]]}})
       (async/deref-blocking (story/run-variant :story.runner/token) 5000)
       (let [final (run-blocking :story.runner/token)]
         (is (some? (:run-token final))
             "every run! call stamps a non-nil token onto the started state")))))

#?(:clj
   (deftest fresh-run-token-replaces-prior
     (testing "back-to-back run! calls stamp DIFFERENT tokens — the newer
              token wins and the stale loop (had it still been queued)
              would bail on mismatch"
       (rf/reg-event-db :rt/touch (fn [db _] (update db :n (fnil inc 0))))
       (story/reg-variant :story.runner/token-rotate
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]]}})
       (async/deref-blocking (story/run-variant :story.runner/token-rotate) 5000)
       (let [first-final  (run-blocking :story.runner/token-rotate)
             _            (run-blocking :story.runner/token-rotate)
             second-state (re/current-state :story.runner/token-rotate)]
         (is (some? (:run-token first-final)))
         (is (some? (:run-token second-state)))
         (is (not= (:run-token first-final) (:run-token second-state))
             "the second run! stamps a fresh token — concurrent stale loops
              detect the swap and abort")))))

#?(:clj
   (deftest sync-steps-do-not-yield-between
     (testing "rf2-ftow6: a script of pure :dispatch-sync + :assert-db steps
              runs end-to-end without intermediate yields — no extra event
              dispatches sneak in mid-script. Probes the legacy execute-play!
              semantics the migration accidentally lost."
       (let [n (atom 0)]
         (rf/reg-event-db :rt/inc (fn [db _] (swap! n inc) (update db :n (fnil inc 0))))
         (story/reg-variant :story.runner/sync-tight
           {:events      [[:rt/inc] [:rt/inc] [:rt/inc]] ; seed: n=3
            :play-script {:auto-run? false
                          :script    [[:dispatch-sync [:rt/inc]]
                                      [:dispatch-sync [:rt/inc]]
                                      [:dispatch-sync [:rt/inc]]
                                      [:assert-db [:n] 6]]}})
         (async/deref-blocking (story/run-variant :story.runner/sync-tight) 5000)
         (let [final (run-blocking :story.runner/sync-tight)]
           (is (= :pass (:status final))
               "the three increments + assertion run atomically — final count is exactly 6")
           (is (= 6 @n)
               "no stray increments leaked from concurrent paths"))))))
