(ns re-frame.freehand.bench.b1-react
  "B1's React arm — what the interpreted React emitter builds, counted
  exactly.

  [[re-frame.freehand.bench.b1]] measures the STRUCTURAL walk and
  publishes a byte reading beside its timings, because the JVM hands it a
  monotonic per-thread allocation counter: a difference across a measured
  region is exactly the bytes that region allocated, whether or not a
  collection ran in the middle. That counter is what makes a change to
  the structural walk attributable — the numbers move, and they move
  because of the change.

  ClojureScript has no such counter. Node's
  `process.memoryUsage().heapUsed` reports heap OCCUPANCY: a collection
  between two readings makes the difference smaller than the bytes really
  allocated, and can make it negative. So the React emitter had a wall
  clock and a byte reading that could not attribute anything, and
  candidate changes to [[re-frame.freehand.react]] could be neither
  shipped on evidence nor rejected on it — which, under a posture that
  forbids unmeasured micro-optimization, means they could only be
  declined.

  This arm supplies the missing half. It renders one finite template
  through the emitter and reports two EXACT counts of what the walk
  built:

    - `:B1/react-elements` — the React elements the render answered;
    - `:B1/react-props` — the props those elements carry, `children`
      excluded.

  Both are deterministic properties, so the harness gates them — and it
  decides that from the observable rather than from anything asserted
  here ([[re-frame.freehand.bench.measure]]). Both are also the
  denominators the interesting changes are actually about: elements for
  anything per-element in the walk, props for anything per-attribute.

  ## Why a count read off the OUTPUT is a count of the work

  The emitter answers every element it creates — the walk builds nothing
  it then discards — so the elements reachable from the value
  [[re-frame.freehand.react/element]] returns are exactly the
  `react/createElement` calls it made. Nothing is patched, wrapped or
  instrumented to learn that: the count is read from the ordinary return
  value through `react/isValidElement`, which is why an arm that measures
  the emitter needs no change to it.

  ## What this arm deliberately does NOT measure

  React itself. No root is created, nothing mounts, nothing reconciles,
  and there is no DOM. D021 warns that React reconciliation or a foreign
  widget may dominate and that the report must not attribute that cost to
  Hiccup interpretation; the way not to attribute it is not to include
  it.

  The template is plain markup with no declared view, so there is no
  ViewCell and no shell commit either. That is forced rather than chosen:
  a declared boundary becomes a React component whose body runs later,
  inside React, under its own candidate — so [[element]] over a boundary
  answers ONE element, and an arm built on one would be measuring a
  template it never walked.

  The heap reading rides along as `:B1/react-heap-delta-bytes`, published
  as evidence and gating nothing. It is the reading this arm exists
  because of: put its spread beside the two counts' and the reason a byte
  number cannot attribute a change on this host is on the page rather
  than in a paragraph.

  ## The small case beside the stress case

  Two registered workloads over one template and one `:run`, differing
  only in the `:rows` fixture parameter — D021 requires a small realistic
  case beside each stress case, so optimization is not tuned only to
  synthetic extremes. It also makes the counters' DISCRIMINATION
  structural: the two workloads gate against different WRITTEN
  expectations, so a counter that stopped moving with the workload it
  counts would fail one of them.

  Registered where it can run. `clojure -M:bench` is a JVM process and
  [[re-frame.freehand.react]] is ClojureScript-only, so this namespace is
  not in that CLI's require list; it registers itself at load exactly
  like every other workload, into the suite the ClojureScript host runs.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react" :as react]
            [goog.object :as gobj]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.react :as emitter]))

;; ---------------------------------------------------------------------------
;; The template
;; ---------------------------------------------------------------------------

(defn- noop
  "One shared handler for every row.

  A closure minted per row would be real allocation the walk did not do —
  it would be the fixture's, billed to the emitter. The arm measures what
  the emitter does with a handler, not what building handlers costs."
  [_]
  nil)

(defn- row
  "One row of the template: a keyed element carrying every attribute
  shape the walk treats differently — `.class` sugar composed with a
  vector `:class`, a `:style` map, a `data-*` name that passes through
  verbatim, an `:on-*` handler, and a `:key` — over a void child, an
  element child, a number child, and a nil that is dropped."
  [i]
  [:li.row {:key        i
            :class      ["cell" "wide"]
            :style      {:padding-left 4 :color "rebeccapurple"}
            :data-index i
            :on-click   noop}
   [:img.avatar {:src "/avatar.png" :alt ""}]
   [:span.label "row "]
   i
   nil])

(defn template
  "The finite template, at `rows`.

  Ordinary markup: an element with sugar and an attribute, a childless
  void element, a fragment, and a list whose rows arrive as a SPLICED
  SEQ — the shape a view body actually produces — so the arm walks the
  branches the emitter really has rather than the one it is easiest to
  count.

  Public because the arithmetic below is a claim about THIS markup at any
  size, and a check that could not render it at any size would be a check
  of the two sizes the workloads happen to register."
  [rows]
  [:section.panel {:aria-label "bench rows"}
   [:h1.title "Rows"]
   [:hr.rule]
   [:<>
    [:p.note "a fragment's children are elements too"]
    nil]
   [:ul#list.rows {:role "list"}
    (map row (range rows))]])

;; ---------------------------------------------------------------------------
;; The template's shape, as arithmetic
;; ---------------------------------------------------------------------------

(defn expected-elements
  "The exact number of React elements the template answers for `rows`.

  Stated as arithmetic rather than measured, so the gate is against a
  WRITTEN expectation instead of against whatever the walk happened to
  produce — a gate that expects what it saw is not a gate.

  Six for the skeleton — the section, the heading, the rule, the
  fragment and its paragraph, and the list — then three per row: the row
  element, its image and its label. Text and numbers are children but not
  elements, and the `nil` in each row is dropped, so neither appears
  here."
  [rows]
  (+ 6 (* 3 rows)))

(defn expected-props
  "The exact number of props those elements carry for `rows`, `children`
  excluded.

  Eight for the skeleton: the section's composed class and its
  `aria-label`, the heading's class, the rule's class, the paragraph's
  class, and the list's class, id and role. The fragment carries none.

  Then eight per row: the row's class, style, `data-index` and handler —
  four, not five, because React lifts `:key` out of props onto the
  element — the image's class, source and alt text, and the label's
  class."
  [rows]
  (+ 8 (* 8 rows)))

;; ---------------------------------------------------------------------------
;; Counting what the emitter answered
;; ---------------------------------------------------------------------------

(defn- own-prop-count
  "The props on one element, `children` excluded.

  `children` is React's own slot for the varargs the emitter spread into
  `createElement`, not something the attribute walk wrote, so counting it
  would make the props total a restatement of the element total."
  [props]
  (- (alength (js/Object.keys props))
     (if (gobj/containsKey props "children") 1 0)))

(defn- tally
  "Fold `x` into `[elements props]`.

  `x` is a React element, a JS array of children, or a leaf React treats
  as text — a string, here, since the walk has already rendered numbers
  through the shared number grammar. Only elements are counted; only
  elements are descended into."
  [[elements props :as acc] x]
  (cond
    (array? x)
    (reduce tally acc x)

    (react/isValidElement x)
    (let [p (.-props x)]
      (tally [(inc elements) (+ props (own-prop-count p))]
             (gobj/get p "children")))

    :else acc))

(defn counts
  "Answer `{:elements n :props n}` for the value the emitter answered.

  Public because the discrimination claim is about this function as much
  as about the workloads: a counter nobody can call over a DIFFERENT tree
  is a counter nobody can show moves."
  [element]
  (let [[elements props] (tally [0 0] element)]
    {:elements elements :props props}))

;; ---------------------------------------------------------------------------
;; One iteration
;; ---------------------------------------------------------------------------

(defn observe
  "Run one iteration over `fixture` and answer its observations.

  The two clock readings bracket the EMIT and nothing else. The tally
  runs outside them and the heap readings outside those, so neither the
  counting nor the cost of taking a reading is billed to the walk —
  which is the same boundary discipline B1's structural arm keeps, and
  the reason the two arms' self times mean the same kind of thing."
  [{:keys [rows]}]
  (let [h0      (m/allocated-bytes)
        t0      (m/now-ms)
        element (emitter/element (template rows))
        t1      (m/now-ms)
        h1      (m/allocated-bytes)
        {:keys [elements props]} (counts element)]
    {:B1/react-elements         elements
     :B1/react-props            props
     :B1/react-self-ms          (- t1 t0)
     :B1/react-per-element-ms   (/ (- t1 t0) elements)
     :B1/react-heap-delta-bytes (- h1 h0)}))

;; ---------------------------------------------------------------------------
;; The workloads
;; ---------------------------------------------------------------------------

(defn workload
  "Build the React arm for `rows`, under `id` and `sampling`."
  [{:keys [id doc rows sampling]}]
  {:id       id
   :doc      doc
   :fixture  {:rows     rows
              :elements (expected-elements rows)
              :props    (expected-props rows)}
   :baseline {:kind :before-vs-after
              :reference
              {:revision :the-revision-before-the-change
               :note     (str "the same template's element and prop counts on the preceding "
                              "revision — equal counts are what make a movement in the timing "
                              "attributable to the change rather than to a different tree")}}
   :sampling sampling
   :measurements
   [{:id         :B1/react-elements
     :doc        "the exact React elements one render of the template answered"
     :observable :count
     :expect     (expected-elements rows)}
    {:id         :B1/react-props
     :doc        "the exact props those elements carry, children excluded"
     :observable :count
     :expect     (expected-props rows)}
    {:id         :B1/react-self-ms
     :doc        "self time for one interpreted emit of the template"
     :observable :duration-ms}
    {:id         :B1/react-per-element-ms
     :doc        "self time divided by the element count — cost per element"
     :observable :duration-ms}
    {:id         :B1/react-heap-delta-bytes
     :doc        "node heap occupancy across one emit — published, and attributable to nothing"
     :observable :bytes}]
   :run observe})

(def workloads
  "The React arm's registered workloads: the stress case in the band D021
  names for B1, and the small realistic case it requires beside it."
  [(workload {:id       :B1/react-template
              :doc      "the interpreted React emitter builds a finite 10^3-element template"
              :rows     400 ; 1206 elements — inside D021's 10^3-10^4 band
              :sampling {:warmup 5 :samples 40}})
   (workload {:id       :B1/react-small-template
              :doc      "the same template at a realistic small size — the control on the stress case"
              :rows     6
              :sampling {:warmup 5 :samples 40}})])

(def registered
  "Registering at load: requiring this namespace puts the React arm in
  the standing evidence suite the ClojureScript host runs."
  (mapv bench/register! workloads))
