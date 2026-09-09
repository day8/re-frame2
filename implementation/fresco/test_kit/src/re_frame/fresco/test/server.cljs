(ns re-frame.hicasso.test.server
  "THE DETERMINISM PROBE over `re-frame.hicasso.server/render` — a test
  kit door, in its own namespace because it is the one kit namespace
  that requires the server module, and with it `react-dom/server`.

      (:require [re-frame.hicasso.test.server :as ts])

      (let [{:keys [identical? differs-at]} (ts/render-twice opts)]
        (is identical? (str \"the render is not deterministic at \" differs-at)))

  A view reading `Date.now`, minting a random id, or printing the
  per-request frame id produces a document that differs run to run, and
  hydration then reports the difference as a mismatch on somebody else's
  machine. This probe is where that is caught: on the server, in a test,
  before a page ships. It used to sit on the product door as
  `server/render-twice` (naming-ledger row 50); it moved here under
  rf2-6c12m.15 because nothing a running host does calls it.
  Design record: docs/design/hicasso/product/naming-ledger.md row 50."
  (:require [re-frame.hicasso.server :as rf.hicasso.server]))

(defn render-twice
  "`server/render` the same `opts` twice and compare the two documents
  byte-for-byte. Answers `{:first :second :identical? :differs-at}`: the
  two full `render` results, whether their `:document`s are equal, and
  the index of the first differing character (`nil` when identical) so a
  red run is diagnosable. The two renders take two different gensym
  frame ids, which is what makes this the standing proof that the
  per-request id is invisible on the wire."
  [opts]
  (let [a (rf.hicasso.server/render opts)
        b (rf.hicasso.server/render opts)
        x (:document a)
        y (:document b)]
    {:first      a
     :second     b
     :identical? (= x y)
     :differs-at (when (not= x y)
                   (let [n (min (count x) (count y))]
                     (loop [i 0]
                       (cond
                         (= i n)                            n
                         (not= (.charAt x i) (.charAt y i)) i
                         :else                              (recur (inc i))))))}))
