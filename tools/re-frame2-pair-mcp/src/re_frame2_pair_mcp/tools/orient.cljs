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
  `app-db-value` top-keys + `rf/machines`) — no reinvention.

  Most valuable precisely for devs NOT working on re-frame2 tooling: they
  hand the agent an arbitrary app and the agent needs a fast map of it.

  ## Summary shape

  `{:ok? true
    :liveness {:debug-enabled? :frame-count :app-frame-count
               :ambiguous-frame? :runtime-instance-id}
    :frames   {:all [...] :app [...] :operating <id>}
    :app-db-top-keys {<app-frame-id> [<top-level key> ...]}
    :registry {:counts {<kind> N ...} :events [...] :subs [...] :fx [...]}
    :machines [...]}`

  Compact + summarized by design (respect the wire cap): registrar COUNTS
  for every v1 kind, the full sorted id vectors for the three most
  navigable kinds (`:event` / `:sub` / `:fx`), and per-app-frame app-db
  TOP-KEYS — NOT the full app-db. Drill via the existing `list-handlers` /
  `list-subscriptions` / `snapshot` / `get-path` / `read-sub` ops.

  Reserved `:rf/*` tool frames (Xray's `:rf/xray`, SSR slots, …) are split
  out of `:frames` (`:app` vs `:all`) and EXCLUDED from `:app-db-top-keys`
  so a first-contact orientation doesn't overflow on tool-frame
  inspection state.

  Read-only + idempotent across same-state calls — no side-effect beyond
  the idempotent listener install `health` performs."
  (:require [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

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
          (wire/ok-text (assoc v :build build-id))
          (wire/err-text {:ok? false :reason :unexpected-shape :value v :build build-id}))))))
