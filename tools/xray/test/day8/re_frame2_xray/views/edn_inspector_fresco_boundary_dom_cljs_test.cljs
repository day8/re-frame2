(ns day8.re-frame2-xray.views.edn-inspector-fresco-boundary-dom-cljs-test
  "THE SHARED VALUE WIDGET, AS A FRESCO BOUNDARY, read off a real React
  commit (rf2-k97c.3).

  `edn-inspector-view` is the head a migrated panel writes. It is a real
  React function component whose three app-db reads go through Fresco's
  own shipped collector, rather than an `rf/reg-view` whose reads are
  tracked by whichever reaction machinery the installed substrate adapter
  supplies. This file is the behavioural evidence for that head.

  ## The mount is the SHAPE STEP 2 WILL USE, not a contrivance

  A migrated panel is a Fresco boundary that the still-`reg-view` shell
  mounts through `as-component` with an EMPTY props map, and the widget
  sits INSIDE that panel's body as an ordinary Fresco head. [[HostPanel]]
  is exactly that, in miniature: the value lives on the Fresco side and
  reaches the widget as an ordinary Clojure argument.

  That shape is load-bearing rather than incidental. Fresco's
  `as-component` contract says the outward decode is shallow and that
  \"names round-trip across a crossing; values do not\" — a Reagent
  parent's `[:>]` runs `convert-prop-value` first, which turns a CLJS map
  into a camelCased JS object. So the inspected VALUE cannot cross a
  Reagent boundary crossing, and a test that handed it across one would be
  testing a shape no call site can use. What crosses here is an empty
  props map, which is what `module_view`'s bridge crosses too.

  ## Which claim each row answers

    W1  first display, and the read lands in the frame the tree NAMED
    W2  it updates on a real dependency change — with a deaf control
    W3  the boundary's render is not application view evidence
    W4  teardown releases BOTH the subscription reference AND the
        per-mount store entry (the ResizeObserver, the width debounce and
        the projection cache)

  W4 is the row this widget needed and `module_view` did not. That panel
  holds no per-mount mutable state; this one holds three pieces of it, and
  they used to live in a form-2 closure that was collected with the mount.
  A Fresco boundary has no such closure, so they moved to a module-level
  store with an EXPLICIT release — which is precisely the change that
  passes its happy path and leaks on unmount.

  ## Instrument notes inherited from the first migrated panel

  Two facts each made a live panel read as dead there, and both apply
  here. A Fresco boundary is NOT in Reagent's render queue, so the
  adapter's `:flush-render!` slot commits nothing of its update — mount is
  committed with React's own `flushSync` and everything after it is a
  bounded poll. And the collector releases a cell ONE MACROTASK after its
  last reader unmounts (`impl/collector`'s `cell-reapers`, so that a keyed
  reorder within one turn reuses the reaction), so W4 polls rather than
  reading once."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. W1 reads it as
  the negative half of the frame-targeting claim and W3 mounts inside it,
  so the evidence claim cannot be carried by `:rf/xray`'s trace gate."
  ::app)

(def ^:private panel-id
  "The consuming panel's id, which is the expansion key's first component."
  ::panel)

(def ^:private mount-id
  "The boundary's REQUIRED `:mount-id`. A Fresco boundary has no form-2
  outer body to mint one in, so the caller names it — see
  `ei/edn-inspector-view`. Named once here because four rows key off it:
  it is the store key, the width-slot key and half the expansion key."
  "edn-inspector-boundary-test")

(def ^:private expansion-q
  "The widget's expansion read. The sub-cache is keyed by the query vector
  itself, so this value IS the cache key the ref-count rows read."
  [ei/expansion-slot])

(def ^:private subject-value
  "One nested container, because the row W2 drives is a TOGGLE: `:a` has to
  be a container for it to have an expanded state at all."
  {:a {:b 1 :c 2}})

(def ^:private inspector-opts
  {:panel-id panel-id
   ;; Deep enough that `:a` renders EXPANDED at first paint, so W2's
   ;; toggle has somewhere to move to.
   :default-expanded-depth 8})

;; ---- the host panel: the shape step 2 will use ----------------------------

(rf.fresco/defview HostPanel
  "A migrated panel, in miniature. Holds the value on the Fresco side and
  renders the widget as an ordinary Fresco head — no props crossing, so
  the value arrives as the Clojure value it is."
  [_props]
  [:div {:data-testid "rf-xray-host-panel"}
   [ei/edn-inspector-view
    {:mount-id mount-id
     :value    subject-value
     :opts     inspector-opts}]])

(def ^:private HostPanel-component
  "Declared once at top level, as `as-component`'s contract requires:
  deriving one per render mints a fresh element type and remounts the
  subtree."
  (rf.fresco/as-component HostPanel))

;; ---- a probe the deaf control and the frame-targeting control need --------

(rf/reg-event ::bump
  (fn [{:keys [db]} [_ n]] {:db (assoc db ::n n)}))

(rf/reg-sub ::n (fn [db _] (::n db)))

(rf/reg-view ProbeRegView
  "The POSITIVE CONTROL for W3, and nothing else. An ordinary `reg-view`
  rendered by the installed adapter in the same root, the same frame and
  the same commit as the subject — so the only variable between them is
  which view layer authored the body."
  []
  [:div {:data-testid "rf-xray-probe-reg-view"} (str @(rf/subscribe [::n]))])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W2 and W4 are `async` rows, and `cljs.test`
     ;; refuses a FUNCTION fixture in any namespace carrying one.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into — and
  no `js/ResizeObserver` either, which W4's precondition depends on."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for W2's CONTROL: an absence asserted immediately after an event is a
  race; asserted after this, it is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- mount-host!
  "Mount the host panel the way the shell mounts a migrated one: the
  `as-component` bridge as a Reagent hiccup head inside a
  `frame-provider` scoping `frame`. Committed synchronously — React 19's
  `root.render` is otherwise async and the first assertion would read an
  empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [:> HostPanel-component {}]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads and where React detaches the
  container `:ref` — have RUN by the time the next line reads. A bare
  `.unmount` merely schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- container-node
  "The widget's own root element, addressed by the testid the renderer
  composes from panel-id and mount-id."
  [container]
  (q container (str "[data-testid=\"rf-xray-edn-inspector-"
                    (name panel-id) "-" mount-id "\"]")))

(defn- a-expanded
  "The `data-rf-expanded` flag on the `[:a]` container node — \"1\", \"0\",
  or nil when the node is not on screen."
  [container]
  (some-> (q container (str "[data-testid=\"rf-xray-edn-inspector-"
                            (name panel-id) "-" mount-id "-:a\"]"))
          (.getAttribute "data-rf-expanded")))

(defn- cache-of [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-widget-paints-and-its-read-lands-in-the-named-frame
  (testing "rf2-k97c.3 — the boundary commits real DOM, and its
            `rf.fresco/sub` on the expansion slot resolves against the frame
            the enclosing `frame-provider` named rather than the ambient
            one. Epic criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim is measured with an instrument demonstrably
            ;; able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-host! :rf/xray)]
        (try
          (is (some? (q container "[data-testid=\"rf-xray-host-panel\"]"))
              "the host panel committed real DOM through the as-component bridge")
          (is (some? (container-node container))
              "and the widget rendered its own container, so the boundary's
               body ran rather than short-circuiting")
          (is (= "1" (a-expanded container))
              "the nested container painted EXPANDED, which is what W2's
               toggle has to move")

          ;; ---- criterion 4: the read is where the tree said it would be ---
          (is (pos? (ref-count-of :rf/xray expansion-q))
              (str "the widget's expansion read holds a reference in "
                   ":rf/xray's sub-cache — the frame the frame-provider "
                   "named. Cache keys: " (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame expansion-q))
              "and NOT in the application frame's — a boundary that took the
               ambient scope instead of reading React context would put it here")
          (is (pos? (ref-count-of app-frame [::n]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zero
               above is an absence rather than a broken reader")
          (finally
            (rf/unsubscribe [::n] {:frame app-frame})
            (teardown! root container)))))))

;; ===========================================================================
;; W2 — it updates on a real dependency change, and the control is deaf
;; ===========================================================================

(deftest w2-widget-updates-on-a-real-dependency-change
  (testing "rf2-k97c.3 — a toggle written into the expansion slot reaches the
            committed DOM, and a write the widget does NOT read does not.
            Epic criterion 2, with the control that makes the update mean
            liveness rather than a commit that had simply not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-host! :rf/xray)]
          (is (= "1" (a-expanded container))
              "PRECONDITION: the node starts expanded, so the toggle below has
               somewhere to move to")

          ;; ---- the deaf control: a write the widget does not read ---------
          ;; Same frame, same app-db, a key the expansion sub does not
          ;; project. The sub recomputes to an unchanged value, so nothing
          ;; the boundary reads was invalidated and it must not re-render.
          (rf/dispatch-sync [::bump 1] {:frame :rf/xray})
          (-> (settle)
              (.then
                (fn [_]
                  (is (= "1" (a-expanded container))
                      "CONTROL: given a full settling window, an app-db write
                       the widget does not read leaves the committed DOM
                       exactly where it was. A widget that re-rendered here
                       would make the phase below pass for a reason that is
                       not liveness")

                  ;; ---- the real change ---------------------------------
                  (rf/dispatch-sync
                    [:rf.xray.edn-inspector/toggle-node panel-id mount-id [:a] true]
                    {:frame :rf/xray})
                  (rf.test-support/poll-until
                    #(= "0" (a-expanded container))
                    {:label "the toggle reached the committed DOM"})))
              (.then
                (fn [_]
                  (is (= "0" (a-expanded container))
                      "the boundary re-rendered on a real change to the slot it
                       reads through Fresco's collector — NOT through the
                       installed adapter's reaction machinery")
                  (is (some? (container-node container))
                      "and the widget is still the SAME mount rather than a
                       remount, which would not be liveness")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — the tool's own render is not application view evidence
;; ===========================================================================

(deftest w3-the-boundarys-render-emits-no-view-trace
  (testing "rf2-k97c.3 / rf2-tqlmq — rendering the widget contributes NOTHING
            to the substrate's view-trace stream, even mounted INSIDE an
            application frame. Epic criterion 5, structural rather than the
            `:rf/xray` frame gate: a Fresco boundary is not a substrate view
            render, so there is no event to gate. The control is an ordinary
            `reg-view` in the same root, frame and commit."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_        (setup!)
            traces   (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          (let [{:keys [container root]} (mount-host! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              (is (some? (container-node container))
                  "precondition: the widget really did render in this commit —
                   an empty container would make the zero below vacuous")
              (is (zero? (count subject-views))
                  (str "the widget's render put NO :rf.view/* op in the trace "
                       "stream while rendering inside an application frame. "
                       "Ops seen: " (pr-str (mapv :operation subject-views))))
              (finally (teardown! root container))))

          (reset! traces [])
          (let [container (.createElement js/document "div")
                root      (rdc/create-root container)]
            (.appendChild (.-body js/document) container)
            (try
              (react-dom/flushSync
                (fn []
                  (rdc/render root [rf/frame-provider {:frame app-frame}
                                    [ProbeRegView]])))
              (let [control-views (filterv view-op? @traces)]
                (is (some? (q container "[data-testid=\"rf-xray-probe-reg-view\"]"))
                    "precondition: the control really did render")
                (is (pos? (count control-views))
                    (str "CONTROL FIRES: an ordinary reg-view rendered the same "
                         "way DOES emit a :rf.view/* op, so the subject's zero "
                         "is a property of the boundary and not of a dead "
                         "instrument. Ops seen: "
                         (pr-str (mapv :operation control-views)))))
              (finally (teardown! root container))))
          (finally
            (rf/unregister-listener! :trace ::collect)))))))

;; ===========================================================================
;; W4 — teardown releases the read AND the per-mount store
;; ===========================================================================

(defn- released? []
  (zero? (ref-count-of :rf/xray expansion-q)))

(deftest w4-unmount-releases-the-read-and-the-per-mount-store
  (testing "rf2-k97c.3 — unmounting releases the subscription reference
            completely AND drops everything the per-mount store held for this
            mount; remounting returns to the same reference count rather than
            a higher one. Epic criterion 6.

            THE STORE HALF IS THE ADVERSARIAL ONE, and it is why this row is
            longer than the panel template's. The widget's ResizeObserver, its
            width debounce and its Editscript projection cache used to live in
            a form-2 closure that was collected with the mount. A Fresco
            boundary has no such closure, so they live in a module-level map
            with an explicit release keyed on React calling the container
            `:ref` with nil. A port that got the rendering right and the
            release wrong would pass every other row in this file: the DOM
            would be correct, the subscription would be released by the
            collector, and the observer would go on observing a detached node
            for the life of the page.

            THE RELEASE OF THE READ IS ASYNCHRONOUS BY DESIGN, so that half
            polls: `impl/collector`'s `cell-reapers` gives a cell whose last
            reader unmounts one macrotask of grace. The STORE half is
            synchronous — React detaches refs inside the `flushSync` above —
            so it is asserted directly."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (-> (rf.test-support/poll-until released?
              {:label "no reference held before the first mount"})
            (.then
              (fn [_]
                (is (nil? (ei/mount-state-held mount-id))
                    "PRECONDITION: the store holds nothing for this mount id
                     before it mounts")
                (let [{:keys [container root]} (mount-host! :rf/xray)
                      mounted (ref-count-of :rf/xray expansion-q)
                      held    (ei/mount-state-held mount-id)]
                  (is (pos? mounted)
                      "the mount took a subscription reference — otherwise the
                       release below is vacuous")
                  (is (contains? held :ref)
                      "the store holds the mount's memoised container ref")
                  (is (contains? held :observer)
                      (str "and its ResizeObserver, which is the piece with a "
                           "real resource behind it. This assertion is the "
                           "reason W4 lives in the BROWSER lane: js/ResizeObserver "
                           "does not exist under Node, so the observer branch "
                           "is never taken there and a release row written for "
                           "the node lane would be releasing nothing. Held: "
                           (pr-str held)))
                  (teardown! root container)

                  ;; ---- the store half: synchronous, asserted directly -----
                  (is (nil? (ei/mount-state-held mount-id))
                      (str "unmount released the WHOLE entry — observer, width "
                           "debounce and projection cache together — rather "
                           "than emptying part of it. Held after unmount: "
                           (pr-str (ei/mount-state-held mount-id))))

                  ;; ---- the read half: polled, per the collector's grace ---
                  (-> (rf.test-support/poll-until released?
                        {:label "the first unmount released the read"})
                      (.then
                        (fn [_]
                          (is (released?)
                              (str "the unmount released the read COMPLETELY, "
                                   "within the collector's grace macrotask. "
                                   "Cache: " (pr-str (keys (cache-of :rf/xray)))))
                          (let [{c2 :container r2 :root} (mount-host! :rf/xray)
                                remounted (ref-count-of :rf/xray expansion-q)]
                            (is (= mounted remounted)
                                (str "reopening returns to the SAME reference "
                                     "count (" mounted ") rather than "
                                     "accumulating — accumulation across "
                                     "open/close cycles is the signature of a "
                                     "release the substrate's own reaction "
                                     "lifecycle cannot see. Got: " remounted))
                            (is (contains? (ei/mount-state-held mount-id) :ref)
                                "and the remount minted a FRESH store entry,
                                 proving the first one really went rather than
                                 being reused")
                            (teardown! r2 c2)
                            (rf.test-support/poll-until released?
                              {:label "the second unmount released it too"}))))))))
            (.then (fn [_]
                     (is (released?)
                         "and the second unmount releases the read too")
                     (is (nil? (ei/mount-state-held mount-id))
                         "and the store too")))
            (.catch (fn [e] (is false (str "poll timed out: " (.-message e))) nil))
            (.then (fn [_] (done))))))))
