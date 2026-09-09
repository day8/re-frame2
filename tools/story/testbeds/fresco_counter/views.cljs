(ns hicasso-counter.views
  "The subject — views authored in Hicasso, and nothing else.

  `rf.hicasso/defview` is the whole authoring surface here: a boundary reads its
  own subscriptions with `rf.hicasso/sub` and states its intent as a data vector at
  `:on-click` / `:on-input`. There is no adapter call, no React import and
  no props ABI in this file, which is what makes it a fair sample of what
  a programmer on the native substrate actually writes.

  ## What Story needs from a declaration here, and gets for free

  Each `defview` publishes ONE registrar `:view` entry under
  `(keyword \"hicasso-counter.views\" \"<sym>\")` carrying its minted head
  at `:handler-fn` (rf2-5qaf4, rf2-kuky.60). A variant in `stories.cljs`
  therefore names `:hicasso-counter.views/tally` exactly as a Reagent
  variant names a `reg-view` id, and the `:hicasso` render fn the shell
  registers reads the head back with `rf/view` — the same lookup the
  `:reagent` substrate uses, on the same key.

  ## The `data-test` attributes are the play script's grip, not decoration

  `[data-test=hic-count]`, `[data-test=hic-bump]` and
  `[data-test=hic-step]` are what the deck's `:click` / `:type` /
  `:assert-dom` steps address. They are ordinary attributes on ordinary
  elements — nothing in this file is test-only behaviour, and removing
  the deck would leave the views unchanged."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]))

(rf.hicasso/defview tally
  "The stateful subject. Reads the tally and its step from the frame the
  canvas scoped, and bumps the tally through an intent vector — so the
  round trip a play script drives (click → event → app-db → repaint) is
  entirely inside the boundary."
  [{:keys [heading]}]
  [:section {:class "hic-tally" :data-test "hic-tally"}
   [:h3 (or heading "Tally")]
   [:output {:data-test "hic-count"} (str (rf.hicasso/sub [:hicasso-counter/count]))]
   [:button {:data-test "hic-bump"
             :on-click  [:hicasso-counter/bump]}
    (str "+" (rf.hicasso/sub [:hicasso-counter/step]))]
   [:label "step "
    [:input {:data-test "hic-step"
             :type      "text"
             :value     (str (rf.hicasso/sub [:hicasso-counter/step]))
             :on-input  [:hicasso-counter/set-step ::rf.hicasso/value]}]]])

(rf.hicasso/defview readout
  "A SECOND boundary, so the deck can tell one Hicasso view from another
  rather than merely from a Reagent one. Read-only: it proves the
  substrate resolves the variant's frame even with no interaction of its
  own to drive."
  [{:keys [label]}]
  [:p {:data-test "hic-readout"}
   (str (or label "count") " = " (rf.hicasso/sub [:hicasso-counter/count]))])

;; ---------------------------------------------------------------------------
;; The live-page bridge — minted ONCE, at the top level
;; ---------------------------------------------------------------------------
;;
;; The Story canvas needs none of this: its `:hicasso` render fn mints an
;; element per render with `rf.hicasso/as-element`, riding the stable `React.memo`
;; wrapper `defview` already made. The LIVE `#/` surface is the other
;; case — a Reagent root whose hiccup has to carry the boundary — and
;; `rf.hicasso/as-component` is the door for it.
;;
;; `def`, not a call inside a render: `as-component` ALLOCATES a component,
;; so minting one per render hands React a fresh element type every pass
;; and React answers a fresh type by unmounting the subtree. Once, here,
;; and the live page re-renders the boundary instead of remounting it.

(def tally-component
  "`tally` as a component a Reagent parent can place in hiccup."
  (rf.hicasso/as-component tally))

(def readout-component
  "`readout`, likewise."
  (rf.hicasso/as-component readout))
