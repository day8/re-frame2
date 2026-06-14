(ns re-frame.routing.nav-counters
  "Host-side per-frame nav-token / pending-nav allocators for re-frame2
  routing, plus the routing-owned durable/transient CLASSIFICATION table.

  Per Spec 012 §Navigation tokens — stale-result suppression and
  §Navigation blocking — pending-nav protocol.

  ## Storage: host-side per-frame TRANSIENT high-water marks (rf2-oosjmh)

  The two monotonic allocators — `:nav-token-counter` (mints
  `[:rf.runtime/routing :current :nav-token]` values) and
  `:pending-nav-counter` (mints `:pending-navigation` `:id`s) — are
  **host-side transient state**, NOT runtime-db state. They live in a
  module-level `defonce` cache keyed by frame-id, mirroring the
  rf2-1hncp2 scroll-position cache and the `re-frame.http-registry`
  in-flight pattern.

  ### Why host-side is a CORRECTNESS fix, not a churn judgment

  A nav-token's only correctness property is the one events.cljc
  documents: *never recycle a value*. A recycled token could collide with
  one still carried by a slow in-flight async continuation (a request
  already on the wire, uncancellable), silently re-validating a stale
  result. The ALLOCATOR (the monotonic counter) therefore must never go
  backward.

  When the counter lived at `[:rf.runtime/routing :nav-token-counter]`,
  an epoch restore — which replaces the runtime-db partition WHOLESALE
  (`frame/replace-runtime-db!`) — rewound the counter to its value at the
  restored epoch. A pre-restore in-flight continuation returning after the
  restore could then carry a token the post-restore timeline has already
  re-allocated → the exact recycle the invariant forbids. Conflating the
  allocator (a high-water mark whose property is *never rewind*) with the
  active token (a durable slice fact that SHOULD restore coherently) in
  one restorable partition is the category error.

  Held host-side the counter is a monotonic high-water mark that survives
  epoch restore untouched, so every fresh allocation strictly exceeds any
  pre-restore in-flight token — token collision is structurally
  impossible. `:pending-nav-counter` is the same anti-recycling-allocator
  class (a recycled pn-id could mis-match a stale continue/cancel).

  ### The pure seam (handlers stay pure)

  Mirroring rf2-1hncp2's rule — *thread the cache as an explicit arg, do
  not reach an ambient global from a handler* — allocation is split:

    - READ via the `:rf.route/nav-counters` cofx, which injects the
      current per-frame high-water snapshot `{:nav-token-counter N
      :pending-nav-counter M}` into the handler's coeffects. The handler
      computes the next id purely from the injected snapshot.
    - WRITE via the `:rf.route/commit-nav-counter` fx, which records the
      new high-water mark into this module's `nav-counters-cache` atom.
      The fx is emitted ONLY on the branch that actually allocates an id
      (a successful nav commit / a blocked navigation), so no id is wasted
      on a no-op navigation.

  The host write is monotone (`max`) so a reordered / replayed commit can
  never lower a counter. A frame's entry is released by `release-frame!`
  on frame destroy (the `:routing/on-frame-destroyed!` teardown hook,
  shared with the scroll cache).

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split: NAV-COUNTERS seam."
  (:require [re-frame.frame :as frame]))

;; ---- host-side per-frame transient cache (rf2-oosjmh) ---------------------

(defonce nav-counters-cache
  ;; frame-id → {:nav-token-counter N :pending-nav-counter M}.
  ;;
  ;; Host-side TRANSIENT high-water marks for the two monotonic routing
  ;; allocators. NOT runtime-db state — held here so an epoch restore (which
  ;; replaces the runtime-db partition wholesale) cannot rewind them, which
  ;; would recycle an authority token (rf2-oosjmh). Keyed by frame-id so
  ;; multi-frame apps keep isolated per-frame counters; the entry is dropped
  ;; on frame destroy via `release-frame!`. Mirrors the rf2-1hncp2
  ;; scroll-position cache + `re-frame.http-registry`'s `in-flight` defonce
  ;; atom — host-owned ephemeral state, not in the reactive db.
  (atom {}))

;; ---- pure snapshot helpers -----------------------------------------------

(defn counter-snapshot
  "Read the per-frame counter snapshot `{:nav-token-counter N
  :pending-nav-counter M}` for `frame-id` from the host
  `nav-counters-cache`, or `{}` when none. The value the
  `:rf.route/nav-counters` cofx threads into the pure nav handlers."
  [frame-id]
  (get @nav-counters-cache frame-id {}))

(defn next-id
  "Pure: given a counter `snapshot` (or nil), a `counter-key`, and an id
  `prefix`, return `[n id]` where `n` is the next monotone counter value
  and `id` is `(str prefix n)`. Does NOT mutate — the handler publishes
  `id` and emits a `:rf.route/commit-nav-counter` fx carrying `n`."
  [snapshot counter-key prefix]
  (let [n (inc (or (get snapshot counter-key) 0))]
    [n (str prefix n)]))

(defn next-nav-token
  "Pure allocator: returns `[n \"nav-N\"]` from a counter `snapshot`.
  `n` is the new `:nav-token-counter` high-water mark."
  [snapshot]
  (next-id snapshot :nav-token-counter "nav-"))

(defn next-pending-nav-id
  "Pure allocator: returns `[n \"pn-N\"]` from a counter `snapshot`.
  `n` is the new `:pending-nav-counter` high-water mark."
  [snapshot]
  (next-id snapshot :pending-nav-counter "pn-"))

;; ---- host-cache wrappers (frame-keyed) -----------------------------------

(defn commit-counter!
  "Record `n` as the high-water mark for `counter-key` under `frame-id` in
  the host `nav-counters-cache`. Monotone — never lowers an existing value
  (a `max` install), so a reordered / replayed commit cannot recycle a
  counter. Returns nil."
  [frame-id counter-key n]
  (swap! nav-counters-cache update-in [frame-id counter-key]
         (fn [cur] (max (or cur 0) n)))
  nil)

(defn release-frame!
  "Drop `frame-id`'s entry from the host `nav-counters-cache`. Invoked on
  frame destroy (the `:routing/on-frame-destroyed!` teardown hook, shared
  with the scroll cache), analogous to the other per-frame transient
  teardown. Idempotent — no-op on an absent frame. Returns nil."
  [frame-id]
  (swap! nav-counters-cache dissoc frame-id)
  nil)

(defn reset-cache!
  "Test-time helper: drop the whole host `nav-counters-cache`. Test
  fixtures call this between runs so a counter value does not leak across
  tests. Returns nil."
  []
  (reset! nav-counters-cache {})
  nil)

;; ---- the :rf.route/nav-counters cofx -------------------------------------

(def nav-counters-cofx-meta
  "Metadata for the `:rf.route/nav-counters` cofx registration. Injects
  the active frame's host-side counter high-water snapshot so the nav
  handlers allocate the next id purely (rf2-oosjmh)."
  {:doc "The active frame's host-side nav-counter high-water snapshot
`{:nav-token-counter N :pending-nav-counter M}`, read from the
`re-frame.routing.nav-counters` host cache and injected under
`:coeffects :rf.route/nav-counters`. The nav handlers read it to mint the
next monotone nav-token / pending-nav id without reaching the host atom
(handlers stay pure); the actual high-water bump rides the
`:rf.route/commit-nav-counter` fx. Per Spec 012 §Navigation tokens."})

(defn nav-counters-cofx
  "Value-returning AMBIENT supplier for the `:rf.route/nav-counters` cofx
  (EP-0017 §2). Reads the in-flight cascade's frame
  (`frame/*current-frame*`, bound by the router during processing) and
  returns the frame's host-side counter snapshot. Pure with respect to the
  fold — it only READS the host cache (the write is the separate
  `:rf.route/commit-nav-counter` fx); never recorded, replay re-runs it. The
  nav entry-point handlers declare `:rf.cofx/requires [:rf.route/nav-counters]`
  and read the snapshot flat. Tests that assert the threading shape
  re-register the supplier (the visible seam)."
  []
  (counter-snapshot frame/*current-frame*))

;; ---- the :rf.route/commit-nav-counter fx ---------------------------------

(def commit-nav-counter-meta
  "Metadata for the `:rf.route/commit-nav-counter` fx registration. The
  WRITE half of the host-side counter seam (rf2-oosjmh): records a new
  monotone high-water mark into the host `nav-counters-cache`. Universal
  platform — the counters are host-side on both client and server (the
  route slice / nav-token exist under SSR too)."
  {:doc "Record a new monotone high-water mark for a routing nav-counter
into the host-side `re-frame.routing.nav-counters` cache. Args:
`{:counter-key :nav-token-counter|:pending-nav-counter :value N}`.
Emitted by the nav handlers on the branch that allocates an id; the
WRITE counterpart to the `:rf.route/nav-counters` cofx read. Per Spec 012
§Navigation tokens."})

(defn commit-nav-counter-handler
  "`:rf.route/commit-nav-counter` fx handler. Registered by the façade so
  a `:reload` re-wires it on a fresh registrar. Writes the new high-water
  mark for `:counter-key` under the cascade-envelope frame into the host
  `nav-counters-cache` (monotone)."
  [{:keys [frame]} {:keys [counter-key value]}]
  (let [;; EP-0002 carried invariant — the fx context carries the cascade
        ;; envelope frame as `:frame`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id (frame/require-frame-stamp!
                   frame :rf.route/commit-nav-counter
                   {:where 'rf.route/commit-nav-counter-handler})]
    (when (and counter-key (number? value))
      (commit-counter! frame-id counter-key value))
    nil))

;; ---- routing durable/transient CLASSIFICATION (rf2-oosjmh) ----------------
;;
;; ONE routing-owned source of truth for how each piece of routing state is
;; classified, so storage / SSR / docs / schemas can never silently drift
;; apart again (the inconsistency rf2-oosjmh reconciled). This is a
;; CLASSIFICATION, not a single shared SSR+epoch allowlist: SSR is a
;; per-key durable-needed allowlist (what the client must reconstitute),
;; while epoch off-box egress is redact-all-unless-trusted (the whole
;; runtime-db partition is `:rf/redacted` off-box by default —
;; `re-frame.epoch.tool_pair/elide-frame-state-slot`, Mike ruling #14).
;; Those policies have different shapes and cannot share one allowlist; the
;; common ground is this classification of the categories. The MOVE (the
;; counters + scroll going host-side) is the actual structural fix — it
;; makes both rewind and off-box leakage structurally impossible; this
;; table documents the result and guards future routing state.

(def routing-state-classification
  "The canonical classification of every piece of per-frame routing
  state, by tier. SSR / docs / Spec-Schemas consume this so the
  durable/transient split has ONE home (rf2-oosjmh).

  Tiers:

    :durable-runtime-db        — serializable facts NEEDED to reconstitute
                                 a coherent frame-state on restore /
                                 SSR-hydration; rides the SSR
                                 `:rf/runtime-db` payload.
    :local-subscribable-runtime-db
                               — runtime-db state that MUST stay
                                 subscribable (a real reg-runtime-sub) and
                                 restore coherently in LOCAL replay, but is
                                 NOT needed on the SSR wire (SSR-stripped,
                                 fail-closed). Off-box epoch egress is
                                 handled by the redact-whole-runtime-db
                                 default, not a routing-specific strip.
    :host-transient            — host-derived / operational state held in a
                                 module-level host cache, NEVER in
                                 runtime-db (so it can neither rewind on
                                 restore nor ride any egress wire)."
  {:durable-runtime-db
   {:keys [:current]
    :doc  "The active route slice {:route-id :params :query :fragment :transition
:error :nav-token} at [:rf.runtime/routing :current]. Subscribable via
:rf/route; restored coherently (the active :nav-token rides WITH the
slice)."}

   :local-subscribable-runtime-db
   {:keys [:pending-navigation]
    :doc  "The pending-navigation slot at [:rf.runtime/routing
:pending-navigation], subscribable via :rf/pending-navigation. SSR-stripped
(fail-closed allowlist); kept in local replay."}

   :host-transient
   {:keys [:scroll-positions :nav-token-counter :pending-nav-counter]
    :doc  "Saved scroll positions (re-frame.routing.scroll, rf2-1hncp2) and
the two monotonic allocator high-water marks (re-frame.routing.nav-counters,
rf2-oosjmh). Held in module-level host caches keyed by frame-id — NOT
runtime-db, so they neither rewind on epoch restore nor ride the
SSR / epoch / trace egress wire."}})

(def durable-runtime-db-routing-keys
  "The routing runtime-db keys that ride the SSR hydration payload — the
  `:durable-runtime-db` tier of `routing-state-classification`. Consumed by
  `re-frame.ssr.payload-policy/durable-routing-keys` so storage and the SSR
  payload policy share ONE source of truth (rf2-oosjmh)."
  (get-in routing-state-classification [:durable-runtime-db :keys]))
