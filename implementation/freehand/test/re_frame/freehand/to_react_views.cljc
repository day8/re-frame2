(ns re-frame.freehand.to-react-views
  "The declarations the outward-bridge suites export.

  Ordinary views, deliberately: the whole claim of `v/->react` is that what
  crosses into React-world is a view like any other, so nothing here is
  written for the bridge. `person-cell` reads a subscription, which is how the
  mounted suites prove a frame actually reached the exported subtree; `panel`
  takes children, which is how they prove React content nests inside one; and
  `badge` declares `:children-policy :none`, so a React parent that nests
  content into a view that accepts none meets the view's own declared refusal
  rather than a bridge-specific one."
  (:require [re-frame.freehand :as v]
            ;; The declaration constructor, reached directly for exactly one
            ;; job — see the reload pair at the foot of this file.
            [re-frame.freehand.descriptor :as descriptor]))

(v/defview person-cell
  "A leaf a grid would render in a cell: value props in, markup out."
  {:children-policy :none}
  [{:keys [person-id column-id]}]
  [:span.cell {:data-column (str column-id)} (str "person " person-id)])

(v/defview greeting
  "Reads state, so a render of it proves the exported subtree resolved a
  frame — an exported view that never read anything would render identically
  under the right frame and under none at all."
  {:children-policy :none}
  [{:keys [salutation]}]
  [:p.greeting (str salutation ", " (v/sub [:person/name]))])

(v/defview panel
  "Takes children, so React content nested inside the exported component has
  somewhere to land."
  {:children-policy :optional}
  [{:keys [title children]}]
  [:section.panel [:h2.title title] [:div.body children]])

(v/defview badge
  "Accepts no children at all — the declared policy the bridge does not
  soften."
  {:children-policy :none}
  [{:keys [label]}]
  [:em.badge label])

;; ---------------------------------------------------------------------------
;; A hot reload, spelled the way one actually happens
;; ---------------------------------------------------------------------------
;;
;; A reload re-evaluates a declaration and rebinds its var to a FRESH descriptor
;; carrying the same qualified id and a different body. That is the whole of
;; what the exported component's cache key has to survive, and it cannot be
;; simulated by two differently-named views: the id is the axis, so two ids
;; would prove the opposite thing.
;;
;; Writing the declaration twice would produce exactly the pair — and a
;; `being replaced` warning on every build of this suite, which is noise a
;; reader has to learn to ignore. So the second generation is minted from the
;; first's own public projection with a different body, through the internal
;; constructor a declaration itself expands to. It is the same value a reload
;; produces: same qualified id, same source, same lowering, same children
;; policy, new render body, different object.

(v/defview reloadable
  {:children-policy :none}
  [{:keys [person-id]}]
  [:span.cell (str "person " person-id)])

(def generation-1
  "The descriptor the declaration above bound."
  reloadable)

(def generation-2
  "The descriptor a reload of that declaration would bind — same view id, a
  body whose output moved, and a genuinely different object."
  (descriptor/declare-view
    (assoc (select-keys (v/describe generation-1)
                        [:view-id :source :lowering :children-policy])
           :render (fn [{:keys [person-id]}]
                     [:span.cell (str "PERSON " person-id)]))))
