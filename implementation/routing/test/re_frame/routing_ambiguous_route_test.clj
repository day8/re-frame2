(ns re-frame.routing-ambiguous-route-test
  "A frame made without `:images` resolves the registration store live, so a
  route id registered from more than one namespace is ambiguous there (Spec 002
  §Image resolution and composition). A URL that would match that route raises
  `:rf.error/ambiguous-registration` rather than falling through to whatever
  else matches: the navigation fails closed to `:rf.route/not-found`, and one
  always-on record names the route, the namespaces and the remedy. Every other
  route keeps working (Spec 012 §The route table is data).

  Every frame here is built through the public `rf/make-frame`, and the probes
  register through the real `rf/reg-route` macro from real namespaces, so each
  registration carries the provenance a handler moved between namespaces
  leaves behind.

  JVM-only: it creates namespaces and `eval`s registration forms in them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.resolve :as rf.routing.resolve]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private probe-namespaces '[probe.routes-a probe.routes-b])

(defn- eval-in-ns
  "Evaluate `form` with `*ns*` bound to the namespace `ns-sym`, so a `rf/reg-*`
  macro in it captures that namespace as its provenance."
  [ns-sym form]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rf 're-frame.core)
    (eval form)))

(defn- register-page-in!
  "Register `:probe/page` at `/page` from the namespace `ns-sym`."
  [ns-sym]
  (eval-in-ns ns-sym '(rf/reg-route :probe/page {} "/page")))

(defn- with-recorded-errors
  "Run `(f seen)` with every always-on error record appended to the atom
  `seen`, and the probe namespaces removed afterwards."
  [f]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (f seen)
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)
        (doseq [ns-sym probe-namespaces] (remove-ns ns-sym))))))

(defn- ambiguities [records]
  (filterv #(= :rf.error/ambiguous-registration (:error %)) records))

(defn- current-route-id [frame-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
          [:rf.runtime/routing :current :route-id]))

(deftest a-url-matching-an-ambiguous-route-raises-instead-of-falling-through
  (with-recorded-errors
    (fn [seen]
      (rf/reg-route :probe/home {} "/")
      (rf/reg-route :probe/catch-all {} "/*")
      (register-page-in! 'probe.routes-a)
      (rf/make-frame {:id :probe/frame})
      (register-page-in! 'probe.routes-b)
      (rf/dispatch-sync [:rf.route/handle-url-change "/page"] {:frame :probe/frame})
      (is (= :rf.route/not-found (current-route-id :probe/frame))
          "the URL fails closed rather than landing on the catch-all")
      (let [[record :as records] (ambiguities @seen)]
        (is (= 1 (count records)) "one always-on record per navigation")
        (is (= {:frame        :probe/frame
                :event-id     :rf.route/handle-url-change
                :failing-id   :probe/page
                :registration [:route :probe/page]
                :namespaces   ["probe.routes-a" "probe.routes-b"]}
               (select-keys record [:frame :event-id :failing-id :registration :namespaces])))
        (is (str/includes? (str (:reason record)) "(rf/clear :route :probe/page)")
            "the record names the remedy"))
      (rf/dispatch-sync [:rf.route/handle-url-change "/"] {:frame :probe/frame})
      (is (= :probe/home (current-route-id :probe/frame))
          "every other route keeps working")
      (is (= 1 (count (ambiguities @seen)))
          "a URL that does not reach the ambiguous route raises nothing")
      (rf/dispatch-sync [:rf.route/navigate {:url "/page"}] {:frame :probe/frame})
      (is (= :rf.route/not-found (current-route-id :probe/frame)))
      (is (= [2 :rf.route/navigate]
             [(count (ambiguities @seen)) (:event-id (peek (ambiguities @seen)))])
          "the programmatic {:url} door raises it too"))))

(deftest match-url-raises-the-ambiguity-inside-the-frame
  (with-recorded-errors
    (fn [_seen]
      (register-page-in! 'probe.routes-a)
      (register-page-in! 'probe.routes-b)
      (rf/make-frame {:id :probe/frame})
      (is (= :rf.error/ambiguous-registration
             (try
               (rf.live-frame/call-with-frame-resolution
                 :probe/frame #(rf.routing/match-url "/page"))
               nil
               (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e))))))
      (is (= :probe/page (:route-id (rf.routing/match-url "/page")))
          "outside a frame the registrar's latest registration still matches"))))

(def ^:private query-secret "AUDIT_SECRET_7")
(def ^:private path-secret "PATH_SECRET_9")

(defn- register-sensitive-routes-in!
  "Register, from the namespace `ns-sym`, `:probe/page` declaring its `ticket`
  query value sensitive and `:probe/reset` declaring its `token` path param
  sensitive."
  [ns-sym]
  (eval-in-ns ns-sym '(rf/reg-route :probe/page {:sensitive [[:query :ticket]]} "/page"))
  (eval-in-ns ns-sym '(rf/reg-route :probe/reset {:sensitive [[:params :token]]} "/reset/:token")))

(defn- project-in-frame
  "`url` as `:probe/frame`'s egress projection sees it."
  [url]
  (rf.live-frame/call-with-frame-resolution
    :probe/frame #(rf.routing.resolve/redact-declared-url url)))

(defn- navigate-tracing!
  "Dispatch `[:rf.route/handle-url-change url]` into `:probe/frame`, returning
  every trace it emits."
  [url]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::traces #(swap! traces conj %))
    (try
      (rf/dispatch-sync [:rf.route/handle-url-change url] {:frame :probe/frame})
      (finally (rf/unregister-listener! :trace ::traces)))
    @traces))

(deftest an-ambiguous-route-url-fails-closed-to-a-redacted-form
  ;; An ambiguous route has no one `:sensitive` declaration to project its URL
  ;; by, so the URL the ambiguity record and the traces carry is redacted as
  ;; though the route declared its whole projection: every value its pattern
  ;; captures, every query value and the fragment.
  (with-recorded-errors
    (fn [seen]
      (let [query-url (str "/page?ticket=" query-secret "&plain=visible")
            path-url  (str "/reset/" path-secret "?plain=visible")]
        (register-sensitive-routes-in! 'probe.routes-a)
        (rf/make-frame {:id :probe/frame})
        (is (= ["/page?ticket=rf/redacted&plain=visible"
                "/reset/rf%2Fredacted?plain=visible"]
               (mapv project-in-frame [query-url path-url]))
            "control: an unambiguous route redacts its declared value and keeps ordinary URL data")
        (register-sensitive-routes-in! 'probe.routes-b)
        (testing "a declared query value"
          (reset! seen [])
          (let [traces               (navigate-tracing! query-url)
                [record :as records] (ambiguities @seen)]
            (is (= :rf.route/not-found (current-route-id :probe/frame))
                "the navigation still fails closed")
            (is (= 1 (count records)) "exactly one ambiguity report")
            (is (= [:rf.route/handle-url-change "/page?ticket=rf/redacted&plain=rf/redacted"]
                   (:event record))
                "the always-on record carries the URL redacted")
            (is (not (str/includes? (pr-str @seen) query-secret))
                "no always-on record carries the declared secret")
            (when rf.interop/debug-enabled?
              (is (some #(= :rf.error/ambiguous-registration (:operation %)) traces)
                  "control: the trace window carries the ambiguity")
              (is (not (str/includes? (pr-str traces) query-secret))
                  "no trace the navigation emits carries the declared secret"))))
        (testing "a declared path param"
          ;; The route-miss telemetry the same navigation emits projects its
          ;; URL by the carrier policy alone, which keeps the path, so this
          ;; case reads the ambiguity's own record and trace.
          (reset! seen [])
          (let [traces               (navigate-tracing! path-url)
                [record :as records] (ambiguities @seen)
                ambiguity-traces     (filterv #(= :rf.error/ambiguous-registration (:operation %))
                                              traces)]
            (is (= :rf.route/not-found (current-route-id :probe/frame)))
            (is (= 1 (count records)))
            (is (= [:rf.route/handle-url-change "/reset/rf%2Fredacted?plain=rf/redacted"]
                   (:event record)))
            (is (not (str/includes? (pr-str record) path-secret)))
            (when rf.interop/debug-enabled?
              (is (= 1 (count ambiguity-traces)))
              (is (not (str/includes? (pr-str ambiguity-traces) path-secret))))))))))
