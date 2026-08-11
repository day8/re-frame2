(ns re-frame.bench.p0-floor
  "EP-0038 P0 — the React FLOOR: the same three pages built by hand with
  `react/createElement`, no substrate, no subscription, no boundary.

  The floor is not a candidate and nobody could ship it. It exists to be
  the CALIBRATOR, and in this arm it carries a second job the predecessor's
  floor did not have.

  ## Why P0 cannot be measured without it

  A like-for-like P0 needs Reagent reading re-frame2 subscriptions AND UIx
  reading re-frame2 subscriptions. Those are two different installed
  adapters, and `install-adapter!` is once-per-process (Spec 006 §Single
  adapter per process) — so the two arms cannot be interleaved inside one
  round the way two arms on one adapter can. They are measured in two
  SEGMENTS of the same round, in the same page, in the same process, with
  the adapter destroyed and re-installed between them.

  The floor runs in BOTH segments. It is identical work in both, it holds
  no re-frame state and it is untouched by which adapter is installed, so
  a difference between its two segment readings in one round is drift
  between the segments and nothing else — and every published figure is a
  ratio to the floor measured in the SAME segment of the SAME round. That
  is what makes a UIx-over-Reagent ratio legitimate across the seam:
  neither number is an absolute millisecond, and the seam cancels
  PROVIDED that drift is common-mode.

  A round in which the floor's two segments disagree by more than the
  stated tolerance is reported as such; it is the seam's own control.

  ## The bulk floor is NOT a lower bound

  On mount the floor is a genuine floor — nothing builds this DOM with
  less work than a bare `createElement` walk. On BULK it is a plain
  top-down React re-render, which is what an application with no reactive
  substrate costs. A fine-grained arm can and should beat it on the narrow
  row, and a ratio below 1.0 there is a real result about localisation,
  not an instrument fault.

  Owner: the operator-owned governance set that superseded rf2-2rtt6.1 on
  2026-08-10, enumerated once in `docs/design/hicasso/studio/README.md`;
  this arm rf2-2rtt6.4."
  (:require ["react" :as react]
            [re-frame.bench.p0-fixture :as fx]))

(defn- el
  ([tag props] (react/createElement tag props))
  ([tag props children] (react/createElement tag props children)))

;; ---------------------------------------------------------------------------
;; W1 — the large template
;; ---------------------------------------------------------------------------

(def ^:private row-style #js {:paddingLeft "4px" :color "rebeccapurple"})

(defn- w1-row-el [i text]
  (el "li" #js {:key i :className "row cell wide" :style row-style :data-index i}
      #js [(el "img" #js {:key "a" :className "avatar" :src "/avatar.png" :alt ""})
           (el "span" #js {:key "b" :className "label"} "row ")
           (el "em" #js {:key "c" :className "n"} text)]))

(defn w1
  "The large template, floor arm. Takes the row texts as a plain vector —
  the same values the `:p0/row` subscription answers, handed over directly
  because the floor has no subscription to read them through."
  [rows]
  (el "section" #js {:className "panel" :aria-label "bench rows"}
      #js [(el "h1" #js {:key "h" :className "title"} "Rows")
           (el "ul" #js {:key "l" :className "rows" :role "list"}
               (into-array (map-indexed w1-row-el rows)))]))

;; ---------------------------------------------------------------------------
;; W3 — the ordinary form
;; ---------------------------------------------------------------------------

(defn- w3-field-el [i {:keys [value error]}]
  (el "div" #js {:key i :className "field"}
      #js [(el "label" #js {:key "l" :className "lbl" :htmlFor (str "f" i)}
               (str "Field " i))
           (el "input" #js {:key       "i"
                            :className "inp"
                            :id        (str "f" i)
                            :name      (str "f" i)
                            :type      "text"
                            :value     value
                            :readOnly  true})
           (el "p" #js {:key "p" :className "err"} error)]))

(defn w3
  "The 12-field form, floor arm."
  [fields]
  (el "form" #js {:className "w3form"}
      #js [(el "fieldset" #js {:key "f" :className "fields"}
               (into-array (map-indexed w3-field-el fields)))
           (el "button" #js {:key "b" :className "submit" :type "submit" :disabled true}
               "Submit")]))

;; ---------------------------------------------------------------------------
;; U — the bulk witness
;; ---------------------------------------------------------------------------

(defn u-grid
  "The bulk grid over an explicit `cells` vector. No state and no hook: a
  write is the caller re-rendering the whole root with a new vector, which
  is exactly the top-down re-render this arm is here to price."
  [cells]
  (el "div" #js {:className "ugrid"}
      (into-array
        (map-indexed
          (fn [i v] (el "span" #js {:key i :className "cell" :data-i i} (str v)))
          cells))))

;; ---------------------------------------------------------------------------
;; The positive control — a page of KNOWN relative size
;; ---------------------------------------------------------------------------

(defn w1-double
  "W1 with twice the rows, and nothing else changed.

  This is the clock's POSITIVE CONTROL and it is the only arm here whose
  answer is PREDICTED before it is measured. `w1-elements 600` is 2,403
  against `w1-elements 300`'s 1,203, so a mount window that is measuring
  the construction and commit of this page must read

      w1-double / w1  =  2403 / 1203  =  1.997

  to within the run's own noise. A window that is measuring something else
  — a scheduler hop, a constant, an arm that rendered nothing — has no
  reason to land there, and the fifteen recorded instrument faults on the
  predecessor's harnesses all produced a plausible precise wrong number
  first. A figure nobody can falsify is not a measurement.

  The control is deliberately built on the FLOOR rather than on a
  substrate arm: it must price the instrument, not a substrate, so it
  contains no boundary, no subscription and no adapter, and it therefore
  reads the same in both segments."
  [rows]
  (w1 (into (vec rows) rows)))

(def control-predicted-ratio
  "2403/1203 — the element ratio `w1-double` has to reproduce on the clock.
  Written as arithmetic over the fixture rather than as a literal, so it
  moves if the witness does."
  (/ (double (fx/w1-elements (* 2 fx/w1-rows)))
     (double (fx/w1-elements fx/w1-rows))))
