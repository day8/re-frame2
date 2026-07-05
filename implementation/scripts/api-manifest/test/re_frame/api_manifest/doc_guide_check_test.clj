(ns re-frame.api-manifest.doc-guide-check-test
  "Regression tests for the docs/core projection check's FILE-SCOPED
  removed-name allowlist (rf2-hk6wd2).

  THE BUG. `:doc-guide-known-unmanifested` was a BARE-NAME set: a name
  listed there was silenced ANYWHERE in the guide tree. EP-0017 removed
  `inject-cofx` and live teaching prose must use `:rf.cofx/requires` — but
  because `inject-cofx` was on the bare allowlist for the from-v1 migration
  chapter, a future live `(rf/inject-cofx …)` in ANY teaching chapter would
  have passed the gate green.

  THE FIX. The allowlist is now `:doc-guide-known-unmanifested-scoped` —
  `{removed-name -> #{approved repo-relative file paths}}`. A call-position
  reference to a removed name is silenced ONLY in its approved migration
  file(s); the SAME reference in any other guide file is RED. These tests
  pin that contract through the pure `reconcile` reconciler with synthetic
  references, plus a live smoke that the committed guide reconciles clean."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.doc-guide-check :as c]
            [re-frame.api-manifest.gen :as gen]))

(def ^:private core-vars #{"reg-event" "reg-sub" "dispatch"})

(def ^:private scoped-allow
  {"inject-cofx" #{"docs/core/25-from-re-frame-v1.md"
                   "docs/core/concepts/effects-and-coeffects.md"}
   "reg-event-db" #{"docs/core/25-from-re-frame-v1.md"}})

(defn- problems-for [references]
  (c/reconcile {:references references :core-vars core-vars
                :scoped-allow scoped-allow}))

(deftest core-var-reference-resolves
  (testing "a live re-frame.core var resolves anywhere with no problem"
    (is (empty? (problems-for
                  [{:var "reg-event" :line 1 :raw "rf/reg-event"
                    :file "docs/core/concepts/events.md"}])))))

(deftest removed-name-in-approved-file-is-silenced
  (testing "a removed name in its approved migration file is silenced"
    (is (empty? (problems-for
                  [{:var "inject-cofx" :line 112 :raw "rf/inject-cofx"
                    :file "docs/core/25-from-re-frame-v1.md"}
                   {:var "inject-cofx" :line 121 :raw "rf/inject-cofx"
                    :file "docs/core/concepts/effects-and-coeffects.md"}])))))

(deftest removed-name-in-live-teaching-file-is-red
  (testing "THE BUG (rf2-hk6wd2): a removed name in a NON-approved teaching
            file is flagged — the bare global allowlist would have passed it"
    (let [probs (problems-for
                  [{:var "inject-cofx" :line 50 :raw "rf/inject-cofx"
                    :file "docs/core/concepts/interceptors.md"}])]
      (is (= 1 (count probs)))
      (is (= "docs/core/concepts/interceptors.md" (:file (first probs))))
      (is (re-find #"removed API named outside its approved" (:detail (first probs)))))))

(deftest scope-is-per-name-not-shared
  (testing "a name's scope does not leak to another name's approved file —
            reg-event-db is approved only in the from-v1 chapter"
    (is (empty? (problems-for
                  [{:var "reg-event-db" :line 48 :raw "rf/reg-event-db"
                    :file "docs/core/25-from-re-frame-v1.md"}])))
    (let [probs (problems-for
                  [{:var "reg-event-db" :line 1 :raw "rf/reg-event-db"
                    :file "docs/core/concepts/effects-and-coeffects.md"}])]
      (is (= 1 (count probs))
          "reg-event-db is NOT approved in effects-and-coeffects.md"))))

(deftest unknown-non-manifest-name-is-red
  (testing "a name that is neither a core var nor on the scoped allowlist is
            an unresolved reference"
    (let [probs (problems-for
                  [{:var "totally-gone" :line 1 :raw "rf/totally-gone"
                    :file "docs/core/concepts/x.md"}])]
      (is (= 1 (count probs)))
      (is (re-find #"no re-frame.core manifest row" (:detail (first probs)))))))

(deftest live-doc-guide-reconciles-clean
  (testing "the committed docs/core reconciles against the committed manifest
            + scoped allowlist with zero problems (the CI contract)"
    (is (true? (c/check!))
        "live drift: docs/core names removed APIs outside approved files")))

(deftest scoped-allowlist-sidecar-key-is-present-and-scopes-inject-cofx
  (testing "the committed sidecar carries the file-scoped allowlist and scopes
            inject-cofx to exactly the two approved migration files"
    (let [scoped (:doc-guide-known-unmanifested-scoped (gen/read-sidecar))]
      (is (map? scoped) "the scoped allowlist must be a {name -> #{files}} map")
      (is (= #{"docs/core/25-from-re-frame-v1.md"
               "docs/core/concepts/coeffects.md"}
             (get scoped "inject-cofx"))
          "inject-cofx must be scoped to the from-v1 chapter + the cofx callout"))))
