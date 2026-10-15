(ns re-frame.views.source-coord-annotation
  "Source-coordinate and view-id DOM annotation for Reagent views.

  Per Spec 006 §Source-coord annotation, the Reagent substrate injects
  `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on each registered
  view's root DOM element when `interop/debug-enabled?` is true. The
  annotation lets pair-shaped tools (re-frame-pair, re-frame-10x, IDE
  jump-to-source) map a clicked DOM node back to the reg-view call
  site.

  Per Spec 006 §View tagging contract, the same wrapper also
  stamps `data-rf-view=\":rf.foo/bar\"` on the same root element — the
  value is `(str id)` (a printed keyword, leading colon included; see the
  shared `format-view-id`), not the colon-less `<ns>/<sym>` form. It is
  the runtime view-id capture surface. Both attributes ride the same
  Hiccup walk and debug-elision gate.

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
        * `[fn-or-component-or-fragment …]` (head is a fn / class / an
          interop head `:>` / `:r>` / `:f>` / React-fragment marker) →
          SKIP, silently: each is valid composition, and the only repair
          would be a wrapper element. Pair-tool consumers fall back to the registry's
          `:rf/id` for source-coord; the view itself is untagged and so
          invisible to view-id lookup — a documented limit in Spec 006
          §View tagging contract.
        * Form-2: the walk never sees a Form-2 inner fn. `re-frame.views`
          recognises one as the construction call's result and hands the
          substrate a render fn that runs the per-render scope, this walk
          included, around every call of the inner fn.
        * Form-3: a `create-class` result satisfies `fn?` too, and passes
          through unchanged so Reagent mounts the class.

    - The wrapper MUST annotate the existing root element. It never
      introduces a synthetic `[:div]`, which would change layout and
      selector semantics.

    - Production elision: every annotation site sits inside
      `(when interop/debug-enabled? ...)` so the closure compiler
      constant-folds the entire branch under `:advanced` +
      `goog.DEBUG=false`. Per Spec 009 §Production builds. Both
      `data-rf2-source-coord` and `data-rf-view` literals are part of
      the production-bundle elision sentinel set (see
      `scripts/check-elision.cjs`)."
  (:require [goog.object :as gobj]
            [re-frame.adapter.context :as rf.adapter.context]))

;; `format-source-coord` / `format-view-id` are the pure string projections
;; of the annotation attribute VALUES. They live in the shared leaf
;; `re-frame.adapter.context` so the Reagent hiccup walk here and the
;; React-element-clone walk in `re-frame.substrate.spine` produce byte-
;; identical `data-rf2-source-coord` / `data-rf-view` values across
;; substrates. Re-exported here for the `re-frame.views` facade and its
;; parity tests.
(def format-source-coord rf.adapter.context/format-source-coord)
(def format-view-id       rf.adapter.context/format-view-id)

(defn reagent-class?
  "Dependency-free structural predicate for a Reagent-family Form-3 class
  (a `create-class` result). Recognises BOTH supported Reagent
  implementations — stock Reagent AND reagent-slim — without importing
  either adapter into neutral core.

  `re-frame.views.source-coord-annotation` lives in the substrate-neutral core
  artefact, whose classpath intentionally contains neither stock Reagent nor
  reagent-slim. Requiring `reagent.impl.component` (or `reagent2.impl.component`)
  here would make a core-only compile fail and would pull a Reagent
  implementation into UIx release bundles. So each supported class shape
  is matched by its own structural marker instead:

    - Stock Reagent 2.x marks a `create-class` constructor with
      `prototype.reagentRender`.
    - reagent-slim tags the constructor itself with `cljsReagentClass = true`
      (slim's own `reagent2.impl.component/reagent-class?` keys off exactly
      this) and installs `prototype.render` + a `cljsReagentRender` fn, never
      `prototype.reagentRender`.

  Matching both markers keeps core neutral without a second adapter hook.
  `re-frame.views` asks it on every render to tell a Form-3 class from a
  Form-2 inner fn. reagent-slim is a first-class supported adapter, so a
  slim Form-3 class MUST be recognised here: otherwise it is taken for a
  Form-2 inner fn, wrapped, and later invoked as an ordinary function rather
  than mounted as a class — losing its React lifecycle.
  (`re-frame.test-helpers` carries its own slim-aware detection where the
  test surface needs one.)"
  [x]
  (and (fn? x)
       (or
         ;; Stock Reagent: `create-class` installs `prototype.reagentRender`.
         (some? (some-> (gobj/get x "prototype")
                        (gobj/get "reagentRender")))
         ;; reagent-slim: `create-class*` tags the constructor
         ;; `cljsReagentClass = true` (mirrors slim's own reagent-class?).
         (true? (gobj/get x "cljsReagentClass")))))

(defn- dom-tag?
  "True if `head` is a Hiccup DOM-tag keyword. Reagent's React-fragment
  marker `:<>` and its interop heads `:>`, `:r>` (raw createElement) and
  `:f>` (function component) are exempt from annotation per Spec 006. The
  interop heads carry the component at position 1, the slot the DOM-root
  branch splices an attrs map into, so annotating one displaces the
  component."
  [head]
  (and (keyword? head)
       (not= :<> head)
       (not= :> head)
       (not= :r> head)
       (not= :f> head)))

(defn inject-source-coord-attr
  "Walk the user's render-fn output and merge `:data-rf2-source-coord`
  (Spec 006 §Source-coord annotation) and `:data-rf-view`
  (Spec 006 §View tagging contract) into the root element's
  attrs map. Called from inside the wrapper (gated on
  `interop/debug-enabled?`). Returns the (possibly rewritten) hiccup.
  Non-DOM roots are returned unchanged (Spec 006 §Documented exemption:
  non-DOM roots).

  This fn annotates the existing root element; it never wraps the output
  with a synthetic element that would alter layout or selector semantics.

  `out` is a render's output, never a Form-2 inner fn: `re-frame.views`
  takes that for construction and walks the inner fn's output instead.
  A Reagent-family `create-class` value (stock Reagent or reagent-slim)
  is passed through so the substrate can mount the class."
  [id coord-attr out]
  (cond
    ;; create-class values satisfy fn?, but Reagent must see the class itself
    ;; in order to install its lifecycle methods.
    ;; A Form-3 class is a component-returning root: it has no concrete DOM
    ;; node this outer registered view can annotate.
    (reagent-class? out)
    out

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

    ;; Non-DOM root (fn-component head, fragment, lazy-seq, string, number,
    ;; nil). Skip. Pair tools fall back to :rf/id; the view is untagged and
    ;; so invisible to view-id lookup — a documented limit in Spec 006
    ;; §View tagging contract.
    :else
    out))
