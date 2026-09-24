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
      {:db (update db :children (fnil inc 0))})))

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
                  (let [final-db (rf/app-db-value source-id)
                        seed-db  (:seed-db (rf.story.recorder/current-state))
                        body     (rf.story.recorder.play-export/recording->script-body
                                   (rf.story.recorder/recorded-entries)
                                   {:auto-assert? true
                                    :seed-db      seed-db
                                    :final-db     final-db})]
                    (is (= {:roots 1 :children 1}
                           (select-keys final-db [:roots :children]))
                        "control: the recorded session ran the timer child once")
                    (is (= 1 @child-runs)
                        "control: one child run while recording")
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
