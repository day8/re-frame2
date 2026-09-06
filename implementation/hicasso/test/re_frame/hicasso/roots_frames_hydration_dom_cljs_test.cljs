(ns re-frame.hicasso.roots-frames-hydration-dom-cljs-test
  "TWO OVERLAPPING HYDRATING ROOTS — independent adoption, independent
  complaints, independent presence, independent teardown.

  The property: two roots adopting server markup at the same time must
  not corrupt or silence one another. A page-global adoption window makes
  it FALSE — one boolean for the whole page means root A's closer shuts
  the window root B is still adopting in, and root B's mismatch
  diagnostic is silently discarded.

  The window is therefore PER ROOT: minted by `hydrate-root!`, reachable
  only from that root's handle, and carried to that root's closer, its
  reporter and its presence subtree. The rows below are the properties
  that arrangement buys: H2's two counts are equal, H4 carries the
  property a page-global measurement could only stand in for, and H5
  covers the window's second reader.

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
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.impl.roots :as rf.hicasso.impl.roots]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.motion :as rf.hicasso.motion]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)

(def ^:private label-q [::label])

;; Registered ABOVE `use-fixtures` — the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is evaluated, so a
;; registration written below it is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app
;; ---------------------------------------------------------------------------

(rf.hicasso/defview screen
  "One boundary, one prop and one subscription read.

  `title` is a PROP so a server/client divergence is a one-argument
  change at the call site rather than a second app; `value` is a
  subscription read so the boundary acquires a frame-keyed cell, which is
  what makes a root's commit observable at all."
  [{:keys [title]}]
  [:div.screen
   [:h1.title title]
   [:p.value (rf.hicasso/sub label-q)]])

;; ---------------------------------------------------------------------------
;; The presence app — H5's, and the observable that makes a PHASE readable
;; ---------------------------------------------------------------------------

(defonce ^:private !phases
  ;; `tag -> [phase …]`, every phase each probe was rendered with, in
  ;; order. Reset at the top of the row that reads it.
  (atom {}))

(rf.hicasso/defview phase-probe
  "A presence child that is a BOUNDARY, so the machine merges the phase's
  override map into its PROPS (`impl.presence/with-phase`, HD-030)
  instead of into a node's attributes. The tray below declares
  `{:phase …}` under each override key, so this body reads the phase
  back as an ordinary prop, defaulting to `:present` — the phase with no
  override.

  **That prop is why this row has an observable at all.** A NATIVE
  presence child wears its phase as `::motion/mounting` attributes, which React
  patches in place and which are gone a macrotask later when the enter
  flip lands — so a row that waited for a commit and then read the DOM
  could not tell a child that was BORN present from one that entered and
  settled. Both end as the same markup, which is the same trap node
  identity exists to escape elsewhere in this file. Recording the phase
  where the machine computes it records the FIRST one, and the first one
  is the entire question.

  Recorded as a sequence rather than a last-value, so a body that runs
  twice cannot turn a `:mounting` first render into a `:present` one."
  [{:keys [tag phase] :or {phase :present}}]
  (swap! !phases update tag (fnil conj []) phase)
  [:span.probe (name phase)])

(rf.hicasso/defview tray-screen
  "A screen with a presence tray in it. Reads the label subscription as
  [[screen]] does, so this root acquires a frame-keyed cell and its commit
  is observable; the tray is what the row is about."
  [{:keys [tag]}]
  [:div.screen
   [:p.value (rf.hicasso/sub label-q)]
   [rf.hicasso.motion/presence {:timeout-ms 50}
    [phase-probe {:key                 "one"
                  :tag                 tag
                  ::rf.hicasso.motion/mounting    {:phase :mounting}
                  ::rf.hicasso.motion/unmounting  {:phase :unmounting}}]]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh! []
  (rf.hicasso.roots-frames-support/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/with-frame frame-a (rf/dispatch-sync [::seed "alpha"]))
  (rf/with-frame frame-b (rf/dispatch-sync [::seed "beta"]))
  (rf.hicasso.impl.collector/reset-runtime!)
  (rf.hicasso.test.runtime/reset-body-runs!)
  nil)

(defn- both-committed?
  "Have both roots committed? A cell is acquired at commit and never
  before it, so a frame appearing in the cell table is that root's own
  completion signal — which the page-wide adoption window cannot be."
  []
  (= #{frame-a frame-b} (rf.hicasso.roots-frames-support/cell-frames)))

(defn- text-in [container sel]
  (some-> (.querySelector container sel) .-textContent))

;; ---------------------------------------------------------------------------
;; Settlement — the ONE way out of an async row, taken on both outcomes
;; ---------------------------------------------------------------------------
;;
;; Every async row below mounts real roots, and each holds things the
;; `:each` fixture will NOT take back: MOUNTED React roots, with the
;; containers `sup/server-dom!` and `mount/fresh-container!` minted still
;; in the document. The fixture resets frames, disposes the adapter and
;; empties the runtime, and none of that unmounts a root. H2 also replaces
;; `console.error` and registers a `window` "error" listener, and it opens
;; that capture with `:swallow-uncaught? true` — so a listener that
;; outlives its row goes on calling `preventDefault` on every later row's
;; uncaught errors, which is precisely the fail-open the browser runner's
;; pageerror rule exists to prevent.
;;
;; These rows used to end INSIDE the fulfilment handler:
;;
;;   (-> (sup/wait-until! both-committed?)
;;       (.then (fn [ok] (try …assertions…
;;                            (finally (mount/release! ha)
;;                                     (mount/release! hb)
;;                                     (done))))))
;;
;; There was no rejection arm anywhere in this file, so on a rejection the
;; handler was skipped, the `try` was never entered and the `finally` never
;; fired. Nothing ran: no `release!`, no `done`. The row did not fail — it
;; HUNG to `cljs.test`'s async timeout, reporting the timeout rather than
;; the rejection, and it handed the next row live roots to take its census
;; against. H6 is the expensive one: `js/Promise.all` rejects if EITHER
;; adoption does, so one rejection stranded FOUR handles.
;;
;; On THIS lane it is worse than a hang, which is worth knowing before
;; reading H7's sabotage as merely slow. An unsettled rejection is an
;; unhandled one, so it reaches the page as an uncaught error, and the
;; browser runner treats that as terminal (rf2-u0j8). Measured on the
;; sibling suite: the run stopped at that namespace with 85 announced, no
;; summary line at all, and every namespace scheduled after it silently
;; unrun — `shadow.test` runs the whole lane, and the closing summary,
;; inside one `cljs.test/run-block` with no try/catch. So the cost of a
;; rejection here was never one row.
;;
;; `sup/settle-row!` is the one path every async row now ends with, and H7 is
;; what says it works — because its rejection arm is on no green path, and
;; a repair to a branch nothing takes is untested by construction.
;;
;; H4 is deliberately NOT on it: that row is synchronous end to end — no
;; `async`, no promise — so its `try`/`finally` is an ordinary bracket and
;; there is no settlement question to answer.

;; ---------------------------------------------------------------------------
;; H1 — two overlapping adoptions, each keeping its own DOM
;; ---------------------------------------------------------------------------

(deftest two-overlapping-hydrating-roots-each-adopt-their-own-server-dom
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (rf.hicasso.roots-frames-support/server-html! frame-a [screen {:title "A"}])
              html-b (rf.hicasso.roots-frames-support/server-html! frame-b [screen {:title "B"}])
              ca     (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html-a))
              cb     (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html-b))]
          (is (rf.hicasso.roots-frames-support/every-server-node? ca ".screen, .title, .value")
              "premise: the stamps are on the server's own nodes")
          (is (re-find #"alpha" html-a) "premise: frame A's markup carries frame A's value")
          (is (re-find #"beta"  html-b) "premise: frame B's markup carries frame B's value")
          (rf.hicasso.impl.collector/reset-runtime!)
          (let [ha (rf.hicasso.impl.mount/hydrate-root! ca frame-a [screen {:title "A"}])
                hb (rf.hicasso.impl.mount/hydrate-root! cb frame-b [screen {:title "B"}])]
            ;; The overlap is a CONSTRUCTION, not a timing guess: both
            ;; roots were handed to React before either had adopted, and
            ;; each root's OWN window being open on this line is what says
            ;; so. Two windows, because the window is per root —
            ;; asserting one page-wide flag here would be satisfied by
            ;; either root alone.
            (is (true? (rf.hicasso.impl.roots/adopting? (:adoption ha)))
                "root A is in flight — `hydrate-root!` returns before the
                 tree is adopted, so its window outlives the call")
            (is (true? (rf.hicasso.impl.roots/adopting? (:adoption hb)))
                "and so is root B, in its own window")
            (-> (rf.hicasso.roots-frames-support/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (is (true? ok)
                        (str "both roots must commit; cell frames were "
                             (pr-str (rf.hicasso.roots-frames-support/cell-frames))))

                    (testing "each root ADOPTED its own server DOM — the nodes
                              are the very nodes the markup produced, which no
                              re-render could reconstruct"
                      (is (rf.hicasso.roots-frames-support/every-server-node? ca ".screen, .title, .value")
                          "root A kept the server's nodes")
                      (is (rf.hicasso.roots-frames-support/every-server-node? cb ".screen, .title, .value")
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
                      (is (= #{[frame-a label-q] [frame-b label-q]} (rf.hicasso.roots-frames-support/cell-keys)))
                      (is (= #{frame-a frame-b} (rf.hicasso.roots-frames-support/frame-memo-frames))))))
                (rf.hicasso.roots-frames-support/settle-row!
                  {:row      "H1 — two overlapping adoptions, each keeping its own DOM"
                   :done     done
                   :release! (fn []
                               (rf.hicasso.impl.mount/release! ha)
                               (rf.hicasso.impl.mount/release! hb))}))))))))

;; ---------------------------------------------------------------------------
;; H2 — two overlapping mismatches, two independent complaints
;; ---------------------------------------------------------------------------

;; ## THE INDEPENDENCE WITNESS — two divergences, two complaints EACH WAY
;;
;; What is owed is two overlapping hydrating roots with *independent
;; mismatch complaints*. Under a page-global window they are not
;; independent, and the measurement of that is:
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
;; The window is root-scoped — one per `hydrate-root!`, reachable only
;; from that root's handle, carried to that root's closer, reporter and
;; presence subtree — so the two counts are EQUAL and this row asserts
;; that: the strict-inequality assertion is an equality
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
;; SABOTAGE, and why it does not execute HERE. The mutation is
;; "mint ONE window in `hydrate-root!` and hand it to both roots": one root's
;; closer then shuts the other's window and this row reds on the count. It is
;; armable — `sup/with-page-global-adoption` IS that mutation, executing — but
;; not against this row, and the reason is the one H4 below gives for driving
;; the doors directly: whether the count collapses depends on the relative
;; ordering of root A's closer and root B's recoverable-error callback, and
;; React does not let a caller choose that ordering. An executing form of THIS
;; row would go green or red on the scheduler, which is a worse control than
;; none. The hand run against this row's count is PR #7756, landed on main as
;; commit `fdbf5d6907` — a pointer that can be followed without the GitHub UI,
;; which "the PR body records it" could not. The family's executing control is
;; [[a-page-global-adoption-window-steals-an-ordinary-roots-enter-transition]]
;; below, which arms the same mutation where the readings are taken by
;; construction rather than on a schedule.
(deftest two-overlapping-hydrating-roots-recover-and-complain-independently
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (rf.hicasso.roots-frames-support/server-html! frame-a [screen {:title "server-A"}])
              html-b (rf.hicasso.roots-frames-support/server-html! frame-b [screen {:title "server-B"}])
              ca     (rf.hicasso.roots-frames-support/server-dom! html-a)
              cb     (rf.hicasso.roots-frames-support/server-dom! html-b)]
          (rf.hicasso.impl.collector/reset-runtime!)
          (let [{:keys [seen stop!]}      (rf.hicasso.roots-frames-support/watch-mismatches!)
                ;; MANUFACTURED here and asserted on here — the only shape
                ;; of call site at which swallowing an uncaught error is
                ;; not the fail-open the pageerror rule forbids.
                {:keys [captured close!]} (rf.hicasso.roots-frames-support/open-console-capture! {:swallow-uncaught? true})
                ha (rf.hicasso.impl.mount/hydrate-root! ca frame-a [screen {:title "client-A"}])
                hb (rf.hicasso.impl.mount/hydrate-root! cb frame-b [screen {:title "client-B"}])]
            (-> (rf.hicasso.roots-frames-support/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    ;; Closed and stopped HERE, and named in `:release!` as
                    ;; well. Here, because the assertions below read
                    ;; `@captured` and `@seen` and the capture has to be shut
                    ;; across the render rather than across the rest of the
                    ;; row; there, because both are idempotent — `close!` on a
                    ;; `closed?` volatile, `stop!` by its own docstring — and
                    ;; the rejection path never reaches this line. Leaving it
                    ;; here alone is what let a `:swallow-uncaught?` listener
                    ;; outlive the row.
                    (close!)
                    (stop!)
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
                               (pr-str (mapv (comp :error rf.hicasso.roots-frames-support/tags-of) @seen)))))

                    (testing "and what did fire is the framework diagnostic
                              Spec 011 names, tier-discriminated by its door"
                      (doseq [ev @seen]
                        (let [tags (rf.hicasso.roots-frames-support/tags-of ev)]
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
                      (is (= "beta"  (text-in cb ".value")))))))
                (rf.hicasso.roots-frames-support/settle-row!
                  {:row      "H2 — two overlapping mismatches, two complaints"
                   :done     done
                   :release! (fn []
                               (close!)
                               (stop!)
                               (rf.hicasso.impl.mount/release! ha)
                               (rf.hicasso.impl.mount/release! hb))}))))))))

;; ---------------------------------------------------------------------------
;; H3 — independent teardown of two hydrated roots
;; ---------------------------------------------------------------------------

(deftest tearing-down-one-hydrated-root-leaves-the-other-adopted-and-live
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html-a (rf.hicasso.roots-frames-support/server-html! frame-a [screen {:title "A"}])
              html-b (rf.hicasso.roots-frames-support/server-html! frame-b [screen {:title "B"}])
              ca     (rf.hicasso.roots-frames-support/server-dom! html-a)
              cb     (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html-b))]
          (rf.hicasso.impl.collector/reset-runtime!)
          (let [ha (rf.hicasso.impl.mount/hydrate-root! ca frame-a [screen {:title "A"}])
                hb (rf.hicasso.impl.mount/hydrate-root! cb frame-b [screen {:title "B"}])]
            (-> (rf.hicasso.roots-frames-support/wait-until! both-committed?)
                (.then
                  (fn [ok]
                    (is (true? ok) "both roots must commit")
                    ;; `unmount!`, not `release!` — `release!` resets the
                    ;; runtime by fiat, and every count below would then
                    ;; read zero whatever the teardown did.
                    (rf.hicasso.impl.mount/unmount! ha)
                    ;; The inner chain is RETURNED from this handler, so the
                    ;; outer promise adopts it and ONE `sup/settle-row!` at the
                    ;; outer tail settles the whole row. Two — one per chain —
                    ;; would call `done` twice on the green path.
                    (-> (rf.hicasso.roots-frames-support/quiesced!)
                        (.then
                          (fn [_]
                            (testing "frame A's keys are released and frame B's
                                      survive"
                              (is (= #{[frame-b label-q]} (rf.hicasso.roots-frames-support/cell-keys))
                                  (str "got " (pr-str (rf.hicasso.roots-frames-support/cell-keys)))))

                            (testing "and root B is still ADOPTED — its nodes are
                                      still the server's, so the sibling's
                                      teardown did not force it through a
                                      re-render"
                              (is (rf.hicasso.roots-frames-support/every-server-node? cb ".screen, .title, .value")))

                            (testing "and still live: a render into the surviving
                                      root re-runs its body and keeps its own
                                      frame's value"
                              (let [ran (rf.hicasso.roots-frames-support/body-runs-delta!
                                          (fn [] (rf.hicasso.impl.mount/render! hb [screen {:title "B2"}])))]
                                (is (= 1 ran)))
                              (is (= "B2" (text-in cb ".title")))
                              (is (= "beta" (text-in cb ".value"))))

                            ;; THE DISCRIMINATING HALF, and the reason the two
                            ;; assertions above are not yet a witness of
                            ;; `mount/tree`'s hydrated-root shape: a `render!`
                            ;; that REMOUNTED the adopted tree — the measured
                            ;; failure `mount/tree`'s docstring records, a bare
                            ;; provider handed to a root that adopted under the
                            ;; Fragment-wrapped closer — would also run the body
                            ;; once and also paint "B2"/"beta". Node identity
                            ;; and the memo bail-out are what a remount cannot
                            ;; fake. (The technique is the fenced
                            ;; `arm1/hydrate-dom-cljs-test`'s §5 rider, restated
                            ;; against the shipped door — provenance, not a
                            ;; dependency.)
                            (testing "and still ADOPTED through that render: a
                                      props-equal render! bails at the memo with
                                      zero body runs, and every node is still the
                                      SERVER's — neither render remounted the
                                      tree the adoption established"
                              (let [ran (rf.hicasso.roots-frames-support/body-runs-delta!
                                          (fn [] (rf.hicasso.impl.mount/render! hb [screen {:title "B2"}])))]
                                (is (zero? ran)
                                    (str "a props-equal render! after adoption "
                                         "ran no body; read " ran)))
                              (is (rf.hicasso.roots-frames-support/every-server-node? cb ".screen, .title, .value")
                                  "and the surviving root's nodes still answer to
                                   the server-node mark — a render! that handed
                                   this root a bare provider where its Fragment
                                   wrapper stood would have replaced every one of
                                   them"))

                            (testing "and tearing the survivor down leaves nothing"
                              (is (= rf.hicasso.roots-frames-support/released (rf.hicasso.roots-frames-support/teardown-census! hb)))))))))
                (rf.hicasso.roots-frames-support/settle-row!
                  {:row      "H3 — independent teardown of two hydrated roots"
                   :done     done
                   :release! (fn []
                               (rf.hicasso.impl.mount/release! ha)
                               (rf.hicasso.impl.mount/release! hb))}))))))))

;; ---------------------------------------------------------------------------
;; H4 — a COMPLETED root's later recovery is not a mismatch, however many
;;      siblings are still adopting. THE ROW THAT RULES OUT A COUNTER.
;; ---------------------------------------------------------------------------

;; A row measuring the page-global — two opens shut by one close — is NOT
;; what stands here. What follows is the property such a measurement stands
;; in for, and it discriminates against a strictly larger set of wrong
;; answers.
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
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [{:keys [seen stop!]}      (rf.hicasso.roots-frames-support/watch-mismatches!)
            ;; MANUFACTURED here and asserted on here — the only shape of
            ;; call site at which swallowing an uncaught error is not the
            ;; fail-open the pageerror rule forbids.
            {:keys [captured close!]} (rf.hicasso.roots-frames-support/open-console-capture! {:swallow-uncaught? true})
            window-a (rf.hicasso.impl.roots/open-adoption-window!)
            window-b (rf.hicasso.impl.roots/open-adoption-window!)
            report-a (rf.hicasso.impl.mount/hydration-reporter window-a)
            report-b (rf.hicasso.impl.mount/hydration-reporter window-b)
            later-a  (js/Error. "a concurrent render root A recovered from")
            genuine-b (js/Error. "Hydration failed on root B")]
        (try
          (is (true? (rf.hicasso.impl.roots/adopting? window-a)) "premise: root A is adopting")
          (is (true? (rf.hicasso.impl.roots/adopting? window-b)) "premise: root B is adopting")

          ;; Root A's hydration commits. Its closer shuts ITS window.
          (rf.hicasso.impl.roots/close-adoption-window! window-a)

          (testing "one root's closer shuts one root's window"
            (is (false? (rf.hicasso.impl.roots/adopting? window-a)) "root A has completed")
            (is (true? (rf.hicasso.impl.roots/adopting? window-b))
                "and root B is STILL ADOPTING — this single pair of readings
                 is the whole repair, and it is what the deleted row
                 measured going the other way"))

          (testing "a LATER recoverable error on the completed root A is not a
                    hydration mismatch, however many siblings are adopting —
                    the assertion a page-global reference count fails"
            (report-a later-a nil)
            (is (= 0 (count @seen))
                (str "nothing should have been emitted; got "
                     (pr-str (mapv (comp :error rf.hicasso.roots-frames-support/tags-of) @seen)))))

          (testing "while root B's genuine mismatch, arriving while B is still
                    adopting, DOES emit — the assertion a page-global boolean
                    fails"
            (report-b genuine-b nil)
            (is (= 1 (count @seen)) "exactly one diagnostic")
            (is (= "Hydration failed on root B"
                   (:error (rf.hicasso.roots-frames-support/tags-of (first @seen))))
                "and it is B's error, not A's"))

          (testing "IN-ROW DISCRIMINATION: the same later error on A, read
                    against a window some OTHER root is still holding open, is
                    mislabelled a mismatch. So the zero above is a property of
                    the scoping and not of the error"
            ((rf.hicasso.impl.mount/hydration-reporter window-b) later-a nil)
            (is (= 2 (count @seen))
                "a page-wide window still open elsewhere would have labelled
                 A's later recovery a hydration mismatch")
            (is (= "a concurrent render root A recovered from"
                   (:error (rf.hicasso.roots-frames-support/tags-of (last @seen))))))

          (testing "and rf2-mwx08's fail-open is untouched in every case: the
                    reporter ALWAYS delegates, emit or no emit"
            (is (= 3 (count (filterv #(re-find #"root A|root B" %) @captured)))
                (str "all three errors reached the page's own error channel: "
                     (pr-str @captured))))
          (finally
            (close!)
            (stop!)
            (rf.hicasso.impl.roots/close-adoption-window! window-b)))))))

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
;; SABOTAGE, EXECUTING:
;; [[a-page-global-adoption-window-steals-an-ordinary-roots-enter-transition]]
;; below arms exactly this mutation — `sup/with-page-global-adoption` points
;; the window `impl.presence-react` reads back at a page-wide one — runs this
;; row's construction under it, and asserts the ordinary root's first phase is
;; `:present`. That is the assertion the shipped code failed, going red on
;; demand rather than being described. It was first run by hand for PR #7756,
;; landed on main as commit `fdbf5d6907`.
(deftest presence-adoption-belongs-to-a-subtree-not-to-the-page
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM") (done))
      ;; `held` is what lets ONE settlement cover this row, and it is here
      ;; because this row is the only one whose releasables are minted INSIDE
      ;; the chain: `ha` needs the server's bytes, and those arrive as a
      ;; promise. A `sup/settle-row!` at the tail cannot close over bindings the
      ;; chain makes, and the rejection path is exactly the path on which
      ;; those `let`s were never entered — so each is recorded as it is
      ;; created and `:release!` gives back whatever exists. Every other row
      ;; in this file binds its handles before the chain and names them
      ;; directly, which is the shape the two repaired sibling suites carry.
      (let [held (atom {})]
        (fresh!)
        (reset! !phases {})
        (-> (rf.hicasso.roots-frames-support/settled-server-html!
              frame-a [tray-screen {:tag :hydrated}]
              (fn [c] (= "present" (text-in c ".probe"))))
            (.then
              (fn [html]
                (is (re-find #"present" html)
                    (str "premise: the server's bytes show the child already
                          PRESENT — " html))
                ;; The server render's own phases are not this row's subject.
                (reset! !phases {})
                (rf.hicasso.impl.collector/reset-runtime!)
                (let [ca (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html))
                      cb (rf.hicasso.impl.mount/fresh-container!)
                      {:keys [seen stop!]} (rf.hicasso.roots-frames-support/watch-mismatches!)
                      ha (rf.hicasso.impl.mount/hydrate-root! ca frame-a [tray-screen {:tag :hydrated}])]
                  (swap! held assoc :stop! stop! :ha ha)
                  (is (true? (rf.hicasso.impl.roots/adopting? (:adoption ha)))
                      "premise: root A is adopting on THIS line, so what follows
                       happens inside its window by construction")
                  ;; An ORDINARY root, mounted inside root A's adoption window.
                  (let [hb (rf.hicasso.impl.mount/root! cb frame-b [tray-screen {:tag :ordinary}])]
                    (swap! held assoc :hb hb)
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
                      (is (false? (rf.hicasso.impl.roots/adopting? (:adoption hb)))))

                    (-> (rf.hicasso.roots-frames-support/adopted! ha)
                        (.then
                          (fn [ok]
                            ;; Stopped here and named in `:release!` too —
                            ;; `stop!` is idempotent by its own docstring, and
                            ;; the rejection path never reaches this line.
                            (stop!)
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
                              (is (rf.hicasso.roots-frames-support/every-server-node? ca ".screen, .probe"))
                              (is (= 0 (count @seen))
                                  (str "no hydration mismatch; got "
                                       (pr-str (mapv (comp :error rf.hicasso.roots-frames-support/tags-of)
                                                     @seen)))))

                            (testing "closing root A's window changed nothing
                                      about root B: B still completed its own
                                      enter transition, on its own clock"
                              (is (false? (rf.hicasso.impl.roots/adopting? (:adoption ha))))
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
                              (let [window (rf.hicasso.impl.roots/open-adoption-window!)
                                    orphan {:frame     frame-a
                                            :container (rf.hicasso.impl.mount/fresh-container!)
                                            :root      nil
                                            :adoption  window}]
                                (is (true? (rf.hicasso.impl.roots/adopting? window))
                                    "premise: open, and its closer has not run")
                                (rf.hicasso.impl.mount/unmount! orphan)
                                (is (false? (rf.hicasso.impl.roots/adopting? window))
                                    "teardown shut it"))))))))))
            (rf.hicasso.roots-frames-support/settle-row!
              {:row      "H5 — presence adoption belongs to a subtree, not to the page"
               :done     done
               :release! (fn []
                           (when-let [stop! (:stop! @held)] (stop!))
                           (some-> (:ha @held) rf.hicasso.impl.mount/release!)
                           (some-> (:hb @held) rf.hicasso.impl.mount/release!))}))))))

;; ---------------------------------------------------------------------------
;; H6 — THE EXECUTING SABOTAGE CONTROL for this risk family
;; ---------------------------------------------------------------------------

;; Kernel risk row 8 of `docs/design/hicasso/product/lanes/adversarial-risks.md`
;; — *adoption and mismatch attribution are root-scoped; simultaneous roots
;; cannot cross-contaminate* — is a correctness gate, and that register's gate
;; construction rule asks every one of them for "a sabotage mutation that makes
;; each correctness gate red". A mutation written as PROSE is no answer: a
;; reviewer cannot re-run a comment.
;;
;; This row is the mutation, executing. `sup/with-page-global-adoption`
;; restores the page-global window — one ref for the whole page, read by
;; every presence tray on it — and the row runs H5's construction under it and
;; again without it, changing nothing else in between. Both halves are
;; load-bearing and they red in opposite directions:
;;
;;   - the ARMED half reds if the sabotage stops sabotaging, which is what a
;;     control that has quietly become a no-op looks like from the outside;
;;   - the DISARMED half reds if the runtime regresses to a page-global window,
;;     which is the defect H5 exists to catch.
;;
;; Together they say what neither says alone: the `:mounting` H5 asserts is a
;; DISCRIMINATION rather than a ceiling, and there is a live, re-runnable
;; mutation that turns it into `:present`.
;;
;; The disarmed half is strictly stronger than H5, and by one line: the armed
;; half's page-wide window is asserted STILL OPEN while the shipped tray reads
;; its phase. So what the tray answers is the scoping doing its work, not the
;; absence of any window anywhere.
;;
;; Every reading is synchronous, and deliberately. `hydrate-root!` returns
;; before its tree is adopted and `root!` commits inside a `flushSync`, so the
;; ordinary root's first render is readable on the line after it mounts and is
;; provably inside the hydrating root's window. No timer stands anywhere in the
;; readings — a control that went green or red on the scheduler would be worse
;; than none.
(deftest a-page-global-adoption-window-steals-an-ordinary-roots-enter-transition
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (reset! !phases {})
        (let [html (rf.hicasso.roots-frames-support/server-html! frame-a [screen {:title "A"}])]
          (rf.hicasso.impl.collector/reset-runtime!)
          (let [armed
                (rf.hicasso.roots-frames-support/with-page-global-adoption
                  (fn [page-window]
                    (is (false? (rf.hicasso.impl.roots/adopting? page-window))
                        "premise: the page-global window is SHUT before anything
                         hydrates, so an open one below was opened by a ROOT and
                         not handed over by the arming")
                    (let [ha (rf.hicasso.impl.mount/hydrate-root! (rf.hicasso.roots-frames-support/server-dom! html) frame-a
                                                  [screen {:title "A"}])]
                      (is (true? (rf.hicasso.impl.roots/adopting? page-window))
                          "premise: root A's hydration opened it — and under this
                           mutation it is the only window there is")
                      {:ha          ha
                       :page-window page-window
                       :hb          (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-b
                                                 [tray-screen {:tag :under-page-global}])})))
                disarmed
                (let [ha (rf.hicasso.impl.mount/hydrate-root! (rf.hicasso.roots-frames-support/server-dom! html) frame-a
                                              [screen {:title "A"}])]
                  (is (not (identical? (:adoption ha) (:page-window armed)))
                      "premise: the arming restored the per-root mint, so this
                       root's window is its own — a half that inherited the
                       page-global would be no control at all")
                  (is (true? (rf.hicasso.impl.roots/adopting? (:adoption ha)))
                      "premise: root A is adopting in this half too, so the two
                       halves differ only in the SCOPE of its window")
                  (is (true? (rf.hicasso.impl.roots/adopting? (:page-window armed)))
                      "premise: and the armed half's PAGE-WIDE window is still
                       open on this line — so what the tray below reads is the
                       scoping, not the absence of a window")
                  {:ha ha
                   :hb (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-b
                                    [tray-screen {:tag :under-root-scoped}])})]

            (testing "ARMED — the ordinary root's tray was told it was adopting,
                      though it is adopting nothing. Its children are born
                      `:present` and the enter transition its author wrote never
                      runs: no error, no complaint, an animation that simply does
                      not play, and only while some unrelated root happens to be
                      hydrating"
              (is (= :present (first (get @!phases :under-page-global)))
                  (str "THE SABOTAGE DID NOT SABOTAGE — this control has become a
                        no-op and H5's green means nothing; saw "
                       (pr-str (get @!phases :under-page-global))))
              (is (= "present" (text-in (:container (:hb armed)) ".probe"))
                  "and it reached the DOM that way"))

            (testing "DISARMED — the same construction with the shipped
                      root-scoped window, and the ordinary root has its ENTER
                      phase back. H5's assertion, taken here so the contrast is
                      one row's pair of readings rather than two files'"
              (is (= :mounting (first (get @!phases :under-root-scoped)))
                  (str "the runtime read a window this root does not own; saw "
                       (pr-str (get @!phases :under-root-scoped))))
              (is (= "mounting" (text-in (:container (:hb disarmed)) ".probe"))))

            ;; `js/Promise.all` rejects if EITHER adoption does, and this row
            ;; holds FOUR handles — so a single rejected adoption stranded all
            ;; four, which is why this is the most expensive row in the file
            ;; to leave unsettled.
            (-> (js/Promise.all #js [(rf.hicasso.roots-frames-support/adopted! (:ha armed))
                                     (rf.hicasso.roots-frames-support/adopted! (:ha disarmed))])
                (.then
                  (fn [oks]
                    (is (= [true true] (vec oks))
                        "both hydrating roots must reach their own closer, or
                         this row left an open window behind")))
                (rf.hicasso.roots-frames-support/settle-row!
                  {:row      "H6 — the page-global sabotage control"
                   :done     done
                   :release! (fn []
                               (rf.hicasso.impl.mount/release! (:hb armed))
                               (rf.hicasso.impl.mount/release! (:ha armed))
                               (rf.hicasso.impl.mount/release! (:hb disarmed))
                               (rf.hicasso.impl.mount/release! (:ha disarmed)))}))))))))

;; ---------------------------------------------------------------------------
;; H7 — THE LEAK CONTROL: a rejected adoption still releases BOTH roots
;; ---------------------------------------------------------------------------
;;
;; H1 through H6 all FULFIL on a green run, so `sup/settle-row!`'s rejection
;; arm is on no green path — and a repair to a branch nothing takes is
;; untested by construction. This row takes it.
;;
;; It is written on `js/Promise.all` over TWO adoptions because that is H6's
;; shape and the most expensive one in this file: `Promise.all` rejects if
;; EITHER adoption does, so one rejection is enough to strand every handle
;; the row holds. The rejection is injected AFTER both adoptions complete,
;; which is the harder case and not the weaker one — every root is live and
;; committed at that moment, so there is strictly MORE to release than there
;; would be had an adoption rejected before either root adopted.
;;
;; Under the shape this file carried before, nothing below the injection runs
;; at all. The rejection skips the fulfilment handler, so the `try` is never
;; entered and its `finally` never fires: no `release!`, no `done`. The row
;; does not go red — it hangs to `cljs.test`'s async timeout, reports the
;; timeout instead of the rejection, and leaves two roots mounted and their
;; containers in the document for whatever runs next.

(deftest a-rejected-adoption-still-releases-both-roots-and-the-watcher
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! "what a rejection can leak here is two real roots")
    (async done
      (fresh!)
      (let [html-a   (rf.hicasso.roots-frames-support/server-html! frame-a [screen {:title "A"}])
            html-b   (rf.hicasso.roots-frames-support/server-html! frame-b [screen {:title "B"}])
            ca       (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html-a))
            cb       (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html-b))
            watch    (rf.hicasso.roots-frames-support/watch-mismatches!)
            ;; NOT `:swallow-uncaught? true`: this row manufactures a
            ;; rejected PROMISE, which `sup/settle-row!` handles, and no uncaught
            ;; window error at all. Swallowing anywhere else is the fail-open
            ;; the browser runner's pageerror rule forbids.
            stops    (atom 0)
            finishes (atom 0)
            reports  (atom [])]
        (rf.hicasso.impl.collector/reset-runtime!)
        (let [ha (rf.hicasso.impl.mount/hydrate-root! ca frame-a [screen {:title "A"}])
              hb (rf.hicasso.impl.mount/hydrate-root! cb frame-b [screen {:title "B"}])]
          (-> (js/Promise.all #js [(rf.hicasso.roots-frames-support/adopted! ha) (rf.hicasso.roots-frames-support/adopted! hb)])
              (.then
                (fn [oks]
                  (is (= [true true] (vec oks)) "premise: both roots really did adopt")
                  (is (not= rf.hicasso.roots-frames-support/released (rf.hicasso.roots-frames-support/census))
                      (str "premise: the runtime is holding these roots' cells "
                           "and edges, so the census taken after the rejection "
                           "is a RELEASE and not an empty page; got "
                           (pr-str (rf.hicasso.roots-frames-support/census))))
                  (js/Promise.reject (js/Error. "adoption rejected on purpose"))))
              (rf.hicasso.roots-frames-support/settle-row!
                {:row      "the rejected-adoption control"
                 :done     (fn [] (swap! finishes inc))
                 :report!  (fn [e] (swap! reports conj e))
                 :release! (fn []
                             (swap! stops inc)
                             ((:stop! watch))
                             (rf.hicasso.impl.mount/release! ha)
                             (rf.hicasso.impl.mount/release! hb))})
              ;; The cell reapers are armed at unmount and run past a bare
              ;; macrotask, so the tables are read at the runtime's own horizon
              ;; rather than one tick after the release.
              (.then (fn [_] (rf.hicasso.roots-frames-support/quiesced!)))
              (.then
                (fn [_]
                  (testing "the rejection is REPORTED — which is the whole of
                            what a hang gives away — and the row ends ONCE"
                    (is (= 1 @finishes)
                        (str "done ran " @finishes " times"))
                    (is (= 1 (count @reports))
                        (str "exactly one report; got " (pr-str @reports)))
                    (is (re-find #"adoption rejected on purpose" (str (first @reports)))
                        (str "naming what the adoption threw; got "
                             (pr-str @reports))))

                  (testing "and the page the NEXT row inherits holds nothing of
                            this one. The roots are the row's alone to give
                            back — the `:each` fixture resets frames, disposes
                            the adapter and empties the runtime, but it never
                            unmounts a React root. (The trace listener it does
                            sweep, in its `:before`; `stop!` is asserted here
                            all the same, because a row that leans on the
                            fixture to stop its own watcher is measuring the
                            fixture)"
                    (is (= rf.hicasso.roots-frames-support/released (rf.hicasso.roots-frames-support/census))
                        (str "neither root left: residue was " (pr-str (rf.hicasso.roots-frames-support/census))))
                    (is (empty? (rf.hicasso.roots-frames-support/cell-frames))
                        (str "no frame: the cell table still mentions "
                             (pr-str (rf.hicasso.roots-frames-support/cell-frames))))
                    (is (= 1 @stops)
                        (str "the mismatch watcher was stopped — `stop!` is what "
                             "unregisters the trace listener; it ran "
                             @stops " times")))))
              (rf.hicasso.roots-frames-support/settle-row!
                {:row      "the rejected-adoption control's own settlement"
                 :done     done
                 :release! (fn []
                             ((:stop! watch))
                             (rf.hicasso.impl.mount/release! ha)
                             (rf.hicasso.impl.mount/release! hb))})))))))
