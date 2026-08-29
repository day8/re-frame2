(ns re-frame.bench.hicasso.arm1.deferred-read-cljs-test
  "AN EXPLICITLY DEFERRED READ IS LOUD, NOT SILENT (rf2-2rtt6.32).

  The last shape in the read-outside-the-render family, and the only one
  the two existing guards cannot see between them.

  ## Where this sits

  `runtime-cljs-test`'s
  `every-read-that-escapes-the-render-is-loud-rather-than-a-missing-edge`
  settles four escapes — a stored handler, a handler invoked, an
  author-held `delay`, and a stashed lazy seq — all of which run when
  **no** body is running, so `read-key!` finds no frame and throws.
  `boundary-crossing-cljs-test` settles the fifth — a lazy seq handed
  across a boundary and realised in the child's render, where a frame IS
  bound and the guard is satisfied — by forcing it at the crossing,
  inside the window of the body that wrote it.

  Both of those repair or refuse **structure**. This file is about what
  is left: an **explicit deferral**, handed across a boundary and forced
  there. A `delay` is the canonical spelling, and it is the one carrier
  the crossing walk may not repair, because repairing it means forcing
  it, and *not now* is the entire content of what a `delay` says.

  ## The fault, and why it is the worst one available

      (defview child  [{:keys [d]}] [:li (str @d)])
      (defview parent [_] [child {:d (delay (:title (sub [:todo 1])))}])

  Measured on the runtime before this change (the probe is preserved as
  `the-fault-the-refusal-replaces` below): the parent's read set is
  **empty**, the child's first render holds `[:todo 1]`, and the child's
  second render holds **nothing** — a `Delay` caches, so the second walk
  calls `sub` zero times, the read set collapses, React re-subscribes and
  the edge is dropped. The parent never held an edge, so it never
  re-renders and the delay is never rebuilt. Correct on screen, frozen
  thereafter, attributable to nothing.

  ## What this change does, and what it deliberately does not

  `codec/realize-deep` — already the one walk at the crossing — refuses
  an **unforced** `delay` it reaches, and refuses it *inside the render
  of the body that wrote it*, so the stack lands on the author's own call
  site. It does not force the delay: that would change the meaning of the
  program to protect a property the author was never told about, which is
  a silent repair of a different kind. The right answer for a deferred
  read is a diagnostic.

  A **function** prop is not in this class and is not a defect at all —
  the rows below verify rather than assert it — because the child calls
  it on every render, so the read repeats, the edge is the child's, and
  the child is the boundary whose output depends on it.

  ## The declared limit, recorded rather than discovered

  The walk descends into data structures. A **mutable reference** is not
  one, and a deferral parked inside an atom — or in a module-level var
  the codec never sees at all — is outside what any structural pass can
  reach. Those rows are here, asserting the unrepaired behaviour, so the
  limit is a written-down property of Surface B and not a surprise.

  The adapter is UIx's, matching `boundary-crossing-cljs-test`:
  `plain-atom` has no reactivity layer, so a subscription under it never
  notifies and every commit assertion would pass by never firing."
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

(def ^:private frame-id ::arm1-deferred-read)

(defn- seeded! []
  (rt/reset-runtime!)
  (dogfood/make-frame! frame-id 3)
  frame-id)

(defn- key-of [query] [frame-id query])

(defn- crossing-props
  "The props map the codec built for the crossing — what the shell reads
  back out of `rfProps` and hands the child's body."
  [element]
  (unchecked-get (.-props element) "rfProps"))

(defn- render [body-fn props]
  (let [element (rt/render-body frame-id body-fn props)]
    {:element element :entry (rt/last-reads) :reads (rt/reads-of (rt/last-reads))}))

;; ---------------------------------------------------------------------------
;; The bodies
;; ---------------------------------------------------------------------------

(defn- child-derefs-body [{:keys [d]}] [:li.deref (str @d)])
(def ^:private child-derefs (rt/mint-view! "deferred/child-derefs" child-derefs-body))

(defn- parent-delay-body
  "The crossing this file exists for: an explicit deferral written by one
  body and forced by another."
  [_]
  [child-derefs {:d (delay (:title (rt/sub [:dogfood/todo 1])))}])

(defn- child-calls-body [{:keys [f]}] [:li.call (str (f))])
(def ^:private child-calls (rt/mint-view! "deferred/child-calls" child-calls-body))

(defn- parent-fn-body
  "The shape that is NOT in the class: the child re-runs it every render."
  [_]
  [child-calls {:f (fn [] (:title (rt/sub [:dogfood/todo 1])))}])

;; ---------------------------------------------------------------------------
;; 1 — the refusal, at the crossing, in the writing body's render
;; ---------------------------------------------------------------------------

(deftest an-unforced-delay-crossing-a-boundary-is-refused
  (seeded!)
  (testing "the refusal fires while the PARENT is rendering — the codec
           runs inside `run-once`, so the throw lands on the call site
           that wrote the crossing rather than on the child that would
           otherwise have been blamed for the read"
    (let [e (try (render parent-delay-body {}) nil (catch :default e e))]
      (is (some? e) "the crossing refuses")
      (is (= :rf.error/hicasso-deferred-read-at-boundary (:rf.error/id (ex-data e))))
      (is (= 'front.codec/boundary-element (:where (ex-data e)))
          "the position that refused is the crossing, not the child")
      (is (= :hand-a-function-or-deref-it-in-this-body (:recovery (ex-data e))))
      (is (re-find #"unforced `delay` reached a boundary's props" (ex-message e))))))

(deftest the-refused-crossing-installs-nothing
  (seeded!)
  (testing "a render that threw is an abandoned render, and an abandoned
           render mutates neither the index nor a reference — the
           refusal is a diagnostic, not a half-built boundary"
    (let [before (rt/residue)]
      (try (render parent-delay-body {}) (catch :default _ nil))
      (is (= before (rt/residue))))))

(deftest the-refusal-reaches-wherever-the-walk-reaches
  (testing "the delay is refused at every position the crossing walk
           descends into, which is the same reach the seq realisation
           already had — a bare prop, inside a vector, inside a map,
           inside a seq, and inside a realised lazy seq"
    (doseq [[label v] [["a bare prop"            {:d (delay 1)}]
                       ["inside a vector"        {:d [(delay 1)]}]
                       ["inside a nested map"    {:d {:k (delay 1)}}]
                       ["inside a list"          {:d (list (delay 1))}]
                       ["inside a lazy seq"      {:d (map (fn [_] (delay 1)) (range 1))}]
                       ["inside a set"           {:d #{(delay 1)}}]]]
      (is (thrown-with-msg? js/Error #"unforced `delay` reached a boundary's props"
                            (codec/realize-deep v))
          label))))

;; ---------------------------------------------------------------------------
;; 1b — the same deferral, held as a map KEY (rf2-2rtt6.32)
;; ---------------------------------------------------------------------------

(defn- child-derefs-key-body
  "Forces a `delay` it finds at a KEY position. Nothing about the child is
  unusual — a map arrives as a prop and the child reads it — which is the
  point: the carrier is the same, only the half of the entry differs."
  [{:keys [m]}]
  [:li.deref-key (str @(first (keys m)))])

(def ^:private child-derefs-key
  (rt/mint-view! "deferred/child-derefs-key" child-derefs-key-body))

(defn- parent-key-delay-body [_]
  [child-derefs-key {:m {(delay (:title (rt/sub [:dogfood/todo 1]))) :marked}}])

(deftest a-delay-held-as-a-map-key-is-refused-exactly-as-a-value-is
  (testing "the invariant is about **reach** — every unforced `delay`
           reachable from the props — and a map entry is two reachable
           positions rather than one. The walk descended into values only
           until `rf2-2rtt6.32`, on an argument that does not survive
           contact with either half of the substrate: a `delay` hashes by
           object identity, so hashing never forces one; and a small map
           literal is a `PersistentArrayMap`, which compares keys with `=`
           and hashes nothing at all, so a one-entry map never so much as
           looks at its key. Both halves now go through the same refusal."
    (doseq [[label v] [["a key of the props map"          {(delay 1) :marked}]
                       ["a key of a nested map"           {:m {(delay 1) :marked}}]
                       ["inside a COLLECTION key"         {:m {[(delay 1)] :marked}}]
                       ["a key of a map inside a vector"  {:v [{(delay 1) :marked}]}]
                       ["a key of a map inside a seq"     {:v (list {(delay 1) :marked})}]
                       ["a key of a map inside a set"     {:v #{{(delay 1) :marked}}}]]]
      (is (thrown-with-msg? js/Error #"unforced `delay` reached a boundary's props"
                            (codec/realize-deep v))
          label))))

(deftest a-key-held-delay-is-refused-before-the-child-can-cache-it
  (seeded!)
  (testing "**Deliberately two renders deep.** A witness that stopped at
           the first paint would pass on a runtime that never looked at a
           key: the child's FIRST render forces the delay and files the
           read under itself, so the screen is right. The SECOND render is
           the wrong one — a `Delay` caches, so that walk calls `sub` zero
           times, the read set collapses to empty, React re-subscribes and
           the edge is dropped. The parent holds no edge either, so nothing
           ever rebuilds the delay.

           On this runtime the crossing refuses and the branch below is
           never entered. On one whose walk skips keys the crossing
           succeeds, and the assertion inside prints the two read sets that
           should have been equal and are not."
    (let [built (try (render parent-key-delay-body {}) (catch :default _ ::refused))]
      (is (= ::refused built)
          "an unforced `delay` at a key position refuses at the crossing")
      (when (not= ::refused built)
        (let [props  (crossing-props (:element built))
              first' (:reads (render child-derefs-key-body props))
              again  (:reads (render child-derefs-key-body props))]
          (is (= first' again)
              "UNREFUSED: the child's first render holds the edge and its
               cached second render drops it"))))))

(deftest the-fault-a-key-held-delay-would-restore
  (seeded!)
  (testing "driven around the codec, because the codec now refuses to build
           it. Identical to `the-fault-the-refusal-replaces` in every
           respect but where the `delay` sits, and that is the finding: the
           position it occupies changes nothing about what it does to the
           edge, so the walk's **reach** was the whole of the defect."
    (let [d      (delay (:title (rt/sub [:dogfood/todo 1])))
          props  {:m {d :marked}}
          first' (render child-derefs-key-body props)
          again  (render child-derefs-key-body props)]
      (is (= #{(key-of [:dogfood/todo 1])} (:reads first'))
          "the first walk files the read under whoever forced it")
      (is (= #{} (:reads again))
          "and the second calls `sub` zero times, so the edge is dropped"))))

(deftest a-delay-in-the-children-position-is-refused-too
  (seeded!)
  (testing "`:children` is a key in the same props map, so the one walk
           covers the children position without a second pass — the
           property `realize-children`'s one-level flatten made necessary
           for seqs holds identically for a deferral"
    (is (thrown-with-msg? js/Error #"unforced `delay` reached a boundary's props"
                          (render (fn [_] [child-derefs (delay (rt/sub [:dogfood/remaining]))]) {})))))

(deftest a-delay-the-author-already-forced-crosses-untouched
  (seeded!)
  (testing "only an UNFORCED delay is refused. One the author deref'd in
           their own body carries a computed value, derefs to it without
           calling anything, and is harmless wherever it goes — so the
           refusal costs the legitimate spelling nothing"
    (let [parent (render (fn [_]
                           (let [d (delay (:title (rt/sub [:dogfood/todo 1])))]
                             @d
                             [child-derefs {:d d}]))
                         {})
          props  (crossing-props (:element parent))]
      (is (= #{(key-of [:dogfood/todo 1])} (:reads parent))
          "the read is the parent's, because the parent forced it")
      (is (realized? (:d props)))
      (is (= #{} (:reads (render child-derefs-body props)))
          "and the child reads nothing, which is correct: it is not the reader"))))

;; ---------------------------------------------------------------------------
;; 2 — the fault the refusal replaces, kept as a witness
;; ---------------------------------------------------------------------------

(deftest the-fault-the-refusal-replaces
  (seeded!)
  (testing "driven around the codec, because the codec now refuses to
           build it: a delay forced inside the child's render is the
           child's edge on the first render and NOBODY's edge on the
           second, because a `Delay` caches. The parent holds no edge at
           any point, so nothing ever rebuilds the delay. This is the
           mechanism the refusal exists to prevent, and it is asserted
           rather than described so that removing the refusal cannot
           quietly restore it."
    (let [d      (delay (:title (rt/sub [:dogfood/todo 1])))
          first' (render child-derefs-body {:d d})
          again  (render child-derefs-body {:d d})]
      (is (= #{(key-of [:dogfood/todo 1])} (:reads first'))
          "the first walk files the read under whoever forced it")
      (is (= #{} (:reads again))
          "and the second walk calls `sub` zero times, so the read set
           collapses, React re-subscribes, and the edge is dropped"))))

;; ---------------------------------------------------------------------------
;; 3 — the shapes that are NOT in the class, verified rather than argued
;; ---------------------------------------------------------------------------

(deftest a-function-prop-keeps-its-edge-because-the-child-re-runs-it
  (seeded!)
  (testing "the render prop is the sanctioned deferral across a crossing.
           Its read repeats on every render, so the edge is kept; and the
           holder is the CHILD, which is correct — the child is the
           boundary whose output depends on it"
    (let [parent (render parent-fn-body {})
          props  (crossing-props (:element parent))
          first' (render child-calls-body props)
          again  (render child-calls-body props)]
      (is (= #{} (:reads parent))
          "the parent read nothing: it wrote a function, it did not read")
      (is (= #{(key-of [:dogfood/todo 1])} (:reads first')))
      (is (= #{(key-of [:dogfood/todo 1])} (:reads again))
          "and again on the next render, which is the whole difference
           from a delay"))))

(deftest a-function-prop-not-called-in-the-render-is-already-loud
  (seeded!)
  (testing "the render-prop-that-is-not-re-run has only two ends, and
           both are already settled: called in the render it keeps its
           edge (above); called anywhere else it finds no frame and is
           the error `read-key!` has raised since the beginning"
    (let [parent (render parent-fn-body {})
          props  (crossing-props (:element parent))
          stashed (volatile! nil)]
      (render (fn [{:keys [f]}] (vreset! stashed f) [:li]) props)
      (is (thrown-with-msg? js/Error #"outside a boundary render" (@stashed))))))

(deftest a-body-that-stops-calling-a-render-prop-simply-holds-no-edge
  (seeded!)
  (testing "not a defect, and worth the row so it is not filed as one:
           the collector's read set is a function of control flow (law
           4), so a body that stops calling its render prop correctly
           stops depending on it. A framework cannot tell that from a
           branch not taken, and must not try."
    (let [parent (render parent-fn-body {})
          props  (crossing-props (:element parent))
          calls  (render (fn [{:keys [f]}] [:li (str (f))]) props)
          skips  (render (fn [_] [:li "static"]) props)]
      (is (= #{(key-of [:dogfood/todo 1])} (:reads calls)))
      (is (= #{} (:reads skips))))))

;; ---------------------------------------------------------------------------
;; 4 — the declared limit
;; ---------------------------------------------------------------------------

(def ^:private !parked (atom nil))

(deftest a-deferral-parked-in-a-mutable-reference-is-outside-the-walk
  (seeded!)
  (testing "**A real limit of the Surface B ruling, written down.** The
           crossing walk descends into data structures; an atom is not
           one, and descending into a mutable reference is neither a
           structural pass nor safe (a reactive reference deref'd by a
           walk would mint a dependency the walk has no business
           holding). So a deferral parked in one — at a prop position or
           in a module-level var the codec never sees at all — reaches
           the child unrepaired and unrefused, and behaves exactly like
           the fault above.

           This is the boundary of the mechanism, not a gap in it: the
           author has routed state around the ruled surface, and no view
           framework detects that. React with hooks has the identical
           hole. It is recorded here so it is a stated property rather
           than a discovery."
    (let [parent  (render (fn [_]
                            (reset! !parked
                                    (map (fn [id] (:title (rt/sub [:dogfood/todo id]))) (range 3)))
                            [:li])
                          {})
          reader  (render (fn [_] [:li (str (doall @!parked))]) {})]
      (is (= #{} (:reads parent))
          "the writing body reads nothing — the seq is unrealised when it returns")
      (is (= #{(key-of [:dogfood/todo 0])
               (key-of [:dogfood/todo 1])
               (key-of [:dogfood/todo 2])}
             (:reads reader))
          "and every row lands on whichever body happened to force it")))
  (testing "the same, one step nearer: the reference is at a boundary
           prop, so the codec SEES it and still may not open it"
    (let [box    (atom (map (fn [id] (:title (rt/sub [:dogfood/todo id]))) (range 3)))
          parent (render (fn [_] [child-derefs {:d box}]) {})
          props  (crossing-props (:element parent))
          child  (render (fn [{:keys [d]}] [:li (str (doall @d))]) props)]
      (is (= #{} (:reads parent)))
      (is (= 3 (count (:reads child)))))))
