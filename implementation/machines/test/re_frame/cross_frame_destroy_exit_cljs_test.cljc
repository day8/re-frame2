(ns re-frame.cross-frame-destroy-exit-cljs-test
  "A frame's machine teardown behaves the same whichever stack destroys the
  frame: at top level, at the boundary of the frame's own event, from another
  frame's effect, or at the boundary of the frame's own event when another
  frame's effect `dispatch-sync`ed into it.

  Frame A holds two spawned actors: a sibling, then a killer whose `:exit`
  destroys the sibling. The frame-destroy walker tears the killer down first
  (newest first), so the sibling is destroyed by the killer's `:exit` and the
  walker then finds it gone. The sibling's `:exit` therefore runs once, and the
  effect it emits sees the sibling's own `:exit`-time `:data` write."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- spawn-fx [t] [:rf.machine/spawn {:machine-id t :id-prefix t}])

(defn- boot-frame-a!
  "Make frame `frame-a` holding `:xfd/sib#1` then `:xfd/killer#1`, and return
  the log the two actors' `:exit`s and the sibling's `:exit` effect append to."
  [frame-a]
  (let [log (atom [])]
    (rf/reg-fx :xfd/record
      (fn [_ v]
        (swap! log conj [v (get-in (rf.machines.test-support/snapshot frame-a :xfd/sib#1)
                                   [:data :exit-marker])])))
    (rf/reg-machine :xfd/sib
      {:initial :running
       :data    {}
       :states  {:running {:exit (fn [_]
                                   (swap! log conj :sib-exit)
                                   {:data {:exit-marker :exited}
                                    :fx   [[:xfd/record :sib-effect]]})}}})
    (rf/reg-machine :xfd/killer
      {:initial :running
       :data    {}
       :states  {:running {:exit (fn [_]
                                   (swap! log conj :killer-exit)
                                   {:fx [[:rf.machine/destroy :xfd/sib#1]]})}}})
    (rf/reg-machine :xfd/boot
      {:initial :idle
       :data    {}
       :states  {:idle {:on {:go {:action (fn [_] {:fx [(spawn-fx :xfd/sib)
                                                        (spawn-fx :xfd/killer)]})}}}}})
    (rf/make-frame {:id frame-a})
    (rf/dispatch-sync [:xfd/boot [:go]] {:frame frame-a})
    (reset! log [])
    log))

(deftest exit-destroying-a-sibling-runs-its-exit-once-whoever-destroys-the-frame
  (rf/reg-fx :xfd/destroy-frame (fn [_ frame-id] (rf/destroy-frame! frame-id)))
  (rf/reg-fx :xfd/dispatch-sync (fn [_ [event frame-id]] (rf/dispatch-sync event {:frame frame-id})))
  (rf/reg-event :xfd/destroy (fn [_ [_ frame-id]] {:fx [[:xfd/destroy-frame frame-id]]}))
  (rf/reg-event :xfd/destroy-via (fn [_ [_ frame-id]]
                                   {:fx [[:xfd/dispatch-sync [[:xfd/destroy frame-id] frame-id]]]}))
  (rf/make-frame {:id :xfd/b})
  (doseq [[frame-a destroy!]
          [[:xfd/a-top-level #(rf/destroy-frame! :xfd/a-top-level)]
           ;; A's own effect destroys A: a pending destroy whose boundary is
           ;; reached in A's own drain, with no other frame's event on the stack.
           [:xfd/a-own-drain #(rf/dispatch-sync [:xfd/destroy :xfd/a-own-drain] {:frame :xfd/a-own-drain})]
           ;; B's effect destroys A: an immediate destroy inside B's event.
           [:xfd/a-from-b    #(rf/dispatch-sync [:xfd/destroy :xfd/a-from-b] {:frame :xfd/b})]
           ;; B's effect runs an A event that destroys A: a pending destroy whose
           ;; boundary is reached inside B's event.
           [:xfd/a-pending   #(rf/dispatch-sync [:xfd/destroy-via :xfd/a-pending] {:frame :xfd/b})]]]
    (let [log (boot-frame-a! frame-a)]
      (destroy!)
      (is (= {:log   [:killer-exit :sib-exit [:sib-effect :exited]]
              :frame nil}
             {:log   @log
              :frame (rf.frame/frame frame-a)})
          (str frame-a)))))
