(ns reagent2.core
  "User-facing compat surface for the day8/reagent-slim artefact
  (rf2-6hyy Stage 4-D).

  Per IMPL-SPEC §2.2: the audit-binding 14 surfaces. This is the
  ns most user code imports as `reagent.core`-equivalent — apps
  migrating from the bridge do `s/reagent\\./reagent2./g` at import
  sites (per IMPL-SPEC §13.1).

  Symbols **shipped** (per Stage 2 §2.7 audit):

    atom              — RAtom constructor (re-export of reagent2.ratom/atom)
    create-class      — Form-3 entry point; 7-key cap enforced
    current-component — in-flight component instance (dynamic-var read)
    after-render      — schedule fn after next React commit
    as-element        — hiccup → React element
    props             — Form-3 accessor (first arg if it's a map)
    children          — Form-3 accessor (rest after props)
    argv              — Form-3 accessor (full hiccup-style argv)
    state             — Form-3 state accessor
    state-atom        — Form-3 state-atom accessor
    set-state         — Form-3 state mutator
    replace-state     — Form-3 state mutator
    force-update      — force re-render of `this` component
    reaction          — macro (src/reagent2/core.clj); stock body syntax,
                        expands to make-reaction over (fn [] body...)

  Symbols **not shipped** (per Stage 1 §2.4 + DECISION-7 + Stage 2 §2.7
  audit-confirmed zero usage): `track`, `track!`, `cursor`, `wrap`,
  `rswap!`, `partial`, `merge-props`, `unsafe-html`, `adapt-react-class`,
  `reactify-component`, `create-element`, `next-tick`, `flush` (replaced
  by `reagent2.dom.client/flush-views!`), `class-names`, `is-client`,
  `set-default-compiler!`, `create-compiler`, `with-let`.

  React-19-removed surfaces are **absent**, not throw-on-call stubs
  (rf2-jif0qp; the pre-alpha no-back-compat-shim stance — DECISION-8).
  `render` (stock `reagent.core/render`, which forwarded to the removed
  `ReactDOM.render`) and `dom-node` (stock `reagent.dom/dom-node`,
  which proxied the removed `findDOMNode`) are simply not defined here:
  use `reagent2.dom.client/{create-root, render}` to mount and a `:ref`
  callback (or `React.useRef`) to reach a DOM node. A call site that
  still references them gets an unresolved-var compile error — fail loud
  at build time rather than at first runtime invocation.

  Apps that genuinely need a dropped surface stay on the bridge
  adapter day8/re-frame2-reagent; the rewrite's commitment is
  to ship only the surfaces the audited codebases actually exercise.
  `dom-node` is the one exception with no bridge escape hatch:
  stock Reagent 2.0.1 — the bridge's own pinned floor — already
  deleted `reagent.dom/dom-node`, so that call site has to move to
  a `:ref` on either coordinate."
  (:refer-clojure :exclude [atom])
  (:require-macros [reagent2.core])
  (:require [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]
            [reagent2.impl.component :as component]
            [reagent2.impl.template :as template]))

;; ---------------------------------------------------------------------------
;; RAtom + Reaction surfaces
;; ---------------------------------------------------------------------------

(def atom
  "Construct a reactive atom (RAtom). See `reagent2.ratom/atom`.

  Like clojure.core/atom — supports IDeref, IReset, ISwap, IWatchable,
  IMeta — except deref'ing inside a Reaction subscribes the Reaction
  to changes."
  ratom/atom)

;; `reaction` is a macro, defined in src/reagent2/core.clj and loaded
;; through the `:require-macros` above: `(reaction body...)` expands to
;; `(reagent2.ratom/make-reaction (fn [] body...))`, as stock
;; `reagent.core/reaction` does. There is deliberately no thunk-taking
;; function under this name — an explicit thunk goes straight to
;; `reagent2.ratom/make-reaction` (rf2-b9l8o).

;; ---------------------------------------------------------------------------
;; Component-shape surface
;; ---------------------------------------------------------------------------

(defn create-class
  "Form-3 entry point. Validates `spec` against the 7-key cap (per
  IMPL-SPEC §6.1); throws `:rf.error/create-class-key-unsupported` on
  miss. Delegates to `reagent2.impl.component/create-class*`.

  The 7 cap keys: `:component-did-mount`, `:component-will-unmount`,
  `:component-did-update`, `:reagent-render`, `:display-name`,
  `:get-snapshot-before-update`, `:component-did-catch`."
  [spec]
  (component/create-class* spec))

(defn current-component
  "Returns the in-flight component instance, or nil outside a render.
  Reads the dynamic `*current-component*` binding installed by the
  render path."
  []
  (component/current-component))

;; ---------------------------------------------------------------------------
;; Form-3 accessors
;; ---------------------------------------------------------------------------

(defn argv
  "Form-3 accessor: full hiccup-style arg vector that mounted `this`."
  [this]
  (component/get-argv this))

(defn props
  "Form-3 accessor: first arg of `this`'s argv if it is a map, else nil."
  [this]
  (component/get-props this))

(defn children
  "Form-3 accessor: children seq from `this`'s argv (everything after props)."
  [this]
  (component/get-children this))

(defn state-atom
  "Form-3 state cell. Per-component RAtom; created lazily."
  [this]
  (component/state-atom this))

(defn state
  "Form-3 state accessor — derefs the per-component state-atom.
  Returns nil if no state has been set."
  [this]
  @(state-atom this))

(defn set-state
  "Form-3 state mutator: merges `m` into the per-component state map."
  [this m]
  (swap! (state-atom this) merge m))

(defn replace-state
  "Form-3 state mutator: replaces the per-component state map with `m`."
  [this m]
  (reset! (state-atom this) m))

(defn force-update
  "Force re-render of `this` component. Routes through React's
  `forceUpdate` directly — bypasses any pending dirty-set dedup.

  The stock-Reagent 2-arity `(force-update this deep?)` is dropped:
  React 19 has no per-call deep-rerender API, and no audited caller
  relied on it. Callers passing a second arg will see a CLJS arity
  error at the call site — fail-fast over silent semantic divergence."
  [^js this]
  (when-some [fu (.-forceUpdate this)]
    (.call fu this)))

;; ---------------------------------------------------------------------------
;; Render-time surfaces
;; ---------------------------------------------------------------------------

(defn after-render
  "Schedule `f` to run after the next React commit — `f` observes the
  COMMITTED DOM, not a DOM whose re-render has merely been REQUESTED
  (rf2-cdoo). Routes through `reagent2.impl.batching/do-after-render`.

  Never synchronous: `f` runs on the scheduler's own turn even when no
  component is dirty."
  [f]
  (batching/do-after-render f))

(defn as-element
  "Convert hiccup `form` to a React element. Delegates to
  `reagent2.impl.template/as-element`. Useful when interfacing with
  React APIs that want a React element directly (e.g. portals,
  React-side createPortal, etc.)."
  [form]
  (template/as-element form))

;; React-19-removed surfaces (`render`, `dom-node`) are ABSENT, not
;; throw-on-call shims — see the ns docstring. The pre-alpha no-back-
;; compat-shim stance (rf2-jif0qp) drops the stub entirely: an
;; unresolved-var compile error at the call site is the louder, earlier
;; signal than a runtime throw.
