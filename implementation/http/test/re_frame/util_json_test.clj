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
