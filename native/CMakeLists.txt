cmake_minimum_required(VERSION 3.22)
project(world_native LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(FETCHCONTENT_SOURCE_DIR_CRC32C ${CMAKE_CURRENT_SOURCE_DIR}/vendor/crc32c CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_SNAPPY ${CMAKE_CURRENT_SOURCE_DIR}/vendor/snappy CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_ZSTD ${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_ZLIB ${CMAKE_CURRENT_SOURCE_DIR}/vendor/zlib CACHE PATH "" FORCE)
set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_UTILS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
# Build the vendored Zstd sources as the target expected by LevelDB.
file(GLOB ZSTD_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib/common/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib/compress/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib/decompress/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib/dictBuilder/*.c")
add_library(libzstd_static STATIC ${ZSTD_SOURCES})
target_include_directories(libzstd_static PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib")
target_compile_definitions(libzstd_static PRIVATE ZSTD_LEGACY_SUPPORT=0 ZSTD_DISABLE_ASM)
add_subdirectory(vendor/leveldb EXCLUDE_FROM_ALL)
# The vendored LevelDB enables Zstd, but its target does not export zstd's
# public header directory to its own compilation units on a clean CI build.
target_include_directories(leveldb PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib")
add_library(world_native SHARED bridge.cpp)
target_include_directories(world_native PRIVATE vendor/leveldb/include)
target_link_libraries(world_native PRIVATE leveldb log)
target_link_options(world_native PRIVATE "-Wl,-z,max-page-size=16384")

