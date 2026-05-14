# `cap` — wire-boundary token-budget cap pipeline (rf2-eyelu)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the ALGORITHM that drives the overflow marker into a result. Until rf2-eyelu this pipeline was duplicated near-identically in pair2-mcp (CLJS, `#js {:content #js [...]}`-shaped results) and story-mcp (CLJ, `{:content [...] :structuredContent ...}`-shaped results). The only structural difference between the two implementations was the SHAPE of the result map and the platform-appropriate accessor used to read its `:text` slots — algorithm identical.

This doc is one of seven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`overflow.md`](overflow.md).

## Scope

`cap` owns:

- The three-step cap algorithm (sum tokens → compare → pass-through or replace).
- The `ResultIO` protocol — the per-consumer specialisation hook.
- The recursion-safety invariant (the overflow marker itself must fit under the cap).

`cap` does NOT own:

- The overflow marker SHAPE — that's [`overflow.md`](overflow.md).
- The token-estimate function — also [`overflow.md`](overflow.md) (`token-estimate`).
- The result-map shape per consumer — each consumer reifies `ResultIO` to express its own platform's result shape.

## Algorithm

1. **Sum tokens.** Sum the cumulative `overflow/token-estimate` across every `:text` slot in the result's `:content` vector.
2. **Compare against cap.** Compare against the per-call cap (`:max-tokens` MCP arg, default `overflow/default-max-tokens`, `0` disables).
3. **Pass-through or replace.** Under-budget responses pass through unchanged; over-budget responses are replaced with a fresh result carrying the `:rf.mcp/overflow` marker.

```clojure
(defn enforce-cap [io result max-tokens]
  (if (zero? max-tokens)
    result                                                    ; 0 disables
    (let [texts       (content-texts io result)
          token-count (reduce + (map overflow/token-estimate texts))]
      (if (<= token-count max-tokens)
        result                                                ; under-budget
        (let [marker (overflow/overflow-marker
                       {:tool        (extract-tool-id result)
                        :token-count token-count
                        :cap-tokens  max-tokens
                        :hint        (resolve-hint io result)})]
          (build-overflow-result io marker result))))))       ; over-budget
```

The algorithm runs synchronously at the wire boundary, after the response body has been assembled but before the consumer-side transport ships it. The cost is one walk over the `:content` vector — O(content size).

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

### Example reify — pair2-mcp (CLJS, JS-object results)

```clojure
(deftype Pair2ResultIO []
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

The conformance harness (`tools/mcp-conformance/test/live-pair2-overflow.js`) asserts this on every cap-trigger; if a future bead grows the marker (a new slot, a longer hint, a verbose recovery message), the test surfaces the regression before it ships.

The structural guarantee comes from the marker shape — `:limit`, `:token-count`, `:cap-tokens`, `:tool`, `:hint` are all small scalars; the marker can grow only by adding new keys. Adding a new key triggers the conformance test, which catches the regression before merge.

## Disabling the cap

`:max-tokens 0` disables the cap entirely. Documented use cases:

1. **Conformance fixtures** — fixtures that assert the un-capped response shape need `:max-tokens 0` so the cap doesn't truncate the expected payload.
2. **Local-host streaming consumers** — agents that stream tool responses (rather than load them into context) may opt out of the cap to receive the full payload.

The default-ON posture matches the agent-ergonomics threat model: a stock install never accidentally floods the agent's context.

## Conformance posture

The conformance harness at `tools/mcp-conformance/test/live-pair2-overflow.js` drives a real `:max-tokens 100` over-budget call on each server and asserts:

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
- rf2-eyelu — the bead that lifted this algorithm out of pair2-mcp / story-mcp into the shared library.
