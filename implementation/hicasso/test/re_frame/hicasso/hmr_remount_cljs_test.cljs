(ns re-frame.hicasso.hmr-remount-cljs-test
  "THE HMR CONTRACT, PART 1 — what a save does to the head, and what the
  stale generation leaves behind (rf2-hic-015).

  Two files carry this bead. This one takes the **structural** half: a
  reload re-mints the boundary head, so React's reconciler meets a new
  element *type* at that position and replaces the subtree outright, and
  the question that leaves is whether the retired generation lets go of
  everything it was holding. `hmr_registry_cljs_test` takes the
  **registry** half — what a save's re-registrations do to the number
  React re-reads, and when an edited subscription's value arrives.

  ## The contract already existed as folklore, in a docstring

  [[re-frame.hicasso.impl.codec/memoize-boundary!]]'s comparator declines
  Reagent's `*always-update*` escape on the grounds that *re-evaluating a
  `defview` here re-mints the head and its wrapper — a new React element
  type, which HMR replaces outright*. That sentence is the whole HMR
  contract, and until this file nothing measured it. The bead exists to
  turn it into a witness.

  ## What a reload IS, mechanically, and why the node lane can model it

  shadow-cljs hot reload re-evaluates a namespace's compiled module: its
  top-level forms run again. For a Hicasso source file that is exactly
  two things — the `reg-sub` / `reg-event` forms re-register, and the
  `def` that [[re-frame.hicasso/defview]] expands to re-runs. The macro
  expands to `(def sym (mint-view! \"<ns>/<sym>\" (fn <sym>-body …)))`, so
  re-evaluating the form is re-running that one call and rebinding the
  var. [[load-namespace!]] below is those two things and nothing else,
  and [[the-defview-macro-mints-what-a-reload-re-mints]] holds the model
  to the macro so the two cannot drift apart silently.

  ## Why the observables here are not DOM assertions

  Rendered markup cannot answer the question this file asks. A remount
  and an update paint the identical text, so a text assertion is green
  across a correct hand-over and green across one that retained the
  predecessor's subscription registration too — a leak costs no pixels.
  What distinguishes them is *which object* is holding the key's cell
  afterwards, and that is [[re-frame.hicasso.impl.inventory/cell-readers]]:
  the surviving reader must be the successor's registration, by identity,
  not merely one reader rather than two. A count of live readers is a
  weaker instrument than it looks — a runtime that leaked the predecessor
  AND failed to acquire for the successor also counts one — so every
  assertion below that could be satisfied by a count is paired with an
  identity.

  The harness is [[re-frame.hicasso.impl.collector/commit-boundary!]],
  the seam React occupies: the same `subscribe` closure
  `useSyncExternalStore` calls, holding the same cleanup React holds.
  The cell table, the reader memberships and the reapers are the real
  ones.

  ## What this file does NOT witness, and why

  The bead's matrix names a focused controlled input, child hook state
  inside a host, and an active imperative host. All three are facts
  React keeps on the fiber, and the node lane has no committing renderer
  — no DOM, no jsdom, and `react-dom/server` never commits — so none of
  the three can be observed here. What CAN be observed here is their
  single common cause, exactly: the element type at the position changes
  on every save. Their loss is then React's own documented consequence
  of that, which invariant I6 says is React's to own and not this
  runtime's to simulate. The DOM rows remain owed to a browser suite;
  the recorded contract says so rather than implying they were measured."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::hmr-remount)

(rf/reg-event :hmr-remount/seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-sub   :hmr-remount/label (fn [db _] (:label db)))

;; `:async? true` — `cljs.test` hard-errors on a fn-form fixture in a namespace
;; containing an `(async done …)` test, and a residue baseline is only honest
;; past the runtime's own reap horizon (`inventory/quiesced!`).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The source file, as a consumer writes it
;; ---------------------------------------------------------------------------

(defn- panel-body
  "The body a `defview` wraps. Named and separate so a reload can re-mint
  a head around the same body — which is what an unrelated edit elsewhere
  in the file does."
  [{:keys [tag]}]
  [:p {:class tag} (h/sub [:hmr-remount/label])])

(h/defview panel
  "Declared through the public door, so what a reload re-mints is the real
  product of the real macro."
  [props]
  (panel-body props))

(def ^:private view-name
  "The name `defview` computes: `\"<ns>/<sym>\"`. Written out because the
  reload model has to mint under the identical name — a reload changes a
  view's identity while keeping its address, and using a different name
  here would be modelling a different view instead."
  "re-frame.hicasso.hmr-remount-cljs-test/panel")

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private sub-key [frame-id [:hmr-remount/label]])

(defn- seeded!
  [label]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hmr-remount/seed label]))
  frame-id)

(defn- load-namespace!
  "Everything shadow-cljs re-runs when it reloads this namespace's module,
  and nothing else: the top-level registrations, then the `def` the
  `defview` expands to. Answers the newly minted head.

  The registrations are here rather than omitted because a save really
  does re-register — leaving them out would model a reload that only
  touched the view, which is not a reload of a Hicasso source file."
  [body]
  (rf/reg-event :hmr-remount/seed (fn [_ [_ label]] {:db {:label label}}))
  (rf/reg-sub   :hmr-remount/label (fn [db _] (:label db)))
  (collector/mint-view! view-name body))

(defn- element-type-of
  "The React `type` of the element the codec creates for `head` — the
  value React's reconciler compares with `===` to decide whether the
  fiber at a position can be updated in place or must be torn down and
  rebuilt. Read off a real element rather than off the head's marker, so
  the witness sees what React sees."
  [head]
  (.-type (codec/as-element [head {:tag "t"}])))

(defn- painted
  "The text the boundary's emitted element carries — its `<p>`'s only
  child. [[re-frame.hicasso.impl.collector/render-body]] answers the
  element the body produced, so this is the boundary's real render
  output rather than a value plucked from beside it."
  [element]
  (.. ^js element -props -children))

(defn- mount-boundary!
  "Take React's place for one boundary of `body`: run it under the
  generation fence, then hand the resolved read set and a notifier to the
  same `subscribe` closure `useSyncExternalStore` would call. Answers the
  emitted element, the entry, the notification counter, this mount's
  registration object, and React's own cleanup."
  [body]
  (let [value    (collector/render-body frame-id body {})
        entry    (collector/last-reads)
        notified (volatile! 0)
        release  (collector/commit-boundary! entry (fn [] (vswap! notified inc)))]
    {:value    value
     :entry    entry
     :notified notified
     :release  release
     ;; The registration this commit just installed: the newest reader on
     ;; the key's cell. It is the object the hand-over is judged by.
     :reg      (peek (inventory/cell-readers sub-key))}))

(defn- same-object?
  "`identical?`, answered as a plain boolean, and every identity assertion
  below goes through it.

  Not a flourish. `cljs.test` pr-strs a failing predicate's arguments, and
  a registration object holds a cyclic reference — so a bare
  `(is (identical? reg2 …))` that FAILS reports
  `RangeError: Maximum call stack size exceeded` instead of a diagnosis.
  Measured while proving these witnesses can go red: sabotaging the
  runtime's `release-cell!` turned four of them into stack overflows. A
  witness whose failure message is unreadable is most of the way to not
  being a witness, so the identity is compared here and the boolean is
  what the assertion prints."
  [a b]
  (identical? a b))

(defn- holds-key?
  "Is `reg` among the registrations currently reading the label key?
  Answered as a boolean, for [[same-object?]]'s reason."
  [reg]
  (boolean (some #(same-object? % reg) (inventory/cell-readers sub-key))))

(defn- settled
  "Run `f` once the runtime's own reapers have run — the only honest place
  to read a residue baseline."
  [f]
  (.then (inventory/quiesced!) f))

(def ^:private one-boundary {:cells 1 :cell-refs 1 :boundaries 1 :edges 1})
(def ^:private nothing      {:cells 0 :cell-refs 0 :boundaries 0 :edges 0})

(defn- retention [] (dissoc (inventory/residue) :entries))

;; ---------------------------------------------------------------------------
;; 1. The head is re-minted, so the element type changes — the whole cause
;; ---------------------------------------------------------------------------

(deftest a-save-re-mints-the-head-and-with-it-the-element-type
  (let [g1 (load-namespace! panel-body)
        g2 (load-namespace! panel-body)]

    ;; NEGATIVE CONTROL, taken FIRST so the instrument is proven able to
    ;; report SAMENESS before it is asked to report a change. A head's memo
    ;; wrapper is minted once at definition and never per element, so two
    ;; elements from one head carry one type — if `element-type-of` allocated
    ;; per call, every comparison below would report "changed" vacuously.
    (is (true? (same-object? (element-type-of g1) (element-type-of g1)))
        "one head, one element type — the instrument can see sameness")

    (testing "a reload rebinds the var to a different object"
      (is (false? (same-object? g1 g2))
          "the head is re-minted, not refreshed in place"))

    (testing "and React meets a different element type at that position,
              which is the entire mechanism by which a save costs a subtree"
      (is (false? (same-object? (element-type-of g1) (element-type-of g2)))))

    (testing "the address survives the transition and the identity does not
              — the frame-incarnation rule rf2-hic-013 recorded, one level up
              and on the view rather than on the frame"
      (is (= view-name (.-displayName ^js g1)))
      (is (= view-name (.-displayName ^js g2))))))

(deftest the-re-mint-is-unconditional-so-an-unchanged-view-is-replaced-too
  ;; The costly half of the contract, and the one a developer is most likely
  ;; to get wrong: the type change is not conditional on the body having
  ;; changed. Both generations below share the SAME body function object —
  ;; the strongest possible statement of "this view was not edited" — and the
  ;; type still changes, because `mint-view!` allocates a fresh component and
  ;; a fresh memo wrapper on every call. Editing one line anywhere in a source
  ;; file therefore remounts every boundary that file defines.
  (let [g1 (collector/mint-view! view-name panel-body)
        g2 (collector/mint-view! view-name panel-body)]
    (is (true? (same-object? panel-body panel-body))
        "the body is one object, by construction")
    (is (false? (same-object? g1 g2))
        "same name, same body, different head")
    (is (false? (same-object? (element-type-of g1) (element-type-of g2)))
        "so React replaces the subtree for a view whose source did not change")))

(deftest the-defview-macro-mints-what-a-reload-re-mints
  ;; The model's own guard. `load-namespace!` claims to be what re-evaluating
  ;; the `defview` form does; that claim is only true while the macro expands
  ;; to this call under this name. If `mint-view!` ever gains an HMR-stable
  ;; proxy keyed on the view name — rf2-hic-030 promises `n/defcomponent`
  ;; "source/HMR metadata", so it is a live possibility — this test goes red
  ;; and the recorded contract must be rewritten before the suite can pass.
  ;; That is the intended behaviour: the freeze is falsifiable.
  (let [reloaded (load-namespace! panel-body)]
    (is (true? (codec/boundary-head? panel))
        "the macro's product is a marked boundary head")
    (is (true? (codec/boundary-head? reloaded))
        "and so is the reload's, so the two are the same kind of object")
    (is (= (.-displayName ^js panel) (.-displayName ^js reloaded) view-name)
        "minted under the identical name — same address")
    (is (false? (same-object? panel reloaded))
        "and it is nonetheless a different head, which is the reload")))

;; ---------------------------------------------------------------------------
;; 2. The hand-over: the stale generation lets go of everything
;; ---------------------------------------------------------------------------

(deftest a-save-hands-the-key-over-and-the-stale-generation-holds-nothing
  (async done
    (seeded! "A")
    (let [g1   (load-namespace! panel-body)
          m1   (mount-boundary! panel-body)
          reg1 (:reg m1)]
      (is (= "A" (painted (:value m1)))
          "the first generation painted the seeded value")
      (is (= one-boundary (retention))
          "and acquired exactly one cell and one reader membership")
      (is (some? reg1))

      ;; The save. React sees a new type at the position, deletes the old
      ;; fiber and mounts a new one in the same commit: the deleted tree's
      ;; passive destroy runs, then the new tree's passive create.
      (let [g2 (load-namespace! panel-body)]
        (is (false? (same-object? (element-type-of g1) (element-type-of g2)))
            "the save really did change the type — the premise of the remount")
        ((:release m1))
        (let [m2   (mount-boundary! panel-body)
              reg2 (:reg m2)]

          (testing "the successor paints the state the save did not touch"
            (is (= "A" (painted (:value m2)))
                "application state is not a React fact and survives the save"))

          (settled
            (fn [_]
              (testing "after the runtime's own reapers, exactly one boundary
                        is holding the key"
                (is (= one-boundary (retention))))

              (testing "and it is the SUCCESSOR's registration, by identity —
                        the assertion a count cannot make, and the one a
                        rendered-markup assertion cannot make at all"
                (let [readers (inventory/cell-readers sub-key)]
                  (is (= 1 (count readers)))
                  (is (true? (same-object? reg2 (first readers)))
                      "the survivor is the generation that is mounted")
                  (is (false? (holds-key? reg1))
                      "and the retired generation is gone from the reader list")))

              ((:release m2))
              (settled
                (fn [_]
                  (testing "releasing the live generation leaves nothing at all
                            — zero residue after quiescence, invariant I5's
                            exact-teardown clause across a reload"
                    (is (= nothing (retention)))
                    (is (= 0 (:entries (inventory/residue)))
                        "the shared read-set entry is reaped too"))
                  (done))))))))))

(deftest the-hand-over-does-not-depend-on-the-order-react-commits-it-in
  ;; React deletes the old fiber and mounts the new one inside one commit,
  ;; and the destroy/create ORDER within that commit is React's business, not
  ;; this runtime's. `make-subscribe`'s cleanup claims order-independence —
  ;; "the registration holds exactly the cells it acquired, so its cleanup
  ;; cannot release a successor's". This runs the save in the OPPOSITE order
  ;; from the test above, create-then-destroy, and requires the same terminal
  ;; state. A cleanup that released by key rather than by held reference would
  ;; strip the successor here and pass there.
  (async done
    (seeded! "A")
    (let [m1 (mount-boundary! panel-body)]
      (load-namespace! panel-body)
      (let [m2   (mount-boundary! panel-body)          ; create first
            reg2 (:reg m2)]
        ((:release m1))                                ; destroy second
        (settled
          (fn [_]
            (is (= one-boundary (retention))
                "same terminal retention as destroy-then-create")
            (is (true? (same-object? reg2 (first (inventory/cell-readers sub-key))))
                "and the successor still holds the key")
            ((:release m2))
            (settled (fn [_] (is (= nothing (retention))) (done)))))))))

;; ---------------------------------------------------------------------------
;; 3. The sabotage the bead names: a leaked old-generation registration
;; ---------------------------------------------------------------------------

(deftest a-leaked-stale-registration-turns-the-cleanup-witness-red
  ;; The bead's acceptance criterion, run as a real perturbation rather than
  ;; described. The leak modelled is the plausible one: a runtime that
  ;; re-mints the head but loses React's cleanup for the deleted fiber — a
  ;; `defonce` reader list, a subscription registered outside the effect, an
  ;; unsubscribe swallowed on the deletion path. The perturbation is
  ;; therefore *omitting the predecessor's release*, which is precisely what
  ;; such a runtime does.
  ;;
  ;; PERTURB, CONFIRM RED, RESTORE, CONFIRM GREEN — all three phases assert.
  (async done
    (seeded! "A")
    (let [m1   (mount-boundary! panel-body)
          reg1 (:reg m1)]
      (load-namespace! panel-body)
      (let [m2   (mount-boundary! panel-body)
            reg2 (:reg m2)]
        ;; PERTURBED: (:release m1) is deliberately NOT called.
        (settled
          (fn [_]
            (testing "RED — the witness of the previous test fails here, and
                      fails on every one of its numbers"
              (is (not= one-boundary (retention))
                  "the clean-hand-over assertion is violated")
              (is (= {:cells 1 :cell-refs 2 :boundaries 2 :edges 2} (retention))
                  "two registrations, two memberships, two edges — one cell,
                   because the leak is a reader that never let go rather than
                   a second key"))

            (testing "RED — and the identity assertion names the culprit,
                      which a count could not"
              (let [readers (inventory/cell-readers sub-key)]
                (is (= 2 (count readers)))
                (is (true? (holds-key? reg1))
                    "the retired generation is still reading the key")
                (is (true? (holds-key? reg2)))))

            (testing "RED — and the stale reader is still WIRED, so the leak
                      is a live subscription and not merely a dead slot: a
                      write notifies the retired generation too"
              (let [before-1 @(:notified m1)
                    before-2 @(:notified m2)]
                (rf/with-frame frame-id (rf/dispatch-sync [:hmr-remount/seed "B"]))
                (is (> @(:notified m1) before-1)
                    "the unmounted generation was told the store moved")
                (is (> @(:notified m2) before-2))))

            ;; RESTORE: perform the release the perturbation withheld.
            ((:release m1))
            (settled
              (fn [_]
                (testing "GREEN — with the cleanup restored, the same
                          instrument reports the clean hand-over, so the red
                          above was the leak and not a broken witness"
                  (is (= one-boundary (retention)))
                  (is (true? (same-object? reg2 (first (inventory/cell-readers sub-key))))))
                ((:release m2))
                (settled (fn [_] (is (= nothing (retention))) (done)))))))))))

;; ---------------------------------------------------------------------------
;; 4. Saves do not accumulate
;; ---------------------------------------------------------------------------

(deftest successive-saves-leave-one-generation-holding-the-key
  ;; "Stale generations leave zero registrations", taken over a session
  ;; rather than a single save — a per-generation leak of any size shows as
  ;; growth here, and the previous test is this one's proof that the
  ;; instrument does grow when something is retained.
  (async done
    (seeded! "A")
    (let [!live (volatile! (mount-boundary! panel-body))]
      (dotimes [_ 3]
        (load-namespace! panel-body)
        (let [previous @!live]
          ((:release previous))
          (vreset! !live (mount-boundary! panel-body))))
      (settled
        (fn [_]
          (is (= one-boundary (retention))
              "three saves later, still exactly one cell, one reader, one edge")
          (is (true? (same-object? (:reg @!live) (first (inventory/cell-readers sub-key))))
              "and the holder is the newest generation")
          (is (= 1 (:entries (inventory/residue)))
              "one read-set entry, shared across every generation and handed
               from each to the next rather than re-minted per save")
          ((:release @!live))
          (settled
            (fn [_]
              (is (= nothing (retention)))
              (is (= 0 (:entries (inventory/residue))))
              (done))))))))
