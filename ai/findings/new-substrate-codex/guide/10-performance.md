# Production performance

## The default path is the fast path

Ordinary code already gets:

- compile-time DOM/tag/prop conversion;
- direct `jsx`/`jsxs` calls;
- static subtree/prop hoisting where proven;
- direct internal prop reads;
- generated `rf=` prop comparison;
- stable query identities and equal result references;
- one external-store Hook per reactive view;
- one ViewCell notification per framework epoch;
- zero live re-frame2 owners for Activity-hidden trees;
- stable event/handler callbacks;
- development instrumentation removed from production.

Do not begin with manual `useMemo`, `useCallback`, or custom equality. Begin with clear subscriptions, stable entity keys, and suitable component boundaries.

## Efficient view shapes

### Pure leaf

```clojure
(ui/defview badge [{:keys [text tone]}]
  [:span {:class (str "badge badge-" (name tone))} text])
```

No ViewCell is emitted in production. Parent prop comparison is straight-line.

### Focused reactive view

```clojure
(ui/defview unread-count []
  (let [n (ui/sub [::unread-count])]
    [:span.unread n]))
```

One cell, one external-store Hook, one real derivation lease.

### Multi-read view

```clojure
(ui/defview toolbar []
  (let [selection (ui/sub [::selection])
        can-edit? (ui/sub [::can-edit?])
        saving?   (ui/sub [::saving?])]
    ...))
```

Still one React bridge and one render per settled epoch. Keep separate meaningful subscriptions; do not merge merely to reduce Hook count.

## Make derivations do derivation work

Avoid repeated filtering/sorting/joins inside render:

```clojure
;; Prefer a registered derived sub.
(let [visible (ui/sub [::visible-items])]
  ...)
```

A registered subscription:

- memoizes by input values;
- is shared;
- only notifies on changed output;
- appears in causal profiling;
- can be tested without React.

Small presentation formatting remains fine in render.

## Stable identity pipeline

Persistent data works best when unchanged references survive:

```text
unchanged app value
→ subscription returns/publishes stable value
→ parent passes stable prop
→ generated comparator skips child
```

Watch for code that wraps a stable value in a fresh map/vector for each child. Pass the meaningful scalar/value or derive the exact shape in a cached subscription.

The ViewCell returns the prior exact site value when a new result is `rf=`. This handles many equal-rebuild cases automatically, but avoiding unnecessary construction is still better.

## Lists

For large or frequently changing lists:

- use semantic keys;
- keep row views small;
- pass ID and read in row when updates are independent;
- or pass stable item values when the collection changes together;
- derive filtered/sorted IDs outside render;
- use virtualization when DOM size is the problem.

The substrate compiles list `for` directly to a JS array but cannot make 10,000 DOM nodes cheap. Use a proven React virtualizer through foreign interop.

## Avoid dynamic cost boundaries in hot loops

These are correct but have explicit runtime work:

- `ui/spread` walks/converts a map;
- `ui/element` resolves a runtime type;
- `ui/raw` trusts a foreign element;
- whole-props `:as` materializes a map;
- foreign components may rebuild props/callbacks internally.

They are ideal at integration/layout boundaries. Prefer literal internal calls inside thousands of repeated rows.

Compiler reports can list dynamic boundary sites and estimated repetition context; profile before rewriting a clear generic component.

## Events

Direct event vectors are usually optimal:

```clojure
{:on-click [::selected id]}
```

The callback is allocated once and its committed value slot changes. `ui/event` and `ui/handler` use the same stable-slot model.

`ui/render-fn` deliberately has no stable-identity guarantee because it must observe the current speculative render; use it only for pure foreign render/comparator/formatter callbacks. `ui/raw-handler` opts out of compiler stabilization entirely and is reserved for a documented foreign identity protocol.

## Forms

For low latency:

- keep field reads narrow;
- avoid whole-form derived work on every field event unless required;
- let browser/DOM own uncommitted composition/caret mechanics;
- dispatch semantic application changes;
- keep validation pure/cached;
- use uncontrolled state only when the draft is genuinely not application state.

Measure event → derivation → React commit. A slow field may be an event/subscription design issue rather than a React render issue.

## Effects and layout

Passive effects do not block paint; layout effects do. Use layout effect only for pre-paint measurement/mutation.

Avoid attaching/detaching a listener every render:

- include only lifecycle-defining values in effect deps;
- use `useEffectEvent` for latest committed values that should not reconnect;
- clean up exactly once per setup;
- let re-frame2 Resources own polling/retry/network lifecycles.

## No transition fiction

re-frame2 app-db is an external store. Do not wrap a dispatch in `startTransition` expecting subscription snapshots to become safely non-blocking. React's external-store contract does not support that guarantee.

Reduce actual work, use focused view boundaries, and use React-local deferred presentation only for a genuine local expensive rendering concern.

## Inspect generated output

A compiler report should show for each view:

```text
app.orders/order-row
  capabilities  subscriptions, events
  DOM nodes      7 direct, 1 dynamic
  static hoists  2
  sub sites      1
  event sites    1
  spread sites   0
  props ABI      direct (4 slots)
  production cell reactive
```

Use it to find a surprising dynamic path or capability, not as a score to minimize blindly.

Advanced JS inspection should show direct JSX-runtime calls and no generic Hiccup/tag/props interpreter.

## Profile by epoch

Xray summarizes a framework transaction:

```text
event ::order-updated
  handler/effects       ...
  derivation nodes      18 considered, 4 changed
  dirty ViewCells       6
  React commits         6
  UI render time        ...
```

Questions:

- Did an unexpected sub output change?
- Did one cell receive multiple changes but only one revision?
- Did a broad parent prop cascade into children?
- Did keys remount rows?
- Is expensive work in render instead of a sub?
- Is a foreign component doing the extra work?

Optimize the first meaningful cause.

## Production build proof

A release gate should build representative entries with Closure `:advanced` and verify absence of:

- runtime Hiccup walker/tag regex/sequence flattener;
- Reagent/UIx/Helix namespaces;
- source files/manifest strings/template fingerprints;
- `data-rf2-*` attrs;
- view cause/history/timing code;
- Xray projection code;
- SSR server renderer in browser chunks;
- development warning text.

Measure incremental compressed bytes after accounting for shared React and re-frame2 core chunks. Keep build metafiles/symbol reachability with benchmark results.

## When manual memoization is justified

Rare cases:

- a foreign Hook requires stable dependency identity;
- a CPU-heavy pure calculation is view-local and cannot reasonably be a reusable subscription;
- a foreign component has a documented callback/object identity contract;
- profiling proves a specific value creation dominates.

Use `react/use-memo` or `use-callback` with a literal complete dependency vector. The compiler lints it. Add a comment naming the foreign/performance contract.

Do not memoize to fix incorrect keys, broad subscriptions, effect loops, mutable props, or render side effects.

## Performance expectations

The design targets direct pure render within 10% of hand-written JSX runtime, one reactive bridge per view, zero abandoned ownership, and exact debug erasure. Those are implementation acceptance gates, not claims this design document has measured.

See [the full performance contract](../08-production-performance.md) for workloads, comparative substrates, memory/concurrency tests, and failure interpretation.
