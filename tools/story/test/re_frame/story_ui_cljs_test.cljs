(ns re-frame.story-ui-cljs-test
  "CLJS tests for the re-frame2-story shell's render shape: the command
  palette, argtype resolution and the controls widget tree, the workspace
  layout renderers and their React keys, the trace-buffer privacy scrub, and
  the test-mode pane. Interaction lives in the browser-test target."
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
  (testing "normalize-argtype-spec translates the spec-canonical :control to
            the renderer's :widget, is idempotent on :widget specs, and passes
            non-map specs through"
    (is (= [{:widget :text} {:widget :textarea} {:widget :select :options [:a :b]}
            {:widget :date} "doc" nil]
           (map rf.story.ui.controls/normalize-argtype-spec
                [{:control :text} {:control :textarea} {:control :select :options [:a :b]}
                 {:widget :date} "doc" nil]))))
  (testing "resolve-argtypes honours :control (spec/007 §argtypes), stripping it
            after translation"
    (rf.story/reg-variant :story.argtypes/ctrl
      {:args     {:placeholder "ok" :flavor :primary}
       :argtypes {:placeholder {:control :textarea}
                  :flavor      {:control :select :options [:primary :secondary]}}
       :setup   []})
    (let [t (rf.story.ui.controls/resolve-argtypes :story.argtypes/ctrl)]
      (is (= [:textarea :select [:primary :secondary] false]
             [(:widget (get t :placeholder)) (:widget (get t :flavor))
              (:options (get t :flavor)) (contains? (get t :placeholder) :control)])))))

;; A workspace cell invokes the registered view inside a frame-provider
;; scoped to the variant id, so its subscribes and dispatches reach the
;; variant's frame rather than :rf/default.
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

;; Cell React keys derive from the variant id, and the workspace root from the
;; workspace id: with positional keys, switching between two workspaces of the
;; same layout would reconcile the old cells in place, `r/with-let` would not
;; re-fire, and the new variant's frame would never be allocated.

(defn- collect-cell-keys
  "The React keys on every `[variant-cell variant-id]` vector in `tree` —
  matched structurally as `[fn namespaced-keyword]`, since variant-cell is a
  private fn value."
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

;; Defined with the tabs helpers below; the grid-key tests use it first.
(declare find-tabs-renderer-call)

(deftest workspace-grid-cells-key-on-variant-id-rf2-kgn0c
  (testing ":grid cells use variant-id-derived React keys, so two workspaces
            with disjoint variant sets have disjoint cell keys"
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
    ;; `:grid` mounts `[capped-grid-renderer cells columns]`; invoke it to
    ;; get the rendered cell tree.
    (let [render  (fn [ws-id]
                    (let [{renderer :fn cells :cells args :args}
                          (find-tabs-renderer-call (rf.story.ui.workspace/workspace-view ws-id))]
                      (apply renderer cells args)))
          keys-a  (collect-cell-keys (render :Workspace.rf2-kgn0c.a/grid))
          keys-b  (collect-cell-keys (render :Workspace.rf2-kgn0c.b/grid))]
      (is (= [2 2] [(count keys-a) (count keys-b)]) "one key per cell")
      (is (empty? (set/intersection keys-a keys-b))
          (str "keys-a=" (pr-str keys-a) " keys-b=" (pr-str keys-b)))
      (is (every? #(re-find #"rf2-kgn0c" %) keys-a)
          (str "cell keys embed the variant id; got " (pr-str keys-a))))))

(deftest workspace-root-section-keys-on-workspace-id-rf2-kgn0c
  (testing "the workspace's root <section> carries a workspace-id-derived key,
            so any swap unmounts the whole subtree"
    (rf.story/reg-variant :story.rf2-kgn0c-root/v {:setup []})
    (rf.story/reg-workspace :Workspace.rf2-kgn0c-root/g
      {:layout   :grid
       :variants [:story.rf2-kgn0c-root/v]})
    (let [k (:key (meta (rf.story.ui.workspace/workspace-view :Workspace.rf2-kgn0c-root/g)))]
      (is (and (string? k) (re-find #"rf2-kgn0c-root" k))
          (str "workspace-root key embeds the workspace id; got " (pr-str k))))))

;; `:tabs` mounts one cell at a time: rendering every cell at once, as the grid
;; does, would let views that hardcode a frame-provider bleed the last-seeded
;; app-db into every cell. The workspace-view tree holds the renderer as
;; `[tabs-renderer cells]`; the tests invoke it directly and walk the output.

(defn- find-tabs-renderer-call
  "The first `[fn cells & args]` vector in `tree` whose cells are a non-empty
  vector of cell maps, as `{:fn :cells :args}`. Both `[tabs-renderer cells]`
  and `[capped-grid-renderer cells columns]` take this shape."
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
  "How many `[variant-cell vid]` vectors `tree` holds."
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
  "Every tab-strip `:button` (`:role \"tab\"`) in `tree`."
  [tree]
  (->> (tree-seq coll? seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (= :button (first node))
                      (map? (second node))
                      (= "tab" (:role (second node))))))
       vec))

(deftest tabs-renderer-mounts-only-the-selected-cell-rf2-ktnl8
  (testing "tabs-renderer mounts only the active tab's variant-cell"
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
                                 :Workspace.rf2-ktnl8.only/t))]
      (is (= 1 (count-variant-cells-in (fn cells)))))))

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
  (testing "a non-active variant is absent from the rendered tree, not merely
            hidden: only one cell mounts, so nothing can bleed"
    (rf.story/reg-variant :story.rf2-ktnl8.iso/a {:setup []})
    (rf.story/reg-variant :story.rf2-ktnl8.iso/b {:setup []})
    (rf.story/reg-workspace :Workspace.rf2-ktnl8.iso/t
      {:layout   :tabs
       :variants [:story.rf2-ktnl8.iso/a
                  :story.rf2-ktnl8.iso/b]})
    (let [{:keys [fn cells]} (find-tabs-renderer-call
                               (rf.story.ui.workspace/workspace-view
                                 :Workspace.rf2-ktnl8.iso/t))
          kws (set (filter keyword? (tree-seq coll? seq (fn cells))))]
      (is (= [true false]
             [(contains? kws :story.rf2-ktnl8.iso/a) (contains? kws :story.rf2-ktnl8.iso/b)])))))

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

;; A workspace's `:columns` pins the grid to `repeat(N, minmax(0, 1fr))`;
;; without it the grid keeps `repeat(auto-fit, minmax(280px, 1fr))`. The tests
;; read the rendered grid div's inline style.

(defn- grid-div-style
  "The inline style of the grid container div (`:data-test-grid-columns`) in `tree`."
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
  (rf.story/reg-variant :story.ugmrg-auto/a {:setup []})
  (rf.story/reg-variant :story.ugmrg-auto/b {:setup []})
  (rf.story/reg-workspace :Workspace.ugmrg-auto/grid
    {:layout   :grid
     :variants [:story.ugmrg-auto/a
                :story.ugmrg-auto/b]})
  (is (= "repeat(auto-fit, minmax(280px, 1fr))"
         (:grid-template-columns (grid-div-style (render-grid-tree :Workspace.ugmrg-auto/grid))))
      ":columns is opt-in"))

(deftest workspace-variants-grid-columns-pins-fixed-template-rf2-ugmrg
  (testing "isolated :variants-grid honours :columns — the capped-grid branch
            :grid takes too — and its :for anchor enumerates both variants"
    (rf.story/reg-variant :story.ugmrg-vg/a {:setup []})
    (rf.story/reg-variant :story.ugmrg-vg/b {:setup []})
    (rf.story/reg-workspace :Workspace.ugmrg-vg/all
      {:layout  :variants-grid
       :for     :story.ugmrg-vg
       :columns 2})
    (let [tree (render-grid-tree :Workspace.ugmrg-vg/all)]
      (is (= ["repeat(2, minmax(0, 1fr))" 2]
             [(:grid-template-columns (grid-div-style tree)) (count-variant-cells-in tree)])))))

;; A workspace cell re-runs its variant when the canvas's shared `run-key`
;; changes. A key watching only :hot-reload-tick would let a control edit,
;; mode toggle or substrate flip leave the cell rendering its original
;; app-db; a key watching more would clobber ordinary intra-cell renders.
(deftest run-key-is-exactly-the-five-re-run-slots-rf2-c56hr
  (let [vid :story.rf2-c56hr/v]
    (is (= {:variant-id      vid
            :hot-reload-tick 1
            :active-modes    [:Mode.x/dark]
            :cell-overrides  {:label "edited"}
            :substrate       :uix}
           (rf.story.ui.canvas/run-key {:hot-reload-tick      1
                                        :active-modes         [:Mode.x/dark]
                                        :cell-overrides       {vid {:label "edited"}}
                                        :substrate            :uix
                                        :other-unrelated-slot "anything"}
                                       vid)))))

;; The controls repeater keys each row on a stable id from the shell state's
;; `[:rf.story/repeater-row-ids [variant-id path]]` (`r:<id>`), not its
;; position: with positional keys a mid-list delete would hand a surviving
;; row's DOM node, focus and cursor to its neighbour, and a :set repeater
;; re-sorts on every keystroke. Tuple rows key `t:<i>`, since their arity is
;; fixed.

(defn- expand-hiccup
  "Materialize a hiccup form by invoking every vector whose head is a fn, as
  Reagent does at render time, keeping each vector's `^{:key ...}` meta."
  [tree]
  (cond
    (vector? tree)
    (if (fn? (first tree))
      (let [expanded (apply (first tree) (rest tree))
            meta'    (meta tree)]
        (with-meta (expand-hiccup expanded) (or (meta expanded) meta')))
      (with-meta (mapv expand-hiccup tree) (meta tree)))

    (seq? tree)
    (map expand-hiccup tree)

    :else
    tree))

(defn- collect-repeater-row-keys
  "The React keys (from `^{:key ...}` meta, as React sees them) of every
  `:data-controls-row-key` row div in the materialized `tree`."
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
  (testing "after deleting the middle row of a 4-row repeater the survivors
            keep their pre-delete React keys, so React reconciles them in place"
    (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                       :story.c8kfy/v [:items] ["a" "b" "c" "d"])
    (let [render      #(collect-repeater-row-keys
                         (rf.story.ui.controls/arg-widget
                           :story.c8kfy/v [:items] %
                           {:widget :repeater :kind :vector :element {:widget :text}}))
          keys-before (render ["a" "b" "c" "d"])]
      ;; The [-] on row 1: drop its id, then update the entries.
      (rf.story.ui.state/swap-state! rf.story.ui.state/remove-repeater-row-id
                         :story.c8kfy/v [:items] 1)
      (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                         :story.c8kfy/v [:items] ["a" "c" "d"])
      (is (= 4 (count keys-before)))
      (is (= [(nth keys-before 0) (nth keys-before 2) (nth keys-before 3)]
             (render ["a" "c" "d"]))
          (str "before=" (pr-str keys-before))))))

(deftest controls-repeater-add-allocates-fresh-id-rf2-c8kfy
  (testing "an appended row carries a fresh key, so React mounts a fresh DOM node"
    (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                       :story.c8kfy.add/v [:items] ["a" "b"])
    (let [render      #(collect-repeater-row-keys
                         (rf.story.ui.controls/arg-widget
                           :story.c8kfy.add/v [:items] %
                           {:widget :repeater :kind :vector :element {:widget :text}}))
          keys-before (render ["a" "b"])]
      ;; The [+]: append an id, then extend the entries.
      (rf.story.ui.state/swap-state! rf.story.ui.state/append-repeater-row-id
                         :story.c8kfy.add/v [:items])
      (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override
                         :story.c8kfy.add/v [:items] ["a" "b" ""])
      (let [keys-after (render ["a" "b" ""])]
        (is (= [2 3] [(count keys-before) (count keys-after)]))
        (is (= keys-before (subvec keys-after 0 2)))
        (is (not (contains? (set keys-before) (nth keys-after 2))))))))

(deftest controls-repeater-set-edit-preserves-keys-rf2-c8kfy
  (testing "a :set repeater's keys follow the stored id vector, not the visible
            sort order, so they are stable across re-renders"
    (let [render #(collect-repeater-row-keys
                    (rf.story.ui.controls/arg-widget
                      :story.c8kfy.set/v [:tags]
                      #{"alpha" "beta" "gamma"}
                      {:widget :repeater :kind :set :element {:widget :text}}))
          keys-1 (render)]
      (is (= 3 (count keys-1)))
      (is (= keys-1 (render))))))

(deftest controls-tuple-rows-key-on-positional-prefix-rf2-c8kfy
  (is (= ["t:0" "t:1"]
         (collect-repeater-row-keys
           (rf.story.ui.controls/arg-widget
             :story.c8kfy.tup/v [:pair]
             ["x" 42]
             {:widget :tuple :kind :tuple
              :positions [{:widget :text} {:widget :number}]})))))

;; Spec 009 §Privacy §Retroactive-scrub: the trace listener gates only at
;; ingest, so narrowing the egress profile from :rf.egress/local-raw back to
;; redacting clears every per-variant trace buffer — or a sensitive cascade
;; buffered under the raw profile would stay visible.
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
        ;; Under the raw profile the listener appends every event.
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        (reset! buf-a [(mk-ev v-a true) (mk-ev v-a false)])
        (reset! buf-b [(mk-ev v-b true)])
        (rf.story.config/note-suppressed! v-a)
        (is (= [2 1 true] [(count @buf-a) (count @buf-b) (pos? (rf.story.config/suppressed-count v-a))])
            "precondition")
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted)
        (is (= [0 0 0] [(count @buf-a) (count @buf-b) (rf.story.config/suppressed-count v-a)])
            "both buffers and the suppressed counter clear — the clear is global")
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
  (testing "test-view shows the empty-state placeholder for a variant with no
            tests, the result sections for one with assertions, and nothing
            without a variant"
    (rf.story/reg-variant :story.tv/no-play {:setup []})
    (rf.story/reg-variant :story.tv/with-assertions
      {:setup      []
       :assertions [[:rf.assert/path-equals [:x] 1]]})
    (let [empty-state        @#'rf.story.ui.test-mode.view/empty-state
          shows-empty-state? (fn [variant-id]
                               (boolean (some #(= [empty-state variant-id] %)
                                              (rf.story.ui.test-mode.view/test-view variant-id))))]
      (is (= [true false] (map shows-empty-state? [:story.tv/no-play :story.tv/with-assertions]))))
    (is (nil? (rf.story.ui.test-mode.view/test-view nil)))))

(deftest format-helpers-take-their-cljs-arms
  (testing "format-elapsed-ms' one-second-plus branch and
            format-timestamp-ms are reader-conditional; these rows run
            their CLJS arms (the JVM arms and the shared branches are
            pinned in re-frame.story-ui-test)"
    (is (= "1.2 s" (rf.story.ui.test-mode.pure/format-elapsed-ms 1234)))
    (is (re-matches #"\d{2}:\d{2}:\d{2}"
                    (rf.story.ui.test-mode.pure/format-timestamp-ms (.getTime (js/Date.)))))))

(deftest registry-snapshot-shape
  (rf.story/reg-variant :story.r/v {:setup []})
  (let [snap (rf.story.ui.state/registry-snapshot)]
    (is (= #{:stories :variants :workspaces :modes :decorators :story-panels :tags}
           (set (keys snap))))
    (is (contains? (:variants snap) :story.r/v))))
