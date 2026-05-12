(ns re-frame.views.source-coord-annotation
  "Source-coord DOM annotation walk for the Reagent-side views ns. Per
  rf2-lh7p — split out of `re-frame.views` so the views file stays
  focused on registration orchestration. Re-frame.views re-exports the
  `format-source-coord` helper so the existing test that references
  `#'re-frame.views/format-source-coord` continues to resolve.

  Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) the
  Reagent substrate adapter MUST inject
  `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on each registered
  view's root DOM element when `interop/debug-enabled?` is true. The
  annotation lets pair-shaped tools (re-frame-pair, re-frame-10x, IDE
  jump-to-source) map a clicked DOM node back to the reg-view call
  site.

  Contract details:

    - The id is a registry keyword `<ns>/<sym>`. Combined with the
      captured `:line` / `:column` (from `(meta &form)` at reg-view
      macro-expansion time), the attribute value is
      `<ns>:<sym>:<line>:<col>`. `<col>` is `?` when the column was
      not captured (the column-key is optional per Spec 001).

    - The wrapper inspects the user's render-fn output:
        * `[:tag {...attrs} & children]` → merge :data-rf2-source-coord
          into attrs.
        * `[:tag & children]` (no attrs map)            → splice an attrs
          map in.
        * `[fn-or-component-or-fragment …]` (head is a fn / class / `:>`
          / React-fragment marker) → SKIP and emit a one-shot warning
          per id. Pair-tool consumers fall back to the registry's
          `:rf/id` for these cases (per Spec 006 §Source-coord
          annotation, documented Fragment exemption).
        * Form-2: when the render-fn returns a fn (`(fn [args] body)`),
          we recurse on the inner-fn's output the next time the wrapper
          is called — Reagent invokes the inner fn during the SAME
          render cycle, but the wrapper's annotation runs OUTSIDE
          Reagent's per-render machinery. The simplest correct shape
          is to wrap the returned fn so the inner output gets walked
          too.

    - Production elision: every annotation site sits inside
      `(when interop/debug-enabled? ...)` so the closure compiler
      constant-folds the entire branch under `:advanced` +
      `goog.DEBUG=false`. Per Spec 009 §Production builds."
  (:require [re-frame.views.warn-once :as warn-once]))

(defn format-source-coord
  "Render the registry slot's captured coords as the attribute value
  shape `<ns>:<sym>:<line>:<col>`. The id keyword's namespace and name
  give us `<ns>` and `<sym>`; `<line>` / `<col>` come from the captured
  coords (CLJS reg-view macro at expansion time). Per Spec 006
  §Source-coord annotation."
  [id coords]
  (let [ns-part  (or (namespace id) "?")
        sym-part (name id)
        line     (:line coords)
        col      (:column coords)]
    (str ns-part ":" sym-part ":"
         (if line (str line) "?")
         ":"
         (if col (str col) "?"))))

(defn- dom-tag?
  "True if `head` is a Hiccup DOM-tag keyword. Reagent's React-fragment
  marker is `:<>`; the `:>` (interop) marker is for arbitrary React
  components — both are exempt from annotation per Spec 006."
  [head]
  (and (keyword? head)
       (not= :<> head)
       (not= :> head)))

(defn inject-source-coord-attr
  "Walk the user's render-fn output and merge :data-rf2-source-coord
  into the root element's attrs map. Called from inside the wrapper
  (gated on interop/debug-enabled?). Returns the (possibly rewritten)
  hiccup. Non-DOM roots are returned unchanged after a one-shot
  warning per Spec 006 §Source-coord annotation.

  Form-2: when `out` is a fn, return a fn that recurses on the inner
  output — Reagent's renderer will call our returned fn just like
  the user's fn, and we get a chance to annotate the inner hiccup."
  [id coord-attr out]
  (cond
    ;; Form-2: render-fn returned a fn. Wrap so the inner fn's output
    ;; is also annotated when Reagent calls through.
    (fn? out)
    (fn form-2-wrapper [& args]
      (inject-source-coord-attr id coord-attr (apply out args)))

    ;; Hiccup vector with a DOM-tag keyword head. Annotate the root.
    (and (vector? out) (dom-tag? (first out)))
    (let [head     (first out)
          maybe-attrs (second out)]
      (if (map? maybe-attrs)
        ;; Existing attrs map — merge in (don't overwrite if user
        ;; already set it for some reason).
        (let [merged (if (contains? maybe-attrs :data-rf2-source-coord)
                       maybe-attrs
                       (assoc maybe-attrs :data-rf2-source-coord coord-attr))]
          (into [head merged] (drop 2 out)))
        ;; No attrs map — splice one in between head and children.
        (into [head {:data-rf2-source-coord coord-attr}] (rest out))))

    ;; Non-DOM root (fn-component head, fragment, lazy-seq, nil). Skip
    ;; with a one-shot warning. Pair tools fall back to :rf/id.
    :else
    (do
      (when (vector? out)
        (warn-once/warn-non-dom-root! id (first out)))
      out)))
