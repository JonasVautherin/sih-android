include(ExternalProject)

set(PX4_REPOSITORY "https://github.com/PX4/PX4-Autopilot.git" CACHE STRING "PX4 Git repository")
set(PX4_REVISION "v1.18.0-rc1" CACHE STRING "PX4 upstream tag or revision")
set(PX4_PATCH "${CMAKE_CURRENT_LIST_DIR}/android.patch" CACHE FILEPATH "Android patch; empty disables patching")

set(patch_command "${CMAKE_COMMAND}" -E true)
if(PX4_PATCH)
    find_program(PATCH_EXECUTABLE patch REQUIRED)
    set(patch_command "${CMAKE_COMMAND}"
        "-DSOURCE_DIR=<SOURCE_DIR>"
        "-DPATCH_FILE=${PX4_PATCH}"
        "-DPATCH_EXECUTABLE=${PATCH_EXECUTABLE}"
        -P "${CMAKE_CURRENT_LIST_DIR}/apply-patch.cmake")
endif()

ExternalProject_Add(
    px4
    GIT_REPOSITORY "${PX4_REPOSITORY}"
    GIT_TAG "${PX4_REVISION}"
    GIT_SUBMODULES_RECURSE TRUE
    UPDATE_DISCONNECTED TRUE
    PREFIX px4
    PATCH_COMMAND ${patch_command}
    CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E env GIT_SUBMODULES_ARE_EVIL=1
        "${CMAKE_COMMAND}" -S <SOURCE_DIR> -B <BINARY_DIR> -G Ninja
        "-DCMAKE_MAKE_PROGRAM=${NINJA_EXECUTABLE}"
        "-DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK}/build/cmake/android.toolchain.cmake"
        -DCONFIG=px4_sitl_sih-android
        -DCMAKE_BUILD_TYPE=Release
        -DANDROID_ABI=arm64-v8a
        -DANDROID_PLATFORM=android-28
        -DBUILD_TESTING=OFF
        -DUSE_LD_GOLD=OFF
        "-DPYTHON_EXECUTABLE=${Python3_EXECUTABLE}"
        "-DPython3_EXECUTABLE=${Python3_EXECUTABLE}"
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
    BUILD_COMMAND "${CMAKE_COMMAND}" -E env GIT_SUBMODULES_ARE_EVIL=1
        "${CMAKE_COMMAND}" --build <BINARY_DIR>
    BUILD_ALWAYS TRUE
    BUILD_BYPRODUCTS <BINARY_DIR>/bin/libpx4.so
    INSTALL_COMMAND "${CMAKE_COMMAND}" -E make_directory "${CMAKE_INSTALL_PREFIX}/lib"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different <BINARY_DIR>/bin/libpx4.so "${CMAKE_INSTALL_PREFIX}/lib/libpx4.so"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory <BINARY_DIR>/etc "${CMAKE_INSTALL_PREFIX}/share/px4/etc"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${CMAKE_INSTALL_PREFIX}/share/px4/bin"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        <BINARY_DIR>/bin/px4-alias.sh "${CMAKE_INSTALL_PREFIX}/share/px4/bin/px4-alias.sh"
)

if(PX4_PATCH)
    ExternalProject_Add_StepDependencies(px4 patch "${PX4_PATCH}" "${CMAKE_CURRENT_LIST_DIR}/apply-patch.cmake")
endif()
