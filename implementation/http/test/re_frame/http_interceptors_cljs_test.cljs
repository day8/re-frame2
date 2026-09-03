(ns re-frame.http-interceptors-cljs-test
  "CLJS-side smoke for Spec 014 §Middleware — per-frame request
  interceptor chain (rf2-6y3q).

  The JVM test (re-frame.http-interceptors-test) covers the full
  end-to-end shape: real transport, real headers landing on the wire,
  trace event assertion. This file confirms that on CLJS:

  - `reg-http-interceptor` / `clear-http-interceptor` round-trip
    against the per-frame registry.
  - Re-registering an id replaces in place.
  - Invalid shape raises `:rf.error/http-bad-interceptor`.
  - The per-frame scope holds (registry-level — frame A and frame B
    have independent slots).
  - The late-bind hooks publish under their documented keys.

  The CLJS Fetch transport itself is covered by the existing CLJS
  test suite for `:rf.http/managed`; this smoke is scoped to the
  interceptor surface."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.middleware :as rf.http.middleware]))

;; EP-0002 (rf2-nn0jqa): the no-`:frame` `reg-http-interceptor` /
;; `clear-http-interceptor` calls below resolve the frame through the
;; carried-invariant scope chain (rf2-5q7um6), so the fixture registers
;; `:rf/default` and pins it as the established scope for each test body —
;; the wrapping fn form replaces the prior `:before`/`:after` map so the
;; body can run inside `*current-frame*` :rf/default. Tests that name a
;; frame explicitly (the per-frame-scope case) still override it.
(use-fixtures :each
  (fn [t]
    (rf.http.managed/clear-all-http-interceptors!)
    (rf.frame/ensure-default-frame!)
    (binding [rf.frame/*current-frame* :rf/default]
      (t))
    (rf.http.managed/clear-all-http-interceptors!)))

;; ---- 1. round-trip register / clear ---------------------------------------

(deftest register-and-clear-round-trip
  (testing "reg-http-interceptor adds a slot; clear removes it"
    (rf/reg-http-interceptor :a {:before (fn [ctx] ctx)})
    (let [chain (rf.http.managed/interceptors-snapshot :rf/default)]
      (is (= 1 (count chain)))
      (is (= :a (:id (first chain)))))
    (rf/clear-http-interceptor :a)
    (let [chain (rf.http.managed/interceptors-snapshot :rf/default)]
      (is (zero? (count chain))))))

;; ---- 1a. rf2-vl5xsp — single-arity clear FAILS CLOSED under no scope ------
;;
;; The fixture pins an ambient `*current-frame* :rf/default`, which MASKS the
;; old facade floor (the single-arity used to recurse `[:rf/default id]`,
;; synthesising the default before delegating). Clear the ambient scope
;; (`*current-frame* nil`) and assert the single-arity facade raises the
;; always-on `:rf.error/no-frame-context` — proving the public surface fails
;; closed and the :rf/default floor is gone.

(deftest clear-http-interceptor-single-arity-fails-closed-under-no-scope
  (testing "rf2-vl5xsp — single-arity `rf/clear-http-interceptor` under NO
            ambient frame raises :rf.error/no-frame-context; it does NOT
            synthesise a :rf/default target."
    (binding [rf.frame/*current-frame* nil]
      (let [thrown (try (rf/clear-http-interceptor :some-id)
                        nil
                        (catch :default e e))]
        (is (some? thrown)
            "single-arity clear with no carried frame must throw")
        (is (= :rf.error/no-frame-context
               (:rf.error/id (ex-data thrown)))
            "the throw is the always-on :rf.error/no-frame-context — no :rf/default floor")))))

;; ---- 1b. rf2-9ynwvx — reg within with-frame installs; bare reg fails closed
;;
;; The RealWorld example apps (examples/real-apps/realworld_{http,resources})
;; registered the bearer-auth interceptor with a BARE top-level
;; `(reg-http-interceptor id {:before …})` in their boot `run` — no ambient
;; frame scope, no `:frame` — which raises the always-on
;; `:rf.error/no-frame-context` (EP-0002 context-required frame-local) and
;; installs NOTHING, silently dropping the Authorization header from every
;; authenticated request. The fix scopes the reg to the app frame with
;; `with-frame`. This pins both halves of that contract on the registration
;; surface (the fixture's ambient `*current-frame* :rf/default` masks the
;; bare-call raise, so we strip it): (a) a bare reg under no scope fails
;; closed and installs nothing; (b) `(with-frame f (reg-http-interceptor …))`
;; installs on f's chain even when f was never `make-frame`d — the example
;; registers before the frame-root ensures the frame.

(deftest reg-http-interceptor-bare-fails-closed-with-frame-installs-rf2-9ynwvx
  (testing "rf2-9ynwvx — a bare reg under no ambient scope raises
            :rf.error/no-frame-context and installs nothing; a
            (with-frame f …) reg installs on f's chain (the RealWorld fix)"
    (binding [rf.frame/*current-frame* nil]
      ;; (a) reproduce the example bug: bare reg with no scope fails closed.
      (let [thrown (try (rf/reg-http-interceptor :realworld/bearer-auth
                          {:before (fn [c] c)})
                        nil
                        (catch :default e e))]
        (is (some? thrown) "bare reg under no ambient scope must throw")
        (is (= :rf.error/no-frame-context (:rf.error/id (ex-data thrown)))
            "the throw is the always-on :rf.error/no-frame-context — nothing installed")
        (is (empty? (rf.http.managed/interceptors-snapshot :realworld/app))
            "no slot landed on the app-frame chain"))
      ;; (b) the fix: with-frame supplies the frame context, so the reg lands
      ;; on :realworld/app's chain even though it was never `make-frame`d.
      (rf/with-frame :realworld/app
        (rf/reg-http-interceptor :realworld/bearer-auth {:before (fn [c] c)}))
      (is (= [:realworld/bearer-auth]
             (mapv :id (rf.http.managed/interceptors-snapshot :realworld/app)))
          "with-frame scoped the reg onto the app frame's chain (the example fix)"))))

;; ---- 2. registration order is preserved -----------------------------------

(deftest registration-order-preserved
  (testing "first / second / third register in order"
    (rf/reg-http-interceptor :first  {:before (fn [c] c)})
    (rf/reg-http-interceptor :second {:before (fn [c] c)})
    (rf/reg-http-interceptor :third  {:before (fn [c] c)})
    (let [chain (rf.http.managed/interceptors-snapshot :rf/default)]
      (is (= [:first :second :third] (mapv :id chain))))))

;; ---- 3. re-register replaces in place -------------------------------------

(deftest re-register-replaces-in-place
  (testing "re-registering :a keeps its position; second :a does not duplicate"
    (rf/reg-http-interceptor :a {:before (fn [c] (assoc c ::v 1))})
    (rf/reg-http-interceptor :b {:before (fn [c] c)})
    (rf/reg-http-interceptor :a {:before (fn [c] (assoc c ::v 2))})
    (let [chain (rf.http.managed/interceptors-snapshot :rf/default)]
      (is (= [:a :b] (mapv :id chain)))
      (is (= {::v 2} ((:before (first chain)) {}))
          ":a's :before fn is the v2 fn (replacement)"))))

;; ---- 4. per-frame scope ---------------------------------------------------

(deftest per-frame-scope
  (testing "interceptors registered on different frames do not collide"
    (rf/reg-http-interceptor :on-default {:frame :rf/default :before (fn [c] c)})
    (rf/reg-http-interceptor :on-other   {:frame :other      :before (fn [c] c)})
    (is (= [:on-default] (mapv :id (rf.http.managed/interceptors-snapshot :rf/default))))
    (is (= [:on-other]   (mapv :id (rf.http.managed/interceptors-snapshot :other))))
    ;; clear-http-interceptor on :rf/default doesn't touch :other
    (rf/clear-http-interceptor :on-default)
    (is (zero? (count (rf.http.managed/interceptors-snapshot :rf/default))))
    (is (= [:on-other] (mapv :id (rf.http.managed/interceptors-snapshot :other))))))

;; ---- 4a. rf2-f28bno / rf2-s32bf — the public {:frame} opts form ------------
;;
;; `clear-http-interceptor`'s public 2-arity is EXACTLY the trailing
;; `{:frame …}` opts map (mirroring `reg-http-interceptor`'s `:frame`).
;; Two-scalar frame-first is not a public shape; artefact-internal cleanup
;; routes through the `clear-http-interceptor*` seam. This closes the silent
;; mis-clear the old `(or (:frame opts) ambient-frame)` resolution carried.

(deftest clear-http-interceptor-frame-arg-spelling-rf2-f28bno
  (testing "rf2-f28bno — `(clear-http-interceptor id {:frame f})` targets frame
            `f` from an ambient `:rf/default` scope; the internal
            `clear-http-interceptor*` seam (frame-first) still clears; and the
            old misbind guess now binds the frame correctly instead of no-op'ing."
    ;; The ambient scope is :rf/default (fixture). Register on the OTHER frame.
    (rf/reg-http-interceptor :fa/on-other {:frame :fa/other :before (fn [c] c)})
    (rf/reg-http-interceptor :fa/on-default {:before (fn [c] c)})
    (is (= [:fa/on-other]   (mapv :id (rf.http.managed/interceptors-snapshot :fa/other))))
    (is (= [:fa/on-default] (mapv :id (rf.http.managed/interceptors-snapshot :rf/default))))
    ;; (1) PUBLIC opts form clears the NAMED frame from the :rf/default scope —
    ;; this is exactly the natural guess `(clear id {:frame f})` from the reg
    ;; shape that used to SILENTLY NO-OP under the old frame-first arity. It now
    ;; binds :fa/other correctly.
    (rf/clear-http-interceptor :fa/on-other {:frame :fa/other})
    (is (zero? (count (rf.http.managed/interceptors-snapshot :fa/other)))
        "opts {:frame :fa/other} cleared the named frame's slot (misbind closed)")
    (is (= [:fa/on-default] (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
        "the :rf/default chain is untouched by the explicit-frame clear")
    ;; (2) INTERNAL frame-first seam still clears a named frame's slot.
    (rf/reg-http-interceptor :fa/again {:frame :fa/other :before (fn [c] c)})
    (is (= [:fa/again] (mapv :id (rf.http.managed/interceptors-snapshot :fa/other))))
    (rf.http.middleware/clear-http-interceptor* :fa/other :fa/again)   ;; private seam: (frame id)
    (is (zero? (count (rf.http.managed/interceptors-snapshot :fa/other)))
        "internal frame-first seam (clear-http-interceptor*) cleared the slot")))

;; ---- 4b. rf2-s32bf — the public opts form is EXACT + FAIL-CLOSED -----------

(deftest clear-http-interceptor-opts-form-fail-closed-rf2-s32bf
  (testing "rf2-s32bf — the public 2-arity opts map must be EXACTLY
            {:frame target}; malformed opts, a non-map second arg, and the old
            two-scalar frame-first shape fail closed with
            :rf.error/http-bad-interceptor and leave the ambient interceptor
            untouched; the exact {:frame target} form still clears."
    (letfn [(threw-bad? [thunk]
              (let [ex (try (thunk) nil (catch :default e e))]
                (and (some? ex)
                     (= :rf.error/http-bad-interceptor
                        (:rf.error/id (ex-data ex))))))]
      ;; ambient scope is :rf/default (fixture) — seed a slot there.
      (rf/reg-http-interceptor :s32bf/ambient {:before (fn [c] c)})
      (is (= [:s32bf/ambient]
             (mapv :id (rf.http.managed/interceptors-snapshot :rf/default))))
      (is (threw-bad? #(rf/clear-http-interceptor :s32bf/ambient {}))
          "empty opts map (no :frame) fails closed")
      (is (threw-bad? #(rf/clear-http-interceptor :s32bf/ambient {:frame nil}))
          "nil :frame fails closed")
      (is (threw-bad? #(rf/clear-http-interceptor :s32bf/ambient {:fram :rf/default}))
          "misspelled opts key fails closed")
      (is (threw-bad? #(rf/clear-http-interceptor :s32bf/ambient {:frame :rf/default :extra 1}))
          "extra opts key fails closed (map must be exactly {:frame target})")
      (is (threw-bad? #(rf/clear-http-interceptor :s32bf/ambient "not-a-map"))
          "non-map second arg fails closed")
      (is (threw-bad? #(rf/clear-http-interceptor :s32bf/ambient 42))
          "non-map scalar second arg fails closed")
      (is (threw-bad? #(rf/clear-http-interceptor :s32bf/ambient :some-frame))
          "old two-scalar frame-first is not a public shape — fails closed")
      (is (= [:s32bf/ambient]
             (mapv :id (rf.http.managed/interceptors-snapshot :rf/default)))
          "no malformed clear touched the ambient :rf/default interceptor")
      (rf/clear-http-interceptor :s32bf/ambient {:frame :rf/default})
      (is (zero? (count (rf.http.managed/interceptors-snapshot :rf/default)))
          "the exact {:frame target} form clears the named frame"))))

;; ---- 5. invalid shape raises ----------------------------------------------

(deftest invalid-shape-raises
  (testing "rf2-uheqq — non-keyword id, non-map interceptor-map, non-fn
            :before / :after, or missing both fns raises
            :rf.error/http-bad-interceptor"
    (let [thrown (try (rf/reg-http-interceptor "string-id" {:before (fn [c] c)})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    (let [thrown (try (rf/reg-http-interceptor :x "not-a-map")
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    (let [thrown (try (rf/reg-http-interceptor :x {:before "not-a-fn"})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    (let [thrown (try (rf/reg-http-interceptor :x {:after "not-a-fn"})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    ;; missing both :before and :after — a no-op interceptor is rejected
    (let [thrown (try (rf/reg-http-interceptor :x {:doc "no fns"})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))))

;; ---- 7. rf2-uheqq — `:after` slot stored alongside `:before` --------------

(deftest after-slot-is-stored
  (testing "rf2-uheqq — an interceptor registered with :after stamps the
            slot under :after on the stored interceptor map"
    (let [after-fn (fn [_ctx resp] resp)]
      (rf/reg-http-interceptor :uheqq/with-after {:after after-fn})
      (let [slot (first (filter #(= :uheqq/with-after (:id %))
                                (rf.http.managed/interceptors-snapshot :rf/default)))]
        (is (some? slot) "slot is in the chain")
        (is (= after-fn (:after slot))
            ":after fn stored verbatim on the slot")
        (is (nil? (:before slot))
            ":before is absent when only :after was supplied")))))

;; ---- 6. late-bind hooks publish under documented keys ---------------------

(deftest late-bind-hooks-published
  (testing ":http/reg-http-interceptor and :http/clear-http-interceptor land in the late-bind registry"
    (is (some? (rf.late-bind/get-fn :http/reg-http-interceptor)))
    (is (some? (rf.late-bind/get-fn :http/clear-http-interceptor)))
    (is (some? (rf.late-bind/get-fn :http/clear-all-http-interceptors!)))))
