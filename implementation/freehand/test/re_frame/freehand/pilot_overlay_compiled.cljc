(ns re-frame.freehand.pilot-overlay-compiled
  "The CASE B pilot's overlay markup, PROMOTED — and the exact shape of
  what could not be.

  Promotion is a one-line change to a declaration, so the honest way to
  prove it is to promote the declaration and render both. [[panel]] is the
  pilot's overlay markup with `{:compiled true}` added and nothing else
  changed: the anchor, the promoted panel, the desired-state property and
  both event sites.

  What is NOT here is the behavior boundary the pilot's real dropdown
  wraps this markup in — and its absence is now a matter of FOCUS, not a
  limit. `[v/behavior {…} node]` compiles: the analyzer lowers it to the
  grammar's own `:op :behavior`, not a foreign component, so the
  measure-then-place half of the composition promotes as cleanly as the
  markup does. `pilot-overlay-parity-cljs-test` proves the body WITH the
  behavior compiles too, so this namespace keeps to the markup and lets
  the suite carry the boundary.

  A separate namespace, because a view id is derived from where a
  declaration LIVES: two declarations of one name cannot share one."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.web :as web]))

(v/defview panel
  "The pilot's overlay markup, compiled."
  {:compiled true
   :props    [:map
              [:open? :boolean]
              [:control :some]]}
  [{:keys [open? control]}]
  [:div {:data-component "acme/dropdown"
         :data-part      "root"
         :style          {:display "inline-block" :position "relative"}}
   [:button {:data-part     "anchor"
             :type          "button"
             :aria-expanded (if open? "true" "false")
             :on-click      [:acme.ui.dropdown/anchor-clicked control]}
    "Export as…"]
   [:div {:data-part          "panel"
          :role               "listbox"
          :popover            :auto
          ::web/popover-open? open?
          :on-toggle          [:acme.ui.dropdown/toggle-reported control ::v/new-state]
          :style              {:position "fixed"
                               :inset    "auto"
                               :top      "var(--acme-anchor-y)"
                               :left     "var(--acme-anchor-x)"
                               :margin   0}}
    "PDF"]])
