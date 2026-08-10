(ns re-frame.hicasso.roots-frames-shared-frame-dom-cljs-test
  "TWO ROOTS, ONE FRAME — the other axis (rf2-hic-012).

  The bead's goal sentence has two nouns in it: independent roots *and*
  frames cannot observe or corrupt one another. Its sibling suites take
  the second noun. `roots-frames-isolation-dom-cljs-test` and
  `roots-frames-hydration-dom-cljs-test` both mount root A under frame A
  and root B under frame B, every row, so across all ten of them the root
  axis and the frame axis are the SAME axis and nothing separates them. A
  runtime in which roots were not independent at all — in which everything
  root-shaped were really frame-shaped — passes every one of those rows.

  This file is the case that separates them, and it is not a hypothetical:
  `re-frame.hicasso.impl.roots/open-adoption-window!` rejects a
  frame-keyed window registry in its own docstring, on the grounds that
  **several roots may intentionally share a frame**. That sentence is a
  design decision with real consequences (it is why the window is a bare
  ref reachable only from its root's handle) and nothing anywhere
  exercised the situation it names.

  ## Sharing a frame is the case where the kernel's arithmetic is load-bearing

  A sub-key is `[frame-kw query-v]` and nothing about it mentions a root.
  So one root per frame means every cell has exactly ONE reader, which
  means `impl.collector/release-cell!`'s `(zero? (alength readers))`
  branch is the only branch any existing row has ever taken: every
  unmount is a last-reader unmount and the reaper always fires. Put two
  roots on one frame and the same key has two readers, and the arithmetic
  starts mattering. Root A's teardown must DECREMENT that key and leave
  it alive, because root B is still reading it.

  The failure if it does not is silent in the way this bead exists to
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
  `re-frame.hicasso.roots-frames-support` states once for all three
  files."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.test-support :as test-support]))

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
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; `nil` and not the default: the default leaves a dynamic-var frame
     ;; stamp in ambient scope, so a boundary that failed to resolve its
     ;; own frame could answer that one instead. This suite makes its own.
     :ambient-frame nil
     ;; The MAP shape, because rows below are `async` — `cljs.test`
     ;; refuses an async test under a fn-form fixture and aborts the whole
     ;; browser run at this namespace when it does.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app — one view, mounted twice, under ONE frame
;; ---------------------------------------------------------------------------

(h/defview panel
  "The whole app: two subscription reads and one intent, in a single
  boundary body. Takes no frame argument — the frame arrives from the
  root it is mounted under, which here is the same frame for both.

  One body reading two keys is what makes the two numbers below
  independent: `:boundaries` counts registrations and `readers-of` counts
  slots per key, so `2` and `2` are a genuine cross-check rather than one
  number read twice."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag :data-frame (str (h/hframe))}
   [:span.label (h/sub label-q)]
   [:span.count (str (h/sub count-q))]])

(h/defview screen
  "The hydration rows' app. `title` is a PROP, so a server/client
  divergence is a one-argument change at the call site rather than a
  second app; the subscription read is what makes the root acquire a
  frame-keyed cell and so makes its commit observable."
  [{:keys [title]}]
  [:div.screen
   [:h1.title title]
   [:p.value (h/sub label-q)]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh!
  "One frame, seeded, and an empty runtime."
  []
  (sup/leave-act-environment!)
  (rf/make-frame {:id shared-frame})
  (rf/with-frame shared-frame (rf/dispatch-sync [::seed "alpha"]))
  (collector/reset-runtime!)
  (collector/reset-body-runs!)
  nil)

(defn- text-at [handle sel]
  (some-> (.querySelector (:container handle) sel) .-textContent))

(defn- text-in [container sel]
  (some-> (.querySelector container sel) .-textContent))

(defn- both-committed?
  "Have BOTH roots committed?

  Not `cell-frames`, which the two-frame suites use: one frame is
  mentioned by the cell table as soon as the first root commits, so that
  predicate would answer true with a root still in flight. Two readers on
  the shared key is the reading that needs both."
  []
  (= 2 (sup/readers-of [shared-frame label-q])))

;; ---------------------------------------------------------------------------
;; S1 — one cell, two readers, and both of them real
;; ---------------------------------------------------------------------------

(deftest two-roots-under-one-frame-share-one-cell-and-both-read-it
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no DOM")
    (let [_ (fresh!)
          a (mount/root! (mount/fresh-container!) shared-frame [panel {:tag "a"}])
          b (mount/root! (mount/fresh-container!) shared-frame [panel {:tag "b"}])]
      (try
        (testing "two roots reading two queries under one frame make TWO cells,
                  not four — the sub-key is (frame, query) and mentions no
                  root, so sharing a frame is sharing the cell. This is the
                  exact shape the two-frame suites never produce"
          (is (= #{[shared-frame label-q] [shared-frame count-q]} (sup/cell-keys))
              (str "got " (pr-str (sup/cell-keys))))
          (is (= #{shared-frame} (sup/cell-frames))))

        (testing "and each shared key carries TWO readers — the fan-out that
                  makes `release-cell!`'s arithmetic load-bearing, and that
                  every existing row pins at one"
          (is (= [2 2] [(sup/readers-of [shared-frame label-q])
                        (sup/readers-of [shared-frame count-q])])
              (str "reader counts: " (pr-str (inventory/residue)))))

        (testing "and the two readers are two DISTINCT boundaries, not one
                  counted twice — `:boundaries` counts registrations and
                  `readers-of` counts slots, so the two numbers can disagree
                  and here they corroborate"
          (is (= 2 (:boundaries (inventory/stats)))
              (str "got " (pr-str (inventory/stats))))
          (is (= 4 (:cell-refs (inventory/residue)))
              "two keys at fan-out two"))

        (testing "one frame is one memo row, however many roots render it —
                  the row is keyed by frame and a second root must not mint a
                  second"
          (is (= #{shared-frame} (sup/frame-memo-frames))
              (str "got " (pr-str (sup/frame-memo-frames)))))

        (testing "BOTH roots are live readers of the shared key: one dispatch
                  re-runs TWO bodies. This is what stops the counts above
                  passing vacuously — a second reader slot that never re-ran
                  would satisfy every assertion so far"
          (let [ran (sup/body-runs-delta! (fn [] (mount/dispatch! a [::bump])))]
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

        (finally (mount/release! a) (mount/release! b))))))

;; ---------------------------------------------------------------------------
;; S2 — a teardown DECREMENTS a shared key. It does not dispose it.
;; ---------------------------------------------------------------------------

;; THE ROW THIS FILE EXISTS FOR.
;;
;; `impl.collector/release-cell!` splices the departing reader out and arms
;; the reaper only `(when (zero? (alength readers)))`. With one root per
;; frame that guard is unconditional in practice — the fan-out is always
;; one, so every teardown is a last-reader teardown — and no landed row has
;; ever taken the other branch. Here root A leaves a reader behind, and the
;; guard has to hold.
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
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (let [_ (fresh!)
            a (mount/root! (mount/fresh-container!) shared-frame [panel {:tag "a"}])
            b (mount/root! (mount/fresh-container!) shared-frame [panel {:tag "b"}])]
        (is (= [2 2] [(sup/readers-of [shared-frame label-q])
                      (sup/readers-of [shared-frame count-q])])
            "precondition: both roots are reading the shared keys")
        ;; `unmount!` and NOT `release!` — `release!` resets the runtime, so
        ;; every count below would read zero whether the teardown released
        ;; anything or not.
        (mount/unmount! a)
        (-> (sup/quiesced!)
            (.then
              (fn [_]
                (try
                  (testing "the shared keys SURVIVE their first reader's
                            departure — the reaper is armed on the last reader
                            and root B is still one"
                    (is (= #{[shared-frame label-q] [shared-frame count-q]}
                           (sup/cell-keys))
                        (str "a sibling's teardown disposed a key root B still
                              reads; got " (pr-str (sup/cell-keys)))))

                  (testing "decremented, not merely present: one reader each,
                            and one boundary. A cell that kept root A's slot
                            would be a leak and reads the same as a survivor
                            unless the number is taken"
                    (is (= [1 1] [(sup/readers-of [shared-frame label-q])
                                  (sup/readers-of [shared-frame count-q])])
                        (str "got " (pr-str (inventory/residue))))
                    (is (= 1 (:boundaries (inventory/stats)))
                        (str "got " (pr-str (inventory/stats)))))

                  (testing "and the survivor is LIVE, which is the half a count
                            cannot show. A cell disposed out from under root B
                            leaves its DOM intact and its body silent — no
                            error, no complaint, just a screen that stopped
                            moving"
                    (let [ran (sup/body-runs-delta!
                                (fn [] (mount/dispatch! b [::bump])))]
                      (is (= 1 ran)
                          (str "the surviving root must still re-run on a
                                commit; " ran " bodies ran")))
                    (is (= "1" (text-at b ".count"))
                        "and the survivor's DOM moved")
                    (is (= "alpha" (text-at b ".label"))))

                  (testing "and tearing the survivor down then releases
                            everything — residue read BEFORE the reset, which
                            is the only reading that can go red"
                    (is (= sup/released (sup/teardown-census! b))))
                  (finally
                    (mount/release! a)
                    (mount/release! b)
                    (done))))))))))

;; ---------------------------------------------------------------------------
;; S3 — two hydrating roots on one frame hold two windows
;; ---------------------------------------------------------------------------

;; The witness for the sentence `impl.roots/open-adoption-window!` argues
;; from and nothing tested: "A registry keyed by frame cannot work — several
;; roots may intentionally share a frame."
;;
;; Under such a registry these two roots would look up ONE window, so root
;; A's closer would shut the window root B is still adopting in — the exact
;; page-global defect rf2-6tmu repaired, reintroduced at frame granularity
;; and invisible to every landed row, because every landed row gives each
;; root a frame of its own and a frame-keyed registry is per-root there.
;;
;; The two readings that discriminate are both taken by CONSTRUCTION rather
;; than on a timer. `hydrate-root!` returns before its tree is adopted, so
;; both windows are provably open on the line after the second call; and
;; the windows are compared by IDENTITY, which a registry keyed by anything
;; these two roots share cannot satisfy however it is implemented.
;;
;; MUTATION (PR body records the red): key the window off the frame in
;; `hydrate-root!` — mint into a `defonce` map on first use per frame and
;; hand both roots the same object — and this row reds on `identical?` and
;; again on root B still adopting after root A's window is shut.
(deftest two-hydrating-roots-on-one-frame-hold-windows-of-their-own
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (sup/server-html! shared-frame [screen {:title "A"}])
              html-b (sup/server-html! shared-frame [screen {:title "B"}])
              ca     (sup/stamp-server-nodes! (sup/server-dom! html-a))
              cb     (sup/stamp-server-nodes! (sup/server-dom! html-b))]
          (is (sup/every-server-node? ca ".screen, .title, .value")
              "premise: the stamps are on the server's own nodes")
          (collector/reset-runtime!)
          (let [ha (mount/hydrate-root! ca shared-frame [screen {:title "A"}])
                hb (mount/hydrate-root! cb shared-frame [screen {:title "B"}])]
            (testing "each root minted a window OF ITS OWN, though both name
                      one frame — the reading a frame-keyed registry cannot
                      produce, taken by construction and not on a timer"
              (is (true? (roots/adopting? (:adoption ha)))
                  "root A is in flight — `hydrate-root!` returns before the
                   tree is adopted, so its window outlives the call")
              (is (true? (roots/adopting? (:adoption hb)))
                  "and so is root B")
              (is (not (identical? (:adoption ha) (:adoption hb)))
                  "two roots sharing a frame must not share a window"))

            (testing "so one root's closer shuts one root's window. This is
                      the page-global defect rf2-6tmu repaired, asked again at
                      frame granularity — where every existing row happens to
                      be immune because no two of its roots share a frame"
              (roots/close-adoption-window! (:adoption ha))
              (is (false? (roots/adopting? (:adoption ha))))
              (is (true? (roots/adopting? (:adoption hb)))
                  "root B must still be adopting after its frame-mate closed"))

            (-> (sup/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (try
                      (is (true? ok)
                          (str "both roots must commit; readers on the shared
                                key were "
                               (sup/readers-of [shared-frame label-q])))

                      (testing "and each root ADOPTED ITS OWN server DOM — the
                                nodes are the very nodes each container's
                                markup produced, which no re-render could
                                reconstruct, and sharing a frame did not make
                                either root adopt the other's tree"
                        (is (sup/every-server-node? ca ".screen, .title, .value")
                            "root A kept the server's nodes")
                        (is (sup/every-server-node? cb ".screen, .title, .value")
                            "root B kept the server's nodes"))

                      (testing "the two roots differ where their markup differs
                                and agree where their frame does"
                        (is (= "A" (text-in ca ".title")))
                        (is (= "B" (text-in cb ".title")))
                        (is (= "alpha" (text-in ca ".value")))
                        (is (= "alpha" (text-in cb ".value"))))

                      (testing "and the shared frame is one cell with two
                                readers here exactly as it is for two ordinary
                                mounts — adoption changes the acquisition's
                                timing and not its shape"
                        (is (= #{[shared-frame label-q]} (sup/cell-keys))
                            (str "got " (pr-str (sup/cell-keys))))
                        (is (= 2 (sup/readers-of [shared-frame label-q]))))

                      (finally (mount/release! ha) (mount/release! hb) (done))))))))))))

;; ---------------------------------------------------------------------------
;; S4 — two divergent roots on one frame complain twice
;; ---------------------------------------------------------------------------

;; H2 in the sibling suite asserts this across two frames. The fact is not
;; the same fact here, and the difference is the whole reason this file
;; exists: a window keyed by frame is per-root when the roots hold
;; different frames, so H2 stays green under it. Only when the frame is
;; SHARED does a frame-keyed window collapse two roots into one, and then
;; root A's closer silences root B's genuine mismatch — a diagnostic
;; missing from the instrumentation stream while the page's own error
;; channel still shows both, which is precisely how the original
;; page-global presented (PR #7751).
;;
;; So the row is read against React's own count rather than against a
;; literal, and then against the literal too. The equality catches a
;; diagnostic going missing; the absolute `2` catches the opposite repair,
;; a window never shut at all.
(deftest two-divergent-hydrating-roots-on-one-frame-complain-independently
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (sup/server-html! shared-frame [screen {:title "server-A"}])
              html-b (sup/server-html! shared-frame [screen {:title "server-B"}])
              ca     (sup/server-dom! html-a)
              cb     (sup/server-dom! html-b)]
          (collector/reset-runtime!)
          (let [{:keys [seen stop!]}      (sup/watch-mismatches!)
                ;; MANUFACTURED here and asserted on here — the only shape of
                ;; call site at which swallowing an uncaught error is not the
                ;; fail-open rf2-mwx08 forbids.
                {:keys [captured close!]} (sup/open-console-capture! {:swallow-uncaught? true})
                ha (mount/hydrate-root! ca shared-frame [screen {:title "client-A"}])
                hb (mount/hydrate-root! cb shared-frame [screen {:title "client-B"}])]
            (-> (sup/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (close!)
                    (stop!)
                    (try
                      (is (true? ok) "both roots must commit")

                      (let [react-complaints (filterv #(re-find #"Hydration failed" %)
                                                      @captured)]
                        (testing "both divergences were detected and reported —
                                  React saw two, and the reporter delegates
                                  unconditionally, so two reach the page"
                          (is (= 2 (count react-complaints))
                              (str "got "
                                   (pr-str (mapv #(subs % 0 (min 60 (count %)))
                                                 @captured)))))

                        (testing "and the framework's own stream carried exactly
                                  what the page did, THOUGH BOTH ROOTS NAME ONE
                                  FRAME. A window keyed by frame would have let
                                  root A's closer silence root B here while
                                  leaving every two-frame row green"
                          (is (= (count react-complaints) (count @seen))
                              (str "React said " (count react-complaints)
                                   ", the framework said " (count @seen)))
                          (is (= 2 (count @seen))
                              (str "`:rf.ssr/hydration-mismatch` count; got "
                                   (count @seen) " — "
                                   (pr-str (mapv (comp :error sup/tags-of) @seen))))))

                      (testing "and what fired is the framework diagnostic Spec
                                011 names, tier-discriminated by its door"
                        (doseq [ev @seen]
                          (let [tags (sup/tags-of ev)]
                            (is (= :rf.ssr/hydration-mismatch (:operation ev)))
                            (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                                   (:where tags)))
                            (is (= :warned-and-replaced (:recovery tags)))
                            (is (string? (:error tags))))))

                      (testing "and each root recovered to ITS OWN client model
                                — a shared frame means a shared value and it
                                does not mean a shared tree"
                        (is (= "client-A" (text-in ca ".title")))
                        (is (= "client-B" (text-in cb ".title")))
                        (is (= "alpha" (text-in ca ".value")))
                        (is (= "alpha" (text-in cb ".value"))))

                      (finally (mount/release! ha) (mount/release! hb) (done))))))))))))
