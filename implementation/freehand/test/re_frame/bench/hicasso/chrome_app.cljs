(ns re-frame.bench.hicasso.chrome-app
  "THE PAGE-CHROME ROW, AND WHAT THE BAIL-OUT COSTS — the page half
  (rf2-2rtt6.52's landing bar).

  HD-028 makes a value-equality bail-out the boundary default. The ruling
  made it conditional on a measurement rather than on the repair being
  obviously right: the default lands only if it removes the 300-row
  cascade **without a material mount/bulk regression and without pushing
  retained heap meaningfully farther past the bar** — because the
  comparator takes React's full `MemoComponent` path and adds an outer
  Fiber per boundary, and the R=0 shell already reads ~1.14 KB against a
  1 KB paper-fail line. If the Fiber fails the gate, the same comparator
  ships as an opt-in and HD-006 stands.

  ## Two arms, one page, one difference

  Both arms render the **same** page — the shape roster's own feed, whose
  witness found the defect — over the same model, the same card markup and
  the same bodies. The only difference is how the head was minted:

      :memo    `runtime/mint-view!`  — marked AND given the codec's
                                       stable memo wrapper (HD-028)
      :plain   [[plain-view]]        — marked only, which is `mint-view!`
                                       exactly as it stood before the
                                       repair

  So `:plain` is not a reconstruction of the old runtime from memory; it
  is the one line that changed, restored. Everything downstream —
  `runtime/shell`, both hooks, the index, the codec's element emission —
  is shared, which is what makes the difference between the arms
  attributable to the wrapper and to nothing else.

  ## The five operations, and which claim each one carries

  | op | write | the claim |
  |---|---|---|
  | `:mount` | — | the cold mount of 300 boundaries; the regression risk the extra Fiber creates |
  | `:chrome` | `:conduit/show-your-feed` | **the win.** The page reads the tab; no card's props and no card's reads move |
  | `:bulk` | `:conduit/refresh-feed` | every card's own subscription moves — external-store invalidation still fires, and the bulk row is where a comparator that never bails still costs |
  | `:narrow` | `:conduit/favorite` | one card's subscription moves; the other 299 must stay asleep |
  | `:props` | `:conduit/go-to-page` | the page forwards `:rev` into every card, so every card's PROPS move — a bail-out that never re-renders is worse than one that always does |

  `:bulk`, `:narrow` and `:props` must read the SAME body counts on both
  arms. Only `:chrome` may differ, and the whole bar is that it does.

  ## The window is frame-inclusive, and the arms defer identically

  `clock_run.cjs` refuses in-page spans because they end when the
  JavaScript returns, before style, layout and paint — and how much work a
  substrate leaves for the browser is exactly what differs between
  substrates. That objection does not reach a **self**-comparison: both
  arms here are the same substrate with one wrapper between them, so the
  error is common-mode. It is avoided anyway — every window closes after
  [[settle-frame]], which resolves in the first task after the frame that
  followed the mutation, so the reading includes the paint either way.

  Runs under `:advanced`, which is the production build the bar names."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.card :as card]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.core :as rf]
            ["react-dom" :as react-dom]))

(def ^:private cards-n
  "The charter's bulk rung, and the roster's: ~300 boundaries on one
  commit."
  300)

(def ^:private tags-n 10)

;; ---------------------------------------------------------------------------
;; The two mints — the one line the arms differ by
;; ---------------------------------------------------------------------------

(defn- plain-view
  "`mint-view!` as it stood before rf2-2rtt6.52: a marked function
  component and no wrapper. Reproduced here rather than described, so the
  `:plain` arm is the old runtime and not an impression of it."
  [view-name body-fn]
  (let [component (fn hicasso-boundary [js-props] (rt/shell body-fn js-props))]
    (unchecked-set component "displayName" view-name)
    (codec/mark-boundary! component)))

;; ---------------------------------------------------------------------------
;; Bodies — shared by both arms, so only the mint differs
;; ---------------------------------------------------------------------------

(def ^:private !cards (atom 0))
(def ^:private !pages (atom 0))

(defn- reset-runs! [] (reset! !cards 0) (reset! !pages 0) nil)
(defn- runs [] {:cards @!cards :pages @!pages})

(defn- card-body
  "One card. The page also passes `:rev`, which this body never reads: it
  rides in the props map so the `:props` op can move every card's props
  without moving any card's subscription, which is the only way to ask the
  comparator the question that op asks."
  [{:keys [slug]}]
  (swap! !cards inc)
  (card/card slug))

(defn- page-body
  "The feed page. Reads the tab (the chrome the `:chrome` op writes), the
  slug order, the tags, and the page number it forwards into every card."
  [card-head]
  (fn page-body* [_]
    (swap! !pages inc)
    (let [your-feed? (rt/sub [:conduit/your-feed?])
          rev        (rt/sub [:conduit/page])]
      [:div.home-page
       [:div.banner [:div.container [:h1.logo-font "conduit"]]]
       [:div.container.page
        [:div.row
         [:div.col-md-9
          [:div.feed-toggle
           [:ul.nav.nav-pills.outline-active
            [:li.nav-item
             [:a.nav-link {:data-testid "your-feed-tab"
                           :class       (when your-feed? "active")} "Your Feed"]]
            [:li.nav-item
             [:a.nav-link {:data-testid "global-feed-tab"
                           :class       (when-not your-feed? "active")} "Global Feed"]]]]
          [:div.article-list {:data-testid "article-list"}
           (into [:<>]
                 (for [slug (rt/sub [:conduit/slugs])]
                   [card-head {:key slug :slug slug :rev rev}]))]]
         [:div.col-md-3
          [:div.sidebar
           [:div.tag-list
            (into [:<>]
                  (for [tag (rt/sub [:conduit/tags])]
                    [:a.tag-pill {:key tag} tag]))]]]]]])))

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(def ^:private memo-card (rt/mint-view! "chrome/memo-card" card-body))
(def ^:private plain-card (plain-view "chrome/plain-card" card-body))

(def ^:private memo-page (rt/mint-view! "chrome/memo-page" (page-body memo-card)))
(def ^:private plain-page (plain-view "chrome/plain-page" (page-body plain-card)))

(def ^:private arms
  {:memo  {:id :memo  :page memo-page  :frame ::memo
           :why "mint-view! — marked and given the codec's stable memo wrapper (HD-028)"}
   :plain {:id :plain :page plain-page :frame ::plain
           :why "marked only — mint-view! exactly as it stood before the repair"}})

(defn- arm-of [id] (get arms (keyword id)))

;; ---------------------------------------------------------------------------
;; Page state
;; ---------------------------------------------------------------------------

(defonce ^:private state (atom {:mounted {} :ops {}}))

(defn- next-op!
  "The op counter is PER ARM, so both arms are handed the identical write
  sequence — the same tab flip, the same rotating narrow row, the same
  page number. A shared counter would give the two arms different writes
  and then compare the times."
  [arm-id]
  (get-in (swap! state update-in [:ops arm-id] (fnil inc 0)) [:ops arm-id]))

(defn- seed! [arm]
  (m/make-frame! (:frame arm) {:articles cards-n :tags tags-n})
  (m/reseed! (:frame arm) {:articles cards-n :tags tags-n})
  nil)

(defn- settle-frame
  "Resolves in the first task AFTER the browser produced the frame that
  followed the mutation — `requestAnimationFrame` runs before paint, so
  the `setTimeout` inside it is what clears the rendering lifecycle. This
  is what makes every window below frame-inclusive."
  []
  (js/Promise. (fn [resolve]
                 (js/requestAnimationFrame (fn [] (js/setTimeout resolve 0))))))

;; ---------------------------------------------------------------------------
;; Operations
;; ---------------------------------------------------------------------------

(defn- mount-arm!
  "Cold-mount one arm. The container is created OUTSIDE the window."
  [arm]
  (let [container (lane/fresh-container!)
        handle    (volatile! nil)
        t0        (lane/now-ms)]
    (react-dom/flushSync
      (fn [] (vreset! handle (mount/root! container (:frame arm) [(:page arm) {}]))))
    (-> (settle-frame)
        (.then (fn [_]
                 (let [ms (- (lane/now-ms) t0)]
                   (swap! state assoc-in [:mounted (:id arm)] @handle)
                   {:ms ms}))))))

(defn- write!
  "One measured write, frame-inclusive. Answers the elapsed ms and the
  body counts the write produced."
  [arm event]
  (reset-runs!)
  (let [t0 (lane/now-ms)]
    (rt/dispatch! (:frame arm) event)
    (react-dom/flushSync (fn [] nil))
    (-> (settle-frame)
        (.then (fn [_] (merge {:ms (- (lane/now-ms) t0)} (runs)))))))

(defn- op-event [op op-n]
  (case op
    :chrome (if (odd? op-n) [:conduit/show-your-feed] [:conduit/show-global-feed])
    :bulk   [:conduit/refresh-feed]
    :narrow [:conduit/favorite (:slug (m/article (mod (* 7 op-n) cards-n)))]
    ;; STRICTLY INCREASING, and above any seeded value. `(inc (mod op-n
    ;; 5))` cycled, and the frame is reseeded every round — so the write
    ;; periodically set the page number the page already had, app-db did
    ;; not move, nothing re-rendered, and the op silently measured
    ;; NOTHING. The instrument's own between-rounds guard caught it as
    ;; `{:cards 0 :pages 0}`, which is what that guard is for.
    :props  [:conduit/go-to-page (+ 1000 op-n)]
    nil))

(defn- release-arm! [arm]
  (when-some [h (get-in @state [:mounted (:id arm)])]
    (mount/unmount! h)
    (swap! state update :mounted dissoc (:id arm)))
  nil)

;; ---------------------------------------------------------------------------
;; The exported surface the driver drives
;; ---------------------------------------------------------------------------

(defn ^:export mountArm [arm-id]
  (let [arm (arm-of arm-id)]
    (seed! arm)
    (-> (mount-arm! arm)
        (.then (fn [{:keys [ms]}]
                 (let [container (:container (get-in @state [:mounted (:id arm)]))]
                   #js {"ms"         ms
                        "boundaries" (inc cards-n)
                        "cards"      (.-length (.querySelectorAll container ".article-preview"))
                        "elements"   (lane/element-count container)}))))))

(defn ^:export runOp [arm-id op-name]
  (let [arm (arm-of arm-id)
        op  (keyword op-name)]
    (-> (write! arm (op-event op (next-op! (:id arm))))
        (.then (fn [{:keys [ms cards pages]}]
                 #js {"ms" ms "cards" cards "pages" pages})))))

(defn ^:export releaseArm [arm-id]
  (release-arm! (arm-of arm-id))
  (-> (lane/settle!) (.then (fn [_] #js {"ok" true}))))

(defn ^:export residue []
  (let [r (rt/residue)]
    #js {"cells" (:cells r) "boundaries" (:boundaries r) "edges" (:edges r)}))

(defn ^:export resetRuntime
  "Drop the arm's runtime state AND both frames.

  `reset-runtime!` alone leaves the frames standing, and a frame holds its
  subscription cache — so an arm released and re-mounted accumulated, the
  next arm's heap baseline was read on a page still holding the previous
  arm's reactive graph, and the two arms' baselines came out 1.9 MB apart.
  A per-boundary figure differenced against a drifting baseline is not a
  measurement, so the frames go too."
  []
  (doseq [a (vals arms)]
    (try (rf/destroy-frame! (:frame a)) (catch :default _e nil)))
  (rt/reset-runtime!)
  #js {"ok" true})

(defn ^:export runtimeLabel [] (clj->js (lane/runtime-label)))

(defn ^:export cardsN [] cards-n)

(defn ^:export -main []
  ;; The UIx adapter, once — Arm 1's React-hook spine is built over it and
  ;; every one of its own witnesses installs it. Both arms run on the same
  ;; adapter, so it is common-mode and cancels in the delta; what it is
  ;; here for is that `make-frame` needs an installed adapter at all.
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (set! (.-HCHROME js/window)
        #js {"mountArm"      mountArm
             "runOp"         runOp
             "releaseArm"    releaseArm
             "residue"       residue
             "resetRuntime"  resetRuntime
             "runtimeLabel"  runtimeLabel
             "cardsN"        cardsN})
  (lane/record! "ready" {:arms (mapv (fn [[k v]] {:id k :why (:why v)}) arms)
                         :cards cards-n
                         :runtime (lane/runtime-label)})
  (set! (.-HCHROME_READY js/window) true)
  nil)
