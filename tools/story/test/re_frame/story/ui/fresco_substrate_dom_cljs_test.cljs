(ns re-frame.story.ui.fresco-substrate-dom-cljs-test
  "THE `:fresco` SUBSTRATE RENDER FN, and the Reagent-parent crossing it
  stands on.

  [[fresco-render]] is the consumer's five lines — the recipe written out
  in `re-frame.story.ui.multi-substrate`'s ns docstring — registered through
  the public `rf.story/register-substrate!`: Story ships no installer for
  `:fresco`, exactly as for `:uix`.

  ## Row order matters

  The two write rows mount on `:story.fresco/card`, whose cell an earlier
  row (`crossing-paints-under-a-reagent-parent`) leaves in Fresco's table
  and the fixture invalidates by re-registering `::counter`, so they
  exercise `impl.collector/acquire-cell!` rewiring a REUSED cell. That is
  why the fixture deliberately does not reset the Fresco runtime. The
  witness that pins the acquire with the crossing held out of the row is
  `re-frame.fresco.foreign-root-bridge-dom-cljs-test`.

  `-dom-cljs-test$` puts this namespace in `:browser-test` AND in
  `:node-test`; the rows that need a fiber self-gate on `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco :as rf.fresco]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.subs :as rf.subs]))

;; ---- the subject: ordinary Fresco views, declared at namespace load -------

(rf.fresco/defview fresco-card
  "An ordinary boundary. It reads a subscription, so the frame it resolved
  is observable on screen."
  [props]
  [:article {:class "hic-card" :data-test "fresco-card"}
   (str (:label props) "/" (rf.fresco/sub [::counter]))])

(rf.fresco/defview fresco-panel
  "A SECOND boundary, so a row can tell one fresco view from another."
  [props]
  [:aside {:data-test "fresco-panel"} (str "panel:" (:label props))])

(def ^:private card-id  ::fresco-card)
(def ^:private panel-id ::fresco-panel)

(def ^:private bridged-card
  "The OTHER bridge door, minted once at top level: `rf.fresco/as-component`
  allocates a component, so minting one inside a render would hand React a
  fresh element type every pass."
  (react/memo (rf.fresco/as-component fresco-card)))

(def ^:private alias-entries
  "The registrar entries `rf.fresco/defview` published at namespace load,
  captured before any fixture clears them; `reset-all!` folds them back."
  {card-id  (rf/handler-meta {:source :store :kind :view :id card-id})
   panel-id (rf/handler-meta {:source :store :kind :view :id panel-id})})

;; ---- the recipe under test -------------------------------------------------

(defn- fresco-render
  "The `:fresco` substrate render fn, exactly as a host writes it (and
  exactly as `multi-substrate`'s ns docstring writes it out)."
  [_variant-id view-id args]
  (if-let [head (rf/view view-id)]
    (rf.fresco/as-element [head args])
    [:div (str ":component " (pr-str view-id)
               " is not registered as a fresco view")]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter) (catch :default _ nil))
  ;; Without the framework `:rf/machine` sub the canvas parks at
  ;; `:pre-mount` forever.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.canvas/reset-first-rendered!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (doseq [[id entry] alias-entries]
    (rf.registrar/register! :view id entry))
  (rf.story.ui.multi-substrate/unregister-substrate! :fresco)
  (rf/reg-event :hicsub/bump
    (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf/reg-sub ::counter (fn [db _] (or (:n db) 0)))
  (rf.story/reg-story* :story.fresco {:doc "fresco-substrate witness story"})
  (rf.story/reg-variant* :story.fresco/card
    {:component  card-id
     :substrates #{:fresco}
     :args       {:label "alpha"}
     :loaders    [[:noop/loader]]})
  (rf.story/reg-variant* :story.fresco/panel
    {:component  panel-id
     :substrates #{:fresco}
     :args       {:label "beta"}
     :loaders    [[:noop/loader]]}))

(defn- restore-registry!
  "`substrate->render-fn` is a `defonce` atom that `rf.story/clear-all!` does
  not touch, so a `:fresco` entry left here would leak into every
  namespace that runs after this one."
  []
  (rf.story.ui.multi-substrate/unregister-substrate! :fresco))

(use-fixtures :each {:before reset-all! :after restore-registry!})

;; ---- helpers --------------------------------------------------------------

(def ^:private canvas-inner @#'rf.story.ui.canvas/canvas-inner)

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle!
  "The ratom host's drain: `r/flush` runs the reactions the write enqueued
  (the notification a Fresco cell's watch rides), and the empty `flushSync`
  lets the sync-lane `onStoreChange` that raised commit."
  []
  (r/flush)
  (react-dom/flushSync (fn [] nil))
  nil)

(defn- write-and-settle! [frame-kw n]
  (rf/dispatch-sync [:hicsub/bump n] {:frame frame-kw})
  (settle!))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(defn- render-under-reagent-parent!
  "Commit `child` inside a Reagent parent under `rf/frame-provider`."
  [root variant-id child]
  (react-dom/flushSync
    (fn []
      (rdc/render root
        [rf/frame-provider {:frame variant-id}
         [:div.reagent-parent child]]))))

(defn- card-text [mount-node]
  (some-> (.querySelector mount-node "[data-test=\"fresco-card\"]") .-textContent))

(defn- ready-tree
  "Drive `variant-id`'s lifecycle to `:ready` so the canvas paints the
  subject rather than the skeleton, then expand its inner tree."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.loaders/mount! variant-id)
  (rf.story.loaders/start-loaders! variant-id)
  (rf.story.loaders/finish-loaders! variant-id)
  (rf.story.loaders/finish-events! variant-id)
  (rf.story.ui.canvas/mark-variant-rendered! variant-id)
  (rf.story.test-helpers.e2e-multi-frame/expand-tree (canvas-inner variant-id)))

(defn- find-react-element
  "The first React element in an expanded hiccup tree — `expand-tree`
  leaves one untouched."
  [tree]
  (cond
    (react/isValidElement tree) tree
    (or (vector? tree) (seq? tree)) (some find-react-element tree)
    :else nil))

;; ---- the crossing ---------------------------------------------------------

(deftest crossing-paints-under-a-reagent-parent
  (testing "a `rf.fresco/defview` boundary minted by `rf.fresco/as-element`
            and spliced into a Reagent tree under `rf/frame-provider` paints
            its own markup and reads the VARIANT's frame (`7`), not the
            default frame's 0"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [variant-id :story.fresco/card
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (rf/make-frame {:id variant-id})
        (rf/dispatch-sync [:hicsub/bump 7] {:frame variant-id})
        (try
          (render-under-reagent-parent! root variant-id
            (rf.fresco/as-element [fresco-card {:label "alpha"}]))
          (is (= "alpha/7" (card-text mount-node)))
          (finally
            (try (.unmount root) (catch :default _ nil))))))))

(deftest a-write-repaints-a-crossed-boundary
  (testing "a boundary spliced into a Reagent tree by `rf.fresco/as-element`
            repaints on a write into its own frame. A deaf boundary paints
            `alpha/1` and then does not move."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [variant-id :story.fresco/card
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (rf/make-frame {:id variant-id})
        (write-and-settle! variant-id 1)
        (try
          (render-under-reagent-parent! root variant-id
            (rf.fresco/as-element [fresco-card {:label "alpha"}]))
          (is (= "alpha/1" (card-text mount-node)) "it painted, reading the variant's frame")
          (write-and-settle! variant-id 42)
          (is (= "alpha/42" (card-text mount-node)) "the write re-ran the body")
          (finally
            (try (.unmount root) (catch :default _ nil))))))))

(deftest a-write-repaints-a-boundary-crossed-in-by-as-component
  (testing "the same claim through the OTHER bridge door, memoized on the
            head, so neither documented parent is left deaf"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [variant-id :story.fresco/card
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (rf/make-frame {:id variant-id})
        (write-and-settle! variant-id 1)
        (try
          (render-under-reagent-parent! root variant-id
            (react/createElement bridged-card #js {"label" "brg"}))
          (is (= "brg/1" (card-text mount-node)) "it painted through the bridge")
          (write-and-settle! variant-id 42)
          (is (= "brg/42" (card-text mount-node)) "the write re-ran the body here too")
          (finally
            (try (.unmount root) (catch :default _ nil))))))))

(deftest a-reagent-parent-rerender-does-not-remount-the-boundary
  (testing "the recipe mints a FRESH element every pass and keeps no cache,
            which is safe only because `defview` mints one stable `React.memo`
            wrapper per head. A Reagent parent re-render must re-render the
            boundary, never remount it — measured on the DOM node's identity,
            which a remount replaces. The re-render is driven by a ratom
            inside the tree: a second top-level `rdc/render` mints a new root
            component type and so always remounts."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (let [variant-id :story.fresco/card
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)
            !label     (r/atom "alpha")
            parent     (fn [] [:div.reagent-parent
                               (fresco-render variant-id card-id {:label @!label})])
            card-node  #(.querySelector mount-node "[data-test=\"fresco-card\"]")]
        (rf/make-frame {:id variant-id})
        (rf/dispatch-sync [:hicsub/bump 3] {:frame variant-id})
        (try
          (react-dom/flushSync
            (fn [] (rdc/render root [rf/frame-provider {:frame variant-id} [parent]])))
          (let [first-node (card-node)]
            (react-dom/flushSync (fn [] (reset! !label "beta") (r/flush)))
            (is (= "beta/3" (some-> (card-node) .-textContent))
                "the boundary re-rendered with the new props")
            (is (identical? first-node (card-node))
                "and did NOT remount: the very same DOM node"))
          (finally
            (try (.unmount root) (catch :default _ nil))))))))

;; ---- the registry on the canvas's single-pane path ------------------------

(deftest the-registered-fresco-render-fn-is-what-the-single-pane-reaches
  (testing "a variant declaring `:substrates #{:fresco}` reaches the fn
            registered under `:fresco`, which mints the variant's OWN view —
            the head's stable memo type — with Story's resolved args crossing
            as the boundary's props map: kebab keywords, by identity, with no
            camelCase round trip"
    (rf.story/register-substrate! :fresco fresco-render)
    (let [card  (find-react-element (ready-tree :story.fresco/card))
          panel (find-react-element (ready-tree :story.fresco/panel))]
      (is (identical? (unchecked-get fresco-card "frescoMemo") (.-type card)))
      (is (= {:label "alpha"} (unchecked-get (.-props card) "rfProps")))
      (is (identical? (unchecked-get fresco-panel "frescoMemo") (.-type panel)))
      (rf.story/destroy-variant! :story.fresco/card)
      (rf.story/destroy-variant! :story.fresco/panel))))

(deftest the-reagent-substrate-refuses-a-fresco-head-inline
  (testing "`rf/view` answers a Fresco boundary, which is a React component
            type: spliced as `[head args]` into a Reagent tree it would be
            called with the wrong ABI. The `:reagent` substrate returns an
            inline diagnostic naming the substrate that CAN mount it"
    (rf.story.ui.multi-substrate/install-reagent-substrate!)
    (let [painted (rf.story.ui.multi-substrate/render-view
                    :reagent :story.fresco/card card-id {:label "one"})
          text    (pr-str painted)]
      (is (= :div (first painted))
          "the inline diagnostic, not the head spliced in head position")
      (is (re-find #"declare :fresco" text)
          "it names the substrate to declare")
      (is (re-find #":substrates" text)
          "and where to declare it"))))
