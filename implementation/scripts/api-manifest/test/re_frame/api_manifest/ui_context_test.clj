(ns re-frame.api-manifest.ui-context-test
  "Regression tests for the re-frame.ui AI context-sheet generator
  (rf2-u53yy.8).

  THE CONTRACT. The compile-rejection roster in
  `skills/re-frame2-ui-context/SKILL.md` is GENERATED from the compiler's
  own `env/fail!` / `env/warn!` didactic messages in
  `implementation/ui/src/re_frame/ui/compiler/analyze.cljc` — never a
  hand-maintained parallel list (which would drift). These tests pin:

    - the message RECONSTRUCTION algorithm (`render-msg`) on synthetic
      forms — concatenation within a `(str …)`, separated `(if …)`
      branches, dropped interpolations / `str/join` separators;
    - that the roster is DERIVED from the live analyzer source (a known
      compiler message appears in the extraction; the extraction is
      non-vacuous);
    - that the committed sheet is IN SYNC with a fresh regeneration — the
      drift lock the CI `--check` gate also enforces."
  (:require [clojure.test :refer [deftest is testing]]
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
            merges (\"option\" \"s\" -> \"options\"), and interpolations drop"
    (is (= "unknown options here"
           (render-msg '(str "unknown option" (when many? "s") " here" bad-value))))))

(deftest render-msg-drops-str-join-separator
  (testing "a nested (str/join sep coll) — a separator over interpolations —
            renders as nothing, not an orphaned separator literal (render-msg
            reconstructs; whitespace is collapsed later by message-text)"
    (let [out (render-msg '(str "option" (when x "s")
                               " " (str/join ", " (map pr-str bad))
                               " — the only option is :fallback"))]
      (is (str/includes? out "options"))                 ; plural suffix merged
      (is (str/includes? out "the only option is :fallback"))
      (is (not (str/includes? out ", "))))))             ; str/join separator dropped

(deftest render-msg-separates-if-branches
  (testing "both arms of an (if …) message render as / -separated alternatives;
            the test drops"
    (is (= "for a view / for a foreign component"
           (render-msg '(if (= :view kind) "for a view" "for a foreign component"))))))

(deftest render-msg-drops-interpolation-only-forms
  (testing "a message form with no string literal renders blank"
    (is (str/blank? (render-msg '(pr-str bindings))))
    (is (str/blank? (render-msg 'some-symbol)))))

;; ---------------------------------------------------------------------------
;; extract-diagnostics / roster — DERIVED from the live analyzer source.
;; ---------------------------------------------------------------------------

(deftest extraction-is-non-vacuous
  (testing "the analyzer source yields a substantial roster of
            :rf.ui.compile/* diagnostics (a broken walk would collapse this)"
    (let [r (uic/roster (uic/extract-diagnostics @uic/analyzer-source))]
      ;; live count is ~70; a floor well below that trips only on collapse.
      (is (>= (count r) 60)
          "expected >= 60 compile-diagnostic ids extracted from analyze.cljc")
      (is (every? #(str/starts-with? (str (:id %)) ":rf.ui.compile/") r)
          "every roster id is a :rf.ui.compile/* keyword"))))

(deftest roster-is-generated-not-hand-listed
  (testing "a KNOWN compiler message reaches the roster verbatim from
            analyze.cljc — proof the roster is derived, not transcribed. If the
            compiler reworded this diagnostic, this assertion (and the sync
            check below) would red until the sheet regenerates."
    (let [r        (uic/roster (uic/extract-diagnostics @uic/analyzer-source))
          by-id    (into {} (map (juxt :id :messages)) r)
          msgs-for #(str/join "\n" (get by-id %))]
      ;; dynamic-head names the literal-head rule + the ui/raw escape.
      (is (str/includes? (msgs-for :rf.ui.compile/dynamic-head) "heads must be literal"))
      ;; react-lazy-misplaced names the def-level fix — a distinctive message
      ;; no hand-list would coincidentally reproduce.
      (is (str/includes? (msgs-for :rf.ui.compile/react-lazy-misplaced) "DEF-LEVEL only"))
      ;; a reject id and a warn id both land, on their correct tiers.
      (is (= :reject  (:tier (first (filter #(= :rf.ui.compile/bad-spread (:id %)) r)))))
      (is (= :warning (:tier (first (filter #(= :rf.ui.compile/bare-fn-in-loop (:id %)) r))))))))

;; ---------------------------------------------------------------------------
;; The committed sheet is in sync — the drift lock (same as CI --check).
;; ---------------------------------------------------------------------------

(deftest committed-sheet-in-sync
  (testing "skills/re-frame2-ui-context/SKILL.md matches a fresh regeneration
            from the current compiler messages + authored prose. Regenerate
            with: clojure -M -m re-frame.api-manifest.ui-context"
    (is (true? (uic/check!)))))
