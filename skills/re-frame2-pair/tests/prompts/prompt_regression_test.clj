;;;; tests/prompts/prompt_regression_test.clj — prompt-regression for the
;;;; canonical re-frame2-pair conversations.
;;;;
;;;; Per `docs/TESTING.md` §3 the goal of prompt regression is to catch
;;;; SILENT DRIFT in the skill's recipes as the skill itself evolves.
;;;; A conversation-driving harness (Claude in the loop) is the *fidelity-
;;;; ideal* version of this surface; the structural substrate here catches
;;;; the cheapest class of drift:
;;;;
;;;; - The canonical prompt's *recipe* still lives in `references/recipes.md`
;;;; under the expected heading.
;;;; - The recipe still names the expected op(s) — so a renamed shim or a
;;;; removed runtime helper breaks the test.
;;;;
;;;; A future bead lands the conversation-driving variant on top of this
;;;; substrate (the canonical-prompts table is the source-of-truth in
;;;; either case).
;;;;
;;;; Run: bb tests/prompts/prompt_regression_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(ns prompt-regression-test
 (:require [clojure.java.io :as io]
 [clojure.string :as str]
 [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private skill-root
 (-> *file*
 (io/file)
 (.getAbsoluteFile)
 (.getParentFile) ;; tests/prompts/
 (.getParentFile) ;; tests/
 (.getParentFile))) ;; skills/re-frame2-pair/

(defn- slurp-rel [rel]
 (slurp (io/file skill-root rel)))

(def ^:private recipes-md (delay (slurp-rel "references/recipes.md")))
(def ^:private ops-md (delay (slurp-rel "references/ops.md")))
(def ^:private skill-md (delay (slurp-rel "SKILL.md")))
(def ^:private errors-md (delay (slurp-rel "references/errors.md")))
(def ^:private vocabulary-md (delay (slurp-rel "references/vocabulary.md")))
(def ^:private hot-reload (delay (slurp-rel "references/ops.md")))

;; User-facing docs + the variant leaf the MCP-surface
;; conformance drift guards assert against.
(def ^:private readme-md (delay (slurp-rel "README.md")))
(def ^:private capabilities-md (delay (slurp-rel "docs/capabilities.md")))
(def ^:private local-dev-md (delay (slurp-rel "docs/LOCAL_DEV.md")))
(def ^:private testing-md (delay (slurp-rel "docs/TESTING.md")))
(def ^:private stories-md (delay (slurp-rel "references/stories.md")))
(def ^:private wire-size-md (delay (slurp-rel "references/wire-size-budget.md")))

;; Live Pair-MCP catalogue cardinality. The generated descriptor
;; manifest is the single source of truth for the tool count; read its
;; `:meta :tool-count` directly rather than hard-coding a number here, so this
;; guard tracks the catalogue automatically. The manifest sits two levels up
;; from skill-root (repo `tools/re-frame2-pair-mcp/`).
(def ^:private tool-count
  (delay
    (let [manifest (io/file skill-root
                            ".." ".." "tools" "re-frame2-pair-mcp"
                            "tool-descriptors.edn")
          text     (slurp manifest)
          ;; Lightweight extraction — avoid an EDN reader dep under bb.
          m        (re-find #":tool-count\s+(\d+)" text)]
      (assert m (str "could not read :tool-count from " (.getPath manifest)))
      (Long/parseLong (second m)))))

;; ---------------------------------------------------------------------------
;; The canonical-prompts table
;; ---------------------------------------------------------------------------
;;
;; Six representative prompts (`docs/TESTING.md` §3 calls them out).
;; Each row carries:
;;
;; :id stable identifier for cross-referencing in beads/PRs
;; :prompt the user-spoken request
;; :recipe-anchor a substring expected in references/recipes.md's
;; heading — proves the recipe still exists
;; :must-mention ops the recipe is expected to name. Each is an
;; alternation of phrasings; the test passes if AT
;; LEAST ONE alternative appears in recipes.md.
;;
;; ALTERNATION RATIONALE — re-frame2-pair's vocabulary admits multiple surfaces
;; for the same op (MCP tool name, runtime fn name).
;; The regression should fire when ALL of them disappear, not when one
;; rename happens. The list per row is the set the recipe *currently*
;; uses; if a future edit drops one and adds another, the test still
;; passes — as long as something covering the same idea is named.

(def canonical-prompts
 [{:id :app-db-snapshot
 :prompt "What's in app-db under :user/profile?"
 :recipe-anchor "What's in `app-db`"
 :must-mention [["app-db/snapshot" "app-db/get" "snapshot"]]}

 {:id :trace-explain-dispatch
 :prompt "Trace `[:cart/apply-coupon \"SPRING25\"]`"
 :recipe-anchor "Explain this dispatch"
 :must-mention [["dispatch-and-collect" "trace/dispatch-and-collect"]
 [":rf/epoch-record" "epoch-record"]
 [":sub-runs"]
 [":renders"]]}

 {:id :why-no-update
 :prompt "Why didn't the header update after `[:profile/save ...]`?"
 :recipe-anchor "Why didn't my view update"
 :must-mention [[":sub-runs"]
 ["trace/last-epoch" "trace/last-pair-epoch" "last-epoch"]
 ["equality" "cache-hit"]]}

 {:id :experiment-loop
 :prompt "Iterate on the cart handler until expired coupons are rejected"
 :recipe-anchor "Experiment loop"
 :must-mention [["dispatch-and-collect"]
 ["restore-epoch"]
 ["reg-event"]]}

 {:id :where-in-code
 :prompt "Where in the code does this button come from?"
 :recipe-anchor "Where in the code"
 :must-mention [["dom/source-at" "source-at"]
 ["data--coord" "source-coord"]]}

 ;; rf2-p1keh — Story-in-the-open-app. The recipe must route through the
 ;; ATTACHED browser runtime: `eval-cljs` over `re-frame.story/*`, awaited
 ;; (run-variant returns a Promise in CLJS), then an ordinary Pair frame op
 ;; against the same runtime. The forbidden half — no `mcp__re-frame2-story-mcp__`
 ;; anywhere — is asserted in `story-work-stays-on-the-attached-runtime` below,
 ;; because a must-mention table can only require presence.
 {:id :story-in-the-open-app
 :prompt "Run this variant in the app I have open"
 :recipe-anchor "Drive a Story variant"
 :must-mention [["mcp__re-frame2-pair__eval-cljs"]
 ["re-frame.story/run-variant"]
 ["await"]
 ["set-operating-frame"]]}])

;; ---------------------------------------------------------------------------
;; Assertions
;; ---------------------------------------------------------------------------

(defn- recipe-section
 "Return the chunk of recipes.md starting at the heading matching
 `anchor` and ending at the next `## ` heading. Empty if no match."
 [md anchor]
 (let [pat (re-pattern (str "(?ms)## .*" (java.util.regex.Pattern/quote anchor) ".*?(?=^## |\\z)"))]
 (or (some-> (re-find pat md)) "")))

(defn- contains-any? [text alts]
 (some #(str/includes? text %) alts))

(defn- section-from
 "Return the chunk of `md` starting at the heading containing `anchor`
 and ending at the next `## ` heading (or EOF). Empty if no match.
 Generalises `recipe-section` to any markdown leaf."
 [md anchor]
 (let [pat (re-pattern (str "(?ms)## .*" (java.util.regex.Pattern/quote anchor) ".*?(?=^## |\\z)"))]
 (or (some-> (re-find pat md)) "")))

(defn- assert-row [{:keys [id prompt recipe-anchor must-mention]}]
 (testing (str id " — " prompt)
 (let [section (recipe-section @recipes-md recipe-anchor)]
 (is (seq section)
 (str "recipes.md missing the `" recipe-anchor "` heading — "
 "did the recipe get renamed? Update either the recipe or "
 "the canonical-prompts table together (drift detector)."))
 (doseq [alts must-mention]
 (is (contains-any? section alts)
 (str "recipe " recipe-anchor
 " no longer names any of " (pr-str alts)
 " — likely a renamed op or removed step. Update the "
 "table or restore the mention."))))))

(deftest canonical-prompts-still-mentioned
 (doseq [row canonical-prompts]
 (assert-row row)))

;; ---------------------------------------------------------------------------
;; SKILL.md-level invariants — the top-level recipe-routing must point
;; somewhere real. These catch the next failure mode after a recipe
;; rename: the SKILL.md guidance still pointing at the old name.
;; ---------------------------------------------------------------------------

(deftest skill-router-still-points-at-recipes
 (testing "SKILL.md mentions references/recipes.md as the recipe leaf"
 (is (str/includes? @skill-md "references/recipes.md"))))

(deftest skill-router-still-points-at-ops
 (testing "SKILL.md mentions references/ops.md as the op leaf"
 (is (str/includes? @skill-md "references/ops.md"))))

(deftest skill-router-still-points-at-errors
 (testing "SKILL.md mentions references/errors.md as the error leaf"
 (is (str/includes? @skill-md "references/errors.md"))))

(deftest skill-router-still-points-at-hot-reload
 (testing "SKILL.md links to the hot-reload-coordination section in ops.md"
 (is (str/includes? @skill-md "ops.md#hot-reload-coordination"))))

;; ---------------------------------------------------------------------------
;; Setup-recipe — discoverable + still pointing at the preload mechanism.
;; ---------------------------------------------------------------------------

(deftest setup-recipe-still-names-the-preload
 (testing "SKILL.md §Setup still names :devtools :preloads and re-frame2-pair.runtime"
 (is (str/includes? @skill-md ":preloads"))
 (is (str/includes? @skill-md "re-frame2-pair.runtime"))))

;; ---------------------------------------------------------------------------
;; Errors recipe — :runtime-not-preloaded is the most-likely first-run
;; failure mode; the recipe must still cover it.
;; ---------------------------------------------------------------------------

(deftest errors-md-covers-not-preloaded
 (testing "errors.md still covers :runtime-not-preloaded"
 (is (str/includes? @errors-md ":runtime-not-preloaded"))))

;; ---------------------------------------------------------------------------
;; Hot-reload protocol — must still name the probe-based contract,
;; since SKILL.md cardinal-rule §Source edits points users there.
;; ---------------------------------------------------------------------------

(deftest hot-reload-doc-still-describes-probe
 (testing "ops.md §Hot-reload coordination still describes the probe-based contract"
 (is (str/includes? @hot-reload "Hot-reload coordination"))
 (is (str/includes? @hot-reload "probe"))
 (is (str/includes? @hot-reload "tail-build"))))

;; ---------------------------------------------------------------------------
;; Privacy-contract drift
;; ---------------------------------------------------------------------------
;;
;; The skill-facing privacy guarantee MUST match what the pair-mcp tools
;; actually enforce:
;;   - The guarantee is scoped to the STRUCTURED MCP read tools,
;;     NOT to raw `eval-cljs` (which is default-ON and returns its value
;;     un-walked, regardless of --allow-sensitive-reads).
;;   - Epoch egress (trace-window / watch-epochs) is REDACTED/ELIDED by
;;     default via projected-record /
;;     elide-wire-value — not shipped raw.
;; These assertions fail if the docs drift to the over-broad "sensitive
;; data does not cross the LLM boundary by default" claim or the "epoch
;; records are not dropped / carry no sensitive stamp" wording.

(defn- includes-ci? [text needle]
  (str/includes? (str/lower-case text) (str/lower-case needle)))

(deftest skill-scopes-guarantee-to-structured-tools
  (testing "SKILL.md privacy bullet names the eval-cljs carve-out, not a blanket guarantee"
    ;; The carve-out must be present: eval-cljs is default-ON + un-elided
    ;; + not governed by --allow-sensitive-reads.
    (is (includes-ci? @skill-md "raw-eval carve-out")
        (str "SKILL.md no longer references the raw-eval carve-out — the "
             "privacy guarantee may have drifted back to the over-broad "
             "blanket claim (rf2-k2off)."))
    (is (or (str/includes? @skill-md "not governed by this gate")
            (str/includes? @skill-md "NOT governed by this gate"))
        "SKILL.md must state eval-cljs is NOT governed by --allow-sensitive-reads.")
    (is (str/includes? @skill-md "without running the elision walker")
        "SKILL.md must state eval-cljs returns its value un-elided."))
  (testing "SKILL.md must NOT carry the bare over-broad guarantee as a standalone claim"
    ;; The exact stale lede the review flagged. Its presence (verbatim,
    ;; un-narrowed) is the regression.
    (is (not (str/includes? @skill-md "Sensitive data does not cross the LLM boundary by default."))
        (str "SKILL.md carries the over-broad 'Sensitive data does not "
             "cross the LLM boundary by default.' lede — narrow it to the "
             "structured MCP reads (rf2-k2off)."))))

(deftest vocabulary-epoch-egress-matches-impl
  (testing "vocabulary.md reflects projected/elided epoch egress (rf2-6wvh5 / rf2-vr2hn)"
    (is (str/includes? @vocabulary-md "projected-record")
        (str "vocabulary.md no longer mentions projected-record — the epoch "
             "egress description may be stale (pre-rf2-6wvh5 'not dropped' "
             "claim)."))
    (is (str/includes? @vocabulary-md ":rf.epoch/sensitive?")
        "vocabulary.md must name the :rf.epoch/sensitive? epoch rollup stamp.")
    (is (str/includes? @vocabulary-md "raw-eval carve-out")
        "vocabulary.md must carry the raw-eval carve-out section.")
    ;; Guard the specific stale wording the review flagged: a claim that
    ;; epoch records do NOT carry a sensitive stamp, OR that they are NOT
    ;; dropped, would be a regression to the pre-tightening posture.
    (is (not (str/includes? @vocabulary-md "Epoch records do not carry a top-level `:sensitive?` stamp"))
        (str "vocabulary.md carries the stale 'Epoch records do not carry a "
             "top-level :sensitive? stamp' wording — epoch records carry "
             ":rf.epoch/sensitive? and are projected/redacted on egress "
             "(rf2-k2off)."))))

(deftest ops-md-raw-eval-rows-carry-privacy-carveout
  (testing "ops.md flags the raw-eval privacy carve-out for the catalogue rows"
    (is (includes-ci? @ops-md "privacy carve-out")
        (str "ops.md no longer carries the raw-eval privacy carve-out — the "
             "raw `eval-cljs` read rows (snapshot / sub-cache / trace-buffer "
             "/ epoch-history) document an un-elided path that must steer "
             "sensitive reads to the structured tools (rf2-k2off)."))
    (is (and (str/includes? @ops-md "trace-buffer")
             (str/includes? @ops-md "epoch-history"))
        "ops.md must still name the raw trace-buffer / epoch-history surfaces the carve-out governs.")))

;; ---------------------------------------------------------------------------
;; MCP-surface conformance drift
;; ---------------------------------------------------------------------------
;;
;; The skill-facing docs MUST describe the MCP-primary tool surface at its
;; LIVE cardinality, NOT the bash/Babashka shim world or v1-style op names.
;; These guards assert the current surface IS named and the shim-/v1-as-primary
;; phrasings do NOT appear. They are scoped to the user-facing prose docs
;; (README / capabilities / LOCAL_DEV / TESTING) — the legitimate harness
;; appendix in references/ops.md is out of scope here. The count is the live
;; catalogue cardinality: the descriptor manifest
;; tools/re-frame2-pair-mcp/tool-descriptors.edn carries :meta :tool-count.
;; This assertion reads that live `@tool-count` rather than a hardcoded literal
;; so the README pin stays in lockstep with the catalogue; the
;; `catalogue-count-matches-live-manifest` guard below is the fuller cross-doc
;; sweep.

(deftest readme-names-mcp-primary-tool-surface
  (testing "README names the live-cardinality MCP-primary surface, not 'fourteen ops'"
    (is (str/includes? @readme-md (str @tool-count))
        (str "README must state the MCP server catalogues " @tool-count
             " tools (the live :tool-count)."))
    (is (not (str/includes? @readme-md "fourteen ops"))
        (str "README carries the stale 'fourteen ops' count — the MCP surface "
             "is " @tool-count " tools (rf2-ojo3z).")))
  (testing "README does not claim the skill is un-exercised end-to-end"
    (is (not (str/includes? @readme-md "not yet exercised against a running"))
        (str "README carries the stale 'not yet exercised against a running "
             "re-frame2 app' status — the fixture app "
             "has landed (rf2-ojo3z)."))))

(deftest local-dev-is-mcp-primary-not-babashka-required
  (testing "LOCAL_DEV no longer lists Babashka as a skill prerequisite"
    ;; The retired bash/babashka transport was removed (rf2-dduetj), so
    ;; LOCAL_DEV must not mention Babashka at all — not as a prerequisite,
    ;; not as a harness caveat.
    (is (not (str/includes? @local-dev-md "Babashka"))
        (str "LOCAL_DEV still mentions Babashka — the bash/babashka transport "
             "was removed; the MCP server is the one implementation (rf2-dduetj)."))
    (is (not (str/includes? @local-dev-md "scripts/discover-app.sh"))
        (str "LOCAL_DEV references the retired scripts/discover-app.sh as the "
             "live first-use path — first use calls the discover-app MCP tool "
             "(rf2-ojo3z).")))
  (testing "LOCAL_DEV documents the MCP server as the transport"
    (is (str/includes? @local-dev-md "@day8/re-frame2-pair-mcp")
        "LOCAL_DEV must name the MCP server package as the skill transport.")))

(deftest capabilities-uses-current-tool-names
  (testing "capabilities.md Notes column names current MCP tools, not v1 ops"
    ;; `epoch/history` / `epoch/restore` are checked in backtick-wrapped
    ;; op form so they don't collide with the legitimate `:rf.epoch/restore-*`
    ;; error keywords (which must stay).
    (doseq [retired ["app-db/snapshot" "app-db/get" "registrar/list"
                     "registrar/describe" "subs/sample" "subs/cache"
                     "machines/list" "frames/list" "`epoch/history`"
                     "`epoch/restore`" "undo/step-back" "repl/eval"
                     "epoch-diff"]]
      (is (not (str/includes? @capabilities-md retired))
          (str "capabilities.md still names the retired v1 op `" retired
               "` — map it to the current MCP tool (rf2-ojo3z).")))
    (is (str/includes? @capabilities-md "get-path")
        "capabilities.md must name the get-path tool.")
    (is (str/includes? @capabilities-md "list-handlers")
        "capabilities.md must name the list-handlers tool.")))

(deftest testing-names-mcp-as-the-one-implementation
  (testing "TESTING.md states the MCP server is the only transport, one implementation"
    (is (str/includes? @testing-md "only skill-facing transport")
        "TESTING.md must state the MCP server is the only skill-facing transport.")
    ;; The retired bash-shim integration suite was removed (rf2-dduetj); the
    ;; docs must not resurrect the "retained harness" framing for it.
    (is (not (str/includes? @testing-md "retained harness"))
        (str "TESTING.md still frames a 'retained harness' bash-shim suite — "
             "the bash/babashka transport was removed; the live coverage moved "
             "to tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs (rf2-dduetj)."))
    (is (str/includes? @testing-md "live-e2e-fixture.cjs")
        (str "TESTING.md must point at the migrated pair-mcp live-e2e gate "
             "(tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs) as the "
             "connect/dispatch/trace/hot-reload coverage (rf2-dduetj)."))))

;; ---------------------------------------------------------------------------
;; Wire-size-budget + recipes drift
;; ---------------------------------------------------------------------------

;; Finding 3 — the render-source-coord recipe must NOT tell an agent to pass
;; the whole `:render-key` tuple to `handler-meta {kind: "view"}`. The schema
;; defines `:render-key` as `[<view-id-or-:rf.view/anonymous> <instance-token>]`;
;; `handler-meta :view` is keyed by the FIRST slot (the registered view id).
;; The recipe must steer to `(first render-key)` / the `view-id`, and
;; capabilities.md must not still call `:render-key` opaque-pending-finalisation.

(deftest render-key-recipe-uses-first-tuple-slot
  (testing "recipes.md resolves render source from the first render-key slot, not the whole tuple (rf2-a85bb2 finding 3)"
    (is (not (str/includes? @recipes-md "id: <render-key>"))
        (str "recipes.md tells an agent to pass the whole render-key tuple as "
             "the handler-meta {kind: \"view\"} id — that yields :not-registered. "
             "The id is the FIRST tuple slot (the registered view id) (rf2-a85bb2)."))
    (is (str/includes? @recipes-md "first render-key")
        (str "recipes.md must steer render-coord resolution to `(first "
             "render-key)` (the view id) for handler-meta {kind: \"view\"} "
             "(rf2-a85bb2)."))
    (is (str/includes? @recipes-md ":rf.view/anonymous")
        (str "recipes.md must handle the :rf.view/anonymous first-slot case "
             "(fall back to read-ui's :source-coord) (rf2-a85bb2).")))
  (testing "capabilities.md no longer calls :render-key opaque-pending-finalisation (rf2-a85bb2 finding 3)"
    (is (not (str/includes? @capabilities-md "opaque pending spec finalisation"))
        (str "capabilities.md still calls :render-key opaque-pending-"
             "finalisation — the schema defines a finalised tuple "
             "[<view-id> <instance-token>] (rf2-a85bb2)."))
    (is (str/includes? @capabilities-md "first render-key")
        (str "capabilities.md must point at `(first render-key)` for source-"
             "coord resolution, matching recipes.md (rf2-a85bb2)."))))

;; ---------------------------------------------------------------------------
;; Restore / hot-reload teaching drift
;; ---------------------------------------------------------------------------
;;
;; - restore-epoch reinstalls the whole frame-state (both app-db AND
;;   runtime-db partitions via replace-frame-state!), NOT app-db only. These
;;   assertions fail if an "app-db only" / "app-db is back" framing appears and
;;   assert the positive frame-state framing.
;; - tail-build returns probe diagnostics (:probe-values / :reason / :note); a
;;   timeout is NOT always a compile error. The "read the tail output / treat a
;;   timeout as a compile error" framing must not appear.

(deftest restore-not-taught-as-app-db-only
  (testing "pair skill no longer teaches restore as app-db-only (rf2-7a1mkv finding 1)"
    (doseq [[label md] [["ops.md" @ops-md]
                        ["recipes.md" @recipes-md]
                        ["README.md" @readme-md]
                        ["capabilities.md" @capabilities-md]]]
      (is (not (includes-ci? md "restore rewinds app-db only"))
          (str label " carries the stale 'restore rewinds app-db only' "
               "framing — restore reinstalls the whole frame-state, both "
               "partitions, via replace-frame-state! (rf2-7a1mkv)."))
      (is (not (includes-ci? md "old snapshots in app-db"))
          (str label " carries the stale 'old snapshots in app-db' wording — "
               "machine snapshots live in the runtime-db partition "
               "([:rf.runtime/machines …]) (rf2-7a1mkv).")))
    (is (str/includes? @ops-md "frame-state")
        (str "ops.md no longer positively teaches restore as a frame-state "
             "rewind (both partitions) (rf2-7a1mkv).")))
  (testing "ops.md restore caveat names runtime-db revival + the side-effect limit"
    (is (and (str/includes? @ops-md "frame-state")
             (includes-ci? @ops-md "runtime-db"))
        (str "ops.md restore caveat must say restore rewinds frame-state "
             "(both partitions incl. runtime-db) while NOT reversing side "
             "effects / transient host state (rf2-7a1mkv)."))))

(deftest hot-reload-branches-on-probe-values-not-blanket-compile-error
  (testing "ops.md hot-reload branches on :probe-values / :reason, not 'all timeouts are compile errors' (rf2-7a1mkv finding 2)"
    (let [hr (section-from @ops-md "Hot-reload coordination")]
      (is (str/includes? hr ":probe-values")
          (str "ops.md hot-reload guidance no longer mentions `:probe-values` "
               "— the diagnostic tail-build returns on timeout so the agent "
               "can tell a stuck probe from a compile error (rf2-7a1mkv)."))
      (is (str/includes? hr ":probe-errored")
          (str "ops.md hot-reload guidance no longer covers `:probe-errored` "
               "as a malformed-probe path distinct from a compile error "
               "(rf2-7a1mkv)."))
      (is (not (str/includes? hr "treat that as a compile error in the user's code — read the tail output"))
          (str "ops.md still tells the agent to treat any tail-build timeout "
               "as a compile error and read the tail output — tail-build does "
               "not tail logs; branch on :reason / :probe-values (rf2-7a1mkv)."))
      (is (or (includes-ci? hr "does not tail")
              (includes-ci? hr "historical"))
          (str "ops.md hot-reload guidance must note tail-build does NOT "
               "actually tail the shadow-cljs server log (rf2-7a1mkv).")))))

;; ---------------------------------------------------------------------------
;; Hot-reload baseline order (rf2-1f60u)
;; ---------------------------------------------------------------------------
;;
;; tail-build's comparison value is the CALLER's pre-edit capture — the
;; baseline — so a reload landing before the first sample still reads as
;; success. The protocol must teach capture-BEFORE-edit and pass `baseline`
;; into tail-build; a post-edit self-baseline framing must not reappear.

(deftest hot-reload-teaches-pre-edit-baseline
  (testing "ops.md hot-reload protocol captures a pre-edit baseline and passes it to tail-build (rf2-1f60u)"
    (let [hr (section-from @ops-md "Hot-reload coordination")]
      (is (includes-ci? hr "baseline")
          (str "ops.md hot-reload guidance no longer names the pre-edit "
               "baseline tail-build compares against (rf2-1f60u)."))
      (is (re-find #"(?i)capture[^.\n]{0,120}(pre-edit|before)" hr)
          (str "ops.md hot-reload guidance must instruct capturing the "
               "probe's value BEFORE the edit (rf2-1f60u)."))
      (is (str/includes? hr ":missing-baseline")
          (str "ops.md hot-reload guidance must cover the :missing-baseline "
               "refusal branch (rf2-1f60u)."))
      (is (re-find #"(?i)(before or after|lands? before)[^.\n]{0,80}(first (probe )?sample|first probe)" hr)
          (str "ops.md must state the invariant: a reload is recognized "
               "whether it lands before or after the first sample "
               "(rf2-1f60u)."))))
  (testing "recipes.md permanent-change step carries the baseline capture (rf2-1f60u)"
    (is (includes-ci? @recipes-md "baseline")
        (str "recipes.md's permanent-change step no longer passes the "
             "pre-edit baseline into tail-build (rf2-1f60u).")))
  (testing "SKILL.md cardinal rule orders capture before the edit (rf2-1f60u)"
    (is (includes-ci? @skill-md "baseline")
        (str "SKILL.md's source-edit cardinal rule no longer names the "
             "pre-edit baseline (rf2-1f60u)."))))

;; ---------------------------------------------------------------------------
;; No-probe mode is a DELAY, never post-edit evidence (rf2-1f60u audit)
;; ---------------------------------------------------------------------------
;;
;; A probe-less tail-build samples nothing and compares nothing, yet still
;; returns {:ok? true :soft? true}. So the proceed-gate must require
;; :soft? false: an "if you don't know a good probe, omit it" bullet sitting
;; inside a gate that accepts any {:ok? true} authorizes dispatching against
;; stale code, which is exactly the contradiction the rf2-1f60u audit found.

(deftest no-probe-soft-wait-is-not-post-edit-evidence
  (testing "ops.md's post-edit gate requires :soft? false, not any {:ok? true} (rf2-1f60u)"
    (let [hr (section-from @ops-md "Hot-reload coordination")]
      (is (str/includes? hr ":soft? false")
          (str "ops.md's proceed-gate no longer requires `:soft? false` — a "
               "probe-less 300ms wait returns {:ok? true :soft? true} and "
               "would satisfy a bare {:ok? true} gate (rf2-1f60u)."))
      (is (not (str/includes? hr "the tool falls back to a 300ms timer"))
          (str "ops.md's no-probe bullet reads as a sanctioned substitute for "
               "the probe again. It must be fenced as a delay only, never as "
               "evidence that unlocks post-edit dispatch (rf2-1f60u)."))
      (is (re-find #"(?i)(never evidence|not evidence|a delay, not)" hr)
          (str "ops.md no longer says a probe-less wait is a delay rather "
               "than evidence that the reload landed (rf2-1f60u).")))))

;; ---------------------------------------------------------------------------
;; snapshot uses plural `frames`, not singular `frame`
;; ---------------------------------------------------------------------------
;;
;; The `snapshot` MCP tool reads only the plural `:frames` arg
;; (snapshot.cljs parses `(args/parse-frames-arg (wire/arg ... :frames))`)
;; — it has NO singular `frame` arg, unlike dispatch / get-path / read-sub.
;; A variant-diff recipe calling `snapshot {frame: ...}` (singular) would be
;; ignored by the tool: it would snapshot the operating frame twice and
;; produce a false comparison. These guards fail if a snapshot recipe uses the
;; singular form.

(deftest snapshot-recipe-uses-plural-frames-not-singular-frame
  (testing "the variant-diff recipe selects frames via plural `frames`, not singular `frame` (rf2-hf7m9j)"
    (let [section (recipe-section @recipes-md "Diff two variants")]
      (is (seq section)
          "recipes.md missing the 'Diff two variants' heading.")
      (is (str/includes? section "snapshot {frames:")
          (str "the variant-diff recipe no longer calls `snapshot "
               "{frames: [...]}` (plural). snapshot has no singular "
               "`frame` arg — it parses only :frames (snapshot.cljs) — so "
               "a singular call snapshots the operating frame twice and "
               "yields a false comparison (rf2-hf7m9j finding 2)."))
      (is (not (re-find #"snapshot \{frame:" section))
          (str "the variant-diff recipe calls `snapshot {frame: ...}` "
               "(singular) — the tool ignores that arg. Use `snapshot "
               "{frames: [\":...\"]}` (plural), or pin one operating frame "
               "at a time (rf2-hf7m9j finding 2).")))))

;; ---------------------------------------------------------------------------
;; Named state-rewrite writes route through the dedicated gated tools
;; ---------------------------------------------------------------------------
;;
;; The two write-authority tools `restore-epoch` + `replace-app-db` are the
;; CANONICAL path for time-travel undo + state injection — both are
;; allow-listed (the server's `--allow-writes` gate, not the allow-list, is
;; the write boundary). The raw eval forms (`(rf/restore-epoch! …)` /
;; `app-db-reset!`) are the BACKSTOP only. These guards fail if the skill
;; teaches the eval form as the default-reachable write path, or drops the two
;; tools from the allow-list.

(deftest write-tools-are-allow-listed
  (testing "SKILL.md allow-lists both dedicated write tools (rf2-230ekq)"
    (is (str/includes? @skill-md "mcp__re-frame2-pair__restore-epoch")
        (str "SKILL.md allowed-tools no longer lists "
             "mcp__re-frame2-pair__restore-epoch — the dedicated time-travel "
             "tool is the canonical named-write path (rf2-230ekq)."))
    (is (str/includes? @skill-md "mcp__re-frame2-pair__replace-app-db")
        (str "SKILL.md allowed-tools no longer lists "
             "mcp__re-frame2-pair__replace-app-db — the dedicated "
             "state-injection tool is the canonical named-write path "
             "(rf2-230ekq)."))))

(deftest named-writes-prefer-dedicated-tool-not-default-eval
  (testing "the skill no longer frames the raw eval write forms as the DEFAULT-reachable path (rf2-230ekq)"
    (doseq [[label md] [["SKILL.md" @skill-md]
                        ["ops.md" @ops-md]
                        ["recipes.md" @recipes-md]
                        ["mcp-transport.md (via recipes/ops links)" @ops-md]]]
      (is (not (re-find #"(?i)default-reachable\s+write\s+path" md))
          (str label " still calls the raw eval form the 'default-reachable "
               "write path' — the dedicated `restore-epoch` / `replace-app-db` "
               "tools are now the canonical path; eval is the backstop "
               "(rf2-230ekq).")))
    ;; The Experiment-loop recipe's restore step must call the dedicated tool,
    ;; not the eval form, as its primary invocation.
    (let [section (recipe-section @recipes-md "Experiment loop")]
      (is (seq section) "recipes.md missing the 'Experiment loop' heading.")
      (is (str/includes? section "mcp__re-frame2-pair__restore-epoch {epoch-id:")
          (str "the Experiment-loop restore step no longer leads with the "
               "dedicated `restore-epoch {epoch-id: …}` tool — the eval form "
               "is the backstop, not the default (rf2-230ekq)."))))
  (testing "the eval write forms are kept as an explicitly-labelled backstop (rf2-230ekq)"
    ;; The backstop must still be documented (gate-OFF server fallback), but
    ;; framed as such — not removed entirely.
    (is (includes-ci? @ops-md "backstop")
        (str "ops.md no longer labels the raw eval restore/reset forms as a "
             "BACKSTOP — they must remain documented for a gate-OFF server but "
             "framed as the fallback, not the default (rf2-230ekq)."))))

;; ---------------------------------------------------------------------------
;; Single-host boundary (rf2-p1keh)
;; ---------------------------------------------------------------------------
;;
;; The skill's contract is ONE attached browser runtime. Story variants are the
;; surface that used to breach it: seven `mcp__re-frame2-story-mcp__*` tools
;; were allow-listed and the Story leaves taught compositions that ran a
;; variant in story-mcp's headless JVM registry while Pair read the browser —
;; two different frames wearing one keyword.
;;
;; `scripts/check_skill_mcp_drift.py`'s single-host axis pins the FRONTMATTER.
;; These guards pin the PROSE, which no gate reads: the surviving Story leaf
;; must teach the browser route, and no doc may name the other server's tool
;; prefix. `story-mcp` in prose is fine and expected — the leaf routes headless
;; work out to it by name. The banned thing is a callable `mcp__...__` entry.

(def ^:private story-mcp-tool-prefix "mcp__re-frame2-story-mcp__")

(deftest story-work-stays-on-the-attached-runtime
  (testing "the Story leaf teaches the browser route: eval-cljs + await + a Pair frame read (rf2-p1keh)"
    (let [md @stories-md]
      (is (str/includes? md "mcp__re-frame2-pair__eval-cljs")
          (str "references/stories.md no longer names "
               "`mcp__re-frame2-pair__eval-cljs` — the attached browser heap "
               "is reached through eval-cljs, and it is the only Story door "
               "this skill has (rf2-p1keh)."))
      (is (str/includes? md "re-frame.story/run-variant")
          (str "references/stories.md no longer names "
               "`re-frame.story/run-variant` — running a variant in the open "
               "app is a call into the app's OWN Story registry (rf2-p1keh)."))
      (is (str/includes? md "await")
          (str "references/stories.md no longer tells the caller to `await` "
               "the run — `run-variant` returns a JS Promise in the browser, "
               "so a plain eval hands back an unresolved thenable "
               "(rf2-p1keh)."))
      (is (or (str/includes? md "set-operating-frame")
              (str/includes? md "mcp__re-frame2-pair__read-sub"))
          (str "references/stories.md no longer follows the run with an "
               "ordinary Pair frame op against the SAME runtime — the "
               "variant-id-is-frame-id identity is what makes the browser "
               "route complete (rf2-p1keh)."))))

  (testing "no live-session doc grants or calls a second MCP server (rf2-p1keh)"
    (doseq [[label md] [["SKILL.md"                skill-md]
                        ["references/stories.md"   stories-md]
                        ["references/recipes.md"   recipes-md]
                        ["references/ops.md"       ops-md]
                        ["references/vocabulary.md" vocabulary-md]]]
      (is (not (str/includes? @md story-mcp-tool-prefix))
          (str label " names a `" story-mcp-tool-prefix "` tool. story-mcp is "
               "a headless same-JVM host with no bridge to the browser, so a "
               "variant it runs is NOT the frame Pair reads — the two share a "
               "keyword, not an object. Drive variants through eval-cljs over "
               "`re-frame.story/*` instead, and route explicitly headless work "
               "out to tools/story-mcp/ (rf2-p1keh).")))
    ;; Control: the census pattern above must be able to find something.
    ;; A `str/includes?` against a token nothing carries answers "absent" in
    ;; the same voice whether the rule holds or the token is misspelt, so
    ;; exercise the SAME shape against the prefix that IS present.
    (is (str/includes? @skill-md "mcp__re-frame2-pair__")
        (str "SKILL.md carries no `mcp__re-frame2-pair__` entry at all — the "
             "single-host assertions above are therefore vacuous, not "
             "satisfied (rf2-p1keh)."))))

(deftest story-leaf-is-single-not-a-pair
  (testing "the overlapping stories.md + variant-as-frame.md pair collapsed to one leaf (rf2-p1keh)"
    (is (not (.exists (io/file skill-root "references/variant-as-frame.md")))
        (str "references/variant-as-frame.md is back. Its content — the "
             "variant-id-is-frame-id identity, per-variant isolation, the "
             "mount/reset/destroy gotchas — lives in references/stories.md, "
             "which is the ONE Story leaf the router points at (rf2-p1keh).")))
  (testing "the surviving leaf kept what the merge absorbed (rf2-p1keh)"
    (let [md @stories-md]
      (doseq [[what token] [["the variant-id/frame-id identity" "variant-id IS the frame-id"]
                            ["the frame-allocation call"        "rf/make-frame"]
                            ["per-variant isolation"            "reset-frame!"]
                            ["the unmount gotcha"               "destroy-frame!"]
                            ["cross-frame diff"                 "frame-diff"]]]
        (is (str/includes? md token)
            (str "references/stories.md no longer covers " what " (`" token
                 "`) — the merge was meant to absorb variant-as-frame.md, not "
                 "drop it (rf2-p1keh).")))))
  (testing "the router points at the one surviving leaf (rf2-p1keh)"
    (is (str/includes? @skill-md "references/stories.md")
        "SKILL.md no longer routes to references/stories.md.")
    (is (not (str/includes? @skill-md "variant-as-frame.md"))
        "SKILL.md still routes to the deleted references/variant-as-frame.md.")))

;; ---------------------------------------------------------------------------
;; Published call ARITY, read against the defining facade
;; ---------------------------------------------------------------------------
;;
;; Every guard above proves a recipe still NAMES an op. None of them says
;; whether the published call would RUN. references/stories.md shipped
;; `(re-frame.story/start-recording!)` and `(re-frame.story/gen-play-snippet)`
;; with zero args against a facade requiring `[variant-id]` and
;; `[events opts]` — an arity error before capture or codegen happens at all —
;; and every structural check stayed green through the merge (rf2-p1keh,
;; audit of PR #8951). These guards read the defining `defn` and the
;; published call, and relate the two.

(def ^:private story-facade
  ;; The public Story facade, two levels up from skill-root (repo `tools/story/`).
  ;; Newlines are normalised: the repo stores LF but a Windows checkout under
  ;; `core.autocrlf=true` hands back CRLF, and an anchor ending in "\n" then
  ;; matches nothing — silently, with the guards below reading as vacuous.
  (delay (-> (io/file skill-root ".." ".." "tools" "story" "src"
                      "re_frame" "story.cljc")
             slurp
             (str/replace "\r\n" "\n"))))

(defn- defn-form
  "Source text of the top-level `(defn <sym> …)` form, from its opening
  paren to the blank line before the next top-level form. nil when the
  facade does not declare `sym` on its own line."
  [src sym]
  (when-let [start (str/index-of src (str "(defn " sym "\n"))]
    (let [tail (subs src start)
          end  (str/index-of tail "\n\n(")]
      (if end (subs tail 0 end) tail))))

(defn- zero-arity?
  "True iff `defn-src` declares a zero-arity — a bare `[]` arg vector alone
  on its line, or an `([] …)` multi-arity clause."
  [defn-src]
  (boolean (re-find #"(?m)^\s*\[\]\s*$|^\s*\(\[\]" defn-src)))

(deftest recorder-recipe-calls-match-the-facade-arity
  (testing "the facade declares the arities the leaf must satisfy (rf2-p1keh)"
    (let [src @story-facade]
      ;; Control. `zero-arity?` answering "false" for every input would clear
      ;; the two assertions below in the same voice as a correct facade, so
      ;; exercise it against the one recorder entry point that IS zero-arity.
      (is (some? (defn-form src "stop-recording!"))
          (str "could not locate `(defn stop-recording!` in the Story facade "
               "— the arity guards below are vacuous, not satisfied "
               "(rf2-p1keh)."))
      (is (zero-arity? (defn-form src "stop-recording!"))
          (str "`re-frame.story/stop-recording!` no longer reads as zero-arity, "
               "so `zero-arity?` is not measuring what it claims and the "
               "guards below prove nothing (rf2-p1keh)."))
      (doseq [sym ["start-recording!" "gen-play-snippet"]]
        (let [form (defn-form src sym)]
          (is (some? form)
              (str "could not locate `(defn " sym "` in the Story facade — "
                   "references/stories.md publishes a call to it, so either "
                   "the fn moved or this guard is reading the wrong file "
                   "(rf2-p1keh)."))
          (is (not (zero-arity? form))
              (str "`re-frame.story/" sym "` now declares a zero-arity. If that "
                   "is deliberate, references/stories.md may simplify its "
                   "recorder sequence; until then the leaf must keep passing "
                   "arguments (rf2-p1keh)."))))))

  (testing "references/stories.md publishes no zero-arg recorder call (rf2-p1keh)"
    (let [md @stories-md]
      (doseq [sym ["start-recording!" "gen-play-snippet"]]
        (is (not (str/includes? md (str "(re-frame.story/" sym ")")))
            (str "references/stories.md publishes `(re-frame.story/" sym ")` "
                 "with zero args. The facade requires arguments, so following "
                 "the recipe throws before it captures anything (rf2-p1keh).")))
      (is (str/includes? md "(re-frame.story/start-recording! :story.")
          (str "references/stories.md no longer passes a variant id to "
               "`start-recording!` — the recording's address IS the variant "
               "frame (rf2-p1keh)."))
      (is (str/includes? md ":variant-id :story.")
          (str "references/stories.md no longer supplies `gen-play-snippet`'s "
               "required `:variant-id` opt — the snippet has no id to render "
               "the `reg-variant` form against (rf2-p1keh).")))))

;; ---------------------------------------------------------------------------
;; Catalogue-cardinality drift
;; ---------------------------------------------------------------------------
;;
;; The name-level gate (scripts/check_skill_mcp_drift.py) compares the
;; SKILL.md allow-list against the server descriptor SET — it does NOT read the
;; PROSE cardinality, so a doc can carry a stale tool count even when the
;; allow-list is correct. These guards anchor every doc that states a count to
;; the LIVE manifest `:tool-count` (read from
;; tools/re-frame2-pair-mcp/tool-descriptors.edn) and fail when a doc carries a
;; stale number. Add a doc to `count-docs` if it states the count.

(def ^:private count-docs
  ;; [label deref] for every skill/re-authoring doc that states the tool count
  ;; in prose. Each MUST name the current count and MUST NOT name a stale one.
  [["README.md"                 readme-md]
   ["SKILL.md"                  skill-md]
   ["STATUS.md"                 (delay (slurp-rel "STATUS.md"))]
   ["references/mcp-transport.md" (delay (slurp-rel "references/mcp-transport.md"))]
   ["references/vocabulary.md"  vocabulary-md]
   ["docs/capabilities.md"      capabilities-md]
   ["docs/initial-spec.md"      (delay (slurp-rel "docs/initial-spec.md"))]
   ["spec/inputs.md"            (delay (slurp-rel "spec/inputs.md"))]
   ["spec/design.md"            (delay (slurp-rel "spec/design.md"))]
   ["spec/authoring-prompt.md"  (delay (slurp-rel "spec/authoring-prompt.md"))]])

(deftest catalogue-count-matches-live-manifest
  (let [live  @tool-count
        live-s (str live)]
    (testing (str "every count-stating doc names the live " live "-tool catalogue")
      (doseq [[label md] count-docs]
        (is (str/includes? @md live-s)
            (str label " no longer states the live Pair-MCP tool count of "
                 live " (from tool-descriptors.edn :tool-count) — update the "
                 "prose count when the catalogue changes (rf2-sdudwy)."))))
    (testing "no count-stating doc carries a stale tool count"
      ;; Sweep the counts the catalogue has actually held; the LIVE count is
      ;; exempt. This catches a doc that was missed when the catalogue
      ;; grew/shrank. The trajectory, from tool-descriptors.edn's history:
      ;; 30 -> 35 -> 33 -> 29 -> 30, so 33 and 35 are real stale counts a
      ;; missed doc could still carry (rf2-je5ki item 8). 31/32 are NOT
      ;; swept -- the catalogue never held them, so no doc can be stale at
      ;; one, and sweeping a count that never shipped only adds noise.
      (doseq [[label md] count-docs
              stale ["26" "27" "28" "29" "30" "33" "35"]
              :when (not= stale live-s)]
        ;; Match the count only in a TOOL-catalogue framing ("NN tool" /
        ;; "NN-tool" / "all NN") so unrelated numerals (dates, line counts,
        ;; failure-mode counts) don't trip the guard.
        (let [pat (re-pattern (str "(?i)\\b" stale "[ -]tools?\\b|\\ball " stale "\\b"))]
          (is (not (re-find pat @md))
              (str label " carries a stale " stale "-tool catalogue count — "
                   "the live surface is " live " tools (rf2-sdudwy).")))))))

;; ---------------------------------------------------------------------------
;; Gated-write allow-list policy drift
;; ---------------------------------------------------------------------------
;;
;; The policy (scripts/check_skill_mcp_drift.py: `intentional_server_only`
;; is empty for the re-frame2-pair mapping): EVERY server tool — including a
;; `--allow-writes`-gated write tool — is allow-listed and fenced at the
;; SERVER. Re-authoring docs telling a future author to keep gated write tools
;; OFF the allow-list and put them in `intentional_server_only` would
;; contradict the live gate and regenerate wrong guidance. This guard fails if
;; that contradictory policy appears in the re-authoring/meta docs.

(deftest gated-write-tools-stay-allow-listed-in-reauthoring-docs
  (testing "re-authoring docs do NOT route gated write tools into intentional_server_only / off the allow-list"
    (doseq [[label md] [["spec/inputs.md"           (delay (slurp-rel "spec/inputs.md"))]
                        ["spec/design.md"           (delay (slurp-rel "spec/design.md"))]
                        ["spec/authoring-prompt.md" (delay (slurp-rel "spec/authoring-prompt.md"))]
                        ["docs/initial-spec.md"     (delay (slurp-rel "docs/initial-spec.md"))]]]
      (let [text @md]
        ;; Flag the STALE-policy framing: a write tool that "stay(s) off" the
        ;; allow-list, or that should "go in" `intentional_server_only`. The
        ;; positive-verb requirement ("stay(s) off" / "go(es) in") deliberately
        ;; does NOT match the CORRECT framing that says a gated write tool is
        ;; "never excluded into `intentional_server_only`" — the negating prose
        ;; lacks the placement verbs.
        (is (not (re-find #"(?is)write tool[^.\n]{0,90}(stays? off[^.\n]{0,30}allow-list|go(?:es)? (?:in)?to?[^.\n]{0,30}intentional_server_only)" text))
            (str label " teaches that a `--allow-writes`-gated write tool stays "
                 "OFF the allow-list / goes in `intentional_server_only` — the "
                 "current policy allow-lists EVERY server tool (incl. gated "
                 "write tools) and fences them at the server's `--allow-writes` "
                 "launch gate; `intentional_server_only` is empty for the "
                 "re-frame2-pair mapping (rf2-sdudwy)."))))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'prompt-regression-test)]
 (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
