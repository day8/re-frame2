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
      :query :fragment :url}`, after matching, defaults, and validation. Facts
      say `:route-id`; intent says `:to`. It is the value the door commits (it
      feeds `commit-navigation`'s slice) — so the seam is load-bearing, not a
      parallel diagnostic copy, and it is where the route's declared
      `:query-defaults` are filled for EVERY door (rf2-kqxe6.23).
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
    - `plan-trace-tags` — the projection as `:rf.route/planned` TRACE tags, the
      ONE mapping both door commit branches emit through so the projection is
      reachable from an executed navigation without the trace becoming a
      carrier.

  Everything here is PURE. Internal namespace; the public facade is
  `re-frame.routing`."
  (:require [re-frame.identity :as identity]
            [re-frame.registrar :as registrar]
            [re-frame.routing.egress :as egress]
            [re-frame.routing.plan :as plan]
            [re-frame.routing.registry :as registry]
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

  after matching, **defaults**, and validation. It is a FACT, not another
  accepted input spelling — facts say `:route-id`, intent says `:to`.

  `:route-id` / `:params` / `:fragment` / `:url` are reflected VERBATIM from
  what the door already resolved. `:query` is the one field this seam resolves
  rather than reflects: the route's declared `:query-defaults` are filled into
  absent keys here (`registry/query-with-defaults`), because that is the
  \"defaults\" step Spec 012's own definition of a `ResolvedTarget` names, and
  this is the ONE place every door's target is shaped.

  That single line is what makes the doors agree (rf2-kqxe6.23). `match-url`
  fills defaults, so the three URL-bearing doors always had them; the
  named-address doors — `[:rf.route/navigate {:to …}]`, `route-url`,
  `rf/route-link`'s href projection and `[:rf.route/prefetch …]` — never go
  through `match-url` and so never did. For a route declaring
  `:query-defaults {:tab :overview}` the same destination therefore committed
  `:query {}` through `{:to …}` and `{:tab :overview}` through every URL door:
  a different slice, a different derived URL (a different history entry), and a
  different resource cache identity depending on which door the user came
  through — the exact split Spec 012 §The one planning pipeline forbids (\"Doors
  differ in cause and history / scroll policy, not in target, entry, resource,
  or readiness semantics\"), and the reason R3's intent prefetch was silently
  inert for such routes (the warm entry and the click's entry landed on two
  different identities, so hovering then clicking one link produced TWO cache
  entries with the warm one orphaned).

  Filling HERE rather than per-door is the point: `route-url`'s emission
  inverse (`registry/query-without-defaults`) keeps the URL free of a key
  already at its default, so no href changes and each target still has exactly
  one canonical URL. The fill is idempotent, so a door whose query `match-url`
  already filled lowers through unchanged, and it is membership-only — no
  second normalisation pass, no per-door defaults hook, no public
  defaults-resolution API."
  [{:keys [route-id params query fragment url]}]
  {:route-id route-id
   :params   params
   :query    (registry/query-with-defaults (registrar/lookup :route route-id)
                                           query)
   :fragment fragment
   :url      url})

;; ---- the URL -> ResolvedTarget extraction (ONE definition) ----------------

(defn url-resolution
  "Resolve a requested URL to the ONE canonical `ResolvedTarget` every
  URL-bearing door commits, together with the fallback discriminators a
  caller's telemetry branches on.

  This is the shared URL half of the R0b seam. A URL is the representation
  every door shares, so the `:rf.route/not-found` **fallback normalisation** —
  the reserved `route-id`, the `{:url … :reason …}` params vocabulary, the
  emptied query, and the fragment a malformed URL cannot carry — has to be ONE
  definition. When the link door normalised a miss differently from the door
  that later committed it, the two disagreed about what the URL meant: the link
  door decided against an incomplete target with a `nil` `:route-id`, so the
  reserved `:rf.route/not-found` route's `:can-enter` guard was never consulted
  and a click on a dead link committed a route the equivalent programmatic
  `:rf.route/navigate` denied. The same mismatch hid the exact-no-op rule from
  the link door, which pushed a history entry for the already-active not-found
  URL. Deriving the target here — before stage 3 and the guards, and again at
  the commit hop — is what makes those three answers the same answer.

  Returns

      {:target           the canonical ResolvedTarget the door commits
       :match            the RAW `match-url` result (nil on a miss) — the
                         pre-fallback view the fragment-only classification
                         needs (Spec 012 §Fragments rules 3-4 compares against
                         the matched route, not the fallback)
       :matched?         `match-url` returned a match
       :validation-fail? the pattern matched but its `:params` / `:query`
                         schema rejected the parsed values
       :malformed?       a path capture / query key-value / `#fragment` failed
                         to %-decode
       :throw-reason     the throw discriminator (`:match-error`) when
                         `match-url` itself threw, else nil
       :fallback?        the target is the reserved `:rf.route/not-found`}

  `match-url-fail-closed` catches any throw and yields a nil match, so a
  hostile / throwing URL degrades to the same shape as a bare miss. The
  `:reason` discriminators are mutually exclusive: a throw pre-empts the
  malformed scan, and a validation fail is a match rather than a miss. The
  returned `:url` preserves the caller's spelling; `match-url` normalises the
  semantic route fields."
  [url]
  (let [{:keys [match throw-reason]} (registry/match-url-fail-closed url)
        ;; Discriminate the bare-miss case from the malformed-URL case only
        ;; when `match-url` already missed (the happy path pays nothing); a
        ;; throw already discriminated via `throw-reason` short-circuits the
        ;; predicate, so the URL is never scanned twice.
        malformed?       (boolean (and (nil? match) (nil? throw-reason)
                                       (registry/malformed-url? url)))
        ;; A malformed URL surfaces no fragment — the fragment was (or may
        ;; have been) the decode-fail site.
        fragment         (when-not malformed? (:fragment match))
        matched?         (some? match)
        validation-fail? (boolean (:validation-failed? match))
        fallback?        (or (not matched?) validation-fail?)
        route-id         (if fallback? :rf.route/not-found (:route-id match))
        params           (cond
                           throw-reason     (plan/not-found-params url throw-reason)
                           malformed?       (plan/not-found-params url :malformed-url)
                           validation-fail? (plan/not-found-params url :validation)
                           (not matched?)   (plan/not-found-params url nil)
                           :else            (:params match))
        query            (if fallback? {} (:query match))]
    {:target           (resolved-target {:route-id route-id
                                         :params   params
                                         :query    query
                                         :fragment fragment
                                         :url      url})
     :match            match
     :matched?         matched?
     :validation-fail? validation-fail?
     :malformed?       malformed?
     :throw-reason     throw-reason
     :fallback?        fallback?}))

(defn target-of-url
  "The canonical `ResolvedTarget` for a requested URL — `url-resolution`'s
  `:target`, for the callers (the link door's stage 3 + decision) that need the
  target and none of the fallback telemetry discriminators."
  [url]
  (:target (url-resolution url)))

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

;; ---- the projection as trace tags -----------------------------------------

(defn- bound-keys
  "The KEY SET of a resolved `:params` / `:query` map, as a vector in the total
  canonical order (`identity/canonical-bytes`) the rest of routing reports key
  sets in — so a heterogeneous EDN-key map never trips a `compare`-based `sort`.
  Empty vector for nil / `{}`."
  [m]
  (vec (sort-by identity/canonical-bytes (keys m))))

(defn plan-trace-tags
  "Project a route `plan` into the `:rf.route/planned` trace tags. ONE
  definition, called from every door commit branch, so the R0 diagnostic
  projection is REACHABLE from an executed navigation (Spec 012 §Resolved target
  and the plan diagnostic projection).

  A trace tag is an EGRESS surface, and the route's `:sensitive` classification
  cannot reach it: that classification is lowered against runtime-db slice PATHS
  (`classification/lower-for-route`), which redacts the `:rf/route` projections a
  tool reads out of the slice but says nothing about a tag map on the trace bus.
  The plan's `:target` nonetheless carries `:url` / `:params` / `:query` — carrier
  VALUES. Emitting them raw would reopen, by a route the classification cannot
  see, exactly the URL-carrier egress class the routing sweeps closed. So the
  mapping is deliberately lossy in two specific ways, and only those two:

    - the URL rides the EXISTING `egress/redact-url-tag` path — the ONE
      URL-carrier redactor routing already sends its route-miss and
      blocked-navigation URL slots through. It keeps the structured PATH (what a
      consumer branches on) and redacts the query-string and `#fragment` carrier
      values. No second redaction route for the same datum.
    - `:params` / `:query` VALUES are NOT carried. Their KEY SETS are: that `:id`
      and `:invite` were bound is diagnostically useful; that `invite=SECRET100`
      is the leak. A key set is not a carrier.

  The plan's `:source` — the caller's raw address / URL request — is a carrier by
  the same argument and is not carried either; `:cause` already names the door
  that supplied it and `:route-id` names what it resolved to. `:branch` is a
  vector of route ids and `:leaf-plan-ids` the leaf plan's event ids, both
  registration-time identifiers rather than runtime values, so both ride whole.

  Callers add the `:frame` stamp (the in-flight drain's frame), which is
  load-bearing rather than cosmetic: epoch capture admits only frame-tagged
  traces and the frame-level trace-disable gate keys suppression off it (Spec 012
  §Trace events — Frame attribution)."
  [{:keys [cause target branch leaf-plan]}]
  (-> {:cause         cause
       :route-id      (:route-id target)
       :url           (:url target)
       :param-keys    (bound-keys (:params target))
       :query-keys    (bound-keys (:query target))
       :branch        (vec branch)
       ;; The leaf plan's event IDs. An `:on-match` entry is authored route
       ;; metadata, but its argument positions are still values on an egress
       ;; surface; the id is the diagnostic half. Total over a non-sequential
       ;; entry so a malformed `:on-match` cannot throw on the trace path.
       :leaf-plan-ids (mapv #(if (sequential? %) (first %) %) leaf-plan)}
      egress/redact-url-tag))
