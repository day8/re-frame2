(ns re-frame.story.ui.docs-mode-pane-cljs-test
  "CLJS coverage of the `:docs` mode pane scenarios spec/015 §`:docs` mode
  pane cites: each section's pure-data projection under a docs-rich registry
  fixture (the renderer is a 1:1 mapping from these rows onto hiccup), the
  tag-chip forward-link, and the read-only contract. The JVM covers the same
  helpers in `re-frame.story-ui-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.predicates :as rf.story.predicates]
            [re-frame.story.ui.docs    :as rf.story.ui.docs]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.story.ui.state.transitions :as rf.story.ui.state.transitions]
            [re-frame.subs             :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  ;; EP-0001: a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>] — mirror `re-frame.machines`.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(defn- register-rich-variant!
  "A parent story carrying :doc / :argtypes / :tags / :modes / :substrates,
  a variant with its own :doc and a :hiccup + :fx-override decorator, and
  one :prose workspace referencing the variant."
  []
  (rf.story/reg-decorator :hiccup-wrap
    {:kind :hiccup
     :doc  "wraps in a centred pane"
     :wrap (fn [body _] [:div.centred body])})
  (rf.story/reg-story :story.docs-rich
    {:doc       "Rich variant fixture for docs-pane scenarios."
     :argtypes  {:label {:doc "user-visible label"}
                 :n     {:doc "tick count"}}
     :tags      #{:dev :docs}
     :modes     #{:Mode.app/dark}
     :substrates #{:reagent}})
  (rf.story/reg-variant :story.docs-rich/v
    {:doc        "The 'happy path' variant — used by docs scenarios."
     :args       {:label "Hello" :n 42}
     :decorators [[:hiccup-wrap]
                  [:rf.story/force-fx-stub :http {:status :ok}]]
     :tags       #{:dev :docs :test}
     :setup     []})
  (rf.story/reg-mode :Mode.app/dark {:args {:theme :dark}})
  (rf.story/reg-workspace :Workspace.docs-rich/prose-ws
    {:layout  :prose
     :content [{:type :variant :id :story.docs-rich/v}
               {:type :prose
                :body "This variant exists for docs-pane regression tests."}
               {:type :prose
                :body "It carries decorators, modes, and rich argtypes."}]}))

;; ===========================================================================
;; Section rendering
;; ===========================================================================

(deftest header-data-projects-from-registry
  (testing "the header reads parent-story + sorted tags + doc-blurb out of the registry"
    (register-rich-variant!)
    (is (= :story.docs-rich
           (rf.story.predicates/parent-story-id :story.docs-rich/v)))
    (is (= [:dev :docs :test] (rf.story.ui.docs/variant-tags :story.docs-rich/v)))
    (is (string? (:doc (rf.story/handler-meta :variant :story.docs-rich/v))))))

(deftest prose-section-pulls-from-prose-workspaces
  (testing "prose-for-variant returns each :prose item of a workspace that
            references the variant, in order, naming its source workspace"
    (register-rich-variant!)
    (is (= [{:workspace-id :Workspace.docs-rich/prose-ws
             :body "This variant exists for docs-pane regression tests."}
            {:workspace-id :Workspace.docs-rich/prose-ws
             :body "It carries decorators, modes, and rich argtypes."}]
           (rf.story.ui.docs/prose-for-variant :story.docs-rich/v)))))

(deftest prose-section-omitted-when-no-prose-workspace
  (testing "a variant no :prose workspace references gets an empty prose
            result, so the renderer omits the section"
    (rf.story/reg-variant :story.docs.no-prose/v
      {:doc "no-prose variant" :setup []})
    (is (= [] (rf.story.ui.docs/prose-for-variant :story.docs.no-prose/v)))))

(deftest args-section-renders-key-default-doc-columns
  (testing "args-rows produces a key / value / doc row per resolved arg (other
            rows, e.g. host global args, may sit beside them)"
    (register-rich-variant!)
    (is (= [{:key :label :value "Hello" :doc "user-visible label"}
            {:key :n :value 42 :doc "tick count"}]
           (filterv (comp #{:label :n} :key)
                    (rf.story.ui.docs/args-rows
                      :story.docs-rich/v
                      (rf.story/resolve-args :story.docs-rich/v)))))))

(deftest decorators-section-groups-by-kind
  (testing "decorator-rows sections the resolved decorator pack by kind"
    (register-rich-variant!)
    (let [by-sect (group-by :section
                            (rf.story.ui.docs/decorator-rows
                              (rf.story/resolve-decorators :story.docs-rich/v)))]
      (is (= [{:section :hiccup :id :hiccup-wrap :doc "wraps in a centred pane"}]
             (:hiccup by-sect)))
      (is (contains? by-sect :fx-override)
          ":fx-override section present — the :rf.story/force-fx-stub")
      (is (not (contains? by-sect :error))
          "no :error rows on the happy path"))))

(deftest parameters-section-falls-back-to-parent-story
  (testing "parameter-rows falls back to the parent story's :modes /
            :substrates and omits a slot neither declares (:platforms)"
    (register-rich-variant!)
    (is (= [{:key :modes :value #{:Mode.app/dark}}
            {:key :substrates :value #{:reagent}}]
           (rf.story.ui.docs/parameter-rows :story.docs-rich/v)))))

;; ===========================================================================
;; Tag-chip forward-link — the chip's click handler applies this same
;; transition; its `aria-pressed` reads `(contains? tag-filter tag)`.
;; ===========================================================================

(deftest tag-chip-toggles-shell-tag-filter
  (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/toggle-tag-filter :dev)
  (is (= #{:dev} (:tag-filter @rf.story.ui.state/shell-state-atom)))
  (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/toggle-tag-filter :docs)
  (is (= #{:dev :docs} (:tag-filter @rf.story.ui.state/shell-state-atom))
      "the filter is multi-select")
  (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/toggle-tag-filter :dev)
  (is (= #{:docs} (:tag-filter @rf.story.ui.state/shell-state-atom))))

;; ===========================================================================
;; Read-only contract — the user's transient canvas edits survive a docs detour
;; ===========================================================================

(deftest mode-tab-switch-preserves-shell-state
  (testing "switching the mode tab :dev → :docs → :dev leaves cell-overrides,
            active-modes, tag-filter and the selected variant untouched"
    (register-rich-variant!)
    (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/set-cell-override-scalar
                                   :story.docs-rich/v :label "USER-EDIT")
    (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/set-active-modes [:Mode.app/dark])
    (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/toggle-tag-filter :dev)
    (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/select-variant :story.docs-rich/v)
    (let [slots  #(select-keys @rf.story.ui.state/shell-state-atom
                               [:cell-overrides :active-modes :tag-filter :selected-variant])
          before (slots)]
      (doseq [tab [:docs :dev]]
        (rf.story.ui.state/swap-state! rf.story.ui.state.transitions/set-active-mode-tab
                                       :story.docs-rich/v tab)
        (is (= tab (rf.story.ui.state/active-mode-tab @rf.story.ui.state/shell-state-atom
                                                      :story.docs-rich/v)))
        (is (= before (slots)))))))

(deftest docs-pane-data-projection-does-not-mutate-registry
  (testing "walking every docs projection leaves the registry unchanged"
    (register-rich-variant!)
    (let [registry #(into {} (map (juxt identity rf.story/registrations))
                          [:variant :story :workspace :decorator :mode])
          before   (registry)]
      (rf.story.ui.docs/prose-for-variant :story.docs-rich/v)
      (rf.story.ui.docs/args-rows :story.docs-rich/v
                                  (rf.story/resolve-args :story.docs-rich/v))
      (rf.story.ui.docs/decorator-rows (rf.story/resolve-decorators :story.docs-rich/v))
      (rf.story.ui.docs/parameter-rows :story.docs-rich/v)
      (rf.story.ui.docs/variant-tags :story.docs-rich/v)
      (is (= before (registry))))))
