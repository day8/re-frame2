(ns day8.re-frame2-xray.self-noise-cljs-test
  "Regression tests for the Xray self-noise filter (rf2-xs8vu).

  Xray's own panels render INSIDE the host app, so every host
  dispatch reactively re-fires the Xray-side subs they read and
  triggers Xray-side view re-renders. Those self-induced
  `:rf.sub/run` + `:rf.view/render` trace emits all carry
  `:frame :rf/xray` (the panels mount under `(rf/with-frame
  :rf/xray ...)`), but they fire OUTSIDE any host dispatch — so
  without a filter they'd bucket as `:ungrouped :ungrounded` in
  Xray's own trace pipeline and drown the host event the user
  actually clicked.

  The filter sits at INGEST in `trace-collector/collect-trace!` so
  the frameless secondary ring + the mirror dispatch never record
  the noise in the first place; readers stay simple. Pure-data +
  JVM-runnable predicate (`xray-internal-event?`) lives in
  `self_noise.cljc` per the rf2-43koh / rf2-3g9nw D4=a ruling; the
  CLJS-side wiring lock drives `collect-trace!` end-to-end."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.self-noise :as self-noise]
            #?(:cljs [day8.re-frame2-xray.trace-collector :as trace-collector])))

;; ---- fixtures -----------------------------------------------------------

(defn- reset-state [test-fn]
  ;; Each test starts with the defaults: flag off, counter empty,
  ;; rings empty. Same shape as `sensitive-trace-cljs-test`.
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!))
  (test-fn)
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!)))

(use-fixtures :each reset-state)

;; ---- predicate ----------------------------------------------------------

(deftest xray-internal-event?-top-level-frame
  (testing ":frame at top level — true iff = :rf/xray"
    (is (true?  (self-noise/xray-internal-event? {:frame :rf/xray})))
    (is (false? (self-noise/xray-internal-event? {:frame :rf/default})))
    (is (false? (self-noise/xray-internal-event? {:frame :rf/main})))
    (is (false? (self-noise/xray-internal-event? {:frame nil})))
    (is (false? (self-noise/xray-internal-event? {})))))

(deftest xray-internal-event?-tags-frame-fallback
  (testing ":frame absent at top, present under :tags — fall-back applies"
    (is (true?  (self-noise/xray-internal-event?
                  {:tags {:frame :rf/xray}})))
    (is (false? (self-noise/xray-internal-event?
                  {:tags {:frame :rf/default}})))
    (is (false? (self-noise/xray-internal-event?
                  {:tags {:frame nil}})))
    (is (false? (self-noise/xray-internal-event?
                  {:tags {}}))))
  (testing "top-level wins when both present"
    (is (true?  (self-noise/xray-internal-event?
                  {:frame :rf/xray :tags {:frame :rf/default}})))
    (is (false? (self-noise/xray-internal-event?
                  {:frame :rf/default :tags {:frame :rf/xray}})))))

(deftest xray-internal-event?-realistic-shapes
  (testing "shapes mirroring real :rf.sub/run + :rf.view/render envelopes"
    ;; Layer-1 sub-read inside Xray's panel — :frame lives under :tags
    ;; per re-frame.subs/validate-and-trace.
    (is (true?  (self-noise/xray-internal-event?
                  {:operation :rf.sub/run :op-type :rf.sub
                   :id 17 :time 1000
                   :tags {:rf.sub/id  :rf.xray/trace-buffer
                          :rf.sub/query-v [:rf.xray/trace-buffer]
                          :frame   :rf/xray}})))
    ;; View re-render from a Xray panel — :frame at top level per
    ;; re-frame.views/emit-render-trace!.
    (is (true?  (self-noise/xray-internal-event?
                  {:operation :rf.view/render :op-type :rf.view
                   :id 18 :time 1001
                   :frame :rf/xray
                   :tags  {:rf.view/render-key 42 :frame :rf/xray}})))
    ;; Host event — must not be filtered.
    (is (false? (self-noise/xray-internal-event?
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
;;
;; Post-rf2-43koh the listener body lives in `trace_collector.cljs`
;; and frame-bound events ride the framework's per-frame rings. The
;; self-noise filter still sits at the listener boundary — `:rf/xray`
;; events never reach either the per-frame rings (the frame is
;; suppressed by `:rf.trace/frame-no-emit?` at source) nor the
;; frameless secondary ring.

#?(:cljs
   (defn- xray-sub-read-event []
     ;; Realistic `:rf.sub/run` emit from a Xray panel re-rendering in
     ;; response to host activity. The sub fires under
     ;; `(rf/with-frame :rf/xray ...)` so its trace envelope carries
     ;; `:frame :rf/xray`. This is the noise rf2-xs8vu eliminates.
     {:operation :rf.sub/run :op-type :rf.sub
      :id 2 :time 1001
      :tags {:rf.sub/id  :rf.xray/trace-buffer
             :rf.sub/query-v [:rf.xray/trace-buffer]
             :frame   :rf/xray}}))

#?(:cljs
   (defn- xray-view-render-event []
     ;; Realistic `:rf.view/render` emit from a Xray panel re-rendering.
     ;; `:frame` is hoisted top-level per re-frame.views/emit-render-trace!.
     {:operation :rf.view/render :op-type :rf.view
      :id 3 :time 1002
      :frame :rf/xray
      :tags  {:rf.view/render-key 42 :frame :rf/xray}}))

#?(:cljs
   (deftest collect-trace-drops-xray-sub-reads
     (testing "Xray-frame :rf.sub/run events MUST NOT enter the rings"
       (trace-collector/collect-trace! (xray-sub-read-event))
       (is (empty? (trace-collector/frameless-events))
           "the self-induced sub-read drowns the host event under :ungrouped if recorded")
       (is (= 0 (config/suppressed-count))
           "the REDACTED counter must NOT bump — this is structural noise, not privacy"))))

#?(:cljs
   (deftest collect-trace-drops-xray-view-renders
     (testing "Xray-frame :rf.view/render events MUST NOT enter the rings"
       (trace-collector/collect-trace! (xray-view-render-event))
       (is (empty? (trace-collector/frameless-events))
           "self-induced view re-renders are dropped")
       (is (= 0 (config/suppressed-count))
           "no REDACTED bump for structural self-noise"))))

#?(:cljs
   (deftest collect-trace-filter-applies-to-tags-frame
     (testing "Xray-internal events with :frame under :tags only are also dropped"
       ;; Some emit sites leave :frame under :tags rather than hoisting
       ;; top-level (e.g. :rf.sub/run via re-frame.subs/validate-and-trace).
       ;; The filter MUST cover both shapes.
       (trace-collector/collect-trace!
         {:operation :rf.sub/run :op-type :rf.sub
          :id 4 :time 1003
          :tags {:rf.sub/id :rf.xray/cascades :frame :rf/xray}})
       (is (empty? (trace-collector/frameless-events))))))

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
  (testing "true iff event-id is a keyword in the `rf.xray` ns or a `rf.xray.*` sub-ns"
    (is (true?  (self-noise/xray-internal-event-id? :rf.xray/focus-cascade)))
    (is (true?  (self-noise/xray-internal-event-id? :rf.xray/select-tab)))
    (is (true?  (self-noise/xray-internal-event-id? :rf.xray/open-settings)))
    (is (true?  (self-noise/xray-internal-event-id? :rf.xray/sync-trace-buffer)))
    (testing "sub-namespaced internal events also classify (rf2-y8iqe)"
      ;; Xray registers + dispatches many internal events under
      ;; SUB-namespaces of rf.xray. The palette lowers
      ;; `:palette/select-static-tab` into a FRAMELESS
      ;; `[:dispatch [:rf.xray.static/select-tab …]]`
      ;; (palette/events.cljs:304) — that chain-resolves onto
      ;; :rf/default, so the frame gate misses it and this data-layer
      ;; predicate is the only thing standing between it and the host's
      ;; user-facing :rf.xray/cascades L2 list. Exact `= "rf.xray"`
      ;; equality missed it; the segment-prefix match catches it.
      (is (true?  (self-noise/xray-internal-event-id? :rf.xray.static/select-tab)))
      (is (true?  (self-noise/xray-internal-event-id? :rf.xray.epoch/toggle-row-expand)))
      (is (true?  (self-noise/xray-internal-event-id? :rf.xray.filters/persist)))
      (is (true?  (self-noise/xray-internal-event-id? :rf.xray.edn-inspector/zoom)))
      (is (true?  (self-noise/xray-internal-event-id? :rf.xray.column-widths/persist))))
    (testing "user-app events stay false"
      (is (false? (self-noise/xray-internal-event-id? :cart/add-item)))
      (is (false? (self-noise/xray-internal-event-id? :checkout/start)))
      (is (false? (self-noise/xray-internal-event-id? :user/click))))
    (testing "framework + sibling reserved namespaces stay false"
      ;; The filter is narrow: only `rf.xray` + its `rf.xray.*`
      ;; sub-namespaces. Sibling reserved namespaces (`:rf/init`,
      ;; `:rf.epoch/*`) are NOT Xray-internal — they're framework /
      ;; epoch surface and must remain visible in the user-facing
      ;; cascade list.
      (is (false? (self-noise/xray-internal-event-id? :rf/init)))
      (is (false? (self-noise/xray-internal-event-id? :rf.epoch/begin)))
      (is (false? (self-noise/xray-internal-event-id? :rf.story/something))))
    (testing "nil-safe + non-keyword inputs"
      (is (false? (self-noise/xray-internal-event-id? nil)))
      (is (false? (self-noise/xray-internal-event-id? "rf.xray/foo")))
      (is (false? (self-noise/xray-internal-event-id? :unnamespaced)))
      (is (false? (self-noise/xray-internal-event-id? 42))))
    (testing "namespace prefix collision guard — segment boundary, not substring"
      ;; A keyword whose namespace shares the leading characters
      ;; `rf.xray` but is NOT exactly `rf.xray` nor a `rf.xray.`
      ;; sub-namespace (e.g. `:rf.xray-test/x`, `:rf.xray-foo/y`,
      ;; `:rf.xrayon/z`) must NOT match — the match is on the dot
      ;; segment boundary, not a naive substring/character prefix. An
      ;; app namespace that merely embeds `rf.xray` mid-string
      ;; (`:my.rf.xray-ish/foo`) is also a host event, not Xray's.
      (is (false? (self-noise/xray-internal-event-id? :rf.xray-test/x)))
      (is (false? (self-noise/xray-internal-event-id? :rf.xray-foo/y)))
      (is (false? (self-noise/xray-internal-event-id? :rf.xrayon/z)))
      (is (false? (self-noise/xray-internal-event-id? :my.rf.xray-ish/foo))))))

(deftest xray-internal-cascade?-event-vector-head
  (testing "true iff the cascade's :event vector's head is xray-internal"
    (is (true?  (self-noise/xray-internal-cascade?
                  {:dispatch-id 1
                   :event       [:rf.xray/focus-cascade 99]})))
    (is (true?  (self-noise/xray-internal-cascade?
                  {:dispatch-id 2
                   :event       [:rf.xray/select-tab :event]})))
    (is (true?  (self-noise/xray-internal-cascade?
                  {:dispatch-id 3
                   :event       [:rf.xray/open-settings]}))
        "single-element event vector (no payload) still classifies")
    (testing "frameless sub-namespaced internal cascade is filtered (rf2-y8iqe)"
      ;; The concrete leak path: palette/events.cljs:304 lowers
      ;; `:palette/select-static-tab` into a FRAMELESS
      ;; `[:dispatch [:rf.xray.static/select-tab :machines]]`. With the
      ;; old exact-equality predicate this cascade landed on :rf/default
      ;; and surfaced as a spurious row in the host's :rf.xray/cascades
      ;; L2 list. The segment-prefix match closes the hole.
      (is (true?  (self-noise/xray-internal-cascade?
                    {:dispatch-id 9
                     :event       [:rf.xray.static/select-tab :machines]})))
      (is (true?  (self-noise/xray-internal-cascade?
                    {:dispatch-id 10
                     :event       [:rf.xray.epoch/toggle-row-expand 3]})))))
  (testing "user-app cascades stay false"
    (is (false? (self-noise/xray-internal-cascade?
                  {:dispatch-id 4
                   :event       [:cart/add-item {:item-id "apple"}]})))
    (is (false? (self-noise/xray-internal-cascade?
                  {:dispatch-id 5
                   :event       [:user/click]}))))
  (testing ":ungrouped + event-less cascades stay false"
    ;; `cascade-has-event?` (rf2-639lc) handles the :ungrouped bucket
    ;; at the L2 boundary; the xray-internal filter sits orthogonal.
    (is (false? (self-noise/xray-internal-cascade?
                  {:dispatch-id :ungrouped :event nil})))
    (is (false? (self-noise/xray-internal-cascade?
                  {:dispatch-id 6 :event []}))))
  (testing "malformed shapes don't throw"
    (is (false? (self-noise/xray-internal-cascade? {})))
    (is (false? (self-noise/xray-internal-cascade?
                  {:dispatch-id 7 :event "not-a-vector"})))))
