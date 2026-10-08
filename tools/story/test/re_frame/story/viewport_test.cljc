(ns re-frame.story.viewport-test
  "The viewport switcher's pure state model. JVM only: no CLJS build
  selects a `-test` namespace, so the localStorage round-trip lives in
  `re-frame.story.viewport-storage-dom-cljs-test`."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.story.viewport :as rf.story.viewport]))

;; The preset ids are the ones docs/story/api/registration.md lists.
(deftest preset-table-and-order-carry-every-canonical-id
  (let [expected #{:full :mobile-portrait :mobile-landscape
                   :tablet :desktop :desktop-wide}]
    (is (= expected (set (keys rf.story.viewport/presets))))
    (is (= expected (set rf.story.viewport/preset-order)))))

(deftest valid-custom-requires-positive-integer-dimensions
  (are [m ok?] (= ok? (rf.story.viewport/valid-custom? m))
    {:width 1 :height 1}   true
    {:width 0 :height 600} false))

(deftest coerce-keeps-presets-and-valid-dims-and-drops-the-rest
  (are [slot coerced] (= coerced (rf.story.viewport/coerce slot))
    :tablet                                  :tablet
    :full                                    :full
    ;; a custom map coerces to a slim {:width :height} map
    {:width 800 :height 600 :label "extra"}  {:width 800 :height 600}
    :Mode.unknown/whatever                   nil
    :phablet                                 nil
    {:width "800" :height "600"}             nil
    {:width 800}                             nil
    "800x600"                                nil
    nil                                      nil))

(deftest resolve-falls-back-to-full-when-empty
  (is (= {:label "Full" :width nil :height nil} (rf.story.viewport/resolve nil nil))))

(deftest resolve-prefers-a-usable-override-else-the-toolbar
  (are [override resolved] (= resolved (rf.story.viewport/resolve override :tablet))
    :mobile-portrait         {:label "Mobile portrait" :width 375 :height 667}
    ;; a custom {:width :height} override renders under its synthetic label
    {:width 500 :height 300} {:label "Custom 500x300" :width 500 :height 300}
    ;; an unrecognised override does not block the live toolbar selection
    :phablet                 {:label "Tablet" :width 768 :height 1024}))

(deftest resolve-id-returns-keyword-or-custom
  (is (= :full    (rf.story.viewport/resolve-id nil nil)))
  (is (= :tablet  (rf.story.viewport/resolve-id nil :tablet)))
  (is (= :desktop (rf.story.viewport/resolve-id :desktop :tablet)))
  (is (= :custom  (rf.story.viewport/resolve-id {:width 500 :height 300} nil))))

(deftest wrap-style-frames-a-sized-preset-and-skips-full
  (is (nil? (rf.story.viewport/wrap-style (:full rf.story.viewport/presets))))
  (let [s (rf.story.viewport/wrap-style (:tablet rf.story.viewport/presets))]
    (is (= "768px" (:width s)))
    (is (= "1024px" (:height s)))
    (is (string? (:border s)))
    (is (= "0 auto" (:margin s)))))
