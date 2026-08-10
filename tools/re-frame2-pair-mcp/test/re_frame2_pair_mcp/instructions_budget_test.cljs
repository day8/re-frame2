(ns re-frame2-pair-mcp.instructions-budget-test
  "Authoring-time budget guard for the onboarding prose (rf2-3dmj).

  `get-re-frame2-pair-instructions` returns one hand-written string —
  `instructions-text` in `tools/get_re_frame2_pair_instructions.cljs` —
  and that string grows every time a tool is added to the catalogue. It
  egresses through the same wire-boundary cap as every other response,
  so once the response exceeds `default-max-tokens` the WHOLE payload is
  replaced by the `{:rf.mcp/overflow ...}` marker: the first call an
  agent makes on a fresh session returns no onboarding text at all, and
  the marker's hint (\"re-call with narrower args\") is useless here
  because this tool has no narrowing args.

  ## Why a dedicated test rather than letting the cap speak

  The cap already stops the over-budget payload from reaching the wire —
  correctness is not in question. What was missing is a failure that
  NAMES THE CAUSE. Without this ns the breach surfaces as three
  unrelated-looking reds — `closed_world_test`'s
  `instructions-answered-before-connection` (`:ok?` is absent from an
  overflow marker, and so is `:text`), the `conformance_test` fixture
  `:get-re-frame2-pair-instructions/happy` (`:edn-submap` mismatch), and
  the mcp-conformance closed-world block — none of which mentions
  tokens, and all of which land in whatever PR happened to add the next
  tool. This test fails in the same run with the budget, the current
  usage and the remaining margin in the message, pointing at the file
  that must be edited.

  ## Measured through the production gate, never re-derived

  The assertion runs the REAL `cap/apply-cap` — the same function the
  `:apply-cap` step of `tools/invoke` runs — over the REAL result the
  tool handler produced, against `cap/default-max-tokens`. There is no
  second copy of the token arithmetic here to drift from the first, and
  no hard-coded `5000`: change the constant or the summing rule and this
  test follows.

  ## The exchange rate is ~2 characters per token, not ~4

  `wire/ok-text` writes the same payload into BOTH `:content[0].text`
  (the `pr-str` EDN) AND `:structuredContent` (the JSON projection), and
  `cap/sum-payload-tokens` counts both — correctly, since both ride the
  wire. So the prose is measured TWICE: one character of prose costs
  about half a token of budget, and the effective prose budget is about
  half the nominal cap. An author estimating headroom from the raw
  character count of `instructions-text` will be wrong by a factor of
  two, which is precisely how a draft lands 88 tokens over."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions :as instr]))

(def ^:private tool-name "get-re-frame2-pair-instructions")

(defn- over-budget-message
  "The failure an author reads. Names the budget, the current usage and
  the margin; says what the breach does to a consuming agent; points at
  the file to edit; states the exchange rate; and names the answer that
  is NOT the fix."
  [tokens budget]
  (str tool-name " is OVER its wire-boundary token budget.\n"
       "  usage : " tokens " tokens\n"
       "  budget: " budget " tokens (re-frame.mcp-base.overflow/default-max-tokens)\n"
       "  margin: " (- budget tokens) " tokens\n"
       "At egress the whole response is REPLACED by the {:rf.mcp/overflow ...} "
       "marker, so an agent's first-contact call returns no onboarding text at "
       "all — and the marker's hint ('re-call with narrower args') cannot help, "
       "because this tool takes no narrowing args.\n"
       "FIX: shorten `instructions-text` in "
       "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/"
       "get_re_frame2_pair_instructions.cljs. The `## Tool catalogue` section is "
       "~75% of it and is the only part that grows with the tool count.\n"
       "EXCHANGE RATE: the prose rides the wire TWICE — once as the pr-str EDN "
       ":content[0].text and once as the :structuredContent JSON — so one "
       "character of prose costs ~0.5 tokens of this budget (~2 characters per "
       "token, not the ~4 a single copy would suggest).\n"
       "NOT THE FIX: raising default-max-tokens. It is a cross-MCP constant in "
       "re-frame.mcp-base.overflow shared with story-mcp, and raising it only "
       "defers the same failure to a larger blob (rf2-3dmj)."))

(deftest instructions-response-fits-the-wire-token-budget
  (async done
    (-> (instr/get-re-frame2-pair-instructions-tool nil nil)
        (.then (fn [result]
                 (let [tokens (cap/sum-payload-tokens result)
                       budget cap/default-max-tokens
                       capped (cap/apply-cap result {:tool tool-name :cap budget})]
                   ;; Self-check: a zero sum would make the budget
                   ;; assertion vacuously green — it would "pass" on an
                   ;; empty result just as happily as on a well-sized one.
                   (is (pos? tokens)
                       (str "token sum for " tool-name " is " tokens
                            " — the result shape changed and this guard is "
                            "measuring nothing; re-check cap/sum-payload-tokens "
                            "against the tool's result envelope"))
                   ;; `apply-cap` returns the result object UNCHANGED when
                   ;; it fits, and a fresh overflow-marker result when it
                   ;; does not. Identity is therefore the exact question:
                   ;; "would the wire boundary have replaced this?"
                   (is (identical? capped result)
                       (over-budget-message tokens budget)))
                 (done)))
        (.catch (fn [e]
                  (is false (str tool-name " handler rejected: " (.-message e)))
                  (done))))))
