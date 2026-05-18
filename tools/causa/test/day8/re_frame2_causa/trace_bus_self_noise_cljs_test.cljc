(ns day8.re-frame2-causa.trace-bus-self-noise-cljs-test
  "Regression tests for the Causa self-noise filter (rf2-xs8vu).

  Causa's own panels render INSIDE the host app, so every host
  dispatch reactively re-fires the Causa-side subs they read and
  triggers Causa-side view re-renders. Those self-induced
  `:sub/run` + `:view/render` trace emits all carry
  `:frame :rf/causa` (the panels mount under `(rf/with-frame
  :rf/causa ...)`), but they fire OUTSIDE any host dispatch — so
  without a filter they'd bucket as `:ungrouped :ungrounded` in
  Causa's own trace buffer and drown the host event the user
  actually clicked.

  The filter sits at INGEST in `collect-trace!` so the buffer never
  records the noise in the first place; readers stay simple. Pure-
  data + JVM-runnable predicate (`causa-internal-event?`) plus a
  CLJS-side wiring lock that drives `collect-trace!` end-to-end."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

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

(deftest causa-internal-event?-top-level-frame
  (testing ":frame at top level — true iff = :rf/causa"
    (is (true?  (trace-bus/causa-internal-event? {:frame :rf/causa})))
    (is (false? (trace-bus/causa-internal-event? {:frame :rf/default})))
    (is (false? (trace-bus/causa-internal-event? {:frame :rf/main})))
    (is (false? (trace-bus/causa-internal-event? {:frame nil})))
    (is (false? (trace-bus/causa-internal-event? {})))))

(deftest causa-internal-event?-tags-frame-fallback
  (testing ":frame absent at top, present under :tags — fall-back applies"
    (is (true?  (trace-bus/causa-internal-event?
                  {:tags {:frame :rf/causa}})))
    (is (false? (trace-bus/causa-internal-event?
                  {:tags {:frame :rf/default}})))
    (is (false? (trace-bus/causa-internal-event?
                  {:tags {:frame nil}})))
    (is (false? (trace-bus/causa-internal-event?
                  {:tags {}}))))
  (testing "top-level wins when both present"
    (is (true?  (trace-bus/causa-internal-event?
                  {:frame :rf/causa :tags {:frame :rf/default}})))
    (is (false? (trace-bus/causa-internal-event?
                  {:frame :rf/default :tags {:frame :rf/causa}})))))

(deftest causa-internal-event?-realistic-shapes
  (testing "shapes mirroring real :sub/run + :view/render envelopes"
    ;; Layer-1 sub-read inside Causa's panel — :frame lives under :tags
    ;; per re-frame.subs/validate-and-trace.
    (is (true?  (trace-bus/causa-internal-event?
                  {:operation :sub/run :op-type :sub/run
                   :id 17 :time 1000
                   :tags {:sub-id  :rf.causa/trace-buffer
                          :query-v [:rf.causa/trace-buffer]
                          :frame   :rf/causa}})))
    ;; View re-render from a Causa panel — :frame at top level per
    ;; re-frame.views/emit-render-trace!.
    (is (true?  (trace-bus/causa-internal-event?
                  {:operation :view/render :op-type :view
                   :id 18 :time 1001
                   :frame :rf/causa
                   :tags  {:render-key 42 :frame :rf/causa}})))
    ;; Host event — must not be filtered.
    (is (false? (trace-bus/causa-internal-event?
                  {:operation :event/dispatched :op-type :event
                   :id 19 :time 1002
                   :tags {:event-id :user/click :frame :rf/default}})))))

;; ---- collect-trace! end-to-end wiring (CLJS only) -----------------------
;;
;; The pure-data predicate above is JVM-runnable; the wiring tests
;; below run only under CLJS because `collect-trace!` reads
;; `re-frame.interop/debug-enabled?` (true under the CLJS dev target,
;; false / unbound under JVM — the same pattern
;; `sensitive_trace_cljs_test.cljc` uses for its `collect-trace!`
;; assertions).

(defn- host-event []
  ;; Realistic host `:event/dispatched` envelope — the user clicks a
  ;; button, `:user/click` dispatches into the host's `:rf/default`
  ;; frame, the framework emits this trace event.
  {:operation :event/dispatched :op-type :event
   :id 1 :time 1000
   :tags {:event-id :user/click :frame :rf/default}})

(defn- causa-sub-read-event []
  ;; Realistic `:sub/run` emit from a Causa panel re-rendering in
  ;; response to host activity. The sub fires under
  ;; `(rf/with-frame :rf/causa ...)` so its trace envelope carries
  ;; `:frame :rf/causa`. This is the noise rf2-xs8vu eliminates.
  {:operation :sub/run :op-type :sub/run
   :id 2 :time 1001
   :tags {:sub-id  :rf.causa/trace-buffer
          :query-v [:rf.causa/trace-buffer]
          :frame   :rf/causa}})

(defn- causa-view-render-event []
  ;; Realistic `:view/render` emit from a Causa panel re-rendering.
  ;; `:frame` is hoisted top-level per re-frame.views/emit-render-trace!.
  {:operation :view/render :op-type :view
   :id 3 :time 1002
   :frame :rf/causa
   :tags  {:render-key 42 :frame :rf/causa}})

#?(:cljs
   (deftest collect-trace-records-host-events
     (testing "host-frame events flow into the buffer (sanity)"
       (trace-bus/collect-trace! (host-event))
       (is (= 1 (count (trace-bus/buffer))))
       (is (= :rf/default
              (get-in (first (trace-bus/buffer)) [:tags :frame]))))))

#?(:cljs
   (deftest collect-trace-drops-causa-sub-reads
     (testing "Causa-frame :sub/run events MUST NOT enter the buffer"
       (trace-bus/collect-trace! (causa-sub-read-event))
       (is (= 0 (count (trace-bus/buffer)))
           "the self-induced sub-read drowns the host event under :ungrouped if recorded")
       (is (= 0 (config/suppressed-count))
           "the REDACTED counter must NOT bump — this is structural noise, not privacy"))))

#?(:cljs
   (deftest collect-trace-drops-causa-view-renders
     (testing "Causa-frame :view/render events MUST NOT enter the buffer"
       (trace-bus/collect-trace! (causa-view-render-event))
       (is (= 0 (count (trace-bus/buffer)))
           "self-induced view re-renders are dropped")
       (is (= 0 (config/suppressed-count))
           "no REDACTED bump for structural self-noise"))))

#?(:cljs
   (deftest collect-trace-mixed-flow-keeps-only-host-events
     (testing "host event + Causa self-noise cascade — only the host event survives"
       ;; Simulate Mike's reported symptom: user clicks a button, Causa
       ;; panels re-render in response, a flurry of Causa-frame sub-
       ;; reads + view-renders arrive immediately after.
       (trace-bus/collect-trace! (host-event))
       (trace-bus/collect-trace! (causa-sub-read-event))
       (trace-bus/collect-trace! (causa-view-render-event))
       (trace-bus/collect-trace! (causa-sub-read-event))
       (trace-bus/collect-trace! (causa-sub-read-event))
       (let [buf (trace-bus/buffer)]
         (is (= 1 (count buf))
             "exactly one event — the host click — survives the filter")
         (is (= :user/click (get-in (first buf) [:tags :event-id]))
             "the surviving event IS the host click, not a self-noise impostor")
         (is (= :rf/default (get-in (first buf) [:tags :frame]))
             "the surviving event targets the host frame")))))

#?(:cljs
   (deftest collect-trace-filter-applies-to-tags-frame
     (testing "Causa-internal events with :frame under :tags only are also dropped"
       ;; Some emit sites leave :frame under :tags rather than hoisting
       ;; top-level (e.g. :sub/run via re-frame.subs/validate-and-trace).
       ;; The filter MUST cover both shapes.
       (trace-bus/collect-trace!
         {:operation :sub/run :op-type :sub/run
          :id 4 :time 1003
          :tags {:sub-id :rf.causa/cascades :frame :rf/causa}})
       (is (= 0 (count (trace-bus/buffer)))))))

#?(:cljs
   (deftest collect-trace-filter-symmetric-across-frame-keys
     (testing "host events with :frame under :tags are still recorded"
       ;; Symmetry guard — the filter is narrow (only :rf/causa), not a
       ;; blanket rejection of any event with a :tags :frame slot.
       (trace-bus/collect-trace!
         {:operation :sub/run :op-type :sub/run
          :id 5 :time 1004
          :tags {:sub-id :user/some-sub :frame :rf/default}})
       (is (= 1 (count (trace-bus/buffer)))
           "non-Causa :sub/run events flow through normally"))))

;; ---- causa-internal event-id guard (rf2-g1pt8) --------------------------
;;
;; Pure-data sibling of `causa-internal-event?`. The ingest filter above
;; catches every trace event emitted INSIDE `(rf/with-frame :rf/causa
;; ...)`. The data-layer filter below catches every CASCADE whose
;; event-id is in the `rf.causa` namespace — covering :rf.causa/* events
;; dispatched WITHOUT `:frame :rf/causa` (those land on the host frame
;; and slip past the ingest filter; the data-layer guard at the
;; `:rf.causa/cascades` sub closes the hole structurally).
;;
;; Predicate tests run under both CLJ + CLJS (pure data, no
;; collect-trace! plumbing).

(deftest causa-internal-event-id?-keyword-namespace
  (testing "true iff event-id is a keyword in the `rf.causa` namespace"
    (is (true?  (trace-bus/causa-internal-event-id? :rf.causa/focus-cascade)))
    (is (true?  (trace-bus/causa-internal-event-id? :rf.causa/select-tab)))
    (is (true?  (trace-bus/causa-internal-event-id? :rf.causa/open-settings)))
    (is (true?  (trace-bus/causa-internal-event-id? :rf.causa/select-panel)))
    (is (true?  (trace-bus/causa-internal-event-id? :rf.causa/sync-trace-buffer)))
    (testing "user-app events stay false"
      (is (false? (trace-bus/causa-internal-event-id? :cart/add-item)))
      (is (false? (trace-bus/causa-internal-event-id? :checkout/start)))
      (is (false? (trace-bus/causa-internal-event-id? :user/click))))
    (testing "framework + sibling reserved namespaces stay false"
      ;; The filter is narrow: only `rf.causa`. Sibling reserved
      ;; namespaces (`:rf/init`, `:rf.epoch/*`) are NOT Causa-internal
      ;; — they're framework / epoch surface and must remain visible
      ;; in the user-facing cascade list.
      (is (false? (trace-bus/causa-internal-event-id? :rf/init)))
      (is (false? (trace-bus/causa-internal-event-id? :rf.epoch/begin)))
      (is (false? (trace-bus/causa-internal-event-id? :rf.story/something))))
    (testing "nil-safe + non-keyword inputs"
      (is (false? (trace-bus/causa-internal-event-id? nil)))
      (is (false? (trace-bus/causa-internal-event-id? "rf.causa/foo")))
      (is (false? (trace-bus/causa-internal-event-id? :unnamespaced)))
      (is (false? (trace-bus/causa-internal-event-id? 42))))
    (testing "namespace prefix collision guard"
      ;; A keyword whose namespace STARTS with `rf.causa` but is not
      ;; exactly `rf.causa` (e.g. `:rf.causal/x`, `:rf.causal-link/y`)
      ;; must NOT match. The contract is namespace equality, not
      ;; prefix matching.
      (is (false? (trace-bus/causa-internal-event-id? :rf.causal/x)))
      (is (false? (trace-bus/causa-internal-event-id? :rf.causal-link/y))))))

(deftest causa-internal-cascade?-event-vector-head
  (testing "true iff the cascade's :event vector's head is causa-internal"
    (is (true?  (trace-bus/causa-internal-cascade?
                  {:dispatch-id 1
                   :event       [:rf.causa/focus-cascade 99]})))
    (is (true?  (trace-bus/causa-internal-cascade?
                  {:dispatch-id 2
                   :event       [:rf.causa/select-tab :event]})))
    (is (true?  (trace-bus/causa-internal-cascade?
                  {:dispatch-id 3
                   :event       [:rf.causa/open-settings]}))
        "single-element event vector (no payload) still classifies"))
  (testing "user-app cascades stay false"
    (is (false? (trace-bus/causa-internal-cascade?
                  {:dispatch-id 4
                   :event       [:cart/add-item {:item-id "apple"}]})))
    (is (false? (trace-bus/causa-internal-cascade?
                  {:dispatch-id 5
                   :event       [:user/click]}))))
  (testing ":ungrouped + event-less cascades stay false"
    ;; `cascade-has-event?` (rf2-639lc) handles the :ungrouped bucket
    ;; at the L2 boundary; the causa-internal filter sits orthogonal.
    (is (false? (trace-bus/causa-internal-cascade?
                  {:dispatch-id :ungrouped :event nil})))
    (is (false? (trace-bus/causa-internal-cascade?
                  {:dispatch-id 6 :event []}))))
  (testing "malformed shapes don't throw"
    (is (false? (trace-bus/causa-internal-cascade? {})))
    (is (false? (trace-bus/causa-internal-cascade?
                  {:dispatch-id 7 :event "not-a-vector"})))))
