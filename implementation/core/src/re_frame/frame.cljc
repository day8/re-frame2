(ns re-frame.frame
  "Frame container, lifecycle, and the frame registry. Per Spec 002.

  A frame is an isolated runtime boundary identified by a keyword. Every
  frame holds its own app-db (a substrate-managed reactive container),
  its own per-frame router queue, and its own sub-cache.

  Frames are not values — they are mutable runtime objects. User code
  holds keywords; this namespace holds the frame records.

  Reserved frame ids:
    :rf/default              — an ORDINARY frame id (per Spec 002 §`:rf/default`
                              is an ordinary id, EP-0002). It carries NO
                              framework privilege: the runtime never creates
                              it, never infers it from a missing stamp, and
                              never uses it as a resolution floor. A small
                              app, example, or test may register and select
                              it EXPLICITLY like any other id.
    :rf.frame/<gensym>       — anonymous instances from make-anon-frame-record!"
  (:require [clojure.string]
            [re-frame.error :as error]
            [re-frame.registrar :as registrar]
            [re-frame.realm :as realm]
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
;;   :realm       the id of the runtime realm the frame belongs to for its
;;                lifetime (EP-0013 D1, Spec 002 §Frames reference realms).
;;                A frame REFERENCES its realm internally; the registrar,
;;                adapter, and capabilities are the realm's, not the frame's.
;;                In a single-realm app this is `realm/default-realm-id` —
;;                a single-frame/single-realm app never spells a realm
;;                (the EP-0002 refinement pattern). The reference is the
;;                realm-id keyword (not the realm map) so the frame record
;;                stays a plain value the (realm, frame) address is built on.
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
;; §Frame-state container and partition projections (EP-0001 decision #3):
;; the frame holds ONE physical frame-state container; app-db and runtime-db
;; are PROJECTION REACTIONS over it. Partition-aware sub-cache invalidation
;; falls out of `make-derived-value`'s memoised `=`-equality — NO dirty flags
;; (decision #7): a runtime-only commit recomputes the app-db projection,
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

;; ---- EP-0023 runnable frame OBJECT marker + target normalization ----------
;;
;; EP-0023 collapse slice 1 (rf2-32siq3.32): `re-frame.live-frame/make-frame`
;; returns a SINGLE runnable image-loaded frame OBJECT — a map carrying the
;; resolved image generation AND a reference (`:rf.frame/runnable-id`) to the
;; backing EP-0013 runnable RECORD this ns owns in `frames`. The object IS the
;; public live frame; its runnable interior (app-db / runtime-db / queue /
;; sub-cache / lifecycle) is the record reached by the runnable-id (EP-0023
;; §Frame — "the live frame object owns app-db, runtime-db, event queue and
;; drain state, subscription cache, ... a reference to the resolved image
;; generation it is running").
;;
;; The PUBLIC target a dispatch / subscribe / destroy / app-db read addresses is
;; a frame — usually a frame id KEYWORD (the public routing address), sometimes
;; a frame VALUE the lifecycle APIs return (EP-0024 Operation target grammar —
;; "the API teaches one routing address: the frame id"; internal normalization
;; still accepts a frame value for tests/tools). Every runnable subsystem
;; resolves per-frame state through a frame-id ADDRESS keyed into `frames` (the
;; ONE registry — the universal chokepoint: the router queue/drain,
;; `commit-frame-transition!`, the sub-cache, cofx, elision, …). So a frame
;; VALUE target is normalized to its id at the public entry, and every
;; bare-`frame-id`-keyed operation downstream stays byte-identical.
;;
;; EP-0024 (rf2-tu2vr7) — the frame VALUE is the live lifecycle token
;; `make-frame` returns. Its representation is NOT an app-facing data contract:
;; it carries the `:rf.frame/object` marker (so a value target is discriminated
;; structurally from a keyword id) and `:rf.frame/runnable-id` (= the frame id
;; its record is keyed by in the one `frames` registry). The resolved image
;; generation is NOT embedded on the value — it lives on the record (the
;; `:generation` slot), read by id. `frame-value->id` is the single public
;; accessor from a frame value to its id (Open Issue #2 — representation hidden).

(def ^:const object-marker
  "Reserved frame-value marker key. A `true` value at this key on a map means
  \"this is a live frame VALUE\" (EP-0024 Term: Frame value) — the structural
  discriminator a target-resolution site uses to tell a frame value from a
  frame-id keyword. The frame value's representation is not an app-facing data
  contract; this marker is internal."
  :rf.frame/object)

(def ^:const runnable-id-key
  "Reserved key on a frame VALUE naming the frame id its record is keyed by in
  the one `frames` registry (EP-0024, rf2-tu2vr7). For an `:id`-bearing value
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
  frame id its record is keyed by in the one `frames` registry (EP-0024,
  rf2-tu2vr7). A frame VALUE (carrying `:rf.frame/object true`) yields its
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
  ^{:doc "Map of frame-ADDRESS → frame-record. Per-process (one global frame
  registry). EP-0013 step 4 (rf2-a15n62): the key is a FRAME ADDRESS — the bare
  frame-id keyword for a DEFAULT-realm frame (byte-identical to the pre-realm
  registry, so every single-realm app and every existing `(frame id)` call
  resolves unchanged), or the `[realm-id frame-id]` pair for a NON-default-realm
  frame, so the SAME frame id is legal in two realms (Spec 002 §Frames reference
  realms — frame ids are unique WITHIN a realm). See `frame-key`."}
  frames
  (atom {}))

;; ---- frame address (the (realm, frame) key) — EP-0013 step 4, rf2-a15n62 ----
;;
;; A frame REFERENCES its realm (Spec 002 §Frames reference realms). The same
;; frame id is legal in two different realms, so the `frames` registry is keyed
;; by a FRAME ADDRESS, not the bare id. The address is the (realm, frame) pair —
;; but for the DEFAULT realm it COLLAPSES to the bare frame-id keyword, so the
;; single-realm path (every existing app, every existing `(frame id)` call site)
;; is byte-identical: the key, the lookup, the `swap! frames assoc`, and every
;; tool's `@frames` read are unchanged. Only a non-default-realm frame keys by
;; the `[realm-id frame-id]` vector, which a bare-id lookup never collides with.
;;
;; `*current-realm*` is the CARRIED realm dimension — the realm half of the
;; (realm, frame) address the dispatch / subscribe envelope carries. The router
;; (`process-event!`) and `subscribe` bind it from the envelope's `:rf.realm/id`
;; for the duration of a cascade / build, so the 1-arity ambient `(frame id)`
;; lookups inside resolve to the carried realm's frame. nil ⇒ the default realm
;; (absence-is-default, the documented rule) ⇒ the bare-id key ⇒ zero added
;; lookup cost. DERIVED from the carried address, never an ambient `with-realm`
;; (EP-0002 carried-invariant).

(def ^:dynamic *current-realm*
  "The carried realm-id for the in-flight cascade / subscribe build, or nil for
  the default realm. Bound by the router (`process-event!`) and `subscribe` from
  the carried (realm, frame) address so 1-arity `frame` lookups resolve to the
  owning realm. nil ⇒ default realm ⇒ the byte-identical bare-id key path
  (EP-0013 step 4, rf2-a15n62)."
  nil)

(defn frame-key
  "The `frames`-registry key (frame ADDRESS) for `frame-id` in realm `realm-id`.
  Collapses to the bare `frame-id` keyword for the default realm (nil or
  `realm/default-realm-id`) — the byte-identical single-realm path — and to the
  `[realm-id frame-id]` pair for a non-default realm, so the same id is legal in
  two realms (EP-0013 step 4, rf2-a15n62). INTERNAL."
  [realm-id frame-id]
  (if (or (nil? realm-id)
          (= realm-id realm/default-realm-id))
    frame-id
    [realm-id frame-id]))

;; ---- realm-owned frame-registry view (EP-0013 D1, rf2-gkddyq) -------------
;;
;; The frame registry is realm-owned (Spec 002 §Frames reference realms). In
;; D1 the frame RECORDS live here in `frames`; the realm owns the MEMBERSHIP
;; VIEW, derived live from this atom by grouping on each frame's `:realm`
;; slot. Deriving (rather than maintaining a separate per-realm set) keeps
;; ONE source of truth and means the many `(reset! frame/frames {})` test
;; fixtures reset membership for free. `re-frame.realm` reads this through
;; the `:realm/frames-by-realm` late-bind hook (a static back-require would
;; cycle — frame requires realm for the record's default realm-id).

(defn frames-by-realm
  "Return `realm-id → #{frame-id …}` over the live, non-destroyed frames —
  the realm-owned membership view (EP-0013 D1). A frame contributes to its
  `:realm` slot's set (default realm when the slot is absent). INTERNAL —
  the realm-membership reader; published as `:realm/frames-by-realm`."
  []
  (persistent!
    (reduce-kv
      ;; EP-0013 step 4 (rf2-a15n62): the registry KEY is now a frame ADDRESS
      ;; (bare id for the default realm, `[realm-id frame-id]` for a non-default
      ;; realm), so read the frame-id from the record's own `:id` slot — NOT the
      ;; map key — and group by the record's `:realm` slot.
      (fn [acc _addr f]
        (if (-> f :lifecycle :destroyed?)
          acc
          (let [rid (or (:realm f) realm/default-realm-id)
                fid (:id f)]
            (assoc! acc rid (conj (get acc rid #{}) fid)))))
      (transient {})
      @frames)))

(late-bind/set-fn! :realm/frames-by-realm frames-by-realm)

;; ---- destroy-in-flight guard (rf2-r1ciy) ---------------------------------
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

;; EP-0008 (rf2-ntv9i9.1): monotonic counter for the per-destroy UNIQUE
;; transient `:on-destroy`-throw capture listener key. `fire-on-destroy-event!`
;; installs a listener on the always-on error-emit registry for the duration of
;; the `:on-destroy` dispatch; the registry keys by id (assoc/dissoc). A
;; CONSTANT key (the former `::on-destroy-throw-watch`) let an OVERLAPPING /
;; NESTED destroy — a Spec 002 (rf2-r1ciy) supported shape: an `:on-destroy`
;; handler destroying a DIFFERENT frame — REPLACE the outer destroy's listener
;; under the same key, then DROP it on the inner's finally, so the outer's
;; `:rf.error/handler-exception` was never captured and its dedicated
;; `:rf.error/on-destroy-handler-exception` discriminator was silently dropped.
;; A fresh per-invocation key gives each (possibly nested) destroy its own
;; listener — no clobber, no cross-removal. `defonce` so a hot reload does not
;; rewind the counter mid-flight.
(defonce ^:private on-destroy-watch-counter
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

;; Per Spec 009 §Per-frame trace rings (rf2-g1b2m / rf2-8uwce): publish
;; the in-flight frame-id through `late-bind` so the trace tooling
;; sibling can route emit-site trace events to their owning frame's
;; ring. Returns nil when no cascade is in flight (frameless emits).
;; The hook is sticky (rf2-f72pd) and read on every push-to-ring!.
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
  Provider default is now the no-provider sentinel, NOT `:rf/default`, per
  Spec 002 §`:rf/default` is an ordinary id + the EP-0002 React-context
  bead). When the hook is unbound (no adapter loaded yet, or JVM build)
  the result is `current-frame` — the dynamic-var tier alone; the React-
  context tier silently no-ops to nil.

  This is the canonical scope reader — `subs/subscribe`,
  `router/dispatch*`'s frame computation, and `core/current-frame-id`
  delegate here so the React-context tier is single-sourced (rf2-jj8xf).
  Public frame-scoped operations that must have a frame call
  `require-current-frame!`, which is built on this reader."
  []
  ;; Sticky hook (rf2-f72pd) — `:adapter/current-frame` is published
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
;; Distinct from `:rf.error/no-frame-context` (rf2-9kpigo). A public
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
;; route descendants to a registered `:app` frame — the bug rf2-9kpigo
;; describes. Validating at the public provider entry points stops the bad
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
  "Validate a public `frame-provider`'s `:frame` arg (rf2-9kpigo). Returns
  `frame-kw` unchanged when it is a keyword. A nil value routes to the
  existing `:rf.error/no-frame-context` path (absence — the provider
  establishes no usable scope). A non-nil non-keyword value emits + throws
  the distinct `:rf.error/bad-frame-provider-arg` so the bad explicit
  target fails loudly at the provider rather than being silently coerced to
  a registered keyword frame by the lower-level context reader.

  `where` (sym/kw) names the validating call site for the payload. The nil
  branch threads `where` + a `:supply-frame` recovery into the
  no-frame-context payload, matching each provider surface's prior nil
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
  "Return the frame record for a frame ADDRESS, or nil if not registered or
  destroyed.

  EP-0013 step 4 (rf2-a15n62) — the (realm, frame) address:
    (frame id)
      The ambient 1-arity: resolve `id` in the CARRIED realm (`*current-realm*`,
      bound by the router / `subscribe` from the envelope's realm). nil
      `*current-realm*` ⇒ the default realm ⇒ the bare-id key — the
      byte-identical single-realm path (one `nil?` check, then the same map
      lookup as before).
    (frame realm-id id)
      The explicit 2-arity: resolve `id` in `realm-id` directly (a tool / async
      callback / install path that holds the realm). nil `realm-id` ⇒ default.

  2-level lookup written as keyword-invoke (`(-> f :lifecycle :destroyed?)`)
  rather than `(get-in f [:lifecycle :destroyed?])` — `get-in` allocates
  a path vector per call (rf2-mqv4m), and `frame` runs on every dispatch
  / subscribe through `current-frame` resolution."
  ([id]
   ;; Hot path: nil carried realm (the single-realm default) skips `frame-key`
   ;; entirely and does the exact bare-id lookup the pre-realm code did.
   (if (nil? *current-realm*)
     (when-let [f (get @frames id)]
       (when-not (-> f :lifecycle :destroyed?)
         f))
     (frame *current-realm* id)))
  ([realm-id id]
   (when-let [f (get @frames (frame-key realm-id id))]
     (when-not (-> f :lifecycle :destroyed?)
       f))))

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

  EP-0013 step 4 (rf2-a15n62): keyed by the frame ADDRESS — resolve `id` in the
  CARRIED realm (`*current-realm*`, bound by the router around the drain), so a
  non-default-realm frame is found under its `[realm-id frame-id]` key rather
  than reported as `disposed` (absent) from a bare-id miss. nil `*current-realm*`
  ⇒ the bare-id key — the byte-identical default-realm path."
  [id]
  (if-let [f (get @frames (frame-key *current-realm* id))]
    (true? (-> f :lifecycle :destroyed?))
    ;; Absent from the atom — destroy-frame!'s step 6 ran, OR the id
    ;; was never registered. The drain-loop caller only consults this
    ;; while a pass is already in flight, so the latter case cannot
    ;; arise from that seam.
    true))

(defn frame-realm
  "Return the id of the runtime realm `id` belongs to (EP-0013,
  Spec 002 §Frames reference realms), or nil for an unknown / destroyed
  frame. A frame references its realm internally; the registrar, adapter,
  and capabilities are the realm's. In a single-realm app this is
  `realm/default-realm-id` for every frame.

  The frame-side half of the (realm, frame) addressing model — the (realm,
  frame) pair is the full address (EP-0013 disposition 3). Re-exported as
  `rf/frame-realm`: the realm-targeted query surface needs a way to learn a
  frame's realm so a tool can route a `{:realm …}` query to the realm a given
  frame lives in; the realm enumeration half is `re-frame.realm/realm-ids`.
  EP-0023 RETAINS this as an internal / tooling surface over the internal
  installation boundary, NOT current public composition vocabulary — the
  (realm, frame) address is publicly replaced by a single process-local frame
  target (EP-0023 §Surface dispositions / §Id Spaces); a tool may still read a
  frame's realm but should label it as internal substrate."
  [id]
  (when-let [f (frame id)]
    (or (:realm f) realm/default-realm-id)))

(defn frame-address
  "Resolve the (realm, frame) ADDRESS key for `frame-id` — the key a per-frame
  SIDE-CHANNEL (SSR request / response / error-trace / head snapshot, …) keys
  its entries by, so the SAME frame id in two realms addresses two distinct
  entries (EP-0013, Spec 002 §Frames reference realms). The realm dimension is:

    1. the CARRIED realm (`*current-realm*`) when bound — it covers a whole
       dispatch drain AND a frame's destroy teardown (the router binds it via
       `call-with-realm` around both), which is exactly when a side-channel is
       written / cleared; OR
    2. the frame's own `:realm` reference (`frame-realm`) as the read-time
       fallback for a side-channel READ issued outside a drain (a host adapter
       reading the resolved response after the drain settles) while the frame
       record still exists.

  Collapses to the bare `frame-id` keyword for the default realm (nil carried
  realm AND a default-realm / unknown / already-marked-destroyed frame) via
  `frame-key`, so every single-realm side-channel is byte-identical to the
  pre-realm bare-id keying — the default-realm round-trip is preserved with no
  realm key. DERIVED from the carried address, never ambient synthesis
  (EP-0002 carried-invariant). INTERNAL — the addressing seam the SSR
  side-channels share."
  [frame-id]
  (frame-key (or *current-realm* (frame-realm frame-id)) frame-id))

;; ---- realm-routed resolution — the (realm, frame) address binding ----------
;;
;; EP-0013 staging step 4 (rf2-a15n62): a frame REFERENCES its realm, and the
;; realm owns the `(kind, id) → metadata` registrar a dispatch / subscription /
;; fx / cofx resolves against (EP-0013 §Realm Conformance — "frames resolve
;; handlers from their owning realm"). `registrar/active-registrar` reads the
;; dynamic `registrar/*registrar*` (nil ⇒ the process-default), so routing a
;; frame's live resolution through its realm is one dynamic binding DERIVED from
;; the carried (realm, frame) address — NOT an ambient `with-realm` (EP-0002
;; carried-invariant; the realm is read off the frame the envelope already
;; carries, never inferred from process state).
;;
;; PERF — the universal hot path. The default-realm frame (every single-realm
;; app) takes the ZERO-COST path: `frame-record-realm-registrar` returns nil
;; (the realm's registrar IS the process-global atom, so binding it would be a
;; no-op rebind), and `with-frame-realm-registrar` then does NOT bind at all —
;; the cascade resolves through `active-registrar`'s nil-fast path exactly as
;; before, byte-identical. A non-default-realm frame pays one realm-map lookup +
;; one dynamic binding per cascade (not per resolution): the binding is
;; established ONCE at `process-event!` / `subscribe` and covers every
;; event / cofx / fx / sub lookup inside it.

(defn frame-record-realm-registrar
  "Given a FRAME RECORD (already resolved — the hot-path caller holds it),
  return the realm's OWN registrar atom when the frame belongs to a
  NON-default realm, else nil. nil is the signal that no binding is needed —
  the default realm's registrar IS `registrar/kind->id->metadata`, so binding
  it would be a no-op rebind; the caller leaves `registrar/*registrar*` unbound
  (the byte-identical single-realm path). Returns nil for an absent realm
  reference too (defensive — treated as the default realm). INTERNAL — the
  resolution-routing seam (EP-0013 staging step 4, rf2-a15n62)."
  [frame-record]
  (let [rid (:realm frame-record)]
    (when (and rid (not= rid realm/default-realm-id))
      ;; A non-default realm — resolve its OWN registrar atom. `realm/registrar`
      ;; reads the `:registrar` slot off the realm map; nil when the realm was
      ;; disposed out from under a live frame (defensive — falls through to no
      ;; binding, i.e. the default registrar, rather than NPE).
      (when-let [r (realm/realm rid)]
        (realm/registrar r)))))

(defn realm-registrar-for-frame
  "Frame-ID arity of `frame-record-realm-registrar`: resolve the frame record
  for `id`, then its non-default realm registrar (or nil). The subscribe path
  holds a frame-id (not the record) when it routes resolution. INTERNAL."
  [id]
  (when-let [f (frame id)]
    (frame-record-realm-registrar f)))

(defn normalize-realm-id
  "Normalize a `:realm` dispatch opt / argument to a realm-id keyword. Accepts a
  realm MAP, a realm-id KEYWORD (returned unchanged), or nil (⇒ the default
  realm id — absence is the default realm, the documented rule). Delegates to
  `realm/realm-id`. The router uses it to stamp `:rf.realm/id` onto the dispatch
  envelope from the carried `:realm` opt (EP-0013 step 4, rf2-a15n62). INTERNAL."
  [realm-or-id]
  (realm/realm-id realm-or-id))

(defn call-with-realm
  "Invoke `thunk` with `*current-realm*` bound to `realm-id` WHEN it is a
  non-default realm, else invoke `thunk` with NO binding (the byte-identical
  default-realm path — nil `*current-realm*`). Returns the thunk's value. The
  router binds the carried realm around a whole drain so the bare-`frame-id`
  registry lookups inside (`frame`, `frame-state-value`,
  `frame-disposed-for-drain?`) resolve to the owning realm's frame record
  (EP-0013 step 4, rf2-a15n62). A plain fn so CLJS sibling-ns callers need no
  `:require-macros`. DERIVED from the carried address (EP-0002)."
  [realm-id thunk]
  (if (or (nil? realm-id) (= realm-id realm/default-realm-id))
    (thunk)
    (binding [*current-realm* realm-id]
      (thunk))))

(defn call-with-frame-realm-registrar
  "Invoke `thunk` with `registrar/*registrar*` bound to `frame-record`'s realm
  registrar WHEN that frame belongs to a non-default realm; otherwise invoke
  `thunk` with NO binding (the byte-identical default-realm path). Returns the
  thunk's value. This is the resolution-routing seam (EP-0013 staging step 4,
  rf2-a15n62): every event / subscription / fx / cofx lookup inside `thunk`
  resolves through the owning frame's realm registrar coherently
  (ALL-OR-NOTHING — routing only some would be an incoherent half-dispatch).

  DERIVED from the carried (realm, frame) address (the frame the dispatch /
  subscribe envelope carries), never from an ambient binding — the realm is read
  off the frame, not inferred from process state (EP-0002 carried-invariant).

  `frame-record` MUST be the already-resolved frame record (the hot-path callers
  all hold it), so this adds no extra frame lookup. A plain fn (not a macro) so
  CLJS callers in sibling namespaces use it with no `:require-macros` plumbing;
  the JIT inlines the no-binding default-realm path. PERF: the default-realm
  frame pays one `:realm` keyword read + one keyword `=` compare and then runs
  `thunk` with ZERO dynamic-binding cost — byte-identical to the pre-realm path.
  (Keyword equality uses `=`, NOT `identical?` — CLJS keyword literals are not
  reliably reference-equal, so `identical?` on a realm-id would spuriously
  classify the default realm as non-default and mis-key the frame.)"
  [frame-record thunk]
  (if-let [reg (frame-record-realm-registrar frame-record)]
    (binding [registrar/*registrar* reg]
      (thunk))
    (thunk)))

(defn call-in-request-scope
  "Invoke `thunk` with the request frame's FULL realm-aware scope established:
  the carried realm (`*current-realm*`), the realm's resolution registrar
  (`registrar/*registrar*`), AND the operating frame (`*current-frame*`) — the
  composed nesting

    (call-with-realm realm-id
      (fn [] (call-with-frame-realm-registrar (frame frame-id)
               (fn [] (binding [*current-frame* frame-id] (thunk))))))

  i.e. exactly what `with-frame` expands to (`*current-frame*` rebind), wrapped
  by the two realm bindings. This is the host-adapter request-scope seam (the
  SSR-ring non-streaming render walk, the streaming shell / continuation /
  final-payload drains, and the render-time error-projection path all share it):
  every per-frame side channel the body touches addresses the `(realm, frame)`
  slot (`frame-address`), every registered-view / head / route lookup resolves
  through the owning realm's registrar, and `*current-frame*` is the body's
  operating frame.

  EP-0013 §Realm Conformance (rf2-bzw8gd / rf2-nu5w48 / rf2-tbr67x): the THREE
  bindings are coherent and ALL-OR-NOTHING — a site that bound the realm + the
  frame but FORGOT the registrar would render the default realm's views from a
  non-default-realm frame (a realm-leak). Single-sourcing the nest here removes
  that per-site footgun.

  `realm-id` is supplied EXPLICITLY rather than re-derived from `frame-id`
  because the streaming writer runs on a DAEMON thread with no ambient
  `*current-realm*`: there, `(frame frame-id)` under a nil carried realm would
  MISS a non-default-realm frame (it is keyed by `[realm frame-id]`, not the
  bare id) and `frame-realm` would yield nil. The request thread captures the
  realm-id (`frame-realm`) while `*current-realm*` is bound and hands it across
  the thread boundary. `(frame frame-id)` for the registrar is resolved INSIDE
  the realm binding, so it finds the realm frame on either thread. nil / default
  `realm-id` ⇒ NO realm binding and (for a default-realm frame) NO registrar
  binding — the byte-identical single-realm path; only `*current-frame*` is
  bound, exactly as a bare `with-frame` would. A plain fn (not a macro) so CLJS
  sibling-ns callers need no `:require-macros`."
  [realm-id frame-id thunk]
  (call-with-realm realm-id
    (fn []
      (call-with-frame-realm-registrar (frame frame-id)
        (fn []
          (binding [*current-frame* frame-id]
            (thunk)))))))

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
  "Transducer over `@frames` `[addr record]` pairs → the `:id` of each
  registered, non-destroyed frame. The shared front of both `frame-ids`
  arities (the 1-arity composes a prefix filter after it). The frame-id is
  read from the record's own `:id` slot, NOT the map key, since the key is a
  frame ADDRESS (EP-0013 step 4, rf2-a15n62)."
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

  EP-0013 step 4 (rf2-a15n62): the `frames` registry is keyed by frame ADDRESS
  (bare id for the default realm, `[realm-id frame-id]` for a non-default realm),
  so the frame-id is read from each record's own `:id` slot — NOT the map key.
  The returned set is frame-IDS across ALL realms (the same id appearing in two
  realms collapses to one entry, since these are unqualified ids); a tool that
  wants per-realm membership uses `re-frame.realm/realm-frames`."
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
  frames — excluded). The single selection predicate `image-loaded-frame-ids`
  and `image-loaded-frame-addresses` share; both differ only in what they
  PROJECT off the selected record. INTERNAL."
  [f]
  (and (some? (:generation f))
       (not (-> f :lifecycle :destroyed?))
       (not= "rf.frame" (namespace (:id f)))))

(defn image-loaded-frame-ids
  "Return the set of PUBLIC frame ids whose record currently carries a resolved
  image GENERATION — the image-loaded frames the hot-reload reprojection path
  enumerates (EP-0024, rf2-tu2vr7). The derived read that replaced the dissolved
  second live-frame registry's `live-frame-ids`: an image-loaded frame is now
  just a `frames`-registry record with a non-nil `:generation` slot, so this is
  a filter over the ONE registry, not a separate index.

  EXCLUDES no-id (direct) frames — a frame created with no `:id` is keyed by a
  private `:rf.frame/<gensym>` id; like the dissolved registry's `live-frame-ids`
  (which kept only `:rf.frame/id`-bearing entries), this enumeration drops the
  reserved `:rf.frame/` namespace so the reprojection / enumeration path never
  touches a harness-local frame the spec says its owner reloads explicitly
  (EP-0023 §Frame — direct frames bypass auto-reprojection). Excludes destroyed
  frames."
  []
  (into #{}
        (comp (filter (fn [[_ f]] (image-loaded-frame-record? f)))
              (map (fn [[_ f]] (:id f))))
        @frames))

(defn image-loaded-frame-addresses
  "Return the image-loaded frames as `{:realm <realm-id> :id <frame-id>}` maps —
  the realm-AWARE companion to `image-loaded-frame-ids` the hot-reload
  reprojection path enumerates. SAME selection (a `frames`-registry record with a
  non-nil `:generation`, not destroyed, public `:id` — the `:rf.frame/` gensym
  namespace excluded), but each entry also carries the frame's OWN realm
  reference, read off the record's `:realm` slot (default-realm when absent), NOT
  off the dynamic `*current-realm*`.

  Reprojection runs on a `next-tick` flush where `*current-realm*` has unwound to
  nil, so a non-default-realm image-loaded frame can only be re-keyed correctly if
  its realm travels WITH its id (EP-0013 — the registry key is the (realm, frame)
  ADDRESS, and a frame REFERENCES its realm). `reproject-live-frames!` binds
  `*current-realm*` per frame from this `:realm` (via `call-with-realm`) before the
  per-frame generation read / capability read / `set-generation!` swap, so each of
  those re-keys to the frame's OWN address rather than missing on a bare-id lookup.
  INTERNAL."
  []
  (into #{}
        (comp (filter (fn [[_ f]] (image-loaded-frame-record? f)))
              (map (fn [[_ f]] {:realm (or (:realm f) realm/default-realm-id)
                                :id    (:id f)})))
        @frames))

;; ---- the internal value-read frame resolver seam (EP-0024, rf2-az1ct6) ----
;;
;; The value-read helpers below all share one shape: resolve the frame record
;; for an id (honouring the carried realm + the destroyed? guard via `frame`),
;; take ONE slot off it, and — for the *-value readers — deref that slot's
;; container through the substrate adapter. rf2-az1ct6 factors that repeated
;; "resolve record → take slot (→ read container)" mechanics into ONE internal
;; seam so the readers do not each re-implement it. No public grammar changes:
;; `frame` is still the record resolver, the per-slot accessors keep their
;; names + nil-on-unknown/destroyed contract; only the duplication is removed.

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
  `frame-runtime-db-value` / `frame-state-value`) funnel through (rf2-az1ct6).
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
  "Return the host capability map frame `id` was created with (EP-0024,
  rf2-tu2vr7), or nil when the frame supplied none / is unknown. Stored on the
  record's `:config` under the reserved `:rf.frame/capabilities` key by
  `make-frame` so `reload-images!` / reprojection can re-check capabilities by id
  without a second registry holding them. Pure."
  [id]
  (:rf.frame/capabilities (frame-slot id :config)))

(defn frame-adapter
  "Return the active-substrate adapter binding frame `id` was created with
  (EP-0024, rf2-tu2vr7), or nil when the frame supplied none / is unknown.
  Stored on the record's `:config` under the reserved `:rf.frame/adapter` key by
  `make-frame` so tooling (Xray's image/frame view) can read it by id. Pure."
  [id]
  (:rf.frame/adapter (frame-slot id :config)))

(defn set-generation!
  "Swap the resolved image GENERATION on frame `id`'s record IN PLACE,
  preserving every other (state-bearing) slot by identity — the in-place
  generation swap `re-frame.live-frame`'s `make-frame` / `reload-images!` /
  reprojection write through (EP-0024, rf2-tu2vr7). A no-op for an unknown frame
  (the address is keyed by the carried realm via `frame-key`). Returns nil.
  INTERNAL — the one mutator of the `:generation` slot."
  [id generation]
  (let [fkey (frame-key *current-realm* id)]
    (swap! frames (fn [m]
                    (if (contains? m fkey)
                      (update m fkey assoc :generation generation)
                      m))))
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

;; ---- EP-0001 two-partition readers (rf2-q4i9ko / rf2-adwcv6) --------------
;;
;; Per Spec 002 §The two-partition frame contract a frame owns two durable
;; partitions — user `app-db` and framework `runtime-db` — projected as a
;; coherent `frame-state` value `{:rf.db/app … :rf.db/runtime …}`.
;;
;; rf2-q4i9ko (bead 3) introduced the read SURFACE; rf2-adwcv6 (bead 5, this
;; one) makes the physical one-container frame-state + projection reactions
;; real, so `frame-runtime-db-value` now reads the live runtime-db partition.

(defn frame-runtime-db-value
  "Read the current runtime-db partition value for a frame — the
  framework-owned subsystem state. Returns `nil` for an unknown / destroyed
  frame.

  rf2-adwcv6 (bead 5): reads the real `:rf.db/runtime` partition off the one
  physical frame-state container (via the runtime-db projection). A fresh
  frame's runtime-db starts `{}`. Per Spec 002 §The two-partition frame
  contract."
  [id]
  (frame-slot-value id :runtime-db))

(defn frame-state-value
  "Read the coherent frame-state projection for a frame —
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. Returns `nil` for an
  unknown / destroyed frame.

  rf2-adwcv6 (bead 5): reads the one physical frame-state container directly
  (a single deref) rather than composing two reads, so the returned value is
  the exact coherent snapshot the commit installed. Per Spec 002 §The
  two-partition frame contract."
  [id]
  (frame-slot-value id :frame-state))

;; ---- EP-0001 partition commit + write helpers (rf2-adwcv6) ----------------
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
      ;; identical?-noop short-circuit (rf2-ekq28v): when the next frame-state
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

  EP-0001 (rf2-adwcv6): now writes the app-db partition of the physical
  frame-state container (was a direct `replace-container!` on the old app-db
  store). Framework durable state — machines, routing, elision, SSR — no
  longer rides under app-db; those writers use the runtime-db sibling
  `swap-runtime-db!` to mutate the `:rf.db/runtime` partition (`:rf.runtime/*`
  children). This surface mutates only the app-db partition."
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

;; ---- lifecycle-vs-drain serialization (rf2-2woz9) -------------------------
;;
;; Some per-frame registry mutations must be ATOMIC with respect to that
;; frame's event drain — they read-modify-write shared registry state AND
;; app-db, and a concurrent drain that interleaves between the steps can
;; observe a half-applied lifecycle change. The flows artefact has two such
;; ops (rf2-2woz9):
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

(defn call-serialized-with-drain!
  "Run thunk `f` serialized against `frame-id`'s event drain, returning its
  value (rf2-2woz9). Used by per-frame registry mutations that must not
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
;; Per Spec 002 §Frame presets, the v1 closed list is:
;;   :default :test :story :ssr-server

(defn- preset-expansion [preset]
  ;; Per Spec 002 §Frame presets and Spec-Schemas §:rf/preset-expansion.
  ;; The four canonical expansions:
  ;;   :default    -> {} (explicit no-op; identical to omitting :preset)
  ;;   :test       -> redirect :rf.http/managed to its canned-success stub
  ;;                  (Spec 014); explicit :drain-depth 100 (matches the
  ;;                  framework default — surfaced so tooling can read the
  ;;                  bound off frame-meta without consulting the global default);
  ;;                  :rf.cofx/mint-policy :strict (EP-0017 slice-B.8 — a
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
  ;; rf2-cdmle — the :test / :story redirect targets
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
                 ;; EP-0017 §6 / slice-B.8 (rf2-5spzo7): the :test preset
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

(defn- new-frame-record [id realm-id config]
  ;; ONE physical frame-state container holding both partitions (Spec 002
  ;; §One physical container, two projection reactions; EP-0001 decision #3).
  ;; A fresh frame starts with an empty app-db (Spec 002 §Frames always start
  ;; with app-db = {}) and an empty runtime-db.
  (let [;; EP-0024 (rf2-tu2vr7): `make-frame` threads the resolved generation +
        ;; the `:initial-db` seed through the config under reserved keys so they
        ;; are installed on the record BEFORE `reg-frame` fires `:on-create` —
        ;; an `:on-create` cascade then resolves through the frame's OWN image
        ;; generation (not the global registrar) and observes the seeded app-db.
        ;; Both default to absent (an ordinary configured frame).
        seeded-app-db (get config :rf.frame/initial-db)
        frame-state (adapter/make-state-container
                      {app-partition-key     (if (some? seeded-app-db) seeded-app-db {})
                       runtime-partition-key {}})]
   {:id          id
    ;; EP-0024 (rf2-tu2vr7) — the resolved IMAGE GENERATION slot. ONE unified
    ;; frame value owns its resolved generation directly on the single
    ;; `frames`-registry record (Term: Frame value — "owns … resolved image
    ;; generation"); there is no second live-frame registry holding it. nil for
    ;; an ordinary configured frame (no `:images` selection) — the
    ;; absence-is-default signal that resolution falls through to the registrar
    ;; atom path. Threaded in via the reserved `:rf.frame/generation` config key
    ;; so it is live BEFORE `:on-create` runs; `reload-images!` / reprojection
    ;; swap it in place via `set-generation!`, preserving every other
    ;; (state-bearing) slot by identity.
    :generation  (get config :rf.frame/generation)
    ;; EP-0013 (rf2-gkddyq D1 / rf2-a15n62 step 4): the frame REFERENCES the
    ;; runtime realm it belongs to for its lifetime (Spec 002 §Frames reference
    ;; realms). `realm-id` is the OWNING realm — `realm/default-realm-id` for a
    ;; default-realm frame (every single-realm app never spells a realm), or the
    ;; realm `install!` is seating this `:frame` descriptor into (carried via
    ;; `frame/*current-realm*`, which `seat-into-realm!` binds). The stored value
    ;; is the realm-id KEYWORD (the carried (realm, frame) address dimension),
    ;; not the realm map, so the frame record stays a plain value.
    :realm       (or realm-id realm/default-realm-id)
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
    ;; The construction-only reserved keys (`:rf.frame/generation` /
    ;; `:rf.frame/initial-db`) are consumed above into the `:generation` slot +
    ;; the seeded frame-state; they are stripped from the stored `:config` so
    ;; `frame-meta` / tooling never surface a one-shot construction input as
    ;; durable frame config. `:rf.frame/capabilities` stays in `:config` —
    ;; `reload-images!` / reprojection re-read it by id.
    :config     (dissoc config :rf.frame/generation :rf.frame/initial-db)}))

(declare destroy-frame!)

(defn reg-frame
  "Atomic create-and-register. Per Spec 002 §reg-frame is atomic:
  - If the id is unregistered, create the frame container, run :on-create
    events synchronously, return the keyword.
  - If the id is already registered, perform a SURGICAL UPDATE: existing
    runtime state (app-db, sub-cache, queue) is preserved; only the
    metadata/config is replaced. Hot-reload Just Works."
  [id metadata]
  (let [;; EP-0013 step 4 (rf2-a15n62): the OWNING realm of the frame being
        ;; registered. `seat-into-realm!` binds `*current-realm*` to the realm
        ;; `install!` is seating a `:frame` descriptor into; a top-level
        ;; `reg-frame` (the single-realm sugar path) runs with `*current-realm*`
        ;; nil ⇒ the default realm. The frame record is keyed + stamped by this
        ;; realm so the same id is legal in two realms; the bare-id default-realm
        ;; key keeps the single-realm path byte-identical.
        realm-id (or *current-realm* realm/default-realm-id)
        fkey     (frame-key realm-id id)
        config (source-coords/merge-coords (expand-preset metadata))
        ;; EP-0015 §3 (rf2-ueg1tn): validate the frame-owned classification
        ;; keys (`:sensitive` / `:large` / `:observability`) EARLY — pure,
        ;; container-independent, fail-loud. A malformed path / unknown
        ;; classification key / non-string carrier name throws here, BEFORE
        ;; the registrar write and BEFORE any container exists, so a bad
        ;; declaration leaves no half-registered frame and never reaches
        ;; `:on-create`. The extracted result (app-db sensitive/large paths,
        ;; sensitive-wins-resolved) is installed into the durable elision
        ;; registry once the container exists (below, atomically before
        ;; `:on-create`). Reached via late-bind: `re-frame.frame-classification`
        ;; requires `elision` which requires this ns, so a static require
        ;; would cycle; `re-frame.core` requires it at boot so the hook is
        ;; always published before any runtime `reg-frame`. Returns nil when
        ;; the config carries no classification key (the common case).
        classification (when-let [validate (late-bind/get-fn
                                            :frame-classification/validate+extract)]
                         (validate id config))
        install-classification!
        (fn []
          (when-let [install! (late-bind/get-fn :frame-classification/install!)]
            (install! id classification)))]
    (registrar/register! :frame id config)
    ;; Frame-level trace-emission gate (rf2-2qaqh): a frame registered
    ;; with `:rf.trace/frame-no-emit? true` is a tool / inspector frame
    ;; (e.g. Xray's `:rf/xray`) whose own reactive substrate must NOT
    ;; flood the shared trace ring it inspects. The flag is the frame-
    ;; scoped sibling of the handler-scoped `:rf.trace/no-emit?`
    ;; (Spec 009 §Trace-emission opt-out). Honoured on BOTH first
    ;; registration and re-registration so a hot-reload can flip it
    ;; either way; `trace.cljc` owns the canonical set + predicate.
    (trace/set-frame-no-emit! id (true? (:rf.trace/frame-no-emit? config)))
    ;; Per Spec 009 §Retention contract (rf2-g1b2m / rf2-8uwce): apply
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
    (let [existing (get @frames fkey)]
      (cond
        ;; First registration: create everything.
        (nil? existing)
        (let [f (new-frame-record id realm-id config)]
          (swap! frames assoc fkey f)
          ;; EP-0015 §3 (rf2-ueg1tn): install the frame-owned app-db
          ;; classification into the durable elision registry NOW — the
          ;; container exists, and this MUST land before `:on-create` runs
          ;; (a `:rf/path` declared sensitive must be redacted in any trace
          ;; the init cascade emits). Already validated above, so this only
          ;; mutates the runtime-db elision slot; no-op when the config
          ;; carries no classification key.
          (install-classification!)
          ;; Run :on-create events BEFORE emitting :frame/created
          ;; (Spec 002 §Frame creation). The router/dispatch ns is
          ;; reached through late-bind to avoid a cyclic dep at
          ;; compile time.
          ;;
          ;; Per Spec 002 §`reg-frame` / `make-frame` called from inside
          ;; a handler: when a handler creates a child frame mid-
          ;; cascade, the child's `:on-create` MUST be async-queued
          ;; (not dispatch-sync'd) — synchronous dispatch-sync from
          ;; inside a handler is an error, and even were it permitted
          ;; the two cascades would interleave (forbidden by the no-
          ;; cross-frame-drain rule in Spec 002 §Run-to-completion).
          ;;
          ;; The signal for "inside a handler" is `trace/*handler-scope*`
          ;; being bound — the router binds it (via
          ;; `with-dispatch-id+call-site`) for the duration of a handler's
          ;; execution and ONLY then. EP-0002 (rf2-9o48ih): the prior
          ;; signal was `*current-frame*`, but under the carried invariant
          ;; a test harness (or any caller) may establish an AMBIENT
          ;; `with-frame` scope for its bare dispatches — binding
          ;; `*current-frame*` WITHOUT any cascade in flight. That made a
          ;; genuine TOP-LEVEL `reg-frame`/`make-frame` (no handler running)
          ;; look mid-cascade and wrongly async-queue its `:on-create`, so
          ;; the post-creation state was never observable synchronously.
          ;; `*handler-scope*` is bound by the router's per-handler frame
          ;; ONLY — it is set during real cascade processing and is nil
          ;; under a bare ambient scope — so it distinguishes
          ;; "created mid-cascade" (async) from "top-level boot under an
          ;; ambient scope" (synchronous) precisely. Both lifecycle
          ;; contract tests still hold: a child frame reg'd from inside a
          ;; handler async-queues (handler-scope bound); a top-level
          ;; reg-frame runs `:on-create` synchronously (handler-scope nil).
          ;; Per rf2-hxj0d: stamp the frame-init dispatch with
          ;; `:source :frame-init` so the Epoch panel's DISPATCH step
          ;; renders "from frame-init" instead of being mislabelled
          ;; via the previous `:ui` default. Additionally, capture the
          ;; `reg-frame` call-site coord as `:rf.trace/call-site` so
          ;; the click-to-source affordance jumps to the
          ;; `(rf/reg-frame :foo {:on-create [...]})` line. The macro
          ;; form of `reg-frame` (via `defreg-macro`) binds
          ;; `*pending-coords*`, which `source-coords/merge-coords`
          ;; merges directly INTO `config` as `:ns`/`:file`/`:line`/
          ;; `:column` — so the call-site is already on the config
          ;; map, no separate capture path needed. Gated on
          ;; `interop/debug-enabled?` so production CLJS builds DCE
          ;; the call-site read.
          ;;
          ;; rf2-inkdqh: the gate MUST be the OUTERMOST form (the canonical
          ;; `(if interop/debug-enabled? <stamped> <plain>)` shape per Spec
          ;; 009 §Production builds), NOT an `(and interop/debug-enabled?
          ;; ...)` test in a `cond->` step. A `cond->` test-position gate
          ;; leaves the `:rf.trace/call-site` keyword literal reachable in
          ;; the assoc form — Closure does not constant-fold it away under
          ;; `:advanced` + `goog.DEBUG=false`, so the keyword survived into
          ;; production bundles (caught by the elision-probe once the frame-
          ;; registration path was rooted — see
          ;; `re-frame.elision-probe/touch-teardown!`). Splitting the gate
          ;; to the outermost `if` lets DCE prove the whole dev arm dead.
          (when-let [on-create (:on-create config)]
            (let [init-opts (if interop/debug-enabled?
                              (cond-> {:frame  id
                                       :source :frame-init}
                                (or (:file config) (:line config))
                                (assoc :rf.trace/call-site
                                       (cond-> {}
                                         (:ns     config) (assoc :ns     (:ns     config))
                                         (:file   config) (assoc :file   (:file   config))
                                         (:line   config) (assoc :line   (:line   config))
                                         (:column config) (assoc :column (:column config)))))
                              {:frame id :source :frame-init})]
              (if trace/*handler-scope*
                ;; Handler-created child frame (a cascade is in flight):
                ;; async-queue on the child.
                (when-let [dispatch (late-bind/get-fn :router/dispatch!)]
                  (dispatch on-create init-opts))
                ;; Top-level (no in-flight cascade): synchronous, as before.
                (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
                  (dispatch-sync on-create init-opts)))))
          (trace/emit! :rf.frame :rf.frame/created
                       {:frame id :config (dissoc config :rf.frame/generation
                                                  :rf.frame/initial-db)})
          id)

        ;; Re-registration: surgical update of replaceable slots only.
        ;; Per Spec 002 §Re-registration — surgical update.
        :else
        (let [;; EP-0024 (rf2-tu2vr7): idempotent replacement — re-`make-frame`
              ;; threads the freshly-resolved generation under the reserved
              ;; `:rf.frame/generation` config key. Refresh the `:generation`
              ;; slot from it (a re-make WITH new `:images` swaps the running
              ;; generation; a re-make WITHOUT `:images` carries nil and CLEARS
              ;; it back to an ordinary configured frame — matching the
              ;; first-creation contract). `:rf.frame/initial-db` is a
              ;; construction-only seed and is NOT re-applied here — durable
              ;; app-db is preserved across the idempotent re-mount (EP-0024
              ;; §Duplicate id policy). Both reserved keys are stripped from the
              ;; stored `:config`.
              stored-config (dissoc config :rf.frame/generation :rf.frame/initial-db)]
          (swap! frames update fkey
                 assoc :config stored-config :generation (get config :rf.frame/generation))
          ;; EP-0015 §3 (rf2-ueg1tn): re-registration REPLACES frame-owned
          ;; classification — the declaration IS the frame's policy (no
          ;; additive merge). `install!` drops the prior `:source :frame`
          ;; elision entries and overlays the new ones; schema- and
          ;; marks-sourced declarations survive. A re-registration that
          ;; DROPS its classification clears the prior frame-sourced entries
          ;; (absent-key clears, per Spec 002 §Re-registration). Runtime
          ;; state (app-db, sub-cache, queue) is preserved as ever.
          (install-classification!)
          (trace/emit! :rf.frame :rf.frame/re-registered
                       {:frame id :config stored-config})
          id)))))

(defn make-anon-frame-record!
  "INTERNAL anonymous-instance creation (EP-0024, rf2-tu2vr7): generate a
  gensym'd id under `:rf.frame/`, register a configured record under it, and
  return the gensym'd id. This is NOT a public constructor — the ONE public
  constructor is `re-frame.live-frame/make-frame` (`rf/make-frame`), which
  accepts both image-selection AND record-config opts and returns the frame
  VALUE. This id-returning record helper survives as the internal no-`:id`
  configured-record path the unified constructor and the test/SSR harnesses build
  on. Per Spec 002 §Per-instance frames.

  rf2-ji3tvy — RENAMED from the bare `make-frame` so the internal record helper
  no longer COLLIDES by short name with the public `re-frame.live-frame/make-frame`
  (the frame-VALUE constructor): a reader who saw `frame/make-anon-frame-record!` could not tell
  from the call which one ran. The `-record!` suffix names exactly what it
  returns — an anonymous gensym-keyed RECORD's id, not a frame value."
  [config]
  (let [id (keyword "rf.frame" (str (gensym "")))]
    (reg-frame id config)
    id))

(defn make-frame-value
  "Build a live frame VALUE for frame id `runnable-id` (EP-0024, rf2-tu2vr7) —
  the lifecycle token `make-frame` returns. INTERNAL: the value carries the
  `:rf.frame/object` marker, its `:rf.frame/runnable-id` (= the id its record is
  keyed by), and the public `:rf.frame/id` + the creation inputs
  (`:rf.frame/initial-db` / `:rf.frame/capabilities` / `:rf.frame/adapter`) when
  present. The resolved generation is NOT embedded on the value — it lives on
  the record (`:generation`), read by id via `frame-generation`, so a value and
  its id resolve the same generation and a `reload-images!` swap is observed by
  every holder of either. Pure map assembly; `id` is the public frame id (nil
  for a no-id direct value), `runnable-id` the record address."
  [{:keys [id runnable-id initial-db capabilities adapter]}]
  (cond-> {object-marker         true
           runnable-id-key       runnable-id}
    (some? id)           (assoc :rf.frame/id id)
    (some? initial-db)   (assoc :rf.frame/initial-db initial-db)
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
;; take no frame arg). Per rf2-x3m8c.
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
;; only conj's when bound). Per rf2-ini4wr.
(def ^:dynamic *teardown-hook-failures* nil)

;; Pre-cascade frame-state snapshot of the in-flight dequeued event, bound by
;; the router around `process-event!` (see `re-frame.router/run-one-pass!`).
;; A handler that calls `destroy-frame!` on its own frame mid-drain runs
;; INSIDE that binding, so `destroy-frame!` can recover the whole frame-state
;; (both partitions) held BEFORE the in-flight event's cascade began — the
;; `:frame-state-before` slot the `:halted-destroy` epoch record carries per
;; Spec-Schemas §`:rf/epoch-record` §Outcomes (rf2-9neiq). EP-0001
;; (rf2-3aizt1, decision #2): the canonical snapshot unit is the whole
;; frame-state; the epoch derives `:db-before` from its app-db projection.
;; nil outside a drain (an out-of-cascade `destroy-frame!` — hot-reload,
;; `reset-frame!`, REPL — commits no `:halted-destroy` record, so the slot
;; is moot there).
(def ^:dynamic *cascade-frame-state-before* nil)

;; rf2-bh56rc: the in-flight dequeued event's causal `:rf/time-ms` (the
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

    1. ALWAYS-ON axis (EP-0008 R1, rf2-ini4wr) — conj the failure entry
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

    2. DIAGNOSTIC channel (EP-0008 R2, rf2-x3m8c) — emit the per-hook
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
  surface. Per EP-0008 (rf2-7b9r4l): the dedicated `:on-destroy`-throw
  category is the DISCRIMINABLE teardown signal — an operator on a
  `goog.DEBUG=false` host must be able to tell 'this throw happened during
  destroy' from a generic `:rf.error/handler-exception`. The router's
  `:rf.error/handler-exception` is the production source of record for the
  *handler throw*, but the discriminator (it was an `:on-destroy`) was
  previously LOST under elision (the dedicated category rode only the DCE'd
  `trace/emit-error!`). It now rides the always-on axis too.

  This is also the ONLY always-on coverage for the rf2-bxud9v defence-in-
  depth re-throw branch (`dispatch-sync!` itself faulting): that path never
  produced a router `:rf.error/handler-exception`, so before this promotion
  it had ZERO production observability.

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
  handler throw semantics (rf2-r1ciy decision b): a throw from the user's
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
  UNIQUE per-destroy key (rf2-ntv9i9.1 — a constant key let a nested /
  overlapping destroy clobber the outer's listener and drop its dedicated
  record): any `:rf.error/handler-exception` record whose `:frame` matches us
  is captured and re-emitted under the new category. The always-on axis is
  the one surface the router's handler-exception fan-out ALSO rides
  (`re-frame.router/emit-pipeline-exception!` → `error-emit/dispatch-on-
  error!`), so this capture survives `:advanced` + `goog.DEBUG=false`
  where the dev trace is DCE'd (rf2-87f7fb — the pre-EP-0008 capture
  observed the dev-only `trace.tooling` listener registry, which no-ops
  in production, so the dedicated discriminator did NOT survive prod for
  the common path despite the Spec 009 catalogue promising it does). We
  reach the registry through the `:error-emit/register-error-listener!` /
  `:error-emit/unregister-error-listener!` late-bind hooks because a
  static `re-frame.frame` → `re-frame.error-emit` require closes the
  `error-emit` → `elision` → `frame` load cycle (the same reason the
  emission below rides `:error-emit/dispatch-on-error`).

  We ALSO wrap the dispatch itself in try/catch as a defence-in-depth: if
  `dispatch-sync!` ever re-throws (e.g. a fault inside the dispatch
  infrastructure itself, not the user handler), we catch it here — and
  per EP-0008 (rf2-7b9r4l) the dedicated category now rides the always-on
  axis so this defence-in-depth branch (which never produced a router
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
            ;; rf2-ntv9i9.1: a UNIQUE per-destroy listener key — NOT a constant.
            ;; A nested / overlapping destroy (an `:on-destroy` that destroys a
            ;; different frame, Spec 002 rf2-r1ciy) would otherwise clobber the
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
              ;; branch never produced a router :rf.error/handler-exception,
              ;; so the always-on emission here is its ONLY production
              ;; observability (EP-0008, rf2-7b9r4l).
              (reset! infra-fault? true)
              (emit-on-destroy-handler-exception! id on-destroy ex nil)))
          (finally
            (when (and register remove-cb)
              (remove-cb listener-k))))
        ;; If the router converted a handler throw to an always-on
        ;; `:rf.error/handler-exception` record, re-emit under the
        ;; dedicated :on-destroy category so consumers can discriminate
        ;; teardown failures from regular handler throws. Rides the
        ;; always-on axis (EP-0008, rf2-7b9r4l) so the discriminable
        ;; teardown signal survives `goog.DEBUG=false` (rf2-87f7fb). The
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

  Per rf2-vsigt — Spec 005 §Cross-Spec Interactions §1: when the
  machines artefact is loaded, delegate the full cascade
  (reverse-creation walk, per-machine `:exit` cascade, HTTP abort,
  unified teardown projection, system-id release, handler unregister)
  to the late-bind hook `:machines/teardown-on-frame-destroy!`. The
  hook is published by `re-frame.machines` so core never statically
  requires the optional machines artefact.

  Fallback (no machines artefact on the classpath): preserve the
  legacy minimal behaviour — fire the `:http/abort-on-actor-destroy`
  hook per snapshot key and emit `:rf.machine.lifecycle/destroyed`
  with `:reason :parent-frame-destroyed`. Without the machines
  artefact there are no live `:exit` cascades to run, no actor
  handlers to unregister, and no system-id reverse index to release."
  [id]
  (if-let [teardown! (late-bind/get-fn :machines/teardown-on-frame-destroy!)]
    (teardown! id)
    ;; Fallback path — minimal contract when the machines artefact is absent.
    ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state.
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
                      ;; rf2-ws5thu — the reaped actor's live INSTANCE address;
                      ;; `:machine-id` is reserved for the registered TYPE. Must
                      ;; match the machines-artefact orchestrator emit
                      ;; (`lifecycle-fx/frame-destroy/emit-lifecycle-destroyed!`)
                      ;; so the registrar-substrate row carries one tag shape
                      ;; whether or not the machines artefact is loaded.
                      :actor-id   machine-id
                      :last-state (:state snapshot)
                      :reason     :parent-frame-destroyed})))))

(defn- mark-frame-destroyed!
  ;; EP-0013 step 4 (rf2-a15n62): the `frames` registry is keyed by frame
  ;; ADDRESS (`fkey`), so flip `:destroyed?` on the addressed record.
  [fkey]
  (swap! frames update fkey assoc-in [:lifecycle :destroyed?] true))

(defn- tear-down-sub-cache!
  "Dispose every cached subscription reaction for the destroyed frame.

  Per rf2-x3m8c: route through the sub-cache-owned
  `:subs.cache/dispose-all-for-frame-destroy!` hook so each eviction
  emits a `:rf.sub/dispose` trace (reason `:frame-destroy`) — frame
  teardown is a real eviction class and MUST appear in the sub-cache
  lifecycle stream like `unsubscribe` / hot-reload / `clear-sub-cache!`
  do (the bypass that disposed reactions directly was invisible to
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
  frame-state container (rf2-adwcv6). Each projection holds a watch on the
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
  ;; EP-0013 (D1 rf2-gkddyq / step 4 rf2-a15n62): realm membership is a VIEW
  ;; derived from this atom (filtered on each frame's `:realm` slot — see
  ;; `frames-by-realm`), so removing the frame record (keyed by frame ADDRESS
  ;; `fkey`) drops it from its realm's membership with no separate retraction
  ;; step and no desync.
  [fkey]
  (swap! frames dissoc fkey))

(defn- unregister-frame!
  ;; EP-0013 step 4 (rf2-a15n62): the `:frame` registrar slot lives in the
  ;; OWNING realm's registrar (a non-default-realm frame was registered into the
  ;; realm's own table via `seat-into-realm!`), so route the unregister through
  ;; that realm. `realm-id` nil / default ⇒ no binding ⇒ the process-global
  ;; registrar, byte-identical.
  [realm-id id]
  (if-let [reg (and realm-id
                    (not= realm-id realm/default-realm-id)
                    (some-> (realm/realm realm-id) realm/registrar))]
    (binding [registrar/*registrar* reg]
      (registrar/unregister! :frame id))
    (registrar/unregister! :frame id)))

(defn- notify-epoch-listeners!
  "Fire the epoch destroy hook, threading the two frame-state snapshots the
  `:halted-destroy` epoch record carries per Spec-Schemas §`:rf/epoch-record`
  §Outcomes (rf2-9neiq). EP-0001 (rf2-3aizt1, decision #2): the canonical
  snapshot unit is the whole frame-state (both partitions); the epoch surface
  derives the `:db-before` / `:db-after` app-db projections from them.

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
  step 6) does not have to read a container that is already gone — the
  root cause of the prior nil-`:db-before` / nil-`:db-after` records.

  `committed-at` (rf2-bh56rc) is the destroying event's causal `:rf/time-ms`
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
                                      hook (rf2-vsigt). That walks each
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
                                      :frame-destroy` (rf2-x3m8c).
    *. cleanup hooks (best-effort, no-op when artefact absent):
         :elision/clear-warning-cache!      — reset schema-first elision
                                              warning cache.
         :ssr/on-frame-destroyed            — clear SSR side-channel
                                              atoms for this frame.
         :machines/on-frame-destroyed!      — clear the machines
                                              artefact's frame-scoped
                                              `:after` timer table.
         :schemas/on-frame-destroyed!       — drop schemas registered
                                              against this frame
                                              (rf2-wkxng / rf2-6m0se).
         :flows/teardown-on-frame-destroy!  — drop flows + last-inputs
                                              rows + dead `:flow`
                                              registrar slots
                                              (rf2-wbtjn).
         :routing/on-frame-destroyed!       — release the frame's
                                              host-side transient routing
                                              caches — scroll positions
                                              (rf2-1hncp2) + nav-token /
                                              pending-nav counters
                                              (rf2-oosjmh).
         :resources/on-frame-destroyed!     — release the frame's
                                              host-side transient resource
                                              caches — work-ledger host
                                              handles + generation
                                              high-water mark (rf2-afpdkn).
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
                                      Spec-Schemas §:rf/epoch-record §Outcomes
                                      (rf2-9neiq / rf2-3aizt1) — not the prior
                                      nil/nil.

  Subsequent dispatch / subscribe against a destroyed frame raises
  :rf.error/frame-destroyed.

  Re-entrancy (rf2-r1ciy): if `destroy-frame!` is called for `id` while
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

  EP-0024 (rf2-tu2vr7): the target may be a frame-id KEYWORD or a frame VALUE
  (`rf/make-frame`'s return token). A value is normalized to its id via
  `frame-target->id` so the whole recipe keys the ONE registry's record
  unchanged; `dissoc-frame!` IS the forget (the resolved generation rode the
  record, dropped with it — no second registry, no `:live-frame/forget!` hook)."
  [target]
  ;; EP-0024 (rf2-tu2vr7): accept a frame VALUE or a frame-id keyword. Normalize
  ;; a value to its id so every keyed teardown step below targets the record; a
  ;; keyword passes through unchanged.
  (let [id (frame-target->id target)]
  ;; Re-entrancy guard: short-circuit if we're already destroying this id.
  ;; Silent no-op (idempotent destroy is already a no-op pattern; no new
  ;; trace event needed per rf2-r1ciy decision).
  ;; EP-0013 step 4 (rf2-a15n62): resolve the frame in whatever realm is in
  ;; scope (`*current-realm*`), then derive its OWNING realm + frame ADDRESS
  ;; from the resolved record so every keyed teardown step (the in-flight guard,
  ;; `mark-frame-destroyed!`, `dissoc-frame!`, the registrar unregister) targets
  ;; the addressed record — the same id in two realms tears down independently.
  ;; The whole teardown runs under `*current-realm*` bound to the frame's realm
  ;; so the many bare-`id` lookups inside (`frame-state-value`, `frame-realm`,
  ;; the late-bound subsystem hooks that re-resolve the frame) resolve to THIS
  ;; frame. Default-realm frame ⇒ bare-id key + no binding (byte-identical).
  (when-let [f (frame id)]
   (let [frame-rid (or (:realm f) realm/default-realm-id)
         fkey      (frame-key frame-rid id)]
    (call-with-realm frame-rid
     (fn []
      (when-not (contains? @destroying-frames fkey)
      (swap! destroying-frames conj fkey)
      ;; Capture the DESTROY-TIME frame-state value BEFORE any teardown step
      ;; runs. After `mark-frame-destroyed!` (step 3) flips :destroyed?,
      ;; `frame-state-value` returns nil; after `dissoc-frame!` (step 6)
      ;; the container is gone entirely. Reading it here yields the state
      ;; the partial cascade left the frame in at the moment destroy was
      ;; requested — the `:frame-state-after` slot the `:halted-destroy`
      ;; epoch record carries (rf2-9neiq). The pre-cascade
      ;; `:frame-state-before` rides the router-bound
      ;; `*cascade-frame-state-before*` dynamic var (nil outside a drain).
      ;; Both are passed to `notify-epoch-listeners!` (step 8). EP-0001
      ;; (rf2-3aizt1, decision #2): the whole frame-state, both partitions.
      (let [cascade-fs-before *cascade-frame-state-before*
            ;; rf2-bh56rc: the destroying event's causal `:time-ms`, bound by
            ;; the router alongside `*cascade-frame-state-before*`. Threaded to
            ;; the epoch hook so the `:halted-destroy` record's `:committed-at`
            ;; is replayable (per EP-0010 §Time). nil outside a drain.
            cascade-time-ms   *cascade-time-ms*
            fs-at-destroy     (frame-state-value id)
            ;; EP-0008 R1 (rf2-ini4wr): per-destroy accumulator for
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
        (mark-frame-destroyed! fkey)
        (tear-down-sub-cache! id f)
        ;; Dispose the app-db / runtime-db projection reactions (rf2-adwcv6)
        ;; AFTER the sub-cache (the sub-cache's layer-1 reactions watch the
        ;; app-db projection; disposing the projection first would orphan
        ;; their source watch). The projections watch the physical
        ;; frame-state container; disposing here releases those watches.
        (tear-down-partition-projections! f)
        (safe-call-hook! :elision/clear-warning-cache!)
        (safe-call-hook! :ssr/on-frame-destroyed id)
        (safe-call-hook! :machines/on-frame-destroyed! id)
        ;; Per rf2-wkxng / rf2-6m0se: drop every schema registered against
        ;; the destroyed frame so a re-registered frame starts with a
        ;; clean schema slate. Without this hook, orphan app-db schemas
        ;; from a prior `reg-frame` cycle persist and re-fire under the
        ;; rollback contract — manifesting as spurious rollbacks against
        ;; paths the new frame's :on-create never wrote. No-op when
        ;; re-frame.schemas is absent (the artefact is optional per
        ;; rf2-p7va).
        (safe-call-hook! :schemas/on-frame-destroyed! id)
        ;; Per rf2-wbtjn: drop every flow registered against the destroyed
        ;; frame plus its cached `last-inputs` rows, and prune the
        ;; `:flow` registrar slot when the destroyed frame was the last
        ;; owner. Symmetric with the machines teardown hook above
        ;; (rf2-vsigt). Without this hook a long-running SSR JVM with
        ;; per-request frame churn grows the flow registry unboundedly.
        ;; This hook does NOT scrub the frame's flow-output elision marks
        ;; (rf2-yt5bbl): those live in the runtime-db partition INSIDE the
        ;; `:frame-state` container, which `dissoc-frame!` (step 6 below)
        ;; drops wholesale with the frame record — a per-flow scrub here
        ;; would be redundant work over about-to-be-GC'd state, and a reused
        ;; frame-id gets a fresh empty container so no stale flow-sourced
        ;; declaration survives the cycle (see the flows
        ;; `teardown-on-frame-destroy!` docstring).
        ;; No-op when re-frame.flows is absent (the artefact is optional
        ;; per rf2-tfw3).
        (safe-call-hook! :flows/teardown-on-frame-destroy! id)
        ;; rf2-1hncp2 + rf2-oosjmh: release the destroyed frame's host-side
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
        ;; rf2-afpdkn: release the destroyed frame's host-side transient
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
        ;; is absent (the artefact is optional, post-v1).
        (safe-call-hook! :resources/on-frame-destroyed! id)
        ;; rf2-f8ztaj: the realm-owned host-transient inventory hatch was
        ;; REMOVED (no production subsystem ever registered a descriptor; the
        ;; shipped subsystems tear down via the named ordered hooks above). The
        ;; per-frame inventory walk that used to run here is gone with it.
        (emit-frame-destroyed-trace! id)
        ;; Per Spec 009 §Per-frame trace rings (rf2-g1b2m / rf2-8uwce):
        ;; release the destroyed frame's cascade-keyed ring so no
        ;; residual trace events leak across the frame lifecycle. Fired
        ;; AFTER `:rf.frame/destroyed` emits so the destroyed trace
        ;; itself (which is frameless and bypasses the ring anyway)
        ;; still flows through the live stream cleanly. Routed via
        ;; late-bind so production CLJS bundles (no trace.tooling) no-op.
        (safe-call-hook! :trace.tooling/release-frame-ring! id)
        ;; EP-0024 (rf2-tu2vr7): the live-frame collapse removed the second
        ;; PUBLIC live-frame registry — there is ONE `frames` registry, and
        ;; `dissoc-frame!` below IS the forget. The frame's resolved generation
        ;; rode the record's `:generation` slot, so dropping the record drops it
        ;; too; the former `:live-frame/forget!` teardown hook (whose only job
        ;; was keeping the second registry coherent) is dissolved.
        (dissoc-frame! fkey)
        (unregister-frame! frame-rid id)
        (notify-epoch-listeners! id cascade-fs-before fs-at-destroy cascade-time-ms)
        nil
        (finally
          ;; EP-0008 R1 (rf2-ini4wr) — FINALLY-shaped flush of the always-on
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
          (swap! destroying-frames disj fkey))))))))))))

(defn reset-frame!
  "destroy-frame! followed by reg-frame with the same config. Per Spec 002
  §reset-frame! — full replace, opt-in.

  EP-0013 step 4 (rf2-a15n62): runs the destroy+re-register under
  `*current-realm*` bound to the frame's OWNING realm so the re-`reg-frame`
  re-creates the frame in the SAME realm it was destroyed from (the bare
  `reg-frame` reads `*current-realm*` to pick its realm). Default-realm frame ⇒
  no binding (byte-identical)."
  [id]
  (when-let [f (frame id)]
    (let [config (:config f)
          rid    (or (:realm f) realm/default-realm-id)]
      (call-with-realm rid
        (fn []
          (destroy-frame! id)
          (reg-frame id config))))))

;; ---- :rf/default — TEST-ONLY fixture helper -------------------------------
;;
;; Per Spec 002 §`:rf/default` is an ordinary id (EP-0002): `:rf/default`
;; is NOT created by `init!`, is NOT the React-context default, is NOT a
;; lookup tier, and is NOT inferred from a missing stamp. The runtime never
;; synthesises it. `init!` no longer calls this.
;;
;; This helper survives ONLY as a convenience for TEST FIXTURES that pin
;; `*current-frame*` to `:rf/default` and dispatch ambiently — the standard
;; `re-frame.test-support/make-reset-runtime-fixture` and the per-suite
;; reset-runtime fixtures across the adapter / SSR test trees call it to
;; establish a known default scope. It is a TEST PATH, not a runtime path:
;; no production / SSR code reaches it, and the chain's later call-site
;; beads (EP-0002 §3+) migrate real ambient call sites to carry an explicit
;; frame. Kept (rather than deleted) because removing it would break those
;; out-of-scope test fixtures wholesale; the name + this banner make the
;; test-only intent unambiguous.

(defn ensure-default-frame!
  "TEST-ONLY fixture helper. Register the ordinary `:rf/default` frame if
  absent (idempotent), so a test that pins `*current-frame*` to
  `:rf/default` and dispatches ambiently has a frame to land on.

  NOT a runtime path — `init!` does NOT call this (per Spec 002
  §`:rf/default` is an ordinary id, EP-0002: the runtime never synthesises
  a default frame). Application / SSR boot code that wants a default-named
  app frame registers it explicitly via `(rf/reg-frame :rf/default {…})`."
  []
  (when-not (get @frames :rf/default)
    (reg-frame :rf/default {:doc "Test-fixture default frame (ordinary id; not a runtime floor)."})))
