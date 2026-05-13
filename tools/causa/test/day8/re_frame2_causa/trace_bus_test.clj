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

;; ---- self-emitted? predicate (rf2-nk01x) --------------------------------
;;
;; `collect-trace!`'s guard for Causa's own bookkeeping dispatches.
;; Pure-data + JVM-runnable so the predicate's algebra is covered
;; without a CLJS runtime. The integration coverage (`dispatch-sync`
;; under a real cascade) lives in the CLJS sensitive_trace test.

(deftest self-emitted?-recognises-bookkeeping-dispatched-trace
  (testing ":event/dispatched trace events for Causa's own bookkeeping
            event ids are self-emits"
    (is (true? (trace-bus/self-emitted?
                {:op-type :event :operation :event/dispatched
                 :sensitive? true
                 :tags {:event [:rf.causa/note-sensitive-suppressed :rf/default]
                        :frame :rf/causa}}))
        ":rf.causa/note-sensitive-suppressed dispatch trace is self-emit")
    (is (true? (trace-bus/self-emitted?
                {:op-type :event :operation :event/dispatched
                 :tags {:event [:rf.causa/note-trace-event {:fake :event}]
                        :frame :rf/causa}}))
        ":rf.causa/note-trace-event dispatch trace is self-emit")
    (is (true? (trace-bus/self-emitted?
                {:op-type :event :operation :event/dispatched
                 :tags {:event [:rf.causa/clear-trace-buffer]
                        :frame :rf/causa}}))
        ":rf.causa/clear-trace-buffer dispatch trace is self-emit")
    (is (true? (trace-bus/self-emitted?
                {:op-type :event :operation :event/dispatched
                 :tags {:event [:rf.causa/reset-suppressed-counters]
                        :frame :rf/causa}}))
        ":rf.causa/reset-suppressed-counters dispatch trace is self-emit")
    (is (true? (trace-bus/self-emitted?
                {:op-type :event :operation :event/dispatched
                 :tags {:event [:rf.causa/sync-trace-buffer []]
                        :frame :rf/causa}}))
        ":rf.causa/sync-trace-buffer dispatch trace is self-emit")))

(deftest self-emitted?-recognises-handler-scoped-bookkeeping-trace
  (testing "trace events emitted INSIDE the bookkeeping handler's scope
            (with :tags :event-id pointing at the bookkeeping id) are
            self-emits — this catches :event/handled, :event/db-changed,
            :rf.fx/handled, etc., emitted under the handler's dynamic
            binding"
    (is (true? (trace-bus/self-emitted?
                {:op-type :event :operation :event/db-changed
                 :tags {:event-id :rf.causa/note-sensitive-suppressed
                        :frame :rf/causa}})))
    (is (true? (trace-bus/self-emitted?
                {:op-type :event :operation :event/handled
                 :tags {:event-id :rf.causa/note-trace-event
                        :frame :rf/causa}})))))

(deftest self-emitted?-rejects-non-bookkeeping-events
  (testing "user-domain events are NOT self-emits"
    (is (false? (trace-bus/self-emitted?
                 {:op-type :event :operation :event/dispatched
                  :sensitive? true
                  :tags {:event [:auth/sign-in {:password "x"}]
                         :frame :rf/default}}))
        "a real :sensitive? event is NOT a self-emit")
    (is (false? (trace-bus/self-emitted?
                 {:op-type :event :operation :event/handled
                  :tags {:event-id :user/click
                         :frame :rf/default}}))
        "a normal user event handled-trace is NOT a self-emit"))
  (testing "non-:rf.causa/* re-frame internal events are NOT self-emits"
    (is (false? (trace-bus/self-emitted?
                 {:op-type :event :operation :event/dispatched
                  :tags {:event [:rf.fx/dispatch [:user/foo]]}}))))
  (testing "non-map / nil / bare values are not self-emits (no NPE)"
    (is (false? (trace-bus/self-emitted? nil)))
    (is (false? (trace-bus/self-emitted? :keyword)))
    (is (false? (trace-bus/self-emitted? [:vector])))
    (is (false? (trace-bus/self-emitted? {})))
    (is (false? (trace-bus/self-emitted? {:tags {}}))))
  (testing "shapes that look LIKE the bookkeeping events but aren't"
    (is (false? (trace-bus/self-emitted?
                 {:op-type :event :operation :event/dispatched
                  :tags {:event [:user/note-sensitive-suppressed]}}))
        "different ns, same suffix — must NOT match")
    (is (false? (trace-bus/self-emitted?
                 {:tags {:event-id :rf.causa/select-panel}}))
        ":rf.causa/* but NOT a bookkeeping id")))
