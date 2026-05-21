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

;; Audit rf2-asmj1 P6 / cluster rf2-sljs1 — reflection-warning gate.
;; The JVM-side `ld-json-string` is hand-rolled JSON emission; the
;; directive flags any accidental boxing introduced by future refactors.
;; CLJS has no reflection concept — the directive is JVM-only.
#?(:clj (set! *warn-on-reflection* true))

;; ---- per-element emitters -------------------------------------------------

(defn- emit-title [title]
  (when (and title (not= "" title))
    (str "<title>" (html/escape-html title) "</title>")))

(defn- emit-meta-tag [m]
  (str "<meta" (html/attr-string m) ">"))

(defn- emit-link-tag [m]
  (str "<link" (html/attr-string m) ">"))

(defn- emit-script-tag [m]
  ;; `attr-string` already iterates the whole map in declaration order —
  ;; the historical pull-known-keys-then-merge-remainder dance was a
  ;; no-op that obscured the contract. Per audit rf2-asmj1 H2 / cluster
  ;; rf2-sljs1: pass `m` straight through.
  (str "<script" (html/attr-string m) "></script>"))

(defn- ld-json-string
  "Serialise a JSON-LD object map to its `<script type=\"application/ld+json\">`
  body. CLJ uses a minimal printer (works for the spec's canonical shape:
  strings, numbers, booleans, vectors, maps with string and/or keyword
  keys). CLJS uses `js/JSON.stringify` via `clj->js`.

  On the JVM side, keyword keys and keyword values are both serialised
  to their fully-qualified name — `:my.app/foo` → `\"my.app/foo\"` — so
  namespaces survive serialisation. The key and value handling are
  symmetric; rf2-a50nz fixed an earlier asymmetry that quietly stripped
  the namespace prefix off keys.

  Script-body safety (rf2-m5u23, security audit 2026-05-14 §P1.1) —
  every `<` inside string contents is escaped as the JSON `\\u003c`
  Unicode escape via `html/escape-script-body-string`, so a string
  value containing `</script>` cannot close the surrounding
  `<script type=\"application/ld+json\">` envelope. The escape is
  applied at the string-literal boundary so structural `:` / `,` / `{`
  / `[` / quote chars are unaffected (they're not user-controlled
  data). JSON.parse on the client accepts `\\u003c` as a string-
  literal escape for `<`, so the payload round-trips unchanged."
  [x]
  #?(:cljs (-> (js/JSON.stringify (clj->js x))
               html/escape-script-body-string)
     :clj  (letfn [(emit-number [v]
                     ;; rf2-8jl26 — `(str v)` produces non-JSON for two
                     ;; numeric shapes the JVM admits but JSON.parse rejects:
                     ;;   • Ratios print as `1/3` — coerce to a double so the
                     ;;     wire form is `0.3333333333333333`.
                     ;;   • Non-finite doubles/floats (`##Inf` / `##-Inf` /
                     ;;     `##NaN`) have no JSON representation — fail-fast,
                     ;;     same posture as the tag-name / header-value gates.
                     (cond
                       (ratio? v) (str (double v))
                       (and (or (instance? Double v) (instance? Float v))
                            (not (Double/isFinite (double v))))
                       (throw (ex-info ":rf.error/invalid-json-ld-number"
                                       {:rf.error/id :rf.error/invalid-json-ld-number
                                        :where    'rf.ssr.head/emit
                                        :reason   (str "JSON-LD number " (pr-str v)
                                                       " is non-finite — JSON has no"
                                                       " representation for ##Inf /"
                                                       " ##-Inf / ##NaN")
                                        :value    v
                                        :recovery :no-recovery}))
                       :else (str v)))
                   (emit [v]
                     (cond
                       (nil? v)     "null"
                       (boolean? v) (if v "true" "false")
                       (number? v)  (emit-number v)
                       (string? v)  (str "\""
                                         (-> v
                                             (str/replace "\\" "\\\\")
                                             (str/replace "\"" "\\\"")
                                             ;; rf2-m5u23 — escape `<` so
                                             ;; user-controlled string
                                             ;; contents can't close the
                                             ;; surrounding <script>.
                                             html/escape-script-body-string)
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

;; Canonical emission order per Spec 011 §Default flow step 4:
;; <title> → <meta> → <link> → <script> → JSON-LD. Encoded as a vector
;; of `[model-key emit-fn]` pairs so the canonical ordering reads as
;; data, not as a six-arm `cond->` (audit rf2-asmj1 H4 / cluster
;; rf2-sljs1).
;;
;; `:title` is intentionally absent — it's a singleton (one slot, one
;; string value) whose emit fn takes a string rather than an item from a
;; collection. `head-model->html` extracts it via a separate `when-let`
;; binding outside this loop. Future singleton additions (e.g. `<base>`)
;; need the same separate-binding treatment; collection keys (multiple
;; tags emitted in declaration order) extend this vector. Audit
;; rf2-cegm7 A5.
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
   (let [title-part (when-let [t (:title head-model)]
                      (emit-title t))
         collection-parts
         (apply str
                (for [[k tag-fn] emission-order
                      :let       [coll (get head-model k)]
                      :when      (seq coll)
                      item       coll]
                  (tag-fn item)))
         inner (str title-part collection-parts)]
     (if wrap?
       (str "<head>" inner "</head>")
       inner))))
