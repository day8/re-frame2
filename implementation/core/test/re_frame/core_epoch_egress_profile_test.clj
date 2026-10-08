(ns re-frame.core-epoch-egress-profile-test
  "The core facade wrapper `rf/project-egress` honours the named
  `:rf.egress/profile` selector and its override layer, not just the
  unqualified `:include-*` opts.

  The profile rows drive a SYNTHETIC epoch record so they run under the
  production gate too: the epoch ring is fed from the dev trace stream and is
  empty there, and asserting on a nil record would pass vacuously. The dev-only
  arm checks that a real captured record has the shape the synthetic one
  assumes."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.projection :as rf.projection]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; publishes the `:epoch/*` hooks the core wrappers delegate to
            [re-frame.epoch]
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-large-path! [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:large [[:blob :payload]]})))
  nil)

(defn- big-string [n] (apply str (repeat n "X")))

(defn- synthetic-record
  "A hand-built epoch record of the shape the ring holds. The `:kind` stamp is
  load-bearing: without it `project-egress` walks the map from `:path []`, the
  frame's `[:blob :payload]` large path never matches, and the payload egresses
  raw."
  [frame-id payload]
  {:kind      :rf/epoch-record
   :frame     frame-id
   :db-before {:blob {:payload nil}}
   :db-after  {:blob {:payload payload}}})

(defn- large-marker-body
  "The `:rf.size/large-elided` marker body at `[:db-after :blob :payload]` of a
  projected record, or nil if the slot is not a marker."
  [record]
  (let [slot (get-in record [:db-after :blob :payload])]
    (when (rf.elision/marker? slot)
      (:rf.size/large-elided slot))))

(deftest core-project-egress-honors-egress-profile
  (rf/make-frame {:id :ep/main})
  (install-large-path! :ep/main)
  (rf/reg-event :store
                (fn [{:keys [db]} [_ payload]]
                  {:db (assoc-in db [:blob :payload] payload)}))
  (rf/dispatch-sync [:store (big-string 50000)] {:frame :ep/main})
  (let [synth        (synthetic-record :ep/main (big-string 50000))
        default-body (large-marker-body (rf/project-egress synth))
        obs-body     (large-marker-body
                       (rf/project-egress
                         synth {:rf.egress/profile :rf.egress/off-box-observability}))
        tool-body    (large-marker-body
                       (rf/project-egress
                         synth {:rf.egress/profile :rf.egress/off-box-tool}))]
    (is (some? default-body) "the default boundary elides the large slot")
    (is (= default-body obs-body) "the 1-arity default is off-box-observability")
    (is (not (contains? obs-body :digest)))
    (is (= tool-body obs-body) "the tool boundary shares observability's size floor")
    (is (= 50000 (count (get-in (rf/project-egress
                                  synth {:rf.egress/profile :rf.egress/local-raw})
                                [:db-after :blob :payload])))
        "local-raw ships the raw value")
    (is (string? (:digest (large-marker-body
                            (rf/project-egress
                              synth {:rf.egress/profile          :rf.egress/off-box-tool
                                     :rf.egress/include-digests? true}))))
        "the digest overlay composes on the tool profile"))
  (when rf.interop/debug-enabled?
    (let [raw (last-record :ep/main)]
      (is (some? (large-marker-body (rf/project-egress raw)))
          "a real captured record has the synthetic record's shape")
      (is (some? (large-marker-body
                   (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool})))))))

(deftest core-project-egress-rejects-unknown-profile
  (rf/make-frame {:id :ep/main})
  (let [data (try (rf/project-egress (synthetic-record :ep/main "v")
                                     {:rf.egress/profile :rf.egress/not-real})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :rf.error/unknown-egress-profile (:rf.error/id data)))
    (is (= (ex-data (rf.projection/unknown-egress-profile-ex
                      'rf/project-egress :rf.egress/not-real))
           data)
        "the shape is the shared builder's")))
