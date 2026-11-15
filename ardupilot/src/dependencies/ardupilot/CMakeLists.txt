include(ExternalProject)

set(ARDUPILOT_REPOSITORY "https://github.com/JonasVautherin/ardupilot.git"
    CACHE STRING "ArduPilot Git repository")
set(ARDUPILOT_REVISION "3071d93c622a2f178090c6bc15d8b576018ceb1d"
    CACHE STRING "ArduPilot Git revision (jonas/android-sitl)")

set(waf_command "${CMAKE_COMMAND}" -E chdir <SOURCE_DIR>
    "${CMAKE_COMMAND}" -E env
    "PATH=${ndk_bin}:$ENV{PATH}"
    "${Python3_EXECUTABLE}" <SOURCE_DIR>/waf)

ExternalProject_Add(
    ardupilot
    GIT_REPOSITORY "${ARDUPILOT_REPOSITORY}"
    GIT_TAG "${ARDUPILOT_REVISION}"
    GIT_SUBMODULES_RECURSE TRUE
    PREFIX ardupilot
    CONFIGURE_COMMAND ${waf_command} configure --board android
        --out <BINARY_DIR> --no-submodule-update --disable-tests
    BUILD_COMMAND ${waf_command} copter plane --out <BINARY_DIR>
    BUILD_ALWAYS TRUE
    BUILD_BYPRODUCTS <BINARY_DIR>/android/bin/arducopter <BINARY_DIR>/android/bin/arduplane
    INSTALL_COMMAND "${CMAKE_COMMAND}" -E make_directory "${CMAKE_INSTALL_PREFIX}/lib"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        <BINARY_DIR>/android/bin/arducopter "${CMAKE_INSTALL_PREFIX}/lib/libarducopter.so"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        <BINARY_DIR>/android/bin/arduplane "${CMAKE_INSTALL_PREFIX}/lib/libarduplane.so"
)
