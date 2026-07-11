(ns re-frame.ui.parity-html
  "Parity-corpus HARNESS: parse react-dom/server markup into the SAME
  semantic-node space normalization N produces (the spike comparator is
  the reference; jvm-tree contract §Normalization). Test scope only —
  never a product surface.

  The parser is deliberately STRICT and lean: it accepts exactly the
  regular markup `renderToStaticMarkup` emits (double-quoted attribute
  values, `<tag/>` self-closing, `<!-- -->` comments dropped) and fails
  loud on anything else. Semantic projection applied here so the two
  sides meet in one space:

    - entity decoding in text + attr values (comparison is DECODED);
    - adjacent text coalescing; empty strings dropped;
    - `style` attr parsed into an order-insensitive {prop value} map;
    - namespace context derived positionally via the SAME table rows
      (`rules/child-ns-context`) N's trees carry structurally;
    - void/self-closing normalized (no children);
    - custom-element property-classified attrs DROPPED: React's own SSR
      serialises property-props (the recorded S1b row-15 divergence);
      the contract space omits them, so the harness strips what the
      future JVM serialiser will never write;
    - `on*` attribute names are a HARD FAILURE — event data must never
      serialise into HTML (conversion table §Children/escaping row);
    - a single leading newline inside `textarea`/`pre` is dropped (the
      HTML-spec serialisation artifact React emits deliberately).

  `expand-trusted` parses `{:html s}` leaves of an N-side semantic
  vector through THIS parser so tree-vs-markup comparison is symmetric
  (the contract's compared-verbatim rule applies tree<->tree; a
  markup comparison necessarily parses both sides)."
  (:require [clojure.string :as str]
            [re-frame.ui.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Entities
;; ---------------------------------------------------------------------------

(def ^:private named-entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " "})

(defn decode-entities
  "Decode the escape vocabulary React emits (named + numeric refs);
  lenient on bare ampersands (trusted-HTML content may carry them)."
  [s]
  (if-not (str/includes? s "&")
    s
    (let [sb #?(:clj (StringBuilder.) :cljs (atom ""))
          append! #?(:clj (fn [x] (.append sb (str x)))
                     :cljs (fn [x] (swap! sb str x)))
          n (count s)]
      (loop [i 0]
        (if (>= i n)
          #?(:clj (.toString sb) :cljs @sb)
          (let [c (nth s i)]
            (if (not= c \&)
              (do (append! c) (recur (inc i)))
              (let [semi (str/index-of s ";" i)
                    body (when (and semi (< semi (+ i 12)))
                           (subs s (inc i) semi))]
                (cond
                  (nil? body)
                  (do (append! c) (recur (inc i)))

                  (and (str/starts-with? body "#x")
                       (re-matches #"#x[0-9a-fA-F]+" body))
                  (do (append! (char #?(:clj (Integer/parseInt (subs body 2) 16)
                                        :cljs (js/parseInt (subs body 2) 16))))
                      (recur (inc semi)))

                  (re-matches #"#[0-9]+" body)
                  (do (append! (char #?(:clj (Integer/parseInt (subs body 1))
                                        :cljs (js/parseInt (subs body 1) 10))))
                      (recur (inc semi)))

                  (contains? named-entities body)
                  (do (append! (get named-entities body))
                      (recur (inc semi)))

                  :else
                  (do (append! c) (recur (inc i))))))))))))

;; ---------------------------------------------------------------------------
;; Tokenizer/parser
;; ---------------------------------------------------------------------------

(defn- fail! [msg near]
  (throw (ex-info (str "parity-html parse failure: " msg
                       " near " (pr-str near))
                  {:parity-html/error msg})))

(defn- char-code [c]
  ;; CLJS characters are one-char strings — (int c) is NOT a char code
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(def ^:private name-char?
  (fn [c]
    (let [i (char-code c)]
      (or (and (>= i 97) (<= i 122))
          (and (>= i 65) (<= i 90))
          (and (>= i 48) (<= i 57))
          (= c \-) (= c \:) (= c \_)))))

(defn- read-name [s i]
  (loop [j i]
    (if (and (< j (count s)) (name-char? (nth s j)))
      (recur (inc j))
      [(subs s i j) j])))

(defn- skip-ws [s i]
  (loop [j i]
    (if (and (< j (count s))
             (contains? #{\space \tab \newline \return} (nth s j)))
      (recur (inc j))
      j)))

(defn- read-attrs
  "-> [attrs self-closing? next-i] from just after the tag name."
  [s i]
  (loop [i (skip-ws s i) attrs {}]
    (let [c (nth s i)]
      (cond
        (= c \>) [attrs false (inc i)]
        (and (= c \/) (= (nth s (inc i)) \>)) [attrs true (+ i 2)]
        :else
        (let [[an j] (read-name s i)]
          (when (= an "") (fail! "expected attribute name" (subs s i (min (count s) (+ i 30)))))
          (if (= (nth s j) \=)
            (do (when (not= (nth s (inc j)) \")
                  (fail! "expected double-quoted attribute value" an))
                (let [q (str/index-of s "\"" (+ j 2))]
                  (recur (skip-ws s (inc q))
                         (assoc attrs an (decode-entities (subs s (+ j 2) q))))))
            ;; bare presence attribute (React emits attr="" but accept bare)
            (recur (skip-ws s j) (assoc attrs an ""))))))))

(defn parse
  "Markup string -> vector of raw nodes
  `{:tag kw :attrs {name-str val-str} :children [...]}` / text strings."
  [html]
  (let [n (count html)]
    (loop [i 0
           ;; stack of [tag attrs children]
           stack [[nil nil []]]]
      (if (>= i n)
        (let [[t _ children] (peek stack)]
          (when t (fail! (str "unclosed element " t) ""))
          children)
        (let [c (nth html i)]
          (if (not= c \<)
            (let [lt   (or (str/index-of html "<" i) n)
                  text (decode-entities (subs html i lt))
                  [t a chs] (peek stack)]
              (recur lt (conj (pop stack) [t a (conj chs text)])))
            (cond
              (str/starts-with? (subs html i (min n (+ i 4))) "<!--")
              (let [e (str/index-of html "-->" i)]
                (when-not e (fail! "unterminated comment" (subs html i)))
                (recur (+ e 3) stack))

              (= (nth html (inc i)) \/)
              (let [[nm j] (read-name html (+ i 2))]
                (when (not= (nth html j) \>) (fail! "malformed close tag" nm))
                (let [[t a chs] (peek stack)]
                  (when (not= t nm) (fail! (str "close " nm " but open " t) nm))
                  (let [stack (pop stack)
                        [pt pa pchs] (peek stack)]
                    (recur (inc j)
                           (conj (pop stack)
                                 [pt pa (conj pchs {:tag (keyword t)
                                                    :attrs a
                                                    :children chs})])))))

              :else
              (let [[nm j] (read-name html (inc i))]
                (when (= nm "") (fail! "expected tag name" (subs html i (min n (+ i 30)))))
                (let [[attrs self? k] (read-attrs html j)
                      void? (contains? rules/void-tags (keyword (str/lower-case nm)))]
                  (if (or self? void?)
                    (let [[t a chs] (peek stack)]
                      (recur k (conj (pop stack)
                                     [t a (conj chs {:tag (keyword nm)
                                                     :attrs attrs
                                                     :children []})])))
                    (recur k (conj stack [nm attrs []]))))))))))))

;; ---------------------------------------------------------------------------
;; Semantic projection
;; ---------------------------------------------------------------------------

(defn- parse-style [s]
  (into {}
        (keep (fn [decl]
                (let [decl (str/trim decl)]
                  (when (seq decl)
                    (let [ci (str/index-of decl ":")]
                      (when-not ci (fail! "malformed style declaration" decl))
                      [(str/trim (subs decl 0 ci))
                       (str/trim (subs decl (inc ci)))])))))
        (str/split s #";")))

(defn- custom-property-attr-names [tag]
  (into #{}
        (map (comp rules/custom-element-property-name name))
        (rules/custom-element-properties tag)))

(defn- coalesce [children]
  (let [vs (reduce (fn [acc v]
                     (if (and (string? v) (string? (peek acc)))
                       (conj (pop acc) (str (peek acc) v))
                       (conj acc v)))
                   [] children)]
    (into [] (remove #(and (string? %) (= "" %))) vs)))

(defn- resource-hint?
  "react-dom 19 HOISTS resource hints (`<link rel=\"preload\">` for an
  `img` src, etc.) into static-markup output. They are React-inserted
  render furniture, not authored content — no structural-tree consumer
  will ever emit them (an authored [:link ...] carries other rel
  values) — so the harness drops them before comparison."
  [raw]
  (and (map? raw)
       (= :link (:tag raw))
       (contains? #{"preload" "preconnect" "dns-prefetch" "modulepreload"}
                  (get-in raw [:attrs "rel"]))))

(declare raw->semantic)

(defn- raws->semantic [raws ctx]
  (coalesce (into []
                  (comp (remove resource-hint?)
                        (map #(if (string? %) % (raw->semantic % ctx))))
                  raws)))

(defn- raw->semantic [{:keys [tag attrs children]} ctx]
  (let [tag-name (name tag)
        _        (doseq [[an _] attrs]
                   (when (re-matches #"on[A-Z].*|on[a-z]+" an)
                     (fail! (str "handler attribute " an " serialised into "
                                 "HTML — event data must never reach markup")
                            tag)))
        drop     (custom-property-attr-names tag)
        attrs'   (reduce-kv
                  (fn [m an av]
                    (cond
                      (contains? drop an) m
                      (= an "style") (let [sm (parse-style av)]
                                       (if (seq sm) (assoc m an sm) m))
                      :else (assoc m an av)))
                  {} attrs)
        el-ns    (rules/element-ns
                  (case tag :svg :svg :math :mathml ctx))
        child-ctx (rules/child-ns-context ctx tag {:encoding (get attrs "encoding")})
        chs      (raws->semantic children child-ctx)
        ;; the HTML-spec leading-newline artifact React writes for
        ;; textarea/pre — semantic space drops it
        chs      (if (and (contains? #{:textarea :pre} tag)
                          (string? (first chs)))
                   (coalesce (into [(str/replace-first (first chs) #"^\n" "")]
                                   (rest chs)))
                   chs)]
    (cond-> {:tag tag}
      el-ns      (assoc :ns el-ns)
      (seq attrs') (assoc :attrs attrs')
      (seq chs)  (assoc :children chs))))

(defn html->semantic
  "react-dom/server markup -> the semantic-node vector (N's space)."
  [html]
  (raws->semantic (parse html) nil))

;; ---------------------------------------------------------------------------
;; Trusted-HTML expansion (N-side symmetry)
;; ---------------------------------------------------------------------------

(defn expand-trusted
  "Expand `{:html s}` leaves of an N-side semantic vector through the
  parser, in the ambient ns context, re-coalescing siblings."
  ([sem-vec] (expand-trusted sem-vec nil))
  ([sem-vec ctx]
   (coalesce
    (into []
          (mapcat (fn [node]
                    (cond
                      (string? node) [node]
                      (contains? node :html) (raws->semantic (parse (:html node)) ctx)
                      :else
                      (let [child-ctx (rules/child-ns-context
                                       ctx (:tag node)
                                       {:encoding (get-in node [:attrs "encoding"])})
                            chs (expand-trusted (or (:children node) []) child-ctx)]
                        [(if (seq chs)
                           (assoc node :children chs)
                           (dissoc node :children))]))))
          sem-vec))))

;; ---------------------------------------------------------------------------
;; Divergence reporting (failure ergonomics)
;; ---------------------------------------------------------------------------

(defn first-divergence
  "Depth-first path to the first structural difference between two
  semantic vectors — nil when equal. Returns {:path [...] :jvm x :react y}."
  ([a b] (first-divergence a b []))
  ([a b path]
   (cond
     (= a b) nil

     (and (vector? a) (vector? b))
     (or (some identity
               (map (fn [i x y] (first-divergence x y (conj path i)))
                    (range) a b))
         {:path path :jvm (drop (count b) a) :react (drop (count a) b)
          :note "child-count mismatch"})

     (and (map? a) (map? b))
     (or (some (fn [k]
                 (when (not= (get a k) (get b k))
                   (if (or (map? (get a k)) (vector? (get a k)))
                     (first-divergence (get a k) (get b k) (conj path k))
                     {:path (conj path k) :jvm (get a k) :react (get b k)})))
               (into (sorted-set-by (fn [x y] (compare (pr-str x) (pr-str y))))
                     (concat (keys a) (keys b))))
         {:path path :jvm a :react b})

     :else {:path path :jvm a :react b})))
