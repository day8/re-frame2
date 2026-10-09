(ns re-frame.story-mcp.protocol-test
  "Wire-format tests for the MCP JSON-RPC protocol layer: the response and
  error envelopes, and frame reading over an in-memory reader (blank lines,
  EOF). The dispatcher and the tools are covered by tools_test.clj."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]
            [re-frame.story-mcp.protocol :as rf.story-mcp.protocol]))

(deftest response-envelope-shape
  (is (= {:jsonrpc "2.0" :id 7 :result {:hello "world"}}
         (rf.story-mcp.protocol/response 7 {:hello "world"}))
      "a success response carries no :error key"))

(deftest error-envelope-shape
  (is (= {:jsonrpc "2.0" :id 42
          :error   {:code rf.mcp-base.vocab/code-method-not-found :message "no such tool"}}
         (rf.story-mcp.protocol/error-response 42 rf.mcp-base.vocab/code-method-not-found "no such tool"))
      "no :data key when none is supplied")
  (is (= {:code rf.mcp-base.vocab/code-internal-error :message "boom" :data {:trace "abc"}}
         (:error (rf.story-mcp.protocol/internal-error 3 "boom" {:trace "abc"}))))
  (is (= [nil rf.mcp-base.vocab/code-parse-error]
         ((juxt :id (comp :code :error)) (rf.story-mcp.protocol/parse-error)))
      "a parse error carries a null id"))

(defn- reader-of [^String s]
  (java.io.BufferedReader. (java.io.StringReader. s)))

(deftest read-frame-skips-blank-lines
  (is (= {:jsonrpc "2.0" :method "ping" :id 1}
         (rf.story-mcp.protocol/read-frame
           (reader-of "\n\n{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}\n")))))

(deftest read-frame-eof-sentinel
  ;; A broken sentinel hangs every run-loop test rather than failing one.
  (is (= rf.story-mcp.protocol/eof-sentinel (rf.story-mcp.protocol/read-frame (reader-of "")))))
