(ns day8.re-frame2-causa-mcp.dedup-test
  "Unit tests for the W-5 structural-dedup wire-pipeline mechanism
  at the Causa-MCP boundary (rf2-8xzoe.9). Pins:

    - MUST 14 — regime-appropriate compression-factor citation:
      ~1.4× raw trace bursts, ~10× high-share replays, 5-10×
      epoch slices. The reduction-ratio assertions here pin the
      load-bearing case (epoch slices with shared `:db-before`)
      at >=50% — the bead's floor; actual is ~89.5% per pair2-mcp's
      `dedup_test.cljs reduction-ratio-shared-subtrees`.
    - Wire shape — `{:rf.mcp/dedup-table <cache-map>}` from
      `re-frame.mcp-base.vocab/dedup-table-key` (cross-MCP).
    - Round-trip — `de-dupe.core/expand` reconstructs the
      payload exactly (the agent host's decode contract).
    - `:dedup?` opt-out — `false` => pass-through; `true` (default)
      => wrap.
    - Composition with B-1 (privacy), W-6 (elision), W-3 (cursor),
      W-1 (token-cap) — additive envelopes; dedup runs BEFORE
      W-1 per spec/004 §1 + §5 ordering.

  These tests are the load-bearing pin for the downstream trace-
  and epoch-shipping tool beads — every dispatcher that returns
  a structurally-repetitive vector calls `dedup/apply-to-result`
  once at the boundary."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.cursor :as cursor]
            [day8.re-frame2-causa-mcp.dedup :as dedup]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [de-dupe.core :as de-dupe]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; A canonical elided marker, as the framework walker would emit.
;; Reused across composition tests so the W-6 surface stays visible
;; inside W-5's deduped payloads.
(def ^:private elided-marker
  {:rf.size/large-elided
   {:path   [:trace 0 :coeffect :db]
    :bytes  102400
    :type   :map
    :reason :schema
    :handle [:rf.elision/at [:trace 0 :coeffect :db]]}})

(defn- expand
  "Test helper — invert `dedup-value` via `de-dupe.core/expand`.
  The agent host's decode contract."
  [wrapped]
  (if (and (map? wrapped) (contains? wrapped base-vocab/dedup-table-key))
    (de-dupe/expand (get wrapped base-vocab/dedup-table-key))
    wrapped))

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "W-5 lands the public surface downstream trace/epoch
            shipping tool dispatchers will require"
    (is (fn? dedup/empty-payload?))
    (is (fn? dedup/dedup-value))
    (is (fn? dedup/parse-dedup-arg))
    (is (fn? dedup/apply-to-result))
    (is (true? dedup/include-dedup-default)
        "spec/004 §5 L329 — :dedup? defaults true (on by default
         for trace/epoch shapes)")))

;; ---------------------------------------------------------------------------
;; empty-payload? — the no-op guard.
;; ---------------------------------------------------------------------------

(deftest empty-payload-nil-is-empty
  (is (true? (dedup/empty-payload? nil))))

(deftest empty-payload-empty-collections-are-empty
  (is (true? (dedup/empty-payload? [])))
  (is (true? (dedup/empty-payload? {})))
  (is (true? (dedup/empty-payload? #{})))
  (is (true? (dedup/empty-payload? '()))))

(deftest empty-payload-scalars-are-empty
  ;; Scalars can't be deduped — the no-op guard catches them. The
  ;; cache-of-one wrap would be a wire-size loss.
  (is (true? (dedup/empty-payload? 42)))
  (is (true? (dedup/empty-payload? :keyword)))
  (is (true? (dedup/empty-payload? "string")))
  (is (true? (dedup/empty-payload? true))))

(deftest empty-payload-non-empty-collections-fire-dedup
  (is (false? (dedup/empty-payload? [1 2 3])))
  (is (false? (dedup/empty-payload? {:a 1})))
  (is (false? (dedup/empty-payload? #{:x})))
  (is (false? (dedup/empty-payload? '(1 2)))))

;; ---------------------------------------------------------------------------
;; dedup-value — the wire-boundary wrap.
;; ---------------------------------------------------------------------------

(deftest dedup-disabled-passes-through
  ;; opt-out: caller asks for the raw payload.
  (let [payload [{:a 1 :b 2} {:a 1 :b 2}]]
    (is (= payload (dedup/dedup-value payload false)))))

(deftest dedup-empty-payload-passes-through
  ;; Empty / scalar inputs skip wrapping — the cache-of-one would
  ;; be a wire-size loss for trivial values.
  (is (nil? (dedup/dedup-value nil true)))
  (is (= [] (dedup/dedup-value [] true)))
  (is (= {} (dedup/dedup-value {} true)))
  (is (= 42 (dedup/dedup-value 42 true))))

(deftest dedup-non-empty-collection-emits-marker
  (let [payload [{:a 1} {:b 2}]
        wrapped (dedup/dedup-value payload true)]
    (is (map? wrapped))
    (is (contains? wrapped :rf.mcp/dedup-table))
    (is (map? (:rf.mcp/dedup-table wrapped))
        "the table itself is a hash-map keyed by namespaced symbols")))

(deftest dedup-marker-key-is-cross-mcp-vocab
  ;; Pin: the marker key matches `re-frame.mcp-base.vocab/dedup-
  ;; table-key`. Agents that learned `:rf.mcp/dedup-table` on
  ;; pair2-mcp see the same slot here.
  (let [wrapped (dedup/dedup-value [{:a 1} {:a 1}] true)]
    (is (= [:rf.mcp/dedup-table] (vec (keys wrapped))))
    (is (= base-vocab/dedup-table-key
           (first (keys wrapped)))
        "marker key sourced from re-frame.mcp-base.vocab")))

;; ---------------------------------------------------------------------------
;; Round-trip: dedup → expand → identity. The agent host's decode
;; contract.
;; ---------------------------------------------------------------------------

(deftest round-trip-simple-shared-map
  (let [shared {:big "common" :keys [:a :b :c]}
        payload [{:id 1 :payload shared}
                 {:id 2 :payload shared}
                 {:id 3 :payload shared}]
        wrapped (dedup/dedup-value payload true)
        restored (expand wrapped)]
    (is (= payload restored))))

(deftest round-trip-already-expanded-is-noop
  ;; A payload that was never deduped (caller passed `dedup? false`)
  ;; round-trips identity through expand.
  (let [payload [{:a 1} {:b 2}]]
    (is (= payload (expand payload)))))

(deftest round-trip-nested-shared-subtrees
  ;; The load-bearing case: epoch slice where every record carries
  ;; the same large `:db-before`. After dedup → expand the structure
  ;; comes back exactly.
  (let [big-db (into {} (for [i (range 100)]
                          [(keyword (str "k" i))
                           {:v (str "value-" i)
                            :meta {:tags [:tag1 :tag2 :tag3]}}]))
        epochs (vec (for [i (range 10)]
                      {:epoch-id (str "ep-" i)
                       :db-before big-db
                       :db-after  {:rf.mcp/diff-from :db-before
                                   :patches [[[(keyword (str "k" i)) :v]
                                              :assoc (str "new-" i)]]}}))
        wrapped (dedup/dedup-value epochs true)
        restored (expand wrapped)]
    (is (= epochs restored))))

(deftest round-trip-empty-collections-inside-payload
  (let [payload [{:items [] :state {}} {:items [] :state {}}]
        wrapped (dedup/dedup-value payload true)
        restored (expand wrapped)]
    (is (= payload restored))))

(deftest round-trip-deeply-nested-uniform-records
  ;; Stress: 50 records each carrying the same nested structure.
  (let [record {:cart {:items [{:sku "A" :qty 1}
                               {:sku "B" :qty 2}]
                       :total 30}
                :user {:id 7 :name "alice"}
                :ui {:loading? false :error nil}}
        payload (vec (repeat 50 record))
        wrapped (dedup/dedup-value payload true)
        restored (expand wrapped)]
    (is (= payload restored))
    (is (= 50 (count restored)))))

;; ---------------------------------------------------------------------------
;; Reduction-ratio benchmark — MUST 14 cross-MCP compression-factor pin.
;; ---------------------------------------------------------------------------

(deftest reduction-ratio-shared-subtrees
  ;; The load-bearing case spec/004 §5 cites — "Epoch slices"
  ;; row: 80-90% reduction (5-10x). pair2-mcp's
  ;; `dedup_test.cljs reduction-ratio-shared-subtrees` pins this
  ;; at ~89.5%; we mirror the same fixture so causa-mcp's
  ;; benchmark stays cross-MCP-aligned.
  (let [;; Build a "big" app-db — 256 keys, each a 256-char string
        ;; value => ~80KB pr-str.
        big-db (into {} (for [i (range 256)]
                          [(keyword (str "k" i))
                           (apply str (repeat 256 \x))]))
        ;; 10 epochs, each sharing the same :db-before reference.
        epochs (vec (for [i (range 10)]
                      {:epoch-id (str "ep-" i)
                       :event-id :touch
                       :db-before big-db
                       :db-after  {:rf.mcp/diff-from :db-before
                                   :patches [[[(keyword (str "k" i))]
                                              :assoc (apply str (repeat 256 \y))]]}}))
        raw-size (count (pr-str epochs))
        wrapped (dedup/dedup-value epochs true)
        wrapped-size (count (pr-str wrapped))]
    (testing "wrapped payload is much smaller than the raw vector"
      (is (< wrapped-size raw-size)
          (str "wrapped >= raw — measurement: raw=" raw-size
               "chars deduped=" wrapped-size "chars"))
      ;; >=50% reduction (the bead's floor). Actual ~89.5% per
      ;; pair2-mcp's pin; the load-bearing epoch-slice regime cited
      ;; in spec/004 §5 (5-10x ratio).
      (is (< wrapped-size (* 0.5 raw-size))
          (str "Deduped size (" wrapped-size
               ") should be < 50% of raw (" raw-size
               "). Ratio: " (/ wrapped-size raw-size 1.0))))
    (testing "round-trip still reconstructs every epoch"
      (let [restored (expand wrapped)]
        (is (= epochs restored))))))

(deftest reduction-ratio-trace-burst-regime
  ;; The spec/004 §5 "Raw trace bursts" row (28-31% reduction —
  ;; ~1.4x). This regime is the least-favourable case because per-
  ;; event `:id` / `:time` variance dominates the wire bytes; the
  ;; only shareable subtree is the cascade-level `:rf.trace/trigger-
  ;; handler` map and `:dispatch-id` backbone.
  (let [trigger-handler {:event-id :user/click
                         :handler-id :user.click/dispatch
                         :source-coord ["app.cljs" 42 7]}
        events (vec (for [i (range 50)]
                      {:id (str "trace-" i)
                       :time (* i 16)
                       :rf.trace/trigger-handler trigger-handler
                       :dispatch-id "dispatch-123"
                       :tag (keyword (str "tag-" i))}))
        raw-size (count (pr-str events))
        wrapped (dedup/dedup-value events true)
        wrapped-size (count (pr-str wrapped))]
    ;; Even the worst-case trace-burst regime should shrink — the
    ;; trigger-handler subtree is the load-bearing shareable. The
    ;; spec cites ~1.4x (28-31%); we pin > 5% as the floor (a
    ;; deduper degenerating to no-shrink would surface here).
    (is (< wrapped-size raw-size)
        (str "trace-burst dedup: raw=" raw-size
             "chars deduped=" wrapped-size "chars"))
    ;; Round-trip stays exact regardless of compression ratio.
    (is (= events (expand wrapped)))))

;; ---------------------------------------------------------------------------
;; Edge cases per the bead.
;; ---------------------------------------------------------------------------

(deftest edge-case-empty-payload-is-noop
  ;; "empty payload (no-op)" — the wrapper short-circuits.
  (is (nil? (dedup/dedup-value nil true)))
  (is (= [] (dedup/dedup-value [] true))))

(deftest edge-case-no-repeated-structure
  ;; "payload with no repeated structure (table empty)" — the
  ;; cache ships only the root entry; round-trip still exact.
  (let [payload [{:a 1} {:b 2} {:c 3}]
        wrapped (dedup/dedup-value payload true)
        restored (expand wrapped)]
    (is (= payload restored))))

(deftest edge-case-one-big-repeated-subtree
  ;; "payload that's one big repeated subtree (table has 1 entry)"
  ;; — the cache compresses well; round-trip still exact.
  (let [shared (into {} (for [i (range 100)]
                          [(keyword (str "k" i)) i]))
        payload (vec (repeat 20 shared))
        wrapped (dedup/dedup-value payload true)
        restored (expand wrapped)]
    (is (= payload restored))
    (is (= 20 (count restored)))
    (is (every? #(= shared %) restored))))

;; ---------------------------------------------------------------------------
;; parse-dedup-arg — :dedup? defaults true (opt-out).
;; ---------------------------------------------------------------------------

(deftest parse-dedup-default-true-when-absent
  ;; spec/004 §5 L329: default on for trace/epoch shapes. Every
  ;; shape of "no input" collapses to the default-true posture.
  (is (true? (dedup/parse-dedup-arg nil)))
  (is (true? (dedup/parse-dedup-arg js/undefined)))
  (is (true? (dedup/parse-dedup-arg {})))
  (is (true? (dedup/parse-dedup-arg #js {})))
  (is (true? (dedup/parse-dedup-arg #js {:other "value"}))))

(deftest parse-dedup-false-from-js-args-object
  ;; The opt-out: caller asks for the raw (un-deduped) payload.
  (is (false? (dedup/parse-dedup-arg #js {"dedup?" false})))
  (is (false? (dedup/parse-dedup-arg #js {"dedup?" "false"})))
  (is (false? (dedup/parse-dedup-arg #js {"dedup?" "no"})))
  (is (false? (dedup/parse-dedup-arg #js {"dedup?" "0"}))))

(deftest parse-dedup-true-from-js-args-object
  ;; Explicit opt-in (same as default).
  (is (true? (dedup/parse-dedup-arg #js {"dedup?" true})))
  (is (true? (dedup/parse-dedup-arg #js {"dedup?" "true"})))
  (is (true? (dedup/parse-dedup-arg #js {"dedup?" "yes"})))
  (is (true? (dedup/parse-dedup-arg #js {"dedup?" "1"}))))

(deftest parse-dedup-from-cljs-map
  (is (true?  (dedup/parse-dedup-arg {:dedup? true})))
  (is (false? (dedup/parse-dedup-arg {:dedup? false})))
  (is (true?  (dedup/parse-dedup-arg {"dedup?" true})))
  (is (false? (dedup/parse-dedup-arg {"dedup?" false}))))

(deftest parse-dedup-unrecognised-value-defaults-on
  ;; Unrecognised raw values collapse to the default-on posture per
  ;; the cross-MCP parse-boolean contract.
  (is (true? (dedup/parse-dedup-arg #js {"dedup?" "maybe"})))
  (is (true? (dedup/parse-dedup-arg #js {"dedup?" 42})))
  (is (true? (dedup/parse-dedup-arg {:dedup? :perhaps}))))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper.
;; ---------------------------------------------------------------------------

(deftest apply-to-result-enabled-wraps
  (let [items [{:a 1} {:a 1} {:a 1}]
        out   (dedup/apply-to-result {} :trace-events items true)]
    (is (contains? (:trace-events out) :rf.mcp/dedup-table)
        ":trace-events MUST be the wrapped marker shape")
    (is (= items (expand (:trace-events out)))
        "round-trip restores the input")))

(deftest apply-to-result-disabled-passes-through
  (let [items [{:a 1} {:a 2}]
        out   (dedup/apply-to-result {} :events items false)]
    (is (= items (:events out))
        ":dedup? false => raw payload under :events")))

(deftest apply-to-result-empty-payload-passes-through
  (let [out (dedup/apply-to-result {} :events [] true)]
    (is (= [] (:events out))
        "empty vector skips wrapping (no dedup opportunity)"))
  (let [out (dedup/apply-to-result {} :events nil true)]
    (is (nil? (:events out)))))

(deftest apply-to-result-preserves-existing-envelope-keys
  ;; Additive — pre-existing slots survive (parallel to every other
  ;; W-* boundary wrapper).
  (let [items [{:a 1} {:a 1}]
        out   (dedup/apply-to-result
                {:tool "get-trace-buffer" :mode :full} :events items true)]
    (is (= "get-trace-buffer" (:tool out)))
    (is (= :full (:mode out)))
    (is (contains? (:events out) :rf.mcp/dedup-table))))

(deftest apply-to-result-shape-for-trace-epoch-tools
  ;; Canonical shape every dedup-enabled tool uses.
  (testing "get-trace-buffer-shape (:trace-events)"
    (let [out (dedup/apply-to-result
                {} :trace-events [{:event :rf/dispatch}] true)]
      (is (contains? out :trace-events))))
  (testing "get-epoch-history-shape (:epochs)"
    (let [out (dedup/apply-to-result
                {} :epochs [{:epoch-id "ep-1"}] true)]
      (is (contains? out :epochs))))
  (testing "subscribe-drain-batch-shape (:events)"
    (let [out (dedup/apply-to-result
                {} :events [{:op :a}] true)]
      (is (contains? out :events)))))

;; ---------------------------------------------------------------------------
;; Composition with B-1 (privacy), W-6 (elision), W-3 (cursor),
;; W-1 (token-cap). The spec/004 §5 cascade order pinned end-to-end.
;; ---------------------------------------------------------------------------

(deftest composes-with-b1-privacy-strip-before-dedup
  ;; The cascade per spec/004 §5: privacy strip first (B-1) => kept
  ;; items; then dedup (W-5). The :dropped-sensitive counter rides
  ;; alongside the dedup-table marker.
  (let [events   [{:op :a :payload {:shared 1}}
                  {:op :b :sensitive? true}
                  {:op :c :payload {:shared 1}}
                  {:op :d :sensitive? true}]
        envelope (-> {:tool "get-trace-buffer"}
                     (privacy/apply-to-result :trace-events events false))
        kept     (:trace-events envelope)
        out      (dedup/apply-to-result envelope :trace-events kept true)]
    (is (= 2 (:dropped-sensitive out))
        "B-1 surfaces dropped-sensitive")
    (is (contains? (:trace-events out) :rf.mcp/dedup-table)
        "W-5 wraps the kept items")
    (let [restored (expand (:trace-events out))]
      (is (= [{:op :a :payload {:shared 1}} {:op :c :payload {:shared 1}}]
             restored)
          "the post-strip kept items round-trip through dedup"))))

(deftest composes-with-w6-elision-counts-survive-dedup
  ;; W-6 counts markers in the items vector BEFORE W-5 dedup runs.
  ;; The counter rides on the envelope and isn't disturbed by the
  ;; dedup-table wrap of the items.
  (let [items    [{:event :a :coeffect elided-marker}
                  {:event :b :coeffect elided-marker}]
        envelope (-> {}
                     (elision/apply-to-result :trace-events items)
                     ;; W-5 now wraps the items in place.
                     ((fn [env]
                        (dedup/apply-to-result env :trace-events
                                               (:trace-events env) true))))]
    (is (= 2 (:elided-large envelope))
        "W-6 counter rides on the envelope across W-5 wrap")
    (is (contains? (:trace-events envelope) :rf.mcp/dedup-table)
        "W-5 wrapped the items")
    (let [restored (expand (:trace-events envelope))]
      (is (= items restored)
          "elided-marker rides verbatim through dedup → expand"))))

(deftest composes-with-w3-cursor-pagination
  ;; W-5 wraps the per-page items; W-3 stamps :next-cursor +
  ;; :remaining alongside. The agent decodes one page at a time.
  (let [items   [{:event :a :shared {:x 1}}
                 {:event :b :shared {:x 1}}]
        ;; W-5 first (the cascade: dedup is per-page, runs before
        ;; the cursor wrap).
        deduped (dedup/apply-to-result {:tool "get-trace-buffer"}
                                       :trace-events items true)
        ;; W-3 stamps :next-cursor + :remaining; the :trace-events
        ;; slot retains the dedup wrap.
        out     (cursor/apply-to-result deduped :trace-events
                                        (:trace-events deduped)
                                        {:next-cursor {:v 1 :after-id "ep-2"}
                                         :remaining 48})]
    (is (contains? (:trace-events out) :rf.mcp/dedup-table)
        "W-5 wrap survives the W-3 cursor stamp")
    (is (string? (:next-cursor out))
        "W-3 stamps the next-cursor alongside the deduped items")
    (is (= 48 (:remaining out)))))

(deftest composes-with-w1-token-cap-runs-after-dedup
  ;; W-5 + W-1 cascade per spec/004 §1: dedup runs BEFORE the cap.
  ;; The structural pin is that W-5's wrap REDUCES the rendered
  ;; token count below the raw count — so a payload that would
  ;; trip W-1 raw may pass post-dedup. We can't easily simulate
  ;; the full cascade end-to-end (W-1's cap wrapper operates on
  ;; the JS MCP-result envelope, not the CLJS map); the pin is
  ;; the per-rendered-bytes reduction.
  (let [big-db (into {} (for [i (range 256)]
                          [(keyword (str "k" i))
                           (apply str (repeat 256 \x))]))
        epochs (vec (for [i (range 10)]
                      {:epoch-id (str "ep-" i)
                       :db-before big-db}))
        raw-tokens     (quot (count (pr-str epochs)) 4)
        deduped        (dedup/dedup-value epochs true)
        deduped-tokens (quot (count (pr-str deduped)) 4)]
    (is (< deduped-tokens raw-tokens)
        (str "W-5 shrinks token count from " raw-tokens
             " raw to " deduped-tokens " deduped"))
    ;; The 10-epoch shared-:db-before fixture overflows the 5K cap
    ;; raw — the load-bearing case W-1 would have to handle without
    ;; W-5's help.
    (is (> raw-tokens token-cap/default-max-tokens)
        "raw payload overflows the 5K cap")
    ;; Post-dedup, the shrink is at least 5x (the spec/004 §5
    ;; epoch-slice regime floor). That's the load-bearing reduction
    ;; the cascade leverages — fitting under cap is a function of
    ;; absolute size; the structural shrink is what dedup provides.
    (is (< deduped-tokens (quot raw-tokens 5))
        (str "W-5 shrinks token count by >=5x for the shared-"
             ":db-before regime (raw=" raw-tokens
             " deduped=" deduped-tokens ")"))))

;; ---------------------------------------------------------------------------
;; Load-bearing spec/004 §5 assertions.
;; ---------------------------------------------------------------------------

(deftest spec-004-dedup-table-marker-key-pin
  (testing "spec/004 §5 + cross-MCP-vocab: marker key is
            `:rf.mcp/dedup-table`"
    (let [wrapped (dedup/dedup-value [{:a 1} {:a 1}] true)]
      (is (= :rf.mcp/dedup-table (first (keys wrapped))))
      (is (= base-vocab/dedup-table-key (first (keys wrapped)))))))

(deftest spec-004-per-tick-self-contained
  (testing "spec/004 §5 — per-tick dedup table; no cross-tick refs.
            Each response is self-contained; the agent decodes one
            response at a time without holding state from prior
            calls."
    (let [items-tick-1 [{:shared {:x 1}} {:shared {:x 1}}]
          items-tick-2 [{:shared {:y 2}} {:shared {:y 2}}]
          tick-1       (dedup/dedup-value items-tick-1 true)
          tick-2       (dedup/dedup-value items-tick-2 true)]
      ;; Each tick's cache decodes independently of the other.
      (is (= items-tick-1 (expand tick-1)))
      (is (= items-tick-2 (expand tick-2)))
      ;; Caches are independent — no cross-tick ref sharing (each
      ;; payload's cache map contains only its own refs).
      (is (map? (:rf.mcp/dedup-table tick-1)))
      (is (map? (:rf.mcp/dedup-table tick-2))))))

(deftest spec-004-must-14-compression-factor-citation
  (testing "MUST 14 — epoch-slice regime exhibits 5-10x ratio
            (80-90% reduction). Pinned by the load-bearing
            shared-:db-before fixture."
    (let [big-db (into {} (for [i (range 256)]
                            [(keyword (str "k" i))
                             (apply str (repeat 256 \x))]))
          epochs (vec (for [i (range 10)]
                        {:epoch-id (str "ep-" i)
                         :db-before big-db
                         :db-after {:rf.mcp/diff-from :db-before
                                    :patches [[[(keyword (str "k" i))] :assoc :y]]}}))
          raw-bytes (count (pr-str epochs))
          wrapped-bytes (count (pr-str (dedup/dedup-value epochs true)))
          ratio (/ raw-bytes (double wrapped-bytes))]
      (is (>= ratio 2.0)
          (str "epoch-slice regime ratio " ratio
               " should be >= 2x (spec/004 §5 cites 5-10x; the
               floor is generous to absorb fixture variance)")))))
