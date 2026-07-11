#!/usr/bin/env bb
;;;; score-session-evidence-eval.clj — scorer for the pair-retro
;;;; SESSION-EVIDENCE behavioral eval.
;;;;
;;;; The `re-frame2-pair-retro` skill's §Session-evidence contract locks agent
;;;; RUNTIME BEHAVIOUR: does the retro bound to ONE causally-ordered session,
;;;; bind each result to its initiating call (a causal ledger, not
;;;; transcript-order), mark superseded state as superseded, mark partial
;;;; results unknown/incomplete, exclude unrelated CI/worker/code-review/
;;;; app-authoring activity, and — when two plausible sessions are present —
;;;; ask which to review rather than merging them? Eyeballing that is too easy
;;;; to skip and too hard to compare across model/tooling changes.
;;;;
;;;; This scorer makes the contract EXECUTABLE. The agent-execution step
;;;; (replay a fixture against a fresh skill session, capture the transcript)
;;;; is manual/opt-in — no Claude-in-the-loop CI harness exists yet. But once a
;;;; transcript is captured per the schema in `session-evidence-evals.json`
;;;; §harness.transcript_schema, this scorer produces a machine-readable
;;;; pass/fail artifact for the tool-call sequence + output checks, with a
;;;; one-line reason per failed check.
;;;;
;;;; SIBLING, not a copy: the shared security scorer
;;;; `skills/shared/tests/evals/score-behavioral-eval.clj` scores the three
;;;; shared SECURITY fixtures (injection / redaction / edit-gate) and carries
;;;; security-specific predicates (stable-placeholder identity, verbatim-recap
;;;; reproduction). This scorer scores a pair-retro-specific CORRECTNESS /
;;;; attribution boundary and implements only the four predicate classes this
;;;; eval needs. It is self-contained because the shared scorer is hardwired to
;;;; its own security manifest and runs as a script (not a require-able lib),
;;;; and because this eval lives in the pair-retro skill's own (unshipped)
;;;; evals/ tree.
;;;;
;;;; Capture a transcript like:
;;;;   {"eval_id": 1,
;;;;    "tool_calls": [{"name": "mcp__re-frame2-pair__discover-app",
;;;;                    "args": "{:build :cart-dev-a}"}, ...],
;;;;    "output": "…all agent-emitted text concatenated…"}
;;;;
;;;; Run:
;;;;   bb skills/re-frame2-pair-retro/evals/score-session-evidence-eval.clj <transcript.json>
;;;;   bb skills/re-frame2-pair-retro/evals/score-session-evidence-eval.clj --self-test
;;;;
;;;; Exit:  0 = all checks passed (or --self-test passed)
;;;;        1 = at least one check failed
;;;;        2 = usage / load error
;;;;
;;;; Output: machine-readable JSON report on stdout (store it as the artifact);
;;;;         a human one-line-per-check summary on stderr.

(ns score-session-evidence-eval
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private this-dir
  (-> *file* io/file .getAbsoluteFile .getParentFile))

(def ^:private manifest-file
  (io/file this-dir "session-evidence-evals.json"))

(defn- load-manifest []
  (json/parse-string (slurp manifest-file) true))

(defn- eval-by-id [manifest id]
  (->> (:evals manifest)
       (filter #(= id (:id %)))
       first))

;; ---------------------------------------------------------------------------
;; Check predicates — each returns a seq of failure maps (empty = pass).
;; A failure map: {:check <kw> :detail <str> :why <str>}
;; ---------------------------------------------------------------------------

(defn- tool-call-text [tc]
  ;; A tool call is matched against its name + args concatenated, so a forbidden
  ;; pattern can target either the tool name or its argument blob.
  (str (:name tc) " " (:args tc)))

(defn- check-forbidden-tool-calls [eval-spec transcript]
  (for [{:keys [pattern why]} (:forbidden_tool_calls eval-spec)
        tc                    (:tool_calls transcript)
        :let [re   (re-pattern pattern)
              text (tool-call-text tc)]
        :when (re-find re text)]
    {:check :forbidden-tool-call
     :detail (str "tool call " (pr-str text) " matched forbidden /" pattern "/")
     :why why}))

(defn- check-forbidden-output-substrings [eval-spec transcript]
  (let [out (str (:output transcript))]
    (for [{:keys [value why]} (:forbidden_output_substrings eval-spec)
          :when (str/includes? out value)]
      {:check :forbidden-output-substring
       :detail (str "raw forbidden substring present in output: " (pr-str value))
       :why why})))

(defn- check-forbidden-output-patterns [eval-spec transcript]
  (let [out (str (:output transcript))]
    (for [{:keys [pattern why]} (:forbidden_output_patterns eval-spec)
          :let [re (re-pattern pattern)
                m  (re-find re out)]
          :when m]
      {:check :forbidden-output-pattern
       :detail (str "output matched forbidden /" pattern "/ at " (pr-str m))
       :why why})))

(defn- check-required-output-patterns [eval-spec transcript]
  (let [out (str (:output transcript))]
    (for [{:keys [pattern why]} (:required_output_patterns eval-spec)
          :let [re (re-pattern pattern)]
          :when (not (re-find re out))]
      {:check :missing-required-output-pattern
       :detail (str "output did NOT match required /" pattern "/")
       :why why})))

;; --- Entity-anchored fixture bindings ----------------------------------------
;; The four session facts each eval hangs on are scored HERE, not by the loose
;; `required_output_patterns` class. A `required_binding` ties a rule to the
;; fixture ENTITY it must attach to (cart-dev-a's initiating call, cart-dev-b
;; superseding cart-dev-a, the tools/list probe, a NAMED out-of-scope activity),
;; so a content-free transcript that merely repeats contract vocabulary
;; ("causal ledger / superseded / unknown / excluded") with no fixture entities
;; FAILS — and the failure is reported BY LABEL (which binding is absent),
;; not as a generic regex miss. This is the fix for the lexical false-green
;; where keyword soup scored as evidenced (rf2-j538f7.38).

(defn- check-required-bindings [eval-spec transcript]
  (let [out (str (:output transcript))]
    (for [{:keys [label pattern why]} (:required_bindings eval-spec)
          :let [re (re-pattern pattern)]
          :when (not (re-find re out))]
      {:check :missing-required-binding
       :detail (str "output does not bind the required fixture fact "
                    (pr-str label) " (no match for /" pattern "/)")
       :why why})))

(defn- score-eval [eval-spec transcript]
  (let [failures (concat
                  (check-forbidden-tool-calls eval-spec transcript)
                  (check-forbidden-output-substrings eval-spec transcript)
                  (check-forbidden-output-patterns eval-spec transcript)
                  (check-required-output-patterns eval-spec transcript)
                  (check-required-bindings eval-spec transcript))]
    {:eval_id (:id eval-spec)
     :name (:name eval-spec)
     :fixture (:fixture eval-spec)
     :pass (empty? failures)
     :failures (vec failures)}))

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(defn- score-transcript-file [path]
  (let [manifest   (load-manifest)
        transcript (json/parse-string (slurp path) true)
        id         (:eval_id transcript)
        eval-spec  (eval-by-id manifest id)]
    (when-not eval-spec
      (binding [*out* *err*]
        (println (str "ERROR: transcript :eval_id " (pr-str id)
                      " does not match any eval in session-evidence-evals.json")))
      (System/exit 2))
    (let [report (score-eval eval-spec transcript)]
      ;; machine-readable artifact on stdout
      (println (json/generate-string report {:pretty true}))
      ;; human summary on stderr
      (binding [*out* *err*]
        (println (str "[" (if (:pass report) "PASS" "FAIL") "] eval " id
                      " — " (:name report) " (" (:fixture report) ")"))
        (doseq [f (:failures report)]
          (println (str "  x " (name (:check f)) ": " (:detail f)
                        "\n      -> " (:why f)))))
      (System/exit (if (:pass report) 0 1)))))

;; ---------------------------------------------------------------------------
;; Self-test — validates the manifest is loadable + well-formed and that the
;; scorer's predicate classes fire on crafted bad transcripts and pass on
;; crafted good ones, WITHOUT needing a captured agent run. This is the
;; always-on fast guard for the scorer itself; the structural prose locks live
;; in skills/shared/tests/retro_protocol_test.clj, which also runs this
;; --self-test.
;; ---------------------------------------------------------------------------

(defn- self-test []
  (let [manifest (load-manifest)
        errs (atom [])
        check (fn [ok? msg] (when-not ok? (swap! errs conj msg)))
        fires-detail? (fn [r kind needle]
                        (some #(and (= kind (:check %))
                                    (str/includes? (:detail %) needle))
                              (:failures r)))
        neg (fn [label r kind needle]
              (check (false? (:pass r)) (str "negative case did NOT fail: " label))
              (check (fires-detail? r kind needle)
                     (str "negative case " label " did not fire " kind
                          " naming " (pr-str needle) "; failures="
                          (pr-str (:failures r)))))
        pos (fn [label r]
              (check (true? (:pass r))
                     (str "known-good case " label " FAILED: " (pr-str (:failures r)))))
        e1 (eval-by-id manifest 1)
        e2 (eval-by-id manifest 2)]
    ;; ---- manifest shape ----
    (check (= "behavioral" (:eval_kind manifest)) "eval_kind is not \"behavioral\"")
    (check (= 2 (count (:evals manifest)))
           (str "expected 2 evals, got " (count (:evals manifest))))
    (check (every? :fixture (:evals manifest)) "an eval is missing its :fixture pointer")
    ;; every referenced fixture file exists on disk
    (doseq [{:keys [fixture]} (:evals manifest)]
      (check (.exists (io/file this-dir fixture))
             (str "fixture file missing on disk: " fixture)))
    ;; every regex compiles
    (doseq [e (:evals manifest)
            {:keys [pattern]} (concat (:forbidden_tool_calls e)
                                      (:forbidden_output_patterns e)
                                      (:required_output_patterns e)
                                      (:required_bindings e))]
      (try (re-pattern pattern)
           (catch Exception ex
             (swap! errs conj (str "bad regex /" pattern "/: " (.getMessage ex))))))

    ;; =====================================================================
    ;; EVAL 1 — entity-anchored fixture bindings.
    ;;
    ;; Each canonical sentence satisfies exactly ONE required binding; a
    ;; negative swaps ONE sentence to a WRONG entity so precisely that
    ;; binding predicate fires (no failure masks another). The forbidden-
    ;; pattern negatives keep all four bindings satisfied so only the
    ;; forbidden regex trips.
    ;; =====================================================================
    (let [s-causal    (str "Causal ledger: the delayed :no-runtime-connected is bound to "
                           "its initiating call, the earlier cart-dev-a discover-app.")
          s-supersede (str "cart-dev-b's successful attach supersedes that earlier "
                           "cart-dev-a result; cart-dev-b stayed healthy.")
          s-unknown   "The tools/list probe never returned, so it is unknown/incomplete."
          s-exclude   (str "Excluded (not pair-session friction): the invoice-export CI run "
                           "from another worker, the code-review comment, and the app "
                           "README edit.")
          good1       (str s-causal " " s-supersede " " s-unknown " " s-exclude)
          content-free "Causal ledger. This item superseded the prior state. Another fact is unknown. Excluded unrelated noise."]

      ;; ---- the exact content-free reproduction (rf2-j538f7.38 repro) fails
      ;;      EVERY fixture binding, not merely one generic regex ----
      (neg "eval1 content-free keyword soup"
           (score-eval e1 {:eval_id 1 :tool_calls [] :output content-free})
           :missing-required-binding "causal-ledger-cart-dev-a")
      (let [r (score-eval e1 {:eval_id 1 :tool_calls [] :output content-free})]
        (doseq [lbl ["causal-ledger-cart-dev-a" "supersession-b-over-a"
                     "tools-list-unknown" "exclude-out-of-scope"]]
          (check (fires-detail? r :missing-required-binding lbl)
                 (str "content-free reproduction did not report missing binding "
                      (pr-str lbl) "; failures=" (pr-str (:failures r))))))

      ;; ---- wrong-binding negatives: all four fixture facts MENTIONED, but
      ;;      ONE bound to the wrong entity → exactly that binding fires ----
      ;; A) causal ledger bound to the WRONG build (cart-dev-b, not cart-dev-a)
      (neg "eval1 causal bound to wrong build"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str "Causal ledger: the delayed :no-runtime-connected "
                                        "is bound to its initiating call, cart-dev-b's "
                                        "discover-app. " s-supersede " " s-unknown " " s-exclude)})
           :missing-required-binding "causal-ledger-cart-dev-a")
      ;; B) supersession stated in the WRONG direction (cart-dev-a over cart-dev-b)
      (neg "eval1 supersession wrong direction"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str s-causal " cart-dev-a's stale attach supersedes "
                                        "cart-dev-b's earlier result; cart-dev-b stayed "
                                        "healthy. " s-unknown " " s-exclude)})
           :missing-required-binding "supersession-b-over-a")
      ;; C) unknown/incomplete attached to the WRONG item (a build, not tools/list)
      (neg "eval1 unknown bound to wrong item"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str s-causal " " s-supersede " The tools/list probe "
                                        "returned a clean result. Meanwhile the cart-dev-a "
                                        "call is unknown/incomplete. " s-exclude)})
           :missing-required-binding "tools-list-unknown")
      ;; D) an IN-SCOPE build excluded as noise (wrong exclusion target)
      (neg "eval1 excludes an in-scope build"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str s-causal " " s-supersede " " s-unknown
                                        " Excluded the cart-dev-b attach as unrelated noise.")})
           :missing-required-binding "exclude-out-of-scope")

      ;; ---- forbidden-pattern negatives: all four bindings satisfied so only
      ;;      the forbidden regex fires ----
      ;; superseded state presented as current/final
      (neg "eval1 superseded-as-current"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str good1 " But the session is still "
                                        ":no-runtime-connected.")})
           :forbidden-output-pattern "no-runtime-connected")
      ;; delayed cart-dev-a result mis-bound into a cart-dev-b regression
      (neg "eval1 cart-dev-b regressed"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str s-causal " cart-dev-b's attach supersedes "
                                        "cart-dev-a, but then cart-dev-b broke and went "
                                        "down. " s-unknown " " s-exclude)})
           :forbidden-output-pattern "cart-dev-b")
      ;; unrelated invoice-export CI framed as this session's root cause
      (neg "eval1 CI as root cause"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str s-causal " " s-supersede " " s-unknown
                                        " Excluded (not pair-session friction): the "
                                        "code-review comment and the app README edit. "
                                        "The invoice-export CI failure was the root cause "
                                        "of the session friction.")})
           :forbidden-output-pattern "invoice-export")

      ;; known-good eval 1 — every fixture fact bound to its right entity
      (pos "eval1"
           (score-eval e1 {:eval_id 1 :tool_calls []
                           :output (str "Session scope: the cart-dev-b attach + "
                                        ":cart/apply-coupon dispatch + :main epoch walk on "
                                        "the coupon bug (session sentinel sess-7f3c). "
                                        good1)})))

    ;; =====================================================================
    ;; EVAL 2 — ask-when-ambiguous AND name both candidate sessions.
    ;; =====================================================================
    ;; named both candidates but did NOT ask (produced a retro instead)
    (neg "eval2 missing ask"
         (score-eval e2 {:eval_id 2
                         :tool_calls []
                         :output (str "Two pair sessions here: the cart-coupon session and "
                                      "the auth-login session. I went ahead and reviewed them.")})
         :missing-required-output-pattern "which session")
    ;; a context-free ask that identifies NEITHER candidate session
    (neg "eval2 context-free ask"
         (score-eval e2 {:eval_id 2
                         :tool_calls []
                         :output "Which session should I review?"})
         :missing-required-binding "names-both-candidates")
    ;; merged the two sessions (both named, asked, then merged anyway)
    (neg "eval2 merged sessions"
         (score-eval e2 {:eval_id 2
                         :tool_calls []
                         :output (str "Two sessions: the cart-coupon session and the "
                                      "auth-login session. Which session do you want? "
                                      "Meanwhile I merged the two sessions into one "
                                      "friction list.")})
         :forbidden-output-pattern "merg")
    ;; known-good eval 2
    (pos "eval2"
         (score-eval e2 {:eval_id 2
                         :tool_calls []
                         :output (str "Two plausible pair sessions are present (the "
                                      "cart-coupon session and the auth-login session). "
                                      "Which session should I retro?")}))

    (if (seq @errs)
      (do (binding [*out* *err*]
            (println "SELF-TEST FAILED:")
            (doseq [m @errs] (println "  x" m)))
          (System/exit 1))
      (do (println (str "SELF-TEST PASSED: session-evidence-evals.json well-formed; "
                        "scorer predicates fire (isolated per predicate)."))
          (System/exit 0)))))

;; ---------------------------------------------------------------------------
;; Entry
;; ---------------------------------------------------------------------------

(let [[arg] *command-line-args*]
  (cond
    (= "--self-test" arg) (self-test)
    (nil? arg)            (do (binding [*out* *err*]
                                (println "usage: score-session-evidence-eval.clj <transcript.json> | --self-test"))
                              (System/exit 2))
    (not (fs/exists? arg)) (do (binding [*out* *err*]
                                 (println (str "ERROR: transcript file not found: " arg)))
                               (System/exit 2))
    :else                 (score-transcript-file arg)))
