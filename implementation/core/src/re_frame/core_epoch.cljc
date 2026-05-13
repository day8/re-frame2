(ns re-frame.core-epoch
  "Public-API wrappers for the optional epoch artefact (Tool-Pair
  §Time-travel). Implementation ships in `day8/re-frame2-epoch`
  (`re-frame.epoch` ns) per rf2-lt4e.

  Per [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention) — wrappers
  look the producing fns up via the late-bind hook table at call time;
  consumers reach the surfaces through `re-frame.core` re-exports.

  Per-feature carve-out: the epoch artefact pulls the per-frame
  `:rf/epoch-record` ring buffer, the per-cascade trace-capture path,
  the `:sub-runs` / `:renders` / `:effects` projection walker, and
  every `:rf.epoch/*` keyword string. The entire epoch surface is also
  gated on `interop/debug-enabled?` (Tool-Pair §Time-travel §Production
  elision).

  Absent-artefact behaviour: wrappers degrade silently (empty vector /
  `false` / no-op) so a release build that omits the artefact does not
  raise. `reset-frame-db!` is the exception — it records an epoch as
  part of its contract and raises `:rf.error/epoch-artefact-missing`.

  Per rf2-h824v the wrappers below are emitted by the
  `re-frame.core-artefact/defwrapper` factory from a declarative table —
  one row per public surface."
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

(defwrapper register-epoch-cb!
  "Register a callback fired once per drain-settle with the assembled
  `:rf/epoch-record`. Per Spec 009 §`register-epoch-cb!`. Same-id
  registrations replace; listener exceptions are isolated. Returns the
  id. No-op (returns nil) when the `day8/re-frame2-epoch` artefact is
  not on the classpath. Late-bound via `:epoch/register-epoch-cb`."
  {:hook :epoch/register-epoch-cb :artefact epoch-artefact :on-absent :nil}
  ([id f] :delegate))

(defwrapper remove-epoch-cb!
  "Remove the listener registered under id. No-op when the
  `day8/re-frame2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/remove-epoch-cb`."
  {:hook :epoch/remove-epoch-cb :artefact epoch-artefact :on-absent :nil}
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
