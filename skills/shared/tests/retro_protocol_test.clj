;;;; tests/retro_protocol_test.clj — structural regression for the shared
;;;; retro protocol (rf2-y1tqa).
;;;;
;;;; The shared protocol carries three normative locks that the rf2-g6auh
;;;; security audit verified and that PR #1116 + #1127 implemented:
;;;;
;;;;   1. Untrusted-evidence boundary  — Finding 1, High
;;;;   2. Universal redaction          — Finding 2, Medium
;;;;   3. Edit-gate split              — Finding 3, recommendation
;;;;
;;;; The audit's Finding 4 was PARTIAL — the prose was in place but no
;;;; regression suite asserted the locks. This file closes that gap by
;;;; pinning the load-bearing phrasings as a structural drift detector.
;;;;
;;;; This is the CHEAP class of drift the protocol can suffer: a future
;;;; edit silently weakening the wording (e.g. dropping "MUST ignore",
;;;; collapsing the four attacker classes into one, removing the
;;;; placeholder convention, deleting the "When in doubt, gate"
;;;; tie-break). A conversation-driving variant — actually replaying
;;;; injection fixtures against a fresh agent and asserting non-
;;;; compliance — is the fidelity-ideal version of this surface, and
;;;; lives under `fixtures/` as document-runnable scenarios (no harness
;;;; yet).
;;;;
;;;; Mirrors the shape of
;;;; `skills/re-frame-pair2/tests/prompts/prompt_regression_test.clj`
;;;; (the only other prose-regression test in the corpus).
;;;;
;;;; Run:    bb tests/retro_protocol_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns retro-protocol-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private shared-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)   ;; tests/
      (.getParentFile))) ;; skills/shared/

(def ^:private skills-root
  (.getParentFile shared-root))  ;; skills/

(defn- slurp-rel [parent rel]
  (slurp (io/file parent rel)))

(def ^:private protocol-md
  (delay (slurp-rel shared-root "retro-protocol.md")))

(def ^:private improver-skill-md
  (delay (slurp-rel skills-root "re-frame2-improver/SKILL.md")))

(def ^:private pair-retro2-skill-md
  (delay (slurp-rel skills-root "re-frame-pair-retro2/SKILL.md")))

;; ---------------------------------------------------------------------------
;; Section extraction — like the pair2 prompt-regression substrate, each
;; assertion targets the *section* the lock belongs in, so a sloppy edit
;; that moves a phrase out of its normative home is still caught.
;; ---------------------------------------------------------------------------

(defn- section
  "Return the chunk of `md` starting at the heading containing `anchor`
   and ending at the next `## ` heading (or EOF). Empty if no match."
  [md anchor]
  (let [pat (re-pattern
             (str "(?ms)## .*"
                  (java.util.regex.Pattern/quote anchor)
                  ".*?(?=^## |\\z)"))]
    (or (re-find pat md) "")))

(defn- contains-any? [text alts]
  (some #(str/includes? text %) alts))

;; ---------------------------------------------------------------------------
;; Lock 1 — Untrusted-evidence boundary
;; ---------------------------------------------------------------------------

(deftest untrusted-evidence-section-exists
  (testing "## Untrusted-evidence boundary heading present"
    (is (str/includes? @protocol-md "## Untrusted-evidence boundary")
        (str "The Untrusted-evidence boundary section was renamed or "
             "deleted. This is a normative section locked by the "
             "rf2-g6auh audit (Finding 1, High). Restore the heading "
             "or update this test AND the audit-verification doc."))))

(deftest untrusted-evidence-states-data-not-instructions
  (testing "evidence is 'data, not instructions'"
    (let [s (section @protocol-md "Untrusted-evidence boundary")]
      (is (contains-any? s ["data, not instructions"])
          (str "The cardinal phrase 'data, not instructions' is "
               "missing from the Untrusted-evidence boundary section. "
               "This is the core principle of the prompt-injection lock.")))))

(deftest untrusted-evidence-enumerates-four-attacker-classes
  (testing "four attacker classes still enumerated"
    (let [s (section @protocol-md "Untrusted-evidence boundary")]
      ;; The audit found the original protocol had ZERO of these. The
      ;; lock requires all four classes named with at least a
      ;; representative phrasing. Alternation per class to survive
      ;; reasonable rewording.
      (testing "tool-use redirection class"
        (is (contains-any? s ["change which tools" "skip the redaction"
                              "without asking"])
            "Tool-use redirection attacker class missing."))
      (testing "approval-gate relaxation class"
        (is (contains-any? s ["approval gate" "pre-approved"
                              "already said yes"])
            "Approval-gate relaxation attacker class missing."))
      (testing "scope / routing redirection class"
        (is (contains-any? s ["redirect scope" "skip the catalogue"
                              "file this against"])
            "Scope/routing redirection attacker class missing."))
      (testing "exfiltration / read-expansion class"
        (is (contains-any? s ["exfiltrate" "expand reads"
                              "id_rsa" "environment variables"])
            "Exfiltration/read-expansion attacker class missing.")))))

(deftest untrusted-evidence-must-ignore-is-imperative
  (testing "phrasing is imperative — MUST ignore"
    (let [s (section @protocol-md "Untrusted-evidence boundary")]
      (is (contains-any? s ["MUST ignore"])
          (str "The imperative 'MUST ignore' was downgraded. RFC-style "
               "MUST is load-bearing — 'should' or 'try to' is too "
               "soft for a security boundary.")))))

(deftest untrusted-evidence-handles-appears-to-address-the-agent
  (testing "comments/docstrings addressing the agent are still data"
    (let [s (section @protocol-md "Untrusted-evidence boundary")]
      (is (contains-any? s ["appear to address the agent"
                            "AI: ignore" "Claude, please run"])
          (str "The 'comments addressing the agent are still data' "
               "carve-out is missing. This was the audit's concrete "
               "worked example.")))))

(deftest untrusted-evidence-user-confirmation-is-single-shot
  (testing "user-confirmation exception is single-shot, scoped"
    (let [s (section @protocol-md "Untrusted-evidence boundary")]
      (is (contains-any? s ["single-shot"])
          (str "The user-confirmation exception was not flagged as "
               "single-shot. Without this scoping rule the exception "
               "swallows the whole protection (one 'yes' would re-trust "
               "all subsequent evidence)."))
      (is (contains-any? s ["does not persist" "not promote"
                            "future steps"])
          (str "The non-persistence clause is missing from the "
               "user-confirmation exception.")))))

(deftest untrusted-evidence-handles-hostile-rendering
  (testing "summarise rather than quote when evidence is hostile"
    (let [s (section @protocol-md "Untrusted-evidence boundary")]
      (is (contains-any? s ["summarise rather than quote"
                            "propagate the injection"])
          (str "The 'don't render hostile evidence inline' "
               "carrier-case handling is missing.")))))

;; ---------------------------------------------------------------------------
;; Lock 2 — Universal redaction
;; ---------------------------------------------------------------------------

(deftest redaction-section-exists-and-is-universal
  (testing "## Redaction (universal) heading present"
    (is (str/includes? @protocol-md "## Redaction (universal)")
        (str "The Redaction section was renamed or its 'universal' "
             "qualifier dropped. The original protocol gated redaction "
             "to bead-filing only — audit Finding 2 (Medium) required "
             "widening this to every output. Restoring the bead-only "
             "scope is a regression."))))

(deftest redaction-covers-every-output
  (testing "redaction applies to every output, not just bead bodies"
    (let [s (section @protocol-md "Redaction (universal)")]
      (is (contains-any? s ["every output the skill emits"])
          (str "The 'every output' clause is missing. This was "
               "Finding 2's cardinal demand.")))))

(deftest redaction-enumerates-the-four-categories
  (testing "secrets, internal URLs, paths, PII all covered"
    (let [s (section @protocol-md "Redaction (universal)")]
      (testing "secrets and credentials category"
        (is (contains-any? s ["Secrets and credentials"
                              "API tokens" "OAuth"])
            "Secrets/credentials redaction category missing."))
      (testing "internal URLs category"
        (is (contains-any? s ["Internal URLs"
                              "RFC 1918" "intranet"])
            "Internal-URLs redaction category missing."))
      (testing "local paths category"
        (is (contains-any? s ["local paths"
                              "C:/Users" "/Users/" "/home/"])
            "Local-paths redaction category missing."))
      (testing "PII category"
        (is (contains-any? s ["PII"
                              "Email addresses" "Phone numbers"])
            "PII redaction category missing.")))))

(deftest redaction-defines-stable-placeholders
  (testing "stable-placeholder convention is named"
    (let [s (section @protocol-md "Redaction (universal)")]
      (is (contains-any? s ["stable placeholder" "stable**placeholders"
                            "<REDACTED-"])
          (str "The stable-placeholder convention is missing. Without "
               "it findings render as <REDACTED> <REDACTED> <REDACTED> "
               "and the reader can't tell whether two masks denote the "
               "same secret."))
      (is (contains-any? s ["<REDACTED-TOKEN-1>"])
          (str "The canonical placeholder example was changed or "
               "removed. Consumers grep for this exact shape."))
      (is (contains-any? s ["monotonically" "same secret" "same mask"])
          (str "The 'same secret -> same mask on repeat mentions' rule "
               "is missing — this is what makes the placeholders "
               "*stable*.")))))

(deftest redaction-defines-pre-emission-reviewer-pass
  (testing "reviewer pass before emission is required"
    (let [s (section @protocol-md "Redaction (universal)")]
      (is (contains-any? s ["Reviewer pass before emission"
                            "before emission" "re-read"])
          (str "The pre-emission reviewer-pass clause is missing. "
               "Without it redaction is best-effort; with it the skill "
               "has a named checkpoint to actually scan output before "
               "sending.")))))

(deftest redaction-warns-against-raw-transcript-quoting
  (testing "don't quote the raw transcript verbatim"
    (let [s (section @protocol-md "Redaction (universal)")]
      (is (contains-any? s ["Don't quote the raw transcript"
                            "don't quote the raw transcript"
                            "verbatim"])
          (str "The 'don't verbatim-quote the transcript' rule is "
               "missing. Raw transcripts are the most common carrier "
               "of sensitive substrings the user didn't realise were "
               "in scope.")))))

;; ---------------------------------------------------------------------------
;; Lock 3 — Edit-gate split (evidence-shaped vs canonical-idiom-shaped)
;; ---------------------------------------------------------------------------

(deftest edit-gate-split-distinguishes-evidence-vs-canonical
  (testing "Edit gate distinguishes evidence-shaped vs canonical-idiom"
    (let [body @protocol-md]
      (is (contains-any? body ["Edit gate — evidence-shaped"])
          (str "The evidence-shaped Edit gate is missing. This is "
               "audit Finding 3 (recommendation): edits whose content "
               "/ motivation derives from evidence require explicit "
               "approval."))
      (is (contains-any? body ["Edit gate — canonical-idiom"])
          (str "The canonical-idiom Edit gate is missing. Without this "
               "split the protocol either over-gates (every Edit needs "
               "approval) or under-gates (no Edit needs approval) — "
               "the audit's risk model required the split.")))))

(deftest edit-gate-requires-explicit-approval-for-evidence-shaped
  (testing "evidence-shaped Edit requires explicit user approval"
    (let [body @protocol-md]
      (is (contains-any? body ["requires explicit user approval first"
                               "explicit user approval"])
          (str "The 'explicit user approval' phrasing on the "
               "evidence-shaped Edit gate was weakened. RFC-style "
               "'requires' is load-bearing.")))))

(deftest edit-gate-when-in-doubt-tiebreak-present
  (testing "'When in doubt, gate' tie-break clause present"
    (let [body @protocol-md]
      (is (contains-any? body ["When in doubt, gate"])
          (str "The 'When in doubt, gate' tie-break is missing. "
               "Without it the evidence-vs-canonical classification "
               "has no fallback for the ambiguous middle ground — "
               "and the ambiguous middle is where bypass attacks live.")))))

(deftest edit-gate-explains-the-risk-model
  (testing "the risk model — evidence steering the edit — is stated"
    (let [body @protocol-md]
      (is (contains-any? body ["evidence steering the edit"])
          (str "The risk model 'the risk is the evidence steering the "
               "edit, not the model's confidence in the rewrite' is "
               "missing. Without it future readers can't tell why a "
               "confident-looking mechanical rewrite still needs "
               "approval when its shape comes from evidence.")))))

;; ---------------------------------------------------------------------------
;; Cross-consumer adoption — both consuming skills must actually load
;; the shared leaf. A future edit that decouples a consumer (dropping
;; the link, copy-pasting the prose inline, …) breaks the single-source
;; assumption.
;; ---------------------------------------------------------------------------

(deftest improver-loads-shared-protocol
  (testing "re-frame2-improver/SKILL.md links to ../shared/retro-protocol.md"
    (is (str/includes? @improver-skill-md "../shared/retro-protocol.md")
        (str "re-frame2-improver no longer links to the shared "
             "retro-protocol. If a copy-paste was deliberate, the "
             "single-source assumption is broken and this regression "
             "suite no longer covers the consumer."))))

(deftest improver-surfaces-untrusted-evidence-callout
  (testing "re-frame2-improver carries the untrusted-evidence callout"
    (is (contains-any? @improver-skill-md
                       ["Untrusted evidence" "data, not instructions"])
        (str "re-frame2-improver dropped its untrusted-evidence "
             "callout. The audit required this at the workflow head, "
             "not just in the linked leaf — consumers should re-state "
             "the rule."))))

(deftest improver-surfaces-edit-gate-split
  (testing "re-frame2-improver carries the Edit-gate split"
    (is (contains-any? @improver-skill-md
                       ["evidence-shaped" "canonical-idiom"])
        (str "re-frame2-improver no longer surfaces the Edit-gate "
             "split. Since improver is the only current consumer with "
             "`Edit` in its allowed-tools, this is THE consumer that "
             "has to enforce it."))))

(deftest pair-retro2-loads-shared-protocol
  (testing "re-frame-pair-retro2/SKILL.md links to ../shared/retro-protocol.md"
    (is (str/includes? @pair-retro2-skill-md "../shared/retro-protocol.md")
        (str "re-frame-pair-retro2 no longer links to the shared "
             "retro-protocol. If a copy-paste was deliberate, the "
             "single-source assumption is broken and this regression "
             "suite no longer covers the consumer."))))

;; ---------------------------------------------------------------------------
;; Fixtures present — the document-runnable behavioural scenarios live
;; alongside this structural test. If they disappear, the prose-only
;; portion of the regression goes back to PARTIAL.
;; ---------------------------------------------------------------------------

(deftest fixtures-dir-exists
  (testing "fixtures/ subdirectory present"
    (is (.isDirectory (io/file shared-root "tests/fixtures"))
        (str "fixtures/ directory disappeared. Document-runnable "
             "fixture scenarios are the behavioural counterpart to "
             "this structural suite — together they close audit "
             "Finding 4 (rf2-y1tqa)."))))

(deftest fixtures-cover-three-locks
  (testing "one fixture per lock"
    (let [fixtures-dir (io/file shared-root "tests/fixtures")
          names        (set (map #(.getName %)
                                 (.listFiles fixtures-dir)))]
      (is (some #(str/includes? % "injection") names)
          "Missing injection-scenario fixture under fixtures/.")
      (is (some #(str/includes? % "redaction") names)
          "Missing redaction-scenario fixture under fixtures/.")
      (is (some #(str/includes? % "edit-gate") names)
          "Missing edit-gate-scenario fixture under fixtures/."))))

(deftest fixtures-readme-explains-run-mechanism
  (testing "fixtures/README.md explains how a human / agent replays them"
    (let [readme (io/file shared-root "tests/fixtures/README.md")]
      (is (.exists readme)
          (str "fixtures/README.md missing. Without it future "
               "maintainers don't know whether the fixtures are "
               "CI-runnable or hand-run."))
      (let [text (slurp readme)]
        (is (contains-any? text ["document-runnable" "human or AI replays"
                                 "manual"])
            (str "fixtures/README.md no longer documents the manual / "
                 "document-runnable replay shape. The fixtures aren't "
                 "CI-runnable; if the README implies they are, future "
                 "maintainers will be confused."))))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'retro-protocol-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
