(ns re-frame.routing.resolve
  "The ONE resolved-target / route-plan seam every navigation door lowers to
  (EP-0037 R0b).

  Per Spec 012 §Resolved target and the plan diagnostic projection: planning
  turns a caller's address into resolved **facts** — a `ResolvedTarget` — and
  the internal **route plan** every door executes. Doors differ in cause and
  history / scroll policy, not in target, entry, resource, or readiness
  semantics (Spec 012 §The one planning pipeline). The programmatic, link,
  URL-change, initial-load, and SSR handlers stay separate public events for
  cause-specific tests and host integration, but they all shape their resolved
  target and build their plan through THIS seam — so no door reinvents the
  ResolvedTarget shape or the plan projection.

  Guard coverage (`re-frame.routing.decisions/decide`) and
  leaf-only planning + commit (`re-frame.routing.events/commit-navigation`) are
  each already ONE definition every door calls; this namespace adds the third:
  the ResolvedTarget fact shape and the plan's diagnostic projection.

    - `resolved-target` — the `ResolvedTarget` facts `{:route-id :params
      :query :fragment :url}`. Facts say `:route-id`; intent says `:to`. It is
      the value the door commits (it feeds `commit-navigation`'s slice) — so
      the seam is load-bearing, not a parallel diagnostic copy.
    - `route-plan` — the internal route plan the door executes, carrying the
      R0 diagnostic projection: the source address / raw-URL request, the
      cause, the resolved target, the parent-to-leaf branch, and the
      behaviour-preserving leaf resource plan.
    - `plan-projection` — the R0 diagnostic projection of a plan (the minimum
      needed to prove the shared spine), the pure on-demand view a tool
      (Xray / trace tooling) reads. Later EP-0037 slices widen it (R2 adds
      occurrence / dependency groups, the grouped order, and the identity
      diff); R0 exposes only the source + cause, the resolved target, the
      branch, and the leaf plan. This adds no public `RoutePlan` constructor
      or promise-returning router object — its observable laws are public and
      its projection is a pure derivation.

  Everything here is PURE. Internal namespace; the public facade is
  `re-frame.routing`."
  (:require [re-frame.registrar :as registrar]
            [re-frame.routing.subs :as routing-subs]))

;; ---- the R0 causes --------------------------------------------------------

(def causes
  "The closed set of navigation CAUSES an R0 route plan may carry (Spec 012
  §Resolved target and the plan diagnostic projection): a `route-link` click
  (`:link`), programmatic `:rf.route/navigate` (`:navigate`), Back / Forward
  (`:popstate`), initial load (`:initial`), and SSR (`:ssr`). Doors differ in
  cause and history / scroll policy, not in target / entry / resource
  semantics."
  #{:link :navigate :popstate :initial :ssr})

;; ---- the ResolvedTarget ---------------------------------------------------

(defn resolved-target
  "Shape the `ResolvedTarget` facts (Spec 012 §Resolved target and the plan
  diagnostic projection):

      {:route-id :route/article
       :params   {:slug \"routing-as-data\"}
       :query    {:tab :comments}
       :fragment \"reply-42\"
       :url      \"/articles/routing-as-data?tab=comments#reply-42\"}

  after matching, defaults, and validation. It is a FACT, not another accepted
  input spelling — facts say `:route-id`, intent says `:to`. The values are
  reflected verbatim from what the door already resolved (route-id / params /
  query / fragment / the committed URL); this reshaping never re-normalises,
  so the target a door commits through here is byte-identical to the slice it
  committed before R0b."
  [{:keys [route-id params query fragment url]}]
  {:route-id route-id
   :params   params
   :query    query
   :fragment fragment
   :url      url})

;; ---- the parent-to-leaf branch + the leaf resource plan -------------------

(defn branch-of
  "The parent-to-leaf branch for a resolved target's `route-id` — the vector
  `[parent-most ... leaf]` derived from the routes' `:parent` chain (Spec 012
  §Nested layouts). The plan's branch field. Shares the ONE `:parent`-chain
  walk the `:rf.route/chain` sub reduces over (`routing-subs/chain-from-meta`),
  so the plan branch and the public chain sub can never disagree. The full
  parent-to-leaf branch PLAN (per-segment resource requirements) graduates in
  EP-0037 R2; R0 exposes the branch itself."
  [route-id]
  (routing-subs/chain-from-meta route-id))

(defn leaf-plan-of
  "The behaviour-preserving LEAF resource plan for a resolved target's
  `route-id` — the route's declarative `:on-match` loader vector (Spec 012
  §Per-route data loading), the events the runtime dispatches when the route
  becomes active. This is the leaf plan `commit-navigation` already executes;
  exposing it on the route plan is the R0 diagnostic projection of it (Spec
  012 §Resolved target and the plan diagnostic projection — 'the
  behaviour-preserving leaf resource plan'). The honest resource-derived
  readiness projection graduates in EP-0037 R1; R0 reflects the loaders that
  already fire, unchanged. Empty vector when the route declares no `:on-match`."
  [route-id]
  (vec (or (:on-match (registrar/lookup :route route-id)) [])))

;; ---- the route plan -------------------------------------------------------

(defn route-plan
  "Build the internal route plan a door executes (Spec 012 §Resolved target
  and the plan diagnostic projection). `source` is the caller's source address
  or raw-URL request (`{:to ...}` / `{:url ...}`); `cause` is one of `causes`;
  `target` is the `resolved-target` facts. The plan derives the parent-to-leaf
  `:branch` and the behaviour-preserving `:leaf-plan` from the resolved
  `route-id`, so a door constructs the plan from just its source, cause, and
  resolved target — the ONE place the branch + leaf plan are derived.

  Plain data — this contract adds no public `RoutePlan` constructor or
  promise-returning router object; its diagnostic projection is
  `plan-projection`."
  [{:keys [source cause target]}]
  (let [route-id (:route-id target)]
    {:source    source
     :cause     cause
     :target    target
     :branch    (branch-of route-id)
     :leaf-plan (leaf-plan-of route-id)}))

;; ---- the diagnostic projection --------------------------------------------

(def r0-projection-keys
  "The keys the R0 plan diagnostic projection exposes (Spec 012 §Resolved
  target and the plan diagnostic projection): the minimum needed to prove the
  shared spine — the source address and cause, the resolved target, the
  parent-to-leaf branch, and the behaviour-preserving leaf resource plan.
  Later EP-0037 slices widen the projection (R2 adds occurrence / dependency
  groups, contributor dedupe advisories, the grouped order, and the identity
  diff)."
  [:source :cause :target :branch :leaf-plan])

(defn plan-projection
  "The R0 diagnostic projection of a route `plan` — the pure, on-demand view a
  tool (Xray / trace tooling) reads to prove the shared spine, exposing ONLY
  `r0-projection-keys`. No slice is licence to build a second Xray graph or a
  general-purpose public plan debugger (Spec 012 §Resolved target and the plan
  diagnostic projection); this is a `select-keys` over the plan the doors
  already build."
  [plan]
  (select-keys plan r0-projection-keys))
