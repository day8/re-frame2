(ns re-frame.facade-internal-constructors-cljs-test
  "`rf/capture-frame` is the facade's carry primitive (the lowering
  constructors behind it live in their owning namespaces): its handle carries
  the frame and the three frame-locked operations."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]))

(deftest capture-frame-remains-the-supported-carry-primitive
  (let [handle (rf/capture-frame :rf/default)]
    (is (= #{:frame :dispatch :dispatch-sync :subscribe} (set (keys handle))))
    (is (= :rf/default (:frame handle)))
    (is (every? fn? (map handle [:dispatch :dispatch-sync :subscribe])))))
