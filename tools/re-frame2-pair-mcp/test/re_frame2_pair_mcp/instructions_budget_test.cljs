(ns re-frame2-pair-mcp.instructions-budget-test
  "Authoring-time budget guard for the onboarding prose (rf2-3dmj).

  `get-re-frame2-pair-instructions` returns one hand-written string —
  `instructions-text` in `tools/get_re_frame2_pair_instructions.cljs`.
  It used to grow with every tool added; rf2-wyza retired the per-tool
  enumeration that made it do so, and what remains is conventions and
  six routing rules, all constant in the tool count. So a breach now
  means the PROSE grew — which is exactly why the guard is still here.
  It egresses through the same wire-boundary cap as every other response,
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
  two, which is precisely how a draft lands 88 tokens over.

  ## Two lines, not one: a reserve as well as the cap (rf2-wyza)

  rf2-3dmj landed only the hard assertion — a reserve BELOW the cap was
  deferred, because at 4,888/5,000 any reserve would have been red the
  day it landed, and trimming prose to create one was the exact
  deferral that bead forbade. Retiring the `## Tool catalogue` removed
  ~3,600 tokens and with them that obstacle, so the reserve lands here.

  It buys an EARLY WARNING. Without it the only signal is the hard
  stop, which arrives as a broken first-contact response; with it, an
  author who is spending the headroom hears about it while the response
  still ships perfectly. The reserve is a FRACTION of the cap rather
  than an absolute, so it tracks `default-max-tokens` the way the hard
  assertion does — one constant to change, not two.

  ## A third line: the advertised hint must match the payload (rf2-wyza)

  The two lines above bound the payload. `instructions-response-
  advertises-its-real-size` bounds what the descriptor CLAIMS the
  payload costs — its `:typicalTokens`, which clients read off
  `tools/list` to budget the call. Retiring the catalogue cut the
  response roughly in half and the hint stayed at 1,500 while the
  response measured 2,164: 44% low when the rf2-wyza audit caught it,
  and low in the direction that matters, since a client sizing a
  context window against it under-provisions.

  This tool is the one place that hint is knowable EXACTLY. Every other
  descriptor advertises a response whose size depends on the app and the
  args, so its `:typicalTokens` is a genuine estimate and nothing can
  check it. This response is a fixed inline string with no narrowing
  args — the same bytes on every call, in every app — so a hint that
  disagrees with it is not an estimate that drifted, it is simply
  wrong, and the measurement that settles it is one the test already
  performs.

  So the guard measures rather than remembers: it compares the shipped
  hint against `cap/sum-payload-tokens` of the real response, through
  the same production summing rule the cap uses. Pinning a second
  hand-written constant here would have created exactly the drift it is
  meant to catch, one file further away."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions :as instr]))

(def ^:private tool-name "get-re-frame2-pair-instructions")

(def ^:private authoring-reserve-fraction
  "Share of `default-max-tokens` the onboarding prose may consume before
  the early-warning assertion trips — the reserve rf2-3dmj deferred and
  rf2-wyza made affordable.

  0.6 is chosen against what the prose costs and what it is for, not as
  a round number. Post-rf2-wyza the text measures ~2,270 tokens — 45% of
  the cap — so a 3,000-token reserve leaves ~730 tokens of authoring
  room, about 1,450 characters: a new convention section and a routing
  rule or two, which is the realistic edit here. It still trips ~2,800
  tokens before the response would break, so the warning arrives with
  room to think rather than as a deadline. Raising it is a DECISION
  about how much early warning is worth, not a way to make a red go
  away; the honest fix is almost always to trim."
  0.6)

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
       "all - and the marker's hint ('re-call with narrower args') cannot help, "
       "because this tool takes no narrowing args.\n"
       "FIX: shorten `instructions-text` in "
       "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/"
       "get_re_frame2_pair_instructions.cljs. Every section there is a "
       "CONVENTION or a routing rule - constant in size, none of it indexed by "
       "the tool count - so a breach means the prose itself grew, and trimming "
       "it is the whole fix. NOT by re-adding a per-tool enumeration: that was "
       "retired (rf2-wyza) precisely because it was the one part that grew, and "
       "`tools/list` plus the :unknown-tool hint already carry the tool set.\n"
       "EXCHANGE RATE: the prose rides the wire TWICE - once as the pr-str EDN "
       ":content[0].text and once as the :structuredContent JSON - so one "
       "character of prose costs ~0.5 tokens of this budget (~2 characters per "
       "token, not the ~4 a single copy would suggest).\n"
       "NOT THE FIX: raising default-max-tokens. It is a cross-MCP constant in "
       "re-frame.mcp-base.overflow shared with story-mcp, and raising it only "
       "defers the same failure to a larger blob (rf2-3dmj)."))

(defn- over-reserve-message
  "The EARLY warning. Distinguished from `over-budget-message` in the
  first line, because the two demand different responses: this one does
  not break anything today, and an author who reads it as the hard stop
  will either panic or (worse) raise the fraction to clear it."
  [tokens budget reserve]
  (str tool-name " is over its AUTHORING RESERVE (the early warning, not the "
       "hard cap - the response still ships correctly today).\n"
       "  usage  : " tokens " tokens\n"
       "  reserve: " reserve " tokens (" (int (* 100 authoring-reserve-fraction))
       "% of the " budget "-token wire cap)\n"
       "  to cap : " (- budget tokens) " tokens still remaining\n"
       "This is the warning rf2-3dmj deferred and rf2-wyza made affordable: it "
       "fires while there is still room to think, so the onboarding text never "
       "reaches the cap - where the WHOLE response becomes an overflow marker "
       "and a fresh session gets no onboarding text at all.\n"
       "FIX: trim `instructions-text`. Raising authoring-reserve-fraction in "
       "this ns is a deliberate decision about how much early warning is worth, "
       "and it should be argued for, not reached for to clear a red."))

(def ^:private hint-tolerance-fraction
  "How far the shipped `:typicalTokens` may sit from the measured
  response before the hint counts as wrong.

  Not zero. `:typicalTokens` is a budgeting aid, so a round number is
  the readable thing to ship, and exact equality would red this test on
  every comma — turning a correctness guard into a two-file chore and
  training authors to retype the number without reading it. 10% is
  wide enough for a round hint and for ordinary prose edits, and far
  too narrow for the 44% miss that occasioned it (rf2-wyza)."
  0.10)

(defn- typical-tokens-hint
  "The `:typicalTokens` this tool advertises, read off the REGISTERED
  descriptor rather than the `descriptors-data` def — the registered
  vector is what `tools/list` projects, so this measures the number
  clients actually receive."
  []
  (some #(when (= tool-name (:name %)) (:typicalTokens %))
        registry/tool-descriptors))

(defn- hint-drift-message
  "The failure an author reads. Leads with the correction, because the
  fix is to write one number down and there is no judgement in it."
  [hint tokens]
  (str tool-name " advertises a :typicalTokens that its response does not cost.\n"
       "  advertised: " hint " tokens (:typicalTokens in "
       "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs)\n"
       "  measured  : " tokens " tokens (cap/sum-payload-tokens over the real result)\n"
       "  drift     : " (js/Math.round (* 100 (/ (- hint tokens) tokens))) "%"
       " (tolerated: " (int (* 100 hint-tolerance-fraction)) "%)\n"
       "FIX: set :typicalTokens to " tokens " (or a round number within "
       (int (* 100 hint-tolerance-fraction)) "% of it).\n"
       "WHY THIS ONE IS CHECKABLE AT ALL: every other descriptor describes a "
       "response whose size depends on the app and the args, so its hint is a "
       "real estimate. This response is a fixed inline string with no narrowing "
       "args - identical on every call in every app - so its cost is knowable "
       "exactly, and a hint that disagrees is wrong rather than approximate.\n"
       "WHY IT MATTERS: clients read :typicalTokens off tools/list to size a "
       "call before making it. A hint reading LOW under-provisions the context "
       "budget for the first call of a session - which is this tool's whole "
       "purpose. Retiring the tool catalogue (rf2-wyza) halved the response and "
       "left the hint 44% low; that is the drift this guard exists to stop "
       "recurring.\n"
       "NOTE: after editing `instructions-text`, expect this red - it is the "
       "guard doing its job. Re-run and copy the measured figure; do NOT widen "
       "hint-tolerance-fraction to clear it."))

(deftest instructions-response-advertises-its-real-size
  (async done
    (-> (instr/get-re-frame2-pair-instructions-tool nil nil)
        (.then (fn [result]
                 (let [tokens (cap/sum-payload-tokens result)
                       hint   (typical-tokens-hint)]
                   ;; Self-check, mirroring the `(pos? tokens)` guard on the
                   ;; budget assertion: a nil hint (tool renamed, descriptor
                   ;; unregistered) would make the comparison below throw
                   ;; rather than fail readably.
                   (is (pos? (or hint 0))
                       (str "no :typicalTokens found for " tool-name
                            " in registry/tool-descriptors — the tool name or "
                            "the descriptor registration changed"))
                   (when (pos? (or hint 0))
                     (is (<= (js/Math.abs (- hint tokens))
                             (* hint-tolerance-fraction tokens))
                         (hint-drift-message hint tokens))))
                 (done)))
        (.catch (fn [e]
                  (is false (str tool-name " handler rejected: " (.-message e)))
                  (done))))))

(deftest instructions-response-fits-the-wire-token-budget
  (async done
    (-> (instr/get-re-frame2-pair-instructions-tool nil nil)
        (.then (fn [result]
                 (let [tokens  (cap/sum-payload-tokens result)
                       budget  cap/default-max-tokens
                       capped  (cap/apply-cap result {:tool tool-name :cap budget})
                       ;; `apply-cap` returns the result object UNCHANGED when
                       ;; it fits, and a fresh overflow-marker result when it
                       ;; does not. Identity is therefore the exact question —
                       ;; "would the wire boundary have replaced this?" —
                       ;; collapsed to a boolean HERE so the `actual:` line
                       ;; stays one word. Asserting `(identical? capped result)`
                       ;; directly would print both ~10 KB envelopes above the
                       ;; message, burying the numbers the author needs.
                       replaced? (not (identical? capped result))]
                   ;; Self-check: a zero sum would make the budget
                   ;; assertion vacuously green — it would "pass" on an
                   ;; empty result just as happily as on a well-sized one.
                   (is (pos? tokens)
                       (str "token sum for " tool-name " is " tokens
                            " — the result shape changed and this guard is "
                            "measuring nothing; re-check cap/sum-payload-tokens "
                            "against the tool's result envelope"))
                   (is (false? replaced?)
                       (over-budget-message tokens budget))
                   ;; The early line, asserted AFTER the hard one so that a
                   ;; run which breached both reads top-down as "broken" then
                   ;; "and you were warned", rather than leading with the
                   ;; softer of the two failures.
                   (let [reserve (js/Math.floor (* authoring-reserve-fraction budget))]
                     (is (<= tokens reserve)
                         (over-reserve-message tokens budget reserve))))
                 (done)))
        (.catch (fn [e]
                  (is false (str tool-name " handler rejected: " (.-message e)))
                  (done))))))
