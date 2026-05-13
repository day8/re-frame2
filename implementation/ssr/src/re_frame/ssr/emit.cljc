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

  HTML escape helpers (`escape-html`, `escape-attr`, `attr-string`) live
  in `re-frame.ssr.html-helpers` — shared with the head/meta emitter per
  rf2-x7g10. Public-surface aliases are re-exported below so consumers
  who `:require [re-frame.ssr.emit :as emit]` keep seeing them at
  `emit/escape-html` / `emit/escape-attr` / `emit/attr-string`.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [clojure.string]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.ssr.hash :as hash]
            [re-frame.ssr.html-helpers :as html]
            #?(:cljs [re-frame.substrate.plain-atom :as plain-atom-cljs])))

;; ---- shared HTML helpers (rf2-x7g10) --------------------------------------
;;
;; Re-export the helpers so callers that `:require [re-frame.ssr.emit :as
;; emit]` still resolve `emit/escape-html` etc. The producing ns is
;; `re-frame.ssr.html-helpers` (shared with `re-frame.ssr.head.emit`); the
;; entity-escape rules live there once.

(def escape-html html/escape-html)
(def escape-attr html/escape-attr)
(def attr-string html/attr-string)

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

;; ---- root-attrs injection (per rf2-lxwse) --------------------------------
;;
;; The render-hash (data-rf-render-hash) is stamped on the first DOM-tag
;; element of the rendered tree. Historically this used a post-emit regex
;; replace on the output string; rf2-lxwse refactored that into a
;; structural injection on the hiccup root before stringification — the
;; same pattern as `inject-coord-on-root-hiccup` above. To compose with
;; that source-coord injection (which only runs inside the view-ref
;; resolution branch of `emit-element`), the injection threads an optional
;; `root-attrs` map down through `emit-element` and consumes it on the
;; first DOM-tag emission — past any view-refs, fragments (`:<>`),
;; Reagent-native heads (`:>`), or fn-headed components on the root path.
;; Non-DOM-rooted trees silently no-op on the injection (matches the
;; source-coord exemption).

(defn merge-root-attrs
  "Merge root-level injected attrs (per rf2-lxwse) into the attrs map of
  a DOM tag. Existing attribute values win — the injected attr is only
  added when the key isn't already present, so a caller-supplied
  `data-rf-render-hash` on the root never gets overwritten."
  [attrs root-attrs]
  (reduce-kv (fn [m k v]
               (if (contains? m k) m (assoc m k v)))
             attrs
             root-attrs))

(defn emit-element
  "Emit a hiccup node as an HTML string. The optional `root-attrs` map
  (per rf2-lxwse) carries attributes destined for the first DOM-tag
  element on the root path — view-refs, fragments, Reagent-native heads,
  and fn-headed components pass it through; the first DOM-tag emission
  merges and consumes it. Recursive calls into children always pass
  `nil` so the injection lands on the root only."
  ([el] (emit-element el nil))
  ([el root-attrs]
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
               ;; Pass root-attrs through view-ref resolution so the hash
               ;; lands on the resolved DOM root, alongside the source-coord.
               (emit-element out root-attrs))
             (let [[tag-name tag-attrs] (parse-tag-name head)
                   [user-attrs children]
                   (if (map? (second el))
                     [(second el) (drop 2 el)]
                     [{} (rest el)])
                   merged-attrs (merge-class-attrs tag-attrs user-attrs)
                   ;; Fragment `:<>` and Reagent-native `:>` heads are
                   ;; exempt from root-attrs injection (matches the
                   ;; `inject-coord-on-root-hiccup` skip-list per
                   ;; rf2-lxwse acceptance criteria) — drop root-attrs
                   ;; on these heads.
                   skip-attrs?  (or (= :<> head) (= :> head))
                   attrs        (if (and root-attrs (not skip-attrs?))
                                  (merge-root-attrs merged-attrs root-attrs)
                                  merged-attrs)
                   void?        (contains? void-elements (keyword tag-name))]
               (if void?
                 (str "<" tag-name (attr-string attrs) ">")
                 (str "<" tag-name (attr-string attrs) ">"
                      (emit-children children)
                      "</" tag-name ">")))))

         (fn? head)
         ;; Pass root-attrs through fn-headed component resolution too —
         ;; the wrapping fn is structurally the same kind of indirection as
         ;; a registered-view ref.
         (emit-element (apply head (rest el)) root-attrs)

         :else (str el)))

     (sequential? el) (emit-children el)
     :else (escape-html el))))

(defn render-to-string
  "Pure hiccup → HTML string. Per Spec 011 §The render-tree → HTML
  emitter. Returns a STRING. The structural hash (`render-tree-hash`)
  and the HTTP response accumulator (`re-frame.ssr/get-response`, backed
  by the framework-private `response-slots` side-channel atom per
  rf2-jbcmt — Spec 011 §Response storage substrate) are separate surfaces.

  Implements HTML5 void elements, :tag#id.cls parsing, boolean attrs,
  text/attr escaping, registered-view resolution, var-reference
  resolution, :doctype? prefix, and :emit-hash? root-element hash
  injection for client-side mismatch detection.

  Per rf2-lxwse: when `:emit-hash?` is true, `data-rf-render-hash` is
  threaded as `root-attrs` through `emit-element` and merged onto the
  first DOM-tag element of the rendered tree — past view-refs, fragments,
  Reagent-native heads, and fn-headed components on the root path. This
  replaces the prior post-emit regex-on-string injection: structural,
  composes with the source-coord annotation, and silently no-ops for
  non-DOM-rooted trees (matching the source-coord exemption)."
  [render-tree opts]
  (let [root-attrs (when (:emit-hash? opts)
                     {:data-rf-render-hash (hash/render-tree-hash render-tree)})
        body       (emit-element render-tree root-attrs)]
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
