(ns re-frame.freehand.compiler.header
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
    is an ordinary declarable slot — a view forwards a ref only by
    declaring it (React 19 ref-as-prop).
  - `:strs`/`:syms` are outside the props ABI (slots are keywords)."
  (:require [re-frame.freehand.compiler.binding-plan :as bp]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.props-schema :as props-schema]))

(defn- fail [id msg data]
  (throw (env/compile-error id msg data)))

(defn slot-name
  "Q3 encode E: namespace + \"/\" + name when namespaced, else name —
  verbatim, no case conversion, no mangling (quoted JS property access
  makes any spelling legal)."
  [k]
  (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k)))

(defn- reserved-slot-check!
  "A view prop keyword may not be the reserved React `:key` slot (it feeds
  React's key slot). It is rejected wherever the author names it — a
  `:keys [key]` group symbol or an explicit `{p :key}` value alike — judged on
  what was WRITTEN, so the canonical collapse can never hide a reserved
  declaration. `:ref` is NOT rejected here: React 19 passes it as an ordinary
  prop, so a view forwards a ref by declaring `:ref` like any other slot (a
  ref's commit-phase contract lives at the `:ref` call site, not the header)."
  [k]
  (when (= k :key)
    (fail :rf.ui.compile/key-prop-declared
          (str ":key cannot be a view prop — it is reserved (it feeds React's "
               "key slot). Callers pass :key at the call site; it never "
               "arrives in props — remove the :key binding")
          {:key k}))
  k)

(defn- validate-header-map!
  "Judge a header map's SHAPE on what the author wrote, before the canonical
  plan collapses it: `:or` must be a map; `:strs`/`:syms` are outside the props
  ABI; a group needs a vector of symbols; a non-group keyword key is
  unsupported; an explicit entry's lookup value must be a prop keyword; and no
  produced slot may be the reserved `:key`. Collapse must never SUPPRESS a
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
           ;; Derive each group key with the CANONICAL transform the binding plan
           ;; uses (`bp/key-group-directive-fn`), so `{:keys [acct/key]}` derives
           ;; the qualified `:acct/key` — NOT a bare `:key` from `(keyword (name
           ;; s))` — while a genuinely bare `:keys [key]` still derives the
           ;; reserved `:key` and fails. One namespace rule for the diagnostic
           ;; and the plan, so equivalent host spellings agree.
           (let [group-key (bp/key-group-directive-fn k)]
             (doseq [s v]
               (reserved-slot-check! (group-key s)))))

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
  "Project the full host binding-unit plan to its FINAL visible-local slots: keep
  an entry only where it binds a local NO LATER entry rebinds — the bindings the
  host's `let` last-wins actually leaves visible. `bp/assoc-binding-units` already
  collapses two units that share a `bes` KEY (`{:keys [x] x :foo}` → the group's
  `assoc` overwrites the explicit entry, one `x <- :x`). The residual shadows it
  does NOT catch arrive as DISTINCT units binding one local via host last-wins:
    - a QUALIFIED group local whose bare name collides an explicit local
      (`{:keys [ns/x] x :other}` — `ns/x` ≠ `x` as `bes` keys, both name-strip to
      the local `x`);
    - a NESTED or partially overlapping pattern (`{[x] :other :keys [ns/x]}` —
      `[x]` and `x` are unequal `:pattern` values, but `[x]`'s only local `x` is
      reclaimed by the group's `x <- :ns/x`).
  Left uncollapsed these leave a DEAD slot (`:other` here) in the derived `:slots`
  — over-comparing in the memo comparator (spurious re-renders), over-declaring in
  the manifest `:prop-slots`, and silently accepting a never-bound key under
  CLOSED `:props`.

  A reverse sweep restores the 'no dead slot' invariant: walk entries LAST→first,
  keeping the set of locals a later entry already claimed; retain an entry only
  where it binds at least one local NOT yet claimed (a final visible local), then
  add its locals to the claimed set. Comparing the LOCALS each pattern binds
  (`bp/pattern-locals`) rather than whole `:pattern` values is what catches the
  nested case a whole-pattern equality misses. The winning entries stay in their
  `bes` positions, and the FULL executable plan (`:binding-units`) is untouched —
  every host initializer still runs. Two DISTINCT locals sharing one slot
  (`{:keys [x] y :x}`) both survive; a partial overlap (`{[a b] :other :keys
  [ns/a]}`) keeps `:other` because `b` is still a final visible local; the slot
  `distinct` folds any shared key."
  [entries]
  (let [v (vec entries)]
    (loop [i (dec (count v)), claimed #{}, keep #{}]
      (if (neg? i)
        (into [] (keep-indexed (fn [idx e] (when (contains? keep idx) e))) v)
        (let [locals (bp/pattern-locals (:pattern (nth v i)))]
          (if (some (complement claimed) locals)
            (recur (dec i) (into claimed locals) (conj keep i))
            (recur (dec i) claimed keep)))))))

(defn parse-header
  "argv -> {:mode :none|:named|:as, :as-sym, :entries [{:key :slot :pattern
  :default}...], :binding-units [...same shape...], :slots [kw...], :children?
  bool, :binding-form}

  TWO views of the ONE canonical, host-faithful binding plan
  (`bp/assoc-binding-units`), split by what the host actually does:

  - `:binding-units` — the FULL host binding-unit plan, one unit per `bes`
    entry, in host `destructure` order, carrying its winning lookup key and any
    `:or` default. The EXECUTABLE emission (the CLJS `header-bindings` lowering)
    and the analyzer's scope walk consume this, so every host initializer runs
    and the host `let` last-wins is reproduced exactly — including the two units
    a qualified-group-name collision (`{:keys [ns/x] x :other}`) establishes for
    one local (rf2-nedxb).

  - `:entries` + `:slots` — the COLLAPSED effective-slot projection (`:entries`
    run through `collapse-entries`): one entry per bound local, the host-winning
    last occurrence. DERIVED metadata — the memo comparator, the manifest
    `:prop-slots`, the closed-`:props` slot set and the schema slot order — reads
    these, so it never over-declares a dead slot (`{:keys [ns/x] x :other}` drops
    `:other`), re-collides, or strips a qualified key (`{:keys [acct/id]}` keeps
    `:acct/id`).

  For every header WITHOUT a qualified-group-name collision the two views are
  identical; they differ only where the host binds one local through two `bes`
  keys. The JVM emitter uses `:binding-form` (native destructuring) and needs
  neither view."
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
    {:mode :none :as-sym nil :entries [] :binding-units [] :slots []
     :children? false :binding-form nil}
    (let [b (first argv)]
      (cond
        (symbol? b)
        {:mode :as :as-sym b :entries [] :binding-units [] :slots []
         :children? true :binding-form b}

        (map? b)
        (let [as-sym  (get b :as)
              or-map  (get b :or {})
              _       (validate-header-map! b or-map)
              ;; The FULL host binding-unit plan — the host `bes` order with
              ;; winning lookup keys, EVERY unit the host `let` establishes. A
              ;; qualified-group-name collision (`{:keys [ns/x] x :other}`) is two
              ;; units binding one local via host last-wins; both stay here so
              ;; executable emission runs every host initializer (rf2-nedxb).
              binding-units
              (mapv (fn [{:keys [local-pattern key]}]
                      (cond-> {:key key
                               :slot (slot-name key)
                               :pattern local-pattern}
                        (and (symbol? local-pattern)
                             (contains? or-map local-pattern))
                        (assoc :default (get or-map local-pattern))))
                    (bp/assoc-binding-units b))
              ;; The COLLAPSED effective-slot projection — one entry per bound
              ;; local (the host-winning last occurrence). `collapse-entries` folds
              ;; the qualified-group-name collision to its single winner, so the
              ;; comparator, manifest and schema slots carry no dead key.
              entries (collapse-entries binding-units)
              _ (doseq [s (keys or-map)]
                  (when-not (some #(= s (:pattern %)) entries)
                    (fail :rf.ui.compile/bad-defview-args
                          (str ":or key " s " does not match any bound slot symbol")
                          {:or or-map})))
              slots   (into [] (distinct) (map :key entries))]
          {:mode (if as-sym :as :named)
           :as-sym as-sym
           :entries entries
           :binding-units binding-units
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
  time).

  The parse itself lives in [[re-frame.freehand.props-schema]], which owns
  the schema's meaning for both execution modes; slot layout reads it
  through here so a schema is never parsed two ways."
  [schema]
  (props-schema/declared-keys schema))

(defn declared-slots
  "Comparator/manifest slot order: header slots first, then schema-only
  keys (Q2 pin). :children participates as one slot when declared."
  [header schema-keys]
  (let [hs (:slots header)
        extra (remove (set hs) (or schema-keys []))]
    (into (vec hs) extra)))
