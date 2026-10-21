(ns re-frame.live-frame-reprojection-incarnation-jvm-test
  "A reprojection publishes its generation only onto the frame record it read:
  the same incarnation, still carrying the generation and descriptor pool the
  reprojection re-resolved from. If the frame was destroyed and a same-id
  successor made before the publish, the stale generation is dropped and the
  successor keeps its own. If a same-id re-registration staged a new
  generation and pool instead, the staged pair survives, still paired.

  The competing write runs inside the generation resolve, between the read and
  the publish — the window a concurrent `destroy-frame!` + `make-frame`, or a
  same-id `make-frame`, on another JVM thread can land in.

  JVM-only: it creates a namespace and `eval`s a registration form in it, and
  holds a re-registration in its staged window on a second thread."
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

(deftest reprojection-cannot-overwrite-a-pair-a-same-id-re-registration-staged
  ;; The reprojection reads the frame on the live store. Inside its resolve, a
  ;; same-id `make-frame` against an explicit pool stages a new generation and
  ;; pool on a second thread, and the policy probe holds it in that staged
  ;; window while the reprojection publishes. A re-registration keeps the
  ;; incarnation, so only the pair the reprojection read can show that its
  ;; generation is stale.
  (let [frame-id  :kndrz-pair/frame
        img       (rf/image {:id :kndrz-pair/image :select-ns {:include ["kndrz-pair.**"]}})
        pool      [{:rf.provenance/ns "kndrz-pair.pool" :kind :event :id :kndrz-pair/ev
                    :handler-fn ::pool}]
        real      @#'rf.live-frame/resolve-generation!
        read?     (atom false)
        staged    (promise)
        published (promise)
        re-make   (atom nil)]
    (eval-in-ns 'kndrz-pair.probe '(rf/reg-event :kndrz-pair/ev (fn [{:keys [db]} _] {:db db})))
    (rf/make-frame {:id frame-id :images [img]})
    (let [read-generation (rf.frame/frame-generation frame-id)
          moved
          (with-redefs [rf.live-frame/resolve-generation!
                        (fn [& args]
                          (let [generation (apply real args)]
                            (if (compare-and-set! read? false true)
                              (do (reset! re-make
                                          (future
                                            (binding [rf.frame/*upsert-policy-probe*
                                                      (fn [id]
                                                        (when (= frame-id id)
                                                          (deliver staged (get @rf.frame/frames id))
                                                          (deref published 10000 nil)))]
                                              (rf/make-frame {:id frame-id :images [img]} pool))))
                                  (deref staged 10000 nil)
                                  ;; Differ from the generation the frame was
                                  ;; read with, so the reprojection has
                                  ;; something to publish.
                                  (assoc generation ::reprojected true))
                              generation)))]
            (try (rf.live-frame/reproject-live-frame! frame-id)
                 (finally
                   (deliver published true)
                   (some-> @re-make (deref 10000 ::timed-out)))))
          staged-record (deref staged 0 nil)
          final-record  (get @rf.frame/frames frame-id)]
      (is (= [nil true true true :final]
             [moved
              (and (some? (:generation staged-record))
                   (not (identical? read-generation (:generation staged-record)))
                   (identical? pool (:descriptor-pool staged-record)))
              (identical? (:generation staged-record) (:generation final-record))
              (identical? pool (:descriptor-pool final-record))
              (-> final-record :construction :state)])
          "the reprojection reports no move; the staged generation survives its publish, paired with its pool"))))
