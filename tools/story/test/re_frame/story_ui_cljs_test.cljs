(ns re-frame.story-ui-cljs-test
  "CLJS smoke tests for the re-frame2-story shell.

  The UI shell is Reagent-rendered, so the bulk of coverage is shape
  rather than visual — we exercise:

  - The shell-state transitions and the pure grouping / tag-collection
    helpers.
  - Argtype resolution + the controls widget tree.
  - The workspace layout renderers (`:tabs`, `:grid`, `:variants-grid`)
    and their cell keys.
  - The public mount/unmount surface on `re-frame.story`.

  The visual / interaction shape (clicking a variant row triggers a
  re-render) lives in the browser-test target; this ns is the smoke
  layer."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.command-palette.view :as rf.story.ui.command-palette.view]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.controls :as rf.story.ui.controls]
            [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
            [re-frame.story.ui.test-mode.pure :as rf.story.ui.test-mode.pure]
            [re-frame.story.ui.test-mode.view :as rf.story.ui.test-mode.view]
            [re-frame.story.ui.trace-buffer :as rf.story.ui.trace-buffer]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- public API additions ------------------------------------------------

(deftest active-shell-starts-nil
  (testing "active-shell returns nil before any mount"
    (is (nil? (rf.story/active-shell)))))

;; ---- command palette -----------------------------------------------------

(deftest command-palette-shortcut-detection
  (testing "Cmd-K and Ctrl-K open the global palette"
    (is (rf.story.ui.command-palette.view/shortcut-event?
          #js {:type "keydown" :key "k" :metaKey true}))
    (is (rf.story.ui.command-palette.view/shortcut-event?
          #js {:type "keydown" :key "K" :ctrlKey true})))
  (testing "modified or unrelated keydowns do not open it"
    (is (not (rf.story.ui.command-palette.view/shortcut-event?
               #js {:type "keydown" :key "k" :ctrlKey true :shiftKey true})))
    (is (not (rf.story.ui.command-palette.view/shortcut-event?
               #js {:type "keyup" :key "k" :ctrlKey true})))))

(deftest command-palette-selection-side-effects
  (testing "variant selection focuses a variant and clears workspace"
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-workspace :Workspace.cp/all)
    (rf.story.ui.command-palette.view/select-entry! {:kind :variant :id :story.cp/empty})
    (is (= :story.cp/empty (:selected-variant (rf.story.ui.state/get-state))))
    (is (nil? (:selected-workspace (rf.story.ui.state/get-state)))))
  (testing "workspace selection focuses a workspace and clears variant"
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.cp/empty)
    (rf.story.ui.command-palette.view/select-entry! {:kind :workspace :id :Workspace.cp/all})
    (is (= :Workspace.cp/all (:selected-workspace (rf.story.ui.state/get-state))))
    (is (nil? (:selected-variant (rf.story.ui.state/get-state)))))
  (testing "story selection jumps to the first registered child variant"
    (rf.story.ui.command-palette.view/select-entry!
      {:kind :story :id :story.cp :variant-ids [:story.cp/a :story.cp/b]})
    (is (= :story.cp/a (:selected-variant (rf.story.ui.state/get-state)))))
  (testing "mode selection toggles the active mode"
    (rf.story.ui.command-palette.view/select-entry! {:kind :mode :id :Mode.cp/dark})
    (is (= [:Mode.cp/dark] (:active-modes (rf.story.ui.state/get-state))))))

;; ---- pure filter + grouping ---------------------------------------------

(deftest sidebar-tag-collection
  (testing "rf.story.ui.sidebar/collect-tags enumerates registered tags"
    (rf.story/reg-variant :story.tg/a {:tags #{:dev :test} :setup []})
    (rf.story/reg-variant :story.tg/b {:tags #{:dev :docs} :setup []})
    (let [vs (rf.story.registrar/registrations :variant)]
      (is (= [:dev :docs :test]
             (rf.story.ui.sidebar/collect-tags vs))))))

;; ---- argtype inference ---------------------------------------------------

(deftest argtype-resolution-from-variant
  (testing "rf.story.ui.controls/resolve-argtypes inherits from args' value shapes"
    (rf.story/reg-variant :story.at/v
      {:args   {:label "x" :n 1 :flag true}
       :setup []})
    (let [t (rf.story.ui.controls/resolve-argtypes :story.at/v)]
      (is (= :text    (:widget (get t :label))))
      (is (= :number  (:widget (get t :n))))
      (is (= :boolean (:widget (get t :flag)))))))

(deftest argtype-control-to-widget-translation
  (testing "rf.story.ui.controls/normalize-argtype-spec translates :control → :widget"
    (is (= {:widget :text}     (rf.story.ui.controls/normalize-argtype-spec {:control :text})))
    (is (= {:widget :textarea} (rf.story.ui.controls/normalize-argtype-spec {:control :textarea})))
    (is (= {:widget :select :options [:a :b]}
           (rf.story.ui.controls/normalize-argtype-spec {:control :select :options [:a :b]}))))
  (testing "normalize-argtype-spec is idempotent on :widget-keyed specs"
    (is (= {:widget :date} (rf.story.ui.controls/normalize-argtype-spec {:widget :date}))))
  (testing "non-map specs round-trip"
    (is (= "doc" (rf.story.ui.controls/normalize-argtype-spec "doc")))
    (is (nil?    (rf.story.ui.controls/normalize-argtype-spec nil))))
  (testing "resolve-argtypes honours the spec-canonical :control key"
    ;; Per /spec/007-Stories.md §argtypes — author writes :control;
    ;; renderer dispatches on :widget. Without the translation this
    ;; would fall through to the 'unsupported widget' span.
    (rf.story/reg-variant :story.argtypes/ctrl
      {:args     {:placeholder "ok" :flavor :primary}
       :argtypes {:placeholder {:control :textarea}
                  :flavor      {:control :select :options [:primary :secondary]}}
       :setup   []})
    (let [t (rf.story.ui.controls/resolve-argtypes :story.argtypes/ctrl)]
      (is (= :textarea (:widget (get t :placeholder))))
      (is (= :select   (:widget (get t :flavor))))
      (is (= [:primary :secondary] (:options (get t :flavor))))
      (is (not (contains? (get t :placeholder) :control))
          ":control key is stripped after translation"))))

;; ---- workspace: variant cell renders the variant view -------------------
;;
;; A workspace `variant-cell` must invoke the registered view in the
;; variant's allocated frame. A cell that emitted only a label and a
;; placeholder div would render every card of a workspace such as
;; `:Workspace.counter/auto-grid` as an empty frame — title + stub, no
;; counter UI inside. This test pins it: the
;; workspace renderer for a `:grid` layout with one variant must emit
;; hiccup that references the variant id, and the cell's body must wrap
;; the registered `:component` in a `frame-provider` scoped to that
;; variant id (so the rendered view's subscribe / dispatch scope to the
;; per-variant frame, not `:rf/default`).

(deftest workspace-view-emits-variant-cells-with-frame-providers
  (testing "workspace-view renders each variant cell with a
            frame-provider wrap scoped to the variant id"
    (rf/reg-view* :rf2-zme7/view
      {}
      (fn [_args]
        [:section {:data-test "rf2-zme7-view"} "rendered"]))
    (rf.story/reg-story :story.rf2-zme7
      {:component :rf2-zme7/view
       :substrates #{:reagent}})
    (rf.story/reg-variant :story.rf2-zme7/v
      {:setup []
       :substrates #{:reagent}})
    (rf.story/reg-workspace :Workspace.rf2-zme7/g
      {:layout   :grid
       :variants [:story.rf2-zme7/v]})
    (let [tree (rf.story.ui.workspace/workspace-view :Workspace.rf2-zme7/g)]
      (is (= :section (first tree))
          "the wrap is a `<section>` landmark (a11y)")
      (is (some #(= :story.rf2-zme7/v %) (tree-seq coll? seq tree))
          "the variant id appears somewhere in the rendered tree"))
    ;; The cell is a Reagent component whose body is `variant-cell-inner`,
    ;; so the frame-provider wrap is read off that body.
    (rf/make-frame {:id :story.rf2-zme7/v})
    (let [cell     (@#'rf.story.ui.workspace/variant-cell-inner :story.rf2-zme7/v)
          provider (some #(when (and (vector? %) (= rf/frame-provider (first %))) %)
                         (tree-seq coll? seq cell))]
      (is (= {:frame :story.rf2-zme7/v} (second provider))
          "the cell wraps the view in a frame-provider scoped to the variant id"))))

(deftest workspace-view-empty-for-missing-workspace
  (testing "workspace-view renders an empty / not-registered notice for
            an unregistered workspace id rather than throwing"
    (let [tree (rf.story.ui.workspace/workspace-view :Workspace.does-not/exist)]
      (is (vector? tree))
      (is (boolean (some #(and (string? %)
                               (re-find #"not registered" %))
                         (tree-seq coll? seq tree)))))))

;; ---- workspace cell keys are variant-id-derived -------------------------
;;
;; Position-only React keys (`(str "v-" i)`) would let React reconcile the
;; prior workspace's `variant-cell` components in place when the user
;; switches to a different `:variants-grid` workspace — same layout / same
;; cell positions / same component type. The cell's `r/with-let`
;; initialiser only runs once per mount, so the NEW variant's frame would
;; never be allocated by `run-variant-with-shell-opts!`; subscribes
;; against the un-allocated frame would return nil and `@nil` would throw
;; `No protocol method IDeref.-deref defined for type null`.
;;
;; So cell keys derive from variant-id, and the workspace root carries
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

;; `find-tabs-renderer-call` (defined with the tabs helpers
;; below) ALSO extracts the capped-grid renderer's `[fn cells]` call; the
;; grid-key tests just below it use it, so forward-declare to avoid an
;; undeclared-var warning (the helper body lives with its siblings).
(declare find-tabs-renderer-call)

(deftest workspace-grid-cells-key-on-variant-id-rf2-kgn0c
  (testing ":grid layout cells use variant-id-derived React keys so
            React mounts fresh cells when a workspace swap changes the
            variant set"
    (rf.story/reg-variant :story.rf2-kgn0c.a/x {:setup []})
    (rf.story/reg-variant :story.rf2-kgn0c.a/y {:setup []})
    (rf.story/reg-variant :story.rf2-kgn0c.b/p {:setup []})
    (rf.story/reg-variant :story.rf2-kgn0c.b/q {:setup []})
    (rf.story/reg-workspace :Workspace.rf2-kgn0c.a/grid
      {:layout   :grid
       :variants [:story.rf2-kgn0c.a/x :story.rf2-kgn0c.a/y]})
    (rf.story/reg-workspace :Workspace.rf2-kgn0c.b/grid
      {:layout   :grid
       :variants [:story.rf2-kgn0c.b/p :story.rf2-kgn0c.b/q]})
    ;; The `:grid` branch mounts the capped-grid
    ;; renderer (`[capped-grid-renderer cells]`); extract + invoke its
    ;; inner fn (same `[fn cells]` shape as tabs) to get the rendered
    ;; cell tree, then walk for variant-id keys.
    (let [render  (fn [ws-id]
                    (let [{renderer :fn cells :cells args :args}
                          (find-tabs-renderer-call (rf.story.ui.workspace/workspace-view ws-id))]
                      (apply renderer cells args)))
          keys-a  (collect-cell-keys (render :Workspace.rf2-kgn0c.a/grid))
          keys-b  (collect-cell-keys (render :Workspace.rf2-kgn0c.b/grid))]
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

(deftest workspace-root-section-keys-on-workspace-id-rf2-kgn0c
  (testing "the workspace's root <section> carries a workspace-id-derived
            React key so any swap unmounts the whole subtree as a
            belt-and-braces guard alongside per-cell keys"
    (rf.story/reg-variant :story.rf2-kgn0c-root/v {:setup []})
    (rf.story/reg-workspace :Workspace.rf2-kgn0c-root/g
      {:layout   :grid
       :variants [:story.rf2-kgn0c-root/v]})
    (let [tree (rf.story.ui.workspace/workspace-view :Workspace.rf2-kgn0c-root/g)
          k    (:key (meta tree))]
      (is (string? k))
      (is (re-find #"rf2-kgn0c-root" k)
          (str "workspace-root key MUST embed the workspace id; got "
               (pr-str k))))))

;; ---- :tabs serialises rendering -----------------------------------------
;;
;; Rendering every `:tabs` variant cell simultaneously, as the grid does,
;; would collapse the interior state of views that internally hardcode a
;; frame-provider (a gallery-chrome view is the canonical example) — the
;; last-seeded variant's app-db would bleed into every other cell.
;;
;; So a dedicated `tabs-renderer` mounts ONE cell at a time. A
;; tab strip switches the active tab; only the active variant's
;; `variant-cell` appears in the rendered tree. Per-variant state
;; isolation is therefore intrinsic — distinct mounts share nothing.
;;
;; These tests pin the render shape without depending on Reagent
;; lifecycle. The workspace-view returns a hiccup tree containing the
;; tabs-renderer as a child component vector `[tabs-renderer cells]`;
;; we extract the renderer fn from that vector and invoke it directly
;; so we can walk the rendered output.

(defn- find-tabs-renderer-call
  "Walk `tree` for a hiccup vector of the shape `[fn cells-vec & args]`
  where cells-vec is a non-empty vector of cell maps. Returns
  `{:fn renderer :cells cells :args trailing-args}` or nil. The
  workspace-view mounts `:tabs` as `[tabs-renderer cells]` AND `:grid` /
  isolated `:variants-grid` as `[capped-grid-renderer cells columns]`
  (with a trailing `:columns` arg) — both
  share the `[fn cells …]` shape, so this helper extracts either
  renderer's inner call regardless of trailing args. Callers invoke
  `(apply renderer cells args)` (or `(renderer cells)` for the
  no-trailing-arg tabs case) to get the rendered hiccup tree they walk."
  [tree]
  (->> (tree-seq coll? seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (>= (count node) 2)
                      (fn? (first node))
                      (vector? (second node))
                      (seq (second node))
                      (every? map? (second node))
                      (every? #(contains? % :type) (second node)))))
       first
       (#(when % {:fn    (first %)
                  :cells (second %)
                  :args  (vec (drop 2 %))}))))

(defn- count-variant-cells-in
  "Count `[variant-cell vid]` invocations in a hiccup tree —
  `[fn namespaced-keyword]` vectors. Mirrors `collect-cell-keys`."
  [tree]
  (->> (tree-seq coll? seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (= 2 (count node))
                      (fn? (first node))
                      (keyword? (second node))
                      (some? (namespace (second node))))))
       count))

(defn- tab-buttons-in
  "Collect every tab-strip `:button` element from a hiccup tree.
  Tab buttons carry `:role \"tab\"` on their props map."
  [tree]
  (->> (tree-seq coll? seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (= :button (first node))
                      (map? (second node))
                      (= "tab" (:role (second node))))))
       vec))

(deftest tabs-renderer-mounts-only-the-selected-cell-rf2-ktnl8
  (testing "tabs-renderer mounts ONLY the active tab's variant-cell —
            simultaneous-render bleed cannot occur because
            non-active cells are not present in the rendered tree"
    (rf.story/reg-variant :story.rf2-ktnl8.only/a {:setup []})
    (rf.story/reg-variant :story.rf2-ktnl8.only/b {:setup []})
    (rf.story/reg-variant :story.rf2-ktnl8.only/c {:setup []})
    (rf.story/reg-workspace :Workspace.rf2-ktnl8.only/t
      {:layout   :tabs
       :variants [:story.rf2-ktnl8.only/a
                  :story.rf2-ktnl8.only/b
                  :story.rf2-ktnl8.only/c]})
    (let [{:keys [fn cells]} (find-tabs-renderer-call
                               (rf.story.ui.workspace/workspace-view
                                 :Workspace.rf2-ktnl8.only/t))
          rendered            (fn cells)
          n                   (count-variant-cells-in rendered)]
      (is (= 1 n)
          (str "exactly ONE variant-cell MUST appear in the tabs-"
               "renderer's output (got " n "). Multiple cells means "
               "the renderer is rendering all variants simultaneously, "
               "so their state can bleed across cells.")))))

(deftest tabs-renderer-strip-has-one-switchable-tab-per-variant-rf2-ktnl8
  (testing "tabs-renderer renders a tab strip with one button per variant:
            labelled with the variant id, carrying an on-click that selects
            its own tab, and aria-selected reflecting the active tab (tab 0
            by default)"
    (rf.story/reg-variant :story.rf2-ktnl8.btn/a {:setup []})
    (rf.story/reg-variant :story.rf2-ktnl8.btn/b {:setup []})
    (rf.story/reg-variant :story.rf2-ktnl8.btn/c {:setup []})
    (rf.story/reg-workspace :Workspace.rf2-ktnl8.btn/t
      {:layout   :tabs
       :variants [:story.rf2-ktnl8.btn/a
                  :story.rf2-ktnl8.btn/b
                  :story.rf2-ktnl8.btn/c]})
    (let [{:keys [fn cells]} (find-tabs-renderer-call
                               (rf.story.ui.workspace/workspace-view
                                 :Workspace.rf2-ktnl8.btn/t))
          buttons (tab-buttons-in (fn cells))
          labels  (mapv #(nth % 2) buttons)
          props   (mapv second buttons)]
      (is (= [":story.rf2-ktnl8.btn/a"
              ":story.rf2-ktnl8.btn/b"
              ":story.rf2-ktnl8.btn/c"]
             labels)
          "one tab button per variant, labelled with its id, in cell order")
      (is (every? fn? (map :on-click props))
          "every tab button MUST carry an on-click handler")
      (is (= "true" (:aria-selected (first props)))
          "tab 0 MUST be selected by default (aria-selected=true)")
      (is (every? #(= "false" (:aria-selected %)) (rest props))
          "non-selected tabs MUST carry aria-selected=false")
      ;; Outside a React render the selection change cannot be observed
      ;; (`r/with-let` re-allocates its bindings on each non-React call),
      ;; so the handler is read through its `reset!`'s return value.
      (is (= 1 ((:on-click (second props))))
          "tab 1's on-click runs without error and selects its own index"))))

(deftest tabs-renderer-isolates-non-active-variants-rf2-ktnl8
  (testing "non-active variants MUST NOT appear in the rendered tree —
            bleed-free isolation requires that non-selected variants
            are absent, not merely hidden via CSS. (This is the load-
            bearing assertion: the bleed happens when simultaneous
            cells mount the SAME view in the SAME render tree; if only
            ONE cell mounts at a time, the bleed cannot occur.)"
    (rf.story/reg-variant :story.rf2-ktnl8.iso/a {:setup []})
    (rf.story/reg-variant :story.rf2-ktnl8.iso/b {:setup []})
    (rf.story/reg-workspace :Workspace.rf2-ktnl8.iso/t
      {:layout   :tabs
       :variants [:story.rf2-ktnl8.iso/a
                  :story.rf2-ktnl8.iso/b]})
    (let [{:keys [fn cells]} (find-tabs-renderer-call
                               (rf.story.ui.workspace/workspace-view
                                 :Workspace.rf2-ktnl8.iso/t))
          rendered  (fn cells)
          ;; Use a hash-set of namespaced keywords found anywhere in
          ;; the tree. The active variant-cell is mounted as
          ;; `[variant-cell :story.rf2-ktnl8.iso/a]`; its keyword
          ;; therefore appears in the flattened tree. The non-active
          ;; variant's keyword must NOT appear (no cell mounted).
          kws       (->> (tree-seq coll? seq rendered)
                         (filter keyword?)
                         set)]
      (is (contains? kws :story.rf2-ktnl8.iso/a)
          "the default-active variant's id MUST appear in the tree")
      (is (not (contains? kws :story.rf2-ktnl8.iso/b))
          (str "the non-active variant's id MUST NOT appear — its "
               "cell is not mounted. Found keywords: "
               (pr-str (filter #(re-find #"rf2-ktnl8" (str %)) kws)))))))

;; ---- :variants-grid :isolation :shared ----------------------------------

(deftest variants-grid-shared-isolation-mounts-one-cell-behind-a-navigator
  (testing ":isolation :shared mounts ONE cell at a time behind a prev/next
            navigator, where the default :isolated grid mounts every cell"
    (rf.story/reg-variant :story.shared-iso/a {:setup []})
    (rf.story/reg-variant :story.shared-iso/b {:setup []})
    (rf.story/reg-variant :story.shared-iso/c {:setup []})
    (rf.story/reg-workspace :Workspace.shared-iso/all
      {:layout    :variants-grid
       :isolation :shared})
    (let [{renderer :fn cells :cells args :args}
          (find-tabs-renderer-call
            (rf.story.ui.workspace/workspace-view :Workspace.shared-iso/all))
          rendered  (apply renderer cells args)
          positions (->> (tree-seq coll? seq rendered)
                         (filter #(and (vector? %) (map? (second %))))
                         (keep #(:data-test-shared-position (second %))))]
      (is (= 3 (count cells))
          "precondition: the anchor story enumerates three variants")
      (is (= 1 (count-variant-cells-in rendered))
          "exactly one variant-cell is mounted")
      (is (= ["1/3"] positions)
          "the navigator shows the first of three cells"))))

;; ---- workspace :columns grid template -----------------------------------
;;
;; The workspace body's `:columns` slot pins the CSS grid's column count:
;; the capped-grid renderer emits `repeat(N, minmax(0, 1fr))` when
;; `:columns` is present and keeps the
;; `repeat(auto-fit, minmax(280px, 1fr))` default when absent.
;;
;; These tests walk the rendered grid div's inline `grid-template-columns`
;; style — the load-bearing render output an author observes.

(defn- grid-div-style
  "Find the workspace grid container div in `tree` and return its inline
  style map. The grid div carries `:data-test-grid-columns`."
  [tree]
  (->> (tree-seq coll? seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (= :div (first node))
                      (map? (second node))
                      (contains? (second node) :data-test-grid-columns))))
       first
       second
       :style))

(defn- render-grid-tree
  "Mount `ws-id`'s workspace-view, extract the capped-grid renderer call,
  and invoke it to get the rendered hiccup tree."
  [ws-id]
  (let [{renderer :fn cells :cells args :args}
        (find-tabs-renderer-call (rf.story.ui.workspace/workspace-view ws-id))]
    (apply renderer cells args)))

(deftest workspace-grid-without-columns-keeps-auto-fit-rf2-ugmrg
  (testing ":grid without :columns keeps the responsive auto-fit default
            (:columns is opt-in)"
    (rf.story/reg-variant :story.ugmrg-auto/a {:setup []})
    (rf.story/reg-variant :story.ugmrg-auto/b {:setup []})
    (rf.story/reg-workspace :Workspace.ugmrg-auto/grid
      {:layout   :grid
       :variants [:story.ugmrg-auto/a
                  :story.ugmrg-auto/b]})
    (let [style (grid-div-style
                  (render-grid-tree :Workspace.ugmrg-auto/grid))]
      (is (= "repeat(auto-fit, minmax(280px, 1fr))"
             (:grid-template-columns style))
          (str "absent :columns MUST keep the auto-fit default; got "
               (pr-str (:grid-template-columns style)))))))

(deftest workspace-variants-grid-columns-pins-fixed-template-rf2-ugmrg
  (testing "isolated :variants-grid honours :columns — the same capped-grid
            branch `:grid` takes, so this row pins both layouts"
    (rf.story/reg-variant :story.ugmrg-vg/a {:setup []})
    (rf.story/reg-variant :story.ugmrg-vg/b {:setup []})
    (rf.story/reg-workspace :Workspace.ugmrg-vg/all
      {:layout  :variants-grid
       :for     :story.ugmrg-vg
       :columns 2})
    (let [tree  (render-grid-tree :Workspace.ugmrg-vg/all)
          style (grid-div-style tree)]
      (is (= "repeat(2, minmax(0, 1fr))"
             (:grid-template-columns style))
          ":columns 2 MUST pin a 2-column variants-grid template")
      ;; also pins that :for drove the enumeration (2 variants rendered)
      (is (= 2 (count-variant-cells-in tree))
          ":for anchor MUST enumerate both variants"))))

;; ---- workspace cells re-run on full run-key -----------------------------
;;
;; The workspace `variant-cell` keys its re-run trigger on the canvas's
;; shared public `run-key` — the FULL tuple
;; `{:variant-id :hot-reload-tick :active-modes :cell-overrides
;;   :substrate}`, the same shape canvas's `run-if-needed!` uses. A cell
;; that re-ran `run-variant-with-shell-opts!` ONLY when `:hot-reload-tick`
;; advanced would, in `:variants-grid` / `:grid` workspaces, let a control
;; edit write through to `:cell-overrides` without re-seeding the cell's
;; frame, so the cell would keep rendering against its original
;; `:setup`-seeded app-db; chrome-level `:active-modes` toggles and
;; substrate flips would hit the same hazard. The test below pins both
;; halves with one whole-value check: the key carries every watched slot,
;; so each of those transitions flips it, AND it carries nothing else, so
;; ordinary intra-cell renders (app-db updates that DON'T touch the
;; run-key slice) skip the re-run and user interactions are not clobbered.

(deftest run-key-is-exactly-the-five-re-run-slots-rf2-c56hr
  (testing "rf.story.ui.canvas/run-key projects the variant id plus the
            four shell slots a re-run watches, and nothing else: a change
            to :cell-overrides, :active-modes, :substrate or
            :hot-reload-tick flips the key (a :hot-reload-tick-only key
            would miss the first three), while an unrelated slot leaves
            it equal"
    (let [vid   :story.rf2-c56hr/v
          shell {:hot-reload-tick      1
                 :active-modes         [:Mode.x/dark]
                 :cell-overrides       {vid {:label "edited"}}
                 :substrate            :uix
                 :other-unrelated-slot "anything"}]
      (is (= {:variant-id      vid
              :hot-reload-tick 1
              :active-modes    [:Mode.x/dark]
              :cell-overrides  {:label "edited"}
              :substrate       :uix}
             (rf.story.ui.canvas/run-key shell vid))))))

;; ---- controls repeater stable React keys --------------------------------
;;
;; `rf.story.ui.controls/repeater-widget` keys each row on a stable id,
;; not positionally. With positional keys (`^{:key i}`) deleting a
;; middle entry would shift every surviving row's key up by one — React
;; would reuse the original DOM node at each position with the next
;; entry's value, so an input that had focus / cursor at index i+1 would
;; display index i's value with the SAME focus. For `:set`-kind
;; repeaters `vector-coerce` re-sorts on every render, so that would
;; fire on every keystroke.
;;
;; The shell-state carries a parallel
;; `[id0 id1 ...]` vector at `[:rf.story/repeater-row-ids
;; [variant-id path]]` synced in lockstep with the entries vector.
;; `repeater-widget` keys each row on `(str "r:" id)`; add appends a
;; fresh id, delete drops the id at position i. Surviving rows retain
;; their original id → React reconciles them in place across a
;; mid-list delete → focus + cursor are preserved.
;;
;; The namespacing-prefix discipline (`r:` for repeater, `t:` for tuple,
;; `v:` for variant cells) is consistent across the Story UI's React
;; keys.

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
            namespaced with the `r:` prefix, consistent with the Story
            UI's other React keys."
    (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                       :story.c8kfy/v [:items] ["a" "b" "c"])
    (let [tree (rf.story.ui.controls/arg-widget
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
  (testing "The regression pinned: after deleting the
            middle row of a 4-row repeater, the surviving rows MUST
            carry the SAME React keys they had pre-delete. Position
            shifts by one — identity does not. React then reconciles
            the surviving inputs in place and focus / cursor are
            preserved (rather than leaking from row [i+1] onto row [i]
            with the old DOM node)."
    ;; Initial render against a 4-entry repeater.
    (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                       :story.c8kfy/v [:items] ["a" "b" "c" "d"])
    (let [tree-before (rf.story.ui.controls/arg-widget
                        :story.c8kfy/v [:items]
                        ["a" "b" "c" "d"]
                        {:widget :repeater :kind :vector
                         :element {:widget :text}})
          keys-before (collect-repeater-row-keys tree-before)]
      (is (= 4 (count keys-before)))
      ;; Simulate the user clicking [-] on row index 1 (the second
      ;; entry). The widget calls `remove-repeater-row-id` then
      ;; updates the entries vector via `on-change-at-path`.
      (rf.story.ui.state/swap-state! rf.story.ui.state/remove-repeater-row-id
                         :story.c8kfy/v [:items] 1)
      (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                         :story.c8kfy/v [:items] ["a" "c" "d"])
      (let [tree-after (rf.story.ui.controls/arg-widget
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
    (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                       :story.c8kfy.add/v [:items] ["a" "b"])
    (let [tree-before (rf.story.ui.controls/arg-widget
                        :story.c8kfy.add/v [:items]
                        ["a" "b"]
                        {:widget :repeater :kind :vector
                         :element {:widget :text}})
          keys-before (collect-repeater-row-keys tree-before)]
      (is (= 2 (count keys-before)))
      ;; Simulate [+]: append id + extend entries vector.
      (rf.story.ui.state/swap-state! rf.story.ui.state/append-repeater-row-id
                         :story.c8kfy.add/v [:items])
      (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                         :story.c8kfy.add/v [:items] ["a" "b" ""])
      (let [tree-after (rf.story.ui.controls/arg-widget
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
            `vector-coerce`, which would shuffle positional keys against
            values on every keystroke. The row keys are
            tied to the stored id vector — NOT to the visible sort
            order — so the keys are stable across edits regardless of
            re-sort."
    ;; First render syncs 3 ids for a 3-element set.
    (let [tree-1 (rf.story.ui.controls/arg-widget
                   :story.c8kfy.set/v [:tags]
                   #{"alpha" "beta" "gamma"}
                   {:widget :repeater :kind :set
                    :element {:widget :text}})
          keys-1 (collect-repeater-row-keys tree-1)
          ;; Second render against the same set — keys MUST match
          ;; exactly (same count, same ids).
          tree-2 (rf.story.ui.controls/arg-widget
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
            discipline-consistency with the repeater's and the other
            Story UI React keys.
            (Tuple positions don't reshuffle — the focus-leak class
            doesn't fire — but uniform key shape across the file pins
            the convention.)"
    (let [tree (rf.story.ui.controls/arg-widget
                 :story.c8kfy.tup/v [:pair]
                 ["x" 42]
                 {:widget :tuple :kind :tuple
                  :positions [{:widget :text} {:widget :number}]})
          keys (collect-repeater-row-keys tree)]
      (is (= 2 (count keys)))
      (is (= ["t:0" "t:1"] keys)
          (str "tuple row keys MUST be `t:<i>`; got " (pr-str keys))))))

;; ---- privacy: retroactive scrub on egress-profile narrowing
;;
;; Per Spec 009 §Privacy §Retroactive-scrub (EP-0015): narrowing
;; the local-render egress profile from a sensitive-revealing boundary
;; (`:rf.egress/local-raw`) back to the redacting default MUST clear every
;; per-variant trace buffer. The Story trace listener only gates at
;; ingest time, so without this scrub a sensitive cascade buffered
;; while the raw profile was active would remain visible in every variant's
;; downstream consumer of the per-variant buffer after the user expected privacy to be
;; restored. The trade-off (non-sensitive history also lost) is the
;; simplest correct semantic — see Spec 009 for the rationale.

(deftest narrowing-profile-clears-every-variant-buffer-rf2-lqmje
  (testing "reveal → redact narrowing clears every per-variant Story trace buffer"
    (let [v-a       :story.priv-scrub/a
          v-b       :story.priv-scrub/b
          buf-a     (rf.story.ui.trace-buffer/ensure-buffer! v-a)
          buf-b     (rf.story.ui.trace-buffer/ensure-buffer! v-b)
          mk-ev     (fn [vid sensitive?]
                      (cond-> {:op-type   :rf.event
                               :operation :rf.event/dispatched
                               :id        1
                               :time      1700000000000
                               :tags      {:rf.trace/dispatch-id 1
                                           :frame                vid
                                           :rf.trace/event-id    :foo
                                           :rf.event/v           [:foo]}}
                        sensitive? (assoc :sensitive? true)))]
      (try
        ;; Engineer opts into the trusted-local raw boundary to investigate.
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        ;; Simulate the per-variant listener body: under the raw profile the
        ;; listener appends every event (no suppression).
        (reset! buf-a [(mk-ev v-a true) (mk-ev v-a false)])
        (reset! buf-b [(mk-ev v-b true)])
        (rf.story.config/note-suppressed! v-a) ; previously bumped before opt-in
        (is (= 2 (count @buf-a)))
        (is (= 1 (count @buf-b)))
        (is (pos? (rf.story.config/suppressed-count v-a)))

        ;; Engineer narrows the profile back expecting privacy restored.
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted)

        (is (= 0 (count @buf-a))
            "variant A's buffer must be empty — sensitive payloads cannot survive the narrowing")
        (is (= 0 (count @buf-b))
            "variant B's buffer must be empty too — the clear is global")
        (is (zero? (rf.story.config/suppressed-count v-a))
            "per-variant suppressed counter drops in lockstep with the buffer")
        (finally
          (rf.story.ui.trace-buffer/drop-buffer! v-a)
          (rf.story.ui.trace-buffer/drop-buffer! v-b)
          (rf.story.config/set-egress-profile! :rf.egress/local-redacted)
          (rf.story.config/reset-suppressed-count!))))))

(deftest redact-to-redact-and-widening-leave-the-buffers-alone-rf2-lqmje
  ;; The profile starts redacting, so no sensitive event ever landed: only
  ;; a narrowing FROM the raw profile has anything to scrub.
  (doseq [[transition profile] [["redundant redact → redact" :rf.egress/local-redacted]
                                ["widening redact → reveal"  :rf.egress/local-raw]]]
    (testing (str transition " keeps the buffered non-sensitive history")
      (let [vid :story.priv-scrub/kept
            buf (rf.story.ui.trace-buffer/ensure-buffer! vid)]
        (try
          (reset! buf [{:op-type :rf.event :tags {:frame vid}}])
          (rf.story.config/set-egress-profile! profile)
          (is (= 1 (count @buf))
              (str transition " must not clear the buffer"))
          (finally
            (rf.story.ui.trace-buffer/drop-buffer! vid)
            (rf.story.config/set-egress-profile! :rf.egress/local-redacted)))))))

;; ---- :test mode ---------------------------------------------------------

(deftest test-view-empty-state-without-play
  (testing "test-view renders the empty-state placeholder when the
            variant body has no :script slot — the variant is registered
            but declares zero assertions, so no run is fired."
    (rf.story/reg-variant :story.tv/no-play {:setup []})
    (rf.story/reg-variant :story.tv/with-assertions
      {:setup      []
       :assertions [[:rf.assert/path-equals [:x] 1]]})
    (let [empty-state        @#'rf.story.ui.test-mode.view/empty-state
          shows-empty-state? (fn [variant-id]
                               (boolean (some #(= [empty-state variant-id] %)
                                              (rf.story.ui.test-mode.view/test-view variant-id))))]
      (is (shows-empty-state? :story.tv/no-play)
          "the pane carries the empty-state placeholder")
      (is (not (shows-empty-state? :story.tv/with-assertions))
          "a variant with assertions gets the result sections instead"))
    (is (nil? (rf.story.ui.test-mode.view/test-view nil))
        "no variant-id = no pane")))

(deftest format-helpers-take-their-cljs-arms
  (testing "format-elapsed-ms' one-second-plus branch and
            format-timestamp-ms are reader-conditional; these rows run
            their CLJS arms (the JVM arms and the shared branches are
            pinned in re-frame.story-ui-test)"
    (is (= "1.2 s" (rf.story.ui.test-mode.pure/format-elapsed-ms 1234)))
    (is (re-matches #"\d{2}:\d{2}:\d{2}"
                    (rf.story.ui.test-mode.pure/format-timestamp-ms (.getTime (js/Date.)))))))

(deftest registry-snapshot-shape
  (testing "registry-snapshot returns every Story kind"
    (rf.story/reg-variant :story.r/v {:setup []})
    (let [snap (rf.story.ui.state/registry-snapshot)]
      (is (= #{:stories :variants :workspaces :modes :decorators
               :story-panels :tags}
             (set (keys snap))))
      (is (contains? (:variants snap) :story.r/v)))))
