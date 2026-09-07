# `cap` — wire-boundary token-budget cap pipeline

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the algorithm that measures a consumer's serialized payload slots and replaces an over-budget result with an overflow marker. `ResultIO` isolates each server's native result shape.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`cap` owns:

- The two-stage cap algorithm (sum tokens + chars in one pass → primary token gate OR secondary char gate → pass-through or replace).
- The `max-tokens` per-call cap resolver (`0` resolves to `nil` = disabled; absent / non-number → `default-max-tokens`; negative, fractional-in-(0,1), or non-finite / out-of-range → `{:rf.mcp/invalid-arg {...}}` rejection).
- The `ResultIO` protocol — the per-consumer specialisation hook.
- The one-pass replacement invariant: an overflow result is built once and is not recursively capped by this function.

`cap` does NOT own:

- The overflow marker SHAPE — that's [`overflow.md`](overflow.md).
- The token-estimate function — also [`overflow.md`](overflow.md) (`token-estimate`).
- The result-map shape per consumer — each consumer reifies `ResultIO` to express its own platform's result shape.

## Algorithm

The pipeline is a **two-stage** gate that folds both sums in a **single pass** over every string returned by `wire-payload-strings`:

1. **Resolve the cap.** `max-tokens` returns `nil` for `0`, the 5000 default for an absent/non-number value, a floored positive integer for an in-range number, or an invalid-arg marker for an out-of-domain number. Consumers reject the marker before calling `apply-cap`.
2. **Single-pass token + character sum.** One `transduce` folds both `Σ token-estimate` and `Σ (count s)` across all measured payload strings, including a serialized `:structuredContent` slot when present.
3. **Two-stage over-budget decision** (`over-cap?`): trip when either the token sum exceeds `cap` or the character sum exceeds `cap * byte-cap-multiplier`. The second gate bounds loss from flooring each short payload string independently. `reported-count` uses the character count when that gate trips, otherwise the token estimate.
4. **Pass-through or replace.** Under-budget responses pass through unchanged; over-budget responses are replaced with a fresh result carrying the `:rf.mcp/overflow` marker (built via `overflow/overflow-payload`).

```clojure
(defn apply-cap [io result {:keys [tool cap hint]}] ; cap = resolved max-tokens
  (if (or (nil? cap) (nil? result))
    result                                          ; nil cap ⇒ disabled
    (let [{:keys [tokens chars]}                    ; single-pass fold
          (transduce (filter string?)
                     (completing
                       (fn [acc s]
                         {:tokens (+ (:tokens acc) (overflow/token-estimate s))
                          :chars  (+ (:chars acc)  (count s))}))
                     {:tokens 0 :chars 0}
                     (wire-payload-strings io result))]
      (if-not (over-cap? tokens chars cap)          ; token gate OR char gate
        result                                      ; under-budget
        (let [marker (overflow/overflow-payload
                       {:tool        tool
                        :token-count (reported-count tokens chars cap)
                        :cap         cap
                        :hint        hint})]
          (build-overflow-result io marker result)))))) ; over-budget
```

The algorithm runs synchronously at the wire boundary, after the response body has been assembled but before the consumer-side transport ships it. The cost is one walk over the consumer's measured wire payload strings (every serialized payload-bearing slot, not only `:content`) — O(measured payload strings).

### Why the secondary character gate is reachable

A naive reading argues the char gate is structurally dominated: for a SINGLE string of length `L`, `chars = L` and `tokens = (quot L 4)`, so `chars > cap*8` ⇒ `tokens = (quot L 4) > 2*cap > cap` — the token gate would have already tripped. That single-string argument is real but does NOT generalise to the multi-slot sum the pipeline actually folds.

`token-estimate` floors **per string** (`quot` truncates), so the sum of floors is not the floor of the sum:

```
Σ (quot len_i 4)  ≤  quot (Σ len_i) 4    ; strictly less when the
                                         ; per-string remainders are sub-4
```

A multipart result with many short strings drives the gap to its extreme: 3000 slots of 3 chars each give `tokens = 0` while `chars = 9000`. At `cap = 1` the token gate is quiet and the character gate fires. Shipping dual-coded envelopes normally expose only a few measured strings, but `ResultIO` permits multipart consumers and the shared algorithm covers them.

`over-cap?` and `reported-count` are pure functions over the two sums; tests exercise both branches directly and through `apply-cap`.

## Per-server specialisation hook — the `ResultIO` protocol

Each consumer reifies `ResultIO` with two methods:

```clojure
(defprotocol ResultIO
  (wire-payload-strings  [io result]                      "Seq of strings — ONE for EVERY serialized payload-bearing wire slot (e.g. :content[*].text PLUS a duplicated :structuredContent), not only :text")
  (build-overflow-result [io marker original-result]      "Fresh result map / object carrying the overflow marker"))
```

- `(wire-payload-strings io result)` ⇒ seq of strings — **one for every serialized, payload-bearing slot that rides the wire**, NOT only the `:text` slots. This is the cap's measurement surface: `apply-cap` sums tokens + chars across exactly these strings, so a slot omitted here is a slot the cap cannot see. At minimum that means the `:text`-slot values inside `result`'s content vector (platform accessor `:text` / `j/get :text` lives behind this method), PLUS any duplicated payload slot the envelope also ships (most commonly `:structuredContent` — see the contract below). The method is named `wire-payload-strings`, not `content-texts`, precisely so a new consumer implements the whole wire payload, not just `:content[*].text`.
- `(build-overflow-result io marker original-result)` ⇒ a fresh result map / object carrying the overflow marker, shaped for the consumer's transport.

The cap pipeline calls these two methods; everything else is shared. Adding a third consumer is a single reify, not a code copy.

### Contract: count every payload-bearing slot, not only `:content`

A consumer whose result envelope duplicates a payload into `:content[*].text` and `:structuredContent` must surface stable string representations of both. The adapters include `:structuredContent` when it is present; single-slot results expose only their text slots.

Omitting a duplicated slot lets real wire bytes escape the budget. story-mcp serializes the structured value with `pr-edn`; pair-mcp uses `JSON.stringify` on the SDK-facing JS object. Unit tests cover both dual-coded and single-slot adapters.

### Example reify — minimal single-slot consumer (content-only envelope)

A consumer that ships ONLY `:content[*].text` (no duplicated slot) surfaces just those strings:

```clojure
(deftype MinimalResultIO []
  ResultIO
  (wire-payload-strings [_ result]
    (->> (j/get result :content)
         (map #(j/get % :text))))
  (build-overflow-result [_ marker original]
    (j/lit
      {:isError false
       :content [{:type "text"
                  :text (pr-str marker)}]})))
```

### Example reify — dual-coding consumer (story-mcp / pair-mcp, `:structuredContent` envelope)

Both server adapters account for `:structuredContent` whenever a result carries it. Dual-coded results must surface both slots — the second as a stable string projection (`pr-str` / `JSON.stringify`):

```clojure
(deftype StoryResultIO []
  ResultIO
  (wire-payload-strings [_ result]
    (cond-> (mapv :text (:content result))
      (contains? result :structuredContent)
      (conj (pr-str (:structuredContent result)))))   ; the duplicated wire slot
  (build-overflow-result [_ marker original]
    {:isError          false
     :content          [{:type "text" :text (pr-str marker)}]
     :structuredContent marker}))
```

Both reifies are ~10 lines each. The cap algorithm is unchanged.

Overflow is a budget/retry signal, not a tool-execution failure: the shipped
adapters return `isError: false` or omit it. An invalid `max-tokens` argument
is different and remains an error result. See the
[cross-MCP budget contract](../../mcp-conformance/TOKEN-BUDGETS.md#overflow).

## Replacement safety

`apply-cap` measures the original result once and returns
`build-overflow-result` directly. It does not feed the marker back into
itself, so even a deliberately tiny cap cannot recurse. The marker is
kept compact to remain useful under ordinary caps.

## Disabling the cap

`:max-tokens 0` disables the cap entirely — `max-tokens` resolves the `0` arg to `nil`, and `apply-cap` short-circuits on a `nil` cap. Documented use cases:

1. **Conformance fixtures** — fixtures that assert the un-capped response shape need `:max-tokens 0` so the cap doesn't truncate the expected payload.
2. **Local-host streaming consumers** — agents that stream tool responses (rather than load them into context) may opt out of the cap to receive the full payload.

The default-ON posture matches the agent-ergonomics threat model: a stock install never accidentally floods the agent's context.

## Out-of-domain `:max-tokens` is rejected

An out-of-domain `:max-tokens` is neither a disable signal nor a valid cap. `max-tokens` rejects it with an `{:rf.mcp/invalid-arg {:arg :max-tokens :value <n> :hint "max-tokens must be an integer >= 1; 0 disables the cap"}}` marker; the consumer surfaces that as an `isError: true` tool-result (the agent reads it and corrects the arg). The handler is never dispatched and the rejection never reaches `apply-cap` as a `:cap`. The rejected domain:

- **Negative** — would make every non-negative payload count exceed the cap.
- **Fractional positive in (0,1)** — would floor to a real zero cap rather than the `nil` disable sentinel.
- **Non-finite / out-of-range** — can throw or truncate differently at a host numeric conversion. The shared safe-integer guard rejects them consistently before conversion.

Rejecting these values gives the caller a recoverable argument error instead of silently changing its request or crashing at the boundary.

Helpers (in `cap`):

- `invalid-arg-marker` — builds the `{:rf.mcp/invalid-arg {...}}` rejection payload.
- `invalid-arg?` — the predicate each consumer gates on before threading the cap into `apply-cap`.

The reserved key is `vocab/invalid-arg-key` (`:rf.mcp/invalid-arg`). The wire `:minimum 0` on story-mcp's `:max-tokens` input-schema is the first line of defence for schema-validating hosts; this egress rejection is the backstop for hosts that don't validate (pair-mcp's descriptor carries no `:minimum`).

## Conformance posture

The pair-mcp live harness drives an over-budget SDK call; story-mcp covers the same shared builder and JVM adapter in-process. Together the gates assert:

1. The response carries the `:rf.mcp/overflow` marker.
2. The marker's `:cap-tokens` slot equals 100.
3. The marker's `:token-count` is greater than 100.
4. The marker remains compact under the fixture cap.
5. The marker shape matches the conformance-gate Malli schema (per [`overflow.md` §Conformance posture](overflow.md#conformance-posture)).

Per-server fixtures validate the same marker schema, while shared-builder tests pin the common body shape; server markers differ in the `:tool` slot.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`overflow.md`](overflow.md) — the marker shape this algorithm produces.
- [`vocab.md` §Marker catalogue (`:rf.mcp/*`)](vocab.md#marker-catalogue-rfmcp) — the `:rf.mcp/overflow` key.
