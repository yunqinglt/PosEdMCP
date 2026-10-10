cmake_minimum_required(VERSION 3.22.1)

project(deploynative CXX)

# The analysis backend. For now a hand-written ELF header reader; LIEF links in
# here and replaces the body of nativeAnalyze without changing the JNI seam.
add_library(deploynative SHARED deploynative.cpp)

find_library(log-lib log)
target_link_libraries(deploynative ${log-lib})
