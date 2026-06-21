(ns re-frame.frame-classification-cljs-test
  "EP-0015 §3 + §9 (rf2-ueg1tn) — frame-owned durable classification on
  `reg-frame`. Pins the four acceptance legs the bead enumerates:

    (a) install-before-`:initial-events` — frame-owned `:sensitive` / `:large`
        `:app-db` paths are installed into the durable elision registry
        ATOMICALLY as part of frame creation, BEFORE the `:initial-events`
        setup cascade runs (so a path declared sensitive is already redacted
        in any trace the init cascade emits).
    (b) replace-on-rereg — re-registering a frame REPLACES its frame-owned
        classification (the declaration IS the policy; no additive merge);
        schema- and marks-sourced declarations survive.
    (c) sensitive-wins-over-large — a path declared BOTH sensitive and large
        installs as sensitive ONLY; no large declaration entry is created
        for it, so no `:rf.size/large-elided` marker can leak.
    (d) fail-loud — malformed paths, unknown classification keys, and
        non-string HTTP carrier names throw `:rf.error/bad-frame-classification`
        at `reg-frame` time, before any state mutates / before `:initial-events`.

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
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; (a) install-before-:initial-events
;; ---------------------------------------------------------------------------

(deftest classification-installed-before-initial-events
  (testing "frame-owned :sensitive / :large :app-db paths are in the elision
            registry BEFORE the :initial-events setup cascade runs"
    (let [seen (atom :unset)]
      ;; The :initial-events setup handler observes the elision registry
      ;; mid-init. If classification installs atomically before setup, the
      ;; handler sees the sensitive declaration already present.
      (rf/reg-event :app/init
        (fn [{:keys [db]} _]
          (reset! seen (elision/sensitive-declarations :app/main))
          {:db db}))
      (rf/reg-frame :app/main
        {:sensitive {:app-db [[:auth :token]]}
         :large     {:app-db [[:documents :csv-upload]]}
         :initial-events [[:app/init]]})
      ;; The handler captured the registry as it stood DURING :initial-events.
      (is (not= :unset @seen) ":initial-events ran")
      (is (contains? @seen [:auth :token])
          "sensitive declaration was present before :initial-events ran")
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

(deftest re-registration-preserves-other-sourced-declarations
  (testing "non-frame-sourced declarations survive a frame-classification replace"
    (rf/reg-frame :app/m {:sensitive {:app-db [[:auth :token]]}})
    ;; EP-0025: the imperative add-marks/set-marks API is REMOVED. A non-frame
    ;; declaration source (here a commit-plane `:sensitive` effect, `:source
    ;; :effect`) co-exists and survives a frame-classification replace.
    (elision/swap-elision-slot! :app/m
      (fn [reg]
        (assoc-in reg [:sensitive-declarations [:user :ssn]] {:source :effect})))
    (let [decls (elision/sensitive-declarations :app/m)]
      (is (= :frame (:source (get decls [:auth :token]))))
      (is (= :effect (:source (get decls [:user :ssn])))))
    ;; Re-register the frame with a new frame-owned set — the effect-sourced
    ;; [:user :ssn] declaration MUST survive (it is not frame-owned).
    (rf/reg-frame :app/m {:sensitive {:app-db [[:tenant :key]]}})
    (let [decls (elision/sensitive-declarations :app/m)]
      (is (contains? decls [:tenant :key]) "new frame-owned declaration present")
      (is (not (contains? decls [:auth :token])) "old frame-owned declaration gone")
      (is (= :effect (:source (get decls [:user :ssn])))
          "effect-sourced declaration survived the frame-classification replace"))))

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

;; rf2-4wqxq8 — :query-params accepts a {:include [..] :except [..]} policy map
;; (an additive frame-local subtraction path) in addition to the legacy vector.

(deftest query-params-policy-map-accepted-and-validated
  (testing "rf2-4wqxq8 — a well-formed {:include :except} :query-params map is accepted"
    (rf/reg-frame :app/qp-policy-ok
      {:sensitive {:http {:query-params {:include ["shop_token"]
                                         :except  ["token"]}}}})
    (is (= {:include ["shop_token"] :except ["token"]}
           (get-in (rf/frame-meta :app/qp-policy-ok)
                   [:sensitive :http :query-params]))
        "the policy map rides the frame config verbatim"))
  (testing "an unknown key inside the :query-params policy map fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-badkey
                    {:sensitive {:http {:query-params {:include ["x"] :bogus ["y"]}}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :query-params :bogus] (:bad-key data)))))
  (testing "a non-string name inside :include / :except fails loudly with a precise bad-key"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-badinc
                    {:sensitive {:http {:query-params {:include [42]}}}}))]
      (is (= [:sensitive :http :query-params :include] (:bad-key data)))
      (is (= 42 (:bad-carrier data))))
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-badexc
                    {:sensitive {:http {:query-params {:except [:kw]}}}}))]
      (is (= [:sensitive :http :query-params :except] (:bad-key data)))
      (is (= :kw (:bad-carrier data)))))
  (testing "a non-vector :include sub-value fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-nonvec
                    {:sensitive {:http {:query-params {:include "not-a-vector"}}}}))]
      (is (= [:sensitive :http :query-params :include] (:bad-key data)))))
  (testing "the header denylist stays vector-only (no policy-map form)"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/hdr-policy-rejected
                    {:sensitive {:http {:headers {:include ["X-Foo"]}}}}))]
      (is (= [:sensitive :http :headers] (:bad-key data))
          "a {:include ...} map is not a valid :headers value (immutable denylist)"))))

(deftest fail-loud-on-bad-observability-entry
  (testing "an :observability entry without a :sink keyword fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad4
                    {:observability {:handled-events [{:opts {:service "x"}}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :sink] (:bad-key data))))))

(deftest fail-loud-on-unknown-observability-profile
  ;; rf2-t55hxg.13 — `:rf.egress/profile` is a member of the closed EP-0015
  ;; §10 profile enum. A typo'd / unknown profile must fail loudly at
  ;; reg-frame (the seam that owns the policy), not silently install and
  ;; only blow up downstream when the sink first fires.
  (testing "an :observability entry naming an unknown :rf.egress/profile fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad-profile
                    {:observability
                     {:handled-events [{:sink :my-app.sinks/datadog
                                        :rf.egress/profile :rf.egress/bogus-profile}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :rf.egress/profile] (:bad-key data)))
      (is (= :rf.egress/bogus-profile (:bad-value data)))
      (is (contains? (:valid data) :rf.egress/off-box-observability)
          "the error carries the closed profile enum"))
    ;; The :errors stream is validated identically.
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad-profile-err
                    {:observability
                     {:errors [{:sink :my-app.sinks/sentry
                                :rf.egress/profile :not-even-egress-namespaced}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :errors :rf.egress/profile] (:bad-key data)))))
  (testing "a well-formed :rf.egress/profile from the closed enum is accepted"
    (rf/reg-frame :app/good-profile
      {:observability {:handled-events [{:sink :my-app.sinks/datadog
                                         :rf.egress/profile :rf.egress/off-box-observability}]}})
    (is (= :my-app.sinks/datadog
           (get-in (rf/frame-meta :app/good-profile)
                   [:observability :handled-events 0 :sink])))))

(deftest fail-loud-on-bad-observability-opts
  ;; rf2-t55hxg.13 — `:opts`, when present, is the sink's option bag and
  ;; must be a map (or nil). A non-map :opts is malformed — fail at reg-frame
  ;; rather than handing junk to the sink at fire time.
  (testing "an :observability entry with non-map :opts fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad-opts
                    {:observability
                     {:handled-events [{:sink :my-app.sinks/datadog
                                        :opts "not-a-map"}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :opts] (:bad-key data)))
      (is (= "not-a-map" (:bad-value data)))))
  (testing "nil :opts and an omitted :opts are both accepted"
    (rf/reg-frame :app/nil-opts
      {:observability {:handled-events [{:sink :my-app.sinks/datadog :opts nil}]
                       :errors         [{:sink :my-app.sinks/sentry}]}})
    (is (= :my-app.sinks/datadog
           (get-in (rf/frame-meta :app/nil-opts)
                   [:observability :handled-events 0 :sink])))))

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
;; HARDENED (rf2-w9x5fv) — `normalize-app-db-paths` now routes each path
;; through `path/normalize-concrete`, the VALIDATED concrete boundary (item 2:
;; "Split shape coercion from validated concrete normalization ... use the
;; validated helper at concrete boundaries such as frame classification").
;; `normalize` still coerces container shape only; `normalize-concrete`
;; additionally validates the segment domain, so an opaque host-object segment
;; like `[:auth (Object.)]` FAILS CLOSED at reg-frame (item 3 / Conventions
;; §Segment domain) rather than silently riding in a stored elision path.
;; ---------------------------------------------------------------------------

(deftest host-object-path-segment-is-rejected
  (testing "rf2-w9x5fv (items 2 + 3): an opaque host-object path segment FAILS
            LOUDLY at reg-frame — the concrete declaration boundary routes
            through path/normalize-concrete, which rejects non-EDN segments
            (Conventions §Path shape / §Segment domain: concrete paths MUST NOT
            contain host values; rejected at the boundary that accepts them)"
    (let [host #?(:clj (java.lang.Object.) :cljs #js {:opaque true})]
      (let [data (bad-classification-ex
                   #(rf/reg-frame :app/host-seg
                      {:sensitive {:app-db [[:auth host]]}}))]
        (is (= :rf.error/bad-frame-classification (:rf.error/id data))
            "a host-object segment is rejected with the frame-classification error")
        (is (= [:sensitive :app-db] (:bad-key data))
            "the offending classification key is named")
        (is (= [:auth host] (:bad-path data))
            "the offending path is named")
        (is (identical? host (:bad-segment data))
            "the offending host segment is surfaced from the path boundary"))
      ;; The frame must NOT have been registered (fail-loud is transactional).
      (is (nil? (frame/frame :app/host-seg))
          "the malformed classification threw before any frame state mutated"))))

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
