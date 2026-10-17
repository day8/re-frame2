(ns re-frame.ssr.derived-value-watch-cljs-test
  "An SSR derived value takes a watch, on both runtimes. The SSR adapter
  reuses the plain-atom derived value under its own `subscribe-container`,
  and Spec 006 §`make-derived-value` says `subscribe-container` works on a
  derived container as on a base container. The plain-atom adapter's own
  pin, with the layered and disposal cases, is
  `re-frame.substrate.plain-atom-derived-watch-cljs-test`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ssr :as rf.ssr]))

(def ^:private A rf.ssr/adapter)

(deftest an-observer-hears-each-change-of-the-computed-value-once
  (let [db    ((:make-state-container A) {:n 1 :noise 0})
        tens  ((:make-derived-value A) [db] (fn [{:keys [n]}] (* 10 n)))
        calls (atom [])
        unsub ((:subscribe-container A) tens (fn [old nu] (swap! calls conj [old nu])))]
    ((:replace-container! A) db {:n 2 :noise 0})
    (is (= [[10 20]] @calls) "one call, carrying the old and new computed values")
    ((:replace-container! A) db {:n 2 :noise 1})
    (is (= [[10 20]] @calls) "a source write that leaves the computed value unchanged makes no call")
    (unsub)
    ((:replace-container! A) db {:n 3 :noise 1})
    (is (= [[10 20]] @calls) "an unsubscribed observer hears nothing more")
    (is (= 30 ((:read-container A) tens)) "the value still recomputes on read")))
