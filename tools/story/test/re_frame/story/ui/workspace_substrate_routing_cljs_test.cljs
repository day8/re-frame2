(ns re-frame.story.ui.workspace-substrate-routing-cljs-test
  "The WORKSPACE cell resolves its renderer through the substrate registry
  by the canvas's policy (`canvas/variant-substrate-set`: the variant's
  declared `:substrates`, else its story's, else the shell's host
  substrate). The cell re-runs when the user toggles the substrate, so a
  cell that then painted Reagent regardless would make that re-run a lie.

  Every row distinguishes which substrate rendered: the variant's
  `:component` resolves to a Reagent view emitting `reagent-view-render`,
  the stub registered under `:uix` emits `uix-stub-render`.

  `variant-cell-inner` sits inside `#?(:cljs …)` in `workspace.cljc`, so
  only `npm run test:cljs` witnesses this namespace. The `:after` fixture
  dissocs the `:uix` stub, which `rf.story/clear-all!` does not touch."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]))

;; ---- fixtures ------------------------------------------------------------

(declare register-probe-views!)

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story.loaders/clear-watchers!)
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
  "Stand-in for a host-registered UIx render fn — returns hiccup so the test
  can walk it without a React cycle, and prints the view-id it was handed."
  [_variant-id view-id _eff-args]
  [:div {:data-test "uix-stub-render"} (str "rendered under uix: " (pr-str view-id))])

(defn- register-probe-views! []
  (rf/reg-view* :views/probe reagent-probe-view)
  (rf.story/reg-story* :story.workspace-routing {:doc "workspace-routing witness story"})
  (rf.story/reg-variant* :story.workspace-routing/uix-only
    {:doc        "Declares ONE substrate, and it is not Reagent."
     :component  :views/probe
     :substrates #{:uix}})
  (rf.story/reg-variant* :story.workspace-routing/undeclared
    {:doc        "Declares NO substrates — the shell's host substrate decides."
     :component  :views/probe})
  ;; The subject and layer inherited through `:extends` from a variant of a
  ;; story that names neither.
  (rf.story/reg-story* :story.workspace-extends {:doc "workspace-extends witness story"})
  (rf.story/reg-variant* :story.workspace-extends/base
    {:doc        "Names the subject and the layer."
     :component  :views/probe
     :substrates #{:uix}})
  (rf.story/reg-variant* :story.workspace-extends/child
    {:doc     "Declares neither; inherits both from its :extends parent."
     :extends :story.workspace-extends/base}))

;; ---- helpers -------------------------------------------------------------

(def ^:private variant-cell-inner @#'rf.story.ui.workspace/variant-cell-inner)

(defn- cell-tree
  "Render the workspace cell's inner tree for `variant-id` and expand it to
  plain hiccup. The cell has no skeleton gate to drive."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.test-helpers.e2e-multi-frame/expand-tree (variant-cell-inner variant-id)))

(defn- rendered-under-uix? [tree]
  (some? (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "uix-stub-render")))

(defn- rendered-under-reagent? [tree]
  (some? (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "reagent-view-render")))

;; Read diagnostics with `e2e-multi-frame/text-nodes`, never `pr-str` on the
;; tree: the cell's expanded tree carries nodes whose printed form recurses
;; without bound (`RangeError: Maximum call stack size exceeded`).

(deftest an-extends-child-cell-renders-what-it-inherits
  (testing "the cell reads `:component` and `:substrates` off the compiled
            plan, which folds the `:extends` chain, and renders through the
            render fn registered for the inherited :uix layer"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-extends/child)]
      (is (= "rendered under uix: :views/probe"
             (some-> (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "uix-stub-render")
                     (nth 2))))
      (is (not (rendered-under-reagent? tree))
          "and it did not fall back to the host substrate"))))

(deftest undeclared-substrate-falls-back-to-the-shell-host
  (testing "a variant declaring NO :substrates paints under the shell's
            host substrate"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-routing/undeclared)]
      (is (rendered-under-reagent? tree))
      (is (not (rendered-under-uix? tree))))))

(deftest unregistered-substrate-degrades-loudly
  (testing "a declared substrate with NO registered render fn says so
            instead of silently painting Reagent"
    (let [tree (cell-tree :story.workspace-routing/uix-only)]
      (is (not (rendered-under-reagent? tree)))
      (is (re-find #"substrate :uix is not registered"
                   (rf.story.test-helpers.e2e-multi-frame/text-nodes tree))))))

(deftest missing-view-diagnostic-survives-the-reroute
  (testing "a `:component` naming an unregistered view degrades to
            `render-view`'s inline message, via `reagent-render`"
    (rf.story/reg-variant* :story.workspace-routing/no-such-view
      {:doc       "Names a :component nobody registered."
       :component :views/does-not-exist})
    (is (re-find #"is not registered as a view"
                 (rf.story.test-helpers.e2e-multi-frame/text-nodes
                   (cell-tree :story.workspace-routing/no-such-view))))))
