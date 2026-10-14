(ns re-frame.fresco.roots-frames-shared-frame-dom-cljs-test
  "TWO ROOTS, ONE FRAME — the other axis.

  The property has two nouns in it: independent roots *and* frames
  cannot observe or corrupt one another. Its sibling suites take the
  second noun. `roots-frames-isolation-dom-cljs-test` and
  `roots-frames-hydration-dom-cljs-test` both mount root A under frame A
  and root B under frame B, every row, so across both suites the root
  axis and the frame axis are the SAME axis and nothing separates them. A
  runtime in which roots were not independent at all — in which everything
  root-shaped were really frame-shaped — passes every one of those rows.

  This file is the case that separates them, and it is not a hypothetical:
  `re-frame.fresco.impl.roots/open-adoption-window!` rejects a
  frame-keyed window registry in its own docstring, on the grounds that
  **several roots may intentionally share a frame**. That sentence is a
  design decision with real consequences (it is why the window is a bare
  ref reachable only from its root's handle) and nothing else exercises
  the situation it names.

  ## Sharing a frame is the case where the kernel's arithmetic is load-bearing

  A sub-key is `[frame-kw query-v]` and nothing about it mentions a root.
  So one root per frame means every cell has exactly ONE reader, which
  means `impl.collector/release-cell!`'s `(zero? (alength readers))`
  branch is the only branch a one-root-per-frame row ever takes: every
  unmount is a last-reader unmount and the reaper always fires. Put two
  roots on one frame and the same key has two readers, and the arithmetic
  starts mattering. Root A's teardown must DECREMENT that key and leave
  it alive, because root B is still reading it.

  The failure if it does not is silent in the way this file exists to
  catch. Root B keeps its DOM, keeps its markup, keeps rendering — it
  simply never hears another commit, because the cell it subscribed
  through was disposed out from under it by a sibling's teardown. No
  error, no complaint, no visible difference until someone dispatches and
  the screen does not move. [[unmounting-one-root-decrements-the-shared-key-and-does-not-dispose-it]]
  is that row, and it reads liveness afterwards rather than counts alone
  for exactly that reason.

  ## The completion signal has to change with the axis

  The two-frame suites wait on a frame appearing in the cell table: a
  cell is acquired at commit, so a frame that is present has committed.
  That signal is unavailable here — both roots share one frame, so the
  frame appears the moment the FIRST root commits and a row waiting on it
  would proceed with one root still in flight. The shared-frame analogue
  is the READER COUNT on the shared key, which reaches two only when both
  roots have committed. [[both-committed?]] is that, and it is the same
  kind of observable for the same reason: React cannot forge it.

  Everything else — why the DOM is corroboration and never the primary
  reading, why node identity is what tells adoption from a re-render, why
  a residue census is taken before the reset and not after —
  `re-frame.fresco.roots-frames-support` states once for all three
  files."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.impl.roots :as rf.fresco.impl.roots]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.test-support :as rf.test-support]))

;; ONE frame. The whole file is about what two roots do to it.
(def ^:private shared-frame ::shared)

(def ^:private label-q [::label])
(def ^:private count-q [::count])

;; Registered ABOVE `use-fixtures` — the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is EVALUATED, so a
;; registration written below it is erased before the first row runs and
;; every screen renders nothing.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-sub ::count (fn [db _] (:count db)))

(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label :count 0}}))
(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; `nil` and not the default: the default leaves a dynamic-var frame
     ;; stamp in ambient scope, so a boundary that failed to resolve its
     ;; own frame could answer that one instead. This suite makes its own.
     :ambient-frame nil
     ;; The MAP shape, because rows below are `async` — `cljs.test`
     ;; refuses an async test under a fn-form fixture and aborts the whole
     ;; browser run at this namespace when it does.
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app — one view, mounted twice, under ONE frame
;; ---------------------------------------------------------------------------

(rf.fresco/defview panel
  "The whole app: two subscription reads and one intent, in a single
  boundary body. Takes no frame argument — the frame arrives from the
  root it is mounted under, which here is the same frame for both.

  One body reading two keys is what makes the two numbers below
  independent: `:boundaries` counts registrations and `readers-of` counts
  slots per key, so `2` and `2` are a genuine cross-check rather than one
  number read twice."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag :data-frame (str (rf/current-frame-id))}
   [:span.label (rf.fresco/sub label-q)]
   [:span.count (str (rf.fresco/sub count-q))]])

(rf.fresco/defview screen
  "The hydration row's app. `title` is a PROP, so the two roots' markup
  differs by one argument at the call site rather than by a second app;
  the subscription read is what makes the root acquire a
  frame-keyed cell and so makes its commit observable."
  [{:keys [title]}]
  [:div.screen
   [:h1.title title]
   [:p.value (rf.fresco/sub label-q)]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh!
  "One frame, seeded, and an empty runtime."
  []
  (rf.fresco.roots-frames-support/leave-act-environment!)
  (rf/make-frame {:id shared-frame})
  (rf/with-frame shared-frame (rf/dispatch-sync [::seed "alpha"]))
  (rf.fresco.impl.collector/reset-runtime!)
  (rf.fresco.test.runtime/reset-body-runs!)
  nil)

(defn- text-at [handle sel]
  (some-> (.querySelector (:container handle) sel) .-textContent))

(defn- both-committed?
  "Have BOTH roots committed?

  Not `cell-frames`, which the two-frame suites use: one frame is
  mentioned by the cell table as soon as the first root commits, so that
  predicate would answer true with a root still in flight. Two readers on
  the shared key is the reading that needs both."
  []
  (= 2 (rf.fresco.roots-frames-support/readers-of [shared-frame label-q])))

;; ---------------------------------------------------------------------------
;; S1 — one cell, two readers, and both of them real
;; ---------------------------------------------------------------------------

(deftest two-roots-under-one-frame-share-one-cell-and-both-read-it
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no DOM")
    (let [_ (fresh!)
          a (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!) shared-frame [panel {:tag "a"}])
          b (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!) shared-frame [panel {:tag "b"}])]
      (try
        (testing "two roots reading two queries under one frame make TWO cells,
                  not four — the sub-key is (frame, query) and mentions no
                  root, so sharing a frame is sharing the cell. This is the
                  exact shape the two-frame suites never produce"
          (is (= #{[shared-frame label-q] [shared-frame count-q]} (rf.fresco.roots-frames-support/cell-keys))
              (str "got " (pr-str (rf.fresco.roots-frames-support/cell-keys)))))

        (testing "and each shared key carries TWO readers — the fan-out that
                  makes `release-cell!`'s arithmetic load-bearing, and that
                  every two-frame row pins at one"
          (is (= [2 2] [(rf.fresco.roots-frames-support/readers-of [shared-frame label-q])
                        (rf.fresco.roots-frames-support/readers-of [shared-frame count-q])])
              (str "reader counts: " (pr-str (rf.fresco.test.runtime/residue)))))

        (testing "and the two readers are two DISTINCT boundaries, not one
                  counted twice — `:boundaries` counts registrations and
                  `readers-of` counts slots, so the two numbers can disagree
                  and here they corroborate"
          (is (= 2 (:boundaries (rf.fresco.test.runtime/stats)))
              (str "got " (pr-str (rf.fresco.test.runtime/stats)))))

        (testing "BOTH roots are live readers of the shared key: one dispatch
                  re-runs TWO bodies. This is what stops the counts above
                  passing vacuously — a second reader slot that never re-ran
                  would satisfy every assertion so far"
          (let [ran (rf.fresco.roots-frames-support/body-runs-delta! (fn [] (rf.fresco.impl.mount/dispatch! a [::bump])))]
            (is (= 2 ran)
                (str "a commit on a shared key must notify every reader of it; "
                     ran " bodies ran"))))

        (testing "and the markup corroborates on BOTH roots — roots sharing a
                  frame share its state, which is the whole point of allowing
                  it, and both screens moved on one dispatch"
          (is (= "1" (text-at a ".count")))
          (is (= "1" (text-at b ".count")))
          (is (= "alpha" (text-at a ".label")))
          (is (= "alpha" (text-at b ".label")))
          (is (= (str shared-frame) (.getAttribute (.querySelector (:container a) ".panel")
                                                   "data-frame")))
          (is (= (str shared-frame) (.getAttribute (.querySelector (:container b) ".panel")
                                                   "data-frame"))))

        (finally (rf.fresco.impl.mount/release! a) (rf.fresco.impl.mount/release! b))))))

;; ---------------------------------------------------------------------------
;; S2 — a teardown DECREMENTS a shared key. It does not dispose it.
;; ---------------------------------------------------------------------------

;; THE ROW THIS FILE EXISTS FOR.
;;
;; `impl.collector/release-cell!` splices the departing reader out and arms
;; the reaper only `(when (zero? (alength readers)))`. With one root per
;; frame that guard is unconditional in practice — the fan-out is always
;; one, so every teardown is a last-reader teardown — and no two-frame row
;; takes the other branch. Here root A leaves a reader behind, and the guard
;; has to hold.
;;
;; The failure it prevents is silent, which is why the row does not stop at
;; the counts. A disposed cell does not throw and does not clear the screen:
;; root B keeps every node it has and simply stops hearing commits. So after
;; asserting the key survived, the row DISPATCHES and reads both the body
;; count and the DOM — a stranded root re-runs zero bodies and shows a
;; frozen number, and nothing else about it looks wrong.
;;
;; MUTATION. The reference count on a shared cell is guarded TWICE and
;; neither guard alone is load-bearing, so no single-point mutation reds
;; this row. Making `release-cell!` arm the reaper unconditionally —
;; `(arm-cell-reaper! cell)` with no `when` — stays green, because the
;; reaper's own callback re-checks `(zero? (alength (.-readers cell)))`
;; before it disposes. Deleting that re-check instead, and leaving
;; `release-cell!` guarded, stays green too: `release-cell!` never arms
;; while a reader remains, so the mutated line is unreachable. Removing
;; BOTH — the coherent "the runtime forgot a cell can have more than one
;; reader" defect — reds this row five times: the surviving key set, the
;; decremented reader counts, the boundary count, the survivor's body
;; count and the survivor's DOM. Every row in both two-frame suites stays
;; green under all three.
(deftest unmounting-one-root-decrements-the-shared-key-and-does-not-dispose-it
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (rf.fresco.roots-frames-support/skip! ":node-test has no DOM") (done))
      (let [_ (fresh!)
            a (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!) shared-frame [panel {:tag "a"}])
            b (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!) shared-frame [panel {:tag "b"}])]
        (is (= [2 2] [(rf.fresco.roots-frames-support/readers-of [shared-frame label-q])
                      (rf.fresco.roots-frames-support/readers-of [shared-frame count-q])])
            "precondition: both roots are reading the shared keys")
        ;; `unmount!` and NOT `release!` — `release!` resets the runtime, so
        ;; every count below would read zero whether the teardown released
        ;; anything or not.
        (rf.fresco.impl.mount/unmount! a)
        (-> (rf.fresco.roots-frames-support/quiesced!)
            (.then
              (fn [_]
                (try
                  (testing "the shared keys SURVIVE their first reader's
                            departure — the reaper is armed on the last reader
                            and root B is still one"
                    (is (= #{[shared-frame label-q] [shared-frame count-q]}
                           (rf.fresco.roots-frames-support/cell-keys))
                        (str "a sibling's teardown disposed a key root B still
                              reads; got " (pr-str (rf.fresco.roots-frames-support/cell-keys)))))

                  (testing "decremented, not merely present: one reader each,
                            and one boundary. A cell that kept root A's slot
                            would be a leak and reads the same as a survivor
                            unless the number is taken"
                    (is (= [1 1] [(rf.fresco.roots-frames-support/readers-of [shared-frame label-q])
                                  (rf.fresco.roots-frames-support/readers-of [shared-frame count-q])])
                        (str "got " (pr-str (rf.fresco.test.runtime/residue))))
                    (is (= 1 (:boundaries (rf.fresco.test.runtime/stats)))
                        (str "got " (pr-str (rf.fresco.test.runtime/stats)))))

                  (testing "and the survivor is LIVE, which is the half a count
                            cannot show. A cell disposed out from under root B
                            leaves its DOM intact and its body silent — no
                            error, no complaint, just a screen that stopped
                            moving"
                    (let [ran (rf.fresco.roots-frames-support/body-runs-delta!
                                (fn [] (rf.fresco.impl.mount/dispatch! b [::bump])))]
                      (is (= 1 ran)
                          (str "the surviving root must still re-run on a
                                commit; " ran " bodies ran")))
                    (is (= "1" (text-at b ".count"))
                        "and the survivor's DOM moved")
                    (is (= "alpha" (text-at b ".label"))))

                  (testing "and tearing the survivor down then releases
                            everything — residue read BEFORE the reset, which
                            is the only reading that can go red"
                    (is (= rf.fresco.roots-frames-support/released (rf.fresco.roots-frames-support/teardown-census! b))))
                  (finally
                    (rf.fresco.impl.mount/release! a)
                    (rf.fresco.impl.mount/release! b)
                    (done))))))))))

;; ---------------------------------------------------------------------------
;; S3 — two hydrating roots on one frame hold two windows
;; ---------------------------------------------------------------------------

;; The witness for the sentence `impl.roots/open-adoption-window!` argues
;; from, which no two-frame row tests: "A registry keyed by frame cannot work
;; — several roots may intentionally share a frame."
;;
;; Under such a registry these two roots would look up ONE window, so root A's
;; closer would shut the window root B is still adopting in — the exact
;; page-global defect the per-root window closes, at frame granularity, and
;; invisible to every two-frame row, because each of those gives each root a
;; frame of its own and a frame-keyed registry is per-root there.
;;
;; The two readings that discriminate are both taken by CONSTRUCTION rather
;; than on a timer. `hydrate-root!` returns before its tree is adopted, so
;; both windows are provably open on the line after the second call; and
;; the windows are compared by IDENTITY, which a registry keyed by anything
;; these two roots share cannot satisfy however it is implemented.
;;
;; MUTATION: key the window off the frame in `hydrate-root!` — mint into a
;; `defonce` map on first use per frame and hand both roots the same object —
;; and this row reds on `identical?` and again on root B still adopting after
;; root A's window is shut. Both roots here name ONE frame, so
;; `sup/with-page-global-adoption` — the executing form of the page-global
;; mutation — would arm an observationally identical defect against this row
;; if a second control for this axis is wanted. The kernel family's
;; executing control is the sibling suite's
;; `a-page-global-adoption-window-steals-an-ordinary-roots-enter-transition`.
(deftest two-hydrating-roots-on-one-frame-hold-windows-of-their-own
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (rf.fresco.roots-frames-support/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (rf.fresco.roots-frames-support/server-html! shared-frame [screen {:title "A"}])
              html-b (rf.fresco.roots-frames-support/server-html! shared-frame [screen {:title "B"}])
              ca     (rf.fresco.roots-frames-support/stamp-server-nodes! (rf.fresco.roots-frames-support/server-dom! html-a))
              cb     (rf.fresco.roots-frames-support/stamp-server-nodes! (rf.fresco.roots-frames-support/server-dom! html-b))]
          (is (rf.fresco.roots-frames-support/every-server-node? ca ".screen, .title, .value")
              "premise: the stamps are on the server's own nodes")
          (rf.fresco.impl.collector/reset-runtime!)
          (let [ha (rf.fresco.impl.mount/hydrate-root! ca shared-frame [screen {:title "A"}])
                hb (rf.fresco.impl.mount/hydrate-root! cb shared-frame [screen {:title "B"}])]
            (testing "each root minted a window OF ITS OWN, though both name
                      one frame — the reading a frame-keyed registry cannot
                      produce, taken by construction and not on a timer"
              (is (true? (rf.fresco.impl.roots/adopting? (:adoption ha)))
                  "root A is in flight — `hydrate-root!` returns before the
                   tree is adopted, so its window outlives the call")
              (is (true? (rf.fresco.impl.roots/adopting? (:adoption hb)))
                  "and so is root B")
              (is (not (identical? (:adoption ha) (:adoption hb)))
                  "two roots sharing a frame must not share a window"))

            (testing "so one root's closer shuts one root's window. This is
                      the page-global defect asked again at frame granularity
                      — where every two-frame row is immune because no two of
                      its roots share a frame"
              (rf.fresco.impl.roots/close-adoption-window! (:adoption ha))
              (is (false? (rf.fresco.impl.roots/adopting? (:adoption ha))))
              (is (true? (rf.fresco.impl.roots/adopting? (:adoption hb)))
                  "root B must still be adopting after its frame-mate closed"))

            (-> (rf.fresco.roots-frames-support/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (try
                      (is (true? ok)
                          (str "both roots must commit; readers on the shared
                                key were "
                               (rf.fresco.roots-frames-support/readers-of [shared-frame label-q])))

                      (testing "and each root ADOPTED ITS OWN server DOM — the
                                nodes are the very nodes each container's
                                markup produced, which no re-render could
                                reconstruct, and sharing a frame did not make
                                either root adopt the other's tree"
                        (is (rf.fresco.roots-frames-support/every-server-node? ca ".screen, .title, .value")
                            "root A kept the server's nodes")
                        (is (rf.fresco.roots-frames-support/every-server-node? cb ".screen, .title, .value")
                            "root B kept the server's nodes"))

                      (finally (rf.fresco.impl.mount/release! ha) (rf.fresco.impl.mount/release! hb) (done))))))))))))
