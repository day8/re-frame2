(ns re-frame.freehand.custom-element-views
  "The declared views FH-STRUCT-011 renders — the `v/custom-element`
  classification matrix, in both execution modes.

  ## Why the modes sit in ONE namespace

  The compiled twins elsewhere in this corpus live in a sibling namespace
  because their fixtures pin whole trees and the arm rewrites the
  `:view-id`s onto the twin. Here the modes are named PAIRWISE instead —
  `panel-literal` beside `panel-literal-compiled` — and each fixture case
  pins its own view-id. The reason is the subject: the law under test is
  that ONE declaration classifies a prop the same way on every lowering
  path, so the paths have to be nameable INDIVIDUALLY. A rewriting arm can
  say the two modes agree; it cannot say which of the four paths the
  compiled arm actually took.

  ## The tags

  One declaration per tag, all from this single source — the cross-source
  law forbids two SOURCES declaring one tag differently, and a source
  declaring several tags is ordinary.

  - `:ce-panel`   the everyday case: three declared properties beside
                  undeclared names, and one property whose value is a MAP
                  (legal only because it is declared).
  - `:ce-attrish` a declared name that is ALSO a standard HTML attribute
                  spelling. The declaration is the sole classifier, so
                  `:tab-index` is the JS property here and not `tabindex`.
  - `:ce-below`   declared BELOW the views that use it — the harvester's
                  reason to exist. Macros expand top to bottom, so without
                  the source harvest a view above its declaration would
                  bake the ATTRIBUTE lowering and the same two forms
                  reversed would bake the property one.
  - `:ce-plain`   declared NOWHERE: the all-attributes default.

  Every value is data — no callbacks, no host objects — so the trees these
  views produce round-trip as EDN and the fixture can pin them literally."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; The everyday case, and the paths a prop can arrive by
;; ---------------------------------------------------------------------------

(v/custom-element :ce-panel {:properties #{:accent-color :help-text :scale}})

;; `:scale` carries a MAP. That is the whole reason the declaration exists:
;; a property's meaning belongs to the element's own setter, so a map, a
;; vector or a host object is legal there and refused as an attribute.
(def panel-props
  {:accent-color "blue" :help-text "h" :scale {:min 0 :max 10}
   :data-x "d" :label "lab"})

(v/defview panel-literal [_]
  [:ce-panel {:accent-color "blue" :help-text "h" :scale {:min 0 :max 10}
              :data-x "d" :label "lab"}])

(v/defview panel-literal-compiled {:compiled true} [_]
  [:ce-panel {:accent-color "blue" :help-text "h" :scale {:min 0 :max 10}
              :data-x "d" :label "lab"}])

;; The RUNTIME seam. `v/spread` hands the canonicaliser a map the compiler
;; never saw, so the declaration's runtime arm is the only one that can
;; classify it — in an interpreted body and beside a compiled element alike.
(v/defview panel-spread [_]
  [:ce-panel (v/spread panel-props)])

(v/defview panel-spread-compiled {:compiled true} [_]
  [:ce-panel (v/spread panel-props)])

;; `v/spread-safe`: the component owns `:label`, the CALLER forwards a
;; declared property and an undeclared name. The bound is unchanged — owned
;; props win, the caller's survivors fold under them — and a forwarded
;; DECLARED name is still a property.
(v/defview panel-spread-safe [{:keys [caller]}]
  [:ce-panel (v/spread-safe {:label "owned"} caller)])

;; A conditional property is the same law a conditional attribute is: the
;; nil-is-absent rule drops it before classification, so the property marker
;; names what the element actually CARRIES.
(v/defview panel-nil-property [_]
  [:ce-panel {:accent-color nil :help-text "h" :data-x "d"}])

;; ---------------------------------------------------------------------------
;; The declaration is the sole classifier
;; ---------------------------------------------------------------------------

(v/custom-element :ce-attrish {:properties #{:tab-index}})

(v/defview attrish [_]
  [:ce-attrish {:tab-index "3" :role "note"}])

;; ---------------------------------------------------------------------------
;; An UNDECLARED element is all-attributes
;; ---------------------------------------------------------------------------

(v/defview undeclared [_]
  [:ce-plain {:accent-color "x" :data-y "y"}])

;; ---------------------------------------------------------------------------
;; Declaration ORDER — the views above their own declaration
;; ---------------------------------------------------------------------------

(v/defview below-literal [_]
  [:ce-below {:model "m" :data-x "d"}])

(v/defview below-literal-compiled {:compiled true} [_]
  [:ce-below {:model "m" :data-x "d"}])

(v/defview below-spread [_]
  [:ce-below (v/spread {:model "m" :data-x "d"})])

(v/custom-element :ce-below {:properties #{:model}})

;; ---------------------------------------------------------------------------
;; The roster the fixture addresses views by
;; ---------------------------------------------------------------------------

(def by-name
  "Fixture view keyword -> the declared view. The fixture names a path; this
  map is the only place a name is bound to a declaration."
  {:panel-literal          panel-literal
   :panel-literal-compiled panel-literal-compiled
   :panel-spread           panel-spread
   :panel-spread-compiled  panel-spread-compiled
   :panel-spread-safe      panel-spread-safe
   :panel-nil-property     panel-nil-property
   :attrish                attrish
   :undeclared             undeclared
   :below-literal          below-literal
   :below-literal-compiled below-literal-compiled
   :below-spread           below-spread})
