(ns day8.re-frame2-causa.panels.machine-inspector-helpers-cljs-test
  "Pure-data tests for Causa's Machine Inspector panel helpers
  (Phase 5+, rf2-r9f9u).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `causality_graph_cljs_test.cljc`,
  `subscriptions_helpers_cljs_test.cljc`, etc.:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **transition-event?**     — recognises the v1 transition
                                   operations (outer + microstep).
    2. **machine-id-of**         — pulls the machine-id off the trace
                                   event's `:tags`.
    3. **project-machine-rows**  — folds the registered ids + snapshot
                                   map into the row shape; sorts
                                   deterministically.
    4. **pick-selected**         — defaults to the first row when the
                                   selection is nil or unknown.
    5. **chart-props**           — builds the prop map per
                                   `tools/machines-viz/spec/API.md`.
    6. **project-transitions**   — filters the trace buffer to the
                                   selected machine; newest first.
    7. **cap-transitions**       — applies the v1 200-entry cap.
    8. **project-data**          — the top-level composite shape.
    9. **format-* helpers**      — display formatters."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.machine-inspector-helpers :as h]))

;; ---- (1) transition-event? ---------------------------------------------

(deftest transition-event-recognises-outer-transition
  (is (true?  (h/transition-event? {:operation :rf.machine/transition})))
  (is (true?  (h/transition-event?
                {:operation :rf.machine.microstep/transition})))
  (is (false? (h/transition-event? {:operation :event/dispatched})))
  (is (false? (h/transition-event? nil)))
  (is (false? (h/transition-event? {}))))

;; ---- (2) machine-id-of -------------------------------------------------

(deftest machine-id-of-reads-tags
  (testing "the :tags :machine-id slot wins when present"
    (is (= :auth/login
           (h/machine-id-of {:tags {:machine-id :auth/login}}))))
  (testing "falls back to :handler-id (same value for machines)"
    (is (= :auth/login
           (h/machine-id-of {:tags {:handler-id :auth/login}}))))
  (testing "nil when neither slot is present"
    (is (nil? (h/machine-id-of {:tags {}})))
    (is (nil? (h/machine-id-of {})))))

;; ---- (3) project-machine-rows ------------------------------------------

(deftest project-machine-rows-empty-when-no-machines
  (is (= [] (h/project-machine-rows nil nil)))
  (is (= [] (h/project-machine-rows [] {}))))

(deftest project-machine-rows-one-per-id
  (let [rows (h/project-machine-rows [:auth/login :checkout/flow]
                                     {:auth/login {:state :idle :data {}}})]
    (is (= 2 (count rows)))
    (is (= #{:auth/login :checkout/flow}
           (set (map :machine-id rows))))))

(deftest project-machine-rows-fills-state-from-snapshot
  (let [rows (h/project-machine-rows [:auth/login]
                                     {:auth/login {:state :authing
                                                   :data {:user "ada"}}})
        row  (first rows)]
    (is (= :authing (:state row)))
    (is (= {:user "ada"} (:data row)))
    (is (true? (:registered? row)))))

(deftest project-machine-rows-tolerates-missing-snapshot
  (let [rows (h/project-machine-rows [:auth/login] {})
        row  (first rows)]
    (is (= :auth/login (:machine-id row)))
    (is (nil? (:state row)))
    (is (nil? (:data row)))
    (is (true? (:registered? row))
        "registered? stays true even when uninitialised")))

(deftest project-machine-rows-sorts-deterministically
  (let [rows (h/project-machine-rows [:z/last :a/first :m/middle] {})
        ids  (map :machine-id rows)]
    (is (= [:a/first :m/middle :z/last] ids))))

(deftest project-machine-rows-3-arity-fills-definition
  (testing "the 3-arity overload propagates the machine definition into
            the row so the chart primitive can lay it out"
    (let [defs {:auth/login {:initial :idle
                             :states  {:idle    {:on {:start :authing}}
                                       :authing {:on {:ok :done}}
                                       :done    {:final? true}}}}
          rows (h/project-machine-rows [:auth/login] {} defs)
          row  (first rows)]
      (is (= :auth/login (:machine-id row)))
      (is (= (:auth/login defs) (:definition row))))))

;; ---- (4) pick-selected -------------------------------------------------

(deftest pick-selected-returns-matching-row
  (let [rows [{:machine-id :a} {:machine-id :b} {:machine-id :c}]]
    (is (= :b (:machine-id (h/pick-selected rows :b))))))

(deftest pick-selected-falls-back-to-first-when-nil
  (let [rows [{:machine-id :a} {:machine-id :b}]]
    (is (= :a (:machine-id (h/pick-selected rows nil))))))

(deftest pick-selected-falls-back-to-first-when-unknown
  (let [rows [{:machine-id :a} {:machine-id :b}]]
    (is (= :a (:machine-id (h/pick-selected rows :unknown))))))

(deftest pick-selected-returns-nil-on-empty
  (is (nil? (h/pick-selected [] :anything)))
  (is (nil? (h/pick-selected nil nil))))

;; ---- (5) chart-props ---------------------------------------------------

(deftest chart-props-nil-when-no-row
  (is (nil? (h/chart-props nil :rf/default))))

(deftest chart-props-fills-required-keys
  (let [props (h/chart-props {:machine-id :auth/login :state nil :data nil}
                             :rf/default)]
    (is (= :auth/login (:machine-id props)))
    (is (= :rf/default (:frame-id props)))
    (is (nil? (:current-state-override props))
        "no override when the snapshot has no :state")))

(deftest chart-props-includes-current-state-override-when-state-present
  (let [props (h/chart-props {:machine-id :auth/login
                              :state      :authing
                              :data       {:user "ada"}}
                             :rf/default)]
    (is (= {:state :authing :data {:user "ada"}}
           (:current-state-override props)))))

(deftest chart-props-omits-data-from-override-when-nil
  (let [props (h/chart-props {:machine-id :auth/login
                              :state      :idle
                              :data       nil}
                             :rf/default)]
    (is (= {:state :idle}
           (:current-state-override props))
        "data slot is omitted rather than nil")))

(deftest chart-props-carries-definition-when-present
  (testing "the chart-props payload threads the machine definition so
            the chart primitive can lay it out without a second sub"
    (let [def-map {:initial :idle
                   :states  {:idle    {:on {:start :ready}}
                             :ready   {:final? true}}}
          props   (h/chart-props {:machine-id :auth/login
                                  :state      :ready
                                  :data       nil
                                  :definition def-map}
                                 :rf/default)]
      (is (= def-map (:definition props))
          "definition is passed through to the chart layer"))))

(deftest chart-props-omits-definition-when-nil
  (let [props (h/chart-props {:machine-id :auth/login :state :idle :data nil}
                             :rf/default)]
    (is (not (contains? props :definition))
        "no :definition key when the row carries no definition")))

;; ---- (6) project-transitions -------------------------------------------

(deftest project-transitions-empty-when-machine-id-nil
  (is (= [] (h/project-transitions [{:operation :rf.machine/transition
                                     :tags {:machine-id :auth/login}}]
                                   nil))))

(deftest project-transitions-empty-when-buffer-empty
  (is (= [] (h/project-transitions [] :auth/login)))
  (is (= [] (h/project-transitions nil :auth/login))))

(deftest project-transitions-filters-by-machine-id
  (let [buffer [{:id 1 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :idle :to :authing}}
                {:id 2 :operation :rf.machine/transition
                 :tags {:machine-id :checkout/flow :from :idle :to :cart}}
                {:id 3 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :authing :to :idle}}]
        rows   (h/project-transitions buffer :auth/login)
        ids    (set (map :id rows))]
    (is (= 2 (count rows)) "only the focused machine's transitions surface")
    (is (= #{1 3} ids)
        "events #1 and #3 (both :auth/login) survive; #2 (:checkout/flow) is dropped")))

(deftest project-transitions-newest-first
  (let [buffer [{:id 10 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :idle :to :a}}
                {:id 30 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :a :to :b}}
                {:id 20 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :b :to :c}}]
        rows   (h/project-transitions buffer :auth/login)]
    (is (= [30 20 10] (map :id rows))
        "highest :id first (newest first)")))

(deftest project-transitions-marks-microsteps
  (let [buffer [{:id 1 :operation :rf.machine.microstep/transition
                 :tags {:machine-id :auth/login :from :idle :to :a}}
                {:id 2 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :a :to :b}}]
        rows   (h/project-transitions buffer :auth/login)
        outer  (first (filter #(= 2 (:id %)) rows))
        micro  (first (filter #(= 1 (:id %)) rows))]
    (is (false? (:microstep? outer)))
    (is (true?  (:microstep? micro)))))

(deftest project-transitions-drops-non-transition-events
  (let [buffer [{:id 1 :operation :event/dispatched
                 :tags {:machine-id :auth/login}}
                {:id 2 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :idle :to :a}}
                {:id 3 :operation :sub/run
                 :tags {:machine-id :auth/login}}]
        rows   (h/project-transitions buffer :auth/login)]
    (is (= [2] (map :id rows)))))

(deftest project-transitions-row-carries-event-and-dispatch-id
  (let [buffer [{:id 1 :operation :rf.machine/transition
                 :time 100
                 :tags {:machine-id :auth/login
                        :from :idle :to :authing
                        :event [:auth/submit "ada"]
                        :dispatch-id "d-42"}}]
        rows   (h/project-transitions buffer :auth/login)
        row    (first rows)]
    (is (= [:auth/submit "ada"] (:event row)))
    (is (= "d-42" (:dispatch-id row)))
    (is (= :idle (:from row)))
    (is (= :authing (:to row)))
    (is (= 100 (:time row)))))

;; ---- (7) cap-transitions -----------------------------------------------

(deftest cap-transitions-defaults-to-200
  (let [rows (vec (repeat 250 {:id 1}))]
    (is (= 200 (count (h/cap-transitions rows))))))

(deftest cap-transitions-keeps-under-cap-rows-unchanged
  (let [rows [{:id 1} {:id 2} {:id 3}]]
    (is (= rows (h/cap-transitions rows)))))

(deftest cap-transitions-honours-custom-cap
  (let [rows [{:id 1} {:id 2} {:id 3} {:id 4} {:id 5}]]
    (is (= 2 (count (h/cap-transitions rows 2))))
    (is (= [{:id 1} {:id 2}] (h/cap-transitions rows 2))
        "takes from the head — newest first preserved by project-transitions")))

;; ---- (8) project-data --------------------------------------------------

(deftest project-data-empty-when-no-machines
  (let [d (h/project-data [] {} [] nil :rf/default)]
    (is (= [] (:machines d)))
    (is (= 0 (:total d)))
    (is (nil? (:selected-id d)))
    (is (nil? (:selected d)))
    (is (nil? (:chart-props d)))
    (is (= [] (:transitions d)))
    (is (= :no-machines (:empty-kind d)))))

(deftest project-data-shapes-everything-the-view-needs
  (let [machines  [:auth/login :checkout/flow]
        snapshots {:auth/login {:state :authing :data {:user "ada"}}}
        buffer    [{:id 1 :operation :rf.machine/transition
                    :tags {:machine-id :auth/login
                           :from :idle :to :authing
                           :event [:auth/submit] :dispatch-id "d-1"}}]
        d         (h/project-data machines snapshots buffer nil :rf/default)]
    (is (= 2 (:total d)))
    (is (= :auth/login (:selected-id d))
        "selection defaults to first row (sorted) when no explicit pick")
    (is (some? (:selected d)))
    (is (= :auth/login (-> d :chart-props :machine-id)))
    (is (= :rf/default (-> d :chart-props :frame-id)))
    (is (= {:state :authing :data {:user "ada"}}
           (-> d :chart-props :current-state-override)))
    (is (= 1 (count (:transitions d))))
    (is (nil? (:empty-kind d)))))

(deftest project-data-honours-explicit-selection
  (let [d (h/project-data [:auth/login :checkout/flow]
                          {}
                          []
                          :checkout/flow
                          :rf/default)]
    (is (= :checkout/flow (:selected-id d)))))

(deftest project-data-falls-back-to-first-when-selection-stale
  (let [d (h/project-data [:auth/login]
                          {}
                          []
                          :nonexistent/machine
                          :rf/default)]
    (is (= :auth/login (:selected-id d))
        "stale selection -> first row (the picker can't focus a non-row)")))

(deftest project-data-transitions-scoped-to-selection
  (let [machines [:auth/login :checkout/flow]
        buffer   [{:id 1 :operation :rf.machine/transition
                   :tags {:machine-id :auth/login   :from :idle :to :a}}
                  {:id 2 :operation :rf.machine/transition
                   :tags {:machine-id :checkout/flow :from :idle :to :a}}]
        d        (h/project-data machines {} buffer :checkout/flow :rf/default)]
    (is (= 1 (count (:transitions d))))
    (is (= 2 (:id (first (:transitions d))))
        "only the selected machine's transitions are surfaced")))

;; ---- (9) format-* helpers ----------------------------------------------

(deftest format-machine-id-handles-keywords
  (is (= ":auth/login" (h/format-machine-id :auth/login)))
  (is (= "" (h/format-machine-id nil)))
  (is (= "" (h/format-machine-id ""))))

(deftest format-state-handles-uninit
  (is (= "(uninit)" (h/format-state nil)))
  (is (= ":authing" (h/format-state :authing))))

(deftest format-event-handles-nil-and-vector
  (is (= "" (h/format-event nil)))
  (is (= "[:auth/submit]" (h/format-event [:auth/submit]))))

;; ---- (10) focused-event lens (rf2-a9cke) --------------------------------

(defn- t-event
  "Build a `:rf.machine/transition` trace event with the
  registration.cljc shape — `:before`/`:after` snapshots in `:tags`."
  ([id mid from to ev]
   (t-event id mid from to ev :rf.machine/transition))
  ([id mid from to ev op]
   {:id        id
    :time      (* id 10)
    :operation op
    :tags      {:machine-id  mid
                :before      {:state from :data {}}
                :after       {:state to   :data {}}
                :event       ev
                :dispatch-id (str "d-" id)}}))

(deftest project-focused-event-empty-for-no-events
  (is (= [] (h/project-focused-event-transitions nil)))
  (is (= [] (h/project-focused-event-transitions []))))

(deftest project-focused-event-empty-for-no-machine-traces
  (testing "a cascade with no machine traces yields the silent-by-default
            empty vector"
    (let [events [{:id 1 :operation :event/dispatched
                   :tags {:event [:foo]}}
                  {:id 2 :operation :sub/run
                   :tags {:sub-id ::bar}}]]
      (is (= [] (h/project-focused-event-transitions events))))))

(deftest project-focused-event-projects-one-record-per-transition
  (let [events [(t-event 1 :auth/login :idle    :authing [:auth/submit])
                (t-event 2 :auth/login :authing :done    [:auth/ok])]
        records (h/project-focused-event-transitions events)]
    (is (= 2 (count records)))
    (is (= [:auth/login :auth/login] (mapv :machine-id records)))
    (is (= [:idle :authing]          (mapv :from-state records)))
    (is (= [:authing :done]          (mapv :to-state records)))
    (is (= [:auth/submit :auth/ok]   (mapv :on-event records)))))

(deftest project-focused-event-preserves-cascade-order
  (testing "records are oldest-first (cascade document order) regardless
            of buffer-insertion order"
    (let [events [(t-event 2 :auth/login :authing :done    [:auth/ok])
                  (t-event 1 :auth/login :idle    :authing [:auth/submit])]
          records (h/project-focused-event-transitions events)]
      (is (= [:idle :authing] (mapv :from-state records))))))

(deftest project-focused-event-multi-machine
  (testing "a cascade triggering ≥ 1 transitions across multiple machines
            yields one record per transition, document-order"
    (let [events [(t-event 1 :auth/login    :idle   :ok    [:bootstrap])
                  (t-event 2 :checkout/flow :idle   :paying [:cart/sync])
                  (t-event 3 :session/clock :tick-0 :tick-1 [:tick])]
          records (h/project-focused-event-transitions events)]
      (is (= 3 (count records)))
      (is (= [:auth/login :checkout/flow :session/clock]
             (mapv :machine-id records))))))

(deftest project-focused-event-surfaces-microstep-flag
  (let [events [(t-event 1 :auth/login :idle    :authing [:auth/submit])
                (t-event 2 :auth/login :authing :done    [:always]
                          :rf.machine.microstep/transition)]
        records (h/project-focused-event-transitions events)]
    (is (= [false true] (mapv :microstep? records)))))

(deftest project-focused-event-attaches-definition-when-present
  (let [definitions {:auth/login {:initial :idle
                                  :states  {:idle    {:on {:submit :authing}}
                                            :authing {:on {:ok :done}}
                                            :done    {:final? true}}}}
        events [(t-event 1 :auth/login :idle :authing [:auth/submit])]
        records (h/project-focused-event-transitions events definitions)]
    (is (= 1 (count records)))
    (is (= (get definitions :auth/login)
           (-> records first :definition)))))

(deftest project-focused-event-drops-records-without-machine-id
  (testing "a malformed trace lacking :machine-id is dropped rather
            than rendered as an identityless section"
    (let [events [{:id 1 :time 1 :operation :rf.machine/transition
                   :tags {:before {:state :a :data {}}
                          :after  {:state :b :data {}}}}]]
      (is (= [] (h/project-focused-event-transitions events))))))

(deftest project-focused-event-attaches-guard-and-action-traces
  (testing "when the substrate emits guard-evaluated / action-ran traces,
            they attach to the per-transition record by machine-id"
    (let [events [(t-event 1 :auth/login :idle :authing [:auth/submit])
                  {:id 2 :time 11 :operation :rf.machine/guard-evaluated
                   :tags {:machine-id :auth/login
                          :guard-id   :user-has-credentials?
                          :input      {:user "ada"}
                          :outcome    :pass}}
                  {:id 3 :time 12 :operation :rf.machine/action-ran
                   :tags {:machine-id :auth/login
                          :action-id  :issue-token
                          :input      {:user "ada"}
                          :outcome    :ok}}]
          records (h/project-focused-event-transitions events)]
      (is (= 1 (count records)))
      (let [rec (first records)]
        (is (= 1 (count (:guards rec))))
        (is (= :user-has-credentials? (-> rec :guards first :guard-id)))
        (is (= :pass                  (-> rec :guards first :outcome)))
        (is (= 1 (count (:actions rec))))
        (is (= :issue-token (-> rec :actions first :action-id)))
        (is (= :ok          (-> rec :actions first :outcome)))))))

;; ---- (11) focused-epoch-record (rf2-a9cke) ------------------------------

(deftest focused-epoch-record-empty-history
  (is (nil? (h/focused-epoch-record nil  {:epoch-id 7})))
  (is (nil? (h/focused-epoch-record []   {:epoch-id 7}))))

(deftest focused-epoch-record-matches-by-epoch-id
  (let [history [{:epoch-id 5 :trace-events []}
                 {:epoch-id 7 :trace-events [:x]}
                 {:epoch-id 9 :trace-events []}]]
    (is (= 7 (:epoch-id (h/focused-epoch-record history {:epoch-id 7}))))))

(deftest focused-epoch-record-falls-back-to-head-for-live-focus
  (testing "LIVE focus carries nil :epoch-id → fall back to head (the
            most recent settling epoch in the history)"
    (let [history [{:epoch-id 5 :trace-events []}
                   {:epoch-id 7 :trace-events []}]]
      (is (= 7 (:epoch-id (h/focused-epoch-record history nil))))
      (is (= 7 (:epoch-id (h/focused-epoch-record history {:epoch-id nil})))))))

(deftest focused-epoch-record-falls-back-to-head-when-evicted
  (testing "Focused :epoch-id no longer in the buffer → fall back to head
            so the panel renders SOMETHING rather than going silent on a
            buffer-eviction event"
    (let [history [{:epoch-id 5 :trace-events []}
                   {:epoch-id 7 :trace-events []}]]
      (is (= 7 (:epoch-id (h/focused-epoch-record history {:epoch-id 99})))))))
