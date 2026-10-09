(ns re-frame.projection-cljs-test
  "EP-0015 §10/§11 — record-level egress projection: `project-egress` and the
  six `:rf.egress/*` profiles, layered over `elide-wire-value`.

    - each profile's redaction / elision of a record carrying sensitive, large
      and plain slots, and an explicit `:rf.egress/*` override composing over
      the profile floor (override wins);
    - sensitive wins over large, on the frame walker and the path walker;
    - the recognised `:rf.observe/*` records project their own slots: off-box
      omits the handled-event `:event` args, `:rf.egress/public-error` drops
      the internal exception;
    - the closed profile enum, and frame ownership decided by key PRESENCE
      (an explicit opts `:frame`, nil included, then a recognised record's own
      `:frame`, then the carried scope), failing closed with no frame;
    - the `:rf.observe/derived-tree` path projection, and the late-bound
      `:rf/epoch-record` kind.

  Dual-runtime `*_cljs_test.cljc`: `npm run test:cljs` and `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            ;; The `:rf/epoch-record` arms bind the
            ;; `:epoch/project-record` hook explicitly (present and absent).
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.projection :as rf.projection]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; Over the 16384-byte default threshold, though [:docs :blob] is DECLARED
;; large, so the marker is deterministic regardless of threshold.
(def ^:private big-string (apply str (repeat 40000 \x)))

(defn- mk-frame!
  "A live frame classifying [:auth :token] sensitive and [:docs :blob] large,
  through the EP-0025 commit-plane effect path."
  [frame-id]
  (rf/make-frame {:id frame-id})
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:auth :token]]
                :large     [[:docs :blob]]}))))

(defn- sample-value []
  {:auth {:token "super-secret-token"}
   :docs {:blob big-string}
   :public {:count 3}})

(defn- redacted? [v] (= :rf/redacted v))
(defn- large-marker? [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

(defn- axes
  "The projected sample value's three slots: the token (`:redacted` or raw),
  the blob (`:marker`, `:whole` or itself) and the plain count."
  [out]
  (let [token (get-in out [:auth :token])
        blob  (get-in out [:docs :blob])]
    [(if (redacted? token) :redacted token)
     (cond (large-marker? blob) :marker (= big-string blob) :whole :else blob)
     (get-in out [:public :count])]))

;; ---------------------------------------------------------------------------
;; Profiles
;; ---------------------------------------------------------------------------

(deftest profile-resolves-to-size-opts
  ;; The closed six. Every boundary but local-raw redacts sensitive; only
  ;; local-raw and ssr-hydration keep large (the hydration payload is the
  ;; browser's live state, not a tool budget); no profile turns digests on.
  (let [opts (fn [sensitive? large?]
               {:rf.egress/include-sensitive? sensitive?
                :rf.egress/include-large?     large?
                :rf.egress/include-digests?   false})]
    (is (= {:rf.egress/off-box-observability (opts false false)
            :rf.egress/off-box-tool          (opts false false)
            :rf.egress/local-redacted        (opts false false)
            :rf.egress/public-error          (opts false false)
            :rf.egress/ssr-hydration         (opts false true)
            :rf.egress/local-raw             (opts true true)}
           (into {} (map (juxt identity rf.projection/profile-size-opts)) rf.projection/profiles)))
    (is (= [nil nil] (map rf.projection/profile-size-opts [:rf.egress/bogus nil])))))

(defn- project-sample [frame-id profile]
  (axes (rf/project-egress (sample-value) {:frame frame-id :rf.egress/profile profile})))

(deftest off-box-profiles-redact-sensitive-and-elide-large
  ;; ssr-hydration redacts sensitive but keeps large whole: the payload is the
  ;; browser's live state, not a tool budget.
  (mk-frame! :proj/offbox)
  (is (= {:rf.egress/off-box-observability [:redacted :marker 3]
          :rf.egress/off-box-tool          [:redacted :marker 3]
          :rf.egress/local-redacted        [:redacted :marker 3]
          :rf.egress/public-error          [:redacted :marker 3]
          :rf.egress/ssr-hydration         [:redacted :whole 3]}
         (into {} (map (juxt identity #(project-sample :proj/offbox %)))
               [:rf.egress/off-box-observability :rf.egress/off-box-tool :rf.egress/local-redacted
                :rf.egress/public-error :rf.egress/ssr-hydration]))))

(deftest local-raw-includes-sensitive-and-large
  (mk-frame! :proj/raw)
  (is (= ["super-secret-token" :whole 3] (project-sample :proj/raw :rf.egress/local-raw))))

;; A marker carries `:digest` only when a digest string was computed. The
;; browser build computes none (`sha256-hex` is `nil` there), so `->marker`
;; omits the slot: a `:digest nil` on every browser-side marker would fail the
;; normative `:rf/elision-marker` schema and read as a false "unchanged". This
;; is the only test that grades that `#?(:cljs …)` branch.
(deftest marker-digest-is-a-string-or-absent-never-nil
  (mk-frame! :proj/digest)
  (let [marker-body (fn [opts]
                      (get-in (rf/project-egress (sample-value)
                                                 (merge {:frame :proj/digest
                                                         :rf.egress/profile :rf.egress/off-box-tool}
                                                        opts))
                              [:docs :blob :rf.size/large-elided]))
        override-body (marker-body {:rf.egress/include-digests? true})]
    (is (= [true false] ((juxt map? #(contains? % :digest)) (marker-body {})))
        "off-box-tool carries no digest by default, on either host")
    #?(:clj  (is (string? (:digest override-body))
                 "the JVM computes the digest the explicit override asks for")
       :cljs (is (= [true false] ((juxt map? #(contains? % :digest)) override-body))
                 "the browser build computes none, so the slot is omitted"))))

(deftest profile-plus-override-composes
  ;; An explicit :rf.egress/* boolean overlays the profile floor; the
  ;; un-overridden axis stays at the floor.
  (mk-frame! :proj/compose)
  (is (= [["super-secret-token" :marker 3] ["super-secret-token" :marker 3]]
         (mapv #(axes (rf/project-egress (sample-value) (merge {:frame :proj/compose} %)))
               [{:rf.egress/profile :rf.egress/off-box-observability :rf.egress/include-sensitive? true}
                {:rf.egress/profile :rf.egress/local-raw :rf.egress/include-large? false}]))))

(deftest unknown-profile-throws
  ;; The enum is closed. The throw routes through the shared canonical
  ;; builder: a human sentence trailed by the greppability token.
  (let [thrown (try (rf/project-egress {} {:rf.egress/profile :rf.egress/bogus})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))
        msg    (ex-message thrown)]
    (is (= {:rf.error/id :rf.error/unknown-egress-profile
            :profile     :rf.egress/bogus
            :where       'rf/project-egress
            :recovery    :use-a-known-profile
            :valid       rf.projection/profiles}
           (select-keys (ex-data thrown) [:rf.error/id :profile :where :recovery :valid])))
    (is (= [true false] [(rf.error/message-has-id-token? msg) (rf.error/keyword-only-message? msg)]))))

;; ---------------------------------------------------------------------------
;; Sensitive wins over large
;; ---------------------------------------------------------------------------

(deftest sensitive-wins-over-large
  ;; A both-marked path redacts and never large-elides, so no path, size or
  ;; digest can leak.
  (rf/make-frame {:id :proj/both})
  (rf.frame/swap-runtime-db! :proj/both
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:secret]]
                :large     [[:secret]]})))
  (is (= {:secret :rf/redacted}
         (rf/project-egress {:secret big-string}
                            {:frame :proj/both :rf.egress/profile :rf.egress/off-box-observability}))))

(deftest path-walker-nested-large-over-sensitive-descendant-redacts
  ;; `rf.classification/redact-with-paths` walks event arg-maps, sub outputs,
  ;; fx args and cofx values. A :large path with a :sensitive descendant
  ;; descends and redacts, emitting no marker (Spec 015 §No-propagation, a
  ;; MUST), for a nested and a whole-value large path alike; with no
  ;; sensitive descendant it still marks.
  (is (= [{:a {:b :rf/redacted :c "public"}} {:b :rf/redacted :other "ok"}]
         [(rf.classification/redact-with-paths {:a {:b "PATH-WALKER-SECRET" :c "public"}} [[:a :b]] [[:a]])
          (rf.classification/redact-with-paths {:b "WHOLE-VALUE-SECRET" :other "ok"} [[:b]] [[]])]))
  (is (large-marker? (:a (rf.classification/redact-with-paths {:a {:b "x"}} [[:elsewhere]] [[:a]])))))

;; ---------------------------------------------------------------------------
;; Recognised :rf.observe/* records
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
  ;; Off-box carries the summary fields and omits the :event args slot
  ;; entirely; trusted-local keeps it, projected through the walker.
  (mk-frame! :proj/he)
  (let [record     (handled-event-record :proj/he)
        project-as #(rf/project-egress record {:frame :proj/he :rf.egress/profile %})]
    (is (= [(dissoc record :event) [:auth/login {:password "secret"}]]
           [(project-as :rf.egress/off-box-observability)
            (:event (project-as :rf.egress/local-raw))]))))

(deftest public-error-drops-internal-exception
  (mk-frame! :proj/err)
  (let [out (rf/project-egress {:kind      :rf.observe/error
                                :frame     :proj/err
                                :error     :rf.error/handler-exception
                                :event-id  :auth/login
                                :event     [:auth/login {:auth {:token "tok"}}]
                                :exception {:internal "raw stacktrace + secret"}}
                               {:frame :proj/err :rf.egress/profile :rf.egress/public-error})]
    (is (= [false :rf.error/handler-exception :auth/login]
           [(contains? out :exception) (:error out) (:event-id out)]))))

;; ---------------------------------------------------------------------------
;; Frame ownership, decided by key PRESENCE: an explicit opts `:frame` (nil
;; included), else a RECOGNISED record's own `:frame` (nil included), else the
;; carried scope. Truthiness would make `{:frame nil}` unsayable: it would
;; borrow the ambient frame, which for a tool projecting another app's value
;; is live with an empty registry, so the value would ship RAW.
;; ---------------------------------------------------------------------------

(defn- error-record [frame-id]
  {:kind  :rf.observe/error
   :frame frame-id
   :error :rf.error/handler-exception
   :event [:auth/login {:auth {:token "super-secret-token"}}]})

(deftest record-frame-governs-when-opts-omit-frame
  ;; The fixture's ambient :rf/default declares nothing, so a token redacted
  ;; here was redacted under the governing frame's policy. With opts omitting
  ;; :frame the record's own frame governs; an explicit :frame opt overrides
  ;; the record's.
  (mk-frame! :proj/decl)
  (rf/make-frame {:id :proj/empty})
  (let [token (fn [out] (get-in (:event out) [1 :auth :token]))
        owned (rf/project-egress (error-record :proj/decl) {:rf.egress/profile :rf.egress/off-box-tool})]
    (is (= [:rf/redacted :proj/decl :rf/redacted]
           [(token owned)
            (:frame owned)
            (token (rf/project-egress (error-record :proj/empty)
                                      {:frame :proj/decl :rf.egress/profile :rf.egress/off-box-tool}))]))))

(deftest fails-closed-with-no-frame
  ;; With the ambient frame rebound away, project-egress synthesises no
  ;; :rf/default: the delegated walker redacts the whole value (EP-0002), a
  ;; `:frame nil` record seeds nothing and still fails closed, and only the
  ;; deliberate include-sensitive? opt-out walks against the empty policy.
  (binding [rf.frame/*current-frame* nil]
    (is (= [:rf/redacted :rf/redacted {:auth {:token "tok"}}]
           [(rf/project-egress {:auth {:token "tok"}}
                               {:rf.egress/profile :rf.egress/off-box-observability})
            (:event (rf/project-egress {:kind :rf.observe/error :frame nil :event [:x {:auth {:token "tok"}}]}
                                       {:rf.egress/profile :rf.egress/off-box-observability}))
            (rf/project-egress {:auth {:token "tok"}} {:rf.egress/include-sensitive? true})]))))

(deftest explicit-nil-frame-opt-wins-over-the-ambient-scope
  ;; The ambient frame is live and classifying, so a borrow is observable.
  (mk-frame! :proj/ambient)
  (mk-frame! :proj/record-owner)
  (binding [rf.frame/*current-frame* :proj/ambient]
    ;; Precondition and step 3: with no :frame key the ambient frame's
    ;; policy applies, as a real walk rather than a whole-value redact.
    (is (= [true [:redacted :marker 3]]
           [(some? (rf.frame/frame :proj/ambient)) (axes (rf/project-egress (sample-value) {}))]))
    ;; An explicit nil opt fails closed for a bare value, and overrides a
    ;; recognised record's own live frame (the derived-tree projector returns
    ;; the walked tree, the error projector the record).
    (is (= [:rf/redacted :rf/redacted :rf/redacted]
           [(rf/project-egress (sample-value) {:frame nil})
            (rf/project-egress {:kind :rf.observe/derived-tree :frame :proj/record-owner :tree (sample-value)}
                               {:frame nil :rf.egress/profile :rf.egress/off-box-tool})
            (:event (rf/project-egress (error-record :proj/record-owner)
                                       {:frame nil :rf.egress/profile :rf.egress/off-box-tool}))]))))

(deftest a-record-with-an-explicit-nil-frame-slot-fails-closed
  ;; A recognised record carrying `:frame nil`, with no opts :frame key, seeds
  ;; nil at step 2 rather than borrowing the ambient frame; the same record
  ;; naming a live frame walks it.
  (mk-frame! :proj/ambient2)
  (mk-frame! :proj/owner)
  (binding [rf.frame/*current-frame* :proj/ambient2]
    (let [project (fn [frame-id]
                    (rf/project-egress {:kind :rf.observe/derived-tree :frame frame-id :tree (sample-value)}
                                       {:rf.egress/profile :rf.egress/off-box-tool}))]
      (is (= [:rf/redacted [:redacted :marker 3]]
             [(project nil) (axes (project :proj/owner))])))))

(deftest a-bare-value-carrying-a-frame-key-is-a-value-not-a-record
  ;; Only recognised records seed, so a kindless map's :frame key seeds
  ;; nothing and, with no ambient frame, the whole value fails closed.
  (mk-frame! :proj/owner3)
  (binding [rf.frame/*current-frame* nil]
    (is (= :rf/redacted
           (rf/project-egress {:frame :proj/owner3 :auth {:token "tok"}}
                              {:rf.egress/profile :rf.egress/off-box-tool})))))

;; ---------------------------------------------------------------------------
;; EP-0025 — the `:rf.observe/derived-tree` record kind.
;;
;; A derived tree re-surfaces a frame's app-db value at positions the path
;; walker may not reach (a token copied into a rendered `[:input {:value …}]`).
;; EP-0025 excludes value-match (taint) redaction, so each tree slot is walked
;; through `elide-wire-value` against the frame's classification: a value at
;; an actual app-db PATH redacts, and a re-keyed copy ships RAW under a live
;; frame (intended fail-open, hygiene not a guarantee). With NO live frame and
;; no explicit raw opt-in the tree fails closed, matching the walker, Spec 015
;; §Direct reads and the generic no-frame case.
;; ---------------------------------------------------------------------------

(defn- derived-tree-with-token [token]
  [:form
   [:input {:type "password" :value token}]
   [:span "public label"]])

(def ^:private explain
  {:effective-args {:headers {:auth "super-secret-token"}}
   :network        ["GET" "super-secret-token"]
   :source-chain   [:a :b]})                      ;; author prose — not a named slot

(deftest derived-tree-app-db-path-position-redacts
  (mk-frame! :proj/derived-path)
  (rf/reg-event :proj/seed-path
    (fn [{:keys [db]} _]
      {:db        (assoc-in db [:auth :token] "path-secret")
       :sensitive [[:auth :token]]}))
  (rf/with-frame :proj/derived-path
    (rf/dispatch-sync [:proj/seed-path]))
  (is (= {:auth {:token :rf/redacted} :public {:count 3}}
         (rf/project-egress {:kind  :rf.observe/derived-tree
                             :frame :proj/derived-path
                             :tree  {:auth {:token "path-secret"} :public {:count 3}}}
                            {:rf.egress/profile :rf.egress/off-box-tool}))))

(deftest derived-tree-multi-slot-form-path-walks-named-slots
  ;; Under a live frame each named :slot-keys value is path-walked, so a
  ;; re-keyed secret at a non-app-db position ships raw (fail-open), and the
  ;; un-named slot is untouched.
  (mk-frame! :proj/derived-multi)
  (is (= explain
         (rf/project-egress {:kind      :rf.observe/derived-tree
                             :frame     :proj/derived-multi
                             :tree      explain
                             :slot-keys [:effective-args :network]}
                            {:rf.egress/profile :rf.egress/off-box-tool}))))

(deftest derived-tree-without-a-live-frame-fails-closed
  ;; A nil, never-registered or destroyed :frame names no policy-bearing
  ;; frame: the single tree redacts whole, and in the :slot-keys form each
  ;; named slot does while the un-named slot is never walked.
  (mk-frame! :proj/derived-destroyed)
  (rf/destroy-frame! :proj/derived-destroyed)
  (binding [rf.frame/*current-frame* nil]
    (doseq [frame [nil :proj/never-registered :proj/derived-destroyed]]
      (is (= [:rf/redacted {:effective-args :rf/redacted :network :rf/redacted :source-chain [:a :b]}]
             [(rf/project-egress {:kind :rf.observe/derived-tree :frame frame
                                  :tree (derived-tree-with-token "super-secret-token")}
                                 {:rf.egress/profile :rf.egress/off-box-tool})
              (rf/project-egress {:kind :rf.observe/derived-tree :frame frame
                                  :tree explain :slot-keys [:effective-args :network]}
                                 {:rf.egress/profile :rf.egress/off-box-tool})])
          (pr-str frame)))))

(deftest derived-tree-no-live-frame-local-raw-opt-out-still-raw
  ;; The local-raw profile, and the bare include-sensitive? override, are the
  ;; one deliberate way to cross a frameless tree raw.
  (binding [rf.frame/*current-frame* nil]
    (let [tree (derived-tree-with-token "super-secret-token")]
      (is (= [tree tree]
             (mapv #(rf/project-egress {:kind :rf.observe/derived-tree :frame nil :tree tree} %)
                   [{:rf.egress/profile :rf.egress/local-raw}
                    {:rf.egress/include-sensitive? true}]))))))

;; ---------------------------------------------------------------------------
;; `:rf/epoch-record` — the LATE-BOUND record kind.
;;
;; `:rf/epoch-record` is owned by the OPTIONAL `day8/re-frame2-epoch` artefact,
;; so its projector arrives through the `:epoch/project-record` late-bind hook.
;; These arms pin the DOOR's half: the kind is recognised, the resolved frame
;; reaches the projector, and (guard G1) an ABSENT projector throws rather than
;; falling through to the kindless walk. The artefact's half is pinned in
;; `re-frame.epoch-egress-redaction-cljs-test`. Sibling test namespaces load
;; `re-frame.epoch`, so each arm binds the hook explicitly.
;; ---------------------------------------------------------------------------

(defn- with-epoch-projector
  "Run `f` with the `:epoch/project-record` late-bind hook bound to `v`
  (`nil` simulating an absent artefact), restoring the previous value
  afterwards even when `f` throws."
  [v f]
  (let [original (rf.late-bind/get-fn :epoch/project-record)]
    (try
      (rf.late-bind/set-fn! :epoch/project-record v)
      (f)
      (finally (rf.late-bind/set-fn! :epoch/project-record original)))))

(defn- epoch-record [frame-id]
  {:kind     :rf/epoch-record
   :epoch-id 1
   :frame    frame-id
   :db-after (sample-value)})

(deftest epoch-record-is-a-recognised-kind
  ;; The door returns the projector's value verbatim, handing it the record
  ;; and opts whose :frame the door resolved from the record's own slot; the
  ;; named boundary rides through unresolved, for the projector to resolve
  ;; once per slot.
  (let [fid  :projection-test/epoch-kind
        seen (atom nil)]
    (mk-frame! fid)
    (with-epoch-projector
      (fn [record opts] (reset! seen [record opts]) ::projected)
      (fn []
        (let [out (rf/project-egress (epoch-record fid) {:rf.egress/profile :rf.egress/off-box-tool})
              [record opts] @seen]
          (is (= [::projected :rf/epoch-record fid :rf.egress/off-box-tool]
                 [out (:kind record) (:frame opts) (:rf.egress/profile opts)])))))))

(deftest epoch-record-explicit-frame-override-reaches-the-projector
  ;; An explicit :frame opt wins over the record's own slot, nil included and
  ;; present, so the projector never re-reads the record's slot behind it.
  (let [fid  :projection-test/epoch-frame-override
        seen (atom [])]
    (mk-frame! fid)
    (with-epoch-projector
      (fn [_record opts] (swap! seen conj opts) ::projected)
      (fn []
        (doseq [frame [:projection-test/other nil]]
          (rf/project-egress (epoch-record fid) {:rf.egress/profile :rf.egress/off-box-tool :frame frame}))
        (is (= [[true :projection-test/other] [true nil]]
               (mapv (juxt #(contains? % :frame) :frame) @seen)))))))

(deftest guard-g1-absent-epoch-projector-throws-and-never-bare-walks
  ;; A RECOGNISED kind whose projector is absent throws before returning any
  ;; payload. The contrast shows why: an UNSTAMPED epoch-shaped map is a
  ;; kindless value walked from :path [], where the frame's [:auth :token]
  ;; declaration cannot match [:db-after :auth :token], so it ships RAW.
  (let [fid    :projection-test/epoch-g1
        record (epoch-record fid)
        opts   {:rf.egress/profile :rf.egress/off-box-tool}]
    (mk-frame! fid)
    (with-epoch-projector nil
      (fn []
        (let [thrown (try (rf/project-egress record opts)
                          nil
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
          (is (= [{:rf.error/id :rf.error/epoch-artefact-missing :where 'rf/project-egress :kind :rf/epoch-record}
                  true
                  "super-secret-token"]
                 [(select-keys (ex-data thrown) [:rf.error/id :where :kind])
                  (rf.error/message-has-id-token? (ex-message thrown))
                  (get-in (rf/project-egress (dissoc record :kind) opts) [:db-after :auth :token])])))))))
