(ns day8.re-frame2-causa.panels.ai-co-pilot-conversation-model
  "Pure conversation buffer transforms for the AI Co-Pilot.")

(defn empty-conversation
  []
  [])

(defn append-question
  [conversation text]
  (conj (vec conversation)
        {:role :question
         :text (str text)
         :streaming? false}))

(defn start-answer
  [conversation]
  (conj (vec conversation)
        {:role       :answer
         :text       ""
         :streaming? true}))

(defn append-token
  "Append one token to the trailing in-flight answer, otherwise no-op."
  [conversation token]
  (let [conv (vec conversation)
        n    (count conv)]
    (if (zero? n)
      conv
      (let [last-turn (nth conv (dec n))]
        (if (and (= :answer (:role last-turn))
                 (:streaming? last-turn))
          (assoc conv (dec n)
                 (update last-turn :text str token))
          conv)))))

(defn end-answer
  "Mark the trailing in-flight answer as no longer streaming."
  [conversation]
  (let [conv (vec conversation)
        n    (count conv)]
    (if (zero? n)
      conv
      (let [last-turn (nth conv (dec n))]
        (if (and (= :answer (:role last-turn))
                 (:streaming? last-turn))
          (assoc conv (dec n)
                 (assoc last-turn :streaming? false))
          conv)))))
