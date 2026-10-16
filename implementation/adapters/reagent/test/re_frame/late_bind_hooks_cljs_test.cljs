(ns re-frame.late-bind-hooks-cljs-test
  "The documented Reagent capability set rides the adapter map, is published
  into the `:adapter/*` slots only when that map is installed, and the
  directory attributes those slots to `install-adapter!`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.late-bind.directory :as rf.late-bind.directory]
            [re-frame.substrate.adapter :as rf.substrate.adapter]))

;; Each test starts never-installed and leaves the slot never-installed for
;; whichever namespace runs next.
(use-fixtures :each
  (fn [t]
    (rf.substrate.adapter/dispose-adapter!)
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (try
      (t)
      (finally
        (rf.substrate.adapter/dispose-adapter!)
        (rf.substrate.adapter/reset-lifecycle-state-for-tests!)))))

(def ^:private reagent-capability-slots
  {:current-frame            :adapter/current-frame
   :current-component        :adapter/current-component
   :ratom                    :adapter/ratom
   :ratom?                   :adapter/ratom?
   :make-reaction            :adapter/make-reaction
   :activate-derived-value!  :adapter/activate-derived-value!
   :add-on-dispose!          :adapter/add-on-dispose!
   :dispose!                 :adapter/dispose!
   :reactive?                :adapter/reactive?
   :reactive-owner           :adapter/reactive-owner
   :read-container-untracked :adapter/read-container-untracked
   :after-render             :adapter/after-render
   :derived-container?       :adapter/derived-container?})

(deftest reagent-adapter-publishes-its-documented-capabilities-at-install
  (testing "the Reagent map carries every documented capability"
    (doseq [k (keys reagent-capability-slots)]
      (is (fn? (get rf.adapter.reagent/adapter k)) (str k " is a fn on the map"))))
  (testing "loading the adapter ns publishes none of them"
    (is (= {} (into {} (keep (fn [slot] (when-some [f (rf.late-bind/get-fn slot)] [slot f])))
                    (vals reagent-capability-slots)))))
  (testing "installing the map publishes each capability into its slot"
    (rf.substrate.adapter/install-adapter! rf.adapter.reagent/adapter)
    (doseq [[k slot] reagent-capability-slots]
      (is (identical? (get rf.adapter.reagent/adapter k) (rf.late-bind/get-fn slot))
          (str slot " holds the installed map's " k)))))

(deftest reagent-capability-slots-match-directory-listing
  (testing "every slot is listed in re-frame.late-bind.directory with
            install-adapter!'s namespace as its producer"
    (doseq [slot (vals reagent-capability-slots)]
      (is (= 're-frame.substrate.adapter
             (:producer-ns (rf.late-bind.directory/entry slot)))
          (str slot " is published by re-frame.substrate.adapter/install-adapter!"))))
  (testing "the chained SSR emitter setter is still produced by the adapter ns"
    (let [producer (:producer-ns (rf.late-bind.directory/entry :reagent/set-hiccup-emitter!))]
      (is (contains? (set producer) 're-frame.adapter.reagent))
      (is (some? (rf.late-bind/get-fn :reagent/set-hiccup-emitter!))
          "chained at ns-load, before any install"))))
