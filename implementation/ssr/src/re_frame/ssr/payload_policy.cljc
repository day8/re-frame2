(ns re-frame.ssr.payload-policy
  "Hydration-payload policy contract — explicit and fail-closed.

  Per Spec 011 §Payload scope (canonical boundary): the
  `:rf/hydration-payload` carries the **bounded** state needed to
  recompute the server's view on the client. Whether to ship a slice or
  the whole `app-db` is a security decision — the wrong default leaks
  internal state (auth tokens stashed for in-flight handlers, internal
  feature flags, server-only working scratch) to every visitor of every
  page.

  Shipping whole `app-db` when `:payload` is absent would make the privacy
  decision implicit, so missing policy fails closed.

  This namespace makes the policy:

    1. **Explicit** — callers must declare intent.
    2. **Fail-closed** — absence of an opt is a structural error, not
       a license to ship everything.
    3. **Allowlist-shaped** — denylists go stale silently as new keys
       land in `app-db`; an allowlist requires a deliberate edit per
       new wire-bound key.

  ---- The contract ----

  A single handler opt, `:payload`, carries one of two policy shapes:

    `:payload [<kw> <kw> ...]`
      An **allowlist** of top-level `app-db` keys to ship (a non-empty
      **vector** of KEYWORDS). Other keys are dropped — including any
      keys added later as the app evolves. The recommended primary
      mechanism. A non-empty vector carrying a non-keyword element
      (a string typo, a stray `nil`, a nested coll) fails loud at boot with
      `:rf.error/ssr-malformed-payload-allowlist` rather than silently
      shipping a wrong or empty slice. The allowlist is specifically a
      vector; a list or seq is not an accepted
      spelling); the vector-vs-keyword distinction IS the allowlist-vs-
      whole-app-db policy selector, so a single precise shape keeps the
      fail-closed security boundary unambiguous.

    `:payload :rf.ssr.payload/whole-app-db`
      An explicit opt-in to ship the whole `app-db`. Use only when the
      app's `app-db` is structurally safe to expose end-to-end — e.g.
      a small SPA where every key the server populates is intended
      for the client. The keyword is namespaced under `:rf.ssr.payload/`
      (per Conventions §`:rf/*` reserved namespace) so consumers
      reading the opt see the security weight of the choice.

  Absence of `:payload` is a structural error
  (`:rf.error/ssr-missing-payload-policy`) — fail-closed.

  One opt can hold exactly one value, so there is nothing to arbitrate: the
  allowlist-vs-whole-app-db choice is the value's shape
  (vector vs keyword), not a contest between two opts. Empty `:payload`
  (`[]`) is treated as **no allowlist supplied**, since shipping zero
  keys is almost certainly a programmer error rather than an intent.
  Callers that genuinely want to ship an empty `:rf/app-db` use
  `:payload :rf.ssr.payload/whole-app-db` against an empty `app-db`
  (i.e. don't populate it server-side).

  ---- Where this is consumed ----

  Two payload builders share this contract:

    `re-frame.ssr.ring.payload/build-payload`     — non-streaming SSR
    `re-frame.ssr.streaming/build-final-payload`  — streaming SSR

  The Ring host adapter (`re-frame.ssr.ring/ssr-handler` and
  `stream-handler`) validates the policy at handler-construction time
  via `validate-policy-opts!` so misconfigured deployments fail at
  boot rather than at first request — the canonical fail-closed
  pattern.

  ---- Version resolution + payload assembly ----

  This namespace is also the single home for the hydration-payload's
  `:rf/version` resolution (`resolve-version`) and the canonical
  four-key payload assembly (`build-payload`). Both streaming and
  non-streaming SSR construct the identical `:rf/hydration-payload`
  shape and pin `:rf/version` from the identical source-of-truth: the
  caller's explicit `:version` opt, falling back to the SSR artefact's
  compiled-in `pattern-protocol-version` constant — the SAME value the
  client-side `:rf.ssr/check-version` fx reads, so both wire ends agree
  with no host wiring. The two call sites differ only in how they source
  `app-db` (the non-streaming path is handed it; the streaming path
  reads it from the live frame after every continuation drains), so
  each owns a thin wrapper over `build-payload`:

    `re-frame.ssr.ring.payload/build-payload`     - non-streaming SSR
    `re-frame.ssr.streaming/build-final-payload`  - streaming SSR"
  (:refer-clojure :exclude [resolve])
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.projection :as projection]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- policy-spec keyword constants ----------------------------------------
;;
;; The whole-app-db keyword is reserved under the `:rf.ssr.payload/*`
;; namespace (per Conventions §`:rf/*` reserved namespaces — `:rf.ssr/*`
;; is the SSR sub-namespace; `:rf.ssr.payload/*` is the policy slot under
;; it).

(def whole-app-db-policy
  "Opt-in policy keyword for shipping the entire `app-db` in the
  hydration payload. Set as the `:payload` opt's value on the handler
  opts (`:payload :rf.ssr.payload/whole-app-db`)."
  :rf.ssr.payload/whole-app-db)

;; ---- policy resolution ---------------------------------------------------

(defn- valid-allowlist?
  "An allowlist is a non-empty **vector** of top-level app-db keys — the
  vector shape of the `:payload` opt. Per Spec 011 §`:rf/app-db`
  projection the elements are *top-level app-db keys*, which in re-frame2
  are keywords; this validator therefore requires EVERY element to be a
  keyword.

  rf2-hzttr finding 2 — the prior element-shape gap: a check of
  `(and (sequential? x) (seq x))` only verified the OUTER shape, so a
  malformed allowlist — a string typo for a keyword (`[\"public/articles\"]`),
  a stray `nil` (`[:a nil]`), or a nested coll (`[[:a :b]]`) — passed
  construction-time validation and was caught only later by `select-keys`,
  silently shipping an empty or wrong hydration slice instead of failing
  closed at boot. Validating each element to a keyword closes that gap.

  Outer shape — a non-empty SEQUENTIAL of keywords: a vector (the
  documented canonical spelling) OR a list / lazy-seq (`'(:a :b)`, or a
  computed `(mapv ...)` / `(keep ...)` / `(filterv ...)` result). The real
  policy selector is COLLECTION-vs-KEYWORD, not vector-vs-keyword — a
  sequential of keywords can never be confused with the whole-app-db keyword
  opt-in (`:rf.ssr.payload/whole-app-db`), so admitting any sequential
  spelling does not weaken the shape-selected fail-closed boundary. A SET is
  NOT sequential, so it still falls into the missing-policy bucket — the
  allowlist is an ORDERED key selection. (An empty `[]` / `'()` still fails
  closed via `(seq x)`; the `(every? keyword?)` element guard still catches a
  `\"public/articles\"` string typo as a malformed allowlist.)"
  [x]
  (and (sequential? x)
       (seq x)
       (every? keyword? x)))

(defn- malformed-allowlist?
  "A `:payload` that LOOKS like an allowlist attempt — a non-empty
  **sequential** — but carries a non-keyword element (a string typo, a stray
  `nil`, a nested coll, …). Distinguished from `valid-allowlist?` so the
  construction-time validator can surface a diagnostic
  `:rf.error/ssr-malformed-payload-allowlist` (which element is bad)
  rather than dumping the caller into the generic missing-policy bucket
  — the developer clearly INTENDED an allowlist; telling them which entry
  is wrong is the fail-loud, masterpiece outcome (rf2-hzttr finding 2).
  An empty `[]` / `'()` is NOT malformed — it is the documented `no-allowlist`
  shape that falls into the missing-policy bucket per Spec 011.

  Gated on `sequential?` to match `valid-allowlist?`'s outer shape: a
  list / lazy-seq with a bad element is an allowlist ATTEMPT and earns the
  same malformed diagnostic a vector does (so a `'(\"public/articles\")`
  list typo fails loud, not silent)."
  [x]
  (and (sequential? x)
       (seq x)
       (not (every? keyword? x))))

(defn- valid-policy-keyword?
  "Currently the only explicit-policy keyword recognised is the
  whole-app-db opt-in — the keyword shape of the `:payload` opt. New
  policies (e.g. schema-driven projections) would extend this set."
  [x]
  (= x whole-app-db-policy))

(defn validate-policy-opts!
  "Throw a structured error when the caller's `:payload` opt is absent or
  malformed. Called at handler-construction time by the Ring host adapter
  so misconfigured deployments fail at boot, not at first request.

    - `:payload [<kws>]` (non-empty SEQUENTIAL of keywords — a vector, the
      canonical spelling, or a list / lazy-seq) → OK (allowlist)
    - `:payload :rf.ssr.payload/whole-app-db`   → OK (whole-app-db opt-in)
    - `:payload [<… non-keyword element …>]` (non-empty SEQUENTIAL with a
      string/nil/nested entry) → `:rf.error/ssr-malformed-payload-allowlist`
      (rf2-hzttr — a clear allowlist attempt with a bad element, surfaced
      distinctly with the offending entries so a `\"public/articles\"`
      string-typo fails loud at boot instead of silently shipping an empty
      slice)
    - `:payload <other keyword>`  → `:rf.error/ssr-unknown-payload-policy`
      (a typo'd policy keyword, e.g. `:rf.ssr.payload/whole-db`, surfaced
      distinctly so it doesn't silently land in the missing bucket)
    - `:payload` absent / empty `[]` / `'()` / nil / a set / any other
      non-sequential non-keyword → `:rf.error/ssr-missing-payload-policy`
      (fail-closed; the selector is collection-vs-keyword, so a SET is
      rejected — the allowlist is an ORDERED key selection)

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly."
  [{:keys [payload] :as opts}]
  (cond
    (valid-allowlist? payload)
    opts

    (valid-policy-keyword? payload)
    opts

    ;; Caller passed a non-empty sequential coll that ISN'T an all-keyword
    ;; allowlist — a clear allowlist attempt with a malformed element
    ;; (string typo, stray nil, nested coll). Surface the offending entries
    ;; so the fix is obvious; do NOT let it slide into the generic
    ;; missing-policy bucket where a `select-keys` would otherwise ship a
    ;; wrong/empty slice (rf2-hzttr finding 2).
    (malformed-allowlist? payload)
    (error/throw-error!
      :rf.error/ssr-malformed-payload-allowlist
      'rf.ssr/payload-policy
      (str "ssr-handler :payload allowlist must be a "
           "non-empty VECTOR of KEYWORD "
           "top-level app-db keys; got "
           (pr-str payload)
           " — these entries are not keywords: "
           (pr-str (vec (remove keyword? payload)))
           " (a string key like \"public/articles\" "
           "should be the keyword :public/articles).")
      {:recovery :declare-payload-policy
       :extra    {:got         payload
                  :bad-entries (vec (remove keyword? payload))}})

    ;; Caller passed a keyword that isn't the recognised whole-app-db
    ;; opt-in — surface as a distinct error so a typo (e.g.
    ;; `:rf.ssr.payload/whole-db`) doesn't silently land in the
    ;; missing-policy bucket.
    (keyword? payload)
    (error/throw-error!
      :rf.error/ssr-unknown-payload-policy
      'rf.ssr/payload-policy
      (str "ssr-handler :payload keyword must be "
           (pr-str whole-app-db-policy)
           " (or pass a vector allowlist of "
           "top-level app-db keys instead)")
      {:recovery :declare-payload-policy
       :extra    {:got        payload
                  :recognised #{whole-app-db-policy}}})

    :else
    (error/throw-error!
      :rf.error/ssr-missing-payload-policy
      'rf.ssr/payload-policy
      (str "ssr-handler requires an explicit hydration-"
           "payload policy: pass :payload "
           "[<top-level-app-db-keys>] (allowlist, "
           "preferred) OR :payload "
           (pr-str whole-app-db-policy)
           " to opt-in to shipping the whole app-db.")
      {:recovery :declare-payload-policy
       :extra    {:got payload}})))

(defn apply-policy
  "Project `app-db` to the wire slice per the caller's declared `:payload`
  policy. Returns the slice (a map) — the value that lands on the
  `:rf/hydration-payload`'s `:rf/app-db` key.

  Per the contract:

    - `:payload [<kws>]` (allowlist, non-empty SEQUENTIAL of keywords —
      vector / list / lazy-seq) → `select-keys`.
    - `:payload :rf.ssr.payload/whole-app-db` → ships `app-db` verbatim.
    - Absence / malformed → throws (`:rf.error/ssr-missing-payload-policy`,
      `:rf.error/ssr-malformed-payload-allowlist`, or
      `:rf.error/ssr-unknown-payload-policy`) — the fail-closed default.

  This is the runtime arm of the contract; the construction-time arm
  is `validate-policy-opts!` (called by the Ring host adapter so
  misconfigured deployments fail at boot)."
  [app-db {:keys [payload] :as opts}]
  (cond
    (valid-allowlist? payload)
    (select-keys app-db payload)

    (valid-policy-keyword? payload)
    app-db

    :else
    ;; Re-use the construction-time validator's throw so the runtime
    ;; arm and the construction arm produce the same structured error
    ;; shape — tests can assert on one keyword and cover both surfaces.
    (validate-policy-opts! opts)))

;; ---- app-db hydration egress projection -----------------------------------
;;
;; Per EP-0015 §14 + Spec 015 §Projection: SSR/hydration is ALLOWLIST-FIRST
;; (the `apply-policy` `:payload` contract above), and frame classification
;; COMPOSES as defense-in-depth — after allowlisting, the surviving slice is
;; run through the centralized record-level boundary primitive
;; `re-frame.projection/project-egress` under the `:rf.egress/ssr-hydration`
;; profile, seeded at the request frame. So a frame that declares
;; `:sensitive {:app-db [[:session :token]]}` and ships `:session` (via the
;; allowlist OR `:rf.ssr.payload/whole-app-db`) redacts the nested token before
;; it serializes into `:rf/app-db`, instead of shipping the raw secret.
;;
;; project-egress on a kindless map walks it as a tree-shaped VALUE via
;; `elide-wire-value`, which resolves the frame's elision registry from the
;; explicit `:frame` opt. EP-0002 / EP-0015 issue 1 fail-closed: an
;; unresolvable / never-registered frame redacts the WHOLE value to
;; `:rf/redacted` rather than borrow another frame's policy — so a missing
;; frame policy fails CLOSED here too (a server render always carries the live
;; request frame, so the projection runs against real declarations).

(defn project-app-db-egress
  "Run the already-allowlisted `db-slice` through the centralized
  `:rf.egress/ssr-hydration` egress projection seeded at `frame-id`, so a
  frame-classified `:sensitive` / `:large` path inside an allowlisted (or
  whole-app-db) slice redacts / elides as defense-in-depth before it serializes
  into the hydration `:rf/app-db` (EP-0015 §14). Defers to
  `re-frame.projection/project-egress` (over the shared `elide-wire-value`
  walker) — never a family-private elider.

  Fail-closed (EP-0002 / EP-0015 issue 1): an unresolvable / never-registered
  `frame-id` redacts the whole slice to `:rf/redacted` rather than ship it under
  no policy. A server render carries the live request frame, so the projection
  runs against that frame's real declarations; a nil `frame-id` is the same
  fail-closed case (no frame policy ⇒ whole-value redaction)."
  [db-slice frame-id]
  (projection/project-egress
    db-slice
    {:frame             frame-id
     :rf.egress/profile :rf.egress/ssr-hydration}))

;; ---- routing hydration egress projection ----------------------------------
;;
;; EP-0025 §Subsystem matrix (`reg-route` row) + Spec 012 §Route data
;; classification + Spec 015 §SSR: a route declares `:sensitive` / `:large`
;; paths PROJECTION-RELATIVE to its `{:query … :params …}` current-state
;; projection (`(rf/reg-route … {:sensitive [[:query :token]]} …)`). At route
;; activation those declarations are RE-ROOTED under
;; `[:rf.runtime/routing :current …]` and lowered into the per-frame elision
;; registry (`re-frame.routing.classification/apply-route-classification`,
;; `:source :route`) — the SAME `[:rf.runtime/elision …]` slot the
;; `:rf.egress/ssr-hydration` egress walk reads. But the prior
;; `project-runtime-db` shipped the routing `:current` slice via a bare
;; `select-keys`, so a route declaring `:sensitive [[:query :token]]` installed
;; the registry decl yet serialised the raw `:query` / `:params` value into the
;; hydration `:rf/runtime-db` payload (the leak rf2-4xut98 names). This mirrors
;; the IDENTICAL leak class machines fixed under rf2-jm2u63 (snapshots shipped
;; raw before the `:machines/project-ssr-runtime-db` hook), and the app-db slice
;; fixed under rf2-bt9kct (`project-app-db-egress` above).
;;
;; The routing decls are stored as ABSOLUTE runtime-db paths
;; (`[:rf.runtime/routing :current :query :token]`), so the slice is walked
;; through `project-egress` seeded at the offset `:path [:rf.runtime/routing]`:
;; `elide-wire-value`'s `:path` opt seeds the candidate declaration-coordinate
;; set, so a value walked under that offset matches the registry's re-rooted
;; route paths exactly. A `:sensitive` route path redacts to `:rf/redacted`; a
;; `:large` one elides to the size marker; an unclassified route slice rides
;; verbatim (the projection is precise, not a blanket scrub) — only the
;; classified paths are touched.

(defn project-routing-egress
  "Run the already-allowlisted durable routing `slice` (the `{:current …}`
  map, the `durable-routing-keys` projection of `:rf.runtime/routing`) through
  the centralized `:rf.egress/ssr-hydration` egress projection seeded at
  `frame-id`, so a route's projection-relative `:sensitive` / `:large`
  classification — lowered into the per-frame elision registry under
  `[:rf.runtime/routing :current …]` at route activation — redacts / elides the
  classified `:query` / `:params` value before it serialises into the hydration
  `:rf/runtime-db` (Spec 012 §Route data classification, Spec 015 §SSR,
  EP-0025). Mirrors `project-app-db-egress` (app-db slice, rf2-bt9kct) and the
  machines `:machines/project-ssr-runtime-db` hook (snapshot `:data`, rf2-jm2u63).

  The route decls are stored as ABSOLUTE runtime-db paths
  (`[:rf.runtime/routing :current :query :token]`), so the walk is seeded at the
  offset `:path [:rf.runtime/routing]` — `elide-wire-value` seeds the candidate
  declaration-coordinate set with the offset, so the slice value matches the
  registry's re-rooted route paths exactly. Defers to
  `re-frame.projection/project-egress` (over the shared `elide-wire-value`
  walker) — never a family-private elider.

  Fail-closed on a lost frame (rf2-j538f7.15): an EXPLICIT `frame-id` is handed
  STRAIGHT to `project-egress`, which is PATH-precise under a LIVE frame (an
  unclassified route slice rides verbatim — only the declared `:query` /
  `:params` slots redact / elide) and FAILS CLOSED under a destroyed /
  re-registered / unresolvable one (the whole `:current` slice redacts to
  `:rf/redacted`). This is the SAME fail-closed posture as
  `project-app-db-egress`. It closes the teardown-race leak: a `:current` route
  slice captured while its frame was live no longer rides RAW once that frame is
  torn down (or re-registered under the same id) between capture and projection —
  its classification authority is gone, so the slice fails closed rather than
  serialising declared `:query` / `:params` under no policy. The PRIOR guard
  rode the slice VERBATIM whenever the frame was not live, which is exactly the
  leak: a stale explicit target shipped the raw classified route state.

  A nil `frame-id` is the frameless / ambient-nil convenience (the one-arity
  `project-runtime-db` called outside any frame): there is no frame policy to
  lose, so the already-allowlisted durable `:current` slice rides VERBATIM.
  Handing a nil frame to `project-egress` would fail closed and redact the whole
  slice — wrong for genuinely frameless projection. The security-critical
  builders always pass the live request frame explicitly, so the fail-closed arm
  governs every real render; the verbatim arm is only the no-target convenience."
  [slice frame-id]
  (if (some? frame-id)
    ;; Explicit target: project-egress is precise under a live frame and fails
    ;; closed (redacts whole) under a destroyed / re-registered / unresolvable
    ;; one — no longer routed around (the former verbatim else-branch was the
    ;; rf2-j538f7.15 leak).
    (projection/project-egress
      slice
      {:frame             frame-id
       :path              [:rf.runtime/routing]
       :rf.egress/profile :rf.egress/ssr-hydration})
    ;; No explicit target (frameless / ambient-nil convenience): no frame policy
    ;; to lose, so the allowlisted durable slice rides verbatim.
    slice))

;; ---- runtime-db hydration projection --------------------------------------
;;
;; Per Spec 011 §The hydration payload (`:rf/runtime-db`) + §Off-box redaction
;; (Mike ruling #13): the optional `:rf/runtime-db` payload slice carries ONLY
;; the SERIALIZABLE DURABLE runtime-db facts the client needs to reconstitute
;; a coherent frame-state — machine snapshots / spawn registry, the active
;; route slice, elision declarations, and SSR hydration metadata. Transient
;; runtime state MUST NOT ride the wire: server-only request/response
;; accumulators, head snapshots, streaming continuation registries,
;; pending-error buffers, in-flight HTTP handles, host handles, and the
;; client-local scroll-position cache (per [002 §Durable vs transient]).
;;
;; The projection is an ALLOWLIST by subsystem child — symmetric to the
;; `:rf/app-db` policy's fail-closed allowlist posture (a new transient
;; sub-key under a shipped subsystem does NOT silently leak). Routing ships
;; only the durable `:current` slice; `:pending-navigation` (a
;; local-subscribable runtime-db key) stays client-local. Absent / nil
;; runtime-db projects to nil (the client-only / no-server-runtime fallback)
;; so `build-payload` omits the optional key.

(def durable-routing-keys
  "The durable routing-runtime keys that ride the hydration payload. Only
  `:current` (the active route slice) is durable + needed client-side; the
  `:pending-navigation` sibling is a local-subscribable runtime-db key
  (per [002 §Durable vs transient]) and stays off the wire (fail-closed).

  This list must equal the routing-owned
  `re-frame.routing.nav-counters/durable-runtime-db-routing-keys` (the
  `:durable-runtime-db` tier of `routing-state-classification`). It is held
  as a literal here rather than `:require`-d so production SSR builds do NOT
  drag the routing artefact onto the classpath (SSR depends on core only;
  routing is a test-only dep). A cross-artefact conformance test asserts
  the two agree so storage / SSR / docs can never silently drift — see
  `re-frame.ssr.payload-policy-cljs-test`.

  The nav-token / pending-nav COUNTERS are no longer runtime-db keys at all
  — they live in a host-side transient cache per rf2-oosjmh (as do saved
  scroll positions, rf2-1hncp2) — but the fail-closed allowlist would strip
  them regardless."
  [:current])

(defn project-runtime-db
  "Project a frame's runtime-db value to the SERIALIZABLE durable slice that
  rides the `:rf/hydration-payload`'s optional `:rf/runtime-db` key (EP-0001
  rf2-30kzz2, Mike ruling #13). Allowlist-shaped per subsystem child:

    - `:rf.runtime/machines` — shipped whole (snapshots + spawn registry are
      durable serializable facts the client re-materialises actors from);
    - `:rf.runtime/routing`  — only the durable `:current` route slice
      (`:pending-navigation` is local-subscribable client state; the
      scroll / nav-token / pending-nav caches are host-side transient
      state, not runtime-db at all — rf2-1hncp2 / rf2-oosjmh), PROJECTED
      against the frame's route classification (rf2-4xut98 — see below);
    - `:rf.runtime/elision`  — OMITTED from the wire (rf2-ybn1yb — see below);
    - `:rf.runtime/ssr`       — the SSR hydration metadata.

  Returns nil for a nil / empty runtime-db OR when no durable subsystem fact
  is present, so `build-payload` omits the optional `:rf/runtime-db` key
  (the client-only / no-server-runtime fallback). nil-pruned: a subsystem
  whose durable slice is absent contributes no key.

  rf2-jm2u63 — the `:rf.runtime/machines` slice is NOT shipped raw: each
  durable machine snapshot's `:data` is projected per the owning machine's
  `:data-schema` `:sensitive?` / `:large?` classification under the
  `:rf.egress/ssr-hydration` boundary (via the late-bound machines-owned
  `:machines/project-ssr-runtime-db` hook), so a sensitive/large field inside a
  durable snapshot redacts/elides rather than riding the hydration blob raw —
  symmetric with the resource projection's `:ssr/extend-runtime-db-projection`.
  When the machines artefact is absent the hook is unbound and the slice rides
  unchanged (a runtime-db with `:rf.runtime/machines` but no machines artefact
  loaded cannot carry actor snapshots anyway).

  rf2-4xut98 — the `:rf.runtime/routing` slice is NOT shipped raw either: the
  allowlisted durable `:current` route slice is run through
  `project-routing-egress` under the `:rf.egress/ssr-hydration` boundary, so a
  route's projection-relative `:sensitive` / `:large` `:query` / `:params`
  classification (re-rooted under `[:rf.runtime/routing :current …]` into the
  per-frame elision registry at route activation) redacts / elides before it
  rides the hydration blob raw — symmetric with the machines snapshot
  projection and the app-db slice projection. An unclassified route slice rides
  verbatim (the walk is path-precise).

  rf2-ybn1yb — the `:rf.runtime/elision` per-frame DECLARATION REGISTRY is NOT
  shipped at all: it is OMITTED from the hydration wire. Its declaration KEYS are
  themselves app-db / runtime-db PATHS, and a classified path can embed a
  sensitive id (`[:by-id \"user-secret\" :token]`), so shipping the raw registry
  would leak both the sensitive PATH STRUCTURE (what the app classifies, and
  where) and any sensitive id embedded in a key off-box to every visitor — the
  exact leak EP-0025 / Spec 015 §SSR forbids (\"the per-frame registry is itself
  projected before any view of it crosses the hydration wire\"). Omitting is the
  strongest form of that projection. It is also sufficient: SSR and the client
  run the SAME app image, so the client rebuilds its own identical registry from
  its `reg-event` classification effects / `reg-flow` / `reg-route` /
  `reg-machine` as they re-run on mount, and the egress walk consults the LIVE
  per-frame registry, never the wire copy. The classified app-db / route /
  machine slices above were ALREADY projected against this registry server-side,
  so their redaction is baked into the wire value and does not need the
  declarations to re-derive it.

  ## The projection frame is the EXPLICIT carried target (rf2-3fc89f.15)

  The two-arity `[runtime-db frame-id]` is the canonical form: it projects
  every runtime-db slice under the EXPLICIT `frame-id` the caller carries — the
  same target the payload is stamped `:rf/frame-id`, and the same target
  `build-final-payload` / the non-streaming builder already thread to the app-db
  projection. A hydration payload therefore projects BOTH partitions under ONE
  frame, regardless of ambient scope.

  This matters because the runtime-db projectors fail in OPPOSITE directions on
  a missing frame: `project-routing-egress` fails OPEN (no frame ⇒ the
  classified route `:current` slice rides RAW) while the machines projector
  skips `:data` redaction. Resolving the frame AMBIENTLY (the pre-3fc89f.15
  one-arity behaviour) meant a builder called outside `rf/with-frame`, or under
  a DIFFERENT ambient frame, serialized classified route / machine / resource
  state under nil or the WRONG frame's policy — a serialization privacy leak.

  The one-arity `[runtime-db]` is a convenience that projects under the ambient
  scope frame — sound ONLY at a call site already inside the MATCHING
  `rf/with-frame` (ambient == target). The security-critical builders MUST pass
  the explicit target.

  The late-bound `:ssr/extend-runtime-db-projection` hook (resources) takes the
  explicit target as a second parameter (`[runtime-db frame-id]`, rf2-f02diw);
  the two-arity threads the same `frame-id` into it so the extension projects
  under the SAME frame as every other slice — never a borrowed / mismatched
  ambient scope."
  ([runtime-db]
   ;; Convenience: project under the AMBIENT scope frame. Correct only when the
   ;; call site is inside the frame's own `with-frame` (ambient == the target).
   ;; The security-critical builders pass the explicit target (rf2-3fc89f.15).
   (project-runtime-db runtime-db (frame/resolve-current-frame)))
  ([runtime-db frame-id]
   (when (map? runtime-db)
    (let [;; rf2-j538f7.15 — an EXPLICIT `frame-id` that no longer resolves to a
          ;; LIVE frame (destroyed, or re-registered under the same id, between
          ;; the caller's state capture and this projection) has lost the
          ;; per-frame elision registry that classifies every user-bearing
          ;; runtime-db slice. Routing fails closed on its own (`project-routing-
          ;; egress` delegates to `project-egress`, which redacts a dead-frame
          ;; slice whole); but the MACHINES snapshot hook and the RESOURCE
          ;; extension hook classify PRECISELY only under a live frame and would
          ;; otherwise ride the captured, still-classified state VERBATIM under
          ;; absent policy — so when the explicit target is stale we do NOT
          ;; invoke them: the machines slice redacts whole and the resource slice
          ;; is omitted. A nil `frame-id` is the frameless / ambient-nil
          ;; convenience (no frame policy to lose) and stays precise, matching the
          ;; established one-arity contract.
          stale-target?    (and (some? frame-id) (nil? (frame/frame frame-id)))
          project-machines (late-bind/get-fn :machines/project-ssr-runtime-db)
          machines-slice   (when (contains? runtime-db :rf.runtime/machines)
                             (cond
                               stale-target?    :rf/redacted
                               project-machines (project-machines runtime-db frame-id)
                               :else            (:rf.runtime/machines runtime-db)))
          ;; Allowlist the durable routing keys (only `:current` rides; the
          ;; transient `:pending-navigation` / counter siblings stay off the
          ;; wire — fail-closed), THEN project the surviving slice against the
          ;; frame's route classification so a `:sensitive` / `:large` query /
          ;; param redacts / elides (rf2-4xut98) before it serialises raw.
          routing-allowed  (select-keys (:rf.runtime/routing runtime-db) durable-routing-keys)
          routing-slice    (when (seq routing-allowed)
                             (project-routing-egress routing-allowed frame-id))
          slice (cond-> {}
                  (contains? runtime-db :rf.runtime/machines)
                  (assoc :rf.runtime/machines machines-slice)

                  (some? routing-slice)
                  (assoc :rf.runtime/routing routing-slice)

                  ;; rf2-ybn1yb — `:rf.runtime/elision` is intentionally OMITTED
                  ;; (no `(contains? … :rf.runtime/elision)` clause): the per-frame
                  ;; declaration registry must not cross the hydration wire (its
                  ;; keys ARE classified paths that can embed a sensitive id; the
                  ;; client rebuilds its own registry from its registrations on
                  ;; mount — see the docstring's rf2-ybn1yb note).

                  (contains? runtime-db :rf.runtime/ssr)
                  (assoc :rf.runtime/ssr (:rf.runtime/ssr runtime-db)))
          ;; LATE-BOUND cross-subsystem projection extension. A
          ;; cross-feature artefact (e.g. Resources, Spec 016 §SSR and
          ;; hydration) contributes its OWN durable runtime-db slice
          ;; projection — the allowlist-by-subsystem-child hook the spec
          ;; names — without SSR statically `:require`ing it. The hook
          ;; takes the full runtime-db value and returns a `{subsystem-key
          ;; durable-projection}` map merged into the slice; absent hook
          ;; (no extension artefact loaded) contributes nothing, so an
          ;; app without resources sees no behaviour change. Resources is
          ;; the first publisher (rf2-p10npe): it projects ONLY the
          ;; durable `:entries` of `:rf.runtime/resources` (the
          ;; `:tag-index` / `:owner-index` are recomputable-from-entries
          ;; and need not ride the wire — Spec 016 §Restore and replay).
          slice (if-let [extend-fn (late-bind/get-fn :ssr/extend-runtime-db-projection)]
                  ;; The extension hook (resources) takes the EXPLICIT target as a
                  ;; second parameter (rf2-f02diw — finishing the clean break: the
                  ;; hook is re-signatured `[runtime-db frame-id]`), so it projects
                  ;; under the same frame as every other slice, never a borrowed /
                  ;; mismatched ambient one — no `binding` rebind of ambient scope.
                  ;; rf2-j538f7.15 — but under a STALE explicit target its derived
                  ;; `:entries` would ride verbatim under absent policy, so fail
                  ;; closed by OMITTING the resource slice (do not invoke the hook
                  ;; with a dead frame).
                  (if stale-target?
                    slice
                    (merge slice (extend-fn runtime-db frame-id)))
                  slice)]
      (when (seq slice) slice)))))

;; ---- version resolution + payload assembly -------------------------------
;;
;; Single home for the hydration-payload `:rf/version` resolution and the
;; Canonical payload assembly shared by streaming and non-streaming SSR.

(def pattern-protocol-version
  "The hydration pattern-protocol version — the SSR artefact's compiled-in
  constant (v1 = 1, per Spec-Schemas §`:rf/hydration-payload` — \"integer;
  v1 = 1\"). The SSR artefact OWNS this fact: it is compiled into the wire
  code both ends run, so `resolve-version` (server payload assembly) and
  `re-frame.ssr.hydrate/runtime-version-lookup` (the client-side scalar
  `:rf.ssr/check-version` path) read this one source of truth and agree with
  no host wiring. Also the terminal fallback in `resolve-version` so the
  canonical `:rf/version` key is always present (Malli `:int` slot, not
  `:optional`)."
  1)

(defn- coerce-version
  "Coerce a candidate `:rf/version` source value to the canonical INTEGER
  pattern-protocol version (per Spec-Schemas §`:rf/hydration-payload` —
  `:rf/version` is `:int`, explicitly NOT a semver-style string; rf2-g00l2t).

  Returns:
    - the integer itself when `v` is already an int;
    - the parsed integer when `v` is a whole-number-valued string
      (`\"7\"` → 7) — a tolerant coercion for hosts that stamp the
      version as a string of digits;
    - `nil` when `v` is nil (the source is simply absent), OR when `v`
      is a non-integer / non-numeric-string value (a semver `\"1.0.0\"`,
      a float, a keyword) — REJECTED so the caller falls through to the
      next source rather than shipping a schema-violating `:rf/version`.

  A rejection (non-nil, non-coercible) emits a `:rf.ssr/invalid-version`
  warning trace so the drift surfaces in dev/CI rather than silently
  defaulting."
  [v]
  (cond
    (nil? v) nil

    (int? v) v

    (and (string? v) (re-matches #"\d+" v))
    #?(:clj  (Long/parseLong v)
       :cljs (js/parseInt v 10))

    :else
    (do
      ;; `trace/emit!` self-gates on `interop/debug-enabled?` (production
      ;; CLJS bundles DCE the body), so no outer guard is needed here.
      (trace/emit! :warning :rf.ssr/invalid-version
                   {:value    v
                    :reason   (str "Hydration :rf/version must be an integer "
                                   "pattern-protocol version (per Spec-Schemas "
                                   "§:rf/hydration-payload), not "
                                   (pr-str (type v)) " " (pr-str v)
                                   "; rejected — falling back to the next "
                                   "version source.")
                    :recovery :rejected-and-fell-back})
      nil)))

(defn resolve-version
  "Pick the canonical INTEGER `:rf/version` value to ship in the hydration
  payload (per Spec-Schemas §`:rf/hydration-payload` — `:rf/version` is
  `:int`, NOT a semver string). Each candidate source is coerced/validated
  via `coerce-version`: an int is taken verbatim, a whole-number string is
  parsed, and any other value (semver string, float, keyword) is REJECTED
  (with a `:rf.ssr/invalid-version` warning) so resolution falls through to
  the next source rather than shipping a schema-violating value (rf2-g00l2t).

  The resolution order is:

    1. An explicit `:version` opt from the caller (host-supplied stamp;
       wins so test fixtures and apps that ship their own version source
       stay in control) — when it coerces to an integer.
    2. `pattern-protocol-version` (v1 = 1) — the SSR artefact's compiled-in
       constant, the SAME value the client-side `:rf.ssr/check-version` fx
       reads (`re-frame.ssr.hydrate/runtime-version-lookup`), so both wire
       ends pin the same value with no host wiring. The canonical schema
       slot is required (per Spec-Schemas §`:rf/hydration-payload`), so this
       integer is also the terminal fallback — used whenever no explicit
       `:version` supplied a coercible integer (absent, or rejected).

  Both wire ends read the SSR-owned constant, so a host that doesn't pass
  `:version` explicitly still pins the value the client agrees with (the
  inline `(or version 1)` it replaced silently defeated the version-mismatch
  check; rf2-asmj1 S8 / rf2-l8fi6 non-streaming, rf2-via0g streaming)."
  [explicit-version]
  (or (coerce-version explicit-version)
      pattern-protocol-version))

(defn build-payload
  "Assemble the canonical `:rf/hydration-payload` map per Spec 011 §The
  hydration payload — the two always-present keys (`:rf/version`,
  `:rf/app-db`) plus the optional `:rf/render-hash`, `:rf/frame-id`,
  `:rf/runtime-db`, `:rf/schema-digest`, and `:rf/head-hash`.

  **The first arg `wire-frame-id` is the WIRE `:rf/frame-id`, decoupled
  from the projection frame (rf2-lm2yzy).** It is stamped only when
  non-nil, and MUST be a STABLE frame id both server and client agree on
  ahead of time — NEVER a per-request server gensym. Callers project the
  app-db / runtime-db slices under the REAL (per-request) frame BEFORE
  handing them here, then pass the stable wire id (or nil) separately.
  A nil / absent `wire-frame-id` OMITS `:rf/frame-id` — the documented
  no-conflict shape for an anonymous per-request server frame (Spec 011
  §The hydration payload — \"An absent `:rf/frame-id` is no conflict\";
  boot `hydrate!` / the `:rf/hydrate` handler both treat absent as no
  conflict, so the client's explicit `:frame` target stands). Stamping a
  per-request gensym here would guarantee `:rf.error/hydration-frame-id-
  mismatch` on every real page (the client hydrates a stable id, never
  the gensym) — the defect rf2-lm2yzy closed.

  The `:rf/app-db` slice is the already-projected `db-slice` — callers
  run `apply-policy` (the fail-closed allowlist / whole-app-db contract,
  rf2-gtgf9) and hand the result here. `:rf/version` is resolved via
  `resolve-version` (caller's `:version` opt → the SSR-owned
  `pattern-protocol-version` constant). `render-hash` is the BODY-ONLY structural hash
  (rf2-1oxjxk Option B) — see `re-frame.ssr.ring.lifecycle/render-document-
  hash` — and is OPTIONAL: nil omits the key (rf2-2rtt6.91, below).
  Schema-digest is supplied by the caller when their app
  participates in the schema-digest check; nil otherwise.

  rf2-2rtt6.91 — a nil `render-hash` OMITS `:rf/render-hash` rather than
  stamping a nil-valued key. The slot is `{:optional true} :string` in
  [Spec-Schemas §`:rf/hydration-payload`], not `[:maybe :string]`, so a
  present-and-nil key is not a legal spelling of absence; and absence is
  the shape an ADOPTION-TIER root needs. Per Spec 011 §Hydration-mismatch
  detection the hash channel applies only to the HICCUP tier: a compiled
  `re-frame.ui` root, a native UIx root and a Freehand root all verify by
  React-native adoption and carry no hash at either end. Forcing such a
  caller to supply one would make it invent a value over the only tree it
  has — an unresolved `[<component> {props}]` root, whose canonical EDN is
  `[#fn[] {props}]` — which is a constant, not a fingerprint.

  EP-0001 (rf2-30kzz2): the optional `:runtime-db` opt carries the
  already-projected SERIALIZABLE runtime-db slice (callers run
  `project-runtime-db` on the frame's runtime-db value and hand the result
  here). When non-nil it rides the payload as `:rf/runtime-db` so the client
  `:rf/hydrate` handler installs a coherent frame-state (app-db + runtime-db);
  nil omits the optional key (the client-only / no-server-runtime shape).

  rf2-1oxjxk — the optional `:head-hash` opt carries the SEPARATE
  client-reconstructible head-model hash (`re-frame.ssr.ring.lifecycle/
  render-head-hash`), NOT covered by `render-hash`. It rides the payload
  as `:rf/head-hash` when the caller supplied one; omitted (no key) when
  nil — the explicit-`:head`-STRING or degraded-head-resolution shape
  where the server knows the head is not client-reconstructible.

  Shared verbatim by both SSR paths (rf2-8wrzz.4): the non-streaming
  `re-frame.ssr.ring.payload/build-payload` and the streaming
  `re-frame.ssr.streaming/build-final-payload`, which differ only in how
  they source `app-db` + runtime-db before projecting them."
  [wire-frame-id db-slice render-hash {:keys [version schema-digest runtime-db head-hash]}]
  (cond-> {:rf/version (resolve-version version)
           :rf/app-db  db-slice}
    ;; rf2-2rtt6.91 — the hash channel is HICCUP-TIER-ONLY, so a nil
    ;; `render-hash` omits the key instead of stamping a nil-valued one that
    ;; the `:string` schema slot does not admit. See the docstring.
    (some? render-hash)   (assoc :rf/render-hash render-hash)
    ;; rf2-lm2yzy — stamp `:rf/frame-id` ONLY for a stable, ahead-of-time
    ;; agreed wire id; a nil wire id omits it (anonymous per-request server
    ;; frame → the documented no-conflict shape). Never a per-request gensym.
    (some? wire-frame-id) (assoc :rf/frame-id wire-frame-id)
    (some? runtime-db)    (assoc :rf/runtime-db runtime-db)
    schema-digest         (assoc :rf/schema-digest schema-digest)
    head-hash             (assoc :rf/head-hash head-hash)))
