cmake_minimum_required(VERSION 3.22.1)
project(qmlbridge C)

add_library(qmlbridge SHARED jvm_launcher.c input_bridge.c)
target_include_directories(qmlbridge PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(qmlbridge PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
target_link_libraries(qmlbridge android log dl)
