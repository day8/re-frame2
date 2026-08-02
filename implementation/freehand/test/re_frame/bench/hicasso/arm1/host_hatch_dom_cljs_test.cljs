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
  distinction is the whole point of the door — and the door itself mints
  NO wrapper component, no fiber and no hook: the foreign component is
  the element's own type, lowered at the crossing inside the parent
  boundary's render window. The dispatcher-level probe below reads the
  shell's two hooks first, exactly one subscription hook on the whole
  page, and nothing after the shell that is not the widget's own
  roster.

  ## One honest limit, found rather than smoothed

  A child handed to `h/presence` is lowered inside the presence
  component's OWN render, where no ambient frame is bound — presence
  binds none — so an intent-bearing prop on ANY presence child (native
  or host alike) lowers against no dispatch: a vector refuses at render,
  a declared-`:event` `h/fn` refuses loudly at invocation. Loud, never
  silent, but dead. The presence rows below therefore drive the host
  child through model changes and prove state/retention/override
  crossing; the gap is filed as its own bead rather than smoothed here,
  because it is presence's frame plumbing, not the door's.

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
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom" :as react-dom])
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
                  :received-imperative nil :ref-node nil :ref-cleanups 0}))

(defn- widget
  "The worst reasonable case, as a plain React function component — raw
  hooks, its own DOM, its own callback contracts, written exactly as a
  JS library author would (ref as a prop: the React 19 contract)."
  [^js props]
  (swap! !instr update :renders (fnil inc 0))
  (when-some [f (.-onImperative props)]
    (swap! !instr assoc :received-imperative f))
  (let [theme       (react/useContext theme-context)
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

(defview host-page
  "The minimal page the hook probe counts: one shell, one hosted widget,
  nothing else."
  [_]
  [picker {:label (rt/sub [:hatch/label])}])

(defview tray
  "The host under presence. No callback props here, deliberately — see
  the ns docstring's honest limit: presence lowers its children with no
  ambient frame, so intent-bearing props on any presence child are a
  filed gap, not this witness's subject."
  [_]
  [presence {:timeout-ms timeout-ms}
   (for [w (rt/sub [:hatch/widgets])]
     [picker {:key   (:id w)
              :label (:name w)
              :re-frame.hicasso/unmounting {:class "widget--exit"}}])])

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
  "Unmount, read the live-reference census, THEN release — the
  lifecycle suite's ordering, and for the same reason: a census taken
  after `release!` reads an emptied table whatever teardown did."
  [handle]
  (react-dom/flushSync (fn [] (.unmount (:root handle))))
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
                    (is (= released @census))
                    (done))))
              (.catch (fn [e] (is false (str e)) (done)))))))))

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
                                   :plain-fn   identity}])
          ^js props (unchecked-get el "props")]
      (is (= "compact" (unchecked-get props "variant"))
          "keyword values cross by name")
      (is (identical? identity (unchecked-get props "plainFn"))
          "functions cross by identity — a value handed to a foreign API,
           not a position")
      (let [row (aget (unchecked-get props "menuItems") 0)]
        (is (= 1 (unchecked-get row "day-of-week"))
            "nested keys are NOT renamed — shallow means shallow")
        (is (nil? (unchecked-get row "dayOfWeek")))))))

;; ---------------------------------------------------------------------------
;; 6 — the hook budget distinction, at React's own dispatcher
;; ---------------------------------------------------------------------------

(deftest the-door-adds-no-hicasso-hook-and-the-hosted-hooks-are-its-own
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
                     "them — the door minted no wrapper and spent no hook on "
                     "the way to the foreign component: " (pr-str names)))
            (is (every? #{"useContext" "useState" "useEffect"} (drop 2 names))
                (str "and EVERYTHING after them is the widget's own roster — "
                     "useContext/useState/useEffect, however React's dev "
                     "dispatcher counts its reads of them. No useRef, no "
                     "useCallback, no useMemo, and no hook of Hicasso's: "
                     (pr-str names)))
            (is (= 1 (count (filter #{"useSyncExternalStore"} names)))
                (str "exactly ONE subscription hook on the whole page — the "
                     "one boundary's. HD-020's ≤2 budget is a statement about "
                     "Hicasso's boundaries; the hosted component's hooks are "
                     "its own affair, and that distinction is the door's "
                     "whole point: " (pr-str names)))
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
          (mount/dispatch! handle [:hatch/set :widgets [{:id 1 :name "one"}]])
          (is (not (.contains (.-classList (q handle ".widget")) "widget--exit"))
              "re-entry cancelled the exit and took the override off")
          (is (= "1" (attr handle ".widget" "data-clicks"))
              "and the state survived the WHOLE transition — retained key
               identity means the fiber never remounted")
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
                              value-equality bail-out in flight on
                              rf2-2rtt6.52 sits ABOVE this seam and stops the
                              equal-props parent re-render before the door is
                              reached at all.)"
                      (let [before (:renders @!instr)]
                        (mount/dispatch! @handle [:hatch/set :label "moved-again"])
                        (is (= "moved-again" (.-textContent (q @handle ".mlabel"))))
                        (is (= (inc before) (:renders @!instr)))))
                    (finally (mount/release! @handle) (done)))))
              (.catch (fn [e] (is false (str e)) (done)))))))))
