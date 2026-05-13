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
