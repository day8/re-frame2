(ns re-frame.freehand.compiled-select-multiple-cljs-test
  "A `<select multiple>` whose empty `value` React reads as an ARRAY — in the
  compiled browser runtime, and with a RUNTIME-valued `multiple`.

  A native `<select multiple>`'s selection is a list of option values, so its
  controlled EMPTY value is the empty array, not the empty string; React
  reports the wrong shape loudly. The interpreted emitter settles that verdict
  over the runtime attribute map every render. The compiled React emitter had
  settled it only from a BUILD-TIME literal `multiple`, so a runtime-valued
  `:multiple` wrote the scalar empty string for an explicitly nil value — in
  the direct-props path AND in `compiled-react/spread!`'s forwarded map.

  This file pins the compiled RUNTIME shapes both fixes now produce, against
  the interpreted walk's shape for the same map (rf2-sf9n5). The emitter half —
  that the direct-props lowering derives and threads the runtime verdict — is
  `react-lowering-jvm-test/a-runtime-valued-select-multiple-settles-its-empty-value-at-render`.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Controlled inputs,
  and [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiled-react :as cr]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.tree :as tree]))

(defn- as-data
  "A `value` prop read back as ordinary Clojure data — a JS array becomes a
  vector — so a row can say what it means; that it WAS an array is asserted
  separately, because the array is React's own contract here."
  [v]
  (if (array? v) (vec v) v))

(deftest a-runtime-multiple-verdict-shapes-the-nil-value-in-the-direct-props-path
  (testing "The compiled emitter now derives the multiple-select verdict from
            the RUNTIME :multiple and threads it to the nil-value
            normalization — the exact runtime sequence the emitted body runs.
            A true verdict makes an explicitly nil <select> value the empty JS
            ARRAY React requires; a false verdict the empty string."
    (doseq [{:keys [flag value]} [{:flag true :value []} {:flag false :value ""}]]
      (let [verdict (controlled/multiple-select? :select [[:multiple flag]] nil)
            o       (js-obj)]
        (cr/attr! o :select :value nil verdict)
        (is (= value (as-data (gobj/get o "value")))
            (str ":multiple " flag " — the nil value takes its controlled empty shape"))
        (when flag
          (is (array? (gobj/get o "value"))
              "and a multiple select's empty value is a JS array, not a string"))))
    ;; The aliased spellings reach the same verdict and the same value slot.
    (let [verdict (controlled/multiple-select? :select [[:x/multiple true]] nil)
          o       (js-obj)]
      (cr/attr! o :select :x/value nil verdict)
      (is (array? (gobj/get o "value"))
          ":x/multiple decides, and :x/value reaches the same empty-array shape"))))

(deftest a-forwarded-select-multiple-shapes-the-nil-value-in-the-spread-path
  (testing "compiled-react/spread! settled controlledness from the forwarded
            map but wrote the nil value through the arity that DEFAULTS the
            verdict to false, so a forwarded <select multiple> value=nil wrote
            the scalar empty string. It now settles the verdict over the whole
            forwarded map, matching the interpreted walk."
    (doseq [{:keys [m value]} [{:m {:multiple true :value nil}   :value []}
                               {:m {:multiple false :value nil}  :value ""}
                               {:m {:x/multiple true :value nil} :value []}]]
      (let [o (js-obj)]
        (cr/spread! o :select [] "sid" m)
        (is (= value (as-data (gobj/get o "value")))
            (str (pr-str m) " — the forwarded nil value takes its controlled empty shape"))))
    (let [o (js-obj)]
      (cr/spread! o :select [] "sid" {:multiple true :value nil})
      (is (array? (gobj/get o "value"))
          "a forwarded multiple select's empty value is a JS array, not a string"))))

(deftest the-compiled-runtime-shapes-match-the-interpreted-walk
  (testing "The cross-mode claim: the compiled runtime writes the SAME value
            shape the interpreted emitter writes for the same runtime map —
            the empty array when multiple is truthy, the empty string when it
            is not."
    (letfn [(interp [form] (as-data (gobj/get (.-props (fr/element form)) "value")))
            (spread [m] (let [o (js-obj)]
                          (cr/spread! o :select [] "s" m)
                          (as-data (gobj/get o "value"))))]
      (is (= (interp [:select {:multiple true :value nil :on-change [:e]}])
             (spread {:multiple true :value nil})
             [])
          "empty multiple select: both modes write the empty array")
      (is (= (interp [:select {:multiple false :value nil :on-change [:e]}])
             (spread {:multiple false :value nil})
             "")
          "single select: both modes write the empty string"))))

;; ---------------------------------------------------------------------------
;; The safe-spread caller carries :multiple — rf2-sf9n5 gap #1
;; ---------------------------------------------------------------------------
;;
;; `:multiple` is legal in a `(v/spread-safe owned caller)` caller (only
;; key/ref/value/checked and the owned handler families are denied), and the
;; caller folds UNDER the owned props. So an OWNED nil `value` is normalized
;; before the caller's `:multiple` is folded — and the verdict that decides what
;; that empty value IS must already have seen the caller. The structural walk and
;; the interpreted React walk both settle it over the caller now.

(deftest a-safe-spread-caller-multiple-shapes-an-owned-nil-value-structurally
  (testing "The guarded caller's :multiple is a THIRD source of the whole-element
            verdict, settled before the owned nil value is normalized — so
            tree/render answers the empty COLLECTION, not the empty string."
    (is (= [] (get-in (tree/render [:select (v/spread-safe {:value nil} {:multiple true})])
                      [:attrs :value]))
        "owned :value nil + caller :multiple true -> the empty array")
    (is (= [] (get-in (tree/render [:select (v/spread-safe {:x/value nil} {:x/multiple true})])
                      [:attrs :x/value]))
        "the aliased spellings reach the same verdict; the structural tree keeps the author key")
    (is (= "" (get-in (tree/render [:select (v/spread-safe {:value nil} {:multiple false})])
                      [:attrs :value]))
        "the NEGATIVE control: a false caller multiple leaves the empty string")
    (is (= "" (get-in (tree/render [:select (v/spread-safe {:value nil} {:aria-label "L"})])
                      [:attrs :value]))
        "and a caller with no multiple at all is the ordinary single select")
    (is (= [] (get-in (tree/render [:select (v/spread-safe {:value nil :multiple true} {})])
                      [:attrs :value]))
        "the POSITIVE control: keeping both facts owned already yielded the array")))

(deftest a-safe-spread-caller-multiple-shapes-an-owned-nil-value-in-react
  (testing "The interpreted React walk settles the same verdict over the caller
            before writing the owned nil value, so the props object carries the
            JS array React's multiple-select contract requires — matching the
            structural tree and never the scalar empty string."
    (letfn [(react-value [form] (as-data (gobj/get (.-props (fr/element form)) "value")))]
      (is (= [] (react-value [:select (v/spread-safe {:value nil} {:multiple true})]))
          "owned :value nil + caller :multiple true -> the empty array in React props")
      (is (array? (gobj/get (.-props (fr/element [:select (v/spread-safe {:value nil} {:multiple true})]))
                            "value"))
          "and it really is a JS array, not a string")
      (is (= [] (react-value [:select (v/spread-safe {:x/value nil} {:x/multiple true})]))
          "the aliased spellings reach the same shape")
      (is (= "" (react-value [:select (v/spread-safe {:value nil} {:multiple false})]))
          "the NEGATIVE control: a false caller multiple is the empty string")
      (is (= (get-in (tree/render [:select (v/spread-safe {:value nil} {:multiple true})])
                     [:attrs :value])
             (react-value [:select (v/spread-safe {:value nil} {:multiple true})]))
          "the cross-mode claim: structural and interpreted React agree on the shape"))))
