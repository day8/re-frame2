(ns re-frame.story.ui.command-palette
  "Pure helpers for Story's global command palette."
  (:require [clojure.string :as str]
            [re-frame.story.predicates :as pred]))

(def searchable-kinds
  [:command :story :variant :workspace :mode :decorator])

(def kind-labels
  {:command   "Command"
   :story     "Story"
   :variant   "Variant"
   :workspace "Workspace"
   :mode      "Mode"
   :decorator "Decorator"})

(def commands
  "Synthetic palette entries that run a UI action rather than navigate
  the registry. Each carries an `:action` keyword the
  impure `select-entry!` dispatches. Kept tiny + data-only so the JVM
  search corpus can rank them alongside registry entries; the action →
  side-effect mapping lives in the CLJS view.

  - `:explain` — open the Explain panel (provenance + lowering over
    `story/explain`) and scroll it into view. Reachable here AND from
    the RHS inspector rail per spec/020 §4.
  - `:save-current-as-variant` — capture the focused
    variant's live canvas state as a new variant. The SAME flow as the
    Controls-panel 'save as new variant…' button (spec/019 §3 — the flow
    is available from Controls AND the palette). A no-op when no variant
    is focused; the action handler guards on the live selection.

  - `:author-expectations` — author EXPECTATIONS onto the
    focused story so a useful story becomes a regression test (spec/021
    §S5). Distinct from `:save-current-as-variant` (state authoring) and
    failure promotion (a captured artifact). A no-op when no variant is
    focused; the action handler guards on the live selection."
  [{:kind   :command
    :id     :explain
    :action :explain
    :doc    "Explain variant — source chain, merges, substitutions, lowering"}
   {:kind   :command
    :id     :save-current-as-variant
    :action :save-current-as-variant
    :doc    "Save current canvas state as a new variant — capture live args, review + copy the reg-variant form"}
   {:kind   :command
    :id     :author-expectations
    :action :author-expectations
    :doc    "Add expectations to this story — author app-db / sub / DOM / schema / a11y expectations, see runner cost before save, copy the :assertions form"}])

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

(def ^:private registry-kinds
  "The `searchable-kinds` that resolve to a registry slot — i.e. all
  except the synthetic `:command` kind, which is folded in separately."
  [:story :variant :workspace :mode :decorator])

(defn command-entries
  "Build the synthetic command entries in palette-entry
  shape. Pure data → data so the search corpus can include them."
  []
  (mapv (fn [{:keys [kind id action doc]}]
          {:kind       kind
           :kind-label (get kind-labels kind (name kind))
           :id         id
           :id-label   (id-text id)
           :action     action
           :doc        (or doc "")})
        commands))

(defn entries
  "Build palette entries from a `state/registry-snapshot` map.

  Synthetic `:command` entries (e.g. `Explain variant`) lead the list
  so an action like `explain` is one keystroke away; registry entries
  follow. Stories include their child variants so the impure selector
  can jump to the first concrete variant when a story row is chosen."
  [snapshot]
  (let [variants (:variants snapshot)]
    (into
      (command-entries)
      (->> registry-kinds
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
           vec))))

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
               :command   9
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
