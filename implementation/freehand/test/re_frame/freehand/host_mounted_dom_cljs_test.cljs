(ns re-frame.freehand.host-mounted-dom-cljs-test
  "D022 acceptance 1 and 4 — the `v/defhost` crossing MOUNTED, against a
  real `react-dom/client` commit.

  The headless suite (`host-door-cljs-test`) proves the declaration, the
  three disjoint planes and the structural projection, host-neutrally. None
  of that runs React. What is left, and what this file owes, is the half
  that only a real mount can answer: that the contract the declaration
  advertises is the contract the registered React component actually
  receives, and that the callback positions carry D008's identity laws
  rather than a plain function that happens to work once.

  ## The two witnesses

  D022 acceptance 4 asks for a value/callback component and a hook-owning
  wrapper through the SAME declaration kind, because \"leaf\" and
  \"wrapper\" describe the registered implementation and were explicitly
  rejected as a `:kind` split on the declaration.

  * [[vega-chart]] is the React-Vega shape: a value spec in, a selection
    out, a `:map-props` adapter turning the authored Clojure spec into the
    JS object the library would want. Nothing else is declared.
  * [[hook-panel]] is the wrapper shape: it owns `useState`, `useRef`,
    `useEffect` and a React context Provider, and it renders the caller's
    children inside its own tree.

  Two implementations that could not be less alike, and the declarations
  differ only in their options. That is the claim.

  ## Why the assertions are shaped the way they are

  Every callback claim is measured on the EXACT function object React was
  handed — [[hook-panel]] records it from its own props on every commit —
  rather than on a value this file constructed. A test that builds its own
  proxy proves the site machinery works; only the recorded one proves the
  host path went through it.

  Identity is asserted with `identical?` over the recorded list rather than
  through a set, because a JS function's CLJS hash is not the thing under
  test.

  This file rides the browser lane through its `-dom-cljs-test` suffix, and
  also matches the node suites' broader regex — where the mounted rows have
  no DOM and say so rather than passing quietly. The two rows that need
  only the emitter, not a commit, run in both."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.test-support :as test-support]))

(def react-007 (conf/fixture :FH-REACT-007))

;; ===========================================================================
;; The ledger — what the REGISTERED COMPONENT saw, in its own words
;; ===========================================================================
;;
;; Written from inside the React components, so every claim below is about
;; what really crossed rather than about what this file passed in.

(def ^:private ledger (atom nil))

(defn- reset-ledger! []
  (reset! ledger {:commits      0
                  :callback-ids []
                  :rows-shape   nil
                  :notes        []
                  :live-panels  0})
  nil)

(defn- one-identity?
  "Are all of `fs` the same object? `identical?` rather than a set, because
  a JS function's CLJS hash is not what this is asking about."
  [fs]
  (and (seq fs) (every? #(identical? (first fs) %) fs)))

;; ===========================================================================
;; Witness 1 — a hook-owning wrapper (D022 acceptance 4)
;; ===========================================================================

(defonce ^:private theme-context
  ;; Default "default", so a consumer reached from a DIFFERENT React tree
  ;; reads the default and "outer" read back is proof of ONE shared tree.
  (react/createContext "default"))

(def ^:private theme-probe
  "A raw React consumer, handed to the host as an ordinary child value."
  (fn [_props]
    (react/createElement "span" #js {:className "theme-probe"}
                         (react/useContext theme-context))))

(def hook-panel
  "A React component that owns hooks — `useState`, `useRef`, `useEffect`
  and a context Provider — and renders the caller's children inside its own
  tree. The wrapper shape, and deliberately NOT a second declaration kind:
  D022 rejected a leaf/wrapper `:kind` split by name, because which of the
  two a component is is a fact about its implementation and not about its
  boundary."
  (fn [^js props]
    (let [label     (.-label props)
          rows      (.-rows props)
          on-select (.-onSelect props)
          on-note   (.-onNote props)
          [opened set-opened] (react/useState 0)
          first-seen (react/useRef nil)]
      (when (nil? (.-current first-seen))
        (set! (.-current first-seen) on-select))
      ;; No dependency array: this runs after EVERY commit, which is what
      ;; makes `:commits` a count of committed renders rather than of render
      ;; passes. An abandoned pass never reaches here, and that is the point.
      (react/useEffect
        (fn []
          (swap! ledger (fn [l]
                          (-> l
                              (update :commits inc)
                              (update :callback-ids conj on-select)
                              (assoc :rows-shape (cond
                                                   (vector? rows) :cljs-vector
                                                   (array? rows)  :js-array
                                                   :else          :other)))))
          js/undefined))
      (react/useEffect
        (fn []
          (swap! ledger update :live-panels inc)
          (fn [] (swap! ledger update :live-panels dec)))
        #js [])
      (react/createElement
        "div" #js {:className   "hook-panel"
                   :data-label  label
                   :data-opened (str opened)}
        (react/createElement "button"
                             #js {:className "select"
                                  :onClick   (fn [] (when on-select (on-select "mark")))}
                             "select")
        (react/createElement "button"
                             #js {:className "note"
                                  :onClick   (fn [] (when on-note (on-note "noted")))}
                             "note")
        (react/createElement "button"
                             #js {:className "open"
                                  :onClick   (fn [] (set-opened inc))}
                             "open")
        ;; The caller's children, inside the component's OWN tree and under
        ;; its OWN context. One shared React tree is what makes this legal.
        (react/createElement (.-Provider theme-context) #js {:value "outer"}
                             (.-children props))))))

;; ===========================================================================
;; Witness 2 — a React-Vega-class value/callback component (acceptance 4)
;; ===========================================================================

(def vega-chart
  "The React-Vega shape: a spec in, a selection out. It reads a real JS
  object off `props.spec`, which is what a charting library wants and what
  the authored Clojure map is NOT — so the declaration carries the one
  `:map-props` adapter, and the crossing itself stays free of any per-prop
  conversion language."
  (fn [^js props]
    (let [spec      (.-spec props)
          on-select (.-onSelect props)]
      (react/createElement
        "div" #js {:className "vega-chart" :data-mark (.-mark spec)}
        (.map (.-values spec)
              (fn [value _i]
                (react/createElement
                  "button"
                  #js {:key        (str value)
                       :className  "vega-mark"
                       :data-value (str value)
                       :onClick    (fn [] (when on-select (on-select value)))}
                  (str value))))))))

;; ===========================================================================
;; The declarations — one kind, two very different implementations
;; ===========================================================================

(v/defhost panel-host
  "The hook-owning wrapper, declared."
  hook-panel
  {:callbacks {:onSelect :event :onNote :handler}
   :children  :required
   :ssr       :client-only})

(v/defhost vega-host
  "The value/callback chart, declared. Same verb, same descriptor kind."
  vega-chart
  {:callbacks {:onSelect :event}
   :children  :none
   :ssr       :client-only
   :map-props (fn [p] {:spec (clj->js (:spec p))})})

(v/defhost renaming-host
  "A host whose adapter tries to rename its way past the reserved-fact
  check — the browser half of the naming law FH-REACT-007 §prop-names
  states. The adapter owns VALUES; the names stay the caller's."
  vega-chart
  {:callbacks {:onSelect :event}
   :children  :none
   :ssr       :client-only
   :map-props (fn [_p] {"key" "smuggled" "onSelect" "smuggled" :x/spec 1})})

;; ===========================================================================
;; The application under mount
;; ===========================================================================

(rf/reg-sub :d022/label      (fn [db _] (:label db)))
(rf/reg-sub :d022/prefix     (fn [db _] (:prefix db)))
(rf/reg-sub :d022/selections (fn [db _] (:selections db)))

(rf/reg-event :d022/selected
  (fn [{:keys [db]} [_ v]] {:db (update db :selections conj v)}))
(rf/reg-event :d022/label-changed
  (fn [{:keys [db]} [_ l]] {:db (assoc db :label l)}))
(rf/reg-event :d022/prefix-changed
  (fn [{:keys [db]} [_ p]] {:db (assoc db :prefix p)}))

(v/defview panel-page
  "The use site, and the whole integration. `prefix` is read INSIDE the
  render, so the `v/event` body is re-authored on every commit while the
  site — and therefore the function React holds — stays put. That gap is
  where `latest committed body` lives."
  [_]
  (let [label  (v/sub [:d022/label])
        prefix (v/sub [:d022/prefix])]
    [:section.panel-page
     [panel-host {:label    label
                  ;; An ordinary prop is DATA and crosses SHALLOWLY: this
                  ;; arrives as the Clojure vector it is, not as a JS array.
                  :rows     [1 2 3]
                  :onSelect (v/event [mark] [:d022/selected (str prefix "-" mark)])
                  :onNote   (v/handler [n] (swap! ledger update :notes conj n))}
      [:em.kid "freehand child"]
      (react/createElement theme-probe nil)]]))

(v/defview vega-page
  [_]
  (let [prefix (v/sub [:d022/prefix])]
    [:figure.vega-page
     [vega-host {:spec     {:mark "bar" :values [10 20]}
                 :onSelect (v/event [value] [:d022/selected (str prefix "-" value)])}]]))

;; ===========================================================================
;; Harness
;; ===========================================================================

(def ^:private fid :dom/host-mounted)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (reset-ledger!)
                      (root/reset-registry!)
                      (fr/reset-boundaries!))}))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit AND its flushed effects rather than racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- setup! [db]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid db)
  nil)

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))
(defn- read* [query] (rf/subscribe-once query {:frame fid}))
(defn- mount! [container form] (v/mount form container {:frame fid}))
(defn- q [container selector] (.querySelector container selector))

(defn- teardown! [container mounted]
  (v/unmount! mounted)
  (.remove container)
  nil)

(def ^:private seed {:label "alpha" :prefix "p1" :selections []})

;; ===========================================================================
;; D022 acceptance 1 — one mounted fixture, the whole declared contract
;; ===========================================================================

(deftest d022-a-mounted-host-carries-the-whole-declared-contract
  (testing "D022 acceptance 1. One crossing, mounted, and every law the
            declaration advertises measured on it: value props shallow and
            exact, an `:event` position and a `:handler` position doing
            their two different jobs, the caller's children inside the
            registered component's own tree and under its own context,
            ONE callback identity across every commit, the LATEST committed
            body behind that unchanged identity, and total retirement after
            unmount.

            The identity and the body claims are the pair that matters, and
            they pull in opposite directions: React must see a prop that
            never changes, and the adopter must get the behaviour they most
            recently wrote. A host position that handed over the authored
            closure would pass the second and fail the first; one that
            memoised the closure would pass the first and fail the second."
    (if-not (browser?)
      (skip! "the browser job runs the mounted host crossing")
      (async done
        (setup! seed)
        (let [container (host-node!)]
          (-> (act #(mount! container [panel-page {}]))
              (.then
                (fn [mounted]
                  (let [panel (q container ".hook-panel")]
                    (is (some? panel) "the registered component really rendered")
                    (is (= "alpha" (.getAttribute panel "data-label"))
                        "a value prop crossed under its exact name")
                    (is (= :cljs-vector (:rows-shape @ledger))
                        "and crossed SHALLOWLY — no deep Clojure-to-JavaScript walk")
                    (is (= "freehand child" (.-textContent (q container ".hook-panel em.kid")))
                        "the caller's children are inside the component's own tree")
                    (is (= "outer" (.-textContent (q container ".hook-panel .theme-probe")))
                        "and read the component's OWN React context — one shared
                         tree, not a portal or a nested root")
                    (is (= 1 (:live-panels @ledger))
                        "the wrapper's own mount effect ran exactly once"))
                  (act (fn [] (.click (q container "button.select")) mounted))))
              (.then
                (fn [mounted]
                  (is (= ["p1-mark"] (read* [:d022/selections]))
                      "the :event position dispatched its event vector")
                  (act (fn [] (.click (q container "button.note")) mounted))))
              (.then
                (fn [mounted]
                  (is (= ["noted"] (:notes @ledger))
                      "the :handler position ran imperative work")
                  (is (= ["p1-mark"] (read* [:d022/selections]))
                      "and dispatched nothing of its own — the two roles are
                       different contracts, not two spellings of one")
                  ;; A commit that moves BOTH the rendered value and the
                  ;; callback's body, so the next two assertions cannot both
                  ;; be read off a tree that never re-rendered.
                  (act (fn []
                         (send! [:d022/prefix-changed "p2"])
                         (send! [:d022/label-changed "beta"])
                         mounted))))
              (.then
                (fn [mounted]
                  (is (= "beta" (.getAttribute (q container ".hook-panel") "data-label"))
                      "the re-render really committed")
                  (is (< 1 (:commits @ledger))
                      "and there was more than one commit to compare across")
                  (is (one-identity? (:callback-ids @ledger))
                      "React was handed the SAME function object every time —
                       a declared position is one site, and a site owns its
                       identity")
                  (act (fn [] (.click (q container "button.select")) mounted))))
              (.then
                (fn [mounted]
                  (is (= ["p1-mark" "p2-mark"] (read* [:d022/selections]))
                      "and that unchanged object ran the LATEST committed body")
                  (act #(teardown! container mounted))))
              (.then
                (fn [_]
                  (let [held (first (:callback-ids @ledger))]
                    (is (fn? held) "the component really recorded what it was handed")
                    (held "after-unmount")
                    (is (= ["p1-mark" "p2-mark"] (read* [:d022/selections]))
                        "a proxy a foreign component still holds is INERT after
                         unmount — it dispatches nothing rather than firing into
                         whatever owns the frame now"))
                  (is (= 0 (:live-panels @ledger))
                      "and the registered component's own cleanup ran")
                  (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest d022-a-hosts-callback-does-not-fire-into-its-successor
  (testing "D022 acceptance 1, the retirement half that unmount alone cannot
            show: a proxy held across a REMOUNT of the same view — the shape
            a hot reload takes, and the shape a foreign library takes when it
            caches the callback it was given.

            The successor occupies the same view id and the same site key, so
            a scheme that keyed identity on either would let the stale
            reference fire into the new occurrence. It must stay inert while
            the successor's own callback works."
    (if-not (browser?)
      (skip! "the browser job runs the remount")
      (async done
        (setup! seed)
        (let [container (host-node!)]
          (-> (act #(mount! container [panel-page {}]))
              (.then (fn [mounted] (act #(teardown! container mounted))))
              (.then
                (fn [_]
                  (let [stale     (first (:callback-ids @ledger))
                        container (host-node!)]
                    (-> (act #(mount! container [panel-page {}]))
                        (.then
                          (fn [mounted]
                            (stale "from-the-dead")
                            (is (= [] (read* [:d022/selections]))
                                "the stale proxy fired into nothing")
                            (is (= 1 (count (filter #(= :re-frame.freehand.host-mounted-dom-cljs-test/panel-page %)
                                                    (keys (fr/boundary-cache)))))
                                "and the successor reused the ONE cached boundary
                                 for this view id, which is the reload invariant
                                 that makes the stale reference plausible")
                            (act (fn [] (.click (q container "button.select")) mounted))))
                        (.then
                          (fn [mounted]
                            (is (= ["p1-mark"] (read* [:d022/selections]))
                                "while the successor's own callback is live")
                            (act #(teardown! container mounted))))
                        (.then (fn [_] (done)))
                        (.catch (fn [e]
                                  (is false (str "remount rejected: " e))
                                  (done)))))))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; D022 acceptance 4 — the value/callback witness, through the same verb
;; ===========================================================================

(deftest d022-a-value-callback-chart-crosses-through-the-same-declaration
  (testing "D022 acceptance 4: a React-Vega-class component — a value spec
            in, a selection out — reaching the page through the same verb
            and the same descriptor kind as the hook-owning wrapper above,
            from a call site that is one map.

            The `:map-props` adapter is the whole of the conversion story:
            it prepares the ONE value the authored Clojure map could not
            hold, and the crossing itself gains no per-prop conversion
            language for it."
    (if-not (browser?)
      (skip! "the browser job runs the chart mount")
      (async done
        (setup! seed)
        (let [container (host-node!)]
          (-> (act #(mount! container [vega-page {}]))
              (.then
                (fn [mounted]
                  (is (= "bar" (.getAttribute (q container ".vega-chart") "data-mark"))
                      "the adapter's JS object reached the library as one")
                  (is (= 2 (.-length (.querySelectorAll container ".vega-mark")))
                      "and the chart rendered from it")
                  (act (fn [] (.click (q container "button.vega-mark")) mounted))))
              (.then
                (fn [mounted]
                  (is (= ["p1-10"] (read* [:d022/selections]))
                      "a value the LIBRARY chose came back as an ordinary
                       re-frame event")
                  (act #(teardown! container mounted))))
              (.then (fn [_] (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; The naming law, browser half — a `:map-props` adapter may not rename
;; ===========================================================================

(deftest d022-a-map-props-adapter-answers-under-the-callers-naming-law
  (testing "Per FH-REACT-007 §prop-names §map-props-adapter: the adapter
            owns the ordinary plane's VALUES, and the names stay the
            caller's.

            This is the second half of the defect the reopen named. The
            reserved-fact check filtered the adapter's answer for the
            KEYWORDS `:key`, `:children` and each declared position, while
            the crossing named every key by `name` — so an adapter
            returning `\"key\"` or `\"onSelect\"` passed the filter and
            reached React under the reserved name anyway. Holding the
            answer to the caller's own exactness law FIRST is what makes
            that filter total.

            No commit needed: the refusal is the emitter's, so this row
            runs in the node lane too."
    (let [d (conf/caught-data #(fr/element [renaming-host {:spec {:mark "bar" :values [1]}}]))]
      (is (= (get-in react-007 [:prop-names :map-props-adapter :error-id])
             (:rf.error/id d))
          "the adapter's answer is refused")
      (doseq [k (get-in react-007 [:prop-names :map-props-adapter :rejects])]
        (is (some #(= k %) (:props d))
            (str "and " (pr-str k) " is named in the refusal"))))))

(deftest d022-a-well-named-adapter-still-crosses
  (testing "The positive control for the row above: the SAME machinery, an
            adapter that spells its answer exactly, and the crossing
            happens. Without this, the refusal above could be a host that
            never crosses at all."
    (is (some? (fr/element [vega-host {:spec {:mark "bar" :values [1]}}]))
        "an exactly-named adapter answer produces a real React element")))
