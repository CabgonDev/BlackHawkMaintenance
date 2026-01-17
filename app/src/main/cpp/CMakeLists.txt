cmake_minimum_required(VERSION 3.22.1)
project(blackhawk_llm)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Forzar optimización fuerte (incluso si Android Studio arma Debug)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG")

# llama.cpp vendorizado en app/src/main/cpp/llama
add_subdirectory(llama)

add_library(llama_bridge SHARED
        llama_bridge.cpp
)

target_include_directories(llama_bridge PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama
        ${CMAKE_CURRENT_SOURCE_DIR}/llama/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama/ggml/include
)

target_link_libraries(llama_bridge
        llama
        android
        log
)
