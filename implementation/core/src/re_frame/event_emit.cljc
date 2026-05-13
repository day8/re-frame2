(ns re-frame.event-emit
  "Always-on event-emit substrate for production observability
  (Datadog, Sentry, Honeycomb, ...). Per rf2-rirbq / Spec 009 §What IS
  available in production §Event-emit listener.

  ## Why this namespace exists

  Spec 009's trace surface is compile-time elided in CLJS production
  builds via `re-frame.interop/debug-enabled?` — see Spec 009
  §Production builds. The chapter-22 recipe for forwarding events to a
  hosted observability service used to ride `register-trace-cb!`; under
  `:advanced` + `goog.DEBUG=false` that listener silently died with
  the rest of the trace machinery. The escape hatches (flip
  `goog.DEBUG=true` in prod, or fall back to the User Timing channel)
  were unsatisfying — the first balloons the bundle and the second
  drops the sensitive/large-elision composition that re-frame2
  otherwise offers.

  This namespace carves a small **always-on** delivery path that
  survives `goog.DEBUG=false`, *parallel* to (not a fallback for) the
  trace surface. Two surfaces coexist:

    - Trace events (dev): `register-trace-cb!` listeners see every
      `:event/dispatched`, `:event` (`:phase :run-start` /
      `:run-end`), `:sub/run`, `:fx`, `:event/db-changed`,
      `:rf.error/*`, etc. Production builds DCE the entire surface.
    - Event-emit (always-on): `register-event-emit-listener!`
      listeners see one record per processed *event* — NOT subs, NOT
      fxs, NOT `:event/db-changed`, NOT registration metadata. The
      record is intentionally tight (Spec 009 §Event-emit listener
      §Record shape) so the hot-path cost is bounded and listeners
      can ship the wire payload without further shaping. Production
      builds preserve the surface.

  ## Surface (tiny by design)

  - [[register-event-emit-listener!]]   — register a listener fn under
    an id. Re-registering the same id replaces.
  - [[unregister-event-emit-listener!]] — drop a listener by id.
  - [[clear-event-emit-listeners!]]     — wipe the registry; test
    isolation only.
  - [[dispatch-on-event!]]              — invoked by `router.cljc`
    after each event settles. Builds the tight record, runs
    `re-frame.elision/elide-wire-value` ONCE (with off-box defaults:
    `:rf.size/include-large? false`, `:rf.size/include-sensitive?
    false` — large values get the `:rf.size/large-elided` marker;
    sensitive values get `:rf/redacted`), then fans the elided
    record out to every registered listener. Each listener
    invocation is wrapped in try/catch so a buggy listener cannot
    break the cascade or other listeners. Published through the
    late-bind hook `:event-emit/dispatch-on-event` so `router.cljc`
    does NOT statically `:require` this namespace.

  ## Record shape (TIGHT — Spec 009)

    {:event       <vector>      ;; the dispatched event vector (elided)
     :event-id    <kw>           ;; (first event)
     :frame       <kw>           ;; resolved frame-id
     :time        <millis>       ;; emit timestamp (host clock, ms since epoch)
     :outcome     :ok | :error   ;; handler exception → :error
     :elapsed-ms  <int>}         ;; wall-clock from queue → settle

  No trace-bus keys (`:dispatch-id`, `:parent-dispatch-id`,
  `:rf.trace/trigger-handler`, ...) — those ride the dev-only trace
  surface and DCE under `:advanced` + `goog.DEBUG=false`.

  ## goog.DEBUG framing

  The substrate is always-on (no `goog.DEBUG` check inside this
  namespace). Listener REGISTRATION sites SHOULD use `goog.DEBUG` as a
  belt-and-braces gate alongside the user's explicit config flag, e.g.

    (when (and (= \"production\" (:env config))
               (not ^boolean re-frame.interop/debug-enabled?)
               (:api-key config))
      (rf/register-event-emit-listener!
        :datadog/forward
        (fn [event-record]
          (datadog/track event-record))))

  Catches the 'accidentally deployed a dev bundle with prod config'
  bug class.

  ## Listener responsibilities

  - Listeners are invoked synchronously after each event settles. Keep
    bodies cheap; ship work to a background channel if it cannot fit
    inside the drain step's wall-clock budget.
  - Listeners receive an event-record whose `:event` vector has
    ALREADY been passed through `re-frame.elision/elide-wire-value`
    with off-box defaults (large values → `:rf.size/large-elided`
    marker; sensitive values → `:rf/redacted`). Listeners SHOULD
    NOT re-run elision unless they want to widen the policy (e.g.
    flip `:rf.size/include-digests? true` for a debug pipeline that
    cares about content hashes).
  - Exceptions raised by a listener are caught here; the other
    listeners still run and the drain continues. No retry, no
    recursive emit.

  ## What this namespace deliberately does NOT do

  - It does NOT push to the dev-only trace ring buffer.
  - It does NOT carry `:dispatch-id` / `:parent-dispatch-id` /
    source-coord enrichment — those are trace-surface-only.
  - It does NOT emit per-sub, per-fx, or per-`:event/db-changed`
    records — *only* events, per rf2-rirbq.
  - It does NOT validate listener return values.

  Per Spec 009 §Production debugging: the recommended way to wire
  production *event* observability (Datadog, Honeycomb, custom
  pipelines). For production *error* monitoring (Sentry, Rollbar,
  Honeybadger, ...) prefer the parallel always-on substrate behind
  the per-frame `:on-error` slot (per rf2-hqbeh /
  `re-frame.error-emit`).

  ## Handler-meta `:sensitive?` (rf2-6hklf)

  Honoured at this boundary. If the event's registered handler-meta
  carries `:sensitive? true`, `dispatch-on-event!` drops the record
  entirely — listeners are NOT invoked. This matches the chapter-22
  promise that flagging a handler `:sensitive?` is sufficient to keep
  *every* one of its records out of production observability,
  regardless of whether the payload happens to touch a sensitive
  `:rf/elision`-registered app-db path. The narrower per-value
  redaction (walker against `:rf/elision`) still applies to records
  from non-sensitive handlers."
  (:require [re-frame.elision  :as elision]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners
  ;; id -> listener fn. `defonce` so hot reload of this namespace
  ;; does not silently drop a long-lived production listener that
  ;; the consuming app registered at boot.
  (atom {}))

(defn register-event-emit-listener!
  "Register a listener `f` under `id`. The id can be any value usable
  as a map key; re-registering under the same id replaces. `f`
  receives a single event-record map (see ns docstring §Record
  shape) and its return value is ignored. Returns `id`.

  Always-on: the substrate is NOT gated on
  `re-frame.interop/debug-enabled?`. Registration sites SHOULD still
  use `goog.DEBUG=false` as a belt-and-braces gate around
  production-only listeners — see the ns docstring §goog.DEBUG
  framing."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn unregister-event-emit-listener!
  "Drop the listener registered under `id`. Returns nil."
  [id]
  (swap! listeners dissoc id)
  nil)

(defn clear-event-emit-listeners!
  "Drop every registered listener. Test-isolation only; production
  code should never call this. Returns nil."
  []
  (reset! listeners {})
  nil)

(defn dispatch-on-event!
  "Fan an elided event-record out to every registered listener.
  Always-on (NOT gated by `re-frame.interop/debug-enabled?`) —
  fires in CLJS production builds where the trace surface is
  elided.

  Short-circuits to a no-op when the registry is empty so the
  per-event hot-path cost reduces to one deref + an empty-map
  check.

  Builds the record from the supplied positional args, runs
  `re-frame.elision/elide-wire-value` ONCE against the `:event`
  vector with off-box defaults (large → `:rf.size/large-elided`;
  sensitive → `:rf/redacted`), then fans the elided record out.

  Listener exceptions are caught — the cascade does NOT abort
  and sibling listeners still run. The runtime does NOT
  recursively emit through this substrate on a listener throw.

  Called by `router.cljc` once per processed event after the
  cascade body settles (`:db` committed, flows run, `:fx`
  walked). Published under the late-bind key
  `:event-emit/dispatch-on-event` so the router does NOT
  statically `:require` this namespace.

  Per rf2-6hklf: if the event's registered handler-meta carries
  `:sensitive? true`, the record is dropped entirely (listeners are
  NOT invoked). The handler-meta lookup happens BEFORE the elision
  walk so the sensitive-handler short-circuit costs no per-value
  work.

  Per rf2-qsjda: if the event's registered handler-meta carries
  `:rf.trace/no-emit? true`, the record is also dropped. The flag
  marks the handler as a framework-internal bookkeeping handler
  (Spec 009 §Trace-emission opt-out) — its dispatches are not
  user-domain observable signal. Production observability pipelines
  should not see Causa/Story/etc. bookkeeping dispatches in their
  event stream any more than they should see the trace events those
  handlers suppress."
  [event event-id frame time outcome elapsed-ms]
  (let [reg @listeners]
    (when (seq reg)
      (let [handler-meta (registrar/lookup :event event-id)]
        (when-not (or (:sensitive?        handler-meta)
                      (:rf.trace/no-emit? handler-meta))
          (let [elided-event (elision/elide-wire-value event {:frame frame})
                record       {:event      elided-event
                              :event-id   event-id
                              :frame      frame
                              :time       time
                              :outcome    outcome
                              :elapsed-ms elapsed-ms}]
            (doseq [[_id f] reg]
              (try
                (f record)
                (catch #?(:clj Throwable :cljs :default) _ nil))))))))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `router.cljc` invokes `dispatch-on-event!` once per processed
;; event. To keep the substrate orthogonal to the trace surface
;; (and to mirror how the rest of the late-bound surfaces ship),
;; the router looks the fn up through the late-bind hook table at
;; call time rather than `:require`ing this namespace directly.
;; Per rf2-rirbq.

(late-bind/set-fn! :event-emit/dispatch-on-event dispatch-on-event!)
