(ns day8.re-frame2-xray.trace-collector
  "Xray's trace-event listener body — the consumer-side surface that
  bridges the framework's per-frame trace rings into Xray's reactive
  app-db.

  `collect-trace!` drops Xray self-noise, applies the local egress
  profile, retains otherwise-unaddressable frameless events, and requests
  a coalesced mirror refresh. One refresh per scheduled task snapshots
  every registered frame ring plus the bounded frameless ring into
  `[:trace-buffer]` in Xray's app-db.

  Frameless events have no frame or dispatch id, so the framework's
  per-frame rings cannot retain them. The secondary ring exists only to
  support the opt-in `:show-ungrouped?` view. All addressed events remain
  owned by the framework rings; Xray does not maintain a duplicate global
  trace store.

  Narrowing the egress profile also scrubs the framework rings, frameless
  ring, and app-db mirror before another read can expose old raw values.

  ## Production posture

  Every entry point in this ns is gated on
  `re-frame.interop/debug-enabled?`, so `:advanced` + `goog.DEBUG false`
  folds away the collector's bodies and the top-level registration.
  Listener registration lives in `install.cljs` and is invoked by the
  preload under the same gate — but `core/init!` invokes it with no
  gate, and Xray as a whole stays out of a release build by build
  placement rather than by this self-gate. See
  `spec/013-Trace-Consumer.md` §Production posture."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.self-noise :as self-noise]))

;; ---- frameless secondary ring -------------------------------------------
;;
;; Frameless trace events — registry-time emits, REPL evals, frame
;; lifecycle outside a drain, SSR hydration mismatches that fire before
;; any `:dispatch-id` is in scope — carry no `:rf.trace/dispatch-id` or
;; `:frame`. The framework's per-frame rings skip them; listeners still
;; receive them. Xray keeps a small, capped ring at that boundary so the
;; opt-in `:show-ungrouped?` view can surface them. The depth is bounded
;; (default 100 events; framework's per-frame default is 50 event-bundles)
;; because frameless events are rare in a healthy app — most happen at
;; boot or during a REPL session. A bigger buffer would buy little
;; signal.

(def default-frameless-ring-depth
  "Default capacity of the frameless secondary ring (in events). Capped
  small because frameless emits are rare and hidden unless
  `:show-ungrouped?` is enabled."
  100)

(defonce ^:private frameless-ring-depth
  ;; Mutable so future Settings UX can tune this without re-evaluating
  ;; the constant. Same shape as the framework's per-process depth
  ;; (`re-frame.trace.tooling/process-events-retained`).
  (atom default-frameless-ring-depth))

(defonce ^:private frameless-ring
  ;; Oldest entries at the head; conj appends, subvec evicts the head.
  ;; Vector under an atom matches the same primitive the per-frame rings
  ;; use inside `re-frame.trace.tooling/trace-rings`.
  (atom []))

(defn- push-frameless!
  "Append `event` to the frameless ring, evicting the oldest entry when
  the count exceeds the configured depth."
  [event]
  (let [depth @frameless-ring-depth]
    (swap! frameless-ring
           (fn [ring]
             (let [ring' (conj ring event)
                   n     (count ring')]
               (if (> n depth)
                 (subvec ring' (- n depth))
                 ring'))))))

(defn frameless-events
  "Return the frameless secondary ring's current contents, oldest-first.
  Empty in production (the listener never receives events when
  `rf.interop/debug-enabled?` is false at compile time)."
  []
  @frameless-ring)

(defn clear-frameless-ring!
  "Drop every entry in the frameless secondary ring. Test fixtures + the
  retroactive privacy scrub call this; the secondary ring participates
  in the same lifecycle as the framework's per-frame rings."
  []
  (reset! frameless-ring [])
  nil)

(defn set-frameless-ring-depth!
  "Set the frameless secondary ring's depth (in events). Test fixtures
  use this to drive overflow behaviour without churning a full
  framework runtime. Production tuning is via the Settings UX once the
  knob is wired through.

  Shrinking the depth below the current ring's contents drops the head
  (oldest) entries immediately so the snapshot reflects the new depth
  on the next read."
  [depth]
  (when (and (number? depth) (not (neg? depth)))
    (reset! frameless-ring-depth depth)
    (swap! frameless-ring
           (fn [ring]
             (let [n (count ring)]
               (cond
                 (zero? depth) []
                 (> n depth)   (subvec ring (- n depth))
                 :else         ring)))))
  nil)

(defn- frameless-event?
  "True when `event` carries neither a frame nor a `:rf.trace/dispatch-id`
  under `:tags` — the framework's per-frame rings would have skipped it
  (per the B3 ruling) and Xray's secondary ring is the only place it can
  be retained. Reads the RAW trace-event frame via the canonical reader
  `re-frame.trace/trace-event-frame` ([:tags :frame] — rf2-7737vq); the
  prior divergent top-level `:frame` check is removed (raw events never
  carry a top-level `:frame`)."
  [event]
  (and (nil? (rf.trace/trace-event-frame event))
       (nil? (get-in event [:tags :rf.trace/dispatch-id]))))

;; ---- task-coalesced mirror sync (D3=b ruling) --------------------------
;;
;; Production hosts drive the reactive surface by snapshotting the
;; framework's per-frame rings + the frameless secondary ring into
;; Xray's app-db's `:trace-buffer` slot once per JS tick. Same-tick
;; listener callbacks request a sync; one scheduled task drains the
;; queue with a single snapshot dispatch — capping the event-bundle depth
;; at 1 regardless of host trace-event volume. The pre-rf2-43koh shape
;; used the same coalescer; what changed is the SOURCE (per-frame rings
;; instead of the retired Xray ring atom).
;;
;; The scheduling primitive is `re-frame.interop/next-tick`, which runs
;; the refresh ASYNCHRONOUSLY, AS A TASK — never a host microtask, never
;; inline. That boundary is the whole guarantee it makes, and it is the
;; one this coalescer relies on: the requesting listener callbacks all
;; land, then the single refresh runs. NOT guaranteed, and not depended
;; on here: WHICH task mechanism runs the callback (Closure picks per
;; host — `setImmediate`, a `MessageChannel`/`postMessage` emulation, or
;; a `setTimeout(cb, 0)` fallback), hence no latency bound on when the
;; mirror lands. Coalescing is correct on every one of those paths — a
;; later boundary merges MORE requests, never fewer.
;;
;; Tests bypass the scheduler by calling `refresh-trace-rings!`
;; directly (the D3=b sync entrypoint), getting a deterministic snap
;; without waiting for `goog.async.nextTick`.

(defonce ^:private mirror-sync-scheduled?
  ;; `compare-and-set!` sentinel — `true` while a refresh task is queued
  ;; and not yet drained; reset to `false` immediately before the queued
  ;; tick reads the snapshot, so a request that arrives after the read
  ;; enqueues a fresh tick rather than merging silently with one already
  ;; in flight.
  (atom false))

(defn snapshot-from-rings
  "Materialise every registered HOST frame's flat trace events + the
  frameless secondary ring into one oldest-first vector. Pure-data;
  reads from the framework's per-frame rings via
  `re-frame.trace.tooling/trace-buffer` with `{:flat true}` (Spec 009
  §`trace-buffer` API) and concatenates the frameless ring's contents,
  sorted by `:id`.

  Tool frames (`:rf/xray`, `:rf/re-frame2-pair`) are filtered — the
  same self-noise discipline the `xray-internal-event?` predicate and
  the `:rf.trace/frame-no-emit?` framework gate apply at the listener
  + emit-time boundaries. Reading from tool-frame rings here would
  surface Xray's own machinery in the user-facing event-bundle list. Pre-
  alpha posture: drop unconditionally; tool-frame introspection is a
  separate feature surface if needed (rf2-43koh consumer substrate).

  ## Privacy gate (rf2-0ax6f)

  The framework's per-frame rings RETAIN every emitted event with no
  `:sensitive?` check (`re-frame.trace.tooling/push-to-ring!`) — the
  ring is a faithful record of what the runtime emitted. Xray's
  documented policy (Spec 009 §Privacy) is to SUPPRESS THE WHOLE EVENT
  while the local-render egress profile does not reveal sensitive values
  (the `:rf.egress/local-redacted` default — `config/include-sensitive?`
  false), because non-marked envelope slots can structurally reveal a
  redacted value. So the read
  side — not the ring — is where the gate must live for frame-bound
  events: `collect-trace!` already drops sensitive events on the
  listener path, but it cannot stop the per-frame ring from retaining
  them, and a later non-sensitive event's mirror-sync would otherwise
  pull the retained sensitive event back into the snapshot. We apply
  `config/suppress-sensitive?` to BOTH the per-frame events and the
  frameless ring here so the snapshot is scrubbed regardless of
  frame-bound vs frameless origin — genuinely symmetric with the
  listener gate. The retroactive scrub (profile narrowed
  `:rf.egress/local-raw` → redacting default) still clears the rings
  wholesale; this gate covers the steady-state read while the profile
  redacts.

  Public so `mount.cljs` can drive the first-mount seed
  synchronously alongside the `:rf.xray/sync-trace-buffer` dispatch,
  bypassing the task coalescer (the seed must commit before the
  first paint reads `:rf.xray/event-bundles`)."
  []
  (let [tool-frames #{:rf/xray :rf/re-frame2-pair}
        frame-ids   (remove tool-frames (rf.frame/frame-ids))
        per-frame   (into [] (mapcat (fn [fid]
                                       (rf/trace-buffer fid {:flat true})))
                          frame-ids)
        frameless   (frameless-events)
        all         (into per-frame frameless)]
    (into []
          ;; Privacy gate (rf2-0ax6f): scrub retained-but-sensitive
          ;; events on the read side so the snapshot never leaks an
          ;; event the listener gate already suppressed. No-op when the
          ;; local-render egress profile reveals sensitive values
          ;; (`:rf.egress/local-raw` opt-in) or the event is non-sensitive.
          (remove config/suppress-sensitive?)
          ;; Sort by :id so the resulting flat vector preserves the
          ;; cross-frame oldest-first invariant the projection code +
          ;; the L2 event list both assume. Frameless events carry no
          ;; :id only in pathological cases (host produced an envelope
          ;; without one); fall back to js/Number.MAX_SAFE_INTEGER so they
          ;; sort to the tail.
          (sort-by (fn [ev] (or (:id ev) js/Number.MAX_SAFE_INTEGER))
                   all))))

(defn refresh-trace-rings!
  "Synchronously snapshot every per-frame ring + the frameless secondary
  ring into Xray's app-db's `:trace-buffer` slot.

  PRIMARY USES:
    - Tests (per the rf2-3g9nw D3=b ruling): a sync entrypoint that
      deterministically aligns Xray's reactive surface against the
      framework's rings after each host dispatch, bypassing the
      task coalescer.
    - The `mount.cljs` first-mount seed (lifts pre-mount event-bundles into
      the app-db slot at first Ctrl+Shift+C).
    - The retroactive privacy scrub (post-clear, the rings + secondary
      ring are both empty so the snapshot also lands empty).

  PRE-MOUNT BEHAVIOUR: when `:rf/xray` is not yet registered the
  dispatch is a silent no-op — the per-frame rings keep accumulating
  and the snapshot lands on first mount via the same path.

  Returns nothing."
  []
  (when rf.interop/debug-enabled?
    (when (some? (rf.frame/frame defaults/default-frame-id))
      (let [snapshot (snapshot-from-rings)]
        (rf/with-frame defaults/default-frame-id
          (rf/dispatch-sync [:rf.xray/sync-trace-buffer snapshot])))))
  nil)

(defn- request-mirror-sync!
  "Schedule a coalesced sync into `:rf/xray`'s `:trace-buffer` slot.
  Same-tick callers collapse to a single dispatch carrying the snapshot
  drawn from every registered frame's ring + the frameless secondary
  ring. Caps the event-bundle depth at 1 regardless of trace volume; the
  router's drain-depth headroom cannot gate the mirror under
  saturation."
  []
  (when (compare-and-set! mirror-sync-scheduled? false true)
    (rf.interop/next-tick
      (fn []
        (reset! mirror-sync-scheduled? false)
        (refresh-trace-rings!)))))

;; ---- collector ----------------------------------------------------------

(defn collect-trace!
  "Trace-event listener body. Registered under
  `:rf.xray/trace-collector` (`preload.cljs/register-trace-collector!`).

  Order of operations:
    1. Drop Xray-internal events (frame = `:rf/xray`) — the
       `re-frame.trace/emit!` source-side gate covers most of these,
       but the listener belt-and-braces against any that slipped past.
    2. Apply the privacy gate — `:sensitive?` events bump the
       suppressed counter and skip the frameless secondary ring + the
       mirror-sync request. The framework's per-frame ring does NOT
       honour the gate (it retains every emitted event); the matching
       read-side gate in `snapshot-from-rings` scrubs those retained
       events so the two halves are genuinely symmetric (rf2-0ax6f).
    3. Frameless events feed the Xray-side secondary ring (per the
       rf2-3g9nw D2=a ruling).
    4. Frame-bound events: no Xray-side push needed — the framework's
       per-frame ring (`re-frame.trace.tooling`) already captured them.
       We schedule a coalesced mirror sync so the next scheduled task
       refreshes Xray's app-db's `:trace-buffer` slot.

  No-op in production (the framework's listener fan-out elides under
  `rf.interop/debug-enabled?` false; the entry point also short-circuits).

  Per Spec 009 §Privacy + spec/013-Trace-Consumer.md."
  [event]
  (when rf.interop/debug-enabled?
    (cond
      ;; rf2-xs8vu — drop Xray's own machinery before anything else.
      ;; Self-emitted sub-reads / view-renders from Xray's own panels
      ;; would otherwise drown the host event in `:ungrouped` noise.
      (self-noise/xray-internal-event? event)
      nil

      ;; Spec 009 §Privacy — drop sensitive events while the local-render
      ;; egress profile redacts (the `:rf.egress/local-redacted` default);
      ;; bump the per-frame suppressed counter so the `[● REDACTED N]`
      ;; indicator surfaces. EP-0015 per-(tool,frame) reveal grain.
      (config/suppress-sensitive? event)
      (config/note-suppressed! (rf.trace/trace-event-frame event))

      :else
      (do
        ;; Frameless events: the framework's per-frame rings skipped
        ;; this one (B3 ruling) — push to our 100-event secondary ring
        ;; so the `:show-ungrouped?` UX can surface it.
        (when (frameless-event? event)
          (push-frameless! event))
        ;; In every non-frameless case the framework already retained
        ;; the event in its per-frame ring; nothing to do but request a
        ;; coalesced sync so Xray's app-db slot mirrors the rings on
        ;; the next scheduled task.
        (request-mirror-sync!))))
  nil)

;; ---- retroactive scrub on toggle-off (rf2-lqmje, D5=a re-home) ---------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub: narrowing the local-render
;; egress profile from a sensitive-revealing boundary
;; (`:rf.egress/local-raw`) back to the redacting default MUST clear every
;; place a sensitive event could live. The reveal is NOT a one-way
;; trapdoor — a sensitive event-bundle buffered while the raw profile was active
;; would otherwise remain visible after the user expected privacy restored.
;;
;; Three places hold trace data post-rf2-43koh:
;;   1. The framework's per-frame rings — clear via
;;      `(rf/clear-trace-buffer!)`.
;;   2. The Xray secondary frameless ring — clear via
;;      `(clear-frameless-ring!)`.
;;   3. Xray's app-db `:trace-buffer` slot — clear via
;;      `:rf.xray/clear-trace-buffer` (registered in `registry.cljs`).
;;
;; All three are dropped together. The suppressed-counters reset moves
;; alongside so the `[● REDACTED N]` indicator disappears in lockstep
;; (clearing the buffer is the natural moment to drop the
;; \"you missed N events\" overhang).
;;
;; The framework clear is the 0-arity DATA clear, not the fixture-grade
;; `clear-trace-rings!` (rf2-kuky.54). The scrub's subject is retained
;; *events*; it deliberately does NOT reset the user's configured
;; `(rf/configure! {:trace-buffer {:events-retained N}})` retention, any
;; frame's explicit override, or the hot-reload registration dedup table —
;; the Settings popup writes that very retention knob, so a scrub that reset
;; it would silently revert the user's own setting.

(defn retroactive-scrub!
  "Wholesale clear: per-frame rings + frameless secondary ring + Xray's
  app-db slot + the suppressed-counters. Called from the
  toggle-off-callback registered below, AND from the Settings popup's
  \"Clear buffer now\" affordance.

  Retention policy survives: the framework clear is the 0-arity data
  clear, so the user's configured `:events-retained` and every per-frame
  override stay in force (rf2-kuky.54).

  Production no-op (`rf.interop/debug-enabled?` gates every mutation
  point inside the framework + Xray paths)."
  []
  (when rf.interop/debug-enabled?
    (rf/clear-trace-buffer!)
    (clear-frameless-ring!)
    (when (some? (rf.frame/frame defaults/default-frame-id))
      (rf/with-frame defaults/default-frame-id
        (rf/dispatch [:rf.xray/clear-trace-buffer])))
    (config/reset-suppressed-count!))
  nil)

;; ---- toggle-off registration ------------------------------------------
;;
;; `config.cljc`'s `set-egress-profile!` walks the registered toggle-off
;; callbacks on every reveal → redact narrowing. The listener body's
;; ingest filter already drops `:sensitive?` events while the profile
;; redacts; this callback handles the case where the profile was
;; `:rf.egress/local-raw` and is now narrowed back — every event that was
;; retained between reveal and narrow is purged in one wholesale clear.
;;
;; Gated on `rf.interop/debug-enabled?` so production builds elide the
;; registration alongside the rest of the collector surface.

(when rf.interop/debug-enabled?
  (config/register-toggle-off-callback! ::scrub-on-toggle-off retroactive-scrub!))

;; ---- test affordances --------------------------------------------------

(defn seed-trace-for-test!
  "Push `event` straight into the frameless secondary ring AND
  synchronously refresh Xray's app-db `:trace-buffer` slot so the
  next `:rf.xray/trace-buffer` sub-read sees the seeded event.

  Test-only. Bypasses every ingest gate (self-noise filter, privacy
  gate, debug-enabled? short-circuit). Callers that want the public
  ingest path use `collect-trace!`; callers that want the framework's
  per-frame ring populated naturally use a real `rf/dispatch-sync` so
  the framework's trace pipeline runs end-to-end. This helper is the
  shortest path to put a synthetic event in the projection pipeline
  for testing panels, filters, and the event-bundle list — without
  spinning a full host runtime.

  When `:rf/xray` is registered, also refreshes the app-db slot
  synchronously (per the rf2-3g9nw D3=b ruling: tests get a sync
  entrypoint that bypasses the task coalescer). Pre-mount
  callers see the seed land in the ring; the first mount-time
  refresh lifts it into the slot.

  Lifted from the retired `trace-bus/seed-buffer-for-test!` per
  rf2-43koh. Pure mutation; no privacy / no self-noise / no debug
  gate — strictly for assembling buffer fixtures in tests."
  [event]
  (push-frameless! event)
  (refresh-trace-rings!)
  nil)

(defn buffer-for-test
  "Return the current snapshot every consumer would see — the same
  vector `refresh-trace-rings!` would push into Xray's app-db slot.
  Test-only.

  Returns the merged oldest-first vector across every registered
  frame's per-frame ring + the frameless secondary ring. Replaces
  `trace-bus/buffer` for tests."
  []
  (snapshot-from-rings))

(defn reset-for-test!
  "Reset every piece of mutable state this ns owns — the frameless ring,
  the mirror-sync sentinel — AND every per-frame ring the framework
  owns (via `rf.trace.tooling/clear-trace-rings!`). Test fixtures call
  this between assertions so cross-test bleed (process-global atoms)
  doesn't pollute the surface."
  []
  (reset! frameless-ring [])
  (reset! mirror-sync-scheduled? false)
  (rf.trace.tooling/clear-trace-rings!)
  nil)
