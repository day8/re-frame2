(ns re-frame.example-login-success-token-cljs-test
  "Framework-tree regression for the login feature's SUCCESS-TOKEN privacy — the
   `:auth.login/succeeded` reply event and the `:auth.session/store` persistence
   fx owned by examples/core/login/model.cljc, the substrate-free model shared
   across the Reagent/UIx login examples (rf2-ppbvav / rf2-j538f7.30).

   These belong in the framework test tree, NOT under examples/ (examples stay
   test-free per rf2-8cevm). The ns requires the login model owner
   (`login.model`) so its events / subs / machine / schemas register at ns-load,
   then drives the success continuation directly. Sibling of
   `re-frame.example-login-form-slice-cljs-test` (the PASSWORD-egress half); this
   ns pins the RETURN half — the session token the server hands back.

   THE SUCCESS TOKEN IS A CREDENTIAL, so — exactly like the password on the way
   in — it is classified at every TRANSIENT boundary it crosses on the way back
   (docs/core/how-to/keep-secrets-out-of-traces.md):

     1. THE REPLY EVENT. Managed HTTP appends its reply as the LAST positional
        arg of `:on-success`. Routed at a one-element `[:auth.login/succeeded]`,
        the reply lands in the addressable arg-map slot, and the registration's
        `:sensitive [[:value :token]]` redacts `[:value :token]` in the
        dispatched-event trace (and any error record carrying the event) while
        the handler still reads the real token. (Routing it at the two-element
        machine event would make the reply a THIRD positional arg — unaddressable
        — which is the leak this fix closes.)

     2. THE STORAGE FX. `:auth.session/store` declares `:sensitive [[:token]]`
        on its own registration, so the per-effect `:rf.fx/handled` trace redacts
        `[:token]`. fx args are a transient owner distinct from the event.

     3. THE MACHINE IS KEPT CREDENTIAL-FREE. The reply never reaches the machine
        — `:auth.login/succeeded` nudges it with a bare, credential-free
        `[:auth.login/flow [:auth.login/success]]` signal, so the flow reaches
        `:authed` without ever seeing the token.

   FRAMEWORK-GATED SLOTS — NOW CLOSED (rf2-6h3c02). Two trace slots used to carry
   the raw token, neither reachable by app-side classification — central
   classification-projector gaps where `project-trace-event` applied the fx
   registration's `:sensitive` only to the `:rf.fx/handled` slot. rf2-6h3c02
   taught the projector to apply the fx registration's `:sensitive` to EVERY
   fx-arg-bearing slot, so both now redact off the SAME `:auth.session/store`
   `:sensitive [[:token]]` this example already declares:

     - `:rf.event/fx` on `:rf.fx/do-fx` — the handler's WHOLE returned effect
       vector; each entry's args now walk through its fx registration (sibling of
       the `:rf.event/db` walk — Spec 009 §Canonical per-event trace sequence
       notes they share posture). Asserted clean by the whole-stream sweep below.
     - `:rf.fx/args` on the always-on (production-survivable)
       `:rf.error/fx-handler-exception` — fx-args redaction is now keyed on the
       slot shape, not op `:rf.fx/handled`. Asserted clean by the error-arm test.

   This ns asserts everything the example owns is redacted, pins the two
   registration declarations the projector consumes, and — post-rf2-6h3c02 — the
   two formerly-residual framework slots.

   THE STORY VARIANT'S OWN TOKEN FIXTURE (rf2-cckg / rf2-hz8u). Everything
   above drives `:auth.login/succeeded` with an EXPLICITLY token-bearing reply
   this file writes, which is exactly why it never covered the canonical Story
   variant: `:story.login/success` does not write a reply, it runs the REAL
   form path in a `:preset :story` frame and lets the server's half arrive
   through `:rf.http/managed`. That frame redirects the fx to the framework's
   GENERIC canned-success stub, whose `{:stubbed true}` payload has no
   `[:value :token]` and is therefore refused at `:auth.login/succeeded`'s
   schema boundary — leaving the canonical screenshot stuck in `:submitting`.
   PR #9386 fixed the SOURCE by giving the variant its own `:network` route
   fixture; the final section of this ns guards that fixture at TWO altitudes —
   the compiled plan, and a live `run-variant` drive of the registered variant
   that watches the whole cascade arrive."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is async]]
            [malli.core :as m]
            [re-frame.registrar :as rf.registrar]
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; login.model pulls these transitively; require here so the ns is
            ;; self-sufficient (mirrors the sibling login test namespaces).
            [re-frame.schemas]
            ;; The Malli adapter publishes the validator the `:where :event`
            ;; boundary routes through, and the `m/explain` the rf2-cckg guard
            ;; below runs against the registered `:auth.login/succeeded` schema
            ;; (mirrors re-frame.login-cljs-test).
            [re-frame.schemas.malli]
            [re-frame.machines]
            ;; rf2-cckg — the Story deck under test. `login.stories` sources
            ;; `login.core` (views) which sources `login.model`, so requiring it
            ;; registers the whole example; `re-frame.story.plan` compiles the
            ;; registered variant body to the plan the guard below reads (pure
            ;; data → data, no host). TEST-ONLY: this ns is a `*_cljs_test`
            ;; namespace on the consolidated `:node-test` classpath (which
            ;; already carries `../tools/story/src` and `../examples/core`), so
            ;; the `tools/` bundle-isolation contract — a PRODUCTION-build
            ;; contract, graded by check-bundle-isolation.cjs against compiled
            ;; release bundles — is untouched: no production build requires this
            ;; namespace.
            [re-frame.story.plan :as rf.story.plan]
            ;; rf2-cckg (the acceptance half) — the LIVE runner. `run-variant`
            ;; allocates the variant's own frame, realizes the compiled
            ;; `:network` fixture onto the managed-HTTP seam and drives the
            ;; four-phase lifecycle; `rf.story.async/then` is Story's
            ;; host-neutral promise combinator (a `js/Promise` on CLJS).
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [login.model]
            [login.stories :as login-stories])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; `:async? true` — the live-drive tests at the foot of this ns are `(async
;; done …)` rows, and `cljs.test` HARD-ERRORS on a fn-form fixture for one
;; ("Async tests require fixtures to be specified as maps. Testing aborted." —
;; and it aborts the WHOLE run, not just this ns). The flag hands back the
;; map-form fixture, whose ambient frame scope is a persistent `set!` rather
;; than a dynamic `binding`, so it survives the async body resuming on a later
;; tick. Everything the sync tests above rely on is unchanged.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :async?  true}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

;; A UNIQUE sentinel token — a string that appears nowhere else in the source or
;; the framework, so a recursive scan for it across the trace stream can only be
;; hitting THIS reply's token.
(def sentinel "SESSION-TOKEN-SENTINEL-8b71e0")

(def success-reply
  "The canonical managed-HTTP success envelope for the login request, carrying a
   uniquely-tagged session token — exactly what the demo stub conjures and what
   managed HTTP appends to `[:auth.login/succeeded]`."
  {:status :ok
   :value  {:user  {:id "u1" :email "alice@example.com"}
            :token sentinel}})

(defn- machine-state [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow :state]))

(defn- contains-sentinel?
  "True when `x` contains the sentinel token string ANYWHERE in a nested data
   structure — the recursive scan an off-box shipper / dev tool would apply."
  [x]
  (cond
    (string? x) (not= -1 (.indexOf x sentinel))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

(defn- record-traces! []
  (let [a (atom [])]
    (rf/register-listener! :trace ::probe (fn [ev] (swap! a conj ev)))
    a))

(defn- submit! [f]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit]] {:frame f}))

;; ---------------------------------------------------------------------------
;; (positive control) the real token IS usable end to end
;; ---------------------------------------------------------------------------

(deftest succeeded-persists-real-token-and-drives-machine-to-authed
  (testing "the success handler hands the REAL token to the persistence fx and
            drives the machine to :authed with a credential-free signal"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (submit! f)
      (is (= :submitting (machine-state f))
          "the submit signal put the flow in :submitting")
      (let [stored (atom nil)]
        ;; Capture the persistence fx's args (positive control) but let the
        ;; credential-free machine :dispatch run, so the flow reaches :authed.
        (rf/dispatch-sync [:auth.login/succeeded success-reply]
                          {:frame        f
                           :fx-overrides {:auth.session/store
                                          (fn [_ args] (reset! stored args))}})
        (is (= {:token sentinel} @stored)
            "the persistence fx received the REAL token — it is fully usable by
             the app logic (this is what gets written to localStorage)")
        (is (= :authed (machine-state f))
            "the bare, credential-free :success signal drove the flow to :authed
             — the machine reached the happy ending without seeing the token")))))

;; ---------------------------------------------------------------------------
;; the token is redacted across every EXAMPLE-OWNED trace slot
;; ---------------------------------------------------------------------------

(deftest success-token-redacted-across-trace-egress
  (testing "on a successful login, the session token does NOT appear raw in any
            reply-event or per-effect trace slot the example owns; the reply
            event redacts [:value :token] and the store fx redacts [:token]"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (submit! f)
      (let [traces (record-traces!)]
        (rf/dispatch-sync [:auth.login/succeeded success-reply] {:frame f})
        (rf/unregister-listener! :trace ::probe)

        (is (= :authed (machine-state f))
            "sanity: the drive reached :authed (so the full cascade was traced)")

        ;; --- the reply event trace redacts the token, shape retained ---
        (let [succeeded-vs (->> @traces
                                (keep #(get-in % [:tags :rf.event/v]))
                                (filter #(and (vector? %)
                                              (= :auth.login/succeeded (first %)))))]
          (is (seq succeeded-vs)
              "the :auth.login/succeeded event surfaced on the trace with its payload")
          (doseq [v succeeded-vs]
            (is (= rf.privacy/redacted-sentinel (get-in v [1 :value :token]))
                "the token reads :rf/redacted in the succeeded event trace")
            (is (= :ok (get-in v [1 :status]))
                "shape retained — the envelope :status is still visible")
            (is (contains? (get-in v [1 :value]) :user)
                "shape retained — the :user sibling is still present")))

        ;; --- the store fx's per-effect trace redacts its :token arg ---
        (let [handled (->> @traces
                           (filter #(= :auth.session/store (get-in % [:tags :rf.fx/id]))))]
          (is (seq handled)
              "the :auth.session/store fx emitted a :rf.fx/handled trace")
          (doseq [ev handled]
            (is (= rf.privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
                "the store fx :token arg reads :rf/redacted in :rf.fx/handled")))

        ;; --- the whole-stream sweep: the sentinel appears in NO trace tag.
        ;;     Post-rf2-6h3c02 this includes :rf.event/fx (the :rf.fx/do-fx
        ;;     aggregate) — the projector now walks each fx entry's args through
        ;;     its registration, so the store fx's [:token] redacts there too.
        ;;     (The error-arm :rf.fx/args gap — also closed by rf2-6h3c02 — only
        ;;     fires when the fx THROWS, exercised in the error-arm test below.)
        (let [checked (atom 0)]
          (doseq [ev @traces
                  [k v] (:tags ev)]
            (swap! checked inc)
            (is (not (contains-sentinel? v))
                (str "the session token must not appear raw in " (:operation ev)
                     " / " k)))
          (is (pos? @checked)
              "the sweep actually inspected trace tags (guard against a no-op)"))))))

;; ---------------------------------------------------------------------------
;; the error arm — the origin event on the fx-exception trace is redacted
;; ---------------------------------------------------------------------------

(deftest error-arm-origin-event-and-fx-args-redacted-on-fx-exception
  (testing "when the localStorage fx throws, BOTH the origin event (:event slot,
            example-owned) AND the fx args (:rf.fx/args slot, framework-gated
            until rf2-6h3c02) on the always-on :rf.error/fx-handler-exception
            trace redact the token"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (submit! f)
      (let [traces (record-traces!)]
        (rf/dispatch-sync
          [:auth.login/succeeded success-reply]
          {:frame        f
           :fx-overrides {:auth.session/store
                          (fn [_ _] (throw (js/Error. "localStorage unavailable")))}})
        (rf/unregister-listener! :trace ::probe)
        (let [errs (->> @traces
                        (filter #(= :rf.error/fx-handler-exception (:operation %))))]
          (is (seq errs)
              "the throwing store fx emitted an :rf.error/fx-handler-exception trace")
          (doseq [ev errs]
            (is (not (contains-sentinel? (get-in ev [:tags :event])))
                "the origin event on the error trace redacts the token — the
                 :auth.login/succeeded registration classifies it")
            ;; rf2-6h3c02: the fx args on the production-survivable error trace
            ;; now redact off :auth.session/store's own :sensitive [[:token]].
            (is (= rf.privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
                "the store fx :token arg reads :rf/redacted in :rf.fx/args")
            (is (not (contains-sentinel? (:tags ev)))
                "the token appears nowhere raw across the error trace tags")))))))

;; ---------------------------------------------------------------------------
;; the two registrations own their classification (what the projector consumes)
;; ---------------------------------------------------------------------------

(deftest owners-declare-their-classification
  (testing "the reply event and the persistence fx each declare their own
            :sensitive path — the registration-owned classification the trace
            projector reads at egress"
    (is (= {:sensitive [[:value :token]]}
           (rf.classification/registration-classification :event :auth.login/succeeded))
        ":auth.login/succeeded owns [:value :token] — the reply's token slot")
    (is (= {:sensitive [[:token]]}
           (rf.classification/registration-classification :fx :auth.session/store))
        ":auth.session/store owns [:token] — its own transient fx arg")))

;; ---------------------------------------------------------------------------
;; rf2-cckg - the canonical Story variant's own token fixture
;; ---------------------------------------------------------------------------
;;
;; Everything above supplies the reply itself. `:story.login/success` supplies
;; NOTHING: it runs the real form path in a `:preset :story` frame and lets the
;; server's half arrive through `:rf.http/managed`. That frame redirects the fx
;; to the framework's GENERIC canned-success stub, whose `{:stubbed true}`
;; payload carries no `[:value :token]` and is refused at
;; `:auth.login/succeeded`'s schema boundary - leaving the canonical screenshot
;; stuck in `:submitting`. PR #9386's fix gave the variant its own `:network`
;; route fixture, and this section guards it.
;;
;; TWO ALTITUDES, AND BOTH ARE LOAD-BEARING.
;;
;; (1) THE PLAN GUARD. The registered variant's COMPILED PLAN must carry the
;;     token-bearing route for the POST the form really makes, that reply must
;;     satisfy the REGISTERED `:auth.login/succeeded` schema, and the plan must
;;     lower onto the managed-HTTP seam. This altitude is pure data -> data: no
;;     frame, no host, no promise. It names WHICH slot is wrong when the drive
;;     below goes red, which a live drive alone cannot.
;;
;; (2) THE LIVE DRIVE - the acceptance rf2-cckg / rf2-hz8u actually asked for.
;;     Allocate the variant's own Story frame, run the real four-phase
;;     lifecycle, and assert the cascade ARRIVES: the machine reaches `:authed`,
;;     the `:auth/authenticated` tag the Welcome banner branches on reads true,
;;     the session token reaches `:auth.session/store`, and the default
;;     development validator refuses nothing on the way. The plan guard cannot
;;     witness any of that - a plan is a description, and a description of a
;;     working fixture is exactly what a BROKEN runtime also produces.
;;
;; That second altitude was unreachable when this section was first written:
;; `lower-network` is pure, so it emitted the `{:rf.http/managed
;; :rf.http/managed-test-stub}` redirect while registering nothing, and the only
;; caller of `re-frame.http.test-support/install-managed-request-stubs!` in
;; Story was artifact REPLAY. A live `run-variant` therefore reached the real
;; transport and reproduced the PRE-FIX symptom exactly. PR #9398 (rf2-shx4)
;; added `re-frame.story.network`, which the runtime calls on BOTH live paths
;; (registered and inline) and releases at frame teardown - so the drive below
;; runs.
;;
;; Delete the `:network` slot from examples/core/login/stories.cljs and BOTH
;; altitudes go red: the plan guard on the missing route, the drive on a machine
;; still sitting in `:submitting` with the token nowhere and one
;; `:rf.error/schema-validation-failure :where :event` naming `[1 :value :token]`
;; as a missing key. That is the regression rf2-cckg exists to hold.

(def ^:private story-fixture-token
  "The token `:story.login/success`'s `:network` route fixture hands back - the
   same string the live demo backend (`:auth.login.demo/managed-stub`) conjures,
   so the Story frame and the live-app frame agree on the server's half."
  "demo-token-123")

(def ^:private login-route
  "The route `submit-form` really posts, and the key the variant files its
   fixture under."
  [:post "/api/login"])

(deftest story-success-variant-carries-its-own-token-bearing-fixture
  (testing "the registered :story.login/success variant compiles to a plan whose
            :network route answers the real login POST with a token-bearing
            reply - the shape :auth.login/succeeded's schema requires, and the
            thing the generic preset stub cannot supply"
    ;; Re-fire the EXAMPLE's deck so the body under test is the example's own
    ;; (`register-all!` is idempotent; the seed regression in the Story test
    ;; tree re-asserts the same way).
    (login-stories/register-all!)
    (let [world (:world (rf.story.plan/variant-plan :story.login/success))
          reply (get-in world [:network login-route :reply :ok])]
      (is (some? (get-in world [:network login-route]))
          (str "the variant declares a :network route for " (pr-str login-route)
               "; got routes " (pr-str (keys (:network world)))))
      (is (= story-fixture-token (:token reply))
          "the reply carries the demo token - without it the generic canned
           payload is refused at the schema boundary and the canonical
           screenshot never leaves :submitting")
      (is (some? (:user reply))
          "and the :user sibling the Welcome banner reads")
      ;; Asserted against the REGISTERED schema rather than a copy of it, so a
      ;; tightening of :auth.login/succeeded reds this test rather than
      ;; silently outrunning it.
      (is (nil? (m/explain (:schema (rf.registrar/lookup :event :auth.login/succeeded))
                           [:auth.login/succeeded {:status :ok :value reply}]))
          ":auth.login/succeeded ACCEPTS the fixture's reply - the boundary that
           refuses the generic {:stubbed true} payload"))))

(deftest story-success-fixture-lowers-onto-the-managed-http-seam
  (testing "the compiled plan lowers :network onto :rf.http/managed - the
            variant does not invent an HTTP mock, it redirects the real fx, so
            the cascade it describes stays the real one end to end"
    (login-stories/register-all!)
    (let [plan (rf.story.plan/variant-plan :story.login/success)]
      (is (= :rf.http/managed-test-stub
             (get-in plan [:world :frame :fx-overrides :rf.http/managed]))
          ":network lowered to the managed-request stub override
           (re-frame.story.plan/lower-network)"))))

;; ---------------------------------------------------------------------------
;; rf2-cckg / rf2-hz8u - THE LIVE DRIVE (the acceptance)
;; ---------------------------------------------------------------------------
;;
;; Everything above reads the compiled plan. This part RUNS it: `run-variant`
;; allocates the variant's own frame, installs the `:network` route map on the
;; managed-HTTP seam (`re-frame.story.network/install-for-frame!`, on both live
;; paths since rf2-shx4), then drives the four-phase lifecycle - so what is
;; asserted below is the real cascade the Story canvas performs, not a
;; description of one:
;;
;;     [:login.story/submit good-creds]
;;       -> [:auth.login/edit-field :email ...] + [:auth.login/edit-password ...]
;;       -> [:auth.login/submit-form]          (validates the draft)
;;            -> [:auth.login/flow [:auth.login/submit]]      (-> :submitting)
;;            -> [:rf.http/managed ...]        (redirected to the route fixture)
;;                 -> [:auth.login/succeeded {:status :ok :value {... :token}}]
;;                      -> [:auth.session/store {:token ...}]
;;                      -> [:auth.login/flow [:auth.login/success]]  (-> :authed)
;;
;; STORAGE IS STUBBED, NOT REPLACED. `:auth.session/store`'s real body writes
;; `js/globalThis.localStorage`, and that write is the one thing the acceptance
;; wants to watch, so the JS global is swapped for a capture-only stand-in and
;; put back. Re-registering the fx from THIS namespace would be the wrong tool
;; twice over: it would skip the very handler body under test, and a second
;; provenance namespace registering `[:fx :auth.session/store]` collides in the
;; source store, which the default image projection refuses outright
;; (`:rf.error/image-duplicate-id`).

(def ^:private stored-token-key
  "The localStorage key `:auth.session/store` writes under."
  "auth/token")

(defn- install-stub-storage!
  "Swap `js/globalThis.localStorage` for a capture-only stand-in for the extent
   of one drive. Returns `[captured restore!]` - `captured` is an atom of
   `{key -> value}` recording every `setItem`, and `restore!` puts the prior
   global back. Node ships no Web Storage, so the real fx is a silent no-op
   here and the drive would otherwise have nothing to observe."
  []
  (let [captured (atom {})
        prior    (.-localStorage js/globalThis)]
    (set! (.-localStorage js/globalThis)
          #js {:setItem    (fn [k v] (swap! captured assoc k v) nil)
               :getItem    (fn [k] (get @captured k))
               :removeItem (fn [k] (swap! captured dissoc k) nil)})
    [captured (fn [] (set! (.-localStorage js/globalThis) prior))]))

(defn- event-schema-refusals
  "Every `:rf.error/schema-validation-failure` trace raised at the EVENT
   boundary - the refusal the pre-fix generic `{:stubbed true}` payload earns
   at `:auth.login/succeeded`. Empty is the assertion; a non-empty vector is
   the pre-fix symptom."
  [traces]
  (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                 (= :event (get-in % [:tags :where])))
           traces))

(defn- authenticated?
  "The exact question `login.core/login-banner` asks before it renders the
   'Welcome!' span, computed off the variant frame's own state - so this is the
   view's own branch condition rather than a restatement of it."
  [frame-id]
  (rf/compute-sub [:rf.machine/has-tag? :auth.login/flow :auth/authenticated]
                  (rf/frame-state-value frame-id)))

(defn- drive-variant!
  "Register the example's deck, run `variant-id` to settlement, and call `k`
   with the unified result map, the captured storage map and the captured trace
   vector. `k` runs with the trace probe already unregistered and the storage
   global already restored. The variant frame is torn down afterwards, so the
   next drive allocates a fresh one and `re-frame.story.network` releases the
   route map this frame owned."
  [variant-id k]
  (login-stories/register-all!)
  (let [[captured restore!] (install-stub-storage!)
        traces              (record-traces!)]
    (-> (rf.story/run-variant variant-id)
        (rf.story.async/then
          (fn [result]
            (rf/unregister-listener! :trace ::probe)
            (restore!)
            (try
              (k result @captured @traces)
              (finally
                (rf.story/destroy-variant! variant-id))))))))

(deftest story-success-variant-drives-to-authed-with-the-token-stored
  (testing "running the REGISTERED :story.login/success variant end to end
            reaches the machine's authenticated state and the Welcome banner's
            own branch condition, with the fixture's token reaching
            :auth.session/store and the development validator refusing nothing"
    (async done
      (drive-variant! :story.login/success
        (fn [result stored traces]
          (is (= :ready (:lifecycle result))
              "the four-phase lifecycle completed cleanly")
          (is (empty? (event-schema-refusals traces))
              (str "no event-schema refusal on the way - the pre-fix generic "
                   "{:stubbed true} payload is refused at "
                   ":auth.login/succeeded's [:value :token]; got "
                   (pr-str (mapv :tags (event-schema-refusals traces)))))
          (is (= :authed (machine-state :story.login/success))
              ":auth.login/flow reached :authed - the canonical screenshot's
               state, not the :submitting a missing fixture leaves it in")
          (is (true? (authenticated? :story.login/success))
              "the :auth/authenticated tag reads true, which is exactly what
               login.core/login-banner branches on to render 'Welcome!'")
          (is (= {stored-token-key story-fixture-token} stored)
              "the fixture's session token travelled the whole cascade and
               reached :auth.session/store, which persisted it under the
               auth/token key")
          (done))))))

;; ---- the two controls -----------------------------------------------------
;;
;; These are what make the drive above a MEASUREMENT rather than a coincidence.
;; Both run the same runner over the same deck in the same lane; neither carries
;; a `:network` fixture, and neither reaches `:authed` or stores a token. So a
;; runner that authenticated everything - or a stub-storage helper that captured
;; something ambient - shows up HERE as a red, and the success drive's green
;; cannot be explained by either.

(deftest story-submitting-variant-stays-in-flight-with-no-token-stored
  (testing ":story.login/submitting force-stubs :rf.http/managed and answers
            NOTHING, so the flow freezes mid-request: no reply, no token, and
            the machine is still :submitting when the run settles"
    (async done
      (drive-variant! :story.login/submitting
        (fn [_result stored _traces]
          (is (= :submitting (machine-state :story.login/submitting))
              "the request is in flight and no reply ever came")
          (is (false? (authenticated? :story.login/submitting))
              "the Welcome banner's condition is false - the form is on screen")
          (is (= {} stored)
              ":auth.session/store never ran, so nothing was persisted")
          (done))))))

(deftest story-invalid-credentials-variant-never-submits
  (testing ":story.login/invalid-credentials is turned away by submit-form's
            own pre-submit Credentials validation, so the machine never leaves
            :idle and no request is ever issued"
    (async done
      (drive-variant! :story.login/invalid-credentials
        (fn [result stored _traces]
          (is (= :idle (machine-state :story.login/invalid-credentials))
              "nothing was dispatched at the machine - the draft never passed
               the pre-submit check")
          (is (false? (authenticated? :story.login/invalid-credentials))
              "the Welcome banner's condition is false")
          (is (seq (get-in result [:app-db :auth :login-form :errors]))
              "the field errors surfaced in the slice, under each input")
          (is (= {} stored)
              "no request, no reply, no token")
          (done))))))
