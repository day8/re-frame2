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
;;;; Run:    bb skills/shared/tests/tool_pair_surfaces_test.clj   (from repo root)
;;;;         bb tests/tool_pair_surfaces_test.clj                 (from skills/shared/)
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
;; Lock — the leaf enumerates the FULL upstream-routing surface catalogue
;; (rf2-985x1t)
;; ---------------------------------------------------------------------------
;;
;; The independent correctness review (rf2-985x1t) found the leaf advertised
;; only an abbreviated subset of the Tool-Pair surface families: it stopped
;; at trace / registrar / epoch-restore / schema / source-coord / direct
;; reads and OMITTED render-driving + dispatch-settle, view-plane reads /
;; view attribution, the signal recorder, and the operating-frame trio —
;; all of which the authoritative contract (spec/Tool-Pair.md) and the
;; current re-frame2-pair-mcp catalogue ship. A retro finding about
;; deterministic dispatch->settle->DOM, read-ui/read-dom provenance,
;; human-interaction recording, or multi-frame operating-frame ambiguity
;; could therefore be mislabeled as pair-tool friction and filed against
;; the wrong layer. These tests pin the current catalogue tokens so a
;; future re-narrowing of the leaf fails loudly here.

(deftest render-driving-and-settle-enumerated
  (testing "the leaf names render-driving via flush-render! and the dispatch :settle mode"
    (let [body @surfaces-md]
      (is (str/includes? body "flush-render!")
          (str "tool-pair-surfaces.md no longer names `flush-render!` — the "
               "render-driving framework primitive a headless "
               "dispatch->observe-DOM loop needs to be deterministic "
               "(spec/Tool-Pair.md §Driving the render, rf2-985x1t)."))
      (is (contains-any? body [":settle" "dispatch settle" "dispatch-settle"
                               "settle mode"])
          (str "tool-pair-surfaces.md no longer names the dispatch `:settle` "
               "surface that builds on `flush-render!` to return the settled "
               "epoch in one call (rf2-985x1t).")))))

(deftest view-plane-reads-enumerated
  (testing "the leaf names the view-plane reads read-dom / read-ui and the data-rf-view attribution"
    (let [body @surfaces-md]
      (is (and (str/includes? body "read-dom")
               (str/includes? body "read-ui"))
          (str "tool-pair-surfaces.md no longer names the view-plane read "
               "surfaces `read-dom` / `read-ui`. Without them a retro "
               "finding about rendered-content provenance routes to the "
               "wrong layer (spec/Tool-Pair.md §The view→content read, "
               "re-frame2-pair-mcp/README.md, rf2-985x1t)."))
      (is (str/includes? body "data-rf-view")
          (str "tool-pair-surfaces.md no longer names the `data-rf-view` "
               "view-id attribution attribute — the view↔DOM map the "
               "view-plane reads ride (rf2-985x1t).")))))

(deftest signal-recorder-triplet-enumerated
  (testing "the leaf names the signal-recorder triplet record / read-recording / watch-until"
    (let [body @surfaces-md]
      (is (and (str/includes? body "record")
               (str/includes? body "read-recording")
               (str/includes? body "watch-until"))
          (str "tool-pair-surfaces.md no longer names the signal-recorder "
               "triplet (`record` / `read-recording` / `watch-until`) — the "
               "canonical surface for intermittent / human-in-the-loop bugs "
               "(re-frame2-pair-mcp/README.md, rf2-985x1t).")))))

(deftest operating-frame-trio-enumerated
  (testing "the leaf names the multi-frame operating-frame trio"
    (let [body @surfaces-md]
      (is (and (str/includes? body "set-operating-frame")
               (str/includes? body "reset-operating-frame")
               (str/includes? body "get-operating-frame"))
          (str "tool-pair-surfaces.md no longer names the operating-frame "
               "trio (`set-operating-frame` / `reset-operating-frame` / "
               "`get-operating-frame`) required for multi-frame sessions. A "
               "multi-frame `:ambiguous-frame` finding would route to the "
               "wrong layer without it (spec/Tool-Pair.md §Operating frame, "
               "rf2-985x1t).")))))

;; ---------------------------------------------------------------------------
;; Lock — the four partition-aware state-injection mutators (rf2-7g9htq.2)
;;
;; The leaf used to name only `replace-app-db!` / `reset-app-db!` (the
;; app-db-only halves), under-teaching the post-EP-0001 partition-aware
;; write API. spec/Tool-Pair.md §Pair-tool writes defines FOUR mutators;
;; a skill that omits the runtime-db / full-frame siblings can describe
;; arbitrary repro / story state injection as app-db-only and miss the
;; full-frame install (`replace-frame-state!`) for machine snapshots /
;; routes / elision / SSR metadata. These pins fail loudly if the leaf
;; mentions `replace-app-db!` but drops either partition-aware sibling.
;; ---------------------------------------------------------------------------

(deftest state-injection-names-all-four-mutators
  (testing "the leaf names the four partition-aware state-injection mutators"
    (let [body @surfaces-md]
      (when (str/includes? body "replace-app-db!")
        (is (and (str/includes? body "replace-runtime-db!")
                 (str/includes? body "replace-frame-state!"))
            (str "tool-pair-surfaces.md names `replace-app-db!` but no longer "
                 "names both `replace-runtime-db!` (runtime-db-only privileged "
                 "write) and `replace-frame-state!` (full-frame atomic "
                 "install). The post-EP-0001 injection surface is FOUR "
                 "partition-aware mutators, not the app-db-only pair "
                 "(spec/Tool-Pair.md §Pair-tool writes, rf2-7g9htq.2)."))
        (is (str/includes? body "reset-app-db!")
            (str "tool-pair-surfaces.md dropped `reset-app-db!` from the "
                 "four-mutator state-injection family (rf2-7g9htq.2).")))
      (is (contains-any? body ["never silently touch" "never silently touches"
                               "never silently"])
          (str "tool-pair-surfaces.md no longer warns that the db-shaped "
               "names (`replace-app-db!` / `reset-app-db!`) preserve "
               "runtime-db — the load-bearing partition guarantee a "
               "story/repro tool relies on (rf2-7g9htq.2).")))))

;; ---------------------------------------------------------------------------
;; Lock — restore-epoch is whole-frame-state, not app-db-only (rf2-7g9htq /
;; rf2-7a1mkv). The leaf must teach that restore reinstalls BOTH partitions
;; via replace-frame-state!, so a consuming skill doesn't describe machine
;; snapshots / routes / elision as surviving time-travel.
;; ---------------------------------------------------------------------------

(deftest restore-epoch-named-as-frame-state-both-partitions
  (testing "restore-epoch is described as whole frame-state (both partitions)"
    (let [body @surfaces-md]
      (is (contains-any? body ["frame-state-after" "frame-state"])
          (str "tool-pair-surfaces.md no longer frames `restore-epoch` as a "
               "frame-state rewind. Restore reinstalls BOTH partitions "
               "(app-db AND runtime-db) via `replace-frame-state!`, not the "
               "app-db projection alone (spec/Tool-Pair.md §Restore)."))
      (is (str/includes? body "replace-frame-state!")
          (str "tool-pair-surfaces.md no longer names `replace-frame-state!` "
               "as the restore install surface (rf2-7a1mkv).")))))

;; ---------------------------------------------------------------------------
;; Lock — the privacy-relevant read catalogue covers the STREAMING reads +
;; the signal recorder, not just the one-shot direct-read set (rf2-7g9htq.3).
;;
;; The earlier leaf scoped the fail-closed egress map to the one-shot set
;; (snapshot / get-path / read-sub / list-subscriptions / dispatch-dry-run)
;; and omitted the streaming reads (subscribe / trace-window / watch-epochs)
;; and the signal recorder — all of which are privacy-bearing off-box reads
;; under the SAME default-off gate. A consuming skill using this leaf as the
;; privacy map could treat streaming epoch reads and recordings as outside
;; the boundary. These pins keep the broader catalogue + its emission sites
;; (elide-wire-value for direct values / recorded samples; projected-record
;; for epoch records) visible at the leaf.
;; ---------------------------------------------------------------------------

(deftest streaming-reads-named-as-gated-egress
  (testing "the leaf names the streaming reads subscribe / trace-window / watch-epochs as gated egress"
    (let [body @surfaces-md]
      (is (and (str/includes? body "subscribe")
               (str/includes? body "trace-window")
               (str/includes? body "watch-epochs"))
          (str "tool-pair-surfaces.md no longer names the streaming reads "
               "(`subscribe` / `trace-window` / `watch-epochs`) as off-box "
               "egress under the same default-off gate. They project / elide "
               "privacy-bearing values and are NOT outside the fail-closed "
               "boundary (re-frame2-pair-mcp §sensitive-reads gate, "
               "rf2-7g9htq.3)."))
      (is (str/includes? body "projected-record")
          (str "tool-pair-surfaces.md no longer names `projected-record` — "
               "the normative emission site for egressed EPOCH records "
               "(distinct from `elide-wire-value` for direct values). "
               "Without it the leaf overloads one emission site across "
               "value shapes (rf2-7g9htq.3).")))))

(deftest signal-recorder-egress-not-read-only-free-pass
  (testing "the leaf states recorded samples are elided before egress, not exempt for being read-only"
    (let [body @surfaces-md]
      (is (contains-any? body ["read-only is not the same as egress-safe"
                               "read-only is not"
                               "before entering the change log"
                               "before they enter the change log"])
          (str "tool-pair-surfaces.md no longer states that the signal "
               "recorder's read-only-by-construction posture does NOT exempt "
               "its recorded samples from elision — samples are value egress "
               "and route through `elide-wire-value` before entering the "
               "change log (rf2-7g9htq.3).")))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'tool-pair-surfaces-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
