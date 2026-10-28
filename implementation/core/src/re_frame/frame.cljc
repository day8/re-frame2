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
    :rf/default              — the CONVENTIONAL app-frame id (per Spec 002
                              §`:rf/default` is the conventional app-frame id).
                              It carries NO framework privilege: the runtime
                              never creates it, never infers it from a missing
                              stamp, and never uses it as a resolution floor.
                              A single-frame app, example, or test registers
                              and selects it EXPLICITLY like any other id.
    :rf.frame/<gensym>       — anonymous instances from make-anon-frame-record!"
  (:require [clojure.string]
            [re-frame.error :as rf.error]
            [re-frame.features :as rf.features]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace]))

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
;; `re-frame.live-frame/make-frame` returns a SINGLE runnable frame OBJECT — a
;; map carrying a reference
;; (`:rf.frame/runnable-id`) to the backing runnable RECORD this ns owns in
;; `frames`. The object IS the public live frame; its runnable interior
;; (app-db / runtime-db / queue / sub-cache / lifecycle) is the record reached
;; by the runnable-id (EP-0023 §Frame — "the live frame object owns app-db,
;; runtime-db, event queue and drain state, subscription cache, ... a reference
;; to the resolved image generation it is running").
;;
;; The PUBLIC target a dispatch / subscribe / destroy / app-db read / provider
;; addresses is a frame — a frame id KEYWORD OR a frame VALUE the lifecycle
;; APIs return, ACCEPTED DIRECTLY EVERYWHERE (the API commits to ONE
;; frame-target grammar; EP-0024 Operation target grammar).
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
  `make-frame` call that produced the value installed. Present ONLY
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
  "INTERNAL normalization primitive (there is no public
  `rf/frame-value->id` facade accessor — every public surface accepts
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
  built without a construction token. A non-nil result is the
  opaque teardown AUTHORITY the one-argument `destroy-frame!` consumes so an
  owner's cleanup destroys EXACTLY the incarnation it created; nil selects the
  ADDRESS-directed path (destroy whatever incarnation is currently live under
  the id). Pure."
  [x]
  (when (frame-value? x)
    (get x incarnation-token-key)))

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
;; destruction: each holds its one id's reservation from its claim until it has
;; finished. Values are opaque owner maps compared ONLY by identity; their data
;; fields are diagnostics, never authority.
;;
;; There is deliberately no queue or generation counter. A conflicting claim
;; fails promptly on both hosts, avoiding callback-waits-for-contender deadlocks
;; and keeping CLJ/CLJS semantics identical. Incarnation/config generations
;; remain owned by :drain-lock / :trace-policy-token on the frame record.

(defonce ^:private frame-construction-reservations
  (atom {}))

(def ^:dynamic ^:private *frame-transaction-owner* nil)

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
   (rf.error/throw-error!
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

(defn- new-frame-transaction-owner [id kind]
  {:kind kind :frame-id id :holder (current-host-holder)})

(defn- try-claim-frame-transaction!
  "Try the same CAS as `claim-frame-construction!`.

  Returns an owner token, or nil on contention. Destruction uses this
  non-throwing form only after it has revalidated an exact live incarnation:
  duplicate/concurrent destroy is a prompt nil no-op, while
  public construction uses the typed throwing form below."
  [id kind]
  (let [owner (new-frame-transaction-owner id kind)]
    (loop []
      (let [registry @frame-construction-reservations]
        (when-not (contains? registry id)
          (if (compare-and-set! frame-construction-reservations
                                registry (assoc registry id owner))
            owner
            (recur)))))))

(defn ^:no-doc claim-frame-construction!
  "Atomically reserve frame `id` for one exact owner.

  Returns the opaque identity-compared owner token. If `id` is already owned,
  throws `:rf.error/frame-construction-in-progress`. INTERNAL — not part of the
  public frame API."
  [id kind]
  (let [owner (new-frame-transaction-owner id kind)]
    (loop []
      (let [registry @frame-construction-reservations]
        (if-let [held (get registry id)]
          (throw-frame-construction-in-progress! id held)
          (if (compare-and-set! frame-construction-reservations
                                registry (assoc registry id owner))
            owner
            (recur)))))))

(defn ^:no-doc release-frame-construction!
  "Release `owner`'s reservation if it still holds it, so a repeated release is
  a no-op. INTERNAL companion to `claim-frame-construction!`."
  [owner]
  (let [id (:frame-id owner)]
    (swap! frame-construction-reservations
           (fn [registry]
             (if (identical? owner (get registry id))
               (dissoc registry id)
               registry))))
  nil)

(defn- call-with-frame-transaction!
  "Invoke `(f owner)` holding a fresh exact reservation for `id`, bound as the
  current transaction owner, and release it however `f` exits. The engine
  claims for itself, so a same-id attempt from anywhere — a re-entrant one from
  inside `f` included — is a second claim and loses promptly with
  `:rf.error/frame-construction-in-progress`."
  [id kind f]
  (let [owner (claim-frame-construction! id kind)]
    (binding [*frame-transaction-owner* owner]
      (try
        (f owner)
        (finally
          (release-frame-construction! owner))))))

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
;; token (its `:drain-lock`, captured while still live), the reservation owner,
;; and whether the destroy is still PENDING:
;; `{id {:token drain-lock :transaction-owner owner :pending? bool}}`. The bare
;; id is what the re-entrancy guard above consults — "is a destroy for this id
;; in flight at all"; the token is what `frame-incarnation-closing?` and the
;; drain's boundary check compare, so a caller holding some other incarnation's
;; token never joins this teardown. The destroy holds the id's reservation until
;; its teardown has finished and removes its marker before releasing it, so no
;; same-id incarnation is constructed while a marker stands.
;;
;; A destroy requested while the target incarnation's drain is on the current
;; stack is PENDING (Spec 002 §Destroy): the marker and the reservation are
;; taken at once, the event in flight finishes as an ordinary event, and the
;; drain runs the teardown at the next event boundary (`run-pending-destroy!`),
;; flipping `:pending?` off as teardown begins.

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
;; `:rf.error/handler-exception` (the `[:rf/set-db x]` bad-arg case),
;; or any other `:rf.error/*` recorded against THIS frame (a
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

;; Beside it rides the component that was rendering when that binding was
;; taken. A reader that puts the component tree first while a Reagent
;; component renders (`re-frame.views/current-frame`) needs it to tell the two
;; bindings a render can see apart: one taken AROUND the render — a
;; `with-frame` enclosing a synchronous flush — which the tree overrides, and
;; one the render's OWN code took — in its body, an `:epoch` listener or trace
;; callback it notifies, a handler it runs synchronously — which is the
;; nearest, most explicit statement of the frame and decides. The binding
;; records the render rather than the render recording the binding because a
;; plain Reagent fn offers no render-start seam, while every framework binder
;; of `*current-frame*` is one of four forms: `with-frame`, `with-new-frame`,
;; `bind-fn` and the router's per-handler binding.

(def ^:dynamic ^:no-doc *current-frame-bound-in*
  "The component that was rendering when the in-effect `*current-frame*`
  binding was taken, or nil when it was taken outside every render. Bound
  beside `*current-frame*` by every framework binder; read through
  [[bound-inside-render?]]."
  nil)

(defn ^:no-doc rendering-component
  "The component a substrate is rendering on this stack, or nil. Read through
  the `:adapter/current-component` hook, which only Reagent publishes."
  []
  #?(:cljs (when-let [hook (rf.late-bind/get-fn :adapter/current-component)]
             (hook))
     :clj  nil))

(defn ^:no-doc bound-inside-render?
  "True when the in-effect `*current-frame*` binding was taken while
  component `cmp` was rendering, by code that render reached. Such a binding
  decides over the tree. A binding taken around the render, or during a
  different component's render (one a handler or a listener started), does
  not: that component reads its own tree."
  [cmp]
  (identical? *current-frame-bound-in* cmp))

;; ---- The REFUSAL tier — "no ambient frame is legal here" -----------------
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
;; needs exactly that missing sentence. Fresco is the motivating case
;; (EP-0038 HD-002 clause (a)): inside a boundary body, every read must go
;; through the boundary's collector so it becomes an edge the boundary
;; re-renders on. An ambient `rf/subscribe` written in a body RESOLVES —
;; the Fresco frame is genuinely in scope through tier 2 — and then
;; mutates the sub-cache during render, contributes ZERO collector edges,
;; and leaves a boundary that never re-renders when that subscription
;; moves. Without a refusal tier that is a silent correctness failure, fenced
;; only by which adapter the host happened to install.
;;
;; So: a THIRD tier, at the ONE funnel every adapter already routes through
;; (`resolve-current-frame`). It is a tier and not a reshape — the readers
;; and the hook are the same ones every other extent uses, no adapter
;; publishes anything extra, and the ordinary path pays one nil-test on a
;; var read.
;;
;; WHAT THE REFUSAL REFUSES, precisely: the AMBIENT (React-context) tier
;; only. An explicitly CARRIED stamp still carries — `{:frame …}` opts never
;; reach this resolver at all, and `with-frame` / `bind-fn` / the router's
;; per-handler binding still answer through `*current-frame*`. That keeps
;; the two spellings of "I carried a frame" behaving identically inside a
;; refused extent, per EP-0002 ("frame identity is carried, not found") —
;; the refusal deletes the FINDING, never the carrying.
;;
;; WITH ONE EXCEPTION, WHICH IS OPT-IN AND SUBSTRATE-DECLARED.
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
;;
;; AND THE DECLARED FRAME IS ANSWERED TO THE PURE DOORS. A
;; refusing extent may still expose its declared frame to the identity and
;; capture doors; stateful ambient operations remain refused. The fence
;; exists to keep reads on the substrate's collector and dispatches
;; frame-locked, and `current-frame-id` (identity only) and `capture-frame`'s
;; 0-arity (a frame-locked api, no subscription or dispatch at capture time)
;; do neither — no edge, no mutation — so refusing them would protect nothing
;; and force a substrate to mint a frame verb of its own. One semantic
;; distinction in core, keyed on the DECLARATION and checked AFTER the
;; mismatch, not a configurable admit-set.

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

  `:extent-frame` — THE FRAME THIS EXTENT IS RENDERING. The one
  key core READS rather than passes through, and the only one that changes
  what the tier does — in two ways. A substrate that names it declares \"a
  body of mine has ONE frame\", and [[require-current-frame!]] then refuses
  a carried stamp that names a DIFFERENT one — because a body whose ambient
  ops target `:b` while its own reads, lowered intents and children target
  `:a` is two frames in one body, and frames are ISOLATED contexts. And it
  ANSWERS that frame to the pure doors: `:current-frame-id` and
  `:capture-frame` resolve to it when nothing is carried, because an
  identity read and a capture make no edge and no mutation. Omit it (or
  leave it nil) and the tier refuses the ambient FIND for every operation
  and any carried stamp wins, which is
  right for an extent that has no frame of its own to be mismatched against
  or to offer."
  nil)

(def ^:private pure-frame-doors
  "The two operations a refusing extent still answers with its DECLARED
  frame (Spec 002 §The refusal tier). `:current-frame-id` only
  reports identity, and `:capture-frame` — the 0-arity — captures a
  frame-locked api and performs no subscription or dispatch at capture
  time, so neither makes the edge-less read or the render-phase mutation
  the fence exists to prevent. A closed pair by design: this is one
  semantic distinction in core, not a policy language for a substrate to
  extend."
  #{:current-frame-id :capture-frame})

(defn call-with-ambient-frame-refused
  "**SUBSTRATE seam.** Run `thunk` with ambient frame resolution REFUSED for
  its dynamic extent, so a frame-scoped operation that would have FOUND a
  frame through the React-context tier raises `:rf.error/ambient-frame-refused`
  instead of silently succeeding. Returns the thunk's value.

  This is how a substrate says that no ambient frame is legal here. A
  substrate whose render extent imposes its own read discipline — a compiled-view
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
  the one the extent is rendering. Naming it also ADMITS the
  pure doors: `current-frame-id` and 0-arity `capture-frame` answer the
  declared frame inside the extent, while `subscribe`,
  `dispatch` and every other stateful ambient operation stay refused.
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
;; Normalized to the frame ID: `with-frame` / `with-new-frame`
;; may bind a frame VALUE (`make-frame`'s token) into `*current-frame*`, and
;; ring attribution must key on the record id, never a value map.
(rf.late-bind/set-fn! :frame/current-frame-id (fn [] (frame-value->id *current-frame*)))

(defn- carried-frame-admitted-by
  "The carried (tier-1) stamp, normalized to an id, when `refusal` — the
  in-effect refusal detail — ADMITS it; **nil** otherwise.

  A refusal that names no `:extent-frame` admits every carried stamp, which
  is the right answer for an extent with no frame of its own. One that names one admits only that frame: a body whose
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
  invariant, EP-0002). The two scope tiers it observes, in the order the
  installed adapter's `:current-frame` impl consults them (Reagent's puts
  the tree first while a component renders; Spec 006 §One
  frame-resolution rule for React hooks):

    - `*current-frame*` (dynamic var) — set by `with-frame` / `bind-fn`
      (INTERNAL) / the router's per-handler binding.
    - The closest enclosing frame boundary — a `frame-provider` (SCOPE)
      or `frame-root` (ENSURE) — via React context (CLJS). Both install
      the shared boundary context; the no-provider sentinel appears only
      beneath neither.

  On CLJS this consults the `:adapter/current-frame` late-bind slot so
  the React-context tier is LIVE — a React-shaped adapter carries its
  React-context-aware impl as the `:current-frame` key of its adapter map,
  and `install-adapter!` publishes it into that slot. That impl returns nil when
  neither the dynamic var nor an enclosing frame boundary (`frame-provider`
  or `frame-root`) names a frame (the React-context default is the
  no-provider sentinel, per Spec 002 §`:rf/default`
  is the conventional app-frame id). When the slot is empty (no adapter
  installed yet, an installed map without the key, or a JVM build)
  the result is `current-frame` — the dynamic-var tier alone; the React-
  context tier silently no-ops to nil.

  This is the canonical scope reader — `subs/subscribe`,
  `router/dispatch!`'s frame computation, and `core/current-frame-id`
  delegate here so the React-context tier is single-sourced.
  Public frame-scoped operations that must have a frame call
  `require-current-frame!`, which is built on this reader.

  A THIRD TIER GATES THE SECOND. While a substrate has
  established a refusing extent ([[call-with-ambient-frame-refused]]), tier
  2 is WITHDRAWN and this reader answers from tier 1 alone — so a frame
  that is genuinely in scope is not *found* here, and
  `require-current-frame!` turns that into the loud
  `:rf.error/ambient-frame-refused`. Being a reader, it still returns nil
  rather than throwing: tooling and frame pickers running inside such an
  extent read 'no ambient frame', which is the truth.

  AND TIER 1 ANSWERS ONLY FOR THE EXTENT'S OWN FRAME. When the
  refusal names an `:extent-frame`, a carried stamp naming some OTHER frame
  is not an ambient answer this extent will accept, and this reader says so
  by answering nil — see [[carried-frame-admitted-by]]. THE CHECK BELONGS
  HERE AND NOT ONLY IN `require-current-frame!`, which is not the single
  funnel the catalogue describes it as: `subs/subscribe`'s 1-arity — the
  framework's per-read path, and the very op HD-002 clause (a) is about —
  inlines `(or (resolve-current-frame) (require-current-frame! …))` to keep
  its error payload off the fast path, so a check living only in
  the requiring primitive would miss every ambient subscribe. Putting
  it in the reader makes every reader-first caller correct by construction
  and leaves that optimisation intact.

  THE PURE-DOOR ADMISSION IS NOT HERE. A reader never repairs
  absence, and inside a refusing extent the honest answer is still that no
  ambient frame was FOUND — so this reader keeps answering nil, and
  `require-current-frame!` alone answers the extent's declared frame to
  `current-frame-id` and `capture-frame`. Moving the admission into this
  reader would hand the declared frame to every reader-first caller,
  `subs/subscribe` included, which is the op the refusal exists for."
  []
  ;; Sticky hook — `:adapter/current-frame` holds the installed adapter
  ;; map's `:current-frame` key, published by `install-adapter!`; it fires
  ;; on every ambient resolution (every ambient dispatch and every ambient
  ;; subscribe).
  ;;
  ;; NORMALIZED to the frame ID: `with-frame` / `with-new-frame`
  ;; may bind a frame VALUE (`make-frame`'s return token) into
  ;; `*current-frame*`. Every consumer of the ambient scope keys records /
  ;; rings / registries by the frame ID, so the reader yields the id — a
  ;; keyword target passes through unchanged (`frame-value->id` is identity
  ;; on non-values), and a value normalizes to its runnable id.
  ;;
  ;; THE REFUSAL TIER is one nil-test on a var read, and it
  ;; is the whole of what the ordinary path pays. When no extent has refused
  ;; — every extent of every adapter — the expression evaluated is
  ;; the plain two-tier read below. When an extent HAS refused,
  ;; resolution collapses to the CARRIED tier alone, which is exactly the
  ;; `:clj` branch: the React-context tier is withdrawn, an explicit
  ;; `with-frame` still answers — for the extent's OWN frame.
  (if-some [refusal *ambient-frame-refusal*]
    (carried-frame-admitted-by refusal)
    (frame-value->id
      #?(:cljs (if-let [f (rf.late-bind/get-fn :adapter/current-frame)]
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
;; correlation graph (read off the in-scope `rf.trace/*handler-scope*`, whose
;; slots carry no parent — per Spec 009 §Dispatch correlation,
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

  `:reason` is always composed, and names BOTH halves of the repair.
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
  is `:recovery :supply-frame` — per Spec 009, nothing branches
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
          (when-let [did (some-> rf.trace/*handler-scope* :dispatch-id)]
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
  `resolve-current-frame` and never reach here.

  THE RECORD CARRIES THE PAYLOAD'S OWN `:reason`. This category
  passes no `:exception` — the operation is invalid, nothing threw — so
  without it the always-on record would be seven slots of pure metadata
  (`{:rf.error/id :event :event-id :frame :time :exception :elapsed-ms}`) and the
  composed recovery ladder `no-frame-context-payload` just built would reach
  the always-on axis NOWHERE: a console line would show the record's
  interior fields with no ladder inside, and no console formatter could
  recover it. `:reason` / `:recovery` ride the `attrs`
  attribution seam `dispatch-on-error!` provides for
  category-specific slots — the same seam flow-eval uses — and match the
  non-event union records (the frame-teardown report carries both). An
  off-box shipper gets the sentence too."
  [payload]
  (let [event-id (:event-id payload)]
    ;; Always-on listener registry (survives prod elision).
    ;; no-frame-context is an invalid operation — and we have no frame
    ;; anyway.
    (when-let [dispatch-on-error! (rf.late-bind/get-fn :error-emit/dispatch-on-error)]
      (dispatch-on-error!
        :rf.error/no-frame-context
        nil                              ;; no event vector — absence, not a throw on a dispatch
        event-id
        nil                              ;; no frame — that is the whole point
        nil                              ;; no exception — invalid op, not a throw
        0                                ;; elapsed-ms
        (rf.interop/now-ms)                 ;; time
        ;; The composed ladder + machine-readable repair the payload already
        ;; holds; nil-valued slots are dropped by the attribution merge.
        {:reason   (:reason payload)
         :recovery (:recovery payload)}))
    ;; Dev-only trace path — DCEs under `:advanced` + `goog.DEBUG=false`.
    (rf.trace/emit-error! :rf.error/no-frame-context payload)
    payload))

;; ---- :rf.error/ambient-frame-refused — a frame was found but is not ------
;; ---- reachable ambiently here ---------------------------------------------
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
;; a Fresco body always sits under a frame boundary. Following the generic
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

  TWO REFUSALS, ONE ID, TWO SENTENCES. `extra`'s
  `:carried-frame` — set by [[require-current-frame!]] and by nothing else —
  says which refusal this is. Absent: nothing was carried, the ambient FIND
  was refused, and the prose is the generic refusal sentence. Present:
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
            (when-let [did (some-> rf.trace/*handler-scope* :dispatch-id)]
              {:rf.trace/dispatch-id did})
            (dissoc refusal :reason :extent-frame)
            (when (some? extent-frame) {:extent-frame extent-frame})
            extra))))

(defn emit-ambient-frame-refused!
  "Surface `:rf.error/ambient-frame-refused` through the always-on error axis
  AND the dev-only trace surface, then return the payload — the same ladder
  `emit-no-frame-context!` rides, because the failure it prevents (a
  boundary that silently stops re-rendering) is exactly as invisible in
  production as a frameless op is.

  This is the EMISSION half; `require-current-frame!` emits then throws."
  [payload]
  (when-let [dispatch-on-error! (rf.late-bind/get-fn :error-emit/dispatch-on-error)]
    (dispatch-on-error!
      :rf.error/ambient-frame-refused
      nil                                ;; no event vector — a refused resolution, not a throw on a dispatch
      (:event-id payload)
      nil                                ;; no frame — the whole point is that none is legal here
      nil                                ;; no exception — an illegal operation, not a throw
      0                                  ;; elapsed-ms
      (rf.interop/now-ms)))                 ;; time
  (rf.trace/emit-error! :rf.error/ambient-frame-refused payload)
  payload)

;; ---- :rf.error/bad-frame-provider-arg — a bad explicit target -------------
;;
;; Distinct from `:rf.error/no-frame-context`. A public
;; `frame-provider` whose `:frame` is non-nil but NEITHER a keyword NOR a
;; live frame value has carried an explicit-but-malformed target —
;; `{:frame "app"}`, `{:frame 7}`, `{:frame ['x]}`. `frame-provider` accepts
;; a frame TARGET — a frame-id keyword OR a live frame value (`make-frame`'s
;; return token) — so anything else is a
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
;; coercion at the reader boundary is intentionally kept (the public
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
  observable where the dev trace is elided — including carrying the
  payload's own `:reason` / `:recovery` onto the record, which
  this category needs for exactly the reason its sibling does: it passes no
  `:exception`, so without them the always-on record holds no message at all
  and the dev console can only print the bare category."
  [payload]
  (when-let [dispatch-on-error! (rf.late-bind/get-fn :error-emit/dispatch-on-error)]
    (dispatch-on-error!
      :rf.error/bad-frame-provider-arg
      nil                                ;; no event vector — a provider misuse, not a dispatch throw
      nil                                ;; no event-id
      nil                                ;; no frame — the supplied target is invalid
      nil                                ;; no exception — invalid arg, not a throw
      0                                  ;; elapsed-ms
      (rf.interop/now-ms)                   ;; time
      {:reason   (:reason payload)
       :recovery (:recovery payload)}))
  (rf.trace/emit-error! :rf.error/bad-frame-provider-arg payload)
  payload)

(defn require-frame-provider-target!
  "Validate a public `frame-provider`'s `:frame` arg — accepts a frame-id
  KEYWORD or a live frame VALUE (`make-frame`'s return token), normalizing
  either to the frame id via `frame-value->id` (`frame-provider` teaches the same one frame-target grammar as `dispatch`
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
      (throw (rf.error/ex-info-from-data payload)))
    :else
    (let [payload (bad-frame-provider-arg-payload frame-target {:where where})]
      (emit-bad-frame-provider-arg! payload)
      (throw (rf.error/ex-info-from-data payload)))))

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

  TWO ABSENCES, TWO ERRORS. When the reader returned nil
  because the enclosing render extent REFUSED the ambient tier
  ([[call-with-ambient-frame-refused]]), the mistake is not an absence and
  must not be reported as one: `:rf.error/ambient-frame-refused` is emitted
  and thrown instead, carrying the refusing substrate's own account of what
  to write there.

  AND A MISMATCH IS A THIRD. The reader also answers nil for one
  more reason: inside an extent that declared its own `:extent-frame`, a
  carried stamp naming a DIFFERENT frame is not an answer that extent will
  accept, because the body would then read and dispatch against one frame
  while its own reads, lowered intents and children target another. This
  raises the same `:rf.error/ambient-frame-refused`, with the two frames
  named on the payload so the author is not sent looking for the wrong one.
  A MATCHED carried stamp still answers — refusing it would make `with-frame`
  and `{:frame …}` disagree, which is strictly worse than the silence this
  closes.

  AND THE PURE DOORS ARE ADMITTED. When the reader answered nil
  because nothing was carried — the ambient FIND was refused — and the
  extent has DECLARED its frame, an `operation` in [[pure-frame-doors]]
  (`:current-frame-id`, `:capture-frame`) is answered that declared frame
  instead of refused: an identity read and a capture make no edge and no
  mutation, so the fence has nothing to protect against them. The check is
  ordered AFTER the mismatch, so an enclosing `with-frame` naming a
  different frame is still refused rather than silently repaired to the
  extent's own; and it is keyed on the declaration, so an extent naming no
  frame offers nothing and refuses every operation. Every other operation
  — `:subscribe`, `:dispatch`, and whatever else resolves ambiently — is
  refused.

  The discrimination costs the ordinary path nothing: it is read only after
  resolution has already failed."
  ([operation] (require-current-frame! operation nil))
  ([operation extra]
   (or (resolve-current-frame)
       (if-some [refusal *ambient-frame-refusal*]
         ;; Two refusals, one id — and one admission between them. The
         ;; reader answered nil either because nothing was carried at all
         ;; (the ambient FIND was refused) or because what was carried is
         ;; not this extent's frame. Only the second can name a stamp, so
         ;; `carried` is what the payload builder discriminates on, and it
         ;; is also what ORDERS the admission: a pure door is offered the
         ;; declared frame only when nothing was carried, never over a
         ;; mismatch. Read here, on the failed path, rather than threaded
         ;; down from the reader, so the ordinary resolution stays a single
         ;; call returning a single value.
         (let [carried (frame-value->id (current-frame))]
           (if (and (nil? carried)
                    (contains? pure-frame-doors operation)
                    (some? (:extent-frame refusal)))
             (frame-value->id (:extent-frame refusal))
             (let [payload (ambient-frame-refused-payload
                             operation refusal
                             (if (some? carried)
                               (assoc extra :carried-frame carried)
                               extra))]
               (emit-ambient-frame-refused! payload)
               (throw (rf.error/ex-info-from-data payload)))))
         (let [payload (no-frame-context-payload operation extra)]
           (emit-no-frame-context! payload)
           (throw (rf.error/ex-info-from-data payload)))))))

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
         (throw (rf.error/ex-info-from-data payload))))))

;; ---- bind-fn — INTERNAL dynamic-rebinding carry primitive ------------------
;;
;; There is no public `frame-bound-fn` / `frame-bound-fn*` on the facade —
;; `re-frame.core/capture-frame` is the ONE public HOLD primitive. `bind-fn`'s semantics are genuinely DIFFERENT from
;; `capture-frame`'s pre-bound `{:dispatch :dispatch-sync :subscribe}` op
;; bundle: it re-establishes `*current-frame*` around an ARBITRARY
;; already-held fn (including one that itself calls `current-frame-id` or
;; other frame-scoped readers), so it lives here for the framework's own
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
    (binding [*current-frame*          frame
              *current-frame-bound-in* (rendering-component)]
      (apply f args))))

;; ---- lookup ---------------------------------------------------------------
;;
;; The event pipeline does not ask, hop by hop, whether its frame incarnation
;; is still live. A destroy requested while an event runs waits for that
;; event's boundary (Spec 002 §Destroy), so the incarnation a dequeued event
;; starts with is the one it finishes with; `process-event!` checks once, at
;; the start, that the envelope's drain still names the live record. The
;; liveness predicates below serve work that can outlive its incarnation: a
;; timer, an HTTP reply, a captured frame op, a cold registry write.

(defn- frame-record-visible-to-current-actor?
  "True when raw registry record `f` is a live frame visible to this actor.

  Final records are process-visible. A FIRST construction's provisional record
  is visible only to the work its own construction runs synchronously — setup
  steps, lifecycle trace, registration hooks, finalization and failure
  teardown — so that work can use the ordinary frame machinery while no
  unrelated caller observes a live-looking pre-setup row. That work is exactly
  what runs while the current dynamic transaction owner is the record's owner,
  because `upsert-frame!`'s own transaction is the one place that owner is
  bound; requiring the owner to still hold `id` on THIS host thread excludes a
  binding conveyed to another JVM thread or outliving the reservation. A
  re-registration's provisional record is process-visible: it
  stages a new config onto a LIVE frame whose runtime state, router and drain
  lock are the same objects, so it is not a half-construction, and hiding it
  would make a stable incarnation fail its own liveness checks on every other
  thread. This is the single visibility predicate for exact lookup and every
  public registry enumeration; no reader may expose a half-constructed id by
  filtering raw `@frames` independently."
  [id f]
  (and (not (-> f :lifecycle :destroyed?))
       (or (not= :provisional (-> f :construction :state))
           (= :re-registration (-> f :construction :kind))
           (let [owner (-> f :construction :owner)]
             (and (identical? owner *frame-transaction-owner*)
                  (owner-holds-frame-id? owner id))))))

(defn frame
  "Return the frame record for `id` (a frame-id keyword), or nil if not
  registered, still provisional under another host actor's FIRST construction,
  or destroyed. The registry is keyed by the bare frame-id.

  A first construction's provisional record is visible only to its exact
  construction owner on the owning host thread. Synchronous setup and lifecycle
  publication can therefore use the ordinary frame machinery, while unrelated
  callers never observe a live-looking pre-setup row. A
  re-registration's staged revision is visible to every actor (the frame is
  live throughout), and final records have no owner restriction.

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

  A write that must land on one incarnation — a Tool-Pair state write, an
  exact-incarnation runtime-db write — captures this token while the frame is
  live, then admits its write ONLY while the SAME token is still live
  (`(identical? pinned (frame-incarnation-token id))`). Because
  `destroy-frame!` flips liveness under that SAME `:drain-lock` (both go through
  `call-serialized-with-drain!`), the write and the destruction linearize: it
  can neither land on a destroyed frame nor clobber a newer incarnation that
  reused the id."
  [id]
  (some-> (frame id) :drain-lock))

(defn frame-incarnation-live?
  "True when `token` still identifies `id`'s currently-live frame.

  Work that can outlive its incarnation — a timer, an HTTP reply, a captured
  frame op — takes the token when it is scheduled and compares it here before
  it acts, so work for a destroyed incarnation A never reaches a same-id
  successor B."
  [id token]
  (and (some? token)
       (identical? token (frame-incarnation-token id))))

(declare event-continuation-live?)

(defn frame-disposed-for-drain?
  "Per Spec 002 §Frame disposal mid-drain: predicate used by the
  router's drain loop to interrupt a pass once destruction owns the current
  incarnation. True when ANY of:

    (a) `destroy-frame!` has claimed this exact incarnation — a PENDING claim
        included, which is how the drain finds its event boundary — even while
        its live-container teardown is still running before the `:destroyed?`
        flip. The claim is the queued-work cutoff: a cold serialization release
        never re-kicks that queue, and an already-scheduled drain drops rather
        than executes it. The intentional `:on-destroy` event uses a separate,
        token-scoped internal cascade; ordinary drains have no exemption.
    (b) The frame record still exists but `:destroyed?` is flipped
        (after `destroy-frame!`'s dead flip, before its dissoc), OR
    (c) The frame record is absent from the `frames` atom (the dissoc has
        run).

  The claim test is INCARNATION-SCOPED: it matches the marker's token against
  the record's `:drain-lock`. Returns false when `id` is
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
    ;; Absent from the atom — destroy-frame!'s dissoc ran, OR the id
    ;; was never registered. The drain-loop caller only consults this
    ;; while a pass is already in flight, so the latter case cannot
    ;; arise from that seam.
    true))

(defn frame-incarnation-closing?
  "True when `id`'s in-flight `destroy-frame!` is tearing down EXACTLY the
  incarnation identified by `token` (its `:drain-lock` — see
  `frame-incarnation-token`), false otherwise. A PENDING destroy is not yet
  closing: its teardown has not begun, and the event in flight still runs as
  an ordinary event until the drain reaches the next event boundary.

  The close-lifecycle read, and deliberately the only one: it answers 'is the
  destroy that is in flight for this id the one destroying the incarnation I
  acquired'. Spec 002's destroy recipe linearizes on the EXACT-incarnation
  claim, so the answer is per incarnation, never per bare id. Callers outside
  this namespace acquire a token first (`frame-incarnation-token`) and ask this.
  `destroying-frames` records
  `{id {:token destroying-incarnation-token}}`, so this
  compares the caller's acquire-time token against the token the marker carries
  by identity: a ViewCell commit that acquired the incarnation mid-teardown
  (still live, pre-flip) reads true and joins its teardown, while a token from
  any other incarnation of the id reads false. Nil `token` (or no marker for
  `id`) reads false.

  Keyed by the bare frame-id; the incarnation is disambiguated by the token value."
  [id token]
  (let [{closing-token :token pending? :pending?} (get @destroying-frames id)]
    (and (some? closing-token)
         (not pending?)
         (identical? token closing-token))))

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
  0.72 µs per link, against a `route-url`
  synthesis of 4.71 µs — and it is that caller this exists for.

  This is NOT a widening of what callers may depend on. The lifecycle fields
  `frame-meta` merges (`:created-at`, `:destroyed?`, `:listeners`) and the `:id`
  it stamps are disjoint from the config keys, so for any config key the two
  answer identically; a caller wanting the canonical reflection shape — every
  tool — still calls `frame-meta`, which is the only shape tools may
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

(defn live-store-frame-ids
  "The ids of every registered, non-destroyed frame visible to the current
  actor that resolves the registration store live — a frame `make-frame` built
  without `:images` (the record's `:live-store?` slot). No-id frames are
  included: they resolve live too, and their sub-caches follow the store the
  same way."
  []
  (into #{}
        (comp (filter (fn [[id f]]
                        (and (:live-store? f)
                             (frame-record-visible-to-current-actor? id f))))
              (map (fn [[_ f]] (:id f))))
        @frames))

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
    (rf.substrate.adapter/read-container container)))

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
  preserving every other (state-bearing) slot by identity (EP-0024) — the
  generation-only writer reprojection publishes through. Construction instead
  stages and restores the slot together with its pool inside `upsert-frame!`.
  A no-op for an unknown frame (the registry is keyed by the bare frame-id);
  the two-argument form returns nil.

  The three-argument form is reprojection's compare-and-set against
  `read-record`, the record it re-resolved from. It publishes only while the
  registry still holds that record's incarnation (its `:drain-lock`, the
  `frame-incarnation-token`) carrying that record's generation and descriptor
  pool, each by identity, and returns whether it did. The token alone is not
  enough: a same-id re-registration keeps the incarnation while it stages a
  new generation and pool, and a publish derived from the outgoing pair would
  replace the staged generation and leave its pool unpaired. Spec 002
  §Single-drainer invariant (concurrent hosts). INTERNAL."
  ([id generation]
   (swap! frames (fn [m]
                   (if (contains? m id)
                     (update m id assoc :generation generation)
                     m)))
   nil)
  ([id read-record generation]
   (let [holds?     (fn [m]
                      (let [current (get m id)]
                        (and (some? (:drain-lock read-record))
                             (identical? (:drain-lock read-record) (:drain-lock current))
                             (identical? (:generation read-record) (:generation current))
                             (identical? (:descriptor-pool read-record)
                                         (:descriptor-pool current)))))
         [before _] (swap-vals! frames
                                (fn [m]
                                  (if (holds? m)
                                    (update m id assoc :generation generation)
                                    m)))]
     (holds? before))))

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
  `rf.substrate.adapter/replace-container!` on it throws `:rf.error/derived-container-
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
    (rf.substrate.adapter/read-container container)))

;; ---- EP-0001 partition commit + write helpers -----------------------------
;;
;; The frame-state container is the ONE physical write target. Every durable
;; state write — the router's per-event commit, the privileged runtime
;; mutators, full-frame tool install — flows through `replace-container!` on
;; it. Per Spec 002 §An ordinary :db return replaces only app-db + §Write
;; authority is by convention, and Spec 006 §Commit boundary.

;; ---- per-frame commit epoch ----------------------------------------------
;;
;; A monotonic per-frame counter bumped once per PHYSICAL frame-state install
;; (both write chokepoints: `commit-frame-transition!` and `swap-partition!`).
;; A re-frame-native view substrate's read EVIDENCE carries it as
;; `:frame-epoch`
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
  the `swap!` site below would build a new one on every commit to express a
  constant: measured at 745 B per write on node V8, 21% of what
  a zero-subscription write costs. The value is the same function either
  way; only the number of times it is constructed differs."
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
  or unknown frame). Consumed by a re-frame-native view substrate's read
  evidence — `day8/re-frame2-fresco`'s generation basis is the live caller; a
  moved epoch between two reads means the frame's durable state was
  (re)installed in the gap. Pure read."
  [id]
  (get @frame-commit-epochs id 0))

(def ^:private stale-exact-callback ::stale-exact-callback)

(defn- call-exact-frame-callback
  "Run adapter callback `f` for an exact event-owned incarnation.

  The tagged pair distinguishes a legitimate nil callback result from owner
  loss.  A destroy+throw is inert; a throw while the exact owner remains live
  keeps the adapter's own failure contract."
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
                      #(rf.substrate.adapter/read-container container))]
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
                #(rf.substrate.adapter/replace-container! container next-fs))]
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
  runtime-db untouched (Spec 002 §Frame-state value accessors and mutators
  — a db-shaped name never silently replaces
  runtime-db). Atomic install through the one physical container. Returns
  the set of changed partition keys, or `nil` for an unknown / destroyed
  frame. Internal single-partition write boundary."
  [id app-db]
  (commit-frame-transition! id {app-partition-key app-db}))

(defn replace-runtime-db!
  "Replace ONLY the runtime-db partition of `id`'s frame-state, leaving
  app-db untouched (Spec 002 §Frame-state value accessors and mutators).
  Internal single-partition helper, the sibling of
  `replace-app-db!`; the public/epoch-backed write surface is
  `replace-frame-state!`. Atomic install through
  the one physical container. Returns the set of changed partition keys, or
  `nil` for an unknown / destroyed frame."
  [id runtime-db]
  (commit-frame-transition! id {runtime-partition-key runtime-db}))

(defn replace-frame-state!
  "Atomically install `frame-state` — a PARTIAL frame-state map (any subset
  of `{:rf.db/app … :rf.db/runtime …}`) — into `id`'s one physical
  frame-state container: a PRESENT key replaces that partition, an ABSENT
  key is carried forward UNCHANGED. A
  db-shaped key never silently touches the other partition; this is the
  one explicit frame-state write surface. Both a caller-supplied app-only, runtime-only, or
  both-partition map install in ONE atomic write. Returns the set of
  changed partition keys, or `nil` for an unknown / destroyed frame.

  This is a thin pass-through onto `commit-frame-transition!`, which
  already implements the present-replaces / absent-preserves contract —
  `replace-frame-state!` merely narrows `frame-state` to the two
  recognized partition keys before delegating. A single-partition write is
  a one-key map:
  `(replace-frame-state! id {app-partition-key new-app-db})` /
  `(replace-frame-state! id {runtime-partition-key new-runtime-db})`.

  The 3-arity is the EXACT-INCARNATION variant (mirrors
  `commit-frame-transition!`'s own 2/3-arity split): it resolves the write
  through `owner-token`'s own frame record and installs nothing — returning
  `nil` — unless `owner-token` still names the live incarnation, so a same-id
  successor reseated under `id` after the caller captured its token can never
  receive the write. Epoch restore (`perform-restore!`) threads the token it
  validated preconditions against so a time-travel install lands only on the
  exact frame incarnation it resolved."
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

  The 6-arity is the EXACT-INCARNATION variant behind
  `swap-runtime-db-exact!`, used by the writes an event issues under its
  owner token (machine spawn and destroy). It resolves the write through
  `owner-token`'s own frame record and writes nothing unless that token names
  the live incarnation. The token is the router-bound owner of the event on the
  stack. A frame's teardown runs under no owner (`run-destroy-recipe!`), so when
  frame B's event destroys frame A, A's machine `:exit` effects take the bare
  write and never reach here with B's token. Once the token matches, the write
  runs in one of `id`'s own events, so nothing can take the incarnation away
  mid-install: a destroy that a synchronous container watch requests there
  cannot flip its liveness before the event ends, and the id's reservation
  makes a same-id `make-frame` fail fast. Returns the new slice, or nil when
  `owner-token` does not name the live record (the caller treats a nil as a
  no-op)."
  ([id pk f args] (swap-partition! id pk f args nil false))
  ([id pk f args owner-token exact-owner?]
   (if-not exact-owner?
     (when-let [container (frame-state-container id)]
       (let [current   (rf.substrate.adapter/read-container container)
             new-slice (apply f (get current pk) args)]
         (rf.substrate.adapter/replace-container! container (assoc current pk new-slice))
         ;; Substrate evidence counter: one bump per physical frame-state install —
         ;; this is the second (and last) frame-state write chokepoint.
         (bump-commit-epoch! id)
         new-slice))
     ;; Exact-incarnation write. Resolve the record so the
     ;; write binds to A's own container.
     (when-let [frame-record (frame id)]
       (when (identical? owner-token (:drain-lock frame-record))
         (let [container (:frame-state frame-record)
               current   (rf.substrate.adapter/read-container container)
               new-slice (apply f (get current pk) args)]
           (rf.substrate.adapter/replace-container! container (assoc current pk new-slice))
           (bump-commit-epoch! id)
           new-slice))))))

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
  cascade / mid-fx) writes (machine spawn / destroy, the resources
  registry). A mid-fx write lands after the event's flow pass; the
  `:fx` walk settles the frame's flows when it ends (Spec 013 §Sequencing).
  Models `swap!` over the runtime-db partition;
  under the single-drainer invariant (Spec 002 §Single drainer per frame) the
  read-then-replace is effectively atomic. Per Spec 002 §The two-partition
  frame contract — runtime-db is reserved BY CONVENTION (EP-0001 decision
  #4); this
  is the framework-authority write surface."
  [id f & args]
  (swap-partition! id runtime-partition-key f args))

(defn ^:no-doc swap-runtime-db-exact!
  "Exact-incarnation `swap-runtime-db!`: writes through `owner-token`'s own
  frame record, and only while that token names `id`'s live incarnation (see
  `swap-partition!`). Used by the machine lifecycle writes and the exact-aware
  elision-registry writes they issue, with the router-bound event owner token.
  Returns the new runtime-db slice, or nil when `owner-token` does not name the
  live record."
  [id owner-token f & args]
  (swap-partition! id runtime-partition-key f args owner-token true))

;; ---- lifecycle-vs-drain serialization -------------------------------------
;;
;; Some per-frame mutations must be ATOMIC with respect to that frame's event
;; drain — they read-modify-write frame state, and a concurrent drain that
;; interleaves between the steps can observe a half-applied change. The
;; `destroy-frame!` liveness flip and the Tool-Pair state writes are such
;; operations.
;;
;; The frame's `:drain-lock` is the single-drainer serialization
;; primitive (the router CAS-acquires it for the whole drain pass — see
;; `re-frame.router/drain-loop!`). `call-serialized-with-drain!` runs `f`
;; under that lock so the mutation is mutually exclusive with any concurrent
;; drain, with ONE mechanism rather than per-op reordering / token threading
;; (which would touch the hot path). The drain path itself just CAS-acquires
;; the lock; only the cold ops contend for it on top.
;;
;; REENTRANCY is the load-bearing subtlety. A cold op can be issued MID-DRAIN
;; — a `destroy-frame!` reached from an event's `:fx` walk runs on the
;; draining thread, which already holds `:drain-lock`. A naive acquire would
;; deadlock the drainer against a lock it itself holds. So we first ask the
;; router whether THIS thread is the frame's active drainer (the same
;; `:in-drain?` thread marker the `dispatch-sync` nesting guard reads): if so
;; we are already inside the single-drainer window and run `f` directly; only
;; a DIFFERENT thread (or a non-drain call site) acquires the lock. On CLJS —
;; single-threaded — the marker is `true`/`nil` and the same equality
;; discriminates; an uncontended top-level call CAS-acquires the false lock on
;; the first try.

(defn- current-thread-is-drainer?
  "True when the calling thread is the frame's currently-active drainer.
  Reads the router's `:in-drain?` marker (stamped by
  `re-frame.router/mark-drainer!` to the drainer thread on JVM, `true` on
  CLJS). `call-serialized-with-drain!` uses this to take the reentrant
  fast-path for an op issued mid-drain — it is already inside the
  single-drainer window, so re-taking `:drain-lock` would self-deadlock."
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
  window (e.g. a Tool-Pair write issued from an event HANDLER mid-cascade).
  Public wrapper over the same `:in-drain?` thread marker
  `call-serialized-with-drain!` reads. Returns false for an absent frame
  (nothing can be draining it)."
  [frame-id]
  (boolean
    (when-let [frame-record (frame frame-id)]
      (current-thread-is-drainer? frame-record))))

;; There is no by-frame-id drain-ownership probe published to other
;; namespaces. The trace tooling DEFERS a drain-owned listener fan-out to the
;; post-drain boundary the three `:drain-lock` regions establish
;; (`rf.trace/call-with-deferred-listener-delivery` — `re-frame.router/drain-try!` /
;; `drain-block!` and `call-serialized-with-drain!` below), so the deferral scope
;; itself is the ownership evidence and no cross-namespace probe is needed. The
;; private `current-thread-owns-drain-serialization?` above serves only the
;; reentrancy fast-path.

(defn call-serialized-with-drain!
  "Run thunk `f` serialized against `frame-id`'s event drain, returning its
  value. Used by per-frame lifecycle mutations that must not interleave with a
  concurrent drain or with each other — the `destroy-frame!` liveness flip
  (`mark-frame-destroyed!`) and Tool-Pair state writes.

  - Frame absent (unregistered / destroyed): nothing can be draining it, so
    just run `f`.
  - Calling thread already OWNS the serialization — it is the active drainer
    (a mid-drain call), OR it already holds a COLD critical section on
    this frame (e.g. a Tool-Pair write body that calls `destroy-frame!`): run
    `f` directly. Re-taking the non-reentrant `:drain-lock` CAS cell from a
    thread that already holds it would self-deadlock (see
    `current-thread-owns-drain-serialization?`).
  - Otherwise: spin-CAS-acquire `:drain-lock` (the same acquire shape
    `re-frame.router/drain-block!` uses — an active drainer holds it until
    both of its lanes are empty), stamp this thread as the
    `:serialized-holder`, run `f`, then clear the holder and release the lock
    in a `finally`.

  The cold acquire → run → release region is wrapped in
  `rf.trace/call-with-deferred-listener-delivery`: trace events emitted
  inside a held cold section are delivered at that post-drain boundary, once the
  lock is down, rather than running arbitrary listener code under it. The
  already-owns branch inherits its owner's scope (the wrapper is nesting-aware),
  so a nested serialized op appends to the SAME batch and the outermost holder
  flushes it once."
  [frame-id f]
  (if-let [frame-record (frame frame-id)]
    (if (current-thread-owns-drain-serialization? frame-record)
      (f)
      (rf.trace/call-with-deferred-listener-delivery
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
               ;; The cold release must mirror the
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
                               (let [pending? (or (seq (:queue @router))
                                                  (seq (:internal @router)))
                                     closing? (frame-incarnation-closing?
                                                frame-id drain-lock)]
                                 (reset! drain-lock false)
                                 (boolean (and pending? (not closing?)))))]
                 (when strand?
                   (when-let [reschedule! (rf.late-bind/get-fn :router/reschedule-drain!)]
                     (reschedule! frame-id frame-record))))))))))
    (f)))

;; ---- frame presets (Spec 002 §Frame presets) ------------------------------
;;
;; A :preset key in metadata expands at registration time into a fixed
;; bundle of metadata keys. User-supplied keys win on conflict.
;; Per Spec 002 §Frame presets, the closed list is:
;;   :default :test :story

(defn- preset-expansion [preset]
  ;; Per Spec 002 §Frame presets and Spec-Schemas §:rf/preset-expansion.
  ;; The three canonical expansions:
  ;;   :default    -> {} (explicit no-op; identical to omitting :preset)
  ;;   :test       -> :rf.http/stub-only? true, so managed HTTP never reaches
  ;;                  the network unless a test puts a stub transport in
  ;;                  place (Spec 014); explicit :drain-depth 1000 (matches the
  ;;                  framework default — surfaced so tooling can read the
  ;;                  bound off frame-meta without consulting the global default);
  ;;                  :rf.cofx/mint-policy :strict (per EP-0017 §6 — a
  ;;                  declared-absent generator-backed recordable fact is
  ;;                  missing-required rather than freshly minted, so a test's
  ;;                  path of least resistance is supply-the-fact, not a silent
  ;;                  per-run random; the determinism feature stays core, not
  ;;                  polish). A test that wants a fresh value opts back
  ;;                  into generation with `{:rf.cofx/mint-policy :live}`
  ;;                  (per-call or per-frame).
  ;;   :story      -> stub-only HTTP, as :test; tighter :drain-depth 16
  ;;                  so a runaway dispatch cascade fails fast under a story.
  ;;                  NOT strict-by-default — a story is a live demo, not a
  ;;                  determinism fixture, so it rides the router's :live
  ;;                  default (no mint-policy entry).
  ;; User-supplied keys win on conflict; see expand-preset.
  ;;
  ;; Platform is NOT a preset concern: it is the host default, overridden
  ;; per frame with an explicit `{:platform :server}` / `{:platform :client}`
  ;; frame-config key (Spec 002 §Platform, Spec 011 §Effect handling on the
  ;; server).
  ;;
  ;; `:rf.http/stub-only?` is a frame-config key the HTTP artefact reads when
  ;; a request issues: with no stub transport in place it fails the request
  ;; as `:rf.http/transport` instead of sending it. Core only names the key,
  ;; so a preset frame needs nothing from the HTTP artefact, and a port
  ;; without Spec 014 ignores it.
  (case preset
    :default    {}
    :test       {:rf.http/stub-only?  true
                 :drain-depth         1000
                 ;; Per EP-0017 §6: the :test preset
                 ;; defaults the cofx MINT POLICY to :strict — a declared-absent
                 ;; generator-backed recordable fact under a test frame is
                 ;; `:rf.error/missing-required-cofx`, never a freshly-minted
                 ;; per-run value. Strict-by-default tests are core: a
                 ;; determinism feature whose path of least resistance is a
                 ;; fresh random per run would degrade the test culture it
                 ;; exists to serve. A test that wants a fresh value opts
                 ;; back in with `{:rf.cofx/mint-policy :live}` (per-call
                 ;; dispatch opt or a per-frame override — user keys win on
                 ;; conflict).
                 :rf.cofx/mint-policy :strict}
    :story      {:rf.http/stub-only? true
                 :drain-depth        16}
    nil         {}
    (rf.error/throw-error!
      :rf.error/unknown-preset
      'rf/make-frame
      (str "unknown frame :preset " (pr-str preset)
           "; valid presets are :default, :test, :story "
           "(or omit :preset). Use one of those.")
      {:recovery :use-a-valid-preset
       :extra    {:preset preset
                  :valid  #{:default :test :story}}})))

(defn- expand-preset [metadata]
  (let [preset    (:preset metadata)
        expansion (preset-expansion preset)]
    ;; user-supplied keys win on conflict
    (merge expansion metadata)))

;; ---- registration ---------------------------------------------------------

(def ^:private construction-input-keys
  "The reserved keys `make-frame` threads through config into record slots
  (`:rf.frame/initial-db` is threaded by nothing and stripped defensively).
  They are stripped from the stored `:config` and the lifecycle traces, so no
  `frame-meta` read or trace carries a one-shot construction input — the
  descriptor pool in particular is transient and never serialised."
  [:rf.frame/generation :rf.frame/live-store? :rf.frame/descriptor-pool
   :rf.frame/initial-db])

(defn- new-frame-record [id config]
  ;; ONE physical frame-state container holding both partitions (Spec 002
  ;; §One physical container, two projection reactions; EP-0001 decision #3).
  ;; A fresh frame starts with an empty app-db (Spec 002 §Frames always start
  ;; with app-db = {}) and an empty runtime-db.
  (let [;; EP-0024: `make-frame` threads the resolved generation through the
        ;; config under the reserved `:rf.frame/generation` key so it is installed
        ;; on the record BEFORE construction setup runs — an `:initial-events`
        ;; cascade then resolves through the frame's OWN view (its image
        ;; generation, or the live store for a frame made without `:images`), not
        ;; the global registrar. EP-0027: a fresh frame ALWAYS starts with app-db
        ;; `{}` (Spec 002 §Frames always start with app-db = {}). There is no
        ;; `:initial-db` seed — seeding app-db is itself a setup
        ;; event (`[:rf/set-db {…}]` as the first `:initial-events` step), so the
        ;; whole of construction is one visible event script with no special-cased
        ;; direct write.
        frame-state (rf.substrate.adapter/make-state-container
                      {app-partition-key     {}
                       runtime-partition-key {}})
        ;; Partition projections, constructed into locals under a
        ;; FAILURE-ATOMIC boundary. `make-derived-value`
        ;; returns an opaque value that may install a real host resource (a
        ;; source watch) and be externally owned by the adapter, so a PARTIAL
        ;; allocation — the app-db projection returns, then the runtime-db
        ;; projection throws — must not strand the first projection's watch on
        ;; a frame that never gets installed. When a LATER projection throws,
        ;; every EARLIER successfully-returned projection is disposed in
        ;; REVERSE acquisition order through the `rf.interop/dispose!`
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
        app-db      (rf.substrate.adapter/make-derived-value [frame-state] app-partition-key)
        runtime-db  (try
                      (rf.substrate.adapter/make-derived-value [frame-state] runtime-partition-key)
                      (catch #?(:clj Throwable :cljs :default) e
                        ;; Reverse acquisition order: dispose the already-
                        ;; returned app-db projection best-effort — a throwing
                        ;; dispose must not MASK the original construction
                        ;; failure (mirrors the best-effort posture of
                        ;; `tear-down-partition-projections!`) — then re-raise.
                        (try (rf.interop/dispose! app-db)
                             (catch #?(:clj Throwable :cljs :default) _ nil))
                        (throw e)))]
   {:id          id
    ;; EP-0024 — the resolved IMAGE GENERATION slot. ONE unified
    ;; frame value owns its resolved generation directly on the single
    ;; `frames`-registry record (Term: Frame value — "owns … resolved image
    ;; generation"); there is no second live-frame registry holding it. nil for
    ;; a frame with no `:images` selection. Threaded in via the reserved
    ;; `:rf.frame/generation` config key so it is live BEFORE `:initial-events`
    ;; run; a same-`:id` `make-frame` re-construction stages it with its pool,
    ;; and reprojection swaps it in place via `set-generation!`, each
    ;; preserving every other (state-bearing) slot by identity.
    :generation  (get config :rf.frame/generation)
    ;; The descriptor pool that generation was resolved against: nil for the
    ;; live source store, the caller's pool for `make-frame`'s 2-arity.
    ;; Reprojection re-resolves against it, reading it from the same record
    ;; snapshot as the generation, and a re-registration stages and restores
    ;; the two together. Threaded in via the reserved
    ;; `:rf.frame/descriptor-pool` config key, like `:generation`.
    :descriptor-pool (get config :rf.frame/descriptor-pool)
    ;; True for a frame `make-frame` built WITHOUT `:images`: it carries no
    ;; generation and resolves the registration store live, raising
    ;; `:rf.error/ambiguous-registration` for an id more than one namespace
    ;; registers (Spec 002 §Image resolution and composition). False for every
    ;; other frame without a generation (the test fixture's ambient frame),
    ;; which resolves the registrar's latest registration. Threaded in via the
    ;; reserved `:rf.frame/live-store?` config key, like `:generation`.
    :live-store? (true? (get config :rf.frame/live-store?))
    :frame-state frame-state
    ;; app-db / runtime-db are READ-ONLY projection reactions over the one
    ;; physical container — `make-derived-value` memoises on `=`, so a
    ;; runtime-only commit does not propagate to app subs (and vice versa),
    ;; with no dirty flags (EP-0001 decision #7). The compute-fn is the bare keyword
    ;; lookup of the partition slice; `make-derived-value`'s recompute closure
    ;; arity-specialises the 1-source case so the projection costs a single
    ;; keyword invoke per recompute. Constructed above under a failure-atomic
    ;; boundary.
    :app-db      app-db
    :runtime-db  runtime-db
    ;; Two FIFO lanes (Spec 002 §Run-to-completion): `:queue` is the
    ;; EXTERNAL lane, `:internal` holds the in-flight family's pending work.
    :router      (atom {:queue      rf.interop/empty-queue
                        :internal   rf.interop/empty-queue
                        :scheduled? false})
   ;; Single-drainer invariant: a separate CAS-able cell that admits
   ;; at most one thread into `drain!` at a time. On the JVM the
   ;; executor's `next-tick` callback can wake while the calling
   ;; thread is mid-drain (e.g. `dispatch-sync!`); without this guard,
   ;; both threads' peek+pop sequence on the lanes is non-atomic and
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
    :lifecycle  {:created-at (rf.interop/now-ms)
                 :destroyed? false
                 :listeners  []}
    ;; The construction-only reserved keys are consumed above into their
    ;; slots and stripped from the stored `:config` (`construction-input-keys`),
    ;; so `frame-meta` / tooling never surface a one-shot construction input as
    ;; durable frame config. (EP-0026: there is no `:rf.frame/capabilities`
    ;; config slot.)
    ;; EP-0027: `:initial-events` is DURABLE frame config — it stays in `:config`
    ;; so a destroy + re-`make-frame` (a full "reset" composition)
    ;; can re-dispatch the recorded setup.
    :config     (apply dissoc config construction-input-keys)}))

(declare destroy-frame!)

;; ---- :initial-events normalizer + setup runner (EP-0027) ------------------
;;
;; EP-0027: one declarative key stands in for a hand-written post-`make-frame`
;; setup loop. `:initial-events` is an ordered vector of SETUP STEPS
;; dispatched synchronously, in order, into the frame at construction —
;; "`:initial-events` IS that loop, written as data" (EP-0027 §Abstract). The
;; normalizer below is PREFLIGHT validation (EP-0027 §Failure): it runs BEFORE
;; any step dispatches and BEFORE the frame container exists, so a bad shape
;; throws and leaves no frame registered. The runner then dispatches each step
;; through the synchronous `dispatch-sync!` path — the same path such a
;; loop would use — draining each to a fixed point before the next, tagging each with
;; `:source :frame-init` + its step index (EP-0027 §Provenance).
;;
;; The guiding rule (EP-0027 §Scope note): `:initial-events` is NO MORE CAPABLE
;; than the loop it stands in for. No replay tape, no snapshot, no atomic staging, no
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
    (rf.error/throw-error!
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
    (rf.error/throw-error!
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
            (rf.error/throw-error!
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
              (rf.error/throw-error!
                :rf.error/initial-events-bad-event
                where-sym
                (str "an :initial-events map step's :event is missing, empty, or "
                     "not an event vector — got " (pr-str event) " in step "
                     (pr-str step) ". A map step is {:event <non-empty event "
                     "vector> :opts <dispatch-sync opts>}; :event is required.")
                {:recovery :supply-a-non-empty-event
                 :extra    {:step step}}))
            (when-not (map? opts)
              (rf.error/throw-error!
                :rf.error/initial-events-bad-opts
                where-sym
                (str "an :initial-events map step's :opts is not a map — got "
                     (pr-str opts) " in step " (pr-str step) ". :opts is the "
                     "ordinary dispatch-sync opts map (e.g. {:rf.cofx {:rf/time-ms …}}).")
                {:recovery :pass-an-opts-map
                 :extra    {:step step}}))
            (when (contains? opts :frame)
              (rf.error/throw-error!
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
          (rf.error/throw-error!
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
  under strict construction (EP-0027 §Failure) — the PRE-COMMIT
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
  effort post-commit side-effect failed. Per the FX atomicity
  asymmetry (pre-commit transactional / post-commit best-effort),
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
  "STRICT CONSTRUCTION teardown (EP-0027 §Failure). A setup step
  `idx` (`event`) failed — EITHER by an ESCAPING throw `cause` out of
  `dispatch-sync!`, OR by an IN-BAND `:rf.error/*` the chain captured (its
  always-on error record is `cause`'s stand-in via `captured`). Tear down the
  partially-created frame `id` so no half-created frame is left live, then raise
  `:rf.error/initial-events-step-failed` naming the failing step. `cause-ex` is
  the host throwable when the failure escaped (carried as `:cause`); nil for an
  in-band capture. `cause-msg` is the human cause text for the diagnostic
  message. `where-sym` is the constructor symbol. `owner-token` is the EXACT
  incarnation token this construction installed (the record's `:drain-lock`,
  returned by `try-install-new-frame!`): teardown is exact-token-owned, so once
  a setup step has destroyed A the two-argument `destroy-frame!` finds no live A
  and no-ops. Construction holds the id's reservation until its own `finally`,
  so no same-id successor can be seated before this rollback runs."
  [id idx event cause-ex cause-msg where-sym owner-token]
  (destroy-frame! id owner-token)
  (rf.error/throw-error!
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

  Each step is dispatched through `dispatch-sync!` (the same synchronous path
  such a loop would use), with the step's `:opts` merged under construction provenance:
  `:source :frame-init` and the step's `:step-index`, and `:frame` forced to
  `id` (the EP forbids a caller-supplied `:frame`). By the time this returns the
  synchronous setup has settled; asynchronous effects started by setup are NOT
  awaited (EP-0027 §Construction).

  STRICT CONSTRUCTION (EP-0027 §Failure):
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
      `rf.error/throw-error!`), a user-interceptor throw
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

  Without the in-band capture, a handler-body throw during construction
  would be traced-and-recovered and the frame left ALIVE — contradicting
  the EP-0027 §Failure throw→teardown promise for exactly the
  `[:rf/set-db x]` case.

  `base-opts` carries construction provenance shared by every step — the
  `:rf.trace/call-site` of the `make-frame` declaration (gated on
  `rf.interop/debug-enabled?` by the caller, so production CLJS builds DCE it) — so a
  setup event attributes back to where `:initial-events` was declared (EP-0027
  §Provenance). The step's own `:opts` overlay it (a step may carry `:rf.cofx`,
  etc.), and the framework keys (`:frame` / `:source` / `:step-index`) win last.

  Reached through `dispatch-sync!` via late-bind to avoid a compile-time cyclic
  dep (router requires frame).

  `owner-token` is the EXACT incarnation token the winning `make-frame` install
  produced (the record's `:drain-lock`). Every teardown path here — the
  escaping-throw + in-band `raise-setup-step-failed!` branches and the
  runner-unavailable branch — destroys via the two-argument `destroy-frame! id
  owner-token`, so a rollback owns exactly the incarnation it constructed: it
  no-ops once a setup step has destroyed that incarnation, and the
  construction's reservation keeps any same-id successor out until the
  transaction settles."
  [id steps base-opts where-sym owner-token]
  (when (seq steps)
    ;; The setup runner reaches `dispatch-sync!` via late-bind (the
    ;; router requires frame, so a compile-time call would be a cyclic dep). If
    ;; the hook is NOT yet registered (re-frame.router not loaded — a standalone
    ;; re-frame.frame require with no router) we must NOT silently drop the
    ;; setup: the EP guiding rule is that `:initial-events` is NO LESS capable
    ;; than the hand-written `dispatch-sync` loop it stands in for (which would error
    ;; LOUDLY on an unresolved `dispatch-sync` var, never skip), and Conventions
    ;; §No silent swallow requires a recognised-but-unhonourable input to
    ;; signal. So when there ARE steps to run and the runner is unavailable,
    ;; fail loud — tear down the partial frame (the container was already swapped
    ;; into `frames` by the caller) and throw, naming that the router is not
    ;; loaded. (On the common path re-frame.core requires re-frame.router, so
    ;; the hook is published before any runtime frame construction.)
    (if-let [dispatch-sync! (rf.late-bind/get-fn :router/dispatch-sync!)]
      ;; The always-on error-emit registry — the production-survivable axis the
      ;; router's IN-BAND error fan-out (handler / interceptor / cofx / flow
      ;; exceptions) rides. Reached via late-bind so this fn carries no static
      ;; dep on `error-emit` (the `error-emit` → `elision` → `frame` load
      ;; cycle). `re-frame.router` (whose presence we just confirmed via the
      ;; `:router/dispatch-sync!` hook) statically requires `error-emit`, so when
      ;; the runner is available these hooks are too; the `when register` guard
      ;; keeps the install defensive regardless. See `fire-on-destroy-event!` for
      ;; the symmetric `:on-destroy`-throw capture.
      (let [register  (rf.late-bind/get-fn :error-emit/register-error-listener!)
            remove-cb (rf.late-bind/get-fn :error-emit/unregister-error-listener!)]
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
                  ;;
                  ;; The listener CLAIMS (returns `true` for) only the record
                  ;; it captures, because the step re-raises it as
                  ;; `:rf.error/initial-events-step-failed`. Every other record
                  ;; the step produces — a handler-less step's
                  ;; `:rf.error/no-such-handler`, a post-commit fx failure —
                  ;; stays unclaimed, so an untooled dev build still prints it.
                  listener   (fn [record]
                               (when (and (= id (:frame record))
                                          (contains? setup-step-failure-categories
                                                     (:rf.error/id record))
                                          (nil? @captured))
                                 (reset! captured record)
                                 true))]
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
                      id idx event t (rf.error/ex-message-safe t) where-sym owner-token)))
                (finally
                  (when (and register remove-cb)
                    (remove-cb listener-k))))
              ;; IN-BAND failure: dispatch-sync! returned nil normally but the
              ;; interceptor chain CAPTURED a throw and fanned it out on the
              ;; always-on error-emit axis (the `[:rf/set-db x]` bad-arg / any
              ;; handler-body throw → `:rf.error/handler-exception`).
              ;; Strict construction treats it as a setup-step
              ;; failure — tear down + raise, naming the captured category in the
              ;; cause text (no host throwable is carried; the chain swallowed it).
              (when-let [record @captured]
                (raise-setup-step-failed!
                  id idx event nil
                  (str "the interceptor chain captured " (:rf.error/id record)
                       (when-let [r (:reason record)] (str " (" r ")")))
                  where-sym owner-token)))
            (recur (inc idx) (rest remaining)))))
      ;; The runner hook is unavailable but there ARE steps to run: fail loud
      ;; rather than silently dropping the setup. Tear down the
      ;; partial frame (the caller already swapped the container into `frames`)
      ;; so no half-created, never-setup frame is left live, then throw naming
      ;; that re-frame.router is not loaded. Teardown is exact-token-owned
      ;; (`owner-token`) so it removes only the incarnation we installed.
      (do
        (destroy-frame! id owner-token)
        (rf.error/throw-error!
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

(defn- classification-key-reason
  "The diagnostic for a `:sensitive` / `:large` frame key, naming where the
  declaration it attempts lives instead."
  [k]
  (case k
    :sensitive
    (str "retired frame key :sensitive (EP-0025). A frame no longer carries a "
         ":sensitive block: durable app-db classification is a "
         "`(rf/reg-classification <feature-id> {:sensitive [[…]]})` beside "
         "the feature that writes the path, and HTTP carrier names moved onto "
         "the managed-HTTP `reg-fx` registration metadata (its `:carriers "
         "{:headers […] :query-params …}` block) — see Spec 014 §HTTP "
         "carriers.")
    :large
    (str "retired frame key :large — durable app-db classification is no "
         "longer a frame annotation (EP-0025). Declare it with "
         "`(rf/reg-classification <feature-id> {:large [[…]]})` beside the "
         "feature that writes the path; a frame is not app-db's definition "
         "site.")))

(defn- validate-frame-config!
  "Refuse a malformed frame config before any frame state is written, so a bad
  declaration throws with no frame left registered and never reaches
  `:initial-events`. Pure; the checks run in this order and the first defect
  throws:

  1. `:sensitive` / `:large` — no frame carries either. Durable app-db
     classification is a `reg-classification`, and HTTP carrier names live
     on the `:rf.http/managed`
     registration, so either key would install nothing
     (`:rf.error/bad-frame-classification`).
  2. `:http-interceptors` — a vector of keyword ids naming
     `reg-http-interceptor` definitions, in the order the frame runs them. The
     ids resolve when a request is issued, so neither the http artefact nor the
     definitions need exist yet (`:rf.error/http-bad-interceptor`).
  3. `:flows` — a vector of keyword ids naming `reg-flow` definitions, which
     `make-frame` has already resolved through the frame's own view
     (`:rf.error/unregistered-flow`). A non-empty one needs the flows artefact,
     which evaluates the flows each event (`:rf.error/flows-artefact-missing`).
  4. `:observability` — the sink-policy grammar `re-frame.observability` owns,
     reached by late-bind because that namespace requires this one
     (`:rf.error/bad-frame-classification`).

  Shape only: a bare key nothing reads is `warn-unknown-config-keys!`'s, and a
  key an optional artefact owns is that artefact's preflight. Spec 002
  §`make-frame` — atomic create-and-register and the canonical config grammar."
  [id config where-sym]
  (when-let [k (some #(when (contains? config %) %) [:sensitive :large])]
    (rf.error/throw-error!
      :rf.error/bad-frame-classification
      where-sym
      (classification-key-reason k)
      {:recovery :fix-frame-classification
       :extra    {:frame id :bad-key k :bad-value (get config k)}}))
  (let [ids (:http-interceptors config)]
    (when-not (or (nil? ids)
                  (and (vector? ids) (every? keyword? ids)))
      (rf.error/throw-error!
        :rf.error/http-bad-interceptor
        where-sym
        (str ":http-interceptors must be a vector of keyword ids naming "
             "reg-http-interceptor definitions, in the order the frame runs "
             "them, e.g. [:shop/auth :shop/telemetry] — got " (pr-str ids) ".")
        {:extra {:http-interceptors ids}})))
  (let [ids (:flows config)]
    (cond
      (not (or (nil? ids) (and (vector? ids) (every? keyword? ids))))
      (rf.error/throw-error!
        :rf.error/unregistered-flow
        where-sym
        (str ":flows must be a vector of keyword ids naming reg-flow "
             "definitions, e.g. [:cart/subtotal :cart/total] — got "
             (pr-str ids) ".")
        {:recovery :fix-registration
         :extra    {:flows ids}})

      (and (seq ids) (nil? (rf.late-bind/get-fn :flows/run-flows-on-db)))
      (rf.error/throw-error!
        :rf.error/flows-artefact-missing
        where-sym
        (let [{maven      :maven
               require-ns :require} (:flows rf.features/feature-registry)]
          (str "this frame config names :flows, which requires " maven
               " on the classpath; add it to deps and require " require-ns
               " at app boot, BEFORE the frame is constructed."))
        {:extra {:flows ids}})))
  (when (contains? config :observability)
    (when-let [validate-policy! (rf.late-bind/get-fn :observability/validate-policy!)]
      (validate-policy! where-sym id (:observability config))))
  nil)

;; ---- lifecycle contributions -----------------------------------------------
;;
;; An optional artefact holds a frame's lifecycle through ONE contribution on
;; this list, so its destroy, registration, preflight, test-reset and config-key
;; needs touch no core file (Spec 002 §Lifecycle contributions).

(defonce ^:no-doc lifecycle-contributions
  ;; The contributions in registration (load) order. `destroy-frame!`,
  ;; `upsert-frame!`, the runtime-reset fixture and the frame-config vocabulary
  ;; walk it.
  (atom []))

(defn ^:no-doc contribute-lifecycle!
  "Register an artefact's lifecycle contribution: a map with an `:id` and any
  of `:phase` (the destroy phases it joins, drawn from `#{:pre-dead
  :post-dead}`), `:on-frame-destroyed` `(fn [frame-id phase])`,
  `:on-frame-registered` `(fn [frame-id])`, `:preflight-config` `(fn [frame-id
  config])`, `:test-reset` `(fn [])` and `:config-keys` (the bare frame-config
  keys the artefact reads). An artefact calls this once at load. A contribution
  whose `:id` is already registered merges into that entry and keeps its place,
  so a reload replaces members and a test-support namespace can add its
  `:test-reset`; any other is appended. Spec 002 §Lifecycle contributions."
  [contribution]
  (let [id (:id contribution)]
    (swap! lifecycle-contributions
           (fn [cs]
             (if-let [i (first (keep-indexed (fn [i c] (when (= id (:id c)) i)) cs))]
               (update cs i merge contribution)
               (conj cs contribution)))))
  nil)

(defn ^:no-doc lifecycle-contribution
  "The registered lifecycle contribution with `id`, or nil."
  [id]
  (some #(when (= id (:id %)) %) @lifecycle-contributions))

;; ---- the frame-config key vocabulary (dev-only) ----------------------------
;;
;; Per Conventions §No silent swallow: a frame config is open for NAMESPACED
;; extension keys only. A bare key nothing reads is a likely typo
;; (`:fx-overides`), so `make-frame` warns about it in dev.

(def ^:private core-config-keys
  "The bare frame-config keys core reads (Spec-Schemas §`:rf/frame-meta`),
  with the registration-metadata base a frame config shares. An optional
  artefact names the bare keys it reads in its contribution's `:config-keys`."
  #{:id :doc :schema :tags :platforms :ns :line :column :file
    :preset :initial-events :on-destroy :fx-overrides :interceptor-overrides
    :interceptors :drain-depth :platform :observability})

(defn- warn-unknown-config-keys!
  "Emit one `:rf.warning/unknown-frame-config-key` for a frame config that
  carries bare keys outside the recognised vocabulary, naming every such
  key. Namespaced keys pass. Dev-only."
  [id config]
  (when rf.interop/debug-enabled?
    (let [known   (into core-config-keys (mapcat :config-keys) @lifecycle-contributions)
          unknown (into []
                        (comp (remove qualified-keyword?) (remove known))
                        (keys config))]
      (when (seq unknown)
        (rf.trace/emit! :warning :rf.warning/unknown-frame-config-key
                        {:frame        id
                         :unknown-keys unknown
                         :known        (vec (sort known))
                         :recovery     :warned
                         :reason
                         (str "make-frame for `" id "` declares unknown config "
                              (if (= 1 (count unknown)) "key " "keys ")
                              (pr-str unknown)
                              " — a bare key outside the recognised set is a likely typo, "
                              "or belongs to an artefact that is not loaded, and is never "
                              "read. App extension keys must be NAMESPACED (e.g. "
                              "`:myapp/feature`). Recognised bare keys: "
                              (pr-str (vec (sort known))) ".")})))))

(defn- feature-loaded?
  "Whether the optional artefact `feature` (a `rf.features/feature-registry`
  key) is loaded."
  [feature]
  (true? (get-in (rf.features/features) [feature :loaded?])))

(defn- throw-artefact-missing!
  [error-id feature declared extra-reason extra]
  (rf.error/throw-error!
    error-id
    'rf/make-frame
    (let [{maven      :maven
           require-ns :require} (get rf.features/feature-registry feature)]
      (str "this frame config declares " declared ", which "
           "requires " maven " on the classpath; "
           "add it to deps and require " require-ns " at app "
           "boot, BEFORE the frame is constructed, so " extra-reason))
    {:extra extra}))

(defn- refuse-absent-artefact-keys!
  "Refuse a config declaring a key an ABSENT optional artefact owns: routing's
  `:url-strategy` or `:url-bound? true`, resources' `:revalidate-on`. Storing a
  declaration nobody can check or honour is worse than a dependency error — a
  strategy would fail later deep in a consult point, a binding would skip the
  one-owner check, a revalidation would silently never happen. A contribution
  cannot speak for an artefact that is not loaded, so core probes the feature
  registry. Spec 002 §Lifecycle contributions."
  [id config]
  (when-let [declared (cond
                        (contains? config :url-strategy) :url-strategy
                        (true? (:url-bound? config))     :url-bound?)]
    (when-not (feature-loaded? :routing)
      (throw-artefact-missing! :rf.error/routing-artefact-missing :routing declared
                               "routing can check it at registration time."
                               {:frame id})))
  (when (and (contains? config :revalidate-on)
             (not (feature-loaded? :resources)))
    (throw-artefact-missing! :rf.error/resources-artefact-missing :resources
                             :revalidate-on
                             (str "the frame lifecycle can install the host "
                                  "focus/reconnect listeners.")
                             {:frame-id id}))
  nil)

(defn- run-preflight-contributions!
  "Run every contribution's `:preflight-config` against the final expanded
  config, in list order; the artefact owns the meaning of its keys, core the
  timing. Spec 002 §Lifecycle contributions."
  [id config]
  (doseq [{:keys [preflight-config]} @lifecycle-contributions
          :when preflight-config]
    (preflight-config id config)))

(defn- run-on-frame-registered!
  "Run every contribution's `:on-frame-registered` with `id`, in list order.

  Called at the END of BOTH `upsert-frame!` branches — first registration
  (after `:initial-events` ran and `:rf.frame/created` emitted) and
  re-registration (after the surgical config update and
  `:rf.frame/re-registered` emitted) — so the frame is live when each runs.
  Frames do not flow through `registrar/register!`, so this is the one frame
  (re-)registration extension point. It runs outside any teardown, so a
  failure propagates and the construction rolls back. Spec 002 §Lifecycle
  contributions."
  [id]
  (doseq [{:keys [on-frame-registered]} @lifecycle-contributions
          :when on-frame-registered]
    (on-frame-registered id)))

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

(defn- provisional-construction
  "`kind` is `:creation` (a first construction: owner-only visibility) or
  `:re-registration` (a staged revision of a live frame: visible to every
  actor) — see `frame-record-visible-to-current-actor?`."
  [kind owner revision]
  {:state    :provisional
   :kind     kind
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

  Surgical re-registration stages `:config`, `:generation`, `:descriptor-pool`,
  `:live-store?`, `:trace-policy-token`, and `:construction`, but `:generation`
  also has a valid generation-only writer (`set-generation!`) used by
  live-frame reprojection. Preserve the current record and restore the failed
  constructor's config, policy token, and construction metadata
  unconditionally. Restore its staged generation, and with it the pool that
  generation was resolved against, only while that slot is still the exact
  value this revision installed; an intervening reprojection wins and is
  retained together with the pool it re-resolved against, so the two never
  part. State-bearing containers and every other current slot are therefore
  preserved by identity.

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
                             :live-store? (:live-store? prior)
                             :trace-policy-token (:trace-policy-token prior)
                             :construction (:construction prior))
                (identical? (:generation f) (:generation staged))
                (assoc :generation (:generation prior)
                       :descriptor-pool (:descriptor-pool prior)))]
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
  destroys only the frame this call created and no-ops once a setup step has
  already destroyed it. The caller's reservation keeps every other same-id
  construction out until the transaction settles, so no successor can replace
  that frame in between."
  [id config policy-token owner]
  (when-not (contains? @frames id)
    (let [f (assoc (new-frame-record id config)
                   :trace-policy-token policy-token
                   :construction (provisional-construction
                                   :creation owner policy-token))]
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
  "Throw the typed `:rf.error/frame-id-taken` collision — the
  create-exclusive (`:rf.frame/must-create?`) primitive a fresh-isolated-frame
  test contract rests on. Raised when an exclusive construction for
  `id` meets an already-live FINAL frame under the same id. Same-id overlap with
  an in-flight transaction instead fails earlier as
  `:rf.error/frame-construction-in-progress`; in exclusive mode a pre-existing
  final frame is a COLLISION, never an adoption or surgical refresh (the
  fall-through ordinary construction takes). Never returns."
  [id]
  (rf.error/throw-error!
    :rf.error/frame-id-taken 'rf/make-frame
    (str "exclusive frame construction for " (pr-str id) " collided with an "
         "already-live frame under the same id. The caller requested "
         "create-exclusive construction (:rf.frame/must-create?), so a "
         "pre-existing final frame is a hard collision, "
         "not an adoption or a surgical refresh. Use a distinct frame id, or "
         "destroy the existing frame before constructing.")
    {:recovery :use-a-distinct-frame-id
     :extra    {:frame id}}))

(defn- deliver-construction-outcome!
  "Hand a successful construction's outcome to an optional `outcome-sink` (a
  `volatile!`) from INSIDE the construction transaction, as
  `{:token <installed incarnation token> :displaced <record this call
  replaced, nil on a first construction>}`. `make-frame` embeds the token on
  the returned frame VALUE without a post-return `frame-incarnation-token`
  re-sample — one a concurrent `destroy-frame!` + same-id reseat could race into
  handing back a successor's token — and refreshes the frame's cached
  subscriptions against the displaced record's generation, the exact value the
  surgical swap replaced. No-op when the caller passed no sink
  (`make-anon-frame-record!`, the `:rf/default` fixture, and the engine tests,
  which take the id return). Returns nil."
  [outcome-sink token displaced]
  (when outcome-sink
    (vreset! outcome-sink {:token token :displaced displaced}))
  nil)

(defn ^:no-doc upsert-frame!
  "PRIVATE frame ENGINE — atomic create-or-REFRESH (upsert) of the frame
  record for `id`. Called by `re-frame.live-frame/make-frame`
  (the public constructor `rf/make-frame`) and tightly-scoped engine tests
  ONLY — not a stable entry point.

  The name pins the REFRESH semantics — this engine upserts SEATED ids too.
  Per Spec 002 §Frame lifecycle:
  - If the id is unregistered, create the frame container, run the
    :initial-events setup steps synchronously (EP-0027), return the keyword.
  - If the id is already SEATED, perform a SURGICAL UPDATE / IDEMPOTENT
    REPLACEMENT (EP-0024 §Duplicate id policy): existing runtime state
    (app-db, sub-cache, queue) is preserved; only the metadata/config is
    replaced (+ the `:generation` and `:descriptor-pool` slots, which the
    caller threads as `:rf.frame/generation` / `:rf.frame/descriptor-pool`),
    and the recorded :initial-events is RE-RECORDED,
    never REPLAYED. Hot-reload Just Works. This IS re-construction — the
    Clojure re-def model: re-declaring the same id refreshes config while
    durable state survives. `:initial-events` replay is ONLY the opt-in
    full-replace composition (`destroy-frame!` then re-seat).

  SUBSTRATE OWNERSHIP: the `frames` registry is the ONE store a
  seated frame lives in. Seating a frame writes NO registrar row and NO
  source-store descriptor — a frame is a LIVE runtime object, not a program
  member, so seating/reseating must never bump the registration source-store
  generation (which would invalidate the resolved-image-generation cache,
  EP-0023) nor surface in image assembly. Frame introspection is
  `frame-meta` / `frame-ids`, not the registrar query API.

  Returns the frame `id` on a successful create OR re-register (the documented
  keyword return every caller + engine test relies on). The optional
  3-arity `outcome-sink` (a `volatile!`) is an OUT-channel: on success
  `deliver-construction-outcome!` hands it the exact installed incarnation
  token (the record's `:drain-lock`) and the record the call displaced, from
  INSIDE the construction transaction, so `make-frame` reads neither back from
  the registry afterwards. Passing no sink (the 2-arity) skips the delivery."
  ([id metadata] (upsert-frame! id metadata nil))
  ([id metadata outcome-sink]
  (let [;; The registry is keyed by the bare frame-id.
        config       (expand-preset metadata)
        ;; INTERNAL create-exclusive mode: when the caller
        ;; threads `:rf.frame/must-create?`, a live final frame already present at
        ;; decide throws typed `:rf.error/frame-id-taken` instead of the ordinary
        ;; fall-through to a surgical re-registration. An in-flight same-id
        ;; transaction is the distinct `:rf.error/frame-construction-in-progress`.
        ;; A test harness consumes exclusive mode to
        ;; keep plan frames FRESH + isolated (adopt/refresh become collisions).
        ;; A construction-only reserved key: read here, stripped from `config`
        ;; below so it never lands in the stored `:config` or a lifecycle trace.
        must-create? (true? (:rf.frame/must-create? config))
        config       (dissoc config :rf.frame/must-create?)
        ;; PREFLIGHT, before any container or process-global write: validate
        ;; the config's own keys, then normalize + validate `:initial-events`
        ;; into a vector of setup steps, so a bad declaration throws with no
        ;; frame left registered (EP-0027 §Failure — preflight validation).
        ;; The steps are dispatched once the container + config are installed
        ;; (first-registration branch below).
        _              (validate-frame-config! id config 'rf/make-frame)
        setup-steps    (normalize-initial-events (:initial-events config) 'rf/make-frame)
        ;; Optional-artefact PREFLIGHT at the frame-config COMMIT chokepoint,
        ;; against the FINAL expanded config: a key an absent artefact owns
        ;; fails loud, then each loaded artefact's contribution checks its own
        ;; keys — all BEFORE the record build, the trace-policy writes, the
        ;; `frames` swap, the `:initial-events` setup dispatch, and any trace
        ;; emit. So a malformed declaration fails with ZERO residue on a first
        ;; registration, and a failed RE-registration preserves every
        ;; previously committed value (config, generation, URL listener) and
        ;; emits no `:rf.frame/re-registered`.
        _              (refuse-absent-artefact-keys! id config)
        _              (run-preflight-contributions! id config)
        ;; Every refusal above has had its turn, so a `:sensitive` / `:large`
        ;; or artefact-missing key fails loud rather than being warned about.
        _              (warn-unknown-config-keys! id config)
        ;; One identity token per attempted config commit. Only a successful
        ;; create/re-register installs it. Auxiliary policy stores consult the
        ;; installed token inside their own atomic updates, so teardown and exact
        ;; rollback cannot publish against the wrong revision.
        policy-token   (fresh-trace-policy-token)
        ;; The frame-scoped TRACE POLICY writes (the suppression flag
        ;; + the `:rf.trace/events-retained` retention override) are process-global
        ;; stores SEPARATE from the `frames` registry, so the registry commit
        ;; alone does NOT linearize them. Run speculatively before the commit,
        ;; they would let a must-create LOSER — or a create/create loser — overwrite
        ;; the WINNER's trace policy for this id even though the loser installed
        ;; nothing, corrupting the live winner's tracing. So they ride the sole
        ;; transaction-owner branch via this thunk, and any admission loss is ZERO-WRITE
        ;; across every frame-owned store (the record AND its auxiliary
        ;; rf.trace/retention stores), while first-registration and ordinary
        ;; re-registration apply the winning config's policy EXACTLY ONCE.
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
          (rf.trace/set-frame-no-emit! id
                                    (true? (:rf.trace/frame-no-emit? applied-config))
                                    policy-current?)
          ;; Per Spec 009 §Retention contract: apply the per-frame
          ;; `:rf.trace/events-retained` override. When the key is absent the
          ;; frame inherits the process-default. Routed via late-bind so
          ;; production CLJS bundles (where trace.tooling is not loaded)
          ;; short-circuit cleanly — the trace-ring machinery is dev-only.
          (when-let [apply-retention! (rf.late-bind/get-fn
                                       :trace.tooling/apply-frame-events-retained-policy!)]
            (apply-retention! id
                              (contains? applied-config :rf.trace/events-retained)
                              (:rf.trace/events-retained applied-config)
                              policy-current?))))
        apply-trace-policy!
        (fn []
          (apply-trace-policy-for! config policy-token))]
    ;; SUBSTRATE OWNERSHIP: deliberately NO `registrar/register!`
    ;; here. A seated frame lives in the `frames` registry alone. A
    ;; `:frame` registrar row would leak into the registration SOURCE STORE
    ;; (`registrar/register!` → `source-store/record-descriptor!`), bumping the
    ;; source-store generation on every seat/reseat — invalidating the
    ;; resolved-image-generation cache (EP-0023) and marking the live-frame
    ;; reprojection dirty for a change that is NOT a registration-pool change.
    ;; Routing reads frame config via
    ;; `frame-meta` / `frame-ids`; the registrar `:frame` kind is reserved with
    ;; an intentionally EMPTY slot.
    ;; One exact per-id reservation is acquired BEFORE the decision probe, any
    ;; adapter callback, provisional registry publication, trace-policy write,
    ;; synchronous setup, lifecycle trace, or extension hook. It is released in
    ;; `finally` only after the exact provisional revision becomes final or has
    ;; been rolled back. Disjoint ids never share a monitor; same-id entry fails
    ;; promptly on both hosts.
    (call-with-frame-transaction!
      id :construction
      (fn [owner]
        ;; Destruction publishes its exact-incarnation marker and acquires this
        ;; same reservation under the drain gate. If construction acquired first,
        ;; destroy loses as a prompt idempotent no-op; if destroy marked first,
        ;; this exact-token check fails construction before any side effect.
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
                ;; There is no durable app-db classification install here:
                ;; the frame's policy resolves its `reg-classification`s
                ;; through its view at egress (Spec 015 §Static app-db
                ;; classification), so init-cascade trace is redacted too.
                ;; EP-0027 §Construction — FORBID handler-time frame construction.
                ;; Frames are created by the VIEW (frame-root, ENSURE) or at TOP LEVEL
                ;; (tests, boot, SSR per request); constructing a frame INSIDE an
                ;; event handler is not supported. The signal for "inside a
                ;; handler" is `rf.trace/*handler-scope*` being bound — the router
                ;; binds it for the duration of a handler's execution and ONLY
                ;; then. The container was already installed above; tear it back
                ;; down before throwing so a handler-time construction leaves NO
                ;; half-registered frame. Teardown owns `installed-token`, so it
                ;; removes exactly the incarnation we just installed; this
                ;; transaction's reservation keeps any other same-id
                ;; construction out.
                (when rf.trace/*handler-scope*
                  (destroy-frame! id installed-token)
                  (rf.error/throw-error!
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
                ;; Apply the winning config's frame-scoped trace
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
                (rf.trace/emit! :rf.frame :rf.frame/created
                             {:frame id :config (apply dissoc config
                                                       construction-input-keys)})
                (run-on-frame-registered! id)
                (if (finalize-frame-construction! id owner policy-token)
                  ;; Hand back this exact incarnation's token (the record's
                  ;; `:drain-lock` `try-install-new-frame!` installed and
                  ;; finalization preserved by identity) from inside the
                  ;; transaction so `make-frame` embeds exact authority with no
                  ;; post-return re-sample.
                  (do (deliver-construction-outcome! outcome-sink installed-token nil)
                      id)
                  (throw-frame-construction-in-progress!
                    id :lifecycle-dead :destruction))
                (catch #?(:clj Throwable :cljs :default) e
                  (destroy-frame! id installed-token)
                  (throw e)))
              ;; LOST the create: `try-install-new-frame!` returned nil — nothing
              ;; was installed.
              (if must-create?
                ;; Exclusive mode: a taken id is a hard COLLISION, not
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
          ;; composition).
          :else
          (let [observed      existing
                stored-config (apply dissoc config construction-input-keys)
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
                                      :descriptor-pool
                                      (get config :rf.frame/descriptor-pool)
                                      :live-store?
                                      (true? (get config :rf.frame/live-store?))
                                      :trace-policy-token policy-token
                                      :construction
                                      (provisional-construction
                                        :re-registration owner policy-token)))
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
                (rf.trace/emit! :rf.frame :rf.frame/re-registered
                             {:frame id :config stored-config})
                (run-on-frame-registered! id)
                (if (finalize-frame-construction! id owner policy-token)
                  ;; Idempotent re-registration PRESERVES the incarnation's
                  ;; `:drain-lock` by identity (the surgical swap-vals only
                  ;; replaces config/generation/pool/trace-policy/construction),
                  ;; so the staged record's token IS the still-live incarnation
                  ;; token — hand it back so a re-`make-frame` value carries the
                  ;; SAME authority as the original, with `prior`, the exact
                  ;; record the stage displaced.
                  (do (deliver-construction-outcome! outcome-sink (:drain-lock staged) prior)
                      id)
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
  resolve the same generation and a generation swap is observed by every
  holder of either. Pure map assembly; `id` is the public frame id (nil for a
  no-id direct value), `runnable-id` the record address.

  App-db seeding is a setup event (`:initial-events`, EP-0027), so the
  constructed value carries no `:rf.frame/initial-db` slot. There is no
  `:rf.frame/capabilities` slot either (EP-0026).

  A fresh/idempotent construction threads its EXACT incarnation
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
;; teardown so `record-teardown-failure!` can stamp `:frame` on a teardown
;; failure diagnostic whatever the failing step was handed.
(def ^:dynamic *destroying-frame-id* nil)

(def ^:dynamic ^:no-doc *destroy-halt*
  "The drain's boundary evidence for the destroy in flight — `{:frame-state
  :committed-at :trigger-event :dropped-count :last-event-id}` for a pending
  destroy, nil for an immediate one (see `run-destroy-recipe!`). Bound around
  the teardown so the epoch contribution's `:post-dead` member can build the
  terminal `:halted-destroy` record. Spec 002 §Destroy."
  nil)

;; Per-destroy accumulator of teardown failures, bound to a fresh atom by
;; `destroy-frame!` for the duration of the teardown walk. Every failure conj's
;; one entry through the shared `record-teardown-failure!` boundary —
;; `{:hook <contribution id or core step key> :phase <phase> :exception <ex>
;; :where <catch-boundary>}`, `:phase` present for a contribution member.
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

;; SENSE: event-pipeline-run — bound around ONE dequeued event's
;; run (`process-event!`), not the reactive graph (a run = one event's
;; traversal).
;;
;; Pre-run frame-state snapshot of the in-flight dequeued event, bound by
;; the router around `process-event!` (see `re-frame.router/run-one-pass!`).
;; Its presence is what `guard-open-drain!` reads as "an event drain is still
;; open on this stack". nil outside a drain.
(def ^:dynamic *run-frame-state-before* nil)

(defn guard-open-drain!
  "The SHARED open-event-drain guard — the `:rf.error/flush-in-open-epoch` signal
  (Spec 006 §Render-batch finalization; Spec 009 §Error event catalogue). Reject a
  synchronous registry-flush forced from `where` while a frame's run-to-completion
  event drain is STILL OPEN: flushing there could publish partially-settled queued
  update/commit work (a torn read/render).

  It lives in CORE because it closes over no view state whatever — only
  `*run-frame-state-before*` and the frame accessors above — so every substrate
  whose synchronous flush can publish a render phase reaches THIS one fn
  without requiring anything from another view artefact. One guard,
  not one copy per substrate. No in-repo substrate calls it; the guard lives
  here because the law is core's rather than any substrate's. Contrast a
  convergence bound, which cannot be shared this way: it closes over the
  substrate's OWN cell registry, so each substrate owns its own and the
  `:where` slot disambiguates them.

  `*run-frame-state-before*` is bound around the current event-pipeline run, so
  it answers for the run on this stack whatever the registry says about the
  frame. Throws BEFORE the caller touches its registry (no partial flush); a
  no-op outside any drain."
  [where]
  (when (some? *run-frame-state-before*)
    (let [frame-id (frame-value->id *current-frame*)]
      (rf.error/throw-error!
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
  forge ownership of a fresh same-id incarnation.

  Around the handler pipeline the router also records the dequeued dispatch
  envelope under `:envelope`, read through `current-event-envelope`."
  nil)

(defn ^:no-doc call-with-event-owner-token
  "Run `f` with the exact-incarnation authority captured by the router.

  The raw dynamic var is private so authored handlers and interceptors cannot
  forge the framework's binding. This narrow runner is the only binding seam; a
  nested authored call cannot change the outer event pipeline's authority once
  it returns.

  The 4-arity also records the dequeued `envelope`; the router passes it around
  the handler pipeline only. It rides the SAME binding map rather than a second
  dynamic var, so the per-event cost is one more entry in a map that is built
  anyway, and any nested owner scope drops it by construction."
  ([frame-id token f]
   (call-with-event-owner-token frame-id token nil f))
  ([frame-id token envelope f]
   (binding [*event-owner* {:frame    frame-id
                            :token    token
                            :envelope envelope}]
     (f))))

(defn ^:no-doc current-event-envelope
  "Return the dispatch envelope of the event whose handler pipeline is running
  on frame `frame-id`, or nil outside one (or when the in-flight event belongs
  to another frame).

  For framework code that runs in a handler BODY and queues a child dispatch,
  where no fx ctx `(:envelope m)` is in reach. The motivating case is the machine
  completion carriers. Such code hands the result to `re-frame.fx/child-dispatch!`
  so the child inherits run propagation (Spec 002 §Run propagation). The
  envelope is the one the router DEQUEUED. The child's lane is not the
  envelope's business: a dispatch made from inside the in-flight event joins
  the frame's internal lane (Spec 002 §Run-to-completion)."
  [frame-id]
  (let [owner *event-owner*]
    (when (= frame-id (:frame owner))
      (:envelope owner))))

(defn ^:no-doc current-event-owner-token
  "Return the router-bound event owner token to framework internals."
  []
  (:token *event-owner*))

(defn ^:no-doc current-event-owner-frame-id
  "Return the unforgeable dequeue-time owner frame id. Unlike ambient frame
  scope, this is unaffected by nested `with-frame` rebinding in authored code."
  []
  (:frame *event-owner*))



(defn ^:no-doc event-continuation-live?
  "True while work for incarnation `token` of frame `id` may proceed: the
  incarnation is live and its teardown has not begun. A pending destroy has not
  begun its teardown. The thread running the teardown is the exception — its
  own steps (`:on-destroy` and that event's cascade among them) still act for
  the incarnation they are closing."
  [id token]
  (and (frame-incarnation-live? id token)
       (or (not (frame-incarnation-closing? id token))
           (= id *destroying-frame-id*))))

(defn- record-teardown-failure!
  "Record a best-effort teardown failure on BOTH Spec 009 observability
  channels, then return nil so the caller swallows the throw and teardown
  continues. Every teardown catch site funnels through here, so each failure
  reports identically:

    1. ALWAYS-ON axis (EP-0008 R1) — conj the entry onto the per-destroy
       `*teardown-hook-failures*` accumulator, which `destroy-frame!` flushes
       ONCE as the bounded `:rf.error/frame-teardown-failed` report. Collapsing
       to one report keeps an SSR per-request-destroy × M req/s failure from
       flooding the production error shipper, and keeps the which-failed-
       together correlation.
    2. DIAGNOSTIC channel (EP-0008 R2) — emit the per-failure
       `:rf.warning/teardown-hook-exception` trace (DCE'd in production) at its
       causal position, stamped with the in-flight `*destroying-frame-id*`.

  `entry` is `{:hook <contribution id or core step key> :where <catch
  boundary>}`, plus `:phase` for a contribution member."
  [entry ex]
  (let [entry (assoc entry :exception ex)]
    (when-let [acc *teardown-hook-failures*]
      (swap! acc conj entry))
    (rf.trace/emit-error! :rf.warning/teardown-hook-exception
                          (assoc entry :frame *destroying-frame-id*))
    nil))

(defn- safe-call-contribution!
  "Call contribution `id`'s member for `phase` under the best-effort teardown
  boundary: a throw is recorded under the contribution's id and phase and
  swallowed, so one failing artefact cannot block the rest of teardown.
  Returns the member's value, nil on a throw."
  [id phase thunk]
  (try (thunk)
       (catch #?(:clj Throwable :cljs :default) ex
         (record-teardown-failure! {:hook id :phase phase :where :safe-call-contribution!}
                                   ex))))

(defn- safe-teardown-step!
  "Run one of core's own teardown steps under the same best-effort boundary,
  recorded under `step-key`. No step may escape the recipe mid-flight: an
  escape before the dead flip would leave the frame LIVE and half torn down
  with `:on-destroy` already run, and a retry would re-run the whole recipe and
  re-fire `:on-destroy`."
  [step-key thunk]
  (try (thunk)
       (catch #?(:clj Throwable :cljs :default) ex
         (record-teardown-failure! {:hook step-key :where :safe-teardown-step!} ex))))

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
  `rf.trace/emit-error!`.

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
  (when-let [dispatch-on-error! (rf.late-bind/get-fn :error-emit/dispatch-on-error)]
    (dispatch-on-error!
      :rf.error/on-destroy-handler-exception
      on-destroy                         ;; the :on-destroy event vector
      (when (vector? on-destroy) (first on-destroy)) ;; event-id
      id                                 ;; the frame being torn down
      exception
      0                                  ;; elapsed-ms — not a timed dispatch here
      (rf.interop/now-ms)))
  ;; Dev-only trace path — DCEs under `:advanced` + `goog.DEBUG=false`.
  (rf.trace/emit-error! :rf.error/on-destroy-handler-exception
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
  step (the `:pre-dead` contributions, sub-cache disposal, the `:post-dead`
  contributions, `:rf.frame/destroyed`, registry dissoc) MUST still run so the
  frame is fully torn down.

  Mechanism: the router catches handler throws and converts them to
  `:rf.error/handler-exception` — the internal teardown cascade does not
  re-throw. To surface the throw as the dedicated `:rf.error/on-destroy-handler-
  exception` category, we install a TRANSIENT listener
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

  This mirrors the swallow-then-continue shape of `safe-call-contribution!`
  but ALSO emits a structured error event — the user's `:on-destroy` is
  application code; its failure is a first-class diagnostic event."
  [id expected-token f]
  (when-let [on-destroy (-> f :config :on-destroy)]
    (when-let [run-destroy-event (rf.late-bind/get-fn
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
            register     (rf.late-bind/get-fn :error-emit/register-error-listener!)
            remove-cb    (rf.late-bind/get-fn :error-emit/unregister-error-listener!)
            ;; A UNIQUE per-destroy listener key — NOT a constant.
            ;; A nested / overlapping destroy (an `:on-destroy` that destroys a
            ;; different frame, Spec 002) would otherwise clobber the
            ;; outer destroy's listener under a shared key and drop the outer's
            ;; dedicated `:on-destroy-handler-exception`. A fresh key per call
            ;; gives each extent its own listener.
            listener-k   [::on-destroy-throw-watch
                          id
                          (swap! on-destroy-watch-counter inc)]
            ;; Claims (returns `true` for) only the record it captures, which
            ;; is re-emitted below as `:rf.error/on-destroy-handler-exception`;
            ;; any other record the `:on-destroy` dispatch produces stays
            ;; unclaimed and reaches the dev console fallback.
            listener     (fn [record]
                           (when (and (= :rf.error/handler-exception (:rf.error/id record))
                                      (= id (:frame record))
                                      (nil? @captured))
                             (reset! captured record)
                             true))]
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

(defn- run-destroy-members!
  "Call `(on-frame-destroyed id phase)` for every contribution joining
  `phase`, in list order, each under the best-effort boundary. Returns the
  `[contribution-id fn]` pairs the members handed back for after the dissoc.
  Spec 002 §Lifecycle contributions."
  [id phase]
  (into []
        (keep (fn [{cid :id phases :phase f :on-frame-destroyed}]
                (when (and f (contains? phases phase))
                  (let [after (safe-call-contribution! cid phase #(f id phase))]
                    (when (fn? after) [cid after])))))
        @lifecycle-contributions))

(defn- run-pre-dead-members!
  "Run the `:pre-dead` members inside the frame's own registration resolution
  (`:live-frame/call-with-frame-resolution`, the view the router binds around
  each of the frame's events), so a machine definition, `:exit` action or
  effect handler a member resolves comes from the frame's own image. A teardown
  is no event's work and binds no view of its own: without this, two frames
  whose images select different definitions of one machine id would run
  whichever definition the destroying stack's view, or each id's latest
  registration, names. The id resolves to the claimed incarnation: the destroy
  holds the id's reservation and the record stays registered until the dissoc."
  [id]
  (if-let [with-resolution (rf.late-bind/get-fn :live-frame/call-with-frame-resolution)]
    (with-resolution id #(run-destroy-members! id :pre-dead))
    (run-destroy-members! id :pre-dead))
  nil)

(defn- tear-down-without-machines!
  "Stand in for the machines artefact's `:pre-dead` member when that artefact
  is absent and so contributes nothing: for each machine snapshot in
  runtime-db, abort its actor-owned HTTP and emit
  `:rf.machine.lifecycle/destroyed` with `:reason :parent-frame-destroyed`.
  With no machines artefact there are no `:exit` cascades to run and no actor
  handlers to unregister. Spec 005 §Cross-Spec Interactions §1.

  The abort is FRAME-EXACT. An actor address is frame-LOCAL (Spec 014 §Abort
  on actor destroy §Frame scope), so the hook is called with the frame being
  destroyed as well as the address; its frame-less ANY-FRAME arity would sweep
  that address in every frame and abort a sibling frame's live requests."
  [id]
  ;; EP-0001: machine snapshots are durable runtime-db state.
  (let [container  (runtime-db-container id)
        rt         (when container (rf.substrate.adapter/read-container container))
        machines   (-> rt :rf.runtime/machines :snapshots)
        abort-http (rf.late-bind/get-fn :http/abort-on-actor-destroy)]
    (doseq [[machine-id snapshot] machines]
      (when abort-http
        ;; `id` is the frame under destruction; passing it
        ;; selects the hook's frame-scoped arity.
        (try (abort-http id machine-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      (rf.trace/emit! :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed
                   {:frame      id
                    ;; The reaped actor's live INSTANCE address;
                    ;; `:machine-id` is reserved for the registered TYPE. Must
                    ;; match the machines-artefact orchestrator emit
                    ;; (`lifecycle-fx/frame-destroy/emit-lifecycle-destroyed!`)
                    ;; so the registrar-substrate row carries one tag shape
                    ;; whether or not the machines artefact is loaded.
                    :actor-id   machine-id
                    :last-state (:state snapshot)
                    :reason     :parent-frame-destroyed}))))

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

(defn- claim-frame-destroy!
  "Claim teardown authority for `expected-token`'s live incarnation of `id`.

  The token check, shared per-id transaction reservation,
  `destroying-frames` publication, and pre-claim queue cutoff run under the
  candidate incarnation's drain lock. A foreign construction owner makes this
  the established prompt nil no-op; construction rollback on the current host
  thread joins its own owner. For an immediate destroy the queue cutoff is
  atomic on the router atom: every not-yet-dequeued envelope moves to the
  private `:destroy-claim-dropped-count` evidence slot and the live queue
  becomes empty before the lock releases. The later internal `:on-destroy`
  cascade therefore cannot drain ordinary work behind its cleanup seed. When
  the incarnation's drain is on the current stack the claim is PENDING
  (`::destroy-pending?` on the returned record): the queue is left for the
  drain to cut at the next event boundary, where it also runs the teardown.

  `call-serialized-with-drain!` may have captured the frame record before it
  blocked, so the thunk revalidates the token under the lock: a stale expected
  token claims nothing. A marker already standing for `id` means a destroy of
  this incarnation is in flight — re-entrant from its own teardown, or a second
  request joining a pending one — so the claim is a nil no-op. Returns the
  claimed frame record, or nil."
  [id expected-token]
  (when-let [candidate (frame id)]
    (when (identical? expected-token (:drain-lock candidate))
      (call-serialized-with-drain!
        id
        (fn []
          (when-let [current (frame id)]
            (when (and (identical? expected-token (:drain-lock current))
                       (not (contains? @destroying-frames id)))
              (when-let [transaction-owner
                         (or (when (owner-holds-frame-id?
                                     *frame-transaction-owner* id)
                               *frame-transaction-owner*)
                             (try-claim-frame-transaction! id :destruction))]
                (let [router   (:router current)
                      ;; This incarnation's drain is on the current stack — its
                      ;; own event, or another frame's handler reached through a
                      ;; nested cross-frame `dispatch-sync` inside it. The
                      ;; destroy is PENDING: the queue stays, the event finishes,
                      ;; and the drain cuts the queue and tears down at the next
                      ;; event boundary.
                      pending? (current-thread-is-drainer? current)]
                  ;; Serialize claim publication + queue cutoff with the
                  ;; router's scheduling/release monitor. A submitter that
                  ;; linearized before this section is included in the dropped
                  ;; queue; ordinary drains after publication observe the claim
                  ;; and halt.
                  (locking router
                    (swap! destroying-frames assoc id
                           {:token             expected-token
                            :transaction-owner transaction-owner
                            :pending?          pending?})
                    (when-not pending?
                      (swap! router
                             (fn [{:keys [queue internal destroy-claim-dropped-count]
                                   :as state}]
                               (let [dropped (+ (count queue)
                                                (count internal)
                                                (or destroy-claim-dropped-count 0))]
                                 ;; Preserve the claim-time count until an
                                 ;; actual drain observes the claim. A public
                                 ;; post-claim/pre-dead arrival may enqueue, but
                                 ;; that exact drain drops it before invocation
                                 ;; and atomically combines both counts in one
                                 ;; interruption report.
                                 (cond-> (-> state
                                             (assoc :queue rf.interop/empty-queue
                                                    :internal rf.interop/empty-queue
                                                    :scheduled? false)
                                             (dissoc :destroy-claim-report-emitted?))
                                   (pos? dropped)
                                   (assoc :destroy-claim-dropped-count dropped))))))
                    (assoc current
                           ::destroy-transaction-owner transaction-owner
                           ::destroy-pending?          pending?)))))))))))

(defn- release-frame-destroy-claim!
  "Remove `id`'s destroy marker. The destroy removes it before releasing the
  id's reservation, so no same-id incarnation can have claimed the id."
  [id]
  (swap! destroying-frames dissoc id))

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
  loaded, e.g. a frame with subs but no subscribe path) disposes directly,
  best-effort, so teardown never leaks reactions."
  [id f]
  (when-let [cache (:sub-cache f)]
    (if-let [dispose-all! (rf.late-bind/get-fn :subs.cache/dispose-all-for-frame-destroy!)]
      (dispose-all! cache id)
      (do
        (doseq [[_k entry] @cache]
          (when-let [r (:reaction entry)]
            (try (rf.interop/dispose! r)
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
      (try (rf.interop/dispose! proj)
           (catch #?(:clj Throwable :cljs :default) _ nil)))))

(defn- emit-frame-destroyed-trace!
  [id]
  (rf.trace/emit! :rf.frame :rf.frame/destroyed
               {:frame id}))

(defn- dissoc-frame!
  ;; The frame record is keyed by the bare frame-id; removing it is a plain
  ;; dissoc. Also clears the frame's commit-epoch counter so
  ;; the side table stays bounded by live frames (a fresh same-id incarnation
  ;; restarts at 0 — the incarnation change is what the port's `current?` /
  ;; frame-identity checks detect, not the counter value).
  [id]
  (swap! frames dissoc id)
  (swap! frame-commit-epochs dissoc id))

(defn- run-after-dissoc!
  "Call each fn a `:post-dead` member handed back, in list order, once the
  frame has left the registry; the epoch contribution publishes its terminal
  evidence here. The destroy still holds the id's reservation, so no same-id
  incarnation exists yet.

  A throw is recorded under the contribution's id and `:after-dissoc` with
  STRUCTURAL delivery: it streams live to every listener and is retained by no
  ring and captured by no epoch buffer. Ordinary delivery would push it under
  the bare id — under the destroying event's dispatch-id when the destroy runs
  inside another frame's event — and so recreate a ring for a frame that is
  gone, which nothing frees and a later same-id incarnation would inherit."
  [after-dissoc]
  (doseq [[cid after] after-dissoc]
    (try
      (after)
      (catch #?(:clj Throwable :cljs :default) ex
        (rf.trace/call-with-structural-delivery
          #(record-teardown-failure! {:hook  cid
                                      :phase :after-dissoc
                                      :where :safe-call-contribution!}
                                     ex))))))

(declare run-destroy-recipe!)

(defn destroy-frame!
  "Tear down a frame. Spec 002 §Destroy holds the contract and §Lifecycle
  contributions the phases artefacts join. The recipe:

    1. claim-frame-destroy!  — claim the exact incarnation under its drain
                               serialization, take the id's transaction
                               reservation, and cut the ordinary queue before
                               lifecycle-dead. The reservation is held until
                               the teardown has finished, on an exceptional
                               exit too, so no same-id incarnation coexists
                               with any part of it.

  When the incarnation's drain is on the current stack — its own event, or
  another frame's handler reached through a nested cross-frame `dispatch-sync`
  inside it — step 1 records a PENDING destroy and returns: the event in flight
  finishes as an ordinary event (its `:db`, flows, `:fx` and `:ok` epoch land),
  and at the next event boundary the drain drops both lanes, reports them in
  one `:rf.frame/drain-interrupted`, and runs steps 2–9 (`run-pending-destroy!`).
  A second destroy of the same incarnation joins the pending request.
  Otherwise steps 2–9 run before this call returns:

    2. :on-destroy           — the user event and its same-frame descendants,
                               on the destroy-owned private queue.
    3. :pre-dead members     — every contribution joining `:pre-dead`, in list
                               order, inside the frame's own registration
                               resolution, against a live container (the
                               machines `:exit` cascade). With no machines
                               artefact, `tear-down-without-machines!` stands
                               in.
    4. mark-frame-destroyed! — flip `:lifecycle :destroyed?` while the claimed
                               incarnation token still matches, under its
                               `:drain-lock`.
    5. reactive disposal     — every cached subscription, each emitting
                               `:rf.sub/dispose` with reason `:frame-destroy`,
                               then the partition projections.
    6. :post-dead members    — every contribution joining `:post-dead`, in
                               list order. A member may hand back a fn for
                               step 9.
    7. `:rf.frame/destroyed`, then the frame's trace ring and no-emit flag are
       released.
    8. dissoc-frame!         — remove the record: the `frames` registry is the
                               one store a seated frame lives in.
    9. after-dissoc          — the fns step 6 handed back, in order; the
                               destroy's terminal publication.

  Steps 2–9 are best-effort: a throw is recorded and teardown continues, and
  one always-on `:rf.error/frame-teardown-failed` report lists every failure
  (Spec 009 §Error event catalogue).

  An ordinary dispatch that linearizes while the claimed incarnation remains
  lifecycle-live — for a pending destroy, the destroying event's own dispatches
  to its frame included — may enter its real queue. The next exact-incarnation
  drain check removes it before handler, effects, or child dispatch and combines
  it with claim-time removals in the one interruption report. Only the exact-
  token private cleanup cascade may execute after teardown begins. Dispatch / subscribe
  against the dead or absent frame recovers (dispatch no-ops, subscribe returns
  nil) and emits the always-on :rf.error/frame-destroyed diagnostic.

  Re-entrancy: if `destroy-frame!` is called for `id` while
  an outer `destroy-frame!` for the same `id` is still on the stack
  (e.g. the user's `:on-destroy` handler itself calls `destroy-frame!`,
  or a machine `:exit` cascade does so), the re-entrant call is a
  silent no-op — the outer call's teardown is already in flight and
  re-running the recipe would re-fire `:on-destroy`, re-run the
  machine cascade, and corrupt the half-torn-down state. Idempotent
  destroy is the general pattern (a destroyed frame's `(frame id)`
  lookup already returns nil, so a *later* `destroy-frame!` short-
  circuits at the outer `when-let`); the in-flight guard closes the
  RE-ENTRANT window before `mark-frame-destroyed!` flips the flag.

  EP-0024: the target may be a frame-id KEYWORD or a frame VALUE
  (`rf/make-frame`'s return token). A value is normalized to its id via
  `frame-value->id` so the whole recipe keys the ONE registry's record;
  `dissoc-frame!` IS the forget (the resolved generation rides the
  record, dropped with it — one registry, no separate forget hook).

  The two-argument arity is incarnation-owned teardown: it is a silent no-op
  unless `expected-incarnation-token` is still the live frame's identity token.
  The check is revalidated after acquiring that incarnation's `:drain-lock`,
  and the liveness flip CAS-checks the same token under the same lifecycle gate,
  so a stale teardown can never destroy a fresh same-id incarnation.

  The one-argument arity chooses its authority from the TARGET:
  a fresh-construction frame VALUE carries its EXACT incarnation token
  (`frame-value-incarnation-token`), so the arity delegates to the
  incarnation-owned two-argument arity with it — an owner (`with-new-frame`,
  Story replay, SSR per-request) that destroys the value it created tears down
  ONLY that incarnation, never a same-id successor reseated in between (a stale
  carried token no-ops). A frame-id KEYWORD, or a token-less derived-read VALUE,
  carries no authority and stays ADDRESS-directed: it pins whatever incarnation
  is currently live under the id at invocation and delegates. So keyword/ID
  destruction is explicitly address-directed while a returned frame value
  has coherent lifecycle-token semantics."
  ([target]
   ;; A fresh-construction frame VALUE carries EXACT incarnation
   ;; authority (`:rf.frame/incarnation-token` — the installed `:drain-lock`).
   ;; Consume it so an owner's cleanup (`with-new-frame`, Story replay, SSR
   ;; per-request) destroys ONLY the incarnation it created; a stale carried
   ;; token no-ops through the two-argument arity (which revalidates liveness),
   ;; leaving a same-id successor reseated in between intact. A frame-id KEYWORD
   ;; or a token-less derived-read VALUE has no carried authority, so it stays
   ;; ADDRESS-directed — pin whatever incarnation is currently live under the id.
   (if-let [carried (frame-value-incarnation-token target)]
     (destroy-frame! target carried)
     (let [id (frame-value->id target)]
       (when-let [expected-token (frame-incarnation-token id)]
         (destroy-frame! target expected-token)))))
  ([target expected-incarnation-token]
   ;; Accept a frame VALUE or a frame-id keyword. Normalize a value to its id so
   ;; every keyed teardown step targets the record; a keyword passes through
   ;; unchanged. The registry is keyed by the bare frame-id, so every keyed
   ;; teardown step (the in-flight guard, `mark-frame-destroyed!`,
   ;; `dissoc-frame!`) targets the frame-id-keyed record directly.
   (let [id (frame-value->id target)]
     ;; `claim-frame-destroy!` acquires the shared per-id transaction and
     ;; records the DESTROYING incarnation's token (the live record's
     ;; `:drain-lock`) under that SAME lock, after revalidating the expected
     ;; token. A second destroy of a claimed incarnation — re-entrant from its
     ;; own teardown, or joining a pending request — finds the marker and is a
     ;; silent no-op. A pending claim returns here at once; the drain runs the
     ;; recipe at the next event boundary (`run-pending-destroy!`).
     (when-let [f (claim-frame-destroy! id expected-incarnation-token)]
       (when-not (::destroy-pending? f)
         (run-destroy-recipe! id expected-incarnation-token f nil))))
   nil))

(defn- run-destroy-recipe!
  "Run steps 2–9 of `destroy-frame!` for the incarnation `claim-frame-destroy!`
  claimed: `f` is its claimed record, carrying the reservation owner under
  `::destroy-transaction-owner`. Called at once for an immediate destroy, and by
  the drain at the destroying event's boundary for a pending one.

  `halt` is the boundary evidence for the terminal `:halted-destroy` epoch
  record — `{:frame-state <committed frame-state> :committed-at <ms>
  :trigger-event <the event the drain would have dequeued next>
  :dropped-count <n> :last-event-id <id of the destroying event>}` — or nil for
  an immediate destroy, which commits no such record. It is bound as
  `*destroy-halt*` for the teardown."
  [id expected-incarnation-token f halt]
      (let [transaction-owner (::destroy-transaction-owner f)
            ;; EP-0008 R1: per-destroy accumulator for teardown failures,
            ;; flushed by the `finally` below as ONE always-on
            ;; `:rf.error/frame-teardown-failed` report. Held in a side atom so
            ;; a mid-teardown abort still flushes the entries gathered so far.
            hook-failures     (atom [])]
       ;; A teardown is no event's work, so it runs under no event owner, even
       ;; when another frame's event is on the stack: an immediate destroy called
       ;; from frame B's event, or a pending one whose boundary B reached by
       ;; `dispatch-sync`ing into this frame. Under B's token the exact writes
       ;; this frame's machine `:exit` effects issue are refused, so a sibling
       ;; actor an `:exit` destroys stays in runtime-db and the walker runs its
       ;; `:exit` again. With no owner they take the bare write, which lands in
       ;; this frame's container: the destroy holds the id's reservation and the
       ;; record stays registered until the dissoc. The `:on-destroy` cascade
       ;; binds this frame's own owner.
       (binding [*destroying-frame-id*    id
                 *destroy-halt*           halt
                 *teardown-hook-failures* hook-failures
                 *event-owner*            nil]
         (try
        (fire-on-destroy-event! id expected-incarnation-token f)
        (when-not (lifecycle-contribution :machines)
          (safe-teardown-step! :frame/tear-down-without-machines
                               #(tear-down-without-machines! id)))
        (safe-teardown-step! :frame/pre-dead #(run-pre-dead-members! id))
        ;; Flip the liveness bit under the frame's OWN `:drain-lock` (the ONE
        ;; frame-owned lifecycle gate, via `call-serialized-with-drain!`) so a
        ;; concurrent cold serialized op — a Tool-Pair write — linearizes
        ;; against this destroy. That op pins the incarnation token
        ;; (`frame-incarnation-token`) and REVALIDATES it under the same lock,
        ;; so it either ran BEFORE this flip or observes the destroyed
        ;; incarnation AFTER it and refuses. The frame is still LIVE at this
        ;; point (the flip has not yet run), so the id-keyed helper resolves +
        ;; locks the correct incarnation. Reentrant on ANY same-thread hold: a mid-drain
        ;; destroy runs on the drainer thread, and a destroy issued from inside
        ;; a cold serialized op (e.g. a Tool-Pair write body) runs under that
        ;; op's `:serialized-holder` — either way the flip runs directly rather
        ;; than self-deadlocking, and the `frame-disposed-for-drain?` interrupt
        ;; seam still applies.
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
        (let [after-dissoc (run-destroy-members! id :post-dead)]
          (emit-frame-destroyed-trace! id)
          ;; Per Spec 009 §Per-frame trace rings: release the destroyed
          ;; frame's event-keyed ring AFTER `:rf.frame/destroyed` emits (that
          ;; trace is frameless and bypasses the ring), so no residual trace
          ;; events leak across the frame lifecycle; a `:post-dead` member's
          ;; failure warning pushed onto the ring is cleared with it. Then the
          ;; frame-no-emit flag, the teardown counterpart of construction's
          ;; `rf.trace/set-frame-no-emit!`. Serialized with successful upsert
          ;; publication: the frame is already lifecycle-dead, so a delayed
          ;; publisher that enters afterwards fails its live-token predicate,
          ;; and one that entered before the flip completes first and these
          ;; clears win last.
          (serialize-frame-trace-policy!
           (fn []
             (safe-teardown-step!
               :trace.tooling/release-frame-ring!
               #(when-let [release! (rf.late-bind/get-fn :trace.tooling/release-frame-ring!)]
                  (release! id)))
             (rf.trace/clear-frame-no-emit-for! id)))
          ;; EP-0024: there is ONE `frames` registry, and the dissoc IS the
          ;; forget — the frame's resolved generation and the descriptor pool
          ;; it was resolved against ride the record.
          (dissoc-frame! id)
          (run-after-dissoc! after-dissoc))
        nil)
        (finally
          ;; EP-0008 R1 — the FINALLY-shaped flush of the always-on teardown
          ;; report (Spec 009 §Emit-safety): even if a step aborts the walk
          ;; mid-recipe, the entries collected so far are already in the atom
          ;; and still ship. Reached via late-bind (`error-emit` → `elision` →
          ;; `frame` is a load cycle); the flush itself is wrapped so a fault in
          ;; the always-on substrate can never strand the in-flight marker.
          (let [failures @hook-failures]
            (when (seq failures)
              (when-let [emit-report (rf.late-bind/get-fn
                                       :error-emit/dispatch-frame-teardown-report)]
                (try
                  ;; The frame was dissociated before this finally, so the
                  ;; report carries no frame authority: it reaches the
                  ;; corpus-wide listeners and the process-default sink.
                  (emit-report id failures (rf.interop/now-ms) false)
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))
          ;; Release only once teardown and its terminal publication have
          ;; finished — here, on an exceptional exit too — so no same-id
          ;; incarnation coexists with any part of this one. The marker goes
          ;; first: a construction admitted by the release must find the id
          ;; free of it. A construction rollback JOINED its outer :construction
          ;; owner and leaves that reservation for the constructor's own finally.
          (release-frame-destroy-claim! id)
          (when (= :destruction (:kind transaction-owner))
            (release-frame-construction! transaction-owner)))))))

(defn ^:no-doc pending-destroy?
  "True when a PENDING destroy (Spec 002 §Destroy) is recorded for exactly the
  incarnation `token` of frame `id` and its teardown has not begun. The drain
  asks this at each event boundary."
  [id token]
  (let [{marker-token :token pending? :pending?} (get @destroying-frames id)]
    (boolean (and pending? (some? token) (identical? token marker-token)))))

(defn ^:no-doc run-pending-destroy!
  "Run the teardown of the PENDING destroy recorded for incarnation `token` of
  frame `id`, if one is recorded; a no-op otherwise. The drain calls this at the
  destroying event's boundary, after it has dropped both lanes and reported
  them, while it still holds the frame's drain serialization. `halt` is the
  boundary evidence `run-destroy-recipe!` threads to the epoch layer.

  Flipping the marker out of `:pending?` is the one compare-and-set that starts
  the teardown, so the recipe runs at most once per claim. The claim already
  holds the per-id reservation, so the record under `id` is still the claimed
  incarnation; should it not be, the claim and reservation are released and
  nothing is torn down."
  [id token halt]
  (let [[before _] (swap-vals! destroying-frames
                               (fn [claims]
                                 (let [m (get claims id)]
                                   (if (and (:pending? m)
                                            (identical? token (:token m)))
                                     (assoc claims id (assoc m :pending? false))
                                     claims))))
        marker (get before id)]
    (when (and (:pending? marker) (identical? token (:token marker)))
      (let [owner (:transaction-owner marker)
            f     (get @frames id)]
        (if (and f (identical? token (:drain-lock f)))
          (run-destroy-recipe! id token
                               (assoc f ::destroy-transaction-owner owner)
                               halt)
          (do (release-frame-destroy-claim! id)
              (when (= :destruction (:kind owner))
                (release-frame-construction! owner))))))
    nil))

;; ---- :rf/default — TEST-ONLY fixture helper -------------------------------
;;
;; Per Spec 002 §`:rf/default` is the conventional app-frame id: `:rf/default`
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
  "TEST-ONLY fixture helper. Register the conventional `:rf/default` app frame if
  absent (idempotent), so a test that pins `*current-frame*` to
  `:rf/default` and dispatches ambiently has a frame to land on.

  NOT a runtime path — `init!` does NOT call this (per Spec 002
  §`:rf/default` is the conventional app-frame id: the runtime never synthesises
  a default frame). Application / SSR boot code that wants a default-named
  app frame registers it explicitly via `(rf/make-frame {:id :rf/default …})`."
  []
  (when-not (frame :rf/default)
    (upsert-frame! :rf/default {:doc "Test-fixture app frame (the conventional app-frame id; not a runtime floor)."})))
