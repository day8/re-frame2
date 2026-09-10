(ns re-frame.fresco.view-transition-dom-cljs-test
  "**A HOSTED `<ViewTransition>` AROUND FRESCO CHILDREN, UNDER REAL REACT
  19.3.0.** A WITNESS, not a feature: nothing here is a public surface, and
  nothing in `re-frame.fresco` requires it.

  `docs/design/fresco/product/lanes/react-compatibility-notes.md`
  §\"New host capabilities stay at the host edge\" asks for exactly one
  thing — *prove that a hosted ViewTransition can contain Fresco children
  without ownership or controlled-input breakage* — and could not ask it
  while the API was Canary. On 19.3.0 `ViewTransition` is an ordinary
  export of `react`, so it can be asked now.

  ## The ceiling is the first thing this file establishes, not the last

  React activates a view transition only for an update it schedules in a
  Transition lane. `useSyncExternalStore`-driven updates and updates
  inside `flushSync` are excluded, and **every ordinary re-frame2 view
  update is one or the other** —
  `re-frame.substrate.spine`'s `use-subscribe` factory hands React
  `useSyncExternalStore`, and `flush-render!` is `react-dom/flushSync`.
  [[an-ordinary-dispatch-under-a-hosted-view-transition-animates-nothing]]
  is therefore the file's first browser row and it is a NEGATIVE one: an
  app-db commit animates nothing, and putting `rf/dispatch` inside
  `startTransition` does not change that, because an external-store
  mutation cannot be a non-blocking Transition.

  **A negative row is worthless without a control that bites**, so that
  row carries one in its own body: a plain `useState` update inside
  `startTransition`, under the SAME host, in the SAME page, read through
  the SAME instrument. If the control does not animate, the instrument is
  broken and the zeros beside it mean nothing.

  ## The one honest path, and what it costs

  [[the-deferred-value-bridge-animates-and-a-descendant-that-reads-for-itself-bypasses-it]]
  is the bridge: an island reading `n/use-sub` and handing that value to
  `useDeferredValue` re-renders the deferred subtree in a Transition lane,
  which a `<ViewTransition>` around that subtree does animate. The cost is
  stated by the row rather than by prose — at the blocking commit the
  deferred subtree still shows the OLD projection while a descendant that
  called `n/use-sub` for itself already shows the NEW one. That is not a
  defect to be fixed; it is what deferring means, and a reader who wants
  one consistent frame must not put a self-reading descendant inside the
  deferred region.

  `useDeferredValue` compares by IDENTITY, so every deferred input here is
  a string. An input rebuilt each render defeats the mechanism while
  looking correct.

  ## Why this bead ships no recipe

  [[an-intent-raised-inside-an-animating-subtree-does-not-reach-app-db]] is
  the row that settles it. A click inside a subtree a hosted
  `<ViewTransition>` is animating LANDS ON THE DOM — a native listener on
  the same node fires — and produces NO app-db write: not late, not
  queued, not replayed once the animation ends, and with nothing on any
  console channel to say so. The identical click on a quiet document
  arrives in about a millisecond, and a direct `rf/dispatch` made while the
  same animation is live arrives in about six.

  So the composition holds for CONTENT and fails for INTERACTION, and a
  recipe that said \"wrap the region in a ViewTransition\" would ship a UI
  that silently discards what the user does to it.

  [[the-drop-window-is-reacts-suspended-commit-not-the-animation]] says
  WHERE, and the answer is not ours and not the animation. React gates its
  whole synthetic event system on one module-level flag that
  `commitBeforeMutationEffects` clears and only the mutation phase
  restores; under a view transition the mutation phase is the browser's
  `update` callback, so the flag stays cleared across an asynchronous gap
  and `dispatchEvent` discards everything delivered in it. That row puts
  two PLAIN React buttons in the tree, one inside the animating subtree
  and one outside it in the same root, and both are dropped in the same
  window while a native listener on the same node fires — so the loss is
  upstream of the collector, the intent lowering and the router alike, and
  the animating subtree is not the boundary. The window is the SUSPENDED
  COMMIT, not the animation: the identical clicks made once the `update`
  callback has run, with the transition still animating, all arrive.

  ## The instrument

  `document.startViewTransition` is wrapped and counted. It is preferred to
  React's `onUpdate` callback for the hosted rows because it does not
  depend on how a prop spelled in hiccup lowers, and it is the same
  instrument on every row, positive and negative. Where a row builds its
  element with `react/createElement` directly it reads `onUpdate` as well,
  and the two agree — **but only because [[staged-container!]] pins the
  subject inside the viewport.** React's ViewTransition callbacks are
  viewport-gated, and every row here mounts at the bottom of a
  ninety-namespace test report; the measurement and the consequence are on
  that helper, and it is the one thing in this file a reader would break by
  tidying.

  ## On the node lane this file states a skip, never a green

  `:node-test` has no DOM. Every DOM row degrades to an explicit skip. The
  declaration and `react-dom/server` rows need no DOM and run on both
  lanes. The real run is `npm run test:browser`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.checkpoint-support :as rf.fresco.checkpoint-support]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.native :as rf.fresco.native]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::view-transition)

(rf/reg-sub :vtw/label (fn [db _] (:label db)))
(rf/reg-sub :vtw/text (fn [db _] (:text db)))
(rf/reg-sub :vtw/clicks (fn [db _] (:clicks db)))

(rf/reg-event :vtw/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :vtw/set-label (fn [{:keys [db]} [_ v]] {:db (assoc db :label v)}))
(rf/reg-event :vtw/set-text (fn [{:keys [db]} [_ v]] {:db (assoc db :text v)}))
(rf/reg-event :vtw/click (fn [{:keys [db]} _] {:db (update db :clicks inc)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The declaration — arm (a)'s host, and the trap beside it
;; ---------------------------------------------------------------------------

(rf.fresco/defhost view-transition
  "React's `<ViewTransition>` as a DECLARED crossing, `:server :render`.

  `:render` is the only policy under which a crossing's CHILDREN reach the
  server response, and this host exists to be wrapped around content that
  must survive server rendering — so the policy is not a preference here,
  it is the whole point. [[the-declared-host-passes-its-children-through-on-the-server]]
  witnesses that `react-dom/server` really does render this element type as
  a pass-through; it is EXPECTED, but `:server :render` depends on it, so it
  is measured rather than assumed."
  (.-ViewTransition react)
  {:server :render})

(rf.fresco/defhost suspense
  "React's `<Suspense>`, declared, with its `:fallback` slot."
  (.-Suspense react)
  {:slots #{:fallback}})

;; ---------------------------------------------------------------------------
;; Fresco children — a controlled input among them, deliberately
;; ---------------------------------------------------------------------------

(rf.fresco/defview panel
  "The Fresco children that sit INSIDE the hosted `<ViewTransition>`. The
  controlled `:input` is the lane doc's other named hazard: a controlled
  field whose value is a subscription is exactly the shape a snapshot-and-
  replace animation could break, so it is here rather than in a sibling."
  [_]
  [:div {:id "vtw-children"}
   [:span {:id "vtw-label"} (rf.fresco/sub [:vtw/label])]
   [:input {:id       "vtw-input"
            :type     "text"
            :value    (rf.fresco/sub [:vtw/text])
            :on-input [:vtw/set-text ::rf.fresco/value]}]
   [:button {:id "vtw-button" :on-click [:vtw/click]}
    (str "clicks=" (rf.fresco/sub [:vtw/clicks]))]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "ViewTransition DOM witness needs a real React DOM — " why)))

(defn- seeded!
  []
  (rf.fresco.checkpoint-support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:vtw/seed {:label "A" :text "seed" :clicks 0}]))
  frame-id)

(defn- api-supported?
  []
  (and (rf.fresco.impl.mount/browser?)
       (fn? (.-startViewTransition js/document))))

(defn- sleep!
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) ms))))

(defn- style!
  "Install a stylesheet for the duration of one row and hand back its
  remover. Durations are short so a row does not wait out the UA's
  quarter-second default; the named group is what the assertions read."
  [css]
  (let [el (js/document.createElement "style")]
    (set! (.-textContent el) css)
    (.appendChild js/document.head el)
    (fn [] (.removeChild js/document.head el) nil)))

(defn- staged-container!
  "`fresh-container!`, PINNED TO THE TOP OF THE VIEWPORT — and this is
  load-bearing rather than cosmetic.

  **React's `<ViewTransition>` callbacks are VIEWPORT-GATED.** `onUpdate`
  is reached only when `measureViewTransitionHostInstancesRecursive`
  reports at least one host instance inside the viewport, and the browser
  animates far fewer pseudo-elements for one that is not. `fresh-container!`
  appends to `document.body`, and on this lane that is the bottom of a
  ninety-namespace `cljs-test-display` report — thousands of pixels below
  the fold.

  Measured on a bare React page, twice in each direction: with the element
  at the top of the viewport `startViewTransition` was called once,
  `onUpdate` fired once and five named pseudo-element animations ran; with
  a 5000-pixel spacer above it, `startViewTransition` was still called
  once, `onUpdate` fired ZERO times and only two named animations ran — and
  the DOM committed correctly either way.

  So a witness that skipped this would read a live mechanism as a dead one,
  and an application whose animating region is scrolled out of view gets
  the transition without the callback. That is worth knowing on its own
  account; here it is the difference between a second instrument and a
  permanent false negative."
  []
  (let [c (rf.fresco.impl.mount/fresh-container!)]
    (set! (.. ^js c -style -cssText)
          "position:fixed;top:0;left:0;background:#fff;")
    c))

(def ^:private fast-transitions
  "40 ms, so ten rows do not spend three seconds waiting for the UA
  default. Long enough that an interruption fired on the next macrotask
  still lands while the first animation is live."
  (str "::view-transition-group(*), ::view-transition-old(*), "
       "::view-transition-new(*) { animation-duration: 240ms; }"))

(defn- spy-transitions!
  "Wrap `document.startViewTransition` and record every transition React
  starts. The instrument every row reads, positive and negative.

  `restore` puts the property back the way it was found: `js-delete` where
  the method was inherited (the ordinary case — the own property this
  install creates is the shadow), assignment where the page already had one."
  []
  (let [own?  (.call (.-hasOwnProperty js/Object.prototype) js/document
                     "startViewTransition")
        orig  (.-startViewTransition js/document)
        calls (atom [])]
    (set! (.-startViewTransition js/document)
          (fn [arg]
            (let [t (.call orig js/document arg)]
              (swap! calls conj t)
              t)))
    {:calls   calls
     :restore (fn []
                (if own?
                  (set! (.-startViewTransition js/document) orig)
                  (js-delete js/document "startViewTransition"))
                nil)}))

(defn- vt-animations
  "The view-transition pseudo-element animations running right now. Read at
  `ready`, this is positive evidence that the browser is animating rather
  than that React merely asked it to."
  []
  (->> (js/document.getAnimations)
       (keep (fn [a] (when-some [e (.-effect ^js a)] (.-pseudoElement ^js e))))
       (filter #(re-find #"^::view-transition" %))
       vec))

(defn- awaited!
  "Wait up to `ms` for React to start a transition. Resolves with
  `{:started n :anims [...] :rejected m}` — `:anims` read at the first
  transition's `ready`, `:rejected` the number whose `finished` rejected,
  and the promise settles only once every transition it saw has settled, so
  the next row starts on a quiet document.

  A row that expects NOTHING calls this too: it waits out `ms` and resolves
  with `:started 0`, which is a bounded wait rather than a timeout failure."
  [{:keys [calls]} ms]
  (let [deadline (+ (js/Date.now) ms)
        !bad     (atom 0)]
    (-> (js/Promise.
          (fn [resolve]
            ((fn tick []
               (if (or (seq @calls) (>= (js/Date.now) deadline))
                 (resolve nil)
                 (js/setTimeout tick 5))))))
        (.then (fn [_]
                 (if-some [t (first @calls)]
                   (-> (.-ready ^js t)
                       (.then (fn [_] (vt-animations)))
                       (.catch (fn [_] [])))
                   (.then (sleep! 60) (fn [_] [])))))
        (.then (fn [anims]
                 (-> (js/Promise.all
                       (into-array (map #(.catch (.-finished ^js %)
                                                 (fn [_] (swap! !bad inc) nil))
                                        @calls)))
                     (.then (fn [_] {:started  (count @calls)
                                     :anims    anims
                                     :rejected @!bad}))))))))

(defn- mount-concurrent!
  "A concurrent root rendered WITHOUT `flushSync`. Deliberately not
  `mount/root!`, whose render is inside `flushSync` — a forced schedule is
  precisely what this file must not impose on the thing under test."
  [container element]
  (let [root (react-dom-client/createRoot container)]
    (.render root element)
    {:root root :container container :frame frame-id}))

(defn- node [handle id] (.querySelector ^js (:container handle) (str "#" id)))
(defn- text-of [handle id] (some-> (node handle id) .-textContent))
(defn- value-of [handle id] (some-> (node handle id) .-value))

(defn- ownership
  "The census with the render-phase entry cache projected out."
  []
  (dissoc (rf.fresco.test.runtime/residue) :entries))

(defn- poll
  ([pred label] (poll pred label 4000))
  ([pred label ms]
   (rf.test-support/poll-until pred {:label label :timeout-ms ms})))

(defn- timed-poll
  "`poll`, resolving with how many milliseconds the condition took. Turns a
  latency question into a number instead of a pass/fail, which is what a
  witness of a SCHEDULING claim needs."
  [pred label ms]
  (let [t0 (js/Date.now)]
    (.then (poll pred label ms) (fn [_] (- (js/Date.now) t0)))))

(defn- within
  "How long `pred` took to come true, or `nil` if it did not within `ms`.
  Unlike [[poll]] this NEVER rejects, so a row can measure a condition it
  does not yet know the answer to without the chain unwinding."
  [pred ms]
  (let [t0 (js/Date.now)]
    (.catch (.then (poll pred "within" ms) (fn [_] (- (js/Date.now) t0)))
            (fn [_] nil))))

(defn- teardown-census!
  [handle]
  (rf.fresco.impl.mount/unmount! handle)
  (.then (rf.fresco.test.runtime/quiesced!)
         (fn [_]
           (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                  (rf.fresco.test.runtime/residue))
               "teardown is exact: zero residue after quiescence")
           (rf.fresco.impl.mount/release! handle)
           nil)))

(defn- report-failure!
  "Record `label` against THIS row, release its root, and deliberately do
  NOT finish the row — the chain's single trailing `done` does that. The
  long-form account is on
  [[re-frame.fresco.activity-suspense-dom-cljs-test]]'s own
  `report-failure!`."
  [label handle undo]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | ownership " (pr-str (ownership))))
    (when undo (undo))
    (when handle (rf.fresco.impl.mount/release! handle))
    nil))

;; ---------------------------------------------------------------------------
;; 1. The declaration and the server half — no DOM, so both lanes run these
;; ---------------------------------------------------------------------------

(defn- server-html
  [hiccup]
  (react-dom-server/renderToString
    (rf.fresco.impl.mount/provider
      frame-id (rf.fresco.impl.codec/root-element frame-id hiccup))))

(deftest the-declared-host-passes-its-children-through-on-the-server
  (testing "a `:server :render` ViewTransition host renders its children into
            the response, and the bare `[:>]` escape DELETES them"
    (seeded!)
    (let [bare     (server-html [panel {}])
          hosted   (server-html [view-transition {} [panel {}]])
          escaped  (server-html [:> (.-ViewTransition react) {} [panel {}]])]

      ;; THE PREMISE, and it is the reason a zero below means what it says:
      ;; these children DO reach a server response when nothing wraps them.
      (is (re-find #"vtw-children" bare)
          "premise: the children render on the server with no wrapper at all")

      (is (re-find #"vtw-children" hosted)
          "the declared `:server :render` host is a pass-through — its
           children are in the response")
      (is (re-find #"vtw-label" hosted)
          "and so is the subscription-driven text inside them")

      ;; THE TRAP the lane doc names. `[:>]` carries no declaration in which
      ;; to say `:server :render`, so it takes the Client-only default and
      ;; the whole subtree is absent — not a ViewTransition defect, but the
      ;; failure a naive wrapping produces.
      (is (not (re-find #"vtw-children" escaped))
          "the raw `[:>]` escape omits its subtree on the server: wrapping
           existing content in one DELETES that content from the response"))))

(deftest the-host-declaration-is-the-element-type-itself
  (testing "under `:render` the head's type IS React's ViewTransition, so
            there is no gate and no type swap between server and client"
    (let [el (rf.fresco.impl.codec/root-element frame-id [view-transition {} [:div "x"]])]
      (is (identical? (.-ViewTransition react) (.-type ^js el))
          "premise for every hydration claim below: one element type
           everywhere"))))

;; ---------------------------------------------------------------------------
;; 2. THE CEILING — the negative row, with its control in the same body
;; ---------------------------------------------------------------------------

(def ^:private !set-local (atom nil))

(defn- ceiling-host
  "A hosted ViewTransition wrapping BOTH a Fresco boundary (app-db driven,
  through `useSyncExternalStore`) and a plain React `useState` span. One
  tree, one instrument, two schedules — which is what makes the control
  worth anything."
  [_]
  (let [[local set-local] (react/useState "L0")]
    (react/useEffect (fn [] (reset! !set-local set-local) js/undefined)
                     #js [set-local])
    (rf.fresco.impl.codec/root-element frame-id
      [view-transition {:name "vtw-ceiling"}
       [:div {:id "vtw-ceiling"}
        [panel {}]
        [:span {:id "vtw-local"} local]]])))

(unchecked-set ceiling-host "displayName" "vtw/ceiling-host")

(deftest an-ordinary-dispatch-under-a-hosted-view-transition-animates-nothing
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_      (seeded!)
            undo   (style! fast-transitions)
            handle (mount-concurrent! (staged-container!)
                                      (rf.fresco.impl.mount/provider
                                        frame-id (do (reset! !set-local nil) (react/createElement ceiling-host nil))))
            spy    (spy-transitions!)]
        (-> (poll #(and (= "A" (text-of handle "vtw-label")) (some? @!set-local))
                  "the hosted tree commits and its boundary subscribes")
            (.then
              (fn [_]
                (is (= {:cells 3 :cell-refs 3 :boundaries 1 :edges 3} (ownership))
                    "premise: one boundary owns the three keys its body read")
                (reset! (:calls spy) [])
                ;; ---- (i) an ORDINARY dispatch --------------------------
                (rf/with-frame frame-id (rf/dispatch [:vtw/set-label "B"]))
                (-> (poll #(= "B" (text-of handle "vtw-label"))
                          "the app-db commit reaches the DOM")
                    (.then (fn [_] (awaited! spy 300))))))
            (.then
              (fn [{:keys [started]}]
                (testing "AN ORDINARY DISPATCH ANIMATES NOTHING — the commit
                          landed (the premise above), and React started no
                          transition for it"
                  (is (= "B" (text-of handle "vtw-label")))
                  (is (zero? started)
                      "an app-db commit reaches React through
                       `useSyncExternalStore`, which React excludes from
                       view-transition activation"))
                (reset! (:calls spy) [])
                ;; ---- (ii) the same dispatch inside `startTransition` ----
                ;; THE ROUTER QUEUES. `rf/dispatch` returns before the event
                ;; runs, so the app-db write does not happen inside this
                ;; callback at all — the transition scope has closed by the
                ;; time the store mutates. That is the FIRST of the two
                ;; reasons this spelling is not a sound integration, and (iii)
                ;; takes the second.
                (react/startTransition
                  (fn [] (rf/with-frame frame-id (rf/dispatch [:vtw/set-label "C"]))))
                (-> (poll #(= "C" (text-of handle "vtw-label"))
                          "the startTransition-wrapped commit reaches the DOM")
                    (.then (fn [_] (awaited! spy 300))))))
            (.then
              (fn [{:keys [started]}]
                (testing "AND WRAPPING `rf/dispatch` IN `startTransition` DOES
                          NOT CHANGE THAT — the router queues, so the store
                          mutation lands after the transition scope has closed"
                  (is (= "C" (text-of handle "vtw-label")))
                  (is (zero? started)))
                (reset! (:calls spy) [])
                ;; ---- (iii) a SYNCHRONOUS commit inside `startTransition` ---
                ;; The queue is taken out of the argument: `dispatch-sync`
                ;; mutates app-db INSIDE the callback, so the store really does
                ;; change within the transition scope. It still animates
                ;; nothing, because an external-store mutation cannot be a
                ;; non-blocking Transition — the SECOND reason, and the one
                ;; that would survive any amount of work on the router.
                (react/startTransition
                  (fn [] (rf/with-frame frame-id (rf/dispatch-sync [:vtw/set-label "D"]))))
                (-> (poll #(= "D" (text-of handle "vtw-label"))
                          "the synchronous commit reaches the DOM")
                    (.then (fn [_] (awaited! spy 300))))))
            (.then
              (fn [{:keys [started]}]
                (testing "NOR DOES TAKING THE QUEUE OUT OF THE ARGUMENT — an
                          external-store mutation performed synchronously
                          INSIDE `startTransition` still activates nothing"
                  (is (= "D" (text-of handle "vtw-label")))
                  (is (zero? started)))
                (reset! (:calls spy) [])
                ;; ---- (iii) THE CONTROL, same host, same instrument ------
                (react/startTransition (fn [] (@!set-local "L1")))
                (-> (poll #(= "L1" (text-of handle "vtw-local"))
                          "the local-state transition reaches the DOM")
                    (.then (fn [_] (awaited! spy 1500))))))
            (.then
              (fn [{:keys [started anims]}]
                (testing "THE CONTROL BITES: a plain `useState` update inside
                          `startTransition`, under the SAME host and read
                          through the SAME instrument, DOES activate — so the
                          two zeros above are the ceiling and not a dead spy"
                  (is (pos? started)
                      "React started a view transition for the local-state
                       transition")
                  (is (seq anims)
                      "and the browser really animated it — view-transition
                       pseudo-elements were running at `ready`"))
                (undo)
                ((:restore spy))
                (teardown-census! handle)))
            (.catch (report-failure! "ceiling row" handle undo))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 3. THE ONE HONEST PATH — useDeferredValue over `n/use-sub`
;; ---------------------------------------------------------------------------

(def ^:private !bridge-updates (atom 0))

(defn- self-reader
  "A descendant that calls `n/use-sub` FOR ITSELF. It reads the store at the
  current epoch, so the deferral above it cannot reach it — which is the
  cost this bridge charges, stated as a row rather than as prose."
  [_]
  (react/createElement "span" #js {:id "vtw-self"} (rf.fresco.native/use-sub [:vtw/label])))

(unchecked-set self-reader "displayName" "vtw/self-reader")

(defn- bridge-island
  "The bridge. `n/use-sub` is `useSyncExternalStore`, so its own update is
  blocking; `useDeferredValue` schedules a SECOND render of the deferred
  value in a Transition lane, and that is the render `<ViewTransition>`
  animates.

  The deferred input is a string — identity-stable by value. An input
  rebuilt each render (a fresh map, a fresh vector) compares unequal every
  time and silently defeats the whole mechanism while looking correct."
  [_]
  (let [label    (rf.fresco.native/use-sub [:vtw/label])
        deferred (react/useDeferredValue label)]
    (react/createElement
      (.-ViewTransition react)
      #js {:name "vtw-bridge" :onUpdate (fn [] (swap! !bridge-updates inc))}
      (react/createElement "div" #js {:id "vtw-bridge"}
                           (react/createElement "span" #js {:id "vtw-deferred"} deferred)
                           (react/createElement self-reader nil)))))

(unchecked-set bridge-island "displayName" "vtw/bridge-island")

(deftest the-deferred-value-bridge-animates-and-a-descendant-that-reads-for-itself-bypasses-it
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_      (seeded!)
            undo   (style! fast-transitions)
            handle (mount-concurrent! (staged-container!)
                                      (rf.fresco.impl.mount/provider
                                        frame-id (react/createElement bridge-island nil)))
            spy    (spy-transitions!)
            !split (atom nil)]
        (reset! !bridge-updates 0)
        (-> (poll #(= "A" (text-of handle "vtw-deferred"))
                  "the island commits and both readers show the seed")
            (.then
              (fn [_]
                (is (= "A" (text-of handle "vtw-self"))
                    "premise: both readers agree before the update")
                (reset! (:calls spy) [])
                (rf/with-frame frame-id (rf/dispatch [:vtw/set-label "B"]))
                ;; The BLOCKING commit lands first. Catch the tree in that
                ;; window: the self-reader is already at the new epoch while
                ;; the deferred subtree still paints the old projection.
                (-> (poll #(= "B" (text-of handle "vtw-self"))
                          "the self-reading descendant takes the blocking commit")
                    (.then (fn [_]
                             (reset! !split {:deferred (text-of handle "vtw-deferred")
                                             :self     (text-of handle "vtw-self")})
                             (awaited! spy 1500))))))
            (.then
              (fn [{:keys [started anims]}]
                (testing "THE BRIDGE ACTIVATES — premise first: React started a
                          transition and told the boundary about it"
                  (is (pos? started)
                      "React started a view transition for the deferred render")
                  (is (pos? @!bridge-updates)
                      "and the ViewTransition boundary's own `onUpdate` fired —
                       the second instrument agrees with the first")
                  (is (seq anims)
                      "the browser animated it: view-transition pseudo-elements
                       were running at `ready`")
                  (is (some #(re-find #"vtw-bridge" %) anims)
                      "and they are THIS boundary's, by its declared name"))

                (testing "AND THE COST, measured rather than described: at the
                          blocking commit the deferred subtree still showed the
                          OLD projection while the descendant that called
                          `n/use-sub` for itself already showed the NEW one"
                  (is (= {:deferred "A" :self "B"} @!split)
                      "descendants that read for themselves BYPASS the
                       deferral — do not put one inside a deferred region and
                       expect one consistent frame"))

                (-> (poll #(= "B" (text-of handle "vtw-deferred"))
                          "the deferred subtree catches up")
                    (.then (fn [_]
                             (is (= "B" (text-of handle "vtw-self"))
                                 "and the two agree again once the transition
                                  has committed")
                             (undo)
                             ((:restore spy))
                             (teardown-census! handle))))))
            (.catch (report-failure! "bridge row" handle undo))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 4. ARM (a) — the Suspense reveal, with a controlled input inside
;; ---------------------------------------------------------------------------

(def ^:private !resolve-lazy (atom nil))

(def ^:private lazy-panel
  "A `react/lazy` whose loader this file resolves by hand — the shape
  `shadow.lazy` produces, with the network taken out so a row can decide
  when the reveal happens."
  (react/lazy
    (fn []
      (js/Promise.
        (fn [resolve]
          (reset! !resolve-lazy
                  (fn []
                    (resolve #js {:default
                                  (fn [_] (rf.fresco.impl.codec/root-element frame-id [panel {}]))}))))))))

(defn- reveal-tree
  []
  (rf.fresco.impl.codec/root-element frame-id
    [view-transition {:name "vtw-reveal"}
     [:div {:id "vtw-reveal"}
      [suspense {:fallback [:div {:id "vtw-skeleton" :aria-busy true} "SKELETON"]}
       [:> lazy-panel {}]]]]))

(deftest the-suspense-reveal-under-the-host-animates-and-the-controlled-input-survives
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_      (seeded!)
            undo   (style! fast-transitions)
            spy    (spy-transitions!)
            handle (mount-concurrent! (staged-container!)
                                      (rf.fresco.impl.mount/provider
                                        frame-id (reveal-tree)))]
        ;; `!resolve-lazy` is published by the loader React calls on
        ;; the suspending render, so it is nil until that happens; the
        ;; poll below waits for the fallback, which is that same event.
        (-> (poll #(some? (node handle "vtw-skeleton"))
                  "the lazy head suspends and the declared fallback paints")
            (.then
              (fn [_]
                (is (nil? (node handle "vtw-children"))
                    "premise: the content is genuinely not there yet")
                (reset! (:calls spy) [])
                (@!resolve-lazy)
                (-> (poll #(some? (node handle "vtw-children"))
                          "the chunk resolves and the content commits")
                    (.then (fn [_] (awaited! spy 1500))))))
            (.then
              (fn [{:keys [started anims]}]
                (testing "THE REVEAL ANIMATES — skeleton to content, under a
                          declared host, with Fresco children inside"
                  (is (nil? (node handle "vtw-skeleton"))
                      "premise: the fallback is gone")
                  (is (pos? started)
                      "React started a view transition for the Suspense reveal")
                  (is (seq anims)))

                (testing "AND THE CONTROLLED INPUT INSIDE IS UNBROKEN — its
                          value is the subscription's, and a keystroke still
                          round-trips through app-db rather than sticking"
                  (is (= "seed" (value-of handle "vtw-input"))
                      "the revealed input carries the subscribed value")
                  (is (= {:cells 3 :cell-refs 3 :boundaries 1 :edges 3} (ownership))
                      "and the revealed boundary owns exactly the keys its
                       body read — no ownership breakage across the reveal"))

                (rf/with-frame frame-id (rf/dispatch [:vtw/set-text "typed"]))
                (poll #(= "typed" (value-of handle "vtw-input"))
                      "the controlled input follows app-db after the reveal")))
            (.then
              (fn [_]
                (is (= "typed" (value-of handle "vtw-input")))
                (undo)
                ((:restore spy))
                (teardown-census! handle)))
            (.catch (report-failure! "suspense reveal row" handle undo))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 5. Interruption, intent routing and unmount
;; ---------------------------------------------------------------------------

(deftest a-second-update-mid-animation-interrupts-cleanly
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_       (seeded!)
            undo    (style! fast-transitions)
            handle  (mount-concurrent! (staged-container!)
                                       (rf.fresco.impl.mount/provider
                                         frame-id (do (reset! !set-local nil) (react/createElement ceiling-host nil))))
            spy     (spy-transitions!)]
        (-> (poll #(and (some? (node handle "vtw-button")) (some? @!set-local))
                  "the hosted tree commits")
            (.then
              (fn [_]
                (reset! (:calls spy) [])
                (react/startTransition (fn [] (@!set-local "L1")))
                (poll #(seq @(:calls spy)) "React starts the first transition")))
            (.then
              (fn [_]
                (is (= 1 (count @(:calls spy)))
                    "premise: exactly one transition is live")
                ;; A SECOND transition-lane update while the first animation is
                ;; running. Nothing else touches the tree in this window — the
                ;; intent case is the NEXT row, deliberately apart, because a
                ;; Fresco intent turns out to change the outcome.
                (.then (sleep! 20)
                       (fn [_]
                         (react/startTransition (fn [] (@!set-local "L2")))
                         (awaited! spy 3000)))))
            (.then
              (fn [{:keys [started rejected]}]
                (testing "INTERRUPTION IS CLEAN — every transition settles
                          rather than rejecting, and the second update gets its
                          own transition rather than being folded into the
                          first"
                  (is (>= started 2)
                      (str "two updates, two transitions — saw " started))
                  (is (zero? rejected)
                      (str "every transition settled rather than rejecting — "
                           started " started, " rejected " rejected")))
                ;; POLL, do not read. React DEFERS a second transition's
                ;; mutation until the first has finished, and `awaited!`
                ;; returns on the transitions it had SEEN when it started —
                ;; so a bare read here is a race the first draft lost, and it
                ;; lost it in the reassuring direction: `started` was already
                ;; 2 while the DOM still showed the first value.
                (poll #(= "L2" (text-of handle "vtw-local"))
                      "the tree settles on the LAST update, not a stale one")))
            (.then
              (fn [_]
                (is (= "L2" (text-of handle "vtw-local")))
                (undo)
                ((:restore spy))
                (teardown-census! handle)))
            (.catch (report-failure! "interruption row" handle undo))
            (.then (fn [_] (done))))))))

(def ^:private !quiet-latency
  "The control's measured latency, carried into the claim's message so the
  two numbers are reported side by side rather than one in isolation."
  (atom nil))

(def ^:private !busy-readings
  "What each door read while the animation was live."
  (atom nil))

(def ^:private !native-hits (atom 0))

(deftest an-intent-raised-inside-an-animating-subtree-does-not-reach-app-db
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_      (seeded!)
            undo   (style! fast-transitions)
            handle (mount-concurrent! (staged-container!)
                                      (rf.fresco.impl.mount/provider
                                        frame-id (do (reset! !set-local nil) (react/createElement ceiling-host nil))))
            spy    (spy-transitions!)
            !warn  (atom [])
            orig-w (.-warn js/console)]
        (reset! !native-hits 0)
        ;; React reports a cancelled transition on `console.warn`. Captured
        ;; because the row's last claim is that NOTHING is reported.
        (set! (.-warn js/console)
              (fn [& args] (swap! !warn conj (pr-str (vec args))) nil))
        (-> (poll #(and (some? (node handle "vtw-button")) (some? @!set-local))
                  "the hosted tree commits")
            (.then
              (fn [_]
                ;; THE CONTROL COMES FIRST, and it is not optional: a click
                ;; that fails to reach app-db DURING an animation says nothing
                ;; unless the same click reaches it when nothing is animating.
                ;; The first draft of this row had only the zero.
                (.click ^js (node handle "vtw-button"))
                (timed-poll #(= 1 @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks])))
                            "CONTROL: the same intent routes with no transition in flight"
                            4000)))
            (.then
              (fn [quiet-ms]
                (reset! !quiet-latency quiet-ms)
                (reset! (:calls spy) [])
                (react/startTransition (fn [] (@!set-local "L1")))
                (poll #(seq @(:calls spy)) "React starts a transition")))
            (.then
              (fn [_]
                (is (= 1 (count @(:calls spy)))
                    "premise: a transition is live at the moment of the click")
                ;; A NATIVE listener on the SAME node, so a zero on app-db can
                ;; be told apart from a click that never landed. This is the
                ;; premise the whole row turns on.
                (.addEventListener ^js (node handle "vtw-button") "click"
                                   (fn [_] (swap! !native-hits inc)))
                ;; TWO DOORS, and the discriminator is the point: a `.click`
                ;; goes through the rendered intent, while a direct
                ;; `rf/dispatch` goes to the router alone.
                (.click ^js (node handle "vtw-button"))
                (-> (within #(= 2 @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks]))) 3000)
                    (.then (fn [click-ms]
                             (rf/with-frame frame-id (rf/dispatch [:vtw/click]))
                             (-> (within #(<= 2 @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks]))) 3000)
                                 (.then (fn [disp-ms]
                                          {:click-ms click-ms :disp-ms disp-ms}))))))))
            (.then
              (fn [{:keys [click-ms disp-ms]}]
                (reset! !busy-readings {:click click-ms :dispatch disp-ms})

                (testing "THE PREMISE: the click really did land on the button
                          while the animation was live. Without this the rest
                          of the row is a zero with no meaning — and the
                          browser is exonerated by it, twice over: an isolated
                          page delivers both a native listener AND React's own
                          `onClick` throughout a live transition"
                  (is (= 1 @!native-hits)
                      "a native listener on the same node fired during the
                       animation"))

                (testing "THE ROUTER IS EXONERATED TOO — an app-db write made
                          by dispatching DIRECTLY, while the same animation is
                          live, arrives promptly"
                  (is (some? disp-ms)
                      (str "a direct `rf/dispatch` during the animation reached
                            app-db in " (pr-str disp-ms) " ms")))

                (testing "AND YET THE INTENT'S OWN WRITE NEVER ARRIVES. This is
                          the row's finding, and it is why this bead ships no
                          recipe: an interaction inside a subtree a hosted
                          `<ViewTransition>` is animating lands on the DOM and
                          produces no app-db write — not late, not queued, not
                          replayed after the animation.

                          WHERE it is lost is
                          [[the-drop-window-is-reacts-suspended-commit-not-the-animation]],
                          which was written against this row and narrows it:
                          the loss is React's, and the window is the SUSPENDED
                          COMMIT rather than the animation. What this row
                          rules out stands — the browser (the native listener
                          above), task scheduling (setTimeout, MessageChannel,
                          microtasks and rAF all fire within a few ms) and the
                          router (the direct dispatch above) — but read its
                          fourth rule-out narrowly: an isolated page's
                          `onClick` fires throughout an animation because a
                          click made THERE lands outside the window, and a
                          plain React `onClick` in THIS tree is dropped inside
                          it"
                  (is (nil? click-ms)
                      (str "the intent's write did not arrive within 3000 ms — "
                           "quiet-document control " @!quiet-latency " ms, "
                           "readings " (pr-str @!busy-readings))))

                (testing "AND IT IS SILENT: React reports nothing, so an
                          application would show no sign of the dropped
                          interaction"
                  (is (= [] @!warn)
                      (str "nothing on `console.warn` at this point — saw "
                           (pr-str @!warn))))

                ;; Give the settled document its chance, so "not late" is a
                ;; measurement rather than an assumption.
                (within #(<= 3 @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks]))) 3000)))
            (.then
              (fn [late-ms]
                (is (nil? late-ms)
                    (str "and it is still absent after the animation settles: "
                         "app-db reads "
                         @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks]))
                         " (control click + direct dispatch), never 3"))
                (set! (.-warn js/console) orig-w)
                (undo)
                ((:restore spy))
                (teardown-census! handle)))
            (.catch (fn [e]
                      (set! (.-warn js/console) orig-w)
                      ((report-failure! "intent-during-animation row" handle undo) e)))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 5b. WHERE THE WRITE IS LOST — React's SUSPENDED COMMIT, not the animation
;; ---------------------------------------------------------------------------

(def ^:private slow-transitions
  "600 ms. [[fast-transitions]] wants ten rows not to spend three seconds
  waiting; this row wants ONE animation that outlasts a frame by a wide
  margin, so that a click landing after the commit window has closed is
  still unambiguously a click into a LIVE animation. Opposite
  requirements, so a second duration rather than a shared one."
  (str "::view-transition-group(*), ::view-transition-old(*), "
       "::view-transition-new(*) { animation-duration: 600ms; }"))

(def ^:private !win-set-local (atom nil))
(def ^:private !win-inside-hits (atom 0))
(def ^:private !win-outside-hits (atom 0))
(def ^:private !win-native-hits (atom 0))
(def ^:private !win-in-window (atom nil))
(def ^:private !win-after-window (atom nil))

(defn- window-host
  "The ceiling host's shape plus the two doors that decide whose mechanism
  this is: a PLAIN React `<button onClick>` INSIDE the hosted
  `<ViewTransition>`, beside the Fresco boundary, and a second one OUTSIDE
  it in the same root.

  Neither plain button touches fresco, the collector or the router, so a
  zero on either is a statement about React and not about us; and the
  inside/outside pair is what tells 'the animating subtree' apart from
  'this React root is committing'."
  [_]
  (let [[local set-local] (react/useState "W0")]
    (react/useEffect (fn [] (reset! !win-set-local set-local) js/undefined)
                     #js [set-local])
    (react/createElement
      "div" #js {:id "vtw-window-root"}
      (react/createElement
        (.-ViewTransition react) #js {:name "vtw-window"}
        (react/createElement
          "div" #js {:id "vtw-window-subtree"}
          (rf.fresco.impl.codec/root-element frame-id [panel {}])
          (react/createElement
            "button"
            #js {:id      "vtw-inside"
                 :onClick (fn [_] (swap! !win-inside-hits inc))}
            "inside")
          (react/createElement "span" #js {:id "vtw-window-local"} local)))
      (react/createElement
        "button"
        #js {:id      "vtw-outside"
             :onClick (fn [_] (swap! !win-outside-hits inc))}
        "outside"))))

(unchecked-set window-host "displayName" "vtw/window-host")

(defn- spy-commit-window!
  "[[spy-transitions!]] plus the two readings this row turns on: WHEN
  `document.startViewTransition` was called, and WHEN React's own `update`
  callback ran. The gap between those two is the window under test, so it
  is measured rather than assumed.

  `!on-open` is an atom holding a zero-argument function or `nil`. It is
  invoked from a MICROTASK queued the instant `startViewTransition` is
  called, and cleared as it fires. That is what makes the window
  deterministic instead of a race against a 5 ms poll: the browser calls
  `update` no earlier than the next rendering opportunity, and a microtask
  queued inside the current task always runs first."
  [!on-open]
  (let [own?    (.call (.-hasOwnProperty js/Object.prototype) js/document
                       "startViewTransition")
        orig    (.-startViewTransition js/document)
        calls   (atom [])
        !opened (atom nil)
        !closed (atom nil)]
    (set! (.-startViewTransition js/document)
          (fn [arg]
            (reset! !opened (js/Date.now))
            (let [arg' (if (fn? arg)
                         (fn [] (reset! !closed (js/Date.now)) (arg))
                         (let [copy (js/Object.assign #js {} arg)
                               upd  (.-update ^js arg)]
                           (set! (.-update copy)
                                 (fn [] (reset! !closed (js/Date.now)) (upd)))
                           copy))
                  t    (.call orig js/document arg')]
              (swap! calls conj t)
              (when-some [f @!on-open]
                (reset! !on-open nil)
                (js/queueMicrotask f))
              t)))
    {:calls   calls
     :opened  !opened
     :closed  !closed
     :restore (fn []
                (if own?
                  (set! (.-startViewTransition js/document) orig)
                  (js-delete js/document "startViewTransition"))
                nil)}))

(deftest the-drop-window-is-reacts-suspended-commit-not-the-animation
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_        (seeded!)
            undo     (style! slow-transitions)
            handle   (mount-concurrent! (staged-container!)
                                        (rf.fresco.impl.mount/provider
                                          frame-id (do (reset! !win-set-local nil)
                                                       (react/createElement window-host nil))))
            !on-open (atom nil)
            spy      (spy-commit-window! !on-open)]
        (reset! !win-inside-hits 0)
        (reset! !win-outside-hits 0)
        (reset! !win-native-hits 0)
        (reset! !win-in-window nil)
        (reset! !win-after-window nil)
        (-> (poll #(and (some? (node handle "vtw-button"))
                        (some? (node handle "vtw-inside"))
                        (some? (node handle "vtw-outside"))
                        (some? @!win-set-local))
                  "the probe tree commits")
            (.then
              (fn [_]
                ;; THE CONTROL, all three doors at once: with nothing
                ;; animating the Fresco intent reaches app-db AND both plain
                ;; React buttons fire. Without it the three zeros below are a
                ;; dead tree rather than a finding.
                (.click ^js (node handle "vtw-button"))
                (.click ^js (node handle "vtw-inside"))
                (.click ^js (node handle "vtw-outside"))
                (timed-poll #(= 1 @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks])))
                            "CONTROL: every door works on a quiet document"
                            4000)))
            (.then
              (fn [quiet-ms]
                (is (= 1 @!win-inside-hits)
                    (str "control: a plain React `onClick` INSIDE the subtree "
                         "fires on a quiet document (intent took "
                         (pr-str quiet-ms) " ms)"))
                (is (= 1 @!win-outside-hits)
                    "control: a plain React `onClick` OUTSIDE it fires too")
                (reset! !win-inside-hits 0)
                (reset! !win-outside-hits 0)
                (reset! (:calls spy) [])
                (reset! !on-open
                        (fn []
                          ;; A native listener on the same node, so a zero on
                          ;; the two React doors can be told apart from a click
                          ;; that never landed.
                          (.addEventListener ^js (node handle "vtw-button") "click"
                                             (fn [_] (swap! !win-native-hits inc)))
                          (let [closed @(:closed spy)]
                            (.click ^js (node handle "vtw-button"))
                            (.click ^js (node handle "vtw-inside"))
                            (.click ^js (node handle "vtw-outside"))
                            (reset! !win-in-window
                                    {:closed  closed
                                     :native  @!win-native-hits
                                     :inside  @!win-inside-hits
                                     :outside @!win-outside-hits}))))
                (react/startTransition (fn [] (@!win-set-local "W1")))
                (poll #(some? @!win-in-window)
                      "React starts a transition and three clicks land inside its commit window"
                      4000)))
            (.then
              (fn [_]
                (let [{:keys [closed native inside outside]} @!win-in-window]
                  (testing "THE PREMISE: the clicks landed while React's commit
                            was SUSPENDED — `document.startViewTransition` had
                            been called and React's own `update` callback,
                            which is where the mutation phase runs, had not"
                    (is (nil? closed)
                        "React's view-transition `update` callback had not run at the moment of the click")
                    (is (= 1 native)
                        "and a native listener on the fresco button fired, so the click really landed"))
                  (testing "AND HERE IS WHERE THE WRITE IS LOST, AND IT IS NOT
                            OURS: a PLAIN React `onClick`, on a plain
                            `<button>`, is dropped in the same window — INSIDE
                            the animating subtree and OUTSIDE it alike. Neither
                            button touches fresco, the collector or the router,
                            so the loss is upstream of everything this library
                            owns; and the outside zero says the boundary is the
                            COMMIT, not the animating subtree.

                            React gates its whole synthetic event system on one
                            module-level flag: `commitBeforeMutationEffects`
                            clears it and only the mutation phase restores it.
                            Ordinarily those two are a few microseconds apart
                            in one task. Under a view transition the mutation
                            phase is React's `update` callback, which the
                            browser calls after it has captured the old
                            snapshot — so the flag stays cleared across an
                            asynchronous gap, and `dispatchEvent` discards
                            every event delivered in it. Not queued, not
                            replayed, and nothing on any console channel"
                    (is (zero? inside)
                        "a plain React `onClick` INSIDE the animating subtree did not fire")
                    (is (zero? outside)
                        "nor did the one OUTSIDE it — so the animating subtree is not the boundary")))
                ;; Past this point the browser is really animating, which is
                ;; also past the point where React restored its event system.
                (poll #(seq (vt-animations))
                      "the browser is animating the transition"
                      4000)))
            (.then
              (fn [_]
                (let [before @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks]))]
                  (is (some? @(:closed spy))
                      "premise for the second half: React's `update` callback has now run")
                  (.click ^js (node handle "vtw-inside"))
                  (.click ^js (node handle "vtw-outside"))
                  (.click ^js (node handle "vtw-button"))
                  (-> (within #(< before @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks]))) 2000)
                      (.then (fn [ms] {:before    before
                                       :ms        ms
                                       :anims     (count (vt-animations))
                                       :window-ms (when-some [c @(:closed spy)]
                                                    (- c @(:opened spy)))}))))))
            (.then
              (fn [{:keys [ms anims] :as readings}]
                (reset! !win-after-window readings)
                (testing "AND THE SAME THREE CLICKS, MADE WHILE THE SAME
                          TRANSITION IS STILL ANIMATING but after React's
                          `update` callback has run, ALL ARRIVE. So the drop
                          window is the SUSPENDED COMMIT — one frame at the
                          START of a transition — and not the animation, which
                          is the distinction
                          [[an-intent-raised-inside-an-animating-subtree-does-not-reach-app-db]]
                          could not draw from one click"
                  (is (= 1 @!win-inside-hits)
                      "the plain React `onClick` inside the subtree fires again once the commit has flushed")
                  (is (= 1 @!win-outside-hits)
                      "and so does the one outside it")
                  (is (some? ms)
                      (str "the fresco intent's own write reached app-db in "
                           (pr-str ms) " ms with " (pr-str anims)
                           " view-transition animations still running")))
                ;; AND THE ROUTER, RE-MEASURED WITH THE PHASE ASSERTED rather
                ;; than inferred. `an-intent-raised-inside-an-animating-subtree-
                ;; does-not-reach-app-db` makes its direct-dispatch reading
                ;; only after a 3000 ms `within` has run to exhaustion over a
                ;; 240 ms animation, so that reading is post-transition and
                ;; eliminates nothing (rf2-hk8c). This one dispatches with
                ;; view-transition animations demonstrably running and reads
                ;; the count again at arrival, so the phase is a measurement
                ;; at both ends.
                (let [live (count (vt-animations))]
                  (is (pos? live)
                      "premise: view-transition animations are running at the moment of the direct dispatch")
                  (rf/with-frame frame-id (rf/dispatch [:vtw/set-label "MID"]))
                  (-> (within #(= "MID" @(rf/with-frame frame-id (rf/subscribe [:vtw/label])))
                              2000)
                      (.then
                        (fn [direct-ms]
                          (let [at-arrival (count (vt-animations))]
                            (swap! !win-after-window assoc
                                   :direct-ms          direct-ms
                                   :anims-at-dispatch  live
                                   :anims-at-arrival   at-arrival)
                            (is (some? direct-ms)
                                (str "a direct `rf/dispatch` reached app-db in "
                                     (pr-str direct-ms) " ms, dispatched with "
                                     (pr-str live) " view-transition animations "
                                     "running and " (pr-str at-arrival)
                                     " still running when it landed"))
                            nil)))))))
            (.then
              (fn [_] (awaited! spy 2000)))
            (.then
              (fn [_]
                (testing "AND THE CLICK MADE INSIDE THE WINDOW IS GONE FOR
                          GOOD: once every transition has settled app-db
                          carries the control write and the post-window write
                          and nothing else. React discards a dropped event
                          rather than queueing it, so there is nothing to
                          replay and nothing to wait for"
                  (is (= 2 @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks])))
                      (str "app-db reads "
                           @(rf/with-frame frame-id (rf/subscribe [:vtw/clicks]))
                           " (control + post-window), never 3 — readings "
                           (pr-str {:in-window    @!win-in-window
                                    :after-window @!win-after-window}))))
                (undo)
                ((:restore spy))
                (teardown-census! handle)))
            (.catch (fn [e]
                      ((:restore spy))
                      ((report-failure! "commit-window row" handle undo) e)))
            (.then (fn [_] (done))))))))

(def ^:private !win-direct (atom nil))

(deftest a-direct-dispatch-made-inside-that-window-still-reaches-app-db
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_        (seeded!)
            undo     (style! slow-transitions)
            handle   (mount-concurrent! (staged-container!)
                                        (rf.fresco.impl.mount/provider
                                          frame-id (do (reset! !win-set-local nil)
                                                       (react/createElement window-host nil))))
            !on-open (atom nil)
            spy      (spy-commit-window! !on-open)
            !noise   (atom [])
            orig-w   (.-warn js/console)
            orig-e   (.-error js/console)]
        (reset! !win-direct nil)
        (-> (poll #(and (some? (node handle "vtw-button")) (some? @!win-set-local))
                  "the probe tree commits")
            (.then
              (fn [_]
                (reset! (:calls spy) [])
                ;; React reports a flush made during its own commit on
                ;; `console.error`. Captured rather than asserted about: what
                ;; this row measures is whether the WRITE lands, and React's
                ;; opinion of the flush is a separate question this row has no
                ;; standing to settle. It is reported in the message so the
                ;; number is never read without it.
                (set! (.-warn js/console)
                      (fn [& args] (swap! !noise conj [:warn (pr-str (vec args))]) nil))
                (set! (.-error js/console)
                      (fn [& args] (swap! !noise conj [:error (pr-str (vec args))]) nil))
                (reset! !on-open
                        (fn []
                          (let [closed @(:closed spy)]
                            (rf/with-frame frame-id (rf/dispatch [:vtw/set-label "IW"]))
                            (reset! !win-direct {:closed closed}))))
                (react/startTransition (fn [] (@!win-set-local "W1")))
                (poll #(some? @!win-direct)
                      "React starts a transition and a direct dispatch is made inside its commit window"
                      4000)))
            (.then
              (fn [_]
                (is (nil? (:closed @!win-direct))
                    "premise: the dispatch was made with React's commit SUSPENDED — its `update` callback had not run")
                (within #(= "IW" @(rf/with-frame frame-id (rf/subscribe [:vtw/label]))) 3000)))
            (.then
              (fn [ms]
                (set! (.-warn js/console) orig-w)
                (set! (.-error js/console) orig-e)
                (testing "THE ROUTER IS NOT THE MECHANISM, AND THIS IS THE
                          CONTROL THAT SAYS SO FROM INSIDE THE WINDOW ITSELF:
                          a direct `rf/dispatch` made in the same suspended
                          commit in which a click is discarded DOES reach
                          app-db. So what the window stops is React's EVENT
                          DELIVERY and not an app-db write — two candidates
                          [[an-intent-raised-inside-an-animating-subtree-does-not-reach-app-db]]
                          could not separate, because the direct dispatch it
                          makes runs only after its own 3000 ms `within` has
                          gone to exhaustion over a 240 ms animation, which
                          puts that reading after the transition rather than
                          inside it (rf2-hk8c)"
                  (is (some? ms)
                      (str "a direct dispatch made inside the commit window reached app-db in "
                           (pr-str ms) " ms; console traffic during the window was "
                           (pr-str @!noise))))
                (awaited! spy 2000)))
            (.then
              (fn [_]
                (undo)
                ((:restore spy))
                (teardown-census! handle)))
            (.catch (fn [e]
                      (set! (.-warn js/console) orig-w)
                      (set! (.-error js/console) orig-e)
                      ((:restore spy))
                      ((report-failure! "in-window direct dispatch row" handle undo) e)))
            (.then (fn [_] (done))))))))

(deftest an-unmount-during-the-animation-leaves-no-residue
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_      (seeded!)
            undo   (style! fast-transitions)
            handle (mount-concurrent! (staged-container!)
                                      (rf.fresco.impl.mount/provider
                                        frame-id (do (reset! !set-local nil) (react/createElement ceiling-host nil))))
            spy    (spy-transitions!)
            !errs  (atom [])
            on-err (fn [e] (swap! !errs conj (str "window: " (.-message ^js e))))]
        (.addEventListener js/window "error" on-err)
        (-> (poll #(and (= "A" (text-of handle "vtw-label")) (some? @!set-local))
                  "the hosted tree commits")
            (.then
              (fn [_]
                (reset! (:calls spy) [])
                (react/startTransition (fn [] (@!set-local "L1")))
                (poll #(seq @(:calls spy)) "React starts a transition")))
            (.then
              (fn [_]
                (is (= 1 (count @(:calls spy)))
                    "premise: a transition is live at the moment of teardown")
                ;; Tear the root down WHILE the animation is running.
                (rf.fresco.impl.mount/unmount! handle)
                (awaited! spy 2000)))
            (.then
              (fn [_]
                (.removeEventListener js/window "error" on-err)
                (testing "UNMOUNT DURING AN ANIMATION IS CLEAN"
                  (is (= [] @!errs)
                      "React reported nothing to the window error channel")
                  (is (nil? (node handle "vtw-children"))
                      "premise: the subtree really did come down"))
                (.then (rf.fresco.test.runtime/quiesced!)
                       (fn [_]
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rf.fresco.test.runtime/residue))
                             "and it left no ownership behind")
                         (undo)
                         ((:restore spy))
                         (rf.fresco.impl.mount/release! handle)
                         nil))))
            (.catch (report-failure! "unmount row" handle undo))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 6. prefers-reduced-motion — React does not honour it; CSS must
;; ---------------------------------------------------------------------------

(deftest react-does-not-honour-reduced-motion-and-css-is-what-stops-the-animation
  (async done
    (if-not (api-supported?)
      (do (skip! (if (rf.fresco.impl.mount/browser?)
                   "this browser has no View Transition API"
                   ":node-test has no DOM"))
          (done))
      (let [_      (seeded!)
            ;; What a `@media (prefers-reduced-motion: reduce)` block would
            ;; contain. Installed unconditionally, because the claim is about
            ;; the MECHANISM — this environment reports `no-preference`, and a
            ;; row that waited for a real reduced-motion browser would never
            ;; run anywhere.
            undo   (style! (str fast-transitions
                                " ::view-transition-group(*),"
                                " ::view-transition-old(*),"
                                " ::view-transition-new(*)"
                                " { animation: none !important; }"))
            handle (mount-concurrent! (staged-container!)
                                      (rf.fresco.impl.mount/provider
                                        frame-id (do (reset! !set-local nil) (react/createElement ceiling-host nil))))
            spy    (spy-transitions!)]
        (-> (poll #(some? @!set-local) "the hosted tree commits")
            (.then
              (fn [_]
                (reset! (:calls spy) [])
                (react/startTransition (fn [] (@!set-local "L1")))
                (awaited! spy 1500)))
            (.then
              (fn [{:keys [started anims]}]
                (testing "REACT'S ACTIVATION IS UNCHANGED BY THE CSS — it does
                          not consult `prefers-reduced-motion`, so an
                          application that wants motion suppressed must write
                          the CSS itself"
                  (is (pos? started)
                      "React started the transition anyway")
                  (is (= "L1" (text-of handle "vtw-local"))
                      "premise: the update committed"))

                (testing "AND THE CSS IS WHAT STOPS THE MOTION — with the rule
                          in place no old/new pseudo-element animation runs"
                  (is (empty? (filter #(re-find #"^::view-transition-(old|new)\(" %) anims))
                      (str "no old/new pseudo animations under `animation: none` — saw "
                           (pr-str anims))))
                (is (false? (.-matches (js/window.matchMedia "(prefers-reduced-motion: reduce)")))
                    "recorded for the reader: this environment reports
                     no-preference, so the row above is about the mechanism
                     rather than about the media query being live")
                (undo)
                ((:restore spy))
                (teardown-census! handle)))
            (.catch (report-failure! "reduced-motion row" handle undo))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 7. A browser WITHOUT the View Transition API
;; ---------------------------------------------------------------------------

(deftest a-browser-without-the-view-transition-api-still-commits
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            handle (mount-concurrent! (staged-container!)
                                      (rf.fresco.impl.mount/provider
                                        frame-id (react/createElement bridge-island nil)))
            !errs  (atom [])
            on-err (fn [e] (swap! !errs conj (str "window: " (.-message ^js e))))
            hide!  (fn []
                     (js/Object.defineProperty
                       js/document "startViewTransition"
                       #js {:value js/undefined :configurable true :writable true}))
            show!  (fn [] (js-delete js/document "startViewTransition"))]
        (reset! !bridge-updates 0)
        (.addEventListener js/window "error" on-err)
        (-> (poll #(= "A" (text-of handle "vtw-deferred")) "the island commits")
            (.then
              (fn [_]
                (hide!)
                (is (undefined? (.-startViewTransition js/document))
                    "premise: to React this document has no View Transition API")
                (rf/with-frame frame-id (rf/dispatch [:vtw/set-label "B"]))
                (poll #(= "B" (text-of handle "vtw-deferred"))
                      "the deferred subtree still reaches its new value")))
            (.then
              (fn [_]
                (.then (sleep! 120)
                       (fn [_]
                         (.removeEventListener js/window "error" on-err)
                         (show!)
                         (testing "NO API, NO ANIMATION, NO BREAKAGE — the
                                   commit lands, nothing throws, and the
                                   boundary's `onUpdate` never fires"
                           (is (= "B" (text-of handle "vtw-deferred")))
                           (is (= "B" (text-of handle "vtw-self")))
                           (is (= [] @!errs))
                           (is (zero? @!bridge-updates)
                               "React does not report a transition it could not
                                start"))
                         (is (fn? (.-startViewTransition js/document))
                             "and the row put the API back for its successors")
                         (teardown-census! handle)))))
            (.catch (report-failure! "no-API row" handle nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 8. Hydration
;; ---------------------------------------------------------------------------

(deftest a-hosted-view-transition-hydrates-with-no-mismatch
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_         (seeded!)
            container (staged-container!)
            html      (server-html [view-transition {:name "vtw-hydrate"} [panel {}]])
            !seen     (atom [])
            original  (.-error js/console)
            on-err    (fn [e] (swap! !seen conj (str "window: " (.-message ^js e))))]
        (set! (.-innerHTML container) html)
        (set! (.-error js/console)
              (fn [& args] (swap! !seen conj (str "console.error: " (pr-str (vec args))))))
        (.addEventListener js/window "error" on-err)
        (let [root   (react-dom-client/hydrateRoot
                       container
                       (rf.fresco.impl.mount/provider
                         frame-id (rf.fresco.impl.codec/root-element frame-id
                                    [view-transition {:name "vtw-hydrate"} [panel {}]]))
                       #js {:onRecoverableError
                            (fn [err _info]
                              (swap! !seen conj (str "onRecoverableError: " (ex-message err))))})
              handle {:root root :container container :frame frame-id}]
          (-> (poll #(= "A" (text-of handle "vtw-label"))
                    "the hydrated tree is live")
              (.then
                (fn [_]
                  (.then (sleep! 60)
                         (fn [_]
                           (set! (.-error js/console) original)
                           (.removeEventListener js/window "error" on-err)
                           (testing "ONE TREE EVERYWHERE — `:server :render` means
                                     the same element type on both sides, so
                                     hydration has nothing to reconcile"
                             (is (= [] @!seen)
                                 "React reported no mismatch on any of its three
                                  channels")
                             (is (= "seed" (value-of handle "vtw-input"))
                                 "premise: the hydrated controlled input carries
                                  the subscribed value"))
                           (teardown-census! handle)))))
              (.catch (report-failure! "hydration row" handle nil))
              (.then (fn [_] (done)))))))))
