(ns re-frame.fresco.fragment-ref-dom-cljs-test
  "A REF ON A FRAGMENT — `[:<> {:ref f} …]`, mounted.

  [[re-frame.fresco.codec-cljs-test]] pins what the codec BUILDS: the
  handle reaches `element.props.ref` by identity, and the fragment emits
  no other slot. This file pins what React then DOES with it, which is
  the half a codec test cannot see, and it is the half the feature is
  for.

  Three claims, each one a caller can get wrong:

  1. **The handle is a `FragmentInstance`, not a DOM node.** A fragment
     has no element of its own, so there is nothing for React to hand
     back but an object addressing the run of children — and the whole
     reason to want one is that the alternative is a wrapper `<div>`
     that changes the layout.

  2. **The fragment still contributes no element.** Asserted as the
     parent's child list, before and after, because that is the claim in
     the form a caller cares about: the flex row does not gain a box.
     A ref that silently made the codec wrap its children would satisfy
     every other row here.

  3. **Hydration is not disturbed.** Refs are a client-commit concern,
     so the server bytes carry no artefact of one, and hydrating them
     attaches the handle without React recovering from anything.

  ## The mutation witnesses

  Drop the `:ref` copy from `codec/fragment-element` and both rows go
  red on a handle that never arrives. Copy it under the literal key
  instead of the canonical slot and the spelling row in
  [[re-frame.fresco.codec-cljs-test]] goes red while every row here
  stays green — which is why that claim is pinned there and not restated
  here. Re-wrap the author's function per render and
  [[re-frame.fresco.codec-cljs-test/a-fragment-forwards-its-ref-to-react-by-identity]]
  goes red on the identity.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every row states a skip."
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
  it holds."
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
            (is (fn? (.-focusLast (first @!seen)))
                "a FragmentInstance — an Element has no focusLast, so wrapped children fail here"))
          (testing "and the DOM is exactly the shape it would have been with no
                    ref at all: the fragment's children are the owner's own
                    children, in order, between the two siblings"
            (is (= ["span" "button" "button" "span"] (tags (owner (:container h))))))
          (finally (rf.fresco.impl.mount/release! h)))))))

;; ---------------------------------------------------------------------------
;; 3 — the SSR seam
;; ---------------------------------------------------------------------------

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
