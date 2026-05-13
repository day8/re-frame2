(ns re-frame.ssr.emit
  "Pure hiccup → HTML emitter. Per Spec 011 §The render-tree → HTML emitter.

  HTML5: void elements self-close bare, doctype prefix on demand, full
  attr/text escaping, `:tag#id.cls` keyword parsing, registered-view
  resolution via the `:view` registry. `render-to-string` returns ONE
  shape: an HTML STRING — the structural hash and HTTP response triple
  live in sibling namespaces (`re-frame.ssr.hash` /
  `re-frame.ssr.response`).

  Source-coord annotation (Spec 006 §Source-coord annotation, rf2-z7f7
  / rf2-z9n1) is applied here on registered-view roots — the emitter
  injects `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on the
  view's root DOM element so pair-tool consumers can map server-rendered
  HTML back to the `reg-view` call site.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [clojure.string]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.ssr.hash :as hash]
            #?(:cljs [re-frame.substrate.plain-atom :as plain-atom-cljs])))

(defn escape-html [s]
  (-> (str s)
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "<" "&lt;")
      (clojure.string/replace ">" "&gt;")
      (clojure.string/replace "\"" "&quot;")
      (clojure.string/replace "'" "&#39;")))

(defn escape-attr [s]
  ;; Less aggressive — attributes don't need < > escaped, but " must
  ;; be escaped if we use double-quoted values. We use double-quoted
  ;; values, so escape " and &.
  (-> (str s)
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "\"" "&quot;")))

(defn attr-string [attrs]
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
(def void-elements
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; Tag-name parsing for the :div#id.cls syntax. Reagent / Hiccup convention.
(defn parse-tag-name
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

(defn merge-class-attrs
  "Merge the class from the tag-name into the attrs map's :class."
  [tag-attrs user-attrs]
  (let [t-class (:class tag-attrs)
        u-class (:class user-attrs)
        merged  (cond
                  (and t-class u-class) (str t-class " " u-class)
                  t-class                t-class
                  u-class                u-class)]
    (cond-> (merge tag-attrs (dissoc user-attrs :class))
      merged (assoc :class merged))))

(declare emit-element)

(defn- emit-children [children]
  (clojure.string/join (mapv emit-element children)))

;; ---- source-coord annotation on registered-view roots --------------------
;;
;; Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) and
;; Spec 011 §Source-coord annotation under SSR: when emitting HTML for a
;; registered view, the SSR emitter injects
;; `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the view's root
;; DOM element so pair-tool consumers can map server-rendered HTML back
;; to the reg-view call site. Mirrors the CLJS-side Reagent-adapter
;; behaviour (re-frame.views/reg-view*).

(defn format-view-source-coord
  "Render the registered view's metadata as the attribute value
  `<ns>:<sym>:<line>:<col>` per Spec 006 §Source-coord annotation. Returns
  nil when the slot has no captured coords (programmatic registration that
  bypassed the macro path) — the emitter then skips the annotation."
  [id slot]
  (when (or (:ns slot) (:line slot) (:file slot) (:column slot))
    (let [ns-part  (or (namespace id) "?")
          sym-part (name id)
          line     (:line slot)
          col      (:column slot)]
      (str ns-part ":" sym-part ":"
           (if line (str line) "?")
           ":"
           (if col (str col) "?")))))

(defn inject-coord-on-root-hiccup
  "Inject :data-rf2-source-coord into the root element of a hiccup form,
  if the root is a DOM-tag keyword. Mirrors the CLJS-side wrapper in
  re-frame.views per Spec 006 §Source-coord annotation. Non-DOM roots
  (fragment :<>, fn-or-component head, lazy-seq) are returned unchanged
  — pair tools fall back to :rf/id for those (documented exemption)."
  [coord out]
  (cond
    (and (vector? out)
         (keyword? (first out))
         (not= :<> (first out))
         (not= :> (first out)))
    (let [head        (first out)
          maybe-attrs (second out)]
      (if (map? maybe-attrs)
        (if (contains? maybe-attrs :data-rf2-source-coord)
          out
          (into [head (assoc maybe-attrs :data-rf2-source-coord coord)]
                (drop 2 out)))
        (into [head {:data-rf2-source-coord coord}] (rest out))))

    :else out))

(defn emit-element [el]
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
            (let [raw   (apply (:handler-fn maybe-view) (rest el))
                  ;; Spec 006 §Source-coord annotation: inject the
                  ;; data-rf2-source-coord attribute on the registered
                  ;; view's root DOM element when the slot's metadata
                  ;; carries source coords.
                  coord (format-view-source-coord head maybe-view)
                  out   (if coord
                          (inject-coord-on-root-hiccup coord raw)
                          raw)]
              (emit-element out))
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
  "Pure hiccup → HTML string. Per Spec 011 §The render-tree → HTML
  emitter. Returns a STRING. The structural hash (`render-tree-hash`)
  and HTTP response (`[:rf/response]`) are separate surfaces.

  Implements HTML5 void elements, :tag#id.cls parsing, boolean attrs,
  text/attr escaping, registered-view resolution, var-reference
  resolution, :doctype? prefix, and :emit-hash? root-element hash
  injection for client-side mismatch detection."
  [render-tree opts]
  (let [body (emit-element render-tree)
        body (if (:emit-hash? opts)
               (let [h (hash/render-tree-hash render-tree)]
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

;; Wire render-to-string into the plain-atom adapter so callers using
;; rf/render-to-string (delegating through the substrate adapter) get
;; this implementation. Per rf2-uo7v the Reagent adapter wires its own
;; set-hiccup-emitter! through `:reagent/set-hiccup-emitter!`; we
;; consume that hook below so ssr does not statically :require the
;; Reagent adapter ns.
#?(:clj
   (try
     (require 're-frame.substrate.plain-atom)
     ((requiring-resolve 're-frame.substrate.plain-atom/set-hiccup-emitter!)
      render-to-string)
     (catch Throwable _ nil)))

#?(:cljs
   (plain-atom-cljs/set-hiccup-emitter! render-to-string))

;; Reagent adapter wiring (load-order-symmetric counterpart to the
;; plain-atom path above). No-op when the Reagent adapter isn't on the
;; classpath.
(when-let [reagent-set-emitter! (late-bind/get-fn :reagent/set-hiccup-emitter!)]
  (reagent-set-emitter! render-to-string))

(defn install-render-to-string!
  "Install this ns's render-to-string into a substrate adapter's
  :render-to-string slot. Called by adapter namespaces that ship in
  their own artefact for hosts that wire a custom adapter directly.
  Per Spec 006 §Adapter shipping convention (rf2-0hxm).

  The bundled Reagent adapter wires itself via the
  `:reagent/set-hiccup-emitter!` late-bind hook (rf2-uo7v) — this fn
  remains as a public surface for non-bundled adapters."
  [set-hiccup-emitter!-fn]
  (set-hiccup-emitter!-fn render-to-string)
  nil)
