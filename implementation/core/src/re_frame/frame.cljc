(ns re-frame.frame
  "Frame container, lifecycle, and the frame registry. Per Spec 002.

  A frame is an isolated runtime boundary identified by a keyword. Every
  frame holds its own app-db (a substrate-managed reactive container),
  its own per-frame router queue, and its own sub-cache.

  A public operation targets a frame by its frame-id KEYWORD or by the live
  frame VALUE `make-frame` returns — either one passed DIRECTLY, with no
  accessor to unwrap. This namespace privately normalizes a value ONE WAY to
  its id (`frame-value->id`) and holds the frame records keyed by that id;
  the value's representation is not an app-facing data contract.

  Teardown is the sole exception to that equivalence: one-argument
  `destroy-frame!` reads a construction-returned value as EXACT-INCARNATION
  authority (a stale value no-ops against a same-id successor), while a
  frame-id keyword — or a token-less derived value — is ADDRESS-directed.

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
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
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
;;   :config      the record-config the constructor was given
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
;; The PUBLIC target a dispatch / subscribe / destroy / app-db read / provider
;; addresses is a frame — a frame id KEYWORD OR a frame VALUE the lifecycle
;; APIs return, ACCEPTED DIRECTLY EVERYWHERE (API-shrink #1, rf2-csbbwu — the
;; API commits to ONE frame-target grammar; EP-0024 Operation target grammar).
;; For the ROUTING operations the two spellings are INTERCHANGEABLE: the value
;; is normalized to its id and the operation downstream is identical.
;; `destroy-frame!` is the SOLE exception — it accepts either, but reads a
;; construction-returned value's EXACT-INCARNATION authority (see
;; `incarnation-token-key`), while a keyword or a token-less derived value
;; stays ADDRESS-directed.
;;
;; Every runnable subsystem resolves per-frame state through a
;; frame-id ADDRESS keyed into `frames` (the ONE registry — the universal
;; chokepoint: the router queue/drain, `commit-frame-transition!`, the
;; sub-cache, cofx, elision, …). So a frame VALUE target is normalized to its
;; id at the public entry, and every bare-`frame-id`-keyed operation
;; downstream is identical.
;;
;; The frame VALUE is the live lifecycle token `make-frame` returns. Its
;; representation is NOT an app-facing data contract: it carries the
;; `:rf.frame/object` marker (so a value target is discriminated structurally
;; from a keyword id) and `:rf.frame/runnable-id` (= the frame id its record is
;; keyed by in the one `frames` registry). The resolved image generation is NOT
;; embedded on the value — it lives on the record (the `:generation` slot),
;; read by id. `frame-value->id` normalizes a frame value to its id
;; (EP-0024 Open Issue #2 — representation hidden); it is an INTERNAL
;; primitive — every public surface accepts the value directly, so there is
;; no facade accessor to reach for.

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

(def ^:const incarnation-token-key
  "Reserved key on a frame VALUE carrying the EXACT incarnation-identity token
  (the winning construction's `:drain-lock`, see `frame-incarnation-token`) the
  `make-frame` call that produced the value installed (rf2-moftbs). Present ONLY
  on a value handed back by a fresh/idempotent construction — the opaque
  lifecycle-token AUTHORITY an owner (`with-new-frame`, Story replay, SSR
  per-request) consumes at cleanup so its teardown destroys EXACTLY the
  incarnation it created, never a same-id successor reseated in between. Absent
  on a derived-read value (`live-frame` / `image-view-frames`) and on a frame-id
  keyword — both of which stay ADDRESS-directed (destroy whatever incarnation is
  currently live under the id). Internal — the value's representation is not an
  app-facing data contract; this token is opaque authority, compared only by
  identity."
  :rf.frame/incarnation-token)

(defn frame-value?
  "True when `x` is a live frame VALUE (`make-frame`'s return token — carries the
  `:rf.frame/object` marker), as opposed to a frame-id keyword. The structural
  discriminator a target-resolution site uses (EP-0024 Term: Frame value). Pure."
  [x]
  (boolean (and (map? x) (get x object-marker))))

(defn frame-value->id
  "INTERNAL normalization primitive (API-shrink #1, rf2-csbbwu removed the
  public `rf/frame-value->id` facade accessor — every public surface accepts
  a frame value directly, so there is no app-facing need to unwrap one).
  Returns the frame id a frame value routes to (its `:rf.frame/id` when
  created with one, else its private `:rf.frame/<gensym>` runnable id).
  Passing a frame-id keyword returns it unchanged, so callers can always
  pass a value or an id. Pure."
  [frame-value]
  (if (frame-value? frame-value)
    (get frame-value runnable-id-key)
    frame-value))

(defn frame-value-incarnation-token
  "Return the EXACT incarnation-identity token a fresh-construction frame VALUE
  carries (its `:rf.frame/incarnation-token` — the installed `:drain-lock`), or
  nil when `x` is a frame-id keyword, a derived-read frame value, or any value
  built without a construction token (rf2-moftbs). A non-nil result is the
  opaque teardown AUTHORITY the one-argument `destroy-frame!` consumes so an
  owner's cleanup destroys EXACTLY the incarnation it created; nil selects the
  ADDRESS-directed path (destroy whatever incarnation is currently live under
  the id). Pure."
  [x]
  (when (frame-value? x)
    (get x incarnation-token-key)))

(defn frame-target->id
  "Normalize a public frame TARGET — a frame-id KEYWORD or a frame VALUE — to the
  frame id its record is keyed by in the one `frames` registry (EP-0024). A
  frame VALUE (carrying `:rf.frame/object true`) yields its
  `:rf.frame/runnable-id`; any other target (a keyword id, or a nil / malformed
  value) is returned UNCHANGED — so every keyword-target caller is
  byte-identical. The internal normalization seam dispatch / subscribe / destroy
  / app-db-read / frame-provider funnel a frame value through before keying
  `frames`; the value is accepted EVERYWHERE a keyword id is (API-shrink #1,
  rf2-csbbwu). Pure — the
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

;; ---- per-id frame construction transaction -------------------------------
;;
;; One process-owned CAS registry is the admission point for construction and
;; the pre-dissoc portion of destruction. Values are opaque owner maps compared
;; ONLY by identity; their data fields are diagnostics, never authority. A claim
;; always names a SET of ids and installs all of them in one CAS, which is the
;; unchanged primitive the later re-frame.ui multi-plan preflight consumes.
;;
;; There is deliberately no queue or generation counter. A conflicting claim
;; fails promptly on both hosts, avoiding callback-waits-for-contender deadlocks
;; and keeping CLJ/CLJS semantics identical. Incarnation/config generations
;; remain owned by :drain-lock / :trace-policy-token on the frame record.

(defonce ^:private frame-construction-reservations
  (atom {}))

(def ^:dynamic ^:private *frame-transaction-owner* nil)

(def ^:dynamic ^:private *frame-construction-handoff* nil)

(defn- current-host-holder []
  #?(:clj (Thread/currentThread) :cljs true))

(defn- owner-current-on-host? [owner]
  (and (some? owner)
       (identical? (:holder owner) (current-host-holder))))

(defn- owner-holds-frame-id? [owner id]
  (and (owner-current-on-host? owner)
       (identical? owner (get @frame-construction-reservations id))))

(defn- throw-frame-construction-in-progress!
  ([id held-owner]
   (throw-frame-construction-in-progress!
     id :reservation-held (:kind held-owner)))
  ([id reason owner-kind]
   (error/throw-error!
     :rf.error/frame-construction-in-progress
     'rf/make-frame
     (str "frame construction for " (pr-str id) " cannot start because the id "
          (if (= :lifecycle-dead reason)
            "still names a lifecycle-dead row awaiting exact removal"
            "is already owned by an in-flight frame transaction")
          ". Same-id overlap fails promptly rather than waiting inside arbitrary "
          "adapter, setup, or teardown callbacks. Retry after the owning "
          "transaction settles.")
     {:recovery :retry-after-frame-transaction
      :extra    {:frame      id
                 :reason     reason
                 :owner-kind owner-kind}})))

(defn- try-claim-frame-transaction!
  "Try the same all-or-nothing set CAS as `claim-frame-construction!`.

  Returns an owner token, or nil on contention. Destruction uses this
  non-throwing form only after it has revalidated an exact live incarnation:
  duplicate/concurrent destroy remains its established prompt nil no-op, while
  public construction uses the typed throwing form below."
  [frame-ids kind]
  (let [owner {:kind      kind
               :frame-ids frame-ids
               :holder    (current-host-holder)}]
    (loop []
      (let [registry @frame-construction-reservations]
        (if (some #(contains? registry %) frame-ids)
          nil
          (let [claimed (reduce #(assoc %1 %2 owner) registry frame-ids)]
            (if (compare-and-set! frame-construction-reservations
                                  registry claimed)
              owner
              (recur))))))))

(defn ^:no-doc claim-frame-construction!
  "Atomically reserve every id in non-empty SET `frame-ids` for one exact owner.

  Returns the opaque identity-compared owner token. If any id is already owned,
  throws `:rf.error/frame-construction-in-progress` and claims NONE. The set
  shape and all-or-nothing CAS are the narrow core seam reserved for the later
  re-frame.ui multi-plan preflight; ordinary core construction claims a
  singleton. INTERNAL — not part of the public frame API."
  ([frame-ids]
   (claim-frame-construction! frame-ids :preflight))
  ([frame-ids kind]
   (when-not (and (set? frame-ids) (seq frame-ids))
     (throw (ex-info "frame construction claim requires a non-empty set of ids"
                     {:frame-ids frame-ids})))
   (let [owner {:kind      kind
                :frame-ids frame-ids
                :holder    (current-host-holder)}]
     (loop []
       (let [registry @frame-construction-reservations
             conflict (some (fn [id]
                              (when-let [held (get registry id)]
                                [id held]))
                            (sort-by pr-str frame-ids))]
         (if-let [[id held] conflict]
           (throw-frame-construction-in-progress! id held)
           (let [claimed (reduce #(assoc %1 %2 owner) registry frame-ids)]
             (if (compare-and-set! frame-construction-reservations
                                   registry claimed)
               owner
               (recur)))))))))

(defn ^:no-doc release-frame-construction!
  "Compare-release every reservation still owned by exact `owner`.

  A stale release cannot erase a successor's claim. INTERNAL companion to
  `claim-frame-construction!`."
  [owner]
  (swap! frame-construction-reservations
         (fn [registry]
           (reduce-kv (fn [m id held]
                        (if (identical? owner held) (dissoc m id) m))
                      registry
                      registry)))
  nil)

(defn ^:no-doc call-with-frame-construction-handoff!
  "Invoke zero-arg `f` with one exact permission for `owner`'s reserved `id` to
  enter the construction engine without self-colliding.

  The permission is consumed at engine entry, before any adapter callback. A
  nested public `make-frame` therefore sees no permission and loses normally.
  This is the narrow future re-frame.ui preflight → core hand-off seam; it is
  intentionally not blanket same-owner re-entrancy. INTERNAL."
  [owner id f]
  (when-not (owner-holds-frame-id? owner id)
    (throw (ex-info "frame construction handoff owner does not hold id"
                    {:frame id})))
  (binding [*frame-construction-handoff*
            {:owner owner :id id :available? (atom true)}]
    (f)))

(defn- consume-frame-construction-handoff! [id]
  (let [{:keys [owner available?] handoff-id :id} *frame-construction-handoff*]
    (when (and (= id handoff-id)
               (owner-holds-frame-id? owner id)
               (compare-and-set! available? true false))
      owner)))

(defn- unspent-frame-construction-handoff?
  "True when THIS caller already stands inside an exact reservation for `id`
  handed to it by an outer preflight, with the permission still UNSPENT.
  Deliberately NON-consuming — `consume-frame-construction-handoff!` at engine
  entry is the one place that spends it, and this predicate must not race that
  one-shot."
  [id]
  (let [{:keys [owner available?] handoff-id :id} *frame-construction-handoff*]
    (boolean (and (= id handoff-id)
                  (some? available?)
                  (true? @available?)
                  (owner-holds-frame-id? owner id)))))

(defn ^:no-doc call-with-frame-construction-claim!
  "Invoke zero-arg `f` holding ONE exact per-id construction reservation for
  `id`, with the engine hand-off armed so the `upsert-frame!` inside `f` enters
  that SAME reservation instead of colliding with it.

  This is how a caller puts work of its OWN inside the frame transaction rather
  than beside it. `make-frame`'s generation-provenance publication and its
  rollback are that work (rf2-rt4jz): they write a second process-global store
  that a reprojection reads, so they have to be admitted, ordered and rolled
  back under the same authority as the frame revision they describe.

  Contention is the engine's own typed `:rf.error/frame-construction-in-progress`,
  raised HERE — before `f` runs — so a LOSING attempt performs none of `f`'s
  writes at all. That is the property the alternative shape cannot have: a
  caller that writes first and enters the engine second has already mutated
  process-global state by the time the engine rejects it.

  An outer preflight that already reserved `id` and handed it off (re-frame.ui's
  multi-plan `execute-frame-plans!`) is ADOPTED as-is: nothing is claimed and
  nothing is released, so its window and its release point are unchanged, and
  `f`'s writes fall inside the reservation it is already holding. A hand-off
  that has already been SPENT is not adoption — the nested public entry claims
  and loses normally, exactly as it did when the engine claimed for itself.
  INTERNAL."
  [id kind f]
  (if (unspent-frame-construction-handoff? id)
    (f)
    (let [owner (claim-frame-construction! #{id} kind)]
      (try
        (call-with-frame-construction-handoff! owner id f)
        (finally
          (release-frame-construction! owner))))))

(defn- call-with-frame-transaction!
  [id kind handoff? join-current-owner? f]
  (if-let [owner (or (when handoff?
                       (consume-frame-construction-handoff! id))
                     (when (and join-current-owner?
                                (owner-holds-frame-id?
                                  *frame-transaction-owner* id))
                       *frame-transaction-owner*))]
    (binding [*frame-transaction-owner* owner]
      (f owner))
    (let [owner (claim-frame-construction! #{id} kind)]
      (binding [*frame-transaction-owner* owner]
        (try
          (f owner)
          (finally
            (release-frame-construction! owner)))))))

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
;;
;; Keyed by the bare frame-id. Each value carries the DESTROYING incarnation's
;; token (its `:drain-lock`, captured while still live):
;; `{id {:token drain-lock}}`. The bare id is what `frame-closing?` consults;
;; the token is what the duplicate guard and
;; `frame-incarnation-closing?` consults so a stale close marker of an
;; already-torn-down incarnation A cannot be mistaken for a fresh same-id
;; REPLACEMENT incarnation B being in-flight. A new B claim REPLACES A's stale
;; marker; each destroy's terminal cleanup compare-removes only its own token.

(defonce ^:private destroying-frames
  (atom {}))

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
;; its frame from the causal token it holds — the ambient scope established
;; by `with-frame` (dynamic var) or by the closest enclosing frame boundary,
;; a `frame-provider` (SCOPE) or a `frame-root` (ENSURE), or a frame stamp it
;; captured. It never *synthesises* one from absence: there is no process-
;; global `:rf/default` floor that catches operations issued under no scope
;; at all.
;;
;; The rationale leads with **replay determinism + temporal non-locality**,
;; NOT purity (per EP-0002 §Resolved Decisions R1-R7):
;;
;;   - A silently-defaulted frame poisons replay — `restore-epoch!`,
;;     time-travel, and Story / Xray determinism all become unsound the
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

;; ---- The REFUSAL tier — "no ambient frame is legal here" (rf2-2rtt6.122) --
;;
;; The two scope tiers above answer WHICH frame is current. Neither can say
;; NO FRAME IS LEGAL HERE, and the difference is not academic: tier 1 is
;; consumed by a bare `or` in every adapter's reader, so any non-nil value a
;; substrate binds there comes back AS the frame; and tier 2 is the shared
;; React context, which a substrate mounting a `frame-provider` populates on
;; purpose — a substrate cannot withdraw it without withdrawing the boundary
;; its own shell reads.
;;
;; A substrate whose render extent has a read/write discipline of its own
;; needs exactly that missing sentence. Hicasso is the motivating case
;; (EP-0038 HD-002 clause (a)): inside a boundary body, every read must go
;; through the boundary's collector so it becomes an edge the boundary
;; re-renders on. An ambient `rf/subscribe` written in a body RESOLVES —
;; the Hicasso frame is genuinely in scope through tier 2 — and then
;; mutates the sub-cache during render, contributes ZERO collector edges,
;; and leaves a boundary that never re-renders when that subscription
;; moves. It is a silent correctness failure whose only fence today is
;; which adapter the host happened to install.
;;
;; So: a THIRD tier, at the ONE funnel every adapter already routes through
;; (`resolve-current-frame`). It is a tier and not a reshape — the readers
;; are untouched, the hook is untouched, no adapter publishes anything new,
;; and the ordinary path adds one nil-test on a var read.
;;
;; WHAT THE REFUSAL REFUSES, precisely: the AMBIENT (React-context) tier
;; only. An explicitly CARRIED stamp still carries — `{:frame …}` opts never
;; reach this resolver at all, and `with-frame` / `bind-fn` / the router's
;; per-handler binding still answer through `*current-frame*`. That keeps
;; the two spellings of "I carried a frame" behaving identically inside a
;; refused extent, per EP-0002 ("frame identity is carried, not found") —
;; the refusal deletes the FINDING, never the carrying.
;;
;; WITH ONE EXCEPTION, WHICH IS OPT-IN AND SUBSTRATE-DECLARED (rf2-nqj22).
;; The sentence above is the rule wherever a carried stamp is the only frame
;; in play. It stops being the rule when the extent HAS a frame of its own
;; and the stamp names a different one: the body then reads and dispatches
;; against `:b` while its own reads, lowered intents, presence tray and
;; children target `:a`, chosen by which spelling the author reached for and
;; with no signal at all. Frames are ISOLATED contexts, so that is not a
;; carried-stamp win but an ambiguity, and blessing it would leave exactly
;; the silently-wrong-frame class this tier exists to delete alive in one
;; configuration. A substrate declares its extent's frame as `:extent-frame`
;; on the refusal detail and `require-current-frame!` refuses the mismatch;
;; a substrate that names none is untouched, and a MATCHED stamp still
;; carries — refusing that would make `with-frame` and `{:frame …}` disagree,
;; which is worse than the bug.

(def ^:private default-ambient-refusal-reason
  "The sentence a refusing substrate is expected to replace with its own —
  what the author should write INSTEAD, in its vocabulary."
  "Use the read and dispatch surfaces the enclosing render extent provides.")

(def ^:private default-ambient-refusal
  "The fail-closed refusal detail used when a substrate establishes the
  extent without describing it. A fence that silently disarms because its
  argument was nil is the trap class this tier exists to delete."
  {:reason default-ambient-refusal-reason})

(def ^:dynamic *ambient-frame-refusal*
  "The REFUSAL tier of the ambient chain: **nil** — the ordinary state, in
  every extent of every existing adapter — or a map describing why ambient
  frame resolution is illegal for this dynamic extent.

  Set only by [[call-with-ambient-frame-refused]]. Read only by
  [[resolve-current-frame]] (which collapses to the carried tier while it is
  non-nil) and by [[require-current-frame!]] (which turns the resulting
  absence into `:rf.error/ambient-frame-refused` rather than the generic
  `:rf.error/no-frame-context` — a refused ambient read and a genuinely
  frameless one are different mistakes and must not share a diagnostic).

  The map's keys are the substrate's to supply: `:substrate` (a keyword
  naming it), `:reason` (one sentence saying what the extent requires
  INSTEAD — this is what the author reads), `:recovery` (a keyword),
  `:extent-frame` (below), and any additional detail, which is merged into
  the payload.

  `:extent-frame` — THE FRAME THIS EXTENT IS RENDERING (rf2-nqj22). The one
  key core READS rather than passes through, and the only one that changes
  what the tier does. A substrate that names it declares \"a body of mine has
  ONE frame\", and [[require-current-frame!]] then refuses a carried stamp
  that names a DIFFERENT one — because a body whose ambient ops target `:b`
  while its own reads, lowered intents and children target `:a` is two frames
  in one body, and frames are ISOLATED contexts. Omit it (or leave it nil)
  and the tier behaves exactly as it did before: the ambient FIND is refused
  and any carried stamp wins, which is right for an extent that has no frame
  of its own to be mismatched against."
  nil)

(defn call-with-ambient-frame-refused
  "**SUBSTRATE seam.** Run `thunk` with ambient frame resolution REFUSED for
  its dynamic extent, so a frame-scoped operation that would have FOUND a
  frame through the React-context tier raises `:rf.error/ambient-frame-refused`
  instead of silently succeeding. Returns the thunk's value.

  This is the sentence a substrate could not previously say. A substrate
  whose render extent imposes its own read discipline — a compiled-view
  boundary that must observe every read to build its dependency edges, a
  collector that must see every subscription — establishes the extent
  around the code it owns, and an author who reaches past that discipline
  meets a loud error naming it instead of a boundary that quietly stops
  re-rendering.

  `refusal` is the detail map ([[*ambient-frame-refusal*]]); a nil `refusal`
  still refuses, with the generic detail, because a fence must fail closed.

  SCOPE. Refusal applies to the AMBIENT tier only. An explicitly carried
  stamp is untouched: `{:frame …}` opts never consult this resolver, and
  `with-frame` inside the extent still answers through `*current-frame*` —
  with the ONE exception a substrate opts into by naming `:extent-frame` on
  the refusal, which refuses a carried stamp that names a frame OTHER than
  the one the extent is rendering (rf2-nqj22).
  Nesting is not tracked and does not need to be — React renders a child
  fiber only after the parent's render function has returned, so this
  binding has already unwound before any child component (an adapter
  island, a host-interop gate, a deferred render callback) runs. The extent
  is exactly the substrate's own synchronous render call.

  Costs one push/pop per extent — per BODY, not per element or per read."
  [refusal thunk]
  (binding [*ambient-frame-refusal* (or refusal default-ambient-refusal)]
    (thunk)))

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
;; Normalized to the frame ID (rf2-h1vqa4): `with-frame` / `with-new-frame`
;; may bind a frame VALUE (`make-frame`'s token) into `*current-frame*`, and
;; ring attribution must key on the record id, never a value map.
(late-bind/set-fn! :frame/current-frame-id (fn [] (frame-value->id *current-frame*)))

(defn- carried-frame-admitted-by
  "The carried (tier-1) stamp, normalized to an id, when `refusal` — the
  in-effect refusal detail — ADMITS it; **nil** otherwise (rf2-nqj22).

  A refusal that names no `:extent-frame` admits every carried stamp, which
  is the pre-rf2-nqj22 contract and the right answer for an extent with no
  frame of its own. One that names one admits only that frame: a body whose
  ambient ops target `:b` while its own reads, lowered intents and children
  target `:a` is two frames in one body, and frames are ISOLATED contexts.

  Both sides go through `frame-value->id`, so a substrate that declared a
  frame VALUE compares equal to the id the reader normalized to. The
  comparison is BY VALUE and must stay that way — keywords are interned on
  the JVM and are not guaranteed reference-equal in ClojureScript, so an
  `identical?` written here would be green on one host and refuse every
  carried stamp on the other."
  [refusal]
  (let [carried (frame-value->id (current-frame))]
    (when (and (some? carried)
               (if-some [extent-frame (:extent-frame refusal)]
                 (= carried (frame-value->id extent-frame))
                 true))
      carried)))

(defn resolve-current-frame
  "Resolve the active frame at a no-explicit-frame call site — the
  dynamic-or-adapter/React-context scope frame, or **nil** when no scope
  is established. A **reader**: it never repairs absence by synthesising
  `:rf/default` (per Spec 002 §Frame target resolution — the carried
  invariant, EP-0002). The two scope tiers it observes:

    1. `*current-frame*` (dynamic var) — set by `with-frame` / `bind-fn`
       (INTERNAL) / the router's per-handler binding.
    2. The closest enclosing frame boundary — a `frame-provider` (SCOPE)
       or `frame-root` (ENSURE) — via React context (CLJS). Both install
       the shared boundary context; the no-provider sentinel appears only
       beneath neither.

  On CLJS this consults the `:adapter/current-frame` late-bind hook so
  the React-context tier is LIVE — adapters publish their React-context-
  aware impl through the hook at ns-load time. That impl returns nil when
  neither the dynamic var nor an enclosing frame boundary (`frame-provider`
  or `frame-root`) names a frame (the React-context default is the
  no-provider sentinel, per Spec 002 §`:rf/default`
  is an ordinary id). When the hook is unbound (no adapter loaded yet, or JVM build)
  the result is `current-frame` — the dynamic-var tier alone; the React-
  context tier silently no-ops to nil.

  This is the canonical scope reader — `subs/subscribe`,
  `router/dispatch!`'s frame computation, and `core/current-frame-id`
  delegate here so the React-context tier is single-sourced.
  Public frame-scoped operations that must have a frame call
  `require-current-frame!`, which is built on this reader.

  A THIRD TIER GATES THE SECOND (rf2-2rtt6.122). While a substrate has
  established a refusing extent ([[call-with-ambient-frame-refused]]), tier
  2 is WITHDRAWN and this reader answers from tier 1 alone — so a frame
  that is genuinely in scope is not *found* here, and
  `require-current-frame!` turns that into the loud
  `:rf.error/ambient-frame-refused`. Being a reader, it still returns nil
  rather than throwing: tooling and frame pickers running inside such an
  extent read 'no ambient frame', which is the truth.

  AND TIER 1 ANSWERS ONLY FOR THE EXTENT'S OWN FRAME (rf2-nqj22). When the
  refusal names an `:extent-frame`, a carried stamp naming some OTHER frame
  is not an ambient answer this extent will accept, and this reader says so
  by answering nil — see [[carried-frame-admitted-by]]. THE CHECK BELONGS
  HERE AND NOT ONLY IN `require-current-frame!`, which is not the single
  funnel the catalogue describes it as: `subs/subscribe`'s 1-arity — the
  framework's per-read path, and the very op HD-002 clause (a) is about —
  inlines `(or (resolve-current-frame) (require-current-frame! …))` to keep
  its error payload off the fast path (rf2-a8bw0), so a check living only in
  the requiring primitive would have missed every ambient subscribe. Putting
  it in the reader makes every reader-first caller correct by construction
  and leaves that optimisation intact."
  []
  ;; Sticky hook — `:adapter/current-frame` is published
  ;; once per loaded React-shaped adapter at ns-load time and routed
  ;; via `current-adapter`; it fires on every ambient resolution
  ;; (every ambient dispatch and every ambient subscribe).
  ;;
  ;; NORMALIZED to the frame ID (rf2-h1vqa4): `with-frame` / `with-new-frame`
  ;; may bind a frame VALUE (`make-frame`'s return token) into
  ;; `*current-frame*`. Every consumer of the ambient scope keys records /
  ;; rings / registries by the frame ID, so the reader yields the id — a
  ;; keyword target passes through unchanged (`frame-value->id` is identity
  ;; on non-values), and a value normalizes to its runnable id.
  ;;
  ;; THE REFUSAL TIER (rf2-2rtt6.122) is one nil-test on a var read, and it
  ;; is the whole of what the ordinary path pays. When no extent has refused
  ;; — every extent of every existing adapter — the expression evaluated is
  ;; byte-for-byte the one that was here before. When an extent HAS refused,
  ;; resolution collapses to the CARRIED tier alone, which is exactly the
  ;; `:clj` branch: the React-context tier is withdrawn, an explicit
  ;; `with-frame` still answers — for the extent's OWN frame (rf2-nqj22).
  (if-some [refusal *ambient-frame-refusal*]
    (carried-frame-admitted-by refusal)
    (frame-value->id
      #?(:cljs (if-let [f (late-bind/get-fn-cached :adapter/current-frame)]
                 (f)
                 (current-frame))
         :clj  (current-frame)))))

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
;; carries capture-site ancestry through the `:rf.trace/dispatch-id`
;; correlation graph (read off the in-scope `trace/*handler-scope*`, whose
;; five slots carry no parent — per Spec 009 §Dispatch correlation,
;; `:rf.trace/parent-dispatch-id` is scoped to `:rf.event/dispatched` only and
;; is walked FROM this dispatch), so the hardest case — a callback captured at
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
     :where       <sym>       ;; the resolving call site, a SYMBOL
     :event-id    <kw>        ;; the in-flight op's id, when known
     :recovery    :supply-frame}

  `:where` is a SYMBOL naming the resolving fn (`'re-frame.subs/subscribe`),
  never a keyword — `NoFrameContextTags` in spec/Spec-Schemas.md declares the
  slot `:symbol`, and every call site in the corpus emits a quoted symbol.

  `:reason` is always composed, and names BOTH halves of the repair (rf2-8747).
  EP-0002 resolves a frame three ways — **scope**, **hold** (carry), and
  **override** — and the enumerated \"three distinct ways\" cover only scope
  (1, 2) and override (3). The single most-travelled way to reach this error is
  a deferred callback — an `:on-click`, a timer, a `.then` — written inside a
  live scope and run after it unwound, and for that case all three are the wrong
  advice: the author has not forgotten to establish a scope, they had one and it
  ended. The composed sentence therefore leads with the **hold** repair (the
  `reg-view` macro's injected render-time `dispatch` / `subscribe`, or an
  explicit `(:dispatch (rf/capture-frame))` taken while the scope is live) and
  keeps the three scope/override ways behind an \"otherwise\". This is prose
  only: the discriminator is `:rf.error/id` and the machine-readable recovery
  is `:recovery :supply-frame`, both unchanged — per Spec 009, nothing branches
  on message bytes.

  `:rf.trace/dispatch-id` (the capture-site correlation key) is merged
  whenever a handler scope is in effect. There is
  no `:rf.trace/parent-dispatch-id` on this payload: per Spec 009 §Dispatch
  correlation that key is scoped to `:rf.event/dispatched` only, and the
  parent is walked from the dispatch-id through the correlation graph.

  `extra` (optional) merges caller-supplied `:where` / `:event-id`. Caller-
  supplied keys win over the defaults so a call site can name itself
  precisely."
  ([operation] (no-frame-context-payload operation nil))
  ([operation extra]
   (merge {:rf.error/id :rf.error/no-frame-context
           :operation   operation
           :recovery    :supply-frame
           :reason      (str "a frame-scoped " (name operation) " ran with no frame "
                             "context — no carried frame stamp and no established "
                             "scope. Frame identity is carried, not found. If this fired "
                             "from an :on-* handler, a timer, a promise or any other "
                             "deferred callback, the scope it was WRITTEN inside had "
                             "already unwound by the time it RAN: the repair is to carry "
                             "the frame across that boundary, not to establish a second "
                             "scope there. In a view, use the dispatch / subscribe the "
                             "reg-view macro injects — they are render-time captures; "
                             "elsewhere hold (:dispatch (rf/capture-frame)) while the "
                             "scope is still live. Otherwise establish a "
                             "scope one of three distinct ways (do not combine 1 and 2): "
                             "(1) create a frame with rf/make-frame, then scope the "
                             "operation inside it with with-frame or a frame-provider "
                             "(which SCOPES an existing frame); (2) let a frame-root "
                             "ENSURE the frame (create-if-absent) without pre-creating "
                             "it; or (3) pass an explicit {:frame <id>}. Per Spec 002 "
                             "§The error and its ladder.")}
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

;; ---- :rf.error/ambient-frame-refused — a frame was found but is not ------
;; ---- reachable ambiently here (rf2-2rtt6.122) ----------------------------
;;
;; The fourth member of the frame-resolution error family, and it is a
;; DIFFERENT MISTAKE from the other three, which is the whole reason it has
;; its own id:
;;
;;   - absence (no stamp, no scope)     → `:rf.error/no-frame-context`
;;   - a bad public provider argument   → `:rf.error/bad-frame-provider-arg`
;;   - a disturbed React-context read   → `:rf.error/frame-context-corrupted`
;;   - a frame IS in scope, and reaching it AMBIENTLY is illegal in this
;;     render extent → THIS.
;;
;; Reporting the refusal as `no-frame-context` would be actively misleading:
;; that error's recovery is "establish a scope", and the author has one —
;; a Hicasso body always sits under a frame boundary. Following the generic
;; advice (wrap it in another provider, or a `with-frame`) would not fix the
;; frozen boundary, because the boundary was never the problem. The refusing
;; substrate supplies the sentence that IS the fix, and this payload carries
;; it verbatim.
;;
;; ALWAYS-ON, on the same ladder as `:rf.error/no-frame-context` and for the
;; same reason: what it prevents is a boundary that silently stops
;; re-rendering, which is a correctness failure with no symptom at the point
;; of the mistake. A dev-gated refusal would also buy nothing — the extent's
;; own binding is what costs, and the resolver's nil-test cannot be elided
;; while the var can be bound at all — while introducing the one divergence
;; worth least: code that throws in development and silently freezes in
;; production.

(defn ambient-frame-refused-payload
  "Build the canonical `:rf.error/ambient-frame-refused` payload for a
  frame-scoped `operation` that resolved AMBIENTLY inside an extent which
  refused ambient resolution. `refusal` is the substrate's detail map (see
  [[*ambient-frame-refusal*]]); `extra` (optional) supplies call-site detail
  (`:where` / `:event-id`) and wins over the defaults.

  The substrate's own keys are merged in AFTER the frame, so `:substrate`,
  `:recovery` and any extra detail it carries reach the reader; its
  `:reason` is folded into the composed prose rather than replacing it, so
  the payload always says both what happened and what to do instead.

  TWO REFUSALS, ONE ID, TWO SENTENCES (rf2-nqj22). `extra`'s
  `:carried-frame` — set by [[require-current-frame!]] and by nothing else —
  says which refusal this is. Absent: nothing was carried, the ambient FIND
  was refused, and the prose is the one that has always been here. Present:
  a stamp WAS carried and it names a frame other than the extent's
  `:extent-frame`, so the composed sentence names BOTH frames and says why
  a carried stamp lost for once — because the generic sentence's closing
  promise (\"an explicitly carried frame still carries\") is precisely the
  thing that just did not happen, and a diagnostic that says the opposite of
  what occurred is worse than none."
  ([operation refusal] (ambient-frame-refused-payload operation refusal nil))
  ([operation refusal extra]
   ;; The extent's frame is normalized to an id here and nowhere else: a
   ;; substrate may declare a frame VALUE, and a payload — or worse, a
   ;; sentence — carrying a whole frame map instead of `:app` is unreadable.
   (let [extent-frame (frame-value->id (:extent-frame refusal))]
     (merge {:rf.error/id :rf.error/ambient-frame-refused
             :operation   operation
             :recovery    :use-the-substrates-own-read-surface
             :reason      (if-some [carried (:carried-frame extra)]
                            (str "a frame-scoped " (name operation) " resolved its frame "
                                 "AMBIENTLY inside a render extent that refuses ambient "
                                 "frame resolution"
                                 (when-some [s (:substrate refusal)]
                                   (str " (" (name s) ")"))
                                 ", and the stamp it found — " (pr-str carried)
                                 " — is NOT the frame this extent is rendering ("
                                 (pr-str extent-frame) "). A carried stamp "
                                 "ordinarily wins, and everywhere it is the only frame in "
                                 "play it still does; here it would put TWO frames in one "
                                 "body — this " (name operation) " targeting " (pr-str carried)
                                 " while the body's own reads, lowered intents and children "
                                 "target " (pr-str extent-frame) " — and frames are "
                                 "ISOLATED contexts. Carry the extent's own frame if that is "
                                 "what you meant, or move the operation outside the render. "
                                 (:reason refusal default-ambient-refusal-reason))
                            (str "a frame-scoped " (name operation) " resolved its frame "
                                 "AMBIENTLY inside a render extent that refuses ambient "
                                 "frame resolution"
                                 (when-some [s (:substrate refusal)]
                                   (str " (" (name s) ")"))
                                 ". This is NOT an absence: a frame IS in scope here, so "
                                 "adding another frame boundary or a with-frame will not "
                                 "help. The extent refuses the AMBIENT reach specifically — "
                                 "an explicitly carried frame still carries, both as a "
                                 "{:frame <id>} option and through a with-frame naming THIS "
                                 "extent's own frame. "
                                 (:reason refusal default-ambient-refusal-reason)))}
            ;; Capture-site ancestry, exactly as `no-frame-context-payload`
            ;; threads it — the refused op may sit under a captured callback.
            (when-let [did (some-> trace/*handler-scope* :dispatch-id)]
              {:rf.trace/dispatch-id did})
            (dissoc refusal :reason :extent-frame)
            (when (some? extent-frame) {:extent-frame extent-frame})
            extra))))

(defn emit-ambient-frame-refused!
  "Surface `:rf.error/ambient-frame-refused` through the always-on error axis
  AND the dev-only trace surface, then return the payload — the same ladder
  `emit-no-frame-context!` rides, because the failure it replaces (a
  boundary that silently stops re-rendering) is exactly as invisible in
  production as a frameless op is.

  This is the EMISSION half; `require-current-frame!` emits then throws."
  [payload]
  (when-let [dispatch-on-error! (late-bind/get-fn :error-emit/dispatch-on-error)]
    (dispatch-on-error!
      :rf.error/ambient-frame-refused
      nil                                ;; no event vector — a refused resolution, not a throw on a dispatch
      (:event-id payload)
      nil                                ;; no frame — the whole point is that none is legal here
      nil                                ;; no exception — an illegal operation, not a throw
      0                                  ;; elapsed-ms
      (interop/now-ms)))                 ;; time
  (trace/emit-error! :rf.error/ambient-frame-refused payload)
  payload)

;; ---- :rf.error/bad-frame-provider-arg — a bad explicit target -------------
;;
;; Distinct from `:rf.error/no-frame-context`. A public
;; `frame-provider` whose `:frame` is non-nil but NEITHER a keyword NOR a
;; live frame value has carried an explicit-but-malformed target —
;; `{:frame "app"}`, `{:frame 7}`, `{:frame ['x]}`. `frame-provider` accepts
;; a frame TARGET — a frame-id keyword OR a live frame value (`make-frame`'s
;; return token), API-shrink #1 rf2-csbbwu — so anything else is a
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
  public `frame-provider` call whose `:frame` is non-nil but neither a
  keyword nor a live frame value. `received` is the offending value;
  `extra` (optional) merges call-site detail (`:where`)."
  ([received] (bad-frame-provider-arg-payload received nil))
  ([received extra]
   (merge {:rf.error/id :rf.error/bad-frame-provider-arg
           :received    received
           :recovery    :supply-frame-target
           :reason      "frame-provider :frame must be a frame id keyword (e.g. :todo) or a live frame value (make-frame's return token); anything else is a bad public provider argument, not a carried frame."}
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

(defn require-frame-provider-target!
  "Validate a public `frame-provider`'s `:frame` arg — accepts a frame-id
  KEYWORD or a live frame VALUE (`make-frame`'s return token), normalizing
  either to the frame id via `frame-value->id` (API-shrink #1, rf2-csbbwu —
  `frame-provider` teaches the same one frame-target grammar as `dispatch`
  / `subscribe` / `destroy-frame!`). A nil value routes to the
  `:rf.error/no-frame-context` path (absence — the provider
  establishes no usable scope). Any other non-nil value emits + throws
  the distinct `:rf.error/bad-frame-provider-arg` so the bad explicit
  target fails loudly at the provider rather than being silently coerced to
  a registered keyword frame by the lower-level context reader.

  `where` is a SYMBOL naming the validating call site for the payload (every
  caller passes a quoted fn symbol, e.g.
  `'re-frame.views.provider/frame-provider`). The nil branch threads `where` +
  a `:supply-frame` recovery into the no-frame-context payload, matching each
  provider surface's nil handling."
  [frame-target where]
  (cond
    (keyword? frame-target) frame-target
    (frame-value? frame-target) (frame-value->id frame-target)
    (nil? frame-target)
    (let [payload (no-frame-context-payload
                    :frame-provider
                    {:where where :recovery :supply-frame})]
      (emit-no-frame-context! payload)
      (throw (error/ex-info-from-data payload)))
    :else
    (let [payload (bad-frame-provider-arg-payload frame-target {:where where})]
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
  directly and never throw.

  TWO ABSENCES, TWO ERRORS (rf2-2rtt6.122). When the reader returned nil
  because the enclosing render extent REFUSED the ambient tier
  ([[call-with-ambient-frame-refused]]), the mistake is not an absence and
  must not be reported as one: `:rf.error/ambient-frame-refused` is emitted
  and thrown instead, carrying the refusing substrate's own account of what
  to write there.

  AND A MISMATCH IS A THIRD (rf2-nqj22). The reader now answers nil for one
  more reason: inside an extent that declared its own `:extent-frame`, a
  carried stamp naming a DIFFERENT frame is not an answer that extent will
  accept, because the body would then read and dispatch against one frame
  while its own reads, lowered intents and children target another. This
  raises the same `:rf.error/ambient-frame-refused`, with the two frames
  named on the payload so the author is not sent looking for the wrong one.
  A MATCHED carried stamp still answers — refusing it would make `with-frame`
  and `{:frame …}` disagree, which is strictly worse than the silence this
  closes.

  The discrimination costs the ordinary path nothing: it is read only after
  resolution has already failed."
  ([operation] (require-current-frame! operation nil))
  ([operation extra]
   (or (resolve-current-frame)
       (if-some [refusal *ambient-frame-refusal*]
         ;; Two refusals, one id. The reader answered nil either because
         ;; nothing was carried at all (the ambient FIND was refused) or
         ;; because what was carried is not this extent's frame — and only
         ;; the second can name a stamp, so `:carried-frame` is what the
         ;; payload builder discriminates on. Read here, on the failed path,
         ;; rather than threaded down from the reader, so the ordinary
         ;; resolution stays a single call returning a single value.
         (let [payload (ambient-frame-refused-payload
                         operation refusal
                         (if-some [carried (frame-value->id (current-frame))]
                           (assoc extra :carried-frame carried)
                           extra))]
           (emit-ambient-frame-refused! payload)
           (throw (error/ex-info-from-data payload)))
         (let [payload (no-frame-context-payload operation extra)]
           (emit-no-frame-context! payload)
           (throw (error/ex-info-from-data payload)))))))

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

;; ---- bind-fn — INTERNAL dynamic-rebinding carry primitive ------------------
;;
;; API-shrink #1 (rf2-csbbwu): the public `frame-bound-fn` / `frame-bound-fn*`
;; are REMOVED from the facade — `re-frame.core/capture-frame` is the ONE
;; public HOLD primitive. `bind-fn`'s semantics are genuinely DIFFERENT from
;; `capture-frame`'s pre-bound `{:dispatch :dispatch-sync :subscribe}` op
;; bundle: it re-establishes `*current-frame*` around an ARBITRARY
;; already-held fn (including one that itself calls `current-frame-id` or
;; other frame-scoped readers), so it survives here for the framework's own
;; internal / test / tooling reach.

(defn bind-fn
  "INTERNAL. Wrap `f` so `*current-frame*` is re-established to `frame` for
  the duration of each call to the returned fn — i.e. re-run `f` UNDER an
  explicit frame binding rather than through `capture-frame`'s op bundle.
  The returned fn dispatches / reads into `frame` even when invoked after
  the caller's own dynamic / React-context scope has unwound (the async-
  boundary case `with-frame` / a `frame-provider` / `frame-root` cannot
  cover). NOT an app-facing surface."
  [frame f]
  (fn [& args]
    (binding [*current-frame* frame]
      (apply f args))))

;; ---- lookup ---------------------------------------------------------------
;;
;; WHAT ONE DISPATCH COSTS THIS REGISTRY, RECORDED (rf2-l260n). A single
;; `dispatch-sync` of a one-line `:db`-writing handler re-resolves the registry
;; 124 times:
;;
;;     frame                                    124
;;     frame-record-visible-to-current-actor?   125
;;     frame-incarnation-token                  115
;;     frame-incarnation-closing?               105
;;
;; Method: counting wrappers installed over those four vars for the duration of
;; ONE dispatch, plain-atom substrate, JVM, default debug gate. Structural
;; counts, not timings — identical on every run. (The visibility predicate runs
;; once more than `frame` because one call arrives from a registry enumeration —
;; `frame-meta` / `frame-ids` — rather than through `frame`.)
;;
;; The total points at the wrong file, so the ATTRIBUTION is the part worth
;; keeping. 115 of the 124 arrive through `frame-incarnation-token` from
;; `frame-incarnation-live?`, and 105 of those from `event-continuation-live?` —
;; the liveness fence, consulted from TWENTY distinct sites in one pipeline run.
;; The heaviest are `router/call-while-exact-owner` (22), `process-event!`'s
;; inner continuation (21) and `prepare-handler-ctx` (9). The four router seams
;; that already hold a frame record — `run-one-pass!`, `prepare-handler-ctx`,
;; `commit-and-flow!`, `emit-pipeline-trailers!` — account for 20 between them.
;;
;; So the tempting remedy, record-taking arities at the seams that already hold
;; the record, reaches about a sixth of the resolutions; collapsing the rest
;; means threading an incarnation token through a chain whose widest hop
;; (`emit-pipeline-trailers!`) already takes eight positional arguments. That
;; trades clarity for microseconds and is DELIBERATELY NOT TAKEN — the fence
;; density is the design: every hop that could outlive its incarnation asks. The
;; one unconditionally free win here, dropping the literal-path `get-in` from
;; these predicates, is already applied (rf2-sj5s7).

(defn- frame-record-visible-to-current-actor?
  "True when raw registry record `f` is a live frame visible to this actor.

  Final records are process-visible. A provisional record is visible only when
  the current dynamic construction owner is the record's exact owner AND that
  identity still holds `id` on this host thread. This is the single visibility
  predicate for exact lookup and every public registry enumeration; no reader
  may expose a half-constructed id by filtering raw `@frames` independently."
  [id f]
  (and (not (-> f :lifecycle :destroyed?))
       (or (not= :provisional (-> f :construction :state))
           (let [owner (-> f :construction :owner)]
             (and (identical? owner *frame-transaction-owner*)
                  (owner-holds-frame-id? owner id))))))

(defn frame
  "Return the frame record for `id` (a frame-id keyword), or nil if not
  registered, still provisional under another host actor's construction
  transaction, or destroyed. The registry is keyed by the bare frame-id.

  A provisional record is visible only to its exact construction owner on the
  owning host thread. Synchronous setup and lifecycle publication can therefore
  use the ordinary frame machinery, while unrelated callers never observe the
  former live-looking pre-setup row. Final records have no owner restriction.

  2-level lookup written as keyword-invoke (`(-> f :lifecycle :destroyed?)`)
  rather than `(get-in f [:lifecycle :destroyed?])` — `get-in` allocates
  a path vector per call, and `frame` runs on every dispatch
  / subscribe through `current-frame` resolution."
  [id]
  (when-let [f (get @frames id)]
    (when (frame-record-visible-to-current-actor? id f)
      f)))

(defn frame-incarnation-token
  "Return a STABLE per-incarnation identity token for the currently-live frame
  `id`, or nil when the frame is absent or destroyed.

  The token is the frame record's `:drain-lock` atom. `new-frame-record` builds
  a fresh `:drain-lock` for every FIRST-time construction, and every in-place
  record swap over a frame's life — surgical re-registration, `set-generation!`,
  `mark-frame-destroyed!` — preserves that atom by identity. So the token is
  CONSTANT across one incarnation and DISTINCT across a `destroy-frame!` +
  fresh construction of the same id.

  A lifecycle-sensitive registry mutation (cold `reg-flow`) captures this token
  while the frame is live, then admits its write ONLY while the SAME token is
  still live (`(identical? pinned (frame-incarnation-token id))`). Because
  `destroy-frame!` flips liveness under that SAME `:drain-lock` (both go through
  `call-serialized-with-drain!`), the registration and the destruction
  linearize: the mutation can neither leave a ghost row on a destroyed frame
  nor clobber a newer incarnation that reused the id."
  [id]
  (some-> (frame id) :drain-lock))

(defn frame-incarnation-live?
  "True when `token` still identifies `id`'s currently-live frame.

  Internal event-tail fence: a dequeued event captures this token before its
  handler runs. If that handler destroys A and a fresh same-id B appears before
  the returned transition/effects settle, the identity comparison is false, so
  A's tail can neither commit nor dispatch into B."
  [id token]
  (and (some? token)
       (identical? token (frame-incarnation-token id))))

(declare event-continuation-live?)

(defn frame-disposed-for-drain?
  "Per Spec 002 §Frame disposal mid-drain: predicate used by the
  router's drain loop to interrupt a pass once destruction owns the current
  incarnation. True when ANY of:

    (a) `destroy-frame!` has claimed this exact incarnation, even while its
        live-container teardown is still running before the `:destroyed?`
        flip. The claim is the queued-work cutoff: a cold serialization release
        never re-kicks that queue, and an already-scheduled drain drops rather
        than executes it. The intentional `:on-destroy` event uses a separate,
        token-scoped internal cascade; ordinary drains have no exemption.
    (b) The frame record still exists but `:destroyed?` is flipped
        (post-step-5 of `destroy-frame!`, before step-10 dissoc), OR
    (c) The frame record is absent from the `frames` atom (post-step-10
        of `destroy-frame!` — the dissoc step has run).

  The claim test is INCARNATION-SCOPED: a stale destroy marker for incarnation A
  does not dispose a fresh same-id incarnation B installed after A's registry
  dissoc but before A's terminal marker cleanup. Returns false when `id` is
  registered, live, and not claimed for destruction. Calling for a
  never-registered `id` returns true — that case is benign for the drain-loop
  caller (a drain cannot run on a frame that was never registered), but the
  predicate is named `*-for-drain?` to make the intended seam explicit and avoid
  suggesting general destroyed-vs-never-registered discrimination.

  Keyed by the bare frame-id."
  [id]
  (if-let [f (get @frames id)]
    (or (true? (-> f :lifecycle :destroyed?))
        (let [token (:token (get @destroying-frames id))]
          (and (some? token)
               (identical? token (:drain-lock f)))))
    ;; Absent from the atom — destroy-frame!'s step 10 ran, OR the id
    ;; was never registered. The drain-loop caller only consults this
    ;; while a pass is already in flight, so the latter case cannot
    ;; arise from that seam.
    true))

(defn frame-closing?
  "True when `id` is anywhere in its CLOSE lifecycle: a `destroy-frame!` for it
  is IN FLIGHT (its teardown recipe is running) OR the frame is already
  destroyed / dissoc'd. False for a live, not-being-destroyed frame — including
  a FRESH same-id incarnation created after a prior destroy fully completed.

  The linearization read the compiled-view ViewCell commit consults so a cell
  that acquires handles + enrols while a frame is being torn down does not
  publish ownership onto a dying frame (rf2-vxgfnd.61). `destroying-frames` is
  populated at the TOP of `destroy-frame!` — BEFORE the `:ui/on-frame-destroyed!`
  sweep snapshots the live cells — and cleared only in `destroy-frame!`'s
  terminal `finally`, AFTER `mark-frame-destroyed!` flips liveness and
  `dissoc-frame!` forgets the record. So `frame-closing?` is CONTINUOUSLY true
  across the entire teardown window (the in-flight-but-not-yet-flipped sub-window
  the destroyed?/absent test alone MISSES). A commit that enrols into the
  live-cell registry and THEN reads this predicate therefore linearizes the
  sweep's victim SELECTION against the frame's CLOSURE: if its enrolment was too
  late for the sweep's snapshot, the frame was necessarily already in
  `destroying-frames` when the commit read it, so it observes the close and joins
  the teardown instead of stranding `:connected`. Keyed by the bare frame-id.

  BARE-ID: this predicate cannot distinguish WHICH incarnation is closing. In the
  JVM window where an old incarnation A's marker is still set (post-`dissoc-frame!`,
  pre-`finally`) while a fresh same-id REPLACEMENT incarnation B is already live,
  this reads true for the id — which would wrongly tear down a cell that owns B.
  A commit that must resolve against the EXACT incarnation it acquired consults the
  incarnation-scoped `frame-incarnation-closing?` instead (rf2-vxgfnd.94)."
  [id]
  (or (contains? @destroying-frames id)
      (frame-disposed-for-drain? id)))

(defn frame-incarnation-closing?
  "True when `id`'s in-flight `destroy-frame!` is tearing down EXACTLY the
  incarnation identified by `token` (its `:drain-lock` — see
  `frame-incarnation-token`), false otherwise.

  The incarnation-scoped counterpart to `frame-closing?`: where `frame-closing?`
  answers 'is this id anywhere in its close lifecycle' by BARE id,
  `frame-incarnation-closing?` answers 'is the destroy that is in flight for this
  id the one destroying the incarnation I acquired'. `destroying-frames` records
  `{id {:token destroying-incarnation-token}}`, so this
  compares the caller's acquire-time token against the token the marker carries
  by identity.

  This is what closes rf2-vxgfnd.88's reciprocal Failure-2 (rf2-vxgfnd.94): in the
  JVM window between `destroy-frame!`'s step-10 `dissoc-frame!` and its terminal
  `finally`, incarnation A's marker is still set while a fresh same-id incarnation
  B is already live under the reused id. A ViewCell commit that ACQUIRED B (its
  captured token is B's) reads false here (B's token ≠ A's marker token), so B's
  cell is not torn down by A's stale close authority — while the rf2-vxgfnd.61
  in-flight case (a commit that acquired A while A is mid-teardown, still live
  pre-flip) reads true (A's token IS the marker token) and correctly joins A's
  teardown. Nil `token` (or no marker for `id`) reads false.

  Keyed by the bare frame-id; the incarnation is disambiguated by the token value."
  [id token]
  (let [closing-token (:token (get @destroying-frames id))]
    (and (some? closing-token) (identical? token closing-token))))

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

(defn frame-config
  "The frame's stored CONFIG map for `id` — the post-preset-expansion
  user-supplied metadata alone — or nil when no frame is registered under `id`.

  The narrow sibling of [[frame-meta]], for the consult points that need ONE
  config key and run on the render path. `frame-meta` answers the canonical
  `:rf/frame-meta` SHAPE, and building that shape costs a three-way `merge`
  per call; a consult that reads `:url-strategy` and nothing else was paying
  for the whole flattening to reach one key. Routing's
  `url-strategy-for-frame-id` runs once per rendered `route-link` — measured at
  0.72 µs per link on the rf2-cno31 census probe, against a `route-url`
  synthesis of 4.71 — and it is that caller this exists for.

  This is NOT a widening of what callers may depend on. The lifecycle fields
  `frame-meta` merges (`:created-at`, `:destroyed?`, `:listeners`) and the `:id`
  it stamps are disjoint from the config keys, so for any config key the two
  answer identically; a caller wanting the canonical reflection shape — every
  tool — still calls `frame-meta`, which remains the only shape tools may
  depend on."
  [id]
  (:config (frame id)))

(def ^:private live-frame-id-xf
  "Transducer over `@frames` `[id record]` pairs → the `:id` of each
  registered frame visible to the current actor. The shared front of both
  `frame-ids` arities (the 1-arity composes a prefix filter after it) delegates
  lifecycle + provisional visibility to the same predicate as exact `frame`
  lookup. The frame-id is read from the record's own `:id` slot (which equals
  the map key, the bare frame-id)."
  (comp (filter (fn [[id f]]
                  (frame-record-visible-to-current-actor? id f)))
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
  carries a resolved image `:generation`, is visible to the current actor, and
  its id is a PUBLIC id (the reserved `:rf.frame/<gensym>` namespace — no-id /
  direct frames — excluded). The selection predicate `image-loaded-frame-ids`
  uses to pick the records it projects ids off. INTERNAL."
  [id f]
  (and (some? (:generation f))
       (frame-record-visible-to-current-actor? id f)
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
        (comp (filter (fn [[id f]] (image-loaded-frame-record? id f)))
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
  (`[:rf/machine …]`, `[:rf.route/*]`) read it as their signal source; an
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

(defn ^:no-doc frame-record-state-value
  "Read the coherent frame-state value from an already-resolved frame record.

  Exact-incarnation event preparation uses this instead of resolving the bare
  frame id again: a synchronous adapter read can invalidate the captured
  incarnation, but it can never redirect the read into a fresh same-id frame.
  The caller must recheck its owner token after this callback boundary."
  [frame-record]
  (when-let [container (:frame-state frame-record)]
    (adapter/read-container container)))

;; ---- EP-0001 partition commit + write helpers -----------------------------
;;
;; The frame-state container is the ONE physical write target. Every durable
;; state write — the router's per-event commit, the privileged runtime
;; mutators, full-frame tool install — flows through `replace-container!` on
;; it. Per Spec 002 §An ordinary :db return replaces only app-db + §Write
;; authority is by convention, and Spec 006 §Commit boundary.

;; ---- per-frame commit epoch (Spec 006 §The internal observation port) ------
;;
;; A monotonic per-frame counter bumped once per PHYSICAL frame-state install
;; (both write chokepoints: `commit-frame-transition!` and `swap-partition!`).
;; The observation port's probe/acquire EVIDENCE carries it as `:frame-epoch`
;; so the commit-side evidence comparison can detect "the frame's durable
;; state moved in the render→commit gap" without watching anything — a pure
;; counter read, no watch, no allocation on the read side. A value-equal-but-
;; fresh-object install still bumps (the counter tracks physical installs, not
;; `=`-change) — a false-positive epoch advance costs at most one redundant
;; commit-side evidence re-check, which invariant 5 already prices in; a
;; MISSED advance would break correction-before-paint, so the counter is
;; conservative in the safe direction. The identical?-noop short-circuit in
;; `commit-frame-transition!` genuinely installs nothing, so it does not bump.
;;
;; Storage is a dedicated side atom (frame-id → int), NOT a slot on the frame
;; record: the record is swapped through several lifecycle paths that preserve
;; state-bearing slots by identity, and threading a counter through all of
;; them would couple every swap site to this concern. `dissoc-frame!` clears
;; the destroyed frame's entry so the table stays bounded by live frames.

(defonce ^:private frame-commit-epochs
  (atom {}))

(def ^:private inc-commit-epoch
  "`(fnil inc 0)`, hoisted. `fnil` is a CALL that builds a fresh closure —
  and, being multi-arity + variadic, an expensive one — so spelling it at
  the `swap!` site below built a new one on every commit to express a
  constant. Measured at 745 B per write on node V8 (rf2-78ejq), 21% of what
  a zero-subscription write costs. The value is the same function either
  way; only the number of times it is constructed changes."
  (fnil inc 0))

(defn- bump-commit-epoch!
  "Advance frame `id`'s commit epoch by one. Called at BOTH physical
  frame-state write chokepoints, after the container install. INTERNAL."
  [id]
  (swap! frame-commit-epochs update id inc-commit-epoch)
  nil)

(defn frame-commit-epoch
  "Return the monotonic per-frame commit epoch for frame `id` — the count of
  physical frame-state installs since the frame's registration (0 for a fresh
  or unknown frame). Consumed by the observation port's probe/acquire
  evidence (Spec 006 §The internal observation port); a moved epoch between
  two port reads means the frame's durable state was (re)installed in the
  gap. Pure read."
  [id]
  (get @frame-commit-epochs id 0))

(def ^:private stale-exact-callback ::stale-exact-callback)

(defn- call-exact-frame-callback
  "Run adapter callback `f` for an exact event-owned incarnation.

  The tagged pair distinguishes a legitimate nil callback result from owner
  loss.  A destroy+throw is inert; a throw while the exact owner remains live
  preserves the adapter's existing failure contract."
  [id owner-token exact-owner? f]
  (if-not exact-owner?
    [::ok (f)]
    (if-not (event-continuation-live? id owner-token)
      stale-exact-callback
      (try
        (let [result (f)]
          (if (event-continuation-live? id owner-token)
            [::ok result]
            stale-exact-callback))
        (catch #?(:clj Throwable :cljs :default) e
          (if (event-continuation-live? id owner-token)
            (throw e)
            stale-exact-callback))))))

(defn- commit-frame-record-transition!
  "Install `partitions` into an already-resolved live frame record.

  Keeping the record/container together is load-bearing for exact-incarnation
  callers: after resolution, a concurrent registry replacement can at worst
  make this container detached; it can never redirect the write into a fresh
  same-id record."
  [id frame-record partitions owner-token exact-owner?]
  (let [container  (:frame-state frame-record)
        read-result (call-exact-frame-callback
                      id owner-token exact-owner?
                      #(adapter/read-container container))]
    (when-not (= stale-exact-callback read-result)
      (let [current    (second read-result)
        app-given? (contains? partitions app-partition-key)
        rt-given?  (contains? partitions runtime-partition-key)
        next-app   (if app-given? (get partitions app-partition-key)
                       (get current app-partition-key))
        next-rt    (if rt-given? (get partitions runtime-partition-key)
                       (get current runtime-partition-key))
        next-fs    {app-partition-key     next-app
                    runtime-partition-key next-rt}
        changed    (cond-> #{}
                     (and app-given?
                          (not= next-app (get current app-partition-key)))
                     (conj app-partition-key)
                     (and rt-given?
                          (not= next-rt (get current runtime-partition-key)))
                     (conj runtime-partition-key))]
    ;; `read-container` and `replace-container!` are host-adapter callback
    ;; boundaries.  The exact-owner path rechecks after each: a synchronous
    ;; container watch may destroy A and publish same-id B while A's physical
    ;; install is notifying observers.  That install has already linearized in
    ;; A's captured container (and therefore legitimately appears in A's
    ;; halted-destroy snapshot), but no id-keyed commit epoch or later trace may
    ;; be attributed to B.
    (when (or (not exact-owner?)
              (event-continuation-live? id owner-token))
      ;; ONE atomic frame-state install — both partitions in one write, per
      ;; Spec 006 §Commit boundary.
      (when-not (and (identical? next-app (get current app-partition-key))
                     (identical? next-rt  (get current runtime-partition-key)))
        (let [replace-result
              (call-exact-frame-callback
                id owner-token exact-owner?
                #(adapter/replace-container! container next-fs))]
          (when-not (= stale-exact-callback replace-result)
            (bump-commit-epoch! id))))
      ;; nil is the exact path's terminal-loss marker.  The router suppresses
      ;; commit traces, fx, trailers and normal epoch settlement in that case.
      (when (or (not exact-owner?)
                (event-continuation-live? id owner-token))
        changed))))))

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
  before calling this.

  The 3-arity is the router's exact-incarnation commit. It returns nil unless
  `owner-token` still names the live record, and writes through that resolved
  record's own container so a same-id replacement cannot redirect the write."
  ([id partitions]
   (when-let [frame-record (frame id)]
     (commit-frame-record-transition! id frame-record partitions nil false)))
  ([id owner-token partitions]
   (when-let [frame-record (frame id)]
     (when (identical? owner-token (:drain-lock frame-record))
       (commit-frame-record-transition!
         id frame-record partitions owner-token true)))))

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
  Internal single-partition helper retained for symmetry with
  `replace-app-db!`; the public/epoch-backed write surface is
  `replace-frame-state!` (API-shrink #3, rf2-t3lftq). Atomic install through
  the one physical container. Returns the set of changed partition keys, or
  `nil` for an unknown / destroyed frame."
  [id runtime-db]
  (commit-frame-transition! id {runtime-partition-key runtime-db}))

(defn replace-frame-state!
  "Atomically install `frame-state` — a PARTIAL frame-state map (any subset
  of `{:rf.db/app … :rf.db/runtime …}`) — into `id`'s one physical
  frame-state container: a PRESENT key replaces that partition, an ABSENT
  key is carried forward UNCHANGED (rf2-t3lftq — API-shrink #3). A
  db-shaped key never silently touches the other partition; this is the
  one explicit frame-state write surface (Mike ruling #10, preserved and
  generalised). Both a caller-supplied app-only, runtime-only, or
  both-partition map install in ONE atomic write. Returns the set of
  changed partition keys, or `nil` for an unknown / destroyed frame.

  This is a thin pass-through onto `commit-frame-transition!`, which
  already implements the present-replaces / absent-preserves contract —
  `replace-frame-state!` merely narrows `frame-state` to the two
  recognized partition keys before delegating. Callers wanting the former
  single-partition helpers compose a one-key map:
  `(replace-frame-state! id {app-partition-key new-app-db})` /
  `(replace-frame-state! id {runtime-partition-key new-runtime-db})`.

  The 3-arity is the EXACT-INCARNATION variant (mirrors
  `commit-frame-transition!`'s own 2/3-arity split): it resolves the write
  through `owner-token`'s own frame record and installs nothing — returning
  `nil` — unless `owner-token` still names the live incarnation, so a same-id
  successor reseated under `id` after the caller captured its token can never
  receive the write. Epoch restore (`perform-restore!`) threads the token it
  validated preconditions against so a time-travel install lands only on the
  exact frame incarnation it resolved (rf2-bjh6y)."
  ([id frame-state]
   (commit-frame-transition! id (select-keys frame-state [app-partition-key runtime-partition-key])))
  ([id owner-token frame-state]
   (commit-frame-transition! id owner-token (select-keys frame-state [app-partition-key runtime-partition-key]))))

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
  only writer during fx drain. INTERNAL.

  rf2-vxgfnd.155 — the 6-arity is the EXACT-INCARNATION variant used by the
  callback-bearing flow-registry lifecycle writes (`clear-flow` / `reg-flow`
  output-mark refresh and path vacation). It resolves the write through
  `owner-token`'s own frame record (a same-id successor can never redirect it),
  and — `replace-container!` is a host-adapter callback boundary whose
  synchronous watch may destroy A and publish same-id B mid-install — rechecks
  ownership after the callback. A's write that physically linearized in A's
  captured container before the loss stands (it may legitimately appear in A's
  halted-destroy snapshot), but the id-keyed commit-epoch bump is FENCED so it
  never lands on B. Returns the new slice, or nil on owner loss (the caller
  treats a nil as a no-op and rechecks liveness before its own later actions)."
  ([id pk f args] (swap-partition! id pk f args nil false))
  ([id pk f args owner-token exact-owner?]
   (if-not exact-owner?
     (when-let [container (frame-state-container id)]
       (let [current   (adapter/read-container container)
             new-slice (apply f (get current pk) args)]
         (adapter/replace-container! container (assoc current pk new-slice))
         ;; Observation-port evidence counter (Spec 006 §The internal
         ;; observation port): one bump per physical frame-state install —
         ;; this is the second (and last) frame-state write chokepoint.
         (bump-commit-epoch! id)
         new-slice))
     ;; Exact-incarnation write (rf2-vxgfnd.155). Resolve the record so the
     ;; write binds to A's own container; a same-id B cannot redirect it.
     (when-let [frame-record (frame id)]
       (when (identical? owner-token (:drain-lock frame-record))
         (let [container   (:frame-state frame-record)
               read-result (call-exact-frame-callback
                             id owner-token true
                             #(adapter/read-container container))]
           (when-not (= stale-exact-callback read-result)
             (let [current   (second read-result)
                   new-slice (apply f (get current pk) args)]
               ;; A synchronous watch inside `read-container` may already have
               ;; lost A; recheck before the physical install.
               (when (event-continuation-live? id owner-token)
                 (let [replace-result
                       (call-exact-frame-callback
                         id owner-token true
                         #(adapter/replace-container!
                            container (assoc current pk new-slice)))]
                   ;; The install callback's own watch may destroy A. A's write
                   ;; linearized in A's captured container, but the id-keyed
                   ;; commit-epoch bump must not attribute to same-id B.
                   (when-not (= stale-exact-callback replace-result)
                     (bump-commit-epoch! id)
                     new-slice)))))))))))

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

(defn ^:no-doc swap-frame-db-exact!
  "Exact-incarnation `swap-frame-db!` (rf2-vxgfnd.155): threads `owner-token`
  so a synchronous container watch that destroys A and publishes a same-id B
  during the physical install neither redirects the write into B nor bumps B's
  commit epoch. A's write that linearized before the loss stands in A's
  captured container. Used by the callback-bearing flow-registry lifecycle
  writes. Returns the new app-db slice, or nil on owner loss."
  [id owner-token f & args]
  (swap-partition! id app-partition-key f args owner-token true))

(defn ^:no-doc swap-runtime-db-exact!
  "Exact-incarnation `swap-runtime-db!` (rf2-vxgfnd.155) — the runtime-db
  sibling of `swap-frame-db-exact!`, used by the exact-aware elision-registry
  writes the flow lifecycle ops issue. Returns the new runtime-db slice, or
  nil on owner loss."
  [id owner-token f & args]
  (swap-partition! id runtime-partition-key f args owner-token true))

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

(defn- current-thread-owns-drain-serialization?
  "True when the calling thread ALREADY holds `frame-record`'s single-drainer
  serialization — via EITHER path that takes the frame's `:drain-lock`:

    - it is the active event DRAINER (`:in-drain?`, stamped by the router
      around `run-one-pass!`), OR
    - it is the current holder of a COLD `call-serialized-with-drain!` critical
      section (`:serialized-holder`, set below on the non-reentrant acquire
      path).

  The drain and the cold serialization helper contend on the SAME
  non-reentrant `:drain-lock` CAS cell, but the cold path stamps no
  `:in-drain?`. So a nested serialized op issued from INSIDE a cold critical
  section — e.g. a Tool-Pair state write whose body calls `destroy-frame!`
  (whose liveness flip is itself serialized), or any lifecycle op reached from
  a `call-serialized-with-drain!` thunk — must be detected HERE and run
  DIRECTLY; otherwise it spin-CAS's forever on a lock its own thread already
  holds. Mirrors `current-thread-is-drainer?` for the cold-hold axis; on CLJS
  the marker is `true`/`nil` and the same equality discriminates."
  [frame-record]
  (or (current-thread-is-drainer? frame-record)
      (let [holder @(:serialized-holder frame-record)]
        #?(:clj  (identical? holder (Thread/currentThread))
           :cljs (true? holder)))))

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

;; rf2-wxy1c retired the by-frame-id `thread-owns-drain-serialization?` probe and
;; its `:frame/thread-owns-drain-serialization?` late-bind publication. It existed
;; for exactly one consumer — `re-frame.trace.tooling`, which asked it whether an
;; outermost trace emit should drive its listener fan-out inline (off
;; `fanout-monitor`) to avoid the rf2-jl75r AB-BA cycle. That question no longer
;; arises: the trace tooling now DEFERS a drain-owned fan-out to the post-drain
;; boundary the three `:drain-lock` regions establish
;; (`trace/call-with-deferred-listener-delivery` — `re-frame.router/drain-try!` /
;; `drain-block!` and `call-serialized-with-drain!` below), so the deferral scope
;; itself is the ownership evidence and no cross-namespace probe is needed. The
;; private `current-thread-owns-drain-serialization?` above remains, serving the
;; reentrancy fast-path it was written for.

(defn call-serialized-with-drain!
  "Run thunk `f` serialized against `frame-id`'s event drain, returning its
  value. Used by per-frame lifecycle mutations that must not interleave with a
  concurrent `run-flows-on-db` pass or with each other — the flows lifecycle
  ops (`reg-flow` / `clear-flow`), the `destroy-frame!` liveness flip
  (`mark-frame-destroyed!`), and Tool-Pair state writes.

  - Frame absent (unregistered / destroyed): nothing can be draining it, so
    just run `f`.
  - Calling thread already OWNS the serialization — it is the active drainer
    (mid-drain `:rf.fx/*` call), OR it already holds a COLD critical section on
    this frame (e.g. a Tool-Pair write body that calls `destroy-frame!`): run
    `f` directly. Re-taking the non-reentrant `:drain-lock` CAS cell from a
    thread that already holds it would self-deadlock (see
    `current-thread-owns-drain-serialization?`).
  - Otherwise: spin-CAS-acquire `:drain-lock` (the same acquire shape
    `re-frame.router/drain-block!` uses — bounded wait: an active drainer holds
    it for at most `drain-depth` events), stamp this thread as the
    `:serialized-holder`, run `f`, then clear the holder and release the lock
    in a `finally`.

  The cold acquire → run → release region is wrapped in
  `trace/call-with-deferred-listener-delivery` (rf2-wxy1c): trace events emitted
  inside a held cold section are delivered at that post-drain boundary, once the
  lock is down, rather than running arbitrary listener code under it. The
  already-owns branch inherits its owner's scope (the wrapper is nesting-aware),
  so a nested serialized op appends to the SAME batch and the outermost holder
  flushes it once."
  [frame-id f]
  (if-let [frame-record (frame frame-id)]
    (if (current-thread-owns-drain-serialization? frame-record)
      (f)
      (trace/call-with-deferred-listener-delivery
       (fn []
         (let [drain-lock (:drain-lock frame-record)
               holder     (:serialized-holder frame-record)]
           (loop []
             (when-not (compare-and-set! drain-lock false true)
               #?(:clj (Thread/yield))
               (recur)))
           (reset! holder #?(:clj (Thread/currentThread) :cljs true))
           (try
             (f)
             (finally
               ;; Clear the holder BEFORE releasing the lock: once the lock is
               ;; free another thread may acquire it and stamp its own holder, so
               ;; clearing after the release could clobber that new owner.
               (reset! holder nil)
               ;; Per rf2-x76af2.22 (a): the cold release must mirror the
               ;; drainer's `try-release-on-empty!`. A `dispatch!` that arrived
               ;; DURING the hold set `:scheduled?` true and scheduled a
               ;; `drain-try!` that CAS-lost to us and gave up — and, because we
               ;; are NOT a drainer, nothing is left to re-check the queue. So a
               ;; plain `(reset! drain-lock false)` PERMANENTLY STRANDS the queue
               ;; (`:scheduled?` stuck true no-ops every later
               ;; `ensure-drain-scheduled!`). Under the same lock submitters take
               ;; in `ensure-drain-scheduled!`, snapshot the queue (stable — we
               ;; still hold `:drain-lock`, so no drainer is popping) and release;
               ;; if non-empty, re-kick a fresh async `drain-try!` (via the
               ;; `:router/reschedule-drain!` late-bind seam — frame cannot
               ;; `:require` router) so the stranded events drain. The one
               ;; exception is a thunk that just CLAIMED destruction of this exact
               ;; incarnation: claim is the queued-work cutoff, so re-kicking here
               ;; would manufacture work inside the claim -> lifecycle-dead
               ;; window. An already-submitted drain that has not CAS-lost yet is
               ;; fenced independently by `frame-disposed-for-drain?`; suppressing
               ;; this fresh kick handles the already-lost/no-retry case without
               ;; stranding any LIVE frame. Releasing first lets an allowed
               ;; re-kicked `drain-try!` CAS-acquire the now-free lock.
               (let [router  (:router frame-record)
                     strand? (locking router
                               (let [pending? (seq (:queue @router))
                                     closing? (frame-incarnation-closing?
                                                frame-id drain-lock)]
                                 (reset! drain-lock false)
                                 (boolean (and pending? (not closing?)))))]
                 (when strand?
                   (when-let [reschedule! (late-bind/get-fn :router/reschedule-drain!)]
                     (reschedule! frame-id frame-record))))))))))
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
      'rf/make-frame
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
                       runtime-partition-key {}})
        ;; Partition projections, constructed into locals under a
        ;; FAILURE-ATOMIC boundary (rf2-vxgfnd.198). `make-derived-value`
        ;; returns an opaque value that may install a real host resource (a
        ;; source watch) and be externally owned by the adapter, so a PARTIAL
        ;; allocation — the app-db projection returns, then the runtime-db
        ;; projection throws — must not strand the first projection's watch on
        ;; a frame that never gets installed. When a LATER projection throws,
        ;; every EARLIER successfully-returned projection is disposed in
        ;; REVERSE acquisition order through the existing `interop/dispose!`
        ;; seam (the same seam `tear-down-partition-projections!` uses at
        ;; normal teardown), then the original construction error is
        ;; re-raised so `try-install-new-frame!` installs nothing. The
        ;; physical `frame-state` container is GC-owned (Spec 006 — no
        ;; per-container disposal verb), so only the disposable projections
        ;; are unwound here; a `make-derived-value` that throws BEFORE
        ;; returning has already unwound its own unreturned partial work
        ;; (Spec 006 §`make-derived-value` internal failure-atomicity).
        ;; Bound in `let` rather than inline map values so acquisition order
        ;; is deterministic (a many-entry map literal evaluates its values in
        ;; unspecified order) and the reverse-order rollback is exact.
        app-db      (adapter/make-derived-value [frame-state] app-partition-key)
        runtime-db  (try
                      (adapter/make-derived-value [frame-state] runtime-partition-key)
                      (catch #?(:clj Throwable :cljs :default) e
                        ;; Reverse acquisition order: dispose the already-
                        ;; returned app-db projection best-effort — a throwing
                        ;; dispose must not MASK the original construction
                        ;; failure (mirrors the best-effort posture of
                        ;; `tear-down-partition-projections!`) — then re-raise.
                        (try (interop/dispose! app-db)
                             (catch #?(:clj Throwable :cljs :default) _ nil))
                        (throw e)))]
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
    ;; keyword invoke per recompute. Constructed above under a failure-atomic
    ;; boundary (rf2-vxgfnd.198).
    :app-db      app-db
    :runtime-db  runtime-db
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
   ;; Thread that currently holds a COLD `call-serialized-with-drain!` critical
   ;; section on this frame (JVM: the Thread; CLJS: `true`), or nil when free.
   ;; The router drain marks its hold via the router's `:in-drain?`; the cold
   ;; serialization path stamps it here instead, so a nested serialized op on
   ;; the same thread (e.g. a Tool-Pair write body that calls `destroy-frame!`,
   ;; whose liveness flip is itself serialized) is detected as reentrant rather
   ;; than self-deadlocking on the non-reentrant `:drain-lock` CAS cell.
   :serialized-holder (atom nil)
    :sub-cache  (atom {})
    :lifecycle  {:created-at (interop/now-ms)
                 :destroyed? false
                 :listeners  []}
    ;; The construction-only reserved `:rf.frame/generation` key is consumed
    ;; above into the `:generation` slot; it is stripped from the stored
    ;; `:config` so `frame-meta` / tooling never surface a one-shot construction
    ;; input as durable frame config. (EP-0026, rf2-dlvmpc: the
    ;; `:rf.frame/capabilities` config slot is gone with the image-capability
    ;; feature.)
    ;; EP-0027: `:initial-events` is DURABLE frame config — it stays in `:config`
    ;; so a destroy + re-`make-frame` (a full "reset" composition, rf2-lxwpob)
    ;; can re-dispatch the recorded setup. The retired
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
  (`'rf/make-frame`). Returns `[]` for an absent / empty value
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
  message. `where-sym` is the constructor symbol. `owner-token` is the EXACT
  incarnation token this construction installed (the record's `:drain-lock`,
  returned by `try-install-new-frame!`): teardown is exact-token-owned, so a
  setup step that (re-entrantly / concurrently) destroyed A and seated a same-id
  successor B before this rollback ran cannot make the stale A rollback destroy
  B — the two-argument `destroy-frame!` no-ops unless A's token is still live
  (rf2-wduv35)."
  [id idx event cause-ex cause-msg where-sym owner-token]
  (destroy-frame! id owner-token)
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
  `:rf.trace/call-site` of the `make-frame` declaration (gated on
  `interop/debug-enabled?` by the caller, so production CLJS builds DCE it) — so a
  setup event attributes back to where `:initial-events` was declared (EP-0027
  §Provenance). The step's own `:opts` overlay it (a step may carry `:rf.cofx`,
  etc.), and the framework keys (`:frame` / `:source` / `:step-index`) win last.

  Reached through `dispatch-sync!` via late-bind to avoid a compile-time cyclic
  dep (router requires frame).

  `owner-token` is the EXACT incarnation token the winning `make-frame` install
  produced (the record's `:drain-lock`). Every teardown path here — the
  escaping-throw + in-band `raise-setup-step-failed!` branches and the
  runner-unavailable branch — destroys via the two-argument `destroy-frame! id
  owner-token`, so a rollback owns exactly the incarnation it constructed and
  can never destroy a same-id successor that replaced it in the interim
  (rf2-wduv35)."
  [id steps base-opts where-sym owner-token]
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
    ;; hook is published before any runtime frame construction — is unaffected.)
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
                      id idx event t (error/ex-message-safe t) where-sym owner-token)))
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
                  where-sym owner-token)))
            (recur (inc idx) (rest remaining)))))
      ;; The runner hook is unavailable but there ARE steps to run: fail loud
      ;; rather than silently dropping the setup (rf2-jsokxu). Tear down the
      ;; partial frame (the caller already swapped the container into `frames`)
      ;; so no half-created, never-setup frame is left live, then throw naming
      ;; that re-frame.router is not loaded. Teardown is exact-token-owned
      ;; (`owner-token`) so it removes only the incarnation we installed
      ;; (rf2-wduv35).
      (do
        (destroy-frame! id owner-token)
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

(defn- fire-frame-registered-hook!
  "Fire the routing artefact's POST-(RE-)REGISTRATION lifecycle extension
  hook (rf2-g8pbwg), by key. No-op when `re-frame.routing` is not loaded (the
  artefact is optional).

  Called at the END of BOTH `upsert-frame!` branches below — first
  registration (after `:initial-events` ran and `:rf.frame/created` emitted)
  and re-registration (after the surgical config update and
  `:rf.frame/re-registered` emitted) — so the frame container is always
  guaranteed live by the time the hook fires. This is THE frame
  (re-)registration lifecycle extension point: frames do not flow through
  `registrar/register!` (rf2-h1vqa4 — the frames registry is the one store),
  so there is no registrar registration hook for frames.

  Currently consumed by routing (`:routing/on-frame-registered!` — maintains
  the `:url-bound?` exclusivity check + URL-ownership claim order on BOTH
  hosts, then installs/rewires the CLJS URL-change listener when the
  (re-)registered frame is the resolved URL owner; a losing duplicate
  `:url-bound? true` registration is a no-op). Not wrapped in
  `safe-call-hook!` (the teardown-specific accumulator/diagnostic pairing) —
  an extension hook here runs OUTSIDE any teardown, so a genuine failure
  propagates loudly rather than being swallowed."
  [id]
  (when-let [on-registered! (late-bind/get-fn :routing/on-frame-registered!)]
    (on-registered! id)))

(def ^:dynamic ^:no-doc *upsert-decide-probe*
  "JVM linearization TEST SEAM — `nil` in production (one `nil` check per
  construction, zero further cost). When bound to a 1-arg fn, `upsert-frame!`
  calls `(*upsert-decide-probe* id)` exactly ONCE after acquiring the per-id
  transaction and immediately BEFORE the authoritative registry decision path.
  A concurrency fixture binds it (conveyed into the racing thread by `future`'s
  binding-conveyance) to pause the owner while same-id construction loses
  promptly or a disjoint id proceeds. NEVER bound off the JVM test path."
  nil)

(defn- fresh-trace-policy-token
  "Return an identity token for one attempted frame-config commit. A successful
  registry mutation stores it on the frame record; auxiliary trace-policy
  writers publish only while that exact token remains current."
  []
  #?(:clj (Object.) :cljs (js-obj)))

(def ^:dynamic ^:no-doc *upsert-policy-probe*
  "JVM test seam fired after an exact provisional frame revision is staged and
  immediately before its auxiliary trace policy enters publication
  serialization."
  nil)

#?(:clj
   (do
     (defonce ^:private frame-trace-policy-lock
       ;; Auxiliary policy lives in two independent atoms. Serialize the
       ;; tiny/cold publication section on the concurrent host.
       (Object.))
     (defn- call-with-frame-trace-policy-lock [f]
       (locking frame-trace-policy-lock (f)))))

(defn- serialize-frame-trace-policy!
  "Run one auxiliary trace-policy publication/clear section. JVM callers
  serialize across both stores; CLJS is single-threaded."
  [f]
  #?(:clj  (call-with-frame-trace-policy-lock f)
     :cljs (f)))

(defn- publish-trace-policy!
  [id publish!]
  (when-let [probe *upsert-policy-probe*] (probe id))
  (serialize-frame-trace-policy! publish!))

(defn- provisional-construction [owner revision]
  {:state    :provisional
   :owner    owner
   :revision revision})

(defn- provisional-owned? [f owner revision]
  (let [construction (:construction f)]
    (and (= :provisional (:state construction))
         (identical? owner (:owner construction))
         (identical? revision (:revision construction)))))

(defn- finalize-frame-construction!
  "Transition `id`'s exact owner/revision from provisional to final.

  Returns false if the provisional row was destroyed or replaced. The final
  record retains only the revision diagnostic; it does not retain the
  transaction owner after admission is released."
  [id owner revision]
  (loop []
    (let [registry @frames
          f        (get registry id)]
      (if (provisional-owned? f owner revision)
        (let [final-f (assoc f :construction
                             {:state :final :revision revision})]
          (if (compare-and-set! frames registry (assoc registry id final-f))
            true
            (recur)))
        false))))

(defn- restore-provisional-frame!
  "Roll back construction-owned fields while `id` still names this exact
  provisional revision.

  Surgical re-registration stages `:config`, `:generation`,
  `:trace-policy-token`, and `:construction`, but `:generation` also has a valid
  generation-only writer (`set-generation!`) used by live-frame reprojection.
  Preserve the current record and restore the failed constructor's config,
  policy token, and construction metadata unconditionally. Restore its staged
  generation only while that slot is still the exact value this revision
  installed; an intervening reprojection wins and is retained. State-bearing
  containers and every other current slot are therefore preserved by identity.

  A stale failure can never restore across a successor because owner and
  revision remain identity-pinned."
  [id owner revision prior staged]
  (loop []
    (let [registry @frames
          f        (get registry id)]
      (if (provisional-owned? f owner revision)
        (let [restored
              (cond-> (assoc f
                             :config (:config prior)
                             :trace-policy-token (:trace-policy-token prior)
                             :construction (:construction prior))
                (identical? (:generation f) (:generation staged))
                (assoc :generation (:generation prior)))]
          (if (compare-and-set! frames registry (assoc registry id restored))
            true
            (recur)))
        false))))

(defn- try-install-new-frame!
  "Allocate and install one PROVISIONAL record iff reserved `id` is absent.

  The caller already owns `id` in `frame-construction-reservations`, so no same-id
  creator or destroyer can enter this region. Adapter callbacks run only AFTER
  that reservation exists. The registry CAS may retry for unrelated-id writes,
  but the candidate is allocated once and either installed as the reserved
  owner's provisional row or unwound by `new-frame-record`'s allocation failure
  boundary. There is no process-wide monitor and disjoint ids never block here.

  Returns the INSTALLED incarnation's identity token (its `:drain-lock`) on a
  successful install, `nil` when the id was already present (nothing installed).
  Handing back the exact token of the record WE installed — rather than a bare
  `true` the caller would have to re-read via `frame-incarnation-token` — lets
  construction rollback OWN that exact incarnation: a failed-setup / handler-
  guard teardown passes this token to the two-argument `destroy-frame!`, which
  can then only destroy the frame this call created, never a same-id successor
  that replaced it in the interim (rf2-wduv35). A later re-read would repeat the
  same check-then-act race the token closes."
  [id config policy-token owner]
  (when-not (contains? @frames id)
    (let [f (assoc (new-frame-record id config)
                   :trace-policy-token policy-token
                   :construction (provisional-construction owner policy-token))]
      (loop []
        (let [registry @frames]
          (cond
            (contains? registry id)
            nil

            (compare-and-set! frames registry (assoc registry id f))
            ;; The record's `:drain-lock` IS the incarnation identity token
            ;; (`frame-incarnation-token`); return it so the winning caller owns
            ;; this exact incarnation through construction rollback.
            (:drain-lock f)

            :else
            (recur)))))))

(defn- throw-frame-id-taken!
  "Throw the typed `:rf.error/frame-id-taken` collision (rf2-vxgfnd.76) — the
  create-exclusive (`:rf.frame/must-create?`) primitive `ui.test` rests its
  fresh-isolated-frame contract on. Raised when an exclusive construction for
  `id` meets an already-live FINAL frame under the same id. Same-id overlap with
  an in-flight transaction instead fails earlier as
  `:rf.error/frame-construction-in-progress`; in exclusive mode a pre-existing
  final frame is a COLLISION, never an adoption or surgical refresh (the
  fall-through ordinary construction takes). Never returns."
  [id]
  (error/throw-error!
    :rf.error/frame-id-taken 'rf/make-frame
    (str "exclusive frame construction for " (pr-str id) " collided with an "
         "already-live frame under the same id. The caller requested "
         "create-exclusive construction (:rf.frame/must-create?), so a "
         "pre-existing final frame is a hard collision, "
         "not an adoption or a surgical refresh. Use a distinct frame id, or "
         "destroy the existing frame before constructing.")
    {:recovery :use-a-distinct-frame-id
     :extra    {:frame id}}))

(defn- deliver-incarnation-token!
  "Hand the EXACT installed incarnation token to an optional construction
  `token-sink` (a `volatile!`) from INSIDE the construction transaction
  (rf2-moftbs). `make-frame` passes a sink so it can embed the token on the
  returned frame VALUE without a post-return `frame-incarnation-token` re-sample
  of the registry — a re-sample a concurrent `destroy-frame!` + same-id reseat
  could race into handing back a successor's token. No-op when the caller passed
  no sink (`make-anon-frame-record!`, the `:rf/default` fixture, and the engine
  tests, which take the id return unchanged). Returns nil."
  [token-sink token]
  (when token-sink (vreset! token-sink token))
  nil)

(defn ^:no-doc upsert-frame!
  "PRIVATE frame ENGINE — atomic create-or-REFRESH (upsert) of the frame
  record for `id` (rf2-h1vqa4). Called by `re-frame.live-frame/make-frame`
  (the public constructor `rf/make-frame`) and tightly-scoped engine tests
  ONLY — not a stable entry point.

  The name pins the REFRESH semantics — this engine upserts SEATED ids too.
  Per Spec 002 §Frame lifecycle:
  - If the id is unregistered, create the frame container, run the
    :initial-events setup steps synchronously (EP-0027), return the keyword.
  - If the id is already SEATED, perform a SURGICAL UPDATE / IDEMPOTENT
    REPLACEMENT (EP-0024 §Duplicate id policy): existing runtime state
    (app-db, sub-cache, queue) is preserved; only the metadata/config is
    replaced (+ the `:generation` slot, when the caller threads
    `:rf.frame/generation`), and the recorded :initial-events is RE-RECORDED,
    never REPLAYED. Hot-reload Just Works. This IS re-construction — the
    Clojure re-def model: re-declaring the same id refreshes config while
    durable state survives. `:initial-events` replay is ONLY the opt-in
    full-replace composition (`destroy-frame!` then re-seat, rf2-lxwpob).

  SUBSTRATE OWNERSHIP (rf2-h1vqa4): the `frames` registry is the ONE store a
  seated frame lives in. Seating a frame writes NO registrar row and NO
  source-store descriptor — a frame is a LIVE runtime object, not a program
  member, so seating/reseating must never bump the registration source-store
  generation (which would invalidate the resolved-image-generation cache,
  EP-0023) nor surface in image assembly. Frame introspection is
  `frame-meta` / `frame-ids`, not the registrar query API.

  Returns the frame `id` on a successful create OR re-register (the documented
  keyword return every existing caller + engine test relies on). The optional
  3-arity `token-sink` (a `volatile!`) is an OUT-channel: on success the exact
  installed incarnation token (the record's `:drain-lock`) is delivered into it
  from INSIDE the construction transaction, so `make-frame` can embed exact
  authority on the returned frame VALUE with no post-return registry re-sample
  (rf2-moftbs). Passing no sink (the 2-arity) is byte-identical for every
  existing caller."
  ([id metadata] (upsert-frame! id metadata nil))
  ([id metadata token-sink]
  (let [;; The registry is keyed by the bare frame-id.
        config       (expand-preset metadata)
        ;; INTERNAL create-exclusive mode (rf2-vxgfnd.76): when the caller
        ;; threads `:rf.frame/must-create?`, a live final frame already present at
        ;; decide throws typed `:rf.error/frame-id-taken` instead of the ordinary
        ;; fall-through to a surgical re-registration. An in-flight same-id
        ;; transaction is the distinct `:rf.error/frame-construction-in-progress`.
        ;; `ui.test` consumes exclusive mode to
        ;; keep plan frames FRESH + isolated (adopt/refresh become collisions).
        ;; A construction-only reserved key: read here, stripped from `config`
        ;; below so it never lands in the stored `:config` or a lifecycle trace.
        must-create? (true? (:rf.frame/must-create? config))
        config       (dissoc config :rf.frame/must-create?)
        ;; EP-0027 PREFLIGHT (BEFORE any container / process-global write): reject
        ;; the retired `:on-create` / `:initial-db` keys fail-loud, and normalize
        ;; + validate `:initial-events` into a vector of setup steps. Both run
        ;; here so a bad construction declaration throws BEFORE any frame is
        ;; registered (EP-0027 §Failure — preflight validation; no frame left
        ;; registered). The normalized steps are dispatched after the container +
        ;; config are installed (first-registration branch below).
        _              (reject-retired-construction-keys! config 'rf/make-frame)
        setup-steps    (normalize-initial-events (:initial-events config) 'rf/make-frame)
        ;; EP-0015 §9: validate the surviving frame-owned policy key
        ;; (`:observability` sink policy) EARLY — pure, container-independent,
        ;; fail-loud. A retired `:sensitive` / `:large` frame key, an unknown
        ;; observability key, or a malformed sink entry throws here, BEFORE
        ;; any container exists or process-global write runs, so a bad
        ;; declaration leaves no half-registered frame and never reaches
        ;; `:initial-events`. Reached via late-bind: `re-frame.frame-classification`
        ;; requires this ns, so a static require would cycle; `re-frame.core`
        ;; requires it at boot so the hook is always published before any
        ;; runtime construction. No-op when the config carries no policy key
        ;; (the common case).
        ;;
        ;; EP-0025: durable app-db classification is NO LONGER a frame
        ;; annotation — the `:sensitive` / `:large {:app-db …}` durable
        ;; declaration moved to the commit-plane classification effects
        ;; (a `reg-event` returns `:sensitive` / `:large` alongside `:db`,
        ;; `re-frame.elision`). HTTP carrier classification is NO LONGER a
        ;; frame annotation either — the `:sensitive {:http …}` block moved
        ;; onto the `:rf.http/managed` `reg-fx` registration (`:carriers`).
        ;; So construction only VALIDATES the surviving `:observability` policy;
        ;; it installs NOTHING into the elision registry. (The retired
        ;; `:sensitive` and `:large` frame keys now fail loud.)
        _              (when-let [validate (late-bind/get-fn
                                            :frame-classification/validate!)]
                         (validate id config))
        ;; rf2-ktmto9: routing-owned frame-config PREFLIGHT at the frame-config
        ;; COMMIT chokepoint. The routing artefact validates an explicitly-
        ;; declared `:url-strategy` (shape + host-required callable legs) against
        ;; the FINAL expanded config — here, alongside the other pure preflights,
        ;; BEFORE the record build, the trace-policy
        ;; writes, the `frames` swap, the `:initial-events` setup dispatch, and
        ;; any trace emit. So a malformed declaration fails with ZERO residue on
        ;; a first registration, and a failed RE-registration preserves every
        ;; previously committed value (config, generation, URL listener, claim
        ;; order) and emits no `:rf.frame/re-registered`. Routing owns the
        ;; MEANING (presence semantics: an ABSENT `:url-strategy` is a no-op —
        ;; omission alone selects the default; a PRESENT key, including explicit
        ;; nil, must be a valid strategy map); core owns the TIMING. Late-bound
        ;; because core must not require the optional routing artefact (bundle
        ;; isolation). When the hook is UNPUBLISHED but the config declares
        ;; `:url-strategy`, fail loud — storing a strategy nobody can validate
        ;; or execute is worse than a dependency error (it would only fail
        ;; later, deep in a consult point). A `:url-bound?`-only config with no
        ;; `:url-strategy` key stays registrable before routing loads (the
        ;; late-load case is unchanged).
        _              (if-let [preflight-routing! (late-bind/get-fn
                                                    :routing/preflight-frame-config!)]
                         (preflight-routing! id config)
                         (when (contains? config :url-strategy)
                           (error/throw-error!
                             :rf.error/routing-artefact-missing
                             'rf/make-frame
                             (str "this frame config declares :url-strategy, which "
                                  "requires day8/re-frame2-routing on the classpath; "
                                  "add it to deps and require re-frame.routing at app "
                                  "boot, BEFORE the frame is constructed, so the "
                                  "strategy can be validated at registration time.")
                             {:extra {:frame id}})))
        ;; One identity token per attempted config commit. Only a successful
        ;; create/re-register installs it. Auxiliary policy stores consult the
        ;; installed token inside their own atomic updates, so teardown and exact
        ;; rollback cannot publish against the wrong revision.
        policy-token   (fresh-trace-policy-token)
        ;; rf2-umsyo9: the frame-scoped TRACE POLICY writes (the suppression flag
        ;; + the `:rf.trace/events-retained` retention override) are process-global
        ;; stores SEPARATE from the `frames` registry, so the registry commit
        ;; alone does NOT linearize them. PR #5776 ran them speculatively before
        ;; the commit,
        ;; which let a must-create LOSER — or a create/create loser — overwrite
        ;; the WINNER's trace policy for this id even though the loser installed
        ;; nothing, corrupting the live winner's tracing. They now ride the sole
        ;; transaction-owner branch via this thunk, so any admission loss is ZERO-WRITE
        ;; across every frame-owned store (the record AND its auxiliary
        ;; trace/retention stores), while first-registration and ordinary
        ;; re-registration still apply the winning config's policy EXACTLY ONCE.
        ;; Honoured on BOTH create and re-register so a hot-reload can flip either
        ;; flag either way; the won-create branch calls it BEFORE
        ;; `run-setup-events!` so an init-cascade against a trace-disabled tool
        ;; frame stays redacted.
        apply-trace-policy-for!
        (fn [applied-config applied-token]
          (let [policy-current?
                (fn []
                  (identical? applied-token
                              (:trace-policy-token (frame id))))]
          ;; Frame-level trace-emission gate: a frame registered with
          ;; `:rf.trace/frame-no-emit? true` is a tool / inspector frame (e.g.
          ;; Xray's `:rf/xray`) whose own reactive substrate must NOT flood the
          ;; shared trace ring it inspects. The flag is the frame-scoped sibling
          ;; of the handler-scoped `:rf.trace/no-emit?` (Spec 009 §Trace-emission
          ;; opt-out); `trace.cljc` owns the canonical set + predicate.
          (trace/set-frame-no-emit! id
                                    (true? (:rf.trace/frame-no-emit? applied-config))
                                    policy-current?)
          ;; Per Spec 009 §Retention contract: apply the per-frame
          ;; `:rf.trace/events-retained` override. When the key is absent the
          ;; frame inherits the process-default. Routed via late-bind so
          ;; production CLJS bundles (where trace.tooling is not loaded)
          ;; short-circuit cleanly — the trace-ring machinery is dev-only.
          (when-let [apply-retention! (late-bind/get-fn-cached
                                       :trace.tooling/apply-frame-events-retained-policy!)]
            (apply-retention! id
                              (contains? applied-config :rf.trace/events-retained)
                              (:rf.trace/events-retained applied-config)
                              policy-current?))))
        apply-trace-policy!
        (fn []
          (apply-trace-policy-for! config policy-token))]
    ;; SUBSTRATE OWNERSHIP (rf2-h1vqa4): deliberately NO `registrar/register!`
    ;; here. A seated frame lives in the `frames` registry alone. The former
    ;; `:frame` registrar row leaked into the registration SOURCE STORE
    ;; (`registrar/register!` → `source-store/record-descriptor!`), bumping the
    ;; source-store generation on every seat/reseat — invalidating the
    ;; resolved-image-generation cache (EP-0023) and marking the live-frame
    ;; reprojection dirty for a change that is NOT a registration-pool change.
    ;; Routing (the one former consumer) reads frame config via
    ;; `frame-meta` / `frame-ids`; the registrar `:frame` kind is reserved with
    ;; an intentionally EMPTY slot (the `:flow` precedent, rf2-en00bk).
    ;; One exact per-id reservation is acquired BEFORE the decision probe, any
    ;; adapter callback, provisional registry publication, trace-policy write,
    ;; synchronous setup, lifecycle trace, or extension hook. It is released in
    ;; `finally` only after the exact provisional revision becomes final or has
    ;; been rolled back. Disjoint ids never share a monitor; same-id entry fails
    ;; promptly on both hosts.
    (call-with-frame-transaction!
      id :construction true false
      (fn [owner]
        ;; Destruction publishes its exact-incarnation marker and acquires this
        ;; same reservation under the drain gate. If construction acquired first,
        ;; destroy loses as a prompt idempotent no-op; if destroy marked first,
        ;; this exact-token check fails construction before any side effect. A
        ;; stale marker from an already-dissoc'd incarnation does not match.
        (let [raw           (get @frames id)
              closing-token (:token (get @destroying-frames id))]
          (when (and raw
                     (some? closing-token)
                     (identical? closing-token (:drain-lock raw)))
            (throw-frame-construction-in-progress!
              id :lifecycle-closing :destruction)))
        (when-let [probe *upsert-decide-probe*] (probe id))
        (loop []
          (let [existing (get @frames id)]
            (cond
          ;; ---- CREATE: install the record iff the id is STILL absent ---------
          (nil? existing)
          (if-let [installed-token
                   (try-install-new-frame! id config policy-token owner)]
              ;; The reservation owns this id before allocation, and the row
              ;; remains provisional until setup + lifecycle publication all
              ;; succeed. Any throw destroys only this exact incarnation before
              ;; admission is released.
              (try
                ;; EP-0025: no durable app-db classification install here anymore
                ;; — the frame `:sensitive` / `:large {:app-db …}` annotation was
                ;; removed in favour of the commit-plane classification effects.
                ;; A `:frame-init` `:initial-events` step that classifies a path
                ;; runs (below) at creation, so init-cascade trace stays redacted.
                ;; EP-0027 §Construction — FORBID handler-time frame construction.
                ;; Frames are created by the VIEW (frame-root, ENSURE) or at TOP LEVEL
                ;; (tests, boot, SSR per request); constructing a frame INSIDE an
                ;; event handler is not supported. The signal for "inside a
                ;; handler" is `trace/*handler-scope*` being bound — the router
                ;; binds it for the duration of a handler's execution and ONLY
                ;; then. The container was already installed above; tear it back
                ;; down before throwing so a handler-time construction leaves NO
                ;; half-registered frame. Teardown owns `installed-token`, so it
                ;; removes exactly the incarnation we just installed — never a
                ;; concurrently-seated same-id successor (rf2-wduv35).
                (when trace/*handler-scope*
                  (destroy-frame! id installed-token)
                  (error/throw-error!
                    :rf.error/frame-construction-in-handler
                    'rf/make-frame
                    (str "constructing a frame inside an event handler is not supported "
                         "(EP-0027) — got a frame construction for " (pr-str id) " while a cascade is in "
                         "flight. Frames are created by the VIEW (a frame-root, which "
                         "ENSUREs the frame — create-if-absent) or at TOP LEVEL; a handler "
                         "changes app-db, and the view materializes frames from it. Move "
                         "the frame creation to a frame-root in the view tree, or to "
                         "top-level boot (rf/make-frame).")
                    {:recovery :construct-frames-in-view-or-top-level
                     :extra    {:frame id}}))
                ;; rf2-umsyo9: apply the winning config's frame-scoped trace
                ;; policy now that this create WON installation and passed the
                ;; handler-time guard — BEFORE `run-setup-events!` so a
                ;; trace-disabled tool frame's init cascade stays redacted, and
                ;; BEFORE the `:rf.frame/created` emit so that emit is suppressed
                ;; for a no-emit frame. A create that LOST the CAS never reaches
                ;; here, so it leaves the winner's policy untouched.
                (publish-trace-policy! id apply-trace-policy!)
                ;; Run the :initial-events setup steps synchronously, in order,
                ;; BEFORE emitting :frame/created (Spec 002 §Frame creation;
                ;; EP-0027 §Construction). `setup-steps` was PREFLIGHT-validated
                ;; at the top of the engine. A step that throws at runtime tears
                ;; down the partial frame inside the runner and rethrows —
                ;; teardown owns `installed-token`, so it destroys exactly this
                ;; incarnation. Runs ONCE — only on the WON install, never on a
                ;; lost-create recur.
                (run-setup-events! id setup-steps {} 'rf/make-frame installed-token)
                (trace/emit! :rf.frame :rf.frame/created
                             {:frame id :config (dissoc config :rf.frame/generation
                                                        :rf.frame/initial-db)})
                (fire-frame-registered-hook! id)
                (if (finalize-frame-construction! id owner policy-token)
                  ;; Hand back this exact incarnation's token (the record's
                  ;; `:drain-lock` `try-install-new-frame!` installed and
                  ;; finalization preserved by identity) from inside the
                  ;; transaction so `make-frame` embeds exact authority with no
                  ;; post-return re-sample (rf2-moftbs).
                  (do (deliver-incarnation-token! token-sink installed-token) id)
                  (throw-frame-construction-in-progress!
                    id :lifecycle-dead :destruction))
                (catch #?(:clj Throwable :cljs :default) e
                  (destroy-frame! id installed-token)
                  (throw e)))
              ;; LOST the create: `try-install-new-frame!` returned nil — nothing
              ;; was installed.
              (if must-create?
                ;; Exclusive mode (ui.test): a taken id is a hard COLLISION, not
                ;; an adoption — throw the typed error (nothing was installed).
                (throw-frame-id-taken! id)
                ;; Ordinary construction: fall through to a RE-registration on the
                ;; now-present id (idempotent replacement, EP-0024) — recur.
                (recur)))

          ;; A lifecycle-dead raw row is neither a live duplicate nor a legal
          ;; surgical-refresh target. Normal destruction still owns the id's
          ;; reservation here; this branch defends an orphaned/internal dead row.
          (true? (-> existing :lifecycle :destroyed?))
          (throw-frame-construction-in-progress!
            id :lifecycle-dead :destruction)

          ;; A provisional row without its matching reservation is internal
          ;; corruption, not a refreshable live frame. Fail closed.
          (= :provisional (-> existing :construction :state))
          (throw-frame-construction-in-progress!
            id :orphaned-provisional
            (-> existing :construction :owner :kind))

          ;; ---- must-create met a LIVE final frame → COLLISION ----------------
          ;; Exclusive mode never adopts or surgically refreshes; a present id is
          ;; a hard collision (the id may have been created since the claim).
          must-create?
          (throw-frame-id-taken! id)

          ;; ---- RE-REGISTER: stage one exact provisional revision -------------
          ;; Per Spec 002 §Re-registration. EP-0024 idempotent replacement:
          ;; refresh the `:generation` slot + replaceable `:config` while durable
          ;; runtime state (app-db, sub-cache, queue) is preserved. EP-0027 §Reset
          ;; — the new `:initial-events` is RE-RECORDED into `:config`, never
          ;; REPLAYED (replay is the opt-in `destroy-frame!` then `make-frame`
          ;; composition, rf2-lxwpob).
          :else
          (let [observed      existing
                stored-config (dissoc config
                                      :rf.frame/generation
                                      :rf.frame/initial-db)
                [old new]
                (swap-vals!
                  frames
                  (fn [registry]
                    (let [current (get registry id)]
                      (if (and current
                               (not (-> current :lifecycle :destroyed?))
                               (identical? (:drain-lock observed)
                                           (:drain-lock current)))
                        (assoc registry id
                               (assoc current
                                      :config stored-config
                                      :generation
                                      (get config :rf.frame/generation)
                                      :trace-policy-token policy-token
                                      :construction
                                      (provisional-construction
                                        owner policy-token)))
                        registry))))
                ;; The candidate above is built from the registry value that
                ;; the atomic swap actually replaced. A generation-only writer
                ;; may have updated that record after `observed` was read but
                ;; before this swap linearized, so rollback must use this exact
                ;; pre-stage value rather than the stale observation.
                prior  (get old id)
                staged (get new id)]
            (if (provisional-owned? staged owner policy-token)
              (try
                ;; Policy + trace + hook all run while the staged revision is
                ;; provisional. Only their complete success publishes final.
                (publish-trace-policy! id apply-trace-policy!)
                (trace/emit! :rf.frame :rf.frame/re-registered
                             {:frame id :config stored-config})
                (fire-frame-registered-hook! id)
                (if (finalize-frame-construction! id owner policy-token)
                  ;; Idempotent re-registration PRESERVES the incarnation's
                  ;; `:drain-lock` by identity (the surgical swap-vals only
                  ;; replaces config/generation/trace-policy/construction), so the
                  ;; staged record's token IS the still-live incarnation token —
                  ;; hand it back so a re-`make-frame` value carries the SAME
                  ;; authority as the original (rf2-moftbs).
                  (do (deliver-incarnation-token! token-sink (:drain-lock staged)) id)
                  (throw-frame-construction-in-progress!
                    id :lifecycle-dead :destruction))
                (catch #?(:clj Throwable :cljs :default) e
                  ;; Restore only this exact staged owner/revision, then restore
                  ;; its auxiliary policy. A rollback publication fault must not
                  ;; mask the original constructor failure.
                  (when (restore-provisional-frame!
                          id owner policy-token prior staged)
                    (try
                      (publish-trace-policy!
                        id
                        #(apply-trace-policy-for!
                           (:config prior)
                           (:trace-policy-token prior)))
                      (catch #?(:clj Throwable :cljs :default) _ nil)))
                  (throw e)))
              (recur)))))))))))

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
    (upsert-frame! id config)
    id))

(defn make-frame-value
  "Build a live frame VALUE for frame id `runnable-id` (EP-0024) —
  the lifecycle token `make-frame` returns. INTERNAL: the value carries the
  `:rf.frame/object` marker, its `:rf.frame/runnable-id` (= the id its record is
  keyed by), and the public `:rf.frame/id` + the creation input
  (`:rf.frame/adapter`) when present. The resolved
  generation is NOT embedded on the value — it lives on the record
  (`:generation`), read by id via `frame-generation`, so a value and its id
  resolve the same generation and a `reload-images!` swap is observed by every
  holder of either. Pure map assembly; `id` is the public frame id (nil for a
  no-id direct value), `runnable-id` the record address.

  EP-0027 retired `:initial-db`: app-db seeding is now a setup event
  (`:initial-events`), so the constructed value no longer carries an
  `:rf.frame/initial-db` slot. EP-0026 (rf2-dlvmpc) retired the
  `:rf.frame/capabilities` slot with the image-capability feature.

  rf2-moftbs: a fresh/idempotent construction threads its EXACT incarnation
  `token` (the installed `:drain-lock`) through here so the returned value
  carries `:rf.frame/incarnation-token` — the opaque lifecycle-token authority
  an owner consumes so its `destroy-frame!` is incarnation-EXACT. A nil `token`
  (a derived-read value from `live-frame` / `image-view-frames`) omits the slot,
  leaving that value ADDRESS-directed."
  [{:keys [id runnable-id adapter token]}]
  (cond-> {object-marker         true
           runnable-id-key       runnable-id}
    (some? id)      (assoc :rf.frame/id id)
    (some? adapter) (assoc :rf.frame/adapter adapter)
    (some? token)   (assoc incarnation-token-key token)))

;; ---- destruction ----------------------------------------------------------
;;
;; destroy-frame! runs an ordered teardown. Each step lives in its own
;; named helper so the body of destroy-frame! reads as a step list. Order
;; matters — see destroy-frame!'s docstring for the authoritative recipe.

;; Frame id of the in-flight `destroy-frame!`, bound for the duration of
;; teardown so `record-teardown-failure!` can stamp `:frame` on a teardown-step
;; failure diagnostic regardless of the step's arg shape (the cache-reset hooks
;; take no frame arg).
(def ^:dynamic *destroying-frame-id* nil)

;; Per-destroy accumulator of teardown-STEP failures, bound to a fresh atom
;; by `destroy-frame!` for the duration of the teardown walk. Every failed
;; step conj's one entry through the shared `record-teardown-failure!`
;; boundary — `{:hook <step-key> :exception <ex> :where <catch-boundary>}`,
;; where `:where` is `:safe-call-hook!` for a late-bound cleanup hook and
;; `:safe-teardown-step!` for a guarded direct step (the wire names
;; `:hook-failures` / `:hook` are deliberately stable and span both kinds).
;; The finally-shaped flush at the bottom of `destroy-frame!` ships them as the
;; single always-on `:rf.error/frame-teardown-failed` report's
;; `:hook-failures` vector. ACCUMULATING into a side atom (rather than
;; emitting per-step on the always-on axis) is what makes the flush
;; FINALLY-shaped: if a downstream teardown step aborts the walk mid-recipe,
;; the entries collected so far are already in the atom and the `finally`
;; boundary still flushes them (EP-0008 R1 / Spec 009 §Emit-safety —
;; finally-shaped flush). nil outside a destroy (defensive —
;; `record-teardown-failure!` only conj's when bound).
(def ^:dynamic *teardown-hook-failures* nil)

;; SENSE (rf2-p4cd9c): event-pipeline-run — bound around ONE dequeued event's
;; run (`process-event!`), not the reactive graph. Renamed cascade->run per the
;; glw1bh event-pipeline vocabulary (a run = one event's traversal).
;;
;; Pre-run frame-state snapshot of the in-flight dequeued event, bound by
;; the router around `process-event!` (see `re-frame.router/run-one-pass!`).
;; A handler that calls `destroy-frame!` on its own frame mid-drain runs
;; INSIDE that binding, so `destroy-frame!` can recover the whole frame-state
;; (both partitions) held BEFORE the in-flight event's run began — the
;; `:frame-state-before` slot the `:halted-destroy` epoch record carries per
;; Spec-Schemas §`:rf/epoch-record` §Outcomes. The canonical snapshot unit is
;; the whole frame-state; the epoch derives `:db-before` from its app-db
;; projection.
;; nil outside a drain (an out-of-run `destroy-frame!` — hot-reload, a
;; destroy + re-`make-frame` reset composition, REPL — commits no
;; `:halted-destroy` record, so the slot is moot there).
(def ^:dynamic *run-frame-state-before* nil)

(defn guard-open-drain!
  "The SHARED open-event-drain guard — the `:rf.error/flush-in-open-epoch` signal
  (Spec 006 §Render-batch finalization; Spec 009 §Error event catalogue). Reject a
  synchronous registry-flush forced from `where` while a frame's run-to-completion
  event drain is STILL OPEN: flushing there could publish partially-settled queued
  update/commit work (a torn read/render).

  It lives in CORE because it closes over no view state whatever — only
  `*run-frame-state-before*` and the frame accessors above — so every substrate
  whose synchronous flush can publish a render phase reaches THIS one fn:
  `re-frame.ui.substrate/flush-render!` and `ui.test/flush!` on the compiled-view
  substrate, `re-frame.freehand.substrate/flush-render!` on Freehand. One guard,
  not one copy per substrate, and Freehand acquires it without requiring anything
  from `re-frame.ui` (rf2-87ouj). Contrast the convergence bound, which cannot be
  shared this way: each substrate's `converge-flush!` closes over that substrate's
  OWN cell registry, so there the two implementations are deliberately independent
  and the `:where` slot disambiguates them (rf2-jew4k).

  `*run-frame-state-before*` is bound around the current event-pipeline run and
  SURVIVES a handler destroying its own frame — a live registry scan cannot, since
  destroy removes the active frame before the handler returns, which used to let a
  destroy-self-then-flush call cross the guard and deliver render-phase work inside
  the still-open run. Throws BEFORE the caller touches its registry (no partial
  flush); a no-op outside any drain."
  [where]
  (when (some? *run-frame-state-before*)
    (let [frame-id (frame-target->id *current-frame*)]
      (error/throw-error!
       :rf.error/flush-in-open-epoch where
       (str where " was called while frame " (pr-str frame-id)
            " is still inside its event drain — let the queued update and "
            "commit phases run to completion before forcing a read/render batch")
       {:recovery :no-recovery
        :extra {:frame frame-id
                :frame-epoch (frame-commit-epoch frame-id)}}))))

(def ^:dynamic ^:private *event-owner*
  "Unforgeable exact-incarnation authority for the currently dequeued event.

  The router binds this outside the authored interceptor context.  Application
  interceptors may freely replace/rebuild the context map without being able to
  forge ownership of a fresh same-id incarnation."
  nil)

(defn ^:no-doc call-with-event-owner-token
  "Run `f` with the exact-incarnation authority captured by the router.

  The raw dynamic var is private so authored handlers/interceptors cannot
  replace the framework's binding after creating a same-id successor. This
  narrow runner is the only binding seam; a nested authored call cannot change
  the outer event pipeline's authority once it returns."
  ([frame-id token f]
   (call-with-event-owner-token frame-id token false f))
  ([frame-id token allow-closing? f]
   (binding [*event-owner* {:frame          frame-id
                            :token          token
                            :allow-closing? allow-closing?}]
     (f))))

(defn ^:no-doc current-event-owner-token
  "Return the router-bound event owner token to framework internals."
  []
  (:token *event-owner*))

(defn ^:no-doc current-event-owner-frame-id
  "Return the unforgeable dequeue-time owner frame id. Unlike ambient frame
  scope, this is unaffected by nested `with-frame` rebinding in authored code."
  []
  (:frame *event-owner*))

(defn ^:no-doc current-event-owner-allows-closing?
  "True only inside the router's exact-token private teardown cascade."
  []
  (true? (:allow-closing? *event-owner*)))

(defn ^:no-doc event-owner-live?
  "True when the router-bound event owner still names frame `id`."
  [id]
  (and (= id (:frame *event-owner*))
       (event-continuation-live? id (:token *event-owner*))))

(defn ^:no-doc event-continuation-live?
  "True while ordinary framework-owned event continuation may proceed.

  A destroy claim is the terminal cutoff even before lifecycle-dead. The sole
  exemption is the router's unforgeable exact-token private teardown cascade,
  whose owner binding carries `:allow-closing? true`."
  [id token]
  (and (frame-incarnation-live? id token)
       (or (not (frame-incarnation-closing? id token))
           (and (:allow-closing? *event-owner*)
                (= id (:frame *event-owner*))
                (identical? token (:token *event-owner*))))))

;; SENSE (rf2-p4cd9c): event-pipeline-run — the run's causal time, bound
;; alongside `*run-frame-state-before*`. Renamed cascade->run per glw1bh.
;;
;; The in-flight dequeued event's causal `:rf/time-ms` (the
;; `:rf.cofx` `:rf/time-ms` stamped on its envelope at the causal
;; boundary), bound by the router around `process-event!` alongside
;; `*run-frame-state-before*`. A handler that calls `destroy-frame!`
;; on its own frame mid-drain runs INSIDE this binding, so the
;; `:halted-destroy` epoch record's `:committed-at` is the DESTROYING
;; event's causal time — replayable — rather than an ambient host-clock
;; read at assembly time (per EP-0010 §Time / Spec 002 §Recordable
;; coeffects). nil outside a drain — the moot out-of-run destroy commits no
;; record, so the epoch surface's nil-tolerant fallback applies.
(def ^:dynamic *run-time-ms* nil)

(defn- record-teardown-failure!
  "Record a best-effort teardown-step failure on BOTH Spec 009
  observability channels, then return nil so the caller swallows the
  throw and teardown continues. Shared by `safe-call-hook!` (late-bound
  keyed cleanup hooks) and `safe-teardown-step!` (direct-call ordered
  steps) so every best-effort teardown step reports identically:

    1. ALWAYS-ON axis (EP-0008 R1) — conj `{:hook <step-key> :exception
       <ex> :where <where>}` onto the per-destroy `*teardown-hook-
       failures*` accumulator, which `destroy-frame!` flushes ONCE as the
       bounded `:rf.error/frame-teardown-failed` report.
    2. DIAGNOSTIC channel (EP-0008 R2) — emit the per-step
       `:rf.warning/teardown-hook-exception` trace (DCE'd in production)
       carrying the step key, the in-flight `*destroying-frame-id*`, and
       the exception, at its causal position.

  `step-key` labels the failing step (a hook key, or the recipe-step
  name for a direct call); `where` names the boundary that caught it."
  [step-key where ex]
  (when-let [acc *teardown-hook-failures*]
    (swap! acc conj {:hook      step-key
                     :exception ex
                     :where     where}))
  (trace/emit-error! :rf.warning/teardown-hook-exception
                     {:category  :rf.warning/teardown-hook-exception
                      :hook      step-key
                      :frame     *destroying-frame-id*
                      :exception ex
                      :where     where})
  nil)

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
           (record-teardown-failure! hook-key :safe-call-hook! ex)))))

(defn- safe-teardown-step!
  "Run an ordered teardown step that is a DIRECT call (not a late-bound
  hook) under the SAME best-effort boundary as `safe-call-hook!`: catch
  any throw, record it on both Spec 009 channels via
  `record-teardown-failure!`, and swallow it so NO teardown step escapes
  the recipe mid-flight.

  `safe-call-hook!` covers the optional late-bound cleanup hooks; this
  covers the ordered direct-call steps that would otherwise throw
  straight out of `destroy-frame!`'s `try` — notably
  `notify-machine-destruction!` (step 3), whose `teardown!` hook call,
  fallback `:rf.machine.lifecycle/destroyed` trace emits, and
  trace-listener fan-out are all reachable throw sites (rf2-jt47s0). An
  unguarded throw there left the frame LIVE + HALF-TORN-DOWN
  (`:destroyed?` never flipped, sub-cache intact, record not dissoc'd) —
  the `:on-destroy` (step 2) had already run, so a subsequent
  `destroy-frame!` saw the still-live record and RE-RAN the whole recipe,
  re-firing the user `:on-destroy`. Guarding the step keeps teardown
  best-effort end-to-end. `step-key` labels the step in both channels."
  [step-key thunk]
  (try (thunk)
       (catch #?(:clj Throwable :cljs :default) ex
         (record-teardown-failure! step-key :safe-teardown-step! ex))))

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
  branch (the private teardown cascade itself faulting): that path never
  produces a router
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
  `:rf.error/handler-exception` — the internal teardown cascade does not
  re-throw. To surface the throw as the dedicated `:rf.error/on-destroy-handler-
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
  the internal teardown cascade ever re-throws (e.g. a fault inside the dispatch
  infrastructure itself, not the user handler), we catch it here — and
  per EP-0008 the dedicated category rides the always-on axis so this
  defence-in-depth branch (which never produces a router
  `:rf.error/handler-exception`) is observable in production. The two
  paths are mutually exclusive (a router-converted handler throw never
  re-throws out of the cascade; an infra fault re-throws and never
  produces a router handler-exception record), and an `infra-fault?` guard
  makes the single-record contract explicit either way.

  This mirrors the swallow-then-continue shape of `safe-call-hook!` below
  but ALSO emits a structured error event (where `safe-call-hook!` is
  silent) — the user's `:on-destroy` is application code; its failure
  is a first-class diagnostic event."
  [id expected-token f]
  (when-let [on-destroy (-> f :config :on-destroy)]
    (when-let [run-destroy-event (late-bind/get-fn
                                   :router/run-frame-destroy-event!)]
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
            (run-destroy-event id expected-token on-destroy)
            (catch #?(:clj Throwable :cljs :default) ex
              ;; Defence-in-depth: the router normally swallows
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
          machines   (-> rt :rf.runtime/machines :snapshots)
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
  "CAS-flip `:destroyed?` only while `id` still names `expected-token`'s
  incarnation. The caller holds that incarnation's `:drain-lock`; the registry
  CAS is still required because unrelated frame ids share the `frames` atom.
  Returns true on the flip, false when the expected incarnation is no longer
  live."
  [id expected-token]
  (loop []
    (let [registry @frames
          f        (get registry id)]
      (if (and f
               (not (-> f :lifecycle :destroyed?))
               (identical? expected-token (:drain-lock f)))
        (if (compare-and-set! frames registry
                              (assoc-in registry [id :lifecycle :destroyed?] true))
          true
          (recur))
        false))))

(def ^:private ^:dynamic *destroy-claim-probe*
  "JVM concurrency-test seam fired after `destroy-frame!` captures the candidate
  incarnation and before it enters drain serialization. nil in production."
  nil)

(defn- claim-frame-destroy!
  "Claim teardown authority for `expected-token`'s live incarnation of `id`.

  The token check, shared per-id transaction reservation,
  `destroying-frames` publication, and pre-claim queue cutoff run under the
  candidate incarnation's drain lock. A foreign construction owner makes this
  the established prompt nil no-op; construction rollback on the current host
  thread joins its own owner. The queue cutoff is atomic on the router atom:
  every not-yet-dequeued envelope moves to the private
  `:destroy-claim-dropped-count` evidence slot and the live queue becomes empty
  before the lock releases. The later internal `:on-destroy` cascade therefore
  cannot drain ordinary work behind its cleanup seed.

  `call-serialized-with-drain!` may have captured A's frame record before
  blocking and wake after A was replaced by B, so the thunk revalidates the
  registry token after acquisition and retries against the current record. A
  stale expected token therefore never claims B while holding A's obsolete
  lock. An existing marker blocks only a duplicate claim for the SAME token; a
  fresh same-id incarnation replaces a stale prior marker. Returns the claimed
  frame record, or nil."
  [id expected-token]
  (loop []
    (when-let [candidate (frame id)]
      (let [candidate-token (:drain-lock candidate)]
        (when (identical? expected-token candidate-token)
          (when *destroy-claim-probe*
            (*destroy-claim-probe* id candidate-token))
          (let [claimed
                (call-serialized-with-drain!
                  id
                  (fn []
                    (when-let [current (frame id)]
                      (let [marker-token (:token (get @destroying-frames id))]
                        (cond
                          (not (identical? candidate-token (:drain-lock current)))
                          ::retry-destroy-claim

                          (and (some? marker-token)
                               (identical? candidate-token marker-token))
                          nil

                          :else
                          (when-let [transaction-owner
                                     (or (when (owner-holds-frame-id?
                                                 *frame-transaction-owner* id)
                                           *frame-transaction-owner*)
                                         (try-claim-frame-transaction!
                                           #{id} :destruction))]
                            (let [router (:router current)]
                            ;; Serialize claim publication + queue cutoff with
                            ;; the router's scheduling/release monitor. A
                            ;; submitter that linearized before this section is
                            ;; included in the dropped queue; ordinary drains
                            ;; after publication observe the claim and halt.
                            (locking router
                              (swap! destroying-frames assoc id
                                     {:token candidate-token
                                      :transaction-owner transaction-owner})
                               (swap! router
                                      (fn [{:keys [queue destroy-claim-dropped-count]
                                            :as state}]
                                        (let [dropped (+ (count queue)
                                                         (or destroy-claim-dropped-count 0))]
                                          ;; Preserve the claim-time count until
                                          ;; an actual drain observes the claim.
                                          ;; A public post-claim/pre-dead arrival
                                          ;; may enqueue, but that exact drain
                                          ;; drops it before invocation and
                                          ;; atomically combines both counts in
                                          ;; one interruption report.
                                          (cond-> (-> state
                                                      (assoc :queue interop/empty-queue
                                                             :scheduled? false)
                                                      (dissoc :destroy-claim-report-emitted?))
                                            (pos? dropped)
                                            (assoc :destroy-claim-dropped-count dropped)))))
                               (assoc current
                                      ::destroy-transaction-owner
                                      transaction-owner)))))))))]
            (if (= ::retry-destroy-claim claimed)
              (recur)
              claimed)))))))

(defn- release-frame-destroy-claim!
  "Compare-remove only `expected-token`'s claim. A stale incarnation A's
  terminal `finally` must not erase a fresh same-id B claim that replaced A's
  marker after A dissociated its frame record."
  [id expected-token]
  (swap! destroying-frames
         (fn [claims]
           (if (identical? expected-token (:token (get claims id)))
             (dissoc claims id)
             claims))))

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
  ;; dissoc. Also clears the frame's observation-port commit-epoch counter so
  ;; the side table stays bounded by live frames (a fresh same-id incarnation
  ;; restarts at 0 — the incarnation change is what the port's `current?` /
  ;; frame-identity checks detect, not the counter value).
  [id]
  (swap! frames dissoc id)
  (swap! frame-commit-epochs dissoc id))

(defn- notify-epoch-listeners!
  "Fire the epoch destroy hook, threading the two frame-state snapshots the
  `:halted-destroy` epoch record carries per Spec-Schemas §`:rf/epoch-record`
  §Outcomes. The canonical snapshot unit is the whole frame-state (both
  partitions); the epoch surface derives the `:db-before` / `:db-after` app-db
  projections from them.

    `fs-before` — the pre-run snapshot (frame-state before the in-flight
                  event's run began), recovered from the router-bound
                  `*run-frame-state-before*` dynamic var. nil outside a drain.
    `fs-after`  — the state at destroy-time: the live frame-state value read
                  immediately after the exact-incarnation claim and ordinary-
                  queue cutoff, before cleanup or lifecycle mutation. The partial run's
                  already-committed writes survive in this value; once
                  teardown runs the live container can no longer be read
                  (`frame-state-value` returns nil for a destroyed frame).

  Both snapshots are captured BEFORE the frame is removed and passed
  explicitly so the epoch surface (which fires AFTER `dissoc-frame!`,
  step 10) does not have to read a container that is already gone — reading it
  there would yield nil-`:db-before` / nil-`:db-after` records.

  `committed-at` is the destroying event's causal `:rf/time-ms`
  (the router-bound `*run-time-ms*`), threaded so the `:halted-destroy`
  record's `:committed-at` is replayable per EP-0010 §Time rather than an
  ambient host-clock read. nil outside a drain (the moot out-of-run
  destroy commits no record).

  `owner-token` is the destroyed frame's stable incarnation identity. The epoch
  artefact uses it to compare-clean only this incarnation's id-keyed state if a
  fresh same-id frame has already published between registry dissoc and step 11.

  `terminal-evidence` is the bundle `snapshot-epoch-terminal-evidence!` captured
  BEFORE dissoc (rf2-vxgfnd.151) — the epoch surface publishes A's terminal
  facts from it rather than re-reading the now-shared id-keyed stores.

  This is the ONLY teardown step that runs AFTER `dissoc-frame!`, so a same-id
  successor B may already own the registry key when it fires. A throw here is
  predecessor A's terminal diagnostic: it is recorded on both Spec 009 channels
  like every other teardown step, but the dev-only
  `:rf.warning/teardown-hook-exception` trace is delivered under STRUCTURAL
  delivery so a same-id B's `:rf.trace/frame-no-emit?` policy can neither
  suppress nor capture it (rf2-vxgfnd.152). Every PRE-dissoc teardown hook keeps
  ordinary bare-id policy — no same-id B can exist before dissoc, so their
  attribution is A's unambiguously."
  [id owner-token terminal-evidence]
  (when-let [f (late-bind/get-fn :epoch/on-frame-destroyed)]
    (try
      (f id owner-token terminal-evidence)
      (catch #?(:clj Throwable :cljs :default) ex
        (trace/call-with-structural-delivery
          #(record-teardown-failure! :epoch/on-frame-destroyed
                                     :safe-call-hook! ex))))))

(defn- snapshot-epoch-terminal-evidence!
  "rf2-vxgfnd.151 — snapshot the destroyed incarnation's terminal halted-destroy
  evidence (its `:halted-destroy` record + listener/silencing snapshot) while it
  is still the sole owner of its id-keyed epoch stores, i.e. BEFORE
  `dissoc-frame!`. A same-id successor can only be constructed after that dissoc,
  so it can never claim (and drop) the buffer / observation ledger out from under
  this snapshot. Returns the evidence bundle threaded to `notify-epoch-
  listeners!` (or nil when no epoch layer participates / the frame was not
  mid-drain). Guarded like the best-effort teardown steps: a throw here is
  recorded on both Spec 009 channels and swallowed so teardown still completes."
  [id fs-before fs-after committed-at]
  (when-let [snap (late-bind/get-fn :epoch/snapshot-frame-destroyed)]
    (try
      (snap id fs-before fs-after committed-at)
      (catch #?(:clj Throwable :cljs :default) ex
        (record-teardown-failure! :epoch/snapshot-frame-destroyed
                                  :safe-call-hook! ex)
        nil))))

(defn destroy-frame!
  "Tear down a frame. Per Spec 002 §Destroy, the ordered steps are:

    1. claim-frame-destroy!         — claim the exact incarnation under its
                                      drain serialization; atomically acquire
                                      the shared per-id transaction reservation,
                                      cut the ordinary queue, and publish the
                                      cutoff before lifecycle-dead. The
                                      reservation lasts through step 10, then is
                                      released for the fresh-incarnation
                                      post-dissoc window.
    2. fire-on-destroy-event!       — run user :on-destroy and its same-frame
                                      descendants on the destroy-owned private
                                      queue while the claimed frame is still
                                      available to cleanup code.
    3. notify-machine-destruction!  — per Spec 005 §Cross-Spec Interactions §1:
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
    4. :ui/on-frame-destroyed!      — detach the compiled-view
                                      (day8/re-frame2-ui) observers: a bounded
                                      victim set transitions to :dead (03 §4) —
                                      every currently-connected ViewCell whose
                                      retained subscription targets name this
                                      frame PLUS every still-
                                      disconnected but React-retained
                                      root-owned ViewCell whose last published
                                      site values name it (an Activity-hidden
                                      cell holds no live observers yet must
                                      still be reaped). The union is deduped +
                                      incarnation-scoped; each releases its
                                      handles against the still-live sub-cache,
                                      so a later read/probe follows the
                                      dead-cell lifecycle rather than throwing
                                      :rf.error/frame-destroyed. No-op when
                                      the artefact is absent (rf2-vxgfnd.42).
    5. mark-frame-destroyed!        — CAS-flip :lifecycle :destroyed? only
                                      while the claimed incarnation token still
                                      matches, under that incarnation's
                                      :drain-lock.
    6. reactive-state disposal      — dispose every cached reaction via the
                                      sub-cache-owned
                                      `:subs.cache/dispose-all-for-
                                      frame-destroy!` hook, so each
                                      eviction emits `:rf.sub/dispose`
                                      with `:rf.sub/reason
                                      :frame-destroy`; then dispose the app-db/
                                      runtime-db partition projections whose
                                      source watches the physical container.
    7. cleanup hooks (best-effort, no-op when artefact absent):
         :elision/clear-warning-cache!      — reset schema-first elision
                                              warning cache.
         :ssr/on-frame-destroyed            — clear SSR side-channel
                                              atoms for this frame.
         :machines/on-frame-destroyed!      — clear the machines
                                              artefact's frame-scoped
                                              `:after` timer table.
         :schemas/on-frame-destroyed!       — drop schemas registered
                                              against this frame.
         :flows/teardown-on-frame-destroy!  — release the destroyed
                                              frame's per-frame flow-store
                                              slot, its `last-inputs`
                                              rows, and its pending
                                              abandoned-output-path
                                              (vacation) state.
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
         :http/on-frame-destroyed!          — abort the frame's plain
                                              managed HTTP still in flight
                                              (live fetch/future + sleeping
                                              backoff handles), reply-
                                              suppressed.
         :fx/on-frame-destroyed!            — cancel + remove the frame's
                                              pending :dispatch-later timers.
    8. emit-frame-destroyed-trace!  — emit :frame/destroyed AFTER every
                                      feature cleanup hook has completed.
    8a. snapshot-epoch-terminal-    — bind A's terminal :halted-destroy
        evidence!                     evidence (its epoch record + the
                                      listener/silencing snapshot) via the
                                      :epoch/snapshot-frame-destroyed hook
                                      while A still SOLELY owns its id-keyed
                                      epoch stores — i.e. BEFORE the step-10
                                      dissoc, after which a same-id successor
                                      B can be constructed and claim (drop)
                                      those stores. The bundle is bound
                                      lexically here and published by step 11
                                      regardless of whether A still owns the
                                      stores when it fires (rf2-vxgfnd.151).
                                      Taken BEFORE the step-9 ring release so a
                                      snapshot-hook failure's ordinary-delivery
                                      warning rides A's ring and is cleared by
                                      that release instead of recreating an
                                      already-freed one (rf2-vxgfnd.244).
                                      Best-effort: a throw is recorded on both
                                      Spec 009 channels and swallowed; a nil
                                      bundle (no epoch layer / debug disabled)
                                      publishes nothing.
    9. trace-policy release         — clear the per-frame trace ring and the
                                      frame-no-emit flag after the destroyed
                                      trace has flowed.
    10. dissoc-frame!               — remove from the `frames` atom. This IS
                                      the whole forget: the `frames` registry
                                      is the ONE store a seated frame lives in
                                      (rf2-h1vqa4 — no registrar row exists).
    11. notify-epoch-listeners!     — fire the epoch hook so tools see
                                      :rf.epoch.cb/silenced-on-frame-destroy.
                                      This is the PUBLISH half of the epoch
                                      destroy contract: it ships the terminal
                                      evidence step 8a bound pre-dissoc,
                                      whether or not a same-id successor now
                                      owns the id-keyed stores. It threads the
                                      pre-run
                                      (`*run-frame-state-before*`) and
                                      destroy-time (live frame-state value
                                      captured at the TOP of this fn, before
                                      any teardown) frame-state snapshots so a
                                      mid-drain destroy's :halted-destroy
                                      epoch record carries real
                                      :frame-state-before / :frame-state-after
                                      (and their :db-* app-db projections) per
                                      Spec-Schemas §:rf/epoch-record §Outcomes.

  An external ordinary dispatch that linearizes while the claimed incarnation
  remains lifecycle-live may enter its real queue. The next exact-incarnation
  drain check removes it before handler, effects, or child dispatch and combines
  it with claim-time removals in the one interruption report. Only the exact-
  token private cleanup cascade may execute after claim. Dispatch / subscribe
  against the dead or absent frame recovers (dispatch no-ops, subscribe returns
  nil) and emits the always-on :rf.error/frame-destroyed diagnostic.

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
  record, dropped with it — one registry, no separate forget hook).

  The two-argument arity is incarnation-owned teardown: it is a silent no-op
  unless `expected-incarnation-token` is still the live frame's identity token.
  The check is revalidated after acquiring that incarnation's `:drain-lock`,
  and the liveness flip CAS-checks the same token under the same lifecycle gate,
  so a stale teardown can never destroy a fresh same-id incarnation.

  The one-argument arity chooses its authority from the TARGET (rf2-moftbs):
  a fresh-construction frame VALUE carries its EXACT incarnation token
  (`frame-value-incarnation-token`), so the arity delegates to the
  incarnation-owned two-argument arity with it — an owner (`with-new-frame`,
  Story replay, SSR per-request) that destroys the value it created tears down
  ONLY that incarnation, never a same-id successor reseated in between (a stale
  carried token no-ops). A frame-id KEYWORD, or a token-less derived-read VALUE,
  carries no authority and stays ADDRESS-directed: it pins whatever incarnation
  is currently live under the id at invocation and delegates. So keyword/ID
  destruction remains explicitly address-directed while a returned frame value
  gains coherent lifecycle-token semantics."
  ([target]
   ;; rf2-moftbs: a fresh-construction frame VALUE carries EXACT incarnation
   ;; authority (`:rf.frame/incarnation-token` — the installed `:drain-lock`).
   ;; Consume it so an owner's cleanup (`with-new-frame`, Story replay, SSR
   ;; per-request) destroys ONLY the incarnation it created; a stale carried
   ;; token no-ops through the two-argument arity (which revalidates liveness),
   ;; leaving a same-id successor reseated in between intact. A frame-id KEYWORD
   ;; or a token-less derived-read VALUE has no carried authority, so it stays
   ;; ADDRESS-directed — pin whatever incarnation is currently live under the id.
   (if-let [carried (frame-value-incarnation-token target)]
     (destroy-frame! target carried)
     (let [id (frame-target->id target)]
       (when-let [expected-token (frame-incarnation-token id)]
         (destroy-frame! target expected-token)))))
  ([target expected-incarnation-token]
  ;; Accept a frame VALUE or a frame-id keyword. Normalize a value to its id so
  ;; every keyed teardown step below targets the record; a keyword passes
  ;; through unchanged.
  (let [id (frame-target->id target)]
  ;; Re-entrancy guard: short-circuit if we're already destroying this id.
  ;; Silent no-op (idempotent destroy is a no-op pattern; no new trace event
  ;; needed).
  ;; The registry is keyed by the bare frame-id, so every keyed teardown step
  ;; (the in-flight guard, `mark-frame-destroyed!`, `dissoc-frame!`)
  ;; targets the frame-id-keyed record directly.
  (when-let [f (claim-frame-destroy! id expected-incarnation-token)]
      ;; `claim-frame-destroy!` acquires the shared per-id transaction and records
      ;; the DESTROYING incarnation's token (the live record's `:drain-lock`)
      ;; under that SAME lock, after revalidating the expected token.
      ;; `frame-incarnation-closing?` can therefore tell
      ;; this teardown apart from a fresh same-id replacement that goes live
      ;; under the reused id before this destroy's `finally` clears the marker
      ;; (rf2-vxgfnd.94). `contains?`/keys keep the bare-id semantics the
      ;; re-entrant guard + `frame-closing?` rely on.
      ;; Capture the DESTROY-TIME frame-state value AFTER the exact-incarnation
      ;; claim / ordinary-queue cutoff and BEFORE cleanup or lifecycle mutation.
      ;; After `mark-frame-destroyed!` (step 5) flips :destroyed?,
      ;; `frame-state-value` returns nil; after `dissoc-frame!` (step 10)
      ;; the container is gone entirely. Reading it here yields the state
      ;; the partial run left the frame in at the moment destroy was
      ;; requested — the `:frame-state-after` slot the `:halted-destroy`
      ;; epoch record carries. The pre-run `:frame-state-before` rides the
      ;; router-bound `*run-frame-state-before*` dynamic var (nil outside a
      ;; drain). Both are passed to `notify-epoch-listeners!` (step 11): the
      ;; whole frame-state, both partitions.
      (let [transaction-owner (::destroy-transaction-owner f)
            run-fs-before *run-frame-state-before*
            ;; The destroying event's causal `:time-ms`, bound by
            ;; the router alongside `*run-frame-state-before*`. Threaded to
            ;; the epoch hook so the `:halted-destroy` record's `:committed-at`
            ;; is replayable (per EP-0010 §Time). nil outside a drain.
            run-time-ms   *run-time-ms*
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
         ;; The exact-token destroy recipe is the sole framework-owned action
         ;; authorised after the claim. Replace the now-false authored-event
         ;; trace predicate for the whole recipe (including the post-dissoc
         ;; epoch hook and teardown-failure flush), then restore it on return.
         (trace/call-with-terminal-continuation-predicate
           ;; `claim-frame-destroy!` already granted this exact recipe local
           ;; terminal authority. After A's dissoc, an independent B destroy
           ;; may replace the bare-id marker with token B; that must not
           ;; retroactively cancel A's already-acquired terminal publications.
           ;; Every id-keyed teardown mutation keeps its own exact-token guard;
           ;; this predicate governs only continuation through this local
           ;; recipe and its corpus/structural observations.
           (constantly true)
           (fn []
         (try
        (fire-on-destroy-event! id expected-incarnation-token f)
        ;; Step 3 rides the SAME best-effort boundary as the later
        ;; `safe-call-hook!` steps (rf2-jt47s0). `notify-machine-destruction!`'s
        ;; `teardown!` hook call, its fallback `:rf.machine.lifecycle/destroyed`
        ;; trace emits, and the trace-listener fan-out are all reachable throw
        ;; sites (a non-machines `:machines/teardown-on-frame-destroy!` consumer,
        ;; or a throwing tap). Left unguarded, a throw here escaped the recipe
        ;; BEFORE the liveness flip / sub-cache teardown — the frame stayed LIVE +
        ;; half-torn-down with `:on-destroy` already run, so a retry re-ran the
        ;; whole recipe and re-fired `:on-destroy`. Accumulated into
        ;; `*teardown-hook-failures*` + surfaced on the diagnostic trace, then
        ;; teardown continues so the frame is fully torn down exactly once.
        (safe-teardown-step! :frame/notify-machine-destruction!
                             (fn [] (notify-machine-destruction! id)))
        ;; Detach the compiled-view (day8/re-frame2-ui) observers BEFORE the
        ;; liveness flip / sub-cache teardown. The hook sweeps a bounded victim
        ;; set to :dead (03 §4 dead-cell lifecycle): every currently-connected
        ;; ViewCell whose retained subscription targets name this frame
        ;; PLUS every still-disconnected but
        ;; React-retained root-owned ViewCell whose last published site values
        ;; name it (a hidden cell holds no live observers yet must still be
        ;; reaped) — deduped + incarnation-scoped. Each releases its handles
        ;; against the still-live sub-cache instead of being left live to throw
        ;; :rf.error/frame-destroyed off the observation port on its next read.
        ;; Symmetric with the machine cascade above (observers torn down against
        ;; a live container). No-op when the re-frame2-ui artefact is absent
        ;; (the hook is unbound) — rf2-vxgfnd.42.
        (safe-call-hook! :ui/on-frame-destroyed! id)
        ;; Flip the liveness bit under the frame's OWN `:drain-lock` (the ONE
        ;; frame-owned lifecycle gate, via the same `call-serialized-with-drain!`
        ;; the flows lifecycle ops use) so a concurrent cold `reg-flow` / flow
        ;; lifecycle op linearizes against this destroy. That op pins the
        ;; incarnation token (`frame-incarnation-token`) and REVALIDATES it under
        ;; the same lock inside its serialized mutation, so it either published
        ;; its registry row BEFORE this flip — and the `:flows/teardown-on-frame-
        ;; destroy!` hook below (which runs AFTER this flip) removes it — or it
        ;; observes the destroyed incarnation AFTER the flip and refuses,
        ;; leaving NO ghost row on the dead frame. Because the flows teardown
        ;; always runs before `dissoc-frame!` (and a fresh same-id incarnation
        ;; can only exist after that dissoc), the teardown can never delete a
        ;; newer registration either. The frame is still LIVE at this point (the
        ;; flip has not yet run), so the id-keyed helper resolves + locks the
        ;; correct incarnation. Reentrant on ANY same-thread hold: a mid-drain
        ;; destroy runs on the drainer thread, and a destroy issued from inside
        ;; a cold serialized op (e.g. a Tool-Pair write body) runs under that
        ;; op's `:serialized-holder` — either way the flip runs directly rather
        ;; than self-deadlocking, and the `frame-disposed-for-drain?` interrupt
        ;; seam is unchanged.
        (when (call-serialized-with-drain!
                id
                (fn []
                  (mark-frame-destroyed! id expected-incarnation-token)))
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
        ;; from a prior construction cycle persist and re-fire under the
        ;; rollback contract — manifesting as spurious rollbacks against
        ;; paths the new frame's :initial-events never wrote. No-op when
        ;; re-frame.schemas is absent (the artefact is optional).
        (safe-call-hook! :schemas/on-frame-destroyed! id)
        ;; Drop every flow registered against the destroyed
        ;; frame plus its cached `last-inputs` rows and its pending
        ;; abandoned-output-path (vacation) state. There is NO `:flow`
        ;; registrar slot to prune: per rf2-en00bk the `:flow` registrar
        ;; kind is RESERVED with an intentionally empty slot, and
        ;; `reg-flow` writes only to the flows artefact's own per-frame
        ;; `{frame-id {flow-id flow-map}}` store — the single source of
        ;; truth. Releasing this frame's key from that store (plus the two
        ;; sibling frame-keyed caches) IS the whole teardown.
        ;; Symmetric with the machines teardown hook above.
        ;; Without this hook a long-running SSR JVM with
        ;; per-request frame churn grows the flow registry unboundedly.
        ;; This hook does NOT scrub the frame's flow-output elision marks:
        ;; those live in the runtime-db partition INSIDE the
        ;; `:frame-state` container, which `dissoc-frame!` (step 10 below)
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
        ;; Abort the destroyed frame's still-in-flight PLAIN managed HTTP
        ;; (rf2-j538f7.8) — ordinary event-handler `:rf.http/managed` work with
        ;; no actor id, the exposed path that actor/resource teardown above does
        ;; NOT catch. Each frame-stamped live fetch/future + sleeping-backoff
        ;; handle is cancelled with the reply-suppressing `:reason
        ;; :frame-destroyed` (no delivery into the now-destroyed frame), its
        ;; external `:abort-signal` listener detaches, and both registry indexes
        ;; clear. Ordered AFTER machines/resources so their more specific
        ;; `:actor-destroyed` / ledger teardown wins first and this generic sweep
        ;; no-ops on already-cleared handles. No-op when re-frame.http is absent.
        (safe-call-hook! :http/on-frame-destroyed! id)
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
        ;; Invalidate the Freehand root-ownership ledger. The interpreted mount
        ;; surface (re-frame.freehand.root) keeps a frame-id-keyed ownership row
        ;; the same shape every sibling side table above keeps, but it is
        ;; incarnation-scoped: a row owns the frame only while its recorded handle
        ;; token is the live incarnation's. This hook carries the DYING
        ;; incarnation's token (`expected-incarnation-token`) so the ledger can
        ;; compare-clean ONLY the row whose handle names it — tombstone that row
        ;; and warn if live roots still reference it — leaving a same-id successor
        ;; row untouched, exactly the epoch layer's compare-clean discipline
        ;; (step 11). No-op when the day8/re-frame2 freehand artefact is absent
        ;; (the hook is unbound). The frame is still keyed here (dissoc is step 10
        ;; below), and the frame value is already marked :destroyed?, so the
        ;; carried token is the only way to name the dying incarnation.
        (safe-call-hook! :freehand/on-frame-destroyed! id expected-incarnation-token)
        ;; The shipped subsystems tear down via the named ordered hooks above.
        (emit-frame-destroyed-trace! id)
        ;; EP-0024: there is ONE `frames` registry, and `dissoc-frame!` below IS
        ;; the forget. The frame's resolved generation rides the record's
        ;; `:generation` slot, so dropping the record drops it too — no separate
        ;; forget hook is needed.
        ;; rf2-vxgfnd.151: snapshot A's terminal halted-destroy evidence while A
        ;; is STILL the sole owner of its id-keyed epoch stores — before
        ;; dissoc-frame!, after which a same-id successor B can be constructed
        ;; and claim (dropping) those stores. The bundle is bound lexically to
        ;; A's exact destroy recipe and published by the post-dissoc epoch hook
        ;; regardless of whether A still owns the stores when it fires.
        ;; rf2-vxgfnd.244: the snapshot is taken BEFORE the ring release below.
        ;; The snapshot itself emits no trace on the happy path, but a snapshot-
        ;; hook FAILURE emits `:rf.warning/teardown-hook-exception` under
        ;; ordinary (non-structural) delivery — carrying A's bare frame id — so
        ;; it would push onto A's ring. Ordering it before `release-frame-ring!`
        ;; means A's normal ring release clears that push instead of the warning
        ;; recreating an already-released ring. No same-id B can exist yet (this
        ;; is still pre-dissoc), so ordinary delivery is correct here.
        (let [terminal-evidence (snapshot-epoch-terminal-evidence!
                                  id run-fs-before fs-at-destroy run-time-ms)]
          ;; Per Spec 009 §Per-frame trace rings:
          ;; release the destroyed frame's event-keyed ring so no
          ;; residual trace events leak across the frame lifecycle. Fired
          ;; AFTER `:rf.frame/destroyed` emits so the destroyed trace
          ;; itself (which is frameless and bypasses the ring anyway)
          ;; still flows through the live stream cleanly. Routed via
          ;; late-bind so production CLJS bundles (no trace.tooling) no-op.
          ;; Serialize teardown's two clears with successful upsert publication.
          ;; The frame is already lifecycle-dead, so a delayed publisher that
          ;; enters afterwards fails its live-token predicate; a publisher that
          ;; entered before the flip completes first and these clears win last.
          ;; No interleaving can repopulate either auxiliary store after cleanup.
          (serialize-frame-trace-policy!
           (fn []
             (safe-call-hook! :trace.tooling/release-frame-ring! id)
             ;; rf2-zcl055: release the destroyed frame's trace-emission gate
             ;; flag — the teardown counterpart to construction's
             ;; `trace/set-frame-no-emit!`. A tool / inspector frame registered
             ;; with `:rf.trace/frame-no-emit? true` (e.g. `:rf/xray`) otherwise
             ;; leaves a permanent entry in trace.cljc's process-global
             ;; `trace-disabled-frames` set (the ring IS freed above; the flag
             ;; was not — a teardown asymmetry). Called directly (not via a
             ;; tooling hook) because `trace.cljc` is always loaded — the set +
             ;; predicate live on the core trace surface, same as construction.
             ;; Idempotent no-op for application frames (the common case).
             (trace/clear-frame-no-emit-for! id)))
          (dissoc-frame! id)
          ;; A PUBLIC destroy owns a :destruction reservation only through exact
          ;; registry removal. Release here (the recipe's finally is a harmless
          ;; compare-release fallback) so the established post-dissoc window may
          ;; seat a fresh same-id incarnation before stale teardown diagnostics
          ;; finish. A construction rollback JOINED its outer :construction owner
          ;; and must leave that reservation for the constructor's own finally.
          (when (= :destruction (:kind transaction-owner))
            (release-frame-construction! transaction-owner))
          (notify-epoch-listeners! id expected-incarnation-token
                                   terminal-evidence))
        nil)
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
                  ;; A was dissociated before this finally boundary. Preserve
                  ;; the corpus-wide terminal report, but do not resolve its
                  ;; bare id through a same-id B's frame-owned error sinks.
                  (emit-report id failures (interop/now-ms) false)
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))
          ;; Compare-remove only this incarnation's in-flight marker. A fresh
          ;; same-id incarnation may have replaced it after `dissoc-frame!`;
          ;; stale A's finally must never erase B's claim.
          (when (= :destruction (:kind transaction-owner))
            (release-frame-construction! transaction-owner))
          (release-frame-destroy-claim! id expected-incarnation-token)))))))))))

;; ---- reset-frame! — RETIRED (rf2-lxwpob) -----------------------------------
;;
;; `reset-frame!` (destroy-frame! + make-frame with the same config, full
;; replace) was RETIRED in rf2-lxwpob (API-shrink #5, frame-lifecycle
;; collapse): one axis (create/refresh via `make-frame`, destroy
;; via `destroy-frame!`), not three verbs. A full replace is reproducible by
;; composition — `(destroy-frame! id) (make-frame config)`, re-supplying the
;; SAME config (which carries `:id`, and `:images` for an image-loaded frame,
;; so the recreated frame keeps its resolved generation). There
;; is no internal-only survivor: the mid-cascade atomicity guard this verb
;; offered (reject BEFORE any teardown) has no equivalent in the two-call
;; composition and is accepted as a retired guarantee — frame
;; construction/destruction is already a top-level/view-only operation
;; (EP-0027), so the composition's non-atomicity only bites a call site that
;; was already violating that rule.
;;
;; The name survives ONLY as a `^:no-doc` throwing stub (the project's
;; actionable-removed-API pattern, like the EP-0018 `reg-event-db` / EP-0022
;; `rf/path` stubs): a stale `(rf/reset-frame! …)` call site resolves to a
;; real var and fails LOUDLY with `:rf.error/reset-frame-removed`, naming the
;; two-call composition as the replacement. `^:no-doc` drops it from the API
;; manifest generator + the CLJS publics probe. Plain `defn` (not
;; macro-generated) so it compiles identically on JVM and CLJS. Aliased onto
;; the `re-frame.core` facade in core.cljc.

(defn ^:no-doc reset-frame!
  "REMOVED in rf2-lxwpob (no alias). The dedicated reset verb is retired — a
  full replace is `(destroy-frame! id) (make-frame config)`, re-supplying
  the SAME config (which carries `:id`, and `:images` for an image-loaded
  frame) the caller already holds. Calling `reset-frame!` is the hard error
  `:rf.error/reset-frame-removed`, naming that composition as the
  replacement. See spec/002-Frames.md §Resetting a frame — destroy + make-frame
  and spec/API.md §Frame lifecycle."
  [& args]
  (error/throw-error!
    :rf.error/reset-frame-removed
    'rf/reset-frame!
    (str "`reset-frame!` is REMOVED (no alias, rf2-lxwpob) — a full frame "
         "reset is reproducible by composition: `(destroy-frame! id) "
         "(make-frame config)`, re-supplying the SAME config (which "
         "carries `:id`, and `:images` for an image-loaded frame) you "
         "already hold. See spec/002-Frames.md §Resetting a frame — "
         "destroy + make-frame.")
    {:recovery :destroy-frame-then-make-frame-with-same-config
     :extra    {:got args}}))

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
  app frame registers it explicitly via `(rf/make-frame {:id :rf/default …})`."
  []
  (when-not (frame :rf/default)
    (upsert-frame! :rf/default {:doc "Test-fixture default frame (ordinary id; not a runtime floor)."})))
