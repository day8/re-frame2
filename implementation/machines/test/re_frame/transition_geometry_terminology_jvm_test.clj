(ns re-frame.transition-geometry-terminology-jvm-test
  "Terminology guard for the targetless-vs-explicit-target transition geometry
  (rf2-cczlc).

  PR #6010 made the executable `internal?` flag EXACTLY the targetless /
  no-cascade case (`compute-cascade-paths` in
  `re-frame.machines.transition`), and the four active-path geometries are
  pinned behaviourally by `machine_active_path_geometry_test.clj` plus the
  `machine_property_cljs_test.cljc` invariants. What those tiers cannot see is
  the PROSE drifting back to the mixed vocabulary rf2-cczlc repaired: Spec 005
  §Self-transitions, `docs/machines/concepts.md`, and the transition-runtime
  docstrings once called a targeted self / ancestor transition `internal`,
  conflating XState's non-reentering LABEL with re-frame2's structural runtime
  `internal?` flag — which is targetless-only. The same drift licensed the
  overclaim that ONLY targetless transitions are observable no-ops, although a
  leaf self-target without `:reenter?` has no descendants to resolve and is
  action-only too.

  So this namespace guards the disambiguated vocabulary in two directions,
  precisely (the corrected prose legitimately uses the very words the drift
  used — `internal?` for the targetless case, XState's `internal` as an
  explicitly-labelled parity term):

  - the two conflated meanings + the leaf no-op overclaim stay ABSENT, and
  - the load-bearing disambiguation (the runtime `internal?` flag reserved for
    targetless; XState's `internal` labelled a parity term; the empty-
    descendant leaf case stated by the declaring-state relationship) stays
    PRESENT.

  `guard-has-teeth` proves both halves: every reverted sentence is caught,
  every shipped sentence is not."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- repo-root
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(defn- slurp-rel [rel] (slurp (io/file (repo-root) rel)))

(defn- section-slice
  "Substring of `text` from the first line matching `start-re` up to (but not
  including) the next line matching `boundary-re`, or EOF. Lets the guard scan
  exactly the §Self-transitions surface rf2-cczlc owns, so the careful
  `internal by default` uses in OTHER sections (the drain-semantics /
  pitfalls / lessons summaries that reserve `internal no-op` for targetless and
  attribute the label to XState) are out of scope for this guard."
  [text start-re boundary-re]
  (let [lines (vec (str/split-lines text))
        start (first (keep-indexed (fn [i l] (when (re-find start-re l) i)) lines))]
    (when-not start
      (throw (ex-info "terminology guard: start marker not found"
                      {:start-re (str start-re)})))
    (let [end (or (first (keep-indexed
                          (fn [i l] (when (and (> i start) (re-find boundary-re l)) i))
                          lines))
                  (count lines))]
      (str/join "\n" (subvec lines start end)))))

;; ---------------------------------------------------------------------------
;; The three prose surfaces, sliced to exactly the terminology section.

(def ^:private spec-self
  ;; Spec 005 §Self-transitions — up to the next H3 (§Guards).
  (delay (section-slice (slurp-rel "spec/005-StateMachines.md")
                        #"^### Self-transitions" #"^### ")))

(def ^:private concepts-self
  ;; docs/machines/concepts.md §Self-transitions and wildcards — to next H2.
  (delay (section-slice (slurp-rel "docs/machines/concepts.md")
                        #"^## Self-transitions and wildcards" #"^## ")))

(def ^:private transition-src
  ;; The transition runtime, whole file: the `internal?` classification prose
  ;; lives in `target-path` + `compute-cascade-paths`.
  (delay (slurp-rel "implementation/machines/src/re_frame/machines/transition.cljc")))

;; ---------------------------------------------------------------------------
;; FORBIDDEN — the conflations + the leaf no-op overclaim. Each pattern is
;; anchored on the phrasing that carries the WRONG claim, never on a bare
;; keyword, so the corrected prose's legitimate uses of the same words stay
;; clear of it. `guard-has-teeth` proves both halves.

(def ^:private forbidden-claims
  [;; 1. An EXPLICIT target described as re-frame2's runtime `internal?`
   ;;    (dropping the load-bearing NEVER). The negation `is NEVER internal`
   ;;    is legitimate and must NOT trip: the pattern requires `is internal`
   ;;    adjacency, so `is never internal` (a word between) is clear.
   ["an explicit target is called `internal?`"
    #"(?i)explicit\s+target\s+is\s+(?:an?\s+|the\s+)?internal\b"]

   ;; 2. A self / ancestor target asserted to BE the runtime `internal?`
   ;;    no-op / flag (the structural conflation). The shipped callout says
   ;;    the opposite — `is not internal?` — which cannot match (it needs
   ;;    `is the internal? no-op/flag`).
   ["a self/ancestor target is the runtime `internal?` no-op"
    #"(?i)(?:self|ancestor)[^.\n]{0,40}target[^.\n]{0,20}\bis\s+(?:an?\s+|the\s+)?`?internal\??`?\s+(?:no-op|flag)"]

   ;; 3. The leaf no-op overclaim: ONLY targetless transitions are no-ops.
   ;;    A leaf self-target without `:reenter?` is action-only too, so this
   ;;    over-generalises. The shipped negation reads `targetless is the only
   ;;    action-only geometry` (targetless BEFORE only, and `geometry` not
   ;;    `no-op`), so it stays clear.
   ["only targetless transitions are observable no-ops"
    #"(?i)only\s+targetless\s+transitions?\s+(?:are|is)\b[^.\n]{0,40}no-ops?"]

   ;; 4. The structural-identity overclaim: a targeted leaf IS / EQUALS a
   ;;    targetless transition. It COINCIDES in visible effect, but the
   ;;    distinction is the declaring-state relationship, not leaf-ness. The
   ;;    shipped prose says `is not because a targeted leaf is internal?` and
   ;;    `the same as a targetless transition` (neither is an identity claim).
   ["a targeted leaf is identified with targetless"
    #"(?i)targeted\s+leaf\s+(?:is|equals|==)\s+(?:a\s+|the\s+)?targetless"]])

;; ---------------------------------------------------------------------------
;; REQUIRED — the disambiguation the rewrite installed. Absence = the prose
;; lost the distinction, which is the revert this guard exists to catch.

(def ^:private required-terms
  [;; Spec 005 §Self-transitions
   ["spec: `internal?` labelled the runtime flag"
    spec-self #"(?i)`internal\?`\s*\(runtime flag\)"]
   ["spec: XState's term labelled a parity term"
    spec-self #"(?i)xstate parity term"]
   ["spec: `internal?` reserved for the targetless case"
    spec-self #"(?is)reserve\s+`internal\?`\s+for\s+the\s+targetless"]
   ["spec: the empty-descendant leaf case is stated"
    spec-self #"(?i)empty-descendant leaf"]

   ;; docs/machines/concepts.md
   ;;
   ;; rf2-s41i re-points this surface's two pins. They were anchored on the
   ;; parenthetical that carried the XState-parity disambiguation — "XState
   ;; calls that non-reentering shape `internal`; re-frame2's runtime
   ;; `internal?` flag is narrower — it's the **targetless** no-op alone".
   ;; The rf2-9d0d guide rewrite deleted that parenthetical and now teaches
   ;; the three self-transition shapes as a table that never says `internal`
   ;; at all. That is not the drift this guard exists to catch — the
   ;; conflation is impossible in prose that does not use the word, and the
   ;; FORBIDDEN half still scans this same slice, so re-introducing it fails.
   ;; Re-injecting the deleted parenthetical would revert an operator-authored
   ;; rewrite, so the pins follow the guide instead: they now hold the SAME
   ;; distinction the parenthetical carried — targetless is the action-only
   ;; geometry, and a targeted self on a COMPOUND is not a no-op because it
   ;; re-resolves descendants. The normative statement of the disambiguation
   ;; is unaffected: Spec 005 §Self-transitions still carries all four of its
   ;; pins below, including the `internal?`/XState-parity labels.
   ["concepts: targetless is the action-only shape"
    concepts-self #"(?is)\(targetless\).{0,60}?action only"]
   ["concepts: a targeted self on a compound re-resolves descendants"
    concepts-self #"(?is)compound\s+re-resolves\s+descendants"]

   ;; transition.cljc runtime prose
   ["impl: `compute-cascade-paths` keeps `explicit target is NEVER internal`"
    transition-src #"(?i)explicit\s+target\s+is\s+never\s+internal"]
   ["impl: `target-path` labels XState `internal` a parity label, not the flag"
    transition-src #"(?is)parity label, not re-frame2's runtime"]])

(defn- forbidden-hits
  "Every forbidden claim `text` reacquired."
  [text]
  (->> forbidden-claims
       (filter (fn [[_ pattern]] (re-find pattern text)))
       (mapv first)))

(defn- missing-terms
  "Every required term whose source no longer carries it."
  []
  (->> required-terms
       (remove (fn [[_ src pattern]] (re-find pattern @src)))
       (mapv first)))

;; ---------------------------------------------------------------------------

(deftest transition-geometry-prose-keeps-the-disambiguated-vocabulary
  (testing "the conflations + leaf overclaim stay absent from every surface"
    (doseq [[label src] [["spec/005 §Self-transitions" spec-self]
                         ["docs/machines/concepts.md §Self-transitions" concepts-self]
                         ["machines/transition.cljc" transition-src]]]
      (is (= [] (forbidden-hits @src))
          (str label
               " reacquired a conflated / over-general transition-geometry "
               "claim. The runtime `internal?` flag is TARGETLESS-only "
               "(Spec 005 §Self-transitions + `compute-cascade-paths`); an "
               "explicit self / ancestor target is non-reentering but "
               "re-resolves descendants, and a leaf self-target is action-only "
               "by the empty-descendant coincidence, not by being `internal?`."))))
  (testing "the load-bearing disambiguation stays present"
    (is (= [] (missing-terms))
        (str "a transition-geometry surface lost terminology that carries the "
             "targetless-vs-explicit-target distinction (rf2-cczlc)."))))

(deftest guard-has-teeth
  (testing "every reverted claim is caught"
    (doseq [[claim sentence]
            [;; the runtime-flag conflation in a code comment
             ["an explicit target is called `internal?`"
              ";; An EXPLICIT target is internal (targetless or not)."]
             ;; a self/ancestor target asserted to BE the runtime flag
             ["a self/ancestor target is the runtime `internal?` no-op"
              "A self / ancestor `:target` is the `internal?` no-op by default."]
             ;; the leaf no-op overclaim
             ["only targetless transitions are observable no-ops"
              "Only targetless transitions are observable configuration no-ops."]
             ;; the structural-identity overclaim
             ["a targeted leaf is identified with targetless"
              "At a leaf, a targeted leaf is targetless — the two are one geometry."]]]
      (is (= [claim] (forbidden-hits sentence))
          (str "the guard failed to catch a reverted claim: " claim))))

  (testing "the corrected prose's legitimate uses of the same words are not caught"
    (doseq [sentence
            [;; targetless legitimately IS the internal? no-op
             "Targetless — the runtime `internal?` no-op. This is the only geometry the runtime flags `internal?`."
             ;; the load-bearing negation compute-cascade-paths keeps
             ";; the ONLY internal (true configuration no-op) case. An EXPLICIT target is NEVER internal:"
             ;; a self/ancestor target is XState-`internal` but NOT the runtime flag
             "So a self / ancestor target is \"internal\" in XState's vocabulary yet is not `internal?` in re-frame2's runtime."
             ;; the shipped leaf sentence — coincidence, not identity, not `internal?`
             "a self-target declared on a leaf has no descendants to re-resolve, so its visible effect collapses to action-only — the same as a targetless transition, but by the empty-descendant coincidence, not because a targeted leaf is `internal?` (it is not) and not because targetless is the only action-only geometry."
             ;; the reserve sentence
             "Reserve `internal?` for the targetless case; describe an explicit self / ancestor target operationally."]]
      (is (= [] (forbidden-hits sentence))
          (str "the guard fired on legitimate shipped prose: " sentence))))

  (testing "a surface stripped of the disambiguation is caught by the required half"
    ;; The shipped surfaces satisfy every required term; deleting one must fail.
    (is (empty? (missing-terms)))
    (is (seq (->> required-terms
                  (remove (fn [[_ _ pattern]]
                            (re-find pattern "Self-transitions are internal by default.")))
                  (mapv first)))))

  (testing "the shipped surfaces satisfy both halves"
    (is (= [] (forbidden-hits @spec-self)))
    (is (= [] (forbidden-hits @concepts-self)))
    (is (= [] (forbidden-hits @transition-src)))
    (is (= [] (missing-terms)))))
