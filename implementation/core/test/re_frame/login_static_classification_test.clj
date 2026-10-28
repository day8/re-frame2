(ns re-frame.login-static-classification-test
  "The login draft password is classified by one static declaration,
  `(rf/reg-classification :auth.login …)` in `login.model`, not by an effect
  its init event returns (Spec 015 §Static app-db classification). Pinned end
  to end on that one path: its first egress, the SSR payload and the hydrated
  client, a retained epoch after the declaration is replaced, and resolution
  through the target frame's own view.

  The trace and epoch deftests are `^:requires-debug`: under
  `-Dre-frame.debug=false` neither is recorded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [login.model]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch :as rf.epoch]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.ssr :as rf.ssr]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private this-ns "re-frame.login-static-classification-test")
(def ^:private password-path [:auth :login-form :draft :password])
(def ^:private secret "hunter2-static-slice")

(defn- leaks? [x] (.contains (pr-str x) ^String secret))

(defn- type-password!
  "Boot the login slice in `frame` and type the secret through the real
  password edit event."
  [frame]
  (rf/dispatch-sync [:auth.login/initialise-form] {:frame frame})
  (rf/dispatch-sync [:auth.login/edit-password {:value secret}] {:frame frame}))

(defn- record-for
  [frame event-id]
  (last (filter #(= event-id (:event-id %)) (rf.epoch/epoch-history frame))))

;; ---- first egress -----------------------------------------------------------

(deftest ^:requires-debug the-draft-password-redacts-from-its-first-egress
  (let [traces (atom [])]
    (rf/register-listener! :trace ::probe #(swap! traces conj %))
    (type-password! :rf/default)
    (rf/unregister-listener! :trace ::probe)
    (let [init (record-for :rf/default :auth.login/initialise-form)
          edit (record-for :rf/default :auth.login/edit-password)]
      (testing "no classification event or effect: the init event returns :db alone"
        (is (empty? (:effects init))))
      (testing "the pending-db trace of the very event that writes the secret
                carries it redacted"
        (let [pending (keep #(when (= :rf.event/db-pending (:operation %))
                               (get-in % [:tags :rf.event/db]))
                            @traces)]
          (is (seq pending))
          (is (every? #(= :rf/redacted (get-in % password-path))
                      (filter #(some? (get-in % password-path)) pending)))))
      (is (not (leaks? @traces)) "no trace event carries the secret")
      (is (= :rf/redacted (get-in (rf/project-egress edit) (into [:db-after] password-path))))
      (is (not (leaks? (rf/project-egress edit))) "nor does the projected epoch"))))

;; ---- SSR: the payload permit and the hydrated client agree ----------------

(deftest the-ssr-payload-and-the-hydrated-client-classify-the-path-alike
  (rf/make-frame {:id ::server :platform :server})
  (type-password! ::server)
  (let [payload   (rf.ssr/hydration-payload ::server {:payload [:auth]})
        permitted (rf.ssr/hydration-payload ::server {:payload                   [:auth]
                                                      :payload-include-sensitive [password-path]})]
    (is (= :rf/redacted (get-in payload (into [:rf/app-db] password-path)))
        "the server resolves the declaration and redacts the path in the payload")
    (is (= secret (get-in permitted (into [:rf/app-db] password-path)))
        "the host's permit releases exactly that path")
    (doseq [[label p raw] [["unpermitted" payload :rf/redacted] ["permitted" permitted secret]]]
      (testing label
        (let [client (keyword (name ::client) label)]
          (rf/make-frame {:id client :platform :client})
          (rf/dispatch-sync [:rf/hydrate p] {:frame client})
          (is (= raw (get-in (rf/app-db-value client) password-path))
              "the client holds what the payload carried")
          (is (= :rf/redacted (get-in (rf/project-egress (rf/app-db-value client) {:frame client})
                                      password-path))
              "and classifies the path exactly as the server did"))))))

;; ---- history: a retained epoch after the declaration is replaced ----------

(defn- replace-login-classification!
  "Replace `login.model`'s `:auth.login` declaration as re-evaluating that
  namespace would: the same id, from the same provenance namespace."
  [decl]
  (binding [rf.source-coords/*pending-coords* {:ns 'login.model}]
    (rf.classification/reg-classification* :auth.login decl)))

(deftest ^:requires-debug a-retained-epoch-keeps-the-classification-it-was-recorded-under
  (type-password! :rf/default)
  (let [recorded (record-for :rf/default :auth.login/edit-password)]
    (replace-login-classification! {:doc "Declares nothing." :sensitive []})
    (rf/dispatch-sync [:auth.login/edit-password {:value secret}])
    (is (not (contains? (rf.elision/sensitive-declarations :rf/default) password-path))
        "control: the live classification no longer names the path")
    (let [projected (rf/project-egress recorded)]
      (is (= [:rf/redacted :rf/redacted]
             [(get-in projected (into [:db-after] password-path))
              (get-in projected (into [:frame-state-after :rf.db/app] password-path))])
          "the record made under the declaration keeps its redaction")
      (is (not (leaks? projected))))
    (is (= secret (get-in (rf/project-egress (record-for :rf/default :auth.login/edit-password))
                          (into [:db-after] password-path)))
        "a value recorded after the replacement egresses under the new declaration")))

;; ---- resolution through the target frame's own view ------------------------

(deftest declarations-resolve-through-the-target-frames-image
  (rf/reg-event ::seed-draft
    (fn [{:keys [db]} _] {:db (assoc-in db password-path secret)}))
  (rf/make-frame {:id     ::with-login
                  :images [(rf/image {:id ::with-login :select-ns {:include ["login.model" this-ns]}})]})
  (rf/make-frame {:id     ::without-login
                  :images [(rf/image {:id ::without-login :select-ns {:include [this-ns]}})]})
  (doseq [f [::with-login ::without-login]]
    (rf/dispatch-sync [::seed-draft] {:frame f}))
  (let [egress (fn [f] (get-in (rf.elision/elide-wire-value (rf/app-db-value f) {:frame f})
                               password-path))]
    (is (= :rf/redacted (egress ::with-login)) "the frame whose image carries the declaration")
    (is (= secret (egress ::without-login)) "the frame whose image does not")
    (testing "the target frame governs, whatever resolution the caller stands in"
      (is (= secret (rf.live-frame/call-with-frame-resolution ::with-login #(egress ::without-login))))
      (is (= :rf/redacted (rf.live-frame/call-with-frame-resolution ::without-login #(egress ::with-login)))))))

;; ---- the registration ------------------------------------------------------

(defn- thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-malformed-declaration-fails-at-registration
  (doseq [decl [{:sensitive [:auth :token]}
                {:sensitive [[:auth {:not :a-segment}]]}
                {:large "blob"}
                {:sensitive [[:auth :token]] :frame :app}
                [[:auth :token]]]]
    (is (= :rf.error/bad-classification
           (:rf.error/id (thrown-data #(rf/reg-classification ::bad decl))))
        (pr-str decl)))
  (is (= ::good (rf/reg-classification ::good {:large [[:docs :body]]})))
  (is (= [[:docs :body]] (:large (rf/handler-meta {:kind :classification :id ::good}))))
  (is (= ::good (rf/clear :classification ::good)))
  (is (nil? (rf/handler-meta {:kind :classification :id ::good}))))
