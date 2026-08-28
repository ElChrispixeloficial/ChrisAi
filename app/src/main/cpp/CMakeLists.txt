cmake_minimum_required(VERSION 3.22.1)

project("chriscore")

add_library(
        chriscore
        SHARED
        chriscore.cpp)

target_link_libraries(
        chriscore
        android
        jnigraphics
        log)

# Small helper OS before 23 require the shared STL; we link statically so the
# APK does not carry libc++_shared.so.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -Oz -fvisibility=hidden -ffunction-sections -fdata-sections -fno-unwind-tables -fno-asynchronous-unwind-tables")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -s")