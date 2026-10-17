(ns re-frame.frame-destroy-image-exit-cljs-test
  "A frame's machine teardown resolves through the frame's own image, whichever
  stack destroys the frame: at top level, at the boundary of the frame's own
  event, from another frame's effect, or at the boundary of the frame's own
  event when another frame's effect `dispatch-sync`ed into it.

  Two namespaces each register one machine id, and one effect id its `:exit`
  emits, with different logs. Frames A and B each select one of the two, so
  the definitions each frame's image resolves are the only thing telling the
  two teardowns apart. Each frame holds one actor of that id. Destroying A runs
  A's `:exit` and A's effect, and leaves B's actor alive."
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

(def ^:private shared {:rf.provenance/ns "fdix.shared"})

(defn- reg-side!
  "Register `:fdix/type` and the `:fdix/note` effect its `:exit` emits from
  namespace `fdix.<side>`, logging `[side :entry]`, `[side :exit]` and
  `[side :exit-fx]` into `log`."
  [log side]
  (let [provenance {:rf.provenance/ns (str "fdix." (name side))}]
    (rf/reg-fx :fdix/note provenance (fn [_ _] (swap! log conj [side :exit-fx])))
    (rf.machines/reg-machine* :fdix/type provenance
      {:initial :running
       :data    {}
       :states  {:running {:entry (fn [_] (swap! log conj [side :entry]) {})
                           :exit  (fn [_]
                                    (swap! log conj [side :exit])
                                    {:fx [[:fdix/note true]]})}}})))

(defn- make-side-frame!
  "Make `frame-id` with an image selecting `fdix.<side>` and the shared
  namespace, and spawn one `:fdix/type` actor in it."
  [frame-id side]
  (rf/make-frame {:id     frame-id
                  :images [(rf/image {:select-ns {:include [(str "fdix." (name side)) "fdix.shared"]}})]})
  (rf/dispatch-sync [:fdix/spawn] {:frame frame-id}))

(defn- actor? [frame-id]
  (some? (rf.machines.test-support/snapshot frame-id :fdix/actor)))

(deftest destroying-a-frame-runs-the-exit-its-own-image-resolves
  (let [log (atom [])]
    (reg-side! log :a)
    (reg-side! log :b)
    (rf/reg-fx :fdix/destroy-frame shared (fn [_ frame-id] (rf/destroy-frame! frame-id)))
    (rf/reg-fx :fdix/dispatch-sync shared (fn [_ [event frame-id]] (rf/dispatch-sync event {:frame frame-id})))
    (rf/reg-event :fdix/spawn shared
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :fdix/type :fixed-actor-id :fdix/actor}]]}))
    (rf/reg-event :fdix/destroy shared (fn [_ [_ frame-id]] {:fx [[:fdix/destroy-frame frame-id]]}))
    (rf/reg-event :fdix/destroy-via shared
      (fn [_ [_ frame-id]] {:fx [[:fdix/dispatch-sync [[:fdix/destroy frame-id] frame-id]]]}))
    (doseq [[mode destroy!]
            [[:top-level (fn [a _] (rf/destroy-frame! a))]
             ;; A's own effect destroys A: a pending destroy whose boundary is
             ;; reached in A's own drain.
             [:own-drain (fn [a _] (rf/dispatch-sync [:fdix/destroy a] {:frame a}))]
             ;; B's effect destroys A: an immediate destroy inside B's event.
             [:from-b    (fn [a b] (rf/dispatch-sync [:fdix/destroy a] {:frame b}))]
             ;; B's effect runs an A event that destroys A: a pending destroy
             ;; whose boundary is reached inside B's event.
             [:pending   (fn [a b] (rf/dispatch-sync [:fdix/destroy-via a] {:frame b}))]]]
      (let [frame-a (keyword "fdix.a" (name mode))
            frame-b (keyword "fdix.b" (name mode))]
        (reset! log [])
        (make-side-frame! frame-a :a)
        (make-side-frame! frame-b :b)
        (let [entry   @log
              before  (actor? frame-a)]
          (reset! log [])
          (destroy! frame-a frame-b)
          (is (= {:entry          [[:a :entry] [:b :entry]]
                  :a-actor-before true
                  :exit           [[:a :exit] [:a :exit-fx]]
                  :a-frame        nil
                  :b-actor-after  true}
                 {:entry          entry
                  :a-actor-before before
                  :exit           @log
                  :a-frame        (rf.frame/frame frame-a)
                  :b-actor-after  (actor? frame-b)})
              (str mode)))))))
