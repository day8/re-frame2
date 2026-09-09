(ns app.suggested
  "The suggested declaration (design §7.6) — the report's one piece of
  forward-looking advice, and the one thing in it a migrator is invited to
  PASTE.

  So the sketch has to be acceptable to the door VERBATIM, and rf2-vi11
  found that it was not: it printed `:fn` at every position, which is not
  one of the three contracts `defhost` accepts, and it built the name
  position by lower-casing whatever text the head happened to be — which
  for a string head produced `(h/defhost \"button\" \"button\" …)`, a form
  the reader refuses before the door ever sees it.

  This case exists to put the head shapes in front of `sketch_test.clj`,
  which reads every sketch the corpus emits and round-trips it."
  (:require [reagent.core :as r]))

(defn a-symbol-head []
  ;; The ordinary case: `Btn` names the host `btn`.
  [:> Btn {:on-pick (fn [x] x)}])

(defn a-qualified-and-dotted-head []
  ;; `js/Foo.Bar` names the host `bar` — the last segment. A NAME is free
  ;; to derive because nobody's behaviour rides on it; a CONTRACT is not.
  [:> js/Foo.Bar {:on-change (fn [e] e) :on-render-row (fn [row] row)}])

(defn an-expression-head []
  ;; No segment `def` would take, so the name position is left as an
  ;; obvious placeholder rather than mangled into a plausible one.
  [:> (.-Provider ctx) {:on-close (fn [] nil)}])

(defn a-string-head-is-a-native-tag []
  ;; W6 rewrites this to `[:input …]`. There is no host here to declare,
  ;; so the report suggests nothing — a native tag never took a `defhost`.
  [:input {:on-change (fn [e] e)}])
