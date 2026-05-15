(ns re-frame.story.ui.command-palette
  "Pure helpers for Story's global command palette."
  (:require [clojure.string :as str]
            [re-frame.story.predicates :as pred]))

(def searchable-kinds
  [:story :variant :workspace :mode :decorator])

(def kind-labels
  {:story     "Story"
   :variant   "Variant"
   :workspace "Workspace"
   :mode      "Mode"
   :decorator "Decorator"})

(defn- doc-text [body]
  (let [doc (:doc body)]
    (cond
      (string? doc) doc
      (some? doc)   (pr-str doc)
      :else         "")))

(defn- id-text [id]
  (if (keyword? id)
    (str id)
    (pr-str id)))

(defn- entry-search-text [{:keys [kind id body]}]
  (str/lower-case
    (str (name kind) " " (id-text id) " " (doc-text body))))

(defn- variants-for-story [variants story-id]
  (->> variants
       keys
       (filter #(= story-id (pred/parent-story-id %)))
       sort
       vec))

(defn entries
  "Build palette entries from a `state/registry-snapshot` map.

  Stories include their child variants so the impure selector can jump
  to the first concrete variant when a story row is chosen."
  [snapshot]
  (let [variants (:variants snapshot)]
    (->> searchable-kinds
         (mapcat
           (fn [kind]
             (let [slot (case kind
                          :story     :stories
                          :variant   :variants
                          :workspace :workspaces
                          :mode      :modes
                          :decorator :decorators)]
               (map (fn [[id body]]
                      (cond-> {:kind        kind
                               :kind-label  (get kind-labels kind (name kind))
                               :id          id
                               :id-label    (id-text id)
                               :doc         (doc-text body)
                               :body        body}
                        (= kind :story)
                        (assoc :variant-ids (variants-for-story variants id))))
                    (sort-by (comp str first) (get snapshot slot {}))))))
         vec)))

(defn normalize-query [query]
  (-> (or query "")
      str/lower-case
      str/trim))

(defn- subsequence?
  "True when every char in `needle` appears in order in `haystack`."
  [needle haystack]
  (loop [remaining (seq needle)
         chars     (seq haystack)]
    (cond
      (nil? remaining) true
      (nil? chars)     false
      (= (first remaining) (first chars))
      (recur (next remaining) (next chars))
      :else
      (recur remaining (next chars)))))

(defn- token-score [entry token]
  (let [id    (str/lower-case (:id-label entry))
        kind  (name (:kind entry))
        doc   (str/lower-case (:doc entry))
        text  (entry-search-text entry)]
    (cond
      (str/blank? token) 0
      (= token id)      240
      (= token kind)    180
      (str/starts-with? id token) 140
      (str/includes? id token) 110
      (str/includes? doc token) 80
      (str/includes? text token) 60
      (subsequence? token id) 32
      (subsequence? token text) 18
      :else nil)))

(defn match-score
  "Return a positive score when `entry` matches `query`, otherwise nil.
  Matching is token-AND; each token may be a substring or fuzzy
  subsequence across kind/id/doc text."
  [entry query]
  (let [q      (normalize-query query)
        tokens (remove str/blank? (str/split q #"\s+"))]
    (if (empty? tokens)
      1
      (let [scores (map #(token-score entry %) tokens)]
        (when (every? some? scores)
          (+ (reduce + scores)
             (case (:kind entry)
               :variant   8
               :workspace 7
               :story     6
               :mode      5
               :decorator 4
               0)))))))

(defn search
  "Return matching entries sorted by descending score, then stable kind/id."
  ([entries query]
   (search entries query 20))
  ([entries query limit]
   (->> entries
        (keep (fn [entry]
                (when-let [score (match-score entry query)]
                  (assoc entry :score score))))
        (sort-by (fn [entry]
                   [(- (:score entry))
                    (.indexOf searchable-kinds (:kind entry))
                    (:id-label entry)]))
        (take limit)
        vec)))

(defn clamp-active-index [index result-count]
  (cond
    (not (pos? result-count)) 0
    (< index 0)               (dec result-count)
    (>= index result-count)   0
    :else                     index))

(defn move-active-index [index delta result-count]
  (clamp-active-index (+ (or index 0) delta) result-count))
