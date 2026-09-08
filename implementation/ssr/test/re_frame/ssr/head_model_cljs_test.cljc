(ns re-frame.ssr.head-model-cljs-test
  "rf2-kuky.89 — the NAME-COLLISION guard for the one head read.

  The ruling (rf2-kuky.44) proposed spelling the collapsed read
  `(ssr/head …)`. That name is unusable in ClojureScript: `re-frame.ssr`
  requires `re-frame.ssr.head`, so the namespace OBJECT `re_frame.ssr.head`
  — which carries `.registry`, `.emit` and the head ns's own vars — exists
  before `re-frame.ssr`'s body runs, and a `(def head …)` there would emit
  `re_frame.ssr.head = <fn>` straight over it. The analyzer warns
  `:ns-var-clash` and the runtime breaks. The read is therefore named
  `head-model`, pairing with `head-model->html`, and `re-frame.ssr.head`
  is NOT renamed.

  Nothing in the JVM suite can witness that, because the JVM has no
  munged-object namespace representation to clobber. This file is the
  witness on the host where it matters. It ends in `-cljs-test` so the
  shadow `:node-test` build (`:ns-regexp \"cljs-test$\"`) picks it up, and
  it is `.cljc` so the same assertions also run under `clojure -M:test`
  from `implementation/ssr` — a free cross-check that the two hosts agree
  on the selection rule.

  What is pinned, and the first two are the collision guard:

    1. Compiling this namespace at all. It requires BOTH `re-frame.ssr`
       and `re-frame.ssr.head`; a clobbered namespace object is a compile
       -time `:ns-var-clash` warning and a load-time failure, so a
       regression here never reaches an assertion.
    2. AFTER ns load, `re-frame.ssr/head-model` is callable AND
       `re-frame.ssr.head`'s own vars still resolve through the namespace
       object — the two coexist.
    3. The selection rule's clauses, on the CLJS host: an explicit
       `:head-id`, the effective route's `:head`, `default-head`, and the
       `{:route r}` preview evaluating against that same route."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loaded for its ns-load-time registrations — `rf/reg-route`
            ;; reaches the routing artefact through a late-bind hook this
            ;; namespace publishes.
            [re-frame.routing]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.head :as rf.ssr.head]))

;; rf2-qj4g — COLD-START the adapter slot rather than assuming it is empty.
;; This ns runs in the shared node bundle beside suites that seat Reagent /
;; UIx / plain-atom, so a bare `init!` would be a no-op whenever one of them
;; ran first. Destroy first, seat the adapter this ns NAMES, and destroy
;; again on the way out.
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
  (testing "rf2-kuky.89 — `re-frame.ssr/head-model` does not clobber the
            `re-frame.ssr.head` namespace object. Both are reached in ONE
            namespace, after load, and both answer."
    (is (ifn? rf.ssr/head-model)
        "the read on the ssr door is a callable var, not a namespace object")
    (is (ifn? rf.ssr.head/head-model)
        "and the head namespace still resolves its own re-export")
    (is (ifn? rf.ssr.head/reg-head)
        "as do its other vars — the namespace object survived the def")
    (is (ifn? rf.ssr.head/default-head))
    (is (ifn? rf.ssr/head-model->html)
        "the sibling name that shares the `head-model` prefix is unaffected")))

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
             (rf.ssr/head-model f {:route {:route-id rid}}))))
    (testing "an explicit {:route nil} means NO route, so the default fires"
      (is (= "preview" (:title (rf.ssr/head-model f {:route nil})))))))

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
