(ns {{namespace}}.stories
  "Story, the component playground, for this app's views.

   shadow-cljs.edn boots `init` below in `watch` and `compile`, and
   `core/init` in a release, so Story rides the dev bundle and never the
   release one. Open http://localhost:8280/#/stories.

   `reg-story` names the view a story renders and the args it starts from;
   the `:rf/props` schema on the view's registration gives those args their
   controls. `reg-variant` names one state of it: the events that set it up
   and the assertions Test mode runs."
  (:require [uix.core             :refer [$]]
            [re-frame.core        :as rf]
            [re-frame.story       :as story]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [{{namespace}}.core   :as core]
            ;; Requiring these installs the registrations the story names.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views  :as views]))

;; A story names its view by keyword. Registering the `defui` gives it one,
;; and the registration carries the props schema the controls derive from.
(rf/reg-view* :{{namespace}}.views/counter-app
  {:rf/props [:map [:heading {:optional true} :string]]}
  views/counter-app)

;; Story renders Reagent views itself; a UIx view renders through the
;; function its host registers for `:uix`.
(story/register-substrate! :uix
  (fn [_variant-id view-id args]
    ($ (rf/view view-id) args)))

(story/reg-story :story.counter
  {:doc        "The counter view."
   :component  :{{namespace}}.views/counter-app
   :args       {:heading "Counter"}
   :tags       #{:dev :docs}
   :substrates #{:uix}})

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
      (rf/init! rf.adapter.uix/adapter)
      ;; core/mount! re-renders into #app after every save; renaming the
      ;; node leaves this page to the shell.
      (set! (.-id node) "stories")
      (story/mount-shell! node))
    (core/init)))
