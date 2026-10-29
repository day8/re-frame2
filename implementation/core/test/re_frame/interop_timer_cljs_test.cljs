(ns re-frame.interop-timer-cljs-test
  "`re-frame.interop/set-timeout!` on CLJS: a delay longer than one host timer
  holds (2^31-1 ms) runs as a chain of hops rather than overflowing to an
  immediate fire, and `clear-timeout!` cancels whichever hop is armed. The
  host timer is replaced by a recorder, so nothing waits on a real clock."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.interop :as rf.interop]))

(def ^:private max-ms 2147483647)

(defn- with-recorded-timers
  "Run `(f armed cleared)` with the host's setTimeout / clearTimeout replaced:
  `armed` is an atom of `[callback delay]` in arming order (the handle is the
  1-based position), `cleared` an atom of cleared handles."
  [f]
  (let [orig-set   (.-setTimeout js/globalThis)
        orig-clear (.-clearTimeout js/globalThis)
        armed      (atom [])
        cleared    (atom [])]
    (set! (.-setTimeout js/globalThis)
          (fn [cb ms] (swap! armed conj [cb ms]) (count @armed)))
    (set! (.-clearTimeout js/globalThis)
          (fn [handle] (swap! cleared conj handle)))
    (try
      (f armed cleared)
      (finally
        (set! (.-setTimeout js/globalThis) orig-set)
        (set! (.-clearTimeout js/globalThis) orig-clear)))))

(deftest a-long-delay-runs-as-hops-and-fires-once-when-due
  (with-recorded-timers
    (fn [armed _cleared]
      (let [fired (atom 0)]
        (rf.interop/set-timeout! #(swap! fired inc) (+ max-ms max-ms 5))
        ((first (nth @armed 0)))
        ((first (nth @armed 1)))
        (is (= [max-ms max-ms 5] (map second @armed))
            "two full-length hops, then the remainder")
        (is (zero? @fired) "nothing fires before the last hop")
        ((first (nth @armed 2)))
        (is (= 1 @fired))))))

(deftest clear-timeout-cancels-the-armed-hop
  (with-recorded-timers
    (fn [armed cleared]
      (let [handle (rf.interop/set-timeout! (fn []) (+ max-ms 10))]
        ((first (nth @armed 0)))
        (rf.interop/clear-timeout! handle)
        (is (= [2] @cleared) "the second hop, armed by the first, is the one cancelled")))))

(deftest a-delay-a-host-timer-holds-is-one-host-timer
  (with-recorded-timers
    (fn [armed cleared]
      (let [handle (rf.interop/set-timeout! (fn []) 250)]
        (rf.interop/clear-timeout! handle)
        (is (= [[250] [1]] [(map second @armed) @cleared]))))))
