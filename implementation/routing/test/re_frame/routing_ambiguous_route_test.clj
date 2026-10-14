(ns re-frame.routing-ambiguous-route-test
  "A frame made without `:images` resolves the registration store live, so a
  route id registered from more than one namespace is ambiguous there (Spec 002
  §Image resolution and composition). A URL that would match that route raises
  `:rf.error/ambiguous-registration` rather than falling through to whatever
  else matches: the navigation fails closed to `:rf.route/not-found`, and one
  always-on record names the route, the namespaces and the remedy. Every other
  route keeps working (Spec 012 §Route registration).

  Every frame here is built through the public `rf/make-frame`, and the probes
  register through the real `rf/reg-route` macro from real namespaces, so each
  registration carries the provenance a handler moved between namespaces
  leaves behind.

  JVM-only: it creates namespaces and `eval`s registration forms in them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.routing :as rf.routing]
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
                :registration [:route :probe/page]
                :namespaces   ["probe.routes-a" "probe.routes-b"]}
               (select-keys record [:frame :registration :namespaces])))
        (is (str/includes? (str (:reason record)) "(rf/clear :route :probe/page)")
            "the record names the remedy"))
      (rf/dispatch-sync [:rf.route/handle-url-change "/"] {:frame :probe/frame})
      (is (= :probe/home (current-route-id :probe/frame))
          "every other route keeps working")
      (is (= 1 (count (ambiguities @seen)))
          "a URL that does not reach the ambiguous route raises nothing")
      (rf/dispatch-sync [:rf.route/navigate {:url "/page"}] {:frame :probe/frame})
      (is (= :rf.route/not-found (current-route-id :probe/frame)))
      (is (= 2 (count (ambiguities @seen)))
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
