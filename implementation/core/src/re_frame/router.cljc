(ns re-frame.router
  "Per-frame FIFO router and the drain loop. Per Spec 002 §Run-to-completion
  dispatch (drain semantics) and §Drain-loop pseudocode.

  The router maintains a per-frame FIFO queue. Dispatch appends to the
  back; the drain loop dequeues, runs the handler, applies effects, and
  loops until the queue empties or a terminal depth/destroy boundary halts it.
  Run-to-completion is locked: every event
  dispatched synchronously during a drain normally settles to fixed point
  before any further external event is processed for that frame, and before
  any view re-renders. A depth halt or successful exact-incarnation destroy
  claim is terminal: an authored callback already on the stack may return and
  entered authored interceptor afters may unwind, but its returned framework
  tail is inert; no later ordinary event or intermediate render begins."
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

(defn- call-while-exact-owner
  "Invoke callback-bearing `f` only for the captured frame incarnation.

  A callback may synchronously destroy A and either return or throw. In both
  cases its result/error is inert once exact ownership is lost; a real error is
  rethrown only while A remains current."
  ([frame-id owner-token f]
   (if-not (frame/event-continuation-live? frame-id owner-token)
     ::stale-incarnation
     (try
       (let [result (f)]
         (if (frame/event-continuation-live? frame-id owner-token)
           result
           ::stale-incarnation))
       (catch #?(:clj Throwable :cljs :default) e
         (if (frame/event-continuation-live? frame-id owner-token)
           (throw e)
           ::stale-incarnation)))))
  ([frame-id owner-token allow-closing? f]
   (frame/call-with-event-owner-token
     frame-id owner-token allow-closing?
     #(call-while-exact-owner frame-id owner-token f))))

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
                        reads the scope/hold stamp (`with-frame`, a
                        `frame-provider` (SCOPE) or a `frame-root`
                        (ENSURE) boundary, or a captured
                        `*current-frame*` binding). Absence raises
                        `:rf.error/no-frame-
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
                        nil for the direct HoF fn-form path (calling this
                        fn, or the CLJS same-name `dispatch` value-alias,
                        directly) and under `goog.DEBUG=false` advanced
                        builds."
  [event opts]
  (when (trace/continuation-live?)
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
        ;; EP-0010: VALIDATE a caller-supplied
        ;; `:rf.cofx` at the PUBLIC dispatch boundary BEFORE the clock
        ;; stamp below — a supplied value must be nil-or-map and a supplied
        ;; `:rf/time-ms` must be an integer (Spec 002 §Recordable coeffects +
        ;; Spec-Schemas.md §:rf.cofx). A malformed causal token is not
        ;; a harmless typo: it folds straight into durable writes (the epoch
        ;; record's `:committed-at`, resource `:settled-at`) and breaks the
        ;; deterministic fold / replay. Always-on (a corrupt durable token is a
        ;; production correctness contract); fails fast WITHOUT reading the
        ;; clock for a dispatch that cannot proceed. The RETIRED DRAFT opts
        ;; `:rf.world/inputs` / `:dispatched-at` earn no dedicated retired-name
        ;; error (they only ever lived in the spec's drafts — the
        ;; shipped-names-only tombstone rule, Conventions §The tombstone rule);
        ;; they fall through to the generic unknown-dispatch-opt warning below,
        ;; which appends a did-you-mean naming the canonical replacement. See
        ;; `diag/validate-cofx!` and `diag/retired-draft-opt-hints`.
         _                  (when (trace/continuation-live?)
                              (try
                                (diag/validate-cofx! opts event)
                                (catch #?(:clj Throwable :cljs :default) e
                                  (when (trace/continuation-live?) (throw e)))))
        ;; EP-0017 §Dispatch Envelope Stamping: the
        ;; CAUSAL BOUNDARY — ensure `:rf.cofx` carries `:rf/time-ms`, the one
        ;; host-clock read durable writes fold. `ensure-cofx` owns the
        ;; preserve-supplied / fill-missing-`:rf/time-ms` shape contract (see the
        ;; section comment on the helper above). Stamped AFTER the cofx
        ;; validation check so an invalid dispatch never reads the clock.
         cofx               (when (trace/continuation-live?)
                              (ensure-cofx (:rf.cofx opts)))
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
        ;; (`dispatch!`, `dispatch-sync!`, the capture-frame ops) funnels
        ;; through here, so this is the single chokepoint for the check. The
        ;; dispatch proceeds unchanged regardless (warn-only).
         _                  (when (trace/continuation-live?)
                              (when-let [unknown (diag/unknown-dispatch-opts opts)]
                                (diag/emit-unknown-dispatch-opts-warning! unknown event)))
        ;; Per rf2-70h9wn (Conventions §Event payloads SHOULD be
        ;; serialisable data): a dev-only advisory walk of the dispatched
        ;; event's payload for a host handle (fn / Promise / AbortController
        ;; / DOM node / Date / RegExp) — the same closed set the reply-map /
        ;; reply-target data-only invariant polices. WARNING, not a throw:
        ;; this is a SHOULD, not the `:rf.cofx` structural-EDN MUST. Dev-only
        ;; — `interop/debug-enabled?` gates BOTH the walk and the emit, so
        ;; production DCEs the whole surface.
         _                  (when (and (trace/continuation-live?)
                                       interop/debug-enabled?)
                              (when-let [bad-path (diag/find-non-serialisable-payload-path event)]
                                (diag/emit-non-serialisable-event-payload-warning! event bad-path)))
        ;; EP-0002 §Dispatch And Router — the carried-invariant envelope
        ;; frame. Resolution order:
        ;;   1. explicit `{:frame …}` opt WINS (override). A caller who
        ;;      named a frame HAS carried a stamp — that stamp is used
        ;;      verbatim, even if it later proves unregistered (a bad
        ;;      explicit target is a `:rf.error/frame-destroyed` registry-
        ;;      lookup failure at the dispatch site, a DIFFERENT category
        ;;      from absence).
        ;;   2. otherwise `frame/require-current-frame!` reads the
        ;;      scope/hold stamp (`with-frame`, or the closest enclosing
        ;;      frame boundary — a `frame-provider` (SCOPE) or a
        ;;      `frame-root` (ENSURE) — via `resolve-current-frame`, or a
        ;;      captured `*current-frame*` binding). When no scope is
        ;;      established and no stamp is
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
        ;; `(rf/dispatch-sync [...] {:frame frame})`). Normalize an object to its
        ;; runnable-id ADDRESS via `frame/frame-target->id` so the envelope
        ;; carries a keyword `:frame` and every bare-`frame-id`-keyed cascade
        ;; operation downstream (the router queue/drain, `frame-state-value`,
        ;; the commit path, the sub-cache) stays byte-identical. The
        ;; generation-resolution seam reads the sealed generation off the record
        ;; by this id, so an object target and a child dispatch carrying the same
        ;; id BOTH route the frame's image. A keyword target (and the scope/hold-resolved frame) passes
        ;; through `frame-target->id` unchanged.
         frame              (when (trace/continuation-live?)
                              (try
                                (frame/frame-target->id
                                  (or (:frame opts)
                                      (frame/require-current-frame!
                                        :dispatch
                                        {:where    're-frame.router/build-envelope
                                         :event-id (first event)})))
                                (catch #?(:clj Throwable :cljs :default) e
                                  (when (trace/continuation-live?) (throw e)))))
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
        step-index         (:step-index opts)
        ;; rf2-dlld6: the EXACT incarnation a `capture-frame` op pinned at
        ;; capture (its `:drain-lock`), threaded by
        ;; `re-frame.core/capture-dispatch!`. A CORRECTNESS lever (it gates
        ;; whether the enqueue may target the resolved incarnation), so it rides
        ;; the envelope unconditionally when present — like `:rf.machine/internal?`
        ;; — never a debug diagnostic. nil for every ordinary / address-directed
        ;; dispatch; the key is then omitted so the hot path stays lean.
        expected-incarnation (:rf.frame/expected-incarnation opts)]
    (when (trace/continuation-live?)
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
      ;; goog.DEBUG=false. Direct HoF fn-form callers supply nil and the
      ;; key is omitted.
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
      step-index         (assoc :step-index step-index)
      ;; rf2-dlld6: carry the captured incarnation token onto the envelope only
      ;; when a `capture-frame` op supplied it, so `dispatch!` / `dispatch-sync!`
      ;; can fence the enqueue to the EXACT incarnation the resolve returns.
      expected-incarnation (assoc :rf.frame/expected-incarnation expected-incarnation))))))

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
  `run-candidate-validation!`'s hook isolation): a throw from the resolver must not abort
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
  `:interceptor-overrides` / `:interceptors` in their `make-frame`
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
  ([chain overrides]
   (apply-icpt-overrides chain overrides (constantly true)))
  ([chain overrides continue?]
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
       ;; Replacement refs may invoke parameterized interceptor factories. Stop
       ;; before/after each such callback once exact ownership is lost.
       (loop [entries (seq chain)
              out     []]
         (cond
           (not (continue?)) nil
           (nil? entries) out
           :else
           (let [entry (first entries)
                 value (try
                         (if-not (map? entry)
                           entry
                           (if-let [k (matching-override-key overrides entry)]
                             (override-replacement k (get overrides k))
                             entry))
                         (catch #?(:clj Throwable :cljs :default) e
                           (if (continue?) (throw e) nil)))]
             (if (continue?)
               (recur (next entries) (cond-> out (some? value) (conj value)))
               nil))))))))

;; ---- rf2-yigokd: envelope override capture for strict replay ---------------
;;
;; Per Spec-Schemas §`:rf/epoch-record` + Tool-Pair §Replay: the epoch record
;; carries the envelope's SERIALIZABLE `:fx-overrides` / `:interceptor-
;; overrides` beside `:rf.cofx`, so a strict replay can re-supply the exact
;; per-call + lexical overrides the original run had active — the same class
;; of fold-changing fact `:rf.cofx` already threads through the run-start
;; trace tag. Scope is PINNED to the envelope-carried keys — per-call +
;; lexical `:fx-overrides` (as merged at `build-envelope` above) and per-call
;; `:interceptor-overrides` — never the per-frame tier (`apply-overrides`'s
;; frame ⋈ call merge), which stays in the replay target's live frame config.

(def ^:private fn-override-sentinel
  "The opaque marker `:rf/fn-override` recorded in place of a CLJS-only
  fn-valued `:fx-overrides` entry (Spec 002 §Per-frame and per-call overrides
  — function values are a CLJS reference convenience, never a pattern-level,
  wire-portable contract). A fn is never EDN, so it never rides the epoch
  record or the trace stream; a Tool-Pair strict replay that finds this
  sentinel on a recorded override FAILS LOUD — the same shape as
  `:rf.error/missing-required-cofx` — rather than silently re-running the
  event without the fn-valued override the original run had active."
  :rf/fn-override)

(defn- serializable-fx-overrides
  "Return the envelope's per-call + lexical `:fx-overrides` map with every
  fn-valued entry replaced by `fn-override-sentinel` — never let a fn ride
  the epoch record / trace stream. Keyword-id and `nil` entries (both already
  EDN — id-valued redirect / explicit no-override) pass through unchanged.
  Marker-izes AT THE EMISSION SITE (the run-start trace tag construction
  below), per rf2-yigokd's ruling — the sharp update-phase class
  (`:interceptor-overrides`, which edits the pre-commit chain) is EDN by
  construction (EP-0022 retired value-valued replacements), so only
  `:fx-overrides` needs this walk.

  Returns nil for an empty/nil map so callers can omit the slot entirely
  (matching the `:rf.event/cofx` `(some? …)`-conditional shape below)."
  [overrides]
  (when (seq overrides)
    (reduce-kv (fn [m k v] (assoc m k (if (fn? v) fn-override-sentinel v)))
               {}
               overrides)))

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
  [event-id event handler-meta frame live?]
  (if interop/debug-enabled?
    ;; Sticky hook — `:schemas/validate-event!` is published
    ;; once at re-frame.schemas load and never withdrawn in dev; fires
    ;; per-dispatch.
    (if-let [validate! (late-bind/get-fn-cached :schemas/validate-event!)]
      (try (validate! event-id event handler-meta frame live?)
           (catch #?(:clj Throwable :cljs :default) _
             (if (live?) true :rf/stale-incarnation)))
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
  [envelope frame frame-record handler-meta fx-overrides continue?]
  (let [event       (:event envelope)
        ;; Read one coherent state value from A's already-captured record. A
        ;; substrate adapter read is callback-bearing, so the caller-provided
        ;; exact predicate is checked before cofx delivery below; the read can
        ;; never redirect into same-id B.
        frame-state (frame/frame-record-state-value frame-record)
        db-value    (get frame-state frame/app-partition-key)
        runtime-db  (get frame-state frame/runtime-partition-key)
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
          (if (and (continue?) (seq requires))
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
              mint-policy continue?)
            (when (continue?)
              {:coeffects base-cofx :rf.cofx (:rf.cofx envelope) :rf/skip-handler? false}))
          ;; The (possibly generation-augmented) record — restamp the
          ;; always-staged `:rf.cofx` coeffect so the canonical context record
          ;; carries every generated fact (EP-0017 §4).
          coeffects (when (continue?)
                      (assoc coeffects :rf.cofx (:rf.cofx delivered)))]
      (when (continue?)
        (cond-> {:coeffects coeffects
                 :effects {}
                 :rf/framework-authority? (events/framework-authority? handler-meta)
                 :rf/fx-overrides fx-overrides}
          skip-handler? (assoc :rf/skip-handler? true))))))

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

(defn- run-candidate-validation!
  "Per Spec 010 §Per-step recovery row 4 (rf2-uhk9ko, Mike-ruled Option
  B): validate the COMPLETE CANDIDATE frame transition BEFORE it is
  installed. `db-after` / `runtime-db-after` are the candidate partition
  values `commit-frame-effects!` computed — the container has NOT been
  written when this runs. Returns the validators' boolean conjunction —
  true when every registered schema for the frame conformed (or the
  schemas artefact isn't loaded / no validator is installed); false when
  at least one entry failed, in which case the caller REJECTS the
  candidate (never calls `commit-frame-transition!`).

  Failures emit :rf.error/schema-validation-failure (one per failing
  entry) with `:rollback? true` and `:recovery :no-recovery` stamped in
  the tag. `:rollback? true` is the public transaction-REJECTED
  vocabulary (the deterministic post-condition — app-db keeps its
  pre-event value); it does not imply a physical write-pair.

  Per Spec 010 §Per-frame schemas the validation walks the schemas
  registered against THIS dispatch's frame only — sibling frames'
  schemas don't fire here. One registry snapshot per candidate:
  `validate-app-schema!` derefs the per-frame schema registry exactly
  once, under the frame's drain (the single-drainer invariant), so a
  concurrent re-registration can never produce a mixed-generation
  validation (pinned by the JVM registry-generation race test).

  Per Spec 010 §Per-step recovery row 7: AND-conjoins the app-db
  validator with `:machines/validate-machine-data!` (the `:where
  :machine-data` boundary). The machine walker iterates
  `[:rf.runtime/machines :snapshots]` in the CANDIDATE runtime-db value
  (EP-0001 — machine snapshots are durable runtime-db state) and
  validates each snapshot's `:data` against the registered machine's
  `[:schemas :data]` schema.

  EP-0001: each validator runs against its OWN partition's candidate
  value — app-db schema validation on `db-after`, machine-data
  validation on `runtime-db-after` — and only when that partition is
  actually written by this transition (`app-effect?` / `rt-effect?`).
  ALL touched partitions validate CONJOINED with no first-failure
  short-circuit (both `let` arms always run): every violation surfaces
  its own trace, and a `false` from either rejects the WHOLE candidate.
  A runtime-only machine commit still gets its `[:schemas :data]`
  boundary, and an app-only commit never pays for a machine-data walk
  over a runtime-db it does not touch.

  Fail CLOSED on a validator-machinery throw (rf2-uhk9ko — the retired
  treat-as-pass arm was a fail-OPEN bypass): a host-thrown validator
  (e.g. a buggy user-supplied :schemas/set-schema-validator! fn, or the
  late-bind machinery failing wholesale) is caught, surfaced as a
  `:rf.error/malformed-schema` trace with `:rollback? true`, and the
  candidate is REJECTED — a throwing validator cannot prove the
  candidate conforms, and the pre-handler frame-state is still the live
  container value so rejecting costs nothing. A MALFORMED REGISTERED
  SCHEMA (childless `[:vector]`, unknown op) does not reach this catch
  at all — `validate-app-schema!` isolates that throw per-entry,
  surfaces its own `:rf.error/malformed-schema` trace, fails CLOSED
  (in-band `false` → reject), and keeps validating the frame's sibling
  schemas."
  [db-after runtime-db-after app-effect? rt-effect? event-id frame owner-token]
  (let [live? #(frame/event-continuation-live? frame owner-token)
        emit-throw-reject!
        ;; Surface a validator-machinery throw AND reject (fail closed).
        ;; DCE-gated inside `trace/emit-error!`.
        (fn [where ex]
          (trace/emit-error!
            :rf.error/malformed-schema
            (cond-> {:where     where
                     :frame     frame
                     :reason    (str "Candidate-transition validator threw; "
                                     "the transition is REJECTED (fail "
                                     "closed, nothing installed): "
                                     #?(:clj  (.getMessage ^Throwable ex)
                                        :cljs (ex-message ex)))
                     :rollback? true
                     :recovery  :no-recovery}
              event-id (assoc :failing-id event-id))))
        run-partition-validator!
        ;; The per-partition validator arm template. Runs the
        ;; late-bound `hook-key` validator against the partition's CANDIDATE
        ;; value ONLY when `effect?` (that partition is written by this
        ;; transition) AND the hook is installed; otherwise → true (an absent
        ;; validator / untouched partition is a pass). Resolves the hook ONCE.
        ;; nil-coerce: a nil return is success so a host returning nil on a
        ;; clean validate keeps working. A host-thrown validator is caught,
        ;; surfaced via `emit-throw-reject!`, and the candidate is REJECTED
        ;; (fail closed — real schema failures route through the in-band
        ;; false; a machinery throw cannot prove conformance either).
        (fn [effect? hook-key partition-value where]
          (if-not (live?)
            ::stale-incarnation
            (if effect?
            (if-let [validate (late-bind/get-fn-cached hook-key)]
              (try
                (let [result (validate partition-value event-id frame live?)]
                  (if-not (live?)
                    ::stale-incarnation
                    (if (nil? result) true result)))
                (catch #?(:clj Throwable :cljs :default) ex
                  (if-not (live?)
                    ::stale-incarnation
                    (do
                      (emit-throw-reject! where ex)
                      (if (live?) false ::stale-incarnation)))))
              true)
              true)))
        ;; App-db schema validation runs only when a `:db` effect produced a
        ;; candidate app-db (app schemas validate app-db only — Mike ruling
        ;; #11). Sticky hook — fires per-dispatch.
        app-ok?
        (run-partition-validator! app-effect? :schemas/validate-app-schema!
                                  db-after :app-db)]
    ;; A validator is authored/callback-bearing.  Loss during app validation
    ;; suppresses machine validation and all later diagnostics.
    (if (= ::stale-incarnation app-ok?)
      ::stale-incarnation
      (let [
        ;; The machine-data boundary (Spec 005 §Schema
        ;; validation). EP-0001: machine snapshots are durable
        ;; runtime-db state, so this validates the CANDIDATE runtime-db value
        ;; and runs only when a `:rf.db/runtime` effect rides this transition.
        ;; The hook is absent when the machines artefact isn't on the
        ;; classpath; absent → true (no machines means no machine-data to
        ;; validate).
        machines-ok?
        (run-partition-validator! rt-effect? :machines/validate-machine-data!
                                  runtime-db-after :machine-data)]
        (if (= ::stale-incarnation machines-ok?)
          ::stale-incarnation
          ;; Both must conform for the candidate to install.  Ordinary
          ;; validation failures still run both partitions; only terminal
          ;; owner loss short-circuits the second callback.
          (and app-ok? machines-ok?))))))

(defn- emit-frame-state-changed!
  "Emit the partition-tagged `:rf.event/frame-state-changed` trace
  (EP-0001 decision #6 / Spec 009 §Canonical per-event trace sequence).
  `changed` is the set of frame-state partition keys that changed by `=`
  (a subset of `#{:rf.db/app :rf.db/runtime}` returned by
  `frame/commit-frame-transition!`); the trace carries
  `:rf.event/partitions` mapped to the tooling-facing tag set
  `#{:app-db :runtime-db}`. Fires only when at least one partition
  changed. Dev-only — `trace/emit!` is internally gated on
  `interop/debug-enabled?`. Phase-less: this fires only for the single
  forward commit (rf2-uhk9ko removed the `:phase :rollback` re-emit —
  a rejected candidate never commits, so there is nothing to re-emit)."
  [event-id emit-event frame changed]
  (when (seq changed)
    (let [tags (cond-> #{}
                 (contains? changed frame/app-partition-key)     (conj :app-db)
                 (contains? changed frame/runtime-partition-key) (conj :runtime-db))]
      (trace/emit! :rf.event :rf.event/frame-state-changed
                   {:rf.trace/event-id     event-id
                    :rf.event/v            emit-event
                    :frame                 frame
                    :rf.event/partitions   tags}))))

(defn- emit-db-event!
  "Emit an APP-DB-partition `:rf.event` change trace (`:rf.event/db-changed`
  or `:rf.event/db-noop`) carrying the standard per-event attribution tag set
  (`:rf.trace/event-id` / `:rf.event/v` / `:frame`). Sibling of
  `emit-frame-state-changed!` for the app-db-scoped change traces, holding
  the shared tag map in one place. Phase-less: these fire only for the
  single forward commit (rf2-uhk9ko removed the `:phase :rollback`
  re-emit — a rejected candidate never commits). Dev-only —
  `trace/emit!` is internally gated on `interop/debug-enabled?`."
  [op event-id emit-event frame]
  (trace/emit! :rf.event op
               {:rf.trace/event-id event-id
                :rf.event/v        emit-event
                :frame             frame}))

(defn- restore-flow-snapshots!
  "Roll the flow dirty-check (`last-inputs`) rows and the in-drain
  abandoned-output-path queue BACK to the pre-transform snapshots the
  outermost flows `:after` stashed on `ctx`, for an event that is NOT going to
  commit (rf2-1b8yxb). The flow transform advances a recomputed flow's row and
  read-and-clears the queued vacations EAGERLY, folding the result into the
  pending `:db`; whether that `:db` lands is decided AFTER the chain, so every
  NON-COMMIT outcome must undo those eager side effects in lock-step with the
  discarded `:db` — else a fresh flow's output never re-materialises
  (`last-inputs` stuck advanced past a write that never landed, Spec 013
  §Failure semantics rule 2) and a queued vacation is lost (§clear-flow
  cleanup). Shared by every in-band non-commit arm in `commit-and-flow!` —
  the schema-validation candidate REJECTION (rf2-uhk9ko: the only state the
  rejection restores is this transient flow bookkeeping; the container was
  never written) and the pre-commit aborts (legacy-runtime-root /
  classification-effect-shape) — the boundaries `run-flows-on-db`'s own
  throw arm cannot reach. Frame-scoped and idempotent: a no-op when no flow
  ran (the snapshot keys are absent) or the flows artefact never loaded (the
  restore hooks are nil)."
  [ctx frame owner-token]
  (when (and (frame/event-continuation-live? frame owner-token)
             (contains? ctx :rf/flow-last-inputs-before))
    (when-let [restore-li (late-bind/get-fn-cached :flows/restore-last-inputs!)]
      (call-while-exact-owner
        frame owner-token
        #(restore-li frame owner-token (:rf/flow-last-inputs-before ctx)))))
  (when (and (frame/event-continuation-live? frame owner-token)
             (contains? ctx :rf/flow-abandoned-paths-before))
    (when-let [restore-ap (late-bind/get-fn-cached :flows/restore-abandoned-paths!)]
      (call-while-exact-owner
        frame owner-token
        #(restore-ap frame owner-token
                     (:rf/flow-abandoned-paths-before ctx))))))

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

  PREPARE → VALIDATE → INSTALL (rf2-uhk9ko, Mike-ruled Option B). The fn
  first BUILDS the complete candidate transition — the nil-coerced
  flow-augmented app-db, the reconciled `:rf.db/runtime` value with any
  commit-plane classification effects applied — WITHOUT mutating the
  container; then runs `run-candidate-validation!` over the candidate
  partition values; and only on a full pass performs the single
  `commit-frame-transition!` install. Validators are pure fns over
  already-computed values, so pre-validation costs nothing on the success
  path and the failure path performs ZERO container writes.

  Returns true when the transition installed (or there was no partition
  effect to install); false when candidate validation REJECTED the
  transition (per Spec 010 §Per-step recovery row 4): the candidate is
  discarded, `commit-frame-transition!` is NEVER called, the frame-state
  keeps its pre-handler value by construction (nothing to restore), and NO
  change trace fires — no `:rf.event/db-changed`, no `:rf.event/db-noop`,
  no `:rf.event/frame-state-changed`, no `:rf.trace/phase :rollback`
  re-emit. The only rejection-path emissions are the validation
  diagnostics themselves (`:rf.error/schema-validation-failure` /
  `:rf.error/malformed-schema`, stamped `:rollback? true` = transaction
  REJECTED). Synchronous observers — trace listeners, container watches,
  substrate epoch drains, `useSyncExternalStore` subscribers — can never
  observe the invalid candidate: it never reaches the container. App-db
  schema validation is APP-DB-ONLY (app schemas validate the app
  partition, Mike ruling #11); a rejection discards the WHOLE candidate
  (both partitions, including any classification-effect registry write) so
  the frame stays coherently at its pre-handler state.

  Change traces on the SUCCESS path (per Spec 009 §Canonical per-event
  trace sequence):
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
  frame-state-changed for the app-db partition). Success is exactly ONE
  commit and at most one forward `db-changed`; there is no post-commit
  validation pass.

  nil-coercion: a `:db nil` effect is coerced to `{}` HERE —
  at the `:db` effect → `:rf.db/app` partition mapping, as the candidate is
  built — so the partition layer never sees a nil app-db (app-db is always
  a map). The coercion emits a dev-mode `:rf.warning/db-nil-coerced`
  diagnostic for accidental-wipe visibility; a deliberate clear
  (`{:db {}}`) does not.

  Per Spec 013 §Drain integration: `(:db effects)` here is the
  FLOW-AUGMENTED app-db value — the OUTERMOST flows-after-interceptor has
  already rewritten the pending `:db` effect by the time the chain returns.
  So the candidate validates (and `:rf.event/db-changed` reflects) the
  flow-derived db, and the change trace fires AFTER `:rf.flow/computed`
  (per Spec 009 §Canonical per-event trace sequence).

  Schema-derived redaction is reflected in the change traces' `:tags :event`
  slot via `privacy/redacted-event-from-ctx`.

  On rejection the caller (`commit-and-flow!`) restores ONLY the transient
  flow bookkeeping (`restore-flow-snapshots!` — the flow transform advanced
  each computed flow's dirty-check row inside the chain for a write that
  will now never land) and reports the `:rolled-back` outcome; the durable
  frame-state needs no restore because it was never touched."
  [effects event-id event frame frame-record owner-token ctx]
  (if-not (frame/event-continuation-live? frame owner-token)
    ::stale-incarnation
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
    (if-not (frame/event-continuation-live? frame owner-token)
      ::stale-incarnation
      (if (or app-effect? rt-effect? class-effect?)
      (let [emit-event (privacy/redacted-event-from-ctx ctx)
            ;; A whole-value `:rf.db/runtime` effect REPLACES the
            ;; runtime-db partition (decision #5), but the elision declaration
            ;; registry at `[:rf.runtime/elision]` is a CROSS-CUTTING durable
            ;; subsystem child written OUT-OF-BAND by `reg-flow` outputs /
            ;; the EP-0025 classification effects / machine + route subsystem
            ;; declarations — not by the event returning the effect. An
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
            ;; snapshot. Under the post-EP-0025 model flows install their output
            ;; declarations at `reg-flow` REGISTRATION time, not during the
            ;; `:after` drain (the drain-time `refresh-flow-output-declarations!`
            ;; propagation engine the old rationale cited was REMOVED by
            ;; EP-0025 — there is no input→output sensitivity propagation). But
            ;; an out-of-band subsystem write to `[:rf.runtime/elision]` can
            ;; STILL land DURING this cascade, AFTER `runtime-before` was
            ;; captured by reference in `assemble-initial-ctx`: a handler (or an
            ;; `:after` interceptor) may call `reg-flow` reentrantly to MOVE an
            ;; output-path (rewriting the live registry's flow marks), or a
            ;; machine actor spawn / route activation may lower a subsystem mark
            ;; mid-cascade. When the handler ALSO returns a `:rf.db/runtime`
            ;; effect, reconciling against the STALE chain-start snapshot would
            ;; carry the PRE-cascade registry forward and the commit would
            ;; overwrite those just-written out-of-band marks — so the path
            ;; egresses RAW for one commit (until the subsystem re-asserts it).
            ;; Reading the live runtime-db here picks up the freshest
            ;; out-of-band marks as the reconcile / classification base. (The
            ;; whole-frame-install / deliberate-clear path is unaffected — an
            ;; effect that carries `:rf.runtime/elision` is still honoured
            ;; verbatim.) Read the LIVE runtime-db whenever a runtime-db
            ;; OR a classification effect commits — both need the freshest
            ;; out-of-band registry as their base. (rf2-uhk9ko: there is no
            ;; rollback restore any more — a rejected candidate is simply
            ;; discarded before any write, so no pre-cascade elision
            ;; overlay is needed.)
            live-runtime-result
            (when (or rt-effect? class-effect?)
              (call-while-exact-owner
                frame owner-token
                #(frame/frame-record-state-value frame-record)))
            live-runtime-db
            (when-not (= ::stale-incarnation live-runtime-result)
              (get live-runtime-result frame/runtime-partition-key))
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
                         rt-partition? (assoc frame/runtime-partition-key new-runtime-db))]
        ;; VALIDATE the complete candidate BEFORE install (rf2-uhk9ko,
        ;; Spec 010 §Per-step recovery row 4). Per-partition (EP-0001):
        ;; app-db schema validation on the candidate app-db (only when a
        ;; `:db` effect rides — app schemas validate app-db only, Mike
        ;; ruling #11) AND the machine-data `:where :machine-data` boundary
        ;; on the candidate runtime-db (only when a `:rf.db/runtime` effect
        ;; rides — machine snapshots are durable runtime-db state). A
        ;; `false` from either REJECTS the whole candidate: nothing is
        ;; installed, no change trace fires, and the container keeps its
        ;; pre-handler value by construction.
        (if (= ::stale-incarnation live-runtime-result)
          ::stale-incarnation
          (let [validation-result
                (run-candidate-validation!
                  new-db new-runtime-db app-effect? rt-effect? event-id frame
                  owner-token)]
          (cond
            (= ::stale-incarnation validation-result)
            ::stale-incarnation

            (not validation-result)
            ;; REJECTED before install; diagnostics already fired.
            false

            :else
            ;; ONE atomic frame-state install through A's captured container.
            (let [changed (frame/commit-frame-transition!
                            frame owner-token partitions)]
              (if (nil? changed)
                ::stale-incarnation
                (let [app-changed?
                      (contains? changed frame/app-partition-key)]
                  ;; Each trace emit is a synchronous listener boundary.  Once
                  ;; one destroys A, no subsequent commit evidence may describe
                  ;; same-id B and the router receives terminal stale.
                  (when app-changed?
                    (emit-db-event!
                      :rf.event/db-changed event-id emit-event frame))
                  (if-not (frame/event-continuation-live? frame owner-token)
                    ::stale-incarnation
                    (do
                      (when (and app-effect? (not app-changed?))
                        (emit-db-event!
                          :rf.event/db-noop event-id emit-event frame))
                      (if-not (frame/event-continuation-live? frame owner-token)
                        ::stale-incarnation
                        (do
                          (emit-frame-state-changed!
                            event-id emit-event frame changed)
                          (if (frame/event-continuation-live?
                                frame owner-token)
                            true
                             ::stale-incarnation))))))))))))
        true)))))

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
      ;; Chain-error short-circuit (rf2-1b8yxb): `execute-chain` runs the
      ;; FULL `:after` pass for teardown even after a `:before` / handler /
      ;; cofx / user-`:after` throw was captured into `:rf/interceptor-error`
      ;; (interceptor.cljc). This framework-OUTERMOST flows `:after` is the
      ;; LAST to fire, so without this guard it would run the flow transform
      ;; against a DOOMED pending db — eagerly advancing each recomputed
      ;; flow's dirty-check (`last-inputs`) row (flows.cljc) and read-and-
      ;; clearing the queued in-drain output-path vacations (registry.cljc) —
      ;; even though `commit-and-flow!` is about to abort THIS event
      ;; (`:error`) WITHOUT committing. That poisons the dirty-check (a fresh
      ;; flow's output never re-materialises), loses a queued vacation, and
      ;; emits spurious `:rf.flow/computed` / t2 traces on an aborted event
      ;; (Spec 013 §Failure semantics rule 2 / §clear-flow cleanup). When the
      ;; chain already errored the event is over: return `ctx` untouched — no
      ;; flow transform, no `last-inputs` advance, no vacation drain, no
      ;; t1/t2. (A flow-eval throw is a DISTINCT category stashed under
      ;; `:rf/flow-error`, NOT `:rf/interceptor-error`, so the flow-throw
      ;; atomicity path below is unaffected.)
      (if (:rf/interceptor-error ctx)
        ctx
        (let [frame       (:rf.frame/id (:coeffects ctx))
              owner-token (frame/current-event-owner-token)]
          ;; The user handler may synchronously destroy A, pause after registry
          ;; dissoc, and allow same-id B to publish before it returns. Flows are
          ;; the first framework-owned tail stage after the handler/user-after
          ;; chain. Fence here before any bare-id flow reads, side-table writes,
          ;; or pending-db trace can be attributed to B.
          (if-not (frame/event-continuation-live? frame owner-token)
            (assoc ctx :rf/stale-incarnation? true)
            (let [
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
            ;; The chain-start coeffects were read from A's captured record.
            ;; When the handler returned no partition effect, those values are
            ;; the pending transition inputs; never re-resolve the bare id here
            ;; and accidentally read same-id B.
            pending-db  (if has-db?
                          (:db effects)
                          (when run-on-db (-> ctx :coeffects :db)))
            ;; The pending runtime-db partition the flows read their qualified
            ;; inputs against: the handler's `:rf.db/runtime` effect when one
            ;; landed, else the current (unchanged) runtime-db. Only resolved
            ;; when the flows artefact is loaded — apps without flows never
            ;; touch it. Flow outputs write app-db only (runtime writes
            ;; reserved, §539), so this value is read-only for the whole pass.
            pending-runtime-db (when run-on-db
                                 (if has-runtime-effect?
                                   (:rf.db/runtime effects)
                                   (-> ctx :coeffects :rf.db/runtime)))
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
        ;; Trace listeners are synchronous callback boundaries.  A listener on
        ;; t1 may destroy A; do not enter the optional flow artefact afterward.
        (if-not (frame/event-continuation-live? frame owner-token)
          (assoc ctx :rf/stale-incarnation? true)
          (if run-on-db
          (try
            (let [;; Snapshot THIS frame's
                  ;; dirty-check (`last-inputs`) rows BEFORE the flow transform
                  ;; advances them. The transform eagerly advances a flow's row
                  ;; the moment it recomputes, folding the output into the
                  ;; pending `:db`. But whether that pending `:db` becomes
                  ;; DURABLE is decided AFTER the chain: candidate schema /
                  ;; machine-data validation (rf2-uhk9ko) can REJECT the
                  ;; transition pre-install. `run-flows-on-db`'s own
                  ;; throw-path snapshot/restore cannot cover that — the
                  ;; rejection lands outside it. Without restoring here, the
                  ;; advanced rows survive a rejection, so the next clean drain
                  ;; sees `=`-equal inputs, SKIPS the flow, and the output
                  ;; never re-materialises (a deterministic dev/test failure
                  ;; can permanently suppress a flow). We stash the pre-drain
                  ;; snapshot on the ctx; `commit-and-flow!` restores it iff the
                  ;; candidate is rejected — the exact mirror of the throw-path
                  ;; restore, at the commit boundary. Frame-scoped: the
                  ;; snapshot is `frame`'s own container, structurally unable to
                  ;; touch a sibling frame draining on another thread. The
                  ;; snapshot is a persistent map (pointer-sized to stash); the
                  ;; hook is nil only when the flows artefact never loaded, in
                  ;; which case there are no rows and nothing to restore.
                   snapshot-li (late-bind/get-fn-cached :flows/snapshot-last-inputs)
                   li-before   (when snapshot-li
                                 (call-while-exact-owner
                                   frame owner-token
                                   #(snapshot-li frame owner-token)))
                  ;; Snapshot the frame's pending abandoned-output-
                  ;; paths BEFORE the transform (it DRAINS/clears them and
                  ;; dissocs them from the pending `:db`). On a candidate
                  ;; rejection `commit-and-flow!` re-records this snapshot —
                  ;; the exact mirror of the `last-inputs` snapshot above, for
                  ;; the boundary `run-flows-on-db`'s own throw arm cannot see.
                   snapshot-ap (late-bind/get-fn-cached :flows/snapshot-abandoned-paths)
                   ap-before   (when snapshot-ap
                                 (call-while-exact-owner
                                   frame owner-token
                                   #(snapshot-ap frame owner-token)))
                  ;; EP-0001 §535-551: hand the flow transform
                  ;; BOTH partitions of the pending frame-state. Bare `:inputs`
                  ;; resolve against `pending-db` (app-db); `[:rf.db/runtime …]`
                  ;; inputs resolve against `pending-runtime-db`. The returned
                  ;; value is the flow-augmented APP-DB (runtime-db is read-only
                  ;; for the pass).
                   new-db (call-while-exact-owner
                            frame owner-token
                            #(run-on-db frame pending-db pending-runtime-db
                                        {:exact-owner-token owner-token}))]
               (if (or (= ::stale-incarnation li-before)
                       (= ::stale-incarnation ap-before)
                       (= ::stale-incarnation new-db)
                       (= :rf.flow/stale-incarnation new-db)
                      (not (frame/event-continuation-live?
                             frame owner-token)))
                (assoc ctx :rf/stale-incarnation? true)
                (do
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
              (if-not (frame/event-continuation-live? frame owner-token)
                (assoc ctx :rf/stale-incarnation? true)
                ;; Only publish a `:db` effect when flows actually changed
                ;; the value OR the handler already had one.
                (if (or has-db? (not (identical? new-db pending-db)))
                  (cond-> (interceptor/assoc-effect ctx :db new-db)
                    snapshot-li (assoc :rf/flow-last-inputs-before li-before)
                    snapshot-ap (assoc :rf/flow-abandoned-paths-before ap-before))
                  ctx)))))
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
              (if-not (frame/event-continuation-live? frame owner-token)
                (assoc ctx :rf/stale-incarnation? true)
                (-> (update ctx :effects dissoc :db)
                    (assoc :rf/flow-error e)))))
          ;; No flows artefact loaded — short-circuit (steady state for
          ;; apps that never registered any flow). t1 above already fired
          ;; when the handler returned `:db`; t2 is by definition
          ;; impossible here (no flow could have transformed the value).
              ctx)))))))))

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
  (`emit-pipeline-exception!`).

  Per Spec 009 §Error event catalogue + Spec 013 §Trace stream ordering the
  always-on record MUST carry `:where :flow-eval` and the failing `:flow-id` —
  the prod-surviving attribution. Both ride the `record-attrs` (axis-1) map so
  they survive an egress profile that drops `:exception` (rf2-z1332c);
  previously the failing flow id lived ONLY inside the thrown ex-data
  (`:rf.flow/failed-id`), unreachable once `:exception` is stripped. The id is
  read back off that ex-data (nil-safe: absent for a non-per-flow throw, in
  which case only `:where` is stamped)."
  [e event event-id frame start-ms]
  (let [end-ms     (interop/now-ms)
        elapsed-ms (elapsed-ms-from start-ms end-ms)
        failed-id  (:rf.flow/failed-id (ex-data e))]
    ;; Fan out along BOTH channels (shared helper). Axis 1 — the
    ;; always-on corpus-wide listener fires in CLJS production where
    ;; the trace surface (axis 2) is compile-time elided.
    (error-emit/emit-error-both!
      :rf.error/flow-eval-exception
      event event-id frame e elapsed-ms end-ms
      {:frame frame :event event :exception e}
      ;; axis-1 attribution that survives an `:exception`-dropping egress
      (cond-> {:where :flow-eval}
        (some? failed-id) (assoc :flow-id failed-id)))))

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
  (`{:offending-key … :value … :reason …}`).

  ## Which key, not merely that one was malformed (rf2-eg61l)

  `:offending-key` is lifted onto the ALWAYS-ON record through
  `emit-error-both!`'s trailing `record-attrs` map — the seam the flow-eval
  category already uses for `{:flow-id … :where :flow-eval}`, so no other
  category's record widens and the shared `:failing-id` lift rule is untouched.
  Without it a production build heard only that SOME classification payload
  aborted an event: the four axes are independently malformable and
  `:offending-key` is the ONLY discriminator between them (rf2-mz582u added it
  for exactly that), and it rode the DCE'd dev trace alone. An off-box shipper
  (Sentry / Datadog) had an error with no route to the cause.

  ## Why the KEY egresses and the VALUE does not

  This record is production-surviving and NOT privacy-gated, so every slot on
  it is an egress decision. `:offending-key` is PROGRAM STRUCTURE with a CLOSED
  domain: `classification-effect-defect` stamps it by iterating
  `elision/classification-effect-keys`, so its value is always one of the four
  framework-owned keywords `:sensitive` / `:large` / `:clear-sensitive` /
  `:clear-large`. It is not application-authored, not derived from the payload,
  and cannot be widened by a caller — it carries two bits of \"which axis\",
  nothing more.

  `:value` is the REJECTED PAYLOAD itself — handler- or `:after`-interceptor-
  authored, and on a fail-loud path by definition not what the framework
  expected — so it stays on the dev trace, which DCEs. `:reason` stays with it:
  it is prose that INTERPOLATES that payload through `pr-str`, so shipping it
  would ship the value by the back door. Note that the trace tags deliberately
  carry NO `:failing-id`: adding one would trip `emit-error-both!`'s shared lift
  and drag the interpolating `:reason` onto the always-on record. The closed key
  set of the record that egresses is pinned by
  `re-frame.classification-effect-shape-record-cljs-test`."
  [defect event event-id frame start-ms]
  ;; Build the discriminator ONCE, before either axis sees it — the two axes
  ;; must never be able to disagree about which key was at fault.
  (let [end-ms        (interop/now-ms)
        elapsed-ms    (elapsed-ms-from start-ms end-ms)
        offending-key (:offending-key defect)]
    (error-emit/emit-error-both!
      :rf.error/classification-effect-shape
      event event-id frame nil elapsed-ms end-ms
      ;; Axis 2 — the dev trace. Carries the full diagnosis, DCE'd in prod.
      {:frame             frame
       :rf.trace/event-id event-id
       :rf.event/v        event
       :offending-key     offending-key
       :value             (:value defect)
       :recovery          :fix-effect
       :reason            (:reason defect)}
      ;; Axis 1 — the always-on record. The bounded structural discriminator
      ;; ONLY; see §Why the KEY egresses and the VALUE does not above.
      {:offending-key offending-key})))

(defn- emit-effect-map-shape!
  "Surface `:rf.error/effect-map-shape` (rf2-04tx) through BOTH the always-on
  error-emit substrate AND the dev-only trace surface — the FINAL-effects
  boundary REFUSAL of a malformed effect-map ENVELOPE: a foreign top-level key
  (case a) or a non-sequential `:fx` value (case b).

  Like `emit-legacy-runtime-root!` / `emit-classification-effect-shape!`, this
  is an IN-BAND rejection, not a throw: the FINAL effects map may carry the
  offending key from a handler return OR from an `:after` interceptor, and
  throwing here would escape into `drain-emergency-release!` and abandon the
  rest of the queue. So we emit in-band and abort THIS event only (`:error`
  outcome, NO `:db` / `:rf.db/runtime` / classification commit, NO `:fx`),
  preserving the no-partial-commit promise while keeping the drain alive.

  `defect` is the `re-frame.events/effect-map-defect` map
  (`{:offending-key … :value … :reason …}`).

  ## Why this refuses rather than dropping the key

  The runtime RECOGNISES the key and declines to honour it, which
  Conventions §No silent swallow makes a MUST-signal. Dropping it committed
  the `:db` write while the effect never ran — the partial-success disguise
  that hid a dead `:dispatch-later` timer behind three green engines and an
  eleven-week-dead persist fx inside Xray. Uniform in every build and not
  configurable: erasing the abort in production would make dev abort what
  production commits.

  ## Egress

  `:offending-key` is the ONE slot lifted onto the always-on record, via the
  trailing `record-attrs` map — the seam `:rf.error/classification-effect-shape`
  already uses. Unlike that category's framework-owned four-keyword domain this
  key is APP-AUTHORED, but it is still PROGRAM STRUCTURE (a keyword the
  programmer typed in their own source), not a runtime value; the precedent is
  `:rf.error/override-fallthrough`, which egresses app-authored fx-ids. Without
  it a production build heard only that SOME effect key aborted an event, with
  no route to which one. The rejected `:value` — the payload the handler built —
  and the `:reason` that interpolates the offending key into prose stay on the
  DCE'd dev trace. `:failing-id` rides the trace tags and is EQUAL to
  `:event-id` here, so `emit-error-both!`'s lift does not fire and cannot drag
  `:reason` onto the record (rf2-eg61l). The closed key set of the record that
  egresses is pinned by `re-frame.effect-map-shape-record-cljs-test`."
  [defect event event-id frame start-ms]
  ;; Build the discriminator ONCE, before either axis sees it — the two axes
  ;; must never be able to disagree about which key was at fault.
  (let [end-ms        (interop/now-ms)
        elapsed-ms    (elapsed-ms-from start-ms end-ms)
        offending-key (:offending-key defect)]
    (error-emit/emit-error-both!
      :rf.error/effect-map-shape
      event event-id frame nil elapsed-ms end-ms
      ;; Axis 2 — the dev trace. Carries the full diagnosis, DCE'd in prod.
      {:failing-id        event-id
       :rf.trace/event-id event-id
       :rf.event/v        event
       :offending-key     offending-key
       :value             (:value defect)
       :recovery          :fix-effect
       :reason            (:reason defect)}
      ;; Axis 1 — the always-on record. The structural discriminator ONLY;
      ;; see §Egress above.
      {:offending-key offending-key})))

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
  `do-fx` (the `:effects` opt) so the terminating `:rf.fx/do-fx` trace
  marker can stamp `:fx` (the returned vector) and `:db-present?`
  (whether the handler returned a `:db` slot). The value of `:db`
  is NOT stamped — App-db diff traces already carry slice changes.

  Per rf2-9dk9y: the user-injected coeffects projection moved OFF the
  `:rf.fx/do-fx` marker and ONTO `:rf.event/run-end` (see
  `emit-pipeline-trailers!`). The prior placement silently dropped the
  COEFFECTS row whenever a handler returned only `:db` (no `:fx`) — the
  fx walk was short-circuited so the marker never emitted. Pinning the
  cofx stamp to the always-fires run-end emit makes the COEFFECTS
  section render uniformly across event flavours.

  Per rf2-ee38b.1: the former positional do-fx arity ladder collapsed
  into a single `opts` map — this is the sole caller threading the full
  set of optionals."
  [effects frame frame-record owner-token fx-overrides envelope]
  (if-let [fx-vec (:fx effects)]
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
                            (fx/strip-rejected-overrides
                              fx-overrides frame event
                              #(frame/event-continuation-live?
                                 frame owner-token)))]
      (if-not (frame/event-continuation-live? frame owner-token)
        ::stale-incarnation
        (let [result (fx/do-fx
                       frame fx-vec active-platform
                       {:overrides                 fx-overrides
                        :origin-event              event
                        :parent-envelope           envelope
                        :frame-incarnation-token   owner-token
                        :effects                   effects})]
          (if (= :re-frame.fx/stale-incarnation result)
            ::stale-incarnation
            :ok))))
    :ok))

;; ---- process-event* phases ------------------------------------------------
;;
;; `process-event*` decomposes into named phases per audit RT1 (rf2-mccjv).
;; Each phase owns one piece of the per-event pipeline run; the outer
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
;;                               (:ok / :error / :rolled-back / :flow-error /
;;                               :rejected) for the event-emit record
;;   emit-pipeline-trailers!      :run-end trace + always-on event-emit fan-out
;;   run-handler-pipeline!        sequence prepare → run → commit → trailers
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
  non-recovery sites through one helper keeps the gating uniform.

  `op` (rf2-7xlvt) is the ALREADY-KNOWN operation realm of the failing op
  — `:dispatch` / `:dispatch-sync` / `:subscribe`. It rides the always-on
  record's `:op` attribution slot so `error-emit/error-source-coord` resolves
  the source-coord under the EXACT realm (`[:event id]` for a dispatch /
  dispatch-sync, `[:sub id]` for a subscribe) rather than the realm-ambiguous
  `[:sub]`-then-`[:event]` fallback. `:op` is RATIFIED PUBLIC on the record
  wherever the realm is known (rf2-a2x2w — resolving the rf2-1kph4/#6194
  record-shape contradiction: `:op` is a small closed-enum realm attribution,
  useful to an off-box shipper and consistent with the UI throwing `(frame)`
  surface that already carries it — see Spec 009 §Error contract, the
  `:rf.error/frame-destroyed` row); it is NOT a hidden slot. The bare router
  drain / no-such-frame emitters carry NO realm (the 3-arity → nil `op`), so
  they keep the fallback and the tight record shape — unchanged. Callers that
  KNOW the realm and pass it: the `capture-frame` stale-op PRE-CHECK seam
  (`emit-captured-frame-superseded!`), AND — rf2-a2x2w — the router's LATE
  captured-op fences (the A→B incarnation mismatch + the post-token-match /
  pre-enqueue / pre-drain-acquire window in `dispatch!` / `dispatch-sync!`).

  A present `op` ALSO suppresses the EP-0015 frame-owned sink route (rf2-qjfrw,
  the capture-realm extension of the rf2-bf0io UI seam): every op-bearing caller
  is a KNOWN-DEAD captured incarnation whose bare `frame-id` may now name a live
  same-id SUCCESSOR, so routing to it would leak a dead incarnation's failure
  into the successor's own sink. The corpus fan-out + dev trace still fire; only
  the frame-owned route is dropped. Nil `op` (address-directed) keeps its route."
  ([event-id event frame-id]
   (emit-frame-destroyed! event-id event frame-id nil))
  ([event-id event frame-id op]
   ;; Fan out along BOTH channels (rf2-c4oycd shared helper). Axis 1 — the
   ;; always-on listener (survives prod elision); axis 2 — the dev trace (DCE'd
   ;; under `:advanced` + `goog.DEBUG=false`). No exception — invalid op, not a
   ;; throw; `elapsed-ms 0` (not a timed path). When `op` is present it rides
   ;; BOTH the dev-trace tags (axis 2) and the always-on record-attrs (axis 1
   ;; — the ratified-public `:op` realm attribution, which also steers source-
   ;; coord); nil `op` leaves the record shape exactly as the bare-drain
   ;; callers have always emitted it (no `:op` key).
   ;;
   ;; rf2-qjfrw: `route-frame?` false SUPPRESSES ONLY the EP-0015 frame-owned
   ;; sink route (the rf2-bf0io seam, extended from the UI `(frame)` bundle to
   ;; the core `capture-frame` primitive) whenever `op` is present. A present
   ;; `op` marks a CAPTURED-op rejection — the pre-check seam
   ;; (`emit-captured-frame-superseded!`) and the router's late captured-op
   ;; fences (the A→B incarnation mismatch + the post-token-match windows in
   ;; `dispatch!` / `dispatch-sync!`). Every one of those is a KNOWN-DEAD
   ;; captured incarnation whose bare `frame-id` no longer names the incarnation
   ;; the failure belongs to: a same-id destroy→reincarnation may have reseated
   ;; a live SUCCESSOR B under it, and resolving the bare id to B would deliver a
   ;; dead incarnation's failure into B's OWN `:observability :errors` sink
   ;; (frame isolation; exact-incarnation attribution). The corpus fan-out
   ;; (axis 1 listener) and the dev trace (axis 2) still fire exactly once; only
   ;; the frame-owned route is dropped. A nil `op` (the ordinary
   ;; address-directed router drain / no-such-frame emit) keeps the default
   ;; route — an ordinary frame-destroyed's bare id is address-directed, so it
   ;; stays route-eligible.
   (error-emit/emit-error-both!
     :rf.error/frame-destroyed
     event event-id frame-id nil 0 (interop/now-ms)
     (cond-> {:frame frame-id :event event :reason :frame-destroyed}
       op (assoc :op op))
     (when op {:op op})
     (nil? op))))

(defn- handle-frame-destroyed!
  "Per Spec 002 §Run-to-completion: a frame disposed between enqueue and
  dispatch surfaces as `:rf.error/frame-destroyed`; the drain continues
  with the next envelope. Per rf2-2hvga the emit is production-survivable
  (see [[emit-frame-destroyed!]])."
  [event frame]
  (emit-frame-destroyed! (first event) event frame))

(defn ^:no-doc emit-captured-frame-superseded!
  "rf2-9pyles — the recover-but-emit seam for a `capture-frame` op whose CAPTURED
  frame incarnation has been SUPERSEDED. A frame api built by
  `re-frame.core/make-capture-frame` pins the EXACT incarnation live at capture
  (its `:drain-lock`); if that incarnation is later destroyed — whether the id is
  now unclaimed OR a same-id successor incarnation B has reseated under it — the
  captured op must NOT leak into B. It RECOVERS (the event is never enqueued into
  the successor) and emits the production-survivable `:rf.error/frame-destroyed`,
  exactly like a dispatch into a destroyed frame (the rf2-2hvga = recover-but-emit
  ruling): the runtime cannot distinguish a benign teardown/hot-reload race from a
  real use-after-destroy bug, so it recovers AND stays observable.

  `opts` carries the dev-only `:rf.trace/call-site` (the capture's dispatch coord),
  bound around the emit so the trace attributes the drop to the dispatch site.
  Called UNIFORMLY from `make-capture-frame`'s incarnation-fenced `:dispatch`,
  `:dispatch-sync` (rf2-9pyles) AND `:subscribe` (rf2-tdjv7p, #6084) ops when the
  synchronous `capture-target-superseded?` pre-check already sees the pin gone;
  a superseded `:subscribe` additionally returns nil rather than a reaction.

  `op` (rf2-7xlvt) is the ALREADY-KNOWN operation realm of the failing captured
  op — `:dispatch` / `:dispatch-sync` (from `capture-dispatch!`) or `:subscribe`
  (from `capture-subscribe!`). It is carried through `emit-frame-destroyed!` onto
  the always-on record's `:op` realm attribution slot (rf2-a2x2w: ratified
  public, and also steers) so `error-emit/error-source-coord` resolves the
  source-coord under the EXACT realm — `[:event id]` for a dispatch, `[:sub id]`
  for a subscribe — never the realm-ambiguous
  `[:sub]`-then-`[:event]` fallback that (before rf2-7xlvt) misattributed a stale
  captured dispatch to a same-keyword subscription's coord and a stale captured
  subscribe to an unrelated event's coord instead of OMITTING it. This seam KNOWS
  the realm; the bare router drain emitters (which genuinely do not) keep the
  fallback.

  rf2-dlld6 closes the concurrent-JVM window BETWEEN that pre-check and the
  ordinary bare-id target consumption: `capture-dispatch!` / the `:subscribe`
  thunk carry the pinned incarnation through as `:rf.frame/expected-incarnation`,
  so `dispatch!` / `dispatch-sync!` (and `subscribe-in-frame`) validate it
  against the SAME record they resolve for enqueue/read and emit the SAME
  production-survivable `:rf.error/frame-destroyed` exactly once — never a
  liveness-check-to-bare-id-use window, never a leak into a same-id successor.
  The bare-id `frame-destroyed` emit `dispatch!` / `dispatch-sync!` already raise
  for a fully-unclaimed id is unchanged (an unpinned 1-arity capture stays
  address-directed)."
  [event frame-id op opts]
  (trace/with-call-site (when interop/debug-enabled? (:rf.trace/call-site opts))
    (emit-frame-destroyed! (first event) event frame-id op))
  nil)

;; EP-0015 §8 (rf2-d2r3um): the former per-dispatch
;; `refresh-elision-from-schemas!` is removed — schemas no longer feed the
;; app-db egress registry. Durable app-db classification is frame-owned and
;; installed once at construction time (`re-frame.frame-classification`), so
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
  (let [owner-token (frame/current-event-owner-token)
        live?       #(frame/event-continuation-live? frame owner-token)
        {:keys [extra-interceptors fx-overrides icpt-overrides]}
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
        resolved-chain  (when (live?)
                          (if (icpt-reg/chain-needs-resolution? prepended-chain)
                            (icpt-reg/resolve-chain prepended-chain live?)
                            prepended-chain))
        base-chain      (when (and (live?) (some? resolved-chain))
                          (apply-icpt-overrides resolved-chain icpt-overrides live?))
        ;; rf2-ivr38u — fused single-pass collection of the frame-declared
        ;; sensitive-path overlap (`:schema-paths`) AND the user-installed
        ;; `(rf/redact-interceptor paths)` paths (`:user-paths`) over the
        ;; SAME `base-chain`, replacing the prior two independent chain
        ;; walks. Per rf2-461sp — user-installed redact interceptors expose
        ;; their paths on the interceptor map so the pre-chain trace
        ;; projection (`:run-start`, `emit-pipeline-trailers`) honours them
        ;; too. Each user `:before` ALSO runs during chain execution and
        ;; extends `:rf/redacted-event` in-chain, which is what the schema-
        ;; redaction interceptor (when also installed) composes with. The
        ;; union here is the OUT-OF-CHAIN projection used by emit sites that
        ;; fire BEFORE the chain.
        redaction-result (when (and (live?) (some? base-chain))
                           (call-while-exact-owner
                             frame owner-token
                             #(privacy/collect-redaction-paths frame base-chain)))
        {redaction-paths :schema-paths
         user-paths      :user-paths} (when-not (= ::stale-incarnation
                                                  redaction-result)
                                       redaction-result)
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
        initial-result  (when (and (live?) (some? base-chain))
                          (call-while-exact-owner
                            frame owner-token
                            #(assemble-initial-ctx
                               envelope frame frame-record handler-meta
                               fx-overrides live?)))
        initial-ctx     (when-not (= ::stale-incarnation initial-result)
                          initial-result)
        all-paths       (into (vec redaction-paths) user-paths)]
    (when (and (live?) (some? initial-ctx))
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
       :schema-sensitive? (boolean (seq redaction-paths))})))

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
  collapses to a plain `execute-chain` invocation.

  rf2-mwv4e — this is the DEV half of the `:rf/boundary-rejected?` marker. When
  step-1 refused an event whose handler REFERENCES `:rf.schema/at-boundary`,
  the refusal IS a boundary refusal and takes the same marker the production
  interceptor stamps (`re-frame.spec`), so the router tail fans one always-on
  record and settles `:outcome :rejected` in either posture. An ordinary
  dev-only `:schema` refusal on an UNGUARDED handler is deliberately NOT
  marked: that surface has no production counterpart (Spec 010 §Production
  builds, rf2-bkvu5), so marking it would invent a production signal that
  cannot exist. The predicate runs only on the refusal path — the hot path
  keeps its single `if`."
  [event-id full-chain initial-ctx event-ok? handler-meta]
  (let [ctx (if event-ok?
              initial-ctx
              (cond-> (assoc initial-ctx :rf/skip-handler? true)
                (events/boundary-guarded-handler? handler-meta)
                (assoc :rf/boundary-rejected? true)))]
    (performance/mark-and-measure :event event-id
      (interceptor/execute-chain
        full-chain ctx
        {:continue-before?
         #(frame/event-continuation-live?
            (:rf.frame/id (:coeffects ctx))
            (frame/current-event-owner-token))}))))

(defn- commit-and-flow!
  "Settle the cascade: surface any chain / flow exception, commit the
  (flow-augmented) :db, then walk :fx in source order. Per Spec 002
  §Drain-loop pseudocode. Flows have already run as the outermost
  `:after` inside the chain (rf2-u0zz5), so by the time this fn executes
  the pending `:db` effect is the flow-augmented value; the install here
  is the single deferred commit, and `:fx` walks after it.

  Per Spec 010 §Per-step recovery row 4 (rf2-uhk9ko, Mike-ruled Option
  B): a `:db` / machine-data schema-validation failure REJECTS the
  candidate transition BEFORE install — the container is never written
  (nothing to restore), no change trace fires, and the dispatch is
  treated as failed: `:fx` does NOT walk. Downstream queued events
  still drain per run-to-completion (handled by `drain-loop!`'s outer
  pass).

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
    :rolled-back — candidate schema validation REJECTED the transition
                   BEFORE install (Spec 010 row 4, rf2-uhk9ko): the
                   container was never written and keeps its
                   pre-handler value; no db-changed fired; :fx was
                   skipped. `:rolled-back` is the stable public
                   vocabulary for `transaction rejected` — it does not
                   imply a physical write-pair. NO PRODUCER IN A
                   PRODUCTION BUILD: candidate validation is dev-only
                   (Spec 010 §Production builds, rf2-bkvu5), so a
                   release build never emits this outcome — a candidate
                   violating a registered schema installs and reports
                   `:ok`.
    :flow-error  — a flow's `:derive` threw (Spec 013 §Failure
                   semantics); the event aborted — no install, app-db
                   unchanged, no db-changed, :fx skipped.
    :rejected    — the `:rf.schema/at-boundary` security gate REFUSED
                   the event's payload against the handler's `:schema`
                   (rf2-mwv4e). The handler never ran, so nothing it
                   would have written exists; entered interceptors
                   still unwound in full and any effects THEY produced
                   keep their ordinary treatment. Unlike
                   `:rolled-back`, this outcome DOES have a producer in
                   a production build — the boundary check is the one
                   validation surface Spec 010 keeps ungated.

  All four non-`:ok` values surface to off-box observability shippers
  (Datadog / Sentry / Honeycomb) so a dispatch whose `:db` write was
  rejected, that aborted on a flow throw, or whose untrusted payload was
  refused at the boundary is NOT mis-reported as a clean `:ok`. A chain
  exception is reported as `:error` regardless of any downstream
  rejection — it is the proximate, most-actionable signal — and
  `:rejected` is the LOWEST-priority discriminator: it is selected only
  when the boundary skip is the whole story, so a chain throw, a flow
  throw or a candidate rollback during the unwind still wins."
  [final-ctx event-id event frame frame-record fx-overrides envelope start-ms]
  (let [owner-token (frame/current-event-owner-token)
        live?       #(and (not (:rf/stale-incarnation? final-ctx))
                          (frame/event-continuation-live? frame owner-token))]
    ;; Exact loss is tested BEFORE the shape carriers run: malformed values
    ;; returned by A (or inserted by an authored `:after` while unwinding) are
    ;; inert and must not emit diagnostics under replacement B. The carriers
    ;; below are PURE (rf2-04tx converted the effect-map policing into one), so
    ;; a single liveness gate here covers them all — no emission happens until
    ;; a `cond` arm is chosen.
    (if-not (live?)
      ::stale-incarnation
      (let [error        (:rf/interceptor-error final-ctx)
            flow-error   (:rf/flow-error final-ctx)
            ;; The effects map VERBATIM — `commit-fx-effects` no longer cleans
            ;; it, so a foreign top-level key or a malformed `:fx` value is
            ;; still here to be refused (rf2-04tx). Every downstream consumer
            ;; reads named closed-set keys, and the refusal arm returns before
            ;; any of them run.
            effects      (:effects final-ctx)
            shape-defect (events/effect-map-defect effects event)
            class-defect (elision/classification-effect-defect effects)
            restore!     #(restore-flow-snapshots!
                            final-ctx frame owner-token)]
        (cond
          error
          (do
            (emit-pipeline-exception!
              error event-id event frame final-ctx start-ms)
            (if-not (live?)
              ::stale-incarnation
              (do (restore!)
                  (if (live?) :error ::stale-incarnation))))

          flow-error
          (do
            (emit-flow-eval-exception!
              flow-error event event-id frame start-ms)
            (if (live?) :flow-error ::stale-incarnation))

          ;; The ENVELOPE check comes first among the three effect rejections:
          ;; if the effect map's top level is malformed, no diagnosis of what
          ;; is INSIDE it is trustworthy, and the programmer's first fix is
          ;; the envelope.
          shape-defect
          (do
            (emit-effect-map-shape!
              shape-defect event event-id frame start-ms)
            (if-not (live?)
              ::stale-incarnation
              (do (restore!)
                  (if (live?) :error ::stale-incarnation))))

          (events/legacy-runtime-root? (:db effects))
          (do
            (emit-legacy-runtime-root! event event-id frame start-ms)
            (if-not (live?)
              ::stale-incarnation
              (do (restore!)
                  (if (live?) :error ::stale-incarnation))))

          class-defect
          (do
            (emit-classification-effect-shape!
              class-defect event event-id frame start-ms)
            (if-not (live?)
              ::stale-incarnation
              (do (restore!)
                  (if (live?) :error ::stale-incarnation))))

          :else
          (let [commit-result
                (commit-frame-effects!
                  effects event-id event frame frame-record owner-token
                  final-ctx)]
            (cond
              (= ::stale-incarnation commit-result)
              ::stale-incarnation

              (false? commit-result)
              (do
                (restore!)
                (if (live?) :rolled-back ::stale-incarnation))

              :else
              (let [fx-result
                    (run-fx-effects!
                      effects frame frame-record owner-token
                      fx-overrides envelope)]
                (cond
                  (= ::stale-incarnation fx-result) ::stale-incarnation
                  ;; rf2-mwv4e — LOWEST-priority discriminator. Every
                  ;; higher-priority outcome has already returned above,
                  ;; so reaching here with the marker set means the
                  ;; boundary skip really was the whole story: no chain
                  ;; throw, no flow throw, no candidate rollback. Before
                  ;; this, a refused untrusted payload settled `:ok` — the
                  ;; event stream reporting success for a dispatch whose
                  ;; handler never ran.
                  (:rf/boundary-rejected? final-ctx) :rejected
                  :else                              :ok)))))))))

;; ---- the boundary-rejection always-on record (rf2-mwv4e) -------------------
;;
;; `:rf.schema/at-boundary` is the ONE validation surface Spec 010 keeps
;; ungated in a production build — the opt-in gate for untrusted system
;; ingress (an HTTP response, a websocket frame, a `postMessage`, a query
;; string). Its REFUSAL always survived the gate; its REPORT did not.
;; `trace/emit-error!` sits behind `interop/debug-enabled?`, so under
;; `:advanced` + `goog.DEBUG=false` a rejected payload skipped its handler and
;; told nobody — an opt-in security gate invisible to the person who opted in.
;;
;; This is the always-on half. It follows the promotion pattern the URL
;; route-miss (rf2-ov56u) and the safe-redirect rejections (rf2-6jqa8)
;; established: keep the rich dev trace exactly as it was, add ONE tight
;; production fact, let the existing projector consume it.
;;
;; STRUCTURAL-ONLY, and here that is stricter than a scrub. A validation
;; failure's natural detail is THE VALUE THAT FAILED, which on this surface is
;; attacker-controlled or user-private BY DEFINITION — a rejected payload can
;; carry secrets in keys the declared schema never anticipated, so no
;; schema-aware redactor can be trusted to have seen them. So rather than
;; scrubbing an attacker-controlled slot (the rf2-ov56u / rf2-6jqa8 treatment,
;; where the URL IS the observability payload), this record OMITS every
;; payload-derived slot outright: no event vector, no `:value`, no
;; `:received`, no `:explain`, no schema form, no human `:reason` (which would
;; interpolate the offending value — the `ssr/hydrate` hazard). What remains is
;; identifiers, which is enough to COUNT, ATTRIBUTE, ALERT and PROJECT the
;; rejection; the diagnosis stays on the DCE'd dev trace in `re-frame.spec`.
;; Per OWASP's Logging Cheat Sheet: log input-validation failures, sanitise
;; event data arriving from another trust zone.
;;
;; PROJECTION-ELIGIBLE, deliberately, and this is where it diverges from
;; rf2-6jqa8. The three safe-redirect categories joined
;; `non-projection-eligible-errors` so a hostile probe could not conjure a 500.
;; A boundary rejection on a server frame is the opposite case: it is exactly a
;; 400 (RFC 9110 §15.5.1), the SSR default projector already maps
;; `:rf.error/schema-validation-failure` + `:where :event` to one, and letting
;; it project is what closes the silent-200 SSR hole that surfaced this bead.

(defn- emit-boundary-rejection-record!
  "Fan the ONE always-on STRUCTURAL-ONLY record for a boundary rejection.

  The key set is CLOSED and is pinned by
  `re-frame.always-on-validation-production-test`; a slot added here reaches an
  off-box shipper (Sentry / Datadog), so read the section comment above before
  widening it. `:event-id` / `:failing-id` / `:schema-id` are all the event id
  — the same three-slot shape the dev trace carries, so the two axes name the
  same fact identically. `:frame` may be nil (the frameless
  `:rf.error/no-frame-context` precedent).

  Reached through `error-emit/dispatch-error-record!`, the general non-event
  union-record chokepoint: a refused dispatch is not a dispatched-event
  FAILURE (nothing threw), so the event-centric `dispatch-on-error!` positional
  shape — which would carry the `:event` wire value — is both the wrong shape
  and the wrong egress. Called from the pipeline tail so the dev and production
  enforcement routes share ONE emit site and a rejection can never produce two
  records."
  [event-id frame]
  (error-emit/dispatch-error-record!
    {:error      :rf.error/schema-validation-failure
     :where      :event
     :source     :boundary
     :event-id   event-id
     :failing-id event-id
     :schema-id  event-id
     :frame      frame
     :recovery   :no-recovery
     :time       (interop/now-ms)})
  nil)

(defn- emit-pipeline-trailers!
  "Pipeline-run-tail emissions: the dev-only `:run-end` trace then the
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
  `:error`, `:rolled-back`, `:flow-error`, or `:rejected` — and rides
  straight onto the event-emit record's `:outcome` slot (Spec 009
  §Record shape).

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
                                ctx-delta records `{:rf.interceptor.delta/id <id>
                                :rf.interceptor.delta/ctx-delta {...}}` populated by
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
  (let [owner-token (frame/current-event-owner-token)
        live?       #(frame/event-continuation-live? frame owner-token)]
    (if-not (live?)
      ::stale-incarnation
      (let [user-cofx    (when interop/debug-enabled?
                           (fx/user-injected-coeffects (:coeffects final-ctx)))
            after-deltas (when interop/debug-enabled?
                           (not-empty (:rf/interceptor-after-deltas final-ctx)))]
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
        (if-not (live?)
          ::stale-incarnation
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
            (call-while-exact-owner
              frame owner-token
              #(emit-event! emit-event event-id frame end-ms outcome elapsed-ms)))
          (when (and (live?) route-handled!)
            ;; The effect keys the cascade produced (`final-ctx`'s
            ;; `:effects` map keys) ride the handled-event record's
            ;; `:effects` summary slot; the dispatch-id (dev-only — nil
            ;; under `goog.DEBUG=false`) rides `:correlation` when present.
            (let [effects     (some-> (:effects final-ctx) keys vec)
                  dispatch-id (some-> trace/*handler-scope* :dispatch-id)
                  correlation (when dispatch-id {:dispatch-id dispatch-id})]
              (call-while-exact-owner
                frame owner-token
                #(route-handled! event
                                 event-id
                                 frame
                                 outcome
                                 elapsed-ms
                                 effects
                                 correlation)))))
          (if (live?) :ok ::stale-incarnation))))))))

(defn- run-handler-pipeline!
  "Sequence the four pipeline-run phases under the handler's
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
  and fx walk — covering :rf.event/db-changed, :rf.fx/do-fx, :rf.fx/handled
  (the inner fx scope re-binds), :rf.sub/run (sub recompute re-binds),
  :rf.error/* (every error emit inside the chain).

  Per rf2-rirbq: `start-ms` is captured at the very start of cascade
  execution (unconditional, single `now-ms` call per event) so the
  always-on event-emit substrate can report `:elapsed-ms` in its per-
  event record."
  [envelope event-id event frame frame-record handler-meta]
  (frame/call-with-event-owner-token
    frame
    (:drain-lock frame-record)
    (frame/current-event-owner-allows-closing?)
    (fn []
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
    (when (frame/event-continuation-live?
            frame (frame/current-event-owner-token))
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
                        (-> initial-ctx :coeffects :rf.cofx))
            ;; rf2-yigokd — the envelope's OWN per-call + lexical
            ;; `:fx-overrides` / per-call `:interceptor-overrides` (NOT the
            ;; frame-merged `fx-overrides` local above — the per-frame tier is
            ;; deliberately excluded; it stays in the replay target's live
            ;; frame config, per the ruling's pinned scope). Fn-valued
            ;; `:fx-overrides` entries are marker-ized here, at the emission
            ;; site, so a fn NEVER reaches the trace stream / epoch record.
            ;; `:interceptor-overrides` is EDN by construction (EP-0022) and
            ;; rides verbatim. Both nil when empty so the override-free hot
            ;; path omits the slots entirely, same shape as `run-cofx`.
            override-fx   (when interop/debug-enabled?
                            (serializable-fx-overrides (:fx-overrides envelope)))
            override-icpt (when interop/debug-enabled?
                            (not-empty (:interceptor-overrides envelope)))
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
                                     (assoc :rf.event/cofx run-cofx)
                                     ;; rf2-yigokd — the envelope's serializable
                                     ;; override keys, surfaced here so
                                     ;; `find-trigger-event` can pin them as
                                     ;; first-class `:fx-overrides` /
                                     ;; `:interceptor-overrides` epoch-record
                                     ;; slots and a strict replay can re-supply
                                     ;; them beside `:rf.cofx`.
                                     (some? override-fx)
                                     (assoc :rf.event/fx-overrides override-fx)
                                     (some? override-icpt)
                                     (assoc :rf.event/interceptor-overrides override-icpt)))
            event-ok? (when (frame/event-continuation-live?
                              frame (frame/current-event-owner-token))
                        (validate-event! event-id event handler-meta frame
                                         #(frame/event-continuation-live?
                                            frame
                                            (frame/current-event-owner-token))))
            final-ctx (if (frame/event-continuation-live?
                            frame (frame/current-event-owner-token))
                        (run-chain event-id full-chain initial-ctx event-ok?
                                   handler-meta)
                        (assoc initial-ctx :rf/stale-incarnation? true))
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
            ;; (:ok / :error / :rolled-back / :flow-error / :rejected) so the
            ;; always-on event-emit record reflects schema-rejection,
            ;; flow-throw and boundary-refusal outcomes, not just the chain
            ;; exception.
            outcome   (commit-and-flow! final-ctx event-id event frame
                                        frame-record fx-overrides envelope start-ms)]
        ;; A stale A tail is deliberately silent: run-end / always-on handled
        ;; records keyed by the bare id would describe fresh same-id B.
        (when-not (= ::stale-incarnation outcome)
          ;; rf2-mwv4e — the always-on boundary-rejection record LEADS the
          ;; tail: it is the CAUSE, and the `:events` record the trailers fan
          ;; carries only its consequence (`:outcome :rejected`). This is the
          ;; ONE emit site for both enforcement routes — in dev the refusal
          ;; happens in step-1 `validate-event!`, in production inside the
          ;; boundary interceptor, and both merely stamp
          ;; `:rf/boundary-rejected?` for this line to read — so a rejection
          ;; can never produce two records. It keys off the rejection FACT
          ;; rather than the outcome, so a refusal whose interceptor unwind
          ;; then threw reports BOTH the refusal and the throw. Wrapped in the
          ;; exact-owner fence because the always-on fan-out runs listener
          ;; callbacks, one of which may destroy this frame; `emit-pipeline-
          ;; trailers!` re-checks liveness on entry and goes silent if so.
          (when (:rf/boundary-rejected? final-ctx)
            (call-while-exact-owner
              frame (frame/current-event-owner-token)
              #(emit-boundary-rejection-record! event-id frame)))
          (emit-pipeline-trailers! event-id event emit-event frame outcome
                                  start-ms handler-elapsed-ms final-ctx)))))))))

(defn- process-event*
  "Per-event drain body. Resolve handler, then sequence the four
  pipeline-run phases under the handler-scope binding (see
  `run-handler-pipeline!`).
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
  [envelope frame-record]
  (let [{:keys [event frame]} envelope
        event-id              (first event)]
    (cond
      (nil? frame-record)
      (handle-frame-destroyed! event frame)

      :else
      (let [owner-token (frame/current-event-owner-token)
            registered  (call-while-exact-owner
                          frame owner-token #(resolve-handler event-id))
            handler-meta
            (cond
              (= ::stale-incarnation registered)
              ::stale-incarnation

              (some? registered)
              registered

              :else
              ;; Per rf2-a2sn1 — the lazy actor-handler resolver seam. A
              ;; dynamically-spawned machine actor carries no per-instance
              ;; registrar entry. Resolution is callback-bearing and therefore
              ;; has the same exact-owner fence as the primary registrar.
              (call-while-exact-owner
                frame owner-token #(resolve-unhandled event frame)))]
            ;; Both registered/image resolution and the late-bound machine
            ;; resolver are callback-bearing. Loss during resolution is a
            ;; silent terminal fence: no no-handler diagnostic or preparation
            ;; may be attributed to a same-id successor.
        (when (and (not= ::stale-incarnation handler-meta)
                   (frame/event-owner-live? frame))
          (if (nil? handler-meta)
            (diag/handle-no-handler! event-id event frame)
            (run-handler-pipeline! envelope event-id event frame
                                   frame-record handler-meta)))))))

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
      a fresh stack with no dynamic binding. Use `(rf/capture-frame)`
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
      (a direct frame VALUE and a frame-id keyword normalize to the same
      record address, so both read the same sealed generation), never an
      ambient binding (EP-0002). A target that names no live image-loaded
      frame yields no generation, so `call-with-frame-resolution` binds
      NOTHING and resolution falls through to the registrar-atom path,
      byte-identical (absence-is-default). Child dispatches re-enter
      `process-event!` for their frame and re-derive the binding, so the
      generation is preserved across the cascade automatically."
  [envelope frame-record owner-token allow-closing?]
  (frame/call-with-event-owner-token
    (:frame envelope)
    owner-token
    allow-closing?
    (fn []
      (trace/call-with-continuation-predicate
        ;; Exact liveness fences normal event tail. The exact closing marker is
        ;; the narrow terminal-recipe exemption: destroy-frame!'s own teardown
        ;; traces remain observable until its finally releases A's claim.
        #(frame/event-continuation-live? (:frame envelope) owner-token)
        (fn []
      (trace/with-dispatch-id+call-site (:dispatch-id envelope) (:call-site envelope)
        (binding [frame/*current-frame* (:frame envelope)]
          ;; Preserve the read-time coalesced projection guarantee, but check
          ;; exact ownership around the callback and then derive generation
          ;; from the still-A record. Never let the generic bare-id resolver
          ;; redirect this already-dequeued envelope into same-id B.
          ;; rf2-9c2jf: NOT gated on `interop/debug-enabled?`. The generation
          ;; this cascade resolves through is sealed unconditionally by
          ;; `make-frame`, so a skipped flush froze the frame's view of the
          ;; registration pool in production and every later-registered handler
          ;; dispatched as `:rf.error/no-such-handler`. The consult is by
          ;; late-bind KEYWORD and its publisher is rooted from `make-frame`,
          ;; so a bundle that never constructs a frame still DCEs the graph.
          (when (frame/event-owner-live? (:frame envelope))
            (when-let [flush! (late-bind/get-fn-cached
                                :live-frame/flush-projection!)]
              (try
                (flush!)
                (catch #?(:clj Throwable :cljs :default) e
                  ;; The projection callback is framework-owned. Preserve a
                  ;; real failure while A owns the event; a destroy+throw is
                  ;; inert with the rest of A's abandoned tail.
                  (when (frame/event-owner-live? (:frame envelope))
                    (throw e))))))
          (when (frame/event-owner-live? (:frame envelope))
            (let [active-record (frame/frame (:frame envelope))]
              (when (and active-record
                         (identical? owner-token (:drain-lock active-record)))
                (if-let [generation (:generation active-record)]
                  (binding [registrar/*generation* generation]
                    (process-event* envelope active-record))
                  (process-event* envelope active-record))))))))))))

(def ^:private drain-depth-default
  ;; Deep enough for typical cascade depths. When exceeded, the runtime
  ;; halts the next (unstarted) event per Spec 002 §Run-to-completion rule
  ;; 3 — already-settled events stay durable; the halting event gets a
  ;; trailing `:halted-depth` epoch record (no whole-drain rollback under
  ;; the per-event epoch model).
  100)

(def ^:private cycle-evidence-depth
  ;; rf2-fcbrjo: the bound on the per-drain settled-event-id ring the
  ;; depth-halt path attaches as CYCLE EVIDENCE (`:tail-event-ids`) on the
  ;; always-on `:rf.error/drain-depth-exceeded` record. A runaway drain is
  ;; almost always a small dispatch cycle repeating (A → B → A → …), so the
  ;; last K settled ids ARE the cycle — the repeating suffix names it. K is
  ;; small (the ring is allocated per drain that overflows, and it only ever
  ;; needs to be long enough to show the repeat) and carries STRUCTURAL ids
  ;; only (the event-id keyword, never the event args), so it survives the
  ;; always-on egress-redaction posture (Spec 009 §The promotion criterion —
  ;; structured data only).
  16)

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
  other; `restore-epoch!` refuses non-`:ok` targets.

  rf2-fcbrjo — ALWAYS-ON promotion + cycle evidence. A drain-depth halt is
  the one error class that is inherently DATA-dependent and PRODUCTION-only:
  under `goog.DEBUG=false` the dev trace surface is DCE'd, so before this
  the halt shipped NOTHING to any sink and the runaway simply went silent.
  Per Spec 009 §The promotion criterion (all three legs hold — production-
  reachable, a corrupted-invariant contract breach the next operation can't
  see, silence compounds), the halt now ALSO fans a STRUCTURAL-ONLY record
  out through the always-on axis (`error-emit/dispatch-error-record!`, the
  non-event union-record path the frame-teardown report rides) so an off-box
  shipper sees the halt under `goog.DEBUG=false`. `tail-event-ids` (the last
  K settled event-ids, the `run-one-pass!` ring) is the CYCLE EVIDENCE — the
  repeating suffix names the runaway cycle. The record carries ids / counts
  ONLY; the human `:reason` prose stays on the dev-only `trace/emit-error!`
  path (elided in production), per the elision discipline.

  rf2-vxgfnd.154 — EXACT-INCARNATION halt fence. `owner-token` is A's captured
  event-owner token (the frame record's `:drain-lock`). The depth-halt seam runs
  OUTSIDE the event pipeline's `trace/call-with-continuation-predicate`, so
  before this fix `trace/continuation-live?` read an always-true predicate: the
  first `:rf.error/drain-depth-exceeded` listener could destroy A and publish a
  same-id B, and every later fanout sibling, the frame-owned error route, the
  dev trace, and the bare-id halt commit then leaked into B. Binding A's exact-
  owner predicate around all callback-bearing halt work — and threading A's exact
  token into `commit-halt-record!` — fences B: once the first listener loses A,
  no later listener, frame route, trace, queue trailer, or halt commit targets B,
  while evidence already delivered before the loss stands exactly once."
  [frame-id owner-token router depth last-event tail-event-ids]
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
        ;; rf2-fcbrjo — STRUCTURAL cycle evidence for the always-on record.
        ;; `:last-event-id` is the id keyword of the most-recently-settled
        ;; event; `:tail-event-ids` is the ring of the last K settled ids
        ;; (the repeating suffix names the runaway cycle); `:dropped-event-ids`
        ;; is the queue-ordered id vector of the events cleared from the queue
        ;; at the halt. All ids only — NO event args ride the always-on axis.
        last-event-id   (when (vector? last-event) (first last-event))
        dropped-event-ids (into []
                                (comp (keep :event)
                                      (map #(when (vector? %) (first %))))
                                queue)
        halt-reason     {:operation  :rf.error/drain-depth-exceeded
                         :depth      depth
                         :queue-size queue-size
                         :last-event last-event}]
    ;; The evidence assembly above is a pure read; A still owns the frame at the
    ;; halt seam (a halt is a depth trip, not a destroy). Bind A's EXACT-owner
    ;; continuation predicate around EVERY callback-bearing halt action below
    ;; (rf2-vxgfnd.154). `trace/continuation-live?` — which the always-on fanout,
    ;; the frame-owned route, and the dev trace all consult — now reflects A's
    ;; exact liveness, so the instant the first depth-error listener destroys A
    ;; and publishes same-id B, every later sibling / route / trace / commit is
    ;; fenced from B.
    (trace/call-with-continuation-predicate
      #(frame/event-continuation-live? frame-id owner-token)
      (fn []
    ;; Axis 1 — ALWAYS-ON (rf2-fcbrjo). Fan a STRUCTURAL-ONLY non-event union
    ;; record out through the corpus-wide error-emit listener + the frame-owned
    ;; observability sink, so a drain-depth halt surfaces under `goog.DEBUG=
    ;; false` (the dev trace below is DCE'd there). Ids / counts / the cycle
    ;; id-vector ONLY — no event args, no `:reason` prose — per Spec 009 §The
    ;; promotion criterion (structured data only) and the elision discipline.
    (error-emit/dispatch-error-record!
      {:error             :rf.error/drain-depth-exceeded
       :frame             frame-id
       :time              (interop/now-ms)
       :depth             depth
       :queue-size        queue-size
       :last-event-id     last-event-id
       :tail-event-ids    tail-event-ids
       :dropped-event-ids dropped-event-ids
       ;; Per rf2-nj6p7: no whole-drain rollback under per-event epochs —
       ;; the already-settled events are durable.
       :rollback?         false
       :recovery          :no-recovery})
    ;; Axis 2 — dev-only trace. This is where the RICH diagnostic prose
    ;; (`:reason`, built with `str` + `pr-str`) rides — the full `:last-event`
    ;; vector too, for the local debugger.
    ;;
    ;; rf2-fcbrjo / rf2-cprm0q — the EXPLICIT `interop/debug-enabled?` call-site
    ;; gate is MANDATORY here, not the internal gate inside `trace/emit-error!`
    ;; alone. This fn ALSO makes the live always-on `dispatch-error-record!`
    ;; call above, so `handle-depth-exceeded!` is NOT a sole-statement leaf that
    ;; Closure can fold on the emit body's nil-return; without the call-site
    ;; gate the `(str … (pr-str …))` prose survives into the production bundle
    ;; (the exact leak that broke rf2-cprm0q / #5107). The call-site gate lets
    ;; Closure constant-fold the whole form — prose and all — under `:advanced`
    ;; + `goog.DEBUG=false` (pinned by the 009 elision probe).
    (when interop/debug-enabled?
      (trace/emit-error! :rf.error/drain-depth-exceeded
                         {:frame             frame-id
                          :depth             depth
                          :queue-size        queue-size
                          :last-event        last-event
                          :last-event-id     last-event-id
                          :tail-event-ids    tail-event-ids
                          :dropped-event-ids dropped-event-ids
                          ;; Dev-only human prose — the runaway-cycle hint.
                          :reason            (str "Drain depth limit (" depth
                                                  ") exceeded — likely a dispatch"
                                                  " loop. Cycle (last settled ids): "
                                                  (pr-str tail-event-ids))
                          ;; Per rf2-nj6p7: no whole-drain rollback under
                          ;; per-event epochs — the already-settled events
                          ;; are durable. `:rollback? false` reflects that.
                          :rollback?         false
                          :recovery          :no-recovery}))
    ;; Drop A's runaway queue. The `router` atom is A's incarnation-private drain
    ;; FSM (`make-frame` builds a fresh one per incarnation), so clearing it never
    ;; reaches a same-id successor B; it runs unconditionally so A's abandoned
    ;; queue never lingers even after a listener above lost A.
    (swap! router assoc :queue interop/empty-queue :scheduled? false)
    ;; The halt commit is A's terminal `:halted-depth` epoch record. Gate it on
    ;; A's live continuation AND thread A's EXACT owner token: once A is lost the
    ;; commit neither harvests B's capture buffer nor claims/commits into B's
    ;; history (rf2-vxgfnd.154). The halting event never ran, so the capture
    ;; buffer is empty and `settle!` would skip; `commit-halt-record!` commits
    ;; regardless, pinning the halting event's trigger. :frame-state-before
    ;; equals :frame-state-after — the halting event made no write. rf2-bh56rc:
    ;; `:committed-at` is the halting event's causal `:rf/time-ms`, not an
    ;; ambient read.
    (when (trace/continuation-live?)
      (when-let [commit-halt! (late-bind/get-fn-cached :epoch/commit-halt-record!)]
        (commit-halt! frame-id fs-now fs-now halting-time-ms :halted-depth
                      halt-reason halting-event owner-token)))))))

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
  never fired `:rf.event/run-start`), so a no-handler / frame-destroyed early
  exit commits no misleading record. rf2-erczwd: a rejected dispatch still
  buffers a frame-stamped, dispatch-id-bearing ERROR trace (`:no-such-handler`
  / `:frame-destroyed`), so the buffer is NOT empty at this seam. Threading the
  settling envelope's `:dispatch-id` lets the scoped no-run-start harvest drop
  THAT dispatch's own traces — so the skip fires and no fake `:ok` epoch lands
  — while any unrelated buffered child marker survives for its own settle.

  EP-0001 (rf2-3aizt1, decision #2): `frame-state-before` / `frame-state-after`
  are whole frame-state values (both partitions); `build-record` derives the
  `:db-before` / `:db-after` app-db projections from them.

  rf2-bh56rc: `committed-at` is the settling event's causal `:rf/time-ms` (its
  envelope's `:rf.cofx` `:rf/time-ms`, stamped at the causal boundary).
  Threaded into the epoch record's `:committed-at` so the durable
  causal-time fact is replayable per EP-0010 §Time / Spec 002 §Recordable
  coeffects, not an ambient assembly-time host-clock read."
  [frame-id owner-token frame-state-before frame-state-after committed-at
   settling-dispatch-id]
  (call-while-exact-owner
    frame-id owner-token
    (fn []
      (when-let [settle! (late-bind/get-fn-cached :epoch/settle!)]
        (settle! frame-id frame-state-before frame-state-after committed-at
                 :ok nil settling-dispatch-id
                 {:exact-owner-token owner-token})))))

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
  mid-drain: the drain-loop detected that destruction owns the frame before
  the next dequeue (claim is the cutoff; lifecycle-dead may publish later).
  Drop the remaining queue ONCE, clear `:scheduled?`,
  and emit a single `:rf.frame/drain-interrupted` lifecycle trace
  carrying `:dropped-count` (per Spec 009 §`:rf.frame/drain-interrupted`
  and Spec-Schemas §DrainInterruptedTags).

  An authored callback already on the stack may return and entered authored
  interceptor afters may unwind, but the exact-incarnation continuation fence
  makes its returned context/output inert before this check fires.
  `:dropped-count` combines
  events removed atomically at claim time with any ordinary events found at
  this later check; the destroy-owned private cleanup queue is excluded.

  The check fires AFTER `process-event!` returns and BEFORE the next
  `take-event!` — same seam as `handle-depth-exceeded!`.

  Per rf2-9neiq: this seam NO LONGER commits the `:halted-destroy` epoch
  record. That record is owned by a single site — the epoch destroy hook
  (`re-frame.epoch.listeners/on-frame-destroyed!`), invoked synchronously
  from `frame/destroy-frame!` (step 11) the instant the handler destroyed
  its own frame. That site carries the run's harvested buffer AND the
  pre-run / destroy-time frame-state snapshots (threaded via
  `frame/*run-frame-state-before*` + the destroy-time container read), so it
  builds a record with real `:frame-state-before` / `:frame-state-after`
  (and their `:db-*` app-db projections) per Spec-Schemas
  §`:rf/epoch-record` §Outcomes. Routing a second `:halted-destroy` commit
  through `settle!` here would either no-op on the now-empty (already-
  harvested-by-the-hook) buffer or, worse, double-fan a duplicate record to
  listeners. The drain-loop's responsibility is the lifecycle trace + queue
  drop; the epoch record is the destroy hook's."
  [frame-id router]
  (let [report (volatile! nil)]
    ;; Claim can leave more than one already-captured scheduler callback, and a
    ;; post-claim submit may capture another after claim clears `:scheduled?`.
    ;; Compare/mark inside the router swap so all callbacks for this exact
    ;; router generation still clear rejected work, but only the first winner
    ;; consumes the combined evidence and emits.
    (swap! router
           (fn [{:keys [queue destroy-claim-dropped-count
                        destroy-claim-report-emitted?]
                 :as state}]
             (let [dropped (+ (count queue)
                              (or destroy-claim-dropped-count 0))]
               (when-not destroy-claim-report-emitted?
                 (vreset! report dropped))
               (cond-> (-> state
                           (assoc :queue interop/empty-queue
                                  :scheduled? false)
                           (dissoc :destroy-claim-dropped-count))
                 (not destroy-claim-report-emitted?)
                 (assoc :destroy-claim-report-emitted? true)))))
    (when-some [dropped @report]
      ;; A stale drainer can reach this seam after A's registry slot has been
      ;; reused by B. The lifecycle report is still globally observable, but
      ;; it must never enter B's bare-id epoch buffer.
      (trace/call-with-structural-delivery
        #(trace/emit! :rf.frame :rf.frame/drain-interrupted
                      {:frame         frame-id
                       :dropped-count dropped})))))

(defn- run-one-pass!
  "Process events from the queue to fixed point or until `drain-depth` is
  exceeded. Returns `::settled` when the queue empties cleanly or
  `::halt` when the depth limit is reached OR destruction owns the frame
  mid-pass (the depth-exceeded / drain-interrupted handler has already
  cleared the queue and the `:scheduled?` flag in either halt case).

  Per rf2-68kok / Spec 002 §Frame disposal mid-drain: the destruction-
  ownership check fires BEFORE each dequeue. An authored callback already on
  the stack may return and entered authored interceptor afters may unwind, but
  its returned context/output is inert immediately after exact ownership is
  lost; no commit, flow, effect, child dispatch, ordinary diagnostic/trailer,
  normal epoch settlement, or render follows. No later ordinary event begins. One
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
  also bound to `frame/*run-frame-state-before*` around `process-event!`
  so a handler that destroys its own frame mid-drain can recover the
  pre-run snapshot for its `:halted-destroy` epoch record (rf2-9neiq)."
  [frame-id frame-record router drain-depth allowed-destroy-token]
  ;; rf2-fcbrjo: `tail-ring` accumulates the last K settled event-ids as the
  ;; drain runs — the CYCLE EVIDENCE the depth-halt attaches to the always-on
  ;; record. A bounded vector (drop the head past `cycle-evidence-depth`); ids
  ;; only, no args. Empty until the first event settles (a depth-0 frame halts
  ;; before any event runs, so the ring is legitimately empty there).
  (loop [depth      0
         last-event nil
         tail-ring  []]
    (cond
      (>= depth drain-depth)
      ;; rf2-vxgfnd.154: thread A's EXACT owner token (`:drain-lock`) so the halt
      ;; fanout, frame route, dev trace, and terminal commit all bind to A's
      ;; incarnation and are fenced from a same-id B a depth-error listener may
      ;; publish.
      (do (handle-depth-exceeded! frame-id (:drain-lock frame-record) router
                                  depth last-event tail-ring)
          ::halt)

      ;; Per rf2-68kok: destruction-ownership check fires BEFORE the next
      ;; dequeue. A handler in the just-completed event may have
      ;; called `destroy-frame!` on its own frame; the spec calls for
      ;; interrupting the drain at this exact seam — drop the
      ;; remaining queue, emit one `:rf.frame/drain-interrupted`
      ;; lifecycle event, halt.
      ;;
      ;; Per rf2-v0jwt / rf2-9neiq: the authored callback may have returned
      ;; and entered authored interceptor afters may have unwound, but the
      ;; returned context and every normal framework tail are inert. The
      ;; `:halted-destroy`
      ;; record for the interrupted drain is committed by the epoch destroy
      ;; hook (`on-frame-destroyed!`), which fired synchronously inside the
      ;; handler that called `destroy-frame!`, carrying the cascade buffer
      ;; and real db snapshots; this seam only drops the queue and emits the
      ;; `:rf.frame/drain-interrupted` lifecycle trace. `restore-epoch!`
      ;; refuses non-:ok records, preserving the original "time-travel never
      ;; lands in a misleading state" invariant.
      (and (frame/frame-disposed-for-drain? frame-id)
           ;; The sole post-claim execution path is the internal teardown
           ;; cascade. It presents the exact claimed incarnation token and
           ;; drains an isolated local queue; ordinary drains always pass nil.
           ;; If the marker no longer names this token, even that cascade halts.
           (not (and (some? allowed-destroy-token)
                     (frame/frame-incarnation-closing?
                       frame-id allowed-destroy-token))))
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
        (let [owner-token    (:drain-lock frame-record)
              allow-closing? (and (some? allowed-destroy-token)
                                  (identical? owner-token
                                              allowed-destroy-token))
              continue?      #(frame/call-with-event-owner-token
                                frame-id owner-token allow-closing?
                                (fn []
                                  (frame/event-continuation-live?
                                    frame-id owner-token)))
              ;; The active drainer already owns A's record/router. Read A's
              ;; captured container directly; a callback-induced same-id B can
              ;; never become this dequeued envelope's preparation target.
              fs-before   (call-while-exact-owner
                            frame-id owner-token allow-closing?
                            #(frame/frame-record-state-value frame-record))
              ;; rf2-bh56rc: this event's causal `:rf/time-ms` — the
              ;; `:rf.cofx` `:rf/time-ms` stamped on the envelope at the
              ;; causal boundary (`build-envelope`). Threaded into the epoch
              ;; record's `:committed-at` (per EP-0010 §Time / Spec 002
              ;; §Recordable coeffects) so the durable causal-time fact is
              ;; replayable rather than an ambient assembly-time clock read.
              time-ms   (-> envelope :rf.cofx :rf/time-ms)]
          ;; Per rf2-9neiq: expose this event's pre-run frame-state to a
          ;; handler that calls `destroy-frame!` on its OWN frame mid-drain.
          ;; `destroy-frame!`'s epoch hook reads `frame/*run-frame-state-before*`
          ;; for the `:halted-destroy` record's pre-run snapshot — the
          ;; value the frame-state held before this in-flight event's run
          ;; began, which is otherwise gone by the time the (post-dissoc)
          ;; epoch hook fires. rf2-bh56rc: `*run-time-ms*` is bound the
          ;; same way so the mid-drain `:halted-destroy` record's
          ;; `:committed-at` is THIS event's causal time, not an ambient read.
          (when-not (= ::stale-incarnation fs-before)
            (binding [frame/*run-frame-state-before* fs-before
                      frame/*run-time-ms*            time-ms]
              (when (continue?)
                (process-event! envelope frame-record owner-token
                                allow-closing?))))
          (when (and (not= ::stale-incarnation fs-before)
                     (continue?))
            (let [fs-after (call-while-exact-owner
                             frame-id owner-token allow-closing?
                             #(frame/frame-record-state-value frame-record))]
              ;; The normal settle hook is framework-owned tail.  Once A is
              ;; lost, the destroy hook alone owns A's halted snapshot; no
              ;; settle lookup/callback may target a successor or absence.
              (when (and (not= ::stale-incarnation fs-after)
                         (continue?))
                (frame/call-with-event-owner-token
                  frame-id owner-token allow-closing?
                  #(settle-event-epoch! frame-id owner-token fs-before fs-after
                                        time-ms (:dispatch-id envelope))))))
          (if-not (continue?)
            ;; Owner loss terminates A's router even if a fresh same-id B is
            ;; already live. B's lifecycle cannot make A's disposed predicate
            ;; look healthy or swallow A's dropped-count report.
            (do
              (handle-drain-interrupted! frame-id router)
              ::halt)
            (let [event    (:event envelope)
                ;; rf2-fcbrjo: append this settled event's id to the bounded
                ;; cycle-evidence ring (ids only; drop the head past K).
                event-id (when (vector? event) (first event))
                ring     (conj tail-ring event-id)
                ring     (if (> (count ring) cycle-evidence-depth)
                           (subvec ring (- (count ring) cycle-evidence-depth))
                           ring)]
              (recur (inc depth) event ring))))
        ::settled))))

(defn- force-release-on-halt!
  "Release the drain-lock after a `::halt` outcome. The depth-exceeded
  handler has already forcibly cleared the queue and set `:scheduled?`
  false, so we only need to drop the lock. Taken under `locking router`
  to serialize against `ensure-drain-scheduled!`'s flag-read.

  Per rf2-x76af2.22 (b): a REENTRANT drain (`hold-lock?` true — driven by
  `drain-reentrant!` for a thread already holding the frame's cold
  serialization) does NOT drop the lock; the outer cold section owns it
  and releases it in its own `finally`."
  [router drain-lock hold-lock?]
  (locking router
    (when-not hold-lock?
      (reset! drain-lock false))))

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

  This is the orphan-prevention seam.

  Per rf2-x76af2.22 (b): a REENTRANT drain (`hold-lock?` true) still clears
  `:scheduled?` on empty but LEAVES `:drain-lock` held — the outer cold
  `call-serialized-with-drain!` section owns it and drops it in its own
  `finally`, so its serialized window spans the nested cascade."
  [router drain-lock hold-lock?]
  (locking router
    (let [{:keys [queue]} @router]
      (if (empty? queue)
        (do (swap! router assoc :scheduled? false)
            (when-not hold-lock?
              (reset! drain-lock false))
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
  `run-one-pass!` per dequeued event, not here.

  `hold-lock?` (rf2-x76af2.22 (b)): false on the normal async / sync
  entries (`drain-try!` / `drain-block!`, which acquire the lock and must
  release it when the queue empties); true on the REENTRANT entry
  (`drain-reentrant!`), where the calling thread already owns the lock via
  a cold `frame/call-serialized-with-drain!` section and the release phases
  must leave it held for that outer section to drop."
  [frame-id frame-record router drain-lock drain-depth hold-lock?]
  (loop []
    (let [outcome (try
                    (mark-drainer! router)
                    (run-one-pass! frame-id frame-record router drain-depth nil)
                    (finally
                      (clear-drainer! router)))]
      (case outcome
        ::halt    (force-release-on-halt! router drain-lock hold-lock?)
        ::settled (when (try-release-on-empty! router drain-lock hold-lock?)
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

  Wrapped in `trace/call-with-deferred-listener-delivery` (rf2-wxy1c): the whole
  acquire → drain → release region is one post-drain trace-delivery boundary, so
  this drain's traces reach listeners only once the `:drain-lock` is back down —
  never concurrently with a sibling frame's drain, and never with arbitrary
  listener code running under our lock.

  Per rf2-ynk7 §single-drainer invariant."
  [frame-id frame-record]
  (trace/call-with-deferred-listener-delivery
    (fn []
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get (:config frame-record) :drain-depth drain-depth-default)]
        (when (compare-and-set! drain-lock false true)
          ;; Exact-target revalidation belongs INSIDE the acquired serialization
          ;; boundary.  A scheduled callback for obsolete incarnation A must never
          ;; re-resolve the bare id and become an eager drain of same-id B.
          (if (frame/frame-incarnation-live? frame-id drain-lock)
            (try
              (drain-loop! frame-id frame-record router drain-lock drain-depth false)
              (catch #?(:clj Throwable :cljs :default) t
                (drain-emergency-release! router drain-lock)
                (throw t)))
            (reset! drain-lock false)))))))

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
  release path as the drain loop body.

  Returns `true` iff `under-lock-fn` (the seed-push) actually ran — the
  post-CAS incarnation revalidation passed. `false` when A was lost during the
  spin-CAS wait, so the seed-push was skipped and the lock reset (rf2-a2x2w:
  `dispatch-sync!` reads that signal to recover-but-emit exactly once for a
  captured op that lost its pinned incarnation before the drain-lock acquire).

  Wrapped in `trace/call-with-deferred-listener-delivery` (rf2-wxy1c) so the whole
  acquire → seed → drain → release region — `under-lock-fn` included, since it too
  emits while the lock is held — is one post-drain trace-delivery boundary. The
  deferred batch is flushed before this returns, so `dispatch-sync`'s
  settle-before-return contract still covers listener delivery."
  [frame-id frame-record under-lock-fn]
  (trace/call-with-deferred-listener-delivery
    (fn []
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get (:config frame-record) :drain-depth drain-depth-default)]
        ;; Spin-CAS until we acquire. On JVM the active drainer holds
        ;; the lock for the duration of one drain pass — bounded by
        ;; drain-depth events at most — so the wait is bounded. CLJS
        ;; is single-threaded; the CAS succeeds on first attempt.
        (loop []
          (when-not (compare-and-set! drain-lock false true)
            #?(:clj (Thread/yield))
            (recur)))
        ;; The caller accepted THIS record.  Revalidate it only after acquiring its
        ;; lock; never re-resolve by id after a wait in which A may become B.
        (if (frame/frame-incarnation-live? frame-id drain-lock)
          (try
            (under-lock-fn)
            (drain-loop! frame-id frame-record router drain-lock drain-depth false)
            true
            (catch #?(:clj Throwable :cljs :default) t
              (drain-emergency-release! router drain-lock)
              (throw t)))
          (do (reset! drain-lock false)
              false))))))

(defn- drain-reentrant!
  "Reentrant synchronous-drain entry for a thread that ALREADY owns
  `frame-id`'s drain serialization via a COLD
  `frame/call-serialized-with-drain!` critical section (a Tool-Pair state
  write, the `destroy-frame!` liveness flip, or any lifecycle op reached
  from a serialized thunk). That thread already holds `:drain-lock`, so the
  normal `drain-block!` spin-CAS-acquire would deadlock against itself
  (rf2-x76af2.22 (b) — the same-thread self-deadlock).

  Runs `under-lock-fn` (the front-of-queue seed-push) and the drain loop
  DIRECTLY — no acquire (already held) and no release (`hold-lock?` true):
  the outer cold section owns the lock and drops it in its own `finally`,
  so its serialized window spans this nested cascade. On an unhandled
  throw, clear `:scheduled?` / `:in-drain?` but LEAVE the lock held — the
  cold section's `finally` releases it — mirroring
  `drain-emergency-release!` minus the lock reset.

  Returns `true` iff `under-lock-fn` (the seed-push) actually ran — the record's
  incarnation was still live. `nil` when a same-id replacement invalidated the
  target, so the seed-push was skipped (rf2-a2x2w: `dispatch-sync!` reads that
  signal to recover-but-emit exactly once for a captured op superseded before
  the reentrant drain)."
  [frame-id frame-record under-lock-fn]
  (let [drain-lock  (:drain-lock frame-record)
        router      (:router frame-record)
        drain-depth (get (:config frame-record) :drain-depth drain-depth-default)]
    ;; The outer cold section owns THIS record's lock.  Same-id replacement
    ;; invalidates the target; it does not retarget the synchronous dispatch.
    (when (frame/frame-incarnation-live? frame-id drain-lock)
      (try
        (under-lock-fn)
        (drain-loop! frame-id frame-record router drain-lock drain-depth true)
        true
        (catch #?(:clj Throwable :cljs :default) t
          (locking router
            (swap! router assoc :scheduled? false :in-drain? nil))
          (throw t))))))

(declare front-insert-machine-internal)

(defn- ensure-drain-scheduled!
  "Enqueue `envelope` into the target incarnation's queue and, when this call is
  the one that flips `:scheduled?`, arm the async drain. Returns `true` iff the
  envelope was ACTUALLY enqueued (whether or not this call also scheduled the
  drain), `false` when the target-liveness / incarnation guard fenced the
  enqueue out under the router monitor. rf2-a2x2w: `dispatch!` reads that signal
  to recover-but-emit exactly once for a captured op that lost its pinned
  incarnation in the post-token-match / pre-enqueue window — the `false` return
  distinguishes \"never enqueued\" from \"enqueued but drain already scheduled\"
  (both leave `:scheduled?` unflipped), so the caller never double-emits."
  [frame-id frame-record router envelope continue?]
  (let [outcome
        (locking router
          ;; Target-liveness and enqueue linearize against destroy claim's
          ;; queue cutoff under this same router monitor. `continue?` also
          ;; fences the originating event for explicit cross-frame dispatch.
          (when (and (continue?)
                     (frame/frame-incarnation-live?
                       frame-id (:drain-lock frame-record)))
            (let [{:keys [scheduled?]} @router]
              (swap! router
                     (fn [state]
                       (-> state
                           (update :queue
                                   (fn [queue]
                                     (if (:rf.machine/internal? envelope)
                                       (front-insert-machine-internal queue envelope)
                                       (conj queue envelope))))
                           (assoc :scheduled? true))))
              {:enqueued? true :schedule? (not scheduled?)})))]
    (when (:schedule? outcome)
      ;; The scheduled callback belongs to the exact target record that
      ;; accepted the envelope. Never let an obsolete A callback become
      ;; an eager drain of a fresh same-id B.
      (interop/next-tick
        (fn []
          (drain-try! frame-id frame-record))))
    (boolean (:enqueued? outcome))))

(defn- emit-dispatched-trace!
  "Emit the :rf.event :rf.event/dispatched trace event for this envelope. Per
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
  registration meta and short-circuit the `:rf.event/dispatched` emit
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
  ([envelope sync?]
   (emit-dispatched-trace! envelope sync? (constantly true)))
  ([envelope sync? continue?]
  (when (continue?)
    (let [event        (:event envelope)
        event-id     (when (vector? event) (first event))
        ;; The `:rf.trace/no-emit?` gate reads the TARGET handler's meta from
        ;; the registrar. Dev-only (this whole emit DCEs under `goog.DEBUG=false`).
        ;;
        ;; rf2-x76af2.25: resolve the meta through the TARGET frame's image
        ;; generation, NOT via a BARE `registrar/lookup` (which runs at enqueue
        ;; time OUTSIDE the `call-with-frame-resolution` binding that wraps
        ;; `process-event!`). An image-loaded frame's inline `:reg-event` handler
        ;; lives ONLY in the frame's generation resolver, and its inline
        ;; descriptor can carry `:rf.trace/no-emit?` in `:metadata`; a bare
        ;; lookup is generation-blind and MISSES it → `no-emit?` false → the
        ;; `:rf.event/dispatched` trace floods the very stream the handler is
        ;; marked to stay out of (the flood rf2-qsjda closed for registrar-
        ;; registered handlers, previously still open for image-registered
        ;; ones). Mirror how `process-event!` resolves handlers — one extra
        ;; record read on the dev path; absence-is-default for a non-image
        ;; frame binds nothing and resolves through the registrar atom exactly
        ;; as the bare lookup did.
         handler-meta (when (and event-id (continue?))
                        (try
                          (live-frame/call-with-frame-resolution
                            (:frame envelope)
                            (fn [] (registrar/lookup :event event-id)))
                          (catch #?(:clj Throwable :cljs :default) e
                            ;; Resolution is callback-bearing. A destroy+throw
                            ;; is inert; otherwise preserve the existing error.
                            (when (continue?) (throw e)))))
         no-emit?     (trace/no-emit?-from-meta handler-meta)]
    (when (and (continue?) (not no-emit?))
      (trace/with-call-site (:call-site envelope)
        (trace/call-with-continuation-predicate
          continue?
          (fn []
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
    (continue?)))))

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

;; Private authority for the synchronous `:on-destroy` event and the
;; same-frame child dispatches it intentionally emits. The cascade drains an
;; isolated router, never the dying frame's real queue. The value carries BOTH
;; the incarnation claim token and the actual host thread that entered the
;; cascade: JVM `bound-fn` may convey dynamic bindings to an executor, but it
;; cannot make that executor thread identical to `:owner`. A callback that
;; outlives teardown also loses authority when the claim is compare-removed.
(def ^:dynamic ^:private *frame-destroy-cascade* nil)

(defn- frame-destroy-cascade-router
  "Return the isolated teardown router when this call is an authorised
  same-frame child dispatch; nil for every ordinary dispatch."
  [frame-id]
  (when-let [{cascade-frame :frame
              token         :token
              router        :router
              owner         :owner} *frame-destroy-cascade*]
    (when (and (= cascade-frame frame-id)
               #?(:clj  (identical? owner (Thread/currentThread))
                  :cljs (true? owner))
               (frame/frame-incarnation-closing? frame-id token))
      router)))

(defn dispatch!
  "Append the event to the target frame's router queue. Per Spec 002:
  FIFO at the runtime layer. The drain loop normally picks it up in this same
  drain cycle (run-to-completion); a successful exact-incarnation destroy
  claim is a terminal cutoff for ordinary work.

  Per rf2-j20a7 / Spec 005 §Level 4: the single exception to FIFO is a
  machine-internal continuation event (a dispatch emitted from a
  machine's own processing), which `enqueue-envelope!` inserts at the
  FRONT of the queue so the machine settles its macrostep before the
  next external event. The cut is the dispatch's ORIGIN (machine
  processing), not its target — an event that merely targets a machine
  but originates from user code / the UI / a non-machine effect stays
  FIFO at the back.

  Per rf2-ts1a: the runtime-callable fn form (rf2-m90brg: THIS fn is now
  also the direct public-API-terms target — the `dispatch` macro's
  expansion calls it fully-qualified, and the CLJS same-name `dispatch`
  `def`-alias in `re-frame.core` points straight here). The macro form
  `re-frame.core/dispatch` stamps an `:rf.trace/call-site` onto `opts` at
  compile time; from there it rides the envelope and gets bound around the
  handler chain's invocation in `process-event!`.

  Canonical `event` shape is `[<id>]`, `[<id> <single-scalar>]`, or
  `[<id> <map>]` — best practice, not enforced. Variadic vectors are
  tolerated for v1-migration / caller convenience. See spec/Conventions.md
  §Canonical event-vector shape."
  ([event] (dispatch! event {}))
  ([event opts]
   (let [owner-token    (frame/current-event-owner-token)
         owner-frame-id (frame/current-event-owner-frame-id)
         owner-live?    #(or (nil? owner-token)
                             (and owner-frame-id
                                  (frame/event-owner-live?
                                    owner-frame-id)))]
     ;; A callback is allowed to keep executing after it destroys A, but a
     ;; dispatch it issues afterward is framework-owned tail and therefore
     ;; inert before envelope/target resolution begins.
     (when (owner-live?)
       (when-let [envelope  (build-envelope event opts)]
        (let [
             frame-id       (:frame envelope)
             frame-record   (when (owner-live?) (frame/frame frame-id))
             target-token   (:drain-lock frame-record)
             ;; rf2-dlld6: the EXACT incarnation a `capture-frame` op pinned at
             ;; capture (its `:drain-lock`), carried onto the envelope by
             ;; `build-envelope`. nil for every ordinary / address-directed
             ;; dispatch — the fence below is inert then.
             expected-incarnation (:rf.frame/expected-incarnation envelope)
             ;; rf2-a2x2w: the operation realm to carry onto EVERY late captured-
             ;; op rejection below (`:dispatch` — this is `dispatch!`), so
             ;; `error-emit/error-source-coord` resolves the `:source-coord`
             ;; under the EXACT `[:event id]` realm rather than the realm-
             ;; ambiguous `[:sub]`-then-`[:event]` fallback (7xlvt's mechanism,
             ;; extended from the pre-check seam to the router's late fences).
             ;; nil for an ordinary / address-directed dispatch (no captured
             ;; incarnation) — those keep the legacy fallback, unchanged.
             capture-op     (when (some? expected-incarnation) :dispatch)
             target-live?   #(and (owner-live?)
                                  (frame/frame-incarnation-live?
                                    frame-id target-token))
             cascade-router (frame-destroy-cascade-router frame-id)]
         (cond
       (not (owner-live?))
       nil

       (nil? frame-record)
       ;; Per rf2-2hvga (= B + recover-but-emit): dispatch into a
       ;; destroyed / unknown frame RECOVERS (no-op — the event is not
       ;; enqueued) AND emits a production-survivable
       ;; `:rf.error/frame-destroyed` via the always-on listener (axis 1).
       ;; The call-site is bound so the DEV trace path inside
       ;; `emit-frame-destroyed!` carries it; the always-on record reads
       ;; its coords off the parallel error-coord registry, not the
       ;; dynamic call-site. rf2-a2x2w: `capture-op` carries `:dispatch` when
       ;; this nil-record rejection is a CAPTURED op whose pinned frame is now
       ;; fully unclaimed (realm-exact `[:event id]`); nil for an ordinary
       ;; address-directed dispatch (legacy fallback — unchanged).
       (trace/with-call-site (:call-site envelope)
         (emit-frame-destroyed! (first event) event (:frame envelope) capture-op))

       ;; rf2-dlld6: a captured op pinned to incarnation A resolved a same-id
       ;; successor B here — A was destroyed and B installed in the window
       ;; between `capture-frame`'s liveness pre-check and this bare-id resolve.
       ;; `target-token` is B's `:drain-lock`, read off the SAME record we would
       ;; enqueue into, so this mismatch IS the exact-incarnation check fused
       ;; with target consumption (no second liveness-check-to-bare-id-use
       ;; window). Recover-but-emit `:rf.error/frame-destroyed` and enqueue
       ;; NOTHING — A's authority never leaks into B. Identical recover-but-emit
       ;; to the nil-record clause above; the address-directed path (nil
       ;; `expected-incarnation`) is untouched. rf2-a2x2w: `capture-op`
       ;; (`:dispatch`, always present here — this branch tests `some?
       ;; expected-incarnation`) rides the emit so the resolved `:source-coord`
       ;; names the EXACT `[:event id]` realm, never the realm-ambiguous
       ;; fallback that would steal a same-keyword sub's coord.
       (and (some? expected-incarnation)
            (not (identical? expected-incarnation target-token)))
       (trace/with-call-site (:call-site envelope)
         (emit-frame-destroyed! (first event) event (:frame envelope) capture-op))

       cascade-router
       ;; Cleanup descendants stay on the private teardown queue. No scheduler
       ;; is involved, and no ordinary/racing envelope can join this cascade.
       (let [cascade-live? #(and (owner-live?)
                                 (identical? cascade-router
                                             (frame-destroy-cascade-router
                                               frame-id)))]
         (when (emit-dispatched-trace! envelope false cascade-live?)
           (when (cascade-live?)
             (enqueue-envelope! cascade-router envelope))))

       :else
       (let [router    (:router frame-record)
             ;; `ensure-drain-scheduled!` returns true iff the envelope was
             ;; actually enqueued into the target incarnation's queue under the
             ;; router monitor. `emit-dispatched-trace!` returns `(target-live?)`,
             ;; so a target lost BEFORE the trace short-circuits the `when` and
             ;; leaves `enqueued?` nil.
             enqueued? (when (emit-dispatched-trace! envelope false target-live?)
                         (ensure-drain-scheduled! frame-id frame-record router
                                                  envelope target-live?))]
         ;; rf2-a2x2w (gap 2, async): a CAPTURED dispatch that passed the exact-
         ;; incarnation token comparison above but then lost its pinned target A
         ;; in the window before the enqueue linearized (the incarnation guard
         ;; fenced it out) would otherwise SILENTLY return — B untouched, but no
         ;; diagnostic. Recover-but-emit exactly once: the enqueue never happened,
         ;; so this is the SOLE emit for the rejection, realm-exact via
         ;; `capture-op`. An ordinary address-directed dispatch (nil `capture-op`)
         ;; stays silent on this benign post-resolve teardown race, unchanged.
         ;;
         ;; rf2-iqfbg: a falsey `enqueued?` alone is NOT proof the captured
         ;; TARGET was destroyed. `target-live?` — the predicate the enqueue path
         ;; guards on — FUSES `owner-live?` with target-incarnation liveness, so
         ;; the enqueue also fences out when only the ORIGINATING event owner
         ;; died: a benign continuation cutoff (framework-owned tail after the
         ;; owner destroyed itself) that leaves the captured target A fully live.
         ;; Emit ONLY when the target incarnation ITSELF is gone. Owner-
         ;; continuation cutoff is the HIGHER-PRIORITY silent outcome — checked
         ;; FIRST via `(owner-live?)` — so a dead owner is never misreported as a
         ;; destroyed (still-live) target frame. Genuine target loss (owner still
         ;; live, the pinned incarnation no longer live) still recover-but-emits
         ;; exactly once. Both re-reads are monotonic for a fixed token/owner
         ;; (a destroyed incarnation never revives under the same token), so the
         ;; final decision is stable.
         (when (and capture-op
                    (not enqueued?)
                    (owner-live?)
                    (not (frame/frame-incarnation-live? frame-id target-token)))
           (trace/with-call-site (:call-site envelope)
             (emit-frame-destroyed! (first event) event (:frame envelope)
                                    capture-op))))))))
     nil)))

(defn dispatch-sync!
  "Bypass the queue scheduler and process this single event end-to-end
  immediately, then normally drain synchronously-enqueued events to fixed
  point. A depth halt or exact-incarnation destroy claim terminates that
  ordinary drain without inserting a render. Per Spec 002 §dispatch-sync: this is for outside-the-runtime
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
   (let [owner-token    (frame/current-event-owner-token)
         owner-frame-id (frame/current-event-owner-frame-id)
         owner-live?    #(or (nil? owner-token)
                             (and owner-frame-id
                                  (frame/event-owner-live?
                                    owner-frame-id)))]
    (when (owner-live?)
     (when-let [envelope (build-envelope event opts)]
      (let [frame-record (when (owner-live?)
                           (frame/frame (:frame envelope)))
         target-token (:drain-lock frame-record)
         target-live? #(and (owner-live?)
                            (frame/frame-incarnation-live?
                              (:frame envelope) target-token))
         ;; Read the call-site from the envelope (already gated in
         ;; build-envelope) so the synchronous error emits below can
         ;; carry it without referencing the keyword a second time.
         call-site    (:call-site envelope)
         ;; rf2-dlld6: the EXACT incarnation a `capture-frame` op pinned at
         ;; capture (its `:drain-lock`), carried onto the envelope by
         ;; `build-envelope`. nil for every ordinary / address-directed
         ;; dispatch-sync — the fence below is inert then.
         expected-incarnation (:rf.frame/expected-incarnation envelope)
         ;; rf2-a2x2w: the operation realm carried onto EVERY late captured-op
         ;; rejection below (`:dispatch-sync` — this is `dispatch-sync!`), so
         ;; `error-emit/error-source-coord` resolves the `:source-coord` under
         ;; the EXACT `[:event id]` realm (a dispatch-sync shares the dispatch
         ;; event realm) rather than the realm-ambiguous fallback. nil for an
         ;; ordinary / address-directed dispatch-sync — legacy fallback,
         ;; unchanged.
         capture-op   (when (some? expected-incarnation) :dispatch-sync)
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
             (or (:in-sync-drain? router-state) same-thread-drain?)))
         ;; Per rf2-x76af2.22 (b): this thread already OWNS the frame's drain
         ;; serialization via a COLD `frame/call-serialized-with-drain!`
         ;; critical section (`:serialized-holder` = this thread) but is NOT
         ;; the active drainer. A dispatch-sync issued from inside such a thunk
         ;; (e.g. a Tool-Pair state write, or a lifecycle op reached from a
         ;; serialized thunk) must RE-ENTER the drain directly — it already
         ;; holds `:drain-lock`, so routing through `drain-block!`'s spin-CAS-
         ;; acquire would deadlock against itself. Distinct from `nested-sync?`
         ;; (a dispatch-sync from inside a running HANDLER, which stays the
         ;; `:rf.error/dispatch-sync-in-handler` error): the cold holder is not
         ;; a handler, so its dispatch-sync runs. Mirrors
         ;; `frame/current-thread-owns-drain-serialization?` for the cold axis;
         ;; on CLJS the marker is `true`/nil and the same equality
         ;; discriminates. Checked only when NOT `nested-sync?` so the drainer
         ;; axis wins if a thread were ever both.
         reentrant-cold?
         (when (and frame-record (not nested-sync?))
           (let [holder @(:serialized-holder frame-record)]
             #?(:clj  (identical? holder (Thread/currentThread))
                :cljs (true? holder))))]
     (cond
       (not (owner-live?))
       nil

       (nil? frame-record)
       ;; Per rf2-2hvga (= B + recover-but-emit): dispatch-sync into a
       ;; destroyed / unknown frame RECOVERS (no-op) AND emits the
       ;; production-survivable `:rf.error/frame-destroyed` through the
       ;; always-on listener. rf2-a2x2w: `capture-op` carries `:dispatch-sync`
       ;; when this nil-record rejection is a CAPTURED op whose pinned frame is
       ;; now fully unclaimed (realm-exact `[:event id]`); nil for an ordinary
       ;; address-directed dispatch-sync (legacy fallback — unchanged).
       (trace/with-call-site call-site
         (emit-frame-destroyed! (first event) event (:frame envelope) capture-op))

       ;; rf2-dlld6: a captured op pinned to incarnation A resolved a same-id
       ;; successor B here — A destroyed + B installed between `capture-frame`'s
       ;; liveness pre-check and this bare-id resolve. `target-token` is B's
       ;; `:drain-lock`, read off the SAME record we would seed the drain from,
       ;; so this mismatch fuses the exact-incarnation check with target
       ;; consumption (no liveness-check-to-bare-id-use window). Recover-but-emit
       ;; and process NOTHING, so A's authority never leaks into B. Placed before
       ;; the `nested-sync?` / drain clauses so a superseded capture never enters
       ;; B's drain. Address-directed dispatch-sync (nil expected) is untouched.
       ;; rf2-a2x2w: `capture-op` (`:dispatch-sync`, always present here) rides
       ;; the emit so the resolved `:source-coord` names the EXACT `[:event id]`
       ;; realm, never the realm-ambiguous fallback.
       (and (some? expected-incarnation)
            (not (identical? expected-incarnation target-token)))
       (trace/with-call-site call-site
         (emit-frame-destroyed! (first event) event (:frame envelope) capture-op))

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
         (when (and (owner-live?) interop/debug-enabled?)
           (when-let [other-id (diag/other-frame-mid-drain (:frame envelope))]
             (diag/emit-cross-frame-warning! (:frame envelope) other-id event)))
         (let [drained?
         (when (emit-dispatched-trace! envelope true target-live?)
          (when (target-live?)
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
           ;; the drain returns.
           ;;
           ;; Per rf2-x76af2.22 (b): when this thread already holds the cold
           ;; serialization (`reentrant-cold?`), route through
           ;; `drain-reentrant!` instead — it runs the SAME seed-push + drain
           ;; loop but WITHOUT re-acquiring or releasing `:drain-lock` (this
           ;; thread already holds it; the outer cold section drops it), so a
           ;; nested dispatch-sync re-enters rather than spin-CAS-deadlocking
           ;; on its own lock.
           (let [seed-push (fn []
                             (swap! router (fn [{:keys [queue] :as r}]
                                             (assoc r
                                                    :queue (into interop/empty-queue
                                                                 (cons envelope queue))
                                                    :scheduled?     true
                                                    :in-sync-drain? true))))]
             (if reentrant-cold?
               (drain-reentrant! (:frame envelope) frame-record seed-push)
               (drain-block!     (:frame envelope) frame-record seed-push)))
            (finally
              (swap! router assoc :in-sync-drain? false)))))]
           ;; rf2-a2x2w (gap 2, sync): a CAPTURED dispatch-sync that passed the
           ;; exact-incarnation token comparison but then lost its pinned target A
           ;; before the synchronous drain-lock acquire — `drain-block!` CAS-
           ;; acquires, re-checks incarnation liveness, and on a lost A resets the
           ;; lock WITHOUT running the seed-push (returns falsey) — would otherwise
           ;; SILENTLY return. Recover-but-emit exactly once: the seed-push never
           ;; ran, so this is the SOLE emit for the rejection, realm-exact via
           ;; `capture-op`. An ordinary address-directed dispatch-sync (nil
           ;; `capture-op`) stays silent on the benign post-resolve teardown
           ;; race, unchanged.
           ;;
           ;; rf2-iqfbg: a falsey `drained?` alone is NOT proof the captured
           ;; TARGET was destroyed. `target-live?` — which both the trace and the
           ;; inner drain guard test — FUSES `owner-live?` with target-incarnation
           ;; liveness, so the drain also fences out when only the ORIGINATING
           ;; event owner died: a benign continuation cutoff that leaves the
           ;; captured target A fully live. Emit ONLY when the target incarnation
           ;; ITSELF is gone. Owner-continuation cutoff is the HIGHER-PRIORITY
           ;; silent outcome — checked FIRST via `(owner-live?)` — so a dead owner
           ;; is never misreported as a destroyed (still-live) target frame.
           ;; Genuine target loss (owner still live, the pinned incarnation no
           ;; longer live) still recover-but-emits exactly once.
           (when (and capture-op
                      (not drained?)
                      (owner-live?)
                      (not (frame/frame-incarnation-live?
                             (:frame envelope) target-token)))
             (trace/with-call-site call-site
               (emit-frame-destroyed! (first event) event (:frame envelope)
                                      capture-op))))))
      nil)))
    nil)))

(defn- run-frame-destroy-event!
  "Run one claimed incarnation's `:on-destroy` event and its synchronous
  same-frame queued descendants to fixed point.

  This is deliberately NOT `dispatch-sync!` privilege. Destruction first cuts
  off the frame's real queue, then this function creates an isolated queue for
  the cleanup seed. `dispatch!` routes same-frame descendants to that local
  queue only while all three authority checks hold: the exact claim token is
  still current, the target frame matches, and the actual host thread is the
  entering thread. Ordinary queued work can therefore neither run behind the
  cleanup seed nor inherit authority through JVM `bound-fn` propagation.

  The real router is marked as draining for an out-of-drain destroy so a generic
  same-frame `dispatch-sync!` issued by cleanup remains the normal
  `:rf.error/dispatch-sync-in-handler`; when destroy was called by an active
  handler, its existing drainer marker is preserved."
  [frame-id expected-token event]
  (frame/call-with-event-owner-token
    frame-id expected-token true
    (fn []
      (trace/call-with-terminal-continuation-predicate
        #(frame/frame-incarnation-closing? frame-id expected-token)
        (fn []
  (frame/call-serialized-with-drain!
    frame-id
    (fn []
      (when-let [frame-record (frame/frame frame-id)]
        (when (and (identical? expected-token (:drain-lock frame-record))
                   (frame/frame-incarnation-closing? frame-id expected-token))
          (let [real-router     (:router frame-record)
                drain-depth    (get (:config frame-record) :drain-depth
                                    drain-depth-default)
                envelope       (build-envelope event {:frame frame-id})
                teardown-router (atom {:queue            (conj interop/empty-queue
                                                               envelope)
                                       :scheduled?       true
                                       :in-drain?        nil
                                       :in-sync-drain?   false})
                already-drainer?
                #?(:clj  (identical? (:in-drain? @real-router)
                                     (Thread/currentThread))
                   :cljs (true? (:in-drain? @real-router)))]
            (when-not already-drainer?
              (mark-drainer! real-router))
            (try
              (let [authority? #(and (identical?
                                       expected-token
                                       (:drain-lock (frame/frame frame-id)))
                                     (frame/frame-incarnation-closing?
                                       frame-id expected-token))]
                (when (emit-dispatched-trace! envelope true authority?)
                  (when (authority?)
                    (binding [*frame-destroy-cascade*
                              {:frame  frame-id
                               :token  expected-token
                               :router teardown-router
                               :owner  #?(:clj  (Thread/currentThread)
                                          :cljs true)}]
                      (run-one-pass! frame-id frame-record teardown-router
                                     drain-depth expected-token)))))
              (finally
                (when-not already-drainer?
                  (clear-drainer! real-router)))))))))))))
  nil)

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
(late-bind/set-fn! :router/run-frame-destroy-event! run-frame-destroy-event!)

;; Per rf2-x76af2.22 (a): re-kick a fresh async drain for `frame-id`.
;; `frame/call-serialized-with-drain!`'s COLD release calls this — through
;; the late-bind seam (frame.cljc cannot `:require` router — load cycle) —
;; when it finds the queue non-empty on release, because a `dispatch!` that
;; arrived during the cold hold scheduled a `drain-try!` that CAS-lost to the
;; cold holder and gave up (no drainer left to re-check). Schedules the drain
;; UNCONDITIONALLY (no `:scheduled?` gate — the point is to re-establish a
;; live `drain-try!` for the still-true `:scheduled?` flag); a redundant
;; `drain-try!` merely CAS-loses and returns, so re-kicking when one is
;; already pending is harmless. Mirrors `ensure-drain-scheduled!`'s scheduling
;; action.
(late-bind/set-fn! :router/reschedule-drain!
                   (fn reschedule-drain! [frame-id frame-record]
                     (interop/next-tick
                       (fn [] (drain-try! frame-id frame-record)))))
