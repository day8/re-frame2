(ns re-frame.adapter.test-react
  "The Test-React adapter — simulates the React class-3 lifecycle in pure
  CLJC (no React, no DOM, no jsdom) so React-lifecycle-driven bugs (stale
  closures, unbalanced subscribe/dispose, sync unmount inside render — the
  rf2-4l7t2 class) can be caught at unit-test speed.

  Implements the Spec 006 adapter contract: the 6 required fns, 2 of the
  3 optional (`subscribe-container`, `register-context-provider` — no
  `flush-render!`, since the test owns the clock via `trigger-update!`),
  and the 1 lifecycle fn. The atom-backed
  container quartet is shared with plain-atom via
  `re-frame.substrate.atom-container`; the render half is the novel surface
  — a `class-3` lifecycle simulator that records every transition into a
  per-mount log and enforces the sync-unmount-during-render invariant (it
  throws on a synchronous `unmount!` while a render is in flight ANYWHERE in
  the tree, mirroring React 18+).

  Render bodies + recursive children: a render tree may declare an imperative
  body via the node shape `{:rf/component (fn [mount] ...)}`. The body runs
  during the render phase (while the global render depth is non-zero) and can
  mount children via `mount-child!` (which recurse through their own
  lifecycle) or issue an `unmount!`. An `unmount!` of an ancestor/sibling from
  inside such a body trips the guard ORGANICALLY — no hand-fabricated
  in-flight state — which is exactly the rf2-4l7t2 shape (the senbl panel-host
  unmounting the previous panel's root on a chip-row re-render).

  Status: carries ported lifecycle regressions (rf2-n2cuo broadened the
  rf2-gqyqv skeleton): organic sync-unmount-during-render, unbalanced
  mount/unmount ref-count, and double-render.

  Out of scope (deferred to follow-on beads):
    - Auto-re-render on app-db change: tests drive re-renders explicitly
      via `trigger-update!`. Children re-render only when a test re-runs the
      parent's render body (via `trigger-update!` with the same body) or
      drives the child directly; there is no automatic propagation.
    - React-context provider traversal (frame-routing is via the
      dynamic-var tier; the React-context tier is degenerate — no React).
    - Source-coord annotation (Spec 006 §Source-coord annotation): N/A —
      there is no DOM root to annotate, the render tree is opaque data, and
      the spec exempts non-DOM roots.
    - CLJS derived-value disposal / ref-count symmetry (rf2-pyp3n): see the
      `make-derived-value` comment for the JVM-works / CLJS-silent split.

  See README.md for the family table, the bug-class matrix, and the full
  scope-boundary narrative. Usage:

      (require '[re-frame.core :as rf]
               '[re-frame.adapter.test-react :as test-react])

      (rf/init! test-react/adapter)

      (let [m (test-react/mount! [my-view {:title \"hi\"}])]
        (rf/dispatch-sync [:set-title \"bye\"])
        (test-react/trigger-update! m [my-view {:title \"bye\"}])
        (test-react/unmount! m)
        (mapv :phase (test-react/lifecycle-log m)))
      ;; => [:constructor :render :did-mount :render :did-update :will-unmount]"
  (:require [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.atom-container :as atom-container]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.frame :as frame]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reactive-container half (shared with plain-atom) ----------------------
;;
;; `make-state-container` / `read-container` / `replace-container!` /
;; `subscribe-container` come straight from `re-frame.substrate.atom-container`
;; — see that ns. The simulator never auto-re-renders on container change;
;; tests drive re-renders via `trigger-update!`. `subscribe-container` is
;; still useful for tests that assert which replaces fired.

(defn- make-derived-value [source-containers compute-fn]
  ;; Recompute on every deref. No caching: the test surface only runs a sub
  ;; a handful of times per case.
  ;;
  ;; rf2-pyp3n: IDeref ONLY — deliberately no IWatchable / no disposal
  ;; protocol. The sub-cache's per-reaction dispose path therefore works on
  ;; the JVM (interop.clj implements dispose by reaction identity) but is a
  ;; SILENT no-op under CLJS (this adapter withholds the :adapter/dispose! /
  ;; :adapter/add-on-dispose! late-bind hooks — see the routing block at the
  ;; foot of this ns — and interop.cljs routes through them, so unregistered
  ;; hooks no-op). The derived value holds no host resources, so the
  ;; PER-REACTION dispose has nothing to release. But the CACHE ENTRY itself
  ;; (the `{:reaction … :ref-count n}` slot held on the frame) DOES survive a
  ;; reaction that never auto-disposes — so `dispose-adapter!` MUST reset
  ;; every live frame's sub-cache to clear stale entries + ref-counts across
  ;; a dispose/reinstall cycle (rf2-ghfkkk). See `dispose-adapter!`.
  (reify
    #?(:clj clojure.lang.IDeref :cljs IDeref)
    (#?(:clj deref :cljs -deref) [_]
      (apply compute-fn (map deref source-containers)))))

;; ---- render half — the class-3 lifecycle simulator -------------------------
;;
;; Each mount produces a `MountedComponent` record carrying:
;;
;;   :id              — gensym tag (for log readability)
;;   :render-tree     — atom holding the currently-rendered tree
;;   :lifecycle-log   — atom holding a vector of {:phase ... :seq n} entries;
;;                      the test driver inspects this to assert ordering (`:seq`
;;                      is the monotonic order-key — see `phase-seq`).
;;   :currently-rendering? — atom<boolean>; true while THIS mount's render is
;;                      in flight. Distinct from the global render-depth (see
;;                      below): this cell marks the self-render case, the
;;                      global cell marks "React is rendering somewhere."
;;   :mounted?        — atom<boolean>; false after unmount. The simulator
;;                      THROWS on trigger-update! / unmount! after teardown.
;;   :children        — atom<vector<MountedComponent>>; child roots this mount
;;                      mounted from inside its own render body via
;;                      `mount-child!`. Unmounting a parent cascades to its
;;                      children (will-unmount fires children-first, mirroring
;;                      React's child-before-parent teardown order).
;;   :unmount-fn      — the unmount thunk for this mount; `unmount!` calls it.

(defrecord ^:no-doc MountedComponent
  [id render-tree lifecycle-log currently-rendering? mounted? children unmount-fn])

;; All live mounts; `dispose-adapter!` walks this to drain.
(defonce ^:private active-mounts (atom #{}))

;; Global render depth — "React is rendering somewhere in the tree." React's
;; sync-unmount-during-render guard fires whenever ANY render is in flight, not
;; only when the root being unmounted is itself mid-render. The rf2-4l7t2 shape
;; is exactly this cross-root case: a parent re-renders and, from inside that
;; render body, synchronously unmounts a SEPARATELY-tracked sibling/child root
;; (the senbl panel-host unmounting the previous panel's root on a chip-row
;; switch). The per-mount :currently-rendering? cell cannot see that — it is
;; false on the sibling being torn down — so the guard keys off this global
;; counter. A counter (not a boolean) so nested child renders restore the flag
;; correctly on unwind.
(defonce ^:private render-depth (atom 0))

;; The mount whose render body is currently executing, if any. `mount-child!`
;; attaches newly-mounted children to this parent so the tree structure (and
;; thus cascading unmount) is recorded. nil when no render is in flight.
(def ^:private ^:dynamic *rendering-mount* nil)

(defn rendering?
  "True when the simulator is mid-render anywhere in the tree (the condition
  React's sync-unmount-during-render guard keys off). Exposed for tests that
  want to assert the global render state directly rather than via a thrown
  guard."
  []
  (pos? @render-depth))

;; A process-global monotonic order-key stamped on every logged phase. A
;; wall-clock timestamp is the wrong tool for teardown-ORDER assertions here:
;; a whole cascade completes sub-millisecond, so `now-ms`-style timestamps
;; collapse to the SAME integer and a `<=`-on-ms ORDER check is vacuous — it
;; stays green even on a reversed (root-downward) teardown, the exact bug the
;; check exists to catch. This counter increments once per logged phase, so a
;; strict `<` over two phases' `:seq` values discriminates their REAL firing
;; order and fails deterministically on a reversal. `dispose-adapter!` resets
;; it per test (only relative order within a cascade matters, so the reset is
;; hygiene — keeping the numbers small + deterministic across the suite).
(defonce ^:private phase-seq (atom 0))

(defn- log-phase! [mount phase & {:as extras}]
  (swap! (:lifecycle-log mount)
         conj (merge {:phase phase :seq (swap! phase-seq inc)} extras)))

;; Declared so `run-render!` (which invokes a render body that may mount
;; children) can call the mount seam defined below it.
(declare mount-tree!)

(defn- render-body
  "If `tree` declares an imperative render body, return it; else nil. The body
  is `(fn [mount] ...)` run while a render is in flight (so any `unmount!`
  issued from within fires the guard organically, and any `mount-child!`
  attaches to `mount`). A render tree carries a body via the node shape
  `{:rf/component (fn [mount] ...) ...}`; plain hiccup / opaque data has none."
  [tree]
  (when (map? tree)
    (:rf/component tree)))

(defn- run-render!
  "Run one render of `mount` with `tree` as its output. Increments the global
  render depth and sets the per-mount :currently-rendering? flag for the
  duration, records a :render phase entry, stores the tree, and — if the tree
  declares an imperative render body (`:rf/component`) — invokes that body with
  `mount` bound as `*rendering-mount*` so it can mount children or issue a
  (guard-tripping) re-entrant unmount. Throws from the render body are NOT
  caught — they propagate to the caller (React 18+ unmounts the root); the
  flags/depth are restored on unwind via `finally`."
  [mount tree]
  (swap! render-depth inc)
  (reset! (:currently-rendering? mount) true)
  (try
    (reset! (:render-tree mount) tree)
    (log-phase! mount :render)
    (when-let [body (render-body tree)]
      (binding [*rendering-mount* mount]
        (body mount)))
    (finally
      (reset! (:currently-rendering? mount) false)
      (swap! render-depth dec))))

(defn- unmount-thunk
  "Build the unmount thunk for `mount`. Idempotent (a second call on an
  already-unmounted mount is a no-op). Cascades to children first (React tears
  children down before their parent). Throws
  `:rf.error/sync-unmount-during-render` if called while a render is in flight
  ANYWHERE in the tree (the rf2-4l7t2 class) — keyed off the global
  `render-depth`, so a parent re-render that synchronously unmounts a separate
  sibling/child root trips the guard just as React does.

  `self-ref` is an atom that `mount-tree!` fills with the FINAL record (the one
  it `conj`'d into `active-mounts`). The thunk must `disj` THAT record, not the
  pre-`assoc` skeleton: a defrecord's equality includes the `:unmount-fn`
  field, so the skeleton (`:unmount-fn` nil) and the final record (`:unmount-fn`
  this thunk) are UNEQUAL — `(disj active-mounts skeleton)` would silently fail
  to remove the registered record and leak it for the adapter's lifetime."
  [self-ref]
  (fn unmount []
    (let [mount @self-ref]
      (when @(:mounted? mount)
        (when (rendering?)
          (error/throw-error!
            :rf.error/sync-unmount-during-render
            'rf/test-react-unmount
            (str "Attempted to synchronously unmount a root"
                 " while React was already rendering. React"
                 " 18+ raises the equivalent runtime error;"
                 " the Test-React adapter raises here so the"
                 " bug is caught at unit-test speed. Do not"
                 " unmount a root from inside a render body —"
                 " defer the unmount until rendering settles.")
            {:recovery :defer-the-unmount-until-render-settles
             :extra    {:mount-id (:id mount)}}))
        ;; Children-first teardown (mirrors React). Each child's own thunk runs
        ;; the same guard + cascade, so a deep tree unwinds leaf-upward.
        (doseq [child @(:children mount)]
          ((:unmount-fn child)))
        (log-phase! mount :will-unmount)
        (reset! (:mounted? mount) false)
        (reset! (:render-tree mount) nil)
        (swap! active-mounts disj mount)))
    nil))

(defn- mount-tree!
  "The internal mount seam. Builds the `MountedComponent`, runs the
  constructor → render → did-mount lifecycle, registers it in
  `active-mounts`, and returns the record itself (with its unmount thunk
  stored in the `:unmount-fn` field). Both the substrate `render` fn and the
  public `mount!` driver call this — `render` discards everything but the
  thunk; `mount!` returns the whole record so tests can inspect the log.

  If invoked while a parent's render body is running (i.e. via `mount-child!`,
  with `*rendering-mount*` bound) the new mount is appended to that parent's
  `:children`, so unmounting the parent later cascades to it."
  [render-tree]
  (let [self-ref (atom nil)   ; forward ref so the thunk can disj the FINAL record
        base     (->MountedComponent
                   (gensym "test-react-mount-")
                   (atom nil)   ; render-tree
                   (atom [])    ; lifecycle-log
                   (atom false) ; currently-rendering?
                   (atom true)  ; mounted?
                   (atom [])    ; children
                   nil)         ; unmount-fn — filled in below
        mount    (assoc base :unmount-fn (unmount-thunk self-ref))]
    ;; The thunk closes over `self-ref`, not `mount`, so it disj's the exact
    ;; record that was conj'd (see unmount-thunk docstring — equality includes
    ;; :unmount-fn, so the skeleton would not match the registered record).
    (reset! self-ref mount)
    (log-phase! mount :constructor)
    (run-render! mount render-tree)
    (log-phase! mount :did-mount)
    (swap! active-mounts conj mount)
    mount))

(defn mount-child!
  "Mount `render-tree` as a child of the component whose render body is
  currently executing. Intended to be called from inside an `:rf/component`
  render body (where `*rendering-mount*` is bound). Returns the child's
  `MountedComponent`. Throws if called outside a render body — a child must
  have a parent.

  This is the recursive-child seam: the child runs its own
  constructor → render → did-mount lifecycle (and may itself mount
  grandchildren), is appended to the parent's `:children`, and is torn down
  when the parent unmounts."
  [render-tree]
  (let [parent *rendering-mount*]
    (when (nil? parent)
      (error/throw-error!
        :rf.error/mount-child-outside-render
        'rf/test-react-mount-child!
        (str "mount-child! must be called from inside an"
             " :rf/component render body (a child needs a"
             " parent render in flight); *rendering-mount*"
             " was nil. Call mount-child! only within a render body.")
        {:recovery :call-from-inside-a-render-body}))
    (let [child (mount-tree! render-tree)]
      (swap! (:children parent) conj child)
      child)))

(defn- render [render-tree _mount-point _opts]
  ;; Spec 006 §`render` — return an unmount thunk. Under test-react the
  ;; `mount-point` arg is ignored (no DOM); `opts` is ignored (no
  ;; `:hydrate?` semantics).
  (:unmount-fn (mount-tree! render-tree)))

;; ---- render-to-string ------------------------------------------------------

(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install the render-tree → HTML fn used by render-to-string. Idempotent."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree opts]
  (if-let [emit @hiccup-emitter]
    (emit render-tree opts)
    (error/throw-error!
      :rf.error/no-hiccup-emitter-bound
      'rf/render-to-string
      (str "Test-React adapter has no built-in hiccup emitter; call "
           "set-hiccup-emitter! (or require re-frame.ssr) before "
           "render-to-string if a test needs HTML output.")
      ;; EP-0015 (rf2-uwqale): carry an EP-0015-safe SUMMARY of the
      ;; render-tree, never the raw tree (a thrown render diagnostic is
      ;; captured off-box before path-based projection can classify it).
      {:recovery :call-set-hiccup-emitter
       :extra    {:render-tree/summary (error/diag-value-summary render-tree)}})))

;; ---- frame-provider --------------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; No React context under test-react; tests thread frames explicitly
  ;; (or rely on the dynamic-var tier). Returning nil follows the
  ;; plain-atom precedent — substrate-adapter/register-context-provider
  ;; handles the nil case for absent-impl.
  nil)

;; ---- adapter disposal ------------------------------------------------------

(defn- dispose-adapter! []
  ;; Drain any still-mounted components so a test fixture that forgets to
  ;; unmount doesn't leak across cases. Per the rf2-4l7t2 lesson the drain
  ;; MUST tolerate the currently-rendering? guard — we set mounted? false
  ;; WITHOUT routing through the public `unmount!` (which would throw on a
  ;; stuck currently-rendering? cell) and log a :forced-teardown phase so the
  ;; test surface can spot drift.
  ;;
  ;; The hiccup-emitter is deliberately NOT cleared: it holds no host
  ;; resource, is re-derivable infrastructure installed once via the
  ;; `:reagent/set-hiccup-emitter!` chain at SSR ns-load, and is NOT
  ;; re-published on re-install. Nilling it here would make render-to-string
  ;; throw :rf.error/no-hiccup-emitter-bound across a dispose/reinstall
  ;; cycle. Matches plain-atom's dispose-adapter! (a no-op that leaves the
  ;; emitter alone).
  ;;
  ;; rf2-ghfkkk: this ALSO walks every live frame's per-frame sub-cache and
  ;; resets it, the externally-visible counterpart of the React adapters'
  ;; `spine/dispose-frame-sub-caches!` (Spec 006 §Adapter disposal lifecycle
  ;; MUST 1 + §Lifetime contract — frame disposal §Adapter symmetry). The
  ;; React-adapter walk is CLJS-only (the spine is CLJS-only); test-react is
  ;; CLJC, so it routes through the CLJC-safe shared helper
  ;; `subs-cache/clear-all-frame-sub-caches!` instead. Why this matters even
  ;; though `make-derived-value` holds no host resource (plain IDeref reify,
  ;; so the per-reaction dispose is a CLJS no-op): the CACHE ENTRIES + REF-
  ;; COUNTS live on the FRAME, not the reaction, and a reaction that never
  ;; auto-disposes leaves its `{:reaction … :ref-count n}` slot in the frame's
  ;; sub-cache. Without this reset a test process carries stale slots + stale
  ;; ref-counts across a dispose/reinstall cycle — a later subscribe reads the
  ;; stale cached value instead of recomputing from fresh state, breaking
  ;; isolation. The reset clears the slots so the next subscribe is a fresh
  ;; cache miss that recomputes. (The helper fires BEFORE the active-mounts
  ;; drain below, but order is immaterial — they touch disjoint state.)
  ;;
  ;; Drain flat over active-mounts: children are themselves registered in
  ;; active-mounts (mount-tree! adds every mount, parent or child), so a flat
  ;; walk drains the whole forest without recursing through :children. We do
  ;; NOT route through the public unmount thunk (it would throw on the
  ;; currently-rendering? / render-depth guard) — forced teardown is the
  ;; escape hatch the guard deliberately cannot block.
  ;;
  ;; rf2-ghfkkk — dispose every live frame's sub-cache (the reactive-
  ;; subscription MUST). CLJC-safe shared helper, equal semantics to the
  ;; React adapters' spine walk.
  (subs-cache/clear-all-frame-sub-caches!)
  (doseq [mount @active-mounts]
    (when @(:mounted? mount)
      (log-phase! mount :forced-teardown)
      (reset! (:mounted? mount) false)
      (reset! (:render-tree mount) nil)))
  (reset! active-mounts #{})
  ;; Reset the global render depth. `run-render!`'s `finally` already restores
  ;; it on the normal + guard-throw paths; this is belt-and-braces so a test
  ;; that bypassed run-render! by hand cannot leak a stuck "rendering" state
  ;; into the next case.
  (reset! render-depth 0)
  ;; Reset the monotonic phase order-key so each test's lifecycle log starts
  ;; from a small, deterministic sequence. Only relative order within a single
  ;; cascade is load-bearing, so this is hygiene, not correctness — it mirrors
  ;; the render-depth reset above.
  (reset! phase-seq 0)
  nil)

(def adapter
  "The Test-React adapter map. Pass to `(rf/init! ...)` in a unit-test
  fixture:

      (require '[re-frame.adapter.test-react :as test-react])
      (rf/init! test-react/adapter)

  Per Spec 006 §The adapter API contract — implements the 6 required +
  2-of-3 optional (no `flush-render!`) + 1 lifecycle fn. The
  reactive-container half is shared with plain-atom; the
  render half is the novel surface (class-3 lifecycle simulation with the
  `:currently-rendering?` invariant). See `mount!` / `trigger-update!` /
  `unmount!` for the test driver helpers."
  {:kind                      :rf.adapter/test-react
   :make-state-container      atom-container/make-state-container
   :read-container            atom-container/read-container
   :replace-container!        atom-container/replace-container!
   :subscribe-container       atom-container/subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; ---- public driver / inspection helpers -----------------------------------
;;
;; These are the surface tests reach for. They are NOT part of the
;; substrate-adapter contract — they are test-driver utilities scoped to
;; this adapter. Other adapters expose nothing analogous (real React drives
;; the lifecycle from JS-side; here the test owns the clock).
;;
;; ---- two entry points: substrate :render vs public mount! ----
;;
;; The substrate contract's :render fn (above, registered in `adapter`)
;; returns an unmount thunk and nothing else. Test code typically wants
;; the lifecycle log + the mount record, so this adapter exposes `mount!`
;; as the public driver. Both call the internal `mount-tree!` helper;
;; the substrate :render discards the record and returns just the thunk,
;; while `mount!` returns the whole `MountedComponent` record (whose
;; `:unmount-fn` is the same thunk). Tests should reach for `mount!`,
;; not `(substrate-adapter/render …)`, because they want the record's
;; `:lifecycle-log` for assertions.

(defn mount!
  "Mount `render-tree` (a hiccup vector or any data the test treats as the
  rendered output) under the installed Test-React adapter. Returns the
  `MountedComponent` record carrying the lifecycle log and the unmount
  thunk. Throws if a non-test-react adapter is installed.

  The installed-adapter check is `substrate-adapter/same-adapter?`
  (stable-token routing, rf2-dkl5z1), NOT raw object identity — so a copied
  or wrapped Test-React adapter map (same canonical `:rf.adapter/test-react`
  `:kind`, e.g. one `assoc`'d with an instrumentation wrapper) is still
  accepted, matching the routed hooks' acceptance of the same copy."
  [render-tree]
  (when-not (substrate-adapter/same-adapter? adapter (substrate-adapter/current-adapter-spec))
    (error/throw-error!
      :rf.error/test-react-not-installed
      'rf/test-react-mount!
      (str "test-react/mount! requires the Test-React adapter"
           " to be the (rf/init!)-installed adapter; got "
           (substrate-adapter/current-adapter)
           ". Call (rf/init! test-react/adapter) before mount!.")
      {:recovery :install-the-test-react-adapter}))
  (mount-tree! render-tree))

(defn trigger-update!
  "Simulate a React re-render of `mount` with `new-render-tree` as the next
  render output. Records a `:did-update` phase entry in the lifecycle log.
  Throws if the mount has already been unmounted."
  [mount new-render-tree]
  (when-not @(:mounted? mount)
    (error/throw-error!
      :rf.error/update-after-unmount
      'rf/test-react-trigger-update!
      (str "trigger-update! called on a mount that has already been "
           "unmounted; trigger updates only on a still-mounted root.")
      {:recovery :trigger-only-on-a-mounted-root
       :extra    {:mount-id (:id mount)}}))
  (run-render! mount new-render-tree)
  (log-phase! mount :did-update)
  mount)

(defn unmount!
  "Unmount `mount`, cascading to its children first (React tears children down
  before their parent). Records a `:will-unmount` phase entry per torn-down
  mount. Throws `:rf.error/sync-unmount-during-render` if called while a render
  is in flight anywhere in the tree (the rf2-4l7t2 class) — including the
  organic case where a parent's render body synchronously unmounts a separate
  sibling/child root. Idempotent: a second call on the same mount is a no-op."
  [mount]
  ((:unmount-fn mount))
  nil)

(defn lifecycle-log
  "Return the lifecycle log for `mount` — a vector of `{:phase ... :seq n}`
  entries, in the order they fired. `:seq` is a process-global monotonic
  order-key (see `phase-seq`), the discriminating key for teardown-ORDER
  assertions — a strict `<` over two phases' seqs reflects their real firing
  order. Test assertions typically map over `:phase` and compare to a canonical
  sequence, and read `:seq` to assert cross-mount teardown order."
  [mount]
  @(:lifecycle-log mount))

(defn current-render-tree
  "Return the most recently rendered tree for `mount`, or `nil` after
  unmount."
  [mount]
  @(:render-tree mount))

(defn mounted-roots
  "Return all currently-mounted `MountedComponent`s under this adapter — the
  whole live forest, parents AND recursively-mounted children (every mount,
  child or not, is registered in `active-mounts`). Useful for tests asserting
  balanced mount/unmount counts: an unbalanced subscribe/dispose or a leaked
  child shows up as a non-zero count after the test's teardown should have
  drained everything."
  []
  (filter (comp deref :mounted?) @active-mounts))

(defn children
  "Return the live (still-mounted) child `MountedComponent`s a `mount` mounted
  from inside its own render body via `mount-child!`, in mount order. Useful
  for tests that walk the tree or assert a parent tore its children down."
  [mount]
  (filterv (comp deref :mounted?) @(:children mount)))

;; ---- late-bind hook routing -----------------------------------------------
;; The Test-React adapter publishes only the React-context-tier fallback for
;; `:adapter/current-frame` — there is no React context, so the hook drops
;; through to `frame/current-frame` (the dynamic-var tier). Other
;; reactive-substrate hooks (`:adapter/ratom` / `:adapter/make-reaction` /
;; `:adapter/after-render`) are intentionally NOT published: production code
;; paths that reach for them under a non-React adapter indicate a
;; misconfiguration that should surface, not be papered over.

(substrate-adapter/route-hook! adapter :adapter/current-frame
  (fn test-react-current-frame [] (frame/current-frame))
  #(frame/current-frame))

;; SSR emitter install — chains onto the existing :reagent/set-hiccup-emitter!
;; late-bind hook so a single `(require '[re-frame.ssr])` wires
;; render-to-string for whichever adapter ends up (rf/init!)-installed.
(late-bind/chain-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)
