(ns re-frame.story.ui.shell-error-ownership-cljs-test
  "rf2-8yyd / rf2-kuky.18 — a mounted Story shell OWNS the refusals its own
  frames produce, so `re-frame.error-emit`'s untooled-dev console fallback
  stays quiet while Story runs its deliberately-failing variants.

  The fallback (rf2-fu75) prints a promoted `:rf.error/*` record to
  `console.error` when NOTHING ROUTED IT. Story's testbeds run failing
  scenarios on purpose (`failing-event-throws`, `failing-play`,
  `failing-fx-stub-miss`, `deliberately-failing`), so every one of those
  printed a console line; 227 of them redded the Story feature-load browser
  gate, which treats a console error as fatal.

  ## What changed, and why the assertions moved with it

  rf2-8yyd bought the silence by registering `(fn [_record] nil)` on the
  corpus-wide `:errors` LISTENER registry — a no-op listener whose whole
  payload was the registration, because the fallback keyed on that registry
  being empty. That worked and was honest against the contract as written,
  but it silenced the console for EVERY frame on the page, Story's and the
  host app's alike: a claim on a door nobody reads, to change behaviour on a
  door nobody registered.

  rf2-kuky.18 re-keyed the fallback on \"nothing routed THIS record\", which
  gives Story a frame-scoped way to own its own errors and only its own:
  every Story-allocated frame declares `{:sink :rf.story/errors}` on its
  `[:observability :errors]` policy, and the mounted shell registers the
  concrete sink. So these assertions pin the SINK REGISTRY
  (`re-frame.observability/sinks`) and the frame policy, where they used to
  pin the listener registry — the precise things the mechanism now consults.
  Narrowing the claim to a different id, a different stream, or a
  `goog.DEBUG` branch the fallback does not read would fail here rather than
  in a twelve-minute browser gate.

  The host-app row below is no longer merely \"our release does not drop
  theirs\": a host app's frames are untouched BY CONSTRUCTION, because they
  declare no policy and Story registers nothing on their behalf. That is the
  property the page-wide claim could not express at all.

  What this namespace does NOT do is assert that a console error stops
  appearing — that is the browser gate's job
  (`npm run test:story-feature-load`), which counts console errors for
  real."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.observability :as rf.observability]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

(def ^:private register-sink!   @#'rf.story.ui.shell/register-error-sink!)
(def ^:private unregister-sink! @#'rf.story.ui.shell/unregister-error-sink!)

;; The registry the fallback's arm (b) resolves a frame's declared sink id
;; against. Private in `observability` because it is not an app-facing
;; surface; read here because it is the precise thing this fix moved onto.
(def ^:private sinks @#'rf.observability/sinks)

;; The corpus-wide listener registry — arm (a). Read only to prove Story no
;; longer touches it at all, which is the whole point of the change.
(def ^:private listeners @#'rf.error-emit/listeners)

(def ^:private variant-frame-config @#'rf.story.frames/variant-frame-config)
(def ^:private inline-frame-config  @#'rf.story.frames/inline-frame-config)

(use-fixtures :each
  {:before (fn []
             (rf.error-emit/clear-error-listeners!)
             (rf.observability/clear-observability-sinks!))
   :after  (fn []
             (rf.error-emit/clear-error-listeners!)
             (rf.observability/clear-observability-sinks!))})

(deftest mounting-registers-the-story-error-sink
  (testing "with no shell mounted nothing is registered under Story's id —
            a Story-frame refusal routes nowhere and the fallback fires"
    (is (nil? (get @sinks rf.story.config/error-sink-id))))
  (testing "registering the shell's sink puts a real fn under the id every
            Story frame's policy names, which is what makes those frames'
            records ROUTED and the console quiet for them"
    (register-sink!)
    (is (fn? (get @sinks rf.story.config/error-sink-id)))))

(deftest unmounting-restores-the-fallback
  (testing "unregistering on unmount hands the untooled-dev console fallback
            back to whatever runs on the page next: the frames still declare
            the policy, but it now names a sink nobody has registered, which
            routes NOWHERE and does not own the record"
    (register-sink!)
    (is (fn? (get @sinks rf.story.config/error-sink-id)))
    (unregister-sink!)
    (is (nil? (get @sinks rf.story.config/error-sink-id)))))

(deftest registration-is-idempotent
  (testing "re-mounting a shell replaces the same id rather than stacking
            registrations, so ONE unregister still frees it"
    (register-sink!)
    (register-sink!)
    (is (= 1 (count (select-keys @sinks [rf.story.config/error-sink-id]))))
    (unregister-sink!)
    (is (nil? (get @sinks rf.story.config/error-sink-id)))))

(deftest story-never-touches-the-corpus-wide-listener-registry
  (testing "the claim is frame-scoped policy now, not a page-wide listener.
            Mounting must leave the `:errors` LISTENER registry exactly as it
            found it — that registry is the host app's, and Story taking it
            was what silenced frames Story never mounted (rf2-kuky.18)"
    (is (empty? @listeners))
    (register-sink!)
    (is (empty? @listeners)
        "mounting registers a sink, not a listener")
    (unregister-sink!)
    (is (empty? @listeners))))

(deftest a-host-apps-own-error-listener-is-untouched
  (testing "a consuming app's own `:errors` listener is its own; Story
            registers and unregisters a SINK, so a Story mount/unmount cycle
            cannot add to, drop, or shadow it"
    (rf.error-emit/register-error-listener! ::host-app (fn [_record] nil))
    (register-sink!)
    (is (= [::host-app] (vec (keys @listeners))))
    (unregister-sink!)
    (is (= [::host-app] (vec (keys @listeners))))))

(deftest every-story-allocated-frame-declares-the-sink
  (testing "the shell's sink is only half the mechanism — the other half is
            that Story's frame configs NAME it. A variant frame carries the
            policy"
    (is (= [{:sink rf.story.config/error-sink-id}]
           (get-in (variant-frame-config :story.demo/variant nil {}) [:observability :errors]))))
  (testing "and so does an inline-plan frame, which fails on purpose in
            exactly the same way"
    (is (= [{:sink rf.story.config/error-sink-id}]
           (get-in (inline-frame-config :story.demo/inline nil) [:observability :errors]))))
  (testing "the sink id is the public-shaped `:rf.story/errors`, visible to a
            reader through `rf/frame-meta` rather than a private token"
    (is (= :rf.story/errors rf.story.config/error-sink-id))))

(deftest the-policy-is-conjed-never-substituted
  (testing "an author's own `[:observability :errors]` entry on a frame
            config survives Story adding its own — the `(fnil conj [])` is
            load-bearing, and replacing the vector would silently drop a
            consumer's production sink on every Story frame"
    (let [config (-> (variant-frame-config :story.demo/variant nil {})
                     (update-in [:observability :errors] conj {:sink :app/datadog}))]
      (is (= [{:sink rf.story.config/error-sink-id} {:sink :app/datadog}]
             (get-in config [:observability :errors]))))))

(deftest a-real-story-frame-routes-its-refusal-to-the-sink
  (testing "the two halves composed, end to end and through the real
            framework: a frame built from Story's own config, a refusal
            dispatched into it, and the record arriving at the shell's
            registered sink. Without this the rows above could each be true
            while the pair still failed to route anything"
    (let [seen (atom [])]
      (rf/register-observability-sink! rf.story.config/error-sink-id
                                       (fn [r] (swap! seen conj r)))
      (rf/make-frame (assoc (variant-frame-config :story.owner/variant nil {})
                            :id :story.owner/variant))
      (rf/reg-event :story.owner/throws
                    {:frame :story.owner/variant}
                    (fn [_ _] (throw (ex-info "variant kaboom" {}))))
      (rf/dispatch-sync [:story.owner/throws] {:frame :story.owner/variant})
      (is (= 1 (count @seen))
          (str "the Story frame's refusal reached Story's sink; got "
               (count @seen)))
      (is (= :rf.observe/error (:kind (first @seen))))
      (is (= :story.owner/variant (:frame (first @seen))))
      (is (empty? @listeners)
          "and it did so with the corpus-wide listener registry untouched"))))
