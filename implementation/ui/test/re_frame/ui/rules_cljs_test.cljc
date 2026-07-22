(ns re-frame.ui.rules-cljs-test
  "The DOM conversion rule table, row by row (owning contract:
  jvm-tree-and-conversion-contract.md §The DOM conversion table). The
  [S1-CONFIRM] provenance lives with each set in re-frame.ui.rules; this
  suite pins the row SEMANTICS on both hosts."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.rules :as rules]))

(defn- declared
  "`agg` (default: the live aggregate) with each entry's owner provenance
  stripped. rf2-vxgfnd.143 stamps the declarer `[build-id ns-sym]` set onto every
  entry in the same swap as the declaration (rf2-7uyl9), so the conflict law can
  anchor every side of a contradiction without a second atom. The reload-protocol
  assertions below are about WHICH declarations are live, not who owns them;
  provenance and the law it serves are pinned by
  `re-frame.ui.rules-custom-element-conflict-cljs-test`."
  ([] (declared @rules/custom-elements))
  ([agg]
   (into {}
         (map (fn [[tag entry]] [tag (dissoc entry :re-frame.ui.rules/owners)]))
         agg)))

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
;; arm lives in re-frame.freehand.compiler-build-jvm-test. Eviction is driven from the
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
            :b-el {:properties #{:b-prop}}} (declared))
        "the recovering reload commits the full new manifest, no ghost of the abort")))

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
      (is (= {:k-el {:properties #{:keep-me}}} (declared file-save))
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
         (declared))
      "the published aggregate equals the legal serial execution exactly — the
       reentrant row appears once, the committing source's reload applied"))

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
         (declared))
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
   (defn- shadow-build-id-carrier
     "shadow's OWN build-identity carrier — the object graph `current-build-id`
     reads through `shadow-build-id`, not a test seam standing in for it. Reading
     and writing the real carrier is what makes the migration fixture below drive
     an actual change of resolved build identity."
     []
     (let [root (or (unchecked-get js/goog.global "$CLJS") js/goog.global)
           env  (reduce (fn [o k]
                          (let [child (or (unchecked-get o k) #js {})]
                            (unchecked-set o k child)
                            child))
                        root
                        ["shadow" "cljs" "devtools" "client" "env"])]
       env)))

#?(:cljs
   (defn- set-shadow-build-id! [v]
     (unchecked-set (shadow-build-id-carrier) "build_id" v)))

#?(:cljs
   (deftest reload-producer-is-wired-to-shadow-reset-array
     ;; The wire itself: install-reload-source-producer! (run at ns load) must
     ;; have registered a re-frame.ui producer on shadow's per-reload callback
     ;; array, so the shipped reload path has a real caller for the ledger seam.
     (is (exists? js/goog.global.SHADOW_NS_RESET)
         "install-reload-source-producer! ensured the SHADOW_NS_RESET array")
     (is (= 1 (count (rf2-producers)))
         "exactly one re-frame.ui producer is registered on shadow's array")
     ;; rf2-mp6fz — the wire runs on EVERY load of this namespace, so the entry
     ;; standing on the array is always owned by the identity the CURRENT code
     ;; resolves. A producer whose tag has drifted from `current-build-id` is a
     ;; producer installed by a code generation that no longer exists.
     (is (= (str (rules/current-build-id))
            (unchecked-get (first (rf2-producers)) "reFrameUiReloadSourceReset"))
         "the standing producer is tagged with the identity the current code resolves")))

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
;; rf2-mp6fz — the install must be SELF-UPGRADING across the trampoline
;; boundary.
;;
;; .145 made the installed entry a trampoline so the BEHAVIOUR it runs is always
;; freshly loaded code. But the install itself sat behind a `defonce`, so the
;; INSTALLER never re-ran: a page loaded before a given generation of this
;; namespace kept whatever entry it already had, and no change to the installer,
;; the trampoline's construction, or the owner tag could ever reach it without a
;; full page reload. The `defonce` was there to stop the pre-.145 `.push` from
;; appending duplicates — a job the owner tag took over, which is precisely what
;; made the `defonce` redundant AND the thing blocking self-upgrade.
;;
;; Running the install on every load is only safe if it retires what this copy
;; already owns. Ownership is proven by OBJECT IDENTITY — the exact entry this
;; copy installed — never by tag, because a co-loaded bundle may legitimately own
;; a producer under any tag, including the placeholder.
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest producer-install-migrates-the-entry-this-copy-already-owns
     ;; The bead's concrete live-upgrade failure: an entry created while no real
     ;; build identity was resolvable captured the ::default placeholder. When
     ;; the reloaded code resolves the build's real name, re-installing must
     ;; MIGRATE that entry — one producer, real identity — rather than push a
     ;; second one and leave the placeholder-routed predecessor executing.
     (let [arr      js/goog.global.SHADOW_NS_RESET
           original (unchecked-get (shadow-build-id-carrier) "build_id")
           foreign  (fn [_ns] nil)
           before   (count (array-seq arr))]
       (.push arr foreign)
       (try
         (is (= 1 (count (rf2-producers)))
             "precondition: the ns-load install left exactly one producer")
         (set-shadow-build-id! "app-migrated")
         (is (= :app-migrated (rules/current-build-id))
             "precondition: the code now resolves a REAL build identity, as it
              does the moment shadow's build-id carrier is present")
         (rules/install-reload-source-producer!)      ; what every load re-runs
         (is (= 1 (count (rf2-producers)))
             "exactly one re-frame.ui producer — the entry this copy already
              owned was retired, not left standing beside the new one")
         (is (= ":app-migrated"
                (unchecked-get (first (rf2-producers)) "reFrameUiReloadSourceReset"))
             "and the surviving producer carries the build's real identity, so
              reset evidence stops routing to the placeholder owner")
         (is (= (inc before) (count (array-seq arr)))
             "the array grew by exactly the foreign probe — no accumulation")
         (is (some #(identical? % foreign) (array-seq arr))
             "a callback owned by shadow or another library is untouched")
         (finally
           (set-shadow-build-id! original)
           (rules/install-reload-source-producer!)
           (let [i (.indexOf arr foreign)]
             (when-not (neg? i) (.splice arr i 1))))))))

#?(:cljs
   (deftest producer-migration-leaves-another-owners-producer-alone
     ;; Why identity and not tag: a co-loaded bundle's producer may carry ANY
     ;; tag, including the very placeholder this copy is migrating away from.
     ;; Retiring by tag would evict it; retiring by identity cannot.
     (let [arr      js/goog.global.SHADOW_NS_RESET
           original (unchecked-get (shadow-build-id-carrier) "build_id")
           ;; another bundle's producer, tagged exactly like the entry this copy
           ;; is about to migrate away from
           other    (let [f (fn [_ns] nil)]
                      (unchecked-set f "reFrameUiReloadSourceReset"
                                     (str (rules/current-build-id)))
                      f)]
       (.push arr other)
       (try
         (set-shadow-build-id! "app-migrated")
         (rules/install-reload-source-producer!)
         (is (some #(identical? % other) (array-seq arr))
             "the other owner's identically-tagged producer survives the
              migration — ownership is the object this copy installed, not a tag
              any bundle could be carrying")
         (finally
           (set-shadow-build-id! original)
           (let [i (.indexOf arr other)]
             (when-not (neg? i) (.splice arr i 1)))
           (rules/install-reload-source-producer!))))))

#?(:cljs
   (deftest producer-install-appends-rather-than-adopting-a-same-tagged-foreign
     ;; rf2-ymfix. Ownership is OBJECT IDENTITY, with NO tag fallback. When this
     ;; copy's remembered entry is ABSENT from the shared array, re-installing must
     ;; APPEND its own producer and leave every entry it did not itself install
     ;; untouched — INCLUDING one carrying THIS build's tag, which a co-loaded
     ;; bundle may legitimately hold and which only identity can distinguish from
     ;; ours. The retired tag fallback treated the first same-tagged entry as ours
     ;; and REPLACED it, silently evicting a foreign owner.
     (let [arr      js/goog.global.SHADOW_NS_RESET
           standing (first (rf2-producers))              ; the entry THIS copy owns
           std-i    (.indexOf arr standing)
           ;; a co-loaded bundle's producer, tagged EXACTLY like this copy's — the
           ;; precise case only object identity can tell apart from our own.
           foreign  (let [f (fn [_ns] nil)]
                      (unchecked-set f "reFrameUiReloadSourceReset"
                                     (str (rules/current-build-id)))
                      f)]
       (try
         ;; The exact fallback trigger: this copy's remembered entry goes ABSENT
         ;; from the array while `owned-producer` still points at it, so the
         ;; identity scan misses and the old code fell back to the tag.
         (.splice arr std-i 1)
         (.push arr foreign)
         (rules/install-reload-source-producer!)
         (is (some #(identical? % foreign) (array-seq arr))
             "a same-tagged entry this copy did NOT install is preserved — the
              install APPENDED its own producer instead of adopting the foreign one
              by tag (rf2-ymfix; the retired fallback replaced it in place)")
         (finally
           ;; leave the array with exactly one re-frame.ui producer owned by this
           ;; copy, as every other producer test expects to find it.
           (let [fi (.indexOf arr foreign)]
             (when-not (neg? fi) (.splice arr fi 1)))
           (rules/install-reload-source-producer!))))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.142 — ONE real build identity through the whole reload cycle.
;;
;; Before this, every real path (emitted registrations, both lifecycle hooks, the
;; reset producer) hard-coded the ::default placeholder while a second identity was
;; reachable only from a test-only arity — so an emitted registration and the cycle
;; meant to reconcile it could key a row differently and strand it.
;; `current-build-id` is now the single rule every arity default resolves through,
;; so the participants in ONE build's cycle can never disagree about who owns a
;; row. These fixtures pin that single-build identity COHERENCE; they are not a
;; co-loaded-build isolation claim (rf2-4vm19 — see §THE ISOLATION UNIT in
;; rules.cljc: one Shadow dev build per CLJS root).
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

;; ---------------------------------------------------------------------------
;; rf2-lkv72 (rf2-4vm19 arm 2) — the warm-watch/file-edit fixture and the
;; removed-source GRAPH DELTA.
;;
;; Arm 1 (#6434/#6450) shipped the DETERMINATION: `reconcile-sources` evicts a
;; touched-but-unstaged row, keeps a re-staged one, and keys every row under one
;; real build identity. Arm 2 EXERCISES that determination under a warm
;; (incremental) rebuild and asserts the delta on the ledger's SOURCE-MEMBERSHIP
;; graph itself — `ledger-sources`, the runtime mirror of the compile side's
;; `:build-sources` — a stronger claim than the projected element rows: the graph
;; delta, not just the classification.
;;
;; Bounded by construction (no live watch server, no Chromium): the fixture
;; simulates a saved-file edit/remove/rename by driving the REAL ordered seam —
;; `notify-reload!` (^:dev/before-load) → shadow's `before-load-src` firing every
;; `SHADOW_NS_RESET` entry (the installed producer, NOT a direct
;; `note-reloaded-sources!`) → the surviving sources' re-registrations →
;; `commit-reload!` (^:dev/after-load). The file event is simulated; the ordering
;; and the producer seam are real.
;; ---------------------------------------------------------------------------

(deftest removed-source-graph-delta-on-remove-rename-and-move
  ;; DUAL-HOST (the seam is host-agnostic): the membership graph loses a REMOVED
  ;; source's row, SWAPS the row on a ns RENAME, and PRESERVES ownership on a
  ;; same-namespace MOVE. Asserted on `ledger-sources` — the [build ns] set —
  ;; which no prior test observed.
  (let [b (rules/current-build-id)]
    ;; REMOVE: app.a's file is deleted (re-runs via the reset, declares nothing);
    ;; app.b is untouched.
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :a-el {:properties #{:a}} 'app.a)
    (rules/register-custom-element! :b-el {:properties #{:b}} 'app.b)
    (is (= #{[b 'app.a] [b 'app.b]} (rules/ledger-sources))
        "both saved sources are live in the membership graph")
    (rules/notify-reload!)
    (rules/note-reloaded-sources! ['app.a])            ; app.a removed this cycle
    (rules/commit-reload!)
    (is (= #{[b 'app.b]} (rules/ledger-sources))
        "the removed source drops out of the membership graph — the delta")
    (is (= #{} (rules/custom-element-properties :a-el))
        "and its element rows are evicted (defence in depth on the projection)")
    (is (= #{:b} (rules/custom-element-properties :b-el))
        "the untouched source keeps its row and its classification")

    ;; RENAME: app.a → app.a2 (the file's ns changes). The old ns is reset/removed
    ;; and the new ns re-runs, so the graph SWAPS the row.
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :r-el {:properties #{:r}} 'app.a)
    (rules/notify-reload!)
    (rules/note-reloaded-sources! ['app.a])            ; old ns removed
    (rules/register-custom-element! :r-el {:properties #{:r}} 'app.a2) ; new ns runs
    (rules/commit-reload!)
    (is (= #{[b 'app.a2]} (rules/ledger-sources))
        "a ns rename swaps the membership row — old removed, new present")
    (is (= #{:r} (rules/custom-element-properties :r-el))
        "the renamed-in source classifies its element")

    ;; MOVE: same ns, different file. The ns re-runs declaring the same element,
    ;; so ownership is PRESERVED — the row stays under the same [build ns] key.
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :m-el {:properties #{:m}} 'app.moved)
    (rules/notify-reload!)
    (rules/note-reloaded-sources! ['app.moved])
    (rules/register-custom-element! :m-el {:properties #{:m}} 'app.moved)
    (rules/commit-reload!)
    (is (= #{[b 'app.moved]} (rules/ledger-sources))
        "a same-namespace move preserves ownership — the row survives under the
         same [build ns] key")
    (is (= #{:m} (rules/custom-element-properties :m-el))
        "and the moved source keeps its classification")))

#?(:cljs
   (defn- run-warm-rebuild!
     "The bounded warm-watch/file-edit fixture: drive ONE warm (incremental)
     rebuild through the REAL shipped reload path, in shadow's real order —
     `notify-reload!` (^:dev/before-load), then shadow's `before-load-src` firing
     every `SHADOW_NS_RESET` entry for each reloaded/removed source (the installed
     producer, NOT a direct `note-reloaded-sources!`), then the surviving sources'
     re-registrations, then `commit-reload!` (^:dev/after-load).

     `:reloaded` is the coll of ns-syms shadow reports reset this cycle (an edited
     source, a deleted one, or the old ns a rename left behind); `:registrations`
     is a coll of `[tag decl ns]` the surviving/new sources re-run. Models a
     saved-file edit/remove/rename WITHOUT a live watch server — the file event is
     simulated, the ordering and the producer seam are real. The removed-source
     signal travels ONLY through the producer, so the fixture has teeth: strip the
     producer (or its lifecycle annotation) and the reset is never noted, the
     removed source is never evicted, and the delta assertions redden."
     [{:keys [reloaded registrations]}]
     (rules/notify-reload!)                                  ; ^:dev/before-load
     (doseq [ns reloaded] (fire-shadow-ns-reset! ns))        ; before-load-src → real producer
     (doseq [[tag decl ns] registrations]
       (rules/register-custom-element! tag decl ns))         ; surviving sources re-run
     (rules/commit-reload!)))                                 ; ^:dev/after-load

#?(:cljs
   (deftest warm-rebuild-through-the-real-producer-under-one-real-build-id
     ;; The headline arm-2 fixture: the warm-watch/file-edit path end to end
     ;; through the SHIPPED producer, under ONE real (non-::default) dev build
     ;; identity resolved at the runtime seam. The rf2-4vm19 ruling resolves the
     ;; "chosen build-topology contract" AC to proving ONE real build's identity
     ;; through the whole cycle (NOT two-build isolation), and this drives that:
     ;; the delete and rename deltas fire ONLY because the producer conveys the
     ;; reset (the delete case is red without it — notify+commit alone evict
     ;; nothing), so this is a live FAIL-BEFORE lever on producer/annotation
     ;; load-bearingness.
     (let [original (unchecked-get (shadow-build-id-carrier) "build_id")]
       (try
         (set-shadow-build-id! "app")
         (rules/install-reload-source-producer!)      ; producer now resolves + tags :app
         (is (= :app (rules/current-build-id))
             "precondition: the seam resolves a REAL build identity, not ::default")

         ;; DELETE: app.a removed (re-runs declaring nothing), app.b untouched.
         (rules/reset-custom-elements!)
         (rules/register-custom-element! :x-el {:properties #{:help-text}} 'app.a)
         (rules/register-custom-element! :b-el {:properties #{:b}} 'app.b)
         (is (= #{[:app 'app.a] [:app 'app.b]} (rules/ledger-sources))
             "both sources are live in the graph under the real build id")
         (run-warm-rebuild! {:reloaded ['app.a] :registrations []})
         (is (= #{[:app 'app.b]} (rules/ledger-sources))
             "delete: the removed source drops out of the graph — through the
              producer, keyed under :app, no page reload")
         (is (= #{} (rules/custom-element-properties :x-el))
             "its element rows are gone")
         (is (= #{:b} (rules/custom-element-properties :b-el))
             "the untouched source is preserved")

         ;; RENAME: app.a → app.a2 through the real path.
         (rules/reset-custom-elements!)
         (rules/register-custom-element! :r-el {:properties #{:r}} 'app.a)
         (run-warm-rebuild! {:reloaded      ['app.a]
                             :registrations [[:r-el {:properties #{:r}} 'app.a2]]})
         (is (= #{[:app 'app.a2]} (rules/ledger-sources))
             "rename: the graph swaps the row old→new, under :app, through the producer")
         (is (= #{:r} (rules/custom-element-properties :r-el))
             "the renamed-in source classifies its element")

         ;; MOVE: same ns, new file — ownership preserved.
         (rules/reset-custom-elements!)
         (rules/register-custom-element! :m-el {:properties #{:m}} 'app.moved)
         (run-warm-rebuild! {:reloaded      ['app.moved]
                             :registrations [[:m-el {:properties #{:m}} 'app.moved]]})
         (is (= #{[:app 'app.moved]} (rules/ledger-sources))
             "move: a same-namespace move preserves ownership under :app")
         (is (= #{:m} (rules/custom-element-properties :m-el))
             "the moved source keeps its classification")
         (finally
           ;; restore the real carrier and re-install so every other producer test
           ;; finds exactly one re-frame.ui producer tagged with the ambient id.
           (set-shadow-build-id! original)
           (rules/install-reload-source-producer!))))))

;; ---------------------------------------------------------------------------
;; `shadow-build-notify` (rf2-4vm19) — the removed-source projection: shadow's
;; `:build-complete` broadcast of the authoritative `:build-sources` membership
;; reconciles the ledger's REMOVED delta through the existing cycle seam. These
;; pin the projection ALGEBRA; the end-to-end wiring (define injection, a real
;; watch, a live emitted runtime) is proven by `npm run test:ui-warm-watch`.
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- build-complete-msg
     "A `:build-complete` notify msg whose `:info :sources` carries exactly
     `source-maps` — the per-source image shadow's worker broadcasts
     (`shadow.build/extract-build-info`: each entry has `:ns` and/or
     `:provides`)."
     [source-maps]
     {:type :build-complete :info {:sources source-maps}}))

#?(:cljs
   (deftest build-notify-evicts-the-removed-membership-delta
     ;; The headline algebra: a namespace the successful graph no longer
     ;; contains loses its committed rows; members keep theirs. The eviction
     ;; runs through the existing cycle protocol and leaves no cycle open.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :keep-el {:properties #{:k}} 'app.keep)
     (rules/register-custom-element! :gone-el {:properties #{:g}} 'app.gone)
     (rules/shadow-build-notify (build-complete-msg [{:ns 'app.keep}]))
     (is (= #{} (rules/custom-element-properties :gone-el))
         "the namespace that left :build-sources is evicted from the aggregate")
     (is (= #{:k} (rules/custom-element-properties :keep-el))
         "a member namespace keeps its classification")
     (is (= #{[(rules/current-build-id) 'app.keep]} (rules/ledger-sources))
         "the ledger membership mirrors the broadcast graph")
     (is (= #{} (rules/in-flight-cycles))
         "the removal cycle committed and closed — nothing is left open")))

#?(:cljs
   (deftest build-notify-honours-provides-membership
     ;; extract-build-info reports `:provides` (a set) alongside `:ns`; a
     ;; namespace present only via :provides is a member, never a removal.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :p-el {:properties #{:p}} 'app.provided)
     (rules/shadow-build-notify
      (build-complete-msg [{:provides #{'app.provided 'app.alias}}]))
     (is (= #{:p} (rules/custom-element-properties :p-el))
         "a :provides-only member is not treated as removed")))

#?(:cljs
   (deftest build-notify-with-unchanged-membership-is-inert
     ;; The common case — every ledger source still in the graph — must move
     ;; NOTHING: no cycle churn, no republish (the aggregate watch stays
     ;; silent), no ledger transition.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :s-el {:properties #{:s}} 'app.stable)
     (let [publishes (atom 0)]
       (add-watch rules/custom-elements ::notify-inert (fn [_ _ _ _] (swap! publishes inc)))
       (try
         (rules/shadow-build-notify (build-complete-msg [{:ns 'app.stable}]))
         (is (zero? @publishes)
             "an unchanged membership publishes nothing — no removal cycle runs")
         (finally (remove-watch rules/custom-elements ::notify-inert)))
       (is (= #{:s} (rules/custom-element-properties :s-el)))
       (is (= #{} (rules/in-flight-cycles))))))

#?(:cljs
   (deftest build-notify-ignores-every-non-complete-broadcast
     ;; Only :build-complete carries an accepted graph. A failed build (and the
     ;; start/init chatter) must leave the last-known-good ledger untouched —
     ;; that IS the failed-compile/LKG contract at this seam; the successful
     ;; retry's :build-complete converges it.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :lkg-el {:properties #{:l}} 'app.lkg)
     (doseq [type [:build-failure :build-start :build-init]]
       (rules/shadow-build-notify {:type type :info {:sources []}})
       (is (= #{:l} (rules/custom-element-properties :lkg-el))
           (str "a " type " broadcast never evicts — last-known-good survives")))
     ;; the retry's accepted graph then converges the ledger
     (rules/shadow-build-notify (build-complete-msg []))
     (is (= #{} (rules/custom-element-properties :lkg-el))
         "the successful retry's membership is authoritative")))

#?(:cljs
   (deftest build-notify-reconciles-only-the-addressed-build
     ;; Rows keyed under ANOTHER build id (a test-state partition token here —
     ;; see §THE ISOLATION UNIT) are never this projection's to evict: the
     ;; broadcast is diffed against the ambient build's rows only.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :f-el {:properties #{:f}} 'app.shared :foreign-build)
     (rules/shadow-build-notify (build-complete-msg []))
     (is (= #{:f} (rules/custom-element-properties :f-el))
         "another build's row survives the ambient build's membership diff")
     (is (= #{[:foreign-build 'app.shared]} (rules/ledger-sources)))))

#?(:cljs
   (deftest build-notify-supersedes-a-stranded-cycle-and-still-evicts
     ;; A load task that threw leaves its cycle open forever (shadow skips
     ;; ^:dev/after-load on a failed chain). The projection's removal cycle
     ;; performs exactly the supersession the next before-load would — loud,
     ;; per rf2-84173 — and the removal still commits.
     (rules/reset-custom-elements!)
     (rules/register-custom-element! :z-el {:properties #{:z}} 'app.zombie)
     (rules/notify-reload!)                    ; the stranded cycle opens…
     (rules/note-reloaded-sources! ['app.other]) ; …and carries evidence
     (let [warnings (capturing-console-warn
                     (fn []
                       (rules/shadow-build-notify (build-complete-msg []))))]
       (is (= 1 (count warnings))
           "the supersession is DIAGNOSED, exactly as a before-load's would be"))
     (is (= #{} (rules/custom-element-properties :z-el))
         "the removal is projected despite the stranded predecessor cycle")
     (is (= #{} (rules/in-flight-cycles))
         "no cycle survives — the zombie was superseded and the removal committed")))
