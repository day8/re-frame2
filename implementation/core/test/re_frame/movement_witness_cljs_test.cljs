(ns re-frame.movement-witness-cljs-test
  "The implementor obligations of `re-frame.movement/IMovementWitness`
  (Spec 006 §Movement witness), pinned on its one implementor, the React-hook
  spine's derived container: W1 FRESHNESS — answer `no-witness` until a
  movement completes, and again once an input change is observed; W2
  SOUNDNESS — the witness is the value the container moved away from."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.disposable :as rf.disposable]
            [re-frame.movement :as rf.movement]
            [re-frame.substrate.spine :as rf.substrate.spine]))

;; A bare spine adapter: nothing mounts and no frame exists; the container
;; fns are driven directly.
(def ^:private adapter
  (rf.substrate.spine/make-react-adapter
    (rf.substrate.spine/make-react-spine
      {:substrate-name        "movement-witness"
       :gensym-prefix-sub     "mw-sub-"
       :gensym-prefix-derived "mw-derived-"
       :gensym-prefix-use-sub "mw-use-sub-"
       :use-memo              (fn [t _] (t))
       :use-callback          (fn [t _] t)
       :use-context           (fn [_] nil)})
    {:kind :rf.adapter/movement-witness :frame-provider nil}))

(defn- make-state-container [v] ((:make-state-container adapter) v))
(defn- make-derived-value [sources f] ((:make-derived-value adapter) sources f))
(defn- replace-container! [c v] ((:replace-container! adapter) c v))

(deftest a-fresh-derived-container-answers-no-witness
  (let [derived (make-derived-value [(make-state-container {:n 1})] :n)]
    (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived)))
    (is (= 1 @derived))
    (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived))
        "a read is not a movement")))

(deftest an-observed-input-change-retracts-the-witness
  (let [src     (make-state-container {:n 1})
        derived (make-derived-value [src] :n)]
    (is (= 1 @derived))
    (replace-container! src {:n 2 :other :a})
    (is (= 1 (rf.movement/-moved-from derived))
        "a real move arms the witness with the value departed from")
    ;; Moves the source but leaves this projection `rf=`-equal.
    (replace-container! src {:n 2 :other :b})
    (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived))
        "the observed input change retracted the witness, and the no-move
         recompute did not re-arm it")
    (is (= 2 @derived))))

(deftest dispose-retracts-the-witness-and-releases-the-predecessor
  (let [v0      {:n 1}
        src     (make-state-container v0)
        derived (make-derived-value [src] identity)]
    (is (= v0 @derived))
    (replace-container! src {:n 2})
    (is (identical? v0 (rf.movement/-moved-from derived))
        "after a movement the witness IS the predecessor object")
    (rf.disposable/-dispose derived)
    (is (identical? rf.movement/no-witness (rf.movement/-moved-from derived)))))
