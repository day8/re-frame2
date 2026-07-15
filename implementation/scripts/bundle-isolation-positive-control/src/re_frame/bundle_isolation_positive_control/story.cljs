(ns re-frame.bundle-isolation-positive-control.story
  (:require [re-frame.story.decorators :as decorators]
            [re-frame.story.registrar :as registrar]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationStory"
        #js [registrar/reg-story*
             decorators/resolve-decorator-refs
             decorators/apply-hiccup-decorators]))
