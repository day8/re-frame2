(ns re-frame.story.ui.sub-overrides-render-paths-cljs-test
  "rf2-3x7nj.28.6 — every Story surface that renders a variant renders it
  inside the variant's view-state override scope.

  spec/017 §View-state subscription overrides: a variant's `:sub-overrides`
  feed the RENDER PATH, where a normally-authored view's
  `@(rf/subscribe [:q])` paints the pinned value. The canvas's single pane
  wrapped the view in `canvas/sub-overrides-scope`; the canvas's
  side-by-side substrate grid and every workspace cell did not, so a
  pinned design state painted from the real app-db exactly where a set of
  states is meant to be compared.

  The scope is the override-context Provider
  (`re-frame.story.sub-overrides/override-provider`), which an expanded
  hiccup tree carries as `[:r> Provider #js {:value overrides} child]`. Each
  test finds that node, reads its value, and checks the rendered subject sits
  inside it. That the Provider then surfaces the value at `subscribe` is
  proven with a real React render in
  `re-frame.story.sub-overrides-render-dom-cljs-test`.

  The single pane is the CONTROL: it always applied the scope, so it shows
  the probe can see one — and would fail alongside the others, rather than
  masking them, in a build whose override seam is compiled out.

  Runner note: `.cljs`, and `workspace.cljc`'s renderer sits inside
  `#?(:cljs …)`, so only `npm run test:cljs` grades this namespace."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.sub-override-context :as rf.adapter.sub-override-context]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]
            [re-frame.subs :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(declare register-variants!)

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  ;; The lifecycle machine's snapshot sub, re-registered after the registrar
  ;; clear (mirrors `re-frame.machines`), so the canvas reads `:ready`.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.canvas/reset-first-rendered!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  ;; `substrate->render-fn` survives `clear-all!`; start every case from a
  ;; known-empty :uix slot and leave it that way.
  (rf.story.ui.multi-substrate/unregister-substrate! :uix)
  (register-variants!))

(defn- restore-registry! []
  (rf.story.ui.multi-substrate/unregister-substrate! :uix))

(use-fixtures :each {:before reset-all! :after restore-registry!})

;; ---- the view + variants -------------------------------------------------

(def ^:private pinned {[:probe/n] 5})

(defn- probe-view [_args]
  [:div {:data-test "probe-view"} "probe"])

(defn- uix-stub-render [_variant-id view-id _eff-args]
  [:div {:data-test "uix-stub-render"} (str "rendered under uix: " (pr-str view-id))])

(defn- register-variants! []
  (rf/reg-sub :probe/n (fn [db _] (:n db)))
  (rf/reg-view* :views/probe probe-view)
  (rf.story/reg-story* :story.pinned {:doc "rf2-3x7nj.28.6 witness story"
                                      :component :views/probe})
  ;; `:loaders` so the canvas takes its skeleton-gated path, which the test
  ;; drives to `:ready` itself.
  (rf.story/reg-variant* :story.pinned/multi
    {:doc           "A pinned design state compared across two substrates."
     :substrates    #{:reagent :uix}
     :sub-overrides pinned
     :loaders       [[:noop/loader]]})
  (rf.story/reg-variant* :story.pinned/single
    {:doc           "The same pin on the single pane — the control."
     :substrates    #{:reagent}
     :sub-overrides pinned
     :loaders       [[:noop/loader]]})
  (rf.story/reg-variant* :story.pinned/cell
    {:doc           "The same pin, placed in a workspace cell."
     :sub-overrides pinned}))

;; ---- helpers -------------------------------------------------------------

(def ^:private canvas-inner @#'rf.story.ui.canvas/canvas-inner)
(def ^:private variant-cell-inner @#'rf.story.ui.workspace/variant-cell-inner)

(defn- react-class? [f]
  (and (fn? f) (true? (.-cljs$lang$type ^js f))))

(defn- expand
  "`e2e-multi-frame/expand-tree`, except that a Reagent CLASS head stays a
  leaf: a grid cell's error boundary is one, and only React can render it —
  a walk that calls its constructor without `new` runs it against the wrong
  receiver."
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)) (not (react-class? (first tree))))
    (let [result (apply (first tree) (rest tree))]
      (if (or (vector? result) (seq? result))
        (expand result)
        (mapv expand tree)))
    (vector? tree) (mapv expand tree)
    (seq? tree)    (map expand tree)
    :else          tree))

(defn- nodes [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- canvas-tree
  "The canvas's inner tree for `variant-id`, lifecycle driven to `:ready` so
  the render reaches its substrate branch, expanded to plain hiccup."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.loaders/mount! variant-id)
  (rf.story.loaders/start-loaders! variant-id)
  (rf.story.loaders/finish-loaders! variant-id)
  (rf.story.loaders/finish-events! variant-id)
  (rf.story.ui.canvas/mark-variant-rendered! variant-id)
  (expand (canvas-inner variant-id)))

(defn- cell-tree
  "A workspace cell's inner tree for `variant-id`, expanded to plain hiccup."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (expand (variant-cell-inner variant-id)))

(defn- override-scopes
  "Every override-context Provider node in `tree`."
  [tree]
  (let [provider (.-Provider ^js rf.adapter.sub-override-context/override-context)]
    (filter (fn [node]
              (and (vector? node)
                   (= :r> (first node))
                   (identical? provider (second node))))
            (nodes tree))))

(defn- scope-value [scope-node]
  (.-value ^js (nth scope-node 2)))

(defn- contains-node? [tree pred]
  (boolean (some pred (nodes tree))))

(defn- attr= [k v]
  (fn [node]
    (and (vector? node) (map? (second node)) (= v (get (second node) k)))))

(defn- scope-around
  "The override scope in `tree` that encloses a node matching `pred`."
  [tree pred]
  (first (filter #(contains-node? % pred) (override-scopes tree))))

;; ===========================================================================
;; The canvas: single pane (control) and side-by-side grid
;; ===========================================================================

(deftest the-single-pane-renders-inside-the-override-scope
  (testing "control — the path that always applied the scope"
    (let [tree  (canvas-tree :story.pinned/single)
          scope (scope-around tree (attr= :data-test "probe-view"))]
      (is (some? scope) "the pane's subject sits inside an override scope")
      (is (= pinned (some-> scope scope-value))
          "carrying the variant's pinned overrides"))
    (rf.story/destroy-variant! :story.pinned/single)))

(deftest the-substrate-grid-renders-inside-the-override-scope
  (testing "rf2-3x7nj.28.6 — a variant declaring two substrates takes the
            canvas's grid branch, which wrapped the grid in the frame
            provider and nothing else, so every cell's `subscribe` read the
            real app-db instead of the pinned value"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree  (canvas-tree :story.pinned/multi)
          scope (scope-around tree (attr= :role "group"))]
      (is (contains-node? tree (attr= :role "group"))
          "precondition: the canvas took its side-by-side grid branch")
      (is (some? scope) "the grid sits inside an override scope")
      (is (= pinned (some-> scope scope-value))
          "carrying the variant's pinned overrides"))
    (rf.story/destroy-variant! :story.pinned/multi)))

;; ===========================================================================
;; The workspace cell
;; ===========================================================================

(deftest a-workspace-cell-renders-inside-the-override-scope
  (testing "rf2-3x7nj.28.6 — a `:grid` / `:variants-grid` / `:tabs` / `:prose`
            cell rendered the view with no override scope, so a devcards-style
            gallery of design states painted each from its real app-db"
    (let [tree  (cell-tree :story.pinned/cell)
          scope (scope-around tree (attr= :data-test "probe-view"))]
      (is (contains-node? tree (attr= :data-test "probe-view"))
          "precondition: the cell rendered its subject")
      (is (some? scope) "the cell's subject sits inside an override scope")
      (is (= pinned (some-> scope scope-value))
          "carrying the variant's pinned overrides"))))
