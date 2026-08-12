(ns day8.re-frame2-xray.panels.hicasso
  "Hicasso tab — the glass on the live tier (rf2-hic-023, rf2-hic-037).

  Six views over ONE evidence surface. The first four answer the questions
  Spec SN §10 says a developer actually asks of a view substrate:

    - **Mounted** — which boundaries are mounted, over which frames?
    - **Reads** — which boundaries read each subscription, and at what
      fan-out?
    - **Intents** — what was dispatched, in order, inside the retained
      window?
    - **Why** — which reads changed, and what does that prove?

  The last two are derivations over the same one-turn read (rf2-hic-037):

    - **Advisor** — ranks the census by time, frequency, read churn and
      fan-out; classifies what owns the pressure; and recommends the
      smallest route that addresses THAT owner — which, from this
      evidence, is never a native one. See
      `hicasso_advisor.cljc` for why the refusal is the product.
    - **Causal** — §10's chain, `event → subscriptions recomputed →
      values changed → boundaries notified → bodies run → React commit →
      paint`, walked link by link for one real dispatch, with each link's
      basis AND its join to the previous link printed separately.

  ## The tab's job is to make the LOSS legible, not to hide it

  Hicasso's evidence door refuses to encode unknown as an empty
  collection, and this panel's whole design follows from taking that
  seriously one layer further out. Three things fall out of it:

  1. **Every empty is its own sentence.** *Not running Hicasso*, *running
     a schema this build cannot parse* and *running with an empty roster*
     are unrelated facts with unrelated remedies, and each renders under
     its own testid with its own prose. A tab that showed one blank table
     for all three would verify the absence of bad news. The last of them
     splits again PER VIEW: an empty mounted census is a survey result, an
     empty intent stream is a capped window, and one sentence covering
     both would be false of one of them (audit #7789).
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
            [day8.re-frame2-xray.panels.hicasso-advisor :as advisor]
            [day8.re-frame2-xray.panels.hicasso-causal :as causal]
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
  "The honest empty. Each state gets its own testid and its own sentence —
  see the ns docstring for why collapsing them would undo the schema.

  `view` is passed because an EMPTY roster is a different fact in each of
  the four, so the sentence and the testid come from the view's own entry
  in `hh/empty-copy` rather than from one shared line that could only ever
  be true of one view (audit #7789)."
  [state view]
  (when-some [{:keys [testid-suffix says]} (hh/state-copy state view)]
    [:div {:data-testid (str "rf-xray-" panel-id "-" testid-suffix)
           :style       (if (= :mismatch state) mismatch-style state-style)}
     says]))

;; ---- view 1 — mounted boundaries -----------------------------------------

(defn- visibility-note
  "Mounted means SUBSCRIBED, and this view says so rather than letting a
  reader supply the other word.

  The real-React lifecycle witness (audit #7792) established that this
  census cannot distinguish three states React owns: an Activity-hidden
  subtree that released its reads leaves the same census as an unmounted
  one, and a Suspense-fallback-hidden subtree stays subscribed and so
  stays in this table while absent from the screen. The producer states
  both as `:host-opaque`; the panel's job is to make sure the reader is
  told before they infer otherwise, which is why it renders with the rows
  and not only when the roster is empty."
  [envelope]
  (when (hh/supported? envelope)
    (let [chip (hh/loss-chip (:loss (:host envelope)) (:visibility (:host envelope)))]
      [:div {:data-testid (str "rf-xray-" panel-id "-mounted-visibility")
             :style       (assoc detail-style :margin-top "6px")}
       (str (:short chip) " — these rows are about SUBSCRIPTION, not about the "
            "screen. A hidden-but-retained subtree that released its reads is "
            "indistinguishable here from an unmounted one, and a "
            "Suspense-fallback-hidden subtree stays subscribed and stays "
            "listed. React DevTools is the authority on what is visible.")])))

(defn- mounted-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-mounted")}
     (or (presence-note (hh/presence envelope (empty? rows)) :mounted)
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
                  [(overflow/overflow-row {:panel-id panel-id :over-cap? over? :hidden-count hidden})]))])
     ;; The qualifications are stated whether or not there are rows. A view
     ;; that showed them only when it had something to qualify would drop
     ;; them exactly where the reader has least else to go on — which is the
     ;; empty roster the audit found reading as a clean bill of health.
     (when (hh/supported? envelope)
       [:<>
        (absence-note (str "rf-xray-" panel-id "-mounted-naming")
                      (hh/loss-chip (:loss (:naming envelope)) hh/unknown))
        (absence-note (str "rf-xray-" panel-id "-mounted-host")
                      (hh/loss-chip (:loss (:host envelope)) hh/unknown))
        (visibility-note envelope)])]))

;; ---- view 2 — read attribution -------------------------------------------

(defn- attribution-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-attribution")}
     (or (presence-note (hh/presence envelope (empty? rows)) :attribution)
         (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
               (concat
                 (for [row shown]
                   ^{:key (:slug row)}
                   [:li {:data-testid (str "rf-xray-" panel-id "-edge-" (:slug row))
                         :style       row-style}
                    ;; The PROJECTED QUERY, not the bare sub-id. `[:row 1]`
                    ;; and `[:row 2]` are one registration and two cells, and
                    ;; a view naming only the registration printed them as two
                    ;; identical lines (audit #7802).
                    [:div [:span {:style {:font-family mono-stack}} (:label row)]
                     (loss-chip (str "rf-xray-" panel-id "-edge-" (:slug row) "-frame")
                                (:frame-chip row))
                     [:span {:style (assoc detail-style :display "inline" :margin-left "8px")}
                      (string/join " · "
                                   (remove nil?
                                           [(when-not (hh/unknown? (:frame-id row))
                                              (str "frame " (hh/format-id (:frame-id row))))
                                            (str "fan-out " (:fan-out row))]))]]
                    [:div {:data-testid (str "rf-xray-" panel-id "-edge-" (:slug row) "-readers")
                           :style       detail-style}
                     (str "read by " (string/join ", " (map :label (:readers row))))]])
                 [(overflow/overflow-row {:panel-id (str panel-id "-attribution") :over-cap? over? :hidden-count hidden})])))]))

;; ---- view 3 — the intent stream ------------------------------------------

(defn- intents-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-intents")}
     (or (presence-note (hh/presence envelope (empty? rows)) :intents)
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
                    [:div {:style detail-style}
                     (string/join " · "
                                  (remove nil?
                                          [(str "dispatch " (hh/format-id (:dispatch-id row)))
                                           (when (seq (:frames row))
                                             (str "frame" (when (not= 1 (count (:frames row))) "s")
                                                  " " (string/join ", " (map hh/format-id (:frames row)))))
                                           (when (seq (:sub-ids row))
                                             (str "recomputed "
                                                  (string/join ", " (map hh/format-id (:sub-ids row)))))]))]])
                 [(overflow/overflow-row {:panel-id (str panel-id "-intents") :over-cap? over? :hidden-count hidden})])))
     ;; The window is ALWAYS a cap, so the cap and the opaque origin are
     ;; stated even when the stream is empty — an empty window with its
     ;; loss hidden is the shape that reads as "nothing was dispatched".
     (when (hh/supported? envelope)
       (absence-note (str "rf-xray-" panel-id "-intents-origin")
                     (hh/loss-chip (:loss (:origin envelope)) hh/unknown)))]))

;; ---- view 4 — explain render ---------------------------------------------

(defn- explain-view
  [{:keys [envelope rows]}]
  (let [[shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-explain")}
     (or (presence-note (hh/presence envelope (empty? rows)) :explain)
         (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
               (concat
                 (for [row shown]
                   ^{:key (:slug row)}
                   [:li {:data-testid (str "rf-xray-" panel-id "-explain-" (:slug row))
                         :style       row-style}
                    ;; The frame rides beside the label. Frames are isolated
                    ;; contexts, so one query read in two of them is two facts
                    ;; — and their labels are identical (audit #7802).
                    [:div [:span {:style {:font-family mono-stack}} (:label row)]
                     (loss-chip (str "rf-xray-" panel-id "-explain-" (:slug row) "-frame")
                                (:frame-chip row))
                     (when-not (hh/unknown? (:frame row))
                       [:span {:style (assoc detail-style :display "inline" :margin-left "8px")}
                        (str "frame " (hh/format-id (:frame row)))])]
                    ;; THE PROVEN HALF — off the cells' own epoch stamps.
                    [:div {:data-testid (str "rf-xray-" panel-id "-explain-" (:slug row) "-proven")
                           :style       detail-style}
                     (if (:proven? row)
                       (str "moved most recently: "
                            ;; Already rendered per READ by the helpers, so two
                            ;; parameterizations of one sub stay two entries.
                            (string/join ", " (:latest-reads row))
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

;; ---- view 5 — the advisor -------------------------------------------------

(def ^:private route-style
  {:margin-top "6px" :padding "6px 8px" :border-radius "4px"
   :background (:bg-1 tokens)
   :border (str "1px solid " (:border-subtle tokens))
   :font-family sans-stack :font-size "12px" :line-height "1.45"
   :color (:text-secondary tokens)})

(def ^:private refusal-style
  (assoc route-style
         :background (with-alpha :warning 10)
         :border (str "1px solid " (with-alpha :warning 35))))

(defn- axis-line
  "The four axes on one line, each in its own unit.

  Never a composite score: the units are milliseconds, dispatch counts,
  read orders and reader slots, and any single number over them would be
  the weights talking rather than the evidence."
  [{:keys [time frequency read-churn fan-out]}]
  (string/join " · "
               [(str "time " (advisor/format-ms (:ms time)))
                (str "freq " (:runs frequency) " recompute"
                     (when (not= 1 (:runs frequency)) "s")
                     " / " (:memo-hits frequency) " memo hit"
                     (when (not= 1 (:memo-hits frequency)) "s"))
                (str "churn " (:reads read-churn) " read"
                     (when (not= 1 (:reads read-churn)) "s")
                     ", " (:read-orders read-churn) " order"
                     (when (not= 1 (:read-orders read-churn)) "s"))
                (str "fan-out " (:total fan-out))]))

(defn- advice-row
  [row]
  (let [{:keys [class advice]} row
        stem (str "rf-xray-" panel-id "-advice-" (:slug row))]
    [:li {:data-testid stem :style row-style}
     [:div
      [:span {:style {:font-family mono-stack}} (str "#" (:rank row) "  " (:label row))]
      (loss-chip (str stem "-frame") (hh/loss-chip nil (:frame row)))]
     [:div {:style detail-style} (axis-line (:axes row))]
     ;; WHAT OWNS IT — with the loss for the half that was not measured.
     [:div {:data-testid (str stem "-class") :style detail-style}
      [:span {:style {:color (:text-secondary tokens)}}
       (str "owner: " (hh/format-id (:owner class))
            " (" (hh/format-id (:basis class)) ")  ")]
      ;; One-arity on purpose. Passing `hh/unknown` as the value would mint
      ;; an `unknown` chip for a classification that IS known — a measured
      ;; `:computation` owner carries no loss, and a chip beside it saying
      ;; the field is not held would contradict the sentence next to it.
      (loss-chip (str stem "-class") (hh/loss-chip (:loss class)))
      [:div {:style {:margin-top "3px"}} (:says class)]]
     ;; THE ROUTE — or the refusal, which is the same field and never a
     ;; blank. A boundary the advisor will not route is the case a reader
     ;; most needs a sentence for.
     [:div {:data-testid (str stem "-route")
            :style       (if (:refusal advice) refusal-style route-style)}
      [:div [:strong (:label advice)]
       (when (:rung advice) (str "  · ladder rung " (:rung advice)))]
      [:div {:style {:margin-top "3px"}} (:says advice)]
      (when-some [r (:refusal advice)]
        [:div {:data-testid (str stem "-refusal") :style {:margin-top "5px"}}
         [:div (:says r)]
         (into [:ul {:style {:margin "4px 0 0 16px" :padding 0}}]
               (for [n (:next r)]
                 ^{:key (str (:class n))}
                 [:li {:data-testid (str stem "-refusal-" (name (:class n)))}
                  (str (:label n) " — " (:authority n))]))])
      (into [:ol {:data-testid (str stem "-loop")
                  :style {:margin "6px 0 0 16px" :padding 0
                          :color (:text-tertiary tokens)}}]
            (for [[i s] (map-indexed vector (:working-loop advice))]
              ^{:key i} [:li s]))]]))

(defn- advisor-view
  [{:keys [advice envelope]}]
  (let [rows (:rows advice)
        [shown over? hidden] (ch/cap-rows rows)]
    [:div {:data-testid (str "rf-xray-" panel-id "-advisor")}
     (or (presence-note (hh/presence envelope (empty? rows)) :advisor)
         (into [:ol {:style {:list-style "none" :margin 0 :padding 0}}]
               (concat (for [row shown] ^{:key (:slug row)} [advice-row row])
                       [(overflow/overflow-row {:panel-id     (str panel-id "-advisor")
                                                :over-cap?    over?
                                                :hidden-count hidden})])))
     ;; Stated on EVERY render, rows or none. The three classes this tab
     ;; does not measure are the reason its top row is not a verdict, and a
     ;; reader who saw that only when the roster was empty would read a
     ;; populated roster as complete.
     [:div {:data-testid (str "rf-xray-" panel-id "-advisor-unmeasured")
            :style       (assoc state-style :margin-top "10px")}
      [:div "Three of the five pressure classes are not measured here:"]
      (into [:ul {:style {:margin "4px 0 0 16px" :padding 0}}]
            (for [c (:unmeasured advice)]
              ^{:key (str (:class c))}
              [:li {:data-testid (str "rf-xray-" panel-id "-advisor-unmeasured-"
                                      (name (:class c)))}
               [:strong (:label c)] " — " (:authority c) ". " (:why c)]))]]))

;; ---- view 6 — the causal slice --------------------------------------------

(defn- causal-link
  [link]
  (let [stem (str "rf-xray-" panel-id "-causal-link-" (name (:id link)))]
    [:li {:data-testid stem :style row-style}
     [:div
      [:span {:style {:font-family mono-stack}}
       (str (:ordinal link) ". " (:label link))]
      (loss-chip stem (hh/loss-chip (:loss link)
                                    (if (:evidenced? link) ::present hh/unknown)))
      [:span {:data-testid (str stem "-basis")
              :style (assoc detail-style :display "inline" :margin-left "8px")}
       (str (if (:evidenced? link) "evidenced" "not evidenced")
            " · basis " (hh/format-id (:basis link)))]]
     (when (:seam link)
       [:div {:style detail-style} (str "seam: " (:seam link))])
     [:div {:style detail-style} (:says link)]
     ;; THE JOIN, on its own row. A link's own basis and its join to the
     ;; previous link are different questions, and printing only the first
     ;; is how four solid facts in a row come to read as a proven cause.
     (when-some [j (:joins link)]
       [:div {:data-testid (str stem "-join")
              :style       (assoc detail-style :margin-top "4px")}
        [:span (str "join to the link above: " (hh/format-id (:status j))
                    (when (:on j) (str " on " (hh/format-id (:on j)))) " — ")]
        (:says j)])]))

(defn- causal-view
  [{:keys [slice]}]
  [:div {:data-testid (str "rf-xray-" panel-id "-causal")}
   (if (nil? slice)
     (presence-note :idle :causal)
     (into [:ol {:style {:list-style "none" :margin 0 :padding 0}}]
           (for [link (:links slice)]
             ^{:key (name (:id link))} [causal-link link])))])

;; ---- the sub-strip and the panel -----------------------------------------

;; `dispatch` (rf2-1w07r) is the reg-view-injected frame-bound dispatcher
;; threaded down from the `Panel` body, so the deferred `:on-click` lands on
;; the surrounding Xray instance's frame. A bare global `rf/dispatch` would
;; fire after render unwinds, when the ambient frame is gone, and leak to
;; `:rf/default` — the click would silently switch some other shell's
;; sub-view, or none. This is the tab's only dispatch: the four views read.
(defn- sub-strip
  [dispatch selected]
  (into [:div {:data-testid (str "rf-xray-" panel-id "-sub-strip")
               :role        "tablist"
               :style       strip-style}]
        (for [{:keys [id label asks]} hh/sub-modes]
          ^{:key id}
          [:button {:data-testid   (str "rf-xray-" panel-id "-sub-" (name id))
                    :role          "tab"
                    :aria-selected (= id selected)
                    :title         asks
                    :on-click      #(dispatch [:rf.xray.hicasso/set-view id])
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
                                  :explain     :explain-render
                                  ;; The advisor ranks the mounted census and
                                  ;; the slice starts from the boundary it
                                  ;; named, so both inherit that envelope's
                                  ;; presence — absent, mismatched or idle
                                  ;; means the same three things here.
                                  :advisor     :mounted-boundaries
                                  :causal      :mounted-boundaries))]
    [:section {:data-testid (str "rf-xray-" panel-id) :style panel-root-style}
     (sub-strip dispatch selected)
     [:div {:data-testid (str "rf-xray-" panel-id "-summary") :style summary-style}
      ;; The derived views state their OWN claim, not the census's. An
      ;; advice envelope and a slice envelope have their own scope, basis
      ;; and loss, and printing the producer's summary over them would
      ;; credit Hicasso with a completeness claim about a derivation Xray
      ;; made.
      (or (case selected
            :advisor (advisor/advice-summary (:advice data))
            :causal  (causal/slice-summary (:slice data))
            (hh/read-summary envelope))
          "no envelope")]
     [:div {:style scroll-style}
      (section/section-row
        {:label  (string/upper-case (:label (first (filter #(= selected (:id %))
                                                           hh/sub-modes))))
         :testid (str "rf-xray-" panel-id "-section")}
        (case selected
          :mounted     [mounted-view     {:envelope envelope :rows (:mounted data)}]
          :attribution [attribution-view {:envelope envelope :rows (:attribution data)}]
          :intents     [intents-view     {:envelope envelope :rows (:intents data)}]
          :explain     [explain-view     {:envelope envelope :rows (:explain data)}]
          :advisor     [advisor-view     {:envelope envelope :advice (:advice data)}]
          :causal      [causal-view      {:slice (:slice data)}]))]]))

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
      (let [envelopes (reads/evidence)
            ;; The window is taken in the SAME turn as the four envelopes,
            ;; for the reason the four are taken together: the advisor
            ;; joins the ring's recompute counts to the census's read
            ;; edges, and a mount landing between the two reads would
            ;; price a boundary against a window that never described it.
            windows   (or (reads/trace-windows envelopes) {})
            advice    (advisor/advise envelopes (advisor/sub-timing windows))]
        {:envelopes   envelopes
         :mounted     (hh/mounted-rows     (:mounted-boundaries envelopes))
         :attribution (hh/attribution-rows (:read-attribution envelopes))
         :intents     (hh/intent-rows      (:intents envelopes))
         :explain     (hh/explain-rows     (:explain-render envelopes))
         :advice      advice
         ;; The slice is drawn for the boundary the advisor ranked FIRST
         ;; and the newest dispatch the ring still holds — so the two views
         ;; are one workflow rather than two lookups, and the causal chain
         ;; is about the boundary the roster just pointed at.
         :slice       (when-some [top (first (:rows advice))]
                        (causal/slice {:envelopes    envelopes
                                       :windows      windows
                                       :boundary-key (:key (:boundary top))}))})))

  (panel-registry/reg-l4-tab!
    {:id    :hicasso
     :label "Hicasso"
     :mnem  "h"
     :modes #{:dynamic}
     :order 10
     :panel Panel})

  nil)
