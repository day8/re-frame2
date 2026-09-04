(ns re-frame.story.ui.workspace-substrate-routing-cljs-test
  "rf2-r4coe — the WORKSPACE cell must resolve its renderer through the
  substrate registry, exactly as the canvas single-pane path now does
  (rf2-3afns / #8306).

  ## What the bead asked, and what the source answered

  The bead filed this as a decision, not a reroute: it read
  `variant-cell-inner` as having NO substrate axis at all, so that honouring
  a variant's declared `:substrates` would be NEW BEHAVIOUR needing a ruling.

  At source the cell already had a substrate axis and was already reacting to
  it — it simply did not consult it at the one line that paints:

  - `run-variant-with-shell-opts!` threads `:substrate (:substrate shell)`
    into every `runtime/run-variant` call;
  - `variant-cell` keys its re-runs on `canvas/run-key`, which CARRIES
    `:substrate` for the stated purpose of covering \"substrate flips\".

  So the cell re-ran on a substrate change and then painted Reagent
  regardless, which made that re-run a lie. That is a bypass, in the same
  shape as the canvas's, down to the shared missing-view diagnostic string —
  not a feature. And the policy question the bead wanted ruled on is already
  settled inside `canvas/variant-substrate-set`, whose resolution order IS
  the answer: the variant's declared `:substrates`, else the parent story's,
  else the shell's host substrate. Option (a) and option (b) are not rivals
  there; (b) is (a)'s fallback.

  ## Why a green compile proves nothing here

  Same reason as the canvas witness: the bug is a working path rendering the
  WRONG thing. Every test below DISTINGUISHES WHICH SUBSTRATE RENDERED — the
  variant's `:component` resolves to a Reagent view emitting
  `reagent-view-render`, the stub registered under `:uix` emits
  `uix-stub-render`, and the assertions require the uix marker PRESENT and
  the reagent marker ABSENT. Either alone would pass on a path that rendered
  both, or neither.

  Runner note: `.cljs`, and `workspace.cljc`'s body is entirely inside
  `#?(:cljs …)` — so `clojure -M:test` from `tools/story` loads the file and
  sees NONE of the code under test. This namespace is witnessed by
  `npm run test:cljs` and only by it.

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
     :component  :views/probe}))

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
  (testing "rf2-r4coe — a workspace cell whose variant declares
            `:substrates #{:uix}` renders through the render fn REGISTERED
            FOR :uix. Before the fix this branch called `(rf/view view-id)`
            itself, so the tree carried `reagent-view-render` and no uix
            marker at all — the identical bypass rf2-3afns removed from the
            canvas"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-routing/uix-only)]
      (is (rendered-under-uix? tree)
          "the cell reached the :uix render fn — the registry is ON the
           workspace's path, not merely present in it")
      (is (not (rendered-under-reagent? tree))
          "and it did NOT also paint Reagent — the silent Reagent render is
           what a UIx author got before"))))

(deftest reagent-variants-are-behaviour-unchanged
  (testing "the overwhelmingly common case — a variant declaring
            `:substrates #{:reagent}` — paints exactly as it always did"
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [tree (cell-tree :story.workspace-routing/reagent-only)]
      (is (rendered-under-reagent? tree))
      (is (not (rendered-under-uix? tree))))))

(deftest undeclared-substrate-falls-back-to-the-shell-host
  (testing "a variant declaring NO :substrates keeps the host-substrate
            behaviour — which is the bead's option (b), already present as
            the FALLBACK inside `canvas/variant-substrate-set`'s resolution
            order rather than as a rival to option (a)"
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
  (testing "a declared substrate with NO registered render fn now says so
            instead of silently painting Reagent — the user-visible half of
            the fix, and the same degradation the canvas gives"
    ;; :uix deliberately NOT registered here.
    (let [tree (cell-tree :story.workspace-routing/uix-only)
          txt  (rf.story.test-helpers.e2e-multi-frame/text-nodes tree)]
      (is (not (rendered-under-reagent? tree))
          "silence was the defect's real cost — Reagent must NOT be painted")
      (is (re-find #"substrate :uix is not registered" txt)))))

(deftest missing-view-diagnostic-survives-the-reroute
  (testing "routing through the registry must not lose the cell's
            missing-view message — `render-view` owns it now, via
            `reagent-render`, and it reads the same"
    (rf.story/reg-variant* :story.workspace-routing/no-such-view
      {:doc       "Names a :component nobody registered."
       :component :views/does-not-exist})
    (let [txt (rf.story.test-helpers.e2e-multi-frame/text-nodes (cell-tree :story.workspace-routing/no-such-view))]
      (is (re-find #"is not registered as a view" txt)))))
