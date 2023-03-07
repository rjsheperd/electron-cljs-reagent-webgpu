(ns app.renderer.core
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]])
  (:require-macros [app.utils.macros :refer [inline-resource]]))

(def fragment-shader-src (inline-resource "shaders/fragment.wgsl"))
(def vertex-shader-src (inline-resource "shaders/vertex.wgsl"))

(enable-console-print!)

(def swap-chain-format "bgra8unorm")

(defn device [adapter] ^js/Promise
  (.requestDevice adapter))

(defn command-encoder [device] ^js/CommandEncoder
  (.createCommandEncoder device))

(def gpu-adapter (atom nil))
(def gpu-device  (atom nil))

(def gpu-buffer-usage-opts (js->clj js/GPUBufferUsage :keywordize-keys true))

(defn ->gpu-buffer [device mapped? size usage-opts] ^js/GPUBuffer
  (.createBuffer device #js {:mappedAtCreation mapped?
                             :size             size
                             :usage           (apply bit-or (map (partial get gpu-buffer-usage-opts) usage-opts))}))

(defn gpu-buffer->array-buffer [^js/GPUBuffer gpu-buffer] ^js/ArrayBuffer
  (.getMappedRange gpu-buffer))

(defn write-to-gpu-buffer [gpu-buffer array-to-write]
  (let [array-buffer (gpu-buffer->array-buffer gpu-buffer)]
    ;; Write bytes to buffer.
    (doto (js/Uint8Array. array-buffer)
      (.set array-to-write))))

(defn ->command-encoder [^js/GPUDevice device] ^js/GPUCommandEncoder
  (.createCommandEncoder device))

(defn ->copy-encoder [^js/GPUDevice device ^js/GPUBuffer gpu-write-buffer ^js/GPUBuffer gpu-read-buffer size]
  (let [encoder (->command-encoder device)]
    (.copyBufferToBuffer encoder
                         gpu-write-buffer 
                         0                
                         gpu-read-buffer  
                         0                
                         size)
    encoder))

(defn ->pipeline [device ^js/GPUBuffer gpu-write-buffer ^js/GPUBuffer gpu-read-buffer] ^js/GPUCommandEncoder
  ;; Write bytes to buffer.
  (write-to-gpu-buffer gpu-write-buffer #js [1 2 3 4])

  ;; Unmap buffer so it can be used later for copy.
  (.unmap gpu-write-buffer)

  (let [encoder  (->copy-encoder device gpu-write-buffer gpu-read-buffer 4)
        commands (.finish encoder)]
    ;; Create copy encoder b/w write and read buffers
    (.submit (.-queue device) #js [commands])
    encoder))

(comment

  (def device @gpu-device)

  (def gpu-write-buffer (->gpu-buffer device true 4 [:MAP_WRITE :COPY_SRC]))

  ;; Write bytes to buffer.
  (write-to-gpu-buffer gpu-write-buffer #js [1 2 3 4])

  ;; Unmap buffer so it can be used later for copy.
  (.unmap gpu-write-buffer)

  (def gpu-read-buffer (->gpu-buffer device false 4 [:COPY_DST :MAP_READ]))

  (def copy-encoder (->copy-encoder device gpu-write-buffer gpu-read-buffer 4))
  (def copy-commands (.finish copy-encoder))
  (.submit (.-queue device) #js [copy-commands])

  (def has-completed? (atom nil))
  (go 
    (let [completed? (<p! (.mapAsync gpu-read-buffer js/GPUMapMode.READ))]
      (reset! has-completed? completed?)))

  @has-completed?

  (def copy-array-buffer (gpu-buffer->array-buffer gpu-read-buffer))
  (println (js/Uint8Array. copy-array-buffer))

  )

(defn start-webgpu
  [^js/HTMLCanvasElement canvas]
  ;; TODO: auto-resize smoothly
  (go
    (if (not (. js/navigator -gpu))
      (js/alert "Your browser does not support WebGPU or it is not enabled. More info: https://webgpu.io")
      (let [adapter          ^js/GPUAdapter (<p! (.requestAdapter (. js/navigator -gpu)))
            device           ^js/GPUDevice (<p! (device adapter))
            gpu-write-buffer ^js/GPUBuffer (->gpu-buffer device true 4 [:MAP_WRITE :COPY_SRC])
            gpu-read-buffer  ^js/GPUBuffer (->gpu-buffer device false 4 [:COPY_DST :MAP_READ])
            pipeline         ^js/GPUCommandEncoder (->pipeline device gpu-write-buffer gpu-read-buffer)]

        (println "Found GPU Adapter:" adapter)
        (reset! gpu-adapter adapter)

        (println "Found device:" device)
        (reset! gpu-device device)

        (println "Executing pipeline:" pipeline)
        (<p! (.mapAsync gpu-read-buffer js/GPUMapMode.READ))

        (println "Reading from Completion Buffer:" (js/Uint8Array. (gpu-buffer->array-buffer gpu-read-buffer)))))))

(defn output []
  (r/create-class
   {:display-name "output"
    :component-did-mount #(start-webgpu (rd/dom-node %))
    :reagent-render
    (fn []
      [:canvas {:id "output"
                :style {:flex-grow "1"}}])}))

(def output2
  (with-meta output
    {:component-did-mount #(start-webgpu (rd/dom-node %))}))

(defn root-component
  []
  [:div {:style {:height "100%"
                 :display "flex"
                 :flex-direction "column"}}
   [output]])

(defn ^:dev/after-load start! []
  (rd/render
   [root-component]
   (js/document.getElementById "app-container")))
