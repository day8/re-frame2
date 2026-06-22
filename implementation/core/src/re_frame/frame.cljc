(ns re-frame.frame
  "Frame container, lifecycle, and the frame registry. Per Spec 002.

  A frame is an isolated runtime boundary identified by a keyword. Every
  frame holds its own app-db (a substrate-managed reactive container),
  its own per-frame router queue, and its own sub-cache.

  Frames are not values — they are mutable runtime objects. User code
  holds keywords; this namespace holds the frame records.

  Reserved frame ids:
    :rf/default              — an ORDINARY frame id (per Spec 002 §`:rf/default`
                              is an ordinary id). It carries NO
                              framework privilege: the runtime never creates
                              it, never infers it from a missing stamp, and
                              never uses it as a resolution floor. A small
                              app, example, or test may register and select
                              it EXPLICITLY like any other id.
    :rf.frame/<gensym>       — anonymous instances from make-anon-frame-record!"
  (:require [clojure.string]
            [re-frame.error :as error]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the frame record -----------------------------------------------------
;;
;; Per Spec 002 §What lives in a frame, a frame is a map with:
;;   :id          the keyword identity
;;   :frame-state the ONE physical durable container (opaque; through adapter)
;;                — holds BOTH partitions as a frame-state value
;;                `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`.
;;   :app-db      the app-db PROJECTION REACTION over :frame-state
;;                (`make-derived-value [frame-state] :rf.db/app`). Read-only —
;;                layer-1 app subs read it; writes go through the frame-state
;;                container, never `replace-container!` on this projection.
;;   :runtime-db  the runtime-db PROJECTION REACTION over :frame-state
;;                (`make-derived-value [frame-state] :rf.db/runtime`). Read-only
;;                — framework subs read it.
;;   :router      per-frame queue + drain-state FSM (defined in router.cljc)
;;   :sub-cache   per-frame sub-cache (defined in subs.cljc)
;;   :lifecycle   {:created-at :destroyed? :listeners}
;;   :config      the metadata that reg-frame was given
;;
;; Per Spec 002 §One physical container, two projection reactions + Spec 006
;; §Frame-state container and partition projections:
;; the frame holds ONE physical frame-state container; app-db and runtime-db
;; are PROJECTION REACTIONS over it. Partition-aware sub-cache invalidation
;; falls out of `make-derived-value`'s memoised `=`-equality — NO dirty flags:
;; a runtime-only commit recomputes the app-db projection,
;; finds `:rf.db/app` `=`, and does not propagate to app subs; an app-only
;; commit is symmetric.
;;
;; Frame records are stored in `frames` keyed by id.
;;
;; The two reserved partition keys inside the physical frame-state value.
;; `:rf.db/app` is the app-db partition slot; `:rf.db/runtime` is the
;; runtime-db partition slot (per Spec 002 §The two-partition frame contract
;; and Conventions §Reserved partition keys). Held here as the single source
;; of truth for the commit + projection machinery in this ns.

(def ^:const app-partition-key
  "Reserved frame-state key naming the app-db partition (`:rf.db/app`)."
  :rf.db/app)

(def ^:const runtime-partition-key
  "Reserved frame-state key naming the runtime-db partition (`:rf.db/runtime`)."
  :rf.db/runtime)

;; ---- runnable frame OBJECT marker + target normalization ------------------
;;
;; `re-frame.live-frame/make-frame` returns a SINGLE runnable image-loaded
;; frame OBJECT — a map carrying the resolved image generation AND a reference
;; (`:rf.frame/runnable-id`) to the backing runnable RECORD this ns owns in
;; `frames`. The object IS the public live frame; its runnable interior
;; (app-db / runtime-db / queue / sub-cache / lifecycle) is the record reached
;; by the runnable-id (EP-0023 §Frame — "the live frame object owns app-db,
;; runtime-db, event queue and drain state, subscription cache, ... a reference
;; to the resolved image generation it is running").
;;
;; The PUBLIC target a dispatch / subscribe / destroy / app-db read addresses is
;; a frame — usually a frame id KEYWORD (the public routing address), sometimes
;; a frame VALUE the lifecycle APIs return (EP-0024 Operation target grammar —
;; "the API teaches one routing address: the frame id"; internal normalization
;; also accepts a frame value for tests/tools). Every runnable subsystem
;; resolves per-frame state through a frame-id ADDRESS keyed into `frames` (the
;; ONE registry — the universal chokepoint: the router queue/drain,
;; `commit-frame-transition!`, the sub-cache, cofx, elision, …). So a frame
;; VALUE target is normalized to its id at the public entry, and every
;; bare-`frame-id`-keyed operation downstream is identical.
;;
;; The frame VALUE is the live lifecycle token `make-frame` returns. Its
;; representation is NOT an app-facing data contract: it carries the
;; `:rf.frame/object` marker (so a value target is discriminated structurally
;; from a keyword id) and `:rf.frame/runnable-id` (= the frame id its record is
;; keyed by in the one `frames` registry). The resolved image generation is NOT
;; embedded on the value — it lives on the record (the `:generation` slot), read
;; by id. `frame-value->id` is the single public accessor from a frame value to
;; its id (EP-0024 Open Issue #2 — representation hidden).

(def ^:const object-marker
  "Reserved frame-value marker key. A `true` value at this key on a map means
  \"this is a live frame VALUE\" (EP-0024 Term: Frame value) — the structural
  discriminator a target-resolution site uses to tell a frame value from a
  frame-id keyword. The frame value's representation is not an app-facing data
  contract; this marker is internal."
  :rf.frame/object)

(def ^:const runnable-id-key
  "Reserved key on a frame VALUE naming the frame id its record is keyed by in
  the one `frames` registry (EP-0024). For an `:id`-bearing value
  this equals the public `:rf.frame/id`; for a no-id (direct) value it is a
  process-unique `:rf.frame/<gensym>` so the value is still runnable (its record
  is addressable) while bypassing the PUBLIC frame-id space (EP-0024 — direct
  frame values are local-only tokens)."
  :rf.frame/runnable-id)

(defn frame-value?
  "True when `x` is a live frame VALUE (`make-frame`'s return token — carries the
  `:rf.frame/object` marker), as opposed to a frame-id keyword. The structural
  discriminator a target-resolution site uses (EP-0024 Term: Frame value). Pure."
  [x]
  (boolean (and (map? x) (get x object-marker))))

(defn frame-value->id
  "The single public accessor from a frame VALUE to its frame id (EP-0024 Open
  Issue #2 — \"provide one accessor frame value → id; do not expose the
  representation\"). Returns the frame id a frame value routes to (its
  `:rf.frame/id` when created with one, else its private `:rf.frame/<gensym>`
  runnable id). Passing a frame-id keyword returns it unchanged, so callers can
  always pass a value or an id to this accessor. Pure."
  [frame-value]
  (if (frame-value? frame-value)
    (get frame-value runnable-id-key)
    frame-value))

(defn frame-target->id
  "Normalize a public frame TARGET — a frame-id KEYWORD or a frame VALUE — to the
  frame id its record is keyed by in the one `frames` registry (EP-0024). A
  frame VALUE (carrying `:rf.frame/object true`) yields its
  `:rf.frame/runnable-id`; any other target (a keyword id, or a nil / malformed
  value) is returned UNCHANGED — so every keyword-target caller is
  byte-identical. The internal normalization seam dispatch / subscribe / destroy
  / app-db-read funnel a frame value through before keying `frames`; the public
  API teaches the frame id, the value is accepted for tests/tools. Pure — the
  same normalization as `frame-value->id`."
  [target]
  (frame-value->id target))

(defn anon-frame-id
  "Mint a process-unique anonymous frame-id under the reserved `:rf.frame/`
  namespace — the address a no-id frame's record is keyed by. So tooling that
  filters `:rf.frame/*` ids sees no-id frame values + gensym instances
  uniformly. INTERNAL — used by `make-frame` for a value created without `:id`."
  []
  (keyword "rf.frame" (str (gensym ""))))

(defonce
  ^{:doc "Map of frame-id → frame-record. Per-process (one global frame
  registry), keyed by the bare frame-id keyword. A frame is addressed by its
  process-local id."}
  frames
  (atom {}))

;; ---- frame address — the bare frame-id ------------------------------------
;;
;; A frame is addressed by its process-local frame-id keyword. The registry key
;; is the bare id — no realm coordinate threads the lookup, the `swap! frames
;; assoc`, or any tool's `@frames` read.

;; ---- destroy-in-flight guard ---------------------------------------------
;;
;; Tracks frame-ids whose `destroy-frame!` call is currently mid-flight so
;; a re-entrant `(destroy-frame! id)` from inside the same id's
;; `:on-destroy` handler (or downstream teardown hook) is a silent no-op.
;; Without this guard a re-entrant destroy would recursively re-enter
;; teardown — re-firing `:on-destroy`, re-running the machine cascade,
;; re-disposing the sub-cache — and likely throw on a half-torn-down
;; frame. Per Spec 002 §Destroy — re-entrant destroy is idempotent.

(defonce ^:private destroying-frames
  (atom #{}))

;; Monotonic counter for the per-destroy UNIQUE transient `:on-destroy`-throw
;; capture listener key. `fire-on-destroy-event!` installs a listener on the
;; always-on error-emit registry for the duration of the `:on-destroy`
;; dispatch; the registry keys by id (assoc/dissoc). The listener key MUST be
;; UNIQUE per invocation: an OVERLAPPING / NESTED destroy — a Spec 002
;; supported shape: an `:on-destroy` handler destroying a DIFFERENT frame —
;; would otherwise REPLACE the outer destroy's listener under a shared key,
;; then DROP it on the inner's finally, so the outer's
;; `:rf.error/handler-exception` is never captured and its dedicated
;; `:rf.error/on-destroy-handler-exception` discriminator is silently lost. A
;; fresh per-invocation key gives each (possibly nested) destroy its own
;; listener — no clobber, no cross-removal. `defonce` so a hot reload does not
;; rewind the counter mid-flight.
(defonce ^:private on-destroy-watch-counter
  (atom 0))

;; Monotonic counter for the per-step UNIQUE transient setup-step-failure
;; capture listener key (EP-0027 §Failure, strict construction). `run-setup-
;; events!` installs a listener on the always-on error-emit registry for the
;; duration of EACH `:initial-events` setup-step dispatch so an IN-BAND failure
;; — a handler-body throw the interceptor chain catches and surfaces as
;; `:rf.error/handler-exception` (the `[:rf/set-db x]` bad-arg case, post
;; rf2-izy3b2), or any other `:rf.error/*` recorded against THIS frame (a
;; coeffect / interceptor / flow throw the chain captures rather than re-
;; raising) — is detected even though `dispatch-sync!` returns nil normally.
;; The registry keys by id (assoc/dissoc); the key MUST be UNIQUE per step so a
;; setup step that itself constructs / tears down another frame (whose own
;; transient listener races) cannot clobber this step's listener under a shared
;; key. `defonce` so a hot reload does not rewind the counter mid-flight.
(defonce ^:private setup-step-watch-counter
  (atom 0))

;; ---- frame resolution at call sites — the carried invariant ---------------
;;
;; Per Spec 002 §Frame target resolution — the carried invariant (EP-0002):
;; **frame identity is carried, not found.** A frame-scoped operation reads
;; its frame from the causal token it holds — the dynamic scope a `with-frame`
;; / frame-provider established, or a frame stamp it captured. It never
;; *synthesises* one from absence: there is no process-global `:rf/default`
;; floor that catches operations issued under no scope at all.
;;
;; The rationale leads with **replay determinism + temporal non-locality**,
;; NOT purity (per EP-0002 §Resolved Decisions R1-R7):
;;
;;   - A silently-defaulted frame poisons replay — `restore-epoch!`,
;;     time-travel, and Story / Causa determinism all become unsound the
;;     moment an operation's target depends on which frame happened to be
;;     ambient rather than on a value carried in the token being replayed.
;;   - "sole live frame" is true only until a second frame appears, so an
;;     ambient floor would let adding Xray, Story, or an SSR frame silently
;;     change the meaning of distant, untouched application code (temporal
;;     non-locality).
;;
;; The surface is split deliberately (Spec 002 §Resolver surface):
;;
;;   - `current-frame` / `resolve-current-frame` are **readers** — they
;;     return the scope frame or **nil**. They never repair absence. Low-
;;     level detection, frame pickers, and tooling model "no context" with
;;     the nil return without throwing.
;;   - `require-current-frame!` is the **requiring** primitive — "read the
;;     stamp on the token I hold". It returns the frame stamp or, when the
;;     token carries none, raises/emits `:rf.error/no-frame-context`.
;;     Public frame-scoped operations call THIS so the nil-returning reader
;;     never silently becomes a second, softer fallback.
;;
;; `*current-frame*` is the dynamic var that `with-frame` (and the router's
;; per-handler binding) sets — the *scope* carrier. It is nil at top of
;; stack and after any async hop unwinds the binding.

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Return the lexical/dynamic-scope frame, or **nil** when no scope is
  established. A **reader**: it reports what scope is in effect; it does
  NOT repair absence by synthesising `:rf/default` (per Spec 002 §Frame
  target resolution — the carried invariant, EP-0002). The dynamic-var
  tier only — the React-context tier is consulted by
  `resolve-current-frame` (CLJS). Public frame-scoped operations that must
  have a frame call `require-current-frame!`, not this reader."
  []
  *current-frame*)

;; Per Spec 009 §Per-frame trace rings: publish the in-flight frame-id through
;; `late-bind` so the trace tooling sibling can route emit-site trace events to
;; their owning frame's ring. Returns nil when no cascade is in flight
;; (frameless emits). The hook is sticky and read on every push-to-ring!.
(late-bind/set-fn! :frame/current-frame-id (fn [] *current-frame*))

(defn resolve-current-frame
  "Resolve the active frame at a no-explicit-frame call site — the
  dynamic-or-adapter/React-context scope frame, or **nil** when no scope
  is established. A **reader**: it never repairs absence by synthesising
  `:rf/default` (per Spec 002 §Frame target resolution — the carried
  invariant, EP-0002). The two scope tiers it observes:

    1. `*current-frame*` (dynamic var) — set by `with-frame` /
       `frame-bound-fn` / the router's per-handler binding.
    2. The closest enclosing frame-provider via React context (CLJS).

  On CLJS this consults the `:adapter/current-frame` late-bind hook so
  the React-context tier is LIVE — adapters publish their React-context-
  aware impl through the hook at ns-load time. That impl returns nil when
  neither the dynamic var nor an enclosing Provider names a frame (the
  Provider default is the no-provider sentinel, per Spec 002 §`:rf/default`
  is an ordinary id). When the hook is unbound (no adapter loaded yet, or JVM build)
  the result is `current-frame` — the dynamic-var tier alone; the React-
  context tier silently no-ops to nil.

  This is the canonical scope reader — `subs/subscribe`,
  `router/dispatch*`'s frame computation, and `core/current-frame-id`
  delegate here so the React-context tier is single-sourced.
  Public frame-scoped operations that must have a frame call
  `require-current-frame!`, which is built on this reader."
  []
  ;; Sticky hook — `:adapter/current-frame` is published
  ;; once per loaded React-shaped adapter at ns-load time and routed
  ;; via `current-adapter`; it fires on every ambient resolution
  ;; (every ambient dispatch and every ambient subscribe).
  #?(:cljs (if-let [f (late-bind/get-fn-cached :adapter/current-frame)]
             (f)
             (current-frame))
     :clj  (current-frame)))

;; ---- :rf.error/no-frame-context — the absence-is-the-corollary error ------
;;
;; Per Spec 002 §The error and its ladder + §Resolver surface (EP-0002):
;; `require-current-frame!` is "read the stamp on the token I hold";
;; `:rf.error/no-frame-context` is "this token carries no stamp". The error
;; is reserved for the **absence of a target**, never a **bad** target — a
;; caller who supplies `{:frame :ghost}` HAS carried a stamp; that is a
;; registry-lookup failure (`:rf.error/frame-destroyed`), a different
;; category. So this error is emitted BEFORE any frame-registry lookup, so
;; a missing context is never mis-reported as `frame-destroyed` for a
;; synthesised default.
;;
;; The frameless error is itself frameless: it rides the ALWAYS-ON error
;; axis (`re-frame.error-emit/dispatch-on-error!`, surface #4 — survives
;; `:advanced` + `goog.DEBUG=false`), not per-frame epoch capture. It
;; carries capture-site ancestry through the `:rf.trace/dispatch-id` /
;; `:rf.trace/parent-dispatch-id` correlation graph (read off the in-scope
;; `trace/*handler-scope*`), so the hardest case — a callback captured at
;; handler X in frame Y whose continuation fires with no stamp after the
;; cascade ended — is fully attributed even though the error has no frame
;; of its own.
;;
;; `error-emit` statically requires THIS ns (the always-on error substrate
;; sits above frame in the load order), so we reach `dispatch-on-error!`
;; through the published `:error-emit/dispatch-on-error` late-bind hook to
;; avoid the cycle — the producer always loads at boot, so the lookup never
;; misses in production.

(defn no-frame-context-payload
  "Build the canonical `:rf.error/no-frame-context` payload for an ambient
  frame-scoped `operation` that found no carried stamp and no established
  scope. Per Spec 002 §The error and its ladder, the representative shape
  is:

    {:rf.error/id :rf.error/no-frame-context
     :operation   <op-kw>     ;; e.g. :dispatch / :subscribe
     :where       <sym-or-kw> ;; the resolving call site
     :event-id    <kw>        ;; the in-flight op's id, when known
     :recovery    :supply-frame}

  `extra` (optional) merges additional context-site ancestry slots —
  `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` (capture-site
  correlation) — and any caller-supplied `:where` / `:event-id`. Caller-
  supplied keys win over the defaults so a call site can name itself
  precisely."
  ([operation] (no-frame-context-payload operation nil))
  ([operation extra]
   (merge {:rf.error/id :rf.error/no-frame-context
           :operation   operation
           :recovery    :supply-frame
           :reason      (str "a frame-scoped " (name operation) " ran with no frame "
                             "context — no carried frame stamp and no established "
                             "scope. Frame identity is carried, not found: declare "
                             "your root frame (rf/reg-frame) and run the operation "
                             "inside that scope (with-frame / a frame-provider), or "
                             "pass an explicit {:frame <id>}. Per Spec 002 §The error "
                             "and its ladder.")}
          ;; Capture-site ancestry off the in-scope handler scope: the
          ;; cascade's dispatch-id correlates a stampless continuation back
          ;; to the cascade that captured the callback. nil outside any
          ;; cascade (a genuinely top-of-stack frameless op) — `cond->`'d
          ;; in so absent rather than nil.
          (when-let [did (some-> trace/*handler-scope* :dispatch-id)]
            {:rf.trace/dispatch-id did})
          extra)))

(defn emit-no-frame-context!
  "Surface `:rf.error/no-frame-context` through the always-on error axis
  (production-survivable) AND the dev-only trace surface, then return the
  payload. Per Spec 002 §The error and its ladder the diagnostic must be
  observable in production where the dev trace is elided, so it rides
  `re-frame.error-emit/dispatch-on-error!` (reached via the
  `:error-emit/dispatch-on-error` late-bind hook — `error-emit` requires
  this ns, so a static require would cycle).

  This is the EMISSION half; callers that must also halt the operation use
  `require-current-frame!` (which emits then throws). Detection-only
  callers (frame pickers, tooling) read the nil from `current-frame` /
  `resolve-current-frame` and never reach here."
  [payload]
  (let [event-id (:event-id payload)]
    ;; Always-on listener registry (survives prod elision).
    ;; no-frame-context is an invalid operation — and we have no frame
    ;; anyway.
    (when-let [dispatch-on-error! (late-bind/get-fn :error-emit/dispatch-on-error)]
      (dispatch-on-error!
        :rf.error/no-frame-context
        nil                              ;; no event vector — absence, not a throw on a dispatch
        event-id
        nil                              ;; no frame — that is the whole point
        nil                              ;; no exception — invalid op, not a throw
        0                                ;; elapsed-ms
        (interop/now-ms)))               ;; time
    ;; Dev-only trace path — DCEs under `:advanced` + `goog.DEBUG=false`.
    (trace/emit-error! :rf.error/no-frame-context payload)
    payload))

;; ---- :rf.error/bad-frame-provider-arg — a bad explicit target -------------
;;
;; Distinct from `:rf.error/no-frame-context`. A public
;; `frame-provider` whose `:frame` is non-nil but NOT a keyword has carried
;; an explicit-but-malformed target — `{:frame "app"}`, `{:frame 7}`,
;; `{:frame ['x]}`. Frame ids are keywords (Spec 002 §Frame identity is a
;; value; `frame-provider` is "keyword in"), so a non-keyword `:frame` is a
;; CONFIGURATION ERROR at the provider boundary, not an absence.
;;
;; This is reported as its OWN category so the three states stay distinct:
;;   - absence (nil `:frame`)            → `:rf.error/no-frame-context`
;;   - bad public provider argument      → `:rf.error/bad-frame-provider-arg`
;;   - a disturbed React-context read    → `:rf.error/frame-context-corrupted`
;;
;; Without this, the lower-level reader's `coerce-context-value` would
;; stringify-coerce a `{:frame "app"}` prop back into `:app` and silently
;; route descendants to a registered `:app` frame. Validating at the public
;; provider entry points stops the bad
;; value from ever reaching React Context. The raw-hiccup compatibility
;; coercion at the reader boundary is intentionally preserved (the public
;; surfaces never write a non-keyword value, so prop-stringified keywords
;; reaching the reader only ever originate from raw `[:> Provider …]` mounts).

(defn bad-frame-provider-arg-payload
  "Build the canonical `:rf.error/bad-frame-provider-arg` payload for a
  public `frame-provider` call whose `:frame` is non-nil but not a keyword.
  `received` is the offending value; `extra` (optional) merges call-site
  detail (`:where`)."
  ([received] (bad-frame-provider-arg-payload received nil))
  ([received extra]
   (merge {:rf.error/id :rf.error/bad-frame-provider-arg
           :received    received
           :recovery    :supply-keyword-frame
           :reason      "frame-provider :frame must be a keyword frame id (e.g. :todo); a non-keyword value is a bad public provider argument, not a carried frame."}
          extra)))

(defn emit-bad-frame-provider-arg!
  "Surface `:rf.error/bad-frame-provider-arg` through the always-on error
  axis AND the dev-only trace surface, then return the payload. Mirrors
  `emit-no-frame-context!`: production-survivable so a bad provider arg is
  observable where the dev trace is elided."
  [payload]
  (when-let [dispatch-on-error! (late-bind/get-fn :error-emit/dispatch-on-error)]
    (dispatch-on-error!
      :rf.error/bad-frame-provider-arg
      nil                                ;; no event vector — a provider misuse, not a dispatch throw
      nil                                ;; no event-id
      nil                                ;; no frame — the supplied target is invalid
      nil                                ;; no exception — invalid arg, not a throw
      0                                  ;; elapsed-ms
      (interop/now-ms)))                 ;; time
  (trace/emit-error! :rf.error/bad-frame-provider-arg payload)
  payload)

(defn require-keyword-frame-provider-arg!
  "Validate a public `frame-provider`'s `:frame` arg. Returns
  `frame-kw` unchanged when it is a keyword. A nil value routes to the
  `:rf.error/no-frame-context` path (absence — the provider
  establishes no usable scope). A non-nil non-keyword value emits + throws
  the distinct `:rf.error/bad-frame-provider-arg` so the bad explicit
  target fails loudly at the provider rather than being silently coerced to
  a registered keyword frame by the lower-level context reader.

  `where` (sym/kw) names the validating call site for the payload. The nil
  branch threads `where` + a `:supply-frame` recovery into the
  no-frame-context payload, matching each provider surface's nil
  handling."
  [frame-kw where]
  (cond
    (keyword? frame-kw) frame-kw
    (nil? frame-kw)
    (let [payload (no-frame-context-payload
                    :frame-provider
                    {:where where :recovery :supply-frame})]
      (emit-no-frame-context! payload)
      (throw (error/ex-info-from-data payload)))
    :else
    (let [payload (bad-frame-provider-arg-payload frame-kw {:where where})]
      (emit-bad-frame-provider-arg! payload)
      (throw (error/ex-info-from-data payload)))))

(defn require-current-frame!
  "Return the frame stamp (id) the in-effect scope carries, or raise/emit
  `:rf.error/no-frame-context` when the token carries no stamp. This is the
  \"read the stamp on the token I hold\" primitive (Spec 002 §Resolver
  surface, EP-0002); absence is its corollary error.

  Resolution is the scope reader (`resolve-current-frame`) ONLY — explicit
  `{:frame …}` override resolution belongs to each public surface's call
  site (it wins before this helper is consulted). When the reader returns a
  frame, that stamp is returned unchanged — NO frame-registry lookup
  happens here, so a missing context is never mis-reported as
  `:rf.error/frame-destroyed` (the registry-lookup category for a bad
  explicit target). When the reader returns nil, the always-on
  `:rf.error/no-frame-context` is emitted (with capture-site ancestry) and
  then thrown so the operation halts loudly rather than writing to an
  invented default.

  `operation` is the op kind (`:dispatch` / `:subscribe` / …). `extra`
  (optional) supplies call-site detail merged into the payload — typically
  `{:where '<resolving-fn> :event-id <id>}`.

  Public frame-scoped operations that resolve ambiently call this; low-
  level detection / pickers / tooling read the nil from the readers
  directly and never throw."
  ([operation] (require-current-frame! operation nil))
  ([operation extra]
   (or (resolve-current-frame)
       (let [payload (no-frame-context-payload operation extra)]
         (emit-no-frame-context! payload)
         (throw (error/ex-info-from-data payload))))))

(defn require-frame-stamp!
  "Operation-time companion to `require-current-frame!` (EP-0002, Spec 002
  §Frame target resolution). Where `require-current-frame!` READS the stamp
  off the in-effect scope, this asserts the stamp a token was *supposed to
  carry* is actually present: it returns `frame-id` unchanged when non-nil,
  else emits + throws the always-on `:rf.error/no-frame-context`.

  This is the framework-fx / runtime-subsystem seam. A framework fx invoked
  inside a cascade ALWAYS receives the envelope frame as the fx-context
  `:frame` (the HELD stamp threaded by `re-frame.fx`). A history listener,
  managed-HTTP reply, timer, or other browser-/async-originated callback
  ALWAYS captures the owner/initiation frame at install time. If the stamp
  is nil at the call site, that is an INVARIANT FAILURE — a token reached a
  frame-scoped operation carrying no frame — NOT a request to repair the
  call by mutating a synthesised `:rf/default`. Surfacing it loudly (rather
  than defaulting) keeps replay deterministic per the carried invariant.

  `operation` is the op kind; `extra` (optional) merges call-site detail
  (`{:where '<fx-id-or-fn> :event-id <id>}`) into the payload exactly as
  `require-current-frame!` does."
  ([frame-id operation] (require-frame-stamp! frame-id operation nil))
  ([frame-id operation extra]
   (or frame-id
       (let [payload (no-frame-context-payload operation extra)]
         (emit-no-frame-context! payload)
         (throw (error/ex-info-from-data payload))))))

;; ---- lookup ---------------------------------------------------------------

(defn frame
  "Return the frame record for `id` (a frame-id keyword), or nil if not
  registered or destroyed. The registry is keyed by the bare frame-id.

  2-level lookup written as keyword-invoke (`(-> f :lifecycle :destroyed?)`)
  rather than `(get-in f [:lifecycle :destroyed?])` — `get-in` allocates
  a path vector per call, and `frame` runs on every dispatch
  / subscribe through `current-frame` resolution."
  [id]
  (when-let [f (get @frames id)]
    (when-not (-> f :lifecycle :destroyed?)
      f)))

(defn frame-disposed-for-drain?
  "Per Spec 002 §Frame disposal mid-drain: predicate used by the
  router's drain loop to interrupt a pass when the frame was destroyed
  mid-cycle. True when EITHER:

    (a) The frame record still exists but `:destroyed?` is flipped
        (post-step-3 of `destroy-frame!`, before step-6 dissoc), OR
    (b) The frame record is absent from the `frames` atom (post-step-6
        of `destroy-frame!` — the dissoc step has run).

  Returns false when `id` is registered and not destroyed. Calling for
  a never-registered `id` returns true — that case is benign for the
  drain-loop caller (a drain cannot run on a frame that was never
  registered), but the predicate is named `*-for-drain?` to make the
  intended seam explicit and avoid suggesting general
  destroyed-vs-never-registered discrimination.

  Keyed by the bare frame-id."
  [id]
  (if-let [f (get @frames id)]
    (true? (-> f :lifecycle :destroyed?))
    ;; Absent from the atom — destroy-frame!'s step 6 ran, OR the id
    ;; was never registered. The drain-loop caller only consults this
    ;; while a pass is already in flight, so the latter case cannot
    ;; arise from that seam.
    true))

(defn frame-address
  "Resolve the ADDRESS key for `frame-id` — the key a per-frame SIDE-CHANNEL
  (SSR request / response / error-trace / head snapshot, …) keys its entries by.
  This is the bare `frame-id` keyword: a frame is addressed by its process-local
  id. The named seam the SSR side-channels share so their keying stays
  single-sourced (any change to the address scheme is confined to this one fn).
  INTERNAL."
  [frame-id]
  frame-id)

(defn frame-meta
  "Per Spec 002 §The public registrar query API and Spec-Schemas
  §`:rf/frame-meta`: return the effective metadata map for a frame as a
  flat shape — `:id` plus the post-preset-expansion user-supplied
  metadata keys (`:preset`, `:fx-overrides`, `:drain-depth`, `:doc`,
  `:tags`, `:url-bound?`, `:platform`, `:ssr`, …) merged
  with the lifecycle fields (`:created-at`, `:destroyed?`, `:listeners`).

  Per Spec 002 §Frame presets, the `:preset` key is preserved verbatim
  on the returned map so tools can inspect which preset was applied; the
  expansion keys appear at the top level alongside it. The internal
  storage groupings (`:config` / `:lifecycle` on the frame record) are
  flattened away — tools must not depend on the registry's storage
  organisation, only on the canonical `:rf/frame-meta` shape."
  [id]
  (when-let [f (frame id)]
    (merge (:config f)
           (:lifecycle f)
           {:id (:id f)})))

(def ^:private live-frame-id-xf
  "Transducer over `@frames` `[id record]` pairs → the `:id` of each
  registered, non-destroyed frame. The shared front of both `frame-ids`
  arities (the 1-arity composes a prefix filter after it). The frame-id is
  read from the record's own `:id` slot (which equals the map key, the bare
  frame-id)."
  (comp (remove (fn [[_ f]] (-> f :lifecycle :destroyed?)))
        (map (fn [[_ f]] (:id f)))))

(defn frame-ids
  "All registered, non-destroyed frame ids.

  Two arities:
    (frame-ids)
      Return the full id set.
    (frame-ids ns-prefix)
      Return the subset whose id-namespace starts with `ns-prefix`
      (a string). Namespaceless ids (e.g. `:rf/default`'s namespace is
      `\"rf\"` — keyword-namespace, not value-namespace) are matched
      against the keyword's `namespace` component; ids with no
      namespace are excluded.

  Per Spec 002 §The public registrar query API.

  The `frames` registry is keyed by the bare frame-id; the frame-id is read from
  each record's own `:id` slot."
  ([]
   (into #{} live-frame-id-xf @frames))
  ([ns-prefix]
   (let [prefix (str ns-prefix)]
     (into #{}
           (comp live-frame-id-xf
                 (filter (fn [k]
                           (when-let [ns (namespace k)]
                             (clojure.string/starts-with? ns prefix)))))
           @frames))))

(defn- image-loaded-frame-record?
  "True for a frame record that is image-loaded AND publicly enumerable: it
  carries a resolved image `:generation`, is not destroyed, and its id is a
  PUBLIC id (the reserved `:rf.frame/<gensym>` namespace — no-id / direct
  frames — excluded). The selection predicate `image-loaded-frame-ids` uses to
  pick the records it projects ids off. INTERNAL."
  [f]
  (and (some? (:generation f))
       (not (-> f :lifecycle :destroyed?))
       (not= "rf.frame" (namespace (:id f)))))

(defn image-loaded-frame-ids
  "Return the set of PUBLIC frame ids whose record currently carries a resolved
  image GENERATION — the image-loaded frames the hot-reload reprojection path
  enumerates (EP-0024). An image-loaded frame is a `frames`-registry record with
  a non-nil `:generation` slot, so this is a filter over the ONE registry, not a
  separate index.

  EXCLUDES no-id (direct) frames — a frame created with no `:id` is keyed by a
  private `:rf.frame/<gensym>` id; this enumeration keeps only PUBLIC ids and
  drops the reserved `:rf.frame/` namespace so the reprojection / enumeration
  path never touches a harness-local frame the spec says its owner reloads
  explicitly (EP-0023 §Frame — direct frames bypass auto-reprojection). Excludes
  destroyed frames."
  []
  (into #{}
        (comp (filter (fn [[_ f]] (image-loaded-frame-record? f)))
              (map (fn [[_ f]] (:id f))))
        @frames))

;; ---- the internal value-read frame resolver seam --------------------------
;;
;; The value-read helpers below all share one shape: resolve the frame record
;; for an id (the bare-id lookup + the destroyed? guard via `frame`),
;; take ONE slot off it, and — for the *-value readers — deref that slot's
;; container through the substrate adapter. That repeated "resolve record → take
;; slot (→ read container)" mechanics is factored into ONE internal seam so the
;; readers do not each re-implement it. `frame` is the record resolver, the
;; per-slot accessors carry their names + nil-on-unknown/destroyed contract.

(defn frame-slot
  "Return slot `k` of the frame record for frame ADDRESS `id`, or nil when the
  frame is not registered or has been destroyed. The single record-resolution
  seam the per-slot accessors (`frame-state-container` / `app-db-container` /
  `runtime-db-container` / `frame-generation`) share — `(k (frame id))` with the
  carried-realm + destroyed? guard already applied by `frame`. INTERNAL."
  [id k]
  (k (frame id)))

(defn- frame-slot-value
  "Read slot `k` of `id`'s frame record AS A VALUE — resolve the slot's
  substrate container (via `frame-slot`) and deref it through the adapter, or
  nil when the frame is unknown/destroyed (or the slot is absent). The shared
  read mechanics the `*-value` readers (`frame-app-db-value` /
  `frame-runtime-db-value` / `frame-state-value`) funnel through.
  INTERNAL."
  [id k]
  (when-let [container (frame-slot id k)]
    (adapter/read-container container)))

(defn frame-generation
  "Return the resolved IMAGE GENERATION the frame `id` is running — the sealed
  `image-assembly` generation it resolves `(kind, id)` lookups against (EP-0024
  Term: Resolved image generation, a slot on the one unified frame value), or
  nil when the frame carries none (an ordinary configured frame) or is
  unknown/destroyed. Pure read of the record's `:generation` slot through the
  single resolver seam. The generation-resolution seam
  (`re-frame.live-frame/call-with-frame-resolution`) reads through this by id, so
  a frame-id target and a frame-value target resolve the same generation."
  [id]
  (frame-slot id :generation))

(defn frame-capabilities
  "Return the host capability map frame `id` was created with (EP-0024), or nil
  when the frame supplied none / is unknown. Stored on the
  record's `:config` under the reserved `:rf.frame/capabilities` key by
  `make-frame` so `reload-images!` / reprojection can re-check capabilities by id
  without a second registry holding them. Pure."
  [id]
  (:rf.frame/capabilities (frame-slot id :config)))

(defn frame-adapter
  "Return the active-substrate adapter binding frame `id` was created with
  (EP-0024), or nil when the frame supplied none / is unknown.
  Stored on the record's `:config` under the reserved `:rf.frame/adapter` key by
  `make-frame` so tooling (Xray's image/frame view) can read it by id. Pure."
  [id]
  (:rf.frame/adapter (frame-slot id :config)))

(defn set-generation!
  "Swap the resolved image GENERATION on frame `id`'s record IN PLACE,
  preserving every other (state-bearing) slot by identity — the in-place
  generation swap `re-frame.live-frame`'s `make-frame` / `reload-images!` /
  reprojection write through (EP-0024). A no-op for an unknown frame
  (the registry is keyed by the bare frame-id). Returns nil.
  INTERNAL — the one mutator of the `:generation` slot."
  [id generation]
  (swap! frames (fn [m]
                  (if (contains? m id)
                    (update m id assoc :generation generation)
                    m)))
  nil)

(defn frame-state-container
  "Return the frame's ONE physical frame-state **container** — the
  substrate-managed reactive cell that holds the frame-state VALUE
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` (an `r/atom` under
  the stock Reagent adapter, a `clojure.core/atom` under plain-atom /
  React-hook adapters). This is the single physical write target; every
  durable state write flows through it via `commit-frame-transition!` /
  the partition mutators.

  Internals only: the router commit path and the partition write helpers
  call `replace-container!` against this cell. App-db and runtime-db are
  READ-ONLY projection reactions over it (`app-db-container` /
  `runtime-db-container`).

  Returns `nil` when the frame is not registered or has been destroyed.
  Per Spec 002 §One physical container, two projection reactions and
  Spec 006 §Frame-state container and partition projections."
  [id]
  (frame-slot id :frame-state))

(defn app-db-container
  "Return the app-db **projection reaction** for the frame — the read-only
  derived value `(make-derived-value [frame-state] :rf.db/app)` over the
  one physical frame-state container. Layer-1 app subs read it as their
  signal source, so the subscription machinery only ever sees app-db (the
  partition split is invisible to the invalidation algorithm — a
  runtime-only commit recomputes this projection, finds `:rf.db/app` `=`,
  and does not propagate). Distinct from `frame-state-container`, the
  writable physical cell.

  READ-ONLY: this is a `make-derived-value` result, so
  `adapter/replace-container!` on it throws `:rf.error/derived-container-
  replaced` (per Spec 006 §`make-derived-value`). App-db writes go through
  `swap-frame-db!` / `replace-app-db!` / `commit-frame-transition!`, which
  write the app-db partition of the physical frame-state container.

  Distinct from `re-frame.core/app-db-value`, which returns the deref'd
  app-db **value** (a plain map). User handlers receive `db` via cofx.

  Returns `nil` when the frame is not registered or has been destroyed.
  Per Spec 002 §One physical container, two projection reactions."
  [id]
  (frame-slot id :app-db))

(defn runtime-db-container
  "Return the runtime-db **projection reaction** for the frame — the
  read-only derived value `(make-derived-value [frame-state] :rf.db/runtime)`
  over the one physical frame-state container. Framework subs
  (`sub-machine`, `[:rf.route/*]`) read it as their signal source; an
  app-only commit leaves `:rf.db/runtime` `=`, so the projection does not
  propagate and framework subs are untouched.

  READ-ONLY (a derived value); runtime-db writes go through
  `replace-runtime-db!` / `commit-frame-transition!`, which write the
  runtime-db partition of the physical frame-state container.

  Returns `nil` when the frame is not registered or has been destroyed.
  Per Spec 002 §One physical container, two projection reactions."
  [id]
  (frame-slot id :runtime-db))

(defn frame-app-db-value
  "Read the current app-db value for a frame as a plain map (deref the
  app-db projection through the substrate adapter)."
  [id]
  (frame-slot-value id :app-db))

;; ---- EP-0001 two-partition readers ----------------------------------------
;;
;; Per Spec 002 §The two-partition frame contract a frame owns two durable
;; partitions — user `app-db` and framework `runtime-db` — projected as a
;; coherent `frame-state` value `{:rf.db/app … :rf.db/runtime …}`.
;;
;; The physical one-container frame-state + projection reactions back these
;; readers, so `frame-runtime-db-value` reads the live runtime-db partition.

(defn frame-runtime-db-value
  "Read the current runtime-db partition value for a frame — the
  framework-owned subsystem state. Returns `nil` for an unknown / destroyed
  frame.

  Reads the `:rf.db/runtime` partition off the one physical frame-state
  container (via the runtime-db projection). A fresh frame's runtime-db starts
  `{}`. Per Spec 002 §The two-partition frame contract."
  [id]
  (frame-slot-value id :runtime-db))

(defn frame-state-value
  "Read the coherent frame-state projection for a frame —
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. Returns `nil` for an
  unknown / destroyed frame.

  Reads the one physical frame-state container directly (a single deref) rather
  than composing two reads, so the returned value is the exact coherent snapshot
  the commit installed. Per Spec 002 §The two-partition frame contract."
  [id]
  (frame-slot-value id :frame-state))

;; ---- EP-0001 partition commit + write helpers -----------------------------
;;
;; The frame-state container is the ONE physical write target. Every durable
;; state write — the router's per-event commit, the privileged runtime
;; mutators, full-frame tool install — flows through `replace-container!` on
;; it. Per Spec 002 §An ordinary :db return replaces only app-db + §Write
;; authority is by convention, and Spec 006 §Commit boundary.

(defn commit-frame-transition!
  "Atomically install a frame transition into the ONE physical frame-state
  container (Spec 002 §Drain-loop pseudocode §commit; Spec 006 §Commit
  boundary). `partitions` is a map that MAY carry `:rf.db/app` (the new
  app-db value — the ordinary `:db` effect, scoped to the app-db partition)
  and/or `:rf.db/runtime` (the new runtime-db value — the reserved
  `:rf.db/runtime` effect). The partition(s) NOT present are carried forward
  unchanged from the current frame-state, so:

    - an APP-ONLY commit (`{:rf.db/app v}`) replaces only the app-db slice;
      runtime-db is untouched — the handler cannot drop it through `:db`;
    - a RUNTIME-ONLY commit (`{:rf.db/runtime v}`) replaces only runtime-db;
    - a commit touching BOTH installs the combined result as ONE coherent
      transition — there is never a window where one partition is committed
      and the other is not.

  Returns the SET of partition keys that actually changed by `=` (a subset
  of `#{:rf.db/app :rf.db/runtime}`) — the caller uses it to drive the
  partition-tagged change traces (`:rf.event/db-changed` /
  `:rf.event/frame-state-changed`). A no-op partition (the supplied value
  `=` the current slice) is NOT reported as changed, so the projection
  reactions and the change signals agree. Returns `nil` for an unknown /
  destroyed frame (the nil-container guard in `replace-container!` also
  covers the destroy-race when called through it).

  NOTE the `partitions` map keys are the frame-state partition keys
  (`:rf.db/app` / `:rf.db/runtime`), NOT the effect keys (`:db` /
  `:rf.db/runtime`) — the router maps `:db` effect → `:rf.db/app` partition
  before calling this."
  [id partitions]
  (when-let [container (frame-state-container id)]
    (let [current   (adapter/read-container container)
          app-given? (contains? partitions app-partition-key)
          rt-given?  (contains? partitions runtime-partition-key)
          next-app  (if app-given? (get partitions app-partition-key)
                        (get current app-partition-key))
          next-rt   (if rt-given? (get partitions runtime-partition-key)
                        (get current runtime-partition-key))
          next-fs   {app-partition-key     next-app
                     runtime-partition-key next-rt}
          changed   (cond-> #{}
                      (and app-given?
                           (not= next-app (get current app-partition-key)))
                      (conj app-partition-key)
                      (and rt-given?
                           (not= next-rt (get current runtime-partition-key)))
                      (conj runtime-partition-key))]
      ;; ONE atomic frame-state install — both partitions in one write, per
      ;; Spec 006 §Commit boundary.
      ;;
      ;; identical?-noop short-circuit: when the next frame-state
      ;; would carry forward each partition's CURRENT OBJECT unchanged
      ;; (`identical?`, not merely `=`), the install is a genuine no-op — the
      ;; common `(if cond (assoc db …) db)` else-arm returns the same object —
      ;; so skip the `replace-container!` write entirely rather than re-install
      ;; an equal value. `=` stays the deeper change-DETECTION above (a
      ;; different-object-but-equal-value commit still writes, so the install
      ;; honours value equality and downstream `=`-memoisation collapses it).
      ;; The cheap fast-path is reference identity; deeper equality is `=`.
      (when-not (and (identical? next-app (get current app-partition-key))
                     (identical? next-rt  (get current runtime-partition-key)))
        (adapter/replace-container! container next-fs))
      changed)))

(defn replace-app-db!
  "Replace ONLY the app-db partition of `id`'s frame-state, leaving
  runtime-db untouched (Spec 002 §Frame-state value accessors and mutators,
  Mike ruling #1 / #10 — a db-shaped name never silently replaces
  runtime-db). Atomic install through the one physical container. Returns
  the set of changed partition keys, or `nil` for an unknown / destroyed
  frame. Internal write boundary used by the Tool-Pair `replace-app-db!` /
  epoch `replace-app-db!` path."
  [id app-db]
  (commit-frame-transition! id {app-partition-key app-db}))

(defn replace-runtime-db!
  "Replace ONLY the runtime-db partition of `id`'s frame-state, leaving
  app-db untouched (Spec 002 §Frame-state value accessors and mutators).
  The privileged runtime / full-frame write surface. Atomic install through
  the one physical container. Returns the set of changed partition keys, or
  `nil` for an unknown / destroyed frame."
  [id runtime-db]
  (commit-frame-transition! id {runtime-partition-key runtime-db}))

(defn replace-frame-state!
  "Replace BOTH partitions of `id` atomically with `frame-state`
  (`{:rf.db/app … :rf.db/runtime …}`) — the full-frame install for
  tool-driven replay / fixture install (epoch restore, time travel, SSR
  hydration, frame reset). A db-shaped name never silently replaces
  runtime-db; this is the explicit full-frame surface (Mike ruling #10).
  Both partitions install in ONE atomic write. Returns the set of changed
  partition keys, or `nil` for an unknown / destroyed frame.

  `frame-state` MUST carry both partition keys; a missing key installs
  `nil` for that partition (a full-frame replace is whole-value by
  contract). Use `replace-app-db!` / `replace-runtime-db!` for a
  single-partition write."
  [id frame-state]
  (commit-frame-transition! id {app-partition-key     (get frame-state app-partition-key)
                                runtime-partition-key (get frame-state runtime-partition-key)}))

(defn- swap-partition!
  "Mutate ONE partition `pk` of `id`'s physical frame-state container in place:
  read the current frame-state, recompute the partition slice as
  `(apply f old-slice args)`, write back the frame-state with only that slice
  replaced (the sibling partition carried forward by identity), and return the
  new slice — or nil for an unknown/destroyed frame. The shared read-recompute-
  write-back mechanics behind `swap-frame-db!` (app-db partition) and
  `swap-runtime-db!` (runtime-db partition); both differ ONLY by `pk`. Under
  the single-drainer invariant (Spec 002 §Single drainer per frame) the
  read-then-replace is effectively atomic — `commit-frame-transition!` is the
  only writer during fx drain. INTERNAL."
  [id pk f args]
  (when-let [container (frame-state-container id)]
    (let [current   (adapter/read-container container)
          new-slice (apply f (get current pk) args)]
      (adapter/replace-container! container (assoc current pk new-slice))
      new-slice)))

(defn swap-frame-db!
  "Mutate the frame's app-db PARTITION: read the current app-db value,
  compute `(apply f db args)`, and install the result into the app-db
  partition of the one physical frame-state container (runtime-db
  untouched). Returns the new app-db, or nil if the frame is not registered.

  Models `swap!` over the app-db partition. Under the single-drainer
  invariant (Spec 002 §Single drainer per frame) the read-then-replace is
  effectively atomic — `commit-frame-transition!` is the only writer during
  fx drain. The helper is the canonical \"mutate the frame's app-db\"
  surface; the read / partition-commit dance belongs here, not at every
  fx-handler call site.

  Writes the app-db partition of the physical frame-state container. Framework
  durable state — machines, routing, elision, SSR — rides under runtime-db, not
  app-db; those writers use the runtime-db sibling `swap-runtime-db!` to mutate
  the `:rf.db/runtime` partition (`:rf.runtime/*` children). This surface
  mutates only the app-db partition (per Spec 002 §The two-partition frame
  contract)."
  [id f & args]
  (swap-partition! id app-partition-key f args))

(defn swap-runtime-db!
  "Mutate the frame's runtime-db PARTITION: read the current runtime-db
  value, compute `(apply f runtime-db args)`, and install the result into the
  runtime-db partition of the one physical frame-state container (app-db
  untouched). Returns the new runtime-db, or nil if the frame is not
  registered.

  The runtime-db sibling of `swap-frame-db!` — the canonical \"mutate the
  frame's runtime-db\" surface for framework subsystems' direct (out-of-
  cascade / mid-fx) writes (machine spawn / destroy / update-snapshot,
  routing scroll/can-leave fx). Models `swap!` over the runtime-db partition;
  under the single-drainer invariant (Spec 002 §Single drainer per frame) the
  read-then-replace is effectively atomic. Per Spec 002 §The two-partition
  frame contract — runtime-db is reserved BY CONVENTION (decision #4); this
  is the framework-authority write surface."
  [id f & args]
  (swap-partition! id runtime-partition-key f args))

;; ---- lifecycle-vs-drain serialization -------------------------------------
;;
;; Some per-frame registry mutations must be ATOMIC with respect to that
;; frame's event drain — they read-modify-write shared registry state AND
;; app-db, and a concurrent drain that interleaves between the steps can
;; observe a half-applied lifecycle change. The flows artefact has two such
;; ops:
;;
;;   - `clear-flow` vacates the output path THEN removes the flow from the
;;     registry. A drain that starts in that window still sees the flow,
;;     recomputes it, and re-commits the output that clear-flow already
;;     vacated — leaving stale derived state no live flow maintains.
;;   - `reg-flow` replacement publishes the new flow into the registry
;;     (visible to the drain) BEFORE the registrar replacement-hook drops
;;     the stale `last-inputs` row. A drain in that window sees the new flow
;;     with the OLD input cache and skips recompute on `=`-equal inputs.
;;
;; The frame's `:drain-lock` is the existing single-drainer serialization
;; primitive (the router CAS-acquires it for the whole drain pass — see
;; `re-frame.router/drain-loop!`). `call-serialized-with-drain!` runs `f`
;; under that lock so the lifecycle mutation is mutually exclusive with any
;; concurrent drain, closing the windows above with ONE mechanism rather
;; than per-op reordering / token threading (which would touch the hot
;; dirty-check path). The drain path itself is untouched — it still just
;; CAS-acquires the lock as before; only the cold lifecycle ops now contend
;; for it.
;;
;; REENTRANCY is the load-bearing subtlety. `clear-flow` / `reg-flow` can be
;; invoked MID-DRAIN via the `:rf.fx/clear-flow` / `:rf.fx/reg-flow` effects
;; (do-fx runs inside the drain pass, on the draining thread, which already
;; holds `:drain-lock`). A naive acquire would deadlock the drainer against
;; a lock it itself holds. So we first ask the router whether THIS thread is
;; the frame's active drainer (the same `:in-drain?` thread marker the
;; `dispatch-sync` nesting guard reads): if so we are already inside the
;; single-drainer window and run `f` directly; only a DIFFERENT thread (or a
;; non-drain call site) acquires the lock. On CLJS — single-threaded — the
;; marker is `true`/`nil` and the same equality discriminates; an
;; uncontended top-level call CAS-acquires the false lock on the first try.

(defn- current-thread-is-drainer?
  "True when the calling thread is the frame's currently-active drainer.
  Reads the router's `:in-drain?` marker (stamped by
  `re-frame.router/mark-drainer!` to the drainer thread on JVM, `true` on
  CLJS). The flows lifecycle ops use this to take the reentrant fast-path
  when invoked mid-drain via `:rf.fx/reg-flow` / `:rf.fx/clear-flow` — they
  are already inside the single-drainer window, so re-taking `:drain-lock`
  would self-deadlock."
  [frame-record]
  (let [in-drain (:in-drain? @(:router frame-record))]
    #?(:clj  (identical? in-drain (Thread/currentThread))
       :cljs (true? in-drain))))

(defn in-drain?
  "True when the calling thread is `frame-id`'s currently-active drainer —
  i.e. THIS call is happening reentrantly inside the frame's single-drainer
  window (e.g. a `reg-flow` / `clear-flow` issued from an event HANDLER or a
  `:rf.fx/reg-flow` effect mid-cascade). Public wrapper over the same
  `:in-drain?` thread marker `call-serialized-with-drain!` reads.

  `reg-flow`'s same-frame `:path`-change vacate must DEFER to the
  drain's pending-`:db` transform when in-drain (a direct app-db write made
  here is clobbered by the deferred commit that publishes the handler's
  returned `:db`), but may vacate directly when OUT of a drain (no pending
  commit to clobber it). Returns false for an absent frame (nothing can be
  draining it)."
  [frame-id]
  (boolean
    (when-let [frame-record (frame frame-id)]
      (current-thread-is-drainer? frame-record))))

(defn call-serialized-with-drain!
  "Run thunk `f` serialized against `frame-id`'s event drain, returning its
  value. Used by per-frame registry mutations that must not
  interleave with a concurrent `run-flows-on-db` pass.

  - Frame absent (unregistered / destroyed): nothing can be draining it, so
    just run `f`.
  - Calling thread is the frame's active drainer (mid-drain `:rf.fx/*`
    call): already inside the single-drainer window — run `f` directly to
    avoid self-deadlocking on `:drain-lock`.
  - Otherwise: spin-CAS-acquire `:drain-lock` (the same acquire shape
    `re-frame.router/drain-block!` uses — bounded wait: an active drainer
    holds it for at most `drain-depth` events), run `f`, release in a
    `finally`."
  [frame-id f]
  (if-let [frame-record (frame frame-id)]
    (if (current-thread-is-drainer? frame-record)
      (f)
      (let [drain-lock (:drain-lock frame-record)]
        (loop []
          (when-not (compare-and-set! drain-lock false true)
            #?(:clj (Thread/yield))
            (recur)))
        (try
          (f)
          (finally
            (reset! drain-lock false)))))
    (f)))

;; ---- frame presets (Spec 002 §Frame presets) ------------------------------
;;
;; A :preset key in metadata expands at registration time into a fixed
;; bundle of metadata keys. User-supplied keys win on conflict.
;; Per Spec 002 §Frame presets, the closed list is:
;;   :default :test :story :ssr-server

(defn- preset-expansion [preset]
  ;; Per Spec 002 §Frame presets and Spec-Schemas §:rf/preset-expansion.
  ;; The four canonical expansions:
  ;;   :default    -> {} (explicit no-op; identical to omitting :preset)
  ;;   :test       -> redirect :rf.http/managed to its canned-success stub
  ;;                  (Spec 014); explicit :drain-depth 100 (matches the
  ;;                  framework default — surfaced so tooling can read the
  ;;                  bound off frame-meta without consulting the global default);
  ;;                  :rf.cofx/mint-policy :strict (per EP-0017 §6 — a
  ;;                  declared-absent generator-backed recordable fact is
  ;;                  missing-required rather than freshly minted, so a test's
  ;;                  path of least resistance is supply-the-fact, not a silent
  ;;                  per-run random; the determinism feature stays core, not
  ;;                  polish). A test that DECLARED it accepts nondeterminism
  ;;                  opts back into generation with
  ;;                  `{:rf.cofx/mint-policy :explicit-live}` (per-call or
  ;;                  per-frame).
  ;;   :story      -> same HTTP redirect as :test; tighter :drain-depth 16
  ;;                  so a runaway dispatch cascade fails fast under a story.
  ;;                  NOT strict-by-default — a story is a live demo, not a
  ;;                  determinism fixture, so it rides the router's :live
  ;;                  default (no mint-policy entry).
  ;;   :ssr-server -> :platform :server (gates fx via reg-fx :platforms).
  ;; User-supplied keys win on conflict; see expand-preset.
  ;;
  ;; The :test / :story redirect targets
  ;; `:rf.http/managed-canned-success`, which registers from the test-
  ;; support namespace `re-frame.http.test-support`. Apps that use these
  ;; presets must `:require [re-frame.http.test-support]` (alongside
  ;; `re-frame.http.managed`) so the redirect target resolves. Production
  ;; / SSR code paths use `:default` / `:ssr-server` and never reach this
  ;; branch.
  (case preset
    :default    {}
    :test       {:fx-overrides        {:rf.http/managed :rf.http/managed-canned-success}
                 :drain-depth         100
                 ;; Per EP-0017 §6: the :test preset
                 ;; defaults the cofx MINT POLICY to :strict — a declared-absent
                 ;; generator-backed recordable fact under a test frame is
                 ;; `:rf.error/missing-required-cofx`, never a freshly-minted
                 ;; per-run value. Strict-by-default tests are core: a
                 ;; determinism feature whose path of least resistance is a
                 ;; fresh random per run would degrade the test culture it
                 ;; exists to serve. A test that has DECLARED it accepts
                 ;; nondeterminism opts back in with
                 ;; `{:rf.cofx/mint-policy :explicit-live}` (per-call dispatch
                 ;; opt or a per-frame override — user keys win on conflict).
                 :rf.cofx/mint-policy :strict}
    :story      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
                 :drain-depth  16}
    :ssr-server {:platform :server}
    nil         {}
    (error/throw-error!
      :rf.error/unknown-preset
      'rf/reg-frame
      (str "unknown frame :preset " (pr-str preset)
           "; valid presets are :default, :test, :story, :ssr-server "
           "(or omit :preset). Use one of those.")
      {:recovery :use-a-valid-preset
       :extra    {:preset preset
                  :valid  #{:default :test :story :ssr-server}}})))

(defn- expand-preset [metadata]
  (let [preset    (:preset metadata)
        expansion (preset-expansion preset)]
    ;; user-supplied keys win on conflict
    (merge expansion metadata)))

;; ---- registration ---------------------------------------------------------

(defn- new-frame-record [id config]
  ;; ONE physical frame-state container holding both partitions (Spec 002
  ;; §One physical container, two projection reactions; EP-0001 decision #3).
  ;; A fresh frame starts with an empty app-db (Spec 002 §Frames always start
  ;; with app-db = {}) and an empty runtime-db.
  (let [;; EP-0024: `make-frame` threads the resolved generation through the
        ;; config under the reserved `:rf.frame/generation` key so it is installed
        ;; on the record BEFORE construction setup runs — an `:initial-events`
        ;; cascade then resolves through the frame's OWN image generation (not the
        ;; global registrar). EP-0027: a fresh frame ALWAYS starts with app-db
        ;; `{}` (Spec 002 §Frames always start with app-db = {}). The old
        ;; `:initial-db` seed is RETIRED — seeding app-db is now itself a setup
        ;; event (`[:rf/set-db {…}]` as the first `:initial-events` step), so the
        ;; whole of construction is one visible event script with no special-cased
        ;; direct write.
        frame-state (adapter/make-state-container
                      {app-partition-key     {}
                       runtime-partition-key {}})]
   {:id          id
    ;; EP-0024 — the resolved IMAGE GENERATION slot. ONE unified
    ;; frame value owns its resolved generation directly on the single
    ;; `frames`-registry record (Term: Frame value — "owns … resolved image
    ;; generation"); there is no second live-frame registry holding it. nil for
    ;; an ordinary configured frame (no `:images` selection) — the
    ;; absence-is-default signal that resolution falls through to the registrar
    ;; atom path. Threaded in via the reserved `:rf.frame/generation` config key
    ;; so it is live BEFORE `:initial-events` run; `reload-images!` / reprojection
    ;; swap it in place via `set-generation!`, preserving every other
    ;; (state-bearing) slot by identity.
    :generation  (get config :rf.frame/generation)
    :frame-state frame-state
    ;; app-db / runtime-db are READ-ONLY projection reactions over the one
    ;; physical container — `make-derived-value` memoises on `=`, so a
    ;; runtime-only commit does not propagate to app subs (and vice versa),
    ;; with no dirty flags (decision #7). The compute-fn is the bare keyword
    ;; lookup of the partition slice; `make-derived-value`'s recompute closure
    ;; arity-specialises the 1-source case so the projection costs a single
    ;; keyword invoke per recompute.
    :app-db      (adapter/make-derived-value [frame-state] app-partition-key)
    :runtime-db  (adapter/make-derived-value [frame-state] runtime-partition-key)
    :router      (atom {:queue interop/empty-queue :scheduled? false})
   ;; Single-drainer invariant: a separate CAS-able cell that admits
   ;; at most one thread into `drain!` at a time. On the JVM the
   ;; executor's `next-tick` callback can wake while the calling
   ;; thread is mid-drain (e.g. `dispatch-sync!`); without this guard,
   ;; both threads' peek+pop sequence on `:queue` is non-atomic and
   ;; double-processes / drops envelopes. The loser of the CAS no-ops;
   ;; the winning drainer rechecks the queue before releasing the
   ;; flag so envelopes queued in the gap are not orphaned. CLJS is
   ;; single-threaded so the CAS is uncontended there, but the same
   ;; flag preserves the contract under any future concurrent host.
   :drain-lock (atom false)
    :sub-cache  (atom {})
    :lifecycle  {:created-at (interop/now-ms)
                 :destroyed? false
                 :listeners  []}
    ;; The construction-only reserved `:rf.frame/generation` key is consumed
    ;; above into the `:generation` slot; it is stripped from the stored
    ;; `:config` so `frame-meta` / tooling never surface a one-shot construction
    ;; input as durable frame config. `:rf.frame/capabilities` stays in
    ;; `:config` — `reload-images!` / reprojection re-read it by id.
    ;; EP-0027: `:initial-events` is DURABLE frame config — it stays in `:config`
    ;; so `reset-frame!` can re-dispatch the recorded setup. The retired
    ;; `:rf.frame/initial-db` reserved key is dissoc'd defensively (it is no
    ;; longer threaded; `:initial-db` fails loud upstream).
    :config     (dissoc config :rf.frame/generation :rf.frame/initial-db)}))

(declare destroy-frame!)

;; ---- :initial-events normalizer + setup runner (EP-0027) ------------------
;;
;; EP-0027 replaces the hand-written post-`make-frame` setup loop with one
;; declarative key. `:initial-events` is an ordered vector of SETUP STEPS
;; dispatched synchronously, in order, into the frame at construction —
;; "`:initial-events` IS that loop, written as data" (EP-0027 §Abstract). The
;; normalizer below is PREFLIGHT validation (EP-0027 §Failure): it runs BEFORE
;; any step dispatches and BEFORE the frame container exists, so a bad shape
;; throws and leaves no frame registered. The runner then dispatches each step
;; through the existing synchronous `dispatch-sync!` path — the same path the
;; loop used — draining each to a fixed point before the next, tagging each with
;; `:source :frame-init` + its step index (EP-0027 §Provenance).
;;
;; The guiding rule (EP-0027 §Scope note): `:initial-events` is NO MORE CAPABLE
;; than the loop it replaces. No replay tape, no snapshot, no atomic staging, no
;; outcome capture. The runner IS the loop.

(defn- bad-event-vector?
  "True when `event` is NOT a non-empty event vector. The event grammar a step
  must carry (a top-level step's bare value, or a map step's `:event`)."
  [event]
  (or (not (vector? event)) (empty? event)))

(defn- normalize-initial-events
  "PREFLIGHT-validate + normalize the `:initial-events` construction value into a
  vector of `{:event <event-vec> :opts <opts-map>}` setup steps (EP-0027
  §`:initial-events` / §Failure). Pure; throws on a bad shape BEFORE any frame
  is created so an invalid declaration leaves nothing half-registered.

  `where-sym` is the user-facing constructor symbol for the diagnostic
  (`'rf/reg-frame` / `'rf/make-frame`). Returns `[]` for an absent / empty value
  (both mean \"no setup\").

  The strict shape (EP-0027 §`:initial-events`):
    - the top-level value MUST be a vector of STEPS; a BARE event vector at top
      level (`[:rf/set-db {…}]`) is INVALID — `:rf.error/initial-events-bare-event`
      (the fix names wrapping it as `[[:rf/set-db {…}]]`);
    - a STEP is a bare event vector OR a map `{:event … :opts …}`; anything else
      is `:rf.error/initial-events-bad-step`;
    - a map step's `:event` is REQUIRED and must be a non-empty event vector —
      `:rf.error/initial-events-bad-event`;
    - a map step's `:opts` is the ordinary `dispatch-sync` opts map with `:frame`
      FORBIDDEN (it is forced to the frame being constructed) —
      `:rf.error/initial-events-bad-opts`."
  [initial-events where-sym]
  (cond
    (nil? initial-events) []

    ;; A BARE event vector at top level (`[:some/id …]`) is the common mistake;
    ;; name the fix (wrap it). A keyword head is the tell that it is one event,
    ;; not a vector-of-steps.
    (and (vector? initial-events)
         (seq initial-events)
         (keyword? (first initial-events)))
    (error/throw-error!
      :rf.error/initial-events-bare-event
      where-sym
      (str ":initial-events must be a VECTOR OF STEPS, not a single bare event "
           "vector — got " (pr-str initial-events) ". A one-step setup pays one "
           "extra bracket: wrap it as [" (pr-str initial-events) "]. (Accepting "
           "\"one event or a vector of events\" would reintroduce the [:a :b] "
           "ambiguity the strict shape avoids.)")
      {:recovery :wrap-as-vector-of-steps
       :extra    {:received initial-events}})

    (not (vector? initial-events))
    (error/throw-error!
      :rf.error/initial-events-bad-step
      where-sym
      (str ":initial-events must be a VECTOR of setup steps — got "
           (pr-str initial-events) ". Omit it (or pass []) for no setup; "
           "otherwise pass a vector where each element is an event vector "
           "(e.g. [[:app/boot]]) or a {:event … :opts …} map.")
      {:recovery :pass-a-vector-of-steps
       :extra    {:received initial-events}})

    :else
    (mapv
      (fn [step]
        (cond
          ;; A bare event vector step — the common case. A non-empty vector
          ;; whose head is a keyword is an event; an empty vector is a bad step.
          (vector? step)
          (if (bad-event-vector? step)
            (error/throw-error!
              :rf.error/initial-events-bad-event
              where-sym
              (str "an :initial-events step is an EMPTY event vector — got "
                   (pr-str step) ". A step's event must be a non-empty event "
                   "vector naming a registered event id, e.g. [:app/boot].")
              {:recovery :supply-a-non-empty-event
               :extra    {:step step}})
            {:event step :opts {}})

          ;; A map step `{:event … :opts …}` — for a step that needs dispatch
          ;; opts (the common case is a deterministic clock for tests).
          (map? step)
          (let [event (:event step)
                opts  (:opts step {})]
            (when (bad-event-vector? event)
              (error/throw-error!
                :rf.error/initial-events-bad-event
                where-sym
                (str "an :initial-events map step's :event is missing, empty, or "
                     "not an event vector — got " (pr-str event) " in step "
                     (pr-str step) ". A map step is {:event <non-empty event "
                     "vector> :opts <dispatch-sync opts>}; :event is required.")
                {:recovery :supply-a-non-empty-event
                 :extra    {:step step}}))
            (when-not (map? opts)
              (error/throw-error!
                :rf.error/initial-events-bad-opts
                where-sym
                (str "an :initial-events map step's :opts is not a map — got "
                     (pr-str opts) " in step " (pr-str step) ". :opts is the "
                     "ordinary dispatch-sync opts map (e.g. {:rf.cofx {:rf/time-ms …}}).")
                {:recovery :pass-an-opts-map
                 :extra    {:step step}}))
            (when (contains? opts :frame)
              (error/throw-error!
                :rf.error/initial-events-bad-opts
                where-sym
                (str "an :initial-events map step's :opts supplies :frame — got "
                     (pr-str (:frame opts)) " in step " (pr-str step) ". The "
                     "target frame is forced to the frame being constructed and "
                     "may NOT be supplied; drop :frame from :opts.")
                {:recovery :drop-the-frame-opt
                 :extra    {:step step}}))
            {:event event :opts opts})

          :else
          (error/throw-error!
            :rf.error/initial-events-bad-step
            where-sym
            (str "an :initial-events step is neither an event vector nor a "
                 "{:event … :opts …} map — got " (pr-str step) ". Each step "
                 "must be an event vector (e.g. [:app/boot]) or a map "
                 "{:event [:app/boot] :opts {…}}.")
            {:recovery :pass-event-vector-or-map-step
             :extra    {:step step}})))
      initial-events)))

(def ^:private setup-step-failure-categories
  "The IN-BAND `:rf.error/*` categories that constitute a SETUP-STEP FAILURE
  under strict construction (EP-0027 §Failure, rf2-vw5h1r) — the PRE-COMMIT
  failures the interceptor chain CAPTURES (records into `:rf/interceptor-error`)
  and fans out on the always-on error-emit axis rather than re-raising, so
  `dispatch-sync!` returns nil normally. Each means the setup event itself
  FAILED before its `:db` write could land (app-db unchanged):

    - `:rf.error/handler-exception`     — the event handler body threw (the
                                          `[:rf/set-db x]` bad-arg case raises
                                          `:rf.error/set-db-bad-value` from
                                          inside the handler, surfacing here).
    - `:rf.error/interceptor-exception` — a user interceptor `:before`/`:after`
                                          threw.
    - `:rf.error/coeffect-exception`    — a coeffect supplier threw at context
                                          assembly.
    - `:rf.error/flow-eval-exception`   — a flow `:derive` threw (pre-install).

  POST-COMMIT failures are DELIBERATELY EXCLUDED: `:rf.error/fx-handler-
  exception` (an `:fx` handler threw AFTER the db committed) means the setup
  event SUCCEEDED — its `:db` write landed and is irreversible; only a best-
  effort post-commit side-effect failed. Per the Mike-ruled FX atomicity
  asymmetry (pre-commit transactional / post-commit best-effort, 2026-05-25),
  tearing the frame down on a post-commit fx throw would contradict that — the
  committed state stands and the fx throw is observed, not unwound. The SSR
  server error projector catches such render-walk / cascade fx throws (Spec 011
  §Server error projection); a THROWN setup step is the OUTER `:on-error`
  transport path (Spec 011 §`:on-error` vs `:error-view`), distinct from the
  projector path.

  The escaping-throw failures (unregistered / missing-required cofx escaping
  context assembly) are NOT in this set — they re-raise out of `dispatch-sync!`
  and are caught by the runner's try/catch directly, not via this capture."
  #{:rf.error/handler-exception
    :rf.error/interceptor-exception
    :rf.error/coeffect-exception
    :rf.error/flow-eval-exception})

(defn- raise-setup-step-failed!
  "STRICT CONSTRUCTION teardown (EP-0027 §Failure, rf2-vw5h1r). A setup step
  `idx` (`event`) failed — EITHER by an ESCAPING throw `cause` out of
  `dispatch-sync!`, OR by an IN-BAND `:rf.error/*` the chain captured (its
  always-on error record is `cause`'s stand-in via `captured`). Tear down the
  partially-created frame `id` so no half-created frame is left live, then raise
  `:rf.error/initial-events-step-failed` naming the failing step. `cause-ex` is
  the host throwable when the failure escaped (carried as `:cause`); nil for an
  in-band capture. `cause-msg` is the human cause text for the diagnostic
  message. `where-sym` is the constructor symbol."
  [id idx event cause-ex cause-msg where-sym]
  (destroy-frame! id)
  (error/throw-error!
    :rf.error/initial-events-step-failed
    where-sym
    (str ":initial-events setup step " idx " failed — event "
         (pr-str event) " failed during frame construction: " cause-msg
         ". Construction-time :initial-events is STRICT (EP-0027 §Failure): any "
         "setup-step failure — an escaping throw OR a handler / interceptor / "
         "cofx / flow error the chain captures in-band — tears down the partial "
         "frame (no half-created frame is left live) and aborts construction. "
         "The runtime's traced-and-recover leniency does NOT apply during "
         "construction.")
    {:recovery :fix-the-setup-step
     :extra    (cond-> {:step-index idx
                        :event      event
                        :frame      id}
                 (some? cause-ex) (assoc :cause cause-ex))}))

(defn- run-setup-events!
  "SETUP RUNNER (EP-0027 §Construction / §Provenance). Dispatch each normalized
  setup `step` SYNCHRONOUSLY into frame `id`, in order, draining each to a fixed
  point before the next — exactly as the hand-written `dispatch-sync` loop would.
  `steps` is the already-validated vector from `normalize-initial-events`.

  Each step is dispatched through `dispatch-sync!` (the same synchronous path the
  loop used), with the step's `:opts` merged under construction provenance:
  `:source :frame-init` and the step's `:step-index`, and `:frame` forced to
  `id` (the EP forbids a caller-supplied `:frame`). By the time this returns the
  synchronous setup has settled; asynchronous effects started by setup are NOT
  awaited (EP-0027 §Construction).

  STRICT CONSTRUCTION (EP-0027 §Failure, Mike-ruled 2026-06-23 rf2-vw5h1r):
  construction-time `:initial-events` is STRICT — the runtime's traced-and-
  recover leniency is a RUNTIME concern and does NOT apply here. ANY setup-step
  failure tears down the partially-created frame (`destroy-frame!`) so no half-
  created frame is left live, then raises `:rf.error/initial-events-step-failed`
  naming the failing `:step-index` + `:event`. A failure is EITHER of:

    - an ESCAPING throw out of `dispatch-sync!` — a coeffect-resolution throw
      (an unregistered / missing-required declared cofx escapes context
      assembly), or any fault the synchronous drain re-raises. Caught by the
      try/catch below.

    - an IN-BAND failure the interceptor chain CAPTURES rather than re-raising,
      so `dispatch-sync!` returns nil normally: a handler-body throw surfaced as
      `:rf.error/handler-exception` (the `[:rf/set-db x]` bad-arg case — its
      diagnostic is raised from INSIDE the `:rf/set-db` handler via
      `error/throw-error!`, post rf2-izy3b2), a user-interceptor throw
      (`:rf.error/interceptor-exception`), a coeffect-supplier throw
      (`:rf.error/coeffect-exception`), or a flow throw
      (`:rf.error/flow-eval-exception`). The chain records these into
      `:rf/interceptor-error` and the router fans them out on the always-on
      error-emit axis (`re-frame.router/emit-pipeline-exception!` →
      `error-emit/dispatch-on-error!`) WITHOUT re-throwing — so the try/catch
      never fires. We DETECT them by installing a TRANSIENT always-on error
      listener around each step dispatch (under a unique per-step key) that
      captures any `:rf.error/*` record whose `:frame` matches THIS frame. This
      is the same production-survivable axis + capture pattern
      `fire-on-destroy-event!` uses for the symmetric `:on-destroy`-throw case;
      observing the dev-only trace listener instead would not survive
      `:advanced` + `goog.DEBUG=false`. A clean step emits only `:rf.event/*`
      (the event-emit axis), never `:rf.error/*`, so a successful step captures
      nothing and the listener is a no-op.

  This replaces the former leniency where a handler-body throw during
  construction was traced-and-recovered and the frame left ALIVE — which
  contradicted the EP-0027 §Failure throw→teardown promise for exactly the
  `[:rf/set-db x]` case (rf2-vw5h1r).

  `base-opts` carries construction provenance shared by every step — the
  `:rf.trace/call-site` of the `make-frame`/`reg-frame` declaration (gated on
  `interop/debug-enabled?` by the caller, so production CLJS builds DCE it) — so a
  setup event attributes back to where `:initial-events` was declared (EP-0027
  §Provenance). The step's own `:opts` overlay it (a step may carry `:rf.cofx`,
  etc.), and the framework keys (`:frame` / `:source` / `:step-index`) win last.

  Reached through `dispatch-sync!` via late-bind to avoid a compile-time cyclic
  dep (router requires frame)."
  [id steps base-opts where-sym]
  (when (seq steps)
    ;; rf2-jsokxu: the setup runner reaches `dispatch-sync!` via late-bind (the
    ;; router requires frame, so a compile-time call would be a cyclic dep). If
    ;; the hook is NOT yet registered (re-frame.router not loaded — a standalone
    ;; re-frame.frame require with no router) we must NOT silently drop the
    ;; setup: the EP guiding rule is that `:initial-events` is NO LESS capable
    ;; than the hand-written `dispatch-sync` loop it replaces (which would error
    ;; LOUDLY on an unresolved `dispatch-sync` var, never skip), and Conventions
    ;; §No silent swallow requires a recognised-but-unhonourable input to
    ;; signal. So when there ARE steps to run and the runner is unavailable,
    ;; fail loud — tear down the partial frame (the container was already swapped
    ;; into `frames` by the caller) and throw, naming that the router is not
    ;; loaded. (The common path — re-frame.core requires re-frame.router, so the
    ;; hook is published before any runtime reg-frame — is unaffected.)
    (if-let [dispatch-sync! (late-bind/get-fn :router/dispatch-sync!)]
      ;; The always-on error-emit registry — the production-survivable axis the
      ;; router's IN-BAND error fan-out (handler / interceptor / cofx / flow
      ;; exceptions) rides. Reached via late-bind so this fn carries no static
      ;; dep on `error-emit` (the `error-emit` → `elision` → `frame` load
      ;; cycle). `re-frame.router` (whose presence we just confirmed via the
      ;; `:router/dispatch-sync!` hook) statically requires `error-emit`, so when
      ;; the runner is available these hooks are too; the `when register` guard
      ;; keeps the install defensive regardless. See `fire-on-destroy-event!` for
      ;; the symmetric `:on-destroy`-throw capture.
      (let [register  (late-bind/get-fn :error-emit/register-error-listener!)
            remove-cb (late-bind/get-fn :error-emit/unregister-error-listener!)]
        (loop [idx 0
               remaining steps]
          (when-let [{:keys [event opts]} (first remaining)]
            (let [step-opts  (assoc (merge base-opts opts)
                                    :frame      id
                                    :source     :frame-init
                                    :step-index idx)
                  ;; A fresh per-step capture slot + a UNIQUE listener key. The
                  ;; key must be unique per step (not a constant) so a setup step
                  ;; that itself creates / tears down ANOTHER frame — whose own
                  ;; transient error listener installs under a sibling key —
                  ;; cannot clobber this step's listener under a shared key (the
                  ;; same hazard `fire-on-destroy-event!` guards with its per-
                  ;; destroy key).
                  captured   (atom nil)
                  listener-k [::setup-step-throw-watch
                              id
                              (swap! setup-step-watch-counter inc)]
                  ;; Capture the FIRST PRE-COMMIT-failure `:rf.error/*` record
                  ;; fired against THIS frame during the step dispatch (see
                  ;; `setup-step-failure-categories`). Those categories carry
                  ;; `:frame`, so a frame + category match is an unambiguous
                  ;; setup-step failure under strict construction. A clean step
                  ;; emits only `:rf.event/*` (the event-emit axis), never these,
                  ;; so nothing is captured on success; a POST-COMMIT
                  ;; `:rf.error/fx-handler-exception` is NOT captured — the event
                  ;; committed and the fx throw is best-effort (FX atomicity
                  ;; asymmetry).
                  listener   (fn [record]
                               (when (and (= id (:frame record))
                                          (contains? setup-step-failure-categories
                                                     (:error record))
                                          (nil? @captured))
                                 (reset! captured record)))]
              (when (and register remove-cb)
                (register listener-k listener))
              (try
                (try
                  (dispatch-sync! event step-opts)
                  (catch #?(:clj Throwable :cljs :default) t
                    ;; ESCAPING throw out of dispatch-sync! (e.g. a cofx-
                    ;; resolution throw escaping context assembly). Tear down +
                    ;; raise, carrying the original throwable as `:cause`.
                    (raise-setup-step-failed!
                      id idx event t (error/ex-message-safe t) where-sym)))
                (finally
                  (when (and register remove-cb)
                    (remove-cb listener-k))))
              ;; IN-BAND failure: dispatch-sync! returned nil normally but the
              ;; interceptor chain CAPTURED a throw and fanned it out on the
              ;; always-on error-emit axis (the `[:rf/set-db x]` bad-arg / any
              ;; handler-body throw → `:rf.error/handler-exception`, post
              ;; rf2-izy3b2). Strict construction treats it as a setup-step
              ;; failure — tear down + raise, naming the captured category in the
              ;; cause text (no host throwable is carried; the chain swallowed it).
              (when-let [record @captured]
                (raise-setup-step-failed!
                  id idx event nil
                  (str "the interceptor chain captured " (:error record)
                       (when-let [r (:reason record)] (str " (" r ")")))
                  where-sym)))
            (recur (inc idx) (rest remaining)))))
      ;; The runner hook is unavailable but there ARE steps to run: fail loud
      ;; rather than silently dropping the setup (rf2-jsokxu). Tear down the
      ;; partial frame (the caller already swapped the container into `frames`)
      ;; so no half-created, never-setup frame is left live, then throw naming
      ;; that re-frame.router is not loaded.
      (do
        (destroy-frame! id)
        (error/throw-error!
          :rf.error/initial-events-runner-unavailable
          where-sym
          (str ":initial-events has " (count steps) " setup step(s) to run but "
               "the setup runner is unavailable — `re-frame.router` is not loaded "
               "(the `:router/dispatch-sync!` late-bind hook is unregistered). "
               ":initial-events is dispatched through the router's synchronous "
               "path; require `re-frame.router` (or `re-frame.core`, which does) "
               "before constructing a frame with `:initial-events`. The "
               "partially-created frame was torn down (no half-created frame is "
               "left live).")
          {:recovery :require-re-frame-router
           :extra    {:frame      id
                      :step-count (count steps)}})))))

(defn- reject-retired-construction-keys!
  "PREFLIGHT guard (EP-0027 §Backwards-compat). `:on-create` and `:initial-db`
  are RETIRED construction keys (pre-alpha, no shim). A construction map that
  still supplies either fails LOUD with the dedicated `:rf.error/*` naming the
  `:initial-events` / `[:rf/set-db …]` replacement, BEFORE any frame is created.
  `where-sym` is the constructor symbol for the diagnostic."
  [config where-sym]
  (when (contains? config :on-create)
    (error/throw-error!
      :rf.error/on-create-retired
      where-sym
      (str ":on-create is RETIRED (EP-0027) — frame setup is now the declarative "
           ":initial-events vector. Replace {:on-create [:app/boot]} with "
           "{:initial-events [[:app/boot]]}. (Construction is events-only; there "
           "is no compatibility shim — pre-alpha.)")
      {:recovery :use-initial-events
       :extra    {:on-create (:on-create config)}}))
  (when (contains? config :initial-db)
    (error/throw-error!
      :rf.error/initial-db-retired
      where-sym
      (str ":initial-db is RETIRED (EP-0027) — seeding app-db is itself an event. "
           "Replace {:initial-db {:n 0}} with {:initial-events [[:rf/set-db {:n 0}]]} "
           "(`:rf/set-db` is the framework-standard app-db seed event). "
           "(Construction is events-only; there is no compatibility shim — pre-alpha.)")
      {:recovery :use-rf-set-db
       :extra    {:initial-db (:initial-db config)}})))

(defn reg-frame
  "Atomic create-and-register. Per Spec 002 §reg-frame is atomic:
  - If the id is unregistered, create the frame container, run the
    :initial-events setup steps synchronously (EP-0027), return the keyword.
  - If the id is already registered, perform a SURGICAL UPDATE: existing
    runtime state (app-db, sub-cache, queue) is preserved; only the
    metadata/config is replaced (the recorded :initial-events is REPLACED, not
    replayed — idempotent re-registration). Hot-reload Just Works."
  [id metadata]
  (let [;; The registry is keyed by the bare frame-id.
        config (source-coords/merge-coords (expand-preset metadata))
        ;; EP-0027 PREFLIGHT (BEFORE the registrar write / any container): reject
        ;; the retired `:on-create` / `:initial-db` keys fail-loud, and normalize
        ;; + validate `:initial-events` into a vector of setup steps. Both run
        ;; here so a bad construction declaration throws BEFORE any frame is
        ;; registered (EP-0027 §Failure — preflight validation; no frame left
        ;; registered). The normalized steps are dispatched after the container +
        ;; config are installed (first-registration branch below).
        _              (reject-retired-construction-keys! config 'rf/reg-frame)
        setup-steps    (normalize-initial-events (:initial-events config) 'rf/reg-frame)
        ;; EP-0015 §3 / §9: validate the frame-owned policy keys
        ;; (`:sensitive {:http …}` HTTP carriers + `:observability` sink policy)
        ;; EARLY — pure, container-independent, fail-loud. An unknown
        ;; classification key / non-string carrier name / malformed sink entry
        ;; throws here, BEFORE the registrar write and BEFORE any container
        ;; exists, so a bad declaration leaves no half-registered frame and
        ;; never reaches `:initial-events`. Reached via late-bind:
        ;; `re-frame.frame-classification` requires this ns, so a static require
        ;; would cycle; `re-frame.core` requires it at boot so the hook is
        ;; always published before any runtime `reg-frame`. No-op when the
        ;; config carries no policy key (the common case).
        ;;
        ;; EP-0025: durable app-db classification is NO LONGER a frame
        ;; annotation — the `:sensitive` / `:large {:app-db …}` durable
        ;; declaration moved to the commit-plane classification effects
        ;; (a `reg-event` returns `:sensitive` / `:large` alongside `:db`,
        ;; `re-frame.elision`). So `reg-frame` only VALIDATES the surviving
        ;; HTTP-carrier + observability policy; it installs NOTHING into the
        ;; elision registry. (The retired `:app-db` key now fails loud.)
        _              (when-let [validate (late-bind/get-fn
                                            :frame-classification/validate!)]
                         (validate id config))]
    (registrar/register! :frame id config)
    ;; Frame-level trace-emission gate: a frame registered
    ;; with `:rf.trace/frame-no-emit? true` is a tool / inspector frame
    ;; (e.g. Xray's `:rf/xray`) whose own reactive substrate must NOT
    ;; flood the shared trace ring it inspects. The flag is the frame-
    ;; scoped sibling of the handler-scoped `:rf.trace/no-emit?`
    ;; (Spec 009 §Trace-emission opt-out). Honoured on BOTH first
    ;; registration and re-registration so a hot-reload can flip it
    ;; either way; `trace.cljc` owns the canonical set + predicate.
    (trace/set-frame-no-emit! id (true? (:rf.trace/frame-no-emit? config)))
    ;; Per Spec 009 §Retention contract: apply
    ;; the per-frame `:rf.trace/cascades-retained` override at
    ;; registration time. Honoured on BOTH first registration and re-
    ;; registration so a hot-reload can flip it either way. When the
    ;; key is absent the frame inherits the process-default. Routed via
    ;; late-bind so production CLJS bundles (where trace.tooling is
    ;; not loaded) short-circuit cleanly — the trace-ring machinery is
    ;; dev-only and there's nothing to configure in prod.
    (when (contains? config :rf.trace/cascades-retained)
      (when-let [set-retained! (late-bind/get-fn-cached
                                :trace.tooling/set-frame-cascades-retained!)]
        (set-retained! id (:rf.trace/cascades-retained config))))
    (let [existing (get @frames id)]
      (cond
        ;; First registration: create everything.
        (nil? existing)
        (let [f (new-frame-record id config)]
          (swap! frames assoc id f)
          ;; EP-0025: there is no durable app-db classification install here
          ;; anymore — the frame `:sensitive` / `:large {:app-db …}` annotation
          ;; was removed in favour of the commit-plane classification effects
          ;; (a `reg-event` returns `:sensitive` / `:large` alongside `:db`).
          ;; A `:frame-init` `:initial-events` step that classifies a path
          ;; runs (below) at frame creation, so any trace the init cascade emits
          ;; is still redacted. The frame's policy keys were already validated
          ;; above; nothing is written into the elision registry from here.
          ;; EP-0027 §Construction — FORBID handler-time frame construction.
          ;; Frames are created by the VIEW (frame-provider) or at TOP LEVEL
          ;; (tests, boot, SSR per request); constructing a frame INSIDE an event
          ;; handler is not supported — a handler changes app-db, the view
          ;; materializes frames from it. This REMOVES today's two-regime
          ;; `:on-create` handling (sync at top level / async-queue mid-cascade):
          ;; the mid-cascade case is now a fail-loud error, not a queued
          ;; creation. The signal for "inside a handler" is `trace/*handler-scope*`
          ;; being bound — the router binds it for the duration of a handler's
          ;; execution and ONLY then (a bare ambient `with-frame` scope does NOT
          ;; bind it), so it distinguishes "created mid-cascade" from "top-level
          ;; boot under an ambient scope" precisely. The frame container was
          ;; already swapped into `frames` above; tear it back down before
          ;; throwing so a handler-time `reg-frame` leaves NO half-registered
          ;; frame. (The Spec 002 NORMATIVE text recording this principle is a
          ;; separate hot-zone bead; this is the CODE guard.)
          (when trace/*handler-scope*
            (destroy-frame! id)
            (error/throw-error!
              :rf.error/frame-construction-in-handler
              'rf/reg-frame
              (str "constructing a frame inside an event handler is not supported "
                   "(EP-0027) — got reg-frame " (pr-str id) " while a cascade is in "
                   "flight. Frames are created by the VIEW (frame-provider) or at "
                   "TOP LEVEL; a handler changes app-db, and the view materializes "
                   "frames from it. Move the frame creation to a frame-provider in "
                   "the view tree, or to top-level boot.")
              {:recovery :construct-frames-in-view-or-top-level
               :extra    {:frame id}}))
          ;; Run the :initial-events setup steps synchronously, in order, BEFORE
          ;; emitting :frame/created (Spec 002 §Frame creation; EP-0027
          ;; §Construction). The router/dispatch ns is reached through late-bind
          ;; (in the runner) to avoid a cyclic dep at compile time. `setup-steps`
          ;; was already PREFLIGHT-validated at the top of `reg-frame` (so a bad
          ;; shape threw before any container existed). A step that throws at
          ;; runtime tears down the partial frame inside the runner and rethrows.
          ;;
          ;; Each setup dispatch carries construction provenance: `:source
          ;; :frame-init` + its step index (added by the runner), plus the
          ;; `make-frame`/`reg-frame` call-site captured as `:rf.trace/call-site`
          ;; here so the click-to-source affordance jumps to the
          ;; `(rf/make-frame {… :initial-events […]})` declaration line. The
          ;; macro form of `reg-frame` binds `*pending-coords*`, which
          ;; `source-coords/merge-coords` merges into `config` as
          ;; `:ns`/`:file`/`:line`/`:column` — so the call-site is already on the
          ;; config map. Gated on `interop/debug-enabled?` (the OUTERMOST form, per
          ;; Spec 009 §Production builds) so production CLJS builds DCE the
          ;; call-site read and the keyword never leaks into the advanced bundle.
          (let [base-opts (if interop/debug-enabled?
                            (cond-> {}
                              (or (:file config) (:line config))
                              (assoc :rf.trace/call-site
                                     (cond-> {}
                                       (:ns     config) (assoc :ns     (:ns     config))
                                       (:file   config) (assoc :file   (:file   config))
                                       (:line   config) (assoc :line   (:line   config))
                                       (:column config) (assoc :column (:column config)))))
                            {})]
            (run-setup-events! id setup-steps base-opts 'rf/reg-frame))
          (trace/emit! :rf.frame :rf.frame/created
                       {:frame id :config (dissoc config :rf.frame/generation
                                                  :rf.frame/initial-db)})
          id)

        ;; Re-registration: surgical update of replaceable slots only.
        ;; Per Spec 002 §Re-registration — surgical update.
        :else
        (let [;; EP-0024: idempotent replacement — re-`make-frame`
              ;; threads the freshly-resolved generation under the reserved
              ;; `:rf.frame/generation` config key. Refresh the `:generation`
              ;; slot from it (a re-make WITH new `:images` swaps the running
              ;; generation; a re-make WITHOUT `:images` carries nil and CLEARS
              ;; it back to an ordinary configured frame — matching the
              ;; first-creation contract). The retired `:rf.frame/initial-db`
              ;; reserved key is stripped defensively (no longer threaded). The
              ;; reserved `:rf.frame/generation` key is stripped from the stored
              ;; `:config`.
              ;;
              ;; EP-0027 §Reset / §Frame provider — idempotent re-registration
              ;; RE-RECORDS but does NOT REPLAY `:initial-events`: the new
              ;; `:initial-events` (durable frame config) lands in `:config` here,
              ;; REPLACING the prior recording (and CLEARING it when the key is
              ;; absent), while durable app-db / sub-cache / queue are preserved.
              ;; The recorded setup is replayed ONLY by `reset-frame!` (the
              ;; opt-in full replace), never on a surgical re-registration / a
              ;; React remount / StrictMode double-invoke / Story re-eval.
              stored-config (dissoc config :rf.frame/generation :rf.frame/initial-db)]
          (swap! frames update id
                 assoc :config stored-config :generation (get config :rf.frame/generation))
          ;; EP-0025: re-registration installs no durable app-db classification
          ;; — the frame `:sensitive` / `:large {:app-db …}` annotation was
          ;; removed (classification now rides the commit-plane effects, whose
          ;; `:source :effect` elision entries are owned by event handlers and
          ;; survive re-registration untouched). The new `:config` (with its
          ;; HTTP-carrier + observability policy) was already validated above and
          ;; lands in `:config`; runtime state (app-db, sub-cache, queue, the
          ;; effect-/flow-sourced elision registry) is preserved.
          (trace/emit! :rf.frame :rf.frame/re-registered
                       {:frame id :config stored-config})
          id)))))

(defn make-anon-frame-record!
  "INTERNAL anonymous-instance creation (EP-0024): generate a
  gensym'd id under `:rf.frame/`, register a configured record under it, and
  return the gensym'd id. This is NOT a public constructor — the ONE public
  constructor is `re-frame.live-frame/make-frame` (`rf/make-frame`), which
  accepts both image-selection AND record-config opts and returns the frame
  VALUE. This id-returning record helper is the internal no-`:id`
  configured-record path the unified constructor and the test/SSR harnesses build
  on. Per Spec 002 §Per-instance frames.

  The `-record!` suffix names exactly what it returns — an anonymous gensym-keyed
  RECORD's id, not a frame value — so it never reads as the public
  `re-frame.live-frame/make-frame` (the frame-VALUE constructor) at a call site."
  [config]
  (let [id (keyword "rf.frame" (str (gensym "")))]
    (reg-frame id config)
    id))

(defn make-frame-value
  "Build a live frame VALUE for frame id `runnable-id` (EP-0024) —
  the lifecycle token `make-frame` returns. INTERNAL: the value carries the
  `:rf.frame/object` marker, its `:rf.frame/runnable-id` (= the id its record is
  keyed by), and the public `:rf.frame/id` + the creation inputs
  (`:rf.frame/capabilities` / `:rf.frame/adapter`) when present. The resolved
  generation is NOT embedded on the value — it lives on the record
  (`:generation`), read by id via `frame-generation`, so a value and its id
  resolve the same generation and a `reload-images!` swap is observed by every
  holder of either. Pure map assembly; `id` is the public frame id (nil for a
  no-id direct value), `runnable-id` the record address.

  EP-0027 retired `:initial-db`: app-db seeding is now a setup event
  (`:initial-events`), so the constructed value no longer carries an
  `:rf.frame/initial-db` slot."
  [{:keys [id runnable-id capabilities adapter]}]
  (cond-> {object-marker         true
           runnable-id-key       runnable-id}
    (some? id)           (assoc :rf.frame/id id)
    (some? capabilities) (assoc :rf.frame/capabilities capabilities)
    (some? adapter)      (assoc :rf.frame/adapter adapter)))

;; ---- destruction ----------------------------------------------------------
;;
;; destroy-frame! runs an ordered teardown. Each step lives in its own
;; named helper so the body of destroy-frame! reads as a step list. Order
;; matters — see destroy-frame!'s docstring for the authoritative recipe.

;; Frame id of the in-flight `destroy-frame!`, bound for the duration of
;; the teardown so `safe-call-hook!` can stamp `:frame` on a hook-failure
;; diagnostic regardless of the hook's arg shape (the cache-reset hooks
;; take no frame arg).
(def ^:dynamic *destroying-frame-id* nil)

;; Per-destroy accumulator of cleanup-hook failures, bound to a fresh atom
;; by `destroy-frame!` for the duration of the teardown walk. Each
;; `safe-call-hook!` failure conj's one entry
;; (`{:hook <key> :exception <ex> :where :safe-call-hook!}`); the
;; finally-shaped flush at the bottom of `destroy-frame!` ships them as the
;; single always-on `:rf.error/frame-teardown-failed` report's
;; `:hook-failures` vector. ACCUMULATING into a side atom (rather than
;; emitting per-hook on the always-on axis) is what makes the flush
;; FINALLY-shaped: if a downstream teardown step aborts the walk mid-recipe,
;; the entries collected so far are already in the atom and the `finally`
;; boundary still flushes them (EP-0008 R1 / Spec 009 §Emit-safety —
;; finally-shaped flush). nil outside a destroy (defensive — `safe-call-hook!`
;; only conj's when bound).
(def ^:dynamic *teardown-hook-failures* nil)

;; Pre-cascade frame-state snapshot of the in-flight dequeued event, bound by
;; the router around `process-event!` (see `re-frame.router/run-one-pass!`).
;; A handler that calls `destroy-frame!` on its own frame mid-drain runs
;; INSIDE that binding, so `destroy-frame!` can recover the whole frame-state
;; (both partitions) held BEFORE the in-flight event's cascade began — the
;; `:frame-state-before` slot the `:halted-destroy` epoch record carries per
;; Spec-Schemas §`:rf/epoch-record` §Outcomes. The canonical snapshot unit is
;; the whole frame-state; the epoch derives `:db-before` from its app-db
;; projection.
;; nil outside a drain (an out-of-cascade `destroy-frame!` — hot-reload,
;; `reset-frame!`, REPL — commits no `:halted-destroy` record, so the slot
;; is moot there).
(def ^:dynamic *cascade-frame-state-before* nil)

;; The in-flight dequeued event's causal `:rf/time-ms` (the
;; `:rf.cofx` `:rf/time-ms` stamped on its envelope at the causal
;; boundary), bound by the router around `process-event!` alongside
;; `*cascade-frame-state-before*`. A handler that calls `destroy-frame!`
;; on its own frame mid-drain runs INSIDE this binding, so the
;; `:halted-destroy` epoch record's `:committed-at` is the DESTROYING
;; event's causal time — replayable — rather than an ambient host-clock
;; read at assembly time (per EP-0010 §Time / Spec 002 §Recordable
;; coeffects). nil outside a drain — the moot out-of-cascade destroy commits no
;; record, so the epoch surface's nil-tolerant fallback applies.
(def ^:dynamic *cascade-time-ms* nil)

(defn- safe-call-hook!
  "Fire a late-bound cleanup hook by key. No-op when unbound. Exceptions
  are caught so one bad hook can't block the rest of teardown — but the
  failure is NOT silent. On a throw we do TWO things, on two distinct
  Spec 009 observability channels:

    1. ALWAYS-ON axis (EP-0008 R1) — conj the failure entry
       (`{:hook <key> :exception <ex> :where :safe-call-hook!}`) onto the
       per-destroy `*teardown-hook-failures*` accumulator. `destroy-frame!`
       flushes the accumulated entries as ONE bounded
       `:rf.error/frame-teardown-failed` report through a finally-shaped
       boundary, so even a mid-teardown abort ships the entries gathered
       so far. Accumulating here (rather than emitting per-hook on the
       always-on axis) collapses the SSR per-request-destroy × M req/s
       per-hook flood to one record per destroy while preserving the
       which-hooks-failed-together correlation (Spec 009 §Channel-
       promotion catalogue rows).

    2. DIAGNOSTIC channel (EP-0008 R2) — emit the per-hook
       `:rf.warning/teardown-hook-exception` trace at its CAUSAL position
       carrying the hook key, the in-flight frame id (`*destroying-frame-
       id*`), and the exception, so a leaked optional-artefact cleanup
       (stale schemas, flow rows, side-channel atoms, trace rings) leaves
       a dev breadcrumb in long-lived SSR / test / tooling processes. This
       emit rides `interop/debug-enabled?` (inside `trace/emit-error!`) so
       production CLJS bundles DCE it — the per-hook dev visibility is KEPT
       (only the always-on emission collapsed to the single report).

  Best-effort teardown semantics are preserved — the throw is swallowed
  and teardown continues (`:recovery :ignored`)."
  [hook-key & args]
  (when-let [f (late-bind/get-fn hook-key)]
    (try (apply f args)
         (catch #?(:clj Throwable :cljs :default) ex
           ;; Always-on axis: accumulate (flushed once by destroy-frame!).
           (when-let [acc *teardown-hook-failures*]
             (swap! acc conj {:hook      hook-key
                              :exception ex
                              :where     :safe-call-hook!}))
           ;; Diagnostic channel: per-hook dev trace at its causal position.
           (trace/emit-error! :rf.warning/teardown-hook-exception
                              {:category  :rf.warning/teardown-hook-exception
                               :hook      hook-key
                               :frame     *destroying-frame-id*
                               :exception ex
                               :where     :safe-call-hook!})
           nil))))

(defn- emit-on-destroy-handler-exception!
  "Surface `:rf.error/on-destroy-handler-exception` through BOTH the
  ALWAYS-ON error-emit axis (production-survivable) AND the dev-only trace
  surface. Per EP-0008: the dedicated `:on-destroy`-throw
  category is the DISCRIMINABLE teardown signal — an operator on a
  `goog.DEBUG=false` host must be able to tell 'this throw happened during
  destroy' from a generic `:rf.error/handler-exception`. The router's
  `:rf.error/handler-exception` is the production source of record for the
  *handler throw*; the discriminator (it was an `:on-destroy`) rides the
  always-on axis too so it survives elision rather than riding only the DCE'd
  `trace/emit-error!`.

  This is also the ONLY always-on coverage for the defence-in-depth re-throw
  branch (`dispatch-sync!` itself faulting): that path never produces a router
  `:rf.error/handler-exception`, so the always-on emission here is its only
  production observability.

  `frame` cannot static-require `re-frame.error-emit` (the always-on error
  substrate sits above frame in the load order — a static require closes a
  cycle), so the always-on emission rides the published
  `:error-emit/dispatch-on-error` late-bind hook (the same hook
  `emit-no-frame-context!` uses). The producer always loads at boot, so the
  lookup never misses in production. The dev trace below keeps the in-process
  tooling surface (DCE'd in production)."
  [id on-destroy exception extra-tags]
  ;; Always-on listener registry (survives prod elision). Default
  ;; `:recovery :ignored` — teardown continues best-effort.
  (when-let [dispatch-on-error! (late-bind/get-fn :error-emit/dispatch-on-error)]
    (dispatch-on-error!
      :rf.error/on-destroy-handler-exception
      on-destroy                         ;; the :on-destroy event vector
      (when (vector? on-destroy) (first on-destroy)) ;; event-id
      id                                 ;; the frame being torn down
      exception
      0                                  ;; elapsed-ms — not a timed dispatch here
      (interop/now-ms)))
  ;; Dev-only trace path — DCEs under `:advanced` + `goog.DEBUG=false`.
  (trace/emit-error! :rf.error/on-destroy-handler-exception
                     (merge {:frame     id
                             :event     on-destroy
                             :exception exception
                             :recovery  :ignored
                             :where     :fire-on-destroy-event!}
                            extra-tags)))

(defn- fire-on-destroy-event!
  "Run the user-supplied `:on-destroy` event synchronously, then continue
  teardown regardless of outcome. Per Spec 002 §Destroy — `:on-destroy`
  handler throw semantics: a throw from the user's
  handler MUST NOT abort teardown. Emit `:rf.error/on-destroy-handler-exception`
  through the always-on error-emit axis AND the dev trace
  (`emit-on-destroy-handler-exception!`) and continue — every downstream
  step (machine cascade, sub-cache disposal, cleanup hooks,
  `:frame/destroyed`, registry dissoc) MUST still run so the frame is fully
  torn down.

  Mechanism: the router catches handler throws and converts them to
  `:rf.error/handler-exception` — `dispatch-sync!` does not re-throw. To
  surface the throw as the dedicated `:rf.error/on-destroy-handler-
  exception` category (Mike's decision), we install a TRANSIENT listener
  on the ALWAYS-ON error-emit axis for the duration of the dispatch under a
  UNIQUE per-destroy key (a constant key would let a nested / overlapping
  destroy clobber the outer's listener and drop its dedicated record): any
  `:rf.error/handler-exception` record whose `:frame` matches us is captured
  and re-emitted under the dedicated category. The always-on axis is the one
  surface the router's handler-exception fan-out ALSO rides
  (`re-frame.router/emit-pipeline-exception!` → `error-emit/dispatch-on-
  error!`), so this capture survives `:advanced` + `goog.DEBUG=false` where the
  dev trace is DCE'd — observing the dev-only `trace.tooling` listener registry
  (which no-ops in production) instead would not survive prod despite the Spec
  009 catalogue promising it does. We reach the registry through the
  `:error-emit/register-error-listener!` /
  `:error-emit/unregister-error-listener!` late-bind hooks because a
  static `re-frame.frame` → `re-frame.error-emit` require closes the
  `error-emit` → `elision` → `frame` load cycle (the same reason the
  emission below rides `:error-emit/dispatch-on-error`).

  We ALSO wrap the dispatch itself in try/catch as a defence-in-depth: if
  `dispatch-sync!` ever re-throws (e.g. a fault inside the dispatch
  infrastructure itself, not the user handler), we catch it here — and
  per EP-0008 the dedicated category rides the always-on axis so this
  defence-in-depth branch (which never produces a router
  `:rf.error/handler-exception`) is observable in production. The two
  paths are mutually exclusive (a router-converted handler throw never
  re-throws out of `dispatch-sync!`; an infra fault re-throws and never
  produces a router handler-exception record), and a `re-entered?` guard
  makes the single-record contract explicit either way.

  This mirrors the swallow-then-continue shape of `safe-call-hook!` below
  but ALSO emits a structured error event (where `safe-call-hook!` is
  silent) — the user's `:on-destroy` is application code; its failure
  is a first-class diagnostic event."
  [id f]
  (when-let [on-destroy (-> f :config :on-destroy)]
    (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
      (let [captured     (atom nil)
            infra-fault? (atom false)
            ;; The always-on error-emit listener registry — the
            ;; production-survivable axis the router's handler-exception
            ;; fan-out rides. Reached via late-bind so this fn carries no
            ;; static dep on `error-emit` (the `error-emit` → `elision` →
            ;; `frame` load cycle). The producer always loads at boot, so
            ;; the lookup never misses in production; the `when register`
            ;; guard keeps the install defensive regardless.
            register     (late-bind/get-fn :error-emit/register-error-listener!)
            remove-cb    (late-bind/get-fn :error-emit/unregister-error-listener!)
            ;; A UNIQUE per-destroy listener key — NOT a constant.
            ;; A nested / overlapping destroy (an `:on-destroy` that destroys a
            ;; different frame, Spec 002) would otherwise clobber the
            ;; outer destroy's listener under a shared key and drop the outer's
            ;; dedicated `:on-destroy-handler-exception`. A fresh key per call
            ;; gives each extent its own listener.
            listener-k   [::on-destroy-throw-watch
                          id
                          (swap! on-destroy-watch-counter inc)]
            listener     (fn [record]
                           (when (and (= :rf.error/handler-exception (:error record))
                                      (= id (:frame record))
                                      (nil? @captured))
                             (reset! captured record)))]
        (when (and register remove-cb)
          (register listener-k listener))
        (try
          (try
            (dispatch-sync on-destroy {:frame id})
            (catch #?(:clj Throwable :cljs :default) ex
              ;; Defence-in-depth: dispatch-sync! normally swallows
              ;; handler throws, but if the dispatch infrastructure
              ;; itself fails we still emit the dedicated category. This
              ;; branch never produces a router :rf.error/handler-exception,
              ;; so the always-on emission here is its ONLY production
              ;; observability (EP-0008).
              (reset! infra-fault? true)
              (emit-on-destroy-handler-exception! id on-destroy ex nil)))
          (finally
            (when (and register remove-cb)
              (remove-cb listener-k))))
        ;; If the router converted a handler throw to an always-on
        ;; `:rf.error/handler-exception` record, re-emit under the
        ;; dedicated :on-destroy category so consumers can discriminate
        ;; teardown failures from regular handler throws. Rides the
        ;; always-on axis (EP-0008) so the discriminable
        ;; teardown signal survives `goog.DEBUG=false`. The
        ;; `infra-fault?` guard keeps the single-record contract explicit
        ;; — the defence-in-depth arm above already emitted in that case.
        (when (and (not @infra-fault?) @captured)
          (let [record @captured]
            (emit-on-destroy-handler-exception!
              id on-destroy (:exception record)
              {:exception-message (when-let [ex (:exception record)]
                                    #?(:clj  (.getMessage ^Throwable ex)
                                       :cljs (.-message ex)))})))))))

(defn- notify-machine-destruction!
  "Frame-destroy machine-cascade entry-point.

  Per Spec 005 §Cross-Spec Interactions §1: when the
  machines artefact is loaded, delegate the full cascade
  (reverse-creation walk, per-machine `:exit` cascade, HTTP abort,
  unified teardown projection, system-id release, handler unregister)
  to the late-bind hook `:machines/teardown-on-frame-destroy!`. The
  hook is published by `re-frame.machines` so core never statically
  requires the optional machines artefact.

  Fallback (no machines artefact on the classpath): the minimal contract —
  fire the `:http/abort-on-actor-destroy`
  hook per snapshot key and emit `:rf.machine.lifecycle/destroyed`
  with `:reason :parent-frame-destroyed`. Without the machines
  artefact there are no live `:exit` cascades to run, no actor
  handlers to unregister, and no system-id reverse index to release."
  [id]
  (if-let [teardown! (late-bind/get-fn :machines/teardown-on-frame-destroy!)]
    (teardown! id)
    ;; Fallback path — minimal contract when the machines artefact is absent.
    ;; EP-0001: machine snapshots are durable runtime-db state.
    (let [container  (runtime-db-container id)
          rt         (when container (adapter/read-container container))
          machines   (get-in rt [:rf.runtime/machines :snapshots])
          abort-http (late-bind/get-fn :http/abort-on-actor-destroy)]
      (doseq [[machine-id snapshot] machines]
        (when abort-http
          (try (abort-http machine-id)
               (catch #?(:clj Throwable :cljs :default) _ nil)))
        (trace/emit! :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed
                     {:frame      id
                      ;; The reaped actor's live INSTANCE address;
                      ;; `:machine-id` is reserved for the registered TYPE. Must
                      ;; match the machines-artefact orchestrator emit
                      ;; (`lifecycle-fx/frame-destroy/emit-lifecycle-destroyed!`)
                      ;; so the registrar-substrate row carries one tag shape
                      ;; whether or not the machines artefact is loaded.
                      :actor-id   machine-id
                      :last-state (:state snapshot)
                      :reason     :parent-frame-destroyed})))))

(defn- mark-frame-destroyed!
  ;; The `frames` registry is keyed by the bare frame-id, so flip `:destroyed?`
  ;; on that record.
  [id]
  (swap! frames update id assoc-in [:lifecycle :destroyed?] true))

(defn- tear-down-sub-cache!
  "Dispose every cached subscription reaction for the destroyed frame.

  Route through the sub-cache-owned
  `:subs.cache/dispose-all-for-frame-destroy!` hook so each eviction
  emits a `:rf.sub/dispose` trace (reason `:frame-destroy`) — frame
  teardown is a real eviction class and MUST appear in the sub-cache
  lifecycle stream like `unsubscribe` / hot-reload / `clear-sub-cache!`
  do (disposing reactions directly would be invisible to
  tooling). `subs.cache` requires `frame` (this ns), so the call is
  late-bound to keep the dependency one-directional. The fallback
  (hook unbound — only reachable if `re-frame.subs.cache` was never
  loaded, e.g. a frame with subs but no subscribe path) preserves the
  best-effort direct disposal so teardown never leaks reactions."
  [id f]
  (when-let [cache (:sub-cache f)]
    (if-let [dispose-all! (late-bind/get-fn :subs.cache/dispose-all-for-frame-destroy!)]
      (dispose-all! cache id)
      (do
        (doseq [[_k entry] @cache]
          (when-let [r (:reaction entry)]
            (try (interop/dispose! r)
                 (catch #?(:clj Throwable :cljs :default) _ nil))))
        (reset! cache {})))))

(defn- tear-down-partition-projections!
  "Dispose the two partition projection reactions (`:app-db` /
  `:runtime-db`) that `make-derived-value` layered over the physical
  frame-state container. Each projection holds a watch on the
  physical container (on the React-hook / plain-atom spine) or a Reagent
  reaction; left undisposed across a `destroy-frame!`, those watches /
  reactions leak in long-lived processes (test bundles, SSR per-request
  frame churn, hot-reload). Best-effort — a throwing dispose does not abort
  teardown. The physical frame-state container itself is GC'd with the
  dropped frame record once `dissoc-frame!` runs; no explicit dispose."
  [f]
  (doseq [k [:app-db :runtime-db]]
    (when-let [proj (get f k)]
      (try (interop/dispose! proj)
           (catch #?(:clj Throwable :cljs :default) _ nil)))))

(defn- emit-frame-destroyed-trace!
  [id]
  (trace/emit! :rf.frame :rf.frame/destroyed
               {:frame id}))

(defn- dissoc-frame!
  ;; The frame record is keyed by the bare frame-id; removing it is a plain
  ;; dissoc.
  [id]
  (swap! frames dissoc id))

(defn- unregister-frame!
  ;; The `:frame` registrar slot lives in the process-global registrar, so the
  ;; unregister is a plain registrar call.
  [id]
  (registrar/unregister! :frame id))

(defn- notify-epoch-listeners!
  "Fire the epoch destroy hook, threading the two frame-state snapshots the
  `:halted-destroy` epoch record carries per Spec-Schemas §`:rf/epoch-record`
  §Outcomes. The canonical snapshot unit is the whole frame-state (both
  partitions); the epoch surface derives the `:db-before` / `:db-after` app-db
  projections from them.

    `fs-before` — the pre-cascade snapshot (frame-state before the in-flight
                  event's cascade began), recovered from the router-bound
                  `*cascade-frame-state-before*` dynamic var. nil outside a drain.
    `fs-after`  — the state at destroy-time: the live frame-state value read
                  at the TOP of `destroy-frame!`, before any teardown step
                  mutated or removed the container. The partial cascade's
                  already-committed writes survive in this value; once
                  teardown runs the live container can no longer be read
                  (`frame-state-value` returns nil for a destroyed frame).

  Both snapshots are captured BEFORE the frame is removed and passed
  explicitly so the epoch surface (which fires AFTER `dissoc-frame!`,
  step 6) does not have to read a container that is already gone — reading it
  there would yield nil-`:db-before` / nil-`:db-after` records.

  `committed-at` is the destroying event's causal `:rf/time-ms`
  (the router-bound `*cascade-time-ms*`), threaded so the `:halted-destroy`
  record's `:committed-at` is replayable per EP-0010 §Time rather than an
  ambient host-clock read. nil outside a drain (the moot out-of-cascade
  destroy commits no record)."
  [id fs-before fs-after committed-at]
  (safe-call-hook! :epoch/on-frame-destroyed id fs-before fs-after committed-at))

(defn destroy-frame!
  "Tear down a frame. Per Spec 002 §Destroy, the ordered steps are:

    1. fire-on-destroy-event!       — run user :on-destroy while frame
                                      is still alive.
    2. notify-machine-destruction!  — per Spec 005 §Cross-Spec Interactions §1:
                                      delegates to the machines artefact's
                                      `:machines/teardown-on-frame-destroy!`
                                      hook. That walks each
                                      active machine in reverse-creation
                                      order: runs the `:exit` cascade
                                      against a live container, applies
                                      the unified teardown projection
                                      (snapshot + system-id + spawn-slot
                                      prune), unregisters the live handler,
                                      and emits
                                      `:rf.machine.lifecycle/destroyed`
                                      with :reason :parent-frame-destroyed.
                                      Falls back to minimal HTTP-abort +
                                      trace when the machines artefact is
                                      absent.
    3. mark-frame-destroyed!        — flip :lifecycle :destroyed?.
    4. tear-down-sub-cache!         — dispose every cached reaction
                                      via the sub-cache-owned
                                      `:subs.cache/dispose-all-for-
                                      frame-destroy!` hook, so each
                                      eviction emits `:rf.sub/dispose`
                                      with `:rf.sub/reason
                                      :frame-destroy`.
    *. cleanup hooks (best-effort, no-op when artefact absent):
         :elision/clear-warning-cache!      — reset schema-first elision
                                              warning cache.
         :ssr/on-frame-destroyed            — clear SSR side-channel
                                              atoms for this frame.
         :machines/on-frame-destroyed!      — clear the machines
                                              artefact's frame-scoped
                                              `:after` timer table.
         :schemas/on-frame-destroyed!       — drop schemas registered
                                              against this frame.
         :flows/teardown-on-frame-destroy!  — drop flows + last-inputs
                                              rows + dead `:flow`
                                              registrar slots.
         :routing/on-frame-destroyed!       — release the frame's
                                              host-side transient routing
                                              caches — scroll positions +
                                              nav-token / pending-nav
                                              counters.
         :resources/on-frame-destroyed!     — release the frame's
                                              host-side transient resource
                                              caches — work-ledger host
                                              handles + generation
                                              high-water mark.
    5. emit-frame-destroyed-trace!  — emit :frame/destroyed AFTER the
                                      machine cascade.
    6. dissoc-frame!                — remove from the `frames` atom.
    7. unregister-frame!            — drop from the registrar.
    8. notify-epoch-listeners!      — fire the epoch hook so tools see
                                      :rf.epoch.cb/silenced-on-frame-destroy,
                                      threading the pre-cascade
                                      (`*cascade-frame-state-before*`) and
                                      destroy-time (live frame-state value
                                      captured at the TOP of this fn, before
                                      any teardown) frame-state snapshots so a
                                      mid-drain destroy's :halted-destroy
                                      epoch record carries real
                                      :frame-state-before / :frame-state-after
                                      (and their :db-* app-db projections) per
                                      Spec-Schemas §:rf/epoch-record §Outcomes.

  Subsequent dispatch / subscribe against a destroyed frame raises
  :rf.error/frame-destroyed.

  Re-entrancy: if `destroy-frame!` is called for `id` while
  an outer `destroy-frame!` for the same `id` is still on the stack
  (e.g. the user's `:on-destroy` handler itself calls `destroy-frame!`,
  or a machine `:exit` cascade does so), the re-entrant call is a
  silent no-op — the outer call's teardown is already in flight and
  re-running the recipe would re-fire `:on-destroy`, re-run the
  machine cascade, and corrupt the half-torn-down state. Idempotent
  destroy is the existing pattern (a destroyed frame's `(frame id)`
  lookup already returns nil, so a *later* `destroy-frame!` short-
  circuits at the outer `when-let`); the in-flight guard closes the
  RE-ENTRANT window before `mark-frame-destroyed!` flips the flag.

  EP-0024: the target may be a frame-id KEYWORD or a frame VALUE
  (`rf/make-frame`'s return token). A value is normalized to its id via
  `frame-target->id` so the whole recipe keys the ONE registry's record
  unchanged; `dissoc-frame!` IS the forget (the resolved generation rides the
  record, dropped with it — one registry, no separate forget hook)."
  [target]
  ;; Accept a frame VALUE or a frame-id keyword. Normalize a value to its id so
  ;; every keyed teardown step below targets the record; a keyword passes
  ;; through unchanged.
  (let [id (frame-target->id target)]
  ;; Re-entrancy guard: short-circuit if we're already destroying this id.
  ;; Silent no-op (idempotent destroy is a no-op pattern; no new trace event
  ;; needed).
  ;; The registry is keyed by the bare frame-id, so every keyed teardown step
  ;; (the in-flight guard, `mark-frame-destroyed!`, `dissoc-frame!`, the
  ;; registrar unregister) targets the frame-id-keyed record directly.
  (when-let [f (frame id)]
      (when-not (contains? @destroying-frames id)
      (swap! destroying-frames conj id)
      ;; Capture the DESTROY-TIME frame-state value BEFORE any teardown step
      ;; runs. After `mark-frame-destroyed!` (step 3) flips :destroyed?,
      ;; `frame-state-value` returns nil; after `dissoc-frame!` (step 6)
      ;; the container is gone entirely. Reading it here yields the state
      ;; the partial cascade left the frame in at the moment destroy was
      ;; requested — the `:frame-state-after` slot the `:halted-destroy`
      ;; epoch record carries. The pre-cascade `:frame-state-before` rides the
      ;; router-bound `*cascade-frame-state-before*` dynamic var (nil outside a
      ;; drain). Both are passed to `notify-epoch-listeners!` (step 8): the
      ;; whole frame-state, both partitions.
      (let [cascade-fs-before *cascade-frame-state-before*
            ;; The destroying event's causal `:time-ms`, bound by
            ;; the router alongside `*cascade-frame-state-before*`. Threaded to
            ;; the epoch hook so the `:halted-destroy` record's `:committed-at`
            ;; is replayable (per EP-0010 §Time). nil outside a drain.
            cascade-time-ms   *cascade-time-ms*
            fs-at-destroy     (frame-state-value id)
            ;; EP-0008 R1: per-destroy accumulator for
            ;; cleanup-hook failures. `safe-call-hook!` conj's an entry per
            ;; failed hook; the finally-shaped flush below ships them as ONE
            ;; always-on `:rf.error/frame-teardown-failed` report. Held in a
            ;; side atom so a mid-teardown abort still flushes the entries
            ;; gathered so far (the entries are already in the atom when the
            ;; `finally` runs).
            hook-failures     (atom [])]
       (binding [*destroying-frame-id*    id
                 *teardown-hook-failures* hook-failures]
        (try
        (fire-on-destroy-event! id f)
        (notify-machine-destruction! id)
        (mark-frame-destroyed! id)
        (tear-down-sub-cache! id f)
        ;; Dispose the app-db / runtime-db projection reactions
        ;; AFTER the sub-cache (the sub-cache's layer-1 reactions watch the
        ;; app-db projection; disposing the projection first would orphan
        ;; their source watch). The projections watch the physical
        ;; frame-state container; disposing here releases those watches.
        (tear-down-partition-projections! f)
        (safe-call-hook! :elision/clear-warning-cache!)
        (safe-call-hook! :ssr/on-frame-destroyed id)
        (safe-call-hook! :machines/on-frame-destroyed! id)
        ;; Drop every schema registered against
        ;; the destroyed frame so a re-registered frame starts with a
        ;; clean schema slate. Without this hook, orphan app-db schemas
        ;; from a prior `reg-frame` cycle persist and re-fire under the
        ;; rollback contract — manifesting as spurious rollbacks against
        ;; paths the new frame's :initial-events never wrote. No-op when
        ;; re-frame.schemas is absent (the artefact is optional).
        (safe-call-hook! :schemas/on-frame-destroyed! id)
        ;; Drop every flow registered against the destroyed
        ;; frame plus its cached `last-inputs` rows, and prune the
        ;; `:flow` registrar slot when the destroyed frame was the last
        ;; owner. Symmetric with the machines teardown hook above.
        ;; Without this hook a long-running SSR JVM with
        ;; per-request frame churn grows the flow registry unboundedly.
        ;; This hook does NOT scrub the frame's flow-output elision marks:
        ;; those live in the runtime-db partition INSIDE the
        ;; `:frame-state` container, which `dissoc-frame!` (step 6 below)
        ;; drops wholesale with the frame record — a per-flow scrub here
        ;; would be redundant work over about-to-be-GC'd state, and a reused
        ;; frame-id gets a fresh empty container so no stale flow-sourced
        ;; declaration survives the cycle (see the flows
        ;; `teardown-on-frame-destroy!` docstring).
        ;; No-op when re-frame.flows is absent (the artefact is optional).
        (safe-call-hook! :flows/teardown-on-frame-destroy! id)
        ;; Release the destroyed frame's host-side
        ;; transient routing caches — scroll positions
        ;; (re-frame.routing.scroll) AND the nav-token / pending-nav counter
        ;; high-water marks (re-frame.routing.nav-counters). Neither is
        ;; runtime-db state — they live in module-level atoms (host-derived,
        ;; ephemeral, off the epoch/SSR egress wire; the counters host-side
        ;; so an epoch restore cannot rewind + recycle a token). Without this
        ;; hook a long-running multi-frame / per-request-frame process leaks
        ;; one entry per destroyed frame in each cache. No-op when
        ;; re-frame.routing is absent (the artefact is optional).
        (safe-call-hook! :routing/on-frame-destroyed! id)
        ;; Release the destroyed frame's host-side transient
        ;; RESOURCE caches — the work-ledger host handles
        ;; (re-frame.resources.work-ledger/handle-table, the AbortControllers
        ;; / timer handles keyed by [frame-id work-id]) AND the resource
        ;; generation high-water mark (re-frame.resources.state/generation-
        ;; cache). Neither is runtime-db state — both live in module-level
        ;; atoms (host-derived, ephemeral, off the epoch/SSR egress wire; the
        ;; generation host-side so an epoch restore cannot rewind + recycle a
        ;; generation). The durable serializable work records + cache entries
        ;; ride the dropped frame value. Without this hook a long-running
        ;; multi-frame / per-request-frame process leaks one entry per
        ;; destroyed frame in each host cache. No-op when re-frame.resources
        ;; is absent (the artefact is optional).
        (safe-call-hook! :resources/on-frame-destroyed! id)
        ;; Cancel + drop the destroyed frame's still-pending
        ;; `:dispatch-later` host timers (rf2-uxz52g). Each arms a host-clock
        ;; timer whose thunk dispatches the deferred event into THIS frame;
        ;; left armed across destroy it fires a dead-on-arrival dispatch into
        ;; a torn-down frame, and its armed handle + captured closure leak
        ;; until the delay elapses (unbounded under frame churn in long-running
        ;; SSR / test processes). The handles live in a host-side side table
        ;; in `re-frame.fx` (NOT runtime-db — off the epoch/SSR egress wire),
        ;; mirroring the resources / machines timer tables; this hook releases
        ;; the frame's slice. Reached via late-bind because `re-frame.fx`
        ;; static-requires nothing of `re-frame.frame` (a back-require would
        ;; invert the load order); the hook is bound at boot since fx ships in
        ;; every canonical build.
        (safe-call-hook! :fx/on-frame-destroyed! id)
        ;; The shipped subsystems tear down via the named ordered hooks above.
        (emit-frame-destroyed-trace! id)
        ;; Per Spec 009 §Per-frame trace rings:
        ;; release the destroyed frame's cascade-keyed ring so no
        ;; residual trace events leak across the frame lifecycle. Fired
        ;; AFTER `:rf.frame/destroyed` emits so the destroyed trace
        ;; itself (which is frameless and bypasses the ring anyway)
        ;; still flows through the live stream cleanly. Routed via
        ;; late-bind so production CLJS bundles (no trace.tooling) no-op.
        (safe-call-hook! :trace.tooling/release-frame-ring! id)
        ;; rf2-zcl055: release the destroyed frame's trace-emission gate
        ;; flag — the teardown counterpart to `reg-frame`'s
        ;; `trace/set-frame-no-emit!`. A tool / inspector frame registered
        ;; with `:rf.trace/frame-no-emit? true` (e.g. `:rf/xray`) otherwise
        ;; leaves a permanent entry in trace.cljc's process-global
        ;; `trace-disabled-frames` set (the ring IS freed above; the flag
        ;; was not — a teardown asymmetry). Called directly (not via a
        ;; tooling hook) because `trace.cljc` is always loaded — the set +
        ;; predicate live on the core trace surface, same as the `reg-frame`
        ;; registration call. Idempotent no-op for application frames (the
        ;; common case, where the id was never added).
        (trace/clear-frame-no-emit-for! id)
        ;; EP-0024: there is ONE `frames` registry, and `dissoc-frame!` below IS
        ;; the forget. The frame's resolved generation rides the record's
        ;; `:generation` slot, so dropping the record drops it too — no separate
        ;; forget hook is needed.
        (dissoc-frame! id)
        (unregister-frame! id)
        (notify-epoch-listeners! id cascade-fs-before fs-at-destroy cascade-time-ms)
        nil
        (finally
          ;; EP-0008 R1 — FINALLY-shaped flush of the always-on
          ;; teardown report. If any cleanup hook threw (entries accumulated
          ;; in `hook-failures`), ship ONE bounded
          ;; `:rf.error/frame-teardown-failed` record carrying the
          ;; `:hook-failures` vector. Running this in the `finally` is the
          ;; emit-safety contract: even if a downstream teardown step aborts
          ;; the walk mid-recipe (after, say, hook 3 of 7), the entries
          ;; collected so far are already in the atom and STILL flush — the
          ;; single-report shape does not sacrifice incremental delivery
          ;; against a mid-teardown collapse (Spec 009 §Emit-safety). Reached
          ;; via late-bind (`error-emit` → `elision` → `frame` is a load
          ;; cycle); no-op when no hook failed (the report fn short-circuits
          ;; on an empty vector). The flush itself is wrapped so a fault in
          ;; the always-on substrate can never strand the in-flight marker.
          (let [failures @hook-failures]
            (when (seq failures)
              (when-let [emit-report (late-bind/get-fn
                                       :error-emit/dispatch-frame-teardown-report)]
                (try
                  (emit-report id failures (interop/now-ms))
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))
          ;; Always clear the in-flight marker — even if a downstream step
          ;; throws unexpectedly, future `destroy-frame!` calls for `id`
          ;; (after a fresh `reg-frame`) must not see a stale entry.
          (swap! destroying-frames disj id)))))))))

(defn reset-frame!
  "destroy-frame! followed by reg-frame with the same config. Per Spec 002
  §reset-frame! — full replace, opt-in. The destroy + re-register target the
  frame-id-keyed record directly.

  EP-0027 §Reset: because the recorded `:initial-events` is DURABLE frame config
  (it stays on `:config`), re-registering with that config RE-DISPATCHES the
  recorded `:initial-events` — the only thing that replays the setup, in place of
  the old `:on-create` re-fire. The replay is a BEST-EFFORT re-run through the
  CURRENT handlers (a vector now, exactly as `:on-create` re-fired one event
  before): no snapshot, no replay tape, no atomicity. Because construction is
  events-only, the recorded script IS the constructed state — there is no
  separate baseline to restore. The other app-db reset verbs
  (`reset-app-db!` / `replace-app-db!`) are unchanged.

  IMAGE-LOADED FRAMES (EP-0024, rf2-qnk02m). A frame created via
  `make-frame {:images …}` runs against its OWN resolved image GENERATION (the
  `:generation` slot), and that generation is the registration namespace its
  `:initial-events` replay resolves `(kind, id)` against. The stored `:config`
  carries NEITHER `:images` (consumed by `make-frame` BEFORE `reg-frame` saw it)
  NOR `:rf.frame/generation` (stripped by `new-frame-record` / the re-reg path
  into the `:generation` slot). So a naive `(reg-frame id (:config f))` recreated
  the frame with `:generation` nil — silently DEGRADING an image-loaded frame to
  registrar resolution: if the image carried INLINE-ONLY registrations (present
  only in the generation, never the global registrar), the `:initial-events`
  replay would dispatch events whose handlers no longer resolve, and
  `dispatch-sync` TRACES-and-recovers an unregistered handler (no throw) — leaving
  a LIVE frame in a wrong/empty state with NO loud signal.

  Reset replays against the SAME resolved frame definition, so the recreated
  frame MUST keep the same generation. We SNAPSHOT the live `:generation` BEFORE
  `destroy-frame!` and re-thread it through the reserved `:rf.frame/generation`
  construction key, so `new-frame-record` seats it onto the recreated record's
  `:generation` slot BEFORE the `:initial-events` replay runs — the replay then
  resolves through the frame's OWN image generation exactly as the original
  construction did. An ordinary (no-image) frame carries `:generation` nil; the
  re-thread is a no-op there (nil ⇒ registrar resolution, byte-identical to
  before).

  MID-CASCADE GUARD (EP-0027, rf2-y6uzx8). `reset-frame!` is a top-level / view
  LIFECYCLE op, not a handler op — like construction (a handler mutates app-db;
  views and top-level materialize / reset frames). Calling it INSIDE an event
  handler (a cascade in flight, `trace/*handler-scope*` bound) is rejected
  LOUD with `:rf.error/frame-reset-in-handler` BEFORE any teardown. Without
  this preflight the sequence was: `destroy-frame!` succeeds (no handler-scope
  guard on destroy) — the frame is GONE — then the re-`reg-frame` hits the
  construction-in-handler guard and throws, leaving the frame
  DESTROYED-AND-NOT-RECREATED (a half-completed reset, the live app a frame
  short, signalled by an error naming the WRONG cause). The up-front rejection
  is atomic: no partial teardown."
  [id]
  ;; EP-0027 §Reset (rf2-y6uzx8): reject a mid-cascade reset BEFORE the destroy.
  (when trace/*handler-scope*
    (error/throw-error!
      :rf.error/frame-reset-in-handler
      'rf/reset-frame!
      (str "resetting a frame inside an event handler is not supported "
           "(EP-0027) — got reset-frame! " (pr-str id) " while a cascade is in "
           "flight. reset-frame! is a top-level / view LIFECYCLE op (it destroys "
           "and re-constructs the frame, re-running :initial-events); a handler "
           "changes app-db, and the view materializes / resets frames from it. "
           "Move the reset to a frame-provider in the view tree, or to top-level "
           "boot. (Rejected up front so no partial teardown is left — the frame "
           "is untouched.)")
      {:recovery :reset-frames-in-view-or-top-level
       :extra    {:frame id}}))
  (when-let [f (frame id)]
    (let [;; Snapshot the resolved image generation BEFORE destroy so the
          ;; recreated frame keeps the SAME image-derived registrations
          ;; (rf2-qnk02m). nil for an ordinary configured frame — the re-thread
          ;; is then a no-op (registrar resolution, unchanged).
          generation (:generation f)
          ;; Re-thread the generation via the reserved construction key only when
          ;; present, so an ordinary frame's config is byte-identical to before.
          config     (cond-> (:config f)
                       (some? generation) (assoc :rf.frame/generation generation))]
      (destroy-frame! id)
      (reg-frame id config))))

;; ---- :rf/default — TEST-ONLY fixture helper -------------------------------
;;
;; Per Spec 002 §`:rf/default` is an ordinary id: `:rf/default`
;; is NOT created by `init!`, is NOT the React-context default, is NOT a
;; lookup tier, and is NOT inferred from a missing stamp. The runtime never
;; synthesises it.
;;
;; This helper is a convenience for TEST FIXTURES that pin
;; `*current-frame*` to `:rf/default` and dispatch ambiently — the standard
;; `re-frame.test-support/make-reset-runtime-fixture` and the per-suite
;; reset-runtime fixtures across the adapter / SSR test trees call it to
;; establish a known default scope. It is a TEST PATH, not a runtime path:
;; no production / SSR code reaches it (real ambient call sites carry an
;; explicit frame). The name + this banner make the test-only intent
;; unambiguous.

(defn ensure-default-frame!
  "TEST-ONLY fixture helper. Register the ordinary `:rf/default` frame if
  absent (idempotent), so a test that pins `*current-frame*` to
  `:rf/default` and dispatches ambiently has a frame to land on.

  NOT a runtime path — `init!` does NOT call this (per Spec 002
  §`:rf/default` is an ordinary id: the runtime never synthesises
  a default frame). Application / SSR boot code that wants a default-named
  app frame registers it explicitly via `(rf/reg-frame :rf/default {…})`."
  []
  (when-not (get @frames :rf/default)
    (reg-frame :rf/default {:doc "Test-fixture default frame (ordinary id; not a runtime floor)."})))
