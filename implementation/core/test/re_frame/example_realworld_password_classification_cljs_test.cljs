(ns re-frame.example-realworld-password-classification-cljs-test
  "Password and session-token classification in the RealWorld reference apps
   (docs/core/how-to/keep-secrets-out-of-traces.md). The drives go through the
   apps' public events and sweep EVERY emitted trace event for a password and a
   JWT sentinel, so a raw secret in any slot — the event vector, the pending
   `:rf.event/db`, the `:rf.event/fx` aggregate, `:rf.fx/handled`, a machine
   trace — is a leak.

   The managed-HTTP app is loaded through its feature nses, never `core`
   (which would register routes into the shared node-test registrar). The
   resources app is reached only through its HTTP ns, for its demo stub: the
   two apps register the same ids (`:settings/load`, `:auth/flow`) with
   different implementations, which the image-assembly duplicate-id guard
   rejects."
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
            [re-frame.http.test-support]
            [realworld-http.http]
            [realworld-http.schema]
            [realworld-http.auth]
            [realworld-http.settings]
            [realworld-resources.http])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; A password (>= 8 chars) and a JWT that appear nowhere else.
(def sentinel "PW-REALWORLD-SENTINEL-7d2c4a")
(def token-sentinel "JWT-REALWORLD-SENTINEL-9f1e6b")

;; Mirror of realworld-http.core's `:auth/classify-token`: this ns does not load
;; core, so it marks the durable JWT path on its bare test frame itself.
(rf/reg-event :test.realworld/classify-token
  {:doc "Mirror of realworld-http.core's :auth/classify-token — mark the durable
         JWT path [:auth :token] sensitive on this bare test frame."}
  (fn [{:keys [db]} _]
    {:db db :sensitive [[:auth :token]]}))

(defn- leaks? [needle x] (str/includes? (pr-str x) needle))

;; A replying stand-in for the app's demo stub: it declares the same
;; `:sensitive` path the app stub does and answers through the framework's
;; canned-success fx with a token-bearing user.
(rf/reg-fx :test.realworld/login-succeeds
  {:sensitive [[:request :body :user :password]]}
  (fn fx-test-login-succeeds [frame-ctx args-map]
    (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map
                             :value {:user {:username "alice"
                                            :email    "alice@example.com"
                                            :token    token-sentinel}})))))

(defn- with-local-storage
  "Run `f` with `globalThis.localStorage` defined by the JS property
   `descriptor`, then put back whatever was there before."
  [descriptor f]
  (let [g     js/globalThis
        prior (js/Object.getOwnPropertyDescriptor g "localStorage")]
    (js/Object.defineProperty g "localStorage" descriptor)
    (try
      (f)
      (finally
        (if prior
          (js/Object.defineProperty g "localStorage" prior)
          (js-delete g "localStorage"))))))

(defn- with-saved-jwt
  "Run `f` over a localStorage holding `token` under the RealWorld key
   `jwtToken`."
  [token f]
  (with-local-storage
    #js {:configurable true
         :value        #js {:getItem    (fn [k] (when (= k "jwtToken") token))
                            :setItem    (fn [_ _] nil)
                            :removeItem (fn [_] nil)}}
    f))

(defn- record-traces! [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    a))

;; Every password-bearing request in both apps is `:sensitive? true`; the
;; stub's own path is what redacts an UNFLAGGED one routed through it.
(deftest http-stub-declares-request-body-sensitive
  (doseq [stub [:realworld.demo/http-stub :realworld-resources.demo/http-stub]]
    (is (= {:sensitive [[:request :body :user :password]]}
           (rf.classification/registration-classification :fx stub))
        (str stub " owns [:request :body :user :password]"))))

(deftest login-form-cascade-redacts-password-and-token-everywhere
  (testing "the full login edit -> submit -> success cascade leaves no emitted
            trace carrying the raw password or JWT, while the handler-visible
            values stay real"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed      :test.realworld/login-succeeds
                                                    :auth.session/persist :rf/no-op}})]
      (rf/dispatch-sync [:test.realworld/classify-token] {:frame f})
      (rf/dispatch-sync [:auth.login-form/initialise] {:frame f})
      (let [traces (record-traces! ::login-cascade)]
        (rf/dispatch-sync [:auth.login-form/edit-field :email "alice@example.com"] {:frame f})
        (rf/dispatch-sync [:auth.login-form/edit-password {:value sentinel}] {:frame f})
        (rf/dispatch-sync [:auth.login-form/submit] {:frame f})
        (rf/unregister-listener! :trace ::login-cascade)
        (let [pw-leaking (filter #(leaks? sentinel %) @traces)
              jwt-leaking (filter #(leaks? token-sentinel %) @traces)]
          (is (empty? pw-leaking)
              (str "PW LEAK ops: " (pr-str (mapv :operation pw-leaking))))
          (is (empty? jwt-leaking)
              (str "JWT LEAK ops: " (pr-str (mapv :operation jwt-leaking))))))
      (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f)))
          "the credential-free machine still reaches :authed")
      (is (= token-sentinel (get-in (rf/app-db-value f) [:auth :token]))
          "the real token reached the durable, classified [:auth :token] path")
      (is (= "" (get-in (rf/app-db-value f) [:auth :login-form :draft :password]))
          "the draft password is blanked after hand-off"))))

(deftest register-form-cascade-redacts-password-everywhere
  (testing "the register form's edit -> submit cascade is the same
            credential-owning handoff as login, so no emitted trace leaks the
            raw password"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed      :test.realworld/login-succeeds
                                                    :auth.session/persist :rf/no-op}})]
      (rf/dispatch-sync [:test.realworld/classify-token] {:frame f})
      (rf/dispatch-sync [:auth.register-form/initialise] {:frame f})
      (let [traces (record-traces! ::register-cascade)]
        (rf/dispatch-sync [:auth.register-form/edit-field :username "alice"] {:frame f})
        (rf/dispatch-sync [:auth.register-form/edit-field :email "alice@example.com"] {:frame f})
        (rf/dispatch-sync [:auth.register-form/edit-password {:value sentinel}] {:frame f})
        (rf/dispatch-sync [:auth.register-form/submit] {:frame f})
        (rf/unregister-listener! :trace ::register-cascade)
        (let [pw-leaking (filter #(leaks? sentinel %) @traces)]
          (is (empty? pw-leaking)
              (str "PW LEAK ops: " (pr-str (mapv :operation pw-leaking))))))
      (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f)))
          "the cascade completed: register shares :auth/session-established with login"))))

(deftest boot-read-of-the-saved-jwt-redacts-everywhere
  (testing "the boot read of the saved JWT — `:auth/initialise`, the
            `:auth.session/load` effect, its classified `:auth/session-read`
            reply, the machine's restore and the classified
            `:auth/session-restored` — leaves NO emitted trace event carrying the
            raw JWT, while the durable token stays real"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed      :test.realworld/login-succeeds
                                                    :auth.session/persist :rf/no-op}})]
      (rf/dispatch-sync [:test.realworld/classify-token] {:frame f})
      (let [traces (record-traces! ::boot-read)]
        (with-saved-jwt token-sentinel #(rf/dispatch-sync [:auth/initialise] {:frame f}))
        (rf/unregister-listener! :trace ::boot-read)
        (let [jwt-leaking (filter #(leaks? token-sentinel %) @traces)]
          (is (seq @traces) "the boot read emitted traces to scan")
          (is (empty? jwt-leaking)
              (str "JWT LEAK ops: " (pr-str (mapv :operation jwt-leaking))))))
      (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f)))
          "the restore the read started completed")
      (is (= token-sentinel (get-in (rf/app-db-value f) [:auth :token]))
          "the real token reached the durable, classified [:auth :token] path"))))

(deftest store-session-event-token-redacts-at-its-arg-map-path
  (testing "`:auth/store-session` classifies its JWT at the arg-map-relative
            [:token]. An event's classification paths index into
            `(second event)`, so a vector-relative [1 :token] would be a silent
            no-op; the assertions at `:rf.event/v` are positive so that
            spelling cannot pass"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/dispatch-sync [:test.realworld/classify-token] {:frame f})
      (let [traces (record-traces! ::store-session)
            user   {:username "alice"
                    :email    "alice@example.com"
                    :bio      "bio"
                    :image    nil
                    :token    token-sentinel}]
        (rf/dispatch-sync [:auth/store-session user] {:frame f})
        (rf/unregister-listener! :trace ::store-session)
        (let [slots (->> @traces
                         (keep #(get-in % [:tags :rf.event/v]))
                         (filter #(= :auth/store-session (first %))))]
          (is (seq slots)
              "the drive emitted the dispatched-event slot under test")
          (doseq [v slots]
            (is (= rf.privacy/redacted-sentinel (get-in v [1 :token]))
                "the JWT reads :rf/redacted at the event's own arg-map :token")
            (is (= "alice" (get-in v [1 :username]))
                "the non-secret username rides visible: selective, not whole-arg")))
        (let [jwt-leaking (filter #(leaks? token-sentinel %) @traces)]
          (is (empty? jwt-leaking)
              (str "JWT LEAK ops: " (pr-str (mapv :operation jwt-leaking))))))
      (is (= token-sentinel (get-in (rf/app-db-value f) [:auth :token]))
          "the real token reached the durable classified path")
      (is (nil? (get-in (rf/app-db-value f) [:auth :user :token]))
          "and was never duplicated at the unclassified [:auth :user :token]"))))

(deftest settings-machine-routed-password-subevents-echo-slots-redact
  (testing "the managed-HTTP app's settings machine keeps the password out of
            every trace: its reg-machine OPTS `:sensitive` redacts the routed
            sub-event echoed into the machine traces' `:event` /
            `[:input :event]` slots, and its spec's `:data` `:sensitive` is
            lowered to the absolute snapshot paths at spawn"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf/dispatch-sync [:settings/form [:reset]] {:frame f})
      (let [traces (record-traces! ::settings-edit)]
        (rf/dispatch-sync [:settings/edit-password {:value sentinel}] {:frame f})
        (rf/unregister-listener! :trace ::settings-edit)
        (is (some #(#{:rf.machine/transition :rf.machine/event-received
                      :rf.machine/guard-evaluated :rf.machine/action-ran}
                    (:operation %))
                  @traces)
            "the drive emitted the machine trace ops that echo the routed event")
        (let [pw-leaking (filter #(leaks? sentinel %) @traces)]
          (is (empty? pw-leaking)
              (str "PW LEAK ops: " (pr-str (mapv :operation pw-leaking))))))
      (is (contains? (rf.elision/sensitive-declarations f)
                     [:rf.runtime/machines :snapshots :settings/form :data :draft :password])
          "the machine :data draft-password path lowered to the snapshot path at spawn")
      (is (contains? (rf.elision/sensitive-declarations f)
                     [:rf.runtime/machines :snapshots :settings/form :data :submitted :password])
          "the machine :data submitted-password path lowered too")
      (is (= sentinel (get-in (rf/frame-state-value f)
                              [:rf.db/runtime :rf.runtime/machines :snapshots
                               :settings/form :data :draft :password]))
          "the live snapshot still holds the REAL password"))))
