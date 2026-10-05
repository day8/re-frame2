(ns re-frame.story-mcp.protocol-test
  "Wire-format tests for the MCP JSON-RPC protocol layer.

  Covers:
   - Envelope validation
   - Frame reading over an in-memory reader (blank lines, EOF)
   - Error-response shapes (the generic envelope, parse-error,
     internal-error; the dispatcher tests in tools_test.clj pin
     method-not-found and invalid-params)
   - Notification vs request discrimination

  The wire layer is testable without booting Story's registrar; the
  dispatcher and tool implementations are
  separate (tools_test.clj covers those)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]
            [re-frame.story-mcp.protocol :as rf.story-mcp.protocol]))

;; ---- envelope construction -----------------------------------------------

(deftest response-envelope-shape
  (testing "success response carries jsonrpc/id/result"
    (let [r (rf.story-mcp.protocol/response 7 {:hello "world"})]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 7 (:id r)))
      (is (= {:hello "world"} (:result r)))
      (is (not (contains? r :error))))))

(deftest error-envelope-shape
  (testing "error response carries jsonrpc/id/error"
    (let [e (rf.story-mcp.protocol/error-response 42 rf.mcp-base.vocab/code-method-not-found "no such tool")]
      (is (= "2.0" (:jsonrpc e)))
      (is (= 42 (:id e)))
      (is (= rf.mcp-base.vocab/code-method-not-found (-> e :error :code)))
      (is (= "no such tool" (-> e :error :message)))
      (is (not (contains? (:error e) :data)))))
  (testing "internal-error carries its code and optional :data"
    (let [e (rf.story-mcp.protocol/internal-error 3 "boom" {:trace "abc"})]
      (is (= rf.mcp-base.vocab/code-internal-error (-> e :error :code)))
      (is (= {:trace "abc"} (-> e :error :data)))))
  (testing "id may be nil (parse-error before id is known)"
    (let [e (rf.story-mcp.protocol/parse-error)]
      (is (nil? (:id e)))
      (is (= rf.mcp-base.vocab/code-parse-error (-> e :error :code))))))

;; ---- envelope validation -------------------------------------------------

(deftest envelope-validity
  (testing "request shape"
    (is (rf.story-mcp.protocol/valid-envelope? {:jsonrpc "2.0" :method "tools/list" :id 1}))
    (is (not (rf.story-mcp.protocol/notification? {:jsonrpc "2.0" :method "tools/list" :id 1}))
        "a message carrying :id is a request, not a notification"))
  (testing "missing id → notification"
    (is (rf.story-mcp.protocol/notification? {:jsonrpc "2.0" :method "x"}))
    (is (rf.story-mcp.protocol/valid-envelope? {:jsonrpc "2.0" :method "x"})))
  (testing "missing jsonrpc version → invalid"
    (is (not (rf.story-mcp.protocol/valid-envelope? {:method "x" :id 1}))))
  (testing "wrong jsonrpc version → invalid"
    (is (not (rf.story-mcp.protocol/valid-envelope? {:jsonrpc "1.0" :method "x" :id 1}))))
  (testing "missing method → invalid"
    (is (not (rf.story-mcp.protocol/valid-envelope? {:jsonrpc "2.0" :id 1}))))
  (testing "blank method → invalid"
    (is (not (rf.story-mcp.protocol/valid-envelope? {:jsonrpc "2.0" :method "" :id 1})))))

;; ---- frame I/O -----------------------------------------------------------

(defn- reader-of
  "Build a BufferedReader over a string literal — used to drive
  `read-frame` in tests without touching stdin."
  [^String s]
  (java.io.BufferedReader. (java.io.StringReader. s)))

(deftest read-frame-skips-blank-lines
  (testing "blank lines between frames are silently consumed"
    (let [r (reader-of "\n\n{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}\n")
          f (rf.story-mcp.protocol/read-frame r)]
      (is (= {:jsonrpc "2.0" :method "ping" :id 1} f)))))

(deftest read-frame-eof-sentinel
  (testing "EOF returns rf.story-mcp.protocol/eof-sentinel"
    (let [r (reader-of "")]
      (is (= rf.story-mcp.protocol/eof-sentinel (rf.story-mcp.protocol/read-frame r))))))
