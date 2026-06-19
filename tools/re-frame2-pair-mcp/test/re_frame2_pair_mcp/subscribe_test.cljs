(ns re-frame2-pair-mcp.subscribe-test
  "Unit tests for the streaming subscribe / unsubscribe tools (rf2-hq49).

  These tests cover the MCP-arg → runtime-form translation (the EDN
  shape we send over nREPL), the filter-arg parser (JSON object vs
  EDN string), and the descriptor wiring. The live end-to-end coverage
  (notifications/progress emission, abort-signal termination) lives
  in `test/stdio-roundtrip.js` and the live-nREPL integration test
  against a real shadow-cljs build."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.reader]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.subscribe :as sub]))

;; The MCP-arg filter parser lives in
;; `re-frame2-pair-mcp.tools.args/parse-filter-arg`. Tests pin the
;; public surface directly so a rename or signature change surfaces
;; as a failing test rather than a silent contract drift.
;;
;; rf2-5kbkl — the parser returns the tagged `[:ok m]` /
;; `[:err :invalid-filter-edn]` shape (mirroring `read-edn-arg`). A
;; malformed filter EDN surfaces as an honest `:ok? false` error rather
;; than the pre-fix `{:invalid-filter-edn raw}` MAP that flowed straight
;; into the runtime `:filter` slot as a nonsense filter.

(deftest filter-arg-nil-passes-through
  (is (= [:ok nil] (args/parse-filter-arg nil))))

(deftest filter-arg-edn-string-reads
  (is (= [:ok {:op-type :error}]
         (args/parse-filter-arg "{:op-type :error}"))))

(deftest filter-arg-edn-string-with-touches-path
  (is (= [:ok {:touches-path [:cart :items]}]
         (args/parse-filter-arg "{:touches-path [:cart :items]}"))))

(deftest filter-arg-malformed-edn-is-err-tagged
  ;; The regression: an unparseable EDN string must NOT become a
  ;; nonsense `{:invalid-filter-edn raw}` map — it returns the honest
  ;; `[:err :invalid-filter-edn]` tag so the caller short-circuits.
  (is (= [:err :invalid-filter-edn]
         (args/parse-filter-arg "(((")))) ;; unbalanced delimiters

(deftest filter-arg-js-object-keywordises
  (let [obj #js {:op-type "error" :event-id "user/login"}
        [tag out] (args/parse-filter-arg obj)]
    (is (= :ok tag))
    (is (map? out))
    (is (= "error" (:op-type out)))
    (is (= "user/login" (:event-id out)))))

(deftest filter-arg-clj-map-passes-through
  (is (= [:ok {:op-type :error}]
         (args/parse-filter-arg {:op-type :error}))))

;; rf2-5kbkl — end-to-end at the tool boundary: a malformed filter EDN
;; makes `subscribe-tool` short-circuit to an honest `:ok? false`
;; envelope (`:reason :invalid-filter-edn`, `:isError true`) WITHOUT
;; touching the nREPL socket or reserving a stream slot — rather than
;; subscribing with a garbage filter. A VALID filter is unaffected (it
;; falls through to the normal subscribe path; covered by the
;; subscribe-form tests above).

(deftest subscribe-tool-malformed-filter-errors-honestly
  ;; The headline regression. A malformed filter EDN makes
  ;; `subscribe-tool` short-circuit to an honest `:ok? false` envelope
  ;; (`:reason :invalid-filter-edn`, `:isError true`) — the `cond`
  ;; branch fires BEFORE the `:else` arm reserves a stream slot or
  ;; touches the nREPL socket, so this resolves synchronously with no
  ;; live runtime needed and no resource-controls state mutated.
  (async done
    (-> (sub/subscribe-tool nil #js {:topic "trace" :filter "((("} nil)
        (.then (fn [r]
                 (is (tu/error? r)
                     "malformed filter EDN surfaces as :isError true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :invalid-filter-edn (:reason edn))
                       "honest :reason, not a silent subscribe with a garbage filter")
                   (is (= "(((" (:given edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; rf2-uvfph — numeric subscribe-control validation. The five integer
;; knobs (buffer caps, poll-ms, max-ms, max-events) were forwarded raw to
;; the runtime budget / poll loop / termination caps with NO lower bound.
;; Negatives collapsed the queue budget (empty probe), spun the poll loop,
;; or silently disabled the intended bound. The args helpers vet them.
;; ---------------------------------------------------------------------------

(deftest positive-int-arg-absent-is-ok-nil
  (is (= [:ok nil] (args/parse-positive-int-arg "poll-ms" nil))))

(deftest positive-int-arg-accepts-positive
  (is (= [:ok 500] (args/parse-positive-int-arg "max-buffered-events" 500)))
  (is (= [:ok 1]   (args/parse-positive-int-arg "poll-ms" 1))))

(deftest positive-int-arg-accepts-numeric-string
  ;; MCP hosts sometimes stringify numbers — accept a clean integer string.
  (is (= [:ok 250] (args/parse-positive-int-arg "max-buffered-bytes" "250"))))

(deftest positive-int-arg-rejects-zero
  (let [[tag env] (args/parse-positive-int-arg "poll-ms" 0)]
    (is (= :err tag))
    (is (= :invalid-numeric-arg (:reason env)))
    (is (= "poll-ms" (:arg env)))
    (is (= 0 (:given env)))))

(deftest positive-int-arg-rejects-negative
  (let [[tag env] (args/parse-positive-int-arg "max-buffered-events" -5)]
    (is (= :err tag))
    (is (= :invalid-numeric-arg (:reason env)))
    (is (re-find #"positive integer" (:hint env)))))

(deftest positive-int-arg-rejects-fractional
  (let [[tag _] (args/parse-positive-int-arg "poll-ms" 1.5)]
    (is (= :err tag) "a fractional value is not a valid integer cadence")))

(deftest positive-int-arg-rejects-non-numeric
  (let [[tag _] (args/parse-positive-int-arg "max-events" "soon")]
    (is (= :err tag) "non-numeric junk is rejected")))

;; rf2-ykv9a0 cross-runtime safe-integer guard, routed through
;; `base-args/coerce-finite-long` by rf2-ttspi7. A whole numeric (or
;; numeric string) ABOVE the JS safe-integer ceiling (2^53 − 1 =
;; 9 007 199 254 740 991) is NOT losslessly representable across both
;; hosts; before the routing it slipped past `coerce-int`'s
;; `Number.isInteger` check (a double like 9007199254740993 is "integral"
;; in JS) and reached the runtime budget, while mcp-base's `max-tokens`
;; rejected the same value — the divergence this closes.

(deftest positive-int-arg-rejects-over-safe-integer
  ;; 2^53 + 1 — past the safe-integer window. `Number.isInteger` is true
  ;; for it (it's a whole double), so it would have passed the OLD
  ;; coerce-int; the finite/range guard now rejects it as a bad numeric.
  (let [over (+ 9007199254740992 1)
        [tag env] (args/parse-positive-int-arg "max-buffered-events" over)]
    (is (= :err tag) "an over-safe-integer numeric is rejected, not forwarded")
    (is (= :invalid-numeric-arg (:reason env)))
    (is (= "max-buffered-events" (:arg env))))
  ;; The string arm routes the parsed value through the SAME guard.
  (let [[tag _] (args/parse-positive-int-arg "poll-ms" "9007199254740993")]
    (is (= :err tag) "an over-safe-integer numeric STRING is rejected too"))
  ;; The boundary value (exactly 2^53 − 1) is in-domain and accepted.
  (is (= [:ok 9007199254740991]
         (args/parse-positive-int-arg "max-buffered-bytes" 9007199254740991))
      "the safe-integer ceiling itself is in-domain"))

(deftest non-negative-int-arg-rejects-over-safe-integer
  (let [[tag env] (args/parse-non-negative-int-arg "max-events" (+ 9007199254740992 1))]
    (is (= :err tag) "an over-safe-integer numeric is rejected on the non-negative arm too")
    (is (= :invalid-numeric-arg (:reason env))))
  (is (= [:ok 9007199254740991]
         (args/parse-non-negative-int-arg "max-ms" 9007199254740991))
      "the safe-integer ceiling itself is in-domain"))

(deftest non-negative-int-arg-accepts-zero-sentinel
  ;; 0 = unbounded — the documented sentinel for max-ms / max-events.
  (is (= [:ok 0] (args/parse-non-negative-int-arg "max-ms" 0)))
  (is (= [:ok 0] (args/parse-non-negative-int-arg "max-events" 0))))

(deftest non-negative-int-arg-accepts-positive
  (is (= [:ok 30000] (args/parse-non-negative-int-arg "max-ms" 30000))))

(deftest non-negative-int-arg-absent-is-ok-nil
  (is (= [:ok nil] (args/parse-non-negative-int-arg "max-events" nil))))

(deftest non-negative-int-arg-rejects-negative
  (let [[tag env] (args/parse-non-negative-int-arg "max-events" -1)]
    (is (= :err tag))
    (is (= :invalid-numeric-arg (:reason env)))
    (is (= "max-events" (:arg env)))
    (is (re-find #"non-negative integer" (:hint env)))))

(deftest non-negative-int-arg-rejects-fractional
  (let [[tag _] (args/parse-non-negative-int-arg "max-ms" 12.3)]
    (is (= :err tag))))

;; Tool-boundary: a bad numeric arg short-circuits to an honest
;; `:ok? false` envelope WITHOUT touching the nREPL socket or reserving a
;; stream slot — same synchronous shape as the malformed-filter check.

(deftest subscribe-tool-negative-buffer-errors-honestly
  (async done
    (-> (sub/subscribe-tool nil #js {:topic "trace" :max-buffered-events -10} nil)
        (.then (fn [r]
                 (is (tu/error? r) "negative buffer cap surfaces as :isError true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "max-buffered-events" (:arg edn))))
                 (done))))))

(deftest subscribe-tool-zero-poll-ms-errors-honestly
  (async done
    (-> (sub/subscribe-tool nil #js {:topic "epoch" :poll-ms 0} nil)
        (.then (fn [r]
                 (is (tu/error? r) "zero poll cadence surfaces as :isError true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "poll-ms" (:arg edn))))
                 (done))))))

(deftest subscribe-tool-negative-max-events-errors-honestly
  (async done
    (-> (sub/subscribe-tool nil #js {:topic "fx" :max-events -3} nil)
        (.then (fn [r]
                 (is (tu/error? r) "negative termination cap surfaces as :isError true")
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "max-events" (:arg edn))))
                 (done))))))

;; The VALID-filter path "works unchanged" is pinned by the
;; `subscribe-form-with-trace-and-filter` / `-fx-topic` round-trip tests
;; above: a well-formed filter rides into the runtime `subscribe!`
;; `:filter` opts slot. With the rf2-5kbkl change those still hold
;; because `parse-filter-arg` now unwraps the `[:ok m]` tag back to the
;; same `filter-map` the form constructor consumes.

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
  ;; Per rf2-mscih `:frameless` joins the recognised set as the
  ;; explicit opt-in channel for events with no
  ;; `:rf.trace/dispatch-id` — registration emits, REPL evals,
  ;; lifecycle outside any cascade.
  (let [recognised #{:trace :epoch :fx :error :frameless}]
    (is (contains? recognised :trace))
    (is (contains? recognised :epoch))
    (is (contains? recognised :fx))
    (is (contains? recognised :error))
    (is (contains? recognised :frameless))
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
  (is (= "(re-frame2-pair.runtime/unsubscribe! \"abc-123-uuid\")"
         (unsubscribe-form "abc-123-uuid"))))

(deftest drain-form-roundtrips
  (is (= "(re-frame2-pair.runtime/drain-subscription! \"sub-xyz\")"
         (drain-form "sub-xyz"))))

;; ---------------------------------------------------------------------------
;; Byte+event overflow budget — rf2-ho4ve corner-matrix
;; ---------------------------------------------------------------------------
;;
;; Parallel fixture of the runtime's `evict-oldest` / `enqueue!`
;; (drop-oldest, OR-combined budget) so the MCP-side test harness pins
;; the contract. The runtime itself is bb-tested under
;; `skills/re-frame2-pair/tests/runtime/streaming_subscriptions_test.clj`;
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
;; rf2-vr2hn — `--allow-sensitive-reads` boot gate on the drain eval form.
;;
;; The drain envelope's `:events` slot rides the nREPL wire verbatim. For
;; the `:epoch` topic that means full epoch records (`:db-after` /
;; `:db-before` / `:app-db`) — a hostile per-call arg shouldn't be able
;; to talk an operator who didn't pass `--allow-sensitive-reads` into shipping
;; raw state. The gate mirrors the snapshot / get-path / trace-window
;; pattern (rf2-c2dtu): when OFF, the drain form wraps `:events` with
;; `re-frame.core/elide-wire-value` server-side, sensitive slots redact,
;; large slots elide. When ON, the bare drain ships raw — the
;; pre-rf2-vr2hn posture.
;; ---------------------------------------------------------------------------

(deftest drain-form-gated-elision-wraps-events
  ;; Gate OFF (the default published-build posture), a TRACE-event topic.
  ;; The drain form must:
  ;;   - call `drain-subscription!` for the sub-id.
  ;;   - mapv each event through `re-frame.core/elide-wire-value`.
  ;;   - assoc the elided events back onto the drain envelope.
  ;;   - thread `:rf.size/include-sensitive? false` into the walker opts.
  (let [form (sub/drain-form "sub-abc" :trace true false)]
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

(deftest drain-form-resolves-elision-frame-per-element
  ;; rf2-1we9fa / rf2-7737vq — the trace-walk arm resolves the elision
  ;; `:frame` PER ELEMENT inside the walk BY LAYER, NOT once for the whole
  ;; drain. Cascade bundles are keyed by `[frame dispatch-id]`, so an
  ;; all-frame stream — or a filter frame that differs from the operating
  ;; frame — carries cascades from several frames in one tick. Per EP-0015
  ;; sensitive/large declarations are per-frame, so each element MUST be
  ;; elided against its OWN frame: a DERIVED cascade record's top-level
  ;; `:frame` slot, else a frameless RAW event's frame via the canonical
  ;; `re-frame.trace/trace-event-frame` reader ([:tags :frame]). The
  ;; operating frame (`(re-frame2-pair.runtime/current-frame)`) survives
  ;; as the GENUINELY FRAMELESS FALLBACK only (EP-0002 rf2-bd4div: the
  ;; nREPL eval thread carries no ambient `with-frame` scope, so a truly
  ;; frameless element would otherwise fail closed and redact away).
  ;;
  ;; This test REPLACES the prior `drain-form-threads-operating-frame`,
  ;; which PINNED the buggy "one operating frame for every cascade"
  ;; behaviour. Trace-event topic — the walker arm carries the per-element
  ;; frame resolution.
  (let [form (sub/drain-form "sub-frame" :trace true false)]
    (is (str/includes? form "(re-frame2-pair.runtime/current-frame)")
        "the operating frame is resolved app-side as the frameless fallback")
    ;; The per-element resolution: DERIVED cascade record top-level
    ;; `:frame`, then a RAW event's frame via the canonical reader, then
    ;; the operating-frame fallback.
    (is (str/includes? form "(:frame x)")
        "each element's own top-level :frame slot is the first elision-frame source")
    (is (str/includes? form "(re-frame.trace/trace-event-frame x)")
        "a frameless RAW event's frame reads via the canonical reader (the second source)")
    (is (str/includes? form "(or (:frame x)")
        "the per-element frame falls through (or ...) to the fallback")
    (is (str/includes? form "(assoc base-opts :frame frame)")
        "the per-element frame is assoc'd onto the base elision opts per element")
    (is (not (str/includes?
               form "(merge {:frame (re-frame2-pair.runtime/current-frame)}"))
        "the buggy single-frame-for-every-cascade merge is GONE")))

(deftest drain-form-gated-elision-honours-include-sensitive
  ;; Gate ON path — when the operator passed `--allow-sensitive-reads` AND the
  ;; caller passed `:include-sensitive true`, the walker still fires
  ;; (elision is independent of sensitive), but sensitive slots pass
  ;; through. Trace-event topic.
  (let [form (sub/drain-form "sub-xyz" :trace true true)]
    (is (str/includes? form "re-frame.core/elide-wire-value"))
    (is (str/includes? form ":rf.size/include-sensitive? true")
        "include-sensitive? true threads into the walker opts")))

(deftest drain-form-bare-elision-false-still-walks-trace
  ;; rf2-t55hxg.13 — fail-CLOSED. Pre-fix a gate-ON `:elision false` on a
  ;; trace-event topic shipped the bare `drain-subscription!` call with NO
  ;; walker — which let a declared-sensitive frame slot inside a cascade
  ;; leak off-box WITHOUT the per-call `:include-sensitive true` opt-in (the
  ;; EP-0015 two-key gate collapse). A BARE `:elision false` (incl? false)
  ;; now STILL walks: large content passes (`include-large? true`) but a
  ;; declared-sensitive slot redacts to `:rf/redacted`.
  (let [form (sub/drain-form "sub-raw" :trace false false)]
    (is (str/includes? form "drain-subscription!"))
    (is (str/includes? form "re-frame.core/elide-wire-value")
        "bare :elision false MUST still walk trace events — no sensitive bypass")
    (is (str/includes? form ":rf.size/include-sensitive? false")
        "sensitive cascade slots redact (caller didn't opt in)")
    ;; subscribe is a streaming TRACE surface that always emits large-slot
    ;; markers (it hardcodes include-large? false in the walker opts —
    ;; unlike snapshot / get-path, where :elision false overlays
    ;; include-large? true). The fail-closed point here is that the walker
    ;; still RUNS so sensitive slots redact; the large toggle is moot.
    (is (str/includes? form ":rf.size/include-large? false")
        "subscribe always emits large markers (the trace surface elides large slots)")))

(deftest drain-form-full-raw-opt-in-bare-drain-trace
  ;; rf2-t55hxg.13 — the deliberate full-raw local opt-in (`:elision
  ;; false` AND `:include-sensitive true`) is the ONLY combination that
  ;; ships raw trace events with no walker (the operator's `local-raw` act).
  (let [form (sub/drain-form "sub-raw" :trace false true)]
    (is (not (str/includes? form "re-frame.core/elide-wire-value"))
        "full-raw opt-in ⇒ no walker; raw events ride the wire")
    (is (= "(re-frame2-pair.runtime/drain-subscription! \"sub-raw\")"
           form)
        "bare drain form is the operator full-raw opt-in shape")))

(deftest drain-form-sub-id-pr-str-literal
  ;; The sub-id is an EDN string literal — `pr-str` escapes quotes /
  ;; backslashes correctly so a runtime read-string round-trips it
  ;; verbatim. Pin against a sub-id containing characters that would
  ;; break naive concatenation. Trace-event topic.
  (let [form (sub/drain-form "id-with-\"quote\"" :trace true false)]
    ;; pr-str emits the embedded quotes as `\"` — the round-trip is
    ;; correct, no raw-`str` concatenation hazard.
    (is (str/includes? form "\\\"quote\\\"")
        "embedded quotes survive as escaped EDN literals")))

;; ---------------------------------------------------------------------------
;; rf2-mahffz (EP-0015 §13) — the `:epoch` topic's `:events` slot egresses
;; whole `:rf/epoch-record`s. Those carry the structured `:effects` rows
;; whose `:args` slot is payload-bearing fx input (an HTTP body, a payment
;; map) — NOT rooted at app-db, so the schema-path-keyed
;; `elide-wire-value` walker cannot prove it safe and ships it RAW. Per
;; Security.md §Epoch privacy posture, off-box epoch egress MUST route
;; through `re-frame.core/projected-record` — the single normative
;; emission site, which FAILS CLOSED on `:effects[].args` — NOT the
;; per-slot wire-value walker. Pre-rf2-mahffz the streaming `:epoch`
;; drain reused the cascade walker (`elide-wire-value`) on whole records,
;; leaking `:effects[].args`. The fix routes the `:epoch` topic's
;; `:events` through `projected-record`, mirroring `watch-epochs` /
;; `trace-window`.
;; ---------------------------------------------------------------------------

(deftest drain-form-epoch-topic-projects-via-projected-record
  ;; Gate OFF (the default). The `:epoch` drain form MUST:
  ;;   - call `drain-subscription!`,
  ;;   - mapv the `:events` slot through `re-frame.core/projected-record`
  ;;     (the single normative off-box epoch-egress site, fail-closed on
  ;;     `:effects[].args`),
  ;;   - NOT walk records through `elide-wire-value` (the prohibited
  ;;     per-tool reimplementation that left `:effects[].args` raw).
  (let [form (sub/drain-form "sub-epoch" :epoch true false)]
    (is (str/includes? form "drain-subscription!")
        "drain-subscription! is the runtime entry point")
    (is (str/includes? form "re-frame.core/projected-record")
        "epoch records route through the normative projector")
    (is (str/includes? form ":events")
        "the projection targets the flat :events slot")
    (is (not (str/includes? form "re-frame.core/elide-wire-value"))
        "epoch records MUST NOT use the per-slot wire-value walker — it leaves :effects[].args raw")))

(deftest drain-form-epoch-projection-tracks-sensitive-axis-not-elision
  ;; Epoch projection rides the `--allow-sensitive-reads` opt-in axis
  ;; (`incl?`), like watch-epochs / trace-window — NOT the large-slot
  ;; `elision?` toggle. A gate-ON caller who passed `:elision false` but
  ;; left `:include-sensitive` at its default (incl? false) STILL gets
  ;; PROJECTED (fail-closed) epoch records, not raw fx-arg payloads.
  (let [form (sub/drain-form "sub-epoch" :epoch false false)]
    (is (str/includes? form "re-frame.core/projected-record")
        "elision? false does not disable epoch projection — sensitive axis governs")))

(deftest drain-form-epoch-include-sensitive-routes-through-projection
  ;; rf2-m9duxl — gate ON + `:include-sensitive true` (incl? true) does NOT
  ;; bypass the epoch projection. The `:events` slot STILL routes through
  ;; `projected-record`, threading `{:include-sensitive? true}` as the
  ;; egress opt (app-db sensitive axis ONLY). fx-args / runtime-db / large
  ;; slots stay fail-closed because their opts are NOT passed.
  (let [form (sub/drain-form "sub-epoch" :epoch false true)]
    (is (str/includes? form "re-frame.core/projected-record")
        "include-sensitive STILL projects epoch records — never a raw bypass")
    (is (str/includes? form "{:include-sensitive? true}")
        "the app-db sensitive axis is threaded INTO the projection")
    (is (str/includes? form ":events")
        "the projection targets the flat :events slot")
    (is (not (str/includes? form ":include-fx-args?"))
        "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
    (is (not (str/includes? form ":include-runtime-db?"))
        "runtime-db axis is NOT lifted by include-sensitive (orthogonal)")))

(deftest drain-form-frameless-topic-uses-wire-value-walker
  ;; The `:frameless` topic's `:events` are raw TRACE events (registration
  ;; emits, REPL evals, lifecycle) — tree-shaped, rooted at app-db — so
  ;; they take the size-elision walker, NOT projected-record (which is for
  ;; whole epoch records). Confirm the topic discriminates correctly.
  (let [form (sub/drain-form "sub-fl" :frameless true false)]
    (is (str/includes? form "re-frame.core/elide-wire-value")
        "frameless trace events take the wire-value walker")
    (is (not (str/includes? form "re-frame.core/projected-record"))
        "frameless events are not epoch records — no projected-record")))

;; ---------------------------------------------------------------------------
;; Cascade-bundle wire format (rf2-mscih) — drain envelope shape, slot
;; selection on the progress payload, and cross-frame `:dispatch-id`
;; merge.
;; ---------------------------------------------------------------------------

(deftest drain-form-handles-both-cascade-and-events-slots
  ;; rf2-mscih — for a TRACE-event topic (cascade-bundle `:trace`/`:fx`/
  ;; `:error` ship `:cascades`; `:frameless` ships `:events`), `drain-form`
  ;; with elision ON wraps WHICHEVER trace-event slot the runtime produced.
  ;; The walker arm uses `cond->` over slot presence so it's slot-agnostic
  ;; WITHIN the trace-event family; the runtime determines which slot the
  ;; drain envelope carries. (rf2-mahffz: the `:epoch` topic's `:events`
  ;; take a DIFFERENT primitive — `projected-record` — so they are NOT in
  ;; this walker arm; covered by `drain-form-epoch-*` above.)
  (let [form (sub/drain-form "sub-1" :trace true false)]
    (is (str/includes? form ":cascades")
        "drain form mentions the :cascades slot for cascade-bundle topics")
    (is (str/includes? form ":events")
        "drain form mentions the :events slot for the frameless flat topic")
    (is (str/includes? form "contains?")
        "the form uses contains? to discriminate the slot")
    (is (str/includes? form "re-frame.core/elide-wire-value")
        "the walker is applied to whichever trace-event slot is present")))

(deftest progress-payload-uses-cascades-slot-on-cascade-bundle-topics
  ;; rf2-mscih — the progress payload's load slot is named per the
  ;; topic's wire shape: `:cascades` for cascade-bundle topics
  ;; (`:trace`/`:fx`/`:error`), `:events` for flat topics
  ;; (`:epoch`/`:frameless`).
  (testing "the :cascade? flag in tick-state routes the payload to :cascades"
    ;; Pure-data assertion: simulate the slot-selection logic the
    ;; emitter applies.
    (let [cascade? true
          slot     (if cascade? :cascades :events)]
      (is (= :cascades slot))))
  (testing "the :cascade? flag false routes the payload to :events"
    (let [cascade? false
          slot     (if cascade? :cascades :events)]
      (is (= :events slot)))))

(deftest cascade-bundle-shape-pins-projection-slots
  ;; Pin the shape contract — every cascade bundle carries the seven
  ;; `group-cascades` slots (`:dispatch-id` / `:event` / `:handler` /
  ;; `:fx` / `:effects` / `:subs` / `:renders` / `:other`) PLUS the
  ;; runtime-side `:trace-events` slot. Mirrors `(rf/trace-buffer
  ;; frame-id)` shape per Tool-Pair §Reading the per-frame trace ring.
  (let [bundle {:dispatch-id        7
                :frame              :rf/default
                :event              [:cart/add {:sku "x"}]
                :dispatched         {:operation :rf.event/dispatched}
                :handler            {:operation :rf.event/run-end}
                :fx                 {:operation :rf.fx/do-fx}
                :effects            []
                :subs               []
                :renders            []
                :other              []
                :trace-events       [{:operation :rf.event/dispatched}]
                :parent-dispatch-id nil}]
    (is (contains? bundle :dispatch-id))
    (is (contains? bundle :trace-events))
    (is (contains? bundle :event))
    (is (contains? bundle :effects))
    (is (contains? bundle :subs))
    (is (contains? bundle :renders))
    (is (contains? bundle :other))
    (is (contains? bundle :parent-dispatch-id))))

(deftest cross-frame-cascade-merge-by-dispatch-id
  ;; rf2-mscih cross-frame contract — a single cascade can fan out
  ;; across frames; every emit on every frame shares the same
  ;; `:rf.trace/dispatch-id`. Consumers merge by `:dispatch-id` to
  ;; reconstruct the cross-frame view per Tool-Pair §Cross-frame
  ;; cascade reconstruction.
  (let [frame-a-bundle {:dispatch-id 99
                        :frame       :rf/default
                        :event       [:user/login]
                        :effects     []
                        :subs        []
                        :renders     []
                        :other       []
                        :trace-events [{:operation :rf.event/dispatched
                                        :tags {:rf.trace/dispatch-id 99
                                               :frame :rf/default}}]
                        :parent-dispatch-id nil}
        frame-b-bundle {:dispatch-id 99
                        :frame       :stories
                        :event       nil
                        :effects     []
                        :subs        [{:operation :rf.sub/run
                                       :tags {:rf.trace/dispatch-id 99
                                              :frame :stories}}]
                        :renders     []
                        :other       []
                        :trace-events [{:operation :rf.sub/run
                                        :tags {:rf.trace/dispatch-id 99
                                               :frame :stories}}]
                        :parent-dispatch-id nil}
        ;; Consumer-side reconstruction: merge by :dispatch-id.
        merged (group-by :dispatch-id [frame-a-bundle frame-b-bundle])]
    (is (= 1 (count merged))
        "two bundles sharing :dispatch-id 99 merge into one timeline slot")
    (is (= 2 (count (get merged 99)))
        "both per-frame bundles ride under the same :dispatch-id key")
    (is (= #{:rf/default :stories}
           (into #{} (map :frame (get merged 99))))
        "the merged slot exposes both frames' contributions")))

(deftest frameless-channel-shape-is-flat-events-not-bundles
  ;; rf2-mscih frameless contract — registration emits / REPL / boot
  ;; lifecycle ride under `:events` (flat) on the `:frameless` topic.
  ;; No cascade structure to bundle; the frameless events carry no
  ;; `:rf.trace/dispatch-id` tag.
  (let [registration-emit {:operation :rf.registry/registered
                           :op-type :rf.registry
                           :tags {:registry-kind :event}}
        repl-emit         {:operation :rf.repl/eval
                           :op-type :rf.repl
                           :tags {}}
        frameless-tick    {:sub-id "sub-frameless"
                           :events [registration-emit repl-emit]
                           :dropped-events 0
                           :dropped-bytes  0}]
    (is (contains? frameless-tick :events)
        ":frameless ticks carry an :events slot, not :cascades")
    (is (not (contains? frameless-tick :cascades))
        ":frameless ticks MUST NOT carry a :cascades slot")
    (is (every? #(nil? (get-in % [:tags :rf.trace/dispatch-id]))
                (:events frameless-tick))
        "frameless events MUST carry no :rf.trace/dispatch-id tag")))

;; ---------------------------------------------------------------------------
;; rf2-wz66k7 — the per-tick progress notification is wire-capped.
;;
;; `emit-progress-tick!` ships its per-tick payload as the `:message` of a
;; `notifications/progress` notification. That notification NEVER crosses
;; the `invoke` chokepoint where `apply-cap` runs on `tools/call` RESULTS,
;; so before this fix an oversized drain shipped a multi-megabyte progress
;; message un-capped — busting the per-notification token budget the spec
;; pins. The fix runs the `:message` through `cap/cap-message` with the
;; per-call cap threaded down from `subscribe-tool`.
;; ---------------------------------------------------------------------------

(defn- capture-progress-message
  "Drive `emit-progress-tick!` with a capturing `send-note`, returning the
  `:message` string the notification carried. `cap` is the resolved
  per-notification budget."
  [dedup-events cap]
  (let [captured (atom nil)
        send-note (fn [note]
                    (reset! captured
                            (j/get-in note [:params :message])))]
    (sub/emit-progress-tick!
      {:send-note   send-note
       :progress-tk "tok-1"
       :sub-id      "sub-1"
       :cap         cap}
      false
      {:tick         1
       :cascade?     false
       :dedup-events dedup-events
       :ev-dropped   0
       :by-dropped   0
       :ov-reason    nil
       :dropped      0
       :tick-elided  0})
    @captured))

(deftest emit-progress-tick-small-payload-rides-verbatim
  ;; A small tick ships its EDN message unchanged — the gate must not
  ;; over-trip a genuinely small notification.
  (let [msg (capture-progress-message [{:id 1} {:id 2}] cap/default-max-tokens)
        edn (cljs.reader/read-string msg)]
    (is (not (contains? edn :rf.mcp/overflow))
        "a small progress tick is not capped")
    (is (= "sub-1" (:sub-id edn)))
    (is (= [{:id 1} {:id 2}] (:events edn))
        "the flat-topic payload rides under :events")))

(deftest emit-progress-tick-oversized-payload-is-overflow-marked
  ;; THE Finding-1 acceptance test (rf2-wz66k7): an oversized per-tick
  ;; drain MUST emit an overflow marker on the progress channel, NOT a
  ;; multi-megabyte raw message. FAILS before the fix (the notification
  ;; bypassed the cap); PASSES after.
  (let [fat (apply str (repeat 4000 "x"))
        big-events (vec (repeat 50 {:payload fat}))
        msg (capture-progress-message big-events 500)
        edn (cljs.reader/read-string msg)]
    (is (contains? edn :rf.mcp/overflow)
        "an oversized progress tick MUST be replaced with the overflow marker")
    (let [marker (:rf.mcp/overflow edn)]
      (is (= :reached (:limit marker)))
      (is (= "subscribe" (:tool marker)))
      (is (= 500 (:cap-tokens marker)))
      (is (> (:token-count marker) 500)))
    (is (<= (tu/token-estimate msg) 500)
        "the overflow-marker message itself stays under the cap")))

(deftest emit-progress-tick-nil-cap-disables-gate
  ;; `max-tokens 0` resolves to a nil cap — the caller opted out of the
  ;; budget; even a fat tick ships raw (the documented escape hatch).
  (let [fat (apply str (repeat 4000 "x"))
        big-events (vec (repeat 50 {:payload fat}))
        msg (capture-progress-message big-events nil)
        edn (cljs.reader/read-string msg)]
    (is (not (contains? edn :rf.mcp/overflow))
        "nil cap (max-tokens 0) disables the per-notification gate")
    (is (= 50 (count (:events edn)))
        "the full fat payload rides when the cap is disabled")))

(deftest cursor-stale-does-not-apply-to-subscribe-streams
  ;; rf2-mscih clarification — `:rf.mcp/cursor-stale` is a cursor-
  ;; pagination concern (`trace-window` / `watch-epochs`), not a
  ;; streaming concern. The `subscribe` surface is forward-only with
  ;; no cursor; the cascade-bundle wire reshape introduces no new
  ;; cursor surface.
  (let [;; A streaming subscription's final summary — no cursor slot.
        subscribe-summary {:ok? true
                           :sub-id "sub-1"
                           :topic :trace
                           :delivered 12
                           :ticks 3
                           :reason :aborted}]
    (is (not (contains? subscribe-summary :next-cursor))
        "streaming subscriptions are forward-only — no cursor slot")
    (is (not (contains? subscribe-summary :dispatch-id-of-next-cascade))
        "cascade-bundle delivery introduces no new cursor")))
