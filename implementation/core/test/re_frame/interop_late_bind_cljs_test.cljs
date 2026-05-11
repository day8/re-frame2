(ns re-frame.interop-late-bind-cljs-test
  "Per rf2-oa9z — coverage for the no-adapter / unset-hook branch of
  every reactive-substrate fn in `re-frame.interop`. Per Spec 002
  §Interop layer and rf2-s36l, the reactive-substrate surfaces
  (`ratom`, `ratom?`, `make-reaction`, `add-on-dispose!`, `dispose!`,
  `reactive?`) dispatch through the late-bind hook table:

    :adapter/ratom           — (fn [v])
    :adapter/ratom?          — (fn [x])
    :adapter/make-reaction   — (fn [f])
    :adapter/add-on-dispose! — (fn [a f])
    :adapter/dispose!        — (fn [a])
    :adapter/reactive?       — (fn [])

  Each call site uses `(when-let [hook ...] ...)` or
  `(if-let [hook ...] (hook ...) <default>)` — so an absent hook must
  return nil / false rather than throw. Adapter-uninstall tooling
  (rf2-s36l) depends on this — if a regression made the call sites
  throw on absent-hook, the swap-back-to-no-adapter path during dev
  would break loudly.

  Pre-rf2-oa9z no test exercised the absent-hook branch. The in-tree
  shadow-cljs build loads multiple adapter ns's at once, so the hooks
  are always populated at test time — we flip them to nil in a
  try / finally so cross-test isolation stays clean.

  Source: implementation/core/src/re_frame/interop.cljs:75 ff."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook temporarily set to nil.
  Restores the original value afterwards (success or throw)."
  [hook-key f]
  (let [original (late-bind/get-fn hook-key)]
    (try
      (late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (late-bind/set-fn! hook-key original)))))

;; ---- ratom ----------------------------------------------------------------

(deftest ratom-returns-nil-when-hook-absent
  (testing "interop/ratom returns nil (does not throw) when :adapter/ratom is unset"
    (with-hook-as-nil :adapter/ratom
      (fn []
        (is (nil? (late-bind/get-fn :adapter/ratom))
            "precondition: hook is unset")
        (is (nil? (interop/ratom :v))
            "absent hook returns nil — no throw")))))

;; ---- ratom? ---------------------------------------------------------------

(deftest ratom-pred-returns-false-when-hook-absent
  (testing "interop/ratom? returns false (does not throw) when :adapter/ratom? is unset"
    (with-hook-as-nil :adapter/ratom?
      (fn []
        (is (false? (interop/ratom? :anything))
            "absent hook returns false per the docstring contract")))))

;; ---- make-reaction --------------------------------------------------------

(deftest make-reaction-returns-nil-when-hook-absent
  (testing "interop/make-reaction returns nil (does not throw) when :adapter/make-reaction is unset"
    (with-hook-as-nil :adapter/make-reaction
      (fn []
        (is (nil? (interop/make-reaction (fn [] :computed)))
            "absent hook returns nil — no throw")))))

;; ---- add-on-dispose! ------------------------------------------------------

(deftest add-on-dispose-returns-nil-when-hook-absent
  (testing "interop/add-on-dispose! returns nil (does not throw) when :adapter/add-on-dispose! is unset"
    (with-hook-as-nil :adapter/add-on-dispose!
      (fn []
        (is (nil? (interop/add-on-dispose! (atom :stub) (fn [] :nothing)))
            "absent hook returns nil — no throw")))))

;; ---- dispose! -------------------------------------------------------------

(deftest dispose-returns-nil-when-hook-absent
  (testing "interop/dispose! returns nil (does not throw) when :adapter/dispose! is unset"
    (with-hook-as-nil :adapter/dispose!
      (fn []
        (is (nil? (interop/dispose! (atom :stub)))
            "absent hook returns nil — no throw")))))

;; ---- reactive? ------------------------------------------------------------

(deftest reactive-pred-returns-false-when-hook-absent
  (testing "interop/reactive? returns false (does not throw) when :adapter/reactive? is unset"
    (with-hook-as-nil :adapter/reactive?
      (fn []
        (is (false? (interop/reactive?))
            "absent hook returns false per the docstring contract")))))

;; ---- after-render ---------------------------------------------------------

(deftest after-render-returns-nil-when-hook-absent
  (testing "interop/after-render returns nil (does not throw) when :adapter/after-render is unset"
    ;; after-render is the only late-bound surface that is not on the
    ;; rf2-oa9z list explicitly but follows the same when-let pattern;
    ;; covering it pins the same contract for the same call shape.
    (with-hook-as-nil :adapter/after-render
      (fn []
        (is (nil? (interop/after-render (fn [] :nothing)))
            "absent hook returns nil — no throw")))))
