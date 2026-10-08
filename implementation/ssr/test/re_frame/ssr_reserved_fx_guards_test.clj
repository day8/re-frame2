(ns re-frame.ssr-reserved-fx-guards-test
  "The reserved `:rf.server/*` fx guard their own args in EVERY build, so a
  malformed framework effect never reaches the response accumulator that
  `ssr/get-response` publishes. The dev step-5 schema gate
  (`validate-fx!`) is compiled out under `-Dre-frame.debug=false`, so the
  always-on guards in `re-frame.ssr.response` are what hold the shape in a
  release build. Every test here is posture-independent and runs on the
  production gate too; `m/validate` is called directly so the schema half
  adjudicates there as well."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.response :as rf.ssr.response]
            [re-frame.ssr.server-fx-schemas :as rf.ssr.server-fx-schemas]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- server-frame []
  (rf.frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id   :rf.ssr/default-error-projector
                :dev-error-detail? false}}))

;; Queued after every fx under test: its presence proves a rejection skipped
;; only the bad fx and let the cascade continue.
(def ^:private sibling-fx
  [:rf.server/append-header {:name "X-Sibling" :value "ran"}])

(defn- sibling-ran? [raw]
  (boolean (some (fn [[k v]] (and (= "X-Sibling" k) (= "ran" v)))
                 (:headers raw))))

(defn- drive!
  "Dispatch `fx-vec` then `sibling-fx` on a fresh server frame. Returns the
  pure accumulator read (`:raw`), the same read with the `_status-writes`
  bookkeeping kept (`:bookkept`), the public draining read (`:response`,
  taken last because it drains) and every always-on error record seen."
  [fx-vec]
  (let [f    (server-frame)
        id   (keyword "rf.test" (str "cap-" (name (gensym "g"))))
        seen (atom [])]
    (rf.error-emit/register-error-listener! id (fn [r] (swap! seen conj r)))
    (rf/reg-event ::attempt (fn [_ [_ fx]] {:fx [fx sibling-fx]}))
    (try
      (rf/dispatch-sync [::attempt fx-vec] {:frame f})
      (let [raw      (rf.ssr/peek-response f)
            bookkept (rf.ssr.response/response-of f)]
        {:raw      raw
         :bookkept bookkept
         :response (rf.ssr/get-response f)
         :records  @seen})
      (finally
        (rf.error-emit/unregister-error-listener! id)))))

;; Each row: [label fx-id schema args accept?] — ONE literal driven through the
;; registered Malli schema and through the live fx, so a widening on one side
;; cannot land alone. An optional key present with nil is absent on both sides;
;; a required key with nil is the violation it looks like.
(def ^:private acceptance-corpus
  [["set-status 100 (bottom)"  :rf.server/set-status
    rf.ssr.server-fx-schemas/set-status-args 100                                  true]
   ["set-status 599 (top)"     :rf.server/set-status
    rf.ssr.server-fx-schemas/set-status-args 599                                  true]
   ["set-status string"        :rf.server/set-status
    rf.ssr.server-fx-schemas/set-status-args "not-an-int"                         false]
   ["set-status below range"   :rf.server/set-status
    rf.ssr.server-fx-schemas/set-status-args 99                                   false]
   ["set-status above range"   :rf.server/set-status
    rf.ssr.server-fx-schemas/set-status-args 600                                  false]

   ["set-header well-formed"   :rf.server/set-header
    rf.ssr.server-fx-schemas/set-header-args {:name "X-Foo" :value "bar"}          true]
   ["set-header no :value"     :rf.server/set-header
    rf.ssr.server-fx-schemas/set-header-args {:name "X-Foo"}                       false]
   ["set-header nil :value (required)" :rf.server/set-header
    rf.ssr.server-fx-schemas/set-header-args {:name "X-Foo" :value nil}            false]
   ;; A symbol passes the token grammar once `str`-ed; only the string gate sees it.
   ["set-header symbol :name"  :rf.server/set-header
    rf.ssr.server-fx-schemas/set-header-args {:name 'x-foo :value "bar"}           false]

   ["append-header well-formed" :rf.server/append-header
    rf.ssr.server-fx-schemas/append-header-args {:name "Vary" :value "Accept"}     true]
   ["append-header int :value"  :rf.server/append-header
    rf.ssr.server-fx-schemas/append-header-args {:name "Vary" :value 42}           false]

   ["set-cookie canonical"     :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name "session" :value "abc"
                                              :max-age 3600 :same-site :lax
                                              :secure true :http-only true
                                              :path "/" :domain "example.com"}     true]
   ["set-cookie string attrs (documented ingress tolerance)" :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name "session" :value "abc"
                                              :max-age "3600" :expires "1700000000000"
                                              :same-site "Strict"}                 true]
   ["set-cookie every optional nil" :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name "s" :value "v"
                                              :path nil :domain nil :max-age nil
                                              :expires nil :same-site nil
                                              :secure nil :http-only nil}          true]
   ["set-cookie no :value"     :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name "session"}                     false]
   ["set-cookie nil :value (required)" :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name "s" :value nil}                false]
   ["set-cookie nil :name (required)"  :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name nil :value "v"}                false]
   ["set-cookie keyword :name"  :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name :csrf :value "v"}              false]
   ["set-cookie bogus :same-site keyword" :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name "s" :value "v" :same-site :bogus} false]
   ["set-cookie non-boolean :secure" :rf.server/set-cookie
    rf.ssr.server-fx-schemas/set-cookie-args {:name "s" :value "v" :secure "yes"}  false]

   ["delete-cookie well-formed" :rf.server/delete-cookie
    rf.ssr.server-fx-schemas/delete-cookie-args {:name "stale" :path "/"}          true]
   ["delete-cookie every optional nil" :rf.server/delete-cookie
    rf.ssr.server-fx-schemas/delete-cookie-args {:name "stale" :path nil :domain nil} true]
   ["delete-cookie no :name"    :rf.server/delete-cookie
    rf.ssr.server-fx-schemas/delete-cookie-args {:path "/"}                        false]
   ["delete-cookie int :path"   :rf.server/delete-cookie
    rf.ssr.server-fx-schemas/delete-cookie-args {:name "stale" :path 42}           false]
   ["delete-cookie keyword :name" :rf.server/delete-cookie
    rf.ssr.server-fx-schemas/delete-cookie-args {:name :stale :path "/"}           false]

   ["redirect well-formed"     :rf.server/redirect
    rf.ssr.server-fx-schemas/redirect-args {:location "/dashboard" :status 302}    true]
   ;; The documented no-target graceful path, not a structural error.
   ["redirect no target"       :rf.server/redirect
    rf.ssr.server-fx-schemas/redirect-args {:status 302}                           true]
   ["redirect every optional nil" :rf.server/redirect
    rf.ssr.server-fx-schemas/redirect-args {:location nil :status nil}             true]
   ["redirect non-int :status" :rf.server/redirect
    rf.ssr.server-fx-schemas/redirect-args {:location "/x" :status "oops"}         false]
   ["redirect keyword :location" :rf.server/redirect
    rf.ssr.server-fx-schemas/redirect-args {:location :dashboard}                  false]

   ["safe-redirect well-formed" :rf.server/safe-redirect
    rf.ssr.server-fx-schemas/safe-redirect-args {:location "/dashboard"
                                                 :status 302
                                                 :relative-only? true
                                                 :allow ["app.example.com"]}       true]
   ["safe-redirect every optional nil" :rf.server/safe-redirect
    rf.ssr.server-fx-schemas/safe-redirect-args {:location "/ok" :status nil
                                                 :relative-only? nil :allow nil}   true]
   ["safe-redirect no :location" :rf.server/safe-redirect
    rf.ssr.server-fx-schemas/safe-redirect-args {:status 302}                      false]
   ["safe-redirect nil :location (required)" :rf.server/safe-redirect
    rf.ssr.server-fx-schemas/safe-redirect-args {:location nil}                    false]
   ["safe-redirect non-int :status" :rf.server/safe-redirect
    rf.ssr.server-fx-schemas/safe-redirect-args {:location "/ok" :status "not-int"} false]
   ["safe-redirect scalar :allow" :rf.server/safe-redirect
    rf.ssr.server-fx-schemas/safe-redirect-args {:location "/ok"
                                                 :allow "app.example.com"}         false]
   ["safe-redirect non-boolean :relative-only?" :rf.server/safe-redirect
    rf.ssr.server-fx-schemas/safe-redirect-args {:location "/ok" :relative-only? "yes"} false]])

(deftest a-target-less-safe-redirect-is-a-code-bug-not-a-security-signal
  (testing "a missing or non-string `:location` is refused by the shape guard,
            so it never reaches the URL gate and is never filed as an
            open-redirect probe on the always-on security axis"
    (doseq [[label args] [["missing :location" {:status 302}]
                          ["keyword :location" {:location :dashboard}]]]
      (let [{:keys [raw records]} (drive! [:rf.server/safe-redirect args])]
        (is (nil? (:redirect raw)) label)
        (is (not-any? #(clojure.string/starts-with? (name (:error %)) "safe-redirect-")
                      records)
            (str label "; saw: " (pr-str (mapv :error records))))))))

(deftest an-optional-key-present-with-nil-is-absent-not-a-violation
  (testing "a cookie built from an options map lands with its nil attrs as
            absent, raises no error and is not projected to a 500"
    (let [cookie {:name "session" :value "abc"
                  :path nil :domain nil :max-age nil
                  :expires nil :same-site nil
                  :secure nil :http-only nil}
          {:keys [raw response records]} (drive! [:rf.server/set-cookie cookie])]
      (is (= [[cookie] [] 200]
             [(:cookies raw) (mapv :error records) (:status response)])))))

(defn- landed?
  "Did `fx-id` WRITE to the accumulator? `:rf.server/set-status` is read off
  the `_status-writes` bookkeeping, since 200 is also the default status."
  [fx-id args {:keys [raw bookkept]}]
  (case fx-id
    :rf.server/set-status
    (boolean (seq (get bookkept rf.ssr.response/status-writes-key)))

    (:rf.server/set-header :rf.server/append-header)
    (boolean (some (fn [[k _]] (= (:name args) k)) (:headers raw)))

    (:rf.server/set-cookie :rf.server/delete-cookie)
    (boolean (seq (:cookies raw)))

    (:rf.server/redirect :rf.server/safe-redirect)
    (some? (:redirect raw))))

(deftest schema-and-guard-agree-on-every-canonical-case
  (doseq [[label _fx-id schema args accept?] acceptance-corpus]
    (is (= accept? (m/validate schema args))
        (str label " — the registered Malli schema must "
             (if accept? "ACCEPT" "REJECT") " " (pr-str args)))))

(deftest the-guards-accept-exactly-what-the-schemas-accept
  (testing "a row the schema rejects leaves the accumulator untouched and
            skips only that fx; a row it accepts lands"
    (doseq [[label fx-id _schema args accept?] acceptance-corpus]
      (let [{:keys [raw] :as driven} (drive! [fx-id args])]
        (is (= [accept? true] [(landed? fx-id args driven) (sibling-ran? raw)])
            (str label " — [landed? sibling-ran?] for " (pr-str args)))))))

(deftest a-user-fx-schema-stays-dev-posture-and-genuinely-elides
  (testing "the guard is for the seven reserved fx only: a `:schema` an app
            declares on its own fx is skipped with a diagnostic in dev and
            not checked at all in a release build"
    (let [ran    (atom [])
          traces (atom [])
          tag    (keyword "rf.test" (str "user-" (name (gensym "u"))))
          f      (server-frame)]
      (rf/reg-fx ::user-fx
        {:schema [:map [:n :int]]}
        (fn [_ctx args] (swap! ran conj args)))
      (rf/reg-event ::user-attempt (fn [_ _] {:fx [[::user-fx {:n "not-an-int"}]]}))
      (rf/register-listener! :trace tag
        (fn [ev] (when (and (= :rf.error/schema-validation-failure (:operation ev))
                            (= :fx-args (-> ev :tags :where)))
                   (swap! traces conj ev))))
      (try
        (rf/dispatch-sync [::user-attempt] {:frame f})
        (finally (rf/unregister-listener! :trace tag)))
      (is (= (if rf.interop/debug-enabled?
               [[] true]
               [[{:n "not-an-int"}] false])
             [@ran (boolean (seq @traces))])
          "[handler runs with the args, a diagnostic fired]"))))

;; A rejection routes the offending value through `diag-value-summary` into the
;; message and the ex-data, because a cookie `:value` is a session token. The
;; handlers are called directly so the dev schema gate cannot intercept.

(def ^:private sentinel
  "24 characters, so a 24-character head would reproduce it whole."
  "SENTINELSENTINELSENTINEL")

(defn- rejection-of
  "Call `handler-fn` with a live server frame and `args`; return what it threw."
  [handler-fn args]
  (try
    (handler-fn {:frame (server-frame)} args)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(deftest a-rejection-carries-no-fragment-of-the-value-it-rejected
  (doseq [[label handler-fn args]
          [["status — a token string"
            rf.ssr.response/set-status-fx (str sentinel "-tail-0123456789")]
           ["cookie :value — a map whose keys carry the token"
            rf.ssr.response/set-cookie-fx {:name "session" :value {sentinel "v" (keyword sentinel) 2}}]
           ["args — a token string where a map was required"
            rf.ssr.response/set-cookie-fx (str sentinel "-not-a-map")]]]
    (testing label
      (let [e (rejection-of handler-fn args)]
        (is (= :rf.error/server-fx-args-invalid (:rf.error/id (ex-data e))))
        (is (not (clojure.string/includes? (str (ex-message e) (pr-str (ex-data e)))
                                           "SENTINEL"))
            (str "disclosed: " (ex-message e) " " (pr-str (ex-data e))))))))

(deftest a-rejection-still-names-the-fx-the-key-and-the-shape
  (is (= {:fx-id    :rf.server/set-cookie
          :key      :value
          :value    {:type :map :count 1}
          :expected "a string"}
         (select-keys (ex-data (rejection-of rf.ssr.response/set-cookie-fx
                                             {:name "session" :value {sentinel "v"}}))
                      [:fx-id :key :value :expected]))))

(deftest a-rejection-message-is-a-fixed-size-whatever-arrives
  (let [small (ex-message (rejection-of rf.ssr.response/set-header-fx
                                        {:name "X-Auth" :value {:a 1}}))
        huge  (ex-message (rejection-of rf.ssr.response/set-header-fx
                                        {:name "X-Auth"
                                         :value (into {} (map (fn [i] [(str sentinel "-" i) i]))
                                                      (range 2000))}))]
    ;; The two messages differ only in the rendered `:count` digits.
    (is (< (- (count huge) (count small)) 8)
        (str "message grew with the input: " (count small) " → " (count huge)))))
