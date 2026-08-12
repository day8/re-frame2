(ns re-frame.bench.hicasso.arm1.host-hatch-dom-cljs-test
  "THE HOST HATCH, PROVEN END-TO-END (rf2-2rtt6.65, HD-011).

  The charter's v0 gate says 'one host hatch proven', and use case D9
  names the claim: hosting React libraries via the one door — value in,
  callback out, hook/context/ref owners, React-owned lifecycle. Before
  this bead the contract half existed alone: `front/intent_cljs_test`
  proves what a declared `:callbacks` entry lowers TO, at closure level,
  with no door, no foreign component and no DOM anywhere in the witness.
  This file is the other half: a real foreign React component — its own
  `useState`, its own `useEffect`, its own `useContext`, its own ref
  plumbing — declared once with `defhost`, driven from Hicasso
  subscriptions, dispatching Hicasso intents, surviving what Hicasso
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

  HD-020's ≤2-hook budget is a statement about HICASSO'S OWN boundary
  shells. The hosted component's hooks are its own affair — that
  distinction is the whole point of the door — and the dispatcher-level
  probe below is what measures the difference rather than asserting it.

  **The door's own cost changed with rf2-2rtt6.85**, and this suite is
  where the change is visible. Until HD-011's SSR placeholder was
  activated the door minted no wrapper, no fiber and no hook: the
  foreign component was the element's own type. Activating the policy
  gives every declaration ONE gate — the component that renders the
  placeholder until the markup is adopted and the foreign component
  afterwards — so a crossing now costs one fiber and one
  `useSyncExternalStore`. The budget itself is untouched: `shell-hook-
  ledger` still declares two, the gate holds no subscription and reads
  no frame, and the probe below counts the shell's two, then the door's
  one, then nothing that is not the widget's own roster.

  ## The gap this suite found, and the half only the door can witness

  Proving the crossing turned up a defect one component along: a child
  handed to `h/presence` is lowered inside the presence component's OWN
  render, and presence bound no frame there — so an intent-bearing prop
  on ANY presence child, native or host alike, lowered against no
  dispatch. Filed as rf2-2rtt6.66 rather than smoothed here, because it
  was presence's frame plumbing and not the door's; repaired on main
  (presence resolves the frame through the substrate's context and binds
  it around its one `as-element` call), and `h/error-boundary` since took the
  identical repair for its fallback and children (rf2-uo9di).

  That repair could not witness its own host half — the door was still
  in flight — and its bead says so, naming this suite's then-fenced
  presence children as what it un-fences. So the fence is lifted below
  and both shapes are driven through the door from the RETAINED window:
  an intent vector, which lowers during presence's render, and a
  declared-`:event` `h/fn`, which fails a whole phase later at
  invocation. Two shapes that break at different moments is exactly why
  witnessing one is not witnessing the other.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip while the declaration/refusal rows run everywhere."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.presence :refer [presence]]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview defhost hfn]]))

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
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the presence row waits on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- skip! [why] (is true (str "a host-hatch claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
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
                  ;; order it read them (rf2-vrvv9). Accumulated rather than
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
             ;; 'return ignored' is Hicasso's contract, not the library's
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
  (hfn [x]
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
   [:output.picked-count (str (count (rt/sub [:hatch/picked-log])))]
   [themed {:value (rt/sub [:hatch/theme])}
    [picker {:label         (rt/sub [:hatch/label])
             :draft         (rt/sub [:hatch/draft])
             :value         (rt/sub [:hatch/city])
             :on-pick       (hfn [city e] [:hatch/picked city (.-type e)])
             :on-close      [:re-frame.hicasso/prevent [:hatch/closed]]
             :on-draft      [:hatch/typed :re-frame.hicasso/value]
             :on-imperative stable-imperative
             :ref           grab-ref}
     [:em.gifted "from hiccup"]]]])

(defview namespaced-theme-page
  "TWO hosted providers of the ONE context, side by side, each handed a
  namespaced keyword from a DIFFERENT namespace (rf2-vrvv9). Siblings
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
  [picker {:label (rt/sub [:hatch/label])}])

(defview render-prop-page
  "A declared `:render` slot, driven by the foreign component's own
  render. The body is pure and its return is what the library puts in
  its tree — the position table's render row, met where it actually
  bites."
  [_]
  [render-picker {:label         (rt/sub [:hatch/label])
                  :on-render-row (hfn [label] (str "rendered:" label))}])

(defview tray
  "The host under presence, WITH callbacks — the fence rf2-2rtt6.66's
  repair lifted. Both shapes, because they break a phase apart: the
  vector at `:on-close` is lowered during presence's own render, and the
  `h/fn` at `:on-pick` survives lowering and would fail at invocation."
  [_]
  [presence {:timeout-ms timeout-ms}
   (for [w (rt/sub [:hatch/widgets])]
     [picker {:key      (:id w)
              :label    (:name w)
              :value    (:name w)
              :on-pick  (hfn [city e] [:hatch/picked city (.-type e)])
              :on-close [:hatch/closed]
              :re-frame.hicasso/unmounting {:class "widget--exit"}}])])

(defview hosted-row
  "A host inside an ordinary boundary — which since rf2-2rtt6.52/HD-028
  is a memoised one, every boundary being a `React.memo` carrying a
  value-equality comparator."
  [{:keys [label]}]
  [picker {:label label :on-imperative stable-imperative}])

(defview chrome-page
  "Page chrome reads a key; the row below it reads nothing and takes
  value-equal props. The write moves the chrome only."
  [_]
  [:div
   [:span.chrome (str (rt/sub [:hatch/label]))]
   [hosted-row {:label "fixed"}]])

(defview memo-page
  [_]
  [:div
   [:span.mlabel (str (rt/sub [:hatch/label]))]
   [memo-picker {:label "fixed" :on-imperative stable-imperative}]])

(defview memo-defeated-page
  [_]
  [:div
   [:span.mlabel (str (rt/sub [:hatch/label]))]
   [memo-picker {:label "fixed" :on-pick [:hatch/picked "static"]}]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- q [handle sel] (.querySelector (:container handle) sel))
(defn- attr [handle sel a] (some-> (q handle sel) (.getAttribute a)))

(defn- click! [handle sel]
  (.click (q handle sel))
  (mount/settle!))

(defn- settled!
  "Let the widget's own effect-driven state land. Its `set-phase` runs in
  a passive effect, so the update it schedules is DEFAULT-lane work that
  commits on the scheduler's macrotask — an empty `flushSync` cannot pull
  it forward. One macrotask, then the flush: the presence suite's idiom,
  needed here for the same reason (a foreign enter transition is exactly
  presence's weak half, owned by the library instead)."
  []
  (js/Promise. (fn [resolve]
                 (js/setTimeout (fn [] (mount/settle!) (resolve true)) 0))))

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
    (mount/settle!)))

(defn- teardown-census!
  "Unmount through the arm's own residue door, read the live-reference
  census, THEN release — the lifecycle suite's ordering, and for the
  same reason: a census taken after `release!` reads an emptied table
  whatever teardown did. `mount/unmount!` rather than a raw flushSync,
  because it is the door a residue gate is designed to read through —
  and the seam the teardown mutation breaks."
  [handle]
  (mount/unmount! handle)
  (let [census (select-keys (rt/residue) [:cell-refs :boundaries :edges])]
    (mount/release! (assoc handle :root nil))
    census))

(def ^:private released {:cell-refs 0 :boundaries 0 :edges 0})

;; ---------------------------------------------------------------------------
;; 1 — the crossing, whole: value, context, children, ref, lifecycle
;; ---------------------------------------------------------------------------

(deftest the-declared-door-mounts-a-foreign-component-inside-a-hicasso-tree
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id [screen {}])]
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
                                context flows through the Hicasso tree because
                                the tree is real React elements"
                        (is (= "noir" (attr handle ".widget" "data-theme"))))
                      (testing "React-owned lifecycle: the component advanced
                                its own state from its own effect, with no
                                Hicasso involvement — the enter-transition
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
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [namespaced-theme-page {}])]
          (-> (settled!)
              (.then
                (fn [_]
                  (try
                    (let [seen (set (:context-themes @!instr))]
                      (testing "rf2-vrvv9, at the far end of the crossing. The
                                foreign consumer records every context value it
                                reads; under the old `(name v)` rule BOTH
                                providers handed it \"dark\" and this set held
                                ONE element — two themes, silently one, with
                                nothing thrown anywhere."
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
                    (finally (mount/release! handle)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (done)))))))))

;; ---------------------------------------------------------------------------
;; 2 — callbacks out, and React-owned state surviving Hicasso re-renders
;; ---------------------------------------------------------------------------

(deftest callbacks-cross-out-and-react-owned-state-survives-hicasso-rerenders
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [screen {}])
            census (volatile! nil)]
        (try
          (mount/settle!)
          (click! handle ".widget-pick")
          (click! handle ".widget-pick")
          (testing "declared :event + h/fn: EVERY argument the foreign
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
            (mount/dispatch! handle [:hatch/set :label "arrival"])
            (is (= "arrival" (.-textContent (q handle ".widget-label"))))
            (is (= "2" (attr handle ".widget" "data-clicks")))
            (is (= 1 (:mounts @!instr))
                "the head is minted once at declaration, so React reconciled
                 rather than remounted"))
          (testing "and a context value driven by a subscription moves through
                    the hosted provider"
            (mount/dispatch! handle [:hatch/set :theme "sepia"])
            (is (= "sepia" (attr handle ".widget" "data-theme")))
            (is (= "2" (attr handle ".widget" "data-clicks"))))
          (finally (vreset! census (teardown-census! handle))))
        (is (= released @census))))))

;; ---------------------------------------------------------------------------
;; 3 — the prevent head, and the marker, across the crossing
;; ---------------------------------------------------------------------------

(deftest a-prevented-intent-at-a-declared-position-prevents-then-dispatches
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [screen {}])]
        (try
          (mount/settle!)
          (let [ev      (js/MouseEvent. "click" #js {:bubbles true :cancelable true})
                outcome (.dispatchEvent (q handle ".widget-close") ev)]
            (mount/settle!)
            (is (false? outcome)
                "dispatchEvent answers false exactly when preventDefault ran —
                 the ::h/prevent half fired on the real event")
            (is (true? (.-defaultPrevented ev)))
            (is (= 1 (:closed (db))) "and the wrapped intent dispatched"))
          (finally (mount/release! handle)))))))

(deftest the-value-marker-materializes-when-the-foreign-invoker-hands-an-event
  (testing "the guide's open question — 'whether ::h/value works across a host
            crossing' — answered with evidence: it works exactly when the
            foreign contract hands the DOM event first, as this widget's
            onChange does. A value-first invoker has no event to read a
            target from; h/fn is that spelling (row 2 above proves it)."
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (instr!)
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id [screen {}])]
          (try
            (mount/settle!)
            (type-into! handle ".widget-input" "west")
            (is (= "west" (:draft (db)))
                "the marker read the event's target across the door")
            (is (= "west" (.-value (q handle ".widget-input")))
                "and the model echoed back into the foreign input — the loop
                 is closed in both directions")
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 4 — :handler crosses by identity, runs imperatively, returns to the caller
;; ---------------------------------------------------------------------------

(deftest a-declared-handler-crosses-by-identity-and-its-return-is-the-foreigners
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [screen {}])]
        (try
          (mount/settle!)
          (is (identical? stable-imperative (:received-imperative @!instr))
              ":handler is the FUNCTION ITSELF — the door rewrapped nothing,
               so a library memoising on handler identity is not defeated")
          (click! handle ".widget-run")
          (is (= [41] (:imperative-args @!instr)) "the imperative call ran")
          (is (= 82 (:imperative-return @!instr))
              "and the RETURN went back to the foreign caller — 'return
               ignored' is Hicasso's side of the contract, not the library's")
          (is (= [] (:picked (db))) "and nothing dispatched")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 5 — the declaration's refusals (these rows run under :node-test too)
;; ---------------------------------------------------------------------------

(defn- error-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(deftest the-declaration-refuses-what-it-cannot-carry
  (testing "nil component — the broken-import symptom — refuses at the
            declaration, where the author's stack is the declaration site"
    (is (= :rf.error/hicasso-host-no-component
           (error-id #(codec/mint-host! "hatch/nil-host" nil {})))))
  (testing "a contract on a structural slot is refused in every spelling"
    (is (= :rf.error/hicasso-host-structural-callback
           (error-id #(codec/mint-host! "hatch/reffy" widget
                                        {:callbacks {:ref :event}}))))
    (is (= :rf.error/hicasso-host-structural-callback
           (error-id #(codec/mint-host! "hatch/reffy" widget
                                        {:callbacks {"ref" :handler}}))))
    (is (= :rf.error/hicasso-host-structural-callback
           (error-id #(codec/mint-host! "hatch/keyed" widget
                                        {:callbacks {:x/key :event}})))))
  (testing "an unknown contract is refused at mint, not at first render"
    (is (= :rf.error/hicasso-unknown-callback-contract
           (error-id #(codec/mint-host! "hatch/typo" widget
                                        {:callbacks {:on-pick :evnt}})))))
  (testing "two spellings landing on one slot are one contradiction, refused"
    (is (= :rf.error/hicasso-host-callback-slot-collision
           (error-id #(codec/mint-host! "hatch/twice" widget
                                        {:callbacks {:on-pick :event
                                                     :onPick  :handler}}))))))

(deftest the-crossing-refuses-an-undeclared-event-spelled-intent
  (let [h (codec/mint-host! "hatch/mini" widget {:callbacks {:on-pick :event}})]
    (testing "an intent vector at an event-spelled prop the declaration does
              not name refuses LOUDLY, naming the host and the position —
              never inference, and never an inert array shipped to the
              library"
      (try
        (codec/as-element [h {:on-nope [:boom]}])
        (is false "should have thrown")
        (catch :default e
          (let [d (ex-data e)]
            (is (= :rf.error/hicasso-host-undeclared-callback (:rf.error/id d)))
            (is (= :on-nope (:position d)))
            (is (= "hatch/mini" (:host d)))))))
    (testing "an event-spelled KEY-MAP at an undeclared position is the same
              refusal"
      (is (= :rf.error/hicasso-host-undeclared-callback
             (error-id #(codec/as-element [h {:on-key-down {"Enter" [:boom]}}])))))
    (testing "a vector at the ref slot is HD-022's reservation, held at the
              host position too"
      (is (= :rf.error/hicasso-ref-vector-reserved
             (error-id #(codec/as-element [h {:ref [:re-frame.hicasso/autosize {}]}])))))
    (testing "while a plain data vector at a non-event prop is ordinary data"
      (is (some? (codec/as-element [h {:columns [1 2 3]}]))))))

(deftest the-declaration-binds-by-canonical-slot-not-by-spelling
  (let [h (codec/mint-host! "hatch/slot-bound" widget {:callbacks {:on-pick :event}})]
    (testing "the camel spelling lands on the declared slot: the vector was
              LOWERED — outside a boundary that is the intent's own loud
              error — rather than crossing as data"
      (is (= :rf.error/hicasso-intent-outside-boundary
             (error-id #(codec/as-element [h {:onPick [:hatch/picked "x"]}])))))
    (testing "while an undeclared on* spelling never becomes an event position,
              however event-shaped its name"
      (is (= :rf.error/hicasso-host-undeclared-callback
             (error-id #(codec/as-element [h {:onValueChange [:hatch/picked "x"]}])))))))

(deftest host-props-convert-shallowly
  (testing "HD-011's default: the top-level key camelCases, the value crosses
            with no renaming inside it — a nested option map keeps the
            spelling the author wrote, and converting it is the author's
            explicit job when a library wants camelCase inside"
    (let [h  (codec/mint-host! "hatch/shallow" widget {})
          el (codec/as-element [h {:menu-items [{:day-of-week 1}]
                                   :variant    :compact
                                   :theme      :theme/dark
                                   :class      :primary
                                   :plain-fn   identity}])
          ^js props (unchecked-get el "props")]
      (is (= :compact (unchecked-get props "variant"))
          "keyword values cross by IDENTITY, not by name (rf2-vrvv9) — the
           shallow default's own rule, applied to the value: the author is
           handed to the library exactly what they typed, here as at the
           nested map below")
      (is (= :theme/dark (unchecked-get props "theme"))
          "so a namespaced keyword keeps its namespace. `(name :theme/dark)`
           was \"dark\", which :other/dark also was — one output for two
           inputs, silently")
      (is (= "primary" (unchecked-get props "className"))
          "the one exception: a value bound for an HTML attribute has no
           representation but a string, and this is the answer the native
           walk gives at the same name")
      (is (identical? identity (unchecked-get props "plainFn"))
          "functions cross by identity — a value handed to a foreign API,
           not a position")
      (let [row (aget (unchecked-get props "menuItems") 0)]
        (is (= 1 (unchecked-get row "day-of-week"))
            "nested keys are NOT renamed — shallow means shallow")
        (is (nil? (unchecked-get row "dayOfWeek")))))))

;; ---------------------------------------------------------------------------
;; 5b — the declaration GOVERNS, and the vector spelling is EVENT-FIRST
;;      (HD-024, rf2-2rtt6.35). These rows run under :node-test too.
;; ---------------------------------------------------------------------------
;;
;; Two laws, one surface, and both of them are about the door rather than
;; about the value:
;;
;;   (a) the CONTRACT the declaration named governs every carrier at that
;;       position — not only the `h/fn`.  Before this, a vector took the
;;       intent path and a map the key-map path whatever the declaration
;;       said, so a slot declared `:handler` silently dispatched and a
;;       slot declared `:render` could dispatch during the foreign
;;       component's own render.  That is the value selecting the
;;       contract, which is precisely what HD-024 deletes.
;;   (b) the vector spelling reads the DOM event from argument ONE.  A
;;       foreign invoker that hands a value first has no event there, and
;;       the refusal names the POSITION and points at `h/fn` — instead of
;;       `value.preventDefault is not a function`, the engine's own
;;       TypeError naming nothing the author wrote.
;;
;; The matrix rows cross through the real minted head and then invoke the
;; lowered prop the way [[widget]] invokes it — `(f (.-value props) e)` for
;; `onPick`, `(f e)` for `onDraft`, both written into the component above.
;; The first row is the other half, and needs the DOM: a declared `:render`
;; slot actually called during the foreign component's own render.

(defn- prop [^js el nm] (unchecked-get (unchecked-get el "props") nm))

(defn- crossed
  "Cross one attr map through the real door under a recording dispatch,
  and answer `[element !dispatched]`. The frame is bound for the crossing
  only, so every closure it produces is invoked — like a browser's — after
  the render's dynamic extent has unwound."
  [head props]
  (let [!seen (atom [])
        el    (intent/with-frame (fn [ev] (swap! !seen conj ev) nil)
                (fn [] (codec/as-element [head props])))]
    [el !seen]))

(deftest a-declared-render-slot-is-invoked-during-the-foreign-render
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [render-prop-page {}])]
        (try
          (mount/settle!)
          (is (= "rendered:due date" (.-textContent (q handle ".widget-render")))
              "the h/fn ran inside the foreign component's own render and its
               return went into the library's tree — not to dispatch")
          (is (= [] (:picked (db))) "and nothing dispatched")
          (finally (mount/release! handle)))))))

(deftest the-declaration-governs-every-carrier-at-its-position
  (testing ":event takes all four carriers, because dispatching is what that
            contract MEANS"
    (let [[el !seen] (crossed render-picker
                             {:on-pick (hfn [city e] [:hatch/picked city (.-type e)])})]
      ((prop el "onPick") "paris" #js {:type "click"})
      (is (= [[:hatch/picked "paris" "click"]] @!seen) "h/fn: the returned vector dispatched"))
    (let [[el !seen] (crossed render-picker {:on-pick [:hatch/picked "static" "vec"]})]
      ((prop el "onPick") #js {})
      (is (= [[:hatch/picked "static" "vec"]] @!seen) "an intent vector lowers as at a native position"))
    (let [[el !seen] (crossed render-picker {:on-pick {"Enter" [:hatch/closed]}})]
      ((prop el "onPick") #js {:key "Enter"})
      (is (= [[:hatch/closed]] @!seen) "and a key-map lowers as at a native position"))
    (let [[el !seen] (crossed render-picker {:on-pick identity})]
      (is (identical? identity (prop el "onPick"))
          "an ordinary function is claimed by no contract and crosses by identity")
      (is (= [] @!seen))))

  (testing ":handler crosses the h/fn by identity and REFUSES the dispatching
            carriers — its return is ignored and Hicasso dispatches nothing
            from it, so a carrier whose entire content is a dispatch has no
            reading there"
    (let [[el _] (crossed render-picker {:on-imperative stable-imperative})]
      (is (identical? stable-imperative (prop el "onImperative"))))
    (is (= :rf.error/hicasso-intent-at-a-non-event-contract
           (error-id #(crossed render-picker {:on-imperative [:hatch/closed]})))
        "a bare intent at a declared :handler no longer silently dispatches")
    (is (= :rf.error/hicasso-intent-at-a-non-event-contract
           (error-id #(crossed render-picker {:on-imperative {"Enter" [:hatch/closed]}}))))
    (let [[el _] (crossed render-picker {:on-imperative identity})]
      (is (identical? identity (prop el "onImperative"))
          "and an ordinary function still crosses untouched")))

  (testing ":render wraps the h/fn and refuses the dispatching carriers too —
            a :render position is invoked DURING a render, so a carrier that
            is nothing but a dispatch is the one thing it can never be"
    (let [[el !seen] (crossed render-picker {:on-render-row (hfn [label] (str "row:" label))})]
      (is (= "row:x" ((prop el "onRenderRow") "x")) "the return went back to the caller")
      (is (= [] @!seen)))
    (is (= :rf.error/hicasso-intent-at-a-non-event-contract
           (error-id #(crossed render-picker {:on-render-row [:hatch/closed]})))
        "the audit's sharpest case: an intent vector at a declared :render
         position used to take the intent path and dispatch during the
         foreign component's render")
    (is (= :rf.error/hicasso-intent-at-a-non-event-contract
           (error-id #(crossed render-picker {:on-render-row {"Enter" [:hatch/closed]}}))))
    (let [[el _] (crossed render-picker {:on-render-row identity})]
      (is (identical? identity (prop el "onRenderRow")))))

  (testing "the refusal names the position, the contract and the value —
            never the form, because under ONE form the form is never the
            answer to what went wrong"
    (try
      (crossed render-picker {:on-imperative [:hatch/closed]})
      (is false "should have thrown")
      (catch :default e
        (let [d (ex-data e)]
          (is (= :on-imperative (:position d)))
          (is (= :handler (:contract d)))
          (is (= [:hatch/closed] (:value d)))
          (is (re-find #":on-imperative" (ex-message e))))))))

;; ---------------------------------------------------------------------------
;; 5c — and what the declaration does NOT govern, an `h/fn` may not ask
;;      (rf2-2rtt6.116)
;; ---------------------------------------------------------------------------
;;
;; The complement of 5b, and the same law read from the other side.  5b
;; says the CONTRACT the declaration named governs every carrier at that
;; position.  At a slot the declaration named NOTHING there is no
;; contract to govern with — so the marked form, whose entire content is
;; a request that the position impose one, is asking a position that
;; cannot answer.
;;
;; Before this it crossed by identity and simply ran, which is fine for
;; a plain function and is a SILENTLY DEAD HANDLER for the marked one:
;; the `:event` convenience means an `h/fn` returning `[:row/pick x]` at
;; an unclaimed slot is called by the library, returns the intent, has
;; the return discarded, and dispatches nothing.  The user's click does
;; nothing, in production, with no diagnostic — the same class the
;; sibling refusal on an undeclared intent VECTOR exists to delete, one
;; level of indirection down.
;;
;; The rows below are a pair by construction, because a refusal that
;; also rejected legitimate usage would be strictly worse than the
;; silence it replaces: the RED row asserts the id, the `:where` and the
;; roster, and the GREEN rows re-assert that every CLAIMED slot — a
;; declared `:event`, a declared `:handler`, a declared `:render`,
;; React's own `:ref` — still takes the marked form, and that a PLAIN
;; function is untouched at the very slot the red row refuses.

(deftest an-hfn-at-a-slot-nothing-claimed-is-refused
  (testing "the mark asks the POSITION for a contract, and an unclaimed
            slot has none to give — so the request is refused where the
            author wrote it, rather than answered by silence a phase and
            a component away"
    (try
      (crossed render-picker {:on-value-change (hfn [city] [:hatch/picked city "dead"])})
      (is false "should have thrown")
      (catch :default e
        (let [d (ex-data e)]
          (is (= :rf.error/hicasso-host-unclaimed-callback (:rf.error/id d))
              "its own id, distinct from the sibling's: that one is intent
               DATA at an event-SPELLED undeclared slot, this one is the
               marked form at ANY unclaimed slot")
          (is (= 'front.codec/host-element (:where d)))
          (is (= :on-value-change (:position d)))
          (is (re-find #"/render-picker$" (:host d))
              "the host names ITSELF, so the message points at the
               declaration the author would have to change")
          (is (= #{"onPick" "onImperative" "onRenderRow"} (:declared d))
              "the roster is the DECLARED slots as a set, so the message can
               say what the author could have claimed instead")
          (is (= :declare-the-slot-or-hand-a-plain-function (:recovery d)))
          (is (re-find #":on-value-change" (ex-message e)))
          (is (re-find #"or hand a plain function" (ex-message e))
              "and it states the recovery in the message, not only in the
               data — the author reads the message")))))

  (testing "an on*-SPELLED unclaimed slot is the same refusal and not the
            sibling's: the spelling never selected anything here either"
    (is (= :rf.error/hicasso-host-unclaimed-callback
           (error-id #(crossed render-picker {:on-nope (hfn [_] [:hatch/closed])})))))

  (testing "and a slot with no on* spelling at all is refused just the same —
            the mark is the trigger, never the name"
    (is (= :rf.error/hicasso-host-unclaimed-callback
           (error-id #(crossed render-picker {:row-formatter (hfn [x] (str x))})))))

  (testing "GREEN — a PLAIN function at the very slot the rows above refuse
            still crosses by identity. This is the fence: the refusal is on
            the unanswered REQUEST, never on functions at the crossing"
    (let [[el _] (crossed render-picker {:on-value-change identity})]
      (is (identical? identity (prop el "onValueChange")))))

  (testing "GREEN — every CLAIMED slot still takes the marked form, so the
            refusal is not a blanket ban on h/fn at a host"
    (let [[el !seen] (crossed render-picker
                              {:on-pick (hfn [city e] [:hatch/picked city (.-type e)])})]
      ((prop el "onPick") "lisbon" #js {:type "click"})
      (is (= [[:hatch/picked "lisbon" "click"]] @!seen)
          "declared :event — still wrapped, and the returned intent still
           dispatches"))
    (let [[el _] (crossed render-picker {:on-imperative stable-imperative})]
      (is (identical? stable-imperative (prop el "onImperative"))
          "declared :handler — still the function itself, by identity"))
    (let [[el _] (crossed render-picker {:on-render-row (hfn [label] (str "row:" label))})]
      (is (= "row:x" ((prop el "onRenderRow") "x"))
          "declared :render — still the render wrapper"))
    (let [f (hfn [node] (swap! !instr assoc :hfn-ref node) nil)]
      (is (some? (first (crossed render-picker {:ref f})))
          ":ref is CLAIMED — by React's own contract rather than by the
           declaration (HD-016) — and it is read BEFORE the unclaimed arm,
           so a callback ref written as an h/fn crosses rather than
           refusing"))))

(deftest a-dispatch-from-a-declared-render-position-names-the-position
  (testing "HD-024's core law at the door: the CONTRACT the declaration named
            decides, and a :render contract poisons the ambient frame-locked
            dispatch for the call's dynamic extent — the same id a native
            render position raises, because the position is the thing that
            selected it"
    (let [[el !seen] (crossed render-picker
                             {:on-render-row (hfn [_] (intent/*dispatch* [:hatch/closed]) "never")})]
      (try
        ((prop el "onRenderRow") "x")
        (is false "should have thrown")
        (catch :default e
          (let [d (ex-data e)]
            (is (= :rf.error/hicasso-dispatch-in-render-position (:rf.error/id d)))
            (is (= :on-render-row (:position d)))
            (is (= [:hatch/closed] (:event d)))
            (is (re-find #":on-render-row" (ex-message e))))))
      (is (= [] @!seen) "and nothing reached the frame"))))

(defn- crossed-in-frame
  "[[crossed]] with the boundary's FRAME KEYWORD bound as well — the
  3-arity door, which is what a row body's `intent/*frame*` read (and a
  `route-link` in one) needs. Answers `[element !dispatched]`."
  [frame-kw head props]
  (let [!seen (atom [])
        el    (intent/with-frame frame-kw (fn [ev] (swap! !seen conj ev) nil)
                (fn [] (codec/as-element [head props])))]
    [el !seen]))

(deftest a-render-props-row-is-owned-by-the-boundary-that-supplied-the-callback
  (testing "rf2-2rtt6.74, at the real `renderRow` seam. HD-024's refusal is
            INVOCATION-scoped — poison while the call runs, forward to the
            owner once it has returned — so the handlers a `:render` body
            LOWERS are not poisoned with it. Which is most of what a render
            prop is for: a row that is not interactive works either way, and
            the failure this pins used to land on the USER's click.

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
             (hfn [label]
               (reset! !frame intent/*frame*)
               (reset! !row (codec/as-element
                              [:li {:on-click [:hatch/picked label "row"]}]))
               ;; an event-position h/fn, lowered in the same body
               (codec/as-element
                 [:button {:on-click (hfn [_] [:hatch/closed])}]))})
          btn (intent/with-frame ::other (fn [ev] (swap! !other conj ev) nil)
                (fn [] ((prop el "onRenderRow") "paris")))]
      (is (= ::supplier @!frame)
          "inside the invocation the ambient frame is the OWNER's, not the
           invoking boundary's — the frame a route-link in a row body pins to")
      (is (= [] @!supplier) "the render itself dispatched nothing")
      (is (= [] @!other))

      (testing "and then the browser's click, long after both extents unwound"
        (is (nil? intent/*dispatch*))
        ((prop @!row "onClick") #js {})
        ((prop btn "onClick") #js {})
        (is (= [[:hatch/picked "paris" "row"] [:hatch/closed]] @!supplier)
            "the row's intent vector AND the event-position h/fn both fired
             into the SUPPLYING boundary's recorder")
        (is (= [] @!other)
            "and nothing reached the boundary that merely invoked the render
             prop — which is what makes the ownership assertion non-vacuous")))))

(deftest the-vector-spelling-is-event-first-and-says-so-when-it-is-not
  (testing "the positive half, at the invoker contract the door was built for:
            an EVENT-FIRST foreign call, which is what onDraft makes"
    (let [[el !seen] (crossed picker {:on-draft [:hatch/typed :re-frame.hicasso/value]})]
      ((prop el "onDraft") #js {:target #js {:value "west"}})
      (is (= [[:hatch/typed "west"]] @!seen))))

  (testing "and the case audit #7398 named. The widget calls
            `(f (.-value props) e)` — VALUE-first — so argument one is a
            string, and `.preventDefault` on it is the engine's own
            TypeError naming nothing the author wrote. It is this error
            instead, and it names the POSITION"
    (let [[el !seen] (crossed picker {:on-pick [:re-frame.hicasso/prevent [:hatch/closed]]})]
      (try
        ((prop el "onPick") "paris" #js {:preventDefault (fn [] nil)})
        (is false "should have thrown")
        (catch :default e
          (let [d (ex-data e)]
            (is (= :rf.error/hicasso-intent-needs-the-event (:rf.error/id d)))
            (is (= :on-pick (:position d)))
            (is (= "preventDefault" (:needed d)))
            (is (= "paris" (:argument d)))
            (is (re-find #"h/fn" (ex-message e)) "and it points at the spelling that works"))))
      (is (= [] @!seen) "and nothing dispatched off a half-run handler")))

  (testing "the SAME law, one message, for the markers — which is the whole
            point of stating it once: `::h/value` at a value-first position
            fails the same way and reads the same diagnostic"
    (let [[el _] (crossed picker {:on-pick [:hatch/picked :re-frame.hicasso/value "kind"]})]
      (try
        ((prop el "onPick") "paris" #js {:target #js {:value "x"}})
        (is false "should have thrown")
        (catch :default e
          (let [d (ex-data e)]
            (is (= :rf.error/hicasso-intent-needs-the-event (:rf.error/id d)))
            (is (= "target" (:needed d))))))))

  (testing "and a key-map, whose failure without the law is the WORST of the
            three — no `.key` to look up means no branch, which is a handler
            that silently does nothing"
    (let [[el _] (crossed picker {:on-pick {"Enter" [:hatch/closed]}})]
      (is (= :rf.error/hicasso-intent-needs-the-event
             (error-id #((prop el "onPick") "paris" #js {:key "Enter"}))))))

  (testing "while an intent carrying NEITHER a marker nor a prevent never
            touches its argument, so it is correct under any invoker contract
            and pays no law at all — which is the overwhelmingly common case"
    (let [[el !seen] (crossed picker {:on-pick [:hatch/picked "static" "kind"]})]
      ((prop el "onPick") "paris" #js {})
      (is (= [[:hatch/picked "static" "kind"]] @!seen)))))

;; ---------------------------------------------------------------------------
;; 6 — the hook budget distinction, at React's own dispatcher
;; ---------------------------------------------------------------------------

(deftest the-door-spends-one-hook-and-the-hosted-hooks-are-its-own
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (is false (str "React's internals slot was not found, so this claim is "
                     "UNWITNESSED on this build — fix the probe rather than "
                     "reading this as a pass"))
      (do
        (instr!)
        (fresh!)
        (let [handle (volatile! nil)
              names  (probe/record!
                       (fn [] (vreset! handle
                                       (mount/root! (mount/fresh-container!)
                                                    frame-id [host-page {}]))))]
          (try
            (is (= ["useContext" "useSyncExternalStore"] (vec (take 2 names)))
                (str "the shell's two hooks come FIRST, with nothing before "
                     "them: " (pr-str names)))
            (is (= "useSyncExternalStore" (nth names 2 nil))
                (str "then the door's ONE hook — the SSR gate's adoption "
                     "read (rf2-2rtt6.85), and the whole of what a crossing "
                     "costs: " (pr-str names)))
            (is (every? #{"useContext" "useState" "useEffect"} (drop 3 names))
                (str "and EVERYTHING after it is the widget's own roster — "
                     "useContext/useState/useEffect, however React's dev "
                     "dispatcher counts its reads of them. No useRef, no "
                     "useCallback, no useMemo, and no further hook of "
                     "Hicasso's: " (pr-str names)))
            (is (= 2 (count (filter #{"useSyncExternalStore"} names)))
                (str "exactly TWO on the whole page — the boundary's "
                     "subscription and the door's gate, and no third: "
                     (pr-str names)))
            (is (= 2 (count rt/shell-hook-ledger))
                (str "and HD-020(b)'s ≤2 budget is untouched by the gate, "
                     "which is not a boundary: it holds no subscription and "
                     "reads no frame. " (pr-str rt/shell-hook-ledger)))
            (finally (mount/release! @handle))))))))

;; ---------------------------------------------------------------------------
;; 7 — the host under presence: retention, override crossing, state survival
;; ---------------------------------------------------------------------------

(deftest react-owned-state-survives-a-presence-transition-around-the-host
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (rf/with-frame frame-id
          (rf/dispatch-sync [:hatch/set :widgets [{:id 1 :name "one"}]]))
        (let [handle (mount/root! (mount/fresh-container!) frame-id [tray {}])]
          (mount/settle!)
          (click! handle ".widget-pick")
          (is (= "1" (attr handle ".widget" "data-clicks"))
              "the library's own state moved before the transition")
          (mount/dispatch! handle [:hatch/set :widgets []])
          (is (some? (q handle ".widget"))
              "gone from the model, retained on screen — presence retains a
               host child by key exactly as it retains a native node")
          (is (.contains (.-classList (q handle ".widget")) "widget--exit")
              "the ::h/unmounting override crossed the door as an ordinary
               prop — className — and the foreign component wore it")
          (is (= "1" (attr handle ".widget" "data-clicks"))
              "React-owned state survived entering the exit phase")
          (is (= 1 (:mounts @!instr)))
          (testing "AND THE RETAINED HOST IS STILL LIVE — the half
                    rf2-2rtt6.66's repair could not witness for itself,
                    because the door was in flight when it landed. A
                    declared :event h/fn on a child being animated OUT
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
          (mount/dispatch! handle [:hatch/set :widgets [{:id 1 :name "one"}]])
          (is (not (.contains (.-classList (q handle ".widget")) "widget--exit"))
              "re-entry cancelled the exit and took the override off")
          (is (= "2" (attr handle ".widget" "data-clicks"))
              "and the state survived the WHOLE transition — one click before
               it, one DURING the retained window, both still counted after
               re-entry. Retained key identity means the fiber never
               remounted, so the library's own state was never reset")
          (is (= 1 (:mounts @!instr)))
          ;; and a real departure is a real unmount, on the clock
          (mount/dispatch! handle [:hatch/set :widgets []])
          (js/setTimeout
            (fn []
              (mount/settle!)
              (try
                (is (nil? (q handle ".widget"))
                    "past :timeout-ms the retained host left")
                (is (= (:mounts @!instr) (:cleanups @!instr))
                    "and its own effect cleanups ran — no foreign residue")
                (finally (mount/release! handle) (done))))
            (* 4 timeout-ms)))))))

;; ---------------------------------------------------------------------------
;; 8 — teardown: the hosted cleanups run, the ref detaches, nothing remains
;; ---------------------------------------------------------------------------

(deftest teardown-runs-the-hosted-cleanups-and-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (instr!)
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [screen {}])]
        (mount/settle!)
        (is (= 1 (:mounts @!instr)))
        (is (some? (:ref-node @!instr)))
        (is (zero? (:ref-cleanups @!instr)))
        (let [census (teardown-census! handle)]
          (is (= 1 (:cleanups @!instr))
              "unmounting the Hicasso root ran the FOREIGN effect's cleanup —
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
  (testing "the composition hazard, now that HD-028 ships: a hosted foreign
            component sitting inside a memoised boundary. A write that moves
            only the page chrome re-renders the chrome and stops at the row —
            so the third-party component is not re-rendered AT ALL, and the
            door did not have to know that. The cascade claim rf2-2rtt6.52
            repaired, extended to foreign components, which are exactly the
            ones whose render cost nobody controls."
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (instr!)
          (fresh!)
          (let [handle (mount/root! (mount/fresh-container!) frame-id
                                    [chrome-page {}])]
            (-> (settled!)
                (.then
                  (fn [_]
                    (try
                      (let [before (:renders @!instr)]
                        (is (pos? before) "the host mounted")
                        (mount/dispatch! handle [:hatch/set :label "chrome moved"])
                        (is (= "chrome moved" (.-textContent (q handle ".chrome")))
                            "the page chrome really re-rendered")
                        (is (= before (:renders @!instr))
                            "and the hosted component did not render again —
                             the boundary above it bailed out on value-equal
                             props, so the crossing was never re-run")
                        (is (= 1 (:mounts @!instr))
                            "nor was it remounted"))
                      (finally (mount/release! handle)))))
                (.catch (fn [e] (is false (str e)) nil))
                (.then (fn [_] (done))))))))))

(deftest a-memoised-hosted-component-and-the-doors-honest-cost
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (instr!)
        (fresh!)
        (let [handle (volatile! (mount/root! (mount/fresh-container!)
                                             frame-id [memo-page {}]))]
          (-> (settled!)
              (.then
                (fn [_]
                  (testing "the bail-out HOLDS when the door hands
                            shallow-equal props: scalars cross as values and
                            a :handler crosses by identity"
                    (let [before (:renders @!instr)]
                      (mount/dispatch! @handle [:hatch/set :label "moved"])
                      (is (= "moved" (.-textContent (q @handle ".mlabel")))
                          "the parent boundary really re-rendered")
                      (is (= before (:renders @!instr))
                          "and React.memo held across it — the door mints a
                           fresh props OBJECT per render, but every value in
                           it was shallow-equal")))
                  (mount/release! @handle)
                  (instr!)
                  ;; same frame, re-seeded — a second make-frame mid-test
                  ;; would be a reincarnation, which is its own suite's
                  ;; subject
                  (rf/with-frame frame-id (rf/dispatch-sync [:hatch/seed]))
                  (vreset! handle (mount/root! (mount/fresh-container!)
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
                              value-equality bail-out now SHIPS as HD-028,
                              and it sits ABOVE this seam: it stops the
                              equal-props parent re-render before the door is
                              reached at all, which is the row below.)"
                      (let [before (:renders @!instr)]
                        (mount/dispatch! @handle [:hatch/set :label "moved-again"])
                        (is (= "moved-again" (.-textContent (q @handle ".mlabel"))))
                        (is (= (inc before) (:renders @!instr)))))
                    (finally (mount/release! @handle)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (done)))))))))
