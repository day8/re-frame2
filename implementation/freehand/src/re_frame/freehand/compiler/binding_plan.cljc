(ns re-frame.freehand.compiler.binding-plan
  "The ONE canonical, host-faithful associative-destructuring binding plan.

  A map destructuring pattern (`{:keys [x] x :foo}`, `{:keys [acct/id]}`, …)
  has EXACTLY ONE meaning: whatever `clojure.core/destructure` binds. Both hosts
  run `destructure` on the JVM at macro-expansion time, so reproducing its
  remaining-bindings transformation here — rather than invoking a general
  macroexpander — yields a plan that is faithful to CLJ and CLJS alike.

  This plan is the SINGLE authority every consumer routes through, so none can
  drift from another or from the host:

  - the analyzer's reactive-binding scope walk (`analyze/check-binding-scope!`);
  - the defview header parser (`header/parse-header`), whose emitted binding
    units, schema slots, comparator inputs and manifest metadata are all read
    off these collapsed entries;
  - and, transitively, both emitters — the JVM emitter destructures the raw
    pattern natively; the CLJS emitter lowers exactly these units to property
    reads. Because the units are the host's collapse, the two emitters agree.

  The critical property is COLLAPSE at the host `bes` map: when two entries share
  a `bes` KEY (`{:keys [x] x :foo}` — the group directive's `assoc` overwrites the
  explicit `x`), the host keeps ONE binding with ONE winning lookup key
  (`x <- :x`), and so does this plan — never two bindings, never a silent
  last-wins. A qualified group local (`{:keys [acct/id]}`) keeps its qualified
  lookup key (`:acct/id`), never a bare `:id`.

  One residual case does NOT collapse here, deliberately: a qualified group local
  whose bare NAME collides an explicit local (`{:keys [ns/x] x :other}`) keeps two
  DISTINCT `bes` keys (`ns/x`, `x`) that both name-strip to the same local `x`. The
  host binds it once via `let` last-wins (`x <- :ns/x`), but the two units must
  stay separate HERE so the analyzer's scope walk judges each unit's lookup-key /
  `:or` default against the scope live at ITS binding point. The defview header
  parser (`header/collapse-entries`) folds these two units to the single winning
  entry for its derived slots; the general scope walk keeps both.")

(defn key-group-kw?
  "True for a `:keys`/`:strs`/`:syms` map-destructuring group directive (any
  namespace: `:person/keys` too). Its map-entry VALUE is a vector of symbols;
  its keys are keyword/string/symbol LITERALS, never evaluated expressions."
  [k]
  (and (keyword? k) (contains? #{"keys" "strs" "syms"} (name k))))

(defn key-group-directive-fn
  "The lookup-key transform host `destructure` applies to a `:keys`/`:strs`/
  `:syms` group directive `mk` (any namespace). This is the SHARED CLJ/CLJS
  transform — both hosts run `destructure` on the JVM at macro-expansion time —
  reproduced verbatim so the produced key (and, through it, the map's iteration
  order) matches the host exactly."
  [mk]
  (let [mkns (namespace mk)]
    (case (name mk)
      "keys" #(keyword (or mkns (namespace %)) (name %))
      "syms" #(list 'quote (symbol (or mkns (namespace %)) (name %)))
      "strs" str)))

(defn assoc-binding-units
  "The associative-destructuring binding units of map `pattern`, in the EXACT
  host `destructure` `bes` order — the ONE canonical, host-faithful binding
  plan every consumer shares, so none can drift from another or from the host.

  It reproduces `destructure`'s remaining-bindings transformation rather than
  invoking a general macroexpander: start from `(dissoc pattern :as :or)`, then
  for each group directive — in the order it appears in the pattern's keys —
  `dissoc` the directive and `assoc` each expanded local exactly as the host
  does, and read the units off `(seq bes)` in the resulting map-iteration order.
  Small patterns stay a `PersistentArrayMap` (insertion order); at nine or more
  remaining bindings the host promotes to a `PersistentHashMap` and the order
  becomes hash-driven. Because every host computes this on the JVM the 8→9
  threshold and the hash order are identical on CLJ and CLJS, so reproducing the
  transform here is faithful to both.

  Two entries that share a `bes` KEY COLLAPSE, exactly as the host's `bes` map
  collapses them: `{:keys [x] x :foo}` yields the single unit `x <- :x` (the
  group directive's `assoc` overwrites the explicit entry's value), never two
  bindings and never a silent last-wins. A group local keeps its QUALIFIED
  lookup key: `{:keys [acct/id]}` yields `id <- :acct/id`, never bare `:id`. A
  qualified group local whose bare name collides an explicit local
  (`{:keys [ns/x] x :other}`) keeps DISTINCT `bes` keys and so stays TWO units
  binding one local via host last-wins — consumed in order by the scope walk;
  the header parser (`collapse-entries`) folds them for its derived slots.

  `:as` is NOT included — it binds first, before `bes`, so the caller seeds it
  into scope. Each unit is `{:local-pattern p :key k :explicit? bool}`:
  `:local-pattern` is the bound local (a simple symbol for a group local, or the
  written local pattern — possibly nested — for an explicit `{p key}` entry);
  `:key` is the WINNING lookup key after collapse (an EVALUATED expression for an
  explicit entry, or the produced literal keyword/string/quoted-symbol for a
  group local); `:explicit?` marks the units whose `:key` is a user-written
  expression (a scope walk must judge those for a reactive-authoring escape; a
  group literal never is). The units are consumed in order so each binding's
  default/lookup-key is judged against the scope established BEFORE it — never
  against a symbol the same pattern binds later, and never in an order the host
  would not use."
  [pattern]
  (let [start      (dissoc pattern :as :or)
        transforms (reduce (fn [t k] (if (key-group-kw? k) (assoc t k true) t))
                           {} (keys pattern))
        explicit   (set (keys (reduce dissoc start (keys transforms))))
        bes        (reduce
                    (fn [bes [mk _]]
                      (let [f (key-group-directive-fn mk)]
                        (reduce (fn [b s] (assoc b s (f s)))
                                (dissoc bes mk)
                                (get bes mk))))
                    start
                    transforms)]
    (for [[bb bk] bes]
      (if (contains? explicit bb)
        {:local-pattern bb :key bk :explicit? true}
        ;; Reconstruct the group local exactly as the host does — name-stripped
        ;; (`{:keys [acct/id]}` binds `id`), but carrying the AUTHORED symbol's
        ;; metadata (`(with-meta (symbol nil (name bb)) (meta bb))`), so a `^js`
        ;; or other portable type hint survives to the CLJS `let` binding and its
        ;; Closure interop inference. Only the host's existing metadata is kept;
        ;; nothing is invented, matching `clojure.core/destructure` verbatim.
        {:local-pattern (with-meta (symbol nil (name bb)) (meta bb))
         :key bk :explicit? false}))))

(defn binding-order
  "The local-patterns of map `pattern`, in host `destructure` `bes` order
  (`:as` excluded — it binds first). One plan (`assoc-binding-units`), so a
  dependent `:or` default resolves to the same symbol on every host."
  [pattern]
  (mapv :local-pattern (assoc-binding-units pattern)))

(defn pattern-locals
  "The set of local symbols a destructuring binding PATTERN introduces, exactly
  as host `destructure` binds them. This is the host-faithful primitive a
  DERIVED-slot consumer uses to judge which binding units survive host `let`
  last-wins shadowing: a unit whose every local a LATER unit rebinds contributes
  no final visible local, so its lookup key is a dead slot even though its
  initializer still runs (`assoc-binding-units` keeps it in the executable plan).

  A simple symbol binds itself. A sequential `[a b & more :as all]` binds each
  element pattern (recursively), the rest pattern and `:as`. An associative
  `{:keys [...] p key :as s :or {...}}` binds each group local NAME-STRIPPED
  (`:keys [ns/x]` binds `x`, exactly as the plan's group locals arrive), each
  explicit sub-pattern (recursively), and `:as`. Directives (`:or`, the group
  keyword) and lookup keys are not locals; the `&` marker is dropped.

  Comparing the LOCALS a pattern binds — not whole `:pattern` values — is what
  lets a nested or partially overlapping pattern collapse faithfully: a symbol
  `x <- :ns/x` shadows the `x` a sibling `[x] <- :other` binds (whole-pattern
  equality would miss it, leaking `:other`), while `[a b] <- :other` survives
  beside `a <- :ns/a` because `b` is still a final visible local."
  [pattern]
  (cond
    (symbol? pattern)
    (if (= '& pattern) #{} #{pattern})

    (vector? pattern)
    (loop [ps (seq pattern), acc #{}]
      (if (nil? ps)
        acc
        (let [p (first ps)]
          (if (or (= :as p) (= '& p))
            (recur (nnext ps) (into acc (pattern-locals (second ps))))
            (recur (next ps)  (into acc (pattern-locals p)))))))

    (map? pattern)
    (reduce-kv
     (fn [acc k v]
       (cond
         (= k :as)         (into acc (pattern-locals v))
         (= k :or)         acc
         (key-group-kw? k) (into acc (map (fn [s] (symbol nil (name s)))) v)
         :else             (into acc (pattern-locals k))))
     #{}
     pattern)

    :else #{}))
