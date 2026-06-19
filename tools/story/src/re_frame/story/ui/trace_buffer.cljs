(ns re-frame.story.ui.trace-buffer
  "Per-variant trace ring buffer + listener wiring. Per Spec 009 §Per-frame
  routing — Story installs one trace-cb per variant, filters incoming
  events to that frame, and appends to a per-variant ratom keyed by
  variant-id with a fixed retention cap.

  ## History

  Originally this code lived in `re-frame.story.ui.trace` alongside the
  six-domino trace panel view. Per rf2-sgdd3 the panel view was retired
  in favour of Xray's Trace tab (richer filtering, focus, cascade
  navigation) — Story's RHS embeds Xray now. The buffer infrastructure
  survives because `re-frame.story.ui.schema-validation` still consumes
  it to project Spec 010 schema-validation failures into its own
  registered story-panel.

  ## Public surface

  - `buffers`             — `{variant-id → r/atom <vector of trace events>}`
  - `ensure-buffer!`      — get/create the per-variant ratom
  - `clear-buffer!`       — reset the buffer (variant-scoped or global)
  - `drop-buffer!`        — dissoc the entry on shell unmount
  - `register-listener!`  — install the per-variant trace-cb
  - `remove-listener!`    — tear it down

  ## Elision

  All public fns gate on `re-frame.story.config/enabled?`. Production
  builds (`enabled?` false via `:closure-defines`) elide every Story
  surface, including the load-time clear-on-toggle-off hook below."
  (:require [reagent.core :as r]
            ;; rf2-qwm0a — listener API lives in
            ;; `re-frame.trace.tooling` (production-DCE split).
            [re-frame.trace.tooling :as trace-tooling]
            ;; rf2-7737vq — the canonical RAW trace-event frame reader
            ;; (`re-frame.trace/trace-event-frame`), replacing Story's
            ;; hand-rolled `[:tags :frame]` read.
            [re-frame.trace :as trace]
            [re-frame.story.config :as config]))

;; ---- the per-variant trace buffer ----------------------------------------

(def ^:private max-events
  "How many trace events to retain per variant frame. Per Spec 009
  §Retain-N trace ring buffer the framework defaults to 200; the
  buffer holds the same cap so the downstream consumer cost stays
  bounded."
  200)

(defonce buffers
  ;; {variant-id → r/atom holding a vector of trace events, newest-last}
  (atom {}))

(defn ensure-buffer!
  "Return the per-variant trace-buffer ratom, creating it on first
  access. Public so consumer panels (e.g. the schema-validation panel
  per rf2-dvue) can subscribe to the SAME ratom the listener writes."
  [variant-id]
  (or (get @buffers variant-id)
      (let [a (r/atom [])]
        (swap! buffers assoc variant-id a)
        a)))

(defn clear-buffer!
  "Drop the buffer for `variant-id` (or all buffers when no arg).
  Per rf2-bclgj: also clears the matching per-variant suppressed-
  events counter so the `[● REDACTED]` hint hides when the buffer
  resets."
  ([]
   (doseq [a (vals @buffers)] (reset! a []))
   (config/reset-suppressed-count!)
   nil)
  ([variant-id]
   (when-let [a (get @buffers variant-id)]
     (reset! a []))
   (config/reset-suppressed-count! variant-id)
   nil))

;; ---- retroactive scrub on egress-profile narrowing (rf2-lqmje / rf2-6z4znr)
;;
;; Per Spec 009 §Privacy §Retroactive-scrub (EP-0015 issue 7): narrowing a
;; FRAME's local-render egress profile from a sensitive-revealing boundary
;; (`:rf.egress/local-raw`) back to the redacting default clears THAT frame's
;; trace buffer; narrowing the session-pin clears every (non-overridden)
;; buffer. Each Story trace listener only gates at ingest via
;; `config/suppress-sensitive?`, so without this hook a sensitive cascade
;; buffered while the raw profile was active would remain visible in that
;; frame's downstream surface after the operator expected privacy restored.
;;
;; The callback receives the narrowed `frame-id` (EP-0015 issue 7 —
;; frame-scoped scrub). A specific frame-id clears only that variant's
;; buffer; a `nil` frame-id (the session-pin narrow signal) clears EVERY
;; buffer. The clear cascades through `clear-buffer!`:
;;   - resets the targeted (or every) per-variant ratom to `[]`,
;;   - calls `config/reset-suppressed-count!` so the `[● REDACTED]` hint
;;     drops in lockstep with the buffer.
;;
;; The hook registers at load time, gated on `config/enabled?` —
;; production builds (`enabled?` false via `:closure-defines`) elide
;; Story entirely, including the registration form.

(defn- scrub-on-toggle-off!
  "Frame-scoped toggle-off scrub (rf2-6z4znr). `frame-id` nil → clear every
  buffer (session-pin narrowing); a specific frame-id → clear only that
  variant's buffer."
  [frame-id]
  (if (some? frame-id)
    (clear-buffer! frame-id)
    (clear-buffer!)))

(when config/enabled?
  (config/register-toggle-off-callback! ::clear-on-toggle-off scrub-on-toggle-off!))

(defn drop-buffer!
  "Remove the buffer entry entirely. Called from shell unmount.
  Per rf2-bclgj: also clears the per-variant suppressed-events
  counter so it doesn't leak across variant teardowns."
  [variant-id]
  (swap! buffers dissoc variant-id)
  (config/reset-suppressed-count! variant-id)
  nil)

(defn- append!
  "Append `ev` to `variant-id`'s buffer, evicting oldest entries to
  honour `max-events`."
  [variant-id ev]
  (let [a (ensure-buffer! variant-id)]
    (swap! a (fn [v]
               (let [v' (conj v ev)
                     n  (count v')]
                 (if (> n max-events)
                   (subvec v' (- n max-events))
                   v'))))))

;; ---- the trace listener --------------------------------------------------

(defn- listener-id [variant-id]
  (keyword "re-frame.story.ui.trace-buffer"
           (str "listener-" (when variant-id (str variant-id)))))

(defn- variant-event?
  "Filter predicate: true iff RAW trace event `ev` targets `variant-id`'s
  frame. Reads via the canonical `re-frame.trace/trace-event-frame`
  reader (its `[:tags :frame]` slot); events that have no frame scope
  (registry / global) match nothing. Per Spec 009 §Frame identity on the
  raw event (rf2-7737vq) — `:frame` rides under `:tags` on the raw layer."
  [variant-id ev]
  (= variant-id (trace/trace-event-frame ev)))

(defn register-listener!
  "Install a trace listener that appends every `variant-id`-scoped event
  into the per-variant buffer. Idempotent (re-registering replaces).
  Returns the listener id.

  Per Spec 009 §Privacy + EP-0015 rf2-3t26eh: events whose `:sensitive?`
  flag is true are dropped from the buffer when Story's local-render
  egress profile redacts (`:rf.egress/local-redacted` — the default). The
  suppressed-events counter bumps for the variant so downstream
  consumers can advertise that the buffer is shorter than the
  runtime's actual emit count."
  [variant-id]
  (when config/enabled?
    (let [id (listener-id variant-id)]
      (trace-tooling/register-listener! id
        (fn [ev]
          (when (variant-event? variant-id ev)
            ;; rf2-6z4znr — the listener is per-variant; resolve the suppress
            ;; decision against THIS variant's frame so revealing a sibling
            ;; variant never opens this buffer's gate.
            (if (config/suppress-sensitive? ev variant-id)
              (config/note-suppressed! variant-id)
              (append! variant-id ev)))))
      id)))

(defn remove-listener!
  "Tear down the trace listener for `variant-id`. Idempotent."
  [variant-id]
  (when config/enabled?
    (trace-tooling/unregister-listener! (listener-id variant-id))
    nil))
