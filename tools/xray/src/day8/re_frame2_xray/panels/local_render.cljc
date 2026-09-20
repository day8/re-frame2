(ns day8.re-frame2-xray.panels.local-render
  "Xray's ON-BOX local-render egress seam — the EP-0015 `:rf.egress/local-redacted`
  graduating consumer (rf2-t55hxg.12).

  ## What this is

  ONE projection seam every Xray panel routes a FRAME-SOURCED value through
  before it reaches the shared `views/edn-inspector`: App-DB Diff,
  Subscriptions / Reactive, Machine Inspector, Flow, reply-envelope, Event
  Detail, Routing. It resolves the value through the public record-level
  boundary primitive `re-frame.core/project-egress` under the named egress
  PROFILE for on-box dev-tool rendering.

  Three arms, differing ONLY in how the walk is SEEDED — the profile, the
  `:frame` stamping and the fail-closed posture are shared:
  `local-render-value` walks a value AS the root; `local-render-value-at`
  seeds an explicit absolute `:path` for a slice egress'd in isolation;
  `local-render-route-sub-value` names a framework ROUTE READ sub and lets
  routing's own seed table supply the position (rf2-8nyi2). A route's
  classification is re-rooted under `[:rf.runtime/routing :current …]`, so
  the first arm cannot match it and the Routing panel needs the third.

  ## The default is `:rf.egress/local-redacted` (EP-0015 §10, issue 3)

  [spec/015-Data-Classification.md §Projection profiles] names six closed
  `:rf.egress/*` profiles. The on-box dev-UI default is
  **`:rf.egress/local-redacted`**: *suppress sensitive display by default;
  the local operator may still see large values* (Xray runs in the
  developer's own browser, against the developer's own app — Security.md
  permits on-box value inspection; the only thing the local-redacted
  default withholds is data the FRAME declared `:sensitive`, which a
  shoulder-surfer / screen-share / recorded-session must not leak).

  This is the EP-0015 issue-3 **graduation consumer** for the
  `:rf.egress/local-redacted` profile (the one remaining gap in
  [spec/015 §The graduation gate]): the profile was defined + unit-tested
  in `re-frame.projection` but no on-box dev tool named it as its render
  default. Xray is that tool — the named on-box-dev-tool consumer the
  graduation row requires. Routing the local panel render through it
  exercises the profile end-to-end.

  ## `:rf.egress/local-raw` is the per-(tool,frame) opt-in (EP-0015 issue 7)

  [spec/015 §Cross-tool visibility grain]: on-box visibility is per
  (tool, frame) — there is **no single process-global `show-sensitive?`
  user toggle**. The default is `:rf.egress/local-redacted`; revealing
  sensitive values is an explicit trusted-local OPERATOR ACT that flips
  the per-(tool,frame) grain to `:rf.egress/local-raw` (include sensitive
  AND large). `local-render-value`'s `raw?` arg carries that grain — the
  caller resolves it per (tool, frame); this seam does not own the toggle
  state.

  ## Why a frame-keyed projection (not a global redact)

  Egress policy is FRAME-SCOPED (EP-0002 / Spec 015 §Direct reads). A value
  is projected under the OBSERVED frame's own `:sensitive` / `:large`
  classification — the frame Xray is currently inspecting — never a
  borrowed or ambient one. The named frame is passed as the explicit
  `:frame` opt so the walk applies THAT frame's policy regardless of any
  ambient scope, exactly as `redact-graph-for-egress` (the off-box sibling,
  rf2-yjarv6) does.

  ## Fail-closed (the silent-leak this seam abolishes)

  `rf/project-egress` delegates to `rf/project-egress`, which reads its
  `:frame` opt by KEY PRESENCE — so an ABSENT `:frame` key falls through to
  the AMBIENT dynamically-bound frame, applying THAT frame's (possibly empty)
  policy and shipping value-bearing fields RAW under a borrowed scope. At a
  panel render that ambient frame is Xray's OWN chrome frame, which resolves,
  is live, and declares nothing — so the borrow is not hypothetical. The local-render
  default must not do that. So this seam STAMPS the observed `frame-id` as
  the `:frame` opt, whatever it is. The underlying walker reads that opt by
  KEY PRESENCE, so a nil / destroyed / never-registered id takes its
  UNRESOLVABLE-FRAME fail-closed branch (`frame/frame` returns nil for it)
  and redacts the whole value to `:rf/redacted` rather than borrow the
  ambient frame's marks. STAMPING (never OMITTING) `:frame` is the point: an
  absent `:frame` key is exactly the ambient-borrow path this seam abolishes
  (rf2-cra0nq, mirroring the off-box derivation-graph fix rf2-udkj69).
  (Under `:rf.egress/local-raw`'s explicit `:rf.egress/include-sensitive? true`
  opt-out the walker ships the value raw even under an unresolvable frame —
  the operator has explicitly asked for it; the opt-out branch precedes the
  fail-closed redact.)

  ## The edn-inspector renders the sentinels natively

  A sensitive slot becomes the `:rf/redacted` keyword sentinel; a large
  slot (only under the redacted default if the caller does NOT pass
  `include-large?`) becomes the `{:rf.size/large-elided …}` marker. The
  shared `views/edn-inspector` already paints BOTH as first-class types
  (the muted `redacted` chip / the yellow `● large` chip) — so the panel
  needs no special-casing: it hands the PROJECTED value to the inspector
  and the sentinels render themselves.

  JVM-portable (`.cljc`) so the local-render contract is pinned by the JVM
  test corpus without a CLJS runtime."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The on-box render profiles.
;; ---------------------------------------------------------------------------

(def local-redacted-profile
  "The EP-0015 on-box dev-UI DEFAULT egress profile (Spec 015 §Projection
  profiles): suppress sensitive display; the local operator may still see
  large values (see `local-render-opts`). The single source of truth for
  the keyword so panels + tests never fork the spelling."
  :rf.egress/local-redacted)

(def local-raw-profile
  "The trusted-local OPT-IN egress profile (EP-0015 §Cross-tool visibility
  grain): include sensitive AND large. Flipped per (tool, frame) by an
  explicit operator act, never a process-global toggle."
  :rf.egress/local-raw)

(defn local-render-profile
  "Resolve the egress profile a local panel render should project under,
  given the per-(tool,frame) `raw?` grain. `true` ⇒ the trusted-local
  `:rf.egress/local-raw` opt-in; anything else ⇒ the
  `:rf.egress/local-redacted` default."
  [raw?]
  (if (true? raw?) local-raw-profile local-redacted-profile))

(defn- local-render-opts
  "Build the `rf/project-egress` opts map for an on-box local render of a
  value sourced from `frame-id`.

  - **profile** — `local-render-profile` resolves the named boundary from
    the per-(tool,frame) `raw?` grain (default `:rf.egress/local-redacted`).
  - **`:frame`** — ALWAYS stamped, with `frame-id` VERBATIM. The walker
    reads its `:frame` opt by KEY PRESENCE, so a nil / destroyed /
    never-registered id is believed as `no governing frame` and takes the
    unresolvable-frame FAIL-CLOSED branch (redact the whole value). What it
    must never do is OMIT the key: an absent `:frame` falls through to the
    AMBIENT dynamically-bound frame — at a panel render, Xray's own chrome
    frame — and ships value-bearing fields RAW under that borrowed frame's
    policy, the ambient-borrow leak this seam abolishes (rf2-cra0nq,
    mirroring rf2-udkj69).

    This is why the fn is small again. Three passes (rf2-ws60) fought to
    mint a fake frame IDENTITY — a namespaced keyword, then a shared
    private object, then a fresh per-call object — each because a nil
    `:frame` read as absence, and each fail-OPEN in turn once a caller could
    get hold of the sentinel and register a live frame under it. The walker
    now believes an explicit nil, so there is no sentinel to leak and no
    liveness probe to run (the walker validates liveness itself). The fn
    stays private as ordinary namespace hygiene, not as a privacy
    load-bearer (rf2-kuky.5).
  - **`:rf.egress/include-large? true`** — the on-box 'keep large' override
    (EP-0015 §10: `local-redacted` *suppresses sensitive display*; the
    local operator IS entitled to large values — Xray's own size-bounding
    is a display ergonomics concern, not a privacy one). Composition: the
    profile floor (`include-large? false`) is overlaid by this explicit
    `:rf.egress/*` boolean (the override WINS — `re-frame.projection`
    §resolve-elision-opts). Under `:rf.egress/local-raw` the floor already
    includes large; the explicit overlay is a harmless no-op there.

  Pure and JVM-portable."
  ([frame-id] (local-render-opts frame-id false))
  ([frame-id raw?]
   {:rf.egress/profile      (local-render-profile raw?)
    :rf.egress/include-large? true
    :frame                  frame-id}))

;; ---------------------------------------------------------------------------
;; The projection seam.
;; ---------------------------------------------------------------------------

(defn local-render-value
  "Project `v` for ON-BOX local rendering through `rf/project-egress` under
  the EP-0015 on-box dev-UI default `:rf.egress/local-redacted` (or
  `:rf.egress/local-raw` when the per-(tool,frame) `raw?` grain opts in).

  `frame-id` is the OBSERVED frame whose `:sensitive` / `:large`
  classification governs the projection (the frame Xray is currently
  inspecting). A sensitive-declared slot is replaced by the `:rf/redacted`
  sentinel; non-sensitive siblings + large values ride through (the
  `include-large?` overlay). Structure is preserved — only declared leaf
  slots are touched.

  Fail-closed: an unreachable `frame-id` (nil / destroyed / never
  registered) under the redacted default redacts the WHOLE value rather
  than ship it raw under no policy. Idempotent over the sentinels (a
  re-projection of an already-`:rf/redacted` value is a no-op — the
  sentinel is a non-matchable scalar).

  Returns the projected value, ready to hand to the shared
  `views/edn-inspector` (which renders the `:rf/redacted` / large markers
  as first-class chips)."
  ([v frame-id] (local-render-value v frame-id false))
  ([v frame-id raw?]
   (rf/project-egress v (local-render-opts frame-id raw?))))

(defn local-render-value-at
  "Like `local-render-value`, but for a value that sits at absolute app-db /
  runtime-db `path` (a SLICE egress'd in isolation, not the whole walked
  root). Threading `:path` lets `rf/project-egress` match the path-keyed
  `:sensitive` / `:large` declarations a bare-value walk (path `[]`) would
  miss — e.g. the per-instance resource declarations the resources registry
  LOWERS onto absolute runtime-db slot paths rooted at the entry's byte
  key-id (`[:rf.runtime/resources :entries <key-id> :data …]`, rf2-aw9cfs).
  This is the Resources tab's on-box per-slot egress seam (rf2-9zix0u), the
  path-aware sibling of `local-render-value` the App-DB tab uses.

  Same fail-closed + per-frame guarantees as `local-render-value` — they
  share `local-render-opts`, so an unreachable `frame-id` (nil / destroyed /
  never registered) is stamped verbatim and redacts the whole
  slice rather than borrow the ambient frame's policy, and the projection
  applies the OBSERVED frame's own `:sensitive` / `:large` classification. The
  explicit `:path` composes with (never overrides) the profile floor + the
  `include-large?` overlay `local-render-opts` sets."
  ([v frame-id path] (local-render-value-at v frame-id path false))
  ([v frame-id path raw?]
   (rf/project-egress v (assoc (local-render-opts frame-id raw?)
                               :path (vec path)))))

(defn local-render-route-sub-value
  "Like `local-render-value`, but for the value of a FRAMEWORK ROUTE READ
  sub — `:rf/route` (the whole slice), `:rf.route/query`, or
  `:rf.route/params` — egress'd in isolation for an on-box render.

  A route declares `:sensitive` / `:large` PROJECTION-RELATIVE to its
  `{:query … :params …}` shape, and route activation RE-ROOTS those paths
  to runtime-db-absolute `[:rf.runtime/routing :current …]` in the frame's
  elision registry (`re-frame.routing.classification`). So a bare route
  value walked at the whole-value root — which is what `local-render-value`
  does — can never match its OWN declaration, and the declared-sensitive
  query rides to the DOM verbatim (rf2-8nyi2).

  Naming the sub as `:query-v` is the framework's prescribed gesture for a
  DIRECT-READ surface, and the reason this is not
  `local-render-value-at` with the path spelled out here:
  `re-frame.elision/elide-wire-value` resolves the seed through the
  routing-owned table (`re-frame.routing.sub-egress/route-sub-seed-table`,
  reached by the `:routing/route-sub-egress-path` late-bind hook) and
  overlays it as `:path`, so the seed stays ROUTING's to own and every
  route-read egress surface re-seeds identically. That identity is
  `project-route-sub-egress`'s own docstring contract: the direct-read
  off-box surfaces — Pair MCP's reads, and Xray — reach the SAME
  re-seeding through this opt.

  Same fail-closed + per-frame guarantees as its two siblings, which it
  shares `local-render-opts` with: an unreachable `frame-id` (nil /
  destroyed / never registered) is stamped VERBATIM and redacts the whole
  value rather than borrow the ambient frame's policy. A LIVE frame whose
  active route declared no classification rides the value verbatim — the
  walk is path-precise, never a blanket scrub — so ordinary query display
  is unchanged.

  NARROW, exactly as the routing door is: a `sub-id` outside the route
  read table resolves no seed and the value walks at the root. No generic
  sub-output propagation. Absent the routing artefact the hook is unbound
  and the same thing happens — and there is no route slice to leak."
  ([v frame-id sub-id] (local-render-route-sub-value v frame-id sub-id false))
  ([v frame-id sub-id raw?]
   (rf/project-egress v (assoc (local-render-opts frame-id raw?)
                               :query-v [sub-id]))))

(def route-slice-classified-projections
  "The route slice keys the route CLASSIFICATION CONTRACT covers, mapped to
  the framework route read sub whose seed path re-roots them.

  A route declares `:sensitive` / `:large` projection-relative to its
  `{:query … :params …}` shape (`re-frame.routing.classification`), so these
  two keys — and only these two — are the ones an author's declaration can
  name. Each maps to the routing-owned sub-id whose seed
  (`re-frame.routing.sub-egress/route-sub-seed-table`) is exactly the
  storage position that key's value sits at, which is what lets the
  re-rooted absolute declaration match the bare value.

  Deliberately NOT the whole slice. `:rf/route` would seed at
  `[:rf.runtime/routing :current]` and project every key at once, which
  reads as the tidier call — but it would also walk `:fragment`,
  `:transition`, `:error` and `:nav-token`, which the classification
  contract does not cover and about which
  `re-frame.routing.sub-egress` makes no sensitivity claim. Projecting the
  two covered keys INDIVIDUALLY keeps the blast radius equal to the
  contract."
  {:query  :rf.route/query
   :params :rf.route/params})

(defn local-render-route-slice
  "Project a route SLICE's classification-covered keys for an on-box render
  — `:query` and `:params`, each through `local-render-route-sub-value`
  under its own routing-owned seed (`route-slice-classified-projections`).

  This is the whole-slice composition of the route-sub arm above, and it
  exists because a route's declaration is projection-relative to the
  `{:query … :params …}` shape: `[:params :token]` and `[:query :token]`
  are BOTH ordinary declarations, re-rooted at activation to
  `[:rf.runtime/routing :current :params :token]` and
  `[… :query :token]`. Nothing in
  `re-frame.routing.classification` treats the two axes differently —
  `normalize-axis-paths` validates any concrete path and
  `apply-route-classification` re-roots every one of them the same way — so
  a surface that projects one axis and not the other honours half a
  contract.

  ## Only a key the router actually WROTE is touched

  A key absent from the slice stays absent rather than becoming a
  `:rf/redacted` sentinel a section would then render: fail-closed must not
  INVENT a value where the router wrote none. This is why the walk is
  per-key and guarded on `some?` rather than a blanket
  `update`-every-key.

  ## Guarantees inherited whole

  Same fail-closed + per-frame behaviour as the three seams above, which it
  shares `local-render-opts` with through them: `frame-id` is stamped
  VERBATIM, so a nil / destroyed / never-registered observed frame redacts
  rather than borrowing the ambient (Xray chrome) frame's policy. A LIVE
  frame whose active route declared no classification rides every key
  verbatim — the walk is path-precise, never a blanket scrub.

  `slice` nil (no active route) returns nil. Pure and JVM-portable."
  ([slice frame-id] (local-render-route-slice slice frame-id false))
  ([slice frame-id raw?]
   (reduce-kv
     (fn [acc slice-key sub-id]
       (if (some? (get acc slice-key))
         (assoc acc slice-key
                (local-render-route-sub-value (get acc slice-key) frame-id sub-id raw?))
         acc))
     slice
     route-slice-classified-projections)))
