(ns day8.re-frame2-xray.panels.machine-inspector-helpers-cljs-test
  "Pure-data tests for Xray's Machine Inspector panel helpers
  (Phase 5+, rf2-r9f9u).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `subscriptions_helpers_cljs_test.cljc`,
  etc.:

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
            #?(:clj  [re-frame.test-support :as rf.test-support
                      :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support
                      :refer-macros [with-trace-recorder!]])
            [clojure.string :as str]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as h]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---- (1) transition-event? ---------------------------------------------

(deftest transition-event-recognises-outer-transition
  (is (true?  (h/transition-event? {:operation :rf.machine/transition})))
  (is (true?  (h/transition-event?
                {:operation :rf.machine.microstep/transition})))
  (is (false? (h/transition-event? {:operation :rf.event/dispatched})))
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
  (let [buffer [{:id 1 :operation :rf.event/dispatched
                 :tags {:machine-id :auth/login}}
                {:id 2 :operation :rf.machine/transition
                 :tags {:machine-id :auth/login :from :idle :to :a}}
                {:id 3 :operation :rf.sub/run
                 :tags {:machine-id :auth/login}}]
        rows   (h/project-transitions buffer :auth/login)]
    (is (= [2] (map :id rows)))))

(deftest project-transitions-row-carries-event-and-dispatch-id
  (let [buffer [{:id 1 :operation :rf.machine/transition
                 :time 100
                 :tags {:machine-id :auth/login
                        :from :idle :to :authing
                        :event [:auth/submit "ada"]
                        :rf.trace/dispatch-id "d-42"}}]
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
                           :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}]
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
                :rf.trace/dispatch-id (str "d-" id)}}))

(deftest project-focused-event-empty-for-no-events
  (is (= [] (h/project-focused-event-transitions nil)))
  (is (= [] (h/project-focused-event-transitions []))))

(deftest project-focused-event-empty-for-no-machine-traces
  (testing "a cascade with no machine traces yields the silent-by-default
            empty vector"
    (let [events [{:id 1 :operation :rf.event/dispatched
                   :tags {:rf.event/v [:foo]}}
                  {:id 2 :operation :rf.sub/run
                   :tags {:rf.sub/id ::bar}}]]
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

;; ---- a SPAWNED actor's definition (rf2-3x7nj.23.2) ----------------------

(def ^:private spawn-parent-id :xray-spawned-def/parent)
(def ^:private spawn-child-type :xray-spawned-def/child)

(defn- with-real-runtime
  "Run `f` against a freshly reset plain-atom runtime. Only the
  producer-derived row below drives the machines runtime; everything else
  in this file is pure data, so the reset wraps that row alone rather than
  riding a file-wide `:each` fixture."
  [f]
  ((rf.test-support/make-reset-runtime-fixture
     {:adapter rf.substrate.plain-atom/adapter})
   f))

(defn- store-definitions
  "The `{machine-id spec}` map for `ids`, built the way the panel's
  `machine-definitions-value` builds it: each REGISTERED id's `:rf/machine`
  spec off the source store."
  [ids]
  (into {}
        (keep (fn [id]
                (let [m (rf/handler-meta {:source :store :kind :event :id id})]
                  (when (:rf/machine? m) [id (:rf/machine m)]))))
        ids))

(deftest project-focused-event-attaches-definition-for-a-spawned-actor
  (testing "rf2-3x7nj.23.2 — a SPAWNED actor transitions under its
            `<type>#<n>` instance address, which no key of the registered-id
            definitions map names. The record resolves the definition
            through the TYPE its snapshot carries at `:rf/machine-type`, so
            the focused-event chart can render. Producer-derived: the
            machines runtime spawns the actor and emits the transition"
    (with-real-runtime
      (fn []
        (rf/reg-machine spawn-child-type
          {:initial :idle
           :states  {:idle {:on {:go :busy}}
                     :busy {}}})
        (rf/reg-machine spawn-parent-id
          {:initial :idle
           :states  {:idle    {:on {:start :running}}
                     :running {:spawn {:machine-id spawn-child-type}}}})
        (rf/dispatch-sync [spawn-parent-id [:start]])
        (let [snapshots (get-in (rf.frame/frame-runtime-db-value :rf/default)
                                [:rf.runtime/machines :snapshots])
              actor     (some (fn [[id snap]]
                                (when (= spawn-child-type (:rf/machine-type snap)) id))
                              snapshots)
              defs      (store-definitions [spawn-parent-id spawn-child-type])]
          (is (some? actor) "PRECONDITION: the parent spawned a child actor")
          (is (not (contains? defs actor))
              "PRECONDITION: the actor's address is not a registered id")
          (with-trace-recorder! [traces]
            (rf/dispatch-sync [actor [:go]])
            (let [rec (->> (h/project-focused-event-transitions @traces defs)
                           (filter #(= actor (:machine-id %)))
                           first)]
              (is (= [:idle :busy] [(:from-state rec) (:to-state rec)])
                  "PRECONDITION: the spawned actor's transition was captured")
              (is (some? (:definition rec))
                  "the spawned actor's record carries a definition")
              (is (= (get defs spawn-child-type) (:definition rec))
                  "and it is the registered TYPE's spec")))))))
  (testing "an inline-`:definition` spawn stamps the spec map itself as its
            type, and that map is the definition"
    (let [inline {:initial :idle
                  :states  {:idle {:on {:go :busy}} :busy {}}}
          ev     (assoc-in (t-event 1 :xray-spawned-def/inline#1 :idle :busy [:go])
                           [:tags :after :rf/machine-type] inline)]
      (is (= inline (-> (h/project-focused-event-transitions [ev] {})
                        first :definition))))))

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

(deftest project-focused-event-attaches-history-restore-and-record
  ;; rf2-mle6e.5 — a transition that resolved a `:type :history` pseudo-state
  ;; carries `:history-restored`; one whose macrostep exited a history-bearing
  ;; compound carries `:history-recorded` (spec/009 §History trace events). The
  ;; lens surfaces them so the Machine Inspector renders WHY a re-entry landed
  ;; where it did.
  (testing "history restore/record traces attach to the per-transition record"
    (let [events [(t-event 1 :media/deep [:player :stopped] [:player :playing :mid-track]
                           [:insert])
                  {:id 2 :time 11 :operation :rf.machine.history/restored
                   :tags {:machine-id :media/deep :compound-path [:player]
                          :kind :deep :source :recorded
                          :restored-config [:player :playing :mid-track]
                          :resolved-leaf [:player :playing :mid-track]}}]
          rec    (-> (h/project-focused-event-transitions events) first)]
      (is (= 1 (count (:history-restored rec))))
      (is (= :recorded (-> rec :history-restored first :source)))
      (is (= :deep (-> rec :history-restored first :kind)))
      (is (= [:player :playing :mid-track]
             (-> rec :history-restored first :restored-config)))))
  (testing "the recorded trace attaches as :history-recorded"
    (let [events [(t-event 1 :media/deep [:player :playing :mid-track] [:tray] [:eject])
                  {:id 2 :time 11 :operation :rf.machine.history/recorded
                   :tags {:machine-id :media/deep :compound-path [:player]
                          :kind :deep :recorded-config [:player :playing :mid-track]}}]
          rec    (-> (h/project-focused-event-transitions events) first)]
      (is (= 1 (count (:history-recorded rec))))
      (is (= [:player :playing :mid-track]
             (-> rec :history-recorded first :recorded-config)))))
  (testing "an ordinary (non-history) transition carries NEITHER history key"
    (let [events [(t-event 1 :auth/login :idle :authing [:auth/submit])]
          rec    (-> (h/project-focused-event-transitions events) first)]
      (is (nil? (:history-restored rec)))
      (is (nil? (:history-recorded rec))))))

(deftest project-focused-event-surfaces-before-and-after-snapshots
  ;; rf2-lxvn6 (phase 4 of rf2-oqa60) — the per-transition record
  ;; carries the full `:before` / `:after` snapshot maps so the panel's
  ;; snapshot drill-in surface (spec/021 §10 widget contract) can
  ;; render them via the first-class edn-inspector widget. The two
  ;; slots are nil when the trace tags lack the commit-or-finalize
  ;; snapshot pair (legacy fixtures).
  (testing "the record exposes :before and :after snapshot maps when
            the trace tags carry them"
    (let [events [(t-event 1 :auth/login :idle :authing [:auth/submit])]
          rec    (-> (h/project-focused-event-transitions events) first)]
      (is (= {:state :idle :data {}} (:before rec))
          ":before snapshot threaded through")
      (is (= {:state :authing :data {}} (:after rec))
          ":after snapshot threaded through")))
  (testing "the record's :before / :after slots are nil for legacy
            traces that only carry the `:from`/`:to` tag slots"
    (let [events [{:id 1 :time 1 :operation :rf.machine/transition
                   :tags {:machine-id :auth/login
                          :from       :idle
                          :to         :authing
                          :event      [:auth/submit]}}]
          rec    (-> (h/project-focused-event-transitions events) first)]
      (is (nil? (:before rec))
          ":before is nil on legacy traces — drill-in suppresses the block")
      (is (nil? (:after rec))
          ":after is nil on legacy traces")
      ;; The from/to-state fallback still resolves so the lens renders.
      (is (= :idle (:from-state rec)))
      (is (= :authing (:to-state rec))))))

(deftest project-focused-event-coerces-fn-refs-to-renderable-ids
  ;; rf2-ujra6 — per spec/Spec-Schemas `:guard-id` / `:action-id` carry
  ;; the user-declared ref as-is, which is "keyword OR inline fn". The
  ;; deep-machine testbed (`testbeds/deep_machine/core.cljs`) declares
  ;; state-node `:entry` slots as raw fns; when those fire the
  ;; `:rf.machine/action-ran` trace carries the fn itself in
  ;; `:action-id`. Before #1601 these traces lacked `:frame` and were
  ;; dropped by epoch-capture; post-#1601 they flow into
  ;; `:trace-events` and through this projection. The view renders
  ;; `:action-id` via `(name ...)` to build a `data-testid` suffix —
  ;; which throws `Doesn't support name: function ...` on fn values.
  ;; The projection coerces fn refs to renderable keywords so the view
  ;; contract stays simple.
  (testing "anonymous inline fn ref normalises to :rf.machine/anonymous-fn"
    (let [anon-fn (fn [_data _ev] {:data {}})
          events  [(t-event 1 :auth/login :idle :authing [:auth/submit])
                   {:id 2 :time 11 :operation :rf.machine/action-ran
                    :tags {:machine-id :auth/login
                           :action-id  anon-fn
                           :outcome    :ok}}]
          records (h/project-focused-event-transitions events)
          a-id    (-> records first :actions first :action-id)]
      (is (keyword? a-id)
          "fn ref must be coerced to a keyword so the view's `name` call works")
      (is (= :rf.machine/anonymous-fn a-id))))
  (testing "named fn ref via :name metadata normalises to a keyword carrying that name"
    (let [named-fn (with-meta (fn [_data _ev] {:data {}})
                              {:name 'action-bump-tick})
          events   [(t-event 1 :auth/login :idle :authing [:auth/submit])
                    {:id 2 :time 11 :operation :rf.machine/guard-evaluated
                     :tags {:machine-id :auth/login
                            :guard-id   named-fn
                            :outcome    :pass}}]
          records  (h/project-focused-event-transitions events)
          g-id     (-> records first :guards first :guard-id)]
      (is (keyword? g-id))
      (is (= :rf.machine/action-bump-tick g-id))))
  (testing "keyword refs flow through untouched"
    (let [events  [(t-event 1 :auth/login :idle :authing [:auth/submit])
                   {:id 2 :time 11 :operation :rf.machine/action-ran
                    :tags {:machine-id :auth/login
                           :action-id  :issue-token
                           :outcome    :ok}}]
          records (h/project-focused-event-transitions events)]
      (is (= :issue-token (-> records first :actions first :action-id))))))

;; ---- (10b) machine BIRTH (`:rf.machine/started`) — rf2-eldze ------------
;;
;; A pure machine start emits `:rf.machine/started` (the birth signal) but
;; NO `:rf.machine/transition` (machines · lifecycle_fx · registration.cljc
;; — rf2-gl588 / rf2-coozg). Before rf2-eldze the focused-event lens only
;; projected transitions, so a focused start epoch produced zero records
;; and the Machine tab rendered the "does not target a state machine"
;; empty state. These tests pin that a start IS surfaced as a first-class
;; record (no from-state; to-state = the resulting initial state), and
;; that ordinary transitions are unaffected.

(defn- started-event
  "Build a `:rf.machine/started` (machine BIRTH) trace event with the
  registration.cljc shape — `{:machine-id :state :data :cause}` in
  `:tags`, `:state`/`:data` being the INITIAL snapshot slots."
  ([id mid state] (started-event id mid state {} :explicit))
  ([id mid state data cause]
   {:id        id
    :time      (* id 10)
    :operation :rf.machine/started
    :tags      {:machine-id mid
                :state      state
                :data       data
                :cause      cause
                :rf.trace/dispatch-id (str "s-" id)}}))

(deftest started-event-predicate
  (is (true?  (h/started-event? {:operation :rf.machine/started})))
  (is (false? (h/started-event? {:operation :rf.machine/transition})))
  (is (false? (h/started-event? nil)))
  (is (false? (h/started-event? {}))))

(deftest project-focused-event-surfaces-machine-start
  (testing "a focused machine-start epoch yields ONE record with no
            from-state and the resulting initial state as to-state — so
            the Machine tab renders the topology (initial highlighted)
            rather than the empty state (rf2-eldze)"
    (let [events  [(started-event 1 :door/main :closed {:open? false} :explicit)]
          records (h/project-focused-event-transitions events)
          rec     (first records)]
      (is (= 1 (count records))
          "the start is a first-class focused-event record — NOT dropped")
      (is (= :door/main (:machine-id rec)))
      (is (nil? (:from-state rec))
          "a birth has no from-state (entry into the initial state)")
      (is (= :closed (:to-state rec))
          "to-state is the resulting INITIAL state — the chart highlights it")
      (is (true? (:start? rec))
          ":start? flags the birth case for the view")
      (is (= :explicit (:cause rec)))
      (is (= [:rf.machine/start] (:event rec))
          "the synthetic creation-marker event rides the record")
      (is (= :rf.machine/start (:on-event rec)))
      (is (nil? (:before rec))
          "the machine did not exist before its birth")
      (is (= {:state :closed :data {:open? false}} (:after rec))
          "the initial snapshot is synthesized for the drill-in"))))

(deftest project-focused-event-start-carries-definition
  (testing "a start record gets the registered definition attached so the
            chart can render the topology"
    (let [definitions {:door/main {:initial :closed
                                    :states  {:closed {:on {:push :open}}
                                              :open   {}}}}
          events  [(started-event 1 :door/main :closed)]
          records (h/project-focused-event-transitions events definitions)]
      (is (= (get definitions :door/main)
             (-> records first :definition))))))

(deftest project-focused-event-start-and-transition-interleave
  (testing "a cascade carrying BOTH a birth and a later transition yields
            both records in cascade order; the transition is unaffected by
            the start fold (no regression)"
    (let [events  [(started-event 1 :door/main :closed)
                   (t-event 2 :door/main :closed :open [:door/push])]
          records (h/project-focused-event-transitions events)]
      (is (= 2 (count records)))
      (is (= [true false] (mapv (comp boolean :start?) records))
          "the first is the birth, the second an ordinary transition")
      ;; The ordinary transition still resolves from/to exactly as before.
      (is (= [nil :closed]  (mapv :from-state records)))
      (is (= [:closed :open] (mapv :to-state records))))))

(deftest project-focused-event-transition-still-not-flagged-start
  (testing "an ordinary transition record carries no :start? flag — the
            birth fold does not contaminate the transition projector"
    (let [events  [(t-event 1 :auth/login :idle :authing [:auth/submit])]
          rec     (-> (h/project-focused-event-transitions events) first)]
      (is (nil? (:start? rec)))
      (is (= :idle (:from-state rec)))
      (is (= :authing (:to-state rec))))))

;; ---- (10c) guard-blocked / NO-OP (`:rf.machine.event/unhandled-no-op`) — rf2-skmc7 ----
;;
;; A machine event that matched no transition — an UNHANDLED user event OR a
;; transition whose GUARD failed — emits `:rf.machine.event/unhandled-no-op`
;; (the SOLE signal; no `:rf.machine/transition`) and leaves the machine in
;; its current state. The event DID target a registered machine, so the
;; Machine tab MUST render the topology with the CURRENT state highlighted —
;; NOT the 'does not target a state machine' empty state (spec/003 §Empty
;; state: "Unhandled-event no-op is NOT this empty state"). This is the SAME
;; gap rf2-eldze fixed for the START case, for a different no-transition cause.
;; These tests pin that a no-op IS surfaced as a first-class record and that
;; transitions / starts / genuinely-non-machine events are unaffected.

(defn- no-op-event
  "Build a `:rf.machine.event/unhandled-no-op` (guard-blocked / unhandled)
  trace event with the substrate shape — `{:machine-id :event :state}` in
  `:tags`, `:state` being the machine's CURRENT (unchanged) state."
  ([id mid state event] (no-op-event id mid state event :rf.machine.event/unhandled-no-op))
  ([id mid state event op]
   {:id        id
    :time      (* id 10)
    :operation op
    :tags      {:machine-id mid
                :state      state
                :event      event
                :rf.trace/dispatch-id (str "n-" id)}}))

(deftest no-op-event-predicate
  (is (true?  (h/no-op-event? {:operation :rf.machine.event/unhandled-no-op})))
  (is (false? (h/no-op-event? {:operation :rf.machine/transition})))
  (is (false? (h/no-op-event? {:operation :rf.machine/started})))
  (is (false? (h/no-op-event? nil)))
  (is (false? (h/no-op-event? {}))))

(deftest project-focused-event-surfaces-guard-blocked-no-op
  (testing "a focused guard-blocked / no-op machine event (the door
            `:may-close?`-fail close) yields ONE record with from-state ==
            to-state == the CURRENT state and `:no-op? true` — so the
            Machine tab renders the topology (current state highlighted)
            rather than the 'does not target a state machine' empty state
            (rf2-skmc7)"
    (let [events  [(no-op-event 1 :door/main :open [:door/close])]
          records (h/project-focused-event-transitions events)
          rec     (first records)]
      (is (= 1 (count records))
          "the no-op is a first-class focused-event record — NOT dropped")
      (is (= :door/main (:machine-id rec)))
      (is (true? (:no-op? rec))
          ":no-op? flags the guard-blocked / unhandled case for the view")
      (is (= :open (:from-state rec))
          "from-state is the current state — the machine stayed put")
      (is (= :open (:to-state rec))
          "to-state == from-state (a stationary self-loop; the chart
           highlights the one current state)")
      (is (= [:door/close] (:event rec))
          "the inbound user event that produced the no-op rides the record")
      (is (= :door/close (:on-event rec)))
      (is (nil? (:start? rec))
          "a no-op is NOT a birth")
      (is (nil? (:before rec))
          "the no-op trace carries no snapshot pair — drill-in suppresses")
      (is (nil? (:after rec))))))

(deftest project-focused-event-no-op-carries-definition
  (testing "a no-op record gets the registered definition attached so the
            chart can render the topology"
    (let [definitions {:door/main {:initial :closed
                                    :states  {:closed {:on {:push :open}}
                                              :open   {:on {:close :closed}}}}}
          events  [(no-op-event 1 :door/main :open [:door/close])]
          records (h/project-focused-event-transitions events definitions)]
      (is (= (get definitions :door/main)
             (-> records first :definition))))))

(deftest project-focused-event-no-op-deduped-against-transition
  (testing "a machine that BOTH transitioned and later no-op'd in one cascade
            surfaces ONLY its transition record — a no-op is single-signalled
            (Spec 005); no redundant ghost no-op section"
    (let [events  [(t-event 1 :door/main :closed :open [:door/push])
                   (no-op-event 2 :door/main :open [:door/close])]
          records (h/project-focused-event-transitions events)]
      (is (= 1 (count records))
          "only the transition record survives — the no-op for the same
           machine is dropped")
      (is (= [:open] (mapv :to-state records)))
      (is (nil? (-> records first :no-op?))
          "the surviving record is the transition, not the no-op"))))

(deftest project-focused-event-no-op-interleaves-with-other-machines
  (testing "a cascade where machine A transitions and machine B no-ops yields
            one record per machine in trace order — B's no-op is first-class"
    (let [events  [(no-op-event 1 :door/main :open [:door/close])
                   (t-event 2 :auth/login :idle :authing [:auth/submit])]
          records (h/project-focused-event-transitions events)]
      (is (= 2 (count records)))
      (is (= [:door/main :auth/login] (mapv :machine-id records))
          "records are oldest-first by trace order; the no-op leads")
      (is (= [true false] (mapv (comp boolean :no-op?) records))
          "the first is the no-op, the second an ordinary transition"))))

(deftest project-focused-event-no-op-dedup-multiple-for-same-machine
  (testing "multiple no-op traces for the SAME machine in one cascade collapse
            to a single record (keep the first in trace order)"
    (let [events  [(no-op-event 1 :door/main :open [:door/close])
                   (no-op-event 2 :door/main :open [:door/lock])]
          records (h/project-focused-event-transitions events)]
      (is (= 1 (count records)))
      (is (= [:door/close] (-> records first :event))
          "the first no-op in trace order wins"))))

(deftest project-focused-event-genuinely-non-machine-still-empty
  (testing "an event that targets NO machine at all (no transition / start /
            no-op trace) STILL yields the empty vector — the 'does not target
            a state machine' placeholder is reserved for that case (rf2-skmc7
            does NOT widen the gate to non-machine events)"
    (let [events [{:id 1 :operation :rf.event/dispatched
                   :tags {:rf.event/v [:foo]}}
                  {:id 2 :operation :rf.sub/run
                   :tags {:rf.sub/id ::bar}}]]
      (is (= [] (h/project-focused-event-transitions events))
          "no machine trace of any kind → still empty (still 'does not
           target a state machine')"))))

(deftest project-focused-event-transition-still-not-flagged-no-op
  (testing "an ordinary transition record carries no :no-op? flag — the
            no-op fold does not contaminate the transition projector"
    (let [events  [(t-event 1 :auth/login :idle :authing [:auth/submit])]
          rec     (-> (h/project-focused-event-transitions events) first)]
      (is (nil? (:no-op? rec)))
      (is (= :idle (:from-state rec)))
      (is (= :authing (:to-state rec))))))

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

(deftest focused-epoch-record-nil-when-evicted
  (testing "rf2-uo0rc.1 — a PINNED focus :epoch-id no longer in the buffer
            (evicted from the per-frame ring) resolves to nil, NOT a
            silent head-fallback. Per spec/021 §10.7 every panel renders
            the evicted placeholder; the Machine Inspector must not show
            the LATEST machine state while the operator believes they are
            inspecting the pinned (evicted) epoch. Routes through the
            shared focus-resolver/find-epoch-record, matching Issues /
            Trace / Epoch / App-DB."
    (let [history [{:epoch-id 5 :trace-events []}
                   {:epoch-id 7 :trace-events []}]]
      (is (nil? (h/focused-epoch-record history {:epoch-id 99}))
          "evicted pinned epoch must be nil (not the head record)"))))

(deftest focused-epoch-record-nil-when-pinned-bundle-settled-no-epoch
  (testing "rf2-c4abp / rf2-y8doi.19 — the operator pinned an event bundle
            that settled NO epoch. Focus then carries a `:dispatch-id` with
            a nil `:epoch-id`, which is SHAPE-IDENTICAL to the cold-start
            UNSET focus the rf2-h0120 head-fallback exists to serve — so
            reading `:epoch-id` alone cannot tell the two apart, and this
            helper answered the HEAD for both. That put a DIFFERENT event's
            machine state under the operator's selection, with nothing on
            screen saying so: the same class of state-reconstruction lie
            rf2-uo0rc.1 fixed for the evicted case just above.

            The pinned `:dispatch-id` is the discriminator. The Epoch panel
            got it in rf2-y8doi.19; this is that discriminator reaching the
            Machine Inspector, through the SAME shared resolver rather than
            a parallel selection policy."
    (let [history [{:epoch-id 5  :dispatch-id 5  :trace-events []}
                   {:epoch-id 11 :dispatch-id 11 :trace-events [:x]}]]
      (is (nil? (h/focused-epoch-record history {:dispatch-id 999 :epoch-id nil}))
          (str "a pinned bundle that settled no epoch must resolve to NO "
               "record — head-fallback here renders epoch 11's machine "
               "state under a selection that is not epoch 11's"))
      (is (nil? (h/focused-epoch-record history {:dispatch-id :ungrouped
                                                 :epoch-id    nil}))
          (str "an :ungrouped pin settles no epoch either "
               "(spine/epoch-id-for-event-bundle) and must not head-fall-back")))))

(deftest focused-epoch-record-rejects-only-the-pinned-no-epoch-shape
  (testing "rf2-c4abp POSITIVE CONTROL — the discriminator must reject ONLY
            the pinned-no-epoch shape. An UNSET focus still head-falls-back,
            an ordinary pinned epoch still resolves to its own record, and
            the evicted case is unchanged. Without this row the fix could
            pass by breaking normal selection outright."
    (let [history [{:epoch-id 5  :dispatch-id 5  :trace-events []}
                   {:epoch-id 11 :dispatch-id 11 :trace-events [:x]}]]
      (is (= 11 (:epoch-id (h/focused-epoch-record history nil)))
          "nil focus still resolves the head (rf2-h0120)")
      (is (= 11 (:epoch-id (h/focused-epoch-record history {})))
          "an empty focus map still resolves the head")
      (is (= 11 (:epoch-id (h/focused-epoch-record history {:epoch-id    nil
                                                            :dispatch-id nil})))
          "an explicitly nil :dispatch-id is still an UNSET focus")
      (is (= 5 (:epoch-id (h/focused-epoch-record history {:epoch-id    5
                                                           :dispatch-id 5})))
          "an ordinary selected epoch still resolves to its own record")
      (is (nil? (h/focused-epoch-record history {:epoch-id 99 :dispatch-id 99}))
          "an evicted pinned epoch is unchanged — still nil (rf2-uo0rc.1)"))))

;; ---- focused-event-section-key (rf2-un3gfo) -----------------------------
;;
;; The per-machine focused-event section's React `:key` must be
;; STRUCTURAL (target-frame + machine-id) so ordinary Prev/Next epoch
;; navigation within the SAME machine preserves the section + nested
;; MachineChart instance (keeping the chart's parse/layout caches warm,
;; so ELK does NOT re-run and the topology does not flicker). A genuinely
;; different machine — or a frame switch — must still produce a distinct
;; key so the new topology gets a clean instance + its own ELK layout.

(deftest section-key-is-stable-across-prev-next-for-same-machine
  (testing "rf2-un3gfo — Prev/Next walks records for the SAME machine
            whose epoch id + from/to-state change every navigation. The
            structural key must NOT change across those records — only the
            machine-id (and inspected frame) are load-bearing, so React
            preserves the chart instance and ELK is not re-run."
    (let [frame :rf/default
          ;; Three records the operator walks via Prev/Next: same machine,
          ;; different epoch ids + transition endpoints each time.
          rec-1 {:machine-id :auth/login :id 1
                 :from-state :idle    :to-state :authing}
          rec-2 {:machine-id :auth/login :id 2
                 :from-state :authing :to-state :done}
          rec-3 {:machine-id :auth/login :id 3
                 :from-state :done    :to-state :idle}
          k1 (h/focused-event-section-key frame rec-1)
          k2 (h/focused-event-section-key frame rec-2)
          k3 (h/focused-event-section-key frame rec-3)]
      (is (= k1 k2 k3)
          "the section key is identical across Prev/Next records of the
           same machine — no remount, so the chart instance + its
           parse/layout caches survive and ELK does not re-run")
      ;; Pin that the per-epoch fields are NOT in the key — a regression
      ;; that folded epoch id / from-state / to-state back into the key
      ;; would reintroduce the per-nav remount + ELK relayout flicker.
      (is (not (str/includes? k1 "1"))
          "the record/epoch id is NOT in the key")
      (is (not (str/includes? k1 "idle"))
          "from-state is NOT in the key")
      (is (not (str/includes? k1 "authing"))
          "to-state is NOT in the key"))))

(deftest section-key-changes-for-a-different-machine-topology
  (testing "rf2-un3gfo — switching to a genuinely different machine
            (different topology) MUST change the key so React mounts a
            fresh section + chart, and the new topology gets its own ELK
            layout. This is the half of the contract that must NOT
            regress in pursuit of stability."
    (let [frame :rf/default
          auth  (h/focused-event-section-key
                  frame {:machine-id :auth/login :id 1
                         :from-state :idle :to-state :authing})
          door  (h/focused-event-section-key
                  frame {:machine-id :door/main :id 1
                         :from-state :idle :to-state :authing})]
      (is (not= auth door)
          "a different machine yields a different key (clean instance +
           its own ELK layout)"))))

(deftest section-key-changes-across-inspected-frames
  (testing "rf2-un3gfo — the L1 frame picker re-seeds the panel against a
            DIFFERENT runtime, where the same machine-id may name a
            different machine instance. Including the target-frame in the
            key gives that frame switch a clean section instance."
    (let [rec {:machine-id :auth/login :id 1
               :from-state :idle :to-state :authing}]
      (is (not= (h/focused-event-section-key :rf/default rec)
                (h/focused-event-section-key :rf/checkout rec))
          "the same machine in a different inspected frame yields a
           different key")
      ;; Same frame + same machine collapses to one key (the steady case).
      (is (= (h/focused-event-section-key :rf/default rec)
             (h/focused-event-section-key :rf/default rec))
          "same frame + same machine is one stable key"))))

(deftest section-key-tolerates-nil-target-frame
  (testing "rf2-un3gfo — a single-frame / pre-seed render may have a nil
            target-frame. The key must still build (not throw) and remain
            stable across Prev/Next."
    (let [k1 (h/focused-event-section-key
               nil {:machine-id :auth/login :id 1
                    :from-state :idle :to-state :authing})
          k2 (h/focused-event-section-key
               nil {:machine-id :auth/login :id 2
                    :from-state :authing :to-state :done})]
      (is (string? k1) "key builds with a nil target-frame")
      (is (= k1 k2) "still stable across Prev/Next when frame is nil"))))

;; ---- (12) pick-focused-transition — the selection rule (rf2-mj4jp) ------
;;
;; The Dynamic panel binds to EXACTLY ONE machine per focused event
;; (spec/003 §Dynamic mode — single-instance, event-driven, rf2-8og3k).
;; Which one was `(first records)` — trace order, full stop — and that is
;; the defect rf2-mj4jp closes: rf2-y8doi.23 had already made
;; `:rf.xray/select-machine-id` pin the newest epoch touching the
;; requested machine, but when that epoch's cascade touched A and THEN B,
;; a Static JUMP to B pinned the right epoch and the panel drew A.
;;
;; These rows pin TWO properties, and both are load-bearing. Pinning only
;; the first would have passed against the bug in a recognisable way: a
;; rule that simply answered "the selected machine, always" satisfies
;; every A-and-B row below while destroying the no-selection posture the
;; panel opens in — so the second property is what stops the fix from
;; being worse than the defect.
;;
;;   1. WHICH RECORD IS SELECTED — an explicit selection that the
;;      cascade touched outranks trace order, wherever in the cascade it
;;      sits.
;;   2. ORDINARY FOLLOWING IS UNCHANGED — no selection, a stale
;;      selection, and the 1-arity all still answer first-in-trace-order,
;;      byte for byte what they answered before.
;;
;; Every row drives the REAL projection (`project-focused-event-
;; transitions`) rather than hand-built maps, so the records carry the
;; shape the panel actually receives.

(defn- cascade-records
  "The A-then-B-then-C multi-machine cascade this section reasons about,
  projected exactly as the panel projects it. Trace order is
  `:auth/login`, `:checkout/flow`, `:session/clock`."
  []
  (h/project-focused-event-transitions
    [(t-event 1 :auth/login    :idle   :authing [:auth/submit])
     (t-event 2 :checkout/flow :idle   :paying  [:cart/sync])
     (t-event 3 :session/clock :tick-0 :tick-1  [:tick])]))

;; ---- property 1: which record is selected ----

(deftest pick-focused-transition-selection-outranks-trace-order-rf2-mj4jp
  (testing "rf2-mj4jp — with A, B and C in ONE cascade, an explicit
            selection of B wins over A's earlier trace position. This is
            the defect exactly: the JUMP pinned the right epoch, wrote
            the slot, and the display drew A anyway."
    (let [records (cascade-records)]
      (is (= 3 (count records))
          "fixture really is a multi-machine cascade, not one record")
      (is (= :checkout/flow
             (:machine-id (h/pick-focused-transition records
                                                     :checkout/flow)))
          "the selected machine is the bound one, though it is SECOND in
           trace order")
      (is (= :session/clock
             (:machine-id (h/pick-focused-transition records
                                                     :session/clock)))
          "and when it is LAST — so this is the selection winning, not an
           off-by-one that happens to land on the second record"))))

(deftest pick-focused-transition-returns-the-whole-selected-record-rf2-mj4jp
  (testing "rf2-mj4jp — the caller needs the RECORD, not just the id:
            `machine_after_rings` reads `:frame-id` off it to keep two
            frames' instances apart, and the chart reads the transition
            endpoints. Answering the right machine with another machine's
            endpoints would draw B's name over A's transition."
    (let [record (h/pick-focused-transition (cascade-records)
                                            :checkout/flow)]
      (is (= :checkout/flow (:machine-id record)))
      (is (= :idle   (:from-state record)))
      (is (= :paying (:to-state   record)))
      (is (= :cart/sync (:on-event record))
          "every field comes off the SELECTED machine's own record"))))

(deftest pick-focused-transition-selection-takes-first-of-its-own-rf2-mj4jp
  (testing "rf2-mj4jp — a machine may transition more than once in one
            cascade. The selection names a MACHINE, so trace order still
            decides WHICH of that machine's records binds: the first."
    (let [records (h/project-focused-event-transitions
                    [(t-event 1 :auth/login    :idle    :authing [:go])
                     (t-event 2 :checkout/flow :idle    :paying  [:sync])
                     (t-event 3 :checkout/flow :paying  :done    [:ok])])
          record  (h/pick-focused-transition records :checkout/flow)]
      (is (= :checkout/flow (:machine-id record)))
      (is (= :idle (:from-state record))
          "the FIRST :checkout/flow record in trace order, not the last"))))

;; ---- property 2: ordinary following is unchanged ----

(deftest pick-focused-transition-no-selection-is-trace-order-rf2-mj4jp
  (testing "rf2-mj4jp — the panel's OPENING posture has no selection at
            all, and it must be bit-for-bit what it was: first in trace
            order. `:rf.xray/selected-machine-id` is nil until something
            writes it, so this is the ordinary case, not the edge one."
    (let [records (cascade-records)]
      (is (= :auth/login (:machine-id (h/pick-focused-transition records)))
          "1-arity — the pre-rf2-mj4jp spelling, unchanged")
      (is (= :auth/login
             (:machine-id (h/pick-focused-transition records nil)))
          "explicit nil selection reads the same as no selection")
      (is (= (h/pick-focused-transition records)
             (h/pick-focused-transition records nil))
          "the two spellings are the SAME answer, so no caller left on
           the 1-arity can drift from one passing the slot"))))

(deftest pick-focused-transition-stale-selection-falls-back-rf2-mj4jp
  (testing "rf2-mj4jp — a selection is sticky, so the operator walks
            Prev/Next into epochs their selected machine never touched.
            The selection must NOT blank the panel or bind to nothing
            there: it falls back to trace order, which is what ordinary
            spine following has always done."
    (let [records (cascade-records)]
      (is (= :auth/login
             (:machine-id (h/pick-focused-transition records :door/main)))
          "a machine absent from this cascade cannot outrank trace order")
      (is (= (h/pick-focused-transition records)
             (h/pick-focused-transition records :door/main))
          "identical to the no-selection answer — following is intact"))))

(deftest pick-focused-transition-nil-when-nothing-transitioned-rf2-mj4jp
  (testing "rf2-8og3k — an empty cascade binds to NO machine and the
            panel renders its placeholder. A selection must not conjure a
            record out of an empty projection."
    (is (nil? (h/pick-focused-transition [])))
    (is (nil? (h/pick-focused-transition [] :checkout/flow)))
    (is (nil? (h/pick-focused-transition nil :checkout/flow)))))

;; ---- the raw slot the view must be given (rf2-mj4jp / rf2-y8doi.23) ----

(deftest project-data-echoes-the-raw-selection-slot-rf2-mj4jp
  (testing "rf2-mj4jp — the Dynamic panel feeds the selection rule from
            `project-data`'s RAW `:selected-machine-id`, never its
            `:selected-id`. The two differ precisely when no selection
            has been made: `:selected-id` still names a machine, because
            `pick-selected` falls back to the ALPHABETICALLY first row.
            Feeding that to the rule would bind the panel to a machine
            the operator never chose — which is the wrong-machine half of
            rf2-y8doi.23, re-entered through the front door."
    (let [none (h/project-data [:checkout/flow :auth/login] {} [] nil
                               :rf/default)
          some (h/project-data [:checkout/flow :auth/login] {} []
                               :checkout/flow :rf/default)]
      (is (nil? (:selected-machine-id none))
          "NO selection stays nil in the raw slot")
      (is (= :auth/login (:selected-id none))
          "while the EFFECTIVE id names the alphabetically-first row —
           the very value that must not reach the rule")
      (is (not= (:selected-id none) (:selected-machine-id none))
          "so the two keys are genuinely different values here")
      (is (= :checkout/flow (:selected-machine-id some))
          "an explicit selection is echoed back verbatim")
      (is (= :checkout/flow (:selected-id some))
          "and both agree once the operator HAS chosen"))))
