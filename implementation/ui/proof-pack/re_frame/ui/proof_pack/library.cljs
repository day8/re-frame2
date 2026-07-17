(ns re-frame.ui.proof-pack.library
  "The component-library PROOF PACK (readiness §7; EP-0035) — a small
  representative multi-view library donated to the conformance corpus as a
  rolling consumer of the S3 substrate (no re-com build dependency). Each view
  is an independent library-shaped compiled component carrying a distinctive
  production sentinel string in its markup; the G-18 facade-isolation gate
  imports ONE of them and proves the unused siblings (and their sentinels) are
  absent from an advanced build (fixture-first).

  The views are deliberately independent — no shared `views` map, registry, or
  cross-reference retains a sibling — so Closure's advanced DCE can drop an
  unimported view whole."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview]]))

;; 1 — controlled text input: the reusable event-prefix control (P0-2 door).
(defview controlled-input [{:keys [on-change]}]
  [:input {:data-pp "rf2-pp-controlled-input-sentinel"
           :value (ui/sub [:rf.pp/text])
           :on-input (ui/event [e] (conj on-change (.. e -target -value)))}])

;; 2 — selection controller: multi-writer atomic transitions (update!). Two
;; same-turn updaters compose over the LATEST host state, then a dispatch —
;; the multi-writer contract a library cannot build soundly itself.
(defview selection-controller [{:keys [on-select]}]
  (let [[sel _ update-sel] (ui/local #{})]
    [:div {:data-pp "rf2-pp-selection-controller-sentinel"}
     [:span {:data-role "count"} (str (count sel))]
     [:button {:on-click (ui/handler [_]
                           (update-sel conj :a)
                           (update-sel conj :b)
                           (on-select sel))}
      "select"]]))

;; 3 — slotted list cell: parameterized markup through ui/slot + render-fn.
(defview list-cell [{:keys [rows render]}]
  [:ul {:data-pp "rf2-pp-list-cell-sentinel"}
   (for [[i r] (map-indexed vector rows)]
     [:li {:key (:id r)} (ui/slot render i r)])])

;; 4 — safe-attrs form control: pass-through attrs via the spread-safe policy.
(defview safe-form-control [{:keys [attrs on-change]}]
  [:input (ui/spread-safe {:data-pp "rf2-pp-safe-form-control-sentinel"
                           :value (ui/sub [:rf.pp/text])
                           :on-input (ui/event [e] (conj on-change (.. e -target -value)))}
                          attrs)])

;; 5 — schema-described component: literal props schema drives the manifest.
(defview schema-described
  {:props [:map {:closed true}
           [:label {:doc "the field label" :default "rf2-pp-schema-described-sentinel"} :string]]}
  [{:keys [label]}]
  [:label {:data-pp "rf2-pp-schema-described-sentinel"} (str label)])

;; 6 — inline popover: a measure-before-paint geometry shell (interop tier).
(defview inline-popover [{:keys [content]}]
  (ui/effect :connect
    ;; C-6 measure-before-paint lives here; pure structural shell for the tree.
    (fn [] nil))
  [:div {:data-pp "rf2-pp-inline-popover-sentinel"
         :role "dialog"}
   content])

(defn register-pp! []
  (rf/reg-sub :rf.pp/text (fn [db _] (:text db)))
  (rf/reg-sub :rf.pp/options (fn [db _] (:options db))))
