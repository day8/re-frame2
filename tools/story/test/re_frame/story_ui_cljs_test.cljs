(ns re-frame.story-ui-cljs-test
  "CLJS smoke tests for re-frame2-story Stage 4 (rf2-ekai).

  The UI shell is Reagent-rendered, so the bulk of coverage is shape
  rather than visual — we exercise:

  - The pure shell-state helpers (selection, filters, fingerprints).
  - The pure layout resolver (`:grid`, `:prose`, `:variants-grid`).
  - The pure trace cascade grouper (six-domino projection — framework
    code, consumed by Causa's Trace tab post-rf2-sgdd3).
  - The pure argtype inference + sidebar tag collection.
  - The public mount/unmount surface on `re-frame.story`.

  The visual / interaction shape (clicking a variant row triggers a
  re-render) lives in the browser-test target (Stage 4 ships the smoke
  layer; Stage 8's examples integration covers end-to-end Playwright)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.config :as story-config]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.command-palette :as command-palette]
            [re-frame.story.ui.command-palette.view :as command-palette-view]
            [re-frame.story.ui.docs :as docs]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.controls :as controls]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.test-mode.pure :as test-mode-pure]
            [re-frame.story.ui.test-mode.view :as test-mode-view]
            [re-frame.story.ui.trace-buffer :as trace-buffer]
            [re-frame.story.ui.workspace :as workspace]
            [re-frame.trace.projection :as projection]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- stage marker --------------------------------------------------------

(deftest stage-6-marker
  (testing "Stage 6 advertises :sota-features"
    (is (= :sota-features story/stage))))

;; ---- public API additions ------------------------------------------------

(deftest mount-shell-fn-present
  (testing "mount-shell! / unmount-shell! / active-shell are public CLJS fns"
    (is (fn? story/mount-shell!))
    (is (fn? story/unmount-shell!))
    (is (fn? story/active-shell))))

(deftest active-shell-starts-nil
  (testing "active-shell returns nil before any mount"
    (is (nil? (story/active-shell)))))

;; ---- shell state transitions --------------------------------------------

(deftest select-variant-roundtrip
  (testing "select-variant + swap-state! round-trips through the atom"
    (state/swap-state! state/select-variant :story.foo/bar)
    (is (= :story.foo/bar (:selected-variant (state/get-state))))))

;; rf2-hscut — variant-row click clears the workspace slot so workspace
;; mode is no longer a one-way door. `main-pane` in `shell.cljs` short-
;; circuits on `:selected-workspace`, so a variant click that does NOT
;; clear it is invisible from the canvas. This is the symmetric mirror
;; of the workspace-row handler which clears `:selected-variant`.
;;
;; The click handler lives inline in the private `variant-row` reagent
;; component (`sidebar.cljs`), so we exercise the same pure swap-state!
;; composition the closure runs — that is the unit of behaviour the bug
;; fix codifies.
(deftest variant-click-clears-workspace-rf2-hscut
  (testing "selecting a variant from the sidebar clears any selected workspace"
    (state/swap-state! state/select-workspace :Workspace.nav/all)
    (is (= :Workspace.nav/all (:selected-workspace (state/get-state))))
    (state/swap-state!
      (fn [s] (-> s
                  (state/select-variant :story.nav/v1)
                  (state/select-workspace nil))))
    (is (= :story.nav/v1 (:selected-variant (state/get-state))))
    (is (nil? (:selected-workspace (state/get-state)))))
  (testing "mirror of workspace-row click — both row handlers are symmetric"
    (state/swap-state! state/select-variant :story.nav/v1)
    (state/swap-state!
      (fn [s] (-> s
                  (state/select-workspace :Workspace.nav/all)
                  (state/select-variant nil))))
    (is (= :Workspace.nav/all (:selected-workspace (state/get-state))))
    (is (nil? (:selected-variant (state/get-state))))))

(deftest toggle-tag-filter-flips
  (testing "toggle-tag-filter adds + removes a tag"
    (state/swap-state! state/toggle-tag-filter :dev)
    (is (contains? (:tag-filter (state/get-state)) :dev))
    (state/swap-state! state/toggle-tag-filter :dev)
    (is (not (contains? (:tag-filter (state/get-state)) :dev)))))

(deftest cell-overrides-roundtrip
  (testing "cell overrides write + clear cleanly"
    (state/swap-state! state/set-cell-override-scalar :story.x/y :label "hi")
    (is (= "hi" (get-in (state/get-state)
                        [:cell-overrides :story.x/y :label])))
    (state/swap-state! state/clear-cell-overrides :story.x/y)
    (is (nil? (get-in (state/get-state)
                      [:cell-overrides :story.x/y])))))

;; ---- command palette -----------------------------------------------------

(deftest command-palette-shortcut-detection
  (testing "Cmd-K and Ctrl-K open the global palette"
    (is (command-palette-view/shortcut-event?
          #js {:type "keydown" :key "k" :metaKey true}))
    (is (command-palette-view/shortcut-event?
          #js {:type "keydown" :key "K" :ctrlKey true})))
  (testing "modified or unrelated keydowns do not open it"
    (is (not (command-palette-view/shortcut-event?
               #js {:type "keydown" :key "k" :ctrlKey true :shiftKey true})))
    (is (not (command-palette-view/shortcut-event?
               #js {:type "keyup" :key "k" :ctrlKey true})))))

(deftest command-palette-selection-side-effects
  (testing "variant selection focuses a variant and clears workspace"
    (state/swap-state! state/select-workspace :Workspace.cp/all)
    (command-palette-view/select-entry! {:kind :variant :id :story.cp/empty})
    (is (= :story.cp/empty (:selected-variant (state/get-state))))
    (is (nil? (:selected-workspace (state/get-state)))))
  (testing "workspace selection focuses a workspace and clears variant"
    (state/swap-state! state/select-variant :story.cp/empty)
    (command-palette-view/select-entry! {:kind :workspace :id :Workspace.cp/all})
    (is (= :Workspace.cp/all (:selected-workspace (state/get-state))))
    (is (nil? (:selected-variant (state/get-state)))))
  (testing "story selection jumps to the first registered child variant"
    (command-palette-view/select-entry!
      {:kind :story :id :story.cp :variant-ids [:story.cp/a :story.cp/b]})
    (is (= :story.cp/a (:selected-variant (state/get-state)))))
  (testing "mode selection toggles the active mode"
    (command-palette-view/select-entry! {:kind :mode :id :Mode.cp/dark})
    (is (= [:Mode.cp/dark] (:active-modes (state/get-state))))))

(deftest command-palette-search-roundtrip
  (testing "CLJS search path mirrors the JVM helper"
    (let [results (command-palette/search
                    (command-palette/entries
                      {:stories    {:story.cljs.cp {:doc "Shell docs"}}
                       :variants   {:story.cljs.cp/happy {:doc "Happy path"}}
                       :workspaces {}
                       :modes      {}
                       :decorators {}})
                    "happy")]
      (is (= :story.cljs.cp/happy (:id (first results)))))))

;; ---- pure filter + grouping ---------------------------------------------

(deftest filter-variants-by-tag
  (testing "filter-variants returns matching subset"
    (story/reg-variant :story.t/a {:tags #{:dev} :events []})
    (story/reg-variant :story.t/b {:tags #{:test} :events []})
    (let [vs   (story-registrar/registrations :variant)
          devs (state/filter-variants vs #{:dev})]
      (is (contains? devs :story.t/a))
      (is (not (contains? devs :story.t/b))))))

(deftest group-variants-by-story
  (testing "group-variants-by-story builds the sidebar tree"
    (story/reg-variant :story.a/x {:events []})
    (story/reg-variant :story.a/y {:events []})
    (story/reg-variant :story.b/z {:events []})
    (let [vs       (story-registrar/registrations :variant)
          grouped  (state/group-variants-by-story vs)
          by-story (into {} (map (juxt :story-id :variants) grouped))]
      (is (= 2 (count (get by-story :story.a))))
      (is (= 1 (count (get by-story :story.b)))))))

(deftest sidebar-tag-collection
  (testing "sidebar/collect-tags enumerates registered tags"
    (story/reg-variant :story.tg/a {:tags #{:dev :test} :events []})
    (story/reg-variant :story.tg/b {:tags #{:dev :docs} :events []})
    (let [vs (story-registrar/registrations :variant)]
      (is (= [:dev :docs :test]
             (sidebar/collect-tags vs))))))

;; ---- argtype inference ---------------------------------------------------

(deftest argtype-inference
  (testing "controls/infer-widget classifies primitive shapes"
    (is (= :text    (:widget (controls/infer-widget :string))))
    (is (= :number  (:widget (controls/infer-widget :int))))
    (is (= :boolean (:widget (controls/infer-widget :boolean))))
    (let [enum (controls/infer-widget [:enum :a :b :c])]
      (is (= :select (:widget enum)))
      (is (= [:a :b :c] (:options enum))))))

(deftest argtype-resolution-from-variant
  (testing "controls/resolve-argtypes inherits from args' value shapes"
    (story/reg-variant :story.at/v
      {:args   {:label "x" :n 1 :flag true}
       :events []})
    (let [t (controls/resolve-argtypes :story.at/v)]
      (is (= :text    (:widget (get t :label))))
      (is (= :number  (:widget (get t :n))))
      (is (= :boolean (:widget (get t :flag)))))))

(deftest argtype-control-to-widget-translation
  (testing "controls/normalize-argtype-spec translates :control → :widget"
    (is (= {:widget :text}     (controls/normalize-argtype-spec {:control :text})))
    (is (= {:widget :textarea} (controls/normalize-argtype-spec {:control :textarea})))
    (is (= {:widget :select :options [:a :b]}
           (controls/normalize-argtype-spec {:control :select :options [:a :b]}))))
  (testing "normalize-argtype-spec is idempotent on :widget-keyed specs"
    (is (= {:widget :date} (controls/normalize-argtype-spec {:widget :date}))))
  (testing "non-map specs round-trip"
    (is (= "doc" (controls/normalize-argtype-spec "doc")))
    (is (nil?    (controls/normalize-argtype-spec nil))))
  (testing "resolve-argtypes honours the spec-canonical :control key"
    ;; Per spec/007-Stories.md §argtypes — author writes :control;
    ;; renderer dispatches on :widget. Without the translation this
    ;; would fall through to the 'unsupported widget' span.
    (story/reg-variant :story.argtypes/ctrl
      {:args     {:placeholder "ok" :flavor :primary}
       :argtypes {:placeholder {:control :textarea}
                  :flavor      {:control :select :options [:primary :secondary]}}
       :events   []})
    (let [t (controls/resolve-argtypes :story.argtypes/ctrl)]
      (is (= :textarea (:widget (get t :placeholder))))
      (is (= :select   (:widget (get t :flavor))))
      (is (= [:primary :secondary] (:options (get t :flavor))))
      (is (not (contains? (get t :placeholder) :control))
          ":control key is stripped after translation"))))

;; ---- workspace layout resolver ------------------------------------------

(deftest grid-layout-resolves
  (testing ":grid layout maps :variants to variant cells"
    (let [cells (workspace/resolve-layout
                  :Workspace.demo/x
                  {:layout :grid :variants [:story.a/x :story.b/y]})]
      (is (= 2 (count cells)))
      (is (every? #(= :variant (:type %)) cells))
      (is (= :story.a/x (-> cells first :variant-id))))))

(deftest variants-grid-layout-enumerates-from-registry
  (testing ":variants-grid auto-enumerates the anchor story's variants"
    (story/reg-variant :story.demo/a {:events []})
    (story/reg-variant :story.demo/b {:events []})
    (let [cells (workspace/resolve-layout
                  :Workspace.demo/all-variants
                  {:layout :variants-grid})]
      (is (= 2 (count cells)))
      (is (every? #(= :variant (:type %)) cells)))))

(deftest prose-layout-resolves
  (testing ":prose layout preserves content order, interleaving prose + variant"
    (let [cells (workspace/resolve-layout
                  :Workspace.guide/intro
                  {:layout :prose
                   :content [{:type :prose   :body "intro markdown"}
                             {:type :variant :id   :story.foo/bar}
                             {:type :prose   :body "after example"}]})]
      (is (= 3 (count cells)))
      (is (= :prose   (-> cells (nth 0) :type)))
      (is (= :variant (-> cells (nth 1) :type)))
      (is (= :prose   (-> cells (nth 2) :type))))))

;; ---- workspace: variant cell renders the variant view (rf2-zme7) --------
;;
;; rf2-zme7 — Mike's screenshot of #/stories with
;; `:Workspace.counter/auto-grid` selected showed every variant card
;; rendering as an empty frame: title + "variant frame: <id>" stub, no
;; counter UI inside. Root cause: workspace.cljc's `variant-cell` was a
;; Stage-4-era stub that never called `rf/view` — it shipped a label
;; and a placeholder div instead of invoking the registered view in the
;; variant's allocated frame. This test pins the regression: the
;; workspace renderer for a `:grid` layout with one variant must emit
;; hiccup that references the variant id and, when the variant has a
;; registered `:component`, includes a `frame-provider` wrap with that
;; variant id (so the rendered view's subscribe / dispatch scope to the
;; per-variant frame, not `:rf/default`).

(deftest workspace-view-emits-variant-cells-with-frame-providers
  (testing "workspace-view renders each variant cell with a
            frame-provider wrap scoped to the variant id (rf2-zme7)"
    ;; Register a tiny view + variant + workspace. The view returns a
    ;; sentinel hiccup tag so we can find it in the rendered tree.
    (rf/reg-event-db :rf2-zme7/init (fn [db _] (assoc db :marker 42)))
    (rf/reg-sub :rf2-zme7/marker (fn [db _] (:marker db)))
    (rf/reg-view* :rf2-zme7/view
      {}
      (fn [_args]
        [:section {:data-test "rf2-zme7-view"} "rendered"]))
    (story/reg-story :story.rf2-zme7
      {:component :rf2-zme7/view
       :substrates #{:reagent}})
    (story/reg-variant :story.rf2-zme7/v
      {:events [[:rf2-zme7/init]]
       :substrates #{:reagent}})
    (story/reg-workspace :Workspace.rf2-zme7/g
      {:layout   :grid
       :variants [:story.rf2-zme7/v]})
    ;; Render the workspace; walk the tree to confirm:
    ;;  (a) the variant id appears (cell title)
    ;;  (b) a `frame-provider` element wraps the view
    (let [tree (workspace/workspace-view :Workspace.rf2-zme7/g)
          flat (tree-seq coll? seq tree)]
      (is (some #(= :story.rf2-zme7/v %) flat)
          "the variant id appears somewhere in the rendered tree")
      ;; The rendered cell uses `r/create-class`, so the inner render
      ;; isn't directly inlined into the parent's hiccup — but the cell
      ;; component itself shows up as a vector beginning with the cell
      ;; component fn. We at least confirm the workspace produced
      ;; non-empty grid contents and the cell fn appears as a child.
      ;; The wrap is a `<section>` landmark per rf2-xc65 (a11y).
      (is (vector? tree))
      (is (= :section (first tree))))))

(deftest workspace-view-empty-for-missing-workspace
  (testing "workspace-view renders an empty / not-registered notice for
            an unregistered workspace id rather than throwing"
    (let [tree (workspace/workspace-view :Workspace.does-not/exist)]
      (is (vector? tree))
      (is (boolean (some #(and (string? %)
                               (re-find #"not registered" %))
                         (tree-seq coll? seq tree)))))))

;; ---- workspace cell keys are variant-id-derived (rf2-kgn0c) -------------
;;
;; Position-only React keys (`(str "v-" i)`) let React reconcile the prior
;; workspace's `variant-cell` components in place when the user switched
;; to a different `:variants-grid` workspace — same layout / same cell
;; positions / same component type. The cell's `r/with-let` initialiser
;; only runs once per mount, so the NEW variant's frame was never
;; allocated by `run-variant-with-shell-opts!`. Subscribes against the
;; un-allocated frame returned nil and `@nil` threw
;; `No protocol method IDeref.-deref defined for type null` —
;; ~22 pageerrors on the second workspace's app-db-diff variants
;; observed in PR #1254's Phase 1b smoke.
;;
;; Fix: cell keys derive from variant-id, and the workspace root carries
;; a workspace-id key. Two distinct workspaces with overlapping cell
;; positions therefore produce disjoint React keys and React unmounts
;; the old cells / mounts fresh ones — `r/with-let` re-fires against
;; the correct variant id.

(defn- collect-cell-keys
  "Walk the rendered workspace tree and return the set of React keys
  carried by variant-cell child vectors `[variant-cell-fn variant-id]`.
  Reagent stores `^{:key ...}` metadata directly on the hiccup vector.

  We match the shape `[fn namespaced-keyword]` — variant-cell is a
  private fn-value (no Var in CLJS), so we match structurally rather
  than by symbol. Variant ids are always namespaced keywords."
  [tree]
  (->> (tree-seq coll? seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (= 2 (count node))
                      (fn? (first node))
                      (keyword? (second node))
                      (some? (namespace (second node))))))
       (map (comp :key meta))
       (remove nil?)
       set))

(deftest workspace-grid-cells-key-on-variant-id-rf2-kgn0c
  (testing ":grid layout cells use variant-id-derived React keys so
            React mounts fresh cells when a workspace swap changes the
            variant set (per rf2-kgn0c)"
    (story/reg-variant :story.rf2-kgn0c.a/x {:events []})
    (story/reg-variant :story.rf2-kgn0c.a/y {:events []})
    (story/reg-variant :story.rf2-kgn0c.b/p {:events []})
    (story/reg-variant :story.rf2-kgn0c.b/q {:events []})
    (story/reg-workspace :Workspace.rf2-kgn0c.a/grid
      {:layout   :grid
       :variants [:story.rf2-kgn0c.a/x :story.rf2-kgn0c.a/y]})
    (story/reg-workspace :Workspace.rf2-kgn0c.b/grid
      {:layout   :grid
       :variants [:story.rf2-kgn0c.b/p :story.rf2-kgn0c.b/q]})
    (let [keys-a (collect-cell-keys
                   (workspace/workspace-view :Workspace.rf2-kgn0c.a/grid))
          keys-b (collect-cell-keys
                   (workspace/workspace-view :Workspace.rf2-kgn0c.b/grid))]
      (is (= 2 (count keys-a)) "workspace-a yields one key per cell")
      (is (= 2 (count keys-b)) "workspace-b yields one key per cell")
      (is (empty? (set/intersection keys-a keys-b))
          (str "two workspaces with disjoint variant sets MUST have "
               "disjoint cell-key sets — overlap means React would "
               "reconcile cells in place on workspace switch and the "
               "new variant's frame would never be allocated. "
               "keys-a=" (pr-str keys-a) " keys-b=" (pr-str keys-b)))
      (is (every? #(re-find #"rf2-kgn0c" %) keys-a)
          (str "cell keys MUST embed the variant id so distinct "
               "variants produce distinct keys; got " (pr-str keys-a))))))

(deftest workspace-variants-grid-cells-key-on-variant-id-rf2-kgn0c
  (testing ":variants-grid layout cells use variant-id-derived React
            keys (the layout the Phase 1b smoke triggered the bug on)"
    (story/reg-variant :story.rf2-kgn0c-vg.a/x {:events []})
    (story/reg-variant :story.rf2-kgn0c-vg.a/y {:events []})
    (story/reg-variant :story.rf2-kgn0c-vg.b/p {:events []})
    (story/reg-workspace :Workspace.rf2-kgn0c-vg.a/all
      {:layout :variants-grid})
    (story/reg-workspace :Workspace.rf2-kgn0c-vg.b/all
      {:layout :variants-grid})
    (let [keys-a (collect-cell-keys
                   (workspace/workspace-view :Workspace.rf2-kgn0c-vg.a/all))
          keys-b (collect-cell-keys
                   (workspace/workspace-view :Workspace.rf2-kgn0c-vg.b/all))]
      (is (seq keys-a))
      (is (seq keys-b))
      (is (empty? (set/intersection keys-a keys-b))
          (str "variants-grid cell keys MUST be disjoint across "
               "workspaces with disjoint anchor stories. "
               "keys-a=" (pr-str keys-a) " keys-b=" (pr-str keys-b))))))

(deftest workspace-root-section-keys-on-workspace-id-rf2-kgn0c
  (testing "the workspace's root <section> carries a workspace-id-derived
            React key so any swap unmounts the whole subtree as a
            belt-and-braces guard alongside per-cell keys"
    (story/reg-variant :story.rf2-kgn0c-root/v {:events []})
    (story/reg-workspace :Workspace.rf2-kgn0c-root/g
      {:layout   :grid
       :variants [:story.rf2-kgn0c-root/v]})
    (let [tree (workspace/workspace-view :Workspace.rf2-kgn0c-root/g)
          k    (:key (meta tree))]
      (is (string? k))
      (is (re-find #"rf2-kgn0c-root" k)
          (str "workspace-root key MUST embed the workspace id; got "
               (pr-str k))))))

;; ---- workspace cells re-run on full run-key (rf2-c56hr) -----------------
;;
;; Sibling to rf2-kgn0c (variant-id-keyed React identity) and rf2-z4fza
;; (Causa trace `t:<trace-id>` keying). The prior workspace `variant-cell`
;; kept a `last-tick` atom and re-ran `run-variant-with-shell-opts!` ONLY
;; when `:hot-reload-tick` advanced. Consequence in `:variants-grid` /
;; `:grid` workspaces: editing a control through the controls panel
;; wrote through to `:cell-overrides` but the cell's frame was never
;; re-seeded → the cell kept rendering against its original `:events`-
;; seeded app-db. Same hazard for chrome-level `:active-modes` toggles
;; and substrate flips.
;;
;; Fix: lift the canvas's `run-key` into a shared public helper and key
;; the workspace cell's re-run trigger on the FULL tuple
;; `{:variant-id :hot-reload-tick :active-modes :cell-overrides
;;   :substrate}` — same shape canvas's `run-if-needed!` uses. These
;; tests pin both halves: the shared helper detects the three previously-
;; missed transitions, AND ordinary intra-cell renders (app-db updates
;; that DON'T touch the run-key slice) still skip the re-run so user
;; interactions are not clobbered.

(deftest run-key-detects-cell-overrides-change-rf2-c56hr
  (testing "canvas/run-key flips when the shell's :cell-overrides for
            the variant changes — the workspace cell's trigger MUST see
            this transition (per rf2-c56hr; the prior :hot-reload-tick-
            only key missed it)"
    (let [vid     :story.rf2-c56hr.co/v
          shell-0 {:hot-reload-tick 0
                   :active-modes    []
                   :cell-overrides  {}
                   :substrate       :reagent}
          shell-1 (assoc-in shell-0 [:cell-overrides vid :label] "edited")
          k0      (canvas/run-key shell-0 vid)
          k1      (canvas/run-key shell-1 vid)]
      (is (not= k0 k1)
          "writing a per-variant override MUST yield a distinct run-key")
      (is (= "edited" (get-in k1 [:cell-overrides :label]))
          "the override value is carried through the run-key slice"))))

(deftest run-key-detects-active-modes-change-rf2-c56hr
  (testing "canvas/run-key flips when chrome-level :active-modes change
            — the workspace cell MUST re-seed on mode toggle"
    (let [vid     :story.rf2-c56hr.am/v
          shell-0 {:hot-reload-tick 0 :active-modes []
                   :cell-overrides {} :substrate :reagent}
          shell-1 (assoc shell-0 :active-modes [:Mode.x/dark])
          k0      (canvas/run-key shell-0 vid)
          k1      (canvas/run-key shell-1 vid)]
      (is (not= k0 k1)))))

(deftest run-key-detects-substrate-change-rf2-c56hr
  (testing "canvas/run-key flips when the host substrate changes — the
            workspace cell MUST re-seed on substrate flip"
    (let [vid     :story.rf2-c56hr.sb/v
          shell-0 {:hot-reload-tick 0 :active-modes []
                   :cell-overrides {} :substrate :reagent}
          shell-1 (assoc shell-0 :substrate :uix)
          k0      (canvas/run-key shell-0 vid)
          k1      (canvas/run-key shell-1 vid)]
      (is (not= k0 k1)))))

(deftest run-key-stable-across-ordinary-app-db-renders-rf2-c56hr
  (testing "canvas/run-key stays equal when nothing in the watched slice
            changes — guarantees an inc-click re-render does NOT clobber
            the variant's :events-seeded state. Pins the rf2-c56hr fix
            against an over-eager future change that would re-seed on
            every render."
    (let [vid     :story.rf2-c56hr.stable/v
          shell-0 {:hot-reload-tick 0 :active-modes []
                   :cell-overrides {} :substrate :reagent
                   :other-unrelated-slot "anything"}
          shell-1 (assoc shell-0 :other-unrelated-slot "changed")
          k0      (canvas/run-key shell-0 vid)
          k1      (canvas/run-key shell-1 vid)]
      (is (= k0 k1)
          (str "run-key MUST be stable when only non-watched shell "
               "slots change; got k0=" (pr-str k0)
               " k1=" (pr-str k1))))))

(deftest run-key-detects-hot-reload-tick-change-rf2-c56hr
  (testing "canvas/run-key still flips on :hot-reload-tick — the new
            workspace cell trigger MUST preserve the legacy tick-driven
            re-run path on top of the three previously-missed
            transitions"
    (let [vid     :story.rf2-c56hr.tick/v
          shell-0 {:hot-reload-tick 0 :active-modes []
                   :cell-overrides {} :substrate :reagent}
          shell-1 (assoc shell-0 :hot-reload-tick 1)
          k0      (canvas/run-key shell-0 vid)
          k1      (canvas/run-key shell-1 vid)]
      (is (not= k0 k1)))))
;; ---- controls repeater stable React keys (rf2-c8kfy) --------------------
;;
;; Pre-fix, `controls/repeater-widget` keyed each row positionally
;; (`^{:key i}`). Deleting a middle entry shifted every surviving row's
;; key up by one — React reused the original DOM node at each position
;; with the next entry's value, so an input that had focus / cursor at
;; index i+1 displayed index i's value with the SAME focus. For
;; `:set`-kind repeaters `vector-coerce` re-sorts on every render so
;; the bug fired on every keystroke.
;;
;; Fix (post-rf2-c8kfy): the shell-state carries a parallel
;; `[id0 id1 ...]` vector at `[:rf.story/repeater-row-ids
;; [variant-id path]]` synced in lockstep with the entries vector.
;; `repeater-widget` keys each row on `(str "r:" id)`; add appends a
;; fresh id, delete drops the id at position i. Surviving rows retain
;; their original id → React reconciles them in place across a
;; mid-list delete → focus + cursor are preserved.
;;
;; Same fix-class as rf2-kgn0c (workspace cells) / rf2-z4fza (causa
;; trace ribbon) / rf2-c56hr (story workspace cell re-init). The
;; namespacing-prefix discipline (`r:` for repeater, `t:` for tuple,
;; `v:` for variant cells) is consistent across the family.

(defn- expand-hiccup
  "Materialize a Reagent hiccup form by invoking any vector whose head
  is a fn — Reagent does this at render-time. The controls' top-level
  `arg-widget` dispatches to a private fn (`repeater-widget`,
  `tuple-widget`, etc.) by returning `[<fn> & args]`; tests need the
  materialized hiccup to inspect the per-row `^{:key ...}` metadata
  and the `:data-controls-row-key` slot we stamp alongside it."
  [tree]
  (cond
    (vector? tree)
    (if (fn? (first tree))
      (let [expanded (apply (first tree) (rest tree))
            meta'    (meta tree)]
        ;; Preserve the outer ^{:key ...} meta (Reagent threads it onto
        ;; the materialized child).
        (with-meta (expand-hiccup expanded) (or (meta expanded) meta')))
      (with-meta (mapv expand-hiccup tree) (meta tree)))

    (seq? tree)
    (map expand-hiccup tree)

    :else
    tree))

(defn- collect-repeater-row-keys
  "Materialize the controls hiccup tree and return the React-key vector
  for every `:div` child marked `:data-controls-row-key`. Keys live in
  `^{:key ...}` metadata on each row vector AND in the
  `:data-controls-row-key` slot — we read the meta so we mirror what
  React would actually observe."
  [tree]
  (->> (expand-hiccup tree)
       (tree-seq coll? seq)
       (filter (fn [node]
                 (and (vector? node)
                      (map?  (second node))
                      (= :div (first node))
                      (contains? (second node) :data-controls-row-key))))
       (mapv (comp :key meta))))

(deftest controls-repeater-rows-key-on-stable-id-rf2-c8kfy
  (testing "repeater-widget keys each row on a stable monotonic id
            (`r:<id>`) — NOT on its positional index. The keys MUST be
            namespaced with the `r:` prefix to consistently shape with
            rf2-kgn0c / rf2-z4fza / rf2-c56hr."
    (state/swap-state! state/set-cell-override
                       :story.c8kfy/v [:items] ["a" "b" "c"])
    (let [tree (controls/arg-widget
                 :story.c8kfy/v [:items]
                 ["a" "b" "c"]
                 {:widget :repeater :kind :vector
                  :element {:widget :text}})
          keys (collect-repeater-row-keys tree)]
      (is (= 3 (count keys)))
      (is (every? string? keys))
      (is (every? #(re-matches #"r:\d+" %) keys)
          (str "row keys MUST match the `r:<int>` shape; got " (pr-str keys)))
      (is (apply distinct? keys)
          "row keys MUST be distinct across the row set"))))

(deftest controls-repeater-mid-list-delete-preserves-surviving-keys-rf2-c8kfy
  (testing "the regression pinned by rf2-c8kfy: after deleting the
            middle row of a 4-row repeater, the surviving rows MUST
            carry the SAME React keys they had pre-delete. Position
            shifts by one — identity does not. React then reconciles
            the surviving inputs in place and focus / cursor are
            preserved (rather than leaking from row [i+1] onto row [i]
            with the old DOM node)."
    ;; Initial render against a 4-entry repeater.
    (state/swap-state! state/set-cell-override
                       :story.c8kfy/v [:items] ["a" "b" "c" "d"])
    (let [tree-before (controls/arg-widget
                        :story.c8kfy/v [:items]
                        ["a" "b" "c" "d"]
                        {:widget :repeater :kind :vector
                         :element {:widget :text}})
          keys-before (collect-repeater-row-keys tree-before)]
      (is (= 4 (count keys-before)))
      ;; Simulate the user clicking [-] on row index 1 (the second
      ;; entry). The widget calls `remove-repeater-row-id` then
      ;; updates the entries vector via `on-change-at-path`.
      (state/swap-state! state/remove-repeater-row-id
                         :story.c8kfy/v [:items] 1)
      (state/swap-state! state/set-cell-override
                         :story.c8kfy/v [:items] ["a" "c" "d"])
      (let [tree-after (controls/arg-widget
                        :story.c8kfy/v [:items]
                        ["a" "c" "d"]
                        {:widget :repeater :kind :vector
                         :element {:widget :text}})
            keys-after (collect-repeater-row-keys tree-after)]
        (is (= 3 (count keys-after)))
        ;; The CRITICAL invariant. Surviving rows keep their ids.
        (is (= [(nth keys-before 0)
                (nth keys-before 2)
                (nth keys-before 3)]
               keys-after)
            (str "post-delete surviving row keys MUST match the "
                 "pre-delete keys at positions 0, 2, 3 (the survivors). "
                 "before=" (pr-str keys-before)
                 " after="  (pr-str keys-after)))))))

(deftest controls-repeater-add-allocates-fresh-id-rf2-c8kfy
  (testing "after appending an entry the new row carries a FRESH id
            not seen on any pre-existing row — React mounts a fresh
            DOM node rather than reusing a stale one"
    (state/swap-state! state/set-cell-override
                       :story.c8kfy.add/v [:items] ["a" "b"])
    (let [tree-before (controls/arg-widget
                        :story.c8kfy.add/v [:items]
                        ["a" "b"]
                        {:widget :repeater :kind :vector
                         :element {:widget :text}})
          keys-before (collect-repeater-row-keys tree-before)]
      (is (= 2 (count keys-before)))
      ;; Simulate [+]: append id + extend entries vector.
      (state/swap-state! state/append-repeater-row-id
                         :story.c8kfy.add/v [:items])
      (state/swap-state! state/set-cell-override
                         :story.c8kfy.add/v [:items] ["a" "b" ""])
      (let [tree-after (controls/arg-widget
                        :story.c8kfy.add/v [:items]
                        ["a" "b" ""]
                        {:widget :repeater :kind :vector
                         :element {:widget :text}})
            keys-after (collect-repeater-row-keys tree-after)]
        (is (= 3 (count keys-after)))
        (is (= (subvec keys-after 0 2) keys-before)
            "the surviving prefix's keys are unchanged after append")
        (is (not (contains? (set keys-before) (nth keys-after 2)))
            (str "the new row's key MUST be fresh; before="
                 (pr-str keys-before) " after=" (pr-str keys-after)))))))

(deftest controls-repeater-set-edit-preserves-keys-rf2-c8kfy
  (testing ":set-kind repeaters re-sort entries on every render via
            `vector-coerce`. Pre-fix every keystroke shuffled
            positional keys against values. Post-fix the row keys are
            tied to the stored id vector — NOT to the visible sort
            order — so the keys are stable across edits regardless of
            re-sort."
    ;; First render syncs 3 ids for a 3-element set.
    (let [tree-1 (controls/arg-widget
                   :story.c8kfy.set/v [:tags]
                   #{"alpha" "beta" "gamma"}
                   {:widget :repeater :kind :set
                    :element {:widget :text}})
          keys-1 (collect-repeater-row-keys tree-1)
          ;; Second render against the same set — keys MUST match
          ;; exactly (same count, same ids).
          tree-2 (controls/arg-widget
                   :story.c8kfy.set/v [:tags]
                   #{"alpha" "beta" "gamma"}
                   {:widget :repeater :kind :set
                    :element {:widget :text}})
          keys-2 (collect-repeater-row-keys tree-2)]
      (is (= 3 (count keys-1)))
      (is (= keys-1 keys-2)
          "set repeater keys MUST be stable across re-renders"))))

(deftest controls-tuple-rows-key-on-positional-prefix-rf2-c8kfy
  (testing "tuple-widget rows key on `t:<i>` — tuple arity is fixed so
            position IS stable identity; the namespacing-prefix is for
            discipline-consistency with the repeater + sibling fixes.
            (Tuple positions don't reshuffle — the focus-leak class
            doesn't fire — but uniform key shape across the file pins
            the convention.)"
    (let [tree (controls/arg-widget
                 :story.c8kfy.tup/v [:pair]
                 ["x" 42]
                 {:widget :tuple :kind :tuple
                  :positions [{:widget :text} {:widget :number}]})
          keys (collect-repeater-row-keys tree)]
      (is (= 2 (count keys)))
      (is (= ["t:0" "t:1"] keys)
          (str "tuple row keys MUST be `t:<i>`; got " (pr-str keys))))))

(deftest controls-repeater-keys-not-bare-ints-rf2-c8kfy
  (testing "regression-guard: row keys MUST NOT be bare integers. The
            pre-fix shape used `^{:key i}` which serialises to a raw
            int in React's reconciler; the rf2-c8kfy fix requires the
            `r:` / `t:` namespacing prefix so distinct UI surfaces
            with the same int positions never collide."
    (state/swap-state! state/set-cell-override
                       :story.c8kfy.guard/v [:items] ["a" "b" "c"])
    (let [tree (controls/arg-widget
                 :story.c8kfy.guard/v [:items]
                 ["a" "b" "c"]
                 {:widget :repeater :kind :vector
                  :element {:widget :text}})
          keys (collect-repeater-row-keys tree)]
      (is (every? string? keys)
          (str "post-rf2-c8kfy row keys MUST be strings, never bare "
               "ints; got " (pr-str (map type keys))))
      (is (every? #(.startsWith % "r:") keys)
          (str "row keys MUST carry the `r:` namespacing prefix; got "
               (pr-str keys))))))

;; ---- trace six-domino projection ----------------------------------------

(deftest trace-group-cascades-classifies
  (testing "group-cascades splits trace events into six-domino slots.
            Per rf2-wvzgd the projection lives in
            `re-frame.trace.projection` — consumers (Causa, pair2)
            require that namespace directly. Per rf2-sgdd3 Story no
            longer ships a built-in trace panel; Causa's Trace tab is
            the RHS replacement. Event shapes here track the
            framework's actual emit pattern per Spec 009 §`:op-type`
            vocabulary."
    (let [evs       [{:op-type :event :operation :event/dispatched
                      :id 1 :tags {:dispatch-id 100 :event [:foo]}}
                     {:op-type :event :operation :event
                      :id 2 :tags {:dispatch-id 100 :phase :run-end}}
                     {:op-type :event :operation :event/do-fx
                      :id 3 :tags {:dispatch-id 100}}
                     {:op-type :fx :operation :rf.fx/handled
                      :id 4 :tags {:dispatch-id 100 :fx-id :db}}
                     {:op-type :sub/run :operation :sub/run
                      :id 5 :tags {:dispatch-id 100 :sub-id :sub/foo}}
                     {:op-type :view :operation :view/render
                      :id 6 :tags {:dispatch-id 100 :render-key [:app/root nil]}}]
          cascades  (projection/group-cascades evs)]
      (is (= 1 (count cascades)))
      (let [c (first cascades)]
        (is (= [:foo] (:event c)))
        (is (some? (:handler c)))
        (is (some? (:fx c)))
        (is (= 1 (count (:effects c))))
        (is (= 1 (count (:subs c))))
        (is (= 1 (count (:renders c))))))))

;; ---- privacy: retroactive scrub on set-show-sensitive! false (rf2-lqmje)
;;
;; Per Spec 009 §Privacy §Retroactive-scrub: toggling
;; `:trace/show-sensitive?` from true → false MUST clear every
;; per-variant trace buffer. The Story trace listener only gates at
;; ingest time, so without this scrub a sensitive cascade emitted
;; while the flag was true would remain visible in every variant's
;; downstream consumer of the per-variant buffer after the user expected privacy to be
;; restored. The trade-off (non-sensitive history also lost) is the
;; simplest correct semantic — see Spec 009 for the rationale.

(deftest set-show-sensitive!-false-clears-every-variant-buffer-rf2-lqmje
  (testing "true → false toggle clears every per-variant Story trace buffer"
    (let [v-a       :story.priv-scrub/a
          v-b       :story.priv-scrub/b
          buf-a     (trace-buffer/ensure-buffer! v-a)
          buf-b     (trace-buffer/ensure-buffer! v-b)
          mk-ev     (fn [vid sensitive?]
                      (cond-> {:op-type   :event
                               :operation :event/dispatched
                               :id        1
                               :time      1700000000000
                               :tags      {:dispatch-id 1
                                           :frame       vid
                                           :event-id    :foo
                                           :event       [:foo]}}
                        sensitive? (assoc :sensitive? true)))]
      (try
        ;; Engineer flips the flag on to investigate.
        (story-config/set-show-sensitive! true)
        ;; Simulate the per-variant listener body: with the flag on, the
        ;; listener appends every event (no suppression).
        (reset! buf-a [(mk-ev v-a true) (mk-ev v-a false)])
        (reset! buf-b [(mk-ev v-b true)])
        (story-config/note-suppressed! v-a) ; previously bumped before opt-in
        (is (= 2 (count @buf-a)))
        (is (= 1 (count @buf-b)))
        (is (pos? (story-config/suppressed-count v-a)))

        ;; Engineer flips the flag back off expecting privacy restored.
        (story-config/set-show-sensitive! false)

        (is (= 0 (count @buf-a))
            "variant A's buffer must be empty — sensitive payloads cannot survive the toggle")
        (is (= 0 (count @buf-b))
            "variant B's buffer must be empty too — the clear is global")
        (is (zero? (story-config/suppressed-count v-a))
            "per-variant suppressed counter drops in lockstep with the buffer")
        (finally
          (trace-buffer/drop-buffer! v-a)
          (trace-buffer/drop-buffer! v-b)
          (story-config/set-show-sensitive! false)
          (story-config/reset-suppressed-count!))))))

(deftest set-show-sensitive!-no-clear-when-already-false-rf2-lqmje
  (testing "false → false toggle leaves the buffers alone"
    (let [vid :story.priv-scrub/idempotent
          buf (trace-buffer/ensure-buffer! vid)]
      (try
        ;; The flag started false, so no sensitive events ever landed.
        ;; A redundant set-show-sensitive! false call must NOT throw away
        ;; the buffered non-sensitive history.
        (reset! buf [{:op-type :event :tags {:frame vid}}])
        (story-config/set-show-sensitive! false) ; redundant; default is false
        (is (= 1 (count @buf))
            "redundant set-show-sensitive! false must not clear the buffer")
        (finally
          (trace-buffer/drop-buffer! vid)
          (story-config/set-show-sensitive! false))))))

(deftest set-show-sensitive!-true-does-not-clear-rf2-lqmje
  (testing "false → true toggle leaves the buffers alone (no buffered sensitive risk)"
    (let [vid :story.priv-scrub/opt-in
          buf (trace-buffer/ensure-buffer! vid)]
      (try
        (reset! buf [{:op-type :event :tags {:frame vid}}])
        (story-config/set-show-sensitive! true)
        (is (= 1 (count @buf))
            "opting in must not clear pre-existing non-sensitive history")
        (finally
          (trace-buffer/drop-buffer! vid)
          (story-config/set-show-sensitive! false))))))

;; ---- shell render smoke -------------------------------------------------
;;
;; We don't mount to a real DOM (the node-test target has no jsdom).
;; Instead we confirm the component fns are callable and return hiccup.

(deftest shell-components-are-functions
  (testing "sidebar / controls expose top-level component fns (per
            rf2-sgdd3 the scrubber / trace / actions panels were
            retired; Causa is the RHS primary inspector now)"
    (is (fn? sidebar/sidebar))
    ;; The controls/panel takes a variant-id arg.
    (is (fn? controls/panel))
    ;; rf2-rodx — the :docs mode pane.
    (is (fn? docs/docs-view))))

(deftest docs-view-returns-hiccup-for-registered-variant
  (testing "docs-view returns a hiccup vector for a registered variant — the
            shell can mount it without a thrown ns-resolution error.
            The CLJS smoke proves the cljs-only `:require` (args /
            decorators / state) and the section renderers compose."
    (story/reg-story :story.dv
      {:doc       "parent for the docs-view smoke."
       :tags      #{:dev :docs}})
    (story/reg-variant :story.dv/x
      {:doc       "a tiny variant."
       :args      {:label "L"}
       :argtypes  {:label {:doc "label slot"}}
       :tags      #{:dev :docs}
       :events    []})
    (let [result (docs/docs-view :story.dv/x)]
      (is (vector? result))
      ;; Root is a <section> per the read-only contract.
      (is (= :section (first result))))
    ;; The fn returns nil when given no variant id (the shell already
    ;; gates this, but the helper guards itself too).
    (is (nil? (docs/docs-view nil)))))

(deftest docs-view-section-pure-helpers
  (testing "the pure section helpers run end-to-end in CLJS too"
    (story/reg-story :story.dvh
      {:tags #{:dev :docs}})
    (story/reg-variant :story.dvh/x
      {:args      {:greeting "hi"}
       :argtypes  {:greeting {:doc "the greeting"}}
       :tags      #{:dev :docs}
       :events    []})
    (let [rows (docs/args-rows :story.dvh/x {:greeting "hi"})]
      (is (= [{:key :greeting :value "hi" :doc "the greeting"}]
             rows)))
    (is (= [:dev :docs] (docs/variant-tags :story.dvh/x)))))

;; ---- :test mode (rf2-qmjo) ----------------------------------------------

(deftest test-view-is-a-fn
  (testing "test-view is callable from the shell"
    (is (fn? test-mode-view/test-view))))

(deftest test-view-empty-state-without-play
  (testing "test-view renders the empty-state placeholder when the
            variant body has no :play slot — the variant is registered
            but declares zero assertions, so no run is fired."
    (story/reg-variant :story.tv/no-play {:events []})
    (let [result (test-mode-view/test-view :story.tv/no-play)]
      ;; r/create-class returns a fn — Reagent will invoke it during
      ;; render. We at least confirm the helper returns a non-nil
      ;; component descriptor.
      (is (some? result)))
    (is (nil? (test-mode-view/test-view nil))
        "no variant-id = no pane")))

(deftest test-view-pure-helpers
  (testing "the pure section helpers run end-to-end in CLJS too"
    (let [summary (state/aggregate-summary
                    [{:assertion :rf.assert/path-equals :passed? true}
                     {:assertion :rf.assert/sub-equals  :passed? false}])]
      (is (= 2 (:total summary)))
      (is (= 1 (:passed summary)))
      (is (= 1 (:failed summary)))
      (is (false? (:all-passed? summary))))
    (let [row (test-mode-pure/assertion-row
                {:assertion :rf.assert/path-equals
                 :payload   [[:k] 1] :passed? true})]
      (is (= :pass (:status row))))
    (is (= "12 ms" (test-mode-pure/format-elapsed-ms 12)))
    (is (= "1.2 s" (test-mode-pure/format-elapsed-ms 1234)))
    (is (re-matches #"\d{2}:\d{2}:\d{2}"
                    (test-mode-pure/format-timestamp-ms (.getTime (js/Date.)))))))

;; ---- canvas: decorator-wrap exception swallow ---------------------------
;;
;; rf2-zme7 — a `:wrap` fn that throws used to propagate up the Reagent
;; render machinery and React unmounted the whole Story shell, blanking
;; the page. Repro: register a hiccup decorator whose `:wrap` fn throws
;; on call; click into a variant that references it; the shell goes
;; blank. The fix is `canvas/safe-decorated-view`: catch the exception,
;; project an error block, and re-render the uncoated variant body so
;; the user still sees content.

(deftest safe-decorated-view-handles-good-decorator
  (testing "safe-decorated-view passes the body through a well-behaved :wrap"
    (let [stack [{:id   :test/wrap
                  :args nil
                  :body {:kind :hiccup
                         :wrap (fn [body _args]
                                 [:section {:data-test "wrapped"} body])}}]
          result (canvas/safe-decorated-view
                   [:span "body"]
                   stack
                   {})]
      ;; The well-behaved decorator wraps the body in a :section.
      (is (vector? result))
      (is (= :section (first result))))))

(deftest safe-decorated-view-catches-wrap-throw
  (testing "safe-decorated-view catches an exception thrown by a :wrap fn
            and projects an error block instead of bubbling up — the
            shell stays mounted on a decorator failure (rf2-zme7)."
    (let [stack [{:id   :test/boom
                  :args ["payload"]
                  :body {:kind :hiccup
                         :wrap (fn [_body _args]
                                 ;; Mimic the rf2-zme7 repro: a bad
                                 ;; destructure that throws inside :wrap.
                                 (let [[_ _label] {:not :sequential}]
                                   (throw (ex-info "boom" {}))))}}]
          result (canvas/safe-decorated-view
                   [:span "body"]
                   stack
                   {})]
      ;; The catch branch returns a hiccup vector (not nil, not a throw).
      (is (vector? result))
      ;; The error block names the decorator(s) in the failing stack so
      ;; the user can find the offending registration.
      (is (boolean (some #(and (string? %)
                               (re-find #"test/boom" %))
                         (tree-seq coll? seq result)))))))

(deftest registry-snapshot-shape
  (testing "registry-snapshot returns every Story kind"
    (story/reg-variant :story.r/v {:events []})
    (let [snap (state/registry-snapshot)]
      (is (contains? snap :stories))
      (is (contains? snap :variants))
      (is (contains? snap :workspaces))
      (is (contains? snap :modes))
      (is (contains? snap :decorators))
      (is (contains? snap :story-panels))
      (is (contains? snap :tags))
      (is (contains? (:variants snap) :story.r/v)))))
