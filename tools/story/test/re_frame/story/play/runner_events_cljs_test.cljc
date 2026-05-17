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
  - `coerce-script` lifts (verified inline below)."
  (:require [clojure.test :refer [deftest is testing #?(:clj use-fixtures)]]
            [re-frame.story.play.runner :as runner]
            #?@(:clj
                [[re-frame.core             :as rf]
                 [re-frame.frame            :as frame]
                 [re-frame.registrar        :as registrar]
                 [re-frame.story            :as story]
                 [re-frame.story.assertions :as assertions]
                 [re-frame.story.async      :as async]
                 [re-frame.story.config     :as config]
                 [re-frame.story.loaders    :as loaders]
                 [re-frame.story.play       :as legacy-play]
                 [re-frame.story.play.runner-events :as re]
                 [re-frame.machines         :as machines]
                 [re-frame.substrate.plain-atom :as plain-atom]])))

;; ---- CLJS-runnable: pure-data coverage of the script lift ---------------

(deftest bare-event-vector-lift-via-coerce
  (testing "a bare [:event ...] entry in :script lifts to [:dispatch <vec>]"
    (is (= [[:dispatch [:rt/touch]] [:dispatch [:rt/touch]]]
           (runner/coerce-script [[:rt/touch] [:rt/touch]])))
    (let [spec (runner/parse-spec {:auto-run? false
                                    :script    [[:rt/touch]
                                                [:wait 0]
                                                [:assert-db [:k] nil]]})]
      (is (= [[:dispatch [:rt/touch]]
              [:wait 0]
              [:assert-db [:k] nil]]
             (:script spec))))))

;; ---- JVM-only: integration tests against a live re-frame frame ----------

#?(:clj
   (defn reset-all [test-fn]
     (story/clear-all!)
     (registrar/clear-all!)
     (reset! frame/frames {})
     (try (rf/init! plain-atom/adapter)
          (catch clojure.lang.ExceptionInfo _ nil))
     (require 're-frame.machines :reload)
     (machines/reset-timers!)
     (loaders/clear-watchers!)
     (config/set-global-args! {})
     (reset! assertions/trace-accumulators {})
     (reset! legacy-play/stepper-state    {})
     (reset! re/run-state                 {})
     (story/install-canonical-vocabulary!)
     (frame/ensure-default-frame!)
     (test-fn)))

#?(:clj (use-fixtures :each reset-all))

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
           (let [reg!  (resolve 're-frame.trace.tooling/register-trace-cb!)
                 unreg (resolve 're-frame.trace.tooling/remove-trace-cb!)]
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
               (let [unreg (resolve 're-frame.trace.tooling/remove-trace-cb!)]
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
