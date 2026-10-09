(ns re-frame.ssr.ring.pipeline-materialiser-test
  "The public response materialiser and header fold, called directly, and
  `ssr-middleware`'s default `:match?`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.headers :as rf.ssr.ring.headers]
            [re-frame.ssr.ring.pipeline :as rf.ssr.ring.pipeline]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(deftest redirect-replaces-every-location-casing
  ;; The fold keeps the first-seen spelling, so a bare `(assoc "Location" ..)`
  ;; would ship the app's stale `LOCATION` beside the target.
  (is (= {:status  303
          :headers {"Location"   "/new"
                    "X-Trace"    "abc"
                    "vary"       ["Accept" "Cookie"]
                    "Set-Cookie" "sid=s1; Path=/"}
          :body    ""}
         (rf.ssr.ring.pipeline/ssr-response->ring-response
           {:redirect {:status 303 :location "/new"}
            :headers  [["LOCATION" "/old"]
                       ["X-Trace" "abc"]
                       ["vary" "Accept"]
                       ["Vary" "Cookie"]]
            :cookies  [{:name "sid" :value "s1" :path "/"}]}
           nil))))

(deftest redirect-no-target-leaves-an-existing-location-alone
  ;; Stripping here would leave the 3xx with no Location at all.
  (is (= {:status 302 :headers {"location" "/app-set"} :body ""}
         (rf.ssr.ring.pipeline/ssr-response->ring-response
           {:redirect {:status 302} :headers [["location" "/app-set"]]}
           nil))))

(deftest non-string-redirect-location-coerced-to-string
  (is (= "5" (get-in (rf.ssr.ring.pipeline/ssr-response->ring-response
                       {:redirect {:status 302 :location 5}} nil)
                     [:headers "Location"]))))

(deftest non-integer-status-fails-closed-to-500
  ;; A float is a number but not a Ring status, and it is never coerced.
  (is (= 500 (:status (rf.ssr.ring.pipeline/ssr-response->ring-response
                        {:status 200.0 :headers []} "x")))))

(deftest mixed-case-names-collapse-into-one-first-seen-key
  (is (= {"Vary" ["Accept" "Origin" "Accept-Encoding"]}
         (reduce rf.ssr.ring.headers/merge-pair-into-header-map
                 {}
                 [["Vary" "Accept"] ["vary" "Origin"] ["VARY" "Accept-Encoding"]]))))

(deftest mixed-case-content-type-with-override-strips-every-casing
  (is (= {"Content-Type" "text/xml; charset=utf-8"}
         (rf.ssr.ring.headers/headers->ring-map+content-type-override
           [["CONTENT-TYPE" "text/html"] ["content-type" "application/json"]]
           "text/xml; charset=utf-8"))))

(deftest mixed-case-set-cookie-append-collapses-under-existing-key
  (is (= {"set-cookie" ["pre=1" "session=abc" "theme=dark"]}
         (rf.ssr.ring.headers/append-set-cookies
           {"set-cookie" "pre=1"}
           [{:name "session" :value "abc"} {:name "theme" :value "dark"}]))))

(deftest non-string-header-value-emits-exactly-one-dev-warning
  ;; The string-valued header beside it is the no-false-positive control.
  (let [warnings (atom [])]
    (rf/register-listener! :trace ::non-string-header-watch
      (fn [ev] (when (= :rf.ssr/ssr-non-string-header-value (:operation ev))
                 (swap! warnings conj [(:op-type ev) (select-keys (:tags ev) [:header :value-type])]))))
    (try
      (is (= {"X-Count" "5" "X-Custom" "v"}
             (reduce rf.ssr.ring.headers/merge-pair-into-header-map
                     {}
                     [["X-Count" 5] ["X-Custom" "v"]])))
      (finally
        (rf/unregister-listener! :trace ::non-string-header-watch)))
    ;; The slots are the ones the Spec 009 catalogue row lists.
    (is (= [[:warning {:header "X-Count" :value-type "java.lang.Long"}]] @warnings))))

(deftest middleware-default-match-renders-get-and-passes-other-methods-through
  (rf/reg-event :init/mw-blank {:platforms #{:server}} (fn [_ _] {}))
  (rf/reg-view* :pages/mw-blank (fn [] [:div "ssr body"]))
  (let [from-wrapped {:status 201 :headers {} :body "from wrapped"}
        app          ((rf.ssr.ring/ssr-middleware
                        {:initial-events [[:init/mw-blank]]
                         :root-view      [(rf/view :pages/mw-blank)]
                         :payload        :rf.ssr.payload/whole-app-db})
                      (constantly from-wrapped))]
    (is (str/includes? (:body (app {:uri "/" :request-method :get})) "ssr body"))
    (is (= from-wrapped (app {:uri "/" :request-method :post})))))
