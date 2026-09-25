(ns re-frame.story.ui.workspace-substrate-routing-cljs-test
  "The WORKSPACE cell resolves its renderer through the substrate
  registry, exactly as the canvas single-pane path does.

  ## The cell honours the substrate it reacts to

  - `run-variant-with-shell-opts!` threads `:substrate (:substrate shell)`
    into every `runtime/run-variant` call;
  - `variant-cell` keys its re-runs on `canvas/run-key`, which CARRIES
    `:substrate` so the cell re-runs when the user toggles the substrate.

  A cell that re-ran on a substrate change and then painted Reagent
  regardless would make that re-run a lie — the same bypass the canvas
  avoids. Which substrate the cell paints under is settled inside
  `canvas/variant-substrate-set`, whose resolution order IS the policy: the
  variant's declared `:substrates`, else the parent story's, else the
  shell's host substrate. The host substrate is the declared set's
  fallback, not its rival.

  ## Why a green compile proves nothing here

  Same reason as the canvas witness: the failure mode is a working path
  rendering the WRONG thing. Every test below DISTINGUISHES WHICH
  SUBSTRATE RENDERED — the variant's `:component` resolves to a Reagent view emitting
  `reagent-view-render`, the stub registered under `:uix` emits
  `uix-stub-render`, and the assertions require the uix marker PRESENT and
  the reagent marker ABSENT. Either alone would pass on a path that rendered
  both, or neither.

  Runner note: this file is `.cljs`, and `variant-cell-inner` sits inside
  `#?(:cljs …)` in `workspace.cljc` — so `clojure -M:test` from
  `tools/story` loads `workspace.cljc` and sees NONE of the code under
  test. This namespace is witnessed by `npm run test:cljs` and only by it.

  ## Registry hygiene

  `substrate->render-fn` is a `defonce` atom `rf.story/clear-all!` does not
  touch, so a leaked `:uix` entry would break
  `render_shell_cljs_test`'s `unregistered-substrate-renders-inline-error-cell`,
  whose precondition is that `:uix` is ABSENT. The `:after` fixture dissocs it."
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
  can walk it without a React cycle. What matters is WHICH fn the cell
  reached, not what it built."
  [_variant-id view-id _eff-args]
  [:div {:data-test "uix-stub-render"} (str "rendered under uix: " (pr-str view-id))])

(defn- register-probe-views! []
  (rf/reg-view* :views/probe reagent-probe-view)
  (rf.story/reg-story* :story.workspace-routing {:doc "rf2-r4coe witness story"})
  (rf.story/reg-variant* :story.workspace-routing/uix-only
    {:doc        "Declares ONE substrate, and it is not Reagent."
     :component  :views/probe
     :substrates #{:uix}})
  (rf.story/reg-variant* :story.workspace-routing/reagent-only
    {:doc        "Declares ONE substrate, Reagent — the unchanged baseline."
     :component  :views/probe
     :substrates #{:reagent}})
  (rf.story/reg-variant* :story.workspace-routing/undeclared
    {:doc        "Declares NO substrates — the shell's host substrate decides."
     :component  :views/probe})
  ;; The subject and layer inherited through `:extends`
  ;; from a variant of a story that names neither.
  (rf.story/reg-story* :story.workspace-extends {:doc "rf2-3x7nj.28.2 witness story"})
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
  plain hiccup. Unlike the canvas, the cell has no skeleton/lifecycle gate to
  drive — it renders its variant body directly."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.test-helpers.e2e-multi-frame/expand-tree (variant-cell-inner variant-id)))

(defn- rendered-under-uix? [tree]
  (some? (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "uix-stub-render")))

(defn- rendered-under-reagent? [tree]
  (some? (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "reagent-view-render")))

;; ===========================================================================
;; THE WITNESS
;; ===========================================================================

(deftest workspace-cell-renders-through-the-declared-substrate
  (testing "a workspace cell whose variant declares
            `:substrates #{:uix}` renders through the render fn REGISTERED
            FOR :uix. A branch calling `(rf/view view-id)` itself would
            carry `reagent-view-render` and no uix marker at all — the same
            bypass the canvas avoids"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-routing/uix-only)]
      (is (rendered-under-uix? tree)
          "the cell reached the :uix render fn — the registry is ON the
           workspace's path, not merely present in it")
      (is (not (rendered-under-reagent? tree))
          "and it did NOT also paint Reagent — a UIx author must not get a
           silent Reagent render"))))

(deftest an-extends-child-cell-renders-what-it-inherits
  (testing "a cell resolving its `:component` and `:substrates` from
            the variant's RAW body, then its story's, would render an
            `:extends` child of a variant naming both as 'no :component
            registered'. The compiled plan folds the `:extends` chain; the
            cell must read it, as the canvas does."
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-extends/child)]
      (is (= "rendered under uix: :views/probe"
             (some-> (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "uix-stub-render")
                     (nth 2)))
          "the inherited layer rendered the inherited subject — the stub
           prints the view-id it was handed")
      (is (not (rendered-under-reagent? tree))
          "and it did not fall back to the host substrate"))))

(deftest reagent-variants-are-behaviour-unchanged
  (testing "the overwhelmingly common case — a variant declaring
            `:substrates #{:reagent}` — paints under Reagent and nothing
            else"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-routing/reagent-only)]
      (is (rendered-under-reagent? tree))
      (is (not (rendered-under-uix? tree))))))

(deftest undeclared-substrate-falls-back-to-the-shell-host
  (testing "a variant declaring NO :substrates paints under the shell's
            host substrate — the FALLBACK in
            `canvas/variant-substrate-set`'s resolution order"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-routing/undeclared)]
      (is (rendered-under-reagent? tree))
      (is (not (rendered-under-uix? tree))))))

;; Read diagnostics with `rf.story.test-helpers.e2e-multi-frame/text-nodes`, never `pr-str` on the tree. The
;; cell's expanded tree carries nodes whose printed form recurses without
;; bound (`pr-str` on it raises `RangeError: Maximum call stack size
;; exceeded`), while the structural walk `text-nodes` and `find-by-test-id`
;; share handles it fine. Walking the tree is safe; printing it is not.

(deftest unregistered-substrate-degrades-loudly
  (testing "a declared substrate with NO registered render fn says so
            instead of silently painting Reagent — the same degradation the
            canvas gives"
    ;; :uix deliberately NOT registered here.
    (let [tree (cell-tree :story.workspace-routing/uix-only)
          txt  (rf.story.test-helpers.e2e-multi-frame/text-nodes tree)]
      (is (not (rendered-under-reagent? tree))
          "a silent Reagent paint would hide the missing registration —
           Reagent must NOT be painted")
      (is (re-find #"substrate :uix is not registered" txt)))))

(deftest missing-view-diagnostic-survives-the-reroute
  (testing "routing through the registry keeps the cell's missing-view
            message — `render-view` owns it, via `reagent-render`"
    (rf.story/reg-variant* :story.workspace-routing/no-such-view
      {:doc       "Names a :component nobody registered."
       :component :views/does-not-exist})
    (let [txt (rf.story.test-helpers.e2e-multi-frame/text-nodes (cell-tree :story.workspace-routing/no-such-view))]
      (is (re-find #"is not registered as a view" txt)))))
