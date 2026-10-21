(ns re-frame.machines.work
  "Work identity, and the work record each machine async completion emits.

  A spawned actor finishing on a `:final?` leaf, a `:spawn-all` child folding
  into its join, an `:after` timer firing or going stale, and a timer or actor
  cancelled before it finished each close one work attempt. The emit site
  builds one `record` for that completion and writes its facts as the
  `:rf.reply/*` tags of its trace, so tools correlate machine work by the same
  work id, status and stale reason as every other managed async family. The
  record is trace data only: the public statechart API (`:on-done`,
  `:on-error`, `:after`, the join protocol, actor destroy) never sees it, and
  the completion's payload travels on the parent's carrier event, never on
  the record. Spec 005 §Machine completions emit work records.

  Pure: no atoms, no dispatch, no I/O, no clock reads. The emit site reads the
  causal completion time and hands it in.")

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Work-id correlation (Managed-Effects §Work-id correlation).
;;
;; Machine head: `[:rf.work/machine actor-id work-bearing-path generation]`.
;;  - `actor-id`         — the finishing actor's INSTANCE id (the `<type>#<n>`
;;                         instance id, or an explicit `:fixed-actor-id`);
;;  - `work-bearing-path` — the `:spawn`-bearing node's declaring path
;;                         (the invocation path `:rf/invoke-id` the runtime
;;                         stamped on the child's `:data` at spawn time);
;;  - `generation`       — the monotonic spawn discriminator, so a re-spawn
;;                         under the same declaring path lands on a fresh work
;;                         id (one ATTEMPT, one `:rf.reply/work-id`, per
;;                         EP-0007). One rule covers both spawn kinds
;;                         (`spawn-work-generation`): a generated `<type>#<n>`
;;                         actor carries `n` (the spawn-counter reading that
;;                         minted this instance); an explicit `:fixed-actor-id`
;;                         names the SAME address on every attempt, so it
;;                         carries its spawn's runtime-minted attempt instead —
;;                         a single `:spawn` child's `:invoke-attempt`, a
;;                         `:spawn-all` child's join-attempt token. The spawn
;;                         applies the rule while its args say whether the id
;;                         was authored, and stamps the result on the child
;;                         (`[:rf/runtime :work-generation]`, or `:join-child`'s
;;                         `:work-generation`), so no reader decides provenance
;;                         from the actor keyword. Where no attempt is in hand
;;                         (a hand-emitted `:rf.machine/spawn` mints none), a
;;                         fixed actor carries 1.
;; ---------------------------------------------------------------------------

(defn- generated-actor-generation
  "Parse the generated spawn suffix off an `actor-id` of the form
  `<type>#<n>` (the instance id the spawn-counter mints — see
  `lifecycle-fx.spawn/allocate-actor-id-in-runtime-db`). Returns integer `n`
  only for a fully numeric suffix; fixed ids and malformed suffixes return nil."
  [actor-id]
  (let [nm (when (keyword? actor-id) (name actor-id))
        i  (when nm #?(:clj  (.lastIndexOf ^String nm "#")
                       :cljs (.lastIndexOf nm "#")))]
    (when (and i (nat-int? i) (pos? i))
      (let [suffix (subs nm (inc i))]
        ;; The CLJS branch must REJECT a non-fully-numeric suffix BEFORE
        ;; `js/parseInt`: `js/parseInt "3abc" 10` leniently returns 3, whereas
        ;; CLJ parsing throws. Gating both branches first keeps work identity
        ;; deterministic across platforms.
        (when (re-matches #"\d+" suffix)
          #?(:clj  (try (Long/parseLong suffix) (catch Exception _ nil))
             :cljs (let [x (js/parseInt suffix 10)] (when-not (js/isNaN x) x))))))))

(defn actor-generation
  "Parse the spawn generation off an `actor-id` of the form
  `<type>#<n>`. Returns integer `n`, or 1 when the id carries no valid numeric
  suffix (an explicit `:fixed-actor-id`). nil-safe: returns nil for a nil id.
  Public so the finalize path can read the live spawn-slot occupant's
  generation as the `:current` stale-spawn counterpart."
  [actor-id]
  (when (some? actor-id)
    (or (generated-actor-generation actor-id) 1)))

(defn spawn-work-generation
  "The exact work-id generation of one spawn attempt, chosen from the spawn's
  own provenance while its args are in hand. `fixed?` — the spawn authored a
  `:fixed-actor-id` — takes the runtime-minted `attempt`, because a fixed
  address is the same on every attempt; a generated actor takes the
  `<type>#<n>` counter `n` of `spawned-id`. Provenance never comes from the
  keyword's spelling, so a fixed literal ending in `#<digits>` still takes its
  attempt. The spawn stamps the result on the child, and the attempt's
  completion and cancel rows all read it from there."
  [fixed? spawned-id attempt]
  (if fixed?
    attempt
    (actor-generation spawned-id)))

(defn spawn-work-id
  "Build the machine work-id for one spawned-actor attempt:
  `[:rf.work/machine actor-id work-bearing-path generation]`
  (Managed-Effects §Work-id correlation). `actor-id` is the finishing actor's
  INSTANCE id; `work-bearing-path` is the `:spawn`-bearing node's declaring
  path (the child's stamped invocation path `:rf/invoke-id`). The 3-arity
  takes the runtime-owned exact generation: a `:spawn-all` child's private
  membership carries it (the allocator counter for a known generated actor, or
  the join attempt for an explicitly fixed actor, so the join path never
  decides provenance by parsing the actor keyword), and a single `:spawn`
  child's is the `[:rf/runtime :work-generation]` its spawn stamped
  (`spawn-work-generation`). The 2-arity, for a caller with no attempt in
  hand, parses `generation` off the `<type>#<n>` instance id, or 1 for an id
  with no such suffix. `=`-comparable and EDN-serializable."
  ([actor-id work-bearing-path]
   [:rf.work/machine actor-id (vec work-bearing-path)
    (actor-generation actor-id)])
  ([actor-id work-bearing-path work-generation]
   [:rf.work/machine actor-id (vec work-bearing-path)
    work-generation]))

(defn timer-work-id
  "Build the machine `:after` timer work-id for one timer attempt:
  `[:rf.work/timer logical-timer-id epoch]` (Managed-Effects §Work-id
  correlation — the Timer row). The `logical-timer-id` names THIS `:after`
  entry within the chart: its declaring path followed by the `:after` key it
  was declared under (`[actor-id decl-path... delay-key]` when an `actor-id`
  is known, else without it) — the identity the runtime timer table keys the
  timer by. Inside a parallel region `region` heads the region-relative
  `decl-path`, so two regions entering same-named leaves own distinct work
  ids. The key, never its resolved milliseconds, tells one node's `:after`
  entries apart: each is an independent timer, and two `:delays` names can
  resolve to the same milliseconds. An inline delay fn is not data, so it
  stands as `:fn`, its `:delay-source`. The `epoch` is the per-path `:after`
  epoch the timer was SCHEDULED in, which discriminates one timer attempt from
  a re-armed one on node re-entry (one attempt has one work id — EP-0007).
  `=`-comparable and EDN-serializable. `decl-path` nil (the node was exited —
  no live counterpart) yields `[:rf.work/timer actor-id epoch]`, and
  `[:rf.work/timer nil epoch]` when no `actor-id` is known either; each is a
  valid distinct id. The fired, stale and cancelled rows of one timer attempt
  all carry this id."
  [actor-id region decl-path delay-key epoch]
  (let [entry      (when (some? decl-path)
                     (cond-> (cond->> (vec decl-path) (some? region) (into [region]))
                       (some? delay-key) (conj (if (fn? delay-key) :fn delay-key))))
        logical-id (cond
                     (and (some? actor-id) (some? entry)) (into [actor-id] entry)
                     (some? entry)                        entry
                     :else                                actor-id)]
    [:rf.work/timer logical-id epoch]))

;; ---------------------------------------------------------------------------
;; Correlation — the data-only identity facts a record's `:correlation` holds.
;; ---------------------------------------------------------------------------

(defn actor-correlation
  "The correlation of a spawned actor's work: its INSTANCE id, plus the parent
  that spawned it and the spawn's declaring path when it has them."
  [actor-id parent-id work-bearing-path]
  (cond-> {:actor-id actor-id}
    (some? parent-id)         (assoc :parent-id parent-id)
    (some? work-bearing-path) (assoc :invoke-id (vec work-bearing-path))))

(defn timer-correlation
  "The correlation of an `:after` timer's work: the state it armed in, its
  delay in milliseconds, and its owning actor INSTANCE when known. The emit
  site adds the timer's suppression gate — `:gate` for a fired or cancelled
  timer, the `:carried` and `:current` pair for a stale one."
  [actor-id state delay]
  (cond-> {:state state :delay delay}
    (some? actor-id) (assoc :actor-id actor-id)))

(defn after-suppression-gate
  "The data-only suppression gate of an `:after` timer:
  `{:path <decl-path> :rf/after-epoch <epoch>}` (Managed-Effects §Stale
  suppression). A timer is LIVE iff its declaring path is still active AND its
  carried epoch equals the node's current per-path epoch; otherwise STALE. The
  timer handler decides that itself; the gate is the evidence a timer's
  correlation carries — one `:gate` for a fired or cancelled timer, and for a
  stale one the CARRIED gate (captured at scheduling, riding the synthetic
  timer event) beside the CURRENT gate (read from the live snapshot at
  expiry). `path` is nil when no declaring path is known."
  [decl-path epoch]
  {:path           (when decl-path (vec decl-path))
   :rf/after-epoch epoch})

;; ---------------------------------------------------------------------------
;; The work record (Spec 005 §Machine completions emit work records).
;; ---------------------------------------------------------------------------

(defn- work-kind
  "The work family a work id's head names. Throws on any other head, because
  a record that cannot say what kind of work it closes joins no row."
  [work-id]
  (case (first work-id)
    :rf.work/machine :machine
    :rf.work/timer   :timer))

(defn- work-status
  "The work status an attempt closes with when it finishes with `status`.
  Throws on a status outside the closed set a machine completion uses."
  [status]
  (case status
    :ok        :completed
    :error     :failed
    :stale     :suppressed
    :cancelled :cancelled))

(defn record
  "The work record of one machine completion: `:work-id`, the `:work-kind`
  its head names, the closed `:status` (`:ok`, `:error`, `:stale` or
  `:cancelled`) and the `:work-status` that status closes the attempt with.
  `facts` adds whichever of `:stale-reason`, `:cancel-reason`, `:correlation`
  and `:completed-at` the completion has; a nil fact is left out rather than
  nil-filled."
  ([work-id status] (record work-id status nil))
  ([work-id status facts]
   (into {:work-id     work-id
          :work-kind   (work-kind work-id)
          :status      status
          :work-status (work-status status)}
         (remove (comp nil? val))
         facts)))
