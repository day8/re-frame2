(ns re-frame.fresco.fragment-ref-dom-cljs-test
  "A REF ON A FRAGMENT — `[:<> {:ref f} …]`, mounted.

  [[re-frame.fresco.codec-cljs-test]] pins what the codec BUILDS: the
  handle reaches `element.props.ref` by identity, and the fragment emits
  no other slot. This file pins what React then DOES with it, which is
  the half a codec test cannot see, and it is the half the feature is
  for.

  Five claims, each one a caller can get wrong:

  1. **The handle is a `FragmentInstance`, not a DOM node.** A fragment
     has no element of its own, so there is nothing for React to hand
     back but an object addressing the run of children — and the whole
     reason to want one is that the alternative is a wrapper `<div>`
     that changes the layout. Both ref shapes are measured, because
     React reaches them by different arms: a callback ref is CALLED with
     the instance, an object ref has its `.current` assigned.

  2. **The fragment still contributes no element.** Asserted as the
     parent's child list, before and after, because that is the claim in
     the form a caller cares about: the flex row does not gain a box.
     A ref that silently made the codec wrap its children would satisfy
     every other row here.

  3. **Identity is the contract, not an optimisation** — and this is the
     row the codec's `:ref`-crosses-untouched rule exists for. React
     detaches and reattaches whenever a ref's identity changes, so the
     two directions are measured together: the SAME function across a
     re-render attaches once and its cleanup does not run, and a
     DIFFERENT function runs the old cleanup and attaches the new
     handle. A codec that re-wrapped the author's ref per render would
     pass row 1 and fail this one — the second half is what would go
     red, on every commit, in a shape that reads as a React bug.

  4. **The handle addresses whatever the fragment currently holds.**
     Children are conditional in real markup, so the instance is
     measured across a change to the run: `focusLast` follows the new
     last child rather than the one that was there at attach.

  5. **Neither arm of the SSR seam is disturbed.** The server bytes
     carry no artefact of the ref — no attribute, no wrapper — because
     refs are a client-commit concern and `renderToString` has no commit;
     and hydrating those same bytes attaches the handle without React
     recovering from anything.

  6. **An Activity hide DETACHES the handle and a reveal reattaches it.**
     Measured rather than assumed, and it comes out the opposite way to
     the intuition that a hidden Activity merely stops painting: React
     treats a fragment ref as a layout-tier attachment like a host ref,
     so the author's CLEANUP RUNS on a hide. Anything registered through
     the handle is torn down there and set up again on reveal, which is
     the fact a caller has to build against.

  ## What is deliberately NOT claimed here

  The Activity row says nothing about WHEN the reveal's work lands: the
  stale-window figure this package carries is preserved at one animation
  frame and was not re-measured under the current React (rf2-4ale), and
  a row that timed a reveal would be re-deriving it by accident.

  ## The mutation witnesses

  Drop the `:ref` copy from `codec/fragment-element` and rows 1, 3, 4 and
  the hydration half of 5 go red on a handle that never arrives. Copy it
  under the literal key instead of the canonical slot and the
  spelling row in [[re-frame.fresco.codec-cljs-test]] goes red while
  every row here stays green — which is why that claim is pinned there
  and not restated here. Re-wrap the author's function per render and
  [[a-stable-ref-attaches-once-and-a-changed-one-runs-the-old-cleanup]]
  goes red on the attach count.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The server-bytes row needs no DOM and runs under
  `:node-test` too."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.checkpoint-support :as rf.fresco.checkpoint-support]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::fragment-ref)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "a fragment-ref claim needs a real React DOM — " why)))

(defn- fresh! []
  (rf.fresco.checkpoint-support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  frame-id)

(defn- tags
  "The parent's child ELEMENTS, lowercased — the shape a fragment must not
  change. Read off `.-children` rather than `.-childNodes` so a text node
  cannot stand in for the wrapper this row is looking for."
  [node]
  (mapv #(.toLowerCase (.-tagName %)) (array-seq (.-children node))))

(defn- owner [container] (.querySelector container ".owner"))

;; ---------------------------------------------------------------------------
;; The page
;; ---------------------------------------------------------------------------

(rf.fresco/defview ref-page
  "Two siblings the fragment must not come between, and a run of children
  it holds. `:handle-ref` and `:last?` arrive as props so a row can
  re-render with a different ref, or a different run, through the same
  handle — which is what rows 3 and 4 are."
  [{:keys [handle-ref last?]}]
  [:div.owner
   [:span.before "before"]
   [:<> {:ref handle-ref}
    [:button.first {:type "button"} "first"]
    (when last? [:button.last {:type "button"} "last"])]
   [:span.after "after"]])

;; ---------------------------------------------------------------------------
;; 1 + 2 — what the handle IS, and what the DOM is not
;; ---------------------------------------------------------------------------

(deftest a-fragment-ref-answers-with-a-fragment-instance-and-adds-no-element
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [!seen (atom [])
            f     (fn [instance] (swap! !seen conj instance) js/undefined)
            h     (rf.fresco.impl.mount/root!
                    (rf.fresco.impl.mount/fresh-container!) frame-id
                    [ref-page {:handle-ref f :last? true}])]
        (try
          (testing "the callback ran once, during the commit `root!` flushed,
                    and what it was handed is React's FragmentInstance — an
                    object carrying the fragment's own API, and specifically
                    NOT a DOM node, because the fragment has no element"
            (is (= 1 (count @!seen)))
            (let [instance (first @!seen)]
              (is (some? instance))
              (is (not (instance? js/Element instance))
                  "a DOM node here would mean the codec had wrapped the children")
              (is (fn? (.-focus instance)) "focus, the FragmentInstance API")
              (is (fn? (.-focusLast instance)))
              (is (fn? (.-getClientRects instance)) "and its measurement half")))
          (testing "and the DOM is exactly the shape it would have been with no
                    ref at all: the fragment's children are the owner's own
                    children, in order, between the two siblings"
            (is (= ["span" "button" "button" "span"] (tags (owner (:container h))))))
          (finally (rf.fresco.impl.mount/release! h)))))))

(deftest an-object-ref-is-assigned-the-same-instance
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [r (react/createRef)
            h (rf.fresco.impl.mount/root!
                (rf.fresco.impl.mount/fresh-container!) frame-id
                [ref-page {:handle-ref r :last? true}])]
        (try
          (testing "React reaches an object ref by a different arm than a
                    callback — it assigns `.current` rather than calling —
                    so the shape is measured rather than assumed to follow"
            (is (some? (.-current r)))
            (is (fn? (.-focus (.-current r)))))
          (testing "and it is released at unmount, so the handle cannot outlive
                    the tree it addresses"
            (rf.fresco.impl.mount/unmount! h)
            (rf.fresco.impl.mount/settle!)
            (is (nil? (.-current r))))
          (finally (rf.fresco.impl.mount/release! h)))))))

;; ---------------------------------------------------------------------------
;; 3 — identity, in both directions
;; ---------------------------------------------------------------------------

(deftest a-stable-ref-attaches-once-and-a-changed-one-runs-the-old-cleanup
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [!a       (atom 0)
            !cleanup (atom 0)
            !b       (atom 0)
            f        (fn [_instance] (swap! !a inc) (fn [] (swap! !cleanup inc)))
            g        (fn [_instance] (swap! !b inc) js/undefined)
            h        (rf.fresco.impl.mount/root!
                       (rf.fresco.impl.mount/fresh-container!) frame-id
                       [ref-page {:handle-ref f :last? true}])]
        (try
          (is (= 1 @!a) "attached once at mount")
          (is (= 0 @!cleanup))
          (testing "A RE-RENDER WITH THE SAME FUNCTION IS NOT A REATTACH. This
                    is the claim `:ref` crosses the codec untouched FOR: React
                    compares the ref by identity, so a handle this codec
                    rewrapped per render would detach and reattach here — and
                    would run the author's cleanup — on every single commit"
            (rf.fresco.impl.mount/render! h [ref-page {:handle-ref f :last? false}])
            (rf.fresco.impl.mount/settle!)
            (is (= 1 @!a) "still one attach")
            (is (= 0 @!cleanup) "and the cleanup has not run"))
          (testing "and the same mechanism the other way round, so the row above
                    is a measurement rather than a tautology: a DIFFERENT
                    function does run the old cleanup and does attach the new
                    handle"
            (rf.fresco.impl.mount/render! h [ref-page {:handle-ref g :last? false}])
            (rf.fresco.impl.mount/settle!)
            (is (= 1 @!cleanup) "the first ref's cleanup ran")
            (is (= 1 @!b) "and the second attached"))
          (finally (rf.fresco.impl.mount/release! h)))))))

(deftest the-cleanup-a-callback-ref-returns-runs-at-unmount
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [!cleanup (atom 0)
            f        (fn [_instance] (fn [] (swap! !cleanup inc)))
            h        (rf.fresco.impl.mount/root!
                       (rf.fresco.impl.mount/fresh-container!) frame-id
                       [ref-page {:handle-ref f :last? true}])]
        (try
          (is (= 0 @!cleanup))
          (rf.fresco.impl.mount/unmount! h)
          (rf.fresco.impl.mount/settle!)
          (is (= 1 @!cleanup)
              "a fragment ref's cleanup is the ordinary React 19 one — the
               function the callback returned, run once when the fragment goes")
          (finally (rf.fresco.impl.mount/release! h)))))))

;; ---------------------------------------------------------------------------
;; 4 — the run of children is not frozen at attach
;; ---------------------------------------------------------------------------

(deftest the-handle-follows-the-children-the-fragment-currently-holds
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [!seen (atom nil)
            f     (fn [instance] (reset! !seen instance) js/undefined)
            h     (rf.fresco.impl.mount/root!
                    (rf.fresco.impl.mount/fresh-container!) frame-id
                    [ref-page {:handle-ref f :last? true}])]
        (try
          (let [instance @!seen
                c        (:container h)]
            (testing "focus and measurement reach the children through the
                      handle — the two the FragmentInstance API exists for"
              (.focus instance)
              (is (= "first" (.-textContent js/document.activeElement))
                  "focus lands on the first focusable child")
              (.focusLast instance)
              (is (= "last" (.-textContent js/document.activeElement)))
              (is (pos? (.-length (.getClientRects instance)))
                  "and the fragment measures as the rects of what it holds"))
            (testing "then the last child goes away. The ref did not change, so
                      React did not reattach — and the SAME instance now
                      addresses the shorter run, which is the property that
                      makes a fragment ref usable on conditional markup at all"
              (rf.fresco.impl.mount/render! h [ref-page {:handle-ref f :last? false}])
              (rf.fresco.impl.mount/settle!)
              (is (= ["span" "button" "span"] (tags (owner c)))
                  "the conditional child is out of the DOM")
              (.focusLast instance)
              (is (= "first" (.-textContent js/document.activeElement))
                  "and the handle's last child is the one that is left")))
          (finally (rf.fresco.impl.mount/release! h)))))))

;; ---------------------------------------------------------------------------
;; Activity — a hide is not a detach
;; ---------------------------------------------------------------------------

(def ^:private !set-mode (atom nil))

(defn- activity-host
  "Owns the Activity mode, published from a passive effect so a row cannot
  drive the tree before React has mounted it. The page underneath is the
  ordinary Fresco one, reached through `root-element` — the fragment ref
  under test is the page's own."
  [^js props]
  (let [[mode set-mode] (react/useState "visible")]
    (react/useEffect (fn [] (reset! !set-mode set-mode) js/undefined)
                     #js [set-mode])
    (react/createElement
      (.-Activity react) #js {:mode mode}
      (rf.fresco.impl.codec/root-element
        frame-id [ref-page {:handle-ref (.-handleRef props) :last? true}]))))

(unchecked-set activity-host "displayName" "frd/activity-host")

(deftest an-activity-hide-detaches-a-fragment-ref-and-a-reveal-reattaches-it
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (reset! !set-mode nil)
      (let [!a        (atom 0)
            !cleanup  (atom 0)
            f         (fn [_instance] (swap! !a inc) (fn [] (swap! !cleanup inc)))
            container (rf.fresco.impl.mount/fresh-container!)
            root      (react-dom-client/createRoot container)]
        (try
          (react-dom/flushSync
            (fn []
              (.render root (rf.fresco.impl.mount/provider
                              frame-id
                              (react/createElement activity-host #js {:handleRef f})))))
          (rf.fresco.impl.mount/settle!)
          (is (= 1 @!a) "attached on the visible mount")
          (is (= 0 @!cleanup))
          (is (some? @!set-mode) "the host published its setter")
          (testing "A HIDE DETACHES IT, and this is the row most likely to be
                    assumed the other way. React treats a fragment ref as a
                    layout-tier attachment like a host ref — its
                    `disappearLayoutEffects` detaches the Fragment fiber's ref
                    alongside the host ones — so the author's CLEANUP RUNS on a
                    hide, not only on an unmount. Anything registered through
                    the handle (an observer, a listener) is torn down here and
                    has to survive being set up again"
            (react-dom/flushSync (fn [] (@!set-mode "hidden")))
            (rf.fresco.impl.mount/settle!)
            (is (= 1 @!cleanup) "the cleanup ran on the hide")
            (is (= 1 @!a) "and nothing reattached while hidden"))
          (testing "and a REVEAL reattaches, symmetrically — a fresh handle for
                    the run of children as it stands on reveal"
            (react-dom/flushSync (fn [] (@!set-mode "visible")))
            (rf.fresco.impl.mount/settle!)
            (is (= 2 @!a) "attached a second time")
            (is (= 1 @!cleanup) "and the reveal ran no further cleanup"))
          (testing "WHAT THIS ROW DOES NOT CLAIM: when the reveal's work lands.
                    The stale-window figure this package carries is preserved
                    at one animation frame and was not re-measured under the
                    current React (rf2-4ale), and a row that timed a reveal
                    would be re-deriving it by accident"
            (is true "stated, not measured"))
          (finally (.unmount root)))))))

;; ---------------------------------------------------------------------------
;; 5 — the SSR seam
;; ---------------------------------------------------------------------------

(deftest the-server-bytes-carry-no-artefact-of-a-fragment-ref
  (testing "a ref is a client-commit concern and `renderToString` has no
            commit, so the markup is byte-identical to the same page with no
            ref at all — no attribute, and above all no wrapper element. This
            row needs no DOM and runs on the node lane too"
    (let [f      (fn [_instance] nil)
          render (fn [hiccup]
                   (react-dom-server/renderToString
                     (rf.fresco.impl.mount/provider
                       frame-id (rf.fresco.impl.codec/root-element frame-id hiccup))))
          with   (render [ref-page {:handle-ref f :last? true}])
          without (render [ref-page {:handle-ref nil :last? true}])]
      (is (= without with)
          "the ref changed nothing in the response")
      (is (not (re-find #"(?i)\bref=" with))
          "and nothing named ref reached the bytes"))))

(deftest hydrating-those-bytes-attaches-the-handle-without-a-recovery
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [!seen (atom [])
              f     (fn [instance] (swap! !seen conj instance) js/undefined)
              page  [ref-page {:handle-ref f :last? true}]
              html  (react-dom-server/renderToString
                      (rf.fresco.impl.mount/provider
                        frame-id (rf.fresco.impl.codec/root-element frame-id page)))
              container (rf.fresco.roots-frames-support/stamp-server-nodes!
                          (rf.fresco.roots-frames-support/server-dom! html))
              {:keys [seen stop!]} (rf.fresco.roots-frames-support/watch-mismatches!)
              h     (rf.fresco.impl.mount/hydrate-root! container frame-id page)]
          (js/setTimeout
            (fn []
              (stop!)
              (try
                (is (= 1 (count @!seen))
                    "the handle arrived on the hydration commit, once")
                (is (some? (first @!seen)))
                (is (fn? (.-focus (first @!seen))))
                (is (= [] @seen)
                    "and React recovered from nothing — a fragment ref is
                     invisible to the markup, so it cannot be a mismatch")
                (is (rf.fresco.roots-frames-support/every-server-node?
                      container ".owner button")
                    "the server's own nodes were adopted rather than replaced")
                (finally
                  (rf.fresco.impl.mount/release! h)
                  (rf.fresco.impl.collector/reset-runtime!)
                  (done))))
            200))))))
