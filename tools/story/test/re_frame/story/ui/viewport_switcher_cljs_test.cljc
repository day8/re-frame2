(ns re-frame.story.ui.viewport-switcher-cljs-test
  "The viewport switcher chip on CLJS: `select!` writes shell state, the
  effective viewport resolves override > toolbar, and the chip is a menu
  button (`aria-haspopup`, never `aria-pressed`, which the toolbar reset
  gate in story_feature_load counts). The pure preset logic is in
  `re-frame.story.viewport-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            #?@(:cljs [[re-frame.story.ui.state :as rf.story.ui.state]
                       [re-frame.story.ui.viewport-switcher :as rf.story.ui.viewport-switcher]
                       [re-frame.story.viewport :as rf.story.viewport]])))

#?(:cljs
   (defn reset-all! []
     (rf.story/clear-all!)
     (rf.story.ui.state/reset-shell-state!)
     (rf.story.ui.viewport-switcher/close!)
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.removeItem (.-localStorage js/window) rf.story.viewport/ls-key)
            (catch :default _ nil)))
     (rf.story/install-canonical-vocabulary!)))

#?(:cljs
   (use-fixtures :each (fn [t] (reset-all!) (t))))

;; ---- select! -------------------------------------------------------------

#?(:cljs
   (deftest cljs-select-drops-unknown
     (testing "a preset is written; an unknown preset clears the slot"
       (rf.story.ui.viewport-switcher/select! :tablet)
       (is (= :tablet (:viewport (rf.story.ui.state/get-state))))
       (rf.story.ui.viewport-switcher/select! :phablet)
       (is (nil? (:viewport (rf.story.ui.state/get-state)))))))

;; ---- per-story override resolution --------------------------------------

#?(:cljs
   (deftest cljs-effective-viewport-respects-variant-override
     (testing "per-variant :viewport body slot beats story and toolbar"
       (rf.story/reg-story* :story.vp-override
         {:doc "viewport override fixture" :component :ignored
          :viewport :desktop})
       (rf.story/reg-variant* :story.vp-override/v
         {:doc "child" :viewport :mobile-portrait})
       (rf.story.ui.state/swap-state! assoc :viewport :tablet)
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.vp-override/v)
       (is (= "Mobile portrait" (:label (rf.story.ui.viewport-switcher/effective-viewport))))
       (is (= :mobile-portrait (rf.story.ui.viewport-switcher/effective-id))))))

#?(:cljs
   (deftest cljs-effective-viewport-falls-through-to-story
     (testing "variant has no :viewport → parent story's :viewport applies"
       (rf.story/reg-story* :story.vp-story-only
         {:doc "story-level override only" :component :ignored
          :viewport :desktop})
       (rf.story/reg-variant* :story.vp-story-only/v
         {:doc "child"})
       (rf.story.ui.state/swap-state! assoc :viewport :tablet)
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.vp-story-only/v)
       (is (= :desktop (rf.story.ui.viewport-switcher/effective-id))))))

#?(:cljs
   (deftest cljs-effective-viewport-follows-the-extends-chain
     (testing "`:viewport` is world context, which spec/017
               §`:extends` inherits — an `:extends` child is framed at its
               parent's viewport, the one the compiled plan carries, not at
               the toolbar selection"
       (rf.story/reg-story* :story.vp-extends
         {:doc "names no viewport" :component :ignored})
       (rf.story/reg-variant* :story.vp-extends/base
         {:doc "parent" :viewport :mobile-portrait})
       (rf.story/reg-variant* :story.vp-extends/child
         {:doc "child" :extends :story.vp-extends/base})
       (rf.story/reg-variant* :story.vp-extends/own
         {:doc "grandchild with its own" :extends :story.vp-extends/child
          :viewport :desktop})
       (rf.story.ui.state/swap-state! assoc :viewport :tablet)
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.vp-extends/child)
       (is (= :mobile-portrait (rf.story.ui.viewport-switcher/effective-id))
           "the parent's viewport, not the toolbar's")
       (is (= (get-in (rf.story/variant-plan :story.vp-extends/child) [:world :viewport])
              (rf.story.ui.viewport-switcher/effective-id))
           "the viewport the compiled plan carries")
       (rf.story.ui.state/swap-state! assoc :selected-variant :story.vp-extends/own)
       (is (= :desktop (rf.story.ui.viewport-switcher/effective-id))
           "the nearest declaration on the chain wins"))))

#?(:cljs
   (deftest cljs-effective-viewport-falls-through-to-toolbar
     (testing "no override → toolbar selection takes effect"
       (rf.story.ui.state/swap-state! assoc :viewport :tablet)
       (is (= "Tablet" (:label (rf.story.ui.viewport-switcher/effective-viewport)))))))

;; ---- the chip ------------------------------------------------------------

#?(:cljs
   (deftest cljs-chip-data-attrs
     (testing "the chip carries its browser-spec hooks and is a menu
               button: aria-haspopup, and no aria-pressed='true' anywhere
               under it"
       (let [flat  (->> (tree-seq coll? seq (rf.story.ui.viewport-switcher/chip))
                        (filter map?))
             attrs (filter #(= "story-toolbar-viewport" (:data-test %)) flat)]
         (is (= [["full" "menu"]]
                (mapv (juxt :data-viewport :aria-haspopup) attrs))
             "one chip element; default render reports :full")
         (is (not-any? #(= "true" (:aria-pressed %)) flat))))))
