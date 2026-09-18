(ns day8.re-frame2-xray.resources-shape-parity-cljs-test
  "Shape parity for the RESOURCES fixture family (rf2-y8doi.28).

  The Resources panel projects the cache entries and work-ledger records
  the resources runtime writes, and its suites hand-type both (the helper
  suite's `entries` / `ledger`, the shapes the `set-resource-*-override`
  seams carry). The REAL side here comes from the runtime's own pure
  constructors, once: `rf.resources.state` walks an entry through
  `empty-entry` → `entry-start-load` → `entry-succeeded`, and
  `rf.resources.work-ledger/work-record` builds a running record, which
  `mark-terminal` settles.

  WHY THIS ASSERTS NO PHANTOM KEYS RATHER THAN EQUAL KEY SETS. The
  defect this family guards against is a fixture carrying a key the
  runtime never writes — a stored `:stale?` where staleness is DERIVED,
  a `:path` where the router writes `:route-id`. Every such key is caught
  here. Equality is the stronger statement and does not hold today: the
  runtime writes every entry slot, nil-valued or not (`:error`,
  `:refresh-error`, `:invalidated-at`, `:current-work`, `:previous-key`,
  `:revision`), and every work record's `:work/frame`, while the hand-typed
  fixtures carry only the facts each row needs. Closing that gap means
  building those fixtures from the constructors above, in suites outside
  this item's reach — recorded on rf2-y8doi.28 rather than done here.

  `.cljc` on purpose: both constructors are pure and run on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.set :as set]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [day8.re-frame2-xray.panels.resources-helpers-cljs-test :as resources-suite]))

(def ^:private scoped-key [[:rf.scope/global] :parity/article {:slug "parity"}])

(def ^:private real-entry
  "A `:loaded` cache entry, as the runtime's constructors build one."
  (-> (rf.resources.state/empty-entry :parity/article scoped-key)
      (rf.resources.state/entry-start-load
        {:generation 1 :work-id [:w 1] :request-id [:w 1] :owner [:route :r "nav-1"]})
      (rf.resources.state/entry-succeeded
        {:data {:title "t"} :loaded-at 1 :stale-at 2 :tags #{}})))

(def ^:private real-record
  "A `:running` work-ledger record, as the runtime's constructor builds one."
  (rf.resources.work-ledger/work-record
    {:work-id [:rf.work/resource scoped-key 1] :frame-id :rf/default
     :resource/key scoped-key :generation 1 :transport :rf.http/managed
     :owner [:route :r "nav-1"] :cause [:route-entry :r "nav-1"]
     :started-at 1 :deadline-at 2}))

(defn- phantom-keys
  "The keys `fixtures` carry that `real` does not."
  [real fixtures]
  (set/difference (into #{} (mapcat keys) fixtures) (set (keys real))))

(deftest resources-suite-fixtures-carry-only-runtime-keys
  (testing "every key the resources suites type into an entry or a work
            record is a key the runtime writes there"
    (let [entries        (vals @#'resources-suite/entries)
          {running  true
           settled false} (group-by #(= :running (:status %))
                                    (vals @#'resources-suite/ledger))]
      (is (and (seq entries) (seq running) (seq settled))
          "PRECONDITION: the suite's fixtures were found")
      (is (= #{} (phantom-keys real-entry entries)))
      (is (= #{} (phantom-keys real-record running)))
      (is (= #{} (phantom-keys (rf.resources.work-ledger/mark-terminal
                                 real-record :completed {:ok true})
                               settled)))
      (is (= #{:stale?} (phantom-keys real-entry [{:status :loaded :stale? true}]))
          "control: a stored derived flag is caught as a phantom key"))))
