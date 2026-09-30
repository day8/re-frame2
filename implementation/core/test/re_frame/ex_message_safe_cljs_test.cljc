(ns re-frame.ex-message-safe-cljs-test
  "Nil-safe thrown-value message extractor.

  The runtime catches thrown values at ~7 sites (the router pipeline
  exception, the cofx supplier throw, the fx-handler throw, the reactive /
  compute sub throws, the interceptor-registry arg-resolve throw) and stamps
  the host message into the `:exception-message` slot. The raw
  `#?(:clj (.getMessage e) :cljs (.-message e))` is unsafe in CLJS: ANY value
  is legally throwable, and a thrown NON-Error value (a keyword, a map, a
  string — `(throw :boom)`) has no `.-message` property, so `(.-message e)` is
  `nil` and `:exception-message` silently becomes nil. `rf.error/ex-message-safe`
  is the shared nil-safe extractor those sites route through.

  This gate pins: a host exception's message rides through; a thrown
  non-Error value degrades to a non-nil rendering rather than nil; only a
  genuinely nil input yields nil.

  Dual-runtime: `-cljs-test` rides `npm run test:cljs` (where the
  non-Error-throwable footgun actually exists); the `.cljc` is also
  discovered on the JVM. Pure — no runtime state."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.error :as rf.error]))

(deftest host-exception-message-rides-through
  (testing "a real host exception's message is returned"
    (is (= "boom" (rf.error/ex-message-safe (ex-info "boom" {})))
        "ex-info message extracted")
    (is (= "kaboom"
           (rf.error/ex-message-safe #?(:clj  (RuntimeException. "kaboom")
                                     :cljs (js/Error. "kaboom"))))
        "plain host error message extracted")))

(deftest thrown-non-error-values-render-rather-than-yield-nil
  (testing "a thrown non-Error value (CLJS-legal: `(throw :boom)`) has no host
            message — on CLJS `(.-message :boom)` is nil — so the safe
            extractor falls back to `(str v)`: always a string, and the value
            stays recognisable in it"
    (are [thrown pattern] (let [msg (rf.error/ex-message-safe thrown)]
                            (and (string? msg) (re-find pattern msg)))
      :boom  #"boom"
      {:k 1} #":k"
      "oops" #"oops"
      42     #"^42$")))

(deftest nil-input-yields-nil
  (testing "only a genuinely nil input yields nil (there is no message to
            extract) — the contract's single nil case"
    (is (nil? (rf.error/ex-message-safe nil)))))
