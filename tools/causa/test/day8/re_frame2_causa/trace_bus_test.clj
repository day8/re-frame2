(ns day8.re-frame2-causa.trace-bus-test
  "JVM tests for the pure-fn portion of Causa's trace ring buffer
  (rf2-n6x4q). The `push` fn (in trace_bus.cljc) is pure data — no
  atoms, no side effects — so its eviction algebra is testable from
  the JVM. The CLJS-only side-effecting bits (collect-trace!, the
  set-buffer-depth! atom swap, the framework's register-trace-cb!
  delivery path) live in the CLJS smoke test
  (preload_cljs_test.cljs)."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

(deftest push-appends-up-to-depth
  (testing "push appends events while count stays under depth"
    (is (= [{:id 1}] (trace-bus/push [] 3 {:id 1})))
    (is (= [{:id 1} {:id 2}] (trace-bus/push [{:id 1}] 3 {:id 2})))
    (is (= [{:id 1} {:id 2} {:id 3}]
           (trace-bus/push [{:id 1} {:id 2}] 3 {:id 3})))))

(deftest push-evicts-oldest-on-overflow
  (testing "push drops the oldest entry when depth is exceeded"
    (let [buf [{:id 1} {:id 2} {:id 3}]]
      (is (= [{:id 2} {:id 3} {:id 4}]
             (trace-bus/push buf 3 {:id 4}))
          "appending the 4th entry to a depth-3 buffer drops :id 1"))))

(deftest push-handles-depth-zero
  (testing "push at depth 0 returns an empty buffer"
    ;; Depth 0 means 'never retain'. The fn appends + slices to zero.
    (is (= [] (trace-bus/push [] 0 {:id 1}))
        "depth 0 = no retention")))

(deftest push-handles-shrinking-depth
  (testing "push with a smaller depth than the current buffer truncates head"
    ;; This scenario mirrors set-buffer-depth! flipping to a smaller
    ;; value mid-session: the next push compacts to the new depth.
    (let [buf [{:id 1} {:id 2} {:id 3} {:id 4} {:id 5}]]
      (is (= [{:id 4} {:id 5} {:id 6}]
             (trace-bus/push buf 3 {:id 6}))
          "depth 3 retains 3 newest entries after the next push"))))

;; ---- self-emit guard history (rf2-nk01x → rf2-qsjda) ----------------------
;;
;; rf2-nk01x introduced a Causa-side `self-emitted?` predicate to stop
;; `collect-trace!` re-entering itself via its own bookkeeping dispatches'
;; trace events. rf2-qsjda promoted the opt-out to the framework: the
;; bookkeeping handlers in registry.cljs now carry `:rf.trace/no-emit?
;; true`, and `re-frame.trace/emit!` / `emit-error!` / the queue-time
;; `emit-dispatched-trace!` all short-circuit on the flag. The
;; Causa-side `self-emitted?` predicate is obsolete and gone; the
;; framework gate's tests live in `re-frame.trace-test`. The
;; integration coverage of the closed loop under a real CLJS cascade
;; remains in `sensitive_trace_loop_cljs_test.cljs`.
