(ns re-frame2-pair-mcp.onboarding-catalogue-test
  "Drift guard for the inline onboarding catalogue.

  `get-re-frame2-pair-instructions` ships a prose `## Tool catalogue`
  section (an inline string in `tools/get_re_frame2_pair_instructions`)
  that an AI agent reads to learn what tools exist. Unlike the
  `tools/list` descriptors — which are GENERATED from the single
  `registry/tools` source and so cannot drift — the onboarding prose is
  hand-maintained, so it can drift out of sync with the registered tool
  set. A missing tool (e.g. orient, read-sub, read-ui,
  set/reset/get-operating-frame) leaves a model reaching for snapshot /
  raw eval instead of the bounded reads, or guessing frame targeting
  instead of the operating-frame ops.

  `scripts/check_skill_mcp_drift.py` cross-checks the SKILL.md
  allowed-tools vs the descriptors — NOT this inline prose — so this
  suite guards the prose at the source: the catalogue MUST enumerate
  exactly the registered tool set, no omissions and no phantom names.

  Parse contract: each tool is listed on its own line as
  `\"  <name>          — <one-liner>\"` — two leading spaces, the tool
  name, padding, then an em-dash. Continuation lines for a multi-line
  description are indented far deeper (26 spaces) and carry no name in
  column 3, so the two-space-then-name shape uniquely identifies a
  catalogue entry."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [clojure.set :as set]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions :as instr]))

(defn- catalogue-section
  "Return just the lines under the `## Tool catalogue` heading, up to
  the next `## ` heading. Scoping the parse to the section keeps an
  unrelated `## ` body line from being mistaken for a tool entry."
  [text]
  (let [lines (str/split-lines text)]
    (->> lines
         (drop-while #(not= "## Tool catalogue" (str/trim %)))
         (rest)                              ;; drop the heading itself
         (take-while #(not (str/starts-with? % "## "))))))

(defn- catalogue-tool-names
  "Extract the set of tool names listed in the onboarding catalogue.

  A catalogue entry line matches `^  (\\S+) +— ` — exactly two leading
  spaces (entry, not a deeper continuation line), a non-space name
  token, then the em-dash separator. Returns the names as a set of
  strings."
  [text]
  (->> (catalogue-section text)
       (keep (fn [line]
               (let [[_ nm] (re-find #"^  (\S+)\s+—\s" line)]
                 nm)))
       (into #{})))

(deftest catalogue-section-parses
  (testing "the catalogue parser finds a non-trivial set of entries"
    (let [parsed (catalogue-tool-names instr/instructions-text)]
      ;; Self-check on the parser: if the prose shape changed such that
      ;; nothing parsed, the superset assertion below would vacuously
      ;; mis-report. Pin a floor well under the real count.
      (is (>= (count parsed) 20)
          (str "onboarding catalogue parser found only " (count parsed)
               " entries — the prose line shape likely changed; "
               "update the parse contract in this ns")))))

(deftest catalogue-matches-registered-tools
  (testing "every registered tool appears in the onboarding catalogue (no omissions)"
    (let [registered (set registry/tool-names)
          listed     (catalogue-tool-names instr/instructions-text)
          missing    (set/difference registered listed)]
      (is (empty? missing)
          (str "registered tools missing from the get-re-frame2-pair-instructions "
               "onboarding catalogue: " (sort missing)
               " — add a one-line entry for each (the catalogue is "
               "AI-facing onboarding; an omitted tool is invisible to the agent)"))))

  (testing "the catalogue lists no phantom (unregistered) tool names"
    (let [registered (set registry/tool-names)
          listed     (catalogue-tool-names instr/instructions-text)
          phantom    (set/difference listed registered)]
      (is (empty? phantom)
          (str "onboarding catalogue lists names that are not registered tools: "
               (sort phantom)
               " — remove them or fix the typo (a phantom name sends the "
               "agent at a tool that returns :unknown-tool)")))))
