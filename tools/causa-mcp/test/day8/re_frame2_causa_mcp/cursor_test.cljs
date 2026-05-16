(ns day8.re-frame2-causa-mcp.cursor-test
  "Unit tests for the W-3 cursor-pagination wire-pipeline mechanism
  at the Causa-MCP boundary (rf2-8xzoe.7). Pins:

    - MUST 10 — sequence-returning tools (`get-trace-buffer`,
      `get-epoch-history`, `list-subscriptions`) MUST accept
      `:cursor` (opaque) + `:limit` (integer) args
      (spec/004 §3 L274-281).
    - MUST 11 — responses MUST carry `:next-cursor` (or nil) +
      `:remaining` (count/estimate) (spec/004 §3 L283-284).
    - Opaque round-trip — encode/decode preserves the cursor
      payload exactly (the agent passes it back verbatim).
    - Cursor staleness — `:rf.mcp/cursor-stale` structured error
      shape, sourced from `re-frame.mcp-base.vocab/cursor-stale-
      reason` (cross-MCP).
    - Composition with W-1 (token-cap), W-6 (elision), and B-1
      (privacy) — wrappers are additive; counters from all axes
      ride together on the same envelope.

  These tests are the load-bearing pin for the downstream paginated
  tool beads — every dispatcher that returns a paginated payload
  calls `cursor/apply-to-result` once at the boundary."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.cursor :as cursor]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; A canonical elided marker as the framework walker would emit. Reused
;; across composition tests so the W-6 surface stays visible inside
;; W-3's paginated batches.
(def ^:private elided-marker
  {:rf.size/large-elided
   {:path   [:trace 0 :coeffect :db]
    :bytes  102400
    :type   :map
    :reason :declared
    :handle [:rf.elision/at [:trace 0 :coeffect :db]]}})

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "W-3 lands the public surface downstream paginated tool
            dispatchers will require"
    (is (fn? cursor/parse-limit-arg))
    (is (fn? cursor/limit-arg))
    (is (fn? cursor/encode-cursor))
    (is (fn? cursor/decode-cursor))
    (is (fn? cursor/cursor-arg))
    (is (fn? cursor/cursor-stale-result))
    (is (fn? cursor/apply-to-result))
    (is (= 50 cursor/default-limit))
    (is (= 1  cursor/min-limit))))

;; ---------------------------------------------------------------------------
;; parse-limit-arg / limit-arg — accept-shape contract.
;; ---------------------------------------------------------------------------

(deftest parse-limit-default-when-absent
  (is (= cursor/default-limit (cursor/parse-limit-arg nil))))

(deftest parse-limit-positive-integer-pass-through
  (is (= 10 (cursor/parse-limit-arg 10)))
  (is (= 1  (cursor/parse-limit-arg 1)))
  (is (= 1000 (cursor/parse-limit-arg 1000))))

(deftest parse-limit-clamps-non-positive-to-floor
  ;; min-limit is 1 — a caller asking for 0 / negative collapses to
  ;; the smallest valid page rather than landing in a paging dead-end.
  (is (= cursor/min-limit (cursor/parse-limit-arg 0)))
  (is (= cursor/min-limit (cursor/parse-limit-arg -5))))

(deftest parse-limit-parses-numeric-string
  ;; The MCP wire may hand the dispatcher a stringified integer
  ;; (depending on the agent host's serialiser); the parser handles
  ;; both shapes uniformly.
  (is (= 25 (cursor/parse-limit-arg "25"))))

(deftest parse-limit-non-numeric-string-falls-back
  (is (= cursor/default-limit (cursor/parse-limit-arg "bogus"))))

(deftest limit-arg-from-js-args-object
  ;; The MCP SDK hands the dispatcher a JS args object; the helper
  ;; reads `limit` off it and routes through the parser.
  (is (= 10 (cursor/limit-arg #js {"limit" 10})))
  (is (= 25 (cursor/limit-arg #js {"limit" "25"})))
  (is (= cursor/default-limit (cursor/limit-arg #js {}))))

(deftest limit-arg-from-cljs-map
  (is (= 10 (cursor/limit-arg {:limit 10})))
  (is (= 10 (cursor/limit-arg {"limit" 10}))))

(deftest limit-arg-absent-returns-default
  (is (= cursor/default-limit (cursor/limit-arg nil)))
  (is (= cursor/default-limit (cursor/limit-arg js/undefined))))

;; ---------------------------------------------------------------------------
;; encode-cursor / decode-cursor — opaque round-trip.
;; ---------------------------------------------------------------------------

(deftest encode-cursor-nil-when-no-after-id
  ;; The terminal-page signal: a payload missing :after-id collapses
  ;; to nil. The dispatcher passes nil when pagination is exhausted.
  (is (nil? (cursor/encode-cursor nil)))
  (is (nil? (cursor/encode-cursor {})))
  (is (nil? (cursor/encode-cursor {:v 1 :after-id nil}))))

(deftest encode-cursor-returns-string-on-valid-payload
  (let [c (cursor/encode-cursor {:v 1 :after-id "abc"})]
    (is (string? c))
    (is (pos? (count c)))))

(deftest encode-cursor-is-base64-on-wire
  ;; The cursor must be base64-safe — no JSON-confusing characters
  ;; that would break round-trip through stdio + JSON-RPC.
  (let [c (cursor/encode-cursor {:v 1 :after-id "abc-def"})]
    (is (re-matches #"^[A-Za-z0-9+/=]+$" c))))

(deftest cursor-round-trip-preserves-payload
  (let [payload {:v 1 :after-id "epoch-42" :until-ms 1234567890
                 :frame :rf/default}
        encoded (cursor/encode-cursor payload)
        decoded (cursor/decode-cursor encoded)]
    (is (= payload decoded))))

(deftest decode-cursor-nil-on-nil-input
  (is (nil? (cursor/decode-cursor nil)))
  (is (nil? (cursor/decode-cursor "")))
  (is (nil? (cursor/decode-cursor js/undefined))))

(deftest decode-cursor-malformed-on-junk
  ;; A cursor that doesn't decode is treated as stale — the caller
  ;; translates ::malformed into the same :rf.mcp/cursor-stale error
  ;; a runtime age-out raises.
  (is (= :day8.re-frame2-causa-mcp.cursor/malformed
         (cursor/decode-cursor "not-real-base64-edn-juzlblahHFGYbn")))
  (is (= :day8.re-frame2-causa-mcp.cursor/malformed
         (cursor/decode-cursor 12345)))
  ;; base64 of "[1 2 3]" — decodes but isn't a map with :after-id
  (let [bogus (.toString (js/Buffer.from "[1 2 3]" "utf8") "base64")]
    (is (= :day8.re-frame2-causa-mcp.cursor/malformed
           (cursor/decode-cursor bogus)))))

(deftest decode-cursor-malformed-on-missing-after-id
  ;; The :after-id slot is the load-bearing payload — a cursor
  ;; without it can't resume the stream. Treated as malformed.
  (let [bogus (.toString (js/Buffer.from "{:v 1}" "utf8") "base64")]
    (is (= :day8.re-frame2-causa-mcp.cursor/malformed
           (cursor/decode-cursor bogus)))))

(deftest cursor-arg-from-js-args-object
  (let [payload {:v 1 :after-id "epoch-42"}
        encoded (cursor/encode-cursor payload)]
    (is (= payload (cursor/cursor-arg #js {"cursor" encoded})))))

(deftest cursor-arg-from-cljs-map
  (let [payload {:v 1 :after-id "epoch-42"}
        encoded (cursor/encode-cursor payload)]
    (is (= payload (cursor/cursor-arg {:cursor encoded})))
    (is (= payload (cursor/cursor-arg {"cursor" encoded})))))

(deftest cursor-arg-absent-returns-nil
  ;; First-call posture: no cursor arg supplied.
  (is (nil? (cursor/cursor-arg nil)))
  (is (nil? (cursor/cursor-arg js/undefined)))
  (is (nil? (cursor/cursor-arg #js {})))
  (is (nil? (cursor/cursor-arg {}))))

(deftest cursor-arg-malformed-on-bad-input
  (is (= :day8.re-frame2-causa-mcp.cursor/malformed
         (cursor/cursor-arg #js {"cursor" "not-a-real-cursor"}))))

;; ---------------------------------------------------------------------------
;; cursor-stale-result — :rf.mcp/cursor-stale shape.
;; ---------------------------------------------------------------------------

(deftest cursor-stale-result-shape-minimum
  (let [r (cursor/cursor-stale-result "get-trace-buffer" {})]
    (is (false? (:ok? r)))
    (is (= :rf.mcp/cursor-stale (:reason r)))
    (is (= "get-trace-buffer" (:tool r)))
    (is (string? (:hint r)))
    (is (not (contains? r :requested-id)))
    (is (not (contains? r :head-id)))))

(deftest cursor-stale-result-with-ids
  (let [r (cursor/cursor-stale-result
            "get-epoch-history"
            {:requested-id "ep-100" :head-id "ep-250"})]
    (is (= "ep-100" (:requested-id r)))
    (is (= "ep-250" (:head-id r)))))

(deftest cursor-stale-reason-matches-cross-mcp-vocab
  ;; Pin the cross-MCP convention: `:reason` value comes from
  ;; `re-frame.mcp-base.vocab/cursor-stale-reason`. A rename in the
  ;; base ns surfaces here loud rather than drifting silently.
  (is (= base-vocab/cursor-stale-reason
         (:reason (cursor/cursor-stale-result "any-tool" {})))))

(deftest cursor-stale-reason-keyword-pin
  ;; The keyword literal pin — agents pattern-match on the literal,
  ;; not on the var. A change to the literal needs an explicit
  ;; coordinated update across the MCP triplet.
  (is (= :rf.mcp/cursor-stale
         (:reason (cursor/cursor-stale-result "any-tool" {})))))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper.
;; ---------------------------------------------------------------------------

(deftest apply-to-result-writes-items-and-shape
  ;; Happy path: items ride under `items-key`; :next-cursor encodes
  ;; the supplied payload; :remaining carries the estimate.
  (let [items     [{:id 1} {:id 2} {:id 3}]
        next-cur  {:v 1 :after-id "ep-3"}
        out       (cursor/apply-to-result
                    {} :trace-events items
                    {:next-cursor next-cur :remaining 47})]
    (is (= items (:trace-events out)))
    (is (string? (:next-cursor out)))
    (is (= 47 (:remaining out)))
    ;; The encoded cursor round-trips back to the payload.
    (is (= next-cur (cursor/decode-cursor (:next-cursor out))))))

(deftest apply-to-result-terminal-page-next-cursor-nil
  ;; Pagination exhausted: the dispatcher passes nil :next-cursor;
  ;; the wrapper writes `nil` under `:next-cursor`. Per MUST 11
  ;; the slot is ALWAYS present (even when nil) so the agent's
  ;; pattern-match on `(nil? next-cursor)` reads uniformly.
  (let [items [{:id 9} {:id 10}]
        out   (cursor/apply-to-result
                {} :epochs items
                {:next-cursor nil :remaining 0})]
    (is (= items (:epochs out)))
    (is (nil? (:next-cursor out)))
    (is (contains? out :next-cursor)
        ":next-cursor MUST be present (even nil) per MUST 11")
    (is (= 0 (:remaining out)))))

(deftest apply-to-result-missing-after-id-is-terminal
  ;; A payload missing `:after-id` collapses to a nil :next-cursor
  ;; (the encode contract). Same terminal-page shape.
  (let [items [{:id 1}]
        out   (cursor/apply-to-result
                {} :events items
                {:next-cursor {:v 1} :remaining 0})]
    (is (nil? (:next-cursor out)))))

(deftest apply-to-result-empty-items-vector-pages-empty
  ;; A legitimate empty page (zero kept items but possibly a next
  ;; cursor if filtering produced a quiet window). The wrapper does
  ;; not collapse this — it's not its place to decide whether an
  ;; empty page is terminal; the dispatcher decides.
  (let [out (cursor/apply-to-result
              {} :events []
              {:next-cursor {:v 1 :after-id "ep-99"} :remaining 100})]
    (is (= [] (:events out)))
    (is (string? (:next-cursor out)))
    (is (= 100 (:remaining out)))))

(deftest apply-to-result-preserves-existing-envelope-keys
  ;; Additive wrapper — pre-existing slots survive. Same shape as
  ;; `privacy/apply-to-result` / `elision/apply-to-result` /
  ;; `path-slice/apply-to-result`.
  (let [items [{:id 1}]
        out   (cursor/apply-to-result
                {:tool "get-trace-buffer" :mode :full} :events items
                {:next-cursor nil :remaining 0})]
    (is (= "get-trace-buffer" (:tool out)))
    (is (= :full (:mode out)))))

(deftest apply-to-result-remaining-defaults-to-zero
  ;; Defensive: an omitted `:remaining` opt collapses to 0 rather
  ;; than nil — the agent's per-page accounting stays an integer.
  (let [out (cursor/apply-to-result
              {} :events [{:id 1}]
              {:next-cursor nil})]
    (is (= 0 (:remaining out)))))

(deftest apply-to-result-shape-for-paginated-tools
  ;; Canonical shape every paginated tool uses:
  ;;   `:trace-events`  for `get-trace-buffer`
  ;;   `:epochs`        for `get-epoch-history`
  ;;   `:subscriptions` for `list-subscriptions`
  (testing "get-trace-buffer-shape (:trace-events)"
    (let [out (cursor/apply-to-result
                {} :trace-events [{:event :rf/dispatch}]
                {:next-cursor nil :remaining 0})]
      (is (contains? out :trace-events))))
  (testing "get-epoch-history-shape (:epochs)"
    (let [out (cursor/apply-to-result
                {} :epochs [{:epoch-id "ep-1"}]
                {:next-cursor nil :remaining 0})]
      (is (contains? out :epochs))))
  (testing "list-subscriptions-shape (:subscriptions)"
    (let [out (cursor/apply-to-result
                {} :subscriptions [{:query-v [:items]}]
                {:next-cursor nil :remaining 0})]
      (is (contains? out :subscriptions)))))

;; ---------------------------------------------------------------------------
;; Composition with W-6 (elision) + B-1 (privacy) — the canonical
;; trace-stream + tree-typed cascade.
;; ---------------------------------------------------------------------------

(deftest composes-with-b1-privacy-on-paginated-trace-stream
  ;; Trace-stream tools paginate after privacy-stripping. The
  ;; envelope carries both axes' indicator counters.
  (let [events   [{:op :a}
                  {:op :b :sensitive? true}
                  {:op :c}
                  {:op :d :sensitive? true}]
        ;; B-1 first; remaining vector carries the kept events.
        envelope (-> {:tool "get-trace-buffer"}
                     (privacy/apply-to-result :trace-events events false))
        kept     (:trace-events envelope)
        ;; W-3 paginates the post-strip slice.
        out      (cursor/apply-to-result
                   envelope :trace-events kept
                   {:next-cursor {:v 1 :after-id "ep-3"} :remaining 50})]
    (is (= 2 (:dropped-sensitive out))
        "B-1 surfaces dropped-sensitive count")
    (is (= [{:op :a} {:op :c}] (:trace-events out))
        "W-3 carries the post-strip kept items")
    (is (string? (:next-cursor out))
        "W-3 stamps the next-cursor")
    (is (= 50 (:remaining out)))))

(deftest composes-with-w6-elision-on-paginated-trace-stream
  ;; Per-record tree-typed slots already walked server-side; W-6
  ;; counts markers across the batch. Both axes ride the same
  ;; envelope.
  (let [items    [{:event :rf/dispatch :coeffect elided-marker}
                  {:event :rf/effects  :coeffect elided-marker}]
        envelope (-> {:tool "get-trace-buffer"}
                     (cursor/apply-to-result :trace-events items
                                             {:next-cursor nil :remaining 0}))
        out      (elision/apply-to-result envelope :trace-events
                                          (:trace-events envelope))]
    (is (= 2 (:elided-large out))
        "W-6 counts markers across the paginated batch")
    (is (nil? (:next-cursor out))
        "W-3's terminal :next-cursor stays nil")
    (is (= 0 (:remaining out)))))

(deftest composes-with-b1-and-w6-on-same-envelope
  ;; The full cascade: B-1 strips, W-3 paginates, W-6 counts. All
  ;; three counters surface together.
  (let [items    [{:event :a :coeffect elided-marker}
                  {:event :b :sensitive? true :coeffect elided-marker}
                  {:event :c :coeffect elided-marker}]
        envelope (-> {:tool "get-trace-buffer"}
                     (privacy/apply-to-result :trace-events items false))
        kept     (:trace-events envelope)
        paged    (cursor/apply-to-result envelope :trace-events kept
                                         {:next-cursor {:v 1 :after-id "ep-3"}
                                          :remaining 47})
        out      (elision/apply-to-result paged :trace-events
                                          (:trace-events paged))]
    (is (= 1 (:dropped-sensitive out)) "B-1 drops sensitive")
    (is (= 2 (:elided-large out)) "W-6 counts markers in kept")
    (is (string? (:next-cursor out)) "W-3 stamps next-cursor")
    (is (= 47 (:remaining out)))))

;; ---------------------------------------------------------------------------
;; Load-bearing spec/004 §3 assertions.
;; ---------------------------------------------------------------------------

(deftest spec-004-cursor-and-limit-accepted-end-to-end
  (testing "MUST 10 — :cursor + :limit accepted from the JS args object"
    (let [payload {:v 1 :after-id "ep-42"}
          encoded (cursor/encode-cursor payload)
          args    #js {"cursor" encoded "limit" 25}]
      (is (= payload (cursor/cursor-arg args)))
      (is (= 25 (cursor/limit-arg args))))))

(deftest spec-004-response-carries-next-cursor-and-remaining
  (testing "MUST 11 — :next-cursor + :remaining on every paginated
            response"
    (let [out (cursor/apply-to-result
                {} :events [{:id 1}]
                {:next-cursor {:v 1 :after-id "ep-1"} :remaining 99})]
      (is (contains? out :next-cursor))
      (is (contains? out :remaining))))
  (testing "terminal-page MUST still carry both slots (nil cursor)"
    (let [out (cursor/apply-to-result
                {} :events [{:id 1}]
                {:next-cursor nil :remaining 0})]
      (is (contains? out :next-cursor))
      (is (nil? (:next-cursor out)))
      (is (contains? out :remaining)))))

(deftest spec-004-cursor-opaque-on-wire
  (testing "spec/Principles §Pagination — cursors are opaque; the
            agent passes them back verbatim. The encoded form is a
            base64 string with no JSON-confusing chars."
    (let [c (cursor/encode-cursor {:v 1 :after-id "epoch-with-special-chars/"})]
      (is (re-matches #"^[A-Za-z0-9+/=]+$" c)
          "cursor MUST be base64-safe for stdio + JSON-RPC transit"))))

(deftest spec-004-cursor-stale-cross-mcp-vocabulary
  (testing "spec/004 §3 — `:rf.mcp/cursor-stale` is the cross-MCP
            convention agents pattern-match on for cursor age-out
            recovery. The keyword is pinned in
            `re-frame.mcp-base.vocab` so the triplet shares one
            literal."
    (let [r (cursor/cursor-stale-result
              "get-trace-buffer"
              {:requested-id "ep-100" :head-id "ep-250"})]
      (is (= :rf.mcp/cursor-stale (:reason r)))
      (is (= base-vocab/cursor-stale-reason (:reason r)))
      (is (= "ep-100" (:requested-id r)))
      (is (= "ep-250" (:head-id r))))))
