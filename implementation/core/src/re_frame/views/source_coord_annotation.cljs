(ns re-frame.views.source-coord-annotation
  "Source-coord + view-id DOM annotation walk for the Reagent-side views
  ns. Per rf2-lh7p — split out of `re-frame.views` so the views file
  stays focused on registration orchestration. Re-frame.views re-exports
  the `format-source-coord` helper so the existing test that references
  `#'re-frame.views/format-source-coord` continues to resolve.

  Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) the
  Reagent substrate adapter MUST inject
  `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on each registered
  view's root DOM element when `interop/debug-enabled?` is true. The
  annotation lets pair-shaped tools (re-frame-pair, re-frame-10x, IDE
  jump-to-source) map a clicked DOM node back to the reg-view call
  site.

  Per Spec 006 §View tagging contract (rf2-01il5) the same wrapper also
  stamps `data-rf-view=\"<ns>/<sym>\"` on the same root element — the
  fallback for the runtime view-hierarchy walker when the Fiber-reading
  primary path (Spec View-Hierarchy-Capture, rf2-mxkq7) is unavailable.
  Both attributes ride the same wrapper, the same hiccup walk, and the
  same `interop/debug-enabled?` elision gate.

  Contract details:

    - The id is a registry keyword `<ns>/<sym>`. Combined with the
      captured `:line` / `:column` (from `(meta &form)` at reg-view
      macro-expansion time), the source-coord attribute value is
      `<ns>:<sym>:<line>:<col>`. `<col>` is `?` when the column was
      not captured (the column-key is optional per Spec 001). The
      view-id attribute value is `(str id)` — i.e. `:rf.foo/bar` for a
      namespaced keyword id; the walker reads it back via
      `(keyword (subs s 1))`.

    - The wrapper inspects the user's render-fn output:
        * `[:tag {...attrs} & children]` → merge both attrs into the
          existing map.
        * `[:tag & children]` (no attrs map) → splice an attrs map in
          carrying both attributes.
        * `[fn-or-component-or-fragment …]` (head is a fn / class / `:>`
          / React-fragment marker) → SKIP and emit a one-shot warning
          per id. Pair-tool consumers fall back to the registry's
          `:rf/id` for source-coord; the view-walker falls back to the
          Fiber-walker primary path (or treats the view as invisible to
          the hierarchy capture — documented edge case).
        * Form-2: when the render-fn returns a fn (`(fn [args] body)`),
          we recurse on the inner-fn's output the next time the wrapper
          is called — Reagent invokes the inner fn during the SAME
          render cycle, but the wrapper's annotation runs OUTSIDE
          Reagent's per-render machinery. The simplest correct shape
          is to wrap the returned fn so the inner output gets walked
          too.

    - CRITICAL constraint (rf2-01il5 Comment 5): the wrapper MUST
      mutate the existing first element's attribute map. NEVER wrap
      with a synthetic `[:div]`. Wrapping breaks flexbox, CSS Grid,
      table layouts, `:nth-child` selectors, positioning ancestors,
      stacking contexts, and CSS containment. The wrap-with-div
      approach is a non-starter.

    - Production elision: every annotation site sits inside
      `(when interop/debug-enabled? ...)` so the closure compiler
      constant-folds the entire branch under `:advanced` +
      `goog.DEBUG=false`. Per Spec 009 §Production builds. Both
      `data-rf2-source-coord` and `data-rf-view` literals are part of
      the production-bundle elision sentinel set (see
      `scripts/check-elision.cjs`)."
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

(defn format-view-id
  "Render the registry id keyword as the `:data-rf-view` attribute
  value. Returns `(str id)` so `:rf.foo/bar` → `\":rf.foo/bar\"`. The
  walker reads it back via `(keyword (subs s 1))` when the leading `:`
  is present. Per Spec 006 §View tagging contract (rf2-01il5)."
  [id]
  (str id))

(defn inject-source-coord-attr
  "Walk the user's render-fn output and merge both
  `:data-rf2-source-coord` (Spec 006 §Source-coord annotation, rf2-z7f7)
  and `:data-rf-view` (Spec 006 §View tagging contract, rf2-01il5) into
  the root element's attrs map. Called from inside the wrapper (gated
  on `interop/debug-enabled?`). Returns the (possibly rewritten)
  hiccup. Non-DOM roots are returned unchanged after a one-shot
  warning per Spec 006 §Source-coord annotation.

  CRITICAL: this fn MUST mutate the existing first element's attrs.
  NEVER wrap with a synthetic `[:div]` — wrapping breaks flexbox /
  CSS Grid / table layouts / `:nth-child` selectors / positioning
  ancestors / stacking contexts / CSS containment.

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
    (let [head        (first out)
          maybe-attrs (second out)
          view-attr   (format-view-id id)]
      (if (map? maybe-attrs)
        ;; Existing attrs map — merge in (don't overwrite if user
        ;; already set either attribute for some reason).
        (let [merged (cond-> maybe-attrs
                       (not (contains? maybe-attrs :data-rf2-source-coord))
                       (assoc :data-rf2-source-coord coord-attr)
                       (not (contains? maybe-attrs :data-rf-view))
                       (assoc :data-rf-view view-attr))]
          (into [head merged] (drop 2 out)))
        ;; No attrs map — splice one in between head and children.
        (into [head {:data-rf2-source-coord coord-attr
                     :data-rf-view          view-attr}] (rest out))))

    ;; Non-DOM root (fn-component head, fragment, lazy-seq, nil). Skip
    ;; with a one-shot warning. Pair tools fall back to :rf/id;
    ;; view-walker falls back to the Fiber-walker primary path.
    :else
    (do
      (when (vector? out)
        (warn-once/warn-non-dom-root! id (first out)))
      out)))
