cmake_minimum_required(VERSION 3.22.1)
project(macam_engine CXX)

add_library(macam SHARED engine.cpp)
target_compile_options(macam PRIVATE -O3 -ffast-math -fno-exceptions -Wall)
target_compile_features(macam PRIVATE cxx_std_17)
target_link_libraries(macam jnigraphics log)
