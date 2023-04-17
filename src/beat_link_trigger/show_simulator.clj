(ns beat-link-trigger.show-simulator
  "Provides shallow playback simulation for shows when BLT is offline."
  (:require [beat-link-trigger.util :as util]
            [beat-link-trigger.show-util :as su :refer [latest-show swap-show!]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

(defn- choose-simulator-player
  "Finds the first player number that has not yet been used by a
  simulator in the show."
  [show]
  (let [used (set (map :player (vals (:simulators show))))]
    (first (remove used (map inc (range 6))))))

(defn- player-menu-model
  "Returns the available player numbers this simulator can choose."
  [show uuid]
  (let [show      (latest-show show)
        simulator (get-in show [:simulators uuid])
        used      (set (map :player (vals (:simulators show))))]
    (sort (set/union (set (remove used (map inc (range 6)))) #{(:player simulator)}))))

(defn- recompute-player-models
  "Updates the player combo-boxes of all open windows to reflect the
  current state of the other windows."
  [show]
  (doseq [simulator (vals (:simulators (latest-show show)))]
    (let [combo   (seesaw/select (:frame simulator) [:#player])
          current (seesaw/selection combo)]
      (swap-show! show assoc-in [:simulators (:uuid simulator) :adjusting] true)
      (seesaw/config! combo :model (player-menu-model show (:uuid simulator)))
      (seesaw/selection! combo current)
      (swap-show! show update-in [:simulators (:uuid simulator)] dissoc :adjusting))))

(defn build-simulator-panel
  "Creates the UI of the simulator window, once the show has its basic
  configuration added."
  [show uuid]
  (let [simulator (get-in (latest-show show) [:simulators uuid])]
    (mig/mig-panel
     :items [["Player:"]
             [(seesaw/combobox :id :player :model (player-menu-model show uuid)
                               :selected-item (:player simulator)
                               :listen [:item-state-changed
                                        (fn [e]
                                          (let [chosen (seesaw/selection e)]
                                            (swap-show! show assoc-in [:simulators uuid :player] chosen)
                                            (when-not (get-in (latest-show show) [:simulators uuid :adjusting])
                                              (recompute-player-models show))))])
              "wrap"]])))

(defn- create-simulator
  "Creates a new shallow playback simulator for the show. Must be called
  with an up-to-date view of the show."
  [show]
  (let [uuid         (java.util.UUID/randomUUID)
        ^JFrame root (seesaw/frame :title (str "Simulator for " (util/trim-extension (.getPath (:file show))))
                                   :on-close :nothing)
        player       (choose-simulator-player show)
        close-fn     (fn []
                       (.dispose root)
                       (swap-show! show update :simulators dissoc uuid)
                       (recompute-player-models show)
                       (seesaw/config! (:simulate-item show) :enabled? true)
                       true)]
    (swap-show! show assoc-in [:simulators uuid]
                {:uuid     uuid
                 :show     (:file show)
                 :frame    root
                 :player   player
                 :sync     true
                 :master   false
                 :playing  false
                 :pitch    0
                 :time     0
                 :close-fn close-fn})
    (seesaw/config! root :content (build-simulator-panel show uuid))
    (recompute-player-models show)
    (seesaw/listen root :window-closing (fn [_] (close-fn)))
    (seesaw/pack! root)
    (seesaw/show! root))
  (when (>= (count (:simulators show)) 5)  ; We're adding one, which gets us up to six.
    (seesaw/config! (:simulate-item show) :enabled? false)))



(defn build-simulator-action
  "Creates the menu action to open a shallow playback simulator window."
  [show]
  (seesaw/action :handler (fn [_] (create-simulator (latest-show show)))
                 :name "New Playback Simulator"
                 :tip "Open a window that offers a shallow simulation of a CDJ playing a track."))
