(ns re-frame.story-mcp.protocol-test
  "Wire-format tests for the MCP JSON-RPC protocol layer.

  Covers:
   - JSON parse / encode round-trip
   - Envelope validation
   - Frame I/O over an in-memory reader/writer
   - Error-response shapes (the generic envelope, parse-error,
     internal-error; the dispatcher tests in tools_test.clj pin
     method-not-found and invalid-params)
   - Notification vs request discrimination

  The wire layer is testable without booting Story's registrar; the
  dispatcher and tool implementations are
  separate (tools_test.clj covers those)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

;; ---- JSON parse / encode --------------------------------------------------

(deftest json-parse-keeps-string-keys
  ;; `parse-json` parses with STRING keys (no recursive
  ;; keywordisation). Envelope + known-arg keywordisation happens
  ;; downstream in `normalize-frame` (exercised via `read-frame`). This
  ;; closes the attacker-controlled-nested-key intern surface.
  (testing "parsed map has STRING keys (no recursive keywordise)"
    (let [m (rf.story-mcp.protocol/parse-json "{\"method\":\"tools/list\",\"id\":1}")]
      (is (= "tools/list" (get m "method")))
      (is (= 1 (get m "id")))
      (is (nil? (:method m)) "no keyword key — parse-json is string-keyed"))))

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

(deftest read-frame-propagates-parse-error
  (testing "malformed JSON throws (caller writes parse-error response)"
    (let [r (reader-of "{garbage\n")]
      (try
        (rf.story-mcp.protocol/read-frame r)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/story-mcp-json-parse-failure (:rf.error/id (ex-data e)))))))))

;; NB: `read-frame` is the INBOUND-frame reader — it
;; normalises a request/notification frame and deliberately keywordises
;; ONLY the envelope + the bounded arg-key allowlist (nested `:result`
;; payload keys are NOT walked, since the server never reads its own
;; responses). These write-frame tests therefore deserialise the written
;; RESPONSE with a plain keywordising `json/parse-string` — that is the
;; correct way to read back the server's own output, and it keeps the
;; tests focused on `write-frame!`'s contract (newline + no embedded
;; newlines + faithful serialisation).
(defn- parse-line
  "Read back one written JSON line as a fully-keywordised Clojure map —
  the appropriate deserialiser for the server's OWN response output (not
  the no-intern ingress reader)."
  [^String s]
  (json/parse-string (str/trim s) true))

(deftest write-frame-roundtrips
  (testing "write-frame appends a newline; round-trip via reader"
    (let [sw (java.io.StringWriter.)
          _  (rf.story-mcp.protocol/write-frame! sw {:jsonrpc "2.0" :id 1 :result {:ok true}})
          out (.toString sw)]
      (is (.endsWith out "\n"))
      (is (not (.contains (subs out 0 (dec (count out))) "\n"))
          "no embedded newlines per MCP stdio transport rules")
      (is (= {:jsonrpc "2.0" :id 1 :result {:ok true}}
             (parse-line out))))))
