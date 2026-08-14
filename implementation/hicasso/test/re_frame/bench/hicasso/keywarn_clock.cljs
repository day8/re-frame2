(ns re-frame.bench.hicasso.keywarn-clock
  "THE KEY WARNING'S DEV PRE-PASS, CLOCKED (rf2-2rtt6.104).

  This file exists because of a standing rule and a lane history. The
  rule is [[re-frame.bench.hicasso.front.codec/realize-deep]]'s: figures
  in the codec are **clocked rather than asserted**. The history is
  rf2-2rtt6.32, whose \"impossible result\" is what an unclocked
  micro-claim did in this lane last time. The design that ruled the
  warning derived ~1-4% of dev lowering analytically; that number does
  not go into a docstring without a measurement beside it, so here is the
  measurement.

  ## The ablation

  `codec/as-element` on a seq IS
  [[re-frame.bench.hicasso.front.codec/expand-seq]] — its `(seq? x)`
  branch is `expand-seq`'s sole caller. So the ablated arm is a
  five-line local copy of that loop with the dev pre-pass removed and
  everything else identical, calling the same public `as-element` per
  member. A local copy is this lane's own idiom for an ablation
  (`walk_profile_app` keeps one deliberately); the delta between the two
  arms is the pre-pass and nothing else.

  ## The populations

  Both are 300 members, the tier-1 feed shape the arm is measured on:

  - **keyed** — the steady state, and the expensive one. Every member is
    scanned to the end and rejected on the `:key` read plus one
    `typeof`-class test; nothing early-exits. This is the number that
    matters, because it is what a correct application pays forever.
  - **unkeyed, already warned** — the broken list after its site has
    spoken. Every member reaches [[boundary-head?]] and the dedupe
    `Set.has`, which is the scan's dearest path.

  ## What this is not

  A DIAGNOSTIC, run on demand, not a gate row and not a published
  figure. The clock is in-process `performance.now` over interleaved
  rounds, median-of-rounds; it attributes cost between two arms of one
  walk in one process. Nothing here is a threshold and nothing here is
  compared across runs."
  (:require [re-frame.bench.hicasso.front.codec :as codec]))

(def ^:private members 300)
(def ^:private reps 200)
(def ^:private rounds 11)

(defn- a-row
  "A marked boundary head, stamped the way an arm's mint stamps one."
  [nm]
  (let [f (fn [_js-props] nil)]
    (unchecked-set f "displayName" nm)
    (codec/mark-boundary! f)))

(def ^:private row (a-row "keywarn.clock/row"))

(defn- expand-seq-ablated
  "[[codec/expand-seq]] with the dev pre-pass removed — the shipping loop,
  character for character, minus the one gated line."
  [s]
  (let [a #js []]
    (loop [items (seq s)]
      (when items
        (.push a (codec/as-element (first items)))
        (recur (next items))))
    a))

(defn- now [] (.now js/performance))

(defn- median [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (if (odd? n)
      (nth v (quot n 2))
      (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2))))

(defn- clock
  "`reps` walks of `s` through `f`, in nanoseconds per member."
  [f s]
  (let [t0 (now)]
    (dotimes [_ reps] (f s))
    (/ (* 1e6 (- (now) t0)) (* reps members))))

(defn- interleaved
  "Both arms, alternating, `rounds` times — so drift, GC and JIT warm-up
  land on both arms rather than on whichever ran second."
  [s]
  (let [ship (atom []) abl (atom [])]
    (dotimes [_ rounds]
      (swap! ship conj (clock codec/as-element s))
      (swap! abl conj (clock expand-seq-ablated s)))
    (let [m-ship (median @ship)
          m-abl  (median @abl)]
      {:ship m-ship
       :ablated m-abl
       :pre-pass (- m-ship m-abl)
       :pct (* 100 (/ (- m-ship m-abl) m-ship))})))

(defn- row3 [label {:keys [ship ablated pre-pass pct]}]
  (println (str "| " label
                " | " (.toFixed ship 1)
                " | " (.toFixed ablated 1)
                " | " (.toFixed pre-pass 2)
                " | " (.toFixed pct 2) "% |")))

(defn -main [& _]
  (when-not ^boolean js/goog.DEBUG
    (println ";; REFUSED — this clock only means anything in a DEV build; the")
    (println ";;   pre-pass does not exist under :advanced with goog.DEBUG=false.")
    (js/process.exit 2))
  (let [keyed   (doall (map (fn [i] [row {:key i}]) (range members)))
        unkeyed (doall (map (fn [_] [row {}]) (range members)))]
    ;; Warm the dedupe: the unkeyed row is meant to price the SCAN after the
    ;; site has spoken, not the one console.warn it speaks with.
    (codec/set-lowering-owner! "keywarn.clock/list")
    (codec/as-element unkeyed)
    (codec/set-lowering-owner! nil)
    (println (str ";; keywarn pre-pass — dev build, " members " members, "
                  reps " walks/round, " rounds " interleaved rounds, "
                  "median-of-rounds. ns per member."))
    (println "")
    (println "| population | shipping (ns) | pre-pass ablated (ns) | pre-pass (ns) | share of dev lowering |")
    (println "|---|---|---|---|---|")
    (row3 "keyed (the steady state)" (interleaved keyed))
    (row3 "unkeyed, site already warned" (interleaved unkeyed))
    (println "")
    (println ";; DIAGNOSTIC — not a gate row, not a published figure.")))
