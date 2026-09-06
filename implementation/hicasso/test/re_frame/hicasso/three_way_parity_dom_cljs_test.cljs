(ns re-frame.hicasso.three-way-parity-dom-cljs-test
  "TWO-ARM PARITY UNDER A REAL REACT.

  `three_way_parity_cljs_test` settles what a server render can settle:
  bytes, element shape, props, and the construction figures underneath
  the island band. Three of the parity axes cannot be settled there at
  all, because each is a property of a FIBER and a real document, and
  the node lane has neither:

  | axis | why it needs a fiber |
  |---|---|
  | painted DOM | markup is a string; the DOM is nodes, and a route that emitted correct bytes could still hand React a tree it reconciles differently |
  | refs | a ref is filled at COMMIT and nulled at unmount — there is no commit on the server |
  | cleanup | teardown is what a route releases, and nothing was acquired on the server |

  So they are here, and the browser lane decides them. Under
  `:node-test` every row below degrades to a **stated skip** rather than
  a false green — `impl.mount/browser?` is the guard, and the sentence it
  prints names what was not measured.

  The native authoring tier this file used to measure as a third arm was
  retired by the rf2-6c12m.3 ruling; the two arms that remain —
  handwritten React and UIx — are the floor that ruling kept. The file
  keeps its name for the node-lane sibling's reason.

  ## Hydration is deliberately NOT here

  It belongs to the *per-surface SSR/hydration witnesses*, which this
  file unblocks. Writing a two-route hydration row here would put a
  second authority on the same claim ahead of the one that owns it, and
  the two would drift on the first policy change.
  The server bytes each route produces ARE measured, in the node-lane
  sibling; what happens when React meets those bytes on a client is the
  next bead's subject.

  ## One door for both routes, and why that is the honest shape

  Every arm crosses into the tree through `h/defhost` under
  `{:server :render}`. It is uniform — the same door, the same policy,
  the same props conversion for both — so a difference the rows find
  is a difference between the ROUTES rather than between two ways of
  reaching them. It is also what an application does: a handwritten
  React component and a UIx subtree are both foreign React components
  to the interpreted tier, and the door never names the tier.

  ## One frame, and why a second would measure nothing HERE

  Row 4 of the conformance matrix asks for both embedding directions
  ACROSS TWO FRAMES, and every row below runs under the single
  `::parity-dom`. That is deliberate, and the reason is a property of
  this file's subject rather than a budget.

  A frame is a property of what a body READS. Neither route here reads
  anything: [[page]] calls `h/sub` once per arm and hands each route the
  answer as an ordinary `label` prop, because the axes this file exists
  to settle — painted DOM, refs, cleanup, element identity across a
  re-render — are all properties of a route's own rendering, and a
  subscription inside each route would be two more things that could
  differ for reasons that are not the ones under test. Mount that tree
  under two frames and what differs between the two pages is the number
  React carried down a props chain. That is prop plumbing, and the
  single-frame run above already settles it for both routes in one
  commit.

  The inward two-frame claim belongs where an inward route actually
  subscribes, and that is `hooks_island_dom_cljs_test`'s isolation row:
  one island source under two roots, reading through `n/use-sub` on the
  far side of the crossing, two keys, one reader each, and a write that
  moves one page.

  The raw-React route is why the claim cannot be made here at all. A
  handwritten React component subscribes through no re-frame2 hook by
  construction — that is what makes it the control it is here — so a
  reading tree would have to hand it one, and the row would then be
  measuring a component this file went to some trouble to keep foreign."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.checkpoint-support :as rf.hicasso.checkpoint-support]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.test-support :as rf.test-support]
            [uix.core :as uix :refer-macros [defui]]
            ["react" :as react]))

(def ^:private frame-id ::parity-dom)

(def ^:private routes [:react :uix])

(rf/reg-sub ::price (fn [db _] (:price db)))

(rf/reg-event ::seed (fn [_ [_ db]] {:db db}))

;; ---------------------------------------------------------------------------
;; The observables
;; ---------------------------------------------------------------------------

(def ^:private !refs
  "Every value each route's callback ref was handed, by route, in order.

  A ref claim read off the DOM would prove only that a node exists. What
  matters is that the route's own ref FUNCTION was called with it, and
  that unmount calls it again with nil — React's contract, and the thing
  a route that quietly swallowed the slot would fail."
  (atom {}))

(def ^:private !renders
  "How many times each route's body ran, by route. It separates *the tree
  updated* from *the tree did not re-render at all*, which the node
  identity below cannot: an unchanged node is what BOTH look like."
  (atom {}))

(defn- record-ref!
  [route node]
  (swap! !refs update route (fnil conj []) node))

(defn- ran! [route] (swap! !renders update route (fnil inc 0)))

;; The ref callbacks are TOP-LEVEL and stable, which is load-bearing
;; rather than tidy. React detaches and re-attaches a ref whose identity
;; changed, so a fresh closure per render would call every ref three
;; times across one update — and the re-render row below, whose whole
;; instrument is the call count, would read a remount that never
;; happened.
(def ^:private react-ref (fn [node] (record-ref! :react node)))
(def ^:private uix-ref   (fn [node] (record-ref! :uix node)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.hicasso.checkpoint-support/leave-act-environment!)
                      (reset! !refs {})
                      (reset! !renders {})
                      (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The two routes, as components
;; ---------------------------------------------------------------------------
;;
;; One subject: a cell painting a label, holding a ref on its own span.
;; The ref arrives as an ORDINARY prop named `innerRef` rather than at
;; React's `ref` slot, so both routes are handed it the same way and the
;; row measures the route rather than React's own ref forwarding.

(defn react-cell
  [^js props]
  (ran! :react)
  (react/createElement "span"
    #js {:className "cell react" :ref (.-innerRef props)}
    (.-label props)))

(defui uix-cell
  [{:keys [label inner-ref]}]
  (ran! :uix)
  (uix/$ :span {:class "cell uix" :ref inner-ref} label))

(defn- uix-arm
  "The plain React shim every crossing into UIx needs: UIx's ABI is a
  carrier object its own `uix/$` builds, so a `defui` reached through any
  other door receives props it cannot read."
  [^js props]
  (uix/$ uix-cell {:label (.-label props) :inner-ref (.-innerRef props)}))

(rf.hicasso/defhost react-host react-cell {:server :render})
(rf.hicasso/defhost uix-host   uix-arm    {:server :render})

(rf.hicasso/defview page
  "Both arms in one tree, under one frame, in one commit — so a row
  comparing them is comparing one render and not two that happened to
  agree."
  [_]
  [:div.page
   [react-host {:label (str (rf.hicasso/sub [::price])) :inner-ref react-ref}]
   [uix-host   {:label (str (rf.hicasso/sub [::price])) :inner-ref uix-ref}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "a two-route DOM claim needs a real React DOM — " why)))

(defn- seeded!
  []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed {:price 191}]))
  frame-id)

(defn- mounted!
  []
  (let [handle (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-id [page {}])]
    (rf.hicasso.impl.mount/settle!)
    handle))

(defn- cells
  "The two painted cells, by route, read out of `handle`'s container."
  [handle]
  (into {}
        (map (fn [route]
               [route (.querySelector ^js (:container handle) (str "span.cell." (name route)))]))
        routes))

(defn- normalised
  "`el`'s outer HTML with the route's own class removed.

  The class is how the row FINDS each cell and is therefore the one
  attribute that must differ between the two. Comparing the raw markup
  would compare that difference and nothing else; removing it leaves the
  part the routes are supposed to agree about, and the row below asserts
  the removal still left something."
  [^js el]
  (when el
    (str/replace (.-outerHTML el) #"\s*(react|uix)\b" "")))

;; ---------------------------------------------------------------------------
;; 1. The painted DOM
;; ---------------------------------------------------------------------------

(deftest the-two-routes-paint-the-same-dom
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no React DOM")
    (do
      (seeded!)
      (let [handle (mounted!)
            found  (cells handle)]
        (try
          (testing "the premise: both arms mounted. One arm agreeing with
                    itself while the other rendered nothing is the failure
                    this row is most exposed to, and it is the one the
                    node-lane sibling was actually bitten by"
            (is (every? some? (vals found)) (pr-str (keys found))))

          (testing "and each painted the value it read — one frame, two
                    routes, one commit"
            (is (= ["191" "191"]
                   (mapv #(.-textContent ^js (get found %)) routes))))

          (testing "the painted markup is the same on both routes once the
                    class that distinguishes them is removed — which is the
                    DOM claim the server-bytes row cannot make, because a
                    route could emit correct bytes and still hand React a
                    tree it reconciles differently"
            (let [handwritten (normalised (:react found))]
              (is (seq handwritten) "the premise: normalising left markup")
              (is (some? (re-find #"<span" handwritten)))
              (is (= handwritten (normalised (:uix found))))))
          (finally (rf.hicasso.impl.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2. Refs
;; ---------------------------------------------------------------------------

(deftest a-ref-reaches-a-real-node-on-every-route-and-is-released-on-unmount
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no commit, so no ref is ever filled")
    (do
      (seeded!)
      (let [handle (mounted!)
            found  (cells handle)]

        (testing "each route's own ref function was called, and with the very
                  node that route painted — not merely with A node"
          (doseq [route routes]
            (let [calls (get @!refs route)]
              (is (= 1 (count calls)) (str route " ref called exactly once at mount"))
              (is (identical? (get found route) (first calls))
                  (str route " ref holds the node it painted")))))

        (testing "and every one of them is a real element with a real tag —
                  the two routes reach React's ref slot by two different
                  spellings and land in the same place"
          (doseq [route routes]
            (is (= "SPAN" (.-tagName ^js (first (get @!refs route)))))))

        (rf.hicasso.roots-frames-support/teardown-census! handle)

        (testing "unmount calls each route's ref again with nil, which is
                  React's own contract and the half a mounted-only row cannot
                  see: a route that held the node past unmount would keep a
                  detached tree alive"
          (doseq [route routes]
            (let [calls (get @!refs route)]
              (is (= 2 (count calls)) (str route " ref called at mount and at unmount"))
              (is (nil? (second calls)) (str route " ref released")))))))))

;; ---------------------------------------------------------------------------
;; 3. Cleanup
;; ---------------------------------------------------------------------------

(deftest teardown-releases-everything-the-two-route-tree-acquired
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test acquires nothing, so it can release nothing")
    (do
      (seeded!)
      (let [handle    (mounted!)
            container (:container handle)]

        (testing "the premise: the tree was really mounted, so the zeros
                  below are a release and not the reading of a page that
                  never held anything"
          ;; `.-length`, not `count`: a `NodeList` is array-LIKE and
          ;; ES6-iterable but implements no ClojureScript protocol, so
          ;; `count` throws `ICounted` rather than answering two.
          (is (= 2 (.-length (.querySelectorAll ^js container "span.cell")))))

        (let [census (rf.hicasso.roots-frames-support/teardown-census! handle)]

          (testing "the container is empty — React unmounted the whole tree,
                    both foreign subtrees included"
            (is (= "" (.-innerHTML ^js container))))

          (testing "and the runtime's own census is exact at the instant
                    unmount returned: no cell reference, no boundary, no
                    edge survived a tree holding a handwritten React
                    component and a foreign UIx subtree. Read BEFORE the
                    release finishes, because `mount/release!` empties every
                    table by fiat and a census taken after it reads zeros
                    either way"
            (is (= {:cell-refs 0 :boundaries 0 :edges 0} census))))))))

;; ---------------------------------------------------------------------------
;; 4. Component identity across a re-render
;; ---------------------------------------------------------------------------
;;
;; The parity axis the DOM cannot show. A route whose element type is a
;; fresh object per render renders perfectly and remounts every time:
;; same pixels, new nodes, lost state, dropped refs. A `{:server :render}`
;; crossing's contract is that the element type is the author's own
;; function, so a re-render UPDATES — and the observable is the ref, which
;; React fills once per mount and would fill again on a remount.

(deftest a-re-render-updates-every-route-rather-than-remounting-it
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no second render")
    (do
      (seeded!)
      (let [handle (mounted!)
            before (cells handle)]
        (try
          (testing "the premise: the first commit filled every ref exactly
                    once, which is what a second fill would be measured
                    against"
            (is (= [1 1] (mapv #(count (get @!refs %)) routes))))

          (rf/with-frame frame-id (rf/dispatch-sync [::seed {:price 192}]))
          (rf.hicasso.impl.mount/settle!)

          (testing "the write really re-rendered the page — every route's
                    body ran a second time and repainted the new value.
                    The body count is the half the node identity below
                    cannot supply, since a tree that never re-rendered at
                    all also keeps its nodes"
            (is (= {:react 2 :uix 2} @!renders))
            (is (= ["192" "192"]
                   (mapv #(.-textContent ^js (get (cells handle) %)) routes))))

          (testing "and every route kept its NODE across the update. React
                    reconciled rather than replaced, on both, which is what
                    an unchanged element type buys and what a per-render
                    mint would have cost"
            (doseq [route routes]
              (is (identical? (get before route) (get (cells handle) route))
                  (str route " kept its node"))))

          (testing "so no ref was refilled: still one call each, where a
                    remount would read two — the observable the DOM cannot
                    provide, since the pixels are identical either way"
            (is (= [1 1] (mapv #(count (get @!refs %)) routes))))
          (finally (rf.hicasso.impl.mount/release! handle)))))))
