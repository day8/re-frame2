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
  rf2-1hncp2 scroll-position cache and the `re-frame.http.registry`
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

  ### The pure seam (handlers stay pure) — RECORDABLE allocation (rf2-vcop6y)

  Mirroring rf2-1hncp2's rule — *thread the cache as an explicit arg, do
  not reach an ambient global from a handler* — allocation is split into a
  READ-via-cofx / WRITE-via-fx seam. The READ half is a pair of
  **recordable, generator-backed** coeffects (EP-0017 §5) — one per
  allocator (rf2-oosjmh: two distinct allocators ⇒ two distinct facts):

    - `:rf.route/nav-allocation`         → `{:token \"nav-N\" :counter N}`
    - `:rf.route/pending-nav-allocation` → `{:id    \"pn-N\"  :counter N}`

  Each carries BOTH the minted id AND the allocator high-water position
  (`:counter`). The generator runs at processing-start (router `:live`
  policy), mints the next id from the host snapshot, and the cofx machinery
  WRITES the produced allocation back into the in-flight `:rf.cofx` causal
  record — so the epoch captures the allocation and **replay re-presents
  the SAME id verbatim** (no generator re-run; strict replay FAILS if the
  recorded allocation is missing — `:rf.error/missing-required-cofx`). This
  is the replay-determinism fix: pre-rf2-vcop6y the allocation rode an
  AMBIENT counter-snapshot cofx that was never recorded, so replay re-minted
  DIFFERENT ids and recorded events referencing the originals mismatched.

  The handler reads the recordable allocation flat under its id, writes ONLY
  the supplied/generated id into runtime-db, and emits the
  `:rf.route/commit-nav-counter` fx carrying the allocation's `:counter`:

    - WRITE via the `:rf.route/commit-nav-counter` fx, which records the
      new high-water mark into this module's `nav-counters-cache` atom with
      a `max` install — so a restore/replay can re-establish the host
      high-water from the recorded `:counter` and can never REWIND the
      allocator (the never-recycle invariant). Emitted on the branch that
      actually publishes an id (a successful nav commit / a blocked
      navigation).

  The host write is monotone (`max`) so a reordered / replayed commit can
  never lower a counter. A frame's entry is released by `release-frame!`
  on frame destroy (the `:routing/on-frame-destroyed!` teardown hook,
  shared with the scroll cache).

  ### Shape: generator-backed recordable (not provided-at-construction)

  rf2-vcop6y step 7 prefers a routing-PROVIDED fact stamped onto the
  navigation dispatch envelope at construction (slice-A). That shape fits a
  fact with a single causal originator and a value independent of the
  handler (the `:rf/time-ms` precedent). Navigation has neither: it arrives
  through many internal re-dispatch paths (popstate → `handle-url-change`,
  link-click → `:rf.route/url-requested` → `:rf.route/transitioned`, the resume
  chain `:rf.route/continue` → `:rf.route/url-requested`), each a fresh causal
  token whose `:rf.cofx` is NOT inherited; and a pending-nav allocation's
  very existence depends on the `:can-leave` guard result computed INSIDE
  the handler against the live frame. A provided fact would therefore have
  to be minted-and-stamped at every entry point, before the branch is known.
  The generator-backed recordable shape resolves the hole uniformly via
  `:rf.cofx/requires` on every entry point with no per-site stamping —
  rf2-vcop6y step 7 blesses it as the slice-B consumer alternative. The
  generators run EAGERLY for every declared fact, so a two-way nav handler
  (block vs commit) that declares both allocations advances both monotone
  counters even on the branch it does not publish; that is harmless — the
  counters' only invariant is never-recycle, gaps are a documented
  non-concern (the unbounded f64 / `long` range), and the not-published
  allocation is recorded + replay-stable like any declared-but-unused fact.

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
  ;; scroll-position cache + `re-frame.http.registry`'s `in-flight` defonce
  ;; atom — host-owned ephemeral state, not in the reactive db.
  (atom {}))

;; ---- pure snapshot helpers -----------------------------------------------

(defn counter-snapshot
  "Read the per-frame counter snapshot `{:nav-token-counter N
  :pending-nav-counter M}` for `frame-id` from the host
  `nav-counters-cache`, or `{}` when none. The value the allocation-cofx
  generators (`nav-allocation-cofx` / `pending-nav-allocation-cofx`) mint
  the next id from."
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

;; ---- the recordable allocation cofx (rf2-vcop6y) --------------------------
;;
;; TWO recordable, generator-backed allocation coeffects — one per allocator
;; (rf2-oosjmh: two distinct allocators ⇒ two distinct facts). Each generator
;; reads the in-flight cascade's frame host snapshot (the same
;; `frame/*current-frame*` the retired ambient `:rf.route/nav-counters` cofx
;; read) and mints the next id, returning the allocation map carrying BOTH the
;; id AND the allocator high-water `:counter`. Being RECORDABLE
;; (`:recordable? true`, NOT `:provided?`), the cofx machinery writes the
;; produced allocation back into the in-flight `:rf.cofx` causal record (EP-0017
;; §5, router `:live` policy) — so the epoch captures it and replay re-presents
;; the SAME id verbatim. The retired ambient cofx was never recorded; replay
;; re-minted DIFFERENT ids (the rf2-vcop6y hole). Strict replay FAILS on a
;; missing recorded allocation (`:rf.error/missing-required-cofx`).

(def nav-allocation-cofx-meta
  "Metadata for the `:rf.route/nav-allocation` cofx registration: a
  RECORDABLE generator-backed allocation of a fresh nav-token (rf2-vcop6y).

  Carries a `:schema` (rf2-ps05ug, EP-0017 §5): the generator produces
  `{:token \"nav-N\" :counter N}` and the commit handler folds `:token`
  into the durable route slice + rides `:counter` on the host high-water
  bump fx, so a supplied/replayed value MUST be shape-validated. Without
  the schema, `validate-recordable-value!` is a no-op and a malformed-but-
  present replayed fact (e.g. `{:token nil :counter \"bad\"}`) is delivered
  as trusted — a nil/wrong nav-token then corrupts stale-suppression and
  the host counter bump silently no-ops. The schema makes such a fact fail
  loudly with `:rf.error/cofx-value-invalid` BEFORE it reaches the handler."
  {:recordable? true
   :schema [:map [:token :string] [:counter :int]]
   :doc "A fresh nav-token allocation `{:token \"nav-N\" :counter N}`,
minted at processing-start from the active frame's host nav-token
high-water mark and RECORDED onto the causal token (EP-0017 recordable
coeffect). Delivered FLAT under the `:rf.route/nav-allocation` key in the
coeffects map (EP-0017 §5) to a nav commit handler that declares
`:rf.cofx/requires [:rf.route/nav-allocation]`.
The handler writes only `:token` into the route slice and rides `:counter`
on the `:rf.route/commit-nav-counter` fx (host high-water `max` bump).
Recorded so record+replay re-presents the same nav-token verbatim
(replay-determinism — rf2-vcop6y). Per Spec 012 §Navigation tokens."})

(defn nav-allocation-cofx
  "Value-returning generator for the RECORDABLE `:rf.route/nav-allocation`
  cofx (EP-0017 §5, rf2-vcop6y). Reads the in-flight cascade's frame
  (`frame/*current-frame*`, bound by the router during processing), mints
  the next nav-token from the host high-water snapshot, and returns
  `{:token \"nav-N\" :counter N}`. Runs at processing-start under the
  router `:live` policy; the produced allocation is written back into the
  causal `:rf.cofx` record so replay re-presents it verbatim (no re-mint).
  Strict mode (replay) does not run it — an absent recorded allocation is
  `:rf.error/missing-required-cofx`."
  []
  (let [[n token] (next-nav-token (counter-snapshot frame/*current-frame*))]
    {:token token :counter n}))

(def pending-nav-allocation-cofx-meta
  "Metadata for the `:rf.route/pending-nav-allocation` cofx registration: a
  RECORDABLE generator-backed allocation of a fresh pending-nav id
  (rf2-vcop6y). A DISTINCT allocator from `:rf.route/nav-allocation`
  (rf2-oosjmh).

  Carries a `:schema` (rf2-ps05ug, EP-0017 §5): the generator produces
  `{:id \"pn-N\" :counter N}` and the can-leave block handler folds `:id`
  into the durable pending-navigation slot + rides `:counter` on the host
  high-water bump fx, so a supplied/replayed value MUST be shape-validated.
  Without the schema, a malformed-but-present replayed fact (e.g.
  `{:id nil :counter nil}`) is delivered as trusted — a nil pending-nav id
  then makes a recorded `[:rf.route/continue \"pn-N\"]` no-op (the
  navigation stays blocked forever). The schema makes such a fact fail
  loudly with `:rf.error/cofx-value-invalid` BEFORE it reaches the handler."
  {:recordable? true
   :schema [:map [:id :string] [:counter :int]]
   :doc "A fresh pending-navigation id allocation `{:id \"pn-N\" :counter N}`,
minted at processing-start from the active frame's host pending-nav
high-water mark and RECORDED onto the causal token (EP-0017 recordable
coeffect). Delivered FLAT under the `:rf.route/pending-nav-allocation` key
in the coeffects map (EP-0017 §5) to a nav entry handler that declares
`:rf.cofx/requires [:rf.route/pending-nav-allocation]`. On a `:can-leave` block the handler
writes only `:id` into the pending-navigation slot and rides `:counter` on
the `:rf.route/commit-nav-counter` fx. Recorded so a recorded
`[:rf.route/continue \"pn-N\"]` re-matches under replay (replay-determinism
— rf2-vcop6y). Per Spec 012 §Navigation blocking — pending-nav protocol."})

(defn pending-nav-allocation-cofx
  "Value-returning generator for the RECORDABLE
  `:rf.route/pending-nav-allocation` cofx (EP-0017 §5, rf2-vcop6y). Reads
  the in-flight cascade's frame (`frame/*current-frame*`), mints the next
  pending-nav id from the host high-water snapshot, and returns
  `{:id \"pn-N\" :counter N}`. Recorded + replay-stable like
  `nav-allocation-cofx`; a distinct allocator (rf2-oosjmh)."
  []
  (let [[n id] (next-pending-nav-id (counter-snapshot frame/*current-frame*))]
    {:id id :counter n}))

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
Emitted by the nav handlers on the branch that publishes an id, carrying the
recordable allocation's `:counter` (the WRITE counterpart to the recordable
`:rf.route/nav-allocation` / `:rf.route/pending-nav-allocation` cofx read).
The `max` install means a restore/replay re-establishes the host high-water
from the recorded `:counter` and can never rewind the allocator. Per Spec
012 §Navigation tokens."})

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
