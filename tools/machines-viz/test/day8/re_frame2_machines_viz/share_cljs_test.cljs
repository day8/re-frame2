(ns day8.re-frame2-machines-viz.share-cljs-test
  "Tests for the share-URL encode/decode pipeline (rf2-8d7w1 · v1.0).

  Coverage:
  - `encode-share-url` → `decode-share-url` round-trip preserves the
    ChartState (machine-id, frame-id, definition, snapshot state).
  - Snapshot `:state` configuration arms (rf2-9l8h8): flat keyword,
    compound vector-path, and parallel region-map all round-trip; a
    malformed `:state` is rejected at ENCODE (encode/decode symmetric —
    no undecodable URL), and the closed-map rule still rejects extra
    `:snapshot` keys for every arm.
  - Canonicalisation: the same ChartState encodes byte-for-byte
    identically regardless of input map/set ordering (Principles
    §Reproducible from the registry alone).
  - Privacy: runtime `:data` on `:snapshot` and `:source-coords` are
    dropped; definition metadata is stripped.
  - Versioned envelope: `:rf.machines-viz.share/v` rides outermost;
    a newer version is rejected with `:unknown-version`.
  - Every documented `decode-failed` `:reason`.
  - Host override + `chart-state->props` projection."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [day8.re-frame2-machines-viz.share :as share]))

;; ---------------------------------------------------------------------------
;; Test helper — craft a raw share-URL fragment from an arbitrary
;; envelope (so we can build a future-version / missing-envelope payload
;; the public encoder would never emit). Mirrors the encoder's
;; transit-json → base64url step.

(defn- envelope->url
  [envelope]
  (let [transit-str (transit/write (transit/writer :json) envelope)
        b64 (-> (js/btoa (js/unescape (js/encodeURIComponent transit-str)))
                (str/replace "+" "-")
                (str/replace "/" "_")
                (str/replace "=" ""))]
    (str "https://x/viewer.html#machine=" b64)))

;; ---------------------------------------------------------------------------
;; Fixtures

(def idle-loading-success
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def chart-state
  {:machine-id :auth/login-flow
   :frame-id   :app/main
   :definition idle-loading-success
   :snapshot   {:state :loading}})

(def parallel-state
  {:machine-id :editor/flow
   :frame-id   :app/main
   :definition {:type :parallel
                :regions {:data {:initial :clean :states {:clean {} :dirty {}}}
                          :form {:initial :idle  :states {:idle {} :busy {}}}}}})

(def compound-definition
  "A compound (hierarchical) machine — its snapshot `:state` is a vector
  path from the root to the active leaf."
  {:initial :authenticated
   :states  {:authenticated
             {:initial :cart
              :states  {:cart    {:initial :browsing
                                  :states  {:browsing {} :checkout {}}}
                        :account {}}}
             :anonymous {}}})

;; ---------------------------------------------------------------------------
;; Round-trip

(deftest round-trip-preserves-chart-state
  (testing "encode → decode recovers the ChartState exactly"
    (let [url (share/encode-share-url chart-state)
          env (share/decode-share-url url)
          back (:rf.machines-viz.share/chart env)]
      (is (= :auth/login-flow (:machine-id back)))
      (is (= :app/main (:frame-id back)))
      (is (= idle-loading-success (:definition back)))
      (is (= {:state :loading} (:snapshot back)))
      (is (= "1" (:rf.machines-viz.share/v env)))
      (is (number? (:rf.machines-viz.share/created env))))))

(deftest round-trip-parallel
  (testing "parallel definitions round-trip"
    (let [back (:rf.machines-viz.share/chart
                 (share/decode-share-url (share/encode-share-url parallel-state)))]
      (is (= (:definition parallel-state) (:definition back)))
      (is (= :editor/flow (:machine-id back))))))

(deftest round-trip-no-snapshot
  (testing "a ChartState with no :snapshot round-trips without one"
    (let [cs   (dissoc chart-state :snapshot)
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (share/encode-share-url cs)))]
      (is (not (contains? back :snapshot)))
      (is (= idle-loading-success (:definition back))))))

;; ---------------------------------------------------------------------------
;; Snapshot :state CONFIGURATION — the three Spec 005 §Snapshot-shape arms
;; (rf2-9l8h8). The bug: encode accepted compound/parallel snapshots but a
;; keyword-only decoder rejected them → an undecodable URL. All three arms
;; must now round-trip cleanly and stay encode/decode symmetric.

(deftest round-trip-flat-snapshot
  (testing "a FLAT keyword :state round-trips"
    (let [cs   (assoc chart-state :snapshot {:state :loading})
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (share/encode-share-url cs)))]
      (is (= {:state :loading} (:snapshot back)))
      (is (keyword? (get-in back [:snapshot :state]))))))

(deftest round-trip-compound-snapshot
  (testing "a COMPOUND vector-path :state round-trips (was rejected pre-9l8h8)"
    (let [cs   {:machine-id :shop/store
                :frame-id   :app/main
                :definition compound-definition
                :snapshot   {:state [:authenticated :cart :browsing]}}
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (share/encode-share-url cs)))]
      (is (= {:state [:authenticated :cart :browsing]} (:snapshot back)))
      (is (vector? (get-in back [:snapshot :state]))))))

(deftest round-trip-parallel-snapshot
  (testing "a PARALLEL region-map :state round-trips (was rejected pre-9l8h8)"
    (let [cs   (assoc parallel-state :snapshot {:state {:data :dirty :form :busy}})
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (share/encode-share-url cs)))]
      (is (= {:state {:data :dirty :form :busy}} (:snapshot back)))
      (is (map? (get-in back [:snapshot :state])))))
  (testing "a PARALLEL region-map whose region value is itself a compound path"
    (let [cs   (assoc parallel-state
                      :snapshot {:state {:data :dirty :form [:edit :touched]}})
          back (:rf.machines-viz.share/chart
                 (share/decode-share-url (share/encode-share-url cs)))]
      (is (= {:state {:data :dirty :form [:edit :touched]}} (:snapshot back))))))

(deftest malformed-state-rejected-at-encode
  (testing "a :state that is none of the three arms is rejected at ENCODE — symmetric, no undecodable URL"
    (doseq [bad-state [42
                       "loading"
                       []                              ;; empty vector path
                       [:auth "authing"]               ;; non-keyword in path
                       {}                              ;; empty region-map
                       {:data "loading"}               ;; non-keyword/path region value
                       {"data" :loading}]]             ;; non-keyword region name
      (let [cs (assoc chart-state :snapshot {:state bad-state})
            d  (try (share/encode-share-url cs)
                    (catch :default e (ex-data e)))]
        (is (= :invalid-chart-state (:reason d))
            (str "encode must reject malformed :state " (pr-str bad-state)))
        (is (= :rf.machines-viz.share/encode-failed (:rf.error/id d)))))))

(deftest encode-decode-symmetric-for-all-arms
  (testing "every arm the encoder accepts, the decoder also accepts (no undecodable URL)"
    (doseq [state [:loading
                   [:authenticated :cart :browsing]
                   {:data :dirty :form :busy}
                   {:data :dirty :form [:edit :touched]}]]
      (let [cs  (assoc chart-state
                       :definition compound-definition
                       :snapshot {:state state})
            url (share/encode-share-url cs)
            ;; decode-share-url-safe never throws — an undecodable URL
            ;; (the pre-9l8h8 bug) would surface as {:error ...}.
            r   (share/decode-share-url-safe url)]
        (is (some? (:ok r)) (str "arm round-trips cleanly: " (pr-str state)))
        (is (nil? (:error r)))
        (is (= state (get-in (:ok r) [:rf.machines-viz.share/chart :snapshot :state])))))))

(deftest url-shape
  (testing "encoded URL carries the #machine= fragment + uses base64url alphabet"
    (let [url (share/encode-share-url chart-state)
          frag (subs url (inc (str/index-of url "#")))]
      (is (str/starts-with? url share/default-host))
      (is (str/starts-with? frag "machine="))
      (let [payload (subs frag (count "machine="))]
        ;; base64url: no +, /, or = padding
        (is (not (str/includes? payload "+")))
        (is (not (str/includes? payload "/")))
        (is (not (str/includes? payload "=")))))))

(deftest host-override
  (testing "{:host ...} swaps the viewer base"
    (let [url (share/encode-share-url chart-state {:host "https://acme.example.com/v.html"})]
      (is (str/starts-with? url "https://acme.example.com/v.html#machine=")))))

;; ---------------------------------------------------------------------------
;; Canonicalisation / reproducibility

(defn- frozen-now
  "Run `f` with `js/Date.now` pinned to a constant so the envelope's
  non-reproducible `:created` stamp doesn't perturb byte-identity
  assertions. (`:created` is the ONE allowed non-reproducible bit per
  Principles §Reproducible from the registry alone.)"
  [f]
  (let [orig (.-now js/Date)]
    (try
      (set! (.-now js/Date) (fn [] 1736000000000))
      (f)
      (finally (set! (.-now js/Date) orig)))))

(deftest reproducible-encoding
  (testing "differently-ordered but equal ChartStates encode identically"
    (let [a {:machine-id :m/x :frame-id :app/main
             :definition {:initial :a
                          :states {:a {:on {:go :b}} :b {:on {:back :a}}}}}
          ;; Same value, keys constructed in a different insertion order.
          b {:definition {:states {:b {:on {:back :a}} :a {:on {:go :b}}}
                          :initial :a}
             :frame-id :app/main :machine-id :m/x}]
      (is (= a b) "fixtures are value-equal")
      (frozen-now
        (fn []
          (is (= (share/encode-share-url a) (share/encode-share-url b))
              "and therefore encode byte-for-byte identically (created frozen)"))))))

(deftest reproducible-with-sets
  (testing "set-valued slots (e.g. :tags) canonicalise to a stable order"
    (let [a {:machine-id :m/x :frame-id :app/main
             :definition {:initial :s1
                          :states {:s1 {:tags #{:b :a :c}}}}}
          b {:machine-id :m/x :frame-id :app/main
             :definition {:initial :s1
                          :states {:s1 {:tags #{:c :a :b}}}}}]
      (frozen-now
        (fn []
          (is (= (share/encode-share-url a) (share/encode-share-url b)))))
      (let [back (:rf.machines-viz.share/chart
                   (share/decode-share-url (share/encode-share-url a)))]
        (is (= #{:a :b :c} (get-in back [:definition :states :s1 :tags])))))))

;; ---------------------------------------------------------------------------
;; Privacy — no session data in shares

(deftest snapshot-data-is-dropped
  (testing "runtime :data riding on :snapshot is structurally excluded"
    (let [leaky (assoc chart-state :snapshot {:state :loading
                                              :data  {:token "secret-abc"
                                                      :form  {:password "hunter2"}}})
          back  (:rf.machines-viz.share/chart
                  (share/decode-share-url (share/encode-share-url leaky)))]
      (is (= {:state :loading} (:snapshot back)))
      (is (not (contains? (:snapshot back) :data)))
      (is (not (str/includes? (share/encode-share-url leaky) "hunter2"))
          "the secret never reaches the encoded bytes"))))

(deftest source-coords-are-dropped
  (testing ":source-coords passed by the caller never reach the payload"
    (let [leaky (assoc chart-state :source-coords {:file "/Users/mike/secret/x.cljs"})
          back  (:rf.machines-viz.share/chart
                  (share/decode-share-url (share/encode-share-url leaky)))]
      (is (not (contains? back :source-coords)))
      (is (not (str/includes? (share/encode-share-url leaky) "secret"))))))

(deftest definition-metadata-is-stripped
  (testing "macro-captured source-coord meta on the definition does not propagate"
    (let [defn-with-meta (with-meta idle-loading-success
                                    {:rf/source-coord {:file "/Users/mike/proj/m.cljs"
                                                       :line 42}})
          cs   (assoc chart-state :definition defn-with-meta)
          url  (share/encode-share-url cs)
          back (:rf.machines-viz.share/chart (share/decode-share-url url))]
      (is (nil? (meta (:definition back))) "no meta survives")
      (is (not (str/includes? url "proj")) "the file path never reaches the bytes"))))

;; ---------------------------------------------------------------------------
;; Versioning + failure modes

(deftest unknown-version-rejected
  (testing "a payload tagged with a newer version throws :unknown-version"
    (let [future-url (envelope->url
                       {:rf.machines-viz.share/v       "2"
                        :rf.machines-viz.share/chart   chart-state
                        :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url future-url)
                 (catch :default e (ex-data e)))]
      (is (= :unknown-version (:reason d)))
      (is (= "2" (:payload-version d))))))

(deftest missing-envelope-rejected
  (testing "a payload missing the envelope keys throws :missing-envelope"
    (let [bad-url (envelope->url {:not :an-envelope})
          d (try (share/decode-share-url bad-url)
                 (catch :default e (ex-data e)))]
      (is (= :missing-envelope (:reason d))))))

(deftest decoded-snapshot-with-extra-keys-rejected
  (testing "a hand-edited URL smuggling :data onto :snapshot is rejected on decode"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :snapshot {:state :loading
                                                                       :data {:token "leak"}})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "the closed :snapshot schema rejects extra keys at decode time"))))

(deftest decoded-snapshot-extra-key-alongside-compound-state-rejected
  (testing "a closed :snapshot is still closed for compound/parallel arms — extra keys rejected on decode"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :definition compound-definition
                                                            :snapshot {:state [:authenticated :cart :browsing]
                                                                       :data  {:token "leak"}})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))
          "widening :state to a configuration does NOT loosen the closed-map rule"))))

(deftest decoded-malformed-state-rejected
  (testing "a hand-edited URL whose :state is none of the three arms is rejected on decode (symmetric)"
    (let [smuggled (envelope->url
                     {:rf.machines-viz.share/v       "1"
                      :rf.machines-viz.share/chart   (assoc chart-state
                                                            :snapshot {:state {:data "not-a-keyword-or-path"}})
                      :rf.machines-viz.share/created 0})
          d (try (share/decode-share-url smuggled)
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))))))

(deftest malformed-fragment-rejected
  (testing "a URL with no #machine= fragment throws :malformed-fragment"
    (is (thrown-with-msg? :default #"malformed-fragment"
          (share/decode-share-url "https://example.com/app")))
    (let [d (try (share/decode-share-url "https://example.com/app")
                 (catch :default e (ex-data e)))]
      (is (= :malformed-fragment (:reason d))))))

(deftest malformed-base64-rejected
  (testing "a #machine= fragment that isn't valid base64url throws"
    (let [d (try (share/decode-share-url "https://x/viewer.html#machine=@@@not-b64@@@")
                 (catch :default e (ex-data e)))]
      (is (contains? #{:malformed-fragment :malformed-payload} (:reason d))))))

(deftest invalid-chart-state-rejected
  (testing "a decoded chart that fails the schema throws :invalid-chart-state"
    ;; Encode a valid one, then re-encode a tampered ChartState directly
    ;; through the encoder is blocked by the encoder's own validation.
    ;; So assert the encoder rejects an invalid ChartState up front.
    (is (thrown? :default
          (share/encode-share-url {:machine-id :x :frame-id :y :definition {}})))
    (let [d (try (share/encode-share-url {:machine-id "not-a-kw"
                                          :frame-id :y
                                          :definition idle-loading-success})
                 (catch :default e (ex-data e)))]
      (is (= :invalid-chart-state (:reason d))))))

(deftest decode-safe-wraps-errors
  (testing "decode-share-url-safe returns {:error ...} not a throw"
    (let [r (share/decode-share-url-safe "https://example.com/app")]
      (is (= :malformed-fragment (get-in r [:error :reason])))
      (is (nil? (:ok r))))
    (testing "and {:ok envelope} on success"
      (let [r (share/decode-share-url-safe (share/encode-share-url chart-state))]
        (is (some? (:ok r)))
        (is (nil? (:error r)))))))

;; ---------------------------------------------------------------------------
;; chart-state->props

(deftest chart-state->props-projection
  (testing "envelope → MachineChart props (read-only, current-state from snapshot)"
    (let [env   (share/decode-share-url (share/encode-share-url chart-state))
          props (share/chart-state->props env)]
      (is (= :auth/login-flow (:machine-id props)))
      (is (= idle-loading-success (:definition props)))
      (is (= :loading (:current-state props)))
      (is (true? (:read-only? props)))
      (is (not (contains? props :frame-id)) "frame-id is provenance, not a prop")))
  (testing "no snapshot → no :current-state"
    (let [env   (share/decode-share-url (share/encode-share-url (dissoc chart-state :snapshot)))
          props (share/chart-state->props env)]
      (is (not (contains? props :current-state)))
      (is (true? (:read-only? props)))))
  (testing "compound vector-path snapshot projects :current-state verbatim"
    (let [cs    {:machine-id :shop/store :frame-id :app/main
                 :definition compound-definition
                 :snapshot   {:state [:authenticated :cart :browsing]}}
          props (share/chart-state->props (share/decode-share-url (share/encode-share-url cs)))]
      (is (= [:authenticated :cart :browsing] (:current-state props)))))
  (testing "parallel region-map snapshot projects :current-state verbatim"
    (let [cs    (assoc parallel-state :snapshot {:state {:data :dirty :form :busy}})
          props (share/chart-state->props (share/decode-share-url (share/encode-share-url cs)))]
      (is (= {:data :dirty :form :busy} (:current-state props))))))
