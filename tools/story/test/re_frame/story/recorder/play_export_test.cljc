(ns re-frame.story.recorder.play-export-test
  "Pure unit tests for the recorder → :script translator
  (rf2-x9zsr).

  Covers:

  - Per-event translation (`event->step`) — assertion events ride the
    `:dispatch-sync` rail; everything else rides `:dispatch`;
    redacted placeholders drop out.
  - Recording-level translation (`recording->script-body`) — empty
    input, name + auto-run? slots, auto-assert with and without a
    seed db, max-auto-assertions cap.
  - Snippet rendering (`render-script-body` / `render-variant-form`)
    — round-trip cleanly through `rf.story.play.runner/parse-spec`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [re-frame.story.play.runner             :as rf.story.play.runner]
            [re-frame.story.recorder.play-export    :as rf.story.recorder.play-export]
            [re-frame.story.recorder.selector       :as rf.story.recorder.selector]))

;; ---- per-event translation -----------------------------------------------

(deftest event-step-dispatch
  (testing "ordinary events become :dispatch steps"
    (is (= [:dispatch [:counter/inc]]
           (rf.story.recorder.play-export/event->step [:counter/inc])))
    (is (= [:dispatch [:auth/login {:user "x"}]]
           (rf.story.recorder.play-export/event->step [:auth/login {:user "x"}])))))

(deftest event-step-assertion-rides-dispatch-sync
  (testing "assertion events (`:rf.assert/*`) translate to :dispatch-sync"
    (is (= [:dispatch-sync [:rf.assert/path-equals [:n] 3]]
           (rf.story.recorder.play-export/event->step [:rf.assert/path-equals [:n] 3])))
    (is (= [:dispatch-sync [:rf.assert/no-warnings]]
           (rf.story.recorder.play-export/event->step [:rf.assert/no-warnings])))
    (is (= [:dispatch-sync [:rf.assert/sub-equals [:counter] 5]]
           (rf.story.recorder.play-export/event->step [:rf.assert/sub-equals [:counter] 5])))))

(deftest event-step-redacted-drops
  (testing "the [:rf/redacted] placeholder (recorder's canonical 1-tuple) drops out"
    (is (nil? (rf.story.recorder.play-export/event->step [:rf/redacted])))))

(deftest event-step-malformed-yields-nil
  (testing "malformed inputs return nil"
    (is (nil? (rf.story.recorder.play-export/event->step nil)))
    (is (nil? (rf.story.recorder.play-export/event->step [])))
    (is (nil? (rf.story.recorder.play-export/event->step ["not-keyword"])))
    (is (nil? (rf.story.recorder.play-export/event->step "not-a-vector")))))

;; ---- rf2-l2cn5d (EP-0017): captured cofx rides the dispatch step ----------

(deftest event-step-carries-captured-cofx
  (testing "a captured flat :rf.cofx map rides onto the step as a 3rd opts map"
    (is (= [:dispatch [:counter/inc] {:rf.cofx {:rf/time-ms 123 :counter/delta 4}}]
           (rf.story.recorder.play-export/event->step [:counter/inc] {:rf/time-ms 123 :counter/delta 4}))
        "an ordinary event with cofx → [:dispatch evec {:rf.cofx …}]")
    (is (= [:dispatch-sync [:rf.assert/path-equals [:n] 3] {:rf.cofx {:rf/time-ms 9}}]
           (rf.story.recorder.play-export/event->step [:rf.assert/path-equals [:n] 3] {:rf/time-ms 9}))
        "an assertion event with cofx still rides the :dispatch-sync rail"))
  (testing "nil / empty cofx emits the byte-identical 2-element step"
    (is (= [:dispatch [:counter/inc]]
           (rf.story.recorder.play-export/event->step [:counter/inc] nil)))
    (is (= [:dispatch [:counter/inc]]
           (rf.story.recorder.play-export/event->step [:counter/inc] {}))
        "an empty cofx map is treated as absent — no opts slot")
    (is (= [:dispatch [:counter/inc]]
           (rf.story.recorder.play-export/event->step [:counter/inc]))
        "the 1-arity is the bare-step path")))

(deftest recording-threads-parallel-cofx-vector
  (testing "a parallel :cofx vector (index-aligned with bare events) rides onto
            the matching dispatch steps; :rf/time-ms is preserved verbatim"
    (let [events [[:counter/inc] [:auth/login {:user "x"}] [:counter/dec]]
          cofx   [{:rf/time-ms 100 :counter/delta 4}
                  nil
                  {:rf/time-ms 300}]
          spec   (rf.story.recorder.play-export/recording->script-body events {:cofx cofx})]
      (is (= [[:dispatch [:counter/inc] {:rf.cofx {:rf/time-ms 100 :counter/delta 4}}]
              [:dispatch [:auth/login {:user "x"}]]
              [:dispatch [:counter/dec] {:rf.cofx {:rf/time-ms 300}}]]
             (:script spec))
          "each non-nil cofx member rides its step; nil members emit bare steps"))))

(deftest recording-without-cofx-is-byte-identical
  (testing "no :cofx opt → the pre-EP-0017 2-element steps (zero ceremony)"
    (let [events [[:counter/inc] [:counter/dec]]]
      (is (= (rf.story.recorder.play-export/recording->script-body events {})
             (rf.story.recorder.play-export/recording->script-body events {:cofx []}))
          "an empty parallel cofx vector is byte-identical to no :cofx opt")
      (is (= [[:dispatch [:counter/inc]] [:dispatch [:counter/dec]]]
             (:script (rf.story.recorder.play-export/recording->script-body events {:cofx [nil nil]})))
          "all-nil cofx members emit bare steps"))))

;; ---- recording-level translation -----------------------------------------

(deftest simple-recording-three-dispatches
  (testing "a three-event recording yields a three-step script"
    (let [events [[:counter/inc] [:counter/inc] [:counter/dec]]
          spec   (rf.story.recorder.play-export/recording->script-body events {})]
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script spec))
          "every event lifts to a :dispatch step in order")
      (is (true? (:auto-run? spec))
          ":auto-run? defaults true (matches runner default)")
      (is (not (contains? spec :name))
          ":name omitted when not supplied"))))

(deftest empty-recording-yields-empty-script
  (testing "an empty recording yields a legal empty :script"
    (let [spec (rf.story.recorder.play-export/recording->script-body [])]
      (is (= [] (:script spec)))
      (is (true? (:auto-run? spec))))))

(deftest name-and-auto-run-honoured
  (testing "the :name and :auto-run? opts flow through to the spec"
    (let [spec (rf.story.recorder.play-export/recording->script-body
                 [[:counter/inc]]
                 {:name "happy path" :auto-run? false})]
      (is (= "happy path" (:name spec)))
      (is (false? (:auto-run? spec))))))

(deftest blank-name-omitted
  (testing ":name is omitted when blank or non-string"
    (is (not (contains? (rf.story.recorder.play-export/recording->script-body [] {:name ""})
                        :name)))
    (is (not (contains? (rf.story.recorder.play-export/recording->script-body [] {:name nil})
                        :name)))))

(deftest mixed-events-translate-with-tags
  (testing "ordinary + assertion + redacted events translate together"
    (let [events [[:counter/inc]
                  [:rf.assert/path-equals [:n] 1]
                  [:rf/redacted]
                  [:counter/dec]]
          spec   (rf.story.recorder.play-export/recording->script-body events {})]
      (is (= [[:dispatch       [:counter/inc]]
              [:dispatch-sync  [:rf.assert/path-equals [:n] 1]]
              [:dispatch       [:counter/dec]]]
             (:script spec))
          "assertion → :dispatch-sync; ordinary → :dispatch; redacted drops"))))

;; ---- auto-assert ---------------------------------------------------------

(deftest auto-assert-from-final-db-no-seed
  (testing "auto-assert with no seed emits one assert-db per top-level key"
    (let [spec (rf.story.recorder.play-export/recording->script-body
                 [[:counter/inc]]
                 {:auto-assert? true
                  :final-db {:n 1 :who "alice"}})
          asserts (filter #(= :assert-db (first %)) (:script spec))]
      (is (= 2 (count asserts))
          "two top-level keys → two trailing assertions")
      (is (= [:dispatch [:counter/inc]] (first (:script spec)))
          "dispatches come first; asserts trail")
      (is (every? (fn [a] (and (= :assert-db (first a))
                               (vector? (nth a 1)))) asserts)))))

(deftest auto-assert-from-seed-diff
  (testing "auto-assert with seed emits one :assert-db per CHANGED top-level key"
    (let [seed   {:n 0 :who "alice"}
          final  {:n 1 :who "alice" :extra :added}
          spec   (rf.story.recorder.play-export/recording->script-body
                   [[:counter/inc]]
                   {:auto-assert? true
                    :seed-db      seed
                    :final-db     final})
          asserts (filterv #(= :assert-db (first %)) (:script spec))]
      (is (= #{[:assert-db [:n] 1]
               [:assert-db [:extra] :added]}
             (set asserts))
          ":who is unchanged → no assertion; :n changed + :extra new → two assertions"))))

(deftest auto-assert-never-asserts-story-bookkeeping
  (testing "rf2-3x7nj.29.2: Story's own run bookkeeping (:rf.story/* keys) is
            never app behaviour, so neither branch asserts on it"
    (let [record {:assertion :rf.assert/path-equals :passed? true :dispatch-id 5}
          seed   {:rf.story/lifecycle :loading :rf.story/assertions [] :n 0}
          final  {:rf.story/lifecycle :ready :rf.story/assertions [record] :n 1}]
      (is (= [[:assert-db [:n] 1]]
             (rf.story.recorder.play-export/auto-assert-steps final {:seed-db seed}))
          "seeded: the changed :rf.story/* keys are not asserted, the changed :n is")
      (is (= [[:assert-db [:n] 1]]
             (rf.story.recorder.play-export/auto-assert-steps final {}))
          "no seed: the :rf.story/* keys are not among the asserted top-level keys"))))

(deftest auto-assert-cap-respected
  (testing "the max-auto-assertions cap limits the trailing block"
    (let [final-db (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 20))
          spec     (rf.story.recorder.play-export/recording->script-body
                     []
                     {:auto-assert?        true
                      :final-db            final-db
                      :max-auto-assertions 3})
          asserts  (filter #(= :assert-db (first %)) (:script spec))]
      (is (= 3 (count asserts))
          "20-key db capped to 3 :assert-db steps"))))

(deftest auto-assert-off-default
  (testing "without :auto-assert? true, no assertions trail"
    (let [spec (rf.story.recorder.play-export/recording->script-body
                 [[:counter/inc]]
                 {:final-db {:n 1 :a 2 :b 3}})]
      (is (= [[:dispatch [:counter/inc]]] (:script spec))
          "no auto-assert → no trailing block even when :final-db supplied"))))

(deftest auto-assert-no-final-db-noop
  (testing ":auto-assert? true but no :final-db → empty assert block"
    (let [spec (rf.story.recorder.play-export/recording->script-body
                 [[:counter/inc]]
                 {:auto-assert? true})]
      (is (= [[:dispatch [:counter/inc]]] (:script spec))))))

;; ---- changed-top-paths ---------------------------------------------------

(deftest changed-top-paths-shape
  (testing "changed-top-paths returns [k] vectors for every changed key"
    (is (= [[:n]]
           (vec (rf.story.recorder.play-export/changed-top-paths {:n 0 :who "a"}
                                          {:n 1 :who "a"}))))
    (is (= [[:added]]
           (vec (rf.story.recorder.play-export/changed-top-paths {:k 1}
                                          {:k 1 :added :new}))))
    (is (empty? (rf.story.recorder.play-export/changed-top-paths {:n 1} {:n 1}))
        "identical maps → no paths")))

;; ---- render round-trip ---------------------------------------------------

(deftest render-script-body-round-trips
  (testing "the rendered EDN parses back to the same canonical spec"
    (let [spec (rf.story.recorder.play-export/recording->script-body
                 [[:counter/inc] [:counter/dec]]
                 {:name "round trip" :auto-run? true})
          rendered (rf.story.recorder.play-export/render-script-body spec)
          parsed   (edn/read-string rendered)]
      (is (map? parsed))
      (is (= "round trip" (:name parsed)))
      (is (true? (:auto-run? parsed)))
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script parsed))))))

(deftest render-script-body-empty-script
  (testing "an empty :script renders as []"
    (let [spec     (rf.story.recorder.play-export/recording->script-body [] {})
          rendered (rf.story.recorder.play-export/render-script-body spec)]
      (is (str/includes? rendered ":script    []"))
      (is (str/includes? rendered ":auto-run? true")))))

(deftest render-variant-form-round-trips
  (testing "the rendered (reg-variant ...) form parses back to a callable shape"
    (let [spec     (rf.story.recorder.play-export/recording->script-body
                     [[:counter/inc]]
                     {:auto-run? false})
          form-str (rf.story.recorder.play-export/render-variant-form
                     spec {:variant-id :story.x/recorded
                           :extends    :story.x/source})
          parsed   (edn/read-string form-str)]
      (is (seq? parsed))
      (is (= 'rf.story/reg-variant (first parsed)))
      (is (= :story.x/recorded (second parsed)))
      (let [body (nth parsed 2)]
        (is (= :story.x/source (:extends body)))
        ;; rf2-7mj4z — the rendered form uses the :script slot (a
        ;; `{:script … :auto-run?}` body), never the retired :play-script.
        (is (map? (:script body)))
        (is (nil? (:play-script body))
            "the rendered form never emits the retired :play-script slot")
        (is (false? (:auto-run? (:script body))))
        (is (= [[:dispatch [:counter/inc]]] (:script (:script body))))))))

(deftest render-variant-form-default-alias-and-id
  (testing "defaults fill in when not supplied"
    (let [form-str (rf.story.recorder.play-export/render-variant-form
                     {:script [] :auto-run? true} {})]
      (is (str/starts-with? form-str "(rf.story/reg-variant "))
      (is (str/includes? form-str ":story.recorded/play-export")))))

;; ---- runner round-trip ---------------------------------------------------

(deftest exported-script-survives-runner-parse-spec
  (testing "the exported spec passes rf.story.play.runner/parse-spec without further coercion"
    (let [events [[:counter/inc]
                  [:rf.assert/path-equals [:n] 1]
                  [:counter/dec]]
          spec   (rf.story.recorder.play-export/recording->script-body events {:name "rt"})
          parsed (rf.story.play.runner/parse-spec spec)]
      (is (= (:script spec) (:script parsed))
          "the runner parses the exported script identically (no normalisation drift)")
      (is (= (:auto-run? spec) (:auto-run? parsed)))
      (is (= (:name spec) (:name parsed)))
      (is (every? rf.story.play.runner/known-step? (:script parsed))
          "every emitted step is a known runner step type")
      (is (every? rf.story.play.runner/step-arity-ok? (:script parsed))
          "every emitted step has a valid arity"))))

(deftest exported-script-validates-clean
  (testing "the exported script passes rf.story.play.runner/validate-script (no malformed steps)"
    (let [events [[:counter/inc] [:counter/dec]]
          spec   (rf.story.recorder.play-export/recording->script-body
                   events
                   {:auto-assert? true
                    :final-db     {:n 1 :who "alice"}})]
      (is (= [] (rf.story.play.runner/validate-script (:script spec)))
          "no malformed steps"))))

;; ===========================================================================
;; rf2-d5u89 — :entries shape + DOM-events + wait-step insertion
;; ===========================================================================

;; ---- entry->step ---------------------------------------------------------

(deftest entry-step-dispatch
  (testing ":event/dispatch entry of an ordinary event → [:dispatch ev]"
    (is (= [:dispatch [:counter/inc]]
           (rf.story.recorder.play-export/entry->step
             {:kind :event/dispatch :event [:counter/inc] :t 0})))))

(deftest entry-step-assertion-rides-dispatch-sync
  (testing ":event/dispatch entry of an assertion event → [:dispatch-sync ev]"
    (is (= [:dispatch-sync [:rf.assert/path-equals [:n] 1]]
           (rf.story.recorder.play-export/entry->step
             {:kind :event/dispatch
              :event [:rf.assert/path-equals [:n] 1]
              :t 0})))))

(deftest entry-step-dom-click
  (testing ":dom/click entry → [:click selector]"
    (is (= [:click "[data-test=\"submit\"]"]
           (rf.story.recorder.play-export/entry->step
             {:kind :dom/click :selector "[data-test=\"submit\"]" :t 250})))))

(deftest entry-step-dom-type
  (testing ":dom/type entry → [:type selector text]"
    (is (= [:type "[id=\"name\"]" "alice"]
           (rf.story.recorder.play-export/entry->step
             {:kind :dom/type :selector "[id=\"name\"]" :text "alice" :t 300})))
    (is (= [:type "[id=\"x\"]" ""]
           (rf.story.recorder.play-export/entry->step
             {:kind :dom/type :selector "[id=\"x\"]" :t 0}))
        "missing :text defaults to empty string")))

(deftest entry-step-dom-submit-maps-to-click
  (testing ":dom/submit entry → best-effort [:click form-selector]"
    (is (= [:click "[id=\"login-form\"]"]
           (rf.story.recorder.play-export/entry->step
             {:kind :dom/submit :selector "[id=\"login-form\"]" :t 0})))))

(deftest entry-step-redacted-dispatch-drops
  (testing ":event/dispatch of a [:rf/redacted] placeholder yields nil"
    (is (nil? (rf.story.recorder.play-export/entry->step
                {:kind :event/dispatch :event [:rf/redacted] :t 0})))))

(deftest entry-step-unknown-kind-yields-nil
  (testing "unknown entry kinds yield nil"
    (is (nil? (rf.story.recorder.play-export/entry->step {:kind :unknown :selector "x" :t 0})))
    (is (nil? (rf.story.recorder.play-export/entry->step nil)))
    (is (nil? (rf.story.recorder.play-export/entry->step {})))))

(deftest entry-step-missing-selector-yields-nil
  (testing "DOM-entry without a selector yields nil"
    (is (nil? (rf.story.recorder.play-export/entry->step {:kind :dom/click :t 0})))
    (is (nil? (rf.story.recorder.play-export/entry->step {:kind :dom/type :text "x" :t 0})))
    (is (nil? (rf.story.recorder.play-export/entry->step {:kind :dom/submit :t 0})))))

;; ---- entries->steps + wait insertion -------------------------------------

(deftest entries-translate-in-order
  (testing "entries translate to steps in declared order"
    (is (= [[:dispatch [:counter/inc]]
            [:click "[data-test=\"x\"]"]
            [:type "[id=\"name\"]" "alice"]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :event/dispatch :event [:counter/inc] :t 0}
              {:kind :dom/click :selector "[data-test=\"x\"]" :t 10}
              {:kind :dom/type :selector "[id=\"name\"]" :text "alice" :t 20}])))))

(deftest wait-step-inserted-when-gap-exceeds-threshold
  (testing "consecutive entries > threshold ms apart get a [:wait Δt] between them"
    (is (= [[:click "[data-test=\"a\"]"]
            [:wait 100]
            [:click "[data-test=\"b\"]"]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 100}])))))

(deftest no-wait-when-gap-below-threshold
  (testing "sub-threshold gaps fold out (no :wait noise)"
    (is (= [[:click "[data-test=\"a\"]"]
            [:click "[data-test=\"b\"]"]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 25}])))))

(deftest wait-threshold-override
  (testing "the :wait-threshold-ms opt tunes the gap detector"
    (is (= [[:click "[data-test=\"a\"]"]
            [:wait 30]
            [:click "[data-test=\"b\"]"]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 30}]
             {:wait-threshold-ms 10})))))

(deftest large-wait-threshold-disables-waits
  (testing "an effectively-infinite threshold suppresses every wait"
    (is (= [[:click "[data-test=\"a\"]"]
            [:click "[data-test=\"b\"]"]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 5000}]
             {:wait-threshold-ms 999999})))))

(deftest mixed-events-and-dom-translate-together
  (testing "dispatched events + DOM events + waits compose"
    (is (= [[:dispatch [:counter/inc]]
            [:wait 100]
            [:click "[data-test=\"submit\"]"]
            [:type "[id=\"name\"]" "alice"]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :event/dispatch :event [:counter/inc] :t 0}
              {:kind :dom/click :selector "[data-test=\"submit\"]" :t 100}
              {:kind :dom/type :selector "[id=\"name\"]" :text "alice" :t 120}])))))

(deftest redacted-entries-do-not-leave-orphan-waits
  (testing "a dropped (redacted) entry doesn't insert a wait for itself,
            but later entries still compare against the most recent
            translated step's timestamp"
    (is (= [[:dispatch [:counter/inc]]
            [:wait 200]
            [:dispatch [:counter/dec]]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :event/dispatch :event [:counter/inc]   :t 0}
              {:kind :event/dispatch :event [:rf/redacted]   :t 100}
              {:kind :event/dispatch :event [:counter/dec]   :t 200}])))))

;; ---- :event/timer-child — the forced wait for a re-armed timer ------------
;;
;; A fired `:dispatch-later` child records as a payload-free marker, never a
;; step, because replaying its root re-arms the timer (rf2-tbik1). Its wait
;; must bring the replay up to the time the child fired, measured from the
;; replay's own clock — which runs BEHIND the recorded one by every
;; sub-threshold gap the export folded out, since a replayed step between
;; them takes no time.

(deftest timer-child-wait-covers-a-folded-out-gap
  (testing "rf2-mcjdg — the root arms an 80ms timer at 0, an unrelated
            dispatch lands at 40 (under the 50ms threshold, so no wait), and
            the child fires at 80. Replayed, the root re-arms the timer and
            `:t/other` runs straight after it, so the child needs the whole
            80ms from there: a [:wait 40] reaches the auto-assert 40ms in"
    (is (= [[:dispatch [:t/root]]
            [:dispatch [:t/other]]
            [:wait 80]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :event/dispatch :event [:t/root]  :t 0}
              {:kind :event/dispatch :event [:t/other] :t 40}
              {:kind :event/timer-child :t 80}]))))
  (testing "and the exported script body puts the child's auto-assert after
            that wait"
    (is (= [[:dispatch [:t/root]]
            [:dispatch [:t/other]]
            [:wait 80]
            [:assert-db [:children] 1]]
           (:script (rf.story.recorder.play-export/recording->script-body
                      [{:kind :event/dispatch :event [:t/root]  :t 0}
                       {:kind :event/dispatch :event [:t/other] :t 40}
                       {:kind :event/timer-child :t 80}]
                      {:auto-assert? true
                       :seed-db      {}
                       :final-db     {:children 1}})))))
  (testing "control: with no intervening event the wait was already 80"
    (is (= [[:dispatch [:t/root]]
            [:wait 80]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :event/dispatch :event [:t/root] :t 0}
              {:kind :event/timer-child :t 80}])))))

(deftest timer-child-wait-counts-only-what-the-replay-has-not-waited
  (testing "an emitted wait IS replay time, so the child's wait adds only the
            gap folded out since: 100 waited, 30 folded out, 50 to the child"
    (is (= [[:dispatch [:t/root]]
            [:wait 100]
            [:dispatch [:t/a]]
            [:dispatch [:t/b]]
            [:wait 80]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :event/dispatch :event [:t/root] :t 0}
              {:kind :event/dispatch :event [:t/a]    :t 100}
              {:kind :event/dispatch :event [:t/b]    :t 130}
              {:kind :event/timer-child :t 180}])))))

(deftest ordinary-gaps-still-fold-around-a-timer-child
  (testing "only the timer child's wait catches up: an ordinary sub-threshold
            gap after it still folds out, and an ordinary gap over the
            threshold still waits that gap alone"
    (is (= [[:dispatch [:t/root]]
            [:dispatch [:t/other]]
            [:wait 80]
            [:click "[data-test=\"a\"]"]
            [:wait 60]
            [:click "[data-test=\"b\"]"]]
           (rf.story.recorder.play-export/entries->steps
             [{:kind :event/dispatch :event [:t/root]  :t 0}
              {:kind :event/dispatch :event [:t/other] :t 40}
              {:kind :event/timer-child :t 80}
              {:kind :dom/click :selector "[data-test=\"a\"]" :t 100}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 160}])))))

;; ---- recording->script-body with the rich :entries shape -----------------

(deftest recording-from-entries
  (testing "passing rich :entries vectors produces a full-fidelity script"
    (let [entries [{:kind :event/dispatch :event [:counter/inc] :t 0}
                   {:kind :dom/click :selector "[data-test=\"b\"]" :t 200}
                   {:kind :dom/type  :selector "[id=\"x\"]" :text "hi" :t 220}]
          spec    (rf.story.recorder.play-export/recording->script-body entries)]
      (is (= [[:dispatch [:counter/inc]]
              [:wait 200]
              [:click "[data-test=\"b\"]"]
              [:type "[id=\"x\"]" "hi"]]
             (:script spec))
          "200ms gap → wait; 20ms gap → no wait"))))

(deftest recording-from-entries-respects-wait-threshold-opt
  (testing ":wait-threshold-ms opt threads through recording->script-body"
    (let [entries [{:kind :event/dispatch :event [:counter/inc] :t 0}
                   {:kind :event/dispatch :event [:counter/dec] :t 75}]
          spec    (rf.story.recorder.play-export/recording->script-body entries {:wait-threshold-ms 100})]
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script spec))
          "75ms gap < 100ms threshold — no :wait inserted"))))

(deftest legacy-bare-events-still-translate-without-waits
  (testing "callers that still pass bare event-vectors get the old behaviour
            (no :wait steps emitted — all entries stamped :t 0)"
    (let [spec (rf.story.recorder.play-export/recording->script-body
                 [[:counter/inc] [:counter/inc] [:counter/dec]])]
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script spec))))))

(deftest mixed-bare-and-entry-input
  (testing "an input vector mixing bare event vectors and rich entries
            still coerces cleanly"
    (let [spec (rf.story.recorder.play-export/recording->script-body
                 [[:counter/inc]
                  {:kind :dom/click :selector "[data-test=\"b\"]" :t 100}])]
      (is (= [[:dispatch [:counter/inc]]
              [:wait 100]
              [:click "[data-test=\"b\"]"]]
             (:script spec))))))

(deftest exported-rich-script-survives-runner-parse-spec
  (testing "rich-entries-derived script passes rf.story.play.runner/parse-spec + validate-script clean"
    (let [entries [{:kind :event/dispatch :event [:counter/inc] :t 0}
                   {:kind :dom/click :selector "[data-test=\"b\"]" :t 80}
                   {:kind :dom/type  :selector "[id=\"x\"]" :text "hi" :t 200}]
          spec    (rf.story.recorder.play-export/recording->script-body entries {:name "round trip"})
          parsed  (rf.story.play.runner/parse-spec spec)]
      (is (= (:script spec) (:script parsed))
          "runner parses identically — no normalisation drift")
      (is (every? rf.story.play.runner/known-step? (:script parsed))
          "every emitted step is a known runner step")
      (is (every? rf.story.play.runner/step-arity-ok? (:script parsed))
          "every emitted step has a legal arity")
      (is (= [] (rf.story.play.runner/validate-script (:script parsed)))
          "no malformed steps"))))

(deftest dom-submit-survives-runner-validation
  (testing "the :dom/submit best-effort translation produces a valid :click step"
    (let [entries [{:kind :dom/submit :selector "[id=\"login-form\"]" :t 0}]
          spec    (rf.story.recorder.play-export/recording->script-body entries)]
      (is (= [[:click "[id=\"login-form\"]"]] (:script spec)))
      (is (= [] (rf.story.play.runner/validate-script (:script spec)))))))

;; ---- round-trip: 4-step recording → export → runner-parse → assert -------

(deftest four-step-round-trip
  (testing "a 4-step interaction (click → type → click → dispatch) survives
            the full export + parse pipeline"
    (let [entries [{:kind :dom/click :selector "[data-test=\"open\"]"  :t 0}
                   {:kind :dom/type  :selector "[id=\"name\"]" :text "alice" :t 200}
                   {:kind :dom/click :selector "[data-test=\"save\"]"  :t 600}
                   {:kind :event/dispatch :event [:counter/inc] :t 1100}]
          spec    (rf.story.recorder.play-export/recording->script-body entries {:name "round trip"})
          parsed  (rf.story.play.runner/parse-spec spec)]
      ;; Translation contract — every event lifts and waits insert.
      (is (= [[:click "[data-test=\"open\"]"]
              [:wait 200]
              [:type "[id=\"name\"]" "alice"]
              [:wait 400]
              [:click "[data-test=\"save\"]"]
              [:wait 500]
              [:dispatch [:counter/inc]]]
             (:script spec))
          "all four entries translate; waits insert on each >50ms gap")
      ;; Runner contract — every emitted step is well-formed.
      (is (every? rf.story.play.runner/known-step? (:script spec)))
      (is (every? rf.story.play.runner/step-arity-ok? (:script spec)))
      (is (= [] (rf.story.play.runner/validate-script (:script spec))))
      (is (= "round trip" (:name parsed))))))

;; ---- rf2-3x7nj.30.5 — a positional selector carries the harden hint ------

(deftest positional-selector-steps-carry-the-harden-hint
  (testing "rf2-3x7nj.30.5: a canvas element with no data-test / id /
            aria-label records the positional `tag:nth-of-type(N)` fallback,
            and the pasted form says so above the step, as the selector's
            documented contract promises. The selectors come from the
            recorder's own picker, not hand-written"
    (let [input-sel (rf.story.recorder.selector/pick-selector
                      {:tag "input" :attrs {} :index-of-type 1})
          button-sel (rf.story.recorder.selector/pick-selector
                       {:tag "button" :attrs {} :index-of-type 1})
          hooked-sel (rf.story.recorder.selector/pick-selector
                       {:tag "button" :attrs {"data-test" "save"} :index-of-type 1})
          entries   [{:kind :dom/type  :selector input-sel :text "bob" :t 0}
                     {:kind :dom/click :selector button-sel :t 0}
                     {:kind :dom/click :selector hooked-sel :t 0}]
          {:keys [spec snippet]} (rf.story.recorder.play-export/save-dialog-output
                                   entries {:variant-id :story.x/recorded
                                            :extends    :story.x/source})
          lines     (mapv str/trim (str/split-lines snippet))
          hint-for  (fn [sel] (str ";; TODO harden selector: " (pr-str sel)))
          above     (fn [step]
                      (let [s (pr-str step)
                            i (first (keep-indexed (fn [i l] (when (= s l) i)) lines))]
                        (when (and i (pos? i)) (nth lines (dec i)))))]
      (is (= "input:nth-of-type(1)" input-sel) "precondition: the positional fallback")
      (is (= [[:type input-sel "bob"] [:click button-sel] [:click hooked-sel]]
             (:script spec)))
      (is (str/includes? (str (above [:type input-sel "bob"])) (hint-for input-sel))
          "the positional type step is preceded by its hint")
      (is (str/includes? (str (above [:click button-sel])) (hint-for button-sel))
          "the positional click step is preceded by its hint")
      (is (= 2 (count (filter #(str/includes? % "TODO harden selector") lines)))
          "control: the data-test step carries no hint")
      (is (= (:script spec) (:script (:script (nth (edn/read-string snippet) 2))))
          "the hint is a comment: the form still reads back to the same script"))))
