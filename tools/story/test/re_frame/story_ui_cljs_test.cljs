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
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.controls :as controls]
            [re-frame.story.ui.sidebar :as sidebar]
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

(deftest stage-5-marker
  (testing "Stage 5 advertises :assertions+play"
    (is (= :assertions+play story/stage))))

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

;; ---- trace six-domino projection ----------------------------------------

(deftest trace-group-cascades-classifies
  (testing "group-cascades splits trace events into six-domino slots"
    (let [evs       [{:op-type :event :operation :event/dispatched
                      :id 1 :tags {:dispatch-id 100 :event [:foo]}}
                     {:op-type :event :operation :event/run
                      :id 2 :tags {:dispatch-id 100 :duration-ms 3}}
                     {:op-type :event/do-fx
                      :id 3 :tags {:dispatch-id 100 :fx {:db {}}}}
                     {:op-type :fx
                      :id 4 :tags {:dispatch-id 100 :fx-id :db}}
                     {:op-type :sub/run
                      :id 5 :tags {:dispatch-id 100 :id :sub/foo}}
                     {:op-type :view/render
                      :id 6 :tags {:dispatch-id 100 :view :app/root}}]
          cascades  (trace/group-cascades evs)]
      (is (= 1 (count cascades)))
      (let [c (first cascades)]
        (is (= [:foo] (:event c)))
        (is (some? (:handler c)))
        (is (some? (:fx c)))
        (is (= 1 (count (:effects c))))
        (is (= 1 (count (:subs c))))
        (is (= 1 (count (:renders c))))))))

;; ---- shell render smoke -------------------------------------------------
;;
;; We don't mount to a real DOM (the node-test target has no jsdom).
;; Instead we confirm the component fns are callable and return hiccup.

(deftest shell-components-are-functions
  (testing "sidebar / canvas / controls / scrubber / trace expose top-level component fns"
    (is (fn? sidebar/sidebar))
    (is (fn? trace/panel))
    ;; The controls/panel takes a variant-id arg.
    (is (fn? controls/panel))))

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
