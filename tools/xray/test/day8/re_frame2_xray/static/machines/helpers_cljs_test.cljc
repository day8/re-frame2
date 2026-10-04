(ns day8.re-frame2-xray.static.machines.helpers-cljs-test
  "Pure-data unit tests for the Static Machines projection helpers.
  Dual-runtime so the projection contract is covered on
  the JVM and in the `:node-test` bundle alike."
  (:require #?(:clj  [clojure.test :refer [are deftest is]]
               :cljs [cljs.test :refer-macros [are deftest is]])
            [day8.re-frame2-xray.static.machines.helpers :as h]))

;; ---- sub-mode normalisation ---------------------------------------------

(deftest normalise-sub-mode-keeps-the-four-modes-and-defaults-the-rest
  (is (= :topology h/default-sub-mode))
  (is (= 4 (count h/sub-modes))
      "four sub-modes: topology + sim + instances + cascade")
  (are [raw mode] (= mode (h/normalise-sub-mode raw))
    ;; the four modes survive, as keywords or as their string names
    :topology   :topology
    :sim        :sim
    :instances  :instances
    :cascade    :cascade
    "topology"  :topology
    "sim"       :sim
    "instances" :instances
    "cascade"   :cascade
    ;; anything else falls back to the default
    nil         :topology
    :nonsense   :topology
    "junk"      :topology
    42          :topology))

;; ---- sort-key normalisation ---------------------------------------------

(deftest normalise-sort-key-keeps-the-three-axes-and-defaults-the-rest
  (is (= :name h/default-sort-key))
  (is (= 3 (count h/sort-keys)))
  (are [raw k] (= k (h/normalise-sort-key raw))
    :states   :states
    :live     :live
    nil       :name
    :nonsense :name))

;; ---- source-coord lifting -----------------------------------------------

(deftest lift-source-coord-reads-the-canonical-slot-then-the-lenient-one
  (are [definition coord] (= coord (h/lift-source-coord definition))
    {:source-coord {:file "x.cljs" :line 42}} {:file "x.cljs" :line 42}
    ;; the alternate :source slot is the lenient fallback
    {:source {:file "y.cljs"}}                {:file "y.cljs"}
    nil                                       nil
    {}                                        nil
    {:initial :idle}                          nil))

(deftest format-source-coord-renders-file-and-optional-line
  (are [coord label] (= label (h/format-source-coord coord))
    {:file "src/x.cljs" :line 42} "src/x.cljs:42"
    {:file "src/x.cljs"}          "src/x.cljs"
    nil                           nil
    ;; no :file, no label
    {:line 42}                    nil))

;; ---- row projection -----------------------------------------------------

(def ^:private sample-defs
  {:m/a {:states {:idle {} :loading {} :done {}}
         :source-coord {:file "src/a.cljs" :line 12}}
   :m/b {:states {:on {} :off {}}}
   :m/c {}})  ;; degenerate — no states map, no source-coord

(def ^:private sample-snapshots
  {:m/a {:state :idle}
   :m/c {:state :init}})

(deftest project-row-builds-the-canonical-shape
  (let [row (h/project-row :m/a (:m/a sample-defs) sample-snapshots)]
    (is (= :m/a (:machine-id row)))
    (is (= 3 (:state-count row)))
    (is (= 1 (:live-count row)))
    (is (= {:file "src/a.cljs" :line 12} (:source-coord row)))
    (is (= "src/a.cljs:12" (:source-label row)))))

;; ---- state-count: flat / compound / parallel ----------------------------
;;
;; The state-count drives the browse-list chip, the detail header
;; `<N> states`, and the `Sort: States` axis. It MUST count every state
;; the topology renderer paints — top-level states PLUS compound
;; substates PLUS every parallel region's states. Reading only
;; `(count (:states definition))` would omit compound substates and show
;; parallel machines (no `:states` key) as `0 states`.
;; The count is exercised via the public `project-row` (`state-count`
;; itself is private).

(deftest state-count-counts-every-rendered-state
  (are [definition n] (= n (:state-count (h/project-row :m/x definition {})))
    ;; flat: the top-level states
    {:initial :idle
     :states  {:idle    {:on {:go :busy}}
               :busy    {:on {:done :idle}}
               :stopped {}}}
    3

    ;; compound: :unauth + :authed + :browsing + :paying — the same
    ;; occupiable-state count machines-viz `semantic-counts` emits
    {:initial :unauth
     :states  {:unauth {:on {:login :authed}}
               :authed {:initial :browsing
                        :states  {:browsing {:on {:checkout :paying}}
                                  :paying   {:on {:done :browsing}}}}}}
    4

    ;; three nesting levels: :a + :b + :c + :d
    {:initial :a
     :states  {:a {:initial :b
                   :states  {:b {:initial :c
                                 :states  {:c {}
                                           :d {}}}}}}}
    4

    ;; parallel: region :r1 (:a + :b) + region :r2 (:c), though a
    ;; parallel root carries no top-level :states key
    {:type    :parallel
     :regions {:r1 {:initial :a :states {:a {} :b {}}}
               :r2 {:initial :c :states {:c {}}}}}
    3

    ;; parallel regions recurse into compound states:
    ;; :r1 (a + b + b1 + b2) + :r2 (c)
    {:type    :parallel
     :regions {:r1 {:initial :a
                    :states  {:a {}
                              :b {:initial :b1
                                  :states  {:b1 {} :b2 {}}}}}
               :r2 {:initial :c :states {:c {}}}}}
    5

    ;; degenerate shapes count 0
    nil                            0
    {}                             0
    {:initial :idle}               0
    {:type :parallel :regions {}}  0))

;; ---- search -------------------------------------------------------------

(def ^:private sample-rows
  [{:machine-id :foo/login    :state-count 3 :live-count 0
    :source-coord {:file "src/foo/login.cljs"} :source-label "src/foo/login.cljs"}
   {:machine-id :foo/checkout :state-count 5 :live-count 2
    :source-coord {:file "src/foo/checkout.cljs"} :source-label "src/foo/checkout.cljs"}
   {:machine-id :bar/upload   :state-count 4 :live-count 1
    :source-coord {:file "src/bar/upload.cljs"} :source-label "src/bar/upload.cljs"}])

(deftest search-text-covers-name-namespace-file
  (let [r (first sample-rows)
        s (h/search-text r)]
    (is (re-find #"login" s) "name segment")
    (is (re-find #"foo" s) "namespace segment")
    (is (re-find #"src/foo/login" s) "file segment")))

(deftest apply-search-filters-by-substring
  (is (= 3 (count (h/apply-search sample-rows nil))) "nil query → all rows")
  (is (= 3 (count (h/apply-search sample-rows "")))  "blank query → all rows")
  (is (= 2 (count (h/apply-search sample-rows "foo"))))
  (is (= 1 (count (h/apply-search sample-rows "bar"))))
  (is (= 1 (count (h/apply-search sample-rows "Checkout")))
      "search is case-insensitive")
  (is (= 0 (count (h/apply-search sample-rows "nomatch")))))

;; ---- sort ---------------------------------------------------------------

(deftest apply-sort-orders-by-each-axis
  (are [sort-key order] (= order (mapv :machine-id (h/apply-sort sample-rows sort-key)))
    ;; alphabetical by `(str id)`
    :name    [:bar/upload :foo/checkout :foo/login]
    ;; state-count DESC: 5 > 4 > 3
    :states  [:foo/checkout :bar/upload :foo/login]
    ;; live-count DESC: 2 > 1 > 0
    :live    [:foo/checkout :bar/upload :foo/login]
    ;; an unknown key falls back to :name
    :unknown [:bar/upload :foo/checkout :foo/login]))

;; ---- composite browse-list projection -----------------------------------

(deftest project-browse-list-resolves-defaults
  (let [out (h/project-browse-list [:foo/login :foo/checkout :bar/upload]
                                   {:foo/login {:states {:a {}}}
                                    :foo/checkout {:states {:a {} :b {} :c {}}}
                                    :bar/upload {:states {:a {} :b {}}}}
                                   {:foo/checkout {:state :a}}
                                   nil :name nil)]
    (is (= 3 (:total out)))
    (is (= 3 (:visible out)))
    (is (= :bar/upload (:selected-id out))
        "default selection is the first sorted row")))

(deftest project-browse-list-honours-user-selection
  (let [out (h/project-browse-list [:foo/login :foo/checkout :bar/upload]
                                   {:foo/login {:states {:a {}}}}
                                   {}
                                   nil :name :foo/checkout)]
    (is (= :foo/checkout (:selected-id out)))))

(deftest project-browse-list-defaults-when-selection-filtered-out
  (let [out (h/project-browse-list [:foo/login :foo/checkout :bar/upload]
                                   {:foo/login {:states {:a {}}}}
                                   {}
                                   "bar" :name :foo/checkout)]
    (is (= 1 (:visible out)))
    (is (= :bar/upload (:selected-id out))
        "user's pick is filtered out → default to first visible")))

(deftest project-browse-list-empty
  (let [out (h/project-browse-list [] {} {} nil :name nil)]
    (is (= 0 (:total out)))
    (is (= 0 (:visible out)))
    (is (nil? (:selected-id out)))))

;; ---- pip render plan ----------------------------------------------------

(deftest pip-render-plan-is-none-then-pips-then-a-count
  (are [live plan] (= plan (h/pip-render-plan live))
    nil {:kind :none}
    0   {:kind :none}
    1   {:kind :pips :count 1}
    5   {:kind :pips :count 5}
    ;; 12 is the pip cap
    12  {:kind :pips :count 12}
    13  {:kind :count :count 13}
    999 {:kind :count :count 999}))

