(ns re-frame.story.ui.controls-scalar-widgets-cljs-test
  "Tests for the Story Controls panel's scalar widget vocabulary
  (rf2-viymg).

  Per /spec/007-Stories.md §argtypes the closed control vocabulary is
  `:text` / `:textarea` / `:number` / `:boolean` / `:select` / `:radio`
  / `:date` / `:color`. These tests pin the rendered hiccup for the
  four widgets that landed late (`:textarea` / `:radio` / `:date` /
  `:color`) plus the unknown-widget fallback, and exercise the
  on-change writes for each through the shell-state's `:cell-overrides`
  slot.

  CLJS-only — the renderer is CLJS-only (it depends on Reagent / DOM
  event objects). Lives in a `.cljc` for symmetry with sibling tests.

  Runs under shadow's `:node-test` and `:browser-test` targets (the
  `cljs-test$` ns regex picks up this name)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            #?(:cljs [re-frame.story :as rf.story])
            #?(:cljs [re-frame.story.args :as rf.story.args])
            #?(:cljs [re-frame.story.ui.controls :as rf.story.ui.controls])
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- fixtures ------------------------------------------------------------

#?(:cljs
   (defn reset-fixture [test-fn]
     (rf.story/clear-all!)
     (rf.story.ui.state/reset-shell-state!)
     (rf.story/install-canonical-vocabulary!)
     (test-fn))
   :clj
   (defn reset-fixture [test-fn]
     (rf.story.ui.state/reset-shell-state!)
     (test-fn)))

(use-fixtures :each reset-fixture)

;; ---- helpers -------------------------------------------------------------

#?(:cljs
   (defn- input-event
     "Synthesise a minimal change-event object whose `.-target.-value`
     is `v`. Sufficient for the `:textarea` / `:date` / `:color` widgets
     which all read `(.. e -target -value)`."
     [v]
     #js {:target #js {:value v}}))

#?(:cljs
   (defn- walk-find
     "Walk a hiccup tree depth-first and return the first node whose tag
     matches `pred`. `pred` receives the hiccup vector's first element."
     [tree pred]
     (let [result (atom nil)]
       (letfn [(walk [node]
                 (when (and (nil? @result) (vector? node))
                   (when (pred (first node)) (reset! result node))
                   (doseq [c (rest node)]
                     (cond
                       (vector? c) (walk c)
                       (seq? c)    (doseq [n c] (walk n))))))]
         (walk tree))
       @result)))

;; ---- :textarea -----------------------------------------------------------

#?(:cljs
   (deftest textarea-widget-renders-textarea-element
     (testing ":textarea widget renders a <textarea> with the current value"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:bio] "hello" {:widget :textarea})]
         (is (= :textarea (first tree)))
         (is (= "hello"   (-> tree second :value)))
         (is (fn?         (-> tree second :on-change)))))))

#?(:cljs
   (deftest textarea-widget-nil-value-becomes-empty-string
     (testing ":textarea coerces a nil value to \"\" to satisfy React's
               controlled-component contract"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:bio] nil {:widget :textarea})]
         (is (= "" (-> tree second :value)))))))

#?(:cljs
   (deftest textarea-widget-on-change-writes-override
     (testing ":textarea on-change writes through to :cell-overrides"
       (let [tree     (rf.story.ui.controls/scalar-widget
                        :story.x/v [:bio] "" {:widget :textarea})
             handler  (-> tree second :on-change)]
         (handler (input-event "multi\nline\ntext"))
         (is (= "multi\nline\ntext"
                (get-in (rf.story.ui.state/get-state)
                        [:cell-overrides :story.x/v :bio])))))))

;; ---- :radio --------------------------------------------------------------

#?(:cljs
   (deftest radio-widget-renders-one-input-per-option
     (testing ":radio widget renders one <input type=\"radio\"> per option"
       (let [tree   (rf.story.ui.controls/scalar-widget
                      :story.x/v [:variant] :primary
                      {:widget :radio :options [:primary :secondary :danger]})
             ;; The radio renders [:div {...radio-row...} <labels...>] — each
             ;; label wraps an [:input {:type "radio" ...}] + a text node.
             inputs (filter (fn [n]
                              (and (vector? n)
                                   (= :input (first n))
                                   (= "radio" (-> n second :type))))
                            (tree-seq vector? rest tree))]
         (is (= 3 (count inputs)))
         (is (= [:primary :secondary :danger]
                (mapv (fn [n]
                        ;; Round-trip the rendered `:value` string back to
                        ;; the source keyword for an order-preserving check.
                        (keyword (subs (-> n second :value) 1)))
                      inputs)))))))

#?(:cljs
   (deftest radio-widget-marks-current-option-checked
     (testing ":radio widget marks exactly the matching option as :checked"
       (let [tree   (rf.story.ui.controls/scalar-widget
                      :story.x/v [:variant] :secondary
                      {:widget :radio :options [:primary :secondary :danger]})
             inputs (filter (fn [n]
                              (and (vector? n)
                                   (= :input (first n))
                                   (= "radio" (-> n second :type))))
                            (tree-seq vector? rest tree))
             checked (filter #(-> % second :checked) inputs)]
         (is (= 1 (count checked)))
         (is (= ":secondary" (-> (first checked) second :value)))))))

#?(:cljs
   (deftest radio-widget-on-change-writes-selected-option
     (testing ":radio on-change writes the raw option (not the stringified
               form) so keywords, numbers, etc. round-trip"
       (let [tree   (rf.story.ui.controls/scalar-widget
                      :story.x/v [:variant] :primary
                      {:widget :radio :options [:primary :secondary :danger]})
             inputs (filter (fn [n]
                              (and (vector? n)
                                   (= :input (first n))
                                   (= "radio" (-> n second :type))))
                            (tree-seq vector? rest tree))
             ;; Click the :danger radio.
             handler (-> (nth (vec inputs) 2) second :on-change)]
         (handler (input-event ":danger"))
         (is (= :danger
                (get-in (rf.story.ui.state/get-state)
                        [:cell-overrides :story.x/v :variant])))))))

#?(:cljs
   (deftest radio-widget-name-attribute-isolates-groups
     (testing "each radio group's <input> shares a `:name` derived from
               variant-id + path — distinct paths mean distinct names so
               two radio groups don't toggle each other"
       (let [tree-a (rf.story.ui.controls/scalar-widget
                      :story.x/v [:a] nil
                      {:widget :radio :options [:x :y]})
             tree-b (rf.story.ui.controls/scalar-widget
                      :story.x/v [:b] nil
                      {:widget :radio :options [:x :y]})
             name-a (->> (tree-seq vector? rest tree-a)
                         (some (fn [n]
                                 (when (and (vector? n)
                                            (= :input (first n))
                                            (= "radio" (-> n second :type)))
                                   (-> n second :name)))))
             name-b (->> (tree-seq vector? rest tree-b)
                         (some (fn [n]
                                 (when (and (vector? n)
                                            (= :input (first n))
                                            (= "radio" (-> n second :type)))
                                   (-> n second :name)))))]
         (is (some? name-a))
         (is (some? name-b))
         (is (not= name-a name-b))))))

;; ---- :date ---------------------------------------------------------------

#?(:cljs
   (deftest date-widget-renders-date-input
     (testing ":date widget renders <input type=\"date\">"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:dob] "2026-05-14" {:widget :date})]
         (is (= :input (first tree)))
         (is (= "date" (-> tree second :type)))
         (is (= "2026-05-14" (-> tree second :value)))))))

#?(:cljs
   (deftest date-widget-nil-value-becomes-empty-string
     (testing ":date coerces nil to \"\" to satisfy the controlled-input
               contract"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:dob] nil {:widget :date})]
         (is (= "" (-> tree second :value)))))))

#?(:cljs
   (deftest date-widget-on-change-writes-iso-string
     (testing ":date on-change writes the raw ISO yyyy-mm-dd string"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:dob] nil {:widget :date})
             handler (-> tree second :on-change)]
         (handler (input-event "2026-12-31"))
         (is (= "2026-12-31"
                (get-in (rf.story.ui.state/get-state)
                        [:cell-overrides :story.x/v :dob])))))))

#?(:cljs
   (deftest date-widget-on-change-empty-writes-nil
     (testing ":date on-change with an empty string clears the slot to nil
               — equivalent to 'no date selected'"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:dob] "2026-05-14" {:widget :date})
             handler (-> tree second :on-change)]
         (handler (input-event ""))
         (is (nil? (get-in (rf.story.ui.state/get-state)
                           [:cell-overrides :story.x/v :dob])))
         (is (contains? (get-in (rf.story.ui.state/get-state)
                                [:cell-overrides :story.x/v])
                        :dob))))))

;; ---- :color --------------------------------------------------------------

#?(:cljs
   (deftest color-widget-renders-color-input
     (testing ":color widget renders <input type=\"color\">"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:bg] "#ff0000" {:widget :color})]
         (is (= :input (first tree)))
         (is (= "color" (-> tree second :type)))
         (is (= "#ff0000" (-> tree second :value)))))))

#?(:cljs
   (deftest color-widget-nil-value-becomes-black
     (testing ":color falls back to a valid #000000 when the slot is nil —
               <input type=\"color\"> rejects any non-hex value, so empty
               strings can't be used"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:bg] nil {:widget :color})]
         (is (= "#000000" (-> tree second :value)))))))

#?(:cljs
   (deftest color-widget-on-change-writes-hex
     (testing ":color on-change writes the picker's hex string verbatim"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:bg] "#000000" {:widget :color})
             handler (-> tree second :on-change)]
         (handler (input-event "#abcdef"))
         (is (= "#abcdef"
                (get-in (rf.story.ui.state/get-state)
                        [:cell-overrides :story.x/v :bg])))))))

;; ---- unknown widget fallback --------------------------------------------

#?(:cljs
   (deftest unknown-widget-still-renders-fallback-span
     (testing "an unknown widget tag renders the inline fallback span —
               this is the existing contract and must survive the new
               widget additions"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:k] "v" {:widget :ratchet})]
         (is (= :span (first tree)))
         (is (re-find #"unsupported widget"
                      (nth tree 2)))))))

;; ---- arg-widget dispatch covers the new widget tags ---------------------

#?(:cljs
   (deftest arg-widget-dispatches-new-scalar-widgets
     (testing "arg-widget routes every new vocabulary entry to scalar-
               widget (rather than the unknown-widget fallback that would
               fire if the dispatch case omitted the new tags)"
       (doseq [w [:textarea :radio :date :color]]
         (let [spec    (cond-> {:widget w}
                         (#{:radio} w) (assoc :options [:a :b]))
               sub-spec (last (rf.story.ui.controls/arg-widget
                                :story.x/v [:k] nil spec))]
           ;; arg-widget returns [scalar-widget variant-id path value spec]
           ;; — the trailing element is the widget-spec map, ensuring the
           ;; dispatch carried our :widget tag through.
           (is (= w (:widget sub-spec))))))))

;; ---- aria-label on every scalar widget (rf2-u01y5) ----------------------
;;
;; Every scalar widget MUST carry an :aria-label derived from its path tail
;; — without this the visible <span> label sibling has no programmatic
;; association with the input and screen readers announce 'edit, blank'.

#?(:cljs
   (deftest each-scalar-widget-carries-aria-label
     (testing "rf2-u01y5: every scalar widget renders with an :aria-label
               derived from its path tail so screen readers announce the
               input by name rather than 'edit, blank'."
       (doseq [[w expected-tag]
               [[{:widget :text}                              :input]
                [{:widget :textarea}                          :textarea]
                [{:widget :number}                            :input]
                [{:widget :boolean}                           :input]
                [{:widget :select :options [:a :b]}           :select]
                [{:widget :date}                              :input]
                [{:widget :color}                             :input]]]
         (let [tree (rf.story.ui.controls/scalar-widget
                      :story.x/v [:username] "x" w)]
           (is (= expected-tag (first tree))
               (str (:widget w) " renders the right tag"))
           (is (some? (-> tree second :aria-label))
               (str (:widget w) " carries an :aria-label"))
           (is (re-find #"username" (-> tree second :aria-label))
               (str (:widget w) " :aria-label includes the path tail")))))))

#?(:cljs
   (deftest radio-widget-radiogroup-has-aria-label
     (testing "rf2-u01y5: the :radio container is a role=radiogroup with
               an aria-label — the inner inputs inherit a name from their
               wrapping <label> so they don't need their own aria-label."
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:variant] :primary
                    {:widget :radio :options [:primary :secondary]})]
         (is (= :div (first tree)))
         (is (= "radiogroup" (-> tree second :role)))
         (is (some? (-> tree second :aria-label)))
         (is (re-find #"variant" (-> tree second :aria-label)))))))

#?(:cljs
   (deftest nested-path-aria-label-is-breadcrumb
     (testing "rf2-u01y5: nested path produces a slash-joined breadcrumb
               so a nested input is announced as 'address / street'
               rather than just 'street'."
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:address :street] "Main St" {:widget :text})]
         (is (re-find #"address" (-> tree second :aria-label)))
         (is (re-find #"street"  (-> tree second :aria-label)))))))

;; ---- rf2-i6v4 · typed values survive the DOM adapter ---------------------
;;
;; The controls panel infers its widgets from the variant's Spec 010
;; schema, so a widget the schema GENERATED must write a value that
;; schema ACCEPTS. `<option value>` can only carry a string, so a select
;; whose on-change wrote the raw DOM string turned `:large` into
;; `":large"` — a value the very `[:enum :small :large]` that produced the
;; widget rejects, and one that never reaches the view's keyword branch.
;; The `:radio` renderer above already writes the source option; these
;; pin the same contract for `:select`, and for the keyword coercion
;; `infer-widget`'s `:keyword` case has always promised.

#?(:cljs
   (deftest select-widget-on-change-writes-the-keyword-option
     (testing ":select on-change writes the SOURCE keyword option, not the
               stringified DOM token — the value satisfies the enum that
               generated the widget"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:size] :small
                       {:widget :select :options [:small :large]})
             handler (-> tree second :on-change)]
         (handler (input-event ":large"))
         (let [written (get-in (rf.story.ui.state/get-state)
                               [:cell-overrides :story.x/v :size])]
           (is (= :large written))
           (is (keyword? written) "a keyword, not the string \":large\"")
           (is (m/validate [:enum :small :large] written)
               "and it satisfies the enum schema the widget was inferred from"))))))

#?(:cljs
   (deftest select-widget-on-change-writes-the-numeric-option
     (testing ":select on-change writes a numeric option as a number"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:cols] 1 {:widget :select :options [1 2 3]})
             handler (-> tree second :on-change)]
         (handler (input-event "3"))
         (let [written (get-in (rf.story.ui.state/get-state)
                               [:cell-overrides :story.x/v :cols])]
           (is (= 3 written))
           (is (number? written) "a number, not the string \"3\"")
           (is (m/validate [:enum 1 2 3] written)))))))

#?(:cljs
   (deftest select-widget-leaves-string-options-as-strings
     (testing ":select does not over-coerce — a string option stays a string"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:label] "a" {:widget :select :options ["a" "b"]})
             handler (-> tree second :on-change)]
         (handler (input-event "b"))
         (is (= "b" (get-in (rf.story.ui.state/get-state)
                            [:cell-overrides :story.x/v :label])))))))

#?(:cljs
   (deftest select-widget-ignores-a-token-naming-no-option
     (testing ":select writes nothing when the chosen token names no option
               — no string leaks into the override as a fallback"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:size] :small
                       {:widget :select :options [:small :large]})
             handler (-> tree second :on-change)]
         (handler (input-event ":enormous"))
         (is (nil? (get-in (rf.story.ui.state/get-state)
                           [:cell-overrides :story.x/v :size]))
             "no override written for an unrecognised token")))))

#?(:cljs
   (deftest select-widget-nested-path-writes-the-typed-option
     (testing "the same adapter at a NESTED path also writes the typed
               option — the fix is in the widget, not in a top-level
               special case"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:theme :mode] :light
                       {:widget :select :options [:light :dark]})
             handler (-> tree second :on-change)]
         (handler (input-event ":dark"))
         (is (= :dark (get-in (rf.story.ui.state/get-state)
                              [:cell-overrides :story.x/v :theme :mode])))))))

#?(:cljs
   (deftest infer-widget-keyword-carries-the-coercion-tag
     (testing "a :keyword schema infers a text widget TAGGED for keyword
               coercion — the docstring's promise, now carried in data"
       (is (= {:widget :text :coerce :keyword}
              (rf.story.ui.controls/infer-widget :keyword))))))

#?(:cljs
   (deftest keyword-text-widget-on-change-writes-a-keyword
     (testing "a schema-derived :keyword text field edits to a KEYWORD, so
               an ordinary keyword argument round-trips through its own
               inferred editor"
       (let [spec    (rf.story.ui.controls/infer-widget :keyword)
             tree    (rf.story.ui.controls/scalar-widget :story.x/v [:status] :idle spec)
             handler (-> tree second :on-change)]
         (handler (input-event "loading"))
         (is (= :loading (get-in (rf.story.ui.state/get-state)
                                 [:cell-overrides :story.x/v :status])))
         ;; The user types what the value PRINTS as just as readily.
         (handler (input-event ":ready"))
         (is (= :ready (get-in (rf.story.ui.state/get-state)
                               [:cell-overrides :story.x/v :status])))))))

#?(:cljs
   (deftest plain-text-widget-still-writes-a-string
     (testing "an untagged :text widget is untouched — actual string args
               stay strings"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:title] "" {:widget :text})
             handler (-> tree second :on-change)]
         (handler (input-event "loading"))
         (is (= "loading" (get-in (rf.story.ui.state/get-state)
                                  [:cell-overrides :story.x/v :title]))
             "no coercion tag → the raw string, unchanged")))))

#?(:cljs
   (deftest typed-select-option-survives-into-effective-args
     (testing "ACCEPTANCE: the typed option written by the handler is what
               `resolve-args` hands the view — the override is not
               re-stringified downstream"
       (rf.story/reg-variant :story.typed/cell
                             {:tags #{:dev} :setup [] :args {:size :small}})
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.typed/cell [:size] :small
                       {:widget :select :options [:small :large]})
             handler (-> tree second :on-change)]
         (handler (input-event ":large"))
         (let [shell (rf.story.ui.state/get-state)
               eff   (rf.story.args/resolve-args
                       :story.typed/cell
                       {:active-modes   (:active-modes shell)
                        :cell-overrides (get-in shell [:cell-overrides :story.typed/cell])})]
           (is (= :large (:size eff)))
           (is (m/validate [:enum :small :large] (:size eff))
               "the effective arg satisfies the enum schema"))))))
