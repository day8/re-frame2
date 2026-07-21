(ns re-frame.api-manifest.ui-context-test
  "Regression tests for the re-frame.ui AI context-sheet generator
  (rf2-u53yy.8).

  THE CONTRACT. Two slices of `skills/re-frame2-ui-context/SKILL.md` are
  GENERATED from live repo data — never a hand-maintained parallel list
  (which would drift):

    - the COMPILE-REJECTION ROSTER, from the compiler's own didactic
      messages at every raise site across the BOUNDED source roster
      (`ui-context/compiler-source-files`);
    - the AUTHORING SURFACE, from the public API manifest
      (`spec/api-manifest.edn`).

  These tests pin:
    - the message RECONSTRUCTION algorithm (`render-msg`) on synthetic
      forms — `(str …)` concatenation, separated conditional branches, and
      the TYPED PLACEHOLDERS that keep a dropped interpolation from
      truncating a fix;
    - the EXACT diagnostic-id inventory the extraction sees (an add / remove
      anywhere in the bounded scope reds here), plus that the representative
      defview / root / custom-element / head-resolution / a11y / ui.test
      diagnostics are present so no category can disappear silently;
    - representative RENDERED messages (rejected-prop, keyword-child, tag,
      React-hook arity, callback) whose placeholders must survive;
    - that the authoring-surface disposition covers the manifest EXACTLY and
      teaches `ui/->react`;
    - that the committed sheet is IN SYNC with a fresh regeneration — the
      drift lock the CI `--check` gate also enforces."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [re-frame.api-manifest.ui-context :as uic]))

;; ---------------------------------------------------------------------------
;; render-msg — the reconstruction algorithm, on synthetic forms (pure).
;; ---------------------------------------------------------------------------

(def ^:private render-msg #'uic/render-msg)

(deftest render-msg-plain-string
  (testing "a bare string message renders as itself"
    (is (= "cond needs test/branch pairs" (render-msg "cond needs test/branch pairs")))))

(deftest render-msg-concatenates-within-str
  (testing "a (str …) CONCATENATES its args so a (when … \"s\") plural suffix
            merges (\"option\" \"s\" -> \"options\"), and an interpolation
            renders as a TYPED PLACEHOLDER rather than vanishing"
    (is (= "unknown options here: ‹bad-value›"
           (render-msg '(str "unknown option" (when many? "s") " here: " bad-value))))))

(deftest render-msg-str-join-becomes-one-placeholder
  (testing "a (str/join sep coll) — a separator over interpolations — collapses
            to ONE placeholder labelled by the collection, dropping the literal
            separator (never leaking an orphaned \", \")"
    (let [out (render-msg '(str "options " (str/join ", " (map pr-str bad)) " only"))]
      (is (= "options ‹bad› only" out))
      (is (not (str/includes? out ", "))))))            ; str/join separator dropped

(deftest render-msg-placeholder-peels-wrappers
  (testing "a placeholder label is the interpolation's VALUE symbol —
            wrapper heads (pr-str, name) are dropped, the value they wrap wins"
    (is (= "‹form›" (render-msg '(pr-str (name form)))))
    (is (= "‹bindings›" (render-msg '(pr-str bindings))))
    (is (= "‹argc›" (render-msg 'argc)))))

(deftest render-msg-literal-numbers-and-keywords
  (testing "a literal number/keyword written INTO a message survives as itself
            (only value SYMBOLS become placeholders)"
    (is (= "n=3 k=:foo" (render-msg '(str "n=" 3 " k=" :foo))))))

(deftest render-msg-separates-if-branches
  (testing "both arms of an (if …) message render as / -separated alternatives;
            the test drops"
    (is (= "for a view / for a foreign component"
           (render-msg '(if (= :view kind) "for a view" "for a foreign component"))))))

;; ---------------------------------------------------------------------------
;; extract-diagnostics / roster — DERIVED from the bounded compiler sources.
;; ---------------------------------------------------------------------------

(deftest extraction-is-non-vacuous
  (testing "the bounded compiler-source roster yields a substantial roster of
            :rf.ui.compile/* diagnostics (a broken walk would collapse this)"
    (let [r (uic/roster (uic/extract-diagnostics))]
      (is (>= (count r) 90)
          "expected >= 90 compile-diagnostic ids across the bounded source roster")
      (is (every? #(str/starts-with? (str (:id %)) ":rf.ui.compile/") r)
          "every roster id is a :rf.ui.compile/* keyword")
      (is (not (contains? (uic/roster-ids r) :rf.ui.compile/error))
          ":rf.ui.compile/error is the ex-data envelope key, never a roster row"))))

(def ^:private expected-diagnostic-ids
  "The EXACT diagnostic-id inventory the extraction must see across the bounded
   compiler-source roster. Adding / removing / renaming a diagnostic anywhere
   in that scope reds this pin until the inventory (and the sheet) are updated —
   the completeness lock the audit requires. Grouped by front-door source."
  #{;; compiler.cljc — defview parse + options + lazy + custom-element
    :rf.ui.compile/bad-custom-element
    :rf.ui.compile/bad-defview-args
    :rf.ui.compile/bad-lazy
    :rf.ui.compile/bad-view-id
    :rf.ui.compile/custom-element-conflict
    :rf.ui.compile/unknown-option
    ;; header.cljc — header/argv shape
    :rf.ui.compile/key-prop-declared
    :rf.ui.compile/positional-args
    ;; root.cljc — root identity / mount / static / hydrate
    :rf.ui.compile/bad-disambiguator
    :rf.ui.compile/bad-root-id
    :rf.ui.compile/bad-root-opts
    :rf.ui.compile/client-entry-on-jvm
    :rf.ui.compile/identity-opts-at-hydrate
    :rf.ui.compile/missing-root-id
    :rf.ui.compile/no-single-mounted-view
    :rf.ui.compile/runtime-root-form
    :rf.ui.compile/static-root-requires-runtime
    :rf.ui.compile/static-root-unproven-dependency
    :rf.ui.compile/ui-render-static-jvm-only
    ;; env.cljc — head resolution
    :rf.ui.compile/dynamic-head
    :rf.ui.compile/unresolved-head
    ;; a11y.cljc — suppression reject + the four warnings
    :rf.ui.compile/bad-suppress
    :rf.ui.compile/a11y-click-non-interactive
    :rf.ui.compile/a11y-invalid-literal-aria
    :rf.ui.compile/a11y-missing-accessible-name
    :rf.ui.compile/a11y-presence-exit-interactive
    ;; test.cljc — ui.test front doors
    :rf.ui.compile/bad-test-render-form
    :rf.ui.compile/bad-test-root
    :rf.ui.compile/ui-test-jvm-only
    ;; analyze.cljc — the template grammar body
    :rf.ui.compile/bad-class
    :rf.ui.compile/bad-client-only
    :rf.ui.compile/bad-cond
    :rf.ui.compile/bad-effect
    :rf.ui.compile/bad-error-boundary
    :rf.ui.compile/bad-event-vector
    :rf.ui.compile/bad-for
    :rf.ui.compile/bad-fragment-props
    :rf.ui.compile/bad-frame-provider
    :rf.ui.compile/bad-frame-root
    :rf.ui.compile/bad-handler-options
    :rf.ui.compile/bad-html
    :rf.ui.compile/bad-if
    :rf.ui.compile/bad-let
    :rf.ui.compile/bad-presence
    :rf.ui.compile/bad-raw
    :rf.ui.compile/bad-render-fn
    :rf.ui.compile/bad-slot
    :rf.ui.compile/bad-spread
    :rf.ui.compile/bad-spread-safe
    :rf.ui.compile/bad-style
    :rf.ui.compile/bad-tag
    :rf.ui.compile/bad-ui-callback
    :rf.ui.compile/bad-ui-event
    :rf.ui.compile/bad-ui-handler
    :rf.ui.compile/bare-fn-prop
    :rf.ui.compile/bare-fn-ref
    :rf.ui.compile/capability-in-fallback
    :rf.ui.compile/children-not-accepted
    :rf.ui.compile/children-prop
    :rf.ui.compile/collection-attr-value
    :rf.ui.compile/constant-list-key
    :rf.ui.compile/contradictory-handler-options
    :rf.ui.compile/duplicate-id-sugar
    :rf.ui.compile/dynamic-props-map
    :rf.ui.compile/frame-in-loop
    :rf.ui.compile/frame-root-misplaced
    :rf.ui.compile/hook-misplaced
    :rf.ui.compile/html-in-textarea
    :rf.ui.compile/html-not-sole-child
    :rf.ui.compile/id-sugar-conflict
    :rf.ui.compile/impure-slot-body
    :rf.ui.compile/keyword-child
    :rf.ui.compile/lazy-seq-child
    :rf.ui.compile/loop-capturing-handler
    :rf.ui.compile/markup-returning-map
    :rf.ui.compile/multi-form-body
    :rf.ui.compile/nested-for-body
    :rf.ui.compile/non-keyword-prop
    :rf.ui.compile/presence-unkeyed-child
    :rf.ui.compile/raw-fn-child
    :rf.ui.compile/raw-text-children
    :rf.ui.compile/react-hook-bad-deps
    :rf.ui.compile/react-hook-misplaced
    :rf.ui.compile/react-lazy-misplaced
    :rf.ui.compile/rejected-prop-spelling
    :rf.ui.compile/render-fn-misplaced
    :rf.ui.compile/slot-arity
    :rf.ui.compile/spread-internal-view
    :rf.ui.compile/spread-safe-owned-key
    :rf.ui.compile/sub-in-loop
    :rf.ui.compile/textarea-children
    :rf.ui.compile/undeclared-prop
    :rf.ui.compile/unkeyed-list-item
    :rf.ui.compile/unsupported-form
    :rf.ui.compile/void-children
    ;; warnings shared across analyze.cljc
    :rf.ui.compile/bare-fn-in-loop
    :rf.ui.compile/controlled-input-async-handler
    :rf.ui.compile/placeholder-not-top-level})

(deftest exact-id-inventory
  (testing "the extracted diagnostic-id set EQUALS the pinned inventory — a
            diagnostic added / removed / renamed anywhere in the bounded scope
            reds here (and the sheet) until consciously handled"
    (let [actual (set (uic/roster-ids))]
      (is (= expected-diagnostic-ids actual)
          (str "diagnostic-id inventory drift.\n"
               "  new (extracted, not pinned): "
               (sort (set/difference actual expected-diagnostic-ids)) "\n"
               "  gone (pinned, not extracted): "
               (sort (set/difference expected-diagnostic-ids actual)))))))

(deftest representative-categories-present
  (testing "each front-door category contributes at least its representative
            diagnostics — proof the bounded roster is read whole, not just
            analyze.cljc (the pre-audit scope)"
    (let [ids (set (uic/roster-ids))]
      (doseq [[category id]
              [[:defview        :rf.ui.compile/positional-args]
               [:defview        :rf.ui.compile/unknown-option]
               [:root           :rf.ui.compile/missing-root-id]
               [:root           :rf.ui.compile/no-single-mounted-view]
               [:custom-element :rf.ui.compile/custom-element-conflict]
               [:head-resolution :rf.ui.compile/unresolved-head]
               [:a11y           :rf.ui.compile/a11y-missing-accessible-name]
               [:a11y           :rf.ui.compile/bad-suppress]
               [:ui.test        :rf.ui.compile/bad-test-render-form]
               [:ui.test        :rf.ui.compile/ui-test-jvm-only]]]
        (is (contains? ids id)
            (str category " diagnostic " id " missing — a whole front-door "
                 "source may have dropped out of the bounded roster"))))))

(deftest roster-tiers-are-correct
  (testing "a11y report! sites and analyzer warn! sites classify as WARNINGS;
            fail / fail! / compile-error sites as REJECTS"
    (let [r      (uic/roster (uic/extract-diagnostics))
          tier   (fn [id] (:tier (first (filter #(= id (:id %)) r))))]
      (is (= :warning (tier :rf.ui.compile/a11y-click-non-interactive)))  ; a11y report!
      (is (= :warning (tier :rf.ui.compile/bare-fn-in-loop)))             ; analyzer warn!
      (is (= :reject  (tier :rf.ui.compile/bad-suppress)))                ; a11y env/fail!
      (is (= :reject  (tier :rf.ui.compile/bad-test-root)))               ; ui.test compile-error
      (is (= :reject  (tier :rf.ui.compile/missing-root-id))))))          ; root fail

;; ---------------------------------------------------------------------------
;; Rendered messages — placeholders PRESERVED (the audit's item-3 regression).
;;
;; Each pin targets a message the pre-audit generator TRUNCATED by deleting
;; its interpolations ("String content wants", "is not a prop … Use",
;; "write :)", "takes – argument(s)", "at … got"). The typed placeholder must
;; now stand where the value goes, keeping the fix a complete sentence.
;; ---------------------------------------------------------------------------

(defn- messages-for [id]
  (let [r (uic/roster (uic/extract-diagnostics))]
    (str/join "\n" (:messages (first (filter #(= id (:id %)) r))))))

(deftest pinned-messages-preserve-placeholders
  (testing "rejected-prop-spelling — the prop and its replacement survive"
    (let [m (messages-for :rf.ui.compile/rejected-prop-spelling)]
      (is (str/includes? m "‹k› is not a prop"))
      (is (str/includes? m "Use ‹replacement›"))))
  (testing "keyword-child — 'String content wants' is no longer a dead end"
    (let [m (messages-for :rf.ui.compile/keyword-child)]
      (is (str/includes? m "String content wants ‹form›"))))
  (testing "bad-tag — the '(write :‹s›)' fix keeps its value"
    (let [m (messages-for :rf.ui.compile/bad-tag)]
      (is (str/includes? m "(write :‹s›)"))))
  (testing "unsupported-form — the React-hook arity keeps its counts"
    (let [m (messages-for :rf.ui.compile/unsupported-form)]
      (is (str/includes? m "(react/‹name› …) takes"))
      (is (str/includes? m "got ‹argc›"))))
  (testing "bad-ui-event — the callback names its site and offending binding"
    (let [m (messages-for :rf.ui.compile/bad-ui-event)]
      (is (str/includes? m "at ‹k›"))
      (is (str/includes? m "got ‹bindings›")))))

;; ---------------------------------------------------------------------------
;; Authoring surface — driven by the API manifest (the audit's item-4 gap).
;; ---------------------------------------------------------------------------

(deftest authoring-disposition-covers-manifest-exactly
  (testing "every public re-frame.ui var is classified, and nothing stale
            remains — the disposition key set EQUALS the manifest var set"
    (let [manifest (uic/manifest-ui-vars)
          declared (set (keys uic/authoring-disposition))]
      (is (= manifest declared)
          (str "authoring-disposition out of sync with the manifest.\n"
               "  unclassified: " (sort (set/difference manifest declared)) "\n"
               "  stale: " (sort (set/difference declared manifest)))))))

(deftest to-react-is-taught
  (testing "ui/->react (shipped by PR #6583) is present in the manifest and
            classified :taught — the drift the audit caught is closed"
    (is (contains? (uic/manifest-ui-vars) "->react"))
    (is (= :taught (get uic/authoring-disposition "->react")))))

;; ---------------------------------------------------------------------------
;; The committed sheet is in sync — the drift lock (same as CI --check).
;; ---------------------------------------------------------------------------

(deftest committed-sheet-in-sync
  (testing "skills/re-frame2-ui-context/SKILL.md matches a fresh regeneration
            from the current compiler messages + manifest + authored prose.
            Regenerate with: clojure -M -m re-frame.api-manifest.ui-context"
    (is (true? (uic/check!)))))
