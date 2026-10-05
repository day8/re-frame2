(ns re-frame2-pair-mcp.tools.orient
  "Tool: orient — app-shape orientation summary in one round-trip.

  ## What this answers

  \"What is this app and what can I drive?\" — the first-contact question
  when an agent connects to an UNFAMILIAR app. Without it, orienting
  takes several calls: `discover-app` (frames / health) + `snapshot`
  summary (app-db top-keys) + `list-handlers` (event-ids) +
  `list-subscriptions` (sub-ids) + machines / routes. `orient` composes
  those into ONE compact map by reusing the existing introspection
  surfaces (runtime-side
  `orient` → `health` + `app-frame-ids` + `registrar-list` +
  `app-db-value` top-keys + the preload's `machines-list`) — no reinvention.

  Most valuable precisely for devs NOT working on re-frame2 tooling: they
  hand the agent an arbitrary app and the agent needs a fast map of it.

  ## Summary shape

  `{:ok? true
    :liveness {:debug-enabled? :frame-count :app-frame-count
               :ambiguous-frame? :runtime-instance-id}
    :frames   {:all [...] :app [...] :operating <id>}
    :app-db-top-keys {<app-frame-id> [<top-level key> ...]}
    :registry {:counts {<kind> N ...} :events [...] :subs [...] :fx [...]}
    :machines [...]
    :truncated [{:slot [...] :shown N :total M :next {:tool ... :args ...}} ...]}`

  Bounded for the wire cap: registrar COUNTS for every v1 kind, the first
  `id-example-limit` sorted ids of the three most navigable kinds
  (`:event` / `:sub` / `:fx`) and of the machines, and per-app-frame
  app-db TOP-KEYS — NOT the full app-db. The runtime composes the whole
  id vectors; this tool caps them, because on an app with hundreds of
  registrations they alone are several times the default budget. Each
  capped list gets a `:truncated` entry naming its total and the
  `list-handlers` call that returns every id of that registry. Drill via
  the existing `list-handlers` / `list-subscriptions` / `snapshot` /
  `get-path` / `read-sub` ops.

  Reserved `:rf/*` tool frames (Xray's `:rf/xray`, SSR slots, …) are split
  out of `:frames` (`:app` vs `:all`) and EXCLUDED from `:app-db-top-keys`
  so a first-contact orientation doesn't overflow on tool-frame
  inspection state.

  Read-only + idempotent across same-state calls — no side-effect beyond
  the idempotent listener install `health` performs."
  (:require [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(def ^:private id-example-limit
  "How many ids of each capped kind the first-contact summary carries.
  Enough to show an app's naming and feature split; the counts carry the
  size, and `list-handlers` carries the rest."
  20)

(defn bound-summary
  "Cap the runtime summary's id lists at `id-example-limit` sorted
  examples each, recording every capped list under `:truncated` as
  `{:slot <path> :shown N :total M :next {:tool \"list-handlers\" :args
  {...}}}`. `:next` reads the same registry the summary did: a
  frame-based registry (`:basis :frame`) continues with that `:frame`,
  the process-wide one without it, and machines — which a frame's image
  does not carry — always without it. A list within the limit, and every
  other slot, rides through unchanged."
  [summary]
  (let [frame (when (= :frame (get-in summary [:registry :basis]))
                (get-in summary [:registry :frame]))
        slots [[[:registry :events] "event" frame]
               [[:registry :subs] "sub" frame]
               [[:registry :fx] "fx" frame]
               [[:machines] "machine" nil]]]
    (reduce
      (fn [s [path kind f]]
        (let [ids (get-in s path)]
          (if (and (sequential? ids) (> (count ids) id-example-limit))
            (-> s
                (assoc-in path (vec (take id-example-limit ids)))
                (update :truncated (fnil conj [])
                        {:slot  path
                         :shown id-example-limit
                         :total (count ids)
                         :next  {:tool "list-handlers"
                                 :args (cond-> {:kind kind} f (assoc :frame (str f)))}}))
            s)))
      summary
      slots)))

(defn orient-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        form     (ef/emit (ef/rt-call 'orient))]
    (probe/eval-after-runtime!
      conn build-id form :orient-failed
      (fn [v]
        (if (map? v)
          ;; Echo the canonical resolved `:build` so the agent sees
          ;; which build this orientation ran against (the
          ;; session-sticky target when `:build` was omitted) and can
          ;; copy it straight into later calls.
          (wire/ok-text (assoc (bound-summary v) :build build-id))
          (wire/err-text {:ok? false :reason :unexpected-shape :value v :build build-id}))))))
