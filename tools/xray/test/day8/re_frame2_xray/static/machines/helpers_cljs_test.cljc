(ns day8.re-frame2-xray.static.machines.helpers-cljs-test
  "Pure-data unit tests for the Static Machines projection helpers.
  Dual-runtime so the projection contract is covered on
  the JVM and in the `:node-test` bundle alike."
  (:require #?(:clj  [clojure.test :refer [are deftest is]]
               :cljs [cljs.test :refer-macros [are deftest is]])
            [day8.re-frame2-xray.static.machines.helpers :as h]))

;; ---- sub-mode normalisation ---------------------------------------------

(deftest normalise-sub-mode-keeps-the-four-modes-and-defaults-the-rest
  (are [raw mode] (= mode (h/normalise-sub-mode raw))
    :sim        :sim
    "instances" :instances
    :nonsense   :topology
    "junk"      :topology
    nil         :topology))

;; ---- sort-key normalisation ---------------------------------------------

(deftest normalise-sort-key-keeps-the-three-axes-and-defaults-the-rest
  (are [raw k] (= k (h/normalise-sort-key raw))
    :states   :states
    :nonsense :name
    nil       :name))

;; ---- source-coord lifting -----------------------------------------------

(deftest lift-source-coord-reads-the-canonical-slot-then-the-lenient-one
  (are [definition coord] (= coord (h/lift-source-coord definition))
    {:source-coord {:file "x.cljs" :line 42}} {:file "x.cljs" :line 42}
    {:source {:file "y.cljs"}}                {:file "y.cljs"}
    nil                                       nil
    {:initial :idle}                          nil))

(deftest format-source-coord-renders-file-and-optional-line
  (are [coord label] (= label (h/format-source-coord coord))
    {:file "src/x.cljs" :line 42} "src/x.cljs:42"
    {:file "src/x.cljs"}          "src/x.cljs"
    {:line 42}                    nil))

;; ---- row projection -----------------------------------------------------

(deftest project-row-builds-the-canonical-shape
  (is (= {:machine-id   :m/a
          :state-count  3
          :live-count   1
          :source-coord {:file "src/a.cljs" :line 12}
          :source-label "src/a.cljs:12"}
         (h/project-row :m/a
                        {:states       {:idle {} :loading {} :done {}}
                         :source-coord {:file "src/a.cljs" :line 12}}
                        {:m/a {:state :idle}}))))

;; The count must match what the topology renderer paints: compound
;; substates and every parallel region's states included.
(deftest state-count-counts-every-rendered-state
  (are [definition n] (= n (:state-count (h/project-row :m/x definition {})))
    ;; compound: :unauth + :authed + :browsing + :paying
    {:initial :unauth
     :states  {:unauth {:on {:login :authed}}
               :authed {:initial :browsing
                        :states  {:browsing {:on {:checkout :paying}}
                                  :paying   {:on {:done :browsing}}}}}}
    4

    ;; parallel regions recurse into compound states:
    ;; :r1 (a + b + b1 + b2) + :r2 (c)
    {:type    :parallel
     :regions {:r1 {:initial :a
                    :states  {:a {}
                              :b {:initial :b1
                                  :states  {:b1 {} :b2 {}}}}}
               :r2 {:initial :c :states {:c {}}}}}
    5

    nil              0
    {:initial :idle} 0))

;; ---- search -------------------------------------------------------------

(def ^:private sample-rows
  [{:machine-id :foo/login    :state-count 3 :live-count 1
    :source-coord {:file "src/foo/login.cljs"} :source-label "src/foo/login.cljs"}
   {:machine-id :foo/checkout :state-count 5 :live-count 0
    :source-coord {:file "src/foo/checkout.cljs"} :source-label "src/foo/checkout.cljs"}
   {:machine-id :bar/upload   :state-count 4 :live-count 0
    :source-coord {:file "src/bar/upload.cljs"} :source-label "src/bar/upload.cljs"}])

(deftest search-text-covers-name-namespace-file
  (is (re-find #"src/foo/login" (h/search-text (first sample-rows)))))

(deftest apply-search-filters-by-substring
  (are [query ids] (= ids (mapv :machine-id (h/apply-search sample-rows query)))
    ""         [:foo/login :foo/checkout :bar/upload]
    "foo"      [:foo/login :foo/checkout]
    ;; case-insensitive
    "Checkout" [:foo/checkout]
    "nomatch"  []))

;; ---- sort ---------------------------------------------------------------

(deftest apply-sort-orders-by-each-axis
  (are [sort-key order] (= order (mapv :machine-id (h/apply-sort sample-rows sort-key)))
    ;; alphabetical by `(str id)`
    :name    [:bar/upload :foo/checkout :foo/login]
    ;; state-count DESC: 5 > 4 > 3
    :states  [:foo/checkout :bar/upload :foo/login]
    ;; live-count DESC, the 0-count tie broken on name
    :live    [:foo/login :bar/upload :foo/checkout]))

;; ---- composite browse-list projection -----------------------------------

(deftest project-browse-list-counts-and-resolves-the-selection
  (are [machines query selected out]
       (= out (select-keys (h/project-browse-list machines {} {} query :name selected)
                           [:total :visible :selected-id]))
    ;; no pick: the first SORTED row, not the first registered
    [:foo/login :foo/checkout :bar/upload] nil nil
    {:total 3 :visible 3 :selected-id :bar/upload}

    [:foo/login :foo/checkout :bar/upload] nil :foo/checkout
    {:total 3 :visible 3 :selected-id :foo/checkout}

    ;; the pick is filtered out: the first VISIBLE row
    [:foo/login :foo/checkout :bar/upload] "bar" :foo/checkout
    {:total 3 :visible 1 :selected-id :bar/upload}

    [] nil nil
    {:total 0 :visible 0 :selected-id nil}))

;; ---- pip render plan ----------------------------------------------------

(deftest pip-render-plan-is-none-at-zero-and-pips-when-live
  (are [live plan] (= plan (h/pip-render-plan live))
    0 {:kind :none}
    1 {:kind :pips :count 1}))
