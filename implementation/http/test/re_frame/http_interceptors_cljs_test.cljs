(ns re-frame.http-interceptors-cljs-test
  "CLJS-side smoke for Spec 014 §Middleware — per-frame request
  interceptor chain.

  The JVM test (re-frame.http-interceptors-test) covers the full
  end-to-end shape: real transport, real headers landing on the wire,
  trace event assertion. This file confirms that on CLJS:

  - `reg-http-interceptor` / `clear-http-interceptor` round-trip
    against the per-frame registry.
  - Re-registering an id replaces in place.
  - The per-frame scope holds (registry-level — frame A and frame B
    have independent slots).
  - The late-bind hooks publish under their documented keys.

  The clear grammar, the invalid-shape error id and the `:after` slot are
  platform-neutral `.cljc` code, pinned once by the JVM test. The CLJS
  Fetch transport itself is covered by the CLJS test suite for
  `:rf.http/managed`; this smoke is scoped to the interceptor surface."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.http.managed :as rf.http.managed]))

;; EP-0002: the no-`:frame` `reg-http-interceptor` /
;; `clear-http-interceptor` calls below resolve the frame through the
;; carried-invariant scope chain, so the fixture registers
;; `:rf/default` and pins it as the established scope for each test body —
;; the fixture is a wrapping fn rather than a `:before`/`:after` map so the
;; body can run inside `*current-frame*` :rf/default. Tests that name a
;; frame explicitly (the per-frame-scope case) override it.
(use-fixtures :each
  (fn [t]
    (rf.http.managed/clear-all-http-interceptors!)
    (rf.frame/ensure-default-frame!)
    (binding [rf.frame/*current-frame* :rf/default]
      (t))
    (rf.http.managed/clear-all-http-interceptors!)))

;; ---- 1a. single-arity clear FAILS CLOSED under no scope -------------------
;;
;; The fixture pins an ambient `*current-frame* :rf/default`, which would MASK
;; a facade floor (a single-arity that recursed `[:rf/default id]`,
;; synthesising the default before delegating). Clear the ambient scope
;; (`*current-frame* nil`) and assert the single-arity facade raises the
;; always-on `:rf.error/no-frame-context` — proving the public surface fails
;; closed with no :rf/default floor.

(deftest clear-http-interceptor-single-arity-fails-closed-under-no-scope
  (testing "no-opts `(rf/clear :http-interceptor id)` under NO
            ambient frame raises :rf.error/no-frame-context; it does NOT
            synthesise a :rf/default target."
    (binding [rf.frame/*current-frame* nil]
      (let [thrown (try (rf/clear :http-interceptor :some-id)
                        nil
                        (catch :default e e))]
        (is (some? thrown)
            "single-arity clear with no carried frame must throw")
        (is (= :rf.error/no-frame-context
               (:rf.error/id (ex-data thrown)))
            "the throw is the always-on :rf.error/no-frame-context — no :rf/default floor")))))

;; ---- 1b. reg within with-frame installs; bare reg fails closed
;;
;; A BARE top-level `(reg-http-interceptor id {:before …})` in an app's boot
;; `run` — no ambient frame scope, no `:frame` — raises the always-on
;; `:rf.error/no-frame-context` (EP-0002 context-required frame-local) and
;; installs NOTHING, so a bearer-auth interceptor registered that way would
;; silently drop the Authorization header from every authenticated request.
;; The RealWorld example apps (examples/real-apps/realworld_{http,resources})
;; scope the reg to the app frame with `with-frame`. This pins both halves of
;; that contract on the registration
;; surface (the fixture's ambient `*current-frame* :rf/default` masks the
;; bare-call raise, so we strip it): (a) a bare reg under no scope fails
;; closed and installs nothing; (b) `(with-frame f (reg-http-interceptor …))`
;; installs on f's chain even when f was never `make-frame`d — the example
;; registers before the frame-root ensures the frame.

(deftest reg-http-interceptor-bare-fails-closed-with-frame-installs-rf2-9ynwvx
  (testing "a bare reg under no ambient scope raises
            :rf.error/no-frame-context and installs nothing; a
            (with-frame f …) reg installs on f's chain (the RealWorld pattern)"
    (binding [rf.frame/*current-frame* nil]
      ;; (a) a bare reg with no scope fails closed.
      (let [thrown (try (rf/reg-http-interceptor :realworld/bearer-auth
                          {:before (fn [c] c)})
                        nil
                        (catch :default e e))]
        (is (some? thrown) "bare reg under no ambient scope must throw")
        (is (= :rf.error/no-frame-context (:rf.error/id (ex-data thrown)))
            "the throw is the always-on :rf.error/no-frame-context — nothing installed")
        (is (empty? (rf.http.managed/interceptors-snapshot :realworld/app))
            "no slot landed on the app-frame chain"))
      ;; (b) with-frame supplies the frame context, so the reg lands
      ;; on :realworld/app's chain even though it was never `make-frame`d.
      (rf/with-frame :realworld/app
        (rf/reg-http-interceptor :realworld/bearer-auth {:before (fn [c] c)}))
      (is (= [:realworld/bearer-auth]
             (mapv :id (rf.http.managed/interceptors-snapshot :realworld/app)))
          "with-frame scoped the reg onto the app frame's chain (the example's pattern)"))))

;; ---- 2. a new id appends; re-registering an id replaces it in place ------

(deftest re-register-replaces-in-place
  (testing "re-registering :a keeps its position; second :a does not duplicate"
    (rf/reg-http-interceptor :a {:before (fn [c] (assoc c ::v 1))})
    (rf/reg-http-interceptor :b {:before (fn [c] c)})
    (rf/reg-http-interceptor :a {:before (fn [c] (assoc c ::v 2))})
    (let [chain (rf.http.managed/interceptors-snapshot :rf/default)]
      (is (= [:a :b] (mapv :id chain)))
      (is (= {::v 2} ((:before (first chain)) {}))
          ":a's :before fn is the v2 fn (replacement)"))))

;; ---- 3. per-frame scope ---------------------------------------------------

(deftest per-frame-scope
  (testing "interceptors registered on different frames do not collide"
    (rf/reg-http-interceptor :on-default {:frame :rf/default :before (fn [c] c)})
    (rf/reg-http-interceptor :on-other   {:frame :other      :before (fn [c] c)})
    (is (= [:on-default] (mapv :id (rf.http.managed/interceptors-snapshot :rf/default))))
    (is (= [:on-other]   (mapv :id (rf.http.managed/interceptors-snapshot :other))))
    ;; clear-http-interceptor on :rf/default doesn't touch :other
    (rf/clear :http-interceptor :on-default)
    (is (zero? (count (rf.http.managed/interceptors-snapshot :rf/default))))
    (is (= [:on-other] (mapv :id (rf.http.managed/interceptors-snapshot :other))))))

;; ---- 4. late-bind hooks publish under documented keys ---------------------

(deftest late-bind-hooks-published
  (testing ":http/reg-http-interceptor and :http/clear-http-interceptor land in the late-bind registry"
    (is (some? (rf.late-bind/get-fn :http/reg-http-interceptor)))
    (is (some? (rf.late-bind/get-fn :http/clear-http-interceptor)))
    (is (some? (rf.late-bind/get-fn :http/clear-all-http-interceptors!)))))
