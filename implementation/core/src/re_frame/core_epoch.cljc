(ns re-frame.core-epoch
  "Public-API wrappers for the optional epoch artefact (Tool-Pair
  §Time-travel). Implementation ships in `day8/re-frame2-epoch`
  (`re-frame.epoch`). See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  The entire epoch surface is gated on `interop/debug-enabled?` (Tool-
  Pair §Time-travel §Production elision). Absent-artefact wrappers
  degrade silently (empty vector / `false` / no-op) so a release build
  that omits the artefact does not raise. The partial-map partition-aware
  injection mutator (`replace-frame-state!`, rf2-t3lftq — API-shrink #3
  consolidated the former `replace-app-db!` / `reset-app-db!` /
  `replace-runtime-db!` / `replace-frame-state!` four-mutator family into
  this ONE surface) is the exception — it records a synthetic epoch, so it
  cannot degrade silently (the caller's invariant is 'undo works after
  this call'); it raises `:rf.error/epoch-artefact-missing`."
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

(defwrapper restore-epoch!
  "Rewind the named frame's WHOLE frame-state — BOTH the app-db AND
  runtime-db partitions — to the named epoch's `:frame-state-after`, via
  `replace-frame-state!` (EP-0001 rf2-3aizt1, decisions #2 + #9). This
  revives machine snapshots, the route slice, elision declarations, and SSR
  metadata (runtime-db state) alongside app-db — not just the app-db
  partition. The canonical `:frame-state-after` is the ONLY restore source:
  the `:db-after` slot is a retained app-db PROJECTION for tool diffs, never
  a restore source (a record carrying no `:frame-state-after` is
  malformed/unreachable on the current build path — there is no db-only
  fallback). Per Tool-Pair §Time-travel: returns `true` on success,
  `false` on any
  of the seven documented failure modes (each emits a structured
  `:rf.epoch/*` error trace and leaves `app-db` unchanged) and `false`
  when the `day8/re-frame2-epoch` artefact is not on the classpath. The
  seven modes are `:rf.error/no-such-handler` (frame not registered),
  `:rf.epoch/restore-during-drain`, `:rf.epoch/restore-unknown-epoch`,
  `:rf.epoch/restore-non-ok-record` (target epoch's `:outcome` is not
  `:ok`), `:rf.epoch/restore-schema-mismatch`,
  `:rf.epoch/restore-missing-handler`, and
  `:rf.epoch/restore-version-mismatch`.
  Late-bound via `:epoch/restore-epoch!`."
  {:hook :epoch/restore-epoch! :artefact epoch-artefact :on-absent :false}
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

(defwrapper clear-epoch-listeners!
  "Drop every registered epoch-settled callback. Test-isolation only —
  production code should never call this. Returns nil. No-op (returns
  nil) when the `day8/re-frame2-epoch` artefact is not on the classpath.
  Late-bound via `:epoch/clear-epoch-listeners!`."
  {:hook :epoch/clear-epoch-listeners! :artefact epoch-artefact :on-absent :nil}
  ([] :delegate))

(defwrapper replace-frame-state!
  "Atomically install `frame-state` — a PARTIAL frame-state map (any
  subset of `{:rf.db/app … :rf.db/runtime …}`) — into `frame-id`'s
  frame-state, bypassing the dispatch loop: a PRESENT key replaces that
  partition, an ABSENT key is preserved unchanged (rf2-t3lftq — API-shrink
  #3, consolidating the former `replace-app-db!` / `reset-app-db!` /
  `replace-runtime-db!` / `replace-frame-state!` four-mutator family into
  this ONE surface). A db-shaped key never silently touches the other
  partition — the partition is named explicitly at every call site (Mike
  ruling #10, preserved and generalised). Per Tool-Pair §Pair-tool writes.

  The canonical Tool-Pair write surface for state injection — pair tools
  use it for evolved-state-shape probes after a handler hot-swap,
  story-tool fixture setup, conformance-harness state seeding, and
  time-travel from JSON-loaded bug repros. Records a synthetic
  `:rf/epoch-record` so `restore-epoch!` can rewind the previous state;
  emits `:rf.epoch/db-replaced` on success.

  App-only injection: `(replace-frame-state! frame-id {:rf.db/app v})` —
  the former `replace-app-db!`; `(replace-frame-state! frame-id
  {:rf.db/app {}})` is the former `reset-app-db!`. Runtime-only injection:
  `(replace-frame-state! frame-id {:rf.db/runtime v})` — the former
  `replace-runtime-db!`. Both-partition install supplies both keys.

  Failure modes (each is a no-op on the frame-state and emits a structured
  error trace):

    :rf.error/replace-frame-state-bad-keys — `frame-state` carries no
                                                   recognized partition key
                                                   (`:rf.db/app` /
                                                   `:rf.db/runtime`), or an
                                                   unrecognized key — checked
                                                   BEFORE frame resolution
    :rf.error/no-such-handler                   — frame not registered
    :rf.epoch/replace-during-drain       — drain in flight
    :rf.epoch/replace-schema-mismatch    — a PRESENT app-db partition fails
                                                   the frame's app-schema set,
                                                   OR a PRESENT runtime-db
                                                   partition fails the
                                                   framework-owned runtime-db
                                                   validator
    :rf.epoch/replace-history-disabled   — epoch ring disabled (depth 0);
                                                   the synthetic undo-anchor
                                                   cannot land (rf2-unpldn)

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
  "Project an `:rf/epoch-record` for off-box egress (EP-0015 §15). Per
  Security.md §Epoch privacy posture and rf2-mrsck: the single normative
  projection emission site for off-box epoch egress, parallel to
  `elide-wire-value` for direct reads. Routes each payload-bearing slot
  (`:frame-state-before` / `:frame-state-after`, `:db-before`, `:db-after`,
  `:trigger-event`, `:trace-events`, the structured `:sub-runs` / `:effects`
  value slots) through `project-egress` against the record's frame under the
  named `:rf.egress/profile` boundary (EP-0015 §10); bookkeeping slots
  (`:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:outcome`,
  `:halt-reason`, `:schema-digest`, `:rf.epoch/sensitive?`) and the
  value-free `:renders` projection pass through unchanged.

  Tools that egress epoch records over a process boundary (Xray-MCP
  `watch-epochs`, story / pair recorders, hosted forwarders) MUST
  route through this fn. The on-box ring buffer and
  `register-epoch-listener!` listener fan-out continue to deliver the RAW
  record so on-box devtools (Xray diff, REPL, `restore-epoch!`) can
  reason about exact state. Returns `nil` for non-map input. No-op
  (returns `nil`) when the `day8/re-frame2-epoch` artefact is not on
  the classpath. Late-bound via `:epoch/projected-record`.

  ## Egress profile (the primary selector) + advanced opts

  The 2-arity accepts an egress `opts` map. The PRIMARY public selector is the
  named egress boundary — `:rf.egress/profile` — which answers *\"which
  boundary is this?\"* against the shared closed `:rf.egress/*` enum
  (`re-frame.projection/profiles`), NOT a combination of booleans:

      {:rf.egress/profile <profile>}   ;; default :rf.egress/off-box-observability

  The two off-box boundaries an epoch consumer selects:

    - `:rf.egress/off-box-observability` (DEFAULT) — hosted monitoring / log
      shippers / Story / pair recorders. Redact sensitive, elide large, omit
      structural digests. The bare 1-arity is this safe, fully-redacted path.
    - `:rf.egress/off-box-tool` — the MCP / AI / tool wire. Same redact/elide
      defaults, plus `:rf.size/include-digests?` so a large owner-local slot
      egresses as a marker carrying the structural indicators a tool needs.

  An unknown profile is rejected against the closed enum (a typo is a loud
  error, never a silent permissive walk).

  The legacy unqualified `:include-*` keys remain as ADVANCED per-call
  overrides composed OVER the selected profile (the `:rf.size/*` floor the
  profile resolves; the override wins) — NOT the primary boundary selector:
  `:include-sensitive?` / `:include-large?` opt the APP-DB partition's privacy
  / size posture back in across every payload slot (the trusted-local
  `local-raw` direction); they do NOT lift the frame-state `:rf.db/runtime`
  partition boundary, which stays `:rf/redacted` unless `:include-runtime-db?
  true` is also passed (`:include-fx-args?` / `:include-event-args?` likewise
  opt the `:effects` `:args` / `:trigger-event` args back in). All default
  `false`.

  ## `:redact-fn` is an advanced PROJECTION-side hook (EP-0015 §15, issue 6)

  After the frame/profile projection lands, any app-installed
  `(rf/configure! {:epoch-history {:redact-fn …}})` runs over the ALREADY-PROJECTED
  egress copy as the rare advanced escape for material the frame/profile
  projection cannot prove. It is projection-side ONLY — the on-box ring stays
  raw (post-EP-0010 causal replay material), so the hook can never affect
  `restore-epoch!` fidelity; storage-side epoch mutation was removed."
  {:hook :epoch/projected-record :artefact epoch-artefact :on-absent :nil}
  ([record] :delegate)
  ([record opts] :delegate))

(defwrapper projected-history
  "Convenience: return the projected vector of records for a frame (EP-0015 §15).
  Equivalent to `(mapv #(projected-record % opts) (epoch-history frame-id))`.
  Tools that egress the whole ring (an MCP `watch-epochs` initial
  snapshot, a recorder dumping the full session) call this once
  rather than walking the raw ring and re-wrapping each record. Empty
  vector when the frame has no recorded epochs, when recording is
  disabled, or when the `day8/re-frame2-epoch` artefact is not on the
  classpath. Late-bound via `:epoch/projected-history`.

  The 2-arity threads the egress `opts` map (see `projected-record`) to every
  record — the named `:rf.egress/profile` boundary (default
  `:rf.egress/off-box-observability`) plus the advanced `:include-*` overrides;
  the 1-arity is the safe, fully-redacted off-box-observability path."
  {:hook :epoch/projected-history :artefact epoch-artefact :on-absent :empty-vec}
  ([frame-id] :delegate)
  ([frame-id opts] :delegate))
