# Subscriptions

## When to load

Authoring `reg-sub`: a layer-1 reader of `app-db`, a layer-2/3 derived sub composed from other subs (a literal `:inputs` vector), or a parametric sub whose inputs depend on the query vector (an `:inputs` producer fn, EP-0004).

## Canonical signature

```clojure
;; Layer 1: reads app-db directly — `:inputs` OMITTED
(rf/reg-sub :id
  (fn [db query-v] ...))

;; Layer 2: one declared input
(rf/reg-sub :id {:inputs [[:other-sub]]}
  (fn [[other-val] query-v] ...))

;; Layer 2/3: several declared inputs
(rf/reg-sub :id {:inputs [[:a] [:b]]}
  (fn [[a b] query-v] ...))

;; Layer 2/3: parametric inputs (EP-0004) — a producer fn of the query vector
(rf/reg-sub :id
  {:inputs (fn [[_ arg]] [[:other arg] [:another]])}  ;; producer: query-v -> VECTOR OF QUERY VECTORS
  (fn [[other another] query-v] ...))                 ;; compute fn: resolved-input VECTOR + query-v

;; `:inputs` is one key in the ordinary metadata map
(rf/reg-sub :id {:doc "..." :schema ... :inputs [[:a]]} handler)
```

Verified in `implementation/core/src/re_frame/subs.cljc` (the `reg-sub` fn and the `parse-reg-sub-args` helper). There is **one** registration form — there is no `reg-sub-raw`.

**A subscription declares its dependencies once, under `:inputs`.** A literal vector of query vectors lists edges known at registration; a producer fn computes them from the outer query vector, for the case where an entity id rides in `[:my-sub id]` and the upstream vectors cannot be written down until it arrives. Both spellings are the same declaration, so **declared inputs always reach the compute fn as a vector** — `[v]` at one input exactly as `[a b]` at two — and adding an input never reshapes a body you already wrote. Omitting `:inputs` is the layer-1 reader, whose first arg is `app-db` itself; `{:inputs []}` declares no dependencies and delivers `[]`. The runtime resolves each declared query vector in the **outer sub's frame** and hands over the resolved values in declaration order. A producer fn is **pure**: it takes only the query vector, must not `subscribe`, deref `app-db`, dispatch or perform IO, and returns **query vectors, not** reactions — one that returns live reactions registers cleanly but throws `:rf.error/sub-input-fn-bad-return` at first materialization. A malformed literal is refused at registration with `:rf.error/reg-sub-bad-args`.

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

(rf/reg-sub :todo/todos {:inputs [[:todo/sorted-todos]]}
  (fn [[sorted-todos] _]
    (vals sorted-todos)))           ;; layer-2: one declared input

(rf/reg-sub :todo/visible-todos {:inputs [[:todo/todos] [:todo/showing]]}
  (fn [[todos showing] _]
    (let [predicate (case showing
                      :active    (complement :completed)
                      :completed :completed
                      identity)]
      (filter predicate todos))))   ;; layer-2, two declared inputs
```

Every id is feature-prefixed (`:todo/*`) per cardinal rule 7 — the canonical subs in the example never use bare unprefixed ids. Note that `:todo/todos` and `:todo/visible-todos` destructure their bodies the same way: the one-input case is not a special case.

A layer-1 sub touches `app-db` and recomputes when the value it reads changes by `=`. Layer-2+ subs read other subs' values; they recompute when any input signal changes. The signal graph is built lazily — a sub registers as a callable, and the reactive cache materialises on first `subscribe`.

## Cache behaviour

Caching is per-frame, keyed by the query-vector. Disposal is **synchronous ref-counting (dispose on derefer-count → 0)** (`subs/cache.cljc`). When the last subscriber drops, the cache entry is evicted in-tick: the reaction is disposed, the on-dispose cascade releases input ref-counts, and the slot is dissoc'd. A subscribe arriving after the disposal is treated as a fresh cache miss (the recomputed value `=` the disposed one). This is the **only** disposal algorithm — there are no `:safe` / `:no-cache` / `:reactive` / `:forever` lifecycle options, and no deferred-grace-period timer.

## Common gotchas

- **No `reg-sub-raw`.** v1's escape-hatch is removed. If you need to compose, layer subs by declaring `:inputs`. If you need a one-shot read off `app-db` outside the reactive graph (tests, SSR), use `rf/compute-sub` (the `compute-sub` fn in `subs.cljc`) which returns a value, not a reaction.
- **Subscribe returns a reaction.** Always deref with `@`. Inside a Reagent view this auto-tracks; outside of a reactive context the deref is a one-shot read and won't update.
- **The query-vector is the cache key.** `[:my-sub 1]` and `[:my-sub 2]` are distinct cache entries. Re-using the same vector across renders is fine; constructing fresh vectors with identical content is also fine (`=`-equal keys hit the same cache slot).
- **A declared input is a query-vector, not just an id.** `{:inputs [[:other-sub arg]]}` is legal and threads the arg through. A bare keyword is refused — the only accepted single-input spelling is `[[:other-sub]]`.
- **Subs run inside the calling frame's context.** A plain Reagent fn can't read the surrounding `frame-provider`'s frame, so a bare `subscribe` in it raises `:rf.error/no-frame-context` (EP-0002 — no `:rf/default` fall-through) — use `reg-view` so the frame is read from React context, or, to keep it a plain fn, carry the frame explicitly (`(rf/capture-frame frame-id)`, a `{:frame …}` opt, or a frame api threaded down). A bare no-arg `capture-frame` in the plain fn re-raises — it has no scope to capture. See [frames.md](frames.md).

## Deeper material

Sub topology inspection (`sub-topology`), cache snapshots, the disposal algorithm under reconcile, validation against the output `:schema`: `SKILL-REDIRECT.md` → **EP — Reactive substrate (006)**, **Definitive API reference**.

---

*Derived from `implementation/core/src/re_frame/subs.cljc` @ main. Citations are symbol-level; re-verify symbol homes after sub-cache disposal-algorithm changes.*
