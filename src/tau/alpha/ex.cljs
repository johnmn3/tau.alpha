(ns tau.alpha.ex
  (:require-macros [tau.alpha.macros :refer [log on future later]])
  (:require [figwheel.repl :as repl :refer [connect focus conns]]
            [tau.alpha.state :refer [set-conf! on-screen? repl-fn]]
            [tau.alpha.on :refer [tauon kill]]
            [tau.alpha.exec :refer [exec]]
            [tau.alpha.future :refer [yield future-call]]
            [tau.alpha.util :refer [sleep read]]
            [tau.alpha.tau :refer [Tau tau wait set-tau-validator! set-tau-error-handler!]]))

(enable-console-print!)

;; this namespace contains some examples of how to use tauons

(set-conf! {:main "tau.alpha.ex"
            :log? true})

(defn handle-response [event]
  (let [xhr (.-target event)]
    (if (= (.-readyState xhr) 4)
      (if (= (.-status xhr) 200)
        (let [res (.-responseText xhr)]
          (when res
            (yield res)))
        (yield (.-statusText xhr))))))


(defn xget [url]
  (later [url]
    (let [axhr (js/XMLHttpRequest.)]
      (.open axhr "GET" url)
      (set! (.-onreadystatechange axhr) handle-response)
      (.send axhr nil))))

(def x "x")

(def y 9)

(when (on-screen?)
  (def z {:one 1 :two 2}))


(comment

  (ns tau.alpha.ex)

  (def t (tauon))

  t

  (on t (println "hi" x y))

  (on t
    (def result (assoc z :y y))
    (println result))

  (on t [z]
    (def working-result (assoc z :y y :x x))
    (println working-result))

  (on t (println js/document))

  (on t {:error-fn #(println "oops!" %)} (println js/document))

  (log "hi")

  (log :ret "hi")

  (log :ret "hi" "there")

  (on t (log "<- look, tauon id, namespace and line number!!!"))

  (def t2 (tauon 1))

  (on t2 (log "init value was:" tau.alpha.on/init-val))

  (def t3 (tauon #(log "init result was:" (inc %)) 1))

  (def t4 (tauon "t4" #(log "fancy business with" %) :fancy/value))

  (on "t4" (log "But patches are welcome!"))

  (deft t5 []
    (def one 1)
    (def two 2)
    (log "finished launching with result:" (+ one two)))

  (deft t6 [z]
    (def my-map (atom {:original-value 100}))
    (swap! my-map merge z)
    (log "t6 is ready:" @my-map))

  t2

  (on t [z]
    (let [q 1]
      (on :tau.alpha.on/t1ne3Ug5kRYOrnvtATlH99Q [z q]             ; on t2
        (log "result:" (assoc z :q q)))))

  (kill t)
  (kill t2)
  (kill t3)
  (kill t4)
  (kill t5)
  (kill t6)

  :tau/end)
