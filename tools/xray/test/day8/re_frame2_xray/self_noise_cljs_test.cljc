(ns day8.re-frame2-xray.self-noise-cljs-test
  "Tests for the Xray self-noise filter.

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
  `self_noise.cljc`; the CLJS-side wiring lock drives `collect-trace!`
  end-to-end."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.self-noise :as self-noise]
            #?(:cljs [day8.re-frame2-xray.trace-collector :as trace-collector])))

;; ---- fixtures -----------------------------------------------------------

(defn- reset-state [test-fn]
  ;; Each test starts with the defaults: egress profile redacting, counter
  ;; empty, rings empty. Same shape as `sensitive-trace-cljs-test`.
  (config/set-egress-profile! config/default-egress-profile)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!))
  (test-fn)
  (config/set-egress-profile! config/default-egress-profile)
  (config/reset-suppressed-count!)
  #?(:cljs (trace-collector/reset-for-test!)))

(use-fixtures :each reset-state)

;; ---- predicate ----------------------------------------------------------

(deftest xray-internal-event?-ignores-stray-top-level-frame
  ;; A stray top-level `:frame` is NOT a public raw-event shape;
  ;; the canonical reader ignores it. A raw event whose `[:tags :frame]` is
  ;; absent reads as frameless regardless of a top-level `:frame`.
  (testing "top-level-only :frame is ignored (raw events frame under :tags)"
    (is (false? (self-noise/xray-internal-event? {:frame :rf/xray})))
    (is (false? (self-noise/xray-internal-event? {:frame :rf/default}))))
  (testing "[:tags :frame] is authoritative when both are present"
    (is (true?  (self-noise/xray-internal-event?
                  {:frame :rf/default :tags {:frame :rf/xray}})))
    (is (false? (self-noise/xray-internal-event?
                  {:frame :rf/xray :tags {:frame :rf/default}})))))

;; ---- collect-trace! end-to-end wiring (CLJS only) -----------------------
;;
;; The pure-data predicate above is JVM-runnable; the wiring tests
;; below run only under CLJS because `collect-trace!` reads
;; `re-frame.interop/debug-enabled?` (true under the CLJS dev target,
;; false / unbound under JVM — the same pattern
;; `sensitive_trace_cljs_test.cljc` uses for its `collect-trace!`
;; assertions).
;;
;; The listener body lives in `trace_collector.cljs`
;; and frame-bound events ride the framework's per-frame rings. The
;; self-noise filter sits at the listener boundary — `:rf/xray`
;; events never reach either the per-frame rings (the frame is
;; suppressed by `:rf.trace/frame-no-emit?` at source) nor the
;; frameless secondary ring.

#?(:cljs
   (defn- xray-view-render-event []
     ;; Realistic `:rf.view/render` emit from a Xray panel re-rendering.
     ;; `:frame` rides under `:tags` per re-frame.views/emit-view-render-trace!
     ;; (the emit passes it in the tags map; build-event never hoists it
     ;; top-level).
     {:operation :rf.view/render :op-type :rf.view
      :id 3 :time 1002
      :tags  {:rf.view/render-key 42 :frame :rf/xray}}))

#?(:cljs
   (deftest collect-trace-drops-xray-view-renders
     (testing "Xray-frame :rf.view/render events MUST NOT enter the rings"
       (trace-collector/collect-trace! (xray-view-render-event))
       (is (empty? (trace-collector/frameless-events))
           "self-induced view re-renders are dropped")
       (is (= 0 (config/suppressed-count))
           "no REDACTED bump for structural self-noise"))))

;; ---- xray-internal event-id guard --------------------------------------
;;
;; Pure-data sibling of `xray-internal-event?`. The ingest filter above
;; catches every trace event emitted INSIDE `(rf/with-frame :rf/xray
;; ...)`. The data-layer filter below catches every CASCADE whose
;; event-id is in the `rf.xray` namespace — covering :rf.xray/* events
;; dispatched WITHOUT `:frame :rf/xray` (those land on the host frame
;; and slip past the ingest filter; the data-layer guard at the
;; `:rf.xray/event-bundles` sub closes the hole structurally).
;;
;; Predicate tests run under both CLJ + CLJS (pure data, no
;; collect-trace! plumbing).

(deftest xray-internal-event-id?-keyword-namespace
  (testing "true iff event-id is a keyword in the `rf.xray` ns or a `rf.xray.*` sub-ns"
    (is (true?  (self-noise/xray-internal-event-id? :rf.xray/focus-event)))
    (testing "sub-namespaced internal events also classify"
      ;; Xray registers + dispatches many internal events under
      ;; SUB-namespaces of rf.xray. The palette lowers
      ;; `:palette/select-static-tab` into a FRAMELESS
      ;; `[:dispatch [:rf.xray.static/select-tab …]]`
      ;; (palette/events.cljs) — that chain-resolves onto
      ;; :rf/default, so the frame gate misses it and this data-layer
      ;; predicate is the only thing standing between it and the host's
      ;; user-facing :rf.xray/event-bundles L2 list. Exact `= "rf.xray"`
      ;; equality would miss it; the segment-prefix match catches it.
      (is (true?  (self-noise/xray-internal-event-id? :rf.xray.static/select-tab))))
    (testing "user-app events stay false"
      (is (false? (self-noise/xray-internal-event-id? :cart/add-item))))
    (testing "framework + sibling reserved namespaces stay false"
      ;; The filter is narrow: only `rf.xray` + its `rf.xray.*`
      ;; sub-namespaces. Sibling reserved namespaces (`:rf/init`,
      ;; `:rf.epoch/*`) are NOT Xray-internal — they're framework /
      ;; epoch surface and must remain visible in the user-facing
      ;; cascade list.
      (is (false? (self-noise/xray-internal-event-id? :rf/init)))
      (is (false? (self-noise/xray-internal-event-id? :rf.epoch/begin))))
    (testing "nil-safe + non-keyword inputs"
      (is (false? (self-noise/xray-internal-event-id? nil)))
      (is (false? (self-noise/xray-internal-event-id? "rf.xray/foo")))
      (is (false? (self-noise/xray-internal-event-id? :unnamespaced))))
    (testing "namespace prefix collision guard — segment boundary, not substring"
      ;; A keyword whose namespace shares the leading characters
      ;; `rf.xray` but is NOT exactly `rf.xray` nor a `rf.xray.`
      ;; sub-namespace (e.g. `:rf.xray-test/x`, `:rf.xray-foo/y`,
      ;; `:rf.xrayon/z`) must NOT match — the match is on the dot
      ;; segment boundary, not a naive substring/character prefix. An
      ;; app namespace that merely embeds `rf.xray` mid-string
      ;; (`:my.rf.xray-ish/foo`) is also a host event, not Xray's.
      (is (false? (self-noise/xray-internal-event-id? :rf.xray-test/x)))
      (is (false? (self-noise/xray-internal-event-id? :rf.xrayon/z)))
      (is (false? (self-noise/xray-internal-event-id? :my.rf.xray-ish/foo))))))

(deftest xray-internal-event-bundle?-event-vector-head
  (testing "true iff the cascade's :event vector's head is xray-internal"
    (is (true?  (self-noise/xray-internal-event-bundle?
                  {:dispatch-id 1
                   :event       [:rf.xray/focus-event 99]})))
    (is (true?  (self-noise/xray-internal-event-bundle?
                  {:dispatch-id 3
                   :event       [:rf.xray/open-settings]}))
        "single-element event vector (no payload) still classifies")
    (testing "frameless sub-namespaced internal cascade is filtered"
      ;; The concrete leak path: palette/events.cljs lowers
      ;; `:palette/select-static-tab` into a FRAMELESS
      ;; `[:dispatch [:rf.xray.static/select-tab :machines]]`. That cascade
      ;; lands on :rf/default, and an exact-equality predicate would
      ;; surface it as a spurious row in the host's :rf.xray/event-bundles
      ;; L2 list. The segment-prefix match closes the hole.
      (is (true?  (self-noise/xray-internal-event-bundle?
                    {:dispatch-id 9
                     :event       [:rf.xray.static/select-tab :machines]})))))
  (testing "user-app cascades stay false"
    (is (false? (self-noise/xray-internal-event-bundle?
                  {:dispatch-id 4
                   :event       [:cart/add-item {:item-id "apple"}]}))))
  (testing ":ungrouped + event-less cascades stay false"
    ;; `event-bundle-has-event?` handles the :ungrouped bucket
    ;; at the L2 boundary; the xray-internal filter sits orthogonal.
    (is (false? (self-noise/xray-internal-event-bundle?
                  {:dispatch-id :ungrouped :event nil})))
    (is (false? (self-noise/xray-internal-event-bundle?
                  {:dispatch-id 6 :event []}))))
  (testing "malformed shapes don't throw"
    (is (false? (self-noise/xray-internal-event-bundle? {})))
    (is (false? (self-noise/xray-internal-event-bundle?
                  {:dispatch-id 7 :event "not-a-vector"})))))

;; ---- filtered-event-bundles — the shared group+strip projection --------
;;
;; `filtered-event-bundles` is the ONE home for the `(into [] (remove
;; xray-internal-event-bundle?) (group-by-event buffer))` pairing that
;; spine/db->event-bundles, the reactive :rf.xray/event-bundles sub
;; (registry), and the first-mount seed (mount) all need; it makes their
;; agreement structural. These tests pin the strip behaviour and the
;; vector return.

(defn- dispatched-event
  "A minimal `:rf.event/dispatched` trace event — the cascade root
  `group-by-event` keys on. `event-v` is the dispatched event vector;
  `frame` the owning frame id."
  [id dispatch-id event-v frame]
  {:id id :op-type :rf.event :operation :rf.event/dispatched
   :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v event-v :frame frame}})

(deftest filtered-event-bundles-strips-xray-internal-keeps-host
  (testing "a buffer carrying one host cascade + one Xray-internal
            cascade projects to ONLY the host cascade"
    (let [buffer [(dispatched-event 1 100 [:counter/inc]          :below)
                  (dispatched-event 2 101 [:rf.xray/select-tab :a] :rf/default)]
          out    (self-noise/filtered-event-bundles buffer)]
      (is (= [[:counter/inc]] (mapv :event out))
          "only the host :counter/inc cascade survives; the frameless
           :rf.xray/* cascade is stripped"))))

(deftest filtered-event-bundles-empty-buffer
  (testing "empty buffer → empty vector (always returns a vector)"
    (is (= [] (self-noise/filtered-event-bundles [])))
    (is (vector? (self-noise/filtered-event-bundles [])))))
