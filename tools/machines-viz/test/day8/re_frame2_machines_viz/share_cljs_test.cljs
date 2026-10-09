(ns day8.re-frame2-machines-viz.share-cljs-test
  "The share-URL encode/decode pipeline: the round-trip, the URL and `:host`
  shape, reproducible bytes, the privacy allowlist and definition
  sanitisation, the closed schema on both sides, and value-free errors."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.share :as share]))

;; `encode-share-url` has no default host, so every producer names one.
(def ^:private test-host "https://x/viewer.html")

(defn- encode [chart-state]
  (share/encode-share-url chart-state {:host test-host}))

(defn- b64url
  "The encoder's base64url step, for hand-built payloads."
  [s]
  (-> (js/btoa (js/unescape (js/encodeURIComponent s)))
      (str/replace "+" "-")
      (str/replace "/" "_")
      (str/replace "=" "")))

(defn- envelope->url
  "A share-URL carrying `envelope` verbatim — a payload the public encoder
  would never emit."
  [envelope]
  (str test-host "#machine=" (b64url (transit/write (transit/writer :json) envelope))))

(defn- forge
  "A current-version share-URL whose `…/chart` is `chart` verbatim, past the
  encoder's own validation."
  [chart]
  (envelope->url {:rf.machines-viz.share/v       "2"
                  :rf.machines-viz.share/chart   chart
                  :rf.machines-viz.share/created 0}))

(defn- thrown
  "The exception `(f)` throws, or nil when it returns."
  [f]
  (try (f) nil (catch :default e e)))

(defn- frozen-now
  "Run `f` with `js/Date.now` pinned, so the envelope's `:created` stamp — its
  one non-reproducible field — cannot perturb byte-identity."
  [f]
  (let [orig (.-now js/Date)]
    (try
      (set! (.-now js/Date) (fn [] 1736000000000))
      (f)
      (finally (set! (.-now js/Date) orig)))))

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

(def compound-definition
  {:initial :authenticated
   :states  {:authenticated
             {:initial :cart
              :states  {:cart    {:initial :browsing
                                  :states  {:browsing {} :checkout {}}}
                        :account {}}}
             :anonymous {}}})

(defn- round-trip-definition
  "Encode `d` as a share-URL's definition and return the decoded one."
  [d]
  (-> (encode (assoc chart-state :definition d))
      share/decode-share-url
      :rf.machines-viz.share/chart
      :definition))

;; ---------------------------------------------------------------------------
;; Round-trip and URL shape

(deftest round-trip-preserves-chart-state
  (testing "encode → decode recovers the ChartState exactly, fabricating no
            omitted optional :snapshot or :frame-id"
    (doseq [cs [chart-state (dissoc chart-state :snapshot) (dissoc chart-state :frame-id)]]
      (is (= cs (:rf.machines-viz.share/chart (share/decode-share-url (encode cs))))
          (pr-str (keys cs)))))
  (testing "the envelope carries the encoding version and an encode-time stamp"
    (let [env (share/decode-share-url (encode chart-state))]
      (is (= "2" (:rf.machines-viz.share/v env)))
      (is (number? (:rf.machines-viz.share/created env))))))

(deftest url-shape
  (testing "the host, then a #machine= fragment in the base64url alphabet (no
            `+`, `/` or `=` padding)"
    (is (re-matches #"https://x/viewer\.html#machine=[A-Za-z0-9_-]+" (encode chart-state)))))

(deftest no-host-is-refused
  (testing "there is no default host: a missing (non-string) or blank (after
            trimming) one is refused rather than filled in with a URL that 404s"
    (doseq [opts [nil {:host "   "}]]
      (let [d (ex-data (thrown #(share/encode-share-url chart-state opts)))]
        (is (= [:rf.machines-viz.share/encode-failed :no-host]
               ((juxt :rf.error/id :reason) d))
            (pr-str opts))
        (is (string? (:message d)))))))

;; The one malformed `:host` worth refusing carries a fragment: the payload IS
;; the fragment and the viewer stops at the first `#`, so the link reads as
;; correct and is dead. Any other host passes through verbatim, because a
;; non-URL one is visibly wrong the moment it is pasted.

(deftest host-carrying-a-fragment-is-refused
  (testing "refused with :host-carries-fragment, locating the '#' (index 0
            included) without echoing the host, whose query can carry a token"
    (doseq [host ["https://acme.example.com/viewer.html?theme=dark#docs"
                  "#machine=x"]]
      (let [d (ex-data (thrown #(share/encode-share-url chart-state {:host host})))]
        (is (= {:rf.error/id    :rf.machines-viz.share/encode-failed
                :reason         :host-carries-fragment
                :recovery       :pass-a-viewer-page-url-with-no-fragment
                :fragment-index (str/index-of host "#")}
               (select-keys d [:rf.error/id :reason :recovery :fragment-index]))
            host)
        (is (not (str/includes? (pr-str d) host)) host)))))

(deftest fragment-free-hosts-are-untouched
  (testing "a host with no '#' — queried, file://, relative, or not a URL at
            all — is only trimmed, and carries the identical payload"
    (frozen-now
      (fn []
        (let [fragment (subs (encode chart-state) (count test-host))]
          (doseq [host ["https://acme.example.com/viewer.html?theme=dark"
                        "file:///C:/out/machines-viz-viewer/viewer.html"
                        "/viewer.html"
                        "banana"
                        "  https://acme.example.com/viewer.html  "]]
            (is (= (str (str/trim host) fragment)
                   (share/encode-share-url chart-state {:host host}))
                host)))))))

(deftest reproducible-encoding
  (testing "equal ChartStates built in different map and set orders encode
            byte-for-byte identically (`:created` frozen), and a set decodes
            as a set"
    (let [a {:machine-id :m/x :frame-id :app/main
             :definition {:initial :a
                          :states  {:a {:on {:go :b} :tags #{:b :a :c}}
                                    :b {:on {:back :a}}}}}
          b {:definition {:states  {:b {:on {:back :a}}
                                    :a {:tags #{:c :a :b} :on {:go :b}}}
                          :initial :a}
             :frame-id :app/main :machine-id :m/x}]
      (frozen-now #(is (= (encode a) (encode b))))
      (is (= a (:rf.machines-viz.share/chart (share/decode-share-url (encode a))))))))

;; ---------------------------------------------------------------------------
;; Privacy — only topology and the active state's NAME ride

(deftest session-data-is-dropped
  (testing "runtime :data riding on :snapshot and caller-supplied
            :source-coords never reach the payload"
    (is (= chart-state
           (:rf.machines-viz.share/chart
             (share/decode-share-url
               (encode (assoc chart-state
                              :snapshot      {:state :loading
                                              :data  {:token "secret-abc"
                                                      :form  {:password "hunter2"}}}
                              :source-coords {:file "/Users/mike/secret/x.cljs"}))))))))

;; A reg-machine-stamped definition carries `:source-coords` / `:source-code`
;; and executable `:fn`s as ordinary DATA (Spec 005 §Source-coord stamping),
;; out of `strip-meta`'s reach, and the non-topology slots (`:schemas`, the
;; event `:schema`, `:data`, `:meta`) hold arbitrary host values — a Malli
;; regex Transit cannot write. Each is dropped from every RECORD, and an inline
;; fn labelled; a topology id spelled like one of them survives.

(deftest sanitisation-keeps-only-topology
  (doseq [[label in out]
          [["an inline live fn becomes an opaque names-only label"
            {:initial :idle
             :states  {:idle {:on {:go {:target :done :action (fn [_] {})}}}
                       :done {}}}
            {:initial :idle
             :states  {:idle {:on {:go {:target :done :action :rf.machines-viz.share/fn}}}
                       :done {}}}]
           ["ids spelled like debug fields survive; the debug fields and :fns on
             the same records go"
            {:initial :source-code
             :guards  {:source-coords {:fn (fn [_] true) :source-code "(fn [_] true)"}}
             :actions {:source-code   {:fn            (fn [_] nil)
                                       :source-coords {:file "/Users/mike/proj/x.cljs" :line 9}}}
             :states  {:source-code   {:on            {:source-code   {:target        :source-coords
                                                                       :guard         :source-coords
                                                                       :action        :source-code
                                                                       :source-coords {:file "/Users/mike/proj/x.cljs"
                                                                                       :line 12}}
                                                       :source-coords :source-code}
                                       :source-coords {:file "/Users/mike/proj/x.cljs" :line 10}}
                       :source-coords {:on {:go :source-code}}}}
            {:initial :source-code
             :guards  {:source-coords {}}
             :actions {:source-code {}}
             :states  {:source-code   {:on {:source-code   {:target :source-coords
                                                            :guard  :source-coords
                                                            :action :source-code}
                                            :source-coords :source-code}}
                       :source-coords {:on {:go :source-code}}}}]
           ["consumer :rf.cofx/requires is safe topology and outlives its entry's :fn"
            {:initial :idle
             :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms] :fn (fn [_] true)}}
             :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]
                                        :fn               (fn [_] nil)}}
             :states  {:idle {:on {:go {:target :busy :guard :within-window? :action :schedule-retry}}}
                       :busy {}}}
            {:initial :idle
             :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms]}}
             :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]}}
             :states  {:idle {:on {:go {:target :busy :guard :within-window? :action :schedule-retry}}}
                       :busy {}}}]
           ["regex-bearing non-topology slots are dropped, at the root and on records"
            {:initial :a
             :schemas {:data [:map [:email [:re #".+@.+"]]]}
             :schema  [:tuple [:enum :go] [:re #"^x"]]
             :data    {:email "a@b.c" :pattern #"secret-pattern"}
             :meta    {:doc "d" :check #"meta-re"}
             :states  {:a {:on    {:go :b}
                           :meta  {:hint #"node-re"}
                           :spawn {:machine-id :child :data {:seed #"spawn-re"}}}
                       :b {}}}
            {:initial :a
             :states  {:a {:on {:go :b} :spawn {:machine-id :child}}
                       :b {}}}]]]
    (is (= out (round-trip-definition in)) label)))

(deftest valid-definitions-round-trip-unchanged
  (testing "the payload stores the AUTHORED form — the boundary desugars
            :timeout / :choice only to validate — and leaves all topology
            untouched"
    (doseq [[label definition]
            {:parallel-with-a-region-named-data
             {:type    :parallel
              :regions {:data {:initial :clean :states {:clean {} :dirty {}}}
                        :form {:initial :idle  :states {:idle {} :busy {}}}}}
             :timeout
             {:initial :idle
              :states  {:idle    {:timeout 5000 :on-timeout :expired :on {:go :done}}
                        :expired {:final? true}
                        :done    {:final? true}}}
             :choice
             {:initial :evaluating
              :states  {:evaluating {:type   :choice
                                     :choice [{:target :a :guard :ready?} {:target :b}]}
                        :a {:final? true}
                        :b {:final? true}}}
             :ids-named-like-non-topology-slots
             {:initial :data
              :states  {:data    {:on {:meta :schemas}}
                        :schemas {}}}
             :literal-and-subscription-delays
             {:initial :idle
              :states  {:idle {:after {1000 :a [:timeouts/retry] :b}}
                        :a {} :b {}}}
             :data-valued-fn-slot
             {:initial :idle
              :guards  {:ready? {:fn :named-elsewhere}}
              :states  {:idle {:on {:go {:target :busy :guard :ready?}}}
                        :busy {}}}}]
      (is (= definition (round-trip-definition definition)) (str label)))))

(deftest fn-valued-after-delay-shares-as-an-inert-label
  (testing "a fn-valued :after delay KEY (Transit writes no fn) shares as an
            inert vector label without ever being called, and two anonymous
            delays — which share a label — stay two transitions"
    (let [calls (atom 0)
          d     {:initial :idle
                 :states  {:idle {:after {(fn [_] (swap! calls inc) 100) :a
                                          (fn [_] (swap! calls inc) 200) :b}}
                           :a {} :b {}}}
          dfn   (round-trip-definition d)
          after (get-in dfn [:states :idle :after])]
      (is (every? vector? (keys after)))
      (is (= #{:a :b} (set (vals after))))
      (is (= (layout/semantic-counts d) (layout/semantic-counts dfn)))
      (is (zero? @calls)))))

(deftest unencodable-value-surfaces-as-encode-failed
  (testing "a value Transit cannot write, left in an open namespaced slot,
            throws the documented encode-failed ex-info, value-free, instead
            of a raw `Cannot write` error"
    (let [d (ex-data (thrown #(encode (assoc chart-state :definition
                                             {:initial :a
                                              :states  {:a {:my.app/pattern #"leaky-pattern"}}}))))]
      (is (= {:rf.error/id :rf.machines-viz.share/encode-failed
              :reason      :unencodable-definition
              :recovery    :remove-non-edn-values-from-the-definition}
             (select-keys d [:rf.error/id :reason :recovery])))
      (is (not (str/includes? (pr-str d) "leaky-pattern"))))))

;; ---------------------------------------------------------------------------
;; The ChartState schema is closed and validated on BOTH sides by one
;; predicate: a forged URL fails closed, and the encoder never mints a URL the
;; decoder would refuse. The definition goes through the canonical grammar
;; gate after the projectors' desugar — never a weaker private copy that would
;; bless a string `:initial`, an unvalidated region body, or a defect below
;; the root.

(deftest malformed-chart-state-rejected-at-encode-and-decode
  (doseq [cs (concat
               (for [state ["loading" [] [:auth "authing"] {} {:data "loading"} {"data" :loading}]]
                 (assoc chart-state :snapshot {:state state}))
               [(assoc chart-state :frame-id "not-a-keyword")
                (assoc chart-state :machine-id "not-a-keyword")]
               (for [definition [{:initial "idle" :states {:idle {}}}
                                 {:type :parallel :regions {:main {:initial "x" :states {:x {}}}}}
                                 {:initial :outer :states {:outer {:states {:inner {}}}}}]]
                 {:machine-id :demo :definition definition}))]
    (is (= [:rf.machines-viz.share/encode-failed :invalid-chart-state]
           ((juxt :rf.error/id :reason) (ex-data (thrown #(encode cs)))))
        (pr-str cs))
    (is (= [:rf.machines-viz.share/decode-failed :invalid-chart-state]
           ((juxt :rf.error/id :reason) (ex-data (thrown #(share/decode-share-url (forge cs))))))
        (pr-str cs))))

(deftest decoded-extra-top-level-key-rejected
  (testing "the top-level ChartState is CLOSED on decode: a forged URL adding a
            known leak, or any unreviewed future key, is refused"
    (doseq [extra [{:source-coords {:file "/Users/mike/secret/x.cljs" :line 42}}
                   {:rf.machines-viz.share/some-future-field {:anything :goes}}]]
      (is (= :invalid-chart-state
             (:reason (ex-data (thrown #(share/decode-share-url (forge (merge chart-state extra)))))))
          (pr-str extra)))))

(deftest chart-state->props-projection
  (testing "a decoded envelope projects to read-only MachineChart props: every
            :state arm the encoder accepts decodes and rides verbatim as
            :current-state (none → no highlight); :frame-id is provenance,
            not a prop"
    (doseq [state [nil :loading [:authenticated :cart :browsing]
                   {:data :dirty :form :busy} {:data :dirty :form [:edit :touched]}]]
      (let [cs (cond-> (-> chart-state (assoc :definition compound-definition) (dissoc :snapshot))
                 state (assoc :snapshot {:state state}))]
        (is (= (cond-> {:machine-id :auth/login-flow :definition compound-definition :read-only? true}
                 state (assoc :current-state state))
               (share/chart-state->props (share/decode-share-url (encode cs))))
            (pr-str state))))))

;; ---------------------------------------------------------------------------
;; EP-0015 — a thrown error discloses nothing it was given
;;
;; A forged share URL can carry arbitrary runtime values and projection cannot
;; walk ex-data after the fact, so the diagnostics are checked as a GRAMMAR, not
;; a hunt: a `:type` from a closed vocabulary plus an integer `:count`, inside a
;; bound that does not grow with the payload. A sentinel planted in every
;; position a forger reaches — value, key, keyword, symbol — must not survive,
;; even as a fragment.

(def ^:private sentinel "hunter2-swordfish-SENTINEL")

(def ^:private sentinel-fragments
  "Every 8-character window of the sentinel: a bounded prefix of attacker
  material is still a leak, and V8's `JSON.parse` message carries exactly
  such a prefix."
  (into #{} (map #(subs sentinel % (+ % 8))) (range (- (count sentinel) 7))))

(defn- discloses?
  "Does `x`, printed, reproduce any sentinel fragment? `pr-str` rather than a
  string walk, because a leaked KEYWORD or map key is not a string."
  [x]
  (let [s (pr-str x)]
    (boolean (some #(str/includes? s %) sentinel-fragments))))

(defn- content-free-summary?
  "A `:type` from the closed vocabulary `re-frame.error/diag-value-summary`
  shares, an optional non-negative integer `:count`, and nothing else."
  [s]
  (and (map? s)
       (every? #{:type :count} (keys s))
       (contains? #{:map :vector :seq :set :keyword :symbol :string :number
                    :boolean :nil :fn :scalar}
                  (:type s))
       (or (not (contains? s :count))
           (let [c (:count s)]
             (and (integer? c) (not (neg? c)))))))

(defn- exploding-object
  "A host object whose `toString` throws: a summariser running `str` over
  every key would throw ITS exception in place of the documented one."
  []
  (let [o #js {}]
    (set! (.-toString o)
          (fn [] (throw (js/Error. (str "toString exploded: " sentinel)))))
    o))

(def ^:private hostile-keys
  "Sentinel-bearing map keys of every key type transit carries, plus markup
  and control-character keys — a disclosed key set is pasted into consoles,
  log viewers and issue trackers."
  {(str "string-key-" sentinel)                    1
   (keyword sentinel)                              2
   (keyword sentinel sentinel)                     3
   (symbol sentinel)                               4
   [sentinel]                                      5
   {sentinel sentinel}                             6
   (str "<script>alert(" sentinel ")</script>")    7
   (str (js/String.fromCharCode 27) "[31m" sentinel (js/String.fromCharCode 27) "[0m")  8
   (str "CR\r\nLF-" sentinel)                      9
   (str "NUL" (js/String.fromCharCode 0) "-" sentinel)               10
   4111111111111111                                11
   true                                            12})

(def ^:private forged-payloads
  "One payload per `value-free-summary` leg a forged `#machine=` fragment can
  reach through transit."
  [["a map keyed by sentinels of every key type" hostile-keys]
   ["a keyword with no length bound"             (keyword (apply str (repeat 20 sentinel)))]
   ["a symbol"                                   (symbol sentinel)]
   ["a 4004-character string"                    (apply str (repeat 154 sentinel))]
   ["a vector of secrets"                        [sentinel sentinel]]
   ["a set of secrets"                           #{sentinel}]
   ["a list of secrets"                          (list sentinel)]
   ["a 16-digit card number"                     4111111111111111]
   ["a boolean"                                  true]
   ["nil"                                        nil]])

(deftest unknown-version-rejected
  (testing "a newer :v is refused with :unknown-version, compared as an integer
            (\"10\" is newer than \"2\"), reporting only the integer the
            comparison used — nil when :v does not parse — never the raw value"
    (doseq [[v payload-version] [["10" 10]
                                 [(str "9999-" sentinel) 9999]
                                 [(str "v" sentinel) nil]]]
      (let [d (ex-data (thrown #(share/decode-share-url
                                  (envelope->url {:rf.machines-viz.share/v     v
                                                  :rf.machines-viz.share/chart chart-state}))))]
        (is (= [:unknown-version payload-version] ((juxt :reason :payload-version) d)) v)
        (is (not (discloses? d)) v)))))

(deftest decode-error-omits-raw-chart
  (testing "an :invalid-chart-state failure reports the chart's shape, never
            the chart — here one smuggling :snapshot :data past the closed
            snapshot"
    (let [e (thrown #(share/decode-share-url
                       (envelope->url
                         {:rf.machines-viz.share/v     "1"
                          :rf.machines-viz.share/chart (assoc chart-state :snapshot
                                                              {:state :loading
                                                               :data  {:token sentinel}})})))
          d (ex-data e)]
      (is (= :invalid-chart-state (:reason d)))
      (is (content-free-summary? (:chart-summary d)))
      (is (not (discloses? [d (ex-message e)]))))))

(deftest decode-error-discloses-nothing-it-was-given
  (testing "a forged payload's :missing-envelope failure names its shape and
            nothing else, inside a fixed bound"
    (doseq [[label payload] forged-payloads]
      (let [e (thrown #(share/decode-share-url (envelope->url payload)))
            d (ex-data e)]
        (is (= :missing-envelope (:reason d)) label)
        (is (content-free-summary? (:envelope-summary d))
            (str label " — summary was " (pr-str (:envelope-summary d))))
        (is (not (discloses? [d (ex-message e)])) label)
        (is (< (count (pr-str d)) 600)
            (str label " — ex-data serialized " (count (pr-str d)) " chars"))))))

(deftest decode-error-ex-data-does-not-grow-with-the-payload
  (testing "a 2000-key forged envelope throws the same size ex-data as a 2-key
            one, give or take the DIGITS of :count"
    (let [size (fn [payload]
                 (count (pr-str (ex-data (thrown #(share/decode-share-url (envelope->url payload)))))))]
      (is (<= (- (size (into {} (map (fn [i] [(keyword (str sentinel "-" i)) i])) (range 2000)))
                 (size {:a 1 :b 2}))
              4)))))

(deftest decode-error-omits-the-caller-url
  (testing "a URL with no #machine= fragment is reported by its shape, never
            echoed — a viewer URL's query can carry a token"
    (let [e (thrown #(share/decode-share-url (str "https://x/viewer.html?session=" sentinel)))
          d (ex-data e)]
      (is (= :malformed-fragment (:reason d)))
      (is (content-free-summary? (:url-summary d)))
      (is (not (discloses? [d (ex-message e)]))))))

(deftest decode-error-omits-the-host-parse-message
  (testing "V8's JSON.parse SyntaxError embeds a prefix of its input, so
            neither decode stage republishes the host's own message"
    (doseq [[reason url] [[:malformed-payload  (str test-host "#machine="
                                                    (b64url (str sentinel "-not-transit")))]
                          [:malformed-fragment (str test-host "#machine=" sentinel "!!!")]]]
      (let [e (thrown #(share/decode-share-url url))
            d (ex-data e)]
        (is (= reason (:reason d)))
        (is (not (contains? d :cause)) "no host-message slot")
        (is (not (discloses? [d (ex-message e)])) (str reason))))))

(deftest encode-error-discloses-nothing-it-was-given
  (testing "a rejected chart-state — sentinels in its keys and in a smuggled
            :snapshot :data — is reported by its shape alone"
    (let [e (thrown #(encode (merge hostile-keys
                                    {:machine-id :auth/flow
                                     :definition idle-loading-success
                                     :snapshot   {:state "not-an-arm"
                                                  :data  {:password sentinel}}})))
          d (ex-data e)]
      (is (= :invalid-chart-state (:reason d)))
      (is (content-free-summary? (:chart-state-summary d)))
      (is (not (discloses? [d (ex-message e)])))))
  (testing "a chart-state key whose toString throws does not replace the
            documented failure with its own exception"
    (let [d (ex-data (thrown #(encode {(exploding-object) :whatever
                                       :machine-id        :auth/flow
                                       :definition        idle-loading-success
                                       :snapshot          {:state "not-an-arm"}})))]
      (is (= [:rf.machines-viz.share/encode-failed :invalid-chart-state]
             ((juxt :rf.error/id :reason) d)))
      (is (content-free-summary? (:chart-state-summary d)))
      (is (not (discloses? d))))))
