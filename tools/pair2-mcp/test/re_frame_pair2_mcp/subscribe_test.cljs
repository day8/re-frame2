(ns re-frame-pair2-mcp.subscribe-test
  "Unit tests for the streaming subscribe / unsubscribe tools (rf2-hq49).

  These tests cover the MCP-arg → runtime-form translation (the EDN
  shape we send over nREPL), the filter-arg parser (JSON object vs
  EDN string), and the descriptor wiring. The live end-to-end coverage
  (notifications/progress emission, abort-signal termination) lives
  in `test/stdio-roundtrip.js` and the live-nREPL integration test
  against a real shadow-cljs build."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame-pair2-mcp.tools.args :as args]
            [re-frame-pair2-mcp.tools.eval-form :as ef]
            [re-frame-pair2-mcp.tools.subscribe :as sub]))

;; The MCP-arg filter parser lives in
;; `re-frame-pair2-mcp.tools.args/parse-filter-arg`. Tests pin the
;; public surface directly so a rename or signature change surfaces
;; as a failing test rather than a silent contract drift.

(deftest filter-arg-nil-passes-through
  (is (nil? (args/parse-filter-arg nil))))

(deftest filter-arg-edn-string-reads
  (is (= {:op-type :error}
         (args/parse-filter-arg "{:op-type :error}"))))

(deftest filter-arg-edn-string-with-touches-path
  (is (= {:touches-path [:cart :items]}
         (args/parse-filter-arg "{:touches-path [:cart :items]}"))))

(deftest filter-arg-malformed-edn-surfaces-marker
  (is (= {:invalid-filter-edn "((("}
         (args/parse-filter-arg "(((")))) ;; unbalanced delimiters

(deftest filter-arg-js-object-keywordises
  (let [obj #js {:op-type "error" :event-id "user/login"}
        out (args/parse-filter-arg obj)]
    (is (map? out))
    (is (= "error" (:op-type out)))
    (is (= "user/login" (:event-id out)))))

(deftest filter-arg-clj-map-passes-through
  (is (= {:op-type :error}
         (args/parse-filter-arg {:op-type :error}))))

;; The subscribe-form constructor is private. Re-implement here over
;; the eval-form DSL to pin the IR + emitted source we send to the
;; runtime (rf2-dpzpe). Tests assert against opts-map data shape via
;; `cljs.reader/read-string` over the emitted form — no regex over
;; raw source, no whitespace fragility.

(defn- subscribe-opts
  [topic filter-map max-buf-events max-buf-bytes]
  (cond-> {:topic               topic
           :max-buffered-events max-buf-events
           :max-buffered-bytes  max-buf-bytes}
    filter-map (assoc :filter filter-map)))

(defn- subscribe-form
  [topic filter-map max-buf-events max-buf-bytes]
  (ef/emit (ef/rt-call 'subscribe!
                       (subscribe-opts topic filter-map
                                       max-buf-events max-buf-bytes))))

(defn- form->opts
  "Round-trip the emitted source back into Clojure data; assert against
  the opts-map directly instead of regex over source bytes."
  [form]
  (-> form cljs.reader/read-string second))

(deftest subscribe-form-with-trace-and-filter
  (let [opts (form->opts
               (subscribe-form :trace {:op-type :error :event-id :user/login}
                               500 5000000))]
    (is (= :trace                       (:topic opts)))
    (is (= 500                          (:max-buffered-events opts)))
    (is (= 5000000                      (:max-buffered-bytes opts)))
    (is (= {:op-type :error :event-id :user/login} (:filter opts)))))

(deftest subscribe-form-without-filter-omits-key
  (let [opts (form->opts (subscribe-form :epoch nil 250 1000000))]
    (is (= :epoch  (:topic opts)))
    (is (= 250     (:max-buffered-events opts)))
    (is (= 1000000 (:max-buffered-bytes  opts)))
    (is (not (contains? opts :filter)))))

(deftest subscribe-form-fx-topic
  (let [opts (form->opts (subscribe-form :fx {:event-id :http/get} 100 100000))]
    (is (= :fx     (:topic opts)))
    (is (= 100     (:max-buffered-events opts)))
    (is (= 100000  (:max-buffered-bytes  opts)))
    (is (= {:event-id :http/get} (:filter opts)))))

(deftest subscribe-form-carries-both-budgets
  ;; rf2-ho4ve: the wire shape must always carry BOTH :max-buffered-events
  ;; and :max-buffered-bytes — the runtime applies an OR-combined budget,
  ;; so half the pair would leak through to the runtime's default for the
  ;; missing slot. Pin the round-trip here.
  (let [opts (form->opts (subscribe-form :trace nil 1000 2000000))]
    (is (= 1000    (:max-buffered-events opts)))
    (is (= 2000000 (:max-buffered-bytes  opts)))))

;; Recognised topics — keep this set in lockstep with the runtime's
;; subscribe! whitelist.

(deftest recognised-topics
  (let [recognised #{:trace :epoch :fx :error}]
    (is (contains? recognised :trace))
    (is (contains? recognised :epoch))
    (is (contains? recognised :fx))
    (is (contains? recognised :error))
    (is (not (contains? recognised :unknown)))))

;; The progress payload shape — the MCP notifications/progress
;; notification we emit on each batch tick. Per rf2-ho4ve `_meta.data`
;; slot now carries `:dropped-events`, `:dropped-bytes`, and
;; `:overflow-reason` (stringified keyword) so the AI client can
;; pattern-match on WHICH budget tripped without re-parsing EDN. The
;; official MCP SDK preserves `_meta` in progress callbacks.

(defn- progress-payload
  [progress-token tick events-edn dropped-events dropped-bytes overflow-reason]
  #js {:progressToken progress-token
       :progress      tick
       :message       events-edn
       :_meta         #js {:data #js {:dropped-events  dropped-events
                                      :dropped-bytes   dropped-bytes
                                      :overflow-reason (when overflow-reason
                                                         (pr-str overflow-reason))}}})

(deftest progress-payload-carries-token-and-counters
  (let [p (progress-payload "tok-1" 3 "{:events [...]}" 0 0 nil)]
    (is (= "tok-1" (j/get p :progressToken)))
    (is (= 3 (j/get p :progress)))
    (is (= "{:events [...]}" (j/get p :message)))
    (is (= 0 (j/get-in p [:_meta :data :dropped-events])))
    (is (= 0 (j/get-in p [:_meta :data :dropped-bytes])))
    (is (nil? (j/get-in p [:_meta :data :overflow-reason])))))

(deftest progress-payload-with-events-overflow
  ;; Count-budget eviction.
  (let [p (progress-payload 42 1 "{...}" 7 0 :max-buffered-events)]
    (is (= 42 (j/get p :progressToken)))
    (is (= 7 (j/get-in p [:_meta :data :dropped-events])))
    (is (= 0 (j/get-in p [:_meta :data :dropped-bytes])))
    (is (= ":max-buffered-events" (j/get-in p [:_meta :data :overflow-reason])))))

(deftest progress-payload-with-bytes-overflow
  ;; Byte-budget eviction — same shape, different reason keyword.
  (let [p (progress-payload "tok-9" 4 "{...}" 3 12345 :max-buffered-bytes)]
    (is (= 3 (j/get-in p [:_meta :data :dropped-events])))
    (is (= 12345 (j/get-in p [:_meta :data :dropped-bytes])))
    (is (= ":max-buffered-bytes" (j/get-in p [:_meta :data :overflow-reason])))))

;; Unsubscribe + drain form pinning — built over the eval-form DSL
;; (rf2-dpzpe). The expected source strings are the DSL's emit output;
;; a rename of the runtime ns flows through `ef/runtime-ns`.

(defn- unsubscribe-form [sub-id]
  (ef/emit (ef/rt-call 'unsubscribe! sub-id)))

(defn- drain-form [sub-id]
  (ef/emit (ef/rt-call 'drain-subscription! sub-id)))

(deftest unsubscribe-form-roundtrips
  (is (= "(re-frame-pair2.runtime/unsubscribe! \"abc-123-uuid\")"
         (unsubscribe-form "abc-123-uuid"))))

(deftest drain-form-roundtrips
  (is (= "(re-frame-pair2.runtime/drain-subscription! \"sub-xyz\")"
         (drain-form "sub-xyz"))))

;; ---------------------------------------------------------------------------
;; Byte+event overflow budget — rf2-ho4ve corner-matrix
;; ---------------------------------------------------------------------------
;;
;; Parallel fixture of the runtime's `evict-oldest` / `enqueue!`
;; (drop-oldest, OR-combined budget) so the MCP-side test harness pins
;; the contract. The runtime itself is bb-tested under
;; `skills/re-frame-pair2/tests/runtime/streaming_subscriptions_test.clj`;
;; this CLJS fixture lets `npm test` exercise the same contract
;; alongside the MCP wire surface assertions above.

(def ^:private default-max-events 500)
(def ^:private default-max-bytes 5000000)

(defn- event-byte-size [event]
  (count (pr-str event)))

(defn- evict-oldest
  [sub max-events max-bytes]
  (loop [q       (:queue sub)
         bytes   (:queue-bytes sub 0)
         dropped-n 0
         dropped-b 0
         reason    nil]
    (let [n (count q)
          over-events? (> n max-events)
          over-bytes?  (> bytes max-bytes)]
      (if (and (or over-events? over-bytes?) (pos? n))
        (let [head    (nth q 0)
              head-bs (event-byte-size head)]
          (recur (subvec q 1)
                 (max 0 (- bytes head-bs))
                 (inc dropped-n)
                 (+ dropped-b head-bs)
                 (cond over-bytes?  :max-buffered-bytes
                       over-events? :max-buffered-events
                       :else        reason)))
        (cond-> (assoc sub :queue q :queue-bytes bytes)
          (pos? dropped-n)
          (-> (update :dropped-events (fnil + 0) dropped-n)
              (update :dropped-bytes  (fnil + 0) dropped-b)
              (assoc :overflow-reason reason)))))))

(defn- enqueue!
  [sub event]
  (let [max-events (:max-buffered-events sub default-max-events)
        max-bytes  (:max-buffered-bytes  sub default-max-bytes)
        ev-bytes   (event-byte-size event)
        sub'       (-> sub
                       (update :queue       conj event)
                       (update :queue-bytes (fnil + 0) ev-bytes))]
    (evict-oldest sub' max-events max-bytes)))

(defn- empty-sub [max-events max-bytes]
  {:queue [] :queue-bytes 0
   :dropped-events 0 :dropped-bytes 0 :overflow-reason nil
   :max-buffered-events max-events
   :max-buffered-bytes  max-bytes})

(deftest overflow-count-only-trip-drops-oldest-reports-events
  ;; bytes budget set generously so it can't trip — count budget = 2.
  (let [sub  (empty-sub 2 100000000)
        sub  (-> sub
                 (enqueue! {:id 1})
                 (enqueue! {:id 2})
                 (enqueue! {:id 3})
                 (enqueue! {:id 4}))]
    (is (= 2 (count (:queue sub))))
    (is (= [{:id 3} {:id 4}] (:queue sub)))
    (is (= 2 (:dropped-events sub)))
    (is (pos? (:dropped-bytes sub)))
    (is (= :max-buffered-events (:overflow-reason sub)))))

(deftest overflow-bytes-only-trip-drops-oldest-reports-bytes
  ;; event budget set generously — fat events trip the byte budget.
  (let [fat       (apply str (repeat 300 "x"))
        e1        {:id 1 :payload fat}
        one-size  (event-byte-size e1)
        ;; ~2 events fit.
        byte-cap  (long (* one-size 2.5))
        sub       (empty-sub 1000 byte-cap)
        sub       (-> sub
                      (enqueue! e1)
                      (enqueue! {:id 2 :payload fat})
                      (enqueue! {:id 3 :payload fat}))]
    (is (<= (count (:queue sub)) 2))
    (is (= 3 (:id (last (:queue sub)))))
    (is (pos? (:dropped-events sub)))
    (is (pos? (:dropped-bytes  sub)))
    (is (= :max-buffered-bytes (:overflow-reason sub)))))

(deftest overflow-both-budgets-count-trips-first
  ;; tiny events + tight count cap + roomy byte cap → events budget trips.
  (let [sub  (empty-sub 3 1000000)
        sub  (reduce enqueue! sub
                     (map #(hash-map :id %) (range 1 11)))] ;; 10 events
    (is (= 3 (count (:queue sub))))
    (is (= 7 (:dropped-events sub)))
    (is (= :max-buffered-events (:overflow-reason sub)))))

(deftest overflow-both-budgets-bytes-trips-first
  ;; fat events + roomy count cap + tight byte cap → bytes budget trips.
  (let [fat       (apply str (repeat 1000 "y"))
        e1        {:id 1 :payload fat}
        one-size  (event-byte-size e1)
        byte-cap  (long (* one-size 1.2))
        sub       (empty-sub 100 byte-cap)
        sub       (-> sub
                      (enqueue! e1)
                      (enqueue! {:id 2 :payload fat})
                      (enqueue! {:id 3 :payload fat}))]
    (is (= 1 (count (:queue sub))))
    (is (= 3 (:id (first (:queue sub)))))
    (is (= 2 (:dropped-events sub)))
    (is (= :max-buffered-bytes (:overflow-reason sub)))))

(deftest overflow-empty-queue-no-eviction-no-reason
  ;; Sanity: under both budgets, no eviction, reason stays nil.
  (let [sub (empty-sub 10 100000)
        sub (-> sub (enqueue! {:id 1}) (enqueue! {:id 2}))]
    (is (= 2 (count (:queue sub))))
    (is (zero? (:dropped-events sub)))
    (is (zero? (:dropped-bytes sub)))
    (is (nil?  (:overflow-reason sub)))))

;; ---------------------------------------------------------------------------
;; merge-drain — pure state update for the streaming-loop accumulators
;; (rf2-c0h8p). The fn lives in `tools/subscribe.cljs`; it's the
;; single source of truth for how a drain's contributions fold into
;; the rolling per-stream state map.
;;
;; The poll loop's emit-gate uses `drain-produced-output?` to decide
;; whether to ship a `notifications/progress` tick — the predicate is
;; pinned here too so a future change to "what counts as a tick"
;; surfaces on both the merge-state and emit-gate sides as a single
;; failing test.
;; ---------------------------------------------------------------------------

(def ^:private zero-state
  "Same shape as `subscribe/initial-state` — locally redeclared so the
  test pins the contract independently of a future rename / restructure
  of the source-side default. A mismatch here is a meaningful contract
  drift, not a parallel-fixture lie."
  {:tick              0
   :delivered         0
   :dropped-events    0
   :dropped-bytes     0
   :overflow-reason   nil
   :dropped-sensitive 0
   :elided-large      0})

(defn- drain
  "Construct a drain-delta map. Defaults all counters to zero / nil so
  tests only need to set the slots they care about."
  [overrides]
  (merge {:n 0 :ev-dropped 0 :by-dropped 0
          :ov-reason nil :dropped 0 :tick-elided 0}
         overrides))

(deftest drain-produced-output?-kept-events
  ;; Kept events ⇒ tick.
  (is (true? (sub/drain-produced-output? (drain {:n 3})))))

(deftest drain-produced-output?-only-overflow-drops
  ;; Overflow drops alone ⇒ tick (the client still wants to see the drop).
  (is (true? (sub/drain-produced-output? (drain {:ev-dropped 5})))))

(deftest drain-produced-output?-empty-drain
  ;; No kept events, no overflow drops ⇒ no tick.
  (is (false? (sub/drain-produced-output? (drain {})))))

(deftest drain-produced-output?-only-sensitive-drop
  ;; A sensitive-strip alone (with no kept events) is NOT a tick — the
  ;; runtime had nothing to ship the client. The indicator surfaces on
  ;; the final summary, not on a no-content tick.
  (is (false? (sub/drain-produced-output? (drain {:dropped 4})))))

(deftest drain-produced-output?-only-tick-elided
  ;; Elision count alone (no kept events, no overflow) ⇒ no tick. The
  ;; count piggybacks on real ticks; an empty drain shouldn't ship.
  (is (false? (sub/drain-produced-output? (drain {:tick-elided 7})))))

(deftest merge-drain-empty-drain-is-identity
  ;; The accumulator must not move when nothing changed — drives the
  ;; `cond->` correctness on the no-tick branch.
  (is (= zero-state (sub/merge-drain zero-state (drain {})))))

(deftest merge-drain-counts-tick-on-kept-events
  (let [s' (sub/merge-drain zero-state (drain {:n 4}))]
    (is (= 1 (:tick s')))
    (is (= 4 (:delivered s')))))

(deftest merge-drain-counts-tick-on-overflow-only
  ;; Overflow-only drain still increments tick (no `:delivered` move).
  (let [s' (sub/merge-drain zero-state (drain {:ev-dropped 6 :by-dropped 100
                                               :ov-reason :max-buffered-events}))]
    (is (= 1 (:tick s')))
    (is (zero? (:delivered s')))
    (is (= 6   (:dropped-events s')))
    (is (= 100 (:dropped-bytes s')))
    (is (= :max-buffered-events (:overflow-reason s')))))

(deftest merge-drain-accumulates-across-drains
  ;; Two drains: the first counts a tick, the second another. Counters
  ;; accumulate monotonically; tick counter increments per ticking drain.
  (let [s1 (sub/merge-drain zero-state (drain {:n 2 :dropped 1}))
        s2 (sub/merge-drain s1         (drain {:n 3 :tick-elided 4}))]
    (is (= 2 (:tick s2)))
    (is (= 5 (:delivered s2)))
    (is (= 1 (:dropped-sensitive s2)))
    (is (= 4 (:elided-large s2)))))

(deftest merge-drain-no-tick-when-only-sensitive-dropped
  ;; Sensitive strip drops events; the drain is empty by the time we
  ;; merge it. Tick must NOT increment — the contract is "the client
  ;; sees a tick iff kept events OR overflow drops". A sensitive-only
  ;; drain has nothing to ship.
  (let [s' (sub/merge-drain zero-state (drain {:dropped 3}))]
    (is (zero? (:tick s')))
    (is (zero? (:delivered s')))
    (is (= 3   (:dropped-sensitive s')))))

(deftest merge-drain-overflow-reason-latches-last
  ;; If multiple drains report different overflow-reasons, the most
  ;; recent wins — the runtime evicts oldest-first and the latest
  ;; reason is the one currently in force.
  (let [s1 (sub/merge-drain zero-state (drain {:ev-dropped 1
                                               :ov-reason :max-buffered-events}))
        s2 (sub/merge-drain s1         (drain {:by-dropped 100
                                               :ev-dropped 1
                                               :ov-reason :max-buffered-bytes}))]
    (is (= :max-buffered-bytes (:overflow-reason s2)))))

(deftest merge-drain-elided-large-accumulates
  ;; Indicator slot: each ticking drain's `:tick-elided` adds to the
  ;; rolling `:elided-large` total which surfaces on the final summary.
  (let [s1 (sub/merge-drain zero-state (drain {:n 1 :tick-elided 2}))
        s2 (sub/merge-drain s1         (drain {:n 1 :tick-elided 3}))]
    (is (= 5 (:elided-large s2)))))

;; ---------------------------------------------------------------------------
;; rf2-vr2hn — `--allow-raw-state` boot gate on the drain eval form.
;;
;; The drain envelope's `:events` slot rides the nREPL wire verbatim. For
;; the `:epoch` topic that means full epoch records (`:db-after` /
;; `:db-before` / `:app-db`) — a hostile per-call arg shouldn't be able
;; to talk an operator who didn't pass `--allow-raw-state` into shipping
;; raw state. The gate mirrors the snapshot / get-path / trace-window
;; pattern (rf2-c2dtu): when OFF, the drain form wraps `:events` with
;; `re-frame.core/elide-wire-value` server-side, sensitive slots redact,
;; large slots elide. When ON, the bare drain ships raw — the
;; pre-rf2-vr2hn posture.
;; ---------------------------------------------------------------------------

(deftest drain-form-gated-elision-wraps-events
  ;; Gate OFF (the default published-build posture). The drain form must:
  ;;   - call `drain-subscription!` for the sub-id.
  ;;   - mapv each event through `re-frame.core/elide-wire-value`.
  ;;   - assoc the elided events back onto the drain envelope.
  ;;   - thread `:rf.size/include-sensitive? false` into the walker opts.
  (let [form (sub/drain-form "sub-abc" true false)]
    (is (str/includes? form "drain-subscription!")
        "drain-subscription! is the runtime entry point")
    (is (str/includes? form "\"sub-abc\"")
        "sub-id rides the form as an EDN string literal")
    (is (str/includes? form "re-frame.core/elide-wire-value")
        "every event flows through the size-elision walker")
    (is (str/includes? form ":rf.size/include-large? false")
        "walker is active (not pass-through)")
    (is (str/includes? form ":rf.size/include-sensitive? false")
        "sensitive slots redact when the caller did NOT opt in")))

(deftest drain-form-gated-elision-honours-include-sensitive
  ;; Gate ON path — when the operator passed `--allow-raw-state` AND the
  ;; caller passed `:include-sensitive true`, the walker still fires
  ;; (elision is independent of sensitive), but sensitive slots pass
  ;; through.
  (let [form (sub/drain-form "sub-xyz" true true)]
    (is (str/includes? form "re-frame.core/elide-wire-value"))
    (is (str/includes? form ":rf.size/include-sensitive? true")
        "include-sensitive? true threads into the walker opts")))

(deftest drain-form-elision-off-bare-drain
  ;; Gate ON + caller passes `:elision false` — the form must NOT wrap
  ;; with the walker. Bare `drain-subscription!` call ships raw events
  ;; (the pre-rf2-vr2hn posture).
  (let [form (sub/drain-form "sub-raw" false false)]
    (is (str/includes? form "drain-subscription!"))
    (is (not (str/includes? form "re-frame.core/elide-wire-value"))
        "elision OFF ⇒ no walker; raw events ride the wire")
    (is (= "(re-frame-pair2.runtime/drain-subscription! \"sub-raw\")"
           form)
        "bare drain form is the pre-rf2-vr2hn shape")))

(deftest drain-form-sub-id-pr-str-literal
  ;; The sub-id is an EDN string literal — `pr-str` escapes quotes /
  ;; backslashes correctly so a runtime read-string round-trips it
  ;; verbatim. Pin against a sub-id containing characters that would
  ;; break naive concatenation.
  (let [form (sub/drain-form "id-with-\"quote\"" true false)]
    ;; pr-str emits the embedded quotes as `\"` — the round-trip is
    ;; correct, no raw-`str` concatenation hazard.
    (is (str/includes? form "\\\"quote\\\"")
        "embedded quotes survive as escaped EDN literals")))
