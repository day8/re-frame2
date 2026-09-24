(ns re-frame.story.recorder-timer-child-cljs-test
  "rf2-tbik1 — a recorded `:dispatch-later` child runs ONCE when the
  recording is replayed in the browser runtime.

  rf2-3x7nj.30.2 stopped the recorder capturing a child its root's handler
  dispatched, keyed on the `:rf.trace/parent-dispatch-id` tag, because
  replaying the root re-dispatches the child. A `:dispatch-later` child is
  re-armed by the replayed root in exactly the same way, but in CLJS its
  timer fires from a host callback outside any handler scope, so it carries
  no parent id. On the JVM `set-timeout!` wraps the callback in `bound-fn`,
  so there the child does carry one and is already skipped.

  So this is CLJS-only by construction: the JVM lane cannot reach the
  missing tag. It drives the REAL recorder (the trace listener over a live
  variant frame), lets the timer fire while recording, exports the
  recording the way the save dialog does (auto-assert on, against the
  recording's seed db), replays it as a registered variant, and counts the
  child handler's runs.

  rf2-mcjdg adds an unrelated dispatch between the root and the child,
  under the export's 50ms wait threshold. Its gap folds out of the script,
  but on replay it runs straight after the root that re-armed the timer, so
  the child's wait must still cover the whole delay from there, not only
  the time since that dispatch.

  The measured gap between the root and the child can land a millisecond
  under the delay, so the recorded marker carries the delay itself and the
  export never waits less than it.

  Named `-cljs-test` (not `-dom-cljs-test`), so the `:node-test` build
  selects it; nothing here needs a DOM."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private source-id :story.tbik1/source)
(def ^:private replay-id :story.tbik1/replay)

(def ^:private later-ms
  "The child's `:dispatch-later` delay. Above the export's 50ms wait
  threshold, so the recording's gap is a real `[:wait …]` step."
  80)

(def ^:private settle-ms
  "How long to let host timers run before reading a count: well past
  `later-ms`, so a re-armed timer that is going to fire has fired."
  (* 4 later-ms))

(def ^:private other-gap-ms
  "rf2-mcjdg: when the unrelated dispatch lands after the root. Under the
  50ms threshold, so the export folds its gap out, and far enough before
  the child that a wait measured from it falls short of `later-ms`."
  30)

(def ^:private child-runs (atom 0))

(defn- reset-all! []
  (rf.story.recorder/remove-trace-listener!)
  (rf.story.recorder/clear!)
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  ;; The registrar clear drops the framework `:rf/machine` runtime sub the
  ;; lifecycle machine reads; put it back, as the sibling run suites do.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (reset! child-runs 0)
  (rf/reg-event :tbik1/root
    (fn [{:keys [db]} _]
      {:db (update db :roots (fnil inc 0))
       :fx [[:dispatch-later {:ms later-ms :event [:tbik1/child]}]]}))
  (rf/reg-event :tbik1/child
    (fn [{:keys [db]} _]
      (swap! child-runs inc)
      {:db (update db :children (fnil inc 0))}))
  (rf/reg-event :tbik1/other
    (fn [{:keys [db]} _]
      {:db (update db :others (fnil inc 0))})))

(use-fixtures :each
  {:before reset-all!
   :after  (fn []
             (rf.story.recorder/remove-trace-listener!)
             (rf.story.recorder/clear!))})

(defn- after-ms [ms f] (js/setTimeout f ms))

(deftest recorded-dispatch-later-child-runs-once-on-replay
  (testing "rf2-tbik1: replaying a root re-arms its :dispatch-later timer, so
            the recording must not also carry the timer's child as a step of
            its own — and it must still wait for that timer, or the export's
            auto-assert, which runs straight after the root, reads the db
            before the child has fired"
    (async done
      (rf.story/reg-variant source-id {})
      (-> (rf.story/run-variant source-id)
          (rf.story.async/then
            (fn [_]
              (rf.story.recorder/install-trace-listener!)
              (rf.story.recorder/start-recording! source-id)
              (rf/dispatch-sync [:tbik1/root] {:frame source-id})
              (after-ms settle-ms
                (fn []
                  (rf.story.recorder/stop-recording!)
                  (let [entries  (rf.story.recorder/recorded-entries)
                        final-db (rf/app-db-value source-id)
                        seed-db  (:seed-db (rf.story.recorder/current-state))
                        body     (rf.story.recorder.play-export/recording->script-body
                                   entries
                                   {:auto-assert? true
                                    :seed-db      seed-db
                                    :final-db     final-db})]
                    (is (= {:roots 1 :children 1}
                           (select-keys final-db [:roots :children]))
                        "control: the recorded session ran the timer child once")
                    (is (= 1 @child-runs)
                        "control: one child run while recording")
                    (is (= [{:kind :event/timer-child :ms later-ms}]
                           (->> entries
                                (filter #(= :event/timer-child (:kind %)))
                                (mapv #(select-keys % [:kind :ms]))))
                        "the recorder marks the fired child with its scheduled delay")
                    (is (some #(and (= :wait (first %)) (>= (second %) later-ms))
                              (:script body))
                        (str "the script waits the timer's whole delay; script "
                             (pr-str (:script body))))
                    (is (some #(= [:assert-db [:children] 1] %) (:script body))
                        "control: the export auto-asserts the child's effect")
                    (reset! child-runs 0)
                    (rf.story/reg-variant replay-id {:extends source-id
                                                     :script  body})
                    (-> (rf.story/run replay-id)
                        (rf.story.async/then
                          (fn [result]
                            (after-ms settle-ms
                              (fn []
                                (is (= 1 @child-runs)
                                    (str "the replay ran the :dispatch-later child "
                                         @child-runs " time(s); script "
                                         (pr-str (:script body))))
                                (is (= :pass (:status result))
                                    (str "the replay's auto-assert passes; script "
                                         (pr-str (:script body))))
                                (rf.story/destroy-variant! replay-id)
                                (rf.story/destroy-variant! source-id)
                                (done)))))))))))))))

(defn- recorded-t
  "The recorded `:t` of the dispatch entry for `event-id`, or nil."
  [entries event-id]
  (some #(when (= event-id (first (:event %))) (:t %)) entries))

(deftest a-short-gap-event-does-not-shorten-the-timer-wait
  (testing "rf2-mcjdg: an unrelated dispatch between the root and its
            :dispatch-later child, under the wait threshold, folds out of the
            script — and replayed it runs straight after the re-armed root, so
            a wait measured from it reaches the auto-assert before the timer"
    (async done
      (rf.story/reg-variant source-id {})
      (-> (rf.story/run-variant source-id)
          (rf.story.async/then
            (fn [_]
              (rf.story.recorder/install-trace-listener!)
              (rf.story.recorder/start-recording! source-id)
              (rf/dispatch-sync [:tbik1/root] {:frame source-id})
              (after-ms other-gap-ms
                #(rf/dispatch-sync [:tbik1/other] {:frame source-id}))
              (after-ms settle-ms
                (fn []
                  (rf.story.recorder/stop-recording!)
                  (let [entries  (rf.story.recorder/recorded-entries)
                        final-db (rf/app-db-value source-id)
                        seed-db  (:seed-db (rf.story.recorder/current-state))
                        body     (rf.story.recorder.play-export/recording->script-body
                                   entries
                                   {:auto-assert? true
                                    :seed-db      seed-db
                                    :final-db     final-db})
                        script   (:script body)
                        gap      (- (recorded-t entries :tbik1/other)
                                    (recorded-t entries :tbik1/root))]
                    (is (< gap rf.story.recorder.play-export/default-wait-threshold-ms)
                        (str "control: the intervening dispatch's " gap
                             "ms gap is under the threshold, so it folds out"))
                    ;; A recorded dispatch step carries its `{:rf.cofx …}` as a
                    ;; third element, so compare each step's head.
                    (is (= [[:dispatch [:tbik1/root]] [:dispatch [:tbik1/other]]]
                           (mapv #(subvec % 0 2) (take 2 script)))
                        "control: no wait between the root and the short-gap event")
                    (is (= {:roots 1 :others 1 :children 1}
                           (select-keys final-db [:roots :others :children]))
                        "control: the recorded session ran the timer child once")
                    (is (some #(and (= :wait (first %)) (>= (second %) later-ms)) script)
                        (str "the script waits the timer's whole delay; script "
                             (pr-str script)))
                    (reset! child-runs 0)
                    (rf.story/reg-variant replay-id {:extends source-id
                                                     :script  body})
                    (-> (rf.story/run replay-id)
                        (rf.story.async/then
                          (fn [result]
                            (after-ms settle-ms
                              (fn []
                                (is (= 1 @child-runs)
                                    (str "the replay ran the :dispatch-later child "
                                         @child-runs " time(s); script "
                                         (pr-str script)))
                                (is (= :pass (:status result))
                                    (str "the replay's auto-assert passes; script "
                                         (pr-str script)))
                                (rf.story/destroy-variant! replay-id)
                                (rf.story/destroy-variant! source-id)
                                (done)))))))))))))))
