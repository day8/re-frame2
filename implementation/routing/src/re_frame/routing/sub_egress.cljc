(ns re-frame.routing.sub-egress
  "Route-sub egress projection — the EP-0025 sub-egress complement of the
  route-classification lowering (`re-frame.routing.classification`, rf2-mtzv5m).

  ## The problem

  A route declares `:sensitive` / `:large` paths PROJECTION-RELATIVE to its
  `{:query … :params …}` current-state projection. At route activation those
  declarations are RE-ROOTED to runtime-db-absolute paths under
  `[:rf.runtime/routing :current …]` and lowered into the per-frame elision
  registry (`:source :route`). The SSR hydration egress already honours that
  re-rooting (`re-frame.ssr.payload-policy/project-routing-egress`, seeded at
  `:path [:rf.runtime/routing]`, rf2-4xut98).

  But the DIRECT-READ egress surfaces of the bare route slice do NOT. The
  `:rf/route` sub returns the BARE slice (`{:route-id :params :query …}`) read
  from `[:rf.runtime/routing :current]`, and the trace / off-box egress walks
  the sub value seeded at the WHOLE-VALUE root (`#{[]}`), where the registry's
  absolute `[:rf.runtime/routing :current :query :token]` declaration can never
  match. `re-frame.classification/project-sub-tags` only consults the SUB
  REGISTRATION's own declared classification (the `:rf/route` registration
  declares none — it is the route DECLARATION that owns the durable runtime-db
  classification, not the sub registration). So a `:sensitive` route query /
  param shipped RAW on the `:rf.sub/run` dev-trace, the Pair MCP `read-sub` /
  `list-subscriptions :include-values` / `snapshot :sub-cache` reads, and Xray —
  contradicting [Spec 012 §Lowering and re-rooting](../../../../../../spec/012-Routing.md)
  (\"only the trace bus / Xray / MCP / off-box / SSR egress copies are
  redacted\").

  ## The fix (Mike-ruled (a), rf2-mtzv5m — NARROW)

  Apply the route classification at the direct-read egress of the route's read
  surfaces, by re-seeding the egress walk at the route slice's runtime-db
  storage position so the registry's re-rooted absolute declarations match. This
  is the direct-read sibling of the SSR `project-routing-egress` fix.

  NARROW: this is NOT generic sub-output propagation. ONLY the framework-owned
  route read surfaces (`:rf/route`, `:rf.route/query`, `:rf.route/params`) are
  treated as alternate PROJECTIONS of the route-owned durable fact. An app sub
  that reads route values into a re-keyed shape is NOT covered (fail-open
  hygiene, exactly as EP-0025 §What is removed disclaims for re-keyed copies).
  In-process `@(rf/subscribe [:rf/route])` stays RAW — the redaction is read
  ONLY at egress (the handler / views / app subs always see the real values).

  ## The seed table

  `route-sub-seed-table` maps each framework route read sub-id to the
  runtime-db storage position its VALUE projects onto:

    | sub-id            | sub value                    | seed path                                 |
    |-------------------|------------------------------|-------------------------------------------|
    | `:rf/route`       | whole slice `{:query … …}`   | `[:rf.runtime/routing :current]`          |
    | `:rf.route/query` | the `:query` map             | `[:rf.runtime/routing :current :query]`   |
    | `:rf.route/params`| the `:params` map            | `[:rf.runtime/routing :current :params]`  |

  The other `:rf.route/*` derived subs (`:rf.route/id`, `:transition`,
  `:error`, `:fragment`, `:chain`) carry no classifiable secret (the route id /
  transition keyword / fragment string / parent chain are structural), so they
  are NOT in the table — they ride verbatim.

  Core stays DECOUPLED: routing publishes the seed-path resolver and the
  projector through the late-bind table (`:routing/route-sub-egress-path` /
  `:routing/project-route-sub-egress`); core consults the hook keys without a
  static `:require` on routing (rf2-k682). Absent the routing artefact the hooks
  are unbound and the egress surfaces walk the value with NO route re-seeding —
  there is no route slice to leak anyway.

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split convention: SUB-EGRESS seam."
  (:require [re-frame.elision :as elision]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:const route-sub-seed-table
  "Map of framework route read sub-id → the runtime-db storage position the
  sub's VALUE projects onto. Walking a route sub's value seeded at this path
  lets the per-frame elision registry's re-rooted absolute route declarations
  (`[:rf.runtime/routing :current …]`, `:source :route`) match the bare slice /
  query / params value the sub returns. See the ns docstring's seed-table
  table."
  {:rf/route        [:rf.runtime/routing :current]
   :rf.route/query  [:rf.runtime/routing :current :query]
   :rf.route/params [:rf.runtime/routing :current :params]})

(defn route-sub-seed-path
  "Return the runtime-db seed path for a framework route read `sub-id`, or nil
  when `sub-id` is not a route read sub. Published as the
  `:routing/route-sub-egress-path` late-bind hook so the core wire walker
  (`re-frame.elision/elide-wire-value`, via its `:query-v` opt) re-seeds the
  walk at the route slice's storage position WITHOUT a static `:require` on
  routing. `sub-id` is the head of the query-vector; a non-keyword / unknown id
  returns nil (the common non-route case — no work)."
  [sub-id]
  (get route-sub-seed-table sub-id))

(defn project-route-sub-egress
  "Project a route read sub's `value` for egress, applying the route's
  projection-relative `:sensitive` / `:large` classification (lowered into the
  per-frame elision registry under `[:rf.runtime/routing :current …]` at route
  activation) by re-seeding the wire walk at the slice's runtime-db storage
  position. A no-op pass-through when `sub-id` is not a framework route read sub
  (the common case) — NARROW: NO generic sub-output propagation.

  `sub-id` is the sub's query-id (the head of `:rf.sub/id` / the query-vector);
  `opts` is the `elide-wire-value` opt-map (`:frame` + any `:rf.size/*`
  overrides). The route seed path is OVERLAID onto `opts` as `:path` so the
  walk's candidate declaration-coordinate set starts at the slice's storage
  position and the registry's re-rooted absolute route paths match exactly
  (mirroring the SSR `project-routing-egress` offset). Defers to
  `re-frame.elision/elide-wire-value` — never a private elider.

  Published as the `:routing/project-route-sub-egress` late-bind hook; the trace
  chokepoint (`re-frame.classification/project-sub-tags`) consults it for the
  `:rf.sub/run` `:rf.sub/value` / `:rf.sub/prev-value` slots. The direct-read
  off-box surfaces (Pair MCP `read-sub` / `list-subscriptions :include-values` /
  `snapshot :sub-cache` / Xray) reach the SAME re-seeding through
  `elide-wire-value`'s `:query-v` opt (which consults `route-sub-seed-path`),
  so every route read egress surface redacts identically.

  Fail-closed posture is inherited from `elide-wire-value`: a route sub value
  walked under a nil / unresolvable `:frame` redacts whole (no live frame ⇒ no
  reachable registry). A live frame with no route classification rides verbatim
  (the walk is path-precise, not a blanket scrub)."
  [sub-id value opts]
  (if-let [seed (route-sub-seed-path sub-id)]
    (elision/elide-wire-value value (assoc opts :path seed))
    value))
