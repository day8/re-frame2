(ns re-frame.util-json-cljs-test
  "CLJS-side unit coverage for `re-frame.util-json` (rf2-mih7n;
  follow-on from rf2-q1z1u F3).

  Background. The JVM `util_json_test.clj` covers the Cheshire branch
  exhaustively (keyword cap, malformed-input throw shape, non-string
  guard, empty-string fall-through). The CLJS branch is a separate
  reader (`js/JSON.parse`) with materially different malformed-input
  behaviour:

  - `js/JSON.parse('')` throws SyntaxError — does NOT return nil like
    Cheshire does. Empty input is malformed under V8/SpiderMonkey/
    JavaScriptCore. The `:rf.http/managed` decode site catches. NOTE:
    `\"\"` IS a string, so the `(string? s)` guard added per rf2-x1uhu
    does NOT short-circuit it — the empty-string throw is preserved.
  - `js/JSON.parse('{not-json')` throws SyntaxError.
  - non-string input (nil, keyword, number, map, vector) returns nil per
    rf2-x1uhu: the CLJS branch now opens with the same `(when (string? s)
    ...)` guard as the JVM Cheshire branch, so a non-string short-circuits
    to nil on both hosts. Earlier the CLJS path called `js/JSON.parse`
    directly — nil coerced to `\"null\"` → nil, but a keyword/number/map
    THREW where the JVM returned nil. That host asymmetry is the bug
    rf2-x1uhu closed.

  These per-platform differences matter for the `:rf.http/managed`
  cascade: the CLJS decode site MUST classify the SyntaxError throws
  as `:rf.http/decode-failure`. Pin the branch's behaviour directly
  so a future runtime upgrade doesn't silently change the contract.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.util-json :as util-json]))

(deftest cljs-json-parse-malformed-throws
  (testing "rf2-mih7n — malformed JSON inputs throw under
            `js/JSON.parse` on CLJS. The managed-HTTP decode site
            catches and classifies as `:rf.http/decode-failure`."
    (doseq [s ["{not-json"           ; unclosed object, bareword key
               "[1,2,"               ; unterminated array
               "\"unterminated"      ; unterminated string
               "tru"                 ; truncated literal
               "{\"a\":nul}"         ; misspelt null
               "{\"x\":\"\\u\"}"]]   ; truncated unicode escape
      (let [thrown (try (util-json/json-parse s) ::no-throw
                        (catch :default e e))]
        (is (not= ::no-throw thrown)
            (str "expected a parse exception for " (pr-str s)
                 " — got " (pr-str thrown)))))))

(deftest cljs-json-parse-empty-string-throws
  (testing "rf2-mih7n — the empty string is malformed JSON under
            `js/JSON.parse` (DIFFERENT from the JVM Cheshire branch
            which returns nil for end-of-stream). Pinning this
            divergence prevents a refactor that 'normalises' the
            CLJS branch by adding an empty-string short-circuit —
            doing so would mask a transport-layer programmer error
            that the current decode-failure path surfaces."
    (let [thrown (try (util-json/json-parse "") ::no-throw
                      (catch :default e e))]
      (is (not= ::no-throw thrown)
          (str "empty-string input must throw on CLJS — got "
               (pr-str thrown))))))

(deftest cljs-json-parse-non-string-input-returns-nil
  (testing "rf2-x1uhu — non-string inputs return nil (not a throw) on
            CLJS, mirroring the JVM Cheshire branch. The CLJS branch now
            opens with the SAME `(when (string? s) ...)` guard as JVM, so
            both hosts produce nil for nil / keyword / number / map /
            vector input. Earlier the CLJS path called `js/JSON.parse`
            directly: nil coerced to nil, but a keyword/number/map/vector
            THREW where the JVM returned nil — the host asymmetry this
            bead closed."
    (is (nil? (util-json/json-parse nil))
        "nil input → nil (string? guard short-circuits, cross-host)")
    (is (nil? (util-json/json-parse :keyword))
        "keyword input → nil (was a throw before rf2-x1uhu)")
    (is (nil? (util-json/json-parse 42))
        "number input → nil (was a throw before rf2-x1uhu)")
    (is (nil? (util-json/json-parse {:already :clojure}))
        "map input → nil — caller passed an already-parsed value by
         mistake (was a throw before rf2-x1uhu)")
    (is (nil? (util-json/json-parse [1 2 3]))
        "vector input → nil (was a throw before rf2-x1uhu)")))

(deftest cljs-json-stringify-happy-path
  (testing "rf2-mih7n — sanity-check `json-stringify` on CLJS uses
            `js/JSON.stringify` and produces standard JSON output
            (no edn-isms, no host-specific encoding quirks)."
    (is (= "{\"a\":1,\"b\":\"hello\"}"
           (util-json/json-stringify {:a 1 :b "hello"})))
    (is (= "[1,2,3]" (util-json/json-stringify [1 2 3])))
    (is (= "true" (util-json/json-stringify true)))
    (is (= "null" (util-json/json-stringify nil)))))
