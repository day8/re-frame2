(ns day8.re-frame2-xray.panels.routing-params-egress-cljs-test
  "The Routing panel's CURRENT ROUTE params are an EGRESS PROJECTION, not raw
  frame state (rf2-6j8gd).

  ## The defect these rows pin

  rf2-8nyi2 projected the slice's `:query` and scoped itself to the key it
  was filed against, recording the params residue in its own notes.
  `panels/routing.cljs` therefore still rendered the slice's `:params` with
  `pr-str` straight into
  `[:span {:data-testid \"rf-xray-routing-current-params\"}]`, with NO
  classification step between the observed frame's runtime-db and the DOM —
  and unlike the query span, the params span renders UNCONDITIONALLY, so a
  declared-sensitive path capture was on screen for every activation of the
  route that declared it.

  That is a missed EXPLICIT data-hygiene declaration. It is NOT a claim
  that arbitrary undeclared trace carriers form a security boundary, and
  nothing here scrubs anything the author did not declare — the
  `renders-undeclared-params-verbatim` row is what pins that.

  ## Why PARAMS were never the weaker axis — the point of row (0)

  A route declares `:sensitive` / `:large` PROJECTION-RELATIVE to its
  `{:query … :params …}` shape, and `re-frame.routing.classification`
  treats the two axes IDENTICALLY: `normalize-axis-paths` validates any
  concrete path without inspecting its head, and
  `apply-route-classification` re-roots every one of them the same way to
  `[:rf.runtime/routing :current …]`.

  The axes differ in one direction only, and it runs AGAINST the query: an
  unpromoted query key stays a STRING, so `:sensitive [[:query :token]]`
  can silently FAIL OPEN unless the route's `:query` schema promotes
  `:token` — which is why the sibling query test carries a
  `promotes-both-query-keys-to-keywords` control and why the framework
  emits a reg-route-time advisory for it. A PATH CAPTURE is always
  keyword-keyed (`re-frame.routing.match` keywords every capture name), so
  params CANNOT fail open that way, and the classification code's own
  advisory is deliberately query-axis-only.

  Row (0) measures exactly that: the route below declares NO `:params`
  schema at all, and the capture still arrives keyword-keyed. So the axis
  the panel was leaking is the axis whose declaration is the more reliable
  of the two.

  ## Why these rows assert on the RENDERED output

  The seam is the `rf-xray-routing-current-params` testid, and only the
  rendered text can fail on a panel that still leaks: a helper's return
  value can be correct while the span beside it prints the raw map. So
  every row here walks the panel's hiccup and reads the span's text.

  ## Why the navigation is real

  `rf/reg-route` + a real `:rf.route/handle-url-change` is what makes the
  classification exist at all — it is ACTIVATION that re-roots the
  projection-relative paths into the frame's elision registry. A
  hand-typed slice injected through the test-override seam would carry no
  registry, and would pass against the unfixed panel."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [day8.re-frame2-xray.panels.routing :as routing]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers (same shape as routing_query_egress_cljs_test) ------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- node-text [node]
  (->> (hiccup-seq node)
       (filter string?)
       (apply str)))

;; ---- fixtures -----------------------------------------------------------

(def ^:private secret "secret-abc123")
(def ^:private sibling "posts")

(defn- classified-params-route!
  "A route declaring the PATH CAPTURE `[:params :token]` sensitive, with an
  UNCLASSIFIED sibling capture `:tab`. Note what is NOT here: no `:params`
  schema. Path captures are keyword-keyed by the matcher, so — unlike the
  query axis — nothing has to promote them for the re-rooted keyword
  declaration to match. Row (0) is the control that says so."
  []
  (rf/reg-route ::user
                {:sensitive [[:params :token]]}
                "/rf2-6j8gd/user/:token/:tab"))

(defn- both-axes-route!
  "Declares a sensitive key on BOTH axes. The query key is promoted by the
  `:query` schema (it has to be); the path capture is not, and does not
  need to be. Guards the slice-arm refactor: projecting params must not
  have cost the query its projection."
  []
  (rf/reg-route ::both
                {:sensitive [[:params :token] [:query :secret]]
                 :query     [:map [:secret :string] [:tab :string]]}
                "/rf2-6j8gd/both/:token"))

(defn- plain-params-route!
  "A route with a path capture and NO classification at all — the ordinary
  case the fix must leave exactly as it was."
  []
  (rf/reg-route ::plain
                {}
                "/rf2-6j8gd/plain/:tab"))

(defn- no-params-route!
  "A route capturing nothing. The matcher yields `{}`, and the params span
  must keep rendering `{}` exactly as before — fail-closed must not invent
  a sentinel where the router wrote an empty map."
  []
  (rf/reg-route ::bare
                {}
                "/rf2-6j8gd/bare"))

(defn- navigate! [url]
  (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                    {:frame :rf/default}))

(defn- host-slice []
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/routing :current]))

(defn- observe!
  "Install Xray's handlers and point the panel's OBSERVED frame at `frame`
  — the frame whose classification must govern. `:rf.xray/set-frame` is
  the frame picker's own event, so this is the production path."
  [frame]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-frame frame] {:frame :rf/xray}))

(defn- current-params-text
  "The rendered text of the CURRENT ROUTE params span, or nil when the
  section rendered no params span at all."
  []
  (rf/with-frame :rf/xray
    (let [tree (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data]))]
      (some-> (find-by-testid tree "rf-xray-routing-current-params")
              node-text))))

(defn- current-query-text []
  (rf/with-frame :rf/xray
    (let [tree (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data]))]
      (some-> (find-by-testid tree "rf-xray-routing-current-query")
              node-text))))

(defn- current-section-text
  "Every string in the WHOLE CURRENT ROUTE section. A leak assertion scoped
  to the params span alone would pass over a value that reached the DOM
  through a neighbouring span instead."
  []
  (rf/with-frame :rf/xray
    (let [tree (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data]))]
      (or (some-> (find-by-testid tree "rf-xray-routing-current") node-text)
          ""))))

;; ---- (0) the control: the capture is keyword-keyed with NO schema -------

(deftest path-captures-are-keyword-keyed-without-a-params-schema
  (testing "rf2-6j8gd control — the matcher keywords every path capture, so
            the route's re-rooted [:params :token] declaration can match
            WITHOUT the route declaring a :params schema. This is the
            params-axis counterpart of the query test's promotion control,
            and it runs the other way: where an unpromoted query key
            silently fails open, a path capture cannot."
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (let [p (:params (host-slice))]
      (is (= {:token secret :tab sibling} p)
          "both path captures arrived keyword-keyed with values intact on the
           HOST slice — in-process reads stay RAW; redaction is read only at
           egress")
      (is (every? keyword? (keys p))
          "a capture arrived string-keyed, which would make the keyword
           declaration silently fail open the way an unpromoted query key
           does"))))

;; ---- (1) the defect: a declared-sensitive path param reaches the DOM ----

(deftest redacts-a-declared-sensitive-path-param-in-the-rendered-span
  (testing "rf2-6j8gd — the CURRENT ROUTE params span renders :rf/redacted
            for a path capture the ACTIVE ROUTE declared :sensitive, and
            never its value. RED against the unfixed panel, which printed
            the live token on every activation of this route."
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (observe! :rf/default)
    (let [text (current-params-text)]
      (is (some? text)
          "the params span rendered at all (a nil here means the section went
           quiet, which would pass the leak assertion vacuously)")
      ;; Scoped to the WHOLE section, not the params span: a leak assertion
      ;; reading only the span it expects would pass over a value that
      ;; reached the DOM through a neighbouring one.
      (is (not (re-find (re-pattern secret) (current-section-text)))
          (str "the declared-sensitive path param LEAKED to the DOM: "
               (pr-str (current-section-text))))
      (is (re-find #":rf/redacted" text)
          (str "the sensitive capture did not lower to the :rf/redacted
                sentinel: " (pr-str text)))
      ;; The unclassified sibling is what proves this is the ROUTE's
      ;; path-precise declaration matching, and not a fail-closed
      ;; whole-value redaction that would have hidden the leak by accident.
      (is (re-find (re-pattern sibling) text)
          (str "the UNCLASSIFIED sibling capture was scrubbed too — that is a
                blanket redaction, not the declaration: " (pr-str text))))))

;; ---- (2) ordinary params display is unchanged ---------------------------

(deftest renders-undeclared-params-verbatim
  (testing "rf2-6j8gd — a route declaring NO classification renders its
            params exactly as before. The walk is path-precise, never a
            blanket scrub, and this item is explicitly not a claim that
            undeclared carriers are a boundary."
    (plain-params-route!)
    (navigate! (str "/rf2-6j8gd/plain/" sibling))
    (observe! :rf/default)
    (let [text (current-params-text)]
      (is (some? text) "the params span rendered")
      (is (re-find (re-pattern sibling) text)
          (str "an undeclared param value was withheld: " (pr-str text)))
      (is (not (re-find #":rf/redacted" text))
          (str "an undeclared param redacted — over-scrub: " (pr-str text))))))

(deftest renders-an-empty-params-map-when-the-route-captures-nothing
  (testing "rf2-6j8gd — a route capturing nothing still renders `{}`, not a
            sentinel and not an absent span. The params span has no `seq`
            guard and renders unconditionally through `(or params {})`, so
            this is the existing empty behaviour the item requires be
            preserved."
    (no-params-route!)
    (navigate! "/rf2-6j8gd/bare")
    (observe! :rf/default)
    (is (= "{}" (current-params-text))
        "the empty-params render changed")))

;; ---- (3) observed-frame isolation --------------------------------------

(deftest unreachable-observed-frame-yields-no-slice-and-cannot-leak
  (testing "rf2-6j8gd — an UNREACHABLE observed frame cannot put params on
            screen AT ALL, and the reason is the sub's OTHER input failing
            first: `:rf.xray/target-frame-runtime-db` is
            `(:rf.db/runtime (rf/frame-state-value target))`, and
            `frame-state-value` answers nil for an unknown or destroyed
            frame, so there is no slice to project and the section renders
            its no-active-route caption rather than a sentinel. The
            fail-closed arm is still REACHABLE at the seam and is asserted
            here directly."
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (let [p (:params (host-slice))]
      ;; A frame id that was never registered, stamped VERBATIM, so the
      ;; walker takes its unresolvable-frame arm rather than borrowing the
      ;; ambient (Xray chrome) frame — which IS live and declares nothing.
      (observe! ::never-registered-frame)
      (is (nil? (current-params-text))
          "an unreachable observed frame produced a params span")
      (is (not (re-find (re-pattern secret) (current-section-text)))
          "the token reached the CURRENT ROUTE section under an unreachable frame")
      (is (= :rf/redacted
             (local-render/local-render-route-sub-value
               p ::never-registered-frame :rf.route/params))
          "the seam's fail-closed arm did not redact the whole params value"))))

;; ---- (4) the explicit local-raw grain ----------------------------------

(deftest local-raw-opt-in-returns-the-declared-param-verbatim
  (testing "rf2-6j8gd — the per-(tool,frame) :rf.egress/local-raw opt-in
            (EP-0015 §Cross-tool visibility grain) reaches the params seam
            end to end: the SAME projection with `raw? true` returns the
            declared-sensitive capture verbatim. The panel deliberately
            exposes no toggle, so it always passes the redacted default —
            this row pins that the mechanism exists rather than that the
            panel offers it."
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (let [p       (:params (host-slice))
          default (local-render/local-render-route-sub-value
                    p :rf/default :rf.route/params)
          raw     (local-render/local-render-route-sub-value
                    p :rf/default :rf.route/params true)]
      (is (= :rf/redacted (:token default))
          "the redacted default did not lower the declared capture")
      (is (= sibling (:tab default))
          "the redacted default scrubbed the undeclared sibling")
      (is (= secret (:token raw))
          "the trusted-local raw opt-in withheld the declared capture")
      (is (= p raw)
          "raw is the identity over the whole params map"))))

;; ---- (5) the seam is the ROUTE re-seeding, not a whole-value walk -------

(deftest whole-value-walk-cannot-match-the-re-rooted-params-declaration
  (testing "rf2-6j8gd — why the fix names the sub rather than walking the
            value. A route's declaration is RE-ROOTED to the absolute
            `[:rf.runtime/routing :current :params :token]`, so the plain
            whole-value seam (`local-render-value`, path []) cannot match it
            and ships the capture raw. This is the negative control for the
            door that was chosen, and the params twin of the query row."
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (let [p       (:params (host-slice))
          at-root (local-render/local-render-value p :rf/default)
          seeded  (local-render/local-render-route-sub-value
                    p :rf/default :rf.route/params)]
      (is (= secret (:token at-root))
          "the whole-value seam unexpectedly matched — if this fails the
           re-rooting contract has changed and the route re-seed may be
           redundant")
      (is (= :rf/redacted (:token seeded))
          "the route-sub seam did not re-seed at the slice's storage position"))))

;; ---- (6) the refactor guard: the query axis still projects --------------

(deftest both-classified-axes-project-in-one-slice
  (testing "rf2-6j8gd — the slice arm projects BOTH covered keys. rf2-8nyi2
            projected the query through a hand-written `:query` branch;
            this replaces it with a per-key walk over the covered
            projections, so the query axis has to be re-pinned here or the
            refactor could silently have traded one leak for another."
    (both-axes-route!)
    (navigate! (str "/rf2-6j8gd/both/" secret "?secret=" secret "&tab=" sibling))
    (observe! :rf/default)
    (let [params-text (current-params-text)
          query-text  (current-query-text)]
      (is (re-find #":rf/redacted" params-text)
          (str "the declared path capture did not redact: " (pr-str params-text)))
      (is (re-find #":rf/redacted" query-text)
          (str "the declared query key did not redact — the query axis
                regressed: " (pr-str query-text)))
      (is (re-find (re-pattern sibling) query-text)
          (str "the unclassified query sibling was scrubbed: " (pr-str query-text)))
      (is (not (re-find (re-pattern secret) (current-section-text)))
          (str "the secret reached the CURRENT ROUTE section: "
               (pr-str (current-section-text)))))))

;; ---- (7) the census discriminator: App-DB is NOT a second site ----------

(deftest the-app-db-style-whole-runtime-db-walk-already-matches
  (testing "rf2-6j8gd census — the App-DB tab reaches the SAME route slice
            (`app_db_diff_helpers/runtime-areas` maps :rf/route to
            `[:rf.runtime/routing :current]`), so it is the obvious
            candidate for a second leaking site. It is not one, and the
            reason is the ROOT rather than the seam: App-DB projects the
            WHOLE runtime-db partition through `local-render-value`, whose
            walk coordinates therefore ARE the absolute runtime-db paths the
            route declaration was re-rooted onto. Measured here rather than
            argued, because the census reports it as excluded."
    (classified-params-route!)
    (navigate! (str "/rf2-6j8gd/user/" secret "/" sibling))
    (let [runtime-db (rf.frame/frame-runtime-db-value :rf/default)
          projected  (local-render/local-render-value runtime-db :rf/default)]
      (is (= secret (get-in runtime-db [:rf.runtime/routing :current :params :token]))
          "PRECONDITION: the raw runtime-db carries the live capture")
      (is (= :rf/redacted
             (get-in projected [:rf.runtime/routing :current :params :token]))
          "a whole-runtime-db walk did NOT match the re-rooted route
           declaration — if this fails, the App-DB tab IS a second site and
           the census above is wrong")
      (is (= sibling
             (get-in projected [:rf.runtime/routing :current :params :tab]))
          "the whole-runtime-db walk scrubbed the unclassified sibling"))))
