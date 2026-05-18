;;;; tests/prompts/prompt_regression_test.clj — prompt-regression for the
;;;; canonical re-frame2-pair conversations (rf2-cxik).
;;;;
;;;; Per `docs/TESTING.md` §4 the goal of prompt regression is to catch
;;;; SILENT DRIFT in the skill's recipes as the skill itself evolves.
;;;; A conversation-driving harness (Claude in the loop) is the *fidelity-
;;;; ideal* version of this surface; the v1 here is the structural
;;;; substrate that catches the cheapest class of drift:
;;;;
;;;;   - The canonical prompt's *recipe* still lives in `references/recipes.md`
;;;;     under the expected heading.
;;;;   - The recipe still names the expected op(s) — so a renamed shim or a
;;;;     removed runtime helper breaks the test.
;;;;
;;;; A future bead lands the conversation-driving variant on top of this
;;;; substrate (the canonical-prompts table is the source-of-truth in
;;;; either case).
;;;;
;;;; Run:    bb tests/prompts/prompt_regression_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

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
      (.getParentFile)   ;; tests/prompts/
      (.getParentFile)   ;; tests/
      (.getParentFile))) ;; skills/re-frame2-pair/

(defn- slurp-rel [rel]
  (slurp (io/file skill-root rel)))

(def ^:private recipes-md  (delay (slurp-rel "references/recipes.md")))
(def ^:private ops-md      (delay (slurp-rel "references/ops.md")))
(def ^:private skill-md    (delay (slurp-rel "SKILL.md")))
(def ^:private errors-md   (delay (slurp-rel "references/errors.md")))
(def ^:private hot-reload  (delay (slurp-rel "references/ops.md")))

;; ---------------------------------------------------------------------------
;; The canonical-prompts table
;; ---------------------------------------------------------------------------
;;
;; Five representative prompts (`docs/TESTING.md` §4 calls them out).
;; Each row carries:
;;
;;   :id            stable identifier for cross-referencing in beads/PRs
;;   :prompt        the user-spoken request
;;   :recipe-anchor a substring expected in references/recipes.md's
;;                  heading — proves the recipe still exists
;;   :must-mention  ops the recipe is expected to name. Each is an
;;                  alternation of phrasings; the test passes if AT
;;                  LEAST ONE alternative appears in recipes.md.
;;
;; ALTERNATION RATIONALE — re-frame2-pair's vocabulary admits multiple surfaces
;; for the same op (MCP tool name, bash shim name, runtime fn name).
;; The regression should fire when ALL of them disappear, not when one
;; rename happens. The list per row is the set the recipe *currently*
;; uses; if a future edit drops one and adds another, the test still
;; passes — as long as something covering the same idea is named.

(def canonical-prompts
  [{:id            :app-db-snapshot
    :prompt        "What's in app-db under :user/profile?"
    :recipe-anchor "What's in `app-db`"
    :must-mention  [["app-db/snapshot" "app-db/get" "snapshot"]]}

   {:id            :trace-explain-dispatch
    :prompt        "Trace `[:cart/apply-coupon \"SPRING25\"]`"
    :recipe-anchor "Explain this dispatch"
    :must-mention  [["dispatch-and-collect" "trace/dispatch-and-collect"]
                    [":rf/epoch-record" "epoch-record"]
                    [":sub-runs"]
                    [":renders"]]}

   {:id            :why-no-update
    :prompt        "Why didn't the header update after `[:profile/save ...]`?"
    :recipe-anchor "Why didn't my view update"
    :must-mention  [[":sub-runs"]
                    ["trace/last-epoch" "trace/last-pair-epoch" "last-epoch"]
                    ["equality" "cache-hit"]]}

   {:id            :experiment-loop
    :prompt        "Iterate on the cart handler until expired coupons are rejected"
    :recipe-anchor "Experiment loop"
    :must-mention  [["dispatch-and-collect"]
                    ["restore-epoch"]
                    ["reg-event-fx" "reg-event-db"]]}

   {:id            :where-in-code
    :prompt        "Where in the code does this button come from?"
    :recipe-anchor "Where in the code"
    :must-mention  [["dom/source-at" "source-at"]
                    ["data-rf2-source-coord" "source-coord"]]}])

;; ---------------------------------------------------------------------------
;; Assertions
;; ---------------------------------------------------------------------------

(defn- recipe-section
  "Return the chunk of recipes.md starting at the heading matching
   `anchor` and ending at the next `## ` heading. Empty if no match."
  [md anchor]
  (let [pat (re-pattern (str "(?ms)## .*" (java.util.regex.Pattern/quote anchor) ".*?(?=^## |\\z)"))]
    (or (some-> (re-find pat md) ) "")))

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
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'prompt-regression-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
