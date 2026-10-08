(ns re-frame.ssr-safe-redirect-production-test
  "A rejected `:rf.server/safe-redirect` is refused, leaves the wire alone
  (no redirect, no 500), and ships exactly ONE always-on record an off-box
  shipper sees under the production gate. That record is a closed structural
  projection: framework keywords and the frame id only — never the URL, any
  URL component, the raw scheme or host, or the app's own allowlist — so it
  can carry no caller bytes and cannot be inflated or fragmented by the
  caller. Every assertion is posture-independent.

  The dev trace's diagnostics and the URI parse-failure cases live in
  `re-frame.ssr-end-to-end-test`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.egress :as rf.ssr.egress]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; The fixture leaves the always-on listener registry alone: the façade's own
;; projection listener there is what decides whether a rejection becomes a 500.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- reject!
  "Drive `:rf.server/safe-redirect` with `args` on a fresh server frame.
  Returns the frame, every always-on record a shipper stand-in saw, and the
  resolved response."
  [args]
  (let [f    (rf.frame/make-anon-frame-record!
               {:platform :server
                :ssr      {:public-error-id   :rf.ssr/default-error-projector
                           :dev-error-detail? false}})
        id   (keyword "rf.test" (str "cap-" (name (gensym "s"))))
        seen (atom [])]
    (rf.error-emit/register-error-listener! id (fn [record] (swap! seen conj record)))
    (rf/reg-event ::attempt (fn [_ [_ a]] {:fx [[:rf.server/safe-redirect a]]}))
    (rf/dispatch-sync [::attempt args] {:frame f})
    (rf.error-emit/unregister-error-listener! id)
    {:frame    f
     :records  @seen
     :response (rf.ssr/flush-response! f)}))

(defn- safe-redirect-records [records]
  (filter #(str/starts-with? (name (:error %)) "safe-redirect-") records))

(defn- refusal
  "`[redirect status records]` for `args`, each record with `:frame` and
  `:time` reduced to whether they are this frame's id and a clock reading."
  [args]
  (let [{:keys [frame records response]} (reject! args)]
    [(:redirect response)
     (:status response)
     (mapv #(-> % (update :frame = frame) (update :time number?))
           (safe-redirect-records records))]))

(defn- refused-with
  "The `refusal` of a rejected redirect whose one record is `slots` plus the
  framework-owned attribution."
  [slots]
  [nil 200 [(merge {:frame true :time true :recovery :no-recovery} slots)]])

(def ^:private scheme-rejected :rf.error/safe-redirect-scheme-rejected)
(def ^:private invalid-url     :rf.error/safe-redirect-invalid-url)
(def ^:private host-disallowed :rf.error/safe-redirect-host-disallowed)

(deftest each-rejection-arm-refuses-and-ships-one-closed-record
  (testing "every arm refuses with no redirect and no 500, and its one record
            is exactly the closed shape — so no sentinel planted anywhere in
            the input (scheme, userinfo, host label, path, query key,
            allowlist entry) and no oversized input reaches it"
    (doseq [[args slots]
            [[{:location "javascript:alert(1)"}
              {:error scheme-rejected :scheme-class :javascript}]
             [{:location "s3cr3t-probe-scheme:payload"}
              {:error scheme-rejected :reason :scheme-not-allowed :scheme-class :other}]
             [{:location (str "s" (apply str (repeat 20000 "x")) ":payload")}
              {:error scheme-rejected :reason :scheme-not-allowed :scheme-class :other}]
             [{:location "https:s3cr3t-opaque-token.evil.example"}
              {:error invalid-url :reason :scheme-without-host :scheme-class :https}]
             [{:location "https://s3cr3t-session-id.evil.example/x" :relative-only? true}
              {:error host-disallowed :reason :relative-only-violation}]
             [{:location "//evil.example.com/" :relative-only? true}
              {:error host-disallowed :reason :relative-only-violation}]
             [{:location "https://alice:s3cr3t-pw@a.b.s3cr3t-label.evil.example/reset/s3cr3t-token?s3cr3t-flag"
               :allow    ["app.example.com" "s3cr3t-internal-admin.example.com"]}
              {:error host-disallowed :reason :not-in-allowlist}]
             [{:location (str "https://" (apply str (repeat 20000 "h")) ".evil.example/"
                              (apply str (repeat 20000 "p")))
               :allow    ["app.example.com"]}
              {:error host-disallowed :reason :not-in-allowlist}]
             [{:location ""}
              {:error invalid-url :reason :parse-failed}]]]
      (is (= (refused-with slots) (refusal args))
          (subs (pr-str args) 0 (min 120 (count (pr-str args))))))))

(deftest a-network-path-reference-cannot-satisfy-either-policy
  (testing "locations a browser resolves off-origin while java.net.URI reports
            no usable host (no authority, or an authority with no host) are
            refused by relative-only and by an allowlist alike"
    (doseq [location ["///evil.example/path" "////evil.example/path" "//evil_example/path"]
            [args reason] [[{:location location :relative-only? true} :relative-only-violation]
                           [{:location location :allow ["trusted.example"]} :not-in-allowlist]]]
      (is (= (refused-with {:error host-disallowed :reason reason}) (refusal args))
          (pr-str args)))))

(deftest each-policy-still-passes-its-legitimate-targets
  (doseq [args [{:location "/dashboard" :relative-only? true}
                {:location "/dashboard" :allow ["trusted.example"]}
                {:location "https://trusted.example/path" :allow ["trusted.example"]}
                {:location "https://TRUSTED.example/path" :allow ["trusted.example"]}]]
    (let [{:keys [records response]} (reject! args)]
      (is (= [(:location args) []]
             [(:location (:redirect response)) (vec (safe-redirect-records records))])
          (pr-str args)))))

(deftest the-probe-class-is-normalised-for-aggregation
  (testing "schemes are case-insensitive, so every spelling lands in one bucket"
    (let [expected {"JavaScript:alert(1)"     :javascript
                    "data:text/html,x"        :data
                    "VBScript:x"              :vbscript
                    "MAILTO:evil@example.com" :other
                    "https:evil.example.com"  :https}]
      (is (= expected
             (into {} (for [loc (keys expected)]
                        [loc (:scheme-class (first (safe-redirect-records
                                                     (:records (reject! {:location loc})))))])))))))

(deftest the-class-vocabulary-tracks-the-gates-own-scheme-sets
  (testing "a scheme added to the gate but not to the class map would silently
            degrade to :other"
    (let [gate-schemes (into (deref #'re-frame.ssr.response/rejected-schemes)
                             (deref #'re-frame.ssr.response/allowed-schemes))]
      (is (= (zipmap gate-schemes (map keyword gate-schemes))
             rf.ssr.egress/scheme-classes)))))

;; The `:location` scrub lives in core, so a routing-free SSR host has it. The
;; `:test` alias puts routing on the classpath, so a routing dependency would
;; stay green in an ordinary run; this asserts it off the production deps and
;; the source tree instead.

(defn- ssr-artefact-file
  "Resolve a path inside `implementation/ssr/` from this artefact's directory
  or from the repo root."
  [rel]
  (first (filter #(.exists ^java.io.File %)
                 [(io/file rel) (io/file "implementation/ssr" rel)])))

(def ^:private routing-load-form
  "A routing namespace in a LOAD position (a require vector, a bare
  `(:require re-frame.routing…)`, or a quoted symbol), not a prose mention."
  #"(\[|'|\(:require\s+)re-frame\.routing")

(deftest the-location-scrub-is-on-ssrs-production-classpath-without-routing
  (is (= #{'day8/re-frame2}
         (-> (ssr-artefact-file "deps.edn") slurp edn/read-string :deps keys set))
      "SSR's production deps name core alone")
  (let [sources (->> (file-seq (ssr-artefact-file "src"))
                     (filter #(.isFile ^java.io.File %))
                     (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %))))]
    (is (= [true []]
           [(< 10 (count sources))
            (mapv #(.getPath ^java.io.File %) (filter #(re-find routing-load-form (slurp %)) sources))])
        "[the scan reached the src tree, no source loads a routing namespace]")))
