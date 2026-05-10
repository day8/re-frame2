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
    reaction          — function form sugar over make-reaction

  Symbols **not shipped** (per Stage 1 §2.4 + DECISION-7 + Stage 2 §2.7
  audit-confirmed zero usage): `track`, `track!`, `cursor`, `wrap`,
  `rswap!`, `partial`, `merge-props`, `unsafe-html`, `adapt-react-class`,
  `reactify-component`, `create-element`, `next-tick`, `flush` (replaced
  by `reagent2.dom.client/flush-views!`), `dom-node` (Class B throw),
  `class-names`, `is-client`, `set-default-compiler!`, `create-compiler`,
  `with-let`, `render` (deprecated stub — Class B throw, see §10).

  Apps that genuinely need a dropped surface stay on
  day8/reagent-classic (the bridge); the rewrite's commitment is
  to ship only the surfaces the audited codebases actually exercise."
  (:refer-clojure :exclude [atom])
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

(defn reaction
  "Construct a Reaction wrapping fn `f` (function form; the macro form
  lives in `reagent2.ratom`). Sugar over `reagent2.ratom/make-reaction`.

  Per IMPL-SPEC §14.2: ships the function form because Dash8 uses 25
  sites of `r/reaction` and the rewrite preserves that surface."
  [f]
  (ratom/make-reaction f))

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
  `forceUpdate` directly — bypasses any pending dirty-set dedup."
  ([^js this]
   (when-some [fu (.-forceUpdate this)]
     (.call fu this)))
  ([^js this _deep?]
   ;; The `:deep?` arg in stock Reagent triggered a global re-render of
   ;; the descendant tree. Under React 19 this isn't supported the
   ;; same way (forceUpdate is per-component); we ignore the flag and
   ;; force-update just `this`. Per the audit, no production caller
   ;; actually relied on the deep behaviour.
   (when-some [fu (.-forceUpdate this)]
     (.call fu this))))

;; ---------------------------------------------------------------------------
;; Render-time surfaces
;; ---------------------------------------------------------------------------

(defn after-render
  "Schedule `f` to run after the next React commit. Routes through
  `reagent2.impl.batching/do-after-render`."
  [f]
  (batching/do-after-render f))

(defn as-element
  "Convert hiccup `form` to a React element. Delegates to
  `reagent2.impl.template/as-element`. Useful when interfacing with
  React APIs that want a React element directly (e.g. portals,
  React-side createPortal, etc.)."
  [form]
  (template/as-element form))
