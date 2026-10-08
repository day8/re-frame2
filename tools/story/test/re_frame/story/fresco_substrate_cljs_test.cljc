(ns re-frame.story.fresco-substrate-cljs-test
  "`:fresco` on Story's authoring-layer axis, where a substrate keyword
  enters as data: the closed `SubstrateSet` admits it at registration and
  the MCP read path (`rf.story/variant->edn`) relays it, while an unknown
  member is still refused. The plan fold to `[:world :substrates]` is
  witnessed by `re-frame.story.story-scope-world-keys-cljs-test`, the
  renderer by `re-frame.story.ui.fresco-substrate-dom-cljs-test`.

  `.cljc` with a `-cljs-test` name, so the JVM runner and `:node-test`
  both run every row; nothing here needs a renderer or `re-frame.fresco`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Registration and the default frame build state containers through the
  ;; installed substrate adapter, so the namespace installs its own rather
  ;; than relying on one an earlier namespace left behind.
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

;; The adapter slot is handed back as found: one this fixture seated is
;; disposed afterwards rather than left for a later namespace to lean on.
(use-fixtures :each
  (fn [t]
    (let [seated? (some? (rf/current-adapter))]
      (reset-all!)
      (try (t)
           (finally (when-not seated? (rf/destroy-adapter!)))))))

(deftest a-fresco-variant-registers
  (rf.story/reg-story* :story.hic {:doc "fresco authoring-layer fixture"})
  (rf.story/reg-variant* :story.hic/card
    {:doc        "A variant whose subject is a fresco boundary."
     :component  :my.app.views/article-card
     :substrates #{:fresco}})
  (is (= #{:fresco} (:substrates (rf.story/variant->edn :story.hic/card)))))

(deftest an-unknown-substrate-is-still-refused-at-registration
  (rf.story/reg-story* :story.hic-bad {:doc "fixture"})
  (let [e (try (rf.story/reg-variant* :story.hic-bad/typo
                 {:doc "declares a substrate nobody defines"
                  :substrates #{:hicaso}})
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
    (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))))
