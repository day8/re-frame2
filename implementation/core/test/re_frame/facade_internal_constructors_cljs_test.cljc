(ns re-frame.facade-internal-constructors-cljs-test
  "rf2-93sxp — the implementation-only lowering constructors are OFF the
  `re-frame.core` facade, on both platforms.

  `make-capture-frame`, `->interceptor` and `->interceptor*` were exported
  from `re-frame.core` only so a macro expansion could name them
  fully-qualified. Their manifest rows read `:tier :implementation` (or
  \"internal lowering only\") while still carrying `:facade? true` — the
  annotation-as-removal failure spec/Conventions.md §Removing or demoting a
  facade export names. The lowering seams now live in their owning
  namespaces (`re-frame.capture-frame/make-capture-frame`,
  `re-frame.interceptor/->interceptor*`); the `->interceptor` macro is gone
  outright (no library caller — the owning constructor suffices, and
  `reg-interceptor` is the authoring form); the facade resolves none of the
  three.

  Every absence probe is paired with a presence probe through the SAME
  instrument (`ns-resolve` on the JVM, `goog.getObjectByName` on CLJS), so a
  probe that answers nil for the wrong reason cannot read as a clean
  removal."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]))

#?(:clj
   (defn- facade-var
     "The `re-frame.core` var named `sym`, or nil. `ns-resolve` follows
     `^:no-doc` and private vars too, so nil means GONE, not hidden."
     [sym]
     (ns-resolve 're-frame.core sym)))

#?(:cljs
   (defn- facade-runtime-var
     "The compiled `re-frame.core` runtime property `munged-name`, or nil.
     A symbol reference would not compile once the var is gone, so the
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
        "re-frame.core/make-capture-frame no longer resolves"))
  (testing "->interceptor* is off the facade"
    (is (nil? #?(:clj  (facade-var '->interceptor*)
                 :cljs (facade-runtime-var "_GT_interceptor_STAR_")))
        "re-frame.core/->interceptor* no longer resolves"))
  #?(:clj
     (testing "the ->interceptor macro is gone (JVM-only macro; CLJS never
               carried a runtime var for it)"
       (is (nil? (facade-var '->interceptor))
           "re-frame.core/->interceptor no longer resolves"))))

(deftest capture-frame-remains-the-supported-carry-primitive
  (testing "the 1-arity lock-to-id form still returns the frame api bundle
            (the behavioural contract is pinned in depth by
            re-frame.capture-frame-test; this is the facade-side smoke)"
    (let [handle (rf/capture-frame :rf/default)]
      (is (= #{:frame :dispatch :dispatch-sync :subscribe} (set (keys handle))))
      (is (= :rf/default (:frame handle)))
      (is (fn? (:dispatch handle)))
      (is (fn? (:dispatch-sync handle)))
      (is (fn? (:subscribe handle))))))
