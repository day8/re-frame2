(ns re-frame.ui.compiler.header
  "defview header (props binding) analysis — the Q2 surface.

  ## The Q2 pins (PR section 'Q2 — props binding policy'; all test-pinned)

  - Zero or one argument, semantically a props map. `[sym]` ≡ `[{:as sym}]`.
  - Header destructuring lowers to direct property reads on the host props
    object (CLJS) / native map destructuring (JVM); no CLJS map at entry.
  - `:as` opts into materialization (a CLJS map of ALL present slots) +
    GENERIC comparison over the slot union (documented dev cost).
  - `:or` defaults apply iff the slot is ABSENT (JS undefined), mirroring
    Clojure map-destructuring; a present-nil slot stays nil. Defaults
    affect the BINDING only — never the comparator, never the props.
  - Namespaced `:x/keys [a]` binds slot \"x/a\"; explicit `{p :x/a}`
    likewise. Nested patterns bind the slot value then destructure it
    normally (one lowering rule).
  - Declared slots = header top-level keys ∪ literal `[:map ...]` :props
    schema top-level keys (header order first, then schema-only keys).
    The memo comparator runs over exactly these slots (+ children).
  - `:props` ABSENT = OPEN map (extra call-site props are legal and
    invisible); `:props` PRESENT = CLOSED map (undeclared keys at literal
    call sites are compile errors).
  - `:key` cannot be a declared prop (it feeds React's key slot);
    `:children` in the header declares child acceptance (Q4); `:ref`
    declaration is the S3 forwarding spelling and is rejected at S1.
  - `:strs`/`:syms` are outside the props ABI (slots are keywords)."
  (:require [re-frame.ui.compiler.env :as env]))

(defn- fail [id msg data]
  (throw (env/compile-error id msg data)))

(defn slot-name
  "Q3 encode E: namespace + \"/\" + name when namespaced, else name —
  verbatim, no case conversion, no mangling (quoted JS property access
  makes any spelling legal)."
  [k]
  (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k)))

(defn- entry [k pattern]
  (when (= k :key)
    (fail :rf.ui.compile/key-prop-declared
          ":key cannot be a view prop — it is reserved (it feeds React's key slot)"
          {:key k}))
  (when (= k :ref)
    (fail :rf.ui.compile/ref-prop-declared-s1
          ":ref forwarding is declared per view and lands S3 — remove the :ref binding (conservative S1 pin)"
          {:key k}))
  {:key k :slot (slot-name k) :pattern pattern})

(defn parse-header
  "argv -> {:mode :none|:named|:as, :as-sym, :entries [{:key :slot
  :pattern :default}...], :slots [kw...], :children? bool, :binding-form}"
  [argv]
  (when-not (vector? argv)
    (fail :rf.ui.compile/bad-defview-args
          "defview needs an argument vector: [] or [{...props destructuring...}]"
          {:argv argv}))
  (when (> (count argv) 1)
    (fail :rf.ui.compile/positional-args
          (str "defview takes zero or one argument — one props map, no "
               "positional args. Got " (count argv))
          {:argv argv}))
  (if (empty? argv)
    {:mode :none :as-sym nil :entries [] :slots [] :children? false
     :binding-form nil}
    (let [b (first argv)]
      (cond
        (symbol? b)
        {:mode :as :as-sym b :entries [] :slots [] :children? true
         :binding-form b}

        (map? b)
        (let [as-sym  (get b :as)
              or-map  (get b :or {})
              _       (when-not (map? or-map)
                        (fail :rf.ui.compile/bad-defview-args
                              ":or needs a map of binding-symbol -> default"
                              {:or or-map}))
              entries
              (reduce-kv
               (fn [acc k v]
                 (cond
                   (= k :as) acc
                   (= k :or) acc

                   (and (keyword? k) (contains? #{"strs" "syms"} (name k)))
                   (fail :rf.ui.compile/bad-defview-args
                         (str k " is outside the props ABI — prop slots are "
                              "keywords; use :keys")
                         {:key k})

                   (and (keyword? k) (= "keys" (name k)))
                   (do (when-not (and (vector? v) (every? symbol? v))
                         (fail :rf.ui.compile/bad-defview-args
                               (str k " needs a vector of symbols") {:form v}))
                       (into acc
                             (map (fn [s]
                                    (entry (if-let [ns* (namespace k)]
                                             (keyword ns* (name s))
                                             (keyword (name s)))
                                           (symbol (name s)))))
                             v))

                   (keyword? k)
                   (fail :rf.ui.compile/bad-defview-args
                         (str "unsupported header key " k " — supported: :keys,"
                              " :<ns>/keys, :or, :as, and {pattern :prop-key}"
                              " entries")
                         {:key k})

                   :else
                   ;; {pattern :prop-key} — pattern may itself destructure
                   (do (when-not (keyword? v)
                         (fail :rf.ui.compile/bad-defview-args
                               (str "header entry {" (pr-str k) " " (pr-str v)
                                    "} — the right side must be a prop keyword")
                               {:entry [k v]}))
                       (conj acc (entry v k)))))
               []
               b)
              defaults (into {}
                             (keep (fn [{:keys [pattern] :as en}]
                                     (when (and (symbol? pattern)
                                                (contains? or-map pattern))
                                       [(:key en) (get or-map pattern)])))
                             entries)
              _ (doseq [s (keys or-map)]
                  (when-not (some #(= s (:pattern %)) entries)
                    (fail :rf.ui.compile/bad-defview-args
                          (str ":or key " s " does not match any bound slot symbol")
                          {:or or-map})))
              entries (mapv (fn [en]
                              (if (contains? defaults (:key en))
                                (assoc en :default (get defaults (:key en)))
                                en))
                            entries)
              slots   (into [] (distinct) (map :key entries))]
          {:mode (if as-sym :as :named)
           :as-sym as-sym
           :entries entries
           :slots slots
           :children? (boolean (or as-sym (some #(= :children %) slots)))
           :binding-form b})

        :else
        (fail :rf.ui.compile/bad-defview-args
              (str "defview's one argument must be a map-destructuring form "
                   "or a symbol (≡ {:as sym}); got " (pr-str b))
              {:argv argv})))))

(defn props-schema-keys
  "Top-level prop keys of a LITERAL Malli [:map ...] :props schema; nil
  when the schema is absent or not a literal :map vector (no closed-map
  enforcement then — an opaque schema cannot be introspected at compile
  time)."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (let [entries (rest schema)
          entries (if (map? (first entries)) (rest entries) entries)]
      (into []
            (keep #(when (and (vector? %) (keyword? (first %))) (first %)))
            entries))))

(defn declared-slots
  "Comparator/manifest slot order: header slots first, then schema-only
  keys (Q2 pin). :children participates as one slot when declared."
  [header schema-keys]
  (let [hs (:slots header)
        extra (remove (set hs) (or schema-keys []))]
    (into (vec hs) extra)))
