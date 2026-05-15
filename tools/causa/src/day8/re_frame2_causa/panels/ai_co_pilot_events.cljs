(ns day8.re-frame2-causa.panels.ai-co-pilot-events
  "Events and effects for the AI Co-Pilot panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]))

(def provider-order
  "Provider cycle order for the lightweight title-bar picker."
  [:claude :openai :gemini :local :custom])

(defn- next-provider
  [current]
  (let [idx (.indexOf provider-order current)]
    (nth provider-order (mod (inc idx) (count provider-order)))))

(defn install!
  "Install AI Co-Pilot events and effects."
  []
  (rf/reg-event-db :rf.causa/copilot-toggle
    (fn [db _event]
      (-> db
          (update :copilot-open? not)
          (assoc :copilot-first-used? true))))

  (rf/reg-event-db :rf.causa/copilot-mark-first-use
    (fn [db _event]
      (assoc db :copilot-first-used? true)))

  (rf/reg-event-db :rf.causa/copilot-set-input-text
    {:rf.trace/no-emit? true}
    (fn [db [_ text]]
      (assoc db :copilot-input-text (or text ""))))

  (rf/reg-event-db :rf.causa/copilot-set-provider
    (fn [db [_ provider]]
      (assoc db :copilot-provider provider)))

  (rf/reg-event-db :rf.causa/copilot-cycle-provider
    (fn [db _event]
      (assoc db :copilot-provider
             (next-provider (get db :copilot-provider :claude)))))

  (rf/reg-event-db :rf.causa/copilot-set-redaction
    (fn [db [_ settings]]
      (assoc db :copilot-redaction-settings
             (merge h/default-redaction-settings settings))))

  (rf/reg-event-fx :rf.causa/copilot-submit-question
    (fn [{:keys [db]} [_ {:keys [text parsed]}]]
      (let [settings (get db :copilot-redaction-settings
                          h/default-redaction-settings)
            provider (get db :copilot-provider :claude)
            conv     (-> (get db :copilot-conversation [])
                         (h/append-question text)
                         (h/start-answer))]
        {:db (-> db
                 (assoc :copilot-conversation conv)
                 (assoc :copilot-streaming-token-count 0)
                 (assoc :copilot-first-used? true))
         :fx [[:rf.causa.fx/llm-stream
               {:provider           provider
                :text               text
                :parsed             parsed
                :redaction-settings settings}]]})))

  (rf/reg-event-db :rf.causa/copilot-stream-token
    (fn [db [_ token]]
      (-> db
          (update :copilot-conversation h/append-token token)
          (update :copilot-streaming-token-count (fnil inc 0)))))

  (rf/reg-event-db :rf.causa/copilot-stream-end
    (fn [db _event]
      (-> db
          (update :copilot-conversation h/end-answer)
          (assoc :copilot-streaming-token-count 0))))

  (rf/reg-event-db :rf.causa/copilot-clear-conversation
    (fn [db _event]
      (-> db
          (assoc :copilot-conversation (h/empty-conversation))
          (assoc :copilot-streaming-token-count 0))))

  (rf/reg-event-fx :rf.causa/copilot-chip-clicked
    (fn [_cofx [_ {:keys [chip-key value]}]]
      (when-let [target (get h/chip-targets chip-key)]
        {:fx [[:dispatch [target value]]]})))

  (rf/reg-fx :rf.causa.fx/llm-stream
    (fn [_ctx _args]
      ;; The provider fetch implementation is follow-on work. The
      ;; registered no-op keeps the pull-only UI testable end-to-end.
      nil))

  nil)
