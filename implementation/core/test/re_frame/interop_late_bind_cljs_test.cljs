(ns re-frame.interop-late-bind-cljs-test
  "Coverage for the no-adapter / unset-hook branch of
  every reactive-substrate fn in `re-frame.interop`. Per Spec 002
  §Interop layer, the reactive-substrate surfaces
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
  depends on this — call sites that threw on an absent hook would
  break the swap-back-to-no-adapter path during dev loudly.

  The in-tree
  shadow-cljs build loads multiple adapter ns's at once, so the hooks
  are always populated at test time — these tests flip them to nil in a
  try / finally so cross-test isolation stays clean.

  Source: implementation/core/src/re_frame/interop.cljs:75 ff."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook temporarily set to nil.
  Restores the original value afterwards (success or throw)."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest absent-hook-returns-its-default-rather-than-throwing
  (doseq [[hook call expected]
          [[:adapter/ratom           #(rf.interop/ratom :v)                                   nil]
           [:adapter/ratom?          #(rf.interop/ratom? :anything)                           false]
           [:adapter/make-reaction   #(rf.interop/make-reaction (fn [] :computed))            nil]
           [:adapter/add-on-dispose! #(rf.interop/add-on-dispose! (atom :stub) (fn [] :nothing)) nil]
           [:adapter/dispose!        #(rf.interop/dispose! (atom :stub))                      nil]
           [:adapter/reactive?       #(rf.interop/reactive?)                                  false]
           [:adapter/after-render    #(rf.interop/after-render (fn [] :nothing))              nil]]]
    (testing (str hook " unset")
      (with-hook-as-nil hook
        (fn []
          (is (nil? (rf.late-bind/get-fn hook)) "precondition: the hook is unset")
          (is (= expected (call))
              "the absent hook returns the documented default and does not throw"))))))
