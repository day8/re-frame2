(ns day8.re-frame2-causa.panels.ai-co-pilot-subs
  "Subscriptions for the AI Co-Pilot panel."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]))

(defn install!
  "Install AI Co-Pilot subscriptions."
  []
  (rf/reg-sub :rf.causa/copilot-open?
    (fn [db _query]
      (boolean (get db :copilot-open? false))))

  (rf/reg-sub :rf.causa/copilot-conversation
    (fn [db _query]
      (get db :copilot-conversation [])))

  (rf/reg-sub :rf.causa/copilot-provider
    (fn [db _query]
      (get db :copilot-provider :claude)))

  (rf/reg-sub :rf.causa/copilot-cue-active?
    (fn [db _query]
      (not (true? (get db :copilot-first-used?)))))

  (rf/reg-sub :rf.causa/copilot-redaction-settings
    (fn [db _query]
      (get db :copilot-redaction-settings h/default-redaction-settings)))

  (rf/reg-sub :rf.causa/copilot-streaming-token-count
    (fn [db _query]
      (get db :copilot-streaming-token-count 0)))

  (rf/reg-sub :rf.causa/copilot-input-text
    (fn [db _query]
      (get db :copilot-input-text "")))

  nil)
