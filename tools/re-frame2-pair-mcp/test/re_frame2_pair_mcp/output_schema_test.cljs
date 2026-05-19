(ns re-frame2-pair-mcp.output-schema-test
  "Pin rf2-3l3be — every MCP tool descriptor MUST carry an
  `:outputSchema` map describing its `structuredContent` payload
  shape. mcp-builder canonical pattern: 'Define outputSchema wherever
  possible for structured responses.'

  The slot rides next to `:inputSchema` in the static descriptor
  data and projects to the wire via `tool-descriptors-js` (which is
  a `clj->js` of the catalogue)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools :as tools]))

(deftest every-descriptor-carries-output-schema
  (testing "tool-descriptors: every entry has a map-shaped :outputSchema"
    (doseq [d tools/tool-descriptors]
      (is (map? (:outputSchema d))
          (str "missing :outputSchema on " (:name d))))))

(deftest output-schema-surfaces-on-tools-list
  (testing "tool-descriptors-js projects :outputSchema to the wire"
    (let [arr (tools/tool-descriptors-js)
          n   (alength arr)]
      (is (pos? n) "at least one descriptor exists")
      (doseq [i (range n)]
        (let [desc (aget arr i)
              os   (j/get desc :outputSchema)
              name (j/get desc :name)]
          (is (some? os)
              (str "missing outputSchema on tool " name))
          (is (object? os)
              (str "outputSchema on tool " name " is not a JS object")))))))
