(ns day8.re-frame2-xray.panels.hicasso
  "Hicasso tab — the glass on the live tier (rf2-hic-023).

  Four views over one evidence surface, answering the four questions Spec
  SN §10 says a developer actually asks of a view substrate:

    - **Mounted** — which boundaries are mounted, over which frames?
    - **Reads** — which boundaries read each subscription, and at what
      fan-out?
    - **Intents** — what was dispatched, in order, inside the retained
      window?
    - **Why** — which reads changed, and what does that prove?

  ## The tab's job is to make the LOSS legible, not to hide it

  Hicasso's evidence door refuses to encode unknown as an empty
  collection, and this panel's whole design follows from taking that
  seriously one layer further out. Three things fall out of it:

  1. **Three empties, three sentences.** *Not running Hicasso*, *running a
     schema this build cannot parse* and *running with nothing mounted*
     are unrelated facts with unrelated remedies, and each renders under
     its own testid with its own prose. A tab that showed one blank table
     for all three would verify the absence of bad news.
  2. **Every absence is a CHIP, and the five chips differ.** `capped`,
     `opaque`, `host-opaque`, `uncorrelated` and `unknown` render with
     different words under different testids, so the distinction survives
     the trip from the schema to the screen — which is where a schema
     guarantee usually dies.
  3. **Both halves of an explanation are on screen at once.** The Why view
     puts what the epoch stamps PROVE beside the reason the causal link
     cannot be made. Showing only the first would be a guess; showing only
     the second would waste the half that is real.

  ## Pure hiccup + helpers

  Same contract as every Xray panel — `rf/reg-view` and pure hiccup, no
  Reagent or UIx reference, no component-local state. The data → data
  projection lives in `hicasso_helpers.cljc` so the algebra runs under the
  JVM unit-test target; the live read seam is `hicasso_reads.cljs`, which
  passes the producer's envelopes through unchanged.

  Normative owner: `tools/xray/spec/027-Hicasso-Evidence.md`."
  (:require [clojure.string :as string]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]
            [day8.re-frame2-xray.panels.hicasso-reads :as reads]
            [day8.re-frame2-xray.panels.overflow-indicator :as overflow]
            [day8.re-frame2-xray.panels.common-helpers :as ch]
            [day8.re-frame2-xray.theme.section :as section]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack with-alpha]]
            [re-frame.core :as rf]))

(def ^:private panel-id "hicasso")

;; ---- styles --------------------------------------------------------------

(def ^:private panel-root-style
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      "14px"})

(def ^:private scroll-style {:flex 1 :overflow "auto"})

(def ^:private strip-style
  {:display "flex" :gap "4px" :padding "8px 12px"
   :border-bottom (str "1px solid " (:border-subtle tokens))})

(defn- pill-style [selected?]
  {:padding "3px 10px" :border-radius "10px" :cursor "pointer"
   :font-family sans-stack :font-size "11px"
   :border (str "1px solid " (if selected?
                               (:accent tokens)
                               (:border-default tokens)))
   :background (if selected? (with-alpha :accent 18) "transparent")
   :color (if selected? (:accent tokens) (:text-secondary tokens))})

(def ^:private summary-style
  {:padding "6px 12px" :font-family mono-stack :font-size "11px"
   :color (:text-tertiary tokens)})

(def ^:private state-style
  {:margin "8px 12px" :padding "10px 12px" :border-radius "6px"
   :background (:bg-1 tokens)
   :border (str "1px solid " (:border-subtle tokens))
   :color (:text-secondary tokens)
   :font-family sans-stack :font-size "12px" :line-height "1.5"})

(def ^:private mismatch-style
  (assoc state-style
         :background (with-alpha :warning 12)
         :border (str "1px solid " (with-alpha :warning 40))))

(def ^:private chip-style
  {:display "inline-block" :margin-left "6px" :padding "1px 6px"
   :border-radius "8px" :font-family sans-stack :font-size "10px"
   :background (with-alpha :warning 12)
   :border (str "1px solid " (with-alpha :warning 40))
   :color (:text-secondary tokens)})

(def ^:private row-style
  {:padding "6px 0" :border-bottom (str "1px dotted " (:border-subtle tokens))})

(def ^:private detail-style
  {:color (:text-tertiary tokens) :font-family mono-stack :font-size "11px"
   :margin-top "3px"})

;; ---- the absence chip — the five states, kept apart -----------------------

(defn- loss-chip
  "Render one absence so a reader can SEE which of the five it is.

  The testid carries the kind, so a browser assertion selecting
  `…-loss-cap` can never match an `…-loss-uncorrelated` row — which is
  what makes \"the loss states are visibly distinguishable\" a checkable
  claim rather than a design intention."
  [testid-stem chip]
  (when chip
    [:span {:data-testid (str testid-stem "-loss-" (:testid-suffix chip))
            :title       (:says chip)
            :style       chip-style}
     (:short chip)
     (when (contains? chip :dropped)
       (str " · " (hh/dropped-label (:dropped chip))))]))

(defn- absence-note
  "The full sentence beneath a chip, for the one place per view where the
  reader needs the reason spelled out rather than hovered."
  [testid chip]
  (when chip
    [:div {:data-testid testid :style (assoc detail-style :margin-top "6px")}
     (str (:short chip) " — " (:says chip))]))

;; ---- the three non-live states -------------------------------------------

(defn- presence-note
  "The honest empty. Three states, three testids, three sentences — see
  the ns docstring for why collapsing them would undo the schema."
  [state]
  (when-some [{:keys [testid-suffix says]} (get hh/presence-copy state)]
    [:div {:data-testid (str "rf-xray-" panel-id "-" testid-suffix)
           :style       (if (= :mismatch state) mismatch-style state-style)}
     says]))

;; ---- view 1 — mounted boundaries -----------------------------------------

(defn- mounted-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-mounted")}
     (or (presence-note (hh/presence envelope (empty? rows)))
         [:<>
          (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
                (concat
                  (for [row shown]
                    ^{:key (:slug row)}
                    [:li {:data-testid (str "rf-xray-" panel-id "-boundary-" (:slug row))
                          :style       row-style}
                     [:div
                      [:span {:style {:font-family mono-stack}} (:label row)]
                      (loss-chip (str "rf-xray-" panel-id "-boundary-" (:slug row) "-view")
                                 (:view-chip row))
                      (loss-chip (str "rf-xray-" panel-id "-boundary-" (:slug row) "-frame")
                                 (:frame-chip row))]
                     [:div {:style detail-style}
                      (string/join " · "
                                   (remove nil?
                                           [(str (:instances row) " instance"
                                                 (when (not= 1 (:instances row)) "s"))
                                            (when-not (hh/unknown? (:frame row))
                                              (str "frame " (hh/format-id (:frame row))))
                                            (str (count (:reads row)) " read"
                                                 (when (not= 1 (count (:reads row))) "s"))]))]])
                  [(overflow/overflow-row {:panel-id panel-id :over-cap? over? :hidden-count hidden})]))
          (absence-note (str "rf-xray-" panel-id "-mounted-naming")
                        (hh/loss-chip (:loss (:naming envelope)) hh/unknown))
          (absence-note (str "rf-xray-" panel-id "-mounted-host")
                        (hh/loss-chip (:loss (:host envelope)) hh/unknown))])]))

;; ---- view 2 — read attribution -------------------------------------------

(defn- attribution-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-attribution")}
     (or (presence-note (hh/presence envelope (empty? rows)))
         (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
               (concat
                 (for [row shown]
                   ^{:key (:slug row)}
                   [:li {:data-testid (str "rf-xray-" panel-id "-edge-" (:slug row))
                         :style       row-style}
                    [:div [:span {:style {:font-family mono-stack}}
                           (hh/format-id (:sub-id row))]
                     [:span {:style (assoc detail-style :display "inline" :margin-left "8px")}
                      (str "fan-out " (:fan-out row))]]
                    [:div {:data-testid (str "rf-xray-" panel-id "-edge-" (:slug row) "-readers")
                           :style       detail-style}
                     (str "read by " (string/join ", " (map :label (:readers row))))]])
                 [(overflow/overflow-row {:panel-id (str panel-id "-attribution") :over-cap? over? :hidden-count hidden})])))]))

;; ---- view 3 — the intent stream ------------------------------------------

(defn- intents-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-intents")}
     (or (presence-note (hh/presence envelope (empty? rows)))
         [:<>
          (into [:ol {:style {:list-style "none" :margin 0 :padding 0}}]
                (concat
                  (for [[i row] (map-indexed vector shown)]
                    ^{:key i}
                    [:li {:data-testid (str "rf-xray-" panel-id "-intent-" (:slug row))
                          :style       row-style}
                     [:div [:span {:style {:font-family mono-stack}}
                            (hh/format-id (:event-id row))]
                      [:span {:style (assoc detail-style :display "inline" :margin-left "8px")}
                       (str (:arg-count row) " arg"
                            (when (not= 1 (:arg-count row)) "s")
                            " (not carried)")]]
                     (when (seq (:sub-ids row))
                       [:div {:style detail-style}
                        (str "recomputed " (string/join ", " (map hh/format-id (:sub-ids row))))])])
                  [(overflow/overflow-row {:panel-id (str panel-id "-intents") :over-cap? over? :hidden-count hidden})]))
          (absence-note (str "rf-xray-" panel-id "-intents-origin")
                        (hh/loss-chip (:loss (:origin envelope)) hh/unknown))])]))

;; ---- view 4 — explain render ---------------------------------------------

(defn- explain-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-explain")}
     (or (presence-note (hh/presence envelope (empty? rows)))
         (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
               (concat
                 (for [row shown]
                   ^{:key (:slug row)}
                   [:li {:data-testid (str "rf-xray-" panel-id "-explain-" (:slug row))
                         :style       row-style}
                    [:div [:span {:style {:font-family mono-stack}} (:label row)]]
                    ;; THE PROVEN HALF — off the cells' own epoch stamps.
                    [:div {:data-testid (str "rf-xray-" panel-id "-explain-" (:slug row) "-proven")
                           :style       detail-style}
                     (if (:proven? row)
                       (str "moved most recently: "
                            (string/join ", " (map hh/format-id (:latest-reads row)))
                            " · snapshot " (:snapshot row)
                            " · epoch " (:peak-epoch row))
                       "this boundary reads nothing, so no epoch moved for it")]
                    ;; THE UNCORRELATED HALF — never blended with the above.
                    [:div {:data-testid (str "rf-xray-" panel-id "-explain-" (:slug row) "-cause")
                           :style       detail-style}
                     "cause: "
                     (loss-chip (str "rf-xray-" panel-id "-explain-" (:slug row) "-cause")
                                (:cause-chip row))
                     (if (:leads-known? row)
                       (str "  " (count (:leads row)) " lead"
                            (when (not= 1 (count (:leads row))) "s")
                            (when (seq (:leads row))
                              (str ": " (string/join ", "
                                                     (map #(hh/format-id (:event-id %))
                                                          (:leads row))))))
                       "  leads not searched — the window holds nothing")]])
                 [(overflow/overflow-row {:panel-id (str panel-id "-explain") :over-cap? over? :hidden-count hidden})])))]))

;; ---- the sub-strip and the panel -----------------------------------------

(defn- sub-strip
  [selected]
  (into [:div {:data-testid (str "rf-xray-" panel-id "-sub-strip")
               :role        "tablist"
               :style       strip-style}]
        (for [{:keys [id label asks]} hh/sub-modes]
          ^{:key id}
          [:button {:data-testid   (str "rf-xray-" panel-id "-sub-" (name id))
                    :role          "tab"
                    :aria-selected (= id selected)
                    :title         asks
                    :on-click      #(rf/dispatch [:rf.xray.hicasso/set-view id])
                    :style         (pill-style (= id selected))}
           label])))

(rf/reg-view Panel
  "The Hicasso tab: a sub-strip over four views of one evidence surface.

  Every view derefs `:rf.xray.hicasso/data`, which takes all four
  envelopes in one turn — the four rosters are projections of ONE runtime
  state, and reading them across two turns would let a mount land between
  the census and the edges."
  []
  (let [selected (hh/normalise-sub-mode @(rf/subscribe [:rf.xray.hicasso/view]))
        {:keys [envelopes] :as data} @(rf/subscribe [:rf.xray.hicasso/data])
        envelope (get envelopes (case selected
                                  :mounted     :mounted-boundaries
                                  :attribution :read-attribution
                                  :intents     :intents
                                  :explain     :explain-render))]
    [:section {:data-testid (str "rf-xray-" panel-id) :style panel-root-style}
     (sub-strip selected)
     [:div {:data-testid (str "rf-xray-" panel-id "-summary") :style summary-style}
      (or (hh/read-summary envelope) "no envelope")]
     [:div {:style scroll-style}
      (section/section-row
        {:label  (string/upper-case (:label (first (filter #(= selected (:id %))
                                                           hh/sub-modes))))
         :testid (str "rf-xray-" panel-id "-section")}
        (case selected
          :mounted     [mounted-view     {:envelope envelope :rows (:mounted data)}]
          :attribution [attribution-view {:envelope envelope :rows (:attribution data)}]
          :intents     [intents-view     {:envelope envelope :rows (:intents data)}]
          :explain     [explain-view     {:envelope envelope :rows (:explain data)}]))]]))

;; ---- installation --------------------------------------------------------

(defn install!
  []
  ;; The selected sub-view. Ordinary app-db state under Xray's own
  ;; `:rf.xray.hicasso/*` prefix, normalised on write so a stale
  ;; localStorage value or a hand-dispatched id cannot leave the panel on a
  ;; view that does not exist.
  (rf/reg-event :rf.xray.hicasso/set-view
    (fn [{:keys [db]} [_ view]]
      {:db (assoc db :hicasso-view (hh/normalise-sub-mode view))}))

  ;; ONE sub, taking all four envelopes in one turn and shaping every
  ;; view's rows from them. Not four subs: the rosters are projections of
  ;; a single runtime state, and four independent recomputes could
  ;; interleave with a mount and show an edge whose boundary is not in the
  ;; census. It does not compose off an `:rf.xray/*` app-db slot because
  ;; the Hicasso tables are a process-global fact — they live in the
  ;; inspected application's runtime, not Xray's app-db — but it DOES
  ;; compose off `:rf.xray/trace-buffer` as a re-fire tick, so the panel
  ;; refreshes as the inspected application runs.
  (rf/reg-sub :rf.xray.hicasso/view
    (fn [db _query]
      (hh/normalise-sub-mode (:hicasso-view db))))

  (rf/reg-sub :rf.xray.hicasso/data
    :<- [:rf.xray/trace-buffer]
    (fn [_tick _query]
      (let [envelopes (reads/evidence)]
        {:envelopes   envelopes
         :mounted     (hh/mounted-rows     (:mounted-boundaries envelopes))
         :attribution (hh/attribution-rows (:read-attribution envelopes))
         :intents     (hh/intent-rows      (:intents envelopes))
         :explain     (hh/explain-rows     (:explain-render envelopes))})))

  (panel-registry/reg-l4-tab!
    {:id    :hicasso
     :label "Hicasso"
     :mnem  "h"
     :modes #{:dynamic}
     :order 10
     :panel Panel})

  nil)
