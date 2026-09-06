(ns re-frame.bundle-isolation-positive-control.story
  (:require [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.registrar :as rf.story.registrar]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationStory"
        #js [rf.story.registrar/reg-story*
             rf.story.decorators/resolve-decorator-refs
             rf.story.decorators/apply-hiccup-decorators]))
