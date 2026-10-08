(ns re-frame.story.play.runner-events-cljs-test
  "The play runner against a live re-frame frame. The run-to-terminal
  integration tests are JVM-gated because `re-frame.story.async/deref-blocking`
  blocks a thread; the bridge, abort and classifier tests run on both
  runtimes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.play.runner :as rf.story.play.runner]
            [re-frame.core              :as rf]
            [re-frame.cofx :as rf.cofx]
            [re-frame.frame             :as rf.frame]
            [re-frame.story             :as rf.story]
            [re-frame.story.loaders     :as rf.story.loaders]
            [re-frame.story.play        :as rf.story.play]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            ;; The narrative attribution tests read a live epoch tape; requiring
            ;; the epoch artefact installs its capture hooks so the tape is real.
            [re-frame.epoch             :as rf.epoch]
            [re-frame.machines          :as rf.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.registrar         :as registrar]
            #?@(:clj [[re-frame.story.async  :as rf.story.async]
                      [re-frame.story.config :as rf.story.config]
                      [re-frame.story.play.evidence :as rf.story.play.evidence]
                      [re-frame.story.fingerprint :as rf.story.fingerprint]])))

;; ---- CLJS+JVM: the dispatch→assertion outcome-matching bridge -----------
;;
;; `failed-since` + `dispatch-step-result` turn a handler-recorded
;; `:rf.assert/*` failure into a runner step-fail so the play's terminal
;; status flips. Both are private, reached via var-quote.

(def ^:private failed-since        @#'rf.story.play.runner-events/failed-since)
(def ^:private dispatch-step-result @#'rf.story.play.runner-events/dispatch-step-result)

(def ^:private bridge-frame :story.bridge/frame)

(defn- seed-assertions!
  "Append each record in `recs` to `bridge-frame`'s `:rf.story/assertions`
  via a real `dispatch-sync`. Returns the post-seed assertion count."
  [recs]
  (doseq [r recs]
    (rf/dispatch-sync [::seed r] {:frame bridge-frame}))
  (count (:rf.story/assertions (rf/app-db-value bridge-frame))))

(defn- bridge-reset! [test-fn]
  (rf.story/clear-all!)
  (registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Each attribution test reads only its own freshly-captured tape.
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  #?(:clj (require 're-frame.machines :reload))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  #?(:clj (rf.story.config/set-global-args! {}))
  (reset! rf.story.play/stepper-state {})
  (reset! rf.story.play.runner-events/run-state {})
  (reset! rf.story.play.runner-events/step-boundaries {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id bridge-frame :doc "outcome-matching bridge test frame"})
  (rf/reg-event ::seed
    (fn [{:keys [db]} [_ rec]]
      {:db (update db :rf.story/assertions (fnil conj []) rec)}))
  (test-fn))

(use-fixtures :each bridge-reset!)

(deftest failed-since-tolerates-prev-count-overshoot
  ;; the accumulator can shrink under a reset; subvec must not throw
  (seed-assertions! [{:passed? false :id :rf.assert/path-equals}])
  (is (= [] (failed-since bridge-frame 999))))

(deftest dispatch-step-result-projects-failure-to-step-fail
  (let [step [:dispatch-sync [:rf.assert/path-equals [:status] :loaded]]
        prev (seed-assertions! [])
        _    (seed-assertions! [{:passed? false :assertion :rf.assert/path-equals
                                 :expected :loaded :actual :idle
                                 :reason   "expected :loaded, got :idle"}])]
    ;; :recorded? — the record is already on the accumulator, so the unified
    ;; result must not count it twice
    (is (= {:idx       3
            :step      step
            :type      :dispatch-sync
            :passed?   false
            :expected  :loaded
            :actual    :idle
            :message   "expected :loaded, got :idle"
            :recorded? true}
           (dispatch-step-result bridge-frame prev 3 step)))))

(deftest dispatch-step-result-synthesizes-message-when-record-has-none
  (let [step [:dispatch [:rf.assert/path-equals [:k] 1]]
        prev (seed-assertions! [])
        _    (seed-assertions! [{:passed? false :assertion :rf.assert/path-equals
                                 :payload  [[:k] 1] :expected 1 :actual 0}])
        out  (dispatch-step-result bridge-frame prev 0 step)]
    (is (false? (:passed? out)))
    (is (re-find #":rf.assert/path-equals" (:message out)))
    (is (re-find #"expected 1" (:message out)))
    (is (re-find #"actual 0"   (:message out)))))

(deftest dispatch-step-result-skips-when-no-failure
  ;; a failure recorded BEFORE prev-count is not this dispatch's
  (let [step [:dispatch-sync [:some/event]]
        prev (seed-assertions! [{:passed? false :id :rf.assert/path-equals}])]
    (seed-assertions! [{:passed? true :id :rf.assert/path-equals}])
    (is (= {:idx 1 :step step :type :dispatch-sync :passed? nil}
           (dispatch-step-result bridge-frame prev 1 step)))))

;; ---- CLJS+JVM: terminal assertions auto-run -----------------------------

(deftest run-terminal-assertions-records-handler-backed-pass-and-fail
  ;; Handler-backed atoms record through the one in-script executor; a
  ;; tape-evaluated atom is never dispatched (the result boundary owns it).
  (rf/reg-event ::seed-db (fn [{:keys [db]} [_ m]] {:db (merge db m)}))
  (rf/dispatch-sync [::seed-db {:status :loaded}] {:frame bridge-frame})
  (rf.story.play.runner-events/run-terminal-assertions!
    bridge-frame
    [[:rf.assert/path-equals [:status] :loaded]
     [:rf.assert/path-equals [:status] :idle]
     [:rf.assert/schema-error {:where :event :event :some/evt}]])
  (let [recs (:rf.story/assertions (rf/app-db-value bridge-frame))]
    (is (= [true false]
           (mapv :passed? (filterv #(= :rf.assert/path-equals (:assertion %)) recs))))
    (is (empty? (filterv #(= :rf.assert/schema-error (:assertion %)) recs)))))

;; ---- CLJS+JVM: every run-loop! exit path settles done-cb -----------------
;;
;; Both abort branches — the run-state entry gone (frame torn down mid-run)
;; and a token mismatch (a newer run! took the slot) — must settle done-cb
;; without touching the slot, or the awaiting play-promise hangs forever.
;; Both are the loop's first cond clauses and return synchronously.

(def ^:private run-loop!    @#'rf.story.play.runner-events/run-loop!)
(def ^:private set-state!   @#'rf.story.play.runner-events/set-state!)
(def ^:private settle-abort! @#'rf.story.play.runner-events/settle-abort!)

(deftest run-loop-aborts-on-missing-state-still-settles-done-cb
  (let [settled (atom :unset)]
    (run-loop! :story.abort/torn-down nil "tok-A" (fn [final] (reset! settled final)))
    (is (nil? @settled) "done-cb fired with the last-known state, nil once the slot is gone")))

(deftest run-loop-aborts-on-token-mismatch-still-settles-done-cb
  (let [frame-id :story.abort/token-swap
        settled  (atom :unset)
        newer    (rf.story.play.runner/start
                   (rf.story.play.runner/initial-state {:script [[:dispatch [:noop]]] :name nil})
                   1)]
    (set-state! frame-id nil (assoc newer :run-token "tok-NEWER"))
    (run-loop! frame-id nil "tok-STALE" (fn [final] (reset! settled final)))
    (is (not= :unset @settled) "the stale loop's done-cb fired")
    (let [slot (rf.story.play.runner-events/current-state-for-play frame-id nil)]
      (is (= "tok-NEWER" (:run-token slot)) "the newer run still owns the slot")
      (is (= :running (:status slot)) "the stale abort did not finish the newer run"))))

(deftest settle-abort-tolerates-nil-done-cb
  (is (nil? (settle-abort! :story.abort/no-cb nil nil))))

;; ---- JVM-only: many :wait steps do not grow the call stack ---------------
;;
;; On the JVM the :wait branch sleeps and recurs in tail position; a wait
;; that nested a run-loop! call per step would overflow the stack.

#?(:clj
   (deftest jvm-many-wait-steps-do-not-grow-the-stack
     (let [frame-id :story.wait/deep
           n        10000
           started  (-> (rf.story.play.runner/start
                          (rf.story.play.runner/initial-state {:name nil :script (vec (repeat n [:wait 0]))})
                          0)
                        (assoc :run-token "tok-WAIT"))
           settled  (atom :unset)]
       (set-state! frame-id nil started)
       (run-loop! frame-id nil "tok-WAIT" (fn [final] (reset! settled final)))
       (is (= [n :pass] ((juxt :step-idx :status) @settled))))))

;; ---- JVM-only: integration tests against a live re-frame frame ----------

#?(:clj
   (defn- run-blocking
     "Drive `run!` and block until terminal status is reached or
     `timeout-ms` expires."
     ([variant-id] (run-blocking variant-id 5000))
     ([variant-id timeout-ms]
      (let [done (atom nil)]
        (rf.story.play.runner-events/run! variant-id (fn [state] (reset! done state)))
        (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
          (loop []
            (cond
              @done @done
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "run-blocking timeout"
                              {:variant-id variant-id
                               :state      @rf.story.play.runner-events/run-state}))
              :else (do (Thread/sleep 5) (recur)))))))))

;; ---- EP-0017: captured :rf.cofx replays into the handler -----------------
;;
;; A replayed step carrying `{:rf.cofx …}` re-presents the RECORDED provided
;; fact and `:rf/time-ms` to the handler; without the envelope the same
;; provided-fact handler must fail, so the capture is load-bearing.

#?(:clj
   (deftest dispatch-replays-captured-cofx-into-handler
     ;; The fixture clears the registrar, so register the framework :rf/time-ms
     ;; recordable cofx for this frame (idempotent).
     (when-not (registrar/lookup :cofx :rf/time-ms)
       (rf.cofx/reg-cofx :rf/time-ms {:recordable? true :provided? true}))
     ;; A provided fact has no generator: re-presenting the recorded value is
     ;; the only way replay succeeds.
     (rf/reg-cofx :rf2-l2cn5d.delta/v
       {:recordable? true :provided? true
        :doc "A provided recordable delta the recorder captured."})
     (let [seen (atom [])]
       (rf/reg-event :rf2-l2cn5d/inc-by
         {:doc "Increment by a replayable delta + stamp the replayed time."
          :rf.cofx/requires [:rf/time-ms :rf2-l2cn5d.delta/v]}
         (fn [{:keys [db] t :rf/time-ms delta :rf2-l2cn5d.delta/v} _]
           (swap! seen conj {:t t :delta delta})
           {:db (update db :n (fnil + 0) delta)}))
       (rf.story/reg-variant :story.runner/cofx-replay
         {:setup []
          :script {:auto-run? false
                   :script [[:dispatch      [:rf2-l2cn5d/inc-by]
                             {:rf.cofx {:rf/time-ms 1781078400123
                                        :rf2-l2cn5d.delta/v 4}}]
                            [:dispatch-sync [:rf2-l2cn5d/inc-by]
                             {:rf.cofx {:rf/time-ms 1781078400999
                                        :rf2-l2cn5d.delta/v 10}}]]}})
       (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/cofx-replay) 5000)
       (is (= :pass (:status (run-blocking :story.runner/cofx-replay))))
       (is (= [{:t 1781078400123 :delta 4}
               {:t 1781078400999 :delta 10}]
              @seen)
           "the handler saw the RECORDED :rf/time-ms and provided fact, not a fresh stamp"))))

#?(:clj
   (deftest dispatch-without-cofx-fails-missing-required-provided-fact
     (rf/reg-cofx :rf2-l2cn5d.token/v
       {:recordable? true :provided? true
        :doc "A provided recordable token with no generator."})
     (rf/reg-event :rf2-l2cn5d/needs-token
       {:rf.cofx/requires [:rf2-l2cn5d.token/v]}
       (fn [{:keys [db] tok :rf2-l2cn5d.token/v} _]
         {:db (assoc db :tok tok)}))
     (rf.story/reg-variant :story.runner/cofx-missing
       {:setup []
        :script {:auto-run? false
                 :script [[:dispatch-sync [:rf2-l2cn5d/needs-token]]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/cofx-missing) 5000)
     (is (not= :pass (:status (run-blocking :story.runner/cofx-missing))))))

;; ---- folded :assert-db outcomes ------------------------------------------
;;
;; The runtime consumes the folded plan: an :assert-db step runs as the
;; canonical [:assert [:rf.assert/path-equals …]] checkpoint, whose handler
;; records on :rf.story/assertions. An empty slot would make run-variant,
;; assertions-passing?, the test pane and the Xray panel read false-green.

#?(:clj
   (deftest assert-db-equals-pass-and-fail
     (rf/reg-event :rt/set-status
       (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
     (rf.story/reg-variant :story.runner/assert-db
       {:setup      []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/set-status :loaded]]
                             [:assert-db [:status] :loaded]
                             [:assert-db [:status] :wrong]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/assert-db) 5000)
     (let [final (run-blocking :story.runner/assert-db)
           pe    (filterv #(= :rf.assert/path-equals (:assertion %))
                          (rf.story/read-assertions :story.runner/assert-db))]
       (is (= [:fail 1] ((juxt :status :failures) final)))
       (is (= [true false] (mapv :passed? (filterv #(= :assert (:type %)) (:results final)))))
       (is (= [true false] (mapv :passed? pe)))
       (is (= [:wrong :loaded] ((juxt :expected :actual) (second pe)))))))

#?(:clj
   (deftest assert-db-pass-lands-in-assertions-slot
     (rf/reg-event :rt/set-status
       (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
     (rf.story/reg-variant :story.bridge/db-pass
       {:setup      []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/set-status :loaded]]
                             [:assert-db [:status] :loaded]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.bridge/db-pass) 5000)
     (run-blocking :story.bridge/db-pass)
     (let [slot (rf.story/read-assertions :story.bridge/db-pass)]
       (is (= [true] (mapv :passed? (filterv #(= :rf.assert/path-equals (:assertion %)) slot))))
       (is (true? (rf.story/assertions-passing? slot))))))

#?(:clj
   (deftest run-variant-result-reflects-rich-dsl-assert-failure
     (rf/reg-event :rt/set-status
       (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
     (rf.story/reg-variant :story.bridge/result
       {:setup      []
        :script {:script [[:dispatch-sync [:rt/set-status :idle]]
                          [:assert-db [:status] :loaded]]}})
     (let [result (rf.story.async/deref-blocking
                    (rf.story/run-variant :story.bridge/result) 5000)]
       (is (= :fail (:status result)))
       (is (some (fn [r] (and (= :rf.assert/path-equals (:assertion r))
                              (false? (:passed? r))))
                 (:assertions result)))
       (is (false? (rf.story/assertions-passing? result))))))

;; ---- EXACT narrative attribution on the live run path --------------------
;;
;; Each dispatch step's settle boundary feeds `project-evidence`, so a
;; re-dispatching step's fan-out stays under the step that produced it,
;; where an even forward partition would mis-group it.

#?(:clj
   (defn- beats-by-step [result]
     (->> (rf.story.play.evidence/narrative-beats (:narrative result))
          (reduce (fn [m {:keys [step trigger-event]}]
                    (update m step (fnil conj []) trigger-event))
                  {}))))

#?(:clj
   (deftest run-variant-narrative-exact-attribution-of-redispatch-fanout
     (rf/reg-event :rkd/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
     (rf/reg-event :rkd/c (fn [_ _] {:fx [[:dispatch [:rkd/d]]]}))
     (rf/reg-event :rkd/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
     (rf.story/reg-variant :story.rkd/redispatch
       {:setup  []
        :script {:script [[:dispatch-sync [:rkd/a]]
                          [:dispatch-sync [:rkd/c]]]}})
     (let [by (beats-by-step (rf.story.async/deref-blocking
                               (rf.story/run-variant :story.rkd/redispatch) 5000))]
       (is (= [[:rkd/a]] (get by [:dispatch-sync [:rkd/a]])))
       (is (= [[:rkd/c] [:rkd/d]] (get by [:dispatch-sync [:rkd/c]]))))))

#?(:clj
   (deftest run-variant-narrative-stamp-does-not-perturb-run-hash
     ;; the :rf.story/script-idx stamp is stripped by the determinism projection
     (rf/reg-event :rkd/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
     (rf/reg-event :rkd/c (fn [_ _] {:fx [[:dispatch [:rkd/d]]]}))
     (rf/reg-event :rkd/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
     (rf.story/reg-variant :story.rkd/hash
       {:setup  []
        :script {:script [[:dispatch-sync [:rkd/a]]
                          [:dispatch-sync [:rkd/c]]]}})
     (let [result (rf.story.async/deref-blocking
                    (rf.story/run-variant :story.rkd/hash) 5000)]
       (is (not (contains? (into #{} (mapcat keys) (:epoch-tape result)) :rf.story/script-idx))
           "the retained :epoch-tape slot is the raw tape")
       (is (= (rf.story.fingerprint/run-hash result)
              (rf.story.fingerprint/run-hash (dissoc result :narrative)))))))

;; Per-play settle boundaries accumulate across every auto-run play: if each
;; run! cleared them, later-play effects would be credited to earlier steps.

#?(:clj
   (deftest run-variant-multi-play-narrative-attributes-every-play-step
     (rf/reg-event :ml/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
     (rf/reg-event :ml/b (fn [{:keys [db]} _] {:db (assoc db :b true)}))
     (rf/reg-event :ml/c (fn [_ _] {:fx [[:dispatch [:ml/d]]]}))
     (rf/reg-event :ml/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
     (rf.story/reg-variant :story.ml/two-plays
       {:setup []
        :plays  [{:name "alpha" :auto-run? true
                  :script [[:dispatch-sync [:ml/a]]
                           [:dispatch-sync [:ml/b]]]}
                 {:name "beta" :auto-run? true
                  :script [[:dispatch-sync [:ml/c]]]}]})
     (let [by (beats-by-step (rf.story.async/deref-blocking
                               (rf.story/run-variant :story.ml/two-plays) 5000))]
       (is (= [[[:ml/a]] [[:ml/b]] [[:ml/c] [:ml/d]]]
              (mapv #(get by [:dispatch-sync %]) [[:ml/a] [:ml/b] [:ml/c]]))))))

;; ---- no-DOM steps refuse :cannot-run on the JVM ---------------------------

#?(:clj
   (deftest assert-dom-skipped-on-jvm-is-cannot-run
     (rf.story/reg-variant :story.bridge/dom-skip
       {:setup      []
        :script {:auto-run? false
                 :script    [[:assert-dom "div.foo" :visible]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.bridge/dom-skip) 5000)
     (is (= :cannot-run (:status (run-blocking :story.bridge/dom-skip))))
     (is (empty? (filterv #(true? (:passed? %)) (rf.story/read-assertions :story.bridge/dom-skip)))
         "a skipped :assert-dom contributes no passing record")))

#?(:clj
   (deftest assert-dom-skipped-unified-result-is-cannot-run
     ;; A no-DOM skip records nothing on the slot, so a result map that dropped
     ;; run-state's refusals would aggregate zero records to a vacuous :pass.
     (rf.story/reg-variant :story.bridge/dom-skip-unified
       {:setup      []
        :script {:script [[:assert-dom "div.foo" :visible]]}})
     (let [result (rf.story.async/deref-blocking
                    (rf.story/run-variant :story.bridge/dom-skip-unified) 5000)]
       (is (= :cannot-run (:status result)))
       (is (seq (:cannot-run result))))))

#?(:clj
   (deftest dom-step-skipped-on-jvm
     (rf.story/reg-variant :story.runner/dom
       {:setup []
        :script {:auto-run? false
                 :script    [[:click "button.foo"]
                             [:assert-dom "div" :visible]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/dom) 5000)
     (let [final (run-blocking :story.runner/dom)]
       (is (= :cannot-run (:status final)))
       (is (every? (fn [r] (or (:skipped? r) (true? (:passed? r)))) (:results final))))))

;; ---- :assert-db :pred ---------------------------------------------------

#?(:clj
   (deftest assert-db-pred-form
     ;; a symbol :pred folds to :rf.assert/path-matches and resolves at validation
     (rf/reg-event :rt/set-n
       (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
     (rf.story/reg-variant :story.runner/pred
       {:setup      []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/set-n 7]]
                             [:assert-db [:n] :pred 'clojure.core/pos?]
                             [:assert-db [:n] :pred 'clojure.core/neg?]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/pred) 5000)
     (is (= :fail (:status (run-blocking :story.runner/pred))))
     (is (= [true false]
            (mapv :passed? (filterv #(= :rf.assert/path-matches (:assertion %))
                                    (rf.story/read-assertions :story.runner/pred)))))))

#?(:clj
   (deftest assert-db-pred-fn-direct
     ;; a fn :pred is the advanced-CLJS-safe form
     (rf/reg-event :rt/set-n
       (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
     (rf.story/reg-variant :story.runner/pred-fn
       {:setup      []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/set-n 7]]
                             [:assert-db [:n] :pred pos?]
                             [:assert-db [:n] :pred neg?]
                             [:assert-db [:n] :pred (fn [x] (= x 7))]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/pred-fn) 5000)
     (run-blocking :story.runner/pred-fn)
     (is (= [true false true]
            (mapv :passed? (filterv #(= :rf.assert/path-matches (:assertion %))
                                    (rf.story/read-assertions :story.runner/pred-fn)))))))

#?(:clj
   (deftest assert-db-pred-bogus-symbol-fails-gracefully
     ;; an unresolvable symbol reports a readable failure, not an opaque error
     (rf/reg-event :rt/set-n
       (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
     (rf.story/reg-variant :story.runner/pred-bogus
       {:setup      []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/set-n 7]]
                             [:assert-db [:n] :pred 'no.such.ns/missing-pred]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/pred-bogus) 5000)
     (is (= :fail (:status (run-blocking :story.runner/pred-bogus))))
     (is (= [false]
            (mapv :passed? (filterv #(= :rf.assert/path-matches (:assertion %))
                                    (rf.story/read-assertions :story.runner/pred-bogus)))))))

;; ---- settle boundaries ---------------------------------------------------
;;
;; run! resets a frame's boundaries at the entry that writes them, so an
;; interactive re-run through the public run! does not accumulate stale
;; offsets. The bucket is keyed [frame-id play-key], so a concurrent run! for
;; another play cannot wipe an in-flight sequence's boundaries.

#?(:clj
   (deftest run-resets-step-boundaries-public-driver
     (rf/reg-event :vk/touch
       (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
     (rf.story/reg-variant :story.vkdam/rerun
       {:setup []
        :script {:auto-run? false
                 :script [[:dispatch-sync [:vk/touch]]
                          [:dispatch-sync [:vk/touch]]
                          [:dispatch-sync [:vk/touch]]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.vkdam/rerun) 5000)
     ;; run-blocking's 2-arity run! resolves the single :script play to key nil
     (run-blocking :story.vkdam/rerun)
     (is (= 3 (count (rf.story.play.runner-events/settle-boundaries :story.vkdam/rerun nil))))
     (run-blocking :story.vkdam/rerun)
     (is (= 3 (count (rf.story.play.runner-events/settle-boundaries :story.vkdam/rerun nil)))
         "the second run reset its boundaries")))

#?(:clj
   (deftest concurrent-run-for-different-play-key-does-not-wipe-boundaries
     (rf/reg-event :m0cge5/touch
       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (rf.story/reg-variant :story.m0cge5/two-key
       {:setup []
        :script {:auto-run? false :script []}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.m0cge5/two-key) 5000)
     (let [spec   (fn [n] {:name n :auto-run? false :script [[:dispatch-sync [:m0cge5/touch]]]})
           done-a (promise)
           done-b (promise)]
       ;; play A driven as the sequencer drives it, without clearing
       (rf.story.play.runner-events/run! :story.m0cge5/two-key "A" (spec "A") (fn [_] (deliver done-a :ok))
                                         {:clear-boundaries? false})
       (deref done-a 5000 :timeout)
       ;; play B with the default clear
       (rf.story.play.runner-events/run! :story.m0cge5/two-key "B" (spec "B") (fn [_] (deliver done-b :ok)))
       (deref done-b 5000 :timeout)
       (is (= [1 1] (mapv #(count (rf.story.play.runner-events/settle-boundaries :story.m0cge5/two-key %))
                          ["A" "B"]))))))

;; The boundaries record the framework's monotonic :epoch-id, so a small
;; epoch-history ring can only drop beats, never misattribute a survivor.

#?(:clj
   (deftest narrative-attribution-survives-epoch-ring-eviction
     (try
       (rf/configure! {:epoch-history {:depth 3}})
       (rf/reg-event :re-eviction/set
         (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
       (rf.story/reg-variant :story.runner/eviction
         {:setup []
          :script {:script (mapv (fn [n] [:dispatch-sync [:re-eviction/set n]]) [1 2 3 4 5])}})
       (let [narrative (:narrative (rf.story.async/deref-blocking
                                     (rf.story/run-variant :story.runner/eviction) 5000))
             span-for  (fn [n]
                         (some #(when (= [:dispatch-sync [:re-eviction/set n]] (:step %)) %)
                               narrative))]
         (is (= [[] [] [[:re-eviction/set 3]] [[:re-eviction/set 4]] [[:re-eviction/set 5]]]
                (mapv #(mapv :trigger-event (:epochs (span-for %))) [1 2 3 4 5]))))
       (finally
         ;; the depth is process-global
         (rf/configure! {:epoch-history {:depth 50}})))))

#?(:clj
   (deftest a-bare-unknown-event-step-fails-the-run
     ;; a bare event vector runs as one :dispatch; the router's refusal fails
     ;; the run rather than reading as a vacuous :pass
     (rf.story/reg-variant :story.runner/bare-unknown
       {:setup []
        :script {:auto-run? false
                 :script    [[:does-not-exist :nope]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/bare-unknown) 5000)
     (let [final (run-blocking :story.runner/bare-unknown)]
       (is (= :fail (:status final)))
       (is (= [[:rf.error/no-such-handler :does-not-exist]]
              (mapv (juxt :operation :failing-id)
                    (:rf.story/assertions
                      (rf/app-db-value :story.runner/bare-unknown)))))
       (is (= "no handler registered for :does-not-exist"
              (:message (first (:results final))))))))

;; ---- run-state lifecycle ----------------------------------------------

#?(:clj
   (deftest run-state-clears-and-resets
     ;; the second run fails on the un-reset app-db but re-walks from step 0
     (rf/reg-event :rt/touch
       (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
     (rf.story/reg-variant :story.runner/reset
       {:setup []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/touch]]
                             [:assert-db [:touches] 1]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/reset) 5000)
     (run-blocking :story.runner/reset)
     (run-blocking :story.runner/reset)
     (is (= [:fail 1 2]
            ((juxt :status :failures (comp count :results))
             (rf.story.play.runner-events/current-state :story.runner/reset))))))

#?(:clj
   (deftest each-step-emits-a-trace-event
     (let [trace-events (atom [])
           listener-id  ::play-trace-test]
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
       (require '[re-frame.trace.tooling :as rf.trace.tooling])
       (let [reg!  (resolve 're-frame.trace.tooling/register-listener!)
             unreg (resolve 're-frame.trace.tooling/unregister-listener!)]
         (try
           (reg! listener-id
                 (fn [ev]
                   (when (= :rf.story.play/step (:operation ev))
                     (swap! trace-events conj ev))))
           (rf.story/reg-variant :story.runner/trace
             {:setup []
              :script {:auto-run? false
                       :script    [[:dispatch-sync [:rt/touch]]
                                   [:assert-db [:touches] 1]]}})
           (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/trace) 5000)
           (run-blocking :story.runner/trace)
           (is (>= (count @trace-events) 2) "a trace per step")
           (is (= :story.runner/trace (get-in (first @trace-events) [:tags :frame])))
           (finally
             (unreg listener-id)))))))

;; ---- multi-play ----------------------------------------------------------

#?(:clj
   (defn- run-play-blocking
     "Drive a specific play via `run-play!` and block until terminal."
     ([variant-id play-key] (run-play-blocking variant-id play-key 5000))
     ([variant-id play-key timeout-ms]
      (let [done (atom nil)]
        (rf.story.play.runner-events/run-play! variant-id play-key (fn [s] (reset! done s)))
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
   (deftest run-play-keys-state-per-play
     (rf/reg-event :rt/inc
       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (rf.story/reg-variant :story.multi/keyed
       {:setup []
        :plays  [{:name "first"  :auto-run? false
                  :script [[:dispatch-sync [:rt/inc]]
                           [:assert-db [:n] 1]]}
                 {:name "second" :auto-run? false
                  :script [[:dispatch-sync [:rt/inc]]
                           [:assert-db [:n] 2]]}]})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.multi/keyed) 5000)
     (run-play-blocking :story.multi/keyed "first")
     (run-play-blocking :story.multi/keyed "second")
     (is (= [:pass :pass "second"]
            [(:status (rf.story.play.runner-events/current-state-for-play :story.multi/keyed "first"))
             (:status (rf.story.play.runner-events/current-state-for-play :story.multi/keyed "second"))
             (:play-key (rf.story.play.runner-events/current-state :story.multi/keyed))]))))

#?(:clj
   (deftest run-play-sets-active-play
     ;; run-play! and select-play! both set the key the toolbar reads; only
     ;; run-play! runs
     (rf/reg-event :rt/touch
       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (rf.story/reg-variant :story.multi/active
       {:setup []
        :plays  [{:name "alpha" :auto-run? false
                  :script [[:dispatch-sync [:rt/touch]]]}
                 {:name "beta"  :auto-run? false
                  :script [[:dispatch-sync [:rt/touch]]]}]})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.multi/active) 5000)
     (run-play-blocking :story.multi/active "beta")
     (is (= "beta" (rf.story.play.runner-events/active-play-key :story.multi/active)))
     (rf.story.play.runner-events/select-play! :story.multi/active "alpha")
     (is (= "alpha" (rf.story.play.runner-events/active-play-key :story.multi/active)))
     (is (nil? (rf.story.play.runner-events/current-state-for-play :story.multi/active "alpha")))))

#?(:clj
   (defn- await-done [done what]
     (let [deadline (+ (System/currentTimeMillis) 5000)]
       (loop []
         (cond
           (some? @done) @done
           (> (System/currentTimeMillis) deadline) (throw (ex-info (str what " timeout") {}))
           :else (do (Thread/sleep 5) (recur)))))))

#?(:clj
   (deftest run-all-plays-sequences-runs
     (rf/reg-event :rt/touch
       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (rf.story/reg-variant :story.multi/all
       {:setup []
        :plays  [{:name "a" :auto-run? false
                  :script [[:dispatch-sync [:rt/touch]]
                           [:assert-db [:n] 1]]}
                 {:name "b" :auto-run? false
                  :script [[:dispatch-sync [:rt/touch]]
                           [:assert-db [:n] 2]]}
                 {:name "c" :auto-run? false
                  :script [[:dispatch-sync [:rt/touch]]
                           [:assert-db [:n] 3]]}]})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.multi/all) 5000)
     (let [done (atom nil)]
       (rf.story.play.runner-events/run-all-plays! :story.multi/all (fn [final] (reset! done final)))
       (is (= [["a" :pass] ["b" :pass] ["c" :pass]]
              (mapv (juxt :play-key :status) (await-done done "run-all-plays")))))))

#?(:clj
   (deftest auto-run-multi-runs-only-opted-in-plays
     (rf/reg-event :rt/inc
       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (rf.story/reg-variant :story.multi/auto
       {:setup []
        :plays  [{:name "first-default-true"
                  :script [[:dispatch-sync [:rt/inc]]
                           [:assert-db [:n] 1]]}
                 {:name "second-default-false"
                  :script [[:dispatch-sync [:rt/inc]]
                           [:assert-db [:n] 99]]}
                 {:name "third-opted-in"
                  :auto-run? true
                  :script [[:dispatch-sync [:rt/inc]]]}]})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.multi/auto) 5000)
     (let [done (atom nil)]
       (rf.story.play.runner-events/auto-run! :story.multi/auto (fn [final] (reset! done final)))
       (is (= ["first-default-true" "third-opted-in"]
              (mapv :play-key (await-done done "auto-run multi")))))))

;; ---- concurrent-run race ------------------------------------------------
;;
;; The runtime and the shell's selection watcher can both fire run! against
;; one variant; every fresh run stamps a unique :run-token so a stale loop
;; bails instead of double-dispatching.

#?(:clj
   (deftest fresh-run-token-replaces-prior
     (rf/reg-event :rt/touch (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (rf.story/reg-variant :story.runner/token-rotate
       {:setup      []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/touch]]]}})
     (rf.story.async/deref-blocking (rf.story/run-variant :story.runner/token-rotate) 5000)
     (let [first-final (run-blocking :story.runner/token-rotate)]
       (run-blocking :story.runner/token-rotate)
       (is (not= (:run-token first-final)
                 (:run-token (rf.story.play.runner-events/current-state :story.runner/token-rotate)))))))

;; ---- tape-evaluated in-script [:assert …] checkpoints ---------------------
;;
;; Schema-error and the causal family carry no reg-event handler: the result
;; boundary mints their verdict against the epoch tape. Dispatched instead,
;; such an id would put a spurious :rf.error/no-such-handler on the tape and
;; could read as a false :fail.

#?(:clj
   (defn- run-capturing-no-handler-errors
     "Drive `variant-id` to terminal with a trace listener that collects any
     `:rf.error/no-such-handler` events targeting the variant's frame.
     Returns `[final-state no-handler-events]`."
     [variant-id]
     (require '[re-frame.trace.tooling :as rf.trace.tooling])
     (let [reg!      (resolve 're-frame.trace.tooling/register-listener!)
           unreg     (resolve 're-frame.trace.tooling/unregister-listener!)
           collected (atom [])
           lid       ::no-handler-capture]
       (reg! lid
             (fn [ev]
               (when (and (= :rf.error/no-such-handler (:operation ev))
                          (= variant-id (get-in ev [:tags :frame])))
                 (swap! collected conj ev))))
       (try
         (rf.story.async/deref-blocking (rf.story/run-variant variant-id) 5000)
         [(run-blocking variant-id) @collected]
         (finally (when unreg (unreg lid)))))))

#?(:clj
   (deftest in-script-schema-error-checkpoint-is-tape-evaluated-not-dispatched
     (rf/reg-event :rt/touch
       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
     (rf.story/reg-variant :story.tape/schema-error
       {:setup      []
        :script {:auto-run? false
                 :script    [[:dispatch-sync [:rt/touch]]
                             [:assert [:rf.assert/schema-error
                                       {:where :event :event :rt/touch}]]]}})
     (let [[final no-handler] (run-capturing-no-handler-errors :story.tape/schema-error)]
       (is (empty? no-handler))
       (is (= [:assert nil]
              ((juxt :type :passed?) (first (filter #(= :assert (:type %)) (:results final)))))
           "a no-op step-skip: the result boundary owns the verdict"))))

;; The browser-tier oracle family has its own inline executor, so it is NOT
;; tape-evaluated.

(def ^:private tape-evaluated-assertion? @#'rf.story.play.runner-events/tape-evaluated-assertion?)

(deftest tape-evaluated-assertion?-classifies-every-non-dispatched-family
  (testing "tape-evaluated"
    (is (tape-evaluated-assertion? [:rf.assert/schema-error {}]))
    (is (tape-evaluated-assertion? [:rf.assert/caused {:event :e}]))
    (is (tape-evaluated-assertion? [:rf.assert/no-cascade-rerender {:event :e}])))
  (testing "dispatched, or routed to their own executor"
    (is (not (tape-evaluated-assertion? [:rf.assert/visual-snapshot])))
    (is (not (tape-evaluated-assertion? [:rf.assert/path-equals [:k] 1])))))
