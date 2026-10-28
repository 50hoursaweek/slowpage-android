cmake_minimum_required(VERSION 3.22)
project(hushhour_audio CXX)

find_package(oboe REQUIRED CONFIG)

# The Rust mixer, built by scripts/build-rust.sh (soname libhushhour_ipad.so). Linking it here
# and loading it through JNA resolve to the same copy, so there is one MIXER static.
add_library(hushhour_ipad SHARED IMPORTED)
set_target_properties(hushhour_ipad PROPERTIES
    IMPORTED_LOCATION ${HUSHHOUR_SO_DIR}/${ANDROID_ABI}/libhushhour_ipad.so)

add_library(hushhour_audio SHARED audio.cpp)
target_compile_features(hushhour_audio PRIVATE cxx_std_17)
target_link_libraries(hushhour_audio oboe::oboe hushhour_ipad log)
target_link_options(hushhour_audio PRIVATE "-Wl,-z,max-page-size=16384")
