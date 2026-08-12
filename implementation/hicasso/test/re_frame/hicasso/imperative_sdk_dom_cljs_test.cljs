(ns re-frame.hicasso.imperative-sdk-dom-cljs-test
  "ONE IMPERATIVE SDK, OWNED THROUGH THE DECLARED HOST SEAM (rf2-hic-067).

  `specification.md` §7's *Imperative SDKs* row promises *declared host
  ownership* answered by an *acquire/release recipe*, proved against
  *StrictMode, remount, throw, cleanup*. The requirements mine states the
  same job as five failure modes — P13 an acquired thing whose owner does
  not cover every exit path, R-B12 unmount-while-active leaving listeners,
  StrictMode double-invoke doubling acquisition, cleanup skipped on a
  thrown render — and every one of them is a fact about a thing REACT
  CANNOT SEE. That is what makes the job different from every other host
  row: React reconciles elements, and a chart instance holding a `window`
  listener is not one.

  ## The recipe, in one sentence

  **One effect with empty deps owns the instance's whole lifetime; every
  other effect only ever TELLS it something.** [[spark-island]] is that
  sentence as code, and the two sabotage directions recorded on the PR are
  what say the sentence is load-bearing rather than decorative — the
  acquire effect's `#js []` is not a micro-optimisation, it is the
  idempotence.

  ## The doors, and the one thing that is a stand-in

  | Piece | Door | Shipped? |
  |---|---|---|
  | the wrapper component | `n/defcomponent` | yes |
  | the DOM node it owns | `n/$` with a `:ref` | yes |
  | the crossing into hiccup | `h/defhost` | yes |
  | the SDK's outward callback | `:callbacks {:on-pick :event}` + `h/hfn` | yes |
  | the throw/retry region | `h/error-boundary` `:fallback` / `:reset-key` | yes |
  | the vendor itself | [[new-spark]] | **a stand-in** |

  The vendor is the one thing this file builds, and it is built because
  the package may not take an npm dependency for a test. It is written to
  have the three properties that make a real editor/map/chart hard rather
  than the zero properties that make a mock easy:

  1. it is **constructed against a DOM node** and writes into that node
     directly, so React neither renders nor restores what it draws;
  2. it holds a **real `window` listener**, so a leaked instance keeps
     reacting to a real event — the leak is OBSERVABLE and not merely
     counted;
  3. it is **destroyed exactly once** — a second `destroy` is recorded
     rather than tolerated, because a real SDK throws there and a recipe
     that double-releases must not read as clean.

  ## Every census here can answer NON-EMPTY, and one row is spent proving it

  Every claim below is of the form *zero of these afterwards*, and a
  prohibition is trivially satisfied by an instrument that cannot count.
  Three instruments carry the file and each has an explicit positive
  control, all in [[the-census-can-answer-non-empty]]:

  | Census | Reads | Its positive control |
  |---|---|---|
  | [[!live]] | live instances the vendor knows about | reads 1 under one mount, 2 under two |
  | the `window` listener | reached by [[fire-external!]] | the external event dispatches an intent while mounted |
  | `inventory/residue` | the runtime's own ownership | non-zero BEFORE the teardown that zeroes it |

  The listener census is the one that had to be built this way. A ledger
  entry saying *a listener was registered* is a fact about bookkeeping; the
  claim is about a listener that FIRES, and the only honest instrument for
  it is to fire the event and watch an intent land. So no row asserts a
  registration count anywhere — [[fire-external!]] is the whole instrument,
  and [[the-census-can-answer-non-empty]] is what says it is connected.

  ## What is asserted, and the narrowing each row is written against

  | row | what it establishes | the one-line narrowing it catches |
  |---|---|---|
  | [[the-census-can-answer-non-empty]] | all three instruments are connected | any row below going green on an instrument that reads zero unconditionally |
  | [[one-mount-is-one-acquisition-and-every-update-is-told-not-rebuilt]] | idempotent acquisition | `#js [data]` on the acquire effect — perfectly BALANCED, and wrong |
  | [[strict-modes-double-invoke-is-a-pair-run-twice-not-a-leak]] | React's own adversary | acquiring in the render body, which double-invoke doubles |
  | [[a-remount-releases-then-acquires-and-the-instance-is-a-new-one]] | exact release at an ordinary exit | a cleanup that runs but releases the SUCCESSOR |
  | [[a-thrown-render-releases-and-the-reset-key-retry-acquires-afresh]] | the exit path nobody writes | `try`-less cleanup, or a release keyed off unmount alone |
  | [[after-teardown-the-outside-world-reaches-nothing]] | no stale-frame callbacks | a listener that outlives its component |
  | [[the-shells-hook-ledger-is-unmoved]] | the island's hooks are the ISLAND's | a wrapper's cost migrating into every boundary on the page |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described | a row that started returning early |

  ## Browser lane

  Every row needs a real document and a real React DOM: an effect is the
  entire mechanism under test and `react-dom/server` runs none. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`) and
  each row degrades there to a STATED skip rather than to a false green —
  the posture the other `*-dom` suites keep. [[the-shells-hook-ledger-is-unmoved]]
  is the one row that needs no fiber and states so."
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.roots-frames-support :as support]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]))

(def ^:private frame-id ::sdk)

;; Registered ABOVE `use-fixtures` — the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is EVALUATED, so a
;; registration written below it is erased before the first row runs.

(rf/reg-event ::seed (fn [_ _] {:db {:picks 0}}))
(rf/reg-event ::picked
              (fn [{:keys [db]} [_ v]]
                {:db (-> db (assoc :picked v) (update :picks inc))}))

(rf/reg-sub ::picks  (fn [db _] (:picks db 0)))
(rf/reg-sub ::picked (fn [db _] (:picked db)))

;; ---------------------------------------------------------------------------
;; The roster this file undertakes to reach
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  "A row that starts returning early, or a mechanism that stops being
  driven, fails the last deftest instead of quietly shrinking the
  evidence."
  #{:sdk/premise
    :sdk/idempotent-acquisition
    :sdk/strict-mode
    :sdk/remount
    :sdk/thrown-render-and-retry
    :sdk/no-stale-callback})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; THE VENDOR — the one stand-in, and why it is shaped the way it is
;; ---------------------------------------------------------------------------

(def ^:private external-event
  "The channel the vendor hears from OUTSIDE React. A real SDK's is a
  `ResizeObserver`, a socket message or an animation frame; the property
  that matters is that React's synthetic event system is not on the path,
  so a leaked instance is reachable by something React cannot intercept."
  "spark:pick")

(defonce ^:private !next-id (atom 0))

(def ^:private !live
  "id → instance, for every instance the vendor has constructed and not
  destroyed. THE census this file's zeros are measured against, and
  [[the-census-can-answer-non-empty]] is what says it can read more."
  (atom {}))

(defonce ^:private !acquired (atom 0))
(defonce ^:private !released (atom 0))

(def ^:private !double-destroys
  "A `destroy` on an already-destroyed instance. A real vendor throws
  there; recording it instead means a double release shows up as a
  NUMBER on the row that caused it rather than as an exception three
  frames away in React's effect runner."
  (atom 0))

(def ^:private !island-runs
  "[[spark-island]] body invocations, counted where the body runs. It says
  one thing only: whether StrictMode is engaged. A double RENDER is
  StrictMode's cheapest and most visible signature, so the row claiming
  something about the double-invoke of EFFECTS can state that premise
  independently of the counters it is about to assert on."
  (atom 0))

(defn- new-spark
  "`new Spark(node)` — the vendor's constructor, and the acquisition the
  whole file is about.

  Registers a `window` listener before it returns, exactly as a chart
  registers a resize handler, and writes into `node` with `textContent`
  — a write React neither performs nor repairs, so the node's text is
  evidence about the INSTANCE rather than about the render."
  [^js node]
  (let [id       (swap! !next-id inc)
        !pick    (atom nil)
        !dead    (atom false)
        listener (fn [^js e]
                   (when-not @!dead
                     (when-some [f @!pick]
                       (f (.. e -detail -value)))))
        sdk      #js {"instanceId" id
                      "onPick"     (fn [f] (reset! !pick f) nil)
                      "setData"    (fn [v]
                                     (when @!dead
                                       (throw (js/Error.
                                                (str "spark " id ": setData after destroy"))))
                                     (set! (.-textContent node) (str v))
                                     nil)
                      "destroy"    (fn []
                                     (if @!dead
                                       (swap! !double-destroys inc)
                                       (do (.removeEventListener js/window external-event listener)
                                           (reset! !dead true)
                                           (reset! !pick nil)
                                           (set! (.-textContent node) "")
                                           (swap! !live dissoc id)
                                           (swap! !released inc)))
                                     nil)}]
    (.addEventListener js/window external-event listener)
    (swap! !live assoc id sdk)
    (swap! !acquired inc)
    sdk))

(defn- force-release-all!
  "Destroy whatever a row left standing, between rows.

  It calls the vendor's OWN `destroy`, because that is the only thing
  that removes the window listener — a fixture that merely emptied
  [[!live]] would leave a previous row's leak firing into this one's
  reading. Runs before each row rather than after, so a row's own leak is
  measured inside the row that produced it."
  []
  (doseq [[_ ^js sdk] @!live] (.destroy sdk))
  (reset! !live {})
  (reset! !acquired 0)
  (reset! !released 0)
  (reset! !double-destroys 0)
  (reset! !island-runs 0))

;; ---------------------------------------------------------------------------
;; THE RECIPE — the wrapper, and the five hooks it spends
;; ---------------------------------------------------------------------------

(n/defcomponent spark-island
  "**The acquire/release recipe.** An ordinary React component that owns
  one foreign instance, written on the native tier because that is the
  tier with fibers, refs and effects — HD-020's ≤2-hook budget is a
  statement about Hicasso BOUNDARIES, and an island is not one.

  Five hooks, and the split between them IS the recipe:

  | hook | deps | what it is for |
  |---|---|---|
  | `useRef` × 2 | — | the node the vendor is constructed against, and the instance |
  | acquire/release | `#js []` | **the whole lifetime**, and nothing else |
  | re-register the callback | `#js [on-pick]` | TELLS the instance |
  | push the data | `#js [data]` | TELLS the instance |

  **The empty deps are the idempotence**, not a micro-optimisation. An
  intent callback lowered at an `:event` contract is fresh per render by
  design (`specification.md` §4.1), so an acquire effect that depended on
  its props would tear the chart down and rebuild it on every render of
  the page — balanced, leak-free, and catastrophic. That is the shape
  [[one-mount-is-one-acquisition-and-every-update-is-told-not-rebuilt]]
  exists to refuse.

  **The cleanup is the only release.** Not an unmount handler, not a
  `finally`, not a flag: React runs an effect's cleanup on unmount, on
  StrictMode's double-invoke, and when the subtree is deleted because
  something above it threw — which is the exit path nobody writes by
  hand, and the reason a recipe beats an ad-hoc teardown."
  [^js props]
  (swap! !island-runs inc)
  (let [node-ref (react/useRef nil)
        sdk-ref  (react/useRef nil)
        data     (.-data props)
        on-pick  (.-onPick props)]
    (react/useEffect
      (fn []
        (let [sdk (new-spark (.-current node-ref))]
          (set! (.-current sdk-ref) sdk)
          (fn []
            (.destroy ^js sdk)
            (set! (.-current sdk-ref) nil))))
      #js [])
    (react/useEffect
      (fn []
        (some-> ^js (.-current sdk-ref) (.onPick on-pick))
        js/undefined)
      #js [on-pick])
    (react/useEffect
      (fn []
        (some-> ^js (.-current sdk-ref) (.setData data))
        js/undefined)
      #js [data])
    (n/$ :div {:ref node-ref :class "spark"})))

;; ---------------------------------------------------------------------------
;; THE CROSSINGS — one declaration each, and hiccup uses them as heads
;; ---------------------------------------------------------------------------

(h/defhost spark
  "The declared host seam. `:on-pick` carries the `:event` contract, so an
  `h/hfn` written there sees the vendor's own arguments in order and a
  vector it returns dispatches under the frame of the boundary that wrote
  the crossing — which is what makes the SDK's out-of-React callback a
  re-frame2 intent rather than a closure over whatever `dispatch` happened
  to be in scope."
  spark-island
  {:callbacks {:on-pick :event}})

;; ---------------------------------------------------------------------------
;; The screens
;; ---------------------------------------------------------------------------
;;
;; EVERY screen reads `[::picks]`, and the read is not decoration — it is
;; what makes `inventory/residue` a live instrument here.
;;
;; The first draft of this file had no read anywhere: a chart takes its
;; data from a prop and the SDK owns everything else, so no body ever
;; called `h/sub`. Every `(is (= support/released (teardown-census! …)))`
;; then compared `{:cell-refs 0 :boundaries 0 :edges 0}` against a runtime
;; that had never owned anything — five assertions that could not fail,
;; and which a reader would have taken for teardown evidence. The premise
;; row caught it on the first run, at `(pos? (:boundaries before))`, which
;; is exactly what a positive control is for. A screen showing a count
;; beside its chart is also the more realistic screen.

(h/defview screen
  "One island, in one boundary. `:on-pick` is written FRESH on every
  render — the standing rule, and the reason the acquire effect may not
  depend on it."
  [{:keys [data]}]
  [:div
   [:span.picks (str (h/sub [::picks]))]
   [spark {:data data :on-pick (h/hfn [v] [::picked v])}]])

(h/defview two-screen
  "Two islands under one root — the positive control that says [[!live]]
  counts instances rather than answering a constant."
  [{:keys [data]}]
  [:div
   [:span.picks (str (h/sub [::picks]))]
   [spark {:data data :on-pick (h/hfn [v] [::picked v])}]
   [spark {:data (str data "-b") :on-pick (h/hfn [v] [::picked v])}]])

(h/defview toggle-screen
  "The island behind a `when`, with its identity under the caller's
  control. Dropping it is an ordinary unmount; changing `:key` is a keyed
  remount — two exits, one screen.

  `:key` in the PROPS MAP and not as `^{:key …}` metadata: hicasso reads
  no hiccup metadata anywhere, which the codec's own unkeyed-children
  warning states in as many words (*a key written as Reagent metadata is
  not read here*). Written the Reagent way here the metadata is inert, the
  crossing keeps its position, and the keyed-remount arm below silently
  measures nothing — observed, on the first run of this file."
  [{:keys [data show? instance]}]
  [:div
   [:span.picks (str (h/sub [::picks]))]
   (when show?
     [spark {:key instance :data data :on-pick (h/hfn [v] [::picked v])}])])

(h/defview detonator
  "A SIBLING of the island that throws during render once armed. A
  sibling and not the island itself, because a component that throws on
  its FIRST render never ran an effect and so has nothing to release —
  the assertion *zero live afterwards* would be true of a mount that
  never happened. Here the island is committed and live before the throw,
  which [[a-thrown-render-releases-and-the-reset-key-retry-acquires-afresh]]
  asserts before it arms anything."
  [{:keys [boom?]}]
  (if boom?
    (throw (js/Error. "planted imperative-sdk throw"))
    [:span.armed "armed"]))

(h/defview guarded-screen
  [{:keys [data boom? attempt]}]
  [:div
   [:span.picks (str (h/sub [::picks]))]
   [h/error-boundary {:fallback [:p.fell "fell"] :reset-key attempt}
    [:div
     [spark {:data data :on-pick (h/hfn [v] [::picked v])}]
     [detonator {:boom? boom?}]]]])

;; ---------------------------------------------------------------------------
;; Fixture and harness
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `nil` and not the default: a dynamic-var frame left in ambient
     ;; scope would let a crossing that failed to resolve its own frame
     ;; answer from that one instead.
     :ambient-frame nil
     ;; The MAP shape, because every row here is `async`.
     :async?        true
     :init-fn       (fn []
                      (support/leave-act-environment!)
                      (force-release-all!)
                      (collector/reset-runtime!))}))

(defn- seat! []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed])))

(defn- app-element
  "The frame provider over a root element — the shape `mount/root!`
  builds privately, spelled out here because these rows render
  CONCURRENTLY. `mount/root!` renders inside `flushSync`, which suits a
  witness that reads the DOM on the next line; an effect is passive and
  arrives later regardless, so every wait below is a poll on the
  condition and a synchronous flush would only be inventing a schedule
  for the one row where React deletes a subtree it is midway through."
  [hiccup]
  (mount/provider frame-id (codec/root-element frame-id hiccup)))

(defn- mount! [hiccup]
  (let [container (mount/fresh-container!)
        root      (react-dom-client/createRoot container)]
    (.render root (app-element hiccup))
    {:root root :container container :frame frame-id}))

(defn- strict-mount!
  "The same mount with React's own adversary wrapped around the WHOLE
  tree, which is where an application puts it and where this repo's other
  StrictMode witnesses put it (`kernel_commit_owns_dom_cljs_test`)."
  [hiccup]
  (let [container (mount/fresh-container!)
        root      (react-dom-client/createRoot container)]
    (.render root (react/createElement (.-StrictMode react) nil (app-element hiccup)))
    {:root root :container container :frame frame-id}))

(defn- rerender! [handle hiccup]
  (.render ^js (:root handle) (app-element hiccup))
  nil)

(defn- poll [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000}))

(defn- wait-live!
  "Return once exactly `n` instances are live.

  The wait is on the VENDOR's ledger and not on the DOM, because the
  whole subject is a thing React commits before it acquires: the node is
  in the document at the mutation phase and the instance does not exist
  until React flushes the passive effect. A row that started when its
  markup appeared would be reading the ledger before the acquisition it
  is asserting about."
  [n]
  (poll #(= n (count @!live)) (str n " live instance(s)")))

(defn- fire-external!
  "Drive the vendor from OUTSIDE React — a real `window` event, dispatched
  at the real `window`, which is the one instrument in this file for
  *is that listener still there*. `settle!` lets the intent's commit land
  so the caller may read on the next line."
  [value]
  (.dispatchEvent js/window
                  (js/CustomEvent. external-event #js {"detail" #js {"value" value}}))
  (mount/settle!)
  nil)

(defn- picks [] (rf/with-frame frame-id @(rf/subscribe [::picks])))
(defn- picked [] (rf/with-frame frame-id @(rf/subscribe [::picked])))

(defn- at [handle sel] (.querySelector ^js (:container handle) sel))
(defn- text-at [handle sel] (some-> (at handle sel) .-textContent))

(defn- all-text [handle sel]
  (mapv #(.-textContent ^js %)
        (array-seq (.querySelectorAll ^js (:container handle) sel))))

(defn- ids [] (set (keys @!live)))

(defn- balanced?
  "Every acquisition has been released or is still live — the invariant
  the whole matrix is written against, and the one statement that holds
  under StrictMode, a remount and a throw alike."
  []
  (= @!acquired (+ @!released (count @!live))))

(defn- skip! [why]
  (is true (str "an imperative-SDK claim needs a real React DOM — " why)))

(defn- report-failure!
  "Record `label` against THIS row and release its root; DELIBERATELY do
  not finish the row — the chain's single trailing step calls `done`.

  A rejection handler may not sit downstream of the `.then` that calls
  `done` (rf2-qpns): `run-block` hands `done` a continuation that runs the
  whole remainder of the run synchronously, so a `.catch` out there claims
  a LATER namespace's throw as this row's failure and calls `done` twice,
  which `run-browser-tests.cjs` promotes to fatal."
  [label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | live " (pr-str (ids))
                   " acquired " @!acquired " released " @!released))
    (when handle (mount/release! handle))
    nil))

;; ---------------------------------------------------------------------------
;; 1. THE PREMISE — every instrument in this file can answer NON-EMPTY
;; ---------------------------------------------------------------------------

(deftest the-census-can-answer-non-empty
  ;; Nothing below this row is worth anything without it. Every other row
  ;; asserts a ZERO, and a zero is free from an instrument that cannot
  ;; count: an `!live` that the wrapper never populated, a `fire-external!`
  ;; whose event name or `detail` shape nothing listens for, a residue
  ;; census read after the reset that empties it. Each of the three is
  ;; driven here until it reads more than nothing.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (do
        (seat!)
        (let [handle (mount! [two-screen {:data "seed"}])]
          (-> (wait-live! 2)
              (.then
                (fn [_]
                  (testing "CENSUS 1 — `!live` counts INSTANCES: two crossings
                            in one tree are two instances, with two distinct
                            ids, and the counters agree"
                    (is (= 2 (count @!live)))
                    (is (= 2 (count (ids))))
                    (is (= 2 @!acquired))
                    (is (= 0 @!released))
                    (is (balanced?)))

                  (testing "and each instance is really attached to its own
                            node — the vendor wrote `textContent` React never
                            renders, so this text is evidence about the
                            INSTANCE and not about the element"
                    (is (= ["seed" "seed-b"] (all-text handle ".spark"))))

                  (testing "CENSUS 2 — the `window` listener FIRES. A ledger
                            entry saying a listener was registered is a fact
                            about bookkeeping; this is the only instrument for
                            the claim, and every `nothing reaches it afterwards`
                            below is vacuous unless this reads more than zero"
                    (is (= 0 (picks)) "the premise starts from nothing")
                    (fire-external! "from-outside-react")
                    (is (= 2 (picks))
                        "two live instances, one external event, two intents")
                    (is (= "from-outside-react" (picked))
                        "and the value crossed the seam — the vendor's own
                         argument, through `h/hfn`, into app-db"))

                  (testing "CENSUS 3 — residue BEFORE the reset. `mount/release!`
                            empties every table by fiat, so a census taken after
                            it reads zeros whether the teardown released anything
                            or not; this is the reading that makes the zero below
                            it mean something"
                    (let [before (support/census)]
                      (is (pos? (:boundaries before)))
                      (is (= support/released (support/teardown-census! handle)))))

                  (testing "and the teardown released both instances — the
                            vendor's ledger and the runtime's agree"
                    (is (= 0 (count @!live)))
                    (is (= 2 @!released))
                    (is (= 0 @!double-destroys))
                    (is (balanced?)))

                  (exercised! :sdk/premise)
                  nil))
              (.catch (report-failure! "the premise" handle))
              ;; The single `done`, with nothing after it.
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 2. Idempotent acquisition — the row a BALANCED defect walks past
;; ---------------------------------------------------------------------------

(deftest one-mount-is-one-acquisition-and-every-update-is-told-not-rebuilt
  ;; The characteristic imperative-SDK defect is not a leak. It is a
  ;; wrapper that tears the chart down and builds a new one whenever a
  ;; prop moves: every acquisition released, every count balanced, the
  ;; screen correct throughout, and the editor losing its selection on
  ;; every keystroke. Only the CONSTRUCTION count says so, and only if
  ;; something re-renders the island for a reason the instance does not
  ;; care about — which is what the fresh-per-render intent callback
  ;; provides for free on every one of these renders.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (do
        (seat!)
        (let [handle      (mount! [screen {:data "v0"}])
              !id-at-mount (atom nil)]
          (-> (wait-live! 1)
              (.then
                (fn [_]
                  (reset! !id-at-mount (first (ids)))
                  (is (= 1 @!acquired) "the premise: exactly one acquisition")

                  (testing "four re-renders — two that move `:data` and two
                            that move nothing at all. Every one of them mints
                            a fresh `h/hfn` at `:on-pick`, which is the
                            standing rule and the exact input that would
                            defeat a dependency-bearing acquire effect"
                    (rerender! handle [screen {:data "v1"}])
                    (rerender! handle [screen {:data "v1"}])
                    (rerender! handle [screen {:data "v2"}])
                    (rerender! handle [screen {:data "v2"}]))
                  (poll #(= "v2" (text-at handle ".spark"))
                        "the last update reached the instance")))
              (.then
                (fn [_]
                  (testing "the instance is the one mount built. Narrowing
                            caught: `#js [data]` on the acquire effect —
                            balanced, leak-free, and it answers 3 here"
                    (is (= 1 @!acquired))
                    (is (= 0 @!released))
                    (is (= #{@!id-at-mount} (ids))))

                  (testing "and it was TOLD each update rather than rebuilt
                            for it — the vendor's own `textContent`, which
                            only `setData` writes"
                    (is (= "v2" (text-at handle ".spark"))))

                  (testing "the instance is live, not merely present: an
                            external event still reaches it"
                    (fire-external! "still-here")
                    (is (= 1 (picks))))

                  (testing "and teardown releases exactly the one thing
                            the mount acquired"
                    (is (= support/released (support/teardown-census! handle)))
                    (is (= 0 (count @!live)))
                    (is (= 1 @!released))
                    (is (= 0 @!double-destroys))
                    (is (balanced?)))

                  (exercised! :sdk/idempotent-acquisition)
                  nil))
              (.catch (report-failure! "idempotent acquisition" handle))
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 3. StrictMode — React's own adversary, run on the recipe
;; ---------------------------------------------------------------------------

(deftest strict-modes-double-invoke-is-a-pair-run-twice-not-a-leak
  ;; StrictMode runs mount → cleanup → mount over every effect precisely
  ;; to catch a resource acquired without a matching release. The recipe's
  ;; answer is that the acquisition is the effect's and the release is the
  ;; effect's cleanup, so the double-invoke is the pair run twice; a
  ;; wrapper that acquired in the RENDER body — the one thing an
  ;; imperative wrapper is most tempted to do, because the node is right
  ;; there — leaves two instances and one release, and paints identically.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (do
        (seat!)
        (let [handle (strict-mount! [screen {:data "sm"}])]
          (-> (wait-live! 1)
              (.then
                (fn [_]
                  (testing "the premise: StrictMode really is engaged, and it
                            really did run the acquire effect twice. A
                            production React build silently removes both, and
                            without them this row asserts nothing at all"
                    (is (< 1 @!island-runs)
                        (str "the wrapper body ran " @!island-runs
                             " time(s); StrictMode double-invokes render"))
                    (is (= 2 @!acquired)
                        (str "the acquire effect ran " @!acquired
                             " time(s); StrictMode runs mount/cleanup/mount"))
                    (is (= 1 @!released)))

                  (testing "and what survives is ONE instance, the second —
                            an acquire/release pair run twice, which is the
                            whole claim. Narrowing caught: acquiring in the
                            render body, which reads 2 live here and paints
                            perfectly"
                    (is (= 1 (count @!live)))
                    (is (= 0 @!double-destroys))
                    (is (balanced?)))

                  (testing "the survivor is the live one: the FIRST instance's
                            listener was removed with it, so one external event
                            dispatches exactly one intent and not two"
                    (fire-external! "sm-pick")
                    (is (= 1 (picks))))

                  (testing "and unmount releases exactly what the surviving
                            mount acquired"
                    (is (= support/released (support/teardown-census! handle)))
                    (is (= 0 (count @!live)))
                    (is (= 2 @!released))
                    (is (balanced?)))

                  (exercised! :sdk/strict-mode)
                  nil))
              (.catch (report-failure! "StrictMode" handle))
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 4. Remount — the ordinary exit, taken twice
;; ---------------------------------------------------------------------------

(deftest a-remount-releases-then-acquires-and-the-instance-is-a-new-one
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (do
        (seat!)
        (let [handle   (mount! [toggle-screen {:data "r0" :show? true :instance :a}])
              !first   (atom nil)
              !second  (atom nil)]
          (-> (wait-live! 1)
              (.then
                (fn [_]
                  (reset! !first (first (ids)))
                  ;; Dropped from the tree — the ordinary exit.
                  (rerender! handle [toggle-screen {:data "r0" :show? false :instance :a}])
                  (wait-live! 0)))
              (.then
                (fn [_]
                  (testing "dropped from the tree, the instance went with it"
                    (is (= 1 @!acquired))
                    (is (= 1 @!released))
                    (is (balanced?)))

                  (testing "and the outside world reaches nothing while it is
                            gone — the listener left with the instance"
                    (fire-external! "into-the-void")
                    (is (= 0 (picks))))

                  (rerender! handle [toggle-screen {:data "r1" :show? true :instance :a}])
                  (wait-live! 1)))
              (.then
                (fn [_]
                  (reset! !second (first (ids)))
                  (testing "put back, and it is a NEW instance rather than the
                            old one resurrected"
                    (is (= 2 @!acquired))
                    (is (not= @!first @!second)
                        "a resurrected instance would carry the old id")
                    (is (balanced?)))

                  ;; A KEYED remount is the same story at a different door:
                  ;; React deletes the fiber and builds another, so the recipe
                  ;; pays one release and one acquire without knowing a key
                  ;; changed.
                  (rerender! handle [toggle-screen {:data "r1" :show? true :instance :b}])
                  (poll #(= 3 @!acquired) "the keyed remount acquired")))
              (.then
                (fn [_]
                  (testing "the keyed remount released its predecessor and
                            acquired one successor"
                    (is (= 3 @!acquired))
                    (is (= 2 @!released))
                    (is (= 1 (count @!live)))
                    (is (= 0 @!double-destroys))
                    (is (balanced?)))

                  (testing "one live instance means one intent per external
                            event — a leaked predecessor would answer 2 here
                            while every count above stayed correct"
                    (fire-external! "keyed")
                    (is (= 1 (picks))))

                  (is (= support/released (support/teardown-census! handle)))
                  (is (= 0 (count @!live)))
                  (is (= 3 @!released))
                  (is (balanced?))
                  (exercised! :sdk/remount)
                  nil))
              (.catch (report-failure! "remount" handle))
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 5. The thrown render — the exit path nobody writes by hand
;; ---------------------------------------------------------------------------

(deftest a-thrown-render-releases-and-the-reset-key-retry-acquires-afresh
  ;; The failure mode the requirements mine names as *cleanup skipped on a
  ;; thrown render*, and the reason the recipe puts the release in an
  ;; effect cleanup rather than anywhere an author would think to put it.
  ;; React deletes the whole subtree under the boundary that caught the
  ;; throw, and running the deleted fibers' effect cleanups is part of the
  ;; deletion — so a wrapper that owns its instance through the effect gets
  ;; this path for free, and one that owns it any other way does not get it
  ;; at all.
  ;;
  ;; The order below is the load-bearing part: the island is asserted LIVE
  ;; before anything is armed. A component that threw on its first render
  ;; ran no effect and has nothing to release, so `zero live afterwards`
  ;; would be true of a mount that never happened — the exact shape of gate
  ;; that cannot go red.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (do
        (seat!)
        (let [handle    (mount! [guarded-screen {:data "g0" :boom? false :attempt 0}])
              !before   (atom nil)]
          (-> (wait-live! 1)
              (.then
                (fn [_]
                  (reset! !before (first (ids)))
                  (testing "THE PREMISE — the island is committed and live, and
                            its sibling has not thrown yet"
                    (is (= 1 @!acquired))
                    (is (= 0 @!released))
                    (is (= "armed" (text-at handle ".armed")))
                    (fire-external! "pre-throw")
                    (is (= 1 (picks)) "live, and reachable from outside React"))

                  (rerender! handle [guarded-screen {:data "g0" :boom? true :attempt 0}])
                  ;; TWO conditions, and the second is not redundant.
                  ;;
                  ;; The fallback appearing is a MUTATION-phase fact; running
                  ;; a deleted fiber's effect cleanups is a PASSIVE-phase one,
                  ;; and React flushes that later. Measured: a row that waited
                  ;; only for `.fell` read `!released` 0 with the instance
                  ;; still live and still answering external events — it would
                  ;; have reported a leak that is not there, and a sabotage of
                  ;; the release would have been indistinguishable from it. The
                  ;; wait is therefore on the release itself, exactly as the
                  ;; remount arm waits on `wait-live!`; a release that never
                  ;; happens times out here and names this label, which is the
                  ;; red the acceptance criterion asks for.
                  (poll #(and (some? (at handle ".fell"))
                              (zero? (count @!live)))
                        "the boundary caught, and the deleted subtree's cleanup ran")))
              (.then
                (fn [_]
                  (testing "the region fell back, and the deletion ran the
                            island's cleanup: the instance the throw destroyed
                            is the one that was live"
                    (is (= "fell" (text-at handle ".fell")))
                    (is (= 1 @!acquired) "no second acquisition anywhere in this")
                    (is (= 1 @!released))
                    (is (= 0 @!double-destroys))
                    (is (balanced?)))

                  (testing "and nothing survives the fall: the vendor's window
                            listener went with the instance, so an external
                            event reaches nobody"
                    (fire-external! "post-throw")
                    (is (= 1 (picks)) "still the one pick from before the throw"))

                  ;; The retry is the CALLER's: a changed `:reset-key` clears
                  ;; the caught failure and remounts the region.
                  (rerender! handle [guarded-screen {:data "g1" :boom? false :attempt 1}])
                  (wait-live! 1)))
              (.then
                (fn [_]
                  (testing "and the retry ACQUIRES AFRESH — a second instance,
                            with no contribution from the attempt that threw"
                    (is (= 2 @!acquired))
                    (is (= 1 @!released))
                    (is (not= @!before (first (ids))))
                    (is (balanced?)))

                  (testing "one instance means one intent, which is what says
                            the pre-throw instance is really gone rather than
                            merely unreferenced"
                    (fire-external! "post-retry")
                    (is (= 2 (picks))))

                  (testing "and the ordinary teardown still balances after all
                            of it"
                    (is (= support/released (support/teardown-census! handle)))
                    (is (= 0 (count @!live)))
                    (is (= 2 @!released))
                    (is (= 0 @!double-destroys))
                    (is (balanced?)))

                  (exercised! :sdk/thrown-render-and-retry)
                  nil))
              (.catch (report-failure! "thrown render" handle))
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 6. After teardown — no stale-frame callbacks, because there is no callee
;; ---------------------------------------------------------------------------

(deftest after-teardown-the-outside-world-reaches-nothing
  ;; `retaining_host_callbacks_dom_cljs_test` settles what happens when a
  ;; vendor CALLS a callback it kept past a teardown: core's own fence
  ;; refuses it rather than writing whoever occupies the frame's address
  ;; now. The imperative-SDK claim is one step earlier and stronger — the
  ;; owned thing is GONE, so there is nothing left to hold the callback and
  ;; nothing to refuse. This row is what says the recipe reaches that
  ;; stronger statement and does not merely rely on the fence.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (do
        (seat!)
        (let [handle (mount! [screen {:data "t0"}])]
          (-> (wait-live! 1)
              (.then
                (fn [_]
                  (testing "the premise: while it is up, the outside world
                            reaches it"
                    (fire-external! "while-up")
                    (is (= 1 (picks))))

                  (is (= support/released (support/teardown-census! handle)))

                  (testing "and after the teardown the same event reaches
                            nothing — no intent, and no refusal either,
                            because the listener is not there to be called"
                    (fire-external! "after-teardown")
                    (fire-external! "and-again")
                    (is (= 1 (picks)) "no further intent reached app-db")
                    (is (= "while-up" (picked)) "and the last value is the live one"))

                  (testing "the vendor's ledger agrees, and nothing was
                            released twice"
                    (is (= 0 (count @!live)))
                    (is (= 1 @!acquired))
                    (is (= 1 @!released))
                    (is (= 0 @!double-destroys))
                    (is (balanced?)))

                  (exercised! :sdk/no-stale-callback)
                  nil))
              (.catch (report-failure! "no stale callback" handle))
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 7. The fence — the island's hooks are the ISLAND's
;; ---------------------------------------------------------------------------

(deftest the-shells-hook-ledger-is-unmoved
  ;; The rule an optional capability may not break is that it does not add
  ;; a hook to EVERY boundary; a component spending its own is its own
  ;; affair. [[spark-island]] spends five, which is a fact about one
  ;; wrapper on one screen. The fence that holds is the boundary shell's
  ;; ledger, and it is asserted here — unmoved, and neither of its two
  ;; entries a `useRef` or a `useState` — so that the recipe's cost cannot
  ;; be confused with a change to what every boundary on the page pays.
  ;;
  ;; No fiber needed: both ledgers are declarations, read off the runtime.
  (testing "the context-fed shell still declares exactly its two hooks"
    (is (= [:use-context/frame :use-sync-external-store/subscription-epoch]
           collector/shell-hook-ledger))
    (is (= 2 (count collector/shell-hook-ledger))))
  (testing "and the frame-fed variant still declares exactly its one"
    (is (= [:use-sync-external-store/subscription-epoch]
           collector/frame-prop-shell-hook-ledger)))
  (testing "neither shell declares a ref or a state hook — HD-020(b)"
    (is (empty? (filter #(#{:use-ref :use-state} %)
                        (concat collector/shell-hook-ledger
                                collector/frame-prop-shell-hook-ledger))))))

;; ---------------------------------------------------------------------------
;; 8. The roster
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  (testing "every mechanism this file claims to drive was driven"
    (is (= declared-population (set/intersection declared-population @!exercised))
        (str "not exercised: "
             (pr-str (set/difference declared-population @!exercised))))))
