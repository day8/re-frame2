(ns re-frame.story.ui.canvas-substrate-routing-cljs-test
  "The canvas single pane, the side-by-side grid and the `render-variant`
  host hook resolve the renderer through the substrate registry, keyed on
  the variant's DECLARED substrate — never Reagent by default.

  That failure is a working path rendering the WRONG thing, so every row
  distinguishes which substrate rendered: the variant's `:component`
  resolves to a Reagent view emitting `reagent-view-render`, while the stub
  registered under `:uix` emits `uix-stub-render`.

  `substrate->render-fn` is a `defonce` atom `rf.story/clear-all!` does not
  touch, so the `:after` fixture dissocs the `:uix` stub rather than leak
  it into `render_shell_cljs_test`'s
  `unregistered-substrate-renders-inline-error-cell`."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.render :as rf.story.render]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.subs :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(declare register-probe-views!)

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  ;; Without the framework `:rf/machine` sub the lifecycle machine cannot
  ;; resolve and the canvas reads `:pre-mount` indefinitely.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.canvas/reset-first-rendered!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf.story.ui.multi-substrate/unregister-substrate! :uix)
  (register-probe-views!))

(defn- restore-registry! []
  (rf.story.ui.multi-substrate/unregister-substrate! :uix))

(use-fixtures :each {:before reset-all! :after restore-registry!})

;; ---- probe views + variants ----------------------------------------------

(defn- reagent-probe-view [_args]
  [:div {:data-test "reagent-view-render"} "rendered under reagent"])

(defn- uix-stub-render
  "Stand-in for a host-registered UIx render fn. It returns hiccup so the
  test can walk it without a React render cycle, and prints the view-id it
  was handed."
  [_variant-id view-id _eff-args]
  [:div {:data-test "uix-stub-render"} (str "rendered under uix: " (pr-str view-id))])

(defn- register-probe-views! []
  (rf/reg-view* :views/probe reagent-probe-view)
  (rf.story/reg-story* :story.substrate-routing {:doc "substrate-routing witness story"})
  ;; `:loaders` makes `events-only-variant?` false, so the skeleton gate
  ;; applies and `ready-tree` drives past it.
  (rf.story/reg-variant* :story.substrate-routing/uix-only
    {:doc        "Declares ONE substrate, and it is not Reagent."
     :component  :views/probe
     :substrates #{:uix}
     :loaders    [[:noop/loader]]})
  ;; The STORY names the subject and the layer; the variant names neither.
  (rf.story/reg-story* :story.substrate-story-scope
    {:doc        "witness — story-level :component + :substrates"
     :component  :views/probe
     :substrates #{:uix}})
  (rf.story/reg-variant* :story.substrate-story-scope/inherits
    {:doc     "Declares neither :component nor :substrates."
     :loaders [[:noop/loader]]})
  ;; The parent VARIANT names the subject and the layer; its story names
  ;; neither, and the child names neither.
  (rf.story/reg-story* :story.substrate-extends
    {:doc "witness — its variants render different views"})
  (rf.story/reg-variant* :story.substrate-extends/base
    {:doc        "Names the subject and the layer."
     :component  :views/probe
     :substrates #{:uix}
     :loaders    [[:noop/loader]]})
  (rf.story/reg-variant* :story.substrate-extends/child
    {:doc     "Declares neither; inherits both from its :extends parent."
     :extends :story.substrate-extends/base})
  ;; A cross-story `:extends`, whose OWN story names a different subject.
  (rf.story/reg-story* :story.substrate-borrow
    {:doc       "witness — names a subject its variant does not render"
     :component :views/story-level})
  (rf.story/reg-variant* :story.substrate-borrow/borrow
    {:doc     "Extends a variant of another story."
     :extends :story.substrate-extends/base})
  ;; The grid-bound shape: the inherited set names two substrates.
  (rf.story/reg-variant* :story.substrate-extends/grid-base
    {:doc        "Names the subject and two layers."
     :component  :views/probe
     :substrates #{:reagent :uix}
     :loaders    [[:noop/loader]]})
  (rf.story/reg-variant* :story.substrate-extends/grid-child
    {:doc     "Inherits the subject and both layers."
     :extends :story.substrate-extends/grid-base}))

;; ---- helpers -------------------------------------------------------------

(def ^:private canvas-inner @#'rf.story.ui.canvas/canvas-inner)

(defn- ready-canvas-inner
  "Drive `variant-id`'s lifecycle to `:ready` and mark it rendered, so the
  canvas's inner render reaches the substrate branch rather than the
  skeleton, then render it."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.loaders/mount! variant-id)
  (rf.story.loaders/start-loaders! variant-id)
  (rf.story.loaders/finish-loaders! variant-id)
  (rf.story.loaders/finish-events! variant-id)
  (rf.story.ui.canvas/mark-variant-rendered! variant-id)
  (canvas-inner variant-id))

(defn- ready-tree [variant-id]
  (rf.story.test-helpers.e2e-multi-frame/expand-tree (ready-canvas-inner variant-id)))

(defn- rendered-under-reagent? [tree]
  (some? (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "reagent-view-render")))

(defn- uix-stub-text
  "The text the :uix stub rendered, or nil when it did not render. The
  stub's own node, not the tree's text: the canvas title row prints its
  view-id too."
  [tree]
  (some-> (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "uix-stub-render")
          (nth 2)))

;; ---- the single pane -----------------------------------------------------

(deftest single-pane-says-so-when-the-substrate-is-unregistered
  (testing "with :uix declared but NOT registered, the single pane names
            the missing substrate rather than silently painting Reagent"
    (let [variant-id :story.substrate-routing/uix-only
          tree       (ready-tree variant-id)]
      (is (re-find #"substrate :uix is not registered"
                   (rf.story.test-helpers.e2e-multi-frame/text-nodes tree)))
      (is (not (rendered-under-reagent? tree)))
      (rf.story/destroy-variant! variant-id))))

(deftest an-extends-child-renders-the-subject-and-layer-it-inherits
  (testing "the child names neither `:component` nor `:substrates`; its
            parent variant names both. The canvas renders the parent's view
            under the parent's :uix layer."
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (ready-tree :story.substrate-extends/child)]
      (is (= "rendered under uix: :views/probe" (uix-stub-text tree)))
      (is (not (rendered-under-reagent? tree))
          "and it did not fall back to the host substrate")
      (rf.story/destroy-variant! :story.substrate-extends/child))))

(deftest a-cross-story-extends-renders-its-parents-subject
  (testing "the child's OWN story names a different subject; the plan, a
            headless run and Docs all say the parent's"
    (rf.story/register-substrate! :uix uix-stub-render)
    (is (= "rendered under uix: :views/probe"
           (uix-stub-text (ready-tree :story.substrate-borrow/borrow))))
    (rf.story/destroy-variant! :story.substrate-borrow/borrow)))

;; ---- render-variant's host hook ------------------------------------------

(deftest story-level-declaration-reaches-the-render-variant-host
  (testing "a story declares the subject and the layer once; its variant
            declares neither. `render-variant` paints the inherited subject
            through the :uix render fn — not the `:reagent` host default,
            and not a nil view"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (rf.story.test-helpers.e2e-multi-frame/expand-tree
                 (:rendered (rf.story.render/render-variant :story.substrate-story-scope/inherits)))]
      (is (= "rendered under uix: :views/probe" (uix-stub-text tree)))
      (is (not (rendered-under-reagent? tree)))
      (rf.story/destroy-variant! :story.substrate-story-scope/inherits))))

;; ---- the grid ------------------------------------------------------------

(defn- react-class? [f]
  (and (fn? f) (true? (.-cljs$lang$type ^js f))))

(defn- expand-to-cells
  "`e2e-multi-frame/expand-tree`, except that a Reagent CLASS head stays a
  leaf: a grid cell is `safe-render-cell`'s `[<error-boundary class> {…}]`,
  which only React can render, so its props are read as written."
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)) (not (react-class? (first tree))))
    (let [result (apply (first tree) (rest tree))]
      (if (or (vector? result) (seq? result))
        (expand-to-cells result)
        (mapv expand-to-cells tree)))
    (vector? tree) (mapv expand-to-cells tree)
    (seq? tree)    (map expand-to-cells tree)
    :else          tree))

(defn- grid-cells
  "`[substrate view-id]` for each cell of the side-by-side grid (the
  `role=\"group\"` node), name-sorted by substrate."
  [tree]
  (let [nodes #(tree-seq (some-fn vector? seq?) seq %)
        grid  (some #(when (and (vector? %) (map? (second %)) (= "group" (:role (second %)))) %)
                    (nodes tree))]
    (->> (nodes grid)
         (filter #(and (vector? %) (react-class? (first %))))
         (map (fn [[_ {:keys [substrate view-id]}]] [substrate view-id]))
         (sort-by (comp name first)))))

(deftest an-extends-child-takes-the-grid-it-inherits
  (testing "two inherited substrates put the canvas on its side-by-side
            grid, and every cell renders the inherited subject"
    (rf.story/register-substrate! :uix uix-stub-render)
    (is (= [[:reagent :views/probe] [:uix :views/probe]]
           (grid-cells (expand-to-cells (ready-canvas-inner :story.substrate-extends/grid-child)))))
    (rf.story/destroy-variant! :story.substrate-extends/grid-child)))

;; ---- single-render-substrate ---------------------------------------------

(deftest single-render-substrate-policy
  ;; A single-tree render never paints under a substrate the variant did
  ;; not declare.
  (are [declared expected]
       (= expected (rf.story.ui.multi-substrate/single-render-substrate declared :reagent))
    #{:uix}           :uix
    nil               :reagent
    #{:reagent :uix}  :reagent
    ;; Host default not declared: the name-sorted first, not hash order.
    #{:uix :custom}   :custom))
