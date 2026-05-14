(ns re-frame.util-json-test
  "Unit tests for `re-frame.util-json`.

  Targets the JVM-only fallback reader (`json-read*` reached when
  Cheshire is absent) and the keyword-cap guard rails on both readers
  (the Cheshire branch and the fallback). The Cheshire branch is
  exercised through `json-parse` directly; the fallback reader is
  exercised by binding the resolved Cheshire vars to nil for the
  duration of the test (see `with-fallback-reader`).

  Beads:
   - rf2-263km — truncated `\\uXXXX` escape near EOF surfaces a clean
                 `:rf.error/malformed-json :reason :truncated-unicode-escape`
                 (was: `StringIndexOutOfBoundsException`).
   - rf2-wu1n5 — JSON keyword-interning DoS: cap on unique keys decoded
                 (configurable via `re-frame.util-json/*max-decoded-keys*`).
                 Overflow throws `:rf.http/decode-failure :reason :too-many-keys`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.util-json :as util-json]))

(defmacro with-fallback-reader
  "Force `json-parse` to take the pure-Clojure fallback path by binding
  the resolved Cheshire vars (held in delays) to a stubbed pair whose
  parse-string is nil. Restores after the body."
  [& body]
  `(let [orig-parse#    @#'re-frame.util-json/cheshire-parse-string
         orig-generate# @#'re-frame.util-json/cheshire-generate-string]
     (try
       ;; Replace the private delays with delays whose deref returns nil
       ;; so `json-parse`'s `(some? @cheshire-parse-string)` branch fails
       ;; and the fallback reader runs.
       (alter-var-root #'re-frame.util-json/cheshire-parse-string
                       (constantly (delay nil)))
       ~@body
       (finally
         (alter-var-root #'re-frame.util-json/cheshire-parse-string
                         (constantly orig-parse#))
         (alter-var-root #'re-frame.util-json/cheshire-generate-string
                         (constantly orig-generate#))))))

;; ---- rf2-263km — truncated unicode escape --------------------------------

(deftest fallback-reader-truncated-unicode-escape-throws-cleanly
  (testing "rf2-263km — a `\\u` escape that runs past EOF throws a clean
  `:rf.error/malformed-json :reason :truncated-unicode-escape`
  ex-info rather than StringIndexOutOfBoundsException"
    (with-fallback-reader
      ;; Inputs whose 4-char hex window runs PAST the end-of-string: the
      ;; raw window is `(subs s (+ p 2) (+ p 6))`, which is the SIOOBE
      ;; surface absent the bounds check.
      (doseq [s ["\"x\\u\""        ; window 4..8, n=5 — overflow by 3
                 "\"x\\u1\""        ; window 4..8, n=6 — overflow by 2
                 "\"x\\u12\""]]     ; window 4..8, n=7 — overflow by 1
        (let [thrown (try (util-json/json-parse s) ::no-throw
                          (catch clojure.lang.ExceptionInfo e e))
              data   (when (instance? clojure.lang.ExceptionInfo thrown)
                       (ex-data thrown))]
          (is (instance? clojure.lang.ExceptionInfo thrown)
              (str "expected ex-info for input " (pr-str s) " — got " thrown))
          (is (= :rf.error/malformed-json (:kind data))
              (str "input " (pr-str s) " — wrong :kind"))
          (is (= :truncated-unicode-escape (:reason data))
              (str "input " (pr-str s) " — wrong :reason")))))))

(deftest fallback-reader-invalid-unicode-escape-throws-cleanly
  (testing "rf2-263km — a `\\u` escape whose 4-char window IS in-bounds but
  contains non-hex characters (e.g. the closing quote inside the window)
  throws `:rf.error/malformed-json :reason :invalid-unicode-escape`
  rather than NumberFormatException"
    (with-fallback-reader
      (doseq [s ["\"x\\u123\""      ; window 4..8, hex = `123"` — bad
                 "\"\\uXYZ0\""      ; window 3..7, hex = `XYZ0` — bad
                 "\"\\u123g\""]]    ; window 3..7, hex = `123g` — bad
        (let [thrown (try (util-json/json-parse s) ::no-throw
                          (catch clojure.lang.ExceptionInfo e e))
              data   (when (instance? clojure.lang.ExceptionInfo thrown)
                       (ex-data thrown))]
          (is (instance? clojure.lang.ExceptionInfo thrown)
              (str "expected ex-info for input " (pr-str s) " — got " thrown))
          (is (= :rf.error/malformed-json (:kind data))
              (str "input " (pr-str s) " — wrong :kind"))
          (is (= :invalid-unicode-escape (:reason data))
              (str "input " (pr-str s) " — wrong :reason")))))))

(deftest fallback-reader-full-unicode-escape-still-works
  (testing "rf2-263km — a well-formed `\\uXXXX` escape still parses"
    (with-fallback-reader
      (is (= "A" (util-json/json-parse "\"\\u0041\"")))
      (is (= "AB" (util-json/json-parse "\"\\u0041\\u0042\""))))))

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
        (is (= :rf.error/malformed-json (:kind data)))
        (is (= :too-many-keys (:reason data)))
        (is (= 10 (:limit data)))))))

(deftest fallback-reader-respects-keyword-cap
  (testing "rf2-wu1n5 — pure-Clojure fallback reader caps unique-key cardinality"
    (with-fallback-reader
      (let [s (big-json 50)]
        (is (map? (util-json/json-parse s {:max-decoded-keys 100})))
        (is (map? (util-json/json-parse s {:max-decoded-keys 50})))
        (let [thrown (try (util-json/json-parse s {:max-decoded-keys 10}) ::no-throw
                          (catch clojure.lang.ExceptionInfo e e))
              data   (when (instance? clojure.lang.ExceptionInfo thrown)
                       (ex-data thrown))]
          (is (instance? clojure.lang.ExceptionInfo thrown))
          (is (= :rf.error/malformed-json (:kind data)))
          (is (= :too-many-keys (:reason data)))
          (is (= 10 (:limit data))))))))

(deftest default-cap-enforced-at-default-max
  (testing "rf2-wu1n5 — default cap (`default-max-decoded-keys`) fires
  when no opts supplied. Uses a small synthetic cap by temporarily
  bumping the request opts above the synthetic input size, then back
  below it, to confirm cap arithmetic without building a 10001-key
  string (slow) in the default path."
    ;; Sanity: the documented constant is what we say it is.
    (is (= 10000 util-json/default-max-decoded-keys))
    ;; The interesting regression — a payload one-over the documented
    ;; default trips the cap when no per-call override is supplied.
    ;; We synthesise the over-default payload exactly once and reuse it
    ;; in both branches.
    (let [s (big-json (inc util-json/default-max-decoded-keys))
          thrown (try (util-json/json-parse s) ::no-throw
                      (catch clojure.lang.ExceptionInfo e e))
          data   (when (instance? clojure.lang.ExceptionInfo thrown)
                   (ex-data thrown))]
      (is (instance? clojure.lang.ExceptionInfo thrown)
          "10001-key payload with no opts must trip the default cap")
      (is (= :rf.error/malformed-json (:kind data)))
      (is (= :too-many-keys (:reason data)))
      (is (= util-json/default-max-decoded-keys (:limit data))))))

(deftest cap-counts-unique-not-total
  (testing "rf2-wu1n5 — repeated keys don't multiply the count"
    (with-fallback-reader
      ;; 1000 entries but only 5 unique key strings: {"a":1,"a":2,...}
      (let [pairs (clojure.string/join "," (repeat 200 "\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5"))
            s     (str "{" pairs "}")]
        (is (map? (util-json/json-parse s {:max-decoded-keys 10}))
            "5 unique keys under cap=10 should succeed even with 1000 entries")))))
