(ns day8.re-frame2-causa.static.routes.simulate-url
  "Simulate-URL header surface for Causa's Static Routes tab
  (rf2-o5f5f.3).

  ## Purpose

  Promoted from `panels/routing.cljs` per the two-verbs-two-homes
  decision (Mike, 2026-05-19): the URL → route resolver is a Static
  surface verb (browse), not a Runtime cascade lens. Paste a URL,
  see every route that matches plus its 6-rule `:rf.route/rank`
  tuple; the winner is the first row by rank-descending (mirrors
  `match-url`).

  The implementation reuses the existing
  `panels.routing-helpers/simulate-url` pure helper — the 6-rule
  rank cascade lives there, JVM-portable. This ns is the view +
  dispatch layer only.

  ## State slot

  Lives at `:rf.causa.static.routes/sim-url` on Causa's frame
  (`:rf/causa`). Its companion `set` event is
  `:rf.causa.static.routes/set-sim-url`.

  ## Pure hiccup

  Same contract as every other Causa view — pure hiccup, no Reagent
  / UIx / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `static/shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

(defn- rank-cell
  "Render a single rank tuple as compact mono text. The 6-tuple is
  `[static total -splat catch-all? -optional -reg-index]` per
  `parse-pattern`; surface it verbatim — the lens is about exposing
  the cascade, not interpreting it."
  [rank]
  [:span {:style {:font-family mono-stack
                  :font-size   "11px"
                  :color       (:text-secondary tokens)}}
   (pr-str rank)])

(defn- candidate-row
  "One row in the Simulate-URL result table — winner highlighted."
  [{:keys [route-id rank params winner? path]}]
  [:li {:data-testid (str "rf-causa-static-routes-sim-candidate-"
                          (subs (pr-str route-id) 1))
        :data-winner (when winner? "true")
        :style       {:display        "flex"
                      :align-items    "center"
                      :gap            "8px"
                      :padding        "3px 8px"
                      :background     (if winner? (:bg-active tokens) "transparent")
                      :border-left    (if winner?
                                        (str "2px solid " (:green tokens))
                                        "2px solid transparent")
                      :border-radius  "2px"
                      :font-family    mono-stack
                      :font-size      "12px"
                      :white-space    "nowrap"}}
   [:span {:style {:color       (if winner? (:green tokens) (:text-tertiary tokens))
                   :font-weight 600
                   :font-size   "10px"
                   :min-width   "50px"}}
    (if winner? "WINNER" "")]
   [:span {:style {:color     (:accent-violet tokens)
                   :min-width "140px"}}
    path]
   [:span {:style {:color     (:text-tertiary tokens)
                   :font-size "11px"
                   :min-width "160px"}}
    (str route-id)]
   (rank-cell rank)
   (when (seq params)
     [:span {:style {:color       (:text-secondary tokens)
                     :font-size   "11px"
                     :margin-left "8px"}}
      (pr-str params)])])

(defn header
  "The 'Simulate URL: [____] [Resolve]' header surface — paste a URL,
  see every matching route ranked. `sim-url` is the current input
  value; `sim-result` is the projection from
  `routing-helpers/simulate-url` (nil when input is blank)."
  [sim-url sim-result]
  [:div {:data-testid "rf-causa-static-routes-sim"
         :style       {:padding       "10px 16px"
                       :border-top    (str "1px solid " (:border-subtle tokens))
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :background    (:bg-1 tokens)}}
   [:div {:style {:display     "flex"
                  :align-items "center"
                  :gap         "8px"
                  :font-family sans-stack}}
    [:label {:style {:color          (:text-tertiary tokens)
                     :font-size      "10px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :min-width      "80px"}}
     "Simulate URL"]
    [:input {:type        "text"
             :data-testid "rf-causa-static-routes-sim-input"
             :placeholder "/cart  or  /checkout/payment?step=2"
             :value       (or sim-url "")
             :on-change   (fn [e]
                            (rf/dispatch [:rf.causa.static.routes/set-sim-url
                                          (-> e .-target .-value)]
                                         {:frame :rf/causa}))
             :style       {:flex          1
                           :background    (:bg-3 tokens)
                           :color         (:text-primary tokens)
                           :border        (str "1px solid " (:border-default tokens))
                           :border-radius "3px"
                           :padding       "4px 8px"
                           :font-family   mono-stack
                           :font-size     "12px"}}]
    (when (and sim-url (not= "" sim-url))
      [:button {:data-testid "rf-causa-static-routes-sim-clear"
                :on-click    (fn [_]
                               (rf/dispatch [:rf.causa.static.routes/set-sim-url ""]
                                            {:frame :rf/causa}))
                :style       {:background    "transparent"
                              :border        (str "1px solid " (:border-default tokens))
                              :border-radius "3px"
                              :color         (:text-tertiary tokens)
                              :padding       "3px 8px"
                              :font-family   sans-stack
                              :font-size     "11px"
                              :cursor        "pointer"}}
       "clear"])]
   (when sim-result
     (let [{:keys [path candidates winner]} sim-result]
       [:div {:data-testid "rf-causa-static-routes-sim-result"
              :style       {:margin-top "8px"}}
        [:div {:style {:font-family mono-stack
                       :font-size   "11px"
                       :color       (:text-tertiary tokens)
                       :padding     "0 8px 4px 8px"}}
         (str "matched against path " (pr-str path) " — "
              (cond
                (empty? candidates) "no route matches (match-url → nil)"
                :else (str (count candidates)
                           (if (= 1 (count candidates)) " candidate" " candidates")
                           "; winner = " winner)))]
        (when (seq candidates)
          [:div {:style {:display                "grid"
                         :grid-template-columns  "50px 140px 160px 1fr"
                         :column-gap             "8px"
                         :padding                "0 8px 2px 8px"
                         :font-family            sans-stack
                         :font-size              "10px"
                         :color                  (:text-tertiary tokens)
                         :text-transform         "uppercase"
                         :letter-spacing         "0.4px"}}
           [:span ""] [:span "path"] [:span "route-id"] [:span "rank · params"]])
        (into [:ul {:style {:list-style     "none"
                            :margin         "0"
                            :padding        "0"
                            :display        "flex"
                            :flex-direction "column"
                            :gap            "1px"}}]
              (for [c candidates]
                ^{:key (str (:route-id c))}
                [candidate-row c]))]))])
