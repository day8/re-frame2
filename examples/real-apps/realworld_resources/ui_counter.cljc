(ns realworld-resources.ui-counter
  "The counter — the smallest complete re-frame.ui app — as an S3 coherence
   fixture. It exists to prove that the plainest thing feels plain: three one-line
   events, one sub, one `defview`, and DOM handlers that actually fire.

   Everything here is the ordinary re-frame2 dataflow (plain `reg-event` /
   `reg-sub`, `.cljc` so it renders on the JVM structural host too) plus one
   compiled view. The view is a pure function of `(sub [:ui-counter/count])`; its
   buttons carry literal EVENT VECTORS (`:on-click [:ui-counter/inc 1]`) — no
   `dispatch` call, no closure — and its number field is a controlled input whose
   `:on-input` vector carries the `:rf.ui/value` placeholder, so a keystroke
   projects the live value into `[:ui-counter/set …]` and drains synchronously.

   There is deliberately no `local` here: the count IS product state, so it lives
   in app-db behind events. `local` is for view-only keystroke ephemera — see
   `ui_dashboard.cljc` for the justified use."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub]]))

;; ---- dataflow: plain re-frame2 --------------------------------------------

(rf/reg-event :ui-counter/init
  (fn [_ _] {:db {:ui-counter/count 0}}))

(rf/reg-event :ui-counter/inc
  {:schema [:cat [:= :ui-counter/inc] :int]}
  (fn [{:keys [db]} [_ step]]
    {:db (update db :ui-counter/count + step)}))

(rf/reg-event :ui-counter/dec
  {:schema [:cat [:= :ui-counter/dec] :int]}
  (fn [{:keys [db]} [_ step]]
    {:db (update db :ui-counter/count - step)}))

(rf/reg-event :ui-counter/reset
  (fn [{:keys [db]} _] {:db (assoc db :ui-counter/count 0)}))

(rf/reg-event :ui-counter/set
  {:doc "Set the count from the controlled input's live string value."}
  (fn [{:keys [db]} [_ s]]
    (let [n #?(:cljs (js/parseInt s 10) :clj (try (Long/parseLong s) (catch Exception _ nil)))]
      {:db (assoc db :ui-counter/count (if (or (nil? n)
                                               #?(:cljs (js/isNaN n) :clj false))
                                         0 n))})))

(rf/reg-sub :ui-counter/count (fn [db _] (:ui-counter/count db 0)))

;; ---- view -----------------------------------------------------------------

(defview counter
  "The counter. `(sub [:ui-counter/count])` is the value, not a ref — nothing to
   deref; when the count changes, this view re-renders."
  []
  (let [n (sub [:ui-counter/count])]
    [:div.counter {:data-testid "counter"}
     [:h1 {:data-testid "counter-value"} "Count: " (str n)]
     [:div.counter-controls
      [:button.btn {:data-testid "counter-dec" :on-click [:ui-counter/dec 1]} "-"]
      [:button.btn {:data-testid "counter-inc" :on-click [:ui-counter/inc 1]} "+"]
      [:button.btn {:data-testid "counter-step" :on-click [:ui-counter/inc 5]} "+5"]
      [:button.btn {:data-testid "counter-reset" :on-click [:ui-counter/reset]} "Reset"]]
     ;; A controlled input: the literal :value co-present with the vector handler
     ;; carrying :rf.ui/value rides the synchronous door.
     [:input.counter-input
      {:type "number" :data-testid "counter-input"
       :value (str n)
       :on-input [:ui-counter/set :rf.ui/value]}]]))
