(ns re-frame.ssr.ring.renderer-seam-test
  "rf2-8arzr.1 — the render-body seam (Spec 011 §HTTP response contract;
  slice A of the ssr-node crossing, rf2-8arzr S1 / S2).

  `ssr-handler` takes ONE construction opt, `:renderer` — a plain fn
  `(fn [{:keys [frame-id request opts]}] -> {:body-html :render-hash})` —
  which `build-full-response*` calls where the JVM-local render used to run
  inline: inside the request frame's scope, after the boot-event drain and
  the blocking-resource settle, before head resolution and the payload
  build. The renderer returns body markup and nothing else; the JVM keeps
  the request frame, head, `__rf_payload`, shell, status, headers, cookies,
  error projection and teardown (the ownership line, S2).

  The default, `pipeline/local-renderer`, is the pre-seam body extracted
  verbatim, and its floor is the EXISTING suite: the Jetty end-to-end tests
  pin bytes, so the default path stays byte-identical with no fixture change.
  These rows pin the seam itself."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.test-support :as ts]))

(use-fixtures :each ts/reset-runtime)

;; ---- extraction helpers ---------------------------------------------------

(defn- payload-edn-of
  "The `__rf_payload` script body of a rendered document string."
  [body]
  (second (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)))

(defn- payload-render-hash
  "The payload's `:rf/render-hash`, or nil when the key is absent. Matches
  the `#:rf{…}` namespace-map shorthand `pr-str` emits too."
  [body]
  (when-let [edn (payload-edn-of body)]
    (second (re-find #":(?:rf/)?render-hash \"([0-9a-f]{8})\"" edn))))

(defn- payload-head-hash [body]
  (when-let [edn (payload-edn-of body)]
    (second (re-find #":(?:rf/)?head-hash \"([0-9a-f]{8})\"" edn))))

(defn- wire-render-hash [body]
  (second (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\"" body)))

(defn- content-type-of [headers]
  (or (get headers "content-type") (get headers "Content-Type")))

(defn- drain-stream
  "Read a streaming Ring body (an InputStream) to a String."
  [body]
  (if (string? body)
    body
    (with-open [in body]
      (slurp in))))

(def ^:private request
  {:uri "/seam" :request-method :get :headers {"x-seam-probe" "yes"}})

;; ---- the app under test ---------------------------------------------------

(defn- register-app! []
  (rf/reg-event :rf.test.seam/init
    {:platforms #{:server}}
    (fn [_ _] {:db {:heading "Seam"}}))
  (rf/reg-sub :seam/heading (fn [db _] (:heading db)))
  (rf/reg-view* :seam/root
    (fn []
      (let [h (rf/subscribe-once [:seam/heading])]
        [:main.page [:h1 h] [:p "jvm body"]]))))

(def ^:private base-opts
  {:initial-events [[:rf.test.seam/init]]
   :payload        :rf.ssr.payload/whole-app-db})

(def ^:private fixed-body
  "<main id=\"native\"><h1>Rendered elsewhere</h1></main>")

(defn- fixed-renderer
  "A renderer standing in for a non-local one: a fixed body, no hash."
  [_]
  {:body-html fixed-body :render-hash nil})

;; ===========================================================================
;; Acceptance 2 — a custom renderer's body inside a JVM-built document
;; ===========================================================================

(deftest a-custom-renderer-body-lands-verbatim-in-a-jvm-built-document
  (testing "rf2-8arzr.1 Acceptance 2: a :renderer returning a fixed body and
            a nil hash — the body is inserted verbatim; no data-rf-render-hash
            marker; no payload :rf/render-hash; head, __rf_payload, shell,
            status and headers are JVM-built; :root-view omitted without error"
    (register-app!)
    (let [handler (ssr-ring/ssr-handler
                    ;; No :root-view: with a custom renderer nothing reads it.
                    (assoc base-opts
                           :emit-hash? true
                           :renderer   fixed-renderer))
          {:keys [status headers body]} (handler request)]
      (is (= 200 status) "status is the JVM's — the seam never touches it")
      (is (str/includes? (str (content-type-of headers)) "text/html")
          "headers are the JVM's")
      (is (str/includes? body fixed-body) "the renderer's body, verbatim")
      (is (not (str/includes? body "jvm body"))
          "no JVM :root-view rendered — the seam is the only body source")
      (is (str/includes? body "<!DOCTYPE html>") "shell: a JVM-built document")
      (is (str/includes? body "<div id=\"app\"")
          "shell: the #app root wraps the body")
      (is (some? (payload-edn-of body)) "__rf_payload: JVM-built")
      (is (str/includes? (payload-edn-of body) "\"Seam\"")
          "…from the post-drain app-db (the boot event ran on the JVM)")
      (is (some? (payload-head-hash body))
          "head: JVM-resolved, on its own reconstructible channel")
      (is (str/includes? body "data-rf-head-hash") "…and its wire marker rides")
      (is (nil? (wire-render-hash body))
          "a nil hash under :emit-hash? true re-stamps NOTHING (rf2-atmvj:
           nothing is 'computed yourself' at the seam)")
      (is (not (str/includes? body "render-hash"))
          "a nil hash OMITS the payload's :rf/render-hash key (rf2-q1b96)"))))

(deftest a-custom-renderer-rides-the-wire-through-jetty
  (testing "rf2-8arzr.1 Acceptance 2 on the wire: status, Content-Type and
            the whole document are JVM-built around the renderer's body"
    (register-app!)
    (let [handler (ssr-ring/ssr-handler
                    (assoc base-opts :renderer fixed-renderer))
          client  (ts/new-http-client)]
      (ts/with-jetty [port handler]
        (let [{:keys [status headers body]}
              (ts/http-get client port "/seam" 10 :with-headers? true)
              ct (first (content-type-of headers))]
          (is (= 200 status))
          (is (str/includes? (str ct) "text/html"))
          (is (str/includes? body fixed-body))
          (is (str/includes? body "<!DOCTYPE html>"))
          (is (some? (payload-edn-of body)))
          (is (nil? (wire-render-hash body)))
          (is (not (str/includes? body "render-hash"))))))))

;; ===========================================================================
;; :root-view is required iff the renderer is the default
;; ===========================================================================

(deftest root-view-is-required-exactly-when-renderer-is-absent
  (testing "rf2-8arzr S1: with a custom :renderer, :root-view is optional and
            ignored — a supplied one is simply never read"
    (register-app!)
    (let [without ((ssr-ring/ssr-handler
                     (assoc base-opts :renderer fixed-renderer))
                   request)
          with    ((ssr-ring/ssr-handler
                     (assoc base-opts
                            :root-view [(rf/view :seam/root)]
                            :renderer  fixed-renderer))
                   request)]
      (is (= 200 (:status without)) "constructs and serves with no :root-view")
      (is (= (:body without) (:body with))
          "a supplied :root-view changes nothing under a custom renderer")
      (is (not (str/includes? (:body with) "jvm body")))))
  (testing "…and without a :renderer the requirement is unchanged: omitting
            :root-view still fails closed at construction"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-ring-missing-root-view"
          (ssr-ring/ssr-handler base-opts))))
  (testing "…an explicit-nil :renderer is absent, not custom: the default
            renderer will read :root-view, so it is still required"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/ssr-ring-missing-root-view"
          (ssr-ring/ssr-handler (assoc base-opts :renderer nil))))))

;; ===========================================================================
;; S1 — what the renderer is handed, and where it runs
;; ===========================================================================

(deftest the-renderer-sees-the-live-post-drain-frame-the-request-and-the-opts
  (testing "rf2-8arzr S1: the input is exactly {:frame-id :request :opts} —
            the live post-drain frame by id, the Ring request, the handler
            opts — and the call sits INSIDE the request frame's scope, so a
            frame-relative read resolves without naming the frame"
    (register-app!)
    (let [seen    (atom nil)
          handler (ssr-ring/ssr-handler
                    (assoc base-opts
                           :root-view [(rf/view :seam/root)]
                           :renderer
                           (fn [{:keys [frame-id request opts] :as in}]
                             (reset! seen
                                     {:keys    (set (keys in))
                                      :app-db  (rf/app-db-value frame-id)
                                      :scoped  (rf/subscribe-once [:seam/heading])
                                      :request request
                                      :opts    opts})
                             {:body-html "<p>seen</p>" :render-hash nil})))]
      (handler request)
      (is (= #{:frame-id :request :opts} (:keys @seen))
          "exactly the three S1 keys")
      (is (= {:heading "Seam"} (:app-db @seen))
          "post-drain: the boot event has already run against this frame")
      (is (= "Seam" (:scoped @seen))
          "inside rf/with-frame: an unqualified subscribe-once reads the
           request frame")
      (is (= request (:request @seen))
          "the Ring request, as the host received it")
      (is (= [[:rf.test.seam/init]] (:initial-events (:opts @seen)))
          "the handler's own opts…")
      (is (true? (:emit-hash? (:opts @seen)))
          "…with the construction-time defaults merged in"))))

;; ===========================================================================
;; The default — local-renderer IS the pre-seam body
;; ===========================================================================

(deftest local-renderer-is-the-default-and-naming-it-changes-nothing
  (testing "rf2-8arzr.1 Acceptance 1 at the seam: omitting :renderer and
            passing pipeline/local-renderer explicitly produce byte-identical
            documents — and on the hashed (resolving-root) path the wire
            marker and the payload hash still agree"
    (register-app!)
    (let [opts     (assoc base-opts :root-view (fn [] ((rf/view :seam/root))))
          implicit ((ssr-ring/ssr-handler opts) request)
          explicit ((ssr-ring/ssr-handler
                      (assoc opts :renderer pipeline/local-renderer))
                    request)]
      (is (= (:status implicit) (:status explicit)))
      (is (= (:headers implicit) (:headers explicit)))
      (is (= (:body implicit) (:body explicit)) "byte-identical")
      (is (str/includes? (:body implicit) "jvm body")
          "and it is the JVM :root-view that rendered")
      (is (some? (wire-render-hash (:body implicit)))
          "the resolving root keeps its hash channel")
      (is (= (wire-render-hash (:body implicit))
             (payload-render-hash (:body implicit)))
          "wire marker == payload key (one canonical hash)"))))

;; ===========================================================================
;; S2 — the hash is the renderer's; the body bytes are never rewritten
;; ===========================================================================

(deftest a-custom-render-hash-feeds-the-payload-and-the-body-is-never-rewritten
  (testing "rf2-8arzr S1: a non-nil :render-hash becomes the payload's
            :rf/render-hash; the wire marker is the renderer's own to stamp"
    (register-app!)
    (let [stamped "<div data-rf-render-hash=\"0badf00d\">stamped</div>"
          handler (ssr-ring/ssr-handler
                    (assoc base-opts
                           :root-view [(rf/view :seam/root)]
                           :renderer  (fn [_] {:body-html   stamped
                                               :render-hash "0badf00d"})))
          body    (:body (handler request))]
      (is (= "0badf00d" (payload-render-hash body)) "payload: the seam's hash")
      (is (= "0badf00d" (wire-render-hash body)) "wire: the renderer's marker")
      (is (str/includes? body stamped) "body: verbatim")))
  (testing "…and a hash returned WITHOUT a marker in the body is not stamped
            by the pipeline: the payload carries it, the wire does not"
    (register-app!)
    (let [handler (ssr-ring/ssr-handler
                    (assoc base-opts
                           :root-view [(rf/view :seam/root)]
                           :renderer  (fn [_] {:body-html   "<div>bare</div>"
                                               :render-hash "0badf00d"})))
          body    (:body (handler request))]
      (is (= "0badf00d" (payload-render-hash body)))
      (is (nil? (wire-render-hash body))
          "the pipeline never rewrites body bytes — marker included")
      (is (str/includes? body "<div>bare</div>")))))

;; ===========================================================================
;; Acceptance 3 — stream-handler refuses :renderer at construction
;; ===========================================================================

(deftest stream-handler-refuses-renderer-at-construction
  (testing "rf2-8arzr.1 Acceptance 3: a non-nil :renderer is REJECTED when
            stream-handler is constructed — the :html-shell precedent — with
            ex-data naming the opt, the value and a recovery"
    (register-app!)
    (let [ex   (is (thrown? clojure.lang.ExceptionInfo
                     (ssr-ring/stream-handler
                       (assoc base-opts
                              :root-view [(rf/view :seam/root)]
                              :renderer  fixed-renderer))))
          data (ex-data ex)]
      (is (= :rf.error/ssr-streaming-unsupported-opt (:rf.error/id data))
          "the structured id is the existing unsupported-opt refusal — no
           new error id")
      (is (= :renderer (:opt-key data)) "ex-data names the offending opt")
      (is (= fixed-renderer (:got data)) "ex-data carries the rejected value")
      (is (keyword? (:recovery data)) "ex-data carries a recovery")
      (is (str/includes? (str (:reason data)) ":renderer")
          "the reason names :renderer")
      (is (str/includes? (str (:reason data)) "ssr-handler")
          "the reason points the caller at the non-streaming handler")))
  (testing "…including a :renderer offered INSTEAD of :root-view — the
            streaming path cannot take a whole body from elsewhere, so it
            refuses rather than silently rendering nothing"
    (register-app!)
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                   (ssr-ring/stream-handler
                     (assoc base-opts :renderer fixed-renderer))))]
      (is (= :rf.error/ssr-streaming-unsupported-opt
             (:rf.error/id (ex-data ex))))
      (is (= :renderer (:opt-key (ex-data ex))))))
  (testing "…while an absent or explicit-nil :renderer constructs cleanly and
            streams the JVM-local render — the refusal gates only a non-nil
            override, never the default path"
    (register-app!)
    (doseq [opts [(assoc base-opts :root-view [(rf/view :seam/root)])
                  (assoc base-opts :root-view [(rf/view :seam/root)]
                                   :renderer nil)]]
      (let [handler  (ssr-ring/stream-handler opts)
            response (handler request)
            body     (drain-stream (:body response))]
        (is (= 200 (:status response)))
        (is (str/includes? body "jvm body") "the JVM :root-view streamed")))))

;; ===========================================================================
;; A renderer throw is a render-time throw
;; ===========================================================================

(deftest a-throwing-renderer-projects-like-a-root-view-render-throw
  (testing "rf2-8arzr S5 premise: a :renderer throw happens at the render call
            with a live frame, so the EXISTING render-failure projection
            handles it — projected 500, the projector's public message, no
            hydration payload, no throwable detail on the wire"
    (register-app!)
    (let [handler (ssr-ring/ssr-handler
                    (assoc base-opts
                           :root-view [(rf/view :seam/root)]
                           :renderer
                           (fn [_]
                             (throw (ex-info "sidecar unreachable at 127.0.0.1:8148"
                                             {:where :seam})))))
          {:keys [status body]} (handler request)]
      (is (= 500 status) "projected, fail-closed")
      (is (str/includes? body "Something went wrong")
          "the default projector's :message (Spec 011 §Default projector)")
      (is (str/includes? body "internal-error") "…and its :code")
      (is (not (str/includes? body "__rf_payload"))
          "no hydration payload on the error arm")
      (is (not (str/includes? body "8148"))
          "no throwable detail leaks to the wire"))))
