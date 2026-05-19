(ns day8.re-frame2-causa.panels.app-db-diff-sections
  "Reserved, pinned, and focus-result sections for App-DB Diff."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.panels.app-db-diff-slices :as slices]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(def empty-state slices/empty-state)
(def changed-slices-stack slices/changed-slices-stack)

;; ---- rf2-bz1cl — redacted-paths-modified hint chip ----------------------
;;
;; A muted-grey `·`-marker chip at the top of the diff body. Surfaces
;; "N redacted paths were modified at this cascade" when count > 0.
;; The marker matches the family established by rf2-87lkf's Views polish
;; (just merged in #1498) — `·` for muted / informational signal, `✱`
;; for amber / attention-cue.
;;
;; Why a chip and not a diff row: the elision contract (per
;; spec/015-Data-Classification.md + spec/Security.md §Epoch privacy
;; posture) deliberately suppresses the underlying value at redacted
;; paths. Synthesising a fake diff row that says "(redacted) → (redacted)"
;; would either be useless (no signal) or misleading (suggesting the
;; renderer broke the contract). The chip is a SEPARATE signal that
;; complements the diff body without overriding it.
;;
;; Tooltip: the chip explains the elision contract so the developer
;; understands both *what* the count means and *why* the values aren't
;; in the diff body.

(defn redacted-modified-chip
  "Render a muted-grey informational chip above the diff body when
  `count` > 0. Returns nil when count is 0/nil (no chip, no DOM).
  Per rf2-bz1cl."
  [count]
  (when (and (number? count) (pos? count))
    [:div {:data-testid "rf-causa-app-db-diff-redacted-modified-chip"
           :title       (str count
                             (if (= 1 count) " redacted path" " redacted paths")
                             " in modified subtrees. The elision contract"
                             " suppresses values where both sides are"
                             " :rf/redacted, so no diff row is shown — but"
                             " the enclosing subtree provably changed."
                             " See spec/015-Data-Classification.md for the"
                             " redaction contract.")
           :style       {:display       "inline-flex"
                         :align-items   "center"
                         :gap           "6px"
                         :margin        "8px 12px 0 12px"
                         :padding       "3px 10px"
                         :background    (:bg-3 tokens)
                         :border        (str "1px solid " (:border-subtle tokens))
                         :border-radius "10px"
                         :color         (:text-tertiary tokens)
                         :font-family   sans-stack
                         :font-size     "11px"
                         :line-height   "16px"
                         :cursor        "help"
                         :user-select   "none"}}
     [:span {:style {:color (:text-tertiary tokens)
                     :font-weight 600}} "·"]
     [:span (str count
                 (if (= 1 count)
                   " redacted path modified"
                   " redacted paths modified"))]]))

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

;; ---- rf2-e9tb0 — pinned-slices dropped --------------------------------
;;
;; The pinned-watches strip and its `pinned-row` / `pinned-group`
;; renderers were removed when path-segment click-to-inspect landed
;; (Mike 2026-05-19 Q13). The App-DB Diff body no longer carries a
;; pinned-slices section; the matching subs / events / helpers were
;; pulled from `app_db_diff_subs.cljs`, `app_db_diff_events.cljs`, and
;; `app_db_diff_helpers.cljc` in the same commit. The user inspects
;; arbitrary app-db paths on demand via the segment-inspector popup
;; that opens when any path-segment in a diff breadcrumb is clicked.

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
