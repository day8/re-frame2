(ns state-machine-walkthrough.core
  "Runnable companion to docs/guide/05-state-machines.md.

  This is the login-flow chapter as code. Every prose snippet in ch.05
  appears here in the order the chapter introduces it; each section
  ends with a smoke-test fn that drives the machine through the
  scenario the chapter describes.

  Why .cljc: the chapter promises 'runs in microseconds on the JVM, no
  browser, no network.' The same code runs under shadow-cljs node-test
  for the CLJS surface. The HTTP side is exercised via the id-valued
  override seam (`:fx-overrides`) so no real network traffic happens.

  Read alongside docs/guide/05-state-machines.md."
  (:require [re-frame.core :as rf]))

;; ============================================================================
;; THE TRANSITION TABLE — chapter §The same flow as a machine
;; ============================================================================
;;
;; Pure data. `:guards` and `:actions` live with the spec — there is no
;; global registry. References inside `:states` resolve against this
;; map; cross-machine reuse is via Clojure vars (define a fn, name it
;; locally in each machine's :guards / :actions).

(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit
    ;; 2-arity is canonical: (fn [data event] ...). `data` is the
    ;; snapshot's :data slot directly — pulling it from a snapshot
    ;; wrapper is the runtime's job.
    (fn [data _event]
      (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_data _event] {:data {:error nil}})

    :issue-request
    ;; Returns effects, not side-effects. The :http fx implementation
    ;; conj's the response onto the :on-success template; the runtime
    ;; folds the trailing arg onto the inner event.
    (fn [_data [_ creds]]
      {:fx [[:http {:method     :post
                    :url        "/api/login"
                    :body       creds
                    :on-success [:auth.login/flow [:auth.login/success]]
                    :on-error   [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [data [_ err]]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message err) "Login failed.")))})

    :lock-account
    (fn [_data _event]
      {:fx [[:http {:method :post :url "/api/auth/lock"}]]})

    :store-session
    (fn [_data [_ {:keys [token]}]]
      {:fx [[:auth.session/store {:token token}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :action :clear-error}}}

    :submitting
    {:entry :issue-request
     :on    {:auth.login/success {:target :authed
                                  :action :store-session}
             :auth.login/failure [{:target :error-shown
                                   :guard  :under-retry-limit
                                   :action :record-error}
                                  {:target :locked-out
                                   :action :lock-account}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

    :authed      {:meta {:terminal? true}}
    :locked-out  {:meta {:terminal? true}}}})

;; ============================================================================
;; FX — chapter §Wiring a machine into the rest of re-frame
;; ============================================================================
;;
;; The :http and :auth.session/store fx are stubs for the example: they
;; show the shape, not real network/storage. The smoke test below uses
;; the id-valued override seam (`:fx-overrides`) to swap :http for a
;; canned-success or canned-failure stub at frame-creation time.

(rf/reg-fx :http
  {:doc "Stub: a real implementation would call (js/fetch ...)."}
  (fn [_m _args] nil))

(rf/reg-fx :auth.session/store
  {:doc "Stub: a real implementation would write localStorage."}
  (fn [_m _args] nil))

(rf/reg-fx :http.canned-success
  {:doc "Test stub: every :http call resolves to a canned success."}
  (fn [{:keys [frame]} {:keys [on-success]}]
    (when on-success
      (rf/dispatch (conj on-success {:user  {:id "test-user"}
                                     :token "test-token"})
                   {:frame frame}))))

(rf/reg-fx :http.canned-failure
  {:doc "Test stub: every :http call resolves to a canned failure."}
  (fn [{:keys [frame]} {:keys [on-error]}]
    (when on-error
      (rf/dispatch (conj on-error {:message "bad creds"})
                   {:frame frame}))))

;; ============================================================================
;; REGISTRATION — chapter §Wiring a machine into the rest of re-frame
;; ============================================================================
;;
;; Two equivalent forms; we use the convenience `reg-machine`. The
;; longer form `(reg-event-fx machine-id (create-machine-handler m))`
;; is what reg-machine wraps, and is the form to use when you need
;; registration metadata (`:doc`, `:interceptors`, ...).

(rf/reg-machine :auth.login/flow login-flow)

;; ============================================================================
;; SUBSCRIPTIONS — chapter §Reading a machine: sub-machine
;; ============================================================================

(rf/reg-sub :auth.login/state
  (fn [db _] (get-in db [:rf/machines :auth.login/flow :state])))

(rf/reg-sub :auth.login/error
  (fn [db _] (get-in db [:rf/machines :auth.login/flow :data :error])))

(rf/reg-sub :auth.login/submitting?
  :<- [:auth.login/state]
  (fn [state _] (= :submitting state)))

(rf/reg-sub :auth.login/authenticated?
  :<- [:auth.login/state]
  (fn [state _] (= :authed state)))

;; ============================================================================
;; HEADLESS TESTS — chapter §Headless testing
;; ============================================================================
;;
;; Two flavours of test:
;;
;; 1. Pure machine-transition: pass a snapshot + event, get back the
;;    next snapshot. No frame, no app-db, no fx execution. JVM-runnable.
;;
;; 2. Drain-level: spin up a frame with a fx-override, dispatch into
;;    the machine id, read the resulting app-db slice. Exercises the
;;    full registration → drain → snapshot-write path including the
;;    :on-success / :on-error callback fold.

(defn pure-happy-path-test
  "Drives the transition table directly via machine-transition. No
  frame, no app-db. The chapter's first test."
  []
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        [s1 fx1] (rf/machine-transition login-flow s0
                                        [:auth.login/submit
                                         {:email "a@b.com" :password "secret"}])]
    (assert (= :submitting (:state s1))
            (str "expected :submitting, got " (:state s1)))
    ;; Entering :submitting fires the :issue-request action's :fx.
    (assert (= 1 (count fx1)) (str "expected one :http fx, got " fx1))
    (assert (= :http (ffirst fx1)) (str "expected :http fx-id, got " (ffirst fx1)))

    (let [[s2 _] (rf/machine-transition login-flow s1
                                        [:auth.login/success {:token "t"}])]
      (assert (= :authed (:state s2))
              (str "expected :authed, got " (:state s2))))
    :ok))

(defn pure-lockout-test
  "Once :data :attempts reaches the retry limit, the :under-retry-limit
  guard fails and the second :auth.login/failure clause's :locked-out
  target wins. The guard checks the snapshot BEFORE the action runs;
  :record-error then bumps the counter on hits, so attempts=3 is the
  first counter value at which the guard rejects."
  []
  (let [snapshot {:state :submitting :data {:attempts 3 :error nil}}
        [s _fx] (rf/machine-transition login-flow snapshot
                                       [:auth.login/failure {:message "bad creds"}])]
    (assert (= :locked-out (:state s))
            (str "expected :locked-out at attempts=3, got " (:state s)))
    :ok))

(defn drain-happy-path-test
  "Full drain: registers the machine, dispatches into it, asserts the
  app-db landed at :authed. Uses the fx-overrides seam to swap :http
  for a canned-success stub."
  []
  (let [f (rf/make-frame {:fx-overrides {:http :http.canned-success}})]
    (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                          {:email "a@b.com"
                                           :password "secret"}]]
                      {:frame f})
    (let [state (rf/compute-sub [:auth.login/state] (rf/get-frame-db f))]
      (assert (= :authed state)
              (str "expected :authed after canned success, got " state)))
    :ok))

(defn drain-retry-then-lockout-test
  "Three failures cycle :submitting → :error-shown → :idle ×3, then a
  fourth :submit fails the guard and lands at :locked-out."
  []
  (let [f (rf/make-frame {:fx-overrides {:http :http.canned-failure}})]
    (dotimes [_ 3]
      (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                            {:email "x@y.z" :password "wrong"}]]
                        {:frame f})
      (rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame f}))
    (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                          {:email "x@y.z" :password "wrong"}]]
                      {:frame f})
    (let [state (rf/compute-sub [:auth.login/state] (rf/get-frame-db f))]
      (assert (= :locked-out state)
              (str "expected :locked-out on 4th attempt, got " state)))
    :ok))

(defn smoke-tests
  "Run all four headless tests. Returns :ok or throws."
  []
  (pure-happy-path-test)
  (pure-lockout-test)
  (drain-happy-path-test)
  (drain-retry-then-lockout-test)
  :ok)
