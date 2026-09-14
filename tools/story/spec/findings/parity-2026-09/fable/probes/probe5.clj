;; Probe 5: does the fidelity-upgrade snippet parse? (sibling claim: trailing ; comment swallows the closing delimiters)
(ns probe5 (:require [re-frame.story.ui.view-state :as vs] [clojure.edn :as edn]))
(let [s (vs/upgrade-snippet :story.login-form/idle :real-setup)]
  (println "P5 snippet:") (println s)
  (println "P5 read-string →" (try (pr-str (edn/read-string s)) (catch Throwable e (str "THREW " (.getMessage e)))))
  (println "P5 last-line-is-comment?" (boolean (re-find #"(?m)^\s*;;? .*\)\s*$|; real events.*\)\s*$" s))))
(shutdown-agents) (System/exit 0)
