(ns re-frame.core-epoch
  "Public-API wrappers for the optional epoch artefact (Tool-Pair
  §Time-travel). Implementation ships in `day8/re-frame2-epoch`
  (`re-frame.epoch`). See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  The entire epoch surface is gated on `interop/debug-enabled?` (Tool-
  Pair §Time-travel §Production elision). Absent-artefact wrappers
  degrade silently (empty vector / `false` / no-op) so a release build
  that omits the artefact does not raise. `reset-frame-db!` is the
  exception — it raises `:rf.error/epoch-artefact-missing`."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private epoch-artefact
  {:error-keyword :rf.error/epoch-artefact-missing
   :maven         "day8/re-frame2-epoch"
   :require-ns    "re-frame.epoch"})

(defwrapper epoch-history
  "Return the vector of `:rf/epoch-record` values for the frame, oldest-
  first. Empty vector when the frame has no recorded epochs, when the
  ring buffer's depth is 0 (recording disabled), or when the
  `day8/re-frame2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/epoch-history`."
  {:hook :epoch/epoch-history :artefact epoch-artefact :on-absent :empty-vec}
  ([frame-id] :delegate))

(defwrapper restore-epoch
  "Rewind the named frame's `app-db` to the named epoch's `:db-after`.
  Per Tool-Pair §Time-travel: returns `true` on success, `false` on any
  of the six documented failure modes (each emits a structured
  `:rf.epoch/*` error trace and leaves `app-db` unchanged) and `false`
  when the `day8/re-frame2-epoch` artefact is not on the classpath.
  Late-bound via `:epoch/restore-epoch`."
  {:hook :epoch/restore-epoch :artefact epoch-artefact :on-absent :false}
  ([frame-id epoch-id] :delegate))

(defwrapper register-epoch-listener!
  "Register a callback fired once per drain-settle with the assembled
  `:rf/epoch-record`. Per Spec 009 §`register-epoch-listener!`. Same-id
  registrations replace; listener exceptions are isolated. Returns the
  id. No-op (returns nil) when the `day8/re-frame2-epoch` artefact is
  not on the classpath. Late-bound via `:epoch/register-epoch-listener!`."
  {:hook :epoch/register-epoch-listener! :artefact epoch-artefact :on-absent :nil}
  ([id f] :delegate))

(defwrapper unregister-epoch-listener!
  "Remove the listener registered under id. No-op when the
  `day8/re-frame2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/unregister-epoch-listener!`."
  {:hook :epoch/unregister-epoch-listener! :artefact epoch-artefact :on-absent :nil}
  ([id] :delegate))

(defwrapper reset-frame-db!
  "Replace `frame-id`'s `app-db` with `new-db`, bypassing the dispatch
  loop. Per Tool-Pair §Pair-tool writes (rf2-zq55).

  The canonical Tool-Pair write surface for state injection — pair
  tools use it for evolved-state-shape probes after a handler hot-swap,
  story-tool fixture setup, conformance-harness state seeding, and
  time-travel from JSON-loaded bug repros. Records a synthetic
  `:rf/epoch-record` so `restore-epoch` can rewind the previous state;
  emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on `app-db` and emits a structured
  error trace):

    :rf.error/no-such-handler                 — frame not registered
    :rf.epoch/reset-frame-db-during-drain     — drain in flight
    :rf.epoch/reset-frame-db-schema-mismatch  — `new-db` fails the
                                                 frame's app-schema set

  Dev-only — gated on `interop/debug-enabled?`. Production builds
  (`:advanced` + `goog.DEBUG=false`) elide via Closure DCE. Late-bound
  via `:epoch/reset-frame-db!`; raises `:rf.error/epoch-artefact-missing`
  when the `day8/re-frame2-epoch` artefact is not on the classpath
  (the surface records an epoch and so cannot degrade silently — the
  caller's invariant is 'undo works after this call').

  Returns `true` on success, `false` on any failure."
  {:hook :epoch/reset-frame-db! :artefact epoch-artefact :on-absent :throw}
  ([frame-id new-db] :delegate))

(defwrapper projected-record
  "Project an `:rf/epoch-record` for off-box egress. Per Security.md
  §Epoch privacy posture and rf2-mrsck: the single normative
  projection emission site for off-box epoch egress, parallel to
  `elide-wire-value` for direct reads. Routes the four payload-bearing
  slots (`:db-before`, `:db-after`, `:trigger-event`, `:trace-events`)
  through the wire-elision walker against the record's frame, with
  off-box defaults (`:include-sensitive? false`, `:include-large?
  false`); bookkeeping slots (`:epoch-id`, `:frame`, `:committed-at`,
  `:event-id`, `:outcome`, `:halt-reason`, `:schema-digest`,
  `:rf.epoch/sensitive?`) and the cheap structured projections
  (`:sub-runs` / `:renders` / `:effects`) pass through unchanged.

  Tools that egress epoch records over a process boundary (Causa-MCP
  `watch-epochs`, story / pair recorders, hosted forwarders) MUST
  route through this fn. The on-box ring buffer and
  `register-epoch-listener!` listener fan-out continue to deliver the RAW
  record so on-box devtools (Causa diff, REPL, `restore-epoch`) can
  reason about exact state. Returns `nil` for non-map input. No-op
  (returns `nil`) when the `day8/re-frame2-epoch` artefact is not on
  the classpath. Late-bound via `:epoch/projected-record`."
  {:hook :epoch/projected-record :artefact epoch-artefact :on-absent :nil}
  ([record] :delegate))

(defwrapper projected-history
  "Convenience: return the projected vector of records for a frame.
  Equivalent to `(mapv projected-record (epoch-history frame-id))`.
  Tools that egress the whole ring (an MCP `watch-epochs` initial
  snapshot, a recorder dumping the full session) call this once
  rather than walking the raw ring and re-wrapping each record. Empty
  vector when the frame has no recorded epochs, when recording is
  disabled, or when the `day8/re-frame2-epoch` artefact is not on the
  classpath. Late-bound via `:epoch/projected-history`."
  {:hook :epoch/projected-history :artefact epoch-artefact :on-absent :empty-vec}
  ([frame-id] :delegate))
