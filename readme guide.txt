visual studio code
-import required library
-run python openfoodfacts_to_txt.py for data collection part in terminal of vs code 
-refer command used for cleansing.txt for   Activity 02
-run python activity03_mapping.py for data mapping part in terminal of vs code(add your own gemini api key first before run the .py)
note:
-use ur own gemini api key becuz every gemini api key got its own usage limit,if 3 ppl access same api key might have issue ltr
can access api key from 
-can generate api key from google ai studio 



mobile_baseline(android studio)
1)Create new empty project
2)add the cpp ,java assets ,jniLibs,llama like the diagram below

3)add qwen2.5-1.5b-instruct-q4_k_m.gguf from the https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-
GGUF/blob/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
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
│       │   ├──  llama.h
│       │   ├── ggml.h
│       │   ├── ggml-alloc.h
│       │   ├── ggml-backend.h
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


can type command "adb logcat -s ALLERGEN_DEBUG" in terminal to check the progress of predicting all or predict one item
