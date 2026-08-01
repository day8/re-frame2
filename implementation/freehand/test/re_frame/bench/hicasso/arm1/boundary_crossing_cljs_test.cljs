(ns re-frame.bench.hicasso.arm1.boundary-crossing-cljs-test
  "A READ DEFERRED ACROSS A BOUNDARY CROSSING (rf2-2rtt6.45).

  `runtime-cljs-test`'s
  `every-read-that-escapes-the-render-is-loud-rather-than-a-missing-edge`
  settled four escapes — a stored handler, a handler invoked, an
  author-held `delay`, and a stashed lazy seq — and all four share one
  property: the deferred read runs when **no** body is running, so
  `read-key!` finds no frame and says so. This file is about the fifth,
  which has the opposite property and is therefore silent.

  ## The defect these rows are about

  `read-key!`'s guard asks *is any body running*, not *is THIS body
  running*, because `rstate` is one module-level object. A read deferred
  past its author's body and forced inside a **different** boundary's
  render therefore does not throw: it lands on that boundary's scratch,
  and the boundary that produced it holds no edge for it.

  The carrier is the boundary hand-off. `convert-prop-value` sends any
  collection at a NATIVE prop position through `clj->js`, so the eager
  codec really does force everything it walks there — but
  `boundary-element` hands `body-props` across as a raw ClojureScript
  map, and the shell reads it back as one. No conversion, no walk, no
  realisation:

      (defview child  [{:keys [rows]}] [:ul (for [r rows] [:li (str r)])])
      (defview parent [_] [child {:rows (for [id (sub [:visible-ids])]
                                          (:title (sub [:todo id])))}])

  `(sub [:visible-ids])` is the `for`'s binding expression and runs
  eagerly; every `(sub [:todo id])` runs when the CHILD walks the seq.

  **And it does not merely put the edge on the wrong boundary.** A
  `LazySeq` caches what it realised, so the wrong reader re-renders
  exactly once: on that re-render its body walks an already-realised
  seq, `sub` is never called, its read set collapses to the empty set,
  React re-subscribes and the row edges are dropped. The right reader
  never re-rendered, so the seq is never rebuilt. One correction, then a
  value that is **correct on screen, frozen thereafter, attributable to
  nothing** — the class the judgement page calls the worst failure this
  surface can have.

  ## What closes it

  `codec/realize-deep`, called once on `body-props` in
  `boundary-element`. It forces every lazy sequence reachable from the
  props map — including the `:children` vector, whose one-level flatten
  leaves a nested seq unrealised — and returns the map itself, by
  identity, because realising a `LazySeq` caches into the seq and copies
  nothing. The read is then forced by the same pass that turns hiccup
  into elements, inside the window of the body that WROTE it, which is
  the property the eager codec was always supposed to have.

  It is emphatically not per-boundary render identity: nothing here can
  tell one render attempt from another, `rstate` is still one object, and
  the shell still holds two hooks and no per-instance state.

  ## What these rows are

  The seam driven by hand, as in `staged-read-tear-cljs-test`:
  `render-body` is the render, `commit-boundary!` is React's
  `subscribe`, and the props map the codec built for the crossing is read
  straight off the React element. `boundary-crossing-dom-cljs-test` then
  proves the DOM moves, in real Chromium, on a later write — the half a
  first paint cannot tell you.

  The adapter is UIx's, not `plain-atom`'s: plain-atom has no reactivity
  layer, so a subscription under it never notifies and every commit
  assertion below would pass by never firing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-boundary-crossing)

(defn- seeded! []
  (rt/reset-runtime!)
  (dogfood/make-frame! frame-id 3)
  frame-id)

(defn- key-of [query] [frame-id query])

;; ---------------------------------------------------------------------------
;; The two bodies, and the head that makes the crossing a real one
;; ---------------------------------------------------------------------------
;;
;; The bodies are kept as plain fns and the boundary head is minted from
;; the child's, so this suite can run both halves of the crossing without
;; React: the codec sees a genuine marked head and builds the genuine
;; `rfProps` map, and `render-body` then runs the child's body on exactly
;; the map React would have handed it.

(defn- child-body [{:keys [rows]}]
  [:ul.child (for [r rows] [:li.cell (str r)])])

(def ^:private child-view (rt/mint-view! "boundary-crossing/child" child-body))

(defn- parent-body [_]
  [child-view {:rows (for [id (rt/sub [:dogfood/visible-ids])]
                       (:title (rt/sub [:dogfood/todo id])))}])

(defn- nested-child-body [{:keys [children]}]
  (into [:ul.nested] children))

(def ^:private nested-child-view
  (rt/mint-view! "boundary-crossing/nested-child" nested-child-body))

(defn- nested-parent-body
  "Carrier (b): the seqs are in the CHILDREN position, one level below the
  splice `realize-children` performs. The outer `for` is the boundary's
  one trailing form, so the flatten forces ITS spine and hands each inner
  `for` on as an element, unrealised."
  [_]
  [nested-child-view
   (for [chunk (partition-all 2 (rt/sub [:dogfood/visible-ids]))]
     (for [id chunk]
       [:li.cell {:key id} (str (:title (rt/sub [:dogfood/todo id])))]))])

(defn- crossing-props
  "The props map the codec built for the crossing — precisely what the
  shell reads back out of `rfProps` and hands the child's body."
  [element]
  (unchecked-get (.-props element) "rfProps"))

(defn- render [body-fn props]
  (let [element (rt/render-body frame-id body-fn props)]
    {:element element :entry (rt/last-reads) :reads (rt/reads-of (rt/last-reads))}))

(defn- mounted!
  "A boundary at the seam React occupies: render the body, commit its
  reads, and hand back the notification counter and the cleanup."
  [body-fn props]
  (let [{:keys [element entry reads]} (render body-fn props)
        hits    (volatile! 0)
        release (rt/commit-boundary! entry (fn [] (vswap! hits inc)))]
    {:element element :entry entry :reads reads :hits hits :release! release}))

(def ^:private row-keys
  #{[frame-id [:dogfood/todo 0]]
    [frame-id [:dogfood/todo 1]]
    [frame-id [:dogfood/todo 2]]})

;; ---------------------------------------------------------------------------
;; 1 — the crossing attributes the read to the boundary that WROTE it
;; ---------------------------------------------------------------------------

(deftest a-lazy-seq-handed-across-a-boundary-is-the-parents-read
  (seeded!)
  (testing "the reads deferred into a seq at a boundary's prop position
           belong to the body that wrote them, not to the body that
           happens to walk the seq"
    (let [parent (render parent-body {})
          child  (render child-body (crossing-props (:element parent)))]
      (is (= (conj row-keys (key-of [:dogfood/visible-ids])) (:reads parent))
          "every row query the parent's `for` produced is the PARENT's edge")
      (is (= #{} (:reads child))
          "and nothing at all landed on the child — the seq the codec handed
           it was already realised, so its body called `sub` zero times"))))

(deftest the-childs-second-render-reads-nothing-which-is-why-the-first-row-matters
  (seeded!)
  (testing "**Why the misattribution is not merely untidy.** A `LazySeq`
           caches, so a boundary that realised one and is then re-rendered
           with the same props reads NOTHING the second time. Had the row
           keys landed on the child, this render would have collapsed its
           read set to empty, React would have re-subscribed, and every row
           edge would have been dropped with no boundary left holding one —
           permanent staleness, with a correct first paint."
    (let [parent (render parent-body {})
          props  (crossing-props (:element parent))
          first' (render child-body props)
          again  (render child-body props)]
      (is (= #{} (:reads first')))
      (is (= #{} (:reads again))
          "the second walk of a realised seq calls `sub` zero times — true
           before this fix and after it; what changed is who holds the edge
           when it happens"))))

(deftest carrier-b-a-nested-seq-in-the-children-position
  (seeded!)
  (testing "`realize-children` flattens exactly one level, so a `for`
           inside a `for` reaches the child's body unrealised. The same
           walk covers it, because `:children` is a key in the same props
           map."
    (let [parent (render nested-parent-body {})
          props  (crossing-props (:element parent))
          child  (render nested-child-body props)]
      (is (= 2 (count (:children props)))
          "the outer seq spliced into two children, one per chunk")
      (is (every? seq? (:children props))
          "and each child is still a seq — the ABI's one-level splice is
           unchanged; what changed is that the seq arrives realised")
      (is (= (conj row-keys (key-of [:dogfood/visible-ids])) (:reads parent)))
      (is (= #{} (:reads child))))))

;; ---------------------------------------------------------------------------
;; 2 — and the commit puts the edges where the reads were attributed
;; ---------------------------------------------------------------------------

(deftest a-write-to-a-row-the-parent-produced-re-runs-the-parent
  (seeded!)
  (testing "the half a first render cannot tell you, at the seam: the
           notification must reach the boundary that can REBUILD the seq.
           A notification delivered to the child is worth nothing — its
           props are the same object and its seq is already realised."
    (let [parent (mounted! parent-body {})
          child  (mounted! child-body (crossing-props (:element parent)))]
      (is (= 4 (:edges (rt/stats)))
          "four edges, all of them the parent's: the ids query and one per row")
      (rt/dispatch! frame-id [:dogfood/edit-draft 1 "crossed"])
      (rt/dispatch! frame-id [:dogfood/commit 1])
      (is (= 1 @(:hits parent))
          "the parent re-runs, so the seq is rebuilt and the child receives
           a new one")
      (is (= 0 @(:hits child))
          "and the child is not notified in its stead — a re-render there
           would repaint the identical realised seq and drop the edges")
      ((:release! child))
      ((:release! parent)))))

(deftest the-crossing-leaves-no-residue
  (seeded!)
  (let [parent (mounted! parent-body {})
        child  (mounted! child-body (crossing-props (:element parent)))]
    ((:release! child))
    ((:release! parent))
    (rt/reset-runtime!)
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0} (rt/residue)))))

;; ---------------------------------------------------------------------------
;; 3 — the walk itself: what it forces, and what it must not disturb
;; ---------------------------------------------------------------------------

(deftest realize-deep-returns-its-argument-by-identity
  (testing "realising a `LazySeq` caches into the seq, so the walk rebuilds
           nothing and the props map the child receives is the map the
           codec built — no copy, no fresh identity for React to see"
    (let [m {:rows (map inc (range 3)) :label "x"}]
      (is (identical? m (codec/realize-deep m)))
      (is (identical? (:rows m) (:rows (codec/realize-deep m))))))
  (testing "a scalar and a nil pass straight through"
    (is (= 7 (codec/realize-deep 7)))
    (is (nil? (codec/realize-deep nil)))
    (is (= "s" (codec/realize-deep "s")))))

(deftest realize-deep-forces-every-lazy-seq-it-can-reach
  (testing "the reach is the one `clj->js` already has at a native prop
           position, which is what makes the structural claim true as
           written rather than true for one of the two positions"
    (doseq [[label build] [["a bare seq"            (fn [!n] {:v (map (fn [i] (vswap! !n inc) i) (range 3))})]
                           ["a seq inside a vector" (fn [!n] {:v [(map (fn [i] (vswap! !n inc) i) (range 3))]})]
                           ["a seq inside a map"    (fn [!n] {:v {:k (map (fn [i] (vswap! !n inc) i) (range 3))}})]
                           ["a seq inside a seq"    (fn [!n] {:v (list (map (fn [i] (vswap! !n inc) i) (range 3)))})]
                           ["a seq inside a set"    (fn [!n] {:v #{(map (fn [i] (vswap! !n inc) i) (range 3))}})]]]
      (let [!n (volatile! 0)
            v  (build !n)]
        (codec/realize-deep v)
        (is (= 3 @!n) label)))))

(deftest realize-deep-walks-map-keys-without-disturbing-the-map
  (testing "descending into the key half of an entry is a READ, not a
           rewrite (rf2-2rtt6.32). The map comes back by identity, each key
           comes back by identity, and lookup still finds what it found
           before — by the identical key and by an equal one, so nothing
           the map hashed has moved"
    (let [composite [:composite 1]
          m         {composite :a :plain :b "s" :c 7 :d}
          walked    (codec/realize-deep m)]
      (is (identical? m walked))
      (is (= m walked))
      (is (= (keys m) (keys walked)))
      (is (identical? composite (first (filter vector? (keys walked)))))
      (is (= :a (get walked composite)))
      (is (= :a (get walked [:composite 1])))
      (is (= :b (get walked :plain)))
      (is (= :c (get walked "s")))
      (is (= :d (get walked 7)))))
  (testing "a map big enough to be a hash map rather than an array map is
           equally untouched"
    (let [m      (into {} (map (fn [i] [[:k i] i]) (range 32)))
          walked (codec/realize-deep m)]
      (is (identical? m walked))
      (is (= 32 (count walked)))
      (is (= 17 (get walked [:k 17]))))))

(deftest realize-deep-reaches-a-lazy-seq-at-a-key-position-too
  (testing "the reason keys were skipped was that hashing a seq realises
           it, so nothing unrealised could already be one. A small map
           literal is a `PersistentArrayMap`: it compares keys with `=`
           against what it has already accumulated and hashes nothing, so
           the first key of a one-entry map is never touched. The seq
           arrives unrealised and the walk is what forces it"
    (let [!n             (volatile! 0)
          m              {(map (fn [i] (vswap! !n inc) i) (range 3)) :marked}
          at-construction @!n]
      (is (zero? at-construction)
          "constructing the map neither hashed nor compared the key")
      (codec/realize-deep m)
      (is (= 3 @!n)
          "and the walk reaches it, inside the window of the body that
           wrote it"))))

(deftest the-keyword-key-short-circuit-skips-only-a-provable-no-op
  (testing "the key half is walked through one guard — a `Keyword` is
           neither a collection nor a `Delay`, so the walk on one can
           reach nothing and return nothing. The premise is pinned here
           rather than assumed, because the guard is only sound while it
           holds"
    (is (not (coll? :k)))
    (is (not (delay? :k))))
  (testing "and the guard skips the KEY, never the entry: a keyword-keyed
           entry's value half is walked exactly as before"
    (is (thrown-with-msg? js/Error #"unforced `delay` reached a boundary's props"
                          (codec/realize-deep {:k (delay 1)})))
    (let [!n (volatile! 0)]
      (codec/realize-deep {:k (map (fn [i] (vswap! !n inc) i) (range 3))})
      (is (= 3 @!n)))))

(deftest realize-deep-does-not-walk-into-a-react-element-or-a-function
  (testing "a React element is an opaque JS object and a function is a
           value, not a structure; neither is a collection and neither is
           descended into"
    (let [el (codec/as-element [:li "a"])
          f  (fn [] :never-called)]
      (is (identical? el (codec/realize-deep el)))
      (is (identical? f (codec/realize-deep f))))))
