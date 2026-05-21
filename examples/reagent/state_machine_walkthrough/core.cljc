(ns state-machine-walkthrough.core
  "Runnable companion to docs/guide/11-machines.md.

  This is the login-flow chapter as code. Every prose snippet in ch.09
  appears here in the order the chapter introduces it; each section
  ends with a smoke-test fn that drives the machine through the
  scenario the chapter describes.

  Why .cljc: the chapter promises 'runs in microseconds on the JVM, no
  browser, no network.' The same code runs under shadow-cljs node-test
  for the CLJS surface. The HTTP side is exercised via the framework-
  shipped `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`
  stubs (Spec 014 §Testing) so no real network traffic happens.

  Read alongside docs/guide/11-machines.md."
  (:require [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine and
            ;; rf/machine-transition resolve.
            [re-frame.machines]
            [re-frame.machines.result :as result]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; The login flow's `:auth.login/login-attempt` action
            ;; dispatches `:rf.http/managed` (overridden in tests via
            ;; `:fx-overrides` to the framework-shipped canned stubs).
            ;; Loading the ns here registers the `:rf.http/managed` fx
            ;; family so the override mechanism can target a real fx-id.
            [re-frame.http-managed]
            ;; rf2-cdmle — :fx-overrides into :rf.http/managed-canned-*
            ;; relies on those fx ids being registered. Per the gate
            ;; change, registration moved to re-frame.http-test-support.
            [re-frame.http-test-support]
            [re-frame.registrar :as registrar]))

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
    ;; 2-arity is canonical: (fn [{data :data event :event}] ...). `data` is the
    ;; snapshot's :data slot directly — pulling it from a snapshot
    ;; wrapper is the runtime's job.
    (fn [{data :data}]
      (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    ;; Returns effects, not side-effects. The `:rf.http/managed` fx
    ;; (Spec 014) issues the request; the framework dispatches the
    ;; explicit `:on-success` / `:on-failure` events with the reply
    ;; payload appended as the last arg, so the inner sub-event lands
    ;; back in this machine via :auth.login/flow's machine-id routing.
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             {:request    {:method :post
                           :url    "/api/login"
                           :body   creds
                           :request-content-type :json}
              :decode     :json
              :on-success [:auth.login/flow [:auth.login/success]]
              :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [{data :data [_ {:keys [failure]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message failure) "Login failed.")))})

    :lock-account
    (fn [_]
      {:fx [[:rf.http/managed
             {:request {:method :post :url "/api/auth/lock"}}]]})

    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :action :clear-error}}}

    :submitting
    ;; :auth/busy tag — views query (rf/machine-has-tag? :auth.login/flow
    ;; :auth/busy) to disable inputs and re-label the submit button
    ;; while the request is in flight (ch.09 §State tags).
    {:tags  #{:auth/busy}
     :entry :issue-request
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

    :authed
    ;; :auth/authenticated tag — views query
    ;; (rf/machine-has-tag? :auth.login/flow :auth/authenticated) once the
    ;; flow reaches this terminal state.
    {:tags #{:auth/authenticated}
     :meta {:terminal? true}}

    :locked-out
    ;; :auth/locked tag — root-view swaps the form for the locked-out
    ;; panel when (rf/machine-has-tag? :auth.login/flow :auth/locked) is true.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; ============================================================================
;; FX — chapter §Wiring a machine into the rest of re-frame
;; ============================================================================
;;
;; The `:auth.session/store` fx is a stub for the example: it shows the
;; shape, not real localStorage. The smoke tests below exercise the
;; managed-HTTP path via the framework-shipped canned-success /
;; canned-failure stubs (Spec 014 §Testing), routed in via the
;; `:fx-overrides` seam at frame creation.

(rf/reg-fx :auth.session/store
  {:doc "Stub: a real implementation would write localStorage."}
  (fn [_m _args] nil))

;; ============================================================================
;; REGISTRATION — chapter §Wiring a machine into the rest of re-frame
;; ============================================================================
;;
;; Two equivalent forms; we use the convenience `reg-machine`. The
;; longer form `(reg-event-fx machine-id (make-machine-handler m))`
;; is what reg-machine wraps, and is the form to use when you need
;; registration metadata (`:doc`, `:interceptors`, ...).

(rf/reg-machine :auth.login/flow login-flow)

;; ============================================================================
;; SUBSCRIPTIONS — chapter §Reading a machine: sub-machine
;; ============================================================================

;; The machine snapshot lives at [:rf/machines :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces. The
;; "in :submitting?" / "in :authed?" / "in :locked-out?" predicates
;; moved to the `rf/machine-has-tag?` queries in views.cljs (ch.09 §State
;; tags) — discriminating on the machine's runtime-projected `:tags`
;; set decouples view code from individual state-keyword identity.

(rf/reg-sub :auth.login/state
  (fn [db _] (get-in db [:rf/machines :auth.login/flow :state])))

(rf/reg-sub :auth.login/error
  (fn [db _] (get-in db [:rf/machines :auth.login/flow :data :error])))

;; ============================================================================
;; TEST STUBS — per-test wrappers that delegate to the framework-shipped
;; canned-success / canned-failure stubs.
;; ============================================================================
;;
;; Per Spec 014 §Testing, the framework ships `:rf.http/managed-canned-success`
;; and `:rf.http/managed-canned-failure` fxs that synthesise the canonical
;; reply shape. Per-test wrappers delegate to those stubs while supplying
;; the test-specific `:value` (success) / failure category.

(rf/reg-fx :auth.login/canned-success
  {:doc "Test stub: every `:rf.http/managed` call resolves :success with a
         canned user/token payload. Delegates to the framework-shipped
         `:rf.http/managed-canned-success` per Spec 014 §Testing."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :value {:user  {:id "test-user"}
                                              :token "test-token"})))))

(rf/reg-fx :auth.login/canned-failure
  {:doc "Test stub: every `:rf.http/managed` call resolves :failure.
         Delegates to the framework-shipped `:rf.http/managed-canned-failure`
         per Spec 014 §Testing."}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
      (stub frame-ctx (assoc args-map
                             :kind :rf.http/http-4xx
                             :tags {:message "bad creds" :status 401})))))

;; ============================================================================
;; HEADLESS TESTS — chapter §Headless testing
;; ============================================================================
;;
;; Two flavours of test:
;;
;; 1. Pure machine-transition: pass a snapshot + event, get back the
;;    next snapshot. No frame, no app-db, no fx execution. JVM-runnable.
;;
;; 2. Drain-level: spin up a frame with a `:fx-overrides` map that
;;    redirects `:rf.http/managed` to a per-test stub, dispatch into
;;    the machine id, read the resulting app-db slice. Exercises the
;;    full registration → drain → snapshot-write path including the
;;    :on-success / :on-failure callback fold.

(defn pure-happy-path-test
  "Drives the transition table directly via machine-transition. No
  frame, no app-db. The chapter's first test."
  []
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        {s1 ::result/snap fx1 ::result/fx}
        (rf/machine-transition login-flow s0
                               [:auth.login/submit
                                {:email "a@b.com" :password "secret"}])]
    (assert (= :submitting (:state s1))
            (str "expected :submitting, got " (:state s1)))
    ;; Entering :submitting fires the :issue-request action's :fx.
    (assert (= 1 (count fx1)) (str "expected one :rf.http/managed fx, got " fx1))
    (assert (= :rf.http/managed (ffirst fx1))
            (str "expected :rf.http/managed fx-id, got " (ffirst fx1)))

    (let [{s2 ::result/snap} (rf/machine-transition login-flow s1
                                                    [:auth.login/success {:value {:token "t"}}])]
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
        {s ::result/snap}
        (rf/machine-transition login-flow snapshot
                               [:auth.login/failure
                                {:failure {:kind :rf.http/http-4xx
                                           :message "bad creds"}}])]
    (assert (= :locked-out (:state s))
            (str "expected :locked-out at attempts=3, got " (:state s)))
    :ok))

(defn drain-happy-path-test
  "Full drain: registers the machine, dispatches into it, asserts the
  app-db landed at :authed. Uses the `:fx-overrides` seam to swap
  `:rf.http/managed` for the per-test canned-success stub."
  []
  (let [f (rf/make-frame {:fx-overrides {:rf.http/managed :auth.login/canned-success}})]
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
  fourth :submit fails the guard and lands at :locked-out.

  Uses `rf/with-fx-overrides` — the lexical-scope counterpart to
  the per-frame `:fx-overrides` opt on `make-frame`: every dispatch
  inside the macro body inherits the override map, so the seven
  identical `:rf.http/managed` swaps don't need to thread the override
  through each call. Composes with `with-frame`."
  []
  (let [f (rf/make-frame {})]
    (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
      (dotimes [_ 3]
        (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                              {:email "x@y.z" :password "wrong"}]]
                          {:frame f})
        (rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame f}))
      (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                            {:email "x@y.z" :password "wrong"}]]
                        {:frame f}))
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
