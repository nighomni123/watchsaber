cmake_minimum_required(VERSION 3.22.1)
project(lightsaber_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The mixer is shared with the host offline renderer (engine/host), so it lives in engine/ and is
# compiled from here rather than duplicated. A defect found by replay is then a defect in the code
# that actually ships on the watch.
set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/src/main/cpp)

# Oboe's prefab package is built against the shared C++ runtime. AGP passes ANDROID_STL as a CMake
# *cache* variable (c++_static), which a plain set() cannot override — hence CACHE ... FORCE. Without
# this the link fails with CXX1212 ("static STL but library requires a shared STL").
set(ANDROID_STL c++_shared CACHE STRING "C++ runtime" FORCE)

find_package(oboe REQUIRED CONFIG)

add_library(
  lightsaber_engine
  SHARED
  ${ENGINE_DIR}/mixer.cpp
  audio_engine.cpp
  jni_bridge.cpp
)

target_include_directories(
  lightsaber_engine
  PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${ENGINE_DIR}
)

target_link_libraries(
  lightsaber_engine
  PRIVATE
  oboe::oboe
  android
  log
)

target_compile_options(
  lightsaber_engine
  PRIVATE
  -Wall
  -Wextra
  -fvisibility=hidden
  -ffast-math
)