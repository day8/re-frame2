(ns re-frame.error-projection-owner-scope-cljs-test
  "rf2-ifzi — the REGISTRATION-owned half of `rf/project-egress` must resolve
  the event's `:sensitive` / `:large` marks in the record OWNER's registration
  universe, not in whatever generation happens to be ambient at projection
  time.

  `project-egress` seeds the owner (an explicit `opts :frame`, else a
  recognised record's own `:frame`) into the leaf walker's opts — that governs
  the frame-POLICY walk. The event slot's first pass is different: it applies
  the dispatched handler's OWN registration classification
  (`:classification/redact-event-by-registration`), which resolves through
  `registrar/handler-meta` and therefore reads the AMBIENT generation. An
  image-local event declaration is invisible from outside its frame's
  resolution scope, so a DEFERRED projection — a recorder that retained an
  error record and projects it after `dispatch` returned, or any direct
  `project-egress` caller naming an explicit target — shipped a
  declared-sensitive password RAW under the off-box profile.

  These tests exercise the PUBLIC boundary (`rf/project-egress`) with the
  owning generation NOT bound; the passing control is the same projection run
  inside the owner's resolution scope. `.cljc` — runs under `clojure -M:test`
  (JVM) and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- login-image
  "An image whose ONLY registration is an inline `:reg-event` for
  `:ifzi/login` carrying `classification` metadata."
  [image-id classification]
  (rf.image/image {:id            image-id
                   :registrations {:reg-event [[:ifzi/login classification
                                                (fn [_cofx _ev] {})]]}}))

(defn- install-image-frame!
  "Seal `frame-id`'s generation from `image` through the REAL image-assembly
  path. The empty descriptor pool keeps the generation to the image's OWN
  inline registrations (no live source-store contamination)."
  [frame-id image]
  (rf.live-frame/make-frame {:id frame-id :images [image]} []))

(defn- error-record
  "The shape `error-emit` hands a corpus error listener, plus the documented
  `:rf.observe/error` discriminator a recorder adds before projecting."
  [frame]
  {:kind  :rf.observe/error
   :frame frame
   :event [:ifzi/login {:password "secret" :user "ann"}]})

(defn- projected
  "Project `record` through the PUBLIC boundary under the off-box profile and
  return its `:event` payload map."
  ([record] (projected record nil))
  ([record opts]
   (get-in (rf/project-egress
             record
             (merge {:rf.egress/profile :rf.egress/off-box-observability} opts))
           [:event 1])))

(deftest deferred-error-projection-uses-the-owners-registration-scope
  (testing "rf2-ifzi: an image-local event's :sensitive declaration redacts a
            DEFERRED projection — the owner comes from the record's own :frame
            and the owning generation is NOT ambient"
    (install-image-frame! :ifzi/owner (login-image :ifzi/image {:sensitive [[:password]]}))
    ;; Control: inside the owner's resolution scope the declaration has always
    ;; been visible. This is the arm that already passed.
    (is (= rf.privacy/redacted-sentinel
           (rf.live-frame/call-with-frame-resolution
             :ifzi/owner
             #(:password (projected (error-record :ifzi/owner)))))
        "control: the declaration redacts inside the owner's resolution scope")
    ;; The defect: the same record, projected after the dispatch that produced
    ;; it returned — no owning generation bound.
    (let [payload (projected (error-record :ifzi/owner))]
      (is (= rf.privacy/redacted-sentinel (:password payload))
          "the record's own :frame governs the REGISTRATION pass, so the
           image-local :sensitive declaration redacts the password")
      (is (= "ann" (:user payload))
          "an unclassified sibling in the same payload still rides raw"))))

(deftest explicit-target-frame-overrides-a-different-ambient-generation
  (testing "rf2-ifzi: an explicit `opts :frame` names the registration universe
            — a CONFLICTING same-id declaration in the ambient frame cannot
            answer for the target's"
    ;; Two frames, same event id, different declarations.
    (install-image-frame! :ifzi/owner (login-image :ifzi/image-a {:sensitive [[:password]]}))
    (install-image-frame! :ifzi/other (login-image :ifzi/image-b {}))
    ;; Ambient scope is the OTHER frame (no classification); the explicit
    ;; target is the owner.
    (is (= rf.privacy/redacted-sentinel
           (rf.live-frame/call-with-frame-resolution
             :ifzi/other
             #(:password (projected (dissoc (error-record :ifzi/owner) :frame)
                                    {:frame :ifzi/owner}))))
        "the explicit target's declaration wins over the ambient generation's")
    ;; And the reverse: targeting the UNclassified frame from inside the
    ;; classified one leaves the payload visible — the target is authoritative
    ;; in both directions, so this is not a redact-everything result.
    (is (= "secret"
           (rf.live-frame/call-with-frame-resolution
             :ifzi/owner
             #(:password (projected (dissoc (error-record :ifzi/other) :frame)
                                    {:frame :ifzi/other}))))
        "the target frame declares nothing, so the payload rides raw even
         though the AMBIENT frame declares it sensitive")))

(deftest global-registration-still-answers-for-a-frameless-projection
  (testing "rf2-ifzi: with no owner at all the registration pass is unchanged —
            a global declaration still redacts, and the frame-policy walk's
            fail-closed behaviour is untouched"
    (rf/reg-event :ifzi/global {:sensitive [[:password]]} (fn [_ _] {}))
    (let [payload (get-in (rf/project-egress
                            {:kind  :rf.observe/error
                             :frame :rf/default
                             :event [:ifzi/global {:password "secret" :user "ann"}]}
                            {:rf.egress/profile :rf.egress/off-box-observability})
                          [:event 1])]
      (is (= rf.privacy/redacted-sentinel (:password payload))
          "a globally-registered declaration redacts as before")
      (is (= "ann" (:user payload))))))
