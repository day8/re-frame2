(ns re-frame.hicasso.roots-frames-hydration-dom-cljs-test
  "TWO OVERLAPPING HYDRATING ROOTS — independent adoption, independent
  complaints, independent presence, independent teardown (rf2-hic-012,
  rf2-6tmu).

  The property: two roots adopting server markup at the same time must
  not corrupt or silence one another. rf2-hic-012 wrote these rows and
  found the property FALSE — the hydration adoption window was one
  boolean for the whole page, so root A's closer shut the window root B
  was still adopting in, and root B's mismatch diagnostic was silently
  discarded. That worker was fenced off runtime source, so it measured
  the global rather than repairing it and left in-file instructions for
  the repair to follow.

  rf2-6tmu is that repair: one window per root, minted by
  `hydrate-root!`, reachable only from that root's handle, and carried to
  that root's closer, its reporter and its presence subtree. The rows
  below are the same rows with their measurements turned into
  properties — H2's two counts are now equal, and the row that existed
  only to record the global (`the-adoption-window-is-one-boolean-for-the-whole-page`)
  is deleted rather than re-pinned, exactly as its own comment
  instructed. H4 replaces it with the property it was standing in for,
  and H5 covers the window's second reader.

  ## Why adoption cannot be witnessed on the markup

  A hydrated root and a freshly rendered root produce the same
  `innerHTML`. That is not a coincidence — it is the contract. So a row
  that reads text to decide whether hydration worked is measuring
  nothing: React's own recovery path REPLACES a mismatched subtree and
  then renders the client's model into it, arriving at exactly the markup
  a working adoption would have produced. Value-level assertions stay
  green straight through a hydration that threw the server's DOM away.

  The observable that does discriminate is **node identity**, carried on
  a JS EXPANDO
  (`re-frame.hicasso.roots-frames-support/server-node-mark`). An expando
  cannot survive serialisation and cannot be reconstructed by a
  re-render, so a node still answering to it is the node the server
  markup produced. Adopted and re-created are indistinguishable in the
  markup and opposites here.

  The second observable is the framework diagnostic itself —
  `:rf.ssr/hydration-mismatch`, Spec 011, emitted by
  `impl.mount/hydration-reporter` — read off the live trace stream. A
  complaint that is never emitted leaves no trace in the DOM at all,
  because React has already repaired the DOM by the time the callback
  runs. Counting complaints is the only way to see one go missing.

  ## Provenance

  The technique is `re-frame.bench.hicasso.arm1.hydrate-dom-cljs-test`
  and `…arm1.hydrate-recoverable-dom-cljs-test`, reimplemented rather
  than imported — the freeze gate forbids the package from requiring the
  bench tree, and naming it in prose is provenance rather than a
  dependency. What is NEW here is that every row runs TWO roots at once;
  the prototype's rows all run one."
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

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)

(def ^:private label-q [::label])

;; Registered ABOVE `use-fixtures` — the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is evaluated, so a
;; registration written below it is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app
;; ---------------------------------------------------------------------------

(h/defview screen
  "One boundary, one prop and one subscription read.

  `title` is a PROP so a server/client divergence is a one-argument
  change at the call site rather than a second app; `value` is a
  subscription read so the boundary acquires a frame-keyed cell, which is
  what makes a root's commit observable at all."
  [{:keys [title]}]
  [:div.screen
   [:h1.title title]
   [:p.value (h/sub label-q)]])

;; ---------------------------------------------------------------------------
;; The presence app — H5's, and the observable that makes a PHASE readable
;; ---------------------------------------------------------------------------

(defonce ^:private !phases
  ;; `tag -> [phase …]`, every phase each probe was rendered with, in
  ;; order. Reset at the top of the row that reads it.
  (atom {}))

(h/defview phase-probe
  "A presence child that is a BOUNDARY, so the machine hands it its phase
  as the ordinary prop `:rf/phase` (`impl.presence/with-phase`) instead of
  merging attribute overrides into a node.

  **That prop is why this row has an observable at all.** A NATIVE
  presence child wears its phase as `::h/mounting` attributes, which React
  patches in place and which are gone a macrotask later when the enter
  flip lands — so a row that waited for a commit and then read the DOM
  could not tell a child that was BORN present from one that entered and
  settled. Both end as the same markup, which is the same trap node
  identity exists to escape elsewhere in this file. Recording the phase
  where the machine computes it records the FIRST one, and the first one
  is the entire question.

  Recorded as a sequence rather than a last-value, so a body that runs
  twice cannot turn a `:mounting` first render into a `:present` one."
  [{:keys [tag] :as props}]
  (let [phase (:rf/phase props)]
    (swap! !phases update tag (fnil conj []) phase)
    [:span.probe (name phase)]))

(h/defview tray-screen
  "A screen with a presence tray in it. Reads the label subscription as
  [[screen]] does, so this root acquires a frame-keyed cell and its commit
  is observable; the tray is what the row is about."
  [{:keys [tag]}]
  [:div.screen
   [:p.value (h/sub label-q)]
   [h/presence {:timeout-ms 50}
    [phase-probe {:key "one" :tag tag}]]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh! []
  (sup/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/with-frame frame-a (rf/dispatch-sync [::seed "alpha"]))
  (rf/with-frame frame-b (rf/dispatch-sync [::seed "beta"]))
  (collector/reset-runtime!)
  (collector/reset-body-runs!)
  nil)

(defn- both-committed?
  "Have both roots committed? A cell is acquired at commit and never
  before it, so a frame appearing in the cell table is that root's own
  completion signal — which the page-wide adoption window cannot be."
  []
  (= #{frame-a frame-b} (sup/cell-frames)))

(defn- text-in [container sel]
  (some-> (.querySelector container sel) .-textContent))

;; ---------------------------------------------------------------------------
;; H1 — two overlapping adoptions, each keeping its own DOM
;; ---------------------------------------------------------------------------

(deftest two-overlapping-hydrating-roots-each-adopt-their-own-server-dom
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (sup/server-html! frame-a [screen {:title "A"}])
              html-b (sup/server-html! frame-b [screen {:title "B"}])
              ca     (sup/stamp-server-nodes! (sup/server-dom! html-a))
              cb     (sup/stamp-server-nodes! (sup/server-dom! html-b))]
          (is (sup/every-server-node? ca ".screen, .title, .value")
              "premise: the stamps are on the server's own nodes")
          (is (re-find #"alpha" html-a) "premise: frame A's markup carries frame A's value")
          (is (re-find #"beta"  html-b) "premise: frame B's markup carries frame B's value")
          (collector/reset-runtime!)
          (let [ha (mount/hydrate-root! ca frame-a [screen {:title "A"}])
                hb (mount/hydrate-root! cb frame-b [screen {:title "B"}])]
            ;; The overlap is a CONSTRUCTION, not a timing guess: both
            ;; roots were handed to React before either had adopted, and
            ;; each root's OWN window being open on this line is what says
            ;; so. Two windows, because since rf2-6tmu there are two —
            ;; asserting one page-wide flag here would have been satisfied
            ;; by either root alone.
            (is (true? (roots/adopting? (:adoption ha)))
                "root A is in flight — `hydrate-root!` returns before the
                 tree is adopted, so its window outlives the call")
            (is (true? (roots/adopting? (:adoption hb)))
                "and so is root B, in its own window")
            (-> (sup/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (try
                      (is (true? ok)
                          (str "both roots must commit; cell frames were "
                               (pr-str (sup/cell-frames))))

                      (testing "each root ADOPTED its own server DOM — the nodes
                                are the very nodes the markup produced, which no
                                re-render could reconstruct"
                        (is (sup/every-server-node? ca ".screen, .title, .value")
                            "root A kept the server's nodes")
                        (is (sup/every-server-node? cb ".screen, .title, .value")
                            "root B kept the server's nodes"))

                      (testing "and neither root's adoption reached into the
                                other's tree"
                        (is (= "A" (text-in ca ".title")))
                        (is (= "B" (text-in cb ".title")))
                        (is (= "alpha" (text-in ca ".value")))
                        (is (= "beta"  (text-in cb ".value"))))

                      (testing "the cell table split by frame across the two
                                adoptions, exactly as it does across two ordinary
                                mounts"
                        (is (= #{[frame-a label-q] [frame-b label-q]} (sup/cell-keys)))
                        (is (= #{frame-a frame-b} (sup/dispatch-memo-frames))))

                      (finally (mount/release! ha) (mount/release! hb) (done))))))))))))

;; ---------------------------------------------------------------------------
;; H2 — two overlapping mismatches, two independent complaints
;; ---------------------------------------------------------------------------

;; ## THE INDEPENDENCE WITNESS — two divergences, two complaints EACH WAY
;; (rf2-hic-012 measured it; rf2-6tmu repaired it)
;;
;; The bead asked for two overlapping hydrating roots with *independent
;; mismatch complaints*. As shipped they were not independent, and the
;; version of this row that PR #7751 landed was the measurement:
;;
;;   - React reported BOTH divergences. Two roots diverge, and two
;;     "Hydration failed because…" errors reach the page's own error
;;     channel, because `impl.mount/report-recoverable-default!` delegates
;;     unconditionally.
;;   - Spec 011's `:rf.ssr/hydration-mismatch` fired ONCE. The emit was
;;     gated on a window that was one boolean for the whole page, and root
;;     A's closer shut it from a passive effect before root B's hydration
;;     commit reported. Root B's mismatch was invisible to every tool that
;;     reads the instrumentation stream.
;;
;; Those two counts together are what made it a finding about THIS ARM and
;; not about React: the divergence was detected and reported, and only the
;; framework's own diagnostic went missing.
;;
;; rf2-6tmu root-scoped the window — one per `hydrate-root!`, reachable
;; only from that root's handle, carried to that root's closer, reporter
;; and presence subtree — so the two counts are now EQUAL and this row
;; asserts that. **The row PR #7751 left carried in-file instructions to
;; replace both of its assertions with `(= 2 (count @seen))`, and that is
;; what happened**: the strict-inequality assertion became an equality
;; against the React count, and the `1` became `2`. Neither was re-pinned
;; and neither was deleted.
;;
;; Both halves are load-bearing and they fail in opposite directions. The
;; equality catches a diagnostic going missing again — the defect that was
;; here. The absolute `2` catches the opposite repair, a window that is
;; never shut at all, which would keep both counts equal while making
;; every later recoverable error on either root a "hydration mismatch";
;; H4 below is the row that separates those two.
;;
;; SABOTAGE (run by hand, PR body records it): mint ONE window in
;; `hydrate-root!` and hand it to both roots, and this row goes red on the
;; count — one root's closer shuts the other's window again.
(deftest two-overlapping-hydrating-roots-recover-and-complain-independently
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (sup/server-html! frame-a [screen {:title "server-A"}])
              html-b (sup/server-html! frame-b [screen {:title "server-B"}])
              ca     (sup/server-dom! html-a)
              cb     (sup/server-dom! html-b)]
          (collector/reset-runtime!)
          (let [{:keys [seen stop!]}      (sup/watch-mismatches!)
                ;; MANUFACTURED here and asserted on here — the only shape
                ;; of call site at which swallowing an uncaught error is
                ;; not the fail-open rf2-mwx08 forbids.
                {:keys [captured close!]} (sup/open-console-capture! {:swallow-uncaught? true})
                ha (mount/hydrate-root! ca frame-a [screen {:title "client-A"}])
                hb (mount/hydrate-root! cb frame-b [screen {:title "client-B"}])]
            (-> (sup/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (close!)
                    (stop!)
                    (try
                      (is (true? ok) "both roots must commit")

                      ;; What REACT complained about, on the page's own error
                      ;; channel — the control the row is read against.
                      (let [react-complaints (filterv #(re-find #"Hydration failed" %) @captured)]

                      (testing "BOTH divergences were detected and reported —
                                React saw two, and the reporter delegates
                                unconditionally, so two reach the page"
                        (is (= 2 (count react-complaints))
                            (str "two roots diverged, so two React complaints; got "
                                 (pr-str (mapv #(subs % 0 (min 60 (count %))) @captured)))))

                      (testing "and the framework's own stream carried exactly
                                what the page did. This is the rf2-6tmu repair:
                                each root's Spec 011 emit is gated on the window
                                THAT root minted, so no root's closer can shut
                                another root's window and no divergence React
                                reported goes missing"
                        (is (= (count react-complaints) (count @seen))
                            (str "every divergence React reported reached the
                                  instrumentation stream; React said "
                                 (count react-complaints) ", the framework said "
                                 (count @seen)))
                        (is (= 2 (count @seen))
                            (str "`:rf.ssr/hydration-mismatch` count; got "
                                 (count @seen) " — "
                                 (pr-str (mapv (comp :error sup/tags-of) @seen)))))

                      (testing "and what did fire is the framework diagnostic
                                Spec 011 names, tier-discriminated by its door"
                        (doseq [ev @seen]
                          (let [tags (sup/tags-of ev)]
                            (is (= :rf.ssr/hydration-mismatch (:operation ev)))
                            (is (= 're-frame.hicasso.impl.mount/hydrate-root! (:where tags)))
                            (is (= :warned-and-replaced (:recovery tags)))
                            (is (string? (:error tags))))))

                      (testing "RECOVERY, unlike complaint, IS independent: each
                                root recovered to its OWN client model, not to
                                its sibling's"
                        (is (= "client-A" (text-in ca ".title")))
                        (is (= "client-B" (text-in cb ".title")))
                        (is (= "alpha" (text-in ca ".value")))
                        (is (= "beta"  (text-in cb ".value")))))

                      (finally (mount/release! ha) (mount/release! hb) (done))))))))))))

;; ---------------------------------------------------------------------------
;; H3 — independent teardown of two hydrated roots
;; ---------------------------------------------------------------------------

(deftest tearing-down-one-hydrated-root-leaves-the-other-adopted-and-live
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (sup/server-html! frame-a [screen {:title "A"}])
              html-b (sup/server-html! frame-b [screen {:title "B"}])
              ca     (sup/server-dom! html-a)
              cb     (sup/stamp-server-nodes! (sup/server-dom! html-b))]
          (collector/reset-runtime!)
          (let [ha (mount/hydrate-root! ca frame-a [screen {:title "A"}])
                hb (mount/hydrate-root! cb frame-b [screen {:title "B"}])]
            (-> (sup/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (is (true? ok) "both roots must commit")
                    ;; `unmount!`, not `release!` — `release!` resets the
                    ;; runtime by fiat, and every count below would then
                    ;; read zero whatever the teardown did.
                    (mount/unmount! ha)
                    (-> (sup/quiesced!)
                        (.then
                          (fn [_]
                            (try
                              (testing "frame A's keys are released and frame B's
                                        survive"
                                (is (= #{[frame-b label-q]} (sup/cell-keys))
                                    (str "got " (pr-str (sup/cell-keys)))))

                              (testing "and root B is still ADOPTED — its nodes are
                                        still the server's, so the sibling's
                                        teardown did not force it through a
                                        re-render"
                                (is (sup/every-server-node? cb ".screen, .title, .value")))

                              (testing "and still live: a render into the surviving
                                        root re-runs its body and keeps its own
                                        frame's value"
                                (let [ran (sup/body-runs-delta!
                                            (fn [] (mount/render! hb [screen {:title "B2"}])))]
                                  (is (= 1 ran)))
                                (is (= "B2" (text-in cb ".title")))
                                (is (= "beta" (text-in cb ".value"))))

                              (testing "and tearing the survivor down leaves nothing"
                                (is (= sup/released (sup/teardown-census! hb))))
                              (finally
                                (mount/release! ha)
                                (mount/release! hb)
                                (done)))))))))))))))

;; ---------------------------------------------------------------------------
;; H4 — a COMPLETED root's later recovery is not a mismatch, however many
;;      siblings are still adopting. THE ROW THAT RULES OUT A COUNTER.
;; ---------------------------------------------------------------------------

;; The row that stood here measured the page-global — two opens shut by one
;; close — and carried an in-file instruction to be DELETED rather than
;; re-pinned once the window became root-scoped (rf2-hic-012 → rf2-6tmu).
;; It is deleted. What follows is not a re-pin of it: it is the property
;; that measurement was standing in for, and it discriminates against a
;; strictly larger set of wrong answers.
;;
;; **H2 alone would pass under a page-global REFERENCE COUNT.** Two opens,
;; one close, count still one — both roots' mismatches emit and the two
;; numbers match. What a count cannot do is tell root A's window from root
;; B's, and React makes that difference matter: it holds
;; `onRecoverableError` for a root's WHOLE LIFETIME and fires it for
;; post-hydration recoveries too, a concurrent render it retried. Once A has
;; committed, an error arriving on A is not a hydration mismatch. Under a
;; count it would be labelled one for as long as B kept the page-wide window
;; open — one interference bug traded for another.
;;
;; So this row completes A, leaves B adopting, and sends one error to each:
;;
;;   | root | its window        | the error it gets         | must emit |
;;   |---|---|---|---|
;;   | A    | committed, closed | a later recoverable error | NO        |
;;   | B    | still adopting    | its hydration mismatch    | YES       |
;;
;; Three shapes fail it and only root-scoping passes. A page-global BOOLEAN
;; emits nothing, because A's close shut B's window too. A page-global
;; COUNT emits twice, because A's later error arrives inside a page window B
;; is still holding open. One shared ref does whichever of those its last
;; write said.
;;
;; The doors are called DIRECTLY — `open-adoption-window!`, the real
;; `hydration-reporter` builder over the real window, `close-adoption-window!`
;; — which is exactly what `hydrate-root!` does per root with React's
;; schedule removed. That is deliberate rather than a shortcut: the two
;; orderings this row separates are orderings React does not let a caller
;; choose, so a version that waited for them would be measuring the
;; scheduler and would go green or red on timing. It is the same technique
;; the deleted row used, turned on the property instead of on the defect.
(deftest a-completed-roots-later-recovery-is-not-a-mismatch-while-a-sibling-adopts
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [{:keys [seen stop!]}      (sup/watch-mismatches!)
            ;; MANUFACTURED here and asserted on here — the only shape of
            ;; call site at which swallowing an uncaught error is not the
            ;; fail-open rf2-mwx08 forbids.
            {:keys [captured close!]} (sup/open-console-capture! {:swallow-uncaught? true})
            window-a (roots/open-adoption-window!)
            window-b (roots/open-adoption-window!)
            report-a (mount/hydration-reporter window-a)
            report-b (mount/hydration-reporter window-b)
            later-a  (js/Error. "a concurrent render root A recovered from")
            genuine-b (js/Error. "Hydration failed on root B")]
        (try
          (is (true? (roots/adopting? window-a)) "premise: root A is adopting")
          (is (true? (roots/adopting? window-b)) "premise: root B is adopting")

          ;; Root A's hydration commits. Its closer shuts ITS window.
          (roots/close-adoption-window! window-a)

          (testing "one root's closer shuts one root's window"
            (is (false? (roots/adopting? window-a)) "root A has completed")
            (is (true? (roots/adopting? window-b))
                "and root B is STILL ADOPTING — this single pair of readings
                 is the whole repair, and it is what the deleted row
                 measured going the other way"))

          (testing "a LATER recoverable error on the completed root A is not a
                    hydration mismatch, however many siblings are adopting —
                    the assertion a page-global reference count fails"
            (report-a later-a nil)
            (is (= 0 (count @seen))
                (str "nothing should have been emitted; got "
                     (pr-str (mapv (comp :error sup/tags-of) @seen)))))

          (testing "while root B's genuine mismatch, arriving while B is still
                    adopting, DOES emit — the assertion a page-global boolean
                    fails"
            (report-b genuine-b nil)
            (is (= 1 (count @seen)) "exactly one diagnostic")
            (is (= "Hydration failed on root B"
                   (:error (sup/tags-of (first @seen))))
                "and it is B's error, not A's"))

          (testing "IN-ROW DISCRIMINATION: the same later error on A, read
                    against a window some OTHER root is still holding open, is
                    mislabelled a mismatch. So the zero above is a property of
                    the scoping and not of the error"
            ((mount/hydration-reporter window-b) later-a nil)
            (is (= 2 (count @seen))
                "a page-wide window still open elsewhere would have labelled
                 A's later recovery a hydration mismatch")
            (is (= "a concurrent render root A recovered from"
                   (:error (sup/tags-of (last @seen))))))

          (testing "and rf2-mwx08's fail-open is untouched in every case: the
                    reporter ALWAYS delegates, emit or no emit"
            (is (= 3 (count (filterv #(re-find #"root A|root B" %) @captured)))
                (str "all three errors reached the page's own error channel: "
                     (pr-str @captured))))
          (finally
            (close!)
            (stop!)
            (roots/close-adoption-window! window-b)))))))

;; ---------------------------------------------------------------------------
;; H5 — presence isolation: adoption belongs to a SUBTREE, not to the page
;; ---------------------------------------------------------------------------

;; The mismatch diagnostic was the LOUD half of the page-global. This is the
;; quiet half, and it is the one an application would have felt: presence is
;; a second reader of the same window, and it reads it during a RENDER.
;;
;; While ANY root hydrated, the window answered true for every presence tray
;; on the page — including one in an ORDINARY root that had nothing to do
;; with the hydration and was not adopting anything. Such a tray was told its
;; children were already on the screen, so it started them `:present` and
;; skipped the enter transition its author wrote. Nothing complained; the
;; animation simply did not play, and only when a sibling root happened to be
;; hydrating.
;;
;; The construction makes that overlap a FACT rather than a race, the way H1
;; does: `hydrate-root!` returns before its tree is adopted, so root A's
;; window is provably open on the line where root B mounts, and `root!`
;; commits inside a `flushSync` so root B's first render is readable on the
;; line after it. No timer stands between the two.
;;
;; SABOTAGE (run by hand, PR body records it): point `impl.presence-react`
;; back at a page-wide window and the ordinary root's first phase becomes
;; `:present` — this row reds on that assertion, which is the assertion the
;; shipped code failed.
(deftest presence-adoption-belongs-to-a-subtree-not-to-the-page
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (reset! !phases {})
        (-> (sup/settled-server-html!
              frame-a [tray-screen {:tag :hydrated}]
              (fn [c] (= "present" (text-in c ".probe"))))
            (.then
              (fn [html]
                (is (re-find #"present" html)
                    (str "premise: the server's bytes show the child already
                          PRESENT — " html))
                ;; The server render's own phases are not this row's subject.
                (reset! !phases {})
                (collector/reset-runtime!)
                (let [ca (sup/stamp-server-nodes! (sup/server-dom! html))
                      cb (mount/fresh-container!)
                      {:keys [seen stop!]} (sup/watch-mismatches!)
                      ha (mount/hydrate-root! ca frame-a [tray-screen {:tag :hydrated}])]
                  (is (true? (roots/adopting? (:adoption ha)))
                      "premise: root A is adopting on THIS line, so what follows
                       happens inside its window by construction")
                  ;; An ORDINARY root, mounted inside root A's adoption window.
                  (let [hb (mount/root! cb frame-b [tray-screen {:tag :ordinary}])]
                    (testing "the ordinary sibling gets its ENTER phase, even
                              though a hydration is in flight elsewhere on the
                              page — the row the shipped page-global failed"
                      (is (= :mounting (first (get @!phases :ordinary)))
                          (str "root B's first render must see :mounting; saw "
                               (pr-str (get @!phases :ordinary))))
                      (is (= "mounting" (text-in cb ".probe"))
                          "and it reached the DOM that way"))

                    (testing "an ordinary root has no adoption window at all —
                              nil, which `adopting?` reads as closed. That is
                              what makes the repair free for it: no object, no
                              provider, no branch"
                      (is (nil? (:adoption hb)))
                      (is (false? (roots/adopting? (:adoption hb)))))

                    (-> (sup/adopted! ha)
                        (.then
                          (fn [ok]
                            (stop!)
                            (try
                              (is (true? ok) "root A's own closer ran")

                              (testing "root A was BORN PRESENT: its tray never
                                        rendered a :mounting phase at all, so no
                                        enter transition replayed over DOM the
                                        user already watched arrive"
                                (is (= :present (first (get @!phases :hydrated)))
                                    (str "root A's first render; saw "
                                         (pr-str (get @!phases :hydrated))))
                                (is (not (contains? (set (get @!phases :hydrated))
                                                    :mounting))
                                    "and no later render entered one either"))

                              (testing "so the client's first pass AGREED with the
                                        server's bytes — the adopted nodes are the
                                        server's own, and nothing diverged"
                                (is (sup/every-server-node? ca ".screen, .probe"))
                                (is (= 0 (count @seen))
                                    (str "no hydration mismatch; got "
                                         (pr-str (mapv (comp :error sup/tags-of)
                                                       @seen)))))

                              (testing "closing root A's window changed nothing
                                        about root B: B still completed its own
                                        enter transition, on its own clock"
                                (is (false? (roots/adopting? (:adoption ha))))
                                (is (= [:mounting :present]
                                       (vec (distinct (get @!phases :ordinary))))
                                    (str "root B entered and then settled; saw "
                                         (pr-str (get @!phases :ordinary))))
                                (is (= "present" (text-in cb ".probe"))))

                              (testing "and TEARDOWN BEFORE THE PASSIVE EFFECT
                                        leaves no open window behind. A root
                                        unmounted before its hydration commit
                                        never gets its closer, so `unmount!`
                                        owns the shut — driven here on a handle
                                        with no root, which is what a closer
                                        that never ran leaves behind (the
                                        `:root nil` idiom `teardown-census!`
                                        uses), rather than on a live root whose
                                        mid-hydration teardown would be
                                        measuring React's unmount instead"
                                (let [window (roots/open-adoption-window!)
                                      orphan {:frame     frame-a
                                              :container (mount/fresh-container!)
                                              :root      nil
                                              :adoption  window}]
                                  (is (true? (roots/adopting? window))
                                      "premise: open, and its closer has not run")
                                  (mount/unmount! orphan)
                                  (is (false? (roots/adopting? window))
                                      "teardown shut it")))

                              (finally
                                (mount/release! ha)
                                (mount/release! hb)
                                (done)))))))))))))))
