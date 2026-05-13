(ns re-frame.ssr.head.emit
  "Canonical-order head-model → HTML fragment emitter. Per Spec 011
  §Default flow step 4 and the rf2-x7g10 split of `re-frame.ssr.head`.

  The emit half of the head/meta contract — pure functions that turn a
  `:rf/head-model` map into the inner-head HTML fragment in canonical
  order: `<title>` first, then `<meta>` (declaration order), then
  `<link>`, then `<script>`, then JSON-LD
  `<script type=\"application/ld+json\">`.

  Public surface:

    `head-model->html` — render a `:rf/head-model` map to its inner-head
                         HTML fragment. The 2-arity form with
                         `{:wrap? true}` wraps in `<head>…</head>`.

  The other surfaces of the head/meta contract — `reg-head`,
  `render-head`, `active-head`, `default-head`, the per-frame snapshot
  bookkeeping, and the late-bind hook registrations — live in the
  `re-frame.ssr.head` façade."
  (:require [clojure.string :as str]
            [re-frame.ssr.html-helpers :as html]))

;; ---- per-element emitters -------------------------------------------------

(defn- emit-title [title]
  (when (and title (not= "" title))
    (str "<title>" (html/escape-html title) "</title>")))

(defn- emit-meta-tag [m]
  (str "<meta" (html/attr-string m) ">"))

(defn- emit-link-tag [m]
  (str "<link" (html/attr-string m) ">"))

(defn- emit-script-tag [m]
  (let [{:keys [src async defer type integrity crossorigin nomodule]} m
        attrs (cond-> {}
                src         (assoc :src src)
                type        (assoc :type type)
                async       (assoc :async async)
                defer       (assoc :defer defer)
                integrity   (assoc :integrity integrity)
                crossorigin (assoc :crossorigin crossorigin)
                nomodule    (assoc :nomodule nomodule))]
    (str "<script"
         (html/attr-string (merge attrs
                                  (dissoc m :src :async :defer :type
                                          :integrity :crossorigin :nomodule)))
         "></script>")))

(defn- ld-json-string
  "Serialise a JSON-LD object map to its `<script type=\"application/ld+json\">`
  body. CLJ uses a minimal printer (works for the spec's canonical shape:
  strings, numbers, booleans, vectors, maps with string and/or keyword
  keys). CLJS uses `js/JSON.stringify` via `clj->js`.

  On the JVM side, keyword keys and keyword values are both serialised
  to their fully-qualified name — `:my.app/foo` → `\"my.app/foo\"` — so
  namespaces survive serialisation. The key and value handling are
  symmetric; rf2-a50nz fixed an earlier asymmetry that quietly stripped
  the namespace prefix off keys."
  [x]
  #?(:cljs (js/JSON.stringify (clj->js x))
     :clj  (letfn [(emit [v]
                     (cond
                       (nil? v)     "null"
                       (boolean? v) (if v "true" "false")
                       (number? v)  (str v)
                       (string? v)  (str "\""
                                         (-> v
                                             (str/replace "\\" "\\\\")
                                             (str/replace "\"" "\\\""))
                                         "\"")
                       (keyword? v) (emit (if-let [ns (namespace v)]
                                            (str ns "/" (name v))
                                            (name v)))
                       ;; Keyword keys delegate to the keyword branch above
                       ;; so namespaces are preserved — fixes the asymmetric
                       ;; key/value handling reported in rf2-a50nz.
                       (map? v)     (str "{"
                                         (str/join "," (map (fn [[k val]]
                                                              (str (emit k)
                                                                   ":"
                                                                   (emit val)))
                                                            v))
                                         "}")
                       (sequential? v) (str "[" (str/join "," (map emit v)) "]")
                       :else (emit (str v))))]
             (emit x))))

(defn- emit-json-ld [m]
  (str "<script type=\"application/ld+json\">"
       (ld-json-string m)
       "</script>"))

;; ---- head-model->html -----------------------------------------------------

(defn head-model->html
  "Render a `:rf/head-model` map to its inner-head HTML fragment in
  canonical order — `<title>`, `<meta>` (declaration order), `<link>`,
  `<script>`, JSON-LD `<script type=\"application/ld+json\">`. The two
  attribute-bag keys (`:html-attrs`, `:body-attrs`) are NOT emitted by
  this fn — they're consumed by host shells that stamp them onto
  `<html>` / `<body>`.

  The 1-arity form returns the inner fragment (no surrounding `<head>`
  tags); the 2-arity form with `{:wrap? true}` wraps in `<head>…</head>`.

  Per Spec 011 §Default flow step 4."
  ([head-model] (head-model->html head-model {}))
  ([head-model {:keys [wrap?]}]
   (let [{:keys [title meta link script json-ld]} head-model
         parts (cond-> []
                 (and title (not= "" title))
                 (conj (emit-title title))

                 (seq meta)
                 (into (map emit-meta-tag) meta)

                 (seq link)
                 (into (map emit-link-tag) link)

                 (seq script)
                 (into (map emit-script-tag) script)

                 (seq json-ld)
                 (into (map emit-json-ld) json-ld))
         inner (apply str parts)]
     (if wrap?
       (str "<head>" inner "</head>")
       inner))))
