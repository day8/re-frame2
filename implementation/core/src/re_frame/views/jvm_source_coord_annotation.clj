(ns re-frame.views.jvm-source-coord-annotation
  "Server-side (JVM) dev-mode view annotation, applied at the `reg-view`
  REGISTRATION boundary. The JVM twin of the client hiccup walk in
  `re-frame.views.source-coord-annotation` (`.cljs`).

  Per Spec 006 §Source-coord annotation and §View tagging contract the
  view-registration boundary is the single architectural home for the two
  dev-mode DOM annotations on EVERY host: `data-rf2-source-coord=\"<ns>:
  <sym>:<line>:<col>\"` (rf2-z7f7 — maps a rendered node back to its
  reg-view call site) and `data-rf-view=\"<str id>\"` (rf2-01il5 — the
  view-id capture surface). The client stamps both at React render time;
  this namespace stamps both on the server render's hiccup so the two
  hosts emit the same evidence and a dev SSR page hydrates as a clean
  ADOPTION rather than an attribute-mismatch (rf2-8vi4q).

  ## Why here, and not in the SSR emitter

  The rejected alternative (rf2-8vi4q Option A) stamped the annotation
  inside the JVM SSR emitter's keyword-view branch. That branch is gone
  (rf2-j81hs — a keyword head is a DOM element on every host), and it
  never fired on the callable-head shape isomorphic pages actually use.
  Annotation is a property of the REGISTERED VIEW, not of the emitter, so
  it lives at the registration boundary: `re-frame.core/reg-view*`'s `:clj`
  branch wraps the stored `:handler-fn` with `wrap-handler-fn` below, and
  the pure hiccup→HTML emitter carries no annotation logic at all.

  ## Production gate

  The whole wrapper sits behind `rf.interop/debug-enabled?` (the JVM
  dev/prod gate — true by default, disabled by `-Dre-frame.debug=false`
  / `RE_FRAME_DEBUG=false`, the counterpart to CLJS `goog.DEBUG`). In a
  production SSR build the handler-fn is stored UNWRAPPED, so server
  markup is annotation-free — symmetric with the CLJS `:advanced` +
  `goog.DEBUG=false` build where Closure DCE folds the client walk away.

  ## The dialect is shared, not reinvented

  `format-source-coord` / `format-view-id` are the SAME implementation on
  every host: rf2-5q0jv moved them into the neutral `.cljc` contract owner
  `re-frame.source-coords` (co-located with their inverse parsers), and both
  the vars below and the CLJS `re-frame.adapter.context` counterparts are
  thin aliases of it — so a JVM copy and a CLJS copy can no longer drift.
  The hiccup walk here still mirrors
  `re-frame.views.source-coord-annotation`'s DOM-root branch exactly: merge
  both attributes onto a DOM-tag root, preserve any author-supplied value,
  recurse through Form-2 fns, skip fragment / callable-head roots. The
  `source-coord-parity` tests (JVM + CLJS) pin the shared formatters to one
  canonical literal and assert both hosts resolve to the neutral owner."
  (:require [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]))

(set! *warn-on-reflection* true)

(def format-source-coord
  "Render a registry slot's id + captured coords as the
  `data-rf2-source-coord` attribute value `<ns>:<sym>:<line>:<col>`.
  `<line>` / `<col>` degrade to `?` when the coords were not captured
  (a programmatic `reg-view*` that bypassed the macro path). Per Spec 006
  §Source-coord annotation. JVM-side alias of the neutral cross-host owner
  `re-frame.source-coords/format-source-coord` (rf2-5q0jv) — the single
  implementation both hosts share, pinned to one canonical literal by the
  `source-coord-parity` tests so it cannot drift."
  rf.source-coords/format-source-coord)

(def format-view-id
  "Render a registry id keyword as the `data-rf-view` attribute value —
  `(str id)`, so `:rf.foo/bar` → `\":rf.foo/bar\"` (leading colon
  included). Per Spec 006 §View tagging contract. JVM-side alias of the
  neutral cross-host owner `re-frame.source-coords/format-view-id`
  (rf2-5q0jv)."
  rf.source-coords/format-view-id)

(defn- dom-tag-head?
  "True when `head` is a Hiccup DOM-tag keyword. The React-fragment marker
  `:<>` and the Reagent interop marker `:>` are NOT DOM tags and are
  exempt from annotation, mirroring the client `dom-tag?` predicate per
  Spec 006."
  [head]
  (and (keyword? head)
       (not= :<> head)
       (not= :> head)))

(defn annotate-root
  "Merge `:data-rf2-source-coord` (= `coord-attr`) and `:data-rf-view`
  (= `view-attr`) onto the root DOM element of a view's render output
  `out`, mirroring `re-frame.views.source-coord-annotation/
  inject-source-coord-attr`'s hiccup-walk contract:

    - Form-2 (`out` is a fn): return a fn that annotates the inner
      output when the emitter invokes it (Reagent/UIx Form-2
      semantics; the JVM SSR emitter's `resolve-component-head` unwraps
      the inner fn with the same args).
    - DOM-tag-rooted hiccup: annotate the existing root element,
      PRESERVING any author-supplied `:data-rf2-source-coord` /
      `:data-rf-view` value.
    - Anything else (fragment `:<>`, a callable/view-ref head, a
      lazy-seq, a scalar, nil): pass through unannotated — the documented
      non-DOM-root exemption. Pair tools fall back to the registry
      `:rf/id` for those.

  It annotates the EXISTING root; it never introduces a synthetic
  wrapper element (which would change layout / selector semantics), just
  like the client walk."
  [id coord-attr view-attr out]
  (cond
    ;; Form-2: the render-fn returned an inner render fn. Wrap so the
    ;; inner fn's output is annotated when the emitter calls through.
    (fn? out)
    (fn form-2-wrapper [& args]
      (annotate-root id coord-attr view-attr (apply out args)))

    ;; Hiccup vector with a DOM-tag keyword head — annotate the root.
    (and (vector? out) (dom-tag-head? (first out)))
    (let [head        (first out)
          maybe-attrs (second out)]
      (if (map? maybe-attrs)
        ;; Existing attrs map — merge in, never overwriting an author-set
        ;; value for either framework key.
        (let [merged (cond-> maybe-attrs
                       (not (contains? maybe-attrs :data-rf2-source-coord))
                       (assoc :data-rf2-source-coord coord-attr)
                       (not (contains? maybe-attrs :data-rf-view))
                       (assoc :data-rf-view view-attr))]
          (into [head merged] (drop 2 out)))
        ;; No attrs map — splice one in between head and children.
        (into [head {:data-rf2-source-coord coord-attr
                     :data-rf-view          view-attr}]
              (rest out))))

    ;; Non-DOM root — fragment, callable/view-ref head, lazy-seq, scalar,
    ;; nil. Skip. (The client emits a one-shot dev console.warn here for
    ;; the pair-tool fallback; the JVM has no equivalent surface and the
    ;; net DOM effect — no annotation — is identical, so it degrades
    ;; silently.)
    :else out))

(defn wrap-handler-fn
  "Return `render-fn` wrapped so its render output is annotated at the
  root per `annotate-root`, when `rf.interop/debug-enabled?` is true.

  The gate is read ONCE, at registration time — mirroring the client
  `re-frame.substrate.spine/make-wrap-view`, which decides whether to
  wrap when the view is registered rather than per render. In a
  production SSR build (`rf.interop/debug-enabled?` false) the ORIGINAL
  `render-fn` is returned unwrapped, so server markup carries no
  annotation."
  [id coords render-fn]
  (if rf.interop/debug-enabled?
    (let [coord-attr (format-source-coord id coords)
          view-attr  (format-view-id id)]
      (fn annotated-view [& args]
        (annotate-root id coord-attr view-attr (apply render-fn args))))
    render-fn))
