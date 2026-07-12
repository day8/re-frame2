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
  (rules/register-custom-element! :rules-test-el {:properties #{:some-prop}}
                                  're-frame.ui.rules-test)
  (is (= #{:some-prop} (rules/custom-element-properties :rules-test-el)))
  (is (= #{} (rules/custom-element-properties :never-declared-el))
      "undeclared elements default to all-attributes"))

;; ---------------------------------------------------------------------------
;; Custom-element runtime reconciliation registry — the reload-cycle protocol
;; (rf2-vxgfnd.67, residual of .48). The RUNTIME arm of .16 AC5; the compile/JVM
;; arm lives in re-frame.ui.compiler-build-jvm-test. Eviction is driven from the
;; reload BOUNDARY (notify-reload! / commit-reload!), NOT from a surviving
;; re-registration — so a source that now declares zero elements, or whose file
;; is deleted, is reconciled away too.
;; ---------------------------------------------------------------------------

(deftest edit-reload-replaces-a-source-contribution-atomically
  ;; AC3: a rename — app.a's :x-el becomes :y-el — replaces the WHOLE source
  ;; contribution on commit; the live union never retains the stale :x-el after.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
  (is (= #{:help-text} (rules/custom-element-properties :x-el))
      "initial load classifies :help-text as a property (direct write-through)")
  (rules/notify-reload!)                                       ; ^:dev/before-load
  (rules/register-custom-element! :y-el {:properties #{:label}} 'app.a) ; stages
  (is (= #{:help-text} (rules/custom-element-properties :x-el))
      "mid-cycle the live aggregate stays last-known-good (staged, not committed)")
  (rules/commit-reload!)                                       ; ^:dev/after-load
  (is (= #{} (rules/custom-element-properties :x-el))
      "on commit the renamed-away :x-el is evicted (was leaking until page reload)")
  (is (= #{:label} (rules/custom-element-properties :y-el))
      "the current :y-el declaration classifies its property"))

(deftest zero-declaration-reload-evicts-the-whole-source
  ;; AC1: app.a removes its LAST custom-element declaration. On reload it
  ;; re-runs but registers NOTHING, so .48's re-registration trigger never
  ;; fired and :x-el leaked. The reload machinery notes app.a re-ran; commit
  ;; evicts its now-empty contribution without a page reload.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
  (is (= #{:help-text} (rules/custom-element-properties :x-el)))
  (rules/notify-reload!)
  (rules/note-reloaded-sources! ['app.a])   ; app.a re-ran (its build hook says so)
  ;; ...and registered no declarations this cycle.
  (rules/commit-reload!)
  (is (= #{} (rules/custom-element-properties :x-el))
      "a source that drops its last declaration is evicted (residual .48 gap)")
  (is (= {} @rules/custom-elements)
      "no stale rows survive the zero-declaration reload"))

(deftest deleted-source-file-is-reconciled-away
  ;; AC2: app.a's FILE is deleted — it does not reload at all, but the reload
  ;; machinery reports it removed. app.b is untouched and keeps its rows.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:a-prop}} 'app.a)
  (rules/register-custom-element! :b-el {:properties #{:b-prop}} 'app.b)
  (rules/notify-reload!)
  (rules/note-reloaded-sources! ['app.a])   ; app.a's file removed this cycle
  (rules/commit-reload!)
  (is (= #{} (rules/custom-element-properties :a-el))
      "the deleted source's former contribution is reconciled away")
  (is (= #{:b-prop} (rules/custom-element-properties :b-el))
      "an untouched source keeps its classification across the reload"))

(deftest hot-reload-preserves-untouched-sources
  ;; A reload cycle that reloads only source B must NOT drop source A's rows —
  ;; A did not re-run, so it is neither staged nor touched (the runtime mirror of
  ;; compile-side incremental-preserves-untouched).
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:a-prop}} 'app.a)
  (rules/register-custom-element! :b-el {:properties #{:b-prop}} 'app.b)
  (rules/notify-reload!)
  (rules/register-custom-element! :b-el {:properties #{:b-prop2}} 'app.b) ; only B reloads
  (rules/commit-reload!)
  (is (= #{:a-prop} (rules/custom-element-properties :a-el))
      "untouched source A keeps its classification across B's reload")
  (is (= #{:b-prop2} (rules/custom-element-properties :b-el))
      "reloaded source B's classification updates"))

(deftest multiple-declarations-in-one-source-accumulate-then-replace
  ;; Two elements in one source accumulate in the staged cycle; on commit the
  ;; whole contribution replaces, so a dropped declaration is evicted even though
  ;; the source no longer touches it (never clear-on-first-declaration).
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:a-prop}} 'app.multi)
  (rules/register-custom-element! :b-el {:properties #{:b-prop}} 'app.multi)
  (is (= #{:a-prop} (rules/custom-element-properties :a-el)))
  (is (= #{:b-prop} (rules/custom-element-properties :b-el)))
  ;; reload app.multi keeping only :a-el (with a changed decl); :b-el deleted
  (rules/notify-reload!)
  (rules/register-custom-element! :a-el {:properties #{:a-prop2}} 'app.multi)
  (rules/commit-reload!)
  (is (= #{:a-prop2} (rules/custom-element-properties :a-el))
      "the surviving declaration's classification updates")
  (is (= #{} (rules/custom-element-properties :b-el))
      "the dropped :b-el is evicted even though app.multi no longer touches it"))

(deftest aborted-reload-retains-the-last-known-good-manifest
  ;; AC4: a reload throws after staging a partial new set. shadow-cljs never
  ;; runs ^:dev/after-load, so commit-reload! is not reached — the live manifest
  ;; must stay exactly the last known-good, never a half-applied union.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:a-prop}} 'app.a)
  (rules/register-custom-element! :b-el {:properties #{:b-prop}} 'app.b)
  (let [known-good @rules/custom-elements]
    (rules/notify-reload!)
    (rules/register-custom-element! :a-el {:properties #{:a-prop2}} 'app.a) ; partial
    ;; ...a later top-level form throws → after-load never fires (no commit).
    (is (= known-good @rules/custom-elements)
        "a mid-reload throw leaves the live registry untouched (staged, uncommitted)")
    ;; the NEXT reload opens a fresh cycle, discarding the aborted staging.
    (rules/notify-reload!)
    (rules/register-custom-element! :a-el {:properties #{:a-prop2}} 'app.a)
    (rules/register-custom-element! :b-el {:properties #{:b-prop}} 'app.b)
    (rules/commit-reload!)
    (is (= {:a-el {:properties #{:a-prop2}}
            :b-el {:properties #{:b-prop}}} @rules/custom-elements)
        "the recovering reload commits the full new manifest, no ghost of the abort")))

(deftest build-ids-are-isolated
  ;; AC5: two builds sharing one runtime atom own their rows independently.
  ;; Reloading (and evicting a source in) build :app cannot touch build :lib.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :app-el {:properties #{:a-prop}} 'app.core :app)
  (rules/register-custom-element! :lib-el {:properties #{:l-prop}} 'lib.core :lib)
  (is (= #{:a-prop} (rules/custom-element-properties :app-el)))
  (is (= #{:l-prop} (rules/custom-element-properties :lib-el)))
  ;; build :app reloads app.core with its declaration deleted
  (rules/notify-reload! :app)
  (rules/note-reloaded-sources! ['app.core] :app)
  (rules/commit-reload! :app)
  (is (= #{} (rules/custom-element-properties :app-el))
      "reconciling build :app evicts its own source")
  (is (= #{:l-prop} (rules/custom-element-properties :lib-el))
      "build :lib's row is untouched — reconciling one build cannot delete another's"))

(deftest repl-reeval-converges-to-the-same-manifest-as-file-save-hmr
  ;; AC6: REPL re-evaluation of a source (including a zero-declaration one) goes
  ;; through the SAME begin/stage/commit protocol as a file-save reload, so it
  ;; converges to an identical manifest.
  (letfn [(build-then-drop! []
            (rules/reset-custom-elements!)
            (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
            (rules/register-custom-element! :k-el {:properties #{:keep-me}} 'app.b)
            (rules/notify-reload!)
            (rules/note-reloaded-sources! ['app.a])   ; app.a re-evaluated, now empty
            (rules/commit-reload!)
            @rules/custom-elements)]
    (let [file-save (build-then-drop!)
          repl      (build-then-drop!)]
      (is (= file-save repl)
          "REPL re-eval and file-save HMR converge to the same manifest")
      (is (= {:k-el {:properties #{:keep-me}}} file-save)
          "the zero-declaration source's rows are gone; the untouched one survives"))))

(deftest incremental-reconcile-equals-a-full-page-reload
  ;; AC7: the manifest after an incremental reload cycle equals the one a full
  ;; page reload (a clean rebuild of exactly the current live sources) produces.
  (letfn [(full-reload! []       ; clean process: every live source registers direct
            (rules/reset-custom-elements!)
            (rules/register-custom-element! :y-el {:properties #{:label}} 'app.a)
            (rules/register-custom-element! :k-el {:properties #{:keep-me}} 'app.b)
            @rules/custom-elements)
          (incremental! []       ; edit app.a (:x-el -> :y-el) in a live session
            (rules/reset-custom-elements!)
            (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
            (rules/register-custom-element! :k-el {:properties #{:keep-me}} 'app.b)
            (rules/notify-reload!)
            (rules/register-custom-element! :y-el {:properties #{:label}} 'app.a)
            (rules/commit-reload!)
            @rules/custom-elements)]
    (is (= (full-reload!) (incremental!))
        "incremental reload equals a clean full-page-reload rebuild")))
