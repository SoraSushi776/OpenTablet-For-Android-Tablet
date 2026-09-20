cmake_minimum_required(VERSION 3.22.1)

project("tablet_hid")

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        hid_driver.cpp
        touch_processor.cpp)

# Enable C++17 for std::clamp and structured bindings.
target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_17)

# Optimise for low latency.
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
        -O2
        -ffast-math
        -fvisibility=hidden)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
