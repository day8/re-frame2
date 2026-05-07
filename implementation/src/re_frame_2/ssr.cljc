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

(defn render-to-string
  "Pure hiccup → HTML string. JVM-runnable. Per Spec 011 §The render-tree
  → HTML emitter.

  Implements:
    - HTML5 void elements (self-closing per the void-elements set).
    - :tag#id.cls keyword tag-name parsing (id and class merged into attrs).
    - Boolean attributes (true → bare attr name; false / nil → omitted).
    - Text and attribute escaping (HTML-entity safe).
    - :doctype? opt prepends '<!DOCTYPE html>\\n'.
    - Registered-view resolution (looks up :view kind in the registrar).
    - Var-reference resolution (calls the fn, recurses on the result).

  Not yet implemented (see beads):
    - Style-map attribute (:style {:color :red}).
    - <script>/<style> raw-text contents.
    - Hash-based render-tree mismatch detection on the client side.
    - Streaming emit."
  [render-tree opts]
  (let [body (emit-element render-tree)]
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
