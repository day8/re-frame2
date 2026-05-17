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
