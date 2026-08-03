(ns re-frame.substrate.spine
  "Shared substrate-spine helpers for React-shaped adapters that lack a
  native reactive-atom primitive (UIx and any future minimal-
  React-wrapper substrate). UIx and Helix (removed at S7/W13,
  rf2-d6epb) once duplicated this body byte-for-byte modulo gensym
  prefixes, hook ns, and substrate-name strings; per-adapter wiring
  goes through `make-react-spine`.

  Scope. This ns provides:

    * The plain-`atom` container quartet (make / read / replace / subscribe).
    * `make-derived-value` (one watch per source, coalesced through a
      shared per-adapter epoch scheduler so a multi-input derived value
      recomputes glitch-free and notifies once per coherent input epoch;
      reifies the re-frame-owned `re-frame.disposable/IDisposable`, and —
      because its fan-out is `rf=`-gated — the optional re-frame-owned
      `re-frame.movement/IMovementWitness`).
    * React 18+ root renderer (createRoot + render, hydrateRoot for
      hydrate).
    * Late-bind hiccup-emitter atom + `render-to-string` thrower.
    * The chained source-coord wrapper (`format-source-coord`,
      `dom-element?`, `inject-source-coord-attr`, `warn-non-dom-root!`,
      `clear-warned-non-dom-roots!`, `wrap-view`) parameterised on the
      substrate-name string for the warning text.
    * `flush-views!` with a correct `react-dom/test-utils` fallback
      (subsumes rf2-jk7hr).
    * A factory `make-react-spine` that produces the per-substrate
      hook-based surfaces (`use-current-frame`, `use-subscribe`,
      `frame-provider`, `register-context-provider`) given the
      substrate's hook fns.

  Reagent and reagent-slim do NOT use this ns: they have a native
  reactive-atom primitive (`r/atom` + `ratom/make-reaction`) and a
  Reagent-component-shaped frame-provider in `re-frame.views`, so the
  shapes diverge from the React-hook-shaped contract here.

  Per Spec 006 §CLJS reference — adapters using this spine remain
  shape-compliant with the ten-fn substrate contract (six required +
  three optional + one lifecycle)."
  (:require ["react"             :as React]
            ["react-dom"         :as react-dom]
            ["react-dom/client"  :as react-dom-client]
            [re-frame.disposable :as rf-disposable]
            [re-frame.error      :as rf-error]
            [re-frame.frame      :as frame]
            [re-frame.interop    :as interop]
            [re-frame.late-bind  :as late-bind]
            [re-frame.movement   :as movement]
            [re-frame.subs       :as subs]
            [re-frame.trace      :as trace]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.adapter.context :as adapter-context]))

;; ---- epoch scheduler (glitch-freedom; Spec 006 §Invalidation algorithm) ----
;;
;; Reagent realises the Phase 1/2/3 invalidation contract automatically:
;; each `Reaction` re-runs once per `r/flush!` against settled inputs, so
;; a multi-input layer-2+ reaction never sees a half-updated input set and
;; never notifies more than once per app-db change. The spine has no
;; reaction primitive, so it must satisfy the contract explicitly
;; (Spec 006: "Non-CLJS implementations must satisfy the contract
;; explicitly").
;;
;; The bug a naive spine has (rf2-i21f5): wiring one `add-watch` per
;; source that recomputes-and-notifies INLINE means a layer-2+ sub with N
;; changed inputs recomputes once per changed input. The first source's
;; notify drives the downstream recompute; the second source's notify
;; drives it AGAIN, and so on — N recomputes per app-db change instead of
;; one. Each redundant recompute re-runs the user's sub body and emits a
;; `:sub/run` trace, and a downstream layer-3 sub re-fires per redundant
;; layer-2 notification, fanning the waste out across the whole `:<-`
;; graph. (The spine's derived value is pull-based — `-deref` recomputes
;; fresh from current sources — so each recompute reads settled source
;; state and the final notified VALUE is correct; the defect is the
;; redundant recompute storm + over-notification, not a wrong value. The
;; downstream `notify` only fires on a `=`-change, which masks the storm
;; for `=`-stable bodies but not for the recompute work, the trace
;; emissions, or any body with observable per-call cost.) Reagent is
;; immune (native batched `r/flush!`); the spine must satisfy the Phase
;; 1/2/3 contract explicitly.
;;
;; The fix mirrors Reagent's batched flush. A single per-adapter
;; scheduler is shared by `replace-container!` (the only app-db mutation
;; entry point, Spec 006 §revertibility) and every `make-derived-value`.
;; `replace-container!` brackets its `reset!` in an epoch: source watches
;; fired by the `reset!` only MARK their derived value dirty (enqueue a
;; recompute-and-notify thunk) instead of recomputing inline. When the
;; outermost epoch closes, the scheduler drains the queue. A derived
;; value's recompute, on changing by `=`, notifies its watchers — which
;; for a downstream derived value's source-watch marks THAT derived dirty
;; and enqueues it in turn. Because a dirty-flag dedups re-marks within an
;; epoch, every derived value recomputes EXACTLY ONCE against fully
;; settled inputs and notifies its subscribers at most once — exactly the
;; Phase 1/2/3 ordering (one Phase-3 notification per dirty entry).
;;
;; Outside an epoch (a direct `reset!`/`swap!` on a source by test code or
;; tooling, rather than through `replace-container!`) the mark falls
;; through to an immediate single-derived flush so the contract still
;; holds for the one-source-changed case.
;;
;; `depth` / `flushing?` / `queue` are written and read only on the
;; single-threaded JS event loop and never escape the adapter closure —
;; `volatile!` is the right primitive, no CAS cost.

(def ^:private capture-none
  "Presence sentinel shared by the failure seams — the derived fan-out's
  first-thrown capture (`notify`) and the scheduler's earliest-escape cell
  (`:escaped`, read/written by `drain-scheduler!` + `with-epoch`). A distinct
  object identity so a subscriber / recompute that throws `nil`/`false` (both
  legal CLJS throws) is still recorded and re-raised by PRESENCE — never masked
  by a truthiness test (rf2-vxgfnd.203, rf2-qcmzc, rf2-2u4rw)."
  (js-obj))

(defn- pick-third
  "Reducing fn that returns the map value, discarding accumulator + key.
  Hoisted (top-level, allocated once) so `sole-val` pays no per-call closure."
  [_ _ v]
  v)

(defn- sole-val
  "The single value of a one-entry map, WITHOUT the map-seq + `MapEntry`
  `(val (first m))` allocates (rf2-2u4rw common-path preservation). `reduce-kv`
  walks the backing array directly and returns the sole value; `nil`/`false`
  values survive (it returns whatever `pick-third` yields). Caller guarantees
  exactly one entry (the `(case (count ws) 1 …)` fast path)."
  [m]
  (reduce-kv pick-third nil m))

(defn make-scheduler
  "Return a fresh per-adapter epoch scheduler cell. Each adapter owns its
  own so multiple React-shaped adapters can coexist in a test bundle
  without sharing an epoch queue.

  `:escaped` is the scheduler-lifetime EARLIEST-ESCAPE cell (rf2-2u4rw):
  the first thunk failure a drain contains, held by PRESENCE across the
  `with-epoch` body/finally boundary, so the earliest primary failure
  surfaces once (E1 over any later E2) after all owned work is attempted.
  One volatile per scheduler — no per-drain capture allocation.

  `:source-coordinators` is the per-source fan-out registry (rf2-7ryt0): a
  `js/Map` from a raw atom SOURCE to its single coordinating watch. A direct
  `reset!` fires that ONE watch, which brackets the whole dependent fan-out
  in a `with-epoch` — so a raw source mutation drains every dependent once
  and surfaces the earliest failure at a REAL terminal, exactly like
  `replace-container!` (see `ensure-source-coordinator!`).

  `:queue` / `:queued` are the drain's SCRATCH — a JS array and a `js/Set`,
  mutated in place, rather than a `volatile!` holding a persistent vector
  and a persistent set (rf2-jr76s). This is the same reasoning that already
  made `depth` / `flushing?` volatiles one line above: both are written and
  read only on the single-threaded JS event loop, and neither ever escapes
  this scheduler. A persistent collection buys immutable sharing that nobody
  here can observe, and charges for it once per dirty entry: an app-db write
  that marks D derived values dirty performs D `conj`s onto a growing HAMT
  and D `disj`s as the drain consumes them, each copying its path. Measured
  on a 300-subscription frame that was **1,706 bytes per dirty subscription
  — 60% of the whole per-subscription cost of a narrow write**, and it grew
  with D, because HAMT path length does.

  `js/Set` membership is SameValueZero, which for the function objects this
  set holds is reference identity — the same relation `contains?` on a
  persistent set gave them (CLJS `=` on two distinct functions is
  `identical?`). Enqueue order, the double-enqueue guard, the drain order,
  and the re-entrant append the running drain observes are all unchanged;
  this is a representation change and nothing else."
  []
  {:depth     (volatile! 0)   ;; open-epoch nesting depth
   :flushing? (volatile! false)
   :queue     (array)         ;; ordered queue of pending flush thunks (scratch)
   :queued    (js/Set.)       ;; identity set guarding double-enqueue (scratch)
   :escaped   (volatile! capture-none) ;; earliest-escape cell (rf2-2u4rw)
   :source-coordinators (js/Map.)}) ;; source -> fan-out coordinator (rf2-7ryt0)

(defn- drain-scheduler!
  "Drain the scheduler's pending flush thunks in enqueue order until the
  queue is empty. Re-entrant-safe: an in-progress drain swallows nested
  drain requests (the running loop already observes newly-enqueued
  thunks). Each thunk recomputes its derived value and, on a movement,
  notifies its watchers — which may enqueue downstream thunks that the
  same loop then drains, preserving topological order.

  Per-thunk isolation WITH earliest-escape surfacing (rf2-l3lelt + rf2-qcmzc
  + rf2-2u4rw). On the production sub graph a flush thunk never throws: the
  spine compute-fn is `subs.memo/validate-and-trace`, which brackets the user
  sub body in `try/catch`, emits `:rf.error/sub-exception`, and recovers to
  nil. A non-catching compute-fn (a RAW derived value built by tooling/tests),
  or a throwing derived SUBSCRIBER (whose escape the fan-out `notify`
  re-raises after delivering every sibling), CAN throw though — and this
  seam owes THREE disciplines at once:

    1. Don't STRAND the tail (rf2-l3lelt). A throw propagating out of a bare
       drain loop stranded every downstream thunk still queued behind it:
       the `finally` retained them in `@queue`, but their `dirty?` flag
       stayed `true`, so `mark-dirty!` (guarded by `(when-not @dirty? …)`)
       could never re-enqueue them on a later source change — a latent
       stuck-dirty strand. So each thunk runs inside its own `try/catch` and
       the loop drains every remaining thunk to completion regardless.

    2. Don't SWALLOW the failure (rf2-qcmzc). The earlier per-thunk guard
       DISCARDED every escape (`(catch :default _ nil)`), so a programmer
       error in a raw derived value / subscriber vanished silently and the
       fan-out's careful re-raise reached a dead end. Instead the loop
       CAPTURES the escape by PRESENCE into the scheduler's `:escaped` cell
       (`capture-none`, not truthiness — `nil`/`false` are legal CLJS throws
       whose identity must survive), keeps draining the tail, restores queue/
       flushing state in the `finally`, and surfaces the escape through the
       caller/error channel — identity preserved, AFTER every sibling.

    3. Preserve the EARLIEST across a SEEDED entry (rf2-2u4rw + rf2-7ryt0).
       The drain re-raises ONLY a STALE escape — one already present in
       `:escaped` at its entry — and leaves a FRESH escape (captured in THIS
       drain) in `:escaped` for the terminal `surface-escaped!` to surface.
       The sole seeder is `with-epoch`'s body-throw path: an epoch body that
       queues work and THEN throws E1 seeds E1 as stale before the queued
       drain runs, so the drain re-raises E1 (the earliest) rather than its
       own E2 (drain semantics: E1 over E2). A clean-entry drain never
       re-raises — it captures and defers to `surface-escaped!`.

       The direct-source fan-out has its OWN real terminal (rf2-7ryt0). A raw
       `reset!` no longer fires N independent per-dependent watches into N
       sequential depth-zero drains (which had no terminal for a sole/last
       dependent and stranded the tail for 3+). Each raw atom source now owns
       ONE coordinating watch (`ensure-source-coordinator!`) that brackets the
       WHOLE dependent fan-out in a single `with-epoch`: every dependent marks
       inside the epoch, the outermost close drains them once, and
       `surface-escaped!` surfaces the earliest failure after all owned work
       is attempted — a REAL terminal, exactly like `replace-container!`.

  `:escaped` is a scheduler-lifetime volatile (allocated once by
  `make-scheduler`), so an ordinary empty/one-subscriber drain allocates
  NOTHING here — the cursor is a loop binding, not a per-drain volatile
  (rf2-2u4rw common-path preservation). The throwing thunk's own `dirty?`
  was already cleared by `flush!` before `recompute`, so it leaves no stuck
  guard either."
  [{:keys [flushing? queue queued escaped] :as _scheduler}]
  (when-not @flushing?
    (vreset! flushing? true)
    ;; A STALE escape present at entry was SEEDED by `with-epoch`'s body-throw
    ;; (E1 queued work then threw); this drain attempts the queued tail, then
    ;; re-raises E1 as the earliest primary (over any drain E2).
    (let [stale-at-entry? (not (identical? capture-none @escaped))]
      (try
        ;; Walk the live `@queue` by index rather than re-slicing the head: a
        ;; thunk may `schedule-flush!` downstream thunks, which `conj` onto the
        ;; same vector, so re-reading `(count @queue)` each step keeps the
        ;; running loop observing newly-enqueued thunks in enqueue order. The
        ;; entry leaves `queued` and the cursor advances BEFORE the thunk runs,
        ;; so a thunk that throws is already considered consumed (matching the
        ;; old head-pop-before-call ordering). The per-thunk `try/catch`
        ;; isolates a throwing thunk (drains the tail) AND retains its escape.
        (loop [cursor 0]
          (when (< cursor (alength queue))
            (let [thunk (aget queue cursor)]
              (.delete queued thunk)
              (try (thunk)
                   (catch :default e
                     (when (identical? capture-none @escaped)
                       (vreset! escaped e)))))
            (recur (inc cursor))))
        (finally
          ;; The loop consumes `queue` to empty on every non-re-entrant exit
          ;; (per-thunk capture means no throw escapes the loop), so this
          ;; releases the held thunks for the next epoch — truncating a JS
          ;; array drops the references beyond the new length. Re-entrant
          ;; drains short-circuit on `@flushing?` above and never reach here.
          (set! (.-length queue) 0)
          (vreset! flushing? false)))
      ;; Queue/flushing state is restored. Surface ONLY a stale escape — one
      ;; SEEDED at entry (a `with-epoch` body-throw E1). A fresh escape captured
      ;; in THIS drain is left in `:escaped` for the terminal `surface-escaped!`
      ;; (the `with-epoch` / source-coordinator outermost close) to surface once
      ;; after all owned work is attempted. Re-raised OUTSIDE the try/finally so
      ;; the `finally`'s resets can never mask the primary failure (rf2-qcmzc +
      ;; rf2-2u4rw).
      (when stale-at-entry?
        (let [e @escaped]
          (vreset! escaped capture-none)
          (throw e))))))

(defn- surface-escaped!
  "If the scheduler holds a retained earliest escape, clear it and re-raise it
  (by presence — `nil`/`false` survive). The terminal surfacing helper for
  every `with-epoch` outermost close (rf2-2u4rw) — including the per-source
  fan-out coordinator's (rf2-7ryt0): after the drain has attempted every owned
  thunk and restored state, any escape a FRESH-capture drain deferred surfaces
  here, once."
  [{:keys [escaped] :as _scheduler}]
  (when-not (identical? capture-none @escaped)
    (let [e @escaped]
      (vreset! escaped capture-none)
      (throw e))))

(defn- schedule-flush!
  "Enqueue `thunk` on the scheduler (dedup by identity within the current
  epoch). When no epoch is open, drain immediately so any depth-zero mark
  still flushes synchronously. In practice a raw source mark reaches here
  inside its coordinator's `with-epoch` (depth > 0, no inline drain), and a
  cascade mark reaches here re-entrantly during a running drain (`@flushing?`
  short-circuits the inline drain, and the running loop picks the thunk up);
  the depth-zero inline drain is the fallback flush for any other seam."
  [{:keys [depth queue queued] :as scheduler} thunk]
  (when-not (.has queued thunk)
    (.add queued thunk)
    (.push queue thunk))
  (when (zero? @depth)
    (drain-scheduler! scheduler)))

(defn- with-epoch
  "Run `body-thunk` inside an open epoch on `scheduler`; the outermost close
  drains the pending flush queue. Nested epochs (a re-entrant
  `replace-container!` during a flush) only drain at the outermost boundary so
  coalescing spans the whole synchronous cascade.

  Body/finally failure ordering (rf2-2u4rw). `body-thunk` (the bracketed
  `reset!`) can queue work and THEN throw E1 — e.g. a `replace-container!`
  whose reset fires a watch that itself throws after other watches already
  enqueued flushes. The outermost close must still drain that queued tail, but
  the drain can throw its own E2. A bare `try/finally` would let JavaScript's
  finally-replaces-try semantics surface E2 and LOSE E1 — the caller-visible
  primary would no longer be the earliest escape. So the body's escape is
  caught, recorded as the earliest (`:escaped`) BEFORE the drain runs so a
  drain E2 cannot displace it, the drain attempts every owned thunk and
  restores scheduler state, and the EARLIEST (E1 over E2) surfaces once via
  `surface-escaped!`. `depth` is decremented on both the success and throw
  paths so nested-epoch accounting stays correct. `capture-none` presence
  (not truthiness) keeps a `false`/`nil` body throw representable."
  [{:keys [depth escaped] :as scheduler} body-thunk]
  (vswap! depth inc)
  (let [body-escaped (try
                       (body-thunk)
                       capture-none
                       (catch :default e e))]
    (vswap! depth dec)
    (if (zero? @depth)
      (do
        ;; Body threw E1 → it escaped earliest, so it is the PRIMARY. Seed
        ;; `:escaped` before draining so the drain's own escape can't displace
        ;; it (rf2-2u4rw with-epoch entry). A clean body leaves `:escaped`
        ;; untouched; the drain then owns any flush escape.
        (when-not (identical? capture-none body-escaped)
          (when (identical? capture-none @escaped)
            (vreset! escaped body-escaped)))
        ;; Always attempt the queued tail. `drain-scheduler!` re-raises a
        ;; stale escape (the seeded E1) itself; `surface-escaped!` covers the
        ;; clean-body / drain-only escape the drain deferred (this outermost
        ;; close is terminal — no later sibling drain follows).
        (drain-scheduler! scheduler)
        (surface-escaped! scheduler))
      ;; Nested epoch close: the outer epoch owns the drain + surfacing;
      ;; propagate a body escape upward so it reaches that boundary.
      (when-not (identical? capture-none body-escaped)
        (throw body-escaped)))))

;; ---- direct-source fan-out coordinator (rf2-7ryt0) ------------------------
;;
;; A derived value's inputs are either the app-db root, a raw atom source
;; (`clojure.core/atom`), or an upstream derived value (`reify`). The two
;; fan-out shapes need different terminals:
;;
;;   * An upstream DERIVED value fans out through its own `notify` (the
;;     movement-gated, capture-then-surface loop in `make-derived-value-fn`),
;;     which always runs inside a drain/epoch — so its failures already
;;     surface at that enclosing terminal.
;;
;;   * A raw ATOM source fans out through the atom's OWN `-notify-watches`
;;     loop, which is UNCONTAINED: it fires each watch one at a time with no
;;     post-loop hook, and a throwing watch aborts the remaining watches.
;;     Wiring one mark-dirty watch per dependent gave that fan-out no
;;     terminal — a `reset!` on a source with a sole/last-firing throwing
;;     dependent parked the failure on `:escaped` until an unrelated later
;;     drain, and with 3+ dependents an early throw surfaced mid-loop and
;;     stranded the tail (rf2-7ryt0; the audit of #6210's exactly-two
;;     deferral). No purely local drain rule can fix this: a sole dependent's
;;     drain and an earlier-of-two dependent's drain have identical local
;;     state, so any rule that surfaces the first would strand the second.
;;
;; The fix: give each raw atom source ONE coordinating watch that brackets
;; its whole dependent fan-out in a single `with-epoch`. Every dependent
;; marks inside the epoch (no per-dependent inline drain); the outermost
;; close drains all marked flushes once and `surface-escaped!` surfaces the
;; earliest failure AFTER all owned work is attempted — the same real
;; terminal `replace-container!` gives, now for bare `reset!` too. The
;; coordinator lives on the scheduler's `:source-coordinators` map (keyed by
;; source identity), reuses `with-epoch` + `:escaped` (no new failure store,
;; no drain-model change), and is torn down when its last dependent disposes.

(defn- source-atom?
  "True when `s` is a plain reactive-atom source (`clojure.core/Atom`) — the
  case whose native `-notify-watches` loop is uncontained and needs a
  coordinating fan-out terminal (rf2-7ryt0). False for a `reify` derived
  value (its `notify` already surfaces at the enclosing drain) and for any
  custom non-atom base container (kept on the direct per-dependent watch)."
  [s]
  (instance? Atom s))

(defn- invoke-dep-mark
  "Reducer that invokes the mark-fn of a `[dep-key mark-fn]` dependent entry,
  discarding accumulator + key. Hoisted (allocated once) so the coordinator's
  fan-out over its dependent vector pays no per-mutation closure (rf2-7ryt0
  hot-path preservation — `reduce` walks the vector directly)."
  [_ pair]
  ((nth pair 1))
  nil)

(defn- ensure-source-coordinator!
  "Get-or-create the per-source fan-out coordinator for raw atom source `s`
  on `scheduler` (rf2-7ryt0). The coordinator holds a vector of
  `[dep-key mark-fn]` dependent entries in an atom and installs exactly ONE
  real watch on `s`; that watch brackets the whole fan-out in `with-epoch`,
  so a direct `reset!` marks every dependent inside one epoch and surfaces
  the earliest failure at the outermost close. The fan-out body-thunk and
  watch fn are allocated ONCE here, so a mutation of `s` allocates nothing on
  the fan-out (the `reduce` over the dependent vector walks it directly)."
  [{:keys [source-coordinators] :as scheduler} gensym-prefix s]
  (or (.get source-coordinators s)
      (let [deps      (atom [])   ;; ordered vector of [dep-key mark-fn]
            watch-key (gensym gensym-prefix)
            fanout    (fn source-fanout [] (reduce invoke-dep-mark nil @deps))
            coord     {:deps deps :watch-key watch-key}]
        (add-watch s watch-key (fn [_ _ _ _] (with-epoch scheduler fanout)))
        (.set source-coordinators s coord)
        coord)))

(defn- release-source-wire!
  "Release ONE `[source watch-key]` input wire held by a derived value — the
  exact inverse of one iteration of `make-derived-value-fn`'s install loop.

  A raw atom source's key is a dependent entry in that source's fan-out
  coordinator (rf2-7ryt0): drop the entry, and when the coordinator has no
  dependents left tear down its single real watch + registry slot. A reify
  derived source's key is a direct watch: remove it.

  Tolerates a key that was never actually installed — filtering an absent
  dependent entry and `remove-watch` on an unheld key are both no-ops, and a
  source whose `ensure-source-coordinator!` threw before `.set` has no
  coordinator to find. That tolerance is what lets the failure-atomic
  construction unwind (rf2-vxgfnd.292) replay this over the partially-acquired
  key vector without first working out how far the loop got.

  ONE implementation, two callers: the construction unwind and the steady-state
  `-dispose`."
  [scheduler s k]
  (let [coords (:source-coordinators scheduler)]
    (if (source-atom? s)
      (when-let [coord (.get coords s)]
        (swap! (:deps coord)
               (fn [ds] (filterv (fn [pair] (not= (nth pair 0) k)) ds)))
        (when (empty? @(:deps coord))
          (remove-watch s (:watch-key coord))
          (.delete coords s)))
      (remove-watch s k))))

;; ---- container ------------------------------------------------------------
;;
;; Per Spec 006 §revertibility-constraints the container holds the
;; frame's app-db value and *only* the frame's app-db value. React-only
;; substrates (UIx) don't ship a reactive atom primitive (their
;; hook substrate is React state) so we lean on a plain
;; `clojure.core/atom` and broadcast changes via `add-watch` — observably
;; equivalent to the Reagent adapter's r/atom for the substrate contract
;; surface (read, replace, subscribe). Reactive view-side hookup happens
;; through `useSyncExternalStore` in the spine's `use-subscribe` factory,
;; not through Reagent reactions.

;; rf2-w1g0d2: `make-state-container` is the only point of the container
;; quartet that genuinely differs between the React-hook spine and the
;; ratom family — the React-only substrates seed a plain
;; `clojure.core/atom`, the ratom family seeds the substrate's reactive
;; atom (`r/atom` / `reagent2.*`). The ctor is the ONLY variable, so a
;; single ctor-parameterised factory serves BOTH spines: the React spine
;; binds the plain-`atom` arity below, the ratom spine passes its injected
;; `r-atom`. `read-container`, `replace-container!`, and `make-derived-value`
;; do NOT collapse the same way — see `make-ratom-spine` for why
;; `replace-container!` (epoch-bracketed react vs bare `reset!` ratom) and
;; `make-derived-value` (explicit reify vs native reaction) legitimately
;; diverge; `read-container` IS identical and the ratom spine reuses this
;; Var directly.
(defn make-state-container-fn
  "Return a `make-state-container` fn that seeds its container with the
  given `ctor` (a 1-arg `initial-value -> container` constructor). The
  React-hook spine passes `clojure.core/atom`; the ratom family passes its
  injected reactive-atom ctor (`r-atom`)."
  [ctor]
  (fn make-state-container [initial-value]
    (ctor initial-value)))

(def make-state-container
  "React-hook spine `make-state-container`: seeds a plain `clojure.core/atom`
  (React-only substrates ship no reactive-atom primitive). The ratom family
  builds its own via `make-state-container-fn` with the substrate's `r-atom`."
  (make-state-container-fn atom))

(defn read-container [container]
  @container)

(defn make-replace-container-fn
  "Return a `replace-container!` fn that brackets its `reset!` in an epoch
  on `scheduler`. The `reset!` fires source watches synchronously; those
  watches only MARK their derived values dirty (see
  `make-derived-value-fn`). The epoch close drains the coalesced flush
  queue so each affected derived value recomputes glitch-free against the
  settled app-db and notifies its subscribers exactly once (Spec 006
  §Invalidation algorithm)."
  [scheduler]
  (fn replace-container! [container new-value]
    (with-epoch scheduler (fn epoch-body [] (reset! container new-value)))
    nil))

(defn make-subscribe-container
  "Return a `subscribe-container` fn that gensyms watch keys with the
  given `gensym-prefix`. Parameterised on prefix only so warning logs /
  test inspectors can attribute the watch back to its host substrate."
  [gensym-prefix]
  (fn subscribe-container [container on-change]
    (let [k (gensym gensym-prefix)]
      (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
      (fn unsubscribe [] (remove-watch container k)))))

;; ---- derived value --------------------------------------------------------
;;
;; React-only substrates have no reaction primitive. The substrate
;; contract requires that (read-container) on a derived container deref
;; a fresh value computed from the sources; subscribe-container on a
;; derived container fires when any source changes. We satisfy both via
;; a thin IDeref wrapper whose change-broadcasting fan-out is one watch
;; per source.
;;
;; Equality-on-= is preserved by the core's sub-cache invalidation
;; algorithm (Spec 006 §Invalidation algorithm Phase 2): the sub-cache
;; only re-emits when the recomputed value differs from the cached one
;; by =. The derived container itself does not memoise; per the same
;; spec section that's the cache's job, not the substrate's.
;;
;; Laziness (rf2-ee38b.1 P2). The derived value MUST NOT run `compute-fn`
;; at construction time. `compute-fn` here is the core's memo wrapper —
;; it runs the user sub body and (in dev) emits `:rf.sub/run` via
;; `validate-and-trace`, and for a layer-2+ sub eagerly derefs the whole
;; `:<-` input chain. Reagent (`ratom/make-reaction`, lazy until first
;; deref) and the plain-atom adapter (recompute-on-deref) both defer the
;; first body invocation to first read; the spine matches by seeding
;; `prev-state` with the `unset` sentinel rather than with `(recompute)`.
;; The first flush (or deref) is then the first real recompute — a
;; subscribe-time `(recompute)` would emit `:rf.sub/run` and side-effect
;; before any render reads the reaction, an observable cross-adapter
;; divergence (extra body invocations, different trace timing) that
;; contradicts Spec 006 §No-op via value equality's "body runs on demand"
;; intent. The sentinel is never `rf=` any real value, so the first post-
;; construction change always notifies — the same first-change-notifies
;; semantics Reagent gives.

(def ^:private unset
  "Sentinel for a derived value whose baseline has not yet been computed.
  Distinct object identity so it can never `rf=` a real derived value (incl.
  `nil`/`false`), making the first flush after construction always notify."
  (js-obj))

(defn- rf=
  "The compiled-view substrate's frozen per-slot MOVEMENT law, spelled
  spine-local for the React-hook derived-value fan-out gate: `Object.is(a,b)
  OR (= a b)` — the CLJS branch of `re-frame.ui.eq/rf=`. Kept core-local (a
  transcription, NOT a `:require`) so core does not depend on the UI artefact,
  exactly as the observation port keeps its own `node-value=` spelling (Spec
  006). The load-bearing consequence for rf2-vxgfnd.203: `##NaN` is STABLE
  (`Object.is(##NaN, ##NaN)` is true), so a derived value that stays NaN across
  a source tick reports NO movement and does not fan out — unlike raw `=`/`not=`,
  under which `(= ##NaN ##NaN)` is false so every NaN reads as fresh and fans
  out on a no-move. (`-0.0`/`+0.0` still compare EQUAL via the `=` branch, as
  the ruled law and the prior `not=` gate both give — no behaviour change
  there; NaN→NaN is the sole pair this gate now treats differently.) One frozen
  relation across the direct adapter, the observation port, and the ViewCell
  layer, so the three fan-out boundaries agree on cardinality."
  [a b]
  (or ^boolean (js/Object.is a b)
      (= a b)))

(defn build-recompute-fn
  "Arity-specialised recompute-closure factory for a derived value.

  Returns a 0-arg thunk that derefs `source-containers` and calls
  `compute-fn` with the deref'd values. The hottest path in the
  artefact is `derived-recompute × dispatch × subscriber`; subs
  typically chain off 1 input (layer-1 always; layer-n usually 1–2).
  Specialising 0/1/2 sidesteps the `apply` + lazy-`map` cost on the
  dominant arities; ≥3 falls back to `mapv` (eager, vector-backed)
  + `apply`.

  `count` is captured once at construction (per Spec 006 §CLJS reference
  + `re-frame.subs`, `source-containers` is a vector) so the recompute
  closure pays no per-tick `count`.

  Single source of truth: the Freehand, Reagent, reagent-slim, and UIx
  adapters all build their recompute closure through this fn — one
  implementation, four adapters, zero drift (the donor re-frame.ui substrate
  rides the same path in-tree, making five closures in this repo). The
  arity-spec lifted
  into the spine matches the `make-dispose-adapter!` shape
  (rf2-jcjul); sourced from the rf2-fzrav perf-sweep findings."
  [source-containers compute-fn]
  (let [n (count source-containers)]
    (case n
      0 (fn recompute-0 [] (compute-fn))
      1 (let [s0 (nth source-containers 0)]
          (fn recompute-1 [] (compute-fn @s0)))
      2 (let [s0 (nth source-containers 0)
              s1 (nth source-containers 1)]
          (fn recompute-2 [] (compute-fn @s0 @s1)))
      (fn recompute-n [] (apply compute-fn (mapv deref source-containers))))))

(defn make-derived-value-fn
  "Return a `make-derived-value` fn that tags per-source watch keys with
  the given `gensym-prefix` and coalesces source-change notifications
  through `scheduler` (see the epoch-scheduler section above). The fn
  signature matches the substrate contract:
  `(sources compute-fn) -> derived-container`.

  Single-recompute / single-notification (rf2-i21f5): a source-change
  watch does NOT recompute or notify inline. It marks this derived value
  dirty (enqueues a single recompute-and-notify thunk on the scheduler).
  The epoch open by `replace-container!` defers the drain until the whole
  synchronous app-db cascade has settled, so a multi-input derived value
  recomputes EXACTLY ONCE against the coherent input set and notifies its
  subscribers at most once — never N times (one recompute + one Phase-3
  notification per dirty entry per app-db change)."
  [gensym-prefix scheduler]
  (fn make-derived-value [source-containers compute-fn]
    (let [recompute      (build-recompute-fn source-containers compute-fn)
          watchers       (atom {})           ;; user-key → wrapper-fn
          on-dispose-fns (atom [])
          ;; Per-source wire keys we own so dispose can unwire them. A VECTOR
          ;; of `[source key]` pairs, NOT a `source→key` map (rf2-he7se finding
          ;; 2): `source-containers` is a vector with no uniqueness precondition
          ;; (spec/006 §154-170), so the SAME source object may appear more than
          ;; once. Each occurrence takes its own gensym key; a `source→key` map
          ;; would overwrite earlier keys, so dispose would release only the
          ;; LAST wire per source and leak the rest. Tracking every
          ;; `[source key]` pair lets dispose release ALL held inputs (spec/006
          ;; §600-613). For a raw atom source `key` is the dependent-key
          ;; registered with the source's fan-out coordinator (rf2-7ryt0); for
          ;; a reify derived source it is the direct `add-watch` key.
          own-keys       (atom [])           ;; vector of [source key]
          ;; Disposed guard (rf2-1bzlai). `-dispose` MUST be idempotent and
          ;; re-entrant safe: a second `-dispose`, or a re-entrant
          ;; `interop/dispose!` fired from inside an on-dispose callback
          ;; (e.g. a cleanup path that defensively disposes the same derived
          ;; value), must be a no-op after the first pass. The flag flips
          ;; true on the first call BEFORE callbacks run, so a re-entrant
          ;; dispose short-circuits rather than re-firing the callback set or
          ;; double-releasing layer-2 input watches. Single-threaded JS event
          ;; loop, never escapes this closure — `volatile!` is the right
          ;; primitive (matches `prev-state` / `dirty?` above).
          disposed?      (volatile! false)
          ;; The MOVEMENT WITNESS this container publishes (rf2-gncxk.1) —
          ;; the value its most recent COMPLETED movement departed FROM, or
          ;; `movement/no-witness` when it cannot presently answer. Read by
          ;; `re-frame.subs.memo`'s fixed-arity-1 wrappers to skip a
          ;; structural `=` walk whose answer the witness already
          ;; determines; see `re-frame.movement` for the protocol, its two
          ;; implementor obligations, and the proof.
          ;;
          ;; Written in exactly two places, both of which already exist:
          ;;
          ;;   W2 SOUNDNESS — armed inside `notify`'s `rf=` gate, which is
          ;;     precisely the instant movement is ESTABLISHED: `prev-state`
          ;;     already holds `nu` (flush! writes it before notifying), and
          ;;     the gate has just proven `(not (rf= prev nu))`.
          ;;   W1 FRESHNESS — cleared on `mark-dirty!`'s 0->1 transition.
          ;;     This container is PULL-based (`deref-derived` recomputes on
          ;;     every `-deref`), so once an input change is observed its
          ;;     live value may run ahead of its last notify and it must
          ;;     stop answering. `flush!` clears `dirty?` BEFORE it
          ;;     recomputes and notifies, so the witness re-arms in the
          ;;     right order with no extra bookkeeping.
          ;;
          ;; W1 is stated in terms of an OBSERVED input change, which is
          ;; what a watch-driven container can honour: a source's own write
          ;; lands a tick before this container's `mark-dirty!` runs, and
          ;; nothing on the single-threaded event loop is interposed there
          ;; (a CLJS `reset!` sets the state and immediately fans its
          ;; watches out; the raw-atom coordinator marks every dependent
          ;; before the enclosing epoch closes).
          ;;
          ;; MEMORY, the one real cost: this retains ONE predecessor derived
          ;; value per derived container while the container is clean. For
          ;; the app-db projection that is one extra app-db generation held
          ;; between writes — and structural sharing means the true delta is
          ;; only the nodes the last write replaced. Not a new class of
          ;; retention: every layer-1 memo cell already holds a full app-db
          ;; in its `last-db`.
          last-moved-from (volatile! movement/no-witness)
          ;; Movement-gated, failure-contained fan-out (rf2-vxgfnd.203).
          ;; Two disciplines at this one boundary:
          ;;
          ;;   1. Gate on the frozen `rf=` MOVEMENT law, not raw `not=`. Raw
          ;;      `(not= ##NaN ##NaN)` is true, so a derived value that stays
          ;;      NaN across a source tick would fan out on a NO-move — a false
          ;;      direct invalidation the observation port and ViewCell layer
          ;;      (both `rf=`-gated) do not raise. `rf=` treats NaN→NaN as
          ;;      stable, so all three fan-out boundaries agree on cardinality.
          ;;      The `unset` baseline is never `rf=` a real value, so the first
          ;;      post-construction change still notifies (unchanged).
          ;;
          ;;   2. CONTAIN a throwing subscriber, then SURFACE the primary
          ;;      failure. Bare `run!` aborted at the first throw, skipping
          ;;      every later sibling, so ONE subscriber could permanently
          ;;      suppress another's invalidation, order-dependently. Instead:
          ;;      snapshot the callbacks (an add/remove-watch during fan-out is
          ;;      an immutable-map swap, so this seq is stable and the change
          ;;      lands on the NEXT wave), attempt EVERY subscriber
          ;;      independently, capture the FIRST thrown value by PRESENCE
          ;;      (`capture-none`, not truthiness — `nil`/`false` are legal
          ;;      CLJS throws), then re-raise it AFTER delivery. That re-raise
          ;;      escapes `flush!` into `drain-scheduler!`, which (rf2-qcmzc)
          ;;      retains it by presence, drains the rest, restores scheduler
          ;;      state, and re-raises it to the caller (`replace-container!` /
          ;;      a direct source mutation) — the established caller/error
          ;;      channel. So the primary failure SURFACES with identity
          ;;      preserved rather than disappearing inside the fan-out. (The
          ;;      earlier code re-raised into a per-thunk drain guard that
          ;;      SWALLOWED it — the swallow rf2-qcmzc removes.)
          ;;
          ;;      Common-path preservation (rf2-vxgfnd.203). The dominant
          ;;      cardinality is one subscriber (a cache entry's own watch);
          ;;      that path — and the zero-subscriber / no-move paths — allocate
          ;;      NOTHING extra: `count` on the map is O(1), and the single
          ;;      subscriber (no sibling to protect) is invoked directly, its
          ;;      throw propagating straight through `flush!` into the drain,
          ;;      which surfaces it identically. Only two-plus subscribers pay
          ;;      the capture volatile + `run!` closure.
          notify         (fn [prev nu]
                           (when-not (rf= prev nu)
                             ;; W2 — record the departure at the exact instant
                             ;; movement is established, BEFORE fan-out, so a
                             ;; subscriber that reads this container from
                             ;; inside the fan-out already sees the armed
                             ;; witness (rf2-gncxk.1). One volatile write; no
                             ;; allocation; on the no-move path (the gate
                             ;; above) not reached at all.
                             (vreset! last-moved-from prev)
                             (let [ws @watchers]
                               (case (count ws)
                                 0 nil
                                 ;; Single subscriber: no sibling to protect →
                                 ;; no capture cell, invoke directly. A throw
                                 ;; propagates through `flush!` into the drain,
                                 ;; which surfaces it (rf2-qcmzc). `sole-val`
                                 ;; extracts the lone watcher WITHOUT the map-seq
                                 ;; + `MapEntry` `(val (first ws))` allocates —
                                 ;; genuinely allocation-free on the common path
                                 ;; (rf2-2u4rw).
                                 1 ((sole-val ws) prev nu)
                                 ;; Two+ subscribers: attempt each independently,
                                 ;; capture the FIRST escape by presence, re-raise
                                 ;; after delivery (surfaced via the drain).
                                 ;;
                                 ;; `reduce-kv` walks the map's backing nodes
                                 ;; directly, for the SAME reason `sole-val`
                                 ;; above does (rf2-2u4rw): `(vals ws)` is
                                 ;; `(map val (seq ws))`, so it allocates a seq
                                 ;; node AND a lazy-seq cell PER SUBSCRIBER just
                                 ;; to hand each one to `run!`. On the app-db
                                 ;; projection — whose subscriber set is EVERY
                                 ;; layer-1 subscription in the frame — that was
                                 ;; measured at 307 bytes per subscription per
                                 ;; write, 11% of the whole per-subscription cost
                                 ;; of a narrow write (rf2-jr76s). `reduce-kv`
                                 ;; visits the same entries in the same order
                                 ;; with the same per-subscriber isolation and
                                 ;; the same earliest-capture; `ws` is already
                                 ;; the immutable snapshot taken above, so the
                                 ;; add/remove-watch-during-fan-out guarantee is
                                 ;; untouched.
                                 (let [captured (volatile! capture-none)]
                                   (reduce-kv
                                     (fn [_ _ w]
                                       (try
                                         (w prev nu)
                                         (catch :default e
                                           (when (identical? capture-none @captured)
                                             (vreset! captured e))))
                                       nil)
                                     nil ws)
                                   (when-not (identical? capture-none @captured)
                                     (throw @captured)))))))
          ;; Baseline derived value. LAZY (rf2-ee38b.1 P2): seeded with the
          ;; `unset` sentinel rather than `(recompute)`, so `compute-fn`
          ;; (the memo wrapper running the user sub body) is NOT invoked at
          ;; construction/subscribe time — matching Reagent's lazy
          ;; `make-reaction` and the plain-atom recompute-on-deref adapters.
          ;; The body runs on demand: the FIRST `-deref` (which the sub-
          ;; cache performs to read the subscription's value) establishes
          ;; the baseline, and a `replace-container!` change after that
          ;; notifies `[prev-derived new-derived]` exactly as before. If a
          ;; change flushes before any deref ever happened (no reader),
          ;; `prev-state` is still `unset`; `unset` is never `rf=` any real
          ;; value (incl. nil/false) so the first flush still notifies — the same
          ;; first-change-notifies semantics Reagent gives. (Seeding from
          ;; the *derived* value, never the raw source, still holds: the
          ;; flush thunk compares the recomputed derived value against the
          ;; prior derived value / sentinel, so a projection like
          ;; `(odd? x)` / counts / `:k` lookups never spuriously notifies on
          ;; a same-`=` re-derive.)
          ;;
          ;; `prev-state` / `dirty?` are written and read only on the
          ;; single-threaded JS event loop and never escape this closure —
          ;; `volatile!` is the right primitive, no CAS cost. `dirty?`
          ;; dedups re-marks within an epoch: a multi-input derived value
          ;; whose N sources all fire enqueues exactly one flush thunk.
          prev-state     (volatile! unset)
          dirty?         (volatile! false)
          ;; First-deref baseline seed (rf2-ee38b.1 P2). Pure pull-based
          ;; recompute, but on the FIRST deref it also records the value as
          ;; `prev-state` so the next change's notification carries the real
          ;; prior derived value (not the `unset` sentinel). Subsequent
          ;; derefs do not touch `prev-state` — the flush path owns it.
          deref-derived  (fn deref-derived []
                           (let [v (recompute)]
                             (when (identical? unset @prev-state)
                               (vreset! prev-state v))
                             v))
          flush!         (fn flush! []
                           ;; Disposed-tombstone guard (rf2-jgzica). A
                           ;; derived value's `mark-dirty!` enqueues this
                           ;; thunk on the SHARED epoch scheduler; the drain
                           ;; runs at epoch close. If the reaction is disposed
                           ;; BETWEEN mark-dirty and the drain (a cascade where
                           ;; a downstream unsubscribe drives a sibling
                           ;; ref-count to 0 and disposes a reaction whose
                           ;; flush is already queued), the queued thunk still
                           ;; fires. `-dispose` clears `watchers`, so the
                           ;; `notify` fan-out is already a no-op — but
                           ;; `recompute` is the memo wrapper, so without this
                           ;; guard it re-runs the user sub body and (in dev)
                           ;; emits a spurious `:rf.sub/run`: the exact
                           ;; redundant-recompute the epoch scheduler exists to
                           ;; prevent (rf2-i21f5). The scheduler cannot dequeue
                           ;; a single thunk, so the disposed reaction skips it
                           ;; here instead. Also reset `dirty?` so a re-marked-
                           ;; then-disposed entry leaves a clean guard.
                           (vreset! dirty? false)
                           (when-not @disposed?
                             (let [new-derived  (recompute)
                                   prev-derived @prev-state]
                               (vreset! prev-state new-derived)
                               (notify prev-derived new-derived))))
          mark-dirty!    (fn mark-dirty! []
                           (when-not @dirty?
                             (vreset! dirty? true)
                             ;; W1 — an input change has been observed, so
                             ;; this container's live value may now run ahead
                             ;; of its last completed movement. Stop
                             ;; answering until the next `notify` re-arms
                             ;; (rf2-gncxk.1). Inside the 0->1 branch, so a
                             ;; re-mark within the same epoch costs nothing.
                             (vreset! last-moved-from movement/no-witness)
                             (schedule-flush! scheduler flush!)))]
      ;; Wire this derived value to each source so a source change MARKS it
      ;; dirty — the actual recompute + notify is deferred to the scheduler
      ;; drain so it runs once against settled inputs (glitch-free, single
      ;; notification). A raw ATOM source routes through its per-source
      ;; fan-out coordinator (rf2-7ryt0), which brackets the whole dependent
      ;; fan-out of a bare `reset!` in one `with-epoch` and surfaces the
      ;; earliest failure at a real terminal. A reify DERIVED source (or any
      ;; custom non-atom base) keeps a direct per-dependent `add-watch`: its
      ;; `notify` fan-out already runs inside — and surfaces at — the
      ;; enclosing drain/epoch.
      ;;
      ;; INTERNALLY FAILURE-ATOMIC (rf2-vxgfnd.292). The loop installs one wire
      ;; per source, so a throw partway — a source whose `add-watch` rejects, a
      ;; host container that refuses a new dependent — used to leave EVERY
      ;; earlier wire installed while the constructor returned nothing. The
      ;; caller then held no derived value, so there was no `-dispose` to call
      ;; and no verb that could reach those watches: an unreachable object went
      ;; on marking itself dirty for the lifetime of its sources. Spec 006
      ;; §make-derived-value requires the opposite — a `make-derived-value` that
      ;; throws before returning has removed whatever it installed.
      ;;
      ;; So: unwind in REVERSE acquisition order, attempt EVERY release even if
      ;; one throws, and re-raise the PRIMARY construction error. A secondary
      ;; failure raised while unwinding is swallowed deliberately — it is a
      ;; consequence of the primary and unactionable on its own, and letting it
      ;; escape would replace the one error that names the real fault. This is
      ;; ordinary control flow, not a development assertion: it is present and
      ;; enforcing on EVERY build (no `goog.DEBUG` gate, nothing Closure can
      ;; elide under `:advanced`).
      (try
        (doseq [s source-containers]
          (let [k (gensym gensym-prefix)]
            (swap! own-keys conj [s k])
            (if (source-atom? s)
              (let [coord (ensure-source-coordinator! scheduler gensym-prefix s)]
                (swap! (:deps coord) conj [k mark-dirty!]))
              (add-watch s k (fn [_ _ _ _] (mark-dirty!))))))
        (catch :default e
          (doseq [[s k] (rseq @own-keys)]
            (try
              (release-source-wire! scheduler s k)
              (catch :default _ nil)))
          (reset! own-keys [])
          (throw e)))
      (reify
        IDeref
        (-deref [_] (deref-derived))
        ;; Watch surface — `(subscribe-container derived on-change)` rides
        ;; on this through the standard core helper, and the sub-cache's
        ;; per-entry recompute layer keys watches by gensym so the
        ;; remove-watch path below stays clean.
        IWatchable
        (-add-watch [this k f]
          (swap! watchers assoc k (fn [prev nu] (f k this prev nu)))
          this)
        (-remove-watch [_this k]
          (swap! watchers dissoc k)
          nil)
        ;; Re-frame-owned OPTIONAL movement witness (rf2-gncxk.1). This
        ;; container gates its own propagation on `rf=` (see `notify`
        ;; above), which is exactly the precondition `re-frame.movement`'s
        ;; W2 requires, so it can publish. A raw `cljs.core/Atom` source, a
        ;; Reagent `Reaction`, the plain-atom derived value and test-react's
        ;; derived value all publish NOTHING — and that is the correct
        ;; answer for them, not an omission: their propagation is not
        ;; `rf=`-gated (the raw-atom coordinator fans out on every `reset!`)
        ;; or they have no notify step at all. Consumers resolve the
        ;; capability once, by `satisfies?`, and fall back to the
        ;; comparison they would have made anyway.
        movement/IMovementWitness
        (-moved-from [_] @last-moved-from)
        ;; Re-frame-owned IDisposable — `interop/add-on-dispose!` /
        ;; `interop/dispose!` route into this protocol via the
        ;; adapter's `:adapter/add-on-dispose!` / `:adapter/dispose!`
        ;; hooks (per Spec 006 §subscription-cache). The spine
        ;; deliberately uses `re-frame.disposable/IDisposable` (re-
        ;; frame-owned, no Reagent dependency) rather than
        ;; `reagent.ratom/IDisposable` so UIx bundles don't pay
        ;; ~9KB optimised / 2-3KB gzipped of `reagent.ratom` +
        ;; `reagent.impl.batching` for one protocol.
        rf-disposable/IDisposable
        (-dispose [_]
          ;; Idempotent + re-entrant safe (rf2-1bzlai). Flip the guard
          ;; FIRST so a re-entrant `-dispose` from inside a callback (or a
          ;; plain second call) short-circuits before any teardown re-runs.
          (when-not @disposed?
            (vreset! disposed? true)
            ;; Release every held input through the SAME `release-source-wire!`
            ;; the failure-atomic construction unwind uses (rf2-vxgfnd.292) —
            ;; one release implementation, so the two paths can never drift.
            (doseq [[s k] @own-keys]
              (release-source-wire! scheduler s k))
            (reset! own-keys [])
            (reset! watchers {})
            ;; Stop witnessing and RELEASE the retained predecessor value
            ;; (rf2-gncxk.1). A disposed container answers `no-witness`
            ;; forever, which is both correct (it will never complete
            ;; another movement) and the hygienic answer — the one extra
            ;; generation it held is dropped here rather than pinned for
            ;; the lifetime of whatever still references the reify.
            (vreset! last-moved-from movement/no-witness)
            ;; Snapshot-and-clear callbacks before firing: a callback that
            ;; re-enters `interop/dispose!` on this same object hits the
            ;; guard above (no-op) and never sees the callbacks again, so
            ;; the set fires exactly once in registration order.
            (let [fns @on-dispose-fns]
              (reset! on-dispose-fns [])
              (doseq [f fns] (f)))))
        (-add-on-dispose [_ f]
          (swap! on-dispose-fns conj f))))))

;; ---- render ---------------------------------------------------------------
;;
;; React-only substrates call react-dom/client directly (UIx's uix.dom
;; doesn't expose hydrate-root in every version; Helix ships no DOM
;; wrapper at all). createRoot + .render for fresh mounts; hydrateRoot
;; for the SSR-hydrate path. Both shapes return an unmount thunk.
;;
;; Active roots are tracked in a per-spine atom (rf2-9fdkb). Each mount
;; adds the React root to the active set; the returned unmount thunk
;; removes itself from the set and calls `.unmount` on the root. The
;; spine's `dispose-adapter!` drains the set so torn-down adapters
;; release every root they spun up — Spec 006 §Adapter disposal
;; lifecycle requires browser adapters to unmount active roots.

(defn make-active-roots-cell
  "Return a fresh `(atom #{})` cell holding React roots the spine
  currently keeps mounted. Each adapter owns its own cell so multiple
  React-shaped adapters can coexist in a test bundle without
  clobbering each other's tracking."
  []
  (atom #{}))

(defn track-active-root!
  "Register an already-built React `root` in `active-roots-cell` and return
  a self-removing unmount thunk: it drops `root` from the cell BEFORE
  calling `(unmount-op root)`. Shared by the React-hook `make-render`
  (`unmount-op` = `.unmount`) and the ratom-family render (`unmount-op` =
  the injected `unmount-root`), so the `dispose-adapter!` active-roots drain
  always sees the live set (rf2-w1g0d2). The root constructor / tree-wrap
  (Fragment+sentinel vs none) differs per spine and stays in each render;
  only this tracking tail is shared."
  [active-roots-cell unmount-op root]
  (swap! active-roots-cell conj root)
  (fn unmount []
    (swap! active-roots-cell disj root)
    (unmount-op root)))

;; ---- native-root hydration-mismatch adoption reporter (rf2-qfz65) ----------
;;
;; A native UIx root is a React-ELEMENT root: it has no hashable client
;; render-tree (ruling out the hiccup `:render-tree-fn` / `verify-hydration!`
;; channel — that is for substrates whose view returns a hiccup data tree), and
;; it is not a compiled `re-frame.ui` root (ruling out that tier's
;; `ui/hydrate-root` adoption reporter, rf2-6z1i2). Left alone, `make-render`'s
;; hydrate branch calls `hydrateRoot` with NO root options, so a hydration
;; MISMATCH is SILENT: React's built-in warn-and-replace recovers the DOM but
;; the framework emits no `:rf.ssr/hydration-mismatch` (Spec 011 §Hydration-
;; mismatch detection, the native-root note).
;;
;; The fix mirrors #6507's Path A: a native root VERIFIES by React-native
;; ADOPTION exactly like the compiled tier — `hydrateRoot` diffs the root's
;; render against the server DOM and reports the divergences React RECOVERS FROM
;; (a text-content mismatch, or a missing / extra / wrong-type element — NOT
;; attribute-only mismatches, which take React's dev-only warning path and fire
;; no `onRecoverableError`; see Spec 011 §Hydration-mismatch detection, the
;; attribute-only boundary the compiled and native tiers share) through
;; `onRecoverableError`. We install a framework `onRecoverableError` on the
;; hydrate path ONLY, emit the SAME `:rf.ssr/hydration-mismatch` diagnostic, and
;; compose OVER (never clobber) any host-authored `:on-recoverable-error`.
;;
;; ADOPTION WINDOW (rf2-qfz65 residual). React holds `onRecoverableError` for the
;; root's WHOLE LIFETIME and invokes it for post-hydration recoverable errors too
;; (e.g. a concurrent render React retries and recovers). Emitting the framework
;; hydration-mismatch trace on EVERY call would mislabel that later recovery as a
;; hydration mismatch — false diagnostics beyond the hydration window. So the
;; framework emit is bounded to the ADOPTION WINDOW: a root-local
;; `#js {:adopting true}` flag, read by the reporter and cleared on the hydration
;; commit by the `adoption-window-closer` mounted into the hydrating tree. Once
;; the window closes the reporter still DELEGATES to the host / React-default
;; handler but no longer emits the framework trace. This mirrors the compiled
;; tier's `adoption-ref` (there `re-frame.ui.runtime/PhaseFlipper` clears it on
;; the `:server` commit); a native React-element root has no `:server`->`:client`
;; phase flip, so a dedicated closer component shuts the window on the first
;; (hydration) commit instead.
;;
;; CANONICAL ENTRY. This shared React-hook render path is the ONLY native mount
;; route that installs the reporter, so it is the canonical native UIx
;; hydration entry: hydrate through `(re-frame.substrate.adapter/render tree
;; mount {:hydrate? true})` (the Spec 006 client mount entry / adapter `:render`
;; slot) to get framework mismatch detection. Hydrating via the substrate-native
;; renderer directly (`uix.dom/hydrate-root`, react-dom `hydrateRoot`) bypasses
;; the reporter and falls back to React's default (silent) handling.

(defn- report-recoverable-default!
  "React's default `onRecoverableError` reporting, preserved when the app
  authored NO callback but we installed a wrapper for the native-tier
  hydration-mismatch diagnostic (rf2-qfz65): once a wrapper is set React no
  longer runs its own default, so we replicate it — `globalThis.reportError`
  when present, else `console.error`. Mirrors the compiled tier's
  `re-frame.ui.client/report-recoverable-default!`."
  [error]
  (if (fn? (.-reportError js/globalThis))
    (js/reportError error)
    (when (exists? js/console) (.error js/console error))))

(defn- emit-native-hydration-mismatch!
  ;; The NATIVE-tier `:rf.ssr/hydration-mismatch` diagnostic (rf2-qfz65).
  ;; Surfaces an adoption-window recoverable error as the SAME category the
  ;; hiccup and compiled tiers emit, tier-discriminated by `:where` (the spine
  ;; hydrate site), carrying the recoverable `:error` message and `:recovery`
  ;; `:warned-and-replaced` (React's own recovery). NO `:root-id` (a native
  ;; React-element root carries no `re-frame.ui` root-id) and NO hash (there is
  ;; no native-tier structural hash to report).
  ;;
  ;; Rides the diagnostic channel via `re-frame.trace/emit!`; the
  ;; `interop/debug-enabled?` gate DCEs the whole call under `:advanced` +
  ;; `goog.DEBUG=false` (Spec 009 §Production builds), exactly like the compiled
  ;; tier's `re-frame.ui.runtime/emit-hydration-mismatch!` and `emit-phase-flip!`.
  ;; It is NOT an event and mints no epoch — it fires from a React root-error
  ;; callback, outside any dispatch/handler scope.
  [error]
  (when interop/debug-enabled?
    (trace/emit! :warning :rf.ssr/hydration-mismatch
                 {:error    (some-> error .-message)
                  :where    're-frame.substrate.spine/make-render
                  :recovery :warned-and-replaced})))

(defn native-hydration-reporter
  "Build the composed `onRecoverableError` callback for a HYDRATING native root.

  `adoption-ref` is the root-local `#js {:adopting true}` window flag; `authored`
  is the host's `:on-recoverable-error` (or nil). On every recoverable error the
  callback emits the framework native-tier hydration-mismatch diagnostic ONLY
  while the adoption window is open (`(.-adopting adoption-ref)`), then ALWAYS
  delegates to the host callback (compose, never clobber) or React's default
  report. Bounding the emit to the window is the rf2-qfz65 fix: React invokes
  this callback for post-hydration recoverable errors too, and emitting outside
  the window would mislabel that later recovery as a hydration mismatch. The
  window closes on the hydration commit — see `adoption-window-closer`.

  Public so the mounted-DOM window-bounding proof can drive the REAL callback
  across the window boundary (`re-frame.adapter.react-shared-suite`)."
  [^js adoption-ref authored]
  (fn on-recoverable [error error-info]
    ;; Framework diagnostic ONLY inside the adoption window (debug-gated + DCE'd
    ;; inside `emit-native-hydration-mismatch!`); once the window closes it is a
    ;; later recoverable error, NOT a hydration mismatch — do not emit.
    (when (.-adopting adoption-ref)
      (emit-native-hydration-mismatch! error))
    ;; ALWAYS delegate — compose, never clobber — inside AND outside the window.
    (if (fn? authored)
      (authored error error-info)
      (report-recoverable-default! error))))

(defn- hydrate-root-options
  "Build the react-dom/client root options for a HYDRATING native root, or nil.

  `adoption-ref` is the root-local `#js {:adopting true}` window flag the
  installed reporter reads (see `native-hydration-reporter`) and the
  `adoption-window-closer` clears on the hydration commit. The wrapper is
  installed ONLY when the host authored a callback OR debug is on: with neither
  there is nothing to add over React's default report (the emit DCEs), so this
  returns nil and the caller calls `hydrateRoot` with no options — production
  pays zero cost (the compiled tier's `react-opts-with-hydration-mismatch`
  precedent)."
  [opts adoption-ref]
  (let [authored (:on-recoverable-error opts)]
    (when (or (fn? authored) interop/debug-enabled?)
      #js {:onRecoverableError (native-hydration-reporter adoption-ref authored)})))

(defn adoption-window-closer
  "React function component that CLOSES a native root's hydration adoption window
  on its first (hydration) commit (rf2-qfz65). Reads the root-local
  `#js {:adopting true}` flag off its `rfAdoption` prop and clears it from a
  passive `useEffect` with empty deps — so it runs exactly once, strictly AFTER
  the hydration commit React reports mismatches against (mirroring the compiled
  tier's `PhaseFlipper` clearing `adoption-ref` on the `:server` commit). Renders
  nil (no DOM), so it adds nothing to hydrate and cannot itself mismatch.

  Public so the mounted-DOM window-bounding proof can mount the REAL closer to
  shut the window it drives the reporter across."
  [^js props]
  (React/useEffect
    (fn close-window []
      (when-some [adoption (.-rfAdoption props)]
        (set! (.-adopting adoption) false))
      js/undefined)
    #js [])
  nil)

(when ^boolean js/goog.DEBUG
  (set! (.-displayName adoption-window-closer) "rf.substrate/adoption-window-closer"))

(defn cljs-data-render-tree?
  "True when `render-tree` is CLJS DATA — a hiccup vector, a seq, or a
  map — none of which an ELEMENT-shaped render slot can mount
  (rf2-p6f6u (c)). React treats a CLJS persistent collection as an
  opaque object and sprays one cryptic \"Objects are not valid as a
  React child\" error per child; the guard in `make-render` raises ONE
  structured diagnostic instead. Legal React nodes pass untouched:
  elements (`React/createElement` output), strings, numbers, `nil`, and
  JS arrays are none of these three CLJS shapes."
  [render-tree]
  (or (vector? render-tree)
      (seq? render-tree)
      (map? render-tree)))

(defn make-render
  "Build a `render` fn that registers every mounted React root in
  `active-roots-cell` and returns an unmount thunk that removes the
  root from the cell before calling `.unmount`.

  FAIL-LOUD ELEMENT-SLOT GUARD (rf2-p6f6u (c)). The returned `render`'s
  `render-tree` slot is ELEMENT-shaped (Spec 006 §`render` — this spine
  serves the React-hook substrates, whose trees are built with the
  substrate's element macro, e.g. UIx `$`). Hiccup handed here — a CLJS
  vector / seq / map — is a programmer error that React otherwise
  surfaces as a spray of per-child \"Objects are not valid as a React
  child\" errors. The guard throws ONE structured
  `:rf.error/hiccup-on-element-render-slot` BEFORE any root is created,
  carrying an EP-0015-safe SHAPE summary (never the raw tree). Like the
  construction unwind above (rf2-vxgfnd.292) this is ordinary control
  flow, not a development assertion — present and enforcing on EVERY
  build (no `goog.DEBUG` gate): the misuse breaks production mounts
  identically, and the check is three predicate calls at mount time,
  nowhere near a hot path. This also covers every internal hiccup
  aggregator that funnels through the adapter `:render` slot (e.g.
  Xray's `panels.cljs` mount-<panel>! fns) by construction.

  The user's `render-tree` is wrapped in a Fragment alongside an
  `after-render-sentinel` element (rf2-334d9). The sentinel is a bare
  React function component that fires `React.useLayoutEffect` on every
  commit and drains the per-adapter after-render queue; it renders no
  DOM. See `make-after-render-machinery` for the queue / sentinel
  factory.

  On the HYDRATE path (`:hydrate? true`) the React root is created with a
  framework `onRecoverableError` that surfaces a hydration MISMATCH as the
  `:rf.ssr/hydration-mismatch` diagnostic, composed OVER any host-supplied
  `:on-recoverable-error` opt (rf2-qfz65 — see `hydrate-root-options`). The
  framework emit is bounded to the hydration ADOPTION WINDOW by a root-local flag
  the `adoption-window-closer` (mounted into the hydrating tree) clears on the
  hydration commit, so a LATER recoverable error is not mislabelled a mismatch. A
  plain (non-hydrating) mount installs neither reporter nor closer and pays zero
  cost.

  This render path IS the canonical native UIx hydration entry — the ONLY
  native mount route that installs the framework reporter. Hydrate through
  `(re-frame.substrate.adapter/render tree mount {:hydrate? true})` (the Spec 006
  client mount entry / adapter `:render` slot); hydrating via the substrate-native
  renderer directly (`uix.dom/hydrate-root`, react-dom `hydrateRoot`) bypasses it
  and gets React's default (silent) mismatch handling."
  [active-roots-cell after-render-sentinel-cmp]
  (fn render [render-tree mount-point opts]
    ;; Fail-loud element-slot guard (rf2-p6f6u (c)) — see the docstring.
    ;; EP-0015: the ex-data carries a SHAPE summary of the tree, never
    ;; the raw tree (hiccup can carry app-owned sensitive/large values;
    ;; mirrors `make-render-to-string`'s rf2-uwqale treatment).
    (when (cljs-data-render-tree? render-tree)
      (rf-error/throw-error!
        :rf.error/hiccup-on-element-render-slot
        'rf/render
        (str "this substrate's render slot takes a React ELEMENT, but "
             "received CLJS data (a hiccup vector / seq / map); build the "
             "tree with this substrate's element macro (e.g. uix.core/$) — "
             "hiccup mounts only on the ratom-family (Reagent) substrates")
        {:extra {:render-tree/summary (rf-error/diag-value-summary render-tree)}}))
    ;; Spec 006 §`render` types `:hydrate?` as a boolean; non-bool
    ;; truthy values are undefined-behaviour (no defensive coercion).
    (let [hydrate?     (:hydrate? opts)
          ;; rf2-qfz65 — on the hydrate path mint the root-local adoption-window
          ;; flag and build the composed reporter opts (nil when no host callback
          ;; AND debug off — production zero-cost); non-hydrating mounts get none.
          adoption-ref (when hydrate? #js {:adopting true})
          ropts        (when hydrate? (hydrate-root-options opts adoption-ref))
          wrapped-tree (React/createElement
                         (.-Fragment React)
                         nil
                         (React/createElement after-render-sentinel-cmp nil)
                         ;; The window-closer rides ONLY when a reporter is
                         ;; installed: it clears `adoption-ref` on the hydration
                         ;; commit, closing the window so a later recoverable error
                         ;; is not mislabelled a mismatch. Renders nil (no DOM), so
                         ;; it adds nothing to hydrate and cannot itself mismatch.
                         (when ropts
                           (React/createElement adoption-window-closer
                                                #js {:rfAdoption adoption-ref}))
                         render-tree)
          root         (if hydrate?
                         ;; rf2-qfz65 — a hydrating native root adopts the server
                         ;; DOM; install the composed onRecoverableError reporter
                         ;; when warranted (host callback or debug), else no opts.
                         (if ropts
                           (react-dom-client/hydrateRoot mount-point wrapped-tree ropts)
                           (react-dom-client/hydrateRoot mount-point wrapped-tree))
                         (let [r (react-dom-client/createRoot mount-point)]
                           (.render r wrapped-tree)
                           r))]
      ;; rf2-w1g0d2: shared track-and-unmount tail (unmount-op = .unmount).
      (track-active-root! active-roots-cell (fn [r] (.unmount r)) root))))

;; ---- after-render --------------------------------------------------------
;;
;; `:adapter/after-render` for React-only substrates (UIx) per
;; rf2-334d9 (Mike decision rf2-neiqf 2026-05-19: publish via
;; useLayoutEffect) — without this `(rf/after-render f)` under those
;; adapters would be a silent no-op.
;;
;; Architecture. Per-adapter queue cell + a sentinel function component
;; injected at the root of every mounted tree (via `make-render`'s
;; Fragment wrap). The sentinel uses `React.useLayoutEffect` to drain
;; the queue after each commit — same DOM-mutations-applied / pre-paint
;; timing semantics as Reagent's `r/after-render`. When `after-render`
;; is called, the sentinel's stashed `setState` bumps a tick to force a
;; commit so its `useLayoutEffect` fires and drains the queue.
;;
;; Native-mount parity (rf2-t0x90). The Fragment-wrap sentinel only
;; enters the tree when an app mounts through the adapter's `:render`
;; slot. But the documented boot idiom (and all three adapter testbeds)
;; mounts via the substrate-native renderer directly (`uix-dom/render-
;; root`, Helix's `(.render root …)`), which bypasses `make-render` —
;; so a natively-mounted UIx app NEVER has a sentinel in its tree.
;; Reagent's `r/after-render` is a global post-flush hook that works
;; regardless of mount path; without parity, the SAME `(rf/after-render
;; f)` call has correct post-commit timing on Reagent but degraded
;; microtask timing on natively-mounted UIx — a silent substrate
;; divergence in a public primitive.
;;
;; The fix: a per-adapter SINGLETON DRIVER ROOT, mounted lazily the
;; first time `after-render` is called with no app-tree sentinel
;; present. The hook mounts the sentinel component into a detached
;; (never-attached-to-the-document) React root via `createRoot`; the
;; sentinel's mount LAYOUT effect stashes its `set-tick` setter into
;; `set-tick-ref` exactly as the Fragment-wrap sentinel does, so the
;; same `set-tick` → commit → `useLayoutEffect`-drain machinery now
;; drives post-commit timing on the native-mount path too. The driver
;; root is created once per adapter and reused for the process lifetime
;; (it renders no DOM — the sentinel returns nil — so a detached host
;; node is sufficient and never touches the document). An app-tree
;; sentinel, when present, still wins: it claims `set-tick-ref` and the
;; driver root simply sits idle.
;;
;; Headless / no-DOM fallback. `createRoot` needs `document`; under a
;; pure-node runner (no jsdom) there is no DOM to mount into. In that
;; case — and in the historical pre-DOM-API path — fall through to
;; `queueMicrotask` so `f` still fires once the current microtask
;; boundary completes. Honest under the "tests poke `interop/after-
;; render` without a DOM" path.

(defn make-after-render-queue-cell
  "Return a fresh `(atom [])` queue of pending after-render callbacks.
  Each adapter owns its own cell so multiple React-shaped adapters can
  coexist in a test bundle without clobbering each other's queue."
  []
  (atom []))

(defn make-after-render-set-tick-ref
  "Return a fresh `(atom nil)` slot the sentinel writes its `setState`
  setter into on mount and clears on unmount. Each adapter owns its
  own so the after-render hook below can route to the right adapter's
  sentinel."
  []
  (atom nil))

(defn make-after-render-driver-root-cell
  "Return a fresh `(atom nil)` slot holding the per-adapter SINGLETON
  DRIVER ROOT — the detached React root the after-render hook mounts
  the sentinel into the first time `after-render` is called with no
  app-tree sentinel present (rf2-t0x90 native-mount parity). Lazily
  populated and reused for the adapter's lifetime; each adapter owns
  its own so multiple React-shaped adapters in a test bundle don't
  share a driver root. Drained on `dispose-adapter!`."
  []
  (atom nil))

(defn- drain-after-render-queue!
  "Atomically swap the pending-callbacks vector with empty and invoke
  each in order. Per-fn throws are swallowed so one misbehaving callback
  cannot strand the rest of the drain."
  [queue-cell]
  (let [[pending] (reset-vals! queue-cell [])]
    (doseq [f pending]
      (try (f) (catch :default _ nil)))))

(defn make-after-render-sentinel
  "Build the sentinel React function component for an adapter. The
  sentinel returns nil (no DOM impact) and:

    1. On mount, stashes its `setState` setter in `set-tick-ref` so
       `:adapter/after-render` can trigger a commit. Cleared on unmount.
       Installed from a LAYOUT effect (rf2-he7se finding 3) so the
       singleton-driver-root setup's `flushSync` arms the slot
       synchronously before it decides setter-present vs. microtask
       fallback. `flushSync` ALWAYS flushes layout effects synchronously
       (a documented guarantee); its flushing of PASSIVE `useEffect`s is a
       React-19 implementation detail, not a contract — so the prior
       passive install was not robust across React versions/configs.
    2. On every commit, fires `React.useLayoutEffect` to drain
       `queue-cell` — same timing as `r/after-render`'s post-commit
       run.

  The sentinel uses raw React hooks (`React/useState`,
  `React/useLayoutEffect`) rather than the substrate's hook ns so the
  same impl works for UIx and any future React-shaped substrate
  using this spine.

  Returned value is the bare function component, suitable for
  `(React/createElement sentinel-cmp nil)`."
  [queue-cell set-tick-ref]
  (fn after-render-sentinel [_props]
    (let [tick+setter (React/useState 0)
          set-tick    (aget tick+setter 1)]
      ;; Install the setter from a LAYOUT effect, not a passive useEffect
      ;; (rf2-he7se finding 3). `ensure-after-render-driver-root!` renders
      ;; this sentinel inside `react-dom/flushSync` and EXPECTS the setter
      ;; present in `set-tick-ref` the instant flushSync returns, so it can
      ;; bump the tick rather than falling through to the microtask drain.
      ;; `flushSync` ALWAYS flushes layout effects synchronously during the
      ;; commit; whether it flushes passive (`useEffect`) effects is a
      ;; React-19 implementation detail, NOT a documented guarantee. Where
      ;; passives are deferred (older React / future configs) a passive
      ;; setter-install would leave the slot nil on flushSync's return, so
      ;; the hook would take the `queueMicrotask` fallback and the queue
      ;; could drain BEFORE the app commit after-render is meant to
      ;; observe. A layout mount effect makes the arm-before-decide
      ;; ordering version-INDEPENDENT.
      (React/useLayoutEffect
        (fn mount-effect []
          (reset! set-tick-ref set-tick)
          (fn cleanup []
            ;; Only clear if it's still us — guards against a sentinel
            ;; from a sibling root having claimed the slot in between.
            (compare-and-set! set-tick-ref set-tick nil)))
        #js [set-tick])
      ;; No deps array — fires every commit, which is the contract
      ;; (rf/after-render bumps the tick to force a commit, so the
      ;; useLayoutEffect fires and drains).
      (React/useLayoutEffect
        (fn layout-effect []
          (drain-after-render-queue! queue-cell)
          js/undefined))
      nil)))

(defn- dom-available?
  "True when a `document` capable of creating elements is reachable —
  the precondition for mounting the singleton driver root. False under
  a pure-node runner (no jsdom), where the after-render hook falls
  through to the microtask drain."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- ensure-after-render-driver-root!
  "Lazily mount the per-adapter SINGLETON DRIVER ROOT (rf2-t0x90). If
  `driver-root-cell` is empty, create a detached host node + React root,
  render `sentinel-cmp` into it inside `react-dom/flushSync` so the
  sentinel's mount LAYOUT effect runs SYNCHRONOUSLY and stashes its
  `set-tick` setter into `set-tick-ref` before this fn returns. The host
  node is never attached to the document — the sentinel renders nil, so
  no DOM is produced. Idempotent: a populated cell is left untouched.
  Returns nil."
  [driver-root-cell sentinel-cmp]
  (when (nil? @driver-root-cell)
    (let [host (.createElement js/document "div")
          root (react-dom-client/createRoot host)]
      (reset! driver-root-cell root)
      ;; flushSync so the sentinel's mount LAYOUT effect (which stashes
      ;; set-tick into set-tick-ref) runs synchronously — the caller
      ;; bumps the tick immediately after, expecting the setter present.
      ;; flushSync ALWAYS runs layout effects synchronously; passive
      ;; `useEffect`s are not contractually flushed by it (React-19 detail
      ;; only), so the layout-effect install keeps the slot armed on return
      ;; regardless of React version — without it the slot could be nil and
      ;; force the microtask fallback that drains before the pending app
      ;; commit (rf2-he7se finding 3).
      (react-dom/flushSync
        (fn [] (.render root (React/createElement sentinel-cmp nil))))))
  nil)

(defn make-after-render-hook
  "Build the `:adapter/after-render` impl fn. The returned fn:

    1. Enqueues `f` on `queue-cell`.
    2. If an app-tree sentinel is mounted (`set-tick-ref` is non-nil —
       the app mounted through the adapter's `:render` slot), bumps its
       tick — React schedules a commit, the sentinel's `useLayoutEffect`
       fires, and the queue drains in post-commit / pre-paint order.
    3. Otherwise (the documented native-mount path, rf2-t0x90, where the
       app mounted via the substrate-native renderer and no Fragment-wrap
       sentinel is in the tree) lazily mounts the per-adapter SINGLETON
       DRIVER ROOT — a detached React root carrying the same sentinel —
       and bumps its now-stashed tick, giving the native-mount path the
       SAME post-commit timing as Reagent's global `r/after-render`.
    4. If no DOM is reachable (pure-node runner, no jsdom), falls through
       to a `queueMicrotask` drain so `f` still fires once the current
       microtask boundary completes."
  [queue-cell set-tick-ref sentinel-cmp driver-root-cell]
  (fn after-render-hook [f]
    (swap! queue-cell conj f)
    (when (and (nil? @set-tick-ref) (dom-available?))
      ;; Native-mount path: no app-tree sentinel claimed the slot — arm
      ;; the singleton driver root, whose sentinel stashes set-tick.
      (ensure-after-render-driver-root! driver-root-cell sentinel-cmp))
    (if-let [set-tick @set-tick-ref]
      (set-tick inc)
      (if (exists? js/queueMicrotask)
        (js/queueMicrotask #(drain-after-render-queue! queue-cell))
        (.then (js/Promise.resolve) #(drain-after-render-queue! queue-cell))))
    nil))

(defn dispose-frame-sub-caches!
  "Walk every live frame's per-frame sub-cache and dispose each cached
  Reaction (Spec 006 §Adapter disposal lifecycle MUST 1; rf2-9fdkb,
  rf2-a47kq, rf2-jcjul).

  Why the walk exists at all. Component-unmount-driven disposal handles
  the mounted case — the reactive substrate reaps a derived value once
  its last watcher drops. This walk covers the test-fixture / headless
  path where no component unmount fires before the adapter goes away,
  AND the SSR / server-render path where the rendered tree was string-
  serialised without ever being mounted. Without the walk, a long-lived
  process driving sequential `init! → dispose-adapter!` cycles (test
  bundles, hot-reload, multi-adapter integration tests) accumulates
  cached Reactions closed over stale frames forever.

  Per-entry contract. For every `[k entry]` in every live frame's
  `:sub-cache` atom:

    1. Dispose the cached `:reaction` through the supplied disposer.
       Adapter teardown supplies the exact claimed generation's disposer;
       the direct/test arity routes through `interop/dispose!`.
    2. After draining each frame's entries, `reset!` its sub-cache
       atom to `{}`.

  The walk is best-effort: a throwing per-entry dispose (e.g. a
  misbehaving user `:on-dispose` hook, or a poison entry inserted by
  tests) does NOT abort the rest of the walk — every other cached
  Reaction in the same cache AND every cache in subsequent frames
  still gets disposed and cleared. Per-entry throws are swallowed.

  Used by every React-shaped adapter's `dispose-adapter!` — wired into
  the `make-dispose-adapter!` factory for the first-party re-frame.ui
  adapter plus UIx, and called directly from the Reagent /
  reagent-slim adapters' dispose paths. Centralising the walk here is
  the rf2-jcjul lockstep: one implementation, five adapters, zero drift.

  The one-arg form is the adapter-cleanup path: `dispose-reaction!` is the
  exact claimed generation's substrate disposer, captured before terminal
  teardown hides that generation from every public/routed lookup. The zero-arg
  form remains the direct/test seam and routes through `interop/dispose!` while
  an adapter is publicly live."
  ([]
   (dispose-frame-sub-caches! interop/dispose!))
  ([dispose-reaction!]
   (doseq [[_ frame-record] @frame/frames]
     (when-let [cache (:sub-cache frame-record)]
       (doseq [[_k entry] @cache]
         (when-let [r (:reaction entry)]
           (try (dispose-reaction! r)
                (catch :default _ nil))))
       (reset! cache {})))))

(defn dispose-active-roots-and-caches!
  "Core dispose-drain shared by BOTH spines' `dispose-adapter!`
  (rf2-w1g0d2). Satisfies the substrate-common subset of the Spec 006
  §Adapter disposal lifecycle four-MUST list:

    1. Cancel in-flight reactive subscriptions — `dispose-frame-sub-caches!`.
    2. Release host-specific resources — drain `active-roots-cell`, calling
       `(unmount-op root)` on every tracked root and SWALLOWING per-root
       throws so one misbehaving root cannot strand the rest of the drain;
       then reset the cell to `#{}`.
    3. Discard internal caches — clear the hiccup `emitter-cell`.

  `unmount-op` is the per-spine root-unmount fn (`.unmount` for the
  React-hook spine, the injected `unmount-root` for the ratom family); it
  is the ONLY substrate-varying step in this subset, so parameterising it
  lets both spines reuse the identical drain loop. The React-hook spine
  layers its extra teardown (warn-cache + after-render driver-root +
  set-tick slot) AFTER calling this; the ratom family's dispose IS exactly
  this subset (no warn-cache, no driver-root)."
  [dispose-reaction! unmount-op active-roots-cell emitter-cell]
  (dispose-frame-sub-caches! dispose-reaction!)
  (doseq [root @active-roots-cell]
    (try (unmount-op root)
         (catch :default _ nil)))
  (reset! active-roots-cell #{})
  (when emitter-cell (reset! emitter-cell nil))
  nil)

(defn make-dispose-adapter!
  "Build a `dispose-adapter!` fn satisfying Spec 006 §Adapter disposal
  lifecycle (rf2-9fdkb). The returned fn:

    1. Walks every live frame's per-frame sub-cache and disposes each
       cached Reaction (`dispose-frame-sub-caches!`), satisfying MUST
       (1): cancel all in-flight reactive subscriptions.
    2. Drains `active-roots-cell` by calling `.unmount` on every
       tracked React root, satisfying MUST (2): release host-specific
       resources.
    3. Clears the spine's per-adapter caches — `active-roots-cell`,
       `warn-cache`, `emitter-cell` — satisfying MUST (3): discard
       internal caches.

  MUST (4) (subsequent calls return `:rf.error/adapter-disposed`) is
  enforced one level up by `substrate-adapter/dispose-adapter!` via
  the `disposed?` breadcrumb (rf2-6wxys).

  Best-effort drains. React's `.unmount` is idempotent / no-op on
  already-unmounted roots; we swallow any unmount throw so one
  misbehaving root does not strand the rest of the drain. The
  sub-cache walk has its own per-entry try/catch (see
  `dispose-frame-sub-caches!`).

  rf2-t0x90: also unmounts the singleton after-render DRIVER ROOT (if
  one was lazily armed) and clears its `set-tick` slot, so a torn-down
  adapter releases it and a subsequent `init!` re-arms a fresh one
  against the new adapter rather than bumping a stale setter."
  [{:keys [active-roots-cell warn-cache emitter-cell
           after-render-driver-root-cell after-render-set-tick-ref]}]
  (fn dispose-adapter! []
    ;; rf2-w1g0d2: the substrate-common subset (sub-cache walk + active-roots
    ;; drain-with-swallow + emitter clear) is the shared core; the React-hook
    ;; spine layers warn-cache + driver-root + set-tick teardown on top.
    (dispose-active-roots-and-caches! rf-disposable/-dispose
                                      (fn [r] (.unmount r))
                                      active-roots-cell emitter-cell)
    (when warn-cache (reset! warn-cache #{}))
    (when after-render-driver-root-cell
      (when-let [root @after-render-driver-root-cell]
        (try (.unmount root) (catch :default _ nil)))
      (reset! after-render-driver-root-cell nil))
    (when after-render-set-tick-ref
      (reset! after-render-set-tick-ref nil))
    nil))

(defn make-hiccup-emitter-cell
  "Return a fresh `(atom nil)` cell that will hold the substrate's
  late-bound hiccup-emitter fn. Each adapter owns its own cell so
  multiple adapters can coexist in a test bundle without clobbering each
  other's emitter."
  []
  (atom nil))

(defn set-hiccup-emitter!
  "Install a render-tree → HTML fn into `emitter-cell`. Idempotent."
  [emitter-cell f]
  (reset! emitter-cell f))

(defn make-render-to-string
  "Return a `render-to-string` fn that reads its emitter from
  `emitter-cell`. Throws `:rf.error/no-hiccup-emitter-bound` if no
  emitter has been installed (the SSR artefact resolves the
  `:reagent/set-hiccup-emitter!` late-bind hook to install one)."
  [emitter-cell]
  (fn render-to-string [render-tree opts]
    (if-let [emit @emitter-cell]
      (emit render-tree opts)
      ;; EP-0015 (rf2-uwqale): carry an EP-0015-safe SUMMARY of the
      ;; render-tree, never the raw tree — a thrown render-to-string
      ;; ex-data is captured by SSR/static-export error handlers and
      ;; host logs before the record projector can classify it, and a
      ;; hiccup tree can carry app-owned sensitive/large values.
      (rf-error/throw-error!
        :rf.error/no-hiccup-emitter-bound
        'rf/render-to-string
        "require re-frame.ssr (the SSR ns-load resolves the :reagent/set-hiccup-emitter! late-bind hook automatically), or call set-hiccup-emitter! directly"
        {:extra {:render-tree/summary (rf-error/diag-value-summary render-tree)}}))))

;; ---- context provider — substrate-agnostic CORE ---------------------------
;;
;; Every React-shaped adapter shares the same React.createContext object
;; (in re-frame.adapter.context). The substrate-agnostic CORE is the
;; frame-resolution + element-build below; the user-facing COMPONENT
;; SHELL is NATIVE to each substrate (UIx `defui`, Helix `defnc`,
;; Reagent hiccup) and lives in the adapter ns.
;;
;; Seam placement (rf2-z7hfp). Earlier this ns shipped `frame-provider`
;; as a plain CLJS fn that destructured `{:keys [frame children]}`, and
;; each React-hook adapter RE-EXPORTED it as the component a user hands to
;; `$`. That put the abstraction seam BELOW the layer where each
;; substrate's element macro (`$` in Helix/UIx, hiccup in Reagent)
;; marshals props: Helix's `$` handed the fn a raw JS object with string
;; keys; UIx's `$` ALSO stringified keyword prop values (dropping the
;; namespace), so `:frame` silently fell to `:rf/default`. Each adapter
;; then carried a bespoke un-mangling wrapper to repair the props before
;; they reached the shared fn (helix rf2-9ok1s, uix rf2-8svnm) — a
;; standing per-substrate-patch hazard: a new substrate, or a new prop,
;; reopens the same class of bug.
;;
;; Move the seam UP (Mike-ruled C, rf2-z7hfp). The spine now provides
;; ONLY the substrate-agnostic core — `build-frame-provider-element`
;; (frame-resolution + element-build, touching no substrate prop-
;; marshalling). The COMPONENT SHELL sits ABOVE where `$` marshals: each
;; React-hook adapter defines its `frame-provider` as a NATIVE
;; substrate component (`defui` / `defnc`) that reads its props in that
;; substrate's OWN lossless idiom (UIx's `argv` channel, Helix's
;; `extract-cljs-props`), then hands a clean frame-kw + children to this
;; core. The prop-mangling class is impossible by construction — there is
;; no plain fn under `$` for the element macro to mangle, and no per-
;; substrate un-mangling patch to drift.

(defn build-frame-provider-element
  "Substrate-agnostic CORE of the frame-provider (rf2-z7hfp). Given a
  resolved frame keyword and a children value, returns the shared frame
  Context Provider React element scoping that frame to its subtree —
  inside the subtree, `(rf/capture-frame)` / `reg-view`-registered
  descendants resolve to the named frame. Per Spec 002 §What
  `frame-provider` is (CLJS reference).

  Frame-resolution: `frame-kw` is REQUIRED (EP-0002 carried invariant).
  There is NO `(or frame-kw :rf/default)` floor — per Spec 002 §Frame
  target resolution the runtime never synthesises a frame from absence.
  A native frame-provider shell (UIx `defui`) that
  delegates here with a missing or `nil` `:frame` is a CONFIGURATION
  ERROR: this fn emits `:rf.error/no-frame-context` through the always-on
  error axis and throws, so a tooling-generated or hand-authored tree
  that elides the frame fails loudly at the provider rather than silently
  scoping every descendant call to a conventional default. This mirrors
  the Reagent-side `re-frame.views.provider/frame-provider` contract.

  Children-normalisation: the native trailing-`$`-children idiom
  (rf2-7kii2) hands this core whatever shape each substrate's element
  macro stashes on `:children` — a JS ARRAY for multiple trailing
  children (UIx's `(cljs.core/array …)`), a
  SINGLE element for one trailing child, a CLJS vector/seq, or
  `nil` (no children). All four collapse to a flat positional arg list
  for `provider-element`: a JS array is spread via `array-seq`, an
  existing CLJS sequential is passed through, `nil` becomes no children,
  and any lone non-collection child is wrapped. React keys multi-child
  arrays correctly because they reach `createElement` as distinct
  positional args, not a single array child.

  This fn touches NO substrate prop-marshalling: the native component
  shell in each adapter has already read its props in the substrate's
  idiom and hands this core a clean CLJS frame-kw + children. That is the
  whole point of the moved seam — the marshalling-sensitive surface
  (`$`/hiccup → component) lives ABOVE this core, in substrate-native
  code, so a keyword frame-id survives intact on every substrate by
  construction."
  [frame-kw children]
  ;; rf2-9kpigo: reject a non-nil `:frame` that is neither a frame-id keyword
  ;; nor a live frame value BEFORE it reaches React Context. A nil routes to
  ;; `:rf.error/no-frame-context` (absence); any other such value routes to
  ;; the distinct `:rf.error/bad-frame-provider-arg`.
  ;; The native UIx shells read their props in the substrate idiom and
  ;; delegate the clean frame-kw here, so this is the single validating seam
  ;; for both function-component substrates (mirrors the Reagent-side
  ;; `re-frame.views.provider/frame-provider` contract).
  (frame/require-frame-provider-target!
    frame-kw
    're-frame.substrate.spine/build-frame-provider-element)
  (apply adapter-context/provider-element
         frame-kw
         (adapter-context/normalize-children children)))

;; ---- render flush for tests ----------------------------------------------
;;
;; `flush-views!` wraps React's `act()` so test code can drive a
;; subscribe → re-render cycle synchronously. React 18 ships `act` in
;; `react-dom/test-utils`; React 19 promotes it onto the React namespace
;; proper. Probe both — without the fallback, users on React 18.x get a
;; silent no-op (subsumes rf2-jk7hr).

(defn- resolve-act-fn
  "Return React's act() if available, else nil. React 19 hosts act on
  the React namespace directly; React 18 hosts it on react-dom/test-utils.
  Mirrors the Reagent test harness's act-fn in
  `adapters/reagent/test/re_frame/frame_provider_context_cljs_test.cljs`."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [tu (js/require "react-dom/test-utils")]
          (.-act tu))
        (catch :default _ nil))))

(defn flush-views!
  "Flush pending substrate renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f); with no arg, calls (act
  (fn [] nil)) to flush pending effects. Returns nil. No-op when act() is
  not reachable in the current React build."
  ([] (flush-views! (fn [] nil)))
  ([f]
   (when-let [act (resolve-act-fn)]
     (act f))
   nil))

;; ---- synchronous render flush (rf2-40a84) ---------------------------------
;;
;; `flush-render!` is the PRODUCTION-grade synchronous render-commit fn for
;; the substrate-adapter contract (distinct from `flush-views!`, which is a
;; test-only `act()` wrapper). It exists because the React-shaped substrates
;; schedule their re-renders through React's normal lane scheduler, whose
;; commit lands on a `requestAnimationFrame`-style tick that (a) fires AFTER
;; an eval'd `dispatch` returns and (b) is throttled to ~never in a
;; backgrounded / unfocused tab. So tooling that drives the view lifecycle
;; headless — the re-frame2-pair MCP `dispatch` → observe-the-DOM loop
;; (rf2-40a84 / consumed by rf2-vk79g's dispatch-and-settle) — cannot rely on
;; the scheduled commit ever arriving.
;;
;; `react-dom/flushSync` runs its callback and SYNCHRONOUSLY flushes every
;; React update scheduled inside it (and any already-pending work) to the
;; DOM before returning — it is NOT rAF-scheduled, so it is immune to the
;; backgrounded-tab throttle and fires even headless. After
;; `(flush-render! f)` returns, any state change `f` triggered (or any render
;; already pending) is committed; a caller can then read the settled DOM /
;; epoch. The 0-arity form flushes already-pending work with an empty
;; callback.
;;
;; This is the React-hook (UIx) spine impl; the Reagent / reagent-
;; slim family realises the same contract through `reagent.core/flush` (its
;; render-queue drain forces the component re-renders synchronously and, on
;; React 19, commits them via `flushSync`), wired in the ratom adapter.

(defn flush-render!
  "Synchronously flush pending React renders to the DOM via
  `react-dom/flushSync` (Spec 006 §`flush-render!`). The 1-arity form runs
  `f` inside `flushSync` so any state change `f` schedules commits before
  the call returns; the 0-arity form flushes already-pending work. Unlike
  `flush-views!` (a test-only `act()` wrapper) this is production-grade and
  NOT rAF-scheduled, so headless tooling can drive a `dispatch → flush-render!
  → observe-settled-DOM` loop even in a backgrounded tab (rf2-40a84). Returns
  nil. No-op-safe when there is nothing pending — `flushSync` with an empty
  callback is a cheap no-op."
  ([] (flush-render! (fn [] nil)))
  ([f]
   (react-dom/flushSync f)
   nil))

;; ---- source-coord wrapper (Spec 006 §Source-coord; rf2-z7f7 / rf2-z9n1) --
;;
;; Every React-shaped substrate adapter MUST inject
;; `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on each registered
;; view's root DOM element when `interop/debug-enabled?` is true. The
;; React-element-walking path uses `React.cloneElement` (rather than the
;; hiccup-walk path views.cljs takes) because React elements are opaque
;; — we can clone the root with the extra prop, but we cannot peek
;; inside a fragment / function-component head.
;;
;; Production-elision contract (rf2-z7f7 / Spec 009): the entire branch
;; sits inside `(when interop/debug-enabled? ...)` so the closure
;; compiler constant-folds the wrapper away under :advanced +
;; goog.DEBUG=false. Each adapter ships a bundle-grep elision test that
;; confirms the `data-rf2-source-coord` literal is absent from
;; production builds.

;; `format-source-coord` / `format-view-id` are the pure string projections
;; of the annotation attribute VALUES — shared with the Reagent hiccup walk
;; through the leaf `re-frame.adapter.context` so the React-element-clone
;; path here and the hiccup path in `re-frame.views.source-coord-annotation`
;; emit byte-identical `data-rf2-source-coord` / `data-rf-view` values
;; across substrates (rf2-t9s6p6). Aliased to the spine's historical names
;; so call sites in `make-wrap-view` are unchanged.
(def format-source-coord adapter-context/format-source-coord)
(def format-view-id       adapter-context/format-view-id)

(defn make-warn-once-cache
  "Return an `(atom #{})` for tracking per-id warn-once emission. Each
  adapter owns its own cache so multiple adapters can coexist in test
  bundles without clobbering each other's warn-once state."
  []
  (atom #{}))

(defn make-clear-warned-fn
  "Return a thunk that resets `cache-atom` to `#{}` and returns nil.
  Tests use this between cases (via `make-reset-runtime-fixture` and the
  chained `:adapter/clear-warn-once-caches!` hook) so a sibling test's
  first-encounter warning cannot silently swallow a later test's same-
  id warning."
  [cache-atom]
  (fn clear-warned-non-dom-roots! []
    (reset! cache-atom #{})
    nil))

(defn make-warn-non-dom-root-fn
  "Return a warn-once fn for use inside `inject-source-coord-attr`.
  Parameterised on the substrate-name string so the warning text
  attributes the host substrate. `cache-atom` is the per-adapter
  warn-once set."
  [cache-atom substrate-name]
  (fn warn-non-dom-root! [id type-tag]
    (when-not (contains? @cache-atom id)
      (swap! cache-atom conj id)
      (.warn js/console
        (adapter-context/non-dom-root-warning id type-tag substrate-name)))))

(defn- dom-element?
  "True if the React element's `type` is a string (a DOM tag like
  \"div\"). Function/class components and Fragments have non-string
  `type`s and are exempt per Spec 006."
  [react-element]
  (and react-element
       (some? (.-type react-element))
       (string? (.-type react-element))))

(defn- inject-source-coord-attr
  "Wrap `out` (the user component's React element output) with a
  cloneElement call that adds `data-rf2-source-coord` (Spec 006
  §Source-coord annotation, rf2-z7f7) and `data-rf-view` (Spec 006
  §View tagging contract, rf2-01il5). Non-element outputs (nil,
  fragment, function-component head) emit a one-shot warning per id
  and pass through unchanged — pair tools fall back to `:rf/id` for
  source-coord; the view-walker falls back to the Fiber-walker primary
  path for hierarchy capture.

  CRITICAL: cloneElement returns a new element with the SAME `type` and
  `key` slots — it does NOT wrap the original. Wrapping with a
  synthetic host element (the `[:div]` shape rejected by Spec 006
  §View tagging contract) would break flexbox / CSS Grid / table
  layouts / `:nth-child` selectors / positioning ancestors / stacking
  contexts / CSS containment.

  History: an earlier version also patched the JSX-shaped source-coord
  props (`_jsxFileName` / `_jsxLineNumber` / `_jsxColumnNumber`)
  intended for React DevTools' \"View source\" gesture (rf2-fa4ly).
  The feature never worked — DevTools reads `__source` from
  `React.createElement`'s third arg, not from element props — and the
  props leaked to the DOM as attributes, triggering React's
  \"unrecognised prop\" warnings. rf2-rohdn dropped the injection."
  [warn-fn id coord-attr view-attr out]
  (cond
    (dom-element? out)
    (let [props             (.-props out)
          existing-coord    (when props (aget props "data-rf2-source-coord"))
          existing-view     (when props (aget props "data-rf-view"))
          patch             #js {}]
      (when-not existing-coord
        (aset patch "data-rf2-source-coord" coord-attr))
      (when-not existing-view
        (aset patch "data-rf-view" view-attr))
      (if (and existing-coord existing-view)
        out
        (React/cloneElement out patch)))

    :else
    (do
      (when (some? out)
        (warn-fn id (some-> out .-type)))
      out)))

;; ---- view-unmount parity (rf2-te71r; follow-on from rf2-9hoos) ------------
;;
;; Phase-A (rf2-9hoos) added `:rf.view/unmounted`, fired via a per-render-
;; instance reaction-dispose hook armed in `re-frame.views`. That path
;; rides the Reagent family's tracked render reaction
;; (`componentWillUnmount` disposes the instance's tracked deps). The
;; React-hook substrates (UIx) run the same `views.cljs`
;; frame-aware-view wrapper inside a function component with NO tracked
;; render reaction (they intentionally don't publish
;; `:adapter/make-reaction`, so `interop/make-reaction` returns nil and
;; the views-side arm no-ops). This spine seam restores parity: the
;; React-hook wrap-view arms a `React.useEffect` empty-deps cleanup that
;; emits `:rf.view/unmounted` on instance teardown.
;;
;; Render-key threading. On the Reagent family the instance-token is
;; cached on the component object so the render-key
;; (`[view-id instance-token]`) is stable across re-renders. The React-
;; hook spine has no such per-instance object the views-side token-mint
;; can latch onto (`provider/reagent-component-token` mints a FRESH token
;; every render under UIx because no `:adapter/current-component` is
;; published), so this seam mints its OWN stable per-instance token into a
;; `useRef` — `[view-id <stable-token>]` is a well-formed render-key tuple
;; whose token survives re-renders and matches the render whose teardown
;; it marks. The required `:rf.view/unmounted` tags (`:view-id`, `:frame`)
;; carry the values resolved in-render; `:render-key` carries the stable
;; per-instance tuple.
;;
;; Production elision. The whole arm sits inside `interop/debug-enabled?`
;; — under :advanced + goog.DEBUG=false the wrap collapses to the bare
;; `user-fn` (no hooks, no emit), so the `rf.view/unmounted` sentinel
;; (already present from phase-A) stays absent in prod bundles.

(def ^:private unmount-instance-counter
  "Process-wide monotonic counter for stable per-instance render-key
  tokens minted by the React-hook wrap-view's unmount sentinel
  (rf2-te71r). Dev-only — the only call site sits inside the sentinel
  component, which only runs when React renders it under
  `interop/debug-enabled?`, so this and its `swap!` DCE in production
  builds. Distinct from the views-side `provider/instance-counter` (the
  spine carries no spine→views dependency edge); both are in-run
  discriminators with no cross-run correlation guarantee."
  (atom 0))

(defn- emit-view-unmounted-via-hook!
  "Fire `:rf.view/unmounted` for `render-key` in `frame-id` through the
  `:views/emit-view-unmounted!` late-bind hook (published by
  `re-frame.views`, rf2-te71r). Reaching the emit through late-bind keeps
  the spine (core/substrate) free of a static require on the CLJS-only
  views ns. No-op when the hook is unresolved (views not on the
  classpath) or when `interop/debug-enabled?` is false. The views-side
  impl is itself gated on `interop/debug-enabled?`."
  [view-id render-key frame-id]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn-cached :views/emit-view-unmounted!)]
      (emit! view-id render-key frame-id))))

(defn make-unmount-sentinel
  "Build the per-view unmount-sentinel React function component
  (rf2-te71r). The sentinel renders no DOM (returns nil) and arms a
  `React.useEffect` empty-deps cleanup that emits `:rf.view/unmounted` on
  its instance teardown — the React-hook parity for the Reagent family's
  phase-A (rf2-9hoos) reaction-dispose unmount hook.

  Why a sibling SENTINEL rather than hooks inline in wrap-view's wrapped
  fn. A registered view's wrapper (`(rf/view id)`) is also INVOKED
  DIRECTLY (headless, no React render) — the suite's render-trace tests do
  `((rf/view id))`. Calling `React.useRef` / `useEffect` there throws
  ('hooks can only be called inside a function component'). Routing the
  hooks through a sentinel that wrap-view emits as a sibling ELEMENT means
  a direct invocation merely builds an element object (no hook execution),
  while a real React mount renders the sentinel and runs its hooks — the
  same safety the after-render sentinel relies on.

  Props (passed by wrap-view via `React/createElement`):
    :view-id  the registered view id (the `:view-id` tag + render-key head)
    :frame    the frame resolved at wrap-view render time (the `:frame`
              tag; captured in a ref so the cleanup, which runs outside
              React render, reports the frame the instance rendered under)

  Stable per-instance token: minted once into a `useRef` so the
  `:render-key` tuple `[view-id <token>]` survives re-renders and matches
  the render whose teardown it marks. Empty-deps `#js []` → the effect's
  cleanup runs exactly once on unmount (one-shot, matching the Reagent
  path's reaction-dispose semantics)."
  []
  (fn unmount-sentinel [^js props]
    (let [view-id    (.-viewId props)
          frame-id   (.-frame props)
          token-ref  (React/useRef nil)
          token      (or (.-current token-ref)
                         (let [t (swap! unmount-instance-counter inc)]
                           (set! (.-current token-ref) t)
                           t))
          render-key [view-id token]
          ;; Capture the frame in a ref so a frame change across re-renders
          ;; (rare for a mounted instance) still has the cleanup report the
          ;; last-rendered frame rather than a stale closure value.
          frame-ref  (React/useRef nil)]
      (set! (.-current frame-ref) frame-id)
      (React/useEffect
        (fn unmount-arm-effect []
          (fn cleanup []
            (emit-view-unmounted-via-hook! view-id render-key
                                           (.-current frame-ref))))
        #js [])
      nil)))

(def ^:private void-dom-tags
  "HTML5 void elements — self-closing, MUST NOT receive children. React
  raises a void-element error (and SSR/hydration breaks) if `input`,
  `img`, `br`, … are given a child. The set is fixed in HTML5 (no
  maintenance burden).

  Lockstep with `reagent2.impl.template/void-tags` and
  `re-frame.ssr.emit/void-elements` (same membership, keyword vs string
  shapes). Bundle isolation forbids `:require` across artefacts (core
  must not reach into the adapters or the SSR artefact), so the set is
  duplicated by intent. If HTML5 ever extends the void-element list
  (extraordinarily unlikely), update every copy."
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link"
    "meta" "param" "source" "track" "wbr"})

(defn- void-dom-root?
  "True when `react-element` is a void HTML DOM element (string `type`
  in `void-dom-tags`) — React rejects children on such roots, so the
  unmount sentinel must ride as a SIBLING (in a Fragment) rather than a
  child. Function/class components and Fragments have non-string types
  and are never void."
  [react-element]
  (let [t (some-> ^js react-element .-type)]
    (and (string? t) (contains? void-dom-tags t))))

(defn- append-unmount-sentinel
  "Attach an unmount-sentinel to `annotated` (the source-coord /
  view-id-annotated user output) so the view instance fires
  `:rf.view/unmounted` on teardown (rf2-te71r). Two shapes, keyed on
  whether the user's root is a VOID DOM element:

  - Non-void root (the dominant case): `cloneElement` with a trailing
    extra CHILD. This preserves the root's `type`, `key`, and existing
    props/children — the inspected output is still the user's annotated
    root element, NOT a Fragment wrapper, so the source-coord + view-id
    contract and the layout-critical no-wrapper guarantee both hold.

  - Void root (`input` / `img` / `br` / …, rf2-ghfkkk): React rejects
    children on void elements (it raises a void-element error and breaks
    hydration), so the sentinel CANNOT be a child. Instead, return a
    `React.Fragment` holding the user's annotated root element UNCHANGED
    (its `data-rf2-source-coord` / `data-rf-view` attrs intact) plus the
    sentinel as a SIBLING. A Fragment renders no wrapper DOM node, so the
    committed tree's DOM semantics are stable — the void element stays a
    direct child of its real parent, with no synthetic host element. The
    inspected root is still the user's annotated void element; only the
    sentinel's sibling position changes. This keeps a valid registered
    view returning a void root valid under dev-mode instrumentation
    (pre-fix it became invalid, producing React void-element errors that
    vanished in production).

  The sentinel renders no DOM in BOTH shapes. On a headless direct
  invocation of the wrapped fn (the suite's render-trace tests call
  `((rf/view id))`) this merely builds an element object whose sentinel
  hooks never run; only a real React mount renders the sentinel and arms
  its useEffect cleanup.

  Non-element / nil output (a view returning a string or nil) has no
  mountable root instance — pass it through unchanged (no unmount arm,
  consistent with such a view having nothing to tear down)."
  [unmount-sentinel id frame-id annotated]
  (cond
    (or (nil? annotated) (nil? (.-type ^js annotated)))
    annotated

    (void-dom-root? annotated)
    ;; Void root — sentinel rides as a SIBLING inside a Fragment so the
    ;; user's void element receives NO children. The annotated root is
    ;; forwarded unchanged (source-coord / data-rf-view attrs intact).
    (let [sentinel-el (React/createElement unmount-sentinel
                                           #js {:viewId id :frame frame-id})]
      (React/createElement (.-Fragment React) nil annotated sentinel-el))

    :else
    ;; Non-void root — append the sentinel as a CHILD (no Fragment wrap,
    ;; so layout-critical positioning / flexbox / grid / :nth-child stay
    ;; intact).
    (let [sentinel-el (React/createElement unmount-sentinel
                                           #js {:viewId id :frame frame-id})
          existing    (some-> ^js annotated .-props .-children)
          ;; cloneElement's variadic children REPLACE the original
          ;; `props.children`, so the original children must be carried
          ;; forward explicitly. `existing` is nil (no children), a single
          ;; child, or a JS array — normalise to a flat arg list with the
          ;; sentinel appended last.
          children    (cond
                        (nil? existing)   #js [sentinel-el]
                        (array? existing) (.concat existing #js [sentinel-el])
                        :else             #js [existing sentinel-el])]
      (.apply React/cloneElement nil
              (.concat #js [annotated nil] children)))))

(defn make-wrap-view
  "Return a `wrap-view` fn parameterised on the substrate's per-adapter
  `warn-fn` (typically built via `make-warn-non-dom-root-fn`). The
  returned fn has the standard 3-arg shape `(id metadata user-fn) ->
  wrapped-user-fn` and produces a function component that injects
  both `data-rf2-source-coord` (Spec 006 §Source-coord annotation) and
  `data-rf-view` (Spec 006 §View tagging contract) on the rendered
  root DOM element, AND appends a no-DOM unmount-sentinel child so the
  view instance fires `:rf.view/unmounted` on teardown (rf2-te71r —
  React-hook parity for the phase-A reaction-dispose unmount hook), all
  when `interop/debug-enabled?` is true. Production builds elide via
  `interop/debug-enabled?` per Spec 009 §Production builds.

  The sentinel is appended as a CHILD (via `cloneElement`) rather than
  hooks called inline in the wrapped fn: the wrapped fn is also INVOKED
  HEADLESS (`((rf/view id))` in the render-trace tests), where calling
  React hooks throws. Building the sentinel as an element defers its hook
  execution to a real React render — the same safety the after-render
  sentinel relies on. Appending preserves the root's `type` / `key` /
  props, so the source-coord + view-id annotation contract is unchanged."
  [warn-fn]
  (let [unmount-sentinel (make-unmount-sentinel)]
    (fn wrap-view [id metadata user-fn]
      (if interop/debug-enabled?
        (let [coord-attr (format-source-coord id metadata)
              view-attr  (format-view-id id)
              wrapped    (fn wrapped-user-fn [& args]
                           ;; rf2-te71r: resolve the frame in-render (the substrate-
                           ;; portable React-context read works inside this wrapped fn's
                           ;; render). The sentinel's cleanup runs OUTSIDE render where
                           ;; the read would be wrong, so the frame is threaded as a prop
                           ;; and the sentinel stashes it in a ref. On a headless direct
                           ;; invocation this read falls through the dynamic-var /
                           ;; :rf/default chain — harmless; the sentinel ELEMENT is built
                           ;; but its hooks only run if React actually renders it.
                           (let [frame-id  (adapter-context/function-component-current-frame)
                                 out       (apply user-fn args)
                                 annotated (inject-source-coord-attr warn-fn id coord-attr
                                                                     view-attr out)]
                             (append-unmount-sentinel unmount-sentinel id frame-id annotated)))]
          ;; rf2-fa4ly: stamp the React `displayName` to the registered view-id
          ;; so React DevTools shows `<:cart/total-line>` in the component tree
          ;; rather than the CLJS-munged fn name or an anonymous wrapper. The
          ;; assignment sits inside the `interop/debug-enabled?` arm so the
          ;; string-literal id `(str id)` and the assignment itself elide in
          ;; production builds.
          (set! (.-displayName ^js wrapped) (str id))
          wrapped)
        user-fn))))

(defn install-clear-warn-once-step!
  "Wire `clear-fn` into the chained `:adapter/clear-warn-once-caches!`
  late-bind hook. The hook is chained — each adapter and re-frame.views
  contribute a clear-step; `make-reset-runtime-fixture` invokes the top of
  the chain and every contributor's reset runs.

  Delegates to the canonical governance chokepoint
  `late-bind/register-warn-once-clear-fn!` (rf2-z79p8) so the cache is
  BOTH chained AND enrolled in the warn-once-clear governance registry the
  governance assertion checks. Callers don't need to know the chain key.

  Two arities:
    [clear-fn]            — enrol with a default label and no arm/armed?
                            probes (the empirical arm/fire assertion skips
                            it; the source-enumeration assertion still
                            covers it).
    [clear-fn governance] — pass `{:label :arm :armed?}` so the empirical
                            governance assertion can arm the cache, fire
                            the chain, and prove the cache was wiped. The
                            React-hook spine threads its `warn-cache` atom
                            in via this arity."
  ([clear-fn]
   (install-clear-warn-once-step! clear-fn {:label :adapter/warned-non-dom-roots}))
  ([clear-fn governance]
   (late-bind/register-warn-once-clear-fn!
     (assoc governance :clear-fn clear-fn))))

;; ---- subscription hook ----------------------------------------------------
;;
;; `use-subscribe` is the substrate-idiomatic hook surface for reading
;; a sub. It wraps React.useSyncExternalStore so updates are scheduled
;; by React's concurrent renderer rather than a per-component scheduler.
;;
;; The hook:
;;   1. Resolves the active frame via use-context (Decision 2).
;;   2. Calls re-frame.subs/subscribe to build/cache the reaction.
;;   3. Wires useSyncExternalStore — the snapshot is the deref of the
;;      reaction; subscribe is add-watch on the underlying container.
;;   4. On unmount the watch is removed and the sub's ref-count
;;      decrements; ref-count → 0 disposes synchronously
;;      (per Spec 006 §reference-counting-and-disposal, rf2-cmfln).
;;
;; Hook fns (`use-memo`, `use-callback`, `use-context`) differ between
;; substrates by their deps-array convention — UIx accepts CLJS vectors;
;; some React wrappers want JS arrays. The factory below
;; takes the hook fns as args so each adapter can supply the right pair.
;; The hook fns supplied MUST already be the "wants-JS-array" variants
;; for substrates that need them; the
;; spine passes the deps as a JS array always.

;; ---- the hook-scoped provisional hand-off (rf2-2rtt6.25) -------------------
;;
;; THE PROBLEM IT DELETES. A React render and the commit that owns it are two
;; moments. `use-subscribe-2`'s render phase must read a snapshot, and it used
;; to do so with a BALANCED round trip — subscribe, deref, unsubscribe — so a
;; render that never commits retained nothing (rf2-es09qq). On a query with no
;; live cache entry that round trip is `0 → 1 → 0`, and `1 → 0` is the
;; disposal edge: `re-frame.subs.cache/unsubscribe!` evicts in-tick, no grace
;; period. Microseconds later the commit-owned `subscribe-fn` misses that same
;; cache and BUILDS THE REACTION AGAIN. Every cold subscription read on this
;; spine therefore constructed two reactions and ran the user's sub body
;; twice — for a layer-2+ sub, walking the whole `:<-` input chain twice —
;; measured exactly at `bodyRuns = 2.00N` against Reagent's `1.00N`
;; (rf2-2rtt6.12) and priced at ≥ 20% of the mount red-zone in every round at
;; layers 1, 2 and 3 (rf2-2rtt6.15,
;; docs/design/hicasso/studio/coldmount-double-build-priced.md).
;;
;; THE SHAPE, RULED (rf2-2rtt6.14, ADOPT). The render phase stops balancing
;; in-render. It subscribes and keeps its +1 in a ONE-SHOT ESCROW TOKEN, so
;; the entry is still live when the commit arrives; `subscribe-fn`'s durable
;; subscribe is then a cache HIT and the committed reaction is `identical?`
;; the one the render built — ADOPTION, not a rebuild — after which the token
;; is released, `2 → 1`. One construction per cold read WHEN THE COMMIT
;; ARRIVES INSIDE THE HORIZON — which, since rf2-2rtt6.71 moved that horizon
;; out to `setTimeout 4`, it does on the shipping mount path too. Read "AND ON
;; THE SHIPPING MOUNT PATH IT NOW WINS — BY A MARGIN" below before reading any
;; performance claim into the rest of this comment: the margin is measured,
;; not guaranteed.
;;
;; WHAT IT IS NOT. It is NOT a ref-count-0 cache tenancy. The token is an
;; ordinary reference held by an ordinary owner; the cache never holds a
;; zero-ref entry, `unsubscribe!`'s 1 → 0 in-tick dispose is untouched, and no
;; entry gains a state machine. Reagent, reagent-slim and plain-atom are
;; byte-identical — they never reach this code.
;;
;; WHY IT CANNOT LEAK. Four independent reasons, none of them a promise about
;; React:
;;
;;   * THE REAP IS SCHEDULED BEFORE THE ACQUIRING EXPRESSION RETURNS. Every
;;     token is queued and a host MACROTASK drain armed inside `escrow!`
;;     itself, so an abandoned render, a throwing sub body, an unmount before
;;     commit and a never-retried Suspense attempt are all reaped ≤ 1
;;     macrotask later. React is given no opportunity to cancel it because
;;     React is never asked.
;;   * ONE-SHOT. `spent?` (slot 3) is flipped before the decrement, so
;;     adoption and the drain cannot both release the same token.
;;   * REACTION-GUARDED. The release goes through
;;     `subs/unsubscribe-if-reaction`, which decrements only while the slot
;;     still holds the token's reaction. Hot reload, `clear-sub-cache!` and
;;     `destroy-frame!` evict slots out from under live holders; that eviction
;;     took the +1 with it, so a stale release must no-op rather than
;;     underflow a successor entry rebuilt under the same key.
;;   * ONE OUTSTANDING TOKEN PER HOOK. The factory releases the ref's previous
;;     token AFTER taking its own (subscribe-then-release, so the count never
;;     crosses the disposal edge), which bounds a re-rendering hook at one
;;     escrowed reference no matter how many times React re-runs the factory.
;;
;; AND WHY IT NEED NOT WIN. If the drain fires before React commits, the entry
;; disposes and the commit rebuilds — exactly the pre-hand-off behaviour.
;; Correctness never depends on the ordering; only the saving does.
;;
;; AND ON THE SHIPPING MOUNT PATH IT NOW WINS — BY A MARGIN (rf2-2rtt6.71,
;; ruling (a); measured by rf2-2rtt6.25's merged-PR audit of #7305).
;; `make-render` mounts with a bare `createRoot(…).render(…)`, and on that
;; schedule it is the reaper's DELAY that decides the outcome. A `setTimeout 0`
;; armed during the render runs BEFORE React gets back to flushing the passive
;; effect that installs the `useSyncExternalStore` subscription — so the token
;; was reaped, the entry disposed on the ordinary 1 → 0 edge, and the commit
;; missed and rebuilt: `bodyRuns` 2.00N, the term this was adopted to delete,
;; paid on every consumer mount. That is the whole reason the horizon is 4 ms
;; and not 0. It is not 32 either: 4 is the SHORTEST probed delay reading
;; 1.00N at N = 1 and at N = 300 alike, so the adoption is realised without
;; holding an abandoned render's graph a millisecond longer than winning takes.
;;
;; WHAT THE MARGIN IS, AND WHAT IT IS NOT. React documents no maximum
;; render-to-subscribe interval, so 4 ms cannot be sized against a contract —
;; it is the measured distance on React 19 today, and a future scheduling
;; change can silently reintroduce 2.00N. The claim here is therefore NARROW:
;; one build on the instrument page the ruling measured, with the two-build
;; rebuild as the safe fallback, and adoption stays a best-effort optimisation
;; that no caller may rely on.
;;
;; AND THE BROWSER SUITE IS NOT THE WITNESS FOR IT (rf2-2rtt6.71 implementation
;; sweep). `assert-use-subscribe-public-mount-schedule-rebuilds` mounts through
;; the adapter `:render` slot with no `act` and no `flushSync`, and it still
;; reads TWO builds at this horizon — because the gap that page puts between
;; the render and React's passive flush measures > 128 ms and <= 256 ms, two to
;; three orders of magnitude past anything shippable. Disabling the reaper, or
;; raising it to 256 ms, flips it; 0/8/16/32/64 do not, per-token arming does
;; not. So that row pins the DEFECT the `setTimeout 0` era had in every
;; environment, and it is NOT evidence about a consumer mount at this horizon
;; in either direction. A witness on a representative page is owed; until it
;; exists the ruling's own probe is the only measurement of the win.
;;
;; WHY NO MORE MACHINERY THAN A NUMBER. No new hook and no new public
;; mechanism hardens the margin, because Spec 006's "correctness MUST NOT
;; depend on the reaper losing the race" is untouched — and that is precisely
;; what makes a 4 ms heuristic acceptable. The rest of the mechanism is
;; unchanged and unchanged deliberately: identity adoption, the one-shot
;; release, the identity guard, the bounded horizon, the cascade at the
;; horizon, SSR and StrictMode are all exactly as designed and asserted. Every
;; other probed primitive was rejected on the measurement, not on taste:
;; `setTimeout 32` also reads 1.00N but holds abandoned graphs eight times
;; longer for nothing; `requestAnimationFrame` reads 1.00N at N = 1 and 2.00N
;; at N = 300; a `MessageChannel` post reads 2.00N, because React posts its own
;; message later and the queue is FIFO. A non-timer signal that is a CONTRACT
;; rather than a margin would supersede all of them; React exposes none today
;; (rf2-2rtt6.71 option (c), left open as a research direction).
;;
;; THE ONE CONTRACT-VISIBLE CHANGE, blessed by the ruling: a render abandoned
;; before commit leaves ≤ 1 ref-count until the horizon rather than 0
;; immediately, and the ruling moved that horizon from one `setTimeout 0` task
;; out to ~4 ms. The zero-leak property is unchanged; its zero-POINT is the
;; horizon. Spec 006 §Render-phase provisional acquisition and commit adoption
;; carries the wording, and every witness that crosses the horizon settles
;; PAST it deliberately rather than on a bare `setTimeout 0`.

(defn- release-provisional!
  "Release one escrow token — ONE-SHOT and REACTION-GUARDED, per the section
  comment above. `token` is `#js [reaction frame-kw query-v spent?]`.

  The `spent?` flip precedes the decrement, so the two racers for a token —
  the commit that adopts it and the macrotask drain that reaps it — cannot
  both decrement: on the single-threaded JS event loop a read-then-write with
  no intervening suspension point IS the compare-and-set. Idempotent by the
  same flag. Returns nil."
  [token]
  (when (and (some? token) (not (aget token 3)))
    (aset token 3 true)
    (subs/unsubscribe-if-reaction (aget token 1) (aget token 2) (aget token 0)))
  nil)

(def ^:private no-provisional
  "Miss sentinel for `provisional-snapshot` — a distinct object identity, so a
  live escrow whose reaction derefs to `nil`/`false` is still a HIT and is not
  confused with having no escrow at all."
  (js-obj))

(defn- provisional-snapshot
  "The LIVE value of the reaction an UNSPENT escrow token is holding, or
  `no-provisional` when there is nothing live to read.

  This is what keeps `use-subscribe`'s pre-commit snapshot honest without
  retaining anything (rf2-2rtt6.13, the audit of PR #7304). The render-phase
  memo returns a VALUE, so between a render and the commit that owns it the
  hook has no reaction of its own — and a value frozen at render time compares
  equal to itself forever, which makes React's pre-commit store-consistency
  check a no-op BY CONSTRUCTION and lets a write that landed in that gap commit
  (and paint) stale. Measured: on a concurrent lane the first commit showed the
  render's value while app-db had already moved.

  But the token ALREADY holds the reaction, and holds it LIVE: that +1 is the
  whole point of the hand-off (rf2-2rtt6.25), and it is what makes the entry
  still tenanted when the commit arrives to adopt it. So the pre-commit read has
  a live source available for free. Nothing new is retained — the retention is
  the token's, it predates this fn, and it ends at adoption or at the macrotask
  horizon, never at the component's lifetime. The memo slot and `get-snap`'s
  closure still hold a value and no handle, which is exactly what rf2-2rtt6.13
  bought.

  `spent?` (slot 3) is load-bearing, not defensive. The reaper flips it and
  decrements WITHOUT clearing the holder's ref, so a spent token can be pointing
  at a reaction whose last reference has just gone. Reading only while unspent is
  what keeps the frozen-value fallback as the answer in exactly the cases where
  there is no live reaction to prefer to it.

  What `spent?` does NOT cover, stated rather than glossed: an eviction that
  takes the entry out from under a still-unspent token — hot reload,
  `clear-sub-cache!`, `destroy-frame!` — landing INSIDE a single render→commit
  gap. The release already no-ops for that case (it is identity-guarded), and a
  deref here would read a pull-based recompute against current sources, so the
  VALUE stays right; only a sub body re-registered within that same gap could
  differ. The lifetime-scale version of that hazard is what `committed-ref`
  exists to close, and it is closed."
  [token]
  (if (and (some? token) (not (aget token 3)))
    @(aget token 0)
    no-provisional))

(def ^:private provisional-horizon-ms
  "The reap horizon in milliseconds: how long an UNADOPTED provisional
  reference lives before the macrotask drain releases it. RULED 4 by
  rf2-2rtt6.71. `make-provisional-escrow` below carries the reasoning, the
  measurement and the margin-not-contract caveat; this is the only place the
  number is written down."
  4)

(defn- make-provisional-escrow
  "Build one spine's provisional-escrow acquirer: `(escrow! reaction frame-kw
  query-v)` mints the token, queues it, arms the reaper, and answers the token.

  THE REAPER IS A MACROTASK, AND ITS HORIZON IS `setTimeout 4`
  (`provisional-horizon-ms`, ruled by rf2-2rtt6.71). Those are two separate
  constraints and both are load-bearing.

  A MACROTASK IS NECESSARY. React 19 installs `useSyncExternalStore`'s
  subscription as a PASSIVE effect, and passive effects are flushed from the
  React scheduler's own task. A microtask reaper (`queueMicrotask`, a resolved
  promise's `.then`) drains at the end of the CURRENT task — before that flush
  — so every token would be reaped before the commit that was meant to adopt
  it.

  AND IT IS NOT SUFFICIENT: WHICH macrotask decides the outcome (rf2-2rtt6.25,
  audit of #7305 — the measurement; rf2-2rtt6.71 — the ruling that acts on it).
  Measured through the public adapter render slot with no `act` and no
  `flushSync`, at N = 1 and at N = 300 boundaries, three trials each: a
  `setTimeout 0` armed inside the render fires BEFORE React's passive flush, so
  the token is reaped and the commit rebuilds (`bodyRuns` 2.00N); `setTimeout
  4` and `setTimeout 32` both read 1.00N at both sizes; `requestAnimationFrame`
  wins at one boundary and loses at three hundred; a `MessageChannel` post
  loses, because React posts its own message after ours and the queue is FIFO.
  The horizon is the SHORTEST winning delay — 4, not 32 — so an abandoned
  render, a Suspense retry and a never-shown tree hold their reactive graphs no
  longer than the adoption actually needs.

  A MARGIN, NOT A CONTRACT. React documents no maximum render-to-subscribe
  interval, so no delay can be sized against a guarantee: 4 ms is the measured
  distance on React 19 today, and a future scheduling change can silently
  reintroduce the double build. The claim therefore stays narrow — one build on
  the tested shipping schedule, with the two-build rebuild as the safe fallback
  — and the standing tripwire is an ASSERTION rather than this paragraph:
  `assert-use-subscribe-public-mount-schedule-rebuilds` pins `identical?` and
  ONE construction on the public schedule, so React drifting reds the suite and
  reopens the question with evidence.

  This is a PERFORMANCE ordering, never a correctness one: an early drain
  costs a rebuild, nothing more (see the section comment).

  ONE TIMER PER BURST, armed on the empty → non-empty edge, so a page mounting
  three hundred boundaries in one render pass arms one timer and not three
  hundred. A burst therefore lasts until the host runs a task, and the queue
  holds one four-slot array per read taken since — bounded by the work the
  same burst already did, and emptied whole at the horizon. The drain splices
  before releasing, so a token minted while it runs belongs to the next burst;
  and each release is isolated, so one throwing sub-body teardown cannot
  strand the rest of the batch."
  []
  (let [pending    #js []
        scheduled? (volatile! false)
        drain!     (fn drain-provisionals! []
                     (vreset! scheduled? false)
                     (let [batch (.splice pending 0 (.-length pending))
                           n     (.-length batch)]
                       (dotimes [i n]
                         (try (release-provisional! (aget batch i))
                              (catch :default _ nil)))))]
    (fn escrow! [reaction frame-kw query-v]
      (let [token #js [reaction frame-kw query-v false]]
        (.push pending token)
        (when-not @scheduled?
          (vreset! scheduled? true)
          (js/setTimeout drain! provisional-horizon-ms))
        token))))

(defn make-react-spine
  "Build the per-substrate hook-shaped surfaces given the substrate's
  config:

      :substrate-name  — string used in warn-non-dom-root text (\"UIx\",
                         …)
      :gensym-prefix-sub
      :gensym-prefix-derived
      :gensym-prefix-use-sub
                       — gensym prefix strings per surface
      :use-memo        — (fn [thunk js-deps]) returning the memoised
                         value
      :use-callback    — (fn [thunk js-deps]) returning the memoised
                         fn
      :use-context     — (fn [context]) returning the context value

  Returns a map of surfaces:

      {:make-state-container       …
       :read-container             …
       :replace-container!         …
       :subscribe-container        …
       :make-derived-value         …
       :render                     …
       :render-to-string           …
       :dispose-adapter!           …
       :set-hiccup-emitter!        …
       :use-current-frame          …
       :use-subscribe              …
       :flush-views!               …
       :flush-render!              …
       :wrap-view                  …
       :clear-warned-non-dom-roots! …}

  Note (rf2-z7hfp): the spine no longer produces `:frame-provider` or
  `:register-context-provider`. The user-facing frame-provider is a
  NATIVE substrate component (`defui` / `defnc`) defined in the adapter
  ns above where each substrate's element macro marshals props; the
  adapter passes that component into `make-react-adapter` as
  `:frame-provider`, and the spine wires it into the
  `:register-context-provider` substrate slot. The shared substrate-
  agnostic core is `build-frame-provider-element`, which the native
  component shell calls with clean props."
  [{:keys [substrate-name
           gensym-prefix-sub
           gensym-prefix-derived
           gensym-prefix-use-sub
           use-memo
           use-callback
           use-context]}]
  (let [warn-cache         (make-warn-once-cache)
        clear-warned       (make-clear-warned-fn warn-cache)
        warn-fn            (make-warn-non-dom-root-fn warn-cache substrate-name)
        emitter-cell       (make-hiccup-emitter-cell)
        active-roots-cell  (make-active-roots-cell)
        ;; rf2-334d9: after-render queue + sentinel component + the
        ;; routed-hook impl. The adapter publishes the hook by passing
        ;; `:after-render-hook` to `substrate-adapter/route-hook!`.
        after-render-queue-cell       (make-after-render-queue-cell)
        after-render-set-tick-ref     (make-after-render-set-tick-ref)
        ;; rf2-t0x90: holds the lazily-mounted singleton driver root so
        ;; native-mount apps still get post-commit after-render timing.
        after-render-driver-root-cell (make-after-render-driver-root-cell)
        after-render-sentinel      (make-after-render-sentinel
                                     after-render-queue-cell
                                     after-render-set-tick-ref)
        after-render-hook          (make-after-render-hook
                                     after-render-queue-cell
                                     after-render-set-tick-ref
                                     after-render-sentinel
                                     after-render-driver-root-cell)
        subscribe-cont     (make-subscribe-container gensym-prefix-sub)
        ;; rf2-i21f5: one epoch scheduler per adapter, shared by
        ;; `replace-container!` and every `make-derived-value`, so a
        ;; multi-input derived value recomputes glitch-free and notifies
        ;; once per coherent app-db epoch (Spec 006 §Invalidation
        ;; algorithm). See the epoch-scheduler section above.
        scheduler          (make-scheduler)
        ;; rf2-2rtt6.25: one provisional-escrow acquirer per spine — the
        ;; render-phase +1 `use-subscribe-2` hands to the commit, and the
        ;; single macrotask reaper that guarantees every token dies whether or
        ;; not that commit ever arrives. See the section comment above
        ;; `make-provisional-escrow`.
        escrow!            (make-provisional-escrow)
        replace-cont!      (make-replace-container-fn scheduler)
        make-derived       (make-derived-value-fn gensym-prefix-derived scheduler)
        ;; Precompute the `use-subscribe` watch-key keyword namespace
        ;; once (outside the render hot path). Each `subscribe-fn`
        ;; invocation mints a UNIQUE per-call suffix under this namespace
        ;; (see the subscribe-fn closure below) so sibling subscribers to
        ;; the same cached reaction never collide on the same `add-watch`
        ;; key. The closure runs once per reaction-identity change (it is
        ;; `use-callback`-memoized on `[reaction]`), so the per-call
        ;; keyword is not paid per render.
        use-sub-watch-ns   (let [s gensym-prefix-use-sub
                                 n (count s)]
                             ;; Strip the trailing "-" the gensym prefix
                             ;; carried so the keyword namespace reads
                             ;; cleanly (`:rf-uix-use-sub/<hash>`).
                             (if (and (pos? n) (= "-" (subs s (dec n))))
                               (subs s 0 (dec n))
                               s))
        wrap-view-fn       (make-wrap-view warn-fn)
        render-fn          (make-render active-roots-cell after-render-sentinel)
        dispose-fn         (make-dispose-adapter!
                             {:active-roots-cell active-roots-cell
                              :warn-cache        warn-cache
                              :emitter-cell      emitter-cell
                              ;; rf2-t0x90: release the singleton
                              ;; after-render driver root + clear its
                              ;; set-tick slot so a fresh init! re-arms.
                              :after-render-driver-root-cell after-render-driver-root-cell
                              :after-render-set-tick-ref     after-render-set-tick-ref})
        use-current-frame
        (fn use-current-frame []
          (use-context adapter-context/frame-context))
        ;; Two-arity body extracted so the 1-arg arm can call into it
        ;; without a self-reference on the let-bound `use-subscribe`
        ;; (CLJS let-bound fns cannot name themselves).
        ;;
        ;; ---- stable-key derivation (rf2-mwft2) -------------------------------
        ;;
        ;; React's deps comparison is `Object.is` (≈ `===`). Both
        ;; `frame-kw` (a CLJS keyword) and `query-v` (a CLJS persistent
        ;; vector) are value-equal across renders for the same logical
        ;; subscribe call but produce *fresh JS objects* per render —
        ;; keyword literals compile to `new cljs.core.Keyword(...)` in
        ;; the render body, vector literals to `new
        ;; cljs.core.PersistentVector(...)`, so neither survives the
        ;; render boundary by identity even though both survive by `=`.
        ;; The deps array `#js [frame-kw query-v]` therefore mismatches
        ;; every render and useMemo / useCallback / useEffect re-fire
        ;; their factories — driving cache-hit `subs/subscribe`,
        ;; watch add/remove, and cache-entry ref-count churn even when
        ;; the subscription is unchanged.
        ;;
        ;; Fix: hold the previous `[frame-kw query-v]` tuple in a
        ;; `useRef`. Each render we compare the incoming tuple to
        ;; `ref.current` by CLJS `=`. If equal, we read the stored
        ;; tuple's components back, returning JS-ref-stable elements
        ;; for the deps array. If not equal, we update the ref to the
        ;; new tuple. Writing to a ref during render is sanctioned by
        ;; React for exactly this memo-by-value pattern — the write is
        ;; idempotent given identical inputs and never mutates after a
        ;; commit.
        ;;
        ;; The bead (rf2-mwft2) flagged `(hash [frame-kw query-v])` as
        ;; the simpler candidate. We chose `useRef` + `=` over `hash`
        ;; because Murmur3 collisions, however rare, would have
        ;; useMemo return the wrong reaction for a colliding (frame,
        ;; query) pair — a silent correctness bug. The `useRef` path
        ;; has no false-positive equality and stays cheap (one extra
        ;; ref, one allocation-free `=` compare per render).
        use-subscribe-2
        (fn use-subscribe-2 [frame-kw query-v]
          (let [key-ref (React/useRef nil)
                ;; Holds the DURABLE committed reaction (rf2-sqhjtu). The
                ;; render-phase `use-memo` below reads a snapshot without
                ;; retaining a handle, and (since rf2-2rtt6.25) hands its
                ;; ref-count on to the commit rather than balancing it away, so
                ;; on the cold path `subscribe-fn` ADOPTS the same reaction. The
                ;; committed and render-phase reactions can still be DIFFERENT
                ;; objects — the reaper may have beaten the commit, hot reload
                ;; may have rebuilt the slot — so the rule below is unchanged and
                ;; not conditional on the hand-off winning.
                ;;
                ;; `get-snap` must never read a disposed handle. A disposed
                ;; reaction still recomputes on `-deref` (pull-based), so
                ;; deref-ing it LOOKS correct for ordinary app-db updates —
                ;; but it holds no source watches and still closes over the
                ;; OLD sub body. On sub re-registration / hot-reload the cache
                ;; rebuilds the committed reaction with the v2 body while a
                ;; `get-snap` pinned to the disposed v1 handle keeps rendering
                ;; v1 output. React's `useSyncExternalStore` contract requires
                ;; `getSnapshot` to read a stable, LIVE source — so `get-snap`
                ;; reads the committed reaction stored here once `subscribe-fn`
                ;; has run (post-commit), and before that reads the reaction the
                ;; hook's unspent ESCROW TOKEN is holding, which is live by
                ;; construction (rf2-2rtt6.25's +1 is what keeps it tenanted).
                ;; The render-phase SNAPSHOT VALUE (`render-snapshot` below) is
                ;; the last resort, for when neither is live. Since rf2-2rtt6.13
                ;; nothing here holds a handle for the component's lifetime, so
                ;; a disposed reaction is not merely un-preferred — it is
                ;; unreachable.
                ;;
                ;; rf2-naz09e: the ref stores the committed reaction KEY-TAGGED
                ;; as `#js [stable-key committed]` (see `subscribe-fn` and
                ;; `get-snap` below) so a query-v/frame change on a mounted
                ;; component can't serve the PREVIOUS target's value for the
                ;; change-commit — `get-snap` reads the tag only while it
                ;; matches the current render's key.
                committed-ref (React/useRef nil)
                ;; rf2-2rtt6.25: the hook's single outstanding ESCROW TOKEN,
                ;; key-tagged as `#js [stable-key token]` — the render-phase
                ;; +1 that keeps the freshly-built reaction alive until the
                ;; commit adopts it. nil whenever this hook holds none (before
                ;; the first render, and from the moment `subscribe-fn`
                ;; adopts). Key-tagged for the same reason `committed-ref` is:
                ;; across a query-v / frame change the OLD `subscribe-fn`'s
                ;; cleanup must not release the NEW target's token.
                provisional-ref (React/useRef nil)
                stable-key
                (let [prev (.-current key-ref)
                      new-key #js [frame-kw query-v]]
                  (if (and prev
                           (= (aget prev 0) frame-kw)
                           (= (aget prev 1) query-v))
                    prev
                    (do (set! (.-current key-ref) new-key)
                        new-key)))
                ;; Destructure the stable tuple's components so the
                ;; downstream call sites see JS-ref-stable values for
                ;; same-by-= subsequent renders.
                stable-frame-kw (aget stable-key 0)
                stable-query-v  (aget stable-key 1)
                ;; ---- commit-deferred ref-count acquisition (rf2-es09qq) -------
                ;;
                ;; THE INVARIANT: a render that never commits MUST NOT retain a
                ;; sub-cache ref-count. The earlier design (rf2-879fe ledger) put
                ;; the durable `subs/subscribe` (+1) in the render-phase `useMemo`
                ;; factory and reclaimed it from commit-owned effects. That is
                ;; unsound for a FIRST-MOUNT render abandoned before commit
                ;; (Suspense / concurrent interrupt): React discards the whole
                ;; fiber — its `useRef` ledger AND its never-run effects — so the
                ;; render-phase +1 is pinned in the GLOBAL sub-cache forever with
                ;; no owning component. The ledger only ever healed a render whose
                ;; fiber LATER committed; a discarded first-mount fiber never gets
                ;; that reconcile pass.
                ;;
                ;; FIX — the durable acquire/release lives ONLY in commit-owned
                ;; hooks (`useSyncExternalStore`'s subscribe callback, run after
                ;; commit; its cleanup run on unmount / subscribe-identity change
                ;; / teardown). React NEVER calls that callback for a render that
                ;; doesn't commit, so an abandoned render acquires NOTHING — the
                ;; leak is gone BY CONSTRUCTION, independent of fiber discard.
                ;;
                ;; The render phase still needs a SNAPSHOT to hand
                ;; `useSyncExternalStore`, and it takes one of two shapes
                ;; depending on whether a durable reference already backs this
                ;; (frame, query) — the ONLY thing that decides whether the
                ;; render can reach the 1 → 0 disposal edge.
                ;;
                ;; COMMITTED STEADY STATE — `committed-ref` already holds THIS
                ;; key's reaction, so `subscribe-fn` is holding a durable +1. A
                ;; BALANCED, net-zero round trip (`subs/subscribe`, deref,
                ;; `subs/unsubscribe`) bumps 1 → 2 and drops back to 1, never
                ;; crossing the disposal edge, and the render retains nothing.
                ;; React may DISCARD a memo and re-run this factory on unchanged
                ;; deps whenever it likes (rf2-8u8tx.2) — each re-run is its own
                ;; balanced round trip, so no number of discards can move the
                ;; ref-count.
                ;;
                ;; NO DURABLE BACKING — the first mount before its commit, a
                ;; never-committed render, and the first render after a key
                ;; change. Here the balanced round trip WOULD go 0 → 1 → 0 and
                ;; destroy the reaction it just built, so instead the +1 is kept
                ;; in a hook-scoped ESCROW TOKEN and handed to the commit
                ;; (rf2-2rtt6.25 — see the section comment above
                ;; `make-provisional-escrow`, and Spec 006 §Render-phase
                ;; provisional acquisition and commit adoption). `subscribe-fn`
                ;; then HITS the cache, adopts the identical? reaction, and
                ;; releases the token 2 → 1; a render that never commits has its
                ;; token reaped by the macrotask drain armed inside `escrow!`.
                ;; Since rf2-2rtt6.71 moved the horizon to `setTimeout 4` the
                ;; commit arrives first on the PUBLIC mount schedule too — by a
                ;; measured margin, never by a React guarantee. Nothing in this
                ;; branch depends on which arrives.
                ;;
                ;; The two prior leak triggers stay closed, by the same
                ;; construction that closed them:
                ;;   • rf2-879fe / rf2-es09qq (abandoned before commit) — the
                ;;     render's reference is owned by the reaper, not by the
                ;;     fiber React discarded, so it is released whether or not
                ;;     any effect ever runs. The zero-leak property is
                ;;     unchanged; the zero-POINT is one macrotask later, and
                ;;     that is the single contract-visible change the
                ;;     rf2-2rtt6.14 ruling blesses.
                ;;   • rf2-8u8tx.2 (memo perf-discard) — committed, the round
                ;;     trip is balanced; uncommitted, the factory releases the
                ;;     ref's PREVIOUS token after taking its own, so a hook
                ;;     holds at most one escrowed reference however many times
                ;;     React re-runs it. No climb on either path.
                ;; Per Spec 006 §Reference counting and disposal (rf2-cmfln).
                ;;
                ;; ---- the memo yields the VALUE, never the handle (rf2-2rtt6.13) ----
                ;;
                ;; Returning the reaction HANDLE retained it for the component's
                ;; lifetime — `use-memo`'s hook slot held it and `get-snap`'s
                ;; closure held it — at a measured 769 B `[765–793]` / 23.0
                ;; objects per read, 22% of every UIx subscription read
                ;; (docs/design/hicasso/studio/uix-spine-per-read-
                ;; decomposition.md; instrument landed 24e8822d7f). And on the
                ;; cold path that handle used to be DEAD before the factory
                ;; returned. So deref while the reaction is live and return the
                ;; VALUE; no HOOK SLOT holds the reaction once the factory
                ;; returns. The escrow above does not walk this back. Its token
                ;; does carry the reaction — that is how the release
                ;; identity-guards itself — but the token is owned by the reaper
                ;; and dies at adoption or at the macrotask horizon, so what the
                ;; component retains for its lifetime is still a value and not a
                ;; handle. That distinction is what makes the token safe for
                ;; `get-snap` to READ pre-commit (`provisional-snapshot`) without
                ;; reopening the 769 B this change closed.
                render-snapshot
                (use-memo (fn []
                            (let [stored    (.-current committed-ref)
                                  committed (when (and stored
                                                       (identical? (aget stored 0) stable-key))
                                              (aget stored 1))]
                              (if (some? committed)
                                ;; Durably backed: balanced, net-zero, no escrow
                                ;; to take — the disposal edge is unreachable.
                                (let [r (subs/subscribe stable-query-v {:frame stable-frame-kw})
                                      v (when r @r)]
                                  (subs/unsubscribe stable-frame-kw stable-query-v)
                                  v)
                                ;; Cold: keep the +1 for the commit. The token is
                                ;; queued and its reaper armed INSIDE `escrow!`,
                                ;; before anything below can throw — a sub body
                                ;; that throws on the deref must still leave a
                                ;; reapable reference, never a stranded one.
                                (let [r    (subs/subscribe stable-query-v {:frame stable-frame-kw})
                                      prev (.-current provisional-ref)]
                                  (set! (.-current provisional-ref)
                                        (when (some? r)
                                          #js [stable-key (escrow! r stable-frame-kw stable-query-v)]))
                                  ;; Subscribe-THEN-release: our own +1 is
                                  ;; already taken, so releasing the previous
                                  ;; attempt's token cannot cross 1 → 0 and
                                  ;; dispose the very reaction we are handing on.
                                  (when (some? prev) (release-provisional! (aget prev 1)))
                                  (when (some? r) @r)))))
                          #js [stable-key])
                ;; The store-snapshot fn React calls on every render to
                ;; detect tearing. Pure deref of the LIVE committed reaction.
                ;;
                ;; rf2-sqhjtu: prefer the durable committed reaction stored in
                ;; `committed-ref` by `subscribe-fn` (set post-commit, cleared
                ;; on teardown). `render-snapshot` — the VALUE the render-phase
                ;; round trip read — is only the fallback for the pre-commit
                ;; snapshot: before React has run `subscribe-fn`,
                ;; `committed-ref` is still nil and that value is the only
                ;; thing to read. Once committed, `get-snap` tracks the live
                ;; cached reaction (the one carrying source watches and the
                ;; current sub body), never a disposed first-render handle —
                ;; which since rf2-2rtt6.13 it could not reach anyway. The
                ;; committed reaction's value `=` the render-phase one
                ;; (rf2-cmfln), so the source swap is tear-free.
                ;;
                ;; rf2-2rtt6.13 — the pre-commit read is LIVE, and it is live
                ;; without retaining anything. Three sources, in strict order of
                ;; how current they are:
                ;;
                ;;   1. the COMMITTED reaction, once `subscribe-fn` has published
                ;;      it under this key — the steady state;
                ;;   2. the reaction the hook's UNSPENT ESCROW TOKEN is holding
                ;;      (`provisional-snapshot`) — the render→commit window and
                ;;      the key-change render, the only two moments (1) cannot
                ;;      answer;
                ;;   3. `render-snapshot`, the value the render phase read, when
                ;;      neither of those is live.
                ;;
                ;; (2) is what closes the window the audit of PR #7304 opened.
                ;; The memo returning a VALUE rather than a handle is the whole
                ;; rf2-2rtt6.13 win and is untouched; but a value frozen at
                ;; render time compares equal to itself, so with (3) as the only
                ;; pre-commit answer React's PRE-COMMIT store-consistency check
                ;; could never fire. On a concurrent lane React re-reads every
                ;; store's `getSnapshot` before committing a time-sliced render
                ;; and throws the render away if one moved; a frozen value made
                ;; that check a no-op by construction, so a write landing in the
                ;; gap COMMITTED — and could paint — stale, self-healing only one
                ;; commit later. That is not delayed bookkeeping: a same-commit
                ;; layout effect or ref read sees it, and the ugly instance is a
                ;; panel mounting as a permission drops (rf2-so3io / rf2-anmdr).
                ;; The escrow token already holds that reaction LIVE, so (2) costs
                ;; a ref read and retains nothing new. See `provisional-snapshot`.
                ;;
                ;; On a BLOCKING lane React pushes no pre-commit check at all
                ;; (`0 !== (renderLanes & 127) || pushStoreConsistencyCheck(…)`),
                ;; so there the render's value is the committed value whatever
                ;; `get-snap` says. What still repairs BOTH lanes one commit
                ;; later is React's mount ordering: the `subscribeToStore`
                ;; passive effect is pushed BEFORE the `updateStoreInstance` one
                ;; and passive effects run in push order, so by the second call
                ;; `subscribe-fn` has published the committed reaction and (1)
                ;; answers.
                ;;
                ;; Every source here is `Object.is`-STABLE across back-to-back
                ;; calls — each is a memoised reaction's value or a frozen one —
                ;; which is what React's "the result of getSnapshot should be
                ;; cached" rule requires. The rejected alternative, having
                ;; `get-snap` re-SUBSCRIBE per call, is unsafe for exactly that
                ;; reason: on a miss each call would build a fresh reaction with a
                ;; fresh memo cell, so a collection-returning sub yields a
                ;; non-`Object.is` value every time — React's documented
                ;; infinite-render-loop condition. Reading a reference something
                ;; else already owns cannot build anything, so it cannot reach it.
                ;;
                ;; rf2-naz09e — the committed reaction is stored KEY-TAGGED as
                ;; `#js [stable-key committed]`, and `get-snap` reads it ONLY
                ;; when the stored key is `identical?` the CURRENT render's
                ;; `stable-key`; otherwise it falls back to `render-snapshot`.
                ;; This closes the KEY-CHANGE window. When query-v /
                ;; frame change on a mounted component, `committed-ref`
                ;; transiently still holds the PREVIOUS target's reaction — the
                ;; old `subscribe-fn`'s ref-clearing cleanup is a passive effect
                ;; that runs AFTER this render commits, and the NEW
                ;; `subscribe-fn` that repopulates the ref also runs
                ;; post-commit. Without the key guard `get-snap` would serve the
                ;; OLD target's value for the change-commit — a torn commit (old
                ;; data under the new query args) that any same-commit
                ;; layout-effect / ref read deterministically observes and which
                ;; under a transition lane can paint before the passive-phase
                ;; store-consistency check forces a corrective re-render. The
                ;; `render-snapshot` memo is keyed on `#js [stable-key]`, so on
                ;; a key change it already holds the NEW target's value: the
                ;; fallback serves the NEW value for that very commit, matching
                ;; the Reagent substrate's in-render recompute (no tear) — and
                ;; matching it more closely than before, since Reagent's
                ;; render-phase value is likewise read once, in render. The
                ;; earlier claim — "once committed-ref is populated post-commit,
                ;; get-snap's identity is irrelevant to correctness" — was
                ;; UNTRUE across a key change: committed-ref points at the WRONG
                ;; (old) reaction until the passive cleanup runs.
                ;;
                ;; Deps include `render-snapshot` so the fallback path always
                ;; closes over the CURRENT render's value — both the pre-commit
                ;; first snapshot AND the key-change render — never a stale
                ;; perf-discarded one.
                get-snap
                (use-callback (fn []
                                (let [stored (.-current committed-ref)]
                                  (if (and stored
                                           (identical? (aget stored 0) stable-key))
                                    (let [r (aget stored 1)] (when r @r))
                                    ;; Pre-commit / key-change: prefer the LIVE
                                    ;; reaction the escrow token is holding over
                                    ;; the frozen render value (rf2-2rtt6.13).
                                    ;; Key-tagged for the same reason
                                    ;; `committed-ref` is — across a query-v /
                                    ;; frame change the ref may still hold the
                                    ;; PREVIOUS target's token.
                                    (let [held (.-current provisional-ref)
                                          v    (if (and held
                                                        (identical? (aget held 0) stable-key))
                                                 (provisional-snapshot (aget held 1))
                                                 no-provisional)]
                                      (if (identical? v no-provisional)
                                        render-snapshot
                                        v)))))
                              #js [stable-key render-snapshot])
                ;; The store-subscribe fn — React's COMMIT-OWNED acquire/release
                ;; pair. React calls it (once) only AFTER a commit, passing a
                ;; force-update callback; its returned cleanup runs on unmount,
                ;; on a subscribe-identity change, and on teardown. This is where
                ;; the DURABLE sub-cache ref-count is taken (`subs/subscribe`) and
                ;; released (`subs/unsubscribe`) — never in render — so a render
                ;; abandoned before commit owns nothing beyond the reaper's
                ;; horizon. We re-subscribe by (frame, query) here rather than
                ;; trust a handle carried out of the render phase; since
                ;; rf2-2rtt6.25 that re-subscribe CAN be a cache HIT returning
                ;; the very reaction the render built (the adoption below), and
                ;; since rf2-2rtt6.71 it is one on the public mount schedule as
                ;; well — by a measured margin. Lose that margin and it is an
                ;; honest miss, exactly as it was before the hand-off.
                ;;
                ;; MEMOIZED ON `[stable-key]`, NOT on the render-phase memo
                ;; (rf2-es09qq). Keying on anything derived from the render-phase
                ;; build would change subscribe-fn identity right after the first
                ;; commit — forcing React to release (dispose) and re-acquire the
                ;; durable ref on churn. `stable-key` is identity-stable for a
                ;; fixed (frame, query), so React calls subscribe-fn exactly ONCE
                ;; per subscription target: one durable acquire, one release, no
                ;; churn. The watch is added on the freshly-acquired `committed`
                ;; reaction inside, so it always tracks the live cached reaction.
                subscribe-fn
                (use-callback
                  (fn [on-change]
                    ;; Take the durable committed ref now (post-commit). This is
                    ;; the ONLY place a lasting +1 is acquired. The returned
                    ;; reaction is the live cached one and its value `=` the
                    ;; `render-snapshot` the render phase read.
                    (let [committed (subs/subscribe stable-query-v {:frame stable-frame-kw})]
                      ;; rf2-2rtt6.25 — THE ADOPTION, when it happens. If the
                      ;; render phase's escrowed +1 is still unspent, the entry
                      ;; was live when the subscribe above ran: it HIT, and
                      ;; `committed` is `identical?` the reaction the render
                      ;; built. Release the token now (2 → 1) and clear the ref,
                      ;; so the steady state is exactly what it was before this
                      ;; hand-off existed — one durable reference, owned here,
                      ;; released by the cleanup below. Since rf2-2rtt6.71 that
                      ;; is the measured outcome on the public mount schedule as
                      ;; well as under `act`; should the 4 ms margin ever be lost
                      ;; the reaper spends the token first, this falls to the
                      ;; "missing or already spent" branch below, and the
                      ;; subscribe above was a rebuild. The steady state is
                      ;; identical either way.
                      ;;
                      ;; Guarded on the key tag: across a query-v / frame change
                      ;; the ref may already hold the NEXT target's token, which
                      ;; belongs to the NEXT `subscribe-fn`. Releasing a token
                      ;; this invocation does not own would drop the successor's
                      ;; hand-off on the floor (a correctness no-op — the reaper
                      ;; covers it — but a rebuild). If the token is missing or
                      ;; already spent, the reaper beat us and the subscribe
                      ;; above was an honest miss + rebuild: today's behaviour,
                      ;; no worse.
                      (let [held (.-current provisional-ref)]
                        (when (and (some? held)
                                   (identical? (aget held 0) stable-key))
                          (set! (.-current provisional-ref) nil)
                          (release-provisional! (aget held 1))))
                      ;; rf2-sqhjtu / rf2-naz09e: publish the durable committed
                      ;; reaction KEY-TAGGED with this invocation's `stable-key`
                      ;; so `get-snap` derefs THIS live handle (source watches +
                      ;; current sub body) and never a stale one — but ONLY
                      ;; while the current render's key matches
                      ;; the tag. Set post-commit (here), cleared on teardown
                      ;; below. The key tag is what lets `get-snap` reject a
                      ;; stale committed reaction across a query-v / frame
                      ;; change (the change-commit reads the render-phase handle
                      ;; for the NEW target instead).
                      (set! (.-current committed-ref) #js [stable-key committed])
                      ;; UNIQUE watch key per `subscribe-fn` INVOCATION,
                      ;; closed over by the returned cleanup. The key MUST
                      ;; NOT derive from `(hash reaction)`: subscriptions are
                      ;; cached/deduped by query, so sibling UIx
                      ;; components reading the SAME query share the SAME
                      ;; cached reaction. A hash-of-reaction key would be
                      ;; IDENTICAL across those siblings, and `add-watch`
                      ;; replaces an existing watcher with the same key — so
                      ;; the last-mounted sibling's `on-change` would silently
                      ;; overwrite every earlier sibling's `useSyncExternalStore`
                      ;; callback, leaving the earlier ones rendering stale UI
                      ;; until an unrelated parent render refreshed them.
                      ;; `subscribe-fn` is `use-callback`-memoized on
                      ;; `[stable-key]`, so React calls it once per subscription
                      ;; target (NOT per render); a fresh keyword per call is
                      ;; cheap and collision-free.
                      (let [k (keyword use-sub-watch-ns (str (gensym "watch-")))]
                        (when committed
                          (add-watch committed k (fn [_ _ _ _] (on-change))))
                        (fn unsubscribe []
                          (when committed (remove-watch committed k))
                          ;; rf2-sqhjtu / rf2-naz09e: clear the published
                          ;; committed reaction, but ONLY if it still holds THIS
                          ;; invocation's handle (compare the tagged reaction at
                          ;; index 1). A later `subscribe-fn` re-acquire (e.g. a
                          ;; subscribe-identity / key change) may have already
                          ;; overwritten `committed-ref` with the NEW tagged
                          ;; committed reaction before this older cleanup runs;
                          ;; clobbering it to nil would strand `get-snap` on the
                          ;; fallback.
                          (let [stored (.-current committed-ref)]
                            (when (and stored (identical? (aget stored 1) committed))
                              (set! (.-current committed-ref) nil)))
                          ;; Release the durable committed ref — symmetric with
                          ;; the `subs/subscribe` above. Runs on unmount /
                          ;; key change / teardown.
                          (subs/unsubscribe stable-frame-kw stable-query-v)))))
                  #js [stable-key])]
            (React/useSyncExternalStore subscribe-fn get-snap get-snap)))
        use-subscribe
        (fn use-subscribe
          ;; ---- 1-arg ambient form — full frame-resolution chain (rf2-4mi2zj) ----
          ;;
          ;; The ambient `(use-subscribe [:q …])` form MUST resolve the
          ;; frame through the SAME carried-invariant chain `subs/subscribe`'s
          ;; own 1-arity uses (Spec 006 §Frame resolution (1-arg form), :734,
          ;; :1058; EP-0002): dynamic-var tier (`frame/*current-frame*`, set by
          ;; `with-frame` / `bind-fn`) FIRST, the React-context tier
          ;; (the surrounding `frame-provider`) SECOND, and **nil → a loud
          ;; `:rf.error/no-frame-context`** with NO `:rf/default` floor.
          ;;
          ;; The earlier shortcut `(use-subscribe-2 (use-current-frame) …)`
          ;; bypassed that chain in two correctness-breaking ways:
          ;;
          ;;   1. `use-current-frame` is the NARROW raw `use-context` read
          ;;      (React-context tier ONLY — it never consults the dynamic
          ;;      var). Passing its result straight into the 2-arg EXPLICIT
          ;;      path let a surrounding frame boundary (`frame-provider` or
          ;;      `frame-root`) beat a `with-frame` / `bind-fn` dynamic scope
          ;;      — inverting the spec's tier precedence (dynamic-var MUST
          ;;      win).
          ;;   2. Beneath neither frame boundary, `use-context` returns the
          ;;      no-provider sentinel (`:rf.frame/no-provider`), NOT nil. The
          ;;      explicit 2-arg path then subscribed against that sentinel as
          ;;      a literal frame id — surfacing a bad-/destroyed-frame path
          ;;      instead of the specified `:rf.error/no-frame-context`.
          ;;
          ;; Fix: still CALL `use-current-frame` (the `use-context` hook) so
          ;; the component stays subscribed to context-value changes and
          ;; re-renders when the surrounding frame boundary (`frame-provider`
          ;; or `frame-root`) swaps frames —
          ;; a hook-safe, unconditional top-of-body call — but DISCARD its raw
          ;; value for resolution. Resolve the real frame via
          ;; `frame/require-current-frame!`, which delegates to
          ;; `resolve-current-frame` → the live `:adapter/current-frame`
          ;; late-bind hook (`function-component-current-frame`: dynamic-var →
          ;; `_currentValue` with sentinel→nil and corrupted-value detection)
          ;; and emits + throws `:rf.error/no-frame-context` on nil. This
          ;; single-sources resolution with `subs/subscribe`'s 1-arity — the
          ;; hook and the imperative read can never diverge — and then hands
          ;; the now-EXPLICIT resolved frame to the 2-arg path. The 2-arg
          ;; EXPLICIT form is unchanged (it bypasses the chain by design).
          ([query-v]
           ;; Hook subscription to provider-value changes (re-render). The
           ;; returned sentinel/keyword is intentionally NOT used as the
           ;; frame — resolution runs through the chain below.
           (use-current-frame)
           (use-subscribe-2
             (frame/require-current-frame!
               :subscribe
               {:where    're-frame.substrate.spine/use-subscribe
                :event-id (first query-v)})
             query-v))
          ([frame-kw query-v] (use-subscribe-2 frame-kw query-v)))]
    ;; rf2-6id3el: the return map exposes ONLY the surfaces the adapter
    ;; assembler consumes. `:warn-cache` is read by `make-react-adapter`
    ;; (the governance arm/armed? probes, :1868). The `:emitter-cell` /
    ;; `:active-roots-cell` cells stay INTERNAL to this closure — they are
    ;; wired into the spine fns (`render`, `set-hiccup-emitter!`,
    ;; `dispose-fn`) here and read by NO assembler or production call site,
    ;; so leaking them through the contract map would be dead surface. The
    ;; dispose unit tests build their own cells via the `make-*-cell`
    ;; factories and feed `make-dispose-adapter!` directly, so narrowing the
    ;; map breaks no test.
    {:warn-cache                  warn-cache
     :make-state-container        make-state-container
     :read-container              read-container
     :replace-container!          replace-cont!
     :subscribe-container         subscribe-cont
     :make-derived-value          make-derived
     :render                      render-fn
     :render-to-string            (make-render-to-string emitter-cell)
     :dispose-adapter!            dispose-fn
     :set-hiccup-emitter!         (fn set-it! [f]
                                    (set-hiccup-emitter! emitter-cell f))
     ;; rf2-h9szm — precedence-safe install-replay arm. Arms this generation's
     ;; `emitter-cell` with the retained SSR default ONLY when the cell is
     ;; otherwise unarmed, so a pre-init explicit custom emitter (or reset) is
     ;; never silently overwritten by `install-adapter!`'s replay. Routed by the
     ;; installed-adapter identity onto `:adapter/arm-hiccup-emitter-if-unarmed!`
     ;; by `make-react-adapter` (below), so the replay re-arms ONLY the installed
     ;; adapter's slot — an inactive adapter's arm never runs.
     :arm-hiccup-emitter-if-unarmed! (fn arm-if-unarmed! [f]
                                       (when (nil? @emitter-cell)
                                         (set-hiccup-emitter! emitter-cell f)))
     :use-current-frame           use-current-frame
     :use-subscribe               use-subscribe
     :flush-views!                flush-views!
     ;; rf2-40a84 — production-grade synchronous render-commit (NOT the
     ;; test-only act() wrapper above). Wired into the adapter map's
     ;; :flush-render! contract slot by make-react-adapter.
     :flush-render!               flush-render!
     :wrap-view                   wrap-view-fn
     :clear-warned-non-dom-roots! clear-warned
     ;; rf2-334d9 — :adapter/after-render impl. Each adapter publishes
     ;; this via substrate-adapter/route-hook!.
     :after-render-hook           after-render-hook}))

;; ---- React-hook adapter assembly (re-frame.ui + UIx) --------------
;;
;; rf2-ee38b.1 / rf2-ee38b.13 / rf2-ee38b.14. `make-react-spine` already
;; eliminated the substrate LOGIC drift (one factory, N adapters). The
;; per-adapter WIRING — the 9-key adapter map, the five `route-hook!`
;; calls, and the two chained installs — was still hand-copied byte-for-
;; byte between `uix.cljs` and `helix.cljs` (the clarity-lens twin
;; finding), carrying ~90 lines of identical rationale prose and a
;; standing drift hazard: any new routed hook had to be copied into both
;; files in lockstep (a Helix-only SSR-parity fix per rf2-y9spn already
;; showed the two drifting before being re-synced). `make-react-adapter`
;; folds that wiring here — the adapter file shrinks to "build spine-fns,
;; publish the public Vars, call make-react-adapter". The route-hook block
;; carries zero per-adapter variation; the ONLY input is the spine-fns map
;; (already built per-substrate) and the `:kind` discriminator keyword.
;;
;; Hook routing (per rf2-0d35 — see `substrate-adapter/route-hook!` for
;; the routing contract): each impl runs ONLY when this adapter is the
;; (rf/init!)-installed one; otherwise chains to the previously-registered
;; handler.
;;   :adapter/current-frame — rf2-d4sf. Function components have no
;;     class-component (.-context cmp) slot, so the shared impl in
;;     `re-frame.adapter.context` reads `_currentValue` directly. This is
;;     the WIDER surface — `(rf/current-frame-id)` reaches the dynamic-var-
;;     fallback chain via this hook; the per-adapter `use-current-frame`
;;     hook is the NARROWER React-context-tier-only read (rf2-84myk).
;;   :adapter/add-on-dispose! / :adapter/dispose! — rf2-jicu2. Spine-
;;     produced derived values reify the re-frame-owned
;;     `re-frame.disposable/IDisposable` (no Reagent coupling); the
;;     adapter wires straight to the protocol fns. The reactive-substrate
;;     hooks (`:adapter/ratom`, `:adapter/ratom?`, `:adapter/make-reaction`,
;;     `:adapter/reactive?`) are intentionally NOT published — the React-
;;     hook substrates ship no reactive-atom primitive (rf2-3yij / rf2-2qit)
;;     and `re-frame.interop`'s reactive-atom surfaces have zero production
;;     call sites under them; publishing those hooks would force the bundle
;;     to carry reagent.core (transitively reagent.ratom) for code it never
;;     executes.
;;   :adapter/after-render — rf2-334d9. Backed by `React.useLayoutEffect`
;;     via the spine's after-render machinery. `after-render` is a React-
;;     lifecycle question (when does the next commit complete?), not a
;;     reactive-atom one — so the "no reactive primitive" rationale that
;;     excludes the four hooks above does NOT apply. Without this hook
;;     `(rf/after-render f)` under these adapters would be a silent
;;     no-op.
;;   :adapter/wrap-view — rf2-00li. Substrate-side source-coord injection
;;     via React.cloneElement (the views.cljs inline hiccup-walk would
;;     mis-classify React-element output as a non-DOM root). Production-
;;     elided via `interop/debug-enabled?` per Spec 009 §Production builds.

(defn make-react-adapter
  "Assemble a React-hook adapter (Freehand's observation adapter or UIx —
  and, in-tree, the donor re-frame.ui substrate) from a
  `make-react-spine` result map plus the substrate's config:

      :kind           — the adapter's `:kind` discriminator keyword
      :frame-provider — the substrate's NATIVE frame-provider component
                        (`defui` for UIx), defined in
                        the adapter ns ABOVE where that substrate's `$`
                        marshals props (rf2-z7hfp — the moved seam). The
                        component reads its props in the substrate's
                        lossless idiom and delegates to the spine core
                        `build-frame-provider-element`. Passed in (NOT
                        spine-built) so the spine carries no substrate
                        element-macro dependency, mirroring how
                        `make-ratom-adapter` takes the Reagent-component
                        `register-context-provider` in.

  Builds the 9-key substrate adapter map, routes the five React-hook
  late-bind hooks against it (`substrate-adapter/route-hook!`), and wires
  the two chained installs (warn-once clear + SSR hiccup-emitter). The
  `:register-context-provider` substrate slot returns the native
  `frame-provider` component (the frame-keyword arg is ignored — the
  keyword lives in the Provider's `:value` at render time, not in a
  build-time closure). Returns the adapter map. SIDE-EFFECTING: the
  route-hook! / chain-fn! calls run at call time (the adapter ns
  evaluates `(make-react-adapter spine-fns {:kind :rf.adapter/uix
  :frame-provider …})` at load), exactly as the hand-written wiring did.

  Single source of truth (rf2-ee38b.1): Freehand and UIx both call this with
  the same shape (as does the donor re-frame.ui substrate in-tree) — the
  only inputs are their already-substrate-specific
  `spine-fns` map, `:kind`, and native `:frame-provider`. The former
  hand-copied route-hook block + chained installs (byte-identical across
  the UIx twins) now live once."
  [spine-fns {:keys [kind frame-provider]}]
  (let [adapter {:kind                      kind
                 :make-state-container      (:make-state-container      spine-fns)
                 :read-container            (:read-container            spine-fns)
                 :replace-container!        (:replace-container!        spine-fns)
                 :subscribe-container       (:subscribe-container       spine-fns)
                 :make-derived-value        (:make-derived-value        spine-fns)
                 :render                    (:render                    spine-fns)
                 :render-to-string          (:render-to-string          spine-fns)
                 ;; rf2-z7hfp: the native component IS the provider; the
                 ;; frame-keyword arg is ignored (frame lives in the
                 ;; Provider's `:value` at render time).
                 :register-context-provider (fn [_frame-keyword] frame-provider)
                 ;; rf2-40a84 — optional synchronous render-flush contract fn
                 ;; (react-dom/flushSync). Lets headless tooling commit pending
                 ;; renders without waiting on React's rAF-scheduled lane.
                 :flush-render!             (:flush-render! spine-fns)
                 :dispose-adapter!          (:dispose-adapter!          spine-fns)}]
    (substrate-adapter/route-hook! adapter :adapter/current-frame
      adapter-context/function-component-current-frame
      #(frame/current-frame))
    (substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
      rf-disposable/-add-on-dispose)
    (substrate-adapter/route-hook! adapter :adapter/dispose!
      rf-disposable/-dispose)
    (substrate-adapter/route-hook! adapter :adapter/wrap-view
      (:wrap-view spine-fns))
    (substrate-adapter/route-hook! adapter :adapter/after-render
      (:after-render-hook spine-fns))
    ;; Chained warn-once clear (rf2-4edk): chained (NOT routed by installed-
    ;; adapter identity) — every loaded adapter's per-process defonce must
    ;; clear between tests because a bundle can mount different adapters
    ;; across tests. rf2-z79p8: routed through the governance chokepoint
    ;; with arm/armed? probes over the spine's `warn-cache` atom so the
    ;; warn-once-clear governance assertion proves the chain wipes it.
    (let [warn-cache (:warn-cache spine-fns)]
      (install-clear-warn-once-step!
        (:clear-warned-non-dom-roots! spine-fns)
        {:label  :adapter/warned-non-dom-roots
         :arm    (fn [] (swap! warn-cache conj ::governance-sentinel))
         :armed? (fn [] (contains? @warn-cache ::governance-sentinel))}))
    ;; Chained SSR emitter install (rf2-4z7bp): `re-frame.ssr.emit` invokes
    ;; `:reagent/set-hiccup-emitter!` at ns-load; every loaded React-shaped
    ;; adapter contributes its own install step so a single
    ;; `(require '[re-frame.ssr])` auto-wires every adapter's render-to-
    ;; string slot. Hook key is historical (Reagent published it first per
    ;; rf2-uo7v); behaviour is adapter-agnostic.
    (late-bind/chain-fn! :reagent/set-hiccup-emitter!
                         (:set-hiccup-emitter! spine-fns))
    ;; rf2-h9szm — routed precedence-safe install-replay arm. Unlike the
    ;; broadcast `:reagent/set-hiccup-emitter!` chain above (which every loaded
    ;; adapter contributes to, and which `install-adapter!`'s replay must NOT
    ;; use — one throwing setter would break the active boot), this hook is
    ;; ROUTED via `route-hook!` so `install-adapter!`'s replay re-arms ONLY the
    ;; installed adapter's slot, and only when it is otherwise unarmed.
    (substrate-adapter/route-hook! adapter :adapter/arm-hiccup-emitter-if-unarmed!
                                   (:arm-hiccup-emitter-if-unarmed! spine-fns))
    adapter))

;; ---- ratom-family spine (Reagent + reagent-slim) --------------------------
;;
;; The Reagent and reagent-slim adapters are the SAME shape under a
;; different reactive-atom impl (stock `reagent.*` vs the `reagent2.*`
;; rewrite). `make-ratom-spine` factors the shared container quartet,
;; React-root renderer, and dispose body exactly as `make-react-spine`
;; factors the React-hook family (re-frame.ui / UIx) — one
;; implementation, two adapters, zero drift.
;;
;; CRITICAL — slim bundle isolation (IMPL-SPEC §1.8 / the
;; `test:reagent-slim:bundle-isolation` gate). This helper lives in
;; core/substrate and MUST NOT `:require` stock `reagent.*` — the day8/
;; reagent-slim adapter would otherwise drag the stock-Reagent impl tree
;; into every slim release bundle. The reactive-atom ops are therefore
;; INJECTED by each adapter as a flat set of BARE-FN config keys (the HOF
;; parameterisation): the Reagent adapter passes its stock `reagent.*`
;; impls, the slim adapter passes its `reagent2.*` impls. The spine never
;; names either ns. (The same isolation principle as `make-react-spine`,
;; which calls `react-dom/client` directly but never Reagent.)
;;
;; rf2-0u5em6: the per-substrate ops arrive as FLAT bare-fn config keys
;; (`:r-atom`, `:make-reaction`, `:create-root`, …) — mirroring how
;; `make-react-spine` takes its bare `:use-memo` / `:use-callback` /
;; `:use-context` hook fns — rather than as a hand-shaped `:ratom-ops`
;; keyword map literal. Earlier each adapter built a structurally-identical
;; 7-key `:ratom-ops` map differing only by ns-alias (`reagent.*` vs
;; `reagent2.*`), a "keep two maps in lockstep" hazard. The keyword-key
;; shape now lives ONCE here; each adapter passes ~7 bare fns.

(defn- make-ratom-dispose-dispatch
  "Build the exact-generation disposer shared by ratom adapter cleanup and
  its public routed `:adapter/dispose!` hook. A ratom generation may own both
  re-frame spine values and its substrate's native reactions, so the dispatch
  preserves that dual-protocol order without consulting global adapter state."
  [disposable? dispose!]
  (fn dispose!-dispatch [a]
    (cond
      (satisfies? rf-disposable/IDisposable a) (rf-disposable/-dispose a)
      (disposable? a)                          (dispose! a)
      :else                                    nil)))

(defn make-ratom-spine
  "Build the per-substrate ratom-family substrate surfaces given the
  substrate's gensym prefix and a FLAT set of injected reactive-atom BARE
  FNS (rf2-0u5em6 — mirroring how `make-react-spine` takes its bare
  `:use-memo` / `:use-callback` / `:use-context` hook fns, not a hand-
  shaped keyword map):

      :gensym-prefix-sub — gensym prefix for `subscribe-container` watch
                           keys (substrate-scoped per rf2-l4dmr so logs /
                           inspectors attribute a watch to its substrate)
      :r-atom        — (fn [v]) → reactive atom container
      :make-reaction — (fn [thunk]) → reaction over a thunk
      :create-root   — (fn [mount-point]) → React root
      :render-root   — (fn [root tree]) → render hiccup into root (the
                       substrate's hiccup→element walk + `.render`, NOT a
                       bare `.render`)
      :hydrate-root  — (fn [mount-point tree]) → React root
      :unmount-root  — (fn [root]) → unmount the root
      :disposable?   — (fn [x]) → boolean for the substrate's IDisposable
      :dispose!      — (fn [x]) → dispose a substrate-native reaction
      :flush-render! — (fn [f]) → run `f` then SYNCHRONOUSLY commit the
                       substrate's pending renders to the DOM (rf2-40a84;
                       stock Reagent passes `(fn [f] (f) (reagent.core/
                       flush))`, slim passes its `reagent2.*` synchronous
                       flush). NOT rAF-scheduled — immune to the
                       backgrounded-tab throttle, so headless tooling can
                       drive a `dispatch → flush-render! → observe-DOM` loop.

  The spine assembles its internal ratom-ops shape from these bare fns; it
  MUST NOT `:require` stock `reagent.*`; the fns above are the only path to
  the substrate's reactive primitive, so each adapter's own `reagent.*` /
  `reagent2.*` requires stay confined to the adapter ns (load-bearing for
  reagent-slim bundle isolation — see the section comment above).

  Returns a map of the substrate-contract surfaces (minus
  `register-context-provider`) plus the SSR helpers each adapter
  re-exports:

      {:make-state-container       …
       :read-container             …
       :replace-container!         …
       :subscribe-container        …
       :make-derived-value         …
       :render                     …
       :render-to-string           …
       :dispose-adapter!           …
       :flush-render!              …
       :flush-views!               …
       :set-hiccup-emitter!        …}

  rf2-6id3el: the internal `active-roots-cell` / `emitter-cell` are NOT
  exposed — they stay confined to this closure (wired into `render`,
  `dispose-adapter!`, `set-hiccup-emitter!`); no assembler or production
  call site read them.

  `:register-context-provider` is NOT produced here: for the ratom
  family it is the Reagent-component-shaped frame-provider from
  `re-frame.views` (`views/build-frame-provider`), which the React-hook
  spine's hook-shaped `frame-provider` is not. Keeping it as adapter-side
  wiring also keeps this core ns free of a spine→views dependency edge.

  Produces: container quartet incl. the substrate-scoped gensym; the
  create-root/hydrate-root render with active-roots tracking + an unmount
  thunk that drops itself from the set; and the four-MUST dispose body
  (`dispose-frame-sub-caches!` + active-roots drain w/ per-root throw-
  swallow + emitter clear)."
  [{:keys [gensym-prefix-sub r-atom make-reaction create-root render-root
           hydrate-root unmount-root disposable? dispose!]
    flush-render-op :flush-render!}]
  (let [active-roots-cell (make-active-roots-cell)
        emitter-cell      (make-hiccup-emitter-cell)
        ;; Terminal cleanup cannot route through `interop/dispose!`: the
        ;; process lifecycle deliberately hides the claimed generation before
        ;; invoking its cleanup. Capture this generation's dual-protocol
        ;; disposer in the adapter closure instead.
        dispose-reaction! (make-ratom-dispose-dispatch disposable? dispose!)
        ;; rf2-w1g0d2: reuse the shared container helpers where the ratom +
        ;; React-hook semantics are genuinely identical. `make-state-container`
        ;; differs ONLY in the ctor (substrate `r-atom` vs plain `atom`), so
        ;; it rides the shared `make-state-container-fn` factory. `read-container`
        ;; is BYTE-IDENTICAL (`@container`), so it reuses the top-level Var
        ;; directly. `replace-container!` (bare `reset!`, NO epoch scheduler)
        ;; and `make-derived-value` (native reaction, NOT the explicit reify)
        ;; legitimately DIFFER — see below — so they stay inline.
        ;; (`read-container` is not rebound here — the return map references
        ;; the top-level Var directly.)
        make-state-container (make-state-container-fn r-atom)
        ;; replace-container! is a BARE `reset!` — NO epoch scheduler. The
        ;; React-hook spine's `make-replace-container-fn` brackets its reset!
        ;; in a scheduler epoch (`with-epoch`) because it has no reaction
        ;; primitive and must coalesce multi-input derived recomputes glitch-
        ;; free explicitly (Spec 006 §Invalidation algorithm). The ratom
        ;; family is immune: Reagent's reactions are natively batched through
        ;; `r/flush!`, so a multi-input Reaction already recomputes once per
        ;; coherent input epoch. There is no scheduler in this spine to bracket
        ;; against, so this CANNOT consolidate with the React-hook version.
        replace-container!
        (fn replace-container! [container new-value]
          (reset! container new-value)
          nil)
        subscribe-container
        (make-subscribe-container gensym-prefix-sub)
        ;; Arity-specialised recompute closure via `build-recompute-fn`
        ;; (rf2-eoy63), wrapped in the substrate's own reaction primitive.
        make-derived-value
        (fn make-derived-value [source-containers compute-fn]
          (make-reaction (build-recompute-fn source-containers compute-fn)))
        ;; React 18+/19 Root API: create-root → render → unmount; the
        ;; hydrate branch on the SSR path returns its own Root. Active
        ;; roots are tracked so `dispose-adapter!` can drain them; the
        ;; unmount thunk removes itself from the set before unmounting.
        ;; Per rf2-gwkvr: Spec 006 §`render` types `:hydrate?` as a
        ;; boolean; no defensive coercion.
        render
        (fn render [render-tree mount-point opts]
          (let [hydrate? (:hydrate? opts)
                root     (if hydrate?
                           (hydrate-root mount-point render-tree)
                           (let [r (create-root mount-point)]
                             (render-root r render-tree)
                             r))]
            ;; rf2-w1g0d2: shared track-and-unmount tail (unmount-op =
            ;; the injected `unmount-root`).
            (track-active-root! active-roots-cell unmount-root root)))
        ;; Spec 006 §Adapter disposal lifecycle (rf2-9fdkb, rf2-a47kq,
        ;; rf2-jcjul, rf2-7v82h). The four-MUST list:
        ;;   1. Cancel in-flight reactive subscriptions — walk every live
        ;;      frame's per-frame sub-cache (`dispose-frame-sub-caches!`,
        ;;      shared with the React-hook spine for zero drift).
        ;;   2. Release host-specific resources — drain active-roots,
        ;;      swallowing per-root throws so one bad root cannot strand
        ;;      the rest of the drain.
        ;;   3. Discard internal caches — clear the hiccup-emitter cell.
        ;;   4. Subsequent calls return `:rf.error/adapter-disposed` —
        ;;      enforced one level up by substrate-adapter via the
        ;;      `disposed?` breadcrumb (rf2-6wxys).
        ;; rf2-w1g0d2: the ratom dispose IS exactly the shared core
        ;; (`dispose-active-roots-and-caches!`) — sub-cache walk + active-roots
        ;; drain-with-swallow + emitter clear — with `unmount-root` as the
        ;; unmount-op. No warn-cache / driver-root teardown (those are
        ;; React-hook-only), so unlike `make-dispose-adapter!` it layers
        ;; nothing on top.
        dispose-adapter!
        (fn dispose-adapter! []
          (dispose-active-roots-and-caches! dispose-reaction! unmount-root
                                            active-roots-cell emitter-cell))
        ;; rf2-40a84 — production synchronous render-flush. Delegates to the
        ;; injected `:rdc/flush-render!` op (stock `reagent.core/flush` /
        ;; slim's `reagent2.*` synchronous flush) so the spine never names a
        ;; reactive-atom ns (bundle isolation). The op runs `f` then drains
        ;; the substrate's component-render queue synchronously and (on React
        ;; 19) commits via `flushSync` — NOT rAF-scheduled, so it fires even
        ;; in a backgrounded tab. No-op-safe when nothing is pending.
        flush-render!
        (fn flush-render!
          ([] (flush-render! (fn [] nil)))
          ([f]
           (if flush-render-op
             (flush-render-op f)
             ;; Defensive: an adapter that injected no flush op still honours
             ;; the contract by at least running `f`. Reagent and reagent-slim
             ;; both inject one, so this branch is dead in the reference.
             (f))
           nil))
        ;; rf2-b6nm5 — CANONICAL test-flush hook, converged across all four
        ;; substrates (Decision 6 anointed `flush-views!`; previously stock
        ;; Reagent surfaced none, slim surfaced a Promise-returning one in a
        ;; SUBSTRATE ns). Same name, location (adapter ns, re-exported from
        ;; here), and SHAPE as the React-hook spine's `flush-views!`: wrap
        ;; React's `act()` so a subscribe → re-render cycle drives
        ;; synchronously in test code; with no arg, flushes pending effects;
        ;; returns nil. Inside `act` we drive the ratom-family synchronous
        ;; render drain (the injected `:rdc/flush-render!` op — stock
        ;; `reagent.core/flush` / slim's `reagent2.*` flush) so dirty
        ;; components forceUpdate and Reactions recompute before `act`
        ;; returns. When act() is unreachable in the current React build it
        ;; degrades to a plain synchronous flush — the ratom family has a
        ;; real synchronous render drain even without act (unlike the
        ;; React-hook spine `flush-views!`, which no-ops).
        flush-views!
        (fn flush-views!
          ([] (flush-views! (fn [] nil)))
          ([f]
           (if-let [act (resolve-act-fn)]
             (act (fn act-body []
                    (if flush-render-op
                      (flush-render-op f)
                      (f))))
             ;; No act() — degrade to a plain synchronous flush so a
             ;; :node-test runner (no real React render path) still drains
             ;; the render queue + dirty-set.
             (if flush-render-op (flush-render-op f) (f)))
           nil))]
    {:make-state-container       make-state-container
     :read-container             read-container
     :replace-container!         replace-container!
     :subscribe-container        subscribe-container
     :make-derived-value         make-derived-value
     :render                     render
     :render-to-string           (make-render-to-string emitter-cell)
     :dispose-adapter!           dispose-adapter!
     ;; rf2-40a84 — production synchronous render-commit, wired into the
     ;; adapter map's :flush-render! contract slot by make-ratom-adapter.
     :flush-render!              flush-render!
     ;; rf2-b6nm5 — canonical nil-return test-flush hook (Decision 6),
     ;; re-exported by both ratom adapter namespaces so all four substrates
     ;; surface the SAME `flush-views!` Var with the SAME nil-return shape.
     :flush-views!               flush-views!
     :set-hiccup-emitter!        (fn set-it! [f]
                                   (set-hiccup-emitter! emitter-cell f))
     ;; rf2-h9szm — precedence-safe install-replay arm (see the twin in
     ;; `make-react-spine`). Arms this generation's `emitter-cell` with the
     ;; retained SSR default ONLY when otherwise unarmed; routed onto
     ;; `:adapter/arm-hiccup-emitter-if-unarmed!` by `make-ratom-adapter`.
     :arm-hiccup-emitter-if-unarmed! (fn arm-if-unarmed! [f]
                                       (when (nil? @emitter-cell)
                                         (set-hiccup-emitter! emitter-cell f)))}))
;; rf2-6id3el: the ratom return map exposes ONLY the surfaces the adapter
;; assembler consumes. `make-ratom-adapter` reads none of the internal
;; cells; the `:active-roots-cell` / `:emitter-cell` cells stay INTERNAL to
;; this closure (wired into `render`, `dispose-adapter!`,
;; `set-hiccup-emitter!`) and were read by NO assembler or production call
;; site, so they were dead contract surface. (The ratom family has no
;; `:warn-cache` — its source-coord walk lives in `re-frame.views`, not the
;; spine — so unlike `make-react-spine` it exposes no internal cell at all.)

;; ---- ratom-family adapter assembly (Reagent + reagent-slim) ---------------
;;
;; rf2-ee38b.1 / rf2-ee38b.12 / rf2-ee38b.15. `make-ratom-spine` hoisted
;; the substrate-surface drift (container quartet, renderer, dispose body,
;; SSR emitter) but left the SECOND half — the `set-hiccup-emitter!` chain
;; install, the `register-context-provider` wiring, the 9-key adapter map,
;; and the entire nine-call `route-hook!` table — hand-copied byte-for-byte
;; between `reagent.cljs` and `reagent_slim.cljs` (the clarity-lens twin
;; finding across both ratom beads). The two `cond` dispatch closures
;; (`add-on-dispose!`/`dispose!`) carry zero substrate-specific text — only
;; which `ratom` ns binds the alias differs. `make-ratom-adapter` folds
;; that wiring here, mirroring `make-react-adapter`.
;;
;; CRITICAL — slim bundle isolation. As with `make-ratom-spine`, this
;; helper MUST NOT `:require` stock `reagent.*` (or `reagent2.*`). The
;; reactive-atom-family ops the hook table needs — `current-component`,
;; `atom`, `after-render`, `make-reaction`, the `ratom?`/`disposable?`
;; predicates, and the `add-on-dispose!`/`dispose!`/`reactive?` fns — are
;; INJECTED as a flat set of bare-fn config keys (predicate/dispatch
;; lambdas over the substrate's protocols), so the spine never names a
;; reactive-atom ns. Each adapter passes its `reagent.*` / `reagent2.*`
;; impls.
;;
;; rf2-0u5em6: as with `make-ratom-spine`, the hook ops arrive as FLAT
;; bare-fn config keys rather than a hand-shaped `:hook-ops` keyword map
;; literal. Earlier each adapter built a structurally-identical 10-key
;; `:hook-ops` map differing only by ns-alias (the two maps were byte-
;; identical modulo `reagent.*` vs `reagent2.*`) — a "keep two maps in
;; lockstep" hazard. The keyword-key shape now lives ONCE here; the spine
;; assembles the route-hook table from the bare fns each adapter passes.

(defn make-ratom-adapter
  "Assemble a ratom-family adapter (Reagent / reagent-slim) from a
  `make-ratom-spine` result map plus the substrate's config:

      :kind      — the adapter's `:kind` discriminator keyword
      :register-context-provider
                 — the views-backed (Reagent-component-shaped) provider fn
                   `(fn [_frame-keyword] (views/build-frame-provider))`.
                   Passed in (NOT spine-built) so the core spine carries no
                   spine→views dependency edge.

  …plus a FLAT set of the injected reactive-atom-family BARE FNS the
  late-bind hook table routes (rf2-0u5em6 — bundle-isolation: lambdas only,
  the spine names no reactive-atom ns; mirrors `make-react-spine`'s bare-
  hook-fn config rather than a hand-shaped keyword map):

      :current-frame      — (fn []) → React-context-tier current frame
                            (`views/current-frame`)
      :current-component  — (fn []) → the in-flight component
      :atom               — (fn [v]) → reactive atom
      :ratom?             — (fn [x]) → boolean (IReactiveAtom check)
      :make-reaction      — (fn [thunk]) → reaction
      :activate-reaction! — (fn [rx]) → put one of THIS substrate's
                            reactions on its push path (the missing
                            `deref-capture`), idempotent and a no-op on
                            anything that is not one of its reactions.
                            rf2-8cnxg — see the `:adapter/activate-
                            derived-value!` routing note below.
      :disposable?        — (fn [x]) → boolean (substrate IDisposable
                            check), used by the dual-protocol dispatch
      :add-on-dispose!    — (fn [a f]) → register a substrate-reaction
                            dispose hook
      :dispose!           — (fn [a]) → dispose a substrate reaction
      :reactive?          — (fn []) → boolean
      :after-render       — (fn [f]) → schedule post-render callback

  Builds the 9-key adapter map, wires the chained SSR emitter install, and
  routes the ratom-family late-bind hooks against the adapter. The two
  dual-protocol dispatch hooks (`:adapter/add-on-dispose!` / `:adapter/
  dispose!`) protocol-check the re-frame-owned
  `re-frame.disposable/IDisposable` FIRST (spine-produced derived values
  from a cross-substrate test bundle, rf2-jicu2) then fall through to the
  substrate's own disposable (`:disposable?` / `:add-on-dispose!` /
  `:dispose!`). Returns the adapter map. SIDE-EFFECTING at call time
  (chain-fn! / route-hook!), exactly as the hand-written wiring was.

  Single source of truth (rf2-ee38b.1): Reagent and reagent-slim call this
  with the same shape — only their injected bare hook fns and `:kind`
  differ. The former hand-copied route-hook block now lives once."
  [spine-fns {:keys [kind register-context-provider
                     current-frame current-component atom ratom? make-reaction
                     activate-reaction!
                     disposable? add-on-dispose! dispose! reactive? after-render]}]
  (let [dispose-dispatch (make-ratom-dispose-dispatch disposable? dispose!)
        adapter {:kind                      kind
                 :make-state-container      (:make-state-container spine-fns)
                 :read-container            (:read-container       spine-fns)
                 :replace-container!        (:replace-container!   spine-fns)
                 :subscribe-container       (:subscribe-container  spine-fns)
                 :make-derived-value        (:make-derived-value   spine-fns)
                 :render                    (:render               spine-fns)
                 :render-to-string          (:render-to-string     spine-fns)
                 :register-context-provider register-context-provider
                 ;; rf2-40a84 — optional synchronous render-flush contract fn
                 ;; (reagent.core/flush — drains the render queue + React-19
                 ;; flushSync commit). Lets headless tooling commit pending
                 ;; renders without waiting on Reagent's rAF-scheduled drain.
                 :flush-render!             (:flush-render! spine-fns)
                 :dispose-adapter!          (:dispose-adapter!     spine-fns)}]
    ;; Chained SSR emitter install (rf2-4z7bp / parity rf2-cl1qv): every
    ;; loaded React-shaped adapter contributes its install step so a single
    ;; `(require '[re-frame.ssr])` auto-wires every adapter's render-to-
    ;; string slot. `chain-fn!` (not `set-fn!`) is load-order-independent.
    (late-bind/chain-fn! :reagent/set-hiccup-emitter!
                         (:set-hiccup-emitter! spine-fns))
    ;; rf2-h9szm — routed precedence-safe install-replay arm (twin of the
    ;; `make-react-adapter` publication). ROUTED so `install-adapter!`'s replay
    ;; re-arms ONLY the installed adapter's slot, and only when otherwise
    ;; unarmed — NOT the broadcast `:reagent/set-hiccup-emitter!` chain above.
    (substrate-adapter/route-hook! adapter :adapter/arm-hiccup-emitter-if-unarmed!
                                   (:arm-hiccup-emitter-if-unarmed! spine-fns))
    ;; Each hook routes through `(substrate-adapter/current-adapter)` per
    ;; rf2-0d35 via `route-hook!`: this adapter's impl runs ONLY when it is
    ;; the (rf/init!)-installed one; otherwise it chains to the previously-
    ;; registered handler.
    ;;   :adapter/current-frame — rf2-d4sf. The React-context tier of the
    ;;     3-tier chain; the ratom family uses the class-component
    ;;     (.-context cmp) shape via `views/current-frame`. Chain-bottom
    ;;     fallback is `frame/current-frame` so headless / pre-init shape is
    ;;     preserved.
    ;;   :adapter/current-component — rf2-wbnl. Reads the substrate's
    ;;     in-flight component without hard-binding re-frame.views to it.
    ;;   :adapter/ratom etc. — rf2-s36l. The reactive-substrate surfaces
    ;;     consumed by `re-frame.interop`.
    ;;   :adapter/add-on-dispose! / :adapter/dispose! — rf2-jicu2. A
    ;;     ratom-installed app may still hold a spine-produced derived value
    ;;     (inherited through a cross-substrate test bundle). Dispatch
    ;;     handles BOTH shapes — the re-frame-owned IDisposable (spine
    ;;     derived values, checked first) and the substrate's own
    ;;     IDisposable.
    (substrate-adapter/route-hook! adapter :adapter/current-frame
      current-frame
      #(frame/current-frame))
    (substrate-adapter/route-hook! adapter :adapter/current-component
      current-component)
    (substrate-adapter/route-hook! adapter :adapter/ratom
      atom)
    (substrate-adapter/route-hook! adapter :adapter/ratom?
      ratom?
      (constantly false))
    (substrate-adapter/route-hook! adapter :adapter/make-reaction
      make-reaction)
    ;; rf2-8cnxg / rf2-jt8vz — the ratom family's derived values are
    ;; DEMAND-driven, and Spec 006 §`make-derived-value` requires PUSH ("the
    ;; derived container updates automatically when any source's value
    ;; changes"; `subscribe-container` "works as on a base container"). A
    ;; substrate `Reaction` captures its sources only through the
    ;; substrate's `deref-capture`; a plain `deref` taken OUTSIDE a reactive
    ;; context, with no `auto-run`, runs the body RAW and leaves the
    ;; reaction's `watching` nil — so it is watchable, and watched, and
    ;; notifies nobody. A component render is normally the capture context,
    ;; which is why an ordinary app never sees this; the observation port's
    ;; consumer (a compiled ViewCell) is NOT a component of this substrate,
    ;; so nothing supplies it and the port's `add-watch` observes a node
    ;; that can never fire. This hook is that missing capture, called by
    ;; `re-frame.interop/activate-derived-value!` from the port's
    ;; `build-node-handle!`.
    ;;
    ;; ONLY the ratom family publishes it: the React-hook spine's
    ;; `make-derived-value-fn` wires one watch per source at CONSTRUCTION,
    ;; so its derived values are push-based from birth and the routed
    ;; chain-bottom nil is the correct no-op for them. Deliberately NOT
    ;; `:auto-run true` on `make-derived-value` — that would make every
    ;; subscription under this substrate recompute SYNCHRONOUSLY inside the
    ;; app-db `reset!`, discarding the batching this spine's
    ;; `replace-container!` comment relies on. Activation is per-acquire and
    ;; idempotent, so an unobserved subscription is never made eager.
    (substrate-adapter/route-hook! adapter :adapter/activate-derived-value!
      activate-reaction!)
    (substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
      (fn add-on-dispose!-dispatch [a f]
        (cond
          (satisfies? rf-disposable/IDisposable a) (rf-disposable/-add-on-dispose a f)
          (disposable? a)                          (add-on-dispose! a f)
          :else                                    nil)))
    (substrate-adapter/route-hook! adapter :adapter/dispose! dispose-dispatch)
    (substrate-adapter/route-hook! adapter :adapter/reactive?
      reactive?
      (constantly false))
    (substrate-adapter/route-hook! adapter :adapter/after-render
      after-render)
    ;; rf2-8wrzz.3 — the derived-container discriminator the core's
    ;; `replace-container!` choke point consults to reject writes to a
    ;; `make-derived-value` result (Spec 006 §`make-derived-value`). The
    ;; ratom family CANNOT rely on the choke point's atom-marker fall-back:
    ;; a Reagent `Reaction` reifies `IAtom` exactly like a base `r/atom`, so
    ;; the heuristic would never fire. The disposal protocol IS the
    ;; discriminator — a derived value is disposable, a base `r/atom` /
    ;; `RAtom` is not. Dual-protocol like the dispose dispatch above: the
    ;; re-frame-owned IDisposable FIRST (a spine-produced derived value
    ;; inherited through a cross-substrate test bundle, rf2-jicu2) then the
    ;; substrate's own `:disposable?`. Routed (not an adapter-map key) so
    ;; the ten-fn adapter contract shape is preserved; the choke point reads
    ;; it via `late-bind/get-fn :adapter/derived-container?`. The ratom
    ;; impl is exhaustive over ratom containers — truthy for a `Reaction`
    ;; (derived), `false` for a base `r/atom` (the choke point trusts that
    ;; `false` and skips its atom-marker heuristic, rf2-oitw37). The
    ;; chain-bottom fallback returns the `container-class-unknown` sentinel
    ;; (NOT `false`): when a NON-ratom adapter is installed, this routed
    ;; closure has no opinion and the choke point must reach for the
    ;; atom-marker heuristic — a bare `false` would instead read as "this
    ;; ratom adapter classifies it as base", wrongly forcing the
    ;; non-ratom-adapter path through the ratom verdict (rf2-oitw37).
    (substrate-adapter/route-hook! adapter :adapter/derived-container?
      (fn derived-container?-dispatch [a]
        (or (satisfies? rf-disposable/IDisposable a)
            (boolean (disposable? a))))
      (constantly substrate-adapter/container-class-unknown))
    adapter))
