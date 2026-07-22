(ns re-frame.freehand.crossing-views
  "The declarations the `FH-CALL-004` and `FH-CALL-005` rows mount — the
  four mode pairings of a parent and its child, and the `v/markup`
  boundary.

  The four parents below differ in exactly two places: whether the
  declaration carries `{:compiled true}`, and whether the child it mounts
  is [[re-frame.freehand.tree-views/row]] or its promoted twin
  [[re-frame.freehand.compiled-views/row]]. The BODY is the same
  characters in all four. That is the whole experimental design: if a
  crossing were visible in the output, these four would not agree, and
  they are asserted against ONE pinned tree.

  The child pair is reused rather than redeclared because those two
  declarations are already proven to be twins — `compiled-source-delta`
  reads both files and pins the option map as the only textual
  difference — so a crossing suite that redeclared them would be
  asserting against look-alikes it had to trust.

  Declared here rather than inside a suite for the reason `tree-views`
  is: a `:view-id` names the namespace a declaration lives in, and a
  fixture pins trees literally."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.compiled-views :as compiled]
            [re-frame.freehand.tree-views :as views]))

;; ---------------------------------------------------------------------------
;; The 2 x 2 — parent mode x child mode
;; ---------------------------------------------------------------------------

(v/defview interpreted-parent-interpreted-child
  "The baseline: neither side compiled, so nothing crosses."
  [{:keys [items]}]
  [:ul.rows (for [i items] [views/row {:key i :label i}])])

(v/defview interpreted-parent-compiled-child
  "An interpreted parent mounting a COMPILED descriptor. The parent's walk
  reaches an ordinary vector head and mounts it; that the child resolved
  its own structure at build time is the child's business, and the walk
  has no way to ask."
  [{:keys [items]}]
  [:ul.rows (for [i items] [compiled/row {:key i :label i}])])

(v/defview compiled-parent-interpreted-child
  "A compiled parent mounting a statically named INTERPRETED descriptor —
  D010's escape, and the reason the escape is worth naming: it has to
  actually work, or 'extract a declared child, it may stay interpreted'
  is advice to nowhere."
  {:compiled true}
  [{:keys [items]}]
  [:ul.rows (for [i items] [views/row {:key i :label i}])])

(v/defview compiled-parent-compiled-child
  "Both sides compiled — the case with no crossing at all, present so the
  other three are compared against a control rather than only against
  each other."
  {:compiled true}
  [{:keys [items]}]
  [:ul.rows (for [i items] [compiled/row {:key i :label i}])])

(def by-name
  "Fixture view-name keyword -> the declared parent. A fixture is EDN, so
  it names a view rather than carrying one."
  {:interpreted-parent-interpreted-child interpreted-parent-interpreted-child
   :interpreted-parent-compiled-child    interpreted-parent-compiled-child
   :compiled-parent-interpreted-child    compiled-parent-interpreted-child
   :compiled-parent-compiled-child       compiled-parent-compiled-child})

;; ---------------------------------------------------------------------------
;; `v/markup` — markup already in hand, crossing at a declared boundary
;; ---------------------------------------------------------------------------

(v/defview markup-host
  "A COMPILED parent handing a value it cannot see through to the one
  declared interpreted child D010 names. `value` arrives as a prop here so
  one declaration drives every value shape the fixture carries; in real
  source it is the return of a helper the analyzer cannot see through,
  which is the same thing from the compiler's side."
  {:compiled true}
  [{:keys [value]}]
  [:section.host [v/markup {:value value}]])

(v/defview markup-rows
  "ONE lexical crossing site that mounts many times — the distinction the
  manifest and the evidence column are for. The manifest names sites; a
  render produces mounts, and a site inside a keyed list produces one per
  row."
  {:compiled true}
  [{:keys [rows]}]
  [:ul.rows (for [r rows] [v/markup {:key (:id r) :value (:value r)}])])

;; ---------------------------------------------------------------------------
;; What the manifest has to be able to say
;; ---------------------------------------------------------------------------

(v/defview mixed-crossings
  "Two crossings in one body, into two different modes. A manifest that
  marked crossings by position, or by the parent's own lowering, or not at
  all, would still satisfy a body with one child; this one it cannot."
  {:compiled true}
  [{:keys [value]}]
  [:section.mixed
   [compiled/row {:label "compiled"}]
   [v/markup {:value value}]])
