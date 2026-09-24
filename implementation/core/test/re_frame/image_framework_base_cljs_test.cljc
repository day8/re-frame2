(ns re-frame.image-framework-base-cljs-test
  "The FRAMEWORK BASE beneath an explicit `:images`
  composition, pinned over explicit synthetic descriptor pools.

  An explicit image selects by `:rf.provenance/ns`, and the framework's own
  feature handlers register through the fn-alias path with NO provenance, so
  no `:select-ns` can reach `:rf.route/navigate`,
  `:rf.http/managed`, `:rf/resource` or core's own `:rf/time-ms` — and none of
  them is a protected standard. So assembly layers every
  loaded framework-owned registration (nil provenance, id under the reserved
  `:rf` root, plus the `:route/link` view) BENEATH the app images under the
  reserved pseudo-image id `:rf/framework`, minus the protected standards.

  Pinned here: membership (including the `:rfx` / `:rf2.*` prefix trap and the
  one id outside the root), the isolation control (an app's nil-provenance id
  outside the root stays invisible), later-image shadowing reported against
  `:rf/framework`, the lone-anonymous-image edge, the replaceable-default
  carrier union on the inline-override path, standards untouched, the
  default image untouched, `:rf.gen/images` untouched, and caching.

  The live-store, producer-derived half (the real routing / http / resources /
  machines / core registrations) is
  `re-frame.image-framework-base-features-cljs-test`.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]))

;; The standard registry and the generation cache are process state. Clear both
;; per case, and put the standards back afterwards so a later namespace still
;; sees the ones the framework registered at load.
(use-fixtures :each
  (fn [t]
    (let [standards @rf.image-assembly/standard-registry]
      (rf.image-assembly/clear-standards!)
      (rf.image-assembly/clear-generation-cache!)
      (try
        (t)
        (finally
          (rf.image-assembly/clear-standards!)
          (doseq [[[kind id] d] standards]
            (rf.image-assembly/register-standard! kind id d))
          (rf.image-assembly/clear-generation-cache!))))))

(defn- fw-desc
  "A descriptor as the framework's fn-alias registration path records it: NO
  `:rf.provenance/ns`."
  ([kind id impl] (fw-desc kind id impl nil))
  ([kind id impl extra]
   (merge {:kind kind :id id :handler-fn impl} extra)))

(defn- reg-desc
  "A descriptor authored in `provenance-ns` (the public `reg-*` macro path)."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns :kind kind :id id :handler-fn impl})

(defn- err-id
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- handler-of [gen kind id]
  (:handler-fn (rf.image-assembly/resolve-descriptor gen kind id)))

(def ^:private app-image
  (rf.image/image {:id :app/main :select-ns {:include ["app.**"]}}))

(def ^:private pool
  [(reg-desc "app.core" :event :app/boot ::app-boot)
   ;; framework-owned: nil provenance, id under the reserved :rf root
   (fw-desc :event :rf.route/navigate ::navigate)
   (fw-desc :cofx  :rf/time-ms        ::time-ms)
   ;; the one framework id outside the root — as a :view only
   (fw-desc :view  :route/link        ::route-link)
   ;; NOT framework-owned
   (fw-desc :event :app/programmatic  ::app-programmatic)
   (fw-desc :fx    :rfx/effect        ::rfx)
   (fw-desc :fx    :rf2.tools/effect  ::rf2-tools)
   (fw-desc :event :route/link        ::event-route-link)
   (reg-desc "other.lib" :fx :rf.lib/provenanced ::provenanced)])

;; ===========================================================================
;; 1. Membership — what the base carries into an explicit composition
;; ===========================================================================

(deftest explicit-composition-resolves-the-framework-base
  (let [gen (rf.image-assembly/assemble [app-image] pool)]
    (testing "framework-owned registrations resolve in an explicit generation"
      (is (= ::navigate   (handler-of gen :event :rf.route/navigate)) ":rf.<x>/* id")
      (is (= ::time-ms    (handler-of gen :cofx :rf/time-ms)) ":rf/* id")
      (is (= ::route-link (handler-of gen :view :route/link))
          "the one public framework id outside the reserved root"))
    (testing "the app's own selection is unaffected"
      (is (= ::app-boot (handler-of gen :event :app/boot))))
    (testing "isolation control: nothing outside framework ownership rides the base"
      (is (nil? (handler-of gen :event :app/programmatic))
          "an app's nil-provenance registration outside the root stays unselectable")
      (is (nil? (handler-of gen :fx :rfx/effect)) ":rfx is not the :rf root")
      (is (nil? (handler-of gen :fx :rf2.tools/effect)) ":rf2.* is not the :rf root")
      (is (nil? (handler-of gen :event :route/link)) ":route/link is framework-owned as a :view only")
      (is (nil? (handler-of gen :fx :rf.lib/provenanced))
          "a PROVENANCED reserved-root registration is ordinary namespace-authored
           code — reachable by selecting its namespace, never through the base"))
    (testing "the base is not an image: :rf.gen/images stays the user's composition"
      (is (= [app-image] (:rf.gen/images gen))))
    (testing "nothing overrode anything, so nothing is reported"
      (is (= [] (:rf.gen/shadows gen))))))

(deftest the-default-image-is-unchanged
  (let [gen (rf.image-assembly/assemble-default pool)]
    (is (= ::navigate (handler-of gen :event :rf.route/navigate)))
    (is (= ::app-programmatic (handler-of gen :event :app/programmatic))
        "the default image projects the WHOLE pool")
    (is (= [] (:rf.gen/shadows gen))
        "the default image has no base layer, so no :rf/framework shadow")))

;; ===========================================================================
;; 2. Later images shadow the base — reported against :rf/framework
;; ===========================================================================

(def ^:private stub-push (fn [_ctx _url] :stubbed))

(deftest a-later-image-shadows-a-framework-registration
  (let [pool    (conj pool (fw-desc :fx :rf.nav/push-url ::real-push))
        doubles (rf.image/image {:id :test/doubles
                                 :registrations {:reg-fx [[:rf.nav/push-url stub-push]]}})
        gen     (rf.image-assembly/assemble [app-image doubles] pool)]
    (is (= stub-push (handler-of gen :fx :rf.nav/push-url)) "the later image wins")
    (is (= [{:registration [:fx :rf.nav/push-url]
             :image        :rf/framework
             :shadowed-by  :test/doubles}]
           (:rf.gen/shadows gen))
        "the shadow names the reserved pseudo-image :rf/framework")))

(deftest a-provenanced-app-override-wins-over-the-framework-default
  (let [pool (conj pool
                   (fw-desc :event :rf.route/entry-denied ::fw-denied
                            {:rf/framework-default? true}))]
    (testing "with no override selected, the framework default resolves"
      (let [gen (rf.image-assembly/assemble [app-image] pool)]
        (is (= ::fw-denied (handler-of gen :event :rf.route/entry-denied)))
        (is (= [] (:rf.gen/shadows gen)))))
    (testing "an app image selecting its own registration wins, and the chain
              names the FINAL winner for every loser"
      (let [pool      (conj pool (reg-desc "app.auth" :event :rf.route/entry-denied ::app-denied))
            deny-stub (fn [{:keys [db]} _] {:db db})
            doubles   (rf.image/image {:id :test/doubles
                                       :registrations {:reg-event [[:rf.route/entry-denied deny-stub]]}})
            gen       (rf.image-assembly/assemble [app-image] pool)
            chained   (rf.image-assembly/assemble [app-image doubles] pool)]
        (is (= ::app-denied (handler-of gen :event :rf.route/entry-denied)))
        (is (= [{:registration [:event :rf.route/entry-denied]
                 :image        :rf/framework
                 :shadowed-by  :app/main}]
               (:rf.gen/shadows gen)))
        (is (= [{:registration [:event :rf.route/entry-denied]
                 :image        :rf/framework
                 :shadowed-by  :test/doubles}
                {:registration [:event :rf.route/entry-denied]
                 :image        :app/main
                 :shadowed-by  :test/doubles}]
               (:rf.gen/shadows chained)))))))

(deftest a-lone-anonymous-image-overrides-without-a-degenerate-report
  (testing "the ordinary stub idiom — one anonymous image inlining a framework
            effect — assembles, wins, and records no entry naming a nil image"
    (let [pool (conj pool (fw-desc :fx :rf.nav/push-url ::real-push))
          anon (rf.image/image {:registrations {:reg-fx [[:rf.nav/push-url stub-push]]}})
          gen  (rf.image-assembly/assemble [anon] pool)]
      (is (= stub-push (handler-of gen :fx :rf.nav/push-url)))
      (is (= ::navigate (handler-of gen :event :rf.route/navigate))
          "the rest of the base is still there")
      (is (= [] (:rf.gen/shadows gen))))))

;; ===========================================================================
;; 3. Replaceable-default carrier classification rides an INLINE override
;; ===========================================================================

(deftest an-inline-override-keeps-the-framework-default-carriers
  (let [fw-default (fw-desc :event :rf.route/entry-denied ::fw-denied
                            {:rf/framework-default? true
                             :sensitive             [[:requested-url]]})
        pool       (conj pool fw-default)
        handler    (fn [{:keys [db]} _] {:db db})]
    (testing "an inline override replaces BEHAVIOUR; the framework's own
              :sensitive carriers ride across, unioned with its own"
      (let [auth (rf.image/image {:id :app/auth
                                  :registrations {:reg-event [[:rf.route/entry-denied
                                                               {:sensitive [[:note]]}
                                                               handler]]}})
            gen  (rf.image-assembly/assemble [app-image auth] pool)
            d    (rf.image-assembly/resolve-descriptor gen :event :rf.route/entry-denied)]
        (is (= handler (:impl d)) "the override is the winner")
        (is (= [[:requested-url] [:note]] (:sensitive d)))))
    (testing "a provenanced override that already carries the union (retained at
              registration) passes through untouched — the identical descriptor"
      (let [app-d (assoc (reg-desc "app.auth" :event :rf.route/entry-denied ::app-denied)
                         :sensitive [[:requested-url]])
            gen   (rf.image-assembly/assemble [app-image] (conj pool app-d))]
        (is (identical? app-d (rf.image-assembly/resolve-descriptor
                                gen :event :rf.route/entry-denied)))))
    (testing "a shadowed base registration that is NOT a replaceable default
              carries nothing across"
      (let [pool    (conj pool (fw-desc :fx :rf.nav/push-url ::real-push
                                        {:sensitive [[:url]]}))
            doubles (rf.image/image {:id :test/doubles
                                     :registrations {:reg-fx [[:rf.nav/push-url stub-push]]}})
            gen     (rf.image-assembly/assemble [app-image doubles] pool)]
        (is (nil? (:sensitive (rf.image-assembly/resolve-descriptor
                                gen :fx :rf.nav/push-url))))))))

;; ===========================================================================
;; 4. Standards are untouched
;; ===========================================================================

(def ^:private std-set-db (fn [_ _] :standard))

(deftest standards-stay-protected-and-outside-the-base
  (rf.image-assembly/register-standard! :event :rf/set-db {:handler-fn std-set-db})
  (let [;; the framework dual-registers a standard into the ordinary registrar
        ;; too, so the pool carries its nil-provenance copy
        pool (conj pool (fw-desc :event :rf/set-db ::registrar-copy))]
    (testing "the registrar copy of a standard never enters the base — no
              spurious standard collision, and the standard itself resolves"
      (let [gen (rf.image-assembly/assemble [app-image] pool)]
        (is (= std-set-db (handler-of gen :event :rf/set-db)))
        (is (= [] (:rf.gen/shadows gen)))))
    (testing "an app image colliding with a standard fails loud"
      (is (= :rf.error/image-standard-replacement-forbidden
             (err-id #(rf.image-assembly/assemble
                        [app-image]
                        (conj pool (reg-desc "app.core" :event :rf/set-db ::app-set-db)))))))))

;; ===========================================================================
;; 5. Caching
;; ===========================================================================

(deftest an-unchanged-composition-returns-the-cached-generation
  (is (identical? (rf.image-assembly/assemble [app-image] pool)
                  (rf.image-assembly/assemble [app-image] pool))))
