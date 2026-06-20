(ns re-frame.resources.subs
  "The passive resource subscriptions — the read API over the resource
  cache. Per Spec 016 §Public API §Subscriptions (passive) and §Status
  semantics.

  Subscriptions are PURE passive reads (Spec 016: \"No v1 subscription
  fetches\"): a view reads a resource through `[:rf.resource/state …]` (or
  a narrower projection like `[:rf.resource/data …]`); route entry,
  events, and machines CAUSE the fetch. A resource sub resolves its scope
  per Spec 016 §Subscription-side scope resolution (payload `:scope`, or a
  sub-resolvable spec policy) and raises
  `:rf.error/resource-sub-unresolved-scope` rather than reading global or
  returning a silent `:idle` — the read-side counterpart of the write-side
  fail-closed gate.

  The framework resource subs read the frame's RUNTIME-DB projection
  (`reg-runtime-sub`) — the durable cache lives at
  `[:rf.runtime/resources :entries <key-id>]` in runtime-db, keyed by the
  CEDN-1 byte-identity `key-id` (the scoped key is carried inside each
  entry under `:resource/key`); the sub resolves it via `(state/entry-path k)`.
  The derived booleans (`:stale?` / `:loading?` / `:fetching?` /
  `:has-data?`) are PUBLIC DERIVED SUB VALUES computed here from the
  durable entry facts, NOT stored on the entry (Spec 016 §Status
  semantics).

  The sub registrations are real and the projections read the live entry:
  `resolve-scoped-key` canonicalizes the scope + params and applies the
  sub-side scope-resolution precedence (payload `:scope`, or a sub-
  resolvable spec policy), raising `:rf.error/resource-sub-unresolved-scope`
  fail-closed (NEVER a silent global read or `:idle`).

  ## Frame-state signal + `{:from-db …}` reactive re-keying (EP-0016 D3
  ## slice 3 / rf2-616xa6)

  The resource subs are registered with `reg-frame-state-sub` (NOT
  `reg-runtime-sub`): their single signal source is the WHOLE frame-state
  value `{:rf.db/app … :rf.db/runtime …}`, so the body re-runs on a change
  to EITHER partition. The cache entries live in runtime-db; a `{:from-db
  <id>}` scope resolver derives the scoped key from APP-DB. Reading both
  from one coherent frame-state snapshot means a resource sub whose scope
  is `{:from-db :session}` RE-KEYS reactively when the resolver's declared
  app-db inputs change mid-session (account switch / impersonation / login):
  the body re-resolves the scoped key, the new key's entry is read (`:idle`
  until a route/event ensures it under the new scope — the view observes the
  new key's loading/idle state, NOT the stale entry), and output `=`
  memoisation keeps the sub quiet when neither the resolved key nor the read
  entry changed. Owner-lease handoff is the existing causal machinery: the
  route/event that ensures under the new scope attaches the new owner, and
  route leave / `clear-scope` releases the old — the sub is a passive
  reader throughout (Spec 016 §Views stay passive)."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.subs :as subs]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- frame-state partition extraction -------------------------------------

(defn- runtime-of
  "Extract the runtime-db partition from the frame-state value the
  `:frame-state` sub body receives (`{:rf.db/app … :rf.db/runtime …}`).
  Tolerates a bare runtime-db map (the direct-dispatch / `compute-sub` test
  path that supplies a bare partition) by passing it through when it carries
  no partition keys."
  [frame-state]
  (if (or (contains? frame-state :rf.db/runtime)
          (contains? frame-state :rf.db/app))
    (get frame-state :rf.db/runtime)
    frame-state))

(defn- app-of
  "Extract the app-db partition from the frame-state value (or nil for a
  bare runtime-db map). The db a `{:from-db <id>}` sub scope resolves
  against (EP-0016 D3 slice 3)."
  [frame-state]
  (get frame-state :rf.db/app))

;; ---- scoped-key resolution -----------------------------------------------

(defn resolve-scoped-key
  "Resolve the `{:resource :scope :params}` sub payload to the scoped
  resource key `[canonical-scope resource-id canonical-params]` the cache
  entry is stored under — canonicalizing the scope + params (Spec 016
  §Canonicalization rule) and applying the sub-side scope-resolution
  precedence (Spec 016 §Subscription-side scope resolution).

  PURE: a sub cannot run a `(route, ctx)` resolver. Resolves scope from the
  payload `:scope` or a sub-resolvable spec policy (`:rf.scope/global` /
  `{:from-db <id>}` named-resolver reference / pure-data / fn-of-nothing)
  and raises `:rf.error/resource-sub-unresolved-scope` otherwise. Throws
  `:rf.error/resource-not-registered` when no resource is registered under
  `:resource`.

  `db` is the frame APP-DB value the sub layer reads from the frame-state
  signal (EP-0016 D3 slice 3): a `{:from-db <id>}` payload-scope or spec
  policy resolves against it at use time, so the sub re-keys reactively
  when the resolver's declared app-db inputs change mid-session
  (rf2-616xa6). A reference that resolves nil raises
  `:rf.error/resource-sub-unresolved-scope` (the \"scope unresolved\"
  diagnostic) — never a silent global / wrong-entry read. Every caller
  supplies the frame `db` explicitly (rf2-bwwk6l): a caller that resolves no
  `{:from-db …}` scope passes `{}`."
  [{:keys [resource] :as payload} db]
  (let [spec    (registry/require-resource-spec! resource 'rf.resource/subscribe)
        scope   (registry/resolve-scope-for-sub
                  resource spec (:scope payload) 'rf.resource/subscribe db)
        ;; rf2-hgy5kf — thread `:params` presence (absent vs explicit nil) so
        ;; the sub re-keys to the SAME identity the ensure produced (an absent
        ;; slot defaults to `{}` at the boundary; explicit nil reaches the
        ;; schema unchanged).
        cparams (registry/validate+canonicalize-params
                  resource spec (state/params-present? payload) 'rf.resource/subscribe)]
    ;; rf2-rplgkw: scope + cparams are ALREADY canonical (resolve-scope-for-sub
    ;; → canonicalize-scope; validate+canonicalize-params), so use the trusted
    ;; constructor — a resource sub re-runs this on every frame-state change.
    (state/scoped-resource-key* scope resource cparams)))

;; ---- dev-mode scope-mismatch warning (Spec 016 §Subscription-side scope
;; ---- resolution — likely-mismatch dev warning) ----------------------------
;;
;; The session-scoped (`:rf.scope/from-caller`) pattern's sharpest footgun
;; (rf2-rsmiru, from the EP-0003 dogfooding audit): a route ensures a
;; resource under scope X (e.g. `[:rf.scope/session {:user u}]`), but a view
;; subscribes with a DIFFERENT / absent scope. The sub resolves a DIFFERENT
;; cache entry — one with no owner ever attached — and reads `:idle` FOREVER:
;; a silent permanent skeleton with no error. Fail-closed is correct (the
;; sub never reads a wrong-principal entry); the ergonomics are the issue.
;;
;; This is the READ/SUB-side complement to the existing Xray write-side
;; scope-mismatch lint (`day8.re-frame2-xray.panels.resources-helpers/
;; scope-mismatch-lint`): an in-framework, dev-only heuristic that fires at
;; the moment of the mismatched read so the footgun surfaces in the trace
;; stream without the developer having to open Xray.
;;
;; HEURISTIC (the bead's, verbatim): a `:rf.scope/from-caller` resource is
;; SUBSCRIBED at a scope key with ZERO active owners, while a DIFFERENT scope
;; key for the SAME resource id IS active (has ≥1 active owner). The
;; from-caller policy is the only one this can happen under — `:rf.scope/
;; global` and pure-data / fn-of-nothing resolvers resolve the SAME concrete
;; scope sub-side and ensure-side, so a sub can never land on a different key
;; than the ensure did. (A genuine empty cache — nothing active anywhere —
;; does NOT fire: there is no "right" entry being missed, only an un-ensured
;; resource, which the empty `:idle` projection already documents.)
;;
;; DEV-ONLY + production-elided: gated by `interop/debug-enabled?` and
;; emitted through `trace/emit!` (which is itself behind the same gate), so
;; Closure DCE strips the whole path at `:advanced` + `goog.DEBUG=false`,
;; exactly like the rest of the framework's dev warnings (e.g.
;; `:rf.warning/large-value-unschema'd`). One-shot idempotent per distinct
;; `[resource-id sub-scope active-scope]` triple so a reactive sub that
;; re-runs every render warns ONCE per genuine mismatch, never floods.

(defonce ^:private
  ^{:doc "One-shot dedupe set of `[resource-id sub-scope active-scope]`
   triples already warned about, so a reactively re-running sub emits the
   scope-mismatch dev warning ONCE per genuine mismatch rather than on
   every reaction. Host-side transient dev state (NOT runtime-db); cleared
   per-test by the resources reset hook (`re-frame.resources.test-support`)."}
  warned-scope-mismatch
  (atom #{}))

(defn reset-scope-mismatch-warnings!
  "Drop every recorded scope-mismatch warning dedupe key (test isolation).
  Published as a reset hook so the shared CLJS reset-runtime fixture clears
  it per test — it is host-side transient dev state, not runtime-db, so the
  runtime/frames reset does not touch it. Returns nil."
  []
  (reset! warned-scope-mismatch #{})
  nil)

(defn- entry-active?
  "True iff a durable cache `entry` currently holds ≥1 active owner (a
  liveness lease). An entry with an empty / absent `:active-owners` is NOT
  active — it is a bare cache key no route / event / machine is keeping
  alive, and so a sub landing on it reads `:idle`/stale forever (Spec 016
  §Active owners and causes)."
  [entry]
  (boolean (seq (:active-owners entry))))

(defn- active-mismatch-scope
  "The scope of a DIFFERENT active entry for `resource-id` — the heuristic's
  evidence that the subscribed `sub-key` is a LIKELY scope mismatch. Returns
  the first such scope, or nil when no other scope for this resource is active.

  rf2-tluunj — visits ONLY entries that currently hold an active owner, via the
  `owner-index` (`{<owner> #{<key-id> …}}`), instead of scanning the WHOLE
  `:entries` map on every `:rf.resource/state` sub recompute. The heuristic
  only ever fires on an ACTIVE entry (`entry-active?`), and an entry is active
  IFF it is a member of some owner-index bucket, so the owner-index members are
  exactly the candidate set — every entry the old full scan would have passed
  the `entry-active?` test for, and no more. Resolving each member to its entry
  via `entry-path-by-id` keeps the rest of the predicate identical. Pure."
  [resources-subtree sub-key]
  (let [[sub-scope rid _params] sub-key
        entries     (:entries resources-subtree)
        owner-index (:owner-index resources-subtree)
        ;; the byte key-ids of every entry holding ≥1 active owner — the union
        ;; of the owner-index buckets (deduped). A non-from-caller-heavy cache
        ;; with few active leases visits a handful of keys, not the whole cache.
        active-ids  (reduce-kv (fn [acc _owner ids] (into acc ids)) #{} owner-index)]
    (some (fn [k-id]
            ;; rf2-9e0tyq — the index member IS the byte key-id; read the
            ;; scope/rid off the entry's stored `:resource/key` VECTOR.
            (let [entry        (get entries k-id)
                  [scope r _p] (:resource/key entry)]
              (when (and (= r rid)
                         (not= scope sub-scope)
                         (entry-active? entry))
                scope)))
          active-ids)))

(defn maybe-warn-scope-mismatch!
  "Emit the dev-only likely-scope-mismatch warning (rf2-rsmiru) when a
  `:rf.scope/from-caller` resource is subscribed at a scope key with ZERO
  active owners while a DIFFERENT scope key for the SAME resource id IS
  active — the read-side complement of the Xray write-side scope-mismatch
  lint, and the sub-side tripwire for the silent-permanent-skeleton footgun
  the fail-closed scope rules cannot catch (the sub DID resolve a scope; it
  resolved the WRONG one).

  DEV-ONLY: the whole body is behind `interop/debug-enabled?` so Closure DCE
  elides it in production (`:advanced` + `goog.DEBUG=false`), and the emit
  rides `trace/emit!` (same gate). One-shot idempotent per distinct
  `[resource-id sub-scope active-scope]` so a reactive re-run warns once.

  Only `:rf.scope/from-caller` resources are checked: every other policy
  (`:rf.scope/global`, a pure-data / fn-of-nothing resolver) resolves the
  SAME concrete scope sub-side and ensure-side, so a sub cannot land on a
  different key than the ensure did. Returns nil."
  [runtime-db payload sub-key sub-entry]
  (when interop/debug-enabled?
    (let [resource-id (:resource payload)
          spec        (registry/resource-meta resource-id)]
      ;; only the from-caller footgun — see docstring
      (when (and (= :rf.scope/from-caller (:scope spec))
                 ;; the subscribed entry has no active owner (or no entry) …
                 (not (entry-active? sub-entry)))
        ;; rf2-tluunj — pass the whole resources subtree so the mismatch scan
        ;; can use the `:owner-index` (visit only active entries) rather than
        ;; scanning the entire `:entries` map on every reactive sub recompute.
        (let [resources-subtree (get runtime-db state/resources-key)
              [sub-scope]   sub-key
              active-scope  (active-mismatch-scope resources-subtree sub-key)]
          ;; … while a DIFFERENT scope for the same resource IS active.
          (when (some? active-scope)
            (let [dedupe-key [resource-id sub-scope active-scope]]
              (when-not (contains? @warned-scope-mismatch dedupe-key)
                (swap! warned-scope-mismatch conj dedupe-key)
                (trace/emit! :warning :rf.warning/resource-sub-scope-mismatch
                             {:resource-id  resource-id
                              :sub-scope    sub-scope
                              :active-scope active-scope
                              :recovery     :fix-scope
                              :hint         (str "subscription to resource "
                                                 resource-id " resolved scope "
                                                 (pr-str sub-scope)
                                                 " which has no active owner, while a "
                                                 "DIFFERENT scope "
                                                 (pr-str active-scope)
                                                 " for the same resource IS active — "
                                                 "this sub will read :idle forever (a "
                                                 "silent permanent skeleton). Pass the "
                                                 "SAME :scope the owning route/event "
                                                 "ensured under on the subscription "
                                                 "payload. Per Spec 016 "
                                                 "§Subscription-side scope "
                                                 "resolution.")}))))))))
  nil)

(defn- entry-for
  "Look up the durable cache entry for a sub payload (resolving + validating
  its scoped key), or nil when no entry exists for that key. On the dev
  build also fires the likely-scope-mismatch warning (rf2-rsmiru,
  `maybe-warn-scope-mismatch!`) — production-elided.

  `runtime-db` is the cache partition the entry is read from; `app-db` is
  the app-db partition a `{:from-db <id>}` sub scope resolves against
  (EP-0016 D3 slice 3) — both come from the one coherent frame-state
  snapshot the `:frame-state` sub body receives."
  [runtime-db app-db payload]
  (let [k (resolve-scoped-key payload app-db)
        e (get-in runtime-db (state/entry-path k))]
    (maybe-warn-scope-mismatch! runtime-db payload k e)
    e))

;; ---- derived freshness (Spec 016 §Status semantics) -----------------------
;;
;; The live clock the `:stale?` derivation compares `:stale-at` against is the
;; core WALL-clock `re-frame.interop/epoch-now-ms` (rf2-366u0g) — byte-identical
;; to the private `now-ms` this ns used to define (`System/currentTimeMillis`
;; on the JVM, `js/Date.now` on CLJS), and the canonical EP-0010 §Time
;; wall-clock surface. NOT the perf clock `interop/now-ms` (`performance.now()`
;; on CLJS, origin-relative), which is incomparable with the `js/Date`-based
;; `:stale-at` timestamps. Freshness is computed from durable timestamps, not
;; from trusting a timer fired on time (Spec 016 §Stale and GC scheduling).

(defn- stale?
  "Derived: an entry is stale iff it has been explicitly invalidated
  (`:invalidated-at` set) OR its `:stale-after-ms` window has elapsed
  (`:stale-at` set and `now >= :stale-at`). Freshness is ORTHOGONAL to load
  status — a `:loaded` entry may be stale; a `:fetching` entry may be
  refreshing stale data. Per Spec 016 §Status semantics. A computed value,
  never a stored fact. Delegates to the single shared `state/entry-stale?`
  derivation (the same one the SSR projection + the stale-timer re-check
  use) so freshness never drifts between readers; the live clock is the
  canonical wall-clock `interop/epoch-now-ms`."
  [entry]
  (state/entry-stale? entry (interop/epoch-now-ms)))

;; ---- the projections (public derived sub values) -------------------------

(defn- previous-projection
  "Project the `:keep-previous?` previous-data view (Spec 016 §Paginated
  and previous data) from a `:loading` entry that carries a
  `:previous-key` projection pointer: `{:previous? true :previous-key …
  :previous-data …}`. The previous key's data is read from ITS entry — it
  is NEVER inserted into this entry and NEVER provides this key's tags.
  Returns `{:previous? false}` when there is nothing to project (no
  pointer, or this key already has its own data)."
  [runtime-db e]
  (let [prev-key (:previous-key e)]
    (if (and prev-key (nil? (:data e)))
      (let [prev-entry (get-in runtime-db (state/entry-path prev-key))]
        {:previous?     true
         :previous-key  prev-key
         :previous-data (:data prev-entry)})
      {:previous? false})))

(defn state-sub-fn
  "Project the public `:rf.resource/state` view-model from a durable
  entry: the stored facts plus the DERIVED booleans (`:loading?` /
  `:fetching?` / `:stale?` / `:has-data?`) computed here, never stored,
  plus the `:keep-previous?` previous-data projection (Spec 016 §Paginated
  and previous data). Per Spec 016 §Status semantics. Empty-state shape
  when no entry."
  [frame-state [_id payload]]
  (let [rt  (runtime-of frame-state)
        app (app-of frame-state)
        e   (entry-for rt app payload)]
    (if (nil? e)
      ;; No entry yet — the documented idle empty-state projection.
      {:status :idle :data nil :error nil :refresh-error nil
       :loading? false :fetching? false :stale? false :has-data? false
       :previous? false}
      (merge
        {:status        (:status e)
         :data          (:data e)
         :error         (:error e)
         :refresh-error (:refresh-error e)
         ;; Derived (Spec 016 §Status semantics): :loading? = first load
         ;; with no usable data; :fetching? = refresh in flight; :has-data? =
         ;; usable data present; :stale? = freshness vs :stale-at (runtime
         ;; slice computes the live clock comparison — pinned shape here).
         :loading?      (= :loading (:status e))
         :fetching?     (= :fetching (:status e))
         :stale?        (stale? e)
         ;; `:has-data?` via the shared `state/has-data?` derivation, NOT the
         ;; scalar `(some? :data)`: an infinite feed's `:data` is the page
         ;; VECTOR seeded EMPTY (`[]`), which `some?` would wrongly read as
         ;; usable data (rf2-3fynns). `state/has-data?` branches on the
         ;; feed shape (`(seq data)`), so the scalar `:state` sub and
         ;; `:infinite-state` never disagree.
         :has-data?     (state/has-data? e)}
        ;; `:keep-previous?` projection — previous-key data shown while this
        ;; key first-loads, WITHOUT polluting this entry's cache / tags.
        (previous-projection rt e)))))

(defn data-sub-fn
  "Project `:rf.resource/data` — the entry's last-known-good `:data` (or
  nil). Per Spec 016 §Subscriptions."
  [frame-state [_id payload]]
  (:data (entry-for (runtime-of frame-state) (app-of frame-state) payload)))

(defn status-sub-fn
  "Project `:rf.resource/status` — the entry's `:status` keyword (or
  `:idle` when no entry). Per Spec 016 §Subscriptions."
  [frame-state [_id payload]]
  (or (:status (entry-for (runtime-of frame-state) (app-of frame-state) payload)) :idle))

(defn loading?-sub-fn
  "Project `:rf.resource/loading?` — first load with no usable data. Per
  Spec 016 §Status semantics."
  [frame-state [_id payload]]
  (= :loading (:status (entry-for (runtime-of frame-state) (app-of frame-state) payload))))

(defn fetching?-sub-fn
  "Project `:rf.resource/fetching?` — work in flight while prior data
  stays visible. Per Spec 016 §Status semantics."
  [frame-state [_id payload]]
  (= :fetching (:status (entry-for (runtime-of frame-state) (app-of frame-state) payload))))

(defn stale?-sub-fn
  "Project `:rf.resource/stale?` — freshness orthogonal to load status
  (a `:loaded` entry may be stale). Computed from the durable
  `:invalidated-at` / `:stale-at` facts (Spec 016 §Status semantics)."
  [frame-state [_id payload]]
  (stale? (entry-for (runtime-of frame-state) (app-of frame-state) payload)))

(defn error-sub-fn
  "Project `:rf.resource/error` — the first-load error envelope (or nil).
  Per Spec 016 §Status semantics."
  [frame-state [_id payload]]
  (:error (entry-for (runtime-of frame-state) (app-of frame-state) payload)))

(defn refresh-error-sub-fn
  "Project `:rf.resource/refresh-error` — a failed background refresh
  (prior data kept). Per Spec 016 §Status semantics."
  [frame-state [_id payload]]
  (:refresh-error (entry-for (runtime-of frame-state) (app-of frame-state) payload)))

(defn has-data?-sub-fn
  "Project `:rf.resource/has-data?` — usable data present. Per Spec 016
  §Status semantics. Routed through the shared `state/has-data?` derivation
  (NOT the scalar `(some? :data)`): an infinite feed's `:data` is the page
  VECTOR seeded EMPTY (`[]`), which `some?` would wrongly read as usable data
  (rf2-3fynns). `state/has-data?` branches on the feed shape (`(seq data)`),
  so this scalar sub and `:rf.resource/infinite-state` never disagree."
  [frame-state [_id payload]]
  (state/has-data? (entry-for (runtime-of frame-state) (app-of frame-state) payload)))

(defn previous-data-sub-fn
  "Project `:rf.resource/previous-data` — the prior key's data projected
  while a new page/filter resource first-loads under `:keep-previous?`
  (Spec 016 §Paginated and previous data). Read from the entry's
  `:previous-key` projection pointer's OWN entry; NOT inserted into the new
  entry and NOT a source of the new key's tags. nil when this key is not
  keeping previous data (no pointer, or it already has its own data)."
  [frame-state [_id payload]]
  (let [rt (runtime-of frame-state)
        e  (entry-for rt (app-of frame-state) payload)]
    (when (and (:previous-key e) (nil? (:data e)))
      (:data (get-in rt (state/entry-path (:previous-key e)))))))

;; ---- the infinite-feed projection family (EP-0021 R3) ---------------------
;;
;; The merged list + page metadata for an `:infinite` feed. All passive, all
;; DERIVED (never stored — derived values are not durable facts), and — for
;; the merge — FRAMEWORK-OWNED MEMOISED rather than an ordinary app sub over
;; `:pages` (R3, the deliberate divergence from the `:select` precedent: the
;; merge is the headline read of every feed, so the framework owns the
;; memoised projection once). The page-vector lives in the entry's `:data`
;; (R1); these subs read it from the SAME frame-state signal the scalar family
;; reads, resolving the feed identity via `entry-for`.
;;
;; `:fetching-next?` is the one projection that is NOT a pure function of the
;; entry alone (R2): a load-more and a whole-feed refresh BOTH leave the feed
;; at `:fetching` (no 6th FSM state). The distinguishing durable fact is the
;; in-flight work record's `:page-index` — a load-more APPENDS at a positive
;; index (the tail), a page-0 fetch / refetch fetches index 0 — so the sub
;; joins the entry's `:current-work` to its work-ledger row and reads the
;; recorded page index (the "page-fetch work evidence" R1 names).

(defn- feed-entry-for
  "Read the infinite-feed entry for a sub `payload` (resolving + validating its
  scoped key against `runtime-db` / `app-db`), or nil when no entry / a
  non-infinite entry. The infinite projections are no-ops on an ordinary
  (non-feed) resource — they project the documented empty-feed shape rather
  than throwing, so a view that subscribes the wrong family degrades to empty
  rather than crashing (the loud failure is reserved for a genuine
  missing-accessor merge, below)."
  [runtime-db app-db payload]
  (let [e (entry-for runtime-db app-db payload)]
    (when (state/infinite-entry? e) e)))

;; Framework-owned merge memo (R3). A bounded per-feed cache keyed on the
;; feed's byte `key-id` → `{:pages <page-vector> :items <merged-vector>}`. The
;; sub re-runs its body on EVERY frame-state change (an unrelated runtime-db /
;; app-db commit), but the page vector only moves when a page is actually
;; appended / replaced, so the expensive `(into [] (mapcat …) pages)` flatten
;; is skipped (an `identical?` hit) on every re-run that did not touch THIS
;; feed's pages. The subscription layer's own output `=` memoisation then keeps
;; the sub quiet downstream; this memo additionally keeps the COMPUTATION quiet
;; (the framework owns the merge, not the app — R3). Host-side transient state
;; (NOT runtime-db); cleared per-test by the resources reset hook.
(defonce ^:private
  ^{:doc "Framework-owned `:rf.resource/items` merge memo: `{<feed-key-id>
   {:pages <page-vector> :items <merged-items-vector>}}`. ONE slot per feed
   key, OVERWRITTEN in place on each recompute — it grows with the number of
   DISTINCT feed keys ever merged (the same growth profile as the runtime-db
   `:entries` map), not per-append; each slot is a tiny pair of pointers
   (structurally shared with the entry's `:data`). The merge recomputes ONLY
   when the cached `:pages` is not `identical?` to the live page vector (a real
   append / replace), so a sub re-running on an unrelated frame-state change
   re-uses the prior merged list. A stale slot for a GC'd feed is harmless (it
   is just re-keyed the next time that key reloads, or never read again) and is
   dropped wholesale on the per-test reset; a host-side derivation cache is not
   correctness-bearing, so it carries no eviction-on-GC machinery. Host-side
   transient (NOT runtime-db); reset per-test by `reset-merge-memo!`."}
  items-merge-memo
  (atom {}))

(defn reset-merge-memo!
  "Drop the framework-owned `:rf.resource/items` merge memo (test isolation).
  Published as a reset hook so the shared CLJS reset-runtime fixture clears it
  per test — it is host-side transient dev state (a derivation cache), not
  runtime-db. Returns nil."
  []
  (reset! items-merge-memo {})
  nil)

(defn merged-items
  "The framework-owned, MEMOISED merged item list for an infinite-feed
  `entry` (R3) — the headline `:rf.resource/items` read. Resolves the
  resource's `:page->items` accessor and flattens the entry's `:data` page
  vector via `state/merge-pages->items` (vector pages flatten by identity; a
  non-vector page REQUIRES the accessor or raises
  `:rf.error/infinite-missing-page-accessor`). Memoised on the page-vector
  IDENTITY keyed by the feed's `key-id`: a re-run whose page vector is
  `identical?` to the last merged one returns the cached merged list without
  re-flattening. Returns `[]` for a nil / empty feed. Pure modulo the memo."
  [entry where]
  (let [pages (:data entry)]
    (if (empty? pages)
      []
      (let [k-id   (state/key-id (:resource/key entry))
            cached (get @items-merge-memo k-id)]
        (if (and cached (identical? (:pages cached) pages))
          (:items cached)
          (let [spec     (registry/resource-meta (:resource/id entry))
                accessor (state/resolve-page->items (:page->items spec))
                merged   (state/merge-pages->items
                           pages accessor (:resource/id entry) where)]
            (swap! items-merge-memo assoc k-id {:pages pages :items merged})
            merged))))))

(defn items-sub-fn
  "Project `:rf.resource/items` — the merged / flattened item list of an
  infinite feed (the HEADLINE read). Framework-owned + memoised (R3). `[]`
  when no feed / empty feed. Raises `:rf.error/infinite-missing-page-accessor`
  when a non-vector page has no `:page->items`. Per Spec 016 §Subscription
  contract."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (if e (merged-items e 'rf.resource/items) [])))

(defn pages-sub-fn
  "Project `:rf.resource/pages` — the raw ordered page vector (the TanStack
  `pages` analogue), for views that need page boundaries. `[]` when no feed.
  Per Spec 016 §Subscription contract."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (vec (:data e))))

(defn page-count-sub-fn
  "Project `:rf.resource/page-count` — the number of accumulated pages (0 when
  no feed). Per Spec 016 §Subscription contract."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (state/page-count e)))

(defn has-next-page?-sub-fn
  "Project `:rf.resource/has-next-page?` — `(some? :next-page-param)`, the
  observable terminal (a view never re-derives nil). false when no feed. Per
  Spec 016 §Subscription contract."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (and (some? e) (not (state/terminal? (:next-page-param e))))))

(defn has-prev-page?-sub-fn
  "Project `:rf.resource/has-prev-page?` — `(some? :prev-page-param)`, the
  bidirectional mirror (observable; the prepend event is deferred, R7). false
  when no feed. Per Spec 016 §Subscription contract."
  [frame-state [_id payload]]
  (let [e (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)]
    (and (some? e) (not (state/terminal? (:prev-page-param e))))))

(defn page-error-sub-fn
  "Project `:rf.resource/page-error` — the last load-more failure envelope
  (the THIRD error channel; distinct from `:error` / `:refresh-error`), or
  nil. Per Spec 016 §Subscription contract / §Causal event — load-more."
  [frame-state [_id payload]]
  (:page-error (feed-entry-for (runtime-of frame-state) (app-of frame-state) payload)))

(defn- fetching-next?*
  "DERIVED `:fetching-next?` (R2): true iff a LOAD-MORE is in flight on the
  feed `entry`, read from `runtime-db`. A load-more and a whole-feed refresh
  both leave the feed at `:fetching` (no 6th FSM state), so the entry status
  alone cannot tell them apart; the distinguishing durable fact is the
  in-flight work record's `:page-index` — a load-more APPENDS at a POSITIVE
  index (the tail), a page-0 fetch / refetch fetches index 0. The sub joins
  the entry's `:current-work` pointer to its live work-ledger row and reads the
  recorded page index. false when the feed is not fetching, has no live work,
  or the live work is a page-0 fetch / whole-feed refresh. Per Spec 016
  §Causal event — load-more (R2) / §Subscription contract."
  [runtime-db entry]
  (boolean
    (when (and (some? entry) (= :fetching (:status entry)))
      (let [work-id (:current-work entry)
            record  (when work-id (work-ledger/get-record runtime-db work-id))
            status  (:status record)]
        (and (some? record)
             ;; the work is genuinely LIVE (not terminal / doomed) …
             (not (work-ledger/terminal? status))
             (not= :abort-requested status)
             ;; … and it is a load-more APPEND (a positive page index), not a
             ;; page-0 fetch / whole-feed refresh (index 0 / absent).
             (let [pi (:page-index record)]
               (and (integer? pi) (pos? pi))))))))

(defn fetching-next?-sub-fn
  "Project `:rf.resource/fetching-next?` — a load-more in flight (R2),
  distinct from `:fetching?` (a whole-feed refresh). Per Spec 016
  §Subscription contract / §Causal event — load-more."
  [frame-state [_id payload]]
  (let [rt (runtime-of frame-state)
        e  (feed-entry-for rt (app-of frame-state) payload)]
    (fetching-next?* rt e)))

(defn infinite-state-sub-fn
  "Project `:rf.resource/infinite-state` — the combined infinite view-model
  (the feed analogue of `:rf.resource/state`): the merged `:items`, the raw
  `:pages`, `:page-count`, `:has-next-page?` / `:has-prev-page?`, the DERIVED
  `:loading?` / `:fetching?` / `:fetching-next?` / `:stale?` / `:has-data?`,
  and the three error channels (`:error` first-load / `:refresh-error`
  whole-feed / `:page-error` load-more). Framework-owned + memoised merge (R3).
  Empty-feed shape when no infinite entry. Per Spec 016 §Subscription
  contract."
  [frame-state [_id payload]]
  (let [rt (runtime-of frame-state)
        e  (feed-entry-for rt (app-of frame-state) payload)]
    (if (nil? e)
      ;; documented empty-feed projection (idle, nothing accumulated)
      {:status :idle :items [] :pages [] :page-count 0
       :has-next-page? false :has-prev-page? false
       :loading? false :fetching? false :fetching-next? false
       :stale? false :has-data? false
       :error nil :refresh-error nil :page-error nil}
      (let [next? (fetching-next?* rt e)]
        {:status         (:status e)
         :items          (merged-items e 'rf.resource/infinite-state)
         :pages          (vec (:data e))
         :page-count     (state/page-count e)
         :has-next-page? (not (state/terminal? (:next-page-param e)))
         :has-prev-page? (not (state/terminal? (:prev-page-param e)))
         :loading?       (= :loading (:status e))
         ;; `:fetching?` is a WHOLE-FEED refresh; `:fetching-next?` is a
         ;; load-more — both ride `:fetching` (no 6th FSM state, R2), so split
         ;; them here so the view-model fields are disjoint.
         :fetching?      (and (= :fetching (:status e)) (not next?))
         :fetching-next? next?
         :stale?         (stale? e)
         ;; an infinite feed's `:data` is the page VECTOR (`[]` when empty), so
         ;; `:has-data?` is "≥ 1 accumulated page" — delegated to the shared
         ;; `state/has-data?` (which knows the infinite-feed shape: `(seq data)`,
         ;; never the scalar `(some? data)` that an empty `[]` would satisfy), so
         ;; the sub and the restore/FSM readers never drift.
         :has-data?      (state/has-data? e)
         :error          (:error e)
         :refresh-error  (:refresh-error e)
         :page-error     (:page-error e)}))))

;; ---- registration helper -------------------------------------------------

(defn register-subs!
  "Register the `:rf.resource/*` passive sub family. Called from the
  `re-frame.resources` façade so a `(require … :reload)` on a fresh
  registrar re-wires them. Per Spec 016 §Subscriptions.

  Registered with `reg-frame-state-sub` (NOT `reg-runtime-sub`, EP-0016 D3
  slice 3 / rf2-616xa6): the single signal is the WHOLE frame-state value,
  so a resource sub whose scope is a `{:from-db <id>}` resolver re-keys
  reactively when the resolver's declared app-db inputs change mid-session
  (account switch / impersonation / login), while still reacting to
  runtime-db cache writes. Output `=` memoisation keeps the sub quiet when
  neither the resolved scoped key nor the read entry changed."
  []
  (subs/reg-frame-state-sub :rf.resource/state
    {:doc "Passive read of a resource instance's full view-model `{:status :data :error :refresh-error :loading? :fetching? :stale? :has-data?}`. Resolves scope per Spec 016 §Subscription-side scope resolution (incl. `{:from-db <id>}` named-resolver references against app-db); raises :rf.error/resource-sub-unresolved-scope rather than reading global / returning a silent :idle. Per Spec 016 §Subscriptions."}
    state-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/data
    {:doc "Passive read of a resource instance's last-known-good :data (or nil). Per Spec 016 §Subscriptions."}
    data-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/status
    {:doc "Passive read of a resource instance's :status keyword (:idle / :loading / :fetching / :loaded / :error). Per Spec 016 §Subscriptions."}
    status-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/loading?
    {:doc "Passive read: true iff a resource instance is on its first load with no usable data. Per Spec 016 §Status semantics."}
    loading?-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/fetching?
    {:doc "Passive read: true iff a resource instance is refreshing while prior data stays visible. Per Spec 016 §Status semantics."}
    fetching?-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/stale?
    {:doc "Passive read: true iff a resource instance is stale (freshness is orthogonal to load status). Per Spec 016 §Status semantics."}
    stale?-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/error
    {:doc "Passive read of a resource instance's first-load error envelope (or nil). Per Spec 016 §Status semantics."}
    error-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/refresh-error
    {:doc "Passive read of a resource instance's background-refresh error envelope (or nil); the prior :data is kept. Per Spec 016 §Status semantics."}
    refresh-error-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/has-data?
    {:doc "Passive read: true iff a resource instance currently has usable :data. Per Spec 016 §Status semantics."}
    has-data?-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/previous-data
    {:doc "Passive read of the prior key's data, projected while a new page/filter resource first-loads under :keep-previous? (not inserted into the new entry). Per Spec 016 §Paginated and previous data."}
    previous-data-sub-fn)
  ;; ---- the infinite-feed projection family (EP-0021 R3) -----------------
  ;; `:rf.resource/items` / `:pages` / `:infinite-state` are the framework-
  ;; owned MEMOISED merge subs (R3 — NOT ordinary app subs over :pages); the
  ;; page-metadata subs round out the load-more UI state.
  (subs/reg-frame-state-sub :rf.resource/items
    {:doc "Passive read of an infinite feed's MERGED / flattened item list (the headline read) — DERIVED + framework-owned-memoised by concatenating each accumulated page's items (R3). A vector page flattens by identity; a non-vector / enveloped page REQUIRES a :page->items accessor (else :rf.error/infinite-missing-page-accessor). [] for a non-feed / empty feed. Per Spec 016 §Subscription contract (R3)."}
    items-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/pages
    {:doc "Passive read of an infinite feed's raw ordered page vector (the TanStack `pages` analogue) — for views that need page boundaries. [] for a non-feed. Per Spec 016 §Subscription contract (R3)."}
    pages-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/page-count
    {:doc "Passive read of an infinite feed's accumulated page count (0 when no feed). Per Spec 016 §Subscription contract."}
    page-count-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/has-next-page?
    {:doc "Passive read: true iff an infinite feed has a next page ((some? :next-page-param) — nil is the single terminal). false for a non-feed / terminal feed. Per Spec 016 §Subscription contract."}
    has-next-page?-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/has-prev-page?
    {:doc "Passive read: true iff an infinite feed has a prev page ((some? :prev-page-param) — the bidirectional mirror; the prepend event is deferred, R7). false for a non-feed. Per Spec 016 §Subscription contract."}
    has-prev-page?-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/fetching-next?
    {:doc "Passive read: true iff a LOAD-MORE is in flight on an infinite feed (R2) — distinct from :fetching? (a whole-feed refresh). Derived from the in-flight work record's page index (a load-more appends at a positive index). Per Spec 016 §Subscription contract / §Causal event — load-more."}
    fetching-next?-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/page-error
    {:doc "Passive read of an infinite feed's last LOAD-MORE failure envelope (the third error channel; distinct from :error first-load and :refresh-error whole-feed), or nil. Per Spec 016 §Subscription contract / §Causal event — load-more."}
    page-error-sub-fn)
  (subs/reg-frame-state-sub :rf.resource/infinite-state
    {:doc "Passive read of an infinite feed's combined view-model `{:status :items :pages :page-count :has-next-page? :has-prev-page? :loading? :fetching? :fetching-next? :stale? :has-data? :error :refresh-error :page-error}` — the feed analogue of :rf.resource/state, with the framework-owned-memoised merged :items (R3). Empty-feed shape when no infinite entry. Per Spec 016 §Subscription contract (R3)."}
    infinite-state-sub-fn)
  nil)
