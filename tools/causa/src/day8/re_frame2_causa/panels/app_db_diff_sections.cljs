(ns day8.re-frame2-causa.panels.app-db-diff-sections
  "Reserved, pinned, and focus-result sections for App-DB Diff."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.panels.app-db-diff-slices :as slices]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(def empty-state slices/empty-state)
(def changed-slices-stack slices/changed-slices-stack)

(defn- reserved-row
  [[k v]]
  [:div {:data-testid (str "rf-causa-app-db-diff-reserved-" (pr-str k))
         :style       {:display "flex"
                       :justify-content "space-between"
                       :gap "12px"
                       :padding "4px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family mono-stack
                       :font-size "12px"}}
   [:span {:style {:color (:text-secondary tokens)}} (f/format-edn k)]
   [:span {:style {:color (:text-tertiary tokens)}}
    (f/truncate (f/format-edn v) 48)]])

(defn reserved-group
  [reserved-pairs]
  (when (seq reserved-pairs)
    [:section {:data-testid "rf-causa-app-db-diff-reserved-group"
               :style       {:margin "12px 12px"
                             :background (:bg-3 tokens)
                             :border (str "1px solid " (:border-subtle tokens))
                             :border-radius "4px"}}
     [:header {:style {:padding "6px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family sans-stack
                       :font-size "11px"
                       :font-weight 600
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :color (:text-secondary tokens)}}
      "[runtime] — reserved app-db keys"]
     (into [:div]
           ;; `^{:key …}` reader meta on the `(reserved-row pair)` call
           ;; below would be attached to the source list and lost when
           ;; the call returns its fresh vector — Reagent's
           ;; `get-react-key` only reads `:key` meta from vectors (see
           ;; reagent2.impl.template). `reserved-row` always returns a
           ;; `[:div …]` vector, so apply the key directly via
           ;; `with-meta`. (rf2-ppzid)
           (for [pair reserved-pairs]
             (with-meta (reserved-row pair) {:key (pr-str (first pair))})))]))

(defn- pinned-row
  [{:keys [path value]}]
  [:div {:data-testid (str "rf-causa-app-db-diff-pinned-" (pr-str path))
         :style       {:display "flex"
                       :justify-content "space-between"
                       :gap "12px"
                       :padding "4px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family mono-stack
                       :font-size "12px"}}
   [:span {:style {:color (:text-primary tokens)}}
    (f/truncate (f/format-edn path) 36)]
   [:span {:style {:display "flex" :gap "8px" :align-items "center"}}
    [:span {:style {:color (:text-tertiary tokens)}}
     (f/truncate (f/format-edn value) 36)]
    [:button {:data-testid (str "rf-causa-app-db-diff-unpin-" (pr-str path))
              :on-click    #(rf/dispatch [:rf.causa/unpin-slice path]
                                         {:frame :rf/causa})
              :style       {:background "transparent"
                            :border     "none"
                            :color      (:text-tertiary tokens)
                            :cursor     "pointer"
                            :font-family mono-stack
                            :font-size  "11px"}
              :title       "Unpin"}
     "✕"]]])

(defn pinned-group
  [pinned-slices]
  (when (seq pinned-slices)
    [:section {:data-testid "rf-causa-app-db-diff-pinned-group"
               :style       {:margin "12px 12px"
                             :background (:bg-3 tokens)
                             :border (str "1px solid " (:border-subtle tokens))
                             :border-radius "4px"}}
     [:header {:style {:padding "6px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family sans-stack
                       :font-size "11px"
                       :font-weight 600
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :color (:text-secondary tokens)}}
      "Pinned slices"]
     (into [:div]
           ;; `^{:key …}` reader meta on the `(pinned-row p)` call
           ;; below would be attached to the source list and lost when
           ;; the call returns its fresh vector — Reagent's
           ;; `get-react-key` only reads `:key` meta from vectors (see
           ;; reagent2.impl.template). `pinned-row` always returns a
           ;; `[:div …]` vector, so apply the key directly via
           ;; `with-meta`. (rf2-ppzid)
           (for [p pinned-slices]
             (with-meta (pinned-row p) {:key (pr-str (:path p))})))]))

(defn- focus-result-row
  [{:keys [epoch-id event op before after] :as _hit}]
  [:li {:data-testid (str "rf-causa-app-db-diff-focus-hit-"
                          (pr-str epoch-id))
        :on-click    #(rf/dispatch [:rf.causa/select-epoch epoch-id]
                                   {:frame :rf/causa})
        :style       {:display "flex"
                      :align-items "center"
                      :gap "12px"
                      :padding "6px 12px"
                      :cursor "pointer"
                      :border-bottom (str "1px solid " (:border-subtle tokens))
                      :font-family mono-stack
                      :font-size "12px"
                      :color (:text-primary tokens)}}
   [:span {:style {:color (get tokens (f/op->border op))
                   :flex "0 0 80px"}}
    (f/op->label op)]
   [:span {:style {:color (:accent-violet tokens) :flex "0 0 120px"}}
    (f/truncate (f/format-edn (or event :ungrouped)) 16)]
   [:span {:style {:color (:text-tertiary tokens)
                   :flex 1
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    (case op
      :added    (str "added " (f/truncate (f/format-edn after) 32))
      :removed  (str "removed " (f/truncate (f/format-edn before) 32))
      :modified (str (f/truncate (f/format-edn before) 16)
                     " → "
                     (f/truncate (f/format-edn after) 16)))]])

(defn focus-result-panel
  [focused-path hits]
  [:section {:data-testid "rf-causa-app-db-diff-focus-result"
             :style       {:margin "8px 12px"
                           :background (:bg-3 tokens)
                           :border (str "1px solid " (:border-subtle tokens))
                           :border-radius "4px"}}
   [:header {:style {:padding "8px 12px"
                     :display "flex"
                     :justify-content "space-between"
                     :align-items "center"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :font-family sans-stack
                     :font-size "12px"}}
    [:span {:style {:color (:text-secondary tokens)}}
     "Epochs that touched "
     [:code {:style {:color (:accent-violet tokens)
                     :font-family mono-stack}}
      (f/format-edn focused-path)]]
    [:button {:data-testid "rf-causa-app-db-diff-clear-focus"
              :on-click    #(rf/dispatch [:rf.causa/clear-slice-focus]
                                         {:frame :rf/causa})
              :style       {:background "transparent"
                            :border (str "1px solid " (:border-default tokens))
                            :color (:text-secondary tokens)
                            :padding "2px 8px"
                            :border-radius "4px"
                            :cursor "pointer"
                            :font-family sans-stack
                            :font-size "11px"}}
     "Close"]]
   (if (empty? hits)
     [:p {:style {:padding "12px"
                  :color (:text-tertiary tokens)
                  :font-family sans-stack
                  :font-size "12px"
                  :margin 0}}
      "No epochs touched this path."]
     (into [:ul {:data-testid "rf-causa-app-db-diff-focus-hits"
                 :style {:list-style "none"
                         :margin 0
                         :padding 0}}]
           ;; `^{:key …}` reader meta on the `(focus-result-row hit)`
           ;; call below would be attached to the source list and lost
           ;; when the call returns its fresh vector — Reagent's
           ;; `get-react-key` only reads `:key` meta from vectors (see
           ;; reagent2.impl.template). `focus-result-row` always
           ;; returns a `[:li …]` vector, so apply the key directly
           ;; via `with-meta`. (rf2-ppzid)
           (for [hit hits]
             (with-meta (focus-result-row hit) {:key (pr-str (:epoch-id hit))}))))])
