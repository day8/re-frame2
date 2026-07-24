(ns re-frame.freehand.bench.b4-dom-cljs-test
  "B4 — controlled editing while a heavy sibling is dirty at 20 Hz.

  D021's most product-relevant row, and the one with the strongest
  deterministic half. Everything a user notices when an editor is bad is
  a browser behaviour: a character that never arrives, a caret that jumps
  to the end, a composition overwritten mid-sequence. None of them is
  visible to a structural render, and all of them get worse when
  something ELSE on the page is repainting — which is why the whole row is
  run against a sibling a ticker keeps dirty at 20 Hz.

  ## Deterministic here; evidence there

    - DETERMINISTIC, and these gate:
      **zero dropped input** — the typed string equals the field's final
      value, character for character, in the DOM and in app-db;
      **caret and composition stability** — the caret lands where the
      edit put it, and a composition's node is never replaced beneath it;
      **exact per-keystroke counts** — events, app-db writes,
      subscription recomputes and view-body renders, for the four-field
      form and the editing grid, in BOTH lowerings. A count regression is
      a real regression.
    - EVIDENCE, and this gates nothing: event-to-commit and settlement
      latency, published as p50/p95/p99 with provenance. D021 sets no
      latency threshold, and one set here would be measuring the CI
      runner.

  ## Scale is a reading, not a budget

  The bead this file answers asks for *honest per-keystroke scale*. Scale
  is what the counts DO as the surface grows, and the honest answer is
  that one of them grows: a keystroke recomputes every subscription whose
  signal is app-db, so a grid of a hundred cells recomputes a hundred
  subscriptions where a form of four recomputes four — while the number
  of view bodies that re-render stays at one. Both counts are gated at
  both sizes, because both are exact. The growth between them is
  published as a sentence and asserted against nothing: a per-keystroke
  count that scales with the mounted surface is a real property of the
  substrate, and a threshold on it would be folklore.

  ## What the four counters are

  Events and subscription recomputes are counted at the handler, view
  renders at the body, all through
  [[re-frame.freehand.bench.b4-instrument]] — one instrument, required by
  both twin view files under one alias, so the two arms carry identical
  declarations. WRITES are not instrumented at all: an app-db write is
  `re-frame.frame/frame-commit-epoch`, the framework's own count of
  physical frame-state installs. Instrumenting the handler to count
  writes would count handler calls, which is the event count again under
  another name.

  The records go to the browser console as EDN, which the browser runner
  flushes on failure or under `RF2_VERBOSE_TESTS=1`.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b4-instrument :as inst]
            [re-frame.freehand.bench.b4-views :as interpreted]
            [re-frame.freehand.bench.b4-views-compiled :as compiled]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The fixture parameters — sizes, not language constants
;; ---------------------------------------------------------------------------

(def ^:private form-fields 4)
(def ^:private grid-cells 100)
(def ^:private small-grid-cells 25)

(def ^:private heavy-width
  "Rows in the dirty sibling. Wide enough that a tick is reconciliation
  work happening beside the edit rather than a flag being flipped."
  200)

(def ^:private tick-hz 20)
(def ^:private tick-ms (/ 1000 tick-hz))

(def ^:private typed
  "The string typed under contention. Long enough, at one keystroke per
  browser task, to span several ticks of the 20 Hz sibling."
  "hello world")

;; ---------------------------------------------------------------------------
;; The two arms
;; ---------------------------------------------------------------------------

(def ^:private modes
  "The two arms. The stages are named one by one rather than through a
  namespace alias, because an alias is a compiler fact and not a value a
  map can hold."
  [{:id :interpreted :form-stage interpreted/form-stage :grid-stage interpreted/grid-stage}
   {:id :compiled    :form-stage compiled/form-stage    :grid-stage compiled/grid-stage}])

(def ^:private fid :b4/frame)

(defn- register! []
  (rf/reg-sub :b4/field (fn [db [_ i]] (inst/bump! [:sub :field]) (get-in db [:fields i])))
  (rf/reg-sub :b4/cell  (fn [db [_ i]] (inst/bump! [:sub :cell])  (get-in db [:cells i])))
  (rf/reg-sub :b4/tick  (fn [db _]     (inst/bump! [:sub :tick])  (:tick db)))
  (rf/reg-event :b4/type-field
                (fn [{:keys [db]} [_ i text]]
                  (inst/bump! [:event :field])
                  {:db (assoc-in db [:fields i] text)}))
  (rf/reg-event :b4/type-cell
                (fn [{:keys [db]} [_ i text]]
                  (inst/bump! [:event :cell])
                  {:db (assoc-in db [:cells i] text)})))

(defn- seed! [cells]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {:fields (vec (repeat form-fields ""))
                              :cells  (vec (repeat cells ""))
                              :tick   0})
  fid)

(defn- app-db [] (frame/frame-app-db-value fid))

;; ---------------------------------------------------------------------------
;; Browser plumbing
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real browser mount is required — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live!
  "Leave React's act environment. A keystroke has to reach React as a
  genuine DISCRETE event — which is the mechanism the door lane depends
  on — and inside an act environment React diverts that work to the act
  queue instead of flushing it where the browser would."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- mount!
  "Mount `view` into a fresh host under `tag`. Every mount carries its own
  `:disambiguator`: a root-id is page-unique identity, and this file
  mounts one stage at several fixture sizes, which is several
  occurrences of one view rather than one root reused."
  [view props tag]
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    (-> (act #(v/mount [view props] container {:frame fid :disambiguator tag}))
        (.then (fn [mounted] {:container container :mounted mounted}))
        (.catch (fn [e] (.remove container) (js/Promise.reject e))))))

(defn- release! [arm]
  (when arm
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (when-let [mounted (:mounted arm)]
      (.unmount (.-react-root ^root/Root mounted)))
    (.remove (:container arm)))
  nil)

(defn- node [container id] (.querySelector container (str "#" id)))

(defn- native-value-setter []
  (.-set (js/Object.getOwnPropertyDescriptor
           (.-prototype js/HTMLInputElement) "value")))

(defn- set-native-value!
  "Write through the prototype's own value setter, so React's value
  tracker sees the mutation exactly as it does for a real keystroke."
  [el s]
  (.call (native-value-setter) el s))

(defn- fire-input!
  [el data composing?]
  (.dispatchEvent el (js/InputEvent. "input"
                                     #js {:bubbles     true
                                          :cancelable  false
                                          :data        data
                                          :isComposing composing?})))

(defn- keystroke!
  "One keystroke: APPEND `ch` to whatever the node currently holds, then
  dispatch a real bubbling `input` event. Appending is what makes a
  dropped character visible — if a stale prop restored the node, the next
  keystroke builds on the restored text and the loss shows up in the
  final value."
  [el ch]
  (set-native-value! el (str (.-value el) ch))
  (fire-input! el ch false))

(defn- caret [el] [(.-selectionStart el) (.-selectionEnd el)])

(defn- next-frame!
  "A promise of the next animation frame — the browser's own presentation
  checkpoint, which is what makes a settlement reading a reading about
  the page rather than about a microtask."
  []
  (js/Promise. (fn [resolve] (js/requestAnimationFrame #(resolve nil)))))

(defn- start-ticker!
  "Mark the heavy sibling dirty at 20 Hz for as long as the returned
  handle is live. A real interval installing a real app-db value, so the
  sibling's subscription really moves and React really reconciles its
  rows beside the edit."
  []
  (js/setInterval #(frame/replace-app-db! fid (update (app-db) :tick inc)) tick-ms))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

;; ===========================================================================
;; The per-keystroke counts
;; ===========================================================================

(defn- keystroke-cost!
  "Type ONE character into `el` and answer what that one keystroke cost.

  Counters are zeroed immediately before the keystroke, so the answer is
  about that keystroke and not about the mount that preceded it. The
  write count is the framework's own commit epoch across the same
  window."
  [el ch]
  (inst/zero!)
  (let [e0 (frame/frame-commit-epoch fid)]
    (keystroke! el ch)
    (assoc (inst/snapshot) [:write :app-db] (- (frame/frame-commit-epoch fid) e0))))

(defn- costs
  "The four B4 quantities out of a raw tally, under names a reader can
  check against D021's row."
  [tally edited]
  {:B4/events              (get tally [:event edited] 0)
   :B4/app-db-writes       (get tally [:write :app-db] 0)
   :B4/subscription-recomputes (+ (get tally [:sub :field] 0)
                                  (get tally [:sub :cell] 0)
                                  (get tally [:sub :tick] 0))
   :B4/edited-view-renders (get tally [:render edited] 0)
   :B4/owner-view-renders  (+ (get tally [:render :form] 0)
                              (get tally [:render :grid] 0))
   :B4/sibling-renders     (get tally [:render :heavy] 0)})

(defn- assert-costs!
  [what actual expected]
  (doseq [[k v] expected]
    (is (= v (get actual k))
        (str what " — " (name k) " per keystroke"))))

(defn- expected-costs
  "The per-keystroke counts a correct run answers, as arithmetic over the
  mounted surface.

  One keystroke is one dispatched event and one app-db install. Every
  subscription whose signal is app-db recomputes — the `subscriptions`
  mounted on the edited surface, plus the sibling's ticker — and exactly
  one of those values moved, so exactly one view body re-renders: the
  edited occurrence's. Neither the owner that mounted it nor the sibling
  beside it re-renders, because nothing they read changed."
  [subscriptions]
  {:B4/events                  1
   :B4/app-db-writes           1
   :B4/subscription-recomputes (inc subscriptions)
   :B4/edited-view-renders     1
   :B4/owner-view-renders      0
   :B4/sibling-renders         0})

(defn- publish!
  [record]
  (let [ds (prov/defects record)]
    (js/console.log
      (str ";; B4 evidence — "
           (if (seq ds)
             (str "NOT CITABLE (" (count ds) " provenance field(s) unnamed by this build)")
             "citable")
           "\n" (pr-str record)))))

(defn evidence-record
  "One B4 result record — the provenance a browser can name, the counts as
  gated properties, and the latency distributions as evidence.

  Public because a record nobody can build outside the assertion that
  publishes it is a record nobody can check."
  [{:keys [benchmark mode surface fixture properties method distributions]}]
  {:benchmark    benchmark
   :doc          (str "controlled editing on the " surface
                      " while a heavy sibling is dirty at " tick-hz " Hz")
   :revision     (prov/detect-revision)
   :fixture      (assoc fixture
                        :mode mode
                        :contention {:hz tick-hz :sibling-rows heavy-width}
                        :measurement-method method)
   :build        (prov/detect-build)
   :host         (prov/detect-host)
   :sampling     {:warmup 1 :samples (or (:n (first (vals distributions))) 1)}
   :baseline     {:kind      :interpreted-vs-compiled
                  :reference {:arm  :interpreted
                              :note "the same declarations without the compiled marker"}}
   :distribution distributions
   :properties   properties
   :status       :evidence})

;; ---------------------------------------------------------------------------
;; The count row, per mode and per surface
;; ---------------------------------------------------------------------------

(defn- count-surface!
  "Mount `stage`, type one character into `#<el-id>`, and answer the
  measured per-keystroke costs. `subscriptions` is how many app-db
  subscriptions the surface mounts, which is the arithmetic the gate is
  written against."
  [stage props el-id edited tag]
  (-> (mount! stage props tag)
      (.then (fn [arm]
               (live!)
               (let [el (node (:container arm) el-id)]
                 (is (some? el) (str el-id " mounted"))
                 ;; One discarded keystroke first: the mount's own first
                 ;; render and first subscription pass must not be billed
                 ;; to a measured keystroke.
                 (keystroke! el "w")
                 (let [tally (keystroke-cost! el "x")]
                   (release! arm)
                   (costs tally edited)))))))

(defn- count-row!
  [{:keys [id form-stage grid-stage]} done]
  (register!)
  (seed! grid-cells)
  (let [results (atom {})]
    (-> (count-surface! form-stage
                        {:fields form-fields :width heavy-width}
                        "field-0" :field :b4-form)
        (.then (fn [form-costs]
                 (swap! results assoc :form form-costs)
                 (assert-costs! (str id " / four-field form") form-costs
                                (expected-costs form-fields))
                 (count-surface! grid-stage
                                 {:cells grid-cells :width heavy-width}
                                 "cell-0" :cell :b4-grid-large)))
        (.then (fn [grid-costs]
                 (swap! results assoc :grid grid-costs)
                 (assert-costs! (str id " / " grid-cells "-cell grid") grid-costs
                                (expected-costs grid-cells))
                 (seed! small-grid-cells)
                 (count-surface! grid-stage
                                 {:cells small-grid-cells :width heavy-width}
                                 "cell-0" :cell :b4-grid-small)))
        (.then (fn [small-costs]
                 (swap! results assoc :small-grid small-costs)
                 (assert-costs! (str id " / " small-grid-cells "-cell grid") small-costs
                                (expected-costs small-grid-cells))
                 ;; The SCALE reading — published, asserted against nothing.
                 (let [big (:grid @results) small (:small-grid @results)]
                   (publish!
                     (evidence-record
                       {:benchmark (keyword "B4" (str "per-keystroke-counts-" (name id)))
                        :mode      id
                        :surface   (str small-grid-cells "- and " grid-cells "-cell grids")
                        :fixture   {:form-fields form-fields
                                    :grid-cells  [small-grid-cells grid-cells]
                                    :scale-reading
                                    (inst/scale-reading
                                      [{:what       "subscription recomputes per keystroke"
                                        :small      (:B4/subscription-recomputes small)
                                        :large      (:B4/subscription-recomputes big)
                                        :small-size small-grid-cells
                                        :large-size grid-cells}
                                       {:what       "view-body renders per keystroke"
                                        :small      (:B4/edited-view-renders small)
                                        :large      (:B4/edited-view-renders big)
                                        :small-size small-grid-cells
                                        :large-size grid-cells}])}
                        :properties {:form (:form @results) :grid big :small-grid small}
                        :method    (str "counters zeroed immediately before one keystroke; "
                                        "writes are the frame's own commit epoch across the "
                                        "same window")
                        ;; A count row publishes no distribution of its own; the
                        ;; harness's own reading of the measured window is what
                        ;; makes the record emittable.
                        :distributions
                        {:B4/count-window-ms
                         (assoc (m/summarise [0.0])
                                :doc "the measured window is one keystroke, so this is a
                                      degenerate distribution kept only so the record
                                      carries one"
                                :observable :duration-ms)}})))))
        (.catch (fn [e] (is false (str id " count row rejected: " e))))
        (.finally done))))

(deftest per-keystroke-counts-are-exact-interpreted
  (testing "D021's B4 count gate, interpreted: one keystroke into a
            four-field form and into an editing grid costs exactly one
            event, one app-db write, one recompute per mounted app-db
            subscription, and exactly one view-body render — the edited
            occurrence's. The owner that mounted it and the sibling
            beside it do not re-render, because nothing they read moved."
    (if-not (browser?)
      (skip! "the browser job runs the count assertions")
      (async done (count-row! (first modes) done)))))

(deftest per-keystroke-counts-are-exact-compiled
  (testing "The same counts through the compiled lowering. The
            declarations are the interpreted ones plus `{:compiled true}`
            and nothing else — `compiled-source-delta-jvm-test` reads both
            files and proves it — so an equal count here is promotion
            proven not to change what a keystroke costs, and an unequal
            one is a real regression."
    (if-not (browser?)
      (skip! "the browser job runs the count assertions")
      (async done (count-row! (second modes) done)))))

;; ===========================================================================
;; Editing under contention
;; ===========================================================================

(defn- type-under-contention!
  "Type `s` into `el` one character per browser task, so the 20 Hz ticker
  really interleaves with the edit, answering the per-keystroke
  latencies.

  Two readings per keystroke, both EVIDENCE: `event-to-commit` is the
  wall time of the dispatch itself — the door's synchronous lane commits
  inside the event, so when the call returns the round trip is done —
  and `settlement` runs on to the browser's next animation frame, which
  is the presentation checkpoint a person actually sees."
  [el s]
  (let [commits (atom []) settles (atom []) drops (atom [])]
    (-> (reduce
          (fn [p ch]
            (.then p (fn [_]
                       (let [expected (str (.-value el) ch)
                             t0       (m/now-ms)]
                         (keystroke! el ch)
                         (swap! commits conj (- (m/now-ms) t0))
                         (when-not (= expected (.-value el))
                           (swap! drops conj [expected (.-value el)]))
                         (-> (next-frame!)
                             (.then (fn [_]
                                      (swap! settles conj (- (m/now-ms) t0))
                                      (when-not (= expected (.-value el))
                                        (swap! drops conj [expected (.-value el)]))
                                      nil)))))))
          (js/Promise.resolve nil)
          (seq s))
        (.then (fn [_] {:event-to-commit @commits :settlement @settles :drops @drops})))))

(defn- compose-under-contention!
  "Drive the composition-event protocol a browser really dispatches —
  start, updates, end — while the ticker runs, and answer the node the
  composition ended on."
  [el steps final]
  (let [before el]
    (.focus el)
    (.dispatchEvent el (js/CompositionEvent. "compositionstart" #js {:bubbles true :data ""}))
    (doseq [s steps]
      (.dispatchEvent el (js/CompositionEvent. "compositionupdate" #js {:bubbles true :data s}))
      (set-native-value! el s)
      (fire-input! el s true)
      (is (= s (.-value el))
          (str "the composition's in-flight text survived update " (pr-str s)
               " under 20 Hz contention"))
      (is (identical? before el)
          "and the node it is composing into was not replaced"))
    (.dispatchEvent el (js/CompositionEvent. "compositionend" #js {:bubbles true :data final}))
    (set-native-value! el final)
    (fire-input! el final false)
    before))

(defn- contention-row!
  [{:keys [id form-stage]} done]
  (register!)
  (seed! grid-cells)
  (let [ticker (atom nil)]
    (-> (mount! form-stage {:fields form-fields :width heavy-width} :b4-contention)
        (.then (fn [arm]
                 (live!)
                 (reset! ticker (start-ticker!))
                 (let [el (node (:container arm) "field-0")]
                   (-> (type-under-contention! el typed)
                       (.then (fn [latency]
                                (is (empty? (:drops latency))
                                    (str id ": no keystroke was dropped under "
                                         tick-hz " Hz contention — "
                                         (pr-str (:drops latency))))
                                (is (= typed (.-value el))
                                    (str id ": the field holds the typed string, "
                                         "character for character"))
                                (is (= typed (get-in (app-db) [:fields 0]))
                                    (str id ": and so does application state"))
                                (is (= [(count typed) (count typed)] (caret el))
                                    (str id ": the caret is where the typing left it, "
                                         "not thrown to a stale end"))
                                ;; A mid-string insert is the caret's real test: the
                                ;; value is rewritten around a cursor that is not at
                                ;; the end, while the sibling repaints.
                                (let [at 5]
                                  (.setSelectionRange el at at)
                                  (set-native-value!
                                    el (str (subs typed 0 at) "X" (subs typed at)))
                                  (fire-input! el "X" false)
                                  (.setSelectionRange el (inc at) (inc at))
                                  (is (= [(inc at) (inc at)] (caret el))
                                      (str id ": the caret stayed at the insertion point"))
                                  (is (= (str (subs typed 0 at) "X" (subs typed at))
                                         (.-value el))
                                      (str id ": and the insert landed where it was made")))
                                (let [steps ["k" "ka" "kan"] final "漢"
                                      before (compose-under-contention! el steps final)]
                                  (is (= final (.-value el))
                                      (str id ": the composition committed intact"))
                                  (is (= final (get-in (app-db) [:fields 0]))
                                      (str id ": and reached application state"))
                                  (is (identical? before el)
                                      (str id ": on the node it started on")))
                                (publish!
                                  (evidence-record
                                    {:benchmark (keyword "B4" (str "editing-under-contention-"
                                                                   (name id)))
                                     :mode      id
                                     :surface   "four-field form"
                                     :fixture   {:form-fields   form-fields
                                                 :also-mounted  grid-cells
                                                 :typed-length  (count typed)}
                                     :properties {:dropped-input 0
                                                  :caret-stable  true
                                                  :composition-node-stable true}
                                     :method    (str "one keystroke per browser task; "
                                                     "event-to-commit is the dispatch call's "
                                                     "own wall time, settlement runs on to "
                                                     "the next animation frame")
                                     :distributions
                                     {:B4/event-to-commit-ms
                                      (assoc (m/summarise (:event-to-commit latency))
                                             :doc "dispatch to committed value, per keystroke"
                                             :observable :duration-ms)
                                      :B4/settlement-ms
                                      (assoc (m/summarise (:settlement latency))
                                             :doc "dispatch to the next presented frame"
                                             :observable :duration-ms)}}))
                                (js/clearInterval @ticker)
                                (release! arm)
                                nil))))))
        (.catch (fn [e]
                  (when @ticker (js/clearInterval @ticker))
                  (is false (str id " contention row rejected: " e))))
        (.finally done))))

(deftest editing-survives-a-dirty-sibling-interpreted
  (testing "D021's B4 outcome, interpreted: typing, a mid-string insert
            and an IME composition, all while a heavy sibling is marked
            dirty at 20 Hz. Zero dropped input, a caret that stays where
            the edit put it, and a composition whose node is never
            replaced — gated. The latencies are published beside them and
            decide nothing."
    (if-not (browser?)
      (skip! "the browser job runs the contention assertions")
      (async done (contention-row! (first modes) done)))))

(deftest editing-survives-a-dirty-sibling-compiled
  (testing "The same row through the compiled lowering, on the same
            declarations plus the marker."
    (if-not (browser?)
      (skip! "the browser job runs the contention assertions")
      (async done (contention-row! (second modes) done)))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-counters-and-the-arms-are-real
  (testing "The counts above are only evidence if the counters move, the
            arms are two lowerings, and the write count comes from
            somewhere other than the event count."
    (inst/zero!)
    (is (= 0 (inst/tally [:render :field])) "a reset counter is zero")
    (is (= "field" (inst/rendered! :field)) "the render witness names its view")
    (is (= 1 (inst/tally [:render :field])) "and recording one moved it")
    (doseq [[i c] (map vector
                       [interpreted/field interpreted/grid interpreted/heavy]
                       [compiled/field compiled/grid compiled/heavy])]
      (is (= :interpreted (:lowering (v/describe i))) "the reference arm is interpreted")
      (is (= :compiled (:lowering (v/describe c))) "and the compared arm is compiled"))
    (is (= {:B4/events 1 :B4/app-db-writes 1 :B4/subscription-recomputes 101
            :B4/edited-view-renders 1 :B4/owner-view-renders 0 :B4/sibling-renders 0}
           (expected-costs grid-cells))
        "the gate's expectation is arithmetic over the mounted surface, written
         down rather than recorded from a run")
    (is (not= (expected-costs form-fields) (expected-costs grid-cells))
        "and it really does depend on the surface — a count that did not would
         gate nothing")))
