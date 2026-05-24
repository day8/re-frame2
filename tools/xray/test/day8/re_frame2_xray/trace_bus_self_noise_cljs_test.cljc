(ns day8.re-frame2-xray.trace-bus-self-noise-cljs-test
  "Regression tests for the Xray self-noise filter (rf2-xs8vu).

  Xray's own panels render INSIDE the host app, so every host
  dispatch reactively re-fires the Xray-side subs they read and
  triggers Xray-side view re-renders. Those self-induced
  `:rf.sub/run` + `:rf.view/render` trace emits all carry
  `:frame :rf/xray` (the panels mount under `(rf/with-frame
  :rf/xray ...)`), but they fire OUTSIDE any host dispatch — so
  without a filter they'd bucket as `:ungrouped :ungrounded` in
  Xray's own trace buffer and drown the host event the user
  actually clicked.

  The filter sits at INGEST in `collect-trace!` so the buffer never
  records the noise in the first place; readers stay simple. Pure-
  data + JVM-runnable predicate (`xray-internal-event?`) plus a
  CLJS-side wiring lock that drives `collect-trace!` end-to-end."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- reset-state [test-fn]
  ;; Each test starts with the defaults: flag off, counter empty,
  ;; buffer empty. Same shape as `sensitive-trace-cljs-test`.
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!)
  (trace-bus/clear-buffer!)
  (test-fn)
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!)
  (trace-bus/clear-buffer!))

(use-fixtures :each reset-state)

;; ---- predicate ----------------------------------------------------------

(deftest xray-internal-event?-top-level-frame
  (testing ":frame at top level — true iff = :rf/xray"
    (is (true?  (trace-bus/xray-internal-event? {:frame :rf/xray})))
    (is (false? (trace-bus/xray-internal-event? {:frame :rf/default})))
    (is (false? (trace-bus/xray-internal-event? {:frame :rf/main})))
    (is (false? (trace-bus/xray-internal-event? {:frame nil})))
    (is (false? (trace-bus/xray-internal-event? {})))))

(deftest xray-internal-event?-tags-frame-fallback
  (testing ":frame absent at top, present under :tags — fall-back applies"
    (is (true?  (trace-bus/xray-internal-event?
                  {:tags {:frame :rf/xray}})))
    (is (false? (trace-bus/xray-internal-event?
                  {:tags {:frame :rf/default}})))
    (is (false? (trace-bus/xray-internal-event?
                  {:tags {:frame nil}})))
    (is (false? (trace-bus/xray-internal-event?
                  {:tags {}}))))
  (testing "top-level wins when both present"
    (is (true?  (trace-bus/xray-internal-event?
                  {:frame :rf/xray :tags {:frame :rf/default}})))
    (is (false? (trace-bus/xray-internal-event?
                  {:frame :rf/default :tags {:frame :rf/xray}})))))

(deftest xray-internal-event?-realistic-shapes
  (testing "shapes mirroring real :rf.sub/run + :rf.view/render envelopes"
    ;; Layer-1 sub-read inside Xray's panel — :frame lives under :tags
    ;; per re-frame.subs/validate-and-trace.
    (is (true?  (trace-bus/xray-internal-event?
                  {:operation :rf.sub/run :op-type :rf.sub
                   :id 17 :time 1000
                   :tags {:rf.sub/id  :rf.xray/trace-buffer
                          :rf.sub/query-v [:rf.xray/trace-buffer]
                          :frame   :rf/xray}})))
    ;; View re-render from a Xray panel — :frame at top level per
    ;; re-frame.views/emit-render-trace!.
    (is (true?  (trace-bus/xray-internal-event?
                  {:operation :rf.view/render :op-type :rf.view
                   :id 18 :time 1001
                   :frame :rf/xray
                   :tags  {:rf.view/render-key 42 :frame :rf/xray}})))
    ;; Host event — must not be filtered.
    (is (false? (trace-bus/xray-internal-event?
                  {:operation :rf.event/dispatched :op-type :rf.event
                   :id 19 :time 1002
                   :tags {:rf.trace/event-id :user/click :frame :rf/default}})))))

;; ---- collect-trace! end-to-end wiring (CLJS only) -----------------------
;;
;; The pure-data predicate above is JVM-runnable; the wiring tests
;; below run only under CLJS because `collect-trace!` reads
;; `re-frame.interop/debug-enabled?` (true under the CLJS dev target,
;; false / unbound under JVM — the same pattern
;; `sensitive_trace_cljs_test.cljc` uses for its `collect-trace!`
;; assertions).

(defn- host-event []
  ;; Realistic host `:rf.event/dispatched` envelope — the user clicks a
  ;; button, `:user/click` dispatches into the host's `:rf/default`
  ;; frame, the framework emits this trace event.
  {:operation :rf.event/dispatched :op-type :rf.event
   :id 1 :time 1000
   :tags {:rf.trace/event-id :user/click :frame :rf/default}})

(defn- xray-sub-read-event []
  ;; Realistic `:rf.sub/run` emit from a Xray panel re-rendering in
  ;; response to host activity. The sub fires under
  ;; `(rf/with-frame :rf/xray ...)` so its trace envelope carries
  ;; `:frame :rf/xray`. This is the noise rf2-xs8vu eliminates.
  {:operation :rf.sub/run :op-type :rf.sub
   :id 2 :time 1001
   :tags {:rf.sub/id  :rf.xray/trace-buffer
          :rf.sub/query-v [:rf.xray/trace-buffer]
          :frame   :rf/xray}})

(defn- xray-view-render-event []
  ;; Realistic `:rf.view/render` emit from a Xray panel re-rendering.
  ;; `:frame` is hoisted top-level per re-frame.views/emit-render-trace!.
  {:operation :rf.view/render :op-type :rf.view
   :id 3 :time 1002
   :frame :rf/xray
   :tags  {:rf.view/render-key 42 :frame :rf/xray}})

#?(:cljs
   (deftest collect-trace-records-host-events
     (testing "host-frame events flow into the buffer (sanity)"
       (trace-bus/collect-trace! (host-event))
       (is (= 1 (count (trace-bus/buffer))))
       (is (= :rf/default
              (get-in (first (trace-bus/buffer)) [:tags :frame]))))))

#?(:cljs
   (deftest collect-trace-drops-xray-sub-reads
     (testing "Xray-frame :rf.sub/run events MUST NOT enter the buffer"
       (trace-bus/collect-trace! (xray-sub-read-event))
       (is (= 0 (count (trace-bus/buffer)))
           "the self-induced sub-read drowns the host event under :ungrouped if recorded")
       (is (= 0 (config/suppressed-count))
           "the REDACTED counter must NOT bump — this is structural noise, not privacy"))))

#?(:cljs
   (deftest collect-trace-drops-xray-view-renders
     (testing "Xray-frame :rf.view/render events MUST NOT enter the buffer"
       (trace-bus/collect-trace! (xray-view-render-event))
       (is (= 0 (count (trace-bus/buffer)))
           "self-induced view re-renders are dropped")
       (is (= 0 (config/suppressed-count))
           "no REDACTED bump for structural self-noise"))))

#?(:cljs
   (deftest collect-trace-mixed-flow-keeps-only-host-events
     (testing "host event + Xray self-noise cascade — only the host event survives"
       ;; Simulate Mike's reported symptom: user clicks a button, Xray
       ;; panels re-render in response, a flurry of Xray-frame sub-
       ;; reads + view-renders arrive immediately after.
       (trace-bus/collect-trace! (host-event))
       (trace-bus/collect-trace! (xray-sub-read-event))
       (trace-bus/collect-trace! (xray-view-render-event))
       (trace-bus/collect-trace! (xray-sub-read-event))
       (trace-bus/collect-trace! (xray-sub-read-event))
       (let [buf (trace-bus/buffer)]
         (is (= 1 (count buf))
             "exactly one event — the host click — survives the filter")
         (is (= :user/click (get-in (first buf) [:tags :rf.trace/event-id]))
             "the surviving event IS the host click, not a self-noise impostor")
         (is (= :rf/default (get-in (first buf) [:tags :frame]))
             "the surviving event targets the host frame")))))

#?(:cljs
   (deftest collect-trace-filter-applies-to-tags-frame
     (testing "Xray-internal events with :frame under :tags only are also dropped"
       ;; Some emit sites leave :frame under :tags rather than hoisting
       ;; top-level (e.g. :rf.sub/run via re-frame.subs/validate-and-trace).
       ;; The filter MUST cover both shapes.
       (trace-bus/collect-trace!
         {:operation :rf.sub/run :op-type :rf.sub
          :id 4 :time 1003
          :tags {:rf.sub/id :rf.xray/cascades :frame :rf/xray}})
       (is (= 0 (count (trace-bus/buffer)))))))

#?(:cljs
   (deftest collect-trace-filter-symmetric-across-frame-keys
     (testing "host events with :frame under :tags are still recorded"
       ;; Symmetry guard — the filter is narrow (only :rf/xray), not a
       ;; blanket rejection of any event with a :tags :frame slot.
       (trace-bus/collect-trace!
         {:operation :rf.sub/run :op-type :rf.sub
          :id 5 :time 1004
          :tags {:rf.sub/id :user/some-sub :frame :rf/default}})
       (is (= 1 (count (trace-bus/buffer)))
           "non-Xray :rf.sub/run events flow through normally"))))

;; ---- xray-internal event-id guard (rf2-g1pt8) --------------------------
;;
;; Pure-data sibling of `xray-internal-event?`. The ingest filter above
;; catches every trace event emitted INSIDE `(rf/with-frame :rf/xray
;; ...)`. The data-layer filter below catches every CASCADE whose
;; event-id is in the `rf.xray` namespace — covering :rf.xray/* events
;; dispatched WITHOUT `:frame :rf/xray` (those land on the host frame
;; and slip past the ingest filter; the data-layer guard at the
;; `:rf.xray/cascades` sub closes the hole structurally).
;;
;; Predicate tests run under both CLJ + CLJS (pure data, no
;; collect-trace! plumbing).

(deftest xray-internal-event-id?-keyword-namespace
  (testing "true iff event-id is a keyword in the `rf.xray` namespace"
    (is (true?  (trace-bus/xray-internal-event-id? :rf.xray/focus-cascade)))
    (is (true?  (trace-bus/xray-internal-event-id? :rf.xray/select-tab)))
    (is (true?  (trace-bus/xray-internal-event-id? :rf.xray/open-settings)))
    (is (true?  (trace-bus/xray-internal-event-id? :rf.xray/sync-trace-buffer)))
    (testing "user-app events stay false"
      (is (false? (trace-bus/xray-internal-event-id? :cart/add-item)))
      (is (false? (trace-bus/xray-internal-event-id? :checkout/start)))
      (is (false? (trace-bus/xray-internal-event-id? :user/click))))
    (testing "framework + sibling reserved namespaces stay false"
      ;; The filter is narrow: only `rf.xray`. Sibling reserved
      ;; namespaces (`:rf/init`, `:rf.epoch/*`) are NOT Xray-internal
      ;; — they're framework / epoch surface and must remain visible
      ;; in the user-facing cascade list.
      (is (false? (trace-bus/xray-internal-event-id? :rf/init)))
      (is (false? (trace-bus/xray-internal-event-id? :rf.epoch/begin)))
      (is (false? (trace-bus/xray-internal-event-id? :rf.story/something))))
    (testing "nil-safe + non-keyword inputs"
      (is (false? (trace-bus/xray-internal-event-id? nil)))
      (is (false? (trace-bus/xray-internal-event-id? "rf.xray/foo")))
      (is (false? (trace-bus/xray-internal-event-id? :unnamespaced)))
      (is (false? (trace-bus/xray-internal-event-id? 42))))
    (testing "namespace prefix collision guard"
      ;; A keyword whose namespace STARTS with `rf.xray` but is not
      ;; exactly `rf.xray` (e.g. `:rf.xray-test/x`, `:rf.xray-foo/y`)
      ;; must NOT match. The contract is namespace equality, not
      ;; prefix matching.
      (is (false? (trace-bus/xray-internal-event-id? :rf.xray-test/x)))
      (is (false? (trace-bus/xray-internal-event-id? :rf.xray-foo/y))))))

(deftest xray-internal-cascade?-event-vector-head
  (testing "true iff the cascade's :event vector's head is xray-internal"
    (is (true?  (trace-bus/xray-internal-cascade?
                  {:dispatch-id 1
                   :event       [:rf.xray/focus-cascade 99]})))
    (is (true?  (trace-bus/xray-internal-cascade?
                  {:dispatch-id 2
                   :event       [:rf.xray/select-tab :event]})))
    (is (true?  (trace-bus/xray-internal-cascade?
                  {:dispatch-id 3
                   :event       [:rf.xray/open-settings]}))
        "single-element event vector (no payload) still classifies"))
  (testing "user-app cascades stay false"
    (is (false? (trace-bus/xray-internal-cascade?
                  {:dispatch-id 4
                   :event       [:cart/add-item {:item-id "apple"}]})))
    (is (false? (trace-bus/xray-internal-cascade?
                  {:dispatch-id 5
                   :event       [:user/click]}))))
  (testing ":ungrouped + event-less cascades stay false"
    ;; `cascade-has-event?` (rf2-639lc) handles the :ungrouped bucket
    ;; at the L2 boundary; the xray-internal filter sits orthogonal.
    (is (false? (trace-bus/xray-internal-cascade?
                  {:dispatch-id :ungrouped :event nil})))
    (is (false? (trace-bus/xray-internal-cascade?
                  {:dispatch-id 6 :event []}))))
  (testing "malformed shapes don't throw"
    (is (false? (trace-bus/xray-internal-cascade? {})))
    (is (false? (trace-bus/xray-internal-cascade?
                  {:dispatch-id 7 :event "not-a-vector"})))))
