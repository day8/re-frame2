(ns re-frame.mcp-conformance.infinite-trace-ops-test
  "Keep infinite-feed vocabulary aligned across Spec 009, runtime emitters
  and Xray consumers. Emit pins inspect data; documentation pins inspect prose."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

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
  `state.cljc`'s `merge-pages->items` (the runtime-detected counterpart
  of the registration-time validation), catalogued in Spec 009 §Error
  event catalogue."
  {:op :rf.error/infinite-missing-page-accessor :emit resource-state})

(defn- literal-as-token?
  "True iff `kw`'s `pr-str` literal appears in `text` as a COMPLETE keyword
  token (not as a prefix of a longer keyword). Uses the same
  keyword-extender-aware boundary regex the slot-name near-miss pin uses,
  so `:rf.resource/load-more` does not match inside
  `:rf.resource/load-more-skipped`."
  [kw text]
  (boolean (re-find (rf.mcp-conformance.fixtures/variant-regex (pr-str kw)) text)))

(defn- emit-text [rel-path]
  (rf.mcp-conformance.fixtures/strip-comments-and-strings (rf.mcp-conformance.fixtures/read-source rel-path)))

(deftest spec-009-catalogues-the-infinite-vocabulary
  (let [doc (rf.mcp-conformance.fixtures/read-source spec-009)]
    (testing "Spec 009 §Where trace emission lives names each of the four ops"
      (doseq [{:keys [op]} infinite-trace-ops]
        (is (literal-as-token? op doc)
            (str op " MUST be catalogued in " spec-009
                 " — the four EP-0021 infinite-feed ops are the agent-facing "
                 "trace vocabulary; a missing catalogue row is a contract gap."))))
    (testing "Spec 009 §Error event catalogue carries the loud-merge error row"
      ;; the catalogue row's Channel value: a registration-time thrown
      ;; ex-info is diagnostic-channel (Spec 009 §Error event catalogue —
      ;; "a thrown ex-info registration rejection is diagnostic-channel").
      (is (re-find #"(?m)^\|\s*`:rf\.error/infinite-missing-page-accessor`\s*\|\s*`?:error`?\s*\|\s*diagnostic\s*\|"
                   doc)
          "the error row MUST carry op-type :error + Channel diagnostic"))))

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

(deftest xray-surface-carries-the-infinite-ops
  (let [spec024 (rf.mcp-conformance.fixtures/read-source xray-024)
        enum    (rf.mcp-conformance.fixtures/read-source xray-enum)]
    (testing "the Xray Resources-panel spec (024) documents each op"
      (doseq [{:keys [op]} infinite-trace-ops]
        (is (literal-as-token? op spec024)
            (str op " MUST appear in " xray-024
                 " — the Xray Resources-panel spec is the consumer surface for "
                 "the trace family."))))
    (testing "the closed trace-ops family enum carries each op with its class"
      (doseq [{:keys [op class]} infinite-trace-ops]
        ;; the op's enum map row carries its class keyword
        ;; (e.g. `:rf.resource/page-appended {:class :success ...}`), so one
        ;; match pins both membership and class — a class drift is a
        ;; behaviour change the Trace/Resources tabs colour on.
        (is (re-find (re-pattern (str (java.util.regex.Pattern/quote (pr-str op))
                                      "\\s+\\{:class\\s+"
                                      (java.util.regex.Pattern/quote (pr-str class))))
                     enum)
            (str op " MUST be a member of the closed `trace-ops` enum, classed "
                 class ", in " xray-enum
                 " trace-ops enum (Spec 009 / Xray 024 semantic class)."))))))
