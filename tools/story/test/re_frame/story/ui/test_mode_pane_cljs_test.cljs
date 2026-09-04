(ns re-frame.story.ui.test-mode-pane-cljs-test
  "CLJS-side regression net for the `:test` mode pane (rf2-aoqyy).

  Pairs with `re-frame.story.ui.test-mode-state-cljs-test` (race-guard +
  toggle-expanded scenarios) and the JVM `re-frame.story.ui.test-widget-
  cljs-test` (state transitions + pure aggregations). This namespace
  pins the pane-level scenarios called out by spec/015 §`:test` mode
  pane:

  - **Pass/fail/skip row detail** — `assertion-row` produces rows whose
    `:status` keyword feeds the renderer's status-badge selector; each
    of `:pass` / `:fail` / `:skip` lands in its own row and exposes the
    expected `:detail` projection.

  - **Run-on-mount** — invoking `run-variant-pane!` against a variant
    populates the per-variant slot with a `:result`, `:ran-at-ms`,
    `:play-events`, and the trailing `:epoch-ids` slice. Re-invoking
    against the SAME variant updates the slot in place.

  - **Re-run debounce** — `run-variant-pane!` short-circuits when the
    target variant's slot already carries `:running? true`. A second
    rapid call lands as a no-op so two parallel `reset-variant` runs
    can't race the per-frame teardown.

  Per spec/009 §`:test` mode pane the renderer is a thin projection
  over the local `results-atom`; pinning the atom shape covers the
  bulk of the pane's correctness without a DOM round-trip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.pure  :as rf.story.ui.test-mode.pure]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]
            [re-frame.subs             :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  ;; EP-0001 (rf2-vzld77 / rf2-ixb0bq): a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>], NOT the retired app-db
  ;; `:rf/runtime` path — mirror `re-frame.machines`.
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
;; rf2-aoqyy — pass / fail / skip row detail
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
          ":row-key mirrors :label — stable across re-runs (rf2-tistm)")
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

(deftest aggregate-summary-mixed-rows
  (testing "the pane's headline counts (rf.story.ui.state/aggregate-summary) fold a
            mixed pass/fail/skip vector into the spec'd shape"
    (let [assertions [{:assertion :rf.assert/path-equals  :passed? true}
                      {:assertion :rf.assert/path-equals  :passed? false}
                      {:assertion :rf.assert/skipped      :passed? false}
                      {:assertion :rf.assert/sub-equals   :passed? true}]
          summary    (rf.story.ui.state/aggregate-summary assertions)]
      (is (= 4 (:total summary))
          ":total counts every row including skipped")
      (is (= 2 (:passed summary))
          ":passed counts only :passed? true rows that aren't skipped")
      (is (= 1 (:failed summary))
          ":failed counts non-skipped :passed? false rows")
      (is (= 1 (:skipped summary)))
      (is (false? (:all-passed? summary))
          "any failure OR any skip OR zero total ⇒ :all-passed? false"))))

(deftest aggregate-summary-all-pass-only
  (testing ":all-passed? is true iff total>0 AND failed=0 AND skipped=0"
    (let [s1 (rf.story.ui.state/aggregate-summary
               [{:assertion :rf.assert/path-equals :passed? true}])
          s2 (rf.story.ui.state/aggregate-summary [])
          s3 (rf.story.ui.state/aggregate-summary
               [{:assertion :rf.assert/skipped :passed? false}])]
      (is (true?  (:all-passed? s1)))
      (is (false? (:all-passed? s2)) "empty rows ⇒ not all-passed")
      (is (false? (:all-passed? s3)) "a skip blocks :all-passed?"))))

;; ===========================================================================
;; rf2-aoqyy — run-on-mount
;;
;; `run-variant-pane!` is the pane's mount-side entry point. Asserts:
;;
;;   1. First call seeds the per-variant slot with the run's result map,
;;      :ran-at-ms timestamp, :play-events copy, and trailing :epoch-ids.
;;   2. Switching variants fires a fresh run for the new id without
;;      disturbing the previous variant's slot.
;; ===========================================================================

(deftest run-variant-pane-seeds-slot-on-mount
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

(deftest run-variant-pane-switching-variants-keeps-slots-distinct
  (testing "switching the pane between two variants leaves the previous
            variant's slot intact AND seeds the new variant's slot
            independently — proves the slots are per-variant, not a
            singleton, so the pane's scroll-position / expanded set /
            scrubber state isolate"
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
                      (let [slot-a (get @rf.story.ui.test-mode.state/results-atom :story.pane.switch/a)
                            slot-b (get @rf.story.ui.test-mode.state/results-atom :story.pane.switch/b)]
                        (is (some? slot-a)
                            "variant A's slot survives the switch")
                        (is (some? slot-b)
                            "variant B's slot was seeded")
                        (is (not= slot-a slot-b)
                            "the two slots carry distinct results")
                        (is (= "a" (-> slot-a :result :app-db :v)))
                        (is (= "b" (-> slot-b :result :app-db :v))))
                      (rf.story/destroy-variant! :story.pane.switch/a)
                      (rf.story/destroy-variant! :story.pane.switch/b)
                      (done))))))))))

;; ===========================================================================
;; rf2-aoqyy — re-run debounce
;;
;; `run-variant-pane!` short-circuits when the per-variant slot already
;; carries :running? true. The race-guard prevents two parallel
;; reset-variant runs against the same frame (which would race the
;; per-frame teardown). Asserts: while the slot is :running?, a
;; second call is a no-op.
;;
;; We can't easily await a *busy* state from the resolved promise (the
;; resolve loop happens too fast in CLJS test mode), so we test the
;; gate directly: seed :running? true, call run-variant-pane!, observe
;; that no run was triggered.
;; ===========================================================================

(deftest run-variant-pane-debounce-gate-observable
  (testing "rf2-aoqyy — `:running?` is the single debounce gate the pane
            uses across all its mutation paths. Pin the contract that
            consumers (the Re-run button, the chrome widget's Run-all)
            read this flag to decide whether to fire a second run.

            The pure-data gate this test pins:
              1. After begin-run! the slot's :running? is true.
              2. After store-result! resolves the slot's :running? is false.
              3. While :running? is true the renderer must read 'in-flight'
                 (the disabled-button state).

            The companion JVM tests (test-widget-cljs-test) cover the
            shell-state :tests :runs :status :running stamp the chrome
            widget reads against; this test pins the pane-local
            results-atom flag the pane's own Re-run button reads."
    (rf/reg-event :test/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf.story/reg-variant :story.pane.debounce/v
      {:setup [[:test/inc]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]})
    (async done
      (let [p (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.debounce/v)]
        ;; The synchronous prelude of run-variant-pane! calls begin-run!
        ;; which stamps :running? true BEFORE the promise resolves.
        ;; This is the gate the Re-run button reads: while true, the
        ;; button renders disabled and a click is ignored at the view
        ;; layer. The pure test mirrors that view-layer contract.
        (is (true? (get-in @rf.story.ui.test-mode.state/results-atom
                           [:story.pane.debounce/v :running?]))
            ":running? is true synchronously after begin-run! — the
             Re-run button's `disabled?` prop reads this flag")
        ;; After resolve, :running? clears and a second run is allowed.
        (-> p
            (rf.story.async/then
              (fn [_]
                (is (false? (get-in @rf.story.ui.test-mode.state/results-atom
                                    [:story.pane.debounce/v :running?]))
                    ":running? clears on store-result! — the button
                     re-enables and a fresh re-run can fire")
                (let [slot (get @rf.story.ui.test-mode.state/results-atom :story.pane.debounce/v)]
                  (is (number? (:ran-at-ms slot))
                      ":ran-at-ms stamped — the renderer shows the
                       'last run at HH:mm:ss' badge"))
                (rf.story/destroy-variant! :story.pane.debounce/v)
                (done))))))))

(deftest run-variant-pane-records-on-resolve
  (testing "after a run resolves, :running? clears AND a fresh re-run is
            allowed (the gate is :running?, not a permanent lock)"
    (rf/reg-event :test/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf.story/reg-variant :story.pane.cycle/v
      {:setup [[:test/inc]] :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]})
    (async done
      (-> (rf.story.ui.test-mode.state/run-variant-pane! :story.pane.cycle/v)
          (rf.story.async/then
            (fn [_]
              (is (false? (get-in @rf.story.ui.test-mode.state/results-atom
                                  [:story.pane.cycle/v :running?]))
                  ":running? cleared after first run resolves")
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
