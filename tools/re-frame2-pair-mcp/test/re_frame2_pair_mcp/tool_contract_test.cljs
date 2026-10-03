(ns re-frame2-pair-mcp.tool-contract-test
  "A running server paired with the skill an agent loaded.

  An MCP host keeps the server process it launched, so rebuilding the
  source does not refresh it, and its `serverInfo` version reads the same
  either way. What tells an agent the two apart is `:tool-contract` —
  `registry/contract-fingerprint` over the catalogue's tool names and
  argument keys — which `get-re-frame2-pair-instructions` reports and
  skills/re-frame2-pair/SKILL.md states. The skill's opening step compares
  the two before `discover-app`, so a stale server is named as one before a
  missing tool can be read as an application finding.

  Reading the skill from this suite is the cross-artefact shape
  `handler_meta_test` uses for the preload: the property is an agreement
  between two artefacts, and a check that saw only one would restate the
  gap rather than close it."
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

(defn- stated-contracts
  "Every value the skill states as its tool contract."
  []
  (mapv second (re-seq #"tool contract `([^`]+)`" (skill-md))))

(def ^:private stale-catalogue
  "The catalogue measured on a long-running server built from older source:
  the current registry without the three tools added since, plus the eight
  removed since. Their argument keys are unknown, and the names alone
  already differ."
  (concat (remove (comp #{"read-mounted-boundaries" "read-read-attribution" "replay-epoch"} :name)
                  registry/tool-descriptors)
          (for [tool ["get-stream-controls" "list-streams" "read-mounted-views"
                      "read-view-dependencies" "read-view-event-sites" "read-view-manifest"
                      "subscribe" "unsubscribe"]]
            {:name tool :inputSchema {:type "object" :properties {}}})))

(deftest a-matching-pairing-proceeds
  ;; Through the real pre-connection call path: the step runs before
  ;; `discover-app`, so it must answer with no nREPL at all.
  (async done
    (-> (server/handle-call-for-tests {} "get-re-frame2-pair-instructions" #js {} nil)
        (.then (fn [result]
                 (let [edn (tu/extract-edn result)]
                   (is (true? (:ok? edn)))
                   (is (= [(:tool-contract edn)] (stated-contracts))
                       (str "skills/re-frame2-pair/SKILL.md must state this server's contract once, as "
                            "tool contract `" registry/tool-contract "` — re-copy it after changing a "
                            "tool's name or argument keys.")))))
        (.catch (fn [e] (is false (str "get-re-frame2-pair-instructions rejected: " (.-message e)))))
        (.then (fn [_] (done))))))

(deftest a-stale-server-is-detected-against-the-current-skill
  (is (= 35 (count stale-catalogue))
      "the fixture models the measured stale catalogue — 30 current tools less 3, plus 8")
  (is (not= registry/tool-contract (registry/contract-fingerprint stale-catalogue))
      "a server built before the Fresco boundary tools reads as a different contract from this one, which the skill states")
  (is (not= registry/tool-contract
            (registry/contract-fingerprint
              (map #(cond-> % (= "dispatch" (:name %)) (update-in [:inputSchema :properties] dissoc :replay))
                   registry/tool-descriptors)))
      "a server lacking one argument key reads as a different contract too"))
