(ns re-frame.bench.fresco.arm1.host-hatch-dom-cljs-test
  "THE HOST HATCH, PROVEN END-TO-END (HD-011).

  The charter's v0 gate says 'one host hatch proven', and use case D9
  names the claim: hosting React libraries via the one door — value in,
  callback out, hook/context/ref owners, React-owned lifecycle.
  `front/intent_cljs_test` is the contract half: it proves what a
  declared `:callbacks` entry lowers TO, at closure level, with no door,
  no foreign component and no DOM anywhere in the witness.
  This file is the other half: a real foreign React component — its own
  `useState`, its own `useEffect`, its own `useContext`, its own ref
  plumbing — declared once with `defhost`, driven from Fresco
  subscriptions, dispatching Fresco intents, surviving what Fresco
  does around it, and tearing down to the residue witnesses' standard.

  ## The proof component is a deliberate worst reasonable case

  [[widget]] stands in for a third-party library without vendoring one:
  React-owned state that must survive (`useState` twice), a mount effect
  with a cleanup that must run (`useEffect`), a context read below the
  crossing (`useContext` — and the PROVIDER is hosted too, which is the
  guide's own answer to 'a library hands you a provider'), a ref it
  forwards to its root node, children it slots, and three invoker styles
  on its callbacks: value-first multi-arg (`(onPick value event)`),
  event-first (`(onDraft event)`), and imperative-with-return
  (`onImperative`).

  ## The hook-budget distinction, measured rather than asserted

  HD-020's ≤2-hook budget is a statement about FRESCO'S OWN boundary
  shells. The hosted component's hooks are its own affair — that
  distinction is the whole point of the door — and the dispatcher-level
  probe below is what measures the difference rather than asserting it.

  **The door has a cost of its own**, and this suite is where it is
  visible. HD-011's SSR placeholder gives every declaration ONE gate —
  the component that renders the placeholder until the markup is adopted
  and the foreign component afterwards — so a crossing costs one fiber
  and one `useSyncExternalStore`. The budget itself is untouched:
  `shell-hook-ledger` declares two, the gate holds no subscription and
  reads no frame, and the probe below counts the shell's two, then the
  door's one, then nothing that is not the widget's own roster.

  ## Presence children, and the half only the door can witness

  A child handed to `h/presence` is lowered inside the presence
  component's OWN render, so presence binds the frame there: it resolves
  the frame through the substrate's context and binds it around its one
  `as-element` call, and `h/error-boundary` does the same for its
  fallback and children. Without that, an intent-bearing prop on ANY
  presence child, native or host alike, would lower against no dispatch.

  The host half is what only the door can witness, so both shapes are
  driven through the door from the RETAINED window: an intent vector,
  which lowers during presence's render, and a declared-`:event`
  `h/event`, which would fail a whole phase later at invocation. Two
  shapes that break at different moments is exactly why witnessing one
  is not witnessing the other.

  Runtime: `-dom-cljs-test`, written against a real React DOM. The lane's
  `:fresco-bench-test` build runs it under Node (`npm test`, and nightly
  in the `fresco-bench-compile` job), where every DOM claim degrades to a
  stated skip and the declaration/refusal rows, which need no DOM, run in
  full; no lane runs the DOM claims in a browser."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.hook-probe :as rf.bench.fresco.arm1.hook-probe]
            [re-frame.bench.fresco.arm1.mount :as rf.bench.fresco.arm1.mount]
            [re-frame.bench.fresco.arm1.presence :refer [presence]]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.bench.fresco.front.intent :as rf.bench.fresco.front.intent]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react])
  (:require-macros [re-frame.bench.fresco.arm1.lang :refer [defview defhost event]]))

(def ^:private frame-id ::host-hatch)
(def ^:private timeout-ms 60)

;; Registered ABOVE `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (see the sibling suites).

(rf/reg-sub :hatch/label (fn [db _] (:label db)))
(rf/reg-sub :hatch/draft (fn [db _] (:draft db)))
(rf/reg-sub :hatch/city (fn [db _] (:city db)))
(rf/reg-sub :hatch/theme (fn [db _] (:theme db)))
(rf/reg-sub :hatch/picked-log (fn [db _] (:picked db)))
(rf/reg-sub :hatch/widgets (fn [db _] (:widgets db)))

(rf/reg-event :hatch/seed
  (fn [_ _]
    {:db {:label "due date" :draft "" :city "paris" :theme "noir"
          :picked [] :closed 0 :widgets []}}))
(rf/reg-event :hatch/set
  (fn [{:keys [db]} [_ k v]] {:db (assoc db k v)}))
(rf/reg-event :hatch/picked
  (fn [{:keys [db]} [_ city kind]] {:db (update db :picked conj [city kind])}))
(rf/reg-event :hatch/typed
  (fn [{:keys [db]} [_ v]] {:db (assoc db :draft v)}))
(rf/reg-event :hatch/closed
  (fn [{:keys [db]} _] {:db (update db :closed inc)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; the presence row waits on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(defn- skip! [why] (is true (str "a host-hatch claim needs a real React DOM — " why)))

(defn- fresh! []
  (rf.bench.fresco.lane/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hatch/seed]))
  frame-id)

(defn- db [] (rf/app-db-value frame-id))

;; ---------------------------------------------------------------------------
;; The foreign component — the library that never shipped
;; ---------------------------------------------------------------------------

(def ^:private theme-context (react/createContext "unthemed"))

(def ^:private !instr
  "What the foreign side observed. The witnesses read it because a
  library's insides are exactly what the door cannot see — which is what
  makes an instrumented stand-in the honest probe."
  (atom {}))

(defn- instr! []
  (reset! !instr {:mounts 0 :cleanups 0 :renders 0
                  :imperative-args [] :imperative-return nil
                  :received-imperative nil :ref-node nil :ref-cleanups 0
                  ;; Every context value the foreign side ever read, in the
                  ;; order it read them. Accumulated rather than
                  ;; overwritten because the question is whether two
                  ;; DISTINCT values stay distinct across the crossing, and
                  ;; the last one alone cannot answer it.
                  :context-themes []}))

(defn- widget
  "The worst reasonable case, as a plain React function component — raw
  hooks, its own DOM, its own callback contracts, written exactly as a
  JS library author would (ref as a prop: the React 19 contract)."
  [^js props]
  (swap! !instr update :renders (fnil inc 0))
  (when-some [f (.-onImperative props)]
    (swap! !instr assoc :received-imperative f))
  (let [theme       (react/useContext theme-context)
        _           (swap! !instr update :context-themes (fnil conj []) theme)
        clicks-hook (react/useState 0)
        clicks      (aget clicks-hook 0)
        set-clicks  (aget clicks-hook 1)
        phase-hook  (react/useState "entering")
        phase       (aget phase-hook 0)
        set-phase   (aget phase-hook 1)]
    ;; React-owned lifecycle: the component advances its own state from
    ;; its own effect — the enter-transition shape, i.e. the stand-in
    ;; for React-owned animation — and registers the cleanup teardown
    ;; must run.
    (react/useEffect
      (fn []
        (swap! !instr update :mounts (fnil inc 0))
        (set-phase "settled")
        (fn [] (swap! !instr update :cleanups (fnil inc 0))))
      #js [])
    (react/createElement "div"
      #js {:className   (str "widget"
                             (when-some [c (.-className props)] (str " " c)))
           :ref         (.-ref props)
           :data-theme  theme
           :data-clicks clicks
           :data-phase  phase}
      (react/createElement "span" #js {:className "widget-label"} (.-label props))
      (react/createElement "input"
        #js {:className "widget-input"
             :value     (or (.-draft props) "")
             ;; event-first invoker: the DOM event, verbatim
             :onChange  (fn [e] (when-some [f (.-onDraft props)] (f e)))})
      (react/createElement "button"
        #js {:className "widget-pick"
             ;; value-first multi-arg invoker: (onPick value event) —
             ;; and the click also moves the component's OWN state
             :onClick   (fn [e]
                          (set-clicks (fn [n] (inc n)))
                          (when-some [f (.-onPick props)] (f (.-value props) e)))}
        "pick")
      (react/createElement "button"
        #js {:className "widget-close"
             :onClick   (fn [e] (when-some [f (.-onClose props)] (f e)))}
        "close")
      (react/createElement "button"
        #js {:className "widget-run"
             ;; imperative invoker that USES the return — :handler's
             ;; 'return ignored' is Fresco's contract, not the library's
             :onClick   (fn [_]
                          (when-some [f (.-onImperative props)]
                            (swap! !instr assoc :imperative-return (f 41))))}
        "run")
      ;; A RENDER PROP — invoked during THIS component's own render, which
      ;; is the position table's render row met at a real foreign
      ;; component (`renderRow`/`renderItem`, the shape half the ecosystem
      ;; ships). Its return goes straight into the library's tree.
      (react/createElement "div" #js {:className "widget-render"}
        (when-some [f (.-onRenderRow props)]
          (f (.-label props))))
      (react/createElement "div" #js {:className "widget-slot"}
        (.-children props)))))

;; ---------------------------------------------------------------------------
;; The declarations — one line each, the whole of the door's surface
;; ---------------------------------------------------------------------------

(defhost picker widget
  {:callbacks {:on-pick       :event
               :on-close      :event
               :on-draft      :event
               :on-imperative :handler}})

(defhost themed
  "A provider an ecosystem library hands you is hosted like anything
  else — it is a component (the guide's own troubleshooting row)."
  (.-Provider theme-context))

(defhost render-picker
  "The same component, declared with all THREE contracts on it — the
  matrix below needs one host that can be handed every carrier at every
  contract, and `:render` is the row `picker` above has no slot for."
  widget
  {:callbacks {:on-pick       :event
               :on-imperative :handler
               :on-render-row :render}})

(def ^:private memo-widget (react/memo widget))

(defhost memo-picker memo-widget
  {:callbacks {:on-pick :event :on-imperative :handler}})

(def ^:private stable-imperative
  (event [x]
    (swap! !instr update :imperative-args (fnil conj []) x)
    (* x 2)))

;; ---------------------------------------------------------------------------
;; The screens
;; ---------------------------------------------------------------------------

(defn- grab-ref
  "The consumer's callback ref, in the shape the guide teaches: the
  return is the detach cleanup (React 19)."
  [node]
  (swap! !instr assoc :ref-node node)
  (fn [] (swap! !instr update :ref-cleanups (fnil inc 0))))

(defview screen
  [_]
  [:div.screen
   [:output.picked-count (str (count (rf.bench.fresco.arm1.runtime/sub [:hatch/picked-log])))]
   [themed {:value (rf.bench.fresco.arm1.runtime/sub [:hatch/theme])}
    [picker {:label         (rf.bench.fresco.arm1.runtime/sub [:hatch/label])
             :draft         (rf.bench.fresco.arm1.runtime/sub [:hatch/draft])
             :value         (rf.bench.fresco.arm1.runtime/sub [:hatch/city])
             :on-pick       (event [city e] [:hatch/picked city (.-type e)])
             :on-close      [:re-frame.fresco/prevent [:hatch/closed]]
             :on-draft      [:hatch/typed :re-frame.fresco/value]
             :on-imperative stable-imperative
             :ref           grab-ref}
     [:em.gifted "from hiccup"]]]])

(defview namespaced-theme-page
  "TWO hosted providers of the ONE context, side by side, each handed a
  namespaced keyword from a DIFFERENT namespace. Siblings
  rather than nested, because the question is whether two distinct values
  stay two — and a nested pair would only ever show the inner one.

  This is HD-011's flagship case at its full width: a provider an
  ecosystem library hands you, whose `:value` names a theme, and a
  foreign consumer below reading it back through `useContext`."
  [_]
  [:div.themes
   [:div.theme-a [themed {:value :theme/dark} [picker {:label "a"}]]]
   [:div.theme-b [themed {:value :other/dark} [picker {:label "b"}]]]])

(defview host-page
  "The minimal page the hook probe counts: one shell, one hosted widget,
  nothing else."
  [_]
  [picker {:label (rf.bench.fresco.arm1.runtime/sub [:hatch/label])}])

(defview render-prop-page
  "A declared `:render` slot, driven by the foreign component's own
  render. The body is pure and its return is what the library puts in
  its tree — the position table's render row, met where it actually
  bites."
  [_]
  [render-picker {:label         (rf.bench.fresco.arm1.runtime/sub [:hatch/label])
                  :on-render-row (event [label] (str "rendered:" label))}])

(defview tray
  "The host under presence, WITH callbacks. Both shapes, because they
  break a phase apart: the
  vector at `:on-close` is lowered during presence's own render, and the
  `h/event` at `:on-pick` survives lowering and would fail at invocation."
  [_]
  [presence {:timeout-ms timeout-ms}
   (for [w (rf.bench.fresco.arm1.runtime/sub [:hatch/widgets])]
     [picker {:key      (:id w)
              :label    (:name w)
              :value    (:name w)
              :on-pick  (event [city e] [:hatch/picked city (.-type e)])
              :on-close [:hatch/closed]
              :re-frame.fresco/unmounting {:class "widget--exit"}}])])

(defview hosted-row
  "A host inside an ordinary boundary — which under HD-028 is a
  memoised one, every boundary being a `React.memo` carrying a
  value-equality comparator."
  [{:keys [label]}]
  [picker {:label label :on-imperative stable-imperative}])

(defview chrome-page
  "Page chrome reads a key; the row below it reads nothing and takes
  value-equal props. The write moves the chrome only."
  [_]
  [:div
   [:span.chrome (str (rf.bench.fresco.arm1.runtime/sub [:hatch/label]))]
   [hosted-row {:label "fixed"}]])

(defview memo-page
  [_]
  [:div
   [:span.mlabel (str (rf.bench.fresco.arm1.runtime/sub [:hatch/label]))]
   [memo-picker {:label "fixed" :on-imperative stable-imperative}]])

(defview memo-defeated-page
  [_]
  [:div
   [:span.mlabel (str (rf.bench.fresco.arm1.runtime/sub [:hatch/label]))]
   [memo-picker {:label "fixed" :on-pick [:hatch/picked "static"]}]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- q [handle sel] (.querySelector (:container handle) sel))
(defn- attr [handle sel a] (some-> (q handle sel) (.getAttribute a)))

(defn- click! [handle sel]
  (.click (q handle sel))
  (rf.bench.fresco.arm1.mount/settle!))

(defn- settled!
  "Let the widget's own effect-driven state land. Its `set-phase` runs in
  a passive effect, so the update it schedules is DEFAULT-lane work that
  commits on the scheduler's macrotask — an empty `flushSync` cannot pull
  it forward. One macrotask, then the flush: the presence suite's idiom,
  needed here for the same reason (a foreign enter transition is exactly
  presence's weak half, owned by the library instead)."
  []
  (js/Promise. (fn [resolve]
                 (js/setTimeout (fn [] (rf.bench.fresco.arm1.mount/settle!) (resolve true)) 0))))

(defn- set-native-value!
  "Write `v` through `HTMLInputElement.prototype`'s OWN value setter,
  bypassing React's per-instance change tracker (the sibling controlled
  suites' idiom)."
  [node v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) node v)))

(defn- type-into! [handle sel text]
  (let [node (q handle sel)]
    (set-native-value! node text)
    (.dispatchEvent node (js/Event. "input" #js {:bubbles true}))
    (rf.bench.fresco.arm1.mount/settle!)))

(defn- teardown-census!
  "Unmount through the arm's own residue door, read the live-reference
  census, THEN release — the lifecycle suite's ordering, and for the
  same reason: a census taken after `release!` reads an emptied table
  whatever teardown did. `rf.bench.fresco.arm1.mount/unmount!` rather than a raw flushSync,
  because it is the door a residue gate is designed to read through —
  and the seam the teardown mutation breaks."
  [handle]
  (rf.bench.fresco.arm1.mount/unmount! handle)
  (let [census (select-keys (rf.bench.fresco.arm1.runtime/residue) [:cell-refs :boundaries :edges])]
    (rf.bench.fresco.arm1.mount/release! (assoc handle :root nil))
    census))

(def ^:private released {:cell-refs 0 :boundaries 0 :edges 0})

;; ---------------------------------------------------------------------------
;; 1 — the crossing, whole: value, context, children, ref, lifecycle
;; ---------------------------------------------------------------------------

(deftest the-declared-door-mounts-a-foreign-component-inside-a-fresco-tree
  (async done
    (if-not (rf.bench.fresco.arm1.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [screen {}])]
          (-> (settled!)
              (.then
                (fn [_]
                  (let [census (volatile! nil)]
                    (try
                      (is (some? (q handle ".widget"))
                          "the foreign component is on the page")
                      (testing "value in: a subscription value crossed as an
                                ordinary prop"
                        (is (= "due date" (.-textContent (q handle ".widget-label")))))
                      (testing "context in: the PROVIDER is hosted, and the
                                consumer reads it below the crossing — React
                                context flows through the Fresco tree because
                                the tree is real React elements"
                        (is (= "noir" (attr handle ".widget" "data-theme"))))
                      (testing "React-owned lifecycle: the component advanced
                                its own state from its own effect, with no
                                Fresco involvement — the enter-transition
                                shape standing in for React-owned animation"
                        (is (= "settled" (attr handle ".widget" "data-phase")))
                        (is (= 1 (:mounts @!instr))))
                      (testing "children: hiccup children crossed as React
                                children"
                        (is (= "from hiccup" (.-textContent (q handle ".widget-slot")))))
                      (testing "ref delivery: the consumer's callback ref was
                                attached to the node the FOREIGN component
                                chose"
                        (let [n (:ref-node @!instr)]
                          (is (some? n))
                          (is (.contains (.-classList n) "widget"))))
                      (finally (vreset! census (teardown-census! handle))))
                    (is (= released @census)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (done)))))))))

(deftest two-namespaced-keywords-reach-two-providers-as-two-distinct-values
  (async done
    (if-not (rf.bench.fresco.arm1.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id
                                  [namespaced-theme-page {}])]
          (-> (settled!)
              (.then
                (fn [_]
                  (try
                    (let [seen (set (:context-themes @!instr))]
                      (testing "at the far end of the crossing. The foreign
                                consumer records every context value it reads;
                                were keywords crossed by `(name v)`, BOTH
                                providers would hand it \"dark\" and this set
                                would hold ONE element — two themes, silently
                                one, with nothing thrown anywhere."
                        (is (= 2 (count seen))
                            "the collision, stated as a count: two distinct
                             keywords in, two distinct values out")
                        (is (= #{:theme/dark :other/dark} seen)
                            "and they are the keywords the author wrote,
                             namespaces intact — so `=` against that literal is
                             the whole of reading a context value back"))
                      (testing "the DOM the foreign component built agrees: it
                                puts the context value on an attribute, and the
                                two subtrees differ there"
                        (is (not= (attr handle ".theme-a .widget" "data-theme")
                                  (attr handle ".theme-b .widget" "data-theme")))))
                    (finally (rf.bench.fresco.arm1.mount/release! handle)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 2 — callbacks out, and React-owned state surviving Fresco re-renders
;; ---------------------------------------------------------------------------

(deftest callbacks-cross-out-and-react-owned-state-survives-fresco-rerenders
  (if-not (rf.bench.fresco.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [screen {}])
            census (volatile! nil)]
        (try
          (rf.bench.fresco.arm1.mount/settle!)
          (click! handle ".widget-pick")
          (click! handle ".widget-pick")
          (testing "declared :event + h/event: EVERY argument the foreign
                    invoker passed reached the body — (onPick value event),
                    the variadic contract — and the returned intent
                    dispatched into the frame"
            (is (= [["paris" "click"] ["paris" "click"]] (:picked (db))))
            (is (= "2" (.-textContent (q handle ".picked-count")))
                "and the dispatch echoed back through the page"))
          (is (= "2" (attr handle ".widget" "data-clicks"))
              "the library's own useState moved under its own clicks")
          (testing "REACT-OWNED STATE SURVIVES: the boundary above re-renders
                    on a moved subscription, the new value crosses, and the
                    foreign useState keeps its count — same fiber, no remount"
            (rf.bench.fresco.arm1.mount/dispatch! handle [:hatch/set :label "arrival"])
            (is (= "arrival" (.-textContent (q handle ".widget-label"))))
            (is (= "2" (attr handle ".widget" "data-clicks")))
            (is (= 1 (:mounts @!instr))
                "the head is minted once at declaration, so React reconciled
                 rather than remounted"))
          (testing "and a context value driven by a subscription moves through
                    the hosted provider"
            (rf.bench.fresco.arm1.mount/dispatch! handle [:hatch/set :theme "sepia"])
            (is (= "sepia" (attr handle ".widget" "data-theme")))
            (is (= "2" (attr handle ".widget" "data-clicks"))))
          (finally (vreset! census (teardown-census! handle))))
        (is (= released @census))))))

;; ---------------------------------------------------------------------------
;; 3 — the prevent head, and the marker, across the crossing
;; ---------------------------------------------------------------------------

(deftest a-prevented-intent-at-a-declared-position-prevents-then-dispatches
  (if-not (rf.bench.fresco.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [screen {}])]
        (try
          (rf.bench.fresco.arm1.mount/settle!)
          (let [ev      (js/MouseEvent. "click" #js {:bubbles true :cancelable true})
                outcome (.dispatchEvent (q handle ".widget-close") ev)]
            (rf.bench.fresco.arm1.mount/settle!)
            (is (false? outcome)
                "dispatchEvent answers false exactly when preventDefault ran —
                 the ::h/prevent half fired on the real event")
            (is (true? (.-defaultPrevented ev)))
            (is (= 1 (:closed (db))) "and the wrapped intent dispatched"))
          (finally (rf.bench.fresco.arm1.mount/release! handle)))))))

(deftest the-value-marker-materializes-when-the-foreign-invoker-hands-an-event
  (testing "::h/value across a host crossing works exactly when the
            foreign contract hands the DOM event first, as this widget's
            onChange does. A value-first invoker has no event to read a
            target from; h/event is that spelling (row 2 above proves it)."
    (if-not (rf.bench.fresco.arm1.mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (instr!)
        (fresh!)
        (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [screen {}])]
          (try
            (rf.bench.fresco.arm1.mount/settle!)
            (type-into! handle ".widget-input" "west")
            (is (= "west" (:draft (db)))
                "the marker read the event's target across the door")
            (is (= "west" (.-value (q handle ".widget-input")))
                "and the model echoed back into the foreign input — the loop
                 is closed in both directions")
            (finally (rf.bench.fresco.arm1.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 4 — :handler crosses by identity, runs imperatively, returns to the caller
;; ---------------------------------------------------------------------------

(deftest a-declared-handler-crosses-by-identity-and-its-return-is-the-foreigners
  (if-not (rf.bench.fresco.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [screen {}])]
        (try
          (rf.bench.fresco.arm1.mount/settle!)
          (is (identical? stable-imperative (:received-imperative @!instr))
              ":handler is the FUNCTION ITSELF — the door rewrapped nothing,
               so a library memoising on handler identity is not defeated")
          (click! handle ".widget-run")
          (is (= [41] (:imperative-args @!instr)) "the imperative call ran")
          (is (= 82 (:imperative-return @!instr))
              "and the RETURN went back to the foreign caller — 'return
               ignored' is Fresco's side of the contract, not the library's")
          (is (= [] (:picked (db))) "and nothing dispatched")
          (finally (rf.bench.fresco.arm1.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 5 — the declaration's refusals (these rows need no DOM)
;; ---------------------------------------------------------------------------

(defn- thrown
  "The ex-data `f` throws, or `::did-not-throw`."
  [f]
  (try (f) ::did-not-throw (catch :default e (ex-data e))))

(defn- error-id [f] (let [d (thrown f)] (get d :rf.error/id d)))

(deftest the-declaration-refuses-what-it-cannot-carry
  (testing "every refusal fires at the declaration, where the author's stack
            is the declaration site — the nil component a broken import
            hands it, a structural slot in any spelling, a contract outside
            the roster, and two spellings of one slot"
    (doseq [[component callbacks id]
            [[nil    {}                                 :rf.error/fresco-host-no-component]
             [widget {"ref" :handler}                   :rf.error/fresco-host-structural-callback]
             [widget {:x/key :event}                    :rf.error/fresco-host-structural-callback]
             [widget {:on-pick :evnt}                   :rf.error/fresco-unknown-callback-contract]
             [widget {:on-pick :event :onPick :handler} :rf.error/fresco-host-callback-slot-collision]]]
      (is (= id (error-id #(rf.bench.fresco.front.codec/mint-host! "hatch/refused" component
                                                                   {:callbacks callbacks})))
          (pr-str callbacks)))))

(deftest the-crossing-refuses-an-undeclared-event-spelled-intent
  (let [h (rf.bench.fresco.front.codec/mint-host! "hatch/mini" widget {:callbacks {:on-pick :event}})]
    (testing "an intent vector or key-map at an event-spelled prop the
              declaration does not name refuses, naming the host and the
              position — never inference, and never an inert array shipped to
              the library"
      (doseq [[k v] [[:on-nope [:boom]] [:on-key-down {"Enter" [:boom]}]]]
        (is (= {:rf.error/id :rf.error/fresco-host-undeclared-callback :position k :host "hatch/mini"}
               (select-keys (thrown #(rf.bench.fresco.front.codec/as-element [h {k v}]))
                            [:rf.error/id :position :host]))
            (pr-str v))))
    (testing "a vector at the ref slot is HD-022's reservation, held at the
              host position too"
      (is (= :rf.error/fresco-ref-vector-reserved
             (error-id #(rf.bench.fresco.front.codec/as-element [h {:ref [:re-frame.fresco/autosize {}]}])))))))

(deftest the-declaration-binds-by-canonical-slot-not-by-spelling
  (let [h (rf.bench.fresco.front.codec/mint-host! "hatch/slot-bound" widget {:callbacks {:on-pick :event}})]
    (testing "the camel spelling lands on the declared slot: the vector is
              LOWERED — outside a boundary that is the intent's own loud
              error — rather than crossing as data"
      (is (= :rf.error/fresco-intent-outside-boundary
             (error-id #(rf.bench.fresco.front.codec/as-element [h {:onPick [:hatch/picked "x"]}])))))
    (testing "while an undeclared camel on* spelling is refused like any other"
      (is (= :rf.error/fresco-host-undeclared-callback
             (error-id #(rf.bench.fresco.front.codec/as-element [h {:onValueChange [:hatch/picked "x"]}])))))))

(deftest host-props-convert-shallowly
  (testing "HD-011's default: the top-level key camelCases and the value
            crosses unrenamed — a keyword keeps its namespace, a function its
            identity, a nested map the keys the author wrote, and a data
            vector at a non-event prop is ordinary data — except at an
            HTML-attribute slot, where a string is the only representation"
    (let [h (rf.bench.fresco.front.codec/mint-host! "hatch/shallow" widget {})]
      (is (= {"menuItems" [{"day-of-week" 1}]
              "theme"     :theme/dark
              "className" "primary"
              "plainFn"   identity}
             (js->clj (unchecked-get (rf.bench.fresco.front.codec/as-element
                                       [h {:menu-items [{:day-of-week 1}]
                                           :theme      :theme/dark
                                           :class      :primary
                                           :plain-fn   identity}])
                                     "props")))))))

;; ---------------------------------------------------------------------------
;; 5b — the declaration GOVERNS, and the vector spelling is EVENT-FIRST
;;      (HD-024). These rows need no DOM.
;; ---------------------------------------------------------------------------
;;
;; (a) The CONTRACT the declaration named governs every carrier at that
;;     position, so a slot declared `:handler` or `:render` never dispatches,
;;     whatever value it is handed. (b) The vector spelling reads the DOM
;;     event from argument ONE, and a value-first invoker is refused naming
;;     the POSITION rather than failing with the engine's own TypeError.
;;
;; The rows cross through the real minted head and invoke the lowered prop
;; the way [[widget]] does: `(f (.-value props) e)` for `onPick`, `(f e)`
;; for `onDraft`.

(defn- prop [^js el nm] (unchecked-get (unchecked-get el "props") nm))

(defn- crossed
  "Cross one attr map through the real door under a recording dispatch,
  and answer `[element !dispatched]`. The frame is bound for the crossing
  only, so every closure it produces is invoked — like a browser's — after
  the render's dynamic extent has unwound."
  [head props]
  (let [!seen (atom [])
        el    (rf.bench.fresco.front.intent/with-frame (fn [ev] (swap! !seen conj ev) nil)
                (fn [] (rf.bench.fresco.front.codec/as-element [head props])))]
    [el !seen]))

(deftest a-declared-render-slot-is-invoked-during-the-foreign-render
  (if-not (rf.bench.fresco.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [render-prop-page {}])]
        (try
          (rf.bench.fresco.arm1.mount/settle!)
          (is (= "rendered:due date" (.-textContent (q handle ".widget-render")))
              "the h/event ran inside the foreign component's own render and its
               return went into the library's tree — not to dispatch")
          (is (= [] (:picked (db))) "and nothing dispatched")
          (finally (rf.bench.fresco.arm1.mount/release! handle)))))))

(deftest the-declaration-governs-every-carrier-at-its-position
  (testing ":event lowers the h/event and the key-map, because dispatching is
            what that contract MEANS"
    (let [[el !seen] (crossed render-picker
                             {:on-pick (event [city e] [:hatch/picked city (.-type e)])})]
      ((prop el "onPick") "paris" #js {:type "click"})
      (is (= [[:hatch/picked "paris" "click"]] @!seen)
          "every argument the invoker passed reached the body, and the returned vector dispatched"))
    (let [[el !seen] (crossed render-picker {:on-pick {"Enter" [:hatch/closed]}})]
      ((prop el "onPick") #js {:key "Enter"})
      (is (= [[:hatch/closed]] @!seen))))

  (testing ":handler crosses the h/event by identity, and :render wraps it and
            hands its return back to the caller"
    (is (identical? stable-imperative
                    (prop (first (crossed render-picker {:on-imperative stable-imperative}))
                          "onImperative")))
    (is (= "row:x" ((prop (first (crossed render-picker
                                          {:on-render-row (event [label] (str "row:" label))}))
                          "onRenderRow")
                    "x"))))

  (testing "both REFUSE the dispatching carriers, naming the position, the
            contract and the value: a :handler's return is ignored, and a
            :render position is invoked during the foreign component's own
            render, so a carrier that is nothing but a dispatch has no
            reading at either"
    (doseq [[k contract] [[:on-imperative :handler] [:on-render-row :render]]
            v            [[:hatch/closed] {"Enter" [:hatch/closed]}]]
      (is (= {:rf.error/id :rf.error/fresco-intent-at-a-non-event-contract
              :position    k
              :contract    contract
              :value       v}
             (select-keys (thrown #(crossed render-picker {k v}))
                          [:rf.error/id :position :contract :value]))
          (pr-str k v))))

  (testing "an ordinary function is claimed by no contract and crosses by
            identity at every one"
    (doseq [[k slot] [[:on-pick "onPick"] [:on-imperative "onImperative"] [:on-render-row "onRenderRow"]]]
      (is (identical? identity (prop (first (crossed render-picker {k identity})) slot)) slot))))

;; ---------------------------------------------------------------------------
;; 5c — and what the declaration does NOT govern, an `h/event` may not ask
;; ---------------------------------------------------------------------------
;;
;; At a slot nothing claimed there is no contract to impose, so an `h/event`
;; there would cross as an ordinary function whose returned intent the
;; library discards — a silently dead handler. It is refused instead. A
;; PLAIN function at the same slot still crosses by identity, and `:ref`,
;; claimed by React's own contract, still takes the marked form.

(deftest an-hfn-at-a-slot-nothing-claimed-is-refused
  (testing "RED — refused where the author wrote it, whatever the slot is
            spelled: the mark is the trigger, never the name. The host names
            itself and its DECLARED roster, so the message can say what the
            author could have claimed instead"
    (doseq [k [:on-value-change :row-formatter]]
      (is (= {:rf.error/id :rf.error/fresco-host-unclaimed-callback
              :position    k
              :host        "re-frame.bench.fresco.arm1.host-hatch-dom-cljs-test/render-picker"
              :declared    #{"onPick" "onImperative" "onRenderRow"}
              :recovery    :declare-the-slot-or-hand-a-plain-function}
             (select-keys (thrown #(crossed render-picker {k (event [x] [:hatch/picked x "dead"])}))
                          [:rf.error/id :position :host :declared :recovery])))))

  (testing "GREEN — a PLAIN function at the very slot the row above refuses
            still crosses by identity: the refusal is on the unanswered
            REQUEST, never on functions at the crossing"
    (is (identical? identity
                    (prop (first (crossed render-picker {:on-value-change identity})) "onValueChange"))))

  (testing "GREEN — :ref is CLAIMED, by React's own contract (HD-016), and is
            read BEFORE the unclaimed arm, so a callback ref written as an
            h/event crosses rather than refusing"
    (is (some? (first (crossed render-picker {:ref (event [_] nil)}))))))

(deftest a-dispatch-from-a-declared-render-position-names-the-position
  (testing "HD-024's core law at the door: a :render contract poisons the
            ambient frame-locked dispatch for the call's dynamic extent, with
            the same id a native render position raises"
    (is (= {:rf.error/id :rf.error/fresco-dispatch-in-render-position
            :position    :on-render-row
            :event       [:hatch/closed]}
           (select-keys (thrown #((prop (first (crossed render-picker
                                                        {:on-render-row
                                                         (event [_]
                                                           (rf.bench.fresco.front.intent/*dispatch* [:hatch/closed])
                                                           "never")}))
                                        "onRenderRow")
                                  "x"))
                        [:rf.error/id :position :event])))))

(defn- crossed-in-frame
  "[[crossed]] with the boundary's FRAME KEYWORD bound as well — the
  3-arity door, which is what a row body's `rf.bench.fresco.front.intent/*frame*` read (and a
  `route-link` in one) needs. Answers `[element !dispatched]`."
  [frame-kw head props]
  (let [!seen (atom [])
        el    (rf.bench.fresco.front.intent/with-frame frame-kw (fn [ev] (swap! !seen conj ev) nil)
                (fn [] (rf.bench.fresco.front.codec/as-element [head props])))]
    [el !seen]))

(deftest a-render-props-row-is-owned-by-the-boundary-that-supplied-the-callback
  (testing "at the real `renderRow` seam. HD-024's refusal is
            INVOCATION-scoped — poison while the call runs, forward to the
            owner once it has returned — so the handlers a `:render` body
            LOWERS fire later into the boundary that SUPPLIED the callback.

            Two frames and two recorders are live, and the ambient one at
            invocation is the OTHER — what a foreign component nested below
            a second boundary does — so the ownership claim cannot pass by
            accident."
    (let [!other (atom [])
          !frame (atom ::unread)
          !row   (atom nil)
          [el !supplier]
          (crossed-in-frame
            ::supplier render-picker
            {:on-render-row
             (event [label]
               (reset! !frame rf.bench.fresco.front.intent/*frame*)
               (reset! !row (rf.bench.fresco.front.codec/as-element
                              [:li {:on-click [:hatch/picked label "row"]}]))
               ;; an event-position h/event, lowered in the same body
               (rf.bench.fresco.front.codec/as-element
                 [:button {:on-click (event [_] [:hatch/closed])}]))})
          btn (rf.bench.fresco.front.intent/with-frame ::other (fn [ev] (swap! !other conj ev) nil)
                (fn [] ((prop el "onRenderRow") "paris")))]
      (is (= ::supplier @!frame)
          "inside the invocation the ambient frame is the OWNER's — the frame
           a route-link in a row body pins to")
      ((prop @!row "onClick") #js {})
      ((prop btn "onClick") #js {})
      (is (= [[:hatch/picked "paris" "row"] [:hatch/closed]] @!supplier)
          "the row's intent vector AND the event-position h/event both fired,
           after both extents unwound, into the SUPPLYING boundary's recorder")
      (is (= [] @!other)
          "and nothing reached the boundary that merely invoked the render prop"))))

(deftest the-vector-spelling-is-event-first-and-says-so-when-it-is-not
  (testing "the positive half: an EVENT-first foreign call, which is what
            onDraft makes, hands the marker its event"
    (let [[el !seen] (crossed picker {:on-draft [:hatch/typed :re-frame.fresco/value]})]
      ((prop el "onDraft") #js {:target #js {:value "west"}})
      (is (= [[:hatch/typed "west"]] @!seen))))

  (testing "a VALUE-first call — the widget's `(f (.-value props) e)` — has no
            event at argument one, so the prevent head, a marker and a
            key-map each refuse naming the POSITION and the read they needed,
            rather than raising the engine's own TypeError or, for the
            key-map, silently doing nothing"
    (doseq [[v e needed] [[[:re-frame.fresco/prevent [:hatch/closed]] #js {:preventDefault (fn [] nil)} "preventDefault"]
                          [[:hatch/picked :re-frame.fresco/value "kind"] #js {:target #js {:value "x"}} "target"]
                          [{"Enter" [:hatch/closed]} #js {:key "Enter"} "key"]]]
      (is (= {:rf.error/id :rf.error/fresco-intent-needs-the-event
              :position    :on-pick
              :needed      needed
              :argument    "paris"}
             (select-keys (thrown #((prop (first (crossed picker {:on-pick v})) "onPick") "paris" e))
                          [:rf.error/id :position :needed :argument]))
          needed)))

  (testing "while an intent carrying NEITHER a marker nor a prevent never
            touches its argument, so it is correct under any invoker contract"
    (let [[el !seen] (crossed picker {:on-pick [:hatch/picked "static" "kind"]})]
      ((prop el "onPick") "paris" #js {})
      (is (= [[:hatch/picked "static" "kind"]] @!seen)))))

;; ---------------------------------------------------------------------------
;; 6 — the hook budget distinction, at React's own dispatcher
;; ---------------------------------------------------------------------------

(deftest the-door-spends-one-hook-and-the-hosted-hooks-are-its-own
  (if-not (rf.bench.fresco.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (rf.bench.fresco.arm1.hook-probe/install!)
      (is false (str "React's internals slot was not found, so this claim is "
                     "UNWITNESSED on this build — fix the probe rather than "
                     "reading this as a pass"))
      (do
        (instr!)
        (fresh!)
        (let [handle (volatile! nil)
              names  (rf.bench.fresco.arm1.hook-probe/record!
                       (fn [] (vreset! handle
                                       (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!)
                                                    frame-id [host-page {}]))))]
          (try
            (is (= ["useContext" "useSyncExternalStore"] (vec (take 2 names)))
                (str "the shell's two hooks come FIRST, with nothing before "
                     "them: " (pr-str names)))
            (is (= "useSyncExternalStore" (nth names 2 nil))
                (str "then the door's ONE hook — the SSR gate's adoption "
                     "read, and the whole of what a crossing "
                     "costs: " (pr-str names)))
            (is (every? #{"useContext" "useState" "useEffect"} (drop 3 names))
                (str "and EVERYTHING after it is the widget's own roster — "
                     "useContext/useState/useEffect, however React's dev "
                     "dispatcher counts its reads of them. No useRef, no "
                     "useCallback, no useMemo, and no further hook of "
                     "Fresco's: " (pr-str names)))
            (is (= 2 (count (filter #{"useSyncExternalStore"} names)))
                (str "exactly TWO on the whole page — the boundary's "
                     "subscription and the door's gate, and no third: "
                     (pr-str names)))
            (is (= 2 (count rf.bench.fresco.arm1.runtime/shell-hook-ledger))
                (str "and HD-020(b)'s ≤2 budget is untouched by the gate, "
                     "which is not a boundary: it holds no subscription and "
                     "reads no frame. " (pr-str rf.bench.fresco.arm1.runtime/shell-hook-ledger)))
            (finally (rf.bench.fresco.arm1.mount/release! @handle))))))))

;; ---------------------------------------------------------------------------
;; 7 — the host under presence: retention, override crossing, state survival
;; ---------------------------------------------------------------------------

(deftest react-owned-state-survives-a-presence-transition-around-the-host
  (async done
    (if-not (rf.bench.fresco.arm1.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (rf/with-frame frame-id
          (rf/dispatch-sync [:hatch/set :widgets [{:id 1 :name "one"}]]))
        (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [tray {}])]
          (rf.bench.fresco.arm1.mount/settle!)
          (click! handle ".widget-pick")
          (is (= "1" (attr handle ".widget" "data-clicks"))
              "the library's own state moved before the transition")
          (rf.bench.fresco.arm1.mount/dispatch! handle [:hatch/set :widgets []])
          (is (some? (q handle ".widget"))
              "gone from the model, retained on screen — presence retains a
               host child by key exactly as it retains a native node")
          (is (.contains (.-classList (q handle ".widget")) "widget--exit")
              "the ::h/unmounting override crossed the door as an ordinary
               prop — className — and the foreign component wore it")
          (is (= "1" (attr handle ".widget" "data-clicks"))
              "React-owned state survived entering the exit phase")
          (is (= 1 (:mounts @!instr)))
          (testing "AND THE RETAINED HOST IS STILL LIVE — the half only
                    the door can witness. A declared :event h/event on a
                    child being animated OUT
                    dispatches into the tray's frame: presence lowered this
                    host's props inside its own render, and the frame it
                    bound there is what the callback closed over"
            (click! handle ".widget-pick")
            (is (= [["one" "click"] ["one" "click"]] (:picked (db)))
                "the retained host's callback reached the frame — a toast
                 mid-exit is still clickable, which is the whole pitch"))
          (testing "and the OTHER shape, which breaks a phase earlier: an
                    intent VECTOR is lowered during presence's render, so it
                    would have refused before any click could happen"
            (click! handle ".widget-close")
            (is (= 1 (:closed (db)))))
          (rf.bench.fresco.arm1.mount/dispatch! handle [:hatch/set :widgets [{:id 1 :name "one"}]])
          (is (not (.contains (.-classList (q handle ".widget")) "widget--exit"))
              "re-entry cancelled the exit and took the override off")
          (is (= "2" (attr handle ".widget" "data-clicks"))
              "and the state survived the WHOLE transition — one click before
               it, one DURING the retained window, both still counted after
               re-entry. Retained key identity means the fiber never
               remounted, so the library's own state was never reset")
          (is (= 1 (:mounts @!instr)))
          ;; and a real departure is a real unmount, on the clock
          (rf.bench.fresco.arm1.mount/dispatch! handle [:hatch/set :widgets []])
          (js/setTimeout
            (fn []
              (rf.bench.fresco.arm1.mount/settle!)
              (try
                (is (nil? (q handle ".widget"))
                    "past :timeout-ms the retained host left")
                (is (= (:mounts @!instr) (:cleanups @!instr))
                    "and its own effect cleanups ran — no foreign residue")
                (finally (rf.bench.fresco.arm1.mount/release! handle) (done))))
            (* 4 timeout-ms)))))))

;; ---------------------------------------------------------------------------
;; 8 — teardown: the hosted cleanups run, the ref detaches, nothing remains
;; ---------------------------------------------------------------------------

(deftest teardown-runs-the-hosted-cleanups-and-leaves-no-residue
  (if-not (rf.bench.fresco.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id [screen {}])]
        (rf.bench.fresco.arm1.mount/settle!)
        (is (= 1 (:mounts @!instr)))
        (is (some? (:ref-node @!instr)))
        (is (zero? (:ref-cleanups @!instr)))
        (let [census (teardown-census! handle)]
          (is (= 1 (:cleanups @!instr))
              "unmounting the Fresco root ran the FOREIGN effect's cleanup —
               React owns the teardown because React owned the mount")
          (is (= 1 (:ref-cleanups @!instr))
              "and the callback ref's returned cleanup ran on detach — the
               React 19 contract the guide teaches, witnessed through the
               door")
          (is (= released census)
              "and the runtime holds nothing: the residue witnesses' standard"))))))

;; ---------------------------------------------------------------------------
;; 9 — React.memo at the door: what holds, and the honest cost
;; ---------------------------------------------------------------------------

(deftest a-page-write-does-not-re-render-a-host-under-an-unchanged-boundary
  (testing "the composition hazard under HD-028: a hosted foreign
            component sitting inside a memoised boundary. A write that moves
            only the page chrome re-renders the chrome and stops at the row —
            so the third-party component is not re-rendered AT ALL, and the
            door did not have to know that. HD-028's cascade claim,
            extended to foreign components, which are exactly the ones
            whose render cost nobody controls."
    (async done
      (if-not (rf.bench.fresco.arm1.mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (instr!)
          (fresh!)
          (let [handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!) frame-id
                                    [chrome-page {}])]
            (-> (settled!)
                (.then
                  (fn [_]
                    (try
                      (let [before (:renders @!instr)]
                        (is (pos? before) "the host mounted")
                        (rf.bench.fresco.arm1.mount/dispatch! handle [:hatch/set :label "chrome moved"])
                        (is (= "chrome moved" (.-textContent (q handle ".chrome")))
                            "the page chrome really re-rendered")
                        (is (= before (:renders @!instr))
                            "and the hosted component did not render again —
                             the boundary above it bailed out on value-equal
                             props, so the crossing was never re-run")
                        (is (= 1 (:mounts @!instr))
                            "nor was it remounted"))
                      (finally (rf.bench.fresco.arm1.mount/release! handle)))))
                (.catch (fn [e] (is false (str e)) nil))
                (.then (fn [_] (done))))))))))

(deftest a-memoised-hosted-component-and-the-doors-honest-cost
  (async done
    (if-not (rf.bench.fresco.arm1.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (let [handle (volatile! (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!)
                                             frame-id [memo-page {}]))]
          (-> (settled!)
              (.then
                (fn [_]
                  (testing "the bail-out HOLDS when the door hands
                            shallow-equal props: scalars cross as values and
                            a :handler crosses by identity"
                    (let [before (:renders @!instr)]
                      (rf.bench.fresco.arm1.mount/dispatch! @handle [:hatch/set :label "moved"])
                      (is (= "moved" (.-textContent (q @handle ".mlabel")))
                          "the parent boundary really re-rendered")
                      (is (= before (:renders @!instr))
                          "and React.memo held across it — the door mints a
                           fresh props OBJECT per render, but every value in
                           it was shallow-equal")))
                  (rf.bench.fresco.arm1.mount/release! @handle)
                  (instr!)
                  ;; same frame, re-seeded — a second make-frame mid-test
                  ;; would be a reincarnation, which is its own suite's
                  ;; subject
                  (rf/with-frame frame-id (rf/dispatch-sync [:hatch/seed]))
                  (vreset! handle (rf.bench.fresco.arm1.mount/root! (rf.bench.fresco.arm1.mount/fresh-container!)
                                               frame-id [memo-defeated-page {}]))
                  (settled!)))
              (.then
                (fn [_]
                  (try
                    (testing "and the honest cost, stated: an intent VECTOR at
                              a declared :event position lowers to a fresh
                              closure per parent render, so it defeats a
                              memoised host — the same price every native
                              event position pays. (The boundary-level
                              value-equality bail-out, HD-028, sits ABOVE
                              this seam: it stops the equal-props parent
                              re-render before the door is reached at all,
                              which is the row above.)"
                      (let [before (:renders @!instr)]
                        (rf.bench.fresco.arm1.mount/dispatch! @handle [:hatch/set :label "moved-again"])
                        (is (= "moved-again" (.-textContent (q @handle ".mlabel"))))
                        (is (= (inc before) (:renders @!instr)))))
                    (finally (rf.bench.fresco.arm1.mount/release! @handle)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (done)))))))))
