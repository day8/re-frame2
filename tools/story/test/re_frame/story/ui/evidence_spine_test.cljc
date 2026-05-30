(ns re-frame.story.ui.evidence-spine-test
  "JVM-portable regression net for the evidence-spine's pure projection
  (rf2-ba86n.10, spec/020 §3 + spec/021 §2).

  Covers the host-free surface — projection, evidence-strength
  classification, compact summaries, span-kind, focus-command construction,
  the graceful no-coords path, and the result-row → beat linkage:

  - `beat-evidence-strength` — direct vs attributed (spec/020 §3);
  - `beat-summary`           — compact per-slot counts, zero-count omission;
  - `span-kind` / `step-label` — non-dispatch span definition (spec/020 §3);
  - `build-focus-command`    — the §D3 focus command shape (spec/020 §2.1);
  - `focus-availability`     — the precise / graceful-no-coords decision;
  - `spine-spans`            — the full span+beat render model;
  - `row->beat-index`        — the result-row → beat linkage (spec/021 §2).

  CLJS-side (the React render, the `focus!` side effect, the selection
  ratom, the shell reachability) lives in `evidence_spine_cljs_test.cljs`;
  this corpus pins the pure projection only — no host, no Reagent, no Xray."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.evidence :as evidence]
            [re-frame.story.ui.evidence-spine :as spine]))

;; ---------------------------------------------------------------------------
;; A representative epoch tape — two dispatch steps, one non-dispatch (assert)
;; step, with explicit `:rf.story/script-idx` stamps so attribution is exact.
;; Beat 0 (step 0): a settled dispatch with db transition + an effect + a
;; schema-violation trace + sub-runs/renders → direct AND attributed.
;; Beat 1 (step 1): a second dispatch, db transition only → direct only.
;; ---------------------------------------------------------------------------

(def ^:private script
  [[:dispatch [:counter/inc]]
   [:dispatch [:counter/add 5]]
   [:assert [:rf.assert/path-equals [:count] 6]]])

(def ^:private epoch-tape
  [{:epoch-id    100
    :dispatch-id 100
    :trigger-event [:counter/inc]
    :db-before   {:count 0}
    :db-after    {:count 1}
    :effects     [{:fx-id :db :outcome :ok}]
    :sub-runs    [{:sub-id :count :cause-event-id :counter/inc}]
    :renders     [{:render-key [:counter/view :inst-1] :cause-event-id :counter/inc}]
    :trace-events [{:operation :rf.error/schema-validation-failure
                    :id 1
                    :tags {:where :event :failing-id :counter/inc}}]
    :outcome     :ok
    :rf.story/script-idx 0}
   {:epoch-id    101
    :dispatch-id 101
    :trigger-event [:counter/add 5]
    :db-before   {:count 1}
    :db-after    {:count 6}
    :effects     []
    :sub-runs    []
    :renders     []
    :trace-events []
    :outcome     :ok
    :rf.story/script-idx 1}])

;; ===========================================================================
;; evidence strength  (spec/020 §3 — direct vs attributed)
;; ===========================================================================

(deftest evidence-strength-direct-and-attributed
  (testing "a settled dispatch with db/effects/trace reads as direct; with
            sub-runs/renders reads as attributed too"
    (let [beat (first (evidence/narrative-beats
                        (evidence/narrative script epoch-tape)))]
      (is (= {:direct? true :attributed? true}
             (spine/beat-evidence-strength beat)))))
  (testing "a beat with only a db transition reads as direct, not attributed"
    (is (= {:direct? true :attributed? false}
           (spine/beat-evidence-strength {:db-before {} :db-after {:a 1}}))))
  (testing "a beat with only sub-runs reads as attributed, not direct"
    (is (= {:direct? false :attributed? true}
           (spine/beat-evidence-strength {:sub-runs [{:sub-id :x}]}))))
  (testing "an empty beat reads as neither"
    (is (= {:direct? false :attributed? false}
           (spine/beat-evidence-strength {})))))

;; ===========================================================================
;; compact summary  (spec/020 §3)
;; ===========================================================================

(deftest beat-summary-counts-and-omits-zeros
  (testing "the summary carries db/effects/schemas/trace/sub-runs/renders
            counts and OMITS zero-count slots"
    (let [beat {:db-before {} :db-after {:a 1}
                :effects [{:fx-id :db}]
                :trace-events [{:operation :rf.error/schema-validation-failure}
                               {:operation :rf.fx/run}]
                :sub-runs [{:sub-id :x}]
                :renders []}
          summary (spine/beat-summary beat)
          by-k    (into {} (map (juxt :k :count)) summary)]
      (is (= 1 (:db by-k)))
      (is (= 1 (:effects by-k)))
      (is (= 1 (:schemas by-k)) "one schema-violation trace counted")
      (is (= 2 (:trace by-k)))
      (is (= 1 (:sub-runs by-k)))
      (is (nil? (:renders by-k)) "zero-count renders slot omitted")))
  (testing "attributed slots carry :attributed strength; direct slots :direct"
    (let [summary (spine/beat-summary {:db-after {:a 1} :sub-runs [{:sub-id :x}]})
          by-k    (into {} (map (juxt :k :strength)) summary)]
      (is (= :direct (:db by-k)))
      (is (= :attributed (:sub-runs by-k))))))

;; ===========================================================================
;; span-kind + step-label  (spec/020 §3 — define non-dispatch spans)
;; ===========================================================================

(deftest span-kind-distinguishes-dispatch-non-dispatch-setup
  (testing "a dispatch step span is :dispatch"
    (is (= :dispatch (spine/span-kind {:step [:dispatch [:e]] :epochs []}))))
  (testing "a non-dispatch step span (assert / wait) is :non-dispatch"
    (is (= :non-dispatch (spine/span-kind {:step [:assert [:rf.assert/path-equals [:c] 1]] :epochs []})))
    (is (= :non-dispatch (spine/span-kind {:step [:wait-until [:fn]] :epochs []}))))
  (testing "the leading nil-step span is :setup"
    (is (= :setup (spine/span-kind {:step nil :epochs []})))))

(deftest step-label-renders-compact-labels
  (testing "a dispatch step shows its event-id"
    (is (= ":counter/inc" (spine/step-label [:dispatch [:counter/inc]]))))
  (testing "a non-dispatch step shows its head tag"
    (is (= ":assert" (spine/step-label [:assert [:rf.assert/path-equals [:c] 1]])))
    (is (= ":wait-until" (spine/step-label [:wait-until [:fn]]))))
  (testing "the nil-step span reads 'setup'"
    (is (= "setup" (spine/step-label nil)))))

;; ===========================================================================
;; focus-command construction  (spec/020 §2.1)
;; ===========================================================================

(deftest build-focus-command-shape
  (testing "a beat with an epoch-id pins the epoch + carries opaque source"
    (let [src (spine/focus-source :story/evidence-beat :story.cp/basic {:beat-idx 0 :span-idx 1})
          cmd (spine/build-focus-command :app-db {:epoch-id 42 :dispatch-id 7} src [:checkout :state])]
      (is (= :app-db (:panel cmd)))
      (is (= 42 (:epoch-id cmd)))
      (is (= 7 (:dispatch-id cmd)))
      (is (= [:checkout :state] (:path cmd)))
      (is (= :story/evidence-beat (:kind (:source cmd))))
      (is (= :story.cp/basic (:variant/id (:source cmd))))))
  (testing "an empty-coords command is a well-formed panel-only focus"
    (let [cmd (spine/build-focus-command :trace {} {:kind :x})]
      (is (= :trace (:panel cmd)))
      (is (not (contains? cmd :epoch-id)))
      (is (not (contains? cmd :dispatch-id)))
      (is (not (contains? cmd :path)))))
  (testing "an unknown panel falls back to the default rather than landing
            the unknown-tab stub"
    (is (= spine/default-focus-panel
           (:panel (spine/build-focus-command :app-bd {} {:kind :x}))))
    (is (contains? spine/focus-panels (:panel (spine/build-focus-command :app-bd {} {:kind :x}))))))

(deftest focus-source-threads-only-present-coords
  (testing "focus-source carries kind + variant always; the rest cond->"
    (is (= {:kind :story/assertion :variant/id :v}
           (spine/focus-source :story/assertion :v {})))
    (is (= {:kind :story/assertion :variant/id :v :assertion/id :rf.assert/path-equals}
           (spine/focus-source :story/assertion :v {:assertion-id :rf.assert/path-equals})))))

;; ===========================================================================
;; focus availability  (spec/020 §3 — graceful no-coords path)
;; ===========================================================================

(deftest focus-availability-precise-vs-graceful
  (testing "a beat with an epoch-id focuses precisely"
    (is (:precise? (spine/focus-availability {:epoch-id 42}))))
  (testing "a beat with a dispatch-id focuses precisely"
    (is (:precise? (spine/focus-availability {:dispatch-id 7}))))
  (testing "a beat with no coordinates is NOT precise but carries a reason
            (the graceful no-coords path — still opens the panel)"
    (let [{:keys [precise? reason]} (spine/focus-availability {})]
      (is (false? precise?))
      (is (string? reason))
      (is (re-find #"no epoch" reason)))))

;; ===========================================================================
;; spine-spans  (spec/020 §3 — the full render model)
;; ===========================================================================

(deftest spine-spans-projects-spans-and-decorated-beats
  (let [narrative (evidence/narrative script epoch-tape)
        spans     (spine/spine-spans narrative)]
    (testing "one span per script step, in order"
      (is (= 3 (count spans)))
      (is (= [":counter/inc" ":counter/add" ":assert"] (mapv :label spans)))
      (is (= [:dispatch :dispatch :non-dispatch] (mapv :kind spans))))
    (testing "the first dispatch span carries its decorated beat"
      (let [beat (first (:beats (first spans)))]
        (is (= 100 (:epoch-id beat)))
        (is (= {:epoch-id 100 :dispatch-id 100} (:coords beat)))
        (is (:precise? (:focus beat)))
        (is (= {:direct? true :attributed? true} (:strength beat)))
        (is (seq (:summary beat)))))
    (testing "the non-dispatch assert span committed no epoch"
      (let [assert-span (nth spans 2)]
        (is (= :non-dispatch (:kind assert-span)))
        (is (= 0 (:beat-count assert-span)))
        (is (empty? (:beats assert-span)))))))

(deftest spine-spans-empty-narrative
  (testing "an empty / nil narrative projects to no spans (graceful)"
    (is (= [] (spine/spine-spans nil)))
    (is (= [] (spine/spine-spans [])))))

;; ===========================================================================
;; result-row → beat linkage  (spec/021 §2)
;; ===========================================================================

(deftest row-to-beat-index-linkage
  (let [narrative (evidence/narrative script epoch-tape)]
    (testing "a schema violation keyed on :epoch-id resolves to its beat"
      (is (= 0 (spine/row->beat-index narrative {:epoch-id 100 :selector [:event :counter/inc]})))
      (is (= 1 (spine/row->beat-index narrative {:epoch-id 101}))))
    (testing "an assertion record keyed on :dispatch-id resolves to the
              cascade's first beat"
      (is (= 0 (spine/row->beat-index narrative {:dispatch-id 100 :assertion :rf.assert/path-equals})))
      (is (= 1 (spine/row->beat-index narrative {:dispatch-id 101}))))
    (testing "a row with no resolvable coordinate yields nil (graceful)"
      (is (nil? (spine/row->beat-index narrative {:assertion :rf.assert/no-warnings})))
      (is (nil? (spine/row->beat-index narrative {:epoch-id 999}))))))

;; ===========================================================================
;; the focus-panel vocabulary matches the host-facing API
;; ===========================================================================

(deftest focus-panels-mirror-host-facing-vocabulary
  (testing "the spine's focus-panel set matches the Xray focus API's
            valid-panels (uses :routes / :views, NOT the embed's :routing)"
    (is (= #{:epoch :app-db :views :trace :machines :routes :issues}
           spine/focus-panels))))
