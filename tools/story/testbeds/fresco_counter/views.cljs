(ns fresco-counter.views
  "The subject — views authored in Fresco, and nothing else.

  `rf.fresco/defview` is the whole authoring surface here: a boundary reads its
  own subscriptions with `rf.fresco/sub` and states its intent as a data vector at
  `:on-click` / `:on-input`. There is no adapter call, no React import and
  no props ABI in this file, which is what makes it a fair sample of what
  a programmer on the native substrate actually writes.

  ## What Story needs from a declaration here, and gets for free

  Each `defview` publishes ONE registrar `:view` entry under
  `(keyword \"fresco-counter.views\" \"<sym>\")` carrying its minted head
  at `:handler-fn` (rf2-5qaf4, rf2-kuky.60). A variant in `stories.cljs`
  therefore names `:fresco-counter.views/tally` exactly as a Reagent
  variant names a `reg-view` id, and the `:fresco` render fn the shell
  registers reads the head back with `rf/view` — the same lookup the
  `:reagent` substrate uses, on the same key.

  ## The `data-test` attributes are the play script's grip, not decoration

  `[data-test=hic-count]`, `[data-test=hic-bump]` and
  `[data-test=hic-step]` are what the deck's `:click` / `:type` /
  `:assert-dom` steps address. They are ordinary attributes on ordinary
  elements — nothing in this file is test-only behaviour, and removing
  the deck would leave the views unchanged."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]))

(rf.fresco/defview tally
  "The stateful subject. Reads the tally and its step from the frame the
  canvas scoped, and bumps the tally through an intent vector — so the
  round trip a play script drives (click → event → app-db → repaint) is
  entirely inside the boundary."
  [{:keys [heading]}]
  [:section {:class "hic-tally" :data-test "hic-tally"}
   [:h3 (or heading "Tally")]
   [:output {:data-test "hic-count"} (str (rf.fresco/sub [:fresco-counter/count]))]
   [:button {:data-test "hic-bump"
             :on-click  [:fresco-counter/bump]}
    (str "+" (rf.fresco/sub [:fresco-counter/step]))]
   [:label "step "
    [:input {:data-test "hic-step"
             :type      "text"
             :value     (str (rf.fresco/sub [:fresco-counter/step]))
             :on-input  [:fresco-counter/set-step ::rf.fresco/value]}]]])

(rf.fresco/defview readout
  "A SECOND boundary, so the deck can tell one Fresco view from another
  rather than merely from a Reagent one. Read-only: it proves the
  substrate resolves the variant's frame even with no interaction of its
  own to drive."
  [{:keys [label]}]
  [:p {:data-test "hic-readout"}
   (str (or label "count") " = " (rf.fresco/sub [:fresco-counter/count]))])

;; ---------------------------------------------------------------------------
;; The live-page bridge — minted ONCE, at the top level
;; ---------------------------------------------------------------------------
;;
;; The Story canvas needs none of this: its `:fresco` render fn mints an
;; element per render with `rf.fresco/as-element`, riding the stable `React.memo`
;; wrapper `defview` already made. The LIVE `#/` surface is the other
;; case — a Reagent root whose hiccup has to carry the boundary — and
;; `rf.fresco/as-component` is the door for it.
;;
;; `def`, not a call inside a render: `as-component` ALLOCATES a component,
;; so minting one per render hands React a fresh element type every pass
;; and React answers a fresh type by unmounting the subtree. Once, here,
;; and the live page re-renders the boundary instead of remounting it.

(def tally-component
  "`tally` as a component a Reagent parent can place in hiccup."
  (rf.fresco/as-component tally))

(def readout-component
  "`readout`, likewise."
  (rf.fresco/as-component readout))
