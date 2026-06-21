(ns re-frame.projection-cljs-test
  "EP-0015 §10/§11 (rf2-yjbr1w) — record-level egress projection.
  `project-egress` + the six ruled `:rf.egress/*` profiles, layered over
  `elide-wire-value`. Pins the acceptance legs the bead enumerates:

    - each profile's default redaction / elision behaviour on a record
      carrying sensitive + large + tree slots;
    - profile + explicit `:rf.size/*` override COMPOSES (override wins);
    - sensitive-wins (a both-marked path redacts, never large-elides);
    - `:rf.egress/public-error` never includes raw internal values;
    - delegation to `elide-wire-value` for tree slots (the walker, not a
      reimplemented walk, does the substitution);
    - off-box default omits the handled-event `:event` args slot entirely
      (issue 4), trusted-local keeps it PROJECTED;
    - unknown profile throws (the enum is closed);
    - fail-closed with no frame (no `:rf/default` synthesis).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.projection :as projection]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; A frame classifying [:auth :token] sensitive and [:docs :blob] large.
;; The app-db value under test carries a sensitive leaf, a large leaf, and
;; an unmarked sibling — the three-axis record EP-0015 §10 names.
(def ^:private big-string
  ;; > the 16384-byte default threshold so an UNDECLARED large auto-detect
  ;; would fire; here [:docs :blob] is DECLARED large, so the marker is
  ;; deterministic regardless of threshold.
  (apply str (repeat 40000 \x)))

(defn- mk-frame! [frame-id]
  (rf/reg-frame frame-id {})
  ;; EP-0025: durable app-db classification rides the commit-plane effect
  ;; path (`:source :effect`) — the durable frame annotation is removed.
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:auth :token]]
                :large     [[:docs :blob]]}))))

(defn- sample-value []
  {:auth {:token "super-secret-token"}
   :docs {:blob big-string}
   :public {:count 3}})

(defn- redacted? [v] (= :rf/redacted v))
(defn- large-marker? [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; Profile resolution — the §10 default opt-sets.
;; ---------------------------------------------------------------------------

(deftest profile-enum-is-the-closed-six
  (is (= #{:rf.egress/off-box-observability
           :rf.egress/off-box-tool
           :rf.egress/local-redacted
           :rf.egress/local-raw
           :rf.egress/ssr-hydration
           :rf.egress/public-error}
         projection/profiles)
      "the six ruled profiles are the closed enum (EP-0015 issue 3)"))

(deftest profile-resolves-to-size-opts
  (testing "each profile resolves to its §10 default :rf.size/* opt-set"
    ;; off-box / on-box-redacted boundaries fail closed.
    (doseq [p [:rf.egress/off-box-observability
               :rf.egress/local-redacted
               :rf.egress/ssr-hydration
               :rf.egress/public-error]]
      (let [o (projection/profile-size-opts p)]
        (is (false? (:rf.size/include-sensitive? o)) (str p " redacts sensitive"))
        (is (false? (:rf.size/include-large? o))     (str p " elides large"))))
    ;; off-box-tool turns digests ON (structural indicators).
    (let [o (projection/profile-size-opts :rf.egress/off-box-tool)]
      (is (false? (:rf.size/include-sensitive? o)))
      (is (false? (:rf.size/include-large? o)))
      (is (true?  (:rf.size/include-digests? o))
          "off-box-tool includes structural indicators (§10)"))
    ;; local-raw opts sensitive + large back in.
    (let [o (projection/profile-size-opts :rf.egress/local-raw)]
      (is (true? (:rf.size/include-sensitive? o)) "local-raw includes sensitive")
      (is (true? (:rf.size/include-large? o))     "local-raw includes large"))
    ;; Unknown / absent → nil.
    (is (nil? (projection/profile-size-opts :rf.egress/bogus)))
    (is (nil? (projection/profile-size-opts nil)))))

;; ---------------------------------------------------------------------------
;; Per-profile projection of a sensitive + large + tree record/value.
;; ---------------------------------------------------------------------------

(deftest off-box-profiles-redact-sensitive-and-elide-large
  (testing "off-box / redacted profiles project sensitive → :rf/redacted, large → marker"
    (mk-frame! :proj/offbox)
    (doseq [p [:rf.egress/off-box-observability
               :rf.egress/off-box-tool
               :rf.egress/local-redacted
               :rf.egress/ssr-hydration
               :rf.egress/public-error]]
      (let [out (rf/project-egress (sample-value)
                  {:frame :proj/offbox :rf.egress/profile p})]
        (is (redacted? (get-in out [:auth :token]))
            (str p ": sensitive leaf redacted"))
        (is (large-marker? (get-in out [:docs :blob]))
            (str p ": large leaf elided to a marker"))
        (is (= 3 (get-in out [:public :count]))
            (str p ": unmarked sibling passes through"))))))

(deftest local-raw-includes-sensitive-and-large
  (testing ":rf.egress/local-raw passes sensitive AND large through verbatim"
    (mk-frame! :proj/raw)
    (let [out (rf/project-egress (sample-value)
                {:frame :proj/raw :rf.egress/profile :rf.egress/local-raw})]
      (is (= "super-secret-token" (get-in out [:auth :token]))
          "trusted-local sees the sensitive value")
      (is (= big-string (get-in out [:docs :blob]))
          "trusted-local sees the large value raw")
      (is (= 3 (get-in out [:public :count]))))))

;; ---------------------------------------------------------------------------
;; Profile + explicit :rf.size/* override composes — override wins.
;; ---------------------------------------------------------------------------

(deftest profile-plus-override-composes
  (testing "an explicit :rf.size/* boolean overlays the profile floor (override wins)"
    (mk-frame! :proj/compose)
    ;; off-box-observability floors include-sensitive? false, but the
    ;; caller explicitly opts it back in — the override wins.
    (let [out (rf/project-egress (sample-value)
                {:frame :proj/compose
                 :rf.egress/profile :rf.egress/off-box-observability
                 :rf.size/include-sensitive? true})]
      (is (= "super-secret-token" (get-in out [:auth :token]))
          "explicit include-sensitive? true overrides the off-box floor")
      ;; The non-overridden axis stays at the floor: large still elides.
      (is (large-marker? (get-in out [:docs :blob]))
          "the un-overridden large axis stays at the profile floor"))
    ;; The reverse: local-raw floors include-large? true, override to false.
    (let [out (rf/project-egress (sample-value)
                {:frame :proj/compose
                 :rf.egress/profile :rf.egress/local-raw
                 :rf.size/include-large? false})]
      (is (= "super-secret-token" (get-in out [:auth :token]))
          "local-raw sensitive floor survives (not overridden)")
      (is (large-marker? (get-in out [:docs :blob]))
          "explicit include-large? false overrides the local-raw floor"))))

(deftest resolve-elision-opts-overlay-order
  (testing "resolve-elision-opts: explicit :rf.size/* beats profile floor; pass-throughs kept"
    (let [o (projection/resolve-elision-opts
              {:rf.egress/profile :rf.egress/off-box-observability
               :rf.size/include-large? true
               :frame :x
               :path [:a]
               :rf.size/threshold-bytes 99})]
      (is (true?  (:rf.size/include-large? o)) "override wins over the false floor")
      (is (false? (:rf.size/include-sensitive? o)) "un-overridden axis at floor")
      (is (= :x (:frame o)) ":frame passes through")
      (is (= [:a] (:path o)) ":path passes through")
      (is (= 99 (:rf.size/threshold-bytes o)) ":threshold-bytes passes through")
      (is (not (contains? o :rf.egress/profile)) "the profile key is consumed"))))

;; ---------------------------------------------------------------------------
;; Sensitive wins over large.
;; ---------------------------------------------------------------------------

(deftest sensitive-wins-over-large
  (testing "a path declared BOTH sensitive and large redacts, never large-elides"
    (rf/reg-frame :proj/both {})
    (frame/swap-runtime-db! :proj/both
      (fn [rt] (elision/apply-classification-effects rt
                 {:sensitive [[:secret]]
                  :large     [[:secret]]})))
    (let [out (rf/project-egress {:secret big-string}
                {:frame :proj/both
                 :rf.egress/profile :rf.egress/off-box-observability})]
      (is (redacted? (:secret out)) "the both-marked path is :rf/redacted")
      (is (not (large-marker? (:secret out)))
          "NO large marker is emitted — no path/size/digest can leak"))))

;; ---------------------------------------------------------------------------
;; Delegation to elide-wire-value — the walker, not a reimplemented walk.
;; ---------------------------------------------------------------------------

(deftest delegates-to-elide-wire-value-for-tree-slots
  (testing "project-egress of a tree value equals elide-wire-value under the resolved opts"
    (mk-frame! :proj/deleg)
    (let [opts {:frame :proj/deleg :rf.egress/profile :rf.egress/off-box-tool}
          via-project (rf/project-egress (sample-value) opts)
          ;; The walker called directly under the RESOLVED opts must match —
          ;; proving project-egress delegates rather than re-walking.
          resolved (projection/resolve-elision-opts opts)
          via-walker  (elision/elide-wire-value (sample-value) resolved)]
      (is (= via-project via-walker)
          "tree-slot projection is exactly elide-wire-value under resolved opts"))))

;; ---------------------------------------------------------------------------
;; Handled-event record (issue 4) — off-box omits :event args; raw keeps it.
;; ---------------------------------------------------------------------------

(defn- handled-event-record [frame-id]
  {:kind        :rf.observe/handled-event
   :frame       frame-id
   :event-id    :auth/login
   :event       [:auth/login {:password "secret"}]
   :status      :ok
   :elapsed-ms  12
   :effects     [:db :rf.http/managed]
   :correlation {:work-id "w-77" :dispatch-id "d-91"}})

(deftest handled-event-off-box-omits-event-args
  (testing "off-box default carries summary fields and OMITS the :event args slot"
    (mk-frame! :proj/he)
    (let [out (rf/project-egress (handled-event-record :proj/he)
                {:frame :proj/he
                 :rf.egress/profile :rf.egress/off-box-observability})]
      (is (not (contains? out :event)) "the :event args slot is omitted entirely")
      (is (= :rf.observe/handled-event (:kind out)))
      (is (= :auth/login (:event-id out)))
      (is (= :ok (:status out)))
      (is (= 12 (:elapsed-ms out)))
      (is (= [:db :rf.http/managed] (:effects out)))
      (is (= {:work-id "w-77" :dispatch-id "d-91"} (:correlation out))
          "correlation ids ride through"))))

(deftest handled-event-trusted-local-keeps-projected-event
  (testing "trusted-local keeps :event, PROJECTED through the walker (never raw)"
    (mk-frame! :proj/he2)
    (let [out (rf/project-egress (handled-event-record :proj/he2)
                {:frame :proj/he2
                 :rf.egress/profile :rf.egress/local-raw})]
      (is (contains? out :event) "trusted-local retains the :event slot")
      (is (= [:auth/login {:password "secret"}] (:event out))
          "local-raw projects the event verbatim (sensitive opted in)"))))

;; ---------------------------------------------------------------------------
;; public-error never includes raw internal values.
;; ---------------------------------------------------------------------------

(deftest public-error-drops-internal-exception
  (testing ":rf.egress/public-error never carries internal raw values"
    (mk-frame! :proj/err)
    (let [record {:kind      :rf.observe/error
                  :frame     :proj/err
                  :error     :rf.error/handler-exception
                  :event-id  :auth/login
                  :event     [:auth/login {:auth {:token "tok"}}]
                  :exception {:internal "raw stacktrace + secret"}}
          out (rf/project-egress record
                {:frame :proj/err :rf.egress/profile :rf.egress/public-error})]
      (is (not (contains? out :exception))
          "public-error drops the internal :exception slot entirely")
      (is (= :rf.error/handler-exception (:error out)) "summary fields survive")
      (is (= :auth/login (:event-id out))))))

(deftest error-record-redacts-event-tree-slot
  (testing "error record :event tree slot is projected under the frame's policy"
    (mk-frame! :proj/err2)
    (let [record {:kind     :rf.observe/error
                  :frame    :proj/err2
                  :error    :rf.error/handler-exception
                  :event    [:auth/login {:auth {:token "super-secret-token"}}]}
          out (rf/project-egress record
                {:frame :proj/err2 :rf.egress/profile :rf.egress/off-box-tool})]
      (is (redacted? (get-in (:event out) [1 :auth :token]))
          "the sensitive token inside the error's :event is redacted"))))

;; ---------------------------------------------------------------------------
;; Frame-bearing record — opts omit :frame, the RECORD's own :frame governs
;; (rf2-vkblw4). The documented public call shape (Spec 015 §`project-egress`):
;; the record carries `:frame :app/main` and opts supply ONLY the profile.
;; Pre-fix the record's frame was silently dropped and the AMBIENT (fixture
;; `:rf/default`, no declarations) frame governed — so a record-frame-declared
;; sensitive arg rode RAW off-box (a privacy hole) and off-box tree slots were
;; over-redacted to `:rf/redacted` instead of projected under the record's
;; frame policy.
;; ---------------------------------------------------------------------------

(deftest record-frame-governs-when-opts-omit-frame
  (testing "opts carry ONLY the profile; the record's own :frame governs the walk"
    ;; `:proj/owned` declares [:auth :token] sensitive; the fixture's ambient
    ;; `:rf/default` declares NOTHING. Opts omit :frame, so pre-fix the ambient
    ;; default governed and the token rode RAW; post-fix the record's :frame is
    ;; seeded and the token is redacted (the privacy-critical leg).
    (mk-frame! :proj/owned)
    (let [record {:kind  :rf.observe/error
                  :frame :proj/owned
                  :error :rf.error/handler-exception
                  :event [:auth/login {:auth {:token "super-secret-token"}}]}
          out    (rf/project-egress
                   record
                   ;; NB: NO :frame opt — the documented record-owned-frame shape.
                   {:rf.egress/profile :rf.egress/off-box-tool})]
      (is (redacted? (get-in (:event out) [1 :auth :token]))
          "the record-frame-declared sensitive token is redacted (not shipped raw)")
      (is (= :proj/owned (:frame out)) "the summary :frame slot rides through"))))

(deftest explicit-frame-opt-wins-over-record-frame
  (testing "an explicit :frame opt OVERRIDES the record's own :frame (override semantics)"
    ;; `:proj/decl` declares [:auth :token] sensitive; `:proj/empty` declares
    ;; nothing. The record names :proj/empty but the caller deliberately
    ;; reclassifies under :proj/decl via an explicit opt — the opt must win.
    (mk-frame! :proj/decl)
    (rf/reg-frame :proj/empty {})
    (let [record {:kind  :rf.observe/error
                  :frame :proj/empty
                  :error :rf.error/handler-exception
                  :event [:auth/login {:auth {:token "super-secret-token"}}]}
          out    (rf/project-egress
                   record
                   {:frame :proj/decl
                    :rf.egress/profile :rf.egress/off-box-tool})]
      (is (redacted? (get-in (:event out) [1 :auth :token]))
          "the explicit :frame opt's policy governs, redacting the token"))))

;; ---------------------------------------------------------------------------
;; Closed enum + fail-closed.
;; ---------------------------------------------------------------------------

(deftest unknown-profile-throws
  (testing "an unknown :rf.egress/profile throws — the enum is closed"
    (let [thrown (try (rf/project-egress {} {:rf.egress/profile :rf.egress/bogus})
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))
          data   (ex-data thrown)
          msg    (ex-message thrown)]
      (is (= :rf.error/unknown-egress-profile (:rf.error/id data)))
      (is (= :rf.egress/bogus (:profile data)))
      ;; rf2-krrv87: the throw routes through the shared canonical builder, so
      ;; the message LEADS with a human sentence and TRAILS with the
      ;; [:rf.error/unknown-egress-profile] greppability token, and the ex-data
      ;; carries :where / :recovery / :valid.
      (is (error/message-has-id-token? msg)
          "the message carries the trailing greppability token (rule 4)")
      (is (not (error/keyword-only-message? msg))
          "the message is a human sentence, not a bare keyword (rule 1)")
      (is (= 'rf/project-egress (:where data))
          ":where names the in-file boundary helper")
      (is (= :use-a-known-profile (:recovery data)))
      (is (= projection/profiles (:valid data))
          "the closed valid-profile enum rides in ex-data"))))

(deftest unknown-egress-profile-ex-shared-across-call-sites
  (testing "rf2-krrv87: the shared `unknown-egress-profile-ex` builder yields an
            IDENTICAL canonical shape from both closed-enum guards — the only
            difference is the per-site :where symbol (the in-file
            `resolve-elision-opts` guard vs the epoch
            `resolve-egress-profile` guard). The duplicated hand-rolled
            ex-info that could silently drift is gone."
    (let [a    (projection/unknown-egress-profile-ex 'rf/project-egress :rf.egress/bogus)
          b    (projection/unknown-egress-profile-ex 'epoch/projected-record :rf.egress/bogus)
          da   (ex-data a)
          db   (ex-data b)]
      ;; message + every ex-data slot EXCEPT :where are identical across sites
      (is (= (ex-message a) (ex-message b))
          "the human message + token are byte-identical across call sites")
      (is (= (dissoc da :where) (dissoc db :where))
          "every ex-data slot but :where is identical")
      (is (= 'rf/project-egress (:where da)))
      (is (= 'epoch/projected-record (:where db)))
      (is (= :rf.error/unknown-egress-profile (:rf.error/id da) (:rf.error/id db)))
      (is (error/message-has-id-token? (ex-message a))))))

(deftest fails-closed-with-no-frame
  (testing "no known frame + no opt-out → the delegated walker fails closed"
    ;; The test fixture binds an ambient :rf/default frame; rebind it AWAY
    ;; so the call site is genuinely frameless (the off-box egress posture a
    ;; tool that reads outside any frame scope sees). project-egress must
    ;; NOT synthesise :rf/default — the delegated walker redacts the WHOLE
    ;; value (EP-0002 fail-closed).
    (binding [frame/*current-frame* nil]
      (let [out (rf/project-egress {:auth {:token "tok"}}
                  {:rf.egress/profile :rf.egress/off-box-observability})]
        (is (redacted? out)
            "frameless egress fails closed to :rf/redacted (no :rf/default)"))
      ;; The deliberate opt-out (include-sensitive? true) gets an identity
      ;; walk against the no-frame policy.
      (let [out (rf/project-egress {:auth {:token "tok"}}
                  {:rf.size/include-sensitive? true})]
        (is (= {:auth {:token "tok"}} out)
            "explicit include-sensitive? true is the deliberate frameless opt-out"))
      ;; rf2-vkblw4: the record-frame seed adds NOTHING when neither the record
      ;; nor opts name a frame — a frameless kindless record (or a record whose
      ;; :frame is nil) still FAILS CLOSED, never an empty-policy identity walk.
      (let [out (rf/project-egress {:kind :rf.observe/error :frame nil
                                    :event [:x {:auth {:token "tok"}}]}
                  {:rf.egress/profile :rf.egress/off-box-observability})]
        ;; No frame from the record (:frame nil) and none ambient ⇒ the walker
        ;; redacts the WHOLE :event slot, never an empty-policy identity walk.
        (is (redacted? (:event out))
            "a :frame nil record still fails closed (the seed is a no-op, no leak)")))))

(deftest no-profile-is-the-advanced-raw-flags-path
  (testing "with no profile, :rf.size/* flags pass through to the walker verbatim"
    (mk-frame! :proj/noprof)
    (let [out (rf/project-egress (sample-value)
                {:frame :proj/noprof
                 :rf.size/include-sensitive? false
                 :rf.size/include-large? false})]
      (is (redacted? (get-in out [:auth :token])))
      (is (large-marker? (get-in out [:docs :blob]))))))

;; ---------------------------------------------------------------------------
;; EP-0025 B4 (rf2-ojp8pi) + EP-0025 purge (rf2-j3jlgu) — the
;; `:rf.observe/derived-tree` record kind.
;;
;; A derived tree re-surfaces a frame's app-db-sensitive value at a NON-app-db
;; position the path walker cannot reach (a token copied out of `[:auth :token]`
;; into a rendered `[:input {:value <token>}]`). EP-0025 §"What is removed"
;; removed the VALUE-match (taint) redaction of such re-keyed copies — it is
;; propagation/taint by another name. So `:rf.observe/derived-tree` is now a
;; PATH-based projection: each tree slot is walked through `elide-wire-value`
;; against the frame's classification. A re-keyed value at a non-app-db position
;; ships RAW (intended FAIL-OPEN — hygiene, not a guarantee). A value sitting at
;; an actual app-db PATH within the tree IS redacted; local-raw passes through.
;; ---------------------------------------------------------------------------

(defn- derived-tree-with-token [token]
  ;; A rendered hiccup tree: the token sits at a NON-app-db position
  ;; (`[1 1 :value]`), not at `[:auth :token]` — the path walker is blind to it,
  ;; so EP-0025 fail-open ships it raw.
  [:form
   [:input {:type "password" :value token}]
   [:span "public label"]])

(deftest derived-tree-rekeyed-value-ships-raw-fail-open
  (testing "EP-0025: a re-keyed declared-sensitive value at a NON-app-db
            position in a derived tree ships RAW under off-box (the value-match
            redaction is removed — intended fail-open, hygiene not a guarantee)"
    (mk-frame! :proj/derived)
    (let [source-db {:auth {:token "super-secret-token"} :public {:count 3}}
          tree      (derived-tree-with-token "super-secret-token")
          out       (rf/project-egress
                      {:kind      :rf.observe/derived-tree
                       :frame     :proj/derived
                       :tree      tree
                       :source-db source-db}
                      {:rf.egress/profile :rf.egress/off-box-tool})]
      (is (= "super-secret-token" (get-in out [1 1 :value]))
          "the re-keyed sensitive token ships RAW (no value-match — fail-open)")
      (is (= "public label" (get-in out [2 1]))
          "a benign sibling leaf is untouched"))))

(deftest derived-tree-app-db-path-position-redacts
  (testing "EP-0025: a sensitive value sitting at its actual app-db PATH within a
            derived tree IS redacted (path-based projection)"
    (mk-frame! :proj/derived-path)
    (rf/reg-event :proj/seed-path
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:auth :token] "path-secret")
         :sensitive [[:auth :token]]}))
    (rf/with-frame :proj/derived-path
      (rf/dispatch-sync [:proj/seed-path]))
    ;; The derived tree is an app-db-shaped map whose [:auth :token] position
    ;; matches the frame's classified path — the path walker reaches it.
    (let [tree {:auth {:token "path-secret"} :public {:count 3}}
          out  (rf/project-egress
                 {:kind      :rf.observe/derived-tree
                  :frame     :proj/derived-path
                  :tree      tree}
                 {:rf.egress/profile :rf.egress/off-box-tool})]
      (is (redacted? (get-in out [:auth :token]))
          "a value at its classified app-db PATH redacts (path-based)")
      (is (= 3 (get-in out [:public :count]))
          "an unclassified sibling rides verbatim"))))

(deftest derived-tree-local-raw-passes-through
  (testing "under :rf.egress/local-raw the derived tree passes through verbatim
            (the trusted-local opt-out)"
    (mk-frame! :proj/derived-raw)
    (let [source-db {:auth {:token "super-secret-token"}}
          tree      (derived-tree-with-token "super-secret-token")
          out       (rf/project-egress
                      {:kind      :rf.observe/derived-tree
                       :frame     :proj/derived-raw
                       :tree      tree
                       :source-db source-db}
                      {:rf.egress/profile :rf.egress/local-raw})]
      (is (= tree out)
          "local-raw is the deliberate raw read — the tree crosses verbatim"))))

(deftest derived-tree-multi-slot-form-path-walks-named-slots
  (testing "EP-0025: the MULTI-SLOT form PATH-walks each present :slot-keys value
            of a map; a re-keyed (non-app-db-position) secret ships raw,
            non-named slots are untouched"
    (mk-frame! :proj/derived-multi)
    (let [source-db {:auth {:token "super-secret-token"}}
          explain   {:effective-args {:headers {:auth "super-secret-token"}}
                     :network        ["GET" "super-secret-token"]
                     :source-chain   [:a :b]}              ;; author prose — NOT a named slot
          out       (rf/project-egress
                      {:kind      :rf.observe/derived-tree
                       :frame     :proj/derived-multi
                       :tree      explain
                       :slot-keys [:effective-args :network]
                       :source-db source-db}
                      {:rf.egress/profile :rf.egress/off-box-tool})]
      ;; The secret sits at non-app-db positions in the named slots, so the
      ;; path-walk is a no-op for it (EP-0025 fail-open).
      (is (= "super-secret-token" (get-in out [:effective-args :headers :auth]))
          "a re-keyed secret in a named slot ships raw (fail-open)")
      (is (= "super-secret-token" (get-in out [:network 1]))
          "a re-keyed secret in a second named slot ships raw")
      (is (= [:a :b] (:source-chain out))
          "a NON-named author-prose slot is left untouched"))))
