(ns re-frame.ui.migrator.resolve
  "Namespace-form parsing + symbol resolution for the W1 migrator.

  Several MIG rules (MIG-01/02/04/..) key off whether a symbol resolves to a
  particular Reagent / re-frame.core var - through `:as` aliases, `:refer`,
  `:rename`, and their `:require-macros`/`:refer-macros` twins (the realworld
  files reach `reg-view` via `(:require-macros [re-frame.core :refer [reg-view]])`;
  prep Open items 4). This namespace builds a small resolution environment
  from a file's `ns` form and answers `(resolve-sym env sym) -> \"ns/name\"`.

  It is deliberately a light lexical resolver, not a full analyzer: it maps a
  symbol's textual namespace-part to the required namespace it aliases, and a
  bare referred/renamed symbol to its qualified origin. That is exactly the
  span the detectors need."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; ns-form parsing
;; ---------------------------------------------------------------------------

(defn- as-form
  "Read a node to an sexpr, nil on failure."
  [node]
  (try (n/sexpr node) (catch Exception _ nil)))

(defn- ns-form-node
  "Return the top-level `(ns ...)` list node in `zroot`, or nil."
  [zroot]
  (loop [zloc zroot]
    (cond
      (z/end? zloc) nil
      (and (= :list (z/tag zloc))
           (let [h (z/down zloc)]
             (and h (= :token (z/tag h)) (= 'ns (as-form (z/node h))))))
      (z/node zloc)
      :else (recur (z/next zloc)))))

(defn- require-libspecs
  "Given an sexpr `ns` form, return the seq of libspecs found under every
  `:require` and `:require-macros` clause (macro libspecs are merged in - a
  `reg-view` referred via `:require-macros` resolves the same as via `:require`
  for the purposes of construct detection)."
  [ns-sexpr]
  (->> (rest ns-sexpr)
       (filter seq?)
       (filter (fn [clause] (#{:require :require-macros} (first clause))))
       (mapcat rest)))

(defn- parse-libspec
  "Parse a single libspec into `{:alias ... :ns ... :refers {local qualified}}`.
  A libspec is either a bare symbol `foo.bar` or a vector
  `[foo.bar :as fb :refer [a b] :rename {a a2}]`."
  [libspec]
  (cond
    (symbol? libspec)
    {:ns libspec :alias nil :refers {}}

    (vector? libspec)
    (let [nsym (first libspec)
          opts (apply hash-map (rest libspec))
          alias (:as opts)
          refers (:refer opts)
          renames (:rename opts)
          base   (cond
                   (= :all refers) {}          ;; can't enumerate; leave empty
                   (sequential? refers)
                   (into {} (map (fn [s] [s (symbol (name nsym) (name s))])) refers)
                   :else {})
          ;; `:rename {orig new}` - the NEW local name resolves to orig's origin
          renamed (when (map? renames)
                    (into {} (map (fn [[orig new]]
                                    [new (symbol (name nsym) (name orig))]))
                          renames))]
      {:ns nsym :alias alias :refers (merge base renamed)})

    :else nil))

(defn env-from-ns
  "Build a resolution environment from a file's `ns` sexpr (or nil).
  Returns `{:aliases {alias-sym ns-sym} :refers {local-sym qualified-sym}
            :requires #{ns-sym}}`."
  [ns-sexpr]
  (let [specs (keep parse-libspec (require-libspecs ns-sexpr))]
    {:aliases  (into {} (keep (fn [{:keys [alias ns]}] (when alias [alias ns])) specs))
     :refers   (reduce (fn [m {:keys [refers]}] (merge m refers)) {} specs)
     :requires (into #{} (map :ns) specs)}))

(defn env-from-zipper
  "Build a resolution environment from the file's zipper root."
  [zroot]
  (let [ns-node (ns-form-node zroot)
        ns-sexpr (when ns-node (as-form ns-node))]
    (if (and ns-sexpr (seq? ns-sexpr) (= 'ns (first ns-sexpr)))
      (assoc (env-from-ns ns-sexpr) :ns-node ns-node)
      {:aliases {} :refers {} :requires #{} :ns-node ns-node})))

;; ---------------------------------------------------------------------------
;; symbol resolution
;; ---------------------------------------------------------------------------

(defn resolve-sym
  "Resolve a code `sym` to its canonical `\"namespace/name\"` string using `env`,
  or nil when it cannot be resolved to a required namespace.

    rf/subscribe                (alias rf -> re-frame.core)  -> \"re-frame.core/subscribe\"
    re-frame.core/subscribe     (fully qualified)            -> \"re-frame.core/subscribe\"
    subscribe                   (:refer'd)                    -> \"re-frame.core/subscribe\"

  A bare symbol that was neither referred nor renamed resolves to nil (it is a
  local, a core form, or an unrelated var - the caller decides what nil means)."
  [env sym]
  (when (symbol? sym)
    (let [nsp (namespace sym)
          nm  (name sym)]
      (cond
        ;; qualified: resolve the namespace-part through aliases, else take it
        ;; verbatim (already a full namespace).
        nsp
        (let [nsym (symbol nsp)
              real (get (:aliases env) nsym nsym)]
          (str real "/" nm))

        ;; bare: referred / renamed?
        :else
        (when-let [q (get (:refers env) sym)]
          (str q))))))

(defn resolves-to?
  "Does `sym` resolve (via `env`) to any of the given canonical `\"ns/name\"`
  strings in the `targets` set?"
  [env sym targets]
  (boolean (some-> (resolve-sym env sym) targets)))

(defn required?
  "Is namespace `ns-str` required by the file (any prefix match on requires)?"
  [env ns-str]
  (contains? (into #{} (map str) (:requires env)) ns-str))

(defn simple-name
  "The post-`/` simple name of a possibly-qualified symbol, as a string."
  [sym]
  (when (symbol? sym) (name sym)))
