(ns re-frame-2.ssr
  "Server-side rendering and hydration. Per Spec 011.

  This is a STUB first pass. The pure hiccup → HTML emitter, the
  hydration payload protocol, the head/meta contract, the structural
  hash mismatch detection, the server error projection — all are
  filed as beads.

  Currently implemented:
    - render-to-string-stub: a minimal hiccup → string walker (no
      attribute escaping, no void-element handling, no doctype).

  TODO (filed as beads):
    - Pure hiccup → HTML emitter with attr/text escaping, void elements.
    - :rf/hydrate event with :replace-app-db semantics.
    - hydration-mismatch detection (FNV-1a hash of canonical-EDN).
    - :rf/head model + head-mismatch detection.
    - :rf.server/* response-shape fx (set-status, set-cookie, redirect).
    - error projection (server boundary sanitisation)."
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

(defn- attr-string [attrs]
  (if (empty? attrs)
    ""
    (str " " (clojure.string/join " "
               (map (fn [[k v]]
                      (str (name k) "=\"" (escape-html v) "\""))
                    attrs)))))

(declare emit-element)

(defn- emit-children [children]
  (clojure.string/join (mapv emit-element children)))

(defn- emit-element [el]
  (cond
    (nil? el)         ""
    (string? el)      (escape-html el)
    (number? el)      (str el)
    (vector? el)
    (let [head (first el)]
      (cond
        (keyword? head)
        (let [tag-name (name head)
              ;; Look up registered view (per Spec 004 §How registered views
              ;; are used in hiccup).
              maybe-view (registrar/lookup :view head)]
          (if maybe-view
            (emit-element (apply (:handler-fn maybe-view) (rest el)))
            (let [[attrs children]
                  (if (map? (second el))
                    [(second el) (drop 2 el)]
                    [{} (rest el)])]
              (str "<" tag-name (attr-string attrs) ">"
                   (emit-children children)
                   "</" tag-name ">"))))

        (fn? head)
        ;; Var-reference form: invoke and recurse.
        (emit-element (apply head (rest el)))

        :else (str el)))

    (sequential? el) (emit-children el)
    :else (escape-html el)))

(defn render-to-string
  "First-pass pure hiccup → HTML string walker. Will be replaced by a
  proper emitter (per Spec 011 §The render-tree → HTML emitter) — this
  stub does NOT yet handle:
    - HTML5 void elements (<br>, <img>, etc.)
    - Class-list / style-map attribute interpolation
    - Hash-tag attribute syntax (`:div#id.cls`)
    - The doctype prefix
    - Custom-tag handling (web components)"
  [render-tree _opts]
  (emit-element render-tree))

;; Bind into the substrate adapter dynamic var so plain-atom adapter's
;; render-to-string can dispatch through us.
(try
  (when-let [pa-ns #?(:clj (the-ns 're-frame-2.substrate.plain-atom)
                      :cljs nil)]
    (alter-var-root #?(:clj (resolve 're-frame-2.substrate.plain-atom/*hiccup-emitter*)
                       :cljs nil)
                    (constantly render-to-string)))
  (catch #?(:clj Throwable :cljs :default) _ nil))

;; ---- hydrate event --------------------------------------------------------

(events/reg-event-fx :rf/hydrate
  (fn [{:keys [db]} [_ payload]]
    ;; Locked policy per Spec 011 §The :rf/hydrate event: replace-app-db.
    ;; Server is authoritative for the initial client app-db.
    {:db (or (:rf/app-db payload) (:app-db payload) db)}))
