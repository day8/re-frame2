(ns re-frame.machines.ssr
  "LATE-BOUND SSR / hydration projection for durable machine snapshots.
  Per Spec 011 §The hydration payload (`:rf/runtime-db`) + EP-0015 §6/§8
  (durable machine `:data` classification owned by the machine
  `:data-schema` `:sensitive?` / `:large?` per-slot props) + Spec 015
  §State machines.

  ## The leak this closes (rf2-jm2u63)

  Machine snapshots are durable runtime-db state living at
  `[:rf.runtime/machines :snapshots <actor-id>]`, each carrying a
  `{:state :data :tags …}` body. The SSR hydration payload's
  `:rf/runtime-db` slice ships `:rf.runtime/machines` so the client
  re-materialises actors. But `re-frame.ssr.payload-policy/project-runtime-db`
  previously copied `:rf.runtime/machines` WHOLESALE — so a snapshot whose
  machine `:data-schema` marks a slot `:sensitive?` (an auth token in
  machine data) or `:large?` shipped that field RAW in the hydration blob,
  exactly the leak the schema classification exists to prevent. Unlike
  resources (which has `:ssr/extend-runtime-db-projection`), machines had no
  SSR projection hook.

  ## What this does

  `project-ssr-runtime-db` projects the `:rf.runtime/machines` slice for the
  SSR hydration boundary: it walks each snapshot's `:data` against the
  owning machine's `:data-schema` per-slot marks — the SAME marks the
  trace-egress chokepoint redacts (`marks/marks-for :event <actor-id>`,
  snapshot-rooted under `[:data …]` by `reg-machine`'s schema bridge, EP-0005
  rf2-w46fpt). A `:sensitive?` slot redacts to the `:rf/redacted` sentinel; a
  `:large?` slot elides to the `:rf.size/large-elided` marker, via the SHARED
  frame-independent `re-frame.marks/redact-with-paths` walker — NEVER a
  family-private elider. The marks are also UNIONED with any frame-owned
  `:rf.egress/ssr-hydration` projection through `re-frame.projection/project-egress`
  so a frame-declared sensitive path inside `:data` redacts as
  defense-in-depth.

  Snapshots whose machine declares no `:data-schema` marks (and that carry no
  frame-classified `:data` slot) ride VERBATIM — the projection is precise, not
  a blanket scrub. The sibling registry slots (`:system-ids`, `:spawned`,
  `:spawn-counter`) are durable bookkeeping (reverse indexes + counters — no
  user `:data`) and ride unchanged.

  ## Late-binding

  Published as the late-bound `:machines/project-ssr-runtime-db` hook so SSR
  consults it WITHOUT statically `:require`ing the machines artefact (SSR
  depends on core only; machines is optional). An app that loads machines but
  does not SSR carries none of this path; an SSR app without machines sees the
  hook unbound and ships the (already machine-less) runtime-db unchanged.
  Pure / host-agnostic CLJC — SSR runs on the JVM."
  (:require [re-frame.machines.paths :as paths]
            [re-frame.marks :as marks]
            [re-frame.projection :as projection]))

#?(:clj (set! *warn-on-reflection* true))

(defn project-snapshot-data
  "Project ONE machine snapshot's `:data` for the SSR hydration boundary.

  `actor-id` is the snapshot's key (the singleton machine-id OR a spawned
  instance id — both are keyed under `:event` by `reg-machine`'s schema bridge
  at registration / spawn time, snapshot-rooted under `[:data …]`). `frame-id`
  is the SSR request frame whose app-db classification composes as
  defense-in-depth (nil ⇒ owner marks only; no frame walk).

  Two composed layers, both SHARED primitives (never a machines-private elider):

    (a) the machine's OWNER per-slot `:data-schema` `:sensitive?` / `:large?`
        marks (`marks/marks-for :event actor-id`, rooted under `[:data …]`)
        project through `re-frame.marks/redact-with-paths` — `:sensitive?`
        slots redact to `:rf/redacted`, `:large?` slots elide to the
        `:rf.size/large-elided` marker (sensitive wins at a co-marked slot).
        This fires irrespective of frame app-db classification — a machine that
        marks `[:data :token]` sensitive redacts that slot on SSR even when the
        frame says nothing about it.
    (b) when `frame-id` resolves, the result is THEN projected through the
        merged frame-owned `re-frame.projection/project-egress` under the
        `:rf.egress/ssr-hydration` profile, so any `:data` path the FRAME ALSO
        classifies redacts / elides as defense-in-depth.

  The snapshot's NON-`:data` slots (`:state`, `:tags`, `:rf/machine-type`, the
  reserved spawn-counter slot, …) are durable structural facts the client needs
  to re-materialise the actor — they ride VERBATIM. Returns the snapshot with
  its `:data` projected (or unchanged when the snapshot carries no `:data`)."
  [actor-id snapshot frame-id]
  (if-not (and (map? snapshot) (contains? snapshot :data))
    snapshot
    (let [data (:data snapshot)
          {:keys [sensitive large]} (marks/marks-for :event actor-id)
          ;; The schema bridge roots marks under [:data …]; the marks/redact
          ;; walker indexes from the value root, so strip the leading :data
          ;; segment to index into the snapshot's :data MAP directly.
          strip (fn [paths] (into [] (comp (filter #(= :data (first %)))
                                           (map #(subvec % 1)))
                                  paths))
          s-paths (strip sensitive)
          l-paths (strip large)
          ;; layer (a): owner per-slot marks (frame-independent).
          owner-projected (if (or (seq s-paths) (seq l-paths))
                            (marks/redact-with-paths data s-paths l-paths)
                            data)
          ;; layer (b): merged frame-owned egress projection (defense in depth).
          projected (if frame-id
                      (projection/project-egress
                        owner-projected
                        {:frame             frame-id
                         :rf.egress/profile :rf.egress/ssr-hydration})
                      owner-projected)]
      (assoc snapshot :data projected))))

(defn project-ssr-runtime-db
  "Project the `:rf.runtime/machines` slice of `runtime-db` for the SSR
  hydration `:rf/runtime-db` payload (the body behind the
  `:machines/project-ssr-runtime-db` late-bind hook). Returns the
  `:rf.runtime/machines` value with every snapshot's `:data` projected per its
  machine's `:data-schema` classification (`project-snapshot-data`); or
  `runtime-db`'s `:rf.runtime/machines` value unchanged when it carries no
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
