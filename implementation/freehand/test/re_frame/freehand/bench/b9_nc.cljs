(ns re-frame.freehand.bench.b9-nc
  "B9 — SCAFFOLDING. The concurrent-store contract, ABLATED, so it can be
  priced on the clock and on the heap (`rf2-so3io`).

  **Nothing here ships and nothing here changes the shipped path.** Every
  arm is an extra arm on an existing door: the heap arms merge onto
  [[re-frame.freehand.bench.b7-heap/arms]] and are read through B7's own
  collector; the clock arms are built from
  [[re-frame.freehand.bench.b6-rows]]'s constructors and are measured
  through B6's own `timed-write!` window. No sixth instrument.

  ## What \"the concurrent-store contract\" means here, exactly

  Two mechanisms, both servicing React's concurrent-rendering contract,
  both ablated together and each also ablated on its own:

  1. **`useSyncExternalStore`.** `shell/use-revision!` installs one per
     boundary. It is what lets React detect that a store moved during a
     time-sliced render and restart, and it is 516 B of the ~1,171 B
     React's six hooks retain per boundary (`rf2-oob3g`'s ladder).
  2. **The commit-side re-read.** `cell/commit-readings` calls `obs/read`
     on EVERY staged handle and compares node-key, version and
     frame/registry epochs against the render's probe — Spec 006
     invariant 5, the render→commit tear check. It is O(dependencies) and
     it is the largest single term in the commit-phase layout effect
     (`bulk-rerender-where-the-time-goes.md` §4).

  ## The ladder, and why it has a REFERENCE rung

  A hand-built ablation arm compared against the published Freehand arm
  would measure the ablation PLUS every other difference between a
  hand-built body and the interpreted emitter walk. So the ladder carries
  a reference rung — the same hand-built body under the REAL shell and
  the REAL `cell/commit!` — and the contract's price is the
  `ref − nc` difference, taken with everything else held identical. The
  published arm rides alongside as the anchor, and `published − ref` is
  the emitter walk this pair does not contain.

    ref   hand-built body + `shell/render`    + `cell/commit!`
    nc    hand-built body + [[nc-render]]     + [[nc-commit!]]

  ## Why the ablated commit is a COPY rather than a flag

  It is a copy on purpose, and the copy is the answer to the bead's second
  question. `cell/commit!`'s staging, its currency re-check, its rollback
  discipline, its superseded-handle release and its event-table
  publication are all PRIVATE to `cell.cljc` and all indifferent to
  whether the tear check runs. Ablating one step therefore means
  duplicating every step around it — which is exactly the shape a
  \"cheap by default, guarantee on demand\" build would take if it were
  spelled as a second commit path. The line count below is the price.

  ## The repaint channel the ablation is forced into

  `useSyncExternalStore`'s listener calls React's `forceStoreRerender`,
  which schedules at the **sync lane**. Nothing else available to a
  function component does: a `useReducer` dispatch issued from a plain
  microtask takes the DEFAULT lane, and an EMPTY `react-dom/flushSync`
  flushes only the sync lane (the fault B6 records against its own floor
  arm). So a boundary without `useSyncExternalStore` cannot repaint
  inside `flushSync` through the door `rf2-w2m25` opened.

  The ablation therefore takes Reagent's shape, which is the substrate
  being compared against: a notification ENQUEUES the boundary's
  force-update, and a drain runs the enqueued bumps inside the caller's
  `flushSync`, where they take the sync lane. That is
  `reagent.core/flush` in Freehand spelling, and it is what the `nc`
  arm's `force!` calls. [[sync-lane-probe!]] measures the claim rather
  than asserting it."
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b7-heap :as heap]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.shell :as shell]
            [re-frame.router :as router]
            [re-frame.substrate.observation :as obs]))

(def ^:private el react/createElement)
(def ^:private empty-deps #js [])

(def per-root
  "Boundaries in one root — B7's own, so a per-boundary figure here and a
  per-boundary figure there describe the same page."
  heap/per-root)

;; ===========================================================================
;; 1. The ablated commit — cell/commit! WITHOUT the invariant-5 re-read
;; ===========================================================================
;;
;; Everything in this section is a near-verbatim copy of a PRIVATE function
;; in `cell.cljc`. It is copied rather than called because it is private,
;; and it is private because nothing outside the commit has any business
;; running it. That is the point: see the namespace docstring.

(defn- nc-current?
  "`cell/current?` — the candidate's body revision is still the cell's and
  the frame incarnation it resolved against is still live. UNCHANGED by
  the ablation; copied because it is private.

  `cand` carries its type, and that is not decoration (rf2-cfqk). The
  original reads these fields INSIDE the namespace that `deftype`s
  `RenderCandidate`, where the analyser already knows them; a copy taken
  out of that namespace does not inherit the knowledge, so every
  `(.-field cand)` here is an un-inferrable property access and shadow's
  externs inference says so. Naming the type is what makes the copy
  compile the way the original does, which is exactly the fidelity the
  copy exists for."
  [^cell/RenderCandidate cand s]
  (and (= (.-generation cand) (:generation s))
       (let [f (.-frame cand)]
         (or (nil? f)
             (frame/frame-incarnation-live? f (.-incarnation cand))))))

(defn- nc-release-all! [handles]
  (doseq [h handles] (obs/release! h))
  nil)

(defn- nc-stage!
  "`cell/stage!` — acquire every newly-observed or retargeted target in
  render order, before anything is released, with the same
  release-in-reverse rollback on failure. UNCHANGED by the ablation."
  [c prior order reads]
  (let [acquired (volatile! [])
        n        (count order)]
    (try
      (loop [i      0
             staged (transient {})]
        (if (< i n)
          (let [site-key (nth order i)
                record   (get reads site-key)
                target   (:target record)
                kept     (get prior site-key)
                handle   (if (and (some? kept) (obs/current? (:handle kept) target))
                           (:handle kept)
                           (let [h (obs/acquire!
                                     target
                                     (fn on-change [cause] (cell/mark-dirty! c cause)))]
                             (vswap! acquired conj h)
                             h))]
            (recur (inc i) (assoc! staged site-key (assoc record :handle handle))))
          {:staged (persistent! staged) :acquired @acquired}))
      (catch :default e
        (nc-release-all! (rseq @acquired))
        (throw e)))))

(defn- nc-superseded-handles
  "`cell/superseded-handles`. UNCHANGED by the ablation."
  [prior staged acquired]
  (if (and (empty? acquired) (= (count staged) (count prior)))
    []
    (into []
          (keep (fn [[site-key {:keys [handle]}]]
                  (let [now (get-in staged [site-key :handle])]
                    (when-not (identical? now handle) handle))))
          prior)))

(defn- nc-observations
  "**THE ABLATION.** `cell/commit-readings` with the `obs/read` removed.

  The shipped pass reads every staged handle a SECOND time — after the
  render already read it — and compares node-key, version and
  frame/registry epochs against the render's probe. That read is the
  invariant-5 tear check; the observations it publishes are a by-product
  of it.

  Here the observations are published from the value the RENDER read, so
  no handle is read twice and nothing is compared. `obs/owned?` stays: it
  is a total, no-throw predicate over handle state, it is not
  callback-capable, and it is not part of the contract being priced.

  There is no `:moved?`, so there is no revision advance at commit and no
  correction before paint. A source that moved in the render→commit gap
  is corrected only by its own change watch — which a RETAINED handle has
  on a watchable host and does NOT have on a headless one."
  [order staged]
  (let [n (count order)]
    (loop [i 0
           o (transient [])]
      (if (< i n)
        (let [site-key (nth order i)
              record   (get staged site-key)]
          (recur (inc i)
                 (conj! o {:site-key site-key
                           :query    (:query record)
                           :frame-id (:frame-id (:target record))
                           :value    (:value record)
                           :owned?   (obs/owned? (:handle record))})))
        (persistent! o)))))

(defn- nc-frame-dispatcher
  "`cell/frame-dispatcher`. UNCHANGED by the ablation."
  [frame-id]
  (if (some? frame-id)
    (fn dispatch-into-frame [event] (router/dispatch! event {:frame frame-id :source :ui}))
    (fn dispatch-ambient [event] (router/dispatch! event {:source :ui}))))

(defn- nc-frame-door-dispatcher
  "`cell/frame-door-dispatcher`. UNCHANGED by the ablation."
  [frame-id]
  (when (some? frame-id)
    (fn dispatch-through-door [event]
      (router/dispatch-sync! event {:frame frame-id :source :ui})
      (cell/flush-frame! frame-id)
      nil)))

(defn nc-commit!
  "`cell/commit!` with the invariant-5 commit-side re-read removed, and
  nothing else changed. Answers `:published` or `:abandoned`.

  The currency re-check STAYS: `nc-stage!`'s `obs/acquire!` is still
  callback-capable, so a publication that skipped it could publish stale
  ownership. Only the tear check goes.

  `cand` is typed for the reason [[nc-current?]] gives."
  [^cell/RenderCandidate cand]
  (let [c  (.-cell cand)
        s0 @(.-state c)]
    (if-not (nc-current? cand s0)
      :abandoned
      (let [order   @(.-order cand)
            by-site @(.-by-site cand)
            prior   (:deps s0)
            fid     (.-frame cand)
            {:keys [staged acquired]} (nc-stage! c prior order by-site)
            observations (try
                           (nc-observations order staged)
                           (catch :default e
                             (nc-release-all! (rseq acquired))
                             (throw e)))]
        (if-not (nc-current? cand @(.-state c))
          (do (nc-release-all! (rseq acquired))
              :abandoned)
          (let [bundle {:frame        fid
                        :generation   (.-generation cand)
                        :observations observations}]
            (swap! (.-state c) assoc
                   :lifecycle :connected
                   :frame     fid
                   :deps      staged
                   :dep-order order
                   :evidence  bundle)
            (events/commit! (.-events cand)
                            (nc-frame-dispatcher fid)
                            (nc-frame-door-dispatcher fid))
            (nc-release-all! (nc-superseded-handles prior staged acquired))
            :published))))))

;; ===========================================================================
;; 2. The ablated repaint channel — no useSyncExternalStore
;; ===========================================================================

(defonce ^:private pending-bumps (js/Set.))

(defn drain!
  "Run every enqueued force-update. Called INSIDE the caller's
  `react-dom/flushSync`, so the updates it issues take the sync lane and
  commit before that flush returns — the same contract
  `reagent.core/flush` has, and the only one available to a boundary that
  does not hold a `useSyncExternalStore`.

  Answers how many boundaries were bumped, so a caller can see the
  channel is not silently empty."
  []
  (let [n (.-size pending-bumps)]
    (when (pos? n)
      (let [bs (js/Array.from pending-bumps)]
        (.clear pending-bumps)
        (.forEach bs (fn [b] (b)))))
    n))

(defn- tick [n _] (inc n))

;; ===========================================================================
;; 3. The two shells
;; ===========================================================================

(defn ref-render
  "The REFERENCE rung: the real [[re-frame.freehand.shell/render]],
  unchanged. Present so the ablation is a paired difference rather than a
  comparison against a different body."
  [view-id render-candidate]
  (shell/render view-id 0 :interpreted render-candidate))

(defn nc-render
  "The ABLATED rung: `shell/render` with `useSyncExternalStore` replaced
  by an enqueued force-update and `cell/commit!` replaced by
  [[nc-commit!]].

  FIVE hooks against the shell's six — `useContext`, `useRef`,
  `useReducer`, and the same two `useLayoutEffect`s. The subscription is
  folded into the lifecycle effect, which is where a real implementation
  would put it and which is why no third effect appears.

  The hole this opens is the reason `useSyncExternalStore` exists: the
  store is subscribed to at COMMIT, not during render, so a move between
  this render and its commit is not noticed here at all — and
  [[nc-commit!]] no longer notices it either."
  [view-id render-candidate]
  (let [frame-id (shell/frame-context-frame)
        cell-ref (react/useRef nil)
        _        (when (nil? (.-current cell-ref))
                   (set! (.-current cell-ref) (cell/cell view-id)))
        c        (.-current cell-ref)
        bump     (aget (react/useReducer tick 0) 1)
        _        (cell/advance-generation! c 0)
        cand     (cell/candidate c frame-id)
        element  (render-candidate cand)]
    (react/useLayoutEffect
      (fn reconcile []
        (nc-commit! cand)
        js/undefined))
    (react/useLayoutEffect
      (fn lifecycle []
        (let [unsub (cell/subscribe c (fn notify [] (.add pending-bumps bump) nil))]
          (fn cleanup []
            (unsub)
            (.delete pending-bumps bump)
            (cell/disconnect! c))))
      empty-deps)
    element))

;; ===========================================================================
;; 4. The bodies — hand-built, identical across both rungs
;; ===========================================================================

(defn- storm-body
  "B6's W2 leaf, hand-built: `[:span.leaf \"leaf\"]`, no reactive read."
  [_cand]
  (el "span" #js {:className "leaf"} "leaf"))

(defn- cell-body
  "B6's update-grid leaf, hand-built: one `v/sub` and one span. `v/sub` IS
  `cell/observe!`, so this is the same read the interpreted witness makes
  — what is missing is only the emitter walk around it."
  [i]
  (fn [cand]
    (cell/with-capture cand
      (fn []
        (el "span" #js {:className "cell" :data-i i}
            (str (cell/observe! [:b6/cell i])))))))

(defn- storm-leaf-ref [_props] (ref-render :b9/storm storm-body))
(defn- storm-leaf-nc  [_props] (nc-render  :b9/storm storm-body))

(defn- cell-leaf-ref [props]
  (let [i (aget props "i")] (ref-render :b9/cell (cell-body i))))

(defn- cell-leaf-nc [props]
  (let [i (aget props "i")] (nc-render :b9/cell (cell-body i))))

;; --- the hooks-only rungs, for the heap ladder ------------------------------
;;
;; `rf2-oob3g` priced React's six hooks over a TWO-FIELD JS OBJECT at
;; 1,171 B a boundary, of which `useSyncExternalStore` was 516. These two
;; rungs reproduce that rung and its ablated twin, so the hook half of the
;; answer is read on its own, with no ViewCell and no CLJS data structure
;; anywhere in it.

(defn- trivial-store [] #js {:revision 0 :listeners #js {}})

(defn- trivial-subscribe
  "The trivial store's subscribe. NULL-SAFE on unsubscribe, and that is
  not defensive noise: React runs a layout effect's cleanup BEFORE the
  passive cleanup `useSyncExternalStore` unsubscribes through, so a
  lifecycle effect that cleared the listener table left the later
  unsubscribe dereferencing `null`. `rf2-oob3g`'s ladder carried the same
  shape and threw on every unmount of this rung; it is fixed here rather
  than reproduced."
  [st]
  (fn [listener]
    (let [k (js-obj)]
      (aset (.-listeners st) k listener)
      (fn unsubscribe []
        (when-some [ls (.-listeners st)] (js-delete ls k))
        nil))))

(defn- hooks-leaf-ref
  "The shell's exact six hooks over a trivial store — `rf2-oob3g`'s L2."
  [_props]
  (let [_ctx (shell/frame-context-frame)
        ref  (react/useRef nil)
        _    (when (nil? (.-current ref)) (set! (.-current ref) (trivial-store)))
        st   (.-current ref)
        subscribe (react/useCallback (trivial-subscribe st) #js [st])]
    (react/useSyncExternalStore subscribe (fn [] (.-revision st)) (fn [] 0))
    (react/useLayoutEffect (fn reconcile [] js/undefined))
    (react/useLayoutEffect (fn lifecycle [] (fn cleanup [] (set! (.-listeners st) nil)))
                           empty-deps)
    (el "span" #js {:className "leaf"} "leaf")))

(defn- hooks-leaf-nc
  "The same rung with the concurrent-store contract removed: no
  `useCallback`, no `useSyncExternalStore`, a `useReducer` force-update,
  and the subscription folded into the lifecycle effect."
  [_props]
  (let [_ctx (shell/frame-context-frame)
        ref  (react/useRef nil)
        _    (when (nil? (.-current ref)) (set! (.-current ref) (trivial-store)))
        st   (.-current ref)
        bump (aget (react/useReducer tick 0) 1)]
    (react/useLayoutEffect (fn reconcile [] js/undefined))
    (react/useLayoutEffect
      (fn lifecycle []
        (let [unsub ((trivial-subscribe st) (fn [] (.add pending-bumps bump) nil))]
          (fn cleanup [] (unsub) (.delete pending-bumps bump) (set! (.-listeners st) nil))))
      empty-deps)
    (el "span" #js {:className "leaf"} "leaf")))

;; ===========================================================================
;; 5. The trees
;; ===========================================================================

(def frame-id
  "The one frame behind every reactive B9 root. Deliberately NOT B7's
  `:b7/grid`: that id is ensured CONFIG-BEARING by the published
  `reactive/freehand` arm's root 0, and a second config-bearing ensure of
  one id is refused by design (Spec 004C §7). One frame per arm against
  3,000 held boundaries is 1/3000 of a reading, so a separate id costs the
  comparison nothing and removes an ordering coupling between two arms
  that must be free to run in either order."
  :b9/grid)

(defn- storm-of [leaf]
  (el "div" #js {:className "storm"}
      (into-array (map (fn [i] (el leaf #js {:key i})) (range per-root)))))

(defn- grid-of [leaf fid]
  (shell/provide-frame fid
    (el "div" #js {:className "ugrid"}
        (into-array (map (fn [i] (el leaf #js {:key i :i i})) (range per-root))))))

(defn ensure-frame!
  "Stand the reactive rungs' frame up and seed it. `rf/make-frame` is the
  public constructor and is idempotent for the same id, so this is safe to
  call before every mount."
  [fid n]
  (when (nil? (frame/frame fid))
    (rf/make-frame {:id fid}))
  (frame/replace-app-db! fid {:cells (vec (repeat n 0))})
  nil)

;; ===========================================================================
;; 6. Heap arms — merged onto B7's own table, read through B7's collector
;; ===========================================================================

(defn- react-root-arm [selector element-of]
  {:selector    selector
   :mount-one   (fn [c _i]
                  (let [r (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render r (element-of))))
                    r))
   :unmount-one (fn [r] (react-dom/flushSync (fn [] (.unmount r))))})

(defn- reactive-root-arm [leaf]
  {:selector    ".cell"
   :mount-one   (fn [c _i]
                  (ensure-frame! frame-id per-root)
                  (let [r (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render r (grid-of leaf frame-id))))
                    r))
   :unmount-one (fn [r] (react-dom/flushSync (fn [] (.unmount r))))})

(def heap-arms
  "B7's arms, plus B9's rungs. Merged rather than replaced so the published
  rows are re-taken in the same run as the ablation and on the same box —
  a ratio against a figure from another day is not a ratio."
  (merge heap/arms
         {:b9/storm-hooks-ref (react-root-arm ".leaf" #(storm-of hooks-leaf-ref))
          :b9/storm-hooks-nc  (react-root-arm ".leaf" #(storm-of hooks-leaf-nc))
          :b9/storm-ref       (react-root-arm ".leaf" #(storm-of storm-leaf-ref))
          :b9/storm-nc        (react-root-arm ".leaf" #(storm-of storm-leaf-nc))
          :b9/reactive-ref    (reactive-root-arm cell-leaf-ref)
          :b9/reactive-nc     (reactive-root-arm cell-leaf-nc)}))

;; --- B7's holding door, over the extended table ----------------------------

(defonce ^:private held (atom nil))

(defn- release!* []
  (when-some [{:keys [arm handles containers]} @held]
    (let [{:keys [unmount-one]} (get heap-arms arm)]
      (doseq [h (reverse handles)]
        (try (unmount-one h) (catch :default _ nil))))
    (doseq [c containers] (.remove c))
    (reset! held nil))
  nil)

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn mount!
  "B7's `mount!`, over the extended arm table."
  [arm-id k]
  (release!*)
  (let [{:keys [mount-one selector]} (get heap-arms arm-id)
        containers (mapv (fn [_] (container!)) (range k))
        handles    (into [] (map-indexed (fn [i c] (mount-one c i))) containers)
        elements   (.-length (.querySelectorAll js/document selector))
        expected   (* k per-root)]
    (reset! held {:arm arm-id :handles handles :containers containers})
    #js {:elements elements :expected expected :ok (= elements expected)}))

(defn install-heap-door!
  "Publish the SAME `window.B7H` surface B7's collector drives, over the
  extended arm table. The collector, the readers, the forced collections
  and the positive control are B7's and are untouched."
  []
  (set! (.-B7H js/window)
        #js {:mount          (fn [arm k] (mount! (keyword arm) k))
             :release        (fn [] (release!*) true)
             :control        (fn [n] (heap/control! n))
             :controlRelease (fn [] (heap/control-release!))
             :perfMem        (fn []
                               (if-some [m (.-memory js/performance)]
                                 (.-usedJSHeapSize m)
                                 -1))
             :perRoot        per-root})
  nil)

;; ===========================================================================
;; 7. Clock arms — built for B6's own window
;; ===========================================================================

(defn- b9-update-arm
  "An update arm over a hand-built grid, in B6's arm shape. `write!` is
  B6's `frame/replace-app-db!` verbatim; the only thing that varies across
  the two rungs is which shell the leaf renders through and, for the
  ablated rung, what `force!` has to do.

  Both rungs' `force!` runs `cell/flush!` inside the `flushSync` before
  draining. It is idempotent — the microtask closer has normally already
  closed the window by then — and it is what makes the two rungs' windows
  identical, so the pair's difference is the contract and not the drain."
  [id leaf fid nc?]
  {:id      id
   :mount   (fn [container]
              (ensure-frame! fid rows/cells-n)
              (let [r (react-dom-client/createRoot container)]
                (react-dom/flushSync (fn [] (.render r (grid-of leaf fid))))
                r))
   :write!  (fn [i val]
              (if (= i :all)
                (frame/replace-app-db! fid {:cells (vec (repeat rows/cells-n val))})
                (frame/replace-app-db!
                  fid (update (frame/frame-app-db-value fid) :cells assoc i val))))
   :force!  (fn []
              (react-dom/flushSync
                (fn []
                  (cell/flush!)
                  (when nc? (drain!))
                  nil)))
   :unmount (fn [r] (react-dom/flushSync (fn [] (.unmount r))))})

(defn make-update-arms
  "B6's published arms, plus the two B9 rungs. The published arms are
  re-taken in the same run so the anchor and the ablation share a box.

  `reversed?` runs the whole arm list back to front. B6 already rotates
  the arm order on the SAMPLE index, so no arm is permanently first into
  a cold cache — but a large-object arm has been observed to inflate its
  successor 2× reproducibly on this surface (`rf2-88pie`), and the only
  way to see that is to run both orders."
  ([] (make-update-arms false))
  ([reversed?]
   (let [arms (conj (rows/make-update-arms)
                    (b9-update-arm :b9-ref cell-leaf-ref :b9-ref/grid false)
                    (b9-update-arm :b9-nc  cell-leaf-nc  :b9-nc/grid  true))]
     (if reversed? (vec (reverse arms)) arms))))

;; --- the MOUNT row, over B6's W2 storm shape --------------------------------
;;
;; The update row prices the contract on a write. Mount is the other axis
;; the release gate carries, and `useSyncExternalStore` is paid on every
;; mount whether anything ever moves or not — so the storm witness runs
;; the same six arms through B6's mount measurement.

(defn- b9-mount-arm [id leaf]
  {:id      id
   :mount   (fn [container _props _n]
              (let [r (react-dom-client/createRoot container)]
                (.render r (storm-of leaf))
                r))
   :unmount (fn [r] (.unmount r))})

(defn mount-witness
  "B6's W2 witness — 300 sub-free leaf boundaries, 301 elements — with the
  two B9 rungs added to its published arms.

  W2 is deliberately the shape that MAXIMISES Freehand's compiled elision,
  and B6 flags it as overstating Freehand's advantage. That warning is
  irrelevant to the pair measured here, which is interpreted-shaped on
  both sides and differs only in the contract."
  [reversed?]
  (let [w    (first (filter #(= :W2 (:id %)) rows/mount-witnesses))
        arms (into (vec (:arms w))
                   [(b9-mount-arm :b9-ref-storm storm-leaf-ref)
                    (b9-mount-arm :b9-nc-storm  storm-leaf-nc)])]
    (assoc w
           :id :W2-B9
           :headline? false
           :arms (if reversed? (vec (reverse arms)) arms))))

;; ===========================================================================
;; 8. The sync-lane probe — the claim in the docstring, measured
;; ===========================================================================

(defn tear-probe!
  "WHAT THE GUARANTEE BUYS, measured on a WATCHABLE host — the browser,
  where a retained dependency has a change watch and the headless
  argument for invariant 5 does not apply.

  Two cases, each run through both commits, each moving the source in the
  render→commit gap:

  - **retained** — the site was committed once already, so its handle
    carries a watch installed at the FIRST commit. The move therefore
    marks the cell through the ordinary channel as well.
  - **staged** — a FIRST commit of that site. `obs/acquire!` installs the
    watch DURING this commit, which is after the move already happened,
    so the watch has nothing to fire about.

  Answers, per case per commit: `:revision-delta` (did invariant 5
  correct?), `:dirty?` (is the ordinary notification channel going to
  correct it at the next window?) and `:published` (what value the
  committed bundle carries).

  This is the difference between reporting a bug class and asserting one."
  []
  (let [fid :b9-tear/frame
        q   [:b6/cell 0]
        run (fn [commit! retained?]
              (when (nil? (frame/frame fid)) (rf/make-frame {:id fid}))
              (frame/replace-app-db! fid {:cells [:v0]})
              (let [c (cell/cell :b9/tear)]
                (when retained?
                  ;; One earlier commit, so the site's handle — and its
                  ;; change watch — already exist when the move happens.
                  (let [cand0 (cell/candidate c fid)]
                    (cell/with-capture cand0 (fn [] (cell/observe! q)))
                    (commit! cand0)))
                (let [cand   (cell/candidate c fid)
                      _      (cell/with-capture cand (fn [] (cell/observe! q)))
                      before (cell/revision c)
                      ;; THE GAP: the source moves after the render probed
                      ;; it and before the commit publishes it.
                      _      (frame/replace-app-db! fid {:cells [:v1]})
                      result (commit! cand)
                      out    {:commit         result
                              :revision-delta (- (cell/revision c) before)
                              :dirty?         (cell/dirty? c)
                              :published      (str (:value (first (:observations (cell/evidence c)))))}]
                  (cell/disconnect! c)
                  out)))]
    {:retained {:shipped  (run cell/commit! true)
                :ablated  (run nc-commit!   true)}
     :staged   {:shipped  (run cell/commit! false)
                :ablated  (run nc-commit!   false)}}))

(defn sync-lane-probe!
  "Does a boundary WITHOUT `useSyncExternalStore` repaint inside an empty
  `react-dom/flushSync`, the way `rf2-w2m25`'s synchronous commit door
  promises?

  Mounts one grid of each rung, writes, yields ONE microtask, runs an
  EMPTY `flushSync` — B6's published window, with no drain — and reads the
  written cell back out of the DOM. Answers a promise of
  `{:ref <bool> :nc <bool>}`: whether each rung's DOM held the value that
  was written.

  This is a control, not a row. It costs two mounts and two writes, and it
  is the difference between reporting a scheduling consequence and
  asserting one."
  []
  (let [mk (fn [leaf fid]
             (let [c (js/document.createElement "div")]
               (.appendChild js/document.body c)
               (ensure-frame! fid rows/cells-n)
               (let [r (react-dom-client/createRoot c)]
                 (react-dom/flushSync (fn [] (.render r (grid-of leaf fid))))
                 {:container c :root r :fid fid})))
        ref (mk cell-leaf-ref :b9-probe-ref/grid)
        nc  (mk cell-leaf-nc  :b9-probe-nc/grid)
        one (fn [{:keys [container fid]} val]
              (frame/replace-app-db! fid {:cells (vec (repeat rows/cells-n val))})
              (-> (js/Promise.resolve nil)
                  (.then (fn [_]
                           (react-dom/flushSync (fn [] nil))
                           (= (str val) (rows/cell-text container 0))))))]
    (-> (one ref 90001)
        (.then (fn [ref-ok]
                 (-> (one nc 90002)
                     (.then (fn [nc-ok]
                              (doseq [{:keys [container root]} [ref nc]]
                                (react-dom/flushSync (fn [] (.unmount root)))
                                (.remove container))
                              {:ref ref-ok :nc nc-ok}))))))))
