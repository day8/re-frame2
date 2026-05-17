(ns re-frame.story.recorder.selector
  "Pure selector picker — DOM element → best-effort CSS selector string
  (rf2-d5u89).

  Used by the recorder's DOM-capture layer to turn a `click` /
  `input` / `change` event target into a stable selector the
  exporter can drop into a `:play-script` `[:click selector]` /
  `[:type selector text]` step.

  ## Priority

  We walk a fixed priority list and pick the first attribute that
  resolves to a non-blank value:

  1. `[data-test=\"X\"]` — the canonical Story-/test-stable hook
     (re-frame2 + Storybook convention). Every Story component
     authored to be tested carries `:data-test \"...\"` on its
     interactive nodes.
  2. `[id=\"X\"]` — the next-best stable hook. Most app templates
     emit ids on form controls.
  3. `[aria-label=\"X\"]` — for icon buttons / inputs whose only
     accessible label is the aria-label attribute.
  4. `tag:nth-of-type(N)` fallback — best-effort positional
     selector when nothing else is available. Brittle; the
     translator's output carries a hint when this branch fires so
     the user knows to harden the selector by hand.

  ## Pure / impure split

  This namespace is `.cljc` and exposes:

  - `pick-selector` — pure: takes an element-shape map
    (`{:tag :attrs :index-of-type}`) and returns a selector
    string. JVM-testable without a DOM.
  - `element->shape` — CLJS-only: walks a live `js/Element` and
    builds the shape map `pick-selector` consumes. The DOM read
    is the only impure part; the chooser logic stays pure.

  The split means the priority logic ships one set of tests on
  both JVM + CLJS; the DOM-shape extraction layer ships under a
  separate browser-only test.

  ## Why a strict whitelist of attributes

  The recorder is a developer ergonomics tool — the selector it
  picks ends up in source. We deliberately AVOID classes (CSS-
  framework noise like `tailwind-3xl`), `name`, or `for` even when
  they're present: only the four attributes above are stable
  hooks Story / re-frame2 / Reagent authors reach for. Less is
  more — a brittle selector that looks right (until the
  refactor) is worse than a `:nth-of-type` fallback the user
  immediately spots as wanting hardening."
  (:require [clojure.string :as str]))

;; ---- pure ---------------------------------------------------------------

(defn- blank?
  "Local `clojure.string/blank?` — keeps the deps surface small and
  avoids `(:require [clojure.string :as str])` reaching every call site."
  [s]
  (or (nil? s) (and (string? s) (zero? (count (.trim ^String s))))))

(def ^:const attribute-priority
  "Ordered list of attribute keys we try, highest priority first. Pure
  data so call sites can introspect (e.g. the translator's hint).

  Each entry is a `[attribute-key css-template]` pair: the template
  carries a `%s` placeholder for the attribute value."
  [["data-test"  "[data-test=\"%s\"]"]
   ["id"         "[id=\"%s\"]"]
   ["aria-label" "[aria-label=\"%s\"]"]])

(defn- escape-attr-value
  "Escape a CSS-attribute-selector value: backslash + double-quote.
  The selector is wrapped in double quotes so the escape set is
  small. Returns the empty string for nil."
  [s]
  (if (nil? s)
    ""
    (-> (str s)
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\""))))

(defn- attribute-selector
  "Build a selector for `attr-key` if `attrs` carries a non-blank
  value for it, else nil. `attrs` is a map keyed on lower-case
  attribute name (string).

  `css-template` carries a `%s` placeholder; we splice manually
  (CLJS has no `format`)."
  [attrs [attr-key css-template]]
  (let [v (get attrs attr-key)]
    (when-not (blank? v)
      (str/replace css-template "%s" (escape-attr-value v)))))

(defn- nth-of-type-fallback
  "Best-effort positional selector: `tag:nth-of-type(N)`. `tag`
  defaults to `*` when missing; `index-of-type` is 1-based per CSS.
  Returns nil if we can't produce anything sensible."
  [{:keys [tag index-of-type]}]
  (when (some? index-of-type)
    (let [t (cond
              (blank? tag) "*"
              :else        (str/lower-case (str tag)))]
      (str t ":nth-of-type(" index-of-type ")"))))

(defn pick-selector
  "Return the best-effort CSS selector for the DOM element described
  by `shape`. Pure data → string (or nil).

  `shape` is a map:
    :tag            string — lower-case tag name (e.g. \"button\")
    :attrs          {<lower-case-name> <value>} — relevant attributes only
    :index-of-type  1-based positional index among siblings of the
                    same tag, used by the `:nth-of-type(N)` fallback

  Returns:
    - the highest-priority attribute selector that resolves, or
    - the `:nth-of-type` fallback if attributes don't help, or
    - nil when no shape information is available."
  [{:keys [attrs] :as shape}]
  (or (some (partial attribute-selector (or attrs {})) attribute-priority)
      (nth-of-type-fallback shape)))

(defn selector-kind
  "Diagnostic: tell the caller WHICH tier `pick-selector` picked.
  Returns one of `:data-test` / `:id` / `:aria-label` / `:nth-of-type`
  / `:none`. Used by the translator to attach a hint when the
  brittle fallback fires."
  [{:keys [attrs index-of-type]}]
  (let [a (or attrs {})]
    (cond
      (not (blank? (get a "data-test")))  :data-test
      (not (blank? (get a "id")))         :id
      (not (blank? (get a "aria-label"))) :aria-label
      (some? index-of-type)               :nth-of-type
      :else                               :none)))

;; ---- impure (CLJS-only DOM shape extractor) -----------------------------

#?(:cljs
   (defn- relevant-attrs
     "Walk the priority list and read just those attributes off `el`.
     Skips the `:nth-of-type` line — that's computed from sibling
     position, not an attribute. Returns a string-keyed map suitable
     for `pick-selector`'s `:attrs` slot."
     [el]
     (when (and el (.-getAttribute el))
       (into {}
             (keep (fn [[attr-key _]]
                     (let [v (.getAttribute el attr-key)]
                       (when v [attr-key v]))))
             attribute-priority))))

#?(:cljs
   (defn- index-of-type
     "1-based index among same-tag siblings under the same parent.
     Returns nil when `el` has no parent (root / disconnected).
     The implementation walks `previousElementSibling` so we don't
     allocate a full sibling array."
     [el]
     (when-let [parent (.-parentElement el)]
       (let [tag (.-tagName el)]
         (loop [sib (.-previousElementSibling el) idx 1]
           (cond
             (nil? sib)               idx
             (= (.-tagName sib) tag)  (recur (.-previousElementSibling sib)
                                             (inc idx))
             :else                    (recur (.-previousElementSibling sib)
                                             idx)))))))

#?(:cljs
   (defn element->shape
     "Read the priority-relevant slice of `el` into a shape map
     `pick-selector` understands. Returns nil for nil input."
     [el]
     (when el
       {:tag           (some-> (.-tagName el) str str/lower-case)
        :attrs         (relevant-attrs el)
        :index-of-type (index-of-type el)})))

#?(:cljs
   (defn pick-for-element
     "Convenience: `element->shape` → `pick-selector`. The hot path the
     DOM-capture layer reaches for on every observed click / change.
     Returns nil if no usable selector can be derived."
     [el]
     (some-> el element->shape pick-selector)))
