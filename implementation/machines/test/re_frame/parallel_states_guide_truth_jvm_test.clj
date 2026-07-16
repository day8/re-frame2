(ns re-frame.parallel-states-guide-truth-jvm-test
  "Terminology guard for `docs/machines/parallel-states.md`.

  The engine law — parallel `:always` stabilization is a PARENT-owned
  select-then-apply round loop over the whole configuration — is already
  protected executably: property INVARIANT 9 in
  `machine_property_cljs_test.cljc` plus the pinned `parallel-always-round-*`
  deftests fail if the engine reverts.

  What those tiers cannot see is the GUIDE reverting to region-local prose
  while the engine stays correct. That is the exact drift rf2-1rdo4j had to
  repair: docs that taught `:always` as a per-region loop settling to a
  per-region fixed point, converging only on the NEXT event, against sibling
  keys frozen for the whole macrostep.

  So this namespace guards the prose, in two directions:

  - the superseded claims stay ABSENT, and
  - the load-bearing terms (`parent-owned`, the freeze/select/apply ROUND,
    the between-round RE-FREEZE) stay PRESENT.

  Precision matters more than breadth here. The corrected guide legitimately
  uses the very words the superseded claims used — `:bump-count` runs `ONCE
  PER REGION` (true: that is the APPLY phase), the `Per-region :always /
  :after / :spawn` heading (true: TARGETING is region-scoped), and the law is
  taught by explicitly negating the old framing (`not \"each region settles
  itself\"`, frozen per round `not for the whole macrostep`). A guard that
  fired on those would be deleted within a week, so `guard-has-teeth` pins
  both directions: every superseded sentence is caught, every legitimate
  sentence is not."
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

(def ^:private guide
  (delay (slurp (io/file (repo-root) "docs/machines/parallel-states.md"))))

;; ---------------------------------------------------------------------------
;; The four superseded claims rf2-1rdo4j retired.
;;
;; Each pattern is anchored on the phrasing that carries the CLAIM, never on a
;; bare keyword, so the corrected guide's legitimate uses of the same words
;; stay clear of it. `guard-has-teeth` proves both halves of that.

(def ^:private superseded-claims
  [;; 1. Birth / per-region settling: "each region's birth `:always` settles
   ;;    independently as part of the initial step". The parent runs birth
   ;;    rounds across the whole configuration.
   ["a region's `:always` settles independently"
    #"(?i)settles?\s+independently"]

   ;; 2. "per region" applied to the `:always` LOOP (not to the apply phase):
   ;;    "the microstep loop runs *per region*", settling to "*that region's*
   ;;    fixed point", with "siblings aren't re-evaluated".
   ["the `:always` loop runs per region"
    #"(?i)loop\s+runs\s+\*{0,2}per[-\s]region"]
   ["`:always` settles to a per-region fixed point"
    #"(?i)(?:that\s+region's|its\s+own)\*{0,2}\s+\*{0,2}fixed\s+point"]
   ["siblings are not re-evaluated during the `:always` loop"
    #"(?i)siblings\s+are(?:n't|\s+not)\s+re-evaluated"]

   ;; 3. A guarded `:always` reading a sibling "converges on the NEXT EVENT" —
   ;;    it converges inside the SAME macrostep, on the parent's first
   ;;    eventless round.
   ["a guarded `:always` converges on the next event"
    #"(?i)converges?\s+on\s+the\s+\*{0,2}next\s+event"]
   ["a guarded `:always` re-selects on the next event delivered"
    #"(?i)on\s+the\s+next\s+event\s+delivered"]
   ["a guarded `:always` fires one event later"
    #"(?i)just\s+one\s+event\s+later"]

   ;; 4. `:tags` / `:all-state` frozen for the WHOLE macrostep — they are
   ;;    frozen per SELECTION ROUND and re-frozen between rounds.
   ;;
   ;;    NOTE: the corrected guide contains the substring "not for the whole
   ;;    macrostep". These patterns require the ASSERTION ("frozen FOR the
   ;;    whole macrostep", "as of the START of the macrostep"), so the negated
   ;;    teaching does not trip them.
   ["sibling keys are frozen for the whole macrostep"
    #"(?i)frozen\s+for\s+the\s+\*{0,2}whole\s+macrostep"]
   ["sibling keys reflect the configuration at macrostep start"
    #"(?i)as\s+of\s+the\s+\*{0,2}start\*{0,2}\s+of\s+the\s+macrostep"]])

;; ---------------------------------------------------------------------------
;; The load-bearing terms the rewrite installed. Absence = the guide lost the
;; law, which is the revert this guard exists to catch.

(def ^:private required-terms
  [["the parent owns `:always` stabilization"
    #"(?i)`?:always`?\s+stabilization\s+is\s+parent-owned"]
   ["the round is freeze → select → apply"
    #"(?is)\*{2}Freeze\*{2}.{0,400}\*{2}Select\*{2}.{0,400}\*{2}Apply\*{2}"]
   ["the view is re-frozen between rounds"
    #"(?i)between\s+rounds[^\n]{0,80}re-frozen"]
   ["sibling keys are frozen per selection round"
    #"(?i)frozen\s+for\s+the\s+\*{0,2}selection\s+round"]
   ["`:always-depth-limit` counts parent rounds"
    #"(?i)counts\s+parent\s+\*{0,2}rounds"]
   ["birth runs the same parent-owned round process"
    #"(?i)same\s+process\s+runs\s+at\s+\*{0,2}birth"]])

(defn- superseded-hits
  "Every superseded claim `text` reacquired."
  [text]
  (->> superseded-claims
       (filter (fn [[_ pattern]] (re-find pattern text)))
       (mapv first)))

(defn- missing-terms
  "Every load-bearing term `text` no longer carries."
  [text]
  (->> required-terms
       (remove (fn [[_ pattern]] (re-find pattern text)))
       (mapv first)))

;; ---------------------------------------------------------------------------

(deftest parallel-states-guide-keeps-the-parent-owned-round-law
  (testing "the retired region-local claims stay absent"
    (is (= [] (superseded-hits @guide))
        (str "docs/machines/parallel-states.md reacquired superseded "
             "region-local `:always` claims. The law (Spec 005 §Per-region "
             "`:always` / `:after` / `:spawn` scoping) is that the PARENT "
             "settles `:always` in freeze/select/apply rounds across the "
             "whole configuration.")))
  (testing "the load-bearing parent-round terms stay present"
    (is (= [] (missing-terms @guide))
        (str "docs/machines/parallel-states.md lost terminology that carries "
             "the parent-owned round law."))))

(deftest guard-has-teeth
  (testing "every superseded sentence is caught"
    ;; Verbatim from the prose rf2-1rdo4j removed (git show cbaa8192cb^).
    (doseq [[claim sentence]
            [["a region's `:always` settles independently"
              "each region's birth `:always` settles independently as part of the initial step."]
             ["the `:always` loop runs per region"
              "**`:always`** — the microstep loop runs *per region*."]
             ["`:always` settles to a per-region fixed point"
              "that region's new state's `:always` entries are checked and settle to *that region's* fixed point;"]
             ["siblings are not re-evaluated during the `:always` loop"
              "siblings aren't re-evaluated for `:always` on this region's microstep."]
             ["a guarded `:always` converges on the next event"
              "- **A guarded `:always` reading the sibling — converges on the NEXT EVENT.**"]
             ["a guarded `:always` re-selects on the next event delivered"
              "so this re-selects against the committed `:form/valid` on the next event delivered"]
             ["a guarded `:always` fires one event later"
              "it fires, just one event later."]
             ["sibling keys are frozen for the whole macrostep"
              "`:tags` and `:all-state` are frozen for the whole macrostep."]
             ["sibling keys reflect the configuration at macrostep start"
              "the sibling configuration as of the *start* of the macrostep."]]]
      (is (= [claim] (superseded-hits sentence))
            (str "the guard failed to catch a reverted claim: " claim))))

  (testing "the corrected guide's legitimate uses of the same words are not caught"
    ;; Verbatim from the SHIPPED guide. Each sits within a word or two of a
    ;; banned phrasing; none may trip it.
    (doseq [sentence
            [;; the APPLY phase is genuinely per-region
             ";; Both regions handle :reset. :bump-count runs ONCE PER REGION against the"
             ;; TARGETING is genuinely region-scoped
             "## Per-region `:always` / `:after` / `:spawn` — and the `:raise` exception"
             "a region's `:always` entries target states *inside that region*"
             ;; the law is taught by NEGATING the old framing
             "So the loop is not *\"each region settles itself\"* — it is *\"the machine settles, one whole-configuration round at a time\"*."
             "`:tags` and `:all-state` are frozen for the **selection round** that is currently choosing transitions, not for the whole macrostep."
             ;; per-region timers/spawn remain correct region-local claims
             "A sibling region transitioning does **not** cancel this region's in-flight `:after` timer; each region keeps its own timer epoch."]]
      (is (= [] (superseded-hits sentence))
          (str "the guard fired on legitimate shipped prose: " sentence))))

  (testing "a guide stripped of the law is caught by the required-term half"
    (is (seq (missing-terms "Parallel regions are orthogonal axes of one machine."))))

  (testing "the shipped guide satisfies both halves"
    (is (= [] (superseded-hits @guide)))
    (is (= [] (missing-terms @guide)))))
