(ns re-frame.freehand.bench.b4-instrument
  "B4's counters — the four per-keystroke quantities, and nothing else.

  D021's B4 row gates EXACT per-keystroke event, write,
  subscription-recompute and render counts. Three of them need a place to
  be counted from, and that place has to be reachable from a view body
  and from a subscription handler alike, in both lowerings, without either
  arm carrying a different instrument from the other. Hence one namespace
  the twin view files both require under the same alias, so the
  declarations they hold are identical text.

  The fourth quantity needs nothing here at all: an app-db WRITE is
  `re-frame.frame/frame-commit-epoch`, the framework's own monotonic count
  of physical frame-state installs. Counting writes by instrumenting the
  handler would count handler calls — which is the event count again,
  wearing another name."
  (:require [clojure.string :as str]))

(defonce ^:private tallies (atom {}))

(defn zero!
  "Zero every counter. Called immediately before a measured keystroke, so
  a count is of THAT keystroke and not of the mount that preceded it.

  Not named `reset!`: this namespace would then shadow `clojure.core`'s
  and the body below would call itself."
  []
  (reset! tallies {})
  nil)

(defn bump!
  "Record one occurrence of `k` and answer nil."
  [k]
  (swap! tallies update k (fnil inc 0))
  nil)

(defn tally
  "How many times `k` has occurred since the last [[reset!]]."
  [k]
  (get @tallies k 0))

(defn snapshot
  "Every counter, as data — what a published record carries."
  []
  @tallies)

(defn rendered!
  "Record one invocation of the `id` view's body, and answer the name it
  renders under.

  A plain function: it owns no subscription, no occurrence and no
  memoization, so calling it is not a reactive site and cannot change the
  ViewCell verdict of the body that calls it. The return value reaches
  the DOM as an ordinary attribute, which is what lets a reader see WHICH
  view an element came from without a seam into the runtime."
  [id]
  (bump! [:render id])
  (name id))

(defn scale-reading
  "The honest per-keystroke SCALE, as a line of prose over two measured
  sizes — never a budget.

  D021's B4 gates the counts; how they GROW is a reading taken from the
  gated counts at two fixture sizes, and it is published rather than
  asserted. A per-keystroke count that grows with the number of mounted
  subscriptions is a real fact about the substrate, and pretending a
  threshold could be set on it is how a benchmark becomes folklore."
  [rows]
  (str/join
    "; "
    (for [{:keys [what small large small-size large-size]} rows]
      (str what ": " small " at " small-size " -> " large " at " large-size
           (cond
             (= small large)                  " (flat)"
             (zero? small)                    " (grew from zero)"
             :else (str " (x" (/ (double large) small) ")"))))))
