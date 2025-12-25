visual studio code

//for data collection
run openfoodfacts_to_txt.py by open terminal  in vs code by using command python openfoodfacts_to_txt.py

//data cleansing can refer command used for mapping.txt

//data mapping
run activity03_mapping.py by open terminal  in vs code by using command python activity03_mapping.py




mobile_baseline(android studio)

1)add the cpp ,java assets ,jniLibs,llama like the diagram below

2)add qwen2.5-1.5b-instruct-q4_k_m.gguf from the https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-
GGUF/blob/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
into assets


app/
├── .cxx/
├── build/
├── src/
│   ├── androidTest/
│   └── main/
│       ├── assets/
│       │   └── qwen2.5-1.5b-instruct-q4_k_m...   <-- (Model file)
│       │
│       ├── cpp/                                  <-- (Native C++ Source)
│       │   ├── CMakeLists.txt
│       │   └── native-lib.cpp
│       │
│       ├── java/
│       │   └── edu/utem/ftmk/slm02/
│       │       ├── ui.theme/                     <-- (Theme Package)
│       │       ├── InferenceMetrics              <-- (Kotlin/Java Class)
│       │       ├── MainActivity                  <-- (Kotlin/Java Class)
│       │       └── MemoryReader                  <-- (Kotlin/Java Class)
│       │
│       ├── jniLibs/                              <-- (Precompiled Native Libs)
│       │   └── arm64-v8a/
│       │       ├── libggml.so
│       │       ├── libggml-base.so
│       │       ├── libggml-cpu.so
│       │       └── libllama.so
│       │
│       ├── llama/                                <-- (Likely Header files)
│       │   └── llama.h
|	 |    └── ggml.h
│       │   └──ggml-alloc.h
│       │   └── ggml-backend.h
│       │   └── ggml-cpu.h
│       │   └── ggml-opt.h		
│       │
│       ├── res/
│       └── AndroidManifest.xml
│
├── test [unitTest]/
├── .gitignore
├── build.gradle.kts
└── proguard-rules.pro