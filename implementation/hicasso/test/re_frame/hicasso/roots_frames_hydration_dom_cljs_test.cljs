(ns re-frame.hicasso.roots-frames-hydration-dom-cljs-test
  "TWO OVERLAPPING HYDRATING ROOTS — independent adoption, independent
  complaints, independent teardown; and the ONE remaining process-global
  in the way (rf2-hic-012).

  The bead's second and third deliverables. The second is a property:
  two roots adopting server markup at the same time must not corrupt or
  silence one another. The third is a decision: the shared hydration
  adoption window is moved to root scope, *or* the remaining global is
  justified in writing, feeding rf2-hic-017 (\"the mutable-global sweep —
  root-scope or justify every one\"). This suite takes the second branch,
  and it takes it by MEASURING the global rather than by arguing about
  it — see [[the-adoption-window-is-one-boolean-for-the-whole-page]],
  which is the row rf2-hic-017 inherits.

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
            ;; the window being open on this line is what says so.
            (is (true? (roots/adopting?))
                "both roots are in flight — `hydrate-root!` returns before
                 the tree is adopted, so the window outlives both calls")
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
                        (is (= 2 (:frames (inventory/stats)))))

                      (finally (mount/release! ha) (mount/release! hb) (done))))))))))))

;; ---------------------------------------------------------------------------
;; H2 — two overlapping mismatches, two independent complaints
;; ---------------------------------------------------------------------------

(deftest two-overlapping-hydrating-roots-complain-independently
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

                      (testing "TWO complaints, one per root — a complaint that
                                went missing would leave no mark on the DOM at
                                all, because React repairs the tree before the
                                callback runs"
                        (is (= 2 (count @seen))
                            (str "one `:rf.ssr/hydration-mismatch` per diverging
                                  root; got " (count @seen) " — "
                                 (pr-str (mapv (comp :error sup/tags-of) @seen)))))

                      (testing "and each is the framework diagnostic Spec 011
                                names, tier-discriminated by its door"
                        (doseq [ev @seen]
                          (let [tags (sup/tags-of ev)]
                            (is (= :rf.ssr/hydration-mismatch (:operation ev)))
                            (is (= 're-frame.hicasso.impl.mount/hydrate-root! (:where tags)))
                            (is (= :warned-and-replaced (:recovery tags)))
                            (is (string? (:error tags))))))

                      (testing "React reported both on its own channel too — the
                                reporter composes over React's default and never
                                swallows it"
                        (is (<= 2 (count @captured))
                            (str "got " (pr-str @captured))))

                      (testing "and each root recovered to ITS OWN client model,
                                not to its sibling's"
                        (is (= "client-A" (text-in ca ".title")))
                        (is (= "client-B" (text-in cb ".title")))
                        (is (= "alpha" (text-in ca ".value")))
                        (is (= "beta"  (text-in cb ".value"))))

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
;; H4 — the remaining global, MEASURED. rf2-hic-017's row.
;; ---------------------------------------------------------------------------

;; **This row exists to be DELETED**, and saying so is the point.
;;
;; rf2-hic-012's third deliverable is to move the hydration adoption window
;; to root scope *or* to justify the remaining global in writing. This is
;; the justification, in the only form worth having: a measurement, taken
;; through the shipping doors, of exactly what the global does.
;;
;; `impl.roots` holds ONE boolean. `impl.mount/hydrate-root!` opens it per
;; root, and each root's `adoption-window-closer` shuts it from a passive
;; effect on that root's hydration commit. So N overlapping roots perform N
;; opens and the FIRST close ends the window for all of them. The readers
;; are `impl.presence-react`, during render, and
;; `impl.mount/hydration-reporter`, which emits the Spec 011 diagnostic
;; only `(when (roots/adopting?))`.
;;
;; The consequence is a conditional, and the condition is React's: while
;; React commits two same-tick hydrations before flushing either root's
;; passive effects, no complaint is lost, and H2 above measures that it is
;; not. **The property therefore holds today by React's scheduling and not
;; by this runtime's design** — a root whose hydration commit landed after
;; another root's closer had run would have its mismatch diagnostic
;; silently dropped, and nothing in this arm prevents that ordering. That
;; is the whole of the case for rf2-hic-017, and the two rows together are
;; its evidence: H2 says the property holds, this row says what it rests
;; on.
;;
;; When the window becomes root-scoped this row goes RED, because two opens
;; will no longer be shut by one close. That is its job. Re-pinning it
;; would be the wrong repair; deleting it is the right one.
(deftest the-adoption-window-is-one-boolean-for-the-whole-page
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no DOM")
    (do
      (fresh!)
      (try
        (is (false? (roots/adopting?)) "premise: no adoption is in flight")
        ;; The two doors a hydrating root takes, called directly — this is
        ;; what `hydrate-root!` does per root, with React's schedule
        ;; removed so the arity is the only variable left.
        (roots/open-adoption-window!)
        (roots/open-adoption-window!)
        (is (true? (roots/adopting?)) "two roots, adopting")
        ;; What ONE root's closer does, on ONE root's hydration commit.
        (roots/close-adoption-window!)
        (is (false? (roots/adopting?))
            "MEASURED (rf2-hic-017): one root's closer shuts the window for
             every root. The window is page-scoped, so a second root still
             adopting is no longer adopting as far as every reader is
             concerned. This assertion must be DELETED, not re-pinned, when
             the window becomes root-scoped.")
        (finally (roots/close-adoption-window!))))))
