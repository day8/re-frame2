(ns re-frame.ssr.ring.realm-routing-test
  "rf2-nu5w48 / EP-0013 §Realm Conformance — the ssr-ring ERROR-body render
  path and the STREAMING continuation render (re-established on the daemon
  writer thread) route registered-view lookups through the request frame's
  OWN realm registrar.

  Follow-up to rf2-bzw8gd (#4294), which keyed the SSR side channels by the
  (realm, frame) address and bound the realm registrar around the NON-error
  render walk (`build-full-response*` / `render-streaming-shell!`). Two gaps
  remained, closed here:

    1. The error-body render path (`pipeline/project-render-throw->ring-
       response` → `resolve-error-body`) did not bind the frame's realm, so a
       caller `:error-view` registered IN the realm resolved against the
       process-global default registrar (a miss → the host default template).

    2. The streaming daemon writer re-establishes the realm around its
       continuation render via the carried `:realm-id`, but no JVM regression
       drove a non-default-realm frame's continuation END-TO-END across the
       thread boundary. This ns proves the realm view resolves on the writer
       thread.

  The unit-level side-channel + head/route isolation is covered by
  `re-frame.ssr-realm-address-test` (implementation/ssr/test); this ns covers
  the ssr-ring error + cross-thread streaming render paths.

  Realm setup mirrors `re-frame.ssr-realm-address-test`: a constructed realm
  carrying the SSR adapter (`:adapter ssr/adapter`) plus its OWN registrar,
  with the per-request server frame + the realm-only views registered under
  the realm's `*current-realm*` / `*registrar*` scope. The realm-routing only
  activates when `*current-realm*` is ambiently bound around the request (the
  router / host-adapter pattern — `frame/call-with-realm`), which is how the
  bzw8gd side-channel writes are also driven."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.streaming :as streaming]
            [re-frame.ssr.ring.test-support :as ts])
  (:import [java.io OutputStream]))

(use-fixtures :each ts/reset-runtime)

;; ---------------------------------------------------------------------------
;; Realm scaffolding
;; ---------------------------------------------------------------------------

(defn- with-realm-registrar
  "Run `thunk` with BOTH the realm dimension bound: `*current-realm*` to
  `rid` (so frame-registry + side-channel addressing resolve the realm's
  frame) AND `registrar/*registrar*` to the realm's OWN registrar (so
  reg-frame / reg-view* SEAT into — and view lookups RESOLVE from — the
  realm's table). Mirrors how `app-value/install!` seats a realm's program
  and how the SSR render path resolves one."
  [rid thunk]
  (binding [frame/*current-realm*    rid
            registrar/*registrar*    (realm/registrar (realm/realm rid))]
    (thunk)))

;; ===========================================================================
;; (1) ERROR-body render path routes through the frame's realm registrar
;; ===========================================================================

(deftest error-view-resolves-the-frames-own-realm-registration
  (testing "rf2-nu5w48: project-render-throw->ring-response renders a caller
            :error-view REGISTERED IN THE REALM through the realm's OWN
            registrar (not the process-global default). A view by the same id
            registered in the default realm would be the WRONG one — the test
            registers the error view ONLY in the realm, so a default-registrar
            lookup misses and the realm-routed lookup is what makes it
            resolve."
    (let [rid :nu5w48/err-realm
          fid :nu5w48/err-frame]
      (realm/construct-realm {:id rid :adapter ssr/adapter})
      (try
        ;; Seat a per-request SERVER frame + the realm-only error view into
        ;; the realm's registrar, under the realm scope.
        (with-realm-registrar rid
          (fn []
            (rf/reg-frame fid {:platform :server :doc "realm per-request frame"})
            ;; The error view lives ONLY in this realm's registrar. Its markup
            ;; is a realm-unique signature so a default-realm miss is visible.
            (rf/reg-view* :nu5w48/error-page
              (fn [{:keys [status code message]}]
                [:div.realm-error
                 [:h1 "REALM ERROR PAGE"]
                 [:p.code (name code)]
                 [:p.status (str status)]
                 [:p.msg message]]))))
        (let [t    (ex-info "internal-render-boom" {})
              opts {:error-view  :nu5w48/error-page
                    :content-type "text/html; charset=utf-8"}
              ;; Drive the error-body path UNDER the realm scope (the host /
              ;; router binds `*current-realm*` around the request; the
              ;; in-fn binding of the realm REGISTRAR is what rf2-nu5w48 adds).
              response (frame/call-with-realm rid
                         (fn [] (pipeline/project-render-throw->ring-response fid t opts)))
              body     (:body response)]
          (is (= 500 (:status response))
              "the projector's fail-closed status rides the response")
          (is (str/includes? body "REALM ERROR PAGE")
              "rf2-nu5w48: the realm-registered :error-view resolved through the
               frame's OWN realm registrar (a default-registrar lookup would
               miss it and fall back to the host default template)")
          (is (str/includes? body "class=\"realm-error\"")
              "the realm error view's own markup reached the wire")
          (is (not (str/includes? body "internal-render-boom"))
              "rf2-kzvwq: the throwable message never reaches the wire"))
        (finally
          (realm/dispose-realm! rid))))))

(deftest default-realm-error-view-still-resolves-byte-identically
  (testing "rf2-nu5w48: the realm binding is a no-op for a default-realm
            frame — a default-realm :error-view still resolves (the
            single-realm path is unchanged)."
    (rf/reg-event :nu5w48/init-ok {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view* :nu5w48/broken-default (fn [] (throw (ex-info "boom" {}))))
    (rf/reg-view* :nu5w48/default-error-page
      (fn [{:keys [message]}]
        [:div.default-error [:h1 "DEFAULT ERROR PAGE"] [:p message]]))
    (let [handler  (ssr-ring/ssr-handler
                     {:on-create  [:nu5w48/init-ok]
                      :root-view  [:nu5w48/broken-default]
                      :error-view :nu5w48/default-error-page
                      :payload    :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/broken" :request-method :get})
          body     (:body response)]
      (is (= 500 (:status response)))
      (is (str/includes? body "DEFAULT ERROR PAGE")
          "a default-realm error view resolves unchanged (binding no-ops)"))))

;; ===========================================================================
;; (2) STREAMING continuation render re-establishes the realm on the daemon
;;     writer thread — cross-thread realm isolation, end to end.
;; ===========================================================================

(defn- collecting-output-stream
  "An OutputStream that accumulates every written byte into `sb` (decoded as
  UTF-8 on close-time read via `str`). Used to capture the streamed wire
  bytes the writer pumps on the daemon thread."
  ^OutputStream [^StringBuilder sb]
  (proxy [OutputStream] []
    (write
      ([b]
       (if (bytes? b)
         (.append sb (String. ^bytes b java.nio.charset.StandardCharsets/UTF_8))
         (.append sb (char (int b))))
       nil)
      ([b off len]
       (.append sb (String. ^bytes b (int off) (int len)
                            java.nio.charset.StandardCharsets/UTF_8))
       nil))
    (flush [] nil)
    (close [] nil)))

(deftest streaming-continuation-resolves-realm-view-on-daemon-thread
  (testing "rf2-nu5w48: a non-default-realm frame's streaming render captures
            the realm-id on the request thread (`render-streaming-shell!`),
            and the writer re-establishes that realm on a FRESH thread so the
            suspense-boundary continuation resolves the realm's REGISTERED
            view. The boundary body view is registered ONLY in the realm's
            registrar — a default-registrar lookup on the writer thread would
            miss it; the realm-id carry + call-with-realm/call-with-frame-
            realm-registrar binding is what resolves it cross-thread."
    (let [rid :nu5w48/stream-realm
          fid :nu5w48/stream-frame]
      (realm/construct-realm {:id rid :adapter ssr/adapter})
      (try
        (with-realm-registrar rid
          (fn []
            (rf/reg-frame fid {:platform :server :doc "realm streaming frame"})
            ;; The deferred subtree's view — REALM-ONLY. Its body is a
            ;; realm-unique signature string.
            (rf/reg-view* :nu5w48/realm-section
              (fn [] [:div.realm-deferred "REALM-DEFERRED-CONTENT"]))
            ;; A root carrying a suspense boundary around the realm-only view.
            (rf/reg-view* :nu5w48/stream-root
              (fn []
                [:main
                 [:h1 "Realm streaming"]
                 [:rf/suspense-boundary
                  {:id :nu5w48/boundary :fallback [:p "loading…"]}
                  [:nu5w48/realm-section]]]))))
        (let [opts {:on-create nil
                    :root-view [:nu5w48/stream-root]
                    :emit-hash? true
                    :payload :rf.ssr.payload/whole-app-db}
              ;; Render the shell on THIS thread UNDER the realm scope — exactly
              ;; as the request thread does (router binds *current-realm*). This
              ;; captures :realm-id into the rendered map for the writer thread.
              rendered (frame/call-with-realm rid
                         (fn [] (#'streaming/render-streaming-shell! fid opts)))]
          (is (= rid (:realm-id rendered))
              "render-streaming-shell! captured the frame's non-default realm-id
               for the daemon writer to re-establish across the thread boundary")
          (is (seq (:continuations rendered))
              "the realm suspense boundary registered a continuation")
          ;; Drive the writer on a FRESH thread with NO ambient realm binding —
          ;; the daemon thread must re-establish the realm from the carried
          ;; :realm-id alone. A non-realm-routed writer would resolve
          ;; :nu5w48/realm-section against the default registrar (a miss) and
          ;; emit an unresolved/blank subtree.
          (let [sb     (StringBuilder.)
                out    (collecting-output-stream sb)
                writer (Thread.
                         ^Runnable
                         (fn []
                           ;; Assert there is NO ambient realm on this thread —
                           ;; the writer must rely solely on the carried id.
                           (assert (nil? frame/*current-realm*))
                           (@#'streaming/run-streaming-writer! out fid rendered opts)))]
            (.start writer)
            (.join writer 10000)
            (is (not (.isAlive writer)) "the writer thread completed")
            (let [body (str sb)]
              (is (str/includes? body "Realm streaming")
                  "the shell streamed")
              (is (str/includes? body "REALM-DEFERRED-CONTENT")
                  "rf2-nu5w48: the daemon-thread continuation resolved the
                   REALM-registered view (cross-thread realm re-establishment
                   via the carried :realm-id) — a default-registrar lookup on
                   the writer thread would have missed it")
              (is (str/includes? body "data-rf2-suspense-resolved=\"1\"")
                  "the continuation chunk streamed resolved (not the fallback)")
              ;; The boundary did NOT fail (the view resolved cleanly).
              (is (not (str/includes? body "data-rf2-suspense-failed=\"1\""))
                  "the realm continuation resolved cleanly — NOT a failed
                   fallback re-emit (which a missed realm lookup could cause)")
              (is (str/includes? body "__rf_payload")
                  "the final payload chunk streamed")
              ;; rf2-z9dduj cross-check on the realm path: #app closes before
              ;; the resolved chunk + payload here too.
              (let [idx-close    (str/index-of body "</div>")
                    idx-resolved (str/index-of body "data-rf2-suspense-resolved=\"1\"")
                    idx-payload  (str/index-of body "__rf_payload")]
                (is (and idx-close idx-resolved (< idx-close idx-resolved))
                    "the app root closes before the realm resolved chunk")
                (is (and idx-close idx-payload (< idx-close idx-payload))
                    "the app root closes before the final payload")))
            ;; Tear the realm frame down under its realm scope (the daemon
            ;; thread had none) so the test leaves no per-frame record behind.
            (frame/call-with-realm rid
              (fn [] (lifecycle/destroy-frame-quietly! fid)))))
        (finally
          (realm/dispose-realm! rid))))))

;; ===========================================================================
;; (3) FINAL __rf_payload reflects the NON-DEFAULT realm frame's app-db /
;;     runtime-db — the writer's `build-final-payload` re-establishes the
;;     carried realm so `frame-app-db-value` / `frame-runtime-db-value`
;;     resolve the (realm, frame) slot, not the missing default-realm key.
;; ===========================================================================

(defn- stream-payload-edn
  "Parse the `__rf_payload` final-chunk EDN out of a streamed document body.
  The payload is a `#:rf{…}` namespace-map; `clojure.edn/read-string` reads
  it (the namespaced keys round-trip)."
  [body]
  (some-> (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)
          second
          edn/read-string))

(deftest streaming-final-payload-reflects-realm-frame-state-on-daemon-thread
  (testing "rf2-tbr67x: a non-default-realm request frame carrying seeded
            app-db / runtime-db state streams a FINAL __rf_payload whose
            :rf/app-db (and :rf/runtime-db) are projected FROM THAT REALM
            FRAME — even though the writer's `build-final-payload` runs on a
            FRESH daemon thread with NO ambient `frame/*current-realm*`. Before
            the fix, `build-final-payload` read `frame-app-db-value` /
            `frame-runtime-db-value` through `*current-realm*` (unbound on the
            writer thread), missing the frame stored at the [realm frame-id]
            slot — so the payload carried nil app-db even though the streamed
            HTML came from the realm frame."
    (let [rid :tbr67x/payload-realm
          fid :tbr67x/payload-frame]
      (realm/construct-realm {:id rid :adapter ssr/adapter})
      (try
        (with-realm-registrar rid
          (fn []
            (rf/reg-frame fid {:platform :server :doc "realm payload frame"})
            (rf/reg-view* :tbr67x/payload-root
              (fn [] [:main [:h1 "Realm payload"]]))))
        ;; Seed the realm frame's app-db + a durable runtime-db slice UNDER the
        ;; realm scope (the writes resolve `(frame id)` through *current-realm*).
        (frame/call-with-realm rid
          (fn []
            (frame/replace-app-db! fid {:token "realm-db" :n 7})
            (frame/replace-runtime-db!
              fid {:rf.runtime/routing {:current {:handler :tbr67x/home}}})))
        (let [opts     {:on-create nil
                        :root-view [:tbr67x/payload-root]
                        :emit-hash? true
                        :payload   :rf.ssr.payload/whole-app-db}
              rendered (frame/call-with-realm rid
                         (fn [] (#'streaming/render-streaming-shell! fid opts)))]
          (is (= rid (:realm-id rendered))
              "render-streaming-shell! captured the non-default realm-id")
          (let [sb     (StringBuilder.)
                out    (collecting-output-stream sb)
                writer (Thread.
                         ^Runnable
                         (fn []
                           ;; The writer must rely solely on the carried
                           ;; :realm-id — NO ambient realm on this thread.
                           (assert (nil? frame/*current-realm*))
                           (@#'streaming/run-streaming-writer! out fid rendered opts)))]
            (.start writer)
            (.join writer 10000)
            (is (not (.isAlive writer)) "the writer thread completed")
            (let [body    (str sb)
                  payload (stream-payload-edn body)]
              (is (some? payload) "the final __rf_payload parsed")
              (is (= fid (:rf/frame-id payload))
                  "the payload pins the realm frame's id")
              ;; THE bug assertion: the projected app-db is the realm frame's
              ;; seeded value, NOT nil. A writer-thread read without the realm
              ;; rebinding would resolve the default-realm key (a miss) and
              ;; ship :rf/app-db nil under the whole-app-db policy.
              (is (= {:token "realm-db" :n 7} (:rf/app-db payload))
                  "rf2-tbr67x: the final payload's :rf/app-db is projected from
                   the NON-DEFAULT realm frame (the writer re-established the
                   carried realm around build-final-payload)")
              (is (= {:handler :tbr67x/home}
                     (get-in payload [:rf/runtime-db :rf.runtime/routing :current]))
                  "rf2-tbr67x: the final payload's :rf/runtime-db durable slice
                   is also projected from the realm frame"))))
        (finally
          (frame/call-with-realm rid
            (fn [] (lifecycle/destroy-frame-quietly! fid)))
          (realm/dispose-realm! rid))))))

(deftest streaming-final-payload-default-realm-unchanged
  (testing "rf2-tbr67x: the final-payload realm rebinding is a no-op for a
            DEFAULT-realm frame — a default-realm streamed payload still
            carries the frame's app-db (the single-realm path is unchanged)."
    (rf/reg-frame :tbr67x/default-frame {:platform :server})
    (rf/reg-view* :tbr67x/default-root (fn [] [:main [:h1 "Default payload"]]))
    (frame/replace-app-db! :tbr67x/default-frame {:token "default-db"})
    (let [fid      :tbr67x/default-frame
          opts     {:on-create nil
                    :root-view [:tbr67x/default-root]
                    :emit-hash? true
                    :payload   :rf.ssr.payload/whole-app-db}
          rendered (#'streaming/render-streaming-shell! fid opts)]
      (is (contains? #{nil realm/default-realm-id} (:realm-id rendered))
          "a default-realm frame captures the default realm-id — call-with-realm
           treats it as the byte-identical no-binding path")
      (let [sb     (StringBuilder.)
            out    (collecting-output-stream sb)
            writer (Thread.
                     ^Runnable
                     (fn []
                       (@#'streaming/run-streaming-writer! out fid rendered opts)))]
        (.start writer)
        (.join writer 10000)
        (is (not (.isAlive writer)) "the writer thread completed")
        (let [payload (stream-payload-edn (str sb))]
          (is (= {:token "default-db"} (:rf/app-db payload))
              "the default-realm streamed payload carries the frame's app-db"))))))
