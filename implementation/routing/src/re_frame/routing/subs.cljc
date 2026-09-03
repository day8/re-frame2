(ns re-frame.routing.subs
  "Framework-shipped subs over the route slice for re-frame2 routing.

  Per Spec 012. Subs live in this artefact (not re-frame.core) so apps
  that don't pull day8/re-frame2-routing carry no `:rf.route/*` strings
  in their production bundle.

  The route slice lives at `[:rf.runtime/routing :current]` per
  Spec-Schemas §`:rf/runtime-db` — `{:route-id :params :query :transition
  :error :fragment :nav-token}`. (`:route-id` is the slice key; the
  consumer-facing sub-id stays `:rf.route/id`.) `:pending-navigation` has its
  own `:rf/pending-navigation` sub, and the optional Resources integration may
  add resource-blocking bookkeeping as another sibling. Because all of these
  sit beside `:current` under `[:rf.runtime/routing ...]`, the slice carries only the
  published fields and the layer-1 `:rf/route` sub reads it directly — no
  projection needed (views that deref `[:rf/route]` are isolated from the
  pending-nav slot by structure, not by `select-keys`). The nav-token /
  pending-nav COUNTERS are NOT runtime-db siblings — like the scroll
  positions they live in host-side transient caches, off the reactive db, so
  no counter tick ever reaches a sub.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `subs/reg-sub` calls so a `:reload` re-wires them on a
  fresh registrar."
  (:require [re-frame.registrar :as rf.registrar]))

(defn route-sub-fn
  "Layer-1 sub fn for `:rf/route` — reads the route slice from
  `[:rf.runtime/routing :current]`. Exposed publicly so external
  callers (smoke tests, tooling) read the slice without re-deriving the
  path."
  [db _query]
  (get-in db [:rf.runtime/routing :current]))

(defn chain-from-meta
  "Walk the `:parent` chain from `id` to the root, returning a vector
  [parent-most ... id]. Routes without a `:parent` produce a single-
  element chain. Cycles are guarded by a `seen` set; a route id whose
  `:parent` ultimately points back to itself terminates at the cycle's
  entry point (defensive — Spec 012 §Nested layouts does not address
  cycle handling explicitly, but a bad-faith registration must not
  hang the runtime).

  Public so the façade can wire it as the `:rf.route/chain` sub's
  reduction fn."
  [id]
  (when id
    (loop [cur  id
           acc  (list)
           seen #{}]
      (cond
        (nil? cur)      (vec acc)
        (seen cur)      (vec acc)
        :else           (recur (:parent (rf.registrar/lookup :route cur))
                               (conj acc cur)
                               (conj seen cur))))))

(defn pending-navigation-sub-fn
  "Layer-1 sub fn for `:rf/pending-navigation` — reads
  `[:rf.runtime/routing :pending-navigation]`. Public so the façade
  can wire it as the sub's reduction fn."
  [db _]
  (get-in db [:rf.runtime/routing :pending-navigation]))
