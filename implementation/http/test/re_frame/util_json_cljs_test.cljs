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
    JavaScriptCore. The `:rf.http/managed` decode site catches.
  - `js/JSON.parse('{not-json')` throws SyntaxError.
  - `js/JSON.parse(nil)` is `JSON.parse('null')` (JS coercion) →
    returns `null` (JS) → `(js->clj nil :keywordize-keys true)` is nil.
    Counter-intuitive but consistent with the JS spec.

  These per-platform differences matter for the `:rf.http/managed`
  cascade: the CLJS decode site MUST classify the SyntaxError throws
  as `:rf.http/decode-failure`. Pin the branch's behaviour directly
  so a future runtime upgrade (or a refactor that adds a string-guard
  wrapper around the CLJS branch) doesn't silently change the contract.

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

(deftest cljs-json-parse-nil-input-returns-nil
  (testing "rf2-mih7n — `(util-json/json-parse nil)` returns nil on
            CLJS. The JS engine coerces `nil` → `\"null\"` →
            `js/JSON.parse` returns `null` → `js->clj` produces nil.
            Counter-intuitive but spec-consistent. The JVM branch
            short-circuits via `(when (string? s) ...)` and also
            returns nil — the two branches produce the SAME observable
            result for a nil input despite taking very different
            paths."
    (is (nil? (util-json/json-parse nil))
        "nil input → nil (cross-platform consistent for this slot)")))

(deftest cljs-json-stringify-happy-path
  (testing "rf2-mih7n — sanity-check `json-stringify` on CLJS uses
            `js/JSON.stringify` and produces standard JSON output
            (no edn-isms, no host-specific encoding quirks)."
    (is (= "{\"a\":1,\"b\":\"hello\"}"
           (util-json/json-stringify {:a 1 :b "hello"})))
    (is (= "[1,2,3]" (util-json/json-stringify [1 2 3])))
    (is (= "true" (util-json/json-stringify true)))
    (is (= "null" (util-json/json-stringify nil)))))
