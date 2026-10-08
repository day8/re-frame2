(ns re-frame.routing-scroll-record-bounded-cljs-test
  "The always-on `:rf.error/unsupported-scroll-strategy` record is
  STRUCTURAL and BOUNDED.

  The record rides `dispatch-on-error!`, which passes the positional `:event`
  through `elision/elide-wire-value` but merges `record-attrs` UNCHANGED, and
  the listener registry is production-surviving and not privacy-gated. A
  `:rf.route/navigate` call's `:scroll` opt is per-call runtime data that on a
  schemas-less host may be any map, string or collection, so a copy of it in
  `record-attrs` (or a `:reason` interpolating `(pr-str strategy)`) would ride
  off-box whole, with no bound. The record therefore carries the supported
  vocabulary, the recovery, a constant `:reason` and the value's SHAPE, never
  the value; the raw value rides only the dev trace.

  Whether the record survives production is
  `re-frame.routing-scroll-always-on-elision-prod-test`'s job."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!)
                (rf.routing.scroll/reset-cache!))}))

(defn- reject!
  "Drive the handler's default branch with `strategy` (optionally under an
  originating `event`) and return the single always-on record."
  ([strategy] (reject! strategy nil))
  ([strategy event]
   (let [records (atom [])]
     (rf.error-emit/register-error-listener! :bounded.scroll/recorder
                                             (fn [record] (swap! records conj record)))
     (rf.routing.scroll/scroll-fx-handler
       (cond-> {:frame :rf/default} event (assoc :event event))
       {:strategy strategy})
     (let [errs (filterv #(= :rf.error/unsupported-scroll-strategy (:error %)) @records)]
       (is (= 1 (count errs))
           "exactly one always-on record — the rejection still fires")
       (first errs)))))

;; Every key and every leaf embeds `sentinel`, so a copy of any FRAGMENT of
;; the rejected value shows up in a substring search of the serialized record.
(def ^:private sentinel "ZZQQ-secret-payload-marker")

(defn- adversarial-strategy
  "A large, deeply nested runtime value of `width` top-level keys."
  [width]
  (into {}
        (map (fn [i]
               [(keyword (str sentinel "-key-" i))
                {:nested {:deep [{:leaf (str sentinel "-leaf-" i)}
                                 #{(str sentinel "-set-" i)}]}
                 :blob   (str/join "" (repeat 40 sentinel))}]))
        (range width)))

(defn- record-size
  "Serialized size of the record. `:time` is left out: it is a float whose
  printed length varies run to run."
  [record]
  (count (pr-str (dissoc record :time))))

(deftest always-on-record-is-structural
  (testing "the record names the supported vocabulary, the recovery, a
            constant reason and the value's shape, and still attributes the
            originating navigation through the elided `:event`"
    (is (= {:supported     [:top :restore :preserve]
            :recovery      :no-scroll
            :strategy-type :map
            :reason        rf.routing.scroll/unsupported-strategy-reason
            :frame         :rf/default
            :event         [:test/navigate-somewhere 42]
            :event-id      :test/navigate-somewhere}
           (select-keys (reject! (adversarial-strategy 50) [:test/navigate-somewhere 42])
                        [:supported :recovery :strategy-type :reason :frame :event :event-id])))))

(deftest the-record-bound-holds-across-escalation-recovery-and-re-escalation
  (testing "small -> HUGE -> small -> HUGER: the record is one size at every
            step, under a fixed ceiling, and carries no fragment of the value"
    (let [records (mapv #(reject! (adversarial-strategy %)) [1 400 1 2000])
          sizes   (mapv record-size records)]
      (is (apply = sizes) (str "record sizes: " sizes))
      (is (<= (first sizes) 600) "far below anything that could carry a payload")
      (is (not-any? #(str/includes? (pr-str %) sentinel) records)))))
