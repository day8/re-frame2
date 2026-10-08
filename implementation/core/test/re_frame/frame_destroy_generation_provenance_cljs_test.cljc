(ns re-frame.frame-destroy-generation-provenance-cljs-test
  "`destroy-frame!` releases the frame's generation-provenance row in
  `re-frame.live-frame`: the descriptor pool its current generation resolved
  against (nil for the live store, the caller's pool for the 2-arity). Every
  public `make-frame` writes one, so a row left behind leaks one key per frame,
  and on the 2-arity the caller's whole pool. The per-request SSR recipe makes
  and destroys a fresh frame per request.

  Each case shows the row was present while the frame lived before showing it
  is gone, so a `make-frame` that stopped recording cannot pass. Presence is
  key membership: an ordinary frame's recorded pool is nil."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core       :as rf]
            [re-frame.frame      :as rf.frame]
            [re-frame.image      :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; the fixture leaves the provenance table alone, so each case takes its own
;; baseline
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- provenance
  "The private generation-provenance table, as a plain map."
  []
  (deref @#'rf.live-frame/frame-generation-pool))

(defn- row?
  "Does the table carry a row for `id`?"
  [id]
  (contains? (provenance) id))

(defn- live?
  [id]
  (contains? (set (rf.frame/frame-ids)) id))

(defn- reg-desc
  "A synthetic registered descriptor authored in `provenance-ns`."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(deftest destroy-frame-releases-explicit-pool-provenance-across-incarnations-rf2-cq0yi
  ;; two same-id incarnations with distinct pools: a stale destroy of A must not
  ;; strip successor B's row
  (let [id     :cq0yi-explicit/main
        pool-a [(reg-desc "cq0yi-explicit.core" :event :cq0yi-explicit/inc ::a)]
        pool-b [(reg-desc "cq0yi-explicit.core" :event :cq0yi-explicit/inc ::b)]
        img    (rf.image/image {:id        :cq0yi-explicit/img
                                :select-ns {:include ["cq0yi-explicit.core"]}})
        a      (rf/make-frame {:id id :images [img]} pool-a)]
    (is (identical? pool-a (get (provenance) id)) "control: A's row is A's exact pool")
    (rf/destroy-frame! a)
    (is (= [false false] [(row? id) (live? id)]) "destroying A releases its row")
    (let [b (rf/make-frame {:id id :images [img]} pool-b)]
      (rf/destroy-frame! a)
      (is (= [true true] [(live? id) (identical? pool-b (get (provenance) id))])
          "a stale destroy of A leaves B live with its own row")
      (rf/destroy-frame! b)
      (is (= [false false] [(row? id) (live? id)])))))

(deftest destroy-frame-returns-provenance-table-to-baseline-under-churn-rf2-cq0yi
  ;; the failure is unbounded growth, so the whole table's size is pinned both ways
  (let [n        100
        baseline (count (provenance))
        frames   (vec (repeatedly n #(rf/make-frame {})))]
    (is (= (+ baseline n) (count (provenance))) "control: one row per live frame")
    (run! rf/destroy-frame! frames)
    (is (= baseline (count (provenance))))))
