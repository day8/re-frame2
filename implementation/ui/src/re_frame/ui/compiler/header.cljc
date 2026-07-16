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
  (:require [re-frame.ui.compiler.binding-plan :as bp]
            [re-frame.ui.compiler.env :as env]))

(defn- fail [id msg data]
  (throw (env/compile-error id msg data)))

(defn slot-name
  "Q3 encode E: namespace + \"/\" + name when namespaced, else name —
  verbatim, no case conversion, no mangling (quoted JS property access
  makes any spelling legal)."
  [k]
  (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k)))

(defn- reserved-slot-check!
  "A view prop keyword may not be a reserved React slot. `:key` feeds React's
  key slot; `:ref` forwarding is the S3 spelling. Both are rejected wherever the
  author names them — a `:keys [key]` group symbol or an explicit `{p :key}`
  value alike — judged on what was WRITTEN, so the canonical collapse can never
  hide a reserved declaration."
  [k]
  (when (= k :key)
    (fail :rf.ui.compile/key-prop-declared
          (str ":key cannot be a view prop — it is reserved (it feeds React's "
               "key slot). Callers pass :key at the call site; it never "
               "arrives in props — remove the :key binding")
          {:key k}))
  (when (= k :ref)
    (fail :rf.ui.compile/ref-prop-declared-s1
          ":ref forwarding is declared per view and lands S3 — remove the :ref binding (conservative S1 pin)"
          {:key k}))
  k)

(defn- validate-header-map!
  "Judge a header map's SHAPE on what the author wrote, before the canonical
  plan collapses it: `:or` must be a map; `:strs`/`:syms` are outside the props
  ABI; a group needs a vector of symbols; a non-group keyword key is
  unsupported; an explicit entry's lookup value must be a prop keyword; and no
  produced slot may be a reserved `:key`/`:ref`. Collapse must never SUPPRESS a
  diagnostic, so these run on the raw entries, not the de-collided units."
  [b or-map]
  (when-not (map? or-map)
    (fail :rf.ui.compile/bad-defview-args
          ":or needs a map of binding-symbol -> default"
          {:or or-map}))
  (reduce-kv
   (fn [_ k v]
     (cond
       (= k :as) nil
       (= k :or) nil

       (and (keyword? k) (contains? #{"strs" "syms"} (name k)))
       (fail :rf.ui.compile/bad-defview-args
             (str k " is outside the props ABI — prop slots are keywords; use "
                  ":keys")
             {:key k})

       (and (keyword? k) (= "keys" (name k)))
       (do (when-not (and (vector? v) (every? symbol? v))
             (fail :rf.ui.compile/bad-defview-args
                   (str k " needs a vector of symbols") {:form v}))
           (doseq [s v]
             (reserved-slot-check! (if-let [ns* (namespace k)]
                                     (keyword ns* (name s))
                                     (keyword (name s))))))

       (keyword? k)
       (fail :rf.ui.compile/bad-defview-args
             (str "unsupported header key " k " — supported: :keys,"
                  " :<ns>/keys, :or, :as, and {pattern :prop-key} entries")
             {:key k})

       :else
       ;; {pattern :prop-key} — pattern may itself destructure
       (do (when-not (keyword? v)
             (fail :rf.ui.compile/bad-defview-args
                   (str "header entry {" (pr-str k) " " (pr-str v)
                        "} — the right side must be a prop keyword")
                   {:entry [k v]}))
           (reserved-slot-check! v)))
     nil)
   nil
   b)
  nil)

(defn- collapse-entries
  "Keep exactly ONE entry per bound local (`:pattern`) — the host `bes`-order
  WINNER, i.e. the LAST occurrence, the binding the host's `let` last-wins
  actually establishes. `bp/assoc-binding-units` already collapses two units
  that share a `bes` KEY (`{:keys [x] x :foo}` → the group's `assoc` overwrites
  the explicit entry, one `x <- :x`). But a QUALIFIED group local whose bare
  name collides an explicit local (`{:keys [ns/x] x :other}`) keeps two DISTINCT
  `bes` keys (`ns/x`, `x`) that both name-strip to the same local `x`; those
  arrive as two units binding one local via host last-wins. Left uncollapsed
  they leave a DEAD slot (`:other` here) in the derived `:slots` — over-comparing
  in the memo comparator (spurious re-renders), over-declaring in the manifest
  `:prop-slots`, and silently accepting a never-bound key under CLOSED `:props`.
  De-duping by `:pattern` here — keeping the last, host-winning entry — restores
  the 'never two entries for one local' invariant so the derived slots carry no
  key the view never binds. The winning entry stays in its `bes` position; a
  same-key collision across DISTINCT locals (`{:keys [x] y :x}` — two locals,
  one slot) is untouched (both patterns differ, so both survive; the slot
  `distinct` folds the shared key)."
  [entries]
  (let [last-idx (into {} (map-indexed (fn [i e] [(:pattern e) i])) entries)]
    (into [] (keep-indexed (fn [i e] (when (= i (last-idx (:pattern e))) e)))
          entries)))

(defn parse-header
  "argv -> {:mode :none|:named|:as, :as-sym, :entries [{:key :slot
  :pattern :default}...], :slots [kw...], :children? bool, :binding-form}

  The binding `:entries` and `:slots` are the CANONICAL, host-faithful binding
  units (`bp/assoc-binding-units`, then `collapse-entries`): one entry per bound
  local, in host `destructure` `bes` order, carrying the WINNING lookup key after
  collapse. Two header entries that bind the same local COLLAPSE to a single
  entry — never two, never a silent last-wins — whether they collide in the host
  `bes` map (`{:keys [x] x :foo}` → `x <- :x`) or only after the qualified group
  local name-strips onto an explicit local (`{:keys [ns/x] x :other}` → the host
  last-wins `x <- :ns/x`, the explicit `:other` dropped). A qualified group local
  (`{:keys [acct/id]}`) keeps its qualified slot `:acct/id`. Both emitters, the
  schema slot order, the memo comparator and the manifest metadata read these
  same collapsed entries, so no downstream path can re-collide, strip a qualified
  key, or inherit a dead slot."
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
              _       (validate-header-map! b or-map)
              ;; ONE canonical, de-collided binding plan — the host `bes` order
              ;; with winning lookup keys. `collapse-entries` folds any two units
              ;; that bind the SAME local (including the qualified-group-name
              ;; collision `bp/assoc-binding-units` leaves as two) to the single
              ;; host-winning entry, so no downstream consumer inherits a dead
              ;; slot, re-collides, or strips a qualified key.
              entries (collapse-entries
                       (mapv (fn [{:keys [local-pattern key]}]
                               (cond-> {:key key
                                        :slot (slot-name key)
                                        :pattern local-pattern}
                                 (and (symbol? local-pattern)
                                      (contains? or-map local-pattern))
                                 (assoc :default (get or-map local-pattern))))
                             (bp/assoc-binding-units b)))
              _ (doseq [s (keys or-map)]
                  (when-not (some #(= s (:pattern %)) entries)
                    (fail :rf.ui.compile/bad-defview-args
                          (str ":or key " s " does not match any bound slot symbol")
                          {:or or-map})))
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
