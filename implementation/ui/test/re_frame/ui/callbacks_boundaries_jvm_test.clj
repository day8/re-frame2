(ns re-frame.ui.callbacks-boundaries-jvm-test
  "rf2-vxgfnd.95.3 — the S3 explicit-callback + interop-boundary forms on the
  JVM Tier-1 structural host (Spec 004 §The JVM structural subset):

    error-boundary  a CLIENT recovery mechanism — the JVM/SSR renders the
                    guarded CHILD transparently (server failure policy per 011),
                    never the fallback.
    client-only     the JVM/SSR renders the deterministic, capability-free
                    FALLBACK wrapped in the `:rf.ui/boundary :client-only`
                    marker (§004B); the browser-only client subtree — foreign
                    content and all — never reaches the JVM tree (no
                    jvm-host-op).
    C-13a           an ordinary fn prop between INTERNAL views is a legal opaque
                    value — the view-boundary records it as `{:rf.ui/opaque
                    :fn}`, identity-compared, never invoked by the framework.

  The client-side committed-callback behaviour (ui/event vector-or-nil, ui/
  handler imperative, per-site stable identity, error-boundary render/commit/
  effect phases + reset, client-only activation) rides the DOM suite
  `callbacks_boundaries_dom_cljs_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview error-boundary client-only]]
            [re-frame.ui.test :as uit]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- all-nodes [t]
  (tree-seq map? (fn [n] (filter map? (:children n))) t))

(defn- boundary-node [t marker]
  (some #(when (= marker (:rf.ui/boundary %)) %) (all-nodes t)))

(defn- view-boundary-node [t view-id]
  (some #(when (= view-id (:view-id %)) %) (all-nodes t)))

(defn- all-strings [t]
  (mapcat (fn [n] (filter string? (:children n))) (all-nodes t)))

;; A foreign React component placeholder: resolves to a non-view var, so the
;; analyzer classifies `[foreign-widget …]` as :foreign. On the JVM a rendered
;; foreign raises :rf.error/jvm-host-op — client-only must keep it off the tree.
(def foreign-widget "a foreign React component placeholder")

;; ---------------------------------------------------------------------------
;; Views
;; ---------------------------------------------------------------------------

(defview error-fallback
  "The fallback view (never rendered on the JVM — error-boundary is a client
  recovery mechanism; the JVM renders the child)."
  [{:keys [error]}]
  [:div.error (str "failed: " error)])

(defview guarded
  []
  (error-boundary {:fallback error-fallback :reset-key :k
                   :on-error [:err/log]}
                  [:div.guard "SAFE-CONTENT"]))

(defview only-on-client
  []
  (client-only {:fallback [:div.skeleton "SERVER-FALLBACK"]}
               [foreign-widget {:mode "CLIENT-ONLY-CONTENT"}]))

(defview leaf [{:keys [cb]}]
  [:span.leaf "leaf"])

(defview passes-fn-prop
  "C-13a: an ordinary fn prop flows to an INTERNAL view as an opaque value."
  []
  [leaf {:cb (fn [x] (inc x))}])

;; ---------------------------------------------------------------------------
;; error-boundary — transparent child on the JVM (server failure policy)
;; ---------------------------------------------------------------------------

(deftest error-boundary-renders-the-child-transparently
  (let [t (uit/render [guarded])]
    (is (= "SAFE-CONTENT" (uit/text (some #(when (= :div (:tag %)) %) (all-nodes t))))
        "the JVM/SSR renders the GUARDED CHILD, not the fallback")
    (is (nil? (boundary-node t :error-boundary))
        "no error-boundary marker on the JVM tree — it is a client mechanism")
    (is (not-any? #(re-find #"^failed: " %) (all-strings t))
        "the fallback view never renders on the JVM")))

;; ---------------------------------------------------------------------------
;; client-only — the capability-free fallback + boundary marker; the browser-only
;; subtree (foreign and all) never reaches the JVM
;; ---------------------------------------------------------------------------

(deftest client-only-renders-the-fallback-with-the-boundary-marker
  (let [t (uit/render [only-on-client])
        b (boundary-node t :client-only)]
    (is (some? b) "the JVM emits the `:rf.ui/boundary :client-only` fallback node")
    (is (= "SERVER-FALLBACK" (uit/text (some #(when (= :div (:tag %)) %) (all-nodes t))))
        "the deterministic capability-free fallback renders on the JVM/SSR")
    (testing "the browser-only client subtree — foreign content and all — is absent"
      ;; (uit/render [only-on-client]) not throwing :rf.error/jvm-host-op already
      ;; proves the FOREIGN client subtree never emitted; and no client content
      ;; leaks onto the JVM tree.
      (is (not-any? #(= "CLIENT-ONLY-CONTENT" %) (all-strings t))
          "no client-only content leaks into the JVM tree"))))

;; ---------------------------------------------------------------------------
;; C-13a — an internal-view fn prop is a legal opaque value
;; ---------------------------------------------------------------------------

(deftest internal-fn-prop-is-opaque-on-the-view-boundary
  (let [t  (uit/render [passes-fn-prop])
        vb (view-boundary-node t :re-frame.ui.callbacks-boundaries-jvm-test/leaf)]
    (is (some? vb) "the internal child renders as a view-boundary node")
    (is (= {:rf.ui/opaque :fn} (get-in vb [:props :cb]))
        "the fn prop is recorded as an OPAQUE value — never invoked, identity-compared")))
