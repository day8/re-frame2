(ns login-form.events
  "Login-form testbed events (rf2-0sg12) — five-state login flow.

  The Story tutorial's index page opens with a five-state login-form
  scenario (`docs/story/index.md:13-26`) and never delivers code for
  it. This testbed promotes the scenario to a runnable variant set.

  The five states from the tutorial's index page:

    :idle              — empty form, ready for input.
    :submitting        — first submit; HTTP request in flight.
    :error             — server rejected creds; user sees a message.
    :submitting-retry  — user fixed the typo and re-submitted.
    :authenticated     — server accepted creds; welcome banner.

  The events module is *plain re-frame2* — no Story-specific code
  lives here. The same handlers run when the live app boots and when
  any Story variant frame allocates. Per IMPL-SPEC §1.1, variant
  bodies are data; they reference these event-ids in their `:events`
  slot. Story's per-variant frame isolation (Spec 002) means each
  variant gets its own fresh `:rf/machines :login/flow` slot.

  Per Spec 014 §Testing the stub layer: `:rf.http/managed` is the
  fx-id the machine emits; the Story variants override that id via
  the `force-fx-stub` decorator at the frame level, so the real
  network call never fires. The fx body below would run in the live
  app; in Story it's intercepted."
  (:require [re-frame.core :as rf]
            ;; Loads the machine substrate's late-bind hooks so
            ;; rf/make-machine-handler resolves below.
            [re-frame.machines]
            ;; Loads :rf.http/managed; without it, dispatching
            ;; the login flow's request fx would throw
            ;; :rf.error/no-such-fx.
            [re-frame.http-managed]
            ;; rf2-cdmle — this testbed resolves
            ;; :rf.http/managed-canned-success/failure via registrar lookup.
            ;; Per the gate change, the canned-stub fx ids register from
            ;; re-frame.http-test-support, NOT re-frame.http-managed.
            [re-frame.http-test-support]
            [re-frame.registrar :as registrar]))

;; ============================================================================
;; MACHINE — :login/flow
;; ============================================================================
;;
;; Five states; three transition events (:submit, :retry, :dismiss).
;; The :error state has TWO outbound events — :retry (the user fixed
;; the typo) and :dismiss (the user gave up). Both demonstrate the
;; tutorial's claim that "you swap five variants without a single
;; refresh."

(rf/reg-event-fx :login/flow
  {:doc "Login flow machine — five-state authentication FSM."}
  (rf/make-machine-handler
    {:initial :idle
     :data    {:email      ""
               :error      nil
               :attempts   0}

     :guards
     {:credentials-look-valid
      ;; Both fields non-blank and email looks email-ish. Keeps the
      ;; guard surface honest — production would Malli-validate.
      (fn [{[_ {:keys [email password]}] :event}]
        (and (some? email) (some? password)
             (re-find #".+@.+\..+" (str email))
             (>= (count (str password)) 1)))}

     :actions
     {:remember-credentials
      ;; Capture the email so the success banner can greet the user
      ;; by handle. Production might capture the full claims set.
      (fn [{data :data [_ {:keys [email]}] :event}]
        {:data (assoc data :email email :error nil)})

      :issue-request
      ;; Issue the login HTTP request. The fx-id `:rf.http/managed`
      ;; is the override seam: in Story variants `force-fx-stub`
      ;; intercepts this id at the frame level; in the live app the
      ;; production fx body fires the real network call.
      (fn [{[_ creds] :event}]
        {:fx [[:rf.http/managed
               {:request    {:method :post
                             :url    "/api/login"
                             :body   creds
                             :request-content-type :json}
                :decode     :json
                :on-success [:login/flow [:login/success]]
                :on-failure [:login/flow [:login/failure]]}]]})

      :record-error
      ;; Capture the server error and increment the attempt counter.
      ;; `:attempts` is what flips the :error label from "Invalid
      ;; credentials" to "Invalid credentials (attempt 2)" — the
      ;; visible difference between :error and :submitting-retry
      ;; once the retry lands.
      (fn [{data :data [_ {:keys [failure]}] :event}]
        {:data (-> data
                   (update :attempts (fnil inc 0))
                   (assoc :error (or (:message failure)
                                     "Invalid credentials.")))})

      :clear-error
      ;; Reset the error message at retry-time so the form doesn't
      ;; display stale text while the second request is in flight.
      (fn [{data :data}]
        {:data (assoc data :error nil)})}

     :states
     {;; :idle — empty form, ready for input. This is the entry
      ;; state and also the state the variant body's `:events` slot
      ;; lands in for the *first* of the five Story variants.
      :idle
      {:on {:login/submit [{:target :submitting
                            :guard  :credentials-look-valid
                            :action :remember-credentials}]}}

      ;; :submitting — first submit; HTTP request in flight. The
      ;; submit button is disabled (the view queries the `:auth/busy`
      ;; tag), the form is read-only. This is the second variant.
      :submitting
      {:tags  #{:auth/busy}
       :entry :issue-request
       :on    {:login/success {:target :authenticated}
               :login/failure {:target :error
                               :action :record-error}}}

      ;; :error — server rejected creds. The user sees the error
      ;; message and can either retry (fix the typo, click submit
      ;; again) or dismiss (back to :idle without a message).
      ;; This is the third variant — the canonical "error
      ;; state" Story exists to capture.
      :error
      {:on {:login/retry   {:target :submitting-retry
                            :guard  :credentials-look-valid
                            :action :clear-error}
            :login/dismiss {:target :idle
                            :action :clear-error}}}

      ;; :submitting-retry — distinct from :submitting because the
      ;; tutorial calls it out as the fourth state. Same busy tag,
      ;; same request fx, but the variant body wraps the form in a
      ;; small "Retrying" hint and the attempt counter is now > 1.
      :submitting-retry
      {:tags  #{:auth/busy :auth/retry}
       :entry :issue-request
       :on    {:login/success {:target :authenticated}
               :login/failure {:target :error
                               :action :record-error}}}

      ;; :authenticated — fifth variant. The welcome banner replaces
      ;; the form. Not strictly terminal — :sign-out routes back to
      ;; :idle so the live testbed page can demonstrate the full
      ;; round-trip; Story variants pin the state via their own
      ;; `:events` slot regardless.
      :authenticated
      {:tags #{:auth/authenticated}
       :on   {:login/sign-out {:target :idle
                               :action :clear-error}}}}}))

;; ============================================================================
;; DEMO FX — for the LIVE testbed page (not for Story variants)
;; ============================================================================
;;
;; The Story variants override `:rf.http/managed` per-frame via the
;; `force-fx-stub` decorator. The live testbed page at `#/` needs a
;; real fx body so a curious visitor can click *Sign in* and see
;; the flow drive through. Password `correct-horse` succeeds; anything
;; else fails after a 250ms artificial delay so :submitting is visible.

(rf/reg-fx :login/demo-http
  {:doc       "Demo override for :rf.http/managed — routes any login
               request to a canned success or failure based on the
               password value, with a 250ms artificial latency so
               the :submitting state is observable on the live page."
   :platforms #{:client :server}}
  (fn fx-login-demo-http [frame-ctx args-map]
    (let [{:keys [body]} (:request args-map)
          good?          (= "correct-horse" (:password body))
          ok-stub        (registrar/handler :fx :rf.http/managed-canned-success)
          fail-stub      (registrar/handler :fx :rf.http/managed-canned-failure)]
      (js/setTimeout
        (fn []
          (if good?
            (ok-stub frame-ctx
                     (assoc args-map :value {:user  {:email (:email body)}
                                             :token "demo-token"}))
            (fail-stub frame-ctx
                       (assoc args-map
                              :kind :rf.http/http-4xx
                              :tags {:status 401
                                     :message "Invalid credentials."}))))
        250))))
