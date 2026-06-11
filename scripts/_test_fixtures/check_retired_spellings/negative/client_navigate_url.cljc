(ns fixture.negative.client-navigate-url
  "NEGATIVE fixture: the SANCTIONED :url key on a CLIENT navigation surface
  (routing / navigate). Per the HTTP-response-vs-navigation vocabulary rule
  (spec/Conventions.md §The naming rules), client navigation uses :url —
  different concept from the SSR redirect :location. No :rf.server/*redirect
  tag is anywhere near these, so the (b) detector must stay GREEN.")

(defn go-to [path]
  ;; Sanctioned: :url is the canonical client-navigation key.
  (dispatch [:routing/navigate {:url path}])
  (navigate {:url path :replace? true}))

(defn url-change-handler [{:keys [url]}]
  (handle-url url))
