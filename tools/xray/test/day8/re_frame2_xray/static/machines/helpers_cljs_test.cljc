(ns day8.re-frame2-xray.static.machines.helpers-cljs-test
  "Pure-data unit tests for the Static Machines projection helpers
  (rf2-o5f5f.2). Dual-runtime so the projection contract is covered on
  the JVM and in the `:node-test` bundle alike (rf2-odlm3)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.static.machines.helpers :as h]))

;; ---- sub-mode normalisation ---------------------------------------------

(deftest sub-mode-defaults-to-topology
  (is (= :topology h/default-sub-mode))
  (is (= 4 (count h/sub-modes))
      "four sub-modes: topology + sim + instances + cascade"))

(deftest normalise-sub-mode-accepts-valid
  (doseq [m [:topology :sim :instances :cascade]]
    (is (= m (h/normalise-sub-mode m))
        (str m " survives normalisation"))))

(deftest normalise-sub-mode-coerces-strings
  (is (= :topology (h/normalise-sub-mode "topology")))
  (is (= :sim      (h/normalise-sub-mode "sim")))
  (is (= :instances (h/normalise-sub-mode "instances")))
  (is (= :cascade  (h/normalise-sub-mode "cascade"))))

(deftest normalise-sub-mode-rejects-unknown
  (is (= :topology (h/normalise-sub-mode nil)))
  (is (= :topology (h/normalise-sub-mode :nonsense)))
  (is (= :topology (h/normalise-sub-mode "junk")))
  (is (= :topology (h/normalise-sub-mode 42))))

;; ---- sort-key normalisation ---------------------------------------------

(deftest sort-key-defaults-to-name
  (is (= :name h/default-sort-key))
  (is (= 3 (count h/sort-keys))))

(deftest normalise-sort-key-rejects-unknown
  (is (= :name (h/normalise-sort-key nil)))
  (is (= :name (h/normalise-sort-key :nonsense)))
  (is (= :states (h/normalise-sort-key :states)))
  (is (= :live  (h/normalise-sort-key :live))))

;; ---- source-coord lifting -----------------------------------------------

(deftest lift-source-coord-pulls-canonical-slot
  (is (= {:file "x.cljs" :line 42}
         (h/lift-source-coord {:source-coord {:file "x.cljs" :line 42}})))
  (testing "alternate :source slot — lenient fallback"
    (is (= {:file "y.cljs"}
           (h/lift-source-coord {:source {:file "y.cljs"}})))))

(deftest lift-source-coord-nil-safe
  (is (nil? (h/lift-source-coord nil)))
  (is (nil? (h/lift-source-coord {})))
  (is (nil? (h/lift-source-coord {:initial :idle}))))

(deftest format-source-coord-renders-file-line
  (is (= "src/x.cljs:42"
         (h/format-source-coord {:file "src/x.cljs" :line 42})))
  (is (= "src/x.cljs"
         (h/format-source-coord {:file "src/x.cljs"}))
      "line is optional"))

(deftest format-source-coord-nil-safe
  (is (nil? (h/format-source-coord nil)))
  (is (nil? (h/format-source-coord {:line 42}))
      "no :file → no format"))

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

(deftest project-row-degrades-on-missing-fields
  (let [row (h/project-row :m/c (:m/c sample-defs) sample-snapshots)]
    (is (= 0 (:state-count row)) "no :states → 0")
    (is (= 1 (:live-count row)) ":m/c has a snapshot")
    (is (nil? (:source-coord row)))
    (is (nil? (:source-label row)))))

(deftest project-rows-walks-all-machines
  (let [rows (h/project-rows [:m/a :m/b :m/c]
                             sample-defs
                             sample-snapshots)]
    (is (= 3 (count rows)))
    (is (= [:m/a :m/b :m/c] (mapv :machine-id rows)))))

;; ---- state-count: flat / compound / parallel (rf2-6nx8y) ----------------
;;
;; The state-count drives the browse-list chip, the detail header
;; `<N> states`, and the `Sort: States` axis. It MUST count every state
;; the topology renderer paints — top-level states PLUS compound
;; substates PLUS every parallel region's states. Pre-rf2-6nx8y it read
;; only `(count (:states definition))`, so compound substates were
;; omitted and parallel machines (no `:states` key) showed `0 states`.
;; The count is exercised via the public `project-row` (`state-count`
;; itself is private).

(deftest state-count-flat-counts-top-level-states
  (testing "a flat machine counts its top-level states"
    (let [row (h/project-row :m/flat
                             {:initial :idle
                              :states  {:idle    {:on {:go :busy}}
                                        :busy    {:on {:done :idle}}
                                        :stopped {}}}
                             {})]
      (is (= 3 (:state-count row))))))

(deftest state-count-compound-includes-nested-substates
  (testing "compound substates are counted, not omitted (rf2-6nx8y)"
    ;; :unauth + :authed + (:browsing + :paying) = 4 — the same occupiable-
    ;; state count machines-viz `semantic-counts` emits for this definition.
    (let [row (h/project-row :m/compound
                             {:initial :unauth
                              :states  {:unauth {:on {:login :authed}}
                                        :authed {:initial :browsing
                                                 :states  {:browsing {:on {:checkout :paying}}
                                                           :paying   {:on {:done :browsing}}}}}}
                             {})]
      (is (= 4 (:state-count row))
          ":unauth + :authed + :browsing + :paying"))))

(deftest state-count-deeply-nested-compound
  (testing "recursion descends every compound level"
    ;; :a + (:b + (:c + :d)) = 4
    (let [row (h/project-row :m/deep
                             {:initial :a
                              :states  {:a {:initial :b
                                            :states  {:b {:initial :c
                                                          :states  {:c {}
                                                                    :d {}}}}}}}
                             {})]
      (is (= 4 (:state-count row))
          ":a + :b + :c + :d across three nesting levels"))))

(deftest state-count-parallel-sums-region-states
  (testing "a :type :parallel machine sums its regions' states (rf2-6nx8y)"
    ;; region :r1 (:a + :b) + region :r2 (:c) = 3 — was 0 pre-fix
    ;; because a parallel root carries no top-level :states key.
    (let [row (h/project-row :m/par
                             {:type    :parallel
                              :regions {:r1 {:initial :a :states {:a {} :b {}}}
                                        :r2 {:initial :c :states {:c {}}}}}
                             {})]
      (is (= 3 (:state-count row))
          "two regions' states flatten: a + b + c"))))

(deftest state-count-parallel-with-compound-regions
  (testing "parallel regions whose states are themselves compound recurse"
    ;; region :r1: :a + (:b + :b1 + :b2) = 4 ; region :r2: :c = 1 → 5
    (let [row (h/project-row :m/par-compound
                             {:type    :parallel
                              :regions {:r1 {:initial :a
                                             :states  {:a {}
                                                       :b {:initial :b1
                                                           :states  {:b1 {} :b2 {}}}}}
                                        :r2 {:initial :c :states {:c {}}}}}
                             {})]
      (is (= 5 (:state-count row))
          "region :r1 (a + b + b1 + b2) + region :r2 (c)"))))

(deftest state-count-degenerate-shapes-are-zero
  (testing "nil / empty / no-states definitions count 0"
    (is (= 0 (:state-count (h/project-row :m/nil nil {}))))
    (is (= 0 (:state-count (h/project-row :m/empty {} {}))))
    (is (= 0 (:state-count (h/project-row :m/no-states {:initial :idle} {})))
        "an :initial-only map with no :states map → 0")
    (is (= 0 (:state-count (h/project-row :m/empty-parallel
                                          {:type :parallel :regions {}} {})))
        "a parallel root with no regions → 0")))

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

(deftest apply-sort-name-asc
  (let [sorted (h/apply-sort sample-rows :name)]
    (is (= [:bar/upload :foo/checkout :foo/login]
           (mapv :machine-id sorted))
        "alphabetical by `(str id)`")))

(deftest apply-sort-states-desc
  (let [sorted (h/apply-sort sample-rows :states)]
    (is (= [:foo/checkout :bar/upload :foo/login]
           (mapv :machine-id sorted))
        "5 > 4 > 3")))

(deftest apply-sort-live-desc
  (let [sorted (h/apply-sort sample-rows :live)]
    (is (= [:foo/checkout :bar/upload :foo/login]
           (mapv :machine-id sorted))
        "2 > 1 > 0, ties on name")))

(deftest apply-sort-unknown-key-falls-back-to-name
  (let [sorted (h/apply-sort sample-rows :unknown)]
    (is (= [:bar/upload :foo/checkout :foo/login]
           (mapv :machine-id sorted)))))

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

(deftest pip-render-plan-zero-is-none
  (is (= {:kind :none} (h/pip-render-plan 0)))
  (is (= {:kind :none} (h/pip-render-plan nil))))

(deftest pip-render-plan-under-cap-is-pips
  (is (= {:kind :pips :count 1}  (h/pip-render-plan 1)))
  (is (= {:kind :pips :count 12} (h/pip-render-plan 12)))
  (is (= {:kind :pips :count 5}  (h/pip-render-plan 5))))

(deftest pip-render-plan-over-cap-is-count
  (is (= {:kind :count :count 13} (h/pip-render-plan 13)))
  (is (= {:kind :count :count 999} (h/pip-render-plan 999))))
