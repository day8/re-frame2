(ns re-frame.http-cljs-test
  "CLJS-only coverage of the Fetch transport: CORS classification, the native
  body readers, the Fetch init and its headers, timeouts, and the external
  `:abort-signal` binding."
  (:require [cljs.test :refer-macros [are deftest is async]]
            [clojure.string :as str]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport-cljs :as rf.http.transport-cljs]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:private classify-cljs-error rf.http.transport-cljs/classify-cljs-error)
(def ^:private cljs-fetch rf.http.transport-cljs/cljs-fetch)

(defn- with-stub-location
  "Run `f` with `js/globalThis.location` stubbed to `{origin <origin>}`. The
  node lane has no `location`, so without it `cross-origin?` never compares."
  [origin f]
  (let [orig (aget js/globalThis "location")]
    (aset js/globalThis "location" #js {:origin origin})
    (try
      (f)
      (finally
        (aset js/globalThis "location" orig)))))

(deftest cross-origin-classification-under-injected-origin
  (with-stub-location "https://app.example"
    (fn []
      (are [err url out] (= out (classify-cljs-error err url))
        ;; Only a TypeError can be CORS. `:cause` is the class-name string, so
        ;; the failure map stays EDN-serializable.
        (js/Error. "connection-reset") "https://other.invalid/x"
        {:kind :rf.http/transport :message "connection-reset" :cause "Error"}

        (js/TypeError. "Failed to fetch") "https://other.invalid/x?a=1"
        {:kind :rf.http/cors :message "Failed to fetch" :url "https://other.invalid/x?a=1"}

        (js/TypeError. "Failed to fetch") "https://app.example/api/items"
        {:kind :rf.http/transport :message "Failed to fetch" :cause "TypeError"}

        (js/TypeError. "Failed to fetch") "/api/items"
        {:kind :rf.http/transport :message "Failed to fetch" :cause "TypeError"}

        ;; A protocol-relative URL carries its own host.
        (js/TypeError. "Failed to fetch") "//other.invalid/x"
        {:kind :rf.http/cors :message "Failed to fetch" :url "//other.invalid/x"}

        (js/TypeError. "Failed to fetch") "//app.example/x"
        {:kind :rf.http/transport :message "Failed to fetch" :cause "TypeError"}

        ;; data: and file: parse to origin "null"; the scheme exclusion is
        ;; case-insensitive.
        (js/TypeError. "Failed to fetch") "data:text/plain,hello"
        {:kind :rf.http/transport :message "Failed to fetch" :cause "TypeError"}

        (js/TypeError. "Failed to fetch") "FILE:///etc/hosts"
        {:kind :rf.http/transport :message "Failed to fetch" :cause "TypeError"}))))

;; ---- the Fetch transport, driven directly ----------------------------------

(defn- fake-response
  "A minimal Fetch `Response` stand-in; each body reader resolves its own value."
  [{:keys [status content-type blob-val ab-val fd-val text-val]}]
  #js {:ok          (and (>= status 200) (< status 300))
       :status      status
       :statusText  ""
       :headers     #js {:forEach (fn [cb]
                                    (when content-type (cb content-type "content-type")))}
       :text        (fn [] (js/Promise.resolve text-val))
       :blob        (fn [] (js/Promise.resolve blob-val))
       :arrayBuffer (fn [] (js/Promise.resolve ab-val))
       :formData    (fn [] (js/Promise.resolve fd-val))})

(defn- ok-json-response []
  (fake-response {:status 200 :content-type "application/json" :text-val "{}"}))

(defn- with-stub-fetch
  "Run `f` with `js/fetch` resolving `resp`; returns `f`'s Promise."
  [resp f]
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis) (fn [_url _init] (js/Promise.resolve resp)))
    (-> (f)
        (.finally (fn [] (set! (.-fetch js/globalThis) orig))))))

(defn- with-init-capturing-fetch
  "Like `with-stub-fetch`, recording the Fetch `init` into `captured-init`."
  [resp captured-init f]
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis)
          (fn [_url init]
            (reset! captured-init init)
            (js/Promise.resolve resp)))
    (-> (f)
        (.finally (fn [] (set! (.-fetch js/globalThis) orig))))))

(deftest blob-array-buffer-and-form-data-read-native-bodies
  ;; Each binary decode reads its own native reader into :body-binary, never the
  ;; lossy `.text()`.
  (async done
    (let [fetch-as (fn [decode content-type k v]
                     (with-stub-fetch (fake-response (assoc {:status 200 :content-type content-type
                                                             :text-val "lossy-utf8-text"}
                                                            k v))
                       #(cljs-fetch {:method :get :url "/x" :headers {} :decode decode
                                     :internal-controller (js/AbortController.)})))
          body     (juxt :body-binary :body-text :ok?)
          blob     (js-obj "__kind" "blob")
          ab       (js-obj "__kind" "ab")
          fd       (js-obj "__kind" "fd")]
      (-> (fetch-as :blob "image/png" :blob-val blob)
          (.then (fn [r]
                   (is (= [blob nil true] (body r)))
                   (fetch-as :array-buffer "application/octet-stream" :ab-val ab)))
          (.then (fn [r]
                   (is (= [ab nil true] (body r)))
                   (fetch-as :form-data "multipart/form-data" :fd-val fd)))
          (.then (fn [r] (is (= [fd nil true] (body r)))))
          (.catch (fn [e] (is false (str "unexpected reject: " e)) nil))
          (.then (fn [_] (done)))))))

(deftest non-2xx-binary-decode-still-reads-text
  ;; Decode never runs on a non-2xx, whose failure carries the raw body text.
  (async done
    (-> (with-stub-fetch (fake-response {:status 404 :content-type "image/png"
                                         :blob-val (js-obj "__kind" "blob")
                                         :text-val "Not Found"})
          #(cljs-fetch {:method :get :url "/missing.png" :headers {} :decode :blob
                        :internal-controller (js/AbortController.)}))
        (.then (fn [r] (is (= ["Not Found" nil false] ((juxt :body-text :body-binary :ok?) r)))))
        (.catch (fn [e] (is false (str "unexpected reject: " e)) nil))
        (.then (fn [_] (done))))))

(deftest cljs-fetch-passes-redirect-into-init
  (async done
    (let [captured-init (atom nil)]
      (-> (with-init-capturing-fetch (ok-json-response) captured-init
            #(cljs-fetch {:method :get :url "/x" :headers {} :decode :json :redirect :error
                          :internal-controller (js/AbortController.)}))
          (.then (fn [_] (is (= "error" (aget @captured-init "redirect")))))
          (.catch (fn [e] (is false (str "unexpected reject: " e)) nil))
          (.then (fn [_] (done)))))))

(deftest cljs-fetch-multi-valued-request-header-appends-each-value
  ;; One `Headers.append` per element; `Headers.get` joins repeated values.
  (async done
    (let [captured-init (atom nil)]
      (-> (with-init-capturing-fetch (ok-json-response) captured-init
            #(cljs-fetch {:method :get :url "/x" :decode :json
                          :headers {"X-Multi" ["alpha" "beta" "gamma"] "X-One" "solo"}
                          :internal-controller (js/AbortController.)}))
          (.then (fn [_]
                   (let [h (aget @captured-init "headers")]
                     (is (= ["alpha, beta, gamma" "solo"] [(.get h "X-Multi") (.get h "X-One")])))))
          (.catch (fn [e] (is false (str "unexpected reject: " e)) nil))
          (.then (fn [_] (done)))))))

;; ---- invalid request headers stay on the managed path --------------------
;;
;; `Headers.append` throws a TypeError on an invalid name or a CR/LF-bearing
;; value. The transport catches per pair, emits a redacted
;; `:rf.warning/http-header-invalid` trace, drops the pair and carries on, so
;; the throw never escapes as an fx-handler exception.

(defn- with-trace-capture
  "Record every trace event while `f`'s Promise runs, then call
  `(on-events seen-atom result)`."
  [f on-events]
  (let [seen   (atom [])
        cb-key (keyword (str "http-cljs-invalid-header-" (gensym)))]
    (rf.trace.tooling/register-listener! cb-key (fn [ev] (swap! seen conj ev)))
    (-> (f)
        (.then (fn [result] (on-events seen result)))
        (.finally (fn [] (rf.trace.tooling/unregister-listener! cb-key))))))

(defn- header-invalid-warning [seen]
  (first (filter #(= :rf.warning/http-header-invalid (:operation %)) @seen)))

(deftest cljs-fetch-invalid-header-name-surfaces-managed-warning-not-escape
  (async done
    (let [captured-init (atom nil)]
      (-> (with-trace-capture
            #(with-init-capturing-fetch (ok-json-response) captured-init
               (fn []
                 (cljs-fetch {:method  :get
                              :url     "https://example.invalid/v1?api_key=SECRET&page=2"
                              :headers {"" "anything" "X-Good" "kept"}
                              :decode  :json
                              :internal-controller (js/AbortController.)})))
            (fn [seen _result]
              (let [w    (header-invalid-warning seen)
                    tags (:tags w)]
                ;; The trace URL is redacted like every other http trace.
                (is (= [:warning true "" "https://example.invalid/v1?api_key=:rf/redacted&page=2" true "kept"]
                       [(:op-type w) (:sensitive? w) (:header tags) (:url tags) (some? (:cause tags))
                        (.get (aget @captured-init "headers") "X-Good")])))))
          ;; Upstream of the single trailing `done`, so a foreign throw after it
          ;; cannot be claimed as this row's.
          (.catch (fn [e] (is false (str "the invalid header escaped the managed path: " e)) nil))
          (.then (fn [_] (done)))))))

(deftest cljs-fetch-invalid-header-warning-carries-no-part-of-the-rejected-value
  ;; The TypeError message echoes the rejected value, and header values carry
  ;; credentials, so `:cause` names only the header.
  (async done
    (let [captured-init (atom nil)]
      (-> (with-trace-capture
            #(with-init-capturing-fetch (ok-json-response) captured-init
               (fn []
                 (cljs-fetch {:method  :get
                              :url     "https://example.invalid/v1"
                              :headers {"Authorization" "tok\nSECRETVALUE"}
                              :decode  :json
                              :internal-controller (js/AbortController.)})))
            (fn [seen _result]
              (let [tags (:tags (header-invalid-warning seen))]
                (is (= ["Authorization" true false nil]
                       [(:header tags)
                        (str/includes? (str (:cause tags)) "Authorization")
                        (str/includes? (pr-str @seen) "SECRETVALUE")
                        (.get (aget @captured-init "headers") "Authorization")])))))
          (.catch (fn [e] (is false (str "unexpected reject: " e)) nil))
          (.then (fn [_] (done)))))))

;; ---- timeouts ----------------------------------------------------------------

(defn- with-deferred-fetch
  "Like `with-stub-fetch`, but `js/fetch` resolves on the next macrotask."
  [resp f]
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis)
          (fn [_url _init]
            (js/Promise. (fn [resolve _reject]
                           (js/setTimeout (fn [] (resolve resp)) 0)))))
    (-> (f)
        (.finally (fn [] (set! (.-fetch js/globalThis) orig))))))

(deftest zero-timeout-ms-does-not-arm-near-instant-abort
  ;; `:timeout-ms 0` is the opt-out. 0 is truthy, so a bare truthiness guard
  ;; would arm a 0 ms timer that beats this macrotask-deferred fetch.
  (async done
    (-> (with-deferred-fetch (fake-response {:status 200 :content-type "application/json"
                                             :text-val "{\"ok\":true}"})
          #(cljs-fetch {:method :get :url "/slow" :headers {} :decode :json :timeout-ms 0
                        :internal-controller (js/AbortController.)}))
        (.then (fn [r] (is (= ["{\"ok\":true}" true] ((juxt :body-text :ok?) r)))))
        (.catch (fn [e] (is false (str ":timeout-ms 0 armed an abort and rejected: " e)) nil))
        (.then (fn [_] (done))))))

(defn- fake-response-stalled-body
  "A Fetch `Response` whose headers resolve at once but whose body reader never
  settles: the slow-loris the per-attempt timeout must bound. `read-fired` goes
  true when the reader is invoked."
  [{:keys [status content-type read-fired]}]
  (let [stall (fn [] (reset! read-fired true) (js/Promise. (fn [_ _])))]
    #js {:ok          (and (>= status 200) (< status 300))
         :status      status
         :statusText  ""
         :headers     #js {:forEach (fn [cb]
                                      (when content-type (cb content-type "content-type")))}
         :text        stall
         :blob        stall
         :arrayBuffer stall
         :formData    stall}))

(deftest cljs-timeout-bounds-stalled-body-read
  ;; The timer races the whole fetch -> body-read chain, not just the headers.
  (async done
    (let [read-fired (atom false)]
      (-> (with-stub-fetch (fake-response-stalled-body {:status 200 :content-type "application/json"
                                                        :read-fired read-fired})
            #(cljs-fetch {:method :get :url "/slow-body" :headers {} :decode :json :timeout-ms 40
                          :internal-controller (js/AbortController.)}))
          ;; The rejection is the success path, so both arms share one `.then`:
          ;; exactly one runs, and a foreign throw after `done` reaches neither.
          (.then (fn [result]
                   (is false (str "a stalled body read resolved: " (pr-str result))))
                 (fn [err]
                   (let [data (ex-data err)]
                     (is (= [true :rf.error/http-timeout true 40]
                            [@read-fired (:rf.error/id data) (:rf.http/timeout? data) (:limit-ms data)]))
                     ;; Measured, not a copy of :limit-ms; `setTimeout` can fire a
                     ;; hair early, hence the 10 ms band.
                     (is (>= (:elapsed-ms data) 30)))))
          (.then (fn [_] (done)))))))

;; ---- the full :rf.http/managed pipeline ---------------------------------------
;;
;; Setup is inline rather than a fixture: a wrap-style fixture would tear down
;; before an async body completes. The managed fxs need a carried frame, so
;; every dispatch names one.

(defn- reset-runtime! []
  (rf/init! rf.adapter.reagent/adapter)
  (rf.frame/ensure-default-frame!)
  (rf.http.managed/clear-all-in-flight!))

(defn- record-replies!
  "Register `:reply/recorder`; returns the atom of replies it receives."
  []
  (let [replies (atom [])]
    (rf/reg-event :reply/recorder (fn [_ [_ p]] (swap! replies conj p) {}))
    replies))

(defn- issue!
  "Dispatch one `:rf.http/managed` request with `args`, replying to `:reply/recorder`."
  ([args] (issue! :rf/default args))
  ([frame args]
   (rf/reg-event ::issue
     (fn [_ _]
       {:fx [[:rf.http/managed (merge {:on-success [:reply/recorder]
                                       :on-failure [:reply/recorder]}
                                      args)]]}))
   (rf/dispatch-sync [::issue] {:frame frame})))

(defn- in-flight-empty? []
  (empty? (rf.http.registry/in-flight-snapshot)))

(def ^:private cancel-facts
  (juxt :status #(get-in % [:error :kind]) #(get-in % [:error :reason])))

(defn- next-macrotask
  "Resolves on the next macrotask, after every queued finalise microtask, so a
  reply that should have been suppressed has had its chance to land."
  []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))

(defn- sleep [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(deftest cljs-timeout-stalled-body-finalises-as-timeout-and-clears-registry
  (async done
    (reset-runtime!)
    (let [replies    (record-replies!)
          read-fired (atom false)
          resp       (fake-response-stalled-body {:status 200 :content-type "application/json"
                                                  :read-fired read-fired})
          orig       (.-fetch js/globalThis)]
      (set! (.-fetch js/globalThis) (fn [_url _init] (js/Promise.resolve resp)))
      (issue! {:request {:url "/slow-body"} :decode :json :timeout-ms 40 :request-id :loris})
      (-> (rf.test-support/poll-until
            #(seq @replies)
            {:timeout-ms 2000 :label "cljs stalled-body timeout reply"})
          (.then (fn [_]
                   (let [reply (first @replies)]
                     (is (= [true :error :rf.http/timeout true]
                            [@read-fired (:status reply) (get-in reply [:error :kind]) (in-flight-empty?)])))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_]
                   (set! (.-fetch js/globalThis) orig)
                   (done)))))))

(defn- with-counting-500-fetch
  "Stub `js/fetch` to resolve a 500, counting calls. Returns a restore fn."
  [count-atom]
  (let [orig (.-fetch js/globalThis)
        resp (fake-response {:status 500 :content-type "application/json" :text-val "boom"})]
    (set! (.-fetch js/globalThis)
          (fn [_url _init]
            (swap! count-atom inc)
            (js/Promise.resolve resp)))
    (fn [] (set! (.-fetch js/globalThis) orig))))

(defn- backoff-sleeping?
  "Attempt 1 has failed and `request-id` sleeps in its backoff: a backoff handle
  lacks the live-fetch handle's `:finalised?` cell."
  [fetch-count request-id]
  (let [handle (get (rf.http.registry/in-flight-snapshot) request-id)]
    (and (= 1 @fetch-count) (some? handle) (nil? (:finalised? handle)))))

(def ^:private retry-5xx-every-80ms
  {:on #{:rf.http/http-5xx} :max-attempts 5 :backoff {:base-ms 80 :factor 1 :max-ms 80}})

(deftest cljs-destroy-frame-cancels-backoff-and-suppresses-reply
  ;; Destroying the owning frame clears the `js/setTimeout` backoff (no second
  ;; fetch) and suppresses the reply, where a managed-abort would deliver one.
  (async done
    (reset-runtime!)
    (rf/make-frame {:id :frame/req :doc "owns the in-flight request"})
    (let [fetch-count (atom 0)
          replies     (record-replies!)
          restore     (with-counting-500-fetch fetch-count)]
      (issue! :frame/req {:request {:url "/always-500"} :decode :json
                          :retry retry-5xx-every-80ms :request-id :destroy/race})
      (-> (rf.test-support/poll-until
            #(backoff-sleeping? fetch-count :destroy/race)
            {:timeout-ms 2000 :label "cljs backoff sleeping (destroy)"})
          (.then (fn [_]
                   (rf/destroy-frame! :frame/req)
                   (is (in-flight-empty?))
                   (sleep 230)))
          (.then (fn [_] (is (= [1 []] [@fetch-count @replies]))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

;; ---- body-prep failures ------------------------------------------------------

(defn- with-failing-fetch
  "Stub `js/fetch` to reject: these bodies throw before any network call.
  Returns a restore fn."
  []
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis)
          (fn [_url _init]
            (js/Promise.reject (js/Error. "fetch must not be reached"))))
    (fn [] (set! (.-fetch js/globalThis) orig))))

(deftest cljs-unencodable-body-delivers-managed-transport-failure
  ;; A circular body makes `JSON.stringify` throw in the prep phase.
  (async done
    (reset-runtime!)
    (let [replies  (record-replies!)
          restore  (with-failing-fetch)
          circular (let [o (js-obj)] (aset o "self" o) o)]
      (issue! {:request {:url "/x" :method :post :request-content-type :json :body circular}
               :request-id :prep-encode})
      (-> (rf.test-support/poll-until
            #(seq @replies)
            {:timeout-ms 2000 :label "cljs encode prep failure reply"})
          (.then (fn [_]
                   (let [reply (first @replies)]
                     (is (= [1 :rf.http/transport :request-prep true]
                            [(count @replies) (get-in reply [:error :kind]) (get-in reply [:error :stage])
                             (in-flight-empty?)])))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

(deftest cljs-throwing-body-thunk-retries-when-configured
  (async done
    (reset-runtime!)
    (let [replies     (record-replies!)
          invocations (atom 0)
          restore     (with-failing-fetch)]
      (issue! {:request    {:url "/x" :method :post
                            :body (fn [] (swap! invocations inc) (throw (js/Error. "boom-retry")))}
               :retry      {:on #{:rf.http/transport} :max-attempts 3
                            :backoff {:base-ms 1 :factor 1 :max-ms 1}}
               :request-id :prep-retry})
      (-> (rf.test-support/poll-until
            #(seq @replies)
            {:timeout-ms 4000 :label "cljs prep-failure retry exhaustion reply"})
          (.then (fn [_]
                   (let [reply (first @replies)]
                     ;; The thunk runs once per attempt; one final reply.
                     (is (= [3 1 :error :rf.http/transport :request-prep true]
                            [@invocations (count @replies) (:status reply) (get-in reply [:error :kind])
                             (get-in reply [:error :stage]) (in-flight-empty?)])))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

;; ---- the external :abort-signal ----------------------------------------------
;;
;; The signal routes to whichever handle owns the current phase (live fetch or
;; sleeping backoff) through its `:abort-fn :user`, sharing the once-only
;; precedence cells with managed-abort, supersede and actor-destroy, and its
;; listener detaches on ownership transfer and on every terminal path.

(defn- fake-abort-signal
  "An AbortSignal stand-in that records its live 'abort' listeners."
  []
  (let [listeners (atom [])
        sig       (js-obj)]
    (aset sig "aborted" false)
    (aset sig "addEventListener" (fn [_type f] (swap! listeners conj f)))
    (aset sig "removeEventListener"
          (fn [_type f] (swap! listeners (fn [ls] (vec (remove #(identical? % f) ls))))))
    {:signal         sig
     :listener-count (fn [] (count @listeners))
     :fire!          (fn []
                       (aset sig "aborted" true)
                       (doseq [f @listeners] (f #js {})))}))

(deftest external-abort-rebind-detaches-prior-phase-listener
  ;; A retry hands ownership from the live fetch to the backoff: exactly one
  ;; listener stays attached, and the signal reaches the current phase.
  (let [{:keys [signal listener-count fire!]} (fake-abort-signal)
        binding (rf.http.transport-cljs/make-external-abort signal)
        fired   (atom [])]
    (rf.http.transport-cljs/bind-external-abort! binding #(swap! fired conj :live-fetch))
    (rf.http.transport-cljs/bind-external-abort! binding #(swap! fired conj :backoff))
    (is (= 1 (listener-count)))
    (fire!)
    (is (= [:backoff] @fired))))

(defn- with-controlled-body-fetch
  "Stub `js/fetch` with a 200 whose `.text()` the test settles through
  `:resolve-body!`. `read-fired` goes true once the framework is reading the
  body, mid-finalisation. Returns `{:restore :resolve-body!}`."
  [read-fired]
  (let [orig         (.-fetch js/globalThis)
        body-resolve (atom nil)
        resp #js {:ok         true
                  :status     200
                  :statusText ""
                  :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
                  :text       (fn []
                                (reset! read-fired true)
                                (js/Promise. (fn [res _] (reset! body-resolve res))))}]
    (set! (.-fetch js/globalThis) (fn [_url _init] (js/Promise.resolve resp)))
    {:restore       (fn [] (set! (.-fetch js/globalThis) orig))
     :resolve-body! (fn [txt] (@body-resolve txt))}))

(deftest cljs-external-abort-after-settle-before-finalise-cancels-once
  ;; The signal fires after the body promise fulfils but before finalisation:
  ;; abort precedence turns the settled success into one :user cancel.
  (async done
    (reset-runtime!)
    (let [replies    (record-replies!)
          read-fired (atom false)
          {:keys [restore resolve-body!]} (with-controlled-body-fetch read-fired)
          controller (js/AbortController.)]
      (issue! {:request {:url "/x"} :decode :json :abort-signal (.-signal controller)
               :request-id :ext})
      (-> (rf.test-support/poll-until
            #(when @read-fired true)
            {:timeout-ms 2000 :label "cljs body reader reached"})
          (.then (fn [_]
                   (resolve-body! "{\"ok\":true}")
                   (.abort controller)
                   (rf.test-support/poll-until
                     #(seq @replies)
                     {:timeout-ms 2000 :label "cljs external-abort cancel reply"})))
          (.then (fn [_] (next-macrotask)))
          (.then (fn [_]
                   (is (= [[[:cancelled :rf.http/aborted :user]] true]
                          [(mapv cancel-facts @replies) (in-flight-empty?)]))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

(deftest cljs-external-abort-during-backoff-cancels-retry-and-does-not-reinvoke-thunk
  (async done
    (reset-runtime!)
    (let [fetch-count (atom 0)
          thunk-calls (atom 0)
          replies     (record-replies!)
          restore     (with-counting-500-fetch fetch-count)
          controller  (js/AbortController.)]
      (issue! {:request      {:url "/always-500" :method :post
                              :body (fn [] (swap! thunk-calls inc) "payload")}
               :decode       :json
               :retry        retry-5xx-every-80ms
               :abort-signal (.-signal controller)
               :request-id   :race})
      (-> (rf.test-support/poll-until
            #(backoff-sleeping? fetch-count :race)
            {:timeout-ms 2000 :label "cljs backoff sleeping (external)"})
          (.then (fn [_]
                   (.abort controller)
                   (is (in-flight-empty?) "the cancel clears the registry synchronously")
                   (rf.test-support/poll-until
                     #(seq @replies)
                     {:timeout-ms 2000 :label "cljs external abort reply"})))
          ;; Past the backoff deadline: no attempt 2, and the thunk is not re-run.
          (.then (fn [_] (sleep 200)))
          (.then (fn [_]
                   (is (= [1 1 [[:cancelled :rf.http/aborted :user]]]
                          [@fetch-count @thunk-calls (mapv cancel-facts @replies)]))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

(deftest cljs-already-aborted-signal-short-circuits-attempt-setup
  (async done
    (reset-runtime!)
    (let [replies     (record-replies!)
          thunk-calls (atom 0)
          controller  (js/AbortController.)
          restore     (with-failing-fetch)]
      (.abort controller)
      (issue! {:request      {:url "/x" :method :post
                              :body (fn [] (swap! thunk-calls inc) "payload")}
               :decode       :json
               :abort-signal (.-signal controller)
               :request-id   :pre})
      (-> (rf.test-support/poll-until
            #(seq @replies)
            {:timeout-ms 2000 :label "cljs pre-aborted reply"})
          (.then (fn [_] (next-macrotask)))
          (.then (fn [_]
                   (is (= [[[:cancelled :rf.http/aborted :user]] 0 true]
                          [(mapv cancel-facts @replies) @thunk-calls (in-flight-empty?)]))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

(deftest cljs-external-abort-racing-managed-abort-single-outcome
  ;; Both sources route through the one handle, so its once-only CAS yields
  ;; exactly one cancel.
  (async done
    (reset-runtime!)
    (let [replies    (record-replies!)
          read-fired (atom false)
          {:keys [restore]} (with-controlled-body-fetch read-fired)
          controller (js/AbortController.)]
      (rf/reg-event :do/managed-abort (fn [_ _] {:fx [[:rf.http/managed-abort :both]]}))
      (issue! {:request {:url "/x"} :decode :json :abort-signal (.-signal controller)
               :request-id :both})
      (-> (rf.test-support/poll-until
            #(when @read-fired true)
            {:timeout-ms 2000 :label "cljs both-sources in flight"})
          (.then (fn [_]
                   (.abort controller)
                   (rf/dispatch-sync [:do/managed-abort] {:frame :rf/default})
                   (rf.test-support/poll-until
                     #(seq @replies)
                     {:timeout-ms 2000 :label "cljs single cancel reply"})))
          (.then (fn [_] (next-macrotask)))
          (.then (fn [_]
                   (is (= [[[:cancelled :rf.http/aborted :user]] true]
                          [(mapv cancel-facts @replies) (in-flight-empty?)]))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

(deftest cljs-external-abort-listener-detached-on-natural-success-and-failure
  ;; Two requests share ONE signal; neither terminal path leaves a listener.
  (async done
    (reset-runtime!)
    (let [{:keys [signal listener-count]} (fake-abort-signal)
          replies (record-replies!)
          orig    (.-fetch js/globalThis)
          issue-answered-with!
          (fn [status text]
            (set! (.-fetch js/globalThis)
                  (fn [_url _init]
                    (js/Promise.resolve (fake-response {:status status :content-type "application/json"
                                                        :text-val text}))))
            (issue! {:request {:url "/x"} :decode :json :abort-signal signal :request-id :shared}))]
      (issue-answered-with! 200 "{\"ok\":true}")
      (-> (rf.test-support/poll-until
            #(seq @replies)
            {:timeout-ms 2000 :label "cljs shared-signal success reply"})
          (.then (fn [_]
                   (is (= [:ok 0] [(:status (first @replies)) (listener-count)]))
                   (reset! replies [])
                   (issue-answered-with! 500 "boom")
                   (rf.test-support/poll-until
                     #(seq @replies)
                     {:timeout-ms 2000 :label "cljs shared-signal failure reply"})))
          (.then (fn [_]
                   (let [reply (first @replies)]
                     (is (= [:error :rf.http/http-5xx 0]
                            [(:status reply) (get-in reply [:error :kind]) (listener-count)])))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_]
                   (set! (.-fetch js/globalThis) orig)
                   (done)))))))

(deftest cljs-success-reply-carries-response-meta
  ;; The wire status, status text and normalized (lower-cased) headers ride
  ;; [:meta ...] beside the decoded :value.
  (async done
    (reset-runtime!)
    (let [replies (record-replies!)
          orig    (.-fetch js/globalThis)
          resp    #js {:ok         true
                       :status     200
                       :statusText "OK"
                       :headers    #js {:forEach (fn [cb]
                                                   (cb "application/json" "content-type")
                                                   (cb "37" "x-ratelimit-remaining"))}
                       :text       (fn [] (js/Promise.resolve "{\"title\":\"hello\"}"))}]
      (set! (.-fetch js/globalThis) (fn [_url _init] (js/Promise.resolve resp)))
      (issue! {:request {:url "/meta"} :decode :json})
      (-> (rf.test-support/poll-until
            #(seq @replies)
            {:timeout-ms 2000 :label "cljs success meta reply"})
          (.then (fn [_]
                   (is (= {:status :ok
                           :value  {:title "hello"}
                           :meta   {:status      200
                                    :status-text "OK"
                                    :headers     {"content-type"          "application/json"
                                                  "x-ratelimit-remaining" "37"}}}
                          (-> (first @replies)
                              (select-keys [:status :value :meta])
                              (update :meta select-keys [:status :status-text :headers])
                              (update-in [:meta :headers] select-keys ["content-type" "x-ratelimit-remaining"]))))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_]
                   (set! (.-fetch js/globalThis) orig)
                   (done)))))))
