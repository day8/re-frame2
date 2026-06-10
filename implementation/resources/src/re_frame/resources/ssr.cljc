(ns re-frame.resources.ssr
  "LATE-BOUND SSR / hydration integration for resources. Per Spec 016
  §SSR and hydration and §Restore and replay.

  SSR MUST use request-local frames — a process-global resource cache
  would leak data between users. On a server render the runtime resolves
  the route, computes route resources, enqueues blocking ensures, drains
  until blocking resources for the current nav-token settle, renders with
  the settled state, and serializes ONLY the allowed resource-runtime
  projection. On client hydration the allowed projection installs into the
  target frame-state's `:rf.runtime/resources` slice in runtime-db;
  hydrated entries are preserved, fresh ones avoid a duplicate fetch,
  stale ones background-refetch by event.

  Resource hydration uses an EXPLICIT projection hook — the
  allowlist-by-subsystem-child `project-runtime-db` of [011-SSR]. Rather
  than statically `:require`ing SSR (which would drag the SSR body into
  every resources app), resources publishes the LATE-BOUND
  `:ssr/extend-runtime-db-projection` hook; SSR's `project-runtime-db`
  consults it. The wiring is late-bound BOTH ways — an app that loads
  resources but does not SSR carries none of this code path, and an SSR
  app without resources sees no behaviour change.

  Only the durable resource projection (`:rf.runtime/resources` →
  `:entries`) rides the wire; `:tag-index` / `:owner-index` are
  recomputable-from-`:entries` (rebuilt on install, never trusted from the
  snapshot — Spec 016 §Restore and replay part 5) and need not ride. Per
  Spec 016 §Runtime-subsystem graduation clause 4.

  SKELETON slice (rf2-p10npe): the projection-extension wiring is REAL
  (the durable `:entries` slice projects onto the hydration payload once
  both artefacts are loaded); the blocking-drain wait point, the
  client-side hydration install (preserve / avoid-dup-fetch /
  background-refetch-stale), the per-entry projection metadata
  (serialized / redacted / omitted / fresh / stale / refetch-on-client),
  and the index recompute land with the SSR slice (a later EP-0003
  slice)."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.resources.state :as state]))

#?(:clj (set! *warn-on-reflection* true))

(defn project-resources-runtime-db
  "Project the durable resource-runtime slice that rides the
  `:rf/hydration-payload`'s `:rf/runtime-db` slice. Returns a
  `{:rf.runtime/resources {:entries …}}` map (the durable cache facts)
  for a runtime-db carrying resource entries, or `{}` otherwise. The
  reverse indexes (`:tag-index` / `:owner-index`) are deliberately
  EXCLUDED — they are recomputable-from-`:entries` and rebuilt on install.
  Per Spec 016 §SSR and hydration / §Runtime-subsystem graduation clause 4.

  This is the body behind the `:ssr/extend-runtime-db-projection` hook.

  SKELETON: ships the `:entries` whole. Per-entry redaction / omission
  (`:sensitive?` / `:large?` classification through `rf/elide-wire-value`)
  + the projection metadata land with the SSR slice; until then a resource
  whose data is sensitive should not yet be SSR-projected (the runtime
  slice gates that)."
  [runtime-db]
  (let [resources (get runtime-db state/resources-key)
        entries   (:entries resources)]
    (if (seq entries)
      {state/resources-key {:entries entries}}
      {})))

(defn install-ssr-integration!
  "Publish the LATE-BOUND SSR projection extension: register the
  `:ssr/extend-runtime-db-projection` hook so SSR's `project-runtime-db`
  rides the durable resource `:entries` on the hydration payload. No-op
  effect on an app that never SSRs — the hook simply sits unread. Per
  Spec 016 §SSR and hydration.

  SKELETON: wires the projection extension only. The client-side
  hydration install + blocking-drain wait point land with the SSR slice."
  []
  (late-bind/set-fn! :ssr/extend-runtime-db-projection project-resources-runtime-db)
  nil)

(defn hydrate-resources!
  "Install the allowed resource projection into the target frame-state's
  `:rf.runtime/resources` slice on client hydration: preserve hydrated
  entries, recompute `:tag-index` / `:owner-index` from `:entries`, avoid
  duplicate fetches for fresh entries, background-refetch stale ones by
  event, maintain frame + nav-token isolation, and surface server clock
  skew in trace when freshness is ambiguous. Per Spec 016 §SSR and
  hydration (client hydration) / §Restore and replay.

  SKELETON: lands with the SSR slice. Named so the install path and the
  restore/replay reconciliation (which shares the same install path) have
  a stable home."
  [_frame-id _projection]
  (throw (ex-info ":rf.error/resource-not-implemented"
                  {:rf.error/id :rf.error/resource-not-implemented
                   :where       're-frame.resources.ssr/hydrate-resources!
                   :recovery    :no-recovery
                   :reason      (str "resource hydration install lands with the "
                                     "EP-0003 SSR slice. This is the rf2-p10npe "
                                     "artefact skeleton.")})))
