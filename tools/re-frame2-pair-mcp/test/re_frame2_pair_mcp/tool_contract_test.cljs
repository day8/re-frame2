(ns re-frame2-pair-mcp.tool-contract-test
  "An MCP host keeps the server process it launched, so a rebuilt source
  does not refresh it and its `serverInfo` version reads the same.
  `:tool-contract` — `registry/contract-fingerprint` over the tool names and
  argument keys — is what `get-re-frame2-pair-instructions` reports and
  skills/re-frame2-pair/SKILL.md states, so the skill's opening step can
  name a stale server before a missing tool reads as an application
  finding. The property is an agreement between two artefacts, so this
  suite reads the skill itself."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- skill-md
  "SKILL.md's text, found by walking up from the runner's cwd
  (`tools/re-frame2-pair-mcp` or the repository root)."
  []
  (loop [d (.cwd js/process)]
    (let [f (.join path d "skills/re-frame2-pair/SKILL.md")]
      (cond
        (and (.existsSync fs f) (.existsSync fs (.join path d "tools/re-frame2-pair-mcp/src")))
        (.toString (.readFileSync fs f))

        (= d (.dirname path d))
        (throw (ex-info "Could not locate skills/re-frame2-pair/SKILL.md above the cwd."
                        {:cwd (.cwd js/process)}))

        :else (recur (.dirname path d))))))

(defn- stated-contracts []
  (mapv second (re-seq #"tool contract `([^`]+)`" (skill-md))))

(deftest a-matching-pairing-proceeds
  ;; Through the real pre-connection call path: the skill runs this step
  ;; before `discover-app`, so it must answer with no nREPL at all.
  (async done
    (-> (server/handle-call-for-tests {} "get-re-frame2-pair-instructions" #js {} nil)
        (.then (fn [result]
                 (is (= [(:tool-contract (tu/extract-edn result))] (stated-contracts))
                     (str "skills/re-frame2-pair/SKILL.md must state this server's contract once, as "
                          "tool contract `" registry/tool-contract "` — re-copy it after changing a "
                          "tool's name or argument keys."))))
        (.catch (fn [e] (is false (str "get-re-frame2-pair-instructions rejected: " (.-message e)))))
        (.then (fn [_] (done))))))

(deftest a-stale-server-is-detected-against-the-current-skill
  (doseq [[change edit] [["a renamed tool" #(assoc % :name "dispatch-v0")]
                         ["a dropped argument key" #(update-in % [:inputSchema :properties] dissoc :replay)]]]
    (is (not= registry/tool-contract
              (registry/contract-fingerprint
                (map #(cond-> % (= "dispatch" (:name %)) edit) registry/tool-descriptors)))
        (str change " reads as a different contract"))))
