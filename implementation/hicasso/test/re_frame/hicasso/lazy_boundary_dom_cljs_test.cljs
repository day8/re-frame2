(ns re-frame.hicasso.lazy-boundary-dom-cljs-test
  "CODE SPLITTING — WHAT CROSSES A `React.lazy` BOUNDARY, AND WHAT A
  REJECTED ONE NEVER DOES AGAIN (rf2-hic-041).

  The bridge itself is `n/lazy` and it landed with the ABI helpers
  (rf2-hic-032): a `react/lazy` record carrying the tier marker, its
  `:name` filled in on arrival, its `:server` pinned to Client-only.
  [[re-frame.hicasso.native-abi-cljs-test]] settles the marker and
  [[re-frame.hicasso.native-abi-dom-cljs-test]] settles the arrival.
  **This file is about the BOUNDARY** — the five states a split region
  moves through, what survives each crossing, and one promise the
  documentation was making that React cannot keep.

  ## The rows

  | row | what it establishes | the narrowing it catches |
  |---|---|---|
  | [[the-module-gate-dedupes-a-hover-and-a-click-into-one-load]] | the route/module split's own dedupe rule, at the state it actually races on | a gate that skips only `:loaded`, so a warm-on-hover followed by a click starts a second load |
  | [[a-lazy-boundary-paints-the-fallback-then-the-component-with-the-props-it-was-written-with]] | load, fallback and arrival, with the props and the one child intact across a crossing that was deferred | a bridge that re-ran the loader on arrival; a `:children` slot that lost the single child |
  | [[a-lazy-suspension-retains-the-committed-subscription-and-the-arrival-leaves-exact-ownership]] | the post-commit suspension result, re-measured across a LAZY boundary specifically | a lazy retry that quietly re-subscribed — the screen is right under it |
  | [[a-rejected-lazy-head-re-throws-its-cached-error-and-only-a-fresh-head-reloads]] | rejection is TERMINAL at the payload; `:reset-key` clears the boundary and reloads nothing | the claim, made in this repo until this bead, that changing `:reset-key` retries the chunk |
  | [[a-client-only-lazy-region-writes-nothing-and-a-declared-fallback-writes-the-skeleton]] | which DECLARATION actually emits server bytes, at the `defhost` crossing | a region documented as sending a skeleton whose declaration sends nothing |
  | [[a-bare-lazy-head-under-native-suspense-writes-nothing-and-never-calls-its-loader]] | `n/lazy`'s OWN Client-only policy, with nothing in front of it and a raw `react/lazy` control beside it | a policy that lives on the marker while the head stays an ungated lazy record — and a witness whose zero is really two `defhost` gates |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described | a row that started returning early |

  ## The instrument is the LOADER CALL COUNT, not the paint

  A fallback that stays put after a retry looks identical whether React
  re-threw a cached rejection or ran the loader again and got a second
  failure. Only the count separates them, and the whole of
  [[a-rejected-lazy-head-re-throws-its-cached-error-and-only-a-fresh-head-reloads]]
  turns on that difference: widen `(= 1 calls)` to `(pos? calls)` and the
  row goes green against the behaviour it exists to deny.

  React 19.2's `lazyInitializer` calls the loader only while the payload
  is Uninitialized (`_status === -1`); a rejection sets `_status = 2` and
  every later read `throw`s the cached error. The payload is created once,
  by `react/lazy`, and nothing in this tier — or in React's public API —
  resets it. So the retry a rejected chunk needs is a NEW HEAD, which is
  the same allocation a hot reload performs, which is why the HMR fact and
  the retry fact are one measurement here rather than two.

  ## Where the retryable path actually is

  It is the module gate, not the React boundary: `shadow.lazy/load` is an
  ordinary promise-returning function and calling it again really does
  fetch again. [[the-module-gate-dedupes-a-hover-and-a-click-into-one-load]]
  witnesses both halves of that — the load that must NOT happen twice and
  the retry that must.

  ## Lanes

  `-dom-cljs-test`, so `:browser-test` runs the whole file against a real
  React DOM. THREE rows need no DOM and run under `:node-test` as well:
  the module gate is pure re-frame, and both server rows are
  `renderToString`. Every other row degrades on the node lane to a STATED
  skip rather than to a green for a render that never happened.

  ## Two server rows, and why one could not have been both

  They ask different questions and the second is not a widening of the
  first. Row 5 is about a DECLARATION — which `defhost` key puts bytes in
  a server response — and it renders through two Client-only hosts
  because that is the shape it is describing. Row 7 is about `n/lazy`
  ITSELF, so it must have nothing in front of it: under row 5's shape the
  loader would stay uncalled even if the lazy head were server-active,
  which is exactly what merged-PR audit #7969 found when row 5 was the
  only server row here. A witness dominated by unrelated gates measures
  the gates."
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::lazy-boundary)

;; ---------------------------------------------------------------------------
;; Registrations — above `use-fixtures`, which captures its source-store
;; baseline when the form is evaluated (the sibling suites' convention).
;; ---------------------------------------------------------------------------

(rf/reg-sub :lzb/label (fn [db _] (:label db)))
(rf/reg-sub :lzb/module (fn [db _] (get-in db [:modules :admin] :absent)))

(rf/reg-event :lzb/seed (fn [_ _] {:db {:label "alpha"}}))
(rf/reg-event :lzb/relabel (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

;; How many times the module-loading effect actually ran. The dedupe row's
;; only instrument: app-db's `:loading` says what the gate INTENDED, and
;; this says what the network was actually asked for.
(defonce ^:private !module-loads (atom 0))

(rf/reg-fx :lzb/load-module!
  {:doc       "Stand-in for `shadow.lazy/load`, counted."
   :platforms #{:client}}
  (fn [_ctx _spec] (swap! !module-loads inc) nil))

;; THE GATE, with the repair rf2-hic-041 was filed for.
;;
;; The set is `#{:loading :loaded}` and not `#{:loaded}`. Warming a module
;; from a hover or focus intent is the documented reason to dispatch this
;; early, and the click that follows arrives while the first load is still
;; in flight — so a gate written against the finished state alone starts a
;; second fetch for the chunk it is already fetching.
(rf/reg-event :lzb/wanted
  {:doc "Ask for the admin module, at most once per in-flight load."}
  (fn [{:keys [db]} _]
    (when-not (contains? #{:loading :loaded} (get-in db [:modules :admin]))
      {:db (assoc-in db [:modules :admin] :loading)
       :fx [[:lzb/load-module! {:on-loaded [:lzb/module-loaded]
                                :on-failed [:lzb/module-failed]}]]})))

(rf/reg-event :lzb/module-loaded
  (fn [{:keys [db]} _] {:db (assoc-in db [:modules :admin] :loaded)}))

(rf/reg-event :lzb/module-failed
  (fn [{:keys [db]} _] {:db (assoc-in db [:modules :admin] :failed)}))

;; ---------------------------------------------------------------------------
;; The exercised population — a MEASUREMENT, not a claim
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  #{:gate/dedupe-and-retry
    :lazy/fallback-then-arrival
    :lazy/post-commit-suspension
    :lazy/rejection-is-terminal
    :lazy/fresh-head-reloads
    :lazy/server-render
    :lazy/unshadowed-server-gate})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; Loaders, heads, and the components that arrive in them
;; ---------------------------------------------------------------------------

(defn- deferred-loader
  "A `React.lazy` loader this file settles by hand, counting its own
  calls.

  One loader may back SEVERAL heads, and that sharing is the point of the
  rejection row: it is how the row shows that a rejected chunk's
  terminality belongs to the PAYLOAD `react/lazy` minted and not to the
  function it was handed."
  []
  (let [!calls  (atom 0)
        !settle (atom nil)]
    {:calls  !calls
     :settle !settle
     :load   (fn []
               (swap! !calls inc)
               (js/Promise. (fn [resolve reject]
                              (reset! !settle {:resolve resolve :reject reject}))))}))

(defn- settle!
  "Hand the pending loader its answer. `k` is `:resolve` or `:reject`."
  [loader k v]
  (let [handlers @(:settle loader)]
    (when-not handlers
      (throw (js/Error. "the loader has not been called yet — nothing to settle")))
    ((get handlers k) v)
    nil))

;; The props object the arrived component was handed. The lazy-specific
;; question: the parent wrote these before the chunk existed.
(defonce ^:private !arrived-props (atom nil))

(defn- chart-body
  "The island in the split chunk. It reads a prop the parent wrote while
  the module was still in flight, and renders whatever child was written
  at the crossing."
  [^js props]
  (reset! !arrived-props props)
  (n/$ :div {:id "chart"}
       (n/$ :span {:class "series"} (str (count (.-points props))))
       (.-children props)))

(def ^:private chart-cell
  "What the chunk `def`s. `n/component` rather than `n/defcomponent`
  because the row needs the mint as a VALUE to resolve a promise with,
  which is what a module's exported var is at the moment it lands."
  (n/component "app/heavy-chart" :client-only chart-body))

;; --- the Suspense hosts ----------------------------------------------------

(h/defhost suspense-host
  "The guide's declaration: `:fallback` is a ReactNode SLOT, so hiccup
  written there crosses to React as an element."
  (.-Suspense react)
  {:slots #{:fallback}})

(h/defhost suspense-skeleton-host
  "The same crossing with a declared `:fallback` — `defhost`'s
  Client-only PLACEHOLDER, which is a different key with the same name
  and the only one of the two that can put bytes in a server response.
  Declared here because the documentation promised those bytes from the
  host above, which emits none."
  (.-Suspense react)
  {:slots    #{:fallback}
   :fallback [:div {:id "server-skeleton" :aria-busy true} "loading"]})

;; --- scenario 2: load, fallback, arrival -----------------------------------

(def ^:private paint-loader (deferred-loader))
(def ^:private paint-head (n/lazy (:load paint-loader)))
(h/defhost paint-host paint-head)

;; --- scenario 3: the post-commit suspension --------------------------------

(def ^:private own-loader (deferred-loader))
(def ^:private own-head (n/lazy (:load own-loader)))
(h/defhost own-host own-head)

;; --- scenario 4: rejection, and the head that replaces it ------------------
;;
;; TWO heads over ONE loader. `reject-head` is the one that rejects and
;; can never be revived; `replacement-head` is what a retry — or a hot
;; reload — allocates, and it reaches the SAME function.

(def ^:private reject-loader (deferred-loader))
(def ^:private reject-head (n/lazy (:load reject-loader)))
(def ^:private replacement-head (n/lazy (:load reject-loader)))
(h/defhost reject-host reject-head)
(h/defhost replacement-host replacement-head)

;; --- scenario 6: the server render -----------------------------------------

(def ^:private ssr-loader (deferred-loader))
(def ^:private ssr-head (n/lazy (:load ssr-loader)))
(h/defhost ssr-host ssr-head)

;; --- scenario 7: the UNSHADOWED server render ------------------------------
;;
;; Scenario 6's row is dominated by two `defhost` declarations, each of
;; them independently Client-only, so it cannot distinguish `n/lazy`'s own
;; policy from two unrelated gates in front of it (audit #7969). These two
;; heads sit under NOTHING but React's own Suspense.

(def ^:private bare-loader (deferred-loader))
(def ^:private bare-head (n/lazy (:load bare-loader)))

;; THE CONTROL, and the row is worthless without it. `renderToString` is
;; allowed to be inert — a renderer that never reached the region would
;; leave the count at zero for a reason that has nothing to do with the
;; gate. This head is `react/lazy` with no gate at all, in the same shape
;; and through the same `n/$`, and its count says what an ungated head
;; does here.
(def ^:private raw-loader (deferred-loader))
(def ^:private raw-head (react/lazy (fn [] (.then ((:load raw-loader))
                                                  (fn [c] #js {:default c})))))

(defn- suspense-tree
  "The shipped shape from the audit's control, written in `n/$`: a
  Suspense host with a fallback SLOT and one lazy head under it. No
  `defhost`, no provider, no root element — nothing between
  `renderToString` and the head but React."
  [head]
  (n/$ :div
       (n/$ :h1 {:id "title"} "metrics")
       (n/$ (.-Suspense react)
            {:fallback (n/$ :div {:id "react-fallback"} "loading")}
            (n/$ head {:points #js [1 2 3]}))))

;; ---------------------------------------------------------------------------
;; Views
;; ---------------------------------------------------------------------------

(h/defview own-panel
  "A committed boundary inside the Suspense region, and the subject of the
  ownership row: it holds a subscription before anything suspends."
  [_]
  [:p {:id "panel"} (str "label=" (h/sub [:lzb/label]))])

(defonce ^:private !attempts (atom 0))

(h/defview attempt-marker
  "A sibling INSIDE the error boundary. Its render count is the retry
  row's premise: a `:reset-key` change that cleared the caught error and
  re-rendered the children moves it, and one that did nothing does not —
  without it, `the loader was not called again` would be green for a
  boundary that never retried at all."
  [_]
  (swap! !attempts inc)
  [:i {:id "attempt"} "live"])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- skip! [why] (is true (str "a lazy-boundary DOM claim needs a real React DOM — " why)))

(defn- seeded! []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:lzb/seed]))
  frame-id)

(defn- tree [hiccup] (mount/provider frame-id (codec/root-element frame-id hiccup)))

(defn- mount-concurrent!
  "A concurrent root rendered WITHOUT `flushSync`. Suspense's fallback and
  its retry are React's own scheduling; a row that forced them sync would
  be a row about the forced schedule. Every wait below is a poll."
  [container element]
  (let [root (react-dom-client/createRoot container)]
    (.render root element)
    {:root root :container container :frame frame-id}))

(defn- node [handle id] (.querySelector ^js (:container handle) (str "#" id)))

(defn- text-at [handle sel]
  (some-> (.querySelector ^js (:container handle) sel) .-textContent))

(defn- visible?
  "Is `id`'s node in the layout? A Suspense boundary showing its fallback
  keeps the primary tree's host nodes in the document and takes them out
  of the layout with `display: none`, so presence is not visibility and
  `textContent` still reports hidden text."
  [handle id]
  (when-some [el (node handle id)]
    (not= "none" (.-display (js/getComputedStyle el)))))

(defn- painted
  [handle id]
  (when (visible? handle id) (.-textContent ^js (node handle id))))

(defn- poll [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000}))

(defn- k [query-v] [frame-id query-v])

(defn- ownership [] (dissoc (inventory/residue) :entries))

(defn- reader-count
  "A COUNT rather than the reader vector: a registration and the cells it
  acquired hold each other, so `cljs.test`'s failure printer walks a cycle
  and dies on the vector."
  [query-v]
  (count (inventory/cell-readers (k query-v))))

(defn- sole-reader [query-v]
  (let [rs (inventory/cell-readers (k query-v))]
    (when (= 1 (count rs)) (first rs))))

(defn- teardown-census! [handle]
  (mount/unmount! handle)
  (.then (inventory/quiesced!)
         (fn [_]
           (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                  (inventory/residue))
               "teardown is exact: zero residue after quiescence")
           (mount/release! handle)
           nil)))

(defn- report-failure!
  "Record `label` against THIS row, release its root, and DELIBERATELY DO
  NOT finish it — the chain's single trailing `done` does that. The long
  form is on
  [[re-frame.hicasso.activity-suspense-dom-cljs-test]]'s own
  `report-failure!`."
  [label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | DOM was " (pr-str (when handle (.-textContent ^js (:container handle))))
                   " | ownership " (pr-str (ownership))))
    (when handle (mount/release! handle))
    nil))

;; ---------------------------------------------------------------------------
;; 1. The module gate — the split that needs no Suspense, and the only
;;    retryable path of the two
;; ---------------------------------------------------------------------------

(deftest the-module-gate-dedupes-a-hover-and-a-click-into-one-load
  ;; Pure re-frame; runs on both lanes.
  (seeded!)
  (reset! !module-loads 0)
  (rf/with-frame frame-id
    (testing "the premise: the first intent starts exactly one load and
              parks the module in `:loading`"
      (rf/dispatch-sync [:lzb/wanted])
      (is (= :loading @(rf/subscribe [:lzb/module])))
      (is (= 1 @!module-loads)))

    (testing "and the click that follows the hover is the SAME load. The
              race the gate exists for is `:loading`, not `:loaded` — the
              finished state is the easy half, and a gate written against
              it alone passes every test that never warms a module early"
      (rf/dispatch-sync [:lzb/wanted])
      (rf/dispatch-sync [:lzb/wanted])
      (is (= 1 @!module-loads)
          "a warm-on-hover followed by a click fetched the chunk once"))

    (testing "a failure re-opens the gate, and THIS is where a retry
              belongs: `shadow.lazy/load` is an ordinary function and
              calling it again really does fetch again"
      (rf/dispatch-sync [:lzb/module-failed])
      (is (= :failed @(rf/subscribe [:lzb/module])))
      (rf/dispatch-sync [:lzb/wanted])
      (is (= 2 @!module-loads) "the retry is a second, real load")
      (is (= :loading @(rf/subscribe [:lzb/module]))))

    (testing "and once it is loaded, returning to the route loads nothing"
      (rf/dispatch-sync [:lzb/module-loaded])
      (rf/dispatch-sync [:lzb/wanted])
      (rf/dispatch-sync [:lzb/wanted])
      (is (= 2 @!module-loads))
      (is (= :loaded @(rf/subscribe [:lzb/module])))))
  (exercised! :gate/dedupe-and-retry))

;; ---------------------------------------------------------------------------
;; 2. Load → fallback → arrival, and what crossed
;; ---------------------------------------------------------------------------

(deftest a-lazy-boundary-paints-the-fallback-then-the-component-with-the-props-it-was-written-with
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (tree [suspense-host {:fallback [:p {:id "skel"} "loading"]}
                            [paint-host {:points [1 2 3]}
                             [:em {:id "kid"} "child"]]]))]
        (-> (poll #(= "loading" (painted handle "skel")) "the fallback takes the layout")
            (.then
              (fn [_]
                (testing "the premise: the head suspended and its loader ran
                          — once, on the render that needed it"
                  (is (= 1 @(:calls paint-loader)))
                  (is (nil? (node handle "chart"))))

                (settle! paint-loader :resolve chart-cell)
                (poll #(some? (node handle "chart")) "the chunk lands and the island commits")))
            (.then
              (fn [_]
                (testing "the props the parent wrote BEFORE the component
                          existed are the props the component reads, still
                          a live ClojureScript value rather than something
                          the deferral flattened"
                  (is (= 3 (count (.-points @!arrived-props))))
                  (is (= "3" (text-at handle ".series"))
                      "…and it is the count the island rendered"))

                (testing "the ONE child crossed. hic-032 repaired exactly
                          this shape at the outward door — React's
                          `children` slot is the child itself at one and an
                          array at several — and a deferred crossing is
                          where a bridge that got it right on the first
                          render would still be able to lose it"
                  (is (= "child" (painted handle "kid"))))

                (testing "and arrival did not re-run the loader"
                  (is (= 1 @(:calls paint-loader))))

                (exercised! :lazy/fallback-then-arrival)
                (teardown-census! handle)))
            (.catch (report-failure! "lazy paint witness" handle))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 3. The post-commit suspension, across a LAZY boundary
;; ---------------------------------------------------------------------------

(def ^:private !set-split (atom nil))

(defn- split-host
  "Owns whether the lazy sibling is in the tree. The setter is published
  from a passive effect, so a row cannot drive the tree before React has
  mounted it."
  [_]
  (let [[split? set-split] (react/useState false)]
    (react/useEffect (fn [] (reset! !set-split set-split) js/undefined)
                     #js [set-split])
    ;; The panel is a DIRECT child of the boundary, with no wrapper. React
    ;; hides a suspended primary tree by setting `display: none` on the
    ;; boundary's own host children, and `getComputedStyle` does not
    ;; cascade that to descendants — a `<div>` in between would report the
    ;; panel visible throughout the suspension and the ownership rows
    ;; below would be measuring a premise that never held.
    (codec/root-element
      frame-id
      [suspense-host {:fallback [:p {:id "skel2"} "waiting"]}
       [own-panel {}]
       (when split? [own-host {}])])))

(unchecked-set split-host "displayName" "lzb/split-host")

(defn- act! [f] (react-dom/flushSync f) nil)

(deftest a-lazy-suspension-retains-the-committed-subscription-and-the-arrival-leaves-exact-ownership
  ;; `activity_suspense_dom_cljs_test`'s
  ;; `a-post-commit-suspension-retains-the-subscription-and-the-retry-leaves-exact-ownership`
  ;; established the result against a hand-thrown thenable: hiding a
  ;; committed tree for a Suspense FALLBACK leaves its passive effects
  ;; mounted, so the `useSyncExternalStore` subscription survives and the
  ;; retry's registration is `identical?` to the pre-suspension one —
  ;; which is exactly what `<Activity mode="hidden">` does NOT do.
  ;;
  ;; This row does not re-derive that. It asks the one question the cited
  ;; row cannot answer: whether the same holds when the suspension comes
  ;; from a `React.lazy` PAYLOAD rather than from a component that threw a
  ;; thenable of its own. The two reach React by the same route, so the
  ;; expectation is that it does — and an expectation is not a
  ;; measurement, particularly for the retry, which under a lazy head is
  ;; React re-reading a payload rather than re-running a component.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            _      (reset! !set-split nil)
            handle (mount-concurrent! (mount/fresh-container!)
                                      (mount/provider frame-id
                                                      (react/createElement split-host nil)))
            !state (atom {})]
        (-> (poll #(and (= 1 (reader-count [:lzb/label])) (some? @!set-split))
                  "the panel commits and subscribes")
            (.then
              (fn [_]
                (testing "the premise: the boundary is committed, live and
                          owning its read set BEFORE anything suspends"
                  (is (= "label=alpha" (painted handle "panel")))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership))))

                (swap! !state assoc :first-reg (sole-reader [:lzb/label]))

                ;; The lazy sibling enters an already-committed boundary.
                (act! (fn [] (@!set-split true)))
                (poll #(= "waiting" (painted handle "skel2")) "the fallback takes the layout")))
            (.then
              (fn [_]
                (testing "the premise of the premise: the head really did
                          suspend, and its loader ran"
                  (is (= 1 @(:calls own-loader))))

                (testing "React hid the committed panel rather than
                          unmounting it"
                  (is (some? (node handle "panel")))
                  (is (false? (visible? handle "panel"))))

                (testing "and hiding it behind a LAZY boundary releases
                          nothing, exactly as the hand-thrown thenable did.
                          The count never leaves 1 and the registration is
                          the same object"
                  (is (= 1 (reader-count [:lzb/label])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
                  (is (true? (identical? (:first-reg @!state) (sole-reader [:lzb/label])))
                      "the same registration, not a rebuilt one"))

                ;; A write DURING the suspension. Because the subscription
                ;; survived, the hidden panel hears it.
                (mount/dispatch! handle [:lzb/relabel "beta"])
                (settle! own-loader :resolve chart-cell)
                (poll #(= "label=beta" (painted handle "panel"))
                      "the chunk lands and the boundary is revealed, up to date")))
            (.then
              (fn [_]
                (testing "the arrival leaves EXACT ownership. A lazy retry
                          that had re-subscribed without releasing would
                          show here as two, and would paint correctly
                          throughout"
                  (is (= 1 (reader-count [:lzb/label])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1} (ownership)))
                  (is (true? (identical? (:first-reg @!state) (sole-reader [:lzb/label])))))

                (testing "and the revealed panel needed no correction: it
                          heard the write while it was hidden, which is
                          what an Activity-hidden subtree cannot do"
                  (is (= "label=beta" (painted handle "panel")))
                  (is (some? (node handle "chart"))))

                (testing "still live after the arrival"
                  (mount/dispatch! handle [:lzb/relabel "gamma"])
                  (poll #(= "label=gamma" (painted handle "panel"))
                        "the revealed boundary repaints"))))
            (.then
              (fn [_]
                (is (= 1 (reader-count [:lzb/label])))
                (exercised! :lazy/post-commit-suspension)
                (teardown-census! handle)))
            (.catch (report-failure! "lazy suspension witness" handle))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 4. Rejection, the retry that is not one, and the head that is
;; ---------------------------------------------------------------------------

(defn- reject-tree
  [attempt host]
  (tree [h/error-boundary {:fallback  [:p {:id "fb"} "caught"]
                           :reset-key attempt}
         [:div
          [attempt-marker {}]
          [suspense-host {:fallback [:p {:id "skel3"} "loading"]}
           [host {}]]]]))

(deftest a-rejected-lazy-head-re-throws-its-cached-error-and-only-a-fresh-head-reloads
  ;; The repair rf2-hic-041 was filed for. Both `n/lazy`'s docstring and
  ;; the code-splitting guide said that changing the error boundary's
  ;; `:reset-key` retries the lazy subtree. It clears the boundary — that
  ;; part is true and `kernel_commit_owns_dom_cljs_test` measures it — but
  ;; it cannot retry the CHUNK, because the payload `react/lazy` minted is
  ;; the thing that remembers the rejection and neither this tier nor
  ;; React's public API can reset it.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            _      (reset! !attempts 0)
            handle (mount-concurrent! (mount/fresh-container!) (reject-tree 0 reject-host))]
        (-> (poll #(= "loading" (painted handle "skel3")) "the fallback takes the layout")
            (.then
              (fn [_]
                (testing "the premise: the loader ran once and the region is
                          pending"
                  (is (= 1 @(:calls reject-loader))))
                (settle! reject-loader :reject (js/Error. "chunk 404"))
                (poll #(= "caught" (painted handle "fb")) "the boundary catches the rejection")))
            (.then
              (fn [_]
                (testing "the rejection reached the error region, and the
                          Suspense fallback is gone with the subtree that
                          threw"
                  (is (nil? (node handle "skel3")))
                  (is (nil? (node handle "chart")))
                  (is (= 1 @(:calls reject-loader))))

                ;; The documented retry: change the boundary's :reset-key.
                (reset! !attempts 0)
                (.render ^js (:root handle) (reject-tree 1 reject-host))
                (poll #(pos? @!attempts) "the boundary clears and re-renders its children")))
            (.then
              (fn [_]
                (testing "the premise, and the row is worthless without it:
                          the `:reset-key` change DID clear the caught error
                          and re-render the children — the marker beside the
                          lazy host ran again"
                  (is (pos? @!attempts)))

                (poll #(= "caught" (painted handle "fb"))
                      "and the region lands back in the error state")))
            (.then
              (fn [_]
                (testing "…having reloaded NOTHING. This is the whole row:
                          the paint is identical to a retry that fetched
                          again and failed again, and only the loader count
                          separates them. React 19.2's `lazyInitializer`
                          calls the loader while `_status === -1` and a
                          rejection sets `_status = 2`, after which every
                          read throws the cached error"
                  (is (= 1 @(:calls reject-loader))
                      "the loader was not called a second time"))
                (exercised! :lazy/rejection-is-terminal)

                ;; The repair: a NEW head over the SAME loader. This is
                ;; also what a hot reload allocates, which is why the two
                ;; facts are one measurement.
                (reset! !attempts 0)
                (.render ^js (:root handle) (reject-tree 2 replacement-host))
                (poll #(= 2 @(:calls reject-loader))
                      "the fresh head calls the loader it shares with the rejected one")))
            (.then
              (fn [_]
                (testing "so the terminality belongs to the PAYLOAD and not
                          to the loader — the same function, called again,
                          by a head allocated after the failure"
                  (is (= 2 @(:calls reject-loader))))
                (settle! reject-loader :resolve chart-cell)
                (poll #(some? (node handle "chart")) "and the region recovers")))
            (.then
              (fn [_]
                (testing "the recovered region is the real component, not a
                          fallback that happens to be painting"
                  (is (nil? (node handle "fb")))
                  (is (= 2 @(:calls reject-loader))))
                (exercised! :lazy/fresh-head-reloads)
                (teardown-census! handle)))
            (.catch (report-failure! "lazy rejection witness" handle))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; 5. The server render (no DOM needed; runs under :node-test too)
;; ---------------------------------------------------------------------------

(defn- server-html [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(deftest a-client-only-lazy-region-writes-nothing-and-a-declared-fallback-writes-the-skeleton
  (seeded!)
  (testing "the guide's own declaration — a Suspense host with a `:fallback`
            SLOT and no declared placeholder — writes NOTHING to the
            server response. The slot is React's fallback, and under
            `:client-only` the whole host region is absent, slot included.
            Documentation promising a server skeleton from this shape was
            promising bytes the declaration cannot emit"
    (let [html (server-html [:div
                             [:h1 {:id "title"} "metrics"]
                             [suspense-host {:fallback [:div {:id "react-fallback"} "loading"]}
                              [ssr-host {:points [1 2 3]}]]])]
      (is (re-find #"metrics" html)
          (str "the page rendered — the sibling node is there: " html))
      (is (not (re-find #"react-fallback" html))
          (str "and the host region wrote nothing at all: " html))
      (is (not (re-find #"id=\"chart\"" html))
          (str "the island certainly did not render: " html))
      (is (zero? @(:calls ssr-loader))
          "and the loader was never called: the server never sends the chunk,
           which is why `n/lazy` pins the region to Client-only whatever the
           component inside it declared")))

  (testing "the repair is `defhost`'s own `:fallback` — inert markup on the
            declaration, which the Client-only gate renders on the server
            AND on hydration's first client pass, so the skeleton has a
            footprint and the live region replaces it after adoption"
    (let [html (server-html [:div
                             [:h1 {:id "title"} "metrics"]
                             [suspense-skeleton-host {:fallback [:div {:id "react-fallback"} "loading"]}
                              [ssr-host {:points [1 2 3]}]]])]
      (is (re-find #"server-skeleton" html)
          (str "the declared placeholder is in the server HTML: " html))
      (is (re-find #"aria-busy" html)
          (str "with its attributes: " html))
      (is (not (re-find #"id=\"chart\"" html))
          (str "and still not the island: " html))
      (is (zero? @(:calls ssr-loader))
          "nor was the chunk asked for")))
  (exercised! :lazy/server-render))

;; ---------------------------------------------------------------------------
;; 7. The UNSHADOWED server render — `n/lazy`'s own policy, alone
;; ---------------------------------------------------------------------------

(deftest a-bare-lazy-head-under-native-suspense-writes-nothing-and-never-calls-its-loader
  (testing "THE CONTROL, first, because the row above it is a zero and a
            zero is what an inert renderer also produces. A raw
            `react/lazy` head — no gate, no `defhost`, the identical
            shape — has its loader called ONCE while `renderToString`
            runs, and React abandons the boundary to the client. That is
            what `n/lazy` looked like before this bead: React 19's
            `lazyInitializer` runs during a server render like any other
            render, so a chunk the server can do nothing with is fetched
            anyway and the response carries a switched-to-client template
            instead of the region"
    (let [html (react-dom-server/renderToString (suspense-tree raw-head))]
      (is (= 1 @(:calls raw-loader))
          (str "an ungated lazy head IS reached by the server renderer: " html))
      (is (re-find #"react-fallback" html)
          (str "and React wrote the Suspense fallback into the response: " html))
      (is (re-find #"Switched to client rendering" html)
          (str "having abandoned the boundary: " html))))

  (testing "and `n/lazy`'s head, in that same shape, with NOTHING in front
            of it — no `defhost`, no provider, no root element, one
            Suspense host and the head. The loader is never called: the
            server does not reach for a chunk it cannot use, and that is
            the whole of the `:server client-only` the marker advertises.
            Narrowing caught: the policy living on the marker while the
            head stayed an ungated `react/lazy` record (audit #7969) —
            metadata that no renderer reads"
    (let [html (react-dom-server/renderToString (suspense-tree bare-head))]
      (is (re-find #"metrics" html)
          (str "the premise: the render happened and reached the region's
                sibling, so a zero below is a gate and not an inert
                renderer — " html))
      (is (zero? @(:calls bare-loader))
          (str "THE ROW: the loader was not called — " html))
      (is (not (re-find #"react-fallback" html))
          (str "the region is absent entirely, React's fallback slot with
                it: a gate that renders nil never reaches the prop — " html))
      (is (not (re-find #"Switched to client rendering" html))
          (str "and nothing was abandoned to the client, because nothing
                suspended — " html))))
  (exercised! :lazy/unshadowed-server-gate))

;; ---------------------------------------------------------------------------
;; The roster, asserted rather than described
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  (if-not (mount/browser?)
    (is (set/subset? #{:gate/dedupe-and-retry :lazy/server-render :lazy/unshadowed-server-gate} @!exercised)
        (str "the two lane-independent rows must run everywhere; missing "
             (pr-str (set/difference #{:gate/dedupe-and-retry :lazy/server-render :lazy/unshadowed-server-gate} @!exercised))))
    (is (= declared-population @!exercised)
        (str "every declared mechanism must actually have been reached; missing "
             (pr-str (set/difference declared-population @!exercised))))))
