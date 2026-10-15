(ns re-frame.reply-egress-projection-conformance-cljs-test
  "Reply trace summaries share the wire elider and fail closed on unresolved frames.
  Identity facts remain verbatim (Managed-Effects and Data-Classification)."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.reply :as rf.reply]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-id :reply-egress/main)

(def ^:private raw-token "bearer-SECRET-do-not-ship")

(def ^:private big-string
  ;; Exceeds the default size threshold as well as carrying a declaration.
  (apply str (repeat 40000 \x)))

(defn- mk-frame! []
  (rf/make-frame {:id frame-id})
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:token]]
                :large     [[:blob]]}))))

(def ^:private work-id
  [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1])

(defn- ok-reply []
  {:status               :ok
   :value                {:token raw-token :blob big-string :public {:count 3}}
   :rf.reply/work-id     work-id
   :rf.reply/work-kind   :resource
   :rf.reply/work-status :completed
   :rf.frame/id          frame-id
   :completed-at         1781078400456})

(def ^:private identity-keys
  [:status :rf.reply/work-id :rf.reply/work-kind :rf.reply/work-status :rf.frame/id :completed-at])

(defn- redacted? [value] (= rf.privacy/redacted-sentinel value))
(defn- large-marker? [value] (and (map? value) (contains? value :rf.size/large-elided)))

;; Include map keys and wrapped strings so a copied wire value cannot hide in a marker.
(defn- embeds? [data raw]
  (some #(and (string? %) (str/includes? % raw))
        (tree-seq coll? seq data)))

(deftest trace-summary-projects-wire-slots-through-the-shared-elider
  (mk-frame!)
  (let [reply   (assoc (ok-reply)
                       :error       {:kind :rf.http/http-5xx :token raw-token}
                       :correlation {:token raw-token}
                       :meta        {:blob big-string})
        summary (rf.reply/trace-summary reply {:frame frame-id})]
    (is (= [rf.privacy/redacted-sentinel
            {:kind :rf.http/http-5xx :token rf.privacy/redacted-sentinel}
            {:count 3} rf.privacy/redacted-sentinel]
           [(get-in summary [:value :token]) (:error summary)
            (get-in summary [:value :public]) (get-in summary [:correlation :token])]))
    (is (every? large-marker? [(get-in summary [:value :blob]) (get-in summary [:meta :blob])]))
    (is (= (select-keys reply identity-keys) (select-keys summary identity-keys)))
    (is (not-any? #(embeds? summary %) [raw-token big-string]))))

;; A live carried stamp must not override an explicit unresolved frame.
(deftest egress-frame-comes-from-the-explicit-opt-and-fails-closed-when-unresolved
  (mk-frame!)
  (let [unresolved (rf.reply/trace-summary (ok-reply) {:frame :reply-egress/ghost})]
    (is (= [rf.privacy/redacted-sentinel work-id]
           ((juxt :value :rf.reply/work-id) unresolved)))))

(deftest carried-frame-stamp-auto-resolves-into-the-egress-policy
  (mk-frame!)
  (binding [rf.frame/*current-frame* nil]
    (let [summary (rf.reply/trace-summary (ok-reply) nil)]
      (is (= [rf.privacy/redacted-sentinel {:count 3} (select-keys (ok-reply) identity-keys)]
             [(get-in summary [:value :token]) (get-in summary [:value :public])
              (select-keys summary identity-keys)])))
    (is (= [rf.privacy/redacted-sentinel :reply-egress/ghost]
           ((juxt :value :rf.frame/id)
            (rf.reply/trace-summary (assoc (ok-reply) :rf.frame/id :reply-egress/ghost) nil))))))

;; Explicit nil must not borrow policy from a live carried or ambient frame.
(deftest explicit-nil-frame-is-honoured-and-fails-closed
  (mk-frame!)
  (let [reply (assoc (ok-reply) :error {:token raw-token} :correlation {:token raw-token} :meta {:token raw-token})]
    (is (= (repeat 4 rf.privacy/redacted-sentinel)
           (map (rf.reply/trace-summary reply {:frame nil}) [:value :error :correlation :meta]))))
  (binding [rf.frame/*current-frame* frame-id]
    (is (redacted? (:value (rf.reply/trace-summary (ok-reply) {:frame nil})))))
  (is (= raw-token (get-in (rf.reply/trace-summary (ok-reply) {:frame nil :rf.egress/include-sensitive? true})
                           [:value :token])))
  (is (redacted? (:value (rf.reply/trace-summary (ok-reply) {:frame nil
                                                             :rf.egress/include-sensitive? true
                                                             :rf.privacy/force-redact-wire? true})))))
