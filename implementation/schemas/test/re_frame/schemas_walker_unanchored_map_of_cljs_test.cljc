(ns re-frame.schemas-walker-unanchored-map-of-cljs-test
  "A mark below a `:map-of` the walk reaches before any named slot is written
  without the key it sits under, and a declared path rides a map key only once
  its first named segment has matched, so no extracted path reaches it. The
  completeness hook the off-box HTTP stamp and the invalid-params projection
  consult reports such a schema, beside the opacity it already reports. A named
  slot or a pinned position above the `:map-of` anchors the mark, and the
  opacity predicate the validation surfaces read is unchanged."
  (:require
   #?(:clj  [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer-macros [deftest is]])
   [re-frame.late-bind :as rf.late-bind]
   ;; load-bearing: binds the `:schemas/*` walker hooks.
   [re-frame.schemas :as rf.schemas]))

(def ^:private token [:map [:id :int] [:token {:sensitive? true} :string]])

(def ^:private unanchored
  "Schemas whose marks sit below a `:map-of` no named slot anchors."
  [[:map-of :string token]
   [:map-of :keyword [:map [:blob {:large? true} :string]]]
   [:vector [:map-of :string token]]
   [:map-of :string [:map-of :string token]]
   [:maybe [:map-of :string token]]
   [:or :nil [:map-of :string token]]
   [:orn [:none :nil] [:some [:map-of :string token]]]
   [:map-of :string [:tuple :int token]]])

(def ^:private complete
  "Schemas whose every mark an extracted path reaches."
  [[:map [:accounts [:map-of :string token]]]
   [:vector token]
   [:tuple [:map-of :string token]]
   [:map-of :string :int]
   [:map-of :string [:string {:sensitive? true}]]
   [:map-of {:sensitive? true} :string :int]])

(defn- marks-incomplete? [schema]
  ((rf.late-bind/get-fn :schemas/schema-has-opaque-child?) schema))

(deftest a-mark-below-an-unanchored-map-of-makes-the-marks-incomplete
  (doseq [s unanchored]
    (is (true? (marks-incomplete? s)) (pr-str s)))
  (doseq [s complete]
    (is (false? (marks-incomplete? s)) (pr-str s))))

(deftest the-hook-still-reports-opacity
  (is (true? (marks-incomplete? [:map [:creds [:ref :app/creds]]]))))

(deftest validation-keeps-an-unanchored-map-of-walkable
  (doseq [s unanchored]
    (is (false? (rf.schemas/schema-has-opaque-child? s)) (pr-str s))))
