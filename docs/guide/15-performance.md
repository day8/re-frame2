# 15 - Performance

You want the app to feel fast, and you would prefer not to debug performance by staring at flamegraphs until the furniture starts talking. This chapter gives the re-frame2 performance model: keep derivations in subscriptions, keep views thin, make effects explicit, and use the trace when guessing stops being charming.

Performance problems usually fall into four buckets: too much state changed, too many derivations recomputed, too many views rendered, or too much work happened at the edge.

## State shape controls invalidation

If an event rewrites a giant parent map when it only changed one leaf, more subscriptions will see new inputs and more views will wake up. Shape state so common updates touch the smallest useful path.

```clojure
(update-in db [:entities :todo/by-id id :done?] not)
```

That is better than rebuilding the entire `:entities` root for a one-row change.

## Subscription graphs share work

A sort in a view runs whenever that view renders. A sort in a subscription runs when its inputs change and can be shared by every consumer.

```clojure
(rf/reg-sub :todos/visible
  :<- [:todos/all]
  :<- [:todos/filter]
  (fn [[todos filter] _]
    (filter-todos filter todos)))
```

That is both cleaner and faster. The decoupling is the optimisation.

## Views should be cheap

Render functions should mostly assemble hiccup from subscription values. If a view allocates large derived collections, parses data, walks huge trees, or performs effects, it is no longer merely a view. Move the work to a subscription, event, flow, or effect.

## Use the trace

Xray and the epoch tape can show which event caused which state delta, which subscriptions ran, and which views rendered. That is more useful than "React is slow" because it names the actual moving pieces.

## Pitfall: memoizing around the model

Do not start with random memoization. First make the dataflow obvious. Then measure. Then memoize the actual expensive derivation, usually by making it a subscription or flow. Premature memoization is just global state in a nicer hat.
