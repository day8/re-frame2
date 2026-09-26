(ns re-frame.story.panels-e2e.sidebar-default-filter-e2e-cljs-test
  "The sidebar honours `reg-tag`'s `:default-filter :exclude`: a variant
  carrying such a tag is absent from the rendered tree until the user
  toggles that tag on in the tag filter.

  Rendered at the hiccup level, as `sidebar-search-e2e-cljs-test` does:
  call the sidebar's inner render fn and read the variant rows. No DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- register-variants! []
  (rf.story/reg-tag :internal {:doc "Internal-only." :default-filter :exclude})
  (rf.story/reg-tag :shipped {:doc "Shipped."})
  (rf.story/reg-story :story.dfilter {:doc "Default-filter fixture."})
  (rf.story/reg-variant :story.dfilter/public {:tags #{:shipped} :setup []})
  (rf.story/reg-variant :story.dfilter/private {:tags #{:internal} :setup []}))

(defn- visible-variant-ids []
  (let [tree ((rf.story.ui.sidebar/sidebar) nil)]
    (->> (rf.story.test-helpers.e2e-multi-frame/find-all-by-test-id
           tree "story-sidebar-variant-row")
         (keep (fn [n] (get-in n [1 :data-variant])))
         set)))

(deftest default-excluded-tag-hides-its-variants-until-toggled-on
  (testing "a variant whose tag registers `:default-filter :exclude` is
            hidden at boot and shows once that tag is toggled on"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variants!}
      (fn []
        (is (= #{":story.dfilter/public"} (visible-variant-ids))
            "the :internal variant is hidden while the filter is empty")
        (rf.story.ui.state/swap-state! rf.story.ui.state/toggle-tag-filter :internal)
        (is (contains? (visible-variant-ids) ":story.dfilter/private")
            "toggling :internal on shows its variant")))))
