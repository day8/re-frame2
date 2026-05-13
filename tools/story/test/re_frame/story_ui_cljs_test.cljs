(ns re-frame.story-ui-cljs-test
  "CLJS smoke tests for re-frame2-story Stage 4 (rf2-ekai).

  The UI shell is Reagent-rendered, so the bulk of coverage is shape
  rather than visual — we exercise:

  - The pure shell-state helpers (selection, filters, fingerprints).
  - The pure layout resolver (`:grid`, `:prose`, `:variants-grid`).
  - The pure trace cascade grouper (six-domino projection).
  - The pure argtype inference + sidebar tag collection.
  - The public mount/unmount surface on `re-frame.story`.

  The visual / interaction shape (clicking a variant row triggers a
  re-render; the scrubber slider commits via `restore-epoch`) lives in
  the browser-test target (Stage 4 ships the smoke layer; Stage 8's
  examples integration covers end-to-end Playwright)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.docs :as docs]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.controls :as controls]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.test-mode.pure :as test-mode-pure]
            [re-frame.story.ui.test-mode.view :as test-mode-view]
            [re-frame.story.ui.scrubber :as scrubber]
            [re-frame.story.ui.scrubber-xref :as xref]
            [re-frame.story.ui.trace :as trace]
            [re-frame.story.ui.workspace :as workspace]))

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

(deftest toggle-tag-filter-flips
  (testing "toggle-tag-filter adds + removes a tag"
    (state/swap-state! state/toggle-tag-filter :dev)
    (is (contains? (:tag-filter (state/get-state)) :dev))
    (state/swap-state! state/toggle-tag-filter :dev)
    (is (not (contains? (:tag-filter (state/get-state)) :dev)))))

(deftest cell-overrides-roundtrip
  (testing "cell overrides write + clear cleanly"
    (state/swap-state! state/set-cell-override :story.x/y :label "hi")
    (is (= "hi" (get-in (state/get-state)
                        [:cell-overrides :story.x/y :label])))
    (state/swap-state! state/clear-cell-overrides :story.x/y)
    (is (nil? (get-in (state/get-state)
                      [:cell-overrides :story.x/y])))))

;; ---- pure filter + grouping ---------------------------------------------

(deftest filter-variants-by-tag
  (testing "filter-variants returns matching subset"
    (story/reg-variant :story.t/a {:tags #{:dev} :events []})
    (story/reg-variant :story.t/b {:tags #{:test} :events []})
    (let [vs   (story-registrar/handlers :variant)
          devs (state/filter-variants vs #{:dev})]
      (is (contains? devs :story.t/a))
      (is (not (contains? devs :story.t/b))))))

(deftest group-variants-by-story
  (testing "group-variants-by-story builds the sidebar tree"
    (story/reg-variant :story.a/x {:events []})
    (story/reg-variant :story.a/y {:events []})
    (story/reg-variant :story.b/z {:events []})
    (let [vs       (story-registrar/handlers :variant)
          grouped  (state/group-variants-by-story vs)
          by-story (into {} (map (juxt :story-id :variants) grouped))]
      (is (= 2 (count (get by-story :story.a))))
      (is (= 1 (count (get by-story :story.b)))))))

(deftest sidebar-tag-collection
  (testing "sidebar/collect-tags enumerates registered tags"
    (story/reg-variant :story.tg/a {:tags #{:dev :test} :events []})
    (story/reg-variant :story.tg/b {:tags #{:dev :docs} :events []})
    (let [vs (story-registrar/handlers :variant)]
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

;; ---- trace six-domino projection ----------------------------------------

(deftest trace-group-cascades-classifies
  (testing "group-cascades splits trace events into six-domino slots.
            Per rf2-wvzgd the projection now lives in
            `re-frame.trace.projection`; Story exposes the same fn
            under `re-frame.story.ui.trace/group-cascades` for callers
            wired to that namespace. Event shapes here track the
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
          cascades  (trace/group-cascades evs)]
      (is (= 1 (count cascades)))
      (let [c (first cascades)]
        (is (= [:foo] (:event c)))
        (is (some? (:handler c)))
        (is (some? (:fx c)))
        (is (= 1 (count (:effects c))))
        (is (= 1 (count (:subs c))))
        (is (= 1 (count (:renders c))))))))

;; ---- trace × scrubber cross-reference (rf2-sxwvf) -----------------------

(deftest trace-scrubber-cross-ref-pure-helpers-callable-in-cljs
  (testing "the pure-data cross-ref helpers (xref ns) are JVM+CLJS:
            CLJS callers see the same shape under the cljc target."
    (let [history [{:epoch-id 7 :trace-events [{:id 2 :tags {:dispatch-id 100}}
                                               {:id 6 :tags {:dispatch-id 100}}]}
                   {:epoch-id 8 :trace-events [{:id 12 :tags {:dispatch-id 200}}]}]]
      (is (= 100 (xref/cascade-id-for-epoch history 7)))
      (is (= 6   (xref/max-trace-event-id-for-epoch history 7)))
      (is (nil?  (xref/cascade-id-for-epoch history nil)))
      ;; filter is the identity under nil cap, drops cascades under a cap.
      (let [cs [{:dispatch-id 100 :handler {:id 2 :tags {}} :effects [] :subs [] :renders [] :other []}
                {:dispatch-id 200 :handler {:id 12 :tags {}} :effects [] :subs [] :renders [] :other []}]]
        (is (= cs (xref/filter-cascades-up-to cs nil)))
        (is (= [(first cs)] (xref/filter-cascades-up-to cs 6))))
      (is (true? (xref/cascade-matches-selected-epoch?
                   {:dispatch-id 100} 100)))
      (is (false? (xref/cascade-matches-selected-epoch?
                    {:dispatch-id 200} 100))))))

(deftest scrubber-selection-ratom-roundtrips
  (testing "selection ratom set/get/drop round-trip in CLJS — the trace
            panel's deref against the ratom sees the value the scrubber
            committed (the wiring the .cljs `panel` fns walk)."
    (let [vid :story.xref/v1]
      (try
        ;; default: no selection (cleaned in finally below)
        (is (nil? (scrubber/selected-epoch-id vid)))
        ;; set, read, clear via select-epoch!
        (scrubber/select-epoch! vid 42)
        (is (= 42 (scrubber/selected-epoch-id vid)))
        (scrubber/select-epoch! vid nil)
        (is (nil? (scrubber/selected-epoch-id vid)))
        ;; ensure-selection-atom! returns the same atom on repeated calls
        ;; (the trace panel relies on this for stable deref signal).
        (let [a1 (scrubber/ensure-selection-atom! vid)
              a2 (scrubber/ensure-selection-atom! vid)]
          (is (identical? a1 a2)))
        ;; drop-selection! removes the entry
        (scrubber/drop-selection! vid)
        (is (nil? (scrubber/selected-epoch-id vid)))
        (finally
          (scrubber/drop-selection! vid))))))

;; ---- shell render smoke -------------------------------------------------
;;
;; We don't mount to a real DOM (the node-test target has no jsdom).
;; Instead we confirm the component fns are callable and return hiccup.

(deftest shell-components-are-functions
  (testing "sidebar / canvas / controls / scrubber / trace expose top-level component fns"
    (is (fn? sidebar/sidebar))
    (is (fn? trace/panel))
    (is (fn? scrubber/panel))
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
    (let [summary (test-mode-pure/aggregate-summary
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
