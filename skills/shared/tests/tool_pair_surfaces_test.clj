;;;; tests/tool_pair_surfaces_test.clj — structural regression for the
;;;; shared Tool-Pair surface enumeration.
;;;;
;;;; `tool-pair-surfaces.md` is the single shared-corpus pointer at the
;;;; consumer-facing Tool-Pair surfaces. The skills/shared correctness
;;;; review (rf2-eca6x.1) found its direct-read entries named raw runtime
;;;; reads (`app-db-value`, `compute-sub`, `sub-cache`) WITHOUT carrying
;;;; the MUST-level off-box wire-egress contract from spec/Tool-Pair.md —
;;;; so a consuming skill routing/drafting upstream Tool-Pair work from
;;;; this leaf could describe direct reads as raw values rather than
;;;; `rf/elide-wire-value`-scrubbed egress with sensitive/large defaults
;;;; suppressed. The existing `retro_protocol_test.clj` suite does not
;;;; cover this leaf, so the drift was unguarded.
;;;;
;;;; rf2-c9xgp follow-up: the wire-surface list was ALSO stale against the
;;;; current re-frame2-pair-mcp catalogue — it named a standalone
;;;; `get-app-db` / `sub-cache` tool that the catalogue does not ship, and
;;;; omitted the real subscription-read egress (`read-sub`,
;;;; `list-subscriptions` with `:include-values`) and `dispatch-dry-run`,
;;;; all under the same `--allow-sensitive-reads` fail-closed posture.
;;;; This suite now pins the CURRENT privacy-relevant tool names so a
;;;; future catalogue drift (a renamed/dropped read tool, a new app-db
;;;; egress tool that skips the elision posture) fails loudly here.
;;;;
;;;; This file pins the load-bearing direct-read privacy tokens and the
;;;; link to the Tool-Pair direct-read section, so a future edit that
;;;; strips the elision invariant from the shared surface guidance fails
;;;; loudly. Mirrors `retro_protocol_test.clj`'s structural-drift shape.
;;;;
;;;; Run:    bb tests/tool_pair_surfaces_test.clj   (from skills/shared/)
;;;; Exit:   0 = pass, non-zero = fail.

(ns tool-pair-surfaces-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private shared-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)    ;; tests/
      (.getParentFile)))  ;; skills/shared/

(def ^:private surfaces-md
  (delay (slurp (io/file shared-root "tool-pair-surfaces.md"))))

(defn- contains-any? [text alts]
  (some #(str/includes? text %) alts))

;; ---------------------------------------------------------------------------
;; Lock — the direct-read wire-egress contract is pinned on the shared leaf
;; ---------------------------------------------------------------------------

(deftest direct-reads-named-in-current-mcp-tool-terms
  (testing "the direct-read surfaces are named in CURRENT re-frame2-pair-mcp wire terms"
    (let [body @surfaces-md]
      ;; The current re-frame2-pair-mcp catalogue exposes these direct-read
      ;; egress surfaces — NOT a standalone `get-app-db` / `sub-cache` tool.
      ;; A consuming skill drafting upstream work must see the shipped wire
      ;; vocabulary, not only the raw `app-db-value` / `compute-sub` runtime
      ;; API (rf2-eca6x.1) and not a stale tool name (rf2-c9xgp).
      (is (and (str/includes? body "snapshot")
               (str/includes? body "get-path")
               (str/includes? body "read-sub")
               (str/includes? body "list-subscriptions"))
          (str "tool-pair-surfaces.md no longer names the current "
               "re-frame2-pair-mcp direct-read wire surfaces (`snapshot` / "
               "`get-path` / `read-sub` / `list-subscriptions`). Verify "
               "against tools/re-frame2-pair-mcp/README.md §Tool surface "
               "(rf2-c9xgp).")))))

(deftest subscription-reads-and-dry-run-named-as-gated-egress
  (testing "subscription-value reads + dispatch-dry-run are pinned under the same fail-closed posture"
    (let [body @surfaces-md]
      ;; rf2-c9xgp: the original four-name list omitted the real
      ;; subscription-read egress and the dry-run app-db/fx egress. Both
      ;; ride the SAME `--allow-sensitive-reads` posture (read-sub.cljs /
      ;; list_subscriptions.cljs route :value through elide-wire-value;
      ;; dispatch-dry-run is gated per re-frame2-pair-mcp/README.md:200).
      (is (str/includes? body "include-values")
          (str "tool-pair-surfaces.md no longer names `:include-values` — "
               "the `list-subscriptions` arg that ships each subscription's "
               "current `:value` off-box. That value read rides the "
               "fail-closed elision posture and must be visible at this "
               "leaf (rf2-c9xgp)."))
      (is (str/includes? body "dispatch-dry-run")
          (str "tool-pair-surfaces.md no longer names `dispatch-dry-run`. "
               "It mutates nothing but egresses app-db-/fx-derived data "
               "(`:db-state-after-simulation` / `:would-fire-effects`) "
               "under the SAME `--allow-sensitive-reads` posture "
               "(re-frame2-pair-mcp/README.md:200, rf2-c9xgp).")))))

(deftest get-app-db-only-as-generic-not-current-tool
  (testing "get-app-db is framed as a generic/third-party class, never a current pair-mcp tool"
    (let [body @surfaces-md]
      ;; rf2-c9xgp: `get-app-db` is NOT a current re-frame2-pair-mcp tool —
      ;; the catalogue reaches app-db via `snapshot`/`get-path`. The leaf
      ;; may keep `get-app-db` only as a generic / third-party direct-read
      ;; class name. If it appears at all, it must be near a generic/third-
      ;; party qualifier, never presented as a shipped pair-mcp tool.
      (when (str/includes? body "get-app-db")
        (is (or (str/includes? body "generic / third-party")
                (str/includes? body "generic/third-party")
                (str/includes? body "third-party")
                (str/includes? body "third party"))
            (str "tool-pair-surfaces.md mentions `get-app-db` but no longer "
                 "frames it as a generic / third-party direct-read class. "
                 "There is no standalone `get-app-db` tool in the current "
                 "re-frame2-pair-mcp catalogue — naming it as a current "
                 "wire surface is stale (rf2-c9xgp)."))))))

(deftest direct-reads-route-through-elide-wire-value
  (testing "the leaf pins rf/elide-wire-value as the MUST egress site for direct reads"
    (let [body @surfaces-md]
      (is (str/includes? body "rf/elide-wire-value")
          (str "tool-pair-surfaces.md no longer names `rf/elide-wire-value` "
               "as the wire-egress site for direct reads. This is THE "
               "leak-prevention boundary the spec makes load-bearing — a "
               "direct read bypasses trace redaction, so off-box egress "
               "MUST route through the walker (spec/Tool-Pair.md "
               "§Direct-read privacy)."))
      (is (contains-any? body ["MUST"])
          (str "The MUST-level imperative was downgraded. The direct-read "
               "egress contract is normative, not advisory.")))))

(deftest direct-reads-default-suppress-sensitive-and-large
  (testing "the leaf pins the off-box default-suppress posture + indicators"
    (let [body @surfaces-md]
      (is (and (str/includes? body ":rf.size/include-sensitive?")
               (str/includes? body ":rf.size/include-large?"))
          (str "The off-box default-suppress opts "
               "(`:rf.size/include-sensitive?` / `:rf.size/include-large?`, "
               "both default false) are missing. They are what make the "
               "egress fail-closed."))
      (is (and (str/includes? body ":rf/redacted")
               (str/includes? body ":rf.size/large-elided"))
          (str "The elision sentinels (`:rf/redacted` for sensitive, "
               "`:rf.size/large-elided` for oversize) are missing. They "
               "are the observable result of the suppression."))
      (is (contains-any? body ["sensitive drop wins" "sensitive-wins"
                               "sensitive drop"])
          (str "The composition rule — sensitive drop wins over the size "
               "marker — is missing. Without it the leaf doesn't pin how "
               "the two predicates compose (the size marker would leak "
               ":path / :bytes / :digest)."))
      (is (and (str/includes? body ":dropped-sensitive")
               (str/includes? body ":elided-large"))
          (str "The required elision indicators (`:dropped-sensitive` / "
               "`:elided-large`) are missing. spec/009 requires structured "
               "tool responses report elision counts.")))))

(deftest direct-reads-name-the-allow-sensitive-gate-default-off
  (testing "the leaf pins the --allow-sensitive-reads gate, default OFF"
    (let [body @surfaces-md]
      (is (str/includes? body "--allow-sensitive-reads")
          (str "The cross-MCP `--allow-sensitive-reads` boot gate is no "
               "longer named. It is the canonical opt-in that re-opens "
               "sensitive egress (rf2-eca6x.1: gate default OFF)."))
      (is (contains-any? body ["default **OFF**" "default OFF" "OFF**"
                               "default `OFF`"])
          (str "The 'default OFF' qualifier on `--allow-sensitive-reads` "
               "is missing. The gate being off-by-default is the "
               "load-bearing fail-closed posture — a default-ON reading "
               "inverts the contract.")))))

(deftest direct-reads-link-the-tool-pair-section
  (testing "the leaf links the authoritative Tool-Pair direct-read section"
    (let [body @surfaces-md]
      (is (str/includes? body "Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path")
          (str "tool-pair-surfaces.md no longer links the authoritative "
               "spec/Tool-Pair.md §Direct-read privacy section. The shared "
               "leaf is a pointer at the contract; the anchored link is "
               "what keeps it from being a second (driftable) source of "
               "truth (rf2-eca6x.1).")))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'tool-pair-surfaces-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
