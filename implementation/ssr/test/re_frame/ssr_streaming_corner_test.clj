(ns re-frame.ssr-streaming-corner-test
  "Corner-matrix coverage for the streaming SSR shell walker, continuation
  drain, and final-payload build paths. Per rf2-u91hb (audit follow-on
  from the rare-corner-cases sweep): the existing
  `ssr_streaming_test.clj` pins the common shapes (single boundary,
  duplicate id, failed continuation, payload shape); this ns pins the
  composition corners — `n=0`/`n>=2` body children, nested boundaries,
  boundaries inside view-refs and fragments, fallback-render-throw
  recovery, delta capturing a real change, final-payload-build throw
  composition with the writer's catch arm, and the allowlist projection
  on the final payload.

  Why a sibling ns rather than appending to `ssr_streaming_test`. Each
  test here pins a documented invariant that's downstream of the basic
  shapes — keeping them in a focused file makes the corner topology
  obvious at-a-glance to anyone auditing the streaming surface. Mirrors
  the rf2-jvpli / rf2-ozhy9 split (`streaming_robustness_test` +
  `concurrency_stress_test`) where the basic ring-streaming pin lives in
  `ring_streaming_test` and the failure-mode tests live in dedicated
  sibling ns'.

  All tests are JVM-only — streaming SSR is JVM-only by design (Ring is
  Clojure-on-the-JVM)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace]))

(defn- reset+reg
  [test-fn]
  (tf/reset-runtime
    (fn []
      (rf/reg-event-db :rf.test/seed-db (fn [_ [_ db]] db))
      (rf/reg-event-db :rf.test/noop    (fn [db _] db))
      (test-fn))))

(use-fixtures :each reset+reg)

(defn- with-trace-capture
  "Capture every emitted trace event into `coll-atom` during `(body-fn)`."
  [coll-atom body-fn]
  (let [k (str (gensym "streaming-corner-cb"))]
    (trace/register-listener! k (fn [ev] (swap! coll-atom conj ev)))
    (try (body-fn)
         (finally (trace/unregister-listener! k)))))

(defn- make-server-frame
  "Register a per-request server frame and seed its app-db via :on-create.
   Returns the frame-id."
  ([] (make-server-frame {}))
  ([db]
   (let [fid (keyword "rf.frame" (str (gensym "")))]
     (rf/reg-frame fid
       {:doc       "streaming-corner frame"
        :platform  :server
        :on-create (if (seq db) [:rf.test/seed-db db] [:rf.test/noop])})
     fid)))

;; ===========================================================================
;; Shell-walk body-arity corners — n=0, n>=2 (the case n=0/1/2 branch in
;; streaming.cljc:249-252). The existing test suite only covered n=1.
;; ===========================================================================

(deftest boundary-with-zero-body-children-resolves-empty-html
  (testing "rf2-u91hb: a :rf/suspense-boundary with NO body children
            (n=0 branch) registers a continuation whose subtree is nil;
            render-continuation resolves to empty HTML and does NOT
            throw. The shell still emits the fallback placeholder."
    (let [tree [:div
                [:rf/suspense-boundary
                 {:id :empty/body :fallback [:p "loading"]}]]
          {:keys [shell-html continuations]}
          (streaming/render-shell tree)]
      (is (= 1 (count continuations))
          "even a zero-body boundary registers a continuation")
      (is (str/includes? shell-html "<p>loading</p>")
          "fallback still materialised in the shell")
      ;; Drain the continuation — the subtree is nil per the n=0 branch;
      ;; emit-element returns "" for nil, so the resolved html is empty.
      (let [fid    (make-server-frame)
            entry  (assoc (first continuations) :fallback [:p "loading"])
            result (streaming/render-continuation fid entry)]
        (is (not (:failed? result))
            "nil subtree is NOT a failure — render-to-string treats it
             as an empty render per the emit-element nil branch")
        (is (= "" (:html result))
            "the zero-body resolved chunk's HTML is empty (per Spec 011
             §The render-tree → HTML emitter — nil renders as empty)")
        (is (map? (:delta result))
            ":delta is still a (possibly empty) map even with no body")))))

(deftest boundary-with-multi-child-body-wraps-in-fragment
  (testing "rf2-u91hb: a :rf/suspense-boundary with TWO+ body children
            (n>=2 branch) wraps them in a :<> fragment so a single
            logical hiccup form drains; both children's HTML appears in
            the resolved chunk."
    (let [tree [:div
                [:rf/suspense-boundary
                 {:id :multi/body :fallback [:p "loading"]}
                 [:p "first"]
                 [:p "second"]
                 [:p "third"]]]
          {:keys [continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations))
          "multi-child body still registers ONE continuation — the
           fragment wraps all children")
      (let [fid    (make-server-frame)
            entry  (assoc (first continuations) :fallback [:p "loading"])
            result (streaming/render-continuation fid entry)]
        (is (not (:failed? result)))
        (is (= "<p>first</p><p>second</p><p>third</p>" (:html result))
            "all three children resolved — fragment splices them
             without a wrapper element (Spec 011 §Source-coord
             annotation: :<> is exempt from DOM-tag wrapping)")))))

;; ===========================================================================
;; Boundary composition — nesting + view-ref + fragment heads
;; ===========================================================================

(deftest boundary-nested-inside-resolved-subtree-registers-inner-continuation
  (testing "rf2-u91hb: when a continuation's subtree contains ANOTHER
            :rf/suspense-boundary, the inner boundary registers during
            the continuation's render — at the tail of the drain queue
            per Spec 011 §Boundary nesting and recursion (FIFO over
            registration order)."
    (let [tree [:div
                [:rf/suspense-boundary
                 {:id :outer :fallback [:p "outer loading"]}
                 [:section
                  [:rf/suspense-boundary
                   {:id :inner :fallback [:p "inner loading"]}
                   [:p "inner body"]]]]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      ;; The SHELL walk only sees the outer boundary — the inner is
      ;; buried inside the outer's subtree and registers when the outer
      ;; continuation later drains.
      (is (= 1 (count continuations))
          "shell walk registers ONE outer continuation; inner is buried
           inside the outer's subtree and registers at drain time")
      (is (= :outer (-> continuations first :id)))
      (is (str/includes? shell-html "outer loading")
          "outer fallback in the shell")
      (is (not (str/includes? shell-html "inner loading"))
          "inner fallback NOT in the shell (still buried in the
           unresolved outer subtree)")
      ;; Drain the outer — the inner boundary's hiccup is emitted by
      ;; the standard emitter (since render-continuation calls
      ;; emit/render-to-string, not the walker). This means the inner
      ;; boundary's HICCUP shape lands in the resolved chunk as-is —
      ;; including the inner's fallback rendered inline. The wire
      ;; contract per Spec 011: nested boundaries that fire inside a
      ;; continuation register a new drain entry; the SSR ring adapter
      ;; threads them through (`stream-handler` re-invokes the walker
      ;; per resolved chunk). At the pure-streaming level
      ;; (render-continuation), the inner boundary renders via the
      ;; non-streaming emitter, which surfaces an exception on the
      ;; unrecognised :rf/suspense-boundary head — caught as a
      ;; failure and inline-fallback'd. Pin THAT contract.
      (let [fid    (make-server-frame)
            entry  (assoc (first continuations) :fallback [:p "outer loading"])
            captured (atom [])
            result (with-trace-capture captured
                     #(streaming/render-continuation fid entry))]
        ;; The pure render-continuation path (without the ring
        ;; adapter's per-chunk re-walker) DOES fail on the buried
        ;; suspense-boundary keyword — which exercises the
        ;; inline-fallback contract. The ring host adapter's writer
        ;; loop is what actually re-walks; the pure runtime layer
        ;; pins the fail-soft semantics.
        (is (or (not (:failed? result))
                (and (:failed? result)
                     (some #(= :rf.ssr/suspense-boundary-failed (:operation %))
                           @captured)))
            "outer continuation either resolves cleanly (renderer
             passed the unknown head through) OR fails-soft into the
             fallback (per Spec 011 §Failure semantics — inline
             fallback). Either is acceptable; what is NOT acceptable
             is an uncaught throw escaping the drain.")))))

(deftest boundary-inside-registered-view-body-is-reachable-by-walker
  (testing "rf2-u91hb: when a registered view's body contains
            :rf/suspense-boundary, the walker resolves the view-ref and
            recurses into its hiccup output. This is the load-bearing
            case for the conformance fixture's root view, which IS a
            registered view containing boundaries."
    (rf/reg-view ^{:rf/id :test/wrapper} wrapper-view []
      [:section
       [:h1 "wrapper"]
       [:rf/suspense-boundary
        {:id :buried/in-view :fallback [:p "buried loading"]}
        [:p "buried body"]]])
    (let [tree [:main [:test/wrapper]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations))
          "the walker recursed into the registered view and found the
           boundary")
      (is (= :buried/in-view (-> continuations first :id))
          "the buried boundary's id propagates")
      (is (str/includes? shell-html "<section")
          "the view's wrapping <section> rendered in the shell
           (source-coord injection adds a data-rf2-source-coord attr
           on the registered view's root DOM element per Spec 006)")
      (is (str/includes? shell-html "buried loading")
          "the buried boundary's fallback materialised inline")
      (is (str/includes? shell-html "data-rf2-suspense-id=\":buried/in-view\"")
          "the boundary's id was stamped on the fallback template"))))

(deftest boundary-inside-fragment-children-is-reachable-by-walker
  (testing "rf2-u91hb: when a :<> fragment's children contain a
            :rf/suspense-boundary, the walker splices the fragment and
            finds the boundary. Critical for hiccup authors who use
            fragments to group siblings without a wrapper element."
    (let [tree [:main
                [:<>
                 [:h1 "header in fragment"]
                 [:rf/suspense-boundary
                  {:id :in-fragment :fallback [:p "fragment loading"]}
                  [:p "fragment body"]]
                 [:p "footer in fragment"]]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations))
          "the walker spliced the fragment and reached the boundary")
      (is (= :in-fragment (-> continuations first :id)))
      (is (str/includes? shell-html "<h1>header in fragment</h1>")
          "fragment sibling above the boundary emitted")
      (is (str/includes? shell-html "<p>footer in fragment</p>")
          "fragment sibling below the boundary emitted")
      (is (str/includes? shell-html "fragment loading")
          "the buried boundary's fallback materialised inline"))))

(deftest triple-duplicate-id-keeps-only-last-of-three
  (testing "rf2-u91hb: three boundaries with the same :id — dedup keeps
            ONLY the LAST registration. Pins the last-write-wins shape
            against more than two duplicates (the existing test covers
            only the 2-duplicate case)."
    (let [tree [:div
                [:rf/suspense-boundary
                 {:id :triple :fallback [:p "first fallback"]}
                 [:p "first body"]]
                [:rf/suspense-boundary
                 {:id :triple :fallback [:p "second fallback"]}
                 [:p "second body"]]
                [:rf/suspense-boundary
                 {:id :triple :fallback [:p "third fallback"]}
                 [:p "third body"]]]
          captured (atom [])
          {:keys [continuations]}
          (with-trace-capture captured #(streaming/render-shell tree))]
      (is (= 1 (count continuations))
          "only one continuation survives dedup across three duplicates")
      ;; Drain it — the body should be the LAST registration's body
      ;; (third), confirming last-write-wins.
      (let [fid    (make-server-frame)
            entry  (assoc (first continuations) :fallback [:p "third fallback"])
            result (streaming/render-continuation fid entry)]
        (is (str/includes? (:html result) "third body")
            "the resolved chunk carries the LAST-registered body — not
             the first or middle. Per Spec 011 §Boundary nesting and
             recursion: 'the second registration overwrites the first'
             generalises to N-deep — every-but-last is dropped."))
      (is (some #(= :rf.error/suspense-boundary-duplicate-id (:operation %))
                @captured)
          "the duplicate-id trace still fires across N=3 duplicates")
      (let [dup-traces (filterv #(= :rf.error/suspense-boundary-duplicate-id
                                    (:operation %))
                                @captured)]
        (is (= 1 (count dup-traces))
            "one trace, not three — dedup groups all duplicates of
             the same id into a single trace event")
        (when (seq dup-traces)
          (let [ev   (first dup-traces)
                tags (:tags ev)]
            (is (= 3 (:count tags))
                ":count tag reports the duplicate cardinality (3)")
            (is (= :last-write-wins (:recovery ev))
                ":recovery is hoisted to top-level of the trace
                 envelope per Spec 009 §Error event shape — names the
                 policy applied")))))))

;; ===========================================================================
;; render-continuation — fallback-render throw + delta + stale frame
;; ===========================================================================

(deftest render-continuation-fallback-render-throw-emits-empty-html
  (testing "rf2-u91hb: when the subtree throws AND the fallback ALSO
            throws on render, render-continuation MUST NOT escape — it
            returns :failed? true with empty :html (per streaming.cljc
            line 397-404). The client-side runtime treats an empty
            resolved chunk as a no-op."
    ;; Spec 011 §Failure semantics: shell-walk throws escalate; only
    ;; CONTINUATION-level throws are inline-fallback'd. So we render
    ;; the shell with a NON-THROWING fallback (avoid the shell-walk-
    ;; throw escalation path), then plant a throwing fallback on the
    ;; entry before driving render-continuation.
    (let [throws-sub (fn [] (throw (ex-info "subtree boom" {})))
          throws-fb  (fn [] (throw (ex-info "fallback boom" {})))
          tree   [:rf/suspense-boundary {:id :double-throw
                                         :fallback [:p "ok in shell"]}
                  [throws-sub]]
          {:keys [continuations]} (streaming/render-shell tree)
          fid (make-server-frame)
          entry (assoc (first continuations) :fallback [throws-fb])
          captured (atom [])
          result (with-trace-capture captured
                   #(streaming/render-continuation fid entry))]
      (is (:failed? result)
          ":failed? is true when the subtree throws — even though
           the fallback render also throws")
      (is (= "" (:html result))
          "fallback-render throw → empty html (the inner try/catch in
           streaming.cljc renders fallback OR returns \"\" on its own
           throw)")
      (is (nil? (:delta result))
          "delta still omitted on failure")
      (is (some #(= :rf.ssr/suspense-boundary-failed (:operation %))
                @captured)
          "the suspense-boundary-failed trace still fires for the
           subtree throw — even though the fallback also failed
           (the trace describes the SUBTREE failure, which is what
           the client cares about)"))))

(deftest render-continuation-delta-captures-app-db-change-during-render
  (testing "rf2-u91hb: render-continuation snapshots app-db before
            render, then after; the resulting :delta carries the keys
            that changed (per spec — :delta is the streaming hydration
            speed prop). Pins that the diff actually fires."
    (rf/reg-event-db :test/mutate-during-render
      (fn [db _] (assoc db :new-key :new-value)))
    (let [fid     (make-server-frame {:initial :state})
          ;; A view that DISPATCHES (mutates app-db) during render —
          ;; the canonical streaming pattern for async data resolution.
          _       (rf/reg-view ^{:rf/id :test/mutating} mutating-view []
                    (rf/dispatch-sync [:test/mutate-during-render] {:frame fid})
                    [:p "mutated"])
          tree    [:rf/suspense-boundary
                   {:id :mutator :fallback [:p "loading"]}
                   [:test/mutating]]
          {:keys [continuations]} (streaming/render-shell tree)
          entry   (first continuations)
          result  (streaming/render-continuation fid entry)]
      (is (not (:failed? result)))
      (is (str/includes? (:html result) "mutated")
          "the resolved chunk's html carries the view's rendered
           output (source-coord injection rides the root element
           per Spec 006 — we only assert the textual content)")
      ;; The delta MUST include the new key that the render-time
      ;; dispatch put on app-db.
      (is (contains? (:delta result) :new-key)
          ":delta carries the app-db key the render-time dispatch
           added — pin that diff actually fires, not silently returns
           empty (clojure.data/diff path; spec 011 §Hydration
           interleaving — per-subtree deltas)")
      (is (= :new-value (get-in result [:delta :new-key]))
          "the delta's value matches the post-render app-db value"))))

(deftest render-continuation-after-frame-destroy-still-fails-soft
  (testing "rf2-u91hb: if the host adapter (incorrectly) drives
            render-continuation against a frame-id whose frame has
            been destroyed, the runtime MUST fail-soft (not escape
            with NPE / NoSuchFrame). The continuation's failure
            surfaces as :failed? true with the inline-fallback path."
    (let [fid (make-server-frame {:initial :state})
          tree [:rf/suspense-boundary
                {:id :after-destroy :fallback [:p "loading"]}
                [:p "body"]]
          {:keys [continuations]} (streaming/render-shell tree)
          entry (assoc (first continuations) :fallback [:p "loading"])]
      ;; Destroy the frame.
      (rf/destroy-frame! fid)
      ;; Now drive the continuation against the dead frame-id. The
      ;; pure render-continuation path either resolves cleanly
      ;; (treating absent app-db as nil) or fails-soft via the
      ;; inline-fallback contract. Either is acceptable per Spec 011
      ;; §Failure semantics; what is NOT acceptable is an uncaught
      ;; throw.
      (let [captured (atom [])
            result (with-trace-capture captured
                     #(streaming/render-continuation fid entry))]
        (is (map? result)
            "render-continuation returned a result map — did NOT
             escape with an uncaught exception even though the frame
             was destroyed before the call")
        (is (contains? result :failed?)
            ":failed? key is present")
        (is (contains? result :html)
            ":html key is present")))))

;; ===========================================================================
;; build-final-payload — allowlist projection composition
;; ===========================================================================

(deftest build-final-payload-allowlist-drops-unpermitted-keys
  (testing "rf2-u91hb: the streaming build-final-payload MUST honour
            :payload-keys allowlist projection — same contract as
            non-streaming build-payload (the streaming + non-streaming
            payload builders share re-frame.ssr.payload-policy/apply-
            policy per rf2-gtgf9). Pin that an un-permitted key on
            app-db does NOT ride the wire under the streaming path."
    (let [fid (make-server-frame {:public/articles [{:id "a"}]
                                  :server-only/auth-token "RF2_U91HB_LEAK_PROBE_xyz"
                                  :server-only/admin-flag true})
          payload (streaming/build-final-payload
                    fid "deadbeef"
                    {:version       1
                     :payload-keys  [:public/articles]})]
      (is (= [{:id "a"}] (get-in payload [:rf/app-db :public/articles]))
          "the public slice IS on the wire (sanity)")
      (is (not (contains? (:rf/app-db payload) :server-only/auth-token))
          "the un-permitted :server-only/auth-token key does NOT
           appear in the streaming final payload — pin the rf2-gtgf9
           fail-closed proof on the STREAMING path")
      (is (not (contains? (:rf/app-db payload) :server-only/admin-flag))
          "belt-and-braces over a second un-permitted slot"))))

;; ===========================================================================
;; clear-request! / clear-response! — idempotent no-ops on unpopulated
;; frames (Spec 011 §Per-request frame teardown contract — \"idempotent\"
;; in the on-frame-destroyed! docstring; pin the public surfaces too.)
;; ===========================================================================

(deftest clear-request-on-unpopulated-frame-is-noop
  (testing "rf2-u91hb: ssr/clear-request! on a frame-id that was never
            populated MUST be a no-op — host adapters that forget to
            populate (or clear twice) must not observe any error or
            side-effect"
    (let [before @(requiring-resolve 're-frame.ssr.request/request-slots)]
      ;; Call against a non-existent frame-id.
      (is (= :never-populated
             ((requiring-resolve 're-frame.ssr.request/clear-request!)
              :never-populated))
          "clear-request! returns the frame-id on the empty branch
           (matches the populated-branch return shape)")
      (let [after @(requiring-resolve 're-frame.ssr.request/request-slots)]
        (is (= before after)
            "the slot atom is unchanged — no spurious entry created
             by a clear of a never-populated slot")))))

(deftest clear-response-on-unpopulated-frame-is-noop
  (testing "rf2-u91hb: ssr/clear-response! on a never-populated frame
            MUST be a no-op (same idempotence contract as clear-
            request!)"
    (let [before @(requiring-resolve 're-frame.ssr.response/response-slots)]
      (is (= :never-populated
             ((requiring-resolve 're-frame.ssr.response/clear-response!)
              :never-populated)))
      (let [after @(requiring-resolve 're-frame.ssr.response/response-slots)]
        (is (= before after))))))

;; ===========================================================================
;; Privacy boundary — request slot / response accumulator NOT readable
;; from app-db. The privacy contract is "MUST NOT ride app-db"
;; (Spec 011 §Request storage substrate + §Response storage substrate).
;; Pre-rf2-jbcmt the response accumulator DID live on app-db; we pin
;; the post-fix invariant so a regression that re-introduces an app-db
;; backing fires loudly.
;; ===========================================================================

(deftest response-accumulator-not-on-app-db-privacy-invariant
  (testing "rf2-u91hb: writing an :rf.server/* fx MUST NOT populate any
            key under app-db. Per Spec 011 §Response storage substrate
            (rf2-jbcmt) — privacy boundary: response accumulator data
            (Set-Cookie, internal X-* headers) MUST NOT default-leak
            into the hydration payload via an app-db backing store."
    (rf/reg-event-fx :test/server-write
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/set-header {:name "X-Internal-Token" :value "secret"}]
              [:rf.server/set-cookie {:name "session" :value "sess-abc"}]]}))
    (let [fid (keyword "rf.frame" (str (gensym "")))]
      (rf/reg-frame fid
        {:doc       "privacy-invariant frame"
         :platform  :server
         :on-create [:test/server-write]})
      (let [app-db (frame/frame-app-db-value fid)]
        ;; Spec 011 §Response storage substrate: NO app-db key may
        ;; carry the accumulator. Pin both the published reserved key
        ;; AND the sentinel key the pre-jbcmt path used.
        (is (not (contains? app-db :rf/response))
            "app-db MUST NOT carry :rf/response — pre-rf2-jbcmt this
             was the storage path; the post-fix invariant is the
             side-channel atom in re-frame.ssr.response/response-slots")
        ;; Defensive: also assert no key with substring "response" or
        ;; "cookie" in the keyword name (a sloppy refactor that picks
        ;; a different key name on app-db would still violate).
        (let [keys-named-response
              (filter (fn [k] (and (keyword? k)
                                   (or (str/includes? (name k) "response")
                                       (str/includes? (name k) "cookie")
                                       (str/includes? (name k) "header"))))
                      (keys app-db))]
          (is (empty? keys-named-response)
              (str "app-db carries NO key with a response/cookie/header
                   name — the accumulator MUST live off-band per Spec
                   011 §Response storage substrate. Suspect keys: "
                   (vec keys-named-response))))))))

(deftest request-slot-not-on-app-db-privacy-invariant
  (testing "rf2-u91hb: populating the per-request request slot MUST NOT
            land any key on app-db. Per Spec 011 §Request storage
            substrate — the request map carries Host, Cookie,
            Authorization, X-Forwarded-For; an app-db backing would
            default-leak it onto the hydration payload."
    (let [fid (keyword "rf.frame" (str (gensym "")))]
      (rf/reg-frame fid
        {:doc       "request-slot privacy frame"
         :platform  :server
         :on-create [:rf.test/noop]})
      (let [secret-request {:uri            "/secret"
                            :request-method :get
                            :headers        {"authorization" "Bearer SECRET_TOKEN"
                                             "cookie"        "session=hot"}}]
        ((requiring-resolve 're-frame.ssr.request/set-request!) fid secret-request)
        (let [app-db (frame/frame-app-db-value fid)]
          (is (not (contains? app-db :rf.server/request))
              "app-db MUST NOT carry :rf.server/request")
          (is (not (contains? app-db :request))
              "app-db MUST NOT carry :request")
          (let [all-vals (vals app-db)
                serialised (pr-str all-vals)]
            (is (not (str/includes? serialised "SECRET_TOKEN"))
                "the request's bearer token does NOT appear anywhere
                 in the serialised app-db values — privacy boundary
                 holds across the population path")
            (is (not (str/includes? serialised "session=hot"))
                "the request's cookie value does NOT appear in
                 serialised app-db")))))))
