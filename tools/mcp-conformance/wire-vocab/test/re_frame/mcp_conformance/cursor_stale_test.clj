(ns re-frame.mcp-conformance.cursor-stale-test
  "`:rf.mcp/cursor-stale` reason-value gate.

  The keyword rides as the `:reason` of a `{:ok? false ...}` error
  envelope. Both servers build that envelope through
  `mcp-base/cursor.cljc/cursor-stale-result`, which sources the reason
  from `vocab/cursor-stale-reason`."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.schemas :refer [CursorStaleResult]]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]
            [re-frame.story-mcp.tools.cursor :as rf.story-mcp.tools.cursor]))

(deftest cursor-stale-rejects-non-error-envelopes
  (is (not (m/validate CursorStaleResult
                       {:ok? true :reason :rf.mcp/cursor-stale}))
      "CursorStaleResult MUST reject :ok? true")
  (is (not (m/validate CursorStaleResult
                       {:ok? false :reason :rf.mcp/cursor-stales}))
      "CursorStaleResult MUST reject the pluralised near-miss"))

;; story-mcp's builder wraps the shared mcp-base builder, so this drives the
;; `:reason` and `:ok? false` posture both servers emit.
(deftest story-cursor-stale-emitted-live-by-canonical-builder
  (let [emitted    (rf.story-mcp.tools.cursor/cursor-stale-result "list-stories")
        structured (:structuredContent emitted)]
    (testing "story-mcp wraps the reason in an MCP error envelope"
      (is (true? (:isError emitted))
          "story-mcp cursor-stale rides an :isError true MCP result (per result/error-result)"))
    (testing "the structured content validates against canonical CursorStaleResult"
      (is (m/validate CursorStaleResult structured)
          (str "Live-emitted story-mcp cursor-stale :structuredContent failed "
               "CursorStaleResult validation:\n"
               (me/humanize (m/explain CursorStaleResult structured)))))))

(deftest cursor-stale-literal-in-re-frame2-pair-mcp-doc-sources
  (let [literal ":rf.mcp/cursor-stale"
        files   (get rf.mcp-conformance.wire-vocab.source-pins/doc-source-files :re-frame2-pair-mcp)]
    (is (some (fn [rel] (str/includes? (rf.mcp-conformance.fixtures/read-source rel) literal)) files)
        (str literal " missing from re-frame2-pair-mcp doc-sources " files))))
