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

   KNOWN RESIDUAL — NOT closed here (rf2-agb5jk, framework-pattern decision).
   The credential ALSO travels through a framework-DISPATCHED event whose payload
   the app cannot classify: login/register ride `[:auth/flow [:auth/login {…
   :password …}]]` — a POSITIONAL machine sub-event (the documented
   'positional secret ships raw' structural limitation) — and resources settings
   rides `[:rf.mutation/execute {:params {… :password …}}]`. Those still ship raw
   in the dispatched-event + `:rf.machine/*` trace slots. Closing them needs a
   framework change (classify positional / framework-event payloads) or an
   auth-flow redesign that keeps the machine credential-free (as
   examples/core/login does) — reserved for a ruling, so NOT asserted here."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.classification :as classification]
            [re-frame.elision :as elision]
            [re-frame.privacy :as privacy]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.resources]
            [re-frame.http.managed]
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
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; A UNIQUE sentinel password (>= 8 chars) that appears nowhere else, so a scan
;; for it can only be hitting THIS drive's credential.
(def sentinel "PW-REALWORLD-SENTINEL-7d2c4a")

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
           (classification/registration-classification :fx :realworld.demo/http-stub))
        ":realworld.demo/http-stub owns [:request :body :user :password]")))

(deftest resources-stub-declares-request-body-sensitive
  (testing "the resources app's demo stub classifies the same Conduit
            request-body password path"
    (is (= {:sensitive [[:request :body :user :password]]}
           (classification/registration-classification :fx :realworld-resources.demo/http-stub))
        ":realworld-resources.demo/http-stub owns [:request :body :user :password]")))

(deftest request-body-password-redacts-in-fx-handled-trace
  (testing "a managed request routed through a stub carrying the app's
            :sensitive redacts the request-body password in the always-emitted
            :rf.fx/handled trace, while the non-secret email rides visible"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
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
              (is (= privacy/redacted-sentinel
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
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
      (rf/dispatch-sync [:auth.register-form/initialise] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
      (rf/dispatch-sync [:auth.login-form/edit-field :password sentinel] {:frame f})
      (is (contains? (elision/sensitive-declarations f)
                     [:auth :login-form :draft :password])
          "the login-form draft-password path is in the per-frame sensitive registry")
      (is (contains? (elision/sensitive-declarations f)
                     [:auth :register-form :draft :password])
          "the register-form draft-password path is classified too")
      (is (= sentinel (get-in (rf/app-db-value f) [:auth :login-form :draft :password]))
          "app-db still holds the REAL password — classification redacts only at egress")
      (doseq [profile [:rf.egress/local-redacted :rf.egress/off-box-tool]]
        (let [wire (rf/project-egress (rf/app-db-value f)
                                      {:frame f :rf.egress/profile profile})]
          (is (= privacy/redacted-sentinel
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
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      ;; :settings/form is http-unique — driving it directly is collision-proof.
      (rf/dispatch-sync [:settings/form [:reset]] {:frame f})
      (rf/dispatch-sync [:settings/form [:edit {:field :password :value sentinel}]] {:frame f})
      (is (contains? (elision/sensitive-declarations f)
                     [:rf.runtime/machines :snapshots :settings/form :data :draft :password])
          "the machine :data draft-password path lowered to the snapshot path at spawn")
      (is (contains? (elision/sensitive-declarations f)
                     [:rf.runtime/machines :snapshots :settings/form :data :submitted :password])
          "the machine :data submitted-password path lowered too")
      (is (= sentinel (get-in (rf/frame-state-value f)
                              [:rf.db/runtime :rf.runtime/machines :snapshots
                               :settings/form :data :draft :password]))
          "the live snapshot still holds the REAL password — classification is egress-only"))))
