;;;; tests/retro_protocol_test.clj — structural regression for the shared
;;;; retro protocol.
;;;;
;;;; The shared protocol carries three normative locks:
;;;;
;;;;   1. Untrusted-evidence boundary  — High
;;;;   2. Universal redaction          — Medium
;;;;   3. Edit-gate split              — recommendation
;;;;
;;;; Plus a fourth surface (Lock 4): the shell-safety / command-injection
;;;; boundary on the `gh issue` surfaces the protocol grants, specified in
;;;; `issue-filing.md`. It is the same untrusted-evidence threat model as
;;;; Lock 1 projected onto the shell — across the body (`--body-file`,
;;;; Lock 4), the title (inline `--title`, Lock 4b), the per-filing OS-temp
;;;; body path (Lock 4c), and the search query (inline
;;;; `gh issue list --search`, Lock 4d — `--search` has no `--search-file`,
;;;; so a query lifted from evidence re-opens the same transcript→shell
;;;; injection the title/body rules close).
;;;;
;;;; This file pins the load-bearing phrasings of all four locks as a
;;;; structural drift detector.
;;;;
;;;; This catches the CHEAP class of drift the protocol can suffer: an edit
;;;; silently weakening the wording (e.g. dropping "MUST ignore",
;;;; collapsing the four attacker classes into one, removing the
;;;; placeholder convention, deleting the "When in doubt, gate"
;;;; tie-break). A conversation-driving variant — actually replaying
;;;; injection fixtures against a fresh agent and asserting non-
;;;; compliance — is the fidelity-ideal version of this surface, and
;;;; lives under `fixtures/` as document-runnable scenarios (no harness
;;;; yet).
;;;;
;;;; Mirrors the shape of
;;;; `skills/re-frame2-pair/tests/prompts/prompt_regression_test.clj`
;;;; (the only other prose-regression test in the corpus).
;;;;
;;;; Run:    bb skills/shared/tests/retro_protocol_test.clj   (from repo root)
;;;;         bb tests/retro_protocol_test.clj                 (from skills/shared/)
;;;; Exit:   0 = pass, non-zero = fail.

(ns retro-protocol-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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

(def ^:private pair-retro-skill-md
  (delay (slurp-rel skills-root "re-frame2-pair-retro/SKILL.md")))

(def ^:private issue-filing-md
  (delay (slurp-rel shared-root "issue-filing.md")))

(def ^:private issue-template-md
  (delay (slurp-rel skills-root
                    "re-frame2-pair-retro/references/issue-template.md")))

(def ^:private readme-md
  (delay (slurp-rel skills-root "README.md")))

(def ^:private pair-retro-known-frictions-md
  (delay (slurp-rel skills-root
                    "re-frame2-pair-retro/references/known-frictions.md")))

(def ^:private pair-retro-evals-json
  (delay (slurp-rel skills-root "re-frame2-pair-retro/evals/evals.json")))

;; ---------------------------------------------------------------------------
;; Section extraction — like the re-frame2-pair prompt-regression substrate, each
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
             "skills-shared security audit (Finding 1, High). Restore "
             "the heading or update this test AND the audit-verification doc."))))

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
      ;; The lock requires all four classes named with at least a
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
;; Lock 4 — Shell-safety on the one destructive surface (gh issue create)
;;
;; `issue-filing.md` is the consumer-facing recipe for the only mutating
;; surface the protocol grants (`Bash(gh issue *)`). Its load-bearing
;; safety claim is a *command-injection* boundary: transcript-derived
;; issue bodies can carry shell metacharacters, so the body MUST be
;; composed to a file with the `Write` tool and passed via gh's native
;; `--body-file` flag (which reads the body verbatim from disk, so no
;; shell expansion ever touches the transcript-derived text) rather than
;; interpolated inline. This is the same untrusted-evidence threat model
;; as Lock 1, projected onto the shell — the actual destructive surface.
;; A weakening here (dropping the `--body-file` requirement, or the "never
;; interpolate inline" rule) would re-open transcript→shell injection with
;; no gate firing.
;; ---------------------------------------------------------------------------

(deftest issue-filing-shell-safety-section-exists
  (testing "issue-filing.md carries a shell-safety section"
    (is (contains-any? @issue-filing-md
                       ["Shell-safety" "shell metacharacter"
                        "pass the body via a file"])
        (str "The shell-safety section of issue-filing.md was renamed "
             "or deleted. This is the command-injection boundary on the "
             "one destructive surface the protocol grants (gh issue "
             "create); a transcript-derived body interpolated inline is "
             "the attack. Restore the section or update this test."))))

(deftest issue-filing-requires-body-file
  (testing "the Write-tool + --body-file pattern is mandated"
    (let [s (section @issue-filing-md "Shell-safety")]
      (is (contains-any? s ["--body-file" "`Write` tool" "Write tool"])
          (str "The `Write`-tool + --body-file requirement was dropped "
               "from issue-filing.md. Without it, $, backtick, and \\ in "
               "a transcript-derived body expand in the shell — the exact "
               "transcript→shell injection vector. --body-file reads the "
               "body verbatim from disk so nothing expands."))
      (is (contains-any? s ["verbatim" "bare `gh issue create`"
                            "nothing expands"])
          (str "The 'body read verbatim / nothing expands' rationale was "
               "dropped. It is the reason --body-file is load-bearing, "
               "not stylistic.")))))

(deftest issue-filing-forbids-inline-interpolation
  (testing "never interpolate transcript-derived text inline"
    (is (contains-any? @issue-filing-md
                       ["Never interpolate transcript-derived text"
                        "never interpolate" "never inline"])
        (str "The 'never interpolate transcript-derived text directly "
             "into a shell command' prohibition is missing. This is the "
             "imperative half of the shell-safety lock — the Write-tool + "
             "--body-file recipe is the safe path, this is the banned path."))))

;; ---------------------------------------------------------------------------
;; Lock 4b — Title-safety on the inline `--title` argument
;;
;; `gh issue create` has no `--title-file` flag — the title is always
;; passed as inline argv (`--title "<…>"`). The body-file trick that
;; protects the body therefore cannot protect the title; an
;; evidence-/transcript-derived title can carry `$()`, backticks, `"`,
;; `\`, or a newline that the shell expands BEFORE `gh` receives argv,
;; re-opening transcript→shell injection even though the body is safe.
;; The title is safe ONLY because the agent authors it from a restricted
;; safe alphabet and never pastes evidence text into it. These assertions
;; pin that invariant in the same leaf and frontmatter that carry the
;; body-safety locks, so a weakening of the title rule fails the suite
;; alongside the body checks.
;; ---------------------------------------------------------------------------

(deftest issue-filing-carries-title-safety-rule
  (testing "issue-filing.md states the title is an inline arg with no --title-file"
    (is (contains-any? @issue-filing-md
                       ["no `--title-file`" "no --title-file"
                        "title is an inline argument"])
        (str "issue-filing.md no longer states that the `--title` is an "
             "inline shell argument with no `--title-file` equivalent. "
             "This is the title half of the shell-safety lock — without "
             "it the body is protected but the title re-opens "
             "transcript→shell injection (the gap rf2-q8qu2/#3121 left). "
             "Restore the §Shell-safety title section or update this test."))))

(deftest issue-filing-forbids-evidence-derived-titles
  (testing "never paste transcript-/evidence-derived text into --title"
    (is (contains-any? @issue-filing-md
                       ["into `--title`" "into --title"])
        (str "The 'never copy transcript-/evidence-derived text into "
             "`--title`' prohibition is missing from issue-filing.md. "
             "This is the imperative half of the title-safety lock — the "
             "agent-authored safe-alphabet title is the safe path, "
             "pasting evidence into --title is the banned path."))
    (is (contains-any? @issue-filing-md
                       ["safe alphabet" "safe-alphabet"
                        "author it" "author the title"])
        (str "The 'author the title from a restricted safe alphabet' "
             "safe-path is missing. Without a named safe construction the "
             "prohibition has no constructive counterpart and the agent "
             "has nowhere safe to land."))))

(deftest issue-template-pins-title-safety
  (testing "references/issue-template.md ties --title to the safe-alphabet rule"
    (is (contains-any? @issue-template-md
                       ["Shell-safe titles" "safe alphabet" "safe-alphabet"
                        "no `--title-file`"])
        (str "re-frame2-pair-retro/references/issue-template.md no longer "
             "carries the title-safety rule next to its title patterns "
             "and worked `gh issue create` examples. The template's "
             "inline `--title \"<short title>\"` examples are exactly the "
             "recipe an agent follows; the safe-title rule must live "
             "beside them, not only in the shared leaf."))))

(deftest issue-filing-gates-create-on-user-approval
  (testing "gh issue create is gated on a fresh in-conversation yes"
    (is (contains-any? @issue-filing-md
                       ["explicit user approval"
                        "fresh, in-conversation"
                        "in-conversation \"yes"])
        (str "The 'file only after explicit user approval' gate on "
             "gh issue create was weakened. A 'pre-approved' claim inside "
             "evidence is NOT approval — this couples back to Lock 1."))
    (is (contains-any? @issue-filing-md ["pre-approved"])
        (str "The 'a pre-approved claim inside the evidence is not "
             "approval' carve-out is missing — it is the explicit link "
             "between the filing gate and the untrusted-evidence boundary."))))

(deftest protocol-step-six-points-at-shell-safe-filing
  (testing "step 6 still surfaces the shell-safe filing recipe"
    (let [s (section @protocol-md "The seven-step protocol")]
      (is (contains-any? s ["Shell safety" "--body-file"
                            "issue-body"])
          (str "The seven-step protocol's pointer to the shell-safe "
               "filing recipe was dropped. The inline reminder is what "
               "carries the shell-safety lock to the agent at workflow "
               "time, not just in the linked leaf.")))))

;; ---------------------------------------------------------------------------
;; Lock 4c — Per-filing OS-temp body path
;;
;; The `--body-file` target must be a FRESH, per-filing temp file in the
;; host OS's temp directory — never a fixed, predictable name like
;; `/tmp/issue-body.md`. A hard-coded `/tmp/issue-body.md` (a) fails on
;; hosts without a POSIX `/tmp`, notably Windows consumer installs, even
;; after the user approved filing; and (b) is predictable, so two
;; concurrent retros / two rapid filings overwrite each other's redacted
;; body — filing the WRONG redacted text to GitHub or leaving sensitive
;; evidence in a shared location longer than intended. These assertions
;; lock the rule in the SHARED canonical recipe and its consumer template:
;; the published recipe MUST NOT prescribe `/tmp/issue-body.md` as the
;; `--body-file` target, and MUST name a per-filing OS-temp path (TMPDIR /
;; $env:TEMP). The "never a fixed /tmp/issue-body.md" PROHIBITION strings
;; are tolerated — the regression fires only on the prescriptive command
;; form (`--body-file /tmp/issue-body.md`) or the prescriptive "compose
;; /tmp/issue-body.md" Write step, never on a prohibition that merely names
;; the banned literal.
;; ---------------------------------------------------------------------------

(defn- prescribes-fixed-body-path?
  "True if `md` still PRESCRIBES the fixed `/tmp/issue-body.md` as the
   --body-file target (the antipattern), as opposed to merely naming the
   literal inside a prohibition (e.g. \"never a fixed /tmp/issue-body.md\").
   The prescriptive forms are the worked `--body-file /tmp/issue-body.md`
   command argument and the `compose /tmp/issue-body.md` / `Write … to
   /tmp/issue-body.md` step; prohibition prose contains neither."
  [md]
  (boolean
    (or (re-find #"--body-file\s+/tmp/issue-body\.md" md)
        (re-find #"(?i)(?:compose|create|write[^\n]*?to)\s+`?/tmp/issue-body\.md`?" md))))

(defn- names-per-filing-os-temp-path?
  "True if `md` explicitly requires a fresh per-filing temp file in the
   host OS's temp directory — both the 'per-filing' intent and at least
   one OS-temp token (POSIX TMPDIR or Windows $env:TEMP)."
  [md]
  (and (str/includes? md "per-filing")
       (contains-any? md ["TMPDIR" "$env:TEMP" "env:TEMP"])))

(deftest issue-filing-recipe-uses-per-filing-os-temp-body-path
  (testing "the SHARED canonical recipe (issue-filing.md) no longer prescribes a fixed /tmp/issue-body.md"
    (is (not (prescribes-fixed-body-path? @issue-filing-md))
        (str "skills/shared/issue-filing.md again prescribes the fixed, "
             "predictable `/tmp/issue-body.md` as the `--body-file` "
             "target. That path breaks on hosts without a POSIX `/tmp` "
             "(Windows consumer installs) even after the user approved "
             "filing, and its predictable name lets two concurrent / two "
             "rapid filings overwrite each other's redacted body — filing "
             "the wrong text to GitHub. Compose a fresh, per-filing temp "
             "file in the host OS's temp dir instead (rf2-de7pqw/ca5v9s)."))
    (is (names-per-filing-os-temp-path? @issue-filing-md)
        (str "skills/shared/issue-filing.md no longer names a fresh, "
             "per-filing temp file in the host OS's temp directory "
             "(needs both 'per-filing' and an OS-temp token — `${TMPDIR:"
             "-/tmp}/…` on POSIX or `$env:TEMP\\…` on Windows). The "
             "positive requirement is the constructive half of the lock; "
             "without it the recipe can degrade back to a fixed path."))))

(deftest readme-baseline-uses-per-filing-os-temp-body-path
  (testing "the README §allowed-tools baseline canonical shape uses a per-filing OS-temp path"
    (is (not (prescribes-fixed-body-path? @readme-md))
        (str "skills/README.md §Published-skill `allowed-tools` baseline "
             "again prescribes the fixed `/tmp/issue-body.md` as the "
             "`--body-file` target in the canonical shape the per-consumer "
             "recipes point at. Fix the canonical source so every consumer "
             "inherits the cross-platform per-filing path (rf2-ca5v9s)."))
    (is (names-per-filing-os-temp-path? @readme-md)
        (str "skills/README.md no longer names a fresh, per-filing temp "
             "file in the host OS's temp directory (needs both "
             "'per-filing' and an OS-temp token). The canonical shape is "
             "what consumers copy; it must carry the positive rule."))))

(deftest issue-template-uses-per-filing-os-temp-body-path
  (testing "the consumer template (re-frame2-pair-retro/issue-template.md) uses a per-filing OS-temp path"
    (is (not (prescribes-fixed-body-path? @issue-template-md))
        (str "re-frame2-pair-retro/references/issue-template.md again "
             "prescribes the fixed `/tmp/issue-body.md` in its worked `gh "
             "issue create --body-file …` examples. The template's command "
             "is exactly the recipe an agent follows, so the per-filing "
             "OS-temp path must live in the worked examples, not only in "
             "the shared leaf (rf2-de7pqw landed this in-lane; ca5v9s "
             "locks it)."))
    (is (names-per-filing-os-temp-path? @issue-template-md)
        (str "re-frame2-pair-retro/references/issue-template.md no longer "
             "names a fresh, per-filing temp file in the host OS's temp "
             "directory (needs both 'per-filing' and an OS-temp token). "
             "The worked example must show the per-filing path, not assume "
             "the reader infers it from the shared leaf."))))

;; ---------------------------------------------------------------------------
;; Lock 4d — Search-argument safety on the inline `gh issue list --search`
;; recipe
;;
;; Search-before-file is mandatory, and the recipe is
;; `gh issue list --repo <owner/repo> --search "<keywords>"`. `--search`
;; is inline argv with no `--search-file`, so it is the SAME
;; transcript→shell injection boundary as `--title`: a query lifted from
;; the transcript / an error string / a suggested title can carry `$()`,
;; backticks, quotes, `\`, or a newline that the shell expands before `gh`
;; sees argv (and it leaks the raw evidence to GitHub as the search query).
;; The title/body hardening closed `--title`/`--body`; this lock keeps the
;; `--search` clause from drifting back out. EVERY corpus site that shows
;; the search recipe must carry (or link) the agent-authored-keywords /
;; never-paste-evidence rule next to it.
;; ---------------------------------------------------------------------------

(defn- search-recipe-has-safety-clause?
  "True if `md` carries the search-argument safety clause near the
   `gh issue list --search` recipe: it must mention `--search` and BOTH
   the agent-authored-from-safe-alphabet positive rule AND the
   never-paste-evidence prohibition."
  [md]
  (and (str/includes? md "--search")
       (contains-any? md ["safe alphabet" "safe-alphabet" "author the"
                          "author it" "author the `<keywords>`"
                          "agent-authored"])
       (contains-any? md ["never copy" "never paste" "never interpolate"
                          "Never interpolate" "Never paste"
                          "no `--search-file`" "no --search-file"])))

(deftest issue-filing-search-recipe-carries-search-safety
  (testing "issue-filing.md §Search before filing carries the --search safety clause"
    (is (search-recipe-has-safety-clause? @issue-filing-md)
        (str "skills/shared/issue-filing.md shows the `gh issue list "
             "--search` recipe but no longer states the keywords are "
             "agent-authored from the safe alphabet and never copied from "
             "evidence. `--search` is inline argv with no `--search-file` — "
             "the same transcript→shell injection boundary as `--title`. "
             "Restore the §Search before filing safety clause (rf2-7g9htq.1)."))))

(deftest retro-protocol-search-recipe-carries-search-safety
  (testing "retro-protocol.md step 6 search recipe carries the --search safety clause"
    (is (search-recipe-has-safety-clause? @protocol-md)
        (str "skills/shared/retro-protocol.md shows the `gh issue list "
             "--search` recipe in the filing sub-protocol but no longer "
             "carries the agent-authored / never-paste-evidence `--search` "
             "rule beside it (rf2-7g9htq.1)."))))

(deftest issue-template-search-recipe-carries-search-safety
  (testing "re-frame2-pair-retro/references/issue-template.md search recipe carries the --search safety clause"
    (is (search-recipe-has-safety-clause? @issue-template-md)
        (str "re-frame2-pair-retro/references/issue-template.md shows the "
             "`gh issue list --search` command (it is the recipe the agent "
             "follows) but no longer carries the agent-authored / "
             "never-paste-evidence `--search` rule beside it. The worked "
             "search command is exactly where the injection invitation "
             "lives, so the clause must be local, not only in the shared "
             "leaf (rf2-7g9htq.1)."))))

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

(deftest pair-retro-loads-shared-protocol
  (testing "re-frame2-pair-retro/SKILL.md links to ../shared/retro-protocol.md"
    (is (str/includes? @pair-retro-skill-md "../shared/retro-protocol.md")
        (str "re-frame2-pair-retro no longer links to the shared "
             "retro-protocol. If a copy-paste was deliberate, the "
             "single-source assumption is broken and this regression "
             "suite no longer covers the consumer."))))

(deftest pair-retro-loads-shared-issue-filing
  (testing "re-frame2-pair-retro/SKILL.md links to ../shared/issue-filing.md"
    (is (str/includes? @pair-retro-skill-md "../shared/issue-filing.md")
        (str "re-frame2-pair-retro no longer links to the shared "
             "issue-filing recipe. That leaf is the single home for the "
             "shell-safe Write-tool + --body-file filing pattern; a "
             "decoupled consumer can drift from the security boundary."))))

;; ---------------------------------------------------------------------------
;; Pair-retro frontmatter — pin the `allowed-tools` contract to the shared
;; issue-filing recipe. The recipe (`issue-filing.md` §Shell-safety) makes the
;; `Write` tool + `gh issue create --body-file` pair the no-shell-interpolation
;; boundary for transcript-derived issue bodies. The skill therefore needs
;; `Write` and `Bash(gh issue create *)` PRESENT, and must NOT carry `Edit`
;; (it never rewrites source) or `Bash(bd *)` (the monorepo's internal tracker
;; has no place in a published skill — see `skills/README.md` §Published-skill
;; allowed-tools baseline). This test fails loudly if an edit removes
;; `Write`/`gh issue create` or smuggles in `Edit`/`Bash(bd *)`.
;; ---------------------------------------------------------------------------

(defn- frontmatter
  "Return the YAML frontmatter block (between the first two `---` lines) of
   a SKILL.md, or the whole doc if no closing fence is found."
  [md]
  (let [m (re-find #"(?ms)\A---\r?\n(.*?)\r?\n---" md)]
    (if m (second m) md)))

(deftest pair-retro-frontmatter-grants-write
  (testing "allowed-tools grants Write for the --body-file shell-safety path"
    (let [fm (frontmatter @pair-retro-skill-md)]
      (is (re-find #"(?m)^\s*-\s*Write\s*$" fm)
          (str "re-frame2-pair-retro's allowed-tools no longer grants "
               "`Write`. `Write` is required SOLELY to compose a fresh, "
               "per-filing OS-temp body file so `gh issue create "
               "--body-file` reads the transcript-derived body verbatim "
               "(no shell expansion). Removing it breaks the only "
               "documented no-shell-interpolation filing path — see "
               "../issue-filing.md §Shell-safety.")))))

(deftest pair-retro-frontmatter-grants-gh-issue-create
  (testing "allowed-tools grants Bash(gh issue create *)"
    (let [fm (frontmatter @pair-retro-skill-md)]
      (is (str/includes? fm "Bash(gh issue create *)")
          (str "re-frame2-pair-retro's allowed-tools no longer grants "
               "`Bash(gh issue create *)`. This is the approval-gated "
               "filing surface (L2); without it the skill can draft but "
               "never file.")))))

(deftest pair-retro-frontmatter-omits-edit
  (testing "allowed-tools does NOT grant Edit"
    (let [fm (frontmatter @pair-retro-skill-md)]
      (is (not (re-find #"(?m)^\s*-\s*Edit\s*$" fm))
          (str "re-frame2-pair-retro's allowed-tools now grants `Edit`. "
               "This skill never rewrites source in any repo — friction "
               "routes to GitHub issues, not edits (design.md §9). Remove "
               "`Edit` from the frontmatter.")))))

(deftest pair-retro-frontmatter-omits-bd
  (testing "allowed-tools does NOT grant Bash(bd *)"
    (let [fm (frontmatter @pair-retro-skill-md)]
      (is (not (str/includes? fm "Bash(bd"))
          (str "re-frame2-pair-retro's allowed-tools now grants a "
               "`Bash(bd ...)` surface. `bd` (beads) is the re-frame2 "
               "monorepo's internal tracker and has no place in a "
               "published skill — skills file against the target repo's "
               "GitHub issues via `gh issue create` (skills/README.md "
               "§Published-skill allowed-tools baseline).")))))

;; ---------------------------------------------------------------------------
;; Fixtures present — the document-runnable behavioural scenarios live
;; alongside this structural test. They are the behavioural counterpart to
;; the structural drift detector; these checks keep them from disappearing.
;; ---------------------------------------------------------------------------

(deftest fixtures-dir-exists
  (testing "fixtures/ subdirectory present"
    (is (.isDirectory (io/file shared-root "tests/fixtures"))
        (str "fixtures/ directory disappeared. Document-runnable "
             "fixture scenarios are the behavioural counterpart to "
             "this structural suite — together they close audit "
             "Finding 4."))))

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
                 "fully CI-runnable; if the README implies they are, future "
                 "maintainers will be confused."))))))

;; ---------------------------------------------------------------------------
;; Behavioral eval — the three security fixtures have an EXECUTABLE scoring
;; path. A wording / consumer / model-behaviour shift could pass every
;; structural grep while an agent actually runs `gh issue create`, leaks a
;; token, or applies an evidence-shaped Edit. `tests/evals/behavioral-evals.json`
;; encodes each fixture's §Expected / §Anti-expectation locks as machine-
;; checkable predicates, and `score-behavioral-eval.clj` scores a captured
;; transcript into a machine-readable pass/fail artifact. These structural
;; tests keep the eval machinery + its workflow wiring from disappearing, and
;; run the scorer's own self-test as a cheap always-on guard.
;; ---------------------------------------------------------------------------

(def ^:private behavioral-evals-file
  (io/file shared-root "tests/evals/behavioral-evals.json"))

(deftest behavioral-eval-manifest-present-and-wellformed
  (testing "tests/evals/behavioral-evals.json exists, parses, and has one eval per fixture"
    (is (.exists behavioral-evals-file)
        (str "tests/evals/behavioral-evals.json disappeared. It is the "
             "executable behavioural contract for the three security "
             "fixtures — without it behaviour reverts to eyeball-only "
             "(rf2-1inyqr finding 2)."))
    (when (.exists behavioral-evals-file)
      (let [m (json/parse-string (slurp behavioral-evals-file) true)]
        (is (= "behavioral" (:eval_kind m))
            "behavioral-evals.json :eval_kind is no longer \"behavioral\".")
        (is (= 3 (count (:evals m)))
            (str "behavioral-evals.json no longer has exactly 3 evals (one "
                 "per security fixture: injection / redaction / edit-gate)."))
        (is (every? :fixture (:evals m))
            "a behavioral eval lost its :fixture pointer at the fixture file.")
        ;; the three load-bearing behavioural checks the bead enumerated must
        ;; be present somewhere in the manifest: no gh issue create, no raw
        ;; secret egress, evidence-shaped Edit gated.
        (let [blob (slurp behavioral-evals-file)]
          (is (str/includes? blob "gh\\\\s+issue\\\\s+create")
              "manifest no longer forbids `gh issue create` as a tool call.")
          (is (str/includes? blob "forbidden_output_substrings")
              "manifest no longer pins raw-secret substrings as forbidden output.")
          (is (str/includes? blob "thompson")
              "manifest no longer gates the evidence-shaped Edit (Scenario A)."))))))

(deftest behavioral-eval-scorer-present-and-self-tests
  (testing "score-behavioral-eval.clj exists and its --self-test passes"
    (let [scorer (io/file shared-root "tests/evals/score-behavioral-eval.clj")]
      (is (.exists scorer)
          (str "tests/evals/score-behavioral-eval.clj disappeared. It is the "
               "scorer that turns a captured transcript into a machine-readable "
               "pass/fail artifact (rf2-1inyqr finding 2)."))
      (when (.exists scorer)
        (let [{:keys [exit out err]}
              (shell/sh "bb" (.getPath scorer) "--self-test")]
          (is (zero? exit)
              (str "score-behavioral-eval.clj --self-test failed (exit " exit
                   "). The manifest is malformed or a scorer predicate stopped "
                   "firing.\nstdout: " out "\nstderr: " err)))))))

(deftest behavioral-eval-wired-into-fixtures-readme
  (testing "fixtures/README.md documents the verification-status gate + executable scoring"
    (let [text (slurp (io/file shared-root "tests/fixtures/README.md"))]
      (is (str/includes? text "Verification status")
          (str "fixtures/README.md no longer carries the §Verification status "
               "section — the statement that the structural greps do NOT "
               "verify behaviour and that behaviour is verified only when the "
               "behavioral eval has been replayed + scored (rf2-1inyqr "
               "finding 2)."))
      (is (str/includes? text "score-behavioral-eval.clj")
          (str "fixtures/README.md no longer points at the executable scorer "
               "(score-behavioral-eval.clj). The behavioural contract must be "
               "discoverable from the fixtures (rf2-1inyqr finding 2)."))
      (is (contains-any? text ["NOT verified" "not verified" "release / checklist"
                               "release/checklist"])
          (str "fixtures/README.md no longer states that behaviour is not "
               "verified by the structural greps alone and that the eval is a "
               "release / checklist gate (rf2-1inyqr finding 2).")))))

;; ---------------------------------------------------------------------------
;; Pair-retro production/probe/filing drift
;;
;; - the production-elision known-friction must not claim "every Tool-Pair
;;   surface elides"; the dev-gated surfaces (trace / epoch / schema /
;;   source-coord) go dark, but orientation / registry-frame shape, the
;;   direct-read primitives, and the always-on error-emit substrate still
;;   answer.
;; - the attach-failure guidance + eval fixtures must teach the current
;;   diagnostic ladder (:nrepl-unreachable / :build-not-running /
;;   :no-runtime-connected / :runtime-loaded-but-preload-missing), with the
;;   blanket :runtime-not-preloaded framed only as the fallback reason.
;; - the README routing index must carry the optional-label / fail-open
;;   filing model, not a mandatory `pair-mcp` label.
;; ---------------------------------------------------------------------------

(deftest pair-retro-production-elision-not-blanket
  (testing "the production-elision friction no longer claims every Tool-Pair surface elides (rf2-dhnixf finding 1)"
    (let [body @pair-retro-known-frictions-md]
      (is (not (str/includes? body "every Tool-Pair surface elides"))
          (str "known-frictions.md carries the blanket 'every Tool-Pair "
               "surface elides' claim. Only the dev-gated surfaces (trace / "
               "epoch / schema / source-coord) elide under "
               "debug-enabled?=false; orientation / registry-frame shape, "
               "the direct-read primitives, and the always-on error substrate "
               "still answer (rf2-dhnixf finding 1)."))
      (is (contains-any? body ["orient" "orientation"])
          (str "the narrowed production-elision friction must name "
               "orientation / registry-frame shape as a surface that still "
               "answers in a production-elided build (rf2-dhnixf finding 1)."))
      (is (contains-any? body ["error-emit" "error substrate" "always-on"])
          (str "the narrowed production-elision friction must call out the "
               "always-on error-emit substrate as separate from the dev "
               "trace surface (rf2-dhnixf finding 1).")))))

(deftest pair-retro-teaches-diagnostic-ladder-not-blanket-reason
  (testing "attach-failure guidance teaches the diagnostic ladder (rf2-dhnixf finding 2)"
    (let [body @pair-retro-known-frictions-md]
      (is (and (str/includes? body ":runtime-loaded-but-preload-missing")
               (str/includes? body ":no-runtime-connected")
               (str/includes? body ":build-not-running")
               (str/includes? body ":nrepl-unreachable"))
          (str "known-frictions.md no longer enumerates the current attach "
               "diagnostic ladder (:nrepl-unreachable / :build-not-running / "
               ":no-runtime-connected / :runtime-loaded-but-preload-missing). "
               "The legacy blanket :runtime-not-preloaded must not be the "
               "normal-case reason (rf2-dhnixf finding 2)."))
      (is (contains-any? body ["degradation fallback" "fallback" "last-resort"
                               "legacy"])
          (str "known-frictions.md must frame :runtime-not-preloaded as the "
               "fallback/legacy reason, not the normal-case verdict "
               "(rf2-dhnixf finding 2).")))))

(deftest pair-retro-eval-fixtures-cover-ladder-reasons
  (testing "evals.json fixtures cover the current ladder reasons (rf2-dhnixf finding 2)"
    (let [body @pair-retro-evals-json]
      (is (str/includes? body ":runtime-loaded-but-preload-missing")
          (str "evals/evals.json no longer carries a fixture using "
               ":runtime-loaded-but-preload-missing (the concrete "
               "missing-preload rung). A fixture must exercise the current "
               "ladder so trigger guidance doesn't regress to the old "
               "blanket reason (rf2-dhnixf finding 2)."))
      (is (contains-any? body [":no-runtime-connected" ":build-not-running"
                               ":nrepl-unreachable"])
          (str "evals/evals.json no longer carries a fixture for a "
               "non-preload ladder reason (:no-runtime-connected / "
               ":build-not-running / :nrepl-unreachable). At least one "
               "non-preload rung must be represented (rf2-dhnixf finding 2).")))))

(deftest readme-routing-uses-optional-label-model
  (testing "skills/README.md routing index carries the optional-label / fail-open filing model (rf2-dhnixf finding 3)"
    (let [body @readme-md]
      (is (str/includes? body "Labels are optional taxonomy")
          (str "skills/README.md §Routing no longer states labels are "
               "optional taxonomy, not a filing precondition. An agent "
               "following the index could call `gh issue create --label "
               "pair-mcp` as mandatory and fail filing on a repo without "
               "that label (rf2-dhnixf finding 3)."))
      (is (contains-any? body ["gh label list"])
          (str "skills/README.md §Routing must reference `gh label list` "
               "as the precondition for adding a `--label`, matching the "
               "pair-retro filing contract (rf2-dhnixf finding 3)."))
      (is (not (re-find #"(?m)\*\*with\*\* the `pair-mcp` label" body))
          (str "skills/README.md §Routing still mandates filing **with** the "
               "`pair-mcp` label. Align with the optional-label model: the "
               "title/body carry the distinction; the label is optional "
               "(rf2-dhnixf finding 3)."))
      (is (str/includes? body "re-frame2-pair-retro/SKILL.md#filing-improvements")
          (str "skills/README.md §Routing should point at re-frame2-pair-"
               "retro's filing section rather than restate the operational "
               "label rules (rf2-dhnixf finding 3).")))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'retro-protocol-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
