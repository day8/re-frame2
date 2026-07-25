(ns re-frame.freehand.spike.er01.cliff
  "SPIKE SCAFFOLDING — ER-01 authoring friction. Deleted before this
  bead's PR.

  D010's value-vs-syntax cliff, written three ways. The table's cell is
  extracted into an ORDINARY `defn` helper — the deepest Clojure idiom
  and, per D010, \"compilation's deepest impossibility.\" Each arm
  answers the same question: does this source compile, and does it
  render the same tree?"
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.spike.er01.dollar :refer [$]]))

;; ---------------------------------------------------------------------------
;; The helper, once per front end
;; ---------------------------------------------------------------------------

(defn hiccup-cell
  "A plain Clojure helper returning markup as a VALUE."
  [r c]
  [:div.vtcell {:key c} (str (:id r) ":" c)])

(defn dollar-cell
  "The same helper, returning a built NODE."
  [r c]
  ($ :div.vtcell {:key c} (str (:id r) ":" c)))

;; ---------------------------------------------------------------------------
;; Arm I — interpreted: the helper is ordinary and works
;; ---------------------------------------------------------------------------

(v/defview interpreted-table
  [{:keys [rows cols]}]
  [:div.vtable
   [:div.vthead
    (for [c (range cols)]
      [:div.vth {:key c} (str "c" c)])]
   [:div.vtbody
    (for [r rows]
      [:div.vtrow {:key (:id r) :data-index (:index r)}
       (for [c (range cols)]
         (hiccup-cell r c))])]])

;; ---------------------------------------------------------------------------
;; Arm $ — the helper is ordinary and works
;; ---------------------------------------------------------------------------

(v/defview dollar-table
  [{:keys [rows cols]}]
  ($ :div.vtable
     ($ :div.vthead
        (for [c (range cols)]
          ($ :div.vth {:key c} (str "c" c))))
     ($ :div.vtbody
        (for [r rows]
          ($ :div.vtrow {:key (:id r) :data-index (:index r)}
             (for [c (range cols)]
               (dollar-cell r c)))))))

;; ---------------------------------------------------------------------------
;; Arm C — the compiled twin is a build ERROR, captured rather than thrown
;; ---------------------------------------------------------------------------

(def compiled-refusal
  "What `{:compiled true}` answers when the cell is a helper call.

  Held as data rather than as a commented-out declaration so the spike
  reports the analyzer's ACTUAL diagnostic id and recovery ladder instead
  of a paraphrase."
  (try
    (eval
      '(re-frame.freehand/defview compiled-table
         {:compiled true}
         [{:keys [rows cols]}]
         [:div.vtable
          [:div.vthead
           (for [c (range cols)]
             [:div.vth {:key c} (str "c" c)])]
          [:div.vtbody
           (for [r rows]
             [:div.vtrow {:key (:id r) :data-index (:index r)}
              (for [c (range cols)]
                (re-frame.freehand.spike.er01.cliff/hiccup-cell r c))])]]))
    {:compiled? true}
    (catch Throwable t
      (let [t (or (ex-cause t) t)]
        {:compiled? false
         :message   (ex-message t)
         :data      (dissoc (ex-data t) :form :env :ast)
         :data-keys (vec (sort (keys (ex-data t))))}))))
