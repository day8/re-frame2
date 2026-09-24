(ns re-frame.ssr-hydration-mismatch-test
  "The deliberate-mismatch path of SSR hydration: bake a known-wrong `:rf/render-hash` (`\"deadbeef\"`)
  into the payload, hydrate, call `verify-hydration!` post-render,
  observe the captured `:rf.ssr/hydration-mismatch` trace's tag
  payload (`:server-hash`, `:client-hash`, `:failing-id`,
  `:recovery`), confirm the page stays interactive post-mismatch.

  Every load-bearing assertion is platform-neutral — the trace
  emission lives in `re-frame.ssr.hydrate/verify-hydration!`, which
  is `.cljc` — so the path is pinned on the JVM. The testbed at
  `testbeds/ssr_hydration_mismatch/` walks the same path in a browser;
  its DOM mirror (mismatch-banner) is observation-only, and substrate
  mount is covered by the adapter smokes.

  ## Posture split

  `verify-hydration!` has THREE output channels for one detection, and TWO
  of them survive production.

  The `:rf.ssr/hydration-mismatch` TRACE goes through the
  `:trace/emit-error!` late-bind hook, whose emit site is gated on
  `interop/debug-enabled?` — read once at namespace-load time, so under
  `-Dre-frame.debug=false` nothing is emitted. The strict-mode THROW is
  always-on: `hydrate.cljc` builds ONE shared payload and uses it for both,
  so `:server-hash`, `:client-hash`, `:failing-id`, `:recovery`, `:reason`
  and `:where` are observable in production through `ex-data` even though
  the trace carrying the identical map is not.

  The THIRD channel is the always-on union RECORD, fanned through
  `:error-emit/dispatch-error-record` beside the trace. It is what makes
  the detection observable in production under the DEFAULT `:warn` policy,
  where no throw happens and the trace is DCE'd — without it that build
  would do the hash comparison and report the answer to nobody. It carries
  STRUCTURAL slots only (`:frame`, `:server-hash`, `:client-hash`,
  `:failing-id`, `:recovery`), NOT the shared payload: `:reason` and
  `:first-diff-path` stay on the trace and the throw, both of which are
  local, because this record reaches off-box shippers raw.

  That is why `mismatch-strict-mode-throws-with-structured-payload` is
  green under the gate, and it is the production witness the trace
  assertions lean on. Each trace assertion here sits inside a
  `(when interop/debug-enabled? …)` arm marked as the dev-instrumentation
  arm.

  A deftest whose subject the trace alone observes would be left with
  nothing to prove under the gate, so each such deftest also carries a
  production witness rather than being guarded away:

    - `mismatch-detection-defaults-on-when-knob-absent` and
      `mismatch-detection-disabled-skips-comparison` are about the
      `:detect-mismatch?` knob, which the trace observes — including
      through a `(is (empty? mismatches))` that is satisfied
      automatically under the gate. Each also drives the SAME knob through
      a frame that also sets `:on-mismatch :hard-error`, where detection-on
      throws and detection-off does not. The knob's effect is then visible
      on the always-on channel, in either posture.
    - `mismatch-trace-client-hash-is-8-char-lowercase-hex` pins the shape
      of `render-tree-hash`'s output. The shape claim is about the hash
      function, so it is asserted directly against
      `rf.ssr/render-tree-hash`, which is not gated at all, as well as read
      back off a trace tag.
    - `mismatch-trace-is-an-error-op-type-event` asserts an
      ERROR-severity classification; its always-on counterpart is that the
      same condition carries `:rf.error/id :rf.ssr/hydration-mismatch` — an
      error id — in the strict-mode throw's `ex-data`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; The payload the testbed's `<script id=\"__rf_payload\">` bakes
;; (testbeds/ssr_hydration_mismatch/index.html lines 49-54), minus its
;; `:rf/frame-id`. The
;; "deadbeef" string is the known-wrong server-hash that will not
;; equal whatever the client tree's actual FNV-1a hash resolves to.
;; NO `:rf/frame-id` key — these tests dispatch the payload into a
;; freshly made anonymous `client-frame` (NOT `:rf/default`), and the
;; `:rf/hydrate` handler fails CLOSED on a present-and-different
;; `:rf/frame-id`. The testbed's `:rf/frame-id :rf/default`
;; would (correctly) be rejected as a frame-id mismatch against the synthetic
;; frame, short-circuiting the hash-mismatch path these tests exercise. An
;; absent frame-id is the documented no-conflict shape — the dispatch target
;; stands — which is what these render-hash-mismatch tests intend.
(def ^:private mismatch-payload
  {:rf/version     1
   :rf/render-hash "deadbeef"
   :rf/app-db      {:count 0}})

(defn- register-handlers! []
  (rf/reg-event ::inc
    (fn [{:keys [db]} _ev] {:db (update db :count (fnil inc 0))}))
  (rf/reg-sub :count     (fn [db _] (or (:count db) 0)))
  ;; EP-0001: the SSR hydration metadata is durable runtime-db state.
  (rf.subs/reg-runtime-sub :hydrated? (fn [rt _] (boolean (get-in rt [:rf.runtime/ssr :hydration])))))

(def ^:private hex-8-pattern #"^[0-9a-f]{8}$")

;; ===========================================================================
;; Hydration completes (metadata lands) even with the
;; deliberately-wrong server-hash
;; ===========================================================================

(deftest mismatch-hydrate-still-stashes-metadata-when-server-hash-set
  (testing ":rf/hydrate is independent of
            verify-hydration!: the handler always replaces app-db
            and stashes the metadata, regardless of whether the
            payload's hash matches the client's eventual render.
            The mismatch is a downstream trace, not a hydration
            blocker (Spec 011 — degraded-but-running posture)."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-mismatch client frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      (is (true? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
          "post-hydrate :hydrated? reads true even though the baked
           hash will mismatch the (future) client render")
      (is (= "deadbeef"
             (get-in (:rf.db/runtime (rf/frame-state-value client-frame))
                     [:rf.runtime/ssr :hydration :server-hash]))
          "the deliberately-wrong :rf/render-hash is stashed verbatim
           for verify-hydration! to pick up"))))

;; ===========================================================================
;; The mismatch trace's tag payload
;; ===========================================================================

(deftest mismatch-trace-carries-server-hash-failing-id-recovery
  (testing "server-hash, failing-id and recovery. Per Spec 011
            §Hydration-mismatch detection
            the trace's `:tags` carry the structured shape: server-
            hash + client-hash + failing-id; the `:recovery` slot is
            hoisted to the trace envelope's top level (per Spec 009
            §Error event shape's recovery-hoist branch)."
    (register-handlers!)
    (let [client-frame   (rf.frame/make-anon-frame-record! {:doc "ssr-mismatch client frame"
                                         :platform :client})
          ;; A second 8-hex string — anything other than \"deadbeef\".
          client-hash    "0badf00d"]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      ;; Dev-instrumentation arm (see ns docstring). The
      ;; identical payload map is observable in production through the
      ;; strict-mode throw's ex-data, pinned by
      ;; `mismatch-strict-mode-throws-with-structured-payload`.
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (rf.ssr/verify-hydration! client-frame client-hash)
          (let [mismatches (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                                   @traces)]
            (is (= 1 (count mismatches))
                (str "expected exactly one :rf.ssr/hydration-mismatch trace; saw: "
                     (pr-str (mapv :operation @traces))))
            (when (seq mismatches)
              (let [ev (first mismatches)]
                (is (= "deadbeef" (-> ev :tags :server-hash))
                    ":tags :server-hash echoes the payload's known-wrong literal")
                (is (= client-hash (-> ev :tags :client-hash))
                    ":tags :client-hash echoes the value we passed to
                     verify-hydration!")
                (is (= :rf/hydrate (-> ev :tags :failing-id))
                    ":tags :failing-id discriminator per Spec 011 v1
                     (body-mismatch; runtime head-mismatch reserved for the
                     deferred post-v1 head-only-hash extension —
                     reg-head itself exists)")
                (is (= :warned-and-replaced (:recovery ev))
                    ":recovery hoisted onto the envelope top-level
                     (Spec 009 §Error event shape)")))))))))

(deftest mismatch-trace-client-hash-is-8-char-lowercase-hex
  (testing "the client-hash is 8-char lowercase hex and never
            equals the server-hash. The trace's :client-hash
            tag echoes whatever verify-hydration! was given — for a
            real client tree we'd pass `(render-tree-hash tree)`
            which always emits 8-char lowercase hex per Spec 011
            §Hydration-mismatch detection. Here we hash a concrete
            input and lock the shape + the not-equal-deadbeef
            invariant."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-mismatch client frame"
                                       :platform :client})
          ;; A non-trivial hiccup tree — its computed hash is whatever
          ;; FNV-1a resolves to; we lock the shape and the not-equal
          ;; invariant, not the literal.
          render-tree  [:div {:data-testid "counter-panel"}
                        [:p "count=" [:span {:data-testid "count"} 0]]]
          client-hash  (rf.ssr/render-tree-hash render-tree)]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      ;; SEMANTIC, posture-independent: the SHAPE claim is about
      ;; `render-tree-hash`, which is not gated at all, so both halves (the
      ;; shape and not-equal-deadbeef) are stated here against the hash
      ;; function itself.
      (is (and (string? client-hash) (= 8 (count client-hash)))
          (str "render-tree-hash emits exactly 8 chars; got "
               (pr-str client-hash)))
      (is (re-matches hex-8-pattern client-hash)
          (str "render-tree-hash emits 8-char lowercase hex; got "
               (pr-str client-hash)))
      (is (not= "deadbeef" client-hash)
          "the computed hash never equals the (deliberately wrong)
           server-hash — that would be a hash-collision spec violation")

      ;; Dev-instrumentation arm (see ns docstring). What is
      ;; genuinely ABOUT the trace: that the tag echoes
      ;; the same value `render-tree-hash` computes directly.
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (rf.ssr/verify-hydration! client-frame render-tree)
          (let [mismatch (first (filter #(= :rf.ssr/hydration-mismatch
                                            (:operation %))
                                        @traces))]
            (is (some? mismatch)
                ":rf.ssr/hydration-mismatch fires when the resolved tree
                 hashes to anything other than 'deadbeef'")
            (when mismatch
              (let [observed (-> mismatch :tags :client-hash)]
                (is (and (string? observed) (= 8 (count observed)))
                    (str "computed client-hash is exactly 8 chars; got "
                         (pr-str observed)))
                (is (re-matches hex-8-pattern observed)
                    (str "computed client-hash is 8-char lowercase hex; got "
                         (pr-str observed)))
                (is (= client-hash observed)
                    "the trace echoes the same hash render-tree-hash
                     computes when called directly on the input")
                (is (not= "deadbeef" observed)
                    "client-hash never equals the (deliberately wrong)
                     server-hash — that would be a hash-collision spec
                     violation")))))))))

;; ===========================================================================
;; The trace's :op-type is :error
;; ===========================================================================

(deftest mismatch-trace-is-an-error-op-type-event
  (testing "Per Spec 009 §Error event shape +
            Spec 011 §Hydration-mismatch detection the mismatch
            event is a structured :error (the trace bus's
            error-emit path is the producer site — see
            `re-frame.ssr.hydrate/verify-hydration!`)."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-mismatch client frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      ;; SEMANTIC, posture-independent: the ERROR-severity
      ;; classification has an always-on counterpart — the same condition,
      ;; escalated, carries an `:rf.error/id` (not a `:rf.warning/…` id) in
      ;; the strict-mode throw's ex-data. Spec 009 categorisation is
      ;; therefore witnessed in both postures, not only on the trace bus.
      (let [strict-frame (rf.frame/make-anon-frame-record!
                           {:doc      "ssr-mismatch severity frame"
                            :platform :client
                            :ssr      {:on-mismatch :hard-error}})]
        (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame strict-frame})
        (let [thrown (try (rf.ssr/verify-hydration! strict-frame "0badf00d")
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown) "the mismatch escalates when strict")
          (is (= :rf.ssr/hydration-mismatch (:rf.error/id (ex-data thrown)))
              "the condition is catalogued under :rf.error/… — an ERROR
               severity, matching the trace's :op-type :error")))

      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (rf.ssr/verify-hydration! client-frame "0badf00d")
          (let [mismatch (first (filter #(= :rf.ssr/hydration-mismatch
                                            (:operation %))
                                        @traces))]
            (is (some? mismatch))
            (when mismatch
              (is (= :error (:op-type mismatch))
                  ":op-type is :error — Spec 009 categorisation"))))))))

;; ===========================================================================
;; The page is still interactive post-mismatch
;; ===========================================================================

(deftest mismatch-page-stays-interactive-post-mismatch
  (testing "Per Spec 011 §Mismatch recovery and
            configuration the default recovery is :warned-and-
            replaced — the client renders against the seeded state
            and the dispatch pipeline stays live. The equivalent of a
            browser click-and-readback: drive ::inc through
            `dispatch-sync` and read :count via `subscribe-once`."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-mismatch client frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      (is (= 0 (rf/subscribe-once [:count] {:frame client-frame}))
          "seeded :count post-hydrate matches the payload's :rf/app-db
           (= 0 on this surface — the payload didn't seed a higher
           value)")

      ;; Fire the mismatch trace (otherwise this test would pass
      ;; vacuously — we want to assert the dispatch survives
      ;; the recovery, not just that it works without one).
      (with-trace-recorder! [_traces]
        (rf.ssr/verify-hydration! client-frame "0badf00d"))

      (rf/dispatch-sync [::inc] {:frame client-frame})
      (is (= 1 (rf/subscribe-once [:count] {:frame client-frame}))
          "post-mismatch ::inc dispatches through the live event-
           handler → db-update → sub-recompute pipeline; the
           warn-and-replace recovery is degraded-but-running, not
           crash"))))

;; ===========================================================================
;; Frame `:ssr` hydration-mismatch config knobs
;; (:on-mismatch :hard-error strict mode + :detect-mismatch? false)
;; ===========================================================================

(deftest mismatch-strict-mode-throws-with-structured-payload
  (testing "a frame with :ssr {:on-mismatch :hard-error}
            escalates a detected mismatch to a thrown structured
            exception (Spec 011 §Mismatch recovery and configuration
            item 2). The thrown ex-info carries the same server/client
            hash + failing-id payload as the trace, and :recovery is
            :hard-error."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr strict-mode frame"
                                       :platform :client
                                       :ssr {:on-mismatch :hard-error}})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      (let [thrown (try (rf.ssr/verify-hydration! client-frame "0badf00d")
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown)
            "strict mode throws on a detected mismatch")
        (let [data (ex-data thrown)
              msg  (ex-message thrown)]
          (is (= :rf.ssr/hydration-mismatch (:rf.error/id data))
              "the thrown exception is the structured hydration-mismatch")
          (is (= "deadbeef" (:server-hash data)))
          (is (= "0badf00d" (:client-hash data)))
          (is (= :rf/hydrate (:failing-id data)))
          (is (= :hard-error (:recovery data))
              ":recovery reflects the strict-mode escalation")
          ;; The throw routes through error/ex-info-from-data, so
          ;; the message LEADS with the human :reason sentence and TRAILS with
          ;; the [:rf.ssr/hydration-mismatch] greppability token (rule 4), and
          ;; the ex-data carries :where like the frame/events sibling throws.
          (is (rf.error/message-has-id-token? msg)
              "the message carries the trailing greppability token (rule 4)")
          (is (not (rf.error/keyword-only-message? msg))
              "the message is the human sentence, not a bare keyword (rule 1)")
          (is (= (rf.error/human-message :rf.ssr/hydration-mismatch (:reason data)) msg)
              "the message is derived from the payload's own :rf.error/id + :reason")
          (is (= 'rf/verify-hydration! (:where data))
              ":where names the throwing helper"))))))

(deftest mismatch-strict-mode-still-emits-trace-before-throwing
  (testing "strict mode emits the :rf.ssr/hydration-mismatch
            trace (monitoring integrations rely on it) AND throws — the
            two are not mutually exclusive."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr strict-mode frame"
                                       :platform :client
                                       :ssr {:on-mismatch :hard-error}})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      ;; Dev-instrumentation arm (see ns docstring). This
      ;; deftest's subject is the TRACE half of the emit-then-throw pair; the
      ;; throw half is `mismatch-strict-mode-throws-with-structured-payload`,
      ;; which runs in both postures.
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (try (rf.ssr/verify-hydration! client-frame "0badf00d")
               (catch clojure.lang.ExceptionInfo _ nil))
          (let [mismatch (first (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                                        @traces))]
            (is (some? mismatch)
                "the mismatch trace fires even in strict mode")
            (is (= :hard-error (:recovery mismatch))
                "the trace's :recovery reflects strict mode")))))))

(deftest mismatch-detection-disabled-skips-comparison
  (testing "a frame with :ssr {:detect-mismatch? false}
            short-circuits the hash comparison entirely (Spec 011 item 4):
            no trace, no throw, even when the hashes diverge."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr detection-off frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? false}})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})
      (is (nil? (rf.ssr/verify-hydration! client-frame "0badf00d"))
          "verify-hydration! is a no-op when detection is off")

      ;; SEMANTIC, posture-independent: a no-op return value is
      ;; also what the DETECTING path returns, so it cannot on its own tell
      ;; the short-circuit from a completed comparison. Drive the same knob
      ;; on a frame that ALSO asks for `:on-mismatch :hard-error`: with
      ;; detection off there is nothing to escalate, so it must not throw.
      ;; That reaches the always-on channel and holds in either posture.
      (let [off-strict (rf.frame/make-anon-frame-record!
                         {:doc      "ssr detection-off strict frame"
                          :platform :client
                          :ssr      {:detect-mismatch? false
                                     :on-mismatch      :hard-error}})]
        (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame off-strict})
        (is (nil? (rf.ssr/verify-hydration! off-strict "0badf00d"))
            ":detect-mismatch? false short-circuits BEFORE the comparison —
             even :on-mismatch :hard-error has nothing to escalate"))

      ;; Dev-instrumentation arm (see ns docstring). A NEGATIVE
      ;; over the trace ring: vacuous under the gate, where no mismatch
      ;; trace fires whether detection ran or not.
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (rf.ssr/verify-hydration! client-frame "0badf00d")
          (let [mismatches (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                                   @traces)]
            (is (empty? mismatches)
                "no mismatch trace fires when :detect-mismatch? is false")))))))

(deftest mismatch-detection-defaults-on-when-knob-absent
  (testing "absence of the :detect-mismatch? knob (the
            common case) leaves detection ON; a divergent hash still
            warns. Pins the default so a future refactor can't silently
            flip detection off."
    (register-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr default frame"
                                       :platform :client})]
      (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame client-frame})

      ;; SEMANTIC, posture-independent: the default this deftest
      ;; exists to pin is `:detect-mismatch?` ABSENT ⇒ detection ON, and the
      ;; trace cannot show it under the gate. Drive the same absent knob on
      ;; a frame that asks for `:on-mismatch :hard-error`: if detection
      ;; silently defaulted off there would be nothing to escalate and no
      ;; throw. The throw is always-on, so the default is pinned in both
      ;; postures — which is precisely the refactor this deftest guards
      ;; against.
      (let [default-strict (rf.frame/make-anon-frame-record!
                             {:doc      "ssr default-detection strict frame"
                              :platform :client
                              :ssr      {:on-mismatch :hard-error}})]
        (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame default-strict})
        (let [thrown (try (rf.ssr/verify-hydration! default-strict "0badf00d")
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "with :detect-mismatch? absent the comparison still ran —
               detection defaults ON")
          (is (= "0badf00d" (:client-hash (ex-data thrown)))
              "…and it compared the hashes it was given, rather than
               short-circuiting")))

      ;; Dev-instrumentation arm (see ns docstring). The default
      ;; RECOVERY (`:warned-and-replaced` rather than `:hard-error`) is only
      ;; observable on the trace: the warn path throws nothing by definition.
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (rf.ssr/verify-hydration! client-frame "0badf00d")
          (let [mismatch (first (filter #(= :rf.ssr/hydration-mismatch (:operation %))
                                        @traces))]
            (is (some? mismatch) "detection defaults on")
            (is (= :warned-and-replaced (:recovery mismatch))
                "the default recovery is warn-and-replace (not hard-error)")))))))
