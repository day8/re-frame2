(ns re-frame.machine-exit-cascade-incarnation-fence-cljs-test
  "The destroy-time `:exit` helper (`run-child-exit!`) keeps its post-exit
  snapshot write and its nested `:fx` walk bound to the exact frame
  incarnation that entered teardown. Here the `:exit` returns a CHANGED `:data`
  and two real effects, and ownership is lost at each of the helper's three
  callback boundaries: the destroy-exit `:rf.machine/action-ran` trace, the
  first exit effect, and a container watch on the post-exit write. A successor
  cannot be published from inside an effect (frame construction is refused
  while `*handler-scope*` is bound), so loss is the reachable case there.
  Per Spec 005 §Declarative `:spawn` §Composition with explicit `:entry` /
  `:exit`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.destroy :as rf.machines.lifecycle-fx.destroy]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private actor :rf2-fzbj-1/actor)
(def ^:private snapshot-path [:rf.runtime/machines :snapshots actor])

(defn- runtime-db [frame-id] (rf.frame/frame-runtime-db-value frame-id))
(defn- snapshot [frame-id] (get-in (runtime-db frame-id) snapshot-path))

(defn- run-destroy
  "Seed `actor` live in frame A, whose `:exit` returns a changed `:data` and two
  effects, then tear it down.

  `mode` picks the driver and the loss boundary:
    :live         — `:rf.machine/destroy` under A's event owner, no loss;
    :eventless    — `destroy-single-actor!` with NO event owner bound (the
                    frame-destroy cascade's entry), no loss;
    :action-ran   — replace A with B on the destroy-exit `:rf.machine/action-ran`;
    :first-effect — destroy A inside the first exit effect;
    :exit-write   — replace A with B from a container watch on A's post-exit write.

  The loss fires once. Every effect run is recorded with whether it ran in B
  and whether the post-exit write was visible when it ran; effects and terminal
  markers that run after the first-effect loss are recorded separately, because
  the frame-destroy cascade inside that loss runs the `:exit` again."
  [frame-id mode]
  (let [effects          (atom [])
        after-loss       (atom [])
        do-fx-in-b       (atom 0)
        do-fx-after-loss (atom 0)
        fired?           (atom false)
        lost?            (atom false)
        b-token          (atom nil)
        b-birth          (atom nil)
        b-commit         (atom nil)
        in-b?            (fn []
                           (and (some? @b-token)
                                (identical? @b-token (rf.frame/frame-incarnation-token frame-id))))
        replace!         (fn []
                           (when (compare-and-set! fired? false true)
                             (rf.frame/destroy-frame! frame-id)
                             (rf/make-frame {:id frame-id})
                             (rf.frame/swap-runtime-db! frame-id assoc-in snapshot-path
                                                        {:state :running :data {:owner :b}})
                             (reset! b-token (rf.frame/frame-incarnation-token frame-id))
                             (reset! b-birth (runtime-db frame-id))
                             (reset! b-commit (rf.frame/frame-commit-epoch frame-id))))]
    (rf/reg-fx :rf2-fzbj-1/effect
      (fn [_ctx value]
        (when @lost? (swap! after-loss conj value))
        (swap! effects conj {:value       value
                             :in-b?       (in-b?)
                             :exit-marker (get-in (snapshot frame-id) [:data :exit-marker])})
        (when (and (= :first-effect mode) (= 1 value) (compare-and-set! fired? false true))
          (rf.frame/destroy-frame! frame-id)
          (reset! lost? true))))
    (rf/reg-machine actor
      {:initial :running
       :states  {:running {:exit (fn [_]
                                   {:data {:exit-marker :from-a}
                                    :fx   [[:rf2-fzbj-1/effect 1]
                                           [:rf2-fzbj-1/effect 2]]})}}})
    (rf/make-frame {:id frame-id})
    (rf.frame/swap-runtime-db! frame-id assoc-in snapshot-path
                               {:state :running :data {:owner :a}})
    (let [token-a     (rf.frame/frame-incarnation-token frame-id)
          container-a (rf.frame/frame-state-container frame-id)]
      (rf.trace.tooling/register-listener!
        ::observer
        (fn [ev]
          (case (:operation ev)
            :rf.machine/action-ran
            (when (and (= :action-ran mode)
                       (= :destroy-exit (get-in ev [:tags :phase])))
              (replace!))

            :rf.fx/do-fx
            (do (when (in-b?) (swap! do-fx-in-b inc))
                (when @lost? (swap! do-fx-after-loss inc)))

            nil)))
      (when (= :exit-write mode)
        (add-watch container-a ::exit-write
                   (fn [_ _ _ after]
                     (when (= :from-a (get-in after (into [:rf.db/runtime] (conj snapshot-path :data :exit-marker))))
                       (replace!)))))
      (try
        (if (= :eventless mode)
          (rf.machines.lifecycle-fx.destroy/destroy-single-actor! frame-id actor)
          (rf.frame/call-with-event-owner-token frame-id token-a
            (fn [] (rf.machines.lifecycle-fx.destroy/destroy-machine-fx {:frame frame-id} actor))))
        {:b-birth          @b-birth
         :b-runtime        (runtime-db frame-id)
         :b-commit         @b-commit
         :commit           (rf.frame/frame-commit-epoch frame-id)
         :snapshot         (snapshot frame-id)
         :effects          @effects
         :after-loss       @after-loss
         :do-fx-in-b       @do-fx-in-b
         :do-fx-after-loss @do-fx-after-loss}
        (finally
          (rf.trace.tooling/unregister-listener! ::observer)
          (remove-watch container-a ::exit-write))))))

(defn- assert-b-untouched [{:keys [b-birth b-runtime b-commit commit effects do-fx-in-b]}]
  ;; b-birth is nil unless the replacement ran, so the first pair also proves
  ;; the fence was exercised.
  (is (= [b-birth b-commit [] 0]
         [b-runtime commit (filterv :in-b? effects) do-fx-in-b])
      "B's runtime-db and commit epoch are unchanged since birth, and no A-derived exit effect or terminal :rf.fx/do-fx marker ran in B"))

(deftest action-ran-loss-fences-exit-write-and-effects
  (assert-b-untouched (run-destroy :rf2-fzbj-1/action-ran-frame :action-ran)))

(deftest first-effect-loss-fences-remaining-exit-effects
  (testing "the nested walk carries A's exact token, so the second effect and the terminal marker do not run"
    (let [{:keys [effects after-loss do-fx-after-loss]} (run-destroy :rf2-fzbj-1/effect-frame :first-effect)]
      (is (= [{:value 1 :in-b? false :exit-marker :from-a} [] 0]
             [(first effects) after-loss do-fx-after-loss])))))

(deftest exit-write-watch-loss-fences-epoch-and-effects
  (assert-b-untouched (run-destroy :rf2-fzbj-1/write-frame :exit-write)))

(deftest owner-intact-exit-writes-then-fires-then-tears-down
  (testing "with no loss — under A's event owner, or eventless as the frame-destroy cascade runs it — the exit write lands before both effects run, in order, and the snapshot is removed"
    (doseq [[frame-id mode] [[:rf2-fzbj-1/live-frame :live]
                             [:rf2-fzbj-1/eventless-frame :eventless]]]
      (is (= {:effects  [{:value 1 :in-b? false :exit-marker :from-a}
                         {:value 2 :in-b? false :exit-marker :from-a}]
              :snapshot nil}
             (select-keys (run-destroy frame-id mode) [:effects :snapshot]))
          (str mode)))))
