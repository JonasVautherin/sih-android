cmake_minimum_required(VERSION 3.22)

if(NOT EXISTS "${PATCH_FILE}")
    message(FATAL_ERROR "PX4 patch not found: ${PATCH_FILE}")
endif()

execute_process(
    COMMAND "${PATCH_EXECUTABLE}" --dry-run --batch --forward --fuzz=0 -p1 -i "${PATCH_FILE}"
    WORKING_DIRECTORY "${SOURCE_DIR}"
    RESULT_VARIABLE can_apply
    OUTPUT_VARIABLE output
    ERROR_VARIABLE error
)
if(can_apply EQUAL 0)
    execute_process(
        COMMAND "${PATCH_EXECUTABLE}" --batch --forward --fuzz=0 -p1 -i "${PATCH_FILE}"
        WORKING_DIRECTORY "${SOURCE_DIR}"
        COMMAND_ERROR_IS_FATAL ANY
    )
else()
    execute_process(
        COMMAND "${PATCH_EXECUTABLE}" --dry-run --batch --reverse --force --fuzz=0 -p1 -i "${PATCH_FILE}"
        WORKING_DIRECTORY "${SOURCE_DIR}"
        RESULT_VARIABLE already_applied
        OUTPUT_QUIET ERROR_QUIET
    )
    if(already_applied EQUAL 0)
        message(STATUS "PX4 Android patch already applied")
    else()
        message(FATAL_ERROR "PX4 patch does not apply cleanly. Use a fresh dependency build directory after changing the patch or revision.\n${output}\n${error}")
    endif()
endif()
