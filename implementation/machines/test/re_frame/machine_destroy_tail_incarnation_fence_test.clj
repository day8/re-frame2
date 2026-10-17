(ns re-frame.machine-destroy-tail-incarnation-fence-test
  "Teardown writes that outlive the frame incarnation A that started them leave
  a same-id successor B untouched: an `:after` timer release cancels only its
  own handle, and the classification drop rides the exact elision write."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.classification :as rf.machines.classification]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private actor-type :rf2-i4aj9c/child)
(def ^:private actor-id   (keyword "rf2-i4aj9c" "child#1"))

;; Declares a `:sensitive` path, so spawn lowers a classification claim.
(def ^:private classified-spec
  {:initial   :running
   :sensitive [[:data :token]]
   :states    {:running {:exit (fn [ctx] (:data ctx))
                         :on   {:go :done}}
               :done    {:final? true}}})

(defn- elision-slot [frame-id] (get-in (rf/frame-state-value frame-id) [:rf.db/runtime :rf.runtime/elision]))

;; Releasing an `:after` timer cancels the entry's own host handle. Once a
;; cancelled-trace listener has destroyed A and a same-id B has armed the same
;; key, A's release must leave B's entry alone; with no successor it cancels
;; A's handle and the slot is emptied.
(deftest timer-release-cancels-only-its-own-handle
  (doseq [lose-a? [true false]]
    (let [frame-a   (keyword "rf2-i4aj9c" (str "release-" lose-a?))
          k         {:parent actor-id :spawn [] :delay 5000}
          a-handle  (Object.)
          b-entry   {:handle (Object.) :resolved-ms 5000 :epoch 0 :state :running
                     :region nil :delay-source :literal :token ::b-token}
          cancelled (atom [])
          fired?    (atom false)]
      (rf/make-frame {:id frame-a})
      (swap! rf.machines.timer/after-timers assoc-in [frame-a k]
             {:handle a-handle :resolved-ms 5000 :epoch 0 :state :running
              :region nil :delay-source :literal :token ::a-token})
      (rf.trace.tooling/register-listener!
        ::release-fence
        (fn [ev]
          (when (and lose-a?
                     (= :rf.machine.timer/cancelled (:operation ev))
                     (compare-and-set! fired? false true))
            (rf.frame/destroy-frame! frame-a)
            (rf/make-frame {:id frame-a})
            (swap! rf.machines.timer/after-timers assoc-in [frame-a k] b-entry))))
      (try
        (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)]
          (rf.machines.timer/cancel-actor-timers! frame-a actor-id))
        (is (= [(when lose-a? b-entry) [a-handle]]
               [(get-in @rf.machines.timer/after-timers [frame-a k]) @cancelled])
            (if lose-a?
              "A's release cancels A's handle and leaves B's entry"
              "with no successor, A's release cancels its handle and empties the slot"))
        (finally
          (rf.trace.tooling/unregister-listener! ::release-fence)
          (swap! rf.machines.timer/after-timers dissoc frame-a))))))

(defn- install-watching-adapter!
  "Install a plain-atom adapter whose container write lands, then runs
  `on-write` once while `armed?` holds."
  [armed? on-write]
  (let [base-replace (:replace-container! rf.substrate.plain-atom/adapter)]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter!
      (assoc rf.substrate.plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))

(deftest classification-drop-write-watch-loss-fences-removal
  ;; A container watch destroys A and publishes a same-id B, which re-lowers
  ;; the same owner's claim, DURING A's classification-drop write.
  (let [frame-a :rf2-i4aj9c/class-drop-frame
        armed?  (atom false)
        b-state (atom nil)]
    (install-watching-adapter!
      armed?
      (fn []
        (rf.frame/destroy-frame! frame-a)
        (rf/make-frame {:id frame-a})
        (rf.machines.classification/lower-at-spawn! frame-a actor-id classified-spec)
        (reset! b-state [(elision-slot frame-a) (rf.frame/frame-commit-epoch frame-a)])))
    (try
      (rf/reg-machine actor-type classified-spec)
      (rf/make-frame {:id frame-a})
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (reset! armed? true)
        (rf.frame/call-with-event-owner-token frame-a token-a
          #(rf.machines.classification/drop-at-destroy! frame-a actor-id classified-spec token-a)))
      (is (seq (first @b-state)) "B re-lowered its claim")
      (is (= @b-state [(elision-slot frame-a) (rf.frame/frame-commit-epoch frame-a)])
          "A's drop neither removed B's claim nor bumped B's commit epoch")
      (finally
        (restore-plain-adapter!)))))
