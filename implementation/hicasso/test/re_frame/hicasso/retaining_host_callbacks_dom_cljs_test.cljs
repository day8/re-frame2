(ns re-frame.hicasso.retaining-host-callbacks-dom-cljs-test
  "THE RETAINING HOST — callback identity measured where it can cost
  something, and the stable-event verdict (rf2-hic-029).

  `specification.md` §4.1 carries the standing rule this file exists to
  decide:

  > Generated intent callbacks may be fresh per render. A narrow
  > stable-event primitive is admitted only if a realistic retaining host
  > demonstrates a material problem and the solution is safe across
  > abandonment, frame reincarnation, and teardown.

  Two clauses, and each needs a different instrument. *Material problem*
  is a COUNT — how often a memoized foreign child's body runs while its
  parent re-renders — and section 1 is a grid of nine carriers against
  that one number. *Safe across abandonment, reincarnation and teardown*
  is a ROUTING claim, and sections 3–6 drive a callback the vendor
  stashed at mount through all three.

  ## Why the vendor is `React.memo` and nothing simpler

  A fresh closure per render costs a native DOM element nothing: React
  assigns the new listener and moves on. It costs a **memoized** child
  its bail-out, because `React.memo`'s comparator is `Object.is` per prop
  and two closures minted in two renders are never the same object. So
  the retaining host is the only place on a page where identity is a
  price rather than a detail, and it is where this file looks.

  `sink` is written as ordinary React — it knows nothing about Hicasso,
  it counts its own body runs, and it KEEPS every callback it is handed.
  That last part is the retention: a vendor that stores a handler and
  calls it after a debounce, a WebSocket message or an animation frame is
  the realistic shape, and it is the shape that can still be holding a
  predecessor incarnation's closure long after the render that made it.

  ## What section 1 measures, exactly

  `run-arm!` mounts one carrier, ticks a piece of app-db the vendor's
  props do not depend on [[ticks]] times, and answers the vendor's body
  count. The mount is one run, so **a memo that bails answers 1 and a
  memo defeated by identity answers 1 + [[ticks]]**. There is no third
  answer, which is what makes the grid decidable rather than a matter of
  degree.

  Four of the nine carriers are CONTROLS, and they are why the grid can
  fail:

  - `:absent` — no callback at all. If this were not 1 the memo would not
    be memoizing and every other row would be meaningless.
  - `:plain-fn` — an ordinary unmarked function, hoisted out of every
    render. `lower-declared-prop` claims no unmarked fn at any contract
    and `host-prop-value` crosses functions by identity, so this row
    proves identity stability is REACHABLE at this edge today.
  - `:handler` — the same hoisted `h/hfn` at a `defhost` `:handler`
    declaration, where the contract hands the function through by
    identity.
  - `:native-stable` — `n/use-frame` plus React's own `useCallback`
    inside an `n/defcomponent` island: the one carrier that is BOTH
    identity-stable and dispatching.

  `:native-churn` is that last one with the `useCallback` taken off, so
  the native arm carries its own negative control and the bail-out cannot
  be credited to the tier.

  ## The instrument's blind spot, stated

  A vendor body run is not a millisecond and this file does not pretend
  it is. It is the event `React.memo` exists to prevent, counted where it
  happens; how much one costs depends on the vendor, which is a fact
  about somebody else's code. `hm/bodies-run` in section 7 is the
  matching reading on OUR side of the fence — Hicasso boundary bodies,
  page-wide — and it is there to show what the ordinary path pays for the
  incarnation pin, which is the second half of the risk row.

  ## Runtime

  `-dom-cljs-test`, so `:browser-test` runs it against a real React DOM;
  under `:node-test` every DOM claim degrades to a stated skip. The
  verdict this file's numbers support is recorded in
  `docs/design/hicasso/product/callback-identity-verdict.md`.

  ## Companion

  `reincarnation_routing_cljs_test` establishes the same routing law one
  rung down, against the lowering functions directly and with a
  reconstruction of the pre-rf2-x874 mechanism as its negative control.
  This file does not restate it: it asks whether the law survives a real
  React vendor holding the closure, and what the law costs there."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

(def ^:private frame-id ::retaining-host)

(def ^:private ticks
  "How many times a scenario moves app-db the vendor's props do not read.
  Five rather than one so a bail-out and a defeat are five apart rather
  than one apart, and so an off-by-one in the mount render cannot be
  mistaken for either."
  5)

;; Registered ABOVE `use-fixtures`, as every suite in this arm does: the
;; reset fixture captures its source-store baseline when the `use-fixtures`
;; form is evaluated, so a registration written below it is erased before
;; the first row runs.
;;
;; `::ping` carries a TAG so a write that arrived through the wrong carrier
;; is named in app-db rather than merely counted, and `:pings` counts
;; because a retained callback firing twice is a different fault from one
;; firing once.

(rf/reg-event ::seed (fn [_ [_ who]] {:db {:who who :tick 0 :pings 0 :last nil}}))
(rf/reg-event ::tick (fn [{:keys [db]} _] {:db (update db :tick inc)}))
(rf/reg-event ::ping (fn [{:keys [db]} [_ tag]]
                       {:db (-> db (update :pings inc) (assoc :last tag))}))

(rf/reg-sub ::ticks (fn [db _] (:tick db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (collector/reset-runtime!)
                      (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; The vendor — ordinary React, memoized, and it KEEPS what it is handed
;; ---------------------------------------------------------------------------

(defonce ^:private !sink-runs
  ;; The vendor's own body count. `hm/bodies-run` counts BOUNDARY bodies
  ;; and a foreign component is not one, so the number section 1 turns on
  ;; has to be kept where the body actually runs.
  (atom 0))

(defonce ^:private !held
  ;; Every callback the vendor has been handed, in render order. A vector
  ;; rather than one slot because the two ends are different questions:
  ;; `(first …)` is the closure the MOUNT render minted — the one a
  ;; realistic vendor is still holding an hour later — and `(last …)` is
  ;; the newest, which is what a fresh-per-render runtime keeps replacing.
  (atom []))

(defonce ^:private !plain-calls
  ;; How often the two identity-stable-but-inert carriers were invoked.
  ;; Neither dispatches (see the grid's notes), so a call count is the
  ;; only evidence that they ran at all.
  (atom 0))

(defn- sink-body
  "The retaining host. It counts its runs, keeps every handler it is
  given, and paints a button — nothing else, and deliberately nothing
  else: `children` would be a fresh element on every parent render and
  `React.memo` would never bail for any carrier, which would make the
  whole grid read the same number for the wrong reason."
  [^js props]
  (swap! !sink-runs inc)
  (swap! !held conj (.-onPing props))
  (react/createElement "button" #js {:className "sink"} (.-label props)))

(def ^:private sink (react/memo sink-body))

(h/defhost sink-event
  "The crossing under the RULED DEFAULT `:ssr :client-only`, so the grid
  measures the path a consumer gets by writing nothing. The gate that
  policy mints is a plain function component that hands its props object
  straight to the foreign component, so it changes what is compared not
  at all — it re-renders with its parent and the memo below it decides
  for itself."
  sink
  {:callbacks {:on-ping :event}})

(h/defhost sink-handler
  "The SAME vendor at the `:handler` contract, which passes an `h/fn`
  through by identity. One declaration apart from the row above, so the
  grid's `:handler` row differs from its `:hfn-hoisted` row in the
  declaration and in nothing else."
  sink
  {:callbacks {:on-ping :handler}})

;; ---------------------------------------------------------------------------
;; The carriers that are hoisted out of every render
;; ---------------------------------------------------------------------------

(defn- plain-ping
  "An ordinary unmarked function. No position claims it — `lower-prop`
  and `lower-declared-prop` both leave an unmarked fn alone — so it
  reaches the vendor as itself and its identity is whatever the `def`
  gave it."
  [& _]
  (swap! !plain-calls inc)
  nil)

(def ^:private hoisted-hfn
  "The one callback form, minted once at load rather than per render.
  At an `:event` contract this is STILL re-wrapped every render — the
  wrapper is what closes over the frame-locked dispatch — so hoisting the
  author's own function does not hoist what the vendor sees. That is the
  `:hfn-hoisted` row, and it is the row that shows the churn is the
  LOWERING's and not the author's."
  (h/hfn [& _] (swap! !plain-calls inc) [::ping :hoisted]))

;; ---------------------------------------------------------------------------
;; The interpreted parent — one boundary, one read, nine carriers
;; ---------------------------------------------------------------------------

(h/defview host-parent
  "Reads a piece of app-db the vendor's props do not depend on, so every
  tick re-runs this body and hands the crossing a fresh props object
  carrying semantically identical values. Whether the vendor re-renders
  is then a question about ONE thing: the identity of what sits at
  `:on-ping`."
  [{:keys [arm]}]
  (let [tick (h/sub [::ticks])]
    [:div.parent {:data-tick (str tick)}
     (case arm
       :absent        [sink-event   {:label "s"}]
       :plain-fn      [sink-event   {:label "s" :on-ping plain-ping}]
       :handler       [sink-handler {:label "s" :on-ping hoisted-hfn}]
       :intent-vector [sink-event   {:label "s" :on-ping [::ping :vector]}]
       :hfn-inline    [sink-event   {:label "s" :on-ping (h/hfn [& _] [::ping :inline])}]
       :hfn-hoisted   [sink-event   {:label "s" :on-ping hoisted-hfn}]
       :key-map       [sink-event   {:label "s" :on-ping {"Enter" [::ping :key-map]}}])]))

;; ---------------------------------------------------------------------------
;; The native parent — the tier where a hook can hold an identity
;; ---------------------------------------------------------------------------
;;
;; Past the fence there is no boundary body, there is a fiber, so React's
;; own hooks are the vocabulary and `n/use-frame` supplies the one thing
;; React cannot: the frame. Its docstring already states the property this
;; arm turns on — the bundle is the runtime's own memo row, identical
;; across renders of ONE incarnation and replaced when that incarnation is
;; superseded — so a `useCallback` keyed on it is stable exactly as long
;; as it is safe to be, and no longer.
;;
;; Both islands render the vendor through `react/createElement` rather
;; than through a `defhost`, because inside the native tier that IS the
;; ordinary spelling. There is therefore no gate above the memo on these
;; two rows, which is stated rather than hidden: it removes a component
;; from the path and adds nothing to it, and the `:absent` row already
;; shows the gate does not disturb a bail-out.

(n/defcomponent stable-island
  "The identity-stable DISPATCHING carrier — the whole of the safe
  solution the standing rule asks about, assembled out of surface that
  already ships."
  [^js _props]
  (let [_tick   (n/use-sub [::ticks])
        ops     (n/use-frame)
        on-ping (react/useCallback
                  (fn [& _] ((:dispatch-sync ops) [::ping :native-stable]) nil)
                  #js [ops])]
    (react/createElement sink #js {:label "s" :onPing on-ping})))

(n/defcomponent churning-island
  "The same island with the `useCallback` taken off — the negative
  control for the row above. Without it a reader could credit the
  bail-out to the tier, to the missing gate, or to `use-frame` itself
  rather than to the memoisation of the closure."
  [^js _props]
  (let [_tick (n/use-sub [::ticks])
        ops   (n/use-frame)]
    (react/createElement sink
                         #js {:label  "s"
                              :onPing (fn [& _]
                                        ((:dispatch-sync ops) [::ping :native-churn])
                                        nil)})))

(h/defview stable-parent [_] (n/$ stable-island {}))
(h/defview churn-parent  [_] (n/$ churning-island {}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip! [why] (is true (str "a retaining-host claim needs a real React DOM — " why)))

(defn- db [] (rf/app-db-value frame-id))
(defn- pings [] (:pings (db)))
(defn- last-tag [] (:last (db)))

(defn- fresh!
  "Seat a first incarnation under the fixed public id and zero every
  counter. React's `act` queue is not the browser's and none of this runs
  inside one."
  ([] (fresh! "A"))
  ([who]
   (support/leave-act-environment!)
   (when (frame/frame-incarnation-token frame-id) (rf/destroy-frame! frame-id))
   (frames/forget-frame-ops! frame-id)
   (rf/make-frame {:id frame-id})
   (rf/with-frame frame-id (rf/dispatch-sync [::seed who]))
   (reset! !sink-runs 0)
   (reset! !held [])
   (reset! !plain-calls 0)
   frame-id))

(defn- reincarnate!
  "Destroy the live incarnation and seat a fresh one under the same public
  id, seeded with a different marker so a write that landed cannot be
  confused for one that did not."
  [who]
  (rf/destroy-frame! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed who]))
  nil)

(defn- with-refusals
  "Run `thunk` and collect the always-on corpus `:rf.error/frame-destroyed`
  records it fans. Answers `{:result … :refusals [<record>…]}`.

  Axis 1 (`error-emit`) rather than the dev trace, exactly as
  `reincarnation_routing_cljs_test` takes it: it survives
  `-Dre-frame.debug=false`, so these assertions hold under the production
  posture too. It is also the ONLY observable separating *refused* from
  *silently delivered to the wrong frame*, which is the fault this file
  is here about."
  [thunk]
  (let [seen (atom [])
        k    (keyword "rf2-hic-029" (name (gensym "refusal")))]
    (error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try {:result (thunk) :refusals @seen}
         (finally (error-emit/unregister-error-listener! k)))))

(defn- fire!
  "Invoke a retained handler the way its position's invoker would. One
  event object serves every carrier: the key-map branch reads `.key`, and
  every other carrier ignores the argument."
  [h]
  (h #js {:key "Enter"}))

(defn- run-arm!
  "Mount `form`, tick [[ticks]] times, and answer the vendor's body count.
  The mount is one run, so 1 is a bail-out and 1 + [[ticks]] is a memo
  defeated by identity."
  [form]
  (fresh!)
  (let [handle (mount/root! (mount/fresh-container!) frame-id form)]
    (try
      (dotimes [_ ticks] (mount/dispatch! handle [::tick]))
      @!sink-runs
      (finally (mount/release! handle)))))

;; ---------------------------------------------------------------------------
;; 1 — THE GRID. What a memoized vendor costs, per carrier
;; ---------------------------------------------------------------------------

(def ^:private grid
  "Carrier → the form that mounts it. Read with [[run-arm!]]; the expected
  number is asserted below against the two constants rather than against
  a literal, so the table cannot drift from what [[ticks]] says."
  [[:absent        [host-parent {:arm :absent}]        :bails]
   [:plain-fn      [host-parent {:arm :plain-fn}]      :bails]
   [:handler       [host-parent {:arm :handler}]       :bails]
   [:native-stable [stable-parent {}]                  :bails]
   [:intent-vector [host-parent {:arm :intent-vector}] :re-renders]
   [:hfn-inline    [host-parent {:arm :hfn-inline}]    :re-renders]
   [:hfn-hoisted   [host-parent {:arm :hfn-hoisted}]   :re-renders]
   [:key-map       [host-parent {:arm :key-map}]       :re-renders]
   [:native-churn  [churn-parent {}]                   :re-renders]])

(deftest the-memoized-vendor-grid
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM, and a memo bail-out is React's own")
    (doseq [[carrier form expectation] grid]
      (testing (str (name carrier) " — the vendor "
                    (if (= :bails expectation)
                      "bails out on every re-render"
                      "re-renders with its parent"))
        (let [runs (run-arm! form)]
          (is (= (if (= :bails expectation) 1 (inc ticks)) runs)
              (str "carrier " (name carrier) " ran the vendor's body " runs
                   " times across " ticks " parent re-renders")))))))

(deftest the-grid-is-a-live-instrument
  ;; Section 1 turns on a bail-out, which is a NON-event, and a non-event
  ;; passes for the wrong reason the moment the thing that would disturb
  ;; it stops happening — a parent that no longer re-renders, a read that
  ;; no longer notifies, a tick that no longer writes. So the same
  ;; scenario is asked the opposite question: the carriers that churn must
  ;; churn by exactly the number of ticks that were driven.
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (testing "the parent really did re-render once per tick"
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [host-parent {:arm :intent-vector}])]
          (try
            (dotimes [_ ticks] (mount/dispatch! handle [::tick]))
            (is (= (str ticks) (.getAttribute (.querySelector (:container handle) ".parent")
                                              "data-tick"))
                "the parent painted every tick, so the bail-out rows above were
                 given something to bail out of")
            (finally (mount/release! handle)))))

      (testing "and the vendor is one component, so the two answers are
                comparable rather than two different memos"
        (fresh!)
        (is (= 1 (run-arm! [host-parent {:arm :absent}])))
        (is (= (inc ticks) (run-arm! [host-parent {:arm :intent-vector}]))
            "same vendor, same declaration, same parent, same ticks — the
             carrier is the whole of the difference")))))

;; ---------------------------------------------------------------------------
;; 2 — Identity, read directly rather than inferred from the count
;; ---------------------------------------------------------------------------

(deftest what-the-vendor-holds-across-renders
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (testing "a lowered intent vector hands the vendor a NEW function every
                render — which is the mechanism the grid measures the
                consequence of"
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [host-parent {:arm :intent-vector}])]
          (try
            (dotimes [_ ticks] (mount/dispatch! handle [::tick]))
            (is (= (inc ticks) (count @!held)))
            (is (= (inc ticks) (count (set @!held)))
                "every one of them is a distinct object")
            (finally (mount/release! handle)))))

      (testing "the identity-stable carriers hand it the SAME function every
                render, so the bail-out above is `Object.is` answering true
                rather than React skipping a render for some other reason"
        (doseq [arm [:plain-fn :handler]]
          (fresh!)
          (let [handle (mount/root! (mount/fresh-container!) frame-id
                                    [host-parent {:arm arm}])]
            (try
              (dotimes [_ ticks] (mount/dispatch! handle [::tick]))
              (is (= 1 (count @!held))
                  (str (name arm) " re-rendered the vendor"))
              (finally (mount/release! handle)))))))))

;; ---------------------------------------------------------------------------
;; 3 — RETENTION. A callback the vendor kept still routes inside its own
;;     incarnation
;; ---------------------------------------------------------------------------

(deftest the-mount-renders-callback-still-routes-after-later-renders
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [host-parent {:arm :intent-vector}])]
        (try
          (dotimes [_ ticks] (mount/dispatch! handle [::tick]))
          (let [oldest (first @!held)
                {:keys [refusals]} (with-refusals #(fire! oldest))]
            (mount/settle!)
            (is (= 1 (pings))
                "the closure minted by the MOUNT render — five renders stale,
                 and the only one a vendor holding its first handler would
                 still have — dispatches into the live incarnation")
            (is (= :vector (last-tag)))
            (is (empty? refusals)
                "and nothing is refused: staleness across renders is not
                 staleness across incarnations, and only the second is a
                 fault"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — TEARDOWN and RETIREMENT. Harmless afterwards, and LOUDLY so
;; ---------------------------------------------------------------------------

(deftest a-retained-callback-is-harmless-after-the-frame-is-retired
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle   (mount/root! (mount/fresh-container!) frame-id
                                  [host-parent {:arm :intent-vector}])
            retained (do (mount/dispatch! handle [::tick]) (first @!held))]
        (mount/unmount! handle)
        (rf/destroy-frame! frame-id)
        (let [{:keys [refusals]} (with-refusals #(fire! retained))]
          (is (= 1 (count refusals))
              "the drop is LOUD — exactly one always-on
               :rf.error/frame-destroyed. Silence here would be
               indistinguishable from a handler that quietly worked")
          (is (= frame-id (:frame (first refusals))) "attributed to the captured id")
          (is (= ::ping (:event-id (first refusals))) "carrying the refused event's head"))
        (is (nil? (frame/frame-incarnation-token frame-id))
            "and the retirement really happened, so the refusal above is not a
             frame that was never there")))))

;; ---------------------------------------------------------------------------
;; 5 — REINCARNATION. Same public id, and the retained closure may not
;;     reach the successor
;; ---------------------------------------------------------------------------

(deftest a-retained-callback-cannot-write-the-successor-under-the-same-id
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (testing "SAFETY — the predecessor's callback, still held by a live
                vendor, is refused against a successor at the same address"
        (fresh! "A")
        (let [handle   (mount/root! (mount/fresh-container!) frame-id
                                    [host-parent {:arm :intent-vector}])
              retained (first @!held)]
          (mount/unmount! handle)
          (reincarnate! "B")
          (is (zero? (pings)) "sanity: the successor starts unpinged")
          (let [{:keys [refusals]} (with-refusals #(fire! retained))]
            (is (zero? (pings))
                "the successor's app-db is UNTOUCHED by a predecessor-era
                 closure — the revival the contract forbids")
            (is (nil? (last-tag)))
            (is (= 1 (count refusals))))
          (rf/destroy-frame! frame-id)))

      (testing "LIVENESS — a vendor mounted under the SUCCESSOR gets a working
                one, so the silence above is a refusal and not a runtime in
                which nothing dispatches at all"
        (fresh! "B")
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [host-parent {:arm :intent-vector}])]
          (try
            (let [{:keys [refusals]} (with-refusals #(fire! (first @!held)))]
              (mount/settle!)
              (is (= 1 (pings)) "the live incarnation's own vendor writes its own app-db")
              (is (empty? refusals)))
            (finally (mount/release! handle))))))))

(deftest NEGATIVE-CONTROL-an-unpinned-capture-does-reach-the-successor
  ;; Section 5's safety half asserts a NON-event, and the bead names the
  ;; sabotage that has to redden it: remove the retirement check and the
  ;; retired-callback case must go red. Performed through the documented
  ;; seam rather than by redefining a runtime var — `rf/capture-frame`
  ;; taken while NO frame is live under the id pins nothing, so the ops
  ;; stay address-directed and write whoever occupies the address later.
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [unpinned (rf/capture-frame frame-id)]    ; captured with no live frame
      (fresh! "A")
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals
                                 #((:dispatch-sync unpinned) [::ping :unpinned]))]
        (is (= 1 (pings))
            "the write LANDS — so the identical assertion in section 5 is
             capable of failing, and passes there because of the pin")
        (is (= :unpinned (last-tag)))
        (is (empty? refusals) "and nothing is refused, because nothing was pinned")))))

;; ---------------------------------------------------------------------------
;; 6 — DELAYED. The same law, a real macrotask after the render
;; ---------------------------------------------------------------------------

(deftest a-delayed-retained-callback-obeys-the-same-law
  ;; Sections 4 and 5 fire on the test's own stack, which is the one place
  ;; a vendor never fires from. This one goes through a real `setTimeout`,
  ;; long after the render's dynamic extent has unwound and after the
  ;; teardown — the debounce, the socket message, the animation frame.
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh! "A")
      (let [handle   (mount/root! (mount/fresh-container!) frame-id
                                  [host-parent {:arm :intent-vector}])
            retained (first @!held)]
        (mount/unmount! handle)
        (reincarnate! "B")
        (js/setTimeout
          (fn []
            (let [{:keys [refusals]} (with-refusals #(fire! retained))]
              (is (zero? (pings))
                  "a macrotask later, past the teardown and past the
                   reincarnation, the retained closure still cannot reach the
                   successor")
              (is (= 1 (count refusals))))
            (rf/destroy-frame! frame-id)
            (done))
          0)))))

;; ---------------------------------------------------------------------------
;; 7 — THE ORDINARY PATH. What every boundary pays for the safety
;; ---------------------------------------------------------------------------

(deftest the-ordinary-path-pays-nothing-for-the-incarnation-pin
  ;; The risk row's second clause: *ordinary code pays no universal proxy
  ;; cost*. There is no proxy to count, so the question is asked as work:
  ;; how many BOUNDARY bodies one tick runs, with a retaining host on the
  ;; page and without one. `hm/bodies-run` is the page-wide reading for
  ;; exactly this and it settles inside the thunk, which `mount/dispatch!`
  ;; already does.
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (doseq [arm [:absent :intent-vector :plain-fn]]
      (testing (str "one tick runs one boundary body with the " (name arm)
                    " carrier on the page")
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [host-parent {:arm arm}])]
          (try
            (is (= 1 (hm/bodies-run #(mount/dispatch! handle [::tick])))
                "the parent's body, and nothing else. The crossing, the gate
                 and the vendor are not boundaries, and the pin the safety
                 rests on is a memo row acquired once per incarnation rather
                 than work done per render")
            (finally (mount/release! handle))))))))
