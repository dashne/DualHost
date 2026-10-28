cmake_minimum_required(VERSION 3.22.1)
project("dualcore")

add_library(
        dualcore
        SHARED
        DualCoreJni.cpp
        elf_hooker.cpp
        RedirectTable.cpp
        Hooks.cpp
        InlineHooker.cpp
)

find_library(log-lib log)
target_link_libraries(dualcore ${log-lib} android)