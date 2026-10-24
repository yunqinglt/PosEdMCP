cmake_minimum_required(VERSION 3.22.1)

project(posednative C)

add_library(posednative SHARED posednative.c ad_dns.c concealment.c)

# liblog is how anything here reports; nothing else from the NDK is needed.
find_library(log-lib log)
target_link_libraries(posednative ${log-lib})
