(ns re-frame.epoch.state
  "Shared state for the epoch surface — the six defonce atoms plus the
  low-level CRUD against them, and the config knob accessors. Per
  rf2-0wi86 (cohesion split): every other seam (`capture`, `assembly`,
  `write`, `listeners`) and the `re-frame.epoch` facade reach the
  shared atoms through this namespace; the atoms themselves stay
  `^:private` so cross-seam access is exclusively through the named
  helpers below.

  The atoms in question:

    config                  per-frame ring-buffer config + redact-fn
    histories               frame-id → vector<:rf/epoch-record>
    capture-buffers         frame-id → vector<trace-event> (in-flight)
    listeners               cb-id    → fn
    observed-frames-by-cb   cb-id    → #{frame-id ...}
    epoch-counter           monotonically-increasing :epoch-id source

  Per Phase-1 finding (rf2-0wi86): no two atoms are ever held in a
  single critical section, so a tiny state ns owns all of them with
  zero locking/ordering subtleties — the cross-cutting coupling is
  cosmetic, not structural.")

;; ---- configuration --------------------------------------------------------

(def ^:private default-depth
  ;; Deep enough to hold a typical debug session's cascade history;
  ;; trades bounded heap for stable time-travel coverage.
  50)

(def ^:private default-trace-events-keep
  ;; Per rf2-mrsck and Security.md §Epoch privacy posture: a finite
  ;; default that bounds dev-session heap growth from accumulated raw
  ;; cascade traces. The most-recent N records keep `:trace-events`;
  ;; older records keep only the cheap structured projections
  ;; (`:sub-runs` / `:renders` / `:effects`). Five matches the pair-
  ;; tool / Causa "what just happened?" working set — devs typically
  ;; care about the latest handful of cascades' raw streams; a deeper
  ;; ring depth is for time-travel reproducibility (`:db-after` is
  ;; cheap), not raw-trace inspection. Apps that genuinely need the
  ;; whole ring's traces can opt back in via
  ;; `(rf/configure :epoch-history {:trace-events-keep nil})` (or any
  ;; value >= the depth cap). Setting the slot to `0` drops every
  ;; record's `:trace-events`.
  5)

(defonce ^:private config
  ;; Three keys today (:depth, :trace-events-keep, :redact-fn). Map
  ;; shape kept open so future (rf/configure :epoch-history {...})
  ;; extensions don't break the shape. Per rf2-wp70d / Tool-Pair
  ;; §Time-travel §Redaction hook + Security.md §Epoch privacy
  ;; posture: :redact-fn defaults to nil — apps that record
  ;; sensitive material into app-db opt in by installing a fn.
  (atom {:depth             default-depth
         :trace-events-keep default-trace-events-keep
         :redact-fn         nil}))

(defn non-neg-int?
  "True for non-negative integer values; nil and non-numeric values
  fail. Mirrors the validation `re-frame.trace/configure-trace-buffer!`
  applies at its own config boundary."
  [x]
  (and (integer? x) (not (neg? x))))

(defn merge-config!
  "Validate and merge an `opts` map into the live config atom. Returns
  nil. Silently drops invalid slot values (`:depth` /
  `:trace-events-keep` must be non-negative integers; `:redact-fn`
  accepts `fn?` or `nil` for explicit-clear; anything else is dropped).

  Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: validation at the
  boundary keeps stored config sane — a `nil` or non-numeric value
  would otherwise survive into `record!` and explode at the next
  `pos?` / `nat-int?` call."
  [opts]
  (when (map? opts)
    (let [numeric (select-keys opts [:depth :trace-events-keep])
          numeric-valid (into {}
                              (filter (fn [[_ v]] (non-neg-int? v)))
                              numeric)
          ;; :redact-fn validated separately — accept fn? OR nil
          ;; (explicit-clear); anything else silently dropped.
          ;; `contains?` distinguishes 'absent slot' from 'present
          ;; nil' so the explicit-clear path lands while a callsite
          ;; that didn't mention :redact-fn doesn't clobber a
          ;; previously-installed fn.
          redact (when (contains? opts :redact-fn)
                   (let [v (:redact-fn opts)]
                     (when (or (nil? v) (fn? v))
                       {:redact-fn v})))
          valid (merge numeric-valid redact)]
      (when (seq valid)
        (swap! config merge valid))))
  nil)

(defn current-config
  "Return the current epoch-history configuration map."
  []
  @config)

(defn depth []
  (:depth @config default-depth))

(defn trace-events-keep []
  (:trace-events-keep @config default-trace-events-keep))

(defn redact-fn
  "Return the currently-installed `:redact-fn` (or nil). One config
  deref per record-build — the hot path for installed-fn cases is one
  keyword lookup, no allocation."
  []
  (:redact-fn @config))

;; ---- epoch-id counter -----------------------------------------------------

(defonce ^:private epoch-counter (atom 0))

(defn next-epoch-id []
  (swap! epoch-counter inc))

;; ---- per-frame ring buffer ------------------------------------------------
;;
;; Per Tool-Pair §Time-travel "Bounded history": last N epochs per frame.
;; Stored as a map of frame-id → vector (oldest-first). New records append
;; to the back; the front evicts when the buffer exceeds the configured
;; depth.

(defonce ^:private histories (atom {}))

(defn- elide-just-crossed-trace-events
  "When the record at index `(- (count history) keep 1)` crosses the
  keep-boundary, dissoc its `:trace-events`. O(1) per append: every
  earlier record was already elided on its own crossing, so the only
  record that needs work is the one that just slid out of the keep-
  window. Records keep their structured projections (`:sub-runs` /
  `:renders` / `:effects`) but lose the raw trace stream. nil `keep`
  means 'keep every record's :trace-events'.

  HOT PATH: invoked from `append-record` on every drain settle (every
  user-facing event). Pre-rf2-1e38x this rewrote the whole history
  vector via `map-indexed`; under steady state only one record per
  append actually transitions, so the O(n) walk was wasted work. The
  steady-state invariant holds because every prior append already
  elided its own just-crossed record; runtime reductions of `keep`
  via `(rf/configure :epoch-history ...)` will take full effect on
  subsequent appends rather than retroactively rewriting the buffer
  (pre-alpha posture)."
  [history keep]
  (let [n (count history)]
    (if (and (some? keep) (nat-int? keep) (> n keep))
      (let [idx (- n keep 1)
            r   (nth history idx)]
        (if (contains? r :trace-events)
          (assoc history idx (dissoc r :trace-events))
          history))
      history)))

(defn- append-record
  "Conj `record` onto the frame's history vector, cap to `d` via
  `subvec` (cheap structural reuse — no copy), then elide the just-
  crossed record's `:trace-events` per `keep`.

  HOT PATH: fires once per cascade settle, i.e. once per dispatched
  user event under steady state. Cost is O(1) in both the depth cap
  and the trace-events elision — the vector grows by one, optionally
  drops its leftmost element via `subvec`, and at most one record's
  `:trace-events` slot is dissoc'd."
  [history record d keep]
  (let [history+ (conj (or history []) record)
        n        (count history+)
        capped   (if (and (pos? d) (> n d))
                   (subvec history+ (- n d))
                   history+)]
    (elide-just-crossed-trace-events capped keep)))

(defn record!
  "Append a record into the frame's history. The depth cap and the
  `:trace-events-keep` cap are read from the config atom on each
  append so runtime `(rf/configure :epoch-history ...)` takes effect
  immediately."
  [record]
  (let [d    (depth)
        keep (trace-events-keep)]
    (when (pos? d)
      (let [frame-id (:frame record)]
        (swap! histories update frame-id append-record record d keep)))))

(defn history-for
  "Return the frame's history vector (oldest-first) or `[]`."
  [frame-id]
  (or (get @histories frame-id) []))

(defn drop-frame-history!
  "Drop the named frame's ring buffer."
  [frame-id]
  (swap! histories dissoc frame-id)
  nil)

;; ---- per-cascade capture buffer -------------------------------------------
;;
;; Per Tool-Pair §Per-cascade capture: the drain runs traces through
;; `re-frame.trace/emit!` which fans out to every registered listener.
;; An internal listener appends every event into a per-cascade buffer;
;; when the cascade settles, the buffer is harvested and projected into
;; the structured record slots. Keyed by frame-id so concurrent drains
;; across frames don't co-mingle. Within a frame, drain-execution is
;; single-threaded (Spec 002 §Run-to-completion).

(defonce ^:private capture-buffers
  ;; frame-id → vector of trace events (in arrival order)
  (atom {}))

(defn buffer-event!
  "Append `event` onto the frame's in-flight cascade buffer.

  HOT PATH: fires once per `trace/emit!` while a cascade is in flight,
  which is the dominant per-event cost (sub-runs, renders, fx, error
  emits all funnel here). O(1) swap! + (fnil conj []) — the buffer
  vector grows by one and is harvested wholesale at cascade settle
  via `harvest-buffer!`."
  [frame-id event]
  (swap! capture-buffers update frame-id (fnil conj []) event))

(defn buffer-for
  "Return the frame's in-flight capture buffer (vector) or `[]`. Used
  by the destroy hook to inspect buffered events before deciding
  whether to commit a `:halted-destroy` partial record."
  [frame-id]
  (get @capture-buffers frame-id []))

(defn harvest-buffer!
  "Atomically read-and-clear the frame's in-flight buffer."
  [frame-id]
  (let [b (get @capture-buffers frame-id [])]
    (swap! capture-buffers dissoc frame-id)
    b))

(defn drop-frame-buffer!
  "Drop the frame's in-flight capture buffer."
  [frame-id]
  (swap! capture-buffers dissoc frame-id)
  nil)

(defn reset-capture-buffers!
  "Wipe every in-flight capture buffer across all frames. Test fixtures
  use this in lockstep with `reset-histories!`.

  Per rf2-v0jwt: fixtures that sequence runs need a fresh capture
  state per fixture; a stale buffer from a previous fixture would
  otherwise be harvested into the next fixture's first cascade."
  []
  (reset! capture-buffers {})
  nil)

(defn reset-histories!
  "Wipe every frame's recorded epochs."
  []
  (reset! histories {})
  nil)

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))

;; Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656):
;; track which frames each cb has been delivered records for. When a
;; frame is destroyed, every cb whose observed-frames set contains
;; that frame receives a one-shot :rf.epoch.cb/silenced-on-frame-destroy
;; trace. The frame is then dropped from the cb's entry so a
;; re-registration of a same-keyed frame (e.g. `reset-frame! :app/main`)
;; can re-arm the silencing trace for a future destroy.
(defonce ^:private observed-frames-by-cb
  ;; cb-id → #{frame-id ...}
  (atom {}))

(defn put-listener!
  "Install or replace `f` under `id`. Also clears `id`'s
  observed-frames set so the new callback's silencing trace fires
  fresh against frames the new callback observes."
  [id f]
  (swap! listeners assoc id f)
  (swap! observed-frames-by-cb dissoc id)
  id)

(defn drop-listener!
  "Remove the listener registered under `id` and any observation
  bookkeeping it carried."
  [id]
  (swap! listeners dissoc id)
  (swap! observed-frames-by-cb dissoc id)
  nil)

(defn reset-listeners!
  "Drop every registered listener and clear all observation bookkeeping."
  []
  (reset! listeners {})
  (reset! observed-frames-by-cb {})
  nil)

(defn listeners-snapshot
  "Return the current `{cb-id → f}` map. Callers iterate this snapshot
  for fan-out — taking the snapshot once isolates iteration from
  concurrent `put-listener!` / `drop-listener!` updates."
  []
  @listeners)

(defn observations-snapshot
  "Return the current `{cb-id → #{frame-id ...}}` map."
  []
  @observed-frames-by-cb)

(defn record-observation!
  "Mark that the cb registered under `cb-id` has seen a record from
  `frame-id`. Guards against re-firing the atom watcher on the
  no-op case (cb already observes that frame) — for the common
  long-lived listener observing the same frame on every cascade,
  this is a single deref + membership check with no swap."
  [cb-id frame-id]
  (when frame-id
    (let [current @observed-frames-by-cb]
      (when-not (contains? (get current cb-id) frame-id)
        (swap! observed-frames-by-cb
               (fn [m]
                 (if (contains? (get m cb-id) frame-id)
                   m
                   (update m cb-id (fnil conj #{}) frame-id))))))))

(defn drop-frame-observation!
  "Drop `frame-id` from every cb's observed-frames set. cbs whose set
  goes empty as a result are dropped from the map entirely so the
  map doesn't accrete keys to empty sets."
  [frame-id]
  (swap! observed-frames-by-cb
         (fn [m]
           (reduce-kv (fn [acc cb-id frames]
                        (let [frames' (disj frames frame-id)]
                          (if (empty? frames')
                            (dissoc acc cb-id)
                            (assoc acc cb-id frames'))))
                      {}
                      m)))
  nil)
