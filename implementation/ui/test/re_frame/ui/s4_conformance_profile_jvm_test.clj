(ns re-frame.ui.s4-conformance-profile-jvm-test
  "The S4 view-substrate CONFORMANCE PROFILE, executable (07 §5; EP-0035;
  rf2-yho9j — the S4-E leaf of epic rf2-vxgfnd.96).

  This is the single authoritative, EXACT catalogue of the FROZEN S4 surface —
  the compiled-view substrate's presence + web-boundaries stage. S4 adds NO new
  verb to the facade: its forms were EXPORTED at S1 for symbol resolution and
  ASSERT at S4, so this profile pins BEHAVIOUR over an already-blessed surface.
  It fixes FOUR rosters:

    1. PRESENCE — the `ui/presence` retention boundary, the `ui/presence-phase`
       read, and the `ui.test/flush-presence!` fake-clock driver;
    2. CUSTOM ELEMENTS — the RULED property-vs-attribute classification
       (declaration is the sole classifier; kebab -> camelCase);
    3. the compile-tier A11Y roster — four `:rf.ui.compile/*` warnings with NO
       Spec 009 row and no runtime emission, plus the suppression round trip;
    4. the FOREIGN BOUNDARY — `ui/raw` / `ui/raw-fn` / a foreign component head
       (host-bearing, typed `:rf.error/jvm-host-op`) and `ui/html` (the single
       escaping bypass, which sanitises NOTHING).

  Plus two absences and one corpus: the document HEAD is host-owned (§3 — an
  absence of SURFACE, bound here as an absence), the S4 WALL (§5), and the W14
  dual-emitter PARITY rows S4 contributes to the compiled-view corpus (§4).

  Runtime behaviour itself is proven by the named proof homes (browser / JVM /
  corpus suites), not re-asserted here. What this guard adds is BINDING: every
  human row in spec/conformance/S4-view-conformance-profile.md is validated
  ROW BY ROW against these maps — keyed on the row's FIRST table cell, so prose
  mentions and cross-references elsewhere do not satisfy a row — and every
  named proof home must be a REAL test namespace on disk. Deleting a row,
  hollowing one out, or swapping a contract, error id or proof home between
  rows is red.

  Mirrors the shape rf2-pd0dh left on the S3 profile guard
  (`s3_conformance_profile_jvm_test`); the S3 surface is that file's and is not
  restated here."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui]
            [re-frame.ui.compiler.a11y :as a11y]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.parity-fixtures :as fx]
            [re-frame.ui.rules :as rules]
            ;; COMPOSITION, not duplication: the S3 guard owns the frozen S1-S3
            ;; rosters AND the row-binding machinery (repo-root / table-row-for /
            ;; section-between). This profile reuses both rather than restating
            ;; the S3 surface or re-implementing its helpers.
            [re-frame.ui.s3-conformance-profile-jvm-test :as s3]))

;; ---------------------------------------------------------------------------
;; (1) The frozen S4 FORMS — presence, custom elements, the foreign boundary.
;; Each entry names the namespace the form lives in, its kind, the loud-failure
;; ids the CODE must honour, the small EXACT contract fragments its human row
;; must carry, and BOTH proof homes. This map is the source of truth §1 of the
;; profile document restates.
;;
;; `:token` is the profile row's FIRST CELL (rows are keyed on it, so a mention
;; in prose or in another row's contract cell does not satisfy the row).
;; `:row-fragments` are deliberately SMALL exact fragments: enough that swapping
;; two rows' contracts, or hollowing one out, is red; not so many that ordinary
;; editorial rewording is.
;; ---------------------------------------------------------------------------

(def frozen-s4-forms
  {;; --- §1.1 presence -----------------------------------------------------
   'presence
   {:section       "### 1.1"
    :token         "`ui/presence`"
    :ns            're-frame.ui
    :kind          :template-form
    :direct-error  :rf.error/ui-tree-malformed
    :compile-errors [:rf.ui.compile/bad-presence
                     :rf.ui.compile/presence-unkeyed-child]
    :jvm-proof     "re-frame.ui.presence-jvm-test"
    :cljs-proof    "re-frame.ui.presence-dom-cljs-test"
    :row-fragments ["`:timeout-ms` is **mandatory**"
                    "the exit retention duration and the terminal bound"
                    "terminal removal is **exactly-once**"
                    "re-insertion interrupts the exit"
                    "**no node of its own**"
                    "unkeyed child is a build failure"]}

   'presence-phase
   {:section       "### 1.1"
    :token         "`ui/presence-phase`"
    :ns            're-frame.ui
    :kind          :fn
    :direct-error  nil                      ; a direct call is LEGAL
    :jvm-value     :present
    :jvm-proof     "re-frame.ui.presence-jvm-test"
    :cljs-proof    "re-frame.ui.presence-dom-cljs-test"
    :row-fragments ["**`:present` outside one**"
                    "reusable anywhere"
                    "JVM structural subset always yields `:present`"]}

   'flush-presence!
   {:section       "### 1.1"
    :token         "`ui.test/flush-presence!`"
    :ns            're-frame.ui.test
    :kind          :fn
    :cljs-only?    true                     ; CLJS mounted host only (rf2-n7jtp) — no JVM var
    :direct-error  nil
    :jvm-proof     "re-frame.ui.presence-jvm-test"
    :cljs-proof    "re-frame.ui.presence-dom-cljs-test"
    :row-fragments ["fake clock"
                    "no wall-clock sleep"
                    "drains to quiescence"
                    "CLJS mounted host only"]}

   ;; --- §1.2 custom elements ----------------------------------------------
   'custom-element
   {:section       "### 1.2"
    :token         "`ui/custom-element`"
    :ns            're-frame.ui
    :kind          :macro
    :compile-errors [:rf.ui.compile/bad-custom-element
                     :rf.ui.compile/custom-element-conflict]
    :jvm-proof     "re-frame.ui.custom-element-classification-jvm-test"
    :cljs-proof    "re-frame.ui.custom-element-classification-dom-cljs-test"
    :row-fragments ["closed `{:properties #{…}}` grammar"
                    "**kebab → camelCase**"
                    "`:accent-color` → `accentColor`"
                    "**undeclared element is all-attributes**"
                    "declaration is the **sole classifier**"]}

   ;; --- §1.4 the foreign boundary ------------------------------------------
   'raw
   {:section       "### 1.4"
    :token         "`ui/raw`"
    :ns            're-frame.ui
    :kind          :fn
    :host-op       :ui/raw
    :direct-error  :rf.error/jvm-host-op
    :compile-errors [:rf.ui.compile/bad-raw]
    :jvm-proof     "re-frame.ui.raw-foreign-boundary-jvm-test"
    :cljs-proof    "re-frame.ui.raw-foreign-boundary-dom-cljs-test"
    :row-fragments ["cannot render on the JVM"
                    "the raise is **lazy**"
                    "compiled siblings render normally"]}

   'raw-fn
   {:section       "### 1.4"
    :token         "`ui/raw-fn`"
    :ns            're-frame.ui
    :kind          :fn
    :direct-error  nil                      ; identity — it passes through
    :opaque-marker {:rf.ui/opaque :ui/raw-fn}
    :compile-errors [:rf.ui.compile/raw-fn-child]
    :jvm-proof     "re-frame.ui.raw-foreign-boundary-jvm-test"
    :cljs-proof    "re-frame.ui.raw-foreign-boundary-dom-cljs-test"
    :row-fragments ["foreign callback in prop position"
                    "`{:rf.ui/opaque :ui/raw-fn}` marker"]}

   'html
   {:section       "### 1.4"
    :token         "`ui/html`"
    :ns            're-frame.ui
    :kind          :fn
    :direct-error  nil                      ; the JVM arm BUILDS the node
    :shape-error   :rf.error/ui-tree-malformed
    :compile-errors [:rf.ui.compile/bad-html
                     :rf.ui.compile/html-not-sole-child]
    :jvm-proof     "re-frame.ui.raw-foreign-boundary-jvm-test"
    :cljs-proof    "re-frame.ui.raw-foreign-boundary-dom-cljs-test"
    :row-fragments ["**`re-frame.ui` sanitises nothing**"
                    "no `javascript:`-scheme gate"
                    "`string?` **shape** check"
                    "never a content check"
                    "empty string is a legal inert bypass"]}})

;; The foreign COMPONENT HEAD is a boundary without a facade var — it is any
;; resolvable non-view head. It still owns a §1.4 row, and its distinctness from
;; `ui/raw` (a different `:op` in the same typed error) is the load-bearing fact.
(def frozen-foreign-head
  {:section       "### 1.4"
   :token         "foreign component head"
   :host-op       :foreign-component
   :error         :rf.error/jvm-host-op
   :jvm-proof     "re-frame.ui.raw-foreign-boundary-jvm-test"
   :cljs-proof    "re-frame.ui.raw-foreign-boundary-dom-cljs-test"
   :row-fragments ["resolvable var without view metadata"
                   "**distinct** boundary from `ui/raw`"]})

;; ---------------------------------------------------------------------------
;; (2) The compile-tier A11Y roster (S4-C, rf2-74vlo). FOUR ids, closed. They
;; are `:rf.ui.compile/*` — COMPILE tier: no Spec 009 catalogue row, no runtime
;; emission, no production egress. `:rf.ui.compile/bad-suppress` is the loud
;; failure of the suppression grammar itself and is NOT a suppressible finding.
;; ---------------------------------------------------------------------------

(def frozen-a11y-roster
  {:rf.ui.compile/a11y-missing-accessible-name
   {:row-fragments ["no discernible accessible name"
                    "`aria-labelledby`"]}
   :rf.ui.compile/a11y-invalid-literal-aria
   {:row-fragments ["**literal** `aria-*` value"
                    "runtime-computed value is never flagged"]}
   :rf.ui.compile/a11y-click-non-interactive
   {:row-fragments ["non-interactive element"
                    "no keyboard path"]}
   :rf.ui.compile/a11y-presence-exit-interactive
   {:row-fragments ["**inline literal** markup under a presence boundary"
                    "outside the per-child phase Provider"
                    "keyed child view"
                    "`(ui/presence-phase) = :unmounting`"]}})

(def a11y-suppress-error :rf.ui.compile/bad-suppress)
;; The a11y roster's proof home moved with the compile tier itself (EP-0036
;; slice F3a): the analyzer, both emitters and the a11y diagnostics they mint
;; are owned by `implementation/freehand/` now, so the suite that proves this
;; roster lives there. The profile is donor-era evidence and is deleted at the
;; retirement gate; until then it must name where the proof ACTUALLY is.
(def a11y-proof-home "re-frame.freehand.a11y-diagnostics-cljs-test")

;; ---------------------------------------------------------------------------
;; (3) The W14 dual-emitter PARITY rows S4 contributes to the compiled-view
;; corpus (§4). Each names the corpus case id it pins and the small exact
;; fragments the §4 row must carry, so a row cannot be hollowed out and a case
;; cannot be deleted from the corpus while the profile still advertises it.
;; ---------------------------------------------------------------------------

(def frozen-s4-parity-cases
  {:presence-two
   {:view :presence-toasts
    :row-fragments ["**no node of its own**"
                    "marker fragment is stripped by `N`"
                    "phase Provider is invisible in markup"]}
   :presence-empty
   {:view :presence-toasts
    :row-fragments ["structurally inert on both hosts"]}
   :presence-inline
   {:view :presence-inline
    :row-fragments ["**mid-tree**"
                    "inline keyed literal markup"
                    "siblings are undisturbed"]}
   :ce-declared-attr-name
   {:view :ce-attrish
    :row-fragments ["standard HTML attribute spelling"
                    "`:tab-index`"
                    "stays a **PROPERTY** on both emitters"]}
   :trusted-hostile
   {:view :trusted
    :row-fragments ["crosses **verbatim** on both hosts"
                    "`javascript:` scheme is not gated"]}})

;; ---------------------------------------------------------------------------
;; (4) The S4-specific gate arms this profile certifies (07 §5 / EP-0034 §4).
;; S4 introduces NO new named gate — it GROWS two existing ones. The §6 roster
;; must name exactly this set.
;; ---------------------------------------------------------------------------

(def s4-arm-gates #{"G-7" "G-11"})

;; ---------------------------------------------------------------------------
;; (5) INHERITANCE. This profile is CUMULATIVE: S1-S3 ride the frozen S3 profile
;; by exact reference. The facade publics the S3 guard classifies as "not S3"
;; (`non-s3-facade-publics`) split into three buckets — the S4 delta this profile
;; rows, the S1/S2 carry-over forms the Spec 004 family catalogues, and the S5
;; delta (`render-static`, rf2-oo5lb). Asserting that partition is EXACT is what
;; makes a NEW PUBLIC SURFACE fail loudly: a new facade var lands in
;; `non-s3-facade-publics` (the S3 guard's own exact-set check) and must then be
;; classified here (S4 delta, S1/S2 carry-over, or the S5 delta bucket) or listed
;; below.
;; ---------------------------------------------------------------------------

(def s1-s2-carryover-publics
  "Facade publics that are neither S3 nor the S4 delta — the S1/S1b/S1c/S2 forms
  and the boot adapter, catalogued in the Spec 004 family. Listed explicitly so
  every NEW public var must be classified (S3 verb/view, S4 delta, the S5 delta,
  or here)."
  '#{adapter defview mount create-root render! hydrate-root unmount!
     frame-root frame-provider sub frame spread})

(def s5-facade-delta
  "The S5 facade delta — the ONE new re-frame.ui public S5 adds (rf2-oo5lb):
  `render-static`, the pure :server static-HTML render macro (non-hydrating: no
  manifest, no payload, no phase flip). Held as its own partition SLOT so the
  non-S3 exact-set classification recognises it as an S5 public rather than an
  unexpected surface member — the S5 conformance profile §3 named this the
  missing 'S5 bucket' prerequisite for landing render-static."
  '#{render-static})

(def s6-facade-delta
  "The S6 facade delta — the ONE new re-frame.ui public S6 adds (rf2-u53yy.2):
  `->react`, the OUTWARD interop bridge (export a compiled view as a foreign
  React component). Held as its own partition SLOT so the non-S3 exact-set
  classification recognises it as an S6 public rather than an unexpected surface
  member. Shipped ahead of the nominal S6 migration wave as a ratified
  UIx-review feature."
  '#{->react})

(def s3-ref-promotion
  "The substrate DOM-node host hook `ref` (rf2-u53yy.9) — promoted OUT of the
  re-frame.ui.react interop tier into the re-frame.ui facade. An S3-era host hook
  (peer of `local`), not an S4/S5/S6 delta and not an S1/S2 carry-over; held as
  its own partition SLOT so the non-S3 exact-set classification recognises it as
  the promoted ref rather than an unexpected surface member. Its runtime
  behaviour rides the react-interop JVM/DOM suites (not an S3 verb-arm gate), so
  it is a non-S3-verb facade public catalogued here."
  '#{ref})

(def s4-inherits-profile
  "The frozen profile this one inherits by exact reference. A broken inheritance
  link — the file gone, or the reference dropped from §0 — is red."
  "S3-view-conformance-profile.md")

;; ===========================================================================
;; Executable surface pins — the shipped code honours each catalogued contract.
;; ===========================================================================

;; Shared with the S3 guard — see the :require note above.
(def ^:private repo-root s3/repo-root)
(def ^:private table-row-for s3/table-row-for)
(def ^:private section-between s3/section-between)

(defn- profile-doc []
  (io/file (repo-root) "spec" "conformance" "S4-view-conformance-profile.md"))

(defn- s3-profile-doc []
  (io/file (repo-root) "spec" "conformance" "S3-view-conformance-profile.md"))

(defn- doc-lines [] (str/split-lines (slurp (profile-doc))))

(defn- err-id
  "Run `f`; -> the thrown `:rf.error/id`, or ::no-throw."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- ex-data-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ===========================================================================
;; Inheritance — composes with the S3 guard (the ruled S4-E obligation):
;; a new public surface, a broken inheritance link, or a moved/deleted proof row
;; must fail loudly.
;; ===========================================================================

(defn- s4-facade-forms
  "The S4-delta forms that live on the `re-frame.ui` facade (flush-presence! is
  `re-frame.ui.test`'s, so it is not a facade public)."
  []
  (set (keys (into {} (remove (fn [[_ v]] (= 're-frame.ui.test (:ns v)))
                              frozen-s4-forms)))))

(deftest new-public-surface-fails-loudly
  (testing "the non-S3 facade publics partition EXACTLY into the S4 delta + the S1/S2 carry-over + the S5 delta + the S6 delta + the S3 ref promotion — a NEW public var is red until it is classified"
    (let [non-s3     s3/non-s3-facade-publics
          classified (set/union (s4-facade-forms) s1-s2-carryover-publics
                                s5-facade-delta s6-facade-delta s3-ref-promotion)]
      (is (= non-s3 classified)
          (str "facade classification drift — "
               "unclassified (new?) publics: " (sort (set/difference non-s3 classified))
               "; classified-but-absent: " (sort (set/difference classified non-s3))))))
  (testing "the live facade agrees — every classified name actually resolves"
    (doseq [sym (set/union (s4-facade-forms) s1-s2-carryover-publics
                           s5-facade-delta s6-facade-delta s3-ref-promotion)]
      (is (some? (ns-resolve 're-frame.ui sym))
          (str "ui/" sym " is classified but does not resolve")))))

(deftest s4-does-not-double-classify-any-s3-surface
  (testing "no S4-delta form is also catalogued as an S3 verb, the S3 view, or a React wrapper"
    (doseq [sym (keys frozen-s4-forms)]
      (is (not (contains? s3/frozen-s3-verbs sym))
          (str sym " must not be double-classified as an S3 verb"))
      (is (not (contains? s3/frozen-s3-view sym))
          (str sym " must not be double-classified as the S3 framework view"))
      (is (not (contains? s3/frozen-react-wrappers sym))
          (str sym " must not be double-classified as a React interop wrapper")))))

(deftest inheritance-link-is-intact
  (testing "the frozen S3 profile this one inherits still exists"
    (is (.exists (s3-profile-doc))
        (str "broken inheritance link — " s4-inherits-profile " is missing")))
  (testing "§0 inherits S1-S3 by EXACT reference to the frozen S3 profile"
    (let [section (section-between (slurp (profile-doc)) "## 0." "## 1.")]
      (is (some? section) "the profile must have a §0 inheritance section and a §1 header")
      (when section
        (doseq [claim [s4-inherits-profile
                       "cumulative"
                       "by exact reference"
                       "does not restate"]]
          (is (str/includes? section claim)
              (str "§0 must state: " claim))))))
  (testing "the S4 profile does NOT restate the frozen S3 rosters (duplication IS drift)"
    (let [lines (doc-lines)]
      (doseq [sym (concat (keys s3/frozen-s3-verbs) (keys s3/frozen-s3-view))]
        (is (nil? (table-row-for lines (str "`ui/" sym "`")))
            (str "the S4 profile must not restate the S3 row for ui/" sym
                 " — S1-S3 are inherited by reference (§0)")))
      (doseq [sym (keys s3/frozen-react-wrappers)]
        (is (nil? (table-row-for lines (str "`react/" sym "`")))
            (str "the S4 profile must not restate the S3 row for react/" sym))))))

(deftest s4-forms-resolve-with-their-frozen-kinds
  (testing "every catalogued S4 form resolves in its namespace with the frozen kind"
    (doseq [[sym {:keys [ns kind cljs-only?]}] frozen-s4-forms]
      (let [v (ns-resolve ns sym)]
        (if cljs-only?
          (is (nil? v)
              (str sym " is CLJS-only — it has no JVM var in " ns
                   " (the JVM structural host has no presence lifecycle)"))
          (do
            (is (some? v) (str sym " must resolve in " ns))
            (case kind
              :macro (is (true? (:macro (meta v)))
                         (str sym " must be a def-level macro"))
              (is (not (:macro (meta v)))
                  (str sym " must be a fn, not a macro")))
            (is (not (:rf.ui/view (meta v)))
                (str sym " is an S4 form, not a compiled view"))))))))

(deftest presence-is-a-fail-loud-template-form
  (testing "a direct (ui/presence …) call fails loud — the compiler owns the form"
    (is (= :rf.error/ui-tree-malformed (err-id #(ui/presence {:timeout-ms 300})))))
  (testing "the catalogued direct-call error is the one the code raises"
    (is (= (:direct-error (frozen-s4-forms 'presence))
           (err-id #(ui/presence {:timeout-ms 300}))))))

(deftest presence-phase-reads-present-outside-a-boundary
  ;; The reusability law: a presence-aware child rendered ANYWHERE reads
  ;; :present, so it never needs a boundary to be legal.
  (is (= (:jvm-value (frozen-s4-forms 'presence-phase)) (ui/presence-phase)))
  (is (= :present (ui/presence-phase))
      "presence-phase outside a boundary (and on the JVM) is :present"))

;; `flush-presence!` is the CLJS mounted host only (the ratified ui.test
;; minimization deleted the JVM no-op — the JVM structural render has no presence
;; lifecycle, it renders :present). Its two-arity awaited-drain behaviour is
;; proven in re-frame.ui.presence-dom-cljs-test; the JVM half of the presence
;; story (presence renders :present, no lifecycle) is proven in
;; re-frame.ui.presence-jvm-test above.

(deftest raw-and-html-honour-their-catalogued-jvm-behaviour
  (testing "ui/raw is host-bearing: a JVM call raises the typed host-op error naming its op"
    (let [d (ex-data-of #(ui/raw ::anything))]
      (is (= (:direct-error (frozen-s4-forms 'raw)) (:rf.error/id d)))
      (is (= (:host-op (frozen-s4-forms 'raw)) (:op d))
          "the ex-data names WHICH host-bearing form was hit")))
  (testing "ui/raw-fn passes the fn through verbatim (identity-as-protocol)"
    (let [f (fn [_] :ok)]
      (is (identical? f (ui/raw-fn f)))))
  (testing "ui/html sanitises nothing — the JVM arm builds the verbatim leaf"
    (let [hostile "<a href=\"javascript:alert(1)\">x</a><b>a & b</b>"]
      (is (= {:html hostile} (ui/html hostile))
          "no escaping, no tag/attribute filter, no scheme gate")))
  (testing "the ONLY runtime guard on trusted markup is a `string?` SHAPE check"
    (is (= (:shape-error (frozen-s4-forms 'html)) (err-id #(ui/html 42))))
    (is (= {:html ""} (ui/html ""))
        "an empty string is a legal inert bypass — emptiness is not a content rule")))

;; --- presence compile-tier pins --------------------------------------------

(defn- presence-env []
  (env/make-env {:host :clj :ns-sym 'app.s4probe
                 :self 'self-view :self-id :app.s4probe/self-view
                 :resolver (fn [sym]
                             (case sym
                               presence {:fqn 're-frame.ui/presence :meta {}}
                               nil))}))

(defn- compile-err-id [form]
  (:rf.ui.compile/error (ex-data-of #(ana/analyze (presence-env) form))))

(deftest presence-compile-errors-are-the-catalogued-ids
  (let [{:keys [compile-errors]} (frozen-s4-forms 'presence)
        [bad-presence unkeyed] compile-errors]
    (testing "a MISSING :timeout-ms is a build failure — it is mandatory, not defaulted"
      (is (= bad-presence
             (compile-err-id '(presence {} (for [t ts] [:li {:key (:id t)} "a"]))))))
    (testing "a non-positive :timeout-ms is the same build failure"
      (is (= bad-presence
             (compile-err-id '(presence {:timeout-ms 0} (for [t ts] [:li {:key (:id t)} "a"]))))))
    (testing "an UNKEYED child under a presence boundary is a build failure"
      (is (= unkeyed
             (compile-err-id '(presence {:timeout-ms 300} [:li "no key"])))))
    (testing "the well-formed shapes compile (the checks are not blanket rejections)"
      ;; The LANDED grammar §1.1 now states. Both legal child shapes: the keyed
      ;; `(for …)`, keyed by construction, and the keyed LITERAL element, which
      ;; rf2-vxgfnd.96.1 (#6331) stopped wrongly rejecting.
      (is (= :presence (:op (ana/analyze (presence-env)
                                         '(presence {:timeout-ms 300}
                                            (for [t ts] [:li {:key (:id t)} "a"]))))))
      (is (= :presence (:op (ana/analyze (presence-env)
                                         '(presence {:timeout-ms 300}
                                            [:li {:key "a"} "a"]))))
          "a keyed literal child is legal (rf2-vxgfnd.96.1)"))
    (testing "a props-bearing but UNKEYED fragment does NOT pass as keyed (rf2-xoz1s, #6337)"
      ;; The permissive half: `[:<> {} …]` used to report its PROPS MAP's
      ;; presence as a key, so the boundary admitted a child it had no identity
      ;; to retain. Key presence is now `:key`'s own presence.
      (is (= unkeyed (compile-err-id '(presence {:timeout-ms 300} [:<> {} [:li "x"]]))))
      (is (= :presence (:op (ana/analyze (presence-env)
                                         '(presence {:timeout-ms 300}
                                            [:<> {:key "k"} [:li "x"]]))))
          "a genuinely keyed fragment is still legal"))))

;; --- custom-element pins ----------------------------------------------------

(deftest custom-element-property-names-lower-kebab-to-camelcase
  ;; The RULED name mapping the §1.2 row states — the declaration is the sole
  ;; classifier, so even a standard-attribute spelling camelizes when declared.
  (is (= "accentColor" (rules/custom-element-property-name "accent-color")))
  (is (= "helpText" (rules/custom-element-property-name "help-text")))
  (is (= "tabIndex" (rules/custom-element-property-name "tab-index"))))

;; --- a11y roster pins -------------------------------------------------------

(deftest a11y-roster-is-exactly-the-catalogue
  (testing "the compile-tier a11y roster is EXACTLY the four catalogued ids — an addition is a roster change"
    (is (= (set (keys frozen-a11y-roster)) a11y/a11y-warning-ids)
        (str "a11y roster drift — "
             "uncatalogued: " (sort (set/difference a11y/a11y-warning-ids
                                                    (set (keys frozen-a11y-roster))))
             "; catalogued-but-absent: " (sort (set/difference (set (keys frozen-a11y-roster))
                                                               a11y/a11y-warning-ids))))))

(deftest a11y-ids-are-compile-tier-with-no-spec-009-row
  (testing "every a11y id is a COMPILE-tier id (rf.ui.compile), not a runtime error/trace id"
    (doseq [id (keys frozen-a11y-roster)]
      (is (= "rf.ui.compile" (namespace id))
          (str id " must be a compile-tier id"))))
  (testing "no a11y id has a Spec 009 catalogue row — the compile tier has no runtime emission"
    (let [spec-009 (slurp (io/file (repo-root) "spec" "009-Instrumentation.md"))]
      (doseq [id (cons a11y-suppress-error (keys frozen-a11y-roster))]
        (is (not (str/includes? spec-009 (str id)))
            (str id " must NOT have a Spec 009 row — it is compile-tier only"))))))

(deftest malformed-a11y-suppression-is-a-loud-compile-error
  ;; The suppression round trip's loud half: an unknown id, or a blank / non-string
  ;; reason, is a build failure — never a silent no-op that hides a real finding.
  (let [env-fn #(-> (env/make-env {:host :clj :ns-sym 'app.s4suppress
                                   :self 'self-view :self-id :app.s4suppress/self-view
                                   :resolver (constantly nil)})
                    (assoc :self-children? false :self-closed-keys nil))
        reject (fn [form]
                 (:rf.ui.compile/error
                  (ex-data-of #(ana/analyze (env-fn) form))))]
    (testing "an unknown suppression id"
      (is (= a11y-suppress-error
             (reject '^{:rf.ui/suppress {:rf.ui.compile/a11y-nonsense "r"}}
                     [:p "fine"]))))
    (testing "a blank reason"
      (is (= a11y-suppress-error
             (reject '^{:rf.ui/suppress {:rf.ui.compile/a11y-click-non-interactive "  "}}
                     [:p "fine"]))))
    (testing "a non-string reason"
      (is (= a11y-suppress-error
             (reject '^{:rf.ui/suppress {:rf.ui.compile/a11y-click-non-interactive true}}
                     [:p "fine"]))))))

;; --- §3 the document head is host-owned: bound as an ABSENCE ----------------

(deftest no-head-targeting-surface-exists
  (testing "the re-frame.ui facade exposes no head-targeting public"
    (let [publics (set (map name (keys (ns-publics 're-frame.ui))))]
      (is (not (contains? publics "head")) "there is no ui/head")
      (is (empty? (filter #(str/includes? % "head") publics))
          (str "a head-targeting facade public appeared: "
               (sort (filter #(str/includes? % "head") publics))
               " — §3 of the profile says the document head is host-owned"))))
  (testing "the compiler's CLOSED op set carries no head channel"
    (is (not (contains? ana/node-ops :head)))
    (is (empty? (filter #(str/includes? (name %) "head") ana/node-ops))
        "no AST op may target the document head")))

;; --- §4 the W14 parity rows exist in the shipped corpus ---------------------

(deftest s4-parity-cases-are-in-the-shipped-corpus
  (testing "every catalogued S4 parity row is exactly one live corpus case over the view it names"
    (let [by-id (group-by :id fx/cases)]
      (doseq [[id {:keys [view]}] frozen-s4-parity-cases]
        (let [matches (get by-id id)]
          (is (= 1 (count matches))
              (str "corpus case " id " must exist exactly once in parity-fixtures/cases"))
          (when (= 1 (count matches))
            (is (= view (:view (first matches)))
                (str "corpus case " id " must render the catalogued view " view)))))))
  (testing "every view an S4 parity row names is a real compiled corpus view"
    (doseq [[id {:keys [view]}] frozen-s4-parity-cases]
      (is (some? (get fx/views view))
          (str "the view " view " named by S4 parity row " id " is not in parity-fixtures/views")))))

;; ===========================================================================
;; Document drift guard — the human profile stays EXACT against this surface,
;; validated row by row rather than by whole-document token presence.
;; ===========================================================================

(defn- check-row!
  "Assert the profile row keyed by `token` exists exactly once and carries every
  fragment + every id in `musts`."
  [lines token musts fragments]
  (let [row (table-row-for lines token)]
    (is (some? row)
        (str "the profile must catalogue " token " in exactly one table row"))
    (when row
      (doseq [m musts]
        (is (str/includes? row (str m))
            (str token "'s row must name " m)))
      (doseq [f fragments]
        (is (str/includes? row f)
            (str token "'s row must carry its contract fragment: " f))))
    row))

(deftest profile-document-exists
  (is (some? (repo-root)) "must locate the repo root (spec/conformance)")
  (is (.exists (profile-doc))
      (str "the S4 profile document must exist at " (profile-doc))))

(deftest profile-catalogues-every-s4-form-row-exactly
  (testing "each frozen S4 form has one §1 row naming its errors, proof homes and contract"
    (let [lines (doc-lines)]
      (doseq [[sym {:keys [token direct-error compile-errors shape-error host-op
                           jvm-proof cljs-proof row-fragments]}] frozen-s4-forms]
        (testing (str sym)
          (check-row! lines token
                      (concat (when direct-error [direct-error])
                              (when shape-error [shape-error])
                              (when host-op [host-op])
                              compile-errors
                              [jvm-proof cljs-proof])
                      row-fragments))))))

(deftest profile-catalogues-the-foreign-head-row-exactly
  (testing "the foreign component head owns its own §1.4 row, distinct from ui/raw"
    (let [{:keys [token error host-op jvm-proof cljs-proof row-fragments]} frozen-foreign-head]
      (check-row! (doc-lines) token
                  [error host-op jvm-proof cljs-proof]
                  row-fragments))))

(deftest profile-catalogues-every-a11y-row-exactly
  (testing "each a11y id has one §1.3 row naming what it fires on and the author remedy"
    (let [lines (doc-lines)]
      (doseq [[id {:keys [row-fragments]}] frozen-a11y-roster]
        (check-row! lines (str "`" id "`") [] row-fragments))))
  (testing "§1.3 pins the compile-tier posture, the closed roster, the suppression grammar, and its proof home"
    (let [section (section-between (slurp (profile-doc)) "### 1.3" "### 1.4")]
      (is (some? section) "the profile must have a §1.3 a11y section and a §1.4 header")
      (when section
        (doseq [claim ["no Spec 009 catalogue row"
                       "no runtime emission"
                       "closed"
                       "`^{:rf.ui/suppress {<id> \"reason\"}}`"
                       (str a11y-suppress-error)
                       a11y-proof-home]]
          (is (str/includes? section claim)
              (str "§1.3 must state: " claim)))))))

(deftest profile-catalogues-every-s4-parity-row-exactly
  (testing "each W14 S4 corpus row is one §4 table row naming the claim it pins"
    (let [lines (doc-lines)]
      (doseq [[id {:keys [row-fragments]}] frozen-s4-parity-cases]
        (check-row! lines (str "`" id "`") [] row-fragments))))
  (testing "§4 states the parity contract and names what is deliberately OUT of the corpus"
    (let [section (section-between (slurp (profile-doc)) "## 4." "## 5.")]
      (is (some? section) "the profile must have a §4 parity section and a §5 header")
      (when section
        (doseq [claim ["normalized structural equivalence"
                       "byte-identical HTML is *not* the contract"
                       "no JVM side to compare"
                       ":rf.error/jvm-host-op"]]
          (is (str/includes? section claim)
              (str "§4 must state: " claim)))))))

(deftest profile-names-real-proof-homes
  ;; A proof home that is not a real test namespace is a lie the row-binding
  ;; checks above cannot see — they only prove the STRING is in the row.
  (let [test-roots [(io/file (repo-root) "implementation" "ui" "test")
                    ;; The absorbed compile tier's suites (EP-0036 slice F3a).
                    (io/file (repo-root) "implementation" "freehand" "test")]
        exists?   (fn [ns-name]
                    (let [base (-> ns-name (str/replace "-" "_") (str/replace "." "/"))]
                      (some (fn [root]
                              (some #(.exists (io/file root (str base %)))
                                    [".clj" ".cljs" ".cljc"]))
                            test-roots)))]
    (doseq [home (concat (mapcat (juxt :jvm-proof :cljs-proof) (vals frozen-s4-forms))
                         [(:jvm-proof frozen-foreign-head)
                          (:cljs-proof frozen-foreign-head)
                          a11y-proof-home])]
      (is (exists? home)
          (str "the profile names proof home " home
               ", which is not a test namespace under implementation/ui/test "
               "or implementation/freehand/test")))))

;; --- the ruled sequencing, non-parity, S5-wall and hand-off obligations ------

(def landed-presence-grammar-fixes
  "The presence grammar bugs that GATED the S4-conforming declaration, and the
  PRs that closed them (rf2-vxgfnd.96.3). §1.1 must record them as LANDED and §8
  must declare the stage conforming on that basis."
  ["rf2-vxgfnd.96.1" "rf2-vxgfnd.96.2" "rf2-xoz1s" "#6331" "#6337"])

(def stale-blocker-phrases
  "The PRE-declaration wording. Each of these asserted an open presence grammar
  bug or a not-yet-conforming stage; all are now false, so their reappearance
  ANYWHERE in the profile is drift. Absence is the one claim a document-wide
  census states correctly — 'nowhere' is stronger than 'not in this section' —
  whereas the positive claims below are bound to the REGION that must carry
  them (rf2-vxcl7)."
  ["S4 is not yet declarable conforming"
   "not yet declarable"
   "bugs are **open**"
   "Closing both is part of the S4-conforming declaration"
   "track the POST-FIX presence grammar"
   "will ship once both close"])

(deftest profile-declares-s4-conforming-on-the-landed-grammar
  ;; REGION-bound, not document-wide: §1.1 and §8 legitimately name the SAME
  ;; beads and PRs, so a whole-document `includes?` would let either site alone
  ;; satisfy both checks — the exact false-green class rf2-vxcl7 fixed. Each
  ;; claim is asserted against the section that must carry it.
  (testing "§1.1 records the presence grammar as LANDED, naming every closed bug and its PR"
    (let [section (section-between (slurp (profile-doc)) "### 1.1" "### 1.2")]
      (is (some? section) "the profile must have a §1.1 presence section and a §1.2 header")
      (when section
        (doseq [claim (concat landed-presence-grammar-fixes
                              ["LANDED presence grammar"
                               "no presence grammar bug is open"])]
          (is (str/includes? section claim)
              (str "§1.1 must state: " claim))))))
  (testing "§8 DECLARES the stage conforming, on the cumulative profile and the green named suites"
    (let [text (slurp (profile-doc))
          tail (subs text (str/index-of text "## 8."))]
      (doseq [claim (concat landed-presence-grammar-fixes
                            ["S4 IS DECLARED CONFORMING"
                             "rf2-vxgfnd.96.3"
                             "**cumulative** inheritance of §0"
                             "**all green**"])]
        (is (str/includes? tail claim)
            (str "§8 must state: " claim)))))
  (testing "the pre-declaration blocker wording is gone from the WHOLE document — its return is drift"
    (let [text (slurp (profile-doc))]
      (doseq [stale stale-blocker-phrases]
        (is (not (str/includes? text stale))
            (str "stale pre-declaration wording returned to the profile: " stale))))))

(deftest profile-rows-the-intentional-non-parity-facts
  (testing "§4.1 rows each non-parity S4 fact against its real proof home — never the structural comparator"
    (let [lines (doc-lines)]
      (doseq [[token proof] {"`ui/raw` / foreign component head" "re-frame.ui.raw-foreign-boundary-jvm-test"
                             "the document head"                 "absence of surface"
                             "the a11y roster"                   a11y-proof-home
                             "presence's three-phase machine"    "re-frame.ui.presence-dom-cljs-test"}]
        (let [row (table-row-for lines token)]
          (is (some? row)
              (str "§4.1 must row the non-parity fact " token " in exactly one table row"))
          (when row
            (is (str/includes? row proof)
                (str token "'s non-parity row must name its real proof home: " proof))))))))

(deftest profile-defers-the-s5-surfaces-explicitly
  (testing "§5 lists the deliberate S5 non-surfaces — S4 claims conformance for none of them"
    (let [section (section-between (slurp (profile-doc)) "## 5." "## 6.")]
      (is (some? section) "the profile must have a §5 wall and a §6 header")
      (when section
        (doseq [claim ["rf2-vxgfnd.97"
                       "Root Manifest"
                       "hydration"
                       "`render-static`"
                       "phase"
                       "failed-root isolation"
                       "root-hydration half of W14"]]
          (is (str/includes? section claim)
              (str "§5 must defer to S5: " claim)))))))

(deftest profile-bounds-the-g7-growth
  (testing "the §6 G-7 ROW itself carries the S4-shapes-only bound"
    ;; Row-keyed, not section-keyed: the same phrase also appears in the §6
    ;; prose, so a section-wide substring check would stay green when the ROW
    ;; is hollowed out (the exact under-binding class rf2-pd0dh fixed for S3).
    (let [row (table-row-for (doc-lines) "**G-7**")]
      (is (some? row) "§6 must carry exactly one G-7 roster row")
      (when row
        (is (str/includes? row "**S4 shapes only**")
            "the G-7 row must bound its arm to S4 shapes only"))))
  (testing "§6 bounds G-7 to S4 shapes and disclaims the broader S1-S3 equivalence debt"
    (let [section (section-between (slurp (profile-doc)) "## 6." "## 7.")]
      (when section
        (doseq [claim ["**S4 shapes only**"
                       "Layer 1"
                       "mounted"
                       "rf2-55zsd"
                       "in scope here"]]
          (is (str/includes? section claim)
              (str "§6 must bound the G-7 growth: " claim)))))))

(deftest profile-carries-the-s5-handoff-row
  (testing "§7 CITES the obligations already recorded on rf2-vxgfnd.97 and creates no parallel ones"
    (let [section (section-between (slurp (profile-doc)) "## 7." "## 8.")]
      (is (some? section) "the profile must have a §7 hand-off section and a §8 header")
      (when section
        (doseq [claim ["rf2-vxgfnd.97"
                       "no parallel obligation"
                       "root-manifest hydration + failed-root isolation fixtures"
                       "rf2-d9yq3"
                       "hydrated presence starts `:present`"
                       "rf2-aozoi"
                       "SSR-adoption custom-element"
                       "semantic normalizer"
                       "render-fingerprint vocabulary"]]
          (is (str/includes? section claim)
              (str "§7 hand-off must cite: " claim)))))))

(deftest profile-cites-the-ruled-head-ownership
  (testing "§3 cites the rf2-3i7tr ruling and the ui/raw portal qualification — S4 adds no head machinery"
    (let [section (section-between (slurp (profile-doc)) "## 3." "## 4.")]
      (when section
        (doseq [claim ["rf2-3i7tr"
                       "normatively owns"
                       "Spec 011 normatively owns"
                       "**`ui/raw` does widen it, and that is deliberate.**"
                       "no head machinery"]]
          (is (str/includes? section claim)
              (str "§3 must cite the ruled ownership: " claim)))))))

(deftest profile-gate-roster-is-exactly-the-arm-set
  (testing "the §6 gate roster names EXACTLY the certified S4 arms — S4 adds NO new named gate"
    (let [roster (section-between (slurp (profile-doc)) "## 6." "## 7.")]
      (is (some? roster) "the profile must have a §6 gate roster and a §7 header")
      (when roster
        (let [named (set (map second (re-seq #"\*\*(G-\d+)\*\*" roster)))]
          (is (= s4-arm-gates named)
              (str "§6 gate-roster drift — "
                   "extra: " (sort (set/difference named s4-arm-gates))
                   "; missing: " (sort (set/difference s4-arm-gates named)))))
        (is (str/includes? roster "no new named gate")
            "§6 must state that S4 introduces no new named gate")))))

(deftest profile-documents-the-head-policy-and-the-non-surface-wall
  (let [text (slurp (profile-doc))]
    (testing "§3 records the host-owned head policy as an ABSENCE of surface"
      (let [section (section-between text "## 3." "## 4.")]
        (is (some? section) "the profile must have a §3 head section and a §4 header")
        (when section
          (doseq [claim ["No compiled *structural* form renders into `<head>`"
                         "no `ui/head`, and no head channel"
                         "**absence of surface**"
                         "`reg-head`"]]
            (is (str/includes? section claim)
                (str "§3 must state: " claim))))))
    (testing "§5 enumerates the explicit S4 non-surfaces (the wall)"
      (let [section (section-between text "## 5." "## 6.")]
        (is (some? section) "the profile must have a §5 wall and a §6 header")
        (when section
          (doseq [claim ["not an animation system"
                         "wrapper node"
                         "**retracted**"
                         "no optional `:timeout-ms`"
                         "sole classifier"
                         "**sanitisation**"
                         "head-targeting"
                         "compile-tier only"]]
            (is (str/includes? section claim)
                (str "the §5 wall must enumerate: " claim))))))))
