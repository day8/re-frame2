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
      `scripts/check-elision.cjs`).

  ## History: JSX source-coord props (rf2-fa4ly, removed by rf2-rohdn)

  An earlier version of this wrapper also injected the JSX-shaped
  source-coord props (`:_jsxFileName` / `:_jsxLineNumber` /
  `:_jsxColumnNumber`) intended for React DevTools' \"View source\"
  gesture. The feature never worked: Reagent passed the props through
  as DOM attributes (triggering React's \"unrecognised prop on a DOM
  element\" warnings), and DevTools does not read \"View source\" from
  element props anyway — it reads `__source` off the third arg of
  `React.createElement`, which is set by `@babel/plugin-transform-
  react-jsx-source` at JSX-compile time and is not accessible via
  hiccup. Net effect: dev-console noise with no DevTools benefit.
  Option A from rf2-rohdn dropped the injection cleanly. The
  `data-rf2-source-coord` + `data-rf-view` DOM attributes (which DO
  work and are read by re-frame-pair, the view-walker, IDE jump-to-
  source tooling) ride the same wrapper unchanged."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.views.warn-once :as warn-once]))

;; `format-source-coord` / `format-view-id` are the pure string projections
;; of the annotation attribute VALUES. They live in the shared leaf
;; `re-frame.adapter.context` so the Reagent hiccup walk here and the
;; React-element-clone walk in `re-frame.substrate.spine` produce byte-
;; identical `data-rf2-source-coord` / `data-rf-view` values across
;; substrates (rf2-t9s6p6). Re-exported under their historical names here
;; so `re-frame.views/format-source-coord` (and the parity test referencing
;; `#'re-frame.views/format-source-coord`) keep resolving.
(def format-source-coord adapter-context/format-source-coord)
(def format-view-id       adapter-context/format-view-id)

(defn- dom-tag?
  "True if `head` is a Hiccup DOM-tag keyword. Reagent's React-fragment
  marker is `:<>`; the `:>` (interop) marker is for arbitrary React
  components — both are exempt from annotation per Spec 006."
  [head]
  (and (keyword? head)
       (not= :<> head)
       (not= :> head)))

(defn inject-source-coord-attr
  "Walk the user's render-fn output and merge `:data-rf2-source-coord`
  (Spec 006 §Source-coord annotation, rf2-z7f7) and `:data-rf-view`
  (Spec 006 §View tagging contract, rf2-01il5) into the root element's
  attrs map. Called from inside the wrapper (gated on
  `interop/debug-enabled?`). Returns the (possibly rewritten) hiccup.
  Non-DOM roots are returned unchanged after a one-shot warning per
  Spec 006 §Source-coord annotation.

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
        ;; already set any of the framework keys for some reason).
        (let [merged (cond-> maybe-attrs
                       (not (contains? maybe-attrs :data-rf2-source-coord))
                       (assoc :data-rf2-source-coord coord-attr)
                       (not (contains? maybe-attrs :data-rf-view))
                       (assoc :data-rf-view view-attr))]
          (into [head merged] (drop 2 out)))
        ;; No attrs map — splice one in between head and children.
        (let [attrs {:data-rf2-source-coord coord-attr
                     :data-rf-view          view-attr}]
          (into [head attrs] (rest out)))))

    ;; Non-DOM root (fn-component head, fragment, lazy-seq, nil). Skip
    ;; with a one-shot warning. Pair tools fall back to :rf/id;
    ;; view-walker falls back to the Fiber-walker primary path.
    :else
    (do
      (when (vector? out)
        (warn-once/warn-non-dom-root! id (first out)))
      out)))
