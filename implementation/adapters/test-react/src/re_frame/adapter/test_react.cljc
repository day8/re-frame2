(ns re-frame.adapter.test-react
  "Test-only adapter that simulates the React class-3 lifecycle in pure CLJC.
  It catches lifecycle ordering, unbalanced mount/disposal, and synchronous
  unmount-during-render failures without React, a DOM, or jsdom.

  The atom-backed container operations are shared with plain-atom. Tests own
  the render clock through `trigger-update!`, so this adapter intentionally
  provides no `flush-render!`. Every lifecycle transition is recorded in a
  per-mount log. `unmount!` throws while any render is in flight, matching
  React's cross-root guard.

  A render tree may declare `{:rf/component (fn [mount] ...)}`. That body runs
  during render and may call `mount-child!`, allowing tests to exercise nested
  lifecycle and cross-root unmount ordering. There is no automatic rerender on
  app-db change, React context traversal, or DOM source annotation.

  Usage:

      (require '[re-frame.core :as rf]
               '[re-frame.adapter.test-react :as test-react])

      (rf/init! test-react/adapter)

      (let [m (test-react/mount! [my-view {:title \"hi\"}])]
        (rf/dispatch-sync [:set-title \"bye\"])
        (test-react/trigger-update! m [my-view {:title \"bye\"}])
        (test-react/unmount! m)
        (mapv :phase (test-react/lifecycle-log m)))
      ;; => [:constructor :render :did-mount :render :did-update :will-unmount]"
  (:require [re-frame.error :as rf.error]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.atom-container :as rf.substrate.atom-container]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.frame :as rf.frame]))

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
  ;; IDeref only: the value owns no host resource and has no CLJS disposal
  ;; protocol. Frame sub-cache entries still retain reactions and ref-counts,
  ;; so adapter disposal clears every live frame cache.
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
;;   :mounted?        — atom<boolean>; false after unmount. Updates then throw;
;;                      repeated unmounts are no-ops.
;;   :children        — atom<vector<MountedComponent>>; child roots this mount
;;                      mounted from inside its own render body via
;;                      `mount-child!`. Unmounting a parent cascades to its
;;                      children (will-unmount fires children-first, mirroring
;;                      React's child-before-parent teardown order).
;;   :unmount-fn      — the unmount thunk for this mount; `unmount!` calls it.

(defrecord ^:no-doc MountedComponent
  [id render-tree lifecycle-log mounted? children unmount-fn])

;; All live mounts; `dispose-adapter!` walks this to drain.
(defonce ^:private active-mounts (atom #{}))

;; React rejects synchronous unmount while any root is rendering. A global
;; counter catches cross-root unmounts and restores correctly across nested
;; child renders; a per-mount flag would miss sibling-root failures.
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

;; A counter, rather than millisecond timestamps, gives deterministic ordering
;; when an entire teardown cascade completes within one clock tick.
(defonce ^:private phase-seq (atom 0))

;; FIXED ARITY by design (rf2-6r9j.83). The entry shape IS the contract
;; `lifecycle-log` publishes — exactly `{:phase … :seq …}`, nothing else — so
;; the literal map is appended directly. It previously took a variadic
;; `& {:as extras}` merged on TOP of the canonical keys; no call site in the
;; adapter's whole history ever supplied one, and because the merge landed
;; last it was an escape hatch that could overwrite `:phase` or `:seq` and
;; contradict the very ordering invariant the log promises.
(defn- log-phase! [mount phase]
  (swap! (:lifecycle-log mount)
         conj {:phase phase :seq (swap! phase-seq inc)}))

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
  render depth for the duration, records a :render phase entry, stores the
  tree, and — if the tree declares an imperative render body (`:rf/component`)
  — invokes that body with `mount` bound as `*rendering-mount*` so it can mount
  children or issue a (guard-tripping) re-entrant unmount. Throws from the
  render body are NOT caught — they propagate to the caller (React 18+ unmounts
  the root); the render depth is restored on unwind via `finally`."
  [mount tree]
  (swap! render-depth inc)
  (try
    (reset! (:render-tree mount) tree)
    (log-phase! mount :render)
    (when-let [body (render-body tree)]
      (binding [*rendering-mount* mount]
        (body mount)))
    (finally
      (swap! render-depth dec))))

(defn- unmount-thunk
  "Build the unmount thunk for `mount`. Idempotent (a second call on an
  already-unmounted mount is a no-op). Cascades to children first (React tears
  children down before their parent). Throws
  `:rf.error/sync-unmount-during-render` if called while a render is in flight
  anywhere in the tree — keyed off the global
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
          (rf.error/throw-error!
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

(defn- force-teardown-record!
  "Forcibly tear a single mount record down, bypassing the public
  render-depth unmount guard. Records a `:forced-teardown` phase, flips
  `mounted?` off, clears the render tree, and removes the record from
  `active-mounts`. Idempotent via the `mounted?` guard.

  This is the ONE place the forced-teardown steps live, shared by
  `dispose-adapter!` (a flat drain of the whole live forest) and the
  failed-render rollback in `mount-tree!` (a recursive subtree drain). Sharing
  it keeps the two forced paths from drifting — e.g. one clearing a field the
  other forgets. Forced teardown is the escape hatch the render-depth guard
  deliberately cannot block; it must NOT route through the public unmount thunk
  (which correctly rejects unmount-during-render)."
  [mount]
  (when @(:mounted? mount)
    (log-phase! mount :forced-teardown)
    (reset! (:mounted? mount) false)
    (reset! (:render-tree mount) nil)
    (swap! active-mounts disj mount)))

(defn- force-teardown-descendants!
  "Force-tear every descendant of `parent` down GRANDCHILDREN-first, mirroring
  React's leaf-upward teardown. The descendants may be speculative mounts from
  a render that threw or pre-existing children of an already-committed mount;
  this function deliberately leaves `parent` itself untouched.

  Used by `mount-tree!`'s failed-INITIAL-mount rollback (where the parent never
  registers) and by `force-teardown-subtree!` (which tears the live subtree
  root down after its descendants)."
  [parent]
  (doseq [child @(:children parent)]
    (force-teardown-descendants! child)
    (force-teardown-record! child)))

(defn- force-teardown-subtree!
  "Force-tear a whole LIVE subtree down GRANDCHILDREN-first: drain every
  descendant of `mount` via `force-teardown-descendants!`, then force-tear
  the subtree root record itself via `force-teardown-record!`. Every node — the
  root AND all descendants (pre-existing children as well as any a failed render
  speculatively mounted) — leaves `active-mounts` with its render tree cleared.

  Composes the SAME two forced-teardown primitives that back adapter disposal
  and the initial-mount rollback, so the paths cannot drift. Used by
  `trigger-update!`'s failed-UPDATE path: an uncaught update render error
  unmounts the whole already-committed root, mirroring React 18+ (see
  `run-render!`'s docstring — a throwing render propagates and React unmounts
  the root). By contrast, `force-teardown-descendants!` deliberately drains
  DESCENDANTS only and leaves the supplied parent for its caller to handle."
  [mount]
  (force-teardown-descendants! mount)
  (force-teardown-record! mount))

(defn- mount-tree!
  "The internal mount seam. Builds the `MountedComponent`, runs the
  constructor → render → did-mount lifecycle, registers it in
  `active-mounts`, and returns the record itself (with its unmount thunk
  stored in the `:unmount-fn` field). Both the substrate `render` fn and the
  public `mount!` driver call this — `render` discards everything but the
  thunk; `mount!` returns the whole record so tests can inspect the log.

  If invoked while a parent's render body is running (i.e. via `mount-child!`,
  with `*rendering-mount*` bound) the new mount is appended to that parent's
  `:children`, so unmounting the parent later cascades to it.

  Initial mount is TRANSACTIONAL: if `run-render!` throws (the render body
  raised), any children the failed render already mounted are rolled back
  before the original exception is rethrown — a failed mount registers nothing
  and leaks nothing."
  [render-tree]
  (let [self-ref (atom nil)   ; forward ref so the thunk can disj the FINAL record
        base     (->MountedComponent
                   (gensym "test-react-mount-")
                   (atom nil)   ; render-tree
                   (atom [])    ; lifecycle-log
                   (atom true)  ; mounted?
                   (atom [])    ; children
                   nil)         ; unmount-fn — filled in below
        mount    (assoc base :unmount-fn (unmount-thunk self-ref))]
    ;; The thunk closes over `self-ref`, not `mount`, so it disj's the exact
    ;; record that was conj'd (see unmount-thunk docstring — equality includes
    ;; :unmount-fn, so the skeleton would not match the registered record).
    (reset! self-ref mount)
    (log-phase! mount :constructor)
    (try
      (run-render! mount render-tree)
      (catch #?(:clj Throwable :cljs :default) render-error
        ;; The initial render threw. Any children the render speculatively
        ;; mounted via `mount-child!` already registered in `active-mounts`
        ;; (with `mounted?` true) and were recorded under this mount's
        ;; `:children`, but this parent never reaches the `active-mounts conj`
        ;; below — so those children would leak, unreachable via the public
        ;; unmount surface. Roll them back so the failed initial mount is
        ;; transactional. `run-render!` already restored `render-depth` through
        ;; its own `finally`, so the rollback runs with the guard state correct.
        ;; Run the rollback in its OWN try so a defect there can never MASK the
        ;; render exception — the render error is what the caller must see.
        (try
          (force-teardown-descendants! mount)
          (catch #?(:clj Throwable :cljs :default) _rollback-error
            nil))
        (throw render-error)))
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
      (rf.error/throw-error!
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
    (rf.error/throw-error!
      :rf.error/no-hiccup-emitter-bound
      'rf/render-to-string
      (str "Test-React adapter has no built-in hiccup emitter; call "
           "set-hiccup-emitter! (or require re-frame.ssr) before "
           "render-to-string if a test needs HTML output.")
      ;; Diagnostics may be captured before path-based classification, so never
      ;; attach the raw render tree.
      {:recovery :call-set-hiccup-emitter
       :extra    {:render-tree/summary (rf.error/diag-value-summary render-tree)}})))

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
  ;; unmount does not leak across cases. Forced teardown bypasses the public
  ;; render-depth guard and records a distinct lifecycle phase.
  ;;
  ;; The hiccup-emitter is deliberately NOT cleared: it holds no host
  ;; resource and is re-derivable infrastructure installed via the
  ;; `:reagent/set-hiccup-emitter!` chain at SSR ns-load. When re-frame.ssr is
  ;; loaded, install-adapter! ALSO replays the durable emitter on re-install
  ;; (rf2-vxgfnd.204), but this adapter is the LOCAL-TEST fixture and is often
  ;; exercised WITHOUT re-frame.ssr on the classpath (the standalone
  ;; `clojure -M:test` run depends on core + test-quiet only) — there the
  ;; durable slot is unset and the replay no-ops, so leaving this atom intact
  ;; is the only thing keeping render-to-string armed across a dispose/reinstall
  ;; cycle. Nilling it here would make render-to-string throw
  ;; :rf.error/no-hiccup-emitter-bound in that case. Matches plain-atom's
  ;; dispose-adapter! (a no-op that leaves the emitter alone).
  ;;
  ;; Derived values have no CLJS disposal protocol, but their cache entries and
  ;; ref-counts live on frames. Clear every frame cache so reinstalling the
  ;; adapter cannot read a stale subscription value.
  ;;
  ;; Drain flat over active-mounts: children are themselves registered in
  ;; active-mounts (mount-tree! adds every mount, parent or child), so a flat
  ;; walk drains the whole forest without recursing through :children.
  ;; `force-teardown-record!` — the SAME primitive the failed-render rollback
  ;; uses — performs the per-record teardown, so disposal and rollback cannot
  ;; drift. It does NOT route through the public unmount thunk (which would
  ;; throw on the render-depth guard) — forced teardown is the escape hatch the
  ;; guard deliberately cannot block. The trailing `reset! active-mounts #{}` is
  ;; belt-and-suspenders: the primitive already disj'd each drained record.
  ;;
  (rf.subs.cache/clear-all-frame-sub-caches!)
  (doseq [mount @active-mounts]
    (force-teardown-record! mount))
  (reset! active-mounts #{})
  ;; Normal renders restore this in finally; disposal also repairs test code
  ;; that bypassed the public driver.
  (reset! render-depth 0)
  ;; Only relative phase order within a case matters.
  (reset! phase-seq 0)
  nil)

(def adapter
  "The Test-React adapter map. Pass to `(rf/init! ...)` in a unit-test
  fixture:

      (require '[re-frame.adapter.test-react :as test-react])
      (rf/init! test-react/adapter)

  The reactive-container half is shared with plain-atom; this adapter adds the
  class-3 lifecycle simulator and its global unmount-during-render guard. See
  `mount!`, `trigger-update!`, and `unmount!` for the test driver."
  {:kind                      :rf.adapter/test-react
   :make-state-container      rf.substrate.atom-container/make-state-container
   :read-container            rf.substrate.atom-container/read-container
   :replace-container!        rf.substrate.atom-container/replace-container!
   :subscribe-container       rf.substrate.atom-container/subscribe-container
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

  The installed-adapter check uses the stable adapter kind, not raw object
  identity, so a copied
  or wrapped Test-React adapter map (same canonical `:rf.adapter/test-react`
  `:kind`, e.g. one `assoc`'d with an instrumentation wrapper) is still
  accepted, matching the routed hooks' acceptance of the same copy."
  [render-tree]
  (when-not (rf.substrate.adapter/same-adapter? adapter (rf.substrate.adapter/current-adapter-spec))
    (rf.error/throw-error!
      :rf.error/test-react-not-installed
      'rf/test-react-mount!
      (str "test-react/mount! requires the Test-React adapter"
           " to be the (rf/init!)-installed adapter; got "
           (rf.substrate.adapter/current-adapter)
           ". Call (rf/init! test-react/adapter) before mount!.")
      {:recovery :install-the-test-react-adapter}))
  (mount-tree! render-tree))

(defn trigger-update!
  "Simulate a React re-render of `mount` with `new-render-tree` as the next
  render output. On success records a `:did-update` phase entry in the
  lifecycle log. Throws if the mount has already been unmounted.

  Update honors React 18+'s uncaught-render-error semantics: if the update
  render body throws, the WHOLE live root is unmounted — `mount` and its entire
  child subtree (pre-existing children AND any this attempt speculatively
  mounted) are force-torn-down grandchildren-first, evicted from `active-mounts`
  with their render trees cleared — and the ORIGINAL exception is then rethrown.
  A failed attempt publishes no `:did-update` and never leaves the throwing
  candidate exposed as a committed tree. This matches `run-render!`'s documented
  contract (\"React 18+ unmounts the root\") and the failed-INITIAL-mount
  transaction (`mount-tree!`): a render that throws leaves no half-committed
  lifecycle state — the test double does NOT silently preserve the prior tree,
  because real React would tear the root down.

  The sync-unmount-during-render guard is preserved during the render itself
  (an unmount issued from the update body still trips it organically);
  `run-render!`'s own `finally` restores `render-depth` on unwind, so the forced
  teardown (which bypasses the render-depth guard) runs with the guard state
  already correct."
  [mount new-render-tree]
  (when-not @(:mounted? mount)
    (rf.error/throw-error!
      :rf.error/update-after-unmount
      'rf/test-react-trigger-update!
      (str "trigger-update! called on a mount that has already been "
           "unmounted; trigger updates only on a still-mounted root.")
      {:recovery :trigger-only-on-a-mounted-root
       :extra    {:mount-id (:id mount)}}))
  (try
    (run-render! mount new-render-tree)
    (catch #?(:clj Throwable :cljs :default) render-error
      ;; Uncaught update render error → React 18+ unmounts the whole root. Tear
      ;; the entire live root subtree down and rethrow the ORIGINAL error. Run
      ;; the teardown in its OWN try so a defect there can never MASK the render
      ;; exception — that error is what the caller must see. `run-render!`'s
      ;; finally already restored `render-depth`, so forced teardown runs with
      ;; the guard state correct.
      (try
        (force-teardown-subtree! mount)
        (catch #?(:clj Throwable :cljs :default) _teardown-error
          nil))
      (throw render-error)))
  (log-phase! mount :did-update)
  mount)

(defn unmount!
  "Unmount `mount`, cascading to its children first (React tears children down
  before their parent). Records a `:will-unmount` phase entry per torn-down
  mount. Throws `:rf.error/sync-unmount-during-render` if called while a render
  is in flight anywhere in the tree, including the
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

(defn mounted-components
  "Return all currently-mounted `MountedComponent`s under this adapter — the
  whole live forest, parents AND recursively-mounted children (every mount,
  child or not, is registered in `active-mounts`). Useful for tests asserting
  balanced mount/unmount counts: an unbalanced subscribe/dispose or a leaked
  child shows up as a non-zero count after the test's teardown should have
  drained everything."
  []
  (filter (comp deref :mounted?) @active-mounts))

(defn ^:deprecated mounted-roots
  "Deprecated compatibility alias for `mounted-components`.

  Despite its historical name, the result includes descendants as well as
  roots. New callers should use `mounted-components`."
  []
  (mounted-components))

(defn mounted-children
  "Return the live (still-mounted) child `MountedComponent`s a `mount` mounted
  from inside its own render body via `mount-child!`, in mount order. Useful
  for tests that walk the tree or assert a parent tore its children down."
  [mount]
  (filterv (comp deref :mounted?) @(:children mount)))

(defn ^:deprecated children
  "Deprecated compatibility alias for `mounted-children`.

  Despite its historical name, the result contains only children that remain
  mounted; the record's retained `:children` field can also contain dead mounts."
  [mount]
  (mounted-children mount))

;; ---- late-bind hook routing -----------------------------------------------
;; The Test-React adapter publishes only the React-context-tier fallback for
;; `:adapter/current-frame` — there is no React context, so the hook drops
;; through to `frame/current-frame` (the dynamic-var tier). Other
;; reactive-substrate hooks (`:adapter/ratom` / `:adapter/make-reaction` /
;; `:adapter/after-render`) are intentionally NOT published: production code
;; paths that reach for them under a non-React adapter indicate a
;; misconfiguration that should surface, not be papered over.

(rf.substrate.adapter/route-hook! adapter :adapter/current-frame
  (fn test-react-current-frame [] (rf.frame/current-frame))
  #(rf.frame/current-frame))

;; SSR emitter install — chains onto the existing :reagent/set-hiccup-emitter!
;; late-bind hook so a single `(require '[re-frame.ssr])` wires
;; render-to-string for whichever adapter ends up (rf/init!)-installed.
(rf.late-bind/chain-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)
