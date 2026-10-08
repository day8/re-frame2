(ns re-frame.ssr-frame-resolution-test
  "`head-model` and `project-error` take an explicit frame and run outside
  any `with-frame`, so their `(kind, id)` lookups must resolve through that
  frame's image, not the process registrar.

  The provenance store keeps one descriptor per `[kind id provenance-ns]`
  while the registrar atom keeps only the last writer, so the same id
  registered from two namespaces (`re-frame.ssr.head-image-alpha`, then
  `-beta`; two real files because `reg-*` stamps provenance at
  macroexpansion) resolves to two bodies for two frames whose images select
  one namespace each. ALPHA's frame must get ALPHA's body although the atom
  holds BETA's.

  This namespace also runs under the production gate, where a descriptor's
  `:doc` is stripped, so the bodies are told apart by what they return."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-projector :as rf.ssr.error-projector]
            [re-frame.ssr.head-image-alpha :as rf.ssr.head-image-alpha]
            [re-frame.ssr.head-image-beta :as rf.ssr.head-image-beta]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private frame-counter (atom 0))

(defn- register-both!
  "ALPHA then BETA, so the registrar atom ends up holding BETA."
  []
  (rf.ssr.head-image-alpha/register!)
  (rf.ssr.head-image-beta/register!))

(defn- frame-selecting!
  "A server frame seeded with `db` whose image selects exactly `ns-glob`
  (`:select-ns` selects from what is registered; it never loads). Extra
  frame opts merge in."
  ([ns-glob db] (frame-selecting! ns-glob db {}))
  ([ns-glob db opts]
   (let [n   (swap! frame-counter inc)
         fid (keyword "rf.ssr-frame-resolution" (str "f" n))]
     (rf/make-frame (merge {:id       fid
                            :platform :server
                            :images   [(rf/image {:id        (keyword "rf.ssr-frame-resolution" (str "img" n))
                                                  :select-ns {:include [ns-glob]}})]}
                           opts))
     (rf/dispatch-sync [:rf/set-db db] {:frame fid})
     fid)))

(deftest head-model-runs-the-target-frames-own-registration
  (register-both!)
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})
        beta-frame  (frame-selecting! "re-frame.ssr.head-image-beta" {:marker "B"})]
    (is (= [{:title "alpha:A"} {:title "beta:B"}]
           (mapv #(rf.ssr/head-model % {:head-id rf.ssr.head-image-alpha/head-id})
                 [alpha-frame beta-frame])))))

(deftest head-model-refuses-a-head-the-target-frames-image-does-not-carry
  (register-both!)
  (rf/reg-head ::unselected (fn [_ _] {:title "unselected"}))
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})]
    (testing "a head registered outside the frame's image is not that frame's head"
      (is (= :rf.error/no-such-head
             (try (rf.ssr/head-model alpha-frame {:head-id ::unselected})
                  nil
                  (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))))))

(deftest head-model-resolves-route-metadata-and-head-in-the-target-generation
  (testing "the route's :head metadata comes from ALPHA's image too. The atom
            holds BETA's route, which declares no :head, so an atom read would
            fall back to the default head"
    (register-both!)
    (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})]
      (is (= {:title "alpha:A"}
             (rf.ssr/head-model alpha-frame {:route {:route-id rf.ssr.head-image-alpha/route-id}}))))))

(deftest project-error-runs-the-target-frames-own-projector
  (register-both!)
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {}
                                      {:ssr {:public-error-id rf.ssr.head-image-alpha/projector-id}})]
    (is (= {:status 418 :code :alpha :message "alpha" :retryable? false}
           (rf.ssr.error-projector/project-error alpha-frame {:operation :rf.error/handler-exception})))))
