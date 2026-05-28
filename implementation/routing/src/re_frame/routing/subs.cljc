(ns re-frame.routing.subs
  "Framework-shipped subs over the `:rf/route` slice for re-frame2
  routing.

  Per Spec 012. Subs live in this artefact (not re-frame.core) so apps
  that don't pull day8/re-frame2-routing carry no `:rf.route/*` strings
  on their production-elision bundle (rf2-k682).

  The `:rf/route` map at app-db root is the routing-runtime container.
  It carries the route-slice fields AND the per-frame routing-runtime
  sub-keys (`:scroll-positions` / `:scroll-positions-order` /
  `:nav-token-counter` / `:pending-nav-counter` — rf2-3ib8h, nested
  under `:rf/route` to preserve the `:rf/*` single-root scheme per
  spec/Conventions §Reserved app-db keys). The `:rf/route` layer-1 sub
  projects ONLY the published slice keys so views that deref
  `[:rf/route]` don't re-render on internal counter ticks (rf2-xak8u).
  The runtime keys remain in app-db under `:rf/route` and are read
  directly via `(get-in db [:rf/route :scroll-positions])` where needed.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `subs/reg-sub` calls so a `:reload` re-wires them on a
  fresh registrar. Per the rf2-2yabr cohesion split: SUBS seam."
  (:require [re-frame.registrar :as registrar]))

(def ^:private route-slice-keys
  "The published shape of the `:rf/route` sub — only these keys
  propagate through the layer-1 sub (rf2-xak8u). Internal counters
  (`:scroll-positions`, `:scroll-positions-order`, `:nav-token-counter`,
  `:pending-nav-counter`) remain in `app-db` under `:rf/route` but do
  not surface here, so consumers that deref `:rf/route` directly do
  not re-render on internal counter ticks."
  [:id :params :query :transition :error :fragment :nav-token])

(defn route-sub-fn
  "Layer-1 sub fn for `:rf/route` — projects the published route-slice
  (`route-slice-keys`) from the routing-runtime container at
  `(:rf/route db)`. The container additionally carries internal
  routing-runtime sub-keys (`:scroll-positions` /
  `:scroll-positions-order` / `:nav-token-counter` /
  `:pending-nav-counter` — rf2-3ib8h); those stay in `app-db` but do
  not leak through this sub (rf2-xak8u). Exposed publicly so external
  callers (smoke tests, tooling) recover the same projection without
  re-deriving it."
  [db _query]
  (select-keys (:rf/route db) route-slice-keys))

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
        :else           (recur (:parent (registrar/lookup :route cur))
                               (conj acc cur)
                               (conj seen cur))))))

(defn pending-navigation-sub-fn
  "Layer-1 sub fn for `:rf/pending-navigation`. Public so the façade
  can wire it as the sub's reduction fn."
  [db _]
  (:rf/pending-navigation db))
