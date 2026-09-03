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
    - `plan-trace-tags` — the projection as `:rf.route/planned` TRACE tags, the
      ONE mapping both door commit branches emit through so the projection is
      reachable from an executed navigation without the trace becoming a
      carrier.

  Everything here is PURE. Internal namespace; the public facade is
  `re-frame.routing`."
  (:require [re-frame.identity :as rf.identity]
            [re-frame.privacy.url :as rf.privacy.url]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing.events :as rf.routing.events]
            [re-frame.routing.plan :as rf.routing.plan]
            [re-frame.routing.registry :as rf.routing.registry]))

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

(defn- without-nil-query-values
  "Drop every query key whose value is nil.

  `route-url` ELIDES a nil-valued query key when it builds the URL (rf2-gxq7z1),
  so a target that KEEPS one describes a place its own canonical URL cannot
  spell: the slice says `{:drop nil}` while the address bar says `/probe/7`, and
  the same destination reached by URL resolves `{}`. A nil value is the caller
  saying \"this key is not set\" — the absent-key spelling of the same fact — so
  the resolved target carries the absence, not a nil.

  Returns `query` IDENTICALLY when it holds no nil value, which is the common
  case: the URL doors' query comes from `match-url`, whose coercions are total
  passthroughs (never nil-producing), and rebuilding it would discard the
  canonical KEY ORDER `rf.routing.registry/canonical-query-order` just established. When a
  nil IS present the surviving entries are poured back into `(empty query)`, so
  an array-map's order survives the strip too."
  [query]
  (if (some nil? (vals query))
    (into (empty query) (remove (comp nil? val)) query)
    query))

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

  `:route-id` / `:params` / `:url` are reflected VERBATIM from what the door
  already resolved. `:query` and `:fragment` are the two fields this seam
  RESOLVES rather than reflects, and all three of their rules are the same rule:
  a resolved target must describe a place its own canonical URL can spell, so
  every spelling of one destination lowers to one target.

    - `:query` — the route's declared `:query-defaults` are filled into absent
      keys (`rf.routing.registry/query-with-defaults`), because that is the \"defaults\"
      step Spec 012's own definition of a `ResolvedTarget` names; and
      nil-valued keys are dropped first (`without-nil-query-values`), because
      `route-url` elides them from the URL. Stripping BEFORE filling is what the
      programmatic door already did inline, preserved exactly: a route that
      declares a nil DEFAULT still gets it.
    - `:fragment` — an empty-string fragment collapses to nil
      (`rf.routing.plan/normalize-fragment`), because `route-url` emits no trailing `#` for
      it. `\"\"` is truthy, so an un-normalised one made the slice say
      `:fragment \"\"` while the address bar said `/docs`.

  This is the ONE place every door's target is shaped, so this is where those
  rules belong.

  The defaults fill is what makes the doors agree (rf2-kqxe6.23). `match-url`
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
  inverse (`rf.routing.registry/query-without-defaults`) keeps the URL free of a key
  already at its default, so no href changes and each target still has exactly
  one canonical URL. The fill is idempotent, so a door whose query `match-url`
  already filled lowers through unchanged, and it is membership-only — no
  second normalisation pass, no per-door defaults hook, no public
  defaults-resolution API.

  The nil-query and empty-fragment rules moved here for the same reason and by
  the same evidence (rf2-kqxe6.7). Both were written down and applied at ONE
  door: the programmatic handler stripped nil query values inline and called
  `rf.routing.plan/normalize-fragment` itself, and `normalize-fragment`'s own docstring
  says the rule exists to \"keep the programmatic and URL-driven paths in
  agreement\". `[:rf.route/prefetch …]` reaches this seam directly, so it got
  neither: prefetching `{:to :probe :params {:id \"7\"} :query {:drop nil}}`
  warmed `{:drop nil :tab :overview}` while clicking the same address committed
  `{:tab :overview}`, and `:fragment \"\"` warmed `\"\"` against a committed nil
  — two resource identities for one destination, the R3 failure mode over
  again. A door-local normalisation is a normalisation the next door forgets.
  Both are idempotent, so the programmatic door's own earlier
  `normalize-fragment` (which it still needs, to rebuild an unmatched raw URL
  before this seam runs) lowers through unchanged."
  [{:keys [route-id params query fragment url]}]
  {:route-id route-id
   :params   params
   :query    (rf.routing.registry/query-with-defaults (rf.registrar/lookup :route route-id)
                                           (without-nil-query-values query))
   :fragment (rf.routing.plan/normalize-fragment fragment)
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
  (let [{:keys [match throw-reason]} (rf.routing.registry/match-url-fail-closed url)
        ;; Discriminate the bare-miss case from the malformed-URL case only
        ;; when `match-url` already missed (the happy path pays nothing); a
        ;; throw already discriminated via `throw-reason` short-circuits the
        ;; predicate, so the URL is never scanned twice.
        malformed?       (boolean (and (nil? match) (nil? throw-reason)
                                       (rf.routing.registry/malformed-url? url)))
        ;; A malformed URL surfaces no fragment — the fragment was (or may
        ;; have been) the decode-fail site.
        fragment         (when-not malformed? (:fragment match))
        matched?         (some? match)
        validation-fail? (boolean (:validation-failed? match))
        fallback?        (or (not matched?) validation-fail?)
        route-id         (if fallback? :rf.route/not-found (:route-id match))
        params           (cond
                           throw-reason     (rf.routing.plan/not-found-params url throw-reason)
                           malformed?       (rf.routing.plan/not-found-params url :malformed-url)
                           validation-fail? (rf.routing.plan/not-found-params url :validation)
                           (not matched?)   (rf.routing.plan/not-found-params url nil)
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

;; rf2-cqyq2 — the plan's branch is the FAIL-LOUD walk, resolved ONCE.
;;
;; Two `:parent` walks exist deliberately. The DISPLAY walk behind the
;; `:rf.route/chain` sub (`re-frame.routing.subs/chain-from-meta`) is defensive:
;; it swallows cycles and INCLUDES an unregistered parent id in the chain it
;; returns. `rf.routing.events/resolve-branch` is the PLANNING walk: fail-loud, reporting
;; `{:branch-error {:kind :unknown-parent | :parent-cycle …}}` rather than a
;; silently-truncated branch — and `events.cljc` says so in as many words, that
;; the display sub "is not a substitute here".
;;
;; The plan's `:branch` used to delegate to the display walk (its docstring
;; correctly claimed it "can never disagree" with the chain sub) while
;; `commit-navigation` independently called `resolve-branch` for the resource
;; composition. So the plan REPORTED one branch and EXECUTED another, and they
;; disagreed exactly on the malformed-registration cases where a diagnostic
;; earns its keep: `:rf.route/planned` named `:route/nowhere` as a branch
;; segment — no such route — while the very same activation aborted with
;; `:branch-error :unknown-parent` and landed `:transition :error`. R0 exposed
;; the branch as a reflection of the chain sub; R2 then needed a fail-loud walk
;; and ADDED a second one rather than promoting the first.
;;
;; Resolving it here, once, is both the fix and a walk removed per navigation:
;; the plan carries the ids (the R0 diagnostic `:branch`), the error, and the
;; per-segment contributors `commit-navigation` hands the resource plan, so the
;; commit hop reads the branch off the plan the door already built instead of
;; re-walking it. `chain-from-meta` stays exactly as it is — it is correct for
;; what it does; it is just not the plan branch.

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
  (vec (or (:on-match (rf.registrar/lookup :route route-id)) [])))

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
  promise-returning router object; its observable projection is
  `plan-trace-tags`, the `:rf.route/planned` trace both door commit branches
  emit through.

  The parent-to-leaf `:branch` is the FAIL-LOUD walk
  (`rf.routing.events/resolve-branch`), resolved ONCE per navigation here:

    - `:branch` — `[parent-most … leaf]` route ids, the R0 diagnostic field.
      EMPTY when the chain does not resolve, so the plan can never name a route
      the registry does not carry;
    - `:branch-error` — `{:kind :unknown-parent|:parent-cycle :route-id* …}`
      when a `:parent` names an unregistered route or the chain cycles, else
      absent. The plan's honest failure signal;
    - `:branch-contributors` — the walk's `[{:route-id :route-meta} …]`
      segments, the value `commit-navigation` hands the late-bound
      `:routing/on-route-entry` resource plan. Carried on the plan so the commit
      hop READS the branch the door already resolved rather than walking the
      `:parent` chain a second time."
  [{:keys [source cause target]}]
  (let [route-id (:route-id target)
        {:keys [branch branch-error]} (rf.routing.events/resolve-branch route-id)]
    (cond-> {:source              source
             :cause               cause
             :target              target
             :branch              (mapv :route-id branch)
             :branch-contributors (vec branch)
             :leaf-plan           (leaf-plan-of route-id)}
      branch-error (assoc :branch-error branch-error))))

;; ---- the projection as trace tags -----------------------------------------
;;
;; An on-demand `plan-projection` helper and its `r0-projection-keys` list used
;; to sit here (rf2-6r9j.4). Nothing read them: no runtime, Xray, trace tool,
;; example or conformance path called the helper, and its only caller was the
;; unit test that pinned it. `plan-trace-tags` below is the ONE projection an
;; executed navigation actually exposes — deliberately bounded and redacted
;; where the raw plan carries the carriers — so the helper was a second,
;; unreachable spelling of "the plan projection" that future plan-field changes
;; would have had to keep in step for no reader. Should a tool ever need an
;; on-demand view, land the concrete consumer and a supported access boundary
;; with it rather than re-planting a speculative internal helper.

(defn- bound-keys
  "The KEY SET of a resolved `:params` / `:query` map, as a vector in the total
  canonical order (`rf.identity/canonical-bytes`) the rest of routing reports key
  sets in — so a heterogeneous EDN-key map never trips a `compare`-based `sort`.
  Empty vector for nil / `{}`."
  [m]
  (vec (sort-by rf.identity/canonical-bytes (keys m))))

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
  VALUES — and this mapping declines to carry them. The reason is worth stating
  precisely, because it is NOT that emitting them would breach a boundary: there
  is no boundary here for a raw emit to reopen. The same carriers are already
  ambient on the same drain — `:rf.nav/push-url`'s fx args carry the identical URL
  string one trace row later, and the `:rf.route/navigate` event vector carries the
  query values verbatim on `:rf.event/dispatched`. So the projection declines to
  add a REDUNDANT copy of what is already there, and keeps the half that is
  diagnostically load-bearing: WHICH keys were bound, not what they were bound to.
  That is local emit-site hygiene, not a boundary the route classification
  enforces on the trace bus. It is lossy in exactly two ways, and only those two:

    - the URL rides the EXISTING `rf.privacy.url/redact-url-tag` path — the ONE
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

  rf2-cqyq2 — `:branch-error` rides only when the `:parent` chain FAILED to
  resolve, and only as its `:kind` + offending `:route-id*`. Both are
  registration-time identifiers, so neither is a carrier; a `:parent-cycle`'s
  `:chain` is deliberately left off the bus because `resolve-branch` builds it
  out of route-META maps, and route metadata on a trace tag is bulk no consumer
  branches on. The tag is a FAILURE SIGNAL, absent on every healthy navigation —
  which is what makes `:branch` honest: before this, `:branch` came from the
  display `:parent` walk while the activation composed over the fail-loud one, so
  a malformed registration produced a plausible branch naming an unregistered
  route with no indication that planning had failed on that very chain.

  Callers add the `:frame` stamp (the in-flight drain's frame), which is
  load-bearing rather than cosmetic: epoch capture admits only frame-tagged
  traces and the frame-level trace-disable gate keys suppression off it (Spec 012
  §Trace events — Frame attribution)."
  [{:keys [cause target branch branch-error leaf-plan]}]
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
      (cond-> branch-error
        (assoc :branch-error (select-keys branch-error [:kind :route-id*])))
      (rf.privacy.url/redact-url-tag :url)))
