(ns re-frame2-pair-mcp.onboarding-routing-test
  "Phantom-name guard for the onboarding routing rules.

  `get-re-frame2-pair-instructions` ships a `## Routing rules` section
  (an inline string in `tools/get_re_frame2_pair_instructions`) naming
  which tool to reach for at each decision an agent faces. Those names
  are hand-written prose, so a typo or a stale name sends the model at
  a tool that answers `:unknown-tool` — the one failure mode the prose
  can still cause. This ns is the guard: every tool the routing rules
  name MUST be registered.

  ## What this ns deliberately no longer guards (rf2-wyza)

  It used to also assert the REVERSE — that every registered tool
  appeared in the prose — because the blob carried a hand-maintained
  33-entry `## Tool catalogue` that could drift behind the registry.
  That enumeration is retired, so its drift class is gone with it:
  there is no longer a per-tool line here to fall out of date. The
  routing rules name ~20 tools BY DESIGN, not all of them, and a
  no-omissions assertion over them would forbid exactly the elision
  that makes the section a routing index rather than a second
  catalogue.

  Nothing lost coverage in the trade. A newly-registered tool is
  visible to an agent through `tools/list` (generated from
  `registry/tools`, so it cannot drift) and, on a miss, through the
  dispatcher's `:unknown-tool` hint (which folds `registry/tool-names`
  into the error). And a new tool that nobody notices still reds a
  gate: `conformance_test`'s `conformance-corpus-covers-every-
  registered-tool` requires a corpus fixture for every registered
  name.

  ## Parse contract

  Inside the `## Routing rules` section, a backticked
  lowercase-kebab identifier means a tool name and nothing else —
  `^[a-z][a-z0-9-]*$`. That excludes the keywords the rules also
  backtick (`:frame`, `:cascade-summary`), slashed names
  (`tools/list`) and anything capitalised, so those stay free-form.
  The same contract is stated on `instructions-text` itself, where an
  author writing the prose will read it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [clojure.set :as set]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions :as instr]))

(defn- routing-section
  "Return just the lines under the `## Routing rules` heading, up to the
  next `## ` heading. Scoping the parse to the section keeps a
  backticked tool name in an unrelated convention section (e.g.
  `dispatch` under `## Tagged mutations`) from being read as a routing
  rule."
  [text]
  (->> (str/split-lines text)
       (drop-while #(not= "## Routing rules" (str/trim %)))
       (rest)                                ;; drop the heading itself
       (take-while #(not (str/starts-with? % "## ")))))

(defn- routed-tool-names
  "Extract the set of tool names the routing rules point at — every
  backticked token in the section matching `^[a-z][a-z0-9-]*$`. See the
  ns docstring for why that regex is the whole contract."
  [text]
  (->> (routing-section text)
       (mapcat #(re-seq #"`([^`]+)`" %))
       (keep (fn [[_ tok]] (when (re-matches #"[a-z][a-z0-9-]*" tok) tok)))
       (into #{})))

(deftest routing-section-parses
  (testing "the routing-rule parser finds a non-trivial set of tool names"
    (let [parsed (routed-tool-names instr/instructions-text)]
      ;; Self-check on the parser: a section that renamed its heading, or
      ;; prose that stopped backticking its tool names, would parse to
      ;; nothing — and the phantom assertion below would then pass
      ;; vacuously on an empty set. Pin a floor well under the real count.
      (is (>= (count parsed) 10)
          (str "the `## Routing rules` section yielded only " (count parsed)
               " tool names — the heading or the backtick convention likely "
               "changed; update the parse contract in this ns and on "
               "`instructions-text`")))))

(deftest routing-rules-name-only-registered-tools
  (testing "no routing rule points at an unregistered tool"
    (let [registered (set registry/tool-names)
          routed     (routed-tool-names instr/instructions-text)
          phantom    (set/difference routed registered)]
      (is (empty? phantom)
          (str "the `## Routing rules` section names tools that are not "
               "registered: " (sort phantom)
               " — fix the typo, or drop the rule if the tool is gone. A "
               "phantom name in a ROUTING rule is worse than one in a "
               "catalogue: the rule tells the agent to prefer that name, so "
               "it will be called, and it will answer :unknown-tool. (If the "
               "offender is not a tool name at all, it broke the parse "
               "contract — see this ns's docstring; do not backtick a bare "
               "lowercase word in that section.)")))))
