# Subscriptions

## When to load

Authoring `reg-sub`: a layer-1 reader of `app-db`, a layer-2/3 derived sub composed from other subs (static `:<-` inputs), or a parametric sub whose inputs depend on the query vector (the EP-0004 input fn).

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

;; Layer 2/3: parametric input fn (EP-0004) — inputs depend on the query vector
(rf/reg-sub :id
  (fn [[_ arg]] [[:other arg] [:another]])   ;; input fn: query-v -> VECTOR OF QUERY VECTORS
  (fn [[other another] query-v] ...))         ;; compute fn: resolved-input VECTOR + query-v

;; Optional metadata as first positional arg
(rf/reg-sub :id {:doc "..." :schema ...} <chain> handler)
```

Verified in `implementation/core/src/re_frame/subs.cljc` (the `reg-sub` fn and the `parse-reg-sub-args` helper). There is **one** registration form — there is no `reg-sub-raw`.

**Query-dependent inputs use the parametric input fn (EP-0004).** When the inputs depend on the query vector (e.g. an entity id rides in `[:my-sub id]`), prefer `:<-` only for *static* inputs; for query-dependent inputs supply a two-arg `reg-sub` whose **first fn is an input fn** taking the query vector and returning a **vector of query vectors** (plain data — `[[:other arg] [:another]]`). The runtime resolves each in the **outer sub's frame** and hands the compute fn the resolved values, in the same order, as a vector. If you carry v1 "signal fn" habits, note three differences: the input fn (a) takes **only** the query vector (no second arg); (b) returns **query vectors, not** `rf/subscribe` reactions; (c) the compute fn destructures the **input vector** (`[[other another] _]`), not scalars. An input fn that returns live reactions registers cleanly but throws `:rf.error/sub-input-fn-bad-return` at first materialization.

Lookup is via `rf/subscribe`:

```clojure
@(rf/subscribe [:my-sub])
@(rf/subscribe [:my-sub arg1 arg2])
```

The query-vector is `[sub-id & args]`. Its first element is the sub id; args ride in the same vector and are destructured in the handler.

## Canonical mini-example

From `examples/core/todomvc/subs.cljs`:

```clojure
(rf/reg-sub :todo/sorted-todos
  (fn [db _]
    (:todos db)))                   ;; layer-1: reads app-db

(rf/reg-sub :todo/todos
  :<- [:todo/sorted-todos]
  (fn [sorted-todos _]
    (vals sorted-todos)))           ;; layer-2: chained

(rf/reg-sub :todo/visible-todos
  :<- [:todo/todos]
  :<- [:todo/showing]
  (fn [[todos showing] _]
    (let [predicate (case showing
                      :active    (complement :completed)
                      :completed :completed
                      identity)]
      (filter predicate todos))))   ;; layer-2 multi-input
```

Every id is feature-prefixed (`:todo/*`) per cardinal rule 7 — the canonical subs in the example never use bare unprefixed ids.

A layer-1 sub touches `app-db` and recomputes when the value it reads changes by `=`. Layer-2+ subs read other subs' values; they recompute when any input signal changes. The signal graph is built lazily — a sub registers as a callable, and the reactive cache materialises on first `subscribe`.

## Cache behaviour

Caching is per-frame, keyed by the query-vector. Disposal is **synchronous ref-counting (dispose on derefer-count → 0)** (`subs/cache.cljc`). When the last subscriber drops, the cache entry is evicted in-tick: the reaction is disposed, the on-dispose cascade releases input ref-counts, and the slot is dissoc'd. A subscribe arriving after the disposal is treated as a fresh cache miss (the recomputed value `=` the disposed one). This is the **only** disposal algorithm — there are no `:safe` / `:no-cache` / `:reactive` / `:forever` lifecycle options, and no deferred-grace-period timer.

## Common gotchas

- **No `reg-sub-raw`.** v1's escape-hatch is removed. If you need to compose, layer subs with `:<-`. If you need a one-shot read off `app-db` outside the reactive graph (tests, SSR), use `rf/compute-sub` (the `compute-sub` fn in `subs.cljc`) which returns a value, not a reaction.
- **Subscribe returns a reaction.** Always deref with `@`. Inside a Reagent view this auto-tracks; outside of a reactive context the deref is a one-shot read and won't update.
- **The query-vector is the cache key.** `[:my-sub 1]` and `[:my-sub 2]` are distinct cache entries. Re-using the same vector across renders is fine; constructing fresh vectors with identical content is also fine (`=`-equal keys hit the same cache slot).
- **Signal subs (`:<-`) accept a query-vector, not just an id.** `:<- [:other-sub arg]` is legal and threads the arg through.
- **Subs run inside the calling frame's context.** A plain Reagent fn can't read the surrounding `frame-provider`'s frame, so a bare `subscribe` in it raises `:rf.error/no-frame-context` (EP-0002 — no `:rf/default` fall-through) — use `reg-view` so the frame is captured via React context, or capture a `capture-frame`. See [frames.md](frames.md).

## Deeper material

Sub topology inspection (`sub-topology`), cache snapshots, the disposal algorithm under reconcile, validation against the output `:schema`: `SKILL-REDIRECT.md` → **EP — Reactive substrate (006)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/subs.cljc` @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after sub-cache disposal-algorithm changes.*
