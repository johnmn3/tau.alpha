(ns tau.alpha.state
  (:require [cljs.reader :refer [read-string]]))

(enable-console-print!)
(set! *print-fn-bodies* true)

(def start-time (.getTime (js/Date.)))

(def conf (atom nil))

(defn set-conf! [conf-map]
  (swap! conf (constantly conf-map)))

(defn off-screen? []
  (undefined? (.-document js/self)))

(def on-screen? (complement off-screen?))

(def ports (atom {}))

(def sabs (atom {}))

(def default-executor (atom nil))

(def loaded? (atom false))

(defn repl-fn [& args] #_ (apply (:repl-fn @conf) args))

(def screen "screen")

(defn get-id []
  (if (exists? js/taupreload) (.-tid js/taupreload) "screen"))

(def id (get-id))

(def serve-handlers (atom {}))

(defn add-handler [h]
  (swap! serve-handlers merge h))

(defn remove-handler [k]
  (swap! serve-handlers dissoc k))

(def event-message "message")

(defn- deserialize
  [data]
  (read-string data))

(defn- serialize
  [data]
  (pr-str data))

(defn- map-key [f m]
  (into {} (for [[k v] m] [(f k) v])))

(defn- message-handler [handlers e]
  (let [port (-> e .-data .-port)
        serialized (aget (.-data e) "serialized")
        ;_ (println "MH serial:" serialized)
        deserialized
        (try (deserialize serialized)
          (catch :default e
            (do
              (println "error:" e)
              (println "serial:" serialized))))
        tid (keyword (:name deserialized))
        data (:data deserialized)
        transferables (map-key deserialize (js->clj (aget (.-data e) "transferables")))
        copied (reduce #(assoc-in %1 (first %2) (second %2)) data transferables)
        copied (if-not port copied (assoc copied :port port))]
      (when-let [handler (get handlers tid)]
        (handler copied))))

(def new-port-fns (atom #{}))

(defn add-listeners
  [handlers]
  (.addEventListener js/self event-message (partial message-handler handlers)))
