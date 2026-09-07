(ns re-frame.registrar-query-source-cljs-test
  "rf2-kuky.30 — the registrar query grammar names its SOURCE explicitly.

  `rf/registrations` and `rf/handler-meta` each take exactly one argument, a
  query map carrying EXACTLY ONE source selector:

    (rf/registrations {:source :store :kind :sub})
    (rf/registrations {:frame f      :kind :sub})
    (rf/handler-meta  {:source :store :kind :sub :id id})
    (rf/handler-meta  {:frame f      :kind :sub :id id})

  ## The defect this pins

  The retired positional arity — `(registrations :sub)` — DOCUMENTED itself as
  reading the default source store, but delegated to
  `rf.registrar/registrations` / `lookup`, which consult
  `rf.registrar/*generation*` FIRST. `re-frame.live-frame/call-with-frame-
  resolution` binds that generation around every subscribe build, dispatch, fx
  and view resolution targeting an image-loaded frame — `re-frame.subs`'s
  build path wraps the whole computation in it — and `make-frame` seals a
  generation unconditionally, so EVERY image-loaded frame has one. A \"store\"
  read issued from inside a sub computation therefore silently read THAT
  FRAME'S IMAGE.

  That bit an inspector hardest: Xray runs in its own `:rf/xray` image-loaded
  frame, so its registry panels saw only Xray's own registrations. Xray worked
  around it by deref'ing the private `re-frame.registrar/kind->id->metadata`
  atom from a `host_registry.cljs` helper (deleted by this change).

  ## The three-way regression

  `registrar-reads-inside-a-frame-resolution-are-source-discriminated` is the
  load-bearing case: ONE `[kind id]` carrying THREE DIFFERENT descriptors — one
  in the process source store, one in a host frame's image, one in an
  inspector frame's image — read from INSIDE the inspector frame's resolution
  binding. Each of the three query forms must answer with its OWN source's
  descriptor.

  The binding is established with `rf.live-frame/call-with-frame-resolution`,
  which is not a test stand-in: it is the exact fn `re-frame.subs` wraps a
  subscription computation in (`subs.cljc`, the `subscribe-in-frame` build
  path), and the same fn `rf/registrations` / `rf/handler-meta` use for their
  own `{:frame f …}` form. The test asserts POSITIVELY that the binding is
  live — a generation-routed read taken in the same body returns the
  inspector's descriptor — so a green `{:source :store}` assertion cannot be
  a binding that silently failed to take.

  `.cljc` ending `-cljs-test` so it rides `npm run test:cljs` AND
  `clojure -M:test`. Each fail-loud assertion checks the `:rf.error/id`
  discriminator, never the message bytes (Spec 009 §The thrown-error shape
  rule 3)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame     :as rf.live-frame]
            [re-frame.registrar      :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (t)
    (rf.image-assembly/clear-standards!)))

;; ---------------------------------------------------------------------------
;; Helpers — the same synthetic-descriptor idiom facade_frame_read_cljs_test
;; uses: `make-frame`'s 2-arity takes an explicit descriptor pool, so the
;; frames' registrations are INDEPENDENT of the process source store. That
;; independence is the whole point here.
;; ---------------------------------------------------------------------------

(defn- reg-desc [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ONE id, THREE descriptors.
(def ^:private shared-id :counter/value)

(def ^:private host-pool
  [(reg-desc "host.core" :sub shared-id ::host-value)
   (reg-desc "host.core" :sub :host/only ::host-only)])

(def ^:private inspector-pool
  [(reg-desc "inspector.core" :sub shared-id ::inspector-value)])

(def ^:private host-img
  (rf.image/image {:id :host/img :select-ns {:include ["host.core"]}}))

(def ^:private inspector-img
  (rf.image/image {:id :inspector/img :select-ns {:include ["inspector.core"]}}))

(defn- seat-three-sources!
  "Register the STORE descriptor and seat the two image-loaded frames.
  Returns the inspector frame object."
  []
  (rf.registrar/register! :sub shared-id {:handler-fn       ::store-value
                                          :rf.provenance/ns "store.core"})
  (rf.registrar/register! :sub :store/only {:handler-fn ::store-only})
  (rf/make-frame {:id :host/main :images [host-img]} host-pool)
  (rf/make-frame {:id :inspector/main :images [inspector-img]} inspector-pool))

;; ===========================================================================
;; 1. THE THREE-WAY REGRESSION
;; ===========================================================================

(deftest registrar-reads-inside-a-frame-resolution-are-source-discriminated
  (testing "one [kind id] carrying three different descriptors — store, host
            frame image, inspector frame image — read from INSIDE the inspector
            frame's resolution binding. Each query form answers with its own
            source's descriptor."
    (let [inspector (seat-three-sources!)]
      (rf.live-frame/call-with-frame-resolution
        inspector
        (fn []
          (testing "the binding is LIVE — a generation-routed read (the path
                    every runtime resolution takes, and the path the retired
                    positional arity took) resolves the INSPECTOR's descriptor"
            (is (= ::inspector-value
                   (:handler-fn (rf.registrar/handler-meta :sub shared-id)))
                "control: without this, a green :source :store assertion below
                 would prove nothing — the generation might not be bound"))

          (testing "{:source :store} reads the process source store"
            (is (= ::store-value
                   (:handler-fn (rf/handler-meta {:source :store
                                                  :kind   :sub
                                                  :id     shared-id}))))
            (let [store-sub-ids (set (keys (rf/registrations {:source :store
                                                              :kind   :sub})))]
              (is (contains? store-sub-ids :store/only)
                  "and enumerates the STORE's ids (framework-standard subs are
                   registered too, so this is containment, not equality)")
              (is (contains? store-sub-ids shared-id))))

          (testing "{:frame host} reads the HOST frame's sealed image generation"
            (is (= ::host-value
                   (:handler-fn (rf/handler-meta {:frame :host/main
                                                  :kind  :sub
                                                  :id    shared-id}))))
            (is (= #{shared-id :host/only}
                   (set (keys (rf/registrations {:frame :host/main :kind :sub}))))
                "the frame form projects ONLY that frame's image, so it IS an
                 exact set"))

          (testing "{:frame inspector} reads the INSPECTOR's own generation"
            (is (= ::inspector-value
                   (:handler-fn (rf/handler-meta {:frame :inspector/main
                                                  :kind  :sub
                                                  :id    shared-id}))))
            (is (= #{shared-id}
                   (set (keys (rf/registrations {:frame :inspector/main :kind :sub})))))))))))

(deftest store-reads-answer-the-same-inside-and-outside-a-binding
  (testing "the {:source :store} answer does not depend on the caller's context
            — that context-independence IS the fix"
    (let [inspector (seat-three-sources!)
          outside-meta  (rf/handler-meta {:source :store :kind :sub :id shared-id})
          outside-regs  (rf/registrations {:source :store :kind :sub})
          [inside-meta inside-regs]
          (rf.live-frame/call-with-frame-resolution
            inspector
            (fn [] [(rf/handler-meta {:source :store :kind :sub :id shared-id})
                    (rf/registrations {:source :store :kind :sub})]))]
      (is (= outside-meta inside-meta))
      (is (= outside-regs inside-regs))
      (is (= ::store-value (:handler-fn inside-meta))))))

;; ===========================================================================
;; 2. THE RESERVED-BUT-EMPTY KINDS FAIL LOUD AND NAME THE REAL DOOR
;; ===========================================================================

(deftest reserved-empty-kinds-are-not-queryable
  (testing ":flow and :frame are RESERVED-BUT-EMPTY registrar slots. Returning
            {} for them handed the caller an apparently authoritative empty
            catalogue while the real store sat elsewhere (it bit Xray's flow
            panel). They now throw, naming the owning read."
    (doseq [kind [:flow :frame]]
      (is (= :rf.error/registrar-kind-not-queryable
             (err-id #(rf/registrations {:source :store :kind kind})))
          (str "registrations " kind))
      (is (= :rf.error/registrar-kind-not-queryable
             (err-id #(rf/handler-meta {:source :store :kind kind :id :anything})))
          (str "handler-meta " kind)))
    (testing "the ex-data names the read to use instead — the message is not the
              only place the caller can find the door"
      (let [read-for (fn [kind]
                       (try (rf/registrations {:source :store :kind kind}) nil
                            (catch #?(:clj clojure.lang.ExceptionInfo
                                      :cljs cljs.core/ExceptionInfo) e
                              (:read (ex-data e)))))]
        (is (re-find #"flows-snapshot" (str (read-for :flow))))
        (is (re-find #"frame-ids" (str (read-for :frame))))))))

(deftest unknown-kinds-fail-loud
  (testing "a kind outside the queryable set throws the registrar's own
            catalogued id, with `where` naming the QUERY fn rather than
            register!"
    (is (= :rf.error/unknown-registry-kind
           (err-id #(rf/registrations {:source :store :kind :rf2-kuky/not-a-kind}))))
    (is (= :rf.error/unknown-registry-kind
           (err-id #(rf/handler-meta {:source :store
                                      :kind   :rf2-kuky/not-a-kind
                                      :id     :x}))))
    (testing "the frame form validates the kind too — before the frame resolves"
      (is (= :rf.error/unknown-registry-kind
             (err-id #(rf/registrations {:frame :nope/missing
                                         :kind  :rf2-kuky/not-a-kind})))
          "the kind is refused ahead of :rf.error/frame-no-generation"))))

;; ===========================================================================
;; 3. THE DERIVED MACHINE KINDS TAKE THE STORE FORM
;; ===========================================================================

(deftest machine-kinds-read-through-the-store-form
  (testing ":machine-guard / :machine-action are NOT registrar kinds — their
            handler-meta is DERIVED from the enclosing machine's :event
            registration spec — but they are addressed through the same
            {:source :store …} query, so the grammar has no exception"
    (is (nil? (rf/handler-meta {:source :store
                                :kind   :machine-guard
                                :id     [:auth/login :form-valid?]}))
        "no such machine: nil, not a throw — the kind is queryable")
    (is (nil? (rf/handler-meta {:source :store
                                :kind   :machine-action
                                :id     [:auth/login :submit]})))
    (testing "registrations answers {} for them — there is no side-table to
              enumerate (Spec 005 §:machine-guard / :machine-action
              handler-meta surfaces); the documented empty is not the
              reserved-slot empty this bead refused, because the metadata IS
              reachable, one id at a time, through handler-meta"
      (is (= {} (rf/registrations {:source :store :kind :machine-guard})))
      (is (= {} (rf/registrations {:source :store :kind :machine-action}))))))

;; ===========================================================================
;; 4. THE PRIVATE ATOM NO LONGER NEEDS READING
;; ===========================================================================

(deftest the-public-store-read-matches-the-private-atom
  (testing "the {:source :store} read answers exactly what a tool used to reach
            for the private re-frame.registrar/kind->id->metadata atom to get.
            This is the assertion that retires Xray's host_registry.cljs."
    (let [inspector (seat-three-sources!)]
      (rf.live-frame/call-with-frame-resolution
        inspector
        (fn []
          (is (= (get @rf.registrar/kind->id->metadata :sub {})
                 (rf/registrations {:source :store :kind :sub})))
          (is (= (get-in @rf.registrar/kind->id->metadata [:sub shared-id])
                 (rf/handler-meta {:source :store :kind :sub :id shared-id}))))))))
