(ns re-frame.routing.sub-egress
  "Route-sub egress projection, complementing the route-classification
  lowering in `re-frame.routing.classification`.

  ## The problem

  A route declares `:sensitive` / `:large` paths PROJECTION-RELATIVE to its
  `{:query … :params …}` current-state projection. At route activation those
  declarations are RE-ROOTED to runtime-db-absolute paths under
  `[:rf.runtime/routing :current …]` and lowered into the per-frame elision
  registry (`:source :route`). SSR hydration honours that re-rooting by
  seeding its routing projection at `:path [:rf.runtime/routing]`.

  But the DIRECT-READ egress surfaces of the bare route slice do NOT. The
  `:rf/route` sub returns the BARE slice (`{:route-id :params :query …}`) read
  from `[:rf.runtime/routing :current]`, and the trace / off-box egress walks
  the sub value seeded at the WHOLE-VALUE root (`#{[]}`), where the registry's
  absolute `[:rf.runtime/routing :current :query :token]` declaration can never
  match. `re-frame.classification/project-sub-tags` only consults the SUB
  REGISTRATION's own declared classification (the `:rf/route` registration
  declares none — it is the route DECLARATION that owns the durable runtime-db
  classification, not the sub registration). So a `:sensitive` route query /
  param would ship RAW on the `:rf.sub/run` dev-trace, the Pair MCP `read-sub` /
  `list-subscriptions :include-values` / `snapshot :sub-cache` reads, and Xray —
  contradicting [Spec 012 §Lowering and re-rooting](../../../../../../spec/012-Routing.md)
  (\"only the trace bus / Xray / MCP / off-box / SSR egress copies are
  redacted\").

  ## Direct-read projection

  Apply the route classification at the direct-read egress of the route's read
  surfaces, by re-seeding the egress walk at the route slice's runtime-db
  storage position so the registry's re-rooted absolute declarations match. This
  is the direct-read sibling of the SSR `project-routing-egress` projection.

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
  `:error`, `:fragment`, `:chain`) are not projections covered by the route
  classification contract, so they are not in this table. This makes no
  general sensitivity claim about a fragment or error value; those surfaces
  use their own trace/egress policies.

  Core stays DECOUPLED: routing publishes the seed-path resolver and the
  projector through the late-bind table (`:routing/route-sub-egress-path` /
  `:routing/project-route-sub-egress`); core consults the hook keys without a
  static `:require` on routing. Absent the routing artefact the hooks
  are unbound and the egress surfaces walk the value with NO route re-seeding —
  there is no route slice to leak anyway.

  ## Prior values

  A navigation REPLACES the registry's route claims with the entering route's,
  so a held route sub's `:rf.sub/prev-value` — the leaving route's slice — can
  no longer be classified from the registry alone. It belongs to the route its
  slice names, and is additionally classified by the declaration activation
  recorded on that slice (`re-frame.routing.classification/slice-classification`),
  unioned with the route's current registration and re-rooted onto the value
  (`re-frame.routing.classification/classification-at`). So a route
  re-registered without its declaration before the navigation still redacts the
  value it governed. `:rf/route`'s value IS the slice; the `:rf.route/query` /
  `:rf.route/params` leaves read theirs from their one input, the prior
  `[:rf/route]` value the memo path carries as the prior inputs. The registry
  keeps no entry for the leaving route.

  Internal namespace; the public facade is `re-frame.routing`."
  (:require [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing.classification :as rf.routing.classification]))

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

(defn- prior-route-classification
  "The classification of the route a route read sub's PRIOR `value` was
  computed under, re-rooted onto that value, or nil. The route is the one its
  slice names: `:rf/route`'s value is the slice, and a leaf's is its one input
  in `prior-inputs`. The declaration recorded on that slice when the route
  activated governs it, unioned with the route's current registration, so
  re-registering the route between the compute and the trace declassifies
  nothing."
  [sub-id value prior-inputs seed]
  (let [slice    (if (= :rf/route sub-id) value (first prior-inputs))
        route-id (when (map? slice) (:route-id slice))]
    (when (some? route-id)
      (rf.routing.classification/classification-at
        (merge-with (comp vec distinct concat)
                    (rf.routing.classification/slice-classification slice)
                    (rf.routing.classification/validate+extract
                      route-id (rf.registrar/lookup :route route-id)))
        seed))))

(defn project-route-sub-egress
  "Project a route read sub's `value` for egress, applying the route's
  projection-relative `:sensitive` / `:large` classification (lowered into the
  per-frame elision registry under `[:rf.runtime/routing :current …]` at route
  activation) by re-seeding the wire walk at the slice's runtime-db storage
  position. A no-op pass-through when `sub-id` is not a framework route read sub
  (the common case) — NARROW: NO generic sub-output propagation.

  `sub-id` is the sub's query-id (the head of `:rf.sub/id` / the query-vector);
  `opts` is the `elide-wire-value` opt-map (`:frame` + any `:rf.egress/*`
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
  (the walk is path-precise, not a blanket scrub).

  The four-argument arity projects a PRIOR value (`:rf.sub/prev-value`), and
  `prior-inputs` are the inputs it was computed from. Beside the registry walk
  it is redacted by the declaration of the route it was computed under — see
  the ns docstring's §Prior values."
  ([sub-id value opts]
   (if-let [seed (route-sub-seed-path sub-id)]
     (rf.elision/elide-wire-value value (assoc opts :path seed))
     value))
  ([sub-id value opts prior-inputs]
   (if-let [seed (route-sub-seed-path sub-id)]
     (let [{:keys [sensitive large]} (prior-route-classification sub-id value prior-inputs seed)]
       (rf.elision/elide-wire-value
         (rf.classification/redact-with-paths value (or sensitive []) (or large []))
         (assoc opts :path seed)))
     value)))
