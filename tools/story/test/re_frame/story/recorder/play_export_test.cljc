(ns re-frame.story.recorder.play-export-test
  "Pure unit tests for the recorder → :play-script translator
  (rf2-x9zsr).

  Covers:

  - Per-event translation (`event->step`) — assertion events ride the
    `:dispatch-sync` rail; everything else rides `:dispatch`;
    redacted placeholders drop out.
  - Recording-level translation (`recording->play-script`) — empty
    input, name + auto-run? slots, auto-assert with and without a
    seed db, max-auto-assertions cap.
  - Snippet rendering (`render-play-script` / `render-variant-form`)
    — round-trip cleanly through `runner/parse-spec`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [re-frame.story.play.runner             :as runner]
            [re-frame.story.recorder.play-export    :as export]))

;; ---- per-event translation -----------------------------------------------

(deftest event-step-dispatch
  (testing "ordinary events become :dispatch steps"
    (is (= [:dispatch [:counter/inc]]
           (export/event->step [:counter/inc])))
    (is (= [:dispatch [:auth/login {:user "x"}]]
           (export/event->step [:auth/login {:user "x"}])))))

(deftest event-step-assertion-rides-dispatch-sync
  (testing "assertion events (`:rf.assert/*`) translate to :dispatch-sync"
    (is (= [:dispatch-sync [:rf.assert/path-equals [:n] 3]]
           (export/event->step [:rf.assert/path-equals [:n] 3])))
    (is (= [:dispatch-sync [:rf.assert/no-warnings]]
           (export/event->step [:rf.assert/no-warnings])))
    (is (= [:dispatch-sync [:rf.assert/sub-equals [:counter] 5]]
           (export/event->step [:rf.assert/sub-equals [:counter] 5])))))

(deftest event-step-redacted-drops
  (testing "the [:rf/redacted] placeholder (recorder's canonical 1-tuple) drops out"
    (is (nil? (export/event->step [:rf/redacted])))))

(deftest event-step-malformed-yields-nil
  (testing "malformed inputs return nil"
    (is (nil? (export/event->step nil)))
    (is (nil? (export/event->step [])))
    (is (nil? (export/event->step ["not-keyword"])))
    (is (nil? (export/event->step "not-a-vector")))))

;; ---- recording-level translation -----------------------------------------

(deftest simple-recording-three-dispatches
  (testing "a three-event recording yields a three-step script"
    (let [events [[:counter/inc] [:counter/inc] [:counter/dec]]
          spec   (export/recording->play-script events {})]
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
  (testing "an empty recording yields a legal empty :play-script"
    (let [spec (export/recording->play-script [])]
      (is (= [] (:script spec)))
      (is (true? (:auto-run? spec))))))

(deftest name-and-auto-run-honoured
  (testing "the :name and :auto-run? opts flow through to the spec"
    (let [spec (export/recording->play-script
                 [[:counter/inc]]
                 {:name "happy path" :auto-run? false})]
      (is (= "happy path" (:name spec)))
      (is (false? (:auto-run? spec))))))

(deftest blank-name-omitted
  (testing ":name is omitted when blank or non-string"
    (is (not (contains? (export/recording->play-script [] {:name ""})
                        :name)))
    (is (not (contains? (export/recording->play-script [] {:name nil})
                        :name)))))

(deftest mixed-events-translate-with-tags
  (testing "ordinary + assertion + redacted events translate together"
    (let [events [[:counter/inc]
                  [:rf.assert/path-equals [:n] 1]
                  [:rf/redacted]
                  [:counter/dec]]
          spec   (export/recording->play-script events {})]
      (is (= [[:dispatch       [:counter/inc]]
              [:dispatch-sync  [:rf.assert/path-equals [:n] 1]]
              [:dispatch       [:counter/dec]]]
             (:script spec))
          "assertion → :dispatch-sync; ordinary → :dispatch; redacted drops"))))

;; ---- auto-assert ---------------------------------------------------------

(deftest auto-assert-from-final-db-no-seed
  (testing "auto-assert with no seed emits one assert-db per top-level key"
    (let [spec (export/recording->play-script
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
          spec   (export/recording->play-script
                   [[:counter/inc]]
                   {:auto-assert? true
                    :seed-db      seed
                    :final-db     final})
          asserts (filterv #(= :assert-db (first %)) (:script spec))]
      (is (= #{[:assert-db [:n] 1]
               [:assert-db [:extra] :added]}
             (set asserts))
          ":who is unchanged → no assertion; :n changed + :extra new → two assertions"))))

(deftest auto-assert-cap-respected
  (testing "the max-auto-assertions cap limits the trailing block"
    (let [final-db (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 20))
          spec     (export/recording->play-script
                     []
                     {:auto-assert?        true
                      :final-db            final-db
                      :max-auto-assertions 3})
          asserts  (filter #(= :assert-db (first %)) (:script spec))]
      (is (= 3 (count asserts))
          "20-key db capped to 3 :assert-db steps"))))

(deftest auto-assert-off-default
  (testing "without :auto-assert? true, no assertions trail"
    (let [spec (export/recording->play-script
                 [[:counter/inc]]
                 {:final-db {:n 1 :a 2 :b 3}})]
      (is (= [[:dispatch [:counter/inc]]] (:script spec))
          "no auto-assert → no trailing block even when :final-db supplied"))))

(deftest auto-assert-no-final-db-noop
  (testing ":auto-assert? true but no :final-db → empty assert block"
    (let [spec (export/recording->play-script
                 [[:counter/inc]]
                 {:auto-assert? true})]
      (is (= [[:dispatch [:counter/inc]]] (:script spec))))))

;; ---- changed-top-paths ---------------------------------------------------

(deftest changed-top-paths-shape
  (testing "changed-top-paths returns [k] vectors for every changed key"
    (is (= [[:n]]
           (vec (export/changed-top-paths {:n 0 :who "a"}
                                          {:n 1 :who "a"}))))
    (is (= [[:added]]
           (vec (export/changed-top-paths {:k 1}
                                          {:k 1 :added :new}))))
    (is (empty? (export/changed-top-paths {:n 1} {:n 1}))
        "identical maps → no paths")))

;; ---- render round-trip ---------------------------------------------------

(deftest render-play-script-round-trips
  (testing "the rendered EDN parses back to the same canonical spec"
    (let [spec (export/recording->play-script
                 [[:counter/inc] [:counter/dec]]
                 {:name "round trip" :auto-run? true})
          rendered (export/render-play-script spec)
          parsed   (edn/read-string rendered)]
      (is (map? parsed))
      (is (= "round trip" (:name parsed)))
      (is (true? (:auto-run? parsed)))
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script parsed))))))

(deftest render-play-script-empty-script
  (testing "an empty :script renders as []"
    (let [spec     (export/recording->play-script [] {})
          rendered (export/render-play-script spec)]
      (is (str/includes? rendered ":script    []"))
      (is (str/includes? rendered ":auto-run? true")))))

(deftest render-variant-form-round-trips
  (testing "the rendered (reg-variant ...) form parses back to a callable shape"
    (let [spec     (export/recording->play-script
                     [[:counter/inc]]
                     {:auto-run? false})
          form-str (export/render-variant-form
                     spec {:variant-id :story.x/recorded
                           :extends    :story.x/source})
          parsed   (edn/read-string form-str)]
      (is (seq? parsed))
      (is (= 'story/reg-variant (first parsed)))
      (is (= :story.x/recorded (second parsed)))
      (let [body (nth parsed 2)]
        (is (= :story.x/source (:extends body)))
        (is (map? (:play-script body)))
        (is (false? (:auto-run? (:play-script body))))
        (is (= [[:dispatch [:counter/inc]]] (:script (:play-script body))))))))

(deftest render-variant-form-default-alias-and-id
  (testing "defaults fill in when not supplied"
    (let [form-str (export/render-variant-form
                     {:script [] :auto-run? true} {})]
      (is (str/includes? form-str "story/reg-variant"))
      (is (str/includes? form-str ":story.recorded/play-export")))))

;; ---- runner round-trip ---------------------------------------------------

(deftest exported-script-survives-runner-parse-spec
  (testing "the exported spec passes runner/parse-spec without further coercion"
    (let [events [[:counter/inc]
                  [:rf.assert/path-equals [:n] 1]
                  [:counter/dec]]
          spec   (export/recording->play-script events {:name "rt"})
          parsed (runner/parse-spec spec)]
      (is (= (:script spec) (:script parsed))
          "the runner parses the exported script identically (no normalisation drift)")
      (is (= (:auto-run? spec) (:auto-run? parsed)))
      (is (= (:name spec) (:name parsed)))
      (is (every? runner/known-step? (:script parsed))
          "every emitted step is a known runner step type")
      (is (every? runner/step-arity-ok? (:script parsed))
          "every emitted step has a valid arity"))))

(deftest exported-script-validates-clean
  (testing "the exported script passes runner/validate-script (no malformed steps)"
    (let [events [[:counter/inc] [:counter/dec]]
          spec   (export/recording->play-script
                   events
                   {:auto-assert? true
                    :final-db     {:n 1 :who "alice"}})]
      (is (= [] (runner/validate-script (:script spec)))
          "no malformed steps"))))

;; ===========================================================================
;; rf2-d5u89 — :entries shape + DOM-events + wait-step insertion
;; ===========================================================================

;; ---- entry->step ---------------------------------------------------------

(deftest entry-step-dispatch
  (testing ":event/dispatch entry of an ordinary event → [:dispatch ev]"
    (is (= [:dispatch [:counter/inc]]
           (export/entry->step
             {:kind :event/dispatch :event [:counter/inc] :t 0})))))

(deftest entry-step-assertion-rides-dispatch-sync
  (testing ":event/dispatch entry of an assertion event → [:dispatch-sync ev]"
    (is (= [:dispatch-sync [:rf.assert/path-equals [:n] 1]]
           (export/entry->step
             {:kind :event/dispatch
              :event [:rf.assert/path-equals [:n] 1]
              :t 0})))))

(deftest entry-step-dom-click
  (testing ":dom/click entry → [:click selector]"
    (is (= [:click "[data-test=\"submit\"]"]
           (export/entry->step
             {:kind :dom/click :selector "[data-test=\"submit\"]" :t 250})))))

(deftest entry-step-dom-type
  (testing ":dom/type entry → [:type selector text]"
    (is (= [:type "[id=\"name\"]" "alice"]
           (export/entry->step
             {:kind :dom/type :selector "[id=\"name\"]" :text "alice" :t 300})))
    (is (= [:type "[id=\"x\"]" ""]
           (export/entry->step
             {:kind :dom/type :selector "[id=\"x\"]" :t 0}))
        "missing :text defaults to empty string")))

(deftest entry-step-dom-submit-maps-to-click
  (testing ":dom/submit entry → best-effort [:click form-selector]"
    (is (= [:click "[id=\"login-form\"]"]
           (export/entry->step
             {:kind :dom/submit :selector "[id=\"login-form\"]" :t 0})))))

(deftest entry-step-redacted-dispatch-drops
  (testing ":event/dispatch of a [:rf/redacted] placeholder yields nil"
    (is (nil? (export/entry->step
                {:kind :event/dispatch :event [:rf/redacted] :t 0})))))

(deftest entry-step-unknown-kind-yields-nil
  (testing "unknown entry kinds yield nil"
    (is (nil? (export/entry->step {:kind :unknown :selector "x" :t 0})))
    (is (nil? (export/entry->step nil)))
    (is (nil? (export/entry->step {})))))

(deftest entry-step-missing-selector-yields-nil
  (testing "DOM-entry without a selector yields nil"
    (is (nil? (export/entry->step {:kind :dom/click :t 0})))
    (is (nil? (export/entry->step {:kind :dom/type :text "x" :t 0})))
    (is (nil? (export/entry->step {:kind :dom/submit :t 0})))))

;; ---- entries->steps + wait insertion -------------------------------------

(deftest entries-translate-in-order
  (testing "entries translate to steps in declared order"
    (is (= [[:dispatch [:counter/inc]]
            [:click "[data-test=\"x\"]"]
            [:type "[id=\"name\"]" "alice"]]
           (export/entries->steps
             [{:kind :event/dispatch :event [:counter/inc] :t 0}
              {:kind :dom/click :selector "[data-test=\"x\"]" :t 10}
              {:kind :dom/type :selector "[id=\"name\"]" :text "alice" :t 20}])))))

(deftest wait-step-inserted-when-gap-exceeds-threshold
  (testing "consecutive entries > threshold ms apart get a [:wait Δt] between them"
    (is (= [[:click "[data-test=\"a\"]"]
            [:wait 100]
            [:click "[data-test=\"b\"]"]]
           (export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 100}])))))

(deftest no-wait-when-gap-below-threshold
  (testing "sub-threshold gaps fold out (no :wait noise)"
    (is (= [[:click "[data-test=\"a\"]"]
            [:click "[data-test=\"b\"]"]]
           (export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 25}])))))

(deftest wait-threshold-override
  (testing "the :wait-threshold-ms opt tunes the gap detector"
    (is (= [[:click "[data-test=\"a\"]"]
            [:wait 30]
            [:click "[data-test=\"b\"]"]]
           (export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 30}]
             {:wait-threshold-ms 10})))))

(deftest large-wait-threshold-disables-waits
  (testing "an effectively-infinite threshold suppresses every wait"
    (is (= [[:click "[data-test=\"a\"]"]
            [:click "[data-test=\"b\"]"]]
           (export/entries->steps
             [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
              {:kind :dom/click :selector "[data-test=\"b\"]" :t 5000}]
             {:wait-threshold-ms 999999})))))

(deftest mixed-events-and-dom-translate-together
  (testing "dispatched events + DOM events + waits compose"
    (is (= [[:dispatch [:counter/inc]]
            [:wait 100]
            [:click "[data-test=\"submit\"]"]
            [:type "[id=\"name\"]" "alice"]]
           (export/entries->steps
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
           (export/entries->steps
             [{:kind :event/dispatch :event [:counter/inc]   :t 0}
              {:kind :event/dispatch :event [:rf/redacted]   :t 100}
              {:kind :event/dispatch :event [:counter/dec]   :t 200}])))))

;; ---- recording->play-script with the rich :entries shape -----------------

(deftest recording-from-entries
  (testing "passing rich :entries vectors produces a full-fidelity script"
    (let [entries [{:kind :event/dispatch :event [:counter/inc] :t 0}
                   {:kind :dom/click :selector "[data-test=\"b\"]" :t 200}
                   {:kind :dom/type  :selector "[id=\"x\"]" :text "hi" :t 220}]
          spec    (export/recording->play-script entries)]
      (is (= [[:dispatch [:counter/inc]]
              [:wait 200]
              [:click "[data-test=\"b\"]"]
              [:type "[id=\"x\"]" "hi"]]
             (:script spec))
          "200ms gap → wait; 20ms gap → no wait"))))

(deftest recording-from-entries-respects-wait-threshold-opt
  (testing ":wait-threshold-ms opt threads through recording->play-script"
    (let [entries [{:kind :event/dispatch :event [:counter/inc] :t 0}
                   {:kind :event/dispatch :event [:counter/dec] :t 75}]
          spec    (export/recording->play-script entries {:wait-threshold-ms 100})]
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script spec))
          "75ms gap < 100ms threshold — no :wait inserted"))))

(deftest legacy-bare-events-still-translate-without-waits
  (testing "callers that still pass bare event-vectors get the old behaviour
            (no :wait steps emitted — all entries stamped :t 0)"
    (let [spec (export/recording->play-script
                 [[:counter/inc] [:counter/inc] [:counter/dec]])]
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script spec))))))

(deftest mixed-bare-and-entry-input
  (testing "an input vector mixing bare event vectors and rich entries
            still coerces cleanly"
    (let [spec (export/recording->play-script
                 [[:counter/inc]
                  {:kind :dom/click :selector "[data-test=\"b\"]" :t 100}])]
      (is (= [[:dispatch [:counter/inc]]
              [:wait 100]
              [:click "[data-test=\"b\"]"]]
             (:script spec))))))

(deftest exported-rich-script-survives-runner-parse-spec
  (testing "rich-entries-derived script passes runner/parse-spec + validate-script clean"
    (let [entries [{:kind :event/dispatch :event [:counter/inc] :t 0}
                   {:kind :dom/click :selector "[data-test=\"b\"]" :t 80}
                   {:kind :dom/type  :selector "[id=\"x\"]" :text "hi" :t 200}]
          spec    (export/recording->play-script entries {:name "round trip"})
          parsed  (runner/parse-spec spec)]
      (is (= (:script spec) (:script parsed))
          "runner parses identically — no normalisation drift")
      (is (every? runner/known-step? (:script parsed))
          "every emitted step is a known runner step")
      (is (every? runner/step-arity-ok? (:script parsed))
          "every emitted step has a legal arity")
      (is (= [] (runner/validate-script (:script parsed)))
          "no malformed steps"))))

(deftest dom-submit-survives-runner-validation
  (testing "the :dom/submit best-effort translation produces a valid :click step"
    (let [entries [{:kind :dom/submit :selector "[id=\"login-form\"]" :t 0}]
          spec    (export/recording->play-script entries)]
      (is (= [[:click "[id=\"login-form\"]"]] (:script spec)))
      (is (= [] (runner/validate-script (:script spec)))))))

;; ---- round-trip: 4-step recording → export → runner-parse → assert -------

(deftest four-step-round-trip
  (testing "a 4-step interaction (click → type → click → dispatch) survives
            the full export + parse pipeline"
    (let [entries [{:kind :dom/click :selector "[data-test=\"open\"]"  :t 0}
                   {:kind :dom/type  :selector "[id=\"name\"]" :text "alice" :t 200}
                   {:kind :dom/click :selector "[data-test=\"save\"]"  :t 600}
                   {:kind :event/dispatch :event [:counter/inc] :t 1100}]
          spec    (export/recording->play-script entries {:name "round trip"})
          parsed  (runner/parse-spec spec)]
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
      (is (every? runner/known-step? (:script spec)))
      (is (every? runner/step-arity-ok? (:script spec)))
      (is (= [] (runner/validate-script (:script spec))))
      (is (= "round trip" (:name parsed))))))
