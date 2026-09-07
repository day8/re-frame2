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
            [re-frame.api-manifest.gen :as rf.api-manifest.gen]
            [re-frame.api-manifest.projection :as rf.api-manifest.projection]
            [re-frame.api-manifest.xray-spec-check :as rf.api-manifest.xray-spec-check]))

;; A minimal synthetic manifest: two distinct panel namespaces, each
;; exporting the SAME bare var `Panel`, plus a bare-var mount fn. This is
;; the exact ambiguity the manifest has in reality (ten `Panel` rows).
(def ^:private synthetic-rows
  [{:namespace "day8.re-frame2-xray.panels.trace"        :var "Panel"}
   {:namespace "day8.re-frame2-xray.panels.epoch-panel"  :var "Panel"}
   {:namespace "day8.re-frame2-xray.panels"              :var "mount-trace!"}
   ;; A non-Xray row that must never satisfy an Xray reference.
   {:namespace "re-frame.core"                           :var "Panel"}
   ;; A re-frame.core facade row, for the rf2-6264 `(rf/<var>` path. It is
   ;; deliberately NOT an Xray-namespaced row: the facade path resolves
   ;; against ALL rows, where the two Xray paths filter to the Xray prefix.
   {:namespace "re-frame.core"                           :var "trace-buffer"}])

(defn- problems-for
  "Run `reconcile` over `qualified-refs` / `bare-refs` / `facade-refs`
   against the synthetic manifest with the given allowlists."
  [{:keys [qualified-refs bare-refs facade-refs qualified-allow bare-allow facade-allow]
    :or   {qualified-refs [] bare-refs [] facade-refs []
           qualified-allow #{} bare-allow #{} facade-allow #{}}}]
  (rf.api-manifest.xray-spec-check/reconcile {:rows            synthetic-rows
                   :qualified-refs  qualified-refs
                   :bare-refs       bare-refs
                   :facade-refs     facade-refs
                   :qualified-allow qualified-allow
                   :bare-allow      bare-allow
                   :facade-allow    facade-allow
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

;; ---------------------------------------------------------------------------
;; The facade path (rf2-6264) — `(rf/<var>` call-position references.
;;
;; The bead: a planted fault in a `(rf/...` form returned exit 0 with the
;; reference count unmoved, because the extractor read only the two
;; Xray-namespace shapes. These pin the third shape so that cannot recur
;; silently.
;; ---------------------------------------------------------------------------

(deftest facade-reference-to-a-live-var-resolves
  (testing "a `(rf/<var>` reference whose bare name any manifest row carries
            resolves clean — resolution is over ALL rows, not the Xray-
            filtered ones, because these name the re-frame CORE facade"
    (is (empty? (problems-for
                  {:facade-refs [{:var "trace-buffer" :line 502
                                  :raw "rf/trace-buffer"}]}))
        "rf/trace-buffer is a re-frame.core manifest row — must pass")))

(deftest facade-reference-to-a-removed-var-is-rejected
  (testing "THE BUG (rf2-6264): a `(rf/<var>` reference naming a renamed /
            removed / never-manifested surface must go RED. This is the exact
            plant the bead reported passing at exit 0."
    (let [problems (problems-for
                     {:facade-refs [{:var "trace-buffer-BOGUSPLANT" :line 502
                                     :raw "rf/trace-buffer-BOGUSPLANT"}]})]
      (is (= 1 (count problems))
          "the planted facade reference must be flagged")
      (is (= 502 (:line (first problems)))
          "the problem must name the planted line")))

  (testing "and a removed name is still caught even though the check filters
            the OTHER two paths to the Xray namespace prefix — a facade var
            deleted repo-wide has no row in any namespace"
    (is (seq (problems-for
               {:facade-refs [{:var "sub-cache" :line 512 :raw "rf/sub-cache"}]}))
        "rf/sub-cache was removed (rf2-80mmlf) — must not resolve")))

(deftest facade-allowlist-silences-a-named-reference
  (testing "an explicitly allowlisted facade name passes"
    (is (empty? (problems-for
                  {:facade-refs  [{:var "sub-cache" :line 512 :raw "rf/sub-cache"}]
                   :facade-allow #{"sub-cache"}})))))

(deftest live-spec-and-manifest-reconcile-clean
  (testing "the committed tools/xray/spec/API.md reconciles against the
            committed manifest with zero problems (the CI contract)"
    (let [rows            (rf.api-manifest.projection/manifest-rows)
          sidecar         (rf.api-manifest.gen/read-sidecar)
          qualified-allow (set (map vec (:xray-spec-known-unmanifested-qualified sidecar)))
          bare-allow      (set (:xray-spec-known-unmanifested sidecar))
          facade-allow    (set (:xray-spec-known-unmanifested-facade sidecar))
          file            (rf.api-manifest.projection/repo-file "tools" "xray" "spec" "API.md")
          rel             (rf.api-manifest.projection/repo-relative file)
          lines           (rf.api-manifest.projection/numbered-lines file)
          qualified-refs  (distinct (rf.api-manifest.projection/qualified-symbol-references
                                      "day8.re-frame2-xray." lines))
          bare-refs       (distinct (rf.api-manifest.xray-spec-check/mount-references lines))
          facade-refs     (distinct (rf.api-manifest.projection/alias-call-references "rf" lines))
          problems        (rf.api-manifest.xray-spec-check/reconcile {:rows            rows
                                           :qualified-refs  qualified-refs
                                           :bare-refs       bare-refs
                                           :facade-refs     facade-refs
                                           :qualified-allow qualified-allow
                                           :bare-allow      bare-allow
                                           :facade-allow    facade-allow
                                           :rel             rel})]
      (is (pos? (count qualified-refs))
          "the spec must actually name fully-qualified Xray symbols")
      (is (pos? (count facade-refs))
          "the spec must actually name `(rf/<var>` facade references — a zero
           here is the vacuous-green shape rf2-6264 was filed about, reached
           through the facade extractor instead of the Xray ones")
      (is (empty? problems)
          (str "live drift: " (pr-str problems))))))
