(ns re-frame.story.network-runtime-test
  "A variant's authored `:network` fixture is REALIZED on the
  normal run paths (spec/017 §The network surface: \"the runner installs the
  route map and points `:fx-overrides` at the stub fx\").

  `rf.story.plan/lower-network` is pure: it keeps the authored routes at
  `[:world :network]` and emits the `{:rf.http/managed
  :rf.http/managed-test-stub}` redirect at `[:world :frame :fx-overrides]`,
  and registers nothing. Both live run paths consume both halves: each
  installs the route map before allocating the frame, and each hands the
  frame the plan's lowered `:fx-overrides`. A path that did not transfer the
  redirect would execute the REAL `:rf.http/managed` effect; one that
  transferred it but installed no route map would point it at an
  unregistered fx.

  THE LIVE-HTTP SENTINEL. Every test here registers `:rf.http/managed` as a
  CAPTURE-ONLY fx that records its payload and returns nil. No request is
  ever issued: the sentinel IS the real handler's slot for the duration of
  the test, so `live-requests` staying empty is positive proof the redirect
  fired, and a non-empty `live-requests` is exactly the failure this suite
  guards against (a run path reaching the production transport slot, or a
  redirect falling through an unregistered target onto it).

  JVM-only (`.clj`): `rf.story/run` returns a `CompletableFuture` that
  resolves synchronously, and the canned reply is dispatched inside the
  fx walk, so the whole request→reply round trip settles before `.get`
  returns. The compile-time half (that `lower-network` emits the override
  map at all) is `re-frame.story.plan-network-cljs-test`; this suite is about
  the run path that consumes it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story         :as rf.story]
            [re-frame.story.frames  :as rf.story.frames]
            [re-frame.story.network :as rf.story.network]))

(def ^:private live-requests
  "Payloads the capture-only `:rf.http/managed` sentinel saw. MUST stay
  empty — a single entry means a managed request escaped the fixture."
  (atom []))

(defn- reset-rf! [test-fn]
  ;; Drain first, so a previous test's undestroyed frame unwinds the shared
  ;; install/uninstall pair before `clear-all!` drops the registrar.
  (doseq [id (keys @rf.story.network/frame-routes)]
    (rf.story.network/release-frame! id))
  (reset! live-requests [])
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  ;; THE SENTINEL. This occupies the production `:rf.http/managed` fx slot
  ;; for the whole test and issues nothing.
  (rf/reg-fx :rf.http/managed
             (fn [_ctx payload] (swap! live-requests conj payload) nil))
  (rf/reg-event :net/load
                (fn [_ _]
                  {:fx [[:rf.http/managed
                         {:request    {:method :get :url "/api/cart"}
                          :on-success [:net/loaded]
                          :on-failure [:net/failed]}]]}))
  (rf/reg-event :net/load-missing
                (fn [_ _]
                  {:fx [[:rf.http/managed
                         {:request    {:method :get :url "/api/nope"}
                          :on-success [:net/loaded]
                          :on-failure [:net/failed]}]]}))
  (rf/reg-event :net/loaded (fn [{:keys [db]} [_ reply]] {:db (assoc db :cart (:value reply))}))
  (rf/reg-event :net/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :error reply)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target [target]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run target)))

(def ^:private cart-fixture
  {[:get "/api/cart"] {:reply {:ok {:items [1]}}}})

;; ===========================================================================
;; The registered path
;; ===========================================================================

(deftest registered-variant-realizes-its-network-fixture
  (testing "a REGISTERED variant's authored :network delivers the canned reply
            with ZERO live requests (run-phase-0! installs the route map AND
            threads the plan's lowered frame :fx-overrides)"
    (rf.story/reg-variant :story.net/cart
                          {:network cart-fixture
                           :setup   [[:dispatch [:net/load]]]})
    (let [result (run-target :story.net/cart)]
      (is (= {:items [1]} (:cart (:app-db result)))
          "the authored fixture answered the managed request")
      (is (= [] @live-requests)
          "SENTINEL: no managed request reached the production fx slot"))))

(deftest registered-variant-unmatched-route-fails-with-no-stub-matched
  (testing "an unmatched URL follows the helper's canned no-match
            transport failure (spec/017 §Network stubs) rather than escaping
            to the real handler"
    (rf.story/reg-variant :story.net/missing
                          {:network cart-fixture
                           :setup   [[:dispatch [:net/load-missing]]]})
    (let [result (run-target :story.net/missing)
          err    (:error (:app-db result))]
      (is (some? err) "the unmatched route produced a failure reply")
      (is (re-find #"no stub matched" (pr-str err))
          "and it is the helper's own canned no-match failure")
      (is (= [] @live-requests)
          "SENTINEL: an unmatched route still issues no live request"))))

(deftest extends-child-flipping-a-route-to-failure-fires-the-failure
  (testing "a child that :extends an :ok variant and flips the SAME route to
            :failure gets its failure on a live run. Were the route
            deep-merged to {:reply {:ok .. :failure ..}}, the stub would test
            :ok first and the child would silently answer the parent's :ok"
    (rf.story/reg-variant :story.netext/ok
                          {:network cart-fixture
                           :setup   [[:dispatch [:net/load]]]})
    (rf.story/reg-variant :story.netext/fails
                          {:extends :story.netext/ok
                           :network {[:get "/api/cart"]
                                     {:reply {:failure {:kind :rf.http/http-4xx :status 409}}}}})
    (let [db (:app-db (run-target :story.netext/fails))]
      (is (nil? (:cart db)) "the parent's :ok reply did NOT answer")
      (is (= :rf.http/http-4xx (get-in db [:error :error :kind]))
          "the child's :failure reply answered")
      (is (= 409 (get-in db [:error :error :status])))
      (is (= [] @live-requests)
          "SENTINEL: no managed request reached the production fx slot"))))

;; ===========================================================================
;; The inline path
;; ===========================================================================

(deftest inline-plan-realizes-its-network-fixture
  (testing "an INLINE plan map's authored :network delivers the same canned
            reply. The inline path transfers the lowered redirect, so
            without its route-map install it would point `:rf.http/managed`
            at an UNREGISTERED `:rf.http/managed-test-stub`"
    (let [result (run-target {:network cart-fixture
                              :setup   [[:dispatch [:net/load]]]})]
      (is (= {:items [1]} (:cart (:app-db result)))
          "the inline fixture answered the managed request")
      (is (= [] @live-requests)
          "SENTINEL: no managed request reached the production fx slot"))))

;; ===========================================================================
;; The EXPLICIT-APPLICATION-IMAGE path
;;
;; The two registered tests above run on the DEFAULT image — `:images` absent,
;; so the frame projects the WHOLE source store and the helper-installed
;; `:rf.http/managed-test-stub` is visible through it. A variant that declares
;; (or inherits) an app image resolves through a SELECTED, sealed generation
;; instead, and the stub the HTTP helper registers carries no source
;; namespace, so no namespace glob selects it. A frame that could not resolve
;; the `:fx-overrides` redirect's target would fall through to the real
;; `:rf.http/managed` slot (here, the capture sentinel).
;;
;; The fixture stays reachable through the selected generation WITHOUT
;; widening the app image: the framework base every explicit composition is
;; layered over carries the unstamped `:rf`-rooted stub, and
;; `frames/compose-variant-images` also layers the library-owned
;; `:rf.story/network-fixture` image, which carries exactly ONE inline
;; `:reg-fx` — the very handler `install-managed-request-stubs!` registered
;; over the frame-scoped route map (spec/017 §Reaching the fixture through a
;; selected app image). One implementation, one route map, two projections.
;; ===========================================================================

(deftest registered-variant-with-inherited-app-image-realizes-its-fixture
  (testing "a variant whose app image is INHERITED from its parent story still
            gets its authored :network fixture — the selected generation
            resolves the stub target rather than falling through to the
            production :rf.http/managed slot"
    (rf.story/reg-story :story.netimg
                        {:doc    "Parent story declaring the app image once."
                         :images [(rf/image {:id        :net/app-image
                                             :select-ns {:include ["re-frame.story.network-runtime-test"]}})]})
    (rf.story/reg-variant :story.netimg/cart
                          {:network cart-fixture
                           :setup   [[:dispatch [:net/load]]]})
    (let [result (run-target :story.netimg/cart)]
      (is (= [:net/app-image] (:images result))
          "precondition: the variant really did inherit an EXPLICIT app image
           (so the frame resolves a SELECTED generation, not the default
           whole-store projection)")
      (is (= {:items [1]} (:cart (:app-db result)))
          "the authored fixture answered the managed request through the
           selected image")
      (is (= [] @live-requests)
          "SENTINEL: no managed request reached the production fx slot"))))

(deftest registered-variant-with-own-app-image-realizes-its-fixture
  (testing "the same holds for an app image declared on the VARIANT body"
    (rf.story/reg-variant :story.netimg2/cart
                          {:images  [(rf/image {:id        :net/app-image
                                                :select-ns {:include ["re-frame.story.network-runtime-test"]}})]
                           :network cart-fixture
                           :setup   [[:dispatch [:net/load]]]})
    (let [result (run-target :story.netimg2/cart)]
      (is (= [:net/app-image] (:images result))
          "precondition: an explicit app image is in force")
      (is (= {:items [1]} (:cart (:app-db result)))
          "the authored fixture answered the managed request")
      (is (= [] @live-requests)
          "SENTINEL: no managed request reached the production fx slot"))))

(deftest app-image-frame-keeps-application-isolation
  (testing "the fixture image adds EXACTLY the one stub fx the redirect names —
            it does not widen the app image to the whole store. A registration
            the app image does not select stays UNRESOLVABLE in the frame."
    ;; A registration authored OUTSIDE the app image's selected namespace.
    (require 'story.test-helpers.image-behaviour-v1 :reload)
    (rf.story/reg-variant :story.netimg3/cart
                          {:images  [(rf/image {:id        :net/app-image
                                                :select-ns {:include ["re-frame.story.network-runtime-test"]}})]
                           :network cart-fixture
                           :setup   [[:dispatch [:net/load]]]})
    (let [result   (run-target :story.netimg3/cart)
          resolver (:rf.gen/resolver (rf/frame-generation :story.netimg3/cart))]
      (is (= {:items [1]} (:cart (:app-db result)))
          "the fixture answered")
      (is (contains? resolver [:fx :rf.http/managed-test-stub])
          "the stub fx the :fx-overrides redirect names IS in the frame's
           generation, so the redirect resolves")
      (is (not (contains? resolver [:event :img.counter/step]))
          "and nothing else came with it: a registration authored outside the
           app image's selected namespace is still invisible to the frame")
      (is (= [] @live-requests) "SENTINEL: still no live request"))))

;; ===========================================================================
;; Isolation + ownership
;; ===========================================================================

(deftest concurrently-mounted-variants-keep-their-own-replies
  (testing "two variants mounted at once, both stubbing the SAME url with
            DIFFERENT replies, each keep their own — the route map is
            resolved against the frame in flight, not closed over at install
            time, so the second install cannot answer for the first"
    (rf.story/reg-variant :story.net/a
                          {:network {[:get "/api/cart"] {:reply {:ok {:items [:a]}}}}
                           :setup   [[:dispatch [:net/load]]]})
    (rf.story/reg-variant :story.net/b
                          {:network {[:get "/api/cart"] {:reply {:ok {:items [:b]}}}}
                           :setup   [[:dispatch [:net/load]]]})
    (let [a1 (run-target :story.net/a)
          b1 (run-target :story.net/b)
          ;; A is still mounted; re-running it must NOT pick up B's routes.
          a2 (run-target :story.net/a)]
      (is (= {:items [:a]} (:cart (:app-db a1))))
      (is (= {:items [:b]} (:cart (:app-db b1))))
      (is (= {:items [:a]} (:cart (:app-db a2)))
          "A still answers its OWN fixture with B mounted alongside")
      (is (= [] @live-requests) "SENTINEL: still no live request"))))

(deftest destroy-releases-only-that-frames-fixture
  (testing "tearing one variant down drops exactly its ownership and leaves
            every other mounted variant's replies intact"
    (rf.story/reg-variant :story.net/a
                          {:network {[:get "/api/cart"] {:reply {:ok {:items [:a]}}}}
                           :setup   [[:dispatch [:net/load]]]})
    (rf.story/reg-variant :story.net/b
                          {:network {[:get "/api/cart"] {:reply {:ok {:items [:b]}}}}
                           :setup   [[:dispatch [:net/load]]]})
    (run-target :story.net/a)
    (run-target :story.net/b)
    (is (some? (rf.story.network/routes-for :story.net/a)))
    (is (some? (rf.story.network/routes-for :story.net/b)))
    (rf.story.frames/destroy! :story.net/a)
    (is (nil? (rf.story.network/routes-for :story.net/a))
        "the destroyed frame's fixture is released")
    (is (some? (rf.story.network/routes-for :story.net/b))
        "the surviving frame keeps its own")
    (let [b2 (run-target :story.net/b)]
      (is (= {:items [:b]} (:cart (:app-db b2)))
          "and it still answers after the sibling teardown"))
    (rf.story.frames/destroy! :story.net/b)
    (is (= {} @rf.story.network/frame-routes)
        "the last release empties the registry")
    (is (= [] @live-requests) "SENTINEL: still no live request")))

(deftest inline-run-completion-releases-its-fixture
  (testing "an inline run's own teardown releases the anonymous frame's
            fixture, so a headless inline run leaks no ownership"
    (run-target {:network cart-fixture :setup [[:dispatch [:net/load]]]})
    (is (= {} @rf.story.network/frame-routes)
        "destroy-inline! released the inline frame's routes")
    (is (= [] @live-requests) "SENTINEL: still no live request")))
