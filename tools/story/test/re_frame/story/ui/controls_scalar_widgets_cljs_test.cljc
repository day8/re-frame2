(ns re-frame.story.ui.controls-scalar-widgets-cljs-test
  "Tests for the Story Controls panel's scalar widget vocabulary.

  Per /spec/007-Stories.md §argtypes the closed control vocabulary is
  `:text` / `:textarea` / `:number` / `:boolean` / `:select` / `:radio`
  / `:date` / `:color`. These tests pin the rendered hiccup and the
  on-change writes through the shell-state's `:cell-overrides` slot.

  CLJS-only — the renderer is CLJS-only (it depends on Reagent / DOM
  event objects). Lives in a `.cljc` for symmetry with sibling tests.

  Runs under shadow's `:node-test` target (the `cljs-test$` ns regex
  picks up this name)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            #?(:cljs [re-frame.story :as rf.story])
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
     "A minimal change-event object whose `.-target.-value` is `v`."
     [v]
     #js {:target #js {:value v}}))

#?(:cljs
   (defn- written
     "The override the shell state holds for `:story.x/v` at `path`."
     [path]
     (get-in (rf.story.ui.state/get-state) (into [:cell-overrides :story.x/v] path))))

#?(:cljs
   (defn- radio-inputs [tree]
     (filter (fn [n]
               (and (vector? n)
                    (= :input (first n))
                    (= "radio" (-> n second :type))))
             (tree-seq vector? rest tree))))

;; ---- :textarea / :date / :color -----------------------------------------

#?(:cljs
   (deftest textarea-widget-renders-value-and-nil-as-empty
     (testing "nil becomes \"\" to satisfy React's controlled-component contract"
       (is (= ["hello" ""]
              (mapv #(-> (rf.story.ui.controls/scalar-widget
                           :story.x/v [:bio] % {:widget :textarea})
                         second :value)
                    ["hello" nil]))))))

#?(:cljs
   (deftest date-widget-renders-and-writes-iso-or-nil
     (let [tree    (rf.story.ui.controls/scalar-widget
                     :story.x/v [:dob] "2026-05-14" {:widget :date})
           handler (-> tree second :on-change)]
       (is (= ["date" "2026-05-14"] ((juxt :type :value) (second tree))))
       (is (= "" (-> (rf.story.ui.controls/scalar-widget
                       :story.x/v [:dob] nil {:widget :date})
                     second :value)))
       (handler (input-event "2026-12-31"))
       (is (= "2026-12-31" (written [:dob])))
       (testing "an empty string writes nil — 'no date selected' — not an absent key"
         (handler (input-event ""))
         (is (nil? (written [:dob])))
         (is (contains? (written []) :dob))))))

#?(:cljs
   (deftest color-widget-renders-hex-and-nil-as-black
     (testing "<input type=\"color\"> rejects any non-hex value, so nil falls
               back to #000000 rather than \"\""
       (let [render #(second (rf.story.ui.controls/scalar-widget
                               :story.x/v [:bg] % {:widget :color}))]
         (is (= ["color" "#ff0000"] ((juxt :type :value) (render "#ff0000"))))
         (is (= "#000000" (:value (render nil))))))))

;; ---- :radio --------------------------------------------------------------

#?(:cljs
   (deftest radio-widget-renders-checks-and-writes-the-source-option
     (let [tree   (rf.story.ui.controls/scalar-widget
                    :story.x/v [:variant] :secondary
                    {:widget :radio :options [:primary :secondary :danger]})
           inputs (vec (radio-inputs tree))]
       (testing "a named radiogroup container"
         (is (= ["radiogroup" ":variant"] ((juxt :role :aria-label) (second tree)))))
       (testing "one input per option, in order, only the current one checked"
         (is (= [[":primary" false] [":secondary" true] [":danger" false]]
                (mapv (comp (juxt :value :checked) second) inputs))))
       (testing "on-change writes the raw option, not its stringified form"
         ((-> inputs (nth 2) second :on-change) (input-event ":danger"))
         (is (= :danger (written [:variant])))))))

#?(:cljs
   (deftest radio-widget-name-attribute-isolates-groups
     (testing "distinct paths give distinct `:name`s, so two radio groups
               don't toggle each other"
       (let [group-name (fn [path]
                          (-> (rf.story.ui.controls/scalar-widget
                                :story.x/v path nil {:widget :radio :options [:x :y]})
                              radio-inputs first second :name))]
         (is (some? (group-name [:a])))
         (is (not= (group-name [:a]) (group-name [:b])))))))

;; ---- unknown widget fallback --------------------------------------------

#?(:cljs
   (deftest unknown-widget-still-renders-fallback-span
     (testing "an unknown widget tag renders the inline fallback span"
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:k] "v" {:widget :ratchet})]
         (is (= :span (first tree)))
         (is (re-find #"unsupported widget"
                      (nth tree 2)))))))

;; ---- arg-widget dispatch hands scalar specs to scalar-widget ------------

#?(:cljs
   (deftest arg-widget-hands-scalar-widget-specs-through
     (is (= [rf.story.ui.controls/scalar-widget :story.x/v [:k] nil {:widget :textarea}]
            (rf.story.ui.controls/arg-widget :story.x/v [:k] nil {:widget :textarea})))))

;; ---- aria-label on every scalar widget ----------------------------------
;;
;; The row-level label span is purely visual; without an `:aria-label`
;; screen readers announce the input as 'edit, blank'.

#?(:cljs
   (deftest each-scalar-widget-carries-aria-label
     (doseq [[w expected-tag]
             [[{:widget :text}                    :input]
              [{:widget :textarea}                :textarea]
              [{:widget :number}                  :input]
              [{:widget :boolean}                 :input]
              [{:widget :select :options [:a :b]} :select]
              [{:widget :date}                    :input]
              [{:widget :color}                   :input]]]
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:username] "x" w)]
         (is (= [expected-tag ":username"] [(first tree) (-> tree second :aria-label)])
             (str (:widget w) " renders its tag with an :aria-label naming the path"))))))

#?(:cljs
   (deftest nested-path-aria-label-is-breadcrumb
     (testing "a nested path produces a slash-joined breadcrumb
               so a nested input is announced as 'address / street'
               rather than just 'street'."
       (let [tree (rf.story.ui.controls/scalar-widget
                    :story.x/v [:address :street] "Main St" {:widget :text})]
         (is (= ":address / :street" (-> tree second :aria-label)))))))

;; ---- typed values survive the DOM adapter ---------------------------------
;;
;; A widget the schema generated must write a value that schema accepts.
;; `<option value>` can only carry a string, so a select writing the raw DOM
;; string would turn `:large` into `":large"`.

#?(:cljs
   (deftest select-widget-on-change-writes-the-numeric-option
     (let [tree    (rf.story.ui.controls/scalar-widget
                     :story.x/v [:cols] 1 {:widget :select :options [1 2 3]})
           handler (-> tree second :on-change)]
       (handler (input-event "3"))
       (is (= 3 (written [:cols])) "a number, not the string \"3\""))))

#?(:cljs
   (deftest select-widget-leaves-string-options-as-strings
     (testing ":select does not over-coerce — a string option stays a string"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:label] "a" {:widget :select :options ["a" "b"]})
             handler (-> tree second :on-change)]
         (handler (input-event "b"))
         (is (= "b" (written [:label])))))))

#?(:cljs
   (deftest select-widget-ignores-a-token-naming-no-option
     (testing ":select writes nothing when the chosen token names no option
               — no string leaks into the override as a fallback"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:size] :small
                       {:widget :select :options [:small :large]})
             handler (-> tree second :on-change)]
         (handler (input-event ":enormous"))
         (is (nil? (written [:size])))))))

#?(:cljs
   (deftest select-widget-nested-path-writes-the-typed-option
     (testing "the coercion lives in the widget, so a NESTED path writes the
               typed option too"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:theme :mode] :light
                       {:widget :select :options [:light :dark]})
             handler (-> tree second :on-change)]
         (handler (input-event ":dark"))
         (is (= :dark (written [:theme :mode])))))))

;; ---- a scalar schema carrying properties keeps its type -------------------

#?(:cljs
   (deftest infer-widget-property-carrying-scalars-infer-as-their-keyword
     (testing "a scalar schema with Malli properties infers the widget its
               bare keyword does, never the untyped text fallback"
       (is (= {:widget :text :coerce :keyword}
              (rf.story.ui.controls/infer-widget [:keyword {:doc "status"}])))
       (is (= {:widget :number} (rf.story.ui.controls/infer-widget [:maybe :int]))
           "[:maybe X] infers as X"))))

#?(:cljs
   (deftest bounded-int-control-writes-a-number-its-schema-accepts
     (testing "the flagship [:int {:min 8 :max 64}] prop's GENERATED control
               writes a number, not the string \"24\""
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:size] 16
                       (rf.story.ui.controls/infer-widget [:int {:min 8 :max 64}]))
             handler (-> tree second :on-change)]
         (handler (input-event "24"))
         (is (= 24 (written [:size])))))))

#?(:cljs
   (deftest keyword-text-widget-on-change-writes-a-keyword
     (testing "a schema-derived :keyword text field edits to a KEYWORD, so
               an ordinary keyword argument round-trips through its own
               inferred editor"
       (let [spec    (rf.story.ui.controls/infer-widget :keyword)
             tree    (rf.story.ui.controls/scalar-widget :story.x/v [:status] :idle spec)
             handler (-> tree second :on-change)]
         (handler (input-event "loading"))
         (is (= :loading (written [:status])))))))

#?(:cljs
   (deftest plain-text-widget-still-writes-a-string
     (testing "an untagged :text widget does no coercion — actual string
               args stay strings"
       (let [tree    (rf.story.ui.controls/scalar-widget
                       :story.x/v [:title] "" {:widget :text})
             handler (-> tree second :on-change)]
         (handler (input-event "loading"))
         (is (= "loading" (written [:title])))))))
