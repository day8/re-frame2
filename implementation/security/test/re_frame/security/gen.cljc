(ns re-frame.security.gen
  "Seeded generators for security properties; draws reproduce from seed and index."
  (:refer-clojure :exclude [rand-nth])
  (:require [clojure.walk :as walk]))

#?(:clj (def ^:private mask32 0xFFFFFFFF))

(def ^:private lcg-mult 1103515245)
(def ^:private lcg-inc 12345)

(defn make-rng
  "Make an unsigned 32-bit state from a seed."
  [seed]
  #?(:clj  (bit-and (long seed) mask32)
     :cljs (unsigned-bit-shift-right (int seed) 0)))

(defn- next-state
  "Use exact low-32 multiplication so JVM and CLJS draws agree."
  [current-state]
  #?(:clj  (bit-and (unchecked-add (unchecked-multiply (long current-state) lcg-mult) lcg-inc)
                    mask32)
     :cljs (unsigned-bit-shift-right (+ (js/Math.imul current-state lcg-mult) lcg-inc) 0)))

(defn next-int
  "Draw an integer in [0, bound), with positive bound."
  [rng bound]
  (let [next-state-value (next-state rng)
        high-bits (bit-and (bit-shift-right next-state-value 8) 0x7FFFFF)]
    [(mod high-bits bound) next-state-value]))

(defn rand-nth
  "Draw an element and return the advanced state."
  [rng coll]
  (let [collection-values (vec coll)
        [element-index next-rng] (next-int rng (count collection-values))]
    [(nth collection-values element-index) next-rng]))

(defn gen-elem
  "Generate an element of coll."
  [coll]
  (fn [rng] (rand-nth rng coll)))

(defn gen-int
  "Generate an integer in [lo, hi)."
  [lo hi]
  (fn [rng]
    (let [[drawn-offset next-rng] (next-int rng (- hi lo))]
      [(+ lo drawn-offset) next-rng])))

(defn gen-one-of
  "Select a generator, then draw from it."
  [& gens]
  (fn [rng]
    (let [[selected-generator next-rng] (rand-nth rng (vec gens))]
      (selected-generator next-rng))))

(defn gen-fmap
  "Map f over a generated value."
  [f g]
  (fn [rng]
    (let [[drawn-value next-rng] (g rng)]
      [(f drawn-value) next-rng])))

(defn gen-vec
  "Draw a vector; n-or-gen is a fixed length or length generator."
  [n-or-gen g]
  (fn [rng]
    (let [[draw-count initial-rng] (if (fn? n-or-gen) (n-or-gen rng) [n-or-gen rng])]
      (loop [draw-index 0, current-rng initial-rng, accumulated-values []]
        (if (< draw-index draw-count)
          (let [[drawn-value next-rng] (g current-rng)]
            (recur (inc draw-index) next-rng (conj accumulated-values drawn-value)))
          [accumulated-values current-rng])))))

(defn sample
  "Draw n values from an explicit seed."
  [g n seed]
  (loop [draw-index 0, current-rng (make-rng seed), accumulated-values []]
    (if (< draw-index n)
      (let [[drawn-value next-rng] (g current-rng)]
        (recur (inc draw-index) next-rng (conj accumulated-values drawn-value)))
      accumulated-values)))

(defn for-all
  "Return the first failing draw with its seed/index, or nil. Throws count as failures."
  [g n seed pred]
  (loop [draw-index 0, current-rng (make-rng seed)]
    (if (< draw-index n)
      (let [[drawn-value next-rng] (g current-rng)
            [ok? thrown-exception] (try [(boolean (pred drawn-value)) nil]
                             (catch #?(:clj Throwable :cljs :default) e
                               [false e]))]
        (if ok?
          (recur (inc draw-index) next-rng)
          (cond-> {:fail drawn-value :index draw-index :seed seed}
            thrown-exception (assoc :threw thrown-exception))))
      nil)))

(defn contains-string?
  "Scan string leaves and rendered data for a regex-safe literal needle."
  [x needle]
  (let [hit (volatile! false)]
    (walk/postwalk
      (fn [node]
        (when (and (string? node)
                   (re-find (re-pattern needle) node))
          (vreset! hit true))
        node)
      x)
    (or @hit
        (boolean (re-find (re-pattern needle) (pr-str x))))))

(defn large-marker?
  "Recognise a structural large-elision marker."
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

(def ^:private gen-sensitive-key
  "Keys for generated map layers."
  (gen-elem [:auth :token :secret :pw :ssn :payload :inner :node :a :b]))

(defn- sensitive-shape
  "Wrap a sentinel-bearing invalid sensitive leaf in depth schema layers."
  [sentinel arms depth]
  (if (<= depth 0)
    (fn [rng]
      [[[:string {:sensitive? true}] [sentinel]] rng])
    (fn [rng]
      (let [[wrapper-arm next-rng] (rand-nth rng arms)
            [[inner-schema inner-value] after-inner-rng]
            ((sensitive-shape sentinel arms (dec depth)) next-rng)]
        (case wrapper-arm
          :map
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key inner-schema]] {generated-key inner-value}] after-key-rng])

          :vector
          [[[:vector inner-schema] [inner-value]] after-inner-rng]

          :sequential
          [[[:sequential inner-schema] [inner-value]] after-inner-rng]

          :map-of
          [[[:map-of :string inner-schema] {"k" inner-value}] after-inner-rng]

          ;; Malli reports a failing key verbatim in :in; key and leaf carry the sentinel.
          :map-of-key
          [[[:map-of [:string {:sensitive? true}] inner-schema] {sentinel inner-value}] after-inner-rng]

          :tuple
          [[[:tuple :int inner-schema] [0 inner-value]] after-inner-rng]

          ;; Malli reports the entire set element in :in.
          :set
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:set [:map [generated-key inner-schema]]] #{{generated-key inner-value}}] after-key-rng])

          ;; Transparent wrappers mark the ancestor, not the failing leaf.
          :and
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key {:sensitive? true} [:and inner-schema]]] {generated-key inner-value}] after-key-rng])

          :or
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key {:sensitive? true} [:or inner-schema]]] {generated-key inner-value}] after-key-rng])

          :multi
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key {:sensitive? true}
                     [:multi {:dispatch :rf2/d}
                      [:x [:map [:rf2/d :keyword] [:v inner-schema]]]]]]
              {generated-key {:rf2/d :x :v inner-value}}] after-key-rng])

          :orn
          (let [[generated-key after-key-rng] (gen-sensitive-key after-inner-rng)]
            [[[:map [generated-key {:sensitive? true} [:orn [:x inner-schema]]]] {generated-key inner-value}] after-key-rng]))))))

(defn nested-sensitive-generator
  "Generate [schema value] nesting; preserve caller arm order and depth for seed reproduction."
  [sentinel arms max-depth]
  (fn [rng]
    (let [[depth after-depth-rng] (next-int rng max-depth)]
      ((sensitive-shape sentinel arms (inc depth)) after-depth-rng))))
