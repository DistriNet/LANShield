
cmake_minimum_required(VERSION 3.22.1)

project("lanshield")

add_link_options("LINKER:--build-id=none")
add_compile_options("-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -g -fPIC")

set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../)
set(NDPI_ROOT ${ROOTDIR}/app/src/main/jni/ndpi)
include_directories(${NDPI_ROOT}/include ${NDPI_ROOT}/lib/third_party/include ndpi-generated)

message(${ROOTDIR})
message(${NDPI_ROOT})
add_definitions(-DNDPI_LIB_COMPILATION)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/lib ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/lib/third_party/src ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/lib/protocols ndpiSources)

#set(NDPI_GEN ${ROOTDIR}/generated/nDPI)
#configure_file(${NDPI_GEN}/ndpi_api.h ${NDPI_ROOT}/src/include/ndpi_api.h)
#configure_file(${NDPI_GEN}/ndpi_config.h ${NDPI_ROOT}/src/include/ndpi_config.h)
#configure_file(${NDPI_GEN}/ndpi_define.h ${NDPI_ROOT}/src/include/ndpi_define.h)

add_library(ndpi SHARED ${ndpiSources})


add_library(lanshield-dpi SHARED
        lanshield-dpi.c)

target_link_libraries(lanshield-dpi
        # List libraries link to the target library
        android
        ndpi
        log)
