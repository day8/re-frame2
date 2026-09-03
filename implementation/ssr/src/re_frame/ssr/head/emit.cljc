(ns re-frame.ssr.head.emit
  "Canonical-order head-model → HTML fragment emitter.

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
  ;; `clojure.string` and `re-frame.error` are JVM-only: every use is
  ;; inside `ld-json-string`'s `:clj` arm (the hand-rolled JSON emitter and
  ;; its two fail-fast gates). CLJS goes through `js/JSON.stringify`.
  (:require #?@(:clj [[clojure.string :as str]
                      [re-frame.error :as error]])
            [re-frame.ssr.html-helpers :as html]))

;; Reflection warnings guard the hand-rolled JVM JSON emitter.
;; The JVM-side `ld-json-string` is hand-rolled JSON emission; the
;; directive flags any accidental boxing introduced by future refactors.
;; CLJS has no reflection concept — the directive is JVM-only.
#?(:clj (set! *warn-on-reflection* true))

;; ---- per-element emitters -------------------------------------------------

(defn- emit-title [title]
  (when (and title (not= "" title))
    (str "<title>" (html/escape-html title) "</title>")))

(defn- emit-meta-tag [attrs]
  (str "<meta" (html/attr-string attrs) ">"))

(defn- emit-link-tag [attrs]
  (str "<link" (html/attr-string attrs) ">"))

(defn- emit-script-tag [attrs]
  ;; `attr-string` already iterates the whole map in declaration order;
  ;; pass `attrs` straight through.
  (str "<script" (html/attr-string attrs) "></script>"))

(defn- ld-json-string
  "Serialise a JSON-LD object map to its `<script type=\"application/ld+json\">`
  body. CLJ uses a minimal printer (works for the spec's canonical shape:
  strings, numbers, booleans, vectors, maps with string and/or keyword
  keys). CLJS uses `js/JSON.stringify` via `clj->js`.

  On the JVM side, keyword keys and keyword values are both serialised
  to their fully-qualified name — `:my.app/foo` → `\"my.app/foo\"` — so
  namespaces survive serialisation. Key and value handling are symmetric.

  Script-body safety:
  every `<` inside string contents is escaped as the JSON `\\u003c`
  Unicode escape via `html/escape-script-body-string`, so a string
  value containing `</script>` cannot close the surrounding
  `<script type=\"application/ld+json\">` envelope. The escape is
  applied at the string-literal boundary so structural `:` / `,` / `{`
  / `[` / quote chars are unaffected (they're not user-controlled
  data). JSON.parse on the client accepts `\\u003c` as a string-
  literal escape for `<`, so the payload round-trips unchanged."
  [value]
  #?(:cljs (-> (js/JSON.stringify (clj->js value))
               html/escape-script-body-string)
     :clj  (letfn [(emit-number [number]
                     ;; `(str v)` produces non-JSON for two
                     ;; numeric shapes the JVM admits but JSON.parse rejects:
                     ;;   • Ratios print as `1/3` — coerce to a double so the
                     ;;     wire form is `0.3333333333333333`.
                     ;;   • Non-finite doubles/floats (`##Inf` / `##-Inf` /
                     ;;     `##NaN`) have no JSON representation — fail-fast,
                     ;;     same posture as the tag-name / header-value gates.
                     (cond
                       (ratio? number) (str (double number))
                       (and (or (instance? Double number)
                                (instance? Float number))
                            (not (Double/isFinite (double number))))
                       (error/throw-error!
                         :rf.error/invalid-json-ld-number
                         'rf.ssr.head/emit
                         (str "JSON-LD number " (pr-str number)
                              " is non-finite — JSON has no"
                              " representation for ##Inf /"
                              " ##-Inf / ##NaN. Emit a finite number in"
                              " the JSON-LD head model.")
                         {:recovery :supply-a-finite-number
                          :extra    {:value number}})
                       :else (str number)))
                   (escape-json-control [^String s]
                     ;; JSON requires every control
                     ;; char (U+0000..U+001F) inside a string literal to be
                     ;; escaped; a raw newline/tab/CR in a `<script
                     ;; type="application/ld+json">` body is INVALID JSON
                     ;; that search/social consumers reject. The CLJS branch
                     ;; gets this for free via `JSON.stringify`; the JVM
                     ;; hand-rolled emitter must match. Short escapes for the
                     ;; five JSON-named controls (\b \t \n \f \r), `\u00XX`
                     ;; for the rest of the range. Non-control chars pass
                     ;; through untouched so the `<` hardening (applied
                     ;; separately, after this) and ordinary content are
                     ;; unaffected.
                     (let [n (.length s)]
                       (loop [i   0
                              acc (StringBuilder.)]
                         (if (>= i n)
                           (.toString acc)
                           (let [c (.charAt s i)]
                             (recur (inc i)
                                    (.append
                                      acc
                                      (case c
                                        \backspace "\\b"
                                        \tab       "\\t"
                                        \newline   "\\n"
                                        \formfeed  "\\f"
                                        \return    "\\r"
                                        (if (< (int c) 0x20)
                                          (format "\\u%04x" (int c))
                                          c)))))))))
                   (emit-string [string-value]
                     (str "\""
                          (-> string-value
                              (str/replace "\\" "\\\\")
                              (str/replace "\"" "\\\"")
                              escape-json-control
                              ;; Escape `<` so user-controlled
                              ;; string contents can't close the
                              ;; surrounding <script>.
                              html/escape-script-body-string)
                          "\""))
                   (emit-key [map-key]
                     ;; JSON object keys must be quoted
                     ;; strings. `js/JSON.stringify` (the CLJS branch)
                     ;; coerces every key to a string; the JVM branch must
                     ;; match or the two reader-conditional arms diverge on
                     ;; non-string keys. A bare `(emit k)` emitted invalid
                     ;; JSON for number/boolean/nil keys (`1:"a"`,
                     ;; `true:"a"`, `null:"a"`). Coerce every key to a quoted
                     ;; string; nil keys throw (no JSON representation —
                     ;; same fail-fast posture as the non-finite-number gate).
                     (cond
                       (string? map-key)  (emit-string map-key)
                       (keyword? map-key) (emit-string
                                            (if-let [key-namespace (namespace map-key)]
                                              (str key-namespace "/" (name map-key))
                                              (name map-key)))
                       (nil? map-key)
                       (error/throw-error!
                         :rf.error/invalid-json-ld-key
                         'rf.ssr.head/emit
                         (str "JSON-LD object key is nil"
                              " — JSON object keys must be"
                              " strings; nil has no key"
                              " representation. Use a string or keyword"
                              " key in the JSON-LD head model.")
                         {:recovery :supply-a-non-nil-key})
                       (number? map-key)  (emit-string (emit-number map-key))
                       :else              (emit-string (str map-key))))
                   (emit [value]
                     (cond
                       (nil? value)     "null"
                       (boolean? value) (if value "true" "false")
                       (number? value)  (emit-number value)
                       (string? value)  (emit-string value)
                       (keyword? value) (emit
                                          (if-let [value-namespace (namespace value)]
                                            (str value-namespace "/"
                                                 (name value))
                                            (name value)))
                       (map? value)     (str "{"
                                         (str/join "," (map (fn [[map-key map-value]]
                                                              (str (emit-key map-key)
                                                                   ":"
                                                                   (emit map-value)))
                                                            value))
                                         "}")
                       (sequential? value) (str "[" (str/join "," (map emit value)) "]")
                       :else (emit (str value))))]
             (emit value))))

(defn- emit-json-ld [json-ld-value]
  (str "<script type=\"application/ld+json\">"
       (ld-json-string json-ld-value)
       "</script>"))

;; ---- head-model->html -----------------------------------------------------

;; Canonical emission order per Spec 011 §Default flow step 4:
;; <title> → <meta> → <link> → <script> → JSON-LD. Encoded as a vector
;; of `[model-key emit-fn]` pairs so the canonical ordering reads as
;; data, not as a six-arm `cond->`.
;;
;; `:title` is intentionally absent — it's a singleton (one slot, one
;; string value) whose emit fn takes a string rather than an item from a
;; collection. `head-model->html` extracts it via a separate `when-let`
;; binding outside this loop. Future singleton additions (e.g. `<base>`)
;; need the same separate-binding treatment; collection keys (multiple
;; tags emitted in declaration order) extend this vector.
(def ^:private emission-order
  [[:meta    emit-meta-tag]
   [:link    emit-link-tag]
   [:script  emit-script-tag]
   [:json-ld emit-json-ld]])

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
   (let [title-part (when-let [title (:title head-model)]
                      (emit-title title))
         collection-parts
         (apply str
                (for [[model-key emit-tag] emission-order
                      :let       [tag-models (get head-model model-key)]
                      :when      (seq tag-models)
                      item       tag-models]
                  (emit-tag item)))
         inner-html (str title-part collection-parts)]
     (if wrap?
       (str "<head>" inner-html "</head>")
       inner-html))))
