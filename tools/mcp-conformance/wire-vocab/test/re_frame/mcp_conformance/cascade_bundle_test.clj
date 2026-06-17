(ns re-frame.mcp-conformance.cascade-bundle-test
  "Cascade-bundle wire-shape gate (rf2-mscih). Split out of
  `wire_vocab_test.clj` by rf2-7ckmwx.

  re-frame2-pair-mcp's streaming `subscribe` tool ships matched events
  under one of two payload-slots per the sub's topic:

    - `:cascades` — vector of cascade bundles on `:trace`/`:fx`/`:error`
                    (cascade-bundle delivery, rf2-mscih). Each bundle
                    matches `(rf/trace-buffer frame-id)` shape per
                    spec/009 §Cascade projection + Tool-Pair §Reading
                    the per-frame trace ring.
    - `:events`   — flat vector on `:epoch` and `:frameless` (no
                    cascade structure to bundle).

  Pre-rf2-mscih every topic shipped under `:events`. The reshape is the
  load-bearing change that lets AI consumers reason about cause→effect
  at `:dispatch-id` granularity without re-folding raw streams.

  The gate shape mirrors the per-marker pattern:
    1. A canonical Malli schema for one cascade-bundle.
    2. A re-frame2-pair fixture exercising the schema with realistic
       cascade-bundle slots.
    3. (No source-text pin — the cascade-bundle SHAPE is the framework's
       `(rf/trace-buffer frame-id)` projection, not a re-frame2-pair-mcp
       literal. The MCP server walks the drain envelope's `:cascades`
       slot transparently.)"
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core   :as m]
            [malli.error  :as me]))

(defn- not-ungrouped?
  "True for any `:dispatch-id` value EXCEPT the `:ungrouped` sentinel.
  `:ungrouped` is `group-cascades`' marker for frameless events; the
  runtime filters those out of cascade-bundle topics before bundling, so
  a cascade-bundle on the wire MUST NOT carry it. Any other value (a
  typed/numeric cascade id) is application-shaped and accepted."
  [v]
  (not= :ungrouped v))

(def CascadeBundle
  "Cascade-bundle shape per `(rf/trace-buffer frame-id)` (spec/009
  §Cascade projection + Tool-Pair §Reading the per-frame trace ring).
  Open map — additive slots (e.g. a future `:flow` domino, a
  `:timing-ms` rollup) compose without a schema bump.

  Required slots:
    :dispatch-id        — the cascade id. Any value EXCEPT the
                          `:ungrouped` sentinel (rf2-mscih filters
                          frameless events before bundling); the
                          cascade-bundle topics never ship `:ungrouped`.
                          Enforced at the schema level by the
                          `not-ungrouped?` predicate (rf2-voux7 finding 3):
                          the pre-fix `[:dispatch-id :any]` let a regression
                          that routed frameless / `:ungrouped` shapes onto
                          a cascade-bundle topic validate, breaking the
                          cascade-vs-frameless wire distinction this gate
                          claims to pin.
    :trace-events       — raw events for the cascade (vector). May
                          be empty if every event was elided / dropped.
    :event              — event vector or nil (the `:rf.event/dispatched`
                          `:tags :rf.event/v` slot).
    :effects/:subs/:renders/:other — projection domino slots (vectors).

  Optional slots:
    :frame              — frame-id keyword (nil when not frame-qualified).
    :dispatched         — the full `:rf.event/dispatched` trace event.
    :handler            — the `:rf.event/run-end` event.
    :fx                 — the `:rf.fx/do-fx` event.
    :parent-dispatch-id — causal-parent link (nil for a root cascade)."
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

(def CascadeBundleVector
  "A drain tick's `:cascades` slot — a vector of cascade bundles."
  [:sequential CascadeBundle])

(def ^:private re-frame2-pair-cascade-bundle-fixture
  "Canonical cascade-bundle shape for a single cascade — what
  `subscribe`'s `:trace`/`:fx`/`:error` topics ship inside the
  `:cascades` slot per tick (rf2-mscih)."
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

(def ^:private re-frame2-pair-cross-frame-cascade-fixture
  "Cross-frame cascade reconstruction sample (rf2-mscih) — two bundles
  carrying the same `:dispatch-id` from two different frames. The
  consumer merges by `:dispatch-id` to reconstruct the unified
  timeline per Tool-Pair §Cross-frame cascade reconstruction."
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

(deftest cascade-bundle-fixture-conforms-to-schema
  (is (m/validate CascadeBundle re-frame2-pair-cascade-bundle-fixture)
      (str "Cascade-bundle fixture failed schema validation:\n"
           (me/humanize
             (m/explain CascadeBundle re-frame2-pair-cascade-bundle-fixture)))))

(deftest cross-frame-cascade-bundles-conform
  ;; The cross-frame reconstruction case (rf2-mscih) — a vector of
  ;; bundles sharing `:dispatch-id` across frames. Every bundle MUST
  ;; validate; consumers merge by `:dispatch-id`.
  (is (m/validate CascadeBundleVector re-frame2-pair-cross-frame-cascade-fixture)
      (str "Cross-frame cascade-bundle vector failed schema validation:\n"
           (me/humanize
             (m/explain CascadeBundleVector re-frame2-pair-cross-frame-cascade-fixture))))
  (testing "merge-by-:dispatch-id reconstructs a unified cascade timeline"
    (let [by-id (group-by :dispatch-id re-frame2-pair-cross-frame-cascade-fixture)]
      (is (= 1 (count by-id))
          "the two bundles share one :dispatch-id (the cross-frame contract)")
      (is (= 42 (first (keys by-id)))))))

(deftest cascade-bundle-rejects-frameless-shape
  ;; An `:ungrouped` `:dispatch-id` (`:ungrouped` is the
  ;; `group-cascades` sentinel for frameless events) MUST NOT ship on
  ;; the cascade-bundle wire — the runtime filters frameless events
  ;; out of cascade-bundle topics. Per rf2-voux7 finding 3 the schema
  ;; now ENFORCES this directly via the `not-ungrouped?` predicate, in
  ;; addition to the positive topic-routing pin below: the pre-fix
  ;; `[:dispatch-id :any]` let a regression that routed frameless /
  ;; `:ungrouped` shapes onto a cascade-bundle topic validate, masking
  ;; the cascade-vs-frameless distinction this gate claims to pin.
  (testing "schema REJECTS an :ungrouped dispatch-id on a cascade-bundle"
    (let [frameless-bundle (assoc re-frame2-pair-cascade-bundle-fixture
                                  :dispatch-id :ungrouped)]
      (is (not (m/validate CascadeBundle frameless-bundle))
          (str "CascadeBundle MUST reject :dispatch-id :ungrouped — the "
               ":ungrouped sentinel marks frameless events the runtime "
               "filters OUT of cascade-bundle topics (rf2-mscih / "
               "rf2-voux7 finding 3). A bundle carrying it is a "
               "frameless-shape leak onto the cascade wire."))))
  (testing "schema REJECTS an :ungrouped bundle inside a CascadeBundleVector"
    (let [leaky-vec [re-frame2-pair-cascade-bundle-fixture
                     (assoc re-frame2-pair-cascade-bundle-fixture
                            :dispatch-id :ungrouped)]]
      (is (not (m/validate CascadeBundleVector leaky-vec))
          "a :cascades vector with even one :ungrouped bundle MUST fail")))
  (testing "schema ACCEPTS a typed/numeric cascade id (guard against over-tightening)"
    (is (m/validate CascadeBundle re-frame2-pair-cascade-bundle-fixture)
        "the canonical numeric :dispatch-id 17 must still validate")
    (is (m/validate CascadeBundle
                    (assoc re-frame2-pair-cascade-bundle-fixture
                           :dispatch-id :cart/checkout))
        "a keyword cascade id (not :ungrouped) is application-shaped and accepted")))

;; rf2-hvn83u (LOW): the former "cascade-bundle topics ship under
;; :cascades; frameless under :events" testing block was DROPPED — it
;; asserted `vector?` / `not contains?` over locally hand-built literal
;; maps (`cascade-tick` / `frameless-tick`), which is tautological: the
;; values were constructed as vectors / without the key the assertion
;; checked for, so the block could never fail and observed no real
;; emitter. The genuine cascade-vs-frameless distinction is pinned by the
;; two schema-rejection `testing` blocks above (`:ungrouped` rejected on a
;; CascadeBundle and inside a CascadeBundleVector via the `not-ungrouped?`
;; predicate). Tying the dropped tautology to a real progress-notification
;; subscribe-emit projection would require the heavyweight live emitter
;; and is out of scope for the fixture-drift fix; the schema gates carry
;; the contract.
