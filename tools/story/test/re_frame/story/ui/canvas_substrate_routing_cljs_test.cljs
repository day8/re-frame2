(ns re-frame.story.ui.canvas-substrate-routing-cljs-test
  "rf2-3afns — the canvas SINGLE-PANE branch and the `render-variant` host
  hook must resolve the renderer through the substrate registry, keyed on the
  variant's DECLARED substrate.

  ## The defect this namespace is the witness for

  Story's substrate abstraction is an open runtime registry with a public
  `register-substrate!` — and the default render path did not use it.
  `rf.story.ui.canvas/canvas-inner` used the declared substrate set only as a COUNT: a set
  of size one fell to the `:else` branch, which called `(rf/view view-id)`
  itself and embedded the result as a Reagent hiccup vector.
  `canonical/render-host-scope` passed a LITERAL `:reagent` into the shared
  seam. So a variant declaring `:substrates #{:uix}` — a set of size one,
  which is exactly what a single-substrate UIx story looks like — rendered
  under REAGENT, and the user was not told.

  ## Why a green compile proved nothing, and what these tests do instead

  The bug was a working code path rendering the WRONG thing, which compiles
  perfectly. Every test here therefore DISTINGUISHES WHICH SUBSTRATE ACTUALLY
  RENDERED: the variant's `:component` resolves to a Reagent view emitting
  `reagent-view-render`, while the stub registered under `:uix` emits
  `uix-stub-render`. Asserting the uix marker is present AND the reagent
  marker is absent is the whole point — either assertion alone would pass on
  a path that rendered both, or neither.

  The existing coverage could not catch this. `render_shell_cljs_test`'s
  `:uix` arms drive `multi-substrate-grid` DIRECTLY, and
  `story_multi_substrate_cljs_test` covers `render-view` on its own terms —
  neither goes anywhere near `canvas-inner`, which is the path almost every
  story takes.

  ## Registry hygiene

  `substrate->render-fn` is a `defonce` atom that `rf.story/clear-all!` does not
  touch, so a `:uix` stub registered here would leak into every namespace that
  runs after this one — including `render_shell_cljs_test`'s
  `unregistered-substrate-renders-inline-error-cell`, whose precondition is
  that `:uix` is ABSENT. The `:after` fixture dissocs it.

  Sub-millisecond per case; no DOM mount, no React, no Playwright."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
  ;; EP-0001 (rf2-vzld77 / rf2-ixb0bq): machine snapshots are durable
  ;; RUNTIME-DB state at [:rf.runtime/machines :snapshots <id>] — the framework
  ;; `:rf/machine` sub reads the runtime-db partition, NOT the retired app-db
  ;; `:rf/runtime` path. Mirror `re-frame.machines`. Without it the lifecycle
  ;; machine cannot resolve and the canvas reads `:pre-mount` indefinitely.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.canvas/reset-first-rendered!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  ;; Start every case from a KNOWN-EMPTY :uix slot. Individual tests register
  ;; the stub when they want the registered path; the unregistered-substrate
  ;; test wants it absent.
  (rf.story.ui.multi-substrate/unregister-substrate! :uix)
  (register-probe-views!))

(defn- restore-registry! []
  (rf.story.ui.multi-substrate/unregister-substrate! :uix))

(use-fixtures :each {:before reset-all! :after restore-registry!})

;; ---- probe views + variants ----------------------------------------------

;; The two markers that make "which substrate rendered?" answerable. If the
;; canvas resolves through `rf/view` (the pre-rf2-3afns Reagent-pinned path)
;; the tree carries `reagent-view-render`; if it resolves through the registry
;; it carries `uix-stub-render`. They are mutually exclusive by construction.

(defn- reagent-probe-view [_args]
  [:div {:data-test "reagent-view-render"} "rendered under reagent"])

(defn- uix-stub-render
  "Stand-in for a host-registered UIx render fn. The real one would build a
  React element; this returns hiccup so the test can walk it without a React
  render cycle. What matters is WHICH fn the canvas reached, not what it built."
  [_variant-id view-id _eff-args]
  [:div {:data-test "uix-stub-render"} (str "rendered under uix: " (pr-str view-id))])

(defn- register-probe-views! []
  (rf/reg-view* :views/probe reagent-probe-view)
  (rf.story/reg-story* :story.substrate-routing {:doc "rf2-3afns witness story"})
  ;; `:loaders` is declared so `events-only-variant?` returns false — the only
  ;; condition that matters for the `loading-phase?` skeleton gate. The test
  ;; drives the lifecycle machine directly, so the slot needs no real event.
  (rf.story/reg-variant* :story.substrate-routing/uix-only
    {:doc        "Declares ONE substrate, and it is not Reagent."
     :component  :views/probe
     :substrates #{:uix}
     :loaders    [[:noop/loader]]})
  ;; rf2-sc5g0 — the same declaration, one level up. The STORY names the
  ;; subject and the layer; the variant names neither and inherits both.
  (rf.story/reg-story* :story.substrate-story-scope
    {:doc        "rf2-sc5g0 witness — story-level :component + :substrates"
     :component  :views/probe
     :substrates #{:uix}})
  (rf.story/reg-variant* :story.substrate-story-scope/inherits
    {:doc     "Declares neither :component nor :substrates."
     :loaders [[:noop/loader]]})
  (rf.story/reg-variant* :story.substrate-routing/reagent-only
    {:doc        "Declares ONE substrate, Reagent — the unchanged baseline."
     :component  :views/probe
     :substrates #{:reagent}
     :loaders    [[:noop/loader]]}))

;; ---- helpers -------------------------------------------------------------

(def ^:private canvas-inner @#'rf.story.ui.canvas/canvas-inner)

(defn- ready-tree
  "Drive `variant-id`'s lifecycle to `:ready`, then render the canvas's inner
  tree and expand it to plain hiccup. `:ready` + the first-rendered sentinel
  is what elides the skeleton so the render reaches the substrate branch at
  all — a skeleton tree carries NEITHER marker and would make both assertions
  fail for the wrong reason."
  [variant-id]
  (rf/make-frame {:id variant-id})
  (rf.story.loaders/mount! variant-id)
  (rf.story.loaders/start-loaders! variant-id)
  (rf.story.loaders/finish-loaders! variant-id)
  (rf.story.loaders/finish-events! variant-id)
  (rf.story.ui.canvas/mark-variant-rendered! variant-id)
  (rf.story.test-helpers.e2e-multi-frame/expand-tree (canvas-inner variant-id)))

(defn- rendered-under-uix? [tree]
  (some? (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "uix-stub-render")))

(defn- rendered-under-reagent? [tree]
  (some? (rf.story.test-helpers.e2e-multi-frame/find-by-test-id tree "reagent-view-render")))

;; ===========================================================================
;; THE WITNESS — the canvas single-pane path honours the declared substrate
;; ===========================================================================

(deftest single-pane-renders-through-the-declared-substrate
  (testing "rf2-3afns — a variant declaring `:substrates #{:uix}` renders
            through the render fn REGISTERED FOR :uix, not through Reagent.
            This is the bead's defect stated as an assertion: before the fix
            the canvas `:else` branch called `(rf/view view-id)` itself, so
            this tree carried `reagent-view-render` and no uix marker at all."
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [variant-id :story.substrate-routing/uix-only
          tree       (ready-tree variant-id)]
      (is (rendered-under-uix? tree)
          "the canvas reached the :uix render fn — the registry is ON the
           default path, not merely present in it")
      (is (not (rendered-under-reagent? tree))
          "and it did NOT also paint Reagent — a UIx user gets a UIx render,
           which is the whole defect (a silent Reagent render is what they
           got before)")
      (rf.story/destroy-variant! variant-id))))

(deftest single-pane-substrate-is-resolved-not-counted
  (testing "rf2-3afns — the substrate SET SIZE decides grid-vs-single-pane and
            nothing else. A one-element set is not a licence to assume
            Reagent: swap the sole declared substrate and the renderer swaps
            with it, with the count held constant at one."
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [uix-tree     (ready-tree :story.substrate-routing/uix-only)
          reagent-tree (ready-tree :story.substrate-routing/reagent-only)]
      (is (and (rendered-under-uix? uix-tree)
               (not (rendered-under-reagent? uix-tree)))
          "#{:uix} → the uix render fn")
      (is (and (rendered-under-reagent? reagent-tree)
               (not (rendered-under-uix? reagent-tree)))
          "#{:reagent} → the built-in reagent render fn (the baseline this
           fix must not disturb — the same count, the other renderer)")
      (rf.story/destroy-variant! :story.substrate-routing/uix-only)
      (rf.story/destroy-variant! :story.substrate-routing/reagent-only))))

(deftest single-pane-says-so-when-the-substrate-is-unregistered
  (testing "rf2-3afns — the user-visible half of the fix. With :uix declared
            but NOT registered, the single pane must say so rather than
            silently painting Reagent. Silence was the defect's real cost: a
            UIx user got a Reagent render and no signal that anything was
            wrong."
    (is (not (contains? @rf.story.ui.multi-substrate/substrate->render-fn :uix))
        "precondition: :uix absent from the registry")
    (let [variant-id :story.substrate-routing/uix-only
          tree       (ready-tree variant-id)
          text       (rf.story.test-helpers.e2e-multi-frame/text-nodes tree)]
      (is (re-find #"is not registered" text)
          "the miss is named — `render-view`'s FRAGMENT-level diagnostic,
           the counterpart to the grid's cell-level error cell")
      (is (re-find #"uix" text)
          "and it names WHICH substrate, so the author knows what to
           register")
      (is (not (rendered-under-reagent? tree))
          "it did NOT fall back to Reagent — falling back silently is the
           bug, not the remedy")
      (rf.story/destroy-variant! variant-id))))

;; ===========================================================================
;; render-variant's host hook — the second pinned site
;; ===========================================================================

(deftest render-variant-host-honours-the-declared-substrate
  (testing "rf2-3afns — `canonical/render-host-scope` passed a LITERAL
            :reagent into the shared seam, so `render-variant` painted a
            #{:uix} variant under Reagent too. The canvas and the host agreed
            with each other only because both were wrong the same way; they
            must now agree by both being right."
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [result (rf.story.render/render-variant :story.substrate-routing/uix-only)
          tree   (rf.story.test-helpers.e2e-multi-frame/expand-tree (:rendered result))]
      (is (= :rendered (:status result))
          "the CLJS canonical vocabulary installs the host → :rendered")
      (is (rendered-under-uix? tree)
          "render-variant painted through the :uix render fn")
      (is (not (rendered-under-reagent? tree))
          "and not through Reagent")
      (rf.story/destroy-variant! :story.substrate-routing/uix-only))))

;; ===========================================================================
;; rf2-sc5g0 — the same disagreement with the declaration ONE LEVEL UP
;; ===========================================================================
;;
;; `plan/variant-plan` folded `:substrates` and `:component` from the VARIANT
;; and its `:extends` chain only, so a STORY-level declaration never reached
;; `[:world …]`. The canvas was unaffected (`variant-substrate-set` and
;; `variant-component` walk to the story themselves) while `render-variant`
;; read the plan and got nothing — the `:reagent` host default and a nil
;; view. The two rows below are the pair: the canvas half is the CONTROL that
;; was already right, the host half is the one that was wrong. Asserting only
;; the host would not say they now agree, which is the property rf2-3afns
;; established the plan exists to guarantee.

(deftest story-level-declaration-reaches-the-render-variant-host
  (testing "rf2-sc5g0 — a story declares the subject and the layer once; its
            variant declares neither. `render-variant` must paint through
            the :uix render fn. Before the fix the plan carried no
            `:substrates` (so the host default :reagent won) and no
            `:component` (so the view was nil)."
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [result (rf.story.render/render-variant :story.substrate-story-scope/inherits)
          tree   (rf.story.test-helpers.e2e-multi-frame/expand-tree (:rendered result))]
      (is (= :rendered (:status result)))
      (is (rendered-under-uix? tree)
          "the inherited layer reached the host — the substrate half")
      (is (re-find #":views/probe" (rf.story.test-helpers.e2e-multi-frame/text-nodes tree))
          "and the inherited SUBJECT reached it too: the uix stub prints the
           view-id it was handed, so a nil view would print `nil` here. This
           is the `:component` half, which had no fallback in
           `rf.story.render/prepare-render` at all.")
      (is (not (rendered-under-reagent? tree))
          "and it did NOT fall back to Reagent")
      (rf.story/destroy-variant! :story.substrate-story-scope/inherits))))

(deftest story-level-declaration-makes-canvas-and-host-agree
  (testing "rf2-sc5g0 — the canvas single-pane path ALREADY honoured a
            story-level declaration, which is exactly why the disagreement
            was invisible: the live shell painted UIx while `render-variant`
            painted Reagent, for the same variant. Both must now be UIx."
    (rf.story/register-substrate! :uix uix-stub-render)
    (let [variant-id  :story.substrate-story-scope/inherits
          canvas-tree (ready-tree variant-id)
          host-tree   (rf.story.test-helpers.e2e-multi-frame/expand-tree
                        (:rendered (rf.story.render/render-variant variant-id)))]
      (is (and (rendered-under-uix? canvas-tree)
               (not (rendered-under-reagent? canvas-tree)))
          "the canvas — the control, unchanged by this fix")
      (is (and (rendered-under-uix? host-tree)
               (not (rendered-under-reagent? host-tree)))
          "and the host, which is what changed")
      (rf.story/destroy-variant! variant-id))))

;; ===========================================================================
;; single-render-substrate — the policy, on its own terms
;; ===========================================================================
;;
;; A single-tree render has to reduce a declared SET to one substrate. The
;; rule the canvas and the host both depend on: never paint under a substrate
;; the variant did not declare.

(deftest single-render-substrate-policy
  (testing "one declared substrate wins outright — the case the whole bead is
            about"
    (is (= :uix (rf.story.ui.multi-substrate/single-render-substrate #{:uix} :reagent)))
    (is (= :reagent (rf.story.ui.multi-substrate/single-render-substrate #{:reagent} :reagent))))

  (testing "nothing declared falls back to the host default"
    (is (= :reagent (rf.story.ui.multi-substrate/single-render-substrate nil :reagent)))
    (is (= :reagent (rf.story.ui.multi-substrate/single-render-substrate #{} :reagent))))

  (testing "a multi-substrate variant that DECLARED the host default keeps it
            — the common #{:reagent :uix} case is behaviour-unchanged"
    (is (= :reagent (rf.story.ui.multi-substrate/single-render-substrate #{:reagent :uix} :reagent))))

  (testing "a multi-substrate variant that did NOT declare the host default
            gets a declared one, chosen deterministically by name rather than
            by hash order — never the undeclared default"
    (let [picked (rf.story.ui.multi-substrate/single-render-substrate #{:uix :custom} :reagent)]
      (is (contains? #{:uix :custom} picked)
          "the pick is one the variant actually declared")
      (is (= :custom picked)
          "and it is name-sorted, so the choice is a decision rather than
           whatever the set's hash order happened to be"))))
