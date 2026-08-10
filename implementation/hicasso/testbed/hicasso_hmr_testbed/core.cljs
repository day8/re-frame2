(ns hicasso-hmr-testbed.core
  "THE HMR TESTBED SHELL — two frames, two roots, one `^:dev/after-load`
  hook, and a door that answers the questions the DOM cannot (rf2-vsgq).

  `hicasso-hmr-testbed.views` is the namespace under test and the only
  file the gate edits. This one is the application around it: it seeds two
  frames, mounts a root per frame, and carries the re-initialising reload
  hook an ordinary Hicasso app would carry.

  ## Why everything durable here is `defonce`

  `views` is reloaded, and this namespace REQUIRES it, so shadow reloads
  this one too — every plain `def` re-runs. The root handles, the reload
  counter, the identity baseline and the ref sink must all outlive that,
  or the instrument would be replaced by the event it is measuring. This
  is not a testbed quirk: it is the discipline any hot-reloadable app
  keeps, and getting it wrong here would have shown up as a gate that
  could not tell a remount from its own amnesia.

  ## The reload hook is the ORDINARY shape, deliberately

  [[reload!]] re-runs `(rf/make-frame {:id …})` and re-renders each root.
  `make-frame` on a live id is idempotent replacement — config refresh,
  durable state preserved — which `hmr_registry_cljs_test` measured at the
  arm and which this gate now measures through a real reload. The OTHER
  shape, a hook that destroys the frame first, genuinely reincarnates;
  [[destroy-and-remake!]] is that transition, exposed as the routing row's
  negative control rather than as the hook's behaviour.

  ## The door, and what it refuses to do

  `window.__RF2_HMR__` answers three kinds of question:

  1. **Model reads** — what app-db holds, per frame.
  2. **Identity comparisons** — taken in ClojureScript with `identical?`
     against a captured baseline, and answered as BOOLEANS. The objects
     themselves never cross to JavaScript and are never printed. That is
     not fastidiousness: a registration holds a cyclic reference and an
     incarnation token reaches a cyclic object graph, so serialising one
     is a stack overflow where a diagnosis should be — the same trap
     `hmr_remount_cljs_test`'s `same-object?` exists for, met here at the
     language boundary instead of in a failure message.
  3. **The sabotage** — a renderer that fails to run the old generation's
     cleanup, installed at React's own seam and toggled at run time.

  Nothing here reaches past the authoring surface to make an assertion
  pass. The identity readers are `re-frame.hicasso.impl.inventory`'s
  published witness readers, which is what they are for."
  (:require ["react" :as react]
            [hicasso-hmr-testbed.views :as views]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]))

;; ---------------------------------------------------------------------------
;; The two frames — the routing row's whole premise
;; ---------------------------------------------------------------------------

(def ^:private frames
  "Frame id -> container element id -> the label that frame's db is seeded
  with. Two frames rendering the SAME view, so a root painting the other
  one's label is a routing failure and not an ambiguity."
  [{:frame ::alpha :container "alpha" :label "ALPHA"}
   {:frame ::beta  :container "beta"  :label "BETA"}])

(def ^:private watched-queries
  "The queries whose cells the residue and hand-over rows are read on —
  the two a boundary in this app actually subscribes to."
  [[:hmr/text] [:hmr/digits] [:hmr/label]])

;; ---------------------------------------------------------------------------
;; State that must outlive the reload
;; ---------------------------------------------------------------------------

(defonce ^:private !handles   (atom {}))
(defonce ^:private !reloads   (volatile! 0))
(defonce ^:private !baseline  (atom nil))
(defonce ^:private !instances (atom {}))

(defonce ^:private ref-sinks
  ;; ONE stable function PER FRAME, for the length of the page's life.
  ;;
  ;; Stable, because a callback ref whose identity changed every render
  ;; would make React detach and re-attach on renders that remounted
  ;; nothing, and the imperative-host row's negative control — "no save,
  ;; same instance" — would red for a reason that has nothing to do with a
  ;; reload.
  ;;
  ;; Per frame, because both roots render the same view: a single shared
  ;; sink would hold whichever of the two instances attached last, and the
  ;; row would be reading one root while claiming to read the other.
  (into {} (map (fn [{:keys [frame]}]
                  [frame (fn [instance]
                           (if (nil? instance)
                             (swap! !instances update-in [frame :detachments] (fnil inc 0))
                             (swap! !instances update frame
                                    (fn [s] (-> s
                                                (assoc :current instance)
                                                (update :attachments (fnil inc 0)))))))]))
        frames))

;; ---------------------------------------------------------------------------
;; The lost-cleanup sabotage, installed at React's own seam
;; ---------------------------------------------------------------------------
;;
;; The fault the #7755 audit named is "a renderer failing to run
;; old-generation cleanup on type replacement". It is a RENDERER fault, so
;; it is modelled at the renderer: the function React calls to unsubscribe
;; is swallowed, and the runtime — untouched — never learns that the
;; retired boundary went away. Nothing under `implementation/hicasso/src/`
;; is modified to produce it.
;;
;; The wrapper is installed ONCE and unconditionally, on and off alike, and
;; consults the flag only at CLEANUP time. Two consequences worth having:
;; the instrumentation is identical in both phases, so the swallow is the
;; single variable between the red run and the green one; and the
;; `subscribe` identity React compares stays stable per original, which a
;; freshly-allocated wrapper per render would destroy — resubscribing on
;; every render would churn the very registrations the residue row counts.

(defonce ^:private !sabotage?          (volatile! false))
(defonce ^:private !stale-notified     (volatile! 0))
(defonce ^:private wrapped-subscribes  (js/WeakMap.))

(defn- wrap-subscribe
  [subscribe]
  (or (.get wrapped-subscribes subscribe)
      (let [wrapper
            (fn [on-store-change]
              (let [!retired (volatile! false)
                    notify   (fn []
                               ;; A notification delivered AFTER React asked
                               ;; to unsubscribe is the leak being live
                               ;; rather than merely present — the retired
                               ;; generation is still wired to the store.
                               (when @!retired (vswap! !stale-notified inc))
                               (on-store-change))
                    cleanup  (subscribe notify)]
                (fn []
                  (if @!sabotage?
                    (vreset! !retired true)
                    (cleanup)))))]
        (.set wrapped-subscribes subscribe wrapper)
        wrapper)))

(defonce ^:private sabotage-seam-installed?
  (let [original (.-useSyncExternalStore react)]
    (set! (.-useSyncExternalStore react)
          (fn [subscribe get-snapshot get-server-snapshot]
            (original (wrap-subscribe subscribe) get-snapshot get-server-snapshot)))
    true))

;; ---------------------------------------------------------------------------
;; Identity, compared in ClojureScript and answered as booleans
;; ---------------------------------------------------------------------------

(defn- sub-key [frame-kw query] [frame-kw query])

;; A stable, printable name for each registration object the driver has
;; ever seen, assigned on first sight and held weakly so tagging cannot
;; keep a retired registration alive.
;;
;; It exists because `{:count 1 :stale 1}` is a true statement that does
;; not say what happened, and the first time this suite produced one it
;; cost a round of guessing. With tags the same failure reads
;; `now [r1] was [r1]` — the successor never acquired — or `now [r1 r2]`
;; — the predecessor never let go — and those are different bugs. The
;; tags are never compared for identity themselves; `identical?` above is
;; still what decides. They are for the human reading the red.
(defonce ^:private reg-tags (js/WeakMap.))
(defonce ^:private !next-tag (volatile! 0))

(defn- tag-of
  [reg]
  (or (.get reg-tags reg)
      (let [t (str "r" (vswap! !next-tag inc))]
        (.set reg-tags reg t)
        t)))

(defn- readers-now
  "The registrations reading `sub-key` right now, as a vector THIS
  namespace owns.

  The `mapv` is a defensive copy and it is load-bearing, not hygiene.
  `inventory/cell-readers` answers `(vec (.-readers cell))`, and the
  cell's reader list is a JS array the collector mutates IN PLACE — a
  `.push` on acquire (collector.cljs, `acquire-cell!`) and a splice on
  release. A ClojureScript vector built from a JS array can share that
  array's storage, so the value `cell-readers` returns is not necessarily
  a snapshot: hold it across a commit and it can report what the array
  holds LATER.

  Measured here rather than reasoned about. Without this copy the
  baseline captured before a save had, by the time it was compared,
  become the post-save reader list — so `was` equalled `now` for every
  key, `stale` equalled `count`, and the hand-over row failed while the
  runtime was behaving correctly. That reads exactly like a leak, which
  is the most expensive way for a test instrument to be wrong.

  The reader's own docstring says the vector exists so that a CALLER
  cannot mutate the live list, which protects the other direction;
  nothing is claimed here about whether the runtime should also protect
  this one."
  [frame-kw query]
  (mapv identity (inventory/cell-readers (sub-key frame-kw query))))

(defn- capture-baseline!
  "Freeze the objects the next comparison is made against: the view head
  the real macro produced, each frame's incarnation token and frame-op
  row, and every registration currently reading a watched key."
  []
  (reset! !baseline
          {:head     views/app
           :tokens   (into {} (map (fn [{:keys [frame]}]
                                     [frame (frame/frame-incarnation-token frame)]))
                           frames)
           :rows     (into {} (map (fn [{:keys [frame]}]
                                     [frame (collector/frame-row frame)]))
                           frames)
           :readers  (into {} (for [{:keys [frame]} frames
                                    query watched-queries]
                                [[frame query] (readers-now frame query)]))
           :instances (into {} (map (fn [{:keys [frame]}]
                                      [frame (get-in @!instances [frame :current])]))
                            frames)})
  true)

(defn- stale-count
  "How many of the registrations reading `key` right now are the SAME
  OBJECTS the baseline captured. Zero is a clean hand-over; anything else
  names a predecessor that never let go.

  This is the assertion a count cannot make. A runtime that leaked the
  predecessor and also failed to acquire for the successor still reports
  one reader, so `{:count 1}` alone is satisfied by a broken runtime;
  `{:count 1 :stale 0}` is not."
  [frame-kw query]
  (let [before (get-in @!baseline [:readers [frame-kw query]] [])
        now    (readers-now frame-kw query)]
    (count (filter (fn [r] (some #(identical? % r) before)) now))))

(defn- identity-report
  []
  (let [base @!baseline]
    (clj->js
      {:head-same? (identical? (:head base) views/app)
       :frames
       (into {} (map (fn [{:keys [frame]}]
                       [(name frame)
                        {:token-same?    (identical? (get-in base [:tokens frame])
                                                     (frame/frame-incarnation-token frame))
                         :row-same?      (identical? (get-in base [:rows frame])
                                                     (collector/frame-row frame))
                         :instance-same? (identical? (get-in base [:instances frame])
                                                     (get-in @!instances [frame :current]))}]))
             frames)
       :keys
       (into {} (for [{:keys [frame]} frames
                      query watched-queries]
                  [(str (name frame) " " (pr-str query))
                   {:count (count (readers-now frame query))
                    :stale (stale-count frame query)
                    :now   (mapv tag-of (readers-now frame query))
                    :was   (mapv tag-of (get-in base [:readers [frame query]] []))}]))})))

;; ---------------------------------------------------------------------------
;; Mount, reload, and the frame lifecycle
;; ---------------------------------------------------------------------------

(defn- render-all!
  []
  (doseq [{:keys [frame]} frames]
    (when-some [handle (get @!handles frame)]
      (mount/render! handle [views/app {:ref-sink (get ref-sinks frame)}]))))

(defn- seed!
  [frame-kw label]
  (rf/with-frame frame-kw (rf/dispatch-sync [:hmr/seed label])))

(declare install-door!)

(defn ^:dev/after-load reload!
  "The re-initialising reload hook, in its ordinary shape.

  Re-running `make-frame` on a live id is idempotent replacement, and
  re-rendering is what lets React meet the newly minted head at the
  position. Both halves are what a real app's hook does; neither is a
  test affordance.

  The door is re-installed for one reason worth stating: its entries are
  closures over THIS module, and after a reload the previous module's
  closures are stale objects even though every table they read is
  `defonce` and therefore correct. Re-installing keeps the door and the
  code it reports on the same generation, so a driver can never be told
  about the runtime by a function the reload retired."
  []
  (vswap! !reloads inc)
  (doseq [{:keys [frame]} frames]
    (rf/make-frame {:id frame}))
  (render-all!)
  (install-door!))

(defn- destroy-and-remake!
  "The OTHER reload shape — a hook that tears the app down before
  rebuilding it. It genuinely reincarnates, and the routing row uses it as
  the negative control that proves the token reader can report a change."
  [frame-kw label]
  (rf/destroy-frame! frame-kw)
  (rf/make-frame {:id frame-kw})
  (seed! frame-kw label)
  (render-all!)
  true)

;; ---------------------------------------------------------------------------
;; The door
;; ---------------------------------------------------------------------------

(defn- frame-of
  "The frame keyword a driver names by its short string. Every door entry
  that takes a frame goes through this, so a typo is a loud `nil` frame
  rather than a silent read of the wrong root."
  [frame-name]
  (let [kw (keyword "hicasso-hmr-testbed.core" frame-name)]
    (if (some #(= kw (:frame %)) frames)
      kw
      (throw (js/Error. (str "no such frame: " frame-name))))))

(defn- install-door!
  []
  (unchecked-set
    js/window "__RF2_HMR__"
    #js {:reloads       (fn [] @!reloads)
         :model         (fn [frame-name]
                          (js/JSON.stringify (clj->js (rf/app-db-value (frame-of frame-name)))))
         :residue       (fn [] (clj->js (inventory/residue)))
         :capture       (fn [] (capture-baseline!))
         :identity      (fn [] (identity-report))
         :instance      (fn [frame-name]
                          (clj->js (dissoc (get @!instances (frame-of frame-name)) :current)))
         :instanceId    (fn [frame-name]
                          (some-> ^js (get-in @!instances [(frame-of frame-name) :current])
                                  (.-instanceId)))
         :note          (fn [frame-name text]
                          (some-> ^js (get-in @!instances [(frame-of frame-name) :current])
                                  (.note text)))
         :quiesce       (fn [] (inventory/quiesced!))
         :sabotage      (fn [on?] (vreset! !sabotage? (boolean on?)) @!sabotage?)
         :staleNotified (fn [] @!stale-notified)
         :setLabel      (fn [frame-name label]
                          (rf/with-frame (frame-of frame-name)
                            (rf/dispatch-sync [:hmr/set-label label]))
                          true)
         :reincarnate   (fn [frame-name label]
                          (destroy-and-remake! (frame-of frame-name) label))})
  nil)

(defn ^:export init
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/init! uix-adapter/adapter)
  (doseq [{:keys [frame container label]} frames]
    (rf/make-frame {:id frame})
    (seed! frame label)
    (swap! !handles assoc frame
           (h/root! (js/document.getElementById container)
                    frame
                    [views/app {:ref-sink (get ref-sinks frame)}])))
  (install-door!)
  nil)
