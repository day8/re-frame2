;;;; tests/prompts/prompt_regression_test.clj — prompt-regression for the
;;;; canonical re-frame2-pair conversations.
;;;;
;;;; Per `docs/TESTING.md` §4 the goal of prompt regression is to catch
;;;; SILENT DRIFT in the skill's recipes as the skill itself evolves.
;;;; A conversation-driving harness (Claude in the loop) is the *fidelity-
;;;; ideal* version of this surface; the v1 here is the structural
;;;; substrate that catches the cheapest class of drift:
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

;; User-facing docs + the streaming + variant leaves the MCP-surface
;; conformance drift guards (rf2-ojo3z) assert against.
(def ^:private readme-md (delay (slurp-rel "README.md")))
(def ^:private capabilities-md (delay (slurp-rel "docs/capabilities.md")))
(def ^:private local-dev-md (delay (slurp-rel "docs/LOCAL_DEV.md")))
(def ^:private testing-md (delay (slurp-rel "docs/TESTING.md")))
(def ^:private streaming-md (delay (slurp-rel "references/streaming-subscriptions.md")))
(def ^:private variant-md (delay (slurp-rel "references/variant-as-frame.md")))
(def ^:private wire-size-md (delay (slurp-rel "references/wire-size-budget.md")))

;; ---------------------------------------------------------------------------
;; The canonical-prompts table
;; ---------------------------------------------------------------------------
;;
;; Five representative prompts (`docs/TESTING.md` §4 calls them out).
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
;; for the same op (MCP tool name, bash shim name, runtime fn name).
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
 ["reg-event-fx" "reg-event-db"]]}

 {:id :where-in-code
 :prompt "Where in the code does this button come from?"
 :recipe-anchor "Where in the code"
 :must-mention [["dom/source-at" "source-at"]
 ["data--coord" "source-coord"]]}])

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
;; Privacy-contract drift (rf2-k2off)
;; ---------------------------------------------------------------------------
;;
;; The skill-facing privacy guarantee MUST match what the pair-mcp tools
;; actually enforce:
;;   - The guarantee is scoped to the STRUCTURED MCP read/stream tools,
;;     NOT to raw `eval-cljs` (which is default-ON and returns its value
;;     un-walked, regardless of --allow-sensitive-reads).
;;   - Epoch egress (trace-window / watch-epochs / the :epoch streaming
;;     topic) is REDACTED/ELIDED by default via projected-record /
;;     elide-wire-value (rf2-6wvh5 / rf2-vr2hn) — not shipped raw.
;; These assertions fail if the docs drift back to the over-broad
;; "sensitive data does not cross the LLM boundary by default" claim or
;; the stale "epoch records are not dropped / carry no sensitive stamp"
;; wording the rf2-k2off review caught.

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
             "structured MCP reads/streams (rf2-k2off)."))))

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
;; MCP-surface conformance drift (rf2-ojo3z)
;; ---------------------------------------------------------------------------
;;
;; The skill-facing docs MUST describe the MCP-primary 28-tool surface, NOT
;; the retired bash/Babashka shim world or v1-style op names. These guards
;; assert the current surface IS named and the specific retired-as-primary
;; phrasings the rf2-ojo3z review caught do NOT come back. They are scoped to
;; the user-facing prose docs (README / capabilities / LOCAL_DEV / TESTING) —
;; the legitimate harness appendix in references/ops.md is out of scope here.

(deftest readme-names-mcp-primary-28-tool-surface
  (testing "README names the 28-tool MCP-primary surface, not 'fourteen ops'"
    (is (str/includes? @readme-md "28")
        "README must state the MCP server catalogues 28 tools.")
    (is (not (str/includes? @readme-md "fourteen ops"))
        (str "README carries the stale 'fourteen ops' count — the MCP surface "
             "is 28 tools (rf2-ojo3z).")))
  (testing "README does not claim the skill is un-exercised end-to-end"
    (is (not (str/includes? @readme-md "not yet exercised against a running"))
        (str "README carries the stale 'not yet exercised against a running "
             "re-frame2 app' status — the fixture app + push-mode streaming "
             "have landed (rf2-ojo3z)."))))

(deftest local-dev-is-mcp-primary-not-babashka-required
  (testing "LOCAL_DEV no longer lists Babashka as a skill prerequisite"
    (is (str/includes? @local-dev-md "Babashka is not a skill requirement")
        (str "LOCAL_DEV must scope Babashka to the retained harness shims, not "
             "list it as a skill prerequisite (rf2-ojo3z)."))
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

(deftest testing-scopes-shim-suite-as-harness-only
  (testing "TESTING.md frames the bash-shim suite as retained harness, not the live transport"
    (is (str/includes? @testing-md "retained harness")
        (str "TESTING.md must mark the bash-shim integration suite as a "
             "retained harness (not the skill's live MCP transport) "
             "(rf2-ojo3z)."))
    (is (str/includes? @testing-md "only skill-facing transport")
        "TESTING.md must state the MCP server is the only skill-facing transport.")))

(deftest streaming-progress-payload-matches-impl
  (testing "streaming-subscriptions.md documents _meta.data + both payload slots (finding 2)"
    (is (str/includes? @streaming-md "_meta.data")
        (str "streaming-subscriptions.md must place the structured drop counts "
             "under _meta.data, not a top-level `data` slot (rf2-ojo3z)."))
    (is (and (str/includes? @streaming-md ":cascades")
             (str/includes? @streaming-md ":events"))
        (str "streaming-subscriptions.md must document BOTH the :cascades "
             "(trace/fx/error) and :events (epoch/frameless) payload slots."))
    (is (str/includes? @streaming-md "EDN-printed **map**")
        (str "streaming-subscriptions.md must state `message` is an EDN map "
             "(with :sub-id + :cascades/:events), not a bare vector "
             "(rf2-ojo3z)."))))

(deftest subscribe-not-scoped-by-operating-frame-pin
  (testing "variant-as-frame.md + SKILL.md carve subscribe out of operating-frame scoping (finding 3)"
    (is (str/includes? @variant-md "filter {:frame")
        (str "variant-as-frame.md must say subscribe is scoped via "
             "filter {:frame ...}, not the operating-frame pin (rf2-ojo3z)."))
    (is (and (str/includes? @skill-md "subscribe")
             (str/includes? @skill-md "no `frame` arg"))
        (str "SKILL.md must state subscribe has no `frame` arg so the "
             "operating-frame pin does not scope a streaming subscription "
             "(rf2-ojo3z)."))))

;; ---------------------------------------------------------------------------
;; Independent-review findings (rf2-a85bb2)
;; ---------------------------------------------------------------------------
;;
;; Finding 2 — wire-size-budget.md must describe the SAME topic-dependent
;; subscribe payload slot as streaming-subscriptions.md: `:events` for the
;; flat topics (epoch/frameless) and `:cascades` for the cascade-bundle
;; topics (trace/fx/error). A host decoding subscribe dedup off the
;; size-budget leaf that only knew `:events` would leave trace/fx/error
;; cascade ticks unexpanded. Mirrors the streaming-subscriptions guard above.

(deftest wire-size-budget-names-both-subscribe-slots
  (testing "wire-size-budget.md subscribe dedup names both :events and :cascades (rf2-a85bb2 finding 2)"
    (is (and (str/includes? @wire-size-md ":cascades")
             (str/includes? @wire-size-md ":events"))
        (str "wire-size-budget.md must document the topic-dependent subscribe "
             "payload slot — :events (epoch/frameless) AND :cascades "
             "(trace/fx/error) — not :events alone, or a host will leave "
             "cascade-bundle dedup ticks unexpanded (rf2-a85bb2)."))))

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
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'prompt-regression-test)]
 (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
