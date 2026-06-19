(ns re-frame.migration-cljs-test
  "EP-0023 §Backwards Compatibility — the MIGRATION SHIMS + DIAGNOSTICS slice
  (rf2-32siq3.11). Pins the developer-facing migration surface EP-0023's public
  model move produces:

    * `migration-map` / `migration-explain` (the facade re-exports of
      `re-frame.migration/migration-map` / `explain`) carry the EP-0013 ->
      EP-0023 surface dispositions as inspectable DATA — one entry per
      superseded / re-expressed / retained-internal public name, each pointing
      at its EP-0023 replacement vocabulary;
    * `assert-process-local-frame-id!` is the actionable cross-realm
      DUPLICATE-FRAME-ID migration diagnostic: a frame id already live in a
      DIFFERENT realm (the EP-0013 `(realm, frame)` addressing affordance) is
      rejected fail-loud `:rf.error/cross-realm-frame-id` under the EP-0023
      process-local frame-id space, while re-asserting the SAME realm (the
      in-place re-registration case) does NOT collide;
    * `rf/make-frame` is the ONE EP-0024 public constructor (EP-0024 collapse,
      rf2-tu2vr7): the facade exports exactly one `make-frame`, which returns the
      live frame VALUE (not the EP-0013 gensym keyword id). It accepts BOTH
      image-selection AND record-config opts in one call — a record-config key
      (`:on-create` / `:doc` / `:preset` / …) is now HONOURED, not rejected. This
      REVERSES the rf2-32siq3.45 option-(b) fail-loud redirect
      (`:rf.error/make-frame-record-only-key`), which is GONE: the unified frame
      value backed by the ONE registry removes the two-constructor split that
      motivated the redirect (EP-0024 option-(a)).

  Each fail-loud assertion checks the `:rf.error/id` discriminator (NEVER the
  message bytes — Spec 009 §The thrown-error shape rule 3).

  The cross-realm case builds the EP-0013 `(realm, frame)` scenario the same way
  `realm-conformance-cljs-test` does: `av/install!` an app carrying a `:frames`
  section into two constructed realms so the SAME frame id is a real frame in
  each. `.cljc` ends `-cljs-test` so it rides `npm run test:cljs` AND
  `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core       :as rf]
            [re-frame.migration  :as migration]
            [re-frame.live-frame :as lf]
            [re-frame.image      :as image]
            [re-frame.realm      :as realm]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Fixture — mirror realm-conformance-cljs-test: reset the runtime, and drop any
;; leftover non-default realms (the realm registry is process-global state) so a
;; constructed realm never leaks between cases.
;; ---------------------------------------------------------------------------

(defn- drop-non-default-realms! []
  (swap! realm/realms select-keys [realm/default-realm-id])
  (swap! realm/realms update realm/default-realm-id dissoc :app :capabilities))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [test-fn]
    (drop-non-default-realms!)
    (test-fn)
    (drop-non-default-realms!)))


;; ---------------------------------------------------------------------------
;; migration-map / migration-explain — the surface dispositions as data
;; ---------------------------------------------------------------------------

(deftest migration-map-covers-the-superseded-ep-0013-surfaces
  (testing "every EP-0013 public name EP-0023 touches has a disposition entry
            with a status + replacement + guidance"
    (doseq [surface [:rf/app :rf/module :rf/install! :rf/reinstall!
                     :rf/installed-app :rf/realm
                     :rf.realm/frame-address :rf.realm/scoped-query]]
      (let [entry (get migration/migration-map surface)]
        (is (some? entry) (str surface " has a migration disposition"))
        (is (contains? #{:publicly-replaced :re-expressed :retained-internal}
                       (:status entry))
            (str surface " carries a known EP-0023 disposition status"))
        (is (keyword? (:replacement entry))
            (str surface " names an EP-0023 replacement surface"))
        (is (and (string? (:guidance entry)) (seq (:guidance entry)))
            (str surface " carries a human guidance sentence"))))))

(deftest migration-explain-returns-the-guidance-string
  (testing "explain reads the guidance for a known surface and nil for unknown"
    (is (= (get-in migration/migration-map [:rf/app :guidance])
           (migration/explain :rf/app))
        "explain returns the :rf/app guidance verbatim")
    (is (re-find #"rf/image" (migration/explain :rf/app))
        "the :rf/app guidance points at the rf/image replacement")
    (is (re-find #"image fragment" (migration/explain :rf/module))
        "the :rf/module guidance points at the image-fragment re-expression")
    (is (re-find #"frame" (migration/explain :rf.realm/frame-address))
        "the (realm, frame) address guidance points at the frame target")
    (is (nil? (migration/explain :rf/not-a-surface))
        "an unknown surface returns nil")))

;; ---------------------------------------------------------------------------
;; assert-process-local-frame-id! — the cross-realm duplicate-frame-id diagnostic
;; ---------------------------------------------------------------------------

;; NOTE (rf2-afdlyr / rf2-70owfr): the cross-realm + same-realm `frame-id-realms`
;; / `assert-process-local-frame-id!` cases that stood up non-default realms via
;; `construct-realm` + `av/install!` were removed with the realm-substrate
;; collapse — non-default realms can no longer be constructed, so the only
;; reachable case is the default realm (a fresh id is live in no realm).
;; rf2-70owfr removed the per-frame realm-membership view; `frame-id-realms` now
;; reads the live frame registry directly (a live frame is in the default realm,
;; an unregistered id is in no realm).

(deftest a-fresh-frame-id-passes-the-process-local-assertion
  (testing "a frame id live in no realm passes the process-local assertion"
    (is (= #{} (migration/frame-id-realms :mig/never-live))
        "an unregistered id is live in no realm")
    (is (= :mig/never-live (migration/assert-process-local-frame-id! :mig/never-live))
        "a fresh id passes and is returned")
    (is (= :mig/never-live (migration/assert-process-local-frame-id! :mig/never-live :mig/x))
        "a fresh id passes for any target realm")))

;; ---------------------------------------------------------------------------
;; The ONE EP-0024 constructor (EP-0024 collapse, rf2-tu2vr7): rf/make-frame is
;; the single public constructor. The facade var resolves to the unified path
;; that accepts BOTH image-selection AND record-config opts and returns the live
;; frame VALUE. Asserted against the real facade.
;; ---------------------------------------------------------------------------

(deftest facade-make-frame-is-the-ep-0024-value-constructor
  (testing "the live facade exports exactly one make-frame — the EP-0024 ONE
            constructor. rf/make-frame returns the live frame VALUE, NOT a gensym
            keyword id. Built against an explicit descriptor pool (the 2-arity) so
            the pin does not project the live source store."
    (let [pool    [{:rf.provenance/ns "examples.counter"
                    :kind :event :id :counter/inc :handler-fn ::inc}]
          img     (image/image {:include-ns ["examples.counter"]})
          created (rf/make-frame {:images [img]} pool)]
      (is (lf/frame-object? created)
          "rf/make-frame returns the EP-0024 live frame value")
      (is (not (keyword? created))
          "it is NOT the EP-0013 keyword id anymore")
      (rf/destroy-frame! created)))
  (testing "a record-config key is now HONOURED in the same make-frame call
            (EP-0024 option-(a)) — REVERSING the rf2-32siq3.45 option-(b) fail-loud
            redirect (:rf.error/make-frame-record-only-key is GONE). The unified
            constructor passes record-config verbatim to the frame record, so
            :doc / :preset land on the frame's metadata, no throw, no silent drop."
    (let [pool    [{:rf.provenance/ns "examples.counter"
                    :kind :event :id :counter/inc :handler-fn ::inc}]
          img     (image/image {:include-ns ["examples.counter"]})
          ;; A record-config key (:doc) mixed with the EP-0024 image-selection
          ;; opts in ONE call: accepted, returns a frame value, config lands.
          created (rf/make-frame {:id :mig/configured
                                  :images [img]
                                  :doc "honoured record-config"}
                                 pool)]
      (is (lf/frame-object? created)
          "a record-config key is accepted (no throw) and a frame value returns")
      (is (= :mig/configured (rf/frame-value->id created)))
      (is (= "honoured record-config" (:doc (rf/frame-meta :mig/configured)))
          "the :doc record-config key landed on the frame's metadata")
      (rf/destroy-frame! created))
    (testing ":preset is likewise honoured (not redirected) by the unified
              constructor — the :default preset expands to a no-op config"
      (let [created (rf/make-frame {:id :mig/preset :preset :default})]
        (is (lf/frame-object? created)
            ":preset is an accepted record-config key, no fail-loud redirect")
        (is (= :default (:preset (rf/frame-meta :mig/preset)))
            "the :preset key is preserved on the frame metadata")
        (rf/destroy-frame! created)))))
