(ns re-frame.mcp-conformance.result-envelope-test
  "`:rf.mcp/result` typed eval/handler result-envelope gate
  (rf2-ihf8v; follow-on of rf2-y3qpv finding 2 / rf2-qobqy). Split out of
  `wire_vocab_test.clj` by rf2-7ckmwx.

  ## Why this marker needs a bespoke gate

  The wrapper-shaped markers in `schemas/canonical-markers` are
  `{<marker-key> <body-map>}` — the value is a body the agent walks.
  `:rf.mcp/result` is shaped differently: the marker key is itself the
  DISCRIMINATOR and its VALUE is a tag keyword (`:value` / `:nil` /
  `:eval-error` / `:unserializable`) with TAG-SPECIFIC SIBLING SLOTS
  riding alongside the marker key (per `mcp-base/vocab.cljc/result-key`
  and re-frame2-pair-mcp `tools/result_envelope.cljs` `wrap-form` /
  `envelope->result`). It is a wire-FIDELITY envelope: it TYPES an
  evaluation's outcome so a genuine `nil`, a thrown eval-error, and an
  unserializable value (`#object` / `#js` / Function) stop collapsing
  to a bare `null`. That tagged-union shape does not fit the wrapper
  table — so, like `:rf.mcp/cursor-stale` (a `:reason` value) and
  `:rf/redacted` (a bare scalar), it gets its own dedicated gate.

  ## What this closes (the y3qpv finding-2 follow-up)

  rf2-y3qpv pinned the marker KEY (`:rf.mcp/result` = `vocab/result-key`)
  in the mcp-base vocab unit test, but the cross-MCP wire-vocab corpus
  did NOT yet pin the marker's SHAPE — the `tools/mcp-base/spec/vocab.md`
  row carried a \"(Defined-but-not-yet-cross-gated …)\" note. This gate
  pins the four-tag shape so a drift in the envelope grammar (a tag
  renamed, a required slot dropped, a cross-tag slot leak) trips the
  conformance corpus. The vocab.md note is removed in the same change.

  ## Live-vs-fixture posture (Axis 4 of the rf2-80y2h audit)

  The EMITTER (`result_envelope.cljs` `wrap-form`) is CLJS, with no
  JVM-reachable counterpart — same posture as `:rf.mcp/summary` /
  `:rf.mcp/cache-hit`: FIXTURE+grep only, no pure-JVM live-emission
  gate. The marker KEY literal IS JVM-reachable (`vocab/result-key`,
  a `.cljc` def on the `:test` classpath), so the source-text pin
  asserts the live constant rather than a re-typed literal."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]))

(def ResultEnvelope
  "Canonical `:rf.mcp/result` typed-result envelope shape. A
  tagged-union dispatched on the marker key's VALUE — the four tags
  re-frame2-pair-mcp's `wrap-form` / `envelope->result` emit. Each arm
  is CLOSED to the documented slot set per tag, so a cross-tag slot
  leak (e.g. a `:nil` carrying `:value`, an `:unserializable` carrying
  `:reason`) is rejected — the same posture as `SummaryBody`'s per-type
  dispatch. The marker key (`:rf.mcp/result`) and its tag-specific
  sibling slots ride as the top-level envelope (NOT a single-key
  wrapper — like `:rf.mcp/diff-from`, the discriminator + its body
  slots are co-resident top-level keys).

  Tag shapes (per `tools/mcp-base/spec/vocab.md` + `result_envelope.cljs`):

  - `:value`          — `{:rf.mcp/result :value :value <v>}`. A genuine
                        EDN-round-trippable value rides under `:value`
                        (which is `:any` — incl. a literal nil only via
                        the distinct `:nil` tag; a nil VALUE is tagged
                        `:nil`, never `:value nil`).
  - `:nil`            — `{:rf.mcp/result :nil}`. The form genuinely
                        returned nil — NO sibling slots. Distinct from
                        `:value` so a real nil stops collapsing to a
                        bare null on the wire.
  - `:eval-error`     — `{:rf.mcp/result :eval-error :reason <kw>
                        :ex <str> [:message <str|nil>] [:ex-data <any>]}`.
                        The form threw. `:reason` + `:ex` are required;
                        `:message` / `:ex-data` are OPTIONAL — the
                        inner-catch path emits them (possibly nil), the
                        outer wrap-failed path
                        (`:rf.error/result-envelope-wrap-failed`) emits
                        only `:reason` + `:ex`.
  - `:unserializable` — `{:rf.mcp/result :unserializable :type <str>
                        :preview <str>}`. The value `pr-str`'d to text
                        EDN cannot read back (`#object` / `#js` /
                        Function). `:type` is the JS `goog/typeOf`
                        name; `:preview` is the truncated `pr-str` text."
  [:multi {:dispatch :rf.mcp/result}
   [:value
    [:map {:closed true}
     [:rf.mcp/result [:= :value]]
     [:value :any]]]
   [:nil
    [:map {:closed true}
     [:rf.mcp/result [:= :nil]]]]
   [:eval-error
    [:map {:closed true}
     [:rf.mcp/result [:= :eval-error]]
     [:reason  :keyword]
     [:ex      :string]
     [:message {:optional true} [:maybe :string]]
     [:ex-data {:optional true} :any]]]
   [:unserializable
    [:map {:closed true}
     [:rf.mcp/result [:= :unserializable]]
     [:type    :string]
     [:preview :string]]]])

(def ^:private result-envelope-fixtures
  "Per-tag fixtures mirroring re-frame2-pair-mcp's actual emissions
  (`tools/re-frame2-pair-mcp/test/.../conformance_test.cljs` +
  `eval_cljs_test.cljs`). Every fixture MUST validate against
  `ResultEnvelope`.

  Two `:eval-error` fixtures pin BOTH emit paths: the inner-catch shape
  (carries `:message` + `:ex-data`) and the outer wrap-failed shape
  (`:reason :rf.error/result-envelope-wrap-failed`, no `:message` /
  `:ex-data`) — so a regression in either branch's slot set trips the
  gate."
  {:re-frame2-pair-mcp-value
   {:rf.mcp/result :value :value {:a 1 :b [2 3]}}

   :re-frame2-pair-mcp-value-scalar
   {:rf.mcp/result :value :value 5}

   :re-frame2-pair-mcp-nil
   {:rf.mcp/result :nil}

   :re-frame2-pair-mcp-eval-error-inner
   {:rf.mcp/result :eval-error
    :reason        :rf.error/eval-cljs-threw
    :ex            "#error {:message \"undefined-symbol is not defined\"}"
    :message       "undefined-symbol is not defined"
    :ex-data       nil}

   :re-frame2-pair-mcp-eval-error-wrap-failed
   {:rf.mcp/result :eval-error
    :reason        :rf.error/result-envelope-wrap-failed
    :ex            "#error {:message \"classification machinery failed\"}"}

   :re-frame2-pair-mcp-unserializable
   {:rf.mcp/result :unserializable
    :type          "object"
    :preview       "#object[Object [object console]]"}})

(deftest result-envelope-fixtures-conform-to-schema
  ;; Every authored per-tag fixture validates against the single
  ;; canonical `ResultEnvelope` schema — the primary conformance
  ;; assertion (mirrors `every-fixture-conforms-to-its-canonical-schema`
  ;; for the wrapper markers).
  (doseq [[fixture-name fixture-value] result-envelope-fixtures]
    (testing (str ":rf.mcp/result — fixture " fixture-name)
      (is (m/validate ResultEnvelope fixture-value)
          (str "Fixture " fixture-name " for :rf.mcp/result failed schema "
               "validation:\n"
               (me/humanize (m/explain ResultEnvelope fixture-value)))))))

(deftest result-envelope-enforces-per-tag-shape
  ;; The teeth: each tag's CLOSED arm rejects malformed shapes — a tag
  ;; missing a required slot, a cross-tag slot leak, or an unknown tag.
  ;; Without these the schema could stay green while the envelope grammar
  ;; drifted (the exact false-green class y3qpv's finding-2 follow-up
  ;; closes for this marker).
  (testing ":value WITHOUT the :value slot fails"
    (is (not (m/validate ResultEnvelope {:rf.mcp/result :value}))
        "a :value result MUST carry the :value slot"))
  (testing ":nil carrying a sibling slot fails (no extra slots allowed)"
    (is (not (m/validate ResultEnvelope {:rf.mcp/result :nil :value 1}))
        "a :nil result MUST be the bare tag — a sibling slot is a leak")
    (is (not (m/validate ResultEnvelope {:rf.mcp/result :nil :preview "x"}))
        "a :nil result MUST NOT carry an :unserializable slot"))
  (testing ":eval-error WITHOUT :reason fails"
    (is (not (m/validate ResultEnvelope
                         {:rf.mcp/result :eval-error :ex "boom"}))
        "an :eval-error MUST carry :reason"))
  (testing ":eval-error WITHOUT :ex fails"
    (is (not (m/validate ResultEnvelope
                         {:rf.mcp/result :eval-error
                          :reason :rf.error/eval-cljs-threw}))
        "an :eval-error MUST carry :ex"))
  (testing ":unserializable WITHOUT :type fails"
    (is (not (m/validate ResultEnvelope
                         {:rf.mcp/result :unserializable :preview "x"}))
        "an :unserializable MUST carry :type"))
  (testing ":unserializable WITHOUT :preview fails"
    (is (not (m/validate ResultEnvelope
                         {:rf.mcp/result :unserializable :type "object"}))
        "an :unserializable MUST carry :preview"))
  (testing "cross-tag slot leak: :value carrying an :unserializable slot fails"
    (is (not (m/validate ResultEnvelope
                         {:rf.mcp/result :value :value 1 :preview "x"}))
        "a :value result MUST NOT carry the unserializable-only :preview slot"))
  (testing "cross-tag slot leak: :unserializable carrying an :eval-error slot fails"
    (is (not (m/validate ResultEnvelope
                         {:rf.mcp/result :unserializable :type "object"
                          :preview "x" :reason :boom}))
        "an :unserializable result MUST NOT carry the eval-error-only :reason slot"))
  (testing "unknown tag fails (no dispatch arm)"
    (is (not (m/validate ResultEnvelope {:rf.mcp/result :bogus}))
        "an unrecognised tag has no dispatch arm and MUST fail"))
  (testing "an extra sibling top-level key on a :value result fails"
    (is (not (m/validate ResultEnvelope
                         {:rf.mcp/result :value :value 1 :sneaky :key}))
        "the envelope is CLOSED per tag — an unrelated sibling key is rejected"))
  (testing "positive guard: every documented tag shape still validates"
    (is (m/validate ResultEnvelope {:rf.mcp/result :value :value 42}))
    (is (m/validate ResultEnvelope {:rf.mcp/result :value :value nil})
        "a :value slot may itself be nil (the slot is :any) — distinct from the :nil tag")
    (is (m/validate ResultEnvelope {:rf.mcp/result :nil}))
    (is (m/validate ResultEnvelope {:rf.mcp/result :eval-error
                                    :reason :rf.error/eval-cljs-threw
                                    :ex "boom"})
        ":eval-error with only the required :reason + :ex (wrap-failed path) validates")
    (is (m/validate ResultEnvelope {:rf.mcp/result :eval-error
                                    :reason :rf.error/eval-cljs-threw
                                    :ex "boom" :message "boom" :ex-data {:k :v}})
        ":eval-error with the optional :message + :ex-data (inner-catch path) validates")
    (is (m/validate ResultEnvelope {:rf.mcp/result :unserializable
                                    :type "function" :preview "#object[Function]"}))))

(deftest result-envelope-distinguishes-nil-from-value-and-errors
  ;; The load-bearing reason the marker exists (rf2-qobqy): a genuine
  ;; nil, an eval-error, and an unserializable value MUST be three
  ;; DISTINCT tags — pre-marker they all collapsed to a bare null. Pin
  ;; that the schema discriminates them: the `:nil` tag is NOT the
  ;; `:value` tag, and neither error tag validates as a value.
  (let [genuine-nil {:rf.mcp/result :nil}
        value-nil   {:rf.mcp/result :value :value nil}
        eval-error  {:rf.mcp/result :eval-error
                     :reason :rf.error/eval-cljs-threw :ex "boom"}
        unser       {:rf.mcp/result :unserializable
                     :type "object" :preview "#object[…]"}]
    (testing "all four distinct tags validate"
      (is (every? #(m/validate ResultEnvelope %)
                  [genuine-nil value-nil eval-error unser])
          "the four typed outcomes are each a valid envelope"))
    (testing "the genuine-nil tag is :nil, the value-nil tag is :value"
      (is (= :nil   (:rf.mcp/result genuine-nil)))
      (is (= :value (:rf.mcp/result value-nil)))
      (is (not= (:rf.mcp/result genuine-nil) (:rf.mcp/result value-nil))
          "a genuine nil and a nil-valued :value are DIFFERENT tags — the wire-fidelity contract"))))

(deftest result-key-literal-in-re-frame2-pair-mcp-emit-source
  ;; Source-text pin: the canonical `:rf.mcp/result` literal MUST appear
  ;; as DATA (the `result-key` def value) in mcp-base/vocab.cljc — the
  ;; single-source-of-truth home re-frame2-pair-mcp consumes via the
  ;; `vocab/result-key` symbol. Stripped before grep so a rename of the
  ;; def value trips the gate even if old docstrings still mention the
  ;; prior name. Mirrors `cursor-stale-literal-in-re-frame2-pair-mcp-emit-source`.
  (let [literal  ":rf.mcp/result"
        rel      "tools/mcp-base/src/re_frame/mcp_base/vocab.cljc"
        stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings (rf.mcp-conformance.fixtures/read-source rel))]
    (is (str/includes? stripped literal)
        (str literal " missing from " rel
             " AFTER stripping docstrings/comments. The canonical "
             "`result-key` declaration moved — restore the literal or "
             "update this test."))))

(deftest result-key-matches-the-live-vocab-constant
  ;; Pin the gate's dispatch keyword against the live JVM-reachable
  ;; `vocab/result-key` constant (the `.cljc` def is on the `:test`
  ;; classpath). The schema dispatches on `:rf.mcp/result` literally; if
  ;; the vocab constant ever drifted from that literal, this gate trips —
  ;; the schema and the canonical key cannot diverge silently.
  (is (= :rf.mcp/result rf.mcp-base.vocab/result-key)
      (str "vocab/result-key MUST be :rf.mcp/result — the literal the "
           "ResultEnvelope schema dispatches on. Got "
           (pr-str rf.mcp-base.vocab/result-key))))

(deftest result-key-literal-in-mcp-base-vocab-spec-doc-source
  ;; Doc-source pin (looser, raw `str/includes?`): the mcp-base
  ;; `vocab.md` spec — where the marker catalogue row lives and where
  ;; the "(Defined-but-not-yet-cross-gated …)" note was removed by this
  ;; change — MUST still carry the `:rf.mcp/result` literal for human
  ;; readers. re-frame2-pair-mcp's own doc-sources (Principles.md /
  ;; 003-Tool-Catalogue.md) do not catalogue this marker; the canonical
  ;; prose home is the mcp-base vocab spec.
  (let [literal ":rf.mcp/result"
        rel     "tools/mcp-base/spec/vocab.md"]
    (is (str/includes? (rf.mcp-conformance.fixtures/read-source rel) literal)
        (str literal " missing from " rel
             ". The vocab spec catalogues this marker — restore the row "
             "or update this test."))))

(deftest result-key-no-near-miss-in-any-server-source
  ;; Defence-in-depth: a rename to a near-miss form (snake_case,
  ;; pluralised, predicate `?` suffix, underscore-ns) MUST NOT co-exist
  ;; anywhere in the conformance-tracked source/spec tree. Mirrors the
  ;; marker-key near-miss anti-pin. The mcp-base vocab spec is folded
  ;; into the sweep so a near-miss in the catalogue row trips here too.
  (let [sweep (update rf.mcp-conformance.wire-vocab.source-pins/all-source-files :re-frame2-pair-mcp
                      (fnil conj []) "tools/mcp-base/spec/vocab.md")]
    (doseq [variant         (rf.mcp-conformance.wire-vocab.source-pins/near-miss-variants :rf.mcp/result)
            [server files]  sweep
            rel             files]
      (testing (str server " — " rel " — near-miss " variant)
        (is (not (str/includes? (rf.mcp-conformance.fixtures/read-source rel) variant))
            (str "Found near-miss variant " variant
                 " for :rf.mcp/result in " server "/" rel
                 " — vocabulary-drift bug. The canonical form is "
                 ":rf.mcp/result (per mcp-base/vocab.cljc `result-key`)."))))))
