(ns re-frame.freehand.bench.b6-floor
  "B6's React FLOOR — the same three pages, built by hand with
  `react/createElement` and no substrate at all.

  The single most useful instrument in this comparison is an arm that is
  not an arm. It has no boundary, no ViewCell, no reactive wrapper, no
  props map, no attribute walk and no conversion table; it has no state,
  no events and no identity, so nobody could ship it. That is the point.
  It is the irreducible cost of asking React to build this page, so
  `freehand − floor` and `reagent − floor` are the substrates' OWN
  overheads rather than two numbers dominated by a reconciliation both
  pay identically.

  It is also the calibrator. This box drifts — the predecessor report
  watched the floor move 37% across five rounds on an arm no change in it
  could touch — so absolute milliseconds are not comparable between
  rounds and every comparative figure B6 publishes is a ratio to the
  floor measured IN THAT SAME RUN.

  The floor is gated on canonical-DOM equality with every other arm, so a
  hand transcription that drifted fails rather than flatters itself.

  ## The update floor is NOT a lower bound, and the report says so

  On mount, the floor is genuinely a floor: nothing can build this DOM
  with less work than a bare `createElement` walk. On UPDATE it is
  something else — a plain top-down React re-render, which is what an
  application with no reactive substrate at all actually costs. A
  fine-grained substrate can and should beat it on a narrow write, and
  when it does the ratio drops below 1.0. That is a real result about
  fine-grained reactivity, not an instrument fault, and it is reported as
  such.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react" :as react]))

(defn- el
  "`react/createElement` under a shorter name, with the children already
  in an array."
  ([tag props] (react/createElement tag props))
  ([tag props children] (react/createElement tag props children)))

;; ---------------------------------------------------------------------------
;; W1 — the large template
;; ---------------------------------------------------------------------------

(def ^:private row-style
  #js {:paddingLeft "4px" :color "rebeccapurple"})

(defn- w1-row-el [i]
  (el "li" #js {:key i :className "row cell wide" :style row-style :data-index i}
      #js [(el "img" #js {:key "a" :className "avatar" :src "/avatar.png" :alt ""})
           (el "span" #js {:key "b" :className "label"} "row ")
           (el "em" #js {:key "c" :className "n"} (str i))]))

(defn w1
  "The large template, floor arm."
  [rows]
  (el "section" #js {:className "panel" :aria-label "bench rows"}
      #js [(el "h1" #js {:key "h" :className "title"} "Rows")
           (el "ul" #js {:key "l" :className "rows" :role "list"}
               (into-array (map w1-row-el (range rows))))]))

;; ---------------------------------------------------------------------------
;; W2 — the boundary storm, with no boundaries
;; ---------------------------------------------------------------------------

(defn w2
  "300 leaves, floor arm. The floor has no component per leaf — that IS
  the elision the compiled tier has to prove its way to, handed over for
  free because a hand-written arm never created the wrapper in the first
  place."
  [n]
  (el "div" #js {:className "storm"}
      (into-array
        (map (fn [i] (el "span" #js {:key i :className "leaf"} "leaf")) (range n)))))

;; ---------------------------------------------------------------------------
;; W3 — the ordinary form
;; ---------------------------------------------------------------------------

(defn- w3-field-el [i value error]
  (el "div" #js {:key i :className "field"}
      #js [(el "label" #js {:key "l" :className "lbl" :htmlFor (str "f" i)}
               (str "Field " i))
           (el "input" #js {:key      "i"
                            :className "inp"
                            :id       (str "f" i)
                            :name     (str "f" i)
                            :type     "text"
                            :value    value
                            :readOnly true})
           (el "p" #js {:key "p" :className "err"} error)]))

(defn w3
  "The 12-field form, floor arm."
  [fields]
  (el "form" #js {:className "w3form"}
      #js [(el "fieldset" #js {:key "f" :className "fields"}
               (into-array
                 (map (fn [i]
                        (w3-field-el i
                                     (str "value " i)
                                     (if (even? i) "" (str "field " i " is required"))))
                      (range fields))))
           (el "button" #js {:key "b" :className "submit" :type "submit" :disabled true}
               "Submit")]))

;; ---------------------------------------------------------------------------
;; U — the update witness
;; ---------------------------------------------------------------------------

(defn u-grid
  "The update grid over an explicit `cells` vector. There is no state and
  no hook: an update is the caller re-rendering the whole root with a new
  vector, which is precisely the top-down re-render this arm is here to
  price."
  [cells]
  (el "div" #js {:className "ugrid"}
      (into-array
        (map-indexed
          (fn [i v] (el "span" #js {:key i :className "cell" :data-i i} (str v)))
          cells))))
