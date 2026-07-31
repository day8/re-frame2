(ns re-frame.bench.hicasso.arm2.runtime
  "THE PATCH RUNTIME — boundaries, the sub layer, and the commit
  (rf2-2rtt6.10, architecture.md Arm 2).

  Arm 2's one-sentence definition is *dirty boundaries re-run **inside
  the commit** against the committed snapshot; an own keyed hiccup differ
  applies the patch; React exists only at `defhost` islands.* This
  namespace is the first and third clauses; [[patch]] is the second.

  ## Invariant 5, by construction — the whole argument

  The invariant is that every read in one update sees one snapshot. Arm 1
  has to *build* that: React schedules renders, so a generation fence
  keeps all reads within one render pass on one commit, and a
  staged-stale CI witness guards the fence. Arm 2 has no fence, because
  it has nothing to fence — the property falls out of three facts a
  reader can check in this file:

  1. **[[sub]] cannot read without a bound snapshot.** `*snapshot*` is
     nil outside a commit and a read there is a loud error, not a fall
     back to \"whatever the frame holds now\". There is no ambient
     current-db path in this runtime at all.
  2. **`*snapshot*` is bound in exactly two places** — [[mount-root!]]
     and [[commit!]] — each binding it once, to one value, read once.
  3. **[[commit!]] is non-reentrant.** A dispatch that arrives while a
     commit is running does not nest: it raises a flag and the commit
     loop takes another turn *after* the current one finishes. So no
     second binding can appear inside the extent of the first.

  Together: every read taken during one commit resolves against the same
  value, and the property has no runtime cost — no generation counter,
  no per-read staleness check, no fence to get wrong. The
  `snapshots-per-commit` counter in [[stats]] is evidence, not
  machinery: it is what the witness reads, and it is 1 because 1 is the
  only value the three facts allow.

  ## The sub layer: recompute at commit, cut on equality

  Arm 2 subscribes to nothing. It has no reactions, no ref-counts, and no
  reactive substrate on the view path: a commit recomputes each **watched
  key** (a key some live boundary reads — the index's own domain) with
  `rf/compute-sub` against the committed db and compares the result with
  `=`. The keys whose values changed are the dirty set.

  That is architecture.md's fourth front-half item taken literally
  (*equality cutoff on subscription values at the existing sub layer;
  unchanged hot reads perform no new attach/release*), and it is the
  reason `zero-leaked-subscription-refcounts-after-teardown` is
  structural for this arm rather than a test that passes: there is no
  ref-count to leak. The value cache is rebuilt from the index's domain
  every commit, so a key nobody reads any more is gone by construction
  too.

  The cost is honest and worth stating: **O(watched keys) recomputes per
  commit**, where a reactive arm pays O(dirty subgraph). For layer-2 subs
  reading `:db` — the census's overwhelming majority — the two are the
  same set, because every db write invalidates every db-reading reaction
  and Reagent recomputes them all before its own equality cutoff. Where
  they differ is a deep derived graph, and that is a measurement this arm
  owes rather than a claim it makes.

  ## The boundary record

  One CLJS map per live boundary in one global map:

      {:id 7 :view <fn> :props {…} :hiccup […] :node <Element>}

  Five fields, shared structure, no object graph. There is no hook cell,
  no epoch, no subscription cell, no fiber, and — because the differ
  keeps the previous hiccup as the previous tree — nothing per element
  either. A boundary's whole exclusive retention is this map, its entry
  in the index's two edge maps, and one `WeakMap` entry pointing its DOM
  node back at its id. That inventory is what the heap ladder prices
  against the 0.4–0.5 KB shell target.

  ## `defview` is a function here, deliberately

  [[view]] mints a boundary from a plain body function rather than
  through a macro. The spike is measuring a runtime, not an authoring
  surface: HD-002's three-rendering ergonomics verdict rides the grouped
  and collector *surfaces*, which are Arm 1's dogfood work, and adding a
  macro here would put a second uncompared authoring spelling into a
  tournament whose whole value is that the arms differ in one place."
  (:require [re-frame.bench.hicasso.arm2.controlled :as controlled]
            [re-frame.bench.hicasso.arm2.patch :as patch]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.front.sub-index :as idx]
            [re-frame.core :as rf]))

(declare dispatch! commit! re-run!)

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private !frame      (atom nil))
(defonce ^:private !boundaries (atom {}))
(defonce ^:private !next-id    (atom 0))
(defonce ^:private !values     (atom {}))
(defonce ^:private !root       (atom nil))
(defonce ^:private node->boundary (js/WeakMap.))

(defonce ^:private !committing (atom false))
(defonce ^:private !again      (atom false))

(defonce stats
  (atom {:commits 0 :body-runs 0 :sub-reads 0 :sub-computes 0
         :dirty-boundaries 0 :snapshots-per-commit 0}))

(defn reset-stats! [] (swap! stats merge {:commits 0 :body-runs 0 :sub-reads 0
                                          :sub-computes 0 :dirty-boundaries 0
                                          :snapshots-per-commit 0}) nil)

(def ^:dynamic *snapshot*
  "The committed app-db this commit's reads resolve against. Nil outside
  a commit, which is what makes a read outside one a loud error rather
  than a silently different answer."
  nil)

(def ^:private ^:dynamic *ran* nil)
(def ^:private ^:dynamic *seen* nil)

(defn current-snapshot [] *snapshot*)

;; ---------------------------------------------------------------------------
;; The sub layer
;; ---------------------------------------------------------------------------

(def ^:private miss #js {})

(defn- snapshot []
  (or *snapshot*
      (throw (ex-info (str "A subscription was read outside a commit. "
                           "[:rf.error/hicasso-read-outside-commit]")
                      {:rf.error/id :rf.error/hicasso-read-outside-commit
                       :where       'arm2.runtime/sub
                       :reason      "There is no ambient current-db in this runtime — invariant 5 holds because a read without a bound snapshot is impossible."
                       :recovery    :read-inside-a-boundary-body}))))

(defn sub
  "Read `query-v` from inside a boundary body. Records the edge on the
  index and returns the value this commit's snapshot computes.

  The read is a map lookup in the steady state: the commit has already
  recomputed every watched key. A key this body is the first to read
  computes once, against the same snapshot."
  [query-v]
  (idx/read! query-v)
  (swap! stats update :sub-reads inc)
  (let [db (snapshot)
        v  (get @!values query-v miss)]
    (when (some? *seen*) (vswap! *seen* conj db))
    (if (identical? v miss)
      (let [computed (rf/compute-sub query-v db)]
        (swap! stats update :sub-computes inc)
        (swap! !values assoc query-v computed)
        computed)
      v)))

(defn- recompute-watched
  "Rebuild the value cache from the index's own domain and return the set
  of keys whose value changed. Rebuilding rather than updating is what
  drops the keys nobody reads any more — the cache cannot outlive the
  edges."
  [db]
  (let [watched (keys (:sub->bs (idx/snapshot)))
        old     @!values]
    (loop [ks (seq watched) acc (transient {}) dirty (transient #{})]
      (if-not ks
        (do (reset! !values (persistent! acc)) (persistent! dirty))
        (let [k (first ks)
              v (rf/compute-sub k db)
              o (get old k miss)]
          (swap! stats update :sub-computes inc)
          (recur (next ks)
                 (assoc! acc k v)
                 (if (or (identical? o miss) (not= o v)) (conj! dirty k) dirty)))))))

;; ---------------------------------------------------------------------------
;; Boundaries
;; ---------------------------------------------------------------------------

(defn view
  "Mint a boundary from a body function. The returned function is the
  hiccup head; it carries its own body in a holder so an HMR swap can
  replace the body without changing the identity the tree was built
  from."
  [view-id body-fn]
  (let [!body (atom body-fn)
        f     (fn [props] (@!body props))]
    (unchecked-set f "hicassoBody" !body)
    (unchecked-set f "hicassoViewId" (str view-id))
    (codec/mark-boundary! f)))

(defn view-id [view] (unchecked-get view "hicassoViewId"))

(defn- boundary-props
  "The props a boundary body sees: the element's props map without
  `:key`, plus `:children` when the element carries any (HD-016)."
  [form]
  (let [p        (or (patch/props-of form) {})
        children (patch/flatten-children form (patch/first-child-index form))]
    (cond-> (dissoc p :key)
      (seq children) (assoc :children children))))

(defn- run-body
  "Run one boundary body with the frame bound ambiently and the read
  collector open. The frame binding spans the *patch* as well as the
  body, because intent lowering happens during the patch — a changed
  event prop is lowered at the moment it is written, and it needs the
  same frame the body resolved."
  [id view props]
  (swap! stats update :body-runs inc)
  (first (idx/with-reads id (fn [] (view props)))))

(defn- mount-boundary!
  "Mount one boundary element and return the single node it occupies."
  [form]
  (let [view  (nth form 0)
        id    (swap! !next-id inc)
        props (boundary-props form)]
    (idx/mount! id)
    (intent/with-frame dispatch!
      (fn []
        (let [hiccup (run-body id view props)
              node   (patch/mount-child hiccup)]
          (.set node->boundary node id)
          (swap! !boundaries assoc id {:id id :view view :props props :hiccup hiccup :node node})
          node)))))

(defn- patch-boundary!
  "A parent re-rendered and reached this boundary with (possibly) new
  props. Re-running is the right default — HD-006 keeps React's
  semantics, and the differ's tier 2 has already established that the
  element is not `=` to last render's."
  [node _old-form new-form]
  (if-some [id (.get node->boundary node)]
    (re-run! id (boundary-props new-form))
    node))

(defn unmount-subtree!
  "Unmount every boundary at or under `node`. Unconditional on every
  removal and every replacement, which is what makes the standing
  teardown assertion structural."
  [node]
  (when-some [id (.get node->boundary node)]
    (idx/unmount! id)
    (swap! !boundaries dissoc id)
    (.delete node->boundary node))
  (loop [c (.-firstChild node)]
    (when c
      (let [next (.-nextSibling c)]
        (unmount-subtree! c)
        (recur next))))
  nil)

(defn- re-run!
  "Re-run one boundary and patch its subtree in place. Returns the node
  it now occupies."
  [id props]
  (if-some [{:keys [view node hiccup] :as b} (get @!boundaries id)]
    (let [props  (or props (:props b))
          parent (.-parentNode node)]
      (when (some? *ran*) (vswap! *ran* conj id))
      (intent/with-frame dispatch!
        (fn []
          (let [new-hiccup (run-body id view props)
                node'      (patch/patch-child! parent node hiccup new-hiccup)]
            (when-not (identical? node' node)
              (.delete node->boundary node)
              (.set node->boundary node' id))
            (swap! !boundaries update id merge {:props props :hiccup new-hiccup :node node'})
            node'))))
    nil))

(defn install!
  "Hand the differ its three boundary operations. Idempotent."
  []
  (patch/set-boundary-ops! {:mount   mount-boundary!
                            :patch   patch-boundary!
                            :unmount unmount-subtree!})
  nil)

;; ---------------------------------------------------------------------------
;; The commit
;; ---------------------------------------------------------------------------

(defn- run-commit!
  "One turn of the commit: recompute the watched keys against the
  committed db, ask the index which boundaries that dirties, and re-run
  them **here**, inside this extent, against this snapshot."
  []
  (let [db (rf/app-db-value @!frame)]
    (binding [*snapshot* db
              *ran*      (volatile! #{})
              *seen*     (volatile! #{})]
      (let [dirty (recompute-watched db)
            bs    (if (seq dirty) (idx/commit! dirty) #{})]
        (swap! stats update :dirty-boundaries + (count bs))
        ;; Ascending id order is ancestor-first for a tree built top down,
        ;; and `*ran*` makes the order an optimization rather than a
        ;; correctness condition: a boundary an ancestor already re-ran is
        ;; skipped, and one the ancestor's tier-2 cutoff skipped still runs.
        (doseq [id (sort bs)]
          (when (and (contains? @!boundaries id) (not (contains? @*ran* id)))
            (re-run! id nil)))
        (swap! stats assoc :snapshots-per-commit (count @*seen*))))
    (swap! stats update :commits inc))
  nil)

(defn commit!
  "Apply the committed state to the DOM.

  Non-reentrant by design: a dispatch raised from inside a body, an
  event handler, or a patch does not open a second commit inside the
  first — it raises a flag and this loop takes another turn. That is
  fact (3) of the invariant-5 argument, and it is also the reason a
  controlled input cannot observe a half-applied commit."
  []
  (if @!committing
    (do (reset! !again true) nil)
    (do (reset! !committing true)
        (try
          (loop []
            (reset! !again false)
            (run-commit!)
            (when @!again (recur)))
          ;; The end-of-event restore. After this line the focused field
          ;; reads what the model says — see arm2.controlled.
          (controlled/converge-focused!)
          (finally (reset! !committing false)))
        nil)))

(defn dispatch!
  "**The synchronous door** (HD-019, transferred to the renderer). The
  event drains, the commit recomputes, the dirty boundaries re-run, the
  DOM is patched and the controlled fields converge — all inside the
  browser's discrete event, before it returns.

  This is the function [[intent/with-frame]] binds ambiently, so every
  lowered event vector in the tree calls exactly this."
  [event]
  (rf/with-frame @!frame (rf/dispatch-sync event))
  (commit!)
  nil)

;; ---------------------------------------------------------------------------
;; The root (HD-021(b))
;; ---------------------------------------------------------------------------

(defn mount-root!
  "Associate a DOM node, a frame and a root element; render; return an
  **idempotent teardown**.

      (mount-root! {:container node :frame :app :element [my-view {}]})"
  [{:keys [container frame element]}]
  (install!)
  (reset! !frame frame)
  (let [db   (rf/app-db-value frame)
        node (binding [*snapshot* db *seen* (volatile! #{})]
               (let [n (patch/mount-child element)]
                 (.appendChild container n)
                 n))
        torn (volatile! false)]
    (reset! !root {:container container :node node})
    (fn teardown []
      (when-not @torn
        (vreset! torn true)
        (unmount-subtree! node)
        (when (identical? container (.-parentNode node)) (.removeChild container node))
        (reset! !values {})
        (reset! !root nil))
      nil)))

(defn root-node [] (:node @!root))

(defn reset-runtime!
  "Drop every boundary, edge, cached value and root. The test fixture's
  door; also what a root teardown leaves behind when a page holds one
  root."
  []
  (reset! !boundaries {})
  (reset! !values {})
  (reset! !root nil)
  (reset! !frame nil)
  (reset! !committing false)
  (reset! !again false)
  (idx/reset-index!)
  (reset-stats!)
  nil)

;; ---------------------------------------------------------------------------
;; HMR (HD-021(c))
;; ---------------------------------------------------------------------------

(defn swap-body!
  "Replace a view's body without changing the identity the tree was built
  from: the root, the frame and app-db survive, the new body is used, and
  no subscription leaks because the boundary never unmounted."
  [view new-body]
  (reset! (unchecked-get view "hicassoBody") new-body)
  (let [ids (->> @!boundaries vals (filter #(identical? view (:view %))) (map :id) sort)
        db  (rf/app-db-value @!frame)]
    (binding [*snapshot* db *ran* (volatile! #{}) *seen* (volatile! #{})]
      (doseq [id ids] (re-run! id nil))))
  nil)

;; ---------------------------------------------------------------------------
;; Observation — for the witnesses
;; ---------------------------------------------------------------------------

(defn boundary-count [] (count @!boundaries))

(defn watched-keys [] (set (keys @!values)))

(defn boundary-ids [] (set (keys @!boundaries)))

(defn boundary-of
  "The boundary record whose node is `node`, or nil."
  [node]
  (when-some [id (.get node->boundary node)] (get @!boundaries id)))

(defn force-commit!
  "Run a commit without dispatching — the door a witness uses to apply a
  write it made through some other path."
  []
  (commit!))
