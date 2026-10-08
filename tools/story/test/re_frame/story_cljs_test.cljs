(ns re-frame.story-cljs-test
  "Story registration on CLJS. The registration surface is covered on the
  JVM (`re-frame.story-test`)."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

;; Every variant carries the `:state/*` axis, so an unregistered state tag
;; would abort the first gallery namespace load and empty the panel gallery.

(deftest cljs-state-axis-tags-survive-variant-registration
  (rf.story/reg-story :story.cljs.state-axis-smoke {:component :app.cljs/comp :tags #{:dev}})
  (rf.story/reg-variant :story.cljs.state-axis-smoke/all-state-magnitudes
    {:setup [[:init]] :tags (into #{:dev} rf.story.schemas/canonical-state-tags)})
  (is (rf.story/registered? :variant :story.cljs.state-axis-smoke/all-state-magnitudes)))
