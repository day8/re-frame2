(ns re-frame.ui.reactive
  "S2b reactive core of the compiled-view substrate — the ViewCell, the
  render-side probe/record protocol, the 8-step layout-commit reconciler,
  and the three-state lifecycle. Host-agnostic (`.cljc`): the React glue
  that drives it lives in `re-frame.ui.viewcell` (`.cljs`), but every
  ownership decision — kept-check, transactional stage/rollback, evidence
  comparison, publish/release ordering, lifecycle facts — is here, so the
  whole reconciler is graft-checked headlessly against the REAL observation
  port on both hosts (node + JVM, plain-atom adapter).

  Sole reactive consumer of the internal observation port
  (`re-frame.substrate.observation`) — the six operations
  `resolve-target` / `probe` / `acquire!` / `current?` / `read` /
  `release!` (Spec 006 §The internal observation port). Per the S2a
  handoff: `read` returns `:frame-epoch` / `:registry-epoch` ADDITIVELY
  (no second probe at commit step 5); `resolve-target`'s `site-ctx` shape
  is `{:query-v … :frame pin? :override {:value :override-id :version}?}`;
  the value-movement `on-change` watch channel exists only on watchable
  hosts, so on a headless (non-watchable) host movement is caught at the
  commit evidence comparison (step 5), not by a callback — for EVERY acquired
  lease, RETAINED as well as staged (rf2-vxgfnd.39), since a retained site has
  no watch to self-correct there.

  ## The ViewCell (03 §2)

  Every lexical `(sub …)` in a view is a compile-indexed site; all of a
  view's sites share ONE ViewCell — one `useSyncExternalStore`, one scalar
  revision snapshot, one coalesced notification per render batch (the
  drain-quiescence boundary, NOT per epoch — see §Drain coalescing below).
  Render probes WITHOUT
  ownership (resolve-target + probe, no ref-count / watch / cache node);
  the layout commit acquires the CAPTURED targets. Abandoned renders
  (StrictMode double-render, time-sliced tear-off) acquire NO OWNERSHIP —
  the 10k-abandoned-renders-retain-zero-OWNERSHIP property is structural,
  mirroring the port's S-3 §5 cold-probe exit criterion. Each finished render
  returns its immutable capture beside the host element; an abandoned render's
  pair becomes unreachable instead of publishing speculative state to the
  ViewCell.

  ## The render capture

  A render pass records each executed site's resolved target + probe
  evidence + value into a per-pass CAPTURE (ownership-free), keyed by the
  compiler-issued lexical site id. Equal queries at distinct sites therefore
  own distinct balanced leases while still sharing the subscription node.
  The exact immutable capture travels beside its host element into the
  selected layout/passive effect closure; it is never published speculatively
  by render. Layout reconciles the committed dependency set against that
  selected capture — idempotently, so StrictMode's mount→unmount→mount effect
  replay is naturally balanced.

  ## `sub` value stabilization (03 §2, I-8)

  A site returns its PRIOR EXACT value (identical reference) when the new read
  is `rf=` to that lexical site's last committed value — so an `rf=`-stable
  read does not repaint downstream. Query-object reuse is likewise per-site:
  a parametric read may retain its exact prior query object without collapsing
  an equal query executed at another compiler-issued site.

  ## Drain coalescing + `flush!` scope (S2d — 03 §3 invariant 6; Spec 006
  §Epoch finalization)

  The sixth frozen invariant, stated correctly: THE RENDER BATCH BOUNDARY
  IS DRAIN QUIESCENCE, NOT EPOCH CLOSE. An event/frame EPOCH is a
  write-side commit + diagnostic-evidence unit (one per dequeued event —
  Spec 002 §Drain versus event); it is NOT a React render boundary. A
  single run-to-completion drain may settle SEVERAL queued events, each
  committing its OWN epoch record, before the host regains control — and
  every one of those epochs coalesces into ONE render batch.

  The mechanism: sub deltas mark their cell dirty through constant-work
  `on-change` (never compute — I-5), carrying the moving frame's epoch as
  CAUSE EVIDENCE only. The cell enters a module-level DIRTY REGISTRY
  exactly once (a set, deduped by cell identity); a re-mark while already
  pending FOLDS IN regardless of its epoch tag — the pending flag is the
  coalescing key, the epoch tag is NEVER a second key. On CLJS one
  coalesced flush is armed per drain on the host MICROTASK queue
  (`queue-microtask!` — `js/queueMicrotask`, NOT `goog.async.nextTick`,
  which is a macrotask): the microtask checkpoint runs after the
  synchronous run-to-completion drain unwinds and BEFORE the next paint, so
  the flush fires strictly after drain quiescence — never between two queued
  events of the same drain, and always before a torn frame can show
  (rf2-vxgfnd.40); the JVM headless host has no async render loop, so it
  auto-schedules NOTHING and
  drains via the EXPLICIT `flush!` (07 §2's only flush idiom; SSR is
  one-shot) — one honest option per host. Either way, N epochs committed
  in one drain advance each dirty cell's revision ONCE and let React
  perform ONE read/render batch.

  Render SEPARATION is therefore per DRAIN, not per epoch: two epochs
  settled in one drain share one render batch; two epochs settled in
  SEPARATE drains (distinct external events, the host regaining control
  between them) render separately — NO render count may be inferred from
  the number of event/frame epochs. The push-economics bench's queued-
  cascade gate (a parent event that queues further events, proving one
  ViewCell notification and one React render for the whole batch —
  G-5/G-13) is wired with the bench in S2f, not here.

  SYNCHRONOUS forcing is scoped over that registry:

    - `(flush-frame! frame-id)` — the internal frame scope — flushes every root
      observing that frame (each dirty cell whose committed deps include
      the frame).
    - the GLOBAL all-roots flush is the TEST-ONLY `ui.test/flush!`
      spelling (`flush-pending!` here). It is the sole public test flush;
      there is no public production `re-frame.ui/flush!`.

  A scoped flush leaves out-of-scope cells pending — no epoch work leaks
  across roots. Flush is reentrancy-SAFE BY CONSTRUCTION: `flush-scope!`
  atomically drains-then-notifies (`swap-vals!`), so a notify-triggered
  re-entrant flush finds the registry already drained and cannot
  double-advance a cell. The DEV-tier `:rf.error/flush-in-open-epoch`
  signal — the DX guard naming a re-entrant flushSync-into-an-open-epoch
  misuse (03 §11; Spec 006 §Epoch finalization) — is REFERENCED, not
  emitted by `ui.test/flush!`, before this registry is touched, with the
  Spec 009 catalogue row carrying the active frame + frame epoch.

  ## The slice-scoped probe memo (S2d item 3 — 03 §3; Spec 006 §The
  slice-scoped probe memo)

  `sub-read` threads a SLICE-SCOPED pure memo (`obs/make-slice-memo`)
  into every `probe`, so N sibling rows probing one query compute shared
  derivation parents once per synchronous execution slice, not once per
  row (the first-mount fan-out mitigation). The handle is created lazily
  on the first probe of a slice and cleared on the next event-loop tick
  (`interop/next-tick` — a MACROTASK, GC hygiene only, not a
  correctness-before-paint boundary), so an abandoned slice's table is
  unreachable garbage. The memo is an ECONOMY, never an authority — the commit
  evidence comparison (step 5) corrects any staleness before paint."
  (:require [re-frame.error :as error]
            [re-frame.features :as features]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.resource-lease-owner :as lease-owner]
            [re-frame.router :as router]
            [re-frame.substrate.observation :as obs]
            [re-frame.subs.override-schema :as override-schema]
            [re-frame.ui.eq :as eq]
            [re-frame.ui.lease-descriptor :as ui-lease]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Observation-port ABI lockstep (Spec 006 §The internal observation port)
;;
;; This ns is the SOLE reactive consumer of the internal observation port, and
;; from ABI v2 onward it RELIES on the `read` evidence axis `:node-key` — the
;; reincarnation-identity `evidence-moved?` consumes below (rf2-vxgfnd.14/.93).
;; So it PINS the ABI it compiled against and asserts it AT LOAD: a core that
;; predates the `:node-key` read axis is a BOOT ERROR
;; (`:rf.error/observation-port-version-mismatch`, always-on + fanned through
;; the production error-emit axis), never a silently-missed reincarnation
;; correction. Core and re-frame2-ui release on a lockstep train.
;; ---------------------------------------------------------------------------

(def ^:const expected-observation-port-abi
  "The observation-port ABI version this reactive consumer is written against —
  v2 (rf2-vxgfnd.14): `read` on a node lease carries `:node-key`, which
  `evidence-moved?` compares to classify a same-id frame REINCARNATION across
  the render→commit gap as MOVEMENT even when node-version + frame/registry
  epochs coincide. Asserted against the live port at load
  (`assert-port-abi-version!`) so artifact drift fails loud at boot."
  2)

(obs/assert-port-abi-version! expected-observation-port-abi)

;; ---------------------------------------------------------------------------
;; The static override door (03 §3)
;;
;; `resolve-target` consumes a Story-override HIT off the site-ctx. On the
;; JVM there is no React context: `ui.test/render` binds this door
;; explicitly (`{:sub-overrides {query value}}`) — "one honest option, not
;; a pretended same mechanism" (07 §2). On CLJS the landed React-context
;; carriage seeds the same door from the mounted override Provider:
;; `re-frame.ui.sub-overrides/use-current` reads the nearest Provider map
;; with ordinary `useContext`, and `re-frame.ui.viewcell` binds it around
;; each compiled body.
;; ---------------------------------------------------------------------------

(def ^:dynamic *sub-overrides*
  "Override door: a map of query-vector → pinned value, or nil. A HIT
  resolves the site to a `:story-override` target — the pinned value IS the
  resolution (no node), and commit acquires a STATIC lease
  (`:owned? false`, callback-free). Seeded by the JVM `ui.test/render`
  door (an explicit `binding`) and, on CLJS, by the landed React-context
  carriage — `re-frame.ui.sub-overrides` reads the nearest mounted override
  Provider and `re-frame.ui.viewcell` binds this var around each compiled
  body.

  ## The change token (opaque to the observation port — the target ABI)

  `resolve-override` LOWERS a HIT to the port's opaque
  `{:override-id :version}` change token. The port
  (`re-frame.substrate.observation`) never interprets either token, but the
  two obey a SPLIT equality law: `:override-id` is slot identity, compared
  by plain `=`; `:version` is the movement token, compared by the frozen
  `rf=` law (the port's core-local `node-value=` spelling), under which
  NaN-to-NaN RETAINS — the observable counterexample plain `=` gets wrong
  (an `=`-compared NaN version would retarget forever). This artefact's
  private lowering:

    - `:override-id` ← the QUERY — the override's stable slot identity
      (an `=` compare), so two overrides never collide and a site dedups
      by its slot.
    - `:version`     ← the VALIDATED value — the movement token, so
      `current?` (frozen `rf=` on version) retargets EXACTLY when the
      surfaced value MOVES under `rf=`, and retains when it does not
      (including a NaN replaced by NaN).

  Because `resolve-override` re-runs (and re-validates) every render, the
  semantics fall out of the token:

    - `rf=`-equal provider replacement (same validated value, NaN-to-NaN
      included) → version unchanged under the movement law → the
      kept-check retains the static lease (no retarget).
    - nested providers → the CLOSEST enclosing override wins (the innermost
      `*sub-overrides*` binding / React-context map) → its value is the token.
    - value movement under `rf=` → version differs → the site retargets to
      a fresh static lease carrying the new value.
    - HMR / schema change → the override is re-validated against the
      CURRENT registration each render; a schema that now rejects a
      previously-valid value flips the surfaced value to nil, moving the
      version and retargeting the site to nil."
  nil)

(defn- override-sub-meta
  "The target sub's registration metadata for override-value validation,
  resolved through the CURRENT frame's IMAGE generation when a scope is in
  effect (so a multi-image frame validates against its own image's schema —
  parity with the subscription resolution `re-frame.substrate.observation`
  performs), else the global registrar (absence-is-default). Non-throwing;
  nil when the entry sub is unregistered (validation then no-ops)."
  [query-v]
  (if-some [frame-id (frame/resolve-current-frame)]
    (live-frame/call-with-frame-resolution
      (live-frame/frame-resolution-target frame-id)
      (fn [] [(registrar/lookup :sub (first query-v)) frame-id]))
    [(registrar/lookup :sub (first query-v)) nil]))

(defn- resolve-override
  "Resolve a Story `:sub-overrides` HIT for `query`, or nil on a miss.

  The ENTIRE consult — the `*sub-overrides*` dynamic-var read, the schema
  validation, and the token lowering — sits behind `interop/debug-enabled?`
  so a PRODUCTION build (Story absent) carries ZERO per-sub branch, no
  dynamic-var read, and none of these bytes on the subscription render
  path: the whole body DCEs under `:advanced` + `goog.DEBUG=false`, and
  `sub-read`'s `(some? override)` folds to false (rf2-vxgfnd.21).

  On a HIT the pinned value is schema-validated against the target sub's
  declared output `:schema` through the SHARED
  `override-schema/validate-sub-override!` primitive — the SAME registered
  validator + `:rf.error/schema-validation-failure {:where :sub-override}`
  emission + recover-to-nil the Reagent-family `subscribe` path applies, so
  the compiled view can never surface a state the sub's own schema says is
  impossible. A nil-valued (or validation-failed) HIT stays a HIT (distinct
  from a miss). The returned map is the port's opaque change token (see
  `*sub-overrides*`)."
  [query]
  (when interop/debug-enabled?
    (when-some [m *sub-overrides*]
      (when (contains? m query)
        (let [raw            (get m query)
              [sub-meta fid] (override-sub-meta query)
              v              (override-schema/validate-sub-override!
                               raw query sub-meta fid)]
          {:value v :override-id query :version v})))))

;; ---------------------------------------------------------------------------
;; Ambient render capture
;;
;; Single-threaded on both hosts WITHIN one synchronous render; a compiled
;; view's children are ELEMENTS (rendered later by the host), never
;; synchronously nested calls, so renders never nest on one thread. ACROSS
;; threads the JVM Tier-1 host gives no such guarantee (parallel test
;; runners, fixture futures), so the slot is a DYNAMIC var — thread-local
;; under `binding`, exactly like every neighbouring render-path scope
;; (`*sub-overrides*`, `frame/*current-frame*`) — and two concurrent renders
;; can neither cross-record sites into each other's captures nor false-throw
;; the duplicate-sid guard (rf2-1llvoh). On single-threaded CLJS `binding`
;; compiles to the same save/restore the old atom hand-rolled.
;; ---------------------------------------------------------------------------

(def ^:private ^:dynamic *ambient* nil) ;; {:cell <cell> :capture <volatile>} | nil

(defn- fresh-capture
  [generation]
  {:generation generation
   :order []
   :by-site {}})

(defn- target-key
  [target]
  (case (:kind target)
    :subscription   [:sub (:frame-id target) (:query target)]
    :story-override [:override (:override-id target)]))

(defn- record-site
  "Add one compiler-indexed lexical site to `cap`. Site identity—not target
  equality—is the ownership key, so two equal queries remain two owners. A
  repeated execution of one sid in a render is a compiler-contract violation
  and fails loudly: rendering a later value while committing the first target
  would otherwise create an incoherent dependency."
  [cap sid query target ev value]
  (if (contains? (:by-site cap) sid)
    (error/throw-error!
      :rf.error/ui-tree-malformed
      're-frame.ui.reactive/sub-read
      (str "compiler lexical site id " (pr-str sid)
           " executed more than once in one render capture — each finite "
           "reactive occurrence must have a distinct stable id")
      {:extra {:site-id sid :query query}})
    (-> cap
        (update :order conj sid)
        (assoc-in [:by-site sid] {:query query
                                  :target target
                                  :evidence ev
                                  :value value}))))

(defn site-records
  "The immutable `{sid -> render observation}` in capture `cap` (internal
  compiler/runtime/test seam)."
  [cap]
  (:by-site cap))

;; ---------------------------------------------------------------------------
;; The slice-scoped probe memo (S2d item 3; 03 §3)
;;
;; ONE memo handle per synchronous execution slice, shared by every probe
;; in the slice so sibling rows compute shared derivation parents once
;; (the first-mount fan-out mitigation). Created lazily on the first probe
;; of a slice; released on the next event-loop tick (`interop/next-tick` —
;; a macrotask, which is fine here: this is GC hygiene, not a
;; correctness-before-paint boundary) so an abandoned slice's table is
;; unreachable garbage. The memo is an ECONOMY only — commit step 5
;; corrects any staleness before paint — so a single module holder (probes
;; never nest across slices synchronously) is sufficient.
;; ---------------------------------------------------------------------------

(def ^:private slice-memo* (atom nil))

(defn- current-slice-memo
  "The current slice's probe memo handle — reused across every probe of
  this synchronous slice, created lazily. On CLJS a fresh slice mints a
  fresh handle and the old one is released on the next event-loop tick
  (`interop/next-tick` — `goog.async.nextTick`, a macrotask firing after the
  synchronous render pass; GC hygiene only, not a before-paint boundary).
  On the JVM `next-tick` is a concurrent executor, not a microtask, so a
  timer-driven clear would race a synchronous render; there the handle is
  invalidated by the memo's own `(frame, frame-epoch, registry-epoch)` tag
  on the next epoch (`slice-memo-table!`) and cleared between fixtures by
  `reset-scheduler!`. The memo is an ECONOMY — commit step 5 corrects any
  staleness before paint — so the coarser JVM lifetime is harmless."
  []
  (or @slice-memo*
      (let [h (obs/make-slice-memo)]
        (reset! slice-memo* h)
        ;; Release OUR handle at slice end; a later slice may already have
        ;; installed a newer one, so clear only while ours is still current.
        #?(:cljs
           (interop/next-tick (fn [] (compare-and-set! slice-memo* h nil))))
        h)))

;; ---------------------------------------------------------------------------
;; The ViewCell
;; ---------------------------------------------------------------------------

(deftype ViewCell [state]
  ;; Opaque host object with IDENTITY equality (deftype default). `state`
  ;; is an atom of:
  ;;
  ;;   {:view-id vid
  ;;    :generation g            ; view-body generation (HMR); commit rejects
  ;;                             ;   a stale capture (step 1)
  ;;    :lifecycle :fresh|:connected|:disconnected|:dead
  ;;    :root incarnation|nil    ; owning root-incarnation token — a per-mount
  ;;                             ;   identity that SURVIVES an Activity hide, so
  ;;                             ;   root teardown reaps a cell hidden before its
  ;;                             ;   window (rf2-vxgfnd.85; see `root-cells`)
  ;;    :disconnect-provisional? bool ; DEV-only (rf2-vxgfnd.44): a just-emitted
  ;;                             ;   :disconnected interval that has NOT yet
  ;;                             ;   settled past its synchronous commit. A
  ;;                             ;   reconnect while still provisional is a
  ;;                             ;   same-tick StrictMode dev replay (no hide);
  ;;                             ;   `settle-disconnect!` clears it. false/absent
  ;;                             ;   in production (no StrictMode double-invoke)
  ;;    :committed {sid -> {:query exact-query :target target :value value
  ;;                        :lease lease|nil}}
  ;;                             ; lexical site records. `:lease nil` survives
  ;;                             ; disconnect for exact query/value reuse and
  ;;                             ; hidden-cell frame attribution; absence means
  ;;                             ; the site was conditional/dropped
  ;;    :resources {...}        ; ABSENT on no-lease cells. Lease-capable
  ;;                             ; wrappers install one lazy nested state map
  ;;                             ; carrying desired/capture/reservations/held
  ;;    :extra-frame-incarnations {frame-id #{token ...}}
  ;;                             ; ABSENT on no-lease cells; generic frame
  ;;                             ; teardown index maintained with :resources
  ;;    :on-teardown fn         ; ABSENT on no-lease cells; capability cleanup
  ;;    :revision  int           ; get-snapshot returns this (useSyncExternalStore)
  ;;    :dirty?    bool          ; pending-notification flag (drain coalescing)
  ;;    :evidence  ev|nil        ; DEBUG-only bounded causal evidence for the
  ;;                             ;   pending window (see `fold-evidence`); nil
  ;;                             ;   in production (elided) + between flushes
  ;;    :listeners {k -> fn}     ; useSyncExternalStore subscribers
  ;;    :intervals [interval]}   ; lifecycle facts (dev/tool; 03 §4)
  )

(defn cell?
  [x]
  (instance? ViewCell x))

(defn- state
  [^ViewCell cell]
  (.-state cell))

(defn make-cell
  "Mint a fresh ViewCell for view `view-id` at body `generation` (default
  0). Starts `:fresh` — the first successful commit connects it."
  ([view-id] (make-cell view-id 0))
  ([view-id generation]
   (->ViewCell
     (atom
      (cond-> {:view-id        view-id
               :generation     generation
               :lifecycle      :fresh
               :root           nil
               :disconnect-provisional? false
               :committed      {}
               :revision       0
               :dirty?         false
               :evidence       nil
               :listeners      {}
               :intervals      []})))))

;; ---------------------------------------------------------------------------
;; Dev-only view slots — stable shell + exact body revision (03 §10)
;; ---------------------------------------------------------------------------
;;
;; ONE per-view slot is the whole HMR authority.  It owns the stable component
;; identities, current implementation descriptor, revision store/listeners, and
;; the hook-signature → remount decision.  There is deliberately no parallel
;; registrar cache or render-time var lookup.
;;
;; Every successful registration advances BODY REVISION, including a
;; same-signature edit.  Only a changed hook signature advances REMOUNT
;; GENERATION.  The stable outer shell observes body revision; its stable inner
;; body is keyed by remount generation.  Thus a same-signature update runs the
;; new body on the existing Fiber, while an incompatible hook edit remounts that
;; Fiber exactly once without changing the public shell identity.
;;
;; The slot is dev-only in the CLJS emitter: production takes a literal
;; `goog.DEBUG=false` branch directly to `React.memo`, leaving this registry and
;; all listener/dynamic-descriptor machinery unreachable to Closure DCE.

(defonce ^:private view-generations
  ;; view-id -> one atomic private HMR slot. `defonce` survives namespace
  ;; reload. `reset-scheduler!` deliberately NEVER clears it: already-exported
  ;; stable shells retain this slot as their live render authority.
  (atom {}))

(defn ensure-view-shells!
  "Return the stable shell pair for `view-id`, creating it once with
  `make-shells` when absent. `make-shells` returns `{:outer x :inner y}`.

  The winning pair is published in the SAME per-view slot later used for the
  descriptor/revisions/listeners. A losing speculative pair is unreachable
  garbage (possible only under a concurrent JVM test; the CLJS host is
  single-threaded)."
  [view-id make-shells]
  (let [slots (swap! view-generations update view-id
                     (fn [slot]
                       (if (:hmr-outer slot)
                         slot
                         (let [{:keys [outer inner]} (make-shells)]
                           (merge {:hmr-registered?       false
                                   :hmr-body-revision     -1
                                   :hmr-remount-generation 0
                                   :hmr-listeners         {}}
                                  slot
                                  {:hmr-outer outer
                                   :hmr-inner inner})))))]
    (let [slot (get slots view-id)]
      {:outer (:hmr-outer slot)
       :inner (:hmr-inner slot)})))

(defn prepare-view-descriptor!
  "Prepare a descriptor/revision publication without notifying mounted shells.

  This is the first half of the DEV view-registration transaction. It makes
  the new descriptor and revisions visible BEFORE `registrar/register!` fires
  its synchronous registration/replacement hooks, so no hook can observe a new
  manifest paired with an old (or nil) render/comparator. `publication-token`
  is private transaction identity; listeners may still subscribe while the
  registrar call is in progress without invalidating the transaction.

  Call `commit-view-descriptor!` after registrar success or
  `rollback-view-descriptor!` if it throws."
  [view-id hook-signature descriptor]
  (let [publication-token (atom nil)
        [old-slots slots]
        (swap-vals! view-generations update view-id
                    (fn [slot]
                      (let [registered? (boolean (:hmr-registered? slot))
                            changed?    (and registered?
                                             (not= hook-signature
                                                   (:hmr-hook-signature slot)))]
                        (-> (merge {:hmr-body-revision      -1
                                    :hmr-remount-generation 0
                                    :hmr-listeners          {}}
                                   slot)
                            (assoc :hmr-registered? true
                                   :hmr-hook-signature hook-signature
                                   :hmr-descriptor descriptor
                                   :hmr-publication-token publication-token)
                            (update :hmr-body-revision inc)
                            (cond-> changed?
                              (update :hmr-remount-generation inc))))))
        prepared (get slots view-id)]
    {:view-id view-id
     :publication-token publication-token
     :before (get old-slots view-id)
     :prepared prepared}))

(defn commit-view-descriptor!
  "Commit a prepared view publication and notify mounted shells exactly once.

  A re-entrant registration for the same id supersedes an older transaction;
  only the transaction whose token is still current may notify. Returns the
  complete current slot snapshot."
  [{:keys [view-id publication-token]}]
  (let [[old-slots slots]
        (swap-vals! view-generations update view-id
                    (fn [slot]
                      (if (identical? publication-token
                                      (:hmr-publication-token slot))
                        (dissoc slot :hmr-publication-token)
                        slot)))
        current-before (get old-slots view-id)
        committed? (identical? publication-token
                                (:hmr-publication-token current-before))
        published (get slots view-id)]
    (when committed?
      (doseq [listener (vals (:hmr-listeners published))]
        (listener)))
    published))

(defn rollback-view-descriptor!
  "Roll back a prepared descriptor after registrar failure.

  Rollback is conditional on transaction identity, so it cannot overwrite a
  newer re-entrant registration. Revisions are globally monotone even across
  failure: restore the prior descriptor at a fresh body revision and notify
  once, making every render of the provisional descriptor stale. If the failed
  candidate exposed a different hook shape, restoration advances remount
  generation again. A failed first registration becomes an unavailable
  tombstone at a fresh revision. Listener subscriptions made in flight are
  retained. Returns true only when this publication performed the compensation;
  a stale publication returns false."
  [{:keys [view-id publication-token before]}]
  (let [[old-slots slots]
        (swap-vals!
         view-generations update view-id
         (fn [slot]
           (if (identical? publication-token
                           (:hmr-publication-token slot))
             (let [prior (or before {})
                   prior-registered? (boolean (:hmr-registered? prior))
                   shape-observed?
                   (or (not prior-registered?)
                       (not= (:hmr-hook-signature prior)
                             (:hmr-hook-signature slot)))
                   restored
                   (-> prior
                       (assoc :hmr-listeners (:hmr-listeners slot {})
                              :hmr-body-revision
                              (inc (:hmr-body-revision slot))
                              :hmr-remount-generation
                              (cond-> (:hmr-remount-generation slot)
                                shape-observed? inc)))]
               (if prior-registered?
                 restored
                 (assoc restored
                        :hmr-registered? false
                        :hmr-hook-signature nil
                        :hmr-descriptor nil)))
             slot)))
        current-before (get old-slots view-id)
        rolled-back? (identical? publication-token
                                  (:hmr-publication-token current-before))
        restored (get slots view-id)]
    (when rolled-back?
      (doseq [listener (vals (:hmr-listeners restored))]
        (listener)))
    rolled-back?))

(defn register-view-descriptor!
  "Publish one descriptor directly through the same prepare/commit path used
  by the client registrar transaction. This is the headless/JVM test seam.

  Body revision advances on every call. Remount generation advances only when
  an already-registered slot changes hook signature."
  [view-id hook-signature descriptor]
  (-> (prepare-view-descriptor! view-id hook-signature descriptor)
      (commit-view-descriptor!)))

(defn register-view-generation!
  "Headless test/JVM seam for the same registration decision as
  `register-view-descriptor!`. Returns BODY REVISION. It never creates a second
  authority: the existing descriptor (if any) is republished in the one slot."
  [view-id hook-signature]
  (:hmr-body-revision
   (register-view-descriptor!
    view-id hook-signature
    (get-in @view-generations [view-id :hmr-descriptor]))))

(defn view-generation
  "The current BODY REVISION for `view-id` (0 when never registered). Kept
  under the historical internal name while callers migrate; this is no longer
  the remount key."
  [view-id]
  (max 0 (get-in @view-generations
                 [view-id :hmr-body-revision]
                 0)))

(defn registered-view-revision
  "The authoritative body revision for a successfully registered `view-id`, or
  nil when the test/direct-call path has no registered slot."
  [view-id]
  (let [slot (get @view-generations view-id)]
    (when (:hmr-registered? slot)
      (:hmr-body-revision slot))))

(defn view-remount-generation
  "The current hook-incompatibility remount key for `view-id` (tool/test read)."
  [view-id]
  (get-in @view-generations [view-id :hmr-remount-generation] 0))

(defn view-descriptor
  "The current implementation descriptor for `view-id` (dev render path)."
  [view-id]
  (get-in @view-generations [view-id :hmr-descriptor]))

(defn view-shells
  "The stable `{:outer :inner}` identities for `view-id` (tool/test read)."
  [view-id]
  (let [slot (get @view-generations view-id)]
    {:outer (:hmr-outer slot)
     :inner (:hmr-inner slot)}))

(defn subscribe-view!
  "Subscribe `listener` to successful body publications for `view-id`; return
  an idempotent unsubscribe thunk. This is the stable shell's minimal
  useSyncExternalStore store."
  [view-id listener]
  (let [k (gensym "rf-ui-hmr-listener")]
    (swap! view-generations assoc-in
           [view-id :hmr-listeners k] listener)
    (fn unsubscribe []
      (swap! view-generations update-in
             [view-id :hmr-listeners] dissoc k))))

(defn advance-generation!
  "Advance a LIVE `cell` to a newer BODY REVISION before capture. A revision
  bump makes an in-flight capture from the prior body stale at commit step 1.
  Monotone: a no-op unless `generation` exceeds the cell's current revision."
  [^ViewCell cell generation]
  (let [st (state cell)]
    (when (> generation (:generation @st))
      (swap! st assoc :generation generation)))
  cell)

(defn generation
  "The cell's current view-body generation (tool/test read)."
  [^ViewCell cell]
  (:generation @(state cell)))

;; ---- read + query stabilization ---------------------------------------------

(defn sub-read
  "The one bridge `(sub query)` lowers to on both hosts. The compiler calls
  `(sub-read sid query)`; `sid` is the stable lexical ownership key. Resolves
  the site's target (override door → ambient frame), probes ownership-free,
  and returns the value:

    - Inside a live cell render (ambient capture present), RECORDS the site
      by sid. A fresh candidate query that is `rf=` to that same site's prior
      query is replaced by the PRIOR EXACT query object BEFORE override/target
      resolution. The value is then stabilized against the same site's prior
      exact value.
    - Outside a cell, either arity is a one-shot headless read: freshly probe,
      with no ownership/capture. The legacy one-argument arity is deliberately
      illegal under an ambient capture so a compiler/test cannot silently lose
      lexical identity.

  Fail-loud rides the port: `:rf.error/no-such-sub` on an unknown entry
  sub, `:rf.error/frame-destroyed` against a destroyed frame."
  ([query]
   (if (some? (:cell *ambient*))
     (error/throw-error!
       :rf.error/ui-tree-malformed
       're-frame.ui.reactive/sub-read
       (str "one-argument sub-read reached an active ViewCell capture — "
            "compiled render reads must carry their lexical site id")
       {:extra {:query query}})
     (sub-read nil query)))
  ([sid query]
   (let [{:keys [cell capture]} *ambient*
         _ (when (and (some? cell) (nil? sid))
             (error/throw-error!
               :rf.error/ui-tree-malformed
               're-frame.ui.reactive/sub-read
               (str "nil lexical site id reached an active ViewCell capture — "
                    "compiled render reads must carry a non-nil site id")
               {:extra {:query query}}))
         prior-record (when (some? cell)
                        (get (:committed @(state cell)) sid))
         prior-query  (:query prior-record)
         query*       (if (and prior-record (eq/rf= query prior-query))
                        prior-query
                        query)
         ;; Query stabilization MUST precede both the override door and target
         ;; resolution: adapters may key their caches by exact query identity.
         override     (resolve-override query*)
         site-ctx     (cond-> {:query-v query*}
                        (some? override) (assoc :override override))
         target       (obs/resolve-target site-ctx)
         ev           (obs/probe target (current-slice-memo))
         v            (:value ev)]
     (if (some? cell)
       (let [prior-value (:value prior-record)
             v*          (if (and prior-record (eq/rf= v prior-value))
                           prior-value
                           v)]
         (vswap! capture record-site sid query* target ev v*)
         v*)
       v))))

(defn- record-resource-site
  "Record one active compiler-indexed resource declaration in `cap`.
  Resource ownership is lexical-site keyed: equal descriptors at two sites
  deliberately remain two independent owners."
  [cap sid record]
  (if (or (contains? (:resource-by-site cap) sid)
          (contains? (:by-site cap) sid))
    (error/throw-error!
     :rf.error/ui-tree-malformed
     're-frame.ui.reactive/lease-site
     (str "compiler lexical site id " (pr-str sid)
          " executed more than once in one render capture")
     {:extra {:site-id sid
              :descriptor-keys (-> record :descriptor
                                   error/diag-value-summary :keys)
              :resource-id (get-in record [:descriptor :resource])}})
    (-> cap
        (update :resource-order (fnil conj []) sid)
        (assoc-in [:resource-by-site sid] record))))

(defn lease-site
  "Compiler-only render bridge for `(ui/lease descriptor)`.

  Validates the closed descriptor before recording anything. `nil` is an
  inactive declaration and records no desired ownership. An active site pins
  the ambient frame's exact incarnation in the immutable render capture; it
  never mints an owner or dispatches. Layout commit accepts/rejects that exact
  capture, and the later passive resource reconciler alone changes ownership."
  [sid descriptor]
  (let [descriptor (ui-lease/validate-descriptor! descriptor)
        {:keys [cell capture]} *ambient*]
    (when (or (nil? cell) (nil? capture) (nil? sid))
      (error/throw-error!
       :rf.error/ui-tree-malformed
       're-frame.ui.reactive/lease-site
       "compiled lease-site must execute once with a non-nil lexical id inside its ViewCell capture"
       {:extra {:site-id sid
                :descriptor-summary (error/diag-value-summary descriptor)}}))
    (when (some? descriptor)
      (let [frame-id    (frame/require-current-frame!
                         :lease {:where 're-frame.ui/lease})
            frame-token (frame/frame-incarnation-token frame-id)]
        (when (or (nil? frame-token)
                  (frame/frame-incarnation-closing? frame-id frame-token))
          (error/throw-error!
           :rf.error/frame-destroyed
           're-frame.ui.reactive/lease-site
           (str "resource lease site targeted absent or closing frame "
                (pr-str frame-id))
           {:extra {:frame frame-id :site-id sid}}))
        (vswap! capture record-resource-site sid
                {:descriptor descriptor
                 :frame-id frame-id
                 :frame-token frame-token})))
    nil))

(defn with-capture
  "Run `thunk` (a compiled view body) under a fresh ambient capture and return
  `[host-element capture]`.

  Ownership-free: the render acquires NO ref-count, watch, or cache node, so an
  abandoned render (a thunk whose result the host discards — StrictMode
  double-render, time-sliced tear-off) leaks ZERO ownership and publishes ZERO
  shared capture state. React retains the pair only on the selected finished
  Fiber: its layout-effect closure commits that exact render's capture. A later
  speculative render therefore cannot replace the input of an earlier render
  that React actually commits. StrictMode's effect mount→cleanup→remount reuses
  the selected effect closure, so both mounts reconcile the same immutable
  capture without a shared slot.

  The ambient slot is a dynamic var: `binding` gives the save/restore for
  free on single-threaded CLJS and THREAD-LOCAL isolation on the JVM, so two
  concurrent Tier-1 renders own disjoint captures (rf2-1llvoh)."
  [^ViewCell cell thunk]
  (let [cap (volatile! (fresh-capture (:generation @(state cell))))]
    (binding [*ambient* {:cell cell :capture cap}]
      (let [el (thunk)]
        [el @cap]))))

;; ---- useSyncExternalStore contract ------------------------------------------

(defn get-snapshot
  "The scalar revision snapshot — a monotonically-advancing integer, stable
  by `=`/`===` between notifications. `useSyncExternalStore`'s getSnapshot."
  [^ViewCell cell]
  (:revision @(state cell)))

(defn subscribe
  "Register `listener` (a zero-arg fn the host re-renders through) under a
  fresh key; returns an unsubscribe thunk. `useSyncExternalStore`'s
  subscribe."
  [^ViewCell cell listener]
  (let [k (gensym "rf-ui-cell-listener")]
    (swap! (state cell) assoc-in [:listeners k] listener)
    (fn unsubscribe [] (swap! (state cell) update :listeners dissoc k))))

(defn listener-count
  "The current host-subscriber count for `cell` (internal tool/test read).
  Reads the existing listener map on demand; it adds no production counter or
  notification-path work."
  [^ViewCell cell]
  (count (:listeners @(state cell))))

(defn- notify-listeners!
  "Deliver the revision notification to EVERY listener of `cell`. Each
  listener is contained in its own try/catch so one throwing consumer cannot
  starve its sibling listeners on the same cell (mirroring the observation
  port's per-lease disposal containment); the FIRST escape is rethrown AFTER
  every listener has been delivered — surfaced, never starving (rf2-owwbyl)."
  [^ViewCell cell]
  (let [escape (reduce (fn [acc f]
                         (try
                           (f)
                           acc
                           (catch #?(:clj Throwable :cljs :default) e
                             (or acc e))))
                       nil
                       (vals (:listeners @(state cell))))]
    (when (some? escape)
      (throw escape))))

(defn- advance-revision!
  "Advance the cell's revision and notify subscribers — the host re-reads
  getSnapshot, sees the new revision, and re-renders. From step 8 this runs
  synchronously inside the layout commit (React corrects BEFORE paint)."
  [^ViewCell cell]
  (swap! (state cell) update :revision inc)
  (notify-listeners! cell))

;; ---- drain coalescing + the notification scheduler (S2d) --------------------
;;
;; `on-change` is constant-work (mark-dirty; never compute — I-5). The moving
;; epoch/cause rides as EVIDENCE only (bounded + DEBUG-gated — see the
;; evidence plane below; production carries just the pending flag). A cell
;; enters the module DIRTY REGISTRY exactly once per flush boundary (the set
;; dedups by identity; a re-mark while pending folds in regardless of epoch
;; tag). N epochs
;; committed in one run-to-completion drain therefore advance the cell ONCE
;; at flush — the render batch boundary is DRAIN QUIESCENCE, not epoch close.
;; On CLJS one coalesced flush is armed per drain on the host MICROTASK queue
;; (`queue-microtask!`), which drains after the synchronous run-to-completion
;; drain unwinds and BEFORE the next paint — so a watch-fired movement is
;; corrected before the host can show a torn frame (rf2-vxgfnd.40; 03 §3).
;; `flush!` is the synchronous forcing, SCOPED so no pending work leaks
;; across roots. The Q51 scope ruling and the reentrancy contract live in
;; the ns docstring.

(defonce ^:private dirty-cells
  ;; The set of ViewCells with a pending (unflushed) notification — the
  ;; input to every scoped flush. `defonce` (module-lived); tests clear it
  ;; via `reset-scheduler!`.
  (atom #{}))

(defonce ^:private flush-scheduled? (atom false))

(defonce ^:private live-cells
  ;; The set of currently-CONNECTED ViewCells — the connected input to a
  ;; frame-destroy sweep (`teardown-frame!`). A cell enrols on `connect!`,
  ;; when it starts observing subscriptions and/or publishing resource-liveness
  ;; pins, and leaves on `disconnect!` (React unmount / Activity hide, when both
  ;; ownership families release) or `teardown!` (it goes :dead). The exact
  ;; membership test remains family-specific: committed subscription leases
  ;; define reactive observation/flush scope, while desired/reserved/held
  ;; resource incarnation pins provide teardown discovery only. A disconnected
  ;; cell leaves the set, so an unmounted cell never lingers here (no retention
  ;; leak). `defonce` (module-lived); tests clear it via `reset-scheduler!`.
  (atom #{}))

(defonce ^:private root-cells
  ;; ROOT-INCARNATION OWNERSHIP: `incarnation -> <weak membership set>`. Every
  ;; ViewCell attached to a root (`attach-root!`, the mount seam) enrols here
  ;; under its root's incarnation and STAYS enrolled across a transient Activity
  ;; hide — a hide removes the cell from `live-cells` (it holds no committed
  ;; deps) but NOT from its root membership. This is the piece
  ;; `teardown-collector` alone cannot supply: a cell hidden by React Activity
  ;; BEFORE the root's teardown window is armed already left
  ;; `:fresh`/`:connected`, so its cleanup can never enrol it in the window, and
  ;; it would otherwise linger `:disconnected {:reason :unknown}` and
  ;; RECONNECTABLE after its root is gone (rf2-vxgfnd.85). `teardown-root!`
  ;; consults this registry to reap those already-hidden cells alongside the
  ;; ones the window captures.
  ;;
  ;; Membership is WEAK (rf2-mc62sp): an ordinary React reconciliation unmount
  ;; (conditional subtree, route change, keyed-list eviction) runs only the
  ;; effect cleanup → `disconnect!`, which deliberately never detaches (hide vs
  ;; unmount are indistinguishable there — 03 §4), so a STRONG registry would
  ;; pin every ordinarily-unmounted cell — with its retained committed site
  ;; values — for the root's whole lifetime: unbounded production memory growth
  ;; per UI churn. Weak membership keeps both consumers exactly correct: a
  ;; genuinely HIDDEN cell is strongly reachable from React's retained fiber
  ;; (the `useRef` in `use-cell`), so its entry lives precisely as long as
  ;; Activity retention and stays discoverable for `teardown-root!` /
  ;; `teardown-frame!`; a reconciliation-unmounted cell becomes unreachable
  ;; (its leases were already released at `disconnect!`, and nothing can ever
  ;; reconnect a cell nothing references), so it collects and its entry clears
  ;; — the teardown scans lose nothing, per 03 §4 "the cell is garbage". The
  ;; per-host weak plumbing (`make-weak-member-set` etc.) lives beside
  ;; `attach-root!`; `detach-root!` on final `teardown!` stays the
  ;; DETERMINISTIC fast path, and an incarnation's entry is dropped the moment
  ;; its last cell deterministically leaves (or, on CLJS, when the
  ;; finalization reaper clears the last collected member) — repeated
  ;; mount/hide/unmount cycles never grow a historical registry. The
  ;; incarnation is a FRESH per-mount identity (`make-root-incarnation`), NOT
  ;; the reusable root-id, so a stale teardown can never reap the cells of a
  ;; replacement root mounted under the same root-id. `defonce`
  ;; (module-lived); `reset-scheduler!` clears it between fixtures. Frame
  ;; teardown also consults its still-disconnected members, whose retained
  ;; subscription targets and resource-incarnation pins name their last
  ;; published frames.
  (atom {}))

(defonce ^:private teardown-collector
  ;; The COLLECTION WINDOW of an in-flight host/root teardown (`teardown-root!`),
  ;; or nil at rest. `teardown-root!` arms it (a set) around the host React
  ;; `.unmount`; while armed, `disconnect!` attributes each disconnecting cell
  ;; to it — because a host `.unmount` sweeps the effect-cleanups of EXACTLY its
  ;; own root's tree (React scopes the unmount), every captured cell belongs to
  ;; that root and no sibling root's cell can enter the window. The driver then
  ;; retroactively proves each captured cell an unmount (03 §4). nil while no
  ;; teardown runs, so an Activity hide (a `disconnect!` with the window unarmed)
  ;; stays a transient reconnectable disconnect. Save/restored around a teardown
  ;; so a re-entrant one nests. `defonce` (module-lived); `reset-scheduler!`
  ;; clears it between fixtures.
  (atom nil))

(declare flush-pending!)

#?(:cljs
   (defn- queue-microtask!
     "Enqueue `f` on the host MICROTASK queue. The HTML event loop runs its
     microtask checkpoint after the current synchronous task and BEFORE the
     'update the rendering' (paint) step, so a microtask-scheduled flush
     corrects a moved sub before the host can present a torn frame — the
     property the drain-quiescence render batch leans on (rf2-vxgfnd.40).

     `js/queueMicrotask` where present (all modern browsers + Node ≥ 11);
     a resolved-Promise job is the fallback. DELIBERATELY NOT
     `goog.async.nextTick` (`interop/next-tick`), which is a MACROTASK
     (`setImmediate` / `MessageChannel` / `setTimeout`) — it yields to the
     event loop and may let a torn frame paint before it runs."
     [f]
     (if (exists? js/queueMicrotask)
       (js/queueMicrotask f)
       (.then (js/Promise.resolve) (fn [_] (f))))))

(defn- schedule-flush!
  "Arm ONE coalesced microtask that drains the whole registry — the CLJS
  host's realization of the drain-quiescence render batch (03 §3). One
  microtask per drain, NOT per epoch: it is armed by the first mark of a
  drain and fires only after the synchronous run-to-completion drain
  unwinds, so every epoch committed by the drain's queued events folds into
  the same flush. Re-marks before it runs fold in; a synchronous `flush!`
  beforehand just leaves it an empty drain.

  CLJS-only: the flush rides `queue-microtask!` — a TRUE host microtask that
  fires after the synchronous drain unwinds and BEFORE the next paint, so a
  watch-fired invalidation is corrected before a torn frame can show
  (rf2-vxgfnd.40). The JVM headless host has NO async render loop to align
  to — its drain-quiescence flush is the EXPLICIT `flush!` (07 §2 'the only flush
  idiom'; SSR renders one-shot) — and `interop/next-tick` there is a
  CONCURRENT executor, not a microtask, so a background auto-drain would
  race synchronous callers. One honest option per host, not a pretended same
  mechanism (03 §3)."
  []
  #?(:cljs
     (when (compare-and-set! flush-scheduled? false true)
       (queue-microtask!
         (fn []
           (reset! flush-scheduled? false)
           (flush-pending!))))
     :clj nil))

;; ---- the DEBUG invalidation-evidence plane (rf2-vxgfnd.46) ------------------
;;
;; TWO SEPARATE PLANES. The PRODUCTION scheduler needs only the pending flag
;; + identity-deduped registry membership (`enrol-dirty!`); that is the WHOLE
;; production invalidation cost — no per-cause allocation, nothing that scales
;; with the queued-event count. The DEBUG plane accumulates a BOUNDED
;; (constant-size) causal summary of the coalesced batch for tooling (Xray),
;; gated behind `interop/debug-enabled?` so it DCEs out of `:advanced` +
;; goog.DEBUG=false. Notification coalescing and causal evidence stay distinct:
;; one dirty enrolment / one render, while dev/tool builds retain enough to
;; attribute the render batch to its contributing movement (Spec 006 §The
;; internal observation port; 03 §3).

(def ^:private ^:const target-cap
  ;; Distinct moving targets SHOWN per pending window — a bounded SAMPLE of what
  ;; moved. Further distinct targets fold into the bounded `:dropped` loss SET
  ;; (not a running occurrence count), keeping the shown sample constant-size.
  8)

(def ^:private ^:const dropped-cap
  ;; The SECOND bound: distinct OMITTED target keys tracked in `:dropped` before
  ;; the loss account ITSELF saturates. Past this many distinct omissions,
  ;; `:dropped-exact?` flips false and `(count :dropped)` becomes a LOWER bound.
  ;; The whole record therefore stays constant-size (≤ target-cap shown +
  ;; ≤ dropped-cap omitted, all small sub-query-vector keys).
  64)

(defn- fold-evidence
  "DEBUG plane: fold one invalidation `payload` into a cell's pending-window
  evidence `ev` (nil = a fresh window). Returns a BOUNDED, constant-size
  record — never an unbounded payload vector:

    {:first-epoch    e0    ; the FIRST movement's frame-epoch (the anchor)
     :latest-epoch   eN    ; the most-recent movement's frame-epoch
     :count          n     ; total invalidation OCCURRENCES folded this window
     :causes         #{…}  ; the SET of causes seen (:value/:hmr/:disposed — ≤3)
     :targets        [tk…] ; distinct moving targets SHOWN, capped at `target-cap`
     :dropped        #{tk} ; distinct moving targets OMITTED past the shown cap —
                           ;   a BOUNDED SET; its COUNT is the honest fan-out loss
     :dropped-exact? b}    ; true ⇒ every omission is individually tracked;
                           ;   false ⇒ the loss set saturated at `dropped-cap`, so
                           ;   `(count :dropped)` is a LOWER bound

  Honesty (rf2-vxgfnd.74). `:count` is the OCCURRENCE axis (every fold, including
  repeats of an already-seen target); `:dropped` is the DISTINCT-target LOSS
  axis. They are complementary, never duplicates: re-invalidating one already-
  omitted target N times advances `:count` by N but leaves `:dropped` unchanged
  (that target's identity is already recorded). So a window that invalidates
  eight retained targets once then a NINTH target 100 times reports `:count 108`
  yet `:dropped #{tk9}` — ONE distinct omission, not 100. The field means what it
  says (distinct omitted targets), so it never overstates fan-out.

  The cause set and epoch scalars are naturally bounded; BOTH the shown-target
  vector and the omitted-target set are explicitly capped, so overflow is
  REPORTED (never silently lost) yet the record stays constant-size — a
  distinct omission past `dropped-cap` sets `:dropped-exact? false` rather than
  growing the set (acceptance-criteria 3/5)."
  [ev {:keys [cause target frame-epoch]}]
  (let [tk (when target (target-key target))]
    (if (nil? ev)
      {:first-epoch    frame-epoch
       :latest-epoch   frame-epoch
       :count          1
       :causes         (if cause #{cause} #{})
       :targets        (if tk [tk] [])
       :dropped        #{}
       :dropped-exact? true}
      (let [known?     (or (nil? tk)
                           (some #(= tk %) (:targets ev))
                           (contains? (:dropped ev) tk))
            at-cap?    (>= (count (:targets ev)) target-cap)
            drop-full? (>= (count (:dropped ev)) dropped-cap)]
        (cond-> (-> ev
                    (assoc :latest-epoch frame-epoch)
                    (update :count inc))
          cause                                       (update :causes conj cause)
          ;; a NEW distinct target with room in the shown sample
          (and (not known?) (not at-cap?))            (update :targets conj tk)
          ;; a NEW distinct target past the shown cap, with room in the loss set:
          ;; record its IDENTITY so `:dropped` counts DISTINCT omissions
          (and (not known?) at-cap? (not drop-full?)) (update :dropped conj tk)
          ;; a NEW distinct omission but the loss set is FULL: the count is now a
          ;; floor — mark it inexact (the honest "≥ dropped-cap distinct" signal)
          (and (not known?) at-cap? drop-full?)       (assoc :dropped-exact? false))))))

(defn- record-evidence!
  "DEBUG plane: fold `payload`'s bounded causal evidence into `cell`'s
  pending-window accumulator. Elided in production (every caller gates on
  `interop/debug-enabled?`)."
  [^ViewCell cell payload]
  (swap! (state cell) update :evidence fold-evidence payload))

(defonce ^:private evidence-sink
  ;; DEBUG-only consumer seam: `(fn [cell evidence] …)` | nil. Invoked at each
  ;; flush with the coalesced bounded evidence BEFORE it is cleared, so a tool
  ;; (Xray) receives the causal summary of the render batch rather than it
  ;; being dead cell state (rf2-vxgfnd.46). `defonce` (module-lived);
  ;; `reset-scheduler!` clears it between fixtures.
  (atom nil))

(defn set-evidence-sink!
  "Install (or clear, with nil) the DEBUG-only invalidation-evidence consumer
  — a `(fn [cell evidence] …)` the flush invokes with each pending cell's
  coalesced bounded evidence just after it completes that cell's flush. The
  intended `re-frame.ui.tool`/Xray projection point; the flush's call is gated
  on `interop/debug-enabled?`, so it is a no-op in production. A THROWING sink
  is contained by the flush (see `flush-one!` / `report-sink-escape!`) and can
  never strand a cell or abort the render batch. Returns nil.

  This is the RAW last-write-wins slot (the test seam). First-party tools
  install through the identity-owned, HMR-safe lifecycle over it —
  `re-frame.ui.tool.evidence/install!` / `uninstall!` (rf2-vxgfnd.75) — which
  rejects a second owner instead of silently clearing the first."
  [f]
  (reset! evidence-sink f)
  nil)

(defonce ^:private last-sink-escape
  ;; The single bounded diagnostic slot for a CONTAINED evidence-sink throw
  ;; (`{:cell cell :error e}` | nil). The evidence-sink is a DEBUG-only tool
  ;; consumer of the scheduler, NEVER an authority over it: a throwing sink
  ;; must never strand a cell (the acceptance bug rf2-vxgfnd.73) — so
  ;; `flush-one!` completes every cell's flush FIRST and CONTAINS the sink's
  ;; throw, and `flush-scope!` records the escape here (overwritten — one slot,
  ;; the newest escape) plus one host-console diagnostic per batch. Observable
  ;; via `last-evidence-sink-escape` so the escape is never silent. `defonce`
  ;; (module-lived); `reset-scheduler!` clears it between fixtures.
  (atom nil))

(defn- report-sink-escape!
  "DEV plane: contain the fallout of a THROWING `evidence-sink`. The flush has
  ALREADY completed every drained cell's scheduler state (`:dirty?`/evidence
  cleared, revision advanced, listeners notified), so this is a pure
  after-the-fact report that can neither strand a cell nor abort the batch.
  Record the escape in the single bounded `last-sink-escape` slot (a
  tool/test read — `last-evidence-sink-escape`) and, on CLJS, emit ONE host
  `console.warn` naming the offending view. Reporting NEVER routes back through
  `evidence-sink` (no recursion). Called at most once per `flush-scope!` batch,
  and only from its debug-gated branch, so the whole helper DCEs under
  `goog.DEBUG=false`. `escape` is `{:cell cell :error e}`."
  [{:keys [cell error] :as escape}]
  (reset! last-sink-escape escape)
  #?(:cljs
     (when (exists? js/console)
       (.warn js/console
              (str "[re-frame.ui] an evidence-sink (the DEBUG "
                   "invalidation-evidence consumer — e.g. Xray) threw while the "
                   "scheduler flushed view " (pr-str (:view-id @(state cell)))
                   " — the throw was CONTAINED so the render correction still "
                   "completed and the batch was not aborted; a debug observer "
                   "cannot corrupt flush completion. Fix the sink callback "
                   "installed with (set-evidence-sink! …). Cause: "
                   (error/ex-message-safe error))))
     :clj nil))

(defn- enrol-dirty!
  "The PRODUCTION scheduling core: flag `cell` pending, enrol it in the dirty
  registry once (identity-deduped — a re-mark while already dirty coalesces),
  and arm one per-drain microtask flush. NO evidence, no compute, no
  acquire/release (I-5) — this is the WHOLE production invalidation cost, flat
  in the number of queued events."
  [^ViewCell cell]
  (let [st (state cell)]
    (when-not (:dirty? @st)
      (swap! st assoc :dirty? true)
      (swap! dirty-cells conj cell)
      (schedule-flush!))))

(defn mark-dirty!
  "The `on-change` body / test seam: enrol `cell` for a coalesced flush
  (`enrol-dirty!`) and — in DEV/tool builds only — fold `epoch` as minimal
  cause evidence. Never acquires/releases and never computes (I-5). Coalescing
  keys on the pending flag, NEVER on any cause tag: a re-mark while already
  pending advances nothing, yet its evidence still FOLDS into the same bounded
  pending-window record — so N invalidations in one drain coalesce to ONE
  render while the debug plane preserves first/latest epoch plus a bounded
  cause/target summary (rf2-vxgfnd.46). `on-change-fn` folds the RICHER port
  payload (cause + target); this arity carries only an epoch, for the JVM/test
  seam. nil when driven without epoch evidence."
  ([^ViewCell cell] (mark-dirty! cell nil))
  ([^ViewCell cell epoch]
   (when interop/debug-enabled?
     (record-evidence! cell {:frame-epoch epoch}))
   (enrol-dirty! cell)))

(defn- complete-flush!
  "PHASE 1 of a batch flush (rf2-vxgfnd.86): complete `cell`'s SCHEDULER STATE
  with NO arbitrary user code — capture the pre-clear DEBUG evidence, clear
  `:dirty?`/evidence, and advance the revision (WITHOUT notifying listeners yet).
  No-op / nil when the cell is not dirty. Returns `[cell ev]` for the cell it
  completed (`ev` nil in production / when the window carried none), else nil.

  Splitting completion from notification is the order-independence fix. The batch
  core runs this over the WHOLE drained batch BEFORE `deliver-flush!` runs ANY
  listener or evidence-sink, so a re-entrant re-mark (from a phase-2 listener or
  sink) always finds every drained cell already completed and, seeing a cleared
  `:dirty?`, enrols a FRESH pending window (next-batch semantics) — independent of
  set iteration order. Previously each cell was completed-and-notified one at a
  time, so whether a re-marked cell was already cleared (→ fresh window) or still
  in the drained-but-uncompleted batch (→ mark lost) depended on hash order."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (:dirty? @st)
      (let [ev (when interop/debug-enabled? (:evidence @st))]
        (swap! st assoc :dirty? false :evidence nil)
        (swap! st update :revision inc)
        [cell ev]))))

(defn- deliver-flush!
  "PHASE 2 of a batch flush (rf2-vxgfnd.86): now that `complete-flush!` has
  settled the WHOLE batch's scheduler state, notify `cell`'s listeners (the
  production re-render trigger — one coalesced React render batch, since every
  listener fires synchronously within this flush) and, in DEV, hand the captured
  `ev` to the installed `evidence-sink` (Xray; rf2-vxgfnd.46) inside a guard.

  BOTH consumer families are contained here, because neither is an authority
  over the render correction (the rf2-vxgfnd.73 doctrine):

    - a THROWING LISTENER (the PRODUCTION surface — rf2-owwbyl) is caught and
      returned to the batch core, so it can neither strand this cell nor abort
      the batch's remaining cells' deliveries: phase 1 already cleared every
      drained cell's dirty flag and registry membership, so an aborted phase 2
      would LOSE the undelivered sibling notifications outright — stale UI
      until unrelated movement re-marks them. The batch core rethrows the
      FIRST listener escape AFTER the whole batch has delivered — surfaced,
      never silent, never starving (the same drain-then-rethrow shape as the
      observation port's `drain-pending-disposals!`).
    - a THROWING sink is a broken DEBUG tool: contained exactly as before
      (rf2-vxgfnd.73) and reported via ONE bounded diagnostic (never rethrown,
      never through the sink). The sink still receives the exact pre-clear
      bounded summary once per flushed cell.

  Because completion already finished for the whole batch, a re-entrant
  sink/listener that re-marks this (or ANY) cell enrols a FRESH pending window
  rather than losing the notification. Returns nil, or a map carrying the
  contained `:listener` and/or `:sink` escape as `{:cell cell :error e}`; the
  evidence/sink half DCEs under `goog.DEBUG=false`."
  [^ViewCell cell ev]
  (let [listener-escape (try
                          (notify-listeners! cell)
                          nil
                          (catch #?(:clj Throwable :cljs :default) e
                            {:cell cell :error e}))
        sink-escape     (when interop/debug-enabled?
                          (when-some [sink @evidence-sink]
                            (try
                              (sink cell ev)
                              nil
                              (catch #?(:clj Throwable :cljs :default) e
                                {:cell cell :error e}))))]
    (cond-> nil
      (some? listener-escape) (assoc :listener listener-escape)
      (some? sink-escape)     (assoc :sink sink-escape))))

(defn- run-flush-batch!
  "The two-phase batch-flush CORE over an explicit ordered `cells` seq
  (rf2-vxgfnd.86). PHASE 1 (`complete-flush!`) settles every dirty cell's
  scheduler state — with no user code — so the whole batch is complete before
  PHASE 2 (`deliver-flush!`) runs any listener or evidence-sink. A re-entrant
  re-mark in phase 2 therefore always sees a fully-completed batch and opens the
  NEXT window, regardless of iteration order.

  Phase 2 delivers EVERY cell regardless of consumer behaviour — each
  `deliver-flush!` contains its cell's throwing listener AND (in DEV) a
  throwing sink per-cell, so neither can abort the remaining cells' deliveries
  (rf2-owwbyl / rf2-vxgfnd.73). Evaluate the delivery FIRST (never inside a
  short-circuiting form), keep the FIRST escape of each family across the
  batch, then: emit ONE bounded sink diagnostic (never through the sink; the
  sink half DCEs under `goog.DEBUG=false`) and RETHROW the first listener
  escape AFTER the whole batch delivered — a production consumer bug is
  surfaced to the flush caller, never silent, and never costs a sibling cell
  its notification. Returns the count actually flushed."
  [cells]
  (let [completed (into [] (keep complete-flush!) cells)
        escapes   (reduce (fn [acc [cell ev]]
                            (let [e (deliver-flush! cell ev)]
                              (cond-> acc
                                (and (:listener e) (nil? (:listener acc)))
                                (assoc :listener (:listener e))

                                (and (:sink e) (nil? (:sink acc)))
                                (assoc :sink (:sink e)))))
                          {}
                          completed)]
    (when interop/debug-enabled?
      (when-some [se (:sink escapes)]
        (report-sink-escape! se)))
    (when-some [le (:listener escapes)]
      (throw (:error le)))
    (count completed)))

(defn flush-scope!
  "The scoped-flush PRIMITIVE: synchronously flush every pending cell for
  which `(scope-pred cell)` is truthy, advancing each once; cells outside
  the scope stay pending (no epoch work leaks across scopes). Reentrancy-
  SAFE by construction — the matching cells are removed from the registry
  ATOMICALLY (`swap-vals!`) before any notify, so a notify-triggered
  re-entrant flush sees an already-drained set. The drained batch runs through
  the two-phase `run-flush-batch!` core, so a re-entrant re-mark of ANOTHER cell
  in the same batch is order-INDEPENDENT (rf2-vxgfnd.86). Returns the count
  flushed."
  [scope-pred]
  (let [[old _] (swap-vals! dirty-cells
                            (fn [cells] (into #{} (remove scope-pred) cells)))
        flushed (filterv scope-pred old)]
    (run-flush-batch! flushed)))

(defn flush-batch-in-order!
  "Test seam (rf2-vxgfnd.86): drain and flush EXACTLY `cells` in the GIVEN order
  through the same two-phase `run-flush-batch!` core `flush-scope!` uses, so a
  fixture can FORCE a deterministic iteration order and prove the flush is
  order-independent (drive `[a b]` and `[b a]`, assert identical outcomes).
  Removes them from the dirty registry first (mirroring `flush-scope!`'s atomic
  drain), then runs the ordered two-phase flush. Returns the count flushed."
  [cells]
  (swap! dirty-cells #(reduce disj % cells))
  (run-flush-batch! (vec cells)))

(defn flush-pending!
  "GLOBAL drain — flush EVERY pending cell once (the test-only all-roots
  spelling `ui.test/flush!` rides this). Returns the count flushed."
  []
  (flush-scope! (constantly true)))

(defn- cell-frames
  "The set of frame-ids `cell`'s committed subscription sites observe.

  Resource leases are liveness ownership, not read observation. Their exact
  frame-incarnation index participates in teardown discovery only; it must not
  put a resource-only frame into `flush-frame!` scope.

  Only records with a live lease are currently observed. A static Story-
  override target names NO frame (the pinned value IS the
  resolution — there is no node and no observed frame), so an OVERRIDE-ONLY
  cell observes no frame and this returns `#{}`. The frame-scope membership
  test (`cell-observes-frame?`) is therefore false for such a cell against
  EVERY frame, and `flush-frame!` — which scopes on it — can never reach an
  override-only cell; only the GLOBAL `flush-pending!` drains one. A cell
  mixing `sub` and override sites observes exactly its `sub` sites' frames."
  [^ViewCell cell]
  (into #{}
        (keep (fn [{:keys [target lease]}]
                (when (and lease (= :subscription (:kind target)))
                  (:frame-id target))))
        (vals (:committed @(state cell)))))

(defn cell-observes-frame?
  "True when `cell`'s committed dependency set includes a site in frame
  `frame-id` (the frame-scope membership test)."
  [^ViewCell cell frame-id]
  (contains? (cell-frames cell) frame-id))

(defn- cell-retained-frame?
  "True when `cell`'s last published lexical site records name `frame-id`.
  Records survive an Activity disconnect with `:lease nil`, providing bounded
  exact query/value history plus frame attribution for hidden root-owned cells."
  ([^ViewCell cell frame-id]
   (cell-retained-frame? cell frame-id ::any-incarnation))
  ([^ViewCell cell frame-id frame-token]
   (let [st @(state cell)
         observation?
         (some (fn [{:keys [target]}]
                 (and (= :subscription (:kind target))
                      (= frame-id (:frame-id target))))
               (vals (:committed st)))
         extra-tokens (get (:extra-frame-incarnations st) frame-id)
         extra? (and (seq extra-tokens)
                     (or (= ::any-incarnation frame-token)
                         (some #(identical? frame-token %) extra-tokens)))]
     (boolean (or observation? extra?)))))

(defn flush-frame!
  "The FRAME arity of `flush!` — flush every pending cell observing frame
  `frame-id` (every root that observes that frame). Cells scoped to other
  frames stay pending — and an OVERRIDE-ONLY cell, which observes no frame at
  all (`cell-frames` tracks `:sub` keys only), is never in scope: only the
  global `flush-pending!` drains such a cell. Returns the count flushed."
  [frame-id]
  (flush-scope! #(cell-observes-frame? % frame-id)))

(defn flush-dirty!
  "Synchronously flush THIS cell's pending notification, if any (test seam
  + the per-cell forcing door). Returns nil."
  [^ViewCell cell]
  (flush-scope! #(identical? % cell))
  nil)

(defn- discard-pending!
  "Drop `cell`'s pending notification WITHOUT advancing its revision —
  used at disconnect/teardown so an unmounted or dead cell never lingers
  in the registry or fires a stale flush. Returns nil."
  [^ViewCell cell]
  (swap! dirty-cells disj cell)
  (swap! (state cell) assoc :dirty? false :evidence nil)
  nil)

(defn dirty?
  "True when `cell` has a pending (unflushed) notification (tool/test read)."
  [^ViewCell cell]
  (boolean (:dirty? @(state cell))))

(defn pending-epoch
  "The FIRST-epoch ANCHOR of `cell`'s pending-window evidence — the frame-epoch
  the FIRST `on-change` of the current window carried (nil when the cell is not
  pending, was dirtied without epoch evidence, or in a production build where
  the debug evidence plane is elided). A convenience read over
  `pending-evidence`. Epoch ids are movement/cause evidence ONLY; coalescing
  keys on the pending flag, never on this tag, so later queued events' epochs
  fold into the same render batch without re-anchoring or advancing it. For the
  FULL coalesced batch — latest epoch, the cause/target span, the loss account
  — read `pending-evidence` (tool/test read)."
  [^ViewCell cell]
  (:first-epoch (:evidence @(state cell))))

(defn pending-evidence
  "The BOUNDED causal-evidence record for `cell`'s current pending window (nil
  when the cell is not pending, or in a production build where the debug plane
  is elided). The coalesced summary of every invalidation folded into this one
  render batch — see `fold-evidence` for the shape: first/latest frame-epoch,
  the total occurrence `:count`, the cause set, a capped SHOWN distinct-target
  vector, and a bounded `:dropped` SET of the distinct targets OMITTED past the
  shown cap (its count is the honest fan-out loss, `:dropped-exact?` flags
  saturation). The `re-frame.ui.tool`/Xray projection reads this (or receives
  it via `set-evidence-sink!`) to attribute a coalesced render to its
  contributing movement WITHOUT forcing a render per epoch (rf2-vxgfnd.46;
  tool/test read)."
  [^ViewCell cell]
  (:evidence @(state cell)))

(defn pending-cell-count
  "The number of cells with a pending notification (tool/test read)."
  []
  (count @dirty-cells))

(defn last-evidence-sink-escape
  "The most recent CONTAINED `evidence-sink` throw as `{:cell cell :error e}`,
  or nil when no sink has thrown since the last `reset-scheduler!` (or in a
  production build, where the debug evidence plane is elided). A throwing
  DEBUG sink never strands a cell or aborts a flush batch (rf2-vxgfnd.73) — it
  is contained and surfaced HERE (plus a host `console.warn`) so the escape is
  never silent (tool/test read)."
  []
  @last-sink-escape)

(defn reset-scheduler!
  "Test support: drop every pending notification and the slice memo without
  advancing any revision — a clean slate between fixtures. Returns nil."
  []
  (reset! dirty-cells #{})
  (reset! flush-scheduled? false)
  (reset! slice-memo* nil)
  (reset! live-cells #{})
  (reset! root-cells {})
  (reset! teardown-collector nil)
  (reset! evidence-sink nil)
  (reset! last-sink-escape nil)
  ;; Do NOT clear `view-generations`: it now owns the stable component shells
  ;; and descriptors created at namespace load. Clearing it would strand every
  ;; already-defined defview Var on a shell whose dynamic descriptor vanished.
  ;; Tests use qualified per-fixture view ids for HMR decisions.
  nil)

(defn- on-change-fn
  "Build the per-lease `on-change` the commit registers on each acquired
  target (Spec 006 §The internal observation port). Constant-work: enrol
  `cell` for a coalesced flush and — in DEV/tool builds only — fold the port's
  rich invalidation payload (`:cause`/`:target`/`:frame-epoch`, plus the
  `:node-*`/`:registry-epoch` axes it carries) into the bounded pending-window
  evidence. Production carries only the enrolment (I-5; the evidence fold DCEs
  out under goog.DEBUG=false)."
  [^ViewCell cell]
  (fn [payload]
    (when interop/debug-enabled?
      (record-evidence! cell payload))
    (enrol-dirty! cell)))

;; ---- resource ownership family -------------------------------------------
;;
;; Resource leases share the ViewCell/exact render capture and lifecycle with
;; observation leases, but deliberately do NOT participate in the observation
;; port's acquire transaction. Layout accepts an ownership-free desired plan;
;; one passive effect prevalidates the COMPLETE plan and queues ordinary
;; resource events. There is no cross-frame handler rollback or sync dispatch.

(defn- resource-frame-index
  "Generic teardown index for every desired/reserved/held resource record.
  Kept outside the base ViewCell shape: no-lease cells have no index key."
  [{:keys [resource-desired resource-reservations resource-held]}]
  (reduce (fn [out {:keys [frame-id frame-token]}]
            (update out frame-id (fnil conj #{}) frame-token))
          {}
          (concat (vals (:by-site resource-desired))
                  (vals resource-reservations)
                  (vals resource-held))))

(defn- install-resource-state
  [m resources]
  (assoc m
         :resources resources
         :extra-frame-incarnations (resource-frame-index resources)))

(defn- resource-capture-current?
  [st cap]
  (let [resources (:resources st)]
    (and (= :connected (:lifecycle st))
         (= (:generation cap) (:generation st))
         (identical? cap (:resource-capture resources)))))

(defn- validate-resource-frame!
  [{:keys [frame-id frame-token sid]}]
  (when (or (not (identical? frame-token
                             (frame/frame-incarnation-token frame-id)))
            (frame/frame-incarnation-closing? frame-id frame-token))
    (error/throw-error!
     :rf.error/frame-destroyed
     're-frame.ui/lease
     (str "resource lease site " (pr-str sid)
          " no longer targets the live frame incarnation it captured")
     {:extra {:frame frame-id :site-id sid}})))

(defn- resolved-registration
  "Resolve `(kind,id)` through the desired frame's exact image generation."
  [frame-id kind id]
  (live-frame/call-with-frame-resolution
   (live-frame/frame-resolution-target frame-id)
   #(registrar/lookup kind id)))

(defn- require-resource-registration!
  [{:keys [descriptor frame-id]}]
  (let [resource-id (:resource descriptor)]
    (when-not (resolved-registration frame-id :resource resource-id)
      (error/throw-error!
       :rf.error/resource-not-registered
       're-frame.ui/lease
       (str "no resource is registered under " (pr-str resource-id)
            " in frame " (pr-str frame-id)
            " — call rf/reg-resource before leasing it")
       {:recovery :fix-registration
        :extra {:resource-id resource-id}}))))

(defn- prevalidate-resource-plan!
  "Validate the complete desired plan before minting or dispatching anything."
  [{:keys [order by-site]} prior-held prior-held-order]
  (let [records (mapv #(assoc (get by-site %) :sid %) order)
        held-records (keep prior-held prior-held-order)]
    (when (or (seq records) (seq held-records))
      ;; The canonical optional-feature probe. This check is first so an absent
      ;; artefact reports the copy-pasteable coordinate/require fix instead of
      ;; a secondary registrar miss. A dev shell with zero current/prior lease
      ;; sites is a true no-op: it keeps the fixed passive hook but does not
      ;; require the optional feature merely by mounting.
      (features/require-feature! :resources)
      (doseq [record records]
        (validate-resource-frame! record)
        (ui-lease/validate-descriptor! (:descriptor record))
        (require-resource-registration! record))
      (doseq [record held-records]
        (validate-resource-frame! record)))
    records))

(defn- same-resource-reservation?
  [prior desired]
  (and prior
       (= (:frame-id prior) (:frame-id desired))
       (identical? (:frame-token prior) (:frame-token desired))
       (eq/rf= (:descriptor prior) (:descriptor desired))))

(defn- ensure-event
  [view-id {:keys [sid descriptor frame-id owner commit-id]}]
  [:rf.resource/ensure
   (cond-> {:resource (:resource descriptor)
            :owner owner}
     (contains? descriptor :scope)  (assoc :scope (:scope descriptor))
     (contains? descriptor :params) (assoc :params (:params descriptor))
     interop/debug-enabled?
     (assoc :cause {:rf.ui/view view-id
                    :rf.ui/commit commit-id
                    :rf.ui/site sid
                    :frame frame-id
                    :owner owner}))])

(defn- enqueue-ensure!
  [view-id {:keys [frame-id] :as record}]
  (router/dispatch! (ensure-event view-id record) {:frame frame-id}))

(defn- enqueue-release!
  [{:keys [frame-id owner]}]
  (router/dispatch! [:rf.resource/release-owner {:owner owner}]
                    {:frame frame-id}))

(defn- attempt-resource-releases!
  "Attempt every release in order. Never short-circuits; returns the first
  escape and the records whose release could not be queued."
  [records]
  (reduce (fn [{:keys [error failed]} record]
            (try
              (enqueue-release! record)
              {:error error :failed failed}
              (catch #?(:clj Throwable :cljs :default) e
                {:error (or error e) :failed (conj failed record)})))
          {:error nil :failed []}
          records))

(defn- stage-resource-held!
  "Make exactly one about-to-be-enqueued owner lifecycle-cleanup reachable.
  The desired plan already indexes its exact frame pin, so this deliberately
  updates held/order only (no whole-plan index rebuild per owner). Returns true
  only while `cap` still owns connected reconciliation authority."
  [st cap {:keys [owner] :as record}]
  (swap! st
         (fn [m]
           (if (resource-capture-current? m cap)
             (-> m
                 (assoc-in [:resources :resource-held owner] record)
                 (update-in [:resources :resource-held-order]
                            (fn [order]
                              (if (some #(= owner %) order)
                                order
                                (conj (vec order) owner)))))
             m)))
  (let [m @st]
    (and (resource-capture-current? m cap)
         (identical? record
                     (get-in m [:resources :resource-held owner])))))

(defn- forget-exact-held!
  "Forget successfully released records only while the held owner still names
  the exact record this reconciliation staged/read. A newer reentrant passive
  may have installed a different record under the same reusable owner; that
  newer authority is never overwritten. Rebuilds the canonical frame index
  once after the batch."
  [st records]
  (when (seq records)
    (swap! st
           (fn [m]
             (if-let [resources (:resources m)]
               (let [[held removed]
                     (reduce
                      (fn [[held removed] {:keys [owner] :as record}]
                        (if (identical? record (get held owner))
                          [(dissoc held owner) (conj removed owner)]
                          [held removed]))
                      [(:resource-held resources) #{}]
                      records)]
                 (if (seq removed)
                   (install-resource-state
                    m
                    (assoc resources
                           :resource-held held
                           :resource-held-order
                           (into [] (remove removed)
                                 (:resource-held-order resources))))
                   m))
               m))))
  nil)

(defn- compensate-resource-ensures!
  "Conservatively release the ensures this reconciliation actually attempted
  and still exactly owns. Future lexical candidates were never staged or
  enqueued. A record whose held entry a reentrant authority (a newer passive
  reconcile or a lifecycle cleanup) replaced or already released is skipped —
  compensating it here would double-release an owner someone else now
  accounts for. Successfully compensated exact records leave held state;
  failed compensation stays cleanup-reachable."
  [st records]
  (let [held          (get-in @st [:resources :resource-held])
        mine          (filterv #(identical? % (get held (:owner %))) records)
        result        (attempt-resource-releases! mine)
        failed-owners (into #{} (map :owner) (:failed result))
        successful    (into [] (remove #(contains? failed-owners (:owner %)))
                            mine)]
    (forget-exact-held! st successful)
    result))

(defn- release-held-resources!
  "Lifecycle cleanup: park held ownership before attempting every release.
  A disconnect also parks reusable reservations behind a unique cleanup token:
  it restores them only if no synchronous release listener reconnected,
  recommitted, or tore down the cell. Final teardown forgets reservations.
  Returns the first contained dispatch escape, if any."
  [^ViewCell cell retain-reservations?]
  (let [st      (state cell)
        before @st
        resources (:resources before)
        held   (:resource-held resources)
        records (keep held (:resource-held-order resources))
        reservations (:resource-reservations resources)
        cleanup-token (when retain-reservations?
                        #?(:clj (Object.) :cljs (js-obj)))]
    ;; Publish the complete cleanup state BEFORE dispatch: trace listeners run
    ;; synchronously and may reconnect or teardown this cell. In particular,
    ;; hide owner1's reservation so a reconnect during release(owner1) mints a
    ;; distinct owner instead of ensuring owner1 immediately before the outer
    ;; dispatch drops it.
    (swap! st (fn [m]
                (install-resource-state
                 m
                 (cond-> (assoc (:resources m)
                                :resource-reservations {}
                                :resource-held {}
                                :resource-held-order [])
                   retain-reservations?
                   (assoc :resource-disconnect-token cleanup-token)

                   (not retain-reservations?)
                   (-> (assoc :resource-desired {:order [] :by-site {}}
                              :resource-capture nil)
                       (dissoc :resource-disconnect-token))))))
    (let [result (attempt-resource-releases! records)]
      (when retain-reservations?
        ;; Normal StrictMode/Activity cleanup retains owner identity. Any
        ;; synchronous reconnect/commit clears the token in
        ;; `accept-resource-capture`, and teardown clears it above, so this
        ;; conditional restore cannot overwrite newer/dead authority.
        (swap! st
               (fn [m]
                 (let [current (:resources m)]
                   (if (and (= :disconnected (:lifecycle m))
                            (identical? cleanup-token
                                        (:resource-disconnect-token current)))
                     (install-resource-state
                      m
                      (-> current
                          (assoc :resource-reservations reservations)
                          (dissoc :resource-disconnect-token)))
                     m)))))
      (:error result))))

(def ^:private resource-lifecycle-ops
  ;; This map and every function it reaches are referenced only by
  ;; enable-resource-lifecycle!. A production view with no lease sites never
  ;; reaches that installer, allowing Closure to erase the full resource
  ;; ownership family (including release event ids and the neutral owner mint).
  {:disconnect (fn [cell] (release-held-resources! cell true))
   :teardown   (fn [cell] (release-held-resources! cell false))})

(defn enable-resource-lifecycle!
  "Install the resource cleanup family on a lease-capable ViewCell. Idempotent
  and ownership-free: render may install these function pointers, but only the
  accepted passive capture can mint/ensure an owner. Production sub-only cells
  never call this function, preserving structural lease-free DCE."
  [^ViewCell cell]
  (swap! (state cell)
         (fn [m]
           (if (:resources m)
             m
             (let [resources
                   (cond-> {:resource-desired {:order [] :by-site {}}
                            :resource-capture nil
                            :resource-reservations {}
                            :resource-held {}
                            :resource-held-order []
                            :resource-lifecycle resource-lifecycle-ops}
                     interop/debug-enabled? (assoc :resource-commit 0))]
               (assoc (install-resource-state m resources)
                      :on-teardown (:teardown resource-lifecycle-ops))))))
  cell)

(defn- run-resource-lifecycle!
  [^ViewCell cell phase]
  (when-let [f (get-in @(state cell) [:resources :resource-lifecycle phase])]
    (f cell)))

(defn reconcile-resource-leases!
  "Passive-effect resource reconciliation for the exact layout-accepted
  `capture`. Stale/abandoned effects are inert. The full desired set validates
  before owner mint or dispatch; same-site `rf=` descriptors on the same frame
  incarnation retain their exact owner. Every fresh ensure is queued in lexical
  order before any old owner release is attempted. Because dispatch trace
  listeners run synchronously, each owner becomes cleanup-reachable immediately
  before its own enqueue and exact capture/lifecycle authority is fenced after
  every ensure and release. Future lexical owners are never staged early, and
  final publication is conditional on the same accepted capture still owning a
  connected cell."
  [^ViewCell cell cap]
  (let [st  (state cell)
        st0 @st
        resources0 (:resources st0)]
    (when (resource-capture-current? st0 cap)
      (let [desired (:resource-desired resources0)
            records (prevalidate-resource-plan!
                     desired
                     (:resource-held resources0)
                     (:resource-held-order resources0))]
        ;; A feature hook/registrar lookup can synchronously run user tooling.
        ;; Recheck selected-capture identity before any irreversible mint.
        (when (resource-capture-current? @st cap)
          (let [resources         (:resources @st)
                prior-reservations (:resource-reservations resources)
                prior-held        (:resource-held resources)
                prior-held-order  (:resource-held-order resources)
                commit-id         (:resource-commit resources)
                reservations
                (persistent!
                 (reduce (fn [out {:keys [sid] :as desired-record}]
                           (let [prior (get prior-reservations sid)
                                 record
                                 (cond->
                                  (if (same-resource-reservation? prior desired-record)
                                    ;; Preserve the exact prior descriptor
                                    ;; object and owner on an rf=-equal render.
                                    (assoc desired-record
                                           :descriptor (:descriptor prior)
                                           :owner (:owner prior))
                                    (assoc desired-record
                                           :owner (lease-owner/mint!)))
                                   interop/debug-enabled?
                                   (assoc :commit-id commit-id))]
                             (assoc! out sid record)))
                         (transient {})
                         records))
                desired-records (mapv reservations (:order desired))
                desired-owners  (into #{} (map :owner) desired-records)
                ensures         (filterv #(not (contains? prior-held (:owner %)))
                                         desired-records)
                releases        (into []
                                      (comp (keep prior-held)
                                            (remove #(contains? desired-owners
                                                                (:owner %))))
                                      prior-held-order)
                view-id         (:view-id @st)]
            ;; Candidate reservations remain LOCAL until every ensure succeeds.
            ;; Stage only the owner whose dispatch is about to run, then fence
            ;; the exact accepted capture immediately after the synchronous
            ;; trace/listener surface returns.
            (let [ensure-result
                  (loop [remaining ensures
                         attempted []]
                    (if-some [record (first remaining)]
                      (if-not (stage-resource-held! st cap record)
                        {:status :lost :attempted attempted}
                        (let [attempted' (conj attempted record)
                              escape
                              (try
                                (enqueue-ensure! view-id record)
                                nil
                                (catch #?(:clj Throwable :cljs :default) e e))]
                          (cond
                            (not (resource-capture-current? @st cap))
                            {:status :lost
                             :attempted attempted'
                             :error escape}

                            escape
                            {:status :error
                             :attempted attempted'
                             :error escape}

                            :else
                            (recur (next remaining) attempted'))))
                      {:status :ok :attempted attempted}))]
              (if-not (= :ok (:status ensure-result))
                (let [compensation
                      ;; A lifecycle transition published cleanup before its
                      ;; resource dispatch and already released/cleared staged
                      ;; owners. A connected capture supersession did not, so
                      ;; compensate exactly the attempted prefix while preserving
                      ;; all newer desired/capture authority.
                      (when (= :connected (:lifecycle @st))
                        (compensate-resource-ensures!
                         st (:attempted ensure-result)))]
                  (when-let [e (or (:error ensure-result)
                                   (:error compensation))]
                    (throw e)))
                (let [release-result
                      ;; Old releases retain their held records until enqueue
                      ;; succeeds. Continue across ordinary release escapes for
                      ;; total cleanup, but stop immediately when synchronous
                      ;; reentrancy loses exact authority.
                      (loop [remaining releases
                             released  []
                             failed    []
                             error     nil]
                        (if-some [record (first remaining)]
                          (if-not (resource-capture-current? @st cap)
                            {:status :lost
                             :released released
                             :failed failed
                             :error error}
                            (let [escape
                                  (try
                                    (enqueue-release! record)
                                    nil
                                    (catch #?(:clj Throwable :cljs :default) e e))
                                  current? (resource-capture-current? @st cap)
                                  released' (cond-> released (nil? escape)
                                              (conj record))
                                  failed'   (cond-> failed escape (conj record))
                                  error'    (or error escape)]
                              (if current?
                                (recur (next remaining)
                                       released' failed' error')
                                {:status :lost
                                 :released released'
                                 :failed failed'
                                 :error error'})))
                          {:status :ok
                           :released released
                           :failed failed
                           :error error}))]
                  (if-not (= :ok (:status release-result))
                    (let [compensation
                          (when (= :connected (:lifecycle @st))
                            (compensate-resource-ensures!
                             st (:attempted ensure-result)))]
                      (when (= :connected (:lifecycle @st))
                        (forget-exact-held! st (:released release-result)))
                      (when-let [e (or (:error release-result)
                                       (:error compensation))]
                        (throw e)))
                    (let [failed-release (:failed release-result)
                          final-held
                          (reduce (fn [m record]
                                    (assoc m (:owner record) record))
                                  (into {} (map (juxt :owner identity))
                                        desired-records)
                                  failed-release)
                          final-order
                          (into (mapv :owner desired-records)
                                (map :owner) failed-release)]
                      ;; Never overwrite a dead/disconnected cell or a newer
                      ;; selected capture. This swap has no user-code edge; the
                      ;; post-check covers a concurrent authority loss as well.
                      (swap! st
                             (fn [m]
                               (if (resource-capture-current? m cap)
                                 (install-resource-state
                                  m
                                  (assoc (:resources m)
                                         :resource-reservations reservations
                                         :resource-held final-held
                                         :resource-held-order final-order))
                                 m)))
                      (if (resource-capture-current? @st cap)
                        (when-let [e (:error release-result)]
                          (throw e))
                        (let [compensation
                              (when (= :connected (:lifecycle @st))
                                (compensate-resource-ensures!
                                 st (:attempted ensure-result)))]
                          (when (= :connected (:lifecycle @st))
                            (forget-exact-held! st (:released release-result)))
                          (when-let [e (or (:error release-result)
                                           (:error compensation))]
                            (throw e))))))))))))))
  nil)

(defn resource-reservations
  "Canonical site-keyed resource owner reservations (internal tool/test read)."
  [^ViewCell cell]
  (get-in @(state cell) [:resources :resource-reservations]))

(defn resource-held
  "Currently-held resource owners keyed by owner token (internal tool/test read)."
  [^ViewCell cell]
  (get-in @(state cell) [:resources :resource-held]))

(defn resource-state-installed?
  "True only for a ViewCell whose lease-capable wrapper installed lazy state
  (internal structural-erasure inspection seam)."
  [^ViewCell cell]
  (contains? @(state cell) :resources))

(defn resource-frame-incarnation-count
  "Distinct retained incarnation tokens for `frame-id` in a lease-capable
  cell's teardown index (internal compactness inspection seam)."
  [^ViewCell cell frame-id]
  (count (get (:extra-frame-incarnations @(state cell)) frame-id)))

(def ^:private resource-evidence-sentinel
  ;; Stable non-vacuity marker for the actual advanced DCE gate. Simple/dev
  ;; output retains this private debug-plane binding; goog.DEBUG=false removes
  ;; it together with resource-lease-evidence's body.
  "RF2_UI_RESOURCE_LEASE_EVIDENCE_SENTINEL")

(defn resource-lease-evidence
  "Dev-only bounded Xray join rows for the accepted resource plan. Carries
  view/commit/site/frame/owner identity and deliberately no descriptor, scope,
  or params. Returns nil in production."
  [^ViewCell cell]
  (when interop/debug-enabled?
    (let [_sentinel resource-evidence-sentinel
          st @(state cell)
          resources (:resources st)]
      (into []
            (keep (fn [sid]
                    (when-let [{:keys [frame-id owner commit-id]}
                               (get (:resource-reservations resources) sid)]
                      {:rf.ui/view (:view-id st)
                       :rf.ui/commit commit-id
                       :rf.ui/site sid
                       :frame frame-id
                       :owner owner})))
            (get-in resources [:resource-desired :order])))))

(def ^:private conflicting-resource-incarnations
  ::conflicting-resource-incarnations)

(defn- resource-capture-incarnations
  [cap]
  (reduce (fn [out {:keys [frame-id frame-token]}]
            (if (contains? out frame-id)
              (if (identical? (get out frame-id) frame-token)
                out
                (reduced conflicting-resource-incarnations))
              (assoc out frame-id frame-token)))
          {}
          (vals (:resource-by-site cap))))

(defn- accept-resource-capture
  [m cap]
  (let [desired {:order (or (:resource-order cap) [])
                 :by-site (or (:resource-by-site cap) {})}
        resources
        (cond-> (-> (:resources m)
                    (assoc :resource-desired desired
                           ;; Exact identity closes selected-layout A vs abandoned
                           ;; passive B: only this accepted capture may reconcile.
                           :resource-capture cap)
                    ;; Reconnect/commit supersedes any in-flight disconnect
                    ;; reservation-restore authority.
                    (dissoc :resource-disconnect-token))
          interop/debug-enabled?
          (update :resource-commit (fnil inc 0)))]
    (install-resource-state m resources)))

;; ---- lifecycle (03 §4) ------------------------------------------------------
;;
;; Three OBSERVABLE runtime states. The fact emitted at cleanup is always
;; `:disconnected {:reason :unknown}` — the platform gives no hide-vs-unmount
;; signal. Later evidence annotates the PRIOR interval, never the present: a
;; SETTLED reconnect proves an Activity hide (`:activity-hidden {:proof
;; :reconnect}`); an explicit host/root teardown proves an unmount (`:unmounted
;; {:proof :host-teardown}`).
;;
;; The settle qualifier is the rf2-vxgfnd.44 honesty fix: a reconnect within the
;; SAME synchronous commit as its disconnect is NOT a hide — it is React
;; StrictMode's dev mount→cleanup→remount replay, and asserting `:activity-hidden`
;; for it would fabricate a proof the runtime never observed. So `disconnect!`
;; marks each cleanup PROVISIONAL and `settle-disconnect!` (a microtask on CLJS)
;; clears it once the disconnect outlives its commit; only a disconnect that
;; survived a host yield can then be proven a hide. DEV-only — production has no
;; StrictMode double-invoke, so `:disconnect-provisional?` is never set and a
;; reveal is proven exactly as before.

(defn lifecycle
  "The cell's current runtime state keyword."
  [^ViewCell cell]
  (:lifecycle @(state cell)))

(defn intervals
  "The cell's lifecycle interval log (dev/tool read) — the emitted facts
  plus any retroactive annotations."
  [^ViewCell cell]
  (:intervals @(state cell)))

;; ---- root-incarnation ownership (03 §4; rf2-vxgfnd.85) ----------------------
;;
;; `live-cells` is the connected discoverability surface a FRAME-destroy sweep
;; consults: subscription leases contribute reactive-observation frames and
;; resource desired/reserved/held records contribute exact teardown-only pins.
;; The membership is dropped the instant the cell disconnects, so it cannot
;; survive an Activity hide. Root teardown needs an ownership association that
;; DOES survive a transient hide: the `root-cells` registry keyed by a per-mount
;; root incarnation. `attach-root!` (the mount seam) enrols a cell under its
;; root's incarnation; the cell stays enrolled across a hide and leaves
;; deterministically when it is proven dead (`detach-root!` from `teardown!`) —
;; or, because membership is WEAK (rf2-mc62sp), simply by being collected after
;; an ordinary reconciliation unmount dropped the last strong reference.
;; `teardown-root!` reaps a root's already-hidden cells through this registry.

(defn make-root-incarnation
  "Mint a FRESH, opaque root-incarnation token — a per-mount identity with no
  structure, compared only by `identical?`. A root that re-mounts under the same
  (reusable) root-id gets a DISTINCT incarnation, so a stale teardown carrying an
  old incarnation can never reap a replacement root's cells (rf2-vxgfnd.85). The
  mount seam mints one per root and threads it to every ViewCell under that root
  via `attach-root!`."
  []
  #?(:clj (Object.) :cljs (js-obj)))

;; ---- weak root membership (rf2-mc62sp) --------------------------------------
;;
;; The registry must survive an Activity hide (React gives no hide-vs-unmount
;; signal at cleanup), yet an ordinary reconciliation unmount runs ONLY that
;; same cleanup — so a strong set would pin every ordinarily-unmounted cell
;; (with its retained committed site values) until the whole root tears down.
;; Membership is therefore WEAK, per host idiom (mirroring the observation
;; port's weak node-record table): a hidden cell is strongly reachable from
;; React's retained fiber, so its entry lives exactly as long as Activity
;; retention; an unmounted cell becomes unreachable — nothing can ever
;; reconnect it — collects, and its entry clears with it.
;;
;;   - JVM: a synchronized `WeakHashMap`-backed keyset (the
;;     `re-frame.interop` weak-registry idiom) — stale entries expunge on
;;     every access, iteration under the wrapper's own lock.
;;   - CLJS: a `js/Set` of `js/WeakRef` plus an ephemeron `js/WeakMap`
;;     (cell -> its ref) for O(1) removal; a module `FinalizationRegistry`
;;     reaper prunes a collected cell's ref and drops the incarnation entry
;;     when its last member clears (where the host lacks the registry, husks
;;     are compacted opportunistically on iteration instead).

(defn- make-weak-member-set
  []
  #?(:clj  (java.util.Collections/synchronizedSet
            (java.util.Collections/newSetFromMap (java.util.WeakHashMap.)))
     :cljs {:refs (js/Set.) :by-cell (js/WeakMap.)}))

(defn- weak-empty?
  [members]
  #?(:clj  (.isEmpty ^java.util.Set members)
     :cljs (zero? (.-size ^js (:refs members)))))

(defn- drop-root-entry-if-empty!
  "Drop `incarnation`'s registry entry when its membership set has emptied —
  guarded so a concurrent re-attach (which installs or repopulates the entry)
  is never clobbered. Retry-safe inside `swap!`: the emptiness read is
  idempotent."
  [incarnation members]
  (when (weak-empty? members)
    (swap! root-cells
           (fn [m]
             (let [s (get m incarnation)]
               (if (and (identical? s members) (weak-empty? s))
                 (dissoc m incarnation)
                 m))))))

#?(:cljs
   (defonce ^:private root-cells-reaper
     ;; Finalization hook: when a member cell is collected, remove its cleared
     ;; WeakRef husk from its incarnation's set and drop the entry when that
     ;; was the last member — so GC-driven departure is as bounded as the
     ;; deterministic `detach-root!` path. Guarded: an exotic host without
     ;; FinalizationRegistry just leaves husks to iteration-time compaction.
     (when (exists? js/FinalizationRegistry)
       (js/FinalizationRegistry.
        (fn [held]
          (let [{:keys [incarnation ref]} held
                members (get @root-cells incarnation)]
            (when (some? members)
              (.delete ^js (:refs members) ref)
              (drop-root-entry-if-empty! incarnation members))))))))

(defn- weak-add!
  [members incarnation ^ViewCell cell]
  #?(:clj  (.add ^java.util.Set members cell)
     :cljs (let [by-cell ^js (:by-cell members)]
             (when-not (.has by-cell cell)
               (let [ref (js/WeakRef. cell)]
                 (.set by-cell cell ref)
                 (.add ^js (:refs members) ref)
                 (when (some? root-cells-reaper)
                   (.register root-cells-reaper cell
                              {:incarnation incarnation :ref ref}
                              ref)))))))

(defn- weak-remove!
  [members ^ViewCell cell]
  #?(:clj  (.remove ^java.util.Set members cell)
     :cljs (let [by-cell ^js (:by-cell members)]
             (when-some [ref (.get by-cell cell)]
               (.delete by-cell cell)
               (.delete ^js (:refs members) ref)
               (when (some? root-cells-reaper)
                 (.unregister root-cells-reaper ref))))))

(defn- weak-live
  "Snapshot the still-LIVE cells of one incarnation's weak membership set
  (nil-safe — an absent entry is no members). The teardown-discovery read:
  a cleared ref is a collected — therefore unreachable, therefore never
  reconnectable — cell with nothing left to reap; on CLJS its husk is
  compacted away as it is encountered."
  [members]
  (if (nil? members)
    []
    #?(:clj  (locking members (into [] members))
       :cljs (let [refs ^js (:refs members)
                   out  (array)]
               (.forEach refs
                         (fn [ref _ _]
                           (if-some [cell (.deref ^js ref)]
                             (.push out cell)
                             (.delete refs ref))))
               (vec out)))))

(defn- weak-live-count
  [members]
  (if (nil? members)
    0
    #?(:clj  (.size ^java.util.Set members)
       :cljs (count (weak-live members)))))

(defn- forget-root-cell!
  "Remove `cell` from `incarnation`'s weak membership set, DROPPING the
  incarnation entry entirely when its last cell leaves — so repeated
  mount/hide/unmount cycles never grow a historical registry
  (rf2-vxgfnd.85 AC5)."
  [^ViewCell cell incarnation]
  (when (some? incarnation)
    (when-some [members (get @root-cells incarnation)]
      (weak-remove! members cell)
      (drop-root-entry-if-empty! incarnation members))))

(defn- enrol-root-cell!
  "Add `cell` to `incarnation`'s WEAK membership set, installing the set on
  first use. Loops to self-heal the (concurrent-JVM, test-shaped) race where
  a simultaneous last-member removal drops the incarnation entry between the
  install and the add — the re-check guarantees the added cell is reachable
  through the CURRENT registry entry."
  [^ViewCell cell incarnation]
  (let [members (or (get @root-cells incarnation)
                    (let [fresh (make-weak-member-set)]
                      (-> (swap! root-cells update incarnation #(or % fresh))
                          (get incarnation))))]
    (weak-add! members incarnation cell)
    (when-not (identical? members (get @root-cells incarnation))
      (recur cell incarnation))))

(defn attach-root!
  "Mount seam: associate `cell` with root `incarnation` — the per-mount ownership
  token (`make-root-incarnation`) that SURVIVES a transient Activity disconnect
  and lets `teardown-root!` reap a cell already Activity-hidden BEFORE the host
  unmount window (rf2-vxgfnd.85). Enrols the cell in `root-cells` under
  `incarnation` (idempotent, and WEAK — membership never keeps an unmounted
  cell alive, rf2-mc62sp); re-attaching to a DIFFERENT incarnation first drops
  the old membership, so a cell can never straddle two roots. An Activity hide
  (which removes the cell from `live-cells`) does NOT drop this membership —
  that is the whole point. Returns the cell."
  [^ViewCell cell incarnation]
  (let [st  (state cell)
        old (:root @st)]
    ;; A resource/frame pin can reject and tear down the cell in the preceding
    ;; layout effect. The later lifecycle effect must not resurrect that dead
    ;; cell into root ownership.
    (when-not (= :dead (:lifecycle @st))
      (when (and (some? old) (not (identical? old incarnation)))
        (forget-root-cell! cell old))
      (swap! st assoc :root incarnation)
      (enrol-root-cell! cell incarnation)))
  cell)

(defn- detach-root!
  "Drop `cell`'s root-incarnation membership on FINAL teardown — the cell is
  proven dead, so it leaves the registry deterministically (the fast path;
  weak membership is the backstop for cells that were never proven dead).
  Idempotent; a no-op when the cell owns no root."
  [^ViewCell cell]
  (let [st  (state cell)
        inc (:root @st)]
    (when (some? inc)
      (forget-root-cell! cell inc)
      (swap! st assoc :root nil))))

(defn cell-root
  "The root incarnation `cell` is attached to, or nil (tool/test read)."
  [^ViewCell cell]
  (:root @(state cell)))

(defn root-cell-count
  "The number of root incarnations currently tracked, or — with `incarnation` —
  the number of LIVE cells its weak membership currently retains (tool/test
  read). Proves the ownership registry is bounded to retained/mounted cells:
  empty incarnations drop on final teardown (rf2-vxgfnd.85 AC5), and a
  collected member simply stops counting (rf2-mc62sp)."
  ([] (count @root-cells))
  ([incarnation] (weak-live-count (get @root-cells incarnation))))

(defn- release-committed!
  "Release every live lexical-site lease and retain each exact query/target/
  value record with `:lease nil`. A disconnect therefore owns nothing while a
  reconnect can still stabilize exact objects per site. Idempotent."
  [^ViewCell cell]
  (let [st (state cell)]
    (doseq [{:keys [lease]} (vals (:committed @st))]
      (when lease (obs/release! lease)))
    (swap! st update :committed
           (fn [sites]
             (reduce-kv (fn [out sid record]
                          (assoc out sid (assoc record :lease nil)))
                        (empty sites)
                        sites)))))

(defn- annotate-open-disconnect!
  "Upgrade the still-open `:disconnected {:reason :unknown}` interval's
  reason to `reason`+`proof` (the retroactive annotation). No-op when the
  last interval is not an open disconnect."
  [^ViewCell cell reason proof]
  (swap! (state cell) update :intervals
         (fn [ivs]
           (if (and (seq ivs) (= :disconnected (:state (peek ivs))))
             (conj (pop ivs)
                   (assoc (peek ivs) :reason reason :proof proof))
             ivs))))

(defn settle-disconnect!
  "Settle `cell`'s open PROVISIONAL disconnect — mark it a disconnect that
  outlived the synchronous commit that produced it, so a subsequent reconnect
  honestly proves an Activity hide rather than a same-tick React StrictMode
  replay (rf2-vxgfnd.44). No-op unless the cell is still `:disconnected` (a cell
  already reconnected or torn down needs no settle). On CLJS `disconnect!` arms
  this as a microtask — it fires after the synchronous commit unwinds and before
  the next paint, so ONLY a same-commit StrictMode replay can reconnect ahead of
  it; a genuine reveal (a later task) always finds the disconnect already
  settled. A headless/JVM fixture calls this explicitly to model the host yield
  of a real reveal. Returns nil."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (= :disconnected (:lifecycle @st))
      (swap! st assoc :disconnect-provisional? false)))
  nil)

(defn- arm-disconnect-settle!
  "Arm the settle of `cell`'s provisional disconnect. On CLJS a host microtask
  (`queue-microtask!`) that runs after the current synchronous commit unwinds
  and before the next paint — so React StrictMode's synchronous
  mount→cleanup→remount reconnects BEFORE it (a replay, un-annotated), while a
  genuine reveal (a later task) reconnects after it (proven a hide). No
  auto-settle on the JVM headless host (no StrictMode, no async render loop); a
  fixture there settles explicitly."
  [^ViewCell cell]
  #?(:cljs (queue-microtask! (fn [] (settle-disconnect! cell)))
     :clj  nil))

(defn- connect!
  "Commit-time lifecycle transition into `:connected`. A transition FROM
  `:disconnected` is a reconnect. A reconnect proves an Activity hide ONLY when
  the prior disconnect had SETTLED — i.e. it outlived the synchronous commit
  that produced it. A SETTLED-then-reconnected interval is a genuine hide→reveal
  and is annotated `:activity-hidden {:proof :reconnect}`. An UNSETTLED reconnect
  is a React StrictMode dev replay — the same cell's effect
  mount→cleanup→remount within ONE synchronous commit, where NO hide and NO
  unmount happened — so it is NOT annotated: the runtime must not fabricate an
  Activity-hide proof it never observed (rf2-vxgfnd.44). In production
  `:disconnect-provisional?` is never set (no StrictMode double-invoke), so a
  reveal is proven exactly as before."
  [^ViewCell cell]
  (let [st @(state cell)]
    (when (= :disconnected (:lifecycle st))
      (if (:disconnect-provisional? st)
        ;; same-tick StrictMode replay — the disconnect never settled; clear the
        ;; provisional flag and DO NOT fabricate an Activity-hide proof.
        (swap! (state cell) assoc :disconnect-provisional? false)
        (annotate-open-disconnect! cell :activity-hidden :reconnect)))
    (swap! (state cell) assoc :lifecycle :connected)
    ;; Enrol in the live-cell registry (idempotent — a set) so a frame-destroy
    ;; sweep can find this cell while it observes a live committed dep set.
    (swap! live-cells conj cell)))

(defn- disconnect*
  "Effects-cleanup transition (React unmount OR Activity hide —
  indistinguishable at this moment): release lease owners (hidden UI must
  not poll) and emit `:disconnected {:reason :unknown}`. The cell is
  reconnectable — a later commit on the same cell reacquires and
  corrects. Idempotent. Returns the cell.

  When a host/root teardown is in flight (`teardown-root!` armed the
  collection window — this cleanup is firing DURING a real `.unmount`), the
  disconnecting cell is captured so the driver can retroactively prove it an
  unmount. The emitted fact is STILL `:disconnected {:reason :unknown}` (the
  same immediate cleanup fact as an Activity hide — the two are
  indistinguishable here, 03 §4); the upgrade to `:unmounted` happens later, in
  `teardown-root!`. With the window unarmed (an Activity hide) nothing is
  captured and the cell stays reconnectable."
  [^ViewCell cell on-disconnect]
  (let [st (state cell)]
    (when (contains? #{:fresh :connected} (:lifecycle @st))
      (release-committed! cell)
      (discard-pending! cell)
      ;; Leave the live-cell registry: a disconnected cell holds no committed
      ;; deps, so it observes no frame — and an unmounted cell must not linger.
      (swap! live-cells disj cell)
      (swap! st (fn [m]
                  (-> m
                      (assoc :lifecycle :disconnected)
                      (update :intervals conj {:state :disconnected :reason :unknown}))))
      ;; Same-tick StrictMode-replay guard (DEV-only — DCEs in production, which
      ;; has no StrictMode double-invoke): mark this disconnect PROVISIONAL and
      ;; arm its settle. A reconnect BEFORE the settle is a synchronous replay
      ;; (mount→cleanup→remount in one commit — no hide); a reconnect AFTER it is
      ;; a genuine reveal that `connect!` proves an Activity hide (rf2-vxgfnd.44).
      (when interop/debug-enabled?
        (swap! st assoc :disconnect-provisional? true)
        (arm-disconnect-settle! cell))
      ;; Host/root teardown in flight: attribute this cell to it (03 §4).
      (when (some? @teardown-collector)
        (swap! teardown-collector conj cell))
      ;; Resource dispatch is deliberately LAST. Router trace listeners run
      ;; synchronously; a listener may reconnect or teardown, and this outer
      ;; disconnect must perform no stale lifecycle/registry write afterward.
      (when on-disconnect
        (on-disconnect cell)))
    cell))

(defn disconnect!
  "No-capability effects cleanup. Production views with only subscription
  sites call this path, so resource lifecycle code remains unreachable."
  [^ViewCell cell]
  (disconnect* cell nil))

(defn disconnect-resources!
  "Lease-capable effects cleanup. Hidden UI must not poll: every held owner is
  released while its reservation remains reusable for StrictMode/Activity
  reconnect."
  [^ViewCell cell]
  (disconnect* cell #(run-resource-lifecycle! % :disconnect)))

(defn teardown!
  "Explicit host/root teardown (root unmount, parent teardown, frame
  destroy): the frame/adapter/root is destroyed under this cell's handle —
  the retained interval is proven an unmount. Detaches leases, marks the
  cell `:dead` (no resume), annotates, and de-enrols it from the live-cell
  registry. Wired from core's frame-destroy path via `teardown-frame!` (the
  `:ui/on-frame-destroyed!` late-bind hook `re-frame.ui.frames` registers).
  Idempotent. Returns the cell."
  [^ViewCell cell]
  (let [st (state cell)]
    (when-not (= :dead (:lifecycle @st))
      (if (= :disconnected (:lifecycle @st))
        (annotate-open-disconnect! cell :unmounted :host-teardown)
        (swap! st update :intervals conj
               {:state :unmounted :reason :unmounted :proof :host-teardown}))
      (release-committed! cell)
      (let [on-teardown (:on-teardown @st)]
        (discard-pending! cell)
        ;; Terminalize and clear every generic registry BEFORE resource release
        ;; dispatch. A synchronous trace listener that re-enters teardown now
        ;; observes :dead and no-ops instead of recursively releasing forever.
        (swap! st (fn [m]
                    (-> m
                        (assoc :lifecycle :dead :committed {})
                        (dissoc :on-teardown :extra-frame-incarnations))))
        (swap! live-cells disj cell)
        ;; leave the root-incarnation registry — a dead cell is no longer
        ;; retained, so it must not linger as reapable ownership (rf2-vxgfnd.85).
        (detach-root! cell)
        ;; Final teardown drops both held ownership and reusable reservations.
        ;; Dispatch escapes are contained here so one bad owner cannot prevent
        ;; later cells or root-membership cleanup from becoming total. This is
        ;; the final reentrant step; no outer authoritative write follows it.
        (when on-teardown
          (on-teardown cell))))
    cell))

(defn teardown-frame!
  "Frame-destroy sweep: transition every currently-connected ViewCell observing
  frame `frame-id`, plus every still-disconnected root-owned ViewCell whose last
  published site values name it, to `:dead` (03 §4 dead-cell lifecycle). Each
  matched cell's leases are detached, its pending notification dropped, and
  its retained interval proven an unmount (`:unmounted {:proof
  :host-teardown}`) — so a subsequent read/probe on such a cell follows the
  dead-cell lifecycle instead of throwing `:rf.error/frame-destroyed` off the
  observation port. Fired from core's `frame/destroy-frame!` through the
  `:ui/on-frame-destroyed!` late-bind hook wired in `re-frame.ui.frames`;
  the sweep runs while the frame is still live, so each cell releases its
  leases against the live sub-cache (symmetric with `disconnect!`). The
  connected membership test uses `cell-retained-frame?`: committed
  subscription targets provide observation attribution, while resource
  desired/reservation/held records contribute exact incarnation tokens through
  `:extra-frame-incarnations`. An Activity-hidden cell holds no live
  subscription/resource owners, so its bounded root ownership plus retained
  subscription targets and resource reservations supply corresponding
  discoverability without another global registry. Iterates snapshots, so the
  per-cell `teardown!` de-enrol is safe. Returns the count torn down."
  [frame-id]
  (let [frame-token (frame/frame-incarnation-token frame-id)
        ;; Observation ownership retains its historical bare-id discovery;
        ;; resource ownership additionally requires the exact render-captured
        ;; incarnation so A's teardown cannot reap a same-id B reservation.
        connected (filter #(cell-retained-frame? % frame-id frame-token)
                          @live-cells)
        hidden    (into #{}
                        (comp (mapcat (comp weak-live val))
                              (filter #(= :disconnected (lifecycle %)))
                              (filter #(cell-retained-frame?
                                        % frame-id frame-token)))
                        @root-cells)
        victims   (into hidden connected)]
    (doseq [cell victims]
      (teardown! cell))
    (count victims)))

(defn teardown-root!
  "Explicit host/ROOT teardown driver (03 §4) — the root-path counterpart to
  the frame-destroy sweep, driven from `re-frame.ui.client/unmount!*` at the
  host teardown moment. Runs `unmount-thunk` (the host React `.unmount`) with a
  collection window armed, so every ViewCell whose effect-cleanup fires DURING
  the host unmount is captured (`disconnect!` enrols it — see the
  `teardown-collector` window). Because a host `.unmount` sweeps the cleanups of
  EXACTLY its own root's tree, the captured set is precisely the cells belonging
  to that root — sibling and nested-portal roots are structurally isolated (they
  are separate React roots the sweep never touches).

  Each captured cell is then retroactively proven an unmount via the shared
  `teardown!` primitive (the SAME machinery the frame-destroy path uses, not a
  parallel one): its transient `:disconnected {:reason :unknown}` interval —
  which effect cleanup ALREADY emitted, indistinguishable from an Activity hide
  at that moment — is upgraded to `:unmounted {:proof :host-teardown}` and the
  cell goes `:dead`, so a retained handle can never reconnect after its root is
  gone and a late recommit fails through the dead-cell lifecycle (commit! step
  2) rather than by probing a torn-down context. An Activity hide, by contrast,
  disconnects with NO window armed, is never captured, and stays reconnectable —
  a reveal proves it `:activity-hidden {:proof :reconnect}`.

  ## Reaping cells already Activity-hidden before the window (rf2-vxgfnd.85)

  The window alone captures ONLY cells whose effect cleanup fires DURING the host
  unmount. A ViewCell hidden by React Activity/Offscreen BEFORE the window is
  armed already left `:fresh`/`:connected` for `:disconnected`, so its cleanup can
  never re-enrol it — React does not re-run an already-destroyed effect when it
  discards the hidden fiber at root unmount. Such a cell would linger
  `:disconnected {:reason :unknown}` and RECONNECTABLE after its root is gone. So
  teardown ALSO consults the `root-cells` ownership registry: after the window
  closes it reaps every still-`:disconnected` cell owned by a torn-down root
  incarnation. Two incarnation sources, unioned:

    - `root-incarnation` — the explicit incarnation the caller (the mount/root
      layer) names for the root being unmounted. This is the deterministic path:
      it reaps a root's hidden cells even when the window captured NONE (the whole
      root hidden, or a single already-hidden cell).
    - the incarnations of every WINDOW-CAPTURED cell — a captured cell names its
      own root's incarnation, so its still-hidden siblings under the same root are
      reaped too, even when no explicit incarnation is supplied.

  Only `:disconnected` owned cells are reaped; a still-`:connected` cell of a
  SIBLING root's incarnation is never in the set, and an incarnation is a fresh
  per-mount identity, so a replacement root under the same root-id is untouched.

  Ordering-robust and re-entrancy-safe by save/restore. If `unmount-thunk`
  THROWS, the original host error still propagates, but an EXPLICIT
  `root-incarnation` is a framework ownership token: every cell belonging to
  that exact generation is force-dead and its leases are released before the
  throw escapes. React may have consumed the host Root handle even though its
  synchronous flush refused; retaining connected observations after the client
  releases that handle would create unreachable framework ownership. The fresh
  incarnation token keeps this fail-closed reap isolated from a later same-id
  replacement. The one-arity form has no generation evidence and therefore can
  reap only cells actually captured by the teardown window. Returns the count of
  cells torn down on success; a host failure rethrows after the targeted reap.

  Two arities: `[unmount-thunk]` (no explicit incarnation — window + captured-cell
  incarnations only, the current `client/unmount!*` call) and
  `[root-incarnation unmount-thunk]` (the incarnation-aware path)."
  ([unmount-thunk] (teardown-root! nil unmount-thunk))
  ([root-incarnation unmount-thunk]
   (let [prev       @teardown-collector
         host-error (volatile! nil)
         captured   (volatile! #{})
         _          (do
                      (reset! teardown-collector #{})
                      (try
                        (unmount-thunk)
                        (catch #?(:clj Throwable :cljs :default) e
                          (vreset! host-error e))
                        (finally
                          (vreset! captured @teardown-collector)
                          (reset! teardown-collector prev))))
         collected  @captured
         ;; the incarnations whose hidden cells this teardown owns: the explicit
         ;; one plus every window-captured cell's own incarnation.
         incs      (cond-> (into #{} (keep cell-root) collected)
                     (some? root-incarnation) (conj root-incarnation))
         ;; already-Activity-hidden owned cells the window could NOT capture —
         ;; still `:disconnected`, belonging to a torn-down incarnation. A
         ;; hidden-but-alive cell is pinned by React's retained fiber, so weak
         ;; membership still discovers it; only collected (unreachable, never
         ;; reconnectable) cells are absent — nothing to reap (rf2-mc62sp).
         hidden    (into #{}
                         (comp (mapcat #(weak-live (get @root-cells %)))
                               (filter #(= :disconnected (lifecycle %))))
                         incs)
         victims   (if (and @host-error (some? root-incarnation))
                     ;; The host handle is consumed/released even on this path.
                     ;; Fail closed over the EXACT root generation, including
                     ;; cells still connected because React ran no cleanup.
                     (into collected (weak-live (get @root-cells root-incarnation)))
                     (into collected hidden))]
     (doseq [cell victims]
       (teardown! cell))
     (if @host-error
       (throw @host-error)
       (count victims)))))

;; ---------------------------------------------------------------------------
;; The 8-step layout-commit reconciler (03 §3)
;; ---------------------------------------------------------------------------

(defn- evidence-moved?
  "Did the target move in the render→commit gap? Compares the acquire-time
  `read` against the render's `probe` evidence. A cold probe
  (`:node-version nil`) falls back to `rf=` on value; a live probe compares
  the node IDENTITY (`:node-key`), the node version, and the frame/registry
  epochs (belt-and-braces the two-guard rule leans on).

  The `:node-key` clause (ABI v2, rf2-vxgfnd.14/.93) is the reincarnation axis:
  a same-id frame DESTROY + RECREATE across the gap builds a FRESH reaction with
  a strictly-greater `:node-key`, so the render probed one node's key and the
  commit read a DIFFERENT node's key — MOVEMENT the reconciler must correct
  before paint. Version + epochs ALONE can tie across the two incarnations
  (`frame/dissoc-frame!` restarts the commit epoch), so without this clause the
  reincarnation reads as unchanged. The unchanged-node fast path is preserved:
  the same live node reads the same key/version/epochs, so no correction fires.
  The cold-probe (`:node-version nil`) and static-override (`read` returns no
  `:node-key`, probed cold) branches are untouched."
  [read-result probe-ev]
  (if (nil? (:node-version probe-ev))
    (not (eq/rf= (:value read-result) (:value probe-ev)))
    (or (not= (:version read-result) (:node-version probe-ev))
        (not= (:node-key read-result) (:node-key probe-ev))
        (not= (:frame-epoch read-result) (:frame-epoch probe-ev))
        (not= (:registry-epoch read-result) (:registry-epoch probe-ev)))))

;; ---- frame-close revalidation: incarnation-safe (rf2-vxgfnd.88, extends .61) ---
;;
;; A commit publishing ownership must resolve against the EXACT frame incarnation
;; it acquired its leases from — never merely the reused frame-id. `destroy-frame!`
;; + a fresh same-id construction mints a DISTINCT incarnation token
;; (`frame/frame-incarnation-token` — the record's `:drain-lock`, stable across one
;; incarnation, distinct across destroy+recreate), so comparing the token captured
;; at ACQUIRE against the live token catches a same-id reincarnation the bare-id
;; `frame-closing?` check alone misses: a commit that acquired incarnation A but
;; finds B live under the id must JOIN A's teardown, not publish `:connected`
;; against the replacement (rf2-vxgfnd.88). The bare-id `frame-closing?` clause is
;; RETAINED for the .61 in-flight window (A mid-teardown, still live pre-flip,
;; token unchanged — the marker is the only signal there).

(defn- committed-frame-incarnations
  "Post-acquire snapshot `{frame-id -> incarnation-token}` for every frame the
  candidate observation map observes. Capability-specific callers may merge
  additional render-time pins before the common close revalidation."
  [committed]
  (persistent!
   (reduce (fn [acc {:keys [target lease]}]
             (if (and lease (= :subscription (:kind target)))
               (let [fid (:frame-id target)]
                 (assoc! acc fid (frame/frame-incarnation-token fid)))
               acc))
           (transient {})
           (vals committed))))

(defn- incarnation-superseded?
  "True when ANY frame in the acquire-time `incarnations` snapshot is no longer
  live under the SAME incarnation token — its incarnation was destroyed (nil now)
  or REPLACED by a fresh same-id incarnation (a distinct token). The
  exact-identity half of the frame-close revalidation (rf2-vxgfnd.88): a commit
  must resolve against the incarnation it ACQUIRED from, never the reused id."
  [incarnations]
  (reduce-kv (fn [_ fid captured]
               (if (identical? captured (frame/frame-incarnation-token fid))
                 false
                 (reduced true)))
             false
             incarnations))

(def ^:dynamic ^:no-doc *commit-barrier*
  "JVM/DOM linearization TEST SEAM — nil in production (two nil checks per commit,
  zero further cost), NEVER bound off a test path (the `*upsert-decide-probe*`
  idiom). Bound to a `(fn [phase cell] …)`, `commit!` calls it at two
  deterministic points so a fixture can interleave a frame destroy / same-id
  reincarnation with a commit:

    :pre-acquire   — after the render capture is loaded, BEFORE the kept-check /
                     stage-acquire. A same-id DESTROY+RECREATE here makes the
                     commit ACQUIRE the FRESH incarnation while the render probed
                     the destroyed one — the `:node-key` reincarnation
                     `evidence-moved?` must correct before paint (rf2-vxgfnd.93).
    :post-stage-acquire — after leases are acquired, BEFORE the incarnation
                     snapshot/current validation.
    :post-acquire  — after validation + the evidence read, BEFORE the publish.
                     A full destroy of the ACQUIRED incarnation + a fresh same-id
                     incarnation here proves the frame-close revalidation joins the
                     commit to the acquired incarnation's teardown, not the
                     replacement id (rf2-vxgfnd.88)."
  nil)

(defn- commit*
  "Run the 8-step layout commit for `cell` against the exact immutable
  `capture` returned beside the committed host element by `with-capture`.
  Idempotent: an unchanged committed set + capture reconciles to a
  no-op (kept-check retains every lease untouched), so StrictMode's
  release/reacquire replay is naturally balanced.

  1. Reject a stale-generation capture (HMR) — return `:stale`, the host
     re-renders (no ownership touched).
  2. A `:dead` cell fails loudly — reconnection after teardown is not
     allowed.
  3. Kept-check every previously-committed site with `(current? lease
     target)`; unchanged live leases are RETAINED untouched, a failed check
     (disposed node, frame swap, restabilized query, moved override)
     classifies the site as retargeted.
  4. STAGE-acquire every newly-observed or retargeted target BEFORE
     releasing anything (acquire-before-release — a shared node never falls
     through its zero-owner edge). On ANY acquisition failure every staged
     lease is synchronously released in REVERSE acquisition order, the
     prior committed set stays installed, and the typed error propagates.
  5. Compare each acquired node's version + frame/registry epochs against
     the render's probe evidence — for BOTH retained and staged leases, so a
     retained site's movement is caught here on a non-watchable headless host
     that has no value-movement watch (rf2-vxgfnd.39).
  6. Publish the committed site values + the new dependency set (retained +
     staged) — before the user can interact with the new DOM.
  7. Release the prior leases of dropped + retargeted sites.
  8. If any evidence moved in the render→commit gap, advance the revision
     and notify — React corrects BEFORE paint.

  Returns `cell` on a normal commit or `:stale` on a rejected generation."
  [^ViewCell cell cap extra-incarnations accept-capture]
  (let [st  (state cell)
        st0 @st]
    (cond
      ;; step 1 — stale body revision. The cell-local comparison catches an
      ;; explicit sync/remount. The authoritative slot comparison closes the
      ;; harder render(old) → registration(new) → layout(old) window even when
      ;; the cell has not rendered again yet. Direct/headless callers with no
      ;; registered slot retain the cell-local contract.
      (or (not= (:generation cap) (:generation st0))
          (and interop/debug-enabled?
               (when-some [current (registered-view-revision (:view-id st0))]
                 (not= (:generation cap) current))))
      :stale

      :else
      (do
        ;; step 2 — dead cell fails loudly (no resume). The context is gone,
        ;; so the always-on `:rf.error/frame-destroyed` is the honest id (no
        ;; new catalogue row): reconnection after teardown is not allowed.
        (when (= :dead (:lifecycle st0))
          (error/throw-error!
            :rf.error/frame-destroyed
            're-frame.ui.reactive/commit!
            (str "a ViewCell commit reached a :dead cell (view " (:view-id st0)
                 ") — the frame/root was torn down under a retained handle; a "
                 "dead cell cannot resume")
            {:extra {:view-id (:view-id st0)}}))
        ;; :pre-acquire test seam — a fixture may reincarnate the frame in the
        ;; render→commit gap here so the stage-acquire below binds the FRESH
        ;; incarnation while the render probed the destroyed one (rf2-vxgfnd.93).
        (when-some [barrier *commit-barrier*] (barrier :pre-acquire cell))
        (let [committed  (:committed st0)          ;; sid -> site record
              new-order  (:order cap)              ;; sid, render order
              new-by     (:by-site cap)
              new-set    (set new-order)
              ;; step 3 — kept-check
              retained   (persistent!
                           (reduce
                             (fn [acc [sid prior]]
                               (let [lease (:lease prior)]
                                 (if (and lease
                                          (contains? new-set sid)
                                          (obs/current? lease
                                                        (:target (new-by sid))))
                                   (assoc! acc sid
                                           (assoc (select-keys (new-by sid)
                                                               [:query :target :value])
                                                  :lease lease))
                                 acc)))
                             (transient {})
                             committed))
              retained?  (fn [sid] (contains? retained sid))
              to-release (persistent!
                           (reduce
                             (fn [acc [sid record]]
                               (if (or (retained? sid) (nil? (:lease record)))
                                 acc
                                 (assoc! acc sid record)))
                             (transient {})
                             committed))
              to-acquire (into [] (remove retained?) new-order)
              on-change  (on-change-fn cell)
              ;; step 4 — transactional stage-acquire
              staged     (loop [ks     to-acquire
                                acc    []]
                           (if (empty? ks)
                             acc
                             (let [sid    (first ks)
                                   target (:target (new-by sid))
                                   lease  (try
                                            (obs/acquire! target on-change)
                                            (catch #?(:clj Throwable :cljs :default) e
                                              ;; rollback: release staged in
                                              ;; REVERSE acquisition order; the
                                              ;; prior committed set stays
                                              ;; installed; propagate the throw.
                                              (doseq [[_ record] (rseq acc)]
                                                (obs/release! (:lease record)))
                                              (throw e)))]
                               (recur (rest ks)
                                      (conj acc
                                            [sid (assoc (select-keys (new-by sid)
                                                                     [:query :target :value])
                                                        :lease lease)])))))
              staged-map (into {} staged)
              candidate (merge retained staged-map)
              ;; A JVM fixture can destroy/recreate the just-acquired frame in
              ;; the formerly-uncovered acquire→snapshot window.
              _ (when-some [barrier *commit-barrier*]
                  (barrier :post-stage-acquire cell))
              observed-incarnations (committed-frame-incarnations candidate)
              incarnations (if (seq extra-incarnations)
                             (merge observed-incarnations extra-incarnations)
                             observed-incarnations)
              candidate-current?
              (every? (fn [[sid record]]
                        (obs/current? (:lease record) (:target (new-by sid))))
                      candidate)]
          (if-not candidate-current?
            (do
              ;; One of the acquired/retained leases belonged to an incarnation
              ;; that vanished before a trustworthy snapshot could be paired
              ;; with it. Roll back only newly staged ownership, preserve the
              ;; prior committed set, and synchronously invalidate so the host
              ;; re-probes the current incarnation before paint.
              (doseq [[_ record] (rseq staged)]
                (obs/release! (:lease record)))
              (advance-revision! cell)
              cell)
            (let [
              ;; step 5 — evidence comparison: read EACH acquired node (staged AND
              ;; retained) against the render's probe evidence, so movement in the
              ;; render→commit gap is caught before paint (invariant 5).
              ;;
              ;;   - STAGED leases: their freshly-installed watch could not have
              ;;     fired for a pre-acquire gap move, so step 5 is the SOLE catch
              ;;     on every host.
              ;;   - RETAINED leases: on a WATCHABLE host their live watch already
              ;;     caught the move (the cell is pending; its scheduled flush
              ;;     corrects before paint), but on a NON-WATCHABLE headless host
              ;;     there is NO watch — so step 5 is the ONLY catch. Without this
              ;;     read a retained site's headless movement is corrected by
              ;;     nothing: `:values` publishes the stale render value and no
              ;;     revision advances (rf2-vxgfnd.39).
              ;;
              ;; `read` recomputes the plain-atom node on deref, so a headless move
              ;; is observed here; the two catches are kept distinct because the
              ;; step-8 advance treats them differently (see below).
              moved-in? (fn [[sid record]]
                          (evidence-moved? (obs/read (:lease record))
                                           (:evidence (new-by sid))))
              staged-moved?   (boolean (some moved-in? staged))
              retained-moved? (boolean (some moved-in? retained))
              new-committed candidate]
          ;; :post-acquire test seam — a fixture may destroy the ACQUIRED
          ;; incarnation + recreate the id here to prove the revalidation below
          ;; joins this commit to the acquired incarnation's teardown, not the
          ;; replacement id (rf2-vxgfnd.88).
          (when-some [barrier *commit-barrier*] (barrier :post-acquire cell))
          ;; step 6 — publish exact per-site query/value + dependency set
          (swap! st
                 (fn [m]
                   (let [m* (assoc m :committed new-committed)]
                     (if accept-capture
                       (accept-capture m* cap)
                       m*))))
          ;; step 7 — release dropped + retargeted prior leases
          (doseq [[_ record] to-release]
            (obs/release! (:lease record)))
          ;; lifecycle: connect (reconnect annotation when re-committing a
          ;; hidden cell). This ENROLS the cell into the live-cell registry —
          ;; the discoverability publish a frame-destroy sweep consults.
          (connect! cell)
          ;; INCARNATION-SAFE frame-close revalidation (rf2-vxgfnd.88, extending
          ;; rf2-vxgfnd.61). Two reasons this commit must JOIN a teardown instead
          ;; of publishing `:connected`, checked against the ACQUIRE-time
          ;; incarnation snapshot rather than the bare frame-id:
          ;;
          ;;   (a) `incarnation-superseded?` — a frame this commit acquired from
          ;;       is no longer live under the SAME incarnation token: it was
          ;;       destroyed, OR a fresh same-id incarnation replaced it in the
          ;;       render→commit gap. The bare-id `frame-closing?` MISSES this once
          ;;       the old incarnation's teardown completed and cleared its marker
          ;;       while a replacement went live under the id — so an old lease
          ;;       would otherwise survive on the replacement id (rf2-vxgfnd.88).
          ;;       Token identity (`frame/frame-incarnation-token`, the record's
          ;;       `:drain-lock`, distinct per incarnation) resolves the commit to
          ;;       exactly the incarnation it targeted.
          ;;
          ;;   (b) `frame-incarnation-closing?` — the ACQUIRED incarnation is
          ;;       IN-FLIGHT closing (rf2-vxgfnd.61, scoped to the incarnation by
          ;;       rf2-vxgfnd.94). #5731 wires destroy to a SNAPSHOT sweep of the
          ;;       live cells that runs while the frame is still LIVE (pre-flip),
          ;;       then flips liveness. A commit that acquires + enrols between the
          ;;       sweep snapshot and the flip is MISSED by the sweep, and its
          ;;       incarnation token is UNCHANGED (the frame is still live pre-flip)
          ;;       — so `incarnation-superseded?` alone would not catch it. But
          ;;       `destroying-frames` is populated at the TOP of `destroy-frame!`,
          ;;       so the marker is continuously present across the whole teardown
          ;;       window: the enrolled cell observes the close and joins the
          ;;       teardown against the still-releasable cache. Scoping to the
          ;;       ACQUIRE-time token (not the bare id) is what closes .88's
          ;;       reciprocal Failure-2: in the JVM window where an OLD incarnation
          ;;       A's marker is still set (post-`dissoc-frame!`, pre-`finally`)
          ;;       while a fresh same-id incarnation B is already live, a commit
          ;;       that acquired B reads FALSE here (B's token ≠ A's marker token),
          ;;       so A's stale close authority cannot tear down a cell that owns B
          ;;       (rf2-vxgfnd.94). The bare-id `frame/frame-closing?` would read
          ;;       true for the reused id and wrongly reap B's cell.
          ;;
          ;; A live, not-closing frame under an unchanged incarnation (incl. a
          ;; committed fresh same-id incarnation this commit legitimately acquired)
          ;; makes both checks false — disjoint frames commit/destroy concurrently,
          ;; and the single-threaded CLJS host (destroy runs to completion without
          ;; yielding to a commit) never sees either true.
          (if (or (incarnation-superseded? incarnations)
                  (some (fn [[fid token]]
                          (frame/frame-incarnation-closing? fid token))
                        incarnations))
            (teardown! cell)
            ;; step 8 — moved evidence corrects before paint. The staged catch
            ;; always advances synchronously; a RETAINED catch advances only when
            ;; no live watch already caught the move — a pending (`dirty?`) cell is
            ;; a watchable host whose scheduled flush already corrects before paint,
            ;; so advancing here too would add a redundant render (rf2-vxgfnd.39).
            (when (or staged-moved?
                      (and retained-moved? (not (dirty? cell))))
              (advance-revision! cell)))
              cell)))))))

(defn commit!
  "Commit an observation-only capture without installing or copying resource
  state. This is the production path for the 0/0 and 1/0 capability shapes."
  [^ViewCell cell cap]
  (commit* cell cap nil nil))

(defn commit-resources!
  "Commit a lease-capable capture with its exact render-time frame pins and
  ownership-free desired plan. Owner minting remains passive."
  [^ViewCell cell cap]
  (let [incarnations (resource-capture-incarnations cap)]
    (if (= conflicting-resource-incarnations incarnations)
      ;; One render cannot coherently target two incarnations of the same frame
      ;; id. Reject before generic commit publishes/connects; passive reconcile
      ;; then sees a dead cell and cannot mint or dispatch.
      (teardown! cell)
      (commit* cell cap incarnations accept-resource-capture))))

;; ---- test/inspection reads --------------------------------------------------

(defn committed-target-keys
  "Legacy target projection of the cell's LIVE installed dependency set.
  Equal-query lexical sites intentionally collapse in this compatibility view;
  use `committed-sites` to inspect ownership identity."
  [^ViewCell cell]
  (into #{}
        (comp (filter :lease) (map (comp target-key :target)))
        (vals (:committed @(state cell)))))

(defn committed-values
  "Legacy target-keyed value projection. Equal targets collapse here by
  definition; canonical state is `committed-sites`."
  [^ViewCell cell]
  (persistent!
    (reduce (fn [out {:keys [target value]}]
              (assoc! out (target-key target) value))
            (transient {})
            (vals (:committed @(state cell))))))

(defn committed-lease
  "Legacy target-keyed installed lease projection, or nil. If two sites share
  a target this returns one of their distinct leases; use `committed-site`."
  [^ViewCell cell tk]
  (some (fn [{:keys [target lease]}]
          (when (= tk (target-key target)) lease))
        (vals (:committed @(state cell)))))

(defn committed-sites
  "The canonical `{sid -> {:query :target :value :lease}}` lexical ownership
  map for `cell` (internal tool/test seam). Disconnected records remain with
  `:lease nil`; conditionally absent sites are not present."
  [^ViewCell cell]
  (:committed @(state cell)))

(defn committed-site
  "The canonical committed record for lexical `sid`, or nil."
  [^ViewCell cell sid]
  (get (:committed @(state cell)) sid))

(defn revision
  "The cell's current revision integer (tool/test read)."
  [^ViewCell cell]
  (:revision @(state cell)))

(defn cell-view-id
  "The view id `cell` was minted for (tool/test read) — the stable authoring
  identity axis the `re-frame.ui.tool.evidence` projection keys a
  developer-facing row on, alongside the owning root (rf2-vxgfnd.75)."
  [^ViewCell cell]
  (:view-id @(state cell)))

(defn current-live-cells
  "The set of currently-CONNECTED ViewCells (tool/test read) — the live-cell
  registry a frame-destroy sweep consults, and the seam a DOM lifecycle fixture
  grabs a mounted cell through to observe its post-unmount lifecycle."
  []
  @live-cells)
