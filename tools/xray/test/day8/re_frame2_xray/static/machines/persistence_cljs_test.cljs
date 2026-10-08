(ns day8.re-frame2-xray.static.machines.persistence-cljs-test
  "Direct slot tests for the Static Machines persistence round-trip:
  the stored selection format, sub-mode value normalisation, and the
  malformed-EDN catch.

  ## Test seam

  These are CLJS unit tests that `with-redefs` the shared
  `local-storage` seam (`get-item` / `set-item!` / `remove-item!`) over
  an in-process atom. No `js/window` / jsdom is touched — the slot
  parse + normalise logic is exercised hermetically."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.static.machines.persistence :as persistence]))

;; ---- in-process localStorage stub ---------------------------------------

(def ^:private store (atom {}))

(defn- stub-get-item [k] (get @store k))
(defn- stub-set-item! [k v] (swap! store assoc k v) nil)
(defn- stub-remove-item! [k] (swap! store dissoc k) nil)

(use-fixtures :each
  {:before (fn [] (reset! store {}))})

(defn- with-stub-storage* [f]
  (with-redefs [ls/get-item    stub-get-item
                ls/set-item!   stub-set-item!
                ls/remove-item! stub-remove-item!]
    (f)))

;; ---- sub-mode slot ------------------------------------------------------

(deftest load-malformed-edn-returns-empty-map
  (with-stub-storage*
    (fn []
      (stub-set-item! persistence/sub-mode-key "{:a/b :topology")  ;; unbalanced
      (is (= {} (persistence/load-sub-mode-by-id))))))

(deftest load-normalises-invalid-sub-mode-values
  (with-stub-storage*
    (fn []
      (persistence/save-sub-mode-by-id!
        {:m/a :bogus
         :m/b :sim
         :m/c "instances"})
      (is (= {:m/a :topology
              :m/b :sim
              :m/c :instances}
             (persistence/load-sub-mode-by-id))))))

;; ---- selected-id slot ---------------------------------------------------

;; The stored form is `ns/name` with the leading colon stripped — the
;; format a prior session's slot already holds.
(deftest selected-id-round-trips-namespaced-keyword
  (with-stub-storage*
    (fn []
      (persistence/save-selected-id! :foo.bar/login)
      (is (= ["foo.bar/login" :foo.bar/login]
             [(get @store persistence/selection-key)
              (persistence/load-selected-id)])))))
