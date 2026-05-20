(ns re-frame.trace.tooling
  "Trace tooling sibling of `re-frame.trace` — carries the public
  dev-tooling surface (`register-trace-listener!` / `unregister-trace-listener!` /
  `clear-trace-listeners!` / `trace-buffer` / `clear-trace-buffer!` /
  `configure-trace-buffer!` / `configure`) and the buffer / listener
  state they operate on.

  Per rf2-qwm0a (audit rf2-53tcf §Part 4 P1): `re-frame.trace` itself
  carries only the hot emit fast path (`emit!` / `emit-error!` /
  `*handler-scope*` + bracket macros). The listener registry + ring
  buffer + filter predicate are tooling concerns; production counter
  bundles never touch them. Splitting them off lets `:advanced` +
  `goog.DEBUG=false` DCE this ns wholesale (it's only loaded when a
  test fixture, tool, or dev-preload `:require`s it).

  Wiring: at ns load this ns publishes `:trace.tooling/deliver!`
  through `re-frame.late-bind` — `re-frame.trace/deliver!` looks the
  hook up at emit time and fans the event out to the buffer + every
  registered listener. Absent the load, the lookup returns nil and the
  trace fast path skips the fan-out (production behaviour).

  Public surface for tools / tests:
    - `register-trace-listener!` / `unregister-trace-listener!` / `clear-trace-listeners!`
    - `trace-buffer` / `clear-trace-buffer!` / `configure-trace-buffer!`
    - `configure` (generic config dispatch — currently `:trace-buffer`).

  Per Spec 009 §Retain-N trace ring buffer and §The listener API."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))    ;; id → fn

(defn register-trace-listener!
  "Register a listener that receives every trace event. The id can be any
  comparable value; passing the same id twice replaces. Returns the id."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn unregister-trace-listener!
  [id]
  (swap! listeners dissoc id)
  nil)

(defn clear-trace-listeners!
  []
  (reset! listeners {})
  nil)

;; ---- retain-N trace ring buffer (dev-only) -------------------------------

(def ^:private default-buffer-depth
  "Per Spec 009 §Retain-N trace ring buffer: default 200 events —
  enough for one drained cascade plus prior history without per-frame
  memory pressure."
  200)

(defonce ^:private buffer-depth (atom default-buffer-depth))

(defonce ^:private trace-buffer-state
  ;; The buffer is a plain vector held under an atom. Append is conj+slice;
  ;; the slot count caps memory. depth=0 disables the buffer entirely (the
  ;; delivery path still works — see configure-trace-buffer!).
  (atom []))

(defn- push-to-buffer!
  "Append ev to the ring buffer, evicting the oldest entry when the slot
  count is exceeded. No-op when the configured depth is 0."
  [ev]
  (when interop/debug-enabled?
    (let [depth @buffer-depth]
      (when (pos? depth)
        (swap! trace-buffer-state
               (fn [v]
                 (let [v' (conj v ev)
                       n  (count v')]
                   (if (> n depth)
                     (subvec v' (- n depth))
                     v'))))))))

(defn trace-buffer
  "Return the trace ring buffer's current contents, oldest first.

  With opts, filters the result. Recognised keys (all compose AND-wise;
  an absent key means \"no constraint on that axis\"):

    :operation     — keep only events with this :operation value.
    :op-type       — keep only events with this :op-type value.
    :since         — keep only events whose :id is strictly greater than
                     this. Useful for cursor-based polling.
    :frame         — keep only events whose `:tags :frame` (or top-level
                     :frame fallback) matches.
    :severity      — keep only events whose :op-type matches one of
                     `:error` / `:warning` / `:info`. Synonym for
                     `:op-type` restricted to the three severity tiers.
    :event-id      — keep only events whose `:tags :event-id` matches.
                     The event-id is the first element of the dispatched
                     event vector (e.g. `:user/login`).
    :handler-id    — keep only events whose `:tags :handler-id` matches.
                     Carried on `:rf.error/handler-exception` and other
                     handler-scoped emits.
    :source        — keep only events whose :source (top-level, hoisted
                     from `:tags :source` by `emit!`) matches. Source
                     identifies the trigger origin — one of `:ui` /
                     `:timer` / `:http` / `:repl` / `:machine` /
                     `:ssr-hydration`. Matched against the top-level slot.
    :origin        — keep only events whose `:tags :origin` matches.
                     Origin tags the actor that issued the dispatch
                     (`:app` / `:pair` / `:story` / `:test` / ...) per
                     Spec 002 §Dispatch origin tagging.
    :dispatch-id   — keep only events whose `:tags :dispatch-id` matches.
                     Cascade-wide post rf2-g6ih4 — every emit inside a
                     drain carries the in-flight cascade's dispatch-id.
    :since-ms      — keep only events whose :time is strictly greater
                     than this numeric host-clock timestamp.
    :between       — `[t0 t1]` two-element vector — keep only events
                     whose :time falls in [t0, t1] inclusive.
    :sensitive?    — boolean. Match the top-level `:sensitive?` field
                     (per Spec 009 §Privacy filter-vocab row, rf2-isdwf).
                     Pass `false` to exclude sensitive events; pass
                     `true` to select only sensitive events. Absent ⇒
                     no constraint.
    :pred          — `(fn [ev] -> truthy)` arbitrary predicate. Receives
                     the full event map. Returning truthy keeps the event.

  Filters compose: every supplied key must match. Returns an empty vector
  in production (the buffer never receives events when interop/debug-enabled?
  is false at compile time).

  Per Spec 009 §Retain-N trace ring buffer."
  ([] (trace-buffer {}))
  ([opts]
   (if-not interop/debug-enabled?
     []
     (let [{:keys [operation op-type since frame
                   severity event-id handler-id source origin
                   dispatch-id since-ms between pred]} opts
           sensitive-filter (:sensitive? opts)
           [between-t0 between-t1] (when (and (sequential? between)
                                              (= 2 (count between)))
                                     between)
           predicate
           (fn [ev]
             (and (or (nil? operation) (= operation (:operation ev)))
                  (or (nil? op-type)   (= op-type   (:op-type ev)))
                  (or (nil? since)     (and (number? (:id ev))
                                            (> (:id ev) since)))
                  (or (nil? frame)
                      (= frame (or (:frame ev)
                                   (get-in ev [:tags :frame]))))
                  (or (nil? severity) (= severity (:op-type ev)))
                  (or (nil? event-id)
                      (= event-id (get-in ev [:tags :event-id])))
                  (or (nil? handler-id)
                      (= handler-id (get-in ev [:tags :handler-id])))
                  (or (nil? source)
                      (= source (or (:source ev)
                                    (get-in ev [:tags :source]))))
                  (or (nil? origin)
                      (= origin (get-in ev [:tags :origin])))
                  (or (nil? dispatch-id)
                      (= dispatch-id (get-in ev [:tags :dispatch-id])))
                  (or (nil? since-ms)
                      (and (number? (:time ev))
                           (> (:time ev) since-ms)))
                  (or (nil? between-t0)
                      (and (number? (:time ev))
                           (<= between-t0 (:time ev) between-t1)))
                  ;; Top-level `:sensitive?` is hoisted (NOT nested
                  ;; under `:tags`). Match against the top-level slot
                  ;; only; absent reads as false.
                  (or (nil? sensitive-filter)
                      (= (true? sensitive-filter)
                         (true? (:sensitive? ev))))
                  (or (nil? pred) (pred ev))))]
       (filterv predicate @trace-buffer-state)))))

(defn clear-trace-buffer!
  "Empty the ring buffer. Tooling uses this between sessions. No-op in
  production. Per Spec 009 §Retain-N trace ring buffer."
  []
  (when interop/debug-enabled?
    (reset! trace-buffer-state []))
  nil)

(defn configure-trace-buffer!
  "Set the ring buffer's depth. depth=0 disables the buffer (the delivery
  path still works). The new depth applies on the next append; existing
  entries are trimmed to fit immediately. No-op in production.

  Per Spec 009 §Retain-N trace ring buffer."
  [{:keys [depth]}]
  (when (and interop/debug-enabled? (number? depth) (not (neg? depth)))
    (reset! buffer-depth depth)
    (swap! trace-buffer-state
           (fn [v]
             (let [n (count v)]
               (cond
                 (zero? depth) []
                 (> n depth)   (subvec v (- n depth))
                 :else         v)))))
  nil)

(defn configure
  "Generic config dispatch. Recognises :trace-buffer; future config knobs
  add cases here. Per Spec 009 §Retain-N trace ring buffer
  (`(rf/configure :trace-buffer {:depth N})`)."
  [k opts]
  (case k
    :trace-buffer (configure-trace-buffer! opts)
    nil))

;; ---- delivery hook ------------------------------------------------------
;;
;; `re-frame.trace/deliver!` looks this up via late-bind and calls it
;; once per emitted event (after the epoch-capture fan-out). Centralising
;; the buffer-push + listener fan-out in one hook keeps trace.cljc free
;; of any reference to the buffer state or listener atom — so a
;; production build that never `:requires` this ns DCEs the whole body.

(defn- deliver-to-tooling!
  "Push `event` onto the ring buffer (if depth > 0) and fan it out to
  every registered listener. Listener throws are isolated. No-op in
  production (interop/debug-enabled? gate inside push-to-buffer! and at
  the emit call site)."
  [event]
  (push-to-buffer! event)
  (doseq [[_ f] @listeners]
    (try
      (f event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(late-bind/set-fn! :trace.tooling/deliver! deliver-to-tooling!)

;; `re-frame.core/configure :trace-buffer` routes through this hook so
;; consumer call sites don't have to thread the tooling-ns require
;; into the host's boot path. Keeping just THIS one knob hook (vs the
;; full set the listener / buffer surface uses) is deliberate: the
;; per-fn hooks would each pay a keyword-intern cost in every
;; consumer of `re-frame.core`, even when the wrappers' bodies were
;; dead code (the keyword constructor runs at module init).
;; `configure` is a low-traffic op so the extra indirection costs
;; nothing on the hot path.

(late-bind/set-fn! :trace.tooling/configure-trace-buffer! configure-trace-buffer!)

;; Per rf2-r1ciy: `re-frame.frame/fire-on-destroy-event!` installs a one-
;; shot trace listener around the `:on-destroy` dispatch so it can
;; observe the router's `:rf.error/handler-exception` trace and re-emit
;; it under the dedicated `:rf.error/on-destroy-handler-exception`
;; category. The listener-install must run only when the tooling sibling
;; is loaded (otherwise the trace fan-out is dead anyway and there's
;; nothing to observe), so we route through late-bind here — identical
;; pattern to `:trace.tooling/deliver!` above. CLJS production builds
;; that never load this ns short-circuit the install (the lookup
;; returns nil and `fire-on-destroy-event!` skips the listener dance).

(late-bind/set-fn! :trace.tooling/register-trace-listener! register-trace-listener!)
(late-bind/set-fn! :trace.tooling/unregister-trace-listener!   unregister-trace-listener!)

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per rf2-qwm0a: `implementation/scripts/check-bundle-isolation.cjs`
;; greps the counter bundle for this exact string. The string lives
;; ONLY in this file's source body — no other namespace, no docstring,
;; no test fixture references it — so its presence in the production
;; counter bundle proves that the tooling sibling's body got pulled in
;; (most likely via a stray `:require` from a core/* ns). The sentinel
;; survives `:advanced` because string literals are not renamed; it
;; sits outside any `interop/debug-enabled?` gate so DCE cannot drop
;; the literal independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.trace.tooling/sentinel:rf2-qwm0a-2026-05-16:do-not-rename")
