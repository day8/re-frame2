(ns re-frame.freehand.bench.b6-uix
  "B6's mount witnesses, declared in UIX — the third substrate, and the
  one whose position the comparison predicts rather than discovers.

  The hypothesis under test is structural: UIx's `$` is a MACRO that
  expands to `react/createElement` at compile time, which puts it in the
  same position as Freehand's compiled tier — the markup is resolved
  before the browser sees it, so there is no runtime walk to pay for. If
  that is right, the UIx arm should sit near the floor on every mount
  witness.

  It should NOT inherit the compiled tier's boundary-storm win, because
  UIx has no elision concept: a `defui` is a React function component and
  stays one. W2's contrast between compiled Freehand and UIx is therefore
  the cleanest reading in the report of what elision is worth as
  distinct from what compilation is worth.

  The `$` spelling is the whole point of including this arm. The UIx
  adapter also exposes `set-hiccup-emitter!`, and an arm written through
  that would be measuring a hiccup interpreter again — the opposite of
  the hypothesis.

  MOUNT ONLY. UIx's update story is `use-state` and React's own
  scheduler, which is a different mechanism from a reactive substrate's
  and would need its own row to be measured honestly; the report says so
  rather than quietly extrapolating.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [uix.core :refer [$ defui]]))

;; ---------------------------------------------------------------------------
;; W1 — the large template
;; ---------------------------------------------------------------------------

(defui w1-row [{:keys [i]}]
  ($ :li.row.cell.wide {:style      {:padding-left "4px" :color "rebeccapurple"}
                        :data-index i}
     ($ :img.avatar {:src "/avatar.png" :alt ""})
     ($ :span.label "row ")
     ($ :em.n (str i))))

(defui w1 [{:keys [rows]}]
  ($ :section.panel {:aria-label "bench rows"}
     ($ :h1.title "Rows")
     ($ :ul.rows {:role "list"}
        (for [i (range rows)]
          ($ w1-row {:key i :i i})))))

;; ---------------------------------------------------------------------------
;; W2 — the boundary storm
;; ---------------------------------------------------------------------------

(defui w2-leaf [_]
  ($ :span.leaf "leaf"))

(defui w2 [{:keys [n]}]
  ($ :div.storm
     (for [i (range n)]
       ($ w2-leaf {:key i}))))

;; ---------------------------------------------------------------------------
;; W3 — the ordinary form
;; ---------------------------------------------------------------------------

(defui w3-field [{:keys [i value error]}]
  ($ :div.field
     ($ :label.lbl {:for (str "f" i)} (str "Field " i))
     ($ :input.inp {:id        (str "f" i)
                    :name      (str "f" i)
                    :type      "text"
                    :value     value
                    :read-only true})
     ($ :p.err error)))

(defui w3 [{:keys [fields]}]
  ($ :form.w3form
     ($ :fieldset.fields
        (for [i (range fields)]
          ($ w3-field {:key   i
                       :i     i
                       :value (str "value " i)
                       :error (if (even? i) "" (str "field " i " is required"))})))
     ($ :button.submit {:type "submit" :disabled true} "Submit")))
