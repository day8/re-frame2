(ns re-frame.util-json-test
  "Unit tests for `re-frame.util-json` on JVM (Cheshire path).

  Beads:
   - rf2-wu1n5 — JSON keyword-interning DoS: cap on unique keys decoded
                 (configurable via `:max-decoded-keys` option per call).
                 Overflow throws `:rf.error/id :rf.error/malformed-json`
                 with `:cause :too-many-keys`, which the
                 `:rf.http/managed` cascade classifies as
                 `:rf.http/decode-failure`.
   - rf2-dgsu1 — Cheshire is a hard JVM dep (no fallback reader). Native
                 Cheshire `JsonParseException`s propagate to the transport
                 catch site, which classifies them as
                 `:rf.http/decode-failure`. Earlier `rf2-263km` covered
                 the hand-rolled fallback reader's bounds-checking; with
                 the fallback removed those manual-parser regressions
                 are moot — Cheshire is RFC-8259-conforming and bulletproof
                 around `\\uXXXX` escapes by construction."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.util-json :as util-json]))

;; ---- rf2-wu1n5 — keyword-interning cap -----------------------------------

(defn- big-json [n-keys]
  ;; Build `{"k0": 0, "k1": 1, ..., "kN-1": N-1}` — N unique keys exactly.
  (str "{"
       (clojure.string/join
         ","
         (for [i (range n-keys)]
           (str "\"k" i "\":" i)))
       "}"))

(deftest cheshire-branch-respects-keyword-cap
  (testing "rf2-wu1n5 — Cheshire branch caps unique-key cardinality"
    (let [s (big-json 50)]
      ;; Under the cap → success.
      (is (map? (util-json/json-parse s {:max-decoded-keys 100}))
          "50 keys under cap=100 should succeed")
      ;; At the cap exactly → success (50 unique strings ≤ 50).
      (is (map? (util-json/json-parse s {:max-decoded-keys 50}))
          "50 keys at cap=50 should succeed")
      ;; Over the cap → throws tagged ex-info.
      (let [thrown (try (util-json/json-parse s {:max-decoded-keys 10}) ::no-throw
                        (catch clojure.lang.ExceptionInfo e e))
            data   (when (instance? clojure.lang.ExceptionInfo thrown)
                     (ex-data thrown))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            "expected ex-info when key-count exceeds cap")
        (is (= :rf.error/malformed-json (:rf.error/id data)))
        (is (= :too-many-keys (:cause data)))
        (is (string? (:reason data))
            ":reason is a human-readable sentence (canonical shape)")
        (is (= 10 (:limit data)))))))

(deftest default-cap-enforced-at-default-max
  (testing "rf2-wu1n5 — default cap (`default-max-decoded-keys`) fires
  when no opts supplied."
    ;; Sanity: the documented constant is what we say it is.
    (is (= 10000 util-json/default-max-decoded-keys))
    ;; A payload one-over the documented default trips the cap when no
    ;; per-call override is supplied.
    (let [s (big-json (inc util-json/default-max-decoded-keys))
          thrown (try (util-json/json-parse s) ::no-throw
                      (catch clojure.lang.ExceptionInfo e e))
          data   (when (instance? clojure.lang.ExceptionInfo thrown)
                   (ex-data thrown))]
      (is (instance? clojure.lang.ExceptionInfo thrown)
          "10001-key payload with no opts must trip the default cap")
      (is (= :rf.error/malformed-json (:rf.error/id data)))
      (is (= :too-many-keys (:cause data)))
      (is (= util-json/default-max-decoded-keys (:limit data))))))

(deftest cap-counts-unique-not-total
  (testing "rf2-wu1n5 — repeated keys don't multiply the count"
    ;; 1000 entries but only 5 unique key strings: {\"a\":1,\"a\":2,...}
    (let [pairs (clojure.string/join "," (repeat 200 "\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5"))
          s     (str "{" pairs "}")]
      (is (map? (util-json/json-parse s {:max-decoded-keys 10}))
          "5 unique keys under cap=10 should succeed even with 1000 entries"))))

;; ---- rf2-dgsu1 — Cheshire is mandatory; malformed JSON propagates --------

(deftest cheshire-handles-well-formed-unicode-escape
  (testing "rf2-dgsu1 — Cheshire (the now-mandatory JVM JSON dep) parses
  `\\uXXXX` escapes correctly. The previous hand-rolled fallback's
  `\\uXXXX` bounds-checking (rf2-263km) is moot — Cheshire is
  RFC-8259-conforming."
    (is (= "A"   (util-json/json-parse "\"\\u0041\"")))
    (is (= "AB"  (util-json/json-parse "\"\\u0041\\u0042\"")))
    (is (= "café" (util-json/json-parse "\"caf\\u00e9\"")))))

(deftest cheshire-rejects-malformed-input-cleanly
  (testing "rf2-dgsu1 — malformed JSON (truncated escape, unterminated
  string, invalid token) raises a Cheshire/Jackson `JsonParseException`.
  The `:rf.http/managed` cascade's `decode-response-body` catch site
  surfaces this as `:rf.http/decode-failure` with the parser message at
  `:cause` — no per-test fixture needed; the contract is simply 'parse
  errors throw'. Earlier the hand-rolled fallback masked malformed
  input behind a custom `:rf.error/malformed-json` ex-info; that
  layering is no longer necessary."
    ;; Inputs Cheshire/Jackson rejects with a parse exception. (Jackson
    ;; is tolerant of some shapes by design — trailing-comma arrays and
    ;; missing-close-brace objects fall through to its end-of-stream
    ;; handler rather than throwing — so we pick inputs that are
    ;; definitively malformed: truncated escapes, unterminated string
    ;; literals, and invalid tokens.)
    (doseq [s ["{not-a-key:1}"      ; bareword key
               "\"unterminated"     ; unterminated string
               "{\"x\":\"\\u\"}"   ; truncated unicode escape
               "{\"x\":\"\\uZZ"   ; invalid unicode hex
               "tru"               ; truncated `true`
               "{\"x\":nul}"]]      ; misspelt null
      (let [thrown (try (util-json/json-parse s) ::no-throw
                        (catch Exception e e))]
        (is (instance? Exception thrown)
            (str "expected a parse exception for " (pr-str s)
                 " — got " thrown))))))

(deftest json-stringify-uses-cheshire
  (testing "rf2-dgsu1 — `json-stringify` produces real JSON via Cheshire
  (not `pr-str`). The earlier shape fell back to `pr-str` when
  Cheshire was absent; with Cheshire mandatory the output is always
  valid JSON."
    ;; Cheshire emits standard JSON: keys quoted, strings double-quoted,
    ;; no edn-isms.
    (is (= "{\"a\":1,\"b\":\"hello\"}"
           (util-json/json-stringify {:a 1 :b "hello"})))
    (is (= "[1,2,3]" (util-json/json-stringify [1 2 3])))
    (is (= "true" (util-json/json-stringify true)))
    (is (= "null" (util-json/json-stringify nil)))))

;; ---- rf2-mih7n — non-string / empty input coverage -----------------------
;;
;; The JVM `json-parse` body opens with `(when (string? s) ...)`. That
;; guard means a non-string `s` (nil, keyword, number, map, vector)
;; returns nil cleanly rather than throwing — protecting the
;; `:rf.http/managed` decode pipeline from a programmer error in the
;; transport layer.
;;
;; The empty-string case `""` flows through Cheshire's `parse-string`
;; which returns nil (Jackson's end-of-stream → nil for an empty
;; document). A future Cheshire upgrade that changes that behaviour
;; would surface here.
;;
;; Truthful-malformed inputs are covered above in
;; `cheshire-rejects-malformed-input-cleanly` — those throw. This
;; deftest is the no-throw counterpart that pins the
;; "input simply has no JSON value to surface → return nil" path.

(deftest jvm-json-parse-non-string-and-empty-return-nil
  (testing "rf2-mih7n — `json-parse` returns nil (not a throw) for
  non-string inputs and the empty string. The JVM body's `(when
  (string? s) ...)` guard protects the managed-HTTP decode pipeline
  from a programmer error in transport — a malformed input throws
  through to `:rf.http/decode-failure`, a missing input is benign nil."
    (is (nil? (util-json/json-parse nil))
        "nil input → nil (string? guard short-circuits)")
    (is (nil? (util-json/json-parse ""))
        "empty string → nil (Cheshire's end-of-stream → nil)")
    (is (nil? (util-json/json-parse :keyword))
        "keyword input → nil (string? guard short-circuits)")
    (is (nil? (util-json/json-parse 42))
        "number input → nil (string? guard short-circuits)")
    (is (nil? (util-json/json-parse {:already :clojure}))
        "map input → nil (string? guard short-circuits — caller
         passed an already-parsed value by mistake)")
    (is (nil? (util-json/json-parse [1 2 3]))
        "vector input → nil (same)")))

(deftest jvm-json-parse-whitespace-only-returns-nil
  (testing "rf2-mih7n — whitespace-only inputs (\" \", \"\\n\", \"\\t\")
  are not valid JSON documents per RFC 8259, but Cheshire/Jackson
  surfaces them as end-of-stream → nil (same as the empty-string
  case). Pin the contract."
    (is (nil? (util-json/json-parse "   ")))
    (is (nil? (util-json/json-parse "\n")))
    (is (nil? (util-json/json-parse "\t")))))
