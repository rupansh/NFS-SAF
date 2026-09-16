cmake_minimum_required(VERSION 3.22)
project(fsx_bridge C)
add_executable(fsx_bridge bridge.c)
# Android extracts executable ELF files only from lib/<abi>/lib*.so in the test APK.
if(ANDROID)
  set_target_properties(fsx_bridge PROPERTIES OUTPUT_NAME libfsx PREFIX "" SUFFIX ".so")
  target_link_options(fsx_bridge PRIVATE -Wl,-z,max-page-size=16384)
endif()
