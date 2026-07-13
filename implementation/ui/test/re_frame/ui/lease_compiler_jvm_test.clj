(ns re-frame.ui.lease-compiler-jvm-test
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.registrar :as registrar]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.tree :as tree]))

(def descriptor* (atom {:resource :feed/items}))
(def evaluations* (atom 0))

(defview dynamic-lease-view []
  (ui/lease (do (swap! evaluations* inc) @descriptor*))
  [:div "leased"])

(defview two-lease-sites []
  (ui/lease {:resource :feed/items})
  (ui/lease {:resource :feed/items})
  [:div "two"])

(defn- expand-error [form]
  (try
    (macroexpand-1 form)
    nil
    (catch clojure.lang.ExceptionInfo ex ex)
    (catch Exception ex
      (let [cause (.getCause ex)]
        (when (instance? clojure.lang.ExceptionInfo cause) cause)))))

(deftest leading-declarations-evaluate-once-and-render-no-node
  (reset! descriptor* {:resource :feed/items})
  (reset! evaluations* 0)
  (let [rendered (tree/render dynamic-lease-view {})]
    (is (= 1 @evaluations*))
    (is (= :div (get-in rendered [:children 0 :tag])))
    (is (= ["leased"] (get-in rendered [:children 0 :children]))))
  (testing "dynamic validation happens before structural publication"
    (reset! descriptor* {:resource :unqualified})
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (ex-data
                          (try (tree/render dynamic-lease-view {})
                               (catch clojure.lang.ExceptionInfo ex ex))))))))

(deftest manifest-records-distinct-lexical-sites
  (let [leases (get-in (registrar/lookup :view ::two-lease-sites)
                       [:rf.ui/manifest :sites :leases])]
    (is (= 2 (count leases)))
    (is (= 2 (count (set (map :sid leases)))))
    (is (every? #(= {:resource :feed/items} (:descriptor %)) leases))
    (is (contains? (get-in (registrar/lookup :view ::two-lease-sites)
                           [:rf.ui/manifest :capabilities])
                   :lease))))

(deftest body-grammar-is-a-closed-prefix
  (doseq [[form id]
          [['(re-frame.ui/defview v []
               (re-frame.ui/lease {:resource :feed/items}))
            :rf.ui.compile/multi-form-body]
           ['(re-frame.ui/defview v []
               (when active? (re-frame.ui/lease {:resource :feed/items}))
               [:div])
            :rf.ui.compile/multi-form-body]
           ['(re-frame.ui/defview v []
               [:div (re-frame.ui/lease {:resource :feed/items})])
            :rf.ui.compile/unsupported-form]
           ['(re-frame.ui/defview v []
               (re-frame.ui/lease {:resource :feed/items :unknown true})
               [:div])
            :rf.ui.compile/unsupported-form]
           ['(re-frame.ui/defview v []
               (re-frame.ui/lease {:resource :unqualified})
               [:div])
            :rf.ui.compile/unsupported-form]]]
    (let [ex (expand-error form)]
      (is (= id (:rf.ui.compile/error (ex-data ex))) (pr-str form)))))

(deftest public-lease-is-never-a-dynamic-helper
  (is (= :rf.error/ui-tree-malformed
         (:rf.error/id
          (ex-data
           (try (ui/lease {:resource :feed/items})
                (catch clojure.lang.ExceptionInfo ex ex)))))))
