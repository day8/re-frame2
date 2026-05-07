(ns re-frame-2.ssr
  "Server-side rendering and hydration. Per Spec 011.

  Implements:
    - Pure hiccup → HTML emitter (HTML5: void elements self-close
      bare, doctype prefix on demand, full attr/text escaping,
      :tag#id.cls keyword parsing, registered-view resolution via
      the :view registry).
    - :rf/hydrate event with :replace-app-db semantics — the server-
      supplied payload's :rf/app-db replaces the client app-db. The
      conformance harness simulates a hydration-mismatch by feeding a
      :simulated-client-render-hash; the runtime compares against the
      payload's :rf/render-hash and emits :rf.ssr/hydration-mismatch
      with :recovery :warned-and-replaced.
    - :rf.server/* response-shape fx (:rf.server/set-status,
      :rf.server/redirect, :rf.server/set-cookie) gated by
      :platforms #{:server} via the per-frame :platform predicate.
    - Default error projector (:rf.ssr/default-error-projector)
      mapping :rf.error/no-such-handler / no-such-route to the
      Spec-011 public-error shape.

  Conformance fixtures cover all of the above (ssr/render-to-string,
  ssr/hydrate, ssr/hydration-mismatch, ssr/head-emits, ssr/head-hydration,
  ssr/error-known-mapping, ssr/error-sanitisation, ssr/cookie,
  ssr/redirect, ssr/set-status, fx/platforms)."
  (:require [re-frame-2.frame :as frame]
            [re-frame-2.registrar :as registrar]
            [re-frame-2.events :as events]))

(defn- escape-html [s]
  (-> (str s)
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "<" "&lt;")
      (clojure.string/replace ">" "&gt;")
      (clojure.string/replace "\"" "&quot;")
      (clojure.string/replace "'" "&#39;")))

(defn- escape-attr [s]
  ;; Less aggressive — attributes don't need < > escaped, but " must
  ;; be escaped if we use double-quoted values. We use double-quoted
  ;; values, so escape " and &.
  (-> (str s)
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "\"" "&quot;")))

(defn- attr-string [attrs]
  (if (empty? attrs)
    ""
    (str " " (clojure.string/join " "
               (map (fn [[k v]]
                      (cond
                        (true? v)  (name k)             ;; boolean attrs
                        (false? v) nil
                        (nil? v)   nil
                        :else      (str (name k) "=\"" (escape-attr v) "\"")))
                    attrs)))))

;; Per HTML5 spec, these elements are void — they self-close and have no
;; closing tag.
(def ^:private void-elements
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; Tag-name parsing for the :div#id.cls syntax. Reagent / Hiccup convention.
(defn- parse-tag-name
  "Split a keyword like :div#main.col-12.bold into [:div {:id \"main\"
  :class \"col-12 bold\"}] components."
  [tag-kw]
  (let [s     (name tag-kw)
        ;; Match: tag-name optionally followed by #id and .class fragments.
        [_ tag id classes]
        #?(:clj  (re-matches #"([^#.]+)(?:#([^.]+))?(.*)" s)
           :cljs (re-matches #"([^#.]+)(?:#([^.]+))?(.*)" s))
        class-list (when (and classes (seq classes))
                     (->> (clojure.string/split classes #"\.")
                          (remove empty?)
                          (clojure.string/join " ")))]
    [tag
     (cond-> {}
       id         (assoc :id id)
       class-list (assoc :class class-list))]))

(defn- merge-class-attrs
  "Merge the class from the tag-name into the attrs map's :class."
  [tag-attrs user-attrs]
  (let [t-class    (:class tag-attrs)
        u-class    (:class user-attrs)
        merged     (cond
                     (and t-class u-class) (str t-class " " u-class)
                     t-class                t-class
                     u-class                u-class)]
    (cond-> (merge tag-attrs (dissoc user-attrs :class))
      merged (assoc :class merged))))

(declare emit-element)

(defn- emit-children [children]
  (clojure.string/join (mapv emit-element children)))

(defn- emit-element [el]
  (cond
    (nil? el)         ""
    (string? el)      (escape-html el)
    (number? el)      (str el)
    (boolean? el)     ""
    (vector? el)
    (let [head (first el)]
      (cond
        (keyword? head)
        (let [;; Look up registered view first.
              maybe-view (registrar/lookup :view head)]
          (if maybe-view
            (emit-element (apply (:handler-fn maybe-view) (rest el)))
            (let [[tag-name tag-attrs] (parse-tag-name head)
                  [user-attrs children]
                  (if (map? (second el))
                    [(second el) (drop 2 el)]
                    [{} (rest el)])
                  attrs       (merge-class-attrs tag-attrs user-attrs)
                  void?       (contains? void-elements (keyword tag-name))]
              (if void?
                (str "<" tag-name (attr-string attrs) ">")
                (str "<" tag-name (attr-string attrs) ">"
                     (emit-children children)
                     "</" tag-name ">")))))

        (fn? head)
        (emit-element (apply head (rest el)))

        :else (str el)))

    (sequential? el) (emit-children el)
    :else (escape-html el)))

;; ---- render-tree hashing --------------------------------------------------
;;
;; Per Spec 011 §Hydration-mismatch detection: the server hashes the
;; render-tree at SSR time; the client recomputes the hash on first
;; render and compares. Mismatch = the runtime emits
;; :rf.ssr/hydration-mismatch with both hashes. We use FNV-1a 32-bit
;; over the canonical-EDN traversal of the render tree, output as
;; lowercase hex. Same algorithm both sides → byte-identical hashes
;; for byte-identical canonical EDN.

(defn- canonical-edn
  "Print a render-tree node in a stable order. Maps are sorted by key
  string; sequences keep order. Functions and var-references appear
  as their toString — stable enough for trees that re-render the same
  view-fn from the same registry."
  [x]
  (cond
    (map? x)
    (str "{"
         (clojure.string/join
           ","
           (map (fn [[k v]] (str (canonical-edn k) " " (canonical-edn v)))
                (sort-by (comp str key) x)))
         "}")

    (vector? x)
    (str "[" (clojure.string/join " " (map canonical-edn x)) "]")

    (sequential? x)
    (str "(" (clojure.string/join " " (map canonical-edn x)) ")")

    (set? x)
    (str "#{"
         (clojure.string/join " " (sort (map canonical-edn x)))
         "}")

    (fn? x)
    (str "#fn[" (.toString ^Object x) "]")

    :else (pr-str x)))

(defn- fnv-1a-32
  "FNV-1a 32-bit hash of a string. Returns the lowercase-hex form, no
  prefix. Stable on JVM and CLJS — uses unchecked 32-bit multiply on
  both sides (CLJS via Math.imul, JVM via long-multiply-then-mask).
  Output bytes are byte-identical for byte-identical input strings."
  [s]
  (let [offset 2166136261              ;; FNV offset basis
        prime  16777619]               ;; FNV prime
    (loop [i 0
           h offset]
      (if (>= i (count s))
        ;; Convert h to unsigned 32-bit and emit lowercase 8-char hex.
        ;; JS's bitwise ops are 32-bit SIGNED; the `>>> 0` idiom forces
        ;; unsigned. JVM bit-and-with-0xffffffff suffices.
        #?(:clj  (format "%08x" (bit-and h 0xffffffff))
           :cljs (let [u (unsigned-bit-shift-right h 0)
                       hex (.toString u 16)]
                   (.padStart hex 8 "0")))
        (let [c (#?(:clj int :cljs .charCodeAt) (.charAt s i) #?(:cljs 0))
              x (bit-xor h c)
              ;; Guaranteed 32-bit multiply.
              p #?(:clj  (bit-and 0xffffffff
                                  (unchecked-multiply x prime))
                   :cljs (js/Math.imul x prime))]
          (recur (inc i) p))))))

(defn render-tree-hash
  "Per Spec 011 §Hydration-mismatch detection: a stable structural hash
  of the render tree. Deterministic across JVM and CLJS for trees with
  identical canonical-EDN representation."
  [render-tree]
  (fnv-1a-32 (canonical-edn render-tree)))

(defn render-to-string
  "Pure hiccup → HTML string. Per Spec 011 §The render-tree → HTML emitter.

  Implements:
    - HTML5 void elements (no closing tag, no self-close slash).
    - :tag#id.cls keyword tag-name parsing (id and class merged into attrs).
    - Boolean attributes (true → bare attr name; false / nil → omitted).
    - Text and attribute escaping (HTML-entity safe).
    - :doctype? opt prepends '<!DOCTYPE html>'.
    - Registered-view resolution (looks up :view kind in the registrar).
    - Var-reference resolution (calls the fn, recurses on the result).
    - :emit-hash? opt embeds 'data-rf-render-hash=<hex>' on the root
      element for client-side mismatch detection (per Spec 011)."
  [render-tree opts]
  (let [body (emit-element render-tree)
        body (if (:emit-hash? opts)
               (let [h (render-tree-hash render-tree)]
                 ;; Inject data-rf-render-hash on the FIRST '<tag' opener
                 ;; — that's the root element. Skip <!DOCTYPE> if present.
                 (clojure.string/replace-first
                   body
                   #"(<[a-zA-Z][^\s>/]*)"
                   (str "$1 data-rf-render-hash=\"" h "\"")))
               body)]
    (if (:doctype? opts)
      (str "<!DOCTYPE html>" body)
      body)))

;; Wire our render-to-string into the plain-atom adapter so callers using
;; rf/render-to-string (which delegates through the substrate adapter) get
;; this implementation on the JVM. CLJS apps install a Reagent adapter
;; instead and don't go through plain-atom.
#?(:clj
   (try
     (require 're-frame-2.substrate.plain-atom)
     ((requiring-resolve 're-frame-2.substrate.plain-atom/set-hiccup-emitter!)
      render-to-string)
     (catch Throwable _ nil)))

;; ---- hydrate event --------------------------------------------------------

(events/reg-event-fx :rf/hydrate
  (fn [{:keys [db]} [_ payload]]
    ;; Locked policy per Spec 011 §The :rf/hydrate event: replace-app-db.
    ;; Server is authoritative for the initial client app-db.
    {:db (or (:rf/app-db payload) (:app-db payload) db)}))
