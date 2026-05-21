(ns day8.re-frame2-causa.drop-in
  "Drop-in mode for non-re-frame2 hosts (rf2-1o9cq, v1.1).

  Causa's primary integration is as a dev-tool inside a re-frame2 app:
  the host's `re-frame.trace/emit!` calls fan trace events out to
  Causa's collector via `register-listener!` (see
  `tools/causa/spec/013-Trace-Bus.md`). Every panel reads from the
  ring buffer those events land in.

  This namespace lets a host that is NOT a re-frame2 app reach the
  same buffer. Concretely: an XState app, a Redux app with a logger
  middleware, a Zustand app with a devtools subscription, a custom
  state-machine harness — anything that can produce events shaped
  per [`spec/Spec-Schemas.md` §`:rf/trace-event`](../../../../spec/Spec-Schemas.md#rftrace-event)
  — can plug into Causa by attaching its trace source here. The
  panels (Event detail, Trace, App-db diff, machine inspector, …)
  light up against the host's events exactly as they would against a
  native re-frame2 cascade.

  ## What the host MUST supply

  Per [`tools/causa/spec/013-Trace-Bus.md` §Inputs](../spec/013-Trace-Bus.md)
  the buffer holds canonical `:rf/trace-event` maps. The minimum
  field set the drop-in accepts:

      :id        — any (typically a number; if absent, drop-in fills
                   from its own monotonic counter so cursor-based
                   filtering (`:since`) still works against host
                   events alone)
      :operation — keyword (what this trace describes)
      :op-type   — keyword (open vocabulary; see Spec 009 §`:op-type`
                   vocabulary for the canonical reserved values)
      :time      — any (host clock; if absent, drop-in fills with
                   `(re-frame.interop/now-ms)`)
      :tags      — optional `{<keyword> <any>}` payload (carries
                   `:frame`, `:event-id`, `:dispatch-id`, `:source`,
                   `:origin`, `:handler-id`)

  Hosts MAY add additional fields; Causa's filter vocabulary
  (`trace-bus/filter-events`) reads only what it knows about.

  ## The three attach modes

  `attach!` accepts a `:trace-source` argument in three shapes:

  1. **Push mode** — `(attach! {:mode :push})` returns immediately
     and the host calls `(drop-in/emit! event)` for each event.
     This is the simplest mode and the right default when the host
     produces events synchronously inside an existing callback (an
     XState `subscribe` cb, a Redux middleware, etc.).

  2. **Atom mode** — `(attach! {:trace-source <atom-ref>})`. The
     drop-in `add-watch`es the atom; every conj / assoc that grows
     the vector / collection pushes the new tail entries into the
     buffer. Useful when the host already maintains a log-shaped
     atom and Causa just needs to mirror it.

  3. **Subscribe mode** — `(attach! {:trace-source <register-fn>})`
     where `register-fn` is `(fn [emit-cb] -> unregister-fn)`. The
     drop-in calls the fn with its own collector callback; the host
     calls the cb for each event; the host's returned fn detaches
     the subscription when `(detach!)` is called.

  All three modes funnel through the same internal
  `collect-from-host!` path so the buffer + the privacy filter +
  the coalesced mirror dispatch behave identically.

  ## What the drop-in does NOT do

  - It does NOT register a `re-frame.trace/register-listener!`
    callback. The host is by definition not using the framework's
    trace emit path; there is nothing to listen to.
  - It does NOT change Causa's ingest filters: the privacy gate
    still suppresses `:sensitive? true` events unless the host has
    opted in via `(causa-config/configure! {:rf.privacy/show-sensitive?
    true})`.
  - It does NOT mutate the host's state. The host owns its event
    source; the drop-in is a one-way pump from there into Causa's
    buffer.

  ## Production posture

  Like the rest of Causa, every entry point is gated on
  `re-frame.interop/debug-enabled?` — production builds elide the
  drop-in's pushes through Closure DCE. Hosts MAY call `attach!`
  unconditionally; in production it is a silent no-op.

  Pre-alpha posture: no back-compat shims, no legacy spellings. The
  API is a single arity-1 `attach!` taking an options map. Future
  modes (binary trace decoders, transit-over-websocket, etc.) add
  keys here additively."
  (:require [re-frame.interop :as interop]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- internal counter for auto-`:id` ------------------------------------
;;
;; Hosts that don't number their own events still want cursor-based
;; filtering (the `:since` axis on `trace-bus/filter-events`) to work
;; — otherwise the L2 event list, the `:rf.causa/event-detail` hero,
;; and every other panel that diffs the buffer by id would lose its
;; ability to point at a specific event. The drop-in keeps its own
;; monotonic counter, namespaced under `:rf.causa.drop-in/id-counter`
;; so a producer that also numbers its events doesn't collide
;; (host-supplied `:id` always wins).
;;
;; The counter is a single defonce shared by every attached source —
;; consistent with the framework's `re-frame.trace/event-counter`
;; which is a single defonce across every emit site.

(defonce ^:private id-counter (atom 0))

(defn- next-id! []
  (swap! id-counter inc))

;; ---- normalisation -------------------------------------------------------

(defn normalise-event
  "Return `event` augmented with any drop-in-supplied defaults the host
  omitted. The buffer's consumer panels expect at minimum `:id`,
  `:operation`, `:op-type`, and `:time`; if the host omits the
  per-process counters (`:id`) or wall-clock (`:time`), the drop-in
  fills them so cursor-based filtering and chronological sorting both
  keep working.

  Host-supplied values always win. `:operation` and `:op-type` are
  NOT defaulted — these are the per-emit-site discriminator pair
  consumers branch on, and silently defaulting them would mask host
  bugs. The drop-in pushes the event as-is in that case and the
  consumer panels treat it as `:operation nil :op-type nil` (the
  event lands in the buffer but every keyword-filter misses).

  Pure data; JVM-runnable so the fill algebra is testable without a
  CLJS runtime."
  [event]
  (cond-> event
    (nil? (:id event))   (assoc :id   (next-id!))
    (nil? (:time event)) (assoc :time (interop/now-ms))))

(defn- valid-event?
  "Cheap shape predicate — `event` is a map. The buffer is best-effort:
  invalid events are dropped at the door rather than crashing the
  collector. Returning false here is the only place the drop-in
  silently swallows host input, so the bar is deliberately low — we
  drop nils and non-maps; anything else flows through to the buffer
  and the panel-side filters cope with shape mismatches gracefully
  (an event missing `:operation` reads as nil through `trace-bus/
  filter-events`).

  Per [`spec/Spec-Schemas.md` §`:rf/trace-event`](../../../../spec/Spec-Schemas.md#rftrace-event)
  the canonical shape is a map; non-maps cannot satisfy the schema
  by construction."
  [event]
  (map? event))

;; ---- collector -----------------------------------------------------------

(defn collect-from-host!
  "Normalise + push one host-supplied trace event into Causa's buffer.

  Goes through `trace-bus/collect-trace!` (the public ingest path) so
  the privacy gate, the coalesced mirror dispatch, and the
  debug-enabled? gate all apply uniformly. The `:rf/causa` self-noise
  filter in `collect-trace!` is a true predicate — Causa-internal
  events carry `:frame :rf/causa`; host events cannot — so the filter
  is structurally a pass-through on this code path.

  Returns nothing. No-op when `event` is not a map (see
  `valid-event?`) or when the build is production (the `collect-
  trace!` body short-circuits on `interop/debug-enabled?` false)."
  [event]
  (when (valid-event? event)
    (trace-bus/collect-trace! (normalise-event event)))
  nil)

;; ---- attach-state --------------------------------------------------------
;;
;; The drop-in is single-source: attaching a second source replaces the
;; first. This keeps the contract simple — the buffer has one host;
;; multi-source aggregation belongs upstream (the host can fold
;; multiple producers into one source before attach). The state map
;; holds whatever is needed to undo the current attachment.
;;
;; Shape:
;;   {:mode :push   :detach-fn nil}                ;; nothing to undo
;;   {:mode :atom   :detach-fn <fn>}               ;; remove-watch fn
;;   {:mode :sub    :detach-fn <fn>}               ;; host-returned fn
;;
;; nil = not attached.

(defonce ^:private attached-state (atom nil))

(defn attached?
  "True iff a host trace source is currently attached. Returns nil
  before the first `attach!` call and after `detach!`. Useful for
  hosts that want to attach idempotently at boot."
  []
  (some? @attached-state))

(defn current-mode
  "Return the currently-attached mode (`:push`, `:atom`, or `:sub`)
  or nil. Diagnostic surface only — tests + the runtime accessor read
  this to confirm an attachment took the shape the host expected."
  []
  (:mode @attached-state))

;; ---- attach-mode dispatch ------------------------------------------------

(defn- attach-push!
  "Push-mode attach. No external subscription — the host calls
  `(drop-in/emit! event)` directly per event. The state slot just
  records the mode so `current-mode` can answer; there is no
  detach-fn to install."
  []
  {:mode :push :detach-fn nil})

#?(:cljs
   (defn- atom-tail-since
     "Return the elements of `new-val` (sequential) that appear after
     `old-val`. The drop-in watches a host atom for monotonic
     appends; we pump only the new tail rather than replaying the
     whole buffer on every change. The implementation is intentionally
     simple: if the new value is a sequential collection and grew,
     pump the suffix; otherwise pump nothing (we don't try to be
     clever about set-shaped or map-shaped logs — atom mode is for
     vector / list-shaped append-only logs).

     `old-val` may be nil on first watch fire — in that case pump
     the whole `new-val` (treat the initial contents as the first
     batch). This makes a host that seeds its log atom BEFORE
     calling `attach!` get those seed events on the first
     `swap!`-after-attach without an extra ceremony."
     [old-val new-val]
     (cond
       (not (sequential? new-val)) nil
       (nil? old-val)              new-val
       :else
       (let [old-n (count old-val)
             new-n (count new-val)]
         (when (> new-n old-n)
           (drop old-n new-val))))))

#?(:cljs
   (defn- attach-atom!
     "Atom-mode attach. Watch the host atom; on every change, pump the
     newly-appended tail into the buffer. The watch key is namespaced
     under `:rf.causa.drop-in/atom-watch` so it doesn't collide with
     watches the host installed for its own reasons.

     Returns the state map carrying a detach-fn that removes the
     watch."
     [host-atom]
     (let [k ::atom-watch]
       (add-watch host-atom k
                  (fn [_ _ old-val new-val]
                    (doseq [ev (atom-tail-since old-val new-val)]
                      (collect-from-host! ev))))
       ;; Pump the atom's CURRENT contents on attach so the host
       ;; doesn't lose the events that landed before the watch fired.
       ;; Mirror of `mount.cljs/ensure-causa-frame!` seeding from
       ;; the trace-bus atom; the principle is the same — at first
       ;; consumer-side attachment, project the producer's current
       ;; snapshot.
       (doseq [ev (atom-tail-since nil @host-atom)]
         (collect-from-host! ev))
       {:mode      :atom
        :detach-fn (fn [] (remove-watch host-atom k))})))

(defn- attach-subscribe!
  "Subscribe-mode attach. Call `register-fn` with our collector
  callback; record the returned unregister-fn so `detach!` can
  invoke it. Pre-alpha posture — if the host returns a non-fn (a
  rare contract violation), the detach path no-ops; we don't try to
  validate harder than that."
  [register-fn]
  (let [unregister-fn (register-fn collect-from-host!)]
    {:mode      :sub
     :detach-fn (when (fn? unregister-fn) unregister-fn)}))

;; ---- public API ----------------------------------------------------------

(defn detach!
  "Undo the current attachment. Idempotent — a no-op when nothing is
  attached. Returns nothing. Production-elided via the same
  `interop/debug-enabled?` gate Causa's other side-effects share."
  []
  (when interop/debug-enabled?
    (when-let [{:keys [detach-fn]} @attached-state]
      (when (fn? detach-fn)
        (try
          (detach-fn)
          (catch #?(:clj Throwable :cljs :default) _ nil)))
      (reset! attached-state nil)))
  nil)

(defn attach!
  "Attach a host trace source to Causa's buffer. Pre-alpha — only one
  source attaches at a time; calling `attach!` while already attached
  silently `detach!`es the previous one first.

  Recognised opts keys:

      :mode          — `:push` (default) | `:atom` | `:sub`
      :trace-source  — only required for `:atom` and `:sub`:
                       :atom — a host-managed atom holding a
                         sequential append-only event log
                       :sub  — `(fn [emit-cb] -> unregister-fn)`
                         where the host calls `emit-cb` for each event
                         and the returned fn detaches the subscription

  Examples:

      ;; Push mode — host calls (drop-in/emit! event) per event
      (drop-in/attach! {:mode :push})
      (drop-in/emit! {:operation :xstate/transition
                      :op-type   :machine
                      :tags      {:from :idle :to :playing}})

      ;; Atom mode — drop-in watches a host log atom
      (def host-log (atom []))
      (drop-in/attach! {:mode :atom :trace-source host-log})
      (swap! host-log conj {:operation :redux/action
                            :op-type   :event
                            :tags      {:action-type \"INCREMENT\"}})

      ;; Subscribe mode — drop-in registers a callback with the host
      (drop-in/attach! {:mode :sub
                        :trace-source (fn [emit-cb]
                                        (xstate.subscribe state-machine emit-cb))})

  Returns nothing. No-op in production. See ns docstring for the
  event-shape contract the buffer expects."
  ([] (attach! {:mode :push}))
  ([{:keys [mode trace-source] :or {mode :push} :as _opts}]
   (when interop/debug-enabled?
     ;; Single-source contract: replace any prior attachment so
     ;; tests that reconfigure mid-suite don't accumulate watches.
     (detach!)
     (let [state (case mode
                   :push  (attach-push!)
                   :atom  #?(:clj  (throw (ex-info ":rf.error/causa-atom-mode-requires-cljs"
                                                   {:rf.error/id :rf.error/causa-atom-mode-requires-cljs
                                                    :where    'causa/attach!
                                                    :recovery :no-recovery
                                                    :reason   ":atom drop-in mode requires a CLJS runtime"
                                                    :mode     :atom}))
                             :cljs (attach-atom! trace-source))
                   :sub   (attach-subscribe! trace-source)
                   ;; Unknown mode — pre-alpha posture says no
                   ;; silent fallback; raise so a misconfigured
                   ;; attach fails loudly at the call site.
                   (throw (ex-info ":rf.error/causa-unknown-drop-in-mode"
                                   {:rf.error/id :rf.error/causa-unknown-drop-in-mode
                                    :where    'causa/attach!
                                    :recovery :no-recovery
                                    :reason   (str "unknown drop-in mode " (pr-str mode) " — expected :push, :atom, or :sub")
                                    :mode     mode})))]
       (reset! attached-state state)))
   nil))

(defn emit!
  "Push-mode entry point. Host calls this once per trace event.

  Wraps `collect-from-host!` so push-mode hosts have a stable public
  surface that doesn't expose the internal normalisation fn. Other
  modes (`:atom`, `:sub`) call `collect-from-host!` indirectly through
  the watch / subscribe fan-out; push-mode hosts call this directly.

  Pre-alpha posture: this fn is callable regardless of whether
  `attach!` ran — the buffer accepts events. We do NOT gate on
  `(attached?)` because `attach!` for push mode is purely
  bookkeeping (there is no subscription to register); requiring the
  call would add ceremony without behaviour gain. Hosts that prefer
  the explicit lifecycle still call `(attach! {:mode :push})` for
  symmetry with the other modes.

  Returns nothing. No-op in production."
  [event]
  (collect-from-host! event)
  nil)

(defn reset-for-test!
  "Reset the drop-in's internal state — counter + attachment slot.
  Test-only; never call from production code. Mirrors
  `preload/reset-for-test!`'s contract."
  []
  (reset! id-counter 0)
  (when-let [{:keys [detach-fn]} @attached-state]
    (when (fn? detach-fn)
      (try (detach-fn) (catch #?(:clj Throwable :cljs :default) _ nil))))
  (reset! attached-state nil)
  nil)
