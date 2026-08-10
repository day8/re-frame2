(ns re-frame.hicasso.public-root-lifecycle-dom-cljs-test
  "THE PUBLIC DOOR'S ROOT LIFECYCLE — what a CONSUMER can do to one root
  without touching another (rf2-31xm, rf2-e2al).

  Every other suite in this package mounts through `impl.mount`, because
  every other suite is measuring the runtime and the impl door is the
  shortest way to it. This one is the opposite: the mounting, the
  re-rendering and the teardown are all written through
  `re-frame.hicasso` and nothing else, because what is under test IS the
  public surface. `impl.collector`, `impl.inventory` and `impl.mount` are
  required for INSTRUMENTS only — the cell table, the reader lists, the
  body counter, `browser?` and `fresh-container!` — never to perform an
  act the door is supposed to be able to perform.

  ## Why the readings are not the DOM

  A root whose runtime has been emptied under it still LOOKS right: the
  markup React last committed is still on the page, and nothing repaints
  it until something changes. So the surviving root is proved live by
  DISPATCHING into it and watching the paint follow, and by reading the
  cell table's keys and reader lists — the observables
  `re-frame.hicasso.roots-frames-support` chooses, for the reasons it
  states there.

  ## Two frames, and no frame passed as an argument

  Frames are isolated contexts. The two roots below sit under two frames
  and render ONE view, mounted twice; nothing takes a frame-id as a view
  argument. That is what makes \"root A's teardown must not reach root
  B\" a claim about isolation rather than about bookkeeping."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]))

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)

(def ^:private label-q [::label])

;; Registered ABOVE `use-fixtures` for the reason the isolation suite gives:
;; the reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a `reg-sub` below it is erased before
;; the first row runs and every screen renders nothing.

(rf/reg-sub ::label (fn [db _] (:label db)))

(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-event ::relabel (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; nil, not the default: a dynamic-var frame stamp left in ambient
     ;; scope would let a boundary that failed to resolve its own frame
     ;; answer that one instead, and an isolation miss would read as a
     ;; rendering difference rather than as the failure it is.
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app — ONE view, mounted twice
;; ---------------------------------------------------------------------------

(h/defview panel
  "The whole app: one read, and a tag the caller supplies so a re-render
  with different props is legible in the markup."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag}
   [:span.label (h/sub label-q)]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "a public-door lifecycle claim needs a real React DOM — " why)))

(defn- fresh!
  "Two frames, seeded differently, and an empty runtime. The labels differ
  so a cross-frame read is legible in the markup as well as in the
  tables."
  []
  ;; React's `act` queue is not the browser's scheduler, and every reading
  ;; here is taken outside it. Set outright rather than imported: the
  ;; helper that carries this line lives in the bench tree, which this
  ;; package may not require.
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/with-frame frame-a (rf/dispatch-sync [::seed "alpha"]))
  (rf/with-frame frame-b (rf/dispatch-sync [::seed "beta"]))
  (collector/reset-runtime!)
  (collector/reset-body-runs!)
  nil)

(defn- cell-keys [] (set (keys @collector/!cells)))

(defn- readers-of [sub-key] (count (inventory/cell-readers sub-key)))

(defn- node-at [handle sel] (.querySelector (:container handle) sel))

(defn- text-at [handle sel] (some-> (node-at handle sel) .-textContent))

(defn- attr-at [handle sel a] (some-> (node-at handle sel) (.getAttribute a)))

;; ---------------------------------------------------------------------------
;; W1 (rf2-31xm) — tearing one root down must not reach the other
;; ---------------------------------------------------------------------------

(deftest tearing-one-root-down-leaves-the-other-root-live
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (fresh!)
          a (h/root! (mount/fresh-container!) frame-a [panel {:tag "a"}])
          b (h/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])]
      (try
        (testing "premise: two roots, two frames, one cell each, both painted"
          (is (= #{[frame-a label-q] [frame-b label-q]} (cell-keys))
              (str "the cell table must be keyed by (frame, query); got "
                   (pr-str (cell-keys))))
          (is (= "alpha" (text-at a ".label")))
          (is (= "beta" (text-at b ".label"))))

        ;; The act under test, and the ONLY thing that happens between the
        ;; premise above and the readings below.
        (h/unmount! a)

        (testing "root B's runtime survives root A's teardown — its cell is
                  still in the table, and still read by its boundary"
          (is (contains? (cell-keys) [frame-b label-q])
              (str "tearing down root A emptied the runtime under root B; "
                   "the live cell keys are " (pr-str (cell-keys))))
          (is (= 1 (readers-of [frame-b label-q]))
              "root B's boundary must still be reading its own cell"))

        (testing "and it survives LIVE, not merely as a table entry: a
                  dispatch into B's frame still reaches B's paint. This is
                  the reading the DOM alone cannot give — the markup React
                  last committed stays on the page whether or not anything
                  is still wired to it"
          (mount/dispatch! b [::relabel "beta-again"])
          (is (= "beta-again" (text-at b ".label"))
              "root B stopped repainting when root A was torn down"))

        (testing "root B's mount point survives too"
          (is (true? (.-isConnected (:container b)))))

        (testing "and so does root A's — the container was the CALLER's
                  node, handed to `root!`, and a teardown door may not
                  delete a node it did not create"
          (is (true? (.-isConnected (:container a)))
              "tearing down root A removed the caller's own container from
               the document"))

        (testing "root A is nonetheless really down: React emptied its
                  container, and the runtime released the edge its boundary
                  held"
          (is (= "" (.-innerHTML (:container a))))
          (is (zero? (readers-of [frame-a label-q]))
              "root A's boundary is still reading a cell after its teardown"))

        (finally
          (h/unmount! b)
          (collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W2 (rf2-e2al) — the door can re-render a mounted root
;; ---------------------------------------------------------------------------

(deftest a-mounted-root-can-be-re-rendered-through-the-public-door
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (fresh!)
          a (h/root! (mount/fresh-container!) frame-a [panel {:tag "first"}])
          node (node-at a ".panel")]
      (try
        (testing "premise: the root is mounted and painted"
          (is (some? node))
          (is (= "first" (attr-at a ".panel" "data-tag")))
          (is (= "alpha" (text-at a ".label"))))

        (testing "the door re-renders the EXISTING root — the new tree is on
                  the page and the boundary body ran again"
          (collector/reset-body-runs!)
          (h/render! a [panel {:tag "second"}])
          (is (= "second" (attr-at a ".panel" "data-tag")))
          (is (pos? (collector/body-runs))
              "the re-render did not run the boundary body"))

        (testing "and it is a RE-RENDER, not a remount: the very DOM node
                  the first render produced is still the one on the page.
                  A second `root!` would have built a new React root and
                  replaced it, which is why `root!` is not the reload
                  affordance"
          (is (identical? node (node-at a ".panel"))
              "the re-render replaced the DOM node instead of updating it"))

        (testing "the root is still wired after the re-render — a dispatch
                  still reaches its paint"
          (mount/dispatch! a [::relabel "alpha-again"])
          (is (= "alpha-again" (text-at a ".label"))))

        (finally
          (h/unmount! a)
          (collector/reset-runtime!))))))
