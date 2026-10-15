(ns re-frame.fresco.slot-cljs-test
  "The same authored prop keys produce canonical React slots on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.fresco.impl.slot :as rf.fresco.impl.slot]))

(def corpus
  "Prop keys and their canonical React slots."
  {
   :class          "className"
   :className      "className"
   "class"         "className"
   :x/class        "className"
   'class          "className"
   :for            "htmlFor"
   "for"           "htmlFor"
   :x/for          "htmlFor"
   :charset        "charSet"
   'charset        "charSet"

   :on-click       "onClick"
   :tab-index      "tabIndex"
   :on-mouse-enter "onMouseEnter"
   :default-value  "defaultValue"
   :a-b-c          "aBC"
   'on-click       "onClick"
   :x/on-click     "onClick"

   :view-Box       "viewBox"
   :on-Click       "onClick"

   :x              "x"
   :onInput        "onInput"
   :viewBox        "viewBox"
   :id             "id"
   :ref            "ref"
   :key            "key"

   :aria-label     "aria-label"
   :aria-hidden    "aria-hidden"
   :data-index     "data-index"
   :data-testid    "data-testid"
   'aria-label     "aria-label"
   :x/data-index   "data-index"

   :--gap          "--gap"
   :--my-custom-x  "--my-custom-x"

   "on-input"      "on-input"
   :on-input       "onInput"
   "onInput"       "onInput"
   "data-x"        "data-x"
   "--gap"         "--gap"
   "aria-label"    "aria-label"

   :__proto__      "__proto__"
   :constructor    "constructor"})

(deftest the-slot-rule-answers-the-corpus
  (doseq [[k expected] corpus]
    (is (= expected (rf.fresco.impl.slot/prop-name k))
        (str "slot for " (pr-str k)))))
