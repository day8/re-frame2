(ns re-frame.transition-geometry-terminology-jvm-test
  "Terminology guard for the targetless-vs-explicit-target transition geometry.

  The runtime `internal?` flag is EXACTLY the targetless / no-cascade case
  (`compute-transition-geometry` in `re-frame.machines.transition`); the
  geometries themselves are pinned behaviourally by
  `machine_active_path_geometry_test.clj`. What those tests cannot see is the
  PROSE drifting into a mixed vocabulary: Spec 005 §Self-transitions,
  `docs/machines/concepts.md` or the transition-runtime docstrings calling a
  targeted self / ancestor transition `internal` would conflate XState's
  non-reentering LABEL with re-frame2's targetless-only runtime flag, and
  license the overclaim that ONLY targetless transitions are observable
  no-ops (a leaf self-target without `:reenter?` is action-only too).

  So the conflations and the overclaim stay ABSENT, and the disambiguation
  stays PRESENT. `guard-has-teeth` proves both halves: every reverted sentence
  is caught, every shipped sentence is not."
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
  including) the next line matching `boundary-re`, or EOF — so the guard scans
  only the §Self-transitions surface it owns, not other sections' careful uses
  of the same words."
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

(def ^:private spec-self
  ;; Spec 005 §Self-transitions — up to the next H3.
  (delay (section-slice (slurp-rel "spec/005-StateMachines.md")
                        #"^### Self-transitions" #"^### ")))

(def ^:private concepts-self
  ;; docs/machines/concepts.md §Self-transitions and wildcards — to next H2.
  (delay (section-slice (slurp-rel "docs/machines/concepts.md")
                        #"^## Self-transitions and wildcards" #"^## ")))

(def ^:private transition-src
  ;; The `internal?` prose lives in `target-path` + `compute-transition-geometry`.
  (delay (slurp-rel "implementation/machines/src/re_frame/machines/transition.cljc")))

;; FORBIDDEN — each pattern is anchored on the phrasing that carries the WRONG
;; claim, never on a bare keyword, so the shipped prose's legitimate uses of
;; the same words (the negations `guard-has-teeth` lists) stay clear of it.
(def ^:private forbidden-claims
  [;; `is internal` adjacency, so `is NEVER internal` is clear.
   ["an explicit target is called `internal?`"
    #"(?i)explicit\s+target\s+is\s+(?:an?\s+|the\s+)?internal\b"]

   ;; Needs `is the internal? no-op/flag`, so `is not internal?` is clear.
   ["a self/ancestor target is the runtime `internal?` no-op"
    #"(?i)(?:self|ancestor)[^.\n]{0,40}target[^.\n]{0,20}\bis\s+(?:an?\s+|the\s+)?`?internal\??`?\s+(?:no-op|flag)"]

   ;; `targetless is the only action-only geometry` (targetless BEFORE only)
   ;; is clear.
   ["only targetless transitions are observable no-ops"
    #"(?i)only\s+targetless\s+transitions?\s+(?:are|is)\b[^.\n]{0,40}no-ops?"]

   ;; A targeted leaf COINCIDES with targetless in visible effect; it is not
   ;; the same geometry.
   ["a targeted leaf is identified with targetless"
    #"(?i)targeted\s+leaf\s+(?:is|equals|==)\s+(?:a\s+|the\s+)?targetless"]])

;; REQUIRED — the disambiguation itself; absence is the revert this guard
;; exists to catch.
(def ^:private required-terms
  [["spec: `internal?` labelled the runtime flag"
    spec-self #"(?i)`internal\?`\s*\(runtime flag\)"]
   ["spec: XState's term labelled a parity term"
    spec-self #"(?i)xstate parity term"]
   ["spec: `internal?` reserved for the targetless case"
    spec-self #"(?is)reserve\s+`internal\?`\s+for\s+the\s+targetless"]
   ["spec: the empty-descendant leaf case is stated"
    spec-self #"(?i)empty-descendant leaf"]

   ;; The guide's self-transition table never says `internal`, so it pins the
   ;; distinction itself; the FORBIDDEN half still scans the same slice.
   ["concepts: targetless is the action-only shape"
    concepts-self #"(?is)\(targetless\).{0,60}?action only"]
   ["concepts: a targeted self on a compound re-resolves descendants"
    concepts-self #"(?is)compound\s+re-resolves\s+descendants"]

   ["impl: `compute-transition-geometry` keeps `explicit target is NEVER internal`"
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
  "Every required term its source does not carry."
  []
  (->> required-terms
       (remove (fn [[_ src pattern]] (re-find pattern @src)))
       (mapv first)))

(deftest transition-geometry-prose-keeps-the-disambiguated-vocabulary
  (testing "the conflations + leaf overclaim stay absent from every surface"
    (doseq [[label src] [["spec/005 §Self-transitions" spec-self]
                         ["docs/machines/concepts.md §Self-transitions" concepts-self]
                         ["machines/transition.cljc" transition-src]]]
      (is (= [] (forbidden-hits @src))
          (str label
               " reacquired a conflated / over-general transition-geometry "
               "claim. The runtime `internal?` flag is TARGETLESS-only "
               "(Spec 005 §Self-transitions + `compute-transition-geometry`); an "
               "explicit self / ancestor target is non-reentering but "
               "re-resolves descendants, and a leaf self-target is action-only "
               "by the empty-descendant coincidence, not by being `internal?`."))))
  (testing "the load-bearing disambiguation stays present"
    (is (= [] (missing-terms))
        (str "a transition-geometry surface lost terminology that carries the "
             "targetless-vs-explicit-target distinction."))))

(deftest guard-has-teeth
  (testing "every reverted claim is caught"
    (doseq [[claim sentence]
            [["an explicit target is called `internal?`"
              ";; An EXPLICIT target is internal (targetless or not)."]
             ["a self/ancestor target is the runtime `internal?` no-op"
              "A self / ancestor `:target` is the `internal?` no-op by default."]
             ["only targetless transitions are observable no-ops"
              "Only targetless transitions are observable configuration no-ops."]
             ["a targeted leaf is identified with targetless"
              "At a leaf, a targeted leaf is targetless — the two are one geometry."]]]
      (is (= [claim] (forbidden-hits sentence))
          (str "the guard failed to catch a reverted claim: " claim))))

  (testing "the shipped prose's legitimate uses of the same words are not caught"
    (doseq [sentence
            ["Targetless — the runtime `internal?` no-op. This is the only geometry the runtime flags `internal?`."
             ";; the ONLY internal (true configuration no-op) case. An EXPLICIT target is NEVER internal:"
             "So a self / ancestor target is \"internal\" in XState's vocabulary yet is not `internal?` in re-frame2's runtime."
             "a self-target declared on a leaf has no descendants to re-resolve, so its visible effect collapses to action-only — the same as a targetless transition, but by the empty-descendant coincidence, not because a targeted leaf is `internal?` (it is not) and not because targetless is the only action-only geometry."
             "Reserve `internal?` for the targetless case; describe an explicit self / ancestor target operationally."]]
      (is (= [] (forbidden-hits sentence))
          (str "the guard fired on legitimate shipped prose: " sentence))))

  (testing "a surface stripped of the disambiguation is caught by the required half"
    (is (seq (->> required-terms
                  (remove (fn [[_ _ pattern]]
                            (re-find pattern "Self-transitions are internal by default.")))
                  (mapv first))))))
