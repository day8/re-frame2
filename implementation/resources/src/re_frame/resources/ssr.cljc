(ns re-frame.resources.ssr
  "LATE-BOUND SSR / hydration integration for resources. Per Spec 016
  §SSR and hydration and §Restore and replay.

  SSR MUST use request-local frames — a process-global resource cache
  would leak data between users. On a server render the runtime resolves
  the route, computes route resources, enqueues blocking ensures, DRAINS
  until the blocking resources for the current nav-token settle, renders
  with the settled state, and serializes ONLY the allowed resource-runtime
  projection (NEVER all of `:rf.db/runtime`). On client hydration the
  allowed projection installs into the target frame-state's
  `:rf.runtime/resources` slice in runtime-db; hydrated entries are
  preserved, fresh ones avoid a duplicate fetch, stale ones
  background-refetch by event, and hydration NEVER crosses scopes.

  ## What lives here (the SSR slice, rf2-ctk2av)

  This namespace is PURE / host-agnostic. SSR runs on the JVM, so the
  whole body is reader-conditional CLJC and the heavy lifting is plain
  data transforms the host adapter / route slice drives:

  - **Server projection** — `project-resources-runtime-db` (the body
    behind the `:ssr/extend-runtime-db-projection` hook) projects the
    durable `:entries`, applying per-entry REDACTION / OMISSION through
    the resource's `:sensitive?` / `:large?` classification and the
    shared `rf/elide-wire-value` walker, and `projection-metadata`
    records the serialized / redacted / omitted / fresh / stale /
    refetch-on-client decision per entry.
  - **Server blocking drain** — `blocking-settled?` is the drain
    PREDICATE the host loops on (\"have the blocking resources for this
    nav-token settled?\"); `settle-blocking-timeout` is the timeout
    POLICY (settle each unsettled blocking entry as a structured
    first-load failure + produce a route-blocking-failure record), so a
    blocked SSR render never hangs indefinitely.
  - **Client hydration** — `hydrate-runtime-db` reconciles a freshly
    INSTALLED resource subtree on `:rf/hydrate`: it recomputes the
    reverse indexes from `:entries` (never trusts the wire), reconciles
    owners (SSR owners orphan — they belong to a settled server render),
    and surfaces server clock skew. `hydrate-refetch-plan` decides which
    hydrated entries need a client refetch (omitted / redacted →
    metadata-only refetch; stale → background refetch; fresh → no
    double-fetch).

  ## Late-binding both ways

  Resource hydration uses an EXPLICIT projection hook — the
  allowlist-by-subsystem-child `project-runtime-db` of [011-SSR]. Rather
  than statically `:require`ing SSR (which would drag the SSR body into
  every resources app), resources publishes the LATE-BOUND
  `:ssr/extend-runtime-db-projection` hook (server projection) and the
  `:resources/hydrate-runtime-db` hook (client reconcile); SSR consults
  them. The wiring is late-bound BOTH ways — an app that loads resources
  but does not SSR carries none of this code path, and an SSR app without
  resources sees no behaviour change.

  Only the durable resource projection (`:rf.runtime/resources` →
  `:entries`) rides the wire; `:tag-index` / `:owner-index` are
  recomputable-from-`:entries` (rebuilt on install, never trusted from the
  snapshot — Spec 016 §Restore and replay part 5) and need not ride. Per
  Spec 016 §Runtime-subsystem graduation clause 4."
  (:require [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.state :as state]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- shared clock ---------------------------------------------------------

(defn- now-ms
  "Current epoch-ms — the server clock stamped on `loaded-at` / `stale-at`
  and the live clock the client compares restored absolute timestamps
  against to surface skew. Host-platform clock (JVM under SSR; the browser
  on the client). Per Spec 016 §SSR and hydration (absolute timestamps)."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

;; ---- freshness classification (Spec 016 §Status semantics) ----------------
;;
;; Freshness is computed from the entry's DURABLE absolute timestamps
;; (`:stale-at` / `:invalidated-at`) against a supplied clock — never from
;; trusting a timer fired on time (Spec 016 §Stale and GC scheduling). The
;; SAME derivation the subs layer uses, lifted here so the server projection
;; metadata and the client refetch decision agree.

(defn entry-stale?
  "True iff `entry` is stale against `clock-ms`: it has been explicitly
  invalidated (`:invalidated-at` set) OR its `:stale-after-ms` window has
  elapsed (`:stale-at` set and `clock-ms >= :stale-at`). Freshness is
  ORTHOGONAL to load status (a `:loaded` entry may be stale). Per Spec 016
  §Status semantics."
  [entry clock-ms]
  (boolean
    (and entry
         (or (some? (:invalidated-at entry))
             (when-let [sa (:stale-at entry)] (>= clock-ms sa))))))

;; ---- per-entry sensitivity / size classification (Spec 016 clause 4) ------
;;
;; Params, scopes, and data carry `:sensitive?` / `:large?` classification.
;; A resource registered `:sensitive? true` (or `:large? true`) must NOT
;; ship its data verbatim onto the wire — every visitor of every SSR page
;; would otherwise receive it. We run the entry's `:data` through the
;; SHARED `rf/elide-wire-value` walker (the same per-frame, schema-declared
;; redaction the trace / epoch egress uses), under the SSR frame so the
;; resource's declared classification governs. The classification source:
;; the resource spec's coarse `:sensitive?` / `:large?` flags (registration
;; metadata) PLUS the schema-slot marks the walker already honours.

(defn- entry-classification
  "Classify how `entry` (under `scoped-key`) may ride the wire, reading the
  resource spec's coarse `:sensitive?` / `:large?` flags. Returns one of:

    :serialize  — ship the data verbatim;
    :redact     — ship the entry as METADATA ONLY (status / timestamps /
                  generation) with the data replaced by the redaction
                  sentinel — a `:sensitive?` resource;
    :omit       — drop the entry's data wholesale (ship metadata only, no
                  data key at all) — a `:large?` resource whose payload is
                  too big to ride the hydration wire.

  Sensitive wins over large when both are declared (the redaction sentinel
  is the more conservative shape — it still announces an entry exists).
  Per Spec 016 §Runtime-subsystem graduation clause 4."
  [scoped-key _entry]
  (let [resource-id (nth scoped-key 1)
        spec        (registry/resource-meta resource-id)]
    (cond
      (:sensitive? spec) :redact
      (:large? spec)     :omit
      :else              :serialize)))

(defn- project-entry
  "Project a single durable cache `entry` (under `scoped-key`) to its wire
  shape per its classification, against the SSR `frame-id` (so the shared
  elision walker resolves the resource's declared sensitive / large schema
  paths). Returns `[wire-entry metadata]` where `metadata` records the
  per-entry projection decision (Spec 016 §SSR and hydration step 7):

    {:resource-key   scoped-key
     :resource-id    <id>
     :disposition    :serialized | :redacted | :omitted
     :freshness      :fresh | :stale
     :status         <entry :status>
     :refetch-on-client? <bool>
     :loaded-at :stale-at :invalidated-at <absolute ms>}

  - `:serialized` — data rides verbatim;
  - `:redacted`   — `:sensitive?` resource: data replaced by the redaction
    sentinel; the client hydrates it as metadata-only and refetches if the
    route still needs it (`:refetch-on-client? true`);
  - `:omitted`    — `:large?` resource: the `:data` key is dropped entirely;
    metadata-only, refetch-on-client.

  `:refresh-error` rides ONLY when the entry's data is serialized (it is
  the same privacy/size class as data — Spec 016 §SSR and hydration: a
  `:refresh-error` serializes only when the error envelope is allowed by
  the data projection). A redacted/omitted entry drops `:refresh-error`."
  [frame-id clock-ms scoped-key entry]
  (let [resource-id (nth scoped-key 1)
        disposition (entry-classification scoped-key entry)
        stale?      (entry-stale? entry clock-ms)
        ;; metadata-only entries refetch on the client if a live route owner
        ;; needs them; serialized stale entries also refetch (background);
        ;; serialized fresh entries do NOT (no double-fetch).
        metadata-only? (not= :serialize disposition)
        base        {:resource-key   scoped-key
                     :resource-id    resource-id
                     :freshness      (if stale? :stale :fresh)
                     :status         (:status entry)
                     :loaded-at      (:loaded-at entry)
                     :stale-at       (:stale-at entry)
                     :invalidated-at (:invalidated-at entry)}
        wire-entry  (case disposition
                      ;; ship verbatim, but run the data through the frame's
                      ;; elision policy (defense in depth) ONLY when a frame is
                      ;; carried — so a schema-marked sensitive SUB-path is
                      ;; still redacted even on a coarse-`:serialize` resource.
                      ;; Frameless egress fails closed to the sentinel, which
                      ;; would over-redact a non-sensitive resource projected
                      ;; outside a frame scope (test harness / pure use); guard
                      ;; the walker on a known frame so the coarse classification
                      ;; (not frame-presence) governs serialize-vs-redact.
                      :serialize
                      (assoc entry :data
                             (if frame-id
                               (elision/elide-wire-value (:data entry) {:frame frame-id})
                               (:data entry)))
                      ;; sensitive: replace data with the redaction sentinel
                      ;; (the entry still announces it exists; metadata only).
                      ;; The coarse `:sensitive?` claim is the authority — the
                      ;; sentinel is explicit, not dependent on a frame-resolved
                      ;; schema mark. refresh-error is the same privacy class.
                      :redact
                      (-> entry
                          (assoc :data privacy/redacted-sentinel)
                          (dissoc :refresh-error))
                      ;; large: drop the data key entirely (metadata only).
                      :omit
                      (-> entry
                          (dissoc :data :refresh-error)))
        ;; transient host-pointer facts never ride the wire: the work-id the
        ;; entry currently points at references a host handle that does not
        ;; survive the round-trip (Spec 016 §Restore and replay part 2 — a
        ;; non-terminal attempt is dangling on install).
        wire-entry  (dissoc wire-entry :current-work)
        meta'       (assoc base
                           :disposition (case disposition
                                          :serialize :serialized
                                          :redact    :redacted
                                          :omit      :omitted)
                           :refetch-on-client?
                           (boolean (or metadata-only? stale?)))]
    [wire-entry meta']))

(defn projection-metadata
  "Compute the per-entry SSR projection metadata for a frame's resource
  `:entries` against `clock-ms` (Spec 016 §SSR and hydration step 7 — the
  serialized / redacted / omitted / fresh / stale / refetch-on-client
  decisions). Returns a vector of per-entry metadata maps. PURE; the host
  adapter records it in route/SSR diagnostics."
  [frame-id clock-ms entries]
  (mapv (fn [[k entry]] (second (project-entry frame-id clock-ms k entry)))
        entries))

(defn project-resources-runtime-db
  "Project the durable resource-runtime slice that rides the
  `:rf/hydration-payload`'s `:rf/runtime-db` slice. Returns a
  `{:rf.runtime/resources {:entries …}}` map (the durable cache facts,
  per-entry REDACTED / OMITTED by the resource's `:sensitive?` / `:large?`
  classification) for a runtime-db carrying resource entries, or `{}`
  otherwise. The reverse indexes (`:tag-index` / `:owner-index`) are
  deliberately EXCLUDED — they are recomputable-from-`:entries` and rebuilt
  on install. Per Spec 016 §SSR and hydration / §Runtime-subsystem
  graduation clause 4.

  This is the body behind the `:ssr/extend-runtime-db-projection` hook.

  Resolves the SSR frame from the in-effect carried-frame scope (the shared
  `rf/elide-wire-value` walker reads it) so the resource's declared
  sensitive / large schema paths govern the per-entry projection. The
  projection NEVER ships all of `:rf.db/runtime` — only the
  `:rf.runtime/resources` `:entries` (Spec 016 §SSR and hydration: \"Do not
  serialize all of `:rf.db/runtime` by default\")."
  [runtime-db]
  (let [resources (get runtime-db state/resources-key)
        entries   (:entries resources)]
    (if (seq entries)
      (let [frame-id (frame/resolve-current-frame)
            clock-ms (now-ms)
            wired    (into {}
                           (map (fn [[k entry]]
                                  [k (first (project-entry frame-id clock-ms k entry))]))
                           entries)]
        {state/resources-key {:entries wired}})
      {})))

;; ---- server blocking drain (Spec 016 §SSR and hydration steps 3-4) --------
;;
;; SSR resolves the route, enqueues blocking resource ensures, and DRAINS
;; until the blocking resources for the current nav-token settle. The host
;; adapter / route slice owns the actual drain LOOP (it owns the event
;; pump); this namespace owns the PREDICATE it loops on and the TIMEOUT
;; policy, so the wait point is one pure, testable contract independent of
;; the host's pump.

(defn entry-settled?
  "True iff a resource `entry` has settled to a terminal cache status for
  SSR — `:loaded` (data ready) or `:error` (first-load failed, no data) —
  i.e. it is no longer `:idle` / `:loading` / `:fetching` (work in flight).
  A nil entry (never enqueued) is NOT settled. Per Spec 016 §SSR and
  hydration step 4 / §Lifecycle is an FSM."
  [entry]
  (boolean (and entry (contains? #{:loaded :error} (:status entry)))))

(defn blocking-settled?
  "The SSR blocking-drain PREDICATE (Spec 016 §SSR and hydration step 4):
  given the frame's resource `:entries` and the set of `blocking-keys` the
  route enqueued for the current nav-token, return true iff EVERY blocking
  entry has settled (`entry-settled?`). The host adapter loops the event
  pump until this returns true (or the timeout fires — `settle-blocking-
  timeout`). An empty `blocking-keys` set is trivially settled (a route
  with no blocking resources never blocks the render). PURE — the host
  reads the live frame's entries each tick and re-evaluates.

  `blocking-keys` are scoped resource keys; nav-token isolation is the
  CALLER's responsibility — the route slice computes the blocking-keys for
  the current nav-token only, so a superseded navigation's keys never enter
  this predicate."
  [entries blocking-keys]
  (every? (fn [k] (entry-settled? (get entries k))) blocking-keys))

(defn unsettled-blocking-keys
  "Return the subset of `blocking-keys` whose entries have NOT settled
  (still `:idle` / `:loading` / `:fetching`, or absent). The set
  `settle-blocking-timeout` fails closed against when the SSR deadline
  fires. Per Spec 016 §SSR and hydration (blocking timeout policy)."
  [entries blocking-keys]
  (into #{} (remove (fn [k] (entry-settled? (get entries k)))) blocking-keys))

(defn ssr-timeout-error
  "The structured first-load failure envelope a blocking SSR timeout
  settles an unsettled entry to (Spec 016 §SSR and hydration: a timeout
  settles the resource as a structured first-load failure for that SSR
  frame). The `:error` envelope shares the closed `:rf.http/*` failure
  taxonomy (Spec 014) — a wall-clock budget elapsed is `:rf.http/timeout`,
  tagged `:reason :ssr-blocking-timeout` so a renderer can distinguish an
  SSR-deadline failure from a genuine upstream timeout."
  [deadline-ms]
  {:kind       :rf.http/timeout
   :reason     :ssr-blocking-timeout
   :limit-ms   deadline-ms
   :message    (str "SSR blocking resource did not settle within the "
                    deadline-ms "ms render deadline; settled as a first-load "
                    "failure so the request does not hang (Spec 016 §SSR and "
                    "hydration).")})

(defn settle-blocking-timeout
  "Apply the SSR blocking-timeout POLICY (Spec 016 §SSR and hydration: a
  timeout settles the resource as a structured first-load failure for that
  SSR frame, records the route blocking failure, and lets the renderer
  choose error / skeleton / fallback — it MUST NOT hang indefinitely).

  Pure `(entries blocking-keys deadline-ms) -> {:entries :route-blocking-
  failure}`:

    - every UNSETTLED blocking entry (`unsettled-blocking-keys`) is settled
      to a first-load failure (`entry-failed` with the `ssr-timeout-error`
      envelope) so the entry leaves `:loading`/`:idle` and the renderer sees
      a structured `:error`, never a hung `:loading`;
    - `:route-blocking-failure` is the record the route/SSR diagnostics
      surface (`:rf.error/resource-ssr-blocking-timeout`) — which blocking
      keys timed out, the deadline, and the failure envelope.

  Returns the updated `:entries` plus the route-blocking-failure record (or
  nil when nothing timed out). The host adapter installs `:entries` into the
  SSR frame's runtime-db and hands `:route-blocking-failure` to the route
  slice / renderer. This namespace does NOT touch route state directly (the
  route slice owns that surface)."
  [entries blocking-keys deadline-ms frame-id]
  (let [unsettled (unsettled-blocking-keys entries blocking-keys)]
    (if (empty? unsettled)
      {:entries entries :route-blocking-failure nil}
      (let [error    (ssr-timeout-error deadline-ms)
            entries' (reduce
                       (fn [es k]
                         (let [entry (or (get es k)
                                         (state/empty-entry (nth k 1)))]
                           (assoc es k (state/entry-failed entry {:error error}))))
                       entries unsettled)]
        (trace/emit-error! :rf.error/resource-ssr-blocking-timeout
                           {:rf.error/id :rf.error/resource-ssr-blocking-timeout
                            :where       're-frame.resources.ssr/settle-blocking-timeout
                            :frame       frame-id
                            :recovery    :settled-as-first-load-failure
                            :timed-out   (vec unsettled)
                            :limit-ms    deadline-ms
                            :reason      (str (count unsettled) " blocking SSR "
                                              "resource(s) did not settle within "
                                              deadline-ms "ms; settled as first-load "
                                              "failures so the render does not hang.")})
        {:entries entries'
         :route-blocking-failure
         {:rf.error/id :rf.error/resource-ssr-blocking-timeout
          :timed-out   (vec unsettled)
          :limit-ms    deadline-ms
          :error       error}}))))

;; ---- client hydration (Spec 016 §SSR and hydration / §Restore and replay) -
;;
;; On `:rf/hydrate` the SSR handler installs the payload's `:rf/runtime-db`
;; slice wholesale into the runtime-db partition (Spec 011 §The :rf/hydrate
;; event) — including the projected resource `:entries`. The reverse indexes
;; were NOT serialized (recomputable-from-entries), and the installed owners
;; reference the SETTLED server render. `hydrate-runtime-db` reconciles the
;; freshly-installed resource subtree so the client sees a coherent cache:
;;
;;   1. recompute `:tag-index` / `:owner-index` from the installed `:entries`
;;      (never trust the wire — Spec 016 §Restore and replay part 5);
;;   2. reconcile owners by kind — SSR owners (`[:ssr request-id nav-token]`)
;;      do NOT survive as live client leases (they belong to a settled
;;      server render); they are dropped as orphans (Spec 016 §Restore and
;;      replay part 4 / §Release authority is per owner kind);
;;   3. clear each entry's transient `:current-work` pointer (the work it
;;      pointed at never crossed the wire — part 2);
;;   4. surface server CLOCK SKEW when a restored `:stale-at` is implausible
;;      against the live clock (Spec 016 §SSR and hydration: absolute
;;      timestamps; skew surfaced when it makes freshness ambiguous).

(defn- ssr-owner?
  "True iff `owner` is an SSR owner token (`[:ssr request-id nav-token]`).
  SSR owners belong to one server render and never survive as a live
  client-side lease. Per Spec 016 §Release authority is per owner kind."
  [owner]
  (and (vector? owner) (= :ssr (first owner))))

(defn- reconcile-entry-owners
  "Drop SSR owners from a hydrated entry's `:active-owners` (they orphan on
  hydration — Spec 016 §Restore and replay part 4) and clear the transient
  `:current-work` pointer (the attempt it pointed at did not cross the wire
  — part 2). Route / machine / lease owners ride through unchanged (their
  liveness is reconciled by their own subsystem on the live client). Returns
  `[entry' dropped-ssr-owners]`."
  [entry]
  (let [owners  (:active-owners entry #{})
        ssr     (into #{} (filter ssr-owner?) owners)
        kept    (into #{} (remove ssr-owner?) owners)]
    [(-> entry
         (assoc :active-owners kept)
         (assoc :current-work nil))
     ssr]))

(defn clock-skew-ms
  "Compute server→client clock skew evidence for a hydrated `entry` against
  `clock-ms`: the number of ms a restored `:stale-at` lies in the FUTURE
  beyond the live clock by an IMPLAUSIBLE margin (a `:stale-at` far ahead of
  `now` means the server clock ran ahead of the client's, making freshness
  ambiguous). Returns the positive skew ms when implausible, else nil. PURE.
  Per Spec 016 §SSR and hydration (server clock skew surfaced when it makes
  freshness ambiguous)."
  [entry clock-ms]
  (when-let [sa (:stale-at entry)]
    (let [la (:loaded-at entry)
          ;; the entry's own freshness window (loaded-at..stale-at). A
          ;; :stale-at more than one whole window ahead of `now` (relative to
          ;; when it loaded) is implausible against the live clock — the
          ;; server's clock was ahead. nil window (no :stale-after-ms) can't
          ;; be checked.
          window (when (and la sa) (- sa la))]
      (when (and window (pos? window) (> sa (+ clock-ms window)))
        (- sa clock-ms)))))

(defn hydrate-runtime-db
  "Reconcile a freshly-INSTALLED resource subtree on `:rf/hydrate` (the body
  behind the `:resources/hydrate-runtime-db` hook the SSR hydrate handler
  consults). `runtime-db` is the runtime-db partition the SSR handler is
  about to install (the payload's `:rf/runtime-db` slice merged with
  hydration metadata). Returns the runtime-db with the `:rf.runtime/resources`
  subtree reconciled — or `runtime-db` unchanged when it carries no resource
  entries (an SSR app without resource data; a no-op).

  Reconciliation (Spec 016 §SSR and hydration / §Restore and replay):
    1. drop SSR owners from every entry (they orphan — part 4) + clear the
       transient `:current-work` pointer (part 2);
    2. recompute `:tag-index` / `:owner-index` from the reconciled
       `:entries` (never trust the wire — part 5);
    3. emit a `:rf.resource/hydrated` trace summarising installed / orphaned
       counts, and a clock-skew diagnostic when a restored `:stale-at` is
       implausible against the live clock.

  NEVER crosses scopes: it only reconciles the entries the server projected
  under their own scoped keys (the scope is the first element of each key);
  it does not move data between scopes. PURE w.r.t. `runtime-db` (the trace
  emit self-gates on debug)."
  ([runtime-db] (hydrate-runtime-db runtime-db nil))
  ([runtime-db frame-id]
   (let [resources (get runtime-db state/resources-key)
         entries   (:entries resources)]
     (if-not (seq entries)
       runtime-db
       (let [clock-ms (now-ms)
             ;; reconcile each entry: orphan SSR owners + clear current-work
             reconciled (reduce-kv
                          (fn [acc k entry]
                            (let [[entry' ssr] (reconcile-entry-owners entry)
                                  skew         (clock-skew-ms entry clock-ms)]
                              (-> acc
                                  (assoc-in [:entries k] entry')
                                  (update :orphaned into (map (fn [o] [k o])) ssr)
                                  (cond-> skew (update :skews assoc k skew)))))
                          {:entries {} :orphaned [] :skews {}}
                          entries)
             entries'  (:entries reconciled)
             ;; recompute the reverse indexes from the reconciled entries
             subtree'  (state/recompute-indexes
                         (assoc resources :entries entries'))
             rdb'      (assoc runtime-db state/resources-key subtree')]
         (trace/emit! :rf.event :rf.resource/hydrated
                      {:rf.frame/id    frame-id
                       :installed      (count entries')
                       :orphaned-owners (vec (:orphaned reconciled))
                       :clock-skews    (:skews reconciled)})
         (doseq [[k skew] (:skews reconciled)]
           (trace/emit! :warning :rf.resource/hydrate-clock-skew
                        {:rf.frame/id  frame-id
                         :resource-key k
                         :skew-ms      skew
                         :reason       (str "hydrated entry's absolute :stale-at is "
                                            skew "ms ahead of the live client clock "
                                            "— server clock skew makes freshness "
                                            "ambiguous; refetch will resolve it.")}))
         rdb')))))

;; ---- client refetch decision (Spec 016 §SSR and hydration) ----------------
;;
;; After install, a hydrated entry either renders its data immediately
;; (fresh serialized) or needs a client refetch. The decision (no double-
;; fetch is the load-bearing one):
;;
;;   - FRESH + has data            -> no refetch (the win SSR exists for);
;;   - STALE + has data            -> background-refetch by event (renders
;;                                    stale data immediately, refreshes);
;;   - metadata-only (redacted /   -> refetch ONLY if the route still needs
;;     omitted; no data)              it (a live owner / the route's plan).

(defn entry-needs-refetch?
  "Decide whether a hydrated `entry` needs a client refetch against
  `clock-ms`. Per Spec 016 §SSR and hydration:

    - a FRESH entry WITH usable data does NOT refetch (avoid the duplicate
      immediate fetch — the property SSR exists for);
    - a STALE entry with usable data background-refetches by policy;
    - a metadata-only entry (redacted / omitted — no `:data`, or settled to
      `:error` on the server) refetches if the route still needs it.

  Returns `false` only for the fresh-with-data case; the metadata-only /
  stale / no-data cases return `true` so the route slice can issue the
  refetch under a live owner (it is the route plan that decides whether the
  route still NEEDS the entry; this predicate answers \"is the hydrated
  value sufficient on its own?\")."
  [entry clock-ms]
  (let [has-data? (some? (:data entry))]
    (cond
      (not has-data?)              true            ;; metadata-only / error
      (entry-stale? entry clock-ms) true           ;; stale-while-revalidate
      :else                         false)))       ;; fresh + data → no dup-fetch

(defn hydrate-refetch-plan
  "Build the client refetch plan for a hydrated resource subtree: the
  scoped keys whose hydrated entry is NOT sufficient on its own (per
  `entry-needs-refetch?`) and therefore need a client refetch under a live
  owner. Returns a vector of `{:resource-key :resource-id :reason}` entries
  (`:reason` one of `:metadata-only` / `:stale` / `:no-data`). The route
  slice consults this to issue `:rf.resource/refetch` (cause `:hydration`)
  for the entries its route plan still needs — fresh-with-data entries are
  ABSENT from the plan (no double-fetch). Per Spec 016 §SSR and hydration.

  PURE — the route slice / host drives the issuing; this is the decision."
  ([runtime-db] (hydrate-refetch-plan runtime-db (now-ms)))
  ([runtime-db clock-ms]
   (let [entries (get-in runtime-db [state/resources-key :entries])]
     (into []
           (comp
             (filter (fn [[_ entry]] (entry-needs-refetch? entry clock-ms)))
             (map (fn [[k entry]]
                    {:resource-key k
                     :resource-id  (nth k 1)
                     :reason       (cond
                                     (nil? (:data entry))         :no-data
                                     (entry-stale? entry clock-ms) :stale
                                     :else                         :metadata-only)})))
           entries))))

;; ---- install / publish ----------------------------------------------------

(defn install-ssr-integration!
  "Publish the LATE-BOUND SSR integration hooks (Spec 016 §SSR and
  hydration):

    - `:ssr/extend-runtime-db-projection` — SSR's `project-runtime-db`
      rides the durable resource `:entries` (per-entry redacted / omitted)
      on the hydration payload;
    - `:resources/hydrate-runtime-db` — the SSR `:rf/hydrate` handler
      reconciles the installed resource subtree (recompute indexes, orphan
      SSR owners, surface clock skew).

  No-op effect on an app that never SSRs — the hooks simply sit unread.
  Both are no-op on an SSR app WITHOUT resources (the projection contributes
  nothing for an empty entries map; the reconcile is a no-op for a
  resource-free runtime-db). Published from the `re-frame.resources` façade
  so a `:reload` re-wires them."
  []
  (late-bind/set-fns!
    {:ssr/extend-runtime-db-projection project-resources-runtime-db
     :resources/hydrate-runtime-db     hydrate-runtime-db})
  nil)

(defn hydrate-resources!
  "Install the allowed resource projection into the target frame-state's
  `:rf.runtime/resources` slice on client hydration — the direct,
  frame-targeted entry point for a host that drives hydration imperatively
  (the `:rf/hydrate` path uses the `:resources/hydrate-runtime-db` hook
  instead). Reconciles the frame's installed resource subtree
  (`hydrate-runtime-db`: preserve entries, recompute indexes, orphan SSR
  owners, surface clock skew) via the privileged runtime-db mutator, and
  returns the resulting refetch plan (`hydrate-refetch-plan`) so the caller
  can issue background refetches under live owners. Per Spec 016 §SSR and
  hydration (client hydration).

  `frame-id` is the explicit carried hydration target (EP-0002 — no ambient
  fallback). NEVER crosses scopes (the reconcile only touches the entries
  the server projected under their own scoped keys)."
  [frame-id]
  (let [rdb (frame/swap-runtime-db! frame-id hydrate-runtime-db frame-id)]
    (hydrate-refetch-plan (or rdb {}))))
