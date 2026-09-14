(ns {{namespace}}.stories
  "Story, the component playground, at http://localhost:8280/#/stories.
   The dev build boots `init` here; a release boots `core/init`, so Story
   never reaches the release bundle."
  (:require [uix.core             :refer [$]]
            [re-frame.core        :as rf]
            [re-frame.story       :as rf.story]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [{{namespace}}.core   :as core]
            [{{namespace}}.views  :as views]))

;; A story names its view by keyword, so register the `defui`; the
;; `:rf/props` schema gives each of the story's args a control.
(rf/reg-view* :{{namespace}}.views/counter-app
  {:rf/props [:map [:heading {:optional true} :string]]}
  views/counter-app)

;; Story renders a UIx view through the function registered for `:uix`.
(rf.story/register-substrate! :uix
  (fn [_variant-id view-id args]
    ($ (rf/view view-id) args)))

;; A variant is one state of the story: the events that set it up and the
;; assertions Test mode runs.
(rf.story/reg-story :story.counter
  {:doc        "The counter view."
   :component  :{{namespace}}.views/counter-app
   :args       {:heading "Counter"}
   :tags       #{:dev :docs}
   :substrates #{:uix}})

(rf.story/reg-variant :story.counter/clicked-twice
  {:doc    "Seeded, then +1 twice."
   :setup  [[:counter/initialise] [:counter/increment] [:counter/increment]]
   :script [[:assert [:rf.assert/sub-equals [:counter/value] 2]]]
   :tags   #{:dev :docs :test}})

;; `#/stories` mounts the Story shell (open or reload that URL); every other
;; page boots the app.
(defn ^:export init []
  (if (= "#/stories" js/window.location.hash)
    (let [node (js/document.getElementById "app")]
      (rf/init! rf.adapter.uix/adapter)
      ;; core/mount! re-renders #app after every save; renaming the node
      ;; keeps it off the shell.
      (set! (.-id node) "stories")
      (rf.story/mount-shell! node))
    (core/init)))
