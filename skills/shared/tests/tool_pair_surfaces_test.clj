;;;; tests/tool_pair_surfaces_test.clj — projection-integrity + link-adoption
;;;; regression for the shared Tool-Pair routing index.
;;;;
;;;; `tool-pair-surfaces.md` is a ROUTING INDEX, not a second specification.
;;;; It classifies an upstream finding to the right surface FAMILY and points
;;;; at the authoritative owner (spec/Tool-Pair.md, the per-surface spec
;;;; anchors, and the re-frame2-pair-mcp tool catalogue). The exact API / tool
;;;; semantics — wire shapes, the privacy/egress algorithm, per-tool op lists,
;;;; recorder mechanics — are tested by those OWNING spec / implementation /
;;;; tool suites, NOT here. This suite therefore guards only two things:
;;;;
;;;;   projection integrity — the family SET stays complete (so a finding is
;;;;     never mis-routed for a missing row) and the leaf stays a routing
;;;;     index rather than regrowing into a parallel specification; and
;;;;   link adoption — the authoritative owners are linked (the leaf is a
;;;;     pointer), and the `#supersedes-re-frame-10x` anchor that sibling
;;;;     skills deep-link stays stable.
;;;;
;;;; It deliberately does NOT pin operational tokens (elision opts, sentinels,
;;;; the `--allow-sensitive-reads` gate, envelope slots, restore modes, …):
;;;; those are the owners' contract, and pinning them here rebuilt the second
;;;; specification this leaf was slimmed out of.
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

(defn- contains-all? [text alts]
  (every? #(str/includes? text %) alts))

(defn- word-count [text]
  (->> (str/split (str/trim text) #"\s+")
       (remove str/blank?)
       count))

;; ---------------------------------------------------------------------------
;; Projection integrity — the leaf stays a routing index, not a second spec
;; ---------------------------------------------------------------------------

;; The leaf grew from a pointer into a ~3,700-word parallel specification
;; (registrar / epoch / injection / privacy / recorder / operating-frame
;; semantics) before it was slimmed. A word ceiling is the cheap projection
;; guard: it fails loudly if the routing index starts re-absorbing the
;; owners' contract again. The ceiling is generous over the slim size so
;; ordinary maintenance (a new family row, a reworded legend) never trips it;
;; only a return toward a second specification does.
(def ^:private word-ceiling 1400)

(deftest routing-index-not-a-second-spec
  (testing "the leaf stays a concise routing index, well under a second-spec word budget"
    (let [n (word-count @surfaces-md)]
      (is (< n word-ceiling)
          (str "tool-pair-surfaces.md has grown to " n " words (ceiling "
               word-ceiling "). It is a ROUTING INDEX — surface family, "
               "owner, availability tag, authoritative anchor — not a second "
               "specification. Operational semantics / wire shapes / the "
               "privacy algorithm / op lists / recorder mechanics belong to "
               "their owners (spec/Tool-Pair.md, spec/015, the pair-MCP "
               "catalogue); link them, don't restate them (rf2-u3anaj).")))))

(deftest routing-table-columns-present
  (testing "the leaf carries the four-column routing table"
    (let [body @surfaces-md]
      (is (contains-all? body ["Surface family" "Owner" "Availability"
                               "Authoritative anchor"])
          (str "tool-pair-surfaces.md no longer carries the routing-table "
               "header (Surface family | Owner | Availability | Authoritative "
               "anchor). The table IS the routing index — one row per family "
               "with its framework-vs-tool ownership, availability tag, and "
               "canonical anchor (rf2-u3anaj)."))
      (is (contains-all? body ["## The routing table" "Availability legend"])
          (str "tool-pair-surfaces.md no longer carries the routing-table "
               "section + its availability legend. Without the legend the "
               "availability tags are undefined at the leaf (rf2-u3anaj).")))))

(deftest every-surface-family-enumerated
  (testing "the routing index enumerates every upstream-routing family for issue classification"
    (let [body @surfaces-md]
      ;; The family SET must stay complete: an abbreviated subset mis-routes a
      ;; finding about a dropped family as generic pair-tool friction. These
      ;; are the family-name tokens a consuming skill classifies against — NOT
      ;; the operational detail of each family (that lives with the owner).
      (is (contains-all? body ["Trace stream"
                               "Registrar query API"
                               "Epoch history"
                               "State injection"
                               "replace-frame-state!"
                               "Schema reflection"
                               "Source-coord annotation"
                               "Render-driving"
                               "flush-render!"
                               "View-plane reads"
                               "read-ui"
                               "Signal recorder"
                               "watch-until"
                               "Operating-frame trio"
                               "Direct reads"])
          (str "tool-pair-surfaces.md no longer names every upstream-routing "
               "surface family. The complete set is load-bearing for issue "
               "classification — a missing family lets a finding about it "
               "route to the wrong layer (spec/Tool-Pair.md, the pair-MCP "
               "catalogue, rf2-u3anaj).")))))

;; ---------------------------------------------------------------------------
;; Link adoption — the leaf is a pointer, and its stable anchor is preserved
;; ---------------------------------------------------------------------------

(deftest supersedes-10x-anchor-stable
  (testing "the `## Supersedes re-frame-10x` heading (and its #supersedes-re-frame-10x slug) is preserved"
    (let [body @surfaces-md]
      ;; Sibling skills (re-frame2-xray SKILL.md + references/chrome.md)
      ;; deep-link `tool-pair-surfaces.md#supersedes-re-frame-10x`. Renaming
      ;; or dropping this heading breaks those anchored links (and the
      ;; check_doc_slugs gate). The routing fact — v2 tooling supersedes 10x,
      ;; consumers cite this paragraph — must stay for issue classification.
      (is (str/includes? body "## Supersedes re-frame-10x")
          (str "tool-pair-surfaces.md no longer carries the `## Supersedes "
               "re-frame-10x` heading. Its `#supersedes-re-frame-10x` slug is "
               "deep-linked by re-frame2-xray; the routing claim is cited by "
               "several skills. Keep the heading verbatim (rf2-u3anaj)."))
      (is (str/includes? body "re-frame-10x")
          "tool-pair-surfaces.md no longer states the supersedes-re-frame-10x routing fact."))))

(deftest authoritative-owners-linked
  (testing "the leaf links the authoritative framework contract + the tool catalogue (it is a pointer)"
    (let [body @surfaces-md]
      (is (str/includes? body "spec/Tool-Pair.md")
          (str "tool-pair-surfaces.md no longer links the authoritative "
               "framework contract spec/Tool-Pair.md. The leaf is a pointer "
               "at that contract, not a source of truth (rf2-u3anaj)."))
      (is (str/includes? body "re-frame2-pair-mcp/README.md")
          (str "tool-pair-surfaces.md no longer links the re-frame2-pair-mcp "
               "tool catalogue — the authoritative home for the current "
               "shipped tool/op names (rf2-u3anaj).")))))

(deftest per-family-canonical-anchors-present
  (testing "each routing row points at its authoritative per-surface anchor"
    (let [body @surfaces-md]
      ;; Link adoption: the routing value is the anchor. These are the
      ;; canonical section slugs the families route to; a dropped anchor
      ;; turns a row from a router into a dead end. (check_doc_slugs.py
      ;; separately validates each anchor still resolves to a real heading.)
      (is (contains-all? body
                         ["spec/002-Frames.md#the-public-registrar-query-api"
                          "spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo"
                          "spec/Tool-Pair.md#pair-tool-writes--state-injection"
                          "spec/Tool-Pair.md#operating-frame--multi-frame-resolution"
                          "spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path"
                          "spec/Tool-Pair.md#reading-rendered-content--producing-entity--the-viewcontent-read"])
          (str "tool-pair-surfaces.md dropped one or more canonical per-family "
               "anchors. Each routing row must point at the authoritative "
               "section for its semantics; without the anchor the row cannot "
               "route (rf2-u3anaj).")))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'tool-pair-surfaces-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
