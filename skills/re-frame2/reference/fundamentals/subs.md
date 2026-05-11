# Subscriptions

## When to load

Authoring `reg-sub`: a layer-1 reader of `app-db`, a layer-2/3 derived sub composed from other subs, or a multi-input signal sub.

## Canonical signature

```clojure
;; Layer 1: reads app-db directly
(rf/reg-sub :id
  (fn [db query-v] ...))

;; Layer 2: single input signal
(rf/reg-sub :id
  :<- [:other-sub]
  (fn [other-val query-v] ...))

;; Layer 2/3: multi-input
(rf/reg-sub :id
  :<- [:a]
  :<- [:b]
  (fn [[a b] query-v] ...))

;; Optional metadata as first positional arg
(rf/reg-sub :id {:doc "..." :spec ...} <chain> handler)
```

Verified in `implementation/core/src/re_frame/subs.cljc:79` (`reg-sub`) and `subs.cljc:49` (`parse-reg-sub-args`). There is **one** registration form in v2 — `reg-sub-raw` is removed (`subs.cljc:80-81`).

Lookup is via `rf/subscribe`:

```clojure
@(rf/subscribe [:my-sub])
@(rf/subscribe [:my-sub arg1 arg2])
```

The query-vector is `[sub-id & args]`. Its first element is the sub id; args ride in the same vector and are destructured in the handler.

## Canonical mini-example

From `examples/reagent/todomvc/subs.cljs`:

```clojure
(rf/reg-sub :sorted-todos
  (fn [db _]
    (:todos db)))                   ;; layer-1: reads app-db

(rf/reg-sub :todos
  :<- [:sorted-todos]
  (fn [sorted-todos _]
    (vals sorted-todos)))           ;; layer-2: chained

(rf/reg-sub :visible-todos
  :<- [:todos]
  :<- [:showing]
  (fn [[todos showing] _]
    (let [predicate (case showing
                      :active    (complement :completed)
                      :completed :completed
                      identity)]
      (filter predicate todos))))   ;; layer-2 multi-input
```

A layer-1 sub touches `app-db` and recomputes when the value it reads changes by `=`. Layer-2+ subs read other subs' values; they recompute when any input signal changes. The signal graph is built lazily — a sub registers as a callable, and the reactive cache materialises on first `subscribe`.

## Cache behaviour

Caching is per-frame, keyed by the query-vector. Disposal is **deferred ref-counting with a 50ms grace period** (`subs.cljc:21-25`). When the last subscriber drops, the cache entry is scheduled for disposal; a new subscriber arriving within the window cancels disposal and reuses the cached value. This is the **only** disposal algorithm — v1's `:safe` / `:no-cache` / `:reactive` / `:forever` lifecycles are gone (`subs.cljc:26-29`).

For tests that need a different grace period: `(rf/configure :sub-cache {:grace-period-ms 0})` — see `subs.cljc:125` (`configure!`).

## Common gotchas

- **No `reg-sub-raw`.** v1's escape-hatch is removed. If you need to compose, layer subs with `:<-`. If you need a one-shot read off `app-db` outside the reactive graph (tests, SSR), use `rf/compute-sub` (`subs.cljc:379`) which returns a value, not a reaction.
- **Subscribe returns a reaction.** Always deref with `@`. Inside a Reagent view this auto-tracks; outside of a reactive context the deref is a one-shot read and won't update.
- **The query-vector is the cache key.** `[:my-sub 1]` and `[:my-sub 2]` are distinct cache entries. Re-using the same vector across renders is fine; constructing fresh vectors with identical content is also fine (`=`-equal keys hit the same cache slot).
- **Signal subs (`:<-`) accept a query-vector, not just an id.** `:<- [:other-sub arg]` is legal and threads the arg through.
- **Subs run inside the calling frame's context.** Plain Reagent fns under a non-default `frame-provider` may fall through to `:rf/default` — use `reg-view` instead so the frame is captured via React context. See [frames.md](frames.md).

## Deeper material

Sub topology inspection (`sub-topology`), cache snapshots, the disposal algorithm under reconcile, validation against `:spec`: `SKILL-REDIRECT.md` → **EP — Reactive substrate (006)**, **Definitive API reference**.
