# The pinned libnfs decodes FSID with an unaligned uint64_t pointer cast. Keep the
# upstream checkout pristine and generate corrected translation units.
set(nfs_v4_original "${CMAKE_CURRENT_SOURCE_DIR}/../vendor/libnfs/lib/nfs_v4.c")
file(READ "${nfs_v4_original}" nfs_v4_source)
set(unsafe_fsid "st->nfs_dev = ((uint64_t *)(void *)buf)[0] ^ ((uint64_t *)(void *)buf)[1];")
string(FIND "${nfs_v4_source}" "${unsafe_fsid}" fsid_position)
if(fsid_position EQUAL -1)
  message(FATAL_ERROR "Recheck the FSID alignment fix after updating libnfs")
endif()
string(REPLACE "${unsafe_fsid}" "uint64_t fsid_parts[2];\n                memcpy(fsid_parts, buf, sizeof(fsid_parts));\n                st->nfs_dev = fsid_parts[0] ^ fsid_parts[1];" nfs_v4_source "${nfs_v4_source}")

# libnfs's common NFS error callbacks conflate RPC timeouts with cancellation
# (-EINTR), and RPC failures with invalid addresses (-EFAULT). stat64's sync
# wrapper also reads nfs_get_error instead of the supplied callback text. Keep
# the original status and text meaningful before the sync wrapper/JNI sees them.
function(nfs_replace_checked variable expected original replacement)
  string(LENGTH "${${variable}}" before_length)
  string(REPLACE "${original}" "" removed "${${variable}}")
  string(LENGTH "${removed}" after_length)
  string(LENGTH "${original}" match_length)
  math(EXPR count "(${before_length} - ${after_length}) / ${match_length}")
  if(NOT count EQUAL expected)
    message(FATAL_ERROR "Recheck libnfs RPC error normalization: expected ${expected} matches, found ${count}")
  endif()
  string(REPLACE "${original}" "${replacement}" result "${${variable}}")
  set(${variable} "${result}" PARENT_SCOPE)
endfunction()
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/../vendor/libnfs/lib/nfs_v3.c" nfs_v3_source)
foreach(version 3 4)
  if(version EQUAL 3)
    set(expected 1)
    set(error_data "command_data")
  else()
    set(expected 2)
    set(error_data "res")
  endif()
  nfs_replace_checked(nfs_v${version}_source ${expected}
    "data->cb(-EINTR, nfs, \"Command timed out\","
    "nfs_set_error(nfs, \"NFS RPC timed out\");\n                data->cb(-ETIMEDOUT, nfs, nfs_get_error(nfs),")
  nfs_replace_checked(nfs_v${version}_source ${expected}
    "data->cb(-EFAULT, nfs, ${error_data}, data->private_data);"
    "nfs_set_error(nfs, \"NFS RPC failed: %s\", command_data ? (const char *)command_data : \"unknown error\");\n                data->cb(-EIO, nfs, nfs_get_error(nfs), data->private_data);")
  set(fixed_v${version} "${CMAKE_CURRENT_BINARY_DIR}/libnfs-nfs_v${version}.c")
  file(WRITE "${fixed_v${version}}.tmp" "${nfs_v${version}_source}")
  configure_file("${fixed_v${version}}.tmp" "${fixed_v${version}}" COPYONLY)
endforeach()
get_target_property(nfs_sources nfs SOURCES)
set_property(TARGET nfs PROPERTY SOURCES "")
foreach(source IN LISTS nfs_sources)
  if(source STREQUAL "nfs_v4.c")
    target_sources(nfs PRIVATE "${fixed_v4}")
  elseif(source STREQUAL "nfs_v3.c")
    target_sources(nfs PRIVATE "${fixed_v3}")
  elseif(IS_ABSOLUTE "${source}")
    target_sources(nfs PRIVATE "${source}")
  else()
    target_sources(nfs PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../vendor/libnfs/lib/${source}")
  endif()
endforeach()
if(CMAKE_C_COMPILER_ID MATCHES "Clang")
  # RPCGEN's historical variadic zdrproc_t callback ABI triggers this one UBSan
  # check throughout upstream code. Other sanitizers stay on; app/bridge function
  # pointer checks stay on. Do not globally suppress undefined behavior checks.
  target_compile_options(nfs PRIVATE -fno-sanitize=function)
endif()
