(ns tau.alpha.io
  (:require-macros [tau.alpha.macros :refer [log on]])
  (:require [cljs.reader :refer [read-string]]
            [tau.alpha.state :refer [serve-handlers get-id ports conf start-time
                                 screen on-screen? off-screen? id loaded?
                                 add-handler remove-handler add-listeners
                                 event-message message-handler]]
            [tau.alpha.util :refer [write read on? typed-array? pr-fn
                                serialize deserialize]]
            [clojure.pprint :refer [pprint]]))

(enable-console-print!)
(set! *print-fn-bodies* true)

(defn do-print [s]
  (pprint s))

(add-handler {:do-print do-print})

(declare plog)

(defn- post [worker t copying-data transferable-keys]
  (let [without-transferables (reduce #(assoc-in %1 %2 nil) copying-data transferable-keys)
        transferables (into {} (map (fn [key] [(serialize key) (get-in copying-data key)]) transferable-keys))
        serialized (serialize {:name t :data without-transferables})
        copying (clj->js {"serialized" serialized
                          "transferables" transferables})]
    (.postMessage worker copying #_ (clj->js (or (vals transferables) []))
                  (if (= [[:port]] transferable-keys)
                    (clj->js (or (vals transferables) []))
                    #js []))))

(defn worker
  [script handlers]
  (let [w (new js/Worker script)]
    (.addEventListener w event-message (partial message-handler handlers))
    w))


(defn screen>
  ([b tag copying-data transferable-keys]
   (post b tag copying-data transferable-keys))
  ([b tag copying-data]
   (if (:array-buffer copying-data)
     (screen> b tag copying-data [[:array-buffer]])
     (if (:args-typed-array copying-data)
       (screen> b tag copying-data [[:ab]])
       (screen> b tag copying-data [])))))


(defn screen<
  ([tag copying-data transferable-keys]
   (post js/self tag copying-data transferable-keys))
  ([tag copying-data]
   (if (:array-buffer copying-data)
     (screen< tag copying-data [[:array-buffer]])
     (if (:args-typed-array copying-data)
       (screen< tag copying-data [[:ab]])
       (screen< tag copying-data [])))))

(defn- prepare [copying-data transferable-keys]
  (let [without-transferables (reduce #(assoc-in %1 %2 nil) copying-data transferable-keys)
        transferables (into {} (map (fn [key] [(serialize key) (get-in copying-data key)]) transferable-keys))
        serialized (serialize {:name :typed-call-here :data without-transferables})
        copying (clj->js {"serialized" serialized
                          "transferables" transferables})]
    copying))

(defn transmit [tid m]
  (let [p (:port (get @ports tid))]
    (if p (.postMessage p (if (:args-typed-array m)
                            (prepare m [[:ab]] #_ (clj->js [[:ab]]))
                            (prepare m [])) #js []))))

(defn fix-arg [arg]
  (if (fn? arg) (pr-str arg) arg))

(defn fix-args [args]
  (map fix-arg args))

(defn do-on [obj afn opts & args]
  (let [t (if (keyword? obj) obj (str obj))]
    (if (= (get-id) t)
      (apply afn args)
      (if (or (= "screen" t) (= (symbol "screen") t))
        (screen<
          :typed-call-here
          (if (typed-array? (first args))
            {:args (if (= 1 (count args)) '() (rest args)) :to t :from id :opts opts
             :ab (first args) :afn (pr-str afn) :cmd :typed-call-here :args-typed-array true}
            {:cmd :typed-call-here :args (fix-args args) :afn (write afn) :to t
             :from id :opts (update opts :error-fn fix-arg)}))
        (if (= "screen" (get-id))
         (screen>
           (:w (get @ports t))
           :typed-call-here
           (if (typed-array? (first args))
             {:cmd :typed-call-here :args (if (= 1 (count args)) '() (rest args)) :afn (write afn)
              :ab (first args) :args-typed-array true
              :to t :from id :opts (update opts :error-fn pr-str)}
             {:cmd :typed-call-here :args (fix-args args) :afn (write afn)
              :to t :from id :opts (update opts :error-fn pr-str)}))
         (transmit t
                   (if (typed-array? (first args))
                     {:cmd :typed-call-here :afn (write afn) :args (if (= 1 (count args)) '() (rest args))
                      :ab (first args) :args-typed-array true
                      :to t :from id :opts (update opts :error-fn pr-str)}
                     {:cmd :typed-call-here :afn (write afn) :args (fix-args args)
                      :to t :from id :opts (update opts :error-fn pr-str)})))))))

(defn plog [f l ret & s]
  (when (or @loaded? (on-screen?))
    (if (:log? @conf)
      (if-not (on? screen)
        (screen< :do-print
                 {:on (get-id) :file f :line l :return-value ret :out s})
        (pprint {:on (get-id) :file f :line l :return-value ret :out s})))
    ret))

(defn route [m]
  (println "routing for transmit. m cmd: " (:cmd m))
  (((:cmd m) @serve-handlers) m))

(defn receive-dist-port [m]
  (swap! ports assoc-in [(:tid m) :port] (:port m))
  (doall (map #(% (:tid m)) @tau.alpha.state/new-port-fns))
  (set! (.-onmessage (:port m))
        #((partial message-handler @serve-handlers) %)))

(add-handler {:dist-port receive-dist-port})

(defn kill-remote [tid]
  (when (not (on-screen?))
    (swap! ports update-in [tid :port] #(.close %)))
  (swap! ports dissoc tid))

(defn kill-local []
  (let [remote-ports (keys @ports)]
    (doall (map #(on % [id] (kill-remote id)) remote-ports))
    (on screen [id] (kill-remote id))
    (js/close)))

(defn connect
  ([]
   (when (off-screen?)
     (let [p (condp = (get-id)
               "hf"          65
               "not approved")]
      (if (not (:repl-fn @conf))
        (log "no repl-fn set for this filo")
        (if (int? p)
          ((:repl-fn @conf) p))))))
  ([p]
   ((:repl-fn @conf) p)))

(defn send-port [w f p]
  (screen> w :dist-port {:cmd :dist-port :tid f :port p} [[:port]]))

(defn send-two-port [tid1 tid2]
  (let [c (js/MessageChannel.)]
    (send-port (:w (get @ports tid1)) tid2 (.-port2 c))
    (send-port (:w (get @ports tid2)) tid1 (.-port1 c))))

(defn dist-ports [tid]
  (when (on-screen?)
    (let [ports (keys (dissoc @ports tid))]
      (doall (map #(send-two-port tid %) ports)))))

(add-listeners @serve-handlers)
