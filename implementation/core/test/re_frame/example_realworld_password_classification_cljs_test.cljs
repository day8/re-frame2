(ns re-frame.example-realworld-password-classification-cljs-test
  "Framework-tree security regression for the RealWorld reference apps' PASSWORD
   classification (rf2-agb5jk) — the classification declared by
   examples/real-apps/realworld_http/* and examples/real-apps/realworld_resources/*.

   These belong in the framework test tree, NOT under examples/ (examples stay
   test-free per rf2-8cevm). The ns requires the managed-HTTP app's FEATURE nses
   (never `core` — that would pull `routing.cljs` and register routes into the
   shared node-test registrar) PLUS the resources app's HTTP ns (for its demo
   stub only). It does NOT co-load both apps' `settings` / `auth` nses: the two
   apps deliberately define the SAME ids (`:settings/load`, `:auth/flow`) with
   DIFFERENT implementations, and re-frame2's image-assembly duplicate-id guard
   (`:rf.error/image-duplicate-id`) rejects that for every frame in the process
   — the apps are built and run as separate bundles, never co-loaded. So this ns
   loads the managed-HTTP app fully (for the distinct machine-`:data` mechanism
   the resources app doesn't have) and reaches the resources app ONLY through its
   stub's registration (a unique fx-id). The resources app's login / register /
   settings app-db draft classifications use the IDENTICAL slice-init `:sensitive`
   pattern proven here on the managed-HTTP app's drafts (and were runtime-verified
   against the resources app directly during development).

   THE DEFECT (rf2-agb5jk): both apps meticulously classified the durable JWT but
   NEVER the user PASSWORD — a credential that egresses raw through the
   dispatched-event trace, the managed-HTTP request record, the settings machine
   snapshot / app-db drafts, and any off-box shipper. This ns pins the THREE
   surfaces the fix classifies:

     1. THE MANAGED-HTTP REQUEST BODY. Both apps run against a demo-stub
        `:fx-overrides` remap of `:rf.http/managed`, which BYPASSES the real
        handler's `:sensitive?` body scrub. When the override fires,
        `handle-one-fx` stamps the always-emitted `:rf.fx/handled` trace with the
        RESOLVED stub id + RAW args, and the classification projector redacts
        `:rf.fx/args` off the RESOLVED fx's own `:sensitive` (post-rf2-6h3c02).
        So each stub declares `:sensitive [[:request :body :user :password]]` —
        the Conduit `{user {…}}` envelope path — and the request-body password
        reads `:rf/redacted` on the one wire every tool reads.

     2. THE APP-DB FORM DRAFTS. The login / register / (resources) settings
        password drafts live in app-db. Each is classified `:sensitive` in the
        first durable write that creates the slice (classify-before-write,
        mirroring `:auth/classify-token` for the JWT), so the draft reads
        `:rf/redacted` at every app-db egress while handlers read the live value.

     3. THE SETTINGS MACHINE :data (managed-HTTP app). Its settings form is a
        machine whose `:data` holds the draft / submitted password; the machine
        declares projection-relative `:sensitive [[:data :draft :password]
        [:data :submitted :password]]`, lowered per actor at spawn.

   RESIDUAL CLOSED (rf2-agb5jk RULED 2026-07-11, item 1 — app redesign, the
   examples/core/login split). The prior residual — login/register riding
   `[:auth/flow [:auth/login {… :password …}]]` as a POSITIONAL machine
   sub-event that no `:sensitive` mark could reach — is closed by keeping the
   `:auth/flow` machine CREDENTIAL-FREE, not by classifying the position:

     4. PER-KEYSTROKE PASSWORD EDITS are their OWN map-payload events
        (`:auth.login-form/edit-password`, `:auth.register-form/edit-password`,
        `:settings/edit-password`), each `:sensitive [[:value]]` — the
        generic positional `:*-form/edit-field` event non-secret fields keep
        using is never routed a secret.

     5. SUBMIT is the credential-owning handoff: `:auth.login-form/submit` /
        `:auth.register-form/submit` read the draft, fire the `:sensitive?
        true` managed-HTTP request THEMSELVES, blank the draft password
        afterwards, and nudge `:auth/flow` with a BARE, credential-free
        signal (`[:auth/login]` / `[:auth/register]`, no args). The machine
        never sees the password at all.

     6. THE SETTINGS MACHINE (managed-HTTP app) is the one place a password
        legitimately still rides a routed sub-event (`:edit-password`,
        `:submit-valid` — the form-as-a-machine architecture makes the
        machine the password's :data owner by design). Its `reg-machine`
        OPTS carries an EVENT-rooted `:sensitive [[1 :password] [1 :submitted
        :password]]` (rf2-ghgbqi, agb5jk item 2 — the machine trace
        projector's completion) redacting the routed sub-event echoed into
        the `:event` / `[:input :event]` machine trace slots, alongside (not
        instead of) the `:data`-rooted classification in point 3.

     7. THE SESSION TOKEN's return trip gets the SAME treatment in reverse:
        the login/register/restore success reply is routed through a
        classified ordinary event (`:auth/session-established` /
        `:auth/session-restored`, `:sensitive [[:value :user :token]]`) —
        never straight to the machine — and the persistence fx
        (`:auth.session/persist`) now declares `:sensitive [[:token]]` too
        (it lacked one before; the JWT rode along for the same redesign).

   The tests below drive the FULL edit→submit(→success) cascade through the
   real public events (never poking `:auth/flow` / the settings machine
   directly with a credential) and scan EVERY emitted trace event for both a
   password and a JWT sentinel — the \"existing password test explicitly
   skips resources auth/settings, cannot serve as closure proof\" gap the
   ruling's ACCEPTANCE section calls out. The resources app's mirror-image
   closure lives in its own test file (this ns cannot co-load
   `realworld-resources.auth` — see the ns docstring above).

   THREE FRAMEWORK-OWNED GAPS SURFACED BY THIS SWEEP — genuinely NOT fixable
   from examples/ (they live in implementation/core's classification
   projector / implementation/machines' transition trace, outside this
   bead's declared surface) and confirmed to PRE-DATE this redesign — even
   examples/core/login, the reference model, has never been swept this
   thoroughly and shares gap (a)/(b). Filed as a framework follow-up rather
   than papered over here:

     (a) A `[:dispatch [target-event ...]]` fx NESTED inside another
         handler's `:fx` vector does not inherit the TARGET event's own
         `:sensitive` at the DISPATCHING handler's own `:rf.fx/handled` /
         `:rf.event/fx` trace — only a fx's OWN static registration redacts
         there (`:dispatch` itself carries none). This app's redesign works
         AROUND it everywhere avoidable — see `store-session-db` in
         auth.cljs, called inline rather than via a nested
         `[:dispatch [:auth/store-session user]]` — but a machine-routed
         sub-event (settings' `:edit-password` / `:submit-valid`, test 8
         below) has no such workaround: dispatching INTO a machine has no
         non-`:dispatch` form.
     (b) The SAME `:rf.event/fx` aggregate does not understand
         `:rf.http/managed`'s DYNAMIC `:sensitive? true` flag (only static
         per-fx `:sensitive [[paths]]` registrations) — any fx list combining
         a `:sensitive? true` managed-HTTP call with a sibling `:dispatch`
         (login/register's OWN `:submit` handlers, identical in shape to
         examples/core/login's `submit-form`) leaks the request body at this
         ONE trace slot. The dedicated `:rf.fx/handled` / `:rf.fx/args` slots
         for the RESOLVED HTTP fx redact correctly (test 1 above); only the
         PARENT event's aggregate does not.
     (c) `:rf.machine/action-ran`'s `:outcome` tag (the action's raw return
         value) carries no classification pass at all in
         `project-machine-tags` — any action returning updated `:data`
         containing a classified path leaks there, pre-dating this redesign
         identically for the settings machine's pre-existing `:edit` action.

   Tests 5/6 below therefore scope their sweep to what THIS bead's redesign
   provably controls, excluding tag (b)'s `:rf.event/fx` by name (documented,
   narrow — a real NEW leak anywhere else still fails the sweep); test 8
   (settings) documents (a)+(c) as an accepted residual rather than asserting
   a false clean bill."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.privacy :as rf.privacy]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.resources]
            [re-frame.http.managed]
            ;; The stub-registration gate for `rf/with-managed-request-stubs`
            ;; (below) — a route-map-consulting :rf.http/managed override that
            ;; drives the full login/register success cascade synchronously.
            [re-frame.http.test-support]
            ;; managed-HTTP app FEATURE nses (no core → no routes)
            [realworld-http.http :as http-req]
            [realworld-http.schema]
            [realworld-http.auth]
            [realworld-http.settings]
            ;; resources app — its HTTP ns ONLY, for the demo-stub registration.
            ;; Loading its settings/auth would collide with the managed-HTTP app's
            ;; `:settings/load` / `:auth/flow` under the image-assembly guard.
            [realworld-resources.http])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; A UNIQUE sentinel password (>= 8 chars) that appears nowhere else, so a scan
;; for it can only be hitting THIS drive's credential.
(def sentinel "PW-REALWORLD-SENTINEL-7d2c4a")

;; A UNIQUE sentinel JWT — for the item 4/6/7 closure tests below, which chase
;; the session TOKEN through the redesigned success-reply path.
(def token-sentinel "JWT-REALWORLD-SENTINEL-9f1e6b")

;; A whole-value scan: does `needle` appear ANYWHERE in `x`'s printed form?
;; Mirrors the sibling rf2-ghgbqi regression's `leaks?` — the bluntest, most
;; trustworthy way to assert "this trace event carries no trace of the raw
;; secret," regardless of which slot it might have hidden in.
(defn- leaks? [needle x] (str/includes? (pr-str x) needle))

;; The scoped variant tests 5/6 use: EVERYTHING `leaks?` checks, EXCEPT the
;; `:rf.event/fx` tag — the ONE trace slot carrying framework gap (b) from
;; the ns docstring above (the aggregate walker doesn't understand
;; `:rf.http/managed`'s dynamic `:sensitive? true` flag). Scrubbing just that
;; ONE key, by name, rather than skipping the whole event, means a real leak
;; anywhere else on the SAME trace event still fails the sweep.
(defn- leaks-outside-fx-aggregate-gap?
  [needle ev]
  (leaks? needle (update ev :tags dissoc :rf.event/fx)))

;; A local stub that mirrors the app stubs' :sensitive — proves the projector
;; redacts the Conduit body shape at the resolved-fx :rf.fx/handled slot. The
;; app stubs' OWN declarations are pinned by the registration tests below; using
;; a local mirror keeps the drive deterministic (the real stubs route through
;; the demo backend corpus).
(rf/reg-fx :test.realworld/body-stub
  {:sensitive [[:request :body :user :password]]}
  (fn [_ _] nil))

(rf/reg-event :test.realworld/emit-managed
  (fn [_ [_ args]] {:fx [[:rf.http/managed args]]}))

;; A SECOND local stub, this one REPLYING — for the item 4/6/7 cascade tests
;; below, which need to observe the redesigned success path
;; (:auth/session-established) end to end. Note what this is NOT:
;; `re-frame.http.test-support`'s generic `with-managed-request-stubs` (a
;; framework test helper with no idea what shape any one app's request body
;; takes, so it declares no `:sensitive` of its own) would be the wrong tool
;; here — routing through it would make the TEST HARNESS itself the leak,
;; not the app. This stub mirrors the real app's OWN demo-stub discipline
;; (http.cljs): it declares the SAME `:sensitive [[:request :body :user
;; :password]]` the app stub declares, then delegates to the framework's
;; canned-success fx (`:rf.http/managed-canned-success`) for reply fidelity —
;; same shape a real wire reply would have.
(rf/reg-fx :test.realworld/login-succeeds
  {:sensitive [[:request :body :user :password]]}
  (fn fx-test-login-succeeds [frame-ctx args-map]
    (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map
                             :value {:user {:username "alice"
                                            :email    "alice@example.com"
                                            :token    token-sentinel}})))))

(defn- record-traces! [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    a))

(defn- login-args []
  {:request    {:method :post
                :url    "https://api.realworld.show/api/users/login"
                :headers {"Accept" "application/json"}
                :body   {:user {:email "alice@example.com" :password sentinel}}
                :request-content-type :json
                :sensitive? true}
   :decode     :json
   :on-success [:test.realworld/noop]
   :on-failure [:test.realworld/noop]})

;; ---------------------------------------------------------------------------
;; 1. THE REQUEST BODY — each app's demo stub owns the redaction
;; ---------------------------------------------------------------------------

(deftest http-stub-declares-request-body-sensitive
  (testing "the managed-HTTP app's demo stub classifies the Conduit request-body
            password path — the registration the projector reads at egress"
    (is (= {:sensitive [[:request :body :user :password]]}
           (rf.classification/registration-classification :fx :realworld.demo/http-stub))
        ":realworld.demo/http-stub owns [:request :body :user :password]")))

(deftest resources-stub-declares-request-body-sensitive
  (testing "the resources app's demo stub classifies the same Conduit
            request-body password path"
    (is (= {:sensitive [[:request :body :user :password]]}
           (rf.classification/registration-classification :fx :realworld-resources.demo/http-stub))
        ":realworld-resources.demo/http-stub owns [:request :body :user :password]")))

(deftest request-body-password-redacts-in-fx-handled-trace
  (testing "a :sensitive? true managed request routed through a stub redacts
            the WHOLE request body in the always-emitted :rf.fx/handled trace:
            post-rf2-2siusz the keyword redirect stamps :rf.fx/from
            :rf.http/managed and the projector composes the ORIGINAL id's
            dynamic classification (the same whole-body scrub the dedicated
            :rf.http/* composers run) over the stub's own static path"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/with-fx-overrides {:rf.http/managed :test.realworld/body-stub}
        (let [traces (record-traces! ::body)]
          (rf/dispatch-sync [:test.realworld/emit-managed (login-args)] {:frame f})
          (rf/unregister-listener! :trace ::body)
          (let [handled (->> @traces
                             (filter #(= :test.realworld/body-stub
                                         (get-in % [:tags :rf.fx/id]))))]
            (is (seq handled)
                "the stub emitted a :rf.fx/handled trace with its args")
            (doseq [ev handled]
              (is (= :rf.http/managed (get-in ev [:tags :rf.fx/from]))
                  "the redirect provenance rides the handled trace")
              (is (= rf.privacy/redacted-sentinel
                     (get-in ev [:tags :rf.fx/args :request :body]))
                  "the :sensitive? true request's WHOLE body reads :rf/redacted
                   — run-mode parity with the real managed handler's composers")
              (is (not (str/includes? (pr-str ev) sentinel))
                  "no raw password rides the handled trace"))))))))

(deftest request-body-password-redacts-selectively-when-unflagged
  (testing "an UNFLAGGED managed request routed through the stub still rides
            the stub's OWN selective static classification: the declared
            password path redacts while the non-secret email rides visible
            (no reflexive whole-body over-redaction without :sensitive?)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/with-fx-overrides {:rf.http/managed :test.realworld/body-stub}
        (let [traces   (record-traces! ::body-unflagged)
              unflagged (update (login-args) :request dissoc :sensitive?)]
          (rf/dispatch-sync [:test.realworld/emit-managed unflagged] {:frame f})
          (rf/unregister-listener! :trace ::body-unflagged)
          (let [handled (->> @traces
                             (filter #(= :test.realworld/body-stub
                                         (get-in % [:tags :rf.fx/id]))))]
            (is (seq handled)
                "the stub emitted a :rf.fx/handled trace with its args")
            (doseq [ev handled]
              (is (= rf.privacy/redacted-sentinel
                     (get-in ev [:tags :rf.fx/args :request :body :user :password]))
                  "the request-body password reads :rf/redacted in :rf.fx/args")
              (is (= "alice@example.com"
                     (get-in ev [:tags :rf.fx/args :request :body :user :email]))
                  "the non-secret email rides visible — selective classification"))))))))

(deftest http-request-builder-passes-sensitive-flag
  (testing "realworld-http.http/request threads :sensitive? into the request map
            (the real-backend run-mode scrub for the managed handler)"
    (let [req (:request (http-req/request {:method :post :path "/users/login"
                                           :body {:user {:password sentinel}}
                                           :sensitive? true}))]
      (is (true? (:sensitive? req))
          "the builder stamps :sensitive? true on the request")
      (is (= sentinel (get-in req [:body :user :password]))
          "the builder still carries the real password for the wire"))
    (let [req (:request (http-req/request {:method :get :path "/user"}))]
      (is (not (contains? req :sensitive?))
          "a non-credential request stays unmarked — no reflexive sprinkle"))))

;; ---------------------------------------------------------------------------
;; 2. THE APP-DB FORM DRAFTS — classified at slice-init, redacted at egress
;; ---------------------------------------------------------------------------

(deftest login-register-drafts-classified-and-redacted
  (testing "the login + register form drafts classify their password path
            :sensitive at slice-init, so it is redacted at every app-db egress
            while the live value stays readable"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
      (rf/dispatch-sync [:auth.register-form/initialise] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-field :password sentinel] {:frame f})
      (is (contains? (rf.elision/sensitive-declarations f)
                     [:auth :login-form :draft :password])
          "the login-form draft-password path is in the per-frame sensitive registry")
      (is (contains? (rf.elision/sensitive-declarations f)
                     [:auth :register-form :draft :password])
          "the register-form draft-password path is classified too")
      (is (= sentinel (get-in (rf/app-db-value f) [:auth :login-form :draft :password]))
          "app-db still holds the REAL password — classification redacts only at egress")
      (doseq [profile [:rf.egress/local-redacted :rf.egress/off-box-tool]]
        (let [wire (rf/project-egress (rf/app-db-value f)
                                      {:frame f :rf.egress/profile profile})]
          (is (= rf.privacy/redacted-sentinel
                 (get-in wire [:auth :login-form :draft :password]))
              (str "the login-form password reads :rf/redacted at egress under " profile))
          (is (= "alice@example.com"
                 (get-in wire [:auth :login-form :draft :email]))
              (str "the non-secret email rides through unredacted under " profile)))))))

;; ---------------------------------------------------------------------------
;; 3. THE SETTINGS MACHINE :data (managed-HTTP app; :settings/form is unique)
;; ---------------------------------------------------------------------------

(deftest http-settings-machine-data-classified
  (testing "the managed-HTTP app's :settings/form machine lowers its
            projection-relative :sensitive [:data :draft :password] to the
            absolute snapshot path at spawn, redacting the password in every
            machine-snapshot egress while the action bodies read the live value"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; :settings/form is http-unique — driving it directly is collision-proof.
      (rf/dispatch-sync [:settings/form [:reset]] {:frame f})
      (rf/dispatch-sync [:settings/form [:edit {:field :password :value sentinel}]] {:frame f})
      (is (contains? (rf.elision/sensitive-declarations f)
                     [:rf.runtime/machines :snapshots :settings/form :data :draft :password])
          "the machine :data draft-password path lowered to the snapshot path at spawn")
      (is (contains? (rf.elision/sensitive-declarations f)
                     [:rf.runtime/machines :snapshots :settings/form :data :submitted :password])
          "the machine :data submitted-password path lowered too")
      (is (= sentinel (get-in (rf/frame-state-value f)
                              [:rf.db/runtime :rf.runtime/machines :snapshots
                               :settings/form :data :draft :password]))
          "the live snapshot still holds the REAL password — classification is egress-only"))))

;; ---------------------------------------------------------------------------
;; 4. THE REDESIGNED APP (rf2-agb5jk RULING, item 1 closure) — drive the FULL
;;    public edit→submit(→success) cascade and scan EVERY emitted trace for
;;    both sentinels (scoped past the ONE pre-existing, documented framework
;;    gap — see the ns docstring's "THREE FRAMEWORK-OWNED GAPS" section).
;;    This is the residual-closure proof the ruling's ACCEPTANCE section
;;    asked for: never poke :auth/flow or the settings machine directly with
;;    a credential — always go through the same public events a real view
;;    dispatches.
;; ---------------------------------------------------------------------------

(deftest login-form-cascade-redacts-password-and-token-everywhere
  (testing "rf2-agb5jk item 1: the full login edit->submit->success cascade —
            map-payload :auth.login-form/edit-password, a bare credential-free
            :auth/login nudge, a :sensitive? true managed-HTTP request, and the
            classified :auth/session-established reply, whose store-session
            write is INLINE rather than a nested :dispatch (see
            store-session-db in auth.cljs) — leaves NO emitted trace event
            carrying the raw password or the raw JWT, past the ONE documented
            :rf.event/fx gap (b), while the handler-visible values (the
            durable token, the machine state) stay real: redaction is
            egress-only"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed      :test.realworld/login-succeeds
                                                    :auth.session/persist :rf/no-op}})]
      ;; :auth/classify-token mirrors what the real app's :initial-events
      ;; does at frame creation (core.cljs) — without it [:auth :token]
      ;; would be UNCLASSIFIED in this bare test frame, which would be a
      ;; test-harness gap, not the app's.
      (rf/dispatch-sync [:auth/classify-token] {:frame f})
      (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
      (let [traces (record-traces! ::login-cascade)]
        (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
        (rf/dispatch-sync [:auth.login-form/edit-password {:value sentinel}] {:frame f})
        (rf/dispatch-sync [:auth.login-form/submit] {:frame f})
        (rf/unregister-listener! :trace ::login-cascade)
        (let [pw-leaking (filter #(leaks-outside-fx-aggregate-gap? sentinel %) @traces)
              jwt-leaking (filter #(leaks-outside-fx-aggregate-gap? token-sentinel %) @traces)]
          (is (empty? pw-leaking)
              (str "PW LEAK ops: " (pr-str (mapv :operation pw-leaking))))
          (is (empty? jwt-leaking)
              (str "JWT LEAK ops: " (pr-str (mapv :operation jwt-leaking))))))
      ;; Functional correctness + "redaction is egress-only": the flow
      ;; actually completed and the durable write holds the REAL values.
      (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f)))
          "the credential-free machine still reaches :authed")
      (is (= "alice" (:username (rf/compute-sub [:auth/user] (rf/frame-state-value f)))))
      (is (= token-sentinel (get-in (rf/app-db-value f) [:auth :token]))
          "the real token reached the durable, classified [:auth :token] path")
      (is (= "" (get-in (rf/app-db-value f) [:auth :login-form :draft :password]))
          "the draft password is blanked after hand-off (secret-field hygiene)"))))

(deftest register-form-cascade-redacts-password-everywhere
  (testing "rf2-agb5jk item 1: the register form's edit->submit cascade is the
            same credential-owning handoff as login — map-payload
            :auth.register-form/edit-password, a bare :auth/register nudge, a
            :sensitive? true request — so no emitted trace leaks the raw
            password either (scoped past the same documented :rf.event/fx gap)"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed      :test.realworld/login-succeeds
                                                    :auth.session/persist :rf/no-op}})]
      (rf/dispatch-sync [:auth/classify-token] {:frame f})
      (rf/dispatch-sync [:auth.register-form/initialise] {:frame f})
      (let [traces (record-traces! ::register-cascade)]
        (rf/dispatch-sync [:auth.register-form/edit-field :username "alice"] {:frame f})
        (rf/dispatch-sync [:auth.register-form/edit-field :email "alice@example.com"] {:frame f})
        (rf/dispatch-sync [:auth.register-form/edit-password {:value sentinel}] {:frame f})
        (rf/dispatch-sync [:auth.register-form/submit] {:frame f})
        (rf/unregister-listener! :trace ::register-cascade)
        (let [pw-leaking (filter #(leaks-outside-fx-aggregate-gap? sentinel %) @traces)]
          (is (empty? pw-leaking)
              (str "PW LEAK ops: " (pr-str (mapv :operation pw-leaking))))))
      (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f)))
          "register shares :auth/session-established with login — same credential-free machine nudge"))))

(deftest auth-session-persist-fx-classifies-token
  (testing "rf2-agb5jk item 1 (JWT rides along): the session-persistence fx
            declares :sensitive [[:token]] on its OWN registration — it lacked
            one before the redesign"
    (is (= {:sensitive [[:token]]}
           (rf.classification/registration-classification :fx :auth.session/persist))
        ":auth.session/persist owns [:token]")))

(deftest settings-machine-routed-password-subevents-echo-slots-redact
  (testing "rf2-agb5jk item 1 + item 2 framework completion (rf2-ghgbqi): the
            settings machine's OWN reg-machine OPTS :sensitive
            ([[1 :password] [1 :submitted :password]]) redacts the routed
            :edit-password sub-event echoed into the SPECIALIZED machine
            trace's :event (:rf.machine/transition, :event-received) and
            [:input :event] (:guard-evaluated, :action-ran) slots — the exact
            surface rf2-ghgbqi's own regression proves the mechanism reaches,
            and the surface the machine SPEC's :data-rooted :sensitive (test 3
            above) does NOT reach. Scoped, per-op assertions (mirroring
            rf2-ghgbqi's own methodology) rather than a whole-stream sweep —
            see the ns docstring's gap (a)/(c) for why a blanket sweep here
            would be a false claim: the PARENT :settings/edit-password
            handler's own `[:dispatch [:settings/form ...]]` nesting (gap a)
            and :rf.machine/action-ran's :outcome tag (gap c) are NOT reached
            by any app-level classification — accepted, documented residual,
            not silently dropped."
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/dispatch-sync [:settings/form [:reset]] {:frame f})
      (let [traces (record-traces! ::settings-edit)]
        (rf/dispatch-sync [:settings/edit-password {:value sentinel}] {:frame f})
        (rf/unregister-listener! :trace ::settings-edit)
        (doseq [op [:rf.machine/transition :rf.machine/event-received]]
          (doseq [ev (filter #(= op (:operation %)) @traces)]
            (is (not (leaks? sentinel (get-in ev [:tags :event])))
                (str op "'s top-level :event echo slot redacts the routed password"))))
        (doseq [op [:rf.machine/guard-evaluated :rf.machine/action-ran]]
          (doseq [ev (filter #(= op (:operation %)) @traces)]
            (is (not (leaks? sentinel (get-in ev [:tags :input :event])))
                (str op "'s [:input :event] echo slot redacts the routed password"))))
        (is (pos? (count (filter #(#{:rf.machine/transition :rf.machine/event-received
                                     :rf.machine/guard-evaluated :rf.machine/action-ran}
                                    (:operation %))
                                  @traces)))
            "teeth — the drive actually emitted the machine trace ops under test")
        ;; --- accepted residual (gaps a + c), NOT asserted clean: the SAME
        ;;     drive's :dispatch fx-handled / :rf.event/fx aggregate on the
        ;;     PARENT :settings/edit-password event, and
        ;;     :rf.machine/action-ran's :outcome tag, still carry the raw
        ;;     password. Left unasserted (not asserted-to-leak either) —
        ;;     see the ns docstring; a framework fix that closes them should
        ;;     not have to touch this test to stay green.
        )
      (is (= sentinel (get-in (rf/frame-state-value f)
                              [:rf.db/runtime :rf.runtime/machines :snapshots
                               :settings/form :data :draft :password]))
          "the live snapshot still holds the REAL password"))))
