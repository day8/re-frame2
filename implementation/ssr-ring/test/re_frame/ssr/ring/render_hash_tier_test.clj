(ns re-frame.ssr.ring.render-hash-tier-test
  "rf2-q1b96 — the SERVER end of Spec 011's tier rule for the render-hash
  channel.

  rf2-2rtt6.91 (PR #7510) closed the CLIENT half: a Freehand root stopped
  emitting `:rf/render-hash`, because the only tree an adoption-tier root can
  offer a hash is the unresolved root form `[<component> {props}]`, whose
  canonical EDN `[#fn[] {props}]` is a constant. Spec 011 §Hydration-mismatch
  detection now states the rule for both ends: a root that verifies by
  React-native adoption MUST NOT carry `:rf/render-hash` in its payload nor
  `data-rf-render-hash` on its root element.

  `ssr-ring` hashed whatever `:root-view` resolved to, for any root shape.
  These rows pin the repair and, just as importantly, the MEASUREMENT that
  justifies it — the constants stay reproducible here rather than surviving
  only in a commit message.

  **The discriminator is structural, not brand-based.** The server cannot see
  which substrate will hydrate its markup and does not need to: it asks
  whether a hashable data render-tree is PRESENT at the root. That answers the
  tier question wherever the tier question has an answer, and it binds the
  hiccup tier identically — which is why the omission is silent rather than a
  thrown error. `[(rf/view :app/root)]` and a Freehand `[app {}]` are the same
  shape; nothing on this side can tell them apart."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.test-support :as ts]))

(use-fixtures :each ts/reset-runtime)

;; ---- extraction helpers ---------------------------------------------------

(defn- payload-edn-of
  "The `__rf_payload` script body of a rendered document string."
  [body]
  (second (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)))

(defn- payload-render-hash
  "The payload's `:rf/render-hash`, or nil when the key is absent. Matches the
  `#:rf{…}` namespace-map shorthand `pr-str` emits too."
  [body]
  (when-let [edn (payload-edn-of body)]
    (second (re-find #":(?:rf/)?render-hash \"([0-9a-f]{8})\"" edn))))

(defn- payload-head-hash [body]
  (when-let [edn (payload-edn-of body)]
    (second (re-find #":(?:rf/)?head-hash \"([0-9a-f]{8})\"" edn))))

(defn- wire-render-hash [body]
  (second (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\"" body)))

(defn- drain-stream
  "Read a streaming Ring body (an InputStream) to a String."
  [body]
  (if (string? body)
    body
    (with-open [in body]
      (slurp in))))

(defn- get-request [] {:uri "/" :request-method :get})

;; ---- the app under test ---------------------------------------------------

(defn- register-app! []
  (rf/reg-event :rf.test.q1b96/init
    {:platforms #{:server}}
    (fn [_ _] {:db {:heading "Tier"}}))
  (rf/reg-sub :q1b96/heading (fn [db _] (:heading db)))
  (rf/reg-view* :q1b96/root
    (fn []
      (let [h (rf/subscribe-once [:q1b96/heading])]
        [:main.page [:h1 h] [:p "body"]]))))

;; ===========================================================================
;; unresolved-root-form? — the discriminator itself
;; ===========================================================================

(defn- a-component [_props] [:div "a"])
(defn- b-component [_props] [:section [:h2 "b"] [:p "different shape entirely"]])

(deftest unresolved-root-form-recognises-callable-heads
  (testing "rf2-q1b96: a vector whose head is a callable — a raw fn, a Var
            reference, or `(rf/view :id)` — is the UNRESOLVED root form"
    (register-app!)
    (is (true? (lifecycle/unresolved-root-form? [(rf/view :q1b96/root)]))
        "a registered-view reference is a reference, not a tree")
    (is (true? (lifecycle/unresolved-root-form? [#'a-component]))
        "a Var head too — a Var is `ifn?` but NOT `fn?` on the JVM, so the
         test cannot be `fn?` (rf2-wtd8z finding 2 made the same correction
         in the emitter)")
    (is (true? (lifecycle/unresolved-root-form? [a-component {}]))
        "the adoption tier's `[<component> {props}]` shape")))

(deftest unresolved-root-form-excludes-real-render-trees
  (testing "rf2-q1b96: keyword heads are DOM / custom elements on every host
            (rf2-j81hs), so a keyword-headed vector is a real render tree —
            and so is anything that is not a callable-headed vector"
    (is (false? (lifecycle/unresolved-root-form? [:div.page [:h1 "x"]]))
        "an ordinary DOM root")
    (is (false? (lifecycle/unresolved-root-form? [:<> [:div "a"] [:div "b"]]))
        "a fragment root — Spec 011 threads the marker through it onto the
         first DOM child, so it must stay hashable")
    (is (false? (lifecycle/unresolved-root-form? (list [:div "x"])))
        "a lazy-seq / list root is threaded through likewise (rf2-a73idu)")
    (is (false? (lifecycle/unresolved-root-form? []))
        "an empty vector has no head to be callable")
    (is (false? (lifecycle/unresolved-root-form? "just text")))
    (is (false? (lifecycle/unresolved-root-form? nil)))))

;; ===========================================================================
;; The measurement — why a degenerate value is worse than an absent one
;; ===========================================================================

(deftest the-hash-an-unresolved-root-would-have-had-is-a-constant
  (testing "rf2-q1b96 / rf2-2rtt6.91: hashing an unresolved root yields ONE
            constant per arity, identical for every application — because
            `render-tree-hash` is a pure structural walk that never expands a
            callable head, and every raw fn serialises to the identity-free
            token `#fn[]` (a ruled requirement: no fn `.toString` is stable
            across JVM and CLJS — rf2-jsa2ml). Kept measured here so the
            figures behind the repair stay reproducible."
    (register-app!)
    (rf/reg-view* :q1b96/other (fn [] [:aside "nothing like the other one"]))

    ;; Arity 1 — `[<callable>]`, the shape `:root-view [(rf/view :id)]` takes.
    (is (= "f1d63f7e"
           (ssr/render-tree-hash [(rf/view :q1b96/root)])
           (ssr/render-tree-hash [(rf/view :q1b96/other)]))
        "two unrelated views hash IDENTICALLY as [#fn[]]")

    ;; Arity 2 — `[<component> {props}]`, the adoption tier's root form. This
    ;; is the same 83b865f8 rf2-2rtt6.91 measured on the Freehand bench.
    (is (= "83b865f8"
           (ssr/render-tree-hash [a-component {}])
           (ssr/render-tree-hash [b-component {}]))
        "two entirely different screens hash IDENTICALLY as [#fn[] {}] — a
         client comparing that value would find the server agreed with it
         about two different pages")

    ;; The non-vacuity control: a real DOM-rooted tree does discriminate.
    (is (not= (ssr/render-tree-hash [:div "a"])
              (ssr/render-tree-hash [:div "b"]))
        "a genuine render tree hashes differently for different content")))

(deftest render-document-hash-omits-the-unresolved-root-form
  (testing "rf2-q1b96: `render-document-hash` returns nil for the unresolved
            root form — the same OMIT-rather-than-ship shape `render-head-hash`
            already uses for a head the client cannot reconstruct"
    (register-app!)
    (is (nil? (lifecycle/render-document-hash [(rf/view :q1b96/root)])))
    (is (nil? (lifecycle/render-document-hash [a-component {}])))
    (is (some? (lifecycle/render-document-hash ((rf/view :q1b96/root))))
        "the RESOLVED tree still hashes — the channel is not disabled, it is
         conditioned on a hashable tree existing")))

;; ===========================================================================
;; ssr-handler — the wire
;; ===========================================================================

(deftest an-unresolved-root-view-ships-no-render-hash
  (testing "rf2-q1b96: `:root-view [(rf/view :id)]` → NO data-rf-render-hash
            on the root element and NO :rf/render-hash key in the payload,
            even under the default `:emit-hash? true`"
    (register-app!)
    (let [handler (ssr-ring/ssr-handler
                    {:initial-events [[:rf.test.q1b96/init]]
                     :root-view      [(rf/view :q1b96/root)]
                     :payload        :rf.ssr.payload/whole-app-db})
          body    (:body (handler (get-request)))]
      (is (str/includes? body "<h1>Tier</h1>")
          "the page still renders — only the hash channel is affected")
      (is (nil? (wire-render-hash body))
          "no data-rf-render-hash marker")
      (is (not (str/includes? body "render-hash"))
          "the payload omits the KEY rather than stamping a nil: Spec-Schemas
           types the slot `{:optional true} :string`, not `[:maybe :string]`,
           so a present-and-nil key is not a legal spelling of absence")
      (is (some? (payload-head-hash body))
          "the SEPARATE head channel is untouched — the head model is
           client-reconstructible on every tier via `active-head`, so it is
           never degenerate and never omitted for tier reasons")
      (is (str/includes? body "data-rf-head-hash")
          "and its wire marker rides too"))))

(deftest an-adoption-tier-root-form-ships-no-render-hash
  (testing "rf2-q1b96: the `[<component> {props}]` root shape — what a
            compiled `re-frame.ui`, native UIx, or Freehand root can only ever
            be — carries no hash on either channel. This is the trap the bead
            was filed for: nothing serves such a root through ssr-ring today,
            so the first server arm that does must not re-create the fail-open
            gate rf2-2rtt6.91 closed on the client."
    (rf/reg-event :rf.test.q1b96/init {:platforms #{:server}} (fn [_ _] {:db {}}))
    (let [handler (ssr-ring/ssr-handler
                    {:initial-events [[:rf.test.q1b96/init]]
                     :root-view      [a-component {}]
                     :payload        :rf.ssr.payload/whole-app-db})
          body    (:body (handler (get-request)))]
      (is (str/includes? body "<div>a</div>") "the component still renders")
      (is (nil? (wire-render-hash body)))
      (is (not (str/includes? body "render-hash"))))))

(deftest a-resolving-root-view-keeps-the-hash-channel
  (testing "rf2-q1b96: the repair conditions the channel, it does not remove
            it. A `:root-view` that RESOLVES to a DOM-rooted tree still
            carries the hash — and that hash EQUALS the one the documented
            client `:render-tree-fn #((rf/view :id))` computes, which the
            unresolved form never could."
    (register-app!)
    (let [handler (ssr-ring/ssr-handler
                    {:initial-events [[:rf.test.q1b96/init]]
                     ;; Note the OUTER call, mirroring the client's `#(…)`.
                     :root-view      (fn [] ((rf/view :q1b96/root)))
                     :payload        :rf.ssr.payload/whole-app-db})
          body    (:body (handler (get-request)))
          wire    (wire-render-hash body)
          shipped (payload-render-hash body)]
      (is (some? wire) "the marker is stamped on the root element")
      (is (some? shipped) "the payload carries :rf/render-hash")
      (is (= wire shipped) "wire marker == payload key (one canonical hash)")
      (is (not= "f1d63f7e" shipped)
          "and it is not the unresolved-root constant"))))

(deftest the-surviving-hash-matches-the-documented-client-tree
  (testing "rf2-q1b96: the load-bearing equality. Server `:root-view
            (fn [] ((rf/view :id)))` hashes the same tree the client's
            `:render-tree-fn #((rf/view :id))` does. The unresolved server
            form could never match it — it hashes `[#fn[]]` while the client
            hashes the tree — so before this repair the documented pairing
            mismatched on EVERY page while emitting byte-identical HTML."
    (register-app!)
    (let [fid (keyword "rf.frame" (str (gensym "q1b96")))]
      (rf/make-frame {:id fid :platform :server
                      :initial-events [[:rf.test.q1b96/init]]})
      (try
        (rf/with-frame fid
          (let [server-resolved   (lifecycle/resolve-root-view
                                    (fn [] ((rf/view :q1b96/root))))
                server-unresolved (lifecycle/resolve-root-view
                                    [(rf/view :q1b96/root)])
                client-tree       ((rf/view :q1b96/root))]
            (is (= (lifecycle/render-document-hash server-resolved)
                   (ssr/render-tree-hash client-tree))
                "resolving server root == client `#((rf/view :id))` hash")
            (is (nil? (lifecycle/render-document-hash server-unresolved))
                "the unresolved form now carries nothing rather than a
                 never-matching constant")
            (is (= (ssr/render-to-string server-resolved {})
                   (ssr/render-to-string server-unresolved {}))
                "both spellings emit byte-identical HTML — the choice is
                 invisible on the page and decisive on the hash channel,
                 which is why `resolve-root-view`'s docstring now says so")))
        (finally (rf/destroy-frame! fid))))))

;; ===========================================================================
;; stream-handler — the same rule on the chunked path
;; ===========================================================================

(deftest streaming-unresolved-root-view-ships-no-render-hash
  (testing "rf2-q1b96: `stream-handler` reaches the same answer — no
            data-rf-render-hash on the streamed #app root and no
            :rf/render-hash in the final payload. The streaming prefix stamps
            only from `:render-hash` and never recomputes, so a nil hash is
            enough there; only the non-streaming call site also had to drop
            `:emit-hash?` (`render-to-string` reads a true `:emit-hash?` with
            no supplied hash as 'compute it yourself' — rf2-atmvj)."
    (register-app!)
    (let [handler (ssr-ring/stream-handler
                    {:initial-events [[:rf.test.q1b96/init]]
                     :root-view      [(rf/view :q1b96/root)]
                     :payload        :rf.ssr.payload/whole-app-db})
          body    (drain-stream (:body (handler (get-request))))]
      (is (str/includes? body "<h1>Tier</h1>") "the shell still streams")
      (is (nil? (wire-render-hash body)))
      (is (nil? (payload-render-hash body)))
      (is (some? (payload-head-hash body))
          "the separate head channel survives on the streamed payload too"))))
