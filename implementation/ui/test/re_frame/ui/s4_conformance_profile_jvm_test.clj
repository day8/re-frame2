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
            [re-frame.ui.test :as uit]))

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
    :direct-error  nil
    :jvm-value     nil                      ; synchronous no-op on the JVM
    :arities       #{0 1}
    :jvm-proof     "re-frame.ui.presence-jvm-test"
    :cljs-proof    "re-frame.ui.presence-dom-cljs-test"
    :row-fragments ["fake clock"
                    "no wall-clock sleep"
                    "drains to quiescence"
                    "synchronous `nil` no-op on the JVM"]}

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
(def a11y-proof-home "re-frame.ui.a11y-diagnostics-cljs-test")

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

;; ===========================================================================
;; Executable surface pins — the shipped code honours each catalogued contract.
;; ===========================================================================

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d) nil
      (.exists (io/file d "spec" "conformance")) d
      :else (recur (.getParentFile d)))))

(defn- profile-doc []
  (io/file (repo-root) "spec" "conformance" "S4-view-conformance-profile.md"))

(defn- err-id
  "Run `f`; -> the thrown `:rf.error/id`, or ::no-throw."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- ex-data-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest s4-forms-resolve-with-their-frozen-kinds
  (testing "every catalogued S4 form resolves in its namespace with the frozen kind"
    (doseq [[sym {:keys [ns kind]}] frozen-s4-forms]
      (let [v (ns-resolve ns sym)]
        (is (some? v) (str sym " must resolve in " ns))
        (case kind
          :macro (is (true? (:macro (meta v)))
                     (str sym " must be a def-level macro"))
          (is (not (:macro (meta v)))
              (str sym " must be a fn, not a macro")))
        (is (not (:rf.ui/view (meta v)))
            (str sym " is an S4 form, not a compiled view"))))))

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

(deftest flush-presence-is-a-jvm-no-op-at-both-arities
  (let [{:keys [arities jvm-value]} (frozen-s4-forms 'flush-presence!)]
    (is (= #{0 1} arities))
    (is (= jvm-value (uit/flush-presence!)) "zero-arity JVM flush is a nil no-op")
    (is (= jvm-value (uit/flush-presence! 500)) "ms-arity JVM flush is a nil no-op")))

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
    (testing "the well-formed shape compiles (the checks are not blanket rejections)"
      ;; The keyed `(for …)` — keyed by construction. NOTE: a keyed LITERAL
      ;; element is currently still rejected here; that is the open bug
      ;; rf2-vxgfnd.96.1, deliberately not asserted either way by this profile.
      (is (= :presence (:op (ana/analyze (presence-env)
                                         '(presence {:timeout-ms 300}
                                            (for [t ts] [:li {:key (:id t)} "a"])))))))))

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

(defn- table-row-for
  "The single Markdown table row whose FIRST cell is exactly `token`, or nil when
  there is not exactly one. Keying on the first cell (not mere occurrence) binds
  a form to its OWN §1 catalogue row: prose mentions, wall bullets, and rows that
  merely reference the form mid-contract do not match."
  [lines token]
  (let [first-cell (fn [line]
                     (let [t (str/triml line)]
                       (when (str/starts-with? t "|")
                         (-> t (subs 1) (str/split #"\|") first str/trim))))
        rows       (filter #(= token (first-cell %)) lines)]
    (when (= 1 (count rows)) (first rows))))

(defn- section-between
  "The region of `text` from header `from` up to header `to`, so a claim is read
  from the section that owns it rather than from incidental prose elsewhere."
  [text from to]
  (let [start (str/index-of text from)
        end   (str/index-of text to)]
    (when (and start end (< start end)) (subs text start end))))

(defn- doc-lines [] (str/split-lines (slurp (profile-doc))))

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
  (let [test-root (io/file (repo-root) "implementation" "ui" "test")
        exists?   (fn [ns-name]
                    (let [base (-> ns-name (str/replace "-" "_") (str/replace "." "/"))]
                      (some #(.exists (io/file test-root (str base %)))
                            [".clj" ".cljs" ".cljc"])))]
    (doseq [home (concat (mapcat (juxt :jvm-proof :cljs-proof) (vals frozen-s4-forms))
                         [(:jvm-proof frozen-foreign-head)
                          (:cljs-proof frozen-foreign-head)
                          a11y-proof-home])]
      (is (exists? home)
          (str "the profile names proof home " home
               ", which is not a test namespace under implementation/ui/test")))))

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
          (doseq [claim ["No compiled-view form renders into `<head>`"
                         "no `ui/head`"
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
