cmake_minimum_required(VERSION 3.22.1)
project("btdashboard_audio")

# Oboe is delivered as a prefab package by the com.google.oboe:oboe AAR.
find_package(oboe REQUIRED CONFIG)

add_library(btdashboard_audio SHARED
        ToneGenerator.cpp
        jni_bridge.cpp)

target_link_libraries(btdashboard_audio
        oboe::oboe
        log)
