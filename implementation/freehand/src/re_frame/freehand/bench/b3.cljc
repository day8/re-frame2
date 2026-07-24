(ns re-frame.freehand.bench.b3
  "B3 — ten thousand keyed records, a window of about forty, and the exact
  number of rows a mutation touches.

  D021's third required workload: *10,000 keyed rows with `rf=`-stable
  props; repeat with a window of about 40 under updates*, compared
  *generic versus generated comparator*, publishing *parent render time,
  skipped/committed rows, comparator time and end-to-end latency*, to
  decide *whether comparator specialization matters after windowing*.

  The decision that question serves is a spending decision, and it is
  worth stating plainly because it is what keeps this workload honest:
  once a window is in place, the comparator runs over FORTY rows, not ten
  thousand. Whatever specialization buys, it buys it on that forty. So
  B3's gated half is the size of the thing being compared, and its
  published half is what comparing it costs.

  ## What gates, and why these are counts

  `:B3/records` is ten thousand and `:B3/rows-compared` is in the
  hundreds. Both are exact integers; together they ARE the isolation
  claim, and neither is a duration. The rest of the gated half is the
  arithmetic of one scripted mutation sequence:

    - `:B3/committed-rows` — rows the comparator says must re-render,
      summed over the script. A row commits because it ARRIVED in the
      window (it has no counterpart to compare against) or because its
      props changed.
    - `:B3/skipped-rows` — rows present in both windows whose props
      compare `rf=` equal, and which a memoized boundary therefore does
      not re-render.
    - `:B3/comparator-agreement` — the generic and the generated
      comparator named the SAME rows changed, on every step. This is the
      load-bearing half of D021's question: specialization is allowed to
      change what comparing costs and is not allowed to change what
      commits.

  Each is checked against [[expectation]] — combinatorics over the script,
  written without records, props or `rf=` anywhere in it — so the gate
  expects what the script MEANS rather than what the run happened to
  produce.

  ## What is published, and can never gate

  Comparator self time for each arm, parent render time, and the
  end-to-end latency of the whole scripted sequence. Every one is a
  duration, so [[re-frame.freehand.bench.measure]] classes it as a
  distribution and the harness publishes it with no threshold, no
  comparator and no route to the exit code. A B3 that could red on a slow
  comparator would be the folklore threshold D021 forbids, dressed as a
  windowing claim.

  ## The two comparators

  Both are `rf=` per slot — the ruled per-slot equality
  ([[re-frame.freehand.eq]]) — and they differ in exactly one thing,
  which is the thing compilation decides:

    - [[generic-equal?]] knows no slot roster. It walks the union of both
      maps' keys, which is what a props-map view gets: the compiler could
      not name the slots, so the comparator has to discover them.
    - [[generated-equal?]] is straight-line over a WRITTEN roster,
      [[row-slots]] — one `rf=` per declared slot, no key walk, no set
      union. That is the shape `emit-cljs`'s generated comparator has.

  Modelled here rather than harvested from a compiled declaration, and
  said out loud rather than implied: this workload measures the SHAPE of
  the two comparators over the props a windowed table hands them. It does
  not measure the emitted JavaScript, which needs a browser and a mounted
  React tree, and it does not claim to. What it does measure is
  host-neutral, which is why the same numbers are readable from the JVM
  lane and the ClojureScript one.

  ## Keyed, and why that is the whole workload

  Rows are matched between two renders BY KEY, never by position. That is
  what makes a scroll cheap: sliding the window by twenty rows leaves
  twenty rows matched — same key, same props, skipped — and twenty
  arriving with no counterpart. Match by position instead and a
  one-row scroll would commit the entire window. The script below scrolls
  by half a window, back again, and then clean past it, so all three
  cases are in the gated arithmetic rather than in a paragraph.

  ## A small case beside the stress case

  D021: *\"Include at least one small realistic case beside each stress
  case so optimization is not tuned only to synthetic extremes.\"* Hence
  two registered workloads over one script shape and one `:run`, differing
  only in `:total` and `:window` — sizes are fixture parameters, not
  language constants.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.eq :as eq]
            [re-frame.freehand.tree :as tree]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The dataset and the window
;; ---------------------------------------------------------------------------

(defn records
  "`n` generated keyed records.

  Generated rather than fixtured, for the reason ten thousand literal maps
  is not a fixture. Each carries a stable `:id` — the key rows are matched
  by — and one mutable field."
  [n]
  (mapv (fn [i] {:id (str "r" i) :code (str "AC-" i) :amount (* 3 i)})
        (range n)))

(def row-slots
  "The row boundary's prop slots, in declaration order.

  Written once and read twice — by [[record-row]]'s destructuring and by
  [[generated-equal?]] — because the generated comparator's whole nature
  is that it knows this roster before the render, and a roster that could
  drift from the view it compares would be comparing something else."
  [:id :code :amount :index])

(defn window-rows
  "The props of the `window` rows visible from `first-row`, in order.

  `:index` is the record's ABSOLUTE position, so a row that survives a
  scroll carries the same index as well as the same key — which is why a
  scroll's overlapping rows compare equal rather than merely nearly
  equal.

  Public because the isolation claim is about this function as much as
  about the workload: what the comparator sees is exactly what a windowed
  table hands a row boundary."
  [recs first-row window]
  (mapv (fn [i]
          (let [r (nth recs i)]
            {:id (:id r) :code (:code r) :amount (:amount r) :index i}))
        (range first-row (+ first-row window))))

(defn- by-key
  [rows]
  (persistent! (reduce (fn [m r] (assoc! m (:id r) r)) (transient {}) rows)))

;; ---------------------------------------------------------------------------
;; The two comparators
;; ---------------------------------------------------------------------------

(defn generated-equal?
  "The GENERATED comparator: straight-line `rf=` over [[row-slots]].

  What a compiled boundary earns — the slots are known before the render,
  so the comparison is a fixed conjunction with nothing to discover."
  [a b]
  (and (eq/rf= (:id a) (:id b))
       (eq/rf= (:code a) (:code b))
       (eq/rf= (:amount a) (:amount b))
       (eq/rf= (:index a) (:index b))))

(defn generic-equal?
  "The GENERIC comparator: `rf=` over the union of both maps' keys.

  What a boundary with no compile-time slot roster gets. It answers the
  same verdict as [[generated-equal?]] on these props — that agreement is
  gated, not assumed — and it reaches it by discovering the slots on every
  call."
  [a b]
  (let [ks (into (set (keys a)) (keys b))]
    (reduce (fn [_ k] (if (eq/rf= (get a k) (get b k)) true (reduced false)))
            true
            ks)))

;; ---------------------------------------------------------------------------
;; The scripted mutation sequence
;; ---------------------------------------------------------------------------

(defn script
  "The scripted mutation sequence for a fixture, as data.

  Nine steps over three kinds of movement, chosen so every case the
  isolation claim rests on is inside the gated arithmetic:

    - an idle step — nothing changed, so nothing commits;
    - an edit INSIDE the window — exactly one row commits;
    - an edit OUTSIDE the window — nothing commits, which is the
      strongest form of \"the comparison isolates rows\": a mutation to one
      of ten thousand records that the window does not show is not
      compared and is not rendered;
    - a half-window slide and its return — half the window matched by key
      and skipped, half arriving;
    - a jump clean past the window — nothing matched, so the whole window
      commits, which is the honest upper bound windowing does not avoid.

  A function of the fixture rather than a literal, so the small case is
  the same script at a smaller size. Every target is inside the dataset by
  construction: `far` is capped at the last legal first-row, so no step
  needs clamping and the arithmetic below needs no clamp either."
  [{:keys [total window]}]
  (let [far (min (- total window) (* 4 window))]
    [{:op :idle}
     {:op :edit   :row 0}
     {:op :edit   :row (dec total)}
     {:op :scroll :to (quot window 2)}
     {:op :edit   :row (quot window 2)}
     {:op :scroll :to 0}
     {:op :scroll :to far}
     {:op :edit   :row far}
     {:op :idle}]))

;; ---------------------------------------------------------------------------
;; The script's counts, as arithmetic
;; ---------------------------------------------------------------------------

(defn- overlap
  "How many rows two windows of `window` rows, starting at `a` and `b`,
  share — the number of keys present in both, and therefore the number of
  comparisons the next render performs."
  [a b window]
  (let [d (- a b)]
    (max 0 (- window (if (neg? d) (- d) d)))))

(defn- visible?
  [row first-row window]
  (and (<= first-row row) (< row (+ first-row window))))

(defn expectation
  "The exact counts [[script]] produces for `fixture`, stated as
  arithmetic.

      {:compared n :committed n :skipped n :rendered n}

  Written rather than measured, so the gates expect what the script MEANS.
  There is no record, no props map and no `rf=` in this function: it walks
  the script counting window overlaps, which is a claim about the script,
  and the run walks real props with the real comparator, which is a claim
  about the substrate. A gate is only a gate when those two are
  independent.

  Per step, with `W` the window size:

    - the rows both windows hold are `W - |shift|`, floored at zero —
      those are the COMPARISONS;
    - the rest of the new window ARRIVED, has no counterpart, and commits
      without being compared;
    - an edit to a row visible in both windows changes exactly one of the
      compared rows; an edit anywhere else changes none;
    - so `committed = arrivals + changed` and `skipped = compared -
      changed`, and the two always sum to `W`.

  `:rendered` counts the initial window too: the run renders the window
  once before the script starts, because a first render has nothing to
  compare against and would otherwise be an unbounded case hiding inside
  step one."
  [{:keys [window] :as fixture}]
  (let [steps (script fixture)
        seed  {:first-row 0 :compared 0 :committed 0 :skipped 0}
        end   (reduce
                (fn [acc {:keys [op row to]}]
                  (let [p        (:first-row acc)
                        n        (if (= :scroll op) to p)
                        compared (overlap p n window)
                        arrivals (- window compared)
                        changed  (if (and (= :edit op)
                                          (visible? row p window)
                                          (visible? row n window))
                                   1
                                   0)]
                    (-> acc
                        (assoc :first-row n)
                        (update :compared + compared)
                        (update :committed + arrivals changed)
                        (update :skipped + (- compared changed)))))
                seed
                steps)]
    (-> end
        (dissoc :first-row)
        (assoc :rendered (* window (inc (count steps)))))))

;; ---------------------------------------------------------------------------
;; The rendered window
;; ---------------------------------------------------------------------------

(v/defview record-row
  "One windowed row. Its props are exactly [[row-slots]] — the roster the
  generated comparator compares."
  [{:keys [id code amount index]}]
  [:tr.b3-row {:data-row-key id :aria-rowindex (inc index)}
   [:td.b3-cell code]
   [:td.b3-cell (str amount)]])

(v/defview windowed-table
  "The parent. It renders the WINDOW it is handed and knows nothing about
  the collection the window came from — which is the property `:B3/records`
  beside `:B3/rows-compared` states as two integers."
  [{:keys [rows]}]
  [:table.b3-table {:role "grid"}
   [:tbody
    (for [r rows]
      [record-row (assoc r :key (:id r))])]])

(def ^:private row-tag :tr)

(defn rendered-rows
  "The row elements one structural render actually built.

  Counted off the tree rather than off the props vector handed in: the
  claim is that the parent renders the window, and a count of the input
  would be a claim about the input."
  [t]
  (count (filter #(and (map? %) (= row-tag (:tag %)))
                 (tree-seq map? :children t))))

;; ---------------------------------------------------------------------------
;; One step
;; ---------------------------------------------------------------------------

(defn- timed-pass
  "One pass of `equal?` over the rows `prev` and `next-rows` share, timed.

  Answers `{:compared n :equal n :ms ms}`. It counts rather than
  discarding, because a pass whose result is thrown away is a pass a JIT
  may delete — and the number it returns is also the comparison count the
  gate is about. Nothing else happens inside the clock: the key index was
  built before it, and the changed-key set below is collected after it."
  [equal? prev next-rows]
  (let [n  (count next-rows)
        t0 (m/now-ms)]
    (loop [i 0 compared 0 equal 0]
      (if (< i n)
        (let [r (nth next-rows i)
              p (get prev (:id r))]
          (cond
            (nil? p)     (recur (inc i) compared equal)
            (equal? p r) (recur (inc i) (inc compared) (inc equal))
            :else        (recur (inc i) (inc compared) equal)))
        (let [t1 (m/now-ms)]
          {:compared compared :equal equal :ms (- t1 t0)})))))

(defn changed-keys
  "The keys `equal?` says changed between `prev` and `next-rows` — the
  rows a memoized boundary would re-render, named rather than counted.

  Untimed on purpose. The agreement gate needs the identities and the
  published comparator time needs a pass that does nothing but compare, so
  they are two passes rather than one pass doing both."
  [equal? prev next-rows]
  (into #{}
        (comp (filter #(contains? prev (:id %)))
              (remove #(equal? (get prev (:id %)) %))
              (map :id))
        next-rows))

(defn- step-state
  "Apply one script step, answering the records and first row it leaves."
  [{:keys [recs first-row]} {:keys [op row to]}]
  (case op
    :idle   {:recs recs :first-row first-row}
    :edit   {:recs (update-in recs [row :amount] inc) :first-row first-row}
    :scroll {:recs recs :first-row to}))

;; ---------------------------------------------------------------------------
;; One iteration
;; ---------------------------------------------------------------------------

(defn observe
  "Run one B3 iteration over `dataset` and `fixture`, answering its
  observations.

  The dataset arrives as an argument rather than out of the fixture: ten
  thousand records are the thing being windowed, not a parameter a reader
  should have to scroll past in the published record, and regenerating
  them every iteration would bill the generator to the substrate."
  [dataset {:keys [window] :as fixture}]
  (let [steps (script fixture)
        e0    (m/now-ms)
        ;; The initial window. There is nothing to compare it against, so
        ;; it renders and is counted and no comparator runs.
        seed  (window-rows dataset 0 window)
        r0    (m/now-ms)
        tree0 (tree/render [windowed-table {:rows seed}])
        r1    (m/now-ms)
        final (reduce
                (fn [acc step]
                  (let [{:keys [recs first-row]} (step-state (:state acc) step)
                        rows      (window-rows recs first-row window)
                        rt0       (m/now-ms)
                        t         (tree/render [windowed-table {:rows rows}])
                        rt1       (m/now-ms)
                        prev      (:index acc)
                        generated (timed-pass generated-equal? prev rows)
                        generic   (timed-pass generic-equal? prev rows)
                        changed   (changed-keys generated-equal? prev rows)
                        arrivals  (- (count rows) (:compared generated))]
                    (-> acc
                        (assoc :state {:recs recs :first-row first-row}
                               :index (by-key rows))
                        (update :rendered + (rendered-rows t))
                        (update :render-ms + (- rt1 rt0))
                        (update :compared + (:compared generated))
                        (update :committed + arrivals (count changed))
                        (update :skipped + (- (:compared generated) (count changed)))
                        (update :generated-ms + (:ms generated))
                        (update :generic-ms + (:ms generic))
                        (update :agree?
                                #(and % (= changed (changed-keys generic-equal? prev rows)))))))
                {:state     {:recs dataset :first-row 0}
                 :index     (by-key seed)
                 :rendered  (rendered-rows tree0)
                 :render-ms (- r1 r0)
                 :compared  0
                 :committed 0
                 :skipped   0
                 :generated-ms 0.0
                 :generic-ms   0.0
                 :agree?    true}
                steps)
        e1    (m/now-ms)]
    {:B3/records                 (count dataset)
     :B3/rows-rendered           (:rendered final)
     :B3/rows-compared           (:compared final)
     :B3/committed-rows          (:committed final)
     :B3/skipped-rows            (:skipped final)
     :B3/comparator-agreement    (:agree? final)
     :B3/generated-comparator-ms (:generated-ms final)
     :B3/generic-comparator-ms   (:generic-ms final)
     :B3/parent-render-ms        (:render-ms final)
     :B3/end-to-end-ms           (- e1 e0)}))

;; ---------------------------------------------------------------------------
;; The workloads
;; ---------------------------------------------------------------------------

(defn workload
  "Build the B3 workload for `total` records through a `window`, under
  `id` and `sampling`.

  The dataset is built ONCE and captured by `:run`; the fixture carries
  the parameters and the script, which is everything a reader needs to
  re-derive the gated counts and nothing they would have to scroll past."
  [{:keys [id doc total window sampling]}]
  (let [fixture  {:total total :window window :script (script {:total total :window window})}
        expected (expectation fixture)
        dataset  (records total)]
    {:id       id
     :doc      doc
     :fixture  (merge fixture expected)
     :baseline {:kind :interpreted-vs-compiled
                :reference
                {:arm  :generic-comparator
                 :note (str "the comparator a boundary gets when the slots were not known "
                            "before the render — it discovers them on every call, where the "
                            "generated one is a straight line over a roster the compiler "
                            "already had. Both answer the same verdict; only the cost differs, "
                            "and only over the rows the window shows")}}
     :sampling sampling
     :measurements
     [{:id         :B3/records
       :doc        "the keyed records the window is drawn from — the denominator of the isolation claim"
       :observable :count
       :expect     total}
      {:id         :B3/rows-rendered
       :doc        "the row elements every render of the script actually built"
       :observable :count
       :expect     (:rendered expected)}
      {:id         :B3/rows-compared
       :doc        "the comparisons the whole scripted sequence performed — the numerator"
       :observable :count
       :expect     (:compared expected)}
      {:id         :B3/committed-rows
       :doc        "the exact rows the comparator says must re-render across the script"
       :observable :count
       :expect     (:committed expected)}
      {:id         :B3/skipped-rows
       :doc        "the exact rows that compared equal and are skipped across the script"
       :observable :count
       :expect     (:skipped expected)}
      {:id         :B3/comparator-agreement
       :doc        "the generic and generated comparators named the same rows changed, every step"
       :observable :flag
       :expect     true}
      {:id         :B3/generated-comparator-ms
       :doc        "self time of the straight-line comparator over the whole script"
       :observable :duration-ms}
      {:id         :B3/generic-comparator-ms
       :doc        "self time of the key-walking comparator over the whole script"
       :observable :duration-ms}
      {:id         :B3/parent-render-ms
       :doc        "self time of the parent's renders of the window — never of the collection"
       :observable :duration-ms}
      {:id         :B3/end-to-end-ms
       :doc        "the whole scripted sequence, render and comparison together"
       :observable :duration-ms}]
     :run      (fn [f] (observe dataset f))}))

(def workloads
  "B3's registered workloads: D021's ten thousand keyed records through a
  window of about forty, and the small realistic case it requires beside
  it."
  [(workload {:id       :B3/windowed-ledger
              :doc      "10,000 keyed records rendered repeatedly through a window of 40"
              :total    10000
              :window   40
              :sampling {:warmup 5 :samples 40}})
   (workload {:id       :B3/small-windowed-ledger
              :doc      "the same script at a realistic small size — the control on the stress case"
              :total    400
              :window   12
              :sampling {:warmup 5 :samples 40}})])

(def registered
  "Registering at load: requiring this namespace puts B3 in the standing
  evidence suite `clojure -M:bench` runs."
  (mapv bench/register! workloads))
