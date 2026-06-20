# `cap` — wire-boundary token-budget cap pipeline (rf2-eyelu)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the ALGORITHM that drives the overflow marker into a result. Until rf2-eyelu this pipeline was duplicated near-identically in re-frame2-pair-mcp (CLJS, `#js {:content #js [...]}`-shaped results) and story-mcp (CLJ, `{:content [...] :structuredContent ...}`-shaped results). The only structural difference between the two implementations was the SHAPE of the result map and the platform-appropriate accessor used to read its `:text` slots — algorithm identical.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`cap` owns:

- The two-stage cap algorithm (sum tokens + chars in one pass → primary token gate OR secondary char gate → pass-through or replace).
- The `max-tokens` per-call cap resolver (`0` resolves to `nil` = disabled; absent / non-number → `default-max-tokens`; **negative, fractional-in-(0,1), or non-finite / out-of-range → `{:rf.mcp/invalid-arg {...}}` rejection**, rf2-5rdit / rf2-li6y2.2 / rf2-ykv9a0).
- The `ResultIO` protocol — the per-consumer specialisation hook.
- The recursion-safety invariant (the overflow marker itself must fit under the cap).

`cap` does NOT own:

- The overflow marker SHAPE — that's [`overflow.md`](overflow.md).
- The token-estimate function — also [`overflow.md`](overflow.md) (`token-estimate`).
- The result-map shape per consumer — each consumer reifies `ResultIO` to express its own platform's result shape.

## Algorithm

The pipeline is a **two-stage** gate (rf2-ih7g4) that folds both sums in a **single pass** (rf2-hyp0z) over the content `:text` slots:

1. **Resolve the cap.** `max-tokens` turns the raw `:max-tokens` MCP arg into the per-call cap: `nil` (caller passed `0` → disabled), `default-max-tokens` (5000; absent or non-number), a positive integer (custom cap), or — for a **negative** number — an `{:rf.mcp/invalid-arg {...}}` rejection (rf2-5rdit; see [§Out-of-domain `:max-tokens` is rejected](#out-of-domain-max-tokens-is-rejected-rf2-5rdit--rf2-li6y22--rf2-ykv9a0)). When the resolved cap is `nil`, `apply-cap` short-circuits and returns the result untouched — **the disable mechanism is `max-tokens` resolving to `nil`, not the arg being `0` at the gate**.
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
                     (wire-payload-strings io result))]
      (if-not (over-cap? tokens chars cap)          ; token gate OR char gate
        result                                      ; under-budget
        (let [marker (overflow/overflow-payload
                       {:tool        (extract-tool-id result)
                        :token-count (reported-count tokens chars cap)
                        :cap         cap
                        :hint        (resolve-hint io result)})]
          (build-overflow-result io marker result)))))) ; over-budget
```

The algorithm runs synchronously at the wire boundary, after the response body has been assembled but before the consumer-side transport ships it. The cost is one walk over the consumer's measured wire payload strings (every serialized payload-bearing slot, not only `:content`) — O(measured payload strings).

### Why the secondary char gate is load-bearing today (rf2-of2cd)

A naive reading argues the char gate is structurally dominated: for a SINGLE string of length `L`, `chars = L` and `tokens = (quot L 4)`, so `chars > cap*8` ⇒ `tokens = (quot L 4) > 2*cap > cap` — the token gate would have already tripped. That single-string argument is real but does NOT generalise to the multi-slot sum the pipeline actually folds.

`token-estimate` floors **per string** (`quot` truncates), so the sum of floors is not the floor of the sum:

```
Σ (quot len_i 4)  ≤  quot (Σ len_i) 4    ; strictly less when the
                                         ; per-string remainders are sub-4
```

A content vector of MANY short strings drives the gap to its extreme: 3000 slots of 3 chars each give `tokens = Σ (quot 3 4) = 0` while `chars = 9000`. At `cap = 1` the token gate is quiet (`0 > 1` is false) yet the char gate fires (`9000 > 8`), and `apply-cap` correctly replaces the over-budget payload with the overflow marker. The `watch-epochs` / `trace-window` slices are exactly this shape — a long vector of small per-record text slots. So the secondary char gate is reachable through the **live** `apply-cap` path today, not merely future-proofing.

The branch is doubly justified: load-bearing now AND defence-in-depth against a FUTURE `token-estimate` refinement (one recognising base64 / CJK at a lower per-char cost) that would decouple the sums further. `over-cap?` / `reported-count` are extracted as pure fns over already-summed counts so both disjuncts and the `reported` selector are exercised directly in isolation (`cap_test.clj`) AND through the live many-short-strings `apply-cap` path (`apply-cap-many-short-strings-trips-char-gate`).

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

### Contract: count every payload-bearing slot, not only `:content` (rf2-mzndx / rf2-13wbe)

A consumer whose result envelope **duplicates** the payload into a second wire slot MUST surface a stable string representation of that slot from `wire-payload-strings` too. The common case is a `:structuredContent` JSON projection emitted **alongside** the `:content[*].text` EDN on **every** result — both re-frame2-pair-mcp's `wire/ok-text` / `err-text` and story-mcp's `text-result` do exactly this (the dual-coded envelope: agent hosts that understand `:structuredContent` read the typed object; the rest fall back to the text). Both copies ride the wire, so both count toward the one budget.

Omitting the second copy undercounts the response by **~50%**: a response whose `:content[*].text` slot is under budget but whose `:structuredContent` is large would slip past the overflow marker and bust the MCP token budget. story-mcp closed this (rf2-mzndx) by appending a `pr-edn`'d `:structuredContent` string to `wire-payload-strings`; any dual-coding consumer (pair-mcp's `result-io`) must mirror it (`JSON.stringify` / `pr-str` of the structured value). The unit suites pin the contract with **both** a dual-coding reify (`structured-io` — counts both slots and trips the cap) and a single-slot reify (`map-io` — counts one), so a regression that drops the second copy fails the dual-coding regression.

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
      {:isError true
       :content [{:type "text"
                  :text (pr-str marker)}]})))
```

### Example reify — dual-coding consumer (story-mcp / pair-mcp, `:structuredContent` envelope)

Both shipping servers emit `:structuredContent` alongside `:content[*].text` on every result, so `wire-payload-strings` MUST surface BOTH slots — the second as a stable string projection (`pr-str` / `JSON.stringify`) — or the cap undercounts by ~50%:

```clojure
(deftype StoryResultIO []
  ResultIO
  (wire-payload-strings [_ result]
    (cond-> (mapv :text (:content result))
      (contains? result :structuredContent)
      (conj (pr-str (:structuredContent result)))))   ; the duplicated wire slot
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

## Out-of-domain `:max-tokens` is rejected (rf2-5rdit / rf2-li6y2.2 / rf2-ykv9a0)

An out-of-domain `:max-tokens` is neither a disable signal nor a valid cap. `max-tokens` rejects it with an `{:rf.mcp/invalid-arg {:arg :max-tokens :value <n> :hint "max-tokens must be an integer >= 1; 0 disables the cap"}}` marker; the consumer surfaces that as an `isError: true` tool-result (the agent reads it and corrects the arg). The handler is never dispatched and the rejection never reaches `apply-cap` as a `:cap`. The rejected domain:

- **Negative** (rf2-5rdit) — `(long raw)` produced a negative ceiling; `over-cap?` (`(> tokens cap)`) is then true for ANY non-negative token count, so **every** response — even a 2-character one — was replaced by the `:rf.mcp/overflow` marker, locking the agent out and emitting a nonsensical `:cap-tokens -1`.
- **Fractional positive in (0,1)** (rf2-li6y2.2) — e.g. `0.5` floored to a REAL `0` cap (non-nil, NOT the disable sentinel), reproducing the same lockout on every non-empty payload.
- **Non-finite / out-of-range** (rf2-ykv9a0) — `##Inf` and a finite magnitude past the safe-integer window (`1.0E20`) THROW `IllegalArgumentException` on `(long raw)` (a crash at the wire boundary); `##NaN` truncated to a real `0` cap (the same lockout). The resolver routes through the shared `args/coerce-finite-long` guard BEFORE `(long raw)`, so each is rejected honestly and IDENTICALLY on JVM and CLJS rather than crashing the tool or minting a 0-cap.

Why reject rather than clamp / treat-as-default (Mike ruled A, 2026-06-01): an honest, recoverable rejection at the AI/MCP boundary is the masterpiece CORRECTNESS posture — a malformed cap is an agent error worth telling the agent about, not a value to silently paper over (or crash on).

Helpers (in `cap`):

- `invalid-arg-marker` — builds the `{:rf.mcp/invalid-arg {...}}` rejection payload.
- `invalid-arg?` — the predicate each consumer gates on before threading the cap into `apply-cap`.

The reserved key is `vocab/invalid-arg-key` (`:rf.mcp/invalid-arg`). The wire `:minimum 0` on story-mcp's `:max-tokens` input-schema is the first line of defence for schema-validating hosts; this egress rejection is the backstop for hosts that don't validate (pair-mcp's descriptor carries no `:minimum`).

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
