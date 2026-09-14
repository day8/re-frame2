;; Preloaded into the story-mcp JVM before the stdio loop starts (the README's golden path).
(require '[re-frame.core :as rf]
         '[re-frame.frame :as rf.frame]
         '[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
         '[re-frame.story :as rf.story])
(rf/init! rf.substrate.plain-atom/adapter)
(rf.story/install-canonical-vocabulary!)
(rf.frame/ensure-default-frame!)
(rf/reg-event :agent/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
(rf.story/reg-story* :story.agent {:doc "An app slice the agent is asked to fix."})
;; The 'bug': the expectation is wrong (or the app is) — the agent must discover it.
(rf.story/reg-variant* :story.agent/broken {:doc "expects v=2 but setup sets 1"
                                         :setup [[:agent/set 1]]
                                         :assertions [[:rf.assert/path-equals [:v] 2]]
                                         :tags #{:dev :test}})
(binding [*out* *err*] (println "prelude: adapter installed, 1 story / 1 variant registered"))
