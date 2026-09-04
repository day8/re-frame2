(ns re-frame.mcp-conformance.cursor-stale-test
  "`:rf.mcp/cursor-stale` MULTI-server reason-value gate
  (rf2-i3ffz F-GAP-5; promoted to multi-server by rf2-2js41 finding 1).
  Split out of `wire_vocab_test.clj` by rf2-7ckmwx.

  Unlike the wrapper-shaped markers in `schemas/canonical-markers`,
  `:rf.mcp/cursor-stale` rides as the `:reason` value on a generic
  `{:ok? false ...}` error envelope (per `mcp-base/vocab.cljc/
  cursor-stale-reason`). The conformance contract is the keyword
  itself: a rename or pluralisation would silently break every agent
  that pattern-matches on it.

  BOTH MCP servers emit this reason value (rf2-2js41 finding 1):
    - re-frame2-pair-mcp — epoch-id rotated out of the bounded ring
      (`tools/cursor.cljs/cursor-stale-result`).
    - story-mcp — the Docs `list-*` registry id-set changed between
      cursor-mint and cursor-deref
      (`tools/story-mcp/.../tools/cursor.cljc/cursor-stale-result`).
  Both delegate to the shared `mcp-base/cursor.cljc/cursor-stale-result`,
  which sources `:reason` from `vocab/cursor-stale-reason` so the two
  emissions stay byte-identical on the reason keyword.

  The pin shape mirrors the wrapper-marker pins, across BOTH servers:
    1. authored fixtures validate against `CursorStaleResult` (one per
       server emission shape).
    2. LIVE-builder gates drive EACH server's `cursor-stale-result` and
       validate the emission (pair-mcp's data-map directly; story-mcp's
       `:structuredContent` slot).
    3. literal appears in the shared emit-source (mcp-base/vocab.cljc)
       AND in story-mcp's own cursor source (it documents/restarts on
       the reason).
    4. literal appears in re-frame2-pair-mcp's doc-sources (003-Tool-Catalogue.md).
    5. no near-miss spelling co-exists in any conformance-tracked file
       — now INCLUDING story-mcp's cursor source (added to the sweep)."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.mcp-base.vocab  :as rf.mcp-base.vocab]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.schemas :refer [CursorStaleResult]]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]
            ;; story-mcp's cursor-stale builder, so the gate can drive the
            ;; SECOND server's emission live (rf2-2js41 finding 1).
            [re-frame.story-mcp.tools.cursor :as rf.story-mcp.tools.cursor]))

(def ^:private cursor-stale-fixture
  "Canonical re-frame2-pair-mcp emission shape from `tools/cursor.cljs/
  cursor-stale-result`. The envelope-specific slots (`:tool`,
  `:requested-id`, `:head-id`, `:hint`) are open per-server; the
  load-bearing contract is `:ok? false` + `:reason :rf.mcp/cursor-stale`."
  {:ok?          false
   :reason       :rf.mcp/cursor-stale
   :tool         "watch-epochs"
   :requested-id "epoch-9001"
   :head-id      "epoch-9101"
   :hint         "Cursor's epoch-id is no longer in the runtime ring. Drop the cursor or widen the window."})

(def ^:private story-cursor-stale-fixture
  "Canonical story-mcp emission shape — the `:structuredContent` slot of
  `re-frame.story-mcp.tools.cursor/cursor-stale-result` (per
  `tools/story-mcp/.../tools/result.cljc/error-result`). story-mcp's
  staleness cause is a registry id-set change between cursor pages, not
  a ring rotation, but the cross-MCP `:reason` + `:ok? false` posture is
  identical — that shared vocabulary IS the contract this fixture pins.
  Authored here; the live-builder gate below proves the real story-mcp
  builder actually emits it."
  {:ok?    false
   :reason :rf.mcp/cursor-stale
   :tool   "list-stories"
   :hint   "Drop :cursor and re-request from offset 0."})

(deftest cursor-stale-fixture-conforms-to-schema
  ;; Both per-server emission-shape fixtures validate against the single
  ;; canonical schema (rf2-2js41 finding 1 — cursor-stale is multi-server).
  (testing "re-frame2-pair-mcp emission shape (ring rotation)"
    (is (m/validate CursorStaleResult cursor-stale-fixture)
        (str "pair-mcp fixture for :rf.mcp/cursor-stale failed schema validation:\n"
             (me/humanize (m/explain CursorStaleResult cursor-stale-fixture)))))
  (testing "story-mcp emission shape (registry change between pages)"
    (is (m/validate CursorStaleResult story-cursor-stale-fixture)
        (str "story-mcp fixture for :rf.mcp/cursor-stale failed schema validation:\n"
             (me/humanize (m/explain CursorStaleResult story-cursor-stale-fixture))))))

(deftest cursor-stale-rejects-non-error-envelopes
  ;; The reason value MUST ride a `:ok? false` envelope — emitting
  ;; `{:ok? true :reason :rf.mcp/cursor-stale}` would be a contract
  ;; break (success doesn't carry a stale-reason).
  (is (not (m/validate CursorStaleResult
                       {:ok? true :reason :rf.mcp/cursor-stale}))
      "CursorStaleResult MUST reject :ok? true")
  (is (not (m/validate CursorStaleResult
                       {:ok? false :reason :rf.mcp/cursor-stales}))
      "CursorStaleResult MUST reject the pluralised near-miss"))

(deftest cursor-stale-reason-emitted-live-by-canonical-builder
  ;; LIVE-emission gate for `:rf.mcp/cursor-stale` (mirrors
  ;; `overflow-marker-shape-emitted-live-by-canonical-builder` for
  ;; `:rf.mcp/overflow`). Both reason/marker builders were hoisted into
  ;; the shared `mcp-base` ns (rf2-ee38b.19), so both are now
  ;; JVM-reachable from this pure-JVM gate — `overflow-payload` already
  ;; got its live counterpart, `cursor-stale-result` did not.
  ;;
  ;; The gap this closes: the existing cursor-stale coverage was exactly
  ;; the two layers rf2-80y2h flagged as insufficient for `diff-from` —
  ;; (1) an authored fixture validated against `CursorStaleResult`, and
  ;; (2) a source-text grep that the `:rf.mcp/cursor-stale` literal is
  ;; DECLARED in `mcp-base/vocab.cljc`. Neither observes the actual
  ;; BUILDER. A regression that hardcoded a drifted `:reason` literal in
  ;; `cursor-stale-result` (decoupling it from `vocab/cursor-stale-reason`),
  ;; or dropped the `:ok? false` posture, would: leave the vocab literal
  ;; in place (grep passes), leave the authored fixture untouched
  ;; (fixture passes), and ship a builder whose emission no longer
  ;; matches the constant agents pattern-match on. Every gate green.
  ;;
  ;; Drive the real builder with a minimal `error-result` that merely
  ;; returns the structured data-map (each server shapes the wire
  ;; envelope its own way; the cross-MCP contract this builder owns is
  ;; the `:reason` value + the `:ok? false` posture, per the
  ;; `cursor-stale-result` docstring) and assert the emitted envelope.
  (let [emitted (rf.mcp-base.cursor/cursor-stale-result
                  (fn [_message data] data)
                  "watch-epochs"
                  {:extra {:requested-id "epoch-9001"
                           :head-id      "epoch-9101"}})]
    (testing "the builder sources :reason from vocab/cursor-stale-reason"
      (is (= rf.mcp-base.vocab/cursor-stale-reason (:reason emitted))
          (str "cursor-stale-result MUST emit the canonical "
               ":reason value (vocab/cursor-stale-reason). If this fails, "
               "the builder hardcoded a literal that drifted from the "
               "vocab constant agents pattern-match on. Got :reason = "
               (pr-str (:reason emitted)))))
    (testing "the emitted :reason is the pinned cross-MCP keyword"
      (is (= :rf.mcp/cursor-stale (:reason emitted))
          "the canonical reason keyword is :rf.mcp/cursor-stale"))
    (testing "the emitted envelope validates against canonical CursorStaleResult"
      (is (m/validate CursorStaleResult emitted)
          (str "Live-emitted cursor-stale envelope failed CursorStaleResult "
               "validation:\n" (me/humanize (m/explain CursorStaleResult emitted)))))
    (testing "the builder preserves the :ok? false error posture"
      (is (false? (:ok? emitted))
          "cursor-stale rides an :ok? false envelope — success never carries a stale-reason"))
    (testing "the consumer's :extra slots merge through verbatim"
      (is (= "epoch-9001" (:requested-id emitted)))
      (is (= "epoch-9101" (:head-id emitted))))))

(deftest story-cursor-stale-emitted-live-by-canonical-builder
  ;; SECOND-server LIVE-emission gate for `:rf.mcp/cursor-stale`
  ;; (rf2-2js41 finding 1). The gate above drives pair-mcp's reason
  ;; through the SHARED `mcp-base/cursor.cljc` builder; this one drives
  ;; STORY-MCP's own `re-frame.story-mcp.tools.cursor/cursor-stale-result`
  ;; — the builder for the Docs `list-*` pagination surface.
  ;;
  ;; The gap this closes: before this gate, story-mcp's cursor-stale
  ;; emission was caught ONLY by story-mcp's local unit tests
  ;; (`tools/story-mcp/test/...`). A drift in story-mcp's envelope shape
  ;; or a decoupling of its `:reason` from the cross-MCP vocab constant
  ;; would NOT trip the conformance surface that exists to enforce the
  ;; SHARED agent vocabulary across servers. The cross-server contract
  ;; was weaker than the (now multi-server) implementation.
  ;;
  ;; story-mcp's `cursor-stale-result` wraps the shared mcp-base builder
  ;; in its MCP wire envelope via `result/error-result`, so the emission
  ;; is `{:content [...] :isError true :structuredContent <data-map>}`.
  ;; The cross-MCP reason-value contract lives on the `:structuredContent`
  ;; slot — that is what we validate against canonical `CursorStaleResult`
  ;; (the same schema pair-mcp's data-map validates against). Validating
  ;; the structured slot, not the text blob, is the load-bearing pin: an
  ;; agent host that reads JSON pattern-matches on `:reason` there.
  (let [emitted    (rf.story-mcp.tools.cursor/cursor-stale-result "list-stories")
        structured (:structuredContent emitted)]
    (testing "story-mcp wraps the reason in an MCP error envelope"
      (is (true? (:isError emitted))
          "story-mcp cursor-stale rides an :isError true MCP result (per result/error-result)")
      (is (some? structured)
          "story-mcp cursor-stale MUST carry the structured data-map on :structuredContent"))
    (testing "the structured content sources :reason from vocab/cursor-stale-reason"
      (is (= rf.mcp-base.vocab/cursor-stale-reason (:reason structured))
          (str "story-mcp cursor-stale-result MUST emit the canonical "
               ":reason value (vocab/cursor-stale-reason). If this fails, "
               "story-mcp's builder drifted from the shared cross-MCP "
               "constant agents pattern-match on. Got :reason = "
               (pr-str (:reason structured)))))
    (testing "the emitted :reason is the pinned cross-MCP keyword"
      (is (= :rf.mcp/cursor-stale (:reason structured))
          "the canonical reason keyword is :rf.mcp/cursor-stale"))
    (testing "the structured content validates against canonical CursorStaleResult"
      (is (m/validate CursorStaleResult structured)
          (str "Live-emitted story-mcp cursor-stale :structuredContent failed "
               "CursorStaleResult validation:\n"
               (me/humanize (m/explain CursorStaleResult structured)))))
    (testing "the builder preserves the :ok? false error posture"
      (is (false? (:ok? structured))
          "cursor-stale rides an :ok? false envelope — success never carries a stale-reason"))
    (testing "the tool name threads through to the structured slot"
      (is (= "list-stories" (:tool structured))))
    (testing "pair-mcp + story-mcp emit the IDENTICAL cross-MCP reason value"
      ;; The whole point of the multi-server contract: an agent learns
      ;; the keyword once and reuses the recovery path on either server.
      (let [pair-reason (:reason (rf.mcp-base.cursor/cursor-stale-result
                                   (fn [_m data] data) "watch-epochs" {}))]
        (is (= pair-reason (:reason structured))
            "the two servers MUST agree on the :rf.mcp/cursor-stale reason value")))))

(def ^:private story-mcp-cursor-source
  "story-mcp's cursor source — the builder for the Docs `list-*`
  pagination surface (rf2-2js41 finding 1). It documents the cross-MCP
  `:rf.mcp/cursor-stale` reason and routes through the shared
  `mcp-base/cursor.cljc/cursor-stale-result` (sourcing `:reason` from
  the vocab symbol, not the literal). Pinned in the cursor-stale source
  sweep so a story-mcp-side vocabulary drift (a near-miss spelling, a
  dropped reason) trips this cross-MCP gate, not only story-mcp's local
  unit tests."
  "tools/story-mcp/src/re_frame/story_mcp/tools/cursor.cljc")

(def ^:private cursor-stale-near-miss-source-files
  "The full set of conformance-tracked source/spec files the cursor-stale
  near-miss anti-pin sweeps (rf2-2js41 finding 1). The base
  `rf.mcp-conformance.wire-vocab.source-pins/all-source-files` covers the shared vocab declaration + pair-mcp
  specs; cursor-stale ALSO rides story-mcp's own cursor source, so that
  file is folded into story-mcp's sweep here. A near-miss spelling
  introduced on EITHER server's cursor surface now trips the gate."
  (update rf.mcp-conformance.wire-vocab.source-pins/all-source-files :story-mcp (fnil conj []) story-mcp-cursor-source))

(deftest cursor-stale-literal-in-re-frame2-pair-mcp-emit-source
  ;; The canonical declaration lives in mcp-base/vocab.cljc — same
  ;; emit-source as the wrapper markers. Stripped before grep so a
  ;; rename trips the gate even if the old name still appears in a
  ;; docstring.
  (let [literal "\":rf.mcp/cursor-stale\""
        ;; Quoted because pr-str on the keyword renders it without the
        ;; quotes — we want to match the literal token in source code.
        literal (subs literal 1 (dec (count literal)))
        rel     "tools/mcp-base/src/re_frame/mcp_base/vocab.cljc"
        stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings (rf.mcp-conformance.fixtures/read-source rel))]
    (is (str/includes? stripped literal)
        (str literal " missing from " rel
             " AFTER stripping docstrings/comments. The canonical "
             "declaration moved — update this test or restore the "
             "literal."))))

(deftest cursor-stale-literal-in-re-frame2-pair-mcp-doc-sources
  ;; Doc-source pin — looser, raw includes? against the prose docs
  ;; that catalogue pagination semantics.
  (let [literal ":rf.mcp/cursor-stale"
        files   (get rf.mcp-conformance.wire-vocab.source-pins/doc-source-files :re-frame2-pair-mcp)]
    (is (some (fn [rel] (str/includes? (rf.mcp-conformance.fixtures/read-source rel) literal)) files)
        (str literal " missing from re-frame2-pair-mcp doc-sources " files))))

(deftest cursor-stale-literal-in-story-mcp-cursor-source
  ;; story-mcp source pin (rf2-2js41 finding 1). cursor-stale is a
  ;; multi-server reason value; story-mcp's cursor source MUST reference
  ;; the canonical `:rf.mcp/cursor-stale` literal (it documents the
  ;; shared recovery vocabulary on the builder that routes through
  ;; `mcp-base/cursor.cljc`). Looser raw `str/includes?` — story-mcp
  ;; sources the reason from the vocab SYMBOL (not the literal as data),
  ;; so the literal lives in the docstring; a drop here means story-mcp
  ;; stopped documenting the cross-MCP reason it emits.
  (let [literal ":rf.mcp/cursor-stale"]
    (is (str/includes? (rf.mcp-conformance.fixtures/read-source story-mcp-cursor-source) literal)
        (str literal " missing from story-mcp cursor source "
             story-mcp-cursor-source
             ". story-mcp emits this cross-MCP reason via the shared "
             "mcp-base builder; its cursor source must reference the "
             "canonical literal."))))

(deftest cursor-stale-no-near-miss-in-any-server-source
  ;; Defence-in-depth: a rename to a near-miss form (snake_case,
  ;; pluralised, predicate `?` suffix) MUST NOT co-exist anywhere in
  ;; the conformance-tracked source/spec tree. Mirrors the marker-key
  ;; near-miss anti-pin. The sweep set is extended with story-mcp's
  ;; cursor source (rf2-2js41 finding 1) so a near-miss introduced on the
  ;; SECOND server's pagination surface trips here too.
  (doseq [variant (rf.mcp-conformance.wire-vocab.source-pins/near-miss-variants :rf.mcp/cursor-stale)
          [server files] cursor-stale-near-miss-source-files
          rel files]
    (testing (str server " — " rel " — near-miss " variant)
      (is (not (str/includes? (rf.mcp-conformance.fixtures/read-source rel) variant))
          (str "Found near-miss variant " variant
               " for :rf.mcp/cursor-stale in " server "/" rel
               " — vocabulary-drift bug.")))))
