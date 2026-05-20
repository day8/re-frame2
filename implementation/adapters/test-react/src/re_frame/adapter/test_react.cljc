(ns re-frame.adapter.test-react
  "The Test-React adapter — simulates React class-3 lifecycle in pure
  CLJC, no React, no DOM, no jsdom. Purpose: catch React-lifecycle-
  driven bugs (stale closures, unbalanced subscribe/dispose, sync
  unmount inside render — the rf2-4l7t2 class) at unit-test speed.

  Per Spec 006 §The adapter API contract — implements the same
  nine-fn contract as the production adapters (Reagent / UIx / Helix /
  plain-atom). The reactive-container half is shared shape with
  plain-atom (a `clojure.core/atom` per slot, derived-values recompute
  on deref). The render half is the novel surface — `render` instantiates
  a `MountedComponent` that records every lifecycle transition into a
  per-mount log, throws on illegal transitions (e.g. synchronous
  `unmount!` while `:currently-rendering?` is true, mirroring React 18+),
  and exposes inspection helpers (`lifecycle-log`, `current-render-tree`,
  `mounted-roots`) for tests to assert against.

  Status: minimal viable skeleton (rf2-gqyqv worker; placeholder bead).
  The skeleton ships:

    - The nine-fn substrate-adapter contract, JVM + CLJS runnable.
    - A `class-3` lifecycle simulator with mount / update / unmount
      transitions plus a `:currently-rendering?` invariant.
    - `lifecycle-log` / `mounted-roots` / `current-render-tree`
      inspection helpers.
    - `mount!` / `trigger-update!` / `unmount!` driver helpers for
      tests to walk a component through its lifecycle.

  Out of scope for this skeleton (defer to follow-on beads):
    - Walking arbitrary hiccup to instantiate nested child components
      (the current simulator treats the render tree as opaque data;
      children are NOT recursively mounted). The `class-3` invariants
      (one root, one mount, didMount-after-render, willUnmount-before-
      teardown) catch the rf2-4l7t2 class without recursion.
    - Reactive subscription tracking that auto-re-renders on
      app-db change. The test driver calls `trigger-update!` to
      simulate a re-render after a dispatch settles.
    - React-context provider traversal. Frame-routing under this
      adapter is via the dynamic-var tier; the React-context tier
      is degenerate (no React).

  Usage skeleton:

      (require '[re-frame.core :as rf]
               '[re-frame.adapter.test-react :as test-react])

      (rf/init! test-react/adapter)

      (let [root (test-react/mount! [my-view {:title \"hi\"}])]
        (rf/dispatch-sync [:set-title \"bye\"])
        (test-react/trigger-update! root)
        (is (= [my-view {:title \"bye\"}]
               (test-react/current-render-tree root)))
        (test-react/unmount! root)
        (is (= [:constructor :did-mount :did-update :will-unmount]
               (mapv :phase (test-react/lifecycle-log root))))) "
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.frame :as frame]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reactive-container half (shared shape with plain-atom) ----------------

(defn- make-state-container [initial-value]
  (atom initial-value))

(defn- read-container [container]
  @container)

(defn- replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn- subscribe-container [container on-change]
  ;; Optional — the simulator does not auto-re-render on container
  ;; change; tests drive re-renders explicitly via `trigger-update!`.
  ;; The watch is still useful for tests that want to assert which
  ;; replaces fired (e.g. drain balanced).
  (let [k (gensym "rf-test-react-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

(defn- make-derived-value [source-containers compute-fn]
  ;; Recompute on every deref. No caching: the test surface only ever
  ;; runs a sub a handful of times per test case.
  (reify
    #?(:clj clojure.lang.IDeref :cljs IDeref)
    (#?(:clj deref :cljs -deref) [_]
      (apply compute-fn (map deref source-containers)))))

;; ---- render half — the class-3 lifecycle simulator -------------------------
;;
;; Each `render` call produces a `MountedComponent` record. The record
;; carries:
;;
;;   :id              — gensym tag (for log readability)
;;   :render-tree     — atom holding the currently-rendered tree
;;   :lifecycle-log   — atom holding a vector of {:phase ... :at ms}
;;                      entries; the test driver inspects this to
;;                      assert ordering / counts.
;;   :currently-rendering? — atom<boolean>; true while a render is
;;                      in flight. The simulator THROWS on attempts
;;                      to unmount! synchronously while this is true,
;;                      mirroring React 18+'s "Attempted to
;;                      synchronously unmount a root while React was
;;                      already rendering" guard (the rf2-4l7t2 class).
;;   :mounted?        — atom<boolean>; false after `unmount!`. The
;;                      simulator THROWS on attempts to `trigger-update!`
;;                      or `unmount!` after teardown.

(defrecord ^:no-doc MountedComponent
  [id render-tree lifecycle-log currently-rendering? mounted?])

;; All live mounts; `dispose-adapter!` walks this to drain.
(defonce ^:private active-mounts (atom #{}))

(defn- now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- log-phase! [mount phase & {:as extras}]
  (swap! (:lifecycle-log mount)
         conj (merge {:phase phase :at (now-ms)} extras)))

(defn- run-render!
  "Set the :currently-rendering? flag, record a :render phase entry,
  store the render-tree, clear the flag. Throws from inside the render
  body are NOT caught — they propagate to the caller (the simulator
  cannot recover from a render throw in any meaningful way; React 18+
  unmounts the root)."
  [mount tree]
  (reset! (:currently-rendering? mount) true)
  (try
    (reset! (:render-tree mount) tree)
    (log-phase! mount :render)
    (finally
      (reset! (:currently-rendering? mount) false))))

(defn- render [render-tree _mount-point _opts]
  ;; Spec 006 §`render` — return an unmount thunk. Under test-react
  ;; the `mount-point` arg is ignored (no DOM); `opts` is ignored
  ;; (no `:hydrate?` semantics).
  (let [mount (->MountedComponent
                (gensym "test-react-mount-")
                (atom nil)
                (atom [])
                (atom false)
                (atom true))]
    (log-phase! mount :constructor)
    (run-render! mount render-tree)
    (log-phase! mount :did-mount)
    (swap! active-mounts conj mount)
    (fn unmount []
      (when @(:mounted? mount)
        (when @(:currently-rendering? mount)
          (throw (ex-info ":rf.error/sync-unmount-during-render"
                          {:where    'rf/test-react-unmount
                           :recovery :no-recovery
                           :reason   (str "Attempted to synchronously unmount a root"
                                          " while a render is in flight. React 18+"
                                          " raises the equivalent runtime error; the"
                                          " Test-React adapter raises here so the bug"
                                          " is caught at unit-test speed. See"
                                          " rf2-4l7t2 for the production manifestation.")
                           :mount-id (:id mount)})))
        (log-phase! mount :will-unmount)
        (reset! (:mounted? mount) false)
        (reset! (:render-tree mount) nil)
        (swap! active-mounts disj mount))
      nil)))

;; ---- render-to-string ------------------------------------------------------

(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install the render-tree → HTML fn used by render-to-string. Idempotent."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree _opts]
  (if-let [emit @hiccup-emitter]
    (emit render-tree _opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason "Test-React adapter has no built-in hiccup emitter; call set-hiccup-emitter! if a test needs HTML output."
                     :render-tree render-tree}))))

;; ---- frame-provider --------------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; No React context under test-react; tests thread frames explicitly
  ;; (or rely on the dynamic-var tier). Returning nil follows the
  ;; plain-atom precedent — substrate-adapter/register-context-provider
  ;; handles the nil case for absent-impl.
  nil)

;; ---- adapter disposal ------------------------------------------------------

(defn- dispose-adapter! []
  ;; Drain any still-mounted components so a test fixture that forgets
  ;; to unmount doesn't leak across cases. Per the rf2-4l7t2 lesson the
  ;; drain MUST tolerate the currently-rendering? guard — we set
  ;; mounted? false WITHOUT routing through the public `unmount!`
  ;; (which would throw on a stuck currently-rendering? cell) and log
  ;; a :forced-teardown phase so the test surface can spot drift.
  (doseq [m @active-mounts]
    (when @(:mounted? m)
      (log-phase! m :forced-teardown)
      (reset! (:mounted? m) false)
      (reset! (:render-tree m) nil)))
  (reset! active-mounts #{})
  (reset! hiccup-emitter nil)
  nil)

(def adapter
  "The Test-React adapter map. Pass to `(rf/init! ...)` in a unit-test
  fixture:

      (require '[re-frame.adapter.test-react :as test-react])
      (rf/init! test-react/adapter)

  Per Spec 006 §The adapter API contract — implements the nine-fn
  contract. The reactive-container half mirrors plain-atom; the
  render half is the novel surface (class-3 lifecycle simulation
  with `:currently-rendering?` invariant). See `mount!` /
  `trigger-update!` / `unmount!` for the test driver helpers."
  {:kind                      :rf.adapter/test-react
   :make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; ---- public driver / inspection helpers -----------------------------------
;;
;; These are the surface tests reach for. They are NOT part of the
;; substrate-adapter contract — they are test-driver utilities scoped
;; to this adapter. Other adapters do not expose anything analogous
;; (because real React drives the lifecycle from JS-side; here the
;; test owns the clock).

(defn- ^:no-doc mount-record-from-unmount-fn
  "The substrate adapter's `render` returns an unmount thunk;
  `mount!` needs the MountedComponent record so tests can inspect
  the log. We capture the record via a side-channel: `render`
  itself swaps the record into active-mounts; `mount!` reads the
  most-recently-added entry and returns the unmount-thunk-paired
  record. This is a minimal-skeleton hack — a follow-on bead may
  formalise this by widening the substrate `render` return shape
  (under a debug-only seam) or by switching to a per-render
  envelope return value."
  [unmount-fn]
  ;; The mount we just added is the only one whose `:mounted?` is
  ;; true AND that closes over `unmount-fn`. Since closures aren't
  ;; introspectable in CLJ, we use the active-mounts order: the
  ;; most-recently-conj'd is the freshest add for non-empty sets.
  ;; (For unit-test use this is sufficient; tests mount one root at
  ;; a time per scope.)
  (let [mounts @active-mounts]
    (when (seq mounts)
      ;; `set` has no order; we tag each MountedComponent with a
      ;; monotonic id at construction and pick the max-id mount
      ;; whose :mounted? is true.
      (->> mounts
           (filter (comp deref :mounted?))
           (sort-by (comp str :id))
           last))))

(defn mount!
  "Mount `render-tree` (a hiccup vector or any data the test treats as
  the rendered output) under the installed Test-React adapter. Returns
  a `MountedComponent` record carrying the lifecycle log and the
  unmount thunk. Throws if a non-test-react adapter is installed."
  [render-tree]
  (when-not (identical? adapter (substrate-adapter/current-adapter-spec))
    (throw (ex-info ":rf.error/test-react-not-installed"
                    {:where    'rf/test-react-mount!
                     :recovery :no-recovery
                     :reason   (str "test-react/mount! requires the Test-React adapter"
                                    " to be the (rf/init!)-installed adapter; got "
                                    (substrate-adapter/current-adapter) ".")})))
  (let [unmount-fn (substrate-adapter/render render-tree nil nil)
        mount     (mount-record-from-unmount-fn unmount-fn)]
    ;; Stash the unmount-fn on the record so `unmount!` below can
    ;; reach for it. (CLJ records support assoc; this mutates a
    ;; per-mount slot in a way that does not collide with the
    ;; defrecord fields.)
    (assoc mount ::unmount-fn unmount-fn)))

(defn trigger-update!
  "Simulate a React re-render of `mount` with `new-render-tree` as the
  next render output. Records a `:did-update` phase entry in the
  lifecycle log. Throws if the mount has already been unmounted."
  [mount new-render-tree]
  (when-not @(:mounted? mount)
    (throw (ex-info ":rf.error/update-after-unmount"
                    {:where    'rf/test-react-trigger-update!
                     :recovery :no-recovery
                     :mount-id (:id mount)
                     :reason   "trigger-update! called on a mount that has already been unmounted."})))
  (run-render! mount new-render-tree)
  (log-phase! mount :did-update)
  mount)

(defn unmount!
  "Unmount `mount`. Records a `:will-unmount` phase entry. Throws
  `:rf.error/sync-unmount-during-render` if called while the mount
  is in the middle of a render (this is the rf2-4l7t2 class).
  Idempotent: a second call on the same mount is a no-op."
  [mount]
  (when-let [f (::unmount-fn mount)]
    (f))
  nil)

(defn lifecycle-log
  "Return the lifecycle log for `mount` — a vector of `{:phase ... :at ms}`
  entries, in the order they fired. Test assertions typically map
  over `:phase` and compare to a canonical sequence."
  [mount]
  @(:lifecycle-log mount))

(defn current-render-tree
  "Return the most recently rendered tree for `mount`, or `nil` after
  unmount."
  [mount]
  @(:render-tree mount))

(defn mounted-roots
  "Return all currently-mounted `MountedComponent`s under this adapter.
  Useful for tests asserting balanced mount/unmount counts."
  []
  (filter (comp deref :mounted?) @active-mounts))

;; ---- late-bind hook routing -----------------------------------------------
;; The Test-React adapter publishes only the React-context-tier fallback
;; for `:adapter/current-frame` — there is no React context, so the
;; hook drops through to `frame/current-frame` (the dynamic-var tier).
;; Other reactive-substrate hooks (`:adapter/ratom` / `:adapter/make-
;; reaction` / `:adapter/after-render`) are intentionally NOT published:
;; production code paths that reach for them under a non-React adapter
;; indicate a misconfiguration that should surface, not be papered over.

(substrate-adapter/route-hook! adapter :adapter/current-frame
  (fn test-react-current-frame [] (frame/current-frame))
  #(frame/current-frame))

;; SSR emitter install — chains onto the existing :reagent/set-hiccup-
;; emitter! late-bind hook so a single `(require '[re-frame.ssr])` wires
;; render-to-string for whichever adapter ends up (rf/init!)-installed.
(late-bind/chain-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)
