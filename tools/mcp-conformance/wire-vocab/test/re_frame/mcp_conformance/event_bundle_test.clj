(ns re-frame.mcp-conformance.event-bundle-test
  "Event-bundle wire-shape gate (rf2-mscih). Split out of
  `wire_vocab_test.clj` by rf2-7ckmwx.

  re-frame2-pair-mcp's streaming `subscribe` tool ships matched events
  under one of two payload-slots per the sub's topic:

    - `:event-bundles` — vector of event bundles on `:trace`/`:fx`/`:error`
                    (event-bundle delivery, rf2-mscih). Each bundle
                    matches `(rf/trace-buffer frame-id)` shape per
                    spec/009 §Event-bundle projection + Tool-Pair §Reading
                    the per-frame trace ring.
    - `:events`   — flat vector on `:epoch` and `:frameless` (no
                    event-bundle structure to bundle).

  Pre-rf2-mscih every topic shipped under `:events`. The reshape is the
  load-bearing change that lets AI consumers reason about cause→effect
  at `:dispatch-id` granularity without re-folding raw streams.

  The gate shape mirrors the per-marker pattern:
    1. A canonical Malli schema for one event-bundle.
    2. A re-frame2-pair fixture exercising the schema with realistic
       event-bundle slots.
    3. (No source-text pin — the event-bundle SHAPE is the framework's
       `(rf/trace-buffer frame-id)` projection, not a re-frame2-pair-mcp
       literal. The MCP server walks the drain envelope's `:event-bundles`
       slot transparently.)"
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core   :as m]
            [malli.error  :as me]))

(defn- not-ungrouped?
  "True for any `:dispatch-id` value EXCEPT the `:ungrouped` sentinel.
  `:ungrouped` is `group-by-event`'s marker for frameless events; the
  runtime filters those out of event-bundle topics before bundling, so
  an event-bundle on the wire MUST NOT carry it. Any other value (a
  typed/numeric dispatch id) is application-shaped and accepted."
  [v]
  (not= :ungrouped v))

(def EventBundle
  "Event-bundle shape per `(rf/trace-buffer frame-id)` (spec/009
  §Event-bundle projection + Tool-Pair §Reading the per-frame trace ring).
  Open map — additive slots (e.g. a future `:flow` domino, a
  `:timing-ms` rollup) compose without a schema bump.

  Required slots:
    :dispatch-id        — the dispatch id. Any value EXCEPT the
                          `:ungrouped` sentinel (rf2-mscih filters
                          frameless events before bundling); the
                          event-bundle topics never ship `:ungrouped`.
                          Enforced at the schema level by the
                          `not-ungrouped?` predicate (rf2-voux7 finding 3):
                          the pre-fix `[:dispatch-id :any]` let a regression
                          that routed frameless / `:ungrouped` shapes onto
                          an event-bundle topic validate, breaking the
                          event-vs-frameless wire distinction this gate
                          claims to pin.
    :trace-events       — raw events for the run (vector). May
                          be empty if every event was elided / dropped.
    :event              — event vector or nil (the `:rf.event/dispatched`
                          `:tags :rf.event/v` slot).
    :effects/:subs/:renders/:other — projection domino slots (vectors).

  Optional slots:
    :frame              — frame-id keyword (nil when not frame-qualified).
    :dispatched         — the full `:rf.event/dispatched` trace event.
    :handler            — the `:rf.event/run-end` event.
    :fx                 — the `:rf.fx/do-fx` event.
    :parent-dispatch-id — causal-parent link (nil for a root event)."
  [:map
   {:closed false}
   [:dispatch-id        [:and :any [:fn not-ungrouped?]]]
   [:trace-events       [:sequential :any]]
   [:event              [:maybe [:sequential :any]]]
   [:effects            [:sequential :any]]
   [:subs               [:sequential :any]]
   [:renders            [:sequential :any]]
   [:other              [:sequential :any]]
   [:frame              {:optional true} :any]
   [:dispatched         {:optional true} :any]
   [:handler            {:optional true} :any]
   [:fx                 {:optional true} :any]
   [:parent-dispatch-id {:optional true} :any]])

(def EventBundleVector
  "A drain tick's `:event-bundles` slot — a vector of event bundles."
  [:sequential EventBundle])

(def ^:private re-frame2-pair-event-bundle-fixture
  "Canonical event-bundle shape for a single run — what
  `subscribe`'s `:trace`/`:fx`/`:error` topics ship inside the
  `:event-bundles` slot per tick (rf2-mscih)."
  {:dispatch-id        17
   :frame              :rf/default
   :event              [:cart/add-item {:sku "abc"}]
   :dispatched         {:operation :rf.event/dispatched
                        :op-type :rf.event
                        :tags {:rf.trace/dispatch-id 17
                               :rf.event/v [:cart/add-item {:sku "abc"}]
                               :rf.event/origin :app}}
   :handler            {:operation :rf.event/run-end
                        :op-type :rf.event
                        :tags {:rf.trace/dispatch-id 17}}
   :fx                 {:operation :rf.fx/do-fx
                        :op-type :rf.fx
                        :tags {:rf.trace/dispatch-id 17}}
   :effects            [{:operation :rf.fx/handled :op-type :rf.fx
                         :tags {:rf.trace/dispatch-id 17 :fx-id :db}}]
   :subs               [{:operation :rf.sub/run :op-type :rf.sub
                         :tags {:rf.trace/dispatch-id 17}}]
   :renders            [{:operation :rf.view/render :op-type :rf.view
                         :tags {:rf.trace/dispatch-id 17}}]
   :other              []
   :trace-events       [{:operation :rf.event/dispatched :op-type :rf.event
                         :tags {:rf.trace/dispatch-id 17}}]
   :parent-dispatch-id nil})

(def ^:private re-frame2-pair-cross-frame-event-fixture
  "Cross-frame event reconstruction sample (rf2-mscih) — two bundles
  carrying the same `:dispatch-id` from two different frames. The
  consumer merges by `:dispatch-id` to reconstruct the unified
  timeline per Tool-Pair §Cross-frame event reconstruction."
  [{:dispatch-id 42
    :frame       :rf/default
    :event       [:user/login {:id "u1"}]
    :effects     []
    :subs        []
    :renders     []
    :other       []
    :trace-events [{:operation :rf.event/dispatched
                    :op-type :rf.event
                    :tags {:rf.trace/dispatch-id 42 :frame :rf/default}}]
    :parent-dispatch-id nil}
   {:dispatch-id 42
    :frame       :stories
    :event       nil
    :effects     []
    :subs        [{:operation :rf.sub/run :op-type :rf.sub
                   :tags {:rf.trace/dispatch-id 42 :frame :stories}}]
    :renders     []
    :other       []
    :trace-events [{:operation :rf.sub/run :op-type :rf.sub
                    :tags {:rf.trace/dispatch-id 42 :frame :stories}}]
    :parent-dispatch-id nil}])

(deftest event-bundle-fixture-conforms-to-schema
  (is (m/validate EventBundle re-frame2-pair-event-bundle-fixture)
      (str "Event-bundle fixture failed schema validation:\n"
           (me/humanize
             (m/explain EventBundle re-frame2-pair-event-bundle-fixture)))))

(deftest cross-frame-event-bundles-conform
  ;; The cross-frame reconstruction case (rf2-mscih) — a vector of
  ;; bundles sharing `:dispatch-id` across frames. Every bundle MUST
  ;; validate; consumers merge by `:dispatch-id`.
  (is (m/validate EventBundleVector re-frame2-pair-cross-frame-event-fixture)
      (str "Cross-frame event-bundle vector failed schema validation:\n"
           (me/humanize
             (m/explain EventBundleVector re-frame2-pair-cross-frame-event-fixture))))
  (testing "merge-by-:dispatch-id reconstructs a unified event timeline"
    (let [by-id (group-by :dispatch-id re-frame2-pair-cross-frame-event-fixture)]
      (is (= 1 (count by-id))
          "the two bundles share one :dispatch-id (the cross-frame contract)")
      (is (= 42 (first (keys by-id)))))))

(deftest event-bundle-rejects-frameless-shape
  ;; An `:ungrouped` `:dispatch-id` (`:ungrouped` is the
  ;; `group-by-event` sentinel for frameless events) MUST NOT ship on
  ;; the event-bundle wire — the runtime filters frameless events
  ;; out of event-bundle topics. Per rf2-voux7 finding 3 the schema
  ;; now ENFORCES this directly via the `not-ungrouped?` predicate, in
  ;; addition to the positive topic-routing pin below: the pre-fix
  ;; `[:dispatch-id :any]` let a regression that routed frameless /
  ;; `:ungrouped` shapes onto an event-bundle topic validate, masking
  ;; the event-vs-frameless distinction this gate claims to pin.
  (testing "schema REJECTS an :ungrouped dispatch-id on an event-bundle"
    (let [frameless-bundle (assoc re-frame2-pair-event-bundle-fixture
                                  :dispatch-id :ungrouped)]
      (is (not (m/validate EventBundle frameless-bundle))
          (str "EventBundle MUST reject :dispatch-id :ungrouped — the "
               ":ungrouped sentinel marks frameless events the runtime "
               "filters OUT of event-bundle topics (rf2-mscih / "
               "rf2-voux7 finding 3). A bundle carrying it is a "
               "frameless-shape leak onto the event wire."))))
  (testing "schema REJECTS an :ungrouped bundle inside an EventBundleVector"
    (let [leaky-vec [re-frame2-pair-event-bundle-fixture
                     (assoc re-frame2-pair-event-bundle-fixture
                            :dispatch-id :ungrouped)]]
      (is (not (m/validate EventBundleVector leaky-vec))
          "an :event-bundles vector with even one :ungrouped bundle MUST fail")))
  (testing "schema ACCEPTS a typed/numeric dispatch id (guard against over-tightening)"
    (is (m/validate EventBundle re-frame2-pair-event-bundle-fixture)
        "the canonical numeric :dispatch-id 17 must still validate")
    (is (m/validate EventBundle
                    (assoc re-frame2-pair-event-bundle-fixture
                           :dispatch-id :cart/checkout))
        "a keyword dispatch id (not :ungrouped) is application-shaped and accepted")))

;; rf2-hvn83u (LOW): the former "event-bundle topics ship under
;; :event-bundles; frameless under :events" testing block was DROPPED — it
;; asserted `vector?` / `not contains?` over locally hand-built literal
;; maps (`cascade-tick` / `frameless-tick`), which is tautological: the
;; values were constructed as vectors / without the key the assertion
;; checked for, so the block could never fail and observed no real
;; emitter. The genuine event-vs-frameless distinction is pinned by the
;; two schema-rejection `testing` blocks above (`:ungrouped` rejected on an
;; EventBundle and inside an EventBundleVector via the `not-ungrouped?`
;; predicate). Tying the dropped tautology to a real progress-notification
;; subscribe-emit projection would require the heavyweight live emitter
;; and is out of scope for the fixture-drift fix; the schema gates carry
;; the contract.
