(ns day8.re-frame2-xray.static-shape-parity-cljs-test
  "Shape parity for the STATIC catalogue fixture family (rf2-y8doi.28).

  The Static Flows, Schemas and Interceptors tabs read registries, and their
  suites feed hand-typed registries through the
  `:rf.xray.static.{flows,schemas,interceptors}/set-*-override-for-test`
  seams (`sample-flows`, `sample-registry`, `sample-events-with-chains`).
  The REAL side here registers one flow, one app-db schema, one event and
  one sub for real, once, and reads each back from the store the panel
  reads in production.

  WHY THIS ASSERTS NO PHANTOM KEYS RATHER THAN EQUAL KEY SETS. A registry
  entry's key set is its declared metadata plus what the registrar stamps
  per registration — source coords (`:ns`, `:file`, `:line`, `:column`),
  `:handler-fn`, `:rf.handler/source` — which a fixture has no reason to
  type and the panels treat as optional (a missing coord hides the jump
  chip). So equality would fail on every fixture for reasons that are not
  defects. What IS a defect is a fixture key the registry never carries:
  the panel then reads a slot production never fills. Every such key is
  caught here.

  `.cljs` rather than `.cljc`: the three fixtures live in node-lane suites,
  so there is nothing for the JVM gate to compare them against."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.static.flows.panel-cljs-test :as flows-suite]
            [day8.re-frame2-xray.static.interceptors.panel-cljs-test :as interceptors-suite]
            [day8.re-frame2-xray.static.schemas.panel-cljs-test :as schemas-suite]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- phantom-keys
  "The keys `fixtures` carry that `real` does not."
  [real fixtures]
  (set/difference (into #{} (mapcat keys) fixtures) (set (keys real))))

(deftest static-catalogue-fixtures-carry-only-registry-keys
  (testing "every key the Static suites type into a registry entry is a key
            the real registry carries there"
    (rf/reg-flow ::flow {:doc "d" :inputs [[:a]] :output-path [:b]} (fn [a] a))
    (rf/reg-app-schema [::slot] {:doc "d"} :int)
    (rf/reg-event ::event {:doc "d" :schema [:tuple :keyword]} (fn [{:keys [db]} _] {:db db}))
    (rf/reg-sub ::sub {:doc "d" :schema :int} (fn [_ _] 1))
    (let [flow     (get-in (rf.flows/flows-snapshot) [:rf/default ::flow])
          app      (get (rf.schemas/app-schemas {:frame :rf/default}) [::slot])
          event    (get (rf/registrations {:source :store :kind :event}) ::event)
          sub      (get (rf/registrations {:source :store :kind :sub}) ::sub)
          registry schemas-suite/sample-registry
          chains   (vals interceptors-suite/sample-events-with-chains)]
      (is (every? some? [flow app event sub])
          "PRECONDITION: each real registration was read back")
      (is (= #{} (phantom-keys flow (mapcat vals (vals flows-suite/sample-flows)))))
      (is (= #{} (phantom-keys app (mapcat vals (vals (:schemas-by-frame registry))))))
      (is (= #{} (phantom-keys event (vals (:events registry)))))
      (is (= #{} (phantom-keys sub (vals (:subs registry)))))
      (is (= #{} (phantom-keys event chains)))
      (is (= #{} (phantom-keys (first (:interceptors event)) (mapcat :interceptors chains))))
      (is (= #{:event/kind} (phantom-keys event [{:interceptors [] :event/kind :db}]))
          "control: the retired `:event/kind` sub-tag is caught as a phantom key"))))
