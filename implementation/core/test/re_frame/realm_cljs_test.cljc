(ns re-frame.realm-cljs-test
  "EP-0013 D1 internal staging 1-3 (rf2-gkddyq) — the runtime realm record,
  the default realm, the realm-owned registrar, and the frame's realm
  reference.

  D1 is INTERNAL: no public `rf/realm` constructor and no realm-targeted
  public query ship. The headline acceptance is **zero ergonomic regression**
  — everything routes through one default realm so a single-realm app's
  surface is byte-identical. These tests pin:

    (1) the default realm exists with id `:rf.realm/default` and the shipped
        D1 record shape (Spec-Schemas §`:rf/realm`);
    (2) the realm OWNS the registrar — the default realm's `:registrar` slot
        IS the existing process-global `registrar/kind->id->metadata` atom,
        so the registry shape + read/write API are unchanged
        (default-realm transparency: `reg-*` / dispatch / subscribe behave
        exactly as before);
    (3) a frame carries its realm REFERENCE internally (`:realm` slot →
        `:rf.realm/default`); the realm-owned frame-membership VIEW is
        derived live from the frame registry, with no separately-stored set.

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. Pure CLJC — no DOM dependency;
  uses the plain-atom adapter."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; (1) the internal realm record + the default realm
;; ---------------------------------------------------------------------------

(deftest default-realm-exists-with-reserved-id
  (testing "the process creates one default realm with id :rf.realm/default"
    (is (= :rf.realm/default realm/default-realm-id))
    (let [dr (realm/default-realm)]
      (is (some? dr) "default realm exists at process load")
      (is (= :rf.realm/default (:rf.realm/id dr))
          "the default realm carries the reserved :rf.realm/id")
      ;; The default realm is reachable by id through the realm registry.
      (is (identical? dr (realm/realm :rf.realm/default))
          "default-realm and (realm :rf.realm/default) are the same record"))))

(deftest realm-record-shipped-d1-shape
  (testing "the D1 realm record carries the shipped slots and leaves the
            D2/D3-reserved slots absent (Spec-Schemas §:rf/realm)"
    (let [dr (realm/default-realm)]
      ;; Shipped D1 slots.
      (is (contains? dr :rf.realm/id) "carries :rf.realm/id")
      (is (contains? dr :registrar)   "carries the realm-owned :registrar")
      ;; D2/D3-reserved — never required in D1, the container only.
      (is (not (contains? dr :app)) ":app (D2 app value) is absent in D1"))))

(deftest realm-id-reader-absence-is-default
  (testing "realm-id resolves nil → default realm (absence is the documented
            rule), passes a keyword through, and reads the map's id"
    (is (= :rf.realm/default (realm/realm-id nil))
        "absence resolves to the default realm")
    (is (= :tenant/a (realm/realm-id :tenant/a))
        "a realm-id keyword passes through unchanged")
    (is (= :rf.realm/default (realm/realm-id (realm/default-realm)))
        "a realm map resolves to its :rf.realm/id")))

;; ---------------------------------------------------------------------------
;; (2) the realm owns the registrar — default-realm transparency
;; ---------------------------------------------------------------------------

(deftest default-realm-owns-the-process-registrar
  (testing "the default realm's :registrar IS the existing process-global
            registrar atom — ownership moved to the realm, the shape did not"
    (is (identical? registrar/kind->id->metadata
                    (:registrar (realm/default-realm)))
        "the default realm holds the existing process-global registrar atom")
    (is (identical? registrar/kind->id->metadata
                    (realm/registrar (realm/default-realm)))
        "realm/registrar returns that same atom")
    (is (identical? registrar/kind->id->metadata
                    (realm/registrar nil))
        "realm/registrar nil resolves to the default realm's registrar")))

(deftest registry-read-api-unchanged-for-tooling
  (testing "the registry shape + read API are byte-stable — a registration
            made through the public reg-* surface is visible through the
            existing registrar read API the EP-0014 tooling sibling uses"
    (rf/reg-event-db :realm-test/inc
      {:doc "increment counter"}
      (fn [db _] (update db :n (fnil inc 0))))
    (rf/reg-sub :realm-test/n
      {:doc "the counter"}
      (fn [db _] (:n db)))
    ;; Read API on the process-global registrar — unchanged shape.
    (is (some? (registrar/lookup :event :realm-test/inc))
        "registrar/lookup resolves a default-realm registration")
    (is (contains? (registrar/registrations :event) :realm-test/inc)
        "registrar/registrations exposes the registration")
    (is (fn? (registrar/handler :event :realm-test/inc))
        "registrar/handler returns the handler fn")
    ;; And reading through the realm's owned registrar atom is identical —
    ;; the realm owns the SAME table.
    (is (contains? (get @(realm/registrar (realm/default-realm)) :event)
                   :realm-test/inc)
        "the realm-owned registrar atom carries the same registration")))

(deftest default-realm-transparency-dispatch-subscribe
  (testing "reg-* / dispatch / subscribe work UNCHANGED through the default
            realm — the zero-ergonomic-regression headline"
    (rf/reg-frame :realm-test/app {:doc "transparency app"})
    (rf/reg-event-db :realm-test/set
      {:doc "set n"}
      (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-sub :realm-test/read-n
      {:doc "read n"}
      (fn [db _] (:n db)))
    ;; Dispatch + subscribe against the default-realm frame.
    (rf/dispatch-sync [:realm-test/set 7] {:frame :realm-test/app})
    (is (= {:n 7} (rf/app-db-value :realm-test/app))
        "app-db carries the dispatched value — single-app dispatch unchanged")
    (let [reaction (rf/subscribe :realm-test/app [:realm-test/read-n])]
      (is (= 7 @reaction)
          "subscribe reads the value — single-app subscribe unchanged"))))

;; ---------------------------------------------------------------------------
;; (3) a frame carries its realm reference internally
;; ---------------------------------------------------------------------------

(deftest frame-carries-default-realm-reference
  (testing "a frame stores a realm REFERENCE (the :rf.realm/default id) and
            frame-realm reads it back"
    (rf/reg-frame :realm-test/f {:doc "a frame"})
    (let [record (frame/frame :realm-test/f)]
      (is (= :rf.realm/default (:realm record))
          "the frame record carries the default realm id in its :realm slot")
      (is (= :rf.realm/default (frame/frame-realm :realm-test/f))
          "frame-realm reads the frame's realm reference"))
    (is (nil? (frame/frame-realm :realm-test/never-registered))
        "frame-realm is nil for an unknown frame")))

(deftest realm-owned-frame-membership-view-is-derived
  (testing "the realm-owned frame-membership view is derived live from the
            frame registry — a reg-frame joins it, a destroy-frame! leaves it.
            (The reset fixture installs the ordinary :rf/default frame into the
            default realm, so membership is asserted by joins/leaves of the
            test frames, not by exact-set equality with the fixture frame.)"
    (let [base (realm/realm-frames :rf.realm/default)]
      (is (not (contains? base :realm-test/m1))
          "the test frames are not members before registration")
      (rf/reg-frame :realm-test/m1 {:doc "member 1"})
      (rf/reg-frame :realm-test/m2 {:doc "member 2"})
      (let [members (realm/realm-frames :rf.realm/default)]
        (is (contains? members :realm-test/m1)
            "a reg-frame joins the default realm's membership view")
        (is (contains? members :realm-test/m2)
            "the second frame joins too")
        (is (= members (realm/realm-frames))
            "the zero-arg arity defaults to the default realm"))
      ;; Destroying a frame retracts it from the view (membership is derived,
      ;; so the dissoc from the frame registry is the only step needed).
      (rf/destroy-frame! :realm-test/m1)
      (let [members (realm/realm-frames :rf.realm/default)]
        (is (not (contains? members :realm-test/m1))
            "a destroy-frame! leaves the membership view")
        (is (contains? members :realm-test/m2)
            "the surviving frame is still a member")))
    ;; A realm with no frames is empty, never an error.
    (is (= #{} (realm/realm-frames :tenant/nonexistent))
        "an unknown realm has an empty membership view")))

(deftest frames-by-realm-groups-live-frames-only
  (testing "frames-by-realm groups non-destroyed frames by their :realm slot"
    (rf/reg-frame :realm-test/a {:doc "a"})
    (rf/reg-frame :realm-test/b {:doc "b"})
    (let [by-realm (frame/frames-by-realm)]
      (is (contains? (get by-realm :rf.realm/default) :realm-test/a)
          "default-realm frame a is grouped under :rf.realm/default")
      (is (contains? (get by-realm :rf.realm/default) :realm-test/b)
          "default-realm frame b is grouped under :rf.realm/default"))
    (rf/destroy-frame! :realm-test/a)
    (let [by-realm (frame/frames-by-realm)]
      (is (not (contains? (get by-realm :rf.realm/default) :realm-test/a))
          "a destroyed frame drops out of the grouping")
      (is (contains? (get by-realm :rf.realm/default) :realm-test/b)
          "the surviving frame stays in the grouping"))))
