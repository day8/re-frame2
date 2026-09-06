(ns re-frame.ssr.ring.url-strategy-test
  "rf2-089dy — the stock handlers accept a DECLARED `:url-strategy` on the
  per-request frame.

  Since rf2-skr1c the JVM route-link doors encode the href through the
  rendering frame's `:url-strategy`, so a frame CONSTRUCTED with the
  strategy renders `/realworld/active` server-side — but the stock
  `ssr-handler` / `stream-handler` built their per-request `make-frame`
  config with no opt through which to seat one, so an app served through
  them still rendered the path form and the hydrated client re-encoded on
  hydration: the very mismatch rf2-skr1c closed, surviving through this
  one door.

  These tests pin the closure of that door:

  - POSITIVE — a handler-declared `with-base-path` strategy reaches the
    per-request frame: the wire body carries the based href, through BOTH
    handlers, and the non-streaming body's href is byte-equal to a
    `render-to-string` of the same root under a frame constructed with
    the same strategy directly (the client-identical door rf2-skr1c
    already pinned in `route_link_test.clj`).
  - NEGATIVE — an invalid `:url-strategy` fails frame construction with
    the SAME failure signal as on the client (`make-frame`'s preflight,
    Spec 012 §URL strategies): the canonical
    `:rf.error/invalid-url-strategy` ex-info, surfaced through the
    handler's `:on-error` short-circuit. Presence semantics hold — an
    explicit nil is a declaration and fails identically, while OMITTING
    the key keeps the default path-form history strategy.

  The strategy is DECLARED by the programmer; the handlers never infer it
  from the request or its headers (the bead's stated non-goal)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(defn- register-linked-app!
  "One route, one no-op server init event, one root view whose only
  content is a `route-link` to that route — the smallest app whose wire
  body betrays which URL strategy the rendering frame carries."
  []
  (rf/reg-route :route/active {} "/active")
  (rf/reg-event :init/url-strategy {:platforms #{:server}} (fn [_ _] {}))
  (rf/reg-view* :pages/linked
    (fn []
      [:div.page [rf/route-link {:to :route/active} "Active"]])))

(defn- based-strategy []
  (rf.routing/with-base-path rf.routing/history-url-strategy "/realworld"))

(defn- handler-opts [& {:as extra}]
  (merge {:initial-events [[:init/url-strategy]]
          :root-view      [(rf/view :pages/linked)]
          :payload        :rf.ssr.payload/whole-app-db}
         extra))

(defn- first-href
  "The first anchor href in `html` — the route-link's (the default shell
  emits no other `href=`)."
  [html]
  (second (re-find #"href=\"([^\"]+)\"" html)))

;; ===========================================================================
;; Positive — the declared strategy reaches the per-request frame
;; ===========================================================================

(deftest ssr-handler-declared-url-strategy-reaches-the-request-frame
  (register-linked-app!)
  (let [strategy (based-strategy)
        handler  (rf.ssr.ring/ssr-handler (handler-opts :url-strategy strategy))
        response (handler {:uri "/" :request-method :get})]
    (testing "the wire body carries the based href"
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "href=\"/realworld/active\"")
          (str "the server shell encodes through the declared strategy, got: "
               (first-href (:body response))))
      (is (not (str/includes? (:body response) "href=\"/active\""))
          "the path-form href no longer reaches the server shell"))
    (testing "parity with the client-identical door: the handler's href equals
              a render-to-string under a frame CONSTRUCTED with the strategy"
      (rf/make-frame {:id           :parity/client-identical
                      :platform     :server
                      :url-strategy strategy})
      (let [direct (rf/with-frame :parity/client-identical
                     (rf/render-to-string [(rf/view :pages/linked)]))]
        (is (= "/realworld/active" (first-href direct))
            "sanity: the direct-construction door renders the based href")
        (is (= (first-href direct) (first-href (:body response)))
            "the stock handler and the direct make-frame door agree")))))

(deftest stream-handler-declared-url-strategy-reaches-the-request-frame
  (register-linked-app!)
  (let [handler  (rf.ssr.ring/stream-handler
                   (handler-opts :url-strategy (based-strategy)))
        response (handler {:uri "/" :request-method :get})
        body     (with-open [in ^java.io.InputStream (:body response)]
                   (slurp in))]
    (is (= 200 (:status response)))
    (is (str/includes? body "href=\"/realworld/active\"")
        "the streamed shell encodes through the declared strategy too")
    (is (not (str/includes? body "href=\"/active\""))
        "the path-form href does not reach the streamed shell")))

(deftest omitted-url-strategy-keeps-the-default-path-form
  (register-linked-app!)
  (let [handler  (rf.ssr.ring/ssr-handler (handler-opts))
        response (handler {:uri "/" :request-method :get})]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "href=\"/active\"")
        "no declaration ⇒ the default history strategy's path-form href")))

;; ===========================================================================
;; Negative — an invalid strategy fails with the CLIENT's failure signal
;; ===========================================================================

(defn- capturing-on-error
  "An `:on-error` hook that captures the setup throwable into `slot` and
  returns a sentinel response, so the test can read both the wire outcome
  and the underlying failure signal."
  [slot]
  (fn [_request t]
    (reset! slot t)
    {:status 599 :headers {} :body "setup failed"}))

(defn- client-door-ex
  "The ExceptionInfo the CLIENT-identical door throws for `strategy` —
  `make-frame`'s preflight at the frame-config commit chokepoint."
  [strategy]
  (try
    (rf/make-frame {:id           :parity/bad-strategy
                    :platform     :server
                    :url-strategy strategy})
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(deftest invalid-url-strategy-fails-with-the-clients-signal
  (register-linked-app!)
  (let [bad       {:encode "not-callable"}
        client-ex (client-door-ex bad)
        captured  (atom nil)
        handler   (rf.ssr.ring/ssr-handler
                    (handler-opts :url-strategy bad
                                  :on-error     (capturing-on-error captured)))
        response  (handler {:uri "/" :request-method :get})]
    (is (some? client-ex)
        "sanity: the client-identical make-frame door rejects this value")
    (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data client-ex)))
        "sanity: the client signal is the canonical invalid-url-strategy")
    (testing "the handler short-circuits setup through :on-error"
      (is (= 599 (:status response))
          "the sentinel response proves setup-request-frame! short-circuited"))
    (testing "the contained throwable IS the client's failure signal"
      (is (instance? clojure.lang.ExceptionInfo @captured))
      (is (= (:rf.error/id (ex-data client-ex))
             (:rf.error/id (ex-data @captured)))
          "same canonical :rf.error/id as the client door")
      (is (= (:where (ex-data client-ex))
             (:where (ex-data @captured)))
          "same :where — the strategy is validated by make-frame itself"))))

(deftest explicit-nil-url-strategy-is-a-declaration-and-fails
  (register-linked-app!)
  (let [captured (atom nil)
        handler  (rf.ssr.ring/ssr-handler
                   (handler-opts :url-strategy nil
                                 :on-error     (capturing-on-error captured)))
        response (handler {:uri "/" :request-method :get})]
    ;; Presence semantics (Spec 012 §URL strategies): a PRESENT key —
    ;; explicit nil included — is a declaration and must be a valid
    ;; strategy map; only OMISSION selects the default.
    (is (= 599 (:status response))
        "{:url-strategy nil} is threaded and fails loud, exactly as on the client")
    (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data @captured))))))
