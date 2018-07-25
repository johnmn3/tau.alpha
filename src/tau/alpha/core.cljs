(ns tau.alpha.core
  (:require [tau.alpha.state :as s]
            [tau.alpha.on :as o]
            [tau.alpha.tau :as t]))

(def set-conf! s/set-conf!)

(def on-screen? s/on-screen?)

(def tauon o/tauon)

(def tau t/tau)

(def db t/db)

(def reconstruct-tau t/reconstruct-tau)

(def get-id t/-id)
