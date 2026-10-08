(ns re-frame.ssr.head-model-cljs-test
  "`head-model`'s selection rule on both hosts, and the NAME-COLLISION guard
  for the read.

  The read cannot be spelled `(ssr/head …)`: in ClojureScript a
  `(def head …)` in `re-frame.ssr` would emit `re_frame.ssr.head = <fn>`
  over the `re-frame.ssr.head` namespace object (`:ns-var-clash`). This
  namespace requires BOTH and reads a var through `re-frame.ssr.head` after
  load — the witness on the one host where the clobber can happen."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loaded for its ns-load-time registrations — `rf/reg-route`
            ;; reaches the routing artefact through a late-bind hook this
            ;; namespace publishes.
            [re-frame.routing]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.head :as rf.ssr.head]))

;; COLD-START the adapter slot rather than assuming it is empty.
;; This ns runs in the shared node bundle beside suites that seat Reagent /
;; UIx / plain-atom, so a bare `init!` would meet whichever adapter one of
;; them left seated. Destroy first, seat the adapter this ns NAMES, and
;; destroy again on the way out.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

(def ^:private counter (atom 0))

(defn- fresh-id [prefix]
  (keyword "rf.ssrhm" (str prefix (swap! counter inc))))

(defn- fresh-frame!
  "A server frame under an id no other test in this shared process has
  used, carrying `doc` as its `:doc` so `default-head` has something to
  roll into `:title`."
  [doc]
  (let [fid (fresh-id "f")]
    (rf/make-frame {:id fid :platform :server :doc doc})
    fid))

;; ===========================================================================
;; The collision guard
;; ===========================================================================

(deftest head-model-and-the-head-namespace-coexist
  ;; `rf.ssr/head-model` is exercised by every test below; this reads a var
  ;; through the `re-frame.ssr.head` namespace object a clobber would replace.
  (is (ifn? rf.ssr.head/reg-head)))

;; ===========================================================================
;; The selection rule, on this host
;; ===========================================================================

(deftest head-model-selects-the-explicit-head-id
  (let [hid (fresh-id "head")
        f   (fresh-frame! "explicit")]
    (rf/reg-head hid (fn [_db _route] {:title "explicit"}))
    (is (= {:title "explicit"} (rf.ssr/head-model f {:head-id hid})))))

(deftest head-model-falls-back-to-default-head
  (let [f (fresh-frame! "Fallback doc")]
    (testing "no :head-id and no route → default-head, which rolls the
              frame's :doc into :title"
      (let [model (rf.ssr/head-model f)]
        (is (= "Fallback doc" (:title model)))
        (is (some #(= "viewport" (:name %)) (:meta model)))))))

(deftest head-model-route-preview-selects-and-evaluates-against-that-route
  (let [hid (fresh-id "echo")
        rid (fresh-id "route")
        f   (fresh-frame! "preview")]
    (rf/reg-head hid (fn [_db route] {:title (str (:route-id route))}))
    (rf/reg-route rid {:head hid} (str "/" (name rid)))
    (testing "a {:route r} with NO :head-id selects r's :head AND runs it
              against r — one effective route, both uses"
      (is (= {:title (str rid)}
             (rf.ssr/head-model f {:route {:route-id rid}}))))))

(deftest head-model-refuses-an-unregistered-head-id
  (let [f (fresh-frame! "missing")]
    (is (= :rf.error/no-such-head
           (try (rf.ssr/head-model f {:head-id (fresh-id "never")})
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:rf.error/id (ex-data e))))))))

(deftest head-model-refuses-a-nil-frame
  (testing "EP-0002 — the frame is carried; an absent stamp is an error,
            not a synthesised :rf/default"
    (is (= :rf.error/no-frame-context
           (try (rf.ssr/head-model nil)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:rf.error/id (ex-data e))))))))
