(ns re-frame.story.ui.controls-scalar-widgets-cljs-test
  "Tests for the Story Controls panel's scalar widget vocabulary
  (rf2-viymg).

  Per spec/007-Stories.md §argtypes the closed control vocabulary is
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
            #?(:cljs [re-frame.story :as story])
            #?(:cljs [re-frame.story.ui.controls :as controls])
            [re-frame.story.ui.state :as state]))

;; ---- fixtures ------------------------------------------------------------

#?(:cljs
   (defn reset-fixture [test-fn]
     (story/clear-all!)
     (state/reset-shell-state!)
     (story/install-canonical-vocabulary!)
     (test-fn))
   :clj
   (defn reset-fixture [test-fn]
     (state/reset-shell-state!)
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
       (let [tree (controls/scalar-widget
                    :story.x/v [:bio] "hello" {:widget :textarea})]
         (is (= :textarea (first tree)))
         (is (= "hello"   (-> tree second :value)))
         (is (fn?         (-> tree second :on-change)))))))

#?(:cljs
   (deftest textarea-widget-nil-value-becomes-empty-string
     (testing ":textarea coerces a nil value to \"\" to satisfy React's
               controlled-component contract"
       (let [tree (controls/scalar-widget
                    :story.x/v [:bio] nil {:widget :textarea})]
         (is (= "" (-> tree second :value)))))))

#?(:cljs
   (deftest textarea-widget-on-change-writes-override
     (testing ":textarea on-change writes through to :cell-overrides"
       (let [tree     (controls/scalar-widget
                        :story.x/v [:bio] "" {:widget :textarea})
             handler  (-> tree second :on-change)]
         (handler (input-event "multi\nline\ntext"))
         (is (= "multi\nline\ntext"
                (get-in (state/get-state)
                        [:cell-overrides :story.x/v :bio])))))))

;; ---- :radio --------------------------------------------------------------

#?(:cljs
   (deftest radio-widget-renders-one-input-per-option
     (testing ":radio widget renders one <input type=\"radio\"> per option"
       (let [tree   (controls/scalar-widget
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
       (let [tree   (controls/scalar-widget
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
       (let [tree   (controls/scalar-widget
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
                (get-in (state/get-state)
                        [:cell-overrides :story.x/v :variant])))))))

#?(:cljs
   (deftest radio-widget-name-attribute-isolates-groups
     (testing "each radio group's <input> shares a `:name` derived from
               variant-id + path — distinct paths mean distinct names so
               two radio groups don't toggle each other"
       (let [tree-a (controls/scalar-widget
                      :story.x/v [:a] nil
                      {:widget :radio :options [:x :y]})
             tree-b (controls/scalar-widget
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
       (let [tree (controls/scalar-widget
                    :story.x/v [:dob] "2026-05-14" {:widget :date})]
         (is (= :input (first tree)))
         (is (= "date" (-> tree second :type)))
         (is (= "2026-05-14" (-> tree second :value)))))))

#?(:cljs
   (deftest date-widget-nil-value-becomes-empty-string
     (testing ":date coerces nil to \"\" to satisfy the controlled-input
               contract"
       (let [tree (controls/scalar-widget
                    :story.x/v [:dob] nil {:widget :date})]
         (is (= "" (-> tree second :value)))))))

#?(:cljs
   (deftest date-widget-on-change-writes-iso-string
     (testing ":date on-change writes the raw ISO yyyy-mm-dd string"
       (let [tree    (controls/scalar-widget
                       :story.x/v [:dob] nil {:widget :date})
             handler (-> tree second :on-change)]
         (handler (input-event "2026-12-31"))
         (is (= "2026-12-31"
                (get-in (state/get-state)
                        [:cell-overrides :story.x/v :dob])))))))

#?(:cljs
   (deftest date-widget-on-change-empty-writes-nil
     (testing ":date on-change with an empty string clears the slot to nil
               — equivalent to 'no date selected'"
       (let [tree    (controls/scalar-widget
                       :story.x/v [:dob] "2026-05-14" {:widget :date})
             handler (-> tree second :on-change)]
         (handler (input-event ""))
         (is (nil? (get-in (state/get-state)
                           [:cell-overrides :story.x/v :dob])))
         (is (contains? (get-in (state/get-state)
                                [:cell-overrides :story.x/v])
                        :dob))))))

;; ---- :color --------------------------------------------------------------

#?(:cljs
   (deftest color-widget-renders-color-input
     (testing ":color widget renders <input type=\"color\">"
       (let [tree (controls/scalar-widget
                    :story.x/v [:bg] "#ff0000" {:widget :color})]
         (is (= :input (first tree)))
         (is (= "color" (-> tree second :type)))
         (is (= "#ff0000" (-> tree second :value)))))))

#?(:cljs
   (deftest color-widget-nil-value-becomes-black
     (testing ":color falls back to a valid #000000 when the slot is nil —
               <input type=\"color\"> rejects any non-hex value, so empty
               strings can't be used"
       (let [tree (controls/scalar-widget
                    :story.x/v [:bg] nil {:widget :color})]
         (is (= "#000000" (-> tree second :value)))))))

#?(:cljs
   (deftest color-widget-on-change-writes-hex
     (testing ":color on-change writes the picker's hex string verbatim"
       (let [tree    (controls/scalar-widget
                       :story.x/v [:bg] "#000000" {:widget :color})
             handler (-> tree second :on-change)]
         (handler (input-event "#abcdef"))
         (is (= "#abcdef"
                (get-in (state/get-state)
                        [:cell-overrides :story.x/v :bg])))))))

;; ---- unknown widget fallback --------------------------------------------

#?(:cljs
   (deftest unknown-widget-still-renders-fallback-span
     (testing "an unknown widget tag renders the inline fallback span —
               this is the existing contract and must survive the new
               widget additions"
       (let [tree (controls/scalar-widget
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
               sub-spec (last (controls/arg-widget
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
         (let [tree (controls/scalar-widget
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
       (let [tree (controls/scalar-widget
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
       (let [tree (controls/scalar-widget
                    :story.x/v [:address :street] "Main St" {:widget :text})]
         (is (re-find #"address" (-> tree second :aria-label)))
         (is (re-find #"street"  (-> tree second :aria-label)))))))
