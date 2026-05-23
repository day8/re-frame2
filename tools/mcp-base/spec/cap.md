# `cap` — wire-boundary token-budget cap pipeline (rf2-eyelu)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the ALGORITHM that drives the overflow marker into a result. Until rf2-eyelu this pipeline was duplicated near-identically in re-frame2-pair-mcp (CLJS, `#js {:content #js [...]}`-shaped results) and story-mcp (CLJ, `{:content [...] :structuredContent ...}`-shaped results). The only structural difference between the two implementations was the SHAPE of the result map and the platform-appropriate accessor used to read its `:text` slots — algorithm identical.

This doc is one of eight per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`overflow.md`](overflow.md).

## Scope

`cap` owns:

- The two-stage cap algorithm (sum tokens + chars in one pass → primary token gate OR secondary char gate → pass-through or replace).
- The `max-tokens` per-call cap resolver (`0` resolves to `nil` = disabled; absent / non-number → `default-max-tokens`).
- The `ResultIO` protocol — the per-consumer specialisation hook.
- The recursion-safety invariant (the overflow marker itself must fit under the cap).

`cap` does NOT own:

- The overflow marker SHAPE — that's [`overflow.md`](overflow.md).
- The token-estimate function — also [`overflow.md`](overflow.md) (`token-estimate`).
- The result-map shape per consumer — each consumer reifies `ResultIO` to express its own platform's result shape.

## Algorithm

The pipeline is a **two-stage** gate (rf2-ih7g4) that folds both sums in a **single pass** (rf2-hyp0z) over the content `:text` slots:

1. **Resolve the cap.** `max-tokens` turns the raw `:max-tokens` MCP arg into the per-call cap: `nil` (caller passed `0` → disabled), `default-max-tokens` (5000; absent or non-number), or a positive integer (custom cap). When the resolved cap is `nil`, `apply-cap` short-circuits and returns the result untouched — **the disable mechanism is `max-tokens` resolving to `nil`, not the arg being `0` at the gate**.
2. **Single-pass token + char sum.** One `transduce` over the `:text`-slot strings folds both `Σ token-estimate` and `Σ (count s)` in one walk (so a story-mcp `:structuredContent` is materialised once, not twice).
3. **Two-stage over-budget decision** (`over-cap?`): trip when EITHER the token sum `> cap` (primary gate) OR the char sum `> cap * byte-cap-multiplier` (secondary byte gate — defence-in-depth against payloads where `(count s)/4` undercounts: CJK, emoji, base64, dense code). `reported-count` selects which count rides the marker's `:token-count` slot (the char count when the secondary gate is the one that tripped, else the token sum).
4. **Pass-through or replace.** Under-budget responses pass through unchanged; over-budget responses are replaced with a fresh result carrying the `:rf.mcp/overflow` marker (built via `overflow/overflow-payload`).

```clojure
(defn enforce-cap [io result cap]                  ; cap = (max-tokens raw-arg)
  (if (nil? cap)
    result                                          ; nil cap ⇒ disabled
    (let [{:keys [tokens chars]}                    ; single-pass fold
          (transduce (filter string?)
                     (completing
                       (fn [acc s]
                         {:tokens (+ (:tokens acc) (overflow/token-estimate s))
                          :chars  (+ (:chars acc)  (count s))}))
                     {:tokens 0 :chars 0}
                     (content-texts io result))]
      (if-not (over-cap? tokens chars cap)          ; token gate OR char gate
        result                                      ; under-budget
        (let [marker (overflow/overflow-payload
                       {:tool        (extract-tool-id result)
                        :token-count (reported-count tokens chars cap)
                        :cap         cap
                        :hint        (resolve-hint io result)})]
          (build-overflow-result io marker result)))))) ; over-budget
```

The algorithm runs synchronously at the wire boundary, after the response body has been assembled but before the consumer-side transport ships it. The cost is one walk over the `:content` vector — O(content size).

### Why the secondary char gate is structurally dominated today

Under the live `token-estimate = (quot count 4)` rule, the two sums are not independent: if `chars > cap*8` then `tokens = (quot chars 4) > 2*cap > cap`, so the token gate has already tripped whenever the char gate would. The char disjunct is therefore intentional **defence-in-depth** against a FUTURE `token-estimate` refinement that decouples chars from tokens (one that recognises base64 / CJK with a lower per-char cost) — not dead code. `over-cap?` / `reported-count` are extracted as pure fns over already-summed counts so the dominated branch is unit-trippable in isolation (`cap_test.clj`).

## Per-server specialisation hook — the `ResultIO` protocol

Each consumer reifies `ResultIO` with two methods:

```clojure
(defprotocol ResultIO
  (content-texts       [io result]                        "Seq of :text slot strings inside result's content vector")
  (build-overflow-result [io marker original-result]      "Fresh result map / object carrying the overflow marker"))
```

- `(content-texts io result)` ⇒ seq of strings, the `:text`-slot values inside `result`'s content vector. The platform-specific accessor (`:text` / `j/get :text`) lives behind this method.
- `(build-overflow-result io marker original-result)` ⇒ a fresh result map / object carrying the overflow marker, shaped for the consumer's transport.

The cap pipeline calls these two methods; everything else is shared. Adding a third consumer is a single reify, not a code copy.

### Example reify — re-frame2-pair-mcp (CLJS, JS-object results)

```clojure
(deftype ReFrame2PairResultIO []
  ResultIO
  (content-texts [_ result]
    (->> (j/get result :content)
         (map #(j/get % :text))))
  (build-overflow-result [_ marker original]
    (j/lit
      {:isError true
       :content [{:type "text"
                  :text (pr-str marker)}]})))
```

### Example reify — story-mcp (CLJ, Clojure-map results)

```clojure
(deftype StoryResultIO []
  ResultIO
  (content-texts [_ result]
    (->> (:content result)
         (map :text)))
  (build-overflow-result [_ marker original]
    {:isError          true
     :content          [{:type "text" :text (pr-str marker)}]
     :structuredContent marker}))
```

Both reifies are ~10 lines each. The cap algorithm is unchanged.

## Recursion safety

The overflow marker itself MUST fit under the cap. If the marker grew large enough to exceed `:max-tokens` it would trigger another cap, recursing infinitely.

The conformance harness (`tools/mcp-conformance/test/live-re-frame2-pair-overflow.cjs`) asserts this on every cap-trigger; if a future bead grows the marker (a new slot, a longer hint, a verbose recovery message), the test surfaces the regression before it ships.

The structural guarantee comes from the marker shape — `:limit`, `:token-count`, `:cap-tokens`, `:tool`, `:hint` are all small scalars; the marker can grow only by adding new keys. Adding a new key triggers the conformance test, which catches the regression before merge.

## Disabling the cap

`:max-tokens 0` disables the cap entirely — `max-tokens` resolves the `0` arg to `nil`, and `apply-cap` short-circuits on a `nil` cap. Documented use cases:

1. **Conformance fixtures** — fixtures that assert the un-capped response shape need `:max-tokens 0` so the cap doesn't truncate the expected payload.
2. **Local-host streaming consumers** — agents that stream tool responses (rather than load them into context) may opt out of the cap to receive the full payload.

The default-ON posture matches the agent-ergonomics threat model: a stock install never accidentally floods the agent's context.

## Conformance posture

The conformance harness at `tools/mcp-conformance/test/live-re-frame2-pair-overflow.cjs` drives a real `:max-tokens 100` over-budget call on each server and asserts:

1. The response carries the `:rf.mcp/overflow` marker.
2. The marker's `:cap-tokens` slot equals 100.
3. The marker's `:token-count` is greater than 100.
4. The marker itself fits under 100 tokens (recursion-safety check).
5. The marker shape matches the conformance-gate Malli schema (per [`overflow.md` §Conformance posture](overflow.md#conformance-posture)).

Parity across servers is asserted by running the same fixture against each consumer and diffing the resulting markers; shapes match modulo the `:tool` slot.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`overflow.md`](overflow.md) — the marker shape this algorithm produces.
- [`vocab.md` §Marker catalogue (`:rf.mcp/*`)](vocab.md#marker-catalogue-rfmcp) — the `:rf.mcp/overflow` key.
- rf2-eyelu — the bead that lifted this algorithm out of re-frame2-pair-mcp / story-mcp into the shared library.
