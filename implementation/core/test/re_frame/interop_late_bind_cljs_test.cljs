(ns re-frame.interop-late-bind-cljs-test
  "Every reactive-substrate fn in `re-frame.interop` dispatches through a
  late-bind hook (Spec 002 §Interop layer), and an absent hook returns nil /
  false rather than throwing, which adapter-uninstall tooling relies on. The
  node bundle loads several adapters, so each row unsets its hook for the
  extent of the call."
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
        #(is (= expected (call)) "the absent hook returns its default and does not throw")))))
