(ns re-frame2-pair-mcp.dedup-benchmark-test
  "Structural-dedup compression on a high-share trace burst: one cascade
  replayed many times, so every replay's events are structurally
  identical to the canonical cascade's. That is the regime dedup targets;
  the measured ratio is ~10.8x."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- mk-high-share-burst
  "`n-cascades` replays of one `cascade-width`-event cascade: the deduper
  sees `n-cascades` x `cascade-width` references against only
  `cascade-width` distinct subtrees."
  [n-cascades cascade-width]
  (let [handler-id      :app.events/poll-tick
        trigger-handler {:kind         :event
                         :id           handler-id
                         :source-coord {:ns     'app.events
                                        :file   "src/app/events.cljs"
                                        :line   42
                                        :column 3}}
        canonical       (vec
                          (for [i (range cascade-width)]
                            {:operation                (if (zero? i) :rf.event/dispatched :rf.fx/handled)
                             :op-type                  (if (zero? i) :rf.event :rf.fx)
                             :tags                     {:frame             :rf/default
                                                        :rf.trace/event-id handler-id
                                                        :effect-key        (keyword (str "fx-" i))}
                             :rf.trace/trigger-handler trigger-handler}))]
    (vec (mapcat (fn [_] canonical) (range n-cascades)))))

(deftest day8-de-dupe-high-share-burst-compression-factor
  (let [payload (mk-high-share-burst 100 24)
        wrapped (rf.mcp-base.dedup/dedup-value payload true)
        ratio   (/ (count (pr-str payload)) (count (pr-str wrapped)))]
    (is (>= ratio 8.0)
        (str "high-share burst ratio " (.toFixed ratio 2) "x fell below the 8x floor"))
    (is (= payload (tu/dedup-expand wrapped)))))
