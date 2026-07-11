(ns re-frame.ui.rules-cljs-test
  "The DOM conversion rule table, row by row (owning contract:
  jvm-tree-and-conversion-contract.md §The DOM conversion table). The
  [S1-CONFIRM] provenance lives with each set in re-frame.ui.rules; this
  suite pins the row SEMANTICS on both hosts."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Attribute names (author kebab -> React prop)
;; ---------------------------------------------------------------------------

(deftest attribute-name-rows
  (is (= "className" (rules/react-prop-name "class")))
  (is (= "htmlFor"   (rules/react-prop-name "for")))
  (is (= "tabIndex"  (rules/react-prop-name "tab-index"))   "hyphen-collapse")
  (is (= "readOnly"  (rules/react-prop-name "read-only")))
  (is (= "charSet"   (rules/react-prop-name "charset")))
  (is (= "acceptCharset" (rules/react-prop-name "accept-charset")))
  (is (= "httpEquiv" (rules/react-prop-name "http-equiv")))
  (is (= "data-fooBar" (rules/react-prop-name "data-fooBar")) "data-* verbatim, casing preserved")
  (is (= "aria-hidden" (rules/react-prop-name "aria-hidden")) "aria-* verbatim")
  (is (= "foo-bar" (rules/react-prop-name "foo-bar"))
      "unrecognized names pass verbatim (React 16+ behaviour) — no blind camelization")
  (is (= "ismap" (rules/react-prop-name "ismap"))
      "React 19 dropped camel isMap; the author spelling is :ismap"))

(deftest svg-alias-rows
  (is (= "viewBox"     (rules/react-prop-name "view-box")))
  (is (= "stroke-width" (name :stroke-width)) "author space stays kebab")
  (is (= "strokeWidth" (rules/react-prop-name "stroke-width"))
      "mirrors possibleStandardNames ([S1-CONFIRM] row 1)")
  (is (= "strokeDasharray" (rules/react-prop-name "stroke-dasharray")))
  (is (= "clipPath"    (rules/react-prop-name "clip-path")))
  (is (= "horizAdvX"   (rules/react-prop-name "horiz-adv-x")))
  (is (= "xHeight"     (rules/react-prop-name "x-height"))))

(deftest xlink-xml-rows
  (is (= "xlinkHref" (rules/react-prop-name "xlink-href")) "[S1-CONFIRM] row 3")
  (is (= "xmlLang"   (rules/react-prop-name "xml-lang")))
  (is (= "xmlSpace"  (rules/react-prop-name "xml-space"))))

(deftest event-name-rows
  (is (= "onClick"   (rules/react-event-name "on-click")))
  (is (= "onKeyDown" (rules/react-event-name "on-key-down")))
  (is (= "onClickCapture" (rules/react-event-name "on-click" true))
      "capture is a listener option -> React Capture suffix"))

;; ---------------------------------------------------------------------------
;; :style
;; ---------------------------------------------------------------------------

(deftest style-px-rule
  (is (= "16px" (rules/css-val->str "padding" 16)))
  (is (= "0"    (rules/css-val->str "padding" 0))    "0 stays 0")
  (is (= "0.5"  (rules/css-val->str "opacity" 0.5))  "unitless set")
  (is (= "3"    (rules/css-val->str "z-index" 3)))
  (is (= "1.5"  (rules/css-val->str "line-height" 1.5)))
  (is (= "2"    (rules/css-val->str "stroke-width" 2)) "SVG stroke props are unitless")
  (is (= "4"    (rules/css-val->str "--main-gap" 4))
      "custom properties: verbatim, no px rule ([S1-CONFIRM] row 9)")
  (is (= "red"  (rules/css-val->str "color" :red)) "keyword values via name"))

(deftest style-react-names
  (is (= "lineHeight" (rules/react-style-name "line-height")))
  (is (= "--main-gap" (rules/react-style-name "--main-gap")) "custom properties verbatim")
  (is (= "WebkitLineClamp" (rules/react-style-name "-webkit-line-clamp")))
  (is (= "msFlex" (rules/react-style-name "-ms-flex")))
  (is (= "MozBoxFlex" (rules/react-style-name "-moz-box-flex"))))

(deftest unitless-set-is-reacts
  ;; version-pinned to react-dom 19.2.0's unitlessNumbers (71 entries)
  (is (= 71 (count rules/unitless-css)))
  (is (contains? rules/unitless-css "aspect-ratio"))
  (is (contains? rules/unitless-css "-webkit-line-clamp")))

;; ---------------------------------------------------------------------------
;; :class composition
;; ---------------------------------------------------------------------------

(deftest class-rows
  (is (= "a b" (rules/class-val "a b")) "string verbatim")
  (is (= "x"   (rules/class-val :x)))
  (is (= "a c" (rules/class-val ["a" nil :c])) "vector order, nils dropped")
  (is (= "alpha beta" (rules/class-val {:beta true :alpha true :off false}))
      "flag maps render truthy names in LEXICOGRAPHIC order")
  (is (nil? (rules/class-val nil)))
  (is (nil? (rules/class-val "")))
  (is (= "base x" (rules/classes-str ["base" nil "" "x"]))))

;; ---------------------------------------------------------------------------
;; Numbers (JS ToString) + key coercion
;; ---------------------------------------------------------------------------

(deftest js-number-rows
  (is (= "1"   (rules/js-number-str 1.0)) "[S1-CONFIRM] row 10: no trailing .0")
  (is (= "2.5" (rules/js-number-str 2.5)))
  (is (= "0"   (rules/js-number-str 0)))
  (is (= "0"   (rules/js-number-str -0.0)) "String(-0) is \"0\"")
  (is (= "42"  (rules/js-number-str 42)))
  (is (= "NaN" (rules/js-number-str ##NaN)))
  (is (= "Infinity" (rules/js-number-str ##Inf))))

(deftest key-string-coercion
  (is (= (rules/js-string-coerce 1) (rules/js-string-coerce "1"))
      "[S1-CONFIRM] row 11: key 1 collides with key \"1\"")
  (is (not= (rules/js-string-coerce :a) (rules/js-string-coerce "a"))
      "keywords carry their colon — no accidental collision with strings"))

;; ---------------------------------------------------------------------------
;; Booleans + escaping + voids (serialiser-half data rows, probed)
;; ---------------------------------------------------------------------------

(deftest boolean-set-rows
  (is (contains? rules/boolean-attrs "checked"))
  (is (contains? rules/boolean-attrs "inert"))
  (is (contains? rules/boolean-attrs "ismap"))
  (is (contains? rules/boolean-attrs "hidden")
      "hidden is pure boolean for this React pin — 19.2.0 renders
       hidden=\"until-found\" as bare presence (contract row CORRECTED)")
  (is (= #{"contenteditable" "draggable" "spellcheck"} rules/booleanish-attrs))
  (is (= #{"download" "capture"} rules/overloaded-boolean-attrs))
  (is (= #{} rules/property-only-attrs)
      "muted probe: react-dom/server 19.2.0 DOES serialise muted=\"\" —
       the contract draft's property-only citizen is corrected away"))

(deftest void-rows
  (is (contains? rules/void-tags :param) "React 19 throw-list parity (+param)")
  (is (contains? rules/void-tags :keygen) "(+keygen)")
  (is (not (contains? rules/void-tags :menuitem)))
  (is (contains? rules/children-rejected-tags :menuitem)
      "menuitem rejects children but is not self-closing"))

(deftest escaping-row
  (is (= "&amp;&lt;&gt;&quot;&#x27;" (rules/escape-html "&<>\"'"))
      "full 5-char escaping; ui/html is the single bypass"))

;; ---------------------------------------------------------------------------
;; Namespace context rows ([S1-CONFIRM] row 2)
;; ---------------------------------------------------------------------------

(deftest namespace-context-rows
  (is (= :svg (rules/child-ns-context nil :svg {})))
  (is (= :svg (rules/child-ns-context :svg :g {})) "descendants inherit")
  (is (nil? (rules/child-ns-context :svg :foreignObject {}))
      "foreignObject's children revert to HTML")
  (is (= :mathml (rules/child-ns-context nil :math {})))
  (is (nil? (rules/child-ns-context :mathml :annotation-xml {:encoding "text/html"}))
      "annotation-xml HTML island (text/html)")
  (is (nil? (rules/child-ns-context :mathml :annotation-xml
                                    {:encoding "application/xhtml+xml"})))
  (is (= :mathml (rules/child-ns-context :mathml :annotation-xml {:encoding "other"})))
  (is (nil? (rules/element-ns nil)) ":ns is absent for HTML — canonical form")
  (is (= :svg (rules/element-ns :svg))))

;; ---------------------------------------------------------------------------
;; Custom elements
;; ---------------------------------------------------------------------------

(deftest custom-element-rows
  (is (= "helpText" (rules/custom-element-property-name "help-text"))
      "declared names -> camelCase JS property (RULED grammar)")
  (rules/register-custom-element! :rules-test-el {:properties #{:some-prop}})
  (is (= #{:some-prop} (rules/custom-element-properties :rules-test-el)))
  (is (= #{} (rules/custom-element-properties :never-declared-el))
      "undeclared elements default to all-attributes"))
