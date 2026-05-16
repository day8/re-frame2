(ns re-frame.ssr.ring-e2e-validator-test
  "End-to-end JVM coverage for the SSR safety validators — observed at the
  bytes-on-wire layer. Follow-on from rf2-ik4io (the browser-testbed
  hydration coverage) per bead rf2-to8pm.

  ## Why this lives at the wire layer

  Three families of validators fail-fast on the JVM render path BEFORE
  any HTML bytes reach the client:

    - tag-name grammar (rf2-z7gor) — `:rf.error/invalid-tag-name` from
      `re-frame.ssr.emit/validate-tag-name!` when a hiccup keyword's
      tag-name component violates `[A-Za-z][A-Za-z0-9-]*`.
    - header / redirect / cookie CRLF gates (rf2-hbty2, rf2-z7gor) —
      `:rf.error/header-invalid-value`, `:rf.error/cookie-invalid-{path,
      value,domain}`, `:rf.error/header-invalid-name`,
      `:rf.error/redirect-invalid-location` from
      `re-frame.ssr.response/validate-*` when a server-controlled string
      carries CR / LF / NUL (RFC 7230 §3.2.4 header-splitting vector).

  Each of these has tight unit-test coverage in the ssr artefact
  (`ssr_conformance_test.clj`, `ssr_end_to_end_test.clj`). Per Spec 011
  the validators throw structured `ex-info`s; the `re-frame.ssr.ring`
  handler catches via its `:on-error` hook and emits a generic 500
  (rf2-kzvwq: never .getMessage — no topology leak).

  The browser testbed surfaces (rf2-ik4io) cover the hydration round-
  trip from the CLIENT end. The client never sees the broken HTML —
  that's the point of fail-fast — so there's no client-side surface
  where these validators can be exercised end-to-end.

  This namespace closes that loop on the JVM side. The shape:

    1. Stand up a real Jetty server on an ephemeral port (port 0 →
       OS-assigned; read back via `(.getURI server)`).
    2. Mount `ssr-handler` at `/`.
    3. HTTP-GET via `java.net.http.HttpClient` so we observe actual
       wire bytes — response status code, raw header values, body — not
       just Ring-map return values from a direct handler call.
    4. Assert: validator-triggering inputs produce a 500 (generic body,
       no leak); no CR/LF in actual wire headers; the happy path still
       returns 200.

  ## Why this is the right loop-closer

  Unit tests pin the validators in isolation. The Ring-level smoke tests
  in `ring_test.clj` exercise the validator → `:on-error` mapping via
  direct handler invocation. NEITHER guarantees that a real HTTP server
  preserves the contract — a host adapter could in principle re-serialise
  CR/LF, or Jetty's response writer could silently strip control chars.
  Bytes-on-wire is the only contract that holds across host adapter
  changes (rf2-ny6v7 → future ssr-pedestal / ssr-httpkit).

  ## Test scope

  Four tests:

    1. `e2e-bad-tag-name-keyword-returns-500` — view emits a hiccup
       keyword whose tag-name component is illegal under the validator
       grammar; HTTP-GET returns 500 + 'Internal error' body; the
       throwable's keyword does not leak onto the wire.
    2. `e2e-crlf-bearing-cookie-value-rejected` — handler sets a cookie
       whose `:value` carries CR/LF; HTTP-GET returns 500; no Set-Cookie
       on the wire; no CR/LF byte sequences in any response header.
    3. `e2e-crlf-bearing-header-value-rejected` — handler `set-header`s
       a custom header with a CRLF value; HTTP-GET returns 500; no CR/LF
       on the wire.
    4. `e2e-valid-render-path-returns-200` — sanity: the host wiring is
       not broken; a clean view produces a 200 with the expected HTML
       and a `data-rf-render-hash` on the root element.

  ## Server lifecycle

  Per Spec 011 §HTTP response contract the runtime owns frame lifecycle
  and the response accumulator; the host adapter (`ssr-handler`) wires
  those to a Ring-shaped function. Here we go one layer further out and
  hand `ssr-handler` to Jetty.

  One server per `deftest` (created via `with-jetty` and torn down in
  `finally`) so test isolation is as strong as possible — port 0 means
  no port collision risk, and the `:each` reset fixture wipes the
  registrar between tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.test-fixture :as tf]
            [ring.adapter.jetty :as jetty])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [org.eclipse.jetty.server Server]))

;; rf2-i3qc0 — canonical reset-runtime fixture; same shape as
;; `ring_test.clj`. Each test starts from a wiped registrar + reloaded
;; SSR ns-bodies so the per-test registrations don't bleed.
(use-fixtures :each tf/reset-runtime)

;; The NUL byte as a one-char string. Defined once here so the wire-
;; scan helper below reads as obvious set membership.
(def ^:private NUL (str (char 0)))

;; ===========================================================================
;; Server lifecycle helpers
;; ===========================================================================

(defn- start-jetty!
  "Run `handler` under Jetty on an ephemeral port (`:port 0`) and return
  `{:server <Server> :port <int>}`. `:join? false` so this thread does
  not block; `:host \"127.0.0.1\"` so we never bind a public interface
  in CI. `:send-server-version? false` and `:send-date-header? false`
  trim ambient noise out of header assertions."
  [handler]
  (let [^Server server (jetty/run-jetty handler
                                        {:port                 0
                                         :host                 "127.0.0.1"
                                         :join?                false
                                         :send-server-version? false
                                         :send-date-header?    false})
        port (.. server (getURI) (getPort))]
    {:server server :port port}))

(defn- stop-jetty!
  [^Server server]
  (.stop server))

(defn- http-get
  "Issue a real HTTP GET against `http://127.0.0.1:<port>/<path>` via
  `java.net.http.HttpClient` (JDK-built-in; no extra dep). Returns
  `{:status :headers :body}` where `:headers` is a `{name -> [val ...]}`
  map (java.net.http's natural multimap shape — preserved here so the
  CRLF-on-wire scan reads every value verbatim)."
  [port path]
  (let [client  (-> (HttpClient/newBuilder)
                    (.connectTimeout (Duration/ofSeconds 5))
                    (.build))
        req     (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://127.0.0.1:" port path)))
                    (.timeout (Duration/ofSeconds 10))
                    (.GET)
                    (.build))
        resp    (.send client req (HttpResponse$BodyHandlers/ofString))
        headers (->> (.. resp (headers) (map))
                     (map (fn [[k v]] [k (vec v)]))
                     (into {}))]
    {:status  (.statusCode resp)
     :headers headers
     :body    (.body resp)}))

(defn- any-header-contains-crlf?
  "Scan every wire header value for CR / LF / NUL. The point of the
  CRLF validators is that these bytes never reach the wire — this is
  the end-to-end assertion that proves it. (Space and HTAB are valid
  per RFC 7230 §3.2 and are not scanned for.)"
  [headers]
  (some (fn [vs]
          (some (fn [^String v]
                  (or (.contains v "\r")
                      (.contains v "\n")
                      (.contains v NUL)))
                vs))
        (vals headers)))

(defmacro with-jetty
  "Bind `bindings` to `(start-jetty! handler)` and tear the server down
  in `finally`. `bindings` is `[port-sym handler-expr]`; the macro
  exposes `port-sym` to the body."
  [[port-sym handler-expr] & body]
  `(let [{server# :server port# :port} (start-jetty! ~handler-expr)
         ~port-sym port#]
     (try
       ~@body
       (finally
         (stop-jetty! server#)))))

;; ===========================================================================
;; Test 1 — bad tag-name keyword → 500, no leak
;; ===========================================================================
;;
;; The tag-name validator (`re-frame.ssr.emit/validate-tag-name!`,
;; rf2-z7gor) gates DOM tag-name components of hiccup keywords. A
;; hostile keyword whose tag-name carries a space — i.e. a name
;; built via `(keyword \"has space\")` — surfaces as tag-name "has space",
;; which the validator rejects. The throw fires inside
;; `render-to-string`; the handler's outer try/catch routes the
;; throwable through `:on-error` → default 500.
;;
;; Wire assertion: 500 status, generic 'Internal error' body, no
;; `:rf.error/invalid-tag-name` keyword in the body (rf2-kzvwq: the
;; default :on-error MUST NOT leak `.getMessage`).

(deftest e2e-bad-tag-name-keyword-returns-500
  (testing "rf2-z7gor — hostile tag-name keyword surfaces as 500 + generic body"
    (rf/reg-event-fx :init/ok-bad-tag {:platforms #{:server}} (fn [_ _] {}))
    ;; The view body intentionally emits a tag-name component carrying a
    ;; space — `validate-tag-name!` rejects anything outside
    ;; `[A-Za-z][A-Za-z0-9-]*`. We register the view via reg-view*
    ;; (plain fn surface) so `render-to-string` resolves it on the
    ;; server frame and surfaces the throw.
    (rf/reg-view* :pages/bad-tag
                  (fn [] [(keyword "has space")]))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/ok-bad-tag]
                     :root-view [:pages/bad-tag]
                     :payload-policy :rf.ssr.payload/whole-app-db})]
      (with-jetty [port handler]
        (let [{:keys [status body headers]} (http-get port "/")]
          (is (= 500 status)
              "tag-name validator throw surfaces as 500 on the wire")
          (is (= "Internal error" body)
              "rf2-kzvwq: default :on-error body is the fixed generic
               string — no .getMessage leak")
          (is (not (str/includes? body "invalid-tag-name"))
              "the validator's :rf.error/invalid-tag-name keyword must
               not appear on the wire (topology disclosure surface)")
          (is (not (str/includes? body "has space"))
              "the offending tag-name string must not be echoed back")
          (is (not (any-header-contains-crlf? headers))
              "no CR/LF/NUL bytes in any response header"))))))

;; ===========================================================================
;; Test 2 — CRLF-bearing cookie value → 500 status, no Set-Cookie on the wire
;; ===========================================================================
;;
;; `:rf.server/set-cookie` validates `:value` via
;; `re-frame.ssr.response/validate-cookie-attr!` (rf2-z7gor). A
;; CRLF-bearing value throws `:rf.error/cookie-invalid-value` from
;; the fx body during drain.
;;
;; The throw fires INSIDE the drain — fx-handler exceptions are caught
;; by the runtime and surface as `:rf.error/fx-handler-exception` trace
;; events. The SSR error projector (`re-frame.ssr.error-listener`,
;; Spec 011 §Server error projection) buffers those traces and on
;; `get-response` projects the last one through the active projector
;; (default = `:internal-error` 500). The runtime stamps the projected
;; status onto the response accumulator, then rendering of the root
;; view proceeds normally — the projector's status owns the wire status
;; but the page still renders (the spec'd surface for in-page error UX).
;;
;; The LOAD-BEARING wire assertion for the CRLF validator family is the
;; absence of CR/LF bytes in any actual header value on the wire — the
;; cookie was never accumulated (because the fx threw before the
;; accumulator write), so no Set-Cookie header carries the hostile
;; payload. Status 500 confirms the projector saw the error trace.

(deftest e2e-crlf-bearing-cookie-value-rejected
  (testing "rf2-z7gor — CRLF in cookie :value rejected before any wire byte emitted"
    (rf/reg-event-fx :init/bad-cookie
      {:platforms #{:server}}
      (fn [_ _]
        ;; The classic header-splitting payload — if this reached the
        ;; wire, a downstream client could be tricked into honouring
        ;; the injected `admin=1` cookie.
        {:fx [[:rf.server/set-cookie
               {:name  "session"
                :value "abc\r\nSet-Cookie: admin=1"}]]}))

    (rf/reg-view* :pages/bad-cookie-page
                  (fn [] [:div.page "rendered after projection"]))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/bad-cookie]
                     :root-view [:pages/bad-cookie-page]
                     :payload-policy :rf.ssr.payload/whole-app-db})]
      (with-jetty [port handler]
        (let [{:keys [status headers]} (http-get port "/")]
          (is (= 500 status)
              "validator's `:rf.error/cookie-invalid-value` trace flowed
               through the default error projector → 500 status on the
               wire (Spec 011 §Server error projection)")
          ;; LOAD-BEARING wire assertion: the cookie was never written
          ;; to the accumulator (the fx threw before the swap), so no
          ;; Set-Cookie header reaches the wire. The hostile `admin=1`
          ;; cookie cannot have been injected via header-splitting.
          (is (nil? (or (get headers "set-cookie")
                        (get headers "Set-Cookie")))
              "no Set-Cookie header on the wire — the fx threw before
               the accumulator was written, so the cookie never
               reached the response")
          (is (not (any-header-contains-crlf? headers))
              "no CR/LF/NUL bytes in any response header — the
               validator kept the hostile payload off the wire (the
               whole point of rf2-z7gor / rf2-hbty2)"))))))

;; ===========================================================================
;; Test 3 — CRLF-bearing header value → 500 status, no leak on the wire
;; ===========================================================================
;;
;; `:rf.server/set-header` validates `:value` via
;; `re-frame.ssr.response/validate-header-value!` (rf2-hbty2). Same
;; vector as the cookie test — RFC 7230 §3.2.4 explicitly bans CTLs
;; (including CR/LF) in `field-value`. The fx-handler throw is buffered
;; and projected as in Test 2.

(deftest e2e-crlf-bearing-header-value-rejected
  (testing "rf2-hbty2 — CRLF in header :value rejected before any wire byte emitted"
    (rf/reg-event-fx :init/bad-header
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/set-header
               {:name  "X-Custom"
                :value "ok\r\nX-Injected: pwn"}]]}))

    (rf/reg-view* :pages/bad-header-page
                  (fn [] [:div.page "rendered after projection"]))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/bad-header]
                     :root-view [:pages/bad-header-page]
                     :payload-policy :rf.ssr.payload/whole-app-db})]
      (with-jetty [port handler]
        (let [{:keys [status headers]} (http-get port "/")]
          (is (= 500 status)
              "validator's `:rf.error/header-invalid-value` trace flowed
               through the default error projector → 500 status on the
               wire")
          ;; LOAD-BEARING wire assertions: the injected header name
          ;; (`X-Injected`) MUST NOT appear on the wire, and the
          ;; original `X-Custom` header MUST NOT appear either —
          ;; neither was ever accumulated (the throw fired before
          ;; the swap). A successful header-splitting attack would
          ;; produce a wire response with BOTH headers; the validator
          ;; prevents either from materialising.
          (is (nil? (or (get headers "x-injected") (get headers "X-Injected")))
              "no `X-Injected` header on the wire — the CRLF payload
               did not split into a forged second header")
          (is (nil? (or (get headers "x-custom") (get headers "X-Custom")))
              "no `X-Custom` header on the wire — the fx threw before
               the accumulator was written, so the original header
               never reached the response")
          (is (not (any-header-contains-crlf? headers))
              "no CR/LF/NUL bytes in any response header value"))))))

;; ===========================================================================
;; Test 4 — valid render path → 200 (sanity / host-wiring smoke)
;; ===========================================================================
;;
;; Confirms the Jetty host wiring is not broken. If this test fails
;; while #1-#3 pass, the failure mode is the test harness, not the
;; validators. The body assertions mirror the existing Ring-level smoke
;; in `ring_test.clj/handler-renders-html-with-status-and-headers` but
;; observe bytes-on-wire rather than the Ring map return value.

(deftest e2e-valid-render-path-returns-200
  (testing "sanity: a clean view → 200 + HTML + render-hash on the wire"
    (rf/reg-event-fx :init/ok
      {:platforms #{:server}}
      (fn [_ _] {:db {:title "Greetings from JVM SSR"}}))

    (rf/reg-sub :title (fn [db _] (:title db)))

    (rf/reg-view* :pages/greeting
                  (fn []
                    (let [t (rf/subscribe-once [:title])]
                      [:div.page
                       [:h1 t]
                       [:p "Bytes-on-wire e2e proof."]])))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/ok]
                     :root-view [:pages/greeting]
                     :payload-policy :rf.ssr.payload/whole-app-db})]
      (with-jetty [port handler]
        (let [{:keys [status body headers]} (http-get port "/")]
          (is (= 200 status))
          (let [ct (or (first (get headers "content-type"))
                       (first (get headers "Content-Type")))]
            (is (some? ct))
            (is (str/includes? ct "text/html"))
            (is (str/includes? ct "utf-8")))
          (is (str/includes? body "<!DOCTYPE html>"))
          (is (str/includes? body "Greetings from JVM SSR"))
          (is (str/includes? body "Bytes-on-wire e2e proof."))
          (is (re-find #"data-rf-render-hash=\"[0-9a-f]{8}\"" body)
              "the root element carries the render hash — the structural
               anchor for client-side hydration mismatch detection")
          (is (str/includes? body "<script id=\"__rf_payload\"")
              "the hydration payload is present on the wire")
          (is (not (any-header-contains-crlf? headers))
              "no CR/LF/NUL bytes in any response header"))))))
