(ns re-frame.live-frame-reprojection-incarnation-jvm-test
  "A reprojection publishes its generation only onto the frame incarnation it
  read. It captures the incarnation when it starts; if the frame was destroyed
  and a same-id successor made before the publish, the stale generation is
  dropped and the successor keeps its own.

  The replacement runs inside the generation resolve, between the read and the
  publish — the window a concurrent `destroy-frame!` + `make-frame` on another
  JVM thread can land in.

  JVM-only: it creates a namespace and `eval`s a registration form in it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- eval-in-ns
  "Evaluate `form` with `*ns*` bound to `ns-sym`, so a `rf/reg-*` macro in it
  captures that namespace as its provenance."
  [ns-sym form]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rf 're-frame.core)
    (eval form)))

(deftest reprojection-drops-a-generation-read-from-a-replaced-incarnation
  (let [frame-id  :kndrz/frame
        real      @#'rf.live-frame/resolve-generation!
        replaced? (atom false)]
    (eval-in-ns 'kndrz.probe '(rf/reg-event :kndrz/ev (fn [{:keys [db]} _] {:db db})))
    (rf/make-frame {:id     frame-id
                    :images [(rf/image {:id :kndrz/image :select-ns {:include ["kndrz.**"]}})]})
    (with-redefs [rf.live-frame/resolve-generation!
                  (fn [& args]
                    (let [generation (apply real args)]
                      (when (compare-and-set! replaced? false true)
                        (rf/destroy-frame! frame-id)
                        (rf/make-frame {:id frame-id}))
                      ;; Differ from the generation A was sealed with, so the
                      ;; reprojection has something to publish.
                      (assoc generation ::reprojected true)))]
      (is (nil? (rf.live-frame/reproject-live-frame! frame-id))
          "the reprojection reports no move"))
    (is (= {:replaced? true :generation nil}
           {:replaced? @replaced? :generation (rf.frame/frame-generation frame-id)})
        "the successor, made without :images, keeps its own (absent) generation")))
