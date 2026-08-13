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

  - the four superseded claims stay ABSENT, and
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
  sentence is not.

  rf2-b0vvu strengthens the superseded half along two axes the first cut
  (rf2-nqovj) left open, without turning this into a semantic/NLP parser:

  - PARAPHRASE. The retired claims were anchored on their exact removed
    wording, so a natural editor/AI paraphrase using different surface words
    slipped through (e.g. `settles independently` → `stabilizes … on its own`;
    `frozen for the whole macrostep` → `fixed from macrostep entry until
    macrostep exit`). Each of the four claim FAMILIES now carries a small
    relationship/token pattern that catches its original AND a paraphrase,
    while still steering clear of the legitimate near-matches.

  - HISTORICAL / INCORRECT-EXAMPLE POLARITY. A retired claim quoted under an
    explicit negative-example heading (`### BEFORE — incorrect; do not copy`,
    `Historical …`, `Superseded …`) is a labelled anti-example, not the guide
    reasserting the claim — the old cut rejected it. `superseded-hits` now
    strips such heading-scoped sections before scanning, so a labelled block
    may quote a retired claim, while the SAME claim in ordinary affirmative
    prose still fails."
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
;; The four superseded claim FAMILIES rf2-1rdo4j retired.
;;
;; One entry per family, not per removed sentence: each family's pattern is an
;; alternation over the original removed phrasing PLUS a small
;; relationship/token paraphrase form. Collapsing to families keeps the return
;; of `superseded-hits` one label per reverted idea, so an original and its
;; paraphrase both report the same family rather than double-counting.
;;
;; Every alternative is anchored on the phrasing that carries the CLAIM (a
;; relationship between tokens — `region` + a settling verb + `on its own`;
;; a `frozen`/`fixed` word + `whole`/`entire` + `macrostep`), never on a bare
;; keyword, so the corrected guide's legitimate uses of the same words stay
;; clear of it. `guard-has-teeth` proves both halves for each family.

(def ^:private claim-per-region-settle
  "region `:always` settles independently (not one parent round)")
(def ^:private claim-loop-per-region
  "the `:always` loop runs per region (not one parent round over all regions)")
(def ^:private claim-converges-next-event
  "a guarded `:always` waits for a later event (not the same macrostep)")
(def ^:private claim-frozen-whole-macrostep
  "sibling keys frozen for the whole macrostep (not per selection round)")

(def ^:private superseded-claims
  [;; 1. Per-region settling: "each region's birth `:always` settles
   ;;    independently". The parent runs birth rounds across the whole
   ;;    configuration. Paraphrase caught: a region stabilizes/settles
   ;;    "on its own" / "by itself" / "independently".
   ;;
   ;;    The paraphrase alternative deliberately does NOT list `itself` /
   ;;    `themselves`, so the guide's explicit negation of the old framing —
   ;;    `not "each region settles itself"` — is not caught.
   [claim-per-region-settle
    #"(?i)(?:settles?\s+independently|\bregions?\b[^.\n]{0,40}?\b(?:settl|stabili[sz])[a-z]*\b[^.\n]{0,40}?\b(?:on\s+its\s+own|on\s+their\s+own|by\s+itself|by\s+themselves|independently|in\s+isolation|separately)\b)"]

   ;; 2. "per region" applied to the `:always` LOOP (not to the apply phase):
   ;;    "the microstep loop runs *per region*", settling to "*that region's*
   ;;    fixed point", with "siblings aren't re-evaluated". Paraphrase caught:
   ;;    an eventless/microstep LOOP that runs "region by region", or SIBLINGS
   ;;    "held aside" / "left untouched" / "not revisited".
   ;;
   ;;    Both paraphrase alternatives are anchored so the correct guide stays
   ;;    clear: the loop form needs an actual loop word (`the loop is not …`
   ;;    has none of `microstep`/`eventless`/`:always` before `loop`), and the
   ;;    sibling form keys on `sibling(s)`, so the root-`:on` suppression prose
   ;;    "regions … are left untouched" (about REGIONS, not SIBLINGS) is clear.
   [claim-loop-per-region
    #"(?i)(?:loop\s+runs\s+\*{0,2}per[-\s]region|(?:that\s+region'?s|its\s+own)\*{0,2}\s+\*{0,2}fixed\s+point|siblings?\s+are(?:n'?t|\s+not)\s+re-?evaluated|\b(?:microstep|eventless|:?always)\s+loop\b[^.\n]{0,40}?\b(?:per[-\s]region|region[-\s]by[-\s]region|in\s+each\s+region|within\s+each\s+region|region-local|region\s+at\s+a\s+time)\b|\bsiblings?\b[^.\n]{0,40}?\b(?:left\s+untouched|held\s+(?:aside|untouched|fixed|out)|not\s+revisited|not\s+reconsidered|not\s+re-?examined|not\s+re-?checked)\b)"]

   ;; 3. A guarded `:always` reading a sibling "converges on the NEXT EVENT" —
   ;;    it converges inside the SAME macrostep, on the parent's first
   ;;    eventless round. Paraphrase caught: a guard/transition that
   ;;    waits/defers/"only fires" UNTIL/FOR a later/next/another EVENT.
   ;;
   ;;    The paraphrase needs a wait/defer verb, so the correct guide's
   ;;    "re-broadcasts … on the next microstep" and "before it dequeues the
   ;;    next raise" (no wait verb; not an EVENT) stay clear.
   [claim-converges-next-event
    #"(?i)(?:converges?\s+on\s+the\s+\*{0,2}next\s+event|on\s+the\s+next\s+event\s+delivered|just\s+one\s+event\s+later|\b(?:waits?|waited|defers?|deferred|delays?|delayed|postpones?|postponed|held\s+back|won'?t\s+fire|does\s+not\s+fire|only\s+(?:fires?|moves?|re-?selects?))\b[^.\n]{0,45}?\b(?:until|for|till|on)\b[^.\n]{0,30}?\b(?:another|the\s+next|a\s+later|a\s+future|a\s+subsequent|the\s+following|subsequent|later)\b[^.\n]{0,25}?\bevent\b)"]

   ;; 4. `:tags` / `:all-state` frozen for the WHOLE macrostep — they are
   ;;    frozen per SELECTION ROUND and re-frozen between rounds. Paraphrase
   ;;    caught: keys "fixed"/"frozen"/"constant" for the WHOLE/ENTIRE
   ;;    macrostep, or "from macrostep entry … until macrostep exit".
   ;;
   ;;    NOTE: the corrected guide contains "not for the whole macrostep" and
   ;;    "frozen for the **selection round**". The original alternatives
   ;;    require the contiguous ASSERTION ("frozen FOR the whole macrostep"),
   ;;    and the paraphrase alternatives require a `whole`/`entire`/`macrostep`
   ;;    token within a short window of the `fixed`/`frozen` word — which the
   ;;    negated teaching (`frozen for the selection round … not for the whole
   ;;    macrostep`, `whole` ~80 chars away) never satisfies.
   ;;
   ;;    rf2-s41i adds the SEEN-AT axis. The rf2-9d0d rewrite shipped "It sees
   ;;    the sibling state as it was at the start of the macrostep." on main —
   ;;    the superseded claim in a paraphrase none of the alternatives above
   ;;    reached (no `frozen`/`fixed` word at all, and `as it WAS AT` rather
   ;;    than the pinned `as OF`). Two alternatives close it: the `as of / as it
   ;;    was at ... the start of the macrostep` family, and an observation verb
   ;;    (`sees`/`reads`/`observes`) within a short window of `at the start of
   ;;    the macrostep`. Neither can reach the corrected teaching, which never
   ;;    dates the frozen view to the macrostep at all.
   [claim-frozen-whole-macrostep
    #"(?i)(?:frozen\s+for\s+the\s+\*{0,2}whole\s+macrostep|as\s+(?:of|(?:it|they)\s+(?:was|were)\s+at)\s+the\s+\*{0,2}(?:start|beginning|outset)\*{0,2}\s+of\s+the\s+macrostep|\b(?:sees?|reads?|observes?|gets?)\b[^.\n]{0,60}?\bat\s+the\s+\*{0,2}(?:start|beginning|outset)\*{0,2}\s+of\s+the\s+macrostep\b|\b(?:frozen|fixed|constant|unchanged|stable|pinned|immutable|unchanging)\b[^.\n]{0,45}?\b(?:whole|entire|throughout|across|for\s+the\s+(?:duration|life)\s+of|until\s+the\s+end\s+of|macrostep\s+entry)\b[^.\n]{0,30}?\bmacrostep\b|\bfrom\s+macrostep\s+(?:entry|start|begin(?:ning)?)\b[^.\n]{0,45}?\b(?:until|to|through|up\s+to)\b[^.\n]{0,30}?\bmacrostep\s+(?:exit|end|finish|completion)\b)"]])

;; ---------------------------------------------------------------------------
;; The load-bearing terms the rewrite installed. Absence = the guide lost the
;; law, which is the revert this guard exists to catch.

;; rf2-s41i re-pins two of these onto the shorter prose the rf2-9d0d guide
;; rewrite installed. Both pins move from a phrasing/markup form to the CLAIM
;; the new prose makes; neither is relaxed. The other four are unchanged,
;; because the rewritten guide still carries them word for word.
;;
;; - freeze → select → apply was pinned on a **bolded** three-step list. The
;;   rewrite states the same round in running prose, so the pin now anchors on
;;   the ORDER (freeze the whole configuration → select → apply → freeze again),
;;   which is the law; the bold markup was never the law.
;; - birth was pinned on "the same process runs at **birth**". The rewrite says
;;   the parent settles `:always` across the whole configuration at birth using
;;   "the same freeze / select / apply rounds used after an event" — the same
;;   claim, so the pin follows it.

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

;; ---------------------------------------------------------------------------
;; Historical / incorrect-example polarity.
;;
;; A retired claim quoted under a heading that explicitly frames its section as
;; a NEGATIVE example (BEFORE — incorrect; do not copy / Historical … /
;; Superseded …) is a labelled anti-example, not the guide reasserting the
;; claim. Strip those heading-scoped sections before scanning, so a labelled
;; block may quote a retired claim while the SAME claim in ordinary affirmative
;; prose still trips the guard.
;;
;; This is a light ATX-heading scan, not a Markdown parser: a section runs from
;; a negative-example heading to the next heading of equal-or-higher level.

(def ^:private atx-heading
  #"^(#{1,6})\s")

(def ^:private historical-heading
  #"(?i)^#{1,6}\s.*\b(?:incorrect|do[-\s]*not[-\s]*copy|superseded|deprecated|anti-?patterns?|the\s+old\s+(?:model|framing|way)|what\s+not\s+to|historical)\b")

(defn- strip-historical-sections
  "Drop the body of any section whose ATX heading explicitly frames it as a
  negative example. Everything else — ordinary, affirmative teaching prose — is
  retained and scanned."
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
          ;; inside a historical section: keep dropping until a heading of
          ;; equal-or-higher level closes it (that heading is re-examined).
          drop-level
          (if (and level (<= level drop-level))
            (recur lines nil kept)
            (recur (rest lines) drop-level kept))

          ;; a heading that opens a historical section
          (and level (re-find historical-heading line))
          (recur (rest lines) level kept)

          :else
          (recur (rest lines) nil (conj! kept line)))))))

(defn- superseded-hits
  "Every superseded claim FAMILY the affirmative prose reacquired. Sections
  under an explicit historical/incorrect-example heading are exempt."
  [text]
  (let [affirmative (strip-historical-sections text)]
    (->> superseded-claims
         (filter (fn [[_ pattern]] (re-find pattern affirmative)))
         (mapv first))))

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
  (testing "every superseded claim is caught — original wording AND a paraphrase"
    ;; Originals are verbatim from the prose rf2-1rdo4j removed
    ;; (git show cbaa8192cb^); each `paraphrase` restates the SAME claim with
    ;; different surface words. Both must report the claim's family.
    (doseq [[family sentence]
            [;; family 1 — per-region settling
             [claim-per-region-settle
              "each region's birth `:always` settles independently as part of the initial step."]
             [claim-per-region-settle          ; paraphrase (rf2-b0vvu mutation)
              "Each region stabilizes its eventless transitions on its own before its siblings are revisited."]

             ;; family 2 — the `:always` loop runs per region
             [claim-loop-per-region
              "**`:always`** — the microstep loop runs *per region*."]
             [claim-loop-per-region
              "that region's new state's `:always` entries are checked and settle to *that region's* fixed point;"]
             [claim-loop-per-region
              "siblings aren't re-evaluated for `:always` on this region's microstep."]
             [claim-loop-per-region             ; paraphrase
              "The eventless loop is run region by region, and the siblings are held aside until the next round."]

             ;; family 3 — a guarded `:always` waits for a later event
             [claim-converges-next-event
              "- **A guarded `:always` reading the sibling — converges on the NEXT EVENT.**"]
             [claim-converges-next-event
              "so this re-selects against the committed `:form/valid` on the next event delivered"]
             [claim-converges-next-event
              "it fires, just one event later."]
             [claim-converges-next-event        ; paraphrase (rf2-b0vvu mutation)
              "A sibling-dependent guard waits until another event arrives."]

             ;; family 4 — sibling keys frozen for the whole macrostep
             [claim-frozen-whole-macrostep
              "`:tags` and `:all-state` are frozen for the whole macrostep."]
             [claim-frozen-whole-macrostep
              "the sibling configuration as of the *start* of the macrostep."]
             [claim-frozen-whole-macrostep      ; paraphrase (rf2-b0vvu mutation)
              "Sibling keys remain fixed from macrostep entry until macrostep exit."]
             ;; rf2-s41i — the paraphrase that actually SHIPPED to main in the
             ;; rf2-9d0d guide rewrite, verbatim. It carried no `frozen`/`fixed`
             ;; word, so every alternative above missed it.
             [claim-frozen-whole-macrostep
              "It sees the sibling state as it was at the start of the macrostep."]]]
      (is (= [family] (superseded-hits sentence))
          (str "the guard failed to catch (exactly) the claim: " family
               "\n  in: " sentence))))

  (testing "the three exact paraphrase mutations reproduced in rf2-b0vvu fail the guard"
    (doseq [mutation ["Each region stabilizes its eventless transitions on its own before its siblings are revisited."
                      "Sibling keys remain fixed from macrostep entry until macrostep exit."
                      "A sibling-dependent guard waits until another event arrives."]]
      (is (seq (superseded-hits mutation))
          (str "a reproduced paraphrase mutation slipped through: " mutation))))

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
             "A sibling region transitioning does **not** cancel this region's in-flight `:after` timer; each region keeps its own timer epoch."
             ;; root-`:on` suppression: REGIONS (not siblings) are left untouched
             "Region `:a` declares `:reset` locally, so on `:reset` the root transition is dropped wholesale, and regions the root *would* have moved are left untouched:"
             ;; the orthogonal AXES each move on their own — not the `:always` loop
             "Those three axes are **orthogonal**: each moves on its own, and any combination is legal."
             ;; a raised event re-broadcasts on the next MICROSTEP (not event), no wait
             "that internal event re-broadcasts across every region on the next microstep"
             ;; eventless work drains before the next RAISE (not event), no wait
             "the parent settles `:always` to a fixed point before it dequeues the next raise."]]
      (is (= [] (superseded-hits sentence))
          (str "the guard fired on legitimate shipped prose: " sentence))))

  (testing "an explicitly labelled historical / do-not-copy section may quote a retired claim"
    ;; A BEFORE/incorrect block is a labelled anti-example, not a revert.
    (let [before-block
          (str "### BEFORE — incorrect; do not copy\n"
               "\n"
               "Each region's `:always` settles independently, and its sibling\n"
               "keys stay frozen for the whole macrostep until the next event.\n"
               "\n"
               "## `:always` stabilization is parent-owned\n"
               "\n"
               "The parent settles `:always` in freeze/select/apply rounds.\n")]
      (is (= [] (superseded-hits before-block))
          (str "a retired claim quoted under an explicit BEFORE/incorrect/"
               "do-not-copy heading must be treated as a labelled anti-example, "
               "not as the guide reasserting the claim.")))
    ;; POLARITY: the SAME claims in ordinary affirmative prose still fail —
    ;; the exemption is heading-scoped, not a blanket pass on the phrasing.
    (is (seq (superseded-hits "Each region's `:always` settles independently."))
        "the same claim in affirmative prose (no historical heading) must still trip the guard")
    (is (seq (superseded-hits "Sibling keys stay frozen for the whole macrostep."))
        "the same claim in affirmative prose (no historical heading) must still trip the guard"))

  (testing "a guide stripped of the law is caught by the required-term half"
    (is (seq (missing-terms "Parallel regions are orthogonal axes of one machine."))))

  (testing "the shipped guide satisfies both halves"
    (is (= [] (superseded-hits @guide)))
    (is (= [] (missing-terms @guide)))))
