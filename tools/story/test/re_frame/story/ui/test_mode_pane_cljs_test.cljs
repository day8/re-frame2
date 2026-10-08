(ns re-frame.story.ui.test-mode-pane-cljs-test
  "CLJS-side tests for the `:test` mode pane.

  Pairs with `re-frame.story.ui.test-mode-state-cljs-test` (race-guard +
  toggle-expanded scenarios) and the JVM `re-frame.story.ui.test-widget-
  cljs-test` (state transitions + pure aggregations). This namespace
  pins the pane-level scenarios called out by spec/015 §`:test` mode
  pane:

  - **Pass/fail/skip row detail** — `assertion-row` produces rows whose
    `:status` keyword feeds the renderer's status-badge selector; each
    of `:pass` / `:fail` / `:skip` lands in its own row and exposes the
    expected `:detail` projection.

  - **Re-run fills the slot** — invoking `run-variant-pane!` against a
    variant populates the per-variant slot with a `:result`,
    `:ran-at-ms`, `:play-events`, and the trailing `:epoch-ids` slice.
    Re-invoking against the SAME variant updates the slot in place.

  - **Running flag** — the slot's `:running?` is true while a run is in
    flight and clears when it settles; the Re-run button disables itself
    on it.

  Per spec/009 §`:test` mode pane the renderer is a thin projection
  over the local `results-atom`; pinning the atom shape covers the
  bulk of the pane's correctness without a DOM round-trip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.epoch            :as rf.epoch]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play.evidence      :as rf.story.play.evidence]
            [re-frame.story.ui.evidence-spine  :as rf.story.ui.evidence-spine]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.pure  :as rf.story.ui.test-mode.pure]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]
            [re-frame.story.ui.test-mode.view  :as rf.story.ui.test-mode.view]
            [re-frame.subs             :as rf.subs]
            [re-frame.test-helpers     :as rf.test-helpers]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  ;; EP-0001: a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>] — mirror `re-frame.machines`.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ===========================================================================
;; A failed assertion row links to its beat in the Evidence panel
;;
;; spec/021 §2: a selected result row drives the evidence spine's selected
;; span. The row keeps its causal coordinate, and one click on its link opens
;; the Evidence panel with THAT row's beat selected.
;; ===========================================================================

(def ^:private two-cascade-narrative
  ;; Two committed dispatch cascades → flattened beat-idx 0 and 1.
  [{:step   [:dispatch [:counter/inc]]
    :epochs [{:epoch-id 100 :dispatch-id 100 :trigger-event [:counter/inc]
              :db-before {:count 0} :db-after {:count 1}
              :effects [] :sub-runs [] :renders [] :trace-events []}]}
   {:step   [:dispatch [:counter/inc]]
    :epochs [{:epoch-id 101 :dispatch-id 101 :trigger-event [:counter/inc]
              :db-before {:count 1} :db-after {:count 2}
              :effects [] :sub-runs [] :renders [] :trace-events []}]}])

(deftest assertion-row-keeps-causal-coordinate-rf2-7etf3
  (is (= [101 102] ((juxt :dispatch-id :epoch-id)
                    (rf.story.ui.test-mode.pure/assertion-row
                      {:assertion :rf.assert/path-equals :passed? false
                       :dispatch-id 101 :epoch-id 102})))))

(deftest failed-row-one-click-lands-on-its-beat-rf2-7etf3
  (testing "clicking a failed row's evidence link opens the Evidence panel
            with that row's beat selected"
    (reset! rf.story.ui.evidence-spine/selection-atom {})
    (rf.story.ui.state/swap-state! assoc-in
                                   [:panel-visibility rf.story.ui.evidence-spine/panel-key] false)
    (let [row  (rf.story.ui.test-mode.pure/assertion-row
                 {:assertion :rf.assert/path-equals :payload [[:count] 9]
                  :passed? false :expected 9 :actual 2 :dispatch-id 101})
          link (rf.story.ui.test-mode.view/row-evidence-link
                 :story.pane/v two-cascade-narrative row)]
      (is (= "1" (get (second link) :data-beat-idx)))
      (rf.test-helpers/invoke-handler link :on-click nil)
      (is (true? (get-in (rf.story.ui.state/get-state)
                         [:panel-visibility rf.story.ui.evidence-spine/panel-key]))
          "the click opens the Evidence panel")
      (is (= 1 (rf.story.ui.evidence-spine/selected-beat-idx :story.pane/v))
          "the click lands on the row's own beat (the second cascade), not the first")))
  (testing "a row that resolves to no beat renders no link"
    (is (nil? (rf.story.ui.test-mode.view/row-evidence-link
                :story.pane/v two-cascade-narrative
                (rf.story.ui.test-mode.pure/assertion-row
                  {:assertion :rf.assert/no-warnings :passed? false}))))
    (is (nil? (rf.story.ui.test-mode.view/row-evidence-link
                :story.pane/v nil
                (rf.story.ui.test-mode.pure/assertion-row
                  {:assertion :rf.assert/path-equals :passed? false :dispatch-id 101})))
        "no narrative, no beat to land on")))

;; ===========================================================================
;; The same link, driven by a REAL run rather than a hand-built row
;;
;; A hand-built row cannot see a canonical assertion record arriving with no
;; `:dispatch-id` on this runtime. The expected beat is found by its TRIGGER
;; EVENT, never by dispatch id.
;; ===========================================================================

(defn- beat-for-trigger
  "The `:beat-idx` of the retained beat whose trigger event is `event`, or nil."
  [narrative event]
  (some #(when (= event (:trigger-event %)) (:beat-idx %))
        (rf.story.play.evidence/narrative-beats narrative)))

(deftest real-failed-row-link-opens-its-own-non-first-beat-rf2-v5p6l
  (testing "a failed :assert-db after a dispatch step: the row's link opens
            the Evidence panel on the assertion's own, non-first beat"
    (rf.epoch/clear-history!)
    (reset! rf.story.ui.evidence-spine/selection-atom {})
    (rf.story.ui.state/swap-state! assoc-in
                                   [:panel-visibility rf.story.ui.evidence-spine/panel-key] false)
    (rf/reg-event :evidence-link/set
      (fn [{:keys [db]} _] {:db (assoc db :count 2)}))
    (rf.story/reg-variant :story.evidence-link/retained
      {:script {:script [[:dispatch-sync [:evidence-link/set]]
                         [:assert-db [:count] 99]]}})
    (async done
      (-> (rf.story/run :story.evidence-link/retained)
          (rf.story.async/then
            (fn [result]
              (try
                (let [narrative (:narrative result)
                      row       (rf.story.ui.test-mode.pure/assertion-row
                                  (first (:assertions result)))
                      own-beat  (beat-for-trigger narrative [:rf.assert/path-equals [:count] 99])
                      link      (rf.story.ui.test-mode.view/row-evidence-link
                                  :story.evidence-link/retained narrative row)]
                  (is (= :fail (:status row)))
                  (is (pos-int? own-beat)
                      "the assertion's own epoch is retained, and it is not the first beat")
                  (is (some? link) "the pane renders the evidence link for the real row")
                  (when link
                    (rf.test-helpers/invoke-handler link :on-click nil)
                    (is (true? (get-in (rf.story.ui.state/get-state)
                                       [:panel-visibility rf.story.ui.evidence-spine/panel-key]))
                        "the click opens the Evidence panel")
                    (is (= own-beat (rf.story.ui.evidence-spine/selected-beat-idx
                                      :story.evidence-link/retained))
                        "the click selects the assertion's own beat")))
                (finally
                  (rf.story/destroy-variant! :story.evidence-link/retained)
                  (done)))))))))

;; ===========================================================================
;; pass / fail / skip row detail
;;
;; The :test pane's renderer derives each row's status badge from
;; `assertion-row :status`. Pinning the projection here means the
;; renderer can be a one-liner that reads :status and maps to a CSS
;; class — no badge-class logic to test on the DOM side.
;; ===========================================================================

(deftest assertion-row-pass-status
  (testing "a passing assertion record projects to :pass status with the
            full detail map populated"
    (let [rec {:assertion :rf.assert/path-equals
               :passed?   true
               :payload   [[:counter] 1]
               :expected  1
               :actual    1
               :source    {:file "story.cljs" :line 12}}
          row (rf.story.ui.test-mode.pure/assertion-row rec)]
      (is (= :rf.assert/path-equals (:assertion row)))
      (is (= :pass                  (:status row)))
      (is (string?                  (:label row)))
      (is (= (:label row)           (:row-key row))
          ":row-key mirrors :label — stable across re-runs")
      (is (= 1                      (-> row :detail :expected)))
      (is (= 1                      (-> row :detail :actual)))
      (is (= {:file "story.cljs" :line 12}
             (-> row :detail :source))
          ":detail :source carries the source coord for the open-in-editor link"))))

(deftest assertion-row-fail-status
  (testing "a failing assertion record projects to :fail status; :expected
            / :actual / :reason populate the disclosed detail panel"
    (let [rec {:assertion :rf.assert/path-equals
               :passed?   false
               :payload   [[:counter] 99]
               :expected  99
               :actual    0
               :reason    "values differ"
               :source    {:file "story.cljs" :line 24}}
          row (rf.story.ui.test-mode.pure/assertion-row rec)]
      (is (= :fail               (:status row)))
      (is (= 99                  (-> row :detail :expected)))
      (is (= 0                   (-> row :detail :actual)))
      (is (= "values differ"     (-> row :detail :reason))
          ":reason surfaces in the failure detail panel for diff explanation"))))

(deftest assertion-row-skip-status
  (testing "the :rf.assert/skipped sentinel id projects to :skip — the
            renderer uses this for the muted skip-badge variant"
    (let [rec {:assertion :rf.assert/skipped
               :passed?   false
               :reason    "feature gated"}
          row (rf.story.ui.test-mode.pure/assertion-row rec)]
      (is (= :rf.assert/skipped (:assertion row)))
      (is (= :skip              (:status row))
          ":skip overrides the :passed? false → :fail rule")
      (is (= "feature gated"    (-> row :detail :reason))))))

;; ===========================================================================
;; Re-run fills the slot
;;
;; `run-variant-pane!` is the pane's Re-run. Asserts:
;;
;;   1. A call seeds the per-variant slot with the run's result map,
;;      :ran-at-ms timestamp, :play-events copy, and trailing :epoch-ids.
;;   2. A run of a second variant seeds its own slot without disturbing
;;      the first variant's.
;; ===========================================================================

(deftest run-variant-pane-seeds-the-variants-slot
  (testing "run-variant-pane! against a fresh variant seeds the per-
            variant slot with all the renderer-required fields"
    (rf/reg-event :test/set
      (fn [{:keys [db]} _] {:db (assoc db :counter 7)}))
    (rf.story/reg-variant :story.pane.mount/v
      {:setup [[:test/set]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:counter] 7]]]})
    (async done
      (-> (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.mount/v)
          (rf.story.async/then
            (fn [_]
              (let [slot (get @rf.story.ui.test-mode.state/results-atom :story.pane.mount/v)]
                (is (map?     (:result slot))      ":result populated")
                (is (number?  (:ran-at-ms slot))   ":ran-at-ms stamped")
                (is (false?   (:running? slot))    ":running? cleared on resolve")
                (is (= #{}    (:expanded slot))    ":expanded starts empty")
                (is (vector?  (:play-events slot)) ":play-events captured")
                (is (= 1      (count (:play-events slot)))
                    "one play event → one captured entry")
                (is (vector?  (:epoch-ids slot))
                    ":epoch-ids captured (trailing slice)")
                (is (nil?     (:selected-step slot))
                    ":selected-step starts nil (no scrub)")
                (is (every? :passed? (-> slot :result :assertions))))
              (rf.story/destroy-variant! :story.pane.mount/v)
              (done)))))))

(deftest run-variant-pane-keeps-a-slot-per-variant
  (testing "running a second variant leaves the first variant's slot
            intact AND seeds its own slot independently — the slots are
            per-variant, not a singleton"
    (rf/reg-event :test/set-a
      (fn [{:keys [db]} _] {:db (assoc db :v "a")}))
    (rf/reg-event :test/set-b
      (fn [{:keys [db]} _] {:db (assoc db :v "b")}))
    (rf.story/reg-variant :story.pane.switch/a
      {:setup [[:test/set-a]] :script [[:dispatch-sync [:rf.assert/path-equals [:v] "a"]]]})
    (rf.story/reg-variant :story.pane.switch/b
      {:setup [[:test/set-b]] :script [[:dispatch-sync [:rf.assert/path-equals [:v] "b"]]]})
    (async done
      (-> (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.switch/a)
          (rf.story.async/then
            (fn [_]
              (-> (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.switch/b)
                  (rf.story.async/then
                    (fn [_]
                      (let [slots @rf.story.ui.test-mode.state/results-atom]
                        (is (= ["a" "b"]
                               (mapv #(get-in slots [% :result :app-db :v])
                                     [:story.pane.switch/a :story.pane.switch/b]))
                            "A's slot survives B's run, and each holds its own result"))
                      (rf.story/destroy-variant! :story.pane.switch/a)
                      (rf.story/destroy-variant! :story.pane.switch/b)
                      (done))))))))))

;; ===========================================================================
;; The running flag
;;
;; The per-variant slot carries :running? true while a run is in flight,
;; and the Re-run button reads it to disable itself. These rows pin the
;; flag's lifecycle on the slot: set synchronously by the call, cleared
;; when the run settles, after which another run can start.
;; ===========================================================================

(deftest run-variant-pane-marks-the-slot-running-until-it-settles
  (testing "the slot's :running? (the flag the Re-run button disables
            itself on) is true synchronously after the call and false once
            the run settles, with :ran-at-ms stamped"
    (rf/reg-event :test/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf.story/reg-variant :story.pane.debounce/v
      {:setup [[:test/inc]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]})
    (async done
      (let [p (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.debounce/v)]
        (is (true? (get-in @rf.story.ui.test-mode.state/results-atom
                           [:story.pane.debounce/v :running?])))
        (-> p
            (rf.story.async/then
              (fn [_]
                (let [slot (get @rf.story.ui.test-mode.state/results-atom :story.pane.debounce/v)]
                  (is (false? (:running? slot)))
                  (is (number? (:ran-at-ms slot))))
                (rf.story/destroy-variant! :story.pane.debounce/v)
                (done))))))))

(deftest run-variant-pane-can-run-again-once-settled
  (testing "after a run resolves, :running? clears AND a fresh re-run is
            allowed (the gate is :running?, not a permanent lock)"
    (rf/reg-event :test/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf.story/reg-variant :story.pane.cycle/v
      {:setup [[:test/inc]] :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]})
    (async done
      (-> (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.cycle/v)
          (rf.story.async/then
            (fn [_]
              (-> (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.cycle/v)
                  (rf.story.async/then
                    (fn [_]
                      (let [slot (get @rf.story.ui.test-mode.state/results-atom :story.pane.cycle/v)]
                        (is (false? (:running? slot))
                            "second run resolves cleanly; :running? clear again")
                        (is (every? :passed? (-> slot :result :assertions))
                            "second run's assertions still pass — fresh frame
                             counter starts at 0 and ticks to 1"))
                      (rf.story/destroy-variant! :story.pane.cycle/v)
                      (done))))))))))
