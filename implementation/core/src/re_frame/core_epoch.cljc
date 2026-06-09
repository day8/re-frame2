(ns re-frame.core-epoch
  "Public-API wrappers for the optional epoch artefact (Tool-Pair
  §Time-travel). Implementation ships in `day8/re-frame2-epoch`
  (`re-frame.epoch`). See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  The entire epoch surface is gated on `interop/debug-enabled?` (Tool-
  Pair §Time-travel §Production elision). Absent-artefact wrappers
  degrade silently (empty vector / `false` / no-op) so a release build
  that omits the artefact does not raise. The four partition-aware
  injection mutators (`replace-app-db!` / `reset-app-db!` /
  `replace-runtime-db!` / `replace-frame-state!`) are the exception — each
  records a synthetic epoch, so it cannot degrade silently (the caller's
  invariant is 'undo works after this call'); they raise
  `:rf.error/epoch-artefact-missing`."
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
  of the seven documented failure modes (each emits a structured
  `:rf.epoch/*` error trace and leaves `app-db` unchanged) and `false`
  when the `day8/re-frame2-epoch` artefact is not on the classpath. The
  seven modes are `:rf.error/no-such-handler` (frame not registered),
  `:rf.epoch/restore-during-drain`, `:rf.epoch/restore-unknown-epoch`,
  `:rf.epoch/restore-non-ok-record` (target epoch's `:outcome` is not
  `:ok`), `:rf.epoch/restore-schema-mismatch`,
  `:rf.epoch/restore-missing-handler`, and
  `:rf.epoch/restore-version-mismatch`.
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

(defwrapper replace-app-db!
  "Replace `frame-id`'s `app-db` partition with `new-db`, bypassing the
  dispatch loop. Per Tool-Pair §Pair-tool writes. Renamed from
  `reset-frame-db!` (EP-0001 rf2-tfepxu, Mike ruling #10 — a db-shaped
  name never silently replaces runtime-db; the live runtime-db survives).

  The canonical Tool-Pair write surface for app-db state injection — pair
  tools use it for evolved-state-shape probes after a handler hot-swap,
  story-tool fixture setup, conformance-harness state seeding, and
  time-travel from JSON-loaded bug repros. Records a synthetic
  `:rf/epoch-record` so `restore-epoch` can rewind the previous state;
  emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on `app-db` and emits a structured
  error trace):

    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-app-db-during-drain       — drain in flight
    :rf.epoch/replace-app-db-schema-mismatch    — `new-db` fails the
                                                   frame's app-schema set

  Dev-only — gated on `interop/debug-enabled?`. Production builds
  (`:advanced` + `goog.DEBUG=false`) elide via Closure DCE. Late-bound
  via `:epoch/replace-app-db!`; raises `:rf.error/epoch-artefact-missing`
  when the `day8/re-frame2-epoch` artefact is not on the classpath
  (the surface records an epoch and so cannot degrade silently — the
  caller's invariant is 'undo works after this call').

  Returns `true` on success, `false` on any failure."
  {:hook :epoch/replace-app-db! :artefact epoch-artefact :on-absent :throw}
  ([frame-id new-db] :delegate))

(defwrapper reset-app-db!
  "Reset `frame-id`'s `app-db` partition to `{}`, bypassing the dispatch
  loop, while preserving live runtime-db (machines / routes / elision /
  SSR survive). The app-db-only sibling of the whole-frame `reset-frame!`
  (EP-0001 rf2-tfepxu, Mike ruling #10). Equivalent to
  `(replace-app-db! frame-id {})` — same synthetic-epoch recording, same
  gating and failure modes.

  Dev-only — gated on `interop/debug-enabled?`. Production builds
  (`:advanced` + `goog.DEBUG=false`) elide via Closure DCE. Late-bound
  via `:epoch/reset-app-db!`; raises `:rf.error/epoch-artefact-missing`
  when the `day8/re-frame2-epoch` artefact is not on the classpath
  (it records an epoch and so cannot degrade silently).

  Returns `true` on success, `false` on any failure."
  {:hook :epoch/reset-app-db! :artefact epoch-artefact :on-absent :throw}
  ([frame-id] :delegate))

(defwrapper replace-runtime-db!
  "Replace `frame-id`'s `runtime-db` partition with `runtime-db`, bypassing
  the dispatch loop — the runtime-db sibling of `replace-app-db!` (Tool-Pair
  §Pair-tool writes). Privileged runtime / full-frame tool surface for
  injecting framework-owned subsystem state (machine snapshots, route
  slice, …); the app-db partition is preserved unchanged. Records a
  synthetic `:rf/epoch-record` so `restore-epoch` can rewind the previous
  state; emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on `runtime-db` and emits a structured
  error trace — the shared four-mutator failure surface):

    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-app-db-during-drain       — drain in flight
    :rf.epoch/replace-app-db-schema-mismatch    — `runtime-db` fails the
                                                   framework-owned runtime-db
                                                   validator (reg-runtime-schema)

  Dev-only — gated on `interop/debug-enabled?`. Production builds
  (`:advanced` + `goog.DEBUG=false`) elide via Closure DCE. Late-bound
  via `:epoch/replace-runtime-db!`; raises `:rf.error/epoch-artefact-missing`
  when the `day8/re-frame2-epoch` artefact is not on the classpath
  (the surface records an epoch and so cannot degrade silently — the
  caller's invariant is 'undo works after this call').

  Returns `true` on success, `false` on any failure."
  {:hook :epoch/replace-runtime-db! :artefact epoch-artefact :on-absent :throw}
  ([frame-id runtime-db] :delegate))

(defwrapper replace-frame-state!
  "Replace BOTH of `frame-id`'s partitions atomically with `frame-state`
  (`{:rf.db/app … :rf.db/runtime …}`), bypassing the dispatch loop — the
  full-frame install for tool-driven replay / fixture install (Tool-Pair
  §Pair-tool writes). The whole-frame sibling of `replace-app-db!`; a
  db-shaped name never silently replaces runtime-db, so this is the
  explicit full-frame surface (Mike ruling #10). A missing partition key
  installs `nil` for that partition (a full-frame replace is whole-value
  by contract). Records a synthetic `:rf/epoch-record` so `restore-epoch`
  can rewind the previous state; emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on the frame-state and emits a structured
  error trace — the shared four-mutator failure surface):

    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-app-db-during-drain       — drain in flight
    :rf.epoch/replace-app-db-schema-mismatch    — the app-db partition fails
                                                   the frame's app-schema set
                                                   OR the runtime-db partition
                                                   fails the framework-owned
                                                   runtime-db validator

  Dev-only — gated on `interop/debug-enabled?`. Production builds
  (`:advanced` + `goog.DEBUG=false`) elide via Closure DCE. Late-bound
  via `:epoch/replace-frame-state!`; raises
  `:rf.error/epoch-artefact-missing` when the `day8/re-frame2-epoch`
  artefact is not on the classpath (it records an epoch and so cannot
  degrade silently).

  Returns `true` on success, `false` on any failure."
  {:hook :epoch/replace-frame-state! :artefact epoch-artefact :on-absent :throw}
  ([frame-id frame-state] :delegate))

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

  Tools that egress epoch records over a process boundary (Xray-MCP
  `watch-epochs`, story / pair recorders, hosted forwarders) MUST
  route through this fn. The on-box ring buffer and
  `register-epoch-listener!` listener fan-out continue to deliver the RAW
  record so on-box devtools (Xray diff, REPL, `restore-epoch`) can
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
