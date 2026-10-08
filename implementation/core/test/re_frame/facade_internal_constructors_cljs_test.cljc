(ns re-frame.facade-internal-constructors-cljs-test
  "The implementation-only lowering constructors (`make-capture-frame`,
  `->interceptor*`) are off the `re-frame.core` facade on both platforms; they
  live in their owning namespaces. Each absence probe is paired with a presence
  probe through the same instrument, so a probe that answers nil for the wrong
  reason cannot read as a clean removal."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]))

#?(:clj
   (defn- facade-var
     "The `re-frame.core` var named `sym`, or nil. `ns-resolve` follows
     `^:no-doc` and private vars too, so nil means ABSENT, not hidden."
     [sym]
     (ns-resolve 're-frame.core sym)))

#?(:cljs
   (defn- facade-runtime-var
     "The compiled `re-frame.core` runtime property `munged-name`, or nil.
     A symbol reference would not compile against an absent var, so the
     probe reads the emitted namespace object by name instead."
     [munged-name]
     (js/goog.getObjectByName (str "re_frame.core." munged-name))))

(deftest facade-no-longer-resolves-the-lowering-constructors
  (testing "positive control — the probe finds the public carry primitive"
    (is (some? #?(:clj  (facade-var 'capture-frame)
                  :cljs (facade-runtime-var "capture_frame")))
        "re-frame.core/capture-frame resolves (the instrument works)"))
  (testing "make-capture-frame is off the facade"
    (is (nil? #?(:clj  (facade-var 'make-capture-frame)
                 :cljs (facade-runtime-var "make_capture_frame")))
        "re-frame.core/make-capture-frame does not resolve"))
  (testing "->interceptor* is off the facade"
    (is (nil? #?(:clj  (facade-var '->interceptor*)
                 :cljs (facade-runtime-var "_GT_interceptor_STAR_")))
        "re-frame.core/->interceptor* does not resolve"))
  #?(:clj
     (testing "there is no ->interceptor macro (a macro would be JVM-only;
               CLJS carries no runtime var for one)"
       (is (nil? (facade-var '->interceptor))
           "re-frame.core/->interceptor does not resolve"))))

(deftest capture-frame-remains-the-supported-carry-primitive
  (let [handle (rf/capture-frame :rf/default)]
    (is (= #{:frame :dispatch :dispatch-sync :subscribe} (set (keys handle))))
    (is (= :rf/default (:frame handle)))
    (is (every? fn? (map handle [:dispatch :dispatch-sync :subscribe])))))
