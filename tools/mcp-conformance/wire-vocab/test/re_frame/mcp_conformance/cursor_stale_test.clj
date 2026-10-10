(ns re-frame.mcp-conformance.cursor-stale-test
  "`:rf.mcp/cursor-stale` MULTI-server reason-value gate.

  Unlike the wrapper-shaped markers in `schemas/canonical-markers`,
  `:rf.mcp/cursor-stale` rides as the `:reason` value on a generic
  `{:ok? false ...}` error envelope (per `mcp-base/vocab.cljc/
  cursor-stale-reason`). The conformance contract is the keyword
  itself: a rename or pluralisation would silently break every agent
  that pattern-matches on it.

  BOTH MCP servers emit this reason value:
    - re-frame2-pair-mcp — epoch-id rotated out of the bounded ring
      (`tools/cursor.cljs/cursor-stale-result`).
    - story-mcp — the Docs `list-*` registry id-set changed between
      cursor-mint and cursor-deref
      (`tools/story-mcp/.../tools/cursor.cljc/cursor-stale-result`).
  Both delegate to the shared `mcp-base/cursor.cljc/cursor-stale-result`,
  which sources `:reason` from `vocab/cursor-stale-reason` so the two
  emissions stay byte-identical on the reason keyword.

  The pins:
    1. `CursorStaleResult` rejects a success envelope and a drifted
       reason.
    2. LIVE-builder gates drive EACH server's `cursor-stale-result` and
       validate the emission (pair-mcp's data-map directly; story-mcp's
       `:structuredContent` slot), so a rename in `mcp-base/vocab.cljc`
       or a builder that decouples from it turns red here.
    3. the literal appears in re-frame2-pair-mcp's doc-sources."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.schemas :refer [CursorStaleResult]]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]
            ;; story-mcp's cursor-stale builder, so the gate can drive the
            ;; SECOND server's emission live.
            [re-frame.story-mcp.tools.cursor :as rf.story-mcp.tools.cursor]))

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
  ;; An authored fixture and a grep for the literal in `mcp-base/vocab.cljc`
  ;; never observe the BUILDER: one that hardcoded a drifted `:reason`, or
  ;; dropped the `:ok? false` posture, would leave both green.
  ;;
  ;; Each server shapes the wire envelope its own way, so the builder is
  ;; driven with an `error-result` that returns the structured data-map —
  ;; the `:reason` value + `:ok? false` posture this builder owns.
  (let [emitted (rf.mcp-base.cursor/cursor-stale-result
                  (fn [_message data] data)
                  "watch-epochs"
                  {})]
    (is (m/validate CursorStaleResult emitted)
        (str "Live-emitted cursor-stale envelope failed CursorStaleResult "
             "validation:\n" (me/humanize (m/explain CursorStaleResult emitted))))))

(deftest story-cursor-stale-emitted-live-by-canonical-builder
  ;; story-mcp's own `cursor-stale-result` — the builder for the Docs
  ;; `list-*` pagination surface — wraps the shared mcp-base builder in its
  ;; MCP wire envelope via `result/error-result`:
  ;; `{:content [...] :isError true :structuredContent <data-map>}`. An
  ;; agent host reading JSON pattern-matches on `:reason` in the structured
  ;; slot, so that slot is what validates against `CursorStaleResult`.
  (let [emitted    (rf.story-mcp.tools.cursor/cursor-stale-result "list-stories")
        structured (:structuredContent emitted)]
    (testing "story-mcp wraps the reason in an MCP error envelope"
      (is (true? (:isError emitted))
          "story-mcp cursor-stale rides an :isError true MCP result (per result/error-result)"))
    (testing "the structured content validates against canonical CursorStaleResult"
      (is (m/validate CursorStaleResult structured)
          (str "Live-emitted story-mcp cursor-stale :structuredContent failed "
               "CursorStaleResult validation:\n"
               (me/humanize (m/explain CursorStaleResult structured)))))
    (testing "the tool name threads through to the structured slot"
      (is (= "list-stories" (:tool structured))))))

(deftest cursor-stale-literal-in-re-frame2-pair-mcp-doc-sources
  ;; Doc-source pin — looser, raw includes? against the prose docs
  ;; that catalogue pagination semantics.
  (let [literal ":rf.mcp/cursor-stale"
        files   (get rf.mcp-conformance.wire-vocab.source-pins/doc-source-files :re-frame2-pair-mcp)]
    (is (some (fn [rel] (str/includes? (rf.mcp-conformance.fixtures/read-source rel) literal)) files)
        (str literal " missing from re-frame2-pair-mcp doc-sources " files))))
