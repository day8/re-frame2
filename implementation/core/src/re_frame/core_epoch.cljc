(ns re-frame.core-epoch
  "Public-API wrappers for the optional epoch artefact (Tool-Pair
  §Time-travel).

  Per rf2-lt4e the epoch implementation ships in the
  `day8/re-frame2-epoch` Maven artefact. The core artefact MUST NOT
  statically `:require [re-frame.epoch]` — that would pull the per-frame
  `:rf/epoch-record` ring buffer, the per-cascade trace-capture path,
  the `:sub-runs` / `:renders` / `:effects` projection walker, the
  schema-validate / machine-version / missing-reference predicates, and
  every `:rf.epoch/*` keyword string onto every consumer's classpath
  even when the pair-tool surface is unused.

  Per Tool-Pair §Time-travel §Production elision the entire epoch
  surface is gated on `interop/debug-enabled?` whether or not the
  artefact is on the classpath; the wrappers degrade silently (empty
  vector / false / no-op) when it's absent so a release build that
  omits the artefact does not raise. `reset-frame-db!` is the
  exception — it records an epoch as part of its contract, so it
  raises `:rf.error/epoch-artefact-missing` rather than degrading.

  Per rf2-hoiu these wrappers live here (and `re-frame.core` re-exports
  them) so `core.cljc` is not cluttered with optional-artefact glue.
  The single-import contract is preserved: users continue to write
  `rf/epoch-history` after `(:require [re-frame.core :as rf])`."
  (:require [re-frame.late-bind :as late-bind]))

(defn epoch-history
  "Return the vector of `:rf/epoch-record` values for the frame, oldest-
  first. Empty vector when the frame has no recorded epochs, when the
  ring buffer's depth is 0 (recording disabled), or when the
  `day8/re-frame2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/epoch-history`."
  [frame-id]
  (if-let [f (late-bind/get-fn :epoch/epoch-history)]
    (f frame-id)
    []))

(defn restore-epoch
  "Rewind the named frame's `app-db` to the named epoch's `:db-after`.
  Per Tool-Pair §Time-travel: returns `true` on success, `false` on any
  of the six documented failure modes (each emits a structured
  `:rf.epoch/*` error trace and leaves `app-db` unchanged) and `false`
  when the `day8/re-frame2-epoch` artefact is not on the classpath.
  Late-bound via `:epoch/restore-epoch`."
  [frame-id epoch-id]
  (if-let [f (late-bind/get-fn :epoch/restore-epoch)]
    (f frame-id epoch-id)
    false))

(defn register-epoch-cb
  "Register a callback fired once per drain-settle with the assembled
  `:rf/epoch-record`. Per Spec 009 §`register-epoch-cb`. Same-id
  registrations replace; listener exceptions are isolated. Returns the
  id. No-op (returns nil) when the `day8/re-frame2-epoch` artefact is
  not on the classpath. Late-bound via `:epoch/register-epoch-cb`."
  [id f]
  (when-let [g (late-bind/get-fn :epoch/register-epoch-cb)]
    (g id f)))

(defn remove-epoch-cb
  "Remove the listener registered under id. No-op when the
  `day8/re-frame2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/remove-epoch-cb`."
  [id]
  (when-let [f (late-bind/get-fn :epoch/remove-epoch-cb)]
    (f id)))

(defn reset-frame-db!
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
  [frame-id new-db]
  (if-let [f (late-bind/get-fn :epoch/reset-frame-db!)]
    (f frame-id new-db)
    (throw (ex-info ":rf.error/epoch-artefact-missing"
                    {:where    'reset-frame-db!
                     :recovery :no-recovery
                     :reason   "rf/reset-frame-db! requires day8/re-frame2-epoch on the classpath; add it to deps and require re-frame.epoch at app boot."}))))
