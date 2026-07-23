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
