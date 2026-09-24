(ns re-frame.ssr.ring.app-db-egress-projection-test
  "rf2-bt9kct (+ rf2-mx9w6q dup) — the final hydration app-db slice MUST be
  run through the centralized `:rf.egress/ssr-hydration` projection AFTER the
  `:payload` allowlist, so a frame-classified sensitive child inside an
  allowlisted (or whole-app-db) top-level key redacts before it serializes into
  `:rf/app-db`.

  EP-0015 §14: SSR/hydration is allowlist-FIRST, and frame classification
  COMPOSES as defense-in-depth. The prior `re-frame.ssr.ring.payload/build-payload`
  handed `(apply-policy app-db policy-opts)` straight to `build-payload` — only
  allowlisting, never the frame-owned egress projector. A frame declaring
  `:sensitive {:app-db [[:session :token]]}` and shipping `:session` (via the
  allowlist OR `:rf.ssr.payload/whole-app-db`) serialized the raw token.

  This drives the ACTUAL non-streaming payload-build path
  (`re-frame.ssr.ring.payload/build-payload`) against a registered server frame
  carrying a `:sensitive :app-db` declaration."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.payload :as rf.ssr.ring.payload]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private server-frame :rf.bt9kct/server)

(defn- reg-sensitive-frame! []
  ;; A server frame whose classification marks the nested :session :token path
  ;; sensitive — the canonical durable app-db egress route. EP-0025 B4-ssr
  ;; follow-on (rf2-ux7983): the path is classified through the post-purge
  ;; mechanism — a B3 COMMIT-PLANE `:sensitive` effect the frame's init event
  ;; returns alongside `:db` (EP-0025 §How it works / §Examples) — writing it
  ;; into the per-frame `[:rf.runtime/elision]` registry the
  ;; `:rf.egress/ssr-hydration` egress walk reads. Replaces the retired
  ;; retired frame-config `:sensitive {:app-db}` durable annotation (deleted by the
  ;; EP-0025 B1b purge). Value-independent — classified at init, read at egress.
  (rf/reg-event :rf.bt9kct/classify
    (fn [_ _] {:sensitive [[:session :token]]}))
  (rf/make-frame {:id server-frame :platform       :server
                  :initial-events [[:rf.bt9kct/classify]]}))

(def ^:private app-db
  "An app-db whose allowlisted :session key carries a frame-sensitive child
  (:token) alongside a public sibling (:user), plus a server-only :secrets key
  that must never be allowlisted."
  {:session {:token "secret-jwt" :user "alice"}
   :public  {:page :dashboard}
   :secrets {:api-key "internal-only"}})

;; ---- allowlist + projection compose ---------------------------------------

(deftest allowlisted-key-sensitive-child-redacted
  (testing "an allowlisted top-level key (:session) ships, but its
            frame-sensitive child (:token) is redacted by the ssr-hydration
            projection; the public sibling (:user) rides verbatim; unlisted
            keys (:public / :secrets) are omitted by the allowlist"
    (reg-sensitive-frame!)
    (let [out    (rf.ssr.ring.payload/build-payload server-frame app-db "h1"
                                        {:payload [:session :public]})
          db     (:rf/app-db out)]
      (is (= rf.privacy/redacted-sentinel (get-in db [:session :token]))
          "the frame-sensitive nested :token is redacted on the hydration wire")
      (is (= "alice" (get-in db [:session :user]))
          "the public sibling rides verbatim")
      (is (= {:page :dashboard} (:public db))
          "another allowlisted key rides through the projection unchanged")
      (is (not (contains? db :secrets))
          ":secrets (unlisted) is omitted by the allowlist")
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw token survives anywhere in the emitted payload"))))

(deftest whole-app-db-still-projects-sensitive-children
  (testing "an explicit :rf.ssr.payload/whole-app-db opt-in ships every key,
            but STILL projects frame-sensitive children — the raw token never
            rides even when the whole app-db is opted in"
    (reg-sensitive-frame!)
    (let [out (rf.ssr.ring.payload/build-payload server-frame app-db "h1"
                                     {:payload :rf.ssr.payload/whole-app-db})
          db  (:rf/app-db out)]
      (is (= rf.privacy/redacted-sentinel (get-in db [:session :token]))
          "whole-app-db still redacts the frame-sensitive :token")
      (is (= "alice" (get-in db [:session :user])))
      (is (= "internal-only" (get-in db [:secrets :api-key]))
          ":secrets rides under whole-app-db opt-in (no frame mark on it)")
      (is (not (.contains (pr-str out) "secret-jwt"))))))

;; ---- fail-closed: an unknown / unregistered frame redacts the whole slice --

(deftest missing-frame-policy-fails-closed
  (testing "building the payload against an UNREGISTERED frame (no resolvable
            elision policy) fails CLOSED — the whole app-db slice redacts to
            the :rf/redacted sentinel rather than ship under no policy"
    ;; deliberately do NOT register :rf.bt9kct/ghost
    (let [out (rf.ssr.ring.payload/build-payload :rf.bt9kct/ghost app-db "h1"
                                     {:payload [:session]})]
      (is (= rf.privacy/redacted-sentinel (:rf/app-db out))
          "an unresolvable frame redacts the whole slice (fail-closed)")
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw value escapes under a missing frame policy"))))

(deftest no-classification-frame-rides-allowlisted-slice-verbatim
  (testing "a registered frame with NO :sensitive classification ships its
            allowlisted slice verbatim (the projection is a precise
            classification walk, not a blanket scrub)"
    (rf/make-frame {:id :rf.bt9kct/plain :platform :server})
    (let [out (rf.ssr.ring.payload/build-payload :rf.bt9kct/plain app-db "h1"
                                     {:payload [:session]})
          db  (:rf/app-db out)]
      (is (= {:token "secret-jwt" :user "alice"} (:session db))
          "no frame classification → allowlisted slice rides verbatim")
      (is (not (contains? db :public))))))

;; ---- rf2-hjz4r: no size elision on the hydration wire ----------------------
;;
;; `:large` exists to protect tool budgets and hosted monitors, not the wire to
;; the page's own browser. The hydration payload is installed as LIVE client
;; state by `:rf/hydrate`, so an elided value arrived as a
;; `:rf.size/large-elided` marker map where the page expected its data. The
;; `:rf.egress/ssr-hydration` profile now keeps large values; `:sensitive` still
;; redacts.

(def ^:private catalog-frame :rf.hjz4r/catalog-server)

(defn- reg-catalog-frame! []
  (rf/reg-event :rf.hjz4r/classify-catalog
    (fn [_ _] {:large     [[:catalog :items]]
               :sensitive [[:catalog :owner-token]]}))
  (rf/make-frame {:id catalog-frame :platform :server
                  :initial-events [[:rf.hjz4r/classify-catalog]]}))

(def ^:private catalog-db
  {:catalog {:items [1 2 3] :title "Shop" :owner-token "tok-server-only"}
   :private {:note "never allowlisted"}})

(deftest large-classified-value-rides-the-hydration-wire-intact
  (testing "a :large path inside an allowlisted key ships its data, not a size
            marker; its :sensitive sibling still redacts and its unclassified
            sibling is unchanged"
    (reg-catalog-frame!)
    (let [out (rf.ssr.ring.payload/build-payload catalog-frame catalog-db nil
                                                 {:payload [:catalog]})
          db  (:rf/app-db out)]
      (is (= [1 2 3] (get-in db [:catalog :items]))
          "the :large vector rides intact (it was a :rf.size/large-elided marker map)")
      (is (= rf.privacy/redacted-sentinel (get-in db [:catalog :owner-token]))
          "control: the :sensitive sibling still redacts")
      (is (= "Shop" (get-in db [:catalog :title]))
          "control: the unclassified sibling is unchanged")
      (is (not (contains? db :private))
          "control: the unallowlisted key is still absent")
      (is (not (.contains (pr-str out) "tok-server-only"))
          "control: no raw sensitive value survives anywhere in the payload"))))

(deftest large-classified-value-survives-a-real-hydrate
  (testing "the wire payload, read back as EDN and installed by a real
            :rf/hydrate into a client frame, leaves the client holding the
            vector"
    (reg-catalog-frame!)
    (let [payload (-> (rf.ssr.ring.payload/build-payload catalog-frame catalog-db nil
                                                         {:payload [:catalog]})
                      pr-str
                      edn/read-string)
          client  (rf.frame/make-anon-frame-record! {:doc      "rf2-hjz4r client frame"
                                                     :platform :client})]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client})
      (let [items (get-in (rf/app-db-value client) [:catalog :items])]
        (is (vector? items) (str "the client holds a vector, not a marker; got " (pr-str items)))
        (is (= 3 (count items)))
        (is (= [1 2 3] items)))
      (is (= rf.privacy/redacted-sentinel
             (get-in (rf/app-db-value client) [:catalog :owner-token]))
          "control: the sensitive sibling is still the sentinel on the client"))))

(deftest large-value-obeys-the-numeric-crossing-rule
  (testing "a :large value now rides, so it now obeys the JVM numeric crossing
            rule: a Long past 2^53 inside it is refused rather than shipped as a
            marker (which destroyed the value silently)"
    (reg-catalog-frame!)
    (let [data (try (rf.ssr.ring.payload/build-payload
                      catalog-frame
                      {:catalog {:items [1 9007199254740993] :title "Shop"}}
                      nil {:payload [:catalog]})
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :rf.error/ssr-hydration-payload-invalid (:rf.error/id data)))
      (is (= [:catalog :items 1] (:path data)))
      (is (= :rf/app-db (:partition data))))))

;; ---- rf2-hjz4r: the :payload-include-sensitive permit -----------------------
;;
;; A CSRF synchronizer token is the counterexample to "sensitive means
;; server-only": the app keeps it out of its logs and tools, yet the page must
;; send it back. The host names it; everything else classified stays redacted.
;; These drive the REAL handlers end to end, so the pipeline's policy opts and
;; the streaming writer are what carry the permit.

(defn- reg-session-app! []
  (rf/reg-event :rf.hjz4r/seed-session
    (fn [_ _]
      {:db        {:session {:csrf "csrf-abc-123" :upstream-key "sk-server-only" :user "alice"}
                   :secrets {:api-key "internal-only"}}
       :sensitive [[:session :csrf] [:session :upstream-key]]}))
  (rf/reg-sub :rf.hjz4r/csrf (fn [db _] (get-in db [:session :csrf])))
  (rf/reg-sub :rf.hjz4r/user (fn [db _] (get-in db [:session :user])))
  ;; A hiccup-tier login form: the token in a hidden input, the user's name in
  ;; a paragraph. It never renders the upstream key.
  (rf/reg-view* :rf.hjz4r/root
    (fn []
      [:form {:method "post"}
       [:input {:type "hidden" :name "csrf" :value (rf/subscribe-once [:rf.hjz4r/csrf])}]
       [:p (rf/subscribe-once [:rf.hjz4r/user])]])))

(def ^:private permit {:payload-include-sensitive [[:session :csrf]]})

(defn- payload-of [body]
  (some-> (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)
          second
          edn/read-string))

(defn- serve-session!
  "One request through the real `ssr-handler`: the body and the wire payload
  read back as EDN."
  [extra-opts]
  (let [handler (rf.ssr.ring/ssr-handler
                  (merge {:initial-events [[:rf.hjz4r/seed-session]]
                          :root-view      (fn [] ((rf/view :rf.hjz4r/root)))
                          :payload        [:session]}
                         extra-opts))
        body    (:body (handler {:uri "/login" :request-method :get}))]
    {:body body :payload (payload-of body)}))

(deftest a-permitted-sensitive-value-rides-the-payload-raw
  (reg-session-app!)
  (let [{:keys [payload]} (serve-session! permit)
        db                (:rf/app-db payload)]
    (is (= "csrf-abc-123" (get-in db [:session :csrf]))
        "the permitted token rides raw (it was :rf/redacted)")
    (is (= rf.privacy/redacted-sentinel (get-in db [:session :upstream-key]))
        "control: the classified sibling the host did not permit stays redacted")
    (is (= "alice" (get-in db [:session :user]))
        "control: the unclassified sibling is unchanged")
    (is (not (contains? db :secrets))
        "control: the unallowlisted key is absent")
    (is (not (.contains (pr-str payload) "sk-server-only"))
        "control: the withheld value survives nowhere in the payload"))
  (testing "control: with no permit the token is redacted, as before"
    (is (= rf.privacy/redacted-sentinel
           (get-in (:payload (serve-session! {})) [:rf/app-db :session :csrf])))))

(deftest a-permitted-value-is-live-client-state-after-a-real-hydrate
  (reg-session-app!)
  (let [{:keys [payload]} (serve-session! permit)
        client            (rf.frame/make-anon-frame-record! {:doc      "rf2-hjz4r permit client"
                                                             :platform :client})]
    (rf/dispatch-sync [:rf/hydrate payload] {:frame client})
    (is (= "csrf-abc-123" (get-in (rf/app-db-value client) [:session :csrf]))
        "the client holds the token it must send back, not :rf/redacted")
    (is (= rf.privacy/redacted-sentinel
           (get-in (rf/app-db-value client) [:session :upstream-key]))
        "control: the withheld sibling is still the sentinel on the client")))

(deftest a-permitted-rendered-value-hydrates-without-a-mismatch
  (testing "the hiccup tier renders the LIVE frame, so the server HTML carries
            the raw token; with the permit the client's first render hashes the
            same (it hashed :rf/redacted, a mismatch on a correct app)"
    (reg-session-app!)
    (let [{:keys [body payload]} (serve-session! permit)
          client (rf.frame/make-anon-frame-record! {:doc      "rf2-hjz4r hash client"
                                                    :platform :client
                                                    :ssr      {:on-mismatch :hard-error}})]
      (is (.contains ^String body "value=\"csrf-abc-123\"")
          "control: the server HTML carries the raw token")
      (is (some? (:rf/render-hash payload))
          "control: the resolving root carries the hash channel")
      (is (= payload (rf.ssr/hydrate! {:frame          client
                                       :payload        payload
                                       :render-tree-fn #((rf/view :rf.hjz4r/root))}))
          "no :rf.ssr/hydration-mismatch: the client's first render matches the server's"))))

(deftest the-streaming-final-payload-honours-the-permit
  (reg-session-app!)
  (let [handler (rf.ssr.ring/stream-handler
                  (merge {:initial-events [[:rf.hjz4r/seed-session]]
                          :root-view      [(rf/view :rf.hjz4r/root)]
                          :payload        [:session]}
                         permit))
        body    (with-open [in ^java.io.InputStream (:body (handler {:uri "/login" :request-method :get}))]
                  (slurp in))
        db      (:rf/app-db (payload-of body))]
    (is (= "csrf-abc-123" (get-in db [:session :csrf]))
        "the streamed final payload carries the permitted token raw")
    (is (= rf.privacy/redacted-sentinel (get-in db [:session :upstream-key]))
        "control: the unpermitted sibling stays redacted")))

(deftest a-malformed-permit-fails-at-handler-construction
  (let [data (try (rf.ssr.ring/ssr-handler
                    {:initial-events            [[:rf.hjz4r/seed-session]]
                     :root-view                 (fn [] [:p "x"])
                     :payload                   [:session]
                     :payload-include-sensitive [:session :csrf]})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :rf.error/ssr-malformed-payload-allowlist (:rf.error/id data))
        "one path written unwrapped fails at boot, not at first request")
    (is (= :payload-include-sensitive (:opt data)))
    (is (= [:session :csrf] (:bad-entries data)))))
