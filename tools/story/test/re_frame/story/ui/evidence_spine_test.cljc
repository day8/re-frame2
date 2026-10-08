(ns re-frame.story.ui.evidence-spine-test
  "JVM coverage of the evidence-spine's pure projection (spec/020 §3 +
  spec/021 §2): evidence strength, compact summaries, span kinds and labels,
  focus-command construction, the graceful no-coords path, the span+beat
  render model and the result-row → beat linkage. The React render, the
  `focus!` side effect and the selection ratom are covered by
  `evidence_spine_cljs_test.cljs`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.evidence :as rf.story.play.evidence]
            [re-frame.story.ui.evidence-spine :as rf.story.ui.evidence-spine]))

;; Two dispatch steps and one non-dispatch (assert) step. Beat 0 carries a
;; db transition, an effect, a schema-violation trace and sub-runs/renders
;; (direct AND attributed); beat 1 a db transition only.

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
  (testing "a beat with only a db transition reads as direct, not attributed"
    (is (= {:direct? true :attributed? false}
           (rf.story.ui.evidence-spine/beat-evidence-strength {:db-before {} :db-after {:a 1}}))))
  (testing "a beat with only sub-runs reads as attributed, not direct"
    (is (= {:direct? false :attributed? true}
           (rf.story.ui.evidence-spine/beat-evidence-strength {:sub-runs [{:sub-id :x}]}))))
  (testing "an empty beat reads as neither"
    (is (= {:direct? false :attributed? false}
           (rf.story.ui.evidence-spine/beat-evidence-strength {})))))

;; The db-evidence marker tests for an ACTUAL transition, not key presence:
;; `epoch-beat` always materializes `:db-before` / `:db-after`, so a
;; key-presence test would read every projected beat — including a read-only
;; dispatch — as a db change. These cases drive a real `epoch-beat` beat.

(def ^:private no-db-change-record
  {:epoch-id    200
   :dispatch-id 200
   :trigger-event [:counter/noop]
   :db-before   {:count 6}
   :db-after    {:count 6}
   :effects     []
   :sub-runs    []
   :renders     []
   :trace-events []
   :outcome     :ok})

(deftest db-evidence-tests-transition-not-key-presence
  (testing "a real epoch-beat with equal db-before/db-after carries the keys
            but is NOT direct db-evidence and emits NO `db Δ` chip"
    (let [beat (rf.story.play.evidence/epoch-beat no-db-change-record)]
      (is (contains? beat :db-before) "the keys are present, so a contains? test would mis-fire")
      (is (contains? beat :db-after))
      (is (= (:db-before beat) (:db-after beat)) "no transition occurred")
      (is (= {:direct? false :attributed? false}
             (rf.story.ui.evidence-spine/beat-evidence-strength beat)))
      (is (= [] (rf.story.ui.evidence-spine/beat-summary beat)))))
  (testing "a real epoch-beat WITH a db transition is direct db-evidence and
            emits the `db Δ` chip"
    (let [beat (rf.story.play.evidence/epoch-beat
                 (assoc no-db-change-record :db-after {:count 7}))]
      (is (= {:direct? true :attributed? false}
             (rf.story.ui.evidence-spine/beat-evidence-strength beat)))
      (is (= [[:db 1]]
             (mapv (juxt :k :count) (rf.story.ui.evidence-spine/beat-summary beat)))))))

;; ===========================================================================
;; compact summary  (spec/020 §3)
;; ===========================================================================

(deftest beat-summary-counts-and-omits-zeros
  (testing "per-slot counts and strengths in the spec's order, zero-count slots omitted"
    (is (= [[:db 1 :direct]
            [:effects 1 :direct]
            [:schemas 1 :direct]
            [:trace 2 :direct]
            [:sub-runs 1 :attributed]]
           (mapv (juxt :k :count :strength)
                 (rf.story.ui.evidence-spine/beat-summary
                   {:db-before {} :db-after {:a 1}
                    :effects [{:fx-id :db}]
                    :trace-events [{:operation :rf.error/schema-validation-failure}
                                   {:operation :rf.fx/run}]
                    :sub-runs [{:sub-id :x}]
                    :renders []}))))))

;; ===========================================================================
;; step-label
;; ===========================================================================

(deftest step-label-renders-compact-labels
  (testing "the nil-step span reads 'setup'"
    (is (= "setup" (rf.story.ui.evidence-spine/step-label nil)))))

;; ===========================================================================
;; focus-command construction  (spec/020 §2.1)
;; ===========================================================================

(deftest build-focus-command-shape
  (testing "a beat's coords pin the epoch + dispatch; the source rides opaque"
    (is (= {:panel       :app-db
            :epoch-id    42
            :dispatch-id 7
            :source      {:kind :story/evidence-beat :variant/id :story.cp/basic
                          :beat-idx 0 :span-idx 1}}
           (rf.story.ui.evidence-spine/build-focus-command
             :app-db
             {:epoch-id 42 :dispatch-id 7}
             (rf.story.ui.evidence-spine/focus-source
               :story/evidence-beat :story.cp/basic {:beat-idx 0 :span-idx 1})))))
  (testing "an empty-coords command is a well-formed panel-only focus"
    (is (= {:panel :trace :source {:kind :x}}
           (rf.story.ui.evidence-spine/build-focus-command :trace {} {:kind :x}))))
  (testing "an unknown panel falls back to the default rather than landing
            the unknown-tab stub"
    (is (= rf.story.ui.evidence-spine/default-focus-panel
           (:panel (rf.story.ui.evidence-spine/build-focus-command :app-bd {} {:kind :x}))))))

(deftest embed-panel-for-names-the-chip-that-shows-the-focus
  (testing "a focus panel maps to the same embed chip id, except routes (:routes → :routing)"
    (is (= :app-db (rf.story.ui.evidence-spine/embed-panel-for :app-db)))
    (is (= :routing (rf.story.ui.evidence-spine/embed-panel-for :routes)))))

(deftest submit-beat-focus-command-carries-its-coordinates
  (testing "a script of assertions files every setup epoch in the leading
            span; the 'Xray: Epoch' command for its first beat (the submit)
            must pin THAT beat's epoch AND dispatch"
    (let [submit [:login/flow [:login/submit {:email "ada@example.com" :password "wrong"}]]
          tape   [{:epoch-id 24 :dispatch-id 51 :trigger-event submit
                   :db-before {} :db-after {} :effects [{:fx-id :rf.http/managed}]
                   :sub-runs [] :renders [] :trace-events []}
                  {:epoch-id 31 :dispatch-id 58
                   :trigger-event [:rf.assert/sub-equals [:login/email] "ada@example.com"]
                   :db-before {} :db-after {} :effects [] :sub-runs [] :renders [] :trace-events []}]
          script [[:assert [:rf.assert/state-is :login/flow :authenticated]]
                  [:assert [:rf.assert/sub-equals [:login/email] "ada@example.com"]]]
          beat   (-> (rf.story.play.evidence/narrative script tape)
                     rf.story.ui.evidence-spine/spine-spans
                     first :beats first)]
      (is (= {:panel :epoch :epoch-id 24 :dispatch-id 51 :source {}}
             (rf.story.ui.evidence-spine/build-focus-command :epoch (:coords beat) {}))))))

;; ===========================================================================
;; focus availability  (spec/020 §3 — graceful no-coords path)
;; ===========================================================================

(deftest focus-availability-precise-vs-graceful
  (testing "a beat with an epoch-id focuses precisely"
    (is (:precise? (rf.story.ui.evidence-spine/focus-availability {:epoch-id 42}))))
  (testing "a beat with a dispatch-id focuses precisely"
    (is (:precise? (rf.story.ui.evidence-spine/focus-availability {:dispatch-id 7}))))
  (testing "a beat with no coordinates is NOT precise but says why"
    (let [{:keys [precise? reason]} (rf.story.ui.evidence-spine/focus-availability {})]
      (is (false? precise?))
      (is (re-find #"no epoch" reason)))))

;; ===========================================================================
;; spine-spans  (spec/020 §3 — the full render model)
;; ===========================================================================

(deftest spine-spans-projects-spans-and-decorated-beats
  (let [spans (rf.story.ui.evidence-spine/spine-spans
                (rf.story.play.evidence/narrative script epoch-tape))]
    (testing "one span per script step, in order"
      (is (= [":counter/inc" ":counter/add" ":assert"] (mapv :label spans)))
      (is (= [:dispatch :dispatch :non-dispatch] (mapv :kind spans))))
    (testing "the first dispatch span carries its decorated beat"
      (let [beat (first (:beats (first spans)))]
        (is (= {:epoch-id 100 :dispatch-id 100} (:coords beat)))
        (is (:precise? (:focus beat)))
        (is (= {:direct? true :attributed? true} (:strength beat)))
        (is (seq (:summary beat)))))
    (testing "the non-dispatch assert span has no beats of its own"
      (is (= [0 []] ((juxt :beat-count :beats) (nth spans 2)))))))

(deftest a-checkpoint-epoch-is-filed-above-and-its-span-says-so
  (testing "an [:assert …] checkpoint dispatches its verdict, and the
            narrative files that epoch under the span above the assert —
            here the setup span, since no dispatch step precedes it. The
            assert span's note must say so, not that the step committed
            no epoch."
    (let [narrative (rf.story.play.evidence/narrative
                      [[:assert [:rf.assert/state-is :login/flow :idle]]]
                      [{:epoch-id 19 :dispatch-id 19 :trigger-event [:login/failure]
                        :db-before {} :db-after {:flow :error}}
                       {:epoch-id 22 :dispatch-id 22
                        :trigger-event [:rf.assert/state-is :login/flow :idle]
                        :db-before {:flow :error} :db-after {:flow :error}}])
          [setup-span assert-span :as spans] (rf.story.ui.evidence-spine/spine-spans narrative)]
      (is (= [:setup :non-dispatch] (mapv :kind spans)))
      (is (= [:login/failure :rf.assert/state-is]
             (mapv (comp first :trigger-event) (:beats setup-span)))
          "the checkpoint's verdict epoch sits under the setup span")
      (is (empty? (:beats assert-span)) "the assert span has no beats of its own")
      (let [note (rf.story.ui.evidence-spine/empty-span-note (:kind assert-span))]
        (is (not (re-find #"committed no epoch" note))
            "the note does not deny the epoch the checkpoint committed")
        (is (re-find #"filed under the span above" note)
            "the note says where that epoch went")))))

;; ===========================================================================
;; result-row → beat linkage  (spec/021 §2)
;; ===========================================================================

(deftest row-to-beat-index-linkage
  (let [narrative (rf.story.play.evidence/narrative script epoch-tape)]
    (testing "a schema violation keyed on :epoch-id resolves to its beat"
      (is (= 1 (rf.story.ui.evidence-spine/row->beat-index narrative {:epoch-id 101}))))
    (testing "an assertion record keyed on :dispatch-id resolves to the cascade's beat"
      (is (= 1 (rf.story.ui.evidence-spine/row->beat-index narrative {:dispatch-id 101}))))
    (testing "a row with no resolvable coordinate yields nil (graceful)"
      (is (nil? (rf.story.ui.evidence-spine/row->beat-index narrative {:assertion :rf.assert/no-warnings}))))))
