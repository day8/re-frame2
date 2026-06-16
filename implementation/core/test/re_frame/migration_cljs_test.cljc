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
    * `assert-no-dual-make-frame!` is the transition-window guard: BOTH the
      EP-0013 record `make-frame` and the EP-0023 object `make-frame` exported
      under one public name fail-loud `:rf.error/make-frame-dual-export`, while
      at most one export is the safe window state.

  Each fail-loud assertion checks the `:rf.error/id` discriminator (NEVER the
  message bytes — Spec 009 §The thrown-error shape rule 3).

  The cross-realm case builds the EP-0013 `(realm, frame)` scenario the same way
  `realm-conformance-cljs-test` does: `rf/install!` an app carrying a `:frames`
  section into two constructed realms so the SAME frame id is a real frame in
  each. `.cljc` ends `-cljs-test` so it rides `npm run test:cljs` AND
  `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core      :as rf]
            [re-frame.migration :as migration]
            [re-frame.realm     :as realm]
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
;; Helpers
;; ---------------------------------------------------------------------------

(defn- err-id
  "Run `thunk` and return the `:rf.error/id` of the thrown ex-info, or nil if it
  did not throw. Discriminator-only (never asserts message bytes)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- app-with-frame
  "An EP-0013 app value carrying a single `:frames` section for `frame-id` — the
  shape `install!` lowers into a realm-owned `:frame` descriptor (mirrors
  realm-conformance-cljs-test's `app-for`)."
  [frame-id]
  (rf/app {:id :mig/app
           :modules [(rf/module {:id     :mig/m
                                 :frames {frame-id {:doc "migration shared id"}}})]}))

;; ---------------------------------------------------------------------------
;; migration-map / migration-explain — the surface dispositions as data
;; ---------------------------------------------------------------------------

(deftest migration-map-covers-the-superseded-ep-0013-surfaces
  (testing "every EP-0013 public name EP-0023 touches has a disposition entry
            with a status + replacement + guidance"
    (doseq [surface [:rf/app :rf/module :rf/install! :rf/reinstall!
                     :rf/installed-app :rf/realm
                     :rf.realm/frame-address :rf.realm/scoped-query]]
      (let [entry (get rf/migration-map surface)]
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
    (is (= (get-in rf/migration-map [:rf/app :guidance])
           (rf/migration-explain :rf/app))
        "explain returns the :rf/app guidance verbatim")
    (is (re-find #"rf/image" (rf/migration-explain :rf/app))
        "the :rf/app guidance points at the rf/image replacement")
    (is (re-find #"image fragment" (rf/migration-explain :rf/module))
        "the :rf/module guidance points at the image-fragment re-expression")
    (is (re-find #"frame" (rf/migration-explain :rf.realm/frame-address))
        "the (realm, frame) address guidance points at the frame target")
    (is (nil? (rf/migration-explain :rf/not-a-surface))
        "an unknown surface returns nil")))

;; ---------------------------------------------------------------------------
;; assert-process-local-frame-id! — the cross-realm duplicate-frame-id diagnostic
;; ---------------------------------------------------------------------------

(deftest cross-realm-frame-id-is-a-fail-loud-migration-break
  (testing "EP-0023 §Id Spaces: a frame id already live in ANOTHER realm under
            EP-0013 (realm, frame) addressing is rejected by the EP-0023
            process-local frame-id contract"
    (let [ra (rf/realm {:id :mig/a})
          rb (rf/realm {:id :mig/b})]
      (try
        (rf/install! ra (app-with-frame :mig/shared))
        (rf/install! rb (app-with-frame :mig/shared))
        ;; The same frame id is now live in BOTH :mig/a and :mig/b (the EP-0013
        ;; affordance the EP-0023 model removes).
        (is (= #{:mig/a :mig/b} (migration/frame-id-realms :mig/shared))
            "the shared id is live in both realms (EP-0013 (realm, frame) state)")
        ;; Asserting the EP-0023 process-local contract for a NEW realm target
        ;; surfaces the collision the new model forbids.
        (is (= :rf.error/cross-realm-frame-id
               (err-id #(rf/assert-process-local-frame-id! :mig/shared :mig/c)))
            "a frame id live in other realms fails the process-local assertion")
        ;; The ex-data names the conflicting realms (actionable diagnostic).
        (let [data (try (rf/assert-process-local-frame-id! :mig/shared :mig/c)
                        nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core/ExceptionInfo) e
                          (ex-data e)))]
          (is (= #{:mig/a :mig/b} (set (:other-realms data)))
              "the ex-data names every realm the id is already live in"))
        (finally
          (rf/dispose-realm! :mig/a)
          (rf/dispose-realm! :mig/b))))))

(deftest same-realm-reassertion-is-not-a-collision
  (testing "re-asserting a frame id in the realm it already lives in is the
            in-place case, not a cross-realm collision"
    (let [ra (rf/realm {:id :mig/solo})]
      (try
        (rf/install! ra (app-with-frame :mig/only))
        (is (= #{:mig/solo} (migration/frame-id-realms :mig/only))
            "the id is live only in :mig/solo")
        ;; Targeting the SAME realm — the id is allowed to remain live there.
        (is (= :mig/only (rf/assert-process-local-frame-id! :mig/only :mig/solo))
            "re-asserting in the same realm returns the id (no collision)")
        (finally
          (rf/dispose-realm! :mig/solo))))))

(deftest a-fresh-frame-id-passes-the-process-local-assertion
  (testing "a frame id live in no realm passes the process-local assertion"
    (is (= #{} (migration/frame-id-realms :mig/never-live))
        "an unregistered id is live in no realm")
    (is (= :mig/never-live (rf/assert-process-local-frame-id! :mig/never-live))
        "a fresh id passes and is returned")
    (is (= :mig/never-live (rf/assert-process-local-frame-id! :mig/never-live :mig/x))
        "a fresh id passes for any target realm")))

;; ---------------------------------------------------------------------------
;; assert-no-dual-make-frame! — the transition-window guard
;; ---------------------------------------------------------------------------

(deftest dual-make-frame-export-fails-loud
  (testing "EP-0023 transition window (bead rf2-32siq3.11 NOTES): both the
            record and object make-frame exported under one name is rejected"
    ;; Stand-ins for the two exported constructors — identity is all the guard
    ;; checks (presence of both).
    (let [record-export (fn [_config] :rf.frame/some-id)
          object-export  (fn [_opts] {:rf.frame/object true})]
      (is (= :rf.error/make-frame-dual-export
             (err-id #(migration/assert-no-dual-make-frame! record-export object-export)))
          "both exports present fails loud")
      (is (migration/make-frame-export-conflict? record-export object-export)
          "the pure predicate agrees both-present is a conflict"))))

(deftest single-or-zero-make-frame-export-is-the-safe-window
  (testing "at most one make-frame export is the safe window state — no throw"
    (let [record-export (fn [_config] :rf.frame/some-id)
          object-export  (fn [_opts] {:rf.frame/object true})]
      (is (nil? (migration/assert-no-dual-make-frame! record-export nil))
          "only the record export — safe")
      (is (nil? (migration/assert-no-dual-make-frame! nil object-export))
          "only the object export — safe")
      (is (nil? (migration/assert-no-dual-make-frame! nil nil))
          "neither exported — safe")
      (is (not (migration/make-frame-export-conflict? record-export nil))
          "one export is not a conflict")
      (is (not (migration/make-frame-export-conflict? nil nil))
          "no export is not a conflict"))))

;; ---------------------------------------------------------------------------
;; The live facade exports exactly ONE make-frame during the window (the guard's
;; invariant, asserted against the real facade var rather than stand-ins).
;; ---------------------------------------------------------------------------

(deftest facade-make-frame-is-the-ep-0013-record-constructor-only
  (testing "the live facade exports exactly one make-frame (the EP-0013 record
            constructor) during the transition window — make-frame returns a
            gensym id, NOT the EP-0023 object"
    (let [created (rf/make-frame {})]
      (is (keyword? created)
          "rf/make-frame returns a gensym frame id (the EP-0013 record path)")
      ;; The EP-0023 object constructor is NOT facade-exported under make-frame,
      ;; so the facade var is unambiguous — the guard's window invariant holds.
      (rf/destroy-frame! created))))
