(ns app.renderer.core
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [app.renderer.matrix])
  (:require-macros [app.utils.macros :refer [inline-resource]]))

(def fragment-shader-src (inline-resource "shaders/fragment.wgsl"))
(def vertex-shader-src (inline-resource "shaders/vertex.wgsl"))
(def compute-shader-src (inline-resource "shaders/compute.wgsl"))

(enable-console-print!)

(def swap-chain-format "bgra8unorm")

(defn device [adapter] ^js/Promise
  (.requestDevice adapter))

(defn command-encoder [device] ^js/CommandEncoder
  (.createCommandEncoder device))

(def gpu-adapter (atom nil))
(def gpu-device  (atom nil))

(def gpu-buffer-usage-opts (js->clj js/GPUBufferUsage :keywordize-keys true))

(defn ->gpu-buffer [device mapped? size usage-opts & [label]] ^js/GPUBuffer
  (.createBuffer device #js {:label            label
                             :mappedAtCreation mapped?
                             :size             size
                             :usage            (apply bit-or (map (partial get gpu-buffer-usage-opts) usage-opts))}))

(defn ->unmapped-gpu-buffer [device size usage-opts & [label]] ^js/GPUBuffer
  (.createBuffer device #js {:label label
                             :size  size
                             :usage (apply bit-or (map (partial get gpu-buffer-usage-opts) usage-opts))}))

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

(defn ->compute-pipeline [^js/GPUDevide device ^js/GPUBindGroupLayout bind-group-layout ^js/GPUShaderModule shader-module entry-point]
  (let [pipeline-layout (.createPipelineLayout device (clj->js {:bindGroupLayouts [bind-group-layout]}))]
    (.createComputePipeline device (clj->js {:layout  pipeline-layout
                                             :compute {:module     shader-module
                                                       :entryPoint entry-point}}))))

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


  ;;; Matrix Math

  (def device @gpu-device)

  ;; First Matrix

  (def first-matrix (js/Float32Array. #js [2 4
                                           1 2 3 4
                                           5 6 7 8]))

  (def gpu-buffer-first-matrix (->gpu-buffer device true (.-byteLength first-matrix) [:STORAGE] "First Matrix"))
  (.set (js/Float32Array. (gpu-buffer->array-buffer gpu-buffer-first-matrix)) first-matrix)
  (.unmap gpu-buffer-first-matrix)

  ;; Second Matrix
  (def second-matrix (js/Float32Array. #js [4 2
                                            1 2
                                            3 4
                                            5 6
                                            7 8]))

  (def gpu-buffer-second-matrix (->gpu-buffer device true (.-byteLength second-matrix) [:STORAGE] "Second Matrix"))
  (.set (js/Float32Array. (gpu-buffer->array-buffer gpu-buffer-second-matrix)) second-matrix)
  (.unmap gpu-buffer-second-matrix)

  ;; Result Matrix
  
  (def result-matrix-buffer-size (* js/Float32Array.BYTES_PER_ELEMENT (+ 2 (* (first first-matrix) (second second-matrix)))))
  (def gpu-buffer-result-matrix (->unmapped-gpu-buffer device result-matrix-buffer-size [:STORAGE :COPY_SRC] "Result Matrix"))

  ;; Bind Group Layout
  
  (def bind-group-layout (.createBindGroupLayout device (clj->js {:entries [{:binding    0
                                                                             :visibility js/GPUShaderStage.COMPUTE
                                                                             :buffer     {:type "read-only-storage"}}
                                                                            {:binding    1
                                                                             :visibility js/GPUShaderStage.COMPUTE
                                                                             :buffer     {:type "read-only-storage"}}
                                                                            {:binding    2
                                                                             :visibility js/GPUShaderStage.COMPUTE
                                                                             :buffer     {:type "storage"}}]})))

  (def bind-group (.createBindGroup device (clj->js {:label   "Matrix Bind Group Layout"
                                                     :layout  bind-group-layout
                                                     :entries [{:binding  0
                                                                :resource {:buffer gpu-buffer-first-matrix}}
                                                               {:binding  1
                                                                :resource {:buffer gpu-buffer-second-matrix}}
                                                               {:binding  2
                                                                :resource {:buffer gpu-buffer-result-matrix}}]})))

  ;; Compute Shader
  (def shader-module (.createShaderModule device #js {:code compute-shader-src}))

  ;; Compute Pipeline
  (def pipeline-layout (.createPipelineLayout device (clj->js {:bindGroupLayouts [bind-group-layout]})))
  (def pipeline (.createComputePipeline device (clj->js {:label "Matrix Compute Pipeline"
                                                         :layout  pipeline-layout
                                                         :compute {:module     shader-module
                                                                   :entryPoint "main"}})))

  ;; Encoder
  (def command-encoder (.createCommandEncoder device))
  (def pass-encoder (.beginComputePass command-encoder))

  (.setPipeline pass-encoder pipeline)
  (.setBindGroup pass-encoder 0 bind-group)
  (.dispatchWorkgroups pass-encoder
                       (js/Math.ceil (/ (first first-matrix) 8))
                       (js/Math.ceil (/ (second second-matrix) 8)))
  (.end pass-encoder)

  ;; Get a GPU buffer for reading in an unmapped state.
  (def gpu-read-buffer (->unmapped-gpu-buffer device result-matrix-buffer-size [:COPY_DST :MAP_READ] "Read Buffer"))

  (.copyBufferToBuffer command-encoder gpu-buffer-result-matrix 0 gpu-read-buffer 0 result-matrix-buffer-size)

  (def commands (.finish command-encoder))

  (.submit (.-queue device) #js [commands])

  (.mapAsync gpu-read-buffer js/GPUMapMode.READ)

  (def copy-array-buffer (gpu-buffer->array-buffer gpu-read-buffer))
  (println (js/Float32Array. copy-array-buffer))

  )

(defn start-webgpu
  [^js/HTMLCanvasElement canvas]
  ;; TODO: auto-resize smoothly
  (go
    (if (not (. js/navigator -gpu))
      (js/alert "Your browser does not support WebGPU or it is not enabled. More info: https://webgpu.io")
      (let [adapter          ^js/GPUAdapter (<p! (.requestAdapter (. js/navigator -gpu)))
            device           ^js/GPUDevice (<p! (device adapter))]
        ;   gpu-write-buffer ^js/GPUBuffer (->gpu-buffer device true 4 [:MAP_WRITE :COPY_SRC])
        ;   gpu-read-buffer  ^js/GPUBuffer (->gpu-buffer device false 4 [:COPY_DST :MAP_READ])
        ;   pipeline         ^js/GPUCommandEncoder (->pipeline device gpu-write-buffer gpu-read-buffer)]

        (println "Found GPU Adapter:" adapter)
        (reset! gpu-adapter adapter)

        (println "Found device:" device)
        (reset! gpu-device device)

        #_(println "Executing pipeline:" pipeline)
        #_(<p! (.mapAsync gpu-read-buffer js/GPUMapMode.READ))

        #_(println "Reading from Completion Buffer:" (js/Uint8Array. (gpu-buffer->array-buffer gpu-read-buffer)))))))

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
