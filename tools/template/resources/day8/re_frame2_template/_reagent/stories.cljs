(ns {{namespace}}.stories
  "Story, the component playground, for this app's views.

   shadow-cljs.edn boots `init` below in `watch` and `compile`, and
   `core/init` in a release, so Story rides the dev bundle and never the
   release one. Open http://localhost:8280/#/stories.

   `reg-story` names the view a story renders and the args it starts from;
   the view's `:rf/props` schema gives those args their controls.
   `reg-variant` names one state of it: the events that set it up and the
   assertions Test mode runs."
  (:require [re-frame.core            :as rf]
            [re-frame.story           :as story]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [{{namespace}}.core       :as core]
            ;; Requiring these installs the registrations the story names.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views]))

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

;; The dev build's entry. `#/stories` gets the Story shell; every other page
;; boots the app. Open the hash directly, or reload after changing it.
(defn ^:export init []
  (if (= "#/stories" js/window.location.hash)
    (let [node (js/document.getElementById "app")]
      (rf/init! rf.adapter.reagent/adapter)
      ;; core/mount! re-renders into #app after every save; renaming the
      ;; node leaves this page to the shell.
      (set! (.-id node) "stories")
      (story/mount-shell! node))
    (core/init)))
