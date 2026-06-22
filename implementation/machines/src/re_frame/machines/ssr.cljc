(ns re-frame.machines.ssr
  "LATE-BOUND SSR / hydration projection for durable machine snapshots.
  Per Spec 011 §The hydration payload (`:rf/runtime-db`) + Spec 015
  §State machines.

  ## The classification boundary this enforces

  Machine snapshots are durable runtime-db state living at
  `[:rf.runtime/machines :snapshots <actor-id>]`, each carrying a
  `{:state :data :tags …}` body. The SSR hydration payload's
  `:rf/runtime-db` slice ships `:rf.runtime/machines` so the client
  re-materialises actors. `re-frame.ssr.payload-policy/project-runtime-db`
  consults this hook so a snapshot whose frame classifies a `:data` path
  `:sensitive` / `:large` has that field redacted / elided in the
  hydration blob — frame-owned classification governs machine `:data`
  egress. Like resources (which has `:ssr/extend-runtime-db-projection`),
  machines provides this SSR projection hook.

  ## What this does

  `project-ssr-runtime-db` projects the `:rf.runtime/machines` slice for the
  SSR hydration boundary: each snapshot's `:data` is run through the merged
  frame-owned `re-frame.projection/project-egress` under the
  `:rf.egress/ssr-hydration` profile, so any `:data` path the FRAME classifies
  redacts (`:rf/redacted`) / elides (`:rf.size/large-elided`) before it rides
  the wire. The projection uses SHARED frame-independent primitives — NEVER a
  family-private elider.

  Machine `:data` egress classification lives in the per-frame elision
  registry like every other app-db / runtime-db path. EP-0025: a machine
  definition declares its sensitive / large `:data` slots PROJECTION-RELATIVE
  (`re-frame.machines.classification`), lowered per actor instance into the
  registry under `:source :effect`; the commit-plane `:sensitive` / `:large`
  effects are the general app-db mechanism. The `:data-schema` VALIDATES
  `:data`; it does not classify it for egress.

  Snapshots whose frame classifies no matching `:data` path ride VERBATIM — the
  projection is precise, not a blanket scrub. The sibling registry slots
  (`:system-ids`, `:spawned`, `:spawn-counter`) are durable bookkeeping (reverse
  indexes + counters — no user `:data`) and ride unchanged.

  ## Late-binding

  Published as the late-bound `:machines/project-ssr-runtime-db` hook so SSR
  consults it WITHOUT statically `:require`ing the machines artefact (SSR
  depends on core only; machines is optional). An app that loads machines but
  does not SSR carries none of this path; an SSR app without machines sees the
  hook unbound and ships the (already machine-less) runtime-db unchanged.
  Pure / host-agnostic CLJC — SSR runs on the JVM."
  (:require [re-frame.classification :as classification]))

#?(:clj (set! *warn-on-reflection* true))

(defn project-snapshot-data
  "Project ONE machine snapshot's `:data` for the SSR hydration boundary.

  `actor-id` is the snapshot's key (the singleton machine-id OR a spawned
  instance id). `frame-id` is the SSR request frame whose runtime-db machine-
  snapshot classification governs the projection (nil ⇒ no frame walk ⇒ `:data`
  rides verbatim).

  Machine `:data` egress classification lives in the per-frame elision
  registry. EP-0025: a machine definition declares its sensitive / large
  `:data` slots PROJECTION-RELATIVE (`re-frame.machines.classification`),
  lowered per actor instance into the registry as the absolute runtime-db
  path `[:rf.runtime/machines :snapshots <actor-id> :data …]` under
  `:source :effect`. `re-frame.classification/frame-snapshot-classification`
  re-roots those declarations snapshot-relative (`[:data …]`); here we strip
  the leading `:data` segment to index into the snapshot's bare `:data` map
  and redact via
  the SHARED frame-independent `re-frame.classification/redact-with-paths`
  walker — `:sensitive` slots to
  `:rf/redacted`, `:large` to the `:rf.size/large-elided` marker (sensitive
  wins). Snapshot egress does not consult per-slot `:sensitive?` / `:large?`
  schema marks; frame-owned classification is the sole mechanism.

  The snapshot's NON-`:data` slots (`:state`, `:tags`, `:rf/machine-type`, the
  reserved spawn-counter slot, …) are durable structural facts the client needs
  to re-materialise the actor — they ride VERBATIM. Returns the snapshot with
  its `:data` projected (or unchanged when the snapshot carries no `:data`, or
  when the frame classifies no matching `:data` path)."
  [actor-id snapshot frame-id]
  (if-not (and (map? snapshot) (contains? snapshot :data) frame-id)
    snapshot
    (let [{:keys [sensitive large]} (classification/frame-snapshot-classification frame-id actor-id)
          ;; frame-snapshot-classification returns snapshot-rooted paths ([:data …]);
          ;; the SSR projector walks the bare :data MAP, so strip the leading
          ;; :data segment to index into it directly.
          strip   (fn [paths] (into [] (comp (filter #(= :data (first %)))
                                             (map #(subvec (vec %) 1)))
                                    paths))
          s-paths (strip sensitive)
          l-paths (strip large)]
      (if (or (seq s-paths) (seq l-paths))
        (update snapshot :data classification/redact-with-paths s-paths l-paths)
        snapshot))))

(defn project-ssr-runtime-db
  "Project the `:rf.runtime/machines` slice of `runtime-db` for the SSR
  hydration `:rf/runtime-db` payload (the body behind the
  `:machines/project-ssr-runtime-db` late-bind hook). Returns the
  `:rf.runtime/machines` value with every snapshot's `:data` projected per its
  frame-owned `:sensitive` / `:large` classification (`project-snapshot-data`);
  or `runtime-db`'s `:rf.runtime/machines` value unchanged when it carries no
  snapshots.

  `frame-id` is the SSR request frame (nil ⇒ owner marks only — see
  `project-snapshot-data`). The `:snapshots` map is re-projected entry-by-entry
  keyed on actor-id; the sibling registry slots (`:system-ids`, `:spawned`,
  `:spawn-counter`) ride verbatim (durable reverse indexes + counters carrying
  no user `:data`). Returns nil when `runtime-db` carries no
  `:rf.runtime/machines` slice (so the SSR projector omits the key). Pure."
  [runtime-db frame-id]
  (when (map? runtime-db)
    (let [machines (get runtime-db :rf.runtime/machines)]
      (when (some? machines)
        (let [snapshots (:snapshots machines)]
          (if (seq snapshots)
            (assoc machines :snapshots
                   (reduce-kv
                     (fn [acc actor-id snapshot]
                       (assoc acc actor-id
                              (project-snapshot-data actor-id snapshot frame-id)))
                     {}
                     snapshots))
            machines))))))
