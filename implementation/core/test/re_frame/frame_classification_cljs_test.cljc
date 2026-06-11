(ns re-frame.frame-classification-cljs-test
  "EP-0015 §3 + §9 (rf2-ueg1tn) — frame-owned durable classification on
  `reg-frame`. Pins the four acceptance legs the bead enumerates:

    (a) install-before-`:on-create` — frame-owned `:sensitive` / `:large`
        `:app-db` paths are installed into the durable elision registry
        ATOMICALLY as part of frame creation, BEFORE the `:on-create`
        cascade runs (so a path declared sensitive is already redacted in
        any trace the init cascade emits).
    (b) replace-on-rereg — re-registering a frame REPLACES its frame-owned
        classification (the declaration IS the policy; no additive merge);
        schema- and marks-sourced declarations survive.
    (c) sensitive-wins-over-large — a path declared BOTH sensitive and large
        installs as sensitive ONLY; no large declaration entry is created
        for it, so no `:rf.size/large-elided` marker can leak.
    (d) fail-loud — malformed paths, unknown classification keys, and
        non-string HTTP carrier names throw `:rf.error/bad-frame-classification`
        at `reg-frame` time, before any state mutates / before `:on-create`.

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. The classification install +
  validation is plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.frame-classification :as fc]
            [re-frame.marks :as marks]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; (a) install-before-:on-create
;; ---------------------------------------------------------------------------

(deftest classification-installed-before-on-create
  (testing "frame-owned :sensitive / :large :app-db paths are in the elision
            registry BEFORE the :on-create cascade runs"
    (let [seen (atom :unset)]
      ;; The :on-create handler observes the elision registry mid-init. If
      ;; classification installs atomically before :on-create, the handler
      ;; sees the sensitive declaration already present.
      (rf/reg-event-db :app/init
        (fn [db _]
          (reset! seen (elision/sensitive-declarations :app/main))
          db))
      (rf/reg-frame :app/main
        {:sensitive {:app-db [[:auth :token]]}
         :large     {:app-db [[:documents :csv-upload]]}
         :on-create [:app/init]})
      ;; The handler captured the registry as it stood DURING :on-create.
      (is (not= :unset @seen) ":on-create ran")
      (is (contains? @seen [:auth :token])
          "sensitive declaration was present before :on-create ran")
      (is (= :frame (:source (get @seen [:auth :token])))
          "frame-owned declaration is tagged :source :frame")
      ;; And after creation the large declaration is installed too.
      (is (contains? (elision/declarations :app/main) [:documents :csv-upload])
          "large declaration installed")
      (is (= :frame (:source (get (elision/declarations :app/main)
                                  [:documents :csv-upload])))))))

(deftest classification-app-db-paths-normalize-to-vectors
  (testing ":rf/path entries are normalised to canonical vectors (EP-0012)"
    ;; A seq path normalises to a vector; the stored declaration key is a
    ;; vector.
    (rf/reg-frame :app/n
      {:sensitive {:app-db [(list :auth :token)]}})
    (let [decls (elision/sensitive-declarations :app/n)]
      (is (contains? decls [:auth :token])
          "seq path normalised to a vector declaration key"))))

(deftest no-classification-keys-is-a-no-op
  (testing "a frame with no classification keys installs no frame-sourced declarations"
    (rf/reg-frame :app/plain {:doc "no classification"})
    (is (empty? (elision/sensitive-declarations :app/plain)))
    (is (empty? (elision/declarations :app/plain)))))

;; ---------------------------------------------------------------------------
;; (b) replace-on-rereg
;; ---------------------------------------------------------------------------

(deftest re-registration-replaces-frame-classification
  (testing "re-registering a frame REPLACES its frame-owned classification"
    (rf/reg-frame :app/r
      {:sensitive {:app-db [[:auth :token] [:auth :refresh-token]]}})
    (is (contains? (elision/sensitive-declarations :app/r) [:auth :token]))
    (is (contains? (elision/sensitive-declarations :app/r) [:auth :refresh-token]))
    ;; Re-register with a DIFFERENT sensitive set — the old one is gone.
    (rf/reg-frame :app/r
      {:sensitive {:app-db [[:tenant :partner-api-key]]}})
    (let [decls (elision/sensitive-declarations :app/r)]
      (is (contains? decls [:tenant :partner-api-key]) "new declaration present")
      (is (not (contains? decls [:auth :token]))
          "old frame-owned declaration was replaced, not merged")
      (is (not (contains? decls [:auth :refresh-token]))))))

(deftest re-registration-dropping-classification-clears-it
  (testing "re-registering WITHOUT classification clears prior frame-sourced declarations"
    (rf/reg-frame :app/d {:sensitive {:app-db [[:auth :token]]}})
    (is (contains? (elision/sensitive-declarations :app/d) [:auth :token]))
    ;; Absent-key clears (Spec 002 §Re-registration — the declaration IS the policy).
    (rf/reg-frame :app/d {:doc "classification dropped"})
    (is (empty? (elision/sensitive-declarations :app/d))
        "frame-sourced declarations cleared when the key is dropped")))

(deftest re-registration-preserves-marks-sourced-declarations
  (testing "schema/marks-sourced declarations survive a frame-classification replace"
    (rf/reg-frame :app/m {:sensitive {:app-db [[:auth :token]]}})
    ;; An imperative add-marks declaration (now an internal helper, no
    ;; longer on the public façade — EP-0015 rf2-mngp4o) co-exists.
    (marks/add-marks :app/m {[:user :ssn] :sensitive})
    (let [decls (elision/sensitive-declarations :app/m)]
      (is (= :frame (:source (get decls [:auth :token]))))
      (is (= :marks (:source (get decls [:user :ssn])))))
    ;; Re-register the frame with a new frame-owned set — the marks-sourced
    ;; [:user :ssn] declaration MUST survive (it is not frame-owned).
    (rf/reg-frame :app/m {:sensitive {:app-db [[:tenant :key]]}})
    (let [decls (elision/sensitive-declarations :app/m)]
      (is (contains? decls [:tenant :key]) "new frame-owned declaration present")
      (is (not (contains? decls [:auth :token])) "old frame-owned declaration gone")
      (is (= :marks (:source (get decls [:user :ssn])))
          "marks-sourced declaration survived the frame-classification replace"))))

;; ---------------------------------------------------------------------------
;; (c) sensitive-wins-over-large
;; ---------------------------------------------------------------------------

(deftest sensitive-wins-over-large-at-install
  (testing "a path declared BOTH sensitive and large installs as sensitive ONLY"
    (rf/reg-frame :app/w
      {:sensitive {:app-db [[:auth :token]]}
       :large     {:app-db [[:auth :token]      ;; SAME path — sensitive wins
                            [:documents :csv]]}}) ;; large-only path survives
    (let [sens  (elision/sensitive-declarations :app/w)
          large (elision/declarations :app/w)]
      (is (contains? sens [:auth :token]) "the dual path is sensitive")
      (is (not (contains? large [:auth :token]))
          "the dual path emits NO large declaration entry — no large marker can leak")
      (is (contains? large [:documents :csv])
          "a large-only path still gets its large declaration"))))

(deftest sensitive-wins-extract-shape
  (testing "validate+extract resolves sensitive-wins: large-app-db excludes any sensitive path"
    (let [{:keys [sensitive-app-db large-app-db]}
          (fc/validate+extract :app/x
            {:sensitive {:app-db [[:a :b]]}
             :large     {:app-db [[:a :b] [:c :d]]}})]
      (is (= [[:a :b]] sensitive-app-db))
      (is (= [[:c :d]] large-app-db)
          "the both-path [:a :b] is dropped from large-app-db"))))

;; ---------------------------------------------------------------------------
;; (d) fail-loud
;; ---------------------------------------------------------------------------

(defn- bad-classification-ex
  "Run thunk and return the caught ex-data when it throws
  `:rf.error/bad-frame-classification`, else nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (ex-data e))))

(deftest fail-loud-on-malformed-path
  (testing "a non-sequential :app-db path entry fails loudly at reg-frame"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad1
                    {:sensitive {:app-db [:auth]}}))] ;; bare keyword, not a path
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :app-db] (:bad-key data)))
      (is (= :auth (:bad-path data))))
    ;; The :app-db value itself must be a vector of paths.
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad1b
                    {:large {:app-db {:not :a-vector}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:large :app-db] (:bad-key data))))))

(deftest fail-loud-on-unknown-classification-key
  (testing "an unknown classification block key fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad2
                    {:sensitive {:app-db [[:a]]
                                 :bogus  [:x]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :bogus] (:bad-key data))))
    ;; Unknown :observability stream key.
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad2b
                    {:observability {:bogus-stream []}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :bogus-stream] (:bad-key data))))
    ;; Unknown :sensitive :http carrier key.
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad2c
                    {:sensitive {:http {:cookies ["x"]}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :cookies] (:bad-key data))))))

(deftest fail-loud-on-non-string-carrier
  (testing "a non-string HTTP carrier name fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad3
                    {:sensitive {:http {:headers [:X-Honeycomb-Team]}}}))] ;; keyword, not string
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :headers] (:bad-key data)))
      (is (= :X-Honeycomb-Team (:bad-carrier data))))
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad3b
                    {:sensitive {:http {:query-params [42]}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :query-params] (:bad-key data)))
      (is (= 42 (:bad-carrier data))))))

(deftest fail-loud-on-bad-observability-entry
  (testing "an :observability entry without a :sink keyword fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad4
                    {:observability {:handled-events [{:opts {:service "x"}}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :sink] (:bad-key data))))))

(deftest fail-loud-leaves-no-frame-state
  (testing "a malformed classification throws BEFORE the frame is registered (transactional)"
    (bad-classification-ex
      #(rf/reg-frame :app/never
         {:sensitive {:app-db [:not-a-path]}}))
    (is (nil? (frame/frame :app/never))
        "the frame was never registered — validation fails before any state mutates")))

;; ---------------------------------------------------------------------------
;; valid classification surfaces are retained on the frame config (HTTP
;; carriers + observability ride :config verbatim for the later slices).
;; ---------------------------------------------------------------------------

(deftest valid-http-and-observability-retained-on-config
  (testing "well-formed :http carriers and :observability ride the frame config"
    (rf/reg-frame :app/full
      {:sensitive {:app-db [[:auth :token]]
                   :http   {:headers      ["X-Honeycomb-Team"]
                            :query-params ["shop_token"]}}
       :observability {:handled-events [{:sink :my-app.sinks/datadog
                                         :rf.egress/profile :rf.egress/off-box-observability
                                         :opts {:service "checkout-spa"}}]
                       :errors         [{:sink :my-app.sinks/sentry}]}})
    (let [meta (rf/frame-meta :app/full)]
      (is (= ["X-Honeycomb-Team"]
             (get-in meta [:sensitive :http :headers]))
          ":http carriers ride the frame config (read via frame-meta)")
      (is (= :my-app.sinks/datadog
             (get-in meta [:observability :handled-events 0 :sink]))
          ":observability sink policy rides the frame config"))))

;; ---------------------------------------------------------------------------
;; concrete path host-segment rejection (rf2-orcbow point 2)
;;
;; `:sensitive :app-db` / `:large :app-db` entries are concrete `:rf/path`
;; vectors (EP-0012). A concrete path's SEGMENTS must be portable EDN identity
;; values — Conventions §Path shape: "Concrete runtime paths MUST NOT contain
;; host values; such values are rejected at the boundary that accepts the
;; path." The existing fail-loud tests above cover path-SHAPE rejection
;; (a bare keyword, a non-vector :app-db) and list->vector normalization, but
;; NOT opaque host-OBJECT segments inside an otherwise well-shaped path.
;;
;; KNOWN GAP — `normalize-app-db-paths` validates only that each path is
;; sequential and routes it through `path/normalize`, which coerces container
;; shape but does NOT validate the segment domain. So `[:auth (Object.)]`
;; currently passes. The fail-closed source fix (a validated concrete-path
;; normalization helper at the declaration boundary) is owned by the
;; correctness / best-practice review beads rf2-wgutc2 (item 3, "Concrete
;; path boundaries do not reject non-EP path segments ... frame classification
;; ... can accept host/opaque values as segments") and rf2-w9x5fv (item 2,
;; "Split shape coercion from validated concrete normalization ... use the
;; validated helper at concrete boundaries such as frame classification"),
;; NOT by this coverage bead (tests-only).
;;
;; These tests therefore (a) DOCUMENT + PIN the current (does-not-reject)
;; behaviour on both runtimes so the gap is visible, and (b) carry the
;; spec-correct fail-closed assertion inline so the flip is a one-line edit
;; when the source fix lands. Flip the `host-segment-*` blocks then.
;; ---------------------------------------------------------------------------

(deftest host-object-path-segment-is-not-yet-rejected
  (testing "KNOWN GAP (rf2-wgutc2 / rf2-w9x5fv): an opaque host-object path
            segment is NOT rejected at reg-frame — current behaviour pinned"
    (let [host #?(:clj (java.lang.Object.) :cljs #js {:opaque true})]
      ;; CURRENT behaviour: validate+extract accepts the host segment, returning
      ;; it verbatim in the normalized declaration (no fail-loud).
      (let [{:keys [sensitive-app-db]}
            (fc/validate+extract :app/host-seg
              {:sensitive {:app-db [[:auth host]]}})]
        (is (= 1 (count sensitive-app-db))
            "current: host-object segment passes through (does not throw)")
        (is (= :auth (first (first sensitive-app-db)))
            "the literal prefix segment survives")
        (is (identical? host (second (first sensitive-app-db)))
            "the opaque host object rides in the stored path verbatim"))
      ;; SPEC-CORRECT behaviour, asserted once the source fix lands — flip to:
      ;;   (let [data (bad-classification-ex
      ;;                #(rf/reg-frame :app/host-seg
      ;;                   {:sensitive {:app-db [[:auth host]]}}))]
      ;;     (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      ;;     (is (= [:sensitive :app-db] (:bad-key data)))
      ;;     (is (= [:auth host] (:bad-path data))))
      ;; (Conventions §Path shape: concrete paths MUST NOT contain host values;
      ;;  rejected at the boundary that accepts the path.)
      )))

(deftest existing-segment-shape-rejections-still-hold
  (testing "well-formed concrete paths (incl. the root []) still validate + normalize"
    ;; A vector of concrete EDN segments — and the explicit root [] — are
    ;; accepted; this anchors the negative (host-segment) case above against
    ;; the positive baseline so a future over-broad rejection is caught.
    (let [{:keys [sensitive-app-db]}
          (fc/validate+extract :app/ok-paths
            {:sensitive {:app-db [[:auth :token]
                                  [:cart :items 42 :qty]  ;; integer segment OK
                                  []]}})]                  ;; root path OK
      (is (= [[:auth :token] [:cart :items 42 :qty] []] sensitive-app-db)))))
