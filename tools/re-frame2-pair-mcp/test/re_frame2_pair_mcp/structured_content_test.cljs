(ns re-frame2-pair-mcp.structured-content-test
  "Every result envelope carries the EDN `:content` text and a
  `:structuredContent` projection of the same value. The structured slot is
  always a JSON object — the npm MCP SDK validates it against each tool's
  object-typed outputSchema and rejects null or a primitive — and keeps
  keyword namespaces, which a bare `clj->js` drops, so a name read there can
  be threaded back through `get-path`."
  (:require [cljs.test :refer-macros [deftest is]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(defn- structured-json [result]
  (js->clj (js/JSON.parse (js/JSON.stringify (j/get result :structuredContent)))))

(deftest ok-text-emits-both-slots
  (doseq [[payload json]
          [[{:ok? true :value 42 :rf/runtime {:loaded? true}
             :machine-ids [:door/main :traffic/light] :current :door/open}
            {"ok?" true "value" 42 "rf/runtime" {"loaded?" true}
             "machine-ids" ["door/main" "traffic/light"] "current" "door/open"}]
           ;; An application tag would otherwise serialise its implementation fields.
           [{:at    (tagged-literal 'instant "2026-01-01T00:00:00Z")
             :outer (tagged-literal 'app/outer {:k     :ns/v
                                                :inner (tagged-literal 'app/inner [1 :a/b])})}
            {"at"    {"rf.mcp/tag" "instant" "rf.mcp/form" "2026-01-01T00:00:00Z"}
             "outer" {"rf.mcp/tag"  "app/outer"
                      "rf.mcp/form" {"k"     "ns/v"
                                     "inner" {"rf.mcp/tag" "app/inner" "rf.mcp/form" [1 "a/b"]}}}}]
           [nil {"rf.mcp/null" true}]
           [42 {"rf.mcp/value" 42}]]]
    (let [result (wire/ok-text payload)]
      (is (= (pr-str payload) (tu/extract-text result)) "the text slot is the payload's own EDN")
      (is (= json (structured-json result)) (pr-str payload))
      (is (not (true? (j/get result :isError)))))))

(deftest err-text-emits-both-slots-plus-isError
  (let [payload {:ok? false :reason :sample-error :hint "..."}
        result  (wire/err-text payload)]
    (is (true? (j/get result :isError)))
    (is (= (pr-str payload) (tu/extract-text result)))
    (is (= {"ok?" false "reason" "sample-error" "hint" "..."} (structured-json result)))))

(deftest cache-hit-marker-key-keeps-namespace-in-structured-slot
  ;; The marker is built outside the tool callbacks, so it must route
  ;; through `wire/result` too.
  (let [result (cache/cache-hit-result {:hash 12345 :unchanged-since 1700000000000} "snapshot" :result-hash)]
    (is (some? (j/get-in result [:structuredContent "rf.mcp/cache-hit"])))))
