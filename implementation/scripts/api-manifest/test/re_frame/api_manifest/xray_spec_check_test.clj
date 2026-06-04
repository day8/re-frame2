(ns re-frame.api-manifest.xray-spec-check-test
  "Regression tests for the Xray API-spec projection check (rf2-0u8kz).

  The bug: a fully-qualified `day8.re-frame2-xray.*/<var>` symbol was
  resolved by BARE var name, so a stale / renamed / never-manifested panel
  namespace (e.g. `day8.re-frame2-xray.panels.views/Panel`) falsely passed
  as long as ANY Xray namespace still exported a var of that bare name
  (`Panel` is carried for ten distinct panel namespaces). These tests pin
  the strict `[namespace var]` resolution that fixes it — driven through
  the pure `reconcile` reconciler with synthetic inputs — plus a live
  smoke that the committed spec + manifest still reconcile clean."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.gen :as gen]
            [re-frame.api-manifest.projection :as proj]
            [re-frame.api-manifest.xray-spec-check :as xray]))

;; A minimal synthetic manifest: two distinct panel namespaces, each
;; exporting the SAME bare var `Panel`, plus a bare-var mount fn. This is
;; the exact ambiguity the manifest has in reality (ten `Panel` rows).
(def ^:private synthetic-rows
  [{:namespace "day8.re-frame2-xray.panels.trace"        :var "Panel"}
   {:namespace "day8.re-frame2-xray.panels.epoch-panel"  :var "Panel"}
   {:namespace "day8.re-frame2-xray.panels"              :var "mount-trace!"}
   ;; A non-Xray row that must never satisfy an Xray reference.
   {:namespace "re-frame.core"                           :var "Panel"}])

(defn- problems-for
  "Run `reconcile` over `qualified-refs` / `bare-refs` against the
   synthetic manifest with the given allowlists."
  [{:keys [qualified-refs bare-refs qualified-allow bare-allow]
    :or   {qualified-refs [] bare-refs [] qualified-allow #{} bare-allow #{}}}]
  (xray/reconcile {:rows            synthetic-rows
                   :qualified-refs  qualified-refs
                   :bare-refs       bare-refs
                   :qualified-allow qualified-allow
                   :bare-allow      bare-allow
                   :rel             "tools/xray/spec/API.md"}))

(deftest qualified-symbol-resolves-by-exact-ns+var
  (testing "a live fully-qualified panel symbol resolves clean"
    (is (empty? (problems-for
                  {:qualified-refs [{:ns "day8.re-frame2-xray.panels.trace"
                                     :var "Panel" :line 1
                                     :raw "day8.re-frame2-xray.panels.trace/Panel"}]}))
        "panels.trace/Panel is a manifest [ns var] row — must pass")))

(deftest stale-namespace-with-shared-bare-var-is-rejected
  (testing "THE BUG (rf2-0u8kz): a stale/unmanifested Xray namespace whose
            bare var exists elsewhere must NOT pass"
    (let [problems (problems-for
                     {:qualified-refs [{:ns "day8.re-frame2-xray.panels.views"
                                        :var "Panel" :line 42
                                        :raw "day8.re-frame2-xray.panels.views/Panel"}]})]
      (is (= 1 (count problems))
          "panels.views/Panel is NOT in the manifest — must be flagged even
           though `Panel` is carried for panels.trace + panels.epoch-panel")
      (is (= "day8.re-frame2-xray.panels.views/Panel" (:raw (first problems))))
      (is (= 42 (:line (first problems)))))))

(deftest non-xray-namespace-never-satisfies-xray-reference
  (testing "a non-Xray manifest row sharing the bare var does not resolve an
            Xray-qualified reference"
    ;; re-frame.core/Panel exists in synthetic-rows but is not an Xray ns.
    (is (= 1 (count (problems-for
                      {:qualified-refs [{:ns "day8.re-frame2-xray.panels.missing"
                                         :var "Panel" :line 7
                                         :raw "day8.re-frame2-xray.panels.missing/Panel"}]}))))))

(deftest qualified-allowlist-is-keyed-by-ns+var
  (testing "an allowlisted [ns var] qualified reference passes; the same bare
            var on a DIFFERENT namespace still fails (no bare-name masking)"
    (let [allow #{["day8.re-frame2-xray.panels.views" "Panel"]}]
      (is (empty? (problems-for
                    {:qualified-refs [{:ns "day8.re-frame2-xray.panels.views"
                                       :var "Panel" :line 1
                                       :raw "day8.re-frame2-xray.panels.views/Panel"}]
                     :qualified-allow allow}))
          "the exact [ns var] is allowlisted — passes")
      (is (= 1 (count (problems-for
                        {:qualified-refs [{:ns "day8.re-frame2-xray.panels.gone"
                                           :var "Panel" :line 1
                                           :raw "day8.re-frame2-xray.panels.gone/Panel"}]
                         :qualified-allow allow})))
          "a DIFFERENT namespace with the same bare var is NOT covered by the
           [ns var] allowlist entry"))))

(deftest bare-mount-references-resolve-by-bare-var
  (testing "a bare mount-*! reference resolves against the bare-var index"
    (is (empty? (problems-for
                  {:bare-refs [{:var "mount-trace!" :line 1 :raw "mount-trace!"}]})))
    (testing "and an unmanifested bare mount name is flagged unless allowlisted"
      (is (= 1 (count (problems-for
                        {:bare-refs [{:var "mount-gone!" :line 1 :raw "mount-gone!"}]}))))
      (is (empty? (problems-for
                    {:bare-refs   [{:var "mount-gone!" :line 1 :raw "mount-gone!"}]
                     :bare-allow  #{"mount-gone!"}}))))))

(deftest live-spec-and-manifest-reconcile-clean
  (testing "the committed tools/xray/spec/API.md reconciles against the
            committed manifest with zero problems (the CI contract)"
    (let [rows            (proj/manifest-rows)
          sidecar         (gen/read-sidecar)
          qualified-allow (set (map vec (:xray-spec-known-unmanifested-qualified sidecar)))
          bare-allow      (set (:xray-spec-known-unmanifested sidecar))
          file            (proj/repo-file "tools" "xray" "spec" "API.md")
          rel             (proj/repo-relative file)
          lines           (proj/numbered-lines file)
          qualified-refs  (distinct (proj/qualified-symbol-references
                                      "day8.re-frame2-xray." lines))
          bare-refs       (distinct (xray/mount-references lines))
          problems        (xray/reconcile {:rows            rows
                                           :qualified-refs  qualified-refs
                                           :bare-refs       bare-refs
                                           :qualified-allow qualified-allow
                                           :bare-allow      bare-allow
                                           :rel             rel})]
      (is (pos? (count qualified-refs))
          "the spec must actually name fully-qualified Xray symbols")
      (is (empty? problems)
          (str "live drift: " (pr-str problems))))))
