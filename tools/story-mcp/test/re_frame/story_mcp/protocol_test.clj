(ns re-frame.story-mcp.protocol-test
  "Wire-format tests for the MCP JSON-RPC protocol layer.

  Covers:
   - JSON parse / encode round-trip
   - Envelope validation
   - Frame I/O over an in-memory reader/writer
   - Error-response shapes (parse-error, method-not-found, invalid-params,
     internal-error)
   - Notification vs request discrimination

  Per IMPL-SPEC §7 Stage 7 the wire layer is testable without booting
  Story's registrar — the dispatcher and tool implementations are
  separate (tools_test.clj covers those)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.vocab :as vocab]
            [re-frame.story-mcp.protocol :as proto]))

;; ---- envelope construction -----------------------------------------------

(deftest response-envelope-shape
  (testing "success response carries jsonrpc/id/result"
    (let [r (proto/response 7 {:hello "world"})]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 7 (:id r)))
      (is (= {:hello "world"} (:result r)))
      (is (not (contains? r :error))))))

(deftest error-envelope-shape
  (testing "error response carries jsonrpc/id/error"
    (let [e (proto/error-response 42 vocab/code-method-not-found "no such tool")]
      (is (= "2.0" (:jsonrpc e)))
      (is (= 42 (:id e)))
      (is (= vocab/code-method-not-found (-> e :error :code)))
      (is (= "no such tool" (-> e :error :message)))
      (is (not (contains? (:error e) :data)))))
  (testing "error response carries optional :data"
    (let [e (proto/error-response 1 vocab/code-invalid-params "bad arg"
                                   {:offending "key"})]
      (is (= {:offending "key"} (-> e :error :data)))))
  (testing "id may be nil (parse-error before id is known)"
    (let [e (proto/parse-error)]
      (is (nil? (:id e)))
      (is (= vocab/code-parse-error (-> e :error :code))))))

;; ---- envelope validation -------------------------------------------------

(deftest envelope-validity
  (testing "request shape"
    (is (proto/request? {:jsonrpc "2.0" :method "tools/list" :id 1})))
  (testing "missing id → notification"
    (is (not (proto/request? {:jsonrpc "2.0" :method "x"})))
    (is (proto/notification? {:jsonrpc "2.0" :method "x"}))
    (is (proto/valid-envelope? {:jsonrpc "2.0" :method "x"})))
  (testing "missing jsonrpc version → invalid"
    (is (not (proto/valid-envelope? {:method "x" :id 1}))))
  (testing "wrong jsonrpc version → invalid"
    (is (not (proto/valid-envelope? {:jsonrpc "1.0" :method "x" :id 1}))))
  (testing "missing method → invalid"
    (is (not (proto/valid-envelope? {:jsonrpc "2.0" :id 1}))))
  (testing "blank method → invalid"
    (is (not (proto/valid-envelope? {:jsonrpc "2.0" :method "" :id 1})))))

;; ---- JSON parse / encode --------------------------------------------------

(deftest json-parse-keywordises-keys
  (testing "parsed map has keyword keys"
    (let [m (proto/parse-json "{\"method\":\"tools/list\",\"id\":1}")]
      (is (= "tools/list" (:method m)))
      (is (= 1 (:id m))))))

(deftest json-parse-throws-on-malformed
  (testing "malformed JSON throws ex-info with rf.error/parse-error"
    (try
      (proto/parse-json "{not json")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/parse-error (:rf.error (ex-data e))))))))

(deftest json-encode-roundtrip
  (testing "encode then decode yields the same map (keywordised)"
    (let [m {:jsonrpc "2.0" :id 1 :result {:foo "bar"}}
          s (proto/write-json m)
          back (json/parse-string s true)]
      (is (= m back)))))

;; ---- frame I/O -----------------------------------------------------------

(defn- reader-of
  "Build a BufferedReader over a string literal — used to drive
  `read-frame` in tests without touching stdin."
  [^String s]
  (java.io.BufferedReader. (java.io.StringReader. s)))

(deftest read-frame-parses-one-line
  (testing "single-line frame"
    (let [r (reader-of "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":3}\n")
          f (proto/read-frame r)]
      (is (= {:jsonrpc "2.0" :method "ping" :id 3} f)))))

(deftest read-frame-skips-blank-lines
  (testing "blank lines between frames are silently consumed"
    (let [r (reader-of "\n\n{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}\n")
          f (proto/read-frame r)]
      (is (= {:jsonrpc "2.0" :method "ping" :id 1} f)))))

(deftest read-frame-eof-sentinel
  (testing "EOF returns proto/eof-sentinel"
    (let [r (reader-of "")]
      (is (= proto/eof-sentinel (proto/read-frame r))))))

(deftest read-frame-propagates-parse-error
  (testing "malformed JSON throws (caller writes parse-error response)"
    (let [r (reader-of "{garbage\n")]
      (try
        (proto/read-frame r)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/parse-error (:rf.error (ex-data e)))))))))

(deftest write-frame-roundtrips
  (testing "write-frame appends a newline; round-trip via reader"
    (let [sw (java.io.StringWriter.)
          _  (proto/write-frame! sw {:jsonrpc "2.0" :id 1 :result {:ok true}})
          out (.toString sw)]
      (is (.endsWith out "\n"))
      (is (not (.contains (subs out 0 (dec (count out))) "\n"))
          "no embedded newlines per MCP stdio transport rules")
      (let [r (reader-of out)]
        (is (= {:jsonrpc "2.0" :id 1 :result {:ok true}}
               (proto/read-frame r)))))))

(deftest write-frame-multiple-frames-on-one-stream
  (testing "two frames produce two readable lines"
    (let [sw (java.io.StringWriter.)]
      (proto/write-frame! sw {:jsonrpc "2.0" :id 1 :result {}})
      (proto/write-frame! sw {:jsonrpc "2.0" :id 2 :result {:n 7}})
      (let [r (reader-of (.toString sw))]
        (is (= {:jsonrpc "2.0" :id 1 :result {}}     (proto/read-frame r)))
        (is (= {:jsonrpc "2.0" :id 2 :result {:n 7}} (proto/read-frame r)))
        (is (= proto/eof-sentinel                    (proto/read-frame r)))))))

;; ---- error-helpers --------------------------------------------------------

(deftest method-not-found-includes-method-name
  (testing "method-not-found message names the offending method"
    (let [e (proto/method-not-found 9 "tools/quux")]
      (is (= vocab/code-method-not-found (-> e :error :code)))
      (is (re-find #"tools/quux" (-> e :error :message))))))

(deftest invalid-params-renders-details
  (testing "invalid-params attaches detail string to message"
    (let [e (proto/invalid-params 5 "missing :variant-id")]
      (is (= vocab/code-invalid-params (-> e :error :code)))
      (is (re-find #"missing :variant-id" (-> e :error :message))))))

(deftest internal-error-attaches-data
  (testing "internal-error optional :data lands on the error envelope"
    (let [e (proto/internal-error 3 "boom" {:trace "abc"})]
      (is (= vocab/code-internal-error (-> e :error :code)))
      (is (= {:trace "abc"} (-> e :error :data))))))
