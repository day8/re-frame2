(ns re-frame.parallel-states-guide-truth-jvm-test
  "Holds `docs/machines/parallel-states.md` to the parent-owned `:always` round
  law: four superseded region-local claim families (canonical wording or a
  paraphrase) stay absent from its affirmative prose, and the load-bearing
  terms stay present. `guard-has-teeth` keeps both halves able to fail."
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

(def ^:private claim-per-region-settle
  "region `:always` settles independently (not one parent round)")
(def ^:private claim-loop-per-region
  "the `:always` loop runs per region (not one parent round over all regions)")
(def ^:private claim-converges-next-event
  "a guarded `:always` waits for a later event (not the same macrostep)")
(def ^:private claim-frozen-whole-macrostep
  "sibling keys frozen for the whole macrostep (not per selection round)")

;; One pattern per claim family. Each alternative anchors on the relationship
;; that carries the claim (a region + a settling verb + `on its own`; a frozen
;; word + `whole` + `macrostep`), never a bare keyword, so the guide's
;; legitimate uses of the same words stay clear. `guard-has-teeth` pins both.
(def ^:private superseded-claims
  [;; Deliberately omits `itself` / `themselves`: the guide teaches the law by
   ;; negating `"each region settles itself"`.
   [claim-per-region-settle
    #"(?i)(?:settles?\s+independently|\bregions?\b[^.\n]{0,40}?\b(?:settl|stabili[sz])[a-z]*\b[^.\n]{0,40}?\b(?:on\s+its\s+own|on\s+their\s+own|by\s+itself|by\s+themselves|independently|in\s+isolation|separately)\b)"]

   ;; The loop form needs a loop word; the sibling form keys on `sibling(s)`,
   ;; so the root-`:on` prose "regions ... are left untouched" stays clear.
   [claim-loop-per-region
    #"(?i)(?:loop\s+runs\s+\*{0,2}per[-\s]region|(?:that\s+region'?s|its\s+own)\*{0,2}\s+\*{0,2}fixed\s+point|siblings?\s+are(?:n'?t|\s+not)\s+re-?evaluated|\b(?:microstep|eventless|:?always)\s+loop\b[^.\n]{0,40}?\b(?:per[-\s]region|region[-\s]by[-\s]region|in\s+each\s+region|within\s+each\s+region|region-local|region\s+at\s+a\s+time)\b|\bsiblings?\b[^.\n]{0,40}?\b(?:left\s+untouched|held\s+(?:aside|untouched|fixed|out)|not\s+revisited|not\s+reconsidered|not\s+re-?examined|not\s+re-?checked)\b)"]

   ;; The paraphrase needs a wait/defer verb, so "re-broadcasts ... on the next
   ;; microstep" and "dequeues the next raise" stay clear.
   [claim-converges-next-event
    #"(?i)(?:converges?\s+on\s+the\s+\*{0,2}next\s+event|on\s+the\s+next\s+event\s+delivered|just\s+one\s+event\s+later|\b(?:waits?|waited|defers?|deferred|delays?|delayed|postpones?|postponed|held\s+back|won'?t\s+fire|does\s+not\s+fire|only\s+(?:fires?|moves?|re-?selects?))\b[^.\n]{0,45}?\b(?:until|for|till|on)\b[^.\n]{0,30}?\b(?:another|the\s+next|a\s+later|a\s+future|a\s+subsequent|the\s+following|subsequent|later)\b[^.\n]{0,25}?\bevent\b)"]

   ;; The windows are short enough that the guide's negated teaching ("frozen
   ;; for the selection round ... not for the whole macrostep") never matches.
   [claim-frozen-whole-macrostep
    #"(?i)(?:frozen\s+for\s+the\s+\*{0,2}whole\s+macrostep|as\s+(?:of|(?:it|they)\s+(?:was|were)\s+at)\s+the\s+\*{0,2}(?:start|beginning|outset)\*{0,2}\s+of\s+the\s+macrostep|\b(?:sees?|reads?|observes?|gets?)\b[^.\n]{0,60}?\bat\s+the\s+\*{0,2}(?:start|beginning|outset)\*{0,2}\s+of\s+the\s+macrostep\b|\b(?:frozen|fixed|constant|unchanged|stable|pinned|immutable|unchanging)\b[^.\n]{0,45}?\b(?:whole|entire|throughout|across|for\s+the\s+(?:duration|life)\s+of|until\s+the\s+end\s+of|macrostep\s+entry)\b[^.\n]{0,30}?\bmacrostep\b|\bfrom\s+macrostep\s+(?:entry|start|begin(?:ning)?)\b[^.\n]{0,45}?\b(?:until|to|through|up\s+to)\b[^.\n]{0,30}?\bmacrostep\s+(?:exit|end|finish|completion)\b)"]])

(def ^:private required-terms
  [["the parent owns `:always` stabilization"
    #"(?i)`?:always`?\s+stabilization\s+is\s+parent-owned"]
   ["the round is freeze → select → apply, over the whole configuration"
    #"(?is)parent\s+freezes\s+the\s+whole\s+configuration.{0,250}?\bselects\b.{0,250}?\bapplies\b.{0,120}?\bfreezes\s+again\b"]
   ["the view is re-frozen between rounds"
    #"(?i)between\s+rounds[^\n]{0,80}re-frozen"]
   ["sibling keys are frozen per selection round"
    #"(?i)frozen\s+for\s+the\s+\*{0,2}selection\s+round"]
   ["`:always-depth-limit` counts parent rounds"
    #"(?i)counts\s+parent\s+\*{0,2}rounds"]
   ["birth runs the same parent-owned round process"
    #"(?is)parent\s+settles\s+`?:always`?\s+across\s+the\s+whole\s+configuration.{0,80}?same\s+freeze\s*/\s*select\s*/\s*apply\s+rounds"]])

;; A section under an ATX heading that labels it a negative example (BEFORE —
;; incorrect; do not copy / Historical / Superseded …) may quote a retired
;; claim; it runs to the next heading of equal-or-higher level.
(def ^:private atx-heading
  #"^(#{1,6})\s")

(def ^:private historical-heading
  #"(?i)^#{1,6}\s.*\b(?:incorrect|do[-\s]*not[-\s]*copy|superseded|deprecated|anti-?patterns?|the\s+old\s+(?:model|framing|way)|what\s+not\s+to|historical)\b")

(defn- strip-historical-sections
  [text]
  (loop [lines (str/split-lines text)
         drop-level nil       ;; nil = keeping; int = dropping until heading <= this level
         kept (transient [])]
    (if (empty? lines)
      (str/join "\n" (persistent! kept))
      (let [line  (first lines)
            m     (re-find atx-heading line)
            level (when m (count (second m)))]
        (cond
          ;; a heading of equal-or-higher level closes the section and is re-examined
          drop-level
          (if (and level (<= level drop-level))
            (recur lines nil kept)
            (recur (rest lines) drop-level kept))

          (and level (re-find historical-heading line))
          (recur (rest lines) level kept)

          :else
          (recur (rest lines) nil (conj! kept line)))))))

(defn- superseded-hits
  "Every superseded claim family the affirmative prose reacquired."
  [text]
  (let [affirmative (strip-historical-sections text)]
    (->> superseded-claims
         (filter (fn [[_ pattern]] (re-find pattern affirmative)))
         (mapv first))))

(defn- missing-terms
  [text]
  (->> required-terms
       (remove (fn [[_ pattern]] (re-find pattern text)))
       (mapv first)))

(deftest parallel-states-guide-keeps-the-parent-owned-round-law
  (is (= [] (superseded-hits @guide))
      (str "docs/machines/parallel-states.md reacquired superseded region-local "
           "`:always` claims. The law (Spec 005 §Per-region `:always` / `:after` / "
           "`:spawn` scoping) is that the PARENT settles `:always` in "
           "freeze/select/apply rounds across the whole configuration."))
  (is (= [] (missing-terms @guide))
      "docs/machines/parallel-states.md lost terminology that carries the parent-owned round law."))

(deftest guard-has-teeth
  (testing "every superseded claim is caught — canonical wording and a paraphrase"
    (let [rows [[claim-per-region-settle
                 "each region's birth `:always` settles independently as part of the initial step."]
                [claim-per-region-settle
                 "Each region stabilizes its eventless transitions on its own before its siblings are revisited."]
                [claim-loop-per-region
                 "**`:always`** — the microstep loop runs *per region*."]
                [claim-loop-per-region
                 "that region's new state's `:always` entries are checked and settle to *that region's* fixed point;"]
                [claim-loop-per-region
                 "siblings aren't re-evaluated for `:always` on this region's microstep."]
                [claim-loop-per-region
                 "The eventless loop is run region by region, and the siblings are held aside until the next round."]
                [claim-converges-next-event
                 "- **A guarded `:always` reading the sibling — converges on the NEXT EVENT.**"]
                [claim-converges-next-event
                 "so this re-selects against the committed `:form/valid` on the next event delivered"]
                [claim-converges-next-event
                 "it fires, just one event later."]
                [claim-converges-next-event
                 "A sibling-dependent guard waits until another event arrives."]
                [claim-frozen-whole-macrostep
                 "`:tags` and `:all-state` are frozen for the whole macrostep."]
                [claim-frozen-whole-macrostep
                 "the sibling configuration as of the *start* of the macrostep."]
                [claim-frozen-whole-macrostep
                 "Sibling keys remain fixed from macrostep entry until macrostep exit."]
                [claim-frozen-whole-macrostep
                 "It sees the sibling state as it was at the start of the macrostep."]]]
      (is (= (mapv (comp vector first) rows) (mapv (comp superseded-hits second) rows)))))

  (testing "legitimate guide prose that sits a word or two from a banned phrasing is not caught"
    (is (= [] (filterv (comp seq superseded-hits)
                       [";; Both regions handle :reset. :bump-count runs ONCE PER REGION against the"
                        "## Per-region `:always` / `:after` / `:spawn` — and the `:raise` exception"
                        "a region's `:always` entries target states *inside that region*"
                        "So the loop is not *\"each region settles itself\"* — it is *\"the machine settles, one whole-configuration round at a time\"*."
                        "`:tags` and `:all-state` are frozen for the **selection round** that is currently choosing transitions, not for the whole macrostep."
                        "A sibling region transitioning does **not** cancel this region's in-flight `:after` timer; each region keeps its own timer epoch."
                        "Region `:a` declares `:reset` locally, so on `:reset` the root transition is dropped wholesale, and regions the root *would* have moved are left untouched:"
                        "Those three axes are **orthogonal**: each moves on its own, and any combination is legal."
                        "that internal event re-broadcasts across every region on the next microstep"
                        "the parent settles `:always` to a fixed point before it dequeues the next raise."]))))

  (testing "a retired claim may be quoted under a do-not-copy heading, but not in affirmative prose"
    (is (= [[] [claim-per-region-settle] [claim-frozen-whole-macrostep]]
           (mapv superseded-hits
                 [(str "### BEFORE — incorrect; do not copy\n"
                       "\n"
                       "Each region's `:always` settles independently, and its sibling\n"
                       "keys stay frozen for the whole macrostep until the next event.\n"
                       "\n"
                       "## `:always` stabilization is parent-owned\n"
                       "\n"
                       "The parent settles `:always` in freeze/select/apply rounds.\n")
                  "Each region's `:always` settles independently."
                  "Sibling keys stay frozen for the whole macrostep."]))))

  (testing "a guide stripped of the law is caught by the required-term half"
    (is (seq (missing-terms "Parallel regions are orthogonal axes of one machine.")))))
