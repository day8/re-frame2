(ns re-frame.ex-message-safe-cljs-test
  "rf2-vzrxp3 — nil-safe thrown-value message extractor.

  The runtime catches thrown values at ~7 sites (the router pipeline
  exception, the cofx supplier throw, the fx-handler throw, the reactive /
  compute sub throws, the interceptor-registry arg-resolve throw) and stamps
  the host message into the `:exception-message` slot. The raw
  `#?(:clj (.getMessage e) :cljs (.-message e))` is unsafe in CLJS: ANY value
  is legally throwable, and a thrown NON-Error value (a keyword, a map, a
  string — `(throw :boom)`) has no `.-message` property, so `(.-message e)` is
  `nil` and `:exception-message` silently becomes nil. `error/ex-message-safe`
  is the shared nil-safe extractor those sites route through.

  This gate pins: a host exception's message rides through; a thrown
  non-Error value degrades to a non-nil rendering rather than nil; only a
  genuinely nil input yields nil.

  Dual-runtime: `-cljs-test` rides `npm run test:cljs` (where the
  non-Error-throwable footgun actually exists); the `.cljc` is also
  discovered on the JVM. Pure — no runtime state."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.error :as error]))

(deftest host-exception-message-rides-through
  (testing "a real host exception's message is returned"
    (is (= "boom" (error/ex-message-safe (ex-info "boom" {})))
        "ex-info message extracted")
    (is (= "kaboom"
           (error/ex-message-safe #?(:clj  (RuntimeException. "kaboom")
                                     :cljs (js/Error. "kaboom"))))
        "plain host error message extracted")))

(deftest thrown-keyword-does-not-yield-nil
  (testing "a thrown keyword (CLJS-legal: `(throw :boom)`) degrades to a
            non-nil rendering rather than nil — the footgun rf2-vzrxp3 closes.
            On CLJS `(.-message :boom)` is nil; the safe extractor falls back
            to `(str :boom)`."
    (let [msg (error/ex-message-safe :boom)]
      (is (some? msg) "a thrown keyword does NOT silently become nil")
      (is (string? msg) "the extractor always returns a string for a non-nil value")
      ;; The keyword renders recognisably (its printed form contains its name).
      (is (re-find #"boom" msg) "the keyword's name is recognisable in the message"))))

(deftest thrown-map-does-not-yield-nil
  (testing "a thrown map (`(throw {:k 1})`) degrades to a non-nil structural
            rendering rather than nil"
    (let [msg (error/ex-message-safe {:k 1})]
      (is (some? msg) "a thrown map does NOT silently become nil")
      (is (string? msg))
      (is (re-find #":k" msg) "the map's structure is recognisable in the message"))))

(deftest thrown-string-does-not-yield-nil
  (testing "a thrown string degrades to itself (its printed form) rather than nil"
    (let [msg (error/ex-message-safe "oops")]
      (is (some? msg))
      (is (re-find #"oops" msg)))))

(deftest thrown-number-does-not-yield-nil
  (testing "a thrown number degrades to its printed form rather than nil"
    (is (= "42" (error/ex-message-safe 42)))))

(deftest nil-input-yields-nil
  (testing "only a genuinely nil input yields nil (there is no message to
            extract) — the contract's single nil case"
    (is (nil? (error/ex-message-safe nil)))))

#?(:cljs
   (deftest cljs-non-error-throwable-is-the-real-footgun
     ;; The CLJS-specific proof: a raw `(.-message e)` on a non-Error value
     ;; reads nil with NO error (the property is simply absent). This pins that
     ;; the safe extractor does NOT regress to that behaviour.
     (testing "raw (.-message v) on a non-Error value is nil — the unsafe path
               the extractor replaces"
       (is (nil? (.-message :boom))
           "a thrown keyword has no .-message (this is WHY the raw read was unsafe)")
       (is (some? (error/ex-message-safe :boom))
           "the safe extractor does not propagate that nil"))))
