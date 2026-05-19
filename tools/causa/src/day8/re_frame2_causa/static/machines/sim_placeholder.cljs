(ns day8.re-frame2-causa.static.machines.sim-placeholder
  "Sim mode placeholder — rf2-o5f5f.2 ships the strip cell but NOT the
  Sim view. Sibling bead rf2-r4nao re-hosts the Sim machinery from
  `panels/machine_inspector_sim.cljs` under the Static surface; once
  that bead lands, swap this placeholder for the real renderer.

  This ns deliberately does NOT `:require` any `sim.cljs` — pulling
  the engine in here would couple the two beads' merges. The
  placeholder is a self-contained `<section>` so rf2-r4nao's PR can
  replace the body without touching the strip wiring."
  (:require [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens sans-stack mono-stack type-scale]]))

(def follow-on-bead-id
  "Sibling bead that will land the real Sim renderer."
  "rf2-r4nao")

(defn pill
  "Render the Sim pill in the 4-mode sub-strip. Active when the user
  has selected Sim; otherwise inactive but still clickable (Sim is
  reachable, it just renders the placeholder until rf2-r4nao lands)."
  [{:keys [active? on-click]}]
  [:button
   {:data-testid    "rf-causa-static-machines-pill-sim"
    :role           "tab"
    :aria-selected  (if active? "true" "false")
    :on-click       on-click
    :title          (str "Sim mode (mnemonic: s) — placeholder until "
                         follow-on-bead-id " lands the real Sim view.")
    :aria-label     "Sim mode — placeholder"
    :style {:background    "transparent"
            :border        (str "1px solid "
                                (if active?
                                  (:cyan tokens)
                                  (:border-default tokens)))
            :border-radius "10px"
            :color         (if active? (:cyan tokens) (:text-secondary tokens))
            :cursor        "pointer"
            :font-family   sans-stack
            :font-size     (:caption type-scale)
            :font-weight   (if active? 600 400)
            :padding       "3px 12px"
            :white-space   "nowrap"}}
   "Sim"])

(defn body
  "Render the Sim mode body — a single placeholder card per the bead.
  Replace this fn with the rehosted Sim renderer when rf2-r4nao lands."
  [{:keys [machine-id]}]
  [:section {:data-testid    "rf-causa-static-machines-sim-placeholder"
             :data-machine-id (str machine-id)
             :style {:padding     "16px"
                     :background  (:bg-2 tokens)
                     :color       (:text-secondary tokens)
                     :font-family sans-stack
                     :font-size   (:body type-scale)}}
   [:h2 {:style {:margin "0 0 8px 0"
                 :font-size (:display type-scale)
                 :font-weight 600
                 :color (:text-primary tokens)}}
    "Sim"]
   [:p {:style {:margin "0 0 6px 0"}}
    "Sim — "
    [:strong {:style {:color (:cyan tokens)
                      :font-family mono-stack}}
     follow-on-bead-id]
    " will fill this."]
   [:p {:style {:margin 0
                :font-size (:caption type-scale)
                :color (:text-tertiary tokens)}}
    "The Sim engine is registered on the framework side; rf2-r4nao "
    "re-hosts the UI under the Static surface."]])
