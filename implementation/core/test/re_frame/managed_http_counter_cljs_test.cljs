(ns re-frame.managed-http-counter-cljs-test
  "Contract regression for the managed-HTTP counter example's PUBLIC
  cancellation wiring.

  The example cancels its own live `+1` request the way an application is
  meant to (Spec 014 example E): the request carries `:request-id
  :http-counter/+1`, and the Cancel button is nothing but
  `[:rf.http/managed-abort :http-counter/+1]`. Everything after that is the
  framework's — resolving the handle, firing `AbortController`, clearing the
  registry slot, and delivering a canonical `:status :cancelled` reply back to
  the handler that issued the request.

  That shape has two halves written in two places, and they can drift apart
  silently: change the id on the request and the abort still compiles, still
  runs, and simply resolves nothing — a cancel button that does nothing is
  indistinguishable from a request that finished first. These tests pin the
  two halves TOGETHER (a and b), and pin the `:cancelled` reply arm (c), whose
  own failure mode is worse than a no-op: a status the handler's `cond` does
  not enumerate falls through to the initiation arm and RE-ISSUES the request.

  It belongs in the framework test tree, NOT under `examples/` (examples are
  test-free). It `:require`s `managed-http-counter.core` (a
  Reagent-coupled `.cljs`-only entry ns — its ns-load registers the events /
  subs / fx, and transitively `re-frame.http.managed`) so it runs under the
  consolidated `:node-test` CLJS build, whose source paths include
  `../examples/core`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.reply :as rf.reply]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; Requiring the entry ns fires the example's ns-load
            ;; reg-event / reg-fx / reg-sub / reg-view forms (and,
            ;; transitively, the managed-HTTP fx family) against the live
            ;; registrar captured into this ns's fixture baseline.
            [managed-http-counter.core]))

;; `:ambient-frame nil` — these tests create + drive their own anon frames with
;; an explicit `{:frame f}`; no ambient `:rf/default` scope is wanted.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; Capture seam
;;
;; The example's own effects are the subject here, so we substitute the two
;; managed-HTTP effects with capturing stubs and read back what the handler
;; ASKED FOR. `:fx-overrides` on the anon frame is the supported way to do that
;; (the pattern at example_frame_scoping_cljs_test.cljs and
;; example_realworld_password_classification_cljs_test.cljs). No network, no
;; registry, no timing — the question is what the handler emitted.

(def ^:private captured
  "Vector of `[fx-id effect-value]`, newest last, cleared per frame."
  (atom []))

(rf/reg-fx :test.http-counter/capture-managed
  (fn [_frame-ctx args-map]
    (swap! captured conj [:rf.http/managed args-map])
    nil))

(rf/reg-fx :test.http-counter/capture-abort
  (fn [_frame-ctx request-id]
    (swap! captured conj [:rf.http/managed-abort request-id])
    nil))

(defn- capture-frame!
  "A fresh anon frame whose managed-HTTP effects are captured rather than
  executed, with the capture log cleared. Returns the frame."
  []
  (reset! captured [])
  (rf.frame/make-anon-frame-record!
    {:doc          "managed-http-counter public-cancel test frame"
     :fx-overrides {:rf.http/managed       :test.http-counter/capture-managed
                    :rf.http/managed-abort :test.http-counter/capture-abort}}))

(defn- effects-for
  "The effect values captured for `fx-id`, in order."
  [fx-id]
  (->> @captured (filter #(= fx-id (first %))) (mapv second)))

;; ---------------------------------------------------------------------------
;; (a) + (b) — the two halves of the public cancel handle, pinned together.

;; NOTE the `plus-one` spelling. A deftest name may not START `+1-`: the reader
;; tokenises it as a number and the namespace fails to compile.
(deftest plus-one-request-carries-the-public-cancel-handle
  (testing "the +1 handler's opening move issues :rf.http/managed carrying the
            :request-id that Cancel aborts by — without it the abort has
            nothing to resolve and fails as a silent no-op"
    (let [f        (capture-frame!)
          _        (rf/dispatch-sync [:http-counter/+1] {:frame f})
          requests (effects-for :rf.http/managed)
          args     (first requests)]
      (is (= 1 (count requests))
          "+1 issues exactly one managed request")
      (is (= :http-counter/+1 (:request-id args))
          "the request carries :request-id :http-counter/+1 — the handle the
           Cancel button names (Spec 014 example E puts :request-id at the top
           level of the :rf.http/managed map, beside :request and :reply-to)")
      (is (= [:http-counter/+1] (:reply-to args))
          "and addresses its reply back at the same handler, so the
           cancellation envelope lands on the cond below")
      (is (= :loading (:http-counter/status (rf/app-db-value f)))
          "the UI parks in :loading while that request is live"))))

(deftest cancel-aborts-the-same-id-the-plus-one-request-carries
  (testing "Cancel is the public abort and nothing else: it fires
            :rf.http/managed-abort at the very id test (a) pins onto the
            request. These two assertions are the drift guard — they fail
            together the moment either half is respelled alone"
    (let [f      (capture-frame!)
          _      (rf/dispatch-sync [:http-counter/cancel] {:frame f})
          aborts (effects-for :rf.http/managed-abort)]
      (is (= [:http-counter/+1] aborts)
          "exactly one abort, naming the +1 request's id")
      (is (empty? (effects-for :rf.http/managed))
          "and Cancel issues no request of its own"))))

;; ---------------------------------------------------------------------------
;; (c) — the :cancelled reply arm.

(deftest cancelled-reply-settles-idle-records-the-abort-and-never-re-issues
  (let [cancelled-reply {:status                 :cancelled
                         :cancelled?             true
                         :rf.reply/cancel-reason :user
                         :error                  {:kind       :rf.http/aborted
                                                  :request-id :http-counter/+1
                                                  :reason     :user}}]
    (testing "the fixture is the envelope the framework really delivers —
              asserted against the live validator so this test cannot drift
              into pinning a reply shape that could never arrive
              (re-frame.http.reply/failure-reply builds it on the abort path)"
      (is (rf.reply/valid-reply? cancelled-reply)
          (str "re-frame.reply/validate-reply must accept the fixture; problems="
               (pr-str (rf.reply/validate-reply cancelled-reply)))))

    ;; TOMBSTONE. There is no top-level `:cancel/reason` key; the reason rides
    ;; the namespaced `:rf.reply/cancel-reason`. This block carries the
    ;; retired spelling ON
    ;; PURPOSE, to pin it ABSENT — a tree-wide census that reads it as drift
    ;; and "fixes" it destroys the guard. The teeth are real: the retired
    ;; spelling is rejected as :rf.reply/cancelled-missing-reason even though
    ;; :status and :error are left untouched.
    (testing "the retired top-level :cancel/reason key stays retired"
      (is (not (contains? cancelled-reply :cancel/reason))
          "the canonical envelope carries the namespaced key only")
      (is (= :rf.reply/cancelled-missing-reason
             (:rf.reply/problem
               (first (rf.reply/validate-reply
                        (-> cancelled-reply
                            (dissoc :rf.reply/cancel-reason)
                            (assoc :cancel/reason :user))))))
          "restoring the retired spelling is refused by the reply contract"))

    (testing "the handler settles the UI, records the classified abort, and
              never re-issues — the last being the failure this cond's shape
              exists to prevent"
      (let [f  (capture-frame!)
            _  (rf/dispatch-sync [:http-counter/+1 cancelled-reply] {:frame f})
            db (rf/app-db-value f)]
        (is (= :idle (:http-counter/status db))
            "a cancelled request is not an error state; the UI returns to idle")
        (is (= :rf.http/aborted (get-in db [:http-counter/error :kind]))
            "the classified abort is recorded, so the view's error line reads
             \"Error kind: :rf.http/aborted\" rather than going blank")
        (is (= :user (get-in db [:http-counter/error :reason]))
            "carrying the reason that tells a user-cancel from a supersession")
        (is (empty? (effects-for :rf.http/managed))
            "and NO new request: an unenumerated status falling through to the
             initiation arm would re-issue the very request just cancelled")))))
