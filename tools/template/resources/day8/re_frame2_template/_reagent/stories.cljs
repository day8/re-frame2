(ns {{namespace}}.stories
  "Story, the component playground, at http://localhost:8280/#/stories.
   The dev build boots `init` here; a release boots `core/init`, so Story
   never reaches the release bundle."
  (:require [re-frame.core            :as rf]
            [re-frame.story           :as story]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [{{namespace}}.core       :as core]))

;; A story renders a view from args; the view's `:rf/props` schema gives each
;; arg a control. A variant is one state: the events that set it up and the
;; assertions Test mode runs.
(story/reg-story :story.counter
  {:doc        "The counter view."
   :component  :{{namespace}}.views/counter-app
   :args       {:heading "Counter"}
   :tags       #{:dev :docs}
   :substrates #{:reagent}})

(story/reg-variant :story.counter/clicked-twice
  {:doc    "Seeded, then +1 twice."
   :setup  [[:counter/initialise] [:counter/increment] [:counter/increment]]
   :script [[:assert [:rf.assert/sub-equals [:counter/value] 2]]]
   :tags   #{:dev :docs :test}})

;; `#/stories` mounts the Story shell (open or reload that URL); every other
;; page boots the app.
(defn ^:export init []
  (if (= "#/stories" js/window.location.hash)
    (let [node (js/document.getElementById "app")]
      (rf/init! rf.adapter.reagent/adapter)
      ;; core/mount! re-renders #app after every save; renaming the node
      ;; keeps it off the shell.
      (set! (.-id node) "stories")
      (story/mount-shell! node))
    (core/init)))
