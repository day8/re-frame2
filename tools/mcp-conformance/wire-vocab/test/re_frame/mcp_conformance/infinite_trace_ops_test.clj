(ns re-frame.mcp-conformance.infinite-trace-ops-test
  "EP-0021 infinite-resources tooling conformance (rf2-5oovkm, wave 6).

  The four `:infinite`-feed `:rf.resource/*` trace operations and the
  `:rf.error/infinite-missing-page-accessor` registration error are the
  agent-facing vocabulary an MCP / Xray consumer pattern-matches on to
  observe an infinite feed. A rename or pluralisation of any of them would
  silently break every tool that filters the trace stream for a load-more
  (`:rf.resource/load-more` / `…/page-appended` / `…/page-failed` /
  `…/load-more-skipped`) or surfaces the loud merge error.

  This gate is the cross-surface PIN that keeps the three homes of the
  vocabulary in lockstep (the same posture the wire-marker / cursor-stale
  gates apply to the `:rf.mcp/*` family):

    1. the NORMATIVE catalogue — `spec/009-Instrumentation.md`
       (§Where trace emission lives carries the four ops; §Error event
       catalogue carries the error row, wave-6 landed here);
    2. the runtime EMIT source — the four ops are `trace/emit!`'d from
       `implementation/resources/src/re_frame/resources/events.cljc`
       (waves 3-4) and the error is `throw-error!`'d from
       `…/resources/state.cljc` (wave 4);
    3. the Xray consumer surface — the `tools/xray` Resources panel spec
       (`024-Resources-Panel.md`) + the closed `trace-ops` family enum
       (`panels/resources_helpers.cljc`).

  Pure-JVM: source-text grep against the repo files (via `fixtures`'
  classpath-derived `repo-root` + the comment/string stripper), no server
  boot and no resources-artefact classpath dependency. A literal pinned in
  EMIT source is matched AFTER `strip-comments-and-strings` so a docstring
  mention cannot satisfy the emit pin; a literal pinned in a doc/spec is a
  looser raw-text match. The near-miss anti-pin forbids snake_case /
  pluralised / underscore-ns spellings of any of the five literals anywhere
  in the tracked files — and uses the keyword-boundary-aware `variant-regex`
  so `:rf.resource/load-more` is NOT reported as a near-miss of
  `:rf.resource/load-more-skipped` (one is a genuine keyword-token prefix of
  the other)."
  (:require [clojure.string :as str]
            [clojure.test    :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as fx]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as pins]))

;; ---------------------------------------------------------------------------
;; The closed EP-0021 infinite-feed trace vocabulary (the single source of
;; truth for this gate). Each entry pins the literal against its NORMATIVE
;; catalogue doc, its runtime EMIT source (where the literal MUST appear as
;; DATA, not a docstring), and the Xray consumer surfaces.
;; ---------------------------------------------------------------------------

(def ^:private spec-009 "spec/009-Instrumentation.md")
(def ^:private xray-024 "tools/xray/spec/024-Resources-Panel.md")
(def ^:private xray-enum
  "tools/xray/src/day8/re_frame2_xray/panels/resources_helpers.cljc")
(def ^:private resource-events
  "implementation/resources/src/re_frame/resources/events.cljc")
(def ^:private resource-state
  "implementation/resources/src/re_frame/resources/state.cljc")

(def ^:private infinite-trace-ops
  "The four EP-0021 `:infinite`-feed `:rf.resource/*` trace operations,
  each with the emit source (where it rides as DATA in a `trace/emit!`) and
  the semantic class the Xray family enum records (cross-checked against
  Spec 009 §Where trace emission lives + Xray 024 §The `:rf.resource/*`
  trace family)."
  [{:op :rf.resource/load-more         :emit resource-events :class :lifecycle}
   {:op :rf.resource/page-appended     :emit resource-events :class :success}
   {:op :rf.resource/page-failed       :emit resource-events :class :failure}
   {:op :rf.resource/load-more-skipped :emit resource-events :class :dedupe}])

(def ^:private infinite-error
  "The EP-0021 loud-merge registration error — `throw-error!`'d from
  `state.cljc`'s `merge-pages->items` (the wave-4 runtime-detected
  counterpart of the wave-2 registry validation), catalogued in Spec 009
  §Error event catalogue by THIS wave."
  {:op :rf.error/infinite-missing-page-accessor :emit resource-state})

(def ^:private all-literals
  "Every literal this gate pins — the four ops + the one error."
  (conj (mapv :op infinite-trace-ops) (:op infinite-error)))

;; ---------------------------------------------------------------------------
;; Source-text helpers (boundary-aware so a longer keyword that shares a
;; prefix never satisfies a shorter literal's pin — `:rf.resource/load-more`
;; vs `:rf.resource/load-more-skipped`).
;; ---------------------------------------------------------------------------

(defn- literal-as-token?
  "True iff `kw`'s `pr-str` literal appears in `text` as a COMPLETE keyword
  token (not as a prefix of a longer keyword). Uses the same
  keyword-extender-aware boundary regex the slot-name / indicator near-miss
  pins use, so `:rf.resource/load-more` does not match inside
  `:rf.resource/load-more-skipped`."
  [kw text]
  (boolean (re-find (fx/variant-regex (pr-str kw)) text)))

(defn- emit-text [rel-path]
  (fx/strip-comments-and-strings (fx/read-source rel-path)))

;; ---------------------------------------------------------------------------
;; (1) the NORMATIVE catalogue carries every literal (Spec 009).
;; ---------------------------------------------------------------------------

(deftest spec-009-catalogues-the-infinite-vocabulary
  (let [doc (fx/read-source spec-009)]
    (testing "Spec 009 §Where trace emission lives names each of the four ops"
      (doseq [{:keys [op]} infinite-trace-ops]
        (is (literal-as-token? op doc)
            (str op " MUST be catalogued in " spec-009
                 " — the four EP-0021 infinite-feed ops are the agent-facing "
                 "trace vocabulary; a missing catalogue row is a contract gap."))))
    (testing "Spec 009 §Error event catalogue carries the loud-merge error row"
      (is (literal-as-token? (:op infinite-error) doc)
          (str (:op infinite-error) " MUST be catalogued in " spec-009
               " (the wave-6 deliverable — the error id was reserved by wave 4 "
               "and lands its 009 row here)."))
      ;; the catalogue row's Channel value: a registration-time thrown
      ;; ex-info is diagnostic-channel (Spec 009 §Error event catalogue —
      ;; "a thrown ex-info registration rejection is diagnostic-channel").
      (is (re-find #"(?m)^\|\s*`:rf\.error/infinite-missing-page-accessor`\s*\|\s*`?:error`?\s*\|\s*diagnostic\s*\|"
                   doc)
          "the error row MUST carry op-type :error + Channel diagnostic"))))

;; ---------------------------------------------------------------------------
;; (2) the runtime EMITS every literal as DATA (not just a docstring).
;; ---------------------------------------------------------------------------

(deftest runtime-emits-the-infinite-vocabulary
  (testing "each of the four ops is trace/emit!'d from the resources events source"
    (doseq [{:keys [op emit]} infinite-trace-ops]
      (is (literal-as-token? op (emit-text emit))
          (str op " MUST appear as DATA in " emit
               " (a trace/emit! arg, surviving comment/string stripping) — "
               "this gate fails if the op stops being emitted or is renamed."))))
  (testing "the loud-merge error is throw-error!'d from the resources state source"
    (is (literal-as-token? (:op infinite-error) (emit-text (:emit infinite-error)))
        (str (:op infinite-error) " MUST appear as DATA in " (:emit infinite-error)
             " (the throw-error! arg in merge-pages->items)."))))

;; ---------------------------------------------------------------------------
;; (3) the Xray consumer surface stays in lockstep (spec 024 + the closed
;;     family enum). The standing "xray spec updated in the same PR" rule.
;; ---------------------------------------------------------------------------

(deftest xray-surface-carries-the-infinite-ops
  (let [spec024 (fx/read-source xray-024)
        enum    (fx/read-source xray-enum)]
    (testing "the Xray Resources-panel spec (024) documents each op"
      (doseq [{:keys [op]} infinite-trace-ops]
        (is (literal-as-token? op spec024)
            (str op " MUST appear in " xray-024
                 " — the Xray Resources-panel spec is the consumer surface for "
                 "the trace family (standing same-PR currency rule)."))))
    (testing "the closed trace-ops family enum carries each op with its class"
      (doseq [{:keys [op class]} infinite-trace-ops]
        (is (literal-as-token? op enum)
            (str op " MUST be a member of the closed `trace-ops` enum in "
                 xray-enum))
        ;; the per-op class keyword appears alongside the op in the enum map
        ;; row (e.g. `:rf.resource/page-appended {:class :success ...}`) — a
        ;; class drift is a behaviour change the Trace/Resources tabs colour on.
        (is (re-find (re-pattern (str (java.util.regex.Pattern/quote (pr-str op))
                                      "\\s+\\{:class\\s+"
                                      (java.util.regex.Pattern/quote (pr-str class))))
                     enum)
            (str op " MUST be classed " class " in the " xray-enum
                 " trace-ops enum (Spec 009 / Xray 024 semantic class)."))))))

;; ---------------------------------------------------------------------------
;; (4) near-miss anti-pin — no drifted spelling co-exists anywhere in the
;;     tracked files. Mirrors the wire-marker / cursor-stale near-miss sweep.
;; ---------------------------------------------------------------------------

(def ^:private tracked-files
  "Every conformance-tracked file this gate pins the vocabulary against.
  A near-miss spelling MUST NOT appear in ANY of them."
  [spec-009 xray-024 xray-enum resource-events resource-state])

(deftest no-near-miss-spelling-of-the-infinite-vocabulary
  (doseq [file tracked-files
          :let [text (fx/read-source file)]
          kw   all-literals
          near (pins/near-miss-variants kw)
          ;; a genuine canonical literal that is a PREFIX of `near` (the
          ;; `pluralised` / `predicate` variants of the shorter op) is NOT a
          ;; near-miss when the longer literal is itself canonical: e.g.
          ;; `:rf.resource/load-more` + "s" = "…load-mores", harmless; but
          ;; "…load-more?" must still be forbidden. The boundary regex on the
          ;; FULL near-miss string handles the prefix case, and we additionally
          ;; skip a `near` that IS exactly another canonical literal.
          :when (not (some #(= near (pr-str %)) all-literals))]
    (is (not (re-find (fx/variant-regex near) text))
        (str "near-miss spelling " (pr-str near) " of the canonical literal "
             (pr-str kw) " MUST NOT appear in " file
             " — a drifted spelling silently breaks agent pattern-matching."))))

;; ---------------------------------------------------------------------------
;; (5) internal sanity — the enum classes match the Spec 009 / EP-0021
;;     semantic intent (lifecycle / success / failure / dedupe).
;; ---------------------------------------------------------------------------

(deftest the-four-ops-carry-the-expected-semantic-classes
  (testing "the closed vocabulary is exactly four ops + one error"
    (is (= 4 (count infinite-trace-ops)))
    (is (= 5 (count all-literals))))
  (testing "each op's class is one of the family's closed class set"
    (let [classes #{:lifecycle :success :failure :dedupe :invalidation
                    :gc :suppression :hydration}]
      (doseq [{:keys [op class]} infinite-trace-ops]
        (is (contains? classes class)
            (str op "'s class " class " MUST be a member of the closed "
                 "trace-family class set (Xray 024 §The :rf.resource/* trace family)."))))))
