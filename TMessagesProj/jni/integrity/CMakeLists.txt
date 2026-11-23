add_library(integrity STATIC
        integrity/integrity.cpp
        integrity/meth.cpp
        integrity/openat.c
        integrity/read_cert.cpp
        integrity/SHA1.cpp)
target_compile_options(integrity PRIVATE
        -Oz -Wall -fvisibility=hidden)
target_include_directories(integrity PUBLIC
        integrity)
