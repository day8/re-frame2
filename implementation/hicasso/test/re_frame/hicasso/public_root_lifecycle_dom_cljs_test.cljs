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
  B\" a claim about isolation rather than about bookkeeping.

  ## This suite takes its own containers down

  Every other `fresh-container!` suite in the package hands its nodes to
  `impl.mount/release!`, the fixture door that detaches the container and
  empties the runtime. This one cannot: what it is measuring is the
  PUBLIC teardown, whose contract is that the caller's node survives
  (rf2-31xm), so the very act that would clean up is the act under test.
  The suite therefore removes its own nodes, in [[detach!]] — and only in
  `finally`, AFTER the readings that prove the door left them alone. The
  reset fixture is no help here: it restores registrars and frames and
  never touches the document, and the document is shared with every other
  browser suite."
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

(defn- detach!
  "Remove a container THIS suite minted, once every reading of it is
  taken. The mirror of `fresh-container!`, and the reason it is written
  out here rather than borrowed: `impl.mount/release!` is the door that
  would do it, and it also empties the runtime — total teardown, right
  for a fixture that owns the page and exactly the meaning rf2-31xm took
  off the public facade.

  **Placement is the whole of it.** Every call sits in `finally`, after
  the assertions that the container was still connected once its root
  came down. One line earlier and it would delete the proof of the
  behaviour this file exists to witness."
  [handle]
  (when-some [c (:container handle)]
    (when-some [p (.-parentNode c)] (.removeChild p c)))
  nil)

(defn- connected? [handle] (.-isConnected (:container handle)))

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
          (is (true? (connected? b))))

        (testing "and so does root A's — the container was the CALLER's
                  node, handed to `root!`, and a teardown door may not
                  delete a node it did not create"
          (is (true? (connected? a))
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
          (detach! a)
          (detach! b)
          (is (= [false false] [(connected? a) (connected? b)])
              "this witness left one of its own containers in the shared
               browser-test document")
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
          (detach! a)
          (is (false? (connected? a))
              "this witness left its own container in the shared
               browser-test document")
          (collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W3 (rf2-1mmn) — THE EXECUTING SABOTAGE CONTROL for kernel risk row 2
;; ---------------------------------------------------------------------------

(defn- pre-rf2-31xm-teardown!
  "The public teardown door AS IT WAS BEFORE rf2-31xm narrowed it: take
  this root down, drop its container, and empty the process-global
  runtime.

  **The sabotage needs no mock and no seam, because that door still
  exists under an honest name.** `impl.mount/release!` is `unmount!` plus
  the container removal plus `collector/reset-runtime!` — precisely the
  meaning rf2-31xm took off the public facade, retained as the fixture
  door for a suite that owns the whole page. So the mutation is not a
  hypothetical reconstruction: it is the shipped page-wide door, called
  where the root-scoped one belongs.

  This is the one place in this file where `impl.mount` performs an act
  rather than taking a reading, and the header's rule survives it whole.
  That rule forbids the impl door from doing what the PUBLIC door is
  supposed to be able to do; total teardown is the one thing the public
  door must NOT be able to do, which is the whole of rf2-31xm."
  [handle]
  (mount/release! handle))

;; Kernel risk row 2 of `docs/design/hicasso/product/lanes/adversarial-risks.md`
;; — *independent roots and SSR requests cannot reset, adopt, dirty, or release
;; one another's state* — is a correctness gate, and that register's gate
;; construction rule asks every one of them for "a sabotage mutation that makes
;; each correctness gate red". Until rf2-1mmn this family had none that ran:
;; this suite and `roots-frames-isolation-dom-cljs-test` carried no sabotage in
;; any form, and a reviewer cannot re-run what is not there.
;;
;; The mutation is the register's own first verb. `collector/reset-runtime!`
;; empties every table this arm holds, and every one of them is one-per-page
;; and keyed by frame — its docstring says so out loud — so a teardown door
;; that calls it releases every sibling root's state along with its own. W1
;; above asserts that root A's teardown does not. This row plants the door
;; that does, and watches W1's three readings invert.
;;
;; Both halves run the same construction, and they red in opposite directions:
;;
;;   - the ARMED half reds if the page-wide door stops being page-wide, which
;;     is what a control that has quietly become a no-op looks like;
;;   - the DISARMED half reds if root-scoped teardown regresses to page-wide,
;;     which is the defect W1 exists to catch, and which shipped once already.
;;
;; The stranding reading is the one worth having, and it is why the row does
;; not stop at the counts. A runtime emptied under a live root throws nothing
;; and clears nothing: the markup React last committed stays on the page, the
;; mount point stays connected, and the only symptom is a screen that has
;; stopped moving. The armed half asserts all three — dead tables, a frozen
;; label, and a page that still looks perfectly well.
(deftest a-page-wide-teardown-door-strands-the-sibling-root
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      ;; DISARMED — the shipped root-scoped door.
      (let [_ (fresh!)
            a (h/root! (mount/fresh-container!) frame-a [panel {:tag "a"}])
            b (h/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])]
        (try
          (is (= #{[frame-a label-q] [frame-b label-q]} (cell-keys))
              (str "premise: two roots, two frames, one cell each; got "
                   (pr-str (cell-keys))))
          (h/unmount! a)
          (testing "DISARMED — root B keeps its cell, keeps its reader, and stays
                    LIVE across its sibling's teardown"
            (is (contains? (cell-keys) [frame-b label-q])
                (str "got " (pr-str (cell-keys))))
            (is (= 1 (readers-of [frame-b label-q])))
            (mount/dispatch! b [::relabel "beta-again"])
            (is (= "beta-again" (text-at b ".label"))
                "root B stopped repainting when root A was torn down"))
          (finally
            (h/unmount! b)
            (detach! a)
            (detach! b)
            (is (= [false false] [(connected? a) (connected? b)])
                "this witness left one of its own containers in the shared
                 browser-test document")
            (collector/reset-runtime!))))

      ;; ARMED — the same construction, torn down through the page-wide door.
      (let [_ (fresh!)
            a (h/root! (mount/fresh-container!) frame-a [panel {:tag "a"}])
            b (h/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])]
        (try
          (is (= #{[frame-a label-q] [frame-b label-q]} (cell-keys))
              (str "premise: the same two roots as the disarmed half; got "
                   (pr-str (cell-keys))))
          (pre-rf2-31xm-teardown! a)
          (testing "ARMED — root A's teardown reached the process-global runtime
                    and took root B's state with it"
            (is (= #{} (cell-keys))
                (str "THE SABOTAGE DID NOT SABOTAGE — the page-wide door left the
                      cell table standing, so W1's key reading is not a
                      discrimination; got " (pr-str (cell-keys))))
            (is (zero? (readers-of [frame-b label-q]))
                "root B's boundary must have lost the cell it was reading"))

          (testing "and root B is STRANDED — the half a count cannot show.
                    Nothing threw, nothing complained, its markup is the markup
                    React last committed and its mount point is still connected.
                    The only symptom is a screen that has stopped moving, which
                    is exactly why W1 reads the tables and the dispatch rather
                    than the DOM"
            (mount/dispatch! b [::relabel "beta-again"])
            (is (= "beta" (text-at b ".label"))
                (str "the page-wide door left root B repainting, so the dispatch
                      reading in W1 is not a discrimination either; got "
                     (pr-str (text-at b ".label"))))
            (is (true? (connected? b))
                "and the page still looks perfectly well"))
          (finally
            (h/unmount! b)
            (detach! a)
            (detach! b)
            (is (= [false false] [(connected? a) (connected? b)])
                "this witness left one of its own containers in the shared
                 browser-test document")
            (collector/reset-runtime!)))))))
