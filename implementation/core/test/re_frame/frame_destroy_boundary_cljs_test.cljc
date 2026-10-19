(ns re-frame.frame-destroy-boundary-cljs-test
  "A destroy requested while the frame's drain is on the stack is PENDING
  (Spec 002 §Destroy): the event in flight finishes as an ordinary event — its
  `:db` commits, its `:fx` run, its epoch settles `:ok` — and at the next event
  boundary the drain drops both lanes, reports them in one
  `:rf.frame/drain-interrupted`, and runs the teardown, `:on-destroy` first.

  Every case here is synchronous on both hosts. Async scheduling is captured
  through the `rf.interop/next-tick` seam and run by hand, so the external lane
  can be filled deterministically. Trace and epoch facts are dev-only, so each
  read of them sits in a `rf.interop/debug-enabled?` arm; the always-on facts
  (state, handler runs, the error channel) are asserted in every posture."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(defn- with-captured-ticks
  "Run `f` with `rf.interop/next-tick` capturing its callbacks instead of
  scheduling them, then run the captured callbacks (and any they schedule) in
  order. Returns `f`'s value."
  [f]
  (let [ticks  (atom [])
        result (with-redefs [rf.interop/next-tick (fn [cb] (swap! ticks conj cb) nil)]
                 (f))]
    (loop []
      (when-let [cb (first @ticks)]
        (swap! ticks #(vec (rest %)))
        (cb)
        (recur)))
    result))

(defn- watch-ops!
  "Register a trace listener under `k` collecting events for `frame-id` whose
  `:operation` is in `ops`; returns the collecting atom."
  [k frame-id ops]
  (let [seen (atom [])]
    (rf/register-listener! :trace k
      (fn [ev]
        (when (and (= frame-id (get-in ev [:tags :frame]))
                   (contains? ops (:operation ev)))
          (swap! seen conj ev))))
    seen))

(defn- error-id [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) e
         (:rf.error/id (ex-data e)))))

(deftest a-self-destroying-event-finishes-as-an-ordinary-event
  ;; The handler asks for its own frame's destroy twice (the second request
  ;; joins the first), then returns a db and effects. The db commits, both
  ;; effects run — one of them dispatches into another, live frame, which
  ;; handles it — the epoch settles :ok, and only then does :on-destroy run,
  ;; once, against the committed db.
  (let [log (atom [])]
    (rf/make-frame {:id :bnd/other})
    (rf/reg-event :bnd/other-hears
      (fn [{:keys [db]} [_ v]] {:db (assoc db :heard v)}))
    (rf/reg-fx :bnd/note (fn [_ v] (swap! log conj [:fx v])))
    (rf/reg-fx :bnd/to-other
      (fn [_ v] (rf/dispatch [:bnd/other-hears v] {:frame :bnd/other})))
    (rf/reg-event :bnd/cleanup
      (fn [{:keys [db]} _] (swap! log conj [:on-destroy db]) {}))
    (rf/reg-event :bnd/self-destroy
      (fn [_ _]
        (rf/destroy-frame! :bnd/a)
        (rf/destroy-frame! :bnd/a)
        (swap! log conj [:handler-returned (some? (rf.frame/frame :bnd/a))])
        {:db {:n 1}
         :fx [[:bnd/note :ran]
              [:bnd/to-other :from-a]]}))
    (rf/make-frame {:id :bnd/a :on-destroy [:bnd/cleanup]})
    (rf/register-listener! :epoch ::self-destroy-epochs
      (fn [record]
        (when (= :bnd/a (:frame record))
          (swap! log conj [:epoch (:event-id record) (:outcome record)
                           (:db-after record)]))))
    (try
      (with-captured-ticks
        #(rf/dispatch-sync [:bnd/self-destroy] {:frame :bnd/a}))
      ;; The private cleanup event settles its own record after it.
      (is (= (if rf.interop/debug-enabled?
               [[:handler-returned true] [:fx :ran]
                [:epoch :bnd/self-destroy :ok {:n 1}]
                [:on-destroy {:n 1}]
                [:epoch :bnd/cleanup :ok {:n 1}]]
               [[:handler-returned true] [:fx :ran] [:on-destroy {:n 1}]])
             @log)
          "the event ran to its end and settled :ok before :on-destroy ran once")
      (is (= [nil {:heard :from-a}]
             [(rf.frame/frame :bnd/a) (rf.frame/frame-app-db-value :bnd/other)])
          "the frame is gone, and its dispatch to the live frame was handled there")
      (finally
        (rf/unregister-listener! :epoch ::self-destroy-epochs)))))

(deftest the-boundary-drops-both-lanes-and-reports-them-once
  ;; Two external events wait in the external lane; the destroying event is
  ;; dispatch-synced to the head of the internal lane and queues two children.
  ;; At its boundary all four are dropped — the event's own descendants
  ;; included — and counted in ONE :rf.frame/drain-interrupted. The dropped
  ;; work yields one :halted-destroy record whose trigger is the event the
  ;; drain would have dequeued next.
  (let [ran      (atom [])
        records  (atom [])
        seen     (watch-ops! ::lanes :bnd/lanes #{:rf.frame/drain-interrupted})]
    (rf/reg-event :bnd/ext (fn [_ [_ n]] (swap! ran conj [:ext n]) {}))
    (rf/reg-event :bnd/child (fn [_ [_ n]] (swap! ran conj [:child n]) {}))
    (rf/reg-event :bnd/destroy-with-children
      (fn [_ _]
        (swap! ran conj [:destroyer])
        (rf/destroy-frame! :bnd/lanes)
        {:db {:destroyed-by :event}
         :fx [[:dispatch [:bnd/child 1]]
              [:dispatch [:bnd/child 2]]]}))
    (rf/make-frame {:id :bnd/lanes})
    (rf/register-listener! :epoch ::lanes-epochs
      (fn [record]
        (when (and (= :bnd/lanes (:frame record))
                   (= :halted-destroy (:outcome record)))
          (swap! records conj record))))
    (try
      (with-captured-ticks
        (fn []
          (rf/dispatch [:bnd/ext 1] {:frame :bnd/lanes})
          (rf/dispatch [:bnd/ext 2] {:frame :bnd/lanes})
          (rf/dispatch-sync [:bnd/destroy-with-children] {:frame :bnd/lanes})))
      (is (= [[[:destroyer]] nil]
             [@ran (rf.frame/frame :bnd/lanes)])
          "only the destroying event ran; nothing queued behind it ever did")
      (when rf.interop/debug-enabled?
        (is (= [[4]
                [{:event-id    :bnd/child
                  :trigger     [:bnd/child 1]
                  :halt-reason {:operation     :rf.frame/drain-interrupted
                                :dropped-count 4
                                :last-event-id :bnd/destroy-with-children}
                  :db-before   {:destroyed-by :event}
                  :db-after    {:destroyed-by :event}}]]
               [(mapv #(get-in % [:tags :dropped-count]) @seen)
                (mapv (fn [r] {:event-id    (:event-id r)
                               :trigger     (:trigger-event r)
                               :halt-reason (:halt-reason r)
                               :db-before   (:db-before r)
                               :db-after    (:db-after r)})
                      @records)])
            "one report counts all four; one :halted-destroy names the next event"))
      (finally
        (rf/unregister-listener! :trace ::lanes)
        (rf/unregister-listener! :epoch ::lanes-epochs)))))

(deftest nothing-dropped-means-no-halted-destroy-record
  ;; A pending destroy with an empty queue still reports its boundary, with a
  ;; zero count, but there is no dropped work for a :halted-destroy record.
  (let [records (atom [])
        seen    (watch-ops! ::empty :bnd/empty #{:rf.frame/drain-interrupted})]
    (rf/reg-event :bnd/quiet-destroy
      (fn [_ _] (rf/destroy-frame! :bnd/empty) {:db {:ok true}}))
    (rf/make-frame {:id :bnd/empty})
    (rf/register-listener! :epoch ::empty-epochs
      (fn [record]
        (when (= :bnd/empty (:frame record))
          (swap! records conj (:outcome record)))))
    (try
      (rf/dispatch-sync [:bnd/quiet-destroy] {:frame :bnd/empty})
      (is (nil? (rf.frame/frame :bnd/empty)))
      (when rf.interop/debug-enabled?
        (is (= [[0] [:ok]]
               [(mapv #(get-in % [:tags :dropped-count]) @seen) @records])))
      (finally
        (rf/unregister-listener! :trace ::empty)
        (rf/unregister-listener! :epoch ::empty-epochs)))))

(deftest a-destroy-from-a-nested-cross-frame-dispatch-sync-waits-for-the-outer-event
  ;; The outer frame's handler dispatch-syncs into the inner frame, whose
  ;; handler destroys the OUTER frame. The outer drain is on the stack, so the
  ;; destroy is pending: the inner event finishes, the outer handler resumes
  ;; and commits, and the outer frame is torn down at its own boundary.
  (let [log (atom [])]
    (rf/make-frame {:id :bnd/inner})
    (rf/reg-event :bnd/inner-kills-outer
      (fn [_ _]
        (rf/destroy-frame! :bnd/outer)
        (swap! log conj [:inner-ran (some? (rf.frame/frame :bnd/outer))])
        {:db {:killed :outer}}))
    (rf/reg-event :bnd/outer-event
      (fn [_ _]
        (rf/dispatch-sync [:bnd/inner-kills-outer] {:frame :bnd/inner})
        (swap! log conj [:outer-resumed (some? (rf.frame/frame :bnd/outer))])
        {:db {:outer :committed}}))
    (rf/reg-event :bnd/outer-cleanup
      (fn [{:keys [db]} _] (swap! log conj [:on-destroy db]) {}))
    (rf/make-frame {:id :bnd/outer :on-destroy [:bnd/outer-cleanup]})
    (rf/dispatch-sync [:bnd/outer-event] {:frame :bnd/outer})
    (is (= [[:inner-ran true]
            [:outer-resumed true]
            [:on-destroy {:outer :committed}]]
           @log)
        "the destroy waited for the outer event, whose commit :on-destroy saw")
    (is (= [nil {:killed :outer}]
           [(rf.frame/frame :bnd/outer) (rf.frame/frame-app-db-value :bnd/inner)]))))

(deftest destroy-then-throw-is-reported-and-the-frame-is-still-torn-down
  ;; The handler requests its own frame's destroy and then throws. The throw is
  ;; captured and reported like any handler throw, the event settles, the
  ;; frame is torn down at the boundary, and the reservation is released, so
  ;; the id is free for a fresh frame.
  (let [errors (atom [])]
    (rf.error-emit/register-error-listener! ::destroy-then-throw
      (fn [record]
        (when (= :bnd/thrower (:frame record))
          (swap! errors conj (:rf.error/id record)))))
    (rf/reg-event :bnd/destroy-then-throw
      (fn [_ _]
        (rf/destroy-frame! :bnd/thrower)
        (throw (ex-info "handler failed after asking for its destroy" {}))))
    (rf/make-frame {:id :bnd/thrower})
    (try
      (is (nil? (rf/dispatch-sync [:bnd/destroy-then-throw] {:frame :bnd/thrower})))
      (is (= [[:rf.error/handler-exception] nil]
             [@errors (rf.frame/frame :bnd/thrower)])
          "the throw is reported once and the frame is gone")
      (rf/make-frame {:id :bnd/thrower})
      (is (some? (rf.frame/frame :bnd/thrower))
          "the reservation was released: the id seats a fresh frame")
      (finally
        (rf.error-emit/unregister-error-listener! ::destroy-then-throw)))))

(deftest a-self-destroy-inside-initial-events-fails-the-construction
  ;; A setup step destroys the frame it is constructing. The step settles, the
  ;; frame is torn down at its boundary, and the remaining steps find no frame;
  ;; construction then fails fast because the row it would finalize is gone.
  ;; The reservation is released, so the id can be constructed again.
  (let [ran (atom [])]
    (rf/reg-event :bnd/init-destroy
      (fn [_ _] (swap! ran conj :init-destroy) (rf/destroy-frame! :bnd/init) {:db {:set-up 1}}))
    (rf/reg-event :bnd/init-after
      (fn [_ _] (swap! ran conj :init-after) {}))
    (is (= :rf.error/frame-construction-in-progress
           (error-id #(rf/make-frame {:id :bnd/init
                                      :initial-events [[:bnd/init-destroy]
                                                       [:bnd/init-after]]}))))
    (is (= [[:init-destroy] nil]
           [@ran (rf.frame/frame :bnd/init)])
        "the destroying step ran, the next never did, and no frame is left")
    (rf/make-frame {:id :bnd/init})
    (is (some? (rf.frame/frame :bnd/init))
        "the id is free once the failed construction has unwound")))

(deftest make-frame-inside-the-destroying-handler-meets-the-reservation
  ;; The destroy request took the id's reservation, and `make-frame` claims the
  ;; reservation before it checks for handler scope, so a same-id construction
  ;; inside the destroying handler fails with the in-progress error. A
  ;; different id still meets the handler-scope refusal.
  (let [outcome (atom nil)]
    (rf/reg-event :bnd/destroy-and-rebuild
      (fn [_ _]
        (rf/destroy-frame! :bnd/rebuild)
        (reset! outcome
                [(error-id #(rf/make-frame {:id :bnd/rebuild}))
                 (error-id #(rf/make-frame {:id :bnd/elsewhere}))])
        {:db {:after :attempt}}))
    (rf/make-frame {:id :bnd/rebuild})
    (rf/dispatch-sync [:bnd/destroy-and-rebuild] {:frame :bnd/rebuild})
    (is (= [[:rf.error/frame-construction-in-progress
             :rf.error/frame-construction-in-handler]
            nil
            nil]
           [@outcome (rf.frame/frame :bnd/rebuild) (rf.frame/frame :bnd/elsewhere)]))))
