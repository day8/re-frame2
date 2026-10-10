(ns re-frame.reply-egress-projection-conformance-cljs-test
  "Reply-envelope egress-projection conformance for
  `re-frame.reply/trace-summary`.

  Reply `:value`, `:error`, `:correlation` and `:meta` slots may carry
  family data classified sensitive or large. The summary projects each of
  those wire slots through the shared `elide-wire-value` walker and leaves
  the framework identity facts verbatim. The governing frame is the explicit
  `:frame` opt when present, otherwise the reply's carried `:rf.frame/id`;
  an unresolved frame, or an explicit `{:frame nil}`, fails closed.

  Core owns `project-egress` profiles, reply-target completion and the
  precedence of sensitive over large at a both-marked path.

  Canonical contract: `spec/015-Data-Classification.md` §`project-egress`
  + `spec/Managed-Effects.md` §Tracing (the data-only trace summary)."
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

(defn- tree-contains?
  "True iff any node reachable in `data`, map keys included, satisfies `pred`."
  [pred data]
  (cond
    (pred data)  true
    (map? data)  (boolean (some (fn [[k v]] (or (tree-contains? pred k) (tree-contains? pred v))) data))
    (coll? data) (boolean (some #(tree-contains? pred %) data))
    :else        false))

;; A leak may wrap the raw value rather than replace it, so these match any
;; string that embeds it.
(defn- embeds-raw-token? [data]
  (tree-contains? #(and (string? %) (str/includes? % raw-token)) data))

(defn- embeds-raw-blob? [data]
  (tree-contains? #(and (string? %) (str/includes? % big-string)) data))

(deftest trace-summary-projects-wire-slots-through-the-shared-elider
  (mk-frame!)
  (let [reply   (assoc (ok-reply) :correlation {:token raw-token} :meta {:blob big-string})
        summary (rf.reply/trace-summary reply {:frame frame-id})]
    (is (redacted? (get-in summary [:value :token])))
    (is (large-marker? (get-in summary [:value :blob])))
    (is (= {:count 3} (get-in summary [:value :public])) "an unmarked sibling rides through")
    (is (redacted? (get-in summary [:correlation :token])))
    (is (large-marker? (get-in summary [:meta :blob])))
    (is (= (select-keys reply identity-keys) (select-keys summary identity-keys))
        "identity facts ride verbatim")
    ;; Recursive, so a raw value kept under a sibling key or inside a marker fails.
    (is (not (embeds-raw-token? summary)))
    (is (not (embeds-raw-blob? summary)))))

;; The reply's own stamp names a live frame and the ambient frame is live too,
;; so a redacted slot here also shows the explicit frame wins over both.
(deftest egress-frame-comes-from-the-explicit-opt-and-fails-closed-when-unresolved
  (mk-frame!)
  (let [unresolved (rf.reply/trace-summary (ok-reply) {:frame :reply-egress/ghost})]
    (is (redacted? (:value unresolved)) "an unresolved :frame redacts the whole slot")
    (is (= work-id (:rf.reply/work-id unresolved)) "identity facts still ride verbatim")))

(deftest carried-frame-stamp-auto-resolves-into-the-egress-policy
  (mk-frame!)
  (binding [rf.frame/*current-frame* nil]
    (let [summary (rf.reply/trace-summary (ok-reply) nil)]
      (is (redacted? (get-in summary [:value :token])) "the carried frame's policy applies")
      (is (= {:count 3} (get-in summary [:value :public]))
          "per-leaf policy, not a whole-slot redaction")
      (is (= (select-keys (ok-reply) identity-keys) (select-keys summary identity-keys))
          "identity facts ride verbatim"))
    (is (= [rf.privacy/redacted-sentinel :reply-egress/ghost]
           ((juxt :value :rf.frame/id)
            (rf.reply/trace-summary (assoc (ok-reply) :rf.frame/id :reply-egress/ghost) nil)))
        "an unresolved carried stamp fails closed and still rides as identity")))

;; An explicit `{:frame nil}` says no frame governs the summary. Treating it as
;; an omitted key would let the carried stamp supply policy and ship raw values.
(deftest explicit-nil-frame-is-honoured-and-fails-closed
  (mk-frame!)
  (let [reply (assoc (ok-reply) :error {:token raw-token} :correlation {:token raw-token} :meta {:token raw-token})]
    (is (= (repeat 4 rf.privacy/redacted-sentinel)
           (map (rf.reply/trace-summary reply {:frame nil}) [:value :error :correlation :meta]))
        "every wire slot fails closed"))
  (binding [rf.frame/*current-frame* frame-id]
    (is (redacted? (:value (rf.reply/trace-summary (ok-reply) {:frame nil})))
        "explicit nil beats the ambient frame too"))
  (is (= raw-token (get-in (rf.reply/trace-summary (ok-reply) {:frame nil :rf.egress/include-sensitive? true})
                           [:value :token]))
      "the deliberate raw opt-out still applies")
  (is (redacted? (:value (rf.reply/trace-summary (ok-reply) {:frame nil
                                                             :rf.egress/include-sensitive? true
                                                             :rf.privacy/force-redact-wire? true})))
      "forced wire redaction wins over the raw opt-out"))
