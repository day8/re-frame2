(ns re-frame.ui
  "Public surface of the re-frame.ui compiled-view substrate (artifact
  day8/re-frame2-ui; epic rf2-vxgfnd; Spec 004 rewrite).

  S1b (rf2-vxgfnd.2) exposes the compiler slice:

    defview          the ONE component form (macro; .cljc — two emitters)
    custom-element   the RULED custom-element declaration (macro)
    sub / lease      the reactive-read grammar (compiles at S1; reads
                     land S2 — calling at S1 raises the pending error)
    raw / html /
    raw-fn / spread  interop compile forms (analyzed in templates)

  Anything not exported here does not exist: no reg-view family, no
  Form-1/2/3, no positional view args, no ratoms/cursors/reactions —
  Spec 004 §Removed forms. Roots + mounting land S1c; local/effect and
  the committed-handler surfaces land S2/S3.

  The template AST is PRIVATE; the public contract is the versioned JVM
  structural tree + the DOM conversion table
  (jvm-tree-and-conversion-contract.md)."
  #?(:cljs (:require-macros [re-frame.ui]))
  (:require [re-frame.error :as error]
            #?@(:clj  [[re-frame.ui.compiler :as compiler]
                       [re-frame.ui.tree :as tree]
                       [re-frame.ui.rules :as rules]]
                :cljs [[re-frame.ui.eq :as eq]
                       [re-frame.ui.rules :as rules]
                       [re-frame.ui.runtime :as runtime]])))

;; ---------------------------------------------------------------------------
;; The component form
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defview
     "(defview name docstring? opts? [props?] template)

     The one component form: a pure function of ONE props map to a
     template, compiled to direct React code (browser) and the versioned
     structural tree (JVM). Zero or one argument — the props map; header
     destructuring lowers to direct slot reads; `:as` opts into
     materialization + generic comparison. Options (closed):
     :props (Malli), :id (registry override), :display-name.

     Every view is memoized on the generated per-slot rf= comparator
     (RULED: Object.is(a,b) OR (= a b)) and registers in the registrar's
     :view kind."
     [vname & forms]
     (compiler/defview* &form &env vname forms)))

#?(:clj
   (defmacro custom-element
     "(custom-element tag {:properties #{:help-text ...}})

     RULED declaration grammar (2026-07-12): top-level,
     compile-resolvable, registers like defview; the :properties set is
     the ENTIRE v1 grammar. Declared names compile to camelCase JS
     properties on the client (:help-text -> helpText); undeclared names
     are attributes; undeclared elements need no declaration
     (all-attributes default). The JVM serialiser emits attributes only —
     property-props are named by :rf.ui/property-props and applied at
     hydration."
     [tag opts]
     (compiler/custom-element* &form &env tag opts)))

;; ---------------------------------------------------------------------------
;; Reactive-read grammar (S1: compiles; reads land S2)
;; ---------------------------------------------------------------------------

(defn sub
  "(sub [:query ...]) — returns the subscription's value at a compile-
  indexed view site. The GRAMMAR compiles at S1 (loop rejection is a
  compile error); actual reads land with the S2 reactivity slice — until
  then every call raises :rf.error/ui-sub-unavailable."
  [query]
  #?(:clj  (error/throw-error!
            :rf.error/ui-sub-unavailable 're-frame.ui/sub
            (str "(sub " (pr-str query) ") — reactive reads land with the S2 "
                 "reactivity slice; no Stage-1 Tier-1 fixture may exercise a "
                 "sub read")
            {:extra {:query query}})
     :cljs (runtime/sub* query)))

(defn lease
  "(lease descriptor) — declares resource liveness at a view site.
  Grammar-only at S1; lands with the S2 observation slice."
  [descriptor]
  #?(:clj  (error/throw-error!
            :rf.error/ui-lease-unavailable 're-frame.ui/lease
            (str "(lease " (pr-str descriptor) ") — leases land with the S2 "
                 "observation slice")
            {:extra {:descriptor descriptor}})
     :cljs (runtime/lease* descriptor)))

;; ---------------------------------------------------------------------------
;; Interop compile forms — recognized by the analyzer in templates; the
;; fn definitions below give them var identity (resolution) + honest
;; direct-call behaviour outside templates.
;; ---------------------------------------------------------------------------

(defn raw
  "(ui/raw react-element) — embed an existing React element (child
  position; SSR paths need a client-only sibling fallback). On the JVM a
  rendered ui/raw child raises :rf.error/jvm-host-op."
  [x]
  #?(:clj  (tree/jvm-host-op! :ui/raw "(ui/raw ...) is a host React element")
     :cljs x))

(defn html
  "(ui/html string) — trusted markup, low-friction: the visible call
  marks the one place escaping is bypassed. S1 grammar: the SOLE child of
  a DOM element ([:div (ui/html s)]) — both emitters treat it
  identically. Direct JVM calls build the trusted-HTML node."
  [s]
  #?(:clj  (tree/html s)
     :cljs (error/throw-error!
            :rf.error/ui-tree-malformed 're-frame.ui/html
            (str "(ui/html ...) is a template form — it renders as the sole "
                 "child of a DOM element: [:div (ui/html s)]")
            {:extra {:value s}})))

(defn raw-fn
  "(ui/raw-fn f) — identity-as-protocol callback marker: the fn passes
  through to the host verbatim (also the explicit callback-ref form). In
  the JVM tree it appears as the opaque :ui/raw-fn marker."
  [f]
  f)

(defn spread
  "(ui/spread base overrides) — the ONE generic runtime prop-map
  conversion, legal in a DOM element's props position:
  [:div.card (ui/spread base {:class \"x\"})]. A template form — the
  compiler routes it through the rule table; calling it directly is an
  error by design."
  ([_base] (spread nil nil))
  ([_base _overrides]
   (error/throw-error!
    :rf.error/ui-spread-outside-template 're-frame.ui/spread
    (str "(ui/spread ...) is a template form — it is legal only in a DOM "
         "element's props position, where the compiler wires it through "
         "the conversion rule table")
    nil)))
