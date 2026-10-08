(ns day8.re-frame2-xray.views.edn-inspector-fresco-boundary-dom-cljs-test
  "THE SHARED VALUE WIDGET, AS A FRESCO BOUNDARY, read off a real React
  commit.

  `edn-inspector-view` is the head a Fresco panel writes: a real React
  function component whose app-db reads go through Fresco's own collector.

  ## The mount is a Fresco panel's own shape, not a contrivance

  A Fresco panel is a boundary that the `reg-view` shell mounts through
  `as-component` with an EMPTY props map, and the widget sits INSIDE that
  panel's body as an ordinary Fresco head. [[HostPanel]] is exactly that, in
  miniature. The inspected VALUE cannot cross a Reagent `[:>]` crossing —
  `convert-prop-value` turns a CLJS map into a camelCased JS object — so it
  lives on the Fresco side and reaches the widget as an ordinary argument.

  ## Which claim each row answers

    W1  first display, and the read lands in the frame the tree NAMED
    W5  TWO panels mounted at once under one logical `:mount-id` each hold
        their own ResizeObserver and width, and one's unmount releases its
        per-mount store entry and leaves the other whole
    W6  a leaf TYPE CHANGE renders through the codec — the R7 branch is
        on this boundary's render path and Fresco grades every head it
        emits

  A Fresco boundary is NOT in Reagent's render queue, so the adapter's
  `:flush-render!` slot commits nothing of its update: mount is committed
  with React's own `flushSync` and everything after it settles first."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
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
  the negative half of the frame-targeting claim and W5 mounts the second
  panel under it."
  ::app)

(def ^:private panel-id
  "The consuming panel's id, which is the expansion key's first component."
  ::panel)

(def ^:private mount-id
  "The boundary's REQUIRED `:mount-id` — the width-slot key, the container
  testid and half the expansion key. It is NOT the store key: that is the
  LIFECYCLE KEY, this name qualified by the frame the mount renders under,
  because this name is logical and W5 mounts two panels that both present it."
  "edn-inspector-boundary-test")

(def ^:private expansion-q
  "The widget's expansion read. The sub-cache is keyed by the query vector
  itself, so this value IS the cache key W1's ref-count reads."
  [ei/expansion-slot])

(def ^:private subject-value
  "A nested map too wide to inline at any column, so the widget renders the
  same shape at every width it measures for itself."
  {:a (into {} (for [i (range 12)]
                 [(keyword (str "key-" i))
                  (str "a deliberately long value string number " i)]))})

(def ^:private inspector-opts
  {:panel-id panel-id})

;; ---- the host panel: a Fresco panel's shape -------------------------------

(rf.fresco/defview HostPanel
  "A Fresco panel, in miniature. Holds the value on the Fresco side and
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

;; ---- a probe W1's frame-targeting control needs ---------------------------

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W5 is an `async` row, and `cljs.test`
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
  no `js/ResizeObserver` either, which W5 depends on."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask — so the
  ResizeObserver has measured and its width has reached the slot."
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
  "Mount a host panel the way the shell mounts a Fresco panel: the
  `as-component` bridge as a Reagent hiccup head inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and the first assertion would read an empty container."
  ([frame] (mount-host! frame HostPanel-component))
  ([frame component]
   (let [container (.createElement js/document "div")
         root      (rdc/create-root container)]
     (.appendChild (.-body js/document) container)
     (react-dom/flushSync
       (fn []
         (rdc/render root [rf/frame-provider {:frame frame}
                           [:> component {}]])))
     {:container container :root root})))

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

(defn- cache-of [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- held-in
  "What the per-mount store holds for this widget's mount UNDER `frame-id` —
  keyed by the LIFECYCLE KEY, because the dispatcher it holds is bound to
  one frame and the width it writes lands in that frame's app-db."
  [frame-id]
  (ei/mount-state-held (ei/lifecycle-key frame-id mount-id)))

(defn- width-in
  "The width this widget measured for itself in `frame-id`'s app-db, or nil.
  Reads the LOGICAL id, which keys the slot, so it witnesses the
  measurement rather than how the store is keyed."
  [frame-id]
  (get-in (rf/app-db-value frame-id) [ei/widths-slot mount-id]))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-widget-paints-and-its-read-lands-in-the-named-frame
  ;; The boundary's `rf.fresco/sub` on the expansion slot resolves against the
  ;; frame the enclosing `frame-provider` named, not the ambient one. A live
  ;; read in the application frame shows this instrument can see an entry
  ;; there, so the widget's zero in that frame is an absence.
  (if-not (browser?)
    (is true ":node — the :browser-test runner drives the real React mount")
    (let [_ (setup!)
          _probe (rf/subscribe [::n] {:frame app-frame})
          {:keys [container root]} (mount-host! :rf/xray)]
      (try
        (is (some? (container-node container))
            "the widget committed its own container through the as-component bridge")
        (is (= [true true true]
               [(pos? (ref-count-of :rf/xray expansion-q))
                (zero? (ref-count-of app-frame expansion-q))
                (pos? (ref-count-of app-frame [::n]))])
            (str "the expansion read is held in :rf/xray's sub-cache and not in "
                 "the application frame's, which this instrument can read. "
                 ":rf/xray cache keys: " (pr-str (keys (cache-of :rf/xray)))))
        (finally
          (rf/unsubscribe [::n] {:frame app-frame})
          (teardown! root container))))))

;; ===========================================================================
;; W5 — TWO PANELS AT ONCE, one logical mount-id between them
;; ===========================================================================
;;
;; A `:mount-id` is a LOGICAL surface name and deliberately stable, so the
;; panel gallery's variants, each in its own `frame-provider`, all present
;; the same one. Per-mount state keyed on that name alone would give the two
;; panels one entry: the second would never measure, and its unmount would
;; take the first one's observer with it — while both still PAINT. The two
;; columns are different widths so "independent" is distinguishable from two
;; panels agreeing by accident.

(def ^:private slot-a-width-px 640)

(def ^:private slot-b-width-px 320)

(defn- two-panel-tree
  "Two host panels at two widths under two `frame-provider`s, or — when
  `both?` is false — the first alone. Re-rendering the SAME root with the
  second gone is what unmounting one panel of a live pair actually is."
  [both?]
  [:div {:data-testid "rf-xray-two-panel-host"}
   [:div {:data-testid "rf-xray-slot-a"
          :style       {:width (str slot-a-width-px "px")}}
    [rf/frame-provider {:frame :rf/xray}
     [:> HostPanel-component {}]]]
   (when both?
     [:div {:data-testid "rf-xray-slot-b"
            :style       {:width (str slot-b-width-px "px")}}
      [rf/frame-provider {:frame app-frame}
       [:> HostPanel-component {}]]])])

(defn- mount-two!
  "Commit both panels in ONE synchronous render, the way a gallery commits
  its variants."
  []
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync (fn [] (rdc/render root (two-panel-tree true))))
    {:container container :root root}))

(deftest w5-two-simultaneous-panels-are-independent
  (if-not (browser?)
    (is true ":node — the :browser-test runner drives the real React mount")
    (async done
      (setup!)
      (let [{:keys [container root]} (mount-two!)]
        (-> (settle)
            (.then
              (fn [_]
                (is (= [true true]
                       [(contains? (held-in :rf/xray) :observer)
                        (contains? (held-in app-frame) :observer)])
                    (str "each panel holds its own ResizeObserver, in its own "
                         "frame's entry. Held: "
                         (pr-str [(held-in :rf/xray) (held-in app-frame)])))
                (is (= [slot-a-width-px slot-b-width-px]
                       [(width-in :rf/xray) (width-in app-frame)])
                    "each panel measured its own column into its own frame's width slot")

                ;; ---- unmount the second; the first must be untouched ---
                (react-dom/flushSync
                  (fn [] (rdc/render root (two-panel-tree false))))
                (settle)))
            (.then
              (fn [_]
                (is (= [nil true slot-a-width-px]
                       [(held-in app-frame)
                        (contains? (held-in :rf/xray) :observer)
                        (width-in :rf/xray)])
                    (str "the unmounted panel released its whole entry, and the "
                         "survivor still holds its observer and its width. Held: "
                         (pr-str [(held-in app-frame) (held-in :rf/xray)])))))
            (.catch (fn [e]
                      (is false (str "W5 never settled: " (.-message e)))
                      nil))
            (.then (fn [_]
                     (teardown! root container)
                     (done))))))))

;; ===========================================================================
;; W6 — A LEAF TYPE CHANGE, RENDERED THROUGH THE CODEC
;; ===========================================================================
;;
;; The R7 type-change suffix renders the PRIOR side by CALLING the widget's
;; `mini` renderer, a plain function. Under Fresco a plain function in hiccup
;; head position is a loud error by design (HD-016,
;; `:rf.error/fresco-bad-head`), and that branch sits on
;; `ei/edn-inspector-view`'s render path with no error boundary above it, so
;; a `mini` head would throw out of the boundary and React would unmount the
;; entire Xray root — a panel that never appears rather than an error. No
;; other row renders the widget in diff mode through the codec.
;;
;; `engine/type-change?` is set for a CONTAINER KIND FLIP only, so a nil →
;; 20 KiB string reaches the branch through the size walk: the string is
;; elided into the `{:rf.size/large-elided …}` sentinel, which the diff
;; engine reads as a container (nil → container is R7) while the inspector
;; renders it as a `:sentinel-large` LEAF. This row reproduces that PAIR.

(def ^:private type-change-mount-id
  "W6's own mount, so its width slot, popup id and container testid cannot
  collide with the browse-mode mount the other rows share."
  "edn-inspector-boundary-type-change")

(def ^:private type-change-before
  "The prior side: the slot exists and holds `nil` — present-and-nil, NOT
  absent, which `engine/value-at` would classify `:added`."
  {:a nil})

(def ^:private type-change-after
  "The after side: the size-elision sentinel the framework substitutes for an
  over-budget value, shaped as `ei/large-sentinel?` recognises it."
  {:a {:rf.size/large-elided
       {:path   [:a]
        :bytes  20480
        :type   "string"
        :reason :rf.size/over-budget}}})

(rf.fresco/defview TypeChangeHostPanel
  "The same Fresco-panel shape as [[HostPanel]], in diff mode."
  [_props]
  [:div {:data-testid "rf-xray-type-change-host-panel"}
   [ei/edn-inspector-view
    {:mount-id type-change-mount-id
     :value    type-change-after
     :opts     (assoc inspector-opts :before type-change-before)}]])

(def ^:private TypeChangeHostPanel-component
  "Declared once at top level, as `as-component`'s contract requires."
  (rf.fresco/as-component TypeChangeHostPanel))

(defn- mini-in
  "The `mini` render inside `node`, or nil — scoped to the annotation, because
  the sentinel chip beside it is its own render."
  [node]
  (some-> node (.querySelector "[data-testid=\"rf-xray-edn-inspector-mini\"]")))

(deftest w6-a-leaf-type-change-renders-through-the-fresco-codec
  (if-not (browser?)
    (is true ":node — the :browser-test runner drives the real React mount")
    (let [_ (setup!)
          {:keys [container root]} (mount-host! :rf/xray
                                                TypeChangeHostPanel-component)]
      (try
        (is (= "nil"
               (some-> (mini-in (q container "[data-rf-diff-annotation=\"type-change\"]"))
                       (.getAttribute "title")))
            (str "the R7 type-change suffix rendered the PRIOR side as a CALLED "
                 "`mini` child. Under a `mini` head the whole root unmounts and "
                 "this is nil. Diff ops present: "
                 (pr-str (mapv #(.getAttribute % "data-rf-diff-op")
                               (array-seq
                                 (.querySelectorAll
                                   container "[data-rf-diff-op]"))))))
        (finally
          (teardown! root container))))))
