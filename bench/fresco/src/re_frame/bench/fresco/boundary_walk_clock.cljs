(ns re-frame.bench.fresco.boundary-walk-clock
  "THE BOUNDARY HAND-OFF WALK, CLOCKED ON THE SHIPPING CODEC.

  Every other arm in this lane times a local copy of the runtime. This
  one requires `re-frame.fresco.impl.codec` itself, so its figures are
  the codec in the tree at the time of the run: to price a change to the
  hand-off, run it before the change and again after, and compare the
  two tables.

  ## What a row is

  One PARENT RENDER's element build: `rf.fresco.impl.codec/as-element` over the hiccup a
  parent body returns, with every boundary head memoised the way
  `defview` memoises one. Nothing is mounted, so no child body runs —
  which is exactly a child whose memo bails out, the case where the
  hand-off walk is the only work a forwarded collection causes.

  - **forward** — `[a {:rows rows}]`, one hand-off.
  - **forward x3** — a parent passing the same collection to three
    children, three hand-offs in one render.
  - **forward + children** — one hand-off whose `:children` carry hiccup,
    so a contract that walks `:children` alone still has a walk to price.
  - **dogfood row** — `{:id :title :done?}`, the small-props shape, so a
    change that saves on collections is seen not to cost on the common
    small call.
  - **walk only** — `rf.fresco.impl.codec/realize-deep` over the collection, for
    attribution: the share of a forward row that is the walk.

  Rows are vectors of `{:id :title :done?}` maps — a collection read out
  of a subscription, already realised, which is the case the walk costs
  and cannot repair.

  ## The clock

  `performance.now` over `reps` builds, `rounds` rounds with the shapes
  interleaved inside each round so drift, GC and JIT land on every shape,
  after one discarded warm-up round. Microseconds per parent render,
  median and best of the rounds. A diagnostic, not a gate row: nothing
  here is a threshold."
  (:require [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]))

(def ^:private reps 400)
(def ^:private rounds 15)

(defn- mint
  "A boundary head as `defview` mints one: marked, and given its stable
  memo wrapper. Its body never runs here."
  [nm]
  (let [f (fn [_js-props] nil)]
    (unchecked-set f "displayName" nm)
    (rf.fresco.impl.codec/memoize-boundary! (rf.fresco.impl.codec/mark-boundary! f))))

(def ^:private a (mint "boundary-walk-clock/a"))
(def ^:private b (mint "boundary-walk-clock/b"))
(def ^:private c (mint "boundary-walk-clock/c"))

(defn- rows [n]
  (mapv (fn [i] {:id i :title (str "Row " i) :done? (odd? i)}) (range n)))

(def ^:private sink
  "Every build's result lands here, so no optimiser can prove a build dead."
  #js [nil])

(defn- shapes
  "Each shape is a thunk building one parent render's hiccup FRESH, as a
  body does, then lowering it."
  [n]
  (let [rs (rows n)]
    [[(str "forward, " n " rows")
      (fn [] (rf.fresco.impl.codec/as-element [a {:rows rs}]))]
     [(str "forward x3, " n " rows")
      (fn [] (rf.fresco.impl.codec/as-element [:div [a {:rows rs}] [b {:rows rs}] [c {:rows rs}]]))]
     [(str "forward + children, " n " rows")
      (fn [] (rf.fresco.impl.codec/as-element [a {:rows rs} [:span "one"] [:span "two"]]))]
     [(str "walk only, " n " rows")
      (fn [] (rf.fresco.impl.codec/realize-deep rs))]]))

(def ^:private dogfood
  ["dogfood row {:id :title :done?}"
   (fn [] (rf.fresco.impl.codec/as-element [a {:id 7 :title "milk" :done? false}]))])

(defn- now [] (.now js/performance))

(defn- clock
  "`reps` runs of `f`, in microseconds per run."
  [f]
  (let [t0 (now)]
    (dotimes [_ reps] (aset sink 0 (f)))
    (/ (* 1000 (- (now) t0)) reps)))

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (if (odd? n)
      (nth v (quot n 2))
      (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2))))

(defn -main [& _]
  (let [all (into [dogfood] (concat (shapes 100) (shapes 1000)))]
    (doseq [[_ f] all] (clock f))
    (let [samples (reduce (fn [acc _]
                            (reduce (fn [acc [label f]] (update acc label (fnil conj []) (clock f)))
                                    acc all))
                          {} (range rounds))]
      (println (str ";; boundary hand-off walk — goog.DEBUG=" ^boolean js/goog.DEBUG
                    ", " reps " builds/round, " rounds " interleaved rounds. "
                    "Microseconds per parent render."))
      (println "")
      (println "| shape | median (µs) | best (µs) |")
      (println "|---|---|---|")
      (doseq [[label _] all]
        (let [xs (get samples label)]
          (println (str "| " label " | " (.toFixed (median xs) 2)
                        " | " (.toFixed (apply min xs) 2) " |"))))
      (println "")
      (println ";; DIAGNOSTIC — not a gate row, not a published figure."))))
