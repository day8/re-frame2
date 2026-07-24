(ns re-frame.freehand.controlled-nil-value-cljs-test
  "An explicitly present `value`/`checked` is CONTROLLED whatever it holds
  — including `nil`.

  The door judges controlled-prop membership by PRESENCE, so
  `[:input {:value nil …}]` takes the synchronous lane. The interpreted
  React emitter then dropped the prop under the generic nil-attribute law
  and handed React an element with no `value` at all. That is not a
  predicate mismatch, it is a contradiction the browser acts on: React
  reads the node as UNCONTROLLED, warns that it changed, and keeps the
  last value it rendered — so clearing a field left the old text on
  screen while the site was already on the synchronous lane.

  The generic law is right and stays: an ordinary `nil` attribute is
  absent, because absent is what an author means by writing nothing. The
  controlled slots are the exception the controlled-input contract already
  states, and the exception is scoped exactly as the door is — the
  supported native control tags, judged on the FINAL-NORMALIZED slot, so
  `:x/value` clears the field precisely as `:value` does.

  The browser half of this claim is
  `re-frame.freehand.controlled-input-dom-cljs-test`, which drives the
  same transition on a live node and reads the DOM back.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Controlled
  inputs."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.react :as fr]))

(defn- props
  "The React props object the interpreted emitter built for `form`."
  [form]
  (.-props (fr/element form)))

(defn- present? [form prop] (gobj/containsKey (props form) prop))
(defn- slot     [form prop] (gobj/get (props form) prop))

;; ---------------------------------------------------------------------------
;; The controlled slots, on the supported native controls
;; ---------------------------------------------------------------------------

(def ^:private cleared-rows
  "Every spelling of a controlled slot that an explicit `nil` must leave
  CONTROLLED and empty. The alternate spellings are here because the rule
  is about the emitted slot: `:x/value` is written into React's own
  `value`, so it is the same prop and takes the same answer."
  [{:note "the paved path"                  :form [:input {:value nil :on-change [:e]}]
    :prop "value"   :empty ""}
   {:note "an aliased :value is that prop"  :form [:input {:x/value nil :on-change [:e]}]
    :prop "value"   :empty ""}
   {:note "checked is the second slot"      :form [:input {:type "checkbox" :checked nil :on-change [:e]}]
    :prop "checked" :empty false}
   {:note "and its aliased spelling"        :form [:input {:type "checkbox" :x/checked nil :on-change [:e]}]
    :prop "checked" :empty false}
   {:note "a textarea is a supported control" :form [:textarea {:value nil :on-change [:e]}]
    :prop "value"   :empty ""}
   {:note "and so is a select"              :form [:select {:value nil :on-change [:e]}]
    :prop "value"   :empty ""}])

(deftest an-explicit-nil-control-value-stays-controlled-and-empty
  (testing "Presence is the whole question. An author who wrote `:value
            nil` declared a controlled field with nothing in it, and React
            must be told so — an omitted prop is React's own signal that
            the node is uncontrolled, which is the opposite claim."
    (doseq [{:keys [note form prop empty]} cleared-rows]
      (is (present? form prop)
          (str note " — " (pr-str form) " keeps its " prop " prop"))
      (is (= empty (slot form prop))
          (str note " — and carries the CONTROLLED empty value")))))

(deftest the-generic-nil-attribute-law-is-untouched
  (testing "Only the controlled slots on a supported native control are
            excepted. Everything else keeps the law an author relies on:
            a nil attribute is an absent attribute, so a conditional value
            reads as 'do not set this'."
    (is (not (present? [:input {:title nil :value "x" :on-change [:e]}] "title"))
        "an ordinary nil attribute is still omitted")
    (is (not (present? [:input {:default-value nil :on-change [:e]}] "defaultValue"))
        ":default-value seeds an UNCONTROLLED input and is not a controlled slot")
    (is (not (present? [:div {:value nil}] "value"))
        "a :div has no value React restores, so nothing is excepted for it")
    (is (not (present? [:my-field {:value nil}] "value"))
        "and a custom element's value is its own adapter's protocol")
    (is (not (present? [:input {:on-change [:e]}] "value"))
        "an ABSENT key is still absent — the exception is about presence")))

(deftest a-nonnil-control-value-is-untouched
  (testing "The exception fires only where a nil would otherwise be
            erased. An ordinary value takes the ordinary conversion."
    (is (= "seed" (slot [:input {:value "seed" :on-change [:e]}] "value")))
    (is (= true (slot [:input {:type "checkbox" :checked true :on-change [:e]}] "checked")))
    (is (= false (slot [:input {:type "checkbox" :checked false :on-change [:e]}] "checked"))
        "present-false was already distinguishable from absent")))

;; ---------------------------------------------------------------------------
;; The one control whose value is COLLECTION-shaped
;; ---------------------------------------------------------------------------

(defn- js-value
  "A `value` prop read back as ordinary Clojure data, so a row can say what
  it means. A JavaScript array becomes a vector — and the fact that it WAS
  an array is asserted separately, because that is React's own contract for
  a multiple select and the thing an emitter gets wrong."
  [form]
  (let [v (slot form "value")]
    (if (array? v) (vec v) v)))

(deftest a-multiple-selects-value-reaches-react-as-an-array
  (testing "A native `<select multiple>` is the single element whose value
            React reads as a COLLECTION: its selection is the list of
            chosen option values, and React reports any other shape. The
            author writes ordinary Clojure data and the emitter converts at
            the boundary — the last step, not a second grammar."
    (let [form [:select {:multiple true :value ["a" "b"] :on-change [:e]}]]
      (is (array? (slot form "value")) "the value prop IS a JavaScript array")
      (is (= ["a" "b"] (js-value form)) "carrying the authored option values, in order"))
    (is (= ["a" "1"] (js-value [:select {:multiple true :value [:a 1] :on-change [:e]}]))
        "whose members took the ordinary attribute-value conversion")))

(deftest an-explicit-nil-on-a-multiple-select-is-the-empty-array
  (testing "The controlled EMPTY value of a collection-shaped slot is the
            empty collection. The empty string clears a scalar field and is
            a shape error here — React says so, loudly — while an omitted
            prop is the uncontrolled claim the door already contradicted."
    (doseq [form [[:select {:multiple true :value nil :on-change [:e]}]
                  [:select {:multiple true :x/value nil :on-change [:e]}]
                  [:select {:x/multiple true :value nil :on-change [:e]}]]]
      (is (present? form "value") (str (pr-str form) " keeps its value prop"))
      (is (array? (slot form "value"))
          (str (pr-str form) " — as an array, which is what React requires here"))
      (is (= [] (js-value form)) (str (pr-str form) " — and it selects nothing")))))

(deftest a-single-select-is-untouched-by-the-collection-rule
  (testing "The control that makes the rule mean something. Without
            `multiple` a select's value is a scalar, and its controlled
            empty value is the empty STRING exactly as it was — React
            reports an array there as the wrong shape."
    (is (= "" (slot [:select {:value nil :on-change [:e]}] "value"))
        "an explicit nil still clears a single select with the empty string")
    (is (= "" (slot [:select {:multiple false :value nil :on-change [:e]}] "value"))
        "and an explicitly FALSE multiple is a single select")
    (is (= "a" (slot [:select {:value "a" :on-change [:e]}] "value"))
        "a scalar value is untouched")
    (is (not (present? [:div {:multiple true :value nil}] "value"))
        "and nothing is excepted for an element that is not a native control")))

(deftest an-ordinary-collection-attribute-value-is-still-refused
  (testing "Widening ONE slot on ONE tag is not a general collection
            attribute grammar. Everywhere else a collection still has no
            attribute spelling, and inside the exception the members take
            the same closed scalar grammar — a set among them, because its
            read order is not a value the two hosts agree on."
    (doseq [form [[:div {:value ["a"]}]
                  [:input {:value ["a"]}]
                  [:select {:multiple true :title ["a"]}]
                  [:select {:multiple true :value #{"a"}}]
                  [:select {:multiple true :value {:a 1}}]]]
      (let [ex (try (fr/element form) nil (catch :default e e))]
        (is (some? ex) (str (pr-str form) " is refused"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str (pr-str form) " — the walk's existing diagnostic, not a new one")))))))

;; ---------------------------------------------------------------------------
;; The verdict and the emission are ONE decision
;; ---------------------------------------------------------------------------

(deftest the-door-verdict-and-the-emitted-props-agree
  (testing "The door reads KEYS and never values, so both execution modes
            call an explicitly nil `value` controlled. The emitter has to
            write props that say the same thing — a site on the synchronous
            lane whose node React reads as uncontrolled is the two halves
            of one declaration disagreeing, which is the failure the shared
            predicate exists to make impossible."
    (doseq [k [:value :x/value :checked :x/checked]]
      (is (true? (controlled/controlled-props? [k]))
          (str "the door calls " k " controlled"))
      (is (present? [:input {k nil :on-change [:e]}]
                    (controlled/prop-slot k))
          (str "so the emitter writes the " (controlled/prop-slot k)
               " prop for " k " too")))
    (doseq [k [:default-value :title]]
      (is (false? (controlled/controlled-props? [k]))
          (str "the door does not call " k " controlled"))
      (is (not (present? [:input {k nil :on-change [:e]}]
                         (controlled/prop-slot k)))
          (str "so the generic omission stands for " k)))))
