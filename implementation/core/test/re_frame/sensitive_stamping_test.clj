(ns re-frame.sensitive-stamping-test
  "Runtime sensitivity stamping and classification auto-redaction tests.

  EP-0025: the event-payload `:sensitive?` stamp + redaction for a
  path-scoped handler is driven by the classified `:sensitive` app-db
  overlap (the per-frame elision registry, written by the commit-plane
  classification effects under `:source :effect`), NOT schema-attached
  `{:sensitive? true}` slot props (which no longer feed that registry) and no
  longer a frame annotation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

(defn- install-sensitive!
  "Seed the frame's sensitive app-db classification via the EP-0025
  commit-plane classification effect path (`rf.elision/apply-classification-
  effects`, `:source :effect`) — the same registry write a `reg-event`
  returning `:sensitive` performs."
  [frame-id paths]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive (mapv vec paths)}))))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

(deftest plain-handler-no-sensitive-flag
  (rf/reg-event :sensitive/plain
                   (fn [{:keys [db]} _] {:db db}))
  (let [evs (record-traces #(rf/dispatch-sync [:sensitive/plain]))]
    (doseq [ev evs]
      (is (not (contains? ev :sensitive?))))))

(deftest handler-meta-sensitive-no-longer-stamps-events
  (testing "The handler-meta `:sensitive?` annotation has been removed.
            The `:sensitive?` stamp is now driven exclusively by the
            schema-derived overlap (see `schema-auto-redaction-*` tests
            below). A handler-meta `:sensitive?` value is preserved on
            the registrar's stored meta (registry is opaque) but is no
            longer consulted by the trace surface."
  (rf/reg-event :sensitive/cross-cutting
                   {:sensitive? true}   ;; stored, not consulted
                   (fn [{:keys [db]} _]
                     {:db (assoc db :ran? true)
                      :fx [[:sensitive/noop nil]]}))
  (rf/reg-fx :sensitive/noop (fn [_ _] nil))
  (let [evs (record-traces #(rf/dispatch-sync
                               [:sensitive/cross-cutting {:token "secret"}]))]
    (doseq [op #{:rf.event/dispatched :rf.event/run-start :rf.event/run-end :rf.event/db-changed :rf.fx/do-fx}]
      (let [matches (filterv #(= op (:operation %)) evs)]
        (is (seq matches) (str op " was emitted"))
        (doseq [ev matches]
          (is (not (true? (:sensitive? ev)))
              (str op " is NOT stamped sensitive (handler-meta annotation removed)"))))))))

(deftest frame-class-auto-redaction-for-path-scoped-handler
  (testing "A frame-sensitive app-db path installs redaction without
            user-written redaction interceptors; the handler still sees the
            raw payload."
    (install-sensitive! :rf/default [[:auth :password]])
    (let [seen (atom nil)]
      (rf/reg-event :auth/login
                       {:interceptors [[:rf.interceptor/path [:auth]]]}
                       (fn [{:keys [db]} [_ payload]]
                         (reset! seen payload)
                         {:db (assoc db :last-login payload)}))
      (let [evs (record-traces
                  #(rf/dispatch-sync
                     [:auth/login {:username "ada" :password "shh"}]))
            [run-start]  (filterv #(= :rf.event/run-start (:operation %)) evs)
            [db-changed] (events-of evs :rf.event/db-changed)]
        (is (= {:username "ada" :password "shh"} @seen)
            "handler body receives the unredacted event payload")
        (is (true? (:sensitive? run-start))
            "frame-sensitive handler scope is stamped")
        (is (= :rf/redacted
               (get-in run-start [:tags :rf.event/v 1 :password])))
        (is (= :rf/redacted
               (get-in db-changed [:tags :rf.event/v 1 :password])))
        (is (= "ada" (get-in db-changed [:tags :rf.event/v 1 :username])))))))

(deftest frame-class-auto-redaction-stamps-handler-exception
  (install-sensitive! :rf/default [[:auth :password]])
  (rf/reg-event :auth/throws
                   {:interceptors [[:rf.interceptor/path [:auth]]]}
                   (fn [{:keys [db]} _] {:db (throw (ex-info "boom" {}))}))
  (let [evs (record-traces
              #(rf/dispatch-sync
                 [:auth/throws {:password "shh"}]))
        [err] (events-of evs :rf.error/handler-exception)]
    (is (true? (:sensitive? err)))
    (is (= :rf/redacted
           (get-in err [:tags :event 1 :password])))))

(deftest frame-class-auto-redaction-does-not-affect-unrelated-paths
  (install-sensitive! :rf/default [[:auth :password]])
  (rf/reg-event :profile/save
                   {:interceptors [[:rf.interceptor/path [:profile]]]}
                   (fn [{:keys [db]} [_ payload]]
                     {:db (assoc db :saved payload)}))
  (let [evs (record-traces
              #(rf/dispatch-sync
                 [:profile/save {:password "not-auth"}]))
        [db-changed] (events-of evs :rf.event/db-changed)]
    (is (not (true? (:sensitive? db-changed))))
    (is (= "not-auth"
           (get-in db-changed [:tags :rf.event/v 1 :password])))))

(deftest trace-buffer-sensitive-filter
  (testing "Trace-buffer `:sensitive?` filter operates on the trace event's
            top-level `:sensitive?` field. The stamp is frame-classification
            -derived: a frame-sensitive app-db path drives it (EP-0015 §8)."
  (rf/clear-trace-buffer! :rf/default)
  (rf/configure! {:trace-buffer {:events-retained 100}})
  (install-sensitive! :rf/default [[:auth :password]])
  (rf/reg-event :sensitive/buf
                   {:interceptors [[:rf.interceptor/path [:auth]]]}
                   (fn [{auth :db} _] {:db auth}))
  (rf/reg-event :plain/buf
                   (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:sensitive/buf {:password "x"}])
  (rf/dispatch-sync [:plain/buf])
  (let [all   (rf/trace-buffer :rf/default {:flat true})
        sens  (rf/trace-buffer :rf/default {:flat true :sensitive? true})
        plain (rf/trace-buffer :rf/default {:flat true :sensitive? false})]
    (is (pos? (count sens)) "schema-driven sensitive events present in the buffer")
    (is (pos? (count plain)))
    (is (= (count all) (+ (count sens) (count plain))))
    (doseq [ev sens]  (is (true? (:sensitive? ev))))
    (doseq [ev plain] (is (not (true? (:sensitive? ev))))))))

(deftest sensitive-predicate
  (is (true? (rf/sensitive? {:sensitive? true})))
  (is (false? (rf/sensitive? {:sensitive? false})))
  (is (false? (rf/sensitive? {})))
  (is (false? (rf/sensitive? nil)))
  (is (identical? rf/sensitive? rf.privacy/sensitive?)))

(deftest sensitive-predicate-fails-closed-on-a-malformed-stamp
  ;; rf2-kuky.8 — the `:rf/trace-event` schema types `:sensitive?` as a
  ;; boolean, so a string / keyword / number stamp is a contract violation:
  ;; some producer has coerced the boolean into the wrong shape. The only
  ;; safe reading of a violation on THIS axis is the conservative one.
  ;;
  ;; This was `(true? (:sensitive? ev))`, which read every one of these as
  ;; NOT sensitive and forwarded the event — fail-OPEN in exactly the case
  ;; where the producer has already proved unreliable. The MCP wire
  ;; (`re-frame.mcp-base.sensitive/sensitive-stamp?`) had ruled the other
  ;; way; the framework predicate now matches it.
  (testing "a non-boolean truthy stamp counts as SENSITIVE"
    (is (true? (rf/sensitive? {:sensitive? "true"})))
    (is (true? (rf/sensitive? {:sensitive? "false"}))
        "even a string that LOOKS false — the shape is what is wrong")
    (is (true? (rf/sensitive? {:sensitive? :yes})))
    (is (true? (rf/sensitive? {:sensitive? 1})))
    (is (true? (rf/sensitive? {:sensitive? 0}))
        "0 is truthy in Clojure — a numeric stamp is malformed either way")
    (is (true? (rf/sensitive? {:sensitive? ["any" "truthy"]}))))

  (testing "only the two genuinely falsy values, and absence, pass"
    (is (false? (rf/sensitive? {:sensitive? false})))
    (is (false? (rf/sensitive? {:sensitive? nil})))
    (is (false? (rf/sensitive? {:other :key}))))

  (testing "non-map inputs are tolerated and non-sensitive"
    (is (false? (rf/sensitive? nil)))
    (is (false? (rf/sensitive? "anything")))
    (is (false? (rf/sensitive? [:sensitive? true])))
    (is (false? (rf/sensitive? 42))))

  (testing "the predicate returns a BOOLEAN, never the stamp value — callers
            compose it with `and` / `some` and a leaked payload would be a
            second way to ship the very value being classified"
    (is (boolean? (rf/sensitive? {:sensitive? "super-secret-token"})))
    (is (boolean? (rf/sensitive? nil)))))
