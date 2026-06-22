(ns re-frame.router
  "Per-frame FIFO router and the drain loop. Per Spec 002 §Run-to-completion
  dispatch (drain semantics) and §Drain-loop pseudocode.

  The router maintains a per-frame FIFO queue. Dispatch appends to the
  back; the drain loop dequeues, runs the handler, applies effects, and
  loops until the queue empties. Run-to-completion is locked: every event
  dispatched synchronously during a drain settles to fixed point before
  any further external event is processed for that frame, and before any
  view re-renders."
  (:require [re-frame.frame :as frame]
            [re-frame.elision :as elision]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.error :as error]
            [re-frame.error-emit :as error-emit]
            [re-frame.events :as events]
            [re-frame.cofx :as cofx]
            [re-frame.fx :as fx]
            [re-frame.router.diagnostics :as diag]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.privacy :as privacy]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- dispatch-id allocation -----------------------------------------------
;;
;; Per Spec 009 §Dispatch correlation: every dispatch is stamped with a
;; process-monotonic :dispatch-id at queue time. When the dispatch is
;; emitted as a side-effect of another event's processing (typically inside
;; an fx handler running in do-fx), the new dispatch's :parent-dispatch-id
;; is the in-flight event's :dispatch-id.
;;
;; The in-flight dispatch's id is tracked through
;; `re-frame.trace/*handler-scope*`'s `:dispatch-id` slot (the scope-bundle
;; Var lives in `trace` so `trace/emit!` can read it and
;; stamp every trace event emitted inside the cascade with the cascade-
;; wide id). `process-event!` binds the scope around the inner
;; `process-event*`; child dispatches read it both to populate
;; `:parent-dispatch-id` here AND to ride on every emit inside the
;; cascade.
;;
;; All of this rides the dev-only trace surface; production builds (where
;; interop/debug-enabled? is false at compile time) elide the allocation:
;; `build-envelope`'s `(when interop/debug-enabled? (next-dispatch-id))`
;; gate (see below) means the `swap!` and its counter increment are
;; unreachable under `:advanced + goog.DEBUG=false`. The `defonce` atom
;; allocation itself is process-load-time and harmless.

(defonce ^:private dispatch-counter (atom 0))

(defn- next-dispatch-id []
  (swap! dispatch-counter inc))

;; ---- lexical-scope fx-override binding -------------------------
;;
;; Per the `rf/with-fx-overrides` macro (declared in re-frame.core) tests
;; bind this Var to a `{fx-id -> override}` map for the macro body's
;; lexical scope; `build-envelope` merges it into the per-call
;; `:fx-overrides` opt. Precedence: per-call opt > lexical
;; `*fx-overrides*` > per-frame `:fx-overrides` (the existing per-frame
;; merge stays in `apply-overrides` below).
;;
;; Plain map, not a per-frame map: the macro is a test-side ergonomic
;; aimed at "for THIS block of dispatches, swap these fx for stubs"; it
;; applies regardless of which frame each dispatch lands on. Tests that
;; need per-frame overrides keep using `make-frame`'s `:fx-overrides`
;; key (the per-frame tier).
(def ^:dynamic *fx-overrides* nil)

;; ---- EP-0017 recordable-coeffect stamping --------
;;
;; The CAUSAL BOUNDARY: `build-envelope` ensures every dispatch carries an
;; `:rf.cofx` map bearing `:rf/time-ms` — the one host-clock read whose value
;; durable writes may fold (Spec 002 §Recordable coeffects, EP-0010). The
;; recordable-coeffect envelope field is the flat `:rf.cofx` map (one fact per
;; owner-qualified key, no grouping sub-maps); the framework time fact is the
;; flat `:rf/time-ms`.
;; This is the ONLY place the clock is read for the causal token; it is NOT
;; re-read inside the handler, flow transform, resource reducer, work-ledger
;; writer, or commit.
;;
;; `ensure-cofx` owns the shape contract so `build-envelope`'s let does not
;; inline it:
;;
;;   - caller-supplied wins — a test / replay / SSR-hydration / tool dispatch
;;     that supplies `:rf.cofx` has its map PRESERVED verbatim (extra
;;     owner-qualified fact slots ride through). The router fills ONLY the
;;     framework-required `:rf/time-ms` when absent, never overwriting a
;;     supplied one (EP-0010 §Restore, Replay, And Hydration).
;;   - `:rf/time-ms` is WALL-CLOCK EPOCH ms (EP-0010 §Time), read
;;     from `interop/epoch-now-ms` (`js/Date.now()` / `System/currentTimeMillis`)
;;     — NOT `interop/now-ms` (CLJS `performance.now()` is origin-relative, so a
;;     durable timestamp folded from it would be incomparable with `js/Date`-
;;     based freshness checks: resource `:stale-at`, invalidation, etc.).
;;
;; Unlike `:dispatch-id` / the retired `:dispatched-at`, this is NOT dev-gated:
;; recordable coeffects are DURABLE causal data, not a diagnostic, so
;; `:rf/time-ms` must be present in production too. The cost is one
;; `epoch-now-ms` read + a small map on the dispatch path — the price of a
;; deterministic fold.
;;
;; Child dispatches (`:dispatch` / `:dispatch-later` fx) get their OWN map —
;; `:rf.cofx` is deliberately NOT in
;; `re-frame.fx/inheritable-envelope-keys`, so each child re-enters here and is
;; stamped fresh as a distinct causal token (no `:rf/time-ms` inheritance — Spec
;; 002 §Dispatch Envelope Stamping).

(defn- ensure-cofx
  "Return the caller-supplied `:rf.cofx` map with the framework-required
  `:rf/time-ms` filled from `interop/epoch-now-ms` iff absent. A supplied
  `:rf/time-ms` (and every other supplied fact) is preserved verbatim. See the
  section comment above for the full causal-boundary contract."
  [supplied]
  (if (contains? supplied :rf/time-ms)
    supplied
    (assoc supplied :rf/time-ms (interop/epoch-now-ms))))

(defn- build-envelope
  "Build the dispatch envelope per Spec 002 §Routing: the dispatch envelope.
  The envelope carries:
    :event              the user-facing event vector
    :frame              resolved frame keyword per the EP-0002 carried
                        invariant: explicit `{:frame …}` opt wins
                        (override), else `frame/require-current-frame!`
                        reads the scope/hold stamp (`with-frame` /
                        frame-provider / a captured `*current-frame*`
                        binding). Absence raises `:rf.error/no-frame-
                        context` BEFORE any registry lookup — there is no
                        `:rf/default` floor. Per Spec 002 §Frame target
                        resolution — the carried invariant.
    :fx-overrides       per-call fx-id-to-fx-id remapping
    :interceptor-overrides
    :trace-id           tooling
    :source             closed-enum trigger-kind / functional-origin
                        classifier — one of `:ui :frame-init
                        :machine-spawn :machine-action :always
                        :after-timer :fx-dispatch :fx-dispatch-later
                        :http :router :ssr-hydration :test :tool
                        :websocket :repl :unknown :other`.
                        Default `:unknown` — an unstamped dispatch is
                        not attributed as UI-driven. UI handler
                        call-sites stamp `:source :ui` explicitly;
                        substrate-internal dispatch sites stamp the
                        matching specific value:
                          - machine `:after` timer       → :after-timer
                          - machine `:always` microstep  → :always (on the
                            per-microstep trace; `:always` does not
                            produce its own envelope)
                          - machine spawn fx             → :machine-spawn
                          - `:dispatch`(-later) fx from a
                            machine handler              → :machine-action
                            (the actor-message path)
                          - `:dispatch` fx               → :fx-dispatch
                          - `:dispatch-later` fx         → :fx-dispatch-later
                          - routing-internal dispatch    → :router
                          - HTTP reply settle             → :http
                          - SSR hydrate                  → :ssr-hydration
                          - test-harness fixture          → :test
                          - tool / story / REPL          → :tool / :repl
                          - app websocket adapter         → :websocket (opt-in)
                        Every dispatch site stamps the specific kind;
                        there are no broad `:fx` / `:machine` /
                        `:dispatch-later` / `:timer` aliases. `:source`
                        is the single closed-enum functional-origin axis.
    :origin             actor identity tag (:app default; :pair, :story,
                        :test, ... per Spec 002 §Dispatch origin tagging).
                        Open-vocabulary; distinct from :source which is
                        the closed-enum trigger-kind / functional-origin
                        axis.
    :rf.cofx            EP-0017 recordable-coeffect map (flat, one fact per
                        owner-qualified key; Spec 002 §Recordable coeffects).
                        The router ensures it exists and carries `:rf/time-ms`
                        (epoch-ms wall clock) stamped from
                        `interop/epoch-now-ms` HERE — the causal boundary —
                        UNLESS the caller supplied a map, in which case it is
                        preserved and only the missing framework-required
                        `:rf/time-ms` is filled. This is the durable
                        causal-time contract: the read happens ONCE, at
                        envelope construction, never re-read in a handler /
                        flow / resource reducer / commit. Child dispatches get
                        their OWN map — `:rf/time-ms` is NOT inherited (a child
                        is a distinct causal token).
    :dispatch-id        process-monotonic id allocated here per
                        Spec 009 §Dispatch correlation
    :parent-dispatch-id the in-flight dispatch's id when this dispatch is
                        emitted from inside another event's processing
    :call-site          compile-time-captured invocation coord stamped by
                        the `dispatch` / `dispatch-sync` macro.
                        nil for the fn-form path (`dispatch*` etc.) and
                        under `goog.DEBUG=false` advanced builds."
  [event opts]
  (let [dispatch-id        (when interop/debug-enabled? (next-dispatch-id))
        parent-dispatch-id (when interop/debug-enabled?
                             (some-> trace/*handler-scope* :dispatch-id))
        ;; Read the macro-stamped `:rf.trace/call-site`
        ;; only when interop/debug-enabled?. Wrap the read itself in
        ;; the gate so the closure compiler can DCE the keyword
        ;; reference under `:advanced` + `goog.DEBUG=false`. Without
        ;; this gate the `(:rf.trace/call-site opts)` keyword-as-fn
        ;; call survives even when the consuming `cond->` predicate
        ;; is dead, because the keyword's interned-string slot is
        ;; referenced syntactically.
        call-site          (when interop/debug-enabled?
                             (:rf.trace/call-site opts))
        ;; EP-0010 disposition 5: the RETIRED `:dispatched-at`
        ;; dispatch opt gets the STANDARD RETIREMENT TREATMENT — a HARD
        ;; ERROR naming the replacement, NOT the generic warn-on-unknown-opt
        ;; below. Checked FIRST — BEFORE the cofx clock stamp below —
        ;; so a caller still passing `:dispatched-at` fails fast with the
        ;; specific, actionable retirement error (naming
        ;; `(:rf/time-ms (:rf.cofx envelope))`) WITHOUT first triggering
        ;; the `epoch-now-ms` clock read / map allocation for a dispatch that
        ;; cannot proceed. Always-on (not dev-gated): a retirement hard error
        ;; is a correctness contract that must fire in production too — see
        ;; `reject-retired-dispatch-opts!`.
        _                  (diag/reject-retired-dispatch-opts! opts event)
        ;; EP-0010: VALIDATE a caller-supplied
        ;; `:rf.cofx` at the PUBLIC dispatch boundary BEFORE the clock
        ;; stamp below — a supplied value must be nil-or-map and a supplied
        ;; `:rf/time-ms` must be an integer (Spec 002 §Recordable coeffects +
        ;; Spec-Schemas.md §:rf.cofx). A malformed causal token is not
        ;; a harmless typo: it folds straight into durable writes (the epoch
        ;; record's `:committed-at`, resource `:settled-at`) and breaks the
        ;; deterministic fold / replay. Always-on (a corrupt durable token is a
        ;; production correctness contract); fails fast WITHOUT reading the
        ;; clock for a dispatch that cannot proceed — same fail-before-clock-
        ;; read ordering as the retirement check above. See
        ;; `diag/validate-cofx!`.
        _                  (diag/validate-cofx! opts event)
        ;; EP-0017 §Dispatch Envelope Stamping: the
        ;; CAUSAL BOUNDARY — ensure `:rf.cofx` carries `:rf/time-ms`, the one
        ;; host-clock read durable writes fold. `ensure-cofx` owns the
        ;; preserve-supplied / fill-missing-`:rf/time-ms` shape contract (see the
        ;; section comment on the helper above). Stamped AFTER the retirement +
        ;; validation checks so an invalid dispatch never reads the clock.
        cofx               (ensure-cofx (:rf.cofx opts))
        ;; Surface unrecognised opts keys (typically a typo'd
        ;; opt like `:fram` for `:frame`) rather than silently swallowing
        ;; them. Emitted HERE — BEFORE the frame resolution below — so a
        ;; `:fram` typo still gets its specific, actionable warning even
        ;; though the dispatch then fails with `:rf.error/no-frame-context`
        ;; (the typo IS why no `:frame` was carried, so the typo warning is
        ;; the more useful diagnostic to surface first). Dev-only:
        ;; `unknown-dispatch-opts` returns nil under `interop/debug-enabled?
        ;; false`, so the whole `when-let` body — including the diagnostics-
        ;; ns warning fn — DCEs in production. Every dispatch path
        ;; (`dispatch!`, `dispatch-sync!`, the frame-handle ops) funnels
        ;; through here, so this is the single chokepoint for the check. The
        ;; dispatch proceeds unchanged regardless (warn-only).
        _                  (when-let [unknown (diag/unknown-dispatch-opts opts)]
                             (diag/emit-unknown-dispatch-opts-warning! unknown event))
        ;; EP-0002 §Dispatch And Router — the carried-invariant envelope
        ;; frame. Resolution order:
        ;;   1. explicit `{:frame …}` opt WINS (override). A caller who
        ;;      named a frame HAS carried a stamp — that stamp is used
        ;;      verbatim, even if it later proves unregistered (a bad
        ;;      explicit target is a `:rf.error/frame-destroyed` registry-
        ;;      lookup failure at the dispatch site, a DIFFERENT category
        ;;      from absence).
        ;;   2. otherwise `frame/require-current-frame!` reads the
        ;;      scope/hold stamp (`with-frame` / frame-provider via
        ;;      `resolve-current-frame`, or a captured `*current-frame*`
        ;;      binding). When no scope is established and no stamp is
        ;;      carried, it emits the always-on `:rf.error/no-frame-
        ;;      context` (with capture-site ancestry) and THROWS — so the
        ;;      dispatch raises here, BEFORE the frame-registry lookup in
        ;;      `dispatch!` / `dispatch-sync!`, and NOTHING is enqueued.
        ;; There is no `:rf/default` floor: a bare dispatch under no scope
        ;; fails loudly rather than silently mutating an invented default
        ;; (per Spec 002 §Frame target resolution — the carried invariant).
        ;;
        ;; The `:rf.frame/id` extra threads the runtime-context frame-id
        ;; spelling into the error payload's `:event-id` slot when known,
        ;; so a frameless top-level dispatch's error is attributed to the
        ;; event it was carrying.
        ;; EP-0023: the explicit `:frame` opt may be a frame-id
        ;; KEYWORD or a live frame OBJECT (`rf/make-frame`'s return value —
        ;; `(rf/dispatch-sync frame [...])`). Normalize an object to its
        ;; runnable-id ADDRESS via `frame/frame-target->id` so the envelope
        ;; carries a keyword `:frame` and every bare-`frame-id`-keyed cascade
        ;; operation downstream (the router queue/drain, `frame-state-value`,
        ;; the commit path, the sub-cache) stays byte-identical. The
        ;; generation-resolution seam re-resolves the object from this id via the
        ;; live-frame registry (`frame-resolution-target`), so an object target
        ;; and a child dispatch carrying the same id BOTH route the frame's
        ;; image. A keyword target (and the scope/hold-resolved frame) passes
        ;; through `frame-target->id` unchanged.
        frame              (frame/frame-target->id
                             (or (:frame opts)
                                 (frame/require-current-frame!
                                   :dispatch
                                   {:where    're-frame.router/build-envelope
                                    :event-id (first event)})))
        ;; Per Spec 005 §Level 4: a dispatch emitted from a
        ;; machine's own processing (its `:action` / `:entry` / `:exit` /
        ;; transition handling, via `:fx [[:dispatch …]]` or an inter-
        ;; machine dispatch) is a machine-internal continuation. The
        ;; `:dispatch` / `:dispatch-later` fx body stamps
        ;; `:rf.machine/internal? true` on the child opts when the
        ;; emitting handler is a machine (see `child-dispatch-opts` in
        ;; re-frame.fx, which copies the flag off the machine-tagged
        ;; parent envelope). `dispatch!` reads it to insert the envelope
        ;; at the FRONT of the queue so the macrostep settles to
        ;; quiescence before the next EXTERNAL event. This is a runtime
        ;; ordering guarantee — NOT a trace concern — so the flag is
        ;; carried unconditionally (never gated on interop/debug-enabled?).
        machine-internal?  (true? (:rf.machine/internal? opts))
        ;; EP-0017 §6 / slice-B.8: the per-call cofx MINT POLICY
        ;; — the most-specific binding point. A Tool-Pair replay supplies
        ;; `:strict` (so an incomplete record fails loudly rather than minting
        ;; a fresh value); a nondeterminism-declaring test supplies
        ;; `:explicit-live`. nil here ⇒ `assemble-initial-ctx` falls back to
        ;; the frame config's policy (the `:test` preset's `:strict`), else the
        ;; router's `:live` default. Carried UNCONDITIONALLY (a correctness
        ;; lever that gates durable generation, not a dev diagnostic) and only
        ;; when supplied, so the override-free hot path keeps the envelope lean
        ;; and `assemble-initial-ctx` reads `nil` for the common case.
        mint-policy        (:rf.cofx/mint-policy opts)
        ;; rf2-8j4h7i: the `:initial-events` setup runner (frame.cljc
        ;; `run-setup-events!`) stamps `:step-index` into each setup-step's
        ;; dispatch opts as the second half of the EP-0027 §Provenance contract
        ;; (the `:source :frame-init` half is read just below). Without reading
        ;; it here the value was silently discarded at the envelope boundary and
        ;; never reached the `:rf.event/dispatched` trace, so tools could not
        ;; navigate per setup step. Carried onto the envelope ONLY when present
        ;; (the override-free hot path keeps the envelope lean), and stamped on
        ;; the dispatched trace under `:rf.frame/init-step-index`
        ;; (`emit-dispatched-trace`). It is a debug/trace provenance lever, not a
        ;; correctness one — the trace stamp itself is debug-gated there.
        step-index         (:step-index opts)]
    (cond-> {:event                  event
             :frame                  frame
             ;; Merge the lexical-scope `*fx-overrides*`
             ;; (bound by `rf/with-fx-overrides`) under the per-call opt so
             ;; the per-call opt wins on key collision. The per-frame
             ;; tier is still merged later inside `apply-overrides`.
             :fx-overrides           (merge *fx-overrides* (:fx-overrides opts {}))
             :interceptor-overrides  (:interceptor-overrides opts {})
             :trace-id               (:trace-id opts)
             ;; Default `:source` is `:unknown` so an unstamped
             ;; dispatch (frame-init, internal continuations, REPL
             ;; eval) is never misattributed as UI-driven. UI
             ;; handler call-sites stamp `:source :ui` explicitly; the
             ;; `:initial-events` frame-init dispatch (frame.cljc) stamps
             ;; `:source :frame-init`; fx-emit dispatches (fx.cljc)
             ;; inherit the parent's `:source`; tests / REPL stamp
             ;; their own kind. `:unknown` surfaces "we lost track"
             ;; rather than fabricating an origin.
             :source                 (:source opts :unknown)
             ;; Per-source-kind detail riding alongside
             ;; the closed-set `:source` value. Optional; only stamped
             ;; by substrate dispatch sites that carry kind-specific
             ;; payload (e.g. `:dispatch-later` fx stamps `{:ms <ms>}`
             ;; so the Epoch panel's DISPATCH step renders the
             ;; originally scheduled delay alongside the kind label).
             ;; Tools read this off the `:rf.event/source-detail` tag
             ;; on `:rf.event/dispatched` (stamped in
             ;; `emit-dispatched-trace`).
             :source-detail          (:source-detail opts)
             :origin                 (:origin opts :app)
             ;; EP-0017: the flat recordable-coeffect
             ;; map, stamped unconditionally (durable causal data, not a
             ;; diagnostic — see the `cofx` binding above). Always carries
             ;; `:rf/time-ms`; caller-supplied additional owner-qualified facts
             ;; ride through preserved.
             :rf.cofx                cofx}
      ;; The macro form of `dispatch` / `dispatch-sync`
      ;; stamps an `:rf.trace/call-site` on the opts map. The read in
      ;; `call-site` above is gated on interop/debug-enabled? so this
      ;; branch and its keyword literal DCE under :advanced +
      ;; goog.DEBUG=false. fn-form callers (`dispatch*`) supply nil
      ;; and the key is omitted.
      call-site          (assoc :call-site         call-site)
      dispatch-id        (assoc :dispatch-id        dispatch-id)
      parent-dispatch-id (assoc :parent-dispatch-id parent-dispatch-id)
      ;; Carry the machine-internal continuation flag onto
      ;; the envelope so `dispatch!` can front-of-queue insert it.
      machine-internal?  (assoc :rf.machine/internal? true)
      ;; EP-0017 §6 / slice-B.8: carry the per-call cofx mint
      ;; policy onto the envelope only when supplied, so `assemble-initial-ctx`
      ;; reads it (per-call wins over the frame config). Absent ⇒ the key is
      ;; omitted and the frame-config / `:live` fallback applies.
      mint-policy        (assoc :rf.cofx/mint-policy mint-policy)
      ;; rf2-8j4h7i: carry the `:initial-events` setup-step index onto the
      ;; envelope only when present (frame-init dispatches), so
      ;; `emit-dispatched-trace` can stamp it on the dispatched trace as the
      ;; second half of the EP-0027 §Provenance contract.
      step-index         (assoc :step-index step-index))))

(defn- resolve-handler [event-id]
  (registrar/lookup :event event-id))

(defn- resolve-unhandled
  "The pluggable unresolved-handler resolver seam.

  When `resolve-handler` finds no registrar entry for `event-id`,
  `process-event*` consults this before erroring. It is the late-bound
  extension point an optional artefact registers a resolver under to
  MATERIALISE a handler-meta for an otherwise-unregistered event-id. The
  motivating registrant is `re-frame.machines`
  (`:machines/resolve-actor-handler-meta`): a dynamically-spawned actor
  has no per-instance registration — its liveness is derived from its
  (revertible) app-db snapshot — so the resolver rebuilds the actor's
  handler-meta from the snapshot's `:rf/machine-type` on demand. This is
  how an actor's liveness becomes a pure function of app-db: spawn/destroy
  write only the snapshot, and `restore-epoch!` reverts liveness with zero
  registrar drift.

  Returns a registrar-shaped handler-meta map (which `process-event*`
  drives the cascade with, identical to a registered handler) or nil. Nil
  — when the hook is unregistered (machines artefact absent) OR the
  resolver itself declines (no live snapshot for the event-id) — falls
  through to the genuine `:rf.error/no-such-handler` path. The hook
  lookup uses `get-fn-cached`: the machines artefact publishes it once at
  boot and never withdraws it, so the cache hits after the first miss; on
  a machines-free build the cached miss falls straight through.

  Sticky-resolver try/catch (mirrors `validate-event!` /
  `run-post-commit-validation!`): a throw from the resolver must not abort
  the drain — it degrades to nil (the genuine no-such-handler), never
  propagates."
  [event frame]
  (when-let [resolve! (late-bind/get-fn-cached :machines/resolve-actor-handler-meta)]
    (try
      (resolve! event frame)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

;; Cross-frame dispatch-sync warnings + the no-handler error path live
;; in `re-frame.router.diagnostics`. Every one of those fns runs on a
;; cold/error path or sits behind `interop/debug-enabled?`, so the
;; cross-ns indirection adds no measurable cost. (There is no `:rf/default`
;; floor — a bare dispatch under no scope fails loudly with
;; `:rf.error/no-frame-context` at envelope-build time.)

(def ^:private empty-fx-overrides
  "Shared sentinel returned by `apply-overrides` on the no-override hot
  path. Reused across every override-free dispatch so the cascade
  doesn't churn a fresh empty map per event."
  {})

(def ^:private empty-extra-interceptors
  "Shared sentinel for the no-extra-interceptors hot path."
  [])

(def ^:private empty-icpt-overrides
  "Shared sentinel for the no-interceptor-overrides hot path."
  {})

(defn- apply-overrides
  "Per Spec 002 §Per-frame and per-call overrides: per-frame and per-call
  override maps merge with per-call winning. Returns
  `{:fx-overrides :icpt-overrides :extra-interceptors}` for this dispatch.

  HOT PATH: fires on every dispatch. The dominant production path is
  override-free — most apps neither set per-frame `:fx-overrides` /
  `:interceptor-overrides` / `:interceptors` in their `reg-frame`
  config nor pass per-call overrides in the dispatch envelope. On that
  path we short-circuit to shared empty sentinels rather than `merge`-
  ing empty maps and `vec`-ing an empty `concat`.

  Per Spec 002 §`:interceptor-overrides` (lines 1108-1139): per-frame
  and per-call interceptor-override maps merge with per-call winning,
  same as `:fx-overrides`. The merged map is consumed by
  `apply-icpt-overrides` in `prepare-handler-ctx` to substitute
  interceptors in the chain by `:id`."
  [envelope frame-record]
  (let [frame-cfg            (:config frame-record)
        per-call-fx          (:fx-overrides envelope)
        per-frame-fx         (:fx-overrides frame-cfg)
        per-call-icpt        (:interceptor-overrides envelope)
        per-frame-icpt       (:interceptor-overrides frame-cfg)
        frame-interceptors   (:interceptors frame-cfg)]
    (if (and (nil? per-call-fx)
             (nil? per-frame-fx)
             (nil? per-call-icpt)
             (nil? per-frame-icpt)
             (nil? frame-interceptors))
      {:fx-overrides       empty-fx-overrides
       :icpt-overrides     empty-icpt-overrides
       :extra-interceptors empty-extra-interceptors}
      {:fx-overrides       (merge per-frame-fx per-call-fx)
       :icpt-overrides     (merge per-frame-icpt per-call-icpt)
       :extra-interceptors (vec frame-interceptors)})))

(defn- throw-override-invalid!
  "Throw `:rf.error/interceptor-override-invalid` (Spec 002 §`:interceptor-
  overrides` / §Error model) for a malformed override map key or replacement."
  [k v reason]
  (error/throw-error!
    :rf.error/interceptor-override-invalid
    :rf.interceptor/overrides
    reason
    {:recovery :fix-overrides
     :extra    {:key         k
                :replacement v}}))

(defn- override-replacement
  "Resolve an `:interceptor-overrides` replacement VALUE to an executable
  interceptor (or nil to remove). Per EP-0022 §`:interceptor-overrides`:
  public override replacements are a `nil`
  (remove) or an interceptor REFERENCE (keyword / `[id arg]`, resolved through
  the registrar). A value-valued override — an inline interceptor
  value (or any non-ref non-nil) — is `:rf.error/interceptor-override-invalid`,
  keeping the override map serializable + inspectable across story / SSR / test
  / tool surfaces."
  [k replacement]
  (cond
    (nil? replacement)                      nil
    (icpt-reg/interceptor-ref? replacement) (icpt-reg/resolve-ref replacement)
    :else
    (throw-override-invalid!
      k replacement
      (str "interceptor-override replacement for key `" (pr-str k) "` is neither "
           "an interceptor reference (keyword / `[id arg]`) nor `nil` (remove). "
           "Value-valued overrides are retired (EP-0022); register the "
           "replacement with `reg-interceptor` and reference it by id."))))

(defn- valid-override-key?
  "True when `k` is a structurally-valid `:interceptor-overrides` key — an
  interceptor reference (a bare keyword or an `[id arg]` 2-vector). A
  malformed key is rejected with `:rf.error/interceptor-override-invalid`."
  [k]
  (icpt-reg/interceptor-ref? k))

(defn- matching-override-key
  "Return the FIRST `overrides` key whose canonical interceptor reference
  matches chain `entry` (`icpt-reg/override-key-matches?`), or nil. The shared
  entry→override-key matcher for both `apply-icpt-overrides` (which acts on the
  match) and `override-summary` (which tallies it). A non-map entry — the
  framework handler-wrapper sentinel etc. — matches nothing; callers guard for
  it before calling here."
  [overrides entry]
  (some (fn [k] (when (icpt-reg/override-key-matches? k entry) k))
        (keys overrides)))

(defn- apply-icpt-overrides
  "Per Spec 002 §`:interceptor-overrides` (EP-0022 Slice C — exact-reference
  matching): walk `chain` and substitute / remove interceptors against
  `overrides`. Matching is by **canonical interceptor reference**
  (`icpt-reg/override-key-matches?`), not merely by `:id`:

    - a bare-keyword key matches a bare-keyword authored ref OR an entry `:id`
      (covers inline values + the resolver-stamped `:id`);
    - an `[id arg]` key matches ONLY the entry whose AUTHORED ref is `ref=` to
      that exact vector — so `{[:rf.interceptor/path [:cart]] nil}` removes
      only that exact reference, leaving a sibling `[:rf.interceptor/path
      [:cart :items]]` in the chain.

  A matched entry is replaced by its override value (`override-replacement`); a
  `nil`-valued override removes the entry. `chain` carries EXECUTABLE
  interceptor values (refs already resolved + authored-ref-stamped by
  `prepare-handler-ctx`). A malformed override key or replacement is
  `:rf.error/interceptor-override-invalid`.

  HOT PATH no-op: when `overrides` is empty the chain is returned unchanged."
  [chain overrides]
  (if (empty? overrides)
    chain
    (do
      ;; Validate keys once (cheap; override maps are tiny — test / story /
      ;; SSR / tool surfaces). A malformed key fails the whole dispatch loudly.
      (doseq [k (keys overrides)]
        (when-not (valid-override-key? k)
          (throw-override-invalid!
            k (get overrides k)
            (str "interceptor-override key `" (pr-str k) "` is not an interceptor "
                 "reference (expected a keyword id or an `[id arg]` 2-vector)."))))
      (->> chain
           (mapv (fn [entry]
                   (if-not (map? entry)
                     entry
                     (if-let [k (matching-override-key overrides entry)]
                       (override-replacement k (get overrides k))
                       entry))))
           (filterv some?)))))

(defn- override-summary
  "Build the dev-only `:rf.interceptor/override-summary` trace tag (Spec 009
  §`:tags` interceptor family). Summarises which authored
  interceptor references an `:interceptor-overrides` map (merged per-frame +
  per-call, per-call winning) actually acted on for THIS dispatch, by walking
  the PRE-override `resolved-chain` (whose entries still carry their authored
  ref under `icpt-reg/authored-ref-key`, before any matched entry was
  removed/replaced) against the override keys.

  Returns `nil` when `overrides` is empty (the hot no-override path — the tag
  is then omitted entirely, keeping the override-free run-start byte-identical).
  Otherwise returns:

    {:matched  [<authored-ref-id> …]   ;; keys that matched a chain entry
     :replaced [<authored-ref-id> …]   ;; matched keys with a non-nil (ref) replacement
     :removed  [<authored-ref-id> …]   ;; matched keys with a `nil` replacement (removed)
     :count    <int>}                  ;; total number of MATCHED overrides

  STRICTLY ID-ONLY (Spec 015 §The promotion criterion + EP-0022 redaction
  note): the carried values are the override-map KEY references — a bare
  keyword or an `[id arg]` 2-vector head-keyword reference. NEVER an
  interceptor value, executable map, fn, raw factory arg, or raw replacement
  value. A parameterized `[id arg]` ref is EDN-serializable but its `arg` could
  carry app data, so this surface egresses ids/counts only; the marks
  chokepoint (`re-frame.classification/project-trace-event`) enforces the shape
  fail-closed should it ever grow. Unmatched override keys (a key that matched
  no chain entry — the `:rf.error/override-fallthrough` candidate) are NOT
  counted in `:matched`/`:replaced`/`:removed`/`:count`; the tag reports what
  took effect, not what was requested.

  This helper is pure and feeds ONLY the dev-only `:rf.event/run-start` trace
  emit, so it DCEs in `:advanced` production builds with the rest of that emit
  (Spec 009 §Production builds)."
  [resolved-chain overrides]
  (when (seq overrides)
    (let [matched (reduce
                    (fn [acc entry]
                      (if-not (map? entry)
                        acc
                        (if-let [k (matching-override-key overrides entry)]
                          (let [removed? (nil? (get overrides k))]
                            (-> acc
                                (update :matched conj k)
                                (update (if removed? :removed :replaced) conj k)))
                          acc)))
                    {:matched [] :replaced [] :removed []}
                    resolved-chain)]
      (assoc matched :count (count (:matched matched))))))

(defn- validate-event!
  "Per Spec 010 §Validation order step 1: validate the
  dispatched event vector against the handler's :schema BEFORE the
  handler's interceptor chain runs. Failures emit
  :rf.error/schema-validation-failure with :where :event and skip the
  handler (recovery :no-recovery; downstream queue continues).

  Returns truthy when the handler should run, falsy when it should be
  skipped. Defaults to true when the schemas namespace hasn't been
  loaded.

  Body gated on `interop/debug-enabled?`. Spec 010
  validate-*! is a dev-only validator surface — per
  `re-frame.schemas.validate` §Production builds, every dev-time
  `validate-*!` body sits inside its own `(if interop/debug-enabled?
  ...)` gate and DCE-elides under :advanced+goog.DEBUG=false. The
  validator therefore unconditionally returns true in production
  whether or not the schemas artefact is loaded (the boundary-
  validation seam `:schemas/validate-with-registered-fn` is the
  production-side surface, not this one). Gating the router-side
  caller collapses the late-bind lookup, the try/catch frame, and
  the `:schemas/validate-event!` keyword's interned slot to a
  constant `true` on the hot path.

  `frame` is threaded so the `:where :event` failure
  trace carries a `:frame` tag and is captured into the in-flight
  cascade's epoch `:trace-events` by `epoch.capture/capture-event!`
  (which drops any trace whose tags lack `:frame`). Without it the
  violation would fire on the global trace stream but never land in the
  epoch record, so the Xray Issues / Schema-timeline lens would show
  nothing for an event-args schema failure (the `:where :app-db`
  path always tags `:frame`)."
  [event-id event handler-meta frame]
  (if interop/debug-enabled?
    ;; Sticky hook — `:schemas/validate-event!` is published
    ;; once at re-frame.schemas load and never withdrawn in dev; fires
    ;; per-dispatch.
    (if-let [validate! (late-bind/get-fn-cached :schemas/validate-event!)]
      (try (validate! event-id event handler-meta frame)
           (catch #?(:clj Throwable :cljs :default) _ true))
      true)
    true))

(defn- assemble-initial-ctx
  "Build the initial interceptor context per the standard shape. Envelope
  keys (:source :trace-id) are surfaced as cofx entries so handler bodies
  can read them. Per Spec 002 §Routing — the dispatch envelope.

  EP-0001 — the event context threads BOTH durable partitions
  plus the frame id (per Spec 002 §Event context threads both partitions):

    :db            the app-db partition value (the inherited bare key — KEEPS
                   meaning app-db, NOT the whole frame).
    :rf.db/runtime the runtime-db partition value, injected BY REFERENCE (no
                   copy) so a pure app event pays nothing for a partition it
                   never touches.
    :rf.frame/id   the running frame's id — the runtime-context spelling of
                   the frame id, distinct from the public `:frame` opt.

  The runtime-db partition is a real frame-state slot (the one-container
  frame-state): `frame-runtime-db-value` reads the live runtime-db
  projection — `{}` for a fresh frame, the populated partition once a
  subsystem (machines / routing / elision / ssr) has written to it — and that
  value is injected by reference as the `:rf.db/runtime` coeffect.

  `:rf/framework-authority?` is a NON-coeffect context flag (not visible to
  handler bodies) recording whether THIS handler has framework-write
  authority over the reserved `:rf.db/runtime` partition. Per the GENERAL
  minting mechanism (EP-0001), it is true for any handler whose
  registration meta carries the reserved `:rf/framework-authority? true`
  key — stamped by the framework registrars Spec 002 §Write authority names
  (machines, routing; elision / ssr write through privileged frame-state
  helpers, not event effects, so they mint no event-handler authority).
  Machine handlers imply authority from `:rf/machine? true`, so the
  `events/framework-authority?` predicate folds that implication in. The
  effect-commit site reads this flag to decide whether a returned
  `:rf.db/runtime` effect is in-bounds or should fire the
  `:rf.warning/app-handler-runtime-effect` dev diagnostic (reserved BY
  CONVENTION, not a security boundary — Mike ruling #4). It is NOT a
  capability gate: the effect is applied either way."
  [envelope frame frame-record handler-meta fx-overrides]
  (let [event       (:event envelope)
        db-value    (frame/frame-app-db-value frame)
        ;; The live runtime-db projection: `{}` for a fresh frame, the
        ;; populated partition once a subsystem has written to it. Injected by
        ;; reference per Spec 002 §Event context.
        runtime-db  (frame/frame-runtime-db-value frame)
        ;; EP-0017 §5 declared-only delivery: the handler's parsed
        ;; `:rf.cofx/requires` (stored on the registration by
        ;; `events/register-event!`). nil / empty for the overwhelming majority
        ;; of handlers (no declarations) — the delivery step is then a no-op.
        requires    (:rf.cofx/requires-parsed handler-meta)
        ;; EP-0017 §6 / slice-B.8: the EFFECTIVE cofx
        ;; mint policy for this dispatch, resolved ONCE here (per-call envelope
        ;; opt ▸ frame config ▸ `:live`). The event path consumes it inline at
        ;; the `(seq requires)` branch below; it is ALSO stamped onto `base-cofx`
        ;; as a framework coeffect (`:rf.cofx/mint-policy`) so a handler whose
        ;; OWN sub-surfaces declare cofx requirements but whose OUTER event
        ;; declares none — a state machine, whose `:rf.cofx/requires` live on its
        ;; guards/actions, not the outer event — can read the resolved policy and
        ;; thread it into its own ensure step under the SAME mint semantics as the
        ;; event path (a `:strict` replay/`:test` machine guard fact is missing-
        ;; required, never freshly minted). Resolved unconditionally (a single
        ;; keyword resolve); filtered out of the user-cofx trace projection by
        ;; `fx/framework-coeffect-keys`.
        mint-policy (cofx/resolve-mint-policy
                      (:rf.cofx/mint-policy envelope)
                      (:rf.cofx/mint-policy (:config frame-record)))
        base-cofx   (cond-> {:db              db-value
                             :event           event
                             :rf.db/runtime   runtime-db
                             :rf.frame/id     frame
                             ;; EP-0017: the flat
                             ;; recordable-coeffect map (the envelope's
                             ;; canonical complete record) is a framework
                             ;; coeffect alongside `:db` / `:event` /
                             ;; `:rf.db/runtime` / `:rf.frame/id`. Generic code
                             ;; that wants the whole record reads it through the
                             ;; context (Spec 002 §4); handler-declared leaves
                             ;; arrive flat via the delivery step below.
                             ;; Filtered out of the user-cofx trace projection
                             ;; by `fx/framework-coeffect-keys`.
                             :rf.cofx         (:rf.cofx envelope)
                             ;; The resolved effective mint policy,
                             ;; a framework coeffect for the machine ensure path.
                             :rf.cofx/mint-policy mint-policy}
                      (:source envelope)   (assoc :source (:source envelope))
                      (:trace-id envelope) (assoc :trace-id (:trace-id envelope)))]
    ;; EP-0017 §5 step 4: deliver EXACTLY the declared facts, flat. Recordable
    ;; facts come from the token's `:rf.cofx` (validated against `:schema`);
    ;; ambient facts run their suppliers now; a declared-absent GENERATOR-BACKED
    ;; recordable fact is GENERATED at processing-start (slice B.7) UNDER THE
    ;; RESOLVED MINT POLICY (slice B.8 — per-call opt ▸ frame config
    ;; ▸ `:live`; `:strict` does NOT generate and surfaces missing-required) and
    ;; written back into the record; a
    ;; declared-absent PROVIDED fact is `:rf.error/missing-required-cofx`; an
    ;; unregistered declared id is `:rf.error/unregistered-cofx`. Undeclared
    ;; leaves on the token are NOT staged. A supplier / generator that THROWS
    ;; emits `:rf.error/coeffect-exception` and sets `:rf/skip-handler?` (the
    ;; handler does not run; the cascade fails without a raw throw escaping
    ;; assembly).
    ;;
    ;; The delivery returns the (possibly generation-augmented) `:rf.cofx`
    ;; record; we restamp the always-staged `:rf.cofx` coeffect with it so the
    ;; canonical context record carries every generated fact (the epoch
    ;; capture and generic envelope readers see the post-generation token —
    ;; EP-0017 §4). With no generators on the path the record is unchanged.
    (let [{:keys [coeffects rf/skip-handler?] :as delivered}
          (if (seq requires)
            ;; EP-0017 §6 / slice-B.8: the effective cofx MINT
            ;; POLICY for this dispatch (most-specific-wins — per-call opt ▸
            ;; frame config ▸ `:live`) was resolved ONCE above as `mint-policy`
            ;; and is reused here. The policy gates ONLY the declared-absent
            ;; generator-backed branch of `deliver-declared-cofx`; supplied/
            ;; replayed + ambient delivery are policy-independent. Resolved off
            ;; the live frame-record (not stamped on the envelope) so a frame
            ;; re-registered with a different policy takes effect on its next
            ;; dispatch without re-stamping queued envelopes.
            (cofx/deliver-declared-cofx
              base-cofx requires (:rf.cofx envelope) (first event) frame
              mint-policy)
            {:coeffects base-cofx :rf.cofx (:rf.cofx envelope) :rf/skip-handler? false})
          ;; The (possibly generation-augmented) record — restamp the
          ;; always-staged `:rf.cofx` coeffect so the canonical context record
          ;; carries every generated fact (EP-0017 §4).
          coeffects (assoc coeffects :rf.cofx (:rf.cofx delivered))]
      (cond-> {:coeffects coeffects
               :effects {}
               :rf/framework-authority? (events/framework-authority? handler-meta)
               :rf/fx-overrides fx-overrides}
        skip-handler? (assoc :rf/skip-handler? true)))))

(def ^:private handler-wrapping-interceptor-ids
  "The `:id`(s) the event registrar stamps on the handler-wrapping
  interceptor (the terminal `:before` that invokes the user handler). Since
  EP-0018 collapsed the event family to one form, this is the single
  `:rf/event-handler` id (per `re-frame.events/event-handler-interceptor-id`;
  the former per-kind `:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler`
  ids are gone). A captured `:rf/interceptor-error` whose `:id` is in this set
  is the EVENT HANDLER itself throwing (vs. a coeffect injector or a user
  interceptor); it keeps the `:rf.error/handler-exception` category attributed
  to the event. Held here (not imported from `events`) to keep the router's
  classification cycle-free; the id is a stable framework-owned contract.
  Kept as a set so the `contains?` membership check is unchanged."
  #{:rf/event-handler})

(defn- classify-pipeline-exception
  "Classify a captured `:rf/interceptor-error` into the true failing
  component. Returns
  `{:operation <:rf.error/*> :failing-id <kw> :reason <string>}` — the
  category and attribution the exception emit fans out under. The chain
  runner records `{:phase :id}`; this fn reads that captured identity
  rather than blanket-attributing every `:before`-chain throw to the event
  handler:

    - a user interceptor `:before` / `:after` throw (any `:id` that is not
      a handler-wrapper) → `:rf.error/interceptor-exception`,
      `:failing-id` = the interceptor `:id` (the `:phase` slot, carried on
      the trace tags, distinguishes `:before` from `:after`);
    - the event HANDLER itself throwing (the terminal `:before`, `:id` in
      `handler-wrapping-interceptor-ids`) → `:rf.error/handler-exception`,
      `:failing-id` = the event id.

  Coeffect-supplier throws do NOT reach here: coeffect delivery runs at
  context assembly (BEFORE the interceptor chain runs), so a
  supplier throw is captured and emitted as `:rf.error/coeffect-exception`
  by `re-frame.cofx/emit-coeffect-exception!` directly. No interceptor
  carries a `:rf/cofx-id`, so this classifier only ever discriminates
  user-interceptor throws from the event handler itself.

  Mirrors the distinct-by-component precedent the runtime
  follows for `:rf.error/flow-eval-exception` (flow transform)
  and `:rf.error/fx-handler-exception` (post-commit fx walk): each `:before`-
  chain throw is attributed to its own failing component, not blanket-
  attributed to the event handler."
  [error event-id]
  (let [id (:id error)]
    (cond
      (contains? handler-wrapping-interceptor-ids id)
      {:operation  :rf.error/handler-exception
       :failing-id event-id
       :reason     "Event handler threw."}

      :else
      {:operation  :rf.error/interceptor-exception
       :failing-id id
       :reason     (str "Interceptor `" id "` threw in its `"
                        (name (:phase error)) "` phase.")})))

(defn- elapsed-ms-from
  "The integer `:elapsed-ms` for an error / event-emit record: `end-ms`
  minus `start-ms`, floored at 0 and rounded to a long. Owns the
  cross-platform rounding contract in ONE place (§Record shape —
  `:elapsed-ms` is an integer): `interop/now-ms` is a long on
  the JVM (`System/currentTimeMillis`) but a float on CLJS
  (`js/performance.now()` carries sub-millisecond precision), so the value is
  rounded once at the substrate boundary so the record's contract holds on
  both platforms. Callers pass their own single `end-ms` clock read (no
  re-read here) so the emit instant and the elapsed are derived from the same
  reading."
  [start-ms end-ms]
  (long (max 0 (- end-ms start-ms))))

(defn- emit-pipeline-exception!
  "Surface an interceptor-chain exception as the trace event for its TRUE
  failing component AND fan it out through the always-on error-emit
  listener substrate. The chain captures the exception
  into `:rf/interceptor-error` rather than re-throwing (the drain must
  not abort); this helper translates that into both delivery channels.

  The category + `:failing-id` are derived from the
  captured component identity via `classify-pipeline-exception` — a
  coeffect-injection throw emits `:rf.error/coeffect-exception` attributed
  to the cofx id, a user-interceptor throw emits
  `:rf.error/interceptor-exception` attributed to the interceptor id (with
  `:phase` discriminating `:before`/`:after`), and only the event handler
  itself keeps `:rf.error/handler-exception` attributed to the event id.
  The `:handler-id` tag is retained ONLY for the genuine handler case so
  consumers that read it (production-observability shippers) are not
  mis-fed the event id for a coeffect / interceptor failure.

  Schema-derived redaction is reflected in the `:tags :event` slot:
  when the handler's path-scoped db slice overlaps a sensitive schema
  slot, the router-installed redaction interceptor stores the scrubbed
  event form under `:rf/redacted-event`; this helper surfaces it.

  A corpus-wide listener registry runs for off-box
  observability shippers (Sentry / Honeybadger / Rollbar) and MUST fire
  even when the trace surface is compile-time elided in CLJS production
  builds. We build the tight error-record up-front, hand it to
  `error-emit/dispatch-on-error!` (always-on; survives `goog.DEBUG=
  false`), then forward to the dev-only `trace/emit-error!` for trace
  listeners and the retain-N buffer. The trace path enriches the emitted
  event with the cascade's `:dispatch-id` and the in-scope handler's
  source-coord; the always-on path delivers the tight `:error/:event/
  :event-id/:frame/:time/:exception/:elapsed-ms` record to corpus-wide
  listeners."
  [error event-id event frame ctx start-ms]
  (let [exception  (:exception error)
        ;; nil-safe extractor — a thrown non-Error value (legal in
        ;; CLJS) has no `.-message`, so a raw read would silently nil the slot.
        msg        (error/ex-message-safe exception)
        emit-event (privacy/redacted-event-from-ctx ctx)
        end-ms     (interop/now-ms)
        elapsed-ms (elapsed-ms-from start-ms end-ms)
        {:keys [operation failing-id reason]}
        (classify-pipeline-exception error event-id)
        handler-throw? (= operation :rf.error/handler-exception)
        ;; The throwing user interceptor's definition-site
        ;; coord (captured by the `->interceptor` macro and carried on
        ;; the interceptor map → error-record). Threaded onto the
        ;; `:rf.error/interceptor-exception` trace so the Xray Epoch
        ;; INTERCEPTOR row renders a jump-to-source chip (parity with
        ;; EVENT HANDLER / SUBSCRIPTIONS / VIEWS). Absent for the fn-path
        ;; / framework interceptors (`path` / cofx injector) —
        ;; nothing to jump to.
        icpt-coord (:source-coord error)
        tags       (cond-> {:event-id          event-id
                            :event             emit-event
                            :frame             frame
                            :failing-id        failing-id
                            :phase             (:phase error)
                            :exception         exception
                            :exception-message msg
                            :reason            reason
                            :recovery          :no-recovery}
                     ;; `:handler-id` is only meaningful when the EVENT
                     ;; handler itself threw — a coeffect / interceptor
                     ;; failure has no handler-id to carry (the handler
                     ;; never ran), so stamping the event-id regardless
                     ;; would mis-feed consumers.
                     handler-throw? (assoc :handler-id event-id)
                     icpt-coord     (assoc :source-coord icpt-coord))]
    ;; Fan out along BOTH channels (shared helper). Axis 1 — the
    ;; always-on corpus-wide listener: every fn registered through
    ;; `rf/register-error-listener!` receives the tight error-record so
    ;; production builds with the trace surface elided still observe the error.
    ;; Trigger-handler / dispatch-id enrichment is dev-only and rides the trace
    ;; path (`tags`). Axis 2 — the dev-only `trace/emit-error!` (DCE'd in prod).
    (error-emit/emit-error-both!
      operation emit-event event-id frame exception elapsed-ms end-ms tags)))

(defn- run-post-commit-validation!
  "Per Spec 010 §Per-step recovery row 4: validate
  app-db against registered schemas after each commit. Returns the
  validator's boolean conjunction — true when every registered schema
  for the frame conformed (or the schemas artefact isn't loaded / no
  validator is installed); false when at least one schema failed.

  Failures emit :rf.error/schema-validation-failure (one per failing
  entry) with `:rollback? true` and `:recovery :no-recovery` stamped
  in the tag — the caller restores the pre-handler app-db on a false
  return.

  Per Spec 010 §Per-frame schemas the validation walks the schemas
  registered against THIS dispatch's frame only — sibling frames'
  schemas don't fire here.

  Per Spec 010 §Per-step recovery row 7: AND-conjoins the
  app-db validator with `:machines/validate-machine-data!` (the
  `:where :machine-data` boundary). The machine walker iterates
  `[:rf.runtime/machines :snapshots]` in the new RUNTIME-DB value (EP-0001
  — machine snapshots are durable runtime-db state) and validates
  each snapshot's `:data` against the registered machine's `:data-schema`.

  EP-0001: each validator runs against its OWN partition's new
  value — app-db schema validation on `db-after`, machine-data validation on
  `runtime-db-after` — and only when that partition was actually written this
  commit (`app-effect?` / `rt-effect?`). The conjunction means a `false` from
  either rolls back the WHOLE transition; a runtime-only machine commit still
  gets its `:data-schema` boundary, and an app-only commit no longer pays for
  a machine-data walk over a runtime-db that did not change.

  Defensive truth-coercion: a host-thrown validator (e.g. a buggy
  user-supplied :schemas/set-schema-validator! fn) is caught and
  treated as `true` (no rollback) — the validator is failing on
  itself, not on a user schema, and a hard abort here would mask the
  actual app-db state from the rest of the cascade. Real schema
  failures route through the in-band false return.

  The swallowed throw is never silent: the catch
  emits a `:rf.error/malformed-schema` trace before coercing to `true`,
  so a thrown validator is always observable (coercing to `true` without a
  trace would install an unvalidated commit — a fail-OPEN bypass). A
  MALFORMED REGISTERED SCHEMA (childless `[:vector]`, unknown op) does not
  reach this catch at all — `validate-app-schema!` isolates that throw
  per-entry, surfaces its own `:rf.error/malformed-schema`
  trace, fails CLOSED (in-band `false` → rollback), and keeps validating
  the frame's sibling schemas. So a throw THAT REACHES THIS CATCH
  is the validator/late-bind machinery itself failing wholesale — the
  trace makes that visible without masking app-db from the rest of the
  cascade."
  [db-after runtime-db-after app-effect? rt-effect? event-id frame]
  (let [emit-swallow!
        ;; Surface a swallowed validator throw so it is never invisible.
        ;; DCE-gated inside `trace/emit-error!`.
        (fn [where ex]
          (trace/emit-error!
            :rf.error/malformed-schema
            (cond-> {:where     where
                     :frame     frame
                     :reason    (str "Post-commit validator threw and was "
                                     "swallowed (treated as pass, no rollback): "
                                     #?(:clj  (.getMessage ^Throwable ex)
                                        :cljs (ex-message ex)))
                     :rollback? false
                     :recovery  :no-recovery}
              event-id (assoc :failing-id event-id))))
        run-partition-validator!
        ;; The per-partition validator arm template. Runs the
        ;; late-bound `hook-key` validator against the partition's new value
        ;; ONLY when `effect?` (that partition was written this commit) AND the
        ;; hook is installed; otherwise → true (an absent validator / unwritten
        ;; partition is a pass). Resolves the hook ONCE.
        ;; nil-coerce: a nil return is success (don't roll back) so a host
        ;; returning nil on a clean validate keeps working. A host-thrown
        ;; validator is caught, surfaced via `emit-swallow!`, and treated as
        ;; `true` (the validator is failing on itself, not on a user schema; a
        ;; hard abort here would mask the partition's state from the rest of the
        ;; cascade — real schema failures route through the in-band false).
        (fn [effect? hook-key partition-value where]
          (if effect?
            (if-let [validate (late-bind/get-fn-cached hook-key)]
              (try
                (let [result (validate partition-value event-id frame)]
                  (if (nil? result) true result))
                (catch #?(:clj Throwable :cljs :default) ex
                  (emit-swallow! where ex)
                  true))
              true)
            true))
        ;; App-db schema validation runs only when a `:db` effect produced a
        ;; new app-db (app schemas validate app-db only — Mike ruling #11).
        ;; Sticky hook — fires per-dispatch.
        app-ok?
        (run-partition-validator! app-effect? :schemas/validate-app-schema!
                                  db-after :app-db)
        ;; The machine-data boundary (Spec 005 §Schema
        ;; validation). EP-0001: machine snapshots are durable
        ;; runtime-db state, so this validates the new RUNTIME-DB value and
        ;; runs only when a `:rf.db/runtime` effect landed this commit. The
        ;; hook is absent when the machines artefact isn't on the classpath;
        ;; absent → true (no machines means no machine-data to validate).
        machines-ok?
        (run-partition-validator! rt-effect? :machines/validate-machine-data!
                                  runtime-db-after :machine-data)]
    ;; Both must conform for the cascade to keep its commit; the per-
    ;; failure traces have already been emitted independently so the
    ;; operator sees every violation, not just the first.
    (and app-ok? machines-ok?)))

(defn- emit-frame-state-changed!
  "Emit the partition-tagged `:rf.event/frame-state-changed` trace
  (EP-0001 decision #6 / Spec 009 §Canonical per-event trace sequence).
  `changed` is the set of frame-state partition keys that changed by `=`
  (a subset of `#{:rf.db/app :rf.db/runtime}` returned by
  `frame/commit-frame-transition!`); the trace carries
  `:rf.event/partitions` mapped to the tooling-facing tag set
  `#{:app-db :runtime-db}`. Fires only when at least one partition
  changed. Dev-only — `trace/emit!` is internally gated on
  `interop/debug-enabled?`, and the `phase` keyword is carried through for
  the rollback re-emit."
  ([event-id emit-event frame changed]
   (emit-frame-state-changed! event-id emit-event frame changed nil))
  ([event-id emit-event frame changed phase]
   (when (seq changed)
     (let [tags (cond-> #{}
                  (contains? changed frame/app-partition-key)     (conj :app-db)
                  (contains? changed frame/runtime-partition-key) (conj :runtime-db))]
       (trace/emit! :rf.event :rf.event/frame-state-changed
                    (cond-> {:rf.trace/event-id     event-id
                             :rf.event/v            emit-event
                             :frame                 frame
                             :rf.event/partitions   tags}
                      phase (assoc :rf.trace/phase phase)))))))

(defn- emit-db-event!
  "Emit an APP-DB-partition `:rf.event` change trace (`:rf.event/db-changed`
  or `:rf.event/db-noop`) carrying the standard per-event attribution tag set
  (`:rf.trace/event-id` / `:rf.event/v` / `:frame`). The optional `phase`
  (`:rollback` on the post-rollback re-emit) is stamped when present. Sibling
  of `emit-frame-state-changed!` for the app-db-scoped change traces, holding
  the shared tag map in one place (the three commit-time call sites all emitted
  the identical base map). Dev-only — `trace/emit!` is internally gated on
  `interop/debug-enabled?`."
  ([op event-id emit-event frame] (emit-db-event! op event-id emit-event frame nil))
  ([op event-id emit-event frame phase]
   (trace/emit! :rf.event op
                (cond-> {:rf.trace/event-id event-id
                         :rf.event/v        emit-event
                         :frame             frame}
                  phase (assoc :rf.trace/phase phase)))))

(defn- commit-frame-effects!
  "Install the partitioned frame transition atomically (EP-0001,
  Spec 002 §Drain-loop pseudocode §commit + §An ordinary :db return replaces
  only app-db). A cascade may produce:

    - an ordinary `:db` effect — the app-db partition write (scoped to
      app-db; `:db` is NOT the whole frame);
    - a reserved `:rf.db/runtime` effect — the runtime-db partition write
      (whole-value replacement; decision #5 — operation-style writes go
      through `swap-frame-db!` / the runtime mutators);
    - both — installed as ONE coherent transition;
    - neither — a no-op (a pre-install throw leaves no partition effect, so
      this installs nothing and the event aborts with both partitions
      unchanged).

  Both partitions install in ONE atomic `commit-frame-transition!` on the
  single physical frame-state container — there is never a window where one
  partition is committed and the other is not (Spec 006 §Commit boundary).

  Returns true when the commit is durable (no partition effect, or app-db
  schema conformed); false when post-commit app-db schema validation
  rejected the new state and the frame-state has been **rolled back** to the
  pre-handler value (per Spec 010 §Per-step recovery row 4). App-db schema
  validation is APP-DB-ONLY (app schemas validate
  the app partition, Mike ruling #11); a rejection unwinds the WHOLE
  transition (both partitions) so the frame is left coherently at its
  pre-handler state.

  Change traces (per Spec 009 §Canonical per-event trace sequence):
    - `:rf.event/db-changed` — APP-DB-ONLY (Mike ruling #6): fires only when
      the app-db partition changed; NEVER for a runtime-only commit;
    - `:rf.event/db-noop` — APP-DB-ONLY: fires when a `:db`
      effect was present but app-db did NOT change (the handler returned an
      unchanged db; the `identical?`-noop fast-path in
      `commit-frame-transition!` skipped the write, or a distinct-but-`=`
      value collapsed to no change). Makes the no-op visible — \"event
      returned an unchanged db; nothing committed\" — rather than silent;
    - `:rf.event/frame-state-changed` — fires when EITHER partition changed,
      partition-tagged (`#{:app-db}` / `#{:runtime-db}` / both).
  A runtime-only commit emits ONLY frame-state-changed (`#{:runtime-db}`);
  an app-only commit emits both (db-changed + frame-state-changed
  `#{:app-db}`); an unchanged-db `:db` commit emits db-noop (and no
  frame-state-changed for the app-db partition).

  nil-coercion: a `:db nil` effect is coerced to `{}` HERE —
  at the `:db` effect → `:rf.db/app` partition mapping, before the commit —
  so the partition layer never sees a nil app-db (app-db is always a map).
  The coercion emits a dev-mode `:rf.warning/db-nil-coerced` diagnostic for
  accidental-wipe visibility; a deliberate clear (`{:db {}}`) does not.

  Per Spec 013 §Drain integration: `(:db effects)` here is the
  FLOW-AUGMENTED app-db value — the OUTERMOST flows-after-interceptor has
  already rewritten the pending `:db` effect by the time the chain returns.
  So `:event/db-changed` reflects the flow-derived db and fires AFTER
  `:rf.flow/computed` (per Spec 009 §Canonical per-event trace sequence).

  On rollback, a second `:event/db-changed` (+ `frame-state-changed`) trace
  is emitted for the restored state with `:phase :rollback` so listeners
  (subs, 10x, pair-tools) observe the post-rollback frame-state without
  ambiguity — the trace stream's load-bearing ordering is `:db-changed
  (post-handler) → :rf.error/schema-validation-failure → :db-changed
  (post-rollback)`, mirroring the depth-exceeded pattern (the error trace
  fires AFTER the container is back at its pre-handler value).

  Schema-derived redaction is reflected in the change traces' `:tags :event`
  slot via `privacy/redacted-event-from-ctx`.

  On rollback the flow dirty-check (`last-inputs`)
  bookkeeping is rolled back in lock-step (the flow transform advanced each
  computed flow's row inside the chain; restoring app-db without restoring
  those rows would leave the dirty-check believing the flows are
  up-to-date). `ctx` carries the pre-drain snapshot under
  `:rf/flow-last-inputs-before`; the rollback arm restores it through the
  frame-scoped `:flows/restore-last-inputs!` hook."
  [effects event-id event frame ctx db-before runtime-before]
  (let [app-effect?  (contains? effects :db)
        rt-effect?   (contains? effects :rf.db/runtime)
        ;; EP-0025: the four commit-plane classification effects
        ;; (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) are
        ;; applied WITH the `:db` write at this commit point (NOT a post-commit
        ;; `:fx`). They write the per-frame elision registry, which lives in the
        ;; runtime-db partition (`[:rf.runtime/elision …]`), so a classification
        ;; effect is committed as a RUNTIME-DB partition write folded into the
        ;; SAME atomic `commit-frame-transition!` as `:db` — a same-event
        ;; classify-then-egress therefore redacts. (The validation already ran
        ;; fail-loud pre-commit in `events/commit-fx-effects`; here we only
        ;; APPLY the validated declaration.)
        class-effect? (elision/classification-effect? effects)
        ;; nil-coercion: app-db is ALWAYS a map, never nil. A
        ;; `:db nil` effect is coerced to `{}` HERE — at the `:db` effect →
        ;; `:rf.db/app` partition mapping, BEFORE `commit-frame-transition!` —
        ;; so the partition layer never sees a nil app-db. This rules out a
        ;; db handler returning nil wiping app-db to nil
        ;; structurally, at the commit boundary. A `:db nil` return is more
        ;; often a BUG (a handler accidentally computed nil) than a deliberate
        ;; clear, so the coercion emits a dev-mode `:rf.warning/db-nil-coerced`
        ;; diagnostic for accidental-wipe visibility; a DELIBERATE clear writes
        ;; `{:db {}}` directly (a distinct, non-nil empty map — no diagnostic).
        nil-db?      (and app-effect? (nil? (:db effects)))
        new-db       (if nil-db? {} (:db effects))]
    (when (and nil-db? interop/debug-enabled?)
      (trace/emit! :warning :rf.warning/db-nil-coerced
                   {:rf.trace/event-id (when (vector? event) (first event))
                    :rf.event/v        event
                    :frame             frame
                    :recovery          :warned
                    :reason
                    (str "Event `" (when (vector? event) (first event)) "` returned `{:db nil}`. "
                         "app-db is always a map, never nil — the nil was coerced to `{}` "
                         "at the commit boundary (the v1 nil-footgun is removed structurally). "
                         "A `{:db nil}` return is usually a BUG (a handler accidentally computed "
                         "nil); for a deliberate clear, return `{:db {}}` (which emits no "
                         "diagnostic).")}))
    (if (or app-effect? rt-effect? class-effect?)
      (let [emit-event (privacy/redacted-event-from-ctx ctx)
            ;; A whole-value `:rf.db/runtime` effect REPLACES the
            ;; runtime-db partition (decision #5), but the elision declaration
            ;; registry at `[:rf.runtime/elision]` is a CROSS-CUTTING durable
            ;; subsystem child written OUT-OF-BAND by `reg-flow` / the EP-0025 classification effects /
            ;; frame-classification — not by the event returning the effect. An
            ;; effect that seeds an unrelated subsystem (e.g.
            ;; `:rf.runtime/routing`) and omits `:rf.runtime/elision` would
            ;; otherwise drop the registry on commit, silently losing every
            ;; declaration that correctly redacted PRE-commit. Reconcile the
            ;; effect value against the pre-commit runtime-db so the registry
            ;; survives unless the effect speaks about it explicitly (a
            ;; full-frame install / deliberate clear is honoured verbatim).
            ;;
            ;; Privacy fail-open: reconcile against the LIVE
            ;; runtime-db read AT COMMIT, NOT the chain-start `runtime-before`
            ;; snapshot. The flow drain's `refresh-flow-output-declarations!`
            ;; writes propagated output-sensitivity marks straight into the
            ;; LIVE runtime-db `[:rf.runtime/elision]` slot DURING the `:after`
            ;; chain (after `runtime-before` was captured by reference in
            ;; `assemble-initial-ctx`). When the handler ALSO returns a
            ;; `:rf.db/runtime` effect, reconciling against the STALE snapshot
            ;; carries the PRE-refresh registry forward and the commit overwrites
            ;; the just-written mark — so a flow-derived sensitive path egresses
            ;; RAW for one commit (until the next drain re-propagates). Reading
            ;; the live runtime-db here picks up the refreshed marks: the
            ;; registry the reconcile preserves is the freshest one, not the
            ;; one captured before the drain ran. (The whole-frame-install /
            ;; deliberate-clear path is unaffected — an effect that carries
            ;; `:rf.runtime/elision` is still honoured verbatim.) `runtime-before`
            ;; remains the rollback target below — rollback must restore the
            ;; PRE-handler state, so it correctly stays the chain-start snapshot.
            ;; Read the LIVE runtime-db whenever a runtime-db OR a
            ;; classification effect commits — both need the freshest registry
            ;; (the flow drain may have just written propagated marks; see the
            ;; reconcile fail-open note above) as their base.
            live-runtime-db (when (or rt-effect? class-effect?)
                              (frame/frame-runtime-db-value frame))
            ;; The reconciled runtime-db partition value (only when a
            ;; `:rf.db/runtime` effect landed). EP-0025: a classification
            ;; effect (`:sensitive` / `:large` / `:clear-sensitive` /
            ;; `:clear-large`) is then APPLIED onto that base — onto the
            ;; reconciled `:rf.db/runtime` value when one landed, else onto the
            ;; live runtime-db — folding the per-frame elision-registry write
            ;; (`[:rf.runtime/elision …]`) into the SAME atomic transition as
            ;; `:db`. This is a partition-write alongside `:db`: it reuses the
            ;; existing runtime-db reconcile protection (the registry survives a
            ;; whole-value runtime-db effect) AND commits the classification at
            ;; the exact `:db` boundary, so a same-event classify-then-egress
            ;; redacts. The axes are independent and the write is
            ;; value-independent (it marks a path, not a value).
            reconciled-rt  (when rt-effect?
                             (elision/reconcile-runtime-db-effect
                               (:rf.db/runtime effects) live-runtime-db))
            new-runtime-db (cond
                             ;; classification effect → apply onto the base
                             ;; runtime-db (reconciled rt-effect value or live).
                             class-effect?
                             (elision/apply-classification-effects
                               (if rt-effect? reconciled-rt live-runtime-db)
                               effects)
                             ;; runtime-db effect only — reconciled value.
                             rt-effect? reconciled-rt
                             :else nil)
            ;; Whether the runtime-db partition participates in this commit: a
            ;; `:rf.db/runtime` effect OR a classification effect (which writes
            ;; the elision registry, a runtime-db child). A no-op classification
            ;; (e.g. clearing an unclassified path) collapses to no change at
            ;; `commit-frame-transition!`, so a stray partition write is harmless.
            rt-partition?  (or rt-effect? class-effect?)
            ;; Map the EFFECT keys (:db / :rf.db/runtime) to the frame-state
            ;; PARTITION keys (:rf.db/app / :rf.db/runtime). `:db` scopes to
            ;; the app-db partition; `:rf.db/runtime` (and the classification
            ;; effects' registry write) to runtime-db. A partition not present
            ;; is carried forward unchanged by `commit-frame-transition!`.
            ;; `new-db` is the nil-coerced value.
            partitions (cond-> {}
                         app-effect?   (assoc frame/app-partition-key     new-db)
                         rt-partition? (assoc frame/runtime-partition-key new-runtime-db))
            ;; ONE atomic frame-state install. Returns the set of partition
            ;; keys that actually changed by `=`.
            changed    (frame/commit-frame-transition! frame partitions)
            app-changed? (contains? changed frame/app-partition-key)]
        ;; APP-DB-ONLY db-changed (Mike ruling #6) — only when the app-db
        ;; partition actually changed. A runtime-only commit never fires it.
        (when app-changed?
          (emit-db-event! :rf.event/db-changed event-id emit-event frame))
        ;; db-noop: a `:db` effect that left app-db UNCHANGED — the
        ;; handler returned an unchanged db (the `identical?`-noop fast-path in
        ;; `commit-frame-transition!` skipped the write, OR a distinct-but-`=`
        ;; value collapsed to no change). The forward commit is a genuine
        ;; no-op for the app-db partition, so make it VISIBLE rather than
        ;; silent: Xray can show "event returned an unchanged db; nothing
        ;; committed." Fires only when a `:db` effect was present AND app-db did
        ;; not change; suppressed when app-db changed (the `db-changed` signal
        ;; covers that) and when no `:db` effect was returned at all.
        (when (and app-effect? (not app-changed?))
          (emit-db-event! :rf.event/db-noop event-id emit-event frame))
        ;; Partition-tagged frame-state-changed — when EITHER partition changed.
        (emit-frame-state-changed! event-id emit-event frame changed)
        ;; Post-commit validation runs per-partition (EP-0001):
        ;; app-db schema validation on the new app-db (only when a `:db` effect
        ;; landed — app schemas validate app-db only, Mike ruling #11) AND the
        ;; machine-data `:where :machine-data` boundary on the new runtime-db
        ;; (only when a `:rf.db/runtime` effect landed — machine snapshots are
        ;; durable runtime-db state). A `false` from either rolls back the
        ;; WHOLE transition.
        (if (run-post-commit-validation! new-db new-runtime-db
                                         app-effect? rt-effect? event-id frame)
          ;; EP-0001: framework runtime subsystems live in the runtime-db
          ;; partition, not in app-db under `:rf/runtime` — so a fresh
          ;; `{:db fresh-map}` cannot drop co-located runtime state
          ;; (Conventions §The clobber footgun is eliminated structurally). The
          ;; `:rf/runtime` app-db key is a hard error elsewhere; no per-commit
          ;; detector is needed here.
          true
          (do
            ;; Roll back the WHOLE transition (both partitions) to the
            ;; pre-handler frame-state so the frame stays coherent — app-db
            ;; schema rejection unwinds any runtime-db write in the same
            ;; cascade too. Then emit the rollback change traces so subs /
            ;; listeners see the restored state. The schema-failure error
            ;; trace already fired between the forward and rollback commits.
            ;;
            ;; Privacy fail-safe (forward/rollback symmetry, rf2-qzs1y9): the
            ;; forward commit above reconciles the runtime-db effect against the
            ;; LIVE runtime-db so flow-written elision marks ([:rf.runtime/elision])
            ;; propagated during the `:after` drain SURVIVE the commit. The
            ;; rollback target `runtime-before` is the chain-start snapshot
            ;; captured by reference BEFORE the drain ran, so it predates those
            ;; marks. Restoring it verbatim would silently DISCARD the
            ;; flow-written elision declarations — the very marks the forward
            ;; path takes care to preserve — and a flow-derived sensitive path
            ;; would egress RAW until the next successful drain re-propagated.
            ;; The elision registry is CROSS-CUTTING durable subsystem state
            ;; written OUT-OF-BAND (`reg-flow` / the EP-0025 classification effects / frame-
            ;; classification), NOT part of the transactional handler effect the
            ;; schema rejected — so it must NOT be unwound with the rejected db.
            ;; Carry the LIVE registry (the freshest flow-propagated marks, as
            ;; preserved by the forward reconcile and still resident on the
            ;; container) forward onto the restored `runtime-before`, mirroring
            ;; the forward path. A stale declaration for a path the rolled-back
            ;; db no longer contains is harmless (it redacts nothing); dropping a
            ;; live one risks raw egress — fail safe toward redaction.
            (let [live-elision (get (frame/frame-runtime-db-value frame)
                                    :rf.runtime/elision)
                  runtime-restore (elision/write-elision-slot runtime-before
                                                              live-elision)
                  rb-changed (frame/replace-frame-state!
                               frame
                               {frame/app-partition-key     db-before
                                frame/runtime-partition-key runtime-restore})]
              ;; Roll back the flow dirty-check
              ;; (`last-inputs`) bookkeeping in lock-step with the app-db
              ;; (frame-scoped). No-op when no flow ran or the
              ;; flows artefact never loaded.
              (when (contains? ctx :rf/flow-last-inputs-before)
                (when-let [restore-li (late-bind/get-fn-cached :flows/restore-last-inputs!)]
                  (restore-li frame (:rf/flow-last-inputs-before ctx))))
              ;; Re-record the IN-DRAIN abandoned output paths the
              ;; flow transform drained-and-cleared. The pending `:db` (which
              ;; carried the vacated state) was just discarded by the rollback,
              ;; so the `:path` move must re-attempt next drain rather than be
              ;; silently lost — the exact mirror of the `last-inputs` restore
              ;; above, at the post-commit boundary `run-flows-on-db` can't see.
              (when (contains? ctx :rf/flow-abandoned-paths-before)
                (when-let [restore-ap (late-bind/get-fn-cached :flows/restore-abandoned-paths!)]
                  (restore-ap frame (:rf/flow-abandoned-paths-before ctx))))
              (when (contains? rb-changed frame/app-partition-key)
                (emit-db-event! :rf.event/db-changed event-id emit-event frame :rollback))
              (emit-frame-state-changed! event-id emit-event frame rb-changed :rollback))
            false)))
      true)))

(def ^:private flows-after-interceptor
  "Per Spec 013 §Drain integration: the framework-owned
  OUTERMOST `:after` interceptor that runs the flow transform. The router
  PREPENDS it to the dispatch-time `full-chain` (NOT the registered
  handler-meta chain — see `prepare-handler-ctx`), so it is the first
  interceptor in declaration order and therefore the LAST `:after` to
  fire: after the rest of the `:after` chain, before `:db` install, and
  before `:fx`.

  Outermost (not innermost) is load-bearing: the `path` std-interceptor's
  `:after` splices the handler's slice back into the FULL db, and flows
  read full-app-db `:inputs` paths — so the flow transform MUST run after
  that reshape. Running flows innermost would expose them to the
  un-spliced path slice and mis-read their inputs.

  Its `:after` reads the chain's PENDING `:db` effect (the full,
  fully-reshaped value — or the current app-db value when no `:db` effect
  was produced), runs `:flows/run-flows-on-db` over that value, and writes
  the flow-augmented db back into `(:effects ctx :db)`. The eventual `:db`
  install and the `:fx` walk therefore observe the flow-derived db.

  When the flows artefact is absent (`:flows/run-flows-on-db` hook nil)
  the `:after` is a single nil-check no-op.

  Failure (Spec 013 §Failure semantics — atomicity contract): a flow throw
  is a PRE-INSTALL throw, so it aborts the whole
  event exactly like a handler / interceptor-`:after` throw. The `:after`
  catches the ex-info, DISCARDS the pending `:db` effect (`dissoc`-ing it
  from `(:effects ctx)`) so the single deferred install installs NOTHING —
  app-db is left UNCHANGED, NO `:rf.event/db-changed` is emitted, and NO
  `:fx` run. There is no partial commit: prior successful flows' writes do
  NOT land either (they were only ever in the pending `:db` effect, never
  installed). The throw is stashed under `:rf/flow-error` so
  `commit-and-flow!` can emit `:rf.error/flow-eval-exception` and skip the
  install + `:fx`. It is NOT recorded into `:rf/interceptor-error` — a
  flow-eval failure is a distinct error category from a handler/interceptor
  exception, with its own substrate routing and `:where :flow-eval`
  discriminator.

  Frame-agnostic: a single shared interceptor value (no per-dispatch
  allocation). The dispatching frame is read from the context coeffects
  (`assemble-initial-ctx` stamps `:frame`).

  This interceptor is also the emit
  point for the t1 / t2 pending-`:db` snapshot pair on the trace
  stream. The handler returned its `:db` effect; the rest of the
  `:after` chain reshaped it (e.g. `path`-interceptor splice) and the
  flow transform may (or may not) reshape it further before the single
  deferred install. The pair stamps the full value at two endpoints:

    t1 `:rf.event/db-pending`            — POST-handler-chain,
                                            PRE-flow-transform (the
                                            value the handler chain
                                            returned, before any flow
                                            could touch it).
    t2 `:rf.event/db-pending-post-flow`  — POST-flow-transform, PRE-
                                            commit (the value flows
                                            reshaped, when they did).

  The full value is stored plain — same posture as
  `:rf.event/fx` on `:rf.fx/do-fx`. Persistent data structures make
  the cost a pointer per emit (structural sharing with app-db); no
  copy, no diff, no DEBUG conditional. `day8/de-dupe` at the pair-mcp
  wire boundary collapses the repeated subtrees on
  egress. The Xray Handler panel reads t1 to render the returned
  `:db` value under EVENT HANDLER and (t1, t2) together to render
  the t1→t2 reshape under FLOWS — the framework does NOT precompute
  a diff.

  Emit gating:
  - t1 fires when `has-db?` is true (the handler returned a `:db`
    slot — mirrors how `:rf.event/fx` only carries data when there
    is `:fx`). Fires regardless of whether the flows artefact is
    loaded — apps that never registered a flow still get t1.
  - t2 fires only when flows actually transformed the pending value
    (`(not (identical? new-db pending-db))`). If no flow's `:after`
    touched `:db`, t2 == t1 and the second emit is omitted (no
    information). Implies: no-flows-artefact apps never emit t2.

  Both emits sit inside `trace/emit!` which is DCE-gated by
  `interop/debug-enabled?` — production CLJS bundles fold both away.
  An aborted-by-flow-throw event (the catch arm) does NOT emit t2:
  the partial-cascade `:db` was discarded along with all flow side
  effects (no `:rf.event/db-changed` will fire either)."
  (interceptor/->interceptor*
    :id          :rf/flows
    :rf/default? true
    :after
    (fn [ctx]
      (let [frame       (:rf.frame/id (:coeffects ctx))
            effects     (:effects ctx)
            has-db?     (contains? effects :db)
            ;; EP-0001 §535-551: a runtime-db write also lands as
            ;; a pending `:rf.db/runtime` effect (e.g. a pure
            ;; `:rf.route/transitioned` returns `{:rf.db/runtime …}` and NO
            ;; `:db`). The flow transform must observe the SETTLED pending
            ;; runtime-db so a flow reading a `[:rf.db/runtime …]`-qualified
            ;; input recomputes — the dual-partition TRIGGER (§542-544) keys on
            ;; BOTH partitions, NOT on app-db publication alone. Missing this
            ;; is a SILENT regression: a route/machine-reading flow would just
            ;; stop updating. Note `has-runtime-effect?` (not `has-db?`):
            ;; the runtime-db is resolved independently of whether the handler
            ;; touched app-db.
            has-runtime-effect? (contains? effects :rf.db/runtime)
            run-on-db   (late-bind/get-fn-cached :flows/run-flows-on-db)
            pending-db  (if has-db?
                          (:db effects)
                          (when run-on-db (frame/frame-app-db-value frame)))
            ;; The pending runtime-db partition the flows read their qualified
            ;; inputs against: the handler's `:rf.db/runtime` effect when one
            ;; landed, else the current (unchanged) runtime-db. Only resolved
            ;; when the flows artefact is loaded — apps without flows never
            ;; touch it. Flow outputs write app-db only (runtime writes
            ;; reserved, §539), so this value is read-only for the whole pass.
            pending-runtime-db (when run-on-db
                                 (if has-runtime-effect?
                                   (:rf.db/runtime effects)
                                   (frame/frame-runtime-db-value frame)))
            ;; Stamp `:rf.event/v` + `:rf.trace/event-id`
            ;; on the t1 / t2 trace events so they carry the same
            ;; per-event attribution every other `:op-type :rf.event`
            ;; emit carries (parity with `:rf.event/run-start` /
            ;; `:rf.event/run-end` / `:rf.event/db-changed`; tests like
            ;; `inv-6-frame-created-not-folded-into-next-epoch` assert
            ;; every event-family trace in an epoch carries the
            ;; epoch's `:rf.event/v`). Read the redacted event from
            ;; ctx so schema-sensitive event payloads ride the same
            ;; scrubbed value the rest of the family does.
            emit-event  (when has-db? (privacy/redacted-event-from-ctx ctx))
            event-id    (when has-db? (some-> emit-event first))]
        ;; t1 — stamp the handler-returned (post-`:after`-chain, pre-
        ;; flow-transform) `:db` value. Always fires when the handler
        ;; returned `:db`, whether or not the flows artefact is loaded.
        ;; The value is the persistent reference; structural sharing
        ;; with app-db means the emit cost is pointer-sized.
        (when has-db?
          (trace/emit! :rf.event :rf.event/db-pending
                       {:rf.trace/event-id event-id
                        :rf.event/v        emit-event
                        :frame             frame
                        :rf.event/db       pending-db}))
        (if run-on-db
          (try
            (let [;; Snapshot THIS frame's
                  ;; dirty-check (`last-inputs`) rows BEFORE the flow transform
                  ;; advances them. The transform eagerly advances a flow's row
                  ;; the moment it recomputes, folding the output into the
                  ;; pending `:db`. But whether that pending `:db` becomes
                  ;; DURABLE is decided AFTER the chain, in `commit-db-effect!`:
                  ;; a POST-commit schema / machine-data validation failure
                  ;; rolls app-db back to `db-before`. `run-flows-on-db`'s own
                  ;; throw-path snapshot/restore cannot cover that — the
                  ;; rollback lands outside it. Without restoring here, the
                  ;; advanced rows survive a rollback, so the next clean drain
                  ;; sees `=`-equal inputs, SKIPS the flow, and the output
                  ;; never re-materialises (a deterministic dev/test failure
                  ;; can permanently suppress a flow). We stash the pre-drain
                  ;; snapshot on the ctx; `commit-db-effect!` restores it iff it
                  ;; rolls back — the exact mirror of the throw-path rollback,
                  ;; at the post-commit boundary. Frame-scoped: the
                  ;; snapshot is `frame`'s own container, structurally unable to
                  ;; touch a sibling frame draining on another thread. The
                  ;; snapshot is a persistent map (pointer-sized to stash); the
                  ;; hook is nil only when the flows artefact never loaded, in
                  ;; which case there are no rows and nothing to restore.
                  snapshot-li (late-bind/get-fn-cached :flows/snapshot-last-inputs)
                  li-before   (when snapshot-li (snapshot-li frame))
                  ;; Snapshot the frame's pending abandoned-output-
                  ;; paths BEFORE the transform (it DRAINS/clears them and
                  ;; dissocs them from the pending `:db`). On a POST-commit
                  ;; rollback `commit-frame-effects!` re-records this snapshot —
                  ;; the exact mirror of the `last-inputs` snapshot above, for
                  ;; the boundary `run-flows-on-db`'s own throw arm cannot see.
                  snapshot-ap (late-bind/get-fn-cached :flows/snapshot-abandoned-paths)
                  ap-before   (when snapshot-ap (snapshot-ap frame))
                  ;; EP-0001 §535-551: hand the flow transform
                  ;; BOTH partitions of the pending frame-state. Bare `:inputs`
                  ;; resolve against `pending-db` (app-db); `[:rf.db/runtime …]`
                  ;; inputs resolve against `pending-runtime-db`. The returned
                  ;; value is the flow-augmented APP-DB (runtime-db is read-only
                  ;; for the pass).
                  new-db (run-on-db frame pending-db pending-runtime-db)]
              ;; t2 — flows transformed the pending `:db`. Stamp the
              ;; flow-augmented value so the Xray panel can render the
              ;; t1→t2 reshape. The dirty-check below is the same
              ;; identical-by-reference guard the effect-publish below
              ;; uses; t2 fires on the same condition that a `:db`
              ;; effect survives to `commit-db-effect!`. t2 may fire
              ;; even when the handler returned
              ;; no `:db` (flows synthesised one from app-db); resolve
              ;; the attribution-event lazily so the no-handler-`:db`
              ;; case still carries it.
              (when (not (identical? new-db pending-db))
                (let [t2-event   (or emit-event (privacy/redacted-event-from-ctx ctx))
                      t2-evt-id  (or event-id (some-> t2-event first))]
                  (trace/emit! :rf.event :rf.event/db-pending-post-flow
                               {:rf.trace/event-id t2-evt-id
                                :rf.event/v        t2-event
                                :frame             frame
                                :rf.event/db       new-db})))
              ;; Only publish a `:db` effect when flows actually changed
              ;; the value OR the handler already had one — a no-flow /
              ;; no-write event must not synthesise a spurious `:db`
              ;; effect (which would force an app-db install + db-changed
              ;; trace on an event that wrote nothing). When we DO publish a
              ;; `:db`, stash the pre-drain dirty-check snapshot + the
              ;; restorer fn so `commit-db-effect!` can
              ;; roll `last-inputs` back in lock-step with an app-db rollback.
              ;; A no-flow / no-write event publishes no `:db`, hits no
              ;; commit/rollback boundary, and needs no snapshot.
              (if (or has-db? (not (identical? new-db pending-db)))
                (cond-> (interceptor/assoc-effect ctx :db new-db)
                  snapshot-li (assoc :rf/flow-last-inputs-before li-before)
                  ;; Stash the pre-drain abandoned-paths snapshot so
                  ;; a post-commit rollback can re-record the drained-but-not-
                  ;; durably-vacated path moves. Only when a `:db` is published
                  ;; (a no-`:db` event hits no commit/rollback boundary).
                  snapshot-ap (assoc :rf/flow-abandoned-paths-before ap-before))
                ctx))
            (catch #?(:clj Throwable :cljs :default) e
              ;; Atomicity contract (Spec 013 §Failure semantics): a flow
              ;; throw is a PRE-INSTALL throw, so it
              ;; aborts the whole event. DISCARD any pending `:db` effect
              ;; (the handler's and any prior flows' writes) so the single
              ;; deferred install installs NOTHING — app-db stays unchanged,
              ;; no `:rf.event/db-changed`, no `:fx`. No partial commit.
              ;; Stash the throw under `:rf/flow-error` so `commit-and-flow!`
              ;; surfaces `:rf.error/flow-eval-exception` and skips install +
              ;; `:fx`. `dissoc`-ing `:db` is the whole mechanism — winding
              ;; back on a pre-install throw is FREE because the install was
              ;; already deferred to one write.
              ;;
              ;; No t2 emit on the throw arm: the partial cascade `:db` was
              ;; discarded (no install, no db-changed). The t1 emit above
              ;; already fired with the handler-returned value; consumers
              ;; that pair t1 with an event abort see no t2.
              (-> (update ctx :effects dissoc :db)
                  (assoc :rf/flow-error e))))
          ;; No flows artefact loaded — short-circuit (steady state for
          ;; apps that never registered any flow). t1 above already fired
          ;; when the handler returned `:db`; t2 is by definition
          ;; impossible here (no flow could have transformed the value).
          ctx)))))

(defn- emit-flow-eval-exception!
  "Surface a flow-eval throw (stashed by `flows-after-interceptor` under
  `:rf/flow-error`) as `:rf.error/flow-eval-exception` through BOTH the
  dev-only trace surface AND the always-on error-emit substrate. Per
  Spec 013 §Failure semantics rule 3 the caller skips `:fx` after this
  fires; the drain continues with the NEXT event.

  `trace/emit-error!` is gated by `interop/debug-enabled?`
  and DCEs under `:advanced` + `goog.DEBUG=false` — so the always-on
  listener path is what survives prod elision and reaches off-box
  monitors. Mirrors the pipeline-exception path
  (`emit-pipeline-exception!`)."
  [e event event-id frame start-ms]
  (let [end-ms     (interop/now-ms)
        elapsed-ms (elapsed-ms-from start-ms end-ms)]
    ;; Fan out along BOTH channels (shared helper). Axis 1 — the
    ;; always-on corpus-wide listener fires in CLJS production where
    ;; the trace surface (axis 2) is compile-time elided.
    (error-emit/emit-error-both!
      :rf.error/flow-eval-exception
      event event-id frame e elapsed-ms end-ms
      {:frame frame :event event :exception e})))

(defn- emit-legacy-runtime-root!
  "Surface `:rf.error/legacy-runtime-root` through BOTH the always-on
  error-emit substrate AND the dev-only trace surface — the FINAL-effects
  boundary counterpart of the in-chain
  `events/reject-legacy-runtime-root!` throw.

  Why a SEPARATE in-band emit rather than re-throwing: the in-chain guard
  runs inside the handler-wrapping interceptor's `:before`, so a
  handler-RETURNED legacy root is caught by `execute-chain` and surfaced as
  `:rf.error/handler-exception`. But a legacy `:rf/runtime` root inserted
  into `[:effects :db]` by a user / framework `:after` interceptor lands
  AFTER that guard has run, in the FINAL effects map the router consumes.
  Detecting it here and THROWING would escape `process-event!` into
  `drain-emergency-release!` — which re-throws, abandoning the rest of the
  drained queue. So
  we emit in-band and abort THIS event only (`:error`
  outcome, NO commit, NO `:fx`), preserving the no-partial-commit promise
  while keeping the drain alive.

  Always-on: the corpus-wide listener observes the
  rejection in production where the trace surface DCEs."
  [event event-id frame start-ms]
  (let [end-ms     (interop/now-ms)
        elapsed-ms (elapsed-ms-from start-ms end-ms)
        tags       (events/legacy-runtime-root-ex-data event)]
    ;; Fan out along BOTH channels (shared helper). Axis 1 — the
    ;; always-on listener (survives prod elision); axis 2 — the dev trace (DCE'd
    ;; under `:advanced` + `goog.DEBUG=false`). No exception object: this is an
    ;; invalid-write rejection, not a host throw.
    (error-emit/emit-error-both!
      :rf.error/legacy-runtime-root
      event event-id frame nil elapsed-ms end-ms
      (assoc tags :frame frame))))

(defn- emit-classification-effect-shape!
  "Surface `:rf.error/classification-effect-shape` (EP-0025) through BOTH the
  always-on error-emit substrate AND the dev-only trace surface — the
  FINAL-effects boundary rejection of a malformed commit-plane classification
  effect (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`).

  Like `emit-legacy-runtime-root!`, this is an IN-BAND rejection, not a throw:
  the FINAL effects map may carry a malformed classification payload from a
  handler return OR an `:after` interceptor; throwing here would escape into
  `drain-emergency-release!` and abandon the rest of the queue. So we emit
  in-band and abort THIS event only (`:error` outcome, NO `:db`/registry
  commit, NO `:fx`), preserving the no-partial-commit promise while keeping the
  drain alive. Fail-loud (a surfaced `:rf.error/*`), fail-CLOSED on the commit
  (the classification is not installed and neither is the `:db` write).

  `defect` is the `re-frame.elision/classification-effect-defect` map
  (`{:offending-key … :value … :reason …}`)."
  [defect event event-id frame start-ms]
  (let [end-ms     (interop/now-ms)
        elapsed-ms (elapsed-ms-from start-ms end-ms)]
    (error-emit/emit-error-both!
      :rf.error/classification-effect-shape
      event event-id frame nil elapsed-ms end-ms
      {:frame             frame
       :rf.trace/event-id event-id
       :rf.event/v        event
       :offending-key     (:offending-key defect)
       :value             (:value defect)
       :recovery          :fix-effect
       :reason            (:reason defect)})))

(defn- run-fx-effects!
  "Walk :fx in source order, threading fx-overrides through so per-frame
  / per-call overrides take effect. Per-frame :platform overrides the
  host-wide platform marker (`interop/active-platform`, toggled via
  `re-frame.core/init-platform`) when set.

  Per Spec 002 §The binary fx-handler signature (line 603) and §Cascade
  propagation (line 1162): the originating dispatch envelope is
  threaded into `do-fx` so reserved-fx defmethods (`:dispatch`,
  `:dispatch-later`) can copy inheritable keys (`:fx-overrides`,
  `:interceptor-overrides`, `:trace-id`, `:origin`, `:source`) onto
  child dispatches. User fxs see it at `(:envelope m)`.

  Per rf2-twt7m Change 2: the full effects map is also threaded to
  `do-fx` (the `:effects` opt) so the terminating `:event/do-fx` trace
  marker can stamp `:fx` (the returned vector) and `:db-present?`
  (whether the handler returned a `:db` slot). The value of `:db`
  is NOT stamped — App-db diff traces already carry slice changes.

  Per rf2-9dk9y: the user-injected coeffects projection moved OFF the
  `:rf.fx/do-fx` marker and ONTO `:rf.event/run-end` (see
  `emit-cascade-trailers!`). The prior placement silently dropped the
  COEFFECTS row whenever a handler returned only `:db` (no `:fx`) — the
  fx walk was short-circuited so the marker never emitted. Pinning the
  cofx stamp to the always-fires run-end emit makes the COEFFECTS
  section render uniformly across event flavours.

  Per rf2-ee38b.1: the former positional do-fx arity ladder collapsed
  into a single `opts` map — this is the sole caller threading the full
  set of optionals."
  [effects frame frame-record fx-overrides envelope]
  (when-let [fx-vec (:fx effects)]
    (let [active-platform (fx/platform-for-frame-record frame-record)
          event           (:event envelope)
          ;; Production prod-strip (rf2-snsup5): in a production build the
          ;; dev-path per-call reject in `handle-one-fx` DCEs along with the
          ;; trace surface, so strip the reject-tier reserved-fx overrides
          ;; from the EFFECTIVE merged map (per-frame ⋈ per-call) up front,
          ;; LOUDLY (one always-on `:rf.error/reserved-fx-override` per
          ;; stripped key). This also keeps the rejected keys off the
          ;; `:fx-overrides` that `child-dispatch-opts` would otherwise
          ;; inherit onto cascade children. Dev keeps the per-call emit (it
          ;; carries richer per-fx context); the strip is the prod analogue.
          ;; `strip-rejected-overrides` is identity (no churn) when no
          ;; reject-tier key is present — the dominant path.
          fx-overrides    (if interop/debug-enabled?
                            fx-overrides
                            (fx/strip-rejected-overrides fx-overrides frame event))]
      (fx/do-fx frame fx-vec active-platform
                {:overrides       fx-overrides
                 :origin-event    event
                 :parent-envelope envelope
                 :effects         effects}))))

;; ---- process-event* phases ------------------------------------------------
;;
;; `process-event*` decomposes into named phases per audit RT1 (rf2-mccjv).
;; Each phase owns one piece of the per-event cascade; the outer
;; `process-event*` is a thin driver that sequences them.
;;
;;   handle-frame-destroyed!     early-exit: emit :rf.error/frame-destroyed
;;                               when the frame record is gone (frame disposed
;;                               between enqueue and dispatch)
;;   diag/handle-no-handler!     early-exit: emit :rf.error/no-such-handler
;;                               (the EP-0002-retired fallthrough warning
;;                               no longer fires here). Lives in
;;                               re-frame.router.diagnostics per rf2-0ytl4
;;                               seam R-B.
;;   prepare-handler-ctx         build the full interceptor chain (incl. the
;;                               outermost flows-after-interceptor) + initial
;;                               context and the effective fx-overrides map;
;;                               returns a tight map consumed by run-chain
;;                               and commit-and-flow!
;;   run-chain                   execute the interceptor chain bracketed in
;;                               performance marks; skipped when event-payload
;;                               validation fails (per Spec 010 §Per-step
;;                               recovery step 1). Flows run HERE, inside the
;;                               chain, as the outermost :after (rf2-u0zz5).
;;   commit-and-flow!            handler-exception emit (if any), flow-eval
;;                               error emit (if any), :db commit (of the
;;                               flow-augmented db), then walk :fx in source
;;                               order; returns the dispatch outcome keyword
;;                               (:ok / :error / :rolled-back / :flow-error)
;;                               for the event-emit record
;;   emit-cascade-trailers!      :run-end trace + always-on event-emit fan-out
;;   run-handler-cascade!        sequence prepare → run → commit → trailers
;;                               under `trace/with-handler-scope`

(defn- emit-frame-destroyed!
  "Surface `:rf.error/frame-destroyed` through BOTH the always-on
  error-emit listener (surface #4 — survives `goog.DEBUG=false`) AND the
  dev-only trace surface. Per the rf2-2hvga ruling (= B + recover-but-
  emit): a dispatch / subscribe to a destroyed or unknown frame RECOVERS
  (the caller no-ops / returns nil) but the diagnostic must reach
  production observability — the runtime cannot distinguish a benign
  teardown / hot-reload race from a real use-after-destroy bug, so it
  recovers (race-safe) AND emits on the production-watched stream (bug
  stays observable).

  `:frame`-stampable: the record carries the target `frame-id` and the
  attempted `event` so the 7d30s `:frame`-stamp audit + off-box
  shippers can attribute the failure. The reactive / drain paths have
  no triggering event-id beyond the event vector's head, which the
  record's elided `:event` carries.

  Reached via the `:error-emit/dispatch-on-error` late-bind hook — the
  drain helper is on the same facade as the dispatch entry points and
  router already static-requires `error-emit`, but routing all the
  non-recovery sites through one helper keeps the gating uniform."
  [event-id event frame-id]
  ;; Fan out along BOTH channels (rf2-c4oycd shared helper). Axis 1 — the
  ;; always-on listener (survives prod elision); axis 2 — the dev trace (DCE'd
  ;; under `:advanced` + `goog.DEBUG=false`). No exception — invalid op, not a
  ;; throw; `elapsed-ms 0` (not a timed path).
  (error-emit/emit-error-both!
    :rf.error/frame-destroyed
    event event-id frame-id nil 0 (interop/now-ms)
    {:frame frame-id :event event :reason :frame-destroyed}))

(defn- handle-frame-destroyed!
  "Per Spec 002 §Run-to-completion: a frame disposed between enqueue and
  dispatch surfaces as `:rf.error/frame-destroyed`; the drain continues
  with the next envelope. Per rf2-2hvga the emit is production-survivable
  (see [[emit-frame-destroyed!]])."
  [event frame]
  (emit-frame-destroyed! (first event) event frame))

;; EP-0015 §8 (rf2-d2r3um): the former per-dispatch
;; `refresh-elision-from-schemas!` is removed — schemas no longer feed the
;; app-db egress registry. Durable app-db classification is frame-owned and
;; installed once at `reg-frame` time (`re-frame.frame-classification`), so
;; there is nothing to refresh per dispatch.

(defn- prepare-handler-ctx
  "Build the effective interceptor chain and initial context for a
  resolved handler. Merges per-frame + per-call overrides (Spec 002
  §Per-frame and per-call overrides) and threads them through the
  initial cofx map. Returns `{:full-chain :initial-ctx :fx-overrides}`.

  Chain assembly order, per Spec 002:
  1. Prepend per-frame `:interceptors` to the handler's own chain
     (additive — §`:interceptors` — *add* interceptors).
  2. Walk the assembled chain and apply `:interceptor-overrides`
     (replace / remove by EXACT canonical reference per
     §`:interceptor-overrides` — a bare keyword matches by ref/`:id`, an
     `[id arg]` matches only the exact authored reference); `nil`-valued
     overrides remove the matched interceptor from the chain.

  HOT PATH: fires on every dispatch. On the override-free path (no
  per-frame / per-call `:fx-overrides`, no per-frame / per-call
  `:interceptor-overrides`, no per-frame `:interceptors`),
  `apply-overrides` returns shared empty sentinels and we reuse the
  handler's own `:interceptors` vector directly without `concat`-ing
  an empty extra-interceptors prefix or walking the chain for
  interceptor-overrides."
  [envelope frame frame-record handler-meta]
  (let [{:keys [extra-interceptors fx-overrides icpt-overrides]}
        (apply-overrides envelope frame-record)
        prepended-chain (if (seq extra-interceptors)
                          (vec (concat extra-interceptors (:interceptors handler-meta)))
                          (:interceptors handler-meta))
        ;; Per Spec 002 §Validation and resolution timing + §Effective chain
        ;; ordering (EP-0022 reference-only flip, rf2-0adhqs.9): resolve
        ;; interceptor REFERENCES (frame `:interceptors` refs ++ event
        ;; `:interceptors` refs) to their registered executable values at chain
        ;; assembly. REFERENCE-ONLY — a stale inline interceptor value in the
        ;; chain fails LOUD (`:rf.error/inline-interceptor-removed`); only the
        ;; framework's appended handler-wrapper (`:rf/default? true`) passes
        ;; through. Hot-path skip: when the chain is nothing but that framework
        ;; default (the common no-authored-chain shape) the walk is bypassed.
        ;; Refs resolve through the active registrar.
        resolved-chain  (if (icpt-reg/chain-needs-resolution? prepended-chain)
                          (icpt-reg/resolve-chain prepended-chain)
                          prepended-chain)
        base-chain      (apply-icpt-overrides resolved-chain icpt-overrides)
        ;; rf2-ivr38u — fused single-pass collection of the frame-declared
        ;; sensitive-path overlap (`:schema-paths`) AND the user-installed
        ;; `(rf/redact-interceptor paths)` paths (`:user-paths`) over the
        ;; SAME `base-chain`, replacing the prior two independent chain
        ;; walks. Per rf2-461sp — user-installed redact interceptors expose
        ;; their paths on the interceptor map so the pre-chain trace
        ;; projection (`:run-start`, `emit-cascade-trailers`) honours them
        ;; too. Each user `:before` ALSO runs during chain execution and
        ;; extends `:rf/redacted-event` in-chain, which is what the schema-
        ;; redaction interceptor (when also installed) composes with. The
        ;; union here is the OUT-OF-CHAIN projection used by emit sites that
        ;; fire BEFORE the chain.
        {redaction-paths :schema-paths
         user-paths      :user-paths} (privacy/collect-redaction-paths frame base-chain)
        redacted-chain  (if (seq redaction-paths)
                          (into [(privacy/schema-redaction-interceptor
                                   redaction-paths)]
                                base-chain)
                          base-chain)
        ;; Per Spec 013 §Drain integration (rf2-u0zz5): PREPEND the
        ;; framework's flow-transform interceptor at the HEAD of the
        ;; dispatch-time chain so its `:after` is the OUTERMOST `:after`
        ;; — it fires after the rest of the `:after` chain (handler body
        ;; + every user / framework `:after`) has fully reshaped the
        ;; pending `:db` effect into the complete app-db form. This is
        ;; load-bearing: a `[:rf.interceptor/path [:slice]]` interceptor's
        ;; `:after` splices the handler's slice back into the FULL db, so the flow
        ;; transform (which reads full-db `:inputs` paths) MUST run after
        ;; that splice — i.e. outermost. The rest of the `:after` chain
        ;; therefore precedes flows and sees its INPUT; the flow output
        ;; reaches `:fx`, the reactive cascade, and the single `:db`
        ;; install (all of which run after the chain). Added here
        ;; (dispatch-time) rather than baked into the registered handler-
        ;; meta chain so tooling that reads `(handler-meta :event id)
        ;; :interceptors` still sees the user-authored chain with the
        ;; handler-wrapper at its tail.
        full-chain      (into [flows-after-interceptor] redacted-chain)
        initial-ctx     (assemble-initial-ctx envelope frame frame-record handler-meta fx-overrides)
        all-paths       (into (vec redaction-paths) user-paths)]
    {:full-chain   full-chain
     :initial-ctx  initial-ctx
     :fx-overrides fx-overrides
     ;; rf2-9vx0jk — dev-only per-dispatch interceptor-override summary
     ;; (id-only / counts) for the `:rf.event/run-start` trace tag
     ;; `:rf.interceptor/override-summary`. `nil` on the hot no-override path
     ;; (`icpt-overrides` is the shared empty sentinel) — the tag is then
     ;; omitted entirely. Computed from the PRE-override `resolved-chain` (which
     ;; still carries the authored refs the overrides matched against) + the
     ;; merged per-frame + per-call `icpt-overrides` map — `base-chain` is the
     ;; POST-override chain (matched entries already removed/replaced), so the
     ;; matcher must walk `resolved-chain`. Pure + feeds only the dev-only
     ;; run-start emit, so it DCEs in `:advanced` production.
     :override-summary (override-summary resolved-chain icpt-overrides)
     :emit-event   (if (seq all-paths)
                     (privacy/redact-event (:event envelope) all-paths)
                     (:event envelope))
     ;; `:schema-sensitive?` (a RETAINED key name, like the
     ;; `schema-redaction-paths` fn it derives from — see
     ;; `re-frame.privacy`) strictly tracks the CLASSIFIED sensitive
     ;; path overlap (EP-0025 — the per-frame sensitive-declarations
     ;; registry written by the commit-plane classification effects, no
     ;; longer schema-attached slot props nor a frame annotation). It
     ;; drives the scope-meta `:sensitive?` stamp on every emitted trace
     ;; event. User `redact-interceptor` does NOT stamp `:sensitive?`;
     ;; sensitivity is path-marked via the frame's app-db classification
     ;; (the handler-meta annotation has been removed).
     :schema-sensitive? (boolean (seq redaction-paths))}))

(defn- run-chain
  "Execute the interceptor chain bracketed in performance marks. When
  event-payload validation failed (`event-ok?` false) the handler is
  suppressed via `:rf/skip-handler?` on the initial context — the same
  mechanism the cofx-failure path (Spec 010 step 2) uses — rather than
  skipping the chain wholesale. Per Spec 010 §Per-step recovery step 1
  the handler does not run and the downstream queue continues; per Spec
  002 §Interceptor chain execution rule 2 the chain STILL executes so
  the `:after` pass always runs in full and cleanup-on-`:after`
  interceptors (debug pp/snapshot, Story snapshot capturer) fire even
  on a pre-handler validation failure. Symmetric with the cofx path:
  both failures keep teardown intact.

  Per Spec 009 §Performance instrumentation (rf2-du3i): the
  `performance/mark-and-measure` bracket produces a
  `rf:event:<event-id>` measure entry under prod builds with the perf
  flag enabled. Default-off; under `:advanced` +
  `re-frame.performance/enabled?=false` the bracket DCEs and the call
  collapses to a plain `execute-chain` invocation."
  [event-id full-chain initial-ctx event-ok?]
  (let [ctx (if event-ok?
              initial-ctx
              (assoc initial-ctx :rf/skip-handler? true))]
    (performance/mark-and-measure :event event-id
      (interceptor/execute-chain full-chain ctx))))

(defn- commit-and-flow!
  "Settle the cascade: surface any chain / flow exception, commit the
  (flow-augmented) :db, then walk :fx in source order. Per Spec 002
  §Drain-loop pseudocode. Flows have already run as the outermost
  `:after` inside the chain (rf2-u0zz5), so by the time this fn executes
  the pending `:db` effect is the flow-augmented value; the install here
  is the single deferred commit, and `:fx` walks after it.

  Per Spec 010 §Per-step recovery row 4 (rf2-wkxng / rf2-6m0se): a
  post-commit `:db` schema-validation failure rolls the container
  back to the pre-handler value AND treats the dispatch as failed —
  flows do NOT evaluate and `:fx` does NOT walk. The pre-handler db is
  read from the cascade's pre-handler frame-state snapshot
  (`frame/*cascade-frame-state-before*`'s app-db partition) — NOT from
  `[:coeffects :db]`, which an `:rf.interceptor/path` handler focuses to
  a slice (rf2-wfy2kq); see the `db-before` binding below. Downstream
  queued events still drain per run-to-completion (handled by
  `drain-loop!`'s outer pass).

  Per Spec 002 §Cascade propagation: `envelope` is threaded into
  `run-fx-effects!` so reserved-fx defmethods can propagate
  inheritable keys onto child dispatches.

  Per Spec 013 §Drain integration (rf2-u0zz5): flows have ALREADY run
  by the time this fn executes — the framework's OUTERMOST `:after`
  interceptor (`flows-after-interceptor`) transformed the pending `:db`
  effect inside the chain. So `(:db effects)` here is the FLOW-AUGMENTED
  value, and a flow throw is signalled by `(:rf/flow-error final-ctx)`
  rather than an inline `run-flows!` call.

  Atomicity contract (Spec 013 §Failure semantics, Mike 2026-05-24): the
  `:db` install is the single, deferred, all-or-nothing commit boundary.
  ANY pre-install throw — handler, interceptor `:after`, or the flow
  transform — aborts the event: NO install, app-db UNCHANGED, NO
  `:rf.event/db-changed`, NO `:fx`. This is uniform and FREE: the
  handler / interceptor-error path returns `:error` WITHOUT calling
  `commit-db-effect!`, and the flow-throw path's `:after` already
  `dissoc`-ed the pending `:db` effect — so even if the commit ran it
  would install nothing (`commit-db-effect!` is a no-op when no `:db`
  effect is present). `:fx` is the ONLY post-install stage; an fx throw
  does NOT wind back app-db (its side effects may already have fired).

  Returns the dispatch OUTCOME keyword for the always-on event-emit
  record (Spec 009 §Event-emit listener §Record shape):

    :ok          — clean settle (db committed, flows ran, :fx walked).
    :error       — the interceptor chain threw (event handler, a user
                   interceptor `:before`/`:after`, or a coeffect
                   injection); `emit-pipeline-exception!` has already
                   fired the component-attributed error trace
                   (`:rf.error/handler-exception` / `interceptor-exception`
                   / `coeffect-exception` per rf2-mszrz). No install,
                   app-db unchanged, :fx skipped.
    :rolled-back — post-commit `:db` schema validation rejected the
                   new state and the container was restored to its
                   pre-handler value (Spec 010 row 4); :fx was skipped.
    :flow-error  — a flow's `:derive` threw (Spec 013 §Failure
                   semantics); the event aborted — no install, app-db
                   unchanged, no db-changed, :fx skipped.

  All three non-`:ok` values surface to off-box observability shippers
  (Datadog / Sentry / Honeycomb) so a dispatch that rolled back its
  whole `:db` write or aborted on a flow throw is NOT mis-reported as
  a clean `:ok`. A chain exception is reported as `:error` regardless
  of any downstream rollback — it is the proximate, most-actionable
  signal."
  [final-ctx event-id event frame frame-record fx-overrides envelope start-ms]
  (let [error          (:rf/interceptor-error final-ctx)
        flow-error     (:rf/flow-error final-ctx)
        ;; FINAL-effects boundary policing (rf2-u1kdvg). `commit-fx-effects`
        ;; polices a `reg-event` HANDLER RETURN during the chain's
        ;; `:before` pass — BEFORE the `:after` interceptors run. By the
        ;; time the router consumes `(:effects final-ctx)` the whole chain
        ;; (every `:before` AND every `:after`) has run, so an effect can
        ;; arrive here malformed by a route the per-handler-return checks
        ;; never saw: an `:after`-interceptor mutation. `police-final-effects!` is the
        ;; single authoritative shape gate applied to the FINAL map — it
        ;; drops foreign top-level keys (so a foreign key is no longer
        ;; SILENTLY ignored at the partition commit) and drops a
        ;; non-sequential `:fx` (so it never reaches `fx/do-fx` to throw a
        ;; raw host exception AFTER the db commit), emitting
        ;; `:rf.error/effect-map-shape` (`:logged-and-skipped`) for each.
        ;; Runs BEFORE any commit, preserving the no-partial-commit promise.
        effects        (events/police-final-effects! (:effects final-ctx) event)
        ;; Pre-handler app-db partition for the post-commit schema-rollback
        ;; target (rf2-wfy2kq). MUST NOT be read from `[:coeffects :db]`: the
        ;; `:rf.interceptor/path` std-interceptor's `:before` overwrites
        ;; `[:coeffects :db]` with the FOCUSED slice and its `:after` only
        ;; restores `[:effects :db]`, never the coeffect (std_interceptors
        ;; §standard-path-interceptor). So under an idiomatic
        ;; `[:rf.interceptor/path p]` handler, `(get-in final-ctx [:coeffects
        ;; :db])` is the path SLICE, not the full app-db — and rolling back to
        ;; it would install the slice as the WHOLE app-db, destroying every key
        ;; outside `p` (data corruption on the recovery path). Source instead
        ;; from the cascade's pre-handler frame-state snapshot
        ;; (`frame/*cascade-frame-state-before*`, bound by `run-one-pass!`
        ;; around the WHOLE cascade) — the canonical full pre-handler frame-
        ;; state, whose app-db projection is `=` the un-focused coeffect by
        ;; construction (both read the live container before the handler runs in
        ;; `assemble-initial-ctx`) yet is immune to mid-chain path focusing.
        ;; Fall back to the coeffect only when the var is unbound (no real
        ;; cascade in flight — REPL / direct call; the rollback path cannot fire
        ;; there since nothing commits out-of-cascade).
        db-before      (if-let [fs-before frame/*cascade-frame-state-before*]
                         (get fs-before frame/app-partition-key)
                         (get-in final-ctx [:coeffects :db]))
        ;; Pre-handler runtime-db partition (EP-0001 rf2-adwcv6): the
        ;; `:rf.db/runtime` coeffect `assemble-initial-ctx` injected by
        ;; reference. Needed by `commit-frame-effects!` so an app-db schema
        ;; rollback unwinds the WHOLE transition (both partitions) coherently.
        runtime-before (get-in final-ctx [:coeffects :rf.db/runtime])
        ;; EP-0025: the FINAL-effects boundary check for a malformed commit-plane
        ;; classification effect (`:sensitive` / `:large` / `:clear-sensitive` /
        ;; `:clear-large`). Returns the first defect map (or nil) — a non-vector
        ;; payload or a non-`:rf/path` entry. Checked HERE (not only on the
        ;; handler return) so an `:after`-interceptor-injected malformed payload
        ;; is also rejected; the rejection is in-band (no throw) so it aborts THIS
        ;; event pre-commit without escaping the drain. nil (the common case) is a
        ;; cheap walk over at-most-four absent keys.
        class-defect   (elision/classification-effect-defect effects)]
    (when error
      (emit-pipeline-exception! error event-id event frame final-ctx start-ms))
    (cond
      error :error
      ;; Per Spec 013 §Failure semantics (atomicity contract, Mike
      ;; 2026-05-24): a flow's `:derive` threw during the outermost
      ;; `:after` flow transform. A flow throw is a PRE-INSTALL throw, so
      ;; the event ABORTS — no install, app-db unchanged, no
      ;; `:rf.event/db-changed`, no `:fx`. The `:after` already `dissoc`-ed
      ;; the pending `:db` effect, so we do NOT call `commit-db-effect!`
      ;; here at all: the only post-install stage (`:fx`) is skipped and
      ;; nothing is committed. Surface the cascade-level
      ;; `:rf.error/flow-eval-exception`; the trace stream is therefore
      ;; `flow/failed → flow-eval-exception` with NO `db-changed`.
      flow-error
      (do
        (emit-flow-eval-exception! flow-error event event-id frame start-ms)
        :flow-error)
      ;; FINAL-effects boundary legacy-root rejection (rf2-u1kdvg, EP-0001
      ;; decision #8). `events/reject-legacy-runtime-root!` ran in-chain
      ;; (during the handler-wrapper's `:before`) so a handler-RETURNED
      ;; `:rf/runtime` root surfaces as `:rf.error/handler-exception`. But an
      ;; `:after` interceptor can insert the retired `:rf/runtime` root into
      ;; the FINAL `[:effects :db]` AFTER that guard ran — bypassing it and
      ;; landing legacy-shaped data in app-db. Enforce the rejection on the
      ;; FINAL db effect immediately BEFORE commit. In-band (NO throw) so the
      ;; rejection aborts THIS event only without escaping to the drain's
      ;; emergency release: no commit, no `:fx`, the rest of the queue keeps
      ;; draining. Mirrors the no-partial-commit promise of the in-chain guard.
      (events/legacy-runtime-root? (:db effects))
      (do
        (emit-legacy-runtime-root! event event-id frame start-ms)
        :error)
      ;; EP-0025 FINAL-effects boundary: a malformed commit-plane classification
      ;; effect (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`)
      ;; whose payload is not a vector-of-paths. FAIL-LOUD pre-commit, in-band
      ;; (like the legacy-root rejection above): emit
      ;; `:rf.error/classification-effect-shape` and abort THIS event with NO
      ;; `:db` / registry commit and NO `:fx` — the classification is not
      ;; installed and the `:db` write does not land (no partial commit). Checked
      ;; BEFORE `commit-frame-effects!`, which would otherwise fold the malformed
      ;; declaration into the runtime-db partition.
      class-defect
      (do
        (emit-classification-effect-shape! class-defect event event-id frame start-ms)
        :error)
      ;; Per Spec 010 §Per-step recovery row 4: `commit-frame-effects!`
      ;; returns false when post-commit app-db schema validation rejected
      ;; the new state and rolled the WHOLE frame-state transition back to
      ;; its pre-handler value. `:fx` is skipped; the dispatch failed.
      (not (commit-frame-effects! effects event-id event frame final-ctx
                                  db-before runtime-before))
      :rolled-back
      :else
      (do
        (run-fx-effects! effects frame frame-record fx-overrides envelope)
        :ok))))

(defn- emit-cascade-trailers!
  "Cascade-tail emissions: the dev-only `:run-end` trace then the
  always-on event-emit fan-out.

  Per rf2-rirbq: the event-emit substrate is ALWAYS-ON — it survives
  `:advanced` + `goog.DEBUG=false` while the trace surface above DCEs.
  Looked up through the late-bind hook table so the router carries no
  static dependency on `re-frame.event-emit`; when the event-emit
  namespace has not been loaded the hook is nil and the fan-out is a
  single nil-check. Per Spec 009 §Event-emit listener.

  Per rf2-rirbq §Record shape: `:elapsed-ms` is an integer.
  `interop/now-ms` returns a long on the JVM (`System/currentTimeMillis`)
  but a float on CLJS (`js/performance.now()` carries sub-millisecond
  precision). Round once at the substrate boundary so the record's
  contract holds on both platforms.

  `outcome` is the keyword `commit-and-flow!` returns — `:ok`,
  `:error`, `:rolled-back`, or `:flow-error` — and rides straight onto
  the event-emit record's `:outcome` slot (Spec 009 §Record shape).

  `handler-elapsed-ms` (rf2-hhh92) is the HANDLER-BODY-only wall-clock
  (the interceptor-chain duration, captured before `commit-and-flow!`),
  surfaced onto the dev-only `:rf.event/run-end` trace as
  `:rf.event/elapsed-ms` so the Trace panel's DURATION column reads the
  per-op handler duration — NOT the whole run-end − run-start cascade
  bracket (which also covers fx+subs+views). nil in production (the
  caller's read rides `interop/debug-enabled?`); the `(some? ...)` slot
  then collapses.

  Per rf2-9dk9y two further `:tags` slots ride this emit so the Xray
  Event lens's COEFFECTS / AFTER INTERCEPTORS sections render uniformly
  regardless of whether the handler returned `:fx`:

    `:rf.event/coeffects`    — the USER-INJECTED subset of the
                                handler's final coeffects map (framework
                                defaults `:db` `:event` `:rf.frame/id`
                                `:source` `:trace-id` `:rf.db/runtime`
                                filtered out at this boundary). Absent
                                entirely when zero user cofx were
                                injected.
    `:rf.event/after-deltas` — vector of per-`:after` interceptor
                                ctx-delta records `{:rf.icpt/id <id>
                                :rf.icpt/ctx-delta {...}}` populated by
                                `interceptor/execute-chain` for every
                                user-registered `:after` that mutated
                                the context. Absent when no user-`:after`
                                ran. The Xray AFTER INTERCEPTORS section
                                reads it to render an EDN-diff under
                                each row."
  [event-id event emit-event frame outcome start-ms handler-elapsed-ms final-ctx]
  ;; The cofx / after-delta projections are dev-only: their cost rides
  ;; `interop/debug-enabled?` so production CLJS bundles DCE the
  ;; projection AND the `trace/emit!` body below (the emit itself is
  ;; gated the same way internally).
  (let [user-cofx    (when interop/debug-enabled?
                       (fx/user-injected-coeffects (:coeffects final-ctx)))
        after-deltas (when interop/debug-enabled?
                       (not-empty (:rf/icpt-after-deltas final-ctx)))]
    (trace/emit! :rf.event :rf.event/run-end
                 (cond-> {:rf.trace/event-id event-id
                          :rf.event/v        emit-event
                          :frame             frame
                          :rf.trace/phase    :run-end}
                   (some? handler-elapsed-ms)
                   (assoc :rf.event/elapsed-ms handler-elapsed-ms)
                   (some? user-cofx)
                   (assoc :rf.event/coeffects user-cofx)
                   (some? after-deltas)
                   (assoc :rf.event/after-deltas after-deltas)))
    ;; Sticky hook (rf2-f72pd) — always-on per-event observability fan-out
    ;; per rf2-rirbq; survives `:advanced` + `goog.DEBUG=false`.
    (let [emit-event!     (late-bind/get-fn-cached :event-emit/dispatch-on-event)
          ;; EP-0015 §9 (rf2-t55hxg.7): the frame-owned observability sink
          ;; route — the NORMAL production observation stream (Spec 015
          ;; §The three observation streams, stream 3). Parallel to the
          ;; corpus-wide event-emit listener fan-out above: this routes the
          ;; handled-event record to THIS frame's declared `:observability
          ;; :handled-events` sinks, projected under the frame's
          ;; classification + the sink's egress profile. Also always-on
          ;; (survives `:advanced` + `goog.DEBUG=false`); late-bound so the
          ;; router carries no static dependency on `re-frame.observability`.
          route-handled! (late-bind/get-fn-cached :observability/route-handled-event)]
      (when (or emit-event! route-handled!)
        (let [end-ms     (interop/now-ms)
              elapsed-ms (elapsed-ms-from start-ms end-ms)]
          (when emit-event!
            (emit-event! emit-event
                         event-id
                         frame
                         end-ms
                         outcome
                         elapsed-ms))
          (when route-handled!
            ;; The effect keys the cascade produced (`final-ctx`'s
            ;; `:effects` map keys) ride the handled-event record's
            ;; `:effects` summary slot; the dispatch-id (dev-only — nil
            ;; under `goog.DEBUG=false`) rides `:correlation` when present.
            (let [effects     (some-> (:effects final-ctx) keys vec)
                  dispatch-id (some-> trace/*handler-scope* :dispatch-id)
                  correlation (when dispatch-id {:dispatch-id dispatch-id})]
              (route-handled! event
                              event-id
                              frame
                              outcome
                              elapsed-ms
                              effects
                              correlation))))))))

(defn- run-handler-cascade!
  "Sequence the four cascade phases under the handler's
  `trace/*handler-scope*` binding.

  Per rf2-ryri7: publish the event handler's HandlerScope —
  `:trigger-handler` (rf2-3nn8 error path / rf2-lf84g success path) so
  every trace emitted inside the cascade carries the triggering
  handler's source-coord; `:sensitive?` (rf2-isdwf) so emits inside the
  scope get a top-level `:sensitive? true` stamp per Spec 009 §Privacy;
  `:no-emit?` (rf2-qsjda) so trace emission short-circuits when the
  handler opts out. `:call-site` and `:dispatch-id` are inherited from
  the parent scope (bound by `process-event!` outer wrapper) per
  `inherit-scope`. Scope covers the interceptor chain, db commit, flows,
  and fx walk — covering :event/db-changed, :event/do-fx, :rf.fx/handled
  (the inner fx scope re-binds), :sub/run (sub recompute re-binds),
  :rf.error/* (every error emit inside the chain).

  Per rf2-rirbq: `start-ms` is captured at the very start of cascade
  execution (unconditional, single `now-ms` call per event) so the
  always-on event-emit substrate can report `:elapsed-ms` in its per-
  event record."
  [envelope event-id event frame frame-record handler-meta]
  (let [{:keys [full-chain initial-ctx fx-overrides emit-event
                schema-sensitive? override-summary]}
        (prepare-handler-ctx envelope frame frame-record handler-meta)
        ;; Per rf2-j20a7 / Spec 005 §Level 4: tag the in-flight envelope
        ;; as machine-originated when THIS handler is a machine (its
        ;; registration meta carries `:rf/machine? true`, stamped by
        ;; re-frame.machines `reg-machine*`). The tagged envelope is the
        ;; `parent-envelope` threaded into `do-fx`; `child-dispatch-opts`
        ;; (re-frame.fx) copies the flag onto every `:dispatch` /
        ;; `:dispatch-later` child emitted during this handler's fx walk,
        ;; so those continuation events front-of-queue insert (see
        ;; `enqueue-envelope!`). The cut is the dispatch's ORIGIN — an
        ;; event that merely TARGETS a machine but originates elsewhere
        ;; carries no flag and stays FIFO. `:raise` is untouched: it
        ;; never reaches the router queue (it drains in-memory inside the
        ;; machine handler invocation, pre-commit).
        envelope   (cond-> envelope
                     (:rf/machine? handler-meta)
                     (assoc :rf.machine/internal? true))
        ;; The schema-derived `:rf/sensitive?` key drives the scope's
        ;; `:sensitive?` trace-event stamp (read by `handler-scope-from-
        ;; meta`). Path-marked via app-schema slot meta; the handler-
        ;; meta `:sensitive?` annotation has been removed.
        scope-meta (cond-> handler-meta
                     schema-sensitive? (assoc :rf/sensitive? true))]
    (trace/with-handler-scope
      (trace/handler-scope-from-meta :event event-id scope-meta)
      (let [start-ms  (interop/now-ms)
            ;; rf2-1xdotm — the POST-GENERATION flat `:rf.cofx` replay token:
            ;; the causal cofx map AS IT WAS after `assemble-initial-ctx`'s
            ;; declared-only delivery ran (every generator-backed recordable
            ;; fact minted at processing-start written back into the in-flight
            ;; `:rf.cofx` — re-frame.cofx/deliver-declared-cofx). It carries
            ;; the framework `:rf/time-ms` provided fact AND every generated
            ;; fact, so the epoch record (via `find-trigger-event`) can pin it
            ;; as a first-class `:rf.cofx` slot and a Tool-Pair replay can
            ;; re-present the EXACT facts the original run consumed under
            ;; `:rf.cofx/mint-policy :strict` (EP-0017 §Recordable coeffects +
            ;; Tool-Pair §Replay-mint-policy). Read off `initial-ctx`'s always-
            ;; staged `:rf.cofx` coeffect. Dev-only — the whole run-start
            ;; `trace/emit!` body DCEs under `:advanced` + `goog.DEBUG=false`,
            ;; and the slot is reachable only through `find-trigger-event` at
            ;; epoch-assembly time (itself dev-gated). The marks chokepoint
            ;; (`marks/project-cofx-token-tags`) redacts per-cofx-id declared
            ;; `:sensitive` / `:large` slots before any off-box egress.
            run-cofx  (when interop/debug-enabled?
                        (get-in initial-ctx [:coeffects :rf.cofx]))
            _         (trace/emit! :rf.event :rf.event/run-start
                                   ;; rf2-9vx0jk — `:rf.interceptor/override-
                                   ;; summary` rides the run-start TRACE tag
                                   ;; bag (dev-only — the whole `trace/emit!`
                                   ;; call elides under `:advanced`). The
                                   ;; `cond->` step keys on the RUNTIME
                                   ;; `override-summary` VALUE (nil on the hot
                                   ;; no-override path ⇒ tag omitted), NOT a
                                   ;; keyword-literal test (which Closure won't
                                   ;; fold — rf2-7ynhyn); the keyword literal
                                   ;; itself legitimately survives prod via the
                                   ;; always-reachable marks chokepoint (Spec
                                   ;; 009 §`:tags`; same as `:rf.event/db` /
                                   ;; `:rf.view/render-args`), but the id/count
                                   ;; VALUES are constructed only here and DCE.
                                   (cond-> {:rf.trace/event-id event-id
                                            :rf.event/v        emit-event
                                            :frame             frame
                                            :source            (:source envelope)
                                            :rf.trace/trace-id (:trace-id envelope)
                                            :rf.trace/phase    :run-start}
                                     override-summary
                                     (assoc :rf.interceptor/override-summary
                                            override-summary)
                                     ;; rf2-1xdotm — the post-generation flat
                                     ;; `:rf.cofx` replay token. Threaded only
                                     ;; when present (dev builds; a cascade
                                     ;; whose envelope carried no cofx map
                                     ;; omits the slot).
                                     (some? run-cofx)
                                     (assoc :rf.event/cofx run-cofx)))
            event-ok? (validate-event! event-id event handler-meta frame)
            final-ctx (run-chain event-id full-chain initial-ctx event-ok?)
            ;; rf2-hhh92: the HANDLER-BODY-only elapsed — the interceptor
            ;; chain (`run-chain`) duration, captured BEFORE
            ;; `commit-and-flow!` (db commit + flows + fx walk). This is
            ;; distinct from the `:rf.event/run-end` whole-cascade bracket
            ;; (run-end − run-start, which also covers fx+subs+views). The
            ;; Trace panel's DURATION column reads THIS handler-body figure
            ;; off the `:rf.event/run-end` tag. Dev-only: the read rides
            ;; `interop/debug-enabled?` so production DCEs it.
            handler-elapsed-ms (when interop/debug-enabled?
                                 (- (interop/now-ms) start-ms))
            ;; `commit-and-flow!` returns the dispatch outcome keyword
            ;; (:ok / :error / :rolled-back / :flow-error) so the always-on
            ;; event-emit record reflects schema-rollback and flow-throw
            ;; failures, not just the chain exception.
            outcome   (commit-and-flow! final-ctx event-id event frame
                                        frame-record fx-overrides envelope start-ms)]
        (emit-cascade-trailers! event-id event emit-event frame outcome
                                start-ms handler-elapsed-ms final-ctx)))))

(defn- process-event*
  "Per-event drain body. Resolve handler, then sequence the four cascade
  phases under the handler-scope binding (see `run-handler-cascade!`).
  Per Spec 002 §Drain-loop pseudocode.

  This is the inner of `process-event!`; the outer wraps it in a
  `trace/*handler-scope*` binding (via `trace/with-dispatch-id+call-site`)
  so (a) child dispatches issued from within fx handlers inherit the
  in-flight dispatch's id as their `:parent-dispatch-id`, and (b) every
  trace event emitted inside the cascade carries the cascade's
  `:dispatch-id` under `:tags` (per Spec 009 §Dispatch correlation and
  rf2-g6ih4).

  Two early-exit branches precede the cascade: a destroyed frame and a
  missing handler. Both emit their respective error events and return
  without disturbing the queue — the drain continues with the next
  envelope."
  [envelope]
  (let [{:keys [event frame]} envelope
        event-id              (first event)
        frame-record          (frame/frame frame)]
    (cond
      (nil? frame-record)
      (handle-frame-destroyed! event frame)

      :else
      (let [handler-meta (or (resolve-handler event-id)
                             ;; Per rf2-a2sn1 — the lazy actor-handler
                             ;; resolver seam. A dynamically-spawned
                             ;; machine actor carries NO per-instance
                             ;; registrar entry; its liveness is derived
                             ;; from its (revertible) app-db snapshot. On
                             ;; a no-registrar-handler miss, consult the
                             ;; machines-registered
                             ;; `:machines/resolve-actor-handler-meta`
                             ;; hook, which materialises the actor's
                             ;; handler-meta from its snapshot's
                             ;; `:rf/machine-type` — returning nil when no
                             ;; live snapshot exists (genuine
                             ;; `:no-such-handler`). Late-bound so core
                             ;; carries NO static dependency on the
                             ;; optional machines artefact; the hook is
                             ;; absent (nil) when machines isn't loaded,
                             ;; and resolution falls straight through to
                             ;; the error path below — same shape as the
                             ;; flows / schemas / epoch artefact seams.
                             (resolve-unhandled event frame))]
        (if (nil? handler-meta)
          (diag/handle-no-handler! event-id event frame)
          (run-handler-cascade! envelope event-id event frame
                                frame-record handler-meta))))))

(defn- process-event!
  "Wrap process-event* in three dynamic bindings:

   1. `trace/*handler-scope*` — set with the cascade's `:dispatch-id`
      and the envelope's `:call-site`, inheriting the rest from parent.
      Per rf2-ryri7 (consolidation of the `:dispatch-id` slot per
      rf2-g6ih4 and the `:call-site` slot per rf2-ts1a) — child
      dispatches issued from within an fx handler inherit this event's
      `:dispatch-id` as their `:parent-dispatch-id`, every trace event
      emitted inside the cascade (sub runs, fx-handled, machine
      transitions, errors) rides the cascade's `:dispatch-id` under
      `:tags`, and any error emitted inside the chain attaches the
      call-site to the event as `:rf.trace/call-site` (nil for fn-form
      dispatch). Per Spec 009 §Dispatch correlation.

   2. `frame/*current-frame*` — bound to the envelope's `:frame` for
      the duration of the handler chain. Per Spec 002 §Dispatch
      resolution chain — the dynamic-var tier of the resolution chain
      MUST cover the in-flight handler body so a synchronous
      `(rf/dispatch ...)` / `(rf/subscribe ...)` from inside the
      handler routes to the handler's own frame (not `:rf/default`).
      Without this binding, the handler body would see the same
      `*current-frame*` value the original dispatcher saw — typically
      `nil` for app-level dispatches — and child dispatches would
      slide to `:rf/default`, silently breaking multi-frame isolation.

      The binding does NOT survive async escapes (setTimeout,
      Promise.then, requestAnimationFrame): the JS callback fires on
      a fresh stack with no dynamic binding. Use `(rf/frame-handle)`
      (capture-at-creation), `:fx [[:dispatch ...]]` (fx-walker
      threads the frame), or `:dispatch-later` (frame captured in
      closure) for those paths. Per rf2-l5q3.

   3. `registrar/*generation*` — bound to the target frame's resolved
      IMAGE GENERATION for the WHOLE cascade WHEN the carried `:frame`
      names an EP-0023 image-loaded frame (rf2-uejnt3, operationalising
      the rf2-32siq3.9 seam). This is the EP-0023 restatement of the
      `target frame -> resolved image generation -> registration
      resolution` invariant in image/frame terms. The resolution
      chokepoint (`registrar/lookup`) — event-handler lookup, every cofx
      injection, the whole fx walk — resolves through the frame's OWN
      image generation's resolver, so two frames running DIFFERENT images
      resolve the same `[kind id]` to their own image's descriptor
      (ALL-OR-NOTHING — `call-with-frame-resolution` covers the whole
      thunk). The generation is DERIVED from the carried frame target
      (`frame-resolution-target` resolves a direct frame OBJECT verbatim,
      a frame-id keyword through the live-frame registry), never an
      ambient binding (EP-0002). A target that names no live image-loaded
      frame yields no generation, so `call-with-frame-resolution` binds
      NOTHING and resolution falls through to the registrar-atom path,
      byte-identical (absence-is-default). Child dispatches re-enter
      `process-event!` for their frame and re-derive the binding, so the
      generation is preserved across the cascade automatically."
  [envelope]
  (trace/with-dispatch-id+call-site (:dispatch-id envelope) (:call-site envelope)
    (binding [frame/*current-frame* (:frame envelope)]
      ;; EP-0023 (rf2-uejnt3): route the cascade through the target frame's
      ;; resolved IMAGE generation when the carried `:frame` names an
      ;; image-loaded frame, so event + cofx + fx resolve coherently through
      ;; the frame's own image (rf2-32siq3.9's seam, invoked at the live
      ;; entry). A target that names no image-loaded frame (a single-realm
      ;; default frame) derives no generation, so this binds nothing and the
      ;; cascade resolves through the registrar atom exactly as before
      ;; (absence-is-default). DERIVED from the carried target (EP-0002).
      (live-frame/call-with-frame-resolution
        (live-frame/frame-resolution-target (:frame envelope))
        (fn [] (process-event* envelope))))))

(def ^:private drain-depth-default
  ;; Deep enough for typical cascade depths. When exceeded, the runtime
  ;; halts the next (unstarted) event per Spec 002 §Run-to-completion rule
  ;; 3 — already-settled events stay durable; the halting event gets a
  ;; trailing `:halted-depth` epoch record (no whole-drain rollback under
  ;; the per-event epoch model).
  100)

(defn- handle-depth-exceeded!
  "Tail-path for the depth-limit branch of `drain!`. Per Spec 002
  §Drain versus event — the epoch unit: the epoch boundary is the
  dequeued EVENT, so the events that already ran in this drain each
  settled their own DURABLE `:ok` epoch (and their own db write) as they
  completed — there is no whole-drain rollback under per-event epochs.
  The depth limit stops processing the NEXT event (the halting event,
  still at the head of the queue); the work that already ran is a
  sequence of complete, individually-atomic events.

  Per Spec-Schemas §`:rf/epoch-record` §Outcomes: commit a `:halted-depth`
  epoch record so devtools (Xray, re-frame2-pair) get a clear 'drain
  halted here' marker following the runaway `:ok` epochs. The halting
  event never ran, so its record's `:frame-state-before` /
  `:frame-state-after` both equal the current (last-settled) frame-state
  value and its buffer is empty — `commit-halt-record!` synthesises the
  record from the halting event's trigger. Listeners receive it like any
  other; `restore-epoch!` refuses non-`:ok` targets."
  [frame-id router depth last-event]
  (let [{:keys [queue]} @router
        queue-size      (count queue)
        ;; The halting event — the next one that would have been dequeued.
        ;; It never runs; its event vector pins the `:halted-depth` marker.
        ;; The queue holds ENVELOPES (`build-envelope` maps), so reach the
        ;; raw `[event-id …]` vector through `:event`. Falls back to
        ;; `last-event` (the most-recently-run event) if the queue is empty
        ;; at the halt seam (defensive — the depth-exceed path always has a
        ;; pending child under the runaway-cascade pattern that trips it).
        halting-envelope (peek queue)
        halting-event   (or (:event halting-envelope) last-event)
        ;; rf2-bh56rc: the halting event's causal `:rf/time-ms` (stamped on its
        ;; envelope at the causal boundary). Threaded into the synthesised
        ;; `:halted-depth` record's `:committed-at` so even this never-ran
        ;; marker carries a replayable causal time per EP-0010 §Time / Spec
        ;; 002 §Recordable coeffects, not an ambient assembly-time read. nil
        ;; only on the defensive empty-queue fallback (no envelope to read);
        ;; the epoch surface tolerates a nil `:committed-at` there.
        halting-time-ms (-> halting-envelope :rf.cofx :rf/time-ms)
        ;; Current durable frame-state value — the state the last-settled
        ;; event left behind. The halting event makes no write, so
        ;; :frame-state-before equals :frame-state-after on its record.
        ;; EP-0001 (rf2-3aizt1, decision #2): the whole frame-state (both
        ;; partitions), not app-db alone.
        ;;
        ;; rf2-bhu3a0: this live re-read is now the FALLBACK only — the epoch
        ;; surface's `commit-halt-record!` prefers the canonical last-settled
        ;; epoch record's `:frame-state-after` (the principled durable source,
        ;; the value restore rewinds to) and uses this passed value only when
        ;; no `:ok` epoch has landed yet (a depth-exceed on the first cascade).
        fs-now          (frame/frame-state-value frame-id)
        halt-reason     {:operation  :rf.error/drain-depth-exceeded
                         :depth      depth
                         :queue-size queue-size
                         :last-event last-event}]
    (trace/emit-error! :rf.error/drain-depth-exceeded
                       {:frame      frame-id
                        :depth      depth
                        :queue-size queue-size
                        :last-event last-event
                        ;; Per rf2-nj6p7: no whole-drain rollback under
                        ;; per-event epochs — the already-settled events
                        ;; are durable. `:rollback? false` reflects that.
                        :rollback?  false
                        :recovery   :no-recovery})
    (swap! router assoc :queue interop/empty-queue :scheduled? false)
    (when-let [commit-halt! (late-bind/get-fn-cached :epoch/commit-halt-record!)]
      ;; The halting event never ran, so the capture buffer is empty and
      ;; `settle!` would skip; `commit-halt-record!` commits regardless,
      ;; pinning the halting event's trigger. :frame-state-before equals
      ;; :frame-state-after — the halting event made no write. rf2-bh56rc:
      ;; `:committed-at` is the halting event's causal `:rf/time-ms`, not an
      ;; ambient read.
      (commit-halt! frame-id fs-now fs-now halting-time-ms :halted-depth halt-reason
                    halting-event))))

(defn- settle-event-epoch!
  "Commit the just-completed event's epoch (Tool-Pair §Time-travel). Per
  Spec 002 §Drain versus event — the epoch unit (rf2-u6jsj/rf2-nj6p7):
  the epoch boundary is the dequeued EVENT, not the drain-settle. Called
  by `run-one-pass!` after each `process-event!` returns, with that one
  event's own pre-/post-cascade db snapshot pair. The epoch surface
  harvests the in-flight capture buffer — which, within a frame's
  single-threaded run-to-completion drain, holds exactly this event's
  six-domino cascade (the previous event already harvested its own at its
  settle). A machine macrostep ran inside `process-event!` and rides this
  one event's buffer / epoch (Spec 005 §macrostep), so `:raise` /
  `:always` microsteps do not allocate a new epoch.

  `settle!` itself skips an empty buffer (a rejected/aborted dispatch that
  never fired `:event/run-start`), so a no-handler / frame-destroyed early
  exit commits no misleading record.

  EP-0001 (rf2-3aizt1, decision #2): `frame-state-before` / `frame-state-after`
  are whole frame-state values (both partitions); `build-record` derives the
  `:db-before` / `:db-after` app-db projections from them.

  rf2-bh56rc: `committed-at` is the settling event's causal `:rf/time-ms` (its
  envelope's `:rf.cofx` `:rf/time-ms`, stamped at the causal boundary).
  Threaded into the epoch record's `:committed-at` so the durable
  causal-time fact is replayable per EP-0010 §Time / Spec 002 §Recordable
  coeffects, not an ambient assembly-time host-clock read."
  [frame-id frame-state-before frame-state-after committed-at]
  (when-let [settle! (late-bind/get-fn-cached :epoch/settle!)]
    (settle! frame-id frame-state-before frame-state-after committed-at)))

;; ---- drain-loop! phases ---------------------------------------------------
;;
;; `drain-loop!` decomposes into five named phases per audit RT4 (rf2-hpkjg).
;; Each phase is a pure-ish helper that owns one piece of the lock-release
;; contract; the outer `drain-loop!` is now a thin driver that sequences them.
;;
;;   mark-drainer!         set `:in-drain?` to the current thread marker
;;   clear-drainer!        clear `:in-drain?` (finally-block partner)
;;   take-event!           peek+pop one envelope under the single-drainer
;;                         invariant (rf2-ynk7); returns nil on empty queue
;;   run-one-pass!         the inner loop body: process events to fixed
;;                         point or until depth limit; returns ::halt or
;;                         ::settled
;;   force-release-on-halt!  release the drain-lock after a ::halt outcome
;;                         (queue already drained by `handle-depth-exceeded!`)
;;   try-release-on-empty!   under lock, re-check queue; release both flags
;;                         on still-empty (returns false) or signal another
;;                         pass (returns true) — the orphan-prevention seam.

(defn- mark-drainer!
  "Stamp `:in-drain?` with this thread's marker so the dispatch-sync guard
  can distinguish same-thread nesting from a concurrent caller. Per
  rf2-ynk7. On CLJS — single-threaded — every check is necessarily
  same-thread, so `true` works as the marker."
  [router]
  (swap! router assoc :in-drain? #?(:clj (Thread/currentThread) :cljs true)))

(defn- clear-drainer!
  "Clear the `:in-drain?` marker. Paired with `mark-drainer!` in a
  try/finally to ensure the marker never outlives the pass."
  [router]
  (swap! router assoc :in-drain? nil))

(defn- take-event!
  "Atomic peek+pop of one envelope from the router queue. Returns the
  envelope or nil when the queue is empty.

  Per rf2-ynk7: with the single-drainer invariant held by `:drain-lock`,
  this peek+pop pair is atomic w.r.t. any other drain attempt. The
  pre-fix race (executor and main thread both peek the same envelope)
  cannot occur — the loser of the CAS in `drain-try!` / `drain-block!`
  never reaches this code.

  rf2-tgea2z: ONE `swap-vals!` per dequeue instead of a deref PLUS a
  separate `swap!`, halving the atom traffic on the hottest per-event
  step. The swap pops the head when non-empty (idempotent no-op when
  empty, so the empty case never `pop`s a `PersistentQueue` it shouldn't);
  the popped envelope is read from the PRE-swap value the `swap-vals!`
  returns — i.e. the head at the instant of the pop, strictly more atomic
  than the prior deref-then-swap peek. A concurrent submitter only ever
  `conj`s the tail (sync seed-pushes are serialised under the drain-lock
  per `drain-block!`), so the head this pops is unchanged by any enqueue."
  [router]
  (let [[{old-queue :queue} _]
        (swap-vals! router
                    (fn [{:keys [queue] :as r}]
                      (if (empty? queue)
                        r
                        (assoc r :queue (pop queue)))))]
    (when-not (empty? old-queue)
      (peek old-queue))))

(defn- handle-drain-interrupted!
  "Per rf2-68kok / Spec 002 §Edge cases worth pinning §Frame disposal
  mid-drain: the drain-loop detected the frame was destroyed before
  the next dequeue. Drop the remaining queue ONCE, clear `:scheduled?`,
  and emit a single `:rf.frame/drain-interrupted` lifecycle trace
  carrying `:dropped-count` (per Spec 009 §`:rf.frame/drain-interrupted`
  and Spec-Schemas §DrainInterruptedTags).

  In-flight events are not affected — they have already been dequeued
  and `process-event!` ran them to completion before this check fires
  (run-to-completion per Spec 002 §Rules rule 1). Only events still
  in the queue at the moment of the check are dropped.

  The check fires AFTER `process-event!` returns and BEFORE the next
  `take-event!` — same seam as `handle-depth-exceeded!`.

  Per rf2-9neiq: this seam NO LONGER commits the `:halted-destroy` epoch
  record. That record is owned by a single site — the epoch destroy hook
  (`re-frame.epoch.listeners/on-frame-destroyed!`), invoked synchronously
  from `frame/destroy-frame!` (step 8) the instant the handler destroyed
  its own frame. That site carries the cascade's harvested buffer AND the
  pre-cascade / destroy-time frame-state snapshots (threaded via
  `frame/*cascade-frame-state-before*` + the destroy-time container read), so it
  builds a record with real `:frame-state-before` / `:frame-state-after`
  (and their `:db-*` app-db projections) per Spec-Schemas
  §`:rf/epoch-record` §Outcomes. Routing a second `:halted-destroy` commit
  through `settle!` here would either no-op on the now-empty (already-
  harvested-by-the-hook) buffer or, worse, double-fan a duplicate record to
  listeners. The drain-loop's responsibility is the lifecycle trace + queue
  drop; the epoch record is the destroy hook's."
  [frame-id router]
  (let [dropped (count (:queue @router))]
    (swap! router assoc :queue interop/empty-queue :scheduled? false)
    (trace/emit! :rf.frame :rf.frame/drain-interrupted
                 {:frame         frame-id
                  :dropped-count dropped})))

(defn- run-one-pass!
  "Process events from the queue to fixed point or until `drain-depth` is
  exceeded. Returns `::settled` when the queue empties cleanly or
  `::halt` when the depth limit is reached OR the frame was destroyed
  mid-pass (the depth-exceeded / drain-interrupted handler has already
  cleared the queue and the `:scheduled?` flag in either halt case).

  Per rf2-68kok / Spec 002 §Frame disposal mid-drain: the destroyed-
  frame check fires BEFORE each dequeue, so an in-flight event runs to
  completion (run-to-completion per Spec 002 §Rules rule 1) but events
  still in the queue at the check point are dropped, with one
  `:rf.frame/drain-interrupted` lifecycle trace emitted carrying the
  dropped count.

  Per rf2-u6jsj/rf2-nj6p7 §Drain versus event — the epoch unit: the epoch
  boundary is the dequeued EVENT, not the drain. Each event takes its OWN
  pre-cascade `frame-state-before` snapshot immediately before
  `process-event!` and its OWN post-cascade `frame-state-after` immediately
  after; `settle-event-epoch!` commits one `:rf/epoch-record` per event. A
  drain that processes a parent and an `:fx [[:dispatch …]]` child it queued
  therefore commits TWO records — one per event — even though both settled in
  the same drain. EP-0001 (rf2-3aizt1, decision #2): the snapshot is the whole
  frame-state (both partitions), so an epoch carries machine snapshots / route
  slice / SSR metadata, not just app-db. The per-event `frame-state-before` is
  also bound to `frame/*cascade-frame-state-before*` around `process-event!`
  so a handler that destroys its own frame mid-drain can recover the
  pre-cascade snapshot for its `:halted-destroy` epoch record (rf2-9neiq)."
  [frame-id router drain-depth]
  (loop [depth      0
         last-event nil]
    (cond
      (>= depth drain-depth)
      (do (handle-depth-exceeded! frame-id router depth last-event)
          ::halt)

      ;; Per rf2-68kok: destroyed-frame check fires BEFORE the next
      ;; dequeue. A handler in the just-completed event may have
      ;; called `destroy-frame!` on its own frame; the spec calls for
      ;; interrupting the drain at this exact seam — drop the
      ;; remaining queue, emit one `:rf.frame/drain-interrupted`
      ;; lifecycle event, halt.
      ;;
      ;; Per rf2-v0jwt / rf2-9neiq: the just-completed event already ran in
      ;; full (run-to-completion) AND already settled its own per-event
      ;; epoch (rf2-nj6p7) — that record is durable. The `:halted-destroy`
      ;; record for the interrupted drain is committed by the epoch destroy
      ;; hook (`on-frame-destroyed!`), which fired synchronously inside the
      ;; handler that called `destroy-frame!`, carrying the cascade buffer
      ;; and real db snapshots; this seam only drops the queue and emits the
      ;; `:rf.frame/drain-interrupted` lifecycle trace. `restore-epoch!`
      ;; refuses non-:ok records, preserving the original "time-travel never
      ;; lands in a misleading state" invariant.
      (frame/frame-disposed-for-drain? frame-id)
      (do (handle-drain-interrupted! frame-id router)
          ::halt)

      :else
      (if-let [envelope (take-event! router)]
        ;; Per rf2-nj6p7: per-event epoch boundary. Snapshot this event's
        ;; OWN frame-state-before, run it to completion, snapshot its
        ;; frame-state-after, and settle its epoch — before the next event is
        ;; dequeued.
        ;;
        ;; EP-0001 (rf2-3aizt1, decision #2): the canonical snapshot unit is
        ;; the whole frame-state (both partitions — app-db + runtime-db), so
        ;; an epoch carries (and `restore-epoch!` rewinds to) machine snapshots
        ;; / the route slice / SSR metadata, not just app-db.
        (let [fs-before (frame/frame-state-value frame-id)
              ;; rf2-bh56rc: this event's causal `:rf/time-ms` — the
              ;; `:rf.cofx` `:rf/time-ms` stamped on the envelope at the
              ;; causal boundary (`build-envelope`). Threaded into the epoch
              ;; record's `:committed-at` (per EP-0010 §Time / Spec 002
              ;; §Recordable coeffects) so the durable causal-time fact is
              ;; replayable rather than an ambient assembly-time clock read.
              time-ms   (-> envelope :rf.cofx :rf/time-ms)]
          ;; Per rf2-9neiq: expose this event's pre-cascade frame-state to a
          ;; handler that calls `destroy-frame!` on its OWN frame mid-drain.
          ;; `destroy-frame!`'s epoch hook reads `frame/*cascade-frame-state-before*`
          ;; for the `:halted-destroy` record's pre-cascade snapshot — the
          ;; value the frame-state held before this in-flight event's cascade
          ;; began, which is otherwise gone by the time the (post-dissoc)
          ;; epoch hook fires. rf2-bh56rc: `*cascade-time-ms*` is bound the
          ;; same way so the mid-drain `:halted-destroy` record's
          ;; `:committed-at` is THIS event's causal time, not an ambient read.
          (binding [frame/*cascade-frame-state-before* fs-before
                    frame/*cascade-time-ms*            time-ms]
            (process-event! envelope))
          (let [fs-after (frame/frame-state-value frame-id)]
            (settle-event-epoch! frame-id fs-before fs-after time-ms))
          (recur (inc depth) (:event envelope)))
        ::settled))))

(defn- force-release-on-halt!
  "Release the drain-lock after a `::halt` outcome. The depth-exceeded
  handler has already forcibly cleared the queue and set `:scheduled?`
  false, so we only need to drop the lock. Taken under `locking router`
  to serialize against `ensure-drain-scheduled!`'s flag-read."
  [router drain-lock]
  (locking router
    (reset! drain-lock false)))

(defn- try-release-on-empty!
  "Under the same lock that submitters take in `ensure-drain-scheduled!`,
  re-check the queue:

    * Empty  — clear `:scheduled?` AND release `:drain-lock` under one
               lock so a serialized submitter observes both flags false
               and schedules a fresh drain. Returns false (drainer is
               done).
    * Non-empty — a submitter enqueued between the inner empty-check
               and now. Leave both flags set and return true so the
               caller recurs into another pass.

  This is the orphan-prevention seam."
  [router drain-lock]
  (locking router
    (let [{:keys [queue]} @router]
      (if (empty? queue)
        (do (swap! router assoc :scheduled? false)
            (reset! drain-lock false)
            false)
        true))))

(defn- drain-loop!
  "The drain body proper. Assumes the caller holds `:drain-lock` (per
  rf2-ynk7 §single-drainer invariant) so this fn has exclusive access
  to the queue's peek+pop pair.

  Sequences three named phases per pass:

    1. mark-drainer! — stamp the in-drain marker (cleared in finally).
    2. run-one-pass! — process events to fixed point or depth-halt.
    3. force-release-on-halt! / try-release-on-empty! — outcome-specific
       release sequence under `locking router`.

  Outer loop re-enters whenever `try-release-on-empty!` reports a
  submitter raced in between the inner empty-check and the lock-protected
  release window. Per-event epoch snapshots (rf2-nj6p7) are taken inside
  `run-one-pass!` per dequeued event, not here."
  [frame-id router drain-lock drain-depth]
  (loop []
    (let [outcome (try
                    (mark-drainer! router)
                    (run-one-pass! frame-id router drain-depth)
                    (finally
                      (clear-drainer! router)))]
      (case outcome
        ::halt    (force-release-on-halt! router drain-lock)
        ::settled (when (try-release-on-empty! router drain-lock)
                    (recur))))))

(defn- drain-emergency-release!
  "Mid-drain panic path. An unhandled exception escaped `drain-loop!`
  past its own `finally` cleanup. Clear the router flags and release
  the drain-lock so the frame is not permanently stuck — then re-throw
  so the caller observes the failure."
  [router drain-lock]
  (locking router
    (swap! router assoc :scheduled? false :in-drain? nil)
    (reset! drain-lock false)))

(defn- drain-try!
  "Async drain entry point (called from `interop/next-tick`). CAS-tries
  the drain-lock; on lose, the active drainer holds the responsibility
  for the queue (its release block re-checks under lock — see
  drain-loop!). On win, runs the drain body and releases.

  Per rf2-ynk7 §single-drainer invariant."
  [frame-id]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get-in frame-record [:config :drain-depth] drain-depth-default)]
        (when (compare-and-set! drain-lock false true)
          (try
            (drain-loop! frame-id router drain-lock drain-depth)
            (catch #?(:clj Throwable :cljs :default) t
              (drain-emergency-release! router drain-lock)
              (throw t))))))))

(defn- drain-block!
  "Synchronous drain entry point (called from `dispatch-sync!`). Unlike
  the async path, dispatch-sync's contract requires the cascade settle
  before return — so on CAS-loss this path BLOCKS (Thread/yield on JVM;
  trivially uncontended on CLJS) until the active drainer releases the
  lock, then runs `under-lock-fn` (typically the seed-push) and drains.

  Per rf2-ynk7 §single-drainer invariant: dispatch-sync's seed-push at
  the FRONT of the queue MUST happen while it holds the drain-lock —
  otherwise the prepend interleaves with the active drainer's peek+pop
  and produces the same race the drain-lock was introduced to fix
  (envelope A peek'd, B prepended, A popped becomes B, B processed as
  if it were A's pop result). The `under-lock-fn` callback shape lets
  the caller perform the seed-push inside the lock seam.

  `under-lock-fn` runs once, immediately after CAS-acquire, before the
  drain loop. Exceptions inside it propagate through the same emergency-
  release path as the drain loop body."
  [frame-id under-lock-fn]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get-in frame-record [:config :drain-depth] drain-depth-default)]
        ;; Spin-CAS until we acquire. On JVM the active drainer holds
        ;; the lock for the duration of one drain pass — bounded by
        ;; drain-depth events at most — so the wait is bounded. CLJS
        ;; is single-threaded; the CAS succeeds on first attempt.
        (loop []
          (when-not (compare-and-set! drain-lock false true)
            #?(:clj (Thread/yield))
            (recur)))
        (try
          (under-lock-fn)
          (drain-loop! frame-id router drain-lock drain-depth)
          (catch #?(:clj Throwable :cljs :default) t
            (drain-emergency-release! router drain-lock)
            (throw t)))))))

(defn- ensure-drain-scheduled!
  [frame-id router]
  (let [should-schedule?
        (locking router
          (let [{:keys [scheduled?]} @router]
            (if scheduled?
              false
              (do (swap! router assoc :scheduled? true)
                  true))))]
    (when should-schedule?
      (interop/next-tick (fn [] (drain-try! frame-id))))))

(defn- emit-dispatched-trace!
  "Emit the :event :event/dispatched trace event for this envelope. Per
  Spec 009 §Dispatch correlation, :dispatch-id and :parent-dispatch-id
  ride on :tags. Per Spec 002 §Dispatch origin tagging, :origin rides
  on :tags too. Per rf2-1ve9h (Mike-approved Option A, 2026-05-28), the
  prior parallel `:rf/dispatch-origin` axis was collapsed into
  `:source` — `:source` is the single closed-enum functional-origin
  classifier and rides on :tags so Xray's L2 epoch timeline + Event
  panel can render the per-row source tag, the DISPATCH step's
  per-kind chrome, and per-source filter pills. Spec elision is
  automatic — trace/emit! short-circuits when interop/debug-enabled?
  is false at compile time.

  Per rf2-qsjda: queue-time `:rf.trace/no-emit?` consideration. The
  `*handler-scope*` binding's `:no-emit?` slot doesn't exist yet at
  enqueue time, so we read the flag directly off the target handler's
  registration meta and short-circuit the `:event/dispatched` emit
  when set. Without this, a Xray-style bookkeeping handler would
  have its enqueue trace delivered to listeners (re-entering the
  consumer's trace-cb) before the handler-scope binding ever took
  effect.

  Per rf2-twt7m Change 1: hoist `:rf.trace/call-site` onto this
  success-path emit too. The envelope's `:call-site` was stamped by
  the surface `dispatch` / `dispatch-sync` macro (rf2-ts1a); we
  publish it through `trace/with-call-site` so `build-event` hoists
  it onto the trace event via the existing scope-driven hoist path
  (same machinery the error path uses). Without this, the Event lens
  redesign (rf2-zh2qc) and any consumer building click-to-source UX
  on the enqueue trace would lose the dispatch-site coord."
  [envelope sync?]
  (let [event        (:event envelope)
        event-id     (when (vector? event) (first event))
        ;; The `:rf.trace/no-emit?` gate reads the TARGET handler's meta from
        ;; the registrar. Dev-only (this whole emit DCEs under `goog.DEBUG=false`).
        handler-meta (when event-id
                       (registrar/lookup :event event-id))
        no-emit?     (trace/no-emit?-from-meta handler-meta)]
    (when-not no-emit?
      (trace/with-call-site (:call-site envelope)
        (trace/emit! :rf.event :rf.event/dispatched
                     ;; Per rf2-jt854w (EP-0010 observability completion) /
                     ;; EP-0017 (rf2-alc1lf): stamp the envelope's flat
                     ;; recordable-coeffect map onto the enqueue trace so Xray's
                     ;; Event lens (018 §5.1) can render the COEFFECTS surface —
                     ;; the framework-stamped `:rf/time-ms` plus any
                     ;; caller-supplied owner-qualified facts. Without it the
                     ;; only trace-side view of the causal token is the filtered
                     ;; framework-default cofx (the user-cofx projection drops it
                     ;; via `fx/framework-coeffect-keys`), so the lens had no
                     ;; data.
                     ;;
                     ;; DEBUG-GATED via the canonical OUTERMOST
                     ;; `(if interop/debug-enabled? <stamped> <plain>)` shape —
                     ;; the dev arm carries the `:rf.cofx` slot, the
                     ;; prod arm omits it. This is the rf2-7ynhyn-correct idiom:
                     ;; NOT a `cond->` test-position gate, because Closure does
                     ;; not constant-fold a keyword literal away from a `cond->`
                     ;; step test, so a `(envelope :rf.cofx) (assoc …)`
                     ;; step would leave the slot reachable under `:advanced` +
                     ;; `goog.DEBUG=false`. This is a dev-trace / diagnostic
                     ;; surface, NOT always-on — unlike the envelope's
                     ;; `:rf.cofx` itself (durable causal data, stamped
                     ;; unconditionally in `build-envelope`, present in
                     ;; production). The whole `:rf.event/dispatched` emit
                     ;; already elides in production (the `event/dispatched` op
                     ;; keyword is a `check-elision.cjs` dev-only sentinel), so
                     ;; the dev arm rides that same whole-body elision; the
                     ;; outermost `if` makes the slot's absence in the prod arm
                     ;; structurally explicit rather than incidental.
                     (cond-> (if interop/debug-enabled?
                               {:rf.event/v         event
                                :frame              (:frame envelope)
                                :rf.event/origin    (:origin envelope)
                                :source             (:source envelope)
                                :rf.event/sync?     sync?
                                :rf.cofx            (:rf.cofx envelope)}
                               {:rf.event/v         event
                                :frame              (:frame envelope)
                                :rf.event/origin    (:origin envelope)
                                :source             (:source envelope)
                                :rf.event/sync?     sync?})
                       (:dispatch-id envelope)
                       (assoc :rf.trace/dispatch-id (:dispatch-id envelope))
                       (:parent-dispatch-id envelope)
                       (assoc :rf.trace/parent-dispatch-id (:parent-dispatch-id envelope))
                       ;; Per rf2-5qp4g: optional per-source-kind detail
                       ;; map (e.g. `{:ms 500}` for `:dispatch-later`)
                       ;; so the Epoch panel's DISPATCH source-kind
                       ;; enrichment (spec/021 §9.1.6.3) can render
                       ;; kind-specific chrome. Only stamped when the
                       ;; envelope carried `:source-detail` (the
                       ;; substrate dispatch site opt-in).
                       (:source-detail envelope)
                       (assoc :rf.event/source-detail (:source-detail envelope))
                       ;; rf2-8j4h7i: the `:initial-events` setup-step index
                       ;; (EP-0027 §Provenance) — stamped only on frame-init
                       ;; dispatches (the only envelopes carrying `:step-index`),
                       ;; so tools can navigate per setup step alongside the
                       ;; `:source :frame-init` tag. This whole `:rf.event/
                       ;; dispatched` emit already elides in production, so the
                       ;; tag rides that whole-body elision (it is a dev trace,
                       ;; not always-on durable data).
                       (:step-index envelope)
                       (assoc :rf.frame/init-step-index (:step-index envelope))))))))

(defn- front-insert-machine-internal
  "Return `q` (a PersistentQueue of envelopes) with `envelope` spliced in
  at the boundary between the machine-internal PREFIX and the external
  TAIL — i.e. after any already-queued machine-internal envelopes but
  ahead of the first external one.

  Why a boundary splice, not a head `cons`: each sibling machine-internal
  dispatch from one macrostep (`:fx [[:dispatch :a] [:dispatch :b]]`,
  walked left-to-right by `do-fx`) is a SEPARATE `dispatch!` call, so this
  fn is invoked once per sibling. A plain head-push would reverse them
  (`:b` ends up ahead of `:a`). Inserting each new internal envelope at
  the END of the existing internal prefix keeps siblings in source order
  (`[:a :b …external]`) while still placing the whole internal run ahead
  of every external event already on the queue.

  PersistentQueue has no native splice, so the queue is rebuilt: take the
  leading run of machine-internal envelopes, append `envelope`, then the
  external remainder. `split-with` on `:rf.machine/internal?` is exact —
  external envelopes never carry the flag."
  [q envelope]
  (let [[internal external] (split-with :rf.machine/internal? q)]
    (into interop/empty-queue (concat internal [envelope] external))))

(defn- enqueue-envelope!
  "Insert `envelope` into the frame's router queue. Per Spec 002, ordinary
  dispatches go to the BACK (plain FIFO via `conj` on the PersistentQueue).

  Per rf2-j20a7 / Spec 005 §Level 4 — the one exception: a machine-
  internal continuation envelope (`:rf.machine/internal? true`, stamped
  by `build-envelope` from the machine-tagged child opts) leap-frogs
  ahead of any already-queued EXTERNAL events, so the machine settles its
  macrostep to quiescence before the next external event runs (SCXML
  'internal before external'). It is spliced in by
  `front-insert-machine-internal` AFTER any sibling machine-internal
  envelopes already queued this macrostep, so source order is preserved
  among siblings (first emitted is dequeued first).

  Front-of-queue changes ORDER ONLY, not granularity: the leap-frogged
  envelope is still a separately-dequeued event with its own epoch (per
  Spec 002 §Drain versus event and Spec 005 §Level 4). `:raise` is a
  different lever — it never reaches this queue (it drains in-memory,
  intra-macrostep, inside the machine handler invocation)."
  [router envelope]
  (if (:rf.machine/internal? envelope)
    (swap! router update :queue front-insert-machine-internal envelope)
    (swap! router update :queue conj envelope)))

(defn dispatch!
  "Append the event to the target frame's router queue. Per Spec 002:
  FIFO at the runtime layer. The drain loop picks it up in this same
  drain cycle (run-to-completion).

  Per rf2-j20a7 / Spec 005 §Level 4: the single exception to FIFO is a
  machine-internal continuation event (a dispatch emitted from a
  machine's own processing), which `enqueue-envelope!` inserts at the
  FRONT of the queue so the machine settles its macrostep before the
  next external event. The cut is the dispatch's ORIGIN (machine
  processing), not its target — an event that merely targets a machine
  but originates from user code / the UI / a non-machine effect stays
  FIFO at the back.

  Per rf2-ts1a: the runtime-callable fn form (`re-frame.core/dispatch*`
  in public API terms). The macro form `re-frame.core/dispatch` stamps
  an `:rf.trace/call-site` onto `opts` at compile time; from there it
  rides the envelope and gets bound around the handler chain's
  invocation in `process-event!`.

  Canonical `event` shape is `[<id>]`, `[<id> <single-scalar>]`, or
  `[<id> <map>]` — best practice, not enforced. Variadic vectors are
  tolerated for v1-migration / caller convenience. See spec/Conventions.md
  §Canonical event-vector shape."
  ([event] (dispatch! event {}))
  ([event opts]
   (let [envelope     (build-envelope event opts)
         frame-record (frame/frame (:frame envelope))]
     (cond
       (nil? frame-record)
       ;; Per rf2-2hvga (= B + recover-but-emit): dispatch into a
       ;; destroyed / unknown frame RECOVERS (no-op — the event is not
       ;; enqueued) AND emits a production-survivable
       ;; `:rf.error/frame-destroyed` via the always-on listener (axis 1).
       ;; The call-site is bound so the DEV trace path inside
       ;; `emit-frame-destroyed!` carries it; the always-on record reads
       ;; its coords off the parallel error-coord registry, not the
       ;; dynamic call-site.
       (trace/with-call-site (:call-site envelope)
         (emit-frame-destroyed! (first event) event (:frame envelope)))

       :else
       (let [router (:router frame-record)]
         (emit-dispatched-trace! envelope false)
         (enqueue-envelope! router envelope)
         (ensure-drain-scheduled! (:frame envelope) router)))
     nil)))

(defn dispatch-sync!
  "Bypass the queue scheduler and process this single event end-to-end
  immediately, then drain any synchronously-enqueued events to fixed
  point. Per Spec 002 §dispatch-sync: this is for outside-the-runtime
  callers (test setup, REPL). Calling from inside a handler raises
  :rf.error/dispatch-sync-in-handler — handler bodies should use
  dispatch (the queued form) instead.

  Implementation: the seed event is pushed at the FRONT of the queue
  and then the drain loop runs. Because the scheduled? flag is set to
  true before draining, any dispatch! calls inside the seed handler's
  :fx vector enqueue without scheduling an async drain — the sync drain
  picks them up. Counting the seed event as drain depth 0 keeps drain-
  depth limits behaving uniformly across sync and async dispatch.

  Per rf2-fp97: when the same-frame reentry check passes but ANOTHER
  frame is currently mid-drain, the runtime emits
  `:rf.warning/cross-frame-dispatch-sync-during-drain` and continues
  with the dispatch. The cross-frame cascade interleaves (target frame
  drains to settled before the caller's drain resumes); per Spec 002
  §Rules rule 1 this is intentional (frames are independent state
  machines) but rarely the caller's intent, so the warning surfaces the
  pattern for observability tools without refusing the call.

  Per rf2-ts1a: runtime-callable fn form for `dispatch-sync` (the macro
  form stamps an `:rf.trace/call-site` onto `opts` at compile time)."
  ([event] (dispatch-sync! event {}))
  ([event opts]
   (let [envelope     (build-envelope event opts)
         frame-record (frame/frame (:frame envelope))
         ;; Read the call-site from the envelope (already gated in
         ;; build-envelope) so the synchronous error emits below can
         ;; carry it without referencing the keyword a second time.
         call-site    (:call-site envelope)
         ;; Nested-sync detection, hoisted out of the cond TEST position so
         ;; the cond reads as flat test→result pairs. True when this call is
         ;; reentering the SAME frame's running drain — either an explicit
         ;; in-sync-drain flag is set, OR this thread is itself the active
         ;; drainer (`same-thread-drain?`). nil frame-record is handled by the
         ;; earlier cond clause, so deref-ing `(:router frame-record)` here is
         ;; safe.
         nested-sync?
         (when frame-record
           (let [router-state @(:router frame-record)
                 ;; Per rf2-ynk7: `:in-drain?` now holds the drainer's
                 ;; thread (or nil). Only flag as "nested" when the current
                 ;; thread is the drainer — a different thread mid-drain is
                 ;; a concurrent caller, which `drain-block!` handles
                 ;; correctly by spin-CAS-waiting. CLJS: `:in-drain?` is
                 ;; `true` or `nil`; the equality check still discriminates
                 ;; (truthy = same-thread by construction on a single-
                 ;; threaded host).
                 same-thread-drain?
                 #?(:clj  (identical? (:in-drain? router-state) (Thread/currentThread))
                    :cljs (true? (:in-drain? router-state)))]
             (or (:in-sync-drain? router-state) same-thread-drain?)))]
     (cond
       (nil? frame-record)
       ;; Per rf2-2hvga (= B + recover-but-emit): dispatch-sync into a
       ;; destroyed / unknown frame RECOVERS (no-op) AND emits the
       ;; production-survivable `:rf.error/frame-destroyed` through the
       ;; always-on listener.
       (trace/with-call-site call-site
         (emit-frame-destroyed! (first event) event (:frame envelope)))

       nested-sync?
       ;; Per Spec 002 §dispatch-sync: nesting dispatch-sync inside the
       ;; SAME frame's running drain (sync or async) is an error — the
       ;; event would interleave with the outer handler's run-to-completion.
       (trace/with-call-site call-site
         ;; The rejected inner event vector rides the schema-required
         ;; `:rf.event/v` tag (Spec-Schemas §DispatchSyncInHandlerTags;
         ;; Spec 009 §Error event catalogue) — NOT the undocumented bare
         ;; `:event` (rf2-kg0et6). Trace/schema consumers that route per
         ;; category read the event vector under the documented key.
         (trace/emit-error! :rf.error/dispatch-sync-in-handler
                            {:frame      (:frame envelope)
                             :rf.event/v event
                             :reason     "dispatch-sync called from inside a running drain. Use dispatch (the queued form) instead so the event runs after the current drain settles."
                             :recovery   :no-recovery}))

       :else
       (let [router (:router frame-record)]
         ;; Per rf2-fp97 (Mike's 2026-05-13 Option B decision): the
         ;; same-frame reentry check passed; now check whether any OTHER
         ;; frame is mid-drain. If so, the dispatch will interleave with
         ;; that frame's cascade — warn but proceed. Dev-only: gated on
         ;; `interop/debug-enabled?` so production skips the registry
         ;; walk.
         (when interop/debug-enabled?
           (when-let [other-id (diag/other-frame-mid-drain (:frame envelope))]
             (diag/emit-cross-frame-warning! (:frame envelope) other-id event)))
         (emit-dispatched-trace! envelope true)
         (try
           ;; Per rf2-ynk7 §single-drainer invariant: dispatch-sync
           ;; needs the cascade settled before return AND the seed-
           ;; push at the FRONT of the queue must not interleave with
           ;; an active drainer's peek+pop. drain-block! spin-CAS-
           ;; acquires the drain-lock, THEN runs the callback below
           ;; (the prepend now sits inside the single-drainer window —
           ;; no other drain can be mid-peek+pop), THEN runs the drain
           ;; loop. The :in-sync-drain? flag suppresses any concurrent
           ;; dispatch-sync from another thread; :scheduled? true
           ;; suppresses async drain scheduling mid-cascade.
           ;; :in-sync-drain? is cleared in the outer finally after
           ;; drain-block! returns.
           (drain-block!
             (:frame envelope)
             (fn []
               (swap! router (fn [{:keys [queue] :as r}]
                               (assoc r
                                      :queue (into interop/empty-queue
                                                   (cons envelope queue))
                                      :scheduled?     true
                                      :in-sync-drain? true)))))
           (finally
             (swap! router assoc :in-sync-drain? false)))))
     nil)))

;; ---- late-bind hook registration ------------------------------------------
;;
;; Other namespaces that load BEFORE this one (re-frame.frame for :initial-events
;; / :on-destroy, re-frame.fx for :dispatch / :dispatch-later) need to call
;; into the router. They cannot `:require` this namespace without a cyclic
;; load order, so we publish our entry points through the late-bind hook
;; registry once this namespace is loaded. The hook keys are documented in
;; re-frame.late-bind.

(late-bind/set-fn! :router/dispatch!       dispatch!)
(late-bind/set-fn! :router/dispatch-sync!  dispatch-sync!)
