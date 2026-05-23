(ns re-frame.adapter.test-react
  "The Test-React adapter — simulates the React class-3 lifecycle in pure
  CLJC (no React, no DOM, no jsdom) so React-lifecycle-driven bugs (stale
  closures, unbalanced subscribe/dispose, sync unmount inside render — the
  rf2-4l7t2 class) can be caught at unit-test speed.

  Implements the Spec 006 nine-fn adapter contract. The atom-backed
  container quartet is shared with plain-atom via
  `re-frame.substrate.atom-container`; the render half is the novel surface
  — a `class-3` lifecycle simulator that records every transition into a
  per-mount log and enforces the `:currently-rendering?` invariant (it
  throws on a synchronous `unmount!` while a render is in flight, mirroring
  React 18+).

  Status: minimal viable skeleton (rf2-gqyqv; placeholder bead).

  Out of scope (deferred to follow-on beads):
    - Recursive child mounting: the render tree is treated as opaque data,
      so the rf2-4l7t2 guard is reachable only via fabricated in-flight
      state (a test sets `:currently-rendering?` by hand) until recursive
      child mount lands and a child render-body can organically issue a
      re-entrant unmount.
    - Auto-re-render on app-db change: tests drive re-renders explicitly
      via `trigger-update!`.
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
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.atom-container :as atom-container]
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
  ;; protocol. The sub-cache's dispose/ref-count path therefore works on the
  ;; JVM (interop.clj implements dispose by reaction identity) but is a
  ;; SILENT no-op under CLJS (this adapter withholds the :adapter/dispose! /
  ;; :adapter/add-on-dispose! late-bind hooks — see the routing block at the
  ;; foot of this ns — and interop.cljs routes through them, so unregistered
  ;; hooks no-op). Acceptable for the test adapter: the derived value holds
  ;; no host resources, so there is nothing for a dispose walk to release.
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
;;   :lifecycle-log   — atom holding a vector of {:phase ... :at ms} entries;
;;                      the test driver inspects this to assert ordering.
;;   :currently-rendering? — atom<boolean>; true while a render is in flight.
;;                      The simulator THROWS on attempts to unmount!
;;                      synchronously while this is true, mirroring React
;;                      18+'s sync-unmount-during-render guard (rf2-4l7t2).
;;   :mounted?        — atom<boolean>; false after unmount. The simulator
;;                      THROWS on trigger-update! / unmount! after teardown.
;;   :unmount-fn      — the unmount thunk for this mount; `unmount!` calls it.

(defrecord ^:no-doc MountedComponent
  [id render-tree lifecycle-log currently-rendering? mounted? unmount-fn])

;; All live mounts; `dispose-adapter!` walks this to drain.
(defonce ^:private active-mounts (atom #{}))

(defn- now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- log-phase! [mount phase & {:as extras}]
  (swap! (:lifecycle-log mount)
         conj (merge {:phase phase :at (now-ms)} extras)))

(defn- run-render!
  "Set the :currently-rendering? flag, record a :render phase entry, store the
  render-tree, clear the flag. Throws from inside the render body are NOT
  caught — they propagate to the caller (React 18+ unmounts the root)."
  [mount tree]
  (reset! (:currently-rendering? mount) true)
  (try
    (reset! (:render-tree mount) tree)
    (log-phase! mount :render)
    (finally
      (reset! (:currently-rendering? mount) false))))

(defn- unmount-thunk
  "Build the unmount thunk for `mount`. Idempotent (a second call on an
  already-unmounted mount is a no-op). Throws
  `:rf.error/sync-unmount-during-render` if called while a render is in
  flight (the rf2-4l7t2 class)."
  [mount]
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
    nil))

(defn- mount-tree!
  "The internal mount seam. Builds the `MountedComponent`, runs the
  constructor → render → did-mount lifecycle, registers it in
  `active-mounts`, and returns the record itself (with its unmount thunk
  stored in the `:unmount-fn` field). Both the substrate `render` fn and the
  public `mount!` driver call this — `render` discards everything but the
  thunk; `mount!` returns the whole record so tests can inspect the log."
  [render-tree]
  (let [base    (->MountedComponent
                  (gensym "test-react-mount-")
                  (atom nil)   ; render-tree
                  (atom [])    ; lifecycle-log
                  (atom false) ; currently-rendering?
                  (atom true)  ; mounted?
                  nil)         ; unmount-fn — filled in below
        mount   (assoc base :unmount-fn (unmount-thunk base))]
    (log-phase! mount :constructor)
    (run-render! mount render-tree)
    (log-phase! mount :did-mount)
    (swap! active-mounts conj mount)
    mount))

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
  ;; rf2-pyp3n: this drains active-mounts (the host-resource MUST) but does
  ;; NOT walk per-frame sub-caches the way the React adapters do via
  ;; `spine/dispose-frame-sub-caches!` — this adapter is CLJC and the spine
  ;; is CLJS-only. Acceptable because test-react's `make-derived-value` holds
  ;; no host resources (plain IDeref reify); nothing for the walk to dispose.
  (doseq [m @active-mounts]
    (when @(:mounted? m)
      (log-phase! m :forced-teardown)
      (reset! (:mounted? m) false)
      (reset! (:render-tree m) nil)))
  (reset! active-mounts #{})
  nil)

(def adapter
  "The Test-React adapter map. Pass to `(rf/init! ...)` in a unit-test
  fixture:

      (require '[re-frame.adapter.test-react :as test-react])
      (rf/init! test-react/adapter)

  Per Spec 006 §The adapter API contract — implements the nine-fn
  contract. The reactive-container half is shared with plain-atom; the
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

(defn mount!
  "Mount `render-tree` (a hiccup vector or any data the test treats as the
  rendered output) under the installed Test-React adapter. Returns the
  `MountedComponent` record carrying the lifecycle log and the unmount
  thunk. Throws if a non-test-react adapter is installed."
  [render-tree]
  (when-not (identical? adapter (substrate-adapter/current-adapter-spec))
    (throw (ex-info ":rf.error/test-react-not-installed"
                    {:where    'rf/test-react-mount!
                     :recovery :no-recovery
                     :reason   (str "test-react/mount! requires the Test-React adapter"
                                    " to be the (rf/init!)-installed adapter; got "
                                    (substrate-adapter/current-adapter) ".")})))
  (mount-tree! render-tree))

(defn trigger-update!
  "Simulate a React re-render of `mount` with `new-render-tree` as the next
  render output. Records a `:did-update` phase entry in the lifecycle log.
  Throws if the mount has already been unmounted."
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
  `:rf.error/sync-unmount-during-render` if called while the mount is in the
  middle of a render (the rf2-4l7t2 class). Idempotent: a second call on the
  same mount is a no-op."
  [mount]
  ((:unmount-fn mount))
  nil)

(defn lifecycle-log
  "Return the lifecycle log for `mount` — a vector of `{:phase ... :at ms}`
  entries, in the order they fired. Test assertions typically map over
  `:phase` and compare to a canonical sequence."
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
