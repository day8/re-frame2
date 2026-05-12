(ns re-frame.story.ui.actions-cljs-test
  "Tests for the Actions panel (rf2-5yriz).

  Runs on both the JVM (cognitect.test-runner under
  `clojure -M:test`) and the CLJS node-test build (shadow's
  `:node-test` target; ns-regexp `cljs-test$` picks up this ns
  because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data**: `action-event?` classification, `project-rows`
    projection, `format-timestamp` formatting, `pretty-args` rendering,
    `row-class` derivation.  All runs on JVM AND CLJS.
  - **CLJS-only side-effects**: pause / unpause / toggle / clear
    against the per-variant pause ratoms; `current-events`'s
    snapshot vs live-buffer behaviour; the panel component returns
    hiccup.

  The JVM-side test corpus exercises only the pure surface (`#?(:clj
  ...)` short-circuits the CLJS pieces).  The CLJS node-test
  exercises the full surface."
  (:require [clojure.test :refer [deftest is testing]]
            #?@(:cljs [[re-frame.story.ui.trace :as trace]])
            [re-frame.story.ui.actions :as actions]))

;; ---- fixtures ------------------------------------------------------------

(defn dispatch-event
  "Build a `:event/dispatched` trace event with sensible defaults."
  ([id event] (dispatch-event id event {}))
  ([id event extra-tags]
   {:op-type   :event
    :operation :event/dispatched
    :id        id
    :time      (+ 1700000000000 (* id 10))
    :tags      (merge {:dispatch-id (+ 1000 id)
                       :event-id    (first event)
                       :event       event}
                      extra-tags)}))

(defn fx-dispatch-event
  "Build a `:rf.fx/handled` trace event for a dispatch-shaped fx-id."
  ([id fx-id event] (fx-dispatch-event id fx-id event {}))
  ([id fx-id event extra-tags]
   {:op-type   :fx
    :operation :rf.fx/handled
    :id        id
    :time      (+ 1700000000000 (* id 10))
    :tags      (merge {:dispatch-id (+ 2000 id)
                       :fx-id       fx-id
                       :fx-args     event}
                      extra-tags)}))

(defn unrelated-event
  "Build a non-action trace event (something that should be filtered
  out — a sub-run, a render, a do-fx, etc.)."
  [id op-type operation]
  {:op-type   op-type
   :operation operation
   :id        id
   :time      (+ 1700000000000 (* id 10))
   :tags      {:dispatch-id (+ 1000 id)}})

;; ---- pure: action-event? classification ----------------------------------

(deftest action-event?-classifies-dispatches
  (testing "every :event/dispatched is an action event"
    (is (actions/action-event?
          (dispatch-event 1 [:counter/inc])))
    (is (actions/action-event?
          (dispatch-event 2 [:user/login {:id 7}])))
    ;; Even descendant dispatches inside a cascade qualify.
    (is (actions/action-event?
          (dispatch-event 3 [:counter/dec] {:parent-dispatch-id 999})))))

(deftest action-event?-classifies-dispatch-fxs
  (testing ":rf.fx/handled with dispatch-shaped fx-id qualifies"
    (is (actions/action-event?
          (fx-dispatch-event 10 :dispatch       [:counter/inc])))
    (is (actions/action-event?
          (fx-dispatch-event 11 :dispatch-later [:counter/inc] {:fx-args {:event [:counter/inc] :ms 100}})))
    (is (actions/action-event?
          (fx-dispatch-event 12 :dispatch-sync  [:counter/inc])))))

(deftest action-event?-rejects-non-dispatch-fxs
  (testing ":rf.fx/handled with a non-dispatch fx-id is NOT an action"
    (is (not (actions/action-event?
               (fx-dispatch-event 20 :db          [:counter/inc]))))
    (is (not (actions/action-event?
               (fx-dispatch-event 21 :rf.fx/reg-flow {:id :flow.x/y}))))
    (is (not (actions/action-event?
               (fx-dispatch-event 22 :http        {:url "/api"}))))))

(deftest action-event?-rejects-cascade-internals
  (testing "handler / do-fx / sub / render / error / warning trace events all reject"
    (is (not (actions/action-event? (unrelated-event 30 :event :event))))       ;; handler run
    (is (not (actions/action-event? (unrelated-event 31 :event :event/do-fx)))) ;; effects map
    (is (not (actions/action-event? (unrelated-event 32 :sub/run :sub/run))))
    (is (not (actions/action-event? (unrelated-event 33 :sub/create :sub/create))))
    (is (not (actions/action-event? (unrelated-event 34 :view :view/render))))
    (is (not (actions/action-event? (unrelated-event 35 :error :rf.error/handler-exception))))
    (is (not (actions/action-event? (unrelated-event 36 :warning :rf.fx/skipped-on-platform))))))

(deftest action-event?-rejects-empty-and-malformed
  (testing "empty / partial trace events do not classify"
    (is (not (actions/action-event? {})))
    (is (not (actions/action-event? {:op-type :event})))
    (is (not (actions/action-event? {:op-type :event :operation :event})))))

;; ---- pure: row-class -----------------------------------------------------

(deftest row-class-derivation
  (testing "row-class returns :dispatch / :fx-dispatch / nil"
    (is (= :dispatch    (actions/row-class (dispatch-event 1 [:foo]))))
    (is (= :fx-dispatch (actions/row-class (fx-dispatch-event 2 :dispatch [:bar]))))
    (is (nil?           (actions/row-class (unrelated-event 3 :sub/run :sub/run))))))

;; ---- pure: project-rows --------------------------------------------------

(deftest project-rows-filters-and-projects
  (testing "a buffer with mixed events returns only action rows, projected
            into the canonical shape, in input order"
    (let [buf  [(dispatch-event       1 [:counter/inc])
                (unrelated-event      2 :event :event)                ;; filtered
                (unrelated-event      3 :sub/run :sub/run)            ;; filtered
                (fx-dispatch-event    4 :dispatch [:counter/dec])
                (unrelated-event      5 :view :view/render)           ;; filtered
                (dispatch-event       6 [:user/login {:user-id 7}])
                (fx-dispatch-event    7 :dispatch-later
                                        [:counter/inc]
                                        {:fx-args {:event [:counter/inc] :ms 500}})]
          rows (actions/project-rows buf)]
      (is (= 4 (count rows)))
      ;; Chronological order (input order) preserved.
      (is (= [1 4 6 7] (map :id rows)))
      ;; Classes alternate as expected.
      (is (= [:dispatch :fx-dispatch :dispatch :fx-dispatch]
             (map :class rows)))
      ;; First row: dispatch of [:counter/inc].
      (let [r (nth rows 0)]
        (is (= :counter/inc (:event-id r)))
        (is (= [:counter/inc] (:event r)))
        (is (= 1001 (:dispatch-id r)))
        (is (number? (:time r))))
      ;; Second row: fx-dispatch of [:counter/dec] via :dispatch fx.
      (let [r (nth rows 1)]
        (is (= :counter/dec (:event-id r)))
        (is (= [:counter/dec] (:event r)))
        (is (= :dispatch (:fx-id r)))
        (is (= 2004 (:dispatch-id r))))
      ;; Third row: dispatch with extra arg.
      (let [r (nth rows 2)]
        (is (= :user/login (:event-id r))))
      ;; Fourth row: :dispatch-later carries fx-args as a map; event-id
      ;; should still surface from the inner :event.
      (let [r (nth rows 3)]
        (is (= :dispatch-later (:fx-id r)))
        (is (= :counter/inc (:event-id r)))
        (is (= [:counter/inc] (:event r)))))))

(deftest project-rows-on-empty
  (testing "empty buffer yields empty vector"
    (is (= [] (actions/project-rows [])))
    (is (= [] (actions/project-rows nil)))))

(deftest project-rows-source-coord-hook
  (testing "row carries trigger-handler source-coord under :source when present"
    (let [ev   (-> (dispatch-event 1 [:counter/inc])
                   (assoc :rf.trace/trigger-handler
                          {:kind :event :id :counter/inc
                           :source-coord {:ns "counter.events"
                                          :file "counter/events.cljs"
                                          :line 42}}))
          rows (actions/project-rows [ev])]
      (is (= 1 (count rows)))
      (is (= 42 (-> rows first :source :line))))))

;; ---- pure: format-timestamp ---------------------------------------------

(deftest format-timestamp-renders-hh-mm-ss-mmm
  (testing "non-empty for valid ms"
    (let [out (actions/format-timestamp 1700000000000)]
      (is (string? out))
      ;; Output looks like "HH:MM:SS.mmm" — 12 characters, with two
      ;; colons and one dot.
      (is (= 12 (count out)))
      (is (= 2 (count (filter #(= % \:) out))))
      (is (= 1 (count (filter #(= % \.) out))))))
  (testing "pads sub-second millis to three digits"
    ;; ms ending in 7 → ".007"
    (let [out (actions/format-timestamp 1700000000007)]
      (is (re-find #"\.007$" out)))
    ;; ms ending in 42 → ".042"
    (let [out (actions/format-timestamp 1700000000042)]
      (is (re-find #"\.042$" out)))
    ;; ms ending in 700 → ".700"
    (let [out (actions/format-timestamp 1700000000700)]
      (is (re-find #"\.700$" out)))))

(deftest format-timestamp-handles-nil-and-bad-inputs
  (testing "nil / non-number / negative → empty string"
    (is (= "" (actions/format-timestamp nil)))
    (is (= "" (actions/format-timestamp "abc")))
    (is (= "" (actions/format-timestamp -1)))))

;; ---- pure: pretty-args ---------------------------------------------------

(deftest pretty-args-renders-via-pr-str
  (testing "pretty-args round-trips non-empty collections through pr-str"
    (is (= "" (actions/pretty-args nil)))
    (is (= "" (actions/pretty-args [])))
    (is (= (pr-str [1 2 3]) (actions/pretty-args [1 2 3])))
    (is (= (pr-str [{:user-id 7}]) (actions/pretty-args [{:user-id 7}])))))

;; ---- CLJS-only: pause / unpause / toggle / clear -------------------------

#?(:cljs
   (do
     (deftest pause-unpause-roundtrip
       (testing "pause / unpause flip per-variant state and capture snapshot"
         (let [vid :story.actions-test/v1]
           ;; Clean slate.
           (actions/unpause! vid)
           (is (false? (actions/paused? vid)))
           ;; Seed the trace buffer for vid so pause has a snapshot.
           (let [a (trace/ensure-buffer! vid)]
             (reset! a [(dispatch-event 1 [:foo])
                        (dispatch-event 2 [:bar])]))
           (actions/pause! vid)
           (is (true? (actions/paused? vid)))
           ;; Snapshot must equal the buffer at pause time.
           (let [snap (actions/current-events vid)]
             (is (= 2 (count snap))))
           ;; Continue mutating the buffer; the snapshot should not move.
           (let [a (trace/ensure-buffer! vid)]
             (swap! a conj (dispatch-event 3 [:baz])))
           (let [snap (actions/current-events vid)]
             (is (= 2 (count snap)))
             (is (every? #(not= :baz (first (:event %)))
                         (map :tags snap))))
           ;; Unpause — current-events now reflects the live buffer.
           (actions/unpause! vid)
           (is (false? (actions/paused? vid)))
           (let [live (actions/current-events vid)]
             (is (= 3 (count live))))
           ;; Cleanup.
           (trace/drop-buffer! vid))))

     (deftest toggle-pause-flips
       (testing "toggle-pause! cycles paused state"
         (let [vid :story.actions-test/v-toggle]
           (actions/unpause! vid)
           (is (false? (actions/paused? vid)))
           (actions/toggle-pause! vid)
           (is (true? (actions/paused? vid)))
           (actions/toggle-pause! vid)
           (is (false? (actions/paused? vid)))
           (trace/drop-buffer! vid))))

     (deftest clear-empties-buffer-and-unpauses
       (testing "clear! drops the variant's trace buffer + leaves panel unpaused"
         (let [vid :story.actions-test/v-clear]
           ;; Seed + pause.
           (let [a (trace/ensure-buffer! vid)]
             (reset! a [(dispatch-event 1 [:a]) (dispatch-event 2 [:b])]))
           (actions/pause! vid)
           (is (= 2 (count (actions/current-events vid))))
           (is (true? (actions/paused? vid)))
           ;; Clear.
           (actions/clear! vid)
           (is (= 0 (count (actions/current-events vid))))
           (is (false? (actions/paused? vid)))
           (trace/drop-buffer! vid))))

     ;; ---- panel render smoke -----------------------------------------

     (deftest panel-is-a-function
       (testing "actions/panel exposes a top-level component fn"
         (is (fn? actions/panel))))

     (deftest panel-returns-hiccup-when-invoked
       (testing "panel returns a form-2 inner fn that produces hiccup"
         (let [vid :story.actions-test/v-render
               ;; Seed two action events so the panel renders rows.
               _   (let [a (trace/ensure-buffer! vid)]
                     (reset! a [(dispatch-event 1 [:foo])
                                (fx-dispatch-event 2 :dispatch [:bar])
                                ;; A filtered event mixed in.
                                (unrelated-event 3 :sub/run :sub/run)]))
               inner (actions/panel vid)
               ;; Form-2: the outer fn returns the inner render fn.
               tree  (inner vid)]
           (is (vector? tree))
           (is (= :div (first tree)))
           ;; The root carries the data-test attr per the DOM contract.
           (is (= "story-actions-panel"
                  (-> tree second :data-test)))
           ;; Project-rows would surface two rows; spot-check the tree
           ;; contains both event-ids somewhere in its flattened seq.
           (let [flat (tree-seq coll? seq tree)]
             (is (some #(= % "story-actions-pause") flat))
             (is (some #(= % "story-actions-clear") flat)))
           ;; Cleanup.
           (actions/unpause! vid)
           (trace/drop-buffer! vid))))

     (deftest panel-shows-empty-state-with-no-events
       (testing "with an empty buffer the panel renders the empty hint"
         (let [vid :story.actions-test/v-empty
               _   (let [a (trace/ensure-buffer! vid)] (reset! a []))
               inner (actions/panel vid)
               tree  (inner vid)
               flat  (tree-seq coll? seq tree)
               text  (filter string? flat)]
           (is (some #(re-find #"no actions yet" %) text))
           (trace/drop-buffer! vid))))))
