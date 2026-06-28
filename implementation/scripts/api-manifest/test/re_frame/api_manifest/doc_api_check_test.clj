(ns re-frame.api-manifest.doc-api-check-test
  "Regression tests for the human-doc API-reference projection check
  (rf2-vzupmg).

  THE GAP. The keystone manifest drift-check and its existing projection
  checks scan spec/API.md, docs/core/**, the two tool API specs, and
  skills/ — but NOT the three human-facing API-reference trees
  (spec/Privacy.md, docs/core/api/**, docs/story/api/**). EP-0025 stale prose
  slipped into exactly those trees and passed every CI check. This check
  extends the same call-position discipline to them.

  THE CONTRACT these tests pin (through the pure `reconcile` reconciler with
  synthetic references, plus a live smoke that the committed trees reconcile
  clean):
    * a live manifest var resolves anywhere (no problem);
    * an unknown / removed name with no manifest row is flagged;
    * a file-scoped removed name is silenced ONLY in its approved file(s),
      and RED elsewhere (mirroring :doc-guide-known-unmanifested-scoped)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.doc-api-check :as c]
            [re-frame.api-manifest.gen :as gen]))

(def ^:private manifest-vars
  ;; A small synthetic stand-in spanning several namespaces, exercising the
  ;; bare-name latitude: `configure!` lives on re-frame.story, `machine-meta`
  ;; on re-frame.machines, the rest on re-frame.core — all resolve by name.
  #{"reg-event" "reg-sub" "dispatch" "configure!" "reg-story" "machine-meta"})

(def ^:private scoped-allow
  {"reg-sub-raw" #{"docs/core/api/15-removed.md"}})

(defn- problems-for [references]
  (c/reconcile {:references references
                :manifest-vars manifest-vars
                :scoped-allow scoped-allow}))

(deftest live-manifest-var-resolves-anywhere
  (testing "a live manifest var resolves in any reference tree with no problem"
    (is (empty? (problems-for
                  [{:var "reg-event" :line 13 :raw "rf/reg-event"
                    :file "docs/core/api/01-core.md"}
                   {:var "configure!" :line 7 :raw "story/configure!"
                    :file "docs/story/api/registration.md"}
                   {:var "machine-meta" :line 259 :raw "rf/machine-meta"
                    :file "docs/core/api/10-testing.md"}
                   {:var "reg-event" :line 88 :raw "rf/reg-event"
                    :file "spec/Privacy.md"}])))))

(deftest removed-name-with-no-manifest-row-is-red
  (testing "a removed / renamed / never-manifested name in live reference
            prose is flagged"
    (let [probs (problems-for
                  [{:var "path" :line 85 :raw "rf/path"
                    :file "docs/core/api/03-effects.md"}])]
      (is (= 1 (count probs)))
      (is (= "docs/core/api/03-effects.md" (:file (first probs))))
      (is (re-find #"no manifest row" (:detail (first probs)))))))

(deftest scoped-removed-name-silenced-only-in-approved-file
  (testing "a scoped removed name is silenced in its approved tombstone file"
    (is (empty? (problems-for
                  [{:var "reg-sub-raw" :line 26 :raw "rf/reg-sub-raw"
                    :file "docs/core/api/15-removed.md"}]))))
  (testing "the SAME scoped removed name in a NON-approved live reference file
            is RED — it leaked into live reference prose"
    (let [probs (problems-for
                  [{:var "reg-sub-raw" :line 50 :raw "rf/reg-sub-raw"
                    :file "docs/core/api/01-core.md"}])]
      (is (= 1 (count probs)))
      (is (= "docs/core/api/01-core.md" (:file (first probs))))
      (is (re-find #"removed API named outside its approved" (:detail (first probs)))))))

(deftest live-doc-api-reconciles-clean
  (testing "the committed spec/Privacy.md + docs/core/api + docs/story/api
            reconcile against the committed manifest with zero problems
            (the CI contract — #4887/#4888 cleaned these trees + this PR
            corrected the residual path/unwrap drift the gate surfaced)"
    (is (true? (c/check!))
        "live drift: a human-doc API-reference tree names a removed/renamed public surface")))

(deftest scoped-allowlist-sidecar-key-is-present-and-a-map
  (testing "the committed sidecar carries the file-scoped allowlist key as a
            map (empty today; the tombstone uses bare-backtick names, not
            call-position forms, so no entry is needed)"
    (let [scoped (:doc-api-known-unmanifested-scoped (gen/read-sidecar))]
      (is (map? scoped)
          "the scoped allowlist must be a {name -> #{files}} map"))))
