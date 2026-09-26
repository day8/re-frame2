(ns re-frame.http-managed-carriers-override-test
  "Declaring HTTP carriers the way Spec 014 §HTTP carriers prescribes — the
  public `rf/reg-fx` macro re-registering `:rf.http/managed` with a
  `:carriers` block from an application namespace — overrides the
  framework's own registration rather than colliding with it.

  The http artefact seeds `:rf.http/managed` through the fn-form `reg-fx`,
  which records no source namespace, and marks it a replaceable framework
  default (`:rf/framework-default? true`). The public macro records the
  namespace it is written in, so the default image holds two descriptors for
  `[:fx :rf.http/managed]`; the marker is what lets assembly drop the
  framework's copy once an application has registered its own, instead of
  failing every later `rf/make-frame {}` with `:rf.error/image-duplicate-id`.
  Two APPLICATION namespaces registering the id remain a duplicate.

  JVM-only: the second application namespace is made by evaluating the macro
  form with `*ns*` bound, and the redaction case drives a real request
  against an in-process server."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- start-server! [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx (reify HttpHandler (handle [_ exchange] (handler exchange))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}] (.stop ^HttpServer server 0))

(defn- respond-500-with-headers!
  "Answer every request `500 text/plain` carrying `headers` on the response."
  [headers]
  (fn [^HttpExchange ex]
    (doseq [[k v] headers]
      (-> ex .getResponseHeaders (.set ^String k ^String v)))
    (let [bytes (.getBytes "boom" "UTF-8")]
      (-> ex .getResponseHeaders (.set "Content-Type" "text/plain"))
      (.sendResponseHeaders ex 500 (long (count bytes)))
      (with-open [os (.getResponseBody ex)]
        (.write os bytes)))))

(defn- find-header
  "Case-insensitive header lookup — the JDK normalises header-name casing."
  [headers-map header-name]
  (let [lc (str/lower-case header-name)]
    (some (fn [[k v]] (when (= lc (str/lower-case (str k))) v)) headers-map)))

(defn- thrown-error-id
  "Run `thunk`; return the `:rf.error/id` of the ex-info it throws, or
  `:no-throw`."
  [thunk]
  (try (thunk) :no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- reg-carriers-in-ns!
  "Re-register `:rf.http/managed` with `carriers` through the public
  `rf/reg-fx` macro as though it were written in namespace `ns-sym`. The macro
  records `*ns*` at expansion, so evaluating the form with `*ns*` bound gives
  the registration that namespace's provenance."
  [ns-sym carriers]
  (binding [*ns* (create-ns ns-sym)]
    (eval `(rf/reg-fx :rf.http/managed
             {:carriers ~carriers}
             rf.http.managed/managed-handler))))

(def ^:private this-ns (str (ns-name *ns*)))

;; ---- 1. no re-registration ------------------------------------------------

(deftest the-framework-registration-alone-assembles
  (testing "with no application re-registration the framework's own
            `:rf.http/managed` is the one descriptor, marked a replaceable
            default, and the default image assembles"
    (is (true? (:rf/framework-default?
                 (rf/handler-meta {:source :store :kind :fx :id :rf.http/managed})))
        "the framework registration carries the replaceable-default marker")
    (let [f (rf/make-frame {})]
      (is (some? f) "rf/make-frame {} assembles")
      (is (nil? (:carriers (rf/handler-meta {:frame f :kind :fx :id :rf.http/managed})))
          "no application :carriers block is in force"))))

;; ---- 2. the Spec 014 spelling: rf/reg-fx with :carriers -------------------

(deftest the-spec-014-carriers-spelling-overrides-the-framework-registration
  (testing "an application re-registering `:rf.http/managed` with a `:carriers`
            block through the public `rf/reg-fx` macro assembles, the frame
            resolves the APPLICATION's registration, and the declared carrier
            is redacted on the trace"
    (rf/reg-fx :rf.http/managed
      {:carriers {:headers ["X-Honeycomb-Team"]}}
      rf.http.managed/managed-handler)
    (let [srv      (start-server! (respond-500-with-headers!
                                    {"X-Honeycomb-Team" "hc-secret-token"
                                     "X-Plain-Probe"    "plain-value"}))
          captured (atom [])]
      (try
        (rf/reg-event :api/fetch
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:method :get
                                 :url    (str "http://127.0.0.1:" (:port srv) "/x")}
                    :on-failure nil}]]}))
        (let [f (rf/make-frame {})]
          (is (some? f)
              "rf/make-frame {} assembles — not :rf.error/image-duplicate-id")
          (let [m (rf/handler-meta {:frame f :kind :fx :id :rf.http/managed})]
            (is (= {:headers ["X-Honeycomb-Team"]} (:carriers m))
                "the frame resolves the application's :carriers block")
            (is (= this-ns (str (:rf.provenance/ns m)))
                "…from the application's own registration, not the framework's"))
          (rf.trace.tooling/register-listener! ::capture #(swap! captured conj %))
          (rf/dispatch-sync [:api/fetch] {:frame f})
          (rf.test-support/poll-until
            (fn [] (some #(= :rf.http/http-5xx (:operation %)) @captured))
            {:timeout-ms 3000 :label "carriers-override http-5xx"})
          (let [ev      (first (filter #(= :rf.http/http-5xx (:operation %)) @captured))
                headers (get-in ev [:tags :headers])]
            (is (= "plain-value" (find-header headers "X-Plain-Probe"))
                "control: response headers reach the trace, and a name that is
                 not a carrier rides verbatim")
            (is (= :rf/redacted (find-header headers "X-Honeycomb-Team"))
                "the application-declared carrier header is redacted")
            (is (not (str/includes? (pr-str @captured) "hc-secret-token"))
                "the carrier's value appears nowhere in the captured trace")))
        (finally
          (stop-server! srv))))))

;; ---- 3. two application namespaces ----------------------------------------

(deftest two-application-registrations-still-collide
  (testing "the override is not a winner rule: two APPLICATION namespaces each
            re-registering `:rf.http/managed` are ambiguous, and default-image
            assembly still refuses to let load order decide"
    (rf/reg-fx :rf.http/managed
      {:carriers {:headers ["X-Honeycomb-Team"]}}
      rf.http.managed/managed-handler)
    (reg-carriers-in-ns! 're-frame.http-managed-carriers-override-test.second-app
                         {:headers ["X-Other-Team"]})
    (is (= :rf.error/image-duplicate-id
           (thrown-error-id #(rf/make-frame {}))))))
