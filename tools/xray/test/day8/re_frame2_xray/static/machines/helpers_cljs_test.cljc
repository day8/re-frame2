(ns day8.re-frame2-xray.static.machines.helpers-cljs-test
  "Unit tests for the Static Machines projection helpers: pure data, plus
  one real `reg-machine` registration for the call-site coord.
  Dual-runtime so the projection contract is covered on
  the JVM and in the `:node-test` bundle alike."
  (:require #?(:clj  [clojure.test :refer [are deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [are deftest is use-fixtures]])
            [clojure.string :as str]
            [day8.re-frame2-xray.static.machines.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- source-coord lifting -----------------------------------------------

(deftest format-source-coord-renders-file-and-optional-line
  (are [coord label] (= label (h/format-source-coord coord))
    {:file "src/x.cljs" :line 42} "src/x.cljs:42"
    {:file "src/x.cljs"}          "src/x.cljs"
    {:line 42}                    nil))

;; The coord the chip shows is the one a REAL `reg-machine` records, which
;; lands on the registration metadata rather than on the `:rf/machine` spec.
(deftest a-real-registration-surfaces-its-call-site-coord
  (rf/reg-machine ::door
    {:initial :closed
     :states  {:closed {:on {:open :opened}}
               :opened {:on {:close :closed}}}})
  (let [registration (rf/handler-meta {:source :store :kind :event :id ::door})
        row          (first (h/project-rows [::door]
                                            {::door (:rf/machine registration)}
                                            {::door (h/lift-source-coord registration)}
                                            {}))
        {:keys [file line]} (:source-coord row)]
    (is (str/ends-with? (str file) "helpers_cljs_test.cljc"))
    (is (= (str file ":" line) (:source-label row)))
    (is (= 2 (:state-count row)))))

;; ---- row projection -----------------------------------------------------

(deftest project-row-builds-the-canonical-shape
  (is (= {:machine-id   :m/a
          :state-count  3
          :live-count   1
          :source-coord {:file "src/a.cljs" :line 12}
          :source-label "src/a.cljs:12"}
         (h/project-row :m/a
                        {:states {:idle {} :loading {} :done {}}}
                        {:file "src/a.cljs" :line 12}
                        {:m/a {:state :idle}}))))

;; The count must match what the topology renderer paints: compound
;; substates and every parallel region's states included.
(deftest state-count-counts-every-rendered-state
  (are [definition n] (= n (:state-count (h/project-row :m/x definition nil {})))
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

(deftest apply-search-filters-by-substring
  (are [query ids] (= ids (mapv :machine-id (h/apply-search sample-rows query)))
    ""         [:foo/login :foo/checkout :bar/upload]
    "foo"      [:foo/login :foo/checkout]
    ;; case-insensitive
    "Checkout" [:foo/checkout]
    ;; the source file path
    "src/bar"  [:bar/upload]
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
       (= out (select-keys (h/project-browse-list machines {} {} {} query :name selected)
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
