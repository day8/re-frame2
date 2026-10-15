(ns re-frame.routing-ambiguous-overlap-test
  "An ambiguous route's registrations may declare different path patterns, and
  more than one of them can match the same URL: `/:token/reset` and
  `/login/:token` both match `/login/reset`, each capturing a different
  segment. That URL fails closed with EVERY segment any of those patterns
  captures redacted, whichever order the registrations are visited in, because
  each pattern is matched against the URL as it arrived: a segment one
  pattern's capture redacts can be a static segment another pattern needs in
  order to match.

  Every frame here is built through the public `rf/make-frame`, and the probes
  register through the real `rf/reg-route` macro from real namespaces, so each
  registration carries its namespace as provenance.

  JVM-only: it creates namespaces and `eval`s registration forms in them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.routing.resolve :as rf.routing.resolve]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private probe-namespaces '[probe.overlap-a probe.overlap-b])

(defn- register-overlap-in!
  "Register `:probe/overlap` at `path`, declaring its `token` param sensitive,
  from the namespace `ns-sym`, so the `rf/reg-route` macro captures that
  namespace as the registration's provenance."
  [ns-sym path]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rf 're-frame.core)
    (eval (list 'rf/reg-route :probe/overlap {:sensitive [[:params :token]]} path))))

(defn- project-in-frame
  "`url` as `:probe/frame`'s egress projection sees it."
  [url]
  (rf.live-frame/call-with-frame-resolution
    :probe/frame #(rf.routing.resolve/redact-declared-url url)))

(defn- ambiguities [records]
  (filterv #(= :rf.error/ambiguous-registration (:error %)) records))

(def ^:private overlap-url
  "A URL both `/:token/reset` and `/login/:token` match."
  "/login/reset?plain=visible")

(def ^:private both-redacted "/rf%2Fredacted/rf%2Fredacted?plain=rf/redacted")

(defn- check-overlap
  "Register `:probe/overlap` at `first-path` from one namespace and make
  `:probe/frame`, so the control projects `overlap-url` by that one
  registration to `unambiguous`; then register it at `second-path` from a
  second namespace, making the route ambiguous, and check what that frame's
  projection and navigation report."
  [first-path unambiguous second-path]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (rf/reg-route :probe/item {} "/item/:id")
      (register-overlap-in! 'probe.overlap-a first-path)
      (rf/make-frame {:id :probe/frame})
      (is (= unambiguous (project-in-frame overlap-url))
          "control: one registration redacts its own capture and keeps the query")
      (register-overlap-in! 'probe.overlap-b second-path)
      (testing "a URL both patterns match"
        (is (= both-redacted (project-in-frame overlap-url))
            "every segment either pattern captures is redacted, and every query value")
        (is (= (str both-redacted "#rf/redacted")
               (project-in-frame (str overlap-url "#section")))
            "and the fragment"))
      (is (= "/login/rf%2Fredacted" (project-in-frame "/login/kept"))
          "a URL one pattern matches has that pattern's capture redacted")
      (let [url "/item/42?plain=visible"]
        (is (identical? url (project-in-frame url))
            "an unambiguous route that declares nothing rides unchanged"))
      (is (empty? @seen) "the projection reports nothing")
      (rf/dispatch-sync [:rf.route/handle-url-change overlap-url] {:frame :probe/frame})
      (let [[record :as records] (ambiguities @seen)]
        (is (= 1 (count records)) "the navigation reports the ambiguity once")
        (is (= [:rf.route/handle-url-change both-redacted] (:event record))
            "the always-on record carries the URL with both captures redacted"))
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)
        (doseq [ns-sym probe-namespaces] (remove-ns ns-sym))))))

(deftest overlapping-patterns-redact-every-capture
  (check-overlap "/:token/reset" "/rf%2Fredacted/reset?plain=visible" "/login/:token"))

(deftest overlapping-patterns-redact-every-capture-in-the-other-order
  (check-overlap "/login/:token" "/login/rf%2Fredacted?plain=visible" "/:token/reset"))
