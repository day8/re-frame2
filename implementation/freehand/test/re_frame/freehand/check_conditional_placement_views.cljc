(ns re-frame.freehand.check-conditional-placement-views
  "Reader conditionals in the two legal PLACEMENTS a body-level walk misses:
  inside a SET, and around the WHOLE declaration.

  `re-frame.freehand.check-host-divergent-views` proves the checker selects
  the `:cljs` branch rather than the one the JVM reader elides to. It proves
  it in the easy placement — a conditional standing where the body itself
  stands. A conditional is legal anywhere a form is, and two placements are
  invisible to a walk that knows only maps, vectors and seqs:

    * inside a **set** (`#{#?(…)}`, and the splicing `#{#?@(…)}`), which is
      neither a map nor a vector nor a seq, so the conditional survives the
      walk intact and the `:cljs` branch is never selected — the analyzer
      then sees a reader object where the browser build sees a form;
    * around the **whole declaration** (`#?(:clj (v/defview …) :cljs
      (v/defview …))`), where the preserved conditional is not a seq, so
      declaration discovery skips it and the file reports NOTHING at all.

  Both placements false-green: an author asks whether a view is eligible and
  is told yes about a branch the browser build refuses. Each therefore
  appears here twice — a DIVERGENT view whose `:cljs` branch is outside the
  grammar while its `:clj` branch is inside it (the hazard), and a UNIFORM
  view whose branches differ textually but are both inside the grammar (the
  control, so that covering the placement cannot manufacture a false NO).

  The ineligible `:cljs` branches use the two refusals those placements
  naturally produce: a bare `v/sub` reference, which is a reactive read the
  manifest cannot own outside a call head, and a head bound from props,
  which is a runtime value.

  Loaded on the JVM — the checker classifies heads by resolution, so the
  namespace it is pointed at must be live. The JVM reader takes the `:clj`
  branch everywhere here, so every view loads interpreted without incident;
  the `:cljs` branches are reached by the checker alone."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; Placement 1 — inside a set
;; ---------------------------------------------------------------------------

(v/defview set-divergent
  "CLJ branch a constant; CLJS branch a bare `v/sub` — a reactive read
  flowing as a value, which the manifest cannot own."
  [_]
  [:div {:title (str #{#?(:clj 1 :cljs v/sub)})}])

(v/defview set-splice-divergent
  "The same hazard through a SPLICING conditional: `#?@` contributes two
  constants on the JVM and a bare `v/sub` in the browser."
  [_]
  [:div {:title (str #{#?@(:clj [1 2] :cljs [v/sub])})}])

(v/defview set-uniform
  "The control. Both placements, both branches inside the grammar, and the
  two reads are not textually equal — covering sets must not invent a
  refusal, and must leave the set a set."
  [_]
  [:div {:title (str #{#?(:clj 1 :cljs 2)}
                     #{#?@(:clj [3 4] :cljs [5])})}])

;; ---------------------------------------------------------------------------
;; Placement 2 — around the whole declaration
;; ---------------------------------------------------------------------------

#?(:clj  (v/defview declaration-divergent
           "CLJ branch a literal element - eligible."
           [_]
           [:div "the JVM branch is a literal element - eligible"])
   :cljs (v/defview declaration-divergent
           "CLJS branch has a head bound from props - ineligible."
           [{:keys [tag]}]
           [tag {} "the CLJS branch has a dynamic head - ineligible"]))

#?(:clj  (v/defview declaration-uniform
           "Both branches literal elements - eligible on either target."
           [{:keys [label]}]
           [:span.jvm label])
   :cljs (v/defview declaration-uniform
           "Both branches literal elements - eligible on either target."
           [{:keys [label]}]
           [:span.spa label]))
