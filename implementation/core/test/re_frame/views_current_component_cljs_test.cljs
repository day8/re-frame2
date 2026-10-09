(ns re-frame.views-current-component-cljs-test
  "`re-frame.views/current-frame` with no in-flight component: the dynamic
  var still wins, and otherwise the reader answers nil — never a
  `:rf/default` floor (Spec 002 §Frame target resolution)."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.views :as rf.views]))

(defn- with-hook
  "Run `f` with the late-bind `hook-key` set to `hook`, restoring the
  original afterwards: the node bundle loads adapters that publish it."
  [hook-key hook f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key hook)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest current-frame-with-no-adapter-hook
  (with-hook :adapter/current-component nil
    #(is (nil? (rf.views/current-frame)))))

(deftest current-frame-honours-dynamic-var-without-hook
  (with-hook :adapter/current-component nil
    #(binding [rf.frame/*current-frame* :test/dynamic-frame]
       (is (= :test/dynamic-frame (rf.views/current-frame))))))

(deftest current-frame-tolerates-hook-returning-nil
  ;; A real adapter's current-component answers nil outside a render.
  (with-hook :adapter/current-component (constantly nil)
    #(is (nil? (rf.views/current-frame)))))
