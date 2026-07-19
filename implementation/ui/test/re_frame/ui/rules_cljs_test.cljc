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

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.144 — the commit is linearizable against a REENTRANT registration.
;;
;; Counterexample: a watch on the public `custom-elements` aggregate calls
;; `register-custom-element!` when the aggregate is published during a commit. The
;; watch is the deterministic barrier — it fires synchronously in the middle of
;; the commit, on BOTH hosts. Under the old snapshot-then-`dissoc` shape the
;; commit published (firing the watch) while the cycle was STILL open, so the
;; reentrant registration staged into that cycle and the following `dissoc`
;; discarded it. The single-atom commit closes the cycle in the same swap that
;; reconciles the ledger and publishes only after, so the reentrant registration
;; observes a closed cycle and write-through's — surviving exactly once.
;; ---------------------------------------------------------------------------

(defn- with-publication-reentry
  "Run `body` with a one-shot watch on `custom-elements` that, the first time the
  aggregate changes, reentrantly runs `reentrant`. Returns after cleanup. The
  guard makes it fire ONCE, so the reentrant write-through's own publish does not
  recurse."
  [reentrant body]
  (let [fired (atom false)
        k     (keyword (gensym "reentry"))]
    (add-watch rules/custom-elements k
               (fn [_ _ _ _]
                 (when (compare-and-set! fired false true)
                   (reentrant))))
    (try (body)
         (finally (remove-watch rules/custom-elements k)))))

(deftest reentrant-registration-during-commit-publication-survives-once
  ;; The headline counterexample. Commit publishes -> watch fires -> a NEW
  ;; declaration registers reentrantly. It must become live exactly once.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :seed-el {:properties #{:s}} 'app.seed)
  (with-publication-reentry
    #(rules/register-custom-element! :reentrant-el {:properties #{:r}} 'app.reentry)
    (fn []
      (rules/notify-reload!)
      (rules/register-custom-element! :seed-el {:properties #{:s2}} 'app.seed) ; a real reload
      (rules/commit-reload!)))                                                  ; publishes -> reentry
  (is (= #{:r} (rules/custom-element-properties :reentrant-el))
      "the reentrant registration survived the commit — old snapshot-then-dissoc
       staged it into the still-open cycle, which commit then discarded")
  (is (= #{:s2} (rules/custom-element-properties :seed-el))
      "the committing source's own reload still applied")
  ;; and it is a real ledger row, not a transient aggregate patch: a later clean
  ;; reload of an unrelated source must preserve it.
  (rules/notify-reload!)
  (rules/register-custom-element! :seed-el {:properties #{:s3}} 'app.seed)
  (rules/commit-reload!)
  (is (= #{:r} (rules/custom-element-properties :reentrant-el))
      "the reentrant row is in ::sources, so a subsequent unrelated reload keeps it"))

(deftest reentrant-registration-applies-exactly-once-never-doubled
  ;; The reentrant registration must not be applied twice (once as a stray
  ;; aggregate patch and once as a ledger row) — assert the FINAL aggregate is
  ;; exactly the legal serial result: commit fully applies, then the reentrant
  ;; registration write-through's.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :keep-el {:properties #{:k}} 'app.keep)
  (with-publication-reentry
    #(rules/register-custom-element! :new-el {:properties #{:n}} 'app.new)
    (fn []
      (rules/notify-reload!)
      (rules/register-custom-element! :keep-el {:properties #{:k2}} 'app.keep)
      (rules/commit-reload!)))
  (is (= {:keep-el {:properties #{:k2}}
          :new-el  {:properties #{:n}}}
         @rules/custom-elements)
      "the published aggregate equals the legal serial execution exactly — the
       reentrant row appears once, the committing source's reload applied"))

(deftest reentrant-registration-into-an-open-foreign-cycle-still-stages
  ;; Generation rule: a reentrant registration whose OWN build still has an open
  ;; cycle must stage into it (it belongs to that live generation), not leak live.
  ;; Here build :a commits (firing the watch) while build :b's cycle is open, and
  ;; the reentrant registration targets build :b.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:a}} 'app.a :a)
  (rules/register-custom-element! :b-el {:properties #{:b}} 'app.b :b)
  (rules/notify-reload! :a)
  (rules/notify-reload! :b)                                   ; build :b cycle stays open
  (rules/register-custom-element! :a-el {:properties #{:a2}} 'app.a :a)
  (with-publication-reentry
    #(rules/register-custom-element! :b-el {:properties #{:b2}} 'app.b :b) ; :b cycle open -> stages
    (fn [] (rules/commit-reload! :a)))                        ; commit :a -> publish -> reentry
  (is (= #{:a2} (rules/custom-element-properties :a-el))
      "build :a committed")
  (is (= #{:b} (rules/custom-element-properties :b-el))
      "the reentrant :b registration STAGED into build :b's still-open cycle —
       last-known-good until :b commits, not leaked live by :a's commit")
  (rules/commit-reload! :b)
  (is (= #{:b2} (rules/custom-element-properties :b-el))
      "and it commits with build :b's own cycle"))

;; ---------------------------------------------------------------------------
;; rf2-84173 — exactly ONE live cycle per build, and the supersession is LOUD.
;;
;; `notify-reload!` replaces whatever cycle was open. That is deliberate: shadow
;; abandons the remaining task chain when a load task throws, so an aborted
;; reload never runs `^:dev/after-load` and leaves its cycle open forever — the
;; next before-load must clear it. It is also LOSSY, and it used to be silent:
;; the same blind clobber served the routine abort-recovery and the pathological
;; overlapping-cycle case, and nothing distinguished or reported them.
;;
;; These fixtures pin the SUPPORTED model honestly, including its documented
;; loss. They do not pretend overlapping same-build cycles work — shadow conveys
;; no cycle identity to `^:dev/after-load` and does not pair it with before-load,
;; so no token in this ledger could make them work (see rules.cljc §Overlapping
;; cycles are unsupported).
;; ---------------------------------------------------------------------------

(deftest overlapping-same-build-cycles-settle-without-orphan-or-duplicate
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :base-el {:properties #{:b}} 'app.base :ov)
  (rules/notify-reload! :ov)                                          ; cycle N
  (rules/register-custom-element! :n-el {:properties #{:n}} 'app.n :ov) ; N stages
  (is (= #{:ov} (rules/in-flight-cycles))
      "one live cycle for the build")
  (rules/notify-reload! :ov)                                          ; cycle N+1
  (is (= #{:ov} (rules/in-flight-cycles))
      "still exactly ONE live cycle — a build never accumulates cycles")
  (rules/register-custom-element! :m-el {:properties #{:m}} 'app.m :ov) ; N+1 stages
  (rules/commit-reload! :ov)                                          ; N's after-load
  (is (empty? (rules/in-flight-cycles))
      "the single live cycle is closed — no orphan")
  (is (= #{:m} (rules/custom-element-properties :m-el))
      "the live cycle's staging committed")
  (is (= #{} (rules/custom-element-properties :n-el))
      "the SUPERSEDED cycle's staging was discarded — the documented, diagnosed
       loss, not a silent one; overlapping same-build cycles are unsupported")
  (is (= #{:b} (rules/custom-element-properties :base-el))
      "an untouched source is unaffected by the supersession")
  ;; the superseded cycle's own after-load arrives later: a harmless no-op that
  ;; must not close, reopen, or re-apply anything.
  (rules/commit-reload! :ov)
  (is (empty? (rules/in-flight-cycles))
      "the stale completion closes nothing and creates nothing")
  (is (= {:base-el {:properties #{:b}} :m-el {:properties #{:m}}}
         @rules/custom-elements)
      "the settled manifest is exactly the supported model's legal outcome")
  (is (= (rules/projected-aggregate) @rules/custom-elements)
      "settled aggregate equals the ledger projection — nothing doubled"))

#?(:cljs
   (defn- capturing-console-warn
     "Run `body` with `js/console.warn` captured; returns the captured messages."
     [body]
     (let [warnings (atom [])
           original (.-warn js/console)]
       (set! (.-warn js/console) (fn [msg] (swap! warnings conj (str msg))))
       (try (body)
            (finally (set! (.-warn js/console) original)))
       @warnings)))

#?(:cljs
   (deftest superseding-a-live-cycle-is-diagnosed-never-silent
     ;; FAIL-BEFORE lever: restore the bare `(swap! ledger-state assoc-in ...)`
     ;; clobber and no diagnostic is emitted, so this reddens.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :s-el {:properties #{:s}} 'app.s :diag)
     (let [warnings (capturing-console-warn
                     (fn []
                       (rules/notify-reload! :diag)
                       (rules/register-custom-element! :s-el {:properties #{:s2}} 'app.s :diag)
                       (rules/note-reloaded-sources! ['app.s] :diag)
                       (rules/notify-reload! :diag)))]   ; UNPAIRED before-load
       (is (= 1 (count warnings))
           "the supersession is reported exactly once — bounded, not per-source")
       (is (re-find #"STILL\s+OPEN" (first warnings))
           "and names the anomaly it discarded evidence for"))))

#?(:cljs
   (deftest an-empty-cycle-reopened-discards-nothing-and-is-silent
     ;; The guard must test what was LOST, not merely whether the cycle key was
     ;; present — a presence test would warn on every ordinary consecutive
     ;; reload that happened to stage nothing, training the reader to ignore it.
     (rules/reset-custom-elements!)
     (let [warnings (capturing-console-warn
                     (fn []
                       (rules/notify-reload! :quiet)
                       (rules/notify-reload! :quiet)))]
       (is (empty? warnings)
           "re-opening a cycle that staged and touched NOTHING discarded nothing"))
     (rules/commit-reload! :quiet)
     (is (empty? (rules/in-flight-cycles))
         "and the ledger still settles cleanly")))

;; ---------------------------------------------------------------------------
;; rf2-za45g — publication is DELIVERY, and delivery cannot falsify AUTHORITY.
;;
;; `commit-reload!` reconciles `::sources` and closes the cycle in ONE atomic
;; swap, and only THEN publishes. Publishing `swap!`s the public aggregate, which
;; notifies watches synchronously and without containment — so a throwing
;; observer used to escape `publish!`, escape `commit-reload!`, and land in
;; shadow's `^:dev/after-load` hook, which reports the ENTIRE reload as failed.
;; The framework had already committed it. That is a listener telling a lie about
;; authority, the same split rf2-vxgfnd.215 drew for descriptor HMR.
;;
;; These fixtures are the FAIL-BEFORE lever: on bare uncontained publication the
;; throw propagates out of `commit-reload!` and every one of them errors.
;;
;; What is deliberately NOT asserted: that sibling observers still run. Watch
;; fan-out belongs to the host's `IAtom`, which abandons the remaining watches
;; when one throws. There are no framework-owned observers of `custom-elements`
;; (classification reads `custom-element-properties`), so arbitrary watches are
;; explicitly unsupported observers — contained and diagnosed, but their delivery
;; completeness is the host's semantics, not a framework guarantee.
;; ---------------------------------------------------------------------------

(defn- with-observers
  "Run `body` with each `[k f]` of `watches` installed on `custom-elements`, in
  order, removing every one afterwards even when `body` throws."
  [watches body]
  (doseq [[k f] watches] (add-watch rules/custom-elements k f))
  (try (body)
       (finally (doseq [[k _] watches] (remove-watch rules/custom-elements k)))))

(deftest throwing-publication-observer-cannot-fail-a-committed-reload
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :seed-el {:properties #{:s}} 'app.seed)
  (let [ran (atom [])]
    (with-observers
      [[::boom (fn [_ _ _ _]
                 (swap! ran conj :boom)
                 (throw (ex-info "publication observer exploded" {})))]
       [::good (fn [_ _ _ _] (swap! ran conj :good))]]
      (fn []
        (rules/notify-reload!)
        (rules/register-custom-element! :seed-el {:properties #{:s2}} 'app.seed)
        (is (nil? (rules/commit-reload!))
            "the throwing observer does not escape the commit — shadow's
             ^:dev/after-load hook sees the reload SUCCEED, because it did")))
    (is (some #{:boom} @ran)
        "the throwing observer really ran — the fixture has teeth")
    (is (= #{:s2} (rules/custom-element-properties :seed-el))
        "the committed reload is live despite the observer failure")
    (is (empty? (rules/in-flight-cycles))
        "the cycle is closed — no orphan cycle survives the contained failure")
    (is (= (rules/projected-aggregate) @rules/custom-elements)
        "the published aggregate is exactly the ledger projection — never torn")))

(deftest contained-observer-failure-leaves-the-ledger-reloadable
  ;; Quiescence: a contained failure must not poison the NEXT cycle. The ledger
  ;; is a real row store afterwards, not a wedged one.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:a}} 'app.a)
  (with-observers
    [[::boom (fn [_ _ _ _] (throw (ex-info "boom" {})))]]
    (fn []
      (rules/notify-reload!)
      (rules/register-custom-element! :a-el {:properties #{:a2}} 'app.a)
      (rules/commit-reload!)))
  ;; observer gone; a clean subsequent reload still reconciles normally
  (rules/notify-reload!)
  (rules/note-reloaded-sources! ['app.a])
  (rules/commit-reload!)
  (is (= #{} (rules/custom-element-properties :a-el))
      "a later zero-declaration reload still evicts — the ledger is not wedged")
  (is (empty? (rules/in-flight-cycles))
      "and it closes its cycle cleanly"))

#?(:cljs
   (deftest falsey-observer-throws-are-contained-without-truthiness-bugs
     ;; AC: `(throw nil)` / `(throw false)` are legal on CLJS. A containment that
     ;; branched on the truthiness of the CAUGHT value would treat both as "no
     ;; failure" — silently dropping the diagnostic for exactly the values whose
     ;; failure is hardest to spot. The `:failed?` bit is what carries identity.
     (doseq [thrown [nil false]]
       (rules/reset-custom-elements!)
       (rules/register-custom-element! :f-el {:properties #{:f}} 'app.f)
       (with-observers
         [[::falsey (fn [_ _ _ _] (throw thrown))]]
         (fn []
           (rules/notify-reload!)
           (rules/register-custom-element! :f-el {:properties #{:f2}} 'app.f)
           (is (nil? (rules/commit-reload!))
               (str "a thrown " (pr-str thrown) " is contained like any other"))))
       (is (= #{:f2} (rules/custom-element-properties :f-el))
           (str "the reload committed despite the thrown " (pr-str thrown)))
       (is (empty? (rules/in-flight-cycles))
           (str "no orphan cycle after a thrown " (pr-str thrown))))))

;; ---------------------------------------------------------------------------
;; The production producer (rf2-vxgfnd.77) — the reload ledger's zero-declaration
;; eviction fired THROUGH the shipped browser reload path, not a direct
;; `note-reloaded-sources!` call. `reload-source-reset!` is what shadow-cljs's
;; `before-load-src` drives (through `js/goog.global.SHADOW_NS_RESET`) for every
;; reloaded ns; `install-reload-source-producer!` (run once at ns load via a
;; `defonce`) is the wire. These tests are CLJS-only — the producer is the
;; runtime arm and does not exist on the JVM compile host.
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- fire-shadow-ns-reset!
     "Simulate shadow-cljs's `before-load-src`: call every fn registered on the
     `SHADOW_NS_RESET` array with the reloaded ns, exactly as the real reload
     machinery does mid-cycle. Wrapped so an unrelated resetter registered by
     other code can't fail the ledger assertion."
     [ns]
     (doseq [f (array-seq js/goog.global.SHADOW_NS_RESET)]
       (try (f ns) (catch :default _ nil)))))

#?(:cljs
   (defn- rf2-producers
     "The re-frame.ui-owned entries of the shared SHADOW_NS_RESET array,
     identified the way the installer identifies them — by their owner tag."
     []
     (into [] (filter #(some? (unchecked-get % "reFrameUiReloadSourceReset")))
           (array-seq js/goog.global.SHADOW_NS_RESET))))

#?(:cljs
   (deftest reload-producer-is-wired-to-shadow-reset-array
     ;; The wire itself: install-reload-source-producer! (run at ns load) must
     ;; have registered a re-frame.ui producer on shadow's per-reload callback
     ;; array, so the shipped reload path has a real caller for the ledger seam.
     (is (exists? js/goog.global.SHADOW_NS_RESET)
         "install-reload-source-producer! ensured the SHADOW_NS_RESET array")
     (is (= 1 (count (rf2-producers)))
         "exactly one re-frame.ui producer is registered on shadow's array")))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.145 — the installed producer must run the CURRENT implementation.
;;
;; The install is `defonce`d, so whatever object it puts on SHADOW_NS_RESET is
;; pinned for the life of the page. Pushing the `reload-source-reset!` fn OBJECT
;; therefore froze the implementation realized at first load: hot-reloading
;; rules.cljc rebinds the var, but the array kept calling the object from before
;; the reload. These tests drive the ARRAY ENTRY (what shadow actually calls) —
;; never the var directly — which is the only way to observe the staleness.
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest installed-producer-runs-the-current-implementation-after-a-reload
     ;; FAIL-BEFORE fixture. Simulate a hot-reload of rules.cljc: shadow
     ;; re-evaluates the file, re-assigning the `reload-source-reset!` global to
     ;; a NEW fn object. `defonce` keeps the installed entry, so the entry must
     ;; still dispatch to the reloaded implementation.
     ;;
     ;; This fixture is deliberately TAG-BLIND: it fires EVERY entry on the array
     ;; exactly as shadow's `before-load-src` does, so it proves STALENESS rather
     ;; than merely the absence of the new owner tag. Under the pre-.145 install
     ;; (`.push reload-source-reset!`) the array holds the PRE-reload object,
     ;; which never consults the rebound var — nothing is recorded and this test
     ;; fails. The trampoline resolves the var per call, so it records.
     (let [original rules/reload-source-reset!
           seen     (atom [])]
       (try
         ;; the "reloaded" ns's new object — same arities as the real one, so
         ;; the trampoline's call site is exercised exactly as in a real reload
         (set! rules/reload-source-reset!
               (fn ([ns] (swap! seen conj [ns]) nil)
                 ([ns build-id] (swap! seen conj [ns build-id]) nil)))
         (fire-shadow-ns-reset! 'app.reloaded)            ; what shadow actually calls
         (is (= 1 (count @seen))
             "an installed entry dispatched to the CURRENT implementation, not the
              object captured at first load (pre-.145: the defonce'd object was stale)")
         (is (= 'app.reloaded (first (first @seen)))
             "the reloaded implementation received the ns shadow reported")
         (finally
           (set! rules/reload-source-reset! original))))))

#?(:cljs
   (deftest installed-producer-uses-current-coercion-after-a-reload
     ;; The concrete consequence the bead names: a fix to the callback's OWN
     ;; coercion must take effect without a page refresh. Drive the ARRAY ENTRY
     ;; with a STRING ns (shadow's `before-load-src` reports symbols, but the
     ;; documented contract coerces strings) and prove the ledger saw a SYMBOL —
     ;; i.e. the entry ran the current coercion, not a frozen copy.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
     (rules/notify-reload!)
     ((first (rf2-producers)) "app.a")                    ; string, coerced by the impl
     (rules/commit-reload!)
     (is (= #{} (rules/custom-element-properties :x-el))
         "the installed entry's current coercion turned the string into the
          symbol the ledger keys on, so the zero-declaration source was evicted")))

#?(:cljs
   (deftest producer-install-is-idempotent-and-preserves-other-owners
     ;; Repeated installs (a REPL re-eval of the ns, a build that drops the
     ;; defonce) must neither duplicate nor lose the producer, and must never
     ;; touch a callback shadow or another library owns.
     (let [foreign      (fn [_ns] nil)
           before-count (count (array-seq js/goog.global.SHADOW_NS_RESET))]
       (.push js/goog.global.SHADOW_NS_RESET foreign)
       (try
         (rules/install-reload-source-producer!)
         (rules/install-reload-source-producer!)
         (rules/install-reload-source-producer!)
         (is (= 1 (count (rf2-producers)))
             "three installs leave exactly one re-frame.ui producer — replaced in
              place, never appended")
         (is (some #(identical? % foreign) (array-seq js/goog.global.SHADOW_NS_RESET))
             "a callback owned by another library survives re-installation")
         (is (= (inc before-count) (count (array-seq js/goog.global.SHADOW_NS_RESET)))
             "the array grew by exactly the foreign entry — no producer accumulation")
         (finally
           ;; drop the foreign probe, leaving the array as we found it
           (let [arr js/goog.global.SHADOW_NS_RESET
                 i   (.indexOf arr foreign)]
             (when-not (neg? i) (.splice arr i 1))))))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.142 — ONE real build identity through the whole reload cycle.
;;
;; Before this, every real path (emitted registrations, both lifecycle hooks, the
;; reset producer) hard-coded the ::default placeholder; a second identity was
;; reachable only from a test-only arity. Two real builds sharing a JS realm
;; collapsed onto one [::default ns] row. `current-build-id` is now the single
;; rule every arity default resolves through, so the participants in a cycle can
;; never disagree about who owns a row.
;; ---------------------------------------------------------------------------

(deftest build-identity-is-one-rule-shared-by-every-arity
  ;; The invariant that makes the ledger key trustworthy: whatever the host
  ;; resolves as its build identity, the emitted registration and the reload
  ;; cycle that reconciles it MUST agree. Assert it through behaviour rather than
  ;; by naming a value — the correct id differs per host (a Shadow dev build
  ;; names itself; a node/JVM host has no build to name).
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :ident-el {:properties #{:p}} 'app.ident)   ; arity default
  (rules/notify-reload!)                                                      ; arity default
  (rules/note-reloaded-sources! ['app.ident])                                 ; arity default
  (rules/commit-reload!)                                                      ; arity default
  (is (= #{} (rules/custom-element-properties :ident-el))
      "the zero-declaration source was evicted — registration, notify, note and
       commit all resolved the SAME build identity (a disagreement would leave the
       row stranded under a build nobody reconciles)")
  ;; ...and the same identity is what an EXPLICIT arity must be given to hit the
  ;; same rows, which is what makes `current-build-id` the ledger's public rule.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :ident-el {:properties #{:p}} 'app.ident)
  (rules/notify-reload! (rules/current-build-id))
  (rules/note-reloaded-sources! ['app.ident] (rules/current-build-id))
  (rules/commit-reload! (rules/current-build-id))
  (is (= #{} (rules/custom-element-properties :ident-el))
      "explicitly passing (current-build-id) reconciles exactly the rows the
       arity defaults wrote — one rule, not two"))

(deftest build-identity-is-stable-across-calls
  ;; The owner token must be an IDENTITY, not a digest: it may not change as
  ;; sources change, or a reload would reconcile against rows keyed under the
  ;; previous value and strand every one of them.
  (is (= (rules/current-build-id) (rules/current-build-id))
      "the build identity is stable across calls")
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :stable-el {:properties #{:p}} 'app.s)
  (let [before (rules/current-build-id)]
    (rules/notify-reload!)
    (rules/register-custom-element! :stable-el {:properties #{:p2}} 'app.s)
    (rules/commit-reload!)
    (is (= before (rules/current-build-id))
        "reloading sources does not change the build identity (it is the build's
         name, never the content/build digest)")
    (is (= #{:p2} (rules/custom-element-properties :stable-el))
        "so the reload reconciled the row the initial load wrote")))

(deftest co-loaded-builds-retain-independent-declarations
  ;; AC2: two co-loaded builds with EQUAL namespace symbols. Reloading (or
  ;; zero-declaring) either one may not disturb the other's contribution — the
  ;; collapse that a shared ::default owner token caused.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:from-app}} 'shared.ns :app)
  (rules/register-custom-element! :l-el {:properties #{:from-lib}} 'shared.ns :lib)
  (is (= #{:from-app} (rules/custom-element-properties :a-el)))
  (is (= #{:from-lib} (rules/custom-element-properties :l-el)))
  ;; build :app reloads `shared.ns` declaring NOTHING
  (rules/notify-reload! :app)
  (rules/note-reloaded-sources! ['shared.ns] :app)
  (rules/commit-reload! :app)
  (is (= #{} (rules/custom-element-properties :a-el))
      "build :app's own zero-declaration source is evicted")
  (is (= #{:from-lib} (rules/custom-element-properties :l-el))
      "build :lib's row under the SAME ns symbol is untouched — equal ns symbols
       in two builds are two rows, not one")
  ;; and the reverse direction: :lib zero-declares, :app is re-populated
  (rules/register-custom-element! :a-el {:properties #{:from-app}} 'shared.ns :app)
  (rules/notify-reload! :lib)
  (rules/note-reloaded-sources! ['shared.ns] :lib)
  (rules/commit-reload! :lib)
  (is (= #{} (rules/custom-element-properties :l-el))
      "build :lib's source is evicted by its own cycle")
  (is (= #{:from-app} (rules/custom-element-properties :a-el))
      "build :app's row survives build :lib's reconciliation"))

(deftest one-builds-cycle-never-consumes-another-builds-reload-evidence
  ;; A cycle open for build :app must not be reconciled by evidence belonging to
  ;; build :lib — the two builds' reload cycles are independent transactions.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:from-app}} 'shared.ns :app)
  (rules/register-custom-element! :l-el {:properties #{:from-lib}} 'shared.ns :lib)
  (rules/notify-reload! :app)
  (rules/note-reloaded-sources! ['shared.ns] :lib)   ; :lib has NO open cycle → no-op
  (rules/commit-reload! :app)
  (is (= #{:from-lib} (rules/custom-element-properties :l-el))
      "reload evidence addressed to a build with no open cycle cannot evict rows")
  (is (= #{:from-app} (rules/custom-element-properties :a-el))
      "nor can it leak into the OTHER build's open cycle"))

(deftest ready-made-pair-cannot-inject-a-foreign-owner-into-an-open-cycle
  ;; rf2-4vm19. The seam took a ready-made [build ns] pair VERBATIM, so the
  ;; owner travelled with the datum rather than with the addressed cycle: a
  ;; source handed to build :a's cycle could name build :b as its owner and
  ;; `reconcile-sources` would then evict :b's row. Ownership is a property of
  ;; the CYCLE BEING ADDRESSED, never of the payload — a caller cannot nominate
  ;; whose rows a cycle reconciles.
  (rules/reset-custom-elements!)
  (rules/register-custom-element! :a-el {:properties #{:a}} 'shared.ns :a)
  (rules/register-custom-element! :b-el {:properties #{:b}} 'shared.ns :b)
  (rules/notify-reload! :a)
  (rules/note-reloaded-sources! [[:b 'shared.ns]] :a)   ; :a's cycle, :b's owner
  (rules/commit-reload! :a)
  (is (= #{:b} (rules/custom-element-properties :b-el))
      "build :b's row survives a cycle it never took part in — the foreign owner
       in the payload was normalized to the addressed build, not honoured")
  (is (= #{} (rules/custom-element-properties :a-el))
      "and the source WAS reconciled, against the build whose cycle is open —
       normalization re-owns the datum rather than discarding it"))

(deftest ns-only-and-ready-made-pair-are-the-same-evidence
  ;; The ergonomic ns-only form is preserved, and the pair form is now merely a
  ;; more verbose spelling of it: for one addressed build both must reconcile
  ;; the identical row, so there is no second ownership rule to reason about.
  (doseq [sources [['shared.ns] [[:a 'shared.ns]]]]
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :a-el {:properties #{:a}} 'shared.ns :a)
    (rules/register-custom-element! :b-el {:properties #{:b}} 'shared.ns :b)
    (rules/notify-reload! :a)
    (rules/note-reloaded-sources! sources :a)
    (rules/commit-reload! :a)
    (is (= #{} (rules/custom-element-properties :a-el))
        (str "the addressed build's row is reconciled, given " (pr-str sources)))
    (is (= #{:b} (rules/custom-element-properties :b-el))
        (str "and no other build's row is touched, given " (pr-str sources)))))

#?(:cljs
   (deftest installed-producer-notes-into-its-own-build-only
     ;; The producer is the one real caller of the ledger seam, so its build
     ;; routing is what decides which build a reloaded source is attributed to.
     ;; A row owned by a DIFFERENT build must be untouched when the installed
     ;; producer fires — SHADOW_NS_RESET lives on the shared `goog.global`, so
     ;; every build's producer sees every build's reload.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :own-el {:properties #{:p}} 'shared.ns)      ; this build
     (rules/register-custom-element! :other-el {:properties #{:q}} 'shared.ns
                                     :some-other-build)
     (rules/notify-reload!)
     (fire-shadow-ns-reset! 'shared.ns)      ; what before-load-src does
     (rules/commit-reload!)
     (is (= #{} (rules/custom-element-properties :own-el))
         "the producer noted the reloaded source into ITS OWN build's cycle")
     (is (= #{:q} (rules/custom-element-properties :other-el))
         "another build's row under the same ns symbol is untouched")))

#?(:cljs
   (deftest reinstalled-producer-still-runs-the-current-implementation
     ;; The replacement path must produce a trampoline too — a re-install that
     ;; froze the current object would reintroduce the staleness for anyone whose
     ;; build re-runs the installer.
     (rules/install-reload-source-producer!)
     (let [original rules/reload-source-reset!
           seen     (atom 0)]
       (try
         (set! rules/reload-source-reset!
               (fn ([_ns] (swap! seen inc) nil)
                 ([_ns _build-id] (swap! seen inc) nil)))
         ((first (rf2-producers)) 'app.x)
         (is (= 1 @seen) "the re-installed entry also resolves the current implementation")
         (finally
           (set! rules/reload-source-reset! original))))))

#?(:cljs
   (deftest reload-producer-evicts-zero-declaration-source-end-to-end
     ;; The headline gap (rf2-vxgfnd.67 shipped the seam with NO production
     ;; caller): a source that re-runs declaring NOTHING must have its stale rows
     ;; evicted. Here the eviction fires through the SHIPPED producer — we drive
     ;; shadow's SHADOW_NS_RESET callback (as before-load-src does), NOT
     ;; note-reloaded-sources! directly — proving the browser reload path now
     ;; fires what the ledger always could but never had a caller for.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
     (is (= #{:help-text} (rules/custom-element-properties :x-el))
         "initial load classifies :help-text as a property")
     (rules/notify-reload!)                        ; ^:dev/before-load
     (fire-shadow-ns-reset! 'app.a)                ; before-load-src: app.a re-runs, declares nothing
     (rules/commit-reload!)                        ; ^:dev/after-load
     (is (= #{} (rules/custom-element-properties :x-el))
         "the shipped SHADOW_NS_RESET producer evicts the zero-declaration source")
     (is (= {} @rules/custom-elements)
         "no stale rows survive the zero-declaration reload through the real path")))

#?(:cljs
   (deftest reload-producer-preserves-untouched-and-updates-reloaded
     ;; Through the shipped producer: reloading only app.b (which re-declares)
     ;; must update B and leave A's rows intact — A is neither fired by
     ;; SHADOW_NS_RESET nor re-staged, so it is untouched.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :a-el {:properties #{:a-prop}} 'app.a)
     (rules/register-custom-element! :b-el {:properties #{:b-prop}} 'app.b)
     (rules/notify-reload!)
     (fire-shadow-ns-reset! 'app.b)                                          ; only app.b reloads
     (rules/register-custom-element! :b-el {:properties #{:b-prop2}} 'app.b) ; ...re-declaring
     (rules/commit-reload!)
     (is (= #{:a-prop} (rules/custom-element-properties :a-el))
         "untouched source A keeps its classification across B's reload")
     (is (= #{:b-prop2} (rules/custom-element-properties :b-el))
         "reloaded source B's classification updates through the real path")))

#?(:cljs
   (deftest reload-producer-drops-last-declaration-but-keeps-a-sibling
     ;; app.a reloads, dropping :x-el but keeping :y-el. The producer fires
     ;; app.a (touched); :y-el stages; commit replaces app.a's WHOLE contribution
     ;; so the dropped :x-el is evicted while :y-el survives — all through the
     ;; shipped path, no direct seam call.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
     (rules/register-custom-element! :y-el {:properties #{:label}} 'app.a)
     (rules/notify-reload!)
     (fire-shadow-ns-reset! 'app.a)
     (rules/register-custom-element! :y-el {:properties #{:label}} 'app.a)   ; only :y-el survives
     (rules/commit-reload!)
     (is (= #{} (rules/custom-element-properties :x-el))
         "the dropped :x-el is evicted through the real producer")
     (is (= #{:label} (rules/custom-element-properties :y-el))
         "the surviving :y-el is retained")))

#?(:cljs
   (deftest reload-producer-is-build-isolated
     ;; The producer notes into the ::default build (one compiled bundle = one
     ;; build). Explicit-build rows are untouched by a ::default-build reload —
     ;; the [build-id ns] ownership holds through the real path too.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :app-el {:properties #{:a-prop}} 'app.core)       ; ::default
     (rules/register-custom-element! :lib-el {:properties #{:l-prop}} 'lib.core :lib)  ; build :lib
     (rules/notify-reload!)                       ; opens the ::default cycle
     (fire-shadow-ns-reset! 'app.core)            ; app.core re-runs, declares nothing
     (rules/commit-reload!)
     (is (= #{} (rules/custom-element-properties :app-el))
         "the ::default reload evicts its own zero-declaration source")
     (is (= #{:l-prop} (rules/custom-element-properties :lib-el))
         "build :lib's row is untouched — the producer cannot cross builds")))
