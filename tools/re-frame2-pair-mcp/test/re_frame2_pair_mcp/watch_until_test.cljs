(ns re-frame2-pair-mcp.watch-until-test
  "Unit tests for the watch-until tool.

  ## `:timeout-ms` is validated, not silently coerced

  THE BUG: `watch-until` accepted only a positive numeric `:timeout-ms`;
  anything else — `\"bogus\"`, `0`, a negative value, a fractional value —
  was SILENTLY rewritten to the 30-second default. A malformed deadline
  could therefore block the MCP call for 30s and hide the caller's bad
  input, unlike the other timeout-aware tools (`tail-build :wait-ms`,
  `eval-cljs` / `dispatch` `:timeout-ms`) which reject bad values via the
  shared `args/parse-timeout-arg` positive-millisecond contract.

  THE FIX: route `:timeout-ms` through `args/parse-timeout-arg` and
  short-circuit a bad value to an honest `{:ok? false :reason
  :invalid-numeric-arg}` `isError` envelope BEFORE the runtime preflight —
  the validation is the first `cond` branch, so a bad value never touches
  the nREPL socket. An ABSENT arg keeps the documented default.

  The validation branch fires ahead of the runtime preflight, so these
  tests use a `fresh-conn` with no live socket: if validation did NOT
  short-circuit, the tool would reach the preflight and fail with a
  different reason, so `:invalid-numeric-arg` is a precise pin on the
  early-exit."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

(defn- fresh-conn []
  (nrepl/make-conn 0 "127.0.0.1"))

;; A well-formed signals + pred pair so the ONLY thing under test is the
;; timeout validation (a missing signals/pred would short-circuit first on
;; its own reason).
(def ^:private good-args
  {:signals "[{:app-db [:upload :status]}]"
   :pred    "{:signal 0 :equals :done}"})

(defn- watch-with-timeout
  "Invoke watch-until with the good signals/pred plus the given
  `:timeout-ms` value. Returns the tool Promise."
  [timeout-ms]
  (watch-until/watch-until-tool
    (fresh-conn)
    (tu/args->js (assoc good-args :timeout-ms timeout-ms))))

(defn- assert-invalid-timeout [r]
  (is (err? r) "a bad :timeout-ms surfaces as :isError true")
  (let [edn (read-edn r)]
    (is (false? (:ok? edn)))
    (is (= :invalid-numeric-arg (:reason edn)))
    (is (= "timeout-ms" (:arg edn))
        "the error names the offending arg")))

;; ---------------------------------------------------------------------------
;; Invalid values — reject, don't coerce.
;; ---------------------------------------------------------------------------

(deftest non-numeric-timeout-ms-rejected
  (async done
    (-> (watch-with-timeout "bogus")
        (.then (fn [r] (assert-invalid-timeout r) (done))))))

(deftest zero-timeout-ms-rejected
  ;; 0 is not a meaningful "wait for no time" sentinel here — the deadline
  ;; must be a positive millisecond integer.
  (async done
    (-> (watch-with-timeout 0)
        (.then (fn [r] (assert-invalid-timeout r) (done))))))

(deftest negative-timeout-ms-rejected
  (async done
    (-> (watch-with-timeout -1)
        (.then (fn [r] (assert-invalid-timeout r) (done))))))

(deftest fractional-timeout-ms-rejected
  ;; A non-integral number is rejected, matching the other timeout tools.
  (async done
    (-> (watch-with-timeout 1500.5)
        (.then (fn [r] (assert-invalid-timeout r) (done))))))

(deftest numeric-string-zero-timeout-ms-rejected
  ;; The MCP host can pass the arg as a string; "0" is still rejected.
  (async done
    (-> (watch-with-timeout "0")
        (.then (fn [r] (assert-invalid-timeout r) (done))))))

;; ---------------------------------------------------------------------------
;; Valid / absent values still flow (the validation only rejects bad input).
;; ---------------------------------------------------------------------------

(deftest valid-timeout-ms-passes-validation
  ;; A well-formed positive timeout must NOT be rejected by the validation
  ;; branch. With no live runtime the call proceeds past validation and
  ;; fails at the preflight — the key point is the reason is NOT
  ;; :invalid-numeric-arg.
  (async done
    (-> (watch-with-timeout 2000)
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not= :invalid-numeric-arg (:reason edn))
                       "a valid :timeout-ms clears the validation gate"))
                 (done))))))

(deftest numeric-string-timeout-ms-accepted-by-validation
  ;; "2000" (a numeric string) is a valid positive integer and must clear
  ;; the validation gate, same as the other timeout tools.
  (async done
    (-> (watch-with-timeout "2000")
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (not= :invalid-numeric-arg (:reason edn))
                       "a numeric-string :timeout-ms is accepted"))
                 (done))))))

;; ---------------------------------------------------------------------------
;; watch-form applies the predicate TO the sample (rf2-ahjbc).
;;
;; THE BUG: the emitted poll form read `(boolean ((<pred-fn>) sample))` —
;; the pred fn was invoked with ZERO args (binding `sample` to undefined)
;; and its boolean result was then itself INVOKED with the sample, a
;; TypeError on every poll. The poll loop's nREPL-hiccup `.catch` swallowed
;; the throw, so every live watch-until timed out with `:last-sample nil`.
;; The live turn-observation conformance witness is the end-to-end gate;
;; this pin makes the emission shape a unit-level regression net.
;; ---------------------------------------------------------------------------

(deftest watch-form-applies-pred-to-sample
  (let [pred-src (record/pred-source {:signal 0 :equals :done})
        form     (watch-until/watch-form [{:app-db [:upload :status]}]
                                         :rf/default pred-src nil)]
    (is (clojure.string/includes? form "(boolean ((fn [sample]")
        "the pred fn literal is applied directly inside the boolean")
    (is (clojure.string/includes? form ") sample))")
        "the sample rides as the pred fn's argument")
    (is (not (clojure.string/includes? form "(boolean (((fn"))
        "no zero-arg pred invocation wraps the fn literal")))
